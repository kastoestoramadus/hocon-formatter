package ww86.hocon_fmt.bench

/** Inputs to time the formatter on, generated so every run and every platform measures the same
  * text. Each stresses a different part of the pipeline.
  */
final case class Scenario(name: String, source: String)

object Scenarios {

  val all: List[Scenario] = List(
    Scenario("typical", typical),
    Scenario("large", sections(5000)),
    Scenario("includes", includes(300)),
    Scenario("comments", commented(2000)),
    Scenario("nested", nested(150))
  )

  /** A service's application.conf: what a pre-commit hook meets on most commits. */
  def typical: String =
    """# Service settings
      |include "defaults.conf"
      |service {
      |    name = "billing"
      |  port = 8080
      |  endpoints = [ "/a", "/b" ]
      |  timeouts { connect = 5s, read = 30s }
      |}
      |// Database
      |db {
      |  url = "jdbc:postgresql://localhost/billing"
      |  pool.size = 16
      |  user = ${?DB_USER}
      |}
      |features.flags = [ a, b, c ]
      |""".stripMargin

  def sections(count: Int): String =
    (0 until count).map { i =>
      s"section$i {\n  name = \"n$i\"\n  port = $i\n  tags = [a, b, c]\n}\n"
    }.mkString

  def includes(count: Int): String =
    (0 until count).map(i => s"include \"part$i.conf\"\nkey$i = $i\n").mkString

  def commented(count: Int): String =
    (0 until count).map(i => s"# setting $i explained\nkey$i = $i\n").mkString

  def nested(depth: Int): String =
    (0 until depth).map(i => s"k$i { ").mkString + "v = 1" + " }" * depth + "\n"
}
