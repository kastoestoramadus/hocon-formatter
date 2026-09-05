package ww86.hocon_fmt

import org.ekrich.config.{ConfigFactory, ConfigParseOptions}

import ww86.hocon_fmt.HoconFormatter.*

/** Coverage of the HOCON specification.
  *
  * Two groups: constructs the formatter destroys (bugs, pinned so a fix shows up as a failure),
  * and normalisations it performs on purpose (pinned so they are not "fixed" by accident).
  *
  * @see
  *   https://github.com/lightbend/config/blob/main/HOCON.md
  */
class HoconSpecCoverageSpec extends munit.FunSuite {

  private val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)

  private def parses(s: String): Boolean =
    try { ConfigFactory.parseString(s, parseOptions); true }
    catch { case _: Throwable => false }

  // --- Never hand back output we cannot read again ---------------------------------------------
  // These constructs cannot survive the parse-render round trip. Returning Success with
  // unparseable output is the worst outcome available, because CmdApi writes on success:
  // rewrite mode would replace a valid config with a broken one. Refusing is correct.

  private val mustRefuse = Map(
    "+= field separator"            -> "a : [1]\na += 2",
    "+= field separator, nested"    -> "o { a : [1]\na += 2 }",
    "self-referential substitution" -> "a : 1\na : ${a}"
  )

  mustRefuse.foreach { case (name, raw) =>
    test(s"refuses rather than corrupts: $name") {
      assert(parses(raw), s"the fixture itself must be valid HOCON: $raw")
      assert(
        format(raw).isFailure,
        s"$name: formatter reported success but the output cannot be parsed back"
      )
    }
  }

  // --- Normalised on purpose: meaning kept, original spelling not ------------------------------

  private val normalised = List(
    ("// comments become #", "// c\na : 1", "# c"),
    ("= separator becomes :", "a = 1", "a: 1"),
    ("nested objects are flattened to paths", "a { b { c : 1 } }", "a.b.c: 1"),
    ("triple-quoted strings become escaped", "a : \"\"\"x\ny\"\"\"", "a: \"x\\ny\""),
    ("number literals are canonicalised", "a : 1.5e3", "a: 1500"),
    ("unicode escapes are resolved", "a : \"\\u0041\"", "a: A")
  )

  normalised.foreach { case (name, raw, expectedFragment) =>
    test(s"normalised: $name") {
      val out = format(raw).get
      assert(out.contains(expectedFragment), s"expected [$expectedFragment] in: $out")
      assertEquals(
        ConfigFactory.parseString(out, parseOptions),
        ConfigFactory.parseString(raw, parseOptions),
        s"normalisation changed meaning: $raw"
      )
    }
  }

  // --- Supported, guarded against regression ---------------------------------------------------

  private val supported = Map(
    "substitution"           -> "b : 1\na : ${b}",
    "optional substitution"  -> "a : ${?MISSING}\nb : 2",
    "array concatenation"    -> "a : [1] [2]",
    "object concatenation"   -> "a : { x : 1 } { y : 2 }",
    "duplicate key merging"  -> "a { x : 1 }\na { y : 2 }",
    "path expression key"    -> "a.b.c : 1",
    "null and booleans"      -> "a : null\nb : true",
    "trailing commas"        -> "a : [1, 2, ]",
    "empty object and array" -> "a : {}\nb : []"
  )

  supported.foreach { case (name, raw) =>
    test(s"supported: $name") {
      val out = format(raw).get
      assert(parses(out), s"output does not re-parse: $out")
      assertEquals(
        ConfigFactory.parseString(out, parseOptions),
        ConfigFactory.parseString(raw, parseOptions),
        s"meaning changed for: $raw"
      )
    }
  }
}
