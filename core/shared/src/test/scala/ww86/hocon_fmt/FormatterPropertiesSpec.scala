package ww86.hocon_fmt

import org.scalacheck.Prop.forAll

import ww86.hocon_fmt.HoconFormatter.format
import ww86.hocon_fmt.HoconGen.*

/** Properties that must hold for every document, checked on generated ones.
  *
  * The example-based suites pin the cases someone thought of; these look for the ones nobody did.
  * On a failure ScalaCheck prints the shrunk document and the seed that reproduces it. The formatter
  * may always refuse; what it must never do is hand back text that lost something.
  */
class FormatterPropertiesSpec extends munit.ScalaCheckSuite with HoconTestSupport {

  // The rarest defects found so far took thousands of documents to turn up; a deeper search is
  // `sbt -Dhocon.properties=20000 "coreJVM/testOnly ww86.hocon_fmt.FormatterPropertiesSpec"`.
  override def scalaCheckTestParameters = super.scalaCheckTestParameters
    .withMinSuccessfulTests(sys.props.get("hocon.properties").map(_.toInt).getOrElse(1000))

  property("never loses a comment") {
    forAll(documents(includes = true)) { doc =>
      format(doc.text).foreach { out =>
        doc.comments.foreach(c => assert(out.contains(c), s"comment [$c] lost from:\n$out"))
      }
    }
  }

  property("never loses an include") {
    forAll(documents(includes = true)) { doc =>
      format(doc.text).foreach { out =>
        doc.includes.foreach(i => assert(out.contains(i), s"[$i] lost from:\n$out"))
      }
    }
  }

  property("never leaks a placeholder") {
    forAll(documents(includes = true)) { doc =>
      format(doc.text).foreach(out => assert(!out.contains("__INCLUDE"), out))
    }
  }

  property("keeps the meaning of every document it formats") {
    forAll(documents(includes = false)) { doc =>
      format(doc.text).foreach(out => assertSameMeaning(out, doc.text, s"meaning changed, output:\n$out"))
    }
  }

  // Without this the properties above would pass vacuously on a formatter that refused everything.
  property("formats every document it has no reason to refuse") {
    val documentsWithoutReason = documents(includes = true, distinctKeys = true)
      .suchThat(doc => doc.everyCommentPrecedesAField && !doc.hitsKnownSconfigDefect)
    forAll(documentsWithoutReason) { doc =>
      format(doc.text) match {
        case Right(out)    => assertEquals(format(out), Right(out), "output is not a fixed point")
        case Left(refusal) => fail(s"refused: ${refusal.reason}")
      }
    }
  }
}
