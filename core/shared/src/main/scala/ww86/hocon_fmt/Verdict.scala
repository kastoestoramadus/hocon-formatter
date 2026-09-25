package ww86.hocon_fmt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8

/** What formatting makes of one file's content, decided without touching the file.
  *
  * Every integration acts on this, so the CLI and the build-tool plugins cannot disagree about a
  * file: they differ only in how they find files and report.
  */
enum Verdict {
  case AlreadyFormatted
  case NeedsFormatting(formatted: String)
  case Refused(refusal: Refusal)
}

object Verdict {

  def of(source: String): Verdict =
    HoconFormatter.format(source) match {
      case Left(refusal)                           => Refused(refusal)
      case Right(formatted) if formatted == source => AlreadyFormatted
      case Right(formatted)                        => NeedsFormatting(formatted)
    }

  def of(content: Array[Byte]): Verdict =
    decodeUtf8(content).fold(Refused(_), of)

  // The default decoder reports malformed input instead of replacing it.
  private def decodeUtf8(content: Array[Byte]): Either[Refusal, String] =
    try Right(UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString)
    catch { case _: CharacterCodingException => Left(Refusal.NotUtf8) }
}
