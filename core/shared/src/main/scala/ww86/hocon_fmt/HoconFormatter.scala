package ww86.hocon_fmt

import org.ekrich.config.*
import scala.util.Try

/** Formats HOCON text.
  *
  * Everything except `include` handling is sconfig's job: this parses, re-renders, and puts the
  * include statements back. When output comes out wrong for any other reason the bug is upstream,
  * and the response here is to refuse the file rather than work around it — see `SconfigDefectsSpec`.
  */
object HoconFormatter {

  // Visible to the tests: a copy over there would drift, and a library test rendering with
  // options other than these would prove nothing about this formatter.
  private[hocon_fmt] val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)

  private val formattingOptions = ConfigFormatOptions.defaults
    .setKeepOriginOrder(true)
    .setDoubleIndent(false)
    .setColonAssign(true)
    .setSimplifyNestedObjects(true)

  private[hocon_fmt] val renderOptions = ConfigRenderOptions.defaults
    .setJson(false)
    .setOriginComments(false)
    .setComments(true)
    .setFormatted(true)
    .setConfigFormatOptions(formattingOptions)

  /** Formatted text, or a failure describing why the input was left alone. */
  def format(source: String): Try[String] =
    Try {
      val formatted = formatOnce(source)
      refuseUnlessValidHocon(formatted)
      refuseUnlessFixedPoint(formatted)
      formatted
    }

  /** Kept separate from [[format]] so the checks there can run another pass without recursing
    * back through the checks.
    */
  private def formatOnce(source: String): String = {
    val masked = IncludeMasking.mask(source)

    // Throws for input that is not HOCON at all, which is how callers learn to skip the file.
    val parsed = ConfigFactory.parseString(masked.text, parseOptions)

    val rendered =
      if (parsed.isEmpty) "" // rendering an empty root would produce "{}"
      else parsed.root.render(renderOptions)

    IncludeMasking.unmask(rendered, masked.originals)
  }

  /** The CLI writes on success, so emitting malformed text would replace a valid config with a
    * broken one.
    *
    * The masked form is what gets parsed. The include statements in the output are the ones just
    * put back verbatim, and resolving them would reach for the filesystem — which says nothing
    * about whether the text is well formed, and which sconfig cannot do at all on Scala.js.
    */
  private def refuseUnlessValidHocon(formatted: String): Unit =
    try {
      ConfigFactory.parseString(IncludeMasking.mask(formatted).text, parseOptions)
      ()
    } catch {
      case e: ConfigException.Parse =>
        refuse(s"output is not valid HOCON: ${e.getMessage}", e)
      // An include this masker did not recognise can still reach resolution; that is not a
      // statement about our text either.
      case _: ConfigException => ()
    }

  /** A formatter that is not a fixed point keeps producing diffs on unchanged files.
    *
    * sconfig renders an unresolved merge — a repeated key whose later definition substitutes the
    * earlier one — as a comment banner that does parse, so the syntax check above waves it
    * through even though a second pass grows the text again.
    */
  private def refuseUnlessFixedPoint(formatted: String): Unit = {
    val secondPass =
      try formatOnce(formatted)
      catch { case e: Throwable => refuse(s"output cannot be formatted again: ${e.getMessage}", e) }

    if (secondPass != formatted)
      refuse("a second formatting pass would change the output again")
  }

  private def refuse(reason: String, cause: Throwable = null): Nothing =
    throw new IllegalStateException(s"refusing to emit: $reason", cause)
}
