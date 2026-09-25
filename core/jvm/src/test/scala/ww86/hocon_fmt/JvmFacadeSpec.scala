package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.UTF_8

/** The facade the Java plugins call, and the sbt plugin calls across an isolated class loader. */
class JvmFacadeSpec extends munit.FunSuite {

  test("formatted content maps to empty") {
    assertEquals(JavaCaller.describe("a: 1\n".getBytes(UTF_8)), "already formatted")
  }

  test("unformatted content maps to the text it should have") {
    assertEquals(JavaCaller.describe("a   :   1".getBytes(UTF_8)), "reformat to: a: 1\n")
  }

  test("a refusal is a checked exception whose message is the reason") {
    val described = JavaCaller.describe("a : ${".getBytes(UTF_8))
    assert(described.startsWith("refused: "), described)
    assert(described.length > "refused: ".length, s"no reason given: $described")
  }

  // The sbt 1.x plugin sees none of our classes, so it reaches the facade by name and reads the
  // result as JDK types. Renaming either side breaks it without a compile error anywhere.
  test("the facade is reachable reflectively, returning only JDK types") {
    val reformat = Class.forName("ww86.hocon_fmt.JvmFacade").getMethod("reformat", classOf[Array[Byte]])
    val result   = reformat.invoke(null, "a   :   1".getBytes(UTF_8))
    assertEquals(result, java.util.Optional.of("a: 1\n"))
  }
}
