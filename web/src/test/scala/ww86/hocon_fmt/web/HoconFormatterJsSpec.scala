package ww86.hocon_fmt.web

import scala.scalajs.js

import ww86.hocon_fmt.Refusal

/** The API as a page sees it: through the global the script defines, on the optimised script. */
class HoconFormatterJsSpec extends munit.FunSuite {

  def format(source: String): js.Dynamic = js.Dynamic.global.HoconFormatter.format(source)

  def field(result: js.Dynamic, name: String): Option[String] =
    result.selectDynamic(name).asInstanceOf[js.UndefOr[String]].toOption

  test("a text that needs formatting comes back formatted") {
    val result = format("app {\n    name  =  \"svc\"\n   port =8080\n}\n")
    assertEquals(field(result, "verdict"), Some("needsFormatting"))
    assertEquals(field(result, "formatted"), Some("app {\n  name: svc\n  port: 8080\n}\n"))
    assertEquals(field(result, "refusal"), None)
    assertEquals(field(result, "reason"), None)
  }

  test("an already formatted text comes back as it is") {
    val result = format("retries: 3\n")
    assertEquals(field(result, "verdict"), Some("alreadyFormatted"))
    assertEquals(field(result, "formatted"), Some("retries: 3\n"))
  }

  test("include statements survive, as sconfig alone on Scala.js cannot parse them") {
    val result = format("include \"local.conf\"\napp {\n  include required(\"db.conf\")\n    port = 8080\n}\n")
    assertEquals(
      field(result, "formatted"),
      Some("include \"local.conf\"\napp {\n  include required(\"db.conf\")\n  port: 8080\n}\n")
    )
  }

  test("a refused text has no formatted text, and says which refusal it is and why") {
    val result = format("server {\n  listen 80;\n}\n")
    assertEquals(field(result, "verdict"), Some("refused"))
    assertEquals(field(result, "formatted"), None)
    assertEquals(field(result, "refusal"), Some("notHocon"))
    assert(field(result, "reason").exists(_.startsWith("not valid HOCON: ")), field(result, "reason"))
  }

  test("a comment that would be lost is a refusal of its own") {
    val result = format("a : 1\n# trailing\n")
    assertEquals(field(result, "refusal"), Some("lostComment"))
    assertEquals(field(result, "reason"), Some("a comment would be lost: trailing"))
  }

  test("every refusal has a name a page can branch on") {
    val names = Map(
      Refusal.NotUtf8          -> "notUtf8",
      Refusal.NotHocon("")     -> "notHocon",
      Refusal.BrokenOutput("") -> "brokenOutput",
      Refusal.LostComment("")  -> "lostComment",
      Refusal.LostInclude("")  -> "lostInclude",
      Refusal.UnstableOutput   -> "unstableOutput"
    )
    names.foreach((refusal, name) => assertEquals(HoconFormatterJs.nameOf(refusal), name))
  }

  test("the script says which version of the formatter it is") {
    val version = js.Dynamic.global.HoconFormatter.version.asInstanceOf[String]
    assert(version.matches("""\d+\.\d+\.\d+.*"""), version)
  }
}
