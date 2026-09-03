# Cumba OSS coreJ

The CDISC conformance engine of the Cumba OSS stack — a rule-package loader, check
evaluator and operation executor, a pluggable report-writer SPI with JSON and XLSX
writers, a `.cdt` rule-test harness, and a Define-XML conformance validator.

Licensed under the **GNU Affero General Public License v3.0 only**
(see [`LICENSE`](LICENSE)).

## Provenance

Every module in this repository is **original work**. No module is derived from,
ported from, or adapted from third-party source, so no upstream attribution or
licence-retention obligation applies to the code here.

Third-party code is consumed only as ordinary **Maven dependencies** (Jackson, Apache
POI, commons-compress, pyrolite, Lombok, JSpecify); those carry no obligation beyond
not misrepresenting them.

> Two points of possible confusion. The `cumba-oss-cdisc-core-ruletest` module's Java
> package is `net.cumba.dataviewer.examples.cdt` — "dataviewer" is the internal
> pre-migration Cumba codebase this source came from, not an external project. And the
> engine implements the **CDISC CORE conformance rules**; the rule content itself is
> not in this repository (see below).

Depends on **`cumba-oss-commons`** and **`cumba-oss-datatable`**. Both must be on the
classpath first; they are released separately to Maven Central.

## Modules

| Module | Java package | Purpose |
|---|---|---|
| [`cumba-oss-cdisc-core`](lib/cumba-oss-cdisc-core/README.md) | `net.cumba.cdisc.core` | The engine: rule-package loader, check evaluator, operation executor, report assembler, and the pluggable report-writer SPI. Ships **no** report writer of its own. |
| `cumba-oss-cdisc-report-json` | `net.cumba.cdisc.core.report.json` | Registers two formats with the writer SPI — `json` (frozen v1 schema) and `json-2` (combined-finding v2). |
| `cumba-oss-cdisc-report-xlsx` | `net.cumba.cdisc.core.report.xlsx` | Registers the `xlsx` format. The only module that puts Apache POI's writer path on the engine classpath, so an engine without it validates normally and simply offers no Excel output. |
| [`cumba-oss-cdisc-core-ruletest`](lib/cumba-oss-cdisc-core-ruletest/README.md) | `net.cumba.dataviewer.examples.cdt` | Rule-test harness: load `.cdt` fixtures, evaluate a single rule against them, compare against expected findings. Ships no JUnit on its main path. |
| `cumba-oss-cdisc-define-conformance` | `net.cumba.cdisc.define.conformance` | Define-XML conformance validator implementing the CDISC Define-XML v2.1 Conformance Rules and the PMDA Validation Rules v6.0 Define-XML sheet. |

## What is not in this repository

- **The rule corpus.** The authored CDISC/FDA/PMDA rule packages are distributed
  separately — the engine loads them at runtime from a rules directory. The engine and
  the content release on different schedules, which is why they are separate.
- **The clients.** The validation CLI and the REST API live in `cumba-oss-clients`.

## The Cumba OSS repositories

```
cumba-oss-commons     help · web-api · cdisc-library · bootstrap
      ▲
cumba-oss-formats     sas-utils · datasetjson              (independent leaf)
      ▲
cumba-oss-datatable   datatable · impl · cdisc-define · providers · manager-local · testkit
      ▲
cumba-oss-corej       cdisc-core · report-json · report-xlsx · ruletest · define-conformance
      ▲
cumba-oss-clients     cdisc-cli · cdisc-rest              (publishes nothing to Central)
```

Dependencies run in one direction only. Build order is `cumba-oss-commons` →
`cumba-oss-formats` → `cumba-oss-datatable` → `cumba-oss-corej`.

## Quick start

```bash
mvn -T1C clean install
```

Artifacts are published under groupId `net.cumba` with the module's artifactId:

```xml
<dependency>
    <groupId>net.cumba</groupId>
    <artifactId>cumba-oss-cdisc-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

To produce a report you also need a writer on the classpath — add
`cumba-oss-cdisc-report-json` and/or `cumba-oss-cdisc-report-xlsx`. Without one the
engine validates but can write nothing, and says so by name.

## The CI gate

Every static-analysis check runs by default but is **report-only**; CI flips each one to
fail-on-finding. This is the gate that must pass before a tag:

```bash
mvn -B clean install -Drevision=<version> \
    -Dmaven.compiler.failOnWarning=true -Dspotless.check=true \
    -Dpmd.failOnViolation=true -Dspotbugs.failOnError=true
```

| Property | Default | When `true` |
|---|---|---|
| `-Dspotbugs.failOnError=true` | `false` | SpotBugs findings fail the build |
| `-Dpmd.failOnViolation=true` | `false` | PMD findings fail the build |
| `-Dspotless.check=true` | `false` | Spotless switches to `check` — unformatted files fail rather than being rewritten |
| `-Dmaven.compiler.failOnWarning=true` | `false` | any javac warning fails the build |
| `-Dpitest.failOnError=true` | `false` | promotes each module's `pitest.*.target` to the effective threshold (no-op without `-P Pitest`) |

Opt-outs: `-DskipPmd`, `-DskipSpotbugs`, `-Dnullaway.disabled`. Pitest is opt-in with
`-P Pitest` / `-Dpitest.enabled=true`.

⚠ **Do not pass `-Djacoco.line.coverage=…` or `-Dpitest.mutation.threshold=…` on the
command line.** A CLI `-D` is a Maven *user* property and clobbers every module's
deliberate per-module override at once. Set the per-module property in that module's
pom instead.

## Build conventions

- **Java 25**, `<revision>` + `flatten-maven-plugin` for CI-friendly versioning.
- **Javadoc is a build gate.** A `javadoc-no-fork` execution runs at `package` with
  `failOnError=true`. It was added after three CI failures in one day that were
  invisible to compile, tests, Spotless, PMD and SpotBugs, and surfaced only at publish
  — after a tag was spent. All three were `{@link #someLombokGeneratedGetter()}`, which
  javadoc cannot resolve because it parses source rather than post-processor output.
  Use `{@code getX()}` for Lombok-generated members.
- **NullAway** in JSpecify mode runs at `ERROR` over `net.cumba` (active unless
  `-Dnullaway.disabled`). It is the complete nullness checker for this codebase — see
  `spotbugs_project_filter.xml` for why SpotBugs' partial JSpecify support is filtered
  out rather than duplicated, and for the callee classification that must be re-run on
  every SpotBugs bump.
- **Spotless** reformats in place at `process-sources`, so `mvn install` modifies your
  working tree; CI gates with `-Dspotless.check=true` to fail rather than rewrite.
- **SpotBugs** layers `spotbugs_project_filter.xml` (always) over each module's
  `spotbugs_ignore.xml` (auto-activated when present).
- **Surefire test-CWD isolation.** The forked test JVM's working directory is pinned to
  `target/test-cwd/`, so a test resolving a relative path pollutes `target/` rather than
  the checkout. Tests needing a real root read `projectBasedir` or `repoRoot` from
  system properties Surefire sets per fork.
- **JaCoCo** enforces a per-module line-coverage minimum, `<jacoco.line.coverage>`,
  default `0.80`.

## Adding a new module

1. Create a directory under `lib/`, named exactly as the artifactId.
2. Add a `pom.xml` whose `<parent>` points at this root pom.
3. Add the directory to the top-level `<modules>` list.
4. Add a `<dependency>` for it in the parent `<dependencyManagement>`.
5. Add a `<dependency>` for it in `coverage/pom.xml` — that list *is* the aggregate
   coverage report, and the `coverage-covers-every-module` enforcer fails the build if
   you forget.
6. **Give the pom `<name>`, `<description>`, `<url>`, `<packaging>` and
   `<inceptionYear>`.** These are published to Maven Central. `<name>` is *not*
   inherited from the parent and Central rejects an artifact without one — and it fails
   at publish time, after the tag is spent.
