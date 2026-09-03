# SENDIG v3.1.1 Conformance Rules — Overview

This document is an overview of candidate conformance rules derived from a
careful manual reading of the *CDISC Standard for Exchange of Nonclinical
Data Implementation Guide: Nonclinical Studies (Version 3.1.1 Final)*
(file: `SENDIG_v3.1.1.pdf`, 244 pages, 2021-03-30).

Each row is a candidate rule. The row records:

- **IG Section** — the section in SENDIG v3.1.1 that the rule is derived
  from.
- **Rule description** — single-sentence intent, written in
  "Raise an error when …" form.
- **Domain(s)** — SEND domain code(s) the rule applies to. `--` denotes
  the SDTM wildcard for the current domain prefix.
- **Variables/Columns** — the variables involved in the check.

Notes:

- This is an *overview* only; final JSON rules will be authored later
  using the patterns in `CORE-RULES-AUTHORING-GUIDELINES.md` and the
  schema in `CORE-RULES-SPECIFICATION.md`.
- A "subject-level dataset" is one that has USUBJID as a key (most SEND
  domains). A "pool-level dataset" uses POOLID instead (LB, FW, CL, PC,
  PP can be pool-level).
- "Findings dataset" means any dataset based on the Findings general
  observation class (BW, BG, CL, DD, FW, LB, MA, MI, OM, PM, PC, PP, SC,
  TF, VS, EG, CV, RE).
- "Subject-level findings dataset" means a Findings dataset that uses
  USUBJID (i.e., not pool-level).

---

## 4 Assumptions for Domain Models (cross-cutting)

### 4.1 General Assumptions

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 4.1.1 | Raise an error when a SUPP-- dataset contains any standard timing variable (timing variables belong on the parent record). | SUPP-- | QNAM (must not match any --DTC, --DY, --STDTC, --ENDTC, --TPT, --TPTNUM, --TPTREF, --RFTDTC, --ELTM, --STDY, --ENDY, --DUR, --STINT, --ENINT, --NOMDY, --NOMLBL, VISITDY) |
| 4.1.1 | Raise an error when a RELREC dataset contains any standard timing variable. | RELREC | (any timing variable column) |
| 4.1.1 | Raise an error when any Trial Design dataset (TE, TA, TX, TS) contains a timing variable other than those defined in its own model. | TE, TA, TX, TS | column names |
| 4.1.4 | Raise an error when the SEND dataset filename is not the lowercase domain code followed by `.xpt` (e.g., `dm.xpt`, `lb.xpt`, `relrec.xpt`, `suppdm.xpt`). | all | (file name) |

### 4.2 Variable Naming, Length, and Character Conventions

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 4.2.1 | Raise an error when a `--TESTCD` value is longer than 8 characters, contains characters other than letters, numbers, or underscores, or starts with a number. | findings (LB, MA, MI, OM, PM, BW, BG, CL, DD, FW, PC, PP, SC, TF, VS, EG, CV, RE) | --TESTCD |
| 4.2.1 | Raise an error when a `--TEST` value is longer than 40 characters. | findings | --TEST |
| 4.2.1 | Raise an error when a SUPP-- `QNAM` value is longer than 8 characters, contains invalid characters, or starts with a number. | SUPP-- | QNAM |
| 4.2.1 | Raise an error when a SUPP-- `QLABEL` value is longer than 40 characters. | SUPP-- | QLABEL |
| 4.2.1 | Raise an error when an `ETCD` value is longer than 8 characters or contains invalid characters. | TE, TA | ETCD |
| 4.2.1 | Raise an error when an `ELEMENT` value is longer than 40 characters. | TE, TA | ELEMENT |
| 4.2.1 | Raise an error when an `ARMCD` value is longer than 20 characters. | DM, TA | ARMCD |
| 4.2.1 | Raise an error when an `ARM` value is longer than 40 characters. | DM, TA | ARM |
| 4.2.1 | Raise an error when a `SETCD` value is longer than 8 characters. | DM, TX | SETCD |
| 4.2.1 | Raise an error when a `TXPARMCD` value is longer than 8 characters. | TX | TXPARMCD |
| 4.2.1 | Raise an error when a `TSPARMCD` value is longer than 8 characters. | TS | TSPARMCD |
| 4.2.1 | Raise an error when a `TXPARM` or `TSPARM` value is longer than 40 characters. | TX, TS | TXPARM, TSPARM |
| 4.2.1 | Raise an error when a variable name in any submitted dataset is not entirely uppercase. | all | (column names) |
| 4.2.1 | Raise an error when a Variable Label is longer than 40 characters. | all | (Define-XML variable label metadata) |
| 4.2.2 | Raise an error when the `DOMAIN` value does not match the dataset's domain code, where the first character is A–Z, the second is A–Z or 0–9, both uppercase, with no special characters. | all | DOMAIN |
| 4.2.3 | Raise an error when `USUBJID` is null in a subject-level dataset. | DM, EX, DS, CO, SE, SC, BW, BG, CL, DD, MA, MI, OM, PM, TF, VS, EG, CV, RE, LB, FW, PC, PP (when subject-level) | USUBJID |
| 4.2.3 | Raise an error when `POOLID` is null and `USUBJID` is also null in a pool-eligible dataset (LB, FW, CL, PC, PP). | LB, FW, CL, PC, PP | USUBJID, POOLID |
| 4.2.3 | Raise an error when both `USUBJID` and `POOLID` are populated on the same record (they are mutually exclusive). | LB, FW, CL, PC, PP, RELREC, SUPP-- | USUBJID, POOLID |
| 4.2.3 | Raise an error when two animals on the same study share the same `USUBJID`. | DM | USUBJID |
| 4.2.3 | Raise an error when a `POOLID` is reused for different sets of subjects within a study. | POOLDEF | POOLID, USUBJID |
| 4.2.3 | Raise an error when a `POOLID` referenced in a finding dataset is not defined in POOLDEF. | LB, FW, CL, PC, PP, POOLDEF | POOLID |
| 4.2.3 | Raise an error when a `POOLID` definition consists of zero subjects. | POOLDEF | POOLID, USUBJID |
| 4.2.5 | Raise an error when any "Req" (required) Core variable is null in any submitted dataset. | all | (per dataset, the Req-flagged variables) |
| 4.2.5 | Raise an error when any "Exp" (expected) Core variable is missing from the dataset (the column itself must exist; values may be null with appropriate justification). | all | (per dataset, the Exp-flagged variables) |

### 4.3 Controlled Terminology

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 4.3 | Raise an error when a variable that uses a controlled-terminology codelist contains a value not in that codelist (e.g., SEX, ROUTE, UNIT, NY, ND, POSITION, FRM, etc.). | DM, EX, LB, VS, …, all | SEX, EXROUTE, EXDOSFRM, --ORRESU, --STRESU, --STAT, --POS, etc. |

### 4.4 Timing Variables

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 4.4.1 | Raise an error when a `--DTC` value is not in ISO 8601 extended format (`YYYY-MM-DDThh:mm:ss` and permitted truncations). | all (any --DTC) | --DTC |
| 4.4.1 | Raise an error when a `--DTC` value contains a space, AM/PM marker, or any non-ISO-8601 component. | all | --DTC |
| 4.4.1 | Raise an error when an `--ENDTC` precedes its corresponding `--STDTC` for the same record. | all | --STDTC, --ENDTC |
| 4.4.3 | Raise an error when a `--DUR` value is not in ISO 8601 duration format (`PnYnMnDTnHnMnS`) or mixes weeks (`W`) with other components. | all | --DUR, TEDUR, DOSDUR |
| 4.4.4 | Raise an error when `--DY = 0` (study day zero is not allowed; days run from -n…-1 then 1…m relative to RFSTDTC). | all | --DY, RFSTDTC |
| 4.4.4 | Raise an error when `--DY` is not consistent with `(--DTC date) - (DM.RFSTDTC date) + 1` for `--DTC ≥ RFSTDTC`, or `(--DTC date) - (DM.RFSTDTC date)` otherwise. | all | --DY, --DTC, DM.RFSTDTC |
| 4.4.7 | Raise an error when within the same domain/`--CAT`/`--SCAT` a `--TPT` value maps to multiple `--TPTNUM` values, or a `--TPTNUM` value maps to multiple `--TPT` values (the relationship must be 1:1). | findings | --TPT, --TPTNUM, --CAT, --SCAT |
| 4.4.7 | Raise an error when `--TPTREF` is populated but `--RFTDTC` is null on the same record. | findings | --TPTREF, --RFTDTC |
| 4.4.7 | Raise an error when `--ELTM` is populated but `--TPTREF` is null. | findings | --ELTM, --TPTREF |
| 4.4.7 | Raise an error when `--ELTM` is not in ISO 8601 duration format. | findings | --ELTM |

### 4.5 Original and Standardized Results

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 4.5 | Raise an error when `--ORRES` is null and `--STAT` is not "NOT DONE". | findings | --ORRES, --STAT |
| 4.5 | Raise an error when `--ORRES` is populated and `--STAT` is "NOT DONE". | findings | --ORRES, --STAT |
| 4.5 | Raise an error when `--STRESC` is null while `--ORRES` is populated. | findings | --ORRES, --STRESC |
| 4.5 | Raise an error when `--STRESN` is populated but its character form `--STRESC` is null or non-numeric. | findings | --STRESC, --STRESN |
| 4.5 | Raise an error when `--STAT` is "NOT DONE" and `--REASND` is null (reason for non-completion is required when status is NOT DONE). | findings | --STAT, --REASND |
| 4.5 | Raise an error when `--STAT` is "NOT DONE" and `--ORRESU`, `--STRESU`, `--STRESC`, or `--STRESN` are populated. | findings | --STAT and the result/unit columns |
| 4.5.1 | Raise an error when a SUPP-- record uses `--CALCN` for a parent record where `--STRESC` is not "BLQ", "ALQ", or otherwise an unquantifiable result (CALCN's reserved purpose is to record the numeric interpretation used for BLQ/ALQ). | SUPP-- (suppPC, etc.) | QNAM ("CALCN"), parent --STRESC |

---

## 5 Special-Purpose Domains

### 5.1 Demographics — DM

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 5.1.1 | Raise an error when `STUDYID`, `DOMAIN`, `USUBJID`, `SUBJID`, `RFSTDTC`, `SETCD`, or `SEX` is null on a DM record. | DM | STUDYID, DOMAIN, USUBJID, SUBJID, RFSTDTC, SETCD, SEX |
| 5.1.1 | Raise an error when DM contains more than one record per `USUBJID`. | DM | USUBJID |
| 5.1.1 | Raise an error when `ARM` and `ARMCD` are not 1:1 (the same ARMCD must always be paired with the same ARM, and vice versa). | DM, TA | ARM, ARMCD |
| 5.1.1 | Raise an error when `DM.ARMCD` does not appear in TA.ARMCD or `DM.ARM` does not appear in TA.ARM. | DM, TA | ARM, ARMCD |
| 5.1.1 | Raise an error when both `AGE` and `AGETXT` are populated on the same DM record. | DM | AGE, AGETXT |
| 5.1.1 | Raise an error when `AGE` and `AGETXT` are both null on a DM record. | DM | AGE, AGETXT, AGEU |
| 5.1.1 | Raise an error when `AGEU` is null while `AGE` or `AGETXT` is populated. | DM | AGE, AGETXT, AGEU |
| 5.1.1 | Raise an error when `BRTHDTC` appears to be derived from RFSTDTC and AGE rather than collected (BRTHDTC must be an actually known birth date, not derived). | DM | BRTHDTC, RFSTDTC, AGE |
| 5.1.1 | Raise an error when `DM.SETCD` does not match any `TX.SETCD` value for the study. | DM, TX | SETCD |
| 5.1.1 | Raise an error when `DM.SPECIES` or `DM.STRAIN` is not in the SEND controlled terminology codelist (SPECIES, STRAIN). | DM | SPECIES, STRAIN |

### 5.2 Comments — CO

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 5.2.1 | Raise an error when CO `RDOMAIN` is populated but does not match any submitted domain. | CO | RDOMAIN |
| 5.2.1 | Raise an error when CO `IDVAR` is populated but `IDVARVAL` is null, or vice versa (must both be populated together or both null). | CO | IDVAR, IDVARVAL |
| 5.2.1 | Raise an error when CO `IDVAR`/`IDVARVAL` cannot be matched to a record in the parent dataset identified by RDOMAIN. | CO | RDOMAIN, IDVAR, IDVARVAL |
| 5.2.1 | Raise an error when CO `DOMAIN` is anything other than "CO". | CO | DOMAIN |

### 5.3 Subject Elements — SE

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 5.3.1 | Raise an error when `SESTDTC` is null on a SE record. | SE | SESTDTC |
| 5.3.1 | Raise an error when within a subject the `SEENDTC` of one element does not equal the `SESTDTC` of the next element (elements must abut without gaps). | SE | USUBJID, ETCD, SESTDTC, SEENDTC, SEENDY, SESTDY |
| 5.3.1 | Raise an error when `SE.ETCD` is not "UNPLAN" but does not match any `TE.ETCD` value. | SE, TE | ETCD |
| 5.3.1 | Raise an error when `SE.ETCD` is "UNPLAN" but `ELEMENT` is populated (for unplanned elements ELEMENT should be null). | SE | ETCD, ELEMENT |
| 5.3.1 | Raise an error when `SE.ETCD` is "UNPLAN" but `SEUPDES` is null (description of the unplanned element is required). | SE | ETCD, SEUPDES |
| 5.3.1 | Raise an error when `SE.ETCD` is not "UNPLAN" but `SEUPDES` is populated. | SE | ETCD, SEUPDES |

---

## 6.1 Interventions

### 6.1.1 Exposure — EX

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.1.1 | Raise an error when `EXTRT` contains dosage strength, formulation, or vehicle information (those belong in EXDOSE/EXDOSU/EXDOSFRM/EXVEHICLE/EXVAMT/EXVAMTU). | EX | EXTRT |
| 6.1.1 | Raise an error when `EXDOSFRQ` is null on an EX record. | EX | EXDOSFRQ |
| 6.1.1 | Raise an error when `EXROUTE` is null on an EX record. | EX | EXROUTE |
| 6.1.1 | Raise an error when `EXDOSE` is null while `EXDOSU` is populated, or vice versa. | EX | EXDOSE, EXDOSU |
| 6.1.1 | Raise an error when both `USUBJID` and `POOLID` are null on an EX record. | EX | USUBJID, POOLID |
| 6.1.1 | Raise an error when an EX `USUBJID` does not appear in the DM dataset. | EX, DM | USUBJID |
| 6.1.1 | Raise an error when `EXSTDTC` is later than `EXENDTC` on the same record. | EX | EXSTDTC, EXENDTC |
| 6.1.1 | Raise an error when `EXSTDY` is calculated to a value that does not equal `(EXSTDTC - DM.RFSTDTC) + 1` (or equivalent for negative days). | EX, DM | EXSTDTC, EXSTDY, RFSTDTC |

---

## 6.2 Events

### 6.2.1 Disposition — DS

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.2.1 | Raise an error when a subject in DM has no record in DS at study completion (every subject must have at least one disposition record). | DM, DS | USUBJID |
| 6.2.1 | Raise an error when `DSSTDTC` is recorded as an interval of uncertainty (e.g., "found dead between two times") rather than a single ISO 8601 date/time. | DS | DSSTDTC |
| 6.2.1 | Raise an error when `DSDECOD` is not in the SEND DSDECOD controlled terminology. | DS | DSDECOD |
| 6.2.1 | Raise an error when DS contains more than one terminal-disposition record (TERMINAL SACRIFICE, FOUND DEAD, MORIBUND SACRIFICE, ACCIDENTAL DEATH, NON-MORIBUND SACRIFICE) for the same subject. | DS | USUBJID, DSDECOD |

---

## 6.3 Findings — common patterns

These rules apply to all Findings-class domains (BW, BG, CL, DD, FW, LB,
MA, MI, OM, PM, PC, PP, SC, TF, VS, EG, CV, RE) unless restricted.

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3 (general) | Raise an error when `--SEQ` is not unique within `STUDYID` + `USUBJID` (or `POOLID`) + `DOMAIN`. | findings | STUDYID, USUBJID/POOLID, DOMAIN, --SEQ |
| 6.3 (general) | Raise an error when `--TESTCD` and `--TEST` are not 1:1 (the same TESTCD must always pair with the same TEST and vice versa). | findings | --TESTCD, --TEST |
| 6.3 (general) | Raise an error when `--TESTCD` is populated but `--TEST` is null, or vice versa. | findings | --TESTCD, --TEST |
| 6.3 (general) | Raise an error when `--CAT` and `--SCAT` are populated and the SCAT value is not a recognized sub-category of the CAT value (sponsor-defined hierarchical consistency). | findings | --CAT, --SCAT |
| 6.3 (general) | Raise an error when `--BLFL = "Y"` for a record whose timing is post-dose (baseline flag must mark a pre-dose/baseline measurement). | findings | --BLFL, --DTC, --DY, RFSTDTC |
| 6.3 (general) | Raise an error when `--DRVFL = "Y"` (derived) but the record contains data that should only originate from collection (sponsor decision; flag for review). | findings | --DRVFL |
| 6.3 (general) | Raise an error when `--EXCLFL = "Y"` but `--REASEX` is null (excluded results must carry a reason). | findings | --EXCLFL, --REASEX |
| 6.3 (general) | Raise an error when `--REASEX` is populated but `--EXCLFL` is not "Y". | findings | --EXCLFL, --REASEX |

### 6.3.1 Body Weights — BW

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.1 | Raise an error when `BWTESTCD` is not "BW" or "TERMBW" (the only valid body-weight test codes). | BW | BWTESTCD |
| 6.3.1 | Raise an error when `BWORRESU` or `BWSTRESU` is missing on a BW record. | BW | BWORRESU, BWSTRESU |
| 6.3.1 | Raise an error when `BWSTRESN` is null while `BWORRES` is populated and numeric. | BW | BWORRES, BWSTRESN |

### 6.3.2 Body Weight Gains — BG

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.2 | Raise an error when `BGTESTCD` is not in the BGTESTCD codelist (e.g., "BWGAIN"). | BG | BGTESTCD |
| 6.3.2 | Raise an error when a BG record refers to an interval (BGSTDTC..BGENDTC) and `BGENDTC` is missing. | BG | BGSTDTC, BGENDTC |
| 6.3.2 | Raise an error when `BGORRESU`/`BGSTRESU` is missing on a body weight gain record. | BG | BGORRESU, BGSTRESU |

### 6.3.3 Clinical Observations — CL

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.3 | Raise an error when a CL record uses `CLSEV` but `CLSTRESC` is null. | CL | CLSEV, CLSTRESC |
| 6.3.3 | Raise an error when CL findings exist for a subject for an examination but no record marks "NORMAL" (when no abnormalities are observed, `--STRESC = "NORMAL"` must be recorded for CL). | CL | CLSTRESC, CLSTAT |
| 6.3.3 | Raise an error when `CLSPID` does not match a `--SPID` of the same value in any of CL/PM/MA/MI/TF for the same subject (mass-identifier consistency). | CL, PM, MA, MI, TF | --SPID |

### 6.3.4 Death Diagnosis and Details — DD

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.4 | Raise an error when a DD record exists for a subject whose DM/DS does not indicate death (DD only applies to subjects that died). | DD, DS | USUBJID, DSDECOD |
| 6.3.4 | Raise an error when `DDDTC` does not match the corresponding death-related `DSSTDTC` on DS for the same subject. | DD, DS | DDDTC, DSSTDTC |

### 6.3.5 Food and Water Consumption — FW

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.5 | Raise an error when `FWTESTCD` is not in the FWTESTCD codelist (e.g., "FOODCONS", "WATERCONS"). | FW | FWTESTCD |
| 6.3.5 | Raise an error when an FW record represents an interval (FWSTDTC..FWENDTC) but `FWENDTC` is null. | FW | FWSTDTC, FWENDTC |
| 6.3.5 | Raise an error when `POOLID` is populated on an FW record and is not present in POOLDEF. | FW, POOLDEF | POOLID |

### 6.3.6 Laboratory Test Results — LB

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.6 | Raise an error when both standardized reference ranges (`LBSTNRLO`/`LBSTNRHI`) and original reference ranges (`LBORNRLO`/`LBORNRHI`) are populated on the same record (only one set should be populated). | LB | LBSTNRLO, LBSTNRHI, LBORNRLO, LBORNRHI |
| 6.3.6 | Raise an error when `LBSPEC` is null on an LB record (specimen type is required for laboratory results). | LB | LBSPEC |
| 6.3.6 | Raise an error when `LBSTRESN` is populated but `LBSTRESU` is null. | LB | LBSTRESN, LBSTRESU |
| 6.3.6 | Raise an error when `LBORRESU` and `LBSTRESU` use different physical-quantity dimensions (e.g., mg vs L for the same test). | LB | LBORRESU, LBSTRESU |

### 6.3.7 Macroscopic Findings — MA

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.7 | Raise an error when a subject scheduled for necropsy has zero MA records (every subject at necropsy must have at least one MA record). | MA, DS | USUBJID, MASTAT |
| 6.3.7 | Raise an error when `MASTAT = "NOT DONE"` and `MAREASND` is null. | MA | MASTAT, MAREASND |
| 6.3.7 | Raise an error when an MA examination shows no abnormality and `MASTRESC` is not "UNREMARKABLE" (or equivalent normal value). | MA | MASTRESC, MASTAT |
| 6.3.7 | Raise an error when `MASPID` is populated but does not match a `--SPID` of the same value in CL/PM/MI/TF for the same subject. | MA, CL, PM, MI, TF | --SPID |

### 6.3.8 Microscopic Findings — MI

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.8 | Raise an error when a tissue scheduled by protocol has no MI record for an examined subject (every protocol-scheduled tissue must produce a record, with `MISTAT = "NOT DONE"` if not done). | MI | MITESTCD, MISTAT, MISPEC |
| 6.3.8 | Raise an error when `MISTAT = "NOT DONE"` and `MIREASND` is null. | MI | MISTAT, MIREASND |
| 6.3.8 | Raise an error when an MI examination shows no abnormality and `MISTRESC` is not "UNREMARKABLE". | MI | MISTRESC, MISTAT |
| 6.3.8 | Raise an error when a sample is autolyzed/unusable but `MISPCUFL` is not "N". | MI | MISPCUFL, MISTAT |
| 6.3.8 | Raise an error when `MISPID` is populated but does not match a `--SPID` of the same value in CL/PM/MA/TF for the same subject. | MI, CL, PM, MA, TF | --SPID |

### 6.3.9 Organ Measurements — OM

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.9 | Raise an error when `OMSPEC` is null on an OM record (organ identity is required). | OM | OMSPEC |
| 6.3.9 | Raise an error when `OMSTRESU` is missing while `OMSTRESN` is populated. | OM | OMSTRESN, OMSTRESU |

### 6.3.10 Palpable Masses — PM

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.10 | Raise an error when `PMSPID` is populated but does not match a `--SPID` of the same value in CL/MA/MI/TF for the same subject (mass identification across in-life and pathology must be consistent). | PM, CL, MA, MI, TF | --SPID |
| 6.3.10 | Raise an error when a PM record represents the disappearance/conversion of a mass but the linkage to the original PMSPID is missing (mass converging/diverging linkage). | PM | PMSPID, PMGRPID |

### 6.3.11 Pharmacokinetics Concentrations — PC

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.11 | Raise an error when `PCSPEC` is null on a PC record. | PC | PCSPEC |
| 6.3.11 | Raise an error when a pre-dose sample collected for profile analysis has `PCELTM` not equal to "PT0H". | PC | PCELTM, PCTPT, PCTPTREF |
| 6.3.11 | Raise an error when `PCSTRESC` is "BLQ" or "ALQ" but `PCSTRESN` is populated (numeric form must be null when below/above limit of quantitation). | PC | PCSTRESC, PCSTRESN |
| 6.3.11 | Raise an error when `PCSTRESC = "BLQ"` and either `PCLLOQ` or `PCSTRESU` is null. | PC | PCSTRESC, PCLLOQ, PCSTRESU |
| 6.3.11 | Raise an error when `PCSTAT = "NOT DONE"` and `PCORRES` or `PCSTRESC` is populated. | PC | PCSTAT, PCORRES, PCSTRESC |
| 6.3.11 | Raise an error when `PCELTM` is null on a record used to compute a concentration profile (PCELTM must be populated for plasma concentrations contributing to a profile). | PC | PCELTM, PCCAT, PCTPTREF |
| 6.3.11 | Raise an error when an unscheduled PC test record has any of `PCTPT`, `PCTPTNUM`, `PCELTM`, `PCTPTREF`, `PCRFTDTC`, `PCEVLINT` populated. | PC | PCUSCHFL, PCTPT, PCTPTNUM, PCELTM, PCTPTREF, PCRFTDTC, PCEVLINT |
| 6.3.11 | Raise an error when both `USUBJID` and `POOLID` are null on a PC record. | PC | USUBJID, POOLID |

### 6.3.12 Pharmacokinetics Parameters — PP

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.12 | Raise an error when `PPSPEC` is null on a PP record. | PP | PPSPEC |
| 6.3.12 | Raise an error when `PPSTAT = "NOT DONE"` and `PPORRES` or `PPSTRESC` is populated. | PP | PPSTAT, PPORRES, PPSTRESC |
| 6.3.12 | Raise an error when `PPRFTDTC` is populated but `PPTPTREF` is null. | PP | PPRFTDTC, PPTPTREF |
| 6.3.12 | Raise an error when both `USUBJID` and `POOLID` are null on a PP record. | PP | USUBJID, POOLID |

### 6.3.13 PC/PP Cross-Domain

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.13 | Raise an error when a PP record references a `PPTPTREF` that does not appear as a `PCTPTREF` on the corresponding PC records for the same subject/pool. | PC, PP | PCTPTREF, PPTPTREF |
| 6.3.13 | Raise an error when a PP record's `PPRFTDTC` is populated and does not equal an `EX.EXSTDTC` for the matching dose described by `PPTPTREF`. | EX, PP | EXSTDTC, PPRFTDTC, PPTPTREF |
| 6.3.13 | Raise an error when SUPPPC `QNAM = "PCCALCN"` but the parent PC record's `PCSTRESC` is not "BLQ" or "ALQ" (PCCALCN is reserved for documenting numeric handling of below/above-limit values). | PC, SUPPPC | PCSTRESC, QNAM |

### 6.3.14 Subject Characteristics — SC

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.14 | Raise an error when an SC record contains data that belongs in DM (e.g., SEX, AGE, SPECIES, STRAIN). | SC | SCTESTCD |
| 6.3.14 | Raise an error when SC has more than one record per `(USUBJID, SCTESTCD)` (SC is one record per characteristic per subject). | SC | USUBJID, SCTESTCD |

### 6.3.15 Tumor Findings — TF

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.15 | Raise an error when a TF record has no corresponding MI record for the same subject and same mass identifier (TF is a subset of MI). | TF, MI | USUBJID, TFSPID, MISPID |
| 6.3.15 | Raise an error when `TFDETECT` is null on a TF record (TFDETECT is required for every tumor discovered during the experimental phase). | TF | TFDETECT |
| 6.3.15 | Raise an error when `TFDTHREL` is null (relationship-to-death is required and cannot be missing). | TF | TFDTHREL |
| 6.3.15 | Raise an error when `TFRESCAT` is "METASTATIC" but no TFGRPID-linked primary record (TFRESCAT="PRIMARY" or "MALIGNANT") exists (metastases must be linked to the primary). | TF | TFGRPID, TFRESCAT |
| 6.3.15 | Raise an error when a tumor was detected as a clinical sign and `TFDETECT ≠ (CLDTC - EXSTDTC) + 1` for the matching CL record. | TF, CL, EX | TFDETECT, CLDTC, EXSTDTC |
| 6.3.15 | Raise an error when a tumor was detected as a palpable mass and `TFDETECT ≠ (PMDTC - EXSTDTC) + 1` for the matching PM record. | TF, PM, EX | TFDETECT, PMDTC, EXSTDTC |
| 6.3.15 | Raise an error when a tumor was first detected at necropsy/histopathology and `TFDETECT ≠ (DSSTDTC - EXSTDTC) + 1`. | TF, DS, EX | TFDETECT, DSSTDTC, EXSTDTC |
| 6.3.15 | Raise an error when `TFSPID` is populated but does not match a `--SPID` of the same value in CL/PM/MA/MI for the same subject. | TF, CL, PM, MA, MI | --SPID |

### 6.3.16 Vital Signs — VS

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.16 | Raise an error when `VSTESTCD` is "TEMP" and `VSLOC` is null (location of measurement should be populated for body temperature). | VS | VSTESTCD, VSLOC |
| 6.3.16 | Raise an error when a vital-signs measurement covers a range and `VSENDTC` is null. | VS | VSDTC, VSENDTC |

### 6.3.17 ECG Test Results — EG

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.17 | Raise an error when `EGSTINT` is populated but `EGENINT` is null, or vice versa (EGSTINT/EGENINT must both be populated together to describe an assessment interval). | EG | EGSTINT, EGENINT |
| 6.3.17 | Raise an error when `EGSTINT`/`EGENINT` are populated but `EGTPTREF` is null. | EG | EGSTINT, EGENINT, EGTPTREF |
| 6.3.17 | Raise an error when an EG record covers a continuous evaluation interval but `EGENDTC` is null. | EG | EGDTC, EGENDTC |
| 6.3.17 | Raise an error when `EGEVLINT` is populated and `EGSTINT`/`EGENINT` are also populated (EVLINT is used in place of STINT/ENINT, not in addition). | EG | EGEVLINT, EGSTINT, EGENINT |

### 6.3.18 Cardiovascular Test Results — CV

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.18 | Raise an error when a CV measurement covers an evaluation interval but `CVENDTC` is null. | CV | CVDTC, CVENDTC |
| 6.3.18 | Raise an error when `CVSTINT` is populated but `CVTPTREF` is null. | CV | CVSTINT, CVTPTREF |
| 6.3.18 | Raise an error when both `CVSTINT`/`CVENINT` and `CVEVLINT` are populated on the same record. | CV | CVSTINT, CVENINT, CVEVLINT |

### 6.3.19 Respiratory Test Results — RE

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 6.3.19 | Raise an error when an RE measurement covers an evaluation interval but `REENDTC` is null. | RE | REDTC, REENDTC |
| 6.3.19 | Raise an error when `RESTINT` is populated but `RETPTREF` is null. | RE | RESTINT, RETPTREF |
| 6.3.19 | Raise an error when both `RESTINT`/`REENINT` and `REEVLINT` are populated on the same record. | RE | RESTINT, REENINT, REEVLINT |

---

## 7 Trial Design Model Datasets

### 7.2 Trial Elements — TE

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 7.2.1 | Raise an error when `STUDYID`, `DOMAIN`, `ETCD`, `ELEMENT`, or `TESTRL` is null on a TE record. | TE | STUDYID, DOMAIN, ETCD, ELEMENT, TESTRL |
| 7.2.1 | Raise an error when both `TEENRL` and `TEDUR` are null on the same TE record (at least one must be present). | TE | TEENRL, TEDUR |
| 7.2.1 | Raise an error when two TE records have the same `ETCD` but different `ELEMENT` values (or vice versa) — ETCD and ELEMENT must be 1:1. | TE | ETCD, ELEMENT |
| 7.2.1 | Raise an error when two TE records share `ETCD` and `ELEMENT` but differ in `TESTRL`/`TEENRL`/`TEDUR` (elements with different rules must be different elements). | TE | ETCD, ELEMENT, TESTRL, TEENRL, TEDUR |

### 7.3 Trial Arms — TA

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 7.3.1 | Raise an error when `STUDYID`, `DOMAIN`, `ARMCD`, `ARM`, `TAETORD`, or `ETCD` is null on a TA record. | TA | STUDYID, DOMAIN, ARMCD, ARM, TAETORD, ETCD |
| 7.3.1 | Raise an error when `TA.ETCD` does not appear in `TE.ETCD`. | TA, TE | ETCD |
| 7.3.1 | Raise an error when `TA.ARMCD` and `TA.ARM` are not 1:1. | TA | ARMCD, ARM |
| 7.3.1 | Raise an error when `TA.ARMCD` does not appear in `DM.ARMCD`, or `DM.ARMCD` does not appear in `TA.ARMCD`. | TA, DM | ARMCD |
| 7.3.1 | Raise an error when within an arm `TAETORD` is not a strictly increasing sequence of integers starting at 1. | TA | ARMCD, TAETORD |
| 7.3.1 | Raise an error when within an arm there are gaps between elements (consecutive elements must abut in time, no gaps). | TA | ARMCD, TAETORD |

### 7.4 Trial Sets — TX

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 7.4.1 | Raise an error when `STUDYID`, `DOMAIN`, `SETCD`, `SET`, `TXSEQ`, `TXPARMCD`, `TXPARM`, or `TXVAL` is null on a TX record. | TX | STUDYID, DOMAIN, SETCD, SET, TXSEQ, TXPARMCD, TXPARM, TXVAL |
| 7.4.1 | Raise an error when `TXSEQ` is not unique across the entire TX dataset. | TX | TXSEQ |
| 7.4.1 | Raise an error when a `SETCD` is missing the required `ARMCD` parameter (each trial set should be associated with a single Trial Arm). | TX | SETCD, TXPARMCD ("ARMCD") |
| 7.4.1 | Raise an error when a `SETCD` has a `TXPARMCD = "ARMCD"` value that does not appear in `TA.ARMCD`. | TX, TA | TXVAL when TXPARMCD="ARMCD", ARMCD |
| 7.4.1 | Raise an error when `DM.SETCD` does not appear in `TX.SETCD`. | DM, TX | SETCD |
| 7.4.1 | Raise an error when a single `SETCD` has more than one `ARMCD` value (a trial set must be associated with exactly one trial arm). | TX | SETCD, TXPARMCD, TXVAL |
| 7.4.1 | Raise an error when two `SETCD`s share an identical set of `(TXPARMCD, TXVAL)` entries (distinct trial sets must be distinguishable). | TX | SETCD, TXPARMCD, TXVAL |
| 7.4.2 | Raise an error when an "expected" Trial Set parameter (those flagged "Yes" in the Should-Include column: ARMCD, SPGRPCD, GRPLBL, TRTDOS, TRTDOSU) is not present for a SETCD. | TX | SETCD, TXPARMCD |

### 7.6 Trial Summary — TS

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 7.6.1 | Raise an error when `STUDYID`, `DOMAIN`, `TSSEQ`, `TSPARMCD`, or `TSPARM` is null on a TS record. | TS | STUDYID, DOMAIN, TSSEQ, TSPARMCD, TSPARM |
| 7.6.1 | Raise an error when `TSVAL` and `TSVALNF` are both null (one of them must be populated). | TS | TSVAL, TSVALNF |
| 7.6.1 | Raise an error when `TSVAL` is populated and `TSVALNF` is also populated (TSVALNF can only be present if TSVAL is null). | TS | TSVAL, TSVALNF |
| 7.6.1 | Raise an error when `TSVALNF` value is not in the NULLFLAVOR codelist (NI/INV/OTH/PINF/NINF/UNC/DER/UNK/ASKU/NAV/NASK/QS/TRC/MSK/NA). | TS | TSVALNF |
| 7.6.2 | Raise an error when an "expected" Trial Summary parameter is not present (for SEND, the parameters flagged "Yes" in the Should-Include column: AGEU, SDESIGN, DOSDUR, EXPENDTC, EXPSTDTC, GLPFL, ROUTE, SNDIGVER, SNDCTVER, SPECIES, SPLRNAM, SPREFID, SSPONSOR, STCAT, STDIR, STRAIN, STSTDTC, STITLE, SSTYP, TRT, TFCNTRY, TSTFLOC, TSTFNAM, TRMSAC, TRTCAS, TRTUNII, TRTV). | TS | TSPARMCD |
| 7.6.1 | Raise an error when more than one TS record exists for a TSPARMCD that should be a single record (SNDIGVER, SNDCTVER, SDESIGN, SSTYP). | TS | TSPARMCD, TSSEQ |
| 7.6.1 | Raise an error when `SNDIGVER` `TSVAL` is not "SEND Implementation Guide Version 3.1.1" (or matching the actual IG used). | TS | TSPARMCD ("SNDIGVER"), TSVAL |
| 7.6.1 | Raise an error when `SDESIGN` value is not in the controlled-terminology DESIGN codelist (e.g., PARALLEL, CROSSOVER, MATCHED PAIR, FACTORIAL). | TS | TSPARMCD ("SDESIGN"), TSVAL |
| 7.6.1 | Raise an error when `SSTYP` value is not in the controlled-terminology SSTYP codelist (REPEAT DOSE TOXICITY, CARDIOVASCULAR PHARMACOLOGY, etc.). | TS | TSPARMCD ("SSTYP"), TSVAL |

---

## 8 Representing Relationships and Data

### 8.2 RELREC

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 8.2.1 | Raise an error when `STUDYID`, `RDOMAIN`, `IDVAR`, or `RELID` is null on a RELREC record. | RELREC | STUDYID, RDOMAIN, IDVAR, RELID |
| 8.2.1 | Raise an error when both `USUBJID` and `POOLID` are null and the relationship is record-level (record-level relationships require subject or pool identification). | RELREC | USUBJID, POOLID, IDVARVAL |
| 8.2.1 | Raise an error when `RDOMAIN` is not the 2-letter code of a domain submitted in the study. | RELREC | RDOMAIN |
| 8.2.1 | Raise an error when `IDVAR`/`IDVARVAL` cannot be matched against a record in `RDOMAIN` for the given subject/pool. | RELREC | RDOMAIN, IDVAR, IDVARVAL, USUBJID/POOLID |
| 8.2.1 | Raise an error when `RELTYPE` is populated and is not "ONE" or "MANY". | RELREC | RELTYPE |
| 8.2.1 | Raise an error when a `RELID` value appears with both record-level (USUBJID/POOLID populated) and domain-level (USUBJID/POOLID null, RELTYPE populated) records — relationships must be one or the other. | RELREC | RELID, USUBJID, POOLID, RELTYPE |
| 8.2.1 | Raise an error when a `POOLID` is populated on a RELREC record being used for a domain-to-domain relationship (only record-to-record relationships may use POOLID). | RELREC | POOLID, RELTYPE |

### 8.3 SUPP-- (Supplemental Qualifiers)

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 8.3 | Raise an error when `STUDYID`, `RDOMAIN`, `QNAM`, `QLABEL`, or `QVAL` is null on a SUPP-- record. | SUPP-- | STUDYID, RDOMAIN, QNAM, QLABEL, QVAL |
| 8.3 | Raise an error when both `USUBJID` and `POOLID` are null on a SUPP-- record (except for SUPPDM where IDVAR/IDVARVAL also must be null). | SUPP-- | USUBJID, POOLID, IDVAR, IDVARVAL |
| 8.3 | Raise an error when `RDOMAIN` is not the 2-letter code of a domain submitted in the study. | SUPP-- | RDOMAIN |
| 8.3 | Raise an error when `QNAM` matches a standard variable name in the parent domain (SUPP-- is for *non-standard* qualifiers only). | SUPP-- | QNAM, RDOMAIN |
| 8.3 | Raise an error when within a SUPP-- dataset two records share the same `(USUBJID/POOLID, IDVAR, IDVARVAL, QNAM)` key. | SUPP-- | USUBJID, POOLID, IDVAR, IDVARVAL, QNAM |
| 8.3 | Raise an error when within a SUPP-- dataset the same `QNAM` appears with different `QLABEL` values. | SUPP-- | QNAM, QLABEL |
| 8.3 | Raise an error when `QORIG` is populated and not in the controlled set (COLLECTED, DERIVED, OTHER, NOT AVAILABLE). | SUPP-- | QORIG |
| 8.3 | Raise an error when `IDVAR`/`IDVARVAL` cannot be matched against a record in `RDOMAIN`. | SUPP-- | RDOMAIN, IDVAR, IDVARVAL |
| 8.3.1.2 | Raise an error when SUPP-- carries data that should have been a Comment (`CO`), a Subject Characteristic (`SC`), or a record in another general-observation-class domain. | SUPP-- | QNAM, QVAL |

### 8.4 Comments — CO (relationship to parent)

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 8.4 | Raise an error when CO record has `IDVAR` populated but `IDVARVAL` null (or vice versa). | CO | IDVAR, IDVARVAL |
| 8.4 | Raise an error when CO record has `IDVAR`/`IDVARVAL` populated but `RDOMAIN` is null. | CO | RDOMAIN, IDVAR, IDVARVAL |

### 8.5 Pooling — POOLDEF

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| 8.5.1 | Raise an error when `STUDYID`, `POOLID`, or `USUBJID` is null on a POOLDEF record. | POOLDEF | STUDYID, POOLID, USUBJID |
| 8.5.1 | Raise an error when a `POOLID` value contains zero subjects (a pool must consist of at least one subject). | POOLDEF | POOLID, USUBJID |
| 8.5.1 | Raise an error when the same `POOLID` is reused for different sets of subjects within a study (a new POOLID must be generated whenever the pool composition changes). | POOLDEF | POOLID, USUBJID |
| 8.5.1 | Raise an error when a `USUBJID` listed in POOLDEF does not appear in DM. | POOLDEF, DM | USUBJID |

---

## 9 Appendices

### Appendix C: tumor.xpt Mapping

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| App. C | Raise an error when a subject lacks an `EX` record with `EXSTDTC` populated (required as the basis for tumor.xpt DTHSACTM/TFDETECT calculations). | EX, DM | USUBJID, EXSTDTC |
| App. C | Raise an error when an organ scheduled for examination has no MI record (every scheduled organ must have a record, with `MISTAT = "NOT DONE"` if not examined). | MI | MITESTCD, MISTAT, MISPEC |
| App. C | Raise an error when a sample is found unusable (autolyzed) and `MISPCUFL` is not "N". | MI | MISPCUFL |
| App. C | Raise an error when secondary/multicentric tumors are included (TF) but `TFRESCAT = "METASTATIC"` is not consistently set, or are excluded but METASTATIC records are still present. | TF | TFRESCAT |
| App. C | Raise an error when a tumor (including secondary/multicentric) lacks at least one TF record. | TF | TFSPID |
| App. C | Raise an error when `TFDETECT` is null on a tumor discovered during the experimental phase. | TF | TFDETECT |

### Appendix E: SDTM Variables to Never Use in SEND

| IG Section | Rule description | Domain(s) | Variables/Columns |
|---|---|---|---|
| App. E | Raise an error when an Events dataset contains any of the SDTM-only variables `BDSYCD`, `HLT`, `HLTCD`, `HLGT`, `LLT`, `LLTCD`, `PARTY`, `PRTYID`, `PTCD`, `SCAN`, `SCONG`, `SDISAB`, `SDTH`, `SHOSP`, `SLIFE`, `SOD`, `SMIE`, `SOC`, `SOCCD`. | DS, CL, MA, MI, … (Events-class) | column names |
| App. E | Raise an error when DM contains any of the SDTM-only variables `ACTARMCD`, `ACTARM`, `COUNTRY`, `DTHDTC`, `DTHFL`, `ETHNIC`, `INVID`, `INVNAM`, `RACE`, `RFICDTC`, `RFPENDTC`. | DM | column names |
| App. E | Raise an error when an Interventions dataset (e.g., EX) contains the SDTM-only variable `PRESP`. | EX, … | PRESP |
| App. E | Raise an error when TS contains any of the SDTM-only variables `TSVALCD`, `TSVCDREF`, `TSVCDVER`. | TS | column names |

---

## Coverage Notes

The following areas of the IG were read but produced no candidate rules
beyond those above (mainly because the section is descriptive or
example-only): 7.5 (Additional TE/TA/TX examples), 8.1 (--GRPID
description; no enforceable invariant beyond uniqueness within subject),
8.6 (How To Determine Where Data Belong; modeling guidance, not data
constraints), Appendix A (CDISC SEND Team), Appendix B (Glossary),
Appendix D (Revision History), Appendix F (Disclaimers).

The candidate rules above are the starting point; subsequent stages
will:

1. Cross-reference each candidate against the SDTMIG-3.4 rule package
   (`rules-sdtmig-3-4.json`) to identify rules that already exist for
   SDTMIG and need only authority/scope re-targeting for SENDIG.
2. Group candidates by Rule_Type (Record Data, Variable Metadata Check,
   Dataset Metadata Check, Domain Presence Check, Value Check with
   Variable Metadata, Value Check with Dataset Metadata) before JSON
   authoring.
3. Resolve `--TPT`/`--CAT`/`--SCAT`/`--SPID` references, ISO 8601
   tolerances, and reference-time-point dependencies via Operations
   (`get_dataset`, `extract_metadata`, `record_count`, etc.).
4. Author Authorities entries citing **CDISC SENDIG v3.1.1** with the
   IG section reference.
