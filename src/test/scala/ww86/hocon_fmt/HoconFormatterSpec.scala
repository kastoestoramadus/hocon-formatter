package ww86.hocon_fmt

import collection.mutable.Stack
import java.io.File

import ww86.hocon_fmt.HoconFormatter.*

class HoconFormatterSpec extends munit.FunSuite {
  test("include happy path supported") {
    val result = fmtFileToStr(new File("src/test/resources/test01.conf")).get
    //should be parsable by not throwing exception
    format(result)
    // println("\nResult:\n")
    // println(result)
    assertEquals(result,
      """test01 {
        |  include required("test01a"),
        |  include "test02"
        |}
        |""".stripMargin)
  }
  test("include feature skips include in string") {
    val result = fmtFileToStr(new File("src/test/resources/test02.conf")).get
    //should be parsable by not throwing exception
    format(result)
    // println("\nResult:\n")
    // println(result)
    assertEquals(result,
      """# include I am not
        |test01 {
        |  in-string: " include I am not"
        |}
        |in-multi-line-string: "\ninclude iam.not\n"
        |# another include I am not
        |include "test03-included.conf"
        |""".stripMargin)
  }
  test("unsupported formatting is documented") {
    val result = fmtFileToStr(new File("src/test/resources/test03.conf")).get
    //should be parsable by not throwing exception
    format(result)
    // println("\nResult:\n")
    // println(result)
    assertEquals(result,
      """test01 {
        |  include "test01b","booleans" : 42 // valid but not formated
        |}
        |# unsupported, include can't in multi lines
        |test02 {}
        |""".stripMargin)
  }
}