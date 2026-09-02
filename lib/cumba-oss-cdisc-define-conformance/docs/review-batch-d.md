# Adversarial review — Batch D1 + D2, coordinator items, deferral audit (plan §6.2)

Reviewer: independent stress-test agent (did not author any of the scoped files).
Ground truth used: `Define-XML_v2.1_Conformance_Rules.xlsx` (DefineRules sheet, verbatim
rows for every scoped id), `Define-xml-2-1.pdf` + `Define-xml-2-0.pdf` (text-extracted),
and the engine sources (`CheckDefinition`, `Condition`, `RuleEvaluator`, `PathResolver`,
`OidResolver`, `ElementNodeBuilder`).

Test run: `mvn -P main -pl lib/corej-define-conformance -am test
-Dtest='BatchD1RulesTest,BatchD2RulesTest,WhereClauseCrossDatasetCommentCheckTest,BatchCRulesTest'`
→ **BUILD SUCCESS, 15/15 tests green** (D1: 5, D2: 4, custom check: 1, batch C: 5).

## Verdict table

### Straightforward PASSes (grouped)

| Rules | Evidence |
|---|---|
| 0066, 0072 (integer format), 0144 (positive-integer), 0146 (non-negative-integer) | `RegexFormats` patterns full-match, fire on present values only; sheet versions/Source_Type match; 0072 correctly narrowed to `ItemGroupDef/ItemRef` per the sheet XPaths (0066 correctly bare — sheet lists both parents) |
| 0068, 0075, 0077, 0086, 0088, 0161 | one_of enumerations byte-match the sheet rows; enum sets re-verified against spec 2.1 §5.3.10.1 (Comparator, SoftHard) and §4.3.2 (Source: Subject/Investigator/Vendor/Sponsor); value-kinds fire on present values only, presence is the separate sheet row in each case |
| 0069, 0080, 0082, 0087, 0089, 0090, 0137, 0138, 0139, 0164, 0236 | plain attribute-presence `exists`; blank-counts-as-missing consistent with the corpus convention; scopes match sheet XPaths |
| 0085, 0092, 0237 | child-element `exists`; CheckValue Required/one-or-more confirmed at spec 2.1 §5.3.10.2; RangeCheck at §5.3.10.1; ValueListDef/ItemRef per sheet 237 |
| 0074, 0081, 0083, 0091, 0148, 0163 | `references` to MethodDef / WhereClauseDef / CommentDef / ItemDef / ValueListDef by OID via the document index; fire on present values only; conditional-presence halves correctly attributed to sheet ids 73/84/90/242/164 in the YAML comments |
| 0071 | `unique_among_siblings` groups by parent identity — uniqueness scope is exactly one ItemGroupDef; selector `ItemGroupDef/ItemRef` matches the sheet XPaths (ItemGroupDef only) |
| 0162, 0169, 0235, 0243 | `cardinality_at_most 1`; sheet "no more than one" rows; 0235/0243 correctly 2.1-gated per sheet col F |
| 0079 | sheet row says "def:WhereClauseDef child" but the child an ItemRef carries is `def:WhereClauseRef`; spec 2.1 §5.3.9 confirms verbatim: "Not allowed as a child element of an ItemRef element if the parent node is a def:ItemGroupDef element. It will be considered non-conforming." Author's correction to the real child name is right; scope `ItemGroupDef/ItemRef` leaves value-list ItemRefs (where it is *required*) untouched |
| 0141, 0145 | conditional `exists` guards (`@DataType` oneOf text/integer/float; equals float). Sheet 145's `@DateType` typo correctly identified. Guard-fails-on-missing-DataType = out of reach, presence of DataType is 0139 — correct layering |
| 0143 | `not_exists @Length` under `not(DataType oneOf …)`. The missing-DataType case ("fires too") is *literal* sheet semantics — "@Length must not be present when @DataType **not in** (…)"; a missing DataType is not in the list. Also fires 0139 separately; no contradiction. Present-but-blank Length counts absent (documented). PASS with note |
| 0159 | exists `def:DocumentRef` under Type=Collected ∧ Source∈{Investigator,Subject}; spec 2.1 §5.3.12.3 business rule confirmed verbatim. The 0159/0159-B split is clean: missing-ref half here, wrong-ref half in the companion |
| 0159-B | compare `DocumentRef/@leafID` vs `../../AnnotatedCRF/DocumentRef/@leafID` (Origin→ItemDef→MetaDataVersion — path arity verified). Skip-on-missing on both sides is right (no DocumentRef ⇒ 0159; leafID presence is its own row). First-match-per-side deviation documented in the YAML |
| 0160 / 0160-B | version split verified against both PDFs: 2.1 set = Collected, Derived, Assigned, Protocol, Predecessor, Not Available (spec 2.1 §4.3.2 inline, non-extensible — no CT gate needed); 2.0 set = CRF, Derived, Assigned, Protocol, eDT, Predecessor (2.0 spec def:Origin table, "Allowable Values" — confirmed in the extracted text). Disjoint terms ⇒ split mandatory; union of the two rules' Applicable_Versions = the sheet's "Both". Correct |
| 0238 | all-or-none per parent: guard `../ItemRef/@OrderNumber exists:true` (any sibling, self included, non-blank) + `exists @OrderNumber` ⇒ exactly the have-nots in a mixed parent fire; all-none parents silent. Matches the plain-text row ("at least one"); the sheet Rule column's `count(...) > 1` is a sheet-internal inconsistency and the plain text is the right pick. Bare `ItemRef` scope matches the row naming both parents |
| 0055 | scoping to `ItemDef[@Name="QVAL"]` follows the sheet's own formal Rule column (`def:ValueListRef must be provided for //ItemDef[@OID, @Name="QVAL"]`) over its Element/XPaths columns — the right call: the def:ValueListRef lives on the ItemDef, and an *absent* ValueListDef could never be the finding carrier. QVAL is SUPP--only per SDTM; value-level ItemDefs carry QNAM values as Name, not "QVAL", so no over-reach. Non-SUPP false-positive risk requires a sponsor to name a non-SUPP variable QVAL — out of plausible reach |
| 0073 | deref guard traced through `PathResolver.valuesWithDeref`: `@ItemOID->ItemDef@OID` (index jump) → `Origin` (child step, prefix-agnostic — matches `def:Origin` since the tree stores local names) → `@Type` terminal attr read. Any-match over multiple def:Origins is correct: 2.1 allows "Zero or more" Origins and the spec business rule (§5.3.12.3: "If the variable or value is derived, the corresponding ItemRef must include a MethodOID") demands MethodOID if *any* origin is Derived. Direction is sheet-exact (this direction only; the converse is not row 73). Dangling ItemOID ⇒ guard resolve empty ⇒ out of reach (= sheet 65's defect). Bare ItemRef scope covers both parents per sheet XPaths and the spec's "variable **or value**" wording |
| 0070 | fire-always vehicle verified: guard = Context=Submission (`../../../@Context`, arity ItemGroupDef→MDV→Study→ODM correct) ∧ `ItemRef/@KeySequence exists:false` (no ItemRef carries a non-blank KeySequence — per-IG aggregate, exactly the sheet's `count(...) >= 1` negated) ∧ `@OID exists:true`; `not_exists @OID` then fires exactly once per keyless dataset. No over-fire (any one non-blank KeySequence turns the guard off); under-fire only for an OID-less ItemGroupDef (its own rule). Spec 2.1 ItemGroupDef/ItemRef KeySequence row confirms: "In the context of a regulatory submission, a key must be defined for each data set". 2.1-only per sheet ✓ |
| 0242 | vehicle verified: `@HasNoData=Yes` ∧ ItemOID resolves ∧ deref ItemDef `@CommentOID exists:false` (blank counts missing — consistent). Fires once per offending *reference* rather than once per ItemDef (documented; over-count only when several HasNoData=Yes ItemRefs share one ItemDef — rare and arguably per-reference is more informative). Direction matches the sheet ("at least one ItemRef… =Yes") |
| 0147 | vehicle verified: Context=Submission (arity 4 ⇒ ODM ✓) ∧ `../@ArchiveLocationID->leaf@ID/@href` full-match `(?i).*\.xpt` ∧ ItemOID resolves ∧ resolved ItemDef lacks SASFieldName. Matches the sheet condition (xpt-extension leaf) and spec 2.1 ("Required in the context of a regulatory submission when data are submitted as SAS XPT files"). Finding on the ItemRef is a documented deviation; ItemGroupDef/ItemRef scope matches "parent ItemGroupDef" in the sheet. Sheet says Source_Type Schema (odd but faithful) ✓ |
| 0084 rule + custom check | see detailed section below — PASS (check logic sound; conservative criterion mathematically correct for "provably ≥ 2 datasets"); the *test* gets a WARN |
| 0239-B | correct missing-half companion: sheet/spec ("is included and is set to Yes"; ODM defaults absent to "No") demand presence, value-kinds skip absent ⇒ the exists-half is necessary; guard `Class/@Name = TRIAL DESIGN` (2.1 child element) ✓; 2.1-only ✓; guard-fixture IG.TDNONE fires it, 0239 stays silent — exactly the intended split |
| 0124-B | correct refinement companion: fires iff the reference *resolves* and target `@Type` present ∧ ≠ "IG". A Standard with missing/blank Type is out of reach (its own presence row) — right layering with 0124 (dangling) and no double-fire. Guard-fixture IG.NONIG (Type="CT") fires it; IG.IGOK decoy silent ✓ |
| guardFixturePinsSubmissionGuardsAndCompanionRules + batch-c-guards-21.xml | pins both directions: Submission-guarded rules silent under Context="Other" despite violating shapes (over-fire direction), and both companions fire exactly once on dedicated violators with compliant decoy (IG.IGOK) and no-attribute decoy (IG.BARE). Assertions check id-specific xpaths. Sound |

Version (col F) and Source_Type audit: **all 58 scoped rule files match their sheet rows
exactly** (0160+0160-B union = "Both"; 0159-B/0239-B/0124-B inherit their row's values).
Messages are faithful modulo the documented `${value}`-only template limitation (parent
placeholders carried by the finding xpath) — no meaning drift found.

### WARN findings (detailed below)

| Rule / item | Verdict |
|---|---|
| DEFINE-XML-0140 | WARN — documented 2.0 leniency (`intervalDatetime`) is real; recommend a 0160-style version split |
| DEFINE-XML-0154 | WARN — literal sheet Rule over-fires on spec-conforming either-level Origin placement (value-level ItemDefs) |
| DEFINE-XML-0155 | WARN — Origin/ValueListRef presence proxied via `@Type` / `@ValueListOID`; two documented over-fire corners, neither pinned by a test |
| DEFINE-XML-0168 | WARN — "2.0" in Applicable_Versions is dead code (2.0 has no `ODM/@def:Context`); TranslatedText-text proxy over-fires overlap 61/62 (documented) |
| DEFINE-XML-0142 | PASS w/ note — trace fully verified incl. `..`-after-deref; first-match under-fire on shared value lists is real, documented, and flagged for the DSL discussion |
| WhereClauseCrossDatasetCommentCheckTest | WARN — assertion hole: `contains("WC.CROSS")` also matches `WC.CROSSOK` |
| BatchD2RulesTest javadoc | WARN — stale: says sheet id 84 is deferred; 0084 is shipped |
| Deferral 0067 | PASS — library-gap correct |
| Deferral 0076 | PASS — data-gap correct (validator reads file existence, not xpt content) |
| Deferral 0155-branch-2 | WARN — "grammar-gap" claim is too strong; branch 2 appears encodable with the already-shipped 0142-style reverse join |

FAIL: **none.**

## Detailed findings

### W1 — DEFINE-XML-0140: 2.0 accepts `intervalDatetime` (leniency; recommend version split)

Verified against both PDFs: the 2.1 §5.3.12 DataType table carries all 12 values
including `intervalDatetime`; the 2.0 §4.2.1 table ends at `durationDatetime` —
`intervalDatetime` does not occur anywhere in the 2.0 spec text. So on a 2.0 document
the shipped list wrongly accepts `intervalDatetime` (under-fire, one value). The YAML
documents this as sheet-faithful (the sheet binds one list to Both), which is a
defensible reading — but the corpus already established the *other* precedent on the
same sheet pathology: 0160/0160-B split a Both-row whose per-version term sets differ.
0140's sets differ too (strict subset rather than disjoint — misvalidation in one
direction instead of two, hence WARN not FAIL).

**Recommended correction:** split as 0140 (2.1, 12 values) + 0140-B (2.0, 11 values), or
add an explicit acceptance note to the gaps/review docs stating why subset-leniency is
tolerated where disjoint sets were not.

### W2 — DEFINE-XML-0154: over-fires on spec-conforming value-level metadata

The sheet's formal Rule ("def:Origin must be provided when there is no
//def:ItemDef/def:ValueListRef element and ODM/@def:Context='Submission'") is
implemented verbatim over *every* ItemDef. But the 2.1 spec (p. 59 conformance table
and §5.3.12.3 business rules) says Origin placement is at the sponsor's discretion —
variable level **or** value level — and when the variable-level ItemDef carries the
def:Origin, the value-level ItemDefs legitimately have neither def:Origin nor
def:ValueListRef. Every such value-level ItemDef fires 0154 on a fully conforming
document. This is a systematic false positive on the most common real-world VLM shape,
not an exotic corner. The YAML documents it ("sheet-faithful by design"), and the
sheet row *is* priority-1 ground truth — hence WARN, not FAIL — but the clean fixture
dodges the case (all its value-level ItemDefs carry Origins), so the over-fire is
invisible to the test suite.

**Recommended correction:** either (a) exempt value-level ItemDefs whose variable-level
ItemDef carries a def:Origin (needs a reverse join from the ItemDef to the ValueListDef
ItemRef referencing it — currently ambiguous in the deref grammar because
`@OID->ItemRef@ItemOID` may resolve the ItemGroupDef-side ItemRef first; if inexpressible,
record it as a known-over-fire in the gaps doc with a fixture pin), or (b) at minimum add
a violating-shaped *clean-side* decoy (variable-level Origin + Origin-less VLM item) to a
fixture with an explicit expected-finding entry so the behavior is pinned and visible.

### W3 — DEFINE-XML-0155: proxy semantics have two untested over-fire corners

Branch-1 encoding (vehicle + deref) is correct for the plain case, and the fixture
proves fire/no-fire. But both presence tests are proxies:

1. `…/Origin/@Type exists:false` proxies "no def:Origin". An IG-referenced ItemDef with
   `<def:Origin Source="Sponsor"/>` (Type missing — a 0158 violation) fires 0155 with the
   message "There must be a def:Origin defined…" even though a def:Origin **is** present.
   Documented in the YAML; the fixture's only Type-less Origin (IT.NOTYPE) is deliberately
   *unreferenced*, so no test pins this.
2. `…/ValueListRef/@ValueListOID exists:false` proxies "no def:ValueListRef". An
   ItemDef with `<def:ValueListRef/>` (no ValueListOID — a 0164 violation) and no Origin
   is treated as branch-1 and fires, although the sheet routes that ItemDef to branch 2.

Both only occur on already-broken documents (0158/0164 fire alongside), so severity is
low. Root cause is a genuine grammar limitation: an element-terminal deref path reads
*text*, not element presence, so `…/Origin` cannot be existence-tested directly.

**Recommended correction:** add the two shapes to a fixture with explicit expected counts
(pin the deviation), and record the element-presence-after-deref limitation in the gaps
doc / DSL-discussion list — it is the same root cause as 0168's TranslatedText proxy.

### W4 — DEFINE-XML-0168: dead "2.0" version binding + Description proxy

Confirmed against the 2.0 PDF: `def:Context` does not exist in Define-XML 2.0 (zero
occurrences in the extracted text; the ODM-attribute table has no such row). The sheet
binds row 168 to Both **and** conditions on `ODM/@def:Context='Submission'` — an internal
sheet contradiction. As authored, on a 2.0 document the rule executes and silently never
fires; `Applicable_Versions: ["2.0","2.1"]` therefore claims coverage that cannot exist.
Behavior is harmless (no false positives) and the YAML documents it; the D1 2.0 fixture
even states it. Secondary (documented) deviation: Description presence is proxied by
TranslatedText text, so a Description whose TranslatedText is empty fires 0168 alongside
sheet 61/62 — double-reporting, consistent with `ElementNodeBuilder`'s trim-to-null text.

**Recommended correction:** none mandatory. Cleanest would be `["2.1"]` with a comment
citing the sheet contradiction (a 2.0 evaluation is pure no-op work), but keeping the
sheet's own binding is defensible — decide once and record it, since 0154 (2.1-only per
sheet) and 0168 (Both per sheet) currently look inconsistent to a reader.

### N1 — DEFINE-XML-0142: engine trace verified; accepted first-match caveat

Full trace against `PathResolver.valuesWithDeref`: segment loop handles `..` **after** a
deref step (explicit `else if ("..".equals(step))` branch after `current` was replaced by
the deref target), so `../@OID->ValueListRef@ValueListOID/../@Length` resolves
ValueListDef → (first) ValueListRef carrying that OID → parent ItemDef → Length.
`OidResolver` uses `putIfAbsent` — first node in document order wins — confirming the
YAML's documented first-match deviation: with one value list shared by several variables,
only the first ValueListRef's ItemDef Length is compared (possible under-fire against
later, shorter variables; never an over-fire *for the compared pair*... note it can also
**over**-fire in the inverse sharing case where the first referrer is the shortest — the
YAML comment says "under-fire" but a shared list compared against the wrong parent can
mis-fire in either direction; the deviation note should say "first-referrer-only", not
"under-fire"). Direction is sheet-correct: left = value-level Length, right =
variable-level Length, `less_or_equal`. Missing/non-numeric on either side skips
(presence 141, format 144). Fixture proves 200 vs 20 fires and 10 vs 20 stays silent.
No change required beyond sharpening the comment's "under-fire" wording; keep on the
DSL-discussion list.

### W5 — 0084 custom check PASS; its test has an assertion hole

`WhereClauseCrossDatasetCommentCheck` logic verified: distinct RangeCheck ItemOIDs →
containing-dataset sets via ItemGroupDef/ItemRef membership → violation iff the
intersection of all non-empty sets is empty (⇔ no single dataset can host every
referenced item ⇔ *provably* ≥ 2 datasets involved) and CommentOID absent-or-blank.
The conservative criterion is the right resolution of the sheet ambiguity (an ItemDef
legally shared across datasets must not produce false positives) and is documented on
the class. Blank CommentOID counts as missing — consistent with corpus presence
semantics. Guard-less document-wide WhereClauseDef scope matches the sheet XPaths.
Items referenced by no ItemGroupDef (e.g. VLM-only) are ignored — documented, and
correct layering (dangling ItemOIDs are 0091's job).

The **test**, however: `assertEquals(1, findings.size())` +
`assertTrue(xpath.contains("WC.CROSS"))` — `"WC.CROSSOK".contains("WC.CROSS")` is true,
and the third assertion (filter `!contains("WC.CROSS")` is empty) has the same blind
spot. A polarity regression that fired on WC.CROSSOK *instead of* WC.CROSS would pass
this test verbatim.

**Recommended correction:** assert `xpath.contains("WC.CROSS'")` / equals on the extracted
OID, or `contains("@OID='WC.CROSS'")`, and add a negative `assertFalse(...CROSSOK...)`.

### W6 — BatchD2RulesTest javadoc is stale

Class javadoc: "Sheet id 84 … is deferred — it needs a custom reverse-membership check".
0084 has since been shipped (custom kind, coordinator batch) and is tested elsewhere.
One-line comment fix.

### Deferral audit

- **0067 (library-gap): PASS.** Core="Req" designations live in the SDTMIG/SENDIG
  standards, not in any Define-XML content; nothing in the document can decide it.
  Consistent with the 0263/0265 category.
- **0076 (data-gap): PASS.** "Variable has no data values" requires reading dataset
  content; the validator's only folder-shaped input (`Requires: folder`) is file
  existence (`fileExists` in `RuleEvaluator`), by design. Correctly recorded as a new
  category rather than silently skipped.
- **0155-branch-2 (grammar-gap): WARN — classification arguable.** The stated blocker
  ("universal quantifier over a dereferenced node set") dissolves if the quantifier is
  flipped by re-scoping, exactly as shipped rules already do: scope
  `ValueListDef/ItemRef`, guard = this item's ItemDef lacks Origin
  (`@ItemOID->ItemDef@OID/Origin/@Type exists:false`) ∧ the variable-level ItemDef
  reached by the 0142-style reverse join lacks Origin
  (`../@OID->ValueListRef@ValueListOID/../Origin/@Type exists:false`) ∧ the value list
  is actually referenced (`../@OID->ValueListRef@ValueListOID/../@OID exists:true`),
  vehicle `not_exists @ItemOID`. That fires once per Origin-less value item under an
  Origin-less variable — precisely a branch-2 violation. It inherits the same
  first-referrer and Type-proxy caveats 0142/0155 already ship with, so deferral is
  only justified if those caveats are deemed blocking here; the gaps-doc rationale
  should say that, not "inexpressible".

### Fixture masking analysis

- **D1/D2 violations fixtures:** the tests assert an exact count for **every** rule in
  the batch (not just the spotlighted ones), which is the strongest anti-masking shape
  available — any decoy accidentally violating a sibling rule breaks its count. Spot
  checks confirmed the tricky non-interactions: IG.KS (0070) has no OrderNumbers so 238
  stays silent; IG.ON's KeySequence=1 keeps 0070 silent; IT.NOTYPE (0158) is
  unreferenced so 0155 stays at one; IT.AEDY's dangling MT.MISSING satisfies 0073's
  exists while firing 0074; the OID-less ValueListDef carries an ItemRef so 0237 stays
  at one; VL.A/VL.B double ValueListRef (0162) resolves both so 0163 stays at one.
- **Version-gate tests** double-use the 2.1 violations fixture under "2.0" and assert
  both the gated set and that the non-gated rules still fire — good.
- **Gaps not covered by any fixture** (all noted above): 0154's spec-conforming
  VLM-without-Origin shape, 0155's two proxy corners, 0140 under an actual 2.0 document
  carrying `intervalDatetime` (the 2.0 fixture has no DataType decoys), and the 0084
  test's WC.CROSSOK blind spot.
- Minor: BatchD1's 0147 location assertion (`xpath.contains("ItemRef")`) is satisfied by
  any ItemRef; the exact-count regime makes this near-harmless.

## Summary

- All 15 scoped tests pass; versions/Source_Type/messages faithful across all 58 files.
- No FAILs. The five vehicle rules (0070/0147/0155/0168/0242) are correctly encoded
  guard-wise; 0073 and 0142's deref paths verified against `PathResolver` line by line
  (`..`-after-deref supported; `OidResolver` is first-match by `putIfAbsent`).
- Deferrals 0067/0076 correctly classified; 0155-branch-2's "grammar-gap" label is
  overstated (encodable via the shipped reverse-join idiom, with known caveats).

**3 rules need changes** (WARN-level refinements, no FAILs): DEFINE-XML-0140 (version
split or explicit subset-leniency acceptance), DEFINE-XML-0154 (over-fire on conforming
either-level Origin — mitigate or pin), DEFINE-XML-0155 (pin the two proxy corners,
sharpen the deviation note) — plus 3 non-rule items: the gaps-doc 0155-branch-2
rationale, the 0084 test assertion hole, and the stale BatchD2RulesTest javadoc.

---

## Coordinator resolutions (2026-07-03)

- **WARN 1 (0140)** — version split adopted (0160-precedent): 0140 → 2.1-only
  (12 values), new 0140-B → 2.0-only (11 values, no intervalDatetime).
- **WARN 2 (0154)** — converted to a custom check
  (`checks/VariableLevelOriginCheck`, budget 2/15): Origin-less,
  ValueListRef-less ItemDefs are exempt when they are value-level members
  (spec p. 59 either-level placement); their obligation moves to 0155/0155-B.
- **WARN 3 (0155)** — comment now names all three caveats incl.
  first-referrer-only; **branch 2 SHIPPED as DEFINE-XML-0155-B** per the
  review's own deferral verdict (0142-style reverse join); gaps-doc row
  marked resolved.
- **Non-rule items** — 0084 test assertions quote-delimited ('WC.CROSS');
  stale D2 javadoc fixed; coordinator test `DReviewResolutionsTest` pins the
  0154 exemption, 0155-B both directions, and the 0140/0140-B gates.
