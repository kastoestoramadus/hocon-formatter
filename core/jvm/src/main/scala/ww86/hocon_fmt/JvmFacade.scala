package ww86.hocon_fmt

import java.util.Optional

/** [[Verdict]] for JVM hosts that cannot use Scala types: the Gradle and Maven plugins, written in
  * Java, and the sbt 1.x plugin, which runs on Scala 2.12 and loads this Scala 3 build in an
  * isolated class loader. Only JDK types cross that boundary, so a refusal travels as a checked
  * exception carrying its reason — the idiom a Java caller expects.
  */
object JvmFacade {

  /** The text the file should contain, or empty when it already does. */
  @throws[FormatRefusedException]("when the file must be left alone; the message says why")
  def reformat(content: Array[Byte]): Optional[String] =
    Verdict.of(content) match {
      case Verdict.AlreadyFormatted           => Optional.empty
      case Verdict.NeedsFormatting(formatted) => Optional.of(formatted)
      case Verdict.Refused(refusal)           => throw FormatRefusedException(refusal.reason)
    }
}

final class FormatRefusedException(reason: String) extends Exception(reason)
