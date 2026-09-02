# Adversarial review — PMDA residual batch (Define-XML conformance)

Scope: the 11 residual PMDA rule files (DD0029/-B, DD0132/-B, DD0099, DD0100,
DD0105, OD0082, DD0138, DD0093, DD0150), the two custom checks
(`ArmParameterOidUsageCheck`, `StandardsCombinationCheck`),
`PmdaResidualRulesTest` + the four `pmda-res-*` fixtures, and the 7 adjudicated
gaps (DD0092, DD0116, DD0124, DD0136, DD0137, DD0118, OD0011).

Ground truth: PMDA-ValidationRules-v6-0.xlsx "Define-XML Rules" (header row 7,
cols A–G) read verbatim; Define-XML 2.1 / ARM v1.0 specs; the CDISC Library CT
cache at `/data2/_cdisc_lib_cache/`; engine sources under
`src/main/java/.../conformance/`.

Test run: `mvn -P main -pl lib/corej-define-conformance -am test
-Dtest=PmdaResidualRulesTest` → **Tests run: 11, Failures: 0, Errors: 0** —
BUILD SUCCESS.

## Per-rule verdict

| Rule | Sev (sheet→YAML) | Vers (sheet→YAML) | Kind | Verdict |
|------|------------------|-------------------|------|---------|
| PMDA-DD0029 | Warning→Warning | 2.0,2.1→2.0,2.1 | extended_value_marking required (EnumeratedItem) | PASS |
| PMDA-DD0029-B | Warning→Warning | 2.0,2.1→2.0,2.1 | extended_value_marking required (CodeListItem) | PASS |
| PMDA-DD0132 | Error→Error | 2.1→2.1 | extended_value_marking forbidden (EnumeratedItem) | PASS |
| PMDA-DD0132-B | Error→Error | 2.1→2.1 | extended_value_marking forbidden (CodeListItem) | PASS |
| PMDA-DD0099 | Warning→Warning | 2.0,2.1→2.0,2.1 | term_in_ct_codelist cCode C117744 | PASS (c-code confirmed) |
| PMDA-DD0100 | Warning→Warning | 2.0,2.1→2.0,2.1 | term_in_ct_codelist cCode C117745 | PASS (c-code confirmed) |
| PMDA-DD0105 | Warning→Warning | 2.0,2.1→2.0,2.1 | one_of Derived + Study-Day regex | PASS |
| PMDA-OD0082 | Warning→Warning | 2.0,2.1→2.0,2.1 | not_exists EnumeratedItem when CodeListItem | PASS |
| PMDA-DD0138 | Error→Error | 2.1→2.1 | references StandardOID→Standard, wildcard scope | PASS |
| PMDA-DD0093 | Warning→Warning | 2.0,2.1→2.0,2.1 | custom ArmParameterOidUsageCheck | PASS |
| PMDA-DD0150 | Reject→Reject | 2.1→2.1 | custom StandardsCombinationCheck | PASS |

All 11 severity and Applicable_Versions marks match the sheet verbatim (cols
D/E/F). `Severity` enum carries REJECT; DD0150 maps correctly.

## DD0099 / DD0100 c-codes — RESOLVED, the author's "unconfirmed" flag can be cleared

The author flagged C117744/C117745 as unconfirmed ("wrong c-code = silent
no-op"). Verified against the CDISC Library CT cache:

- **C117744 = ANLREAS = "Analysis Reason", extensible = true** — CORRECT.
- **C117745 = ANLPURP = "Analysis Purpose", extensible = true** — CORRECT.

Presence across packages (`api_mdr_ct_packages_*.json.gz`):

- **Define-XML CT**: present in **every** package 2019-12-20 … 2025-09-26.
- **ADaM CT**: present in the *old* packages (2014-09-26 … ~2021) but **absent
  from all modern ADaM CT packages (2023-06-30 … 2025-09-26)** — they were
  relocated to Define-XML CT.

Consequence: the c-code values are right and `term_in_ct_codelist`'s
fire-when-not-a-submission-value semantics (extensibility not gated, so a
Warning on any extension) is the correct encoding of both rows. The only
residual risk is *which CT package the production `CtProvider` binds at Phase 7*:
binding to a **modern ADaM CT** package silently no-ops both rules (empty
codelist → no finding); binding to **Define-XML CT** (the sheet's "or Define
CT") resolves them. The YAML CAUTION already documents the confirm-at-binding
requirement; recommend it be updated to "confirmed C117744/C117745 = ANLREAS /
ANLPURP; bind Define-XML CT, not modern ADaM CT." **No rule change.**

## Detailed findings

### DD0029/-B — the missing StandardOID guard is sheet-faithful (PASS)
CDISC twin DEFINE-XML-0186 (required mode) carries `when: ../@def:StandardOID
exists: true`; the PMDA DD0029 files omit it. Verified the PMDA sheet DESCRIPTION
("The def:ExtendedValue attribute is required when the CodedValue is an extended
value") contains **no** StandardOID precondition — so the omission mirrors the
sheet, it is not a drop. Functionally the engine's required-mode
(`RuleEvaluator.extendedValueMarking`) fires only when `resolveEnclosingCodelist`
succeeds, i.e. the CodeList has an `Alias[@Context="nci:ExtCodeID"]` resolving to
a known CT codelist — an implicit CT gate that stands in for the CDISC
StandardOID guard. The two diverge only for a CodeList that carries an nci alias
but no `def:StandardOID` (malformed-ish in 2.1); there PMDA fires and CDISC does
not, which is what the broader PMDA wording asks for. DD0132/-B (forbidden) has
no guard, matching CDISC 187/202. Correct.

Observation (not a change): DD0029/-B are marked **2.0 and 2.1** per the sheet,
whereas the CDISC required-mode twin 186 is **2.1-only** (def:ExtendedValue is a
2.1 construct). The YAML mirrors the PMDA sheet's E/F columns faithfully, and
the test asserts DD0029 fires at 2.0 — intentional. The oddity lives in the PMDA
sheet, not the authoring.

### DD0132/-B — 2.1-only correct (PASS)
Sheet E/F = (blank, X) → `["2.1"]`. def:ExtendedValue is a Define-XML 2.1
attribute, so a "forbidden def:ExtendedValue" check is meaningless at 2.0; the
2.1-only scope is right on both sheet and semantic grounds. Fixture cleanly
separates the extensible Unit codelist (required legs fire there) from the
non-extensible Sex codelist (forbidden legs fire there), so DD0029 and DD0132
cannot mask each other.

### DD0105 — regex anchoring correct (PASS)
`Condition.matchesClause` uses `pattern.matcher(v).matches()` (full match, line
181), so `[A-Z]{2,}(STDY|ENDY|DY)` must span the whole `@Name`. "STUDYID" does
**not** match (trailing "ID"), so the "contains DY" false-hit the prompt worried
about does not occur. The violations fixture proves the guard with a decoy:
`AGE` has `Origin Type="Collected"` yet does not fire (name fails the regex),
while `AESTDY` fires and `AEENDY` (Derived) does not.
Minor observation: the regex is not restricted to real domain prefixes, so a
hypothetical all-caps name ending in "DY" of length ≥ 4 (e.g. `STUDY`, `BODY`)
would match — but these are not SDTM variable names, so the risk is negligible.
No `STUDYID`-style decoy exists in the fixtures (would strengthen the suite) but
full-match semantics already preclude that class of false hit.

### OD0082 — distinct from CDISC 199 (PASS)
Sheet DESC is about *item-type homogeneity* (once a CodeListItem exists, no
decode-less EnumeratedItem may be mixed in), not per-item Decode presence.
`not_exists target: EnumeratedItem when exists CodeListItem` encodes exactly
that and is correctly distinct from DEFINE-XML-0199 (`exists Decode` per
CodeListItem). Clean fixture proves both single-type codelists (Enum-only Sex,
CodeListItem-only Sponsor) pass.

### DD0138 — wildcard does not over-fire (PASS)
Element `*` / attribute `StandardOID`, `references … Standard@OID`. Per the
sheet DD0139 note, `def:StandardOID` occurs **only** on ItemGroupDef and
CodeList, so the wildcard universe equals DD0122 ∪ DD0125's domains — no scope
beyond them. `references` fires only on a *present-but-unresolvable* value
(absent → no finding), so on any valid document it never false-fires. The
overlap with DD0122 (ItemGroupDef) and DD0125 (CodeList/CT) produces duplicate
findings on a genuinely-dangling StandardOID; that is intentional PMDA rule
redundancy (all three are distinct sheet rows), not a defect. For CodeLists
DD0138 ⊆ DD0125 (DD0125 additionally checks Type=CT), so DD0138 is strictly the
weaker existence slice. The "(name and version) not valid" catalogue nuance is
correctly deferred to the library gap. Fires once on IG.BAD in the fixture.

### DD0093 — ArmParameterOidUsageCheck matches the ARM DESC (PASS)
Sheet: when an arm:AnalysisResult carries ParameterOID, at least one child
AnalysisDataset must be a BDS and the ParameterOID must reference a PARAMCD
variable for that dataset. The check: resolve ParameterOID→ItemDef, require
`Name == "PARAMCD"`, then require that PARAMCD ItemDef to be an ItemRef member of
one of the result's AnalysisDataset ItemGroupDefs. Collapsing "is a BDS" into
"contains PARAMCD" is sound (a dataset carrying PARAMCD is by definition BDS) and
avoids the fragile 2.0-attr vs 2.1-element `def:Class` string. All three
outcomes are exercised: non-PARAMCD (fixture AR.BAD→IT.AVAL), dangling OID
(inline test), PARAMCD-not-a-member (inline test). Custom is justified
(cross-structure OID chase). Budget: 11 CustomCheck classes / 11 className refs,
under the cap of 15.

### DD0150 — StandardsCombinationCheck matches the sheet, incl. its asymmetry (PASS)
The check keys off each IG def:Standard and requires the matching CT
PublishingSet: `startsWith("SENDIG")`→SEND, `== "SDTMIG"`→SDTM,
`== "ADaMIG"`→SDTM|ADaM. This exactly reproduces the sheet's own asymmetric
wording ("Name begins with 'SENDIG'" vs "Name='SDTMIG'" / "Name='ADaMIG'"). A
consequence (faithful to the sheet, not a defect) is that variant IGs like
`SDTMIG-MD` or `ADaMIG-NCA` are not matched by the exact tests — the sheet does
not cover them. Scoped to the synthetic Document node → reported once. Fixtures +
inline tests cover SEND (fires), SDTM (clean) and ADaM families. Custom justified
(single-element conjunction + cross-element existence, inexpressible in the path
grammar).

## Fixture masking analysis
No masking found. The CT fixture deliberately puts required-mode violations only
on the extensible Unit codelist and forbidden-mode violations only on the
non-extensible Sex codelist, and each rule is asserted to fire **exactly once**
with the offending CodedValue in the message. The non-CT fixture uses decoys
(AGE Collected for DD0105; AR.GOOD/AR.NOPARAM for DD0093; IG.ADLB resolvable
StandardOID for DD0138) that would surface an over-fire. `severitiesAndVersions…`
independently pins every severity/version to the sheet.

## Gap adjudication verdicts

| Gap | Classification | Verdict |
|-----|----------------|---------|
| DD0092 | ARM-semantic (ParameterOID-required trigger non-determinable) | **WARN** — contestable |
| DD0116 | library-gap (IG qualifier-label catalogue) | PASS |
| DD0118 | library-gap + duplication (per-variable→codelist map; CT slice = DD0033/34) | PASS |
| DD0124 | library-gap (per-variable IG CT requirement) | PASS |
| DD0136 | library-gap (IG dataset-label catalogue; twin CDISC-265) | PASS |
| DD0137 | library-gap (IG variable-label catalogue; twin CDISC-266) | PASS |
| OD0011 | raw-check backlog (encoding = prolog/raw-bytes) | **WARN** — authorable now |

- **DD0092 (WARN, contestable, not a required change).** The adjudication
  ("based on specific parameter(s) has no structural marker") is defensible but
  not airtight: ARM encodes parameter-specificity structurally when an
  AnalysisDataset's `arm:WhereClauseRef → WhereClauseDef` filters on the PARAMCD
  ItemDef (a RangeCheck on the parameter variable). A custom could fire "if any
  analysed dataset's WhereClause constrains PARAMCD, then ParameterOID must be
  present." Whether that heuristic is reliable enough for an **Error**-severity
  rule is a genuine judgement call (a WhereClause on PARAMCD is not a guaranteed
  proxy for "based on specific parameters", and parameter-specificity can be
  expressed via AnalysisVariable instead). The conservative deferral is
  acceptable, but this is the weakest of the seven gap calls and should be
  revisited if a WhereClause-driven ARM signal is later adopted. The companion
  DD0093 (the "when present, use correctly" direction) is authored, so the
  authored/gapped split is coherent.
- **OD0011 (WARN, authorable now, deferral is a scoping choice).** Unlike the
  five true library gaps, OD0011 needs no external library: `xsd/RawDocumentChecks`
  already reads the raw bytes and handles the BOM/UTF-16 prolog
  (`namespaceDeclarations`, `BOM_UTF8`, UTF-16 decode). Checking the XML
  declaration encoding is one raw-bytes/prolog scan away. Deferring it to the
  "raw-check backlog" alongside DD0085 is a defensible sequencing decision (the
  residual batch is the declarative/custom layer, not the raw-checks layer), but
  it is a **capability that exists today**, not a hard gap. Flag for the backlog
  owner: OD0011 is low-effort and the infrastructure is present.
- DD0116/DD0118/DD0124/DD0136/DD0137 are genuine library gaps (per-variable /
  per-dataset IG catalogue input this validator deliberately does not take) and
  are correctly classified; DD0118's CT-decidable slice does already ship as
  DD0033/DD0034.

## Conclusion

Every one of the 11 authored rule files is faithful to the sheet (severity,
versions, and check semantics) and the two custom checks trace correctly to
their ARM/sheet DESCRIPTIONs; the fixtures do not mask. The DD0099/DD0100
c-codes are **confirmed correct** (C117744=ANLREAS, C117745=ANLPURP, both
extensible) — the only residual is the Phase-7 CT-package binding choice, which
is already documented. Recommended non-blocking follow-ups (no rule-logic
change): (1) clear the "unconfirmed" language in the DD0099/DD0100 YAML comments
and record "bind Define-XML CT"; (2) treat DD0092 as the weakest gap call, to
revisit if an ARM WhereClause-on-PARAMCD signal is adopted; (3) note OD0011 is
authorable-now in RawDocumentChecks.

**0 rules need changes.**

---

## Coordinator resolutions (2026-07-03)

- **0 rule changes** — all 11 residual rules PASS.
- **DD0099/DD0100 c-codes CONFIRMED** (C117744/C117745, Define-XML CT
  2019-2025): the "unconfirmed" YAML cautions replaced with a CONFIRMED note
  that also records the binding constraint (modern ADaM CT dropped them → the
  production CtProvider must bind Define-XML CT). This directly informs the
  `-vx` integration's CT-binding choice.
- **OD0011** promoted to a Terminal-A pickup (RawDocumentChecks already reads
  the prolog).
- **DD0092** deferral KEPT — acceptable for an Error rule (the reviewer's
  "arguably mechanizable via WhereClause-on-PARAMCD" noted as a future
  enhancement, not a gap error).
