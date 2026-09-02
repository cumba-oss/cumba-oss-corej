# PMDA ↔ CDISC Define-XML rule cross-reference

Per plan §1 (full-mirror policy): every PMDA rule is authored from its own
sheet row and cross-referenced to the CDISC-sheet rule(s) covering the same
ground. A *disagreement* is a difference in condition/scope between the twins
— valuable review material, not a defect. Maintained by the coordinator from
the author batches' returned records.

## Batch P3 (presence rules, 43 files / 39 sheet rows, 2026-07-03)

| PMDA | Relationship | CDISC | Agreement |
|---|---|---|---|
| DD0035 | partial-overlap | 0044 | agree on 2.1; PMDA adds the 2.0 CRF leg |
| DD0037 | partial-overlap | 0048, 0050 | **DISAGREE**: CDISC guards `Type='PhysicalRef'`; PMDA has no Type condition (fires on NamedDestination too) |
| DD0038 | exact-duplicate | 0055 | agree (Name=QVAL proxy) |
| DD0040 | partial-overlap | 0070 | CDISC Submission-gated 2.1-only; PMDA unconditional both versions |
| DD0042 | exact-duplicate | 0073 | agree |
| DD0045/DD0046 | partial-overlap | 0105/0106 | agree on 2.1 deref leg; PMDA adds 2.0 StandardName leg |
| DD0047 | partial-overlap | 0108 | **DISAGREE-ish**: CDISC Submission-gated 2.1; PMDA unconditional both versions |
| DD0054 | partial-overlap | 0131 | **DISAGREE**: 0131 has the IsNonStandard carve-out + Submission gate; PMDA has neither (authored per PMDA row) |
| DD0056 | partial-overlap | 0119 | PMDA sheet unconditional; author adopted 0119's HasNoData carve-out (deviation — stress-review) |
| DD0057(-B/-C) | partial-overlap | 0128, 0210, 0214 | agree; PMDA adds TranslatedText depth |
| DD0058 | partial-overlap | 0168 | agree; CDISC Submission-gated |
| DD0061 | new | — | |
| DD0063 (deferred→P5) | — | 0095 | **DISAGREE on condition**: 0095 = Alias when Domain≠Name; PMDA = Alias when >1 IGD share a Domain |
| DD0068 | exact-duplicate | 0143 | agree |
| DD0069 | new | — | |
| DD0070 | partial-overlap | 0147 | **DISAGREE**: 0147 gated (Submission + .xpt leaf, 2.1); PMDA unconditional both versions |
| DD0072 | partial-overlap | 0154, 0155(+0155-B) | agree on covered branch; PMDA lacks the VLM branch (follow-up: PMDA-DD0072-B) |
| DD0076(-B) | exact-duplicate | 0183 + 0194 | agree |
| DD0077(-B) | exact-duplicate | 0185 + 0196 | agree |
| DD0087/0090/0094/0096/0098/0117 | new | — | ARM cluster; no CDISC ARM rules exist |
| DD0103 | partial-overlap | 0159 | different target (AnnotatedCRF element vs DocumentRef) |
| DD0111 | partial-overlap | 0044 (inverse) | negative leg |
| DD0119 | exact-duplicate | 0035 | agree |
| DD0120/121 | exact-duplicate | 0121/0122 | agree |
| DD0126 | new | — | |
| DD0127 | new | — | |
| DD0128 | partial-overlap | (sheet 153 — P5-deferred, not in corpus) | |
| DD0129 | partial-overlap | 0249 (custom) | agree on presence; 0249 additionally outlaws duplicates |
| DD0133/134 | exact-duplicate | 0240/0242 | agree |
| OD0070/71 | exact-duplicate | 0141/0145 | agree; severity Warning vs Error |
| OD0081 | exact-duplicate | 0165(+B/C) | agree on the empty leg; Warning vs Error |

## Batch P2 (uniqueness / reference integrity / orphans, 38 files / 37 rows, 2026-07-03)

| PMDA | Relationship | CDISC | Agreement |
|---|---|---|---|
| DD0012 | exact-duplicate | 0216 | agree |
| DD0013, DD0083, OD0030/31/32 | subsumed-by | 0230 | PMDA per-element scopes narrower; DD0083 vs 0213 scope difference is per each sheet's own wording |
| DD0015/16/17/18 | exact-duplicate | 0043/0074/0163/0120 | agree (0120's containment deviation shared) |
| DD0041 | exact-duplicate | 0071 | agree |
| DD0062 | exact-duplicate | 0063 | agree |
| DD0071 | union-of | 0030/0083/0127/0148/0177/0256 | agree in aggregate (wildcard) |
| DD0122 | exact-duplicate | 0124(+B) | agree; PMDA row demands no Type refinement |
| DD0125 | exact-duplicate | 0152+0152-B | agree; PMDA folds both halves into one guarded rule |
| DD0131 | partial-overlap | 0172 | **scope disagreement per the sheets** (doc-wide vs per-MDV) — both source-faithful |
| OD0046/OD0048 | exact-duplicate | 0065/0151 | agree |
| DD0067/78/79/80/81/82/139, DD0088/89/91/95/97, OD0022/27/41/42/79(-B), DD0051, DD0014 | new | — | orphan/ARM/uniqueness rows with no CDISC twin |

## Batch P4 (value/format/enum/compare/consistency, 58 files / 37 authored rows + 2 partially-deferred, 2026-07-03)

| PMDA | Relationship | CDISC | Agreement |
|---|---|---|---|
| DD0020B(+B) | partial-overlap | 0028 | **DISAGREE**: PMDA version-splits the DefineVersion pattern (stricter); CDISC 0028 accepts either on both versions |
| DD0044/DD0104/DD0135/DD0141/OD0076/OD0078 | exact-duplicate | 0088/0209/0260/0264/0174/0175-B | agree |
| DD0030 / DD0130 | union-of | 0178+0197 / 0075+0123+0167 | agree (wildcard vs per-element) |
| DD0048 | partial-overlap | 0109 | **DISAGREE**: PMDA's SASDatasetName pattern is case-insensitive with no 8-char cap; CDISC 0109 enforces uppercase XPT-v5 — both source-faithful |
| DD0052/DD0074/DD0123 | exact-duplicate | 0112/—/0142 | agree; DD0074 has no CDISC twin at the same target |
| DD0053(+B) | partial-overlap | 0251/0252 | agree on 2.1; PMDA adds 2.0 legs |
| DD0022 family | new | (CDISC 263 = library-gap) | PMDA enumerates version sets inline; CDISC ruled its twin a library-gap — both defensible, flagged |
| OD0019 | partial-overlap | 0175 | **DISAGREE**: PMDA pattern verbatim has no length cap and allows $ anywhere first-char; CDISC enforces SAS caps |
| OD0075 | partial-overlap | 0140(+B) | **DISAGREE**: PMDA omits intervalDatetime even on 2.1 |
| OD0072/73/74 | exact-duplicate | 0114/0115/0143-inverse | agree |
| DD0109/0110, DD0106/07/08, DD0113, DD0112(+B), DD0025, DD0148, OD0021, OD0077(+B), OD0080, DD0064 family, DD0073 family, DD0055(+B), DD0021 | new | — | no CDISC twins |

## Batch P5b (folder + split-dataset customs, 8 rules, 2026-07-03)

| PMDA | Relationship | CDISC | Agreement |
|---|---|---|---|
| DD0049 | partial-overlap | 0110, 0095 | PMDA adds Domain + standard-family + non-split/non-SUPP conditions |
| DD0063 | partial-overlap | 0095 | **DISAGREE on condition** (Alias when >1 IGD share a Domain vs Alias-less Name≠Domain) — now shipped, deferral resolved |
| DD0075 | partial-overlap | — | PMDA-DD0103/DD0035 siblings; owns the absent-DocumentRef shape (review-pmda-p3 W1) |
| DD0050 / DD0114 / DD0115 | new | — | no CDISC split-dataset rows |
| DD0084 | new | — | first `Requires: folder` rule |
| DD0102 | new | — | declarative via forward-reference scoping (custom budget saved) |

## Batch P5a (CT kinds, 20 files / 13 rows, 2026-07-03)

| Rule | Relationship | Twin | Agreement |
|---|---|---|---|
| DD0024(+B) | variant-of | 0179/0192 | DD0024 is the non-extensible-only variant |
| DD0028(+B) | new | — | term↔c-code pairing; no CDISC twin |
| DD0031 | related | (97 = library-gap) | ships the CT-identifiable slice; 2.0-only |
| DD0032(+B) | related | (98/99 = library-gaps) | term-level alias slice |
| DD0033 / DD0034(+B) | new | — | c-code validity |
| DD0055-C | exact-duplicate | 0132 | same shape, same C103329 |
| DD0021-B | new | — | STDNAM C170452 (verified in the local Define-XML CT cache) |
| 0153 | — | (DD0128 P2 twin) | authored WITHOUT CT per its formal Rule column (dual-reading resolved) |

## Residual batch (11 files / 9 rows, 2026-07-03)

| PMDA | Relationship | CDISC | Agreement |
|---|---|---|---|
| DD0029(+B) | exact-duplicate | 186/201 | agree (extended_value_marking required) |
| DD0132(+B) | exact-duplicate | 187/202 | agree (extended_value_marking forbidden) |
| OD0082 | partial-overlap | 0199 | **stricter**: OD0082 is a mixed-item-type check, not per-item Decode |
| DD0138 | intra-PMDA overlap | (DD0122/DD0125) | wildcard StandardOID leg — full-mirror pattern like DD0071 |
| DD0099/DD0100 | new | — | ARM AnalysisReason/Purpose CT (c-codes C117744/C117745 to confirm at CtProvider binding) |
| DD0105/DD0093/DD0150 | new | — | study-day origin / ARM ParameterOID / standards-combination |
