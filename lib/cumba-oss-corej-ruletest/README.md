# cumba-oss-corej-ruletest

Rule-test harness: load `.cdt` scenarios, run one rule against one scenario
and judge the findings against the scenario's expectations. It is the one
implementation both the rule corpus and a rule editor run: the `.cdt`
rule-coverage suites live with the rule corpus, which is distributed
separately from this repository, and their factory maps `ScenarioRunner`'s
result back onto JUnit.

This module stays separate on purpose: it is a *shipped* harness in main
scope — the corpus consumes it at test scope, a rule editor's server in
production — and it shares no code with the rule corpus.

## Maven coordinates

```xml
<dependency>
    <groupId>net.cumba</groupId>
    <artifactId>cumba-oss-corej-ruletest</artifactId>
    <version>0.5.0</version>
</dependency>
```

## Java packages

- `net.cumba.corej.ruletest.cdt` — `.cdt` resource loading (`CdtLoader`; writing goes
  through `net.cumba.datatable.provider.cdt.CdtWriter` directly)
- `net.cumba.corej.ruletest.cdt.ruletest` — the rule-test factory and the
  scenario run:

| class | does |
|---|---|
| `RuleTestCdt`, `RuleTestScenario` | parse a `.cdt` scenario (directives, datasets, expectations) into a scenario record |
| `ScenarioRunner` | one rule × one scenario, judged exactly as the corpus gate judges it: `run` (a parsed scenario), `runText` (the editor's entry: a scenario held as text), `evaluate` / `judge` separately; three outcomes (pass, fail, skip), never an exception for what the run raises |
| `RunEnvironment` | what a run needs from its host: the metadata store `#library-ref` resolves against (the runner never seeds one), the `#dictionaries` bundle, the sidecar resolver |
| `SidecarResolver` | resolves `#library-include` / `#define-include` / `#define-xml` sidecars **confined** to a tree: relative payloads only, no `..` out of the tree, no symbolic link or junction on any component, a regular file opened without following links; messages name paths relative to the tree, never absolute |
| `ScenarioReports` | one run as the `ReportSections` every report writer takes, assembled as a study run's report is |
| `ExecutionObserver` | sees every execution of a run (the corpus records its findings snapshot through it) |
| `ScenarioLibraryResolver`, `LibraryRef`, `LibraryYaml` | a scenario's library metadata from our metadata store (`#library-ref`) or an inline sidecar (`#library-include`) |
| `ScenarioCapture`, `ScenarioResolver`, `ScenarioScopeGate`, `ScenarioRuleSelector` | the scenario's datasets as the engine's dataset resolver, and the scope / rule selection the run applies |

## Dependencies

| Artifact | Scope | Why |
|---|---|---|
| `net.cumba:cumba-oss-datatable` | compile | the table contract |
| `net.cumba:cumba-oss-datatable-impl` | compile | concrete tables / overlays |
| `net.cumba:cumba-oss-datatable-provider-cdt` | compile | reads `.cdt` fixture files |
| `net.cumba:cumba-oss-corej-core` | compile | the engine the harness drives |

JUnit is **test scope only**. This module's main sources carry no JUnit at
all, so a consumer — the corpus's JUnit factory or the editor's server —
gets the harness without JUnit on its classpath.

## Notes

- Originates in the dataviewer — the internal pre-migration Cumba
  codebase — with the Java packages renamed into
  `net.cumba.corej.ruletest.*` for this repository.
- The `OverlayDataTable` (formerly `TestDataTable`) used by the
  scenario capture lives in `cumba-oss-datatable-impl`.
- **Per-module gate overrides**: `jacoco.line.coverage` is `0.75` (vs the
  project default of `0.80`), and pitest runs at coverage `40` / mutation
  `35`. Some of this module's main sources are exercised by callers that
  live with the rule corpus rather than by tests in this module itself, so
  the per-module numbers read lower standalone than the work the code
  actually gets.

See the root [README](../../README.md) for project-wide context.
