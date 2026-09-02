# Adversarial review — batch F (sheet ids 60–62, 95, 96, 100, 101, 205–210, 212–216, 218, 219, 221, 248, 259)

Reviewer: independent stress-test agent (plan §6.2). Scope: the 23 batch-F rule
YAMLs under `src/main/resources/define-conformance-rules/CDISC/`, plus
`BatchFRulesTest.java` and the `batch-f-*.xml` fixtures, plus the deferral audit
for sheet ids 97/98/99/249. Ground truth checked in priority order: the verbatim
`DefineRules` sheet rows (`/data/cdisc-docs/Define-XML_v2.1_Conformance_Rules.xlsx`),
the Define-XML 2.1 spec (`Define-xml-2-1.pdf` — §5.3.14 MethodDef p. 88, §5.3.15
def:CommentDef, §5.3.16/.1 def:leaf/def:title p. 90, §5.3.11.1 Alias p. 78, §5.2
ODM element) cross-read against the 2.0 spec (`Define-xml-2-0.pdf`), the vendored
ODM 1.3.2 XSD (`src/main/resources/xsd/define-2-1/cdisc-odm-1.3.2/ODM1-3-2-foundation.xsd`),
and the engine sources (`RuleEvaluator`, `CheckDefinition`, `Condition`,
`PathResolver`, `ElementNodeBuilder`).

Test run performed in the worktree
(`mvn -P main -pl lib/corej-define-conformance -am test -Dtest=BatchFRulesTest
-Dsurefire.failIfNoSpecifiedTests=false`):
**Tests run: 4, Failures: 0, Errors: 0.** The clean fixture really passes all
23; the violations fixture really fires each rule exactly once; the 2.0-version
run really executes all 23 (no version gating).

## Verdict

**2 rules need changes — both comment/documentation-level (0209, 0096); 0 rules
need behavioral changes.** 21/23 PASS outright. All 23 sheet rows are verbatim
"Both 2.0 and 2.1" in column F (verified cell by cell) — the author's
"no 2.1-only rows in this batch" claim holds; the only 2.1-only row in scope
(249) is deferred. Deferral verdicts: 97/98/99 RIGHT, 249 RIGHT-but-mislabeled
(not actually CT-gated), and none of the four is recorded in
`docs/define-rules-gaps.md` (D1). Three test-suite observations (T1–T3) are
optional hardening, not defects.

## Rule-by-rule table

Columns: (a) fires exactly on the sheet condition, (b) Applicable_Versions,
(c) Source_Type, (d) Message fidelity, (e) selector/anchoring, (f) tests.

| Rule | a | b | c | d | e | f | Evidence (one line) |
|------|---|---|---|---|---|---|---------------------|
| 0060 | PASS | PASS | PASS | PASS | PASS | PASS | `exists TranslatedText` on every `Description`; the sheet's 9 XPaths are exactly the Description-bearing parents, bare scope covers them (plus benign ARM over-scope, N4); fires once on the empty ItemDef Description. |
| 0061 | PASS | PASS | PASS | PASS | PASS | PASS | Sheet duplicate of 60 (same normative text, different Rule Message) — both firing on the same defect mirrors the sheet's own duplication; messages differ (60 "Child element TranslatedText is missing…", 61 "…not found."); the sheet's stray `Decode/TranslatedText` XPath is id 62's own rule (N5). |
| 0062 | PASS | PASS | PASS | PASS | PASS | PASS | `exists TranslatedText` on `Decode`; sheet message carried verbatim ("Codelist/CodeListItem/Decode/TranslatedText not found."); Decode occurs only under CodeListItem in Define-XML. |
| 0095 | PASS | PASS | PASS | PASS | PASS | PASS | The inversion is exact — see the dedicated analysis below; fires once on IG.SUPPAE (Name=SUPPAE ≠ Domain=AE, no Alias), IG.AE (Name==Domain) silent, `${value}` renders "SUPPAE vs AE". |
| 0096 | WARN | PASS | PASS | PASS | PASS | PASS | Dropped multiplicity clause is acceptable-with-documentation (documented in-YAML, and spec p. 78 backs the broader reading) — but the rule is silently inert on 2.0 documents (`def:Context` does not exist in the 2.0 spec at all) and that is undocumented; see F2. |
| 0100 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @Context` on every `Alias`; spec §5.3.11.1 lists exactly the sheet's 5 parents as the only legal Alias XPaths (no MethodDef/Alias in Define-XML — its Child Elements are Description, def:DocumentRef, FormalExpression), so bare scope ≡ sheet scope; Context Required both versions. |
| 0101 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @Name` on every `Alias`, same scope argument as 0100; violations fixture's Name-less CodeList Alias fires it, the Context-less ItemDef Alias does not (each rule exactly once, no cross-masking). |
| 0205 | PASS | PASS | PASS | PASS | PASS | PASS | `cardinality_at_most Description max 1` on `MethodDef`; fires once on MT.6 (two Descriptions). |
| 0206 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @OID` on `MethodDef`; spec p. 88 OID Required; fires once on the OID-less first MethodDef. |
| 0207 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @Name` on `MethodDef`; spec p. 88 Name Required; fires once on MT.2. |
| 0208 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @Type` on `MethodDef`; Source_Type Specification matches the sheet (consistent with the ODM XSD, where `Type` is `use="optional"` — the requirement is Define-XML-spec-level); fires once on MT.3. |
| 0209 | WARN | PASS | PASS | PASS | PASS | PASS | The check itself is verified correct — spec §5.3.14 p. 88 prints Allowable Values "Computation, Imputation" exactly — but the YAML comment's "ODM 1.3.2's five-value MethodDef Type enumeration" is factually wrong: the ODM 1.3.2 XSD `MethodType` enumerates FOUR values (Computation, Imputation, Transpose, Other); see F1. |
| 0210 | PASS | PASS | PASS | PASS | PASS | PASS | `exists Description` on `MethodDef`; spec p. 88 Business Rule "Must contain the child Description element"; fires once on MT.5. |
| 0212 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @OID` on `CommentDef` (prefix-stripped scope); spec §5.3.15 OID Required; fires once on the OID-less first CommentDef. |
| 0213 | PASS | PASS | PASS | PASS | PASS | PASS | `unique_among_siblings` is the right choice: sheet Rule column scopes uniqueness "within Study/MetaDataVersion" and spec §5.3.15 gives def:CommentDef exactly ONE legal XPath (`/ODM/Study/MetaDataVersion/def:CommentDef`) — per-parent ≡ per-MDV, and two MDVs' comment OIDs stay independent where `unique_in_document` would over-fire. |
| 0214 | PASS | PASS | PASS | PASS | PASS | PASS | `exists Description` on `CommentDef`; spec §5.3.15 Business Rule "Must contain the child Description element"; fires once on COM.NODESC. |
| 0215 | PASS | PASS | PASS | PASS | PASS | PASS | `cardinality_at_most Description max 1` on `CommentDef`; fires once on COM.TWODESC. |
| 0216 | PASS | PASS | PASS | PASS | PASS | PASS | `unique_in_document` is the right choice and is spec-verbatim: p. 90 Business Rule "def:leaf ID attributes must be unique within the Define-XML document", and def:leaf legally lives under BOTH MetaDataVersion and ItemGroupDef (§5.3.16) — sibling scope would miss an MDV-leaf/IGD-leaf collision. Fixture does not discriminate the two kinds though (T1). |
| 0218 | PASS | PASS | PASS | PASS | PASS | PASS | `exists title` on `leaf` (prefixes stripped); spec §5.3.16.1 def:title Required, Cardinality One; fires once on LF.NOTITLE. |
| 0219 | PASS | PASS | PASS | PASS | PASS | PASS | `cardinality_at_most title max 1` on `leaf`; spec Cardinality "One"; fires once on LF.TWOTITLE. |
| 0221 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @Context` on `FormalExpression` (only legal parent in Define-XML is MethodDef, matching the hardcoded message); spec §5.3.14.1 Context Required; fires once on MT.4's FormalExpression. |
| 0248 | PASS | PASS | PASS | PASS | PASS | PASS | `exists @href` on `leaf` — `xlink:` prefix stripped by the tree (verified in `ElementNodeBuilder`/`PathResolver.stripPrefix`); spec p. 90 xlink:href Required; the sheet's own XPaths cell drops `/Study` (sheet typo), bare scope is the faithful reading; fires once on LF.NOHREF. |
| 0259 | PASS | PASS | PASS | PASS | PASS | PASS | Pattern `[A-Za-z_][A-Za-z0-9_.-]*` (full-match via `matcher().matches()`) is the sheet AND spec wording verbatim — p. 90 prints exactly "must start with either a letter or underscore (_), and may contain only letters, digits, underscores, hyphens and periods"; it is deliberately stricter than raw NCName (rejects Unicode letters, N1) and correctly rejects colons and leading digits; fires once on `ID="9BAD LEAF"`. |

## 0095 — the inversion, examined (priority item)

Sheet row 95 verbatim: Plain Text "The Alias element is required for each
ItemGroupDef where the Domain attribute value is not the same as the Name
attribute value." / Rule "Alias must be provided for each ItemGroupDef when
@Name is different from @Domain **and @Domain is present**." / Element `Alias`.

The YAML inverts the anchoring (scope `ItemGroupDef`, `compare @Name vs @Domain`,
`when Alias exists:false`). Truth-table against the sheet's violation set
(IGD ∧ Name≠Domain ∧ Domain present ∧ no Alias):

- Name≠Domain, Domain present, no Alias → `compare` unequal → **fires**. ✓
- Name==Domain, no Alias → compare equal → silent. ✓
- Alias present → guard (`exists:false`, element-path form: node-set emptiness
  in `Condition.matchesClause`) excludes the node → silent. ✓
- **Domain absent** → `compare` skips (either side missing ⇒ no finding,
  `RuleEvaluator.compare` lines 418–421) → silent — which is *exactly* the
  sheet's "and @Domain is present" clause, and is also the sheet's intent for
  the plain-text reading: an ItemGroupDef without @Domain (ADaM datasets,
  trial-design-less customs) has no Name/Domain disagreement to speak of.
  The anchoring inversion is necessary (you cannot anchor a finding on a
  missing Alias element) and loses nothing. ✓
- Split/supp datasets: SUPPAE (Name=SUPPAE, Domain=AE) and split names
  (Name=QS36/SUPPAE-like vs Domain=QS) all satisfy Name≠Domain ∧ Domain
  present → fire when Alias-less, which is precisely spec p. 78's "the Alias
  element is required for each ItemGroupDef that represents a split dataset
  or a supplemental qualifiers dataset". ✓

One genuine edge (N2, no change requested): a present-but-**blank**
`Domain=""` is treated as *present* by `compare` (`PathResolver` collects any
present attribute value, blank included) so `Name="AE" vs Domain=""` fires,
whereas the module's presence kinds (`exists`, exists-guards) treat blank as
missing. A blank Domain is schema-degenerate anyway and firing is defensible;
just note the convention split.

## 0096 — the dropped clause, judged (priority item)

Sheet row 96 verbatim: Rule "Alias must be provided for each ItemGroupDef where
there are **multiple ItemGroupDef elements with the same value for @Domain**,
ODM@Context= 'Submisson' and the @Name begins with 'SUPP'." Plain Text: "Alias
is Required for ItemGroupDef elements representing Supplemental Qualifier
datasets in the context of a regulatory submission."

Verdict on the drop: **acceptable-with-documentation — keep declarative, do not
convert to custom or defer.** Reasons:

1. The normative statement (Plain Text, col C) has no multiplicity clause, and
   spec p. 78 backs the broader guard: "the Alias element is required for each
   ItemGroupDef that represents … a supplemental qualifiers dataset" —
   unconditionally, given submission context.
2. Over-fire analysis: the extra firings vs the sheet's col-D shape are
   (a) a SUPP-named IGD whose @Domain is absent, and (b) a SUPP-named IGD whose
   parent-domain IGD is missing from the define. In a `def:Context="Submission"`
   document a `SUPP*`-named ItemGroupDef *is* a Supplemental Qualifier dataset
   by SDTM naming reservation — both cases are datasets the spec's business
   rule genuinely covers, so the "over-fire" flags real violations of the
   plain-text rule. Residual false-positive surface (a submission dataset named
   `SUPP*` that is not a supp qualifier) is negligible.
3. Most of the sheet-shaped cases are independently caught by 0095 anyway
   (SUPPAE: Name≠Domain, Domain present) — 0096's marginal value is precisely
   the Domain-less SUPP case the dropped clause would have excluded.

**F2 (the WARN):** the guard `../../../@Context equals "Submission"` reads the
ODM element's `def:Context` — an attribute that exists **only in Define-XML
2.1** (2.1 spec §5.2 ODM attribute list; a full-text scan of `Define-xml-2-0.pdf`
finds zero occurrences of `def:Context`). With `Applicable_Versions: ["2.0","2.1"]`
(faithful to the sheet's "Both"), the rule executes on 2.0 documents but can
never fire. The contradiction originates in the sheet itself (its own Rule
column references ODM@Context while claiming 2.0 applicability), so the YAML
is not wrong — but the batch's other deliberate deviations are documented
in-YAML and this one is not. Recommended correction: extend the existing YAML
comment with one line, e.g. "def:Context exists only in 2.1; on 2.0 documents
the guard never matches and the rule is inert (sheet-inherited contradiction —
the sheet marks the row 'Both' yet conditions on ODM@Context)." The path
mechanics themselves are correct (IGD → MDV → Study → ODM is exactly three
`..` steps; verified `ElementNodeBuilder` roots the tree at the ODM document
element and stores `def:Context` under the bare key `Context`; the guard's
`equals` uses any-match over resolved values).

## 0209 — the {Computation, Imputation} claim, verified (priority item)

- Spec §5.3.14, PDF page printed "Page 88": MethodDef attribute table, `Type`,
  Usage **Required**, Allowable Values **"Computation, Imputation"** — the
  closed two-value set is confirmed; `one_of` case-sensitive is right (spec
  values are exact tokens), and missing-Type staying with 0208 matches the
  engine's value-kinds-skip-missing semantics.
- Sheet Rule column verbatim: `@Type must be one of "Computation", "Imputation".` ✓
- **F1 (the WARN):** the YAML comment asserts Define-XML "restricts ODM 1.3.2's
  **five-value** MethodDef Type enumeration". The vendored ODM 1.3.2 XSD
  (`ODM1-3-2-foundation.xsd` line 460, `MethodType`) enumerates **four**
  values: Computation, Imputation, Transpose, Other. Comment-only factual
  error; recommended correction: "five-value" → "four-value (Computation,
  Imputation, Transpose, Other)". Same slip in the violations fixture header:
  "0209: MT.4 has Type=\"Derivation\" (valid ODM, not Define-XML)" — `Derivation`
  is not a valid ODM value either (T3). Behavior unaffected in both places.

## 0213 vs 0216 — uniqueness-scope choices (priority item)

Both verified correct, and deliberately different for the right reasons:

- **0213 `unique_among_siblings`:** sheet Rule column: "//def:CommentDef/@OID
  must be unique **within Study/MetaDataVersion**". Spec §5.3.15 gives
  def:CommentDef exactly one legal XPath — a direct MetaDataVersion child —
  so grouping by parent identity (`RuleEvaluator.uniqueAmongSiblings`,
  `seenByParent`) *is* per-MDV scope, and keeps two MetaDataVersions'
  comment OIDs independent (document-wide would over-fire there). A CommentDef
  illegally placed elsewhere would be grouped under its own parent — degraded
  gracefully, and its placement is XSD-pass territory.
- **0216 `unique_in_document`:** sheet: "unique within the set of //def:leaf/@ID
  values"; spec p. 90 Business Rule literally: "def:leaf ID attributes must be
  unique **within the Define-XML document** (i.e., there can be no 2 def:leaf
  elements with the same ID attribute)". def:leaf legally occurs under both
  MetaDataVersion and ItemGroupDef (§5.3.16), so sibling scope would miss the
  MDV-leaf vs IGD-leaf collision — document-wide is the only correct kind.

## Fixture-masking analysis

Traced every scoped element of the violations fixture against all 23 checks:
each planted defect is reachable by exactly one rule, except the deliberate
0060/0061 pair (one empty `Description` fires both — that *is* the sheet's own
duplication, and the test asserts exactly 1 finding for each id, which the
trace confirms: all MethodDef/CommentDef Descriptions carry TranslatedText, so
neither TranslatedText rule double-fires). No masking found in the other
direction either: the OID-less MethodDef carries Name+Type+Description; MT.4
hosts both the bad Type (0209) and the Context-less FormalExpression (0221)
but those are disjoint scopes; the two LF.DUP leaves have valid IDs, hrefs and
single titles; `9BAD LEAF` is unique, titled and href-ed; the Context-less
ItemDef Alias has a Name and the Name-less CodeList Alias has a Context, so
0100/0101 fire once each with no overlap. The `exactly once` assertion is
therefore meaningful for all 23.

## Deferral audit — 97, 98, 99, 249

| Sheet id | Verdict | Reasoning |
|---|---|---|
| 97 | **RIGHT to defer** | Trigger is "a standard variable that **requires CDISC Controlled Terminology according to the standard**" — deciding which variables require CT is external standards knowledge (IG/CT publication), not derivable from the document. Not authorable now. Caveat: this needs a variable→CT-requirement mapping, which is closer to the `Requires: library` shape of the 263/265 *input gaps* than to the term-list `CtProvider` kinds (`term_in_ct_codelist` etc.) — verify at P5 that the planned CT binding actually answers "does this variable require CT", else 97 belongs in the input-gaps table. |
| 98 | **RIGHT to defer** | Same CT-required trigger as 97 plus an `@def:ExtendedValue`-absent guard (the guard part is expressible today; the trigger is not). Plan §3.3's escape-hatch note explicitly anticipates 98 as a custom/CT case. |
| 99 | **RIGHT to defer** | Identical shape to 98 for CodeListItem instead of EnumeratedItem. |
| 249 | **RIGHT to defer, WRONG label** | The operative condition (sheet Rule col, verbatim: "if Codelist/@def:StandardOID is non-null, count(Alias[@Context='nci:ExtCodeID'] = 1") is **entirely intra-document — no CT input is needed** (the def:Standard Name in the message is cosmetic). What actually blocks it is the check grammar: no kind can express "child element with a specific attribute value exists / occurs exactly once" (`exists` has no child-attribute predicate; `cardinality_at_most` cannot count a filtered subset). So it is grammar-gated (custom-check or a new kind), not CT-gated — plan §3.3 indeed lists 249 under the `custom` escape hatch. Deferring it to P5 (where the nci:ExtCodeID-aware machinery lands) is a defensible batching choice, but it should be recorded as "custom/grammar, no `Requires: ct`" and must keep its **2.1-only** version gate (the only 2.1-only row in batch-F scope, sheet col F verbatim "2.1 only"). |

**D1 (documentation gap):** the four deferrals exist only in the
`BatchFRulesTest` javadoc. The batch-A review established the convention of
recording deferrals in `docs/define-rules-gaps.md` (the "Deferred to the P5
CT-gated batch" table currently lists only 132/261/262). Rows for
DEFINE-XML-0097/0098/0099/0249 should be added there — with 249's row noting
"custom/grammar-gated, 2.1-only, CT not actually required".

## Notes (informational, no YAML change)

- **N1 (0259):** the pattern is stricter than the raw `xs:ID`/NCName lexical
  space (NCName admits Unicode letters, combining marks, middle dots; the
  pattern is ASCII-only). This is faithful: the spec's own Allowable-Values
  text (p. 90) states the restricted ASCII rule as the requirement, and the
  sheet copies it verbatim. Colons and leading digits are correctly rejected
  by both NCName and the pattern. A present-but-empty `ID=""` fires (one-char
  minimum) — correct, empty IDs are invalid.
- **N2 (0095):** blank-`Domain=""` edge fires `compare` although the module's
  presence kinds treat blank as missing — see the 0095 section.
- **N3 (0100/0101):** bare `Alias` scope is provably identical to the sheet's
  5-parent XPath list for schema-valid documents (spec §5.3.11.1 legal-XPath
  list; MethodDef has no Alias child in Define-XML); for schema-invalid
  placements, firing on a Context/Name-less stray Alias is harmless-correct.
- **N4 (0060/0061):** bare `Description` scope also covers ARM-extension
  Descriptions (def:AnalysisResults cluster) in documents that use ARM —
  benign over-scope; ARM Descriptions equally require TranslatedText.
- **N5 (0061):** the sheet's XPaths cell for 61 additionally lists
  `CodeList/CodeListItem/Decode/TranslatedText`, but its Plain Text/Rule
  columns scope to Description only; the Decode leg is id 62's whole rule.
  The author's in-YAML note documents the drop. Correct call.
- **N6 (0216 message):** the sheet's Rule Message is malformed
  ("[count(//def:leaf/[@ID]] …"); the YAML's readable adaptation with
  `[${value}]` is an improvement, consistent with batch conventions.

## Test-suite observations (optional hardening)

- **T1 (0216):** both `LF.DUP` leaves are siblings under the same MDV, so the
  fixtures cannot distinguish `unique_in_document` from a (wrong)
  `unique_among_siblings` — either kind would pass both tests. Move one
  duplicate under `ItemGroupDef` (the clean fixture already demonstrates an
  IGD leaf) to lock in the document-wide semantics.
- **T2 (0213):** single-MDV fixture likewise cannot demonstrate the per-MDV
  independence that justifies `unique_among_siblings` over
  `unique_in_document`. Low value (multi-MDV defines are rare); note only.
- **T3 (fixture comment):** "0209: MT.4 has Type=\"Derivation\" (valid ODM, not
  Define-XML)" — `Derivation` is not in ODM 1.3.2's `MethodType` enumeration
  (Computation | Imputation | Transpose | Other). Fix alongside F1.

## Findings requiring changes

- **F1 — DEFINE-XML-0209 (comment-level):** YAML comment "ODM 1.3.2's
  five-value MethodDef Type enumeration" → four-value (Computation, Imputation,
  Transpose, Other). Check logic itself verified correct against spec §5.3.14
  p. 88.
- **F2 — DEFINE-XML-0096 (comment-level):** add an in-YAML note that
  `def:Context` exists only in Define-XML 2.1, so the rule is inert on 2.0
  documents despite the sheet's "Both" (sheet-inherited contradiction).
- **D1 — docs (not a rule file):** add DEFINE-XML-0097/0098/0099/0249 rows to
  the deferred table in `docs/define-rules-gaps.md`; label 249
  custom/grammar-gated (2.1-only), not CT-gated; re-check at P5 whether 97–99's
  variable-level CT-requirement trigger is answerable by the planned
  `CtProvider` binding or belongs with the `Requires: library` input gaps.

**2 rules need changes** (0209, 0096 — both comment/documentation-level; zero
behavioral defects), plus the D1 gaps-doc addition for the four deferrals.
