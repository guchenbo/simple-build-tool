import sbt._

import java.io.File

/** Support for compiling sbt across multiple versions of Scala.  The scala compiler is run in a
* separate JVM and no partial compilation is done.*/
protected/* protected required until sbt 0.4.1, which will properly ignore abstract classes and traits*/
	abstract class CrossCompileProject extends BasicScalaProject
{
	/** Used for 2.8.0-SNAPSHOT*/
	val scalaToolsSnapshots = "Scala Tools Snapshots" at "http://scala-tools.org/repo-snapshots"
	
	private val version2_7_2 = "2.7.2"
	private val version2_7_3 = "2.7.3"
	private val version2_7_4 = "2.7.4"
	private val version2_8_0 = "2.8.0-SNAPSHOT"
	private val base = "base"
	
	private def optional(v: String) = "optional-" + v
	private def scalac(v: String) = "scalac-" + v
	private def sbt(v: String) = "sbt_" + v
	private def depConf(v: String) = v + "->default"
	
	// =========== Cross-compilation across scala versions ===========
	
	// The dependencies that should go in each configuration are:
	//   base                             Required dependencies that are the same across all scala versions.
	//   <version>                  Required dependencies to use with Scala <version>
	//   optional-base              Optional dependencies that are the same for all scala versions
	//   optional-<version>   Optional dependencies to use with Scala <version>
	//   compile                        Used for normal development, it should extend a specific <version> and optional-<version>
	//   scalac-<version>       The scala compiler for Scala <version>
	// There should be a jar publication for each version of scala.  The artifact should be named sbt_<version>.
	override def ivyXML =
		(<configurations>
			<conf name={base}/>
			<conf name={version2_7_2} extends={base}/>
			<conf name={version2_7_3} extends={base}/>
			<conf name={version2_7_4} extends={base}/>
			<conf name={version2_8_0} extends={base}/>
			<conf name={optional(base)}/>
			<conf name={optional(version2_7_2)} extends={optional(base)}/>
			<conf name={optional(version2_7_3)} extends={optional(base)}/>
			<conf name={optional(version2_7_4)} extends={optional(base)}/>
			<conf name={optional(version2_8_0)} extends={optional(base)}/>
			<conf name="default" extends={version2_7_2 + "," + optional(version2_7_2)} visibility="private"/>
			<conf name={scalac(version2_7_2)} visibility="private"/>
			<conf name={scalac(version2_7_3)} visibility="private"/>
			<conf name={scalac(version2_7_4)} visibility="private"/>
			<conf name={scalac(version2_8_0)} visibility="private"/>
		</configurations>
		<publications>
			<artifact name={sbt(version2_7_2)} conf={version2_7_2}/>
			<artifact name={sbt(version2_7_3)} conf={version2_7_3}/>
			<artifact name={sbt(version2_7_4)} conf={version2_7_4}/>
			<artifact name={sbt(version2_8_0)} conf={version2_8_0}/>
		</publications>
		<dependencies>
			<!-- All Scala versions -->
			<dependency org="org.apache.ivy" name="ivy" rev="2.0.0" transitive="false" conf={depConf(base)}/>
			<dependency org="org.scalacheck" name="scalacheck" rev="1.5" transitive="false" conf={depConf(optional(base))}/>
			<dependency org="org.mortbay.jetty" name="jetty" rev="6.1.14" transitive="true" conf={depConf(optional(base))}/>
			
			<!-- Scala 2.7.2 -->
			<dependency org="org.specs" name="specs" rev="1.4.0" transitive="false" conf={depConf(optional(version2_7_2))}/>
			<dependency org="org.scalatest" name="scalatest" rev="0.9.3" transitive="false" conf={depConf(optional(version2_7_2))}/>
			<dependency org="org.scala-lang" name="scala-compiler" rev={version2_7_2} conf={depConf(scalac(version2_7_2))}/>
			
			<!-- Scala 2.7.3 -->
			<dependency org="org.scala-tools.testing" name="scalatest" rev="0.9.4" transitive="false" conf={depConf(optional(version2_7_3))}/>
			<dependency org="org.specs" name="specs" rev="1.4.3" transitive="false" conf={depConf(optional(version2_7_3))}/>
			<dependency org="org.scala-lang" name="scala-compiler" rev={version2_7_3} conf={depConf(scalac(version2_7_3))}/>
			
			<!-- Scala 2.7.4 -->
			<dependency org="org.scala-tools.testing" name="scalatest" rev="0.9.4" transitive="false" conf={depConf(optional(version2_7_4))}/>
			<dependency org="org.specs" name="specs" rev="1.4.3" transitive="false" conf={depConf(optional(version2_7_4))}/>
			<dependency org="org.scala-lang" name="scala-compiler" rev={version2_7_4} conf={depConf(scalac(version2_7_4))}/>

			<!-- Scala 2.8.0-SNAPSHOT -->
			<dependency org="org.scala-tools.testing" name="scalatest" rev="0.9.5" transitive="false" conf={depConf(optional(version2_8_0))}/>
			<dependency org="org.specs" name="specs" rev="1.4.3" transitive="false" conf={depConf(optional(version2_8_0))}/>
			<dependency org="org.scala-lang" name="scala-compiler" rev={version2_8_0} conf={depConf(scalac(version2_8_0))}/>
		</dependencies>)
	
	private val conf_2_7_2 = config(version2_7_2)
	private val conf_2_7_3 = config(version2_7_3)
	private val conf_2_7_4 = config(version2_7_4)
	private val conf_2_8_0 = config(version2_8_0)
	// the list of all configurations to cross-compile against
	private val allConfigurations = conf_2_7_2 :: conf_2_7_3 :: conf_2_7_4 :: conf_2_8_0 :: Nil
	
	/** The lib directory is now only for building using the 'build' script.*/
	override def unmanagedClasspath = path("ignore_lib_directory")
	/** When cross-compiling, replace mainCompilePath with the classes directory for the version being compiled.*/
	override def fullUnmanagedClasspath(config: Configuration) =
		if( (Configurations.Default :: Configurations.defaultMavenConfigurations) contains config)
			super.fullUnmanagedClasspath(config)
		else
			classesPath(config.toString) +++ mainResourcesPath
	
	// include the optional-<version> dependencies as well as the ones common across all scala versions
	def optionalClasspath(version: String) = fullClasspath(config(optional(version))) +++ super.optionalClasspath
	
	private val CompilerMainClass = "scala.tools.nsc.Main"
	// use a publish configuration that publishes the 'base' + all <version> configurations (base is required because
	//   the <version> configurations extend it)
	private val conf = new DefaultPublishConfiguration("local", "release")
	{
		override def configurations: Option[Iterable[Configuration]] = Some(config(base) :: allConfigurations)
	}
	// the actions for cross-version packaging and publishing
	lazy val crossPackage = allConfigurations.map(conf => packageForScala(conf.toString))
	lazy val crossDeliverLocal = deliverTask(conf, updateOptions) dependsOn(crossPackage : _*)
	lazy val crossPublishLocal = publishTask(conf, updateOptions) dependsOn(crossDeliverLocal)
	// Creates a task that produces a packaged sbt compiled against Scala scalaVersion.
	//  The jar is named 'sbt_<scala-version>-<sbt-version>.jar'
	private def packageForScala(scalaVersion: String) =
	{
		val classes = classesPath(scalaVersion) ** "*"
		val jarName = crossJarName(scalaVersion)
		packageTask(classes +++ mainResources, outputPath, jarName, packageOptions).dependsOn(compileForScala(scalaVersion))
	}
	private def crossJarName(scalaVersion: String) = sbt(scalaVersion) + "-" + version.toString +  ".jar"
	// This creates a task that compiles sbt against the given version of scala.  Classes are put in classes-<scalaVersion>.
	private def compileForScala(version: String)=
		task
		{
			val classes = classesPath(version)
			val toClean = (outputPath / crossJarName(version)) +++ (classes ** "*")
			val setupResult =
				FileUtilities.clean(toClean.get, true, log) orElse
				FileUtilities.createDirectory(classes, log)
			for(err <- setupResult) log.error(err)
			// the classpath containing the scalac compiler
			val compilerClasspath = concatPaths(fullClasspath(config(scalac(version))))
			
			// The libraries to compile sbt against
			val classpath = fullClasspath(config(version)) +++ optionalClasspath(version)
			val sources: List[String] = pathListStrings(mainSources.get)
			val compilerOptions = List("-cp", concatPaths(classpath), "-d", classes.toString)
			val compilerArguments: List[String] = compilerOptions ::: sources
			
			// the compiler classpath has to be appended to the boot classpath to work properly
			val allArguments = "-Xmx256M" :: ("-Xbootclasspath/a:" + compilerClasspath) :: CompilerMainClass :: compilerArguments
			val process = (new ProcessRunner("java", allArguments)).mergeErrorStream.logIO(log)
			val exitValue = process.run.exitValue
			if(exitValue == 0)
				None
			else
				Some("Nonzero exit value (" + exitValue + ") when calling scalac " + version + " with options: \n" + compilerOptions.mkString(" "))
		}
	private def concatPaths(p: PathFinder): String = pathListStrings(p.get).mkString(File.pathSeparator)
	private def pathListStrings(p: Iterable[Path]): List[String] = p.map(_.asFile.getAbsolutePath).toList
	private def classesPath(scalaVersion: String) = ("target"  / ("classes-" + scalaVersion)) ##
}