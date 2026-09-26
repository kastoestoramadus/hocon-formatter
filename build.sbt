import scala.scalanative.build.{LTO, Mode}

val scala3  = "3.8.2"
val sconfig = "1.12.4"
val munit   = "1.2.4"
val sjavatime = "1.5.0"

val catsEffect      = "3.7.1"
val fs2             = "3.14.0"
val decline         = "2.6.2"
val munitCatsEffect = "2.2.1"

ThisBuild / scalaVersion := scala3
ThisBuild / version      := "0.1.0-SNAPSHOT"

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
  .aggregate(coreJVM, coreJS, coreNative, cliJVM, cliJS, cliNative)
  .settings(
    name := "hocon-formatter",
    publish / skip := true,
    // `test` is spelled out instead of aggregated: aggregated projects run concurrently, and
    // their summaries then arrive unlabelled and interleaved, with no way to tell which runtime
    // produced which. Running them in order keeps each banner next to its own result.
    Test / test / aggregate := false,
    Test / test := Def
      .sequential(
        coreJVM / Test / test,
        cliJVM / Test / test,
        coreJS / Test / test,
        cliJS / Test / test,
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
      "org.ekrich"    %%% "sconfig" % sconfig,
      "org.scalameta" %%% "munit"   % munit % Test
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

lazy val cliJVM    = cli.jvm
lazy val cliJS     = cli.js
lazy val cliNative = cli.native

addCommandAlias(
  "crossCompile",
  Seq(coreJVM, coreJS, coreNative, cliJVM, cliJS, cliNative)
    .map(p => s"${p.id}/Test/compile")
    .mkString("; ")
)
addCommandAlias("libraryDefects", "coreJVM/testOnly ww86.hocon_fmt.SconfigDefectsSpec")
