package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** What formatting makes of a file's content — the decision every integration acts on, so the
  * CLI and the build-tool plugins cannot disagree about a file.
  */
class VerdictSpec extends munit.FunSuite with HoconTestSupport {

  test("formatted content needs nothing") {
    assertEquals(Verdict.of("a: 1\n"), Verdict.AlreadyFormatted)
  }

  test("unformatted content comes with the text it should have") {
    assertEquals(Verdict.of("a   :    1"), Verdict.NeedsFormatting("a: 1\n"))
  }

  test("content the formatter refuses carries the reason") {
    Verdict.of("a : ${") match {
      case Verdict.Refused(Refusal.NotHocon(_)) => ()
      case other                                => fail(s"expected NotHocon, got $other")
    }
  }

  test("bytes are decoded as UTF-8, so non-ASCII text survives") {
    Verdict.of("a   :   \"zażółć\"".getBytes(UTF_8)) match {
      case Verdict.NeedsFormatting(formatted) => assert(formatted.contains("zażółć"), formatted)
      case other                              => fail(s"expected NeedsFormatting, got $other")
    }
  }

  // A lenient decode turns each invalid byte into U+FFFD, and writing that back destroys it.
  test("bytes that are not UTF-8 are refused rather than decoded leniently") {
    val latin2 = "a : \"³\"".getBytes(ISO_8859_1) // ł in ISO-8859-2
    assertEquals(Verdict.of(latin2), Verdict.Refused(Refusal.NotUtf8))
  }

  test("every refusal explains itself") {
    val refusals = List(
      Refusal.NotUtf8,
      Refusal.NotHocon("detail"),
      Refusal.BrokenOutput("detail"),
      Refusal.LostComment("detail"),
      Refusal.UnstableOutput
    )
    refusals.foreach(r => assert(r.reason.nonEmpty, s"$r has no reason"))
  }
}
