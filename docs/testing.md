# Tests

Suites are split by concern, so a change answers to one place. A suite's directory follows from
what it touches: `shared` runs on every platform, `jvm-native` reads files, `jvm` is JVM-only.

| suite | where | answers for |
|---|---|---|
| `GoldenFileSpec` | `core/jvm-native` | rendering: each `<name>.conf` in `core/jvm-native/src/test/resources` against `<name>.expected.conf` |
| `HoconFormatterInvariantsSpec` | `core/jvm-native` | idempotence, output re-parses, meaning preserved, inputs shaped like the internal placeholders |
| `FormatterPropertiesSpec` | `core/shared` | the same guarantees on generated documents: no comment, include or meaning lost, no placeholder leaked, nothing refused without reason |
| `IncludeDetectionSpec` | `core/shared` | which occurrences of `include` are a directive; the contract of the detection regex |
| `HoconSpecCoverageSpec` | `core/shared` | the HOCON specification: what is refused (and why), normalised, supported |
| `VerdictSpec` | `core/shared` | the per-file decision every integration acts on, including strict UTF-8 |
| `JvmFacadeSpec` | `core/jvm` | the JDK-typed boundary, called from Java (`JavaCaller.java`) and reflectively |
| `SconfigDefectsSpec` | `core/shared` | sconfig's own bugs, with none of our code involved; red by design |
| `CmdApiSpec` | `cli` | the CLI on real temp files, on JVM, Node and Native: exit codes, every file examined once, unformattable and non-UTF-8 files never written, arguments |
| scripted | `sbt-plugin/src/sbt-test` | the sbt plugin in a real sbt build |
| functional | `gradle-plugin/src/functionalTest` | the Gradle plugin through TestKit, including configuration cache and up-to-date checks |
| invoker | `maven-plugin/src/it` | the Maven plugin in real Maven builds |
| unit, integration | `mill-plugin/test`, `mill-plugin/integration` | the Mill plugin in process through `UnitTester`, and in a real Mill: 1.1.4, the oldest supported, and 1.1.10 |
| e2e | `scripts/pre-commit-e2e.sh` | both families of pre-commit hooks, native and Node, installed from this repository as a user would |

## Property tests

`HoconGen` generates documents as a tree that knows its own comments and includes, then writes
them the ways people write HOCON: `:`, `=` or nothing before `{`, `#` and `//` comments holding
quotes and the word include, quoted and dotted keys. Knowing what went in lets a property check
what came out without parsing it with the code under test. Each property runs 1000 documents on
every platform; `sbt -Dhocon.properties=20000 "coreJVM/testOnly ww86.hocon_fmt.FormatterPropertiesSpec"`
searches deeper. A failure prints the document and the seed that reproduces it.

They found what no example covered: a quote inside a comment hid the next include, which on the
JVM then vanished; a comment with no field after it was dropped; an include in an object that a
later definition replaces vanished with it, after 11356 documents; and, in sconfig, a one-field
object inside an array loses its braces when it does not fit on one line, which one CI run turned
up after 859 documents.

## Benchmarks

`bench` times each phase (mask, parse, render, unmask, the whole format) on five generated
scenarios on the JVM, Scala.js and Scala Native. `scripts/bench.py run` adds the CLI's start-up per
build and attaches the results to HEAD in `refs/notes/benchmarks`, keeping three runs per commit
and thirty commits; `scripts/bench.py report` prints the medians per commit and flags a slowdown
over 15% and 0.05 ms between runs on the same machine. CI records main's history and reports a pull
request against it, without failing: shared runners are too noisy to gate on.

Notes are shared with `git push origin refs/notes/benchmarks`, and survive rebase and amend with
`git config notes.rewriteRef refs/notes/benchmarks`.

## Conventions

- **Golden files.** Adding a fixture is a two-file drop. Regenerate with
  `UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec"` and read the diff before
  committing: a golden file is worth what the human who approved it looked at.
- **Meaning preservation** is asserted on include-free inputs only: an include of a missing file
  cannot be parsed on its own. Files with includes are covered by idempotence.
- **`library:` and `formatter:`** prefixes in `SconfigDefectsSpec` and `HoconSpecCoverageSpec`
  record who is responsible for a failure, so nobody fixes an upstream bug here.
- **`SconfigDefectsSpec` is excluded from `sbt test`**, because a permanently red CI teaches people
  to ignore it. `sbt libraryDefects` runs it on all three platforms, since sconfig's Scala.js and
  Native builds have defects of their own.
- **Order.** `sbt test` runs core and cli on the JVM, Scala.js and Scala Native, one project at a
  time under a `==========` banner. Aggregated projects would run concurrently and print unlabelled,
  interleaved summaries. The cost: the run stops at the first failing project.
