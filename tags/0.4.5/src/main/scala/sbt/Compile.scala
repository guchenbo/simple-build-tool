/* sbt -- Simple Build Tool
 * Copyright 2008 Mark Harrah
 */
package sbt

abstract class CompilerCore
{
	val ClasspathOptionString = "-classpath"
	val OutputOptionString = "-d"
	
	// Returns false if there were errors, true if there were not.
	protected def process(args: List[String], log: Logger): Boolean
	// Returns false if there were errors, true if there were not.
	protected def processJava(args: List[String], log: Logger): Boolean = true
	def actionStartMessage(label: String): String
	def actionNothingToDoMessage: String
	def actionSuccessfulMessage: String
	def actionUnsuccessfulMessage: String

	final def apply(label: String, sources: Iterable[Path], classpathString: String, outputDirectory: Path, options: Seq[String], log: Logger): Option[String] =
		apply(label, sources, classpathString, outputDirectory, options, Nil, log)
	final def apply(label: String, sources: Iterable[Path], classpathString: String, outputDirectory: Path, options: Seq[String], javaOptions: Seq[String], log: Logger): Option[String] =
	{
		log.info(actionStartMessage(label))
		val classpathOption: List[String] =
			if(classpathString.isEmpty)
				Nil
			else
				List(ClasspathOptionString, classpathString)
		val outputDir = outputDirectory.asFile
		FileUtilities.createDirectory(outputDir, log) orElse
		{
			val classpathAndOut: List[String] = OutputOptionString :: outputDir.getAbsolutePath :: classpathOption
			
			Control.trapUnit("Compiler error: ", log)
			{
				val sourceList = sources.map(_.asFile.getAbsolutePath).toList
				if(sourceList.isEmpty)
				{
					log.info(actionNothingToDoMessage)
					None
				}
				else
				{
					val arguments = (options ++ classpathAndOut ++ sourceList).toList
					log.debug("Scala arguments: " + arguments.mkString(" "))
					if(process(arguments, log))
					{
						val javaSourceList = sourceList.filter(_.endsWith(".java"))
						if(javaSourceList.isEmpty)
							success(log)
						else
						{
							val javaArguments = javaOptions.toList ::: classpathAndOut ::: javaSourceList
							log.debug("Java arguments: " + javaArguments.mkString(" "))
							if(processJava(javaArguments, log))
								success(log)
							else
								failure
						}
					}
					else
						failure
				}
			}
		}
	}
	private def success(log: Logger) =
	{
		log.info(actionSuccessfulMessage)
		None
	}
	private def failure = Some(actionUnsuccessfulMessage)
}
// The following code is based on scala.tools.nsc.Main and scala.tools.nsc.ScalaDoc
// Copyright 2005-2008 LAMP/EPFL
// Original author: Martin Odersky
	
object Compile extends CompilerCore
{
	protected def process(arguments: List[String], log: Logger) =
	{
		import scala.tools.nsc.{CompilerCommand, FatalError, Global, Settings, reporters, util}
		import util.FakePos
		var reporter = new LoggerReporter(log)
		val settings = new Settings(reporter.error)
		val command = new CompilerCommand(arguments, settings, error, false)
		
		object compiler extends Global(command.settings, reporter)
		if(!reporter.hasErrors)
		{
			val run = new compiler.Run
			run compile command.files
			reporter.printSummary()
		}
		!reporter.hasErrors
	}
	override protected def processJava(args: List[String], log: Logger) =
		(Process("javac", args) ! log) == 0
	
	def actionStartMessage(label: String) = "Compiling " + label + " sources..."
	val actionNothingToDoMessage = "Nothing to compile."
	val actionSuccessfulMessage = "Compilation successful."
	val actionUnsuccessfulMessage = "Compilation unsuccessful."
}
object Scaladoc extends CompilerCore
{
	protected def process(arguments: List[String], log: Logger) =
	{
		import scala.tools.nsc.{doc, CompilerCommand, FatalError, Global, reporters, util}
		import util.FakePos
		val reporter = new LoggerReporter(log)
		val docSettings: doc.Settings = new doc.Settings(reporter.error)
		val command = new CompilerCommand(arguments, docSettings, error, false)
		object compiler extends Global(command.settings, reporter)
		{
			override val onlyPresentation = true
		}
		if(!reporter.hasErrors)
		{
			val run = new compiler.Run
			run compile command.files
			val generator = new doc.DefaultDocDriver
			{
				lazy val global: compiler.type = compiler
				lazy val settings = docSettings
			}
			generator.process(run.units)
			reporter.printSummary()
		}
		!reporter.hasErrors
	}
	def actionStartMessage(label: String) = "Generating API documentation for " + label + " sources..."
	val actionNothingToDoMessage = "No sources specified."
	val actionSuccessfulMessage = "API documentation generation successful."
	def actionUnsuccessfulMessage = "API documentation generation unsuccessful."
}

// The following code is based on scala.tools.nsc.reporters.{AbstractReporter, ConsoleReporter}
// Copyright 2002-2008 LAMP/EPFL
// Original author: Martin Odersky
class LoggerReporter(log: Logger) extends scala.tools.nsc.reporters.Reporter
{
	import scala.tools.nsc.util.{FakePos,Position}
	private val positions = new scala.collection.mutable.HashMap[Position, Severity]
	
	def error(msg: String) { error(FakePos("scalac"), msg) }

	def printSummary()
	{
		if(WARNING.count > 0)
			log.warn(countElementsAsString(WARNING.count, "warning") + " found")
		if(ERROR.count > 0)
			log.error(countElementsAsString(ERROR.count, "error") + " found")
	}
	
	def display(pos: Position, msg: String, severity: Severity)
	{
		severity.count += 1
		if(severity != ERROR || severity.count <= LoggerReporter.ErrorLimit)
			print(severityToLevel(severity), pos, msg)
	}
	private def severityToLevel(severity: Severity): Level.Value =
		severity match
		{
			case ERROR => Level.Error
			case WARNING => Level.Warn
			case INFO => Level.Info
		}
	
	private def print(level: Level.Value, posIn: Position, msg: String)
	{
		if(posIn == null)
			log.log(level, msg)
		else
		{
			val pos = posIn.inUltimateSource(posIn.source.getOrElse(null))
			def message =
			{
				val sourcePrefix =
					pos match
					{
						case FakePos(msg) => msg + " "
						case _ => pos.source.map(_.file.path).getOrElse("")
					}
				val lineNumberString = pos.line.map(line => ":" + line + ":").getOrElse(":") + " "
				sourcePrefix + lineNumberString + msg
			}
			log.log(level, message)
			if (!pos.line.isEmpty)
			{
				log.log(level, pos.lineContent.stripLineEnd) // source line with error/warning
				for(column <- pos.column if column > 0) // pointer to the column position of the error/warning
					log.log(level, (" " * (column-1)) + '^')
			}
		}
	}
	override def reset =
	{
		super.reset
		positions.clear
	}

	protected def info0(pos: Position, msg: String, severity: Severity, force: Boolean)
	{
		severity match
		{
			case WARNING | ERROR =>
			{
				if(!testAndLog(pos, severity))
					display(pos, msg, severity)
			}
			case _ => display(pos, msg, severity)
		}
	}
	
	private def testAndLog(pos: Position, severity: Severity): Boolean =
	{
		if(pos == null || pos.offset.isEmpty)
			false
		else if(positions.get(pos).map(_ >= severity).getOrElse(false))
			true
		else
		{
			positions(pos) = severity
			false
		}
	}
}
object LoggerReporter
{
	val ErrorLimit = 100
}