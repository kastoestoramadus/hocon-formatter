package ww86.hocon_fmt

import org.ekrich.config.*

import java.io.File
import java.util.regex.*
import scala.util.Try

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
    var i        = 0
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

  // ---- include masking -------------------------------------------------------------------------
  //
  // An include cannot survive parse-then-render, so each *whole statement* is swapped for a
  // placeholder field before parsing and swapped back afterwards.
  //
  // Replacing the whole statement (rather than just the keyword, with the remainder commented
  // out) is what lets an include share its line with other content: nothing is commented out,
  // so a closing brace or a following entry is still seen by the parser.
  //
  // The guard field exists because setSimplifyNestedObjects collapses a single-field object into
  // a dotted path - `o { X: v }` becomes `o.X: v` - which would move the placeholder key out of
  // its object and make it unrestorable. A second field keeps the object from collapsing. It is
  // removed again on the way out.

  private val includeKeyword   = Pattern.compile("""\binclude""")
  private val includeFunctions = List("required", "file", "url", "classpath")

  private def skipWhitespace(str: String, from: Int): Int = {
    var i = from
    while (i < str.length && Character.isWhitespace(str.charAt(i))) i += 1
    i
  }

  /** Index one past a quoted string starting at `start`, or -1 if it is unterminated. */
  private def quotedEnd(str: String, start: Int): Int = {
    var i = start + 1
    while (i < str.length) {
      val c = str.charAt(i)
      if (c == '\\') i += 2
      else if (c == '"') return i + 1
      else i += 1
    }
    -1
  }

  /** Index one past a parenthesised group starting at `start`, or -1 if it is unbalanced. */
  private def parenEnd(str: String, start: Int): Int = {
    var i     = start
    var depth = 0
    while (i < str.length) {
      str.charAt(i) match {
        case '"' =>
          val e = quotedEnd(str, i)
          if (e < 0) return -1 else i = e
        case '(' => depth += 1; i += 1
        case ')' => depth -= 1; if (depth == 0) return i + 1 else i += 1
        case _   => i += 1
      }
    }
    -1
  }

  /** The target of an include whose keyword ends at `afterKeyword`, plus the index one past the
    * whole statement. `None` when what follows is not an include target, so `include_path` and a
    * bare `include` in prose are left alone.
    */
  private def includeTarget(str: String, afterKeyword: Int): Option[(String, Int)] = {
    val t = skipWhitespace(str, afterKeyword)
    if (t >= str.length) None
    else if (str.charAt(t) == '"')
      Option(quotedEnd(str, t)).filter(_ > 0).map(e => (str.substring(t, e), e))
    else
      includeFunctions.find(str.startsWith(_, t)).flatMap { fn =>
        val p = skipWhitespace(str, t + fn.length)
        if (p < str.length && str.charAt(p) == '(') {
          val e = parenEnd(str, p)
          if (e > 0) Some((fn + str.substring(p, e), e)) else None
        } else None
      }
  }

  private def placeholder(i: Int): String =
    s"""__INCLUDE_$i : "__INCLUDE_$i", __INCLUDE_GUARD_$i : "g""""

  private[hocon_fmt] def maskIncludes(str: String): (String, Map[Int, String]) = {
    val out       = new StringBuilder
    val originals = scala.collection.mutable.Map.empty[Int, String]
    val matcher   = includeKeyword.matcher(str)
    var last      = 0
    var idx       = 0
    while (matcher.find()) {
      val kwStart = matcher.start()
      if (kwStart >= last && !isInsideString(str, kwStart)) {
        includeTarget(str, matcher.end()).foreach { case (target, end) =>
          out.append(str.substring(last, kwStart)).append(placeholder(idx))
          originals(idx) = s"include $target"
          idx += 1
          last = end
        }
      }
    }
    out.append(str.substring(last))
    (out.toString, originals.toMap)
  }

  private val placeholderField =
    Pattern.compile("""["]?__INCLUDE_(\d+)["]?\s*:\s*["]?__INCLUDE_\1["]?""")
  // The renderer may drop the quotes around the guard value, so both spellings must match.
  private val guardOwnLine =
    Pattern.compile("""\n[ \t]*["]?__INCLUDE_GUARD_(\d+)["]?[ \t]*:[ \t]*["]?g["]?""")
  private val guardInline =
    Pattern.compile(""",?[ \t]*["]?__INCLUDE_GUARD_(\d+)["]?[ \t]*:[ \t]*["]?g["]?""")

  /** Removes only the guards this run created. A field the user happens to have named
    * `__INCLUDE_GUARD_<n>` must survive, so the index has to be one we handed out.
    */
  private def removeOurGuards(str: String, pattern: Pattern, ours: Set[Int]): String = {
    val matcher = pattern.matcher(str)
    val out     = new StringBuffer()
    while (matcher.find()) {
      val keep = if (ours.contains(matcher.group(1).toInt)) "" else matcher.group()
      matcher.appendReplacement(out, Matcher.quoteReplacement(keep))
    }
    matcher.appendTail(out)
    out.toString
  }

  private[hocon_fmt] def unmaskIncludes(rendered: String, originals: Map[Int, String]): String = {
    val matcher = placeholderField.matcher(rendered)
    val out     = new StringBuffer()
    while (matcher.find()) {
      // An unknown index is the user's own text, not ours: leave it exactly as it was.
      val original = originals.getOrElse(matcher.group(1).toInt, matcher.group())
      matcher.appendReplacement(out, Matcher.quoteReplacement(original))
    }
    matcher.appendTail(out)

    val ours = originals.keySet
    removeOurGuards(removeOurGuards(out.toString, guardOwnLine, ours), guardInline, ours)
  }

  // ---- entry points ----------------------------------------------------------------------------

  def fmtFileToStr(file: File): Try[String] =
    Try(CmdApi.readStringFrom(file.toPath)).flatMap(format)

  def format(confStr: String): Try[String] =
    Try {
      val (masked, originals) = maskIncludes(confStr)

      // Throws when the input is not parseable, which is how callers learn to skip the file.
      val parsed = ConfigFactory.parseString(masked, parseOptions)

      val rendered =
        if (parsed.isEmpty) "" // without it, it produced "{}"
        else parsed.root.render(renderOptions)

      val restored = unmaskIncludes(rendered, originals)
      refuseIfNotReadableBack(restored)
      restored
    }

  /** Refuses output that is not valid HOCON.
    *
    * CmdApi writes on success, so returning malformed text would replace a valid config with a
    * broken one. Only a syntax error counts here: output containing `include required("x")`
    * throws while resolving includes, which says nothing about the text being well formed.
    */
  private def refuseIfNotReadableBack(rendered: String): Unit =
    try {
      ConfigFactory.parseString(rendered, parseOptions)
      ()
    } catch {
      case e: ConfigException.Parse =>
        throw new IllegalStateException(
          s"refusing to emit output that is not valid HOCON: ${e.getMessage}",
          e
        )
      // Anything else is include resolution - a missing required() target, an unreachable url -
      // which says nothing about whether the text we produced is well formed.
      case _: ConfigException => ()
    }
}
