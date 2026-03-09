val scala3Version = "3.8.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "hocon-formatter",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "org.ekrich" %% "sconfig" % "1.12.4",
      "com.github.scopt" %%"scopt" % "4.0.1",
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
      "org.scalameta" %% "munit" % "1.2.4" % Test
    )
  )
