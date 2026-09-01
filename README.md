# Maven multi-module project template

A starting point for new Java + Maven projects. Modeled on a working
multi-module build with a strict CI gate (failOnWarning, SpotBugs,
JaCoCo coverage threshold, optional PMD / Error Prone / ECJ).

## Quick start

```bash
# 1. Copy the template, then from the new project root:
./setup.sh --prompt
# (or pass values as flags: ./setup.sh --group-id com.example.myproject ...)

# 2. Build
mvn -T1C clean install
```

`setup.sh` replaces every `__PLACEHOLDER__` token in the tree with
project-specific values and derives `net/cumba` automatically.
Delete the script afterwards if you don't need it again.

For a plugin-by-plugin walkthrough of `pom.xml` (what each piece
does, when it runs, why it's there), see [`POM.md`](POM.md).

## Putting the project under version control

The template ships without a `.git/` directory so it doesn't pollute
your history with template-side commits. After running `setup.sh`,
initialise a fresh repository and push it to your forge of choice:

```bash
# 1. Initialise locally and make the first commit.
git init -b main
git add .
git commit -m "Initial commit from maven.template"

# 2. Create an empty repository on the remote first (web UI is fine).
#    Then wire it up — pick one URL form:

#    SSH:
git remote add origin git@github.com:<user>/<repo>.git
#    HTTPS:
git remote add origin https://github.com/<user>/<repo>.git

# 3. Push.
git push -u origin main
```

CLI-driven repository creation (no web UI needed):

```bash
# GitHub via the gh CLI
gh repo create <user>/<repo> --private --source=. --remote=origin --push

# Gitea via tea (https://gitea.com/gitea/tea)
tea repos create --name <repo> --private
git remote add origin <url-printed-by-tea>
git push -u origin main
```

Notes:

- The shipped `.gitignore` already excludes `target/`, IDE metadata,
  `.flattened-pom.xml`, OS cruft, and `**/.env`. Verify
  `git status --ignored` before the first commit if you want to
  confirm nothing sensitive is staged.
- There are **two** CI workflows, and they are deliberately not the same
  file. Both trigger on pushes to `main` / `master` and on `vX.Y.Z[.W]`
  tags:
  - `.gitea/workflows/main.yml` — internal Gitea. Runs Sonar and the
    Nexus deploy, which target internal hosts.
  - `.github/workflows/ci.yml` — public GitHub. Runs the Maven Central
    publish and cuts the GitHub release; no Sonar, no Nexus.

  ⚠ **Do not merge them or copy action pins between them.** Their
  artifact-action versions are *opposite* on purpose: Gitea's act_runner
  implements the GHES-style artifact API and rejects `upload-artifact@v4+`
  with `GHESNotSupportedError`, so that file is pinned to `@v3`; on GitHub
  `@v3` is retired, so that file tracks the latest major. Neither pin is
  portable.
- If you keep `setup.sh` in the repo for reproducibility, the first
  commit will contain it. Delete it and amend the commit if you'd
  rather not ship the substitution script.

## Layout

```
.                         # parent pom + project-wide configs
├── pom.xml               # parent pom — replace placeholder tokens
├── POM.md                # narrated walkthrough of the parent pom
├── CLAUDE.md             # working rules for Claude Code agents
├── setup.sh              # one-shot placeholder substitution
├── lombok.config         # Lombok project config (System.Logger)
├── eclipse-formatter.xml # Eclipse JDT formatter profile (driven by Spotless)
├── pmd-ruleset.xml       # PMD ruleset for the opt-in PMD profile
├── spotbugs_project_filter.xml
├── sonar-exclusions.properties
├── .gitattributes
├── .gitignore
├── .gitea/workflows/main.yml    # internal CI (build + Sonar + Nexus deploy)
├── .github/workflows/ci.yml    # public CI (build + Maven Central + release)
│
├── lib/                              # library modules
│   ├── cumba-oss-cdisc-core/         # CORE rules engine
│   ├── cumba-oss-cdisc-report-json/  # JSON report writer
│   └── cumba-oss-cdisc-report-xlsx/  # XLSX report writer
│
├── coverage/                     # single JaCoCo aggregate-report sink
│
├── plans/                        # design / planning docs (Markdown)
│
└── code_reviews/                 # findings / review notes (Markdown)
```

## Build profiles

| Profile             | Active when               | Purpose                                       |
|---------------------|---------------------------|-----------------------------------------------|
| `PMD`               | unless `-DskipPmd`        | runs PMD at `verify`, **report-only** by default |
| `SpotBugs`          | unless `-DskipSpotbugs`   | runs SpotBugs at `verify`, **report-only** by default |
| `Pitest`            | `-P Pitest` or `-Dpitest.enabled=true` | **opt-in** mutation testing at `verify`, **report-only** by default |
| `ecj`               | `-P ecj`                  | second ECJ compile at `verify`                |
| `ErrPrn`            | `-P ErrPrn`               | Error Prone as a javac plugin                 |
| `spotless-check-mode` | `-Dspotless.check=true` | swaps Spotless from `apply` to `check`        |
| `pitest-fail-on-error` | `-Dpitest.failOnError=true` | promotes per-module `pitest.*.target` to the effective threshold (no-op without `Pitest` active) |
| `spotbugs-module-ignore` | per-module file `spotbugs_ignore.xml` exists | layers per-module SpotBugs filter |

## Static-analysis fail toggles

Every always-on check (Spotless, SpotBugs, PMD, Error Prone) runs
by default but produces a **report only** — they do not block the
build on findings. Pitest is **opt-in** (mutation testing is too slow
for inner-loop builds) and likewise report-only once activated. The
CI gate is opt-in:

| Property                       | Default | When set to `true`                                |
|--------------------------------|---------|---------------------------------------------------|
| `-Dspotbugs.failOnError=true`  | `false` | SpotBugs findings fail the build                  |
| `-Dpmd.failOnViolation=true`   | `false` | PMD findings fail the build                       |
| `-Derrprn.failOnWarning=true`  | `false` | Error Prone findings fail the build (requires `-P ErrPrn`) |
| `-Dspotless.check=true`        | `false` | Spotless switches to `check` mode; unformatted files fail the build (does not rewrite) |
| `-Dpitest.failOnError=true`    | `false` | Pitest mutation/coverage/test-strength thresholds are promoted from each module's `pitest.*.target` and enforced (requires the Pitest profile — see below) |

And the disable / opt-in switches:

| Property                    | Effect                                            |
|-----------------------------|---------------------------------------------------|
| `-DskipPmd`                 | skip the PMD profile entirely                     |
| `-DskipSpotbugs`            | skip the SpotBugs profile entirely                |
| `-Dpitest.enabled=true`     | opt in to the Pitest profile (equivalent to `-P Pitest`) |
| `-Derrprn.extraArgs=...`    | append args to Error Prone, e.g. enable NullAway   |

> **Pitest opt-in:** the `Pitest` profile is dormant by default.
> Activate it with `-P Pitest` (manual profile selection) or
> `-Dpitest.enabled=true` (property activation). `-Dpitest.failOnError=true`
> is a no-op on its own — it only promotes the thresholds inside the
> `pitest-fail-on-error` sub-profile, and without `Pitest` active the
> plugin doesn't run, so no thresholds are evaluated. Always combine,
> e.g. `mvn -P Pitest verify -Dpitest.failOnError=true`.

> **Pitest threshold gotcha:** do **not** pass
> `-Dpitest.mutation.threshold=…` on the command line — same trap as
> `-Djacoco.line.coverage`: a CLI `-D` clobbers every per-module
> override at once. Tune per-module by setting
> `<pitest.mutation.target>` (and `<pitest.coverage.target>`,
> `<pitest.test.strength.target>`) in the module's `pom.xml`, then
> let `-Dpitest.failOnError=true` promote them.

> **Fixed (was: "always pass `-P dev` or `-P main`").** The reactor used to
> live in two `activeByDefault` profiles, `dev` and `main`, carrying identical
> module lists. Maven deactivates *every* `activeByDefault` profile the moment
> any other profile activates — and `PMD`, `SpotBugs` and `NullAway`
> self-activate unconditionally via `!property` rules — so the profile was never
> active. Measured: `mvn validate` with no `-P` built the parent pom **alone**
> (1 module) while `-P main` built 5. CI escaped it only by always passing
> `-P main`, exercising a configuration no developer ever ran.
>
> `<modules>` is now declared at **top level**, where profile activation cannot
> switch it off, and the strict `-Xlint:all` that `dev` carried (and which had
> therefore never run — verified clean before adopting it) is unconditional.
> No `-P` is needed for anything; a stale `-P main` is only a warning.

## Build commands

```bash
mvn -T1C clean install                            # dev profile (default), report-only checks
mvn -T1C test                                     # all tests
mvn -T1C verify -Dspotless.check=true      # CI: verify formatting without rewriting
mvn -T1C verify -Dspotbugs.failOnError=true -Dpmd.failOnViolation=true   # CI: hard gate
mvn -T1C -P Pitest verify                     # opt in to pitest, report-only
mvn -T1C -P Pitest verify -Dpitest.failOnError=true  # CI: pitest + enforce mutation/coverage targets
mvn -T1C verify -DskipPmd -DskipSpotbugs   # quick build, no static analysis (pitest already off)
mvn -T1C initialize sonar:sonar                   # SonarQube (initialize is required
                                                  # so sonar-exclusions.properties loads)
```

Standalone `mvn sonar:sonar` does **not** trigger `initialize`, so the
suppression file never loads — always invoke as
`mvn -T1C initialize sonar:sonar` (or any lifecycle command that
already includes the `initialize` phase, e.g.
`mvn -T1C verify sonar:sonar`).

## Conventions baked into the template

- **Java 25** (set via `<java.version>` and `maven.compiler.release`).
- **`<revision>` + `flatten-maven-plugin`** for CI-friendly versioning.
- **Lombok** as compile-time annotation processor; `@CustomLog`
  injects a `java.lang.System.Logger` field named `LOGGER` (see
  `lombok.config`).
- **Strict lint:** `failOnWarning=true` plus `-Xlint:all` in the `dev`
  profile makes any javac warning a build failure.
- **Spotless** reformats Java sources in-place at `process-sources`
  (before compile) using the Eclipse JDT formatter and
  `eclipse-formatter.xml`. Imports are sorted, unused imports
  removed, trailing whitespace stripped. `mvn install` modifies your
  working tree as a side-effect; CI gates with `-Dspotless.check=true`
  to fail rather than rewrite.
- **SpotBugs** runs at `verify`, layered with
  `spotbugs_project_filter.xml` (always) plus the module's
  `spotbugs_ignore.xml` (auto-activated when present). **Report-only
  by default**; CI flips with `-Dspotbugs.failOnError=true`.
- **Surefire test-CWD isolation.** The forked test JVM's working
  directory is pinned to `${project.build.directory}/test-cwd` (i.e.
  `target/test-cwd/`). A test that resolves a relative path
  (`new File("foo")`, `Files.write(Path.of("out.txt"), …)`, …) lands
  inside `target/` and gets wiped by `mvn clean` instead of polluting
  the repo checkout. Tests that legitimately need the module root or
  the multi-module root read them from system properties Surefire
  exposes per fork: `System.getProperty("projectBasedir")` (the
  module's `${project.basedir}`) and `System.getProperty("repoRoot")`
  (`${maven.multiModuleProjectDirectory}`, i.e. the reactor root).
- **JaCoCo** enforces a per-module line-coverage minimum.
  `<jacoco.line.coverage>` defaults to `0.80` (80%). Override
  per-module by setting the property in the module's pom, or globally
  on the CLI with `-Djacoco.line.coverage=0.0`. Greenfield projects
  typically start at 0 and raise the bar as the test suite matures.
- **Pitest** mutation testing is **opt-in** (`-P Pitest` or
  `-Dpitest.enabled=true`) because mutation analysis is too slow for
  the inner loop. Once active it runs at `verify`, report-only by
  default; pair the opt-in with `-Dpitest.failOnError=true` to
  promote each module's `pitest.mutation.target` /
  `pitest.coverage.target` to the effective thresholds. Incremental
  analysis is enabled via the OSS `io.github.mibimiflo:pitest-history`
  SPI plugin (pitest 1.17+ removed its built-in OSS history reader);
  per-module history is written to
  `.pitest-history/<artifactId>/history.bin` at the **repo root**
  (outside any module's `target/`, so `mvn clean` does not wipe it),
  and the CI workflow caches the directory so warm-cache runs reuse it.
- **License aggregation** via `license-maven-plugin`. Run
  `mvn license:add-third-party` to generate `src/license/THIRD-PARTY.txt`.
  The plugin is wired into `pluginManagement` but no licenseUrl
  rewrites are configured by default — add them as needed.

## Adding a new module

1. Create a directory under `lib/` or `clients/`.
2. Add a `pom.xml` with `<parent>` pointing at this root pom.
3. Add the directory to the top-level `<modules>` list.
4. Add a `<dependency>` entry for it in the parent `<dependencyManagement>`.
5. Add a `<dependency>` for it in `coverage/pom.xml` — that list *is* the
   aggregate coverage report. The `coverage-covers-every-module` enforcer rule
   fails the build if you forget.
