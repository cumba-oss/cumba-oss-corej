# Anatomy of the parent `pom.xml`

This file walks through what each plugin and configuration block in
the root `pom.xml` does, written for a developer who isn't deep in
the Maven world.

If you just want to use the template: skip to the `README.md`. This
document is a reference for **why** things look the way they do.

---

## 1. What Maven is doing for you

Maven is a build automation tool. You declare what you want (this
artifact depends on those libraries; produce a jar; run these tests)
and Maven runs a fixed sequence of **phases** to make it happen. The
key phases for a Java project, in order:

```
validate → compile → test → package → verify → install → deploy
```

A **plugin** binds itself to one or more phases. Running `mvn install`
runs everything up to and including the `install` phase, executing
every plugin bound along the way.

The parent pom is special: it's a `pom`-packaged project with no code
of its own. It exists to share configuration with the child modules
(`lib/cumba-oss-corej-core`, etc.). Children inherit:

- Properties (version numbers, encoding, …).
- `<pluginManagement>` — default plugin configuration. A child only
  has to *declare* `<plugin><artifactId>maven-compiler-plugin</…>` to
  pick up everything in pluginManagement.
- `<dependencyManagement>` — version + scope of dependencies. A child
  only declares `<groupId>`+`<artifactId>`; the version comes from
  here.
- `<build><plugins>` — plugins that run on every child by default
  (unlike pluginManagement, these are active without being declared).

---

## 2. Project coordinates and version

```xml
<groupId>com.example.myproject</groupId>
<artifactId>myproject-parent</artifactId>
<packaging>pom</packaging>
<version>${revision}</version>
```

- **`groupId` / `artifactId` / `version`** — the unique coordinates
  of any Maven artifact. Every child module inherits the groupId and
  version unless it overrides them.
- **`${revision}`** is a Maven "CI-friendly version" pattern. The
  actual version is supplied externally (e.g.,
  `mvn -Drevision=1.0.0 install`), falling back to `0.1.0-SNAPSHOT`
  declared in `<properties>`. This avoids hard-coding the version in
  hundreds of pom files. It pairs with `flatten-maven-plugin` (§7)
  which produces a published pom where `${revision}` is replaced by
  the resolved value.

---

## 3. Properties

Every key under `<properties>` is a constant referenced as `${key}`
elsewhere in the pom or in child poms. Highlights:

| Property                         | Purpose                                    |
|----------------------------------|--------------------------------------------|
| `java.version`                   | JDK target (25). Drives `maven.compiler.{release,target,source}`. |
| `maven.version`                  | Required Maven runtime (3.9.12), enforced by `maven-enforcer-plugin` (§5). |
| `general.encoding`               | UTF-8 for sources, reports, properties files. |
| `revision`                       | Fallback version when not passed via `-Drevision`. |
| `jacoco.line.coverage`           | Per-module line-coverage minimum (default 0.80). Override per-module or via `-Djacoco.line.coverage=0.0`. |
| `maven.compiler.failOnWarning`   | `true`: any javac warning fails the build. Flip with `-D` for lint sweeps. |
| `sonar.*`                        | Sonar project key/name + path exclusions. |
| `dependency.*.version`           | Pinned third-party versions used by `<dependencyManagement>`. |
| `plugin.*.version`               | Pinned plugin versions. Centralised so every plugin upgrade is a one-line change. |

---

## 4. Profiles

Maven profiles are conditional bundles of configuration. They can add
modules, override properties, add plugins, anything. Activated with
`-P <name>` on the command line, or automatically by triggers like
"this file exists" or "a system property has this value".

This pom defines:

### `dev` (active by default)

Inner-loop development build. Enables `-Xlint:all` so javac surfaces
every warning category; combined with `failOnWarning=true`, every
warning becomes a build failure.

Removed. This pom used to declare `dev` and `main` profiles, both
`activeByDefault`, carrying identical module lists — which never worked, because
Maven deactivates every `activeByDefault` profile as soon as any other profile
activates. `<modules>` is now at top level and the strict `-Xlint:all` the `dev`
profile carried applies unconditionally.

### `main`

Release build. Excludes dev-only modules. Keeps default lint (no
`-Xlint:all`), so release builds aren't blocked by style nits in
modules that are otherwise green.

### `PMD` (active by default, skip with `-DskipPmd`)

Runs the PMD static analyser (`maven-pmd-plugin`) at `verify`. The
ruleset lives at `pmd-ruleset.xml` in the project root.

Report-only by default — findings don't fail the build. Flip to a
hard gate with `-Dpmd.failOnViolation=true`. Skip the profile
entirely with `-DskipPmd`.

### `SpotBugs` (active by default, skip with `-DskipSpotbugs`)

Runs SpotBugs at `verify` against the project-wide filter
(`spotbugs_project_filter.xml`); per-module filters are layered by
`spotbugs-module-ignore` when present.

Report-only by default. Flip to a hard gate with
`-Dspotbugs.failOnError=true`. Skip entirely with `-DskipSpotbugs`.

### `Pitest` (opt-in: `-P Pitest` **or** `-Dpitest.enabled=true`)

Runs the [Pitest](https://pitest.org) mutation-coverage analyser at
`verify`, writing the per-module HTML/XML report to
`<module>/target/pit-reports/`. Mutation testing complements line
coverage by asking *"if I mutate the production code, does any test
notice?"* — high line coverage with low mutation coverage usually
means tests exercise the code without asserting on it.

**Opt-in only.** The profile does not run on a plain `mvn verify` —
mutation testing is too slow to enable for every inner-loop build.
Activate either by name (`-P Pitest`) or by property
(`-Dpitest.enabled=true`). Maven always honours the `-P <id>` form
regardless of `<activation>`, so both paths work.

Incremental analysis is enabled via the OSS
`io.github.mibimiflo:pitest-history` plugin, declared as a dependency
of `pitest-maven` in the profile. It registers a `HistoryFactory` SPI
that fills the gap pitest 1.17+ left when it removed its built-in
OSS history reader/writer (now part of the commercial
`com.arcmutate:base` subscription). With it on the classpath, the
`<historyInputFile>` / `<historyOutputFile>` settings in the plugin
config read and write
`${maven.multiModuleProjectDirectory}/.pitest-history/<artifactId>/history.bin`
— i.e. the file lives at the **repo root**, outside any module's
`target/`, so `mvn clean` does not wipe it and warm-cache runs reuse
it. The CI workflow caches the directory between runs (see
`.gitea/workflows/main.yml`), so warm-cache mutation passes finish
in a fraction of the cold-run time.

Report-only by default (all three thresholds at 0). Flip to a hard
gate by adding `-Dpitest.failOnError=true` to the opt-in, e.g.
`mvn -P Pitest verify -Dpitest.failOnError=true`. That activates the
`pitest-fail-on-error` sub-profile, which promotes each module's
`pitest.mutation.target` / `pitest.coverage.target` /
`pitest.test.strength.target` onto the effective `*.threshold`
properties.

> **Do not** pass `-Dpitest.mutation.threshold=…` on the command line
> — a CLI `-D` clobbers every per-module override at once. Set the
> per-module `<pitest.mutation.target>` (and friends) in the module's
> `pom.xml` and let the `pitest-fail-on-error` profile promote them.

The pitest plugin config also mirrors Surefire's `projectBasedir` /
`repoRoot` system properties into the minion JVMs via `<jvmArgs>`.
Pitest forks its own JVMs separately from Surefire and does **not**
inherit Surefire's `<systemPropertyVariables>`, so without this
mirror any test that reads `System.getProperty("projectBasedir")` or
`System.getProperty("repoRoot")` would see `null` under `mvn -P Pitest`
even though it works fine under `mvn test`.

### `pitest-fail-on-error` (auto-activated by `-Dpitest.failOnError=true`)

Companion sub-profile to `Pitest`. When `-Dpitest.failOnError=true`
is set, this profile copies each `pitest.<x>.target` onto the
matching `pitest.<x>.threshold`, turning the per-module goals into
build-failing gates. Used by CI; local builds leave the effective
thresholds at 0.

> The toggle is a no-op without `Pitest` itself active. Always pair
> with `-P Pitest` (or `-Dpitest.enabled=true`); otherwise the
> sub-profile updates threshold properties that no plugin reads.

### `ecj` (opt-in, stacks)

Runs a **second** compile pass using the Eclipse compiler (ECJ) at
`verify`. ECJ catches some warning categories javac doesn't — useful
for paranoid CI. Documented as currently flaky upstream when combined
with Lombok; left wired up so flipping it on is one config change.

### `ErrPrn` (opt-in via `-P ErrPrn`)

Runs Google's **Error Prone** as a javac plugin. Catches bug
patterns (`NullAway`-style issues, accidental equality, etc.). The
`<fork>true</fork>` + `-J--add-exports` blocks are required because
Error Prone needs access to internal JDK compiler APIs that JDK 16+
closes by default.

Report-only by default. Flip to a hard gate with
`-Derrprn.failOnWarning=true`. Add extra checks with
`-Derrprn.extraArgs="..."` (e.g. enable NullAway).

### `spotless-check-mode` (auto-activated by `-Dspotless.check=true`)

By default Spotless runs `apply`, rewriting unformatted files in
place at `process-sources`. When `-Dspotless.check=true` is set,
this profile flips the two phase properties so `apply` is bound to
`none` and `check` is bound to `process-sources` — the build fails
on unformatted code instead of rewriting it. Used by CI.

### `spotbugs-module-ignore` (auto)

Activates when a child module has a `spotbugs_ignore.xml` file in
its own directory; layers that file on top of the project-wide
`spotbugs_project_filter.xml`. Allows module-local suppressions
without touching the parent pom.

---

## 5. Plugins under `<build><plugins>` (always-on)

These run on every child module unless explicitly disabled. Each is
declared *and* given its actual configuration here — children don't
need to mention them.

### `maven-enforcer-plugin`

Fails the build if the Maven runtime is older than `${maven.version}`.
The pom uses `${maven.multiModuleProjectDirectory}` which only works
on 3.9.12+, so this is a hard requirement, not a preference.

### `maven-dependency-plugin` (execution `resolve-dependency-properties`)

Bound to `initialize`. Resolves every dependency's local path as a
property like `${org.mockito:mockito-core:jar}`. This makes
`-javaagent:${org.mockito:mockito-core:jar}` work in child poms
(used by `mockito.agent` for tests that need the Mockito agent).

### `spotbugs-maven-plugin`

Static analysis at `verify`. Configured with `effort=Max`,
`threshold=Low`, and `failOnError=true` — strict. Uses the
project-wide filter `spotbugs_project_filter.xml`. The
`spotbugs-module-ignore` profile (§4) layers in per-module filters
when present.

### `jacoco-maven-plugin`

Code coverage. Three executions:

| Execution        | Phase     | What it does                              |
|------------------|-----------|-------------------------------------------|
| `prepare-agent`  | initialize| Attaches the JaCoCo agent to surefire     |
| `report`         | test      | Generates the per-module HTML/XML report  |
| `check`          | verify    | Fails the build if line coverage <80%     |

The `<excludes>` list (driven by `${jacoco.coverage.excludes}`)
exempts class paths that don't need coverage — generated code, UI
adapters, JNI wrappers. Empty in the template; populate as your
project grows.

### `properties-maven-plugin`

Bound to `initialize`, **only on the aggregator** (`inherited=false`).
Reads `sonar-exclusions.properties` from the project root and exposes
every key as a Maven property prefixed with `sonar.`. The Sonar
scanner picks them up at `sonar:sonar` time. This is the mechanism
that turns the human-readable suppression file into Sonar's
multicriteria API.

> ⚠️ Standalone `mvn sonar:sonar` does **not** trigger `initialize`,
> so the suppressions never load. Always invoke as
> `mvn initialize sonar:sonar` (or any lifecycle command that already
> includes initialize, e.g. `mvn verify sonar:sonar`).

### `sonar-maven-plugin`

The SonarQube scanner. Reads everything: source paths, coverage
reports (from `sonar.coverage.jacoco.xmlReportPaths`), the
suppression list, and posts results to the Sonar server.

### `flatten-maven-plugin`

Produces `.flattened-pom.xml` at `process-resources` with
`${revision}` (and any other CI-friendly variables) resolved. The
flattened pom is what gets installed/deployed, so consumers don't
need to deal with `${revision}` themselves. The `clean` execution
removes the flattened file when `mvn clean` runs.

### `spotless-maven-plugin`

Reformats Java sources in-place at `process-sources` (i.e. *before*
compile). Uses the Eclipse JDT formatter driven by
`eclipse-formatter.xml` at the project root. Also sorts imports,
strips unused ones, trims trailing whitespace, and ensures every
file ends with a newline.

- The JDT engine is pinned to `4.38` because Spotless 3.5.1 + JDT
  4.39 hits a metadata-parser regression upstream
  (`diffplug/spotless#2897`).
- `upToDateChecking` is on, so unchanged files are skipped between
  runs.
- To opt out for a specific block: surround it with
  `// spotless:off` / `// spotless:on` comments. To opt out for a
  whole file, list it under `<excludes>` in the plugin config.

Because Spotless runs *before* compile, the `apply` goal will
update your working tree as a side-effect of `mvn install`. If
that's surprising, switch to `<goal>check</goal>` instead — the
build then fails on unformatted code without rewriting it.

---

## 6. Plugins under `<pluginManagement>` (configuration only)

These set default configuration; children opt in by declaring the
plugin with just `<groupId>+<artifactId>`. Most don't need any extra
config in the child poms — they inherit everything.

### `maven-resources-plugin`

Sets `propertiesEncoding=UTF-8` so `.properties` files are read with
the right charset.

### `license-maven-plugin`

Aggregates third-party license info. Run on demand:
`mvn license:add-third-party` writes `src/license/THIRD-PARTY.txt`
listing the license of every runtime dependency. Excludes in-house
modules via `<excludedGroups>` so they don't show up as "no license
information available".

> The source project also wired up `<licenseUrlReplacements>` here to
> rewrite well-known remote license URLs to local copies. That block
> is intentionally not in the template — add it if you mirror
> licenses locally.

### `maven-compiler-plugin`

Compiles Java. Configured with:
- `debug=true`, `debuglevel=lines,vars,source` — full debug info.
- `failOnWarning=${maven.compiler.failOnWarning}` — turns warnings
  into errors (see also the dev profile which adds `-Xlint:all`).
- `<annotationProcessorPaths>` lists Lombok so `@Value`, `@CustomLog`
  etc. are processed at compile time.

### `maven-surefire-plugin`

Runs unit tests. Three things are wired in `pluginManagement` so every
module inherits them:

- **`argLine`** — `@{argLine} ${mockito.agent}`. The `@{...}` is
  Surefire's late-binding syntax: it resolves the Maven `argLine`
  property at JVM fork time, *after* `jacoco:prepare-agent` has set
  it. Plain `${argLine}` would resolve too early (during model
  interpolation) and bind to empty, silently dropping the JaCoCo
  agent. `${mockito.agent}` is empty by default; modules that use
  Mockito 5+ override the property in their own pom to attach the
  Mockito Java agent (avoids `MockMaker = inline` deprecation
  warnings on JDK 21+).
- **`workingDirectory`** — pinned to
  `${project.build.directory}/test-cwd` (`target/test-cwd/`). The
  forked test JVM's CWD is therefore inside `target/`, not the
  module root. A test that resolves a relative path
  (`new File("foo")`, `Files.write(Path.of("out.txt"), …)`, …) lands
  in the scratch dir and is wiped by `mvn clean` instead of polluting
  the repo checkout. Surefire creates the directory on fork; nothing
  in the build needs to pre-create it.
- **`systemPropertyVariables`** — exposes two anchors so tests that
  legitimately need a real location can read them instead of
  relying on CWD:
  - `projectBasedir` = `${project.basedir}` — the module's own
    directory (resolved per-module at fork time, so each fork sees
    its own value).
  - `repoRoot` = `${maven.multiModuleProjectDirectory}` — the
    directory containing the top-level (reactor) pom.

  Tests read them via `System.getProperty("projectBasedir")` /
  `System.getProperty("repoRoot")`, e.g.
  `Path.of(System.getProperty("repoRoot"), "testdata/fixture.json")`.

### `maven-site-plugin`

Generates the project site (`mvn site`). Wired but not used by
default — included for the optional reporting plugins (`maven-pmd-plugin`,
`maven-project-info-reports-plugin`) to have a target.

### `git-commit-id-maven-plugin`

Writes `git.properties` into every module's output directory at
`initialize`, containing commit id / branch / build timestamp. The
generated file lets the app print its build provenance at runtime
(`Class.getResourceAsStream("/git.properties")`).

### `versions-maven-plugin`

`mvn versions:display-dependency-updates` to see what's outdated.
`ignoredVersions` filters out alpha/beta/RC/M releases so the report
only suggests stable upgrades.

---

## 7. `<dependencyManagement>`

This block declares **versions only** for dependencies that child
modules use. Child poms then reference `<groupId>+<artifactId>` and
inherit the version. Two reasons to centralise:

1. Single-line upgrade — bump Jackson once, everywhere updates.
2. Transitive consistency — if module A depends on Jackson 2.21 and
   module B (via a transitive) pulls Jackson 2.10, dependencyManagement
   pins both at 2.21 to avoid runtime surprises.

This pom declares:

- **In-house module declarations** (`cumba-oss-corej-core`,
  `cumba-oss-corej-report-json`, `cumba-oss-corej-report-xlsx`) so children
  can pull each other in without versioning.
- **`junit-bom` + `mockito-bom`** imported with `<type>pom</type>
  <scope>import</scope>` — BOMs align every JUnit / Mockito artifact
  at one consistent version automatically.
- **Lombok** at `<scope>provided</scope>` — compile-time only, not
  packaged into runtime jars.
- **Jackson** (databind, core, annotations) — included because it's
  one of the most common dependencies; delete if unused.

---

## 8. `<licenses>`, `<organization>`, `<name>`, `<description>`

Inherited metadata that shows up in published poms, the Sonar
dashboard, and the license report. The template uses placeholder
tokens (filled by `setup.sh`).

---

## 9. `<reporting>`

The `<reporting>` section configures plugins that run during
`mvn site`. The template wires up `project-info-reports` and
`maven-pmd-plugin` so `mvn site` produces a static HTML overview if
you ever need one. Not part of the regular build.

---

## 10. The order things execute on `mvn -T1C clean install`

Roughly:

1. `clean` — delete `target/`.
2. `validate` — `maven-enforcer-plugin` checks Maven version.
3. `initialize` — `properties-maven-plugin` loads sonar suppressions
   (aggregator only); `maven-dependency-plugin` resolves dep paths;
   `git-commit-id-maven-plugin` writes `git.properties`; `jacoco`
   attaches its agent.
4. `process-resources` — `flatten-maven-plugin` writes the flattened
   pom.
5. `compile` — `maven-compiler-plugin` (with Lombok processing).
6. `test-compile` — same plugin, test sources.
7. `test` — `maven-surefire-plugin` runs unit tests; `jacoco`
   `report` generates per-module coverage.
8. `package` — `maven-jar-plugin` builds the jar; on client modules,
   `maven-dependency-plugin:copy-dependencies` populates `target/libs/`.
9. `verify` — `spotbugs-maven-plugin` static analysis; `jacoco`
   `check` enforces coverage; in the `coverage/<profile>` aggregator,
   `jacoco:report-aggregate` merges all modules.
10. `install` — copy artifacts to `~/.m2/repository`.

`-T1C` runs that pipeline in parallel, one thread per CPU core.

---

## 11. Files referenced by the parent pom

| File                              | Read by                           |
|-----------------------------------|-----------------------------------|
| `lombok.config`                   | Lombok at compile time            |
| `eclipse-formatter.xml`           | `spotless-maven-plugin` (Java formatter) |
| `pmd-ruleset.xml`                 | `maven-pmd-plugin` (PMD profile)  |
| `spotbugs_project_filter.xml`     | `spotbugs-maven-plugin`           |
| `<module>/spotbugs_ignore.xml`    | `spotbugs-module-ignore` profile  |
| `sonar-exclusions.properties`     | `properties-maven-plugin` → Sonar |
| `coverage/<profile>/target/site/jacoco-aggregate/jacoco.xml` | Sonar (via `sonar.coverage.jacoco.xmlReportPaths`) |

All paths are resolved against `${maven.multiModuleProjectDirectory}`,
which always points at the project root regardless of which
sub-module you're building.
