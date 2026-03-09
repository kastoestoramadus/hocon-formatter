package ww86.hocon_fmt

import org.ekrich.config.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Try
import java.util.regex.*

object HoconFormatter {
  private val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)

  private val formattingOptions = ConfigFormatOptions.defaults
    .setKeepOriginOrder(true)
    .setDoubleIndent(false)
    .setColonAssign(true)
    .setSimplifyNestedObjects(true)

  private val renderOptions = ConfigRenderOptions.defaults
    .setJson(false)
    .setOriginComments(false)
    .setComments(true)
    .setFormatted(true)
    .setConfigFormatOptions(formattingOptions)

  def fmtFileToStr(file: File): Try[String] =
    Try(CmdApi.readStringFrom(file.toPath)).flatMap(format)

  def format(confStr: String): Try[String] =
    Try {
      val preprocessed = confStr
        .split("\n")
        .zipWithIndex
        .map { (line, idx) =>
          val forLineChecks =
            " " + line.takeWhile(_ != '#').replaceAll("""//.*$""", "")
          // adding " " is a trick for newline include
          if (forLineChecks.contains(" include "))
            // second entry to keep place for comment with include
            s"__REMOVE$idx: ME," + line.replace("include ", s"# __INCLUDE ")
          else line
        }
        .mkString("\n")

      // throw exception if not parsable
      ConfigFactory.parseString(preprocessed, parseOptions)

      // println(preprocessed)
      val parsed = ConfigFactory.parseString(preprocessed, parseOptions)
      val formatted =
        if (parsed.isEmpty) "" // without it, it produced "{}"
        else
          parsed.root
            .render(renderOptions)
      formatted
        .replaceAll(
          "__REMOVE7: ME, # __INCLUDE",
          "include"
        ) // walkaround for in quotes
        .replaceAll("\n\\s*__REMOVE\\d+: ME", "")
        .replaceAll("# __INCLUDE", "include")
    }
}
