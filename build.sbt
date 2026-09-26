import scala.scalanative.build.{LTO, Mode}

val scala3    = "3.8.2"
val sconfig   = "1.12.4"
val munit     = "1.2.4"
val sjavatime = "1.5.0"

val catsEffect      = "3.7.1"
val fs2             = "3.14.0"
val decline         = "2.6.2"
val munitCatsEffect = "2.2.1"
val munitScalaCheck = "1.2.0"

ThisBuild / scalaVersion := scala3
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Coordinates and the metadata Sonatype requires before anything can reach Maven Central,
// which is what `cs` and therefore the pre-commit coursier hook resolve from.
ThisBuild / organization     := "io.github.kastoestoramadus"
ThisBuild / organizationName := "kastoestoramadus"
ThisBuild / homepage         := Some(url("https://github.com/kastoestoramadus/hocon-formatter"))
ThisBuild / licenses         := Seq("GPL-3.0" -> url("https://www.gnu.org/licenses/gpl-3.0.html"))
ThisBuild / scmInfo          := Some(
  ScmInfo(
    url("https://github.com/kastoestoramadus/hocon-formatter"),
    "scm:git:https://github.com/kastoestoramadus/hocon-formatter.git"
  )
)
ThisBuild / developers := List(
  Developer(
    "kastoestoramadus",
    "Waldemar Wosinski",
    "",
    url("https://github.com/kastoestoramadus")
  )
)

/** sbt prints one unlabelled "Passed: Total N" per aggregated project, and the Scala.js block
  * arrives without the `[info]` prefix, so nothing says which runtime a result came from. The
  * banner goes in a Cleanup hook rather than Setup so it lands next to that project's summary
  * instead of with everything else at start-up.
  */
def announceRuntime(label: String): Setting[?] =
  Test / testOptions += Tests.Cleanup(() => println(s"========== $label =========="))

lazy val root = project
  .in(file("."))
  // Aggregation drives compile, scalafmt and the rest.
  .aggregate(coreJVM, coreJS, coreNative, cliJVM, cliJS, cliNative, web, benchJVM, benchJS, benchNative, sbtPlugin)
  .settings(
    name           := "hocon-formatter",
    publish / skip := true,
    // `test` is spelled out instead of aggregated: aggregated projects run concurrently, and
    // their summaries then arrive unlabelled and interleaved, with no way to tell which runtime
    // produced which. Running them in order keeps each banner next to its own result.
    Test / test / aggregate := false,
    Test / test             := Def
      .sequential(
        coreJVM / Test / test,
        cliJVM / Test / test,
        coreJS / Test / test,
        cliJS / Test / test,
        web / Test / test,
        coreNative / Test / test,
        cliNative / Test / test
      )
      .value
  )

/** Formatting proper: a pure `String => Either[Refusal, String]` with no file access and no
  * effects, which is what lets it cross-build. It depends on nothing but sconfig because the
  * build-tool plugins load it into their hosts' class loaders.
  */
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    name := "hocon-formatter-core",
    libraryDependencies ++= Seq(
      "org.ekrich"    %%% "sconfig"          % sconfig,
      "org.scalameta" %%% "munit"            % munit           % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitScalaCheck % Test
    ),
    // SconfigDefectsSpec asserts what sconfig *should* do, so it is red while those upstream bugs
    // are open. Scoped to the `test` task only, so `testOnly` can still run it on demand.
    Test / test / testOptions += Tests.Exclude(Seq("ww86.hocon_fmt.SconfigDefectsSpec"))
  )
  .jvmSettings(announceRuntime("core on the JVM"))
  .jsSettings(announceRuntime("core on Scala.js"))
  .nativeSettings(announceRuntime("core on Scala Native"))
  .platformsSettings(JSPlatform, NativePlatform)(
    // sconfig reaches for java.time, which neither the Scala.js nor the Scala Native javalib
    // carries. Provided, as sconfig itself declares it: an application supplies exactly one
    // implementation, and two of the same package clash at link time. The CLI gets
    // scala-java-time through cats-effect; this one only serves core's own tests.
    libraryDependencies += "org.ekrich" %%% "sjavatime" % sjavatime % Provided
  )

lazy val coreJVM    = core.jvm
lazy val coreJS     = core.js
lazy val coreNative = core.native

/** The command line tool, on every platform: the native binary, the Node bundle behind the
  * pre-commit hook, and the JVM. Effects live here, in cats-effect, so `core` stays pure.
  */
lazy val cli = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("cli"))
  .dependsOn(core)
  .settings(
    name := "hocon-formatter-cli",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect"       % catsEffect,
      "co.fs2"        %%% "fs2-io"            % fs2,
      "com.monovore"  %%% "decline-effect"    % decline,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffect % Test
    )
  )
  .jvmSettings(
    announceRuntime("cli on the JVM"),
    Compile / mainClass := Some("ww86.hocon_fmt.CmdApi"),
    // IOApp ends with System.exit, which must end a forked JVM and not sbt.
    run / fork := true
  )
  .jsSettings(
    announceRuntime("cli on Scala.js"),
    scalaJSUseMainModuleInitializer := true,
    // fs2-io reaches Node's `fs` through `require`.
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .nativeSettings(
    announceRuntime("cli on Scala Native"),
    // The shipped binary is optimised; tests link in debug mode, which is several times faster.
    Compile / nativeConfig ~= {
      _.withBaseName("hocon-formatter").withMode(Mode.releaseFast).withLTO(LTO.thin)
    },
    Test / nativeConfig ~= { _.withMode(Mode.debug).withLTO(LTO.none) }
  )

val npmPackage = taskKey[File]("Assembles the npm package: the linked CLI and its package.json.")

cliJS / npmPackage := {
  val linked  = (cliJS / Compile / fullLinkJSOutput).value / "main.js"
  val staging = (cliJS / target).value / "npm-package"
  IO.delete(staging)
  IO.copyDirectory(file("npm"), staging)
  // npm links `bin` entries as executables, which needs the shebang to pick Node.
  val cli = staging / "hocon-formatter.js"
  IO.write(cli, "#!/usr/bin/env node\n" + IO.read(linked))
  cli.setExecutable(true)
  val manifest = staging / "package.json"
  IO.write(manifest, IO.read(manifest).replace(""""version": "0.0.0"""", s""""version": "${version.value}""""))
  staging
}

lazy val cliJVM    = cli.jvm
lazy val cliJS     = cli.js
lazy val cliNative = cli.native

/** The formatter as a script for web pages, behind the playground: one global, `HoconFormatter`,
  * with a small JavaScript API; see docs/playground.md. A classic script rather than an ES module,
  * so a page that loads it works from `file://` as well.
  */
lazy val web = project
  .in(file("web"))
  .enablePlugins(ScalaJSPlugin, BuildInfoPlugin)
  .dependsOn(coreJS)
  .settings(
    name           := "hocon-formatter-web",
    publish / skip := true,
    announceRuntime("web on Scala.js"),
    libraryDependencies ++= Seq(
      "org.ekrich"    %%% "sjavatime" % sjavatime,
      "org.scalameta" %%% "munit"     % munit % Test
    ),
    buildInfoPackage := "ww86.hocon_fmt.web",
    buildInfoKeys    := Seq[BuildInfoKey](version),
    // The tests run the optimised script a page loads, where only exported names survive.
    Test / scalaJSStage := FullOptStage
  )

addCommandAlias(
  "crossCompile",
  Seq(coreJVM, coreJS, coreNative, cliJVM, cliJS, cliNative, web)
    .map(p => s"${p.id}/Test/compile")
    .mkString("; ")
)
// On every platform: sconfig's Scala.js and Scala Native builds have defects of their own.
addCommandAlias(
  "libraryDefects",
  Seq(coreJVM, coreJS, coreNative).map(p => s"${p.id}/testOnly ww86.hocon_fmt.SconfigDefectsSpec").mkString("; ")
)

/** Timings of each formatter phase on each platform; see `scripts/bench.py`. Not published. The
  * mutable loop in `Bench.measure` is deliberate: an allocation-free timing loop is the one place
  * where the functional style would distort what it measures.
  */
lazy val bench = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("bench"))
  .dependsOn(core)
  .settings(
    name           := "hocon-formatter-bench",
    publish / skip := true
  )
  .jvmSettings(run / fork := true)
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    // Timings of the fast-optimised output would describe a build nobody ships.
    scalaJSStage := FullOptStage
  )
  .platformsSettings(JSPlatform, NativePlatform)(
    libraryDependencies += "org.ekrich" %%% "sjavatime" % sjavatime
  )
  .nativeSettings(nativeConfig ~= { _.withMode(Mode.releaseFast).withLTO(LTO.thin) })

lazy val benchJVM    = bench.jvm
lazy val benchJS     = bench.js
lazy val benchNative = bench.native

/** The sbt 1.x plugin. sbt loads plugins with Scala 2.12, which cannot link against this Scala 3
  * build, so the plugin resolves the core at run time and calls it through `JvmFacade` in an
  * isolated class loader, the way sbt-scalafmt runs scalafmt. Its behaviour is covered by the
  * scripted tests in `sbt-plugin/src/sbt-test`, run with `sbtPluginTest`: each starts a fresh
  * sbt, which is too slow for the `test` sequence.
  */
lazy val sbtPlugin = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin, BuildInfoPlugin)
  .settings(
    name         := "sbt-hocon-formatter",
    scalaVersion := "2.12.21",
    // The coordinates the plugin resolves the formatter by, so the two are released in lockstep.
    buildInfoPackage := "ww86.hocon_fmt.sbt",
    buildInfoObject  := "FormatterArtifact",
    buildInfoKeys    := Seq[BuildInfoKey](
      "organization" -> (coreJVM / organization).value,
      "name"         -> s"${(coreJVM / moduleName).value}_${(coreJVM / scalaBinaryVersion).value}",
      "version"      -> (coreJVM / version).value,
      "scalaVersion" -> (coreJVM / scalaVersion).value
    ),
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    // The tests read sbt's logs; on CI, sbt colours them, and the escape codes hide `[warn]`.
    scriptedLaunchOpts += "-Dsbt.log.noformat=true",
    // The plugin fetches the core by its coordinates, so scripted needs it published first.
    scriptedDependencies := scriptedDependencies.dependsOn(coreJVM / publishLocal).value
  )

addCommandAlias("sbtPluginTest", "sbtPlugin/scripted")
