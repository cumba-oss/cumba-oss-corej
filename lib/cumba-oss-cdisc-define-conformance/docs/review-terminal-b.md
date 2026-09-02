# Terminal B — Independent engine/infrastructure code review

Scope: the hand-written Java engine + infrastructure of `lib/corej-define-conformance`
(tree, path grammar, condition/check model, evaluator, CT kinds, 11 custom checks, XSD
pre-pass, global ordering, engine wiring, CLI), plus the additive `corej-cdisc-define`
changes and the `corej-cdisc-cli` `-vx` promotion. The 394 rule YAMLs themselves were
**not** re-reviewed (11 prior rounds). Adversarial, fresh read.

Baseline: `mvn -P main -pl lib/corej-define-conformance -am test` (39 test classes) — **BUILD
SUCCESS / exit 0**. No `@Disabled`/`FIXME`/`TODO` markers in tests.

---

## Findings (severity-ranked)

### MAJOR

**M1 — A Define-XML 1.0 document is validated against the full 2.1 ruleset and the report
mislabels its version as "2.1".**
`engine/DefineConformanceEngine.java:184-196` (`resolveVersion`) + `:100-149` (`validate`).
`resolveVersion` is a total function: `aOverride` absent → if `aDetected == V2_0` return
`"2.0"`, **else** return `"2.1"` — so a detected `V1_0` yields `"2.1"`. `validate()` has **no
V1_0 short-circuit**: after `DefinePrePass.run` (which *does* special-case 1.0 — returns early
at `DefinePrePass.java:75-78` with only the raw-declaration checks, no XSD), the engine
re-parses the DOM (a 1.0 file is well-formed, so it parses), builds a `DocumentContext` with
version `"2.1"`, and runs every shipped rule whose `Applicable_Versions` contains `"2.1"`.
- Reproducible scenario: feed a define.xml carrying `def:DefineVersion="1.0.0"` (or any 1.0
  namespace). `DefineXmlConverter.detectVersion` returns `V1_0`; `DefinePrePass.Result.version`
  is `V1_0`; but `report.defineVersion()` is `"2.1"` and `report.executions()` is **non-empty**
  (full ruleset ran against a structurally different document).
- Impact: the report label discards the detected 1.0 (misleading), and out-of-scope rules run
  against a document family the validator declares unsupported (plan §2.5), producing spurious
  findings.
- The pre-pass acknowledges 1.0 specially but the engine forgot to — the asymmetry is the tell.
- Fix: in `validate()`, when the pre-pass detected `V1_0` and no override was given, either
  short-circuit rule evaluation (report an out-of-scope status with `defineVersion "1.0"`), or
  at minimum carry the detected `"1.0"` in the report rather than relabelling it `"2.1"`.
- Note: the javadoc on `resolveVersion` *documents* the 2.1 fallback for 1.0 as intended, so
  this may be an accepted tradeoff — **coordinator should confirm intent**; the report
  mislabelling is the concretely wrong part regardless.

### MINOR

**N1 — Value kinds do not skip present-but-blank attribute values, unlike the presence kind and
every CT kind.**
`eval/RuleEvaluator.java`: `regex` (`:218-219`), `oneOf` (`:234-236`), `references` (`:258-259`),
`compare` (via `valuesWithDeref`, empty-list check only). `valueOf` returns `Optional.of("")`
for a present-but-empty attribute; none of these apply `.filter(v -> !v.isBlank())`, whereas
`isPresent` (`:186`) treats blank-as-missing and the CT kinds all filter blank.
- Scenario: an attribute that is both presence-required (an `exists` rule) and format-constrained
  (a `matches_regex`/`one_of` rule), rendered in the file as `Attr=""`. The presence rule fires
  (blank = missing) **and** the format rule fires on `""` (empty fails the pattern / is not in
  the enumerated set) — one defect, two findings, the second arguably the presence rule's beat.
- Fix: apply the same `.filter(v -> !v.isBlank())` in the value kinds that the CT kinds already
  use, so empty attributes are the presence rule's exclusive concern. (Same root cause as N4.)

**N2 — Deref-aware `exists` uses text-presence even when the terminal step is an element.**
`rule/Condition.java:168-170`. The branch selector is
`lastStep.startsWith("@") || clausePath.contains("->")`; any deref path takes the value-based
branch (`resolve(...).anyMatch(v -> !v.isBlank())`), i.e. **text** presence. A non-deref path
takes `!PathResolver.nodes(...).isEmpty()`, i.e. **element** presence.
- Scenario: a future guard `exists: true` on `@Ref->Target@OID/SomeChild` (deref landing on an
  element that has no text) would read as *not-exists* even though the element is present.
- Current corpus is unaffected: every deref-`exists` terminates in an `@Attr` (e.g.
  `@ItemOID->ItemDef@OID/@OID`, which is the idiom for "the reference resolves") or in
  `Description/TranslatedText` text (where text-presence is the intended semantic). Latent trap
  only.
- Fix: for a deref path whose terminal step is an element, decide existence on element presence
  (resolve nodes), not text; or document the restriction that deref-`exists` must terminate in
  an attribute or a text leaf.

**N3 — Per-rule evaluation is not exception-isolated; one throwing rule aborts the whole report.**
`engine/DefineConformanceEngine.java:134-140` calls `evaluator.evaluate(rule, context)` in a
loop with no try/catch. Any exception (e.g. `PathResolver.valuesWithDeref` throwing
`IllegalStateException` on a malformed deref segment `rule/PathResolver.java:126-127`, or
`RuleEvaluator.instantiate` `:745-748` on a missing custom class) propagates out of
`validate()`, contradicting the "best-effort, the pre-pass gates nothing, rules always run"
contract that the unparseable path otherwise honors.
- Trigger probability is low: the throwing paths are all *authoring*/classpath errors, not
  data-triggerable, and the corpus is test-validated — no well-formed document can make a
  shipped rule throw. But it is not defensive.
- Fix: wrap `evaluator.evaluate` in a try/catch that records an errored `RuleExecution` (e.g. a
  new status) and continues, so a single corpus defect cannot suppress the entire report.

**N4 — `unique_in_document` / `unique_among_siblings` treat blank `""` as a real value.**
`eval/RuleEvaluator.java:300-303` / `:278-286`. `valueOf` returns `Optional.of("")`, unfiltered,
so a second present-but-empty attribute is reported as a duplicate. Same blank-handling
inconsistency as N1. Fix: filter blank before the uniqueness set.

**N5 — `RuleEvaluator.customChecks` is a plain `HashMap` mutated at eval time; the engine is not
thread-safe.**
`eval/RuleEvaluator.java:46` + `:405` (`computeIfAbsent`). `DefineConformanceEngine` holds one
`RuleEvaluator` (`:71`) and reuses it across a `validate()` run. Concurrent `validate()` calls on
a **shared** engine instance race on the `HashMap` (resize corruption / potential spin). The CLI
constructs a fresh engine per invocation, so it is unaffected — flag for any server that caches
one engine across threads. Fix: `ConcurrentHashMap`, or document the engine as single-run.

### NIT

**T1 — Document-scoped findings carry `xpath = "/Document"` (the synthetic node).**
`tree/ElementNode.java:168-199` on the `syntheticParent` node → `/Document`. Cosmetic; there is
no real element to point a document-level finding at.

**T2 — `matches_regex` with an explicit `pattern` recompiles the `Pattern` per node/document.**
`eval/RuleEvaluator.java:211-213`. The named-`format` path is cached (`RegexFormats`); the
explicit-pattern path is not. Performance only.

**T3 — `RuleRepository.loadShipped()` re-reads all 394 classpath resources on every engine
construction; no caching.** `rule/RuleRepository.java:42-50`. Performance only.

---

## Informational (out of this effort's scope — for coordinator awareness)

- **`Step20To21.MDV_ORDER` divergence is genuinely pre-existing and correctly surfaced-not-fixed.**
  `git diff main...define-xml-conformance` shows `Step20To21.java` **unchanged** on this branch
  (last touched by commit 42598722, before this effort). Its `MDV_ORDER`
  (`Description, Include, Protocol, Standards, …`) does not match the XSD (Standards precedes
  Include; Description is an MDV *attribute*, not a child) — `GlobalElementOrderingCheck`'s
  javadoc (`ordering/…:102-110`) documents this. It is used only by the **converter**
  (`Step20To21` 2.0→2.1 element reordering), never by the validator, which re-derives its own
  table. So it is out of scope. Latent converter bug (mis-ordered 2.1 output that the validator's
  ordering check would then flag) worth a separate ticket.
- **`pilot-clean-21.xml` `def:Class` as a 2.0-style attribute in a "21" fixture is a fixture
  inaccuracy, not an engine bug.** `SplitDatasets.datasetClass` reads attribute-then-child, and
  the ordering check treats `Class`-as-attribute as "not a child" (never an anchor), so the
  validator handles both dialects. The rule-level tests use `DocumentContext` directly (bypassing
  the XSD pass), so the schema-nonconformance of the fixture does not affect them. Would draw an
  XSD finding if run through the full engine's pre-pass against the 2.1 schema.
- **Backlog item OD0011 (`xmlEncoding`) IS shipped and wired.** `xsd/RawDocumentChecks.java:92`
  emitted by `xsd/DefinePrePass.java:70`. Resolved.

---

## Verified CORRECT (coverage confirmation)

- **`PathResolver.valuesWithDeref` edge cases** (`rule/PathResolver.java:105-178`): deref of a
  missing attribute → `flatMap` no-op → empty → `compare` skips (no false finding); deref to a
  missing target → empty (skip); `..` at path start works; chained derefs
  (`@A->El@K/@B->El2@K2/@C`) walk correctly; a non-terminal deref continues navigation from the
  target; a terminal `@Attr` returns early; the `arrow > 0 && startsWith("@")` guard prevents a
  literal element name containing `->` from being mis-read as a deref. `compare`'s "either side
  missing ⇒ no finding" (`:429-432`) correctly consumes the silent-empty.
- **Gate order** (`eval/RuleEvaluator.java:50-61`): version → CT → folder → scope → guard → kind.
  version-before-Requires means a version-inapplicable CT/folder rule reports
  `NOT_APPLICABLE_VERSION` rather than `SKIPPED_MISSING_CT/FOLDER`. Judged **acceptable**: the
  rule genuinely does not apply to the detected version, so CT/folder availability is moot; no
  masking of a real applicability signal.
- **CT-kind no-op soundness** (`:491-671`): `term_in_ct_codelist`, `nci_alias_required(term)`,
  `term_matches_nci_code`, `extended_value_marking`, and the term leg of `nci_code_known` all
  `continue` (no finding) when the enclosing codelist is unresolvable or the explicit c-code is
  unknown — **never a false positive on missing CT data**. Values are blank-filtered first. The
  one kind that *does* fire on unknown CT data — `nci_code_known(codelist)` (`:547-552`) — is
  correct by design (its whole purpose is to detect c-codes CT does not know), and its contract
  is "provider present ⇒ authoritative". v1 supplies **no** provider (all CT rules SKIP) and the
  CLI deliberately passes `null` (documented at `CdiscValidate.runDefineConformance`), so no
  live false positives.
- **Unparseable-document contract** (`engine/…:100-167`, `xsd/DefinePrePass.java:66-107`):
  traced 0-byte, non-XML, valid-XML-non-ODM, and well-formed-1.0 inputs — none throw.
  `detectVersion` is total/null-safe; `namespaceDeclarations`/`XsdValidator.validate` are never
  reached with an effective `V1_0` (1.0 returns early); a DOM-parse failure yields
  `unparseableReport`, which reuses the pre-pass `PMDA-OD0001`/XSD-category fatal rather than
  double-reporting, appending a synthetic `DEFINE-XML-XSD` only if none exists. `detectVersion`
  is called only on a non-null document (`DefinePrePass.java:73`).
- **JsonReportWriter injection/escaping** (`engine/JsonReportWriter.java`): the tree is built
  with `ObjectNode.put`/`ArrayNode.addObject`, so every string (including `${value}`-rendered
  messages carrying arbitrary define.xml content) is JSON-escaped by Jackson — **no string
  concatenation into JSON anywhere**. Message templating (`RuleEvaluator.render:782-785`) uses
  `String.replace` (literal, not regex), safe against `$`/backslash. Null omission via
  `putIfPresent`; explicit stable field order.
- **Resource/stream leaks**: try-with-resources on every classpath/schema stream
  (`XsdValidator.loadSchema:140`, `ClasspathResourceResolver.resolveResource:216`,
  `RuleRepository.readClasspath:124` and `loadDirectory`'s `Files.walk:57`). `DefinePrePass`
  and the engine use `Files.readAllBytes` (no open stream). No leaks.
- **Static-cache thread-safety**: `XsdValidator.SCHEMAS` is a `ConcurrentHashMap` holding
  thread-safe `Schema` objects (a fresh `Validator` per call); compiled `Pattern`s
  (`RegexFormats`, `Condition`) are immutable; `GlobalElementOrderingCheck.RANKS`/
  `CANONICAL_CHILD_ORDER` are built once into immutable maps. All safe. (The only unsafe shared
  mutable is `RuleEvaluator.customChecks` — see N5.)
- **The 11 custom checks** (`checks/*`): each traced for NPE / wrong-scope / off-by-one /
  over-under-fire. All operands are `Optional`/null-guarded; `SplitDatasets` grouping keys on a
  **non-blank** `Domain` (blank → out of reach); the reverse-membership scans
  (`WhereClauseCrossDatasetCommentCheck`, `VariableLevelOriginCheck`,
  `CrfOriginAnnotatedCrfReferenceCheck`, `ArmParameterOidUsageCheck`) are O(n²) but correct;
  `CrfOriginAnnotatedCrfReferenceCheck` fires on a qualifying Origin with **no** DocumentRef
  (per its documented ownership of that shape); `StandardsCombinationCheck` reports once on the
  synthetic Document node. No defects found.
- **`referenced_file_exists` path-traversal** (`RuleEvaluator.java:360-366`): an absolute or
  `../`-escaping href resolves outside the folder → `!resolved.startsWith(folder.normalize())`
  is true → treated as missing, and `Files.exists` is **short-circuited** (never probes outside
  the submission folder). Safe.
- **`ElementNode`** (`tree/ElementNode.java`): identity `equals`/`hashCode`; `xpath` is
  OID-qualified or positional (1-based among same-named siblings); `children()` preserves true
  source order (duplicates kept); `xmlns` declarations excluded from attributes
  (`ElementNodeBuilder:60-63`); text is `null` when blank; synthetic Document node does not adopt
  children (real xpaths carry no synthetic segment).
- **Load-time validation**: duplicate `Rule_Id` rejected (`RuleRepository.validateCorpus:92-103`);
  `ConformanceRule.validate` + every `CheckDefinition` kind's `validate()` (pattern-xor-format,
  non-empty `values`/`by`, `op`/`mode`/`level` enums, `referenced_file_exists ⇒ Requires:folder`)
  fire on load; the sealed `CheckDefinition` switch is exhaustive.
- **`corej-cdisc-define` changes are purely additive** (verified via diff): new read-only
  accessors (`getWhereClauseDefs`, `getWhereClauseDefByOid`, `getValueListDefByOid`), two new
  bean attribute fields (`MetaDataVersion.commentOID`, `ODM.context`), and `final → public`
  visibility bumps on the read-only `DefineDomUtil`/`DefineDomIo` query helpers. No mutating
  helper was exposed; no existing consumer
  (`DefineXmlPruner`/`DefineXmlConverter`/`OdmDefineXMLProvider`) behaviour changes.
- **CLI integration** (`CdiscValidate.java`): `-vx` promoted from compat-sink to real flag;
  `-vx` without `-dxp` → `UsageException`; `--remote` prints an ignore note; output is
  `<base>.define.json`; CT deliberately `null` (documented rationale); version normalized
  (`2-1`→`2.1`, bogus→`null` auto-detect, never a bogus override).

---

## Verdict

**1 major (M1, confirm-intent), 0 blockers — SHIP** (address M1's report-mislabelling and the
MINORs as fast-follow; none block the release).

---

## Coordinator resolutions (2026-07-03)

Verdict: 0 blockers, 1 major — SHIP. Fixes applied before handback:

- **M1 (MAJOR) FIXED** — `DefineConformanceEngine.validate()` now short-circuits
  a detected Define-XML 1.0 document (unless a version override is given):
  returns the report labelled `"1.0"` with a `DEFINE-XML-UNSUPPORTED` finding
  and an empty execution summary, instead of mislabelling it `"2.1"` and running
  the out-of-scope 2.1 corpus. Pinned by `defineXml10IsOutOfScopeNotMislabelledOrRun`
  + fixture `engine-define-10.xml`.
- **N5 (MINOR) FIXED** — `RuleEvaluator.customChecks` is now a
  `ConcurrentHashMap` (the engine is documented reusable; custom-check
  instances are stateless), removing the concurrent-`validate()` footgun.

### Deferred as documented fast-follow (reviewer concurred none block release)
- **N1 / N4** — value/unique kinds treat a present-but-blank `Attr=""` as a
  value (double-reports alongside the presence rule rather than under-reporting).
  A blank-skip alignment touches value-kind semantics corpus-wide and every
  batch's count assertions; deferred to avoid destabilising the 11-times-
  reviewed rule behaviour at handback. Not a false-negative.
- **N2** — deref-aware `exists` uses text-presence on a non-terminal element
  step; latent only (no shipped rule terminates a guard on a bare element).
- **N3** — per-rule evaluation isn't exception-isolated; honest fix needs a new
  `ExecutionStatus.ERRORED` (+ report-summary plumbing). Throws are
  authoring/classpath, never data-driven, and the full corpus validates
  clean — deferred rather than widen the enum at handback.
- NITs (document-scope xpath `/Document`, explicit-pattern recompile,
  `loadShipped()` re-read per engine) — cosmetic/perf, no correctness impact.
