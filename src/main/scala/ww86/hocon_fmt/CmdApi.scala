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
        sys.exit(executeFormatting(inputs))
    }
  }

  /** Outcome of examining one file. */
  enum Outcome {
    case Rewritten(path: String)
    case AlreadyFormatted(path: String)
    case NeedsFormatting(path: String, formatted: String)
    case Unformattable(path: String, reason: String)
  }

  /** Examines every file, then reports once. Returns the process exit code.
    *
    * Every file is examined even if an earlier one is unformatted. Exiting from inside the
    * parallel loop used to kill the JVM mid-iteration, so in --check mode some files were
    * never looked at, and the reports of the ones that were raced each other onto stdout.
    */
  def executeFormatting(inputs: InputArguments): Int = {
    val files = inputs.files.map(new File(_))
    println(s"Running HOCON formatter for ${files.length} files.")

    val outcomes = files.par.map(examine(_, inputs.checkOnly)).toList

    outcomes.foreach {
      case Outcome.Unformattable(path, reason) =>
        println(s"ERROR: cannot format, leaving unchanged: $path ($reason)")
      case Outcome.NeedsFormatting(path, formatted) =>
        println(s"Found a not formatted file: $path .")
        println(s"After formatting:\n$formatted\n")
      case Outcome.AlreadyFormatted(_) => print(".")
      case Outcome.Rewritten(_)        => ()
    }

    // 1, not -1: an exit status is a byte, so -1 reaches the shell as 255.
    if (outcomes.exists(_.isInstanceOf[Outcome.NeedsFormatting])) 1 else 0
  }

  private def examine(file: File, checkOnly: Boolean): Outcome = {
    val path = file.getCanonicalPath
    HoconFormatter.fmtFileToStr(file) match {
      case Success(formatted) =>
        if (checkOnly) {
          if (formatted != readStringFrom(file.toPath)) Outcome.NeedsFormatting(path, formatted)
          else Outcome.AlreadyFormatted(path)
        } else {
          replaceContent(file, formatted)
          Outcome.Rewritten(path)
        }
      case Failure(e) => Outcome.Unformattable(path, e.getMessage.take(120))
    }
  }

  // throws exceptions, walkaround for not present JDK11+
  def readStringFrom(file: Path): String =
    String(Files.readAllBytes(file), StandardCharsets.UTF_8)

  private def replaceContent(inFile: File, withContent: String): Unit =
    Files.write(inFile.toPath, withContent.getBytes(StandardCharsets.UTF_8))

}
