# Releasing

Every channel ships the same core, so they are released together, at one version.

1. Set the version in the four places that carry it:
   `build.sbt` (`ThisBuild / version`), `gradle-plugin/gradle.properties`, `maven-plugin/pom.xml`
   (the plugin's own version and the `hocon-formatter-core_3` dependency), and the
   `additional_dependencies` of all four hooks in `.pre-commit-hooks.yaml`.
2. Push a `v<version>` tag. The `Release` workflow links native binaries for Linux (x86_64,
   aarch64) and macOS (aarch64), smoke-tests them, wraps each in a wheel, packs the npm package,
   and attaches all of it to a GitHub release. Run the workflow by hand first to try the matrix
   without releasing.
3. Publish, in dependency order; none of this is automated yet, since it needs credentials:
   - Maven Central, from sbt: `coreJVM`, `cliJVM` and `sbtPlugin`. The build has the metadata
     Sonatype requires but no signing yet; sbt-ci-release would add both signing and publishing.
   - PyPI: the wheels, with `twine upload hocon_formatter-*.whl` or trusted publishing; the
     pre-commit hooks install them from there
   - npm: `npm publish hocon-formatter-<version>.tgz`; the Node hooks install it from there
   - Gradle Plugin Portal: `./gradlew publishPlugins` in `gradle-plugin`
   - Maven Central: `./mvnw deploy` in `maven-plugin`

Until the core is on Maven Central, the Gradle and Maven builds resolve it from Maven Local:
run `sbt coreJVM/publishM2` before building them.
