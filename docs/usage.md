# Usage

Every channel runs the same formatter and follows the same rules:

- **format** rewrites only files whose formatted text differs, as UTF-8.
- **check** writes nothing, names every unformatted file, and then fails.
- A file the formatter **refuses** (not HOCON, not UTF-8, or hit by a
  [known sconfig defect](limitations.md)) is reported with the reason and left byte-for-byte
  untouched. A refusal never fails the run: a `.conf` file that is not HOCON at all, such as an
  nginx config, is common enough that failing on it would make the tool unusable.

All JVM channels need Java 17 or newer, as Scala 3.8 does.

## Command line

```
hocon-formatter [--check] <file>...
```

| exit code | meaning |
|---|---|
| 0 | done; with `--check`, every file is formatted or refused |
| 1 | `--check` found an unformatted file |
| 2 | the arguments could not be parsed |

Three builds of the same program:

| build | get it | start-up per run* |
|---|---|---|
| native binary | `pipx install hocon-formatter`, or `hocon-formatter-<os>-<arch>` from a GitHub release | 31 ms |
| Node | `npx hocon-formatter` | 140 ms |
| JVM | `sbt "cliJVM/run <args>"` from a checkout | 720 ms |

\* `--check` on one small file, averaged over 10 runs on one Linux machine.

## pre-commit

```yaml
repos:
  - repo: https://github.com/kastoestoramadus/hocon-formatter
    rev: v0.1.0
    hooks:
      - id: hocon-formatter        # rewrites files; the commit stops so you can stage them
      # - id: hocon-formatter-check  # or only report
```

The hooks run the native binary, installed from the `hocon-formatter` wheel as ruff's hooks
install ruff, so they need nothing but the Python pre-commit already runs on. Where there is no
native build, such as Windows, use `hocon-formatter-node` and `hocon-formatter-check-node`, which
run the Node build. On 20 files a hook run takes about 155 ms native against 290 ms on Node, and
its environment is 29 MB against 233 MB, mostly the Node that pre-commit downloads.

## sbt

```scala
// project/plugins.sbt
addSbtPlugin("io.github.kastoestoramadus" % "sbt-hocon-formatter" % "0.1.0")
```

| key | |
|---|---|
| `hoconFormat` | rewrite the files that are not formatted |
| `hoconFormatCheck` | fail if any file is not formatted |
| `hoconFormatSources` | the files; default `*.conf` and `*.hocon` in the Compile and Test resource directories |

Run in a project, a task covers that project and the projects it aggregates, so running it at the
root covers the build, and a resource directory two projects share is examined once. Neither task
is wired into `test` or `compile`, as with sbt-scalafmt; add `hoconFormatCheck` to CI explicitly.

```scala
hoconFormatSources := (baseDirectory.value / "conf" ** "*.conf").get
```

## Gradle

```kotlin
plugins {
    id("io.github.kastoestoramadus.hocon-formatter") version "0.1.0"
}
repositories { mavenCentral() } // the plugin resolves the formatter through the project

hoconFormatter {
    source.setFrom(fileTree("config") { include("**/*.conf") }) // default: *.conf and *.hocon under src
}
```

`hoconFormat` rewrites; `hoconFormatCheck` fails on an unformatted file and runs as part of
`check`. Both are configuration-cache compatible, and the check is up to date while neither the
files nor the formatter change. To pin another formatter version:
`dependencies { hoconFormatter("io.github.kastoestoramadus:hocon-formatter-core_3:<version>") }`.

## Mill

```scala
//| mvnDeps:
//| - io.github.kastoestoramadus::mill-hocon-formatter::0.1.0
package build

import mill.*, javalib.*
import ww86.hocon_fmt.mill.HoconFormatterModule

object app extends JavaModule, HoconFormatterModule {
  object test extends JavaTests, TestModule.Junit5, HoconFormatterModule
}
```

`./mill __.hoconFormat` rewrites; `./mill __.hoconFormatCheck` fails on an unformatted file. Mill
1.1.4 or newer. The trait covers the module it is mixed into, so a test module needs it too.
`hoconFormatSources`, the module's `resources` by default, lists directories to search for
`*.conf` and `*.hocon`, or single files:

```scala
override def hoconFormatSources = Task.Sources("conf")
```

## Maven

```xml
<plugin>
  <groupId>io.github.kastoestoramadus</groupId>
  <artifactId>hocon-formatter-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>check</goal></goals> <!-- binds to verify -->
    </execution>
  </executions>
</plugin>
```

`mvn hocon-formatter:format` rewrites. Parameters, relative to the project directory:

| parameter | default |
|---|---|
| `includes` | `src/**/*.conf`, `src/**/*.hocon` |
| `excludes` | none |
| `skip` (`-Dhocon-formatter.skip`) | `false` |
