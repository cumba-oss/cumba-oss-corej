# Adversarial stress-test review — PMDA batch P3 (43 rules)

Reviewer: independent stress-test agent (plan §6.2/§6.5). Scope: the 43
`PMDA-*.yaml` files on disk, `PmdaP3RulesTest.java`, the three `pmda-p3-*.xml`
fixtures, and the P3 section of `docs/define-rules-crossref.md`.

Ground truth used, in priority order:

1. `/data/cdisc-docs/PMDA-ValidationRules-v6-0.xlsx`, sheet "Define-XML Rules"
   — all 39 scoped rows extracted verbatim (MESSAGE / DESCRIPTION / Severity /
   2.0 / 2.1 columns).
2. `/data/cdisc-docs/Define-xml-2-1.pdf`, `Define-xml-2-0.pdf`,
   `Analysis Results Metadata v1.0 for Define-XML v2.pdf` (text-extracted and
   read for every contested point below).
3. Engine sources: `CheckDefinition.java`, `Condition.java`,
   `RuleEvaluator.java`, `PathResolver.java` (every verdict below is an actual
   engine trace, not a YAML-reading).

Test run: `mvn -P main -pl lib/corej-define-conformance -am test
-Dtest='PmdaP3RulesTest'` — **5/5 tests pass** (clean lane, violations lane,
version gate, 2.0-leg lane, severity/message/xpath assertions).

## Per-rule verdict table

Blanket checks performed on **all 43** files first:

* **Severity column D verbatim**: all 43 match the sheet — Error everywhere
  except DD0098 = Warning, DD0111 = Warning, OD0070/OD0071/OD0081 = Warning.
  No deviation found.
* **Applicable_Versions vs E/F X-marks**: all 43 match — the nine 2.1-only
  rows (DD0119, DD0120, DD0121, DD0126, DD0127, DD0128, DD0129, DD0133,
  DD0134) carry `["2.1"]`; the other 34 files carry `["2.0","2.1"]` per their
  X/X marks.
* **Message verbatim**: all match; DD0038 and OD0081 drop the sheet's
  `<dataset>` / `<codelist>` placeholder (documented; the finding xpath names
  the node — acceptable).
* **Plain_Text_Rule verbatim**: spot-checked all 43 against the extracted
  DESCRIPTION column — verbatim.
* All 43 files are listed in `index.txt` (ship manifest), so the production
  `loadShipped()` path picks them up, not just the test's directory loader.

| Rule | Verdict | Evidence (one line) |
|---|---|---|
| DD0035 | **WARN** | Legs correct + organically dispatched, but a CRF/Collected origin with **no** `def:DocumentRef` escapes, and the comment's justification ("DD0103's beat") is wrong — see W1 |
| DD0037 | PASS | Fire-always vehicle traced correct (fires iff no PageRefs ∧ (no FirstPage ∨ no LastPage)); NamedDestination over-fire is row-verbatim and flagged as a CDISC disagreement |
| DD0038 | PASS | QVAL proxy identical to CDISC 0055 (diffed); fixture proves both lanes |
| DD0040 | PASS | Guard `ItemRef/@KeySequence exists:false` = "no ItemRef carries a non-blank KeySequence" — exactly the row's "at least one ItemRef" |
| DD0042 | PASS | Deref guard traced (`@ItemOID->ItemDef@OID/Origin/@Type` any-match); scope `ItemRef` also covers value-level derived items — correct |
| DD0045 | PASS | 2.0 leg `oneOf ["SDTM-IG","SEND-IG"]` = spec 2.0 p.58 enumerated values **and** the sheet's own literals (version lives in def:StandardVersion, so no "SDTM-IG 3.1.2" trap); 2.1 regex `(SDTM|SEND).*` full-matches SDTMIG/SDTMIG-MD/-AP/-PGx/SENDIG/SENDIG-DART (2.1 §4.1.1) and cannot match ADaMIG/CDISC/NCI; legs cannot cross-fire (no @def:StandardName on 2.1 MetaDataVersion, no @def:StandardOID in 2.0) |
| DD0046 | PASS | Mirror of DD0045; `ADaM.*` matches ADaMIG only; blank-Domain escape noted (N3) |
| DD0047 | PASS | Unconditional per PMDA reading; disagreement with Submission-gated CDISC 0108 confirmed real |
| DD0054 | PASS | Both carriers (2.0 `@def:Class`, 2.1 `def:Class` child) demanded disjunctively; disagreement with 0131 (IsNonStandard carve-out + Submission gate) confirmed real; blank `@Class` correctly treated as missing |
| DD0056 | PASS | Severity/versions verbatim; the HasNoData carve-out is **spec-mandated in 2.1** — keep it (recommendation below) |
| DD0057 | **WARN** | Correct node-level encoding, but "cannot be empty" is not enforced: an empty `<TranslatedText/>` passes (element-path guard = node presence) — see W2 |
| DD0057-B | **WARN** | Same as DD0057 (MethodDef leg) |
| DD0057-C | **WARN** | Same as DD0057 (def:CommentDef leg) |
| DD0058 | PASS | Deref guard goes through *value* semantics (`valuesWithDeref` + non-blank), so blank labels DO fire here — better than DD0057, and the row's VLM exemption falls out of the `ItemGroupDef/ItemRef` scope organically |
| DD0061 | PASS | `@Type` anchor guaranteed by the guard's own equals clause; fires once per Predecessor origin without description |
| DD0068 | PASS | Diffed identical to CDISC 0143 (oneOf order only); missing-DataType over-inclusion documented and harmless (DataType is XSD-required); blank Length = empty satisfies the row |
| DD0069 | PASS | Negative SigDigits leg, same shape; float-only guard verbatim |
| DD0070 | PASS | VLM-exemption deviation is documented and spec-sensible; disagreement with 0147 (Submission + .xpt gate, 2.1-only) confirmed real |
| DD0072 | **WARN** | Covered branch correct (neither Origin nor ValueListRef); the row's value-level completeness branch is NOT covered — tracked as PMDA-DD0072-B follow-up, must not be forgotten — see W3 |
| DD0076 | PASS | Guard matches the row's cross-type "any Codelist item" wording; self-inclusion in the sibling set is harmless (checked item passes when it carries Rank) |
| DD0076-B | PASS | CodeListItem leg, same trace |
| DD0077 | PASS | OrderNumber analogue, same trace |
| DD0077-B | PASS | CodeListItem leg, same trace |
| DD0087 | PASS | `arm:AnalysisResultDisplays` is a MetaDataVersion child (ARM §5.3.1) so target/scope are right; legs cannot cross-fire; mixed-standard exemption is a defensible judgment (assessment below) |
| DD0090 | PASS | ARM §5.3.2: ResultDisplay children = Description, def:DocumentRef, arm:AnalysisResult — unconditional `exists Description` is the row |
| DD0094 | PASS | Traced end-to-end: guard passes iff `@CommentOID` absent/blank; `cardinality_at_most(AnalysisDataset, 1)` flags iff child count > 1 ⇒ fires **exactly** when >1 dataset ∧ no comment; `def:CommentOID` on arm:AnalysisDatasets confirmed (ARM §5.3.6, "Required if there is more than one analysis dataset") |
| DD0096 | PASS | `@OID` anchor is ARM-Required on AnalysisResult (§5.3.5); the descendant path's any-match IS the row's second sentence (variable under any one dataset suffices) — clean fixture AR.2 proves it |
| DD0098 | PASS | Context sits on arm:ProgrammingCode, arm:Code is its child (ARM §5.3.9/5.3.10); prefix stripping handles `arm:Code` in the guard; Warning verbatim |
| DD0103 | **WARN** | 2.1 Collected leg lacks the Source ∈ {Investigator, Subject} gate that the 2.1 spec, the PMDA DD0075 row, and this batch's own DD0035/DD0111 all use — over-fires on Collected/Vendor|Sponsor page refs — see W4 |
| DD0111 | PASS | 2.0 leg is row-verbatim ("must be empty when not CRF"); the negative 2.1 reading is a documented judgment (the row's 2.1 sentence merely restates DD0035) and the only encoding that isn't a duplicate; Warning severity bounds the over-fire risk |
| DD0117 | PASS | ARM §5.3.8: Documentation children = Description + def:DocumentRef; guard on DocumentRef presence is the row's "references an External Document" |
| DD0119 | PASS | Diffed byte-equivalent to CDISC 0035; 2.1-only ✓ |
| DD0120 | PASS | Diffed identical to CDISC 0121 |
| DD0121 | PASS | Diffed identical to CDISC 0122; row's own parenthetical defines "not standard" = no def:StandardOID |
| DD0126 | PASS | CodeList/@def:StandardOID confirmed in 2.1 §5.3.13; analogue of DD0120 |
| DD0127 | PASS | `Alias/@Context equals nci:ExtCodeID` any-match = "an NCI Code is provided"; CodeList-level Alias only (correct level; item-level Aliases not caught by the path) |
| DD0128 | PASS | Analogue of DD0121 for CodeList |
| DD0129 | PASS | `not(Alias/@Context equals …)` = no NCI-code alias present; duplicated-Alias laxness vs CDISC 0249 documented |
| DD0133 | PASS | Diffed identical to CDISC 0240; 2.1-only ✓ |
| DD0134 | PASS | Diffed identical to CDISC 0242 (reverse join); unqualified ItemRef scope (incl. VLM ItemRefs) is the row's own unqualified wording; `Attribute: null` nit (N2) |
| OD0070 | PASS | Diffed identical to CDISC 0141 (oneOf order only); Warning verbatim |
| OD0071 | PASS | Diffed identical to CDISC 0145; Warning verbatim |
| OD0081 | PASS | Fires iff all three content forms absent (guard excludes CodeListItem/ExternalCodeList, check demands EnumeratedItem) — exactly the row |

**Tally: 37 PASS / 6 WARN / 0 FAIL.**

## Detailed WARN findings

### W1 — DD0035: DocumentRef-less origins escape, and the comment mis-attributes the gap

Sheet: "the Pages field is required when Origin Type is 'CRF'" (2.0) /
"'Collected' + Source Investigator/Subject" (2.1). The rule is scoped to
`Origin/DocumentRef`, so a CRF/Collected(Inv/Subj) origin that has **no
def:DocumentRef at all** produces no scoped node and can never fire — yet it
plainly has no Pages either, so the sheet row would flag it.

The file comment acknowledges the deviation but justifies it with "the
missing-document defect is DD0103's beat". That is **incorrect**: DD0103 is
also scoped to `Origin/DocumentRef` and additionally guards on
`PDFPageRef exists:true`, so it too cannot fire on a DocumentRef-less origin.
The row that actually owns that shape is **DD0075** ("when def:Origin/@Type is
'CRF' … def:DocumentRef must match the def:DocumentRef in def:AnnotatedCRF"),
which is not in P3.

Recommended correction (comment-only, no behavior change): re-point the
justification from DD0103 to DD0075, and confirm with the coordinator that the
batch owning DD0075 encodes it so that an *absent* DocumentRef fires (else the
gap is corpus-wide, not just P3's).

### W2 — DD0057/-B/-C: "cannot be empty" not enforced (and asymmetric with DD0058)

The guard clause `path: "Description/TranslatedText" exists: false` is an
element path without deref, so `Condition.matchesClause` takes the
**node-presence** branch (`PathResolver.nodes(...).isEmpty()`); an empty
`<TranslatedText/>` counts as present and the rule stays silent, although the
row says the Description "cannot be empty". Contrast DD0058, whose deref path
(`@ItemOID->ItemDef@OID/Description/TranslatedText`) routes through
`valuesWithDeref` → `text()` → `anyMatch(!isBlank)`, so blank labels DO fire
there. Two rules from near-identical "cannot be empty" rows behave differently
on blank text. The file comment documents the limitation ("text-blankness
itself is not checked"), so this is a tracked under-fire, but it deserves an
engine follow-up (e.g. a `#text`-style non-blank predicate for element paths,
which `RuleEvaluator.valueOf` already supports for check attributes but the
condition grammar does not). No P3 file change is possible without that
grammar extension.

### W3 — DD0072: the row's value-level branch is uncovered (tracked)

The encoding fires only when the referenced ItemDef has *neither* `def:Origin`
nor `def:ValueListRef`. The row's second obligation — a ValueListRef is
present but some value-level ItemDef in the list lacks its Origin — needs a
universal quantifier over a dereferenced node set and is explicitly deferred
(file comment + crossref: "follow-up: PMDA-DD0072-B", CDISC ships it as
0155-B). Verified the covered branch is correct (Origin presence proxied via
its XSD-required `@Type`, same as twin 0155). Nothing to change in this file;
the follow-up must actually be scheduled or this is a silent under-fire
against the sheet.

### W4 — DD0103: 2.1 leg over-fires on Collected origins from Vendor/Sponsor

The guard accepts `../@Type ∈ {CRF, Collected}` with **no Source
qualification**. Ground truth is consistently narrower:

* Define-XML 2.1 §5.3.12.3 business rule: only "def:Origin/@Type='Collected'
  **and** def:Origin/@Source in ('Investigator', 'Subject')" must reference
  the annotated CRF.
* The PMDA sheet's own DD0075 row uses exactly that Source-qualified wording
  for the 2.1 CRF-origin notion.
* This batch's DD0035 and DD0111 both encode Collected + Source ∈
  {Investigator, Subject}.

Consequence: a 2.1 `Collected`/`Vendor` (or `Sponsor`) origin whose
DocumentRef carries PDFPageRefs into, say, an eDT specification document
fires "Missing referenced CRF document" (Error) in any define without a
`def:AnnotatedCRF`. (`def:AnnotatedCRF` itself is confirmed to still exist in
2.1 — §5.3.7 — so the target is fine; only the guard is too wide.)

Recommended correction:

```yaml
  when:
    all:
    - path: "@leafID"
      exists: true
    - path: "PDFPageRef"
      exists: true
    - any:
      - path: "../@Type"
        equals: "CRF"
      - all:
        - path: "../@Type"
          equals: "Collected"
        - path: "../@Source"
          oneOf: ["Investigator", "Subject"]
    - path: "../../../AnnotatedCRF"
      exists: false
```

(The `../../../AnnotatedCRF` depth was verified: DocumentRef → Origin →
ItemDef → MetaDataVersion; `def:Origin` exists only under ItemDef in both
specs, so the depth cannot drift.)

## DD0056 — recommendation on the deliberate deviation: KEEP the carve-out

The PMDA row is unconditional ("def:ArchiveLocationID attribute is required
… cannot be empty", X-marked for 2.0 and 2.1). The author adopted CDISC
0119's `not(@HasNoData='Yes')` carve-out. Assessment:

1. **The 2.1 spec itself conditions the attribute exactly this way.**
   Define-XML 2.1 ItemGroupDef table: def:ArchiveLocationID is "Required in
   the context of a regulatory submission for each ItemGroupDef **that does
   not include def:HasNoData='Yes'**"; the §4.9 conditional-conformance table
   repeats it ("for each ItemGroupDef without a def:HasNoData attribute" —
   equivalent, since "Yes" is the attribute's only allowable value, which is
   also why the `equals: "Yes"` form and the exists form coincide).
2. **PMDA's own rule system presupposes the carve-out.** DD0133 (in this same
   sheet, 2.1-marked) requires a def:CommentOID precisely for
   HasNoData='Yes' datasets — i.e. PMDA accepts planned-but-empty datasets as
   legitimate submission content. An unconditional DD0056 would make
   DD0056 + DD0133 jointly unsatisfiable in spirit: a dataset with no data has
   no transport file for ArchiveLocationID to point at, so a sponsor would
   have to fabricate an empty XPT or always eat an Error.
3. **The deviation is invisible in 2.0.** def:HasNoData does not exist in
   Define-XML 2.0, so the guard is vacuous there and the 2.0 behavior is
   unconditional — exactly the row.
4. The PMDA DESCRIPTION is written in version-neutral 2.0-era vocabulary; the
   HasNoData mechanism is a 2.1 addition the row simply never mentions. Sheet
   silence here is not a considered prohibition.

PMDA-faithfulness would win only if PMDA were known to fire unconditionally in
practice; absent that evidence, firing an Error the 2.1 spec explicitly says
is not required would be a false positive. **Verdict: keep the carve-out;
deviation stays documented in the file and crossref (it already is).** The
clean fixture's IG.NODATA proves the carve-out lane; the violations fixture's
IG.V56/IG.V133 pair proves it does not mask the base rule.

## DD0087 — mixed-standard exemption: reasonable, keep

PMDA-verbatim would fire on any define declaring SDTM/SEND that contains an
ARM block. In 2.1, `def:Standards` legitimately lists several IGs; an
integrated/ADaM define that also declares SDTMIG (e.g. for predecessor
traceability) would then produce a false Error on its perfectly legitimate ARM
block (ARM §5.3.1: the element "must be included in an ADaM define.xml").
Residual risk of the exemption — a genuinely SDTM-only define that spuriously
declares ADaMIG to smuggle ARM past the check — is contrived. The 2.0 leg is
unaffected (single def:StandardName, so an ADaM-IG 2.0 define never enters the
SDTM leg at all). Judgment call is defensible and documented; no change.
Verified no cross-fire: 2.1 docs cannot match `@StandardName` (attribute
removed from MetaDataVersion in 2.1), 2.0 docs cannot match
`Standards/Standard/@Name` (element does not exist in 2.0), and CT standards
are named "CDISC/NCI" so they can never satisfy `(SDTM|SEND).*`.

## Crossref spot-check results

Requested five exact-duplicate claims — all five verified by diffing the
Check bodies:

| Claim | Result |
|---|---|
| DD0038 → 0055 | CONFIRMED identical (`exists def:ValueListRef when @Name='QVAL'`, ItemDef scope, 2.0+2.1) |
| DD0042 → 0073 | CONFIRMED identical (deref guard byte-for-byte) |
| DD0068 → 0143 | CONFIRMED identical (oneOf list order differs — set-equal) |
| DD0120/121 → 0121/0122 | CONFIRMED identical both pairs |
| DD0133/134 → 0240/0242 | CONFIRMED identical both pairs (incl. the 0242 fire-always vehicle) |

Bonus checks: DD0119 → 0035, OD0070 → 0141, OD0071 → 0145 also diffed
identical (severity difference correctly recorded in the crossref).

The four flagged disagreements are **all real** (read from the CDISC files,
not from the crossref):

* DD0037 vs 0048/0050: CDISC guards `@Type equals 'PhysicalRef'`; PMDA has no
  Type condition. Real.
* DD0054 vs 0131: 0131 gates on `../../../@Context='Submission'` and exempts
  `@IsNonStandard='Yes'`; PMDA has neither. Real.
* DD0047 vs 0108: 0108 is Submission-gated and 2.1-only. Real.
* DD0070 vs 0147: 0147 gates on Submission + `.xpt` ArchiveLocation deref and
  is 2.1-only. Real.

One crossref nit (N4): DD0076(-B)/DD0077(-B) are labeled "exact-duplicate
0183+0194 / 0185+0196", but the CDISC twins each check only their **own**
sibling set (`../EnumeratedItem/@Rank` for 0183), while the PMDA legs carry
the extra cross-type any-clause. Behaviorally identical on valid documents
(one item type per CodeList), divergent on malformed mixed CodeLists —
"exact-duplicate" slightly overstates it; "agree (PMDA adds the cross-type
clause)" would be accurate.

## Fixture-masking analysis

The three fixtures were read line-by-line against all 43 guards:

* **violations-21**: every rule has exactly one dedicated violator and I found
  no accidental second firer (e.g. IT.QVAL.AESER lacks SASFieldName but is
  VLM-only so DD0070 correctly ignores it — that is a deliberate decoy, not a
  mask; AR.V94's comment-less two-dataset shape does not trip DD0096 because
  its first dataset carries a variable — deliberate any-match proof).
* **clean-21**: contains guard-positive-satisfied shapes (Derived+MethodOID,
  Collected+pages, two-dataset+CommentOID, HasNoData+CommentOID with the
  DD0056 carve-out) and guard-negative decoys (datetime without Length,
  ADaM-declared ARM block) — the guards are genuinely exercised, not
  vacuously green.
* **violations-20**: exercises all six 2.0 legs (CRF pages, StandardName SDTM/
  ADaM dispatch, @def:Class attribute carrier, ARM-in-SDTM, pages-on-Derived)
  with per-rule expected counts including zeroes — good cross-fire protection
  (a 2.1-leg misfire on the 2.0 document would break the zero expectations).
* Untested (acknowledged, acceptable): DD0037's NamedDestination firing
  behavior (the CDISC disagreement lane), mixed-item-type CodeLists for
  DD0076/77, and — necessarily — DD0072's uncovered VLM branch.

## Minor notes (no verdict impact)

* **N1**: `PmdaP3RulesTest` javadoc says "40 sheet rows authored as 43 rule
  files"; the correct count is **39** (43 files − 4 split legs: DD0057-B/-C,
  DD0076-B, DD0077-B). The crossref header has it right ("43 files / 39 sheet
  rows"). One-word doc fix.
* **N2**: PMDA-DD0134 has `Attribute: null` while its sibling DD0133 and its
  byte-identical CDISC twin 0242 carry `Attribute: "CommentOID"` — reporting
  metadata only, cosmetic inconsistency.
* **N3**: DD0046 (and the not_exists family generally): a present-but-**blank**
  `Domain=""` counts as absent under the engine's deliberate exists/not_exists
  symmetry, so it does not fire although the row says the attribute "must not
  be included". Corpus-wide documented convention; listed for completeness.
* **N4**: crossref "exact-duplicate" wording for DD0076/77 legs — see above.

## Bottom line

**2 rules need changes** — PMDA-DD0103 (behavioral: add the
`Source ∈ {Investigator, Subject}` qualification to the Collected leg, W4) and
PMDA-DD0035 (comment-only: re-point the out-of-reach justification from DD0103
to DD0075, W1). DD0057(-B/-C) blank-text enforcement and PMDA-DD0072-B are
tracked follow-ups requiring an engine/grammar decision, not P3 file edits;
N1/N2/N4 are one-line doc/metadata nits. No severity, version-gate, selector,
or crossref-duplicate error was found anywhere in the batch.

---

## Coordinator resolutions (2026-07-03)

- **W4 (DD0103)** — Collected leg now requires Source ∈ {Investigator,
  Subject} (2.1 spec §5.3.12.3; consistent with DD0035/DD0111).
- **W1 (DD0035)** — comment re-attributed: the absent-DocumentRef shape
  belongs to DD0075 (P5); noted for the P5 brief.
- **W3 (DD0072)** — companion `PMDA-DD0072-B` authored (0155-B mirror).
- **DD0056** — recommendation adopted: carve-out KEPT (spec-conditioned +
  DD0133 consistency).
- **W2 (DD0057 empty TranslatedText)** — DSL backlog: element-text
  non-blankness needs a grammar extension (e.g. `#text`-aware exists);
  Terminal-B item, cross-cutting (CDISC 0060/0061/0062 share it).
- Minor: test javadoc 40→39; DD0134 Attribute field; crossref nit noted.
