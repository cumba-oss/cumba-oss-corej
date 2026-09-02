# Batch A adversarial review (plan §6.2)

Reviewer: independent stress-test agent (did not author the batch).
Scope: the 39 batch-A rule YAMLs (`DEFINE-XML-0003…0264` per the batch list),
`BatchARulesTest.java`, `batch-a-*.xml` fixtures, and the batch's 4 deferrals
(132, 261, 262, 263).

Ground truth used: `/data/cdisc-docs/Define-XML_v2.1_Conformance_Rules.xlsx`
sheet `DefineRules` (all 43 scoped rows re-extracted verbatim via openpyxl),
`/data/cdisc-docs/Define-xml-2-1.pdf` (§4.1.1 pp. 17–19, §5.3.5/§5.3.6
pp. 65–67, §5.3.11.2/§5.3.11.2.1 pp. 79–80), and the engine sources
(`PathResolver`, `Condition`, `CheckDefinition`, `RuleEvaluator`,
`RegexFormats`, `ElementNodeBuilder`, `DocumentContext`).

Test run: `mvn -P main -pl lib/corej-define-conformance -am test
-Dtest='BatchARulesTest' -Dsurefire.failIfNoSpecifiedTests=false` —
**5/5 tests pass** (surefire: `tests="5" errors="0" failures="0"`).
Note: the command as given in the plan/prompt (without
`-Dsurefire.failIfNoSpecifiedTests=false`) fails on upstream `-am` modules
("No tests matching pattern"); the flag is required.

## Rule-by-rule verdicts

| Rule | Verdict | Evidence (one line) |
|---|---|---|
| 0003 | PASS | cardinality_at_most ODM≤1 on Document scope; sheet-verbatim text/versions/source; unreachable on well-formed XML (fixture comment acknowledges), same as pilot 0002. |
| 0005 | PASS | iso8601-datetime full-match; fixture `20260701T000000` fires, clean `…T00:00:00Z` passes; message maps sheet `[@AsOfDateTime]`→`[${value}]`. See note N1 (lexical-only ISO). |
| 0007 | PASS | one_of {1.3.2}; fires on `1.3.0`; sheet's stray "element ." space normalised; value-kind skips absent ODMVersion (presence is sheet 6, pilot batch). |
| 0008 | PASS | one_of {Snapshot}; blank `FileType=""` is a *present* value → fires (count 1 verified); message adds `[${value}]` not in sheet (note N2). |
| 0009 | PASS | exists @FileOID; blank-counts-missing engine convention; message verbatim. |
| 0010 | PASS | exists Study on ODM; fires on the bare 2.0 fixture (dedicated test); message verbatim. |
| 0012 | PASS | exists @OID on Study; third `<Study/>` fires once; message verbatim. |
| 0013 | PASS | exists @CreationDateTime; blank counts missing (fires on `CreationDateTime=""`); message verbatim. |
| 0014 | PASS | exists GlobalVariables on Study; message verbatim. |
| 0015 | PASS | cardinality GV≤1; ST.B's two GVs fire once; message verbatim. |
| 0016 | WARN | Rule content correct (exists @Context, 2.1-only, verbatim); but **no test ever makes 0016 fire** — violations-21 keeps def:Context (needed for 0031/0131) and the 2.0 fixture version-skips it. Test gap, see F3. |
| 0018 | PASS | exists StudyName; message verbatim. |
| 0019 | PASS | cardinality StudyName≤1; message verbatim. |
| 0020 | PASS | exists StudyDescription; message verbatim. |
| 0021 | PASS | cardinality StudyDescription≤1; message verbatim. |
| 0022 | PASS | exists ProtocolName; message verbatim. |
| 0023 | PASS | cardinality ProtocolName≤1; message verbatim. |
| 0024 | PASS | exists MetaDataVersion on Study; sheet Source_Type Specification ✓; message verbatim. |
| 0025 | PASS | cardinality MDV≤1; Specification ✓; message verbatim. |
| 0026 | PASS | exists @OID on MDV; message verbatim. |
| 0027 | PASS | exists @Name on MDV; message verbatim. |
| 0028 | PASS | Regex verified empirically (full-match `matches()`): `2.0.0`✓ `2.1.0`✓ `2.1.34`✓; `2.0.0X`✗ `X2.0.0`✗ `2.1`✗ `2.0.1`✗ `2.1.3.4`✗ — alternation is safe under full-match, no anchoring hole. Sheet's message typo (`MetaDataVersion@def:DefineVersion]`, missing `[`) reasonably normalised. Note N4: `2.0.0` branch never exercised by a test. |
| 0029 | PASS | exists @DefineVersion; MDV.B1 fires; message verbatim. |
| 0030 | PASS | references CommentDef@OID via document-wide index — matches sheet rule D (`//MetaDataVersion/@def:CommentOID`, document-wide). Note N3: spec §5.3.5 business rule is stricter ("same MetaDataVersion"); sheet (priority 1) wins. Message reworded `[@Name]`→CommentOID `[${value}]` (engine renders only ${value}); faithful in substance. |
| 0031 | WARN | Guard `../../@Context == Submission` is **correct**: PathResolver steps MDV→(..)Study→(..)ODM, then reads @Context (def: prefix stripped by ElementNodeBuilder, so `def:Context` resolves). Fires 2× on MDV.B1/B2 as expected; spec §5.3.6 confirms "Required when def:Context='Submission'". WARN is for the tests: no fixture has Context≠Submission, so **deleting the guard entirely would still pass the whole suite** (see F2). |
| 0032 | PASS | cardinality Standards≤1; spec §5.3.6 "Cardinality: Exactly One" ✓; 2.1-only per sheet ✓. |
| 0033 | PASS | Sheet column F really says **"Both 2.0 and 2.1"** — YAML `["2.0","2.1"]` matches verbatim (vacuous in 2.0, where def:Standard doesn't exist; faithful-to-sheet is the right call). Message drops sheet's `[@OID]` (engine exists-kind has no value to render); note N5. |
| 0034 | PASS | 2.1-only ✓ (sheet F "2.1 only"); exists @Type; message drops `[@Name]` (N5). |
| 0035 | PASS | Guard `@Type == CT` matches BOTH the sheet ("when @Type=\"CT\"") and spec §5.3.6.1 verbatim: PublishingSet "Conditionally required when Type=\"CT\" / Not applicable for other standard types". Clean fixture proves no false positive on Type="IG" without PublishingSet (a broken always-true guard would fail cleanFixture + the exact count 1). The "not applicable for other types" converse (PublishingSet present on non-CT) is a different rule, not sheet row 35 — correctly not implemented here. |
| 0036 | PASS | 2.1-only ✓ per sheet (schema-wise Version is required in 2.1); exists @Version; message drops `[@Name]` (N5). |
| 0131 | WARN | Guard `../../../@Context` is **correct**: ItemGroupDef→MDV→Study→ODM = exactly three `..` steps, then @Context — verified against PathResolver's step grammar. Fires once (IG.DM) as expected. WARN: (a) spec pp. 17/79 exempts datasets with `def:IsNonStandard` ("the def:Class element will not be used" for non-ADaM analysis datasets; business rule "if def:IsNonStandard is used, def:Class should not be provided") — the sheet-verbatim guard will false-positive on such datasets in a Submission file; (b) same guard-deletion test blindness as 0031 (F2). |
| 0134 | PASS | exists @Name on SubClass, 2.1-only ✓; message drops sheet's `[@Name]` placeholders (N5). |
| 0233 | PASS | exists Standard on Standards; Source_Type **Schema** verbatim per its sheet row; message verbatim (sheet's double space "at least one  def:Standard" normalised). See F1 (duplicate-row handling with 0258). |
| 0254 | WARN | matches_regex(iso8601-datetime) fires on blank `""` (present value) and malformed values, but a truly **absent** CreationDateTime never fires this rule (value-kinds skip absent) while the sheet row 254 says "must be provided… missing or invalid". The missing case is delegated to 0013 (its own sheet row), so the defect is still detected — but 0254's own message text "is missing or invalid" over-promises. Recommend: keep the split but reword to "is not a valid ISO 8601 datetime" or record the 0013-delegation in a YAML comment. Also N1. |
| 0255 | PASS | exists @FileType; blank counts missing → fires on `FileType=""` alongside 0008's value check; distinct sheet rows (8 = value, 255 = presence), correct pairing, message verbatim (sheet's trailing `_x000D_` artifact rightly dropped). |
| 0256 | PASS | references CommentDef@OID; dangling COM.GONE fires; same document-wide-vs-same-MDV spec note as 0030 (N3); message reworded like 0030. |
| 0257 | PASS | exists @OID, 2.1-only ✓ per sheet (OID is required by the 2.1 schema; row is 2.1-only); message drops `[@Name]` (N5). |
| 0258 | PASS | exists Standard on Standards; Source_Type **Specification** and its own distinct message, both verbatim per its sheet row. See F1. |
| 0264 | PASS | one_of {Draft, Final, Provisional} — matches the spec exactly: §4.1.1 (p. 18) prints the Status allowable values as Draft / Provisional / Final (default Final), and §5.3.6.1 (p. 67) marks the Status codelist "**not extensible**". Sheet message with `(C-Code C172332)` reproduced verbatim, `[@Status]`→`[${value}]`. Absent Status correctly skipped (spec: optional, default Final). Caveat N6: no local copy of the published Define-XML CT exists to verify C172332's *current* term list (spec printed the values pre-publication, C-code "TBD"); re-verify against the live CT when the `Requires: ct` machinery lands — if NCI ever adds a term (e.g. "Withdrawn"), this inline list drifts. Nothing in the spec supports Withdrawn/Superseded today. |

Totals: 35 PASS, 4 WARN (0016, 0031, 0131, 0254), 0 FAIL.

## Detailed findings

### F1 — duplicate sheet rows 233/258 (and 13/254, 8/255): handled correctly
Sheet rows 196 (id 233) and 218 (id 258) are the *same* requirement published
twice with different Source Type (Schema vs Specification) and different
messages. The two YAMLs mirror that split exactly (0233: Schema + "must have at
least one def:Standard child element"; 0258: Specification + "Child element
def:Standard is missing…"). They **do** double-report one empty
`<def:Standards/>` (tests pin 1 finding each) — which is faithful to the sheet
duplicating the row, and matches the report's per-rule attribution model. No
change needed. The 13/254 (presence vs validity) and 8/255 (value vs presence)
pairs are complementary rather than duplicates and are split correctly.

### F2 — when-guards are untestable-by-deletion in the current fixtures (0031, 0131)
Every fixture that is evaluated as 2.1 has `def:Context="Submission"` (clean
and violations both), and the only non-Submission document (violations-20) is
evaluated as 2.0, where 0031/0131 are version-skipped. Consequence: a rule
variant with the `when:` guard **removed entirely** produces identical results
across all five tests. The under-fire direction *is* covered (a mis-stepped
`../..`-count would resolve nothing, the guard would go false, and the pinned
counts 2/1 would fail), but the over-fire direction is not.
Recommendation: add a small fixture (or a third variant of an existing one)
with `def:Context="Other"`, a MetaDataVersion without `def:Standards`, and an
ItemGroupDef without `def:Class`, asserting **zero** findings for 0031/0131.

### F3 — 0016 never fires in any test
`def:Context` is present in both 2.1 fixtures (required so 0031/0131 can fire)
and 0016 is version-skipped on the 2.0 fixture. The single-fixture-per-batch
design makes this a genuine conflict (documented in the test javadoc), but a
one-element fixture (`<ODM …>` without def:Context, evaluated as "2.1") would
close it cheaply. Same suggested fixture as F2 could carry it (drop
def:Context there and expect 0016:1, 0031:0/0131:0 — which also kills two
birds: guard-absence and 0016 firing).

### F4 — 0254 message vs value-kind semantics (WARN detail)
Sheet 254: "A valid CreationDateTime attribute must be provided" / message
"The ODM CreationDateTime attribue[sic] is missing or invalid." The YAML keeps
"missing or invalid" but implements only the validity half (regex fires on
present values — including blank `""`, which the fixture exercises); a fully
absent attribute is silent here and caught by 0013 instead. Net detection
across the pair is complete; per-rule attribution differs from the sheet row.
Recommend a message tweak or an explicit delegation comment in the YAML.

### F5 — 0131 spec carve-out for def:IsNonStandard (WARN detail)
Spec p. 17: for non-ADaM analysis datasets "the def:Class element will not be
used"; p. 79 business rule: "if the ItemGroupDef def:IsNonStandard attribute
is used, the def:Class should not be provided." A Submission define.xml
containing such a dataset (legitimately Class-less) is flagged by 0131 as
authored. The sheet row (priority-1 ground truth) contains no exemption, so
the YAML is sheet-faithful — but the false positive is real. Options: (a) keep
sheet-verbatim and record the decision, or (b) extend the guard to
`all: [ {path: "../../../@Context", equals: "Submission"}, {not: {path: "@IsNonStandard", equals: "Yes"}} ]`
(expressible in the existing when-grammar). Needs an author/owner decision;
do not silently change.

### Notes (no action required)
- **N1** — `iso8601-datetime` is lexical only: `2026-13-99T99:99:99` full-matches
  the canned pattern (verified empirically). A month-13 value is "not in ISO
  8601 datetime format" per the sheet text, so 0005/0254 under-detect
  semantically impossible datetimes. Engine-wide format convention; flagging
  for the record, not per-rule.
- **N2** — 0008 message adds `[${value}]` where the sheet has none ("The value
  of ODM/@FileType must be \"Snapshot\"."). Benign, improves the finding.
- **N3** — 0030/0256 resolve def:CommentOID against the document-wide
  (element,key,value) index, exactly as the sheet's `//`-anchored rule text
  says; the spec's business rule is stricter ("same MetaDataVersion"). Only
  observable with multiple Studies/MDVs. Sheet wins per ground-truth priority.
- **N4** — 0028's `2.0.0` alternation branch is never exercised by a test (no
  MDV exists in the 2.0 fixture). The branch is regex-verified here; a 2.0
  fixture MDV would pin it.
- **N5** — Several exists-kind messages drop the sheet's identity placeholders
  (`def:Standard[@Name]`, `ItemGroupDef[@Name]/def:Class[@Name]`, `[@OID]`):
  0033, 0034, 0035, 0036, 0131, 0134, 0257. The engine's renderer only
  substitutes `${value}`, and exists findings carry no value; the finding's
  XPath (`…/Standard[@OID='STD.1']`) supplies the identity instead. Systematic
  and acceptable; noting for transparency.
- **N6** — 0264's inline list is justified by the spec's "not extensible"
  statement + printed 3-term table, but should be re-verified against the
  published Define-XML CT (C172332) once a CT artifact is available; no local
  copy exists in /data/cdisc-docs to check today.
- **N7** — Metadata conventions vs sheet columns I/J: the YAML `Element` is the
  *scope* (parent) for exists/cardinality rules where the sheet's Element
  column sometimes names the child (e.g. sheet row 10 says "Study", YAML
  scopes ODM; sheet row 258 says "def:Standard", YAML scopes Standards) — the
  sheet itself is inconsistent (row 233 names the parent). YAML `Attribute`
  drops the `def:` prefix (Context, DefineVersion, CommentOID) matching the
  namespace-agnostic tree. Both systematic, both fine.

## Deferral audit (132, 261, 262, 263)

| Id | Deferral | Verdict | Evidence |
|---|---|---|---|
| 132 | CT-gated | **RIGHT** | Sheet binds def:Class/@Name to "the CDISC/NCI GNRLOBSC General Observation Class codelist (C103329) **included in the Define-XML Controlled Terminology**"; spec p. 80 says only "Text must follow CDISC Controlled Terminology for General Observation Class" — **no inline closed set anywhere in the spec** (unlike Status, whose 3 values are printed in §4.1.1). The term list is CT-publication-dependent (classes have been added across IG generations), so a hardcoded one_of would drift. Author with `Requires: ct` + `term_in_ct_codelist` when that kind lands (plan P5). |
| 261 | CT-gated | **RIGHT** | The sheet rule itself is open-ended: "…C165635, C165636, C176227, **and any other subclass C-Code that is included in the applicable schema**." Not expressible as a closed one_of by the sheet's own wording. |
| 262 | CT-gated | **RIGHT** | Same open union (Class C103329 ∪ Subclass codelists ∪ "any other subclass C-Code"), same reasoning as 261. |
| 263 | Library gap | **RIGHT** | "@Version must match a version identifier available for @Name" — spec p. 18 prints only *examples* and defers the complete list to http://cdisc.org/standards; a closed-world version list would false-fire on every future standard release. No shortcut exists without a CDISC-Library standards-version catalogue; correctly recorded in docs/define-rules-gaps.md with a revisit note. |

Bookkeeping gap: 132/261/262 are recorded only in a `BatchARulesTest` javadoc
comment; `docs/define-rules-gaps.md` (by its own charter, inputs the validator
*doesn't take*) holds only 263. Since CT **is** a supported optional input
(`Requires: ct`, `CtProvider`), the three CT deferrals deserve a durable row
somewhere (the gaps doc with a "CT-gated, author in/after P5" note, or the
plan's inventory) so they aren't lost when the test comment ages.

## Bottom line

Verbatim fidelity to the sheet is excellent across all 39 rules (versions
column F, Source Type column G, and messages all re-checked against the
re-extracted rows; the scrutinised traps — 0031's `../../` step count, 0131's
`../../../`, 0028's alternation under full-match, 0035's Type=CT guard,
0264's Status set, 0033's both-versions — all check out). All four deferrals
were right.

**4 rules need changes** (0016, 0031, 0131 — test/fixture additions per
F2/F3, plus an owner decision on 0131's IsNonStandard carve-out per F5; 0254 —
message wording per F4); 0 rules are wrong.

---

## Coordinator resolutions (2026-07-03)

- **WARN 1 (0254)** — Message reworded to the invalid-value half; delegation to
  DEFINE-XML-0013 documented in the YAML.
- **WARN 2 (0131)** — carve-out ADOPTED: guard narrowed with
  `not @IsNonStandard=Yes` per spec pp. 17/79; recorded in the YAML comment;
  violations fixture gained an exempt `IsNonStandard` dataset proving silence
  under a live Submission guard.
- **WARNs 3+4 (guard blindness / 0016 never fires)** — new fixture
  `batch-a-context-other-21.xml` + `contextOtherDisarmsSubmissionGuardsAndSatisfies0016`
  pin the over-fire direction for 0016/0031/0131.
- **Deferral bookkeeping** — 132/261/262 now have durable rows in
  `docs/define-rules-gaps.md` (P5 CT-deferred section).
