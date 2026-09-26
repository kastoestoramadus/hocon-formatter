package ww86.hocon_fmt.mill

import mill.*
import mill.api.BuildCtx
import mill.javalib.JavaModule
import ww86.hocon_fmt.{Refusal, Verdict}

/** Formats the HOCON files of a module: `hoconFormat` rewrites them, `hoconFormatCheck` fails when
  * one is not formatted. A file the formatter refuses is reported and left alone, without failing
  * either command. Mix it into each module whose files it should cover, test modules included, and
  * run `__.hoconFormat` to cover the build.
  */
trait HoconFormatterModule extends JavaModule {
  import HoconFormatterModule.*

  /** Where the HOCON files are: a directory is searched for `*.conf` and `*.hocon`, a file is taken
    * as it is. The module's resources by default.
    */
  def hoconFormatSources: T[Seq[PathRef]] = Task { resources() }

  /** Rewrites the HOCON files that are not formatted. */
  def hoconFormat(): Command[Unit] = Task.Command {
    val examined = examine(hoconFormatSources())
    examined.foreach {
      case Examined(file, Verdict.NeedsFormatting(formatted)) =>
        os.write.over(file, formatted)
        Task.log.info(s"Formatted ${shown(file)}")
      case Examined(file, Verdict.Refused(refusal)) => Task.log.warn(leftAlone(file, refusal))
      case Examined(_, Verdict.AlreadyFormatted)    => ()
    }
    Task.log.info(summary(examined, needingFormatAre = "formatted"))
  }

  /** Fails if any HOCON file is not formatted, naming each one; writes nothing. */
  def hoconFormatCheck(): Command[Unit] = Task.Command {
    val examined = examine(hoconFormatSources())
    examined.foreach {
      case Examined(file, Verdict.NeedsFormatting(_)) => Task.log.warn(s"Not formatted: ${shown(file)}")
      case Examined(file, Verdict.Refused(refusal))   => Task.log.warn(leftAlone(file, refusal))
      case Examined(_, Verdict.AlreadyFormatted)      => ()
    }
    Task.log.info(summary(examined, needingFormatAre = "not formatted"))
    val unformatted = examined.count(_.needsFormatting)
    if unformatted > 0 then Task.fail(s"$unformatted HOCON files are not formatted. Run hoconFormat to fix them.")
  }
}

object HoconFormatterModule {

  final private case class Examined(file: os.Path, verdict: Verdict) {
    def needsFormatting: Boolean = verdict.isInstanceOf[Verdict.NeedsFormatting]
  }

  private def examine(sources: Seq[PathRef]): Seq[Examined] =
    hoconFiles(sources).map(file => Examined(file, Verdict.of(os.read.bytes(file))))

  private def hoconFiles(sources: Seq[PathRef]): Seq[os.Path] =
    sources
      .map(_.path)
      .filter(os.exists)
      .flatMap(path => if os.isDir(path) then os.walk(path).filter(isHocon) else Seq(path))
      .distinct
      .sorted

  private def isHocon(path: os.Path): Boolean =
    os.isFile(path) && (path.ext == "conf" || path.ext == "hocon")

  private def shown(file: os.Path): String =
    if file.startsWith(BuildCtx.workspaceRoot) then file.relativeTo(BuildCtx.workspaceRoot).toString
    else file.toString

  private def leftAlone(file: os.Path, refusal: Refusal): String =
    s"Leaving ${shown(file)} unchanged: ${refusal.reason}"

  private def summary(examined: Seq[Examined], needingFormatAre: String): String = {
    val needing = examined.count(_.needsFormatting)
    val already = examined.count(_.verdict == Verdict.AlreadyFormatted)
    val refused = examined.count(_.verdict.isInstanceOf[Verdict.Refused])
    s"HOCON files: $needing $needingFormatAre, $already already formatted, $refused refused."
  }
}
