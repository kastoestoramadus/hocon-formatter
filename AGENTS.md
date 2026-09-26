# AGENTS.md

A formatter for HOCON files, meant to run as a pre-commit hook, a CLI and a build-tool plugin, so
`--check`, exit codes and "never write a broken file" are part of the product. Scala 3 core on
sconfig, cross-built for the JVM, Scala.js and Scala Native. GPL-3.0.

## Commands

```bash
sbt test                  # core + cli on JVM, Scala.js, Scala Native (needs Node and clang)
sbt crossCompile          # compile every platform's tests; needs neither Node nor clang
sbt scalafmtAll           # format; CI runs scalafmtCheckAll
sbt libraryDefects        # SconfigDefectsSpec on every platform: red by design, 12 (JVM, Native), 13 (JS)
sbt sbtPluginTest         # sbt plugin, scripted (slow: a fresh sbt per test)
sbt coreJVM/publishM2     # needed before the Gradle and Maven builds
(cd gradle-plugin && ./gradlew check)
(cd maven-plugin && ./mvnw verify)
scripts/pre-commit-e2e.sh # pre-commit hooks as installed from HEAD; needs pre-commit and python3
scripts/bench.py run      # time every phase on every platform, attach to HEAD in git notes
scripts/bench.py report   # medians per commit, slowdowns flagged

sbt cliNative/nativeLink  # cli/.native/target/scala-3.8.2/hocon-formatter
sbt cliJS/npmPackage      # cli/.js/target/npm-package
sbt "cliJVM/run --check path/to/file.conf"
sbt "coreJVM/testOnly ww86.hocon_fmt.HoconFormatterInvariantsSpec -- *idempotent*"
UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec"
```

`sbt test` fails rather than skips a platform whose runtime is missing: a silently skipped
platform is how a port rots.

## Layout

`core` (pure formatting, sconfig only) · `cli` (cats-effect `IOApp`) · `sbt-plugin` (Scala 2.12)
· `gradle-plugin`, `maven-plugin` (standalone Java builds) · `npm/` (package template) · `python/`
(wheel carrying the native binary) · `bench` · `.pre-commit-hooks.yaml`. Details and the reasons
behind them: [docs/architecture.md](docs/architecture.md).

## Rules

- **`include` handling is ours; everything else is sconfig's.** Wrong output for any other reason
  is an upstream bug: reproduce it against bare sconfig in `SconfigDefectsSpec` (named `library:`),
  make `format` refuse the input, record it in [docs/limitations.md](docs/limitations.md). Never
  work around it in `HoconFormatter`.
- **`IncludeMasking` is the core algorithm.** Read
  [the masking section](docs/architecture.md#include-masking) before changing it. Its regexes must
  run on RE2 (Scala Native) and ES2015 JavaScript (Scala.js): no lookaround, no backreferences, no
  multiline `^`. The suites on every platform enforce it.
- **Refuse rather than corrupt.** Losing a comment or an include counts, not only meaning. Every
  integration acts on `Verdict`; a refused file is never written and never fails a run. The
  output check parses the *masked* text: sconfig cannot parse an `include` on Scala.js, so do not
  simplify it to parse the finished text.
- **`core` depends on sconfig only.** The plugins load it into sbt, Gradle and Maven; effects and
  libraries belong in `cli`. `JvmFacade` is the JDK-typed boundary the plugins share.
- **Do not "fix" the intentional normalisations** (`//` to `#`, `=` to `:`, flattened paths, …)
  listed in [docs/limitations.md](docs/limitations.md).
- **One version everywhere**: see [docs/releasing.md](docs/releasing.md) for the four places.

## Conventions

- Settle a claim by running something and quote the output; reading alone has been wrong.
- Tests before implementation, in their own commits; show them failing first. A guarantee that
  must hold for every input also gets a property in `FormatterPropertiesSpec`.
- A change to a hot path gets a `scripts/bench.py run` before and after.
- New Scala code is functional Scala 3: ADTs and `Either` rather than exceptions, no `var`, effects
  at the edges. Braces, not significant indentation (`.scalafmt.conf`). The sbt plugin is Scala 2.12.
- Comments explain why, never restate the code. No `private` in test code.
- Commits, PRs and review replies in English; a PR carries only what it delivers.

Where to add a test: [docs/testing.md](docs/testing.md).
