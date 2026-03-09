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

  private def isInsideString(str: String, pos: Int): Boolean = {
    var inString = false
    var i = 0
    while (i < pos && i < str.length) {
      val c = str.charAt(i)
      if (c == '"') {
        if (i + 2 < str.length && str.charAt(i + 1) == '"' && str.charAt(i + 2) == '"') {
          inString = !inString
          i += 2 // skip the next two "
        } else if (i == 0 || str.charAt(i - 1) != '\\') {
          inString = !inString
        }
      }
      i += 1
    }
    inString
  }

  private def replaceNonQuotedIncludeKeywordsWithPlaceholder(str: String): String = {
    val includePattern = Pattern.compile("""(?<!\w)include\s+""")
    val matcher = includePattern.matcher(str)
    val result = new StringBuffer()
    var idx = 0
    while (matcher.find()) {
      val matchStart = matcher.start()
      if (!isInsideString(str, matchStart)) {
        // removes also all new lines after include
        // idx is added to preserve the order and uniqueness of the temporary field
        // field is added to control the position of the include after formatting
        matcher.appendReplacement(result, s"__REMOVE$idx: ME, # __INCLUDE ")
        idx += 1
      } else {
        matcher.appendReplacement(result, matcher.group())
      }
    }
    matcher.appendTail(result)
    result.toString
  }

  def fmtFileToStr(file: File): Try[String] =
    Try(CmdApi.readStringFrom(file.toPath)).flatMap(format)

  def format(confStr: String): Try[String] =
    Try {
      val preprocessed = 
        replaceNonQuotedIncludeKeywordsWithPlaceholder(confStr)

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
          "__REMOVE\\d+: ME, # __INCLUDE",
          "include"
        )
        .replaceAll("\n\\s*__REMOVE\\d+: ME", "")
        .replaceAll("# __INCLUDE", "include")
    }
}
