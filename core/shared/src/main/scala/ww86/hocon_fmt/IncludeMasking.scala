package ww86.hocon_fmt

import java.util.regex.{Matcher, Pattern}

/** Carries `include` directives across a parse-render round trip.
  *
  * sconfig resolves an include while parsing and keeps nothing to render afterwards, so the
  * directive would simply vanish. Each whole statement is swapped for a placeholder field before
  * parsing and swapped back once the rendered text comes out.
  *
  * Swapping the whole statement, rather than the keyword alone, is what lets an include share a
  * line with other content. The scheme this replaced commented out the rest of the line, which
  * swallowed closing braces and any entries following the include.
  */
private[hocon_fmt] object IncludeMasking {

  /** Masked text, plus the statements it replaced keyed by their placeholder index. */
  case class Masked(text: String, originals: Map[Int, String])

  def mask(source: String): Masked = {
    val masked     = new StringBuilder
    val originals  = Map.newBuilder[Int, String]
    val keywords   = IncludeKeyword.matcher(source)
    var copiedUpTo = 0
    var nextIndex  = 0

    while (keywords.find())
      if (keywords.start >= copiedUpTo && !isInsideStringLiteral(source, keywords.start))
        targetAfterKeyword(source, keywords.end).foreach { target =>
          masked.append(source.substring(copiedUpTo, keywords.start))
          masked.append(placeholderFor(nextIndex))
          originals += nextIndex -> s"include ${target.text}"
          nextIndex += 1
          copiedUpTo = target.endIndex
        }

    masked.append(source.substring(copiedUpTo))
    Masked(masked.result(), originals.result())
  }

  def unmask(rendered: String, originals: Map[Int, String]): String = {
    val restored = replaceEachMatch(rendered, PlaceholderField) { field =>
      // Compared as text, as a backreference would: `__INCLUDE_01` is not placeholder 1.
      val sameIndex = field.group(1) == field.group(2)
      // An index we never handed out belongs to the user's own text: leave it untouched.
      Option.when(sameIndex)(field.group(1).toInt).flatMap(originals.get)
    }
    // Own-line first: it consumes the newline and indentation, which the inline pattern leaves
    // behind. The other order turns every guard on its own line into a blank one.
    val ours                     = originals.keySet
    def dropOurs(guard: Matcher) = Option.when(ours(guard.group(1).toInt))("")
    val withoutOwnLineGuards     = replaceEachMatch(restored, GuardOnItsOwnLine)(dropOurs)
    replaceEachMatch(withoutOwnLineGuards, GuardInline)(dropOurs)
  }

  // ---- placeholders --------------------------------------------------------------------------
  //
  // The guard field exists because setSimplifyNestedObjects collapses a single-field object into
  // a dotted path: `o { X: v }` becomes `o.X: v`, which would move the placeholder out of its
  // object and leave nothing to restore. A second field keeps the object from collapsing.

  private val PlaceholderPrefix = "__INCLUDE_"
  private val GuardPrefix       = "__INCLUDE_GUARD_"
  private val GuardValue        = "g"

  private def placeholderFor(index: Int): String =
    s"""$PlaceholderPrefix$index : "$PlaceholderPrefix$index", """ +
      s"""$GuardPrefix$index : "$GuardValue""""

  private val OptionalQuote = """["]?"""

  /** Built from the same constants the placeholders are written with, so the two cannot drift.
    *
    * The value's index is captured rather than backreferenced to the key's, because Scala Native's
    * `java.util.regex` is RE2-based and has no backreferences; `unmask` compares the two.
    */
  private val PlaceholderField = Pattern.compile(
    s"""$OptionalQuote$PlaceholderPrefix(\\d+)$OptionalQuote\\s*:""" +
      s"""\\s*$OptionalQuote$PlaceholderPrefix(\\d+)$OptionalQuote"""
  )

  // The renderer may or may not quote the guard value, so both spellings have to match.
  private val guardField =
    s"""$OptionalQuote$GuardPrefix(\\d+)$OptionalQuote[ \\t]*:[ \\t]*$OptionalQuote$GuardValue$OptionalQuote"""
  private val GuardOnItsOwnLine = Pattern.compile(s"""\\n[ \\t]*$guardField""")
  private val GuardInline       = Pattern.compile(s""",?[ \\t]*$guardField""")

  /** Rewrites every match `replacement` accepts, leaving the rest verbatim.
    *
    * A rejected match is not consumed: the search resumes one character past its start, as a
    * regex engine does when a backreference fails there. Consuming it would let a near miss
    * swallow the key of a real placeholder that follows it.
    */
  private def replaceEachMatch(text: String, pattern: Pattern)(
      replacement: Matcher => Option[String]
  ): String = {
    val matcher    = pattern.matcher(text)
    val out        = new StringBuilder
    var copiedUpTo = 0
    var from       = 0
    while (matcher.find(from))
      replacement(matcher) match {
        case Some(next) =>
          out.append(text.substring(copiedUpTo, matcher.start)).append(next)
          copiedUpTo = matcher.end
          from = matcher.end
        case None => from = matcher.start + 1
      }
    out.append(text.substring(copiedUpTo)).toString
  }

  // ---- locating an include statement ---------------------------------------------------------

  private val IncludeKeyword  = Pattern.compile("""\binclude""")
  private val TargetFunctions = List("required", "file", "url", "classpath")

  /** The target text of an include whose keyword ends at `afterKeyword`, and where the statement
    * ends. `None` when what follows is not a target at all, which is how `include_path` and the
    * word "include" in prose are left alone.
    */
  private case class Target(text: String, endIndex: Int)

  private def targetAfterKeyword(source: String, afterKeyword: Int): Option[Target] = {
    val start = skipWhitespace(source, afterKeyword)
    if (start >= source.length) None
    else if (source.charAt(start) == '"')
      endOfQuotedString(source, start).map(end => Target(source.substring(start, end), end))
    else
      for {
        function <- TargetFunctions.find(source.startsWith(_, start))
        openParen = skipWhitespace(source, start + function.length)
        if openParen < source.length && source.charAt(openParen) == '('
        end <- endOfParenGroup(source, openParen)
      } yield Target(function + source.substring(openParen, end), end)
  }

  private def skipWhitespace(source: String, from: Int): Int = {
    var i = from
    while (i < source.length && Character.isWhitespace(source.charAt(i))) i += 1
    i
  }

  /** One past the closing quote, or `None` when the literal is unterminated. */
  private def endOfQuotedString(source: String, start: Int): Option[Int] = {
    var i = start + 1
    while (i < source.length)
      source.charAt(i) match {
        case '\\' => i += 2
        case '"'  => return Some(i + 1)
        case _    => i += 1
      }
    None
  }

  /** One past the matching close paren, or `None` when the group is unbalanced. */
  private def endOfParenGroup(source: String, start: Int): Option[Int] = {
    var i     = start
    var depth = 0
    while (i < source.length)
      source.charAt(i) match {
        case '"' =>
          endOfQuotedString(source, i) match {
            case Some(end) => i = end
            case None      => return None
          }
        case '(' => depth += 1; i += 1
        case ')' =>
          depth -= 1
          if (depth == 0) return Some(i + 1) else i += 1
        case _ => i += 1
      }
    None
  }

  /** Whether `position` falls inside a quoted or triple-quoted literal. */
  private def isInsideStringLiteral(source: String, position: Int): Boolean = {
    var inside = false
    var i      = 0
    while (i < position && i < source.length) {
      if (source.charAt(i) == '"') {
        val isTripleQuote =
          i + 2 < source.length && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"'
        val isEscaped = i > 0 && source.charAt(i - 1) == '\\'
        if (isTripleQuote) { inside = !inside; i += 2 }
        else if (!isEscaped) inside = !inside
      }
      i += 1
    }
    inside
  }
}
