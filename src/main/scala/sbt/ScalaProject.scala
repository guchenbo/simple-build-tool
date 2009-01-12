/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah, David MacIver
 */
package sbt

import FileUtilities._
import java.io.File
import java.util.jar.{Attributes, Manifest}

trait ScalaProject extends Project with FileTasks
{
	import ScalaProject._
	
	def errorTask(message: String) = task{ Some(message) }
	
	trait ActionOption extends NotNull
	
	case class CompileOption(val asString: String) extends ActionOption
	trait PackageOption extends ActionOption
	trait TestOption extends ActionOption
	trait CleanOption extends ActionOption
	case class ClearAnalysis(analysis: TaskAnalysis[_, _, _]) extends CleanOption
	
	case class ExcludeTests(tests: Iterable[String]) extends TestOption
	
	case class ManifestOption(m: Manifest) extends PackageOption
	{
		assert(m != null)
	}
	case class MainClassOption(mainClassName: String) extends PackageOption
	case object Recursive extends PackageOption
	
	val Deprecation = CompileOption("-deprecation")
	val ExplainTypes = CompileOption("-explaintypes")
	val Optimize = CompileOption("-optimise")
	def Optimise = Optimize
	val Verbose = CompileOption("-verbose")
	val Unchecked = CompileOption("-unchecked")
	val DisableWarnings = CompileOption("-nowarn")
	def target(target: Target.Value) = CompileOption("-target:" + target)
	object Target extends Enumeration
	{
		val Java1_5 = Value("jvm-1.5")
		val Java1_4 = Value("jvm-1.4")
		val Msil = Value("msil")
	}
	
	trait ScaladocOption extends ActionOption
	{
		def asList: List[String]
	}
	case class SimpleDocOption(optionValue: String) extends ScaladocOption
	{
		def asList = List(optionValue)
	}
	case class CompoundDocOption(label: String, value: String) extends ScaladocOption
	{
		def asList = List(label, value)
	}
	val LinkSource = SimpleDocOption("-linksource")
	val NoComment = SimpleDocOption("-nocomment")
	def access(access: Access.Value) = SimpleDocOption("-access:" + access)
	def documentBottom(bottomText: String) = CompoundDocOption("-bottom", bottomText)
	def documentCharset(charset: String) = CompoundDocOption("-charset", charset)
	def documentTitle(title: String) = CompoundDocOption("-doctitle", title)
	def documentFooter(footerText: String) = CompoundDocOption("-footer", footerText)
	def documentHeader(headerText: String) = CompoundDocOption("-header", headerText)
	def stylesheetFile(path: Path) = CompoundDocOption("-stylesheetfile", path.asFile.getAbsolutePath)
	def documentTop(topText: String) = CompoundDocOption("-top", topText)
	def windowTitle(title: String) = CompoundDocOption("-windowtitle", title)
	
	object Access extends Enumeration
	{
		val Public = Value("public")
		val Default = Value("protected")
		val Private = Value("private")
	}

	def consoleTask(classpath : PathFinder): Task = 
		interactiveTask { Run.console(classpath.get, log) }

	def runTask(mainClass: Option[String], classpath: PathFinder, options: String*): Task =
		runTask(mainClass, classpath, options)
	def runTask(mainClass: Option[String], classpath: PathFinder, options: => Seq[String]): Task =
		interactiveTask { Run(mainClass, classpath.get, options, log) }

	def cleanTask(paths: PathFinder, options: CleanOption*): Task =
		cleanTask(paths, options)
	def cleanTask(paths: PathFinder, options: => Seq[CleanOption]): Task =
		task
		{
			val pathClean = FileUtilities.clean(paths.get, log)
			for(ClearAnalysis(analysis) <- options)
			{
				analysis.clear()
				analysis.save()
			}
			pathClean
		}
		
	def copyTask(sources: PathFinder, destinationDirectory: Path): Task =
		task { FileUtilities.copy(sources.get, destinationDirectory, log).left.toOption }

	def testTask(frameworks: Iterable[TestFramework], classpath: PathFinder, analysis: CompileAnalysis, options: TestOption*): Task =
		testTask(frameworks, classpath, analysis, options)
	def testTask(frameworks: Iterable[TestFramework], classpath: PathFinder, analysis: CompileAnalysis, options: => Seq[TestOption]): Task =
		task{ doTests(frameworks, classpath, analysis, options) }

	def graphTask(outputDirectory: Path, analysis: CompileAnalysis): Task = task { DotGraph(analysis, outputDirectory, log) }
	def scaladocTask(label: String, sources: PathFinder, outputDirectory: Path, classpath: PathFinder, options: ScaladocOption*): Task =
		scaladocTask(label, sources, outputDirectory, classpath, options)
	def scaladocTask(label: String, sources: PathFinder, outputDirectory: Path, classpath: PathFinder, options: => Seq[ScaladocOption]): Task =
		task
		{
			val classpathString = Path.makeString(classpath.get)
			Scaladoc(label, sources.get, classpathString, outputDirectory, options.flatMap(_.asList), log)
		}

	def packageTask(sources: PathFinder, outputDirectory: Path, jarName: => String, options: PackageOption*): Task =
		packageTask(sources, outputDirectory, jarName, options)
	def packageTask(sources: PathFinder, outputDirectory: Path, jarName: => String, options: => Seq[PackageOption]): Task =
		fileTask((outputDirectory / jarName) from sources)
		{
			import scala.collection.jcl.Map
			/** Copies the mappings in a2 to a1, mutating a1. */
			def mergeAttributes(a1: Attributes, a2: Attributes)
			{
				for( (key, value) <- Map(a2))
					a1.put(key, value)
			}

			import scala.collection.mutable.ListBuffer
			val manifest = new Manifest
			var recursive = false
			for(option <- options)
			{
				option match
				{
					case ManifestOption(mergeManifest) => 
					{
						mergeAttributes(manifest.getMainAttributes, mergeManifest.getMainAttributes)
						val entryMap = Map(manifest.getEntries)
						for((key, value) <- Map(mergeManifest.getEntries))
						{
							entryMap.get(key) match
							{
								case Some(attributes) => mergeAttributes(attributes, value)
								case None => entryMap.put(key, value)
							}
						}
					}
					case Recursive => recursive = true
					case MainClassOption(mainClassName) => manifest.getMainAttributes.putValue(MainClassKey, mainClassName)
					case _ => log.warn("Ignored unknown package option " + option)
				}
			}
			
			val jarPath = outputDirectory / jarName
			FileUtilities.jar(sources.get, jarPath, manifest, recursive, log)
		}
	
	def incrementVersionNumber()
	{
		projectVersion.get match
		{
			case Some(v: BasicVersion) =>
			{
				val newVersion = incrementImpl(v)
				log.info("Changing version to " + newVersion)
				projectVersion() = newVersion
			}
			case a => ()
		}
	}
	protected def incrementImpl(v: BasicVersion): Version = v.incrementMicro
	protected def doTests(frameworks: Iterable[TestFramework], classpath: PathFinder, analysis: CompileAnalysis, options: => Seq[TestOption]): Option[String] = {
		import scala.collection.mutable.HashSet

			val excludeTests = for(ExcludeTests(exclude) <- options) yield exclude
			val excludeTestsSet = HashSet.empty[String] ++ excludeTests.flatMap(x => x)
			if(excludeTestsSet.size > 0 && log.atLevel(Level.Debug))
			{
				log.debug("Excluding tests: ")
				excludeTestsSet.foreach(test => log.debug("\t" + test))
			}
			val tests = HashSet.empty[TestDefinition] ++ analysis.allTests.filter(test => !excludeTestsSet.contains(test.testClassName))

			TestFramework.runTests(frameworks, classpath.get, tests, log)
	}
}
trait ManagedScalaProject extends ScalaProject
{
	trait ManagedOption extends ActionOption
	final class ManagedFlagOption extends ManagedOption
	final val Synchronize = new ManagedFlagOption
	final val Validate = new ManagedFlagOption
	final val QuietUpdate = new ManagedFlagOption
	final val AddScalaToolsReleases = new ManagedFlagOption
	final val ErrorIfNoConfiguration = new ManagedFlagOption
	final case class LibraryManager(m: Manager) extends ManagedOption
	
	private def withConfigurations(outputPattern: String, managedDependencyPath: Path, options: Seq[ManagedOption])
		(doWith: (IvyConfiguration, UpdateConfiguration) => Option[String]) =
	{
		var synchronize = false
		var validate = false
		var quiet = false
		var addScalaTools = false
		var errorIfNoConfiguration = false
		var manager: Manager = AutoDetectManager
		for(option <- options)
		{
			option match
			{
				case Synchronize => synchronize = true
				case Validate => validate = true
				case LibraryManager(m) => manager = m
				case QuietUpdate => quiet = true
				case AddScalaToolsReleases => addScalaTools = true
				case ErrorIfNoConfiguration => errorIfNoConfiguration = true
				case _ => log.warn("Ignored unknown managed option " + option)
			}
		}
		val ivyConfiguration = IvyConfiguration(info.projectPath, managedDependencyPath, manager, validate,
			addScalaTools, errorIfNoConfiguration, log)
		val updateConfiguration = UpdateConfiguration(outputPattern, synchronize, quiet)
		doWith(ivyConfiguration, updateConfiguration)
	}
	private def withIvyTask(doTask: => Option[String]) =
		task
		{
			try { doTask }
			catch
			{
				case e: NoClassDefFoundError =>
					log.trace(e)
					Some("Apache Ivy is required for dependency management (" + e.toString + ")")
			}
		}
	def updateTask(outputPattern: String, managedDependencyPath: Path, options: ManagedOption*): Task =
		updateTask(outputPattern, managedDependencyPath, options)
	def updateTask(outputPattern: String, managedDependencyPath: Path, options: => Seq[ManagedOption]) =
		withIvyTask(withConfigurations(outputPattern, managedDependencyPath, options)(ManageDependencies.update))
		
	def cleanCacheTask(managedDependencyPath: Path, options: => Seq[ManagedOption]) =
		withIvyTask(withConfigurations("", managedDependencyPath, options) { (ivyConf, ignore) => ManageDependencies.cleanCache(ivyConf) })
		
	def cleanLibTask(managedDependencyPath: Path) = task { FileUtilities.clean(managedDependencyPath.get, log) }
}
trait WebScalaProject extends ScalaProject
{
	protected def prepareWebappTask(webappContents: PathFinder, warPath: Path, classpath: PathFinder, extraJars: => Iterable[File]) =
		task
		{
			val (libs, classDirectories) = classpath.get.toList.partition(ClasspathUtilities.isArchive)
			val classes = (Path.lazyPathFinder(classDirectories.map(_ ##))) ** "*.class"
			
			val webInfPath = warPath / "WEB-INF"
			val webLibDirectory = webInfPath / "lib"
			
			(FileUtilities.copy(webappContents.get, warPath, log).right flatMap { copiedWebapp =>
			FileUtilities.copy(classes.get, webInfPath / "classes", log).right flatMap { copiedClasses =>
			FileUtilities.copyFlat(libs, webLibDirectory, log).right flatMap { copiedLibs =>
			FileUtilities.copyFilesFlat(extraJars, webLibDirectory, log).right flatMap { copiedExtraLibs =>
				{
					val toRemove = scala.collection.mutable.HashSet((warPath ** "*").get.toSeq : _*)
					toRemove --= copiedWebapp
					toRemove --= copiedClasses
					toRemove --= copiedLibs
					toRemove --= copiedExtraLibs
					val (directories, files) = toRemove.toList.partition(_.isDirectory)
					//TODO: prune directories
					if(log.atLevel(Level.Debug))
						files.foreach(r => log.debug("Pruning " + r))
					FileUtilities.clean(files, true, log).toLeft(())
				}
			}}}}).left.toOption
		}
	def jettyRunTask(warPath: Path, defaultContextPath: String, classpath: PathFinder, classpathName: String) =
		interactiveTask { JettyRun(classpath.get, classpathName, warPath, defaultContextPath, scala.xml.NodeSeq.Empty, Nil, log) }
	def jettyStopTask = interactiveTask { JettyRun.stop(); None }
}
object ScalaProject
{
	val AnalysisDirectoryName = "analysis"
	val MainClassKey = "Main-Class"
}

/** A Project that determines its library dependencies by reflectively finding all vals with a type
* that conforms to ModuleID.*/
trait ReflectiveLibraryDependencies extends Project
{
	def excludeIDs: Iterable[ModuleID]
	def libraryDependencies: Set[ModuleID] = reflectiveLibraryDependencies
	def reflectiveLibraryDependencies : Set[ModuleID] = Set(Reflective.reflectiveMappings[ModuleID](this).values.toList: _*) -- excludeIDs
}

/** A Project that determines its library dependencies by reflectively finding all vals with a type
* that conforms to ModuleID.*/
trait ReflectiveRepositories extends Project
{
	def repositories: Set[Resolver] = reflectiveRepositories
	def reflectiveRepositories: Set[Resolver] = Set(Reflective.reflectiveMappings[Resolver](this).values.toList: _*)
}

trait ReflectiveManagedProject extends ReflectiveProject with ReflectiveRepositories with ReflectiveLibraryDependencies