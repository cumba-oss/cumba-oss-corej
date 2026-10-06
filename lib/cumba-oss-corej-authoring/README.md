# cumba-oss-corej-authoring

The rule **authoring** API: everything a tool needs to understand an *authored* rule — the
split storage of the rule corpus (a check YAML, an optional docs sidecar, the package standards
map) — in engine main scope, so that the corpus gate and a rule editor run **one implementation**.

The product never loads authored YAML: the packaged JSON is the engine's input contract for every
shipped surface. That is why this is a module of its own and not part of `cumba-oss-corej-core`.

## What is here

| class | does |
|---|---|
| `AuthoredRuleAssembler` | check YAML + docs sidecar → the **canonical** rule node (full `Authorities` join) and the **packaged** node (the released shape, byte-equal to the rule's entry in a generated `rules-*.json`), loaded through `RulePackageLoader`; defects come back as diagnostics, never exceptions |
| `AssembledRule` | the result: `coreId`, the loaded `Rule` (or `null` when parked / unloadable), both nodes, the diagnostics |
| `RuleDiagnostic` | code · severity · message · YAML path · text range |
| `LoadErrorDiagnostics` | the engine's `"; "`-joined `loadError` / `loadWarning` channel as diagnostics, with the key path the loader names |
| `YamlLocations` | where each YAML path sits in the authored text |
| `RulePackageConverter`, `ReleaseShapeTrimmer` | the carry-through conversion and the release shaping the corpus generator runs |
| `PackageStandardsMap`, `RuleFamily` | the packaging-time standards map (`rules-src/package-standards.json`, a corpus **input** resolved by the caller) and the family of a `Core.Id` |
| `AuthoringProvenance` | the engine commit this jar was built from (`git.properties`), so a corpus regeneration can refuse a stale engine |
| `RuleInsight` | the static analysis of one loaded rule: `derived` (Sensitivity, the `OutputVariableListing`, Authorities, evaluation domain, wildcard names), `referencedVariables` (every name the rule names and where — quoted `var_exists` targets included, `--` forms kept), `scopeUniverse` (the datasets of the rule's standards its `Scope` admits, judged with the production `ScopeMatcher` over our metadata store, plus the variable names to complete from), `related` |
| `CorpusIndex` | what `related` looks up in: the corpus's own authority references, plus the equivalence register and the minted-rule parentage where the repository carries them |
| `FunctionHelp` | function help read from the rule guide's `functions.md`: per `### name` section the signature, the summary, the parameter table and the named sub-sections; the corpus's `FunctionReferenceParameterTableTest` holds the tables to the engine's descriptors |

## Maven coordinates

```xml
<dependency>
    <groupId>net.cumba</groupId>
    <artifactId>cumba-oss-corej-authoring</artifactId>
    <version>${cumba-oss-corej.version}</version>
</dependency>
```

## Dependencies

| Module | Scope | Why |
|---|---|---|
| `cumba-oss-corej-core` | compile | the loader, the classifier, the rule model |
| `jackson-dataformat-yaml` | compile | the authored corpus is YAML (the engine's own contract stays JSON) |

No JUnit on the main path.

## ⚠ The corpus bytes depend on this jar

`AuthoredRuleAssembler` is what the corpus's `RuleStorageAssembler` runs, so a corpus regenerated
against a stale installed engine can differ from the same YAML regenerated against the current
one. `RuleCorpusGenerator` prints the commit `AuthoringProvenance` reports and, with
`-Dcorej.authoring.expectedCommit=<sha>`, refuses a mismatch before writing a file. Install the
engine before regenerating.
