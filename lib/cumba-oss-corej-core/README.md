# cumba-oss-corej-core

The CDISC validation rule engine: rule-package loader, check evaluator,
operation executor, report assembler and the pluggable report-writer SPI.
The heart of the project. It ships **no** report writer of its own — add
`cumba-oss-corej-report-json` and/or `cumba-oss-corej-report-xlsx` to get
output.

## Maven coordinates

```xml
<dependency>
    <groupId>net.cumba</groupId>
    <artifactId>cumba-oss-corej-core</artifactId>
    <version>0.3.0</version>
</dependency>
```

## Java packages

- `net.cumba.corej.core.*` — the engine root (rule executor, check
  evaluator, exec subsystem, gen / metadata / report / parity helpers)

## Dependencies

| Artifact | Scope | Why |
|---|---|---|
| `net.cumba:cumba-oss-datatable` | compile | the table / SPI contract the engine evaluates against |
| `net.cumba:cumba-oss-datatable-impl` | compile | concrete table / buffer / view / index impls |
| `net.cumba:cumba-oss-cdisc-library` | compile | rule-package loading (REST + DTO model) |
| `net.cumba:cumba-oss-web-api` | compile | transitively needed by `cumba-oss-cdisc-library` |
| `net.cumba:cumba-oss-cdisc-define` | compile | Define-XML object model used by the define-level checks |

## Notes

- Originates in the dataviewer — the internal pre-migration Cumba
  codebase — with the Java packages renamed into `net.cumba.corej.core.*`
  for this repository; nothing else about the source was changed.
- ⚠ Tests run with `workingDirectory = ${project.basedir}`, overriding the
  project-wide `target/test-cwd/`, because the dictionary tests
  (`dictionaries/`) and the source censuses (`src/main/java`, sibling
  modules) resolve via module-relative paths (the dataviewer convention
  these tests originated under).
- The engine tests carry **no copy of corpus rules**. The generated
  `src/test/resources/fixtures/rules` tree was retired by
  `PLAN-engine-fixture-join-key-drift` (2026-09-28): an engine test is
  carried by a small rule written inline for the mechanism it tests, and
  rule verdicts live in the corpus's own `.cdt` scenarios.
- The **spec harness** is no longer in this module — it moved to
  `cumba-oss-corej-rules` on 2026-09-01, alongside the corpus its specs
  resolve against. There, every YAML spec under `rulespec/specs/` runs
  through the engine and its result is compared against the spec's
  `expected_violations` (which violations), `expected_status` (whether
  the rule ran at all — `EXECUTED` / `SKIPPED` / `ERROR`, absent meaning
  `EXECUTED`) **and** `expected_errors` (*why* it failed, as classified
  reason tokens rather than engine prose). Every spec asserts normally: the
  baseline XFAIL inversion was removed with Python parity (2026-09-17), and
  the baseline file (`parity-diff-baseline.json`) itself on 2026-09-20 — it is
  in git history.
- The spec suite (`RuleExecutionSpecTest`; named `RuleExecutionParityTest`
  until the wave-41 rename) runs in `cumba-oss-corej-rules`' default
  build. The Python lane it was once compared against was removed in
  wave 33 — the `PyParity` profile no longer exists in any `pom.xml`.
- Some tests here need a Python-engine **pickle metadata cache**. Point
  `CDISC_PICKLE_CACHE_DIR` (or `-Dcdisc.pickle.cache.dir`) at one; the cache
  is not part of this repository and has no location it can be assumed to
  occupy. Without it **this module's**
  pickle tests skip (`assumeTrue`). ⚠ In `cumba-oss-corej-rules` the
  cache is mandatory instead: `PickleProviderGuardTest` fails loudly
  there rather than let specs silently flip `EXECUTED` → `SKIPPED`.

## Layout of test-only artifacts

⚠ These live with the **rule corpus**, which is distributed separately from
this repository — not here. The spec harness moved out of the engine, and on
2026-09-01 the module that held it was folded into the rules module, alongside
the rule corpus its specs resolve against.

- `rulespec/specs/` — the YAML rule-execution specs (531 in the public
  `cumba-oss-corej-rules`; the internal corpus carries more)
- ⛔ `cdisc-rules-engine/` — the vendored Python fork, **removed** in
  stage 4 phase 4 (last pinned at `195c7172`,
  `v0.16.0-hf3-19-g195c7172`). Its `resources/cache` used to be one
  source of the pickle metadata cache above; that cache must now be
  provisioned, as described above

See the root [README](../../README.md) for project-wide context.
