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

// `must-mirror` compares decoded lines, so it cannot see a non-UTF-8 byte replaced by U+FFFD.
InputKey[Unit]("assertSameBytes") := {
  val Seq(actual, expected) = Def.spaceDelimited("<file> <file>").parsed
  assert(
    IO.readBytes(baseDirectory.value / actual).sameElements(IO.readBytes(baseDirectory.value / expected)),
    s"$actual no longer has the bytes of $expected"
  )
}
