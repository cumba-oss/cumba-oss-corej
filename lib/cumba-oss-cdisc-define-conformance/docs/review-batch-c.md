# Batch C adversarial review (plan §6.2)

Reviewer: independent stress-test agent (did not author the batch).
Scope: the 29 batch-C rule YAMLs (`DEFINE-XML-0057, 0093, 0094, 0104,
0108–0110, 0112–0129, 0239–0241, 0260`), `BatchCRulesTest.java`, the
`batch-c-*.xml` fixtures, and the batch's deferrals (105, 106, 251, 252 —
deferral *decision* only, the rules themselves are being authored by a
separate agent and are out of scope here — and 265).

Ground truth used, in priority order: the verbatim `DefineRules` sheet rows
(`/data/cdisc-docs/Define-XML_v2.1_Conformance_Rules.xlsx`, all 34 scoped rows
re-extracted via openpyxl), the Define-XML 2.1 spec
(`Define-xml-2-1.pdf` §5.3.11 pp. 76–78, §4.2 pp. 23–28, §4.9 p. 57), and the
engine sources (`CheckDefinition`, `Condition` incl. the new deref-aware
`matches(node, resolver)`, `RuleEvaluator`, `PathResolver`, `OidResolver`,
`ElementNodeBuilder`).

Test run performed in the worktree (after waiting out a concurrent
`clean verify` by the deref-batch agent — this worktree is shared):
`mvn -P main -pl lib/corej-define-conformance -am test -Dtest=BatchCRulesTest
-Dsurefire.failIfNoSpecifiedTests=false` —
**Tests run: 4, Failures: 0, Errors: 0, BUILD SUCCESS.** The clean fixture
really passes all 29; the violations fixture really fires each rule exactly
once (including the two documented double-duty datasets).

## Verdict

**1 rule needs changes: 0239** (under-fires on the absent-attribute case —
the sheet's verbatim "must be 'Yes'" combined with the ODM/spec default
`IsReferenceData = No` makes an *omitted* attribute on a TRIAL DESIGN dataset
a real violation the rule cannot see). Additionally: one recommended
**companion rule** for 0124 (the `Type="IG"` refinement is now expressible
with when-deref — no change to 0124 itself), and one recommended **test
addition** (no fixture anywhere proves the Submission guard of 0093 and its
guard-family). Everything else passes on firing condition, versions, source
type, message, and anchoring.

## Rule-by-rule table

Columns: (a) fires exactly on the sheet condition, (b) Applicable_Versions
vs sheet col F, (c) Source_Type verbatim, (d) Message fidelity, (e)
selector/guard anchoring, (f) tests.

| Rule | a | b | c | d | e | f | Evidence (one line) |
|------|---|---|---|---|---|---|---------------------|
| 0057 | PASS | PASS ("2.1 only") | PASS (Specification) | PASS | PASS | PASS | `exists Description when @IsNonStandard equals "Yes"` = sheet D verbatim; fires once on IG.NSNODESC; guard correctly does not trip on `IsNonStandard="No"`. |
| 0093 | PASS | PASS ("2.1 only") | PASS | PASS | PASS | **WARN (F1)** | `exists ItemGroupDef` on `MetaDataVersion`, guard `../../@Context equals "Submission"` — MDV→Study→ODM is exactly two `..` steps, `def:` prefix stripped by the tree; fires once on MDV.EMPTY; but no test anywhere proves the guard (see F1). |
| 0094 | PASS | PASS (Both) | PASS (Schema) | PASS (N5-style `[@Name]` drop, batch-A convention) | PASS | PASS | `exists @OID`; fires once on the OID-less NOOID dataset. |
| 0104 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `exists @Name`; fires once on IG.NONAME; 0110 correctly skips it (compare's either-side-missing rule). |
| 0108 | PASS | PASS ("2.1 only") | PASS | PASS | PASS | PASS (F1 family) | Guard `../../../@Context` = IGD→MDV→Study→ODM, three `..` steps, correct; fires once on IG.NOSAS; 109/110/112 all value-skip the absent attribute. |
| 0109 | PASS (Q1) | PASS (Both) | PASS (Specification) | PASS | PASS | PASS (note T2) | Full-match regex (engine uses `matcher().matches()`, so the unanchored-looking pattern cannot partial-match); pattern is a defensible spec import — see Q1. |
| 0110 | PASS (Q2) | PASS (Both) | PASS | PASS (note N2) | PASS | PASS | Case-sensitive `compare @SASDatasetName vs @Name`; sheet says "must match" with no case wording — exact equality is the default faithful reading, and the spec independently pins both sides to the same upper-case value (Q2); fires once (AEX vs AE), proven under both 2.1 and 2.0. |
| 0112 | PASS (Q3) | PASS (Both) | PASS | PASS | PASS | PASS | `compare` with deref `@ArchiveLocationID->leaf@ID/@href`, `file-basename`, case-insensitive — all three choices are the only reading that doesn't flag every conformant define (Q3); fires once (EG vs egg); skips cleanly when the attribute is absent or dangling (deref resolves nothing ⇒ right side empty ⇒ skip). |
| 0113 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `exists @Repeating`; fires once on IG.NOREP. |
| 0114 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `one_of {Yes, No}` case-sensitive (sheet quotes exact tokens); fires once on `Repeating="Maybe"`. |
| 0115 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `one_of {Yes, No}`; fires once on `IsReferenceData="Unknown"`; 0260's guard (`equals "Yes"`) correctly stays silent on it. |
| 0116 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `one_of {Tabulation, Analysis}`; fires once on `Purpose="Bogus"`. |
| 0117 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `exists @Purpose`; fires once on IG.NOPURP (0116 value-skips). |
| 0118 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `exists @Structure` — bare-local-name convention (`def:` is a namespace matter in this tree) is established batch-wide and resolves correctly. |
| 0119 | PASS (Q7) | PASS ("2.1 only") | PASS | PASS | PASS | PASS (F1 family) | Guard `Context=Submission ∧ not(@HasNoData equals "Yes")` matches sheet D **verbatim** ("either … present with value 'Yes' or …") and spec p. 77 ("does not include def:HasNoData=\"Yes\"") — deliberately stricter than sheet C's loose "not present"; see Q7 for the pair analysis. |
| 0120 | PASS (N3) | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `references ArchiveLocationID → leaf@ID`; fires once on LF.MISSING; the same-ItemGroupDef containment nuance is a documented, bounded under-fire (N3). |
| 0121 | PASS (Q4) | PASS ("2.1 only") | PASS | PASS (N4) | PASS | PASS | `not_exists @IsNonStandard when @StandardOID exists` = sheet D verbatim; sheet's Rule Message cell is empty — the composed message is documented in-file (N4); fires once on IG.BOTHSTD. |
| 0122 | PASS (Q4) | PASS ("2.1 only") | PASS | PASS | PASS | PASS | `exists @IsNonStandard when @StandardOID exists:false` = sheet D verbatim; fires once on IG.NOSTD; correctly passes IG.BADNS (attribute present, wrong value is 0123's job). |
| 0123 | PASS (Q4) | PASS ("2.1 only") | PASS (Schema) | PASS | PASS | PASS | `one_of {Yes}` unguarded = sheet D ("The value of @def:IsNonStandard must be 'Yes'", no condition); fires once on `IsNonStandard="No"`. |
| 0124 | **WARN (F2)** | PASS ("2.1 only") | PASS (Specification) | PASS | PASS | PASS | Dangling-reference half is correct and fires once on STD.MISSING; the `Type="IG"` refinement is documented as unenforced — but it **is now expressible** with when-deref; concrete companion shape in F2. |
| 0125 | PASS (Q7) | PASS ("2.1 only") | PASS | PASS | PASS | PASS (F1 family) | `exists @HasNoData when Context=Submission ∧ @ArchiveLocationID exists:false` = sheet D verbatim; pair analysis with 0119 in Q7 — no deadlock, intended overlap. |
| 0126 | PASS | PASS ("2.1 only") | PASS (Schema) | PASS | PASS | PASS | `one_of {Yes}`; fires once on `HasNoData="No"` (that dataset carries an ArchiveLocationID so 0119/0125 stay silent — good isolation). |
| 0127 | PASS (N6) | PASS (Both) | PASS (Specification) | PASS | PASS | PASS | `references CommentOID → CommentDef@OID` document-wide; sheet D is document-wide too ("A def:CommentDef element must exist with …"); the spec's stricter "same MetaDataVersion" business rule is a sheet-vs-spec delta resolved in the sheet's favour, same as batch-A 0030 (N6); fires once on COM.MISSING. |
| 0128 | PASS | PASS ("Both 2.0 and 2.1" — sheet F verbatim, faithfully kept despite the 2.1-flavoured `def:Context` guard) | PASS (Schema) | PASS | PASS | PASS (F1 family) | `exists Description when Context=Submission`; fires once on IG.NSNODESC (documented double-duty with 0057). |
| 0129 | PASS | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `cardinality_at_most Description ≤ 1`; fires once on the two-Description dataset; 0128 correctly passes it. |
| 0239 | **FAIL (F3)** | PASS ("2.1 only") | PASS (Specification) | PASS (sheet's stray `ItemRef[@ItemOID]` artifact reasonably dropped) | PASS (guard `Class/@Name equals "TRIAL DESIGN"` resolves `def:Class` correctly) | PASS for what it does | Present-but-not-Yes fires (IG.TDNOREF) — but an **absent** IsReferenceData on a TRIAL DESIGN dataset never fires, and per ODM/spec that absence *means* "No" (F3). |
| 0240 | PASS | PASS ("2.1 only") | PASS | PASS | PASS | PASS | `exists @CommentOID when @HasNoData equals "Yes"` = sheet D verbatim; the spec's slightly wider "if def:HasNoData is provided" (p. 77) collapses to the same thing because 0126 forbids any value but "Yes"; fires once on IG.NODATA. |
| 0241 | PASS (N7) | PASS (Both) | PASS (Schema) | PASS | PASS | PASS | `exists ItemRef`, unconditional per sheet — deliberately also fires on `def:HasNoData="Yes"` datasets, which is correct: variable metadata is still required for empty planned datasets (the clean fixture's IG.ND proves the non-conflict). |
| 0260 | PASS | PASS (Both) | PASS (Specification) | PASS | PASS | PASS | `one_of Repeating {No} when @IsReferenceData equals "Yes"` = sheet D and spec p. 77 business rule verbatim; fires once on IG.REPREF; guard correctly ignores `IsReferenceData="Unknown"`. |

## Answers to the directed questions

### Q1 — 0109 pattern `[A-Z_][A-Z0-9_]{0,7}`: sheet requirement or author's import?

The sheet row is deliberately vague — col C "Non-conforming SAS dataset
name.", col D "Non-conforming SAS Dataset Name.", message "…does not conform
to submission requirements." It names **no** pattern; everything concrete in
the YAML is imported from the spec. That import is sound:

- **8-char limit**: §5.3.11 SASDatasetName business rule "Must conform to SAS
  Transport file naming rules" — XPT v5 member names are at most 8
  characters. The §5.3.11.1 Alias element ("SAS variable or dataset names
  longer than 8 characters" go into an Alias) confirms the attribute itself
  is capped at 8.
- **Upper case**: §5.3.11 is definitional — "The root name is file name in
  upper case without the '.xpt' extension" — plus the continuation business
  bullet (p. 77 top) "If a value is provided, it should be in upper case."
  Lowercase therefore fails the spec's own definition; the pattern's
  rejection of lowercase is the sheet-backed reading, not gold-plating.
- **Split datasets fit**: split names (QSCG/QSCS, SUPPxx, and split-supp
  forms) are all ≤ 8 by SDTMIG naming; digits after the first character are
  allowed by the pattern (`QS36F` etc.).
- **Anchoring**: no `^…$` needed — `RuleEvaluator.regex` uses
  `Pattern.matcher(value).matches()` (full match), so `dm!` or `X DM` cannot
  sneak through on a partial match. Verified in source, and the batch-A 0028
  review already proved the same engine path empirically.

One residual (test-side, not rule-side) note **T2**: the only 0109 violation
any fixture exercises is the *length* arm (`LONGNAME9`); a lowercase
`SASDatasetName` never fires in any test, so a regression that loosened the
pattern to `[A-Za-z_]…` would pass the suite. Optional hardening.

### Q2 — 0110 case-SENSITIVE equals

Sheet D: "The value of @SASDatasetName must match the value @Name." — no case
qualifier. Exact (case-sensitive) equality is the plain reading of "must
match", and the spec closes the loop independently: Name "must be the same as
SASDatasetName if SAS is being used as a transport mechanism" (§5.3.11) while
SASDatasetName is definitionally upper case — so a case-only difference
(`Name="dm"`, `SASDatasetName="DM"`) is a genuine inconsistency on the Name
side, not noise. The YAML's in-file rationale slightly over-claims ("spec
requires both upper-case" — for Name the upper-case requirement is inherited
from the SAS-transport equality, not stated directly), but the behaviour is
right and the contrast comment pointing at 112 is accurate. No change.

### Q3 — 0112 case-INSENSITIVE + file-basename

Sheet D: "@SASDatasetName value must match the filename component of
def:leaf[@ID=@def:ArchiveLocationID]." Faithfulness of the three choices:

- **Deref**: `@ArchiveLocationID->leaf@ID/@href` is exactly the sheet's
  `def:leaf[@ID=@def:ArchiveLocationID]/@href`. Dangling or absent
  ArchiveLocationID ⇒ right side resolves to nothing ⇒ `compare` skips —
  correct division of labour with 0108 (presence) and 0120 (dangling).
- **file-basename** (read from `RuleEvaluator.transform`): normalises `\`→`/`,
  takes the last path segment, strips the **last** extension only.
  `dm.xpt` → `dm` ✓; `folder/dm.xpt`, absolute URLs, and `dm.xpt?v=1` all
  reduce to `dm` ✓; a fragment after the extension (`dm.xpt#page=3`) is
  stripped *with* the extension (lastIndexOf('.') sits before `xpt#page=3`) ✓
  — but a fragment containing a dot (`dm.xpt#sec.1` → `dm.xpt#sec`) or a
  multi-extension href (`dm.xpt.gz` → `dm.xpt`) mismatches and fires. Both
  shapes are themselves non-conformant submission hrefs (a define leaf must
  point at the `.xpt` itself), so the over-fire direction is acceptable and
  arguably desirable. Faithful.
- **Case-insensitive**: required — SASDatasetName is upper case by
  definition while every spec example names the file in lower case (`dm.xpt`,
  `qscg.xpt`; the spec itself even mixes `ae.xpt`/`AE.xpt` on p. 21). A
  case-sensitive compare would flag every conformant define in existence.
  The clean fixture (`DM` vs `dm.xpt`) locks this in: flipping the flag fails
  `cleanFixtureProducesNoFindings`.

### Q4 — 0121/0122/0123 tri-state vs the three sheet rows

Cross-checked verbatim: row 121 "@def:IsNonStandard must not be present when
@def:StandardOID is present." row 122 "@def:IsNonStandard must be present
when @def:StandardOID is not present." row 123 "The value of
@def:IsNonStandard must be 'Yes'." (unconditional). The three YAMLs map
one-to-one, and jointly they tile the 2×3 state space without gaps or
spurious overlaps:

| StandardOID | IsNonStandard | 0121 | 0122 | 0123 |
|---|---|---|---|---|
| present | absent | – | – (guard false) | – (value-kind skips) |
| present | "Yes" | **fires** | – | – |
| present | "No" | **fires** | – | **fires** (both real defects) |
| absent | absent | – | **fires** | – |
| absent | "Yes" | – | – | – |
| absent | "No" | – | – | **fires** |

Also consistent with spec p. 78 (IsNonStandard: allowed value "Yes" only;
"Required … if def:StandardOID is not provided"; "Should not be provided when
def:StandardOID is provided"). Edge: a present-but-**blank**
`IsNonStandard=""` with StandardOID present escapes 0121 (the engine's
blank-counts-missing presence convention, `not_exists` symmetric with
`exists`) but is still caught by 0123 (`""` is a present value ≠ "Yes") — no
silent hole. The 0121 composed message (sheet cell empty) is flagged in-file
and states exactly the sheet's D condition — right call (N4).

### Q7 — 0119/0125 either-or pair mechanics

Truth table under `Context="Submission"` (guards read from the YAMLs):

| ArchiveLocationID | HasNoData | 0119 | 0125 | verdict |
|---|---|---|---|---|
| present | absent | – (target present) | – (guard false) | clean, per both rows |
| absent | "Yes" | – (guard false) | – (target present) | clean, per both rows |
| absent | absent | **fires** | **fires** | both rows' verbatim conditions independently violated — intended overlap, not a deadlock; fixture documents it (IG.NOLOC) |
| present | "Yes" | – (guard false) | – (guard false) | neither fires — correct, nothing is missing |
| absent | "No" | **fires** (not-'Yes' guard passes) | – (HasNoData present) | correct per sheet 119 D ("present with value 'Yes'") — and 0126 independently flags the "No" |

No configuration fires a false pair and none deadlocks. The 0119 guard's
`not(equals "Yes")` (rather than `exists:false`) is the *stronger sheet-D
verbatim* reading and matches spec p. 77 word-for-word ("does not include
def:HasNoData=\"Yes\""). The fixture's inability to separate the two rules
without side effects is real and correctly documented in both the fixture
header and the test javadoc.

### Q5 — 0124 with when-deref (see F2 for the recommendation)

`one_of.attribute` indeed does not take paths (`RuleEvaluator.valueOf` does a
single `node.attribute(...)` lookup — a `x/@y` string would resolve nothing),
and `references` cannot constrain target attributes. But the **guard**
language now can: `RuleEvaluator` line 63 always passes `oidResolver` into
`Condition.matches`, and `Condition.resolve` routes any `->` path through
`PathResolver.valuesWithDeref`. So the refinement is expressible today — as a
companion guard-shaped rule, not as an edit to 0124 (whose dangling-reference
half must keep `references` semantics). Verdict: **keep 0124 as-is; author
the companion** (concrete shape in F2), ideally in the same wave as the
105/106/251/252 deref batch since it is the same gap family the in-file
comment already names.

### Q6 — 0239 present-only firing (see F3)

Sheet row 239 col D verbatim: `@IsReferenceData must be "Yes" when
ItemGroupDef/def:Class/@Name is "TRIAL DESIGN".` — this constrains the
*value*, and per ODM/Define the value of an omitted IsReferenceData **is**
"No": spec p. 77 lists it as `Optional, Default Value: No`, and the spec's
own Trial Design commentary (p. 25, TE example) reads "contains no
subject-level data, so the IsReferenceData attribute **is included and** is
set to Yes." The verbatim text therefore decides against the present-only
convention here: a TRIAL DESIGN dataset that simply omits the attribute is a
violation of row 239 and the rule never fires. This is precisely the most
likely real-world defect shape (the attribute is optional, so sponsors omit
it). The author's in-file comment is honest about the convention and the
missing companion presence row — but a documented under-fire on the dominant
defect is still an under-fire. FAIL; correction options in F3.

### Q8 — 0093 Submission-guard blindness

Confirmed blind spot. `batch-a-context-other-21.xml` exists but
`BatchARulesTest` filters to batch-A ids, so 0093 is never evaluated against
a `Context="Other"` document — and that fixture couldn't prove 0093's guard
anyway (its MDV.O *contains* an ItemGroupDef, so even a guard-less 0093 stays
silent on it). Deleting the `when` block from 0093 entirely would pass the
whole current suite (violations: MDV.EMPTY fires either way; clean: MDV.1 has
ItemGroupDefs). The same guard-deletion blindness covers the whole batch-C
Submission family: 0108, 0119, 0125, 0128. WARN with recommended test — F1.

## Findings

### F1 (WARN — test gap): no fixture proves the batch-C Submission guards

No test evaluates any batch-C rule against a `def:Context="Other"` (or
Context-less) document, so the guards of **0093, 0108, 0119, 0125, 0128** are
unfalsifiable by the current suite: replacing any of them with no guard (or a
tautology) passes all 4 tests. Batch A hit the same finding (its WARNs 3+4)
and fixed it with `contextOtherDisarmsSubmissionGuardsAndSatisfies0016`;
batch C needs the analogue. Recommended: a small
`batch-c-context-other-21.xml` — `def:Context="Other"`, one MetaDataVersion
**without** ItemGroupDef children *(pins 0093)*, plus one ItemGroupDef in a
second MDV lacking SASDatasetName, ArchiveLocationID, HasNoData, and
Description *(pins 0108/0119/0125/0128)* — asserted silent for those five
ids. No YAML change required.

### F2 (WARN — recommended companion rule): 0124's `Type="IG"` refinement is now expressible

Sheet row 124 col D: "A def:Standard element must exist with
def:Standard/@OID = //ItemGroupDef/@def:StandardOID **and with
def:Standard/@Type=\"IG\"**." Spec p. 78 agrees ("StandardOID must match the
OID for a def:Standard element with Type=\"IG\""). 0124's `references` kind
catches the dangling half only; the in-file deviation comment was accurate
when written, but when-deref has since landed and the refinement is
expressible with the current engine, e.g. as a companion rule:

```yaml
Check:
  kind: "not_exists"
  target: "@StandardOID"
  when:
    all:
    - path: "@StandardOID->Standard@OID/@OID"
      exists: true                      # resolves — dangling stays 0124's
    - not:
        path: "@StandardOID->Standard@OID/@Type"
        equals: "IG"                    # …but to a non-IG def:Standard
```

(`not_exists` on a guard-guaranteed-present attribute is the established
fire-always-under-guard shape; the deref-exists clause routes through
`Condition.resolve` → `valuesWithDeref`, non-blank semantics.) Recommend:
keep 0124 exactly as-is, author the companion in the same wave as the
105/106/251/252 deref batch (same gap family, same reviewer), and update
0124's in-file comment to point at it. Alternative *now-fix* (extending
`references` with an optional target-attribute filter, e.g.
`targetWhere: {Type: "IG"}`) would fold both halves into one rule and one
finding — cleaner long-term, but it is an engine change and belongs to the
DSL discussion the comment already flags. Either resolution is acceptable;
doing nothing is not, since the sheet's condition is only half-enforced while
now being fully expressible.

### F3 (FAIL): 0239 never fires on an omitted IsReferenceData

Evidence in Q6. The current `one_of {Yes} when Class/@Name = TRIAL DESIGN`
catches only the explicit `IsReferenceData="No"`/garbage case; the omitted
attribute — which the spec defines as meaning "No" (p. 77 `Default Value:
No`) and which the spec's Trial Design text explicitly says must be "included
and set to Yes" (p. 25) — passes silently. Recommended correction, smallest
first:

1. **Companion presence rule** under the same sheet id (mirroring how the
   0124 companion splits a row):
   `kind: "exists", target: "@IsReferenceData", when: path: "Class/@Name",
   equals: "TRIAL DESIGN"` — together with the existing one_of this exactly
   covers "must be 'Yes'" (absent ⇒ presence rule fires; present-non-Yes ⇒
   value rule fires; present-Yes ⇒ silent). No engine change.
2. Or an engine-level `one_of` flag (`absentFails: true`) treating the
   attribute's documented default as the compared value — bigger hammer,
   touches the corpus-wide value-kind convention; only worth it if more
   defaulted-attribute rows like this surface in batches D/E.

Note a single current-DSL rule cannot do both halves (`exists` cannot see
values; the value kinds cannot see absence; a `not(equals "Yes")`-guarded
`not_exists` catches present-non-Yes but passes the absent case, and the
`exists` variant does the opposite) — the two-rule split in option 1 is the
minimal faithful shape. The violations fixture should then gain a TRIAL
DESIGN dataset with the attribute omitted.

### Informational notes (no change required)

- **N2 (0110/0112 message rendering)**: `compare` renders `${value}` as
  `"left vs right"` (e.g. `AEX vs AE`), so 0110's template reads "…different
  from the value of Name [AEX vs AE]" — the bracket carries both values, not
  just Name's. Slightly off-grammar but information-complete and asserted by
  the test; same pattern as the accepted batch-B N2. Revisit only if
  multi-placeholder templating lands.
- **N3 (0120 containment)**: the document-wide `references` index accepts a
  `def:leaf` living under a *different* ItemGroupDef. In a schema-valid
  document leaf IDs are xs:ID-unique, so the only escaping shape is dataset A
  pointing at dataset B's leaf — a real but rare under-fire, honestly
  documented in-file. If the DSL ever grows a same-scope flag for
  `references`, 0120 is the customer.
- **N4 (0121 message)**: sheet Rule Message cell is genuinely empty (verified
  in the extract); composing from col D is the only option and the YAML
  comment records it.
- **N6 (0127 document-wide)**: sheet D is document-wide; spec p. 77 says
  "same MetaDataVersion". Sheet is priority-1 ground truth — same resolution
  as batch-A 0030. Multi-MDV documents are vanishingly rare in submissions.
- **N7 (0241 vs HasNoData)**: the unconditional ItemRef requirement
  deliberately applies to `def:HasNoData="Yes"` datasets; that matches the
  sheet (no condition) and submission practice (variable metadata is still
  required for empty planned datasets). The clean fixture's IG.ND proves the
  combination is satisfiable.
- **Fixture masking**: beyond the two documented double-duty datasets
  (IG.NOLOC → 0119+0125, IG.NSNODESC → 0057+0128, both forced by rule
  semantics, both explained in the fixture header and test javadoc), every
  violation dataset isolates exactly one rule; I re-derived the full
  29-rule × 26-dataset firing matrix by hand and found no third double-count
  and no accidental suppression (each "neighbouring" rule is de-scoped by an
  explicit device noted in the fixture comments — e.g. LONGNAME9 keeps
  Name = SASDatasetName = href-basename so 110/112 stay silent while 109
  fires). The exactly-once counts double as non-firing proofs against every
  other candidate node. Guard-inversion escapes are largely covered by the
  *clean* fixture (e.g. inverting 0119's `not` would fire on clean IG.ND and
  fail `cleanFixtureProducesNoFindings`); the residual soft spots are F1 and
  the T2 note.
- **T2 (0109 test)**: no fixture exercises the upper-case arm of the 0109
  pattern (only the length arm fires); optional hardening.

## Deferral verdicts

- **105 / 106 / 251 / 252 — deferral was the right call.** All four sheet
  rows condition on the *dereferenced* `def:Standard`'s Name (105: Domain
  required when Standard Name begins "SDTM"/"SEND"; 106: Domain must be
  omitted when it begins "ADaM"; 251: Purpose must be "Tabulation" under
  SDTM*/SEND*; 252: Purpose must be "Analysis" under ADaMIG). That predicate
  lives on another element reachable only through `@def:StandardOID`, with a
  begins-with match — inexpressible in the pre-deref `Condition` grammar, and
  every non-deref approximation is unfaithful: guarding on `@Domain` presence
  or `Purpose` values proxies the standard family through the very attributes
  the rules are supposed to *check*, and a Name-based guard on the
  ItemGroupDef has no data to read. Authoring them broken or approximate
  would have violated the FDA-plan engine-gaps discipline; deferring until
  `Condition.matches(node, resolver)` + `matchesRegex` landed (both now in)
  was correct. The four files now exist in the worktree — authored by the
  separate deref-batch agent, per instruction **not reviewed here**; they
  need their own §6.2 pass, which should also pick up the F2 companion.
- **265 — library-gap classification is correct.** The row requires the
  English `Description/TranslatedText` to match "the one specified in the
  CDISC standard referenced by def:Standard/@Name and def:Standard/@Version"
  — i.e. the IG-published dataset label, an input this validator deliberately
  does not take. Recorded in `docs/define-rules-gaps.md` alongside the
  structurally identical 263 (standards-version catalogue), with the same
  "revisit if a `Requires: library` input is ever added" trigger. Consistent
  and correct.

---

**1 rule needs changes** (0239 — F3), plus one recommended companion rule
(0124 — F2, no edit to the existing file) and one recommended test addition
(F1); 28/29 rules PASS as authored.

---

## Coordinator resolutions (2026-07-03)

- **FAIL (0239)** — companion rule `DEFINE-XML-0239-B` authored (exists-half:
  IsReferenceData ABSENT on a TRIAL DESIGN dataset; 0239 keeps the
  wrong-value half). One-sheet-row-two-rules split, 0013/0254 precedent.
- **WARN (0124)** — companion rule `DEFINE-XML-0124-B` authored per the
  review's sketch: `not_exists @StandardOID` under a when-deref guard
  (`@StandardOID->Standard@OID/@Type` resolves ∧ ≠ "IG"); 0124 keeps the
  dangling-reference half.
- **WARN (0093 guard blindness)** — new fixture `batch-c-guards-21.xml` +
  `guardFixturePinsSubmissionGuardsAndCompanionRules` in BatchCRulesTest:
  Context=Other silence proven for 0093/0108/0119/0125/0128; the same fixture
  exercises both companion rules and a compliant decoy.
- **Test nit (0109 lowercase arm)** — deferred to Terminal B hardening.
