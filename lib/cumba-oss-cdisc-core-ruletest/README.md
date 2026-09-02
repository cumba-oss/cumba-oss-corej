# corej-cdisc-core-ruletest

Rule-test harness: load `.cdt` fixtures, evaluate a single rule against
them, compare against expected findings. The base building block the `.cdt`
rule-coverage suites use — those live in `lib/corej-cdisc-rules` (they were a
separate module, `corej-cdisc-core-ruletest-suites`, until 2026-09-01).

This module stays separate on purpose: it is a *shipped* harness, consumed in
production by `clients/corej-cdisc-rule-editor-rest`, and it shares no code
with the rule corpus.

## Maven coordinates

```xml
<dependency>
    <groupId>net.cumba.corej</groupId>
    <artifactId>corej-cdisc-core-ruletest</artifactId>
    <version>${corej.version}</version>
</dependency>
```

## Java packages

- `net.cumba.dataviewer.examples.cdt.*` — CDT resource loading,
  scenario capture / trim, junit extension
- `net.cumba.dataviewer.examples.cdt.ruletest.*` — rule-test factory,
  scenario record, resolver

## Dependencies

| Module | Scope | Why |
|---|---|---|
| `corej-datatable` | compile | the table contract |
| `corej-datatable-impl` | compile | concrete tables / overlays |
| `corej-datatable-provider-cdt` | compile | reads `.cdt` fixture files |
| `corej-cdisc-core` | compile | the engine the harness drives |

## Notes

- Ships the dataviewer's rule-test harness source verbatim.
- The `OverlayDataTable` (formerly `TestDataTable`) used by the
  scenario capture lives in `corej-datatable-impl`.
- **Per-module JaCoCo override**: `jacoco.line.coverage` is `0.40` (vs
  the project default of `0.80`) — coverage will rise as more focused
  unit tests of the harness land.

See the root [README](../../README.md) for project-wide context.
