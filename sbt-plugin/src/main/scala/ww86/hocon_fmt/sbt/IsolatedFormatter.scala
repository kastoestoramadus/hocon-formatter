package ww86.hocon_fmt.sbt

import java.io.File
import java.lang.reflect.{InvocationTargetException, Method}
import java.net.URLClassLoader
import java.util.Optional

/** What the formatter makes of one file, as the plugin sees it from across the class loaders. */
sealed trait Verdict

object Verdict {
  case object AlreadyFormatted                        extends Verdict
  final case class NeedsFormatting(formatted: String) extends Verdict
  final case class Refused(reason: String)            extends Verdict
}

/** The Scala 3 formatter, loaded apart from sbt's own Scala 2.12 library.
  *
  * Only JDK types cross over: `JvmFacade.reformat` takes a file's bytes and returns the text it
  * should have, empty when it already has it, or throws with the reason it refused.
  */
final class IsolatedFormatter private (reformat: Method) {

  def verdictFor(content: Array[Byte]): Verdict =
    try {
      val formatted = reformat.invoke(null, content).asInstanceOf[Optional[String]]
      if (formatted.isPresent) Verdict.NeedsFormatting(formatted.get) else Verdict.AlreadyFormatted
    } catch {
      // Matched by name: the exception's class belongs to the other class loader.
      case e: InvocationTargetException if e.getCause.getClass.getName == "ww86.hocon_fmt.FormatRefusedException" =>
        Verdict.Refused(e.getCause.getMessage)
      // Anything else is a broken formatter, not a verdict on the file, and must fail the task.
      case e: InvocationTargetException => throw e.getCause
    }
}

object IsolatedFormatter {

  def using[A](classpath: Seq[File])(use: IsolatedFormatter => A): A = {
    // With the platform loader as parent, the two sides share the JDK and nothing else, so sbt's
    // Scala 2.12 library cannot shadow the formatter's Scala 3 one.
    val loader = new URLClassLoader(classpath.map(_.toURI.toURL).toArray, ClassLoader.getPlatformClassLoader)
    try {
      val reformat = loader.loadClass("ww86.hocon_fmt.JvmFacade").getMethod("reformat", classOf[Array[Byte]])
      use(new IsolatedFormatter(reformat))
    } finally loader.close()
  }
}
