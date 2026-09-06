package ww86.hocon_fmt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Fixture files for the JVM-only suites.
  *
  * The golden workflow rewrites these files, so they are addressed by source path rather than
  * loaded from the classpath. sbt runs tests from the build root; the fallback covers running
  * from inside the module.
  */
object FileFixtures {
  val directory: File =
    List("core/jvm/src/test/resources", "src/test/resources")
      .map(new File(_))
      .find(_.isDirectory)
      .getOrElse(new File("core/jvm/src/test/resources"))

  val GoldenSuffix = ".expected.conf"

  /** Every `.conf` that is not itself a golden file. */
  def inputs: List[File] =
    Option(directory.listFiles()).toList.flatten
      .filter(f => f.getName.endsWith(".conf") && !f.getName.endsWith(GoldenSuffix))
      .sortBy(_.getName)

  def goldenFor(input: File): File =
    new File(directory, input.getName.stripSuffix(".conf") + GoldenSuffix)

  def read(file: File): String = String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
}
