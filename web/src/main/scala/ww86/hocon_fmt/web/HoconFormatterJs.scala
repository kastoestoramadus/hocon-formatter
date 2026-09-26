package ww86.hocon_fmt.web

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

import ww86.hocon_fmt.Refusal

// Declared for the tests; implemented in the next commit.
@JSExportTopLevel("HoconFormatter")
object HoconFormatterJs {

  @JSExport
  def format(source: String): js.Object = js.Object()

  @JSExport
  val version: String = ""

  def nameOf(refusal: Refusal): String = ""
}
