package ww86.hocon_fmt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.parallel.CollectionConverters.*
import scala.util.{Failure, Success}

import scopt.OptionParser

object CmdApi {

  case class InputArguments(
      files: List[String] = Nil,
      checkOnly: Boolean = false
  )

  def main(args: Array[String]): Unit = {
    val parser = new OptionParser[InputArguments]("hocon-formatter") {
      head("Formatter of HOCON config files.")

      arg[String]("<file>...")
        .unbounded()
        .action((x, c) => c.copy(files = x :: c.files))
        .text(
          "One or more HOCON files which will be formatted. Not parsable files will be ignored with WARN log."
        )

      opt[Unit]('c', "check")
        .optional()
        .action((_, c) => c.copy(checkOnly = true))
        .text(
          "Optional flag to check only the formatting. Returned error code means that something is not formatted."
        )
    }

    parser.parse(args, InputArguments()) match {
      case None =>
        throw new IllegalArgumentException(
          s"""Error in parsing arguments : ${args.mkString(" ")}""".stripMargin
        )
      case Some(inputs) =>
        executeFormatting(inputs)
    }
  }

  def executeFormatting(inputs: InputArguments): Unit = {
    val files = inputs.files.map(new File(_))
    println(s"Running HOCON formatter for ${files.length} files.")

    files.par.foreach { file =>
      val debugInfo = file.getCanonicalPath
      HoconFormatter.fmtFileToStr(file) match {
        case Success(formatted) =>
          if (inputs.checkOnly) {
            val before = readStringFrom(file.toPath)
            if (formatted != before) {
              println(s"Found a not formatted file: $debugInfo .")
              println(s"After formatting:\n$formatted\n")
              sys.exit(-1)
            } else print(".")
          } else replaceContent(file, formatted)
        case Failure(_) =>
          println(s"ERROR: failed to parse, skipping: $debugInfo . ")
      }
    }
  }

  // throws exceptions, walkaround for not present JDK11+
  def readStringFrom(file: Path): String =
    String(Files.readAllBytes(file), StandardCharsets.UTF_8)

  private def replaceContent(inFile: File, withContent: String): Unit =
    Files.write(inFile.toPath, withContent.getBytes(StandardCharsets.UTF_8))

}
