val scala3  = "3.8.2"
val sconfig = "1.12.4"
val munit   = "1.2.4"
val sjavatime = "1.5.0"

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
  .aggregate(coreJVM, coreJS, cli)
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
        cli / Test / test,
        coreJS / Test / test
      )
      .value
  )

/** Formatting proper: a pure `String => Try[String]` with no file access and no threads, which
  * is what lets it cross-build. Scala Native is deliberately not a target — its `java.util.regex`
  * runs on RE2, which rejects the lookaround this code would otherwise want.
  */
lazy val core = crossProject(JVMPlatform, JSPlatform)
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
  .jsSettings(
    announceRuntime("core on Scala.js"),
    // sconfig declares this `provided`, so a Scala.js consumer has to supply it: sconfig reaches
    // for java.time, which the Scala.js javalib does not carry.
    libraryDependencies += "org.ekrich" %%% "sjavatime" % sjavatime
  )

lazy val coreJVM = core.jvm
lazy val coreJS  = core.js

/** The command line tool: file access, argument parsing and parallelism, none of which exist on
  * Scala.js. Keeping them here is what keeps `core` portable.
  */
lazy val cli = project
  .in(file("cli"))
  // test->test so the CLI suites can reuse HoconTestSupport.
  .dependsOn(coreJVM % "compile->compile;test->test")
  .settings(
    name := "hocon-formatter-cli",
    announceRuntime("cli on the JVM"),
    Compile / mainClass := Some("ww86.hocon_fmt.CmdApi"),
    libraryDependencies ++= Seq(
      "com.github.scopt"       %% "scopt"                      % "4.0.1",
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
      "org.scalameta"          %% "munit"                      % munit % Test
    )
  )

addCommandAlias("crossCompile", "coreJVM/Test/compile; coreJS/Test/compile; cli/Test/compile")
addCommandAlias("libraryDefects", "coreJVM/testOnly ww86.hocon_fmt.SconfigDefectsSpec")
