package ww86.hocon_fmt

import org.ekrich.config.*
import scala.util.Try

/** Formats HOCON text.
  *
  * Everything except `include` handling is sconfig's job: this parses, re-renders, and puts the
  * include statements back. When output comes out wrong for any other reason the bug is upstream,
  * and the response here is to refuse the file rather than work around it — see [[Refusal]] and
  * `SconfigDefectsSpec`.
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

  /** Formatted text, or why the input was left alone. */
  def format(source: String): Either[Refusal, String] =
    for {
      formatted <- attempt(formatOnce(source))(Refusal.NotHocon(_))
      _         <- readableAgain(formatted)
      _         <- fixedPoint(formatted)
    } yield formatted

  /** Kept separate from [[format]] so the checks there can run another pass without recursing
    * back through the checks. Throws for input that is not HOCON at all.
    */
  private def formatOnce(source: String): String = {
    val masked = IncludeMasking.mask(source)
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
  private def readableAgain(formatted: String): Either[Refusal, Unit] =
    try {
      ConfigFactory.parseString(IncludeMasking.mask(formatted).text, parseOptions)
      Right(())
    } catch {
      case e: ConfigException.Parse => Left(Refusal.BrokenOutput(e.getMessage))
      // An include this masker did not recognise can still reach resolution; that is not a
      // statement about our text either.
      case _: ConfigException => Right(())
    }

  /** A formatter that is not a fixed point keeps producing diffs on unchanged files. */
  private def fixedPoint(formatted: String): Either[Refusal, Unit] =
    attempt(formatOnce(formatted))(Refusal.BrokenOutput(_))
      .filterOrElse(_ == formatted, Refusal.UnstableOutput)
      .map(_ => ())

  private def attempt[A](run: => A)(refusal: String => Refusal): Either[Refusal, A] =
    Try(run).toEither.left.map(e => refusal(Option(e.getMessage).getOrElse(e.toString)))
}
