# ADaMIG 1.3 — Gap analysis decisions

Review result for generated-vs-conformance comparison.

| | Path | Rules |
|---|---|---:|
| Source (IG-derived) | `rules/generated/rules-adamig-1-3.json` | 471 |
| Target (conformance) | `rules/rules-adamig-1-3.json` | 696 |
| Output (gaps) | `rules/rules-adamig-1-3-additions.json` | 32 |

## Summary

- **Covered by conformance template** (auto-detected): 373
- **Covered by a specific conformance rule** (verified): 64
- **Not covered (additions)**: 32

## Additions (not covered by conformance file)

| New ID | Source ID | Reason |
|---|---|---|
| `ADAM-ADD-100001` | `ADAM-000022` | Dataset name length ≤ 8 — no conformance rule exists (ADAMCR-0013 covers variable names, not dataset names) |
| `ADAM-ADD-100002` | `ADAM-000033` | DTYPE required when derived records — no conformance rule |
| `ADAM-ADD-100003` | `ADAM-000037` | *DY value must not be 0 — no conformance rule |
| `ADAM-ADD-100004` | `ADAM-000038` | *DTF populated when *DT imputed — ADAMCR-0369 is the inverse direction |
| `ADAM-ADD-100005` | `ADAM-000047` | TRTSDTF populated when TRTSDT imputed — no conformance rule |
| `ADAM-ADD-100006` | `ADAM-000048` | TRTSDT ≤ TRTEDT ordering — no conformance rule |
| `ADAM-ADD-100007` | `ADAM-000052` | BASE present when CHG present — only CHG=AVAL-BASE check exists |
| `ADAM-ADD-100008` | `ADAM-000054` | CHG null for baseline — no conformance rule |
| `ADAM-ADD-100009` | `ADAM-000055` | PCT null for baseline — no conformance rule |
| `ADAM-ADD-100010` | `ADAM-000059` | ADT present when ADY present — no conformance rule |
| `ADAM-ADD-100011` | `ADAM-000063` | ANRHIy requires ANRLOy — no conformance rule |
| `ADAM-ADD-100012` | `ADAM-000068` | TRTP required when TRTA present (non-indexed) — TRT_XX template only covers indexed TRTxxA/TRTxxP |
| `ADAM-ADD-100013` | `ADAM-000070` | ADSL dataset class = "SUBJECT LEVEL ANALYSIS DATASET" — ADAMCR-0320/0321 check dataset_label, not dataset_class |
| `ADAM-ADD-100014` | `ADAM-000072` | SRCVAR requires SRCDOM — no conformance rule |
| `ADAM-ADD-100015` | `ADAM-000073` | SRCSEQ requires SRCDOM — no conformance rule |
| `ADAM-ADD-100016` | `ADAM-000074` | DTYPE CT — no conformance rule |
| `ADAM-ADD-100017` | `ADAM-000076` | EOSSTT CT — no conformance rule |
| `ADAM-ADD-100018` | `ADAM-000077` | DCSREAS null when EOSSTT=COMPLETED — no conformance rule |
| `ADAM-ADD-100019` | `ADAM-000078` | Metadata required — no conformance rule |
| `ADAM-ADD-100020` | `ADAM-000081` | AAGE required if differs from DM.AGE — no conformance rule |
| `ADAM-ADD-100021` | `ADAM-000090` | AGEU CT — no conformance rule |
| `ADAM-ADD-100022` | `ADAM-000461` | --DUR not negative (SDTM wildcard; unusual in ADaM file) |
| `ADAM-ADD-100023` | `ADAM-000462` | Text var contains "." as entire value — no conformance rule |
| `ADAM-ADD-100024` | `ADAM-000463` | Text var contains leading spaces — no conformance rule |
| `ADAM-ADD-100025` | `ADAM-000464` | Required variable not present (Part A) — template rule, not in conformance |
| `ADAM-ADD-100026` | `ADAM-000465` | Expected variable not present — template rule, not in conformance |
| `ADAM-ADD-100027` | `ADAM-000466` | Dataset > 5 GB — no conformance rule |
| `ADAM-ADD-100028` | `ADAM-000467` | Variable order — no conformance rule |
| `ADAM-ADD-100029` | `ADAM-000468` | Not allowed variable for Observation Class — no conformance rule |
| `ADAM-ADD-100030` | `ADAM-000469` | Empty dataset — no conformance rule |
| `ADAM-ADD-100031` | `ADAM-000470` | Variable label title case — no conformance rule |
| `ADAM-ADD-100032` | `ADAM-000471` | Required variable null (Part B) — template rule, not in conformance |

## Covered by specific conformance rule

| Source ID | Conformance ID | Notes |
|---|---|---|
| `ADAM-000001` | `ADAMCR-0001` | ADSL dataset does not exist |
| `ADAM-000002` | `ADAMCR-0054` | Within ADSL more than one record for unique USUBJID |
| `ADAM-000003` | `ADAMCR-0088` | STUDYID is not present |
| `ADAM-000004` | `ADAMCR-0089` | USUBJID is not present |
| `ADAM-000005` | `ADAMCR-0055` | SUBJID is not present within ADSL |
| `ADAM-000006` | `ADAMCR-0047` | SITEID is not present within ADSL |
| `ADAM-000007` | `ADAMCR-0049` | AGE is not present within ADSL |
| `ADAM-000008` | `ADAMCR-0050` | AGEU is not present within ADSL |
| `ADAM-000009` | `ADAMCR-0051` | SEX is not present within ADSL |
| `ADAM-000010` | `ADAMCR-0052` | RACE is not present within ADSL |
| `ADAM-000011` | `ADAMCR-0071` | ARM is not present within ADSL |
| `ADAM-000012` | `ADAMCR-0072` | TRT01P is not present within ADSL |
| `ADAM-000013` | `ADAMCR-0048 [FLAG_PRESENCE]` | At least one *FL flag variable in ADSL — both rules cite ADaMIG §3.2 with identical guidance text "A minimum of one subject-level population flag variable is required in ADSL" |
| `ADAM-000014` | `ADAMCR-0023` | SAFFL value not Y/N |
| `ADAM-000015` | `ADAMCR-0020` | FASFL value not Y/N |
| `ADAM-000016` | `ADAMCR-0021` | ITTFL value not Y/N |
| `ADAM-000017` | `ADAMCR-0022` | PPROTFL value not Y/N |
| `ADAM-000018` | `ADAMCR-0019` | COMPLFL value not Y/N |
| `ADAM-000019` | `ADAMCR-0024` | RANDFL value not Y/N |
| `ADAM-000020` | `ADAMCR-0025` | ENRLFL value not Y/N |
| `ADAM-000021` | `ADAMCR-0496` | Dataset name does not start with "AD" |
| `ADAM-000023` | `ADAMCR-0013` | Variable name length > 8 |
| `ADAM-000024` | `ADAMCR-0016` | Variable label length > 40 |
| `ADAM-000025` | `ADAMCR-0017` | Character value length > 200 |
| `ADAM-000026` | `ADAMCR-0089` | USUBJID is not present |
| `ADAM-000027` | `ADAMCR-0194/0196` | PARAM is not present/populated |
| `ADAM-000028` | `ADAMCR-0195/0197` | PARAMCD is not present/populated |
| `ADAM-000029` | `ADAMCR-0740/0741` | PARAM/PARAMN 1:1 relationship |
| `ADAM-000030` | `ADAMCR-0738/0739` | PARAMCD/PARAM 1:1 relationship |
| `ADAM-000031` | `ADAMCR-0143` | PARAMCD length > 8 |
| `ADAM-000032` | `ADAMCR-0198` | Neither AVAL nor AVALC present |
| `ADAM-000034` | `ADAMCR-0002 [SDTM_LABEL_CHECK]` | Same ADaMIG §3.1.1 Item 3 citation — "same name, same meaning, same values" principle (conformance uses SDTM_LABEL_CHECK + SDTM_TYPE_CHECK templates) |
| `ADAM-000035` | `ADAMCR-0045 [SUFFIX_PATTERN]` | *DT / *DTM date-part mismatch |
| `ADAM-000036` | `ADAMCR-0044 [SUFFIX_PATTERN]` | *TM / *DTM time-part mismatch |
| `ADAM-000039` | `ADAMCR-0039 [SUFFIX_PATTERN]` | *DTF CT check (DATEFL = Y/M/D) |
| `ADAM-000040` | `ADAMCR-0040 [SUFFIX_PATTERN]` | *TMF CT check (TIMEFL = H/M/S) |
| `ADAM-000042` | `ADAMCR-0007 [SUFFIX_PATTERN]` | *FN present without *FL |
| `ADAM-000043` | `ADAMCR-0075 [TRT_XX]` | TRTxxPN without TRTxxP |
| `ADAM-000044` | `ADAMCR-0064 [TRT_XX]` | TRTxxAN without TRTxxA |
| `ADAM-000045` | `ADAMCR-0061` | TRTSDT / TRTSDTM required when SDTM.EX present |
| `ADAM-000046` | `ADAMCR-0365` | TRTEDT / TRTEDTM required when SDTM.EX present |
| `ADAM-000049` | `ADAMCR-0366` | RANDDT not present when RANDFL=Y |
| `ADAM-000053` | `ADAMCR-0223` | CHG != AVAL - BASE |
| `ADAM-000056` | `ADAMCR-0005 + ADAMCR-0494` | ABLFL Y/null |
| `ADAM-000057` | `ADAMCR-0154/0155` | ABLFL=Y uniqueness per PARAMCD/BASETYPE |
| `ADAM-000058` | `ADAMCR-0548/0742` | AVISIT/AVISITN 1:1 |
| `ADAM-000060` | `ADAMCR-0542/0744` | ATPT/ATPTN 1:1 |
| `ADAM-000061` | `ADAMCR-0373` | CNSR is not present |
| `ADAM-000062` | `ADAMCR-0169` | CNSR value not 0/positive |
| `ADAM-000064` | `ADAMCR-0005 + ADAMCR-0493` | ANLzzFL Y/null |
| `ADAM-000065` | `ADAMCR-0256` | USUBJID values not in ADSL |
| `ADAM-000066` | `ADAMCR-0085/0086/0590` | CROSS_DATASET_* templates |
| `ADAM-000067` | `ADAMCR-0581` | None of TRTP/TRTA/TRTPGy/TRTAGy present |
| `ADAM-000069` | `ADAMCR-0556` | TRTPN without TRTP |
| `ADAM-000071` | `ADAMCR-0497` | Dataset name starts with "AD" without dataset class |
| `ADAM-000075` | `ADAMCR-0735` | BASETYPE populated |
| `ADAM-000082` | `ADAMCR-0088/0090` | STUDYID not present / not populated |
| `ADAM-000083` | `ADAMCR-0089/0256` | USUBJID not present / values not in ADSL |
| `ADAM-000084` | `ADAMCR-0145` | PARAMCD non-alphanum chars |
| `ADAM-000085` | `ADAMCR-0196` | PARAM is not populated |
| `ADAM-000086` | `ADAMCR-0197` | PARAMCD is not populated |
| `ADAM-000087` | `ADAMCR-0335 [CRIT_Y]` | CRITyFL without CRITy |
| `ADAM-000088` | `ADAMCR-0005 [WILDCARD_SUFFIX]` | CRITyFL Y/N/null |
| `ADAM-000089` | `ADAMCR-0717 [TRT_XX]` | TRTxxPN/TRTxxP 1:1 |

## Covered by template expansion

- `ADAMCR-0709 [IG_LABEL_CHECK]`: 185 generated rules
- `ADAMCR-0200 [IG_TYPE_CHECK]`: 187 generated rules
- `ADAMCR-0005 [WILDCARD_SUFFIX]`: 1 generated rules

## Revision log

| Date | Change |
|---|---|
| 2026-04-17 | Initial audit — 34 gaps identified |
| 2026-04-17 | Removed ADAM-000034 (duplicate of ADAMCR-0002) per user feedback |
| 2026-04-17 | Removed ADAM-000013 (duplicate of ADAMCR-0048 — identical citation guidance) |
| 2026-04-17 | Renamed additions IDs: `ADAM-*` → `ADAM-ADD-100001`..`100032` |
