package ww86.hocon_fmt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.parallel.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import scopt.OptionParser

/** Command line entry point: reads files, hands their text to [[HoconFormatter]], and either
  * reports or rewrites.
  */
object CmdApi {

  case class InputArguments(files: List[String] = Nil, checkOnly: Boolean = false)

  def main(args: Array[String]): Unit =
    argumentParser.parse(args, InputArguments()) match {
      case Some(inputs) => sys.exit(examineAll(inputs))
      case None         =>
        throw new IllegalArgumentException(s"Error in parsing arguments : ${args.mkString(" ")}")
    }

  private val argumentParser = new OptionParser[InputArguments]("hocon-formatter") {
    head("Formatter of HOCON config files.")

    arg[String]("<file>...")
      .unbounded()
      .action((file, args) => args.copy(files = file :: args.files))
      .text("One or more HOCON files to format. Files that cannot be formatted are left alone.")

    opt[Unit]('c', "check")
      .optional()
      .action((_, args) => args.copy(checkOnly = true))
      .text("Report unformatted files instead of rewriting them; exits non-zero if any are found.")
  }

  enum Outcome {
    case Rewritten(path: String)
    case AlreadyFormatted(path: String)
    case NeedsFormatting(path: String, formatted: String)
    case Unformattable(path: String, reason: String)
  }

  /** Examines every file, reports once, and returns the process exit code.
    *
    * Every file is examined even after an unformatted one is found. Exiting from inside the
    * parallel loop used to kill the JVM mid-iteration, so `--check` could miss files entirely and
    * the reports of the ones it did reach raced each other onto stdout.
    */
  def examineAll(inputs: InputArguments): Int = {
    val files = inputs.files.map(new File(_))
    println(s"Running HOCON formatter for ${files.length} files.")

    val outcomes = files.par.map(examine(_, inputs.checkOnly)).toList
    outcomes.foreach(report)

    // 1 rather than -1: an exit status is a byte, so -1 would reach the shell as 255.
    if (outcomes.exists(_.isInstanceOf[Outcome.NeedsFormatting])) 1 else 0
  }

  private def examine(file: File, checkOnly: Boolean): Outcome = {
    val path = file.getCanonicalPath
    formatFile(file) match {
      case Failure(e)                       => Outcome.Unformattable(path, e.getMessage.take(120))
      case Success(formatted) if !checkOnly =>
        overwrite(file, formatted)
        Outcome.Rewritten(path)
      case Success(formatted) if formatted != readFile(file.toPath) =>
        Outcome.NeedsFormatting(path, formatted)
      case Success(_) => Outcome.AlreadyFormatted(path)
    }
  }

  private def report(outcome: Outcome): Unit = outcome match {
    case Outcome.Unformattable(path, reason) =>
      println(s"ERROR: cannot format, leaving unchanged: $path ($reason)")
    case Outcome.NeedsFormatting(path, formatted) =>
      println(s"Found a not formatted file: $path .")
      println(s"After formatting:\n$formatted\n")
    case Outcome.AlreadyFormatted(_) => print(".")
    case Outcome.Rewritten(_)        => ()
  }

  def formatFile(file: File): Try[String] =
    Try(readFile(file.toPath)).flatMap(HoconFormatter.format)

  def readFile(path: Path): String =
    String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def overwrite(file: File, content: String): Unit =
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
}
