package ww86.hocon_fmt.bench

/** Summary of repeated timings. Medians rather than means: one GC pause or scheduler hiccup should
  * not move the number a regression check compares.
  */
final case class Stats(samples: Int, minMicros: Double, medianMicros: Double, p90Micros: Double)

object Stats {

  def of(nanos: Seq[Long]): Stats = {
    val sorted        = nanos.sorted.toVector
    def at(q: Double) = sorted(math.min(sorted.size - 1, (q * sorted.size).toInt)) / 1000.0
    Stats(sorted.size, sorted.head / 1000.0, at(0.5), at(0.9))
  }
}
