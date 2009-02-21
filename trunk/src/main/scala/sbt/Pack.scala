/* sbt -- Simple Build Tool
 * Copyright 2009 Mark Harrah
 */
package sbt

import java.io.{File, FileOutputStream}
import java.util.jar.{JarEntry, JarFile, JarOutputStream, Pack200}
import scala.collection.Map
import scala.collection.jcl.Conversions
import FileUtilities._

object Pack
{
	def pack(jarPath: Path, out: Path, log: Logger): Option[String] = pack(jarPath, out, defaultPackerOptions, log)
	def pack(jarPath: Path, out: Path, options: Map[String, String], log: Logger): Option[String] =
	{
		val packer = Pack200.newPacker
		val properties = Conversions.convertMap(packer.properties)
		properties ++= options
		def packIt(f: JarFile) = writeStream(out.asFile, log) { stream => packer.pack(f, stream); None }
		ioOption(jarPath.asFile, openJarFile(false), packIt, "applying pack200 compression to jar", log)
	}
	def unpack(packedPath: Path, toJarPath: Path, log: Logger): Option[String] =
	{
		val unpacker = Pack200.newUnpacker
		writeStream(toJarPath.asFile, log) { fileStream =>
			val jarOut = new JarOutputStream(fileStream)
			Control.trapUnitAndFinally("Error unpacking '" + packedPath + "': ", log)
				{ unpacker.unpack(packedPath.asFile, jarOut); None }
				{ jarOut.close() }
		}
	}

	import Pack200.Packer._
	def defaultPackerOptions: Map[String, String] =
		scala.collection.immutable.Map(
			EFFORT -> "7",
			SEGMENT_LIMIT -> "-1",
			KEEP_FILE_ORDER -> FALSE,
			DEFLATE_HINT -> FALSE,
			UNKNOWN_ATTRIBUTE -> PASS
		)
}

import java.net.URL
/** This is somewhat of a mess and is not entirely correct.  jarsigner doesn't work properly
* on scalaz and it is difficult to determine whether a jar is both signed and valid.  */
object SignJar
{
	final case class SignOption private[SignJar](toList: List[String], signOnly: Boolean) extends NotNull
	{
		override def toString = toList.mkString(" ")
	}
	def keyStore(url: URL) = SignOption("-keystore" :: url.toExternalForm :: Nil, true)
	def signedJar(p: Path) = SignOption("-signedjar" :: p.asFile.getAbsolutePath :: Nil, true)
	def verbose = SignOption("-verbose" :: Nil, false)
	def sigFile(name: String) = SignOption("-sigfile" :: name :: Nil, true)
	def storeType(t: String) = SignOption("-storetype" :: t :: Nil, false)
	def provider(p: String) = SignOption("-provider" :: p :: Nil, false)
	def providerName(p: String) = SignOption("-providerName" :: p :: Nil, false)
	def storePassword(p: String) = SignOption("-storepass" :: p :: Nil, true)
	def keyPassword(p: String) = SignOption("-keypass" :: p :: Nil, true)
	
	private def VerifyOption = "-verify"
	
	/** Uses jarsigner to sign the given jar.  */
	def sign(jarPath: Path, alias: String, options: Seq[SignOption], log: Logger): Option[String] =
	{
		require(!alias.trim.isEmpty, "Alias cannot be empty")
		val arguments = options.toList.flatMap(_.toList) ::: jarPath.asFile.getAbsolutePath :: alias :: Nil
		val pr = (new ProcessRunner(CommandName, arguments)).logIO(log)
		log.debug("Sign command: " + pr.commandLine)
		val exitCode = pr.run.exitValue()
		if(exitCode == 0)
			None
		else
			Some("Error signing jar (exit code was " + exitCode + ".)")
	}
	/** Uses jarsigner to verify the given jar.*/
	def verify(jarPath: Path, options: Seq[SignOption], log: Logger): Option[String] =
	{
		val arguments = options.filter(!_.signOnly).toList.flatMap(_.toList) ::: VerifyOption :: jarPath.asFile.getAbsolutePath :: Nil
		val pr = new ProcessRunner(CommandName, arguments)
		log.debug("Verify command: " + pr.commandLine)
		val exitCode = pr.run.exitValue()
		if(exitCode == 0)
		{
			log.debug("Verified " + jarPath)
			None
		}
		else
			Some("Error verifying jar (exit code was " + exitCode + ".)")
	}
	
	val CommandName = "jarsigner"
}