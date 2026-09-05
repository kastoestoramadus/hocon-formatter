package ww86.hocon_fmt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import ww86.hocon_fmt.HoconFormatter.*

/** Golden-file rendering tests.
  *
  * Each `<name>.conf` under src/test/resources is formatted and compared against
  * `<name>.expected.conf`. Adding a fixture is a two-file drop, no Scala changes.
  *
  * To rewrite the expected files after an intentional change:
  * {{{ UPDATE_GOLDEN=1 sbt "testOnly ww86.hocon_fmt.GoldenFileSpec" }}}
  * Then read the diff before committing it — the point of a golden file is that a human
  * approved it.
  */
class GoldenFileSpec extends munit.FunSuite {

  private val updating = sys.env.get("UPDATE_GOLDEN").exists(v => v == "1" || v == "true")

  GoldenFileSpec.inputs.foreach { input =>
    test(s"golden: ${input.getName}") {
      val actual   = fmtFileToStr(input).get
      val expected = GoldenFileSpec.goldenFor(input)

      if (updating) {
        Files.write(expected.toPath, actual.getBytes(StandardCharsets.UTF_8))
        println(s"UPDATE_GOLDEN: wrote ${expected.getName}")
      } else {
        assert(
          expected.exists(),
          s"missing golden file ${expected.getName} - create it with: " +
            s"""UPDATE_GOLDEN=1 sbt "testOnly ww86.hocon_fmt.GoldenFileSpec""""
        )
        assertEquals(actual, GoldenFileSpec.read(expected))
      }
    }
  }
}

object GoldenFileSpec {
  val resourceDir = new File("src/test/resources")

  private val goldenSuffix = ".expected.conf"

  /** Fixture inputs: every .conf that is not itself a golden file. */
  val inputs: List[File] =
    Option(resourceDir.listFiles()).toList.flatten
      .filter(f => f.getName.endsWith(".conf") && !f.getName.endsWith(goldenSuffix))
      .sortBy(_.getName)

  def goldenFor(input: File): File =
    new File(resourceDir, input.getName.stripSuffix(".conf") + goldenSuffix)

  def read(f: File): String = String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
}
