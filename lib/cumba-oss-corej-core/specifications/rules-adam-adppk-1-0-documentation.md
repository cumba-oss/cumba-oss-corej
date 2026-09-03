# ADaM PopPK v1.0 Conformance Rules Documentation

Auto-generated documentation from `rules-adam-adppk-1-0.json`.

**Total Rules:** 206  
**Standard:** ADaM PopPK IG v1.0  
**Model:** ADaM v2.1  

## Summary Statistics

| Rule Type | Count |
|-----------|-------|
| Record Data | 33 |
| Variable Metadata Check | 173 |

### By Category

| Category | Count |
|----------|-------|
| Structural / Conformance | 36 |
| Required Variable Checks | 10 |
| Variable Label Conformance | 80 |
| Variable Type Conformance | 80 |

---

## Structural and Conformance Rules

| Core ID | Description | Rule Type |
|---------|-------------|-----------|
| [ADPPK-000171](#adppk-000171) | ADPPK has one record per subject per parameter (analyte) per analysis timepoint per event (dosing or observation). | Record Data |
| [ADPPK-000172](#adppk-000172) | Required variables (Core=Req): STUDYID, USUBJID, USUBJIDN, AFRLT, EVID, DV, MDV, AMT, SEX, RACE. | Variable Metadata Check |
| [ADPPK-000173](#adppk-000173) | STUDYID must be identical to the ADSL variable. | Record Data |
| [ADPPK-000174](#adppk-000174) | USUBJID must be identical to the ADSL variable. | Record Data |
| [ADPPK-000175](#adppk-000175) | SUBJID must be identical to the ADSL variable. | Record Data |
| [ADPPK-000176](#adppk-000176) | SITEID must be identical to the ADSL variable. | Record Data |
| [ADPPK-000177](#adppk-000177) | SEX must be identical to ADSL.SEX. | Record Data |
| [ADPPK-000178](#adppk-000178) | RACE must be identical to ADSL.RACE (may categorize differently if analysis demands). | Record Data |
| [ADPPK-000179](#adppk-000179) | If both a character variable and its numeric counterpart are present and both populated on a row, there must be a one... | Variable Metadata Check |
| [ADPPK-000180](#adppk-000180) | EVID=1 for dosing events, EVID=0 for observations. | Record Data |
| [ADPPK-000181](#adppk-000181) | MDV=1 when DV is missing for observation or when EVID=1. Controlled terms: 0, 1. | Record Data |
| [ADPPK-000182](#adppk-000182) | DV is a copy of AVAL. | Record Data |
| [ADPPK-000183](#adppk-000183) | AMT is only populated on dosing records. | Record Data |
| [ADPPK-000184](#adppk-000184) | BLQFL set to Y when the analysis value is below the limit of quantification. Controlled terms: N, Y. | Record Data |
| [ADPPK-000185](#adppk-000185) | ALQFL set to Y when the analysis value is above the limit of quantification. Controlled terms: N, Y. | Record Data |
| [ADPPK-000186](#adppk-000186) | PART is required when study has more than 1 part (e.g., part A dose escalation and part B dose evaluation). | Record Data |
| [ADPPK-000187](#adppk-000187) | COHORTC must have a one-to-one mapping to COHORT. | Record Data |
| [ADPPK-000188](#adppk-000188) | ROUTEN is derived from ROUTE as one-on-one unique match. | Record Data |
| [ADPPK-000189](#adppk-000189) | ACYCLEC is derived from ACYCLE as one-on-one unique match. | Record Data |
| [ADPPK-000190](#adppk-000190) | AFRLT is derived from (ADTM of the current event/record) - (ADTM of the first dosing event/record of the subject). Co... | Record Data |
| [ADPPK-000191](#adppk-000191) | APRLT is derived from (ADTM of the current event/record) - (ADTM of the previous dosing event/record of the subject). | Record Data |
| [ADPPK-000192](#adppk-000192) | If SDTM character variables are converted to numeric variables, they should be named as they are in the SDTM with an ... | Record Data |
| [ADPPK-000193](#adppk-000193) | Dataset should use ADPPK as a prefix (recommended, not required). Other relevant information can be used in the datas... | Record Data |
| [ADPPK-000194](#adppk-000194) | ADPPK is of Class BASIC DATA STRUCTURE, SubClass POPULATION PHARMACOKINETIC ANALYSIS. | Record Data |
| [ADPPK-000195](#adppk-000195) | Dataset keys are USUBJID, AFRLT (Actual Rel Time from First Dose), DVID, EVID (Event ID). | Record Data |
| [ADPPK-000196](#adppk-000196) | Covariates follow suffix conventions: <COV> for time-varying, <COV>BL for baseline, <COV>N for numeric version of cat... | Record Data |
| [ADPPK-000197](#adppk-000197) | RATE is calculated as AMT/DOSEDUR. | Record Data |
| [ADPPK-000198](#adppk-000198) | Variable units should be specified in the variable label. | Record Data |
| [ADPPK-000199](#adppk-000199) | DOSEDUR and RATE labels should contain the unit of time or unit. | Record Data |
| [ADPPK-000200](#adppk-000200) | Missing values may be specifically coded depending on the tool (e.g., -99), which would be described as a nonvalid va... | Record Data |
| [ADPPK-000201](#adppk-000201) | Handling of missing values must be clearly documented in the dataset specifications, SAP, or PAP. | Record Data |
| [ADPPK-000202](#adppk-000202) | Imputed date/time records, imputed clock-time records, and imputed amount records should be flagged. | Record Data |
| [ADPPK-000203](#adppk-000203) | When identifying the source dataset for a variable, the immediate predecessor variable is used per ADaM v2.1. | Record Data |
| [ADPPK-000204](#adppk-000204) | All data sources used are to be indicated and provided as supporting information. | Record Data |
| [ADPPK-000205](#adppk-000205) | ADT and related variables represent start date and time for dosing or interval sample collection; AENDT and related v... | Variable Metadata Check |
| [ADPPK-000206](#adppk-000206) | If analysis needs require a derived age that does not match ADSL.AGE, then AAGE (Analysis Age) must be added. | Record Data |

## Required Variable Rules

| Core ID | Variable |
|---------|----------|
| ADPPK-000007 | STUDYID |
| ADPPK-000018 | USUBJID |
| ADPPK-000021 | USUBJIDN |
| ADPPK-000034 | AFRLT |
| ADPPK-000055 | EVID |
| ADPPK-000064 | DV |
| ADPPK-000073 | MDV |
| ADPPK-000088 | AMT |
| ADPPK-000137 | SEX |
| ADPPK-000142 | RACE |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| ADPPK-000001 | PROJID | Project Identifier |
| ADPPK-000003 | PROJIDN | Project Identifier (N) |
| ADPPK-000005 | STUDYID | Study Identifier |
| ADPPK-000008 | STUDYIDN | Study Identifier (N) |
| ADPPK-000010 | PART | Part of the Study |
| ADPPK-000012 | SUBJTYP | Subject Type |
| ADPPK-000014 | SUBJTYPC | Subject Type (C) |
| ADPPK-000016 | USUBJID | Unique Subject Identifier |
| ADPPK-000019 | USUBJIDN | Unique Subject Identifier (N) |
| ADPPK-000022 | SUBJID | Subject Identifier for the Study |
| ADPPK-000024 | SUBJIDN | Subject Identifier for the Study (N) |
| ADPPK-000026 | SITEID | Study Site Identifier |
| ADPPK-000028 | SITEIDN | Study Site Identifier (N) |
| ADPPK-000030 | RECSEQ | Record Sequence |
| ADPPK-000032 | AFRLT | Actual Rel Time from First Dose |
| ADPPK-000035 | RLTU | Relative Time Unit |
| ADPPK-000037 | APRLT | Actual Rel Time from Previous Dose |
| ADPPK-000039 | NFRLT | Nominal Rel Time from First Dose |
| ADPPK-000041 | NPRLT | Nominal Rel Time from Previous Dose |
| ADPPK-000043 | OCC | Occasion |
| ADPPK-000045 | EXCLF | Record Exclusion |
| ADPPK-000047 | EXCLFCOM | Comment for the Record Exclusion |
| ADPPK-000049 | FLGREAS | Identification of Data Issue Reason |
| ADPPK-000051 | FLGREASC | Identification of Data Issue Reason (C) |
| ADPPK-000053 | EVID | Event ID |
| ADPPK-000056 | DVID | Dependent Variable Name |
| ADPPK-000058 | DVIDN | Dependent Variable Name (N) |
| ADPPK-000060 | CMT | Compartment |
| ADPPK-000062 | DV | Dependent Variable Result |
| ADPPK-000065 | AVAL | Analysis Value |
| ADPPK-000067 | AVALU | Dependent Variable Unit |
| ADPPK-000069 | USTRESC | Result or Finding in Standard Format |
| ADPPK-000071 | MDV | Missing Dependent Variable Result |
| ADPPK-000074 | AULOQ | Analysis Upper Limit of Quantitation |
| ADPPK-000076 | ALLOQ | Analysis Lower Limit of Quantitation |
| ADPPK-000078 | BLQFL | Below Lower Limit of Quant Flag |
| ADPPK-000080 | BLQFN | Below Lower Limit of Quant Flag (N) |
| ADPPK-000082 | ALQFL | Above the Upper Limit of Quant Flag |
| ADPPK-000084 | ALQFN | Above the Upper Limit of Quant Flag (N) |
| ADPPK-000086 | AMT | Actual Amount of Dose Received (unit) |
| ADPPK-000089 | DOSEA | Actual Treatment Dose (unit) |
| ADPPK-000091 | DOSETDD | Total Daily Amt of Dose Received (unit) |
| ADPPK-000093 | DOSEDUR | Duration Of Dose Administration (unit) |
| ADPPK-000095 | RATE | Infusion Rate (unit) |
| ADPPK-000097 | II | Dosing Interval (unit) |
| ADPPK-000099 | ADDL | Number Of Additional Doses |
| ADPPK-000101 | SS | Steady State |
| ADPPK-000103 | FORM | Drug Formulation |
| ADPPK-000105 | FORMN | Drug Formulation (N) |
| ADPPK-000107 | ROUTE | Route of Administration |
| ADPPK-000109 | ROUTEN | Route of Administration (N) |
| ADPPK-000111 | ACYCLE | Analysis Cycle |
| ADPPK-000113 | ACYCLEC | Analysis Cycle (C) |
| ADPPK-000115 | COHORT | Cohort Subject Enrolled Into |
| ADPPK-000117 | COHORTC | Cohort Subject Enrolled into (C) |
| ADPPK-000119 | UDTC | Date and Time of the Event |
| ADPPK-000121 | WT | Body Weight (unit) |
| ADPPK-000123 | WTBL | Baseline Body Weight (unit) |
| ADPPK-000125 | HTBL | Baseline Body Height (unit) |
| ADPPK-000127 | BMIBL | Baseline Body Mass Index (unit) |
| ADPPK-000129 | BSABL | Body Surface Area at Baseline (unit) |
| ADPPK-000131 | AGE | Age |
| ADPPK-000133 | AGETPT | Age at Analysis Timepoint (unit) |
| ADPPK-000135 | SEX | Sex |
| ADPPK-000138 | SEXN | Sex (N) |
| ADPPK-000140 | RACE | Race |
| ADPPK-000143 | RACEN | Race (N) |
| ADPPK-000145 | ARACE | Analysis Race |
| ADPPK-000147 | ARACEN | Analysis Race (N) |
| ADPPK-000149 | AETHNIC | Analysis Ethnicity |
| ADPPK-000151 | AETHNICN | Analysis Ethnicity (N) |
| ADPPK-000153 | COUNTRY | Country |
| ADPPK-000155 | COUNTRYL | Country Full Name |
| ADPPK-000157 | COUNTRYN | Country (N) |
| ADPPK-000159 | CREATBL | Baseline Creatinine Serum (unit) |
| ADPPK-000161 | CRCLBL | Baseline Creatinine Clearance (unit) |
| ADPPK-000163 | EGFRBL | Baseline eGFR (unit) |
| ADPPK-000165 | TBILBL | Baseline Total Bilirubin (unit) |
| ADPPK-000167 | ASTBL | Baseline Aspartate transaminase (unit) |
| ADPPK-000169 | ALTBL | Baseline Alanine transaminase (unit) |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| ADPPK-000002 | PROJID | Char |
| ADPPK-000004 | PROJIDN | Num |
| ADPPK-000006 | STUDYID | Char |
| ADPPK-000009 | STUDYIDN | Num |
| ADPPK-000011 | PART | Num |
| ADPPK-000013 | SUBJTYP | Num |
| ADPPK-000015 | SUBJTYPC | Char |
| ADPPK-000017 | USUBJID | Char |
| ADPPK-000020 | USUBJIDN | Num |
| ADPPK-000023 | SUBJID | Char |
| ADPPK-000025 | SUBJIDN | Num |
| ADPPK-000027 | SITEID | Char |
| ADPPK-000029 | SITEIDN | Num |
| ADPPK-000031 | RECSEQ | Num |
| ADPPK-000033 | AFRLT | Num |
| ADPPK-000036 | RLTU | Char |
| ADPPK-000038 | APRLT | Num |
| ADPPK-000040 | NFRLT | Num |
| ADPPK-000042 | NPRLT | Num |
| ADPPK-000044 | OCC | Num |
| ADPPK-000046 | EXCLF | Num |
| ADPPK-000048 | EXCLFCOM | Char |
| ADPPK-000050 | FLGREAS | Num |
| ADPPK-000052 | FLGREASC | Char |
| ADPPK-000054 | EVID | Num |
| ADPPK-000057 | DVID | Char |
| ADPPK-000059 | DVIDN | Num |
| ADPPK-000061 | CMT | Num |
| ADPPK-000063 | DV | Num |
| ADPPK-000066 | AVAL | Num |
| ADPPK-000068 | AVALU | Char |
| ADPPK-000070 | USTRESC | Char |
| ADPPK-000072 | MDV | Num |
| ADPPK-000075 | AULOQ | Num |
| ADPPK-000077 | ALLOQ | Num |
| ADPPK-000079 | BLQFL | Char |
| ADPPK-000081 | BLQFN | Num |
| ADPPK-000083 | ALQFL | Char |
| ADPPK-000085 | ALQFN | Num |
| ADPPK-000087 | AMT | Num |
| ADPPK-000090 | DOSEA | Num |
| ADPPK-000092 | DOSETDD | Num |
| ADPPK-000094 | DOSEDUR | Num |
| ADPPK-000096 | RATE | Num |
| ADPPK-000098 | II | Num |
| ADPPK-000100 | ADDL | Num |
| ADPPK-000102 | SS | Num |
| ADPPK-000104 | FORM | Char |
| ADPPK-000106 | FORMN | Num |
| ADPPK-000108 | ROUTE | Char |
| ADPPK-000110 | ROUTEN | Num |
| ADPPK-000112 | ACYCLE | Num |
| ADPPK-000114 | ACYCLEC | Char |
| ADPPK-000116 | COHORT | Num |
| ADPPK-000118 | COHORTC | Char |
| ADPPK-000120 | UDTC | Char |
| ADPPK-000122 | WT | Num |
| ADPPK-000124 | WTBL | Num |
| ADPPK-000126 | HTBL | Num |
| ADPPK-000128 | BMIBL | Num |
| ADPPK-000130 | BSABL | Num |
| ADPPK-000132 | AGE | Num |
| ADPPK-000134 | AGETPT | Num |
| ADPPK-000136 | SEX | Char |
| ADPPK-000139 | SEXN | Num |
| ADPPK-000141 | RACE | Char |
| ADPPK-000144 | RACEN | Num |
| ADPPK-000146 | ARACE | Char |
| ADPPK-000148 | ARACEN | Num |
| ADPPK-000150 | AETHNIC | Char |
| ADPPK-000152 | AETHNICN | Num |
| ADPPK-000154 | COUNTRY | Char |
| ADPPK-000156 | COUNTRYL | Char |
| ADPPK-000158 | COUNTRYN | Num |
| ADPPK-000160 | CREATBL | Num |
| ADPPK-000162 | CRCLBL | Num |
| ADPPK-000164 | EGFRBL | Num |
| ADPPK-000166 | TBILBL | Num |
| ADPPK-000168 | ASTBL | Num |
| ADPPK-000170 | ALTBL | Num |

---

## Rule Details (Structural Rules)

### ADPPK-000171

**Description:** ADPPK has one record per subject per parameter (analyte) per analysis timepoint per event (dosing or observation).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** ADPPK has one record per subject per parameter (analyte) per analysis timepoint per event (dosing or observation).

---

### ADPPK-000172

**Description:** Required variables (Core=Req): STUDYID, USUBJID, USUBJIDN, AFRLT, EVID, DV, MDV, AMT, SEX, RACE.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Required variables (Core=Req): STUDYID, USUBJID, USUBJIDN, AFRLT, EVID, DV, MDV, AMT, SEX, RACE.

---

### ADPPK-000173

**Description:** STUDYID must be identical to the ADSL variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** STUDYID must be identical to the ADSL variable.

---

### ADPPK-000174

**Description:** USUBJID must be identical to the ADSL variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** USUBJID must be identical to the ADSL variable.

---

### ADPPK-000175

**Description:** SUBJID must be identical to the ADSL variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** SUBJID must be identical to the ADSL variable.

---

### ADPPK-000176

**Description:** SITEID must be identical to the ADSL variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** SITEID must be identical to the ADSL variable.

---

### ADPPK-000177

**Description:** SEX must be identical to ADSL.SEX.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** SEX must be identical to ADSL.SEX.

---

### ADPPK-000178

**Description:** RACE must be identical to ADSL.RACE (may categorize differently if analysis demands).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** RACE must be identical to ADSL.RACE (may categorize differently if analysis demands).

---

### ADPPK-000179

**Description:** If both a character variable and its numeric counterpart are present and both populated on a row, there must be a one-to-one mapping between the 2 variables on all rows within the scope on which both 

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** If both a character variable and its numeric counterpart are present and both populated on a row, there must be a one-to-one mapping between the 2 variables on all rows within the scope on which both 

---

### ADPPK-000180

**Description:** EVID=1 for dosing events, EVID=0 for observations.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** EVID=1 for dosing events, EVID=0 for observations.

---

### ADPPK-000181

**Description:** MDV=1 when DV is missing for observation or when EVID=1. Controlled terms: 0, 1.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** MDV=1 when DV is missing for observation or when EVID=1. Controlled terms: 0, 1.

---

### ADPPK-000182

**Description:** DV is a copy of AVAL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** DV is a copy of AVAL.

---

### ADPPK-000183

**Description:** AMT is only populated on dosing records.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** AMT is only populated on dosing records.

---

### ADPPK-000184

**Description:** BLQFL set to Y when the analysis value is below the limit of quantification. Controlled terms: N, Y.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** BLQFL set to Y when the analysis value is below the limit of quantification. Controlled terms: N, Y.

---

### ADPPK-000185

**Description:** ALQFL set to Y when the analysis value is above the limit of quantification. Controlled terms: N, Y.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** ALQFL set to Y when the analysis value is above the limit of quantification. Controlled terms: N, Y.

---

### ADPPK-000186

**Description:** PART is required when study has more than 1 part (e.g., part A dose escalation and part B dose evaluation).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** PART is required when study has more than 1 part (e.g., part A dose escalation and part B dose evaluation).

---

### ADPPK-000187

**Description:** COHORTC must have a one-to-one mapping to COHORT.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** COHORTC must have a one-to-one mapping to COHORT.

---

### ADPPK-000188

**Description:** ROUTEN is derived from ROUTE as one-on-one unique match.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** ROUTEN is derived from ROUTE as one-on-one unique match.

---

### ADPPK-000189

**Description:** ACYCLEC is derived from ACYCLE as one-on-one unique match.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** ACYCLEC is derived from ACYCLE as one-on-one unique match.

---

### ADPPK-000190

**Description:** AFRLT is derived from (ADTM of the current event/record) - (ADTM of the first dosing event/record of the subject). Could be negative.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** AFRLT is derived from (ADTM of the current event/record) - (ADTM of the first dosing event/record of the subject). Could be negative.

---

### ADPPK-000191

**Description:** APRLT is derived from (ADTM of the current event/record) - (ADTM of the previous dosing event/record of the subject).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** APRLT is derived from (ADTM of the current event/record) - (ADTM of the previous dosing event/record of the subject).

---

### ADPPK-000192

**Description:** If SDTM character variables are converted to numeric variables, they should be named as they are in the SDTM with an 'N' suffix added.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** If SDTM character variables are converted to numeric variables, they should be named as they are in the SDTM with an 'N' suffix added.

---

### ADPPK-000193

**Description:** Dataset should use ADPPK as a prefix (recommended, not required). Other relevant information can be used in the dataset name (e.g., ADPPKxyz).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Dataset should use ADPPK as a prefix (recommended, not required). Other relevant information can be used in the dataset name (e.g., ADPPKxyz).

---

### ADPPK-000194

**Description:** ADPPK is of Class BASIC DATA STRUCTURE, SubClass POPULATION PHARMACOKINETIC ANALYSIS.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** ADPPK is of Class BASIC DATA STRUCTURE, SubClass POPULATION PHARMACOKINETIC ANALYSIS.

---

### ADPPK-000195

**Description:** Dataset keys are USUBJID, AFRLT (Actual Rel Time from First Dose), DVID, EVID (Event ID).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Dataset keys are USUBJID, AFRLT (Actual Rel Time from First Dose), DVID, EVID (Event ID).

---

### ADPPK-000196

**Description:** Covariates follow suffix conventions: <COV> for time-varying, <COV>BL for baseline, <COV>N for numeric version of categorical covariate (one-to-one with <COV>), <COV>I for imputed values, <COV>GRy for

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Covariates follow suffix conventions: <COV> for time-varying, <COV>BL for baseline, <COV>N for numeric version of categorical covariate (one-to-one with <COV>), <COV>I for imputed values, <COV>GRy for

---

### ADPPK-000197

**Description:** RATE is calculated as AMT/DOSEDUR.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** RATE is calculated as AMT/DOSEDUR.

---

### ADPPK-000198

**Description:** Variable units should be specified in the variable label.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Variable units should be specified in the variable label.

---

### ADPPK-000199

**Description:** DOSEDUR and RATE labels should contain the unit of time or unit.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** DOSEDUR and RATE labels should contain the unit of time or unit.

---

### ADPPK-000200

**Description:** Missing values may be specifically coded depending on the tool (e.g., -99), which would be described as a nonvalid value in the metadata.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Missing values may be specifically coded depending on the tool (e.g., -99), which would be described as a nonvalid value in the metadata.

---

### ADPPK-000201

**Description:** Handling of missing values must be clearly documented in the dataset specifications, SAP, or PAP.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Handling of missing values must be clearly documented in the dataset specifications, SAP, or PAP.

---

### ADPPK-000202

**Description:** Imputed date/time records, imputed clock-time records, and imputed amount records should be flagged.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** Imputed date/time records, imputed clock-time records, and imputed amount records should be flagged.

---

### ADPPK-000203

**Description:** When identifying the source dataset for a variable, the immediate predecessor variable is used per ADaM v2.1.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** When identifying the source dataset for a variable, the immediate predecessor variable is used per ADaM v2.1.

---

### ADPPK-000204

**Description:** All data sources used are to be indicated and provided as supporting information.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** All data sources used are to be indicated and provided as supporting information.

---

### ADPPK-000205

**Description:** ADT and related variables represent start date and time for dosing or interval sample collection; AENDT and related variables represent end date and time.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** ADT and related variables represent start date and time for dosing or interval sample collection; AENDT and related variables represent end date and time.

---

### ADPPK-000206

**Description:** If analysis needs require a derived age that does not match ADSL.AGE, then AAGE (Analysis Age) must be added.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADPPK |

**Reference:** ADaM PopPK IG v1.0, Section 3

**Cited Guidance:** If analysis needs require a derived age that does not match ADSL.AGE, then AAGE (Analysis Age) must be added.

---
