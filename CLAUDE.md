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

### The include masking — read this before touching `HoconFormatter`

Parsing a HOCON file resolves and discards its `include` directives, so they cannot survive a plain
parse-then-render round trip. Each **whole include statement** is swapped for a placeholder field
before parsing and swapped back afterwards:

1. `maskIncludes` scans for `include` at a word boundary, outside a string, and works out the
   extent of the statement — a quoted target, or `required(...)` / `file(...)` / `url(...)` /
   `classpath(...)` with balanced parentheses. Anything else (`include_path`, the word `include` in
   prose) is left alone. The statement is replaced by `__INCLUDE_<n>` plus a guard field, and the
   original text is kept in a side table keyed by `<n>`.
2. sconfig parses and renders the masked text.
3. `unmaskIncludes` puts the original statements back and drops the guard fields.

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

### Regex constraint: no lookaround

sconfig is published for JVM, Scala.js and Scala Native, so this formatter can follow it — but
Scala Native implements `java.util.regex` on RE2, which does not support lookaround
(`(?=)`, `(?!)`, `(?<=)`, `(?<!)`), possessive quantifiers, or `\G` `\R` `\Z`.

Keep every pattern here RE2-compatible. The include detector already had to change from
`(?<!\w)include\s+` to `\binclude\s+`; the two are equivalent because `include` starts with a word
character, so `\b` holds exactly when the preceding character is a non-word character or the string
start.

## Known limitations

### Constructs the formatter refuses

Some valid HOCON cannot survive the parse-render round trip, because sconfig renders an unresolved
merge as a comment block that says in its own text that it will not be parseable. Rather than hand
that back, `format` verifies its own output and returns a `Failure`, so `CmdApi` leaves the file
untouched:

- **`+=` field separator** (`a : [1]` then `a += 2`), at the root and nested.
- **Self-referential substitution** (`a : 1` then `a : ${a}`).

Supporting these properly would need the same masking treatment `include` gets. Pinned in
`HoconSpecCoverageSpec` under `refuses rather than corrupts`.

The check only rejects a syntax error in the output. Output containing `include required("x")`
throws while *resolving* includes, which says nothing about the text being well formed, so that is
allowed through.

### Intentional normalisations — do not "fix" these

Meaning is preserved, original spelling is not. Pinned in `HoconSpecCoverageSpec`:
`//` comments become `#`; `=` becomes `:`; nested objects are flattened to path keys
(`setSimplifyNestedObjects`); triple-quoted strings become escaped single-line strings; number
literals are canonicalised (`1.5e3` becomes `1500`); unicode escapes are resolved (a `\u0041`
escape becomes the literal `A`).

## Tests

Four suites, split by concern so a change answers to one place:

- **`GoldenFileSpec`** — rendering. Each `<name>.conf` in `src/test/resources` is compared against
  `<name>.expected.conf`. Adding a fixture is a two-file drop, no Scala changes. Regenerate with
  `UPDATE_GOLDEN=1` and read the diff before committing — a golden file is only worth what the
  human who approved it looked at.
- **`HoconFormatterInvariantsSpec`** — relations holding for every fixture: idempotence
  (`format(format(x)) == format(x)`), output re-parses, meaning preservation
  (`parse(raw) == parse(format(raw))`), and adversarial inputs shaped like the internal
  `__INCLUDE_<n>` and `__INCLUDE_GUARD_<n>` fields.
- **`IncludeDetectionSpec`** — which occurrences of the word `include` are a directive and which
  are ordinary text. This is the contract of the detection regex; change that regex and answer here.
- **`HoconSpecCoverageSpec`** — coverage against the HOCON specification: what is refused, what is
  normalised on purpose, what is supported.
- **`CmdApiSpec`** — CLI behaviour on temp files: exit codes, that every file is examined, and that
  a file the formatter cannot handle is never overwritten.

Meaning preservation is asserted only on include-free inputs: `test01.conf` contains
`include required("test01a")` pointing at a file that does not exist, so the raw input cannot be
parsed on its own. Files with includes are covered by idempotence instead.
