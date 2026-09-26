package ww86.hocon_fmt.bench

import org.ekrich.config.ConfigFactory

import ww86.hocon_fmt.{HoconFormatter, IncludeMasking}

/** Times each phase of the formatter on every scenario and prints one JSON object per line.
  *
  * The same harness runs on the JVM, Scala.js and Scala Native, so a slowdown shows up per platform
  * and per phase: a construct that is free on the JVM can be costly on Native or under Node. Plain
  * wall-clock loops rather than JMH, which exists only for the JVM; medians keep the noise down.
  */
object Bench {

  final case class Phase(name: String, run: () => Any)

  final case class Result(platform: String, scenario: String, phase: String, stats: Stats) {
    def json: String =
      f"""{"platform":"$platform","scenario":"$scenario","phase":"$phase","samples":${stats.samples},""" +
        f""""min_us":${stats.minMicros}%.1f,"median_us":${stats.medianMicros}%.1f,"p90_us":${stats.p90Micros}%.1f}"""
  }

  def phases(source: String): List[Phase] = {
    val masked   = IncludeMasking.mask(source)
    val parsed   = ConfigFactory.parseString(masked.text, HoconFormatter.parseOptions)
    val rendered = parsed.root.render(HoconFormatter.renderOptions)
    List(
      Phase("mask", () => IncludeMasking.mask(source)),
      Phase("parse", () => ConfigFactory.parseString(masked.text, HoconFormatter.parseOptions)),
      Phase("render", () => parsed.root.render(HoconFormatter.renderOptions)),
      Phase("unmask", () => IncludeMasking.unmask(rendered, masked.originals)),
      Phase("format", () => HoconFormatter.format(source))
    )
  }

  /** Warms up, then samples until the time budget is spent, keeping every result reachable so no
    * optimiser can drop the work.
    */
  def measure(phase: Phase, budgetNanos: Long): Stats = {
    var sink: Any = null
    val warmupEnd = System.nanoTime + budgetNanos / 3
    while (System.nanoTime < warmupEnd) sink = phase.run()

    val samples = Vector.newBuilder[Long]
    val end     = System.nanoTime + budgetNanos
    var count   = 0
    while (count < 5 || (System.nanoTime < end && count < 500)) {
      val start = System.nanoTime
      sink = phase.run()
      samples += System.nanoTime - start
      count += 1
    }
    if (sink == null) sys.error("unreachable: keeps the result alive")
    Stats.of(samples.result())
  }

  /** "Scala.js" and "Scala Native" report themselves here; anything else is a JVM. Scala.js hands
    * `main` no arguments, so the platform cannot be passed in.
    */
  val platform: String = System.getProperty("java.vm.name", "") match {
    case "Scala.js"     => "js"
    case "Scala Native" => "native"
    case _              => "jvm"
  }

  def main(args: Array[String]): Unit = {
    val budget = args.headOption.map(_.toLong * 1000000L).getOrElse(1000000000L)
    for {
      scenario <- Scenarios.all
      phase    <- phases(scenario.source)
    } println(Result(platform, scenario.name, phase.name, measure(phase, budget)).json)
  }
}
