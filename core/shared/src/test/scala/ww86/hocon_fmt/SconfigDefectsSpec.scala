package ww86.hocon_fmt

import org.ekrich.config.ConfigRenderOptions

/** Defects in sconfig itself, reproduced with a bare parse-render round trip: no include masking
  * and no other code of ours is involved.
  *
  * The division of labour in this project is that everything except `include` handling is the
  * library's job. So these are not ours to fix in `HoconFormatter` - they are upstream bugs, and
  * all `HoconFormatter` does about them is refuse to emit the result (see
  * `HoconSpecCoverageSpec`).
  *
  * Each test asserts what sconfig *should* do, so it is RED while the bug is open. They are
  * therefore excluded from `sbt test` and from CI, and run on demand:
  *
  * {{{ sbt libraryDefects }}}
  *
  * When a future sconfig release fixes one, its test turns green - that is the signal to drop the
  * corresponding refusal from `HoconFormatter` and the note from CLAUDE.md.
  *
  * Examples are taken from the HOCON specification.
  * @see
  *   https://github.com/lightbend/config/blob/main/HOCON.md
  */
class SconfigDefectsSpec extends munit.FunSuite with HoconTestSupport {

  /** The resolved value, independent of how it is rendered. Used to prove that a plain-language
    * equivalent really is equivalent, instead of assuming it.
    */
  def resolvedValue(s: String): String =
    s.parsedConfig.resolve().root.render(ConfigRenderOptions.concise)

  // --- Should render as the plain equivalent ----------------------------------------------------
  //
  // Each case pairs an input sconfig mis-renders with a plainly written config that means the same
  // thing and that sconfig renders correctly. The expected output is therefore the real target
  // text, not a guess: every test first asserts that both sides resolve to the same value.

  val shouldRenderLike = List(
    ("`+=` field separator", "a : [1]\na += 2", "a : [1, 2]"),
    ("self-referential substitution", "a : 1\na : ${a}", "a : 1"),
    (
      "array concatenation with a substitution",
      "path = [ /bin ]\npath = ${path} [ /usr/bin ]",
      "path = [ /bin, /usr/bin ]"
    ),
    (
      "string concatenation with a substitution",
      "path : \"a:b:c\"\npath : ${path}\":d\"",
      "path : \"a:b:c:d\""
    ),
    (
      "nested self-reference",
      "foo : { a : { c : 1 } }\nfoo : ${foo.a}\nfoo : { a : 2 }",
      "foo : { c : 1, a : 2 }"
    ),
    (
      "object concatenation with a substitution",
      "g = { size = 6 }\ne = ${g} { name = \"east\" }",
      "g = { size = 6 }\ne = { size = 6, name = \"east\" }"
    ),
    // The specification lets an implementation resolve this cycle to 1, to 2, or to an error,
    // requiring only that a and b end up equal. sconfig picks 1, which is a legal choice, so
    // that is what its rendering has to reflect.
    ("substitution cycle", "a : 1\nb : 2\na : ${b}\nb : ${a}", "a : 1\nb : 1")
  )

  shouldRenderLike.foreach { case (name, broken, equivalent) =>
    test(s"library: $name should render as its plain equivalent") {
      assertEquals(
        resolvedValue(broken),
        resolvedValue(equivalent),
        s"premise of this test: the two forms must mean the same thing"
      )
      assertEquals(
        broken.renderedByLibrary,
        equivalent.renderedByLibrary,
        s"OPEN sconfig BUG: $name does not render as the equivalent [$equivalent]"
      )
    }
  }

  // --- Should accept what the specification allows -----------------------------------------------
  // These fail at parse time, so there is no rendering to compare: the target is that they parse.

  val rejectedOnInput = Map(
    "an array at the file root"         -> """[ "a", "b" ]""",
    "the `[]` env-variable list suffix" -> "my-list = ${MY_LIST[]}"
  )

  rejectedOnInput.foreach { case (name, raw) =>
    test(s"library: should parse $name") {
      val outcome = raw.parses
      assert(
        outcome.isSuccess,
        s"OPEN sconfig BUG: the HOCON specification allows $name, sconfig refuses it: " +
          outcome.failed.map(_.getMessage).getOrElse("")
      )
    }
  }
}
