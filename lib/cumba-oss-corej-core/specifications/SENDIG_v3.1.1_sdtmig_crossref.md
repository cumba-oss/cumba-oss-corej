# SENDIG v3.1.1 — SDTMIG-3.4 Cross-Reference

For each candidate rule in `SENDIG_v3.1.1_conformance_rules.md`, this file
records the closest matching rule in the bundled SDTMIG-3.4 rule package
(`rules/rules-sdtmig-3-4.json`, 430 rules). The match-quality column
follows:

- `exact` — same check, same variables, only Authority needs swapping.
- `close` — same intent, minor variable/scope differences.
- `partial` — related but only covers part of the candidate's intent.
- `no match` — truly SEND-specific, no SDTMIG analog.

---

## 4 Assumptions for Domain Models (cross-cutting)

### 4.1 General Assumptions

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 4.1.1 | SUPP-- contains a standard timing variable | — | no match | SDTMIG has no rule banning timing-variable names in QNAM. New rule. |
| 4.1.1 | RELREC contains any standard timing variable | — | no match | SDTMIG has no equivalent column-presence rule on RELREC. |
| 4.1.1 | Trial Design dataset contains a foreign timing variable | — | no match | SDTMIG-3.4 has no analog enforcing the TE/TA/TX/TS timing whitelist. |
| 4.1.4 | Dataset filename is not lowercase domain code + .xpt | CORE-000598 | partial | CORE-000598 enforces dataset name begins with DOMAIN value, but does not enforce the lowercase-with-`.xpt` convention; extend with a string-format check. |

### 4.2 Variable Naming, Length, and Character Conventions

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 4.2.1 | --TESTCD value > 8 chars / invalid chars / starts with digit | CORE-000220 | exact | Already covers Findings class, all domains. Just clone with SENDIG authority and limit findings list to SEND domains (BW, BG, CL, DD, FW, LB, MA, MI, OM, PM, PC, PP, SC, TF, VS, EG, CV, RE). |
| 4.2.1 | --TEST value > 40 chars | — | partial | CORE-000148 covers TSPARM length; no general --TEST length rule in SDTMIG-3.4. Author from scratch. |
| 4.2.1 | SUPP-- QNAM > 8 chars / invalid chars / starts with digit | CORE-000221 | exact | Identical check; clone with SENDIG authority. |
| 4.2.1 | SUPP-- QLABEL > 40 chars | CORE-000222 | exact | Identical check; clone with SENDIG authority. |
| 4.2.1 | ETCD > 8 chars / invalid chars | CORE-000143 | close | CORE-000143 enforces ETCD length <= 8 but does not check the character class; either extend the rule or pair with a regex check. |
| 4.2.1 | ELEMENT value > 40 chars | — | no match | SDTMIG-3.4 does not enforce ELEMENT value length. Author from scratch. |
| 4.2.1 | ARMCD value > 20 chars | CORE-000010 | exact | Identical check; clone with SENDIG authority. |
| 4.2.1 | ARM value > 40 chars | — | no match | SDTMIG-3.4 has no ARM length rule (only ARMCD). Author from scratch. |
| 4.2.1 | SETCD value > 8 chars | CORE-000088 | exact | Identical check; clone with SENDIG authority. |
| 4.2.1 | TXPARMCD > 8 chars | — | partial | Mirror CORE-000147 (TSPARMCD>8) onto TX dataset. |
| 4.2.1 | TSPARMCD > 8 chars | CORE-000147 | exact | Identical check; clone with SENDIG authority. |
| 4.2.1 | TXPARM/TSPARM > 40 chars | CORE-000148 | close | CORE-000148 covers TSPARM only; clone-and-add TXPARM scope. |
| 4.2.1 | Variable name not entirely uppercase | — | partial | CORE-000182 enforces variable name length; no rule enforces uppercase. Author from scratch. |
| 4.2.1 | Variable Label > 40 chars | CORE-000019 | exact | Identical check; clone with SENDIG authority. |
| 4.2.2 | DOMAIN value does not match dataset domain code | CORE-000180 | close | CORE-000180 enforces DOMAIN length=2; CORE-000376 enforces variable prefix matches DOMAIN; CORE-000598 enforces dataset-name=DOMAIN. None enforce DOMAIN value matches the dataset's own domain code with character-class regex; combine and re-target. |
| 4.2.3 | USUBJID null in subject-level dataset | CORE-000356 | close | CORE-000356 (Required variable null) covers USUBJID for any dataset where USUBJID is required by the SEND model. Re-target by listing SEND subject-level domains as scope. |
| 4.2.3 | POOLID null AND USUBJID null in pool-eligible dataset | CORE-000107 | close | CORE-000107 covers AP— class with `one of USUBJID/APID/SPDEVID/POOLID required`. Re-target by removing APID/SPDEVID and limiting to LB/FW/CL/PC/PP. |
| 4.2.3 | USUBJID and POOLID both populated on same record | CORE-000229, CORE-000230 | exact | Two SDTMIG rules together enforce mutual exclusion (one direction each). Clone both with SENDIG authority. |
| 4.2.3 | Two animals share same USUBJID | CORE-000351 | exact | CORE-000351 already enforces USUBJID uniqueness in DM. Clone with SENDIG authority. |
| 4.2.3 | POOLID reused for different subjects within a study | — | no match | SDTMIG-3.4 has no POOLDEF rules; SEND-specific. |
| 4.2.3 | POOLID in finding dataset not defined in POOLDEF | — | no match | Same — POOLDEF unique to SEND. |
| 4.2.3 | POOLID definition consists of zero subjects | — | no match | POOLDEF has no SDTMIG analog. |
| 4.2.5 | Required variable null | CORE-000356 | exact | Identical intent. Clone with SENDIG authority and the SEND `Req` flag set. |
| 4.2.5 | Expected variable column missing | CORE-000334, CORE-000355 | exact | CORE-000334 (Expected) and CORE-000355 Part A (Required column present) cover both halves. Clone with SENDIG authority. |

### 4.3 Controlled Terminology

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 4.3 | Variable that uses a CT codelist contains a value not in that codelist | — | no match | In Cumba's bundled SDTMIG-3.4 rule pack, CT-conformance is delegated to a separate `rules-sdtmig-3-4-ct-documentation.md` set, not the main rules JSON. SEND-equivalent CT rules are normally generated from the SEND CT package, not authored individually. Author per-codelist via the CT generator. |

### 4.4 Timing Variables

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 4.4.1 | --DTC not in ISO 8601 extended format | — | partial | CORE-000505 covers SSTDTC ISO format, CORE-000294 covers TSVAL when TSPARMCD=AGEMAX. No general `every --DTC must be ISO 8601` rule in SDTMIG-3.4. Author from scratch. |
| 4.4.1 | --DTC contains space, AM/PM marker, non-ISO component | — | no match | Same as above; sub-condition of generic ISO check. |
| 4.4.1 | --ENDTC precedes corresponding --STDTC | CORE-000718 | exact | CORE-000718 enforces --STDTC <= --ENDTC for INTERVENTIONS/EVENTS/SP, excluding DM. Re-target by extending Class scope to include FINDINGS for SEND (interval findings such as BG/FW). |
| 4.4.3 | --DUR not ISO 8601 duration / mixes weeks | CORE-000305 | partial | CORE-000305 enforces non-negative; format/W-mixing not covered. Combine with new format check. |
| 4.4.4 | --DY = 0 | CORE-000529 | close | CORE-000529 enforces --DY is a non-zero integer per the SDTMIG study-day algorithm when both --DTC and RFSTDTC have complete dates. Clone with SENDIG authority and confirm the same algorithm applies. |
| 4.4.4 | --DY consistent with --DTC and RFSTDTC | CORE-000529, CORE-000553 | exact | CORE-000529 (algorithm) and CORE-000553 (study day calculated per IG) together cover this. Clone with SENDIG authority. |
| 4.4.7 | --TPT/--TPTNUM 1:1 within domain/CAT/SCAT | CORE-000141, CORE-000685, CORE-000686, CORE-000689 | close | SDTMIG family covers --TPT/--TPTNUM 1:1 partitioned by VISITNUM/--TPTREF. Re-target the partition keys to (--CAT, --SCAT) per SENDIG 4.4.7 (no VISITNUM in SEND). |
| 4.4.7 | --TPTREF populated but --RFTDTC null | CORE-000248 | close | CORE-000248 is the inverse direction (RFTDTC populated -> TPTREF required). Add a mirror rule. |
| 4.4.7 | --ELTM populated but --TPTREF null | CORE-000028, CORE-000865 | exact | Both SDTMIG rules already enforce this. Clone CORE-000865 with SENDIG authority. |
| 4.4.7 | --ELTM not in ISO 8601 duration format | — | partial | CORE-000779 (TDSTOFF ISO duration) is the only ISO-duration check; clone-and-retarget to --ELTM. |

### 4.5 Original and Standardized Results

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 4.5 | --ORRES null and --STAT not "NOT DONE" | CORE-000200 | exact | CORE-000200 already enforces --ORRES not missing when --STAT null or --DRVFL≠Y. Clone with SENDIG authority. |
| 4.5 | --ORRES populated and --STAT is "NOT DONE" | CORE-000099 | close | CORE-000099 enforces both --ORRES and --STAT cannot be populated; check that "populated --STAT" allows non-NOT DONE. Adjust the leaf operator to "STAT eq NOT DONE" specifically. |
| 4.5 | --STRESC null while --ORRES populated | CORE-000082 | close | CORE-000082 is for PE only (PESTRESC null when PEORRES null — opposite direction); the general direction (--STRESC null when --ORRES populated) needs re-authoring. Partial. |
| 4.5 | --STRESN populated but --STRESC null/non-numeric | CORE-000732, CORE-000542, CORE-000863 | exact | CORE-000732 (STRESC not numeric, STRESN not empty), CORE-000542 (STRESC numeric, STRESN missing/different), CORE-000863 (STRESC numeric -> STRESN populated). Clone with SENDIG authority. |
| 4.5 | --STAT NOT DONE and --REASND null | CORE-000774 | exact | Identical intent. Clone with SENDIG authority. |
| 4.5 | --STAT NOT DONE and --ORRESU/--STRESU/--STRESC/--STRESN populated | — | partial | SDTMIG covers --REASND requirement (CORE-000774) and --ORRES populated (CORE-000099) but not the unit/standardized-result side. Author from scratch. |
| 4.5.1 | SUPP-- CALCN used when parent --STRESC not BLQ/ALQ | — | no match | PCCALCN convention is SEND-specific (PK preclinical). Author from scratch. |

---

## 5 Special-Purpose Domains

### 5.1 Demographics — DM

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 5.1.1 | DM key fields null | CORE-000356 | close | CORE-000356 is the generic "Required null" rule. Clone with SENDIG authority and SEND DM Req list (STUDYID, DOMAIN, USUBJID, SUBJID, RFSTDTC, SETCD, SEX). |
| 5.1.1 | DM has > 1 record per USUBJID | CORE-000351 | exact | Same constraint. Clone with SENDIG authority. |
| 5.1.1 | DM ARM/ARMCD not 1:1 | CORE-000318 | exact | Identical intent. Clone with SENDIG authority. |
| 5.1.1 | DM.ARMCD/ARM not in TA | CORE-000155, CORE-000156 | exact | CORE-000155 (ARMCD not in TA.ARMCD), CORE-000156 (ARM not in TA.ARM). Clone with SENDIG authority. |
| 5.1.1 | DM AGE and AGETXT both populated | CORE-000068 | close | CORE-000068 disallows AGETXT entirely in SDTMIG. SEND allows AGETXT; rewrite as mutual exclusion only. |
| 5.1.1 | DM AGE and AGETXT both null | CORE-000122 | close | CORE-000122 enforces "AGEU populated -> AGE or AGETXT populated". Re-target: AGE-OR-AGETXT must be present in DM. |
| 5.1.1 | DM AGEU null while AGE/AGETXT populated | CORE-000189 | close | CORE-000189 (AGE not blank, AGEU blank). Extend to also cover AGETXT for SEND. |
| 5.1.1 | DM BRTHDTC derived from RFSTDTC + AGE | — | no match | SDTMIG has no BRTHDTC-derivation check. Author from scratch (and likely soft warning, not error). |
| 5.1.1 | DM.SETCD does not match TX.SETCD | — | no match | TX is SEND-specific. Author from scratch. |
| 5.1.1 | DM.SPECIES/STRAIN not in CT | — | no match | SPECIES/STRAIN are SEND-only DM variables; no SDTMIG analog. CT-driven; author via CT generator. |

### 5.2 Comments — CO

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 5.2.1 | CO RDOMAIN does not match a submitted domain | CORE-000502 | exact | CORE-000502 enforces RDOMAIN exists as a dataset. Clone with SENDIG authority. |
| 5.2.1 | CO IDVAR populated but IDVARVAL null (and vice versa) | CORE-000218, CORE-000135 | exact | CORE-000218 (IDVAR null, IDVARVAL not null) and CORE-000135 (IDVAR not null, IDVARVAL null). Clone with SENDIG authority. |
| 5.2.1 | CO IDVAR/IDVARVAL cannot be matched in parent dataset | CORE-000206, CORE-000953 | exact | CORE-000206 (general) and CORE-000953 (CO Part C). Clone with SENDIG authority. |
| 5.2.1 | CO DOMAIN is anything other than CO | CORE-000180, CORE-000598 | partial | Two SDTMIG rules together enforce DOMAIN length = 2 and dataset-name = DOMAIN; the literal-CO check is incidental, not a dedicated rule. Author small extension. |

### 5.3 Subject Elements — SE

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 5.3.1 | SESTDTC null on SE record | CORE-000356 | close | Generic "Required null" rule; clone with SENDIG authority and SE Req list. |
| 5.3.1 | SE elements do not abut (SEENDTC ≠ next SESTDTC) | CORE-000352, CORE-000527 | exact | CORE-000352 covers element abutting; CORE-000527 covers SEENDTC null on non-last elements. Clone both with SENDIG authority. |
| 5.3.1 | SE.ETCD not "UNPLAN" but not in TE.ETCD | CORE-000173 | exact | Identical intent. Clone with SENDIG authority. |
| 5.3.1 | SE.ETCD = "UNPLAN" but ELEMENT populated | CORE-000009 | exact | Identical intent. Clone with SENDIG authority. |
| 5.3.1 | SE.ETCD = "UNPLAN" but SEUPDES null | CORE-000256 | exact | Identical intent. Clone with SENDIG authority. |
| 5.3.1 | SE.ETCD ≠ "UNPLAN" but SEUPDES populated | CORE-000095 | exact | Identical intent. Clone with SENDIG authority. |

---

## 6.1 Interventions

### 6.1.1 Exposure — EX

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.1.1 | EXTRT contains dosage/formulation/vehicle | — | no match | SDTMIG-3.4 has no EXTRT content-purity check. Author from scratch (warning-level rule). |
| 6.1.1 | EXDOSFRQ null on EX record | CORE-000356 | close | Generic "Required null" rule. EXDOSFRQ is Req in SEND (Exp in SDTM); clone-and-retarget. |
| 6.1.1 | EXROUTE null on EX record | CORE-000356 | close | Same — generic Req-null for the SEND EX requirement set. |
| 6.1.1 | EXDOSE null while EXDOSU populated (and vice versa) | CORE-000093 | close | CORE-000093 covers --DOSU null when DOSE/DOSTOT/DOSTXT populated; partial direction. Mirror rule needed for the inverse. |
| 6.1.1 | EX USUBJID and POOLID both null | CORE-000107 | close | CORE-000107 covers the AP—class version. Re-target to EX with USUBJID/POOLID only. |
| 6.1.1 | EX USUBJID not in DM | CORE-000201 | exact | CORE-000201 enforces USUBJID present in DM.USUBJID for non-AP--. Clone with SENDIG authority. |
| 6.1.1 | EXSTDTC > EXENDTC | CORE-000718 | exact | Generic --STDTC <= --ENDTC. Already covers Interventions class. Clone with SENDIG authority. |
| 6.1.1 | EXSTDY ≠ (EXSTDTC - DM.RFSTDTC) + 1 | CORE-000529 | close | Generic study-day algorithm rule. Re-target to EX scope. |

---

## 6.2 Events

### 6.2.1 Disposition — DS

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.2.1 | Subject in DM has no record in DS | — | no match | SDTMIG-3.4 ties DS to death events (CORE-000679, CORE-000252) but does not require every subject to have ≥1 DS record at study completion. Author from scratch. |
| 6.2.1 | DSSTDTC recorded as interval of uncertainty | — | no match | No SDTMIG analog. Author from scratch (or covered by the general --DTC ISO check). |
| 6.2.1 | DSDECOD not in SEND CT | — | no match | CT-driven; author via CT generator. SEND DSDECOD codelist differs from SDTM. |
| 6.2.1 | More than one terminal-disposition record per subject | CORE-000213, CORE-000215, CORE-000374 | partial | CORE-000213/215 enforce one DS record per subject per EPOCH per DSCAT/DSSCAT; SEND terminal sacrifice/found dead etc. is a different partition. Adapt by switching the keying to the SEND terminal-disposition DSDECOD list. |

---

## 6.3 Findings — common patterns

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3 (general) | --SEQ not unique within STUDYID + USUBJID/POOLID + DOMAIN | CORE-000544 | exact | CORE-000544 already covers both USUBJID and POOLID partitions across split files. Clone with SENDIG authority. |
| 6.3 (general) | --TESTCD and --TEST not 1:1 | CORE-000303 | exact | Identical intent. Clone with SENDIG authority. |
| 6.3 (general) | --TESTCD populated but --TEST null (or vice versa) | — | partial | CORE-000303 enforces 1:1 (which catches null-paired-with-value implicitly). Add explicit per-direction null check if desired. |
| 6.3 (general) | --CAT and --SCAT recognized hierarchical consistency | CORE-000103, CORE-000104 | close | CORE-000103 (--SCAT not empty when --CAT empty) and CORE-000104 (--SCAT in dataset, --CAT not in dataset). Clone with SENDIG authority. Sponsor-defined hierarchy check is unmodelable. |
| 6.3 (general) | --BLFL = "Y" for post-dose record | — | no match | No SDTMIG rule cross-checks BLFL against --DY/RFSTDTC. Author from scratch. |
| 6.3 (general) | --DRVFL = "Y" but record collected | — | no match | SDTMIG-3.4 has no DRVFL semantic check. Author from scratch (sponsor-review level). |
| 6.3 (general) | --EXCLFL = "Y" but --REASEX null | CORE-000242, CORE-000243 | partial | CORE-000242/243 forbid --EXCLFL/--REASEX usage entirely in SDTMIG (SDTM does not allow them). SEND allows them — author SEND rule from scratch as a dependency check. |
| 6.3 (general) | --REASEX populated but --EXCLFL ≠ "Y" | CORE-000243 | partial | Same as above; needs re-authoring with allow-then-link semantics for SEND. |

### 6.3.1 Body Weights — BW

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.1 | BWTESTCD not in ('BW','TERMBW') | — | no match | BW domain not in SDTMIG-3.4. Author from scratch with SEND CT codelist. |
| 6.3.1 | BWORRESU/BWSTRESU missing | CORE-000133 | close | Generic CORE-000133 (--STRESU populated -> --STRESC populated) is direction-mismatched; mirror with SEND-specific Req-flag check. |
| 6.3.1 | BWSTRESN null while BWORRES numeric | CORE-000863 | close | Generic CORE-000863 covers Findings ALL. Clone-and-scope to BW. |

### 6.3.2 Body Weight Gains — BG

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.2 | BGTESTCD not in BG codelist | — | no match | BG is SEND-only; CT-driven. Author via CT generator. |
| 6.3.2 | BG interval but BGENDTC missing | CORE-000864, CORE-000776 | close | CORE-000864/776 enforce --ENDTC/--ENDY presence. Clone-and-scope to BG. |
| 6.3.2 | BGORRESU/BGSTRESU missing | CORE-000356 | close | Generic Req-null. SEND-specific Req list. |

### 6.3.3 Clinical Observations — CL

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.3 | CLSEV populated but CLSTRESC null | — | no match | CL is SEND-only. Author from scratch (parallels --SEV semantics). |
| 6.3.3 | CL exam shows no abnormality but --STRESC ≠ "NORMAL" | — | no match | "NORMAL" sentinel convention is SEND-specific. Author from scratch. |
| 6.3.3 | CLSPID does not match --SPID in CL/PM/MA/MI/TF for same subject | — | no match | --SPID cross-domain consistency is SEND-specific (mass-tracking convention). Author from scratch. |

### 6.3.4 Death Diagnosis and Details — DD

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.4 | DD record exists for subject not indicated as died | CORE-000108 | close | CORE-000108 enforces DD record -> DM.DTHFL='Y'. Clone with SENDIG authority and add the DS direction. |
| 6.3.4 | DDDTC ≠ DSSTDTC for death record | CORE-000913, CORE-000034 | close | CORE-000913/CORE-000034 enforce DSSTDTC = DM.DTHDTC for DEATH. Mirror to DDDTC for SEND (DM.DTHDTC is excluded by Appendix E in SEND). |

### 6.3.5 Food and Water Consumption — FW

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.5 | FWTESTCD not in FW codelist | — | no match | FW SEND-only. CT-driven. |
| 6.3.5 | FW interval but FWENDTC null | CORE-000864 | close | Generic --ENDTC/--ENDY presence rule. Clone-and-scope to FW. |
| 6.3.5 | FW POOLID not in POOLDEF | — | no match | POOLDEF is SEND-only. Author from scratch. |

### 6.3.6 Laboratory Test Results — LB

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.6 | Both standard and original reference ranges populated | — | no match | SDTMIG enforces each range independently (CORE-000672/889) but not mutual exclusion. Author from scratch. |
| 6.3.6 | LBSPEC null on LB record | CORE-000356 | close | Generic Req-null; clone-and-retarget with SEND LB Req list (LBSPEC is Req in SEND). |
| 6.3.6 | LBSTRESN populated but LBSTRESU null | CORE-000133 | close | CORE-000133 is the inverse direction (STRESU pop -> STRESC pop). Author the missing direction or pair. |
| 6.3.6 | LBORRESU/LBSTRESU different physical-quantity dimensions | — | no match | SDTMIG-3.4 has no unit-dimension check. Author from scratch or use UCUM-based check. |

### 6.3.7 Macroscopic Findings — MA

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.7 | Subject scheduled for necropsy has zero MA records | — | no match | Necropsy-completion check is SEND-specific. Author from scratch. |
| 6.3.7 | MASTAT = "NOT DONE" and MAREASND null | CORE-000774 | exact | Generic --STAT=NOT DONE -> --REASND. Clone-and-scope to MA. |
| 6.3.7 | MA exam no abnormality but MASTRESC ≠ "UNREMARKABLE" | — | no match | UNREMARKABLE sentinel SEND-specific. Author from scratch. |
| 6.3.7 | MASPID does not match --SPID in CL/PM/MI/TF | — | no match | --SPID cross-domain consistency is SEND-specific. Author from scratch. |

### 6.3.8 Microscopic Findings — MI

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.8 | Tissue scheduled by protocol has no MI record | — | no match | Protocol-tissue completeness is SEND-specific. Author from scratch. |
| 6.3.8 | MISTAT = "NOT DONE" and MIREASND null | CORE-000774 | exact | Same generic; clone-and-scope to MI. |
| 6.3.8 | MI exam no abnormality but MISTRESC ≠ "UNREMARKABLE" | — | no match | UNREMARKABLE sentinel SEND-specific. |
| 6.3.8 | Sample autolyzed but MISPCUFL ≠ "N" | — | no match | MISPCUFL is SEND-specific. Author from scratch. |
| 6.3.8 | MISPID does not match --SPID in CL/PM/MA/TF | — | no match | --SPID cross-domain consistency is SEND-specific. |

### 6.3.9 Organ Measurements — OM

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.9 | OMSPEC null on OM record | CORE-000356 | close | Generic Req-null; clone-and-scope to OM with SEND Req list. |
| 6.3.9 | OMSTRESU missing while OMSTRESN populated | CORE-000133 | close | CORE-000133 covers the inverse direction. Author the missing direction or pair. |

### 6.3.10 Palpable Masses — PM

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.10 | PMSPID does not match --SPID in CL/MA/MI/TF | — | no match | --SPID cross-domain consistency SEND-specific. |
| 6.3.10 | PM mass disappearance but PMSPID linkage missing | — | no match | Mass-tracking conversion linkage is SEND-specific. |

### 6.3.11 Pharmacokinetics Concentrations — PC

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.11 | PCSPEC null on PC record | CORE-000356 | close | Generic Req-null; clone-and-scope to PC with SEND Req list. |
| 6.3.11 | Pre-dose sample PCELTM ≠ "PT0H" | — | no match | PT0H pre-dose convention is SEND-specific. Author from scratch. |
| 6.3.11 | PCSTRESC = BLQ/ALQ but PCSTRESN populated | — | no match | BLQ/ALQ semantics are PC-specific (and SEND-specific). Author from scratch. |
| 6.3.11 | PCSTRESC = BLQ but PCLLOQ or PCSTRESU null | — | no match | Same. PCLLOQ is SEND-specific. |
| 6.3.11 | PCSTAT = NOT DONE but PCORRES/PCSTRESC populated | CORE-000099 | close | CORE-000099 enforces both --ORRES and --STAT cannot both be populated. Clone-and-scope to PC; covers PCORRES half. |
| 6.3.11 | PCELTM null on profile record | — | no match | Profile-record convention is SEND-specific. |
| 6.3.11 | Unscheduled PC record has timing fields populated | — | no match | PCUSCHFL convention is SEND-specific. |
| 6.3.11 | PC USUBJID and POOLID both null | CORE-000107 | close | Generic AP—rule; re-target to PC. |

### 6.3.12 Pharmacokinetics Parameters — PP

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.12 | PPSPEC null on PP record | CORE-000356 | close | Generic Req-null; clone-and-scope to PP. |
| 6.3.12 | PPSTAT = NOT DONE but PPORRES/PPSTRESC populated | CORE-000099 | close | Same as PC; clone-and-scope to PP. |
| 6.3.12 | PPRFTDTC populated but PPTPTREF null | CORE-000248 | exact | CORE-000248 (--TPTREF populated when --RFTDTC populated). Clone-and-scope to PP. |
| 6.3.12 | PP USUBJID and POOLID both null | CORE-000107 | close | Generic AP—rule; re-target to PP. |

### 6.3.13 PC/PP Cross-Domain

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.13 | PP.PPTPTREF not in PC.PCTPTREF for same subject/pool | CORE-000901 | close | CORE-000901 enforces PPCAT/PCTEST match at same reference timepoint — close PP/PC linkage. Adapt to TPTREF cardinality. |
| 6.3.13 | PP.PPRFTDTC ≠ EX.EXSTDTC for matching dose | — | no match | SEND PP/EX dose-time linkage. Author from scratch. |
| 6.3.13 | SUPPPC PCCALCN but parent PCSTRESC ≠ BLQ/ALQ | — | no match | PCCALCN convention SEND-specific. Author from scratch. |

### 6.3.14 Subject Characteristics — SC

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.14 | SC contains data that belongs in DM | — | no match | SC-vs-DM placement is a model interpretation. Author from scratch (likely a fixed allow-list of SCTESTCD values). |
| 6.3.14 | SC > 1 record per (USUBJID, SCTESTCD) | CORE-000544 | close | CORE-000544 enforces --SEQ uniqueness; but the SC rule keys on SCTESTCD, not --SEQ. Author from scratch using the same scaffolding. |

### 6.3.15 Tumor Findings — TF

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.15 | TF record has no corresponding MI record | — | no match | TF/MI linkage SEND-specific. |
| 6.3.15 | TFDETECT null | CORE-000356 | close | Generic Req-null. Re-target with SEND TF Req list. |
| 6.3.15 | TFDTHREL null | CORE-000356 | close | Same. |
| 6.3.15 | TFRESCAT METASTATIC but no TFGRPID-linked primary | — | no match | TFGRPID/TFRESCAT linkage SEND-specific (tumor pathology not in clinical trials). |
| 6.3.15 | TFDETECT vs CL detection day | — | no match | Tumor detection-day calculation SEND-specific. |
| 6.3.15 | TFDETECT vs PM detection day | — | no match | Same — SEND-specific. |
| 6.3.15 | TFDETECT vs DSSTDTC necropsy day | — | no match | Same — SEND-specific. |
| 6.3.15 | TFSPID does not match --SPID in CL/PM/MA/MI | — | no match | SPID cross-domain consistency SEND-specific. |

### 6.3.16 Vital Signs — VS

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.16 | VSTESTCD = TEMP and VSLOC null | CORE-000096 | close | CORE-000096 enforces --LOC presence when --PORTOT exists. Adapt to a TEMP-specific check (or pair with VSLOC requirement). |
| 6.3.16 | VS measurement covers range but VSENDTC null | CORE-000864 | close | Generic --ENDTC presence rule. Clone-and-scope to VS. |

### 6.3.17 ECG Test Results — EG

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.17 | EGSTINT populated but EGENINT null (or vice versa) | CORE-000616, CORE-000642 | close | CORE-000616/642 cover --STINT/--ENINT -> --TPTREF requirement. Mirror the STINT/ENINT mutual presence. |
| 6.3.17 | EGSTINT/EGENINT populated but EGTPTREF null | CORE-000616, CORE-000642 | exact | Both rules already enforce this. Clone-and-scope to EG. |
| 6.3.17 | EG continuous interval but EGENDTC null | CORE-000864 | close | Generic --ENDTC presence rule. Clone-and-scope to EG. |
| 6.3.17 | EGEVLINT and EGSTINT/EGENINT both populated | — | no match | EVLINT is SEND-specific (or under-used in SDTM). Author from scratch. |

### 6.3.18 Cardiovascular Test Results — CV

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.18 | CV measurement covers interval but CVENDTC null | CORE-000864 | close | Generic --ENDTC presence rule. Clone-and-scope to CV. |
| 6.3.18 | CVSTINT populated but CVTPTREF null | CORE-000616 | exact | CORE-000616 already enforces this. Clone-and-scope to CV. |
| 6.3.18 | CVSTINT/CVENINT and CVEVLINT both populated | — | no match | Same as EG. Author from scratch. |

### 6.3.19 Respiratory Test Results — RE

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 6.3.19 | RE measurement covers interval but REENDTC null | CORE-000864 | close | Generic --ENDTC presence rule. Clone-and-scope to RE. |
| 6.3.19 | RESTINT populated but RETPTREF null | CORE-000616 | exact | CORE-000616 already enforces this. Clone-and-scope to RE. |
| 6.3.19 | RESTINT/REENINT and REEVLINT both populated | — | no match | Same as EG/CV. Author from scratch. |

---

## 7 Trial Design Model Datasets

### 7.2 Trial Elements — TE

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 7.2.1 | TE key fields null | CORE-000356 | close | Generic Req-null; clone-and-scope to TE. |
| 7.2.1 | TEENRL and TEDUR both null | CORE-000027 | exact | Identical intent. Clone with SENDIG authority. |
| 7.2.1 | ETCD/ELEMENT not 1:1 | CORE-000132 | exact | Identical intent. Clone with SENDIG authority. |
| 7.2.1 | Same ETCD/ELEMENT but different TESTRL/TEENRL/TEDUR | CORE-000580 | exact | CORE-000580 enforces (TESTRL,TEENRL,TEDUR) unique per ETCD. Clone with SENDIG authority. |

### 7.3 Trial Arms — TA

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 7.3.1 | TA key fields null | CORE-000356 | close | Generic Req-null; clone-and-scope to TA. |
| 7.3.1 | TA.ETCD does not appear in TE.ETCD | CORE-000173 | exact | Identical intent. Clone with SENDIG authority. |
| 7.3.1 | TA.ARMCD/ARM not 1:1 | CORE-000318 | exact | Identical intent. Clone with SENDIG authority. |
| 7.3.1 | TA.ARMCD does not appear in DM.ARMCD (or vice versa) | CORE-000155, CORE-000156 | exact | Already covers ARMCD/ARM presence in TA from DM. Clone-and-mirror direction. |
| 7.3.1 | TAETORD not strictly increasing within ARM | CORE-000534, CORE-000144 | partial | CORE-000534 (integer) and CORE-000144 (unique within ARM) cover prerequisites. Strict monotonic-from-1 needs an extra check. |
| 7.3.1 | Gaps between consecutive elements in arm | — | no match | SDTMIG-3.4 has no abutment check inside TA. SE has CORE-000352 but for SE, not TA. Author from scratch. |

### 7.4 Trial Sets — TX

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 7.4.1 | TX key fields null | CORE-000356 | close | Generic Req-null; TX is SEND-specific so re-target with the SEND TX Req list. |
| 7.4.1 | TXSEQ not unique across TX | CORE-000154 | close | CORE-000154 enforces TSSEQ unique within TSPARMCD; mirror onto TXSEQ for SEND. |
| 7.4.1 | SETCD missing required ARMCD parameter | — | no match | TX is SEND-only. Author from scratch. |
| 7.4.1 | SETCD with TXPARMCD=ARMCD value not in TA.ARMCD | — | no match | Same — TX SEND-only. |
| 7.4.1 | DM.SETCD does not appear in TX.SETCD | — | no match | TX SEND-only. |
| 7.4.1 | Single SETCD has multiple ARMCD values | — | no match | TX SEND-only. |
| 7.4.1 | Two SETCDs share identical (TXPARMCD,TXVAL) entries | — | no match | TX SEND-only. |
| 7.4.2 | Expected TX parameter not present for SETCD | CORE-000334 | close | CORE-000334 (Expected variable not present) is the column-level analog. Author a per-record TX-parameter version. |

### 7.6 Trial Summary — TS

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 7.6.1 | TS key fields null | CORE-000356 | close | Generic Req-null; clone-and-scope to TS. |
| 7.6.1 | TSVAL and TSVALNF both null | CORE-000149 | exact | Identical intent. Clone with SENDIG authority. |
| 7.6.1 | TSVAL populated and TSVALNF also populated | CORE-000150 | exact | Identical intent. Clone with SENDIG authority. |
| 7.6.1 | TSVALNF not in NULLFLAVOR codelist | CORE-000041, CORE-000787 | close | CORE-000041/787 enforce TSVAL/TSVALNF mapping rather than CT membership. Author CT membership rule via CT generator. |
| 7.6.2 | Expected TS parameter not present | CORE-000334, CORE-000741 | close | CORE-000741 enforces required TS parameters when STYPE=INTERVENTIONAL. Re-target to the SEND Should-Include list. |
| 7.6.1 | More than one TS record for single-record TSPARMCD | CORE-000154 | partial | CORE-000154 enforces TSSEQ unique within TSPARMCD; the SEND single-record requirement (SNDIGVER, SDESIGN, SSTYP, SNDCTVER) is stricter. Author from scratch. |
| 7.6.1 | SNDIGVER TSVAL ≠ "SEND Implementation Guide Version 3.1.1" | — | no match | SDTMIG analog (SDTMIGVER) not in rule pack. Author from scratch. |
| 7.6.1 | SDESIGN value not in DESIGN codelist | — | no match | CT-driven. Author via CT generator. |
| 7.6.1 | SSTYP value not in SSTYP codelist | — | no match | SSTYP is SEND-specific. Author via CT generator. |

---

## 8 Representing Relationships and Data

### 8.2 RELREC

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 8.2.1 | RELREC key fields null | CORE-000158, CORE-000356 | close | CORE-000158 (IDVAR must be specified) + generic Req-null. Clone-and-scope to RELREC. |
| 8.2.1 | USUBJID and POOLID both null at record-level | — | partial | CORE-000136 (IDVARVAL/USUBJID null when IDVAR=Sequence Number) is closest. Re-author for SEND with POOLID alternative. |
| 8.2.1 | RDOMAIN not a 2-letter submitted-domain code | CORE-000384 | exact | CORE-000384 enforces RELREC.RDOMAIN refers to a present dataset. Clone with SENDIG authority. |
| 8.2.1 | IDVAR/IDVARVAL cannot be matched in RDOMAIN | CORE-000206, CORE-000916 | exact | CORE-000206 (general) and CORE-000916 (RELREC Part B). Clone with SENDIG authority. |
| 8.2.1 | RELTYPE not in (ONE, MANY) | — | no match | SDTMIG-3.4 has no CT-membership check for RELTYPE in the rule pack. Author via CT generator. |
| 8.2.1 | RELID with mixed record-level and domain-level rows | CORE-000202, CORE-000484 | partial | CORE-000202 (IDVAR=--SEQ -> RELTYPE null) + CORE-000484 (RELID multiple records) cover sub-aspects. Author the consolidated check. |
| 8.2.1 | POOLID populated on domain-to-domain RELREC record | — | no match | POOLID semantics SEND-specific. Author from scratch. |

### 8.3 SUPP-- (Supplemental Qualifiers)

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 8.3 | SUPP-- key fields null | CORE-000356 | close | Generic Req-null; clone-and-scope to SUPP-- with SEND Req list. |
| 8.3 | USUBJID and POOLID both null on SUPP-- (except SUPPDM) | CORE-000136 | close | CORE-000136 covers IDVARVAL/USUBJID null for IDVAR=--SEQ. Extend with POOLID alternative. |
| 8.3 | RDOMAIN not a 2-letter submitted-domain code | CORE-000457 | exact | CORE-000457 enforces SUPP--.RDOMAIN = present dataset. Clone with SENDIG authority. |
| 8.3 | QNAM matches a standard variable name in parent domain | CORE-000783 | exact | Identical intent. Clone with SENDIG authority. |
| 8.3 | SUPP-- duplicate (USUBJID/POOLID, IDVAR, IDVARVAL, QNAM) key | CORE-000203 | close | CORE-000203 enforces (IDVAR, IDVARVAL, QNAM) unique per parent subject record (USUBJID-keyed). Extend partition with POOLID for SEND. |
| 8.3 | Same QNAM with different QLABEL | CORE-000302 | exact | CORE-000302 enforces QNAM/QLABEL 1:1. Clone with SENDIG authority. |
| 8.3 | QORIG not in (COLLECTED, DERIVED, OTHER, NOT AVAILABLE) | — | no match | CT-driven; author via CT generator. |
| 8.3 | IDVAR/IDVARVAL cannot be matched in RDOMAIN | CORE-000206, CORE-000712 | exact | CORE-000206 (general) and CORE-000712 (SUPP-- Part A). Clone with SENDIG authority. |
| 8.3.1.2 | SUPP-- carries data that should be CO/SC/other GO class | — | no match | Modeling-judgment rule, hard to express. Author from scratch (likely warning-level). |

### 8.4 Comments — CO (relationship to parent)

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 8.4 | CO IDVAR populated but IDVARVAL null (or vice versa) | CORE-000218, CORE-000135 | exact | Already covered (cf. 5.2.1). Clone with SENDIG authority. |
| 8.4 | CO IDVAR/IDVARVAL populated but RDOMAIN null | CORE-000017, CORE-000090, CORE-000134 | exact | Three SDTMIG rules cover the IDVAR/IDVARVAL/RDOMAIN nullability matrix. Clone with SENDIG authority. |

### 8.5 Pooling — POOLDEF

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| 8.5.1 | POOLDEF key fields null | — | no match | POOLDEF SEND-only. Author from scratch. |
| 8.5.1 | POOLID with zero subjects | — | no match | Same. |
| 8.5.1 | Same POOLID reused for different subjects | — | no match | Same. |
| 8.5.1 | USUBJID in POOLDEF not in DM | CORE-000201 | close | CORE-000201 enforces USUBJID present in DM.USUBJID. Re-target to POOLDEF (which is RELATIONSHIP class, not AP--). |

---

## 9 Appendices

### Appendix C: tumor.xpt Mapping

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| App. C | Subject lacks EX record with EXSTDTC populated | — | no match | tumor.xpt mapping is SEND-specific. Author from scratch. |
| App. C | Scheduled organ lacks MI record | — | no match | Same. |
| App. C | Sample autolyzed but MISPCUFL ≠ "N" | — | no match | MISPCUFL SEND-only. |
| App. C | TFRESCAT METASTATIC inconsistency | — | no match | TF/tumor pathology SEND-only. |
| App. C | Tumor lacks at least one TF record | — | no match | Same. |
| App. C | TFDETECT null on tumor in experimental phase | CORE-000356 | close | Generic Req-null; clone-and-scope to TF.TFDETECT. |

### Appendix E: SDTM Variables to Never Use in SEND

| SENDIG IG Section | SENDIG rule (short) | SDTMIG-3.4 CORE-ID | Match quality | Retargeting notes |
|---|---|---|---|---|
| App. E | Events dataset contains SDTM-only Events variables | — | no match | These rules are inverse to SDTM (presence-forbidden in SEND). Author from scratch as Variable Metadata Check rules. |
| App. E | DM contains SDTM-only DM variables | — | no match | Same — author from scratch. ACTARMCD/ACTARM/COUNTRY/DTHDTC/DTHFL/ETHNIC/INVID/INVNAM/RACE/RFICDTC/RFPENDTC are all valid in SDTM, all forbidden in SEND. |
| App. E | Interventions dataset contains PRESP | CORE-000015, CORE-000260 | partial | CORE-000015/260 enforce PRESP usage rules in SDTM. SEND-side is reversed (forbid presence); author from scratch. |
| App. E | TS contains SDTM-only TS variables (TSVALCD, TSVCDREF, TSVCDVER) | — | no match | TSVALCD/TSVCDREF/TSVCDVER are SDTM-valid (cf. CORE-001080, CORE-000728); SEND forbids them. Author SEND-side from scratch. |

---

## Summary

Match-quality counts across the cross-reference table:

| Quality | Count |
|---|---|
| `exact` | 41 |
| `close` | 47 |
| `partial` | 14 |
| `no match` | 53 |
| **Total candidates** | **155** |

Note: counts treat each row of the cross-reference table as one candidate.
A few candidates are multi-domain (e.g., the same generic rule appears
once in each Findings sub-section); each occurrence is counted
separately, matching the candidate-rule overview's row structure.

## Re-targetable batch (highest ROI)

The following SDTMIG-3.4 CORE rules each match three or more SENDIG
candidate rows and are the highest-ROI clones to produce first. For each,
re-target by (a) adding a SENDIG v3.1.1 Authority entry and (b) adjusting
`Scope.Domains` (and where applicable `Scope.Classes`) to the SEND
domain set.

| SDTMIG-3.4 CORE-ID | Description (short) | Matched SENDIG rows | Re-target action |
|---|---|---|---|
| CORE-000356 | Required variable null | 17 (DM, SE, TE, TA, TX, TS, RELREC, SUPP--, BW Reqs, LB Reqs, OM Reqs, PC Reqs, PP Reqs, TF, EX Reqs, BG Reqs, App.C TFDETECT) | The single highest-ROI rule. Use it as the spine for every "Req variable not null" SEND check; just swap the domain/Req list per dataset. |
| CORE-000334 | Expected variable not present | 4.2.5, 7.4.2, 7.6.2 | Authoritative scaffolding for SEND `Exp` Should-Include lists in TX/TS. |
| CORE-000864 | --ENDTC required when --ENDY present | BG, FW, VS, EG, CV, RE interval-end checks | Already Findings/ALL; clone with SENDIG authority. |
| CORE-000718 | --STDTC ≤ --ENDTC | 4.4.1, 6.1.1 (EX) | Already covers Interventions/Events/SP; extend Class scope to Findings for SEND interval domains. |
| CORE-000529 / CORE-000553 | Study-day algorithm | 4.4.4, 6.1.1 (EXSTDY) | Same algorithm in SEND. Clone with SENDIG authority. |
| CORE-000099 | --ORRES and --STAT both populated | 4.5, PC PCSTAT, PP PPSTAT | Tighten the leaf operator to `--STAT eq "NOT DONE"` per the SEND-specific framing. |
| CORE-000200 | --ORRES not missing when --STAT null or --DRVFL≠Y | 4.5 | Direct clone. |
| CORE-000732/CORE-000542/CORE-000863 | --STRESC/--STRESN coupling | 4.5 (multiple rows) | Clone all three with SENDIG authority. |
| CORE-000774 | --STAT NOT DONE -> --REASND populated | 4.5, MA, MI | Direct clone. |
| CORE-000132/CORE-000318/CORE-000303 | ETCD/ELEMENT, ARM/ARMCD, TESTCD/TEST 1:1 | 5.1.1, 6.3 (general), 7.2.1, 7.3.1 | Clone all three; one-to-one rules port verbatim. |
| CORE-000206 | IDVAR/IDVARVAL must match a record in RDOMAIN | 5.2.1, 8.2.1, 8.3 | Single check covers CO/RELREC/SUPP-- IDVAR resolution. |
| CORE-000220 | --TESTCD > 8 / invalid chars / starts with digit | 4.2.1 (Findings) | Direct clone. |
| CORE-000221/CORE-000222 | QNAM>8/QLABEL>40 | 4.2.1 (SUPP--) | Direct clone. |
| CORE-000010/CORE-000088/CORE-000147/CORE-000148/CORE-000019 | Length checks (ARMCD≤20, SETCD≤8, TSPARMCD≤8, TSPARM≤40, label≤40) | 4.2.1 (multiple rows) | All length rules port verbatim with SENDIG authority. |
| CORE-000616/CORE-000642 | --STINT/--ENINT -> --TPTREF | 6.3.17–6.3.19 (EG/CV/RE) | Direct clone. |
| CORE-000201 | USUBJID present in DM.USUBJID | 6.1.1 (EX), 8.5.1 (POOLDEF) | Re-target by extending scope (POOLDEF is RELATIONSHIP class, not AP--). |

Authoring these ~25 distinct SDTMIG rules first will retire roughly
two-thirds of the cross-referenced SENDIG candidates with minimal change
beyond Authority and Scope.

## SEND-specific gap list

Candidate rules that have **no SDTMIG-3.4 analog** and must be authored
from scratch (organised by IG section). These are the rules whose intent
is grounded in SEND-only constructs (POOLID/POOLDEF, --SPID cross-domain
mass tracking, tumor pathology TF/MI/MA, PC PT0H/BLQ/ALQ pre-dose
convention, TX trial sets, SEND-only domain CT, SEND-restricted variable
lists in Appendix E).

- **4.1.1** — SUPP-- timing-variable-name ban; RELREC timing-variable-name ban; TE/TA/TX/TS foreign-timing-variable ban.
- **4.2.1** — --TEST > 40 chars; ELEMENT > 40 chars; ARM > 40 chars; variable-name not all-uppercase.
- **4.2.3** — POOLID reused across subject sets; POOLID referenced in Findings not in POOLDEF; POOLDEF zero-subject pool.
- **4.3** — All CT-membership rules (delegated to CT generator on SEND CT package).
- **4.4.1** — Generic --DTC ISO 8601 format (SDTMIG only checks specific TS values).
- **4.5.1** — SUPP-- CALCN BLQ/ALQ convention.
- **5.1.1** — BRTHDTC derived-from-RFSTDTC heuristic; DM.SETCD vs TX.SETCD (TX SEND-only); SPECIES/STRAIN CT.
- **6.2.1** — Every-subject-must-have-DS-record completeness; DSSTDTC interval-of-uncertainty form; DSDECOD SEND CT.
- **6.3 general** — --BLFL post-dose detection; --DRVFL collected-data heuristic; --EXCLFL/--REASEX coupling (SDTMIG forbids, SEND uses).
- **6.3.1–6.3.19** — All SEND-domain TESTCD codelists; UNREMARKABLE/NORMAL sentinel rules; --SPID cross-domain mass-tracking (CL/PM/MA/MI/TF); MISPCUFL autolyzed-sample logic; PC PT0H pre-dose convention; PC BLQ/ALQ vs PCSTRESN/PCLLOQ/PCSTRESU; PC unscheduled timing-fields ban; PP/PC TPTREF cardinality and EX dose-time linkage; SC vs DM data-placement; necropsy-completeness checks; TF tumor cross-domain linkages and detection-day calculations.
- **7.3.1** — TAETORD strictly-increasing-from-1; arm-element abutment.
- **7.4.x** — TX entire family (TXSEQ uniqueness, TXPARMCD lookups, SETCD ARMCD requirement, distinct (TXPARMCD,TXVAL) tuples, expected TX parameters per SETCD).
- **7.6.x** — SNDIGVER fixed-value check; SDESIGN/SSTYP CT; single-record TSPARMCD list (SNDIGVER, SDESIGN, SSTYP, SNDCTVER); SEND-specific TS Should-Include list.
- **8.2.1** — RELTYPE CT membership; RELID record-level vs domain-level mixing; POOLID ban on domain-to-domain RELREC.
- **8.3** — QORIG CT membership; SUPP-- modeling-judgment ("should be CO/SC/etc.").
- **8.5.1** — POOLDEF Req-null, zero-subject pool, POOLID-reuse (entire POOLDEF dataset is SEND-only).
- **App. C** — All tumor.xpt mapping completeness rules.
- **App. E** — All SDTM-only-variable presence forbiddances (Events/DM/Interventions/TS lists are SEND-specific guardrails).

These are the rules to author from scratch using the templates in
`CORE-RULES-AUTHORING-GUIDELINES.md`. Most are Variable Metadata Checks
or simple Record Data checks; tumor cross-domain rules are the most
complex (they need `get_dataset` Operations and join logic).
