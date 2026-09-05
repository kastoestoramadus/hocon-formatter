package ww86.hocon_fmt

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import ww86.hocon_fmt.CmdApi.InputArguments

/** CLI behaviour: exit codes, and that every file is examined. */
class CmdApiSpec extends munit.FunSuite {

  private val tmp = FunFixture[File](
    setup = _ => Files.createTempDirectory("hocon-fmt").toFile,
    teardown = dir => { dir.listFiles().foreach(_.delete()); dir.delete() }
  )

  private def write(dir: File, name: String, content: String): File = {
    val f = new File(dir, name)
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))
    f
  }

  private def read(f: File): String =
    String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)

  private def runCapturing(args: InputArguments): (Int, String) = {
    val buf  = new ByteArrayOutputStream()
    val code = Console.withOut(buf)(CmdApi.executeFormatting(args))
    (code, buf.toString(StandardCharsets.UTF_8))
  }

  private val unformatted = "a   :    1"
  private val formatted   = "a: 1\n"

  tmp.test("--check reports exit code 1 for an unformatted file") { dir =>
    val f         = write(dir, "a.conf", unformatted)
    val (code, _) = runCapturing(InputArguments(List(f.getPath), checkOnly = true))
    assertEquals(code, 1)
  }

  tmp.test("--check reports exit code 0 for an already formatted file") { dir =>
    val f         = write(dir, "a.conf", formatted)
    val (code, _) = runCapturing(InputArguments(List(f.getPath), checkOnly = true))
    assertEquals(code, 0)
  }

  tmp.test("--check leaves the file on disk untouched") { dir =>
    val f = write(dir, "a.conf", unformatted)
    runCapturing(InputArguments(List(f.getPath), checkOnly = true))
    assertEquals(read(f), unformatted)
  }

  // The defect: sys.exit fired from inside a parallel foreach, so the JVM died at the first
  // unformatted file and the remaining ones were never examined.
  tmp.test("--check examines every file, not just up to the first unformatted one") { dir =>
    val files       = (1 to 5).map(i => write(dir, s"f$i.conf", unformatted)).toList
    val (code, out) = runCapturing(InputArguments(files.map(_.getPath), checkOnly = true))
    assertEquals(code, 1)
    files.foreach { f =>
      assert(out.contains(f.getCanonicalPath), s"${f.getName} was never reported:\n$out")
    }
  }

  tmp.test("write mode rewrites the file") { dir =>
    val f         = write(dir, "a.conf", unformatted)
    val (code, _) = runCapturing(InputArguments(List(f.getPath), checkOnly = false))
    assertEquals(code, 0)
    assertEquals(read(f), formatted)
  }

  // Safety: a file the formatter cannot handle must never be overwritten.
  tmp.test("write mode leaves an unformattable file untouched") { dir =>
    val broken      = "a : ${"
    val f           = write(dir, "a.conf", broken)
    val (code, out) = runCapturing(InputArguments(List(f.getPath), checkOnly = false))
    assertEquals(read(f), broken)
    assertEquals(code, 0)
    assert(out.contains("cannot format, leaving unchanged"), out)
  }
}
