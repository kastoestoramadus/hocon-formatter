package ww86.hocon_fmt

import org.ekrich.config.{ConfigFactory, ConfigParseOptions}

import ww86.hocon_fmt.HoconFormatter.*

/** Which occurrences of the word `include` are treated as a directive and which are ordinary text.
  *
  * This is the contract of the include-detection regex, isolated from rendering and from the
  * whole-file invariants, so a change to that regex has one place to answer to.
  */
class IncludeDetectionSpec extends munit.FunSuite {

  private val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)

  private def formatted(raw: String): String = format(raw).get

  // --- A directive is recognised and survives formatting -------------------------------------

  private val directives = Map(
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

  private val notDirectives = List(
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
      assertEquals(
        ConfigFactory.parseString(out, parseOptions),
        ConfigFactory.parseString(raw, parseOptions),
        s"meaning changed for: $raw"
      )
    }
  }

  // --- Documented limitations ------------------------------------------------------------------
  // These pin down today's behaviour, not desired behaviour. They are expected to fail once the
  // same-line include handling is solved, which is the signal that the limitation is gone.

  test("LIMITATION: an include sharing its line with a closing brace fails to parse") {
    assert(
      format("""o { include "f.conf" }""").isFailure,
      "same-line include now works - update this test and the note in CLAUDE.md"
    )
  }

  test("LIMITATION: an include with no whitespace after it is silently dropped") {
    assertEquals(
      formatted("""include"f.conf""""),
      "",
      "include\"f.conf\" now survives - update this test and the note in CLAUDE.md"
    )
  }
}
