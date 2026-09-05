package ww86.hocon_fmt

import java.io.File

import org.ekrich.config.{ConfigFactory, ConfigParseOptions}

import ww86.hocon_fmt.HoconFormatter.*

/** Invariants that must hold for every input, as a safety net under changes to the
  * include-placeholder preprocessing.
  */
class HoconFormatterInvariantsSpec extends munit.FunSuite {

  private val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)

  // Shared with GoldenFileSpec so golden files are never mistaken for inputs.
  private val resourceFiles: List[File] = GoldenFileSpec.inputs

  test("test resources are discovered") {
    assert(resourceFiles.nonEmpty, "no .conf fixtures found")
  }

  // Formatting is a fixed point: running it twice must not differ from running it once.
  // This is what catches the placeholder round-trip breaking on already-formatted input.
  resourceFiles.foreach { file =>
    test(s"idempotent: ${file.getName}") {
      val once  = fmtFileToStr(file).get
      val twice = format(once).get
      assertEquals(twice, once, s"second pass changed the output of ${file.getName}")
    }
  }

  // The formatter's output must itself be valid HOCON. This used to be an unlabelled
  // side effect inside the example-based tests, where its result was discarded.
  resourceFiles.foreach { file =>
    test(s"output re-parses: ${file.getName}") {
      val once = fmtFileToStr(file).get
      assert(format(once).isSuccess, s"formatted output of ${file.getName} does not re-parse")
    }
  }

  // Include-free configs can be parsed directly, so meaning can be compared before
  // and after formatting without going through the formatter's own preprocessing.
  private val semanticCases = Map(
    "nested objects"   -> """a { b { c : 1 }, d : "x" }""",
    "list and numbers" -> """xs : [1, 2, 3]
                            |pi : 3.14
                            |flag : true""".stripMargin,
    "comments and quoted keys" -> """# leading
                                    |"quoted.key" : "value" // trailing
                                    |plain : 42""".stripMargin,
    "multi-line string" -> "s : \"\"\"\nline1\nline2\n\"\"\"",
    "substitution"      -> """base : 1
                        |derived : ${base}""".stripMargin
  )

  semanticCases.foreach { case (name, raw) =>
    test(s"meaning preserved: $name") {
      val formatted = format(raw).get
      assertEquals(
        ConfigFactory.parseString(formatted, parseOptions),
        ConfigFactory.parseString(raw, parseOptions),
        s"formatting changed the meaning of: $name"
      )
    }
  }

  // Adversarial: the preprocessing injects "__REMOVEn: ME" markers into the source.
  // Content that already looks like a marker must not be mistaken for one.
  // The dangerous post-processing regex is "\n\s*__REMOVEd+: ME", so a marker at the
  // start of a line is the shape that could actually be swallowed.
  private val markerCases = Map(
    "as a value"                 -> """key : "__REMOVE0: ME"""",
    "at start of line"           -> "a : 1\n\"__REMOVE0: ME\" : 2",
    "inside a multi-line string" -> "s : \"\"\"\n__REMOVE0: ME\n\"\"\"",
    "bare marker line"           -> "__REMOVE0 : \"ME\"\nb : 2"
  )

  markerCases.foreach { case (name, raw) =>
    test(s"placeholder text is not mistaken for a marker: $name") {
      val formatted = format(raw).get
      assertEquals(
        ConfigFactory.parseString(formatted, parseOptions),
        ConfigFactory.parseString(raw, parseOptions),
        s"marker-like content was corrupted: $name"
      )
    }
  }
}
