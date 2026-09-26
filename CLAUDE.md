# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A formatter for HOCON configuration files, meant to be usable in several ways — including as a
pre-commit hook. That goal makes the CLI's `--check` mode and its exit code part of the product,
not an afterthought.

Licensed GPL-3.0. `README.md` is still the untouched sbt template and says nothing about the tool;
do not treat it as a source of truth.

## Commands

```bash
sbt test                                        # core and cli on the JVM, Scala.js, Scala Native
sbt crossCompile                                # compile only, no Node or clang needed
sbt coreJS/test                                 # the Scala.js half on its own
sbt libraryDefects                              # the red sconfig-bug tests

sbt "coreJVM/testOnly ww86.hocon_fmt.IncludeDetectionSpec"
sbt "coreJVM/testOnly ww86.hocon_fmt.HoconFormatterInvariantsSpec -- *idempotent*"
sbt scalafmtAll                                 # apply formatting
sbt scalafmtCheckAll                            # verify formatting (CI runs this)

sbt "cliJVM/run --check path/to/file.conf"      # report without writing
sbt "cliJVM/run path/to/file.conf"              # rewrite in place
sbt cliNative/nativeLink                        # cli/.native/target/scala-3.8.2/hocon-formatter
sbt cliJS/fullLinkJS                            # a CommonJS bundle for Node

UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec"   # rewrite expected files
```

`sbt test` runs every runtime, so it needs **Node and clang on PATH** (`sudo apt install nodejs
clang`). Without them the Scala.js or Scala Native half fails to start rather than being skipped,
which is deliberate: a silently skipped platform is how a port rots. `sbt crossCompile` checks that
everything still compiles without needing either.

It runs the six projects in a fixed order — core and cli on the JVM, then on Scala.js, then on
Scala Native — each under its own `==========` banner. sbt otherwise runs aggregated projects concurrently and prints an
unlabelled `Passed: Total N` for each, with the Scala.js block arriving without the `[info]`
prefix, so there is no way to tell which runtime produced which result. The cost of ordering them
is that the run stops at the first project that fails, so a JVM failure hides any Scala.js one
until it is fixed. Dropping `Test / test / aggregate := false` in `build.sbt` trades the labels
back for seeing every failure at once.

`sbt test` deliberately skips `SconfigDefectsSpec`. Those tests assert what sconfig *should* do,
so they are red while the upstream bugs are open, and a permanently red CI teaches people to
ignore it. `sbt libraryDefects` runs them on demand — expect 9 failures today, each naming an open
bug. The exclusion is scoped to the `test` task in `build.sbt`, so `testOnly` still reaches them.

The entry point is `CmdApi`, an `IOApp`, declared as `cliJVM`'s `mainClass`.

## Architecture

**Division of responsibility: `include` handling is ours, everything else is the library's.**
sconfig parses and renders; this project exists to carry `include` directives across a round trip
sconfig cannot. When output is wrong for any other reason, the bug is upstream — do not work around
it in `HoconFormatter`. Reproduce it against bare sconfig, add it to `SconfigDefectsSpec`, report
it, and have `format` refuse the file so nothing corrupted is written.

Two modules, split along what Scala.js can run:

- **`core`** (`crossProject(JVMPlatform, JSPlatform, NativePlatform)`) — formatting proper, no file access and no
  threads. `HoconFormatter` is the pipeline: parse with sconfig, re-render, restore includes,
  refuse anything it cannot read back, as `Either[Refusal, String]`. `Verdict` decides what
  happens to one file's bytes, decoding them strictly as UTF-8; every caller acts on it, and
  `JvmFacade` offers it in JDK types. `IncludeMasking` is the include round-trip machinery.
- **`cli`** (`crossProject(JVMPlatform, JSPlatform, NativePlatform)`) — `CmdApi`, on cats-effect:
  decline argument parsing, fs2-io file reading and writing on the JVM, Node and Native, parallel
  processing. Effects live here, which is what keeps `core` pure and portable. A usage error
  exits 2, so 1 keeps meaning "unformatted".

Scala Native runs `java.util.regex` on RE2, which rejects lookaround, backreferences, possessive
quantifiers and `\G` `\R` `\Z`, so every pattern in `IncludeMasking` must stay within it; the
suites running on Native enforce that. The include detector uses `\binclude` rather than
`(?<!\w)include`, and a placeholder's key and value indices are compared in code rather than
with a `\1` backreference.

### The include masking — read this before touching `IncludeMasking`

Parsing a HOCON file resolves and discards its `include` directives, so they cannot survive a plain
parse-then-render round trip. Each **whole include statement** is swapped for a placeholder field
before parsing and swapped back afterwards:

1. `IncludeMasking.mask` scans for `include` at a word boundary, outside a string, and works out the
   extent of the statement — a quoted target, or `required(...)` / `file(...)` / `url(...)` /
   `classpath(...)` with balanced parentheses. Anything else (`include_path`, the word `include` in
   prose) is left alone. The statement is replaced by `__INCLUDE_<n>` plus a guard field, and the
   original text is kept in a side table keyed by `<n>`.
2. sconfig parses and renders the masked text.
3. `IncludeMasking.unmask` puts the original statements back and drops the guard fields.
   Own-line guards are removed before inline ones: the inline pattern does not consume the
   preceding newline, so the other order leaves a blank line behind.

**Why a guard field:** `setSimplifyNestedObjects` collapses a single-field object into a dotted
path, so `o { __INCLUDE_0: v }` would become `o.__INCLUDE_0: v` — moving the placeholder key out of
its object and making it unrestorable. A second field keeps the object from collapsing.

**Why the whole statement and not just the keyword:** the previous scheme replaced `include ` with
a marker followed by a `#` line comment, which swallowed everything after it on that line — a
closing brace, or entries following the include. Replacing the whole statement comments out
nothing, so an include may share its line with other content.

**Why any of this exists:** the author contributed formatting support to sconfig upstream, but the
deeper fixes needed here were too invasive to land in that library. The remaining gaps are worked
around in this repo instead. The masking is load-bearing, not accidental cruft — treat a change to
it as a change to the core algorithm.

## Known limitations

### sconfig defects the formatter refuses

Each of these is reproduced with a bare sconfig parse-render round trip, with no code of ours
involved — they are library bugs, not ours. `format` verifies its own output and returns a
`Refusal` saying why, so no caller writes the file.

Each one has a **failing** test in `SconfigDefectsSpec` asserting the output sconfig ought to
produce. The expected text is not guessed: each case is paired with a plainly written config that
means the same thing and that sconfig renders correctly, and every test first asserts that both
sides `resolve()` to the same value before comparing the rendered text. A failure therefore prints
a diff of target against actual, ready to paste into an upstream issue. Run them with
`sbt libraryDefects`. When a future sconfig release fixes one, its test turns
green — that is the signal to drop the corresponding refusal here and the entry below.

Accepted on input, then rendered as text that will not parse again:

- **`+=` field separator** — `a : [1]` then `a += 2`
- **Self-referential substitution** — `a : 1` then `a : ${a}`
- **Array concatenation with a substitution** — `path = ${path} [ /usr/bin ]`
- **String concatenation with a substitution** — `path : ${path}":d"`
- **Nested self-reference** — `foo : ${foo.a}`

Accepted and re-parseable, but not a fixed point:

- **Substitution cycle** — `a : ${b}` with `b : ${a}` renders as an unresolved-merge banner that
  parses but changes again on the next pass. A syntax check alone misses this, which is why
  `format` also requires its output to be stable.

Mis-rendered rather than banner-wrapped:

- **Object concatenation with a substitution** — `e = ${g} { name = "east" }`, the ordinary
  config-inheritance idiom, comes back as `e: ${g}name: east`. This is the one worth reporting
  upstream first: it is short, obviously wrong, and hits a very common pattern.

Unimplemented on Scala.js:

- **Parsing any text containing an `include`** throws `NotImplementedError` — sconfig's JS build
  has no include resolution. This is why `refuseUnlessValidHocon` parses the *masked* form: the
  include statements it would otherwise resolve are the ones we just put back verbatim, so
  resolving them proves nothing and costs portability. Do not "simplify" that back to parsing the
  finished text; `IncludeDetectionSpec` on `coreJS` is what catches it.

Rejected at parse time although the specification allows them:

- **An array at the file root** — `[ "a", "b" ]` throws `WrongType`
- **The `[]` env-variable list suffix** — `${MY_LIST[]}` throws `BadPath`

### Intentional normalisations — do not "fix" these

These are sconfig's renderer doing what the `ConfigFormatOptions` in `HoconFormatter` ask of it,
not defects. Meaning is preserved, original spelling is not. Pinned in `HoconSpecCoverageSpec`:
`//` comments become `#`; `=` becomes `:`; nested objects are flattened to path keys
(`setSimplifyNestedObjects`); triple-quoted strings become escaped single-line strings; number
literals are canonicalised (`1.5e3` becomes `1500`); unicode escapes are resolved (a `\u0041`
escape becomes the literal `A`).

## Tests

Six suites, split by concern so a change answers to one place. Which module a suite lives in
follows from what it touches: anything reading files runs on the JVM and Scala Native, the rest
is shared and runs on all three platforms.

- **`GoldenFileSpec`** (`core/jvm-native`) — rendering. Each `<name>.conf` in
  `core/jvm-native/src/test/resources` is compared against
  `<name>.expected.conf`. Adding a fixture is a two-file drop, no Scala changes. Regenerate with
  `UPDATE_GOLDEN=1` and read the diff before committing — a golden file is only worth what the
  human who approved it looked at.
- **`HoconFormatterInvariantsSpec`** (`core/jvm-native`) — relations holding for every fixture: idempotence
  (`format(format(x)) == format(x)`), output re-parses, meaning preservation
  (`parse(raw) == parse(format(raw))`), and adversarial inputs shaped like the internal
  `__INCLUDE_<n>` and `__INCLUDE_GUARD_<n>` fields.
- **`IncludeDetectionSpec`** (`core/shared`) — which occurrences of the word `include` are a directive and which
  are ordinary text. This is the contract of the detection regex; change that regex and answer here.
- **`HoconSpecCoverageSpec`** (`core/shared`) — coverage against the HOCON specification: what is refused, what is
  normalised on purpose, what is supported.
- **`SconfigDefectsSpec`** (`core/shared`) — library bugs, exercised against bare sconfig with none of our code in
  between. Each test asserts the correct target output rather than pinning the broken one. Red by
  design and excluded from `sbt test`; run with `sbt libraryDefects`. Every test is
  named `library:`; tests that go through our pipeline are named `formatter:`. The split records
  who is responsible for a failure, so nobody tries to fix an upstream bug in this repo.
- **`CmdApiSpec`** (`cli`, every platform) — CLI behaviour on temp files: exit codes, that every
  file is examined, and that a file the formatter cannot handle is never overwritten.

Meaning preservation is asserted only on include-free inputs: `test01.conf` contains
`include required("test01a")` pointing at a file that does not exist, so the raw input cannot be
parsed on its own. Files with includes are covered by idempotence instead.
