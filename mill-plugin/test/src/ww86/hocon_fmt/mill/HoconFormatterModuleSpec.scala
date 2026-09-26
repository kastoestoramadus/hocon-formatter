package ww86.hocon_fmt.mill

import java.io.{ByteArrayOutputStream, PrintStream}

import mill.*
import mill.api.{Discover, ExecResult}
import mill.testkit.{TestRootModule, UnitTester}

class HoconFormatterModuleSpec extends munit.FunSuite {

  object project extends TestRootModule, HoconFormatterModule {
    lazy val millDiscover = Discover[this.type]
  }

  object customSources extends TestRootModule, HoconFormatterModule {
    override def hoconFormatSources = Task.Sources("conf")
    lazy val millDiscover           = Discover[this.type]
  }

  final case class Run(eval: UnitTester, output: ByteArrayOutputStream) {
    def log: String = output.toString("UTF-8")
  }

  /** Evaluates tasks of `module` in a fresh directory holding `files`, capturing what they log. */
  def withFiles[A](module: TestRootModule, files: (String, Array[Byte])*)(test: Run => A): A = {
    val output = ByteArrayOutputStream()
    val stream = PrintStream(output, true, "UTF-8")
    UnitTester(module, os.temp.dir(), outStream = stream, errStream = stream).scoped { eval =>
      files.foreach((path, content) => os.write(module.moduleDir / os.RelPath(path), content, createFolders = true))
      test(Run(eval, output))
    }
  }

  def text(content: String): Array[Byte] = content.getBytes("UTF-8")

  def read(module: TestRootModule, path: String): String = os.read(module.moduleDir / os.RelPath(path))

  def readBytes(module: TestRootModule, path: String): Array[Byte] =
    os.read.bytes(module.moduleDir / os.RelPath(path))

  def failure[A](result: Either[ExecResult.Failing[A], ?]): String = result match {
    case Left(ExecResult.Failure(message, _)) => message
    case other                                => fail(s"expected a failure, got $other")
  }

  test("hoconFormat rewrites the files that are not formatted, and only those") {
    withFiles(
      project,
      "resources/application.conf" -> text("app {\n    name  =  \"svc\"\n   port =8080\n}\n"),
      "resources/formatted.conf"   -> text("retries: 3\n")
    ) { run =>
      val formattedBefore = os.mtime(project.moduleDir / "resources" / "formatted.conf")
      assert(run.eval(project.hoconFormat()).isRight)
      assertEquals(read(project, "resources/application.conf"), "app {\n  name: svc\n  port: 8080\n}\n")
      assertEquals(os.mtime(project.moduleDir / "resources" / "formatted.conf"), formattedBefore)
      assert(run.log.contains("Formatted resources/application.conf"), run.log)
      assert(!run.log.contains("Formatted resources/formatted.conf"), run.log)
    }
  }

  test("hoconFormat carries include statements across") {
    withFiles(
      project,
      "resources/application.conf" -> text(
        "include \"other.conf\"\napp {\n      name   =   \"demo\"\n  include required(\"nested.conf\")\n    port = 8080\n}\n"
      )
    ) { run =>
      assert(run.eval(project.hoconFormat()).isRight)
      assertEquals(
        read(project, "resources/application.conf"),
        "include \"other.conf\"\napp {\n  name: demo\n  include required(\"nested.conf\")\n  port: 8080\n}\n"
      )
    }
  }

  test("hoconFormat leaves each refused file byte for byte, warns once, and formats the rest") {
    val refused = Seq(
      "resources/broken.conf"      -> text("a : ${\n"),
      "resources/latin1.conf"      -> Array[Byte](0x61, 0x20, 0x3a, 0x20, 0x22, 0x63, 0x61, 0x66, 0xe9.toByte, 0x22, 0x0a),
      "resources/plus-equals.conf" -> text("a : [1]\na += 2\n")
    )
    withFiles(project, refused :+ ("resources/acceptable.conf" -> text("retries   =   3\n"))*) { run =>
      assert(run.eval(project.hoconFormat()).isRight)
      refused.foreach { (path, content) =>
        assertEquals(readBytes(project, path).toSeq, content.toSeq, path)
        assertEquals(s"Leaving $path unchanged".r.findAllMatchIn(run.log).size, 1, run.log)
      }
      assertEquals(read(project, "resources/acceptable.conf"), "retries: 3\n")
    }
  }

  test("hoconFormatCheck fails naming each unformatted file, and writes nothing") {
    withFiles(
      project,
      "resources/a.conf" -> text("a   =   1\n"),
      "resources/b.conf" -> text("b   =   2\n"),
      "resources/c.conf" -> text("c: 3\n")
    ) { run =>
      val message = failure(run.eval(project.hoconFormatCheck()))
      assert(message.contains("2 HOCON files are not formatted"), message)
      assert(run.log.contains("Not formatted: resources/a.conf"), run.log)
      assert(run.log.contains("Not formatted: resources/b.conf"), run.log)
      assert(!run.log.contains("resources/c.conf"), run.log)
      assertEquals(read(project, "resources/a.conf"), "a   =   1\n")
      assertEquals(read(project, "resources/b.conf"), "b   =   2\n")
    }
  }

  test("hoconFormatCheck passes when every file is formatted or refused") {
    withFiles(
      project,
      "resources/formatted.conf" -> text("retries: 3\n"),
      "resources/broken.conf"    -> text("a : ${\n")
    ) { run =>
      assert(run.eval(project.hoconFormatCheck()).isRight)
      assert(run.log.contains("Leaving resources/broken.conf unchanged"), run.log)
    }
  }

  test("only .conf and .hocon files are examined") {
    withFiles(
      project,
      "resources/service.hocon"  -> text("a   =   1\n"),
      "resources/data.json"      -> text("{ \"a\" :   1 }\n"),
      "resources/app.properties" -> text("a   =   1\n")
    ) { run =>
      assert(run.eval(project.hoconFormat()).isRight)
      assertEquals(read(project, "resources/service.hocon"), "a: 1\n")
      assertEquals(read(project, "resources/data.json"), "{ \"a\" :   1 }\n")
      assertEquals(read(project, "resources/app.properties"), "a   =   1\n")
    }
  }

  test("hoconFormatSources replaces the resources, and a file outside them is left alone") {
    withFiles(
      customSources,
      "conf/nested/service.conf" -> text("a   =   1\n"),
      "resources/other.conf"     -> text("b   =   2\n")
    ) { run =>
      assert(run.eval(customSources.hoconFormat()).isRight)
      assertEquals(read(customSources, "conf/nested/service.conf"), "a: 1\n")
      assertEquals(read(customSources, "resources/other.conf"), "b   =   2\n")
    }
  }
}
