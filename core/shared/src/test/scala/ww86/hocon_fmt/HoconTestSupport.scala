package ww86.hocon_fmt

import org.ekrich.config.{Config, ConfigFactory}
import scala.util.Try

/** Helpers shared by every suite.
  *
  * Parse and render options come from [[HoconFormatter]] itself rather than being restated here.
  * A private copy would drift, and a library test rendering with different options than the
  * formatter actually uses would prove nothing about the formatter.
  */
trait HoconTestSupport { self: munit.FunSuite =>

  extension (hocon: String) {

    /** Success when the text is valid HOCON; the failure says why it is not, which beats a bare
      * `false` when a test has to explain itself.
      */
    def parses: Try[Unit] = Try(hocon.parsedConfig).map(_ => ())

    /** Parsed but not resolved. Two configs are equal when they mean the same thing, so this is
      * the unit of comparison for "formatting did not change the meaning".
      */
    def parsedConfig: Config = ConfigFactory.parseString(hocon, HoconFormatter.parseOptions)

    /** A bare sconfig round trip: parse, then render, with none of our include masking. */
    def renderedByLibrary: String = hocon.parsedConfig.root.render(HoconFormatter.renderOptions)
  }

  /** Formatted, throwing if the formatter refuses. For the tests that expect success. */
  def formatted(hocon: String): String = HoconFormatter.format(hocon).get

  /** Asserts two snippets mean the same thing, whatever their spelling. */
  def assertSameMeaning(actual: String, expected: String, clue: => String)(using
      munit.Location
  ): Unit =
    assertEquals(actual.parsedConfig, expected.parsedConfig, clue)
}
