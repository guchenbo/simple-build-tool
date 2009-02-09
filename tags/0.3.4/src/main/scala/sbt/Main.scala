/* sbt -- Simple Build Tool
 * Copyright 2008, 2009  Steven Blundy, Mark Harrah, David MacIver
 */
package sbt

import scala.collection.immutable.TreeSet

private object RunCompleteAction extends Enumeration
{
	val Exit, Reload = Value
}

/** This class is the entry point for sbt.  If it is given any arguments, it interprets them
* as actions, executes the corresponding actions, and exits.  If there were no arguments provided,
* sbt enters interactive mode.*/
object Main
{
	/** The entry point for sbt.  If arguments are specified, they are interpreted as actions, executed,
	* and then the program terminates.  If no arguments are specified, the program enters interactive
	* mode.*/
	def main(args: Array[String])
	{
		run(args)
	}
	private def run(args: Array[String])
	{
		val startTime = System.currentTimeMillis
		Project.loadProject match
		{
			case LoadSetupError(message) =>
				println("\n" + message)
				runExitHooks(Project.log)
			case LoadSetupDeclined =>
				runExitHooks(Project.log)
			case LoadError(errorMessage) =>
			{
				println(errorMessage)
				runExitHooks(Project.log)
				// Because this is an error that can probably be corrected, prompt user to try again.
				val line =
					try { Some(readLine("\n Hit enter to retry or 'exit' to quit: ")).filter(_ != null) }
					catch
					{
						case e =>
							Project.log.trace(e)
							Project.log.error(e.toString)
							None
					}
				line match
				{
					case Some(l) => if(!isTerminateAction(l)) run(args)
					case None => ()
				}
			}
			case LoadSuccess(project) =>
			{
				val doNext =
					// in interactive mode, fill all undefined properties
					if(args.length > 0 || fillUndefinedProjectProperties(project.topologicalSort.toList.reverse))
						startProject(project, args, startTime)
					else
						RunCompleteAction.Exit
				runExitHooks(project.log)
				if(doNext == RunCompleteAction.Reload)
					run(args)
			}
		}
	}
	/** Returns true if the project should be reloaded, false if sbt should exit.*/
	private def startProject(project: Project, args: Array[String], startTime: Long): RunCompleteAction.Value =
	{
		project.log.info("Building project " + project.name + " " + project.version.toString + " using " + project.getClass.getName)
		if(args.length == 0)
		{
			project.log.info("No actions specified, interactive session started. Execute 'help' for more information.")
			val doNext = interactive(project)
			printTime(project, startTime, "session")
			doNext
		}
		else if(args.contains("compile-stats"))
		{
			def timeCompile(): Long =
			{
				val start = System.currentTimeMillis
				project.act("compile")
				System.currentTimeMillis - start
			}
			def freshCompile(label: String)
			{
				project.act("clean")
				val time = timeCompile()
				println(label + " full compile time: " + time + " ms")
			}
			project.log.setLevel(Level.Warn)
			project match
			{
				case sp: BasicScalaProject =>
				{
					List("Initial", "Second", "Third").foreach(freshCompile)
					for(source <- sp.mainSources.get)
					{
						FileUtilities.touch(source, sp.log)
						val time = timeCompile()
						println("Time to compile modified source " + source + ": " + time + " ms")
					}
				}
				case _ => project.log.error("Compile statistics only available on BasicScalaProjects.")
			}
			RunCompleteAction.Exit
		}
		else
		{
			((None: Option[String]) /: args)( (errorMessage, arg) => errorMessage orElse project.act(arg) ) match
			{
				case None => project.log.success("Build completed successfully.")
				case Some(errorMessage) => project.log.error("Error during build: " + errorMessage)
			}
			printTime(project, startTime, "build")
			RunCompleteAction.Exit
		}
	}
	
	/** The name of the action that shows the current project and logging level of that project.*/
	val ShowCurrent = "current"
	/** The name of the action that shows all available actions.*/
	val ShowActions = "actions"
	/** The name of the action that sets the currently active project.*/
	val ProjectAction = "project"
	/** The name of the action that shows all available projects.*/
	val ShowProjectsAction = "projects"
	/** The list of lowercase action names that may be used to terminate the program.*/
	val TerminateActions: Iterable[String] = "exit" :: "quit" :: Nil
	/** The name of the action that sets the value of the property given as its argument.*/
	val SetAction = "set"
	/** The name of the action that gets the value of the property given as its argument.*/
	val GetAction = "get"
	/** The name of the action that displays the help message. */
	val HelpAction = "help"
	/** The name of the action that reloads a project.  This is useful for when the project definition has changed. */
	val ReloadAction = "reload"

	/** The list of all available commands at the interactive prompt in addition to the tasks defined
	* by a project.*/
	protected def interactiveCommands: Iterable[String] = basicCommands.toList ++ logLevels.toList
	/** The list of logging levels.*/
	private def logLevels: Iterable[String] = TreeSet.empty[String] ++ Level.elements.map(_.toString)
	/** The list of all interactive commands other than logging level.*/
	private def basicCommands: Iterable[String] = TreeSet(ShowProjectsAction, ShowActions, ShowCurrent, HelpAction, ReloadAction)
	
	/** Enters interactive mode for the given root project.  It uses JLine for tab completion and
	* history.  It returns normally when the user terminates or reloads the interactive session.  That is,
	* it does not call System.exit to quit.
	**/
	private def interactive(baseProject: Project): RunCompleteAction.Value =
	{
		val reader = new JLineReader(baseProject, ProjectAction, interactiveCommands)
		
		/** Prompts the user for the next command using 'currentProject' as context.
		* If the command indicates that the user wishes to terminate or reload the session,
		*   the function returns the appropriate value.
		* Otherwise, the command is handled and this function is called again
		*   (tail recursively) to prompt for the next command. */
		def loop(currentProject: Project): RunCompleteAction.Value =
		{
			reader.readLine("> ") match
			{
				case Some(line) =>
				{
					val trimmed = line.trim
					if(trimmed.isEmpty)
						loop(currentProject)
					else if(isTerminateAction(trimmed))
						RunCompleteAction.Exit
					else if(ReloadAction == trimmed)
						RunCompleteAction.Reload
					else if(trimmed.startsWith(ProjectAction + " "))
					{
						val projectName = trimmed.substring(ProjectAction.length + 1)
						baseProject.topologicalSort.find(_.name == projectName) match
						{
							case Some(newProject) =>
							{
								printProject("Set current project to ", newProject)
								reader.changeProject(newProject)
								loop(newProject)
							}
							case None =>
							{
								currentProject.log.error("Invalid project name '" + projectName + "' (type 'projects' to list available projects).")
								loop(currentProject)
							}
						}
					}
					else
					{
						if(trimmed == HelpAction)
							displayInteractiveHelp()
						else if(trimmed == ShowProjectsAction)
							baseProject.topologicalSort.foreach(listProject)
						else if(trimmed.startsWith(SetAction + " "))
							setProperty(currentProject, trimmed.substring(SetAction.length + 1))
						else if(trimmed.startsWith(GetAction + " "))
							getProperty(currentProject, trimmed.substring(GetAction.length + 1))
						else
							handleCommand(currentProject, trimmed)
						loop(currentProject)
					}
				}
				case None => RunCompleteAction.Exit
			}
		}
		
		loop(baseProject)
	}
	private def displayInteractiveHelp() = {
		Console.println("You may execute any project action or one of the commands described below. Only one action " +
			"may be executed at a time in interactive mode and is entered by name, as it would be at the command line." +
			" Also, tab completion is available.")
		Console.println("Available Commands:")

		def printCmd(name:String, desc:String) = Console.println("\t" + name + ": " + desc)

		printCmd("<action name>", "Executes the project specified action.")
		printCmd(ShowCurrent, "Shows the current project and logging level of that project.")
		printCmd(ShowActions, "Shows all available actions.")
		printCmd(Level.elements.mkString(", "), "Set logging for the current project to the specified level.")
		printCmd(ProjectAction + " <project name>", "Sets the currently active project.")
		printCmd(ShowProjectsAction, "Shows all available projects.")
		printCmd(TerminateActions.elements.mkString(", "), "Terminates the program.")
		printCmd(ReloadAction, "Reloads sbt, recompiling modified project definitions if necessary.")
		printCmd(SetAction + " <property> <value>", "Sets the value of the property given as its argument.")
		printCmd(GetAction + " <property>", "Gets the value of the property given as its argument.")
		printCmd(HelpAction, "Displays this help message.")
	}
	private def listProject(p: Project) = printProject("\t", p)
	private def printProject(prefix: String, p: Project)
	{
		Console.println(prefix + p.name + " " + p.version)
	}
	
	private def handleCommand(project: Project, command: String)
	{
		command match
		{
			case GetAction => getArgumentError(project.log)
			case SetAction => setArgumentError(project.log)
			case ProjectAction => setProjectError(project.log)
			case ShowCurrent =>
			{
				printProject("Current project is ", project)
				Console.println("Current log level is " + project.log.getLevel)
			}
			case ShowActions =>
			{
				for( (name, task) <- project.deepTasks)
					Console.println("\t" + name + task.description.map(x => ": " + x).getOrElse(""))
			}
			case Level(level) => setLevel(project, level)
			case action => handleAction(project, action)
		}
	}
	private def handleAction(project: Project, action: String)
	{
		val startTime = System.currentTimeMillis
		project.act(action) match
		{
			case Some(errorMessage) =>
			{
				project.log.error(errorMessage)
				if(!project.taskNames.exists(_ == action))
					project.log.info("Execute 'help' to see a list of commands or " + 
						"'actions' for a list of available project actions")
			}
			case None =>
			{
				printTime(project, startTime, "")
				project.log.success("Successful.")
			}
		}
	}
	/** Sets the logging level on the given project.*/
	private def setLevel(project: Project, level: Level.Value)
	{
		project.topologicalSort.foreach(_.log.setLevel(level))
		Console.println("Set log level to " + project.log.getLevel)
	}
	/** Prints the elapsed time to the given project's log using the given
	* initial time and the label 's'.*/
	private def printTime(project: Project, startTime: Long, s: String)
	{
		val endTime = System.currentTimeMillis()
		project.log.info("")
		val ss = if(s.isEmpty) "" else s + " "
		project.log.info("Total " + ss + "time: " + (endTime - startTime + 500) / 1000 + " s")
	}
	/** Provides a partial message describing why the given property is undefined. */
	private def undefinedMessage(property: Project#UserProperty[_]): String =
	{
		property.resolve match
		{
			case vu: UndefinedValue => " is not defined."
			case e: ResolutionException => " has invalid value: " + e.toString
			case _ => ""
		}
	}
	/** Prompts the user for the value of undefined properties.  'first' is true if this is the first time
	* that the current property has been prompted.*/
	private def fillUndefinedProperties(project: Project, properties: List[(String, Project#Property[_])], first: Boolean): Boolean =
	{
		properties match
		{
			case (name, variable) :: tail =>
			{
				val shouldAdvanceOrQuit =
					variable match
					{
						case property: Project#UserProperty[_] =>
							if(first)
								project.log.error(" Property '" + name + "' " + undefinedMessage(property))
							val newValue = Console.readLine("  Enter new value for " + name + " : ")
							Console.println()
							if(newValue == null)
								None
							else
							{
								try
								{
									property.setStringValue(newValue)
									Some(true)
								}
								catch
								{
									case e =>
										project.log.error("Invalid value: " + e.getMessage)
										Some(false)
								}
							}
						case _ => Some(true)
					}
				shouldAdvanceOrQuit match
				{
					case Some(shouldAdvance) => fillUndefinedProperties(project, if(shouldAdvance) tail else properties, shouldAdvance)
					case None => false
				}
			}
			case Nil => true
		}
	}
	/** Iterates over the undefined properties in the given projects, prompting the user for the value of each undefined
	* property.*/
	private def fillUndefinedProjectProperties(projects: List[Project]): Boolean =
	{
		projects match
		{
			case project :: remaining =>
			{
				val uninitialized = project.uninitializedProperties.toList
				if(uninitialized.isEmpty)
					fillUndefinedProjectProperties(remaining)
				else
				{
					project.log.error("Project in " + project.info.projectDirectory.getAbsolutePath + " has undefined properties.")
					val result = fillUndefinedProperties(project, uninitialized, true) && fillUndefinedProjectProperties(remaining)
					project.saveEnvironment()
					result
				}
			}
			case Nil => true
		}
	}
	/** Prints the value of the property with the given name in the given project. */
	private def getProperty(project: Project, propertyName: String)
	{
		if(propertyName.isEmpty)
			project.log.error("No property name specified.")
		else
		{
			project.getPropertyNamed(propertyName) match
			{
				case Some(property) =>
				{
					property.resolve match
					{
						case u: UndefinedValue => project.log.error("Value of property '" + propertyName + "' is undefined.")
						case ResolutionException(m, e) => project.log.error(m)
						case DefinedValue(value, isInherited, isDefault) => Console.println(value.toString)
					}
				}
				case None =>
				{
					val value = System.getProperty(propertyName)
					if(value == null)
						project.log.error("No property named '" + propertyName + "' is defined.")
					else
						Console.println(value)
				}
			}
		}
	}
	/** Separates the space separated property name/value pair and stores the value in the user-defined property
	* with the given name in the given project.  If no such property exists, the value is stored in a system
	* property. */
	private def setProperty(project: Project, propertyNameAndValue: String)
	{
		val m = """(\S+)(\s+\S.*)?""".r.pattern.matcher(propertyNameAndValue)
		if(m.matches())
		{
			val name = m.group(1)
			val newValue =
			{
				val v = m.group(2)
				if(v == null) "" else v.trim
			}
			project.getPropertyNamed(name) match
			{
				case Some(property) =>
				{
					val succeeded =
						try
						{
							property.setStringValue(newValue)
							Console.println(" Set property '" + name + "' = '" + newValue + "'")
						}
						catch { case e => project.log.error("Error setting property '" + name + "' in " + project.environmentLabel + ": " + e.toString) }
					project.saveEnvironment()
				}
				case None =>
				{
					System.setProperty(name, newValue)
					project.log.info(" Set system property '" + name + "' = '" + newValue + "'")
				}
			}
		}
		else
			setArgumentError(project.log)
	}
	private def isTerminateAction(s: String) = TerminateActions.elements.contains(s.toLowerCase)
	private def setArgumentError(log: Logger) { log.error("Invalid arguments for 'set': expected property name and new value.") }
	private def getArgumentError(log: Logger) { log.error("Invalid arguments for 'get': expected property name.") }
	private def setProjectError(log: Logger) { log.error("Invalid arguments for 'project': expected project name.") }
	
	/** This is a list of hooks to call when sbt is finished executing.*/
	private val exitHooks = new scala.collection.mutable.HashSet[ExitHook]
	/** Adds a hook to call before sbt exits. */
	private[sbt] def registerExitHook(hook: ExitHook) { exitHooks += hook }
	/** Removes a hook. */
	private[sbt] def unregisterExitHook(hook: ExitHook) { exitHooks -= hook }
	/** Calls each registered exit hook, trapping any exceptions so that each hook is given a chance to run. */
	private def runExitHooks(log: Logger)
	{
		for(hook <- exitHooks.toList)
		{
			try
			{
				log.debug("Running exit hook '" + hook.name + "'...")
				hook.runBeforeExiting()
			}
			catch
			{
				case e =>
				{
					log.trace(e);
					log.error("Error running exit hook '" + hook.name + "': " + e.toString)
				}
			}
		}
	}
}

/** Defines a function to call as sbt exits.*/
trait ExitHook extends NotNull
{
	/** Provides a name for this hook to be used to provide feedback to the user. */
	def name: String
	/** Subclasses should implement this method, which is called when this hook is executed. */
	def runBeforeExiting(): Unit
}