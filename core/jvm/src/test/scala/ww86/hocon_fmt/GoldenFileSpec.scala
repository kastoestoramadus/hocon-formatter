package ww86.hocon_fmt

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Golden-file rendering tests.
  *
  * Each `<name>.conf` is formatted and compared against `<name>.expected.conf`, so adding a
  * fixture is a two-file drop with no Scala changes.
  *
  * To rewrite the expected files after an intentional change:
  * {{{ UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec" }}}
  * Read the diff before committing it — a golden file is worth what the human who approved it
  * looked at.
  */
class GoldenFileSpec extends munit.FunSuite with HoconTestSupport {

  val updating = sys.env.get("UPDATE_GOLDEN").exists(v => v == "1" || v == "true")

  FileFixtures.inputs.foreach { input =>
    test(s"golden: ${input.getName}") {
      val actual = formatted(FileFixtures.read(input))
      val golden = FileFixtures.goldenFor(input)

      if (updating) {
        Files.write(golden.toPath, actual.getBytes(StandardCharsets.UTF_8))
        println(s"UPDATE_GOLDEN: wrote ${golden.getName}")
      } else {
        assert(
          golden.exists(),
          s"missing golden file ${golden.getName} - create it with: " +
            s"""UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec""""
        )
        assertEquals(actual, FileFixtures.read(golden))
      }
    }
  }
}
