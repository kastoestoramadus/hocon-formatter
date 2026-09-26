package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import fs2.{Chunk, Stream}

import ww86.hocon_fmt.CmdApi.Arguments

/** CLI behaviour on real files: exit codes, that every file is examined, and that a file the
  * formatter cannot handle is never overwritten. The same suite runs on the JVM, Scala Native
  * and Node.
  */
class CmdApiSpec extends munit.CatsEffectSuite {

  val tmp = ResourceFunFixture(Files[IO].tempDirectory)

  def write(dir: Path, name: String, bytes: Array[Byte]): IO[Path] =
    Stream.chunk(Chunk.array(bytes)).through(Files[IO].writeAll(dir / name)).compile.drain.as(dir / name)

  def write(dir: Path, name: String, text: String): IO[Path] = write(dir, name, text.getBytes(UTF_8))

  def bytesOf(file: Path): IO[List[Byte]] = Files[IO].readAll(file).compile.toList

  def textOf(file: Path): IO[String] = bytesOf(file).map(bytes => String(bytes.toArray, UTF_8))

  def check(files: Path*): IO[CmdApi.Run]   = CmdApi.examineAll(Arguments(files.toList, checkOnly = true))
  def rewrite(files: Path*): IO[CmdApi.Run] = CmdApi.examineAll(Arguments(files.toList, checkOnly = false))

  val unformatted = "a   :    1"
  val formatted   = "a: 1\n"

  tmp.test("--check reports exit code 1 for an unformatted file") { dir =>
    write(dir, "a.conf", unformatted).flatMap(check(_)).map(run => assertEquals(run.exitCode, ExitCode(1)))
  }

  tmp.test("--check reports exit code 0 for an already formatted file") { dir =>
    write(dir, "a.conf", formatted).flatMap(check(_)).map(run => assertEquals(run.exitCode, ExitCode.Success))
  }

  tmp.test("--check leaves the file on disk untouched") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      _    <- check(file)
      text <- textOf(file)
    } yield assertEquals(text, unformatted)
  }

  // The defect: sys.exit fired from inside a parallel foreach, so the JVM died at the first
  // unformatted file and the remaining ones were never examined.
  tmp.test("--check examines every file, not just up to the first unformatted one") { dir =>
    for {
      files     <- (1 to 5).toList.traverse(i => write(dir, s"f$i.conf", unformatted))
      run       <- check(files*)
      realPaths <- files.traverse(Files[IO].realPath)
    } yield {
      assertEquals(run.exitCode, ExitCode(1))
      realPaths.foreach(path => assert(run.rendered.contains(path.toString), s"$path never reported:\n${run.rendered}"))
    }
  }

  tmp.test("write mode rewrites the file") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      run  <- rewrite(file)
      text <- textOf(file)
    } yield {
      assertEquals(run.exitCode, ExitCode.Success)
      assertEquals(text, formatted)
    }
  }

  // Safety: a file the formatter cannot handle must never be overwritten.
  tmp.test("write mode leaves an unformattable file untouched") { dir =>
    val broken = "a : ${"
    for {
      file <- write(dir, "a.conf", broken)
      run  <- rewrite(file)
      text <- textOf(file)
    } yield {
      assertEquals(text, broken)
      assertEquals(run.exitCode, ExitCode.Success)
      assert(run.rendered.contains("cannot format, leaving unchanged"), run.rendered)
    }
  }

  // A lenient decode turns each invalid byte into U+FFFD, and writing that back destroys it.
  tmp.test("write mode leaves a file that is not UTF-8 byte-for-byte untouched") { dir =>
    val latin2 = "a   :   \"³\"\n".getBytes(ISO_8859_1) // ł in ISO-8859-2
    for {
      file  <- write(dir, "a.conf", latin2)
      run   <- rewrite(file)
      bytes <- bytesOf(file)
    } yield {
      assertEquals(bytes, latin2.toList)
      assert(run.rendered.contains("cannot format, leaving unchanged"), run.rendered)
    }
  }

  tmp.test("a file that cannot be read is reported without stopping the others") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      run  <- rewrite(dir / "missing.conf", file)
      text <- textOf(file)
    } yield {
      assert(run.rendered.contains("missing.conf"), run.rendered)
      assertEquals(text, formatted)
    }
  }

  test("arguments: files, with --check or -c") {
    assertEquals(
      CmdApi.command.parse(List("--check", "a.conf", "b.conf")),
      Right(Arguments(List(Path("a.conf"), Path("b.conf")), checkOnly = true))
    )
    assertEquals(CmdApi.command.parse(List("-c", "a.conf")), Right(Arguments(List(Path("a.conf")), checkOnly = true)))
    assertEquals(CmdApi.command.parse(List("a.conf")), Right(Arguments(List(Path("a.conf")), checkOnly = false)))
  }

  test("arguments: at least one file is required") {
    assert(CmdApi.command.parse(Nil).isLeft)
  }
}
