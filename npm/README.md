# hocon-formatter

Formats [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) files in place, or with
`--check` reports the ones that are not formatted and exits 1. A file it cannot format safely is
left untouched.

```bash
npx hocon-formatter --check src/main/resources/application.conf
```

This package is the Scala.js build of the command line tool; the same tool ships as a native
binary and as sbt, Gradle and Maven plugins. See the
[project page](https://github.com/kastoestoramadus/hocon-formatter).
