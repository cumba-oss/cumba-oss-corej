# cumba-oss-corej-core

The CDISC validation rule engine: rule-package loader, check evaluator,
operation executor, JSON report writer. The heart of the project.

## Maven coordinates

```xml
<dependency>
    <groupId>net.cumba</groupId>
    <artifactId>cumba-oss-corej-core</artifactId>
    <version>0.2.0</version>
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

- Ships the dataviewer's `net.cumba.corej.core.*` source verbatim.
- Tests run with `workingDirectory = ${project.basedir}` so harness code
  can resolve `rules/rules-*.json` and `rulespec/specs/` via
  relative paths.
- Bundles the **spec harness** — every YAML spec under
  `rulespec/specs/` runs through the engine and its result is
  compared against the spec's `expected_violations` (which violations),
  `expected_status` (whether the rule ran at all — `EXECUTED` /
  `SKIPPED` / `ERROR`, absent meaning `EXECUTED`) **and**
  `expected_errors` (*why* it failed, as classified reason tokens
  rather than engine prose). Known divergences are listed in
  `documentation/parity-diff-baseline.json` and inverted to XFAIL by
  the harness, per channel (`channels: ["violations", "status",
  "errors"]`); every channel an entry claims must still diverge, or the
  entry is reported as rotted.
- The spec suite (`RuleExecutionSpecTest`; named `RuleExecutionParityTest`
  until the wave-41 rename) runs in the default build. The Python lane it
  was once compared against was removed in wave 33 — the `PyParity`
  profile no longer exists in any `pom.xml`.
- Some parity tests need a Python-engine **pickle metadata cache**. Point
  `CDISC_PICKLE_CACHE_DIR` (or `-Dcdisc.pickle.cache.dir`) at one; the
  standard location on this project's hosts is
  `/data/cdisc.metadata.library-cache-pkl`. Without it those tests skip,
  and `PickleProviderGuardTest` fails loudly rather than let specs
  silently flip `EXECUTED` → `SKIPPED`.

## Layout of test-only artifacts

⚠ These live with the **rule corpus**, which is distributed separately from
this repository — not here. The spec harness moved out of the engine, and on
2026-09-01 the module that held it was folded into the rules module, alongside
the rule corpus its specs resolve against.

- `rulespec/specs/` — 1048 YAML rule-execution specs
- ⛔ `cdisc-rules-engine/` — the vendored Python fork, **removed** in
  stage 4 phase 4 (last pinned at `195c7172`,
  `v0.16.0-hf3-19-g195c7172`). Its `resources/cache` used to be one
  source of the pickle metadata cache above; that cache must now be
  provisioned, as described above
- `documentation/parity-diff-baseline.json` — lane-aware XFAIL list;
  `lane: python` entries are historical (see the file's own `_comment`)

See the root [README](../../README.md) for project-wide context.
