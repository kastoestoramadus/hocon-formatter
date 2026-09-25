package ww86.hocon_fmt

import java.io.File

import ww86.hocon_fmt.HoconFormatter.*

/** Invariants that must hold for every input, as a safety net under changes to the
  * include-placeholder preprocessing.
  */
class HoconFormatterInvariantsSpec extends munit.FunSuite with HoconTestSupport {

  // Shared with GoldenFileSpec so golden files are never mistaken for inputs.
  val resourceFiles: List[File] = FileFixtures.inputs

  test("test resources are discovered") {
    assert(resourceFiles.nonEmpty, "no .conf fixtures found")
  }

  // Formatting is a fixed point: running it twice must not differ from running it once.
  // This is what catches the placeholder round-trip breaking on already-formatted input.
  resourceFiles.foreach { file =>
    test(s"idempotent: ${file.getName}") {
      val once  = formatted(FileFixtures.read(file))
      val twice = formatted(once)
      assertEquals(twice, once, s"second pass changed the output of ${file.getName}")
    }
  }

  // The formatter's output must itself be valid HOCON. This used to be an unlabelled
  // side effect inside the example-based tests, where its result was discarded.
  resourceFiles.foreach { file =>
    test(s"output re-parses: ${file.getName}") {
      val once = formatted(FileFixtures.read(file))
      assert(format(once).isSuccess, s"formatted output of ${file.getName} does not re-parse")
    }
  }

  // Include-free configs can be parsed directly, so meaning can be compared before
  // and after formatting without going through the formatter's own preprocessing.
  val semanticCases = Map(
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
      assertSameMeaning(formatted(raw), raw, s"formatting changed the meaning of: $name")
    }
  }

  // Adversarial: the masking injects `__INCLUDE_<n>` and `__INCLUDE_GUARD_<n>` fields into the
  // source. Content that already looks like one must not be mistaken for the real thing.
  val markerCases = Map(
    "placeholder as a value"      -> """key : "__INCLUDE_0"""",
    "placeholder as a key"        -> """"__INCLUDE_0" : 1""",
    "full placeholder field"      -> """__INCLUDE_0 : "__INCLUDE_0"""",
    "guard field"                 -> """__INCLUDE_GUARD_0 : "g"""",
    "guard field, unquoted value" -> """__INCLUDE_GUARD_0 : g""",
    "inside a multi-line string"  -> "s : \"\"\"\n__INCLUDE_0\n\"\"\""
  )

  markerCases.foreach { case (name, raw) =>
    test(s"marker-like input is not mistaken for a marker: $name") {
      assertSameMeaning(formatted(raw), raw, s"marker-like content was corrupted: $name")
    }
  }
}
