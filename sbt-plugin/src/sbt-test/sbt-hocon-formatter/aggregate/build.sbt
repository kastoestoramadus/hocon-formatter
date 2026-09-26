// Both subprojects own `shared`, the way the platforms of a crossProject share resources.
lazy val sharedResources = Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "shared"

lazy val a    = project.settings(sharedResources)
lazy val b    = project.settings(sharedResources)
lazy val root = (project in file(".")).aggregate(a, b)

// sbt keeps the log of each task's last run, which lets the script check what a task reported.
InputKey[Unit]("assertWarnedOnce") := {
  val task +: fragments = Def.spaceDelimited("<task> <fragment>...").parsed
  val lines = IO.readLines(target.value / "streams" / "_global" / task / "_global" / "streams" / "out")
  fragments.foreach { fragment =>
    val mentions = lines.filter(_.contains(fragment))
    assert(
      mentions.size == 1 && mentions.head.startsWith("[warn]"),
      s"expected one warning naming $fragment in the log of $task, got:\n${lines.mkString("\n")}"
    )
  }
}
