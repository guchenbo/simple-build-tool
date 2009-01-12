/* sbt -- Simple Build Tool
 * Copyright 2008 Mark Harrah
 */
package sbt

import java.io.File
import FileUtilities._

final case class ProjectInfo(projectDirectory: File, dependencies: Iterable[Project], parent: Option[Project]) extends NotNull
{
	val projectPath = new ProjectDirectory(projectDirectory)
	val builderPath = projectPath / ProjectInfo.MetadataDirectoryName
}

private[sbt] sealed trait SetupResult extends NotNull
private[sbt] final case class SetupError(message: String) extends SetupResult
private[sbt] final case object AlreadySetup extends SetupResult
private[sbt] final case class SetupInfo(name: String, version: Option[Version], initializeDirectories: Boolean) extends SetupResult

object ProjectInfo
{
	val MetadataDirectoryName = "project"
	
	def setup(info: ProjectInfo, log: Logger): SetupResult =
	{
		val builderDirectory = info.builderPath.asFile
		if(builderDirectory.exists)
		{
			if(builderDirectory.isDirectory)
				AlreadySetup
			else
				SetupError("'" + builderDirectory.getAbsolutePath + "' is not a directory.")
		}
		else
			setupProject(info.projectDirectory, log).getOrElse(SetupError("No project found."))
	}
	private def setupProject(projectDirectory: File, log: Logger): Option[SetupInfo] =
	{
		if(confirmPrompt("No project found. Create new project?", false))
		{
			val name = trim(Console.readLine("Project Name: "))
			if(name.isEmpty)
				None
			else
			{
				readVersion(projectDirectory, log) flatMap
				{ version =>
					if(verifyCreateProject(name, version))
						Some(SetupInfo(name, Some(version), true))
					else
						setupProject(projectDirectory, log)
				}
			}
		}
		else
			None
	}
	private def verifyCreateProject(name: String, version: Version): Boolean =
		confirmPrompt("Create new project " + name + " " + version + " ?", true)
	
	private def confirmPrompt(question: String, defaultYes: Boolean) =
	{
		val choices = if(defaultYes) " (Y/n) " else " (y/N) "
		val answer = trim(Console.readLine(question + choices))
		val yes = "y" :: "yes" :: (if(defaultYes) List("") else Nil)
		yes.contains(answer.toLowerCase)
	}
	
	private def readVersion(projectDirectory: File, log: Logger): Option[Version] =
	{
		val version = trim(Console.readLine("Version: "))
		if(version.isEmpty)
			None
		else
		{
			Version.fromString(version) match
			{
				case Left(errorMessage) =>
				{
					log.error("Invalid version: " + errorMessage)
					readVersion(projectDirectory, log)
				}
				case Right(v) => Some(v)
			}
		}
	}
	private def trim(s: String) = if(s == null) "" else s.trim
}