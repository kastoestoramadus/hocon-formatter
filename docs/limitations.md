# Known limitations

## sconfig defects the formatter refuses

Each is reproduced with a bare sconfig parse-render round trip, with no code of ours involved.
`format` verifies its own output and returns a `Refusal`, so no integration writes the file.

Each has a **failing** test in `SconfigDefectsSpec` asserting what sconfig ought to produce. The
expected text is not guessed: each case is paired with a plainly written config that means the same
thing and renders correctly, and the test first asserts both `resolve()` to the same value. A
failure therefore prints a diff ready to paste into an upstream issue. Run them with
`sbt libraryDefects`, on all three platforms: 12 failures on the JVM and Native, 13 on Scala.js. When a
sconfig release fixes one, its test turns green: that is the signal to drop the refusal and the
entry below. sconfig 2.0.0 was tried on 2026-09-25: the regular suites pass on it, and the nine
defects then known remain.

Rendered as text that will not parse again (`Refusal.BrokenOutput`):

- **`+=` field separator**: `a : [1]` then `a += 2`
- **Self-referential substitution**: `a : 1` then `a : ${a}`
- **Array concatenation with a substitution**: `path = ${path} [ /usr/bin ]`
- **String concatenation with a substitution**: `path : ${path}":d"`
- **Nested self-reference**: `foo : ${foo.a}`
- **Object concatenation with a substitution**: `e = ${g} { name = "east" }`, the ordinary
  config-inheritance idiom, which sconfig renders as `e: ${g}name: east`. The one worth reporting
  upstream first: short, obviously wrong, and common.
- **A one-field object holding a substitution, inside an array**: `a : [ { b : ${?X} } ]` loses
  its braces and renders as `a: [ b: ${?X} ]`. Two fields, or no substitution, keep them.

Rendered without a comment (`Refusal.LostComment`); a comment has no meaning to compare, so only
this check notices:

- **A comment no field follows**: after the last field of the file or of an object, and every
  comment of a file that holds nothing else. sconfig attaches a comment to the field after it.

Dropped with its object (`Refusal.LostInclude`):

- **An include in an object that a later definition of the key replaces**: `o { include "x.conf" }`
  then `o : 5`. sconfig merges repeated keys, so the include no longer mattered, but its text would
  vanish.

Re-parseable, but not a fixed point (`Refusal.UnstableOutput`):

- **Substitution cycle**: `a : ${b}` with `b : ${a}` renders as an unresolved-merge banner that
  parses but changes again on the next pass. A syntax check alone misses this.

Rejected at parse time although the specification allows them (`Refusal.NotHocon`):

- **An array at the file root**: `[ "a", "b" ]`
- **The `[]` env-variable list suffix**: `${MY_LIST[]}`

Scala.js only:

- **An object nested 32 or more deep** renders as text that fails to parse ("empty path") and is
  refused (`Refusal.BrokenOutput`). 31 levels, and a dotted path of any length, are fine.

- **Parsing text containing an `include`** throws `NotImplementedError`. See
  [architecture](architecture.md#platforms) for why the formatter is unaffected.

## Intentional normalisations

These are sconfig's renderer doing what the `ConfigFormatOptions` in `HoconFormatter` ask of it,
not defects. Meaning is preserved, original spelling is not. Pinned in `HoconSpecCoverageSpec`:

- `//` comments become `#`
- `=` becomes `:`
- nested objects are flattened to path keys (`setSimplifyNestedObjects`)
- triple-quoted strings become escaped single-line strings
- number literals are canonicalised (`1.5e3` becomes `1500`)
- unicode escapes are resolved (`\u0041` becomes `A`)
- line endings become `\n`, and a UTF-8 byte-order mark is dropped
- a comment at the end of a line moves to its own line above

## Refused although valid

- **A value starting with the word `include` followed by a quoted string**, `a : include "x"`:
  the concatenation is read as a directive, and the file is refused as `Refusal.NotHocon`.

## Speed

sconfig's parser is about 27 times slower on Scala Native than on the JVM, and its renderer is the
slow part on Scala.js. A service's `application.conf` formats in about 1 ms on Native, well under
the process start-up; a generated 330 KB file takes about 2 s there against 0.1 s on the JVM.
Current numbers: `scripts/bench.py report`.
