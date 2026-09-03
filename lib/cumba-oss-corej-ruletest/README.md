# cumba-oss-corej-ruletest

Rule-test harness: load `.cdt` fixtures, evaluate a single rule against
them, compare against expected findings. The base building block the `.cdt`
rule-coverage suites use — those suites live with the rule corpus, which is
distributed separately from this repository.

This module stays separate on purpose: it is a *shipped* harness, consumed in
production by downstream services, and it shares no code with the rule corpus.

## Maven coordinates

```xml
<dependency>
    <groupId>net.cumba</groupId>
    <artifactId>cumba-oss-corej-ruletest</artifactId>
    <version>0.2.0</version>
</dependency>
```

## Java packages

- `net.cumba.corej.ruletest.cdt.*` — CDT resource loading,
  scenario capture, junit extension
- `net.cumba.corej.ruletest.cdt.ruletest.*` — rule-test factory,
  scenario record, resolver

## Dependencies

| Artifact | Scope | Why |
|---|---|---|
| `net.cumba:cumba-oss-datatable` | compile | the table contract |
| `net.cumba:cumba-oss-datatable-impl` | compile | concrete tables / overlays |
| `net.cumba:cumba-oss-datatable-provider-cdt` | compile | reads `.cdt` fixture files |
| `net.cumba:cumba-oss-corej-core` | compile | the engine the harness drives |

JUnit is **test scope only**. This module's main sources carry no JUnit at
all, so the three classes consumers actually import — `RuleTestScenario`,
`RuleTestCdt` and `CdtLoader` — arrive without JUnit on their classpath.

## Notes

- Ships the dataviewer's rule-test harness source verbatim.
- The `OverlayDataTable` (formerly `TestDataTable`) used by the
  scenario capture lives in `cumba-oss-datatable-impl`.
- **Per-module gate overrides**: `jacoco.line.coverage` is `0.75` (vs the
  project default of `0.80`), and pitest runs at coverage `40` / mutation
  `35`. Some of this module's main sources are exercised by callers that
  live with the rule corpus rather than by tests in this module itself, so
  the per-module numbers read lower standalone than the work the code
  actually gets.

See the root [README](../../README.md) for project-wide context.
