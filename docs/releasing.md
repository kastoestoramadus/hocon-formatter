# Releasing

Every channel ships the same core, so all of them are released together, at one version. Nothing
has been released yet: the one-time setup below is still to do, and the `Release` workflow has
never run.

| artifact | built by | published to | users get it through |
|---|---|---|---|
| `hocon-formatter-core_3`, `hocon-formatter-cli_3` | sbt | Maven Central | a library dependency; the JVM command line |
| `sbt-hocon-formatter` | sbt | Maven Central | `addSbtPlugin` |
| `hocon-formatter-maven-plugin` | `maven-plugin/` | Maven Central | `<plugin>` |
| `mill-hocon-formatter_mill1_3` | `mill-plugin/` | Maven Central | `//| mvnDeps` |
| `io.github.kastoestoramadus.hocon-formatter` | `gradle-plugin/` | Gradle Plugin Portal | `plugins { id(...) }` |
| native binaries, `hocon-formatter.js` | `Release` workflow | GitHub release | a download; the playground |
| wheels carrying the native binary | `Release` workflow | PyPI | the pre-commit hooks, `pipx install` |
| npm package carrying the Node build | `Release` workflow | npm | the `-node` pre-commit hooks, `npx` |

The names are free on every registry (checked 2026-09-26).

## One-time setup

Accounts and secrets only you can create; each section ends with the code this repository still
needs, which is a pull request of its own.

### Maven Central

1. Sign in to [central.sonatype.com](https://central.sonatype.com) with GitHub. That verifies the
   namespace `io.github.kastoestoramadus` on the spot; no DNS record or ticket.
2. Generate a user token (account menu → Generate User Token). Its username and password, not the
   account's, are what publishing uses.
3. Create a signing key and publish its public half:
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id> | base64 -w0   # the value of PGP_SECRET
   ```
4. Add repository secrets (Settings → Secrets and variables → Actions): `SONATYPE_USERNAME`,
   `SONATYPE_PASSWORD`, `PGP_SECRET`, `PGP_PASSPHRASE`.

Code still needed: sbt-ci-release (1.11 or later, which publishes through the Central Portal) for
`coreJVM`, `cliJVM` and `sbtPlugin`; `central-publishing-maven-plugin`, `maven-gpg-plugin` and
source and javadoc jars in `maven-plugin`; for Mill, `mill.javalib.SonatypeCentralPublishModule/`,
which reads the same secrets as `MILL_SONATYPE_USERNAME`, `MILL_SONATYPE_PASSWORD`,
`MILL_PGP_SECRET_BASE64` and `MILL_PGP_PASSPHRASE`; and a release job running them.

### Gradle Plugin Portal

1. Sign in to [plugins.gradle.org](https://plugins.gradle.org) with GitHub. The portal only takes
   new plugins under a namespace it can verify, which `io.github.kastoestoramadus.hocon-formatter`
   is.
2. Copy the API key and secret from your profile into the secrets `GRADLE_PUBLISH_KEY` and
   `GRADLE_PUBLISH_SECRET`.

Code still needed: the `com.gradle.plugin-publish` plugin, with the website, VCS URL and tags it
requires, and `./gradlew publishPlugins` in the release job.

### PyPI: the pre-commit hooks' binaries

1. Create an account with two-factor authentication.
2. Add a *pending* trusted publisher at
   [pypi.org/manage/account/publishing](https://pypi.org/manage/account/publishing/): project
   `hocon-formatter`, owner `kastoestoramadus`, repository `hocon-formatter`, workflow
   `release.yml`, environment `pypi`. It reserves the name and needs no token; the first upload
   turns it into the project's publisher.
3. Create the environment `pypi` (Settings → Environments). Requiring yourself as a reviewer
   there makes every upload wait for a click.

Code still needed: a job in `release.yml` with `environment: pypi`, `permissions: id-token: write`
and `pypa/gh-action-pypi-publish`, uploading the wheels the `native` jobs build.

### npm: the Node hooks

1. Create an account with two-factor authentication, and `npm login`.
2. Publish the first version by hand, from the tarball the release attaches:
   `npm publish --access public hocon-formatter-<version>.tgz`. npm lets you configure a trusted
   publisher only for a package that already exists.
3. On npmjs.com, in the package's settings, add a trusted publisher: repository
   `kastoestoramadus/hocon-formatter`, workflow `release.yml`.

Code still needed: a job publishing later versions from `release.yml`, with
`permissions: id-token: write` and npm 11.5.1 or later.

### GitHub

Nothing to create. `release.yml` runs by hand only once it is on `main`: GitHub offers "Run
workflow" for workflows on the default branch.

## Each release

1. Set the version in the five places that carry it: `build.sbt` (`ThisBuild / version`),
   `gradle-plugin/build.gradle.kts` (`version`), `maven-plugin/pom.xml` (the plugin's own version
   and the `hocon-formatter-core_3` dependency), `mill-plugin/build.mill` (`formatterVersion`),
   and the `additional_dependencies` of all four hooks in `.pre-commit-hooks.yaml`. The npm and
   wheel versions follow `build.sbt`.
2. Run the `Release` workflow by hand first (Actions → Release → Run workflow). It builds every
   artifact without releasing anything, which is how to find out the matrix works.
3. Push a tag `v<version>`. The workflow links native binaries for Linux (x86_64, aarch64) and
   macOS (aarch64), smoke-tests them, wraps each in a wheel, packs the npm package, builds the web
   script, and attaches all of it to a GitHub release.
4. Publish, in dependency order:
   - Maven Central, first: the Gradle, Maven and Mill plugins and the sbt plugin all resolve the
     core from there.
   - The Gradle Plugin Portal.
   - PyPI and npm, before announcing the tag: the hooks at that tag pin those exact versions.
5. Try every channel as a user would (below).

Until the core is on Maven Central, the Gradle and Maven builds resolve it from Maven Local and
the Mill build from the local Ivy repository: run `sbt coreJVM/publishM2` or
`sbt coreJVM/publishLocal` before building them.

## pre-commit

A user's configuration names this repository and a tag:

```yaml
repos:
  - repo: https://github.com/kastoestoramadus/hocon-formatter
    rev: v0.1.0
    hooks:
      - id: hocon-formatter
```

pre-commit clones the tag, reads `.pre-commit-hooks.yaml` and installs the hook's
`additional_dependencies` from PyPI or npm. So a tag works only once the versions it pins are
published, and nothing else about the hooks needs releasing. Users move to a new release with
`pre-commit autoupdate`. The hooks install everything at install time, so they also run on
pre-commit.ci.

The wheels cover Linux x86_64 and aarch64 with glibc 2.34 or newer and macOS on Apple silicon.
Anywhere else (Windows, Intel Macs, older Linux) pip finds no wheel, and the `-node` hooks are the
way in.

To check a release, in any repository with a `.conf` file:

```bash
pre-commit try-repo https://github.com/kastoestoramadus/hocon-formatter hocon-formatter --ref v<version> --all-files
```

## Trying a release

```bash
pipx run hocon-formatter --check application.conf          # the wheel, native
npx hocon-formatter@<version> --check application.conf     # the npm package
```

Then the plugins as [usage](usage.md) shows them, with the released version, in a project that
has no local repositories configured: `mavenLocal()`, `~/.m2` and `~/.ivy2/local` would hide a
missing publication.
