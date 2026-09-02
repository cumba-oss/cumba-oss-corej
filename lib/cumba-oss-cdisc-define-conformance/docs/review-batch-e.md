# Review — Batch E (codelists: sheet ids 135–250 codelist cluster)

Adversarial stress-test review (plan §6.2) of the 38 batch-E rules, the
`CodeListStandardAliasCheck` custom class, `BatchERulesTest` and the
`batch-e-*.xml` fixtures, plus the batch's deferral decisions. Reviewer did
not author any of this material.

Ground truth used, in priority order:

1. `/data/cdisc-docs/Define-XML_v2.1_Conformance_Rules.xlsx`, sheet
   `DefineRules` — verbatim rows for all scoped ids (135, 151, 152, 165–167,
   171–178, 180, 182–185, 191, 193–199, 203, 204, 244–246, 249, 250; deferral
   rows 149, 153, 179, 186, 187, 192, 201, 202).
2. `/data/cdisc-docs/Define-xml-2-1.pdf` §5.3.13 (CodeList / EnumeratedItem /
   CodeListItem / ExternalCodeList attribute tables and business rules) and
   `/data/cdisc-docs/Define-xml-2-0.pdf` (2.0 attribute existence).
3. Engine sources: `rule/Condition.java`, `rule/CheckDefinition.java`,
   `rule/PathResolver.java`, `rule/RegexFormats.java`, `eval/RuleEvaluator.java`,
   `checks/CodeListStandardAliasCheck.java`, `tree/ElementNode.java`.

Test run (2026-07-03, this review):
`mvn -P main -pl lib/corej-define-conformance -am test -Dtest='BatchERulesTest'
-Dsurefire.failIfNoSpecifiedTests=false -Dspotless.check=true` →
**Tests run: 4, Failures: 0, Errors: 0** (clean fixture zero findings;
violations fixture exact counts, 0249 = 2; 2.0 version-gate split; message /
xpath spot checks).

## Verdict table

| Rule | Verdict | Evidence (one line) |
|---|---|---|
| 0135 | PASS | `cardinality_at_most CodeListRef max 1` scoped to ItemDef = sheet "no more than one //ItemDef/CodeListRef"; versions/Source_Type match; parent-scope deviation from the sheet's Element column documented. |
| 0151 | PASS | `references CodeListOID → CodeList@OID` = sheet; both versions; message adapted to `${value}` per corpus convention. |
| 0152 | PASS | `references StandardOID → Standard@OID` catches the dangling leg; Type="CT" refinement split to 0152-B, documented (0124-B precedent); 2.1-only correct (2.0 CodeList has no `def:StandardOID` — verified in the 2.0 attribute table). |
| 0152-B | **WARN** | Deref guard verified safe on dangling refs (see trace, W1) — but a *resolving* `def:StandardOID` whose `def:Standard` has **no/blank `Type`** silently escapes both halves of the split. Concrete tightening available. |
| 0165 | PASS | Empty-CodeList leg of the trio; full 2³ combination table verified (see below): trio fires on exactly the invalid combinations. |
| 0165-B | PASS | Enum+Item mixing leg; sheet-verbatim plain text; fire-always `not_exists`-under-violating-guard vehicle documented. |
| 0165-C | PASS | External+items mixing leg; note N1: the (E,C,X)=(1,1,1) triple-mix yields two findings (0165-B and 0165-C) for one sheet violation — every invalid combo is detected, none missed, one double-reported. |
| 0166 | PASS | `exists @OID`; Schema/both; placeholder-to-xpath convention documented. |
| 0167 | PASS | `one_of {Yes}` fires only on a present `def:IsNonStandard` = sheet row 167 (value check only); conditional presence is row 153, deferred (see deferral audit); 2.1-only correct (attribute absent from 2.0). |
| 0171 | PASS | `exists @Name`; Schema/both. |
| 0172 | PASS | `unique_among_siblings Name`: sheet scope "within the set of Codelist elements" = the one MetaDataVersion's CodeLists (Define-XML has a single MDV), and `RuleEvaluator.uniqueAmongSiblings` groups by parent identity — exact; 2.1-only per sheet (the uniqueness business rule is new in 2.1, verified in both spec tables). |
| 0173 | PASS | `exists @DataType`; Schema/both. |
| 0174 | PASS | `one_of [text,float,integer]` case-sensitive; sheet-vs-spec casing conflict ("Integer" in the 2.1 table — verified at §5.3.13; lowercase in the sheet, the 2.0 table, and the ODM datatype vocabulary) resolved to the sheet and documented in the YAML. |
| 0175 | **WARN** | Base pattern `\$?[A-Za-z_][A-Za-z0-9_]{0,30}` (full-match, verified in `RuleEvaluator.regex`) is off by one for numeric formats: SAS allows 32 characters for a numeric format name but the pattern caps un-prefixed names at 31 (W2). Trailing-digit non-enforcement is correctly justified — the 2.1 spec's own examples `SASFormatName="$FMT0049"` / `"$FMT0050"` end in digits (verified in the spec's example listings); sheet's `ItemDef` message slip corrected and documented. |
| 0175-B | PASS | `\$.*` full-match under `when @DataType equals "text"` = the sheet's second sentence verbatim; complementary with the base rule ("$" alone passes B, fails base); guard is case-sensitive on `text` — a `DataType="Text"` document escapes B but is already flagged by 0174 (note N3). |
| 0176 | PASS | Guard `Alias/@Context = 'nci:ExtCodeID' AND ../../../@Context = 'Submission'` implements the sheet's formal Rule verbatim; `../../../` walk CodeList→MDV→Study→ODM verified against `PathResolver`; `def:Context` reachable as bare `Context` (namespace-agnostic tree); 2.1-only correct. |
| 0177 | PASS | `references CommentOID → CommentDef@OID`; 2.1-only correct (no `def:CommentOID` on 2.0 CodeList). Document-wide resolver vs the spec's "same MetaDataVersion" is moot for a single-MDV define (note N4). |
| 0178 | PASS | `one_of {Yes}` on present `def:ExtendedValue`; both-versions correct — `def:ExtendedValue` exists on 2.0 EnumeratedItem (verified in the 2.0 attribute table); conditional rows 186/187 deferred and cross-referenced in the YAML comment. |
| 0180 | PASS | `exists @CodedValue`; Schema/both. |
| 0182 | PASS | `matches_regex format integer` (`[+-]?\d+`, full match) on present Rank; presence is row 183. |
| 0183 | PASS | All-or-none guard `../EnumeratedItem/@Rank exists` = sheet's `$ParentCodeList/EnumeratedItem/@Rank is provided` including self; self-inclusion is harmless (an element carrying Rank trivially passes its own `exists @Rank` check); guard `exists` and check share blank-is-missing semantics (`Condition.matchesClause` attribute branch), so a blank sibling `Rank=""` does not switch the guard on — consistent. |
| 0184 | PASS | OrderNumber twin of 0182. |
| 0185 | PASS | OrderNumber twin of 0183, same guard analysis. |
| 0191 | PASS | `exists @CodedValue` on CodeListItem. |
| 0193 | PASS | Rank integer twin for CodeListItem. |
| 0194 | PASS | All-or-none Rank twin for CodeListItem (`../CodeListItem/@Rank`), same guard analysis as 0183. |
| 0195 | PASS | OrderNumber integer twin for CodeListItem. |
| 0196 | PASS | All-or-none OrderNumber twin for CodeListItem. |
| 0197 | PASS | `one_of {Yes}` on present `def:ExtendedValue`; both-versions correct (2.0 CodeListItem carries the attribute); rows 201/202 deferred and cross-referenced. |
| 0198 | PASS | `cardinality_at_most Decode max 1` scoped to CodeListItem; sheet's Element column says `Decode` but the count is per CodeListItem — re-scope documented. |
| 0199 | PASS | `exists Decode` on CodeListItem. |
| 0203 | PASS | `exists @Dictionary` on ExternalCodeList. |
| 0204 | PASS | `exists @Version` on ExternalCodeList. |
| 0244 | PASS | `cardinality_at_most Description max 1` on CodeList; 2.1-only per sheet (2.0 CodeList child elements exclude Description — verified). |
| 0245 | PASS | Same on EnumeratedItem; the sheet's XPaths-column slip (`…/CodeListItem/EnumeratedItem/Description`) correctly identified and documented — EnumeratedItem is a direct CodeList child per spec §5.3.13.1. |
| 0246 | PASS | Same on CodeListItem. |
| 0249 | PASS | Custom `CodeListStandardAliasCheck` verified: counts **direct** `Alias` children only (`ElementNode.children` is direct-child; nested CodeListItem/EnumeratedItem Aliases — which legally carry item-level C-codes — are not miscounted), exact `Context.equals("nci:ExtCodeID")`, `count == 1` implements the sheet's `count(Alias[@Context='nci:ExtCodeID']) = 1` (both bounds); guard `@StandardOID exists` treats blank as missing = sheet's "non-null"; 2.1-only gate present; "not exactly one" message deviation documented; custom-budget call-out present. Gaps-doc row now stale (W3). |
| 0250 | PASS | `cardinality_at_most ExternalCodeList max 1` on CodeList; both versions; pairs with the 0165 trio ("one ExternalCodelist" upper bound correctly delegated here). |

Versions (sheet col F), Source_Type (col G) and message faithfulness were
checked for all 38 against the verbatim rows: **all match** (2.1-only =
0152/0152-B/0167/0172/0176/0177/0244/0245/0246/0249, exactly the test's
`ONLY_21_IDS`; every message deviation is documented in its YAML comment —
0175's `ItemDef`→CodeList slip fix, 0249's "not exactly one", 0165-B/-C's
companion messages, 0198's `_x000D_` artifact dropped, placeholder-to-xpath
convention elsewhere).

## 0165 trio — exclusive-choice combination table

E = ≥1 EnumeratedItem, C = ≥1 CodeListItem, X = ≥1 ExternalCodeList.
0165: `exists EnumeratedItem when (¬C ∧ ¬X)`; 0165-B: `not_exists CodeListItem
when E`; 0165-C: `not_exists ExternalCodeList when (E ∨ C)`.

| E | C | X | Valid? | 0165 | 0165-B | 0165-C | Net |
|---|---|---|---|---|---|---|---|
| 0 | 0 | 0 | invalid (empty) | **fires** | – | – | 1 finding ✓ |
| 1 | 0 | 0 | valid | guard on, check passes | guard on, passes | guard on, passes | silent ✓ |
| 0 | 1 | 0 | valid | guard off | guard off | guard on, passes | silent ✓ |
| 0 | 0 | 1 | valid | guard off | guard off | guard off | silent ✓ |
| 1 | 1 | 0 | invalid | guard off | **fires** | passes | 1 finding ✓ |
| 1 | 0 | 1 | invalid | guard off | passes | **fires** | 1 finding ✓ |
| 0 | 1 | 1 | invalid | guard off | guard off | **fires** | 1 finding ✓ |
| 1 | 1 | 1 | invalid | guard off | **fires** | **fires** | 2 findings (N1) |

No invalid combination is missed, no valid combination is flagged; the only
blemish is the double report on the triple mix (N1, cosmetic). Multiplicity
of ExternalCodeList (>1, no other children) is 0250's job — correctly out of
the trio.

## Detailed findings

### W1 — 0152-B: Standard-without-Type escapes the 0152/0152-B split

Trace of the guard
`all: [ {path "@StandardOID->Standard@OID/@Type", exists true}, not{… equals "CT"} ]`
through `Condition.matchesClause` / `PathResolver.valuesWithDeref`:

- **Dangling `def:StandardOID`** — deref resolves to nothing, the value set is
  empty, so the `exists: true` clause is **false** and the guard turns the rule
  off. The `not:{equals}` clause never gets to evaluate `not(false)=true` on
  the empty set, because `all` short-circuits on the exists clause. **No
  over-fire on dangling refs** (0152's `references` kind reports them). This
  is the exact empty-set trap the review brief asked about — the authored YAML
  avoids it correctly.
- **Resolves, `Type="CT"`** — exists true, `not(contains "CT")` false → guard
  off → silent ✓.
- **Resolves, `Type="IG"`** — guard true, `not_exists @StandardOID` fails on
  the present attribute → finding ✓ (fixture `CL.V152B` verified).
- **Resolves, `Type` absent or blank** — the deref path yields no (non-blank)
  value → exists clause false → guard off → **silent**, and 0152 is also
  silent because the reference resolves. The sheet ("a def:Standard element
  must exist with … @Type='CT'") is violated, yet neither rule fires.

Impact is bounded: a Type-less `def:Standard` is independently invalid (the
def:Standard batch's Type presence rule), so this only under-fires on
documents already flagged elsewhere. Still, the split can be made exact with
a two-token change — anchor the exists clause on an attribute the resolved
target always carries:

```yaml
when:
  all:
  - path: "@StandardOID->Standard@OID/@OID"   # resolves ⇔ the reference resolves
    exists: true
  - not:
      path: "@StandardOID->Standard@OID/@Type"
      equals: "CT"
```

With that, Type-missing fires (`not(contains "CT")` on the empty Type set =
true) while the dangling case stays gated. Recommended; not blocking.

### W2 — 0175: numeric SAS format names capped one character short

Neither the 2.1 nor the 2.0 spec states a length ("must be a legal SAS
format" is the whole business rule), so SAS's own naming rules are the
reference: a **numeric** format name may be up to **32** characters; a
**character** format name is `$` + up to 31 characters (32 total). The
pattern `\$?[A-Za-z_][A-Za-z0-9_]{0,30}` (full-match) allows 32 with the `$`
(correct) but only **31 without it** — a legal 32-character numeric format
name is flagged as invalid. Exact form if worth fixing:

```
\$[A-Za-z_][A-Za-z0-9_]{0,30}|[A-Za-z_][A-Za-z0-9_]{0,31}
```

Practical impact near zero (32-character format names in a define.xml are
exotic); the author's two documented deviations both check out — the
trailing-digit rule is genuinely contradicted by the spec's own
`$FMT0049`/`$FMT0050` examples, and the sheet message's `ItemDef` is an
obvious copy slip for CodeList.

### W3 — gaps-doc staleness / missing deferral rows (doc change, not a rule)

`docs/define-rules-gaps.md`:

- The DEFINE-XML-0249 row still reads "custom candidate at batch E/P5" —
  batch E has now shipped it as `CodeListStandardAliasCheck`. Mark it
  resolved (the 0155 row shows the convention).
- The batch-E CT deferrals are only partially tracked: **153, 186/187 and
  201/202** are at least cross-referenced in YAML comments (0167, 0178,
  0197), but **179 and 192** (CodedValue must match the published CDISC
  Submission Value) are recorded **nowhere** — no YAML comment, no gaps-doc
  row. Add all seven (153/179/186/187/192/201/202) to the P5 table so the
  CT batch's scope is closed-form. (Also pre-existing: the P5 table's later
  rows carry five cells against a four-column header — formatting drift,
  surfaced not fixed.)

### Notes (no change requested)

- **N1** — trio triple-mix double report (see table). Each finding names a
  real, distinct mixing violation; acceptable.
- **N2** — value-kind blank semantics: `RuleEvaluator.regex`/`oneOf` fire on a
  present-but-**blank** attribute (`Rank=""` → 0182 "not an integer",
  `SASFormatName=""` → 0175, `def:IsNonStandard=""` → 0167), while presence
  kinds and guard `exists` treat blank as missing. This is the corpus-wide
  convention already adjudicated in review-batch-a (0008) — consistent here,
  including the useful corollary that a blank sibling Rank does **not**
  arm the 0183/0185/0194/0196 guards.
- **N3** — 0175-B's guard `equals: "text"` is case-sensitive; `DataType="Text"`
  documents escape the `$`-check but are already invalid per 0174 (lowercase
  enforced). Sheet-faithful.
- **N4** — `references` kinds (0151/0152/0177) resolve document-wide where the
  spec says "same MetaDataVersion"; a conforming define.xml has exactly one
  MDV, so the scopes coincide.
- **N5** — 0172 lets two blank `Name=""` CodeLists count as duplicates (blank
  is a present value to `unique_among_siblings`); both also fire 0171.
  Marginal, consistent with N2.

## Fixture masking analysis

The violations fixture keeps one dedicated violator per rule and the counts
are exact (verified green). Cross-contamination traps were checked and are
handled: `IT.V135`'s two CodeListRefs both resolve (135 fires, 151 stays at
one); `CL.V152`/`CL.V152B` each carry exactly one nci:ExtCodeID Alias (0249
guard is on for both via `def:StandardOID` but count==1 holds — including for
the *dangling* `STD.MISSING`, correct since the sheet conditions on non-null,
not on resolution); `CL.V176` has the Alias but no StandardOID (0249 guard
off); `CL.ENUM`/`CL.ITEM` carry Rank+OrderNumber everywhere so the
all-or-none guards stay off; `CL.V175B`'s `NODOLLAR` passes the base pattern
(only B fires) and `CL.V175`'s float DataType keeps B's guard off.

Untested (not masked — the code paths were verified statically, but no
fixture exercises them): the (E,C,X)=(1,1,1) triple mix; item-level `Alias`
children under a `def:StandardOID` CodeList (would prove 0249's direct-child
counting); a Type-less `def:Standard` behind a resolving StandardOID (the W1
hole); 31/32-character SASFormatName boundary values (the W2 off-by-one); a
2.0-versioned *clean* document (the gate test reuses the 2.1 violations
fixture, which is fine for status assertions). Worth adding alongside the
W1/W2 fixes.

## Deferral audit

| Sheet id | Deferred as | Verdict |
|---|---|---|
| 153 (`def:IsNonStandard` conditional presence) | P5 CT-gated (YAML comment in 0167) | **Defensible, revisit at P5**: the plain-text/message reading ("where CDISC CT is required" for the variable) is CT/IG-gated. But the sheet's formal Rule column — `@def:IsNonStandard must be provided when there is no CodeList/Alias[@Context="nci:ExtCodeID"]` — is fully intra-document and expressible **today** (`exists @IsNonStandard when not:{path Alias/@Context equals nci:ExtCodeID}`); the 2.1 spec's own business rule (§5.3.13: except External codelists, every CodeList needs `def:StandardOID` or `def:IsNonStandard="Yes"`) is likewise intra-document. Record which reading P5 will implement so the CT gate isn't cargo-culted onto a rule that doesn't need it. |
| 179 / 192 (CodedValue = published CDISC Submission Value) | P5 CT-gated — **untracked** (W3) | Rightly deferred (needs the published CT term lists; exactly the `term_in_ct_codelist` shape, `Requires: ct`) — but add the tracking rows. |
| 186 / 201 (`def:ExtendedValue` required when CodedValue is an extension) | P5 CT-gated (YAML comments in 0178/0197) | Rightly deferred: needs published CT membership **and** published extensibility of the referenced codelist. |
| 187 / 202 (`def:ExtendedValue` forbidden when non-extensible) | P5 CT-gated (YAML comments in 0178/0197) | Rightly deferred: the sheet conditions on "[parent CodeList]/Alias[@Name] is non-extensible" — extensibility is a property of the *published* codelist identified by the Alias C-code, **not knowable intra-document** (no define.xml attribute carries it; `def:IsNonStandard` is orthogonal). |
| 149 (CT-required variable must have CodeListRef) | library-gap, recorded in `docs/define-rules-gaps.md` | Correct: needs per-variable CT-requirement designations from the referenced IG standard — CDISC library knowledge, same family as 0067; not a CT-package question, so library-gap (not P5) is the right bucket. |

## Bottom line

Test suite green (4/4); versions, Source_Type and messages faithful across
all 38; the 0165 trio and the 0152/0152-B split hold up under exhaustive
tracing; the one custom check is tight and budget-accounted. Substantive
items: the 0152-B Type-missing escape (W1, cheap exact fix), the 0175 numeric
32-character off-by-one (W2), and gaps-doc bookkeeping (W3, incl. the
untracked 179/192 deferrals).

**2 rules need changes** (DEFINE-XML-0152-B, DEFINE-XML-0175 — both
WARN-level tightenings, neither a sheet contradiction), plus one
documentation update (`define-rules-gaps.md`: stale 0249 row, missing
153/179/186/187/192/201/202 deferral rows).

---

## Coordinator resolutions (2026-07-03)

- **WARN 1 (0152-B)** — guard's first clause re-anchored on the deref target's
  `@OID`: a resolving `def:Standard` lacking `@Type` is now flagged; dangling
  refs remain 0152's finding.
- **WARN 2 (0175)** — pattern split into two alternatives: `$`-prefixed
  character formats ≤31 chars after the `$`, numeric formats ≤32 chars
  (off-by-one fixed).
- **Doc updates** — 179/192 deferral rows added; 0249 row marked shipped;
  153's dual-reading decision recorded for P5.
- Untested-edge notes (triple-mix, item-level Aliases, Type-less Standard,
  31/32-char boundary) → Terminal B hardening list.
