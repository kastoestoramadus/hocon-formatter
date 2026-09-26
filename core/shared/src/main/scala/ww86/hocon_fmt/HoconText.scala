package ww86.hocon_fmt

import scala.annotation.tailrec

/** Which parts of a HOCON text are code, strings and comments, found in one left-to-right pass.
  *
  * Enough lexing to answer two questions: whether an `include` is code, and which comments a text
  * holds. Deciding the first by counting quotes from the start of the file went wrong on a quote
  * inside a comment, and made every `include` cost a scan of everything before it.
  */
private[hocon_fmt] object HoconText {

  enum Kind { case Quoted, Comment }

  /** `text.substring(start, end)`; code is whatever no span covers. */
  final case class Span(kind: Kind, start: Int, end: Int)

  def spans(text: String): Vector[Span] = {
    @tailrec
    def scan(i: Int, found: Vector[Span]): Vector[Span] =
      if (i >= text.length) found
      else if (text.startsWith("\"\"\"", i)) {
        val end = endOfTripleQuoted(text, i + 3)
        scan(end, found :+ Span(Kind.Quoted, i, end))
      } else if (text.charAt(i) == '"') {
        val end = endOfQuoted(text, i + 1)
        scan(end, found :+ Span(Kind.Quoted, i, end))
      } else if (text.charAt(i) == '#' || text.startsWith("//", i)) {
        val end = endOfLine(text, i)
        scan(end, found :+ Span(Kind.Comment, i, end))
      } else scan(i + 1, found)
    scan(0, Vector.empty)
  }

  /** Whether `position` falls in code rather than in a string or a comment. */
  def isCode(spans: Vector[Span], position: Int): Boolean = {
    // The spans are ordered and disjoint: find the last one starting at or before `position`.
    @tailrec
    def search(low: Int, high: Int): Boolean =
      if (low > high) high < 0 || spans(high).end <= position
      else {
        val middle = (low + high) >>> 1
        if (spans(middle).start <= position) search(middle + 1, high) else search(low, middle - 1)
      }
    search(0, spans.size - 1)
  }

  /** Each comment's text without its marker or surrounding whitespace, in order. sconfig rewrites
    * `//` as `#` and trims, so this is the form that survives formatting.
    */
  def comments(text: String): List[String] =
    spans(text).toList.collect { case Span(Kind.Comment, start, end) =>
      val body = text.substring(start, end)
      body.stripPrefix("#").stripPrefix("//").trim
    }

  // A triple-quoted string ends at the last quote of the first run of three or more.
  private def endOfTripleQuoted(text: String, from: Int): Int = {
    val close = text.indexOf("\"\"\"", from)
    if (close < 0) text.length
    else {
      val afterRun = text.indexWhere(_ != '"', close)
      if (afterRun < 0) text.length else afterRun
    }
  }

  // An unterminated string stops at the end of its line, as sconfig's does.
  @tailrec
  private def endOfQuoted(text: String, i: Int): Int =
    if (i >= text.length) text.length
    else
      text.charAt(i) match {
        case '\\' => endOfQuoted(text, i + 2)
        case '"'  => i + 1
        case '\n' => i
        case _    => endOfQuoted(text, i + 1)
      }

  private def endOfLine(text: String, from: Int): Int = {
    val newline = text.indexOf('\n', from)
    if (newline < 0) text.length else newline
  }
}
