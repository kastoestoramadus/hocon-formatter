# hocon-formatter

Formats [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) files in place, or with
`--check` reports the ones that are not formatted and exits 1. A file it cannot format safely is
left untouched.

This wheel carries the native binary and nothing else: installing it puts `hocon-formatter` on
your PATH. It exists mainly for the project's pre-commit hooks. The same tool ships on npm and as
sbt, Gradle, Maven and Mill plugins; see the
[project page](https://github.com/kastoestoramadus/hocon-formatter).
