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
sbt test                                                    # all suites
sbt "testOnly ww86.hocon_fmt.HoconSpecCoverageSpec"         # one suite
sbt "testOnly ww86.hocon_fmt.HoconFormatterInvariantsSpec -- *idempotent*"  # one test
sbt scalafmtAll                                             # apply formatting
sbt scalafmtCheckAll                                        # verify formatting (CI runs this)
sbt "runMain ww86.hocon_fmt.CmdApi --check path/to/file.conf"   # report without writing
sbt "runMain ww86.hocon_fmt.CmdApi path/to/file.conf"           # rewrite in place

UPDATE_GOLDEN=1 sbt "testOnly ww86.hocon_fmt.GoldenFileSpec"    # rewrite expected files
```

`build.sbt` sets no `mainClass`. The real entry point is `CmdApi.main`;
`src/main/scala/Main.scala` is leftover sbt template code printing "Hello world!" and is unrelated
to the tool.

## Architecture

Two files carry the logic:

- **`HoconFormatter`** — pure `String => Try[String]`. Parses with sconfig and re-renders through
  `ConfigRenderOptions` + `ConfigFormatOptions`.
- **`CmdApi`** — scopt CLI, processes files in parallel, chooses between `--check` and in-place rewrite.

### The include workaround — read this before touching `HoconFormatter`

Parsing a HOCON file resolves and discards its `include` directives, so they cannot survive a plain
parse-then-render round trip. The formatter masks them instead:

1. `replaceNonQuotedIncludeKeywordsWithPlaceholder` rewrites every non-quoted `include ` into a
   marker `__REMOVE<n>: ME, # __INCLUDE `. The index `<n>` keeps markers unique, and making it a
   real field forces the renderer to preserve its position.
2. sconfig parses and renders the masked text.
3. Three `replaceAll` passes turn the markers back into `include`.

`isInsideString` exists so the word `include` appearing inside a string or comment is left alone.

**Why it looks like this:** the author contributed formatting support to sconfig upstream, but the
deeper fixes needed here were too invasive to land in that library. The remaining gaps are worked
around in this repo instead. The placeholder scheme is load-bearing, not accidental cruft — treat a
change to it as a change to the core algorithm.

### Regex constraint: no lookaround

sconfig is published for JVM, Scala.js and Scala Native, so this formatter can follow it — but
Scala Native implements `java.util.regex` on RE2, which does not support lookaround
(`(?=)`, `(?!)`, `(?<=)`, `(?<!)`), possessive quantifiers, or `\G` `\R` `\Z`.

Keep every pattern here RE2-compatible. The include detector already had to change from
`(?<!\w)include\s+` to `\binclude\s+`; the two are equivalent because `include` starts with a word
character, so `\b` holds exactly when the preceding character is a non-word character or the string
start.

## Known limitations

### Destroyed input — the serious ones

`format` returns `Success` while producing output that does not parse. Because `CmdApi` writes on
success, running the tool in rewrite mode replaces a valid file with an unparseable one.

- **`+=` field separator** (`a : [1]` then `a += 2`), at the root and nested.
- **Self-referential substitution** (`a : 1` then `a : ${a}`).

Both are valid HOCON per the spec. The cause is the same: sconfig renders an unresolved merge as a
comment block that says in its own text that it will not be parseable. Pinned in
`HoconSpecCoverageSpec` under `DESTROYED:`.

### Same-line includes

Everything after an `include` on the same line is swallowed by the `# __INCLUDE` comment the
preprocessing inserts:

- `o { include "f.conf" }` — fails to parse, the closing brace is commented out.
- `include "x","y" : 42` — passes through unformatted (this is `test03.conf`).
- `include"f.conf"` with no whitespace — silently produces empty output; the include is lost.

This is an unsolved problem, not a design decision — no approach was found that handles every
`include` case while still formatting same-line entries after a comma. Solving it makes these
limitations obsolete and turns the pinned tests red, which is the intended signal.

### CLI exit path

`CmdApi` calls `sys.exit(-1)` from inside a parallel `foreach`. The shell sees exit code 255, and
the JVM dies mid-iteration, so in `--check` mode some files may never be examined.

### Intentional normalisations — do not "fix" these

Meaning is preserved, original spelling is not. Pinned in `HoconSpecCoverageSpec`:
`//` comments become `#`; `=` becomes `:`; nested objects are flattened to path keys
(`setSimplifyNestedObjects`); triple-quoted strings become escaped single-line strings; number
literals are canonicalised (`1.5e3` becomes `1500`); unicode escapes are resolved (a `\u0041` escape
becomes the literal `A`).

## Tests

Four suites, split by concern so a change answers to one place:

- **`GoldenFileSpec`** — rendering. Each `<name>.conf` in `src/test/resources` is compared against
  `<name>.expected.conf`. Adding a fixture is a two-file drop, no Scala changes. Regenerate with
  `UPDATE_GOLDEN=1` and read the diff before committing — a golden file is only worth what the
  human who approved it looked at.
- **`HoconFormatterInvariantsSpec`** — relations holding for every fixture: idempotence
  (`format(format(x)) == format(x)`), output re-parses, meaning preservation
  (`parse(raw) == parse(format(raw))`), and adversarial inputs shaped like the internal
  `__REMOVE<n>: ME` markers.
- **`IncludeDetectionSpec`** — which occurrences of the word `include` are a directive and which
  are ordinary text. This is the contract of the detection regex; change that regex and answer here.
- **`HoconSpecCoverageSpec`** — coverage against the HOCON specification: what is destroyed, what is
  normalised on purpose, what is supported.

Meaning preservation is asserted only on include-free inputs: `test01.conf` contains
`include required("test01a")` pointing at a file that does not exist, so the raw input cannot be
parsed on its own. Files with includes are covered by idempotence instead.
