# hocon-formatter

A formatter for [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) configuration
files: a command line tool (native binary, Node, JVM), pre-commit hooks, and plugins for sbt,
Gradle, Maven and Mill, all running one Scala 3 core.

```
$ cat application.conf
app {
    name  =  "svc"
   port =8080
  include "local.conf"
  db { url = "jdbc:postgresql://localhost/app" }
}

$ hocon-formatter application.conf && cat application.conf
Running HOCON formatter for 1 files.
app {
  name: svc
  port: 8080
  include "local.conf"
  db.url: "jdbc:postgresql://localhost/app"
}
```

## It refuses rather than corrupts

The tool writes files, so "produced something" is not good enough. Before handing text back it
checks that the output parses again, that a second pass would not change it, and that no comment
or include went missing; it decodes files strictly as UTF-8. A file that fails any of this is reported and
left byte-for-byte untouched, without failing the run. [Known limitations](docs/limitations.md)
lists the inputs this applies to.

Parsing HOCON resolves `include` directives and keeps nothing to render, so a plain parse-render
round trip deletes them. The formatter carries each whole statement across the round trip; see
[architecture](docs/architecture.md#include-masking).

## Use it

| channel | |
|---|---|
| command line | `hocon-formatter [--check] <file>...`: `pipx install hocon-formatter` (native), `npx hocon-formatter` (Node), binaries on GitHub releases |
| pre-commit | hooks `hocon-formatter` and `hocon-formatter-check` from this repository, running the native binary |
| sbt | `addSbtPlugin("io.github.kastoestoramadus" % "sbt-hocon-formatter" % "0.1.0")`, then `hoconFormat` / `hoconFormatCheck` |
| Gradle | `id("io.github.kastoestoramadus.hocon-formatter")`, then `hoconFormat` / `hoconFormatCheck` (part of `check`) |
| Maven | `hocon-formatter-maven-plugin`, goals `format` / `check` (bound to `verify`) |
| Mill | `io.github.kastoestoramadus::mill-hocon-formatter`, trait `HoconFormatterModule`, then `__.hoconFormat` / `__.hoconFormatCheck` |

Configuration for each: [usage](docs/usage.md).

**Status:** nothing is published yet; every channel is built and tested from this repository, and
[releasing](docs/releasing.md) lists what publishing still needs.

## Build from source

Java 17+, sbt, Node (for the Scala.js tests) and clang (for Scala Native).

```bash
sbt test                    # core and CLI on the JVM, Scala.js and Scala Native
sbt cliNative/nativeLink    # cli/.native/target/scala-3.8.2/hocon-formatter
```

[Tests](docs/testing.md) describes the suites. Contributors and coding agents: start with
[AGENTS.md](AGENTS.md).

Licensed [GPL-3.0](LICENSE).
