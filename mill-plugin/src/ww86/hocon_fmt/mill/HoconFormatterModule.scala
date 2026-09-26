package ww86.hocon_fmt.mill

import mill.*
import mill.javalib.JavaModule

// Declared for the tests; the tasks are implemented in the next commit.
trait HoconFormatterModule extends JavaModule {
  def hoconFormatSources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }
  def hoconFormat(): Command[Unit]        = Task.Command(())
  def hoconFormatCheck(): Command[Unit]   = Task.Command(())
}
