# Stress-test review — PMDA batches P2 + P4 (+ coordinator files)

Adversarial review (plan §6.2/§6.5) of 96 PMDA rule files (P2: 38, P4: 58), their
test classes and fixtures, the P2/P4 sections of `define-rules-crossref.md`, and
the previously-unreviewed coordinator artefacts (PMDA-DD0072-B,
PmdaP3ResolutionsTest, the DD0103 W4 fix). Reviewer did not author any of the
files under review.

Ground truth used:

* `PMDA-ValidationRules-v6-0.xlsx`, sheet "Define-XML Rules" (header row 7) —
  all scoped rows extracted verbatim (message / description / severity /
  2.0 / 2.1 columns) and diffed against the YAML **by script**, all 96 files
  plus the 3 coordinator files (Severity, Applicable_Versions family-union,
  Plain_Text_Rule verbatim, Sheet_Rule_Identifier).
* Define-XML 2.1 spec (§4.1.1 standards-name table, §4.1.1 Status trio,
  §5.3.12 Origin), Define-XML 2.0 spec, Analysis Results Metadata v1.0
  (§5.3.7.2 arm:AnalysisVariable, §4.1/4.2 ResultDisplay/AnalysisResult).
* Engine sources read end-to-end: `CheckDefinition`, `Condition`,
  `RuleEvaluator`, `PathResolver`, `OidResolver`, `ElementNode(Builder)`,
  `RegexFormats`, `DocumentContext`/`SyntheticNodes`.
* Tests executed: `PmdaP2RulesTest` (4/4), `PmdaP4RulesTest` (5/5),
  `PmdaP3ResolutionsTest` (2/2) — **all green**
  (`mvn -P main -pl lib/corej-define-conformance -am test`).

Scripted sheet audit result: **zero severity mismatches, zero
Plain_Text_Rule mismatches, zero Sheet_Rule_Identifier mismatches** across all
99 files. The only version-union flags are the two *documented* P5 deferrals
(DD0021 / DD0055 2.1 halves — see W1). The P4 Reject set is confirmed sheet-
verbatim: DD0020B(+B), DD0021, DD0022 family (all 10 legs), DD0025 = Reject;
DD0019 = Error.

---

## Verdict table — batch P2 (38 files)

| Rule(s) | Verdict | Note |
|---|---|---|
| DD0012, DD0013, DD0014, DD0015, DD0016, DD0017, DD0041, DD0051, DD0062, DD0083, DD0088, DD0089, DD0091, DD0095, DD0097, DD0122, DD0131, OD0022, OD0027, OD0030, OD0031, OD0032, OD0041, OD0046, OD0048, OD0079, OD0079-B | PASS | Shapes match the sheet rows; uniqueness scopes (doc-wide vs per-parent) each follow their own row's wording, with the DD0083/DD0131 CDISC-scope disagreements correctly documented as source-faithful; OD0079's mixed-CodeList escape is documented and schema-bounded |
| DD0018 | PASS | Documented containment deviation (document-wide leaf index vs child leaf) shared with CDISC 0120; sound under xs:ID uniqueness |
| DD0067 | PASS | See adjudication A1 below (ARM referrer exclusion upheld) |
| DD0071 | PASS | Wildcard @CommentOID = union of the row's carriers; fires only where the attribute is present |
| DD0078 | PASS | Referrer relaxation (any DocumentRef + ArchiveLocationID) verified deliberate and *lax-only*; the clean fixture's aCRF leaf (referenced solely from def:AnnotatedCRF) proves the sheet-literal referrer list would false-positive — see A2 |
| DD0079 | PASS | Element-less @CommentOID referrer = sheet list ∪ remaining legal carriers; lax-only relaxation, documented |
| DD0080, DD0081, DD0082, DD0139 | PASS | Referrer vehicles are the only legal ones per spec; DD0139's two-carrier list (IGD + CodeList def:StandardOID) is exactly 2.1's reference surface |
| DD0125 | PASS | Folded-shape trace verified — see T1 |
| OD0042 | PASS | Cross-type collision impossible under a legal parent — see T2 |

## Verdict table — batch P4 (58 files)

| Rule(s) | Verdict | Note |
|---|---|---|
| DD0019, DD0030, DD0044, DD0048, DD0052, DD0104, DD0106, DD0107, DD0108, DD0123, DD0130, DD0135, DD0141, DD0148, OD0019, OD0021, OD0072, OD0073, OD0074, OD0075, OD0076, OD0077, OD0077-B, OD0078, OD0080 | PASS | Value sets / patterns sheet-verbatim (DD0048/OD0019 patterns byte-map the sheet's printed pattern; OD0075's intervalDatetime omission is the sheet's own text, disagreement flagged); DD0141's inline trio confirmed against 2.1 §4.1.1 (Draft/Provisional/Final); OD0021's RFC-3066 shape correct |
| DD0020B, DD0020B-B | PASS | Version-linked pattern split is stricter than CDISC 0028 (verified: 0028 accepts either shape on both versions) and matches the PMDA row's per-version wording; Reject both legs |
| DD0021 | PASS | 2.0 name set sheet-verbatim; 2.1 STDNAM half CT-deferred — tracking gap, see W1 |
| DD0022 family (base, -B..-E, -F..-J) | PASS | Full matrix verified — see T3 |
| DD0025 | PASS | `meddra-version` format = sheet's "decimal ending in 0 or 1"; guard carrier-detection note, see W3 |
| DD0053, DD0053-B | PASS | Version-organic any-guard (2.0 StandardName leg + 2.1 deref leg); values per sheet; unbound datasets escape (correct — family unknowable) |
| DD0055, DD0055-B | PASS | 2.0 legs sheet-verbatim (incl. the unenforceable "IG v1.1 and above" parenthetical, documented); 2.1 GNRLOBSC half CT-deferred — see W1 |
| DD0064 family (base, -B, -C, -D) | PASS | Four parent-scoped legs = exactly the sheet's four carriers; Alias under ItemDef correctly out of reach |
| DD0073 family (base, -B, -C, -D, -E) | PASS | Reverse-join trace verified — see T4 |
| DD0074 | PASS | Double-deref compare matches "provided at both levels ⇒ must match"; either-side-missing skip is the row's conditionality |
| DD0109, DD0110 | PASS | First-seen-canonical semantics acceptable — see T5; multi-Origin nuance N2 |
| DD0112 | PASS | Document-scope vehicle trace verified — see T6; the EXPECTED_21=0 test exception is legitimate (see Tests section) |
| DD0112-B | PASS | "and version" half; exists @Version under the MedDRA guard; blank Version counts as missing (engine presence semantics) — correct |
| DD0113 | PASS | Multi-hop deref + suffix regex verified — see T7 |

## Verdict table — coordinator files

| Artefact | Verdict | Note |
|---|---|---|
| PMDA-DD0072-B | **WARN (metadata-only)** | Check body is byte-identical to CDISC DEFINE-XML-0155-B (mirror fidelity confirmed by diff); guard trace correct (see T8). But `Plain_Text_Rule` is a paraphrase adapted from CDISC row 155, not the PMDA DD0072 sheet DESC verbatim — every other split leg (DD0020B-B, DD0022-B..J, DD0053-B, DD0055-B, DD0064-B..D, DD0073-B..E, DD0112-B, OD0079-B) carries its row's text verbatim, and `ConformanceRule.plainTextRule` is documented as "the sheet's normative rule text, verbatim". Recommend replacing with the DD0072 row DESC (as PMDA-DD0072 has) — W2 |
| PMDA-DD0103 (W4 fix) | PASS | Nested any-inside-all traced through `Condition.matches` (all→allMatch, any→anyMatch, recursive — evaluates as intended); paths verified: `../@Type`/`../@Source` = the Origin, `../../../AnnotatedCRF` = DocumentRef→Origin→ItemDef→MetaDataVersion child (step count right); 2.0 leg Type='CRF', 2.1 leg Collected + Source∈{Investigator,Subject} matches the W4 resolution and 2.1 §5.3.12.3 |
| PmdaP3ResolutionsTest | PASS | Covers both W3 branches (undelegated member fires, own-Origin and delegated-Origin members silent) and both W4 directions (Collected/Investigator fires, Collected/Vendor silent) |

---

## Detailed traces (priority-scrutiny items)

### T1 — DD0125 folded shape vs CDISC 0152 + 0152-B

Guard: `not(@StandardOID->Standard@OID/@Type equals "CT")`; check:
`not_exists @StandardOID`. Traced against `Condition.matchesClause` +
`RuleEvaluator.presence/isPresent`:

* **No @StandardOID** — deref resolves nothing ⇒ equals false ⇒ `not` true ⇒
  guarded in; `not_exists` on an absent attribute is satisfied ⇒ silent. ✓
* **Dangling @StandardOID** — deref resolves nothing ⇒ guarded in; attribute
  present+non-blank ⇒ `not_exists` fails ⇒ **fires** (0152's beat). ✓
* **Resolves, no @Type** — value set empty ⇒ equals false ⇒ **fires**
  (0152-B's review-batch-e refinement, inherited by the fold). ✓
* **Resolves, Type="CT"** — equals true ⇒ `not` false ⇒ out of reach ⇒ silent. ✓
* **Resolves, Type="IG"** — **fires**. ✓
* Blank `@StandardOID=""` — deref contributes nothing (guarded in) but
  presence semantics treat blank as missing ⇒ silent, consistent with the
  corpus-wide blank-is-missing convention. ✓

The single guarded rule reproduces the union of both CDISC halves exactly as
the crossref claims. One inherent nuance: a single finding/message covers both
dangling and wrong-Type (message "Referenced CT Standard is missing" is the
sheet's own MSG verbatim) — acceptable.

### T2 — OD0042 wildcard @OrderNumber, cross-type collision

`unique_among_siblings` groups scoped nodes by parent identity. OrderNumber
carriers in the Define-XML 2.0/2.1 schemas: ItemRef (parents: ItemGroupDef,
def:ValueListDef), CodeListItem and EnumeratedItem (parent: CodeList). No
legal parent mixes carrier types: IGD/VLD hold only ItemRefs, and CodeList's
content model is a *choice* (CodeListItem+ | EnumeratedItem+ |
ExternalCodeList) — mixing is schema-invalid. A schema-invalid mixed CodeList
could produce a cross-type collision, but a duplicated OrderNumber within one
codelist is arguably a true positive even then (the sheet's row is
carrier-agnostic: "The Order number attribute must be unique"). No change
needed.

### T3 — DD0022 family matrix (10 files)

Sheet sets → legs, verified value-by-value:

| Standard (sheet, 2.0 spelling) | Versions (sheet) | 2.1 leg (def:Standard/@Version) | 2.0 leg (MDV/@def:StandardVersion) |
|---|---|---|---|
| SDTM-IG | 3.1.2, 3.1.3, 3.2, 3.3, 3.4 | base (guard SDTMIG∨SDTM-IG) | -F (guard SDTM-IG) |
| SEND-IG | 3.0, 3.1, 3.1.1 | -B (SENDIG∨SEND-IG) | -G (SEND-IG) |
| ADaM-IG | 1.0, 1.1, 1.2, 1.3 | -C (ADaMIG∨ADaM-IG) | -H (ADaM-IG) |
| SEND-IG-AR / SEND-IG-GENETOX / BIMO | 1.0 | -D (SENDIG-AR, SEND-IG-AR, SENDIG-GENETOX, SEND-IG-GENETOX, BIMO) | -I (SEND-IG-AR, SEND-IG-GENETOX, BIMO) |
| SEND-IG-DART | 1.1 | -E (SENDIG-DART∨SEND-IG-DART) | -J (SEND-IG-DART) |

* All 7 sheet name-sets covered, both version legs each; no leg missing, no
  version literal deviates from the sheet.
* The 2.1 guards' dual spelling is justified: the 2.1 spec §4.1.1 name table
  (read from the PDF) lists the *hyphenless* forms (SDTMIG, SENDIG,
  SENDIG-DART, ADaMIG) while the sheet is worded in 2.0 hyphenated names —
  the union only *widens the guard* (more standards get their Version
  checked); it can never produce a wrong-set false positive because both
  spellings map to the same version set. SENDIG-AR/SENDIG-GENETOX are the
  natural STDNAM extensions of the same convention (not in the 2019 §4.1.1
  table, which predates them — the table itself says the terms are managed in
  Define-CT).
* Names with no sheet version set (SDTMIG-MD/-AP/-PGx, CDISC/NCI CT rows)
  correctly match no guard and stay unchecked; the guard sets are mutually
  disjoint (oneOf exact matching), so no double-fire.
* Define-XML 1.0 value sets are out of validator scope (documented in DD0021).

### T4 — DD0073 family reverse join (5 files)

2.1 legs' guard path `../@OID->ItemRef@ItemOID/../@StandardOID->Standard@OID/@Name`
traced through `PathResolver.valuesWithDeref`:

1. `..` — the Origin's parent = the owning ItemDef;
2. `@OID->ItemRef@ItemOID` — reads the ItemDef's OID and jumps (via
   `OidResolver`, first node in depth-first = document order) to the **first**
   ItemRef whose @ItemOID carries it;
3. `..` — that ItemRef's parent: ItemGroupDef **or def:ValueListDef**;
4. `@StandardOID->Standard@OID` — VLD has no def:StandardOID ⇒ contributes
   nothing ⇒ guard's matchesRegex over an empty set is false ⇒ value-level
   Origins are out of the 2.1 legs' reach. **Escape confirmed, and it is
   documented in all three 2.1 leg files.** (Global element order puts
   ValueListDef ItemRefs before ItemGroupDef ItemRefs, so a doubly-referenced
   ItemDef also escapes via the first-referrer rule — covered by the
   documented "first-match deviation".)
5. `@Name` — terminal attribute read on the def:Standard.

Guard regexes are full-match and family-clean: `SDTM.*` / `SEND.*` / `ADaM.*`
capture both spellings and all sub-IGs of the family, never a CT standard
(Name "CDISC/NCI") and never each other. Value sets per leg are sheet-verbatim
(2.1: SDTM 5-set / SEND 7-set / ADaM 3-set; 2.0: SDTM+ADaM 6-set /
SEND 4-upper-set).

2.0 legs' guard `../../@StandardName`: Origin → ItemDef → **MetaDataVersion**
— step count correct (2 steps, not 3; the def:StandardName carrier in 2.0 is
the MDV). -D covers SDTM-IG + ADaM-IG, -E covers the four SEND names; BIMO
datasets are unguarded, faithful to the sheet (the row names only SDTM/ADaM
and SEND families).

### T5 — DD0109/DD0110 consistency semantics

`consistent_across_document` flags every guarded node whose first-resolved
value deviates from the **first-seen** value in document order. If the first
USUBJID definition is the deviant one, all others get flagged (n−1 findings
pointing at the "wrong" nodes). This is acceptable: the sheet prescribes no
canonical value, only pairwise consistency ("should be consistent"), the
severity is Warning, and *some* set of findings always identifies an
inconsistency precisely when one exists. Ordering sensitivity is inherent to
any single-pass encoding and is now documented here. (Rule-file comments
already state first-seen-canonical.)

### T6 — DD0112 Document-scope vehicle

Scope `Document` = the synthetic wrapper node (`selectScope` returns exactly
it). Guard path `ODM/Study/MetaDataVersion/CodeList/ExternalCodeList/@Dictionary`
is plain child navigation — works from the synthetic node because its single
child is the real ODM root. `not(… matchesRegex "(?i)meddra")` ⇒ guard passes
iff **no** ExternalCodeList document-wide names MedDRA. Check
`not_exists ODM`: the ODM child always exists ⇒ fires exactly once on the
Document node. Shape correct; the finding's xpath is `/Document` (cosmetic,
acceptable). The "and version" half correctly lives in DD0112-B.

### T7 — DD0113 multi-hop deref + suffix regex

* Path `CodeListRef/@CodeListOID->CodeList@OID/ExternalCodeList/@Dictionary`:
  `valuesWithDeref` processes segments positionally — a child step
  (`CodeListRef`), then a deref segment (`@CodeListOID->CodeList@OID`), then a
  child step (`ExternalCodeList`), then a terminal attribute read
  (`@Dictionary`). **Confirmed in the code**: the segment loop dispatches on
  each segment's own shape, so a deref after a child step is fully supported
  (the only restriction — a *plain* mid-path `@attr` truncates — is not hit
  here). The guard's deref requires an OidResolver: `RuleEvaluator` passes
  `aContext.oidResolver()` into `guard.matches` (line 63) ✓.
* Regex `[A-Z]{2}(LLT|LLTCD|DECOD|PTCD|HLT|HLTCD|HLGT|HLGTCD|BODSYS|BDSYCD|SOC|SOCCD)`
  under `matches()` full-match semantics is **anchored on both sides**:
  exactly two leading uppercase letters plus a suffix consuming the rest.
  "KLLT" cannot match (KL+LT — LT not in the alternation); "AELLTCD" matches
  via backtracking to LLTCD ✓. The alternation covers **exactly** the sheet's
  12 suffixes, none missing, none extra.
* Firing set = name matches ∧ the MedDRA walk fails, which covers all three
  violation shapes: no CodeListRef (empty resolution), dangling CodeListOID,
  and a resolving CodeList without a MedDRA ExternalCodeList — precisely the
  row's "must reference MedDRA dictionary". The `not_exists @Name` fire-always
  vehicle is guard-guaranteed to fire (Name presence is the guard's first
  clause). Placeholder deviation (xpath instead of `<define variable>`) is
  documented.

### T8 — PMDA-DD0072-B guard (mirror of 0155-B)

Check body diffed byte-identical to DEFINE-XML-0155-B. Trace (scope
`ValueListDef/ItemRef`): clause 1 gates on a *resolving* @ItemOID (deref to
the value-level ItemDef's own @OID); clause 2 = that ItemDef has no
Origin/@Type (the XSD-required-Type proxy, documented); clause 3 = the
reverse join `../@OID->ValueListRef@ValueListOID/../Origin/@Type` reaches the
**first** delegating variable ItemDef and requires it to carry no Origin
either. Fires on the XSD-required @ItemOID. First-referrer caveat documented.
Scopes of DD0072 (ItemGroupDef/ItemRef) and DD0072-B (ValueListDef/ItemRef)
are disjoint — no double-fire. Test coverage exercises all three member
states. Only the Plain_Text_Rule convention nit remains (W2).

---

## Adjudications

### A1 — DD0067: excluding arm:AnalysisVariable as a referrer

The ARM v1.0 spec **does** define arm:AnalysisVariable/@ItemOID as "OID of
the ItemDef element" (§5.3.7.2 attribute table, read from the PDF) — so an
ItemDef referenced *only* by ARM would be flagged as an orphan by DD0067.
Adjudication: **the sheet-verbatim exclusion stands.** The row's referrer
list is closed ("referenced from a Dataset or Value Level metadata", vehicles
"ItemRef elements within an ItemGroupDef or def:ValueListDef"), and an
analysis variable that appears in no dataset's ItemRef list is itself a
defect — per the sheet it *is* an orphan definition. Severity Warning bounds
the residual risk. Note (N3): the same argument applies to
RangeCheck/@def:ItemOID (WhereClauseDefs also reference ItemDefs; also not
counted; also outside the row's closed list) — worth one comment line in the
rule file, no logic change.

### A2 — DD0078: "any DocumentRef counts" vs the sheet's "Method or Comment"

Verified against the sheet text: the row's first sentence names Method and
Comment only. The file counts any def:DocumentRef (plus
ItemGroupDef/@ArchiveLocationID defensively). The relaxation is *lax-only*
(never adds findings) and prevents a guaranteed false positive the
sheet-literal reading would produce: an aCRF/SAP leaf referenced solely from
def:AnnotatedCRF / def:SupplementalDoc / arm:Documentation — a mandatory
construct in PMDA submissions. The P2 clean fixture contains exactly this
shape (LF.ACRF/LF.SAP referenced only via AnnotatedCRF/arm:Documentation), so
the sheet-literal referrer list would fail the clean test. Deviation upheld,
documented in the file.

---

## Deferral verification

| Deferred item | Where tracked | Status |
|---|---|---|
| DD0021 2.1 half (def:Standard/@Name ∈ STDNAM, Define-CT) | rule-file comment + P4 test javadoc | **W1: not in define-rules-gaps.md** |
| DD0055 2.1 half (def:Class/@Name ∈ GNRLOBSC C103329) | rule-file comment + P4 test javadoc | **W1: not in define-rules-gaps.md** |
| DD0049 (split-dataset detection, cross-element counting) | P4 test javadoc only | **W1: not in define-rules-gaps.md** |
| DD0140 (CT-gated) | P4 test javadoc only | **W1: not in define-rules-gaps.md** |
| DD0063 → P5 | crossref P3 row | tracked ✓ |

The gaps doc's P5 section lists only CDISC ids. The PMDA deferrals above live
solely in code/test comments — fragile for the P5 hand-off.

## Crossref verification (spot checks, 7 claims)

| Claim | Verified |
|---|---|
| DD0048 vs 0109 **DISAGREE** (PMDA mixed-case, no 8-char cap; CDISC uppercase XPT-v5) | ✓ — 0109 pattern `[A-Z_][A-Z0-9_]{0,7}` vs DD0048 `[A-Za-z_][A-Za-z0-9_]*`; both sheet-faithful (PMDA row prints its own pattern) |
| OD0075 vs 0140(+B) **DISAGREE** (PMDA omits intervalDatetime even on 2.1) | ✓ — 0140 (2.1) carries the 12-value set incl. intervalDatetime, 0140-B (2.0) the 11-value set; PMDA row prescribes 11 values for both versions, encoded verbatim |
| DD0020B vs 0028 **DISAGREE** (PMDA version-splits; CDISC accepts either) | ✓ — 0028 pattern `2\.0\.0|2\.1\.\d+` on both versions |
| DD0012 exact-duplicate 0216 | ✓ — identical unique_in_document leaf/@ID |
| DD0125 "folds 0152+0152-B into one guarded rule" | ✓ — see T1 |
| DD0052 exact-duplicate 0112 | ✓ — identical compare (deref, caseInsensitive, file-basename) |
| DD0135 exact-duplicate 0260 | ✓ — identical guard and value |

One count nit (N1): the P4 crossref header and PmdaP4RulesTest javadoc say
"39 sheet rows authored as 58 rule files" — the authored row count is **37**
(27 DD + 10 OD); 39 is the batch's *assigned* row count including the two
unauthored deferrals (DD0049, DD0140). P2's "38 files / 37 rows" is correct.

## Tests & fixture-masking analysis

* All three test classes green (11 tests total). Both fixtures' construct
  maps were walked rule-by-rule against the engine semantics; each violator
  is isolated (exactly-one-finding assertions passed and the walk found no
  accidental second firing and no violator masked by a guard).
* **EXPECTED_21 DD0112=0 is a legitimate exception**, not a hole: the 2.1
  violations fixture must carry MedDRA ExternalCodeLists for the
  DD0025/DD0112-B/DD0113 violators, which makes DD0112's document-wide
  "no MedDRA anywhere" trigger unsatisfiable there. DD0112 has positive
  coverage in pmda-p4-violations-20.xml (EXPECTED_20=1, fires on the
  Document node) and negative (guarded-out) coverage on both 2.1 fixtures.
* P2 fixture notably exercises the tricky negatives: duplicate-OID entities
  counting as referenced (IT.DUPOID/COM.DUP/MT.DUP/VL.DUP), the DD0125
  CT-negative (CL.TERMDUP → STD.CT), and DD0078's AnnotatedCRF-only leaf.
* **W4 (test gap, low):** the 2.0-only legs' *pass* paths are unexercised —
  pmda-p4-violations-20.xml contains only guard-passing violators for
  DD0022-F..J, DD0055(+B) and DD0073-D/E (e.g. no SDTM-IG MDV with a *valid*
  def:StandardVersion anywhere). A guard-regression that made these legs
  fire on valid values would not be caught. Recommend adding one valid
  MDV per leg to the 2.0 fixture (or a clean-20 fixture).

---

## Findings requiring action

* **W1 (docs)** — Add the four PMDA P5 deferrals (DD0021 2.1 STDNAM half,
  DD0055 2.1 GNRLOBSC half, DD0049, DD0140) to `define-rules-gaps.md`'s
  "Deferred to the P5 CT-gated batch" section so the P5 hand-off cannot lose
  them. Doc-only.
* **W2 (PMDA-DD0072-B, metadata-only)** — Replace the paraphrased
  `Plain_Text_Rule` with the PMDA DD0072 sheet DESC verbatim (matching
  PMDA-DD0072 and every other split leg's convention). No check-logic change;
  no test impact.
* **W3 (decision to record; DD0025 / DD0112 / DD0112-B / DD0113)** — MedDRA
  carrier detection uses **full-match** `(?i)meddra`, so a Dictionary value
  like "MedDRA Dictionary" or "MedDRA V26.1" is not recognised: DD0112 would
  over-fire and DD0025/DD0112-B/DD0113 would under-check such documents. The
  four rules are at least mutually consistent. Either loosen to a substring
  match (`(?i).*meddra.*`) across all four, or record the strict-exact-name
  reading as the corpus decision in one rule comment. Judgment call — no
  clear sheet mandate; flagged for the coordinator.
* **W4 (tests, low)** — 2.0-leg pass-path coverage, see Tests section.
* **N1 (docs nit)** — "39 sheet rows" → "37 authored (+2 deferred)" in the
  P4 crossref header and PmdaP4RulesTest javadoc.
* **N2 (comment nit)** — DD0109/DD0110: note that only the *first* def:Origin
  is compared when a 2.1 ItemDef carries several.
* **N3 (comment nit)** — DD0067: mention RangeCheck/@def:ItemOID as a further
  deliberately-uncounted referrer (same closed-list rationale as ARM).

No severity, version-gate, value-set, pattern, scope/selector, or engine-trace
defect was found in any of the 96 batch files; both fixtures' exactly-once
maps verified; the DD0103 W4 fix and the DD0072-B mirror are correct.

**1 rule needs changes** (PMDA-DD0072-B — Plain_Text_Rule metadata only; zero
check-logic defects across all 96+3 files; remaining findings are doc/test
follow-ups W1/W3/W4/N1–N3).

---

## Coordinator resolutions (2026-07-03)

- **W2** — DD0072-B `Plain_Text_Rule` replaced with the sheet DESCRIPTION
  verbatim (copied from DD0072).
- **W1** — all P5 deferrals now have durable rows in `define-rules-gaps.md`
  (PMDA section: DD0021/DD0055 2.1 legs, DD0049/50/63/75/114/115, DD0140).
- **W3 decision recorded**: the `(?i)meddra` full-match carrier detection
  (DD0025/0112/0112-B/0113) intentionally targets the conventional
  Dictionary="MedDRA" spelling; verbose values ("MedDRA Dictionary vX") are
  out of the recognized convention — revisit only if real submissions show
  them. Documented here rather than loosened (a substring match would
  false-hit e.g. "non-MedDRA").
- **W4 + nits** — 2.0-leg pass-path tests → Terminal-B hardening list;
  crossref count corrected; DD0067's sheet-literal orphan semantics kept
  (Warning severity bounds the ARM-only risk; reviewer concurred).
