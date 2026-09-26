# Ideas

What could come next, kept for review rather than promised. Each entry says what it is, why it
would be worth it, and what it takes, split where it matters into **your part** (accounts,
repositories, decisions) and **code**. Sizes are rough: S is an afternoon, M a few days, L more.
Written 2026-09-26, after the plugins, the web script and the release runbook; publishing itself
is in [releasing](releasing.md), not here.

## Found while trying the channels

### A file that cannot be read fails the run (S)

`hocon-formatter --check missing.conf` prints
`ERROR: cannot format, leaving unchanged: .../missing.conf (missing.conf)` and exits 0. A typo in
a CI script's path therefore passes silently, and the reason in brackets is the exception's
message, which for a missing file is only its name. A missing or unreadable file is not a refusal
of its content: it should say `cannot read <path>: no such file` and exit 2, as a usage error
does, leaving 1 to mean "unformatted". **Code:** a new `Outcome` for unreadable files, tests in
`CmdApiSpec` first, on all three platforms.

### One report format for every channel (S)

The command line kept the original tool's messages, and they disagree with the plugins':

| situation | CLI | sbt, Mill | Maven |
|---|---|---|---|
| rewritten | nothing | `Formatted <path>` | `Formatted <path>` |
| already formatted | `.`, with no newline | nothing | nothing |
| unformatted, `--check` | `Found a not formatted file: <path> .` then the whole formatted text | `Not formatted: <path>` | `Not formatted: <path>` |
| refused | `ERROR: cannot format, leaving unchanged: <path> (<reason>)` | `Leaving <path> unchanged: <reason>` | the same |
| summary | `Running HOCON formatter for 1 files.` first | `HOCON files: 1 formatted, ...` | `HOCON files: 1 reformatted, ...` |

"ERROR" for something that does not fail the run misleads, and "1 files" reads badly everywhere.
Settle on the plugins' wording, print the summary last, and move the formatted text behind a
`--diff` flag. **Code:** mostly `CmdApi.render`, the Maven plugin's summary, and the tests that
pin the old messages; the pre-commit e2e script reads none of them.

### Wheels for more machines (M)

The wheels cover Linux with glibc 2.34+ (Ubuntu 22.04, Debian 12, RHEL 9 and newer) and Apple
silicon. Intel Macs, Windows and older Linux get no wheel, so their users fall back to the Node
hooks, twice as slow and 200 MB heavier. **Code:** a `macos-13` job for Intel Macs; linking the
Linux binaries in a `manylinux_2_28` container to lower the glibc floor; a Windows build (below).
Each is a matrix entry in `release.yml`, then a test that the wheel installs where it claims to.

### A native build for Windows (M)

Scala Native supports Windows with clang, but nothing here has tried it: the file handling in
`cli` goes through fs2, which should work, and the regex constraints are the same RE2 ones.
Worth it for Windows developers, who now need Node, and a prerequisite for Scoop and Windows
wheels. **Code:** a `windows-latest` entry in the release matrix and in CI; expect path-handling
surprises in `CmdApiSpec`.

## The command line

### Standard input to standard output (S)

`hocon-formatter --stdin < in.conf > out.conf`, with `--stdin-filename` for messages. Nearly
every editor integration expects exactly this, and so do Spotless's `nativeCmd` step and an LSP
server; it is what unlocks most of the entries below. A refused input would print nothing on
stdout and the reason on stderr, with exit 1, since an editor must not replace a buffer with
nothing.

### Directories, ignores, `--version` (S)

`hocon-formatter src/` walking for `*.conf` and `*.hocon`, skipping what `.gitignore` excludes, as
ruff and prettier do; today the caller expands globs. `--version` for bug reports. Maybe
`--strict`, turning a refusal into a failure, for teams that want every `.conf` to be HOCON.

## Distribution

### Homebrew tap (S)

`brew install kastoestoramadus/tap/hocon-formatter` on macOS and Linux, installing the release
binary. **Your part:** create the repository `kastoestoramadus/homebrew-tap`, and a fine-grained
token that can write to it only, saved as the secret `HOMEBREW_TAP_TOKEN` (the workflow's own
token cannot push to another repository). **Code:** `Formula/hocon-formatter.rb` with a URL and
SHA-256 per platform, and a release job that rewrites it for each tag. homebrew-core itself
accepts only projects with some following, so it comes later if at all.

### coursier (S)

`cs install hocon-formatter` for the Scala crowd, who have coursier already. coursier installs
apps from a channel, a JSON description that can point either at the JVM command line on Maven
Central or at the native binaries on GitHub releases. **Your part:** decide where the channel
lives (a file in this repository, or a small channel repository). **Code:** the app descriptor;
publishing `cliJVM` to Maven Central with its main class, if the JVM variant is used.

### Scoop (S, after the Windows build)

`scoop install hocon-formatter`, a JSON manifest in a bucket repository
`kastoestoramadus/scoop-bucket`, pointing at the Windows binary; same shape as the Homebrew tap.

### Nix (M)

A flake in this repository (`nix run github:kastoestoramadus/hocon-formatter`) that fetches the
release binary and patches its interpreter, and later a nixpkgs package. Building from source in
Nix's sandbox would mean packaging sbt's dependencies, which is where most Scala packages there
stall.

### Docker image (S)

`docker run --rm -v "$PWD:/work" -w /work ghcr.io/kastoestoramadus/hocon-formatter --check ...`,
for CI systems that run containers and nothing else. The binary links glibc and libstdc++
dynamically, so the base must carry both: Debian slim does, scratch and Alpine do not. **Your
part:** after the first push, check the package's visibility in its settings on GitHub and make it
public.
**Code:** a Dockerfile and a release job with `packages: write`, which needs no other secret.

## CI and tools

### GitHub Action (S)

```yaml
- uses: kastoestoramadus/hocon-formatter-action@v1
  with: { files: "**/*.conf", version: 0.1.0 }
```

A composite action that downloads the release binary for the runner and runs `--check`, with an
annotation on each unformatted file. **Your part:** the Marketplace takes an action only from a
public repository with `action.yml` at its root and no workflow files, so it needs its own
repository, `kastoestoramadus/hocon-formatter-action`; then tag `v1` and tick "Publish this
Action to the GitHub Marketplace" on the release. **Code:** `action.yml` and a short script.

### Spotless (S, after `--stdin`)

Teams on Spotless add a step rather than a plugin. With `--stdin`, Spotless's `nativeCmd` step
runs the binary on each file; a snippet in [usage](usage.md) is the whole integration. An
in-process step through `JvmFacade` would avoid the binary but ties the step to Spotless's
internals and its configuration-cache rules.

### Dependency updates (S)

Four build tools pin versions (sbt, Gradle, Maven, Mill) plus GitHub Actions, and the CI log
already warns that `actions/checkout@v4` and `actions/setup-java@v4` run on a deprecated Node.
Renovate or Scala Steward would propose the updates as pull requests; which one covers all four
build tools is the first thing to find out. **Your part:** installing the chosen app.

## Editors

### VS Code extension (M)

Format on save and "Format Document" for `.conf` and `.hocon`, running the Scala.js build inside
the extension, so nothing to install and nothing to spawn. The `web` module's API is most of it;
the extension needs a CommonJS build of it. A refusal shows as a diagnostic with its reason
instead of a silent no-op. **Your part:** a publisher account on the Visual Studio Marketplace
and Open VSX.

### Neovim, Helix, Zed (S, after `--stdin`)

Each takes an external formatter from configuration: conform.nvim's formatter list, Helix's
`languages.toml`, Zed's external `formatter` setting. Documenting the snippets is
enough; a pull request adding the formatter to conform.nvim's built-in list makes it one line
for users.

### Language server (M)

A small LSP server in the native binary: `textDocument/formatting`, and diagnostics saying why a
file would be refused. One integration then serves every LSP editor, including the ones above,
and refusals become visible while editing rather than at commit time.

### IntelliJ (M)

JetBrains' HOCON plugin formats with its own rules; an external-formatter plugin calling the JVM
core would bring this formatter's output and refusals into the IDE. Least urgent of the editors,
since IntelliJ users already have something.

## Build tools

### sbt 2 (M)

sbt 2 runs plugins on Scala 3, so a cross-build of the sbt plugin could call the core directly, as
the Mill plugin does, and drop the isolated class loader. Worth doing when sbt 2 is final and
sbt-scalafmt has moved.

### Mill without a trait (S)

`./mill ww86.hocon_fmt.mill.HoconFormatter/checkAll __.resources`, an external module like Mill's
own `ScalafmtModule/checkFormatAll`, so a build can check its files without changing its modules.

### Gradle version matrix (S)

The functional tests run on the Gradle that builds the plugin. TestKit's `withGradleVersion` can
run them on the oldest supported Gradle too, which is what the Mill plugin's integration test
already does for Mill.

## The formatter itself

### A parser of our own (L)

The largest item and the one that removes most limitations. Formatting through sconfig means
parsing into a configuration and rendering it again, which is why: `include` needs masking; every
sconfig rendering defect ([limitations](limitations.md)) becomes a refusal; `=` becomes `:`, `//`
becomes `#` and paths get flattened whether the author wanted it or not; and parsing is 27 times
slower on Scala Native than on the JVM. A concrete syntax tree for HOCON, parsed with cats-parse
and printed by our own printer, keeps every token the author wrote, so the formatter changes only
layout. It is also what a configurable style needs (next). The branch `spike/cst-cats-parse` was
started for it but holds no code. **Code:** the grammar from the HOCON specification, a
round-trip property (parse then print unchanged is the identity), then the printer; the existing
suites and properties keep their meaning.

### Configurable style (M, after the parser)

A `.hocon-formatter.conf` choosing `:` or `=`, indentation, and whether to flatten single-key
objects. Every channel would read it from the project root. Pointless while sconfig decides the
output.

### Skip unchanged files (S)

A cache of content hashes, as scalafmt keeps, so a large repository checks in milliseconds. Only
the command line needs it; build tools and pre-commit already pass only relevant files.

### Report sconfig's defects upstream (S)

Each `library:` test in `SconfigDefectsSpec` is a reproduction against bare sconfig. Filing them
at [ekrich/sconfig](https://github.com/ekrich/sconfig) helps everyone on sconfig and may shrink
[limitations](limitations.md). **Your part:** filing them, or approving them to be filed.

## The project

### Java style in the Gradle and Maven plugins (S)

The two Java builds are formatted by hand, not quite alike. google-java-format through Spotless
in both, checked in CI, as `scalafmtCheckAll` checks the Scala.

### Benchmarks that can gate (M)

CI reports benchmarks without failing, because shared runners are noisy. A dedicated machine or
a self-hosted runner, and a chart of `refs/notes/benchmarks` published to GitHub Pages, would let a
slowdown fail a pull request.

### Playground extras

Sharing an input by link, a diff view and a "report this refusal" button: see
[playground](playground.md#later).
