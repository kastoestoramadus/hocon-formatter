package ww86.hocon_fmt

import org.ekrich.config.{ConfigFactory, ConfigParseOptions}

import ww86.hocon_fmt.HoconFormatter.*

/** Coverage of the HOCON specification, as seen through `HoconFormatter.format`.
  *
  * Everything here exercises our pipeline, so every test is named `formatter:`. Where the
  * formatter refuses an input, the underlying fault is the library's, not ours - those defects
  * are reproduced against bare sconfig in [[SconfigDefectsSpec]]. Our only responsibility is to
  * notice and refuse rather than write corrupted output.
  *
  * Coverage of the HOCON specification.
  *
  * Two groups: constructs the formatter destroys (bugs, pinned so a fix shows up as a failure),
  * and normalisations it performs on purpose (pinned so they are not "fixed" by accident).
  *
  * @see
  *   https://github.com/lightbend/config/blob/main/HOCON.md
  */
class HoconSpecCoverageSpec extends munit.FunSuite with HoconTestSupport {

  // --- Never hand back output we cannot read again ---------------------------------------------
  // These constructs cannot survive the parse-render round trip. Returning Success with
  // unparseable output is the worst outcome available, because CmdApi writes on success:
  // rewrite mode would replace a valid config with a broken one. Refusing is correct.

  val mustRefuse = Map(
    "+= field separator"            -> "a : [1]\na += 2",
    "+= field separator, nested"    -> "o { a : [1]\na += 2 }",
    "self-referential substitution" -> "a : 1\na : ${a}"
  )

  mustRefuse.foreach { case (name, raw) =>
    test(s"formatter: refuses rather than corrupts: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      assert(
        format(raw).isFailure,
        s"$name: formatter reported success but the output cannot be parsed back"
      )
    }
  }

  // Examples taken verbatim from the HOCON specification. A repeated key whose later definition
  // substitutes the earlier one renders as an unresolved-merge banner: it parses, so the
  // output check passes, but it is not a fixed point - a second pass changes it again.
  val specSelfReference = Map(
    "substitution cycle (Examples of Self-Referential Substitutions)" ->
      "a : 1\nb : 2\na : ${b}\nb : ${a}",
    "array self-concatenation (Array and object concatenation)" ->
      "a : [ 1, 2 ]\na : ${a} [ 3, 4 ]"
  )

  specSelfReference.foreach { case (name, raw) =>
    test(s"formatter: refuses output that is not a fixed point: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      assert(
        format(raw).isFailure,
        s"$name: output parses but a second pass changes it again"
      )
    }
  }

  // --- Normalised on purpose: meaning kept, original spelling not ------------------------------

  val normalised = List(
    ("// comments become #", "// c\na : 1", "# c"),
    ("= separator becomes :", "a = 1", "a: 1"),
    ("nested objects are flattened to paths", "a { b { c : 1 } }", "a.b.c: 1"),
    ("triple-quoted strings become escaped", "a : \"\"\"x\ny\"\"\"", "a: \"x\\ny\""),
    ("number literals are canonicalised", "a : 1.5e3", "a: 1500"),
    ("unicode escapes are resolved", "a : \"\\u0041\"", "a: A")
  )

  normalised.foreach { case (name, raw, expectedFragment) =>
    test(s"formatter: normalised: $name") {
      val out = formatted(raw)
      assert(out.contains(expectedFragment), s"expected [$expectedFragment] in: $out")
      assertSameMeaning(out, raw, s"normalisation changed meaning: $raw")
    }
  }

  // --- Supported, guarded against regression ---------------------------------------------------

  val supported = Map(
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
    test(s"formatter: supported: $name") {
      val out = formatted(raw)
      assert(out.parses.isSuccess, s"output does not re-parse: $out")
      assertSameMeaning(out, raw, s"meaning changed for: $raw")
    }
  }
}
