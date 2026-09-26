package ww86.hocon_fmt

import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import com.monovore.decline.{Command, Help, Opts, PlatformApp}
import fs2.Stream
import fs2.io.file.{Files, Path}

/** Command line entry point: reads files, asks [[Verdict]] what each should become, and either
  * reports or rewrites. The same code runs as a native binary, a Node script and a JVM program.
  */
object CmdApi extends IOApp {

  final case class Arguments(files: List[Path], checkOnly: Boolean)

  val command: Command[Arguments] =
    Command(
      name = "hocon-formatter",
      header = "Formats HOCON files in place. Files it cannot format safely are left alone."
    ) {
      (
        Opts.arguments[String]("file").map(_.toList.map(Path(_))),
        Opts
          .flag(
            "check",
            "Report unformatted files instead of rewriting them; exit 1 if any are found.",
            short = "c"
          )
          .orFalse
      ).mapN(Arguments.apply)
    }

  enum Outcome {
    case Rewritten(path: String)
    case AlreadyFormatted(path: String)
    case NeedsFormatting(path: String, formatted: String)
    case Unformattable(path: String, reason: String)
  }

  /** Every file's outcome, and what the process prints and exits with because of them. */
  final case class Run(outcomes: List[Outcome]) {

    // 1 rather than -1: an exit status is a byte, so -1 would reach the shell as 255.
    def exitCode: ExitCode =
      if (outcomes.exists { case _: Outcome.NeedsFormatting => true; case _ => false }) ExitCode(1)
      else ExitCode.Success

    def rendered: String =
      (s"Running HOCON formatter for ${outcomes.size} files.\n" :: outcomes.map(render)).mkString
  }

  // Scala.js hands `main` no arguments; under Node they are in `process.argv`.
  override def run(args: List[String]): IO[ExitCode] =
    command.parse(PlatformApp.ambientArgs.getOrElse(args)) match {
      case Right(arguments) => examineAll(arguments).flatTap(run => IO.print(run.rendered)).map(_.exitCode)
      case Left(help)       => usage(help)
    }

  /** Examines every file, even after an unformatted one is found.
    *
    * Exiting from inside a parallel loop used to kill the JVM mid-iteration, so `--check` could
    * miss files entirely. Now nothing exits until every outcome is in.
    */
  def examineAll(arguments: Arguments): IO[Run] =
    arguments.files.parTraverse(examine(_, arguments.checkOnly)).map(Run(_))

  private def examine(file: Path, checkOnly: Boolean): IO[Outcome] =
    displayed(file).flatMap { path =>
      Files[IO]
        .readAll(file)
        .compile
        .to(Array)
        .map(Verdict.of)
        .flatMap(act(file, path, checkOnly))
        .handleError(e => Outcome.Unformattable(path, Option(e.getMessage).getOrElse(e.toString)))
    }

  private def act(file: Path, path: String, checkOnly: Boolean)(verdict: Verdict): IO[Outcome] =
    verdict match {
      case Verdict.NeedsFormatting(formatted) if checkOnly => IO.pure(Outcome.NeedsFormatting(path, formatted))
      case Verdict.NeedsFormatting(formatted)              => overwrite(file, formatted).as(Outcome.Rewritten(path))
      case Verdict.AlreadyFormatted                        => IO.pure(Outcome.AlreadyFormatted(path))
      case Verdict.Refused(refusal)                        => IO.pure(Outcome.Unformattable(path, refusal.reason.take(120)))
    }

  private def overwrite(file: Path, content: String): IO[Unit] =
    Stream.emit(content).through(Files[IO].writeUtf8(file)).compile.drain

  // The canonical path, so a report names one file one way; a missing file has none.
  private def displayed(file: Path): IO[String] =
    Files[IO].realPath(file).handleError(_ => file.absolute).map(_.toString)

  private def render(outcome: Outcome): String = outcome match {
    case Outcome.Unformattable(path, reason) =>
      s"ERROR: cannot format, leaving unchanged: $path ($reason)\n"
    case Outcome.NeedsFormatting(path, formatted) =>
      s"Found a not formatted file: $path .\nAfter formatting:\n$formatted\n\n"
    case Outcome.AlreadyFormatted(_) => "."
    case Outcome.Rewritten(_)        => ""
  }

  // 2, as grep and most formatters use for a usage error, keeps 1 meaning "unformatted".
  private def usage(help: Help): IO[ExitCode] =
    if (help.errors.isEmpty) IO.println(help).as(ExitCode.Success)
    else Console[IO].errorln(help).as(ExitCode(2))
}
