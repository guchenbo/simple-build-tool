/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah
 */
package sbt

import java.io.File
import java.net.URL
import java.util.Collections
import scala.collection.mutable.HashSet

import org.apache.ivy.{core, plugins, util, Ivy}
import core.LogOptions
import core.deliver.DeliverOptions
import core.module.descriptor.{DefaultArtifact, DefaultDependencyDescriptor, DefaultModuleDescriptor, MDArtifact, ModuleDescriptor}
import core.module.descriptor.{DefaultExcludeRule, ExcludeRule}
import core.module.id.{ArtifactId,ModuleId, ModuleRevisionId}
import core.publish.PublishOptions
import core.resolve.ResolveOptions
import core.retrieve.RetrieveOptions
import core.settings.IvySettings
import plugins.matcher.{ExactPatternMatcher, PatternMatcher}
import plugins.parser.ModuleDescriptorParser
import plugins.parser.m2.{PomModuleDescriptorParser,PomModuleDescriptorWriter}
import plugins.parser.xml.XmlModuleDescriptorParser
import plugins.repository.{BasicResource, Resource}
import plugins.repository.url.URLResource
import plugins.resolver.{DependencyResolver, ChainResolver, IBiblioResolver}
import util.{Message, MessageLogger}

final class IvyScala(val scalaVersion: String, val configurations: Iterable[Configuration], val checkExplicit: Boolean, val filterImplicit: Boolean) extends NotNull
final class IvyPaths(val projectDirectory: Path, val managedLibDirectory: Path, val cacheDirectory: Option[Path]) extends NotNull
final class IvyFlags(val validate: Boolean, val addScalaTools: Boolean, val errorIfNoConfiguration: Boolean) extends NotNull
final class IvyConfiguration(val paths: IvyPaths, val manager: Manager, val flags: IvyFlags, val ivyScala: Option[IvyScala], val log: Logger) extends NotNull
final class UpdateConfiguration(val outputPattern: String, val synchronize: Boolean, val quiet: Boolean) extends NotNull
object ManageDependencies
{
	val DefaultIvyConfigFilename = "ivysettings.xml"
	val DefaultIvyFilename = "ivy.xml"
	val DefaultMavenFilename = "pom.xml"
	val ScalaOrganization = "org.scala-lang"
	val ScalaLibraryID = "scala-library"
	val ScalaCompilerID = "scala-compiler"
	
	private def defaultIvyFile(project: Path) = project / DefaultIvyFilename
	private def defaultIvyConfiguration(project: Path) = project / DefaultIvyConfigFilename
	private def defaultPOM(project: Path) = project / DefaultMavenFilename
	
	/** Configures Ivy using the provided configuration 'config' and calls 'doWithIvy'.  This method takes care of setting up and cleaning up Ivy.*/
	private def withIvy(config: IvyConfiguration)(doWithIvy: (Ivy, ModuleDescriptor, String) => Option[String]) =
		withIvyValue(config)( (ivy, module, default) => doWithIvy(ivy, module, default).toLeft(()) ).left.toOption
	/** Configures Ivy using the provided configuration 'config' and calls 'doWithIvy'.  This method takes care of setting up and cleaning up Ivy.*/
	private def withIvyValue[T](config: IvyConfiguration)(doWithIvy: (Ivy, ModuleDescriptor, String) => Either[String, T]) =
	{
		import config._
		val logger = new IvyLogger(log)
		Message.setDefaultLogger(logger)
		val ivy = Ivy.newInstance()
		ivy.getLoggerEngine.pushLogger(logger)
		
		/** Parses the given Maven pom 'pomFile'.*/
		def readPom(pomFile: File) =
			Control.trap("Could not read pom: ", log)
				{ Right((PomModuleDescriptorParser.getInstance.parseDescriptor(ivy.getSettings, toURL(pomFile), flags.validate)), "compile") }
		/** Parses the given Ivy file 'ivyFile'.*/
		def readIvyFile(ivyFile: File) =
			Control.trap("Could not read Ivy file: ", log)
			{
				val url = toURL(ivyFile)
				val parser = new CustomXmlParser.CustomParser(ivy.getSettings)
				parser.setValidate(flags.validate)
				parser.setSource(url)
				parser.parse()
				Right((parser.getModuleDescriptor(), parser.getDefaultConf))
			}
		/** Parses the given in-memory Ivy file 'xml', using the existing 'moduleID' and specifying the given 'defaultConfiguration'. */
		def parseXMLDependencies(xml: scala.xml.NodeSeq, moduleID: DefaultModuleDescriptor, defaultConfiguration: String) =
			parseDependencies(xml.toString, moduleID, defaultConfiguration)
		/** Parses the given in-memory Ivy file 'xml', using the existing 'moduleID' and specifying the given 'defaultConfiguration'. */
		def parseDependencies(xml: String, moduleID: DefaultModuleDescriptor, defaultConfiguration: String): Either[String, CustomXmlParser.CustomParser] =
			Control.trap("Could not read dependencies: ", log)
			{
				val parser = new CustomXmlParser.CustomParser(ivy.getSettings)
				parser.setMd(moduleID)
				parser.setDefaultConf(defaultConfiguration)
				parser.setValidate(flags.validate)
				val resource = new ByteResource(xml.getBytes)
				parser.setInput(resource.openStream)
				parser.setResource(resource)
				parser.parse()
				Right(parser)
			}
		/** Configures Ivy using the specified Ivy configuration file.  This method is used when the manager is explicitly requested to be MavenManager or
		* IvyManager.  If a file is not specified, Ivy is configured with defaults and scala-tools releases is added as a repository.*/
		def configure(configFile: Option[Path])
		{
			configFile match
			{
				case Some(path) => ivy.configure(path.asFile)
				case None =>
					configureDefaults()
					scalaTools()
			}
		}
		/** Adds the scala-tools.org releases maven repository to the list of resolvers if configured to do so in IvyFlags.*/
		def scalaTools()
		{
			if(flags.addScalaTools)
			{
				log.debug("Added Scala Tools Releases repository.")
				addResolvers(ivy.getSettings, ScalaToolsReleases :: Nil, log)
			}
		}
		/** Configures Ivy using defaults.  This is done when no ivy-settings.xml exists and no inline configurations or resolvers are specified. */
		def configureDefaults()
		{
			ivy.configureDefault
			val settings = ivy.getSettings
			for(dir <- paths.cacheDirectory) settings.setDefaultCache(dir.asFile)
			settings.setBaseDir(paths.projectDirectory.asFile)
		}
		/** Called to configure Ivy when the configured dependency manager is SbtManager and inline configuration is specified or if the manager
		* is AutodetectManager.  It will configure Ivy with an 'ivy-settings.xml' file if there is one, or configure the defaults and add scala-tools as
		* a repository otherwise.*/
		def autodetectConfiguration()
		{
			log.debug("Autodetecting configuration.")
			val defaultIvyConfigFile = defaultIvyConfiguration(paths.projectDirectory).asFile
			if(defaultIvyConfigFile.canRead)
				ivy.configure(defaultIvyConfigFile)
			else
			{
				configureDefaults()
				scalaTools()
			}
		}
		/** Called to determine dependencies when the dependency manager is SbtManager and no inline dependencies (Scala or XML) are defined
		* or if the manager is AutodetectManager.  It will try to read from pom.xml first and then ivy.xml if pom.xml is not found.  If neither is found,
		* Ivy is configured with defaults unless IvyFlags.errorIfNoConfiguration is true, in which case an error is generated.*/
		def autodetectDependencies(module: ModuleRevisionId) =
		{
			log.debug("Autodetecting dependencies.")
			val defaultPOMFile = defaultPOM(paths.projectDirectory).asFile
			if(defaultPOMFile.canRead)
				readPom(defaultPOMFile)
			else
			{
				val defaultIvy = defaultIvyFile(paths.projectDirectory).asFile
				if(defaultIvy.canRead)
					readIvyFile(defaultIvy)
				else if(flags.errorIfNoConfiguration)
					Left("No readable dependency configuration found.  Need " + DefaultIvyFilename + " or " + DefaultMavenFilename)
				else
				{
					val defaultConf = ModuleDescriptor.DEFAULT_CONFIGURATION
					log.warn("No readable dependency configuration found, using defaults.")
					val moduleID = DefaultModuleDescriptor.newDefaultInstance(module)
					addMainArtifact(moduleID)
					addDefaultArtifact(defaultConf, moduleID)
					Right((moduleID, defaultConf))
				}
			}
		}
		/** Creates an Ivy module descriptor according the manager configured.  The default configuration for dependencies
		* is also returned.*/
		def moduleDescriptor: Either[String, (ModuleDescriptor, String)] =
			config.manager match
			{
				case mm: MavenManager =>
				{
					log.debug("Maven configuration explicitly requested.")
					configure(mm.configuration)
					readPom(mm.pom.asFile)
				}
				case im: IvyManager =>
				{
					log.debug("Ivy configuration explicitly requested.")
					configure(im.configuration)
					readIvyFile(im.dependencies.asFile)
				}
				case adm: AutoDetectManager =>
				{
					log.debug("No dependency manager explicitly specified.")
					autodetectConfiguration()
					autodetectDependencies(toID(adm.module))
				}
				case sm: SbtManager =>
				{
					import sm._
					if(resolvers.isEmpty && autodetectUnspecified)
						autodetectConfiguration()
					else
					{
						log.debug("Using inline configuration.")
						configureDefaults()
						val extra = if(flags.addScalaTools) ScalaToolsReleases :: resolvers.toList else resolvers
						addResolvers(ivy.getSettings, extra, log)
					}
					if(dependencies.isEmpty && dependenciesXML.isEmpty && autodetectUnspecified)
						autodetectDependencies(toID(module))
					else
					{
						val moduleID =
							{
								val mod = new DefaultModuleDescriptor(toID(module), "release", null, false)
								mod.setLastModified(System.currentTimeMillis)
								configurations.foreach(config => mod.addConfiguration(config.toIvyConfiguration))
								mod
							}
						val defaultConf = defaultConfiguration getOrElse Configurations.config(ModuleDescriptor.DEFAULT_CONFIGURATION)
						log.debug("Using inline dependencies specified in Scala" + (if(dependenciesXML.isEmpty) "." else " and XML."))
						for(parser <- parseXMLDependencies(wrapped(module, dependenciesXML), moduleID, defaultConf.name).right) yield
						{
							addDependencies(moduleID, dependencies, parser)
							addMainArtifact(moduleID)
							(moduleID, parser.getDefaultConf)
						}
					}
				}
			}
		/** Creates a full ivy file for 'module' using the 'dependencies' XML as the part after the %lt;info&gt;...%lt;/info&gt; section. */
		def wrapped(module: ModuleID, dependencies: scala.xml.NodeSeq) =
		{
			import module._
			<ivy-module version="2.0">
				<info organisation={organization} module={name} revision={revision}/>
				{dependencies}
			</ivy-module>
		}
		/** Performs checks/adds filters on Scala dependencies (if enabled in IvyScala). */
		def checkModule(moduleAndConf: (ModuleDescriptor, String)): Either[String, (ModuleDescriptor, String)] =
			ivyScala match
			{
				case Some(check) =>
					val (module, conf) = moduleAndConf
					val explicitCheck =
						if(check.checkExplicit)
							checkDependencies(module, check.scalaVersion, check.configurations)
						else
							None
					explicitCheck match
					{
						case None =>
							if(check.filterImplicit)
							{
								val asDefault = toDefaultModuleDescriptor(module)
								excludeScalaJars(asDefault, check.configurations)
								Right( (asDefault, conf) )
							}
							else
								Right(moduleAndConf)
						case Some(err) => Left(err)
					}
				case None => Right(moduleAndConf)
			}
		
		this.synchronized // Ivy is not thread-safe.  In particular, it uses a static DocumentBuilder, which is not thread-safe
		{
			ivy.pushContext()
			try
			{
				moduleDescriptor.right.flatMap(checkModule).right.flatMap { mdAndConf =>
					doWithIvy(ivy, mdAndConf._1, mdAndConf._2)
				}
			}
			finally { ivy.popContext() }
		}
	}
	/** Checks the immediate dependencies of module for dependencies on scala jars and verifies that the version on the
	* dependencies matches scalaVersion. */
	private def checkDependencies(module: ModuleDescriptor, scalaVersion: String, configurations: Iterable[Configuration]): Option[String] =
	{
		val configSet =configurationSet(configurations)
		Control.lazyFold(module.getDependencies.toList)
		{ dep =>
			val id = dep.getDependencyRevisionId
			if(id.getOrganisation == ScalaOrganization && id.getRevision != scalaVersion && dep.getModuleConfigurations.exists(configSet.contains))
				Some("Different Scala version specified in dependency ("+ id.getRevision + ") than in project (" + scalaVersion + ").")
			else
				None
		}
	}
	private def configurationSet(configurations: Iterable[Configuration]) =
		HashSet(configurations.map(_.toString).toSeq : _*)
	/** Adds exclusions for the scala library and compiler jars so that they are not downloaded.  This is
	* done because normally these jars are already on the classpath and cannot/should not be overridden.  The version
	* of Scala to use is done by setting scala.version in the project definition. */
	private def excludeScalaJars(module: DefaultModuleDescriptor, configurations: Iterable[Configuration])
	{
		val configurationNames =
		{
			val names = module.getConfigurationsNames
			if(configurations.isEmpty)
				names
			else
			{
				import scala.collection.mutable.HashSet
				val configSet = configurationSet(configurations)
				configSet.intersect(HashSet(names : _*))
				configSet.toArray
			}
		}
		def excludeScalaJar(name: String)
			{ module.addExcludeRule(excludeRule(ScalaOrganization, name, configurationNames)) }
		excludeScalaJar(ScalaLibraryID)
		excludeScalaJar(ScalaCompilerID)
	}
	/** Creates an ExcludeRule that excludes artifacts with the given module organization and name for
	* the given configurations. */
	private def excludeRule(organization: String, name: String, configurationNames: Iterable[String]): ExcludeRule =
	{
		val artifact = new ArtifactId(ModuleId.newInstance(organization, name), "*", "*", "*")
		val rule = new DefaultExcludeRule(artifact, ExactPatternMatcher.INSTANCE, Collections.emptyMap[AnyRef,AnyRef])
		configurationNames.foreach(rule.addConfiguration)
		rule
	}
	/** Clears the Ivy cache, as configured by 'config'. */
	def cleanCache(config: IvyConfiguration) =
	{
		def doClean(ivy: Ivy, module: ModuleDescriptor, default: String) =
			Control.trapUnit("Could not clean cache: ", config.log)
				{ ivy.getSettings.getRepositoryCacheManagers.foreach(_.clean()); None }
		
		withIvy(config)(doClean)
	}
	/** Creates a Maven pom from the given Ivy configuration*/
	def makePom(config: IvyConfiguration, extraDependencies: Iterable[ModuleID], output: File) =
	{
		def doMakePom(ivy: Ivy, md: ModuleDescriptor, default: String) =
			Control.trapUnit("Could not make pom: ", config.log)
			{
				val module = addLateDependencies(ivy, md, Configurations.Compile.name, extraDependencies)
				PomModuleDescriptorWriter.write(module, DefaultConfigurationMapping, output)
				None
			}
		withIvy(config)(doMakePom)
	}
	private def addDefaultArtifact(defaultConf: String, moduleID: DefaultModuleDescriptor) =
		moduleID.addArtifact(defaultConf, new MDArtifact(moduleID, moduleID.getModuleRevisionId.getName, "jar", "jar"))
	// todo: correct default configuration for extra dependencies
	private def addLateDependencies(ivy: Ivy, md: ModuleDescriptor, defaultConfiguration: String, extraDependencies: Iterable[ModuleID]) =
	{
		val module = toDefaultModuleDescriptor(md)
		val parser = new CustomXmlParser.CustomParser(ivy.getSettings)
		parser.setMd(module)
		parser.setDefaultConf("compile->default")//defaultConfiguration)
		addDependencies(module, extraDependencies, parser)
		module
	}
	private def addConfigurations(configurations: Option[Iterable[Configuration]], to: { def setConfs(c: Array[String]): AnyRef })
		{ for(confs <- configurations) { to.setConfs(confs.map(_.name).toList.toArray) } }
	def deliver(ivyConfig: IvyConfiguration, updateConfig: UpdateConfiguration, status: String, deliverIvyPattern: String, extraDependencies: Iterable[ModuleID], configurations: Option[Iterable[Configuration]]) =
	{
		def doDeliver(ivy: Ivy, md: ModuleDescriptor, default: String) =
			Control.trapUnit("Could not deliver: ", ivyConfig.log)
			{
				val module = addLateDependencies(ivy, md, default, extraDependencies)
				resolve(ivy, updateConfig, module) orElse
				{
					val revID = module.getModuleRevisionId
					val options = DeliverOptions.newInstance(ivy.getSettings).setStatus(status)
					addConfigurations(configurations, options)
					
					ivy.deliver(revID, revID.getRevision, deliverIvyPattern, options)
					None
				}
			}
		withIvy(ivyConfig)(doDeliver)
	}
	// todo: map configurations, extra dependencies
	def publish(ivyConfig: IvyConfiguration, resolverName: String, srcArtifactPatterns: Iterable[String], deliveredIvyPattern: String, configurations: Option[Iterable[Configuration]]) =
	{
		def doPublish(ivy: Ivy, md: ModuleDescriptor, default: String) =
			Control.trapUnit("Could not publish: ", ivyConfig.log)
			{
				val revID = md.getModuleRevisionId
				val patterns = new java.util.ArrayList[String]
				srcArtifactPatterns.foreach(pattern => patterns.add(pattern))
				val options = (new PublishOptions).setSrcIvyPattern(deliveredIvyPattern).setOverwrite(true)
				addConfigurations(configurations, options)
				ivy.publish(revID, patterns, resolverName, options)
				None
			}
		withIvy(ivyConfig)(doPublish)
	}
	/** Resolves and retrieves dependencies.  'ivyConfig' is used to produce an Ivy file and configuration.
	* 'updateConfig' configures the actual resolution and retrieval process. */
	def update(ivyConfig: IvyConfiguration, updateConfig: UpdateConfiguration) =
	{
		def processModule(ivy: Ivy, module: ModuleDescriptor, default: String) =
		{
			import updateConfig._
			Control.trapUnit("Could not process dependencies: ", ivyConfig.log)
			{
				resolve(ivy, updateConfig, module) orElse
				{
					val retrieveOptions = new RetrieveOptions
					retrieveOptions.setSync(synchronize)
					val patternBase = ivyConfig.paths.managedLibDirectory.asFile.getCanonicalPath
					val pattern =
						if(patternBase.endsWith(File.separator))
							patternBase + outputPattern
						else
							patternBase + File.separatorChar + outputPattern
					ivy.retrieve(module.getModuleRevisionId, pattern, retrieveOptions)
					None
				}
			}
		}
		
		withIvy(ivyConfig)(processModule)
	}
	private def resolve(ivy: Ivy, updateConfig: UpdateConfiguration, module: ModuleDescriptor) =
	{
		import updateConfig._
		val resolveOptions = new ResolveOptions
		if(quiet)
			resolveOptions.setLog(LogOptions.LOG_DOWNLOAD_ONLY)
		val resolveReport = ivy.resolve(module, resolveOptions)
		if(resolveReport.hasError)
			Some(Set(resolveReport.getAllProblemMessages.toArray: _*).mkString(System.getProperty("line.separator")))
		else
			None
	}
	/** This method is used to add inline dependencies to the provided module. */
	private def addDependencies(moduleID: DefaultModuleDescriptor, dependencies: Iterable[ModuleID], parser: CustomXmlParser.CustomParser)
	{
		for(dependency <- dependencies)
		{
			//println("Adding dependency " + dependency + " (default:  " + parser.getDefaultConf + ")")
			val dependencyDescriptor = new DefaultDependencyDescriptor(moduleID, toID(dependency), false, false, true)
			dependency.configurations match
			{
				case None => // The configuration for this dependency was not explicitly specified, so use the default
					parser.parseDepsConfs(parser.getDefaultConf, dependencyDescriptor)
				case Some(confs) => // The configuration mapping (looks like: test->default) was specified for this dependency
					parser.parseDepsConfs(confs, dependencyDescriptor)
			}
			moduleID.addDependency(dependencyDescriptor)
		}
	}
	private def toURL(file: File) = file.toURI.toURL
	/** Adds the ivy.xml main artifact. */
	private def addMainArtifact(moduleID: DefaultModuleDescriptor)
	{
		val artifact = DefaultArtifact.newIvyArtifact(moduleID.getResolvedModuleRevisionId, moduleID.getPublicationDate)
		moduleID.setModuleArtifact(artifact)
		moduleID.check()
	}
	/** Sets the resolvers for 'settings' to 'resolvers'.  This is done by creating a new chain and making it the default. */
	private def addResolvers(settings: IvySettings, resolvers: Iterable[Resolver], log: Logger)
	{
		val newDefault = new ChainResolver
		newDefault.setName("redefined-public")
		resolvers.foreach(r => newDefault.add(getResolver(r)))
		newDefault.add(settings.getDefaultResolver)
		settings.addResolver(newDefault)
		settings.setDefaultResolver(newDefault.getName)
		if(log.atLevel(Level.Debug))
		{
			log.debug("Using extra repositories:")
			resolvers.foreach(r => log.debug("\t" + r.toString))
		}
	}
	/** Converts the given sbt resolver into an Ivy resolver..*/
	private def getResolver(r: Resolver) =
		r match
		{
			case repo: MavenRepository =>
			{
				val resolver = new IBiblioResolver
				resolver.setName(repo.name)
				resolver.setM2compatible(true)
				resolver.setChangingPattern(""".*\-SNAPSHOT""")
				resolver.setRoot(repo.root)
				resolver
			}
		}
	/** Converts the given sbt module id into an Ivy ModuleRevisionId.*/
	private def toID(m: ModuleID) =
	{
		import m._
		ModuleRevisionId.newInstance(organization, name, revision)
	}
	/** An implementation of Ivy's Resource class that provides the Ivy file from a byte array.  This is used to support
	* inline Ivy file XML.*/
	private class ByteResource(bytes: Array[Byte]) extends
		BasicResource("Inline XML dependencies", true, bytes.length, System.currentTimeMillis, true)
	{
		override def openStream = new java.io.ByteArrayInputStream(bytes)
	}
	/** Subclasses the default Ivy file parser in order to provide access to protected methods.*/
	private object CustomXmlParser extends XmlModuleDescriptorParser with NotNull
	{
		import XmlModuleDescriptorParser.Parser
		class CustomParser(settings: IvySettings) extends Parser(CustomXmlParser, settings) with NotNull
		{
			def setSource(url: URL) =
			{
				super.setResource(new URLResource(url))
				super.setInput(url)
			}
			/** Overridden because the super implementation overwrites the module descriptor.*/
			override def setResource(res: Resource) {}
			override def setMd(md: DefaultModuleDescriptor) = super.setMd(md)
			override def parseDepsConfs(confs: String, dd: DefaultDependencyDescriptor) = super.parseDepsConfs(confs, dd)
			override def getDefaultConf = super.getDefaultConf
			override def setDefaultConf(conf: String) = super.setDefaultConf(conf)
		}
	}
	/** This code converts the given ModuleDescriptor to a DefaultModuleDescriptor by casting or generating an error.
	* Ivy always produces a DefaultModuleDescriptor, so this should be reasonable. */
	private def toDefaultModuleDescriptor(md: ModuleDescriptor) =
		md match
		{
			case dmd: DefaultModuleDescriptor => dmd
			case _ => error("Unknown ModuleDescriptor type.")
		}
}

private object DefaultConfigurationMapping extends PomModuleDescriptorWriter.ConfigurationScopeMapping(new java.util.HashMap)
{
	override def getScope(confs: Array[String]) =
	{
		Configurations.defaultMavenConfigurations.find(conf => confs.contains(conf.name)) match
		{
			case Some(conf) => conf.name
			case None =>
				if(confs.isEmpty || confs(0) == Configurations.Default.name)
					null
				else
					confs(0)
		}
	}
	override def isOptional(confs: Array[String]) = confs.isEmpty || (confs.length == 1 && confs(0) == Configurations.Optional.name)
}

sealed abstract class Manager extends NotNull
/** This explicitly requests auto detection as a dependency manager.  It will first check for a 'pom.xml' file and if that does not exist, an 'ivy.xml' file.
* Ivy is configured using the detected file or uses defaults.*/
final class AutoDetectManager(val module: ModuleID) extends Manager
/** This explicitly requests that the Maven pom 'pom' be used to determine dependencies.  An Ivy configuration file to use may be specified in
* 'configuration', since Ivy currently cannot extract Maven repositories from a pom file. Otherwise, defaults are used.*/
final class MavenManager(val configuration: Option[Path], val pom: Path) extends Manager
/** This explicitly requests that the Ivy file 'dependencies' be used to determine dependencies.  An Ivy configuration file to use may be specified in
* 'configuration'.  Otherwise, defaults are used.*/
final class IvyManager(val configuration: Option[Path], val dependencies: Path) extends Manager
/** This manager directly specifies the dependencies, resolvers, and configurations through sbt wrapper classes and through an in-memory
* Ivy XML file. */
sealed trait SbtManager extends Manager
{
	def module: ModuleID
	def resolvers: Iterable[Resolver]
	def dependencies: Iterable[ModuleID]
	def autodetectUnspecified: Boolean
	def dependenciesXML: scala.xml.NodeSeq
	def configurations: Iterable[Configuration]
	def defaultConfiguration: Option[Configuration]
}
final class SimpleManager private[sbt] (val dependenciesXML: scala.xml.NodeSeq, val autodetectUnspecified: Boolean,
	val module: ModuleID, val resolvers: Iterable[Resolver], val configurations: Iterable[Configuration],
	val defaultConfiguration: Option[Configuration], val dependencies: ModuleID*) extends SbtManager

final case class ModuleID(organization: String, name: String, revision: String, configurations: Option[String]) extends NotNull
{
	def this(organization: String, name: String, revision: String) = this(organization, name, revision, None)
	override def toString = organization + ":" + name + ":" + revision
}
sealed trait Resolver extends NotNull
{
	def name: String
}
sealed case class MavenRepository(name: String, root: String) extends Resolver
{
	override def toString = name + ": " + root
}
import Resolver._
object ScalaToolsReleases extends MavenRepository(ScalaToolsReleasesName, ScalaToolsReleasesRoot)
object ScalaToolsSnapshots extends MavenRepository(ScalaToolsSnapshotsName, ScalaToolsSnapshotsRoot)
object DefaultMavenRepository extends MavenRepository("Maven2 Repository", IBiblioResolver.DEFAULT_M2_ROOT)

object Resolver
{
	val ScalaToolsReleasesName = "Scala-Tools Maven2 Repository"
	val ScalaToolsSnapshotsName = "Scala-Tools Maven2 Snapshots Repository"
	val ScalaToolsReleasesRoot = "http://scala-tools.org/repo-releases"
	val ScalaToolsSnapshotsRoot = "http://scala-tools.org/repo-snapshots"
}
/** Represents an Ivy configuration. */
final class Configuration(val name: String, val description: String, val isPublic: Boolean, val extendsConfigs: List[Configuration], val transitive: Boolean) extends NotNull {
	require(name != null && !name.isEmpty)
	require(description != null)
	def this(name: String) = this(name, "", true, Nil, true)
	def describedAs(newDescription: String) = new Configuration(name, newDescription, isPublic, extendsConfigs, transitive)
	def extend(configs: Configuration*) = new Configuration(name, description, isPublic, configs.toList ::: extendsConfigs, transitive)
	def notTransitive = new Configuration(name, description, isPublic, extendsConfigs, false)
	def hide = new Configuration(name, description, false, extendsConfigs, transitive)
	override def toString = name
	import org.apache.ivy.core.module.descriptor.{Configuration => IvyConfig}
	import IvyConfig.Visibility._
	def toIvyConfiguration = new IvyConfig(name, if(isPublic) PUBLIC else PRIVATE, description, extendsConfigs.map(_.name).toArray, transitive, null)
}
object Configurations
{
	def config(name: String) = new Configuration(name)
	def defaultMavenConfigurations = Compile :: Runtime :: Test :: Provided :: System :: Optional :: Sources :: Javadoc :: Nil
	
	lazy val Default = config("default")
	lazy val Compile = config("compile")
	lazy val IntegrationTest = config("it")
	lazy val Provided = config("provided")
	lazy val Javadoc = config("javadoc")
	lazy val Runtime = config("runtime")
	lazy val Test = config("test") hide
	lazy val Sources = config("sources")
	lazy val System = config("system")
	lazy val Optional = config("optional")

	lazy val CompilerPlugin = config("plugin")
	
	private[sbt] def removeDuplicates(configs: Iterable[Configuration]) = Set(scala.collection.mutable.Map(configs.map(config => (config.name, config)).toSeq: _*).values.toList: _*)
}
/** Interface between Ivy logging and sbt logging. */
private final class IvyLogger(log: Logger) extends MessageLogger
{
	private var progressEnabled = false
	
	def log(msg: String, level: Int)
	{
		import Message.{MSG_DEBUG, MSG_VERBOSE, MSG_INFO, MSG_WARN, MSG_ERR}
		level match
		{
			case MSG_DEBUG | MSG_VERBOSE => debug(msg)
			case MSG_INFO => info(msg)
			case MSG_WARN => warn(msg)
			case MSG_ERR => error(msg)
		}
	}
	def rawlog(msg: String, level: Int)
	{
		log(msg, level)
	}
	import Level.{Debug, Info, Warn, Error}
	def debug(msg: String) = logImpl(msg, Debug)
	def verbose(msg: String) = debug(msg)
	def deprecated(msg: String) = warn(msg)
	def info(msg: String) = logImpl(msg, Info)
	def rawinfo(msg: String) = info(msg)
	def warn(msg: String) = logImpl(msg, Warn)
	def error(msg: String) = logImpl(msg, Error)
	
	private def logImpl(msg: String, level: Level.Value) = log.log(level, msg)
	
	private def emptyList = java.util.Collections.emptyList[T forSome { type T}]
	def getProblems = emptyList
	def getWarns = emptyList
	def getErrors = emptyList

	def clearProblems = ()
	def sumupProblems = ()
	def progress = ()
	def endProgress = ()

	def endProgress(msg: String) = info(msg)
	def isShowProgress = false
	def setShowProgress(progress: Boolean) {}
}
