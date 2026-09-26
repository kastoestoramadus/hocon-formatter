package ww86.hocon_fmt.sbt

import java.nio.charset.StandardCharsets.UTF_8

import sbt._
import sbt.Keys._
import lmcoursier.CoursierDependencyResolution

/** Formats the HOCON files of a build: `hoconFormat` rewrites them, `hoconFormatCheck` fails the
  * build when one is not formatted. A file the formatter refuses is reported and left alone,
  * without failing either task.
  */
object HoconFormatterPlugin extends AutoPlugin {

  override def trigger  = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport {
    val hoconFormat        = taskKey[Unit]("Rewrites the HOCON files that are not formatted.")
    val hoconFormatCheck   = taskKey[Unit]("Fails if any HOCON file is not formatted, naming each one.")
    val hoconFormatSources = taskKey[Seq[File]](
      "The HOCON files to format: *.conf and *.hocon in the Compile and Test resource directories by default."
    )
  }

  import autoImport._

  private val hoconFormatterClasspath =
    taskKey[Seq[File]]("The formatter and its dependencies, resolved apart from the build's own.")
      .withRank(KeyRanks.Invisible)

  final case class Examined(file: File, path: String, verdict: Verdict)

  override def projectSettings: Seq[Setting[_]] = Seq(
    hoconFormatSources := {
      val directories = (Compile / unmanagedResourceDirectories).value ++ (Test / unmanagedResourceDirectories).value
      directories.flatMap(directory => (directory ** ("*.conf" || "*.hocon")).get)
    },
    hoconFormatterClasspath := {
      val formatter = FormatterArtifact.organization % FormatterArtifact.name % FormatterArtifact.version
      // The build's resolvers and credentials, but none of its Scala: left to itself, resolution
      // pins scala-library to the build's version, a 2.12 library where the formatter needs 3.x.
      val resolution = CoursierDependencyResolution(
        csrConfiguration.value
          .withScalaVersion(Some(FormatterArtifact.scalaVersion))
          .withAutoScalaLibrary(false)
          .withForceVersions(Vector.empty)
      )
      resolution
        .retrieve(formatter, None, streams.value.cacheDirectory, streams.value.log)
        .fold(unresolved => throw unresolved.resolveException, identity)
    },
    hoconFormat := {
      val log      = streams.value.log
      val examined = examineAll.value
      examined.foreach {
        case Examined(file, path, Verdict.NeedsFormatting(formatted)) =>
          IO.write(file, formatted, UTF_8)
          log.info(s"Formatted $path")
        case Examined(_, path, Verdict.Refused(reason)) => log.warn(refusal(path, reason))
        case _                                          => ()
      }
      log.info(summary(examined, needingFormatAre = "formatted"))
    },
    hoconFormatCheck := {
      val log      = streams.value.log
      val examined = examineAll.value
      examined.foreach {
        case Examined(_, path, Verdict.NeedsFormatting(_)) => log.warn(s"Not formatted: $path")
        case Examined(_, path, Verdict.Refused(reason))    => log.warn(refusal(path, reason))
        case _                                             => ()
      }
      log.info(summary(examined, needingFormatAre = "not formatted"))
      val unformatted = examined.count(_.verdict.isInstanceOf[Verdict.NeedsFormatting])
      if (unformatted > 0)
        throw new MessageOnlyException(s"$unformatted HOCON files are not formatted. Run hoconFormat to fix them.")
    },
    // Each task covers the projects it aggregates itself, so a resource directory two of them
    // share is examined once rather than written by two concurrent runs.
    hoconFormat / aggregate      := false,
    hoconFormatCheck / aggregate := false
  )

  private val examineAll: Def.Initialize[Task[Seq[Examined]]] = Def.task {
    val root  = (ThisBuild / baseDirectory).value.getCanonicalFile
    val files = hoconFormatSources.all(ScopeFilter(inAggregates(ThisProject))).value.flatten
    IsolatedFormatter.using(hoconFormatterClasspath.value) { formatter =>
      files.map(_.getCanonicalFile).distinct.sorted.map { file =>
        Examined(file, IO.relativize(root, file).getOrElse(file.getPath), formatter.verdictFor(IO.readBytes(file)))
      }
    }
  }

  private def refusal(path: String, reason: String): String = s"Leaving $path unchanged: $reason"

  private def summary(examined: Seq[Examined], needingFormatAre: String): String = {
    def count(p: Verdict => Boolean) = examined.count(e => p(e.verdict))
    val needing                      = count(_.isInstanceOf[Verdict.NeedsFormatting])
    val already                      = count(_ == Verdict.AlreadyFormatted)
    val refused                      = count(_.isInstanceOf[Verdict.Refused])
    s"HOCON files: $needing $needingFormatAre, $already already formatted, $refused refused."
  }
}
