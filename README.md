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

Configuration for each: [usage](docs/usage.md). A browser build for web pages is on its way:
see [playground](docs/playground.md).

**Status:** nothing is published yet; [releasing](docs/releasing.md) lists what publishing needs.
Until then, every channel can be tried from a checkout.

## Try it from a checkout

You need Java 17+, sbt, Node and clang; Python and pre-commit for the hooks. One command builds
the command line tools and puts the core and the sbt plugin where local builds find them:

```bash
sbt cliNative/nativeLink cliJS/npmPackage web/bundle coreJVM/publishLocal coreJVM/publishM2 sbtPlugin/publishLocal
```

Scala Native's linker prints warnings about `.note.GNU-stack` marked `[error]`; the build still
succeeds.

Each channel below does the same three things to a file, such as the `application.conf` above:
**check** names it and fails, **format** rewrites it, and a `.conf` that is not HOCON (try an
nginx config) is reported and left byte for byte as it was. Worth trying on top: a file with
includes and comments, a file that is not UTF-8, and formatting twice.

**Command line**, in three builds of the same program:

```bash
cli/.native/target/scala-3.8.2/hocon-formatter --check application.conf   # exit 1: not formatted
node cli/.js/target/npm-package/hocon-formatter.js application.conf        # rewrites it
sbt "cliJVM/run --check application.conf"                                  # paths from where sbt runs
```

**pre-commit**, in a git repository of your own, with a wheel built from the native binary:

```bash
python3 python/build_wheel.py --binary cli/.native/target/scala-3.8.2/hocon-formatter --out dist
```

```yaml
# .pre-commit-config.yaml
repos:
  - repo: /path/to/hocon-formatter      # this checkout
    rev: <commit>                       # git rev-parse HEAD in it: pre-commit installs a commit
    hooks:
      - id: hocon-formatter
        additional_dependencies: [/path/to/hocon-formatter/dist/<the wheel build_wheel.py printed>]
```

After `pre-commit install`, committing an unformatted `.conf` stops with the file formatted;
staging it again lets the commit through. `scripts/pre-commit-e2e.sh` runs all four hooks, native
and Node, the same way in a throwaway repository.

**sbt**: in a project with `src/main/resources/application.conf`, then `sbt hoconFormatCheck` and
`sbt hoconFormat`.

```scala
// project/plugins.sbt
addSbtPlugin("io.github.kastoestoramadus" % "sbt-hocon-formatter" % "0.1.0-SNAPSHOT")
```

**Gradle**: `(cd gradle-plugin && ./gradlew publishToMavenLocal)`, then in a project with
`src/main/resources/application.conf`, `gradle hoconFormatCheck` and `gradle hoconFormat`. Without
a Gradle of your own, this checkout's wrapper works from the project:
`/path/to/hocon-formatter/gradle-plugin/gradlew hoconFormatCheck`.

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }

// build.gradle.kts
plugins { id("io.github.kastoestoramadus.hocon-formatter") version "0.1.0-SNAPSHOT" }
repositories { mavenLocal(); mavenCentral() }
```

**Maven**: `(cd maven-plugin && ./mvnw install -Dinvoker.skip)`, then add the plugin to a
project's `<build><plugins>`, and run `mvn hocon-formatter:check` and `mvn hocon-formatter:format`,
or `/path/to/hocon-formatter/maven-plugin/mvnw` in place of `mvn`.

```xml
<plugin>
  <groupId>io.github.kastoestoramadus</groupId>
  <artifactId>hocon-formatter-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</plugin>
```

**Mill**: `(cd mill-plugin && ./mill publishLocal)`, then copy the launcher `mill-plugin/mill` into
a build with `app/resources/application.conf`, and run `./mill __.hoconFormatCheck` and
`./mill __.hoconFormat`.

```scala
//| mvnDeps:
//| - io.github.kastoestoramadus::mill-hocon-formatter::0.1.0-SNAPSHOT
package build
import mill.*, javalib.*
import ww86.hocon_fmt.mill.HoconFormatterModule

object app extends JavaModule, HoconFormatterModule
```

**Web script**, loaded the way a `<script>` tag loads it:

```bash
node -e "require('vm').runInThisContext(require('fs').readFileSync('web/target/bundle/hocon-formatter.js', 'utf8')); console.log(HoconFormatter.format('a   =   1'))"
```

## Build from source

```bash
sbt test                    # core and CLI on the JVM, Scala.js and Scala Native, and the web script
```

The plugins build on their own; [AGENTS.md](AGENTS.md) lists every command. [Tests](docs/testing.md)
describes the suites. Contributors and coding agents: start with [AGENTS.md](AGENTS.md).

Licensed [GPL-3.0](LICENSE).
