package ww86.hocon_fmt

import org.ekrich.config.{ConfigFactory, ConfigParseOptions}

import ww86.hocon_fmt.HoconFormatter.*

/** Which occurrences of the word `include` are treated as a directive and which are ordinary text.
  *
  * This is the contract of the include-detection regex, isolated from rendering and from the
  * whole-file invariants, so a change to that regex has one place to answer to.
  */
class IncludeDetectionSpec extends munit.FunSuite with HoconTestSupport {

  // --- A directive is recognised and survives formatting -------------------------------------

  val directives = Map(
    "plain"              -> """include "f.conf"""",
    "required"           -> """include required("f.conf")""",
    "leading whitespace" -> """   include "f.conf""""
  )

  directives.foreach { case (name, raw) =>
    test(s"directive is preserved: $name") {
      assert(
        formatted(raw).contains("include"),
        s"the include directive disappeared from the output of: $raw"
      )
    }
  }

  test("directive is preserved: several on separate lines") {
    val out = formatted("include \"a.conf\"\ninclude \"b.conf\"")
    assert(out.contains("include \"a.conf\""), out)
    assert(out.contains("include \"b.conf\""), out)
  }

  // --- The bare word is NOT a directive -------------------------------------------------------
  // Third element is text that must survive verbatim, so mangling is caught even where
  // parse-equality alone would not notice (comments carry no meaning to compare).

  val notDirectives = List(
    ("suffix of a key", """my_include : 1""", "my_include"),
    ("glued prefix", """reinclude : 1""", "reinclude"),
    ("after underscore", """_include : 1""", "_include"),
    ("no whitespace after", """include_path : "/tmp"""", "include_path"),
    ("in a hash comment", "# include me not\na : 1", "# include me not"),
    ("in a quoted value", """a : " include me not"""", "\" include me not\""),
    ("in a quoted key", """"include me not" : 1""", "\"include me not\"")
  )

  notDirectives.foreach { case (name, raw, mustSurvive) =>
    test(s"not a directive: $name") {
      val out = formatted(raw)
      assert(out.contains(mustSurvive), s"expected [$mustSurvive] to survive, got: $out")
      assert(!out.contains("__REMOVE"), s"placeholder leaked into the output: $out")
      assertSameMeaning(out, raw, s"meaning changed for: $raw")
    }
  }

  // --- An include may share its line with other content ----------------------------------------
  // The preprocessing used to comment out the rest of the line, which swallowed closing braces
  // and any entries following the include.

  test("same line: include inside a one-line object") {
    val out = formatted("""o { include "f.conf" }""")
    assert(out.contains("""include "f.conf""""), out)
  }

  test("same line: entries after an include are formatted, not passed through") {
    val out = formatted("""o { include "f.conf", b   :    1 }""")
    assert(out.contains("""include "f.conf""""), out)
    assert(out.contains("b: 1"), s"entry after the include was not formatted: $out")
  }

  test("same line: closing brace survives so the result re-parses") {
    val raw = """o { include "f.conf" }"""
    assert(format(raw).isSuccess, "formatting failed outright")
    assert(format(formatted(raw)).isSuccess, "output does not survive a second pass")
  }

  test("no whitespace after include is still a directive") {
    val out = formatted("""include"f.conf"""")
    assert(out.contains("include"), s"the include was silently dropped: [$out]")
    assert(out.trim.nonEmpty, "output is empty - the include was lost")
  }

  test("include function forms survive sharing a line") {
    val out = formatted("""o { include required(file("f.conf")), b : 1 }""")
    assert(out.contains("required"), out)
    assert(out.contains("b: 1"), out)
  }
}
