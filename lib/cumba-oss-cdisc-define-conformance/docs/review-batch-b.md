# Adversarial review — batch B (sheet ids 38, 39, 42, 43, 45–51, 54)

Reviewer: independent stress-test agent (plan §6.2). Scope: the 12 batch-B rule
YAMLs under `src/main/resources/define-conformance-rules/CDISC/`, plus
`BatchBRulesTest.java` and the `batch-b-*.xml` fixtures. Ground truth checked in
priority order: the verbatim `DefineRules` sheet rows
(`/data/cdisc-docs/Define-XML_v2.1_Conformance_Rules.xlsx`), the Define-XML 2.1
spec (`Define-xml-2-1.pdf`, §§4.6, 5.3.7–5.3.8) cross-read against the 2.0 spec
(`Define-xml-2-0.pdf`, §5.3.6.1), and the engine sources
(`RuleEvaluator`, `CheckDefinition`, `Condition`, `PathResolver`,
`OidResolver`, `ElementNodeBuilder`).

Test run performed in the worktree
(`mvn -P main -pl lib/corej-define-conformance -am test -Dtest=BatchBRulesTest
-Dsurefire.failIfNoSpecifiedTests=false` — the extra flag is needed because
`-am` builds upstream modules that have no matching test class):
**Tests run: 4, Failures: 0, Errors: 0, BUILD SUCCESS.** The clean fixture
really passes all 12; the violations fixture really fires each rule exactly
once.

## Verdict

**0 rules need changes.** 12/12 PASS on firing condition, versions, source
type, message, and anchoring. Three informational notes (N1–N3 below) are
deliberate-or-unavoidable deviations worth recording; none requires a YAML
edit. Two test-suite observations (T1–T2) are optional hardening, not defects.

## Rule-by-rule table

Columns: (a) fires exactly on the sheet condition, (b) Applicable_Versions,
(c) Source_Type, (d) Message fidelity, (e) selector/anchoring, (f) tests.

| Rule | a | b | c | d | e | f | Evidence (one line) |
|------|---|---|---|---|---|---|---------------------|
| 0038 | PASS | PASS | PASS | PASS | PASS | PASS | `cardinality_at_most` max 1 anchored on `MetaDataVersion`; sheet "for the MetaDataVersion element"; spec 5.3.7 cardinality "Zero or One" per MDV; fires once on the double-AnnotatedCRF MDV. |
| 0039 | PASS | PASS | PASS | PASS | PASS | PASS | `exists DocumentRef` scoped on `AnnotatedCRF`; spec 5.3.7.1 "Required for def:AnnotatedCRF" (both 2.0 §5.3.6.1 and 2.1); fires once on the empty second AnnotatedCRF. |
| 0042 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @leafID` on every `DocumentRef` document-wide; sheet Plain Text is context-free ("for the def:DocumentRef element") and leafID is Required in all five spec contexts in both versions; the sheet XPaths cell lists only the SupplementalDoc path but no sibling row (37/40/41 do not exist) covers the other contexts, so document-wide is the only faithful reading. |
| 0043 | PASS | PASS | PASS | PASS (N1) | PASS | PASS | `references leafID → leaf@ID` document-wide; sheet F column verbatim "2.1 only" → `["2.1"]`; resolution proven by the live test (dangling `LF.MISSING` fires, valid `LF.ACRF` resolves against `def:leaf` — the tree keys bare local names, `ElementNodeBuilder` strips prefixes, `OidResolver` indexes `leaf`/`ID`). |
| 0045 | PASS | PASS | PASS | PASS (N2) | PASS | PASS | `one_of Type ∈ {PhysicalRef, NamedDestination}` on every `PDFPageRef` (all three sheet XPaths are PDFPageRef parents; PDFPageRef occurs nowhere else); value-kind skips missing Type so no cross-fire with 0046. |
| 0046 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @Type` on `PDFPageRef`; spec: Type Required (both versions); fires once on the Type-less MethodDef PDFPageRef. |
| 0047 | PASS | PASS | PASS | PASS | PASS | PASS | `matches_regex format integer` (`[+-]?\d+` = xs:integer lexical space) on present FirstPage only; `FirstPage="one"` fires once. |
| 0048 | PASS | PASS | PASS | PASS | PASS (N3) | PASS | `exists @FirstPage` guarded `@PageRefs exists:false ∧ @Type equals PhysicalRef`, guard read from the scoped PDFPageRef itself; matches sheet "when PageRefs is missing and Type is PhysicalRef" and spec "Required if PageRefs is not provided"; Source_Type Specification verbatim. |
| 0049 | PASS | PASS | PASS | PASS | PASS | PASS | Mirror of 0047 for LastPage; `LastPage="last"` fires once. |
| 0050 | PASS | PASS | PASS | PASS | PASS (N3) | PASS | Mirror of 0048 for LastPage; Source_Type Specification verbatim. |
| 0051 | PASS | PASS | PASS | PASS | PASS | PASS | Mirror of 0038 for SupplementalDoc; spec 5.3.8 cardinality "Zero or One" per MDV; fires once on the double-SupplementalDoc MDV. |
| 0054 | PASS | PASS | PASS | PASS | PASS | PASS | `exists DocumentRef` scoped on `SupplementalDoc` — correctly follows the sheet's Plain Text/Element columns and ignores the sheet's XPaths cell, which is a copy-paste error (it repeats the AnnotatedCRF path `/ODM/Study/MetaDataVersion/def:AnnotatedCRF/def:DocumentRef`). |

## Answers to the directed questions

**(g) 0038/0051 — per MetaDataVersion, per Study, or per document?**
Per MetaDataVersion is correct. Both the sheet ("No more than one child element
… must be provided **for the MetaDataVersion element**") and spec 5.3.7/5.3.8
(Element XPath `/ODM/Study/MetaDataVersion/def:AnnotatedCRF`, "Cardinality:
Zero or One") scope the limit to one MDV. A hypothetical document with two
MDVs, each holding one AnnotatedCRF, is schema-valid and correctly does not
fire. The bare `MetaDataVersion` selector matches every MDV document-wide,
which is exactly the right anchoring; the check counts children of each scoped
MDV.

**(h) 0043 — version scoping and `targetElement: "leaf"`.**
The sheet's F cell reads verbatim `2.1 only`, so `Applicable_Versions: ["2.1"]`
is faithful (even though the same referential constraint logically holds in
2.0 — the sheet is the ground truth, and the batch does not invent rules).
`references` with `targetElement: "leaf"` does match `def:leaf`:
`ElementNodeBuilder` keys elements and attributes by bare local name (prefixes
are a namespace matter), and `OidResolver` indexes `node.localName()` →
attribute name → value, so `resolve("leaf", "ID", …)` hits `def:leaf ID="…"`.
No scratch run was needed — the executed test suite is the proof: in
`batch-b-violations-21.xml`, `leafID="LF.MISSING"` produced exactly one 0043
finding while three `leafID="LF.ACRF"`/valid references produced none, and the
message assertion (`contains("LF.MISSING")`) passed.

**(a) 2.0-vs-2.1 attribute differences for DocumentRef/PDFPageRef.**
None are missed. Cross-reading the 2.0 spec §5.3.6.1 against 2.1 §5.3.7.1:
`DocumentRef` carries the same single required `leafID` in both;
`PDFPageRef` carries `Type, PageRefs, FirstPage, LastPage` in 2.0 and adds only
the optional `Title` in 2.1 — no renames, no moved requirements affecting these
rules, so the shared `["2.0","2.1"]` definitions need no version split. (The
2.0/2.1 difference that does exist — Origin `Type="CRF"` vs `"Collected"` —
belongs to sheet id 44, which is deliberately outside this batch.)

**(f) Test adequacy.** The violations fixture isolates each rule on a distinct
node/aspect: 0038 fires on the MDV, 0039 on the second (empty) AnnotatedCRF,
0042 on the SupplementalDoc DocumentRef (no leafID — which 0043 then skips,
value-kinds firing only on present values), 0043 on the Origin DocumentRef
(`LF.MISSING`), 0045/0047/0049 on the Origin PDFPageRef
(`Type="Physical"`, `FirstPage="one"`, `LastPage="last"`), 0046 on the second
MethodDef PDFPageRef (no Type, `PageRefs="5"` also de-scopes it from
0048/0050), 0048/0050 on the first MethodDef PDFPageRef
(`Type="PhysicalRef"`, no PageRefs, no First/LastPage), 0051 on the MDV, 0054
on the second (empty) SupplementalDoc. `violationsFixtureFiresEachRuleExactlyOnce`
asserts count == 1 per rule, so no broken element can mask another rule's
assertion, and the exactly-once discipline also proves the non-firing of every
other candidate node per rule. The clean fixture exercises DocumentRef under
four of its five legal parents and both PDFPageRef shapes, and the version-gate
test proves 0043 (and only 0043) skips as NOT_APPLICABLE_VERSION on a 2.0
document. Verified by execution: 4/4 tests pass.

## Findings (informational notes — no YAML change required)

### N1 — 0043 message deliberately repairs a sheet typo
Sheet Rule Message (verbatim, including the typo and double space):
`def:leaf element corresponding to def:DocumentRef/@leafD  is missing.`
YAML: `def:leaf element corresponding to def:DocumentRef/leafID [${value}] is
missing.` The author fixed `@leafD` → `leafID` and injected the offending value
where it belongs. This is the right call (repeating the typo would be
noise-faithful, not meaning-faithful) but it is a conscious deviation from
byte-verbatim and should stay on record here.

### N2 — 0045 message drops the sheet's second placeholder
Sheet: `The value of Type[@Type] is not one of "PhysicalRef",
"NamedDestination" for def:DocumentRef[@leafID]/def:PDFPageRef.`
YAML: `The value of Type[${value}] is not one of "PhysicalRef",
"NamedDestination" for def:DocumentRef/def:PDFPageRef.`
The sheet templates two dynamic values (`@Type` and the parent DocumentRef's
`@leafID`); the engine renders a single `${value}` (the offending value —
placed correctly on `Type[…]`). Dropping the un-renderable `[@leafID]`
qualifier rather than printing it as misleading literal text is the sane
resolution; the finding's xpath carries the location instead. Acceptable as
authored; revisit only if multi-placeholder templating is ever added.

### N3 — 0048/0050 guard treats a *blank* PageRefs as "provided"
Engine nuance, not an authoring error: check-kind presence (`exists`,
`RuleEvaluator.isPresent`) treats a present-but-blank attribute as missing,
but the when-guard's `exists` clause resolves through
`PathResolver.values`, where `PageRefs=""` contributes a value — so
`@PageRefs exists:false` is false and 0048/0050 stay silent on
`<def:PDFPageRef Type="PhysicalRef" PageRefs=""/>` with no FirstPage/LastPage.
Under the sheet's literal wording ("when PageRefs is missing") a blank-but-
present PageRefs is not "missing", so the current behaviour is defensible;
under the codebase's own blank-is-missing presence convention it under-fires
on that one degenerate shape. No expressible YAML fix exists today (the guard
mini-language has no blank-aware predicate). Flagging for the engine backlog:
decide whether guard `exists` should share the check-kind blank semantics.

## Test-suite observations (optional hardening, not defects)

### T1 — Only three rules assert *where* the finding landed
`findingCarriesLocationAndRenderedMessage` pins message/xpath for 0043, 0045,
0047 only. For the other nine, `violationsFixtureFiresEachRuleExactlyOnce`
proves the count but not the node — e.g. if 0042 someday fired on the wrong
DocumentRef while the right one went quiet, count == 1 would still hold. The
single-violation-per-rule fixture design makes this a small residual risk, but
one xpath assertion per rule would close it.

### T2 — def:CommentDef context never exercised
Neither fixture contains `def:CommentDef/def:DocumentRef/def:PDFPageRef` — the
fifth legal DocumentRef parent and third PDFPageRef parent from the sheet's
XPaths column. The bare selectors provably cover it by construction (selector
matching is parent-agnostic), so this is fixture breadth, not correctness.

## Sheet-data glitches encountered (for the record, no action)

- Row 43 XPaths cell reads `/ODM/Study/MetaDataVersion/ItemGroupDef` — clearly
  unrelated to DocumentRef; the Plain Text Rule ("within the Define-XML
  document") is what the YAML correctly implements.
- Row 54 XPaths cell repeats row 39's AnnotatedCRF path; Element and Plain
  Text columns (SupplementalDoc) are what the YAML correctly implements.
- Rows 37, 40, 41, 52, 53 do not exist in the sheet, confirming 0042/0043 are
  the sheet's only leafID rules and must cover all DocumentRef contexts.
