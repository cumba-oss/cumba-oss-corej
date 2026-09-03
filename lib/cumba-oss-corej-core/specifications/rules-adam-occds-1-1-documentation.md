# ADaM OCCDS v1.1 Conformance Rules Documentation

Auto-generated documentation from `rules-adam-occds-1-1.json`.

**Total Rules:** 129  
**Standard:** ADaM OCCDS IG v1.1  
**Model:** ADaM v2.1  

## Summary Statistics

| Rule Type | Count |
|-----------|-------|
| Record Data | 28 |
| Variable Metadata Check | 101 |

### By Category

| Category | Count |
|----------|-------|
| Structural / Conformance | 30 |
| Required Variable Checks | 3 |
| Variable Label Conformance | 48 |
| Variable Type Conformance | 48 |

---

## Structural and Conformance Rules

| Core ID | Description | Rule Type |
|---------|-------------|-----------|
| [OCCDS-000100](#occds-000100) | OCCDS datasets are assigned a dataset metadata class value of OCCURRENCE DATA STRUCTURE | Record Data |
| [OCCDS-000101](#occds-000101) | SubClass ADVERSE EVENT must have a Class of OCCURRENCE DATA STRUCTURE and meet all its principles | Record Data |
| [OCCDS-000102](#occds-000102) | The SDTM input dataset for ADVERSE EVENT SubClass is always AE, with additional info from SUPPAE, FA, and ADSL | Record Data |
| [OCCDS-000103](#occds-000103) | SubClass ADVERSE EVENT should include all SDTM AE variables with SDTM core value of Required or Expected | Variable Metadata Check |
| [OCCDS-000104](#occds-000104) | When FA is used as input to ADVERSE EVENT SubClass, a unique identifier variable (e.g., FASEQ or FASPID) is required ... | Variable Metadata Check |
| [OCCDS-000105](#occds-000105) | Variables copied from SDTM must have the same variable name, label, values, and meaning as in SDTM (harmonization pri... | Record Data |
| [OCCDS-000106](#occds-000106) | For SubClass ADVERSE EVENT, all levels of terms for the primary MedDRA path (SOC, HLGT, HLT, LLT, PT) are required | Record Data |
| [OCCDS-000107](#occds-000107) | For any public versioned dictionary, metadata for each coding variable should include both name and version | Record Data |
| [OCCDS-000108](#occds-000108) | --SEQ provides row identifier when rows are from a single SDTM domain; SRCDOM+SRCSEQ used when from multiple domains | Record Data |
| [OCCDS-000109](#occds-000109) | SRCDOM and SRCSEQ are not to be used in conjunction with --SEQ | Record Data |
| [OCCDS-000110](#occds-000110) | For SubClass ADVERSE EVENT, AESEQ is used rather than SRCDOM and SRCSEQ | Record Data |
| [OCCDS-000111](#occds-000111) | APERIODC must have a one-to-one map to APERIOD | Record Data |
| [OCCDS-000112](#occds-000112) | APERIOD value (if populated) must be one of the xx values found in the ADSL TRTxxP variables | Record Data |
| [OCCDS-000113](#occds-000113) | ADURU is conditional on whether ADURN is included | Record Data |
| [OCCDS-000114](#occds-000114) | DOSEU is conditional on whether DOSEON and/or DOSCUMA are included | Record Data |
| [OCCDS-000115](#occds-000115) | If TREMxxFL is included, TRTEMFL is defined as the overall treatment-emergent flag | Record Data |
| [OCCDS-000116](#occds-000116) | If ONTRxxFL is included, ONTRTFL is defined as the overall on-treatment flag | Record Data |
| [OCCDS-000117](#occds-000117) | If TRTEMwFL is included, TRTEMFL is defined as the overall treatment-emergent flag | Record Data |
| [OCCDS-000118](#occds-000118) | If ONTRTwFL is included, ONTRTFL is defined as the overall on-treatment flag | Record Data |
| [OCCDS-000119](#occds-000119) | SDTM does not allow variable AEOCCUR, so --OCCUR is not available for adverse events | Record Data |
| [OCCDS-000120](#occds-000120) | Occurrence flags are typically Y or null (not Y/N); codelist is Y | Record Data |
| [OCCDS-000121](#occds-000121) | Numeric severity/toxicity/causality: low intensity/toxicity/relation should correspond to low numeric value | Record Data |
| [OCCDS-000122](#occds-000122) | Either --SEV or --TOXGR should be included in SDTM (for AE SubClass) | Record Data |
| [OCCDS-000123](#occds-000123) | Structure is usually 1 record per each record in the corresponding SDTM domain | Record Data |
| [OCCDS-000124](#occds-000124) | There is no AVAL or AVALC in OCCDS; occurrences are counted, not measured | Record Data |
| [OCCDS-000125](#occds-000125) | Denominators usually need to be obtained from ADSL, not from the occurrence analysis dataset | Record Data |
| [OCCDS-000126](#occds-000126) | When using prefix U (unmodified), no modifications to SDTM variable content are made; prefix A (analysis) indicates p... | Record Data |
| [OCCDS-000127](#occds-000127) | SRCVAR from BDS is not included in OCCDS since AVAL and AVALC are not applicable | Record Data |
| [OCCDS-000128](#occds-000128) | SMQzzSC codelist values are BROAD and NARROW; all narrow terms are also within broad scope | Record Data |
| [OCCDS-000129](#occds-000129) | ANLzzFL codelist may be Y, N, or null as described in ADaMIG v1.2 | Record Data |

## Required Variable Rules

| Core ID | Variable |
|---------|----------|
| OCCDS-000003 | STUDYID |
| OCCDS-000006 | USUBJID |
| OCCDS-000019 | CMTRT |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| OCCDS-000001 | STUDYID | Study Identifier |
| OCCDS-000004 | USUBJID | Unique Subject Identifier |
| OCCDS-000007 | SUBJID | Subject Identifier for the Study |
| OCCDS-000009 | SITEID | Study Site Identifier |
| OCCDS-000011 | SRCDOM | Source Data |
| OCCDS-000013 | SRCSEQ | Source Sequence Number |
| OCCDS-000015 | ASEQ | Analysis Sequence Number |
| OCCDS-000017 | CMTRT | Reported Name of Drug, Med, or Therapy |
| OCCDS-000020 | CMDECOD | Standardized Medication Name |
| OCCDS-000022 | CMCLAS | Medication Class |
| OCCDS-000024 | CMCLASCD | Medication Class Code |
| OCCDS-000026 | ASTDT | Analysis Start Date |
| OCCDS-000028 | ASTTM | Analysis Start Time |
| OCCDS-000030 | ASTDTM | Analysis Start Datetime |
| OCCDS-000032 | ASTDTF | Analysis Start Date Imputation Flag |
| OCCDS-000034 | ASTTMF | Analysis Start Time Imputation Flag |
| OCCDS-000036 | AENDT | Analysis End Date |
| OCCDS-000038 | AENTM | Analysis End Time |
| OCCDS-000040 | AENDTM | Analysis End Datetime |
| OCCDS-000042 | AENDTF | Analysis End Date Imputation Flag |
| OCCDS-000044 | AENTMF | Analysis End Time Imputation Flag |
| OCCDS-000046 | ASTDY | Analysis Start Relative Day |
| OCCDS-000048 | AENDY | Analysis End Relative Day |
| OCCDS-000050 | ADURN | Analysis Duration (N) |
| OCCDS-000052 | ADURU | Analysis Duration Units |
| OCCDS-000054 | APERIOD | Period |
| OCCDS-000056 | APERIODC | Period (C) |
| OCCDS-000058 | APHASE | Phase |
| OCCDS-000060 | TRTEMFL | Treatment Emergent Analysis Flag |
| OCCDS-000062 | AETRTEM | Treatment Emergent Flag |
| OCCDS-000064 | ONTRTFL | On Treatment Record Flag |
| OCCDS-000066 | PREFL | Pre-treatment Flag |
| OCCDS-000068 | FUPFL | Follow-up Flag |
| OCCDS-000070 | AOCCFL | 1st Occurrence within Subject Flag |
| OCCDS-000072 | AOCCPFL | 1st Occurrence of Preferred Term Flag |
| OCCDS-000074 | AOCCIFL | 1st Max Sev./Int. Occurrence Flag |
| OCCDS-000076 | AOCCPIFL | 1st Max Sev./Int. Occur Within PT Flag |
| OCCDS-000078 | AOCCSFL | 1st Occurrence of SOC Flag |
| OCCDS-000080 | AOCCSIFL | 1st Max Sev./Int. Occur Within SOC Flag |
| OCCDS-000082 | DOSEON | Treatment Dose at Record Start |
| OCCDS-000084 | DOSCUMA | Cumulative Actual Treatment Dose |
| OCCDS-000086 | DOSEU | Treatment Dose Units |
| OCCDS-000088 | ASEV | Analysis Severity/Intensity |
| OCCDS-000090 | ASEVN | Analysis Severity/Intensity (N) |
| OCCDS-000092 | AREL | Analysis Causality |
| OCCDS-000094 | ARELN | Analysis Causality (N) |
| OCCDS-000096 | ATOXGR | Analysis Toxicity Grade |
| OCCDS-000098 | ATOXGRN | Analysis Toxicity Grade (N) |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| OCCDS-000002 | STUDYID | Char |
| OCCDS-000005 | USUBJID | Char |
| OCCDS-000008 | SUBJID | Char |
| OCCDS-000010 | SITEID | Char |
| OCCDS-000012 | SRCDOM | Char |
| OCCDS-000014 | SRCSEQ | Num |
| OCCDS-000016 | ASEQ | Num |
| OCCDS-000018 | CMTRT | Char |
| OCCDS-000021 | CMDECOD | Char |
| OCCDS-000023 | CMCLAS | Char |
| OCCDS-000025 | CMCLASCD | Char |
| OCCDS-000027 | ASTDT | Num |
| OCCDS-000029 | ASTTM | Num |
| OCCDS-000031 | ASTDTM | Num |
| OCCDS-000033 | ASTDTF | Char |
| OCCDS-000035 | ASTTMF | Char |
| OCCDS-000037 | AENDT | Num |
| OCCDS-000039 | AENTM | Num |
| OCCDS-000041 | AENDTM | Num |
| OCCDS-000043 | AENDTF | Char |
| OCCDS-000045 | AENTMF | Char |
| OCCDS-000047 | ASTDY | Num |
| OCCDS-000049 | AENDY | Num |
| OCCDS-000051 | ADURN | Num |
| OCCDS-000053 | ADURU | Char |
| OCCDS-000055 | APERIOD | Num |
| OCCDS-000057 | APERIODC | Char |
| OCCDS-000059 | APHASE | Char |
| OCCDS-000061 | TRTEMFL | Char |
| OCCDS-000063 | AETRTEM | Char |
| OCCDS-000065 | ONTRTFL | Char |
| OCCDS-000067 | PREFL | Char |
| OCCDS-000069 | FUPFL | Char |
| OCCDS-000071 | AOCCFL | Char |
| OCCDS-000073 | AOCCPFL | Char |
| OCCDS-000075 | AOCCIFL | Char |
| OCCDS-000077 | AOCCPIFL | Char |
| OCCDS-000079 | AOCCSFL | Char |
| OCCDS-000081 | AOCCSIFL | Char |
| OCCDS-000083 | DOSEON | Num |
| OCCDS-000085 | DOSCUMA | Num |
| OCCDS-000087 | DOSEU | Char |
| OCCDS-000089 | ASEV | Char |
| OCCDS-000091 | ASEVN | Num |
| OCCDS-000093 | AREL | Char |
| OCCDS-000095 | ARELN | Num |
| OCCDS-000097 | ATOXGR | Char |
| OCCDS-000099 | ATOXGRN | Num |

---

## Rule Details (Structural Rules)

### OCCDS-000100

**Description:** OCCDS datasets are assigned a dataset metadata class value of OCCURRENCE DATA STRUCTURE

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 1.1.2

**Cited Guidance:** OCCDS datasets are assigned a dataset metadata class value of OCCURRENCE DATA STRUCTURE

---

### OCCDS-000101

**Description:** SubClass ADVERSE EVENT must have a Class of OCCURRENCE DATA STRUCTURE and meet all its principles

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.1.2

**Cited Guidance:** SubClass ADVERSE EVENT must have a Class of OCCURRENCE DATA STRUCTURE and meet all its principles

---

### OCCDS-000102

**Description:** The SDTM input dataset for ADVERSE EVENT SubClass is always AE, with additional info from SUPPAE, FA, and ADSL

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.1.2

**Cited Guidance:** The SDTM input dataset for ADVERSE EVENT SubClass is always AE, with additional info from SUPPAE, FA, and ADSL

---

### OCCDS-000103

**Description:** SubClass ADVERSE EVENT should include all SDTM AE variables with SDTM core value of Required or Expected

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2

**Cited Guidance:** SubClass ADVERSE EVENT should include all SDTM AE variables with SDTM core value of Required or Expected

---

### OCCDS-000104

**Description:** When FA is used as input to ADVERSE EVENT SubClass, a unique identifier variable (e.g., FASEQ or FASPID) is required for traceability

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.1.2

**Cited Guidance:** When FA is used as input to ADVERSE EVENT SubClass, a unique identifier variable (e.g., FASEQ or FASPID) is required for traceability

---

### OCCDS-000105

**Description:** Variables copied from SDTM must have the same variable name, label, values, and meaning as in SDTM (harmonization principle)

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2

**Cited Guidance:** Variables copied from SDTM must have the same variable name, label, values, and meaning as in SDTM (harmonization principle)

---

### OCCDS-000106

**Description:** For SubClass ADVERSE EVENT, all levels of terms for the primary MedDRA path (SOC, HLGT, HLT, LLT, PT) are required

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.3

**Cited Guidance:** For SubClass ADVERSE EVENT, all levels of terms for the primary MedDRA path (SOC, HLGT, HLT, LLT, PT) are required

---

### OCCDS-000107

**Description:** For any public versioned dictionary, metadata for each coding variable should include both name and version

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.3

**Cited Guidance:** For any public versioned dictionary, metadata for each coding variable should include both name and version

---

### OCCDS-000108

**Description:** --SEQ provides row identifier when rows are from a single SDTM domain; SRCDOM+SRCSEQ used when from multiple domains

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.2

**Cited Guidance:** --SEQ provides row identifier when rows are from a single SDTM domain; SRCDOM+SRCSEQ used when from multiple domains

---

### OCCDS-000109

**Description:** SRCDOM and SRCSEQ are not to be used in conjunction with --SEQ

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.2

**Cited Guidance:** SRCDOM and SRCSEQ are not to be used in conjunction with --SEQ

---

### OCCDS-000110

**Description:** For SubClass ADVERSE EVENT, AESEQ is used rather than SRCDOM and SRCSEQ

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.2

**Cited Guidance:** For SubClass ADVERSE EVENT, AESEQ is used rather than SRCDOM and SRCSEQ

---

### OCCDS-000111

**Description:** APERIODC must have a one-to-one map to APERIOD

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.4

**Cited Guidance:** APERIODC must have a one-to-one map to APERIOD

---

### OCCDS-000112

**Description:** APERIOD value (if populated) must be one of the xx values found in the ADSL TRTxxP variables

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.4

**Cited Guidance:** APERIOD value (if populated) must be one of the xx values found in the ADSL TRTxxP variables

---

### OCCDS-000113

**Description:** ADURU is conditional on whether ADURN is included

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.4

**Cited Guidance:** ADURU is conditional on whether ADURN is included

---

### OCCDS-000114

**Description:** DOSEU is conditional on whether DOSEON and/or DOSCUMA are included

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.7

**Cited Guidance:** DOSEU is conditional on whether DOSEON and/or DOSCUMA are included

---

### OCCDS-000115

**Description:** If TREMxxFL is included, TRTEMFL is defined as the overall treatment-emergent flag

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.5

**Cited Guidance:** If TREMxxFL is included, TRTEMFL is defined as the overall treatment-emergent flag

---

### OCCDS-000116

**Description:** If ONTRxxFL is included, ONTRTFL is defined as the overall on-treatment flag

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.5

**Cited Guidance:** If ONTRxxFL is included, ONTRTFL is defined as the overall on-treatment flag

---

### OCCDS-000117

**Description:** If TRTEMwFL is included, TRTEMFL is defined as the overall treatment-emergent flag

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.5

**Cited Guidance:** If TRTEMwFL is included, TRTEMFL is defined as the overall treatment-emergent flag

---

### OCCDS-000118

**Description:** If ONTRTwFL is included, ONTRTFL is defined as the overall on-treatment flag

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.5

**Cited Guidance:** If ONTRTwFL is included, ONTRTFL is defined as the overall on-treatment flag

---

### OCCDS-000119

**Description:** SDTM does not allow variable AEOCCUR, so --OCCUR is not available for adverse events

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.5

**Cited Guidance:** SDTM does not allow variable AEOCCUR, so --OCCUR is not available for adverse events

---

### OCCDS-000120

**Description:** Occurrence flags are typically Y or null (not Y/N); codelist is Y

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.6

**Cited Guidance:** Occurrence flags are typically Y or null (not Y/N); codelist is Y

---

### OCCDS-000121

**Description:** Numeric severity/toxicity/causality: low intensity/toxicity/relation should correspond to low numeric value

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.8

**Cited Guidance:** Numeric severity/toxicity/causality: low intensity/toxicity/relation should correspond to low numeric value

---

### OCCDS-000122

**Description:** Either --SEV or --TOXGR should be included in SDTM (for AE SubClass)

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.8

**Cited Guidance:** Either --SEV or --TOXGR should be included in SDTM (for AE SubClass)

---

### OCCDS-000123

**Description:** Structure is usually 1 record per each record in the corresponding SDTM domain

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 1.1.2

**Cited Guidance:** Structure is usually 1 record per each record in the corresponding SDTM domain

---

### OCCDS-000124

**Description:** There is no AVAL or AVALC in OCCDS; occurrences are counted, not measured

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 1.1.2

**Cited Guidance:** There is no AVAL or AVALC in OCCDS; occurrences are counted, not measured

---

### OCCDS-000125

**Description:** Denominators usually need to be obtained from ADSL, not from the occurrence analysis dataset

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 2.1

**Cited Guidance:** Denominators usually need to be obtained from ADSL, not from the occurrence analysis dataset

---

### OCCDS-000126

**Description:** When using prefix U (unmodified), no modifications to SDTM variable content are made; prefix A (analysis) indicates possible modifications

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.11

**Cited Guidance:** When using prefix U (unmodified), no modifications to SDTM variable content are made; prefix A (analysis) indicates possible modifications

---

### OCCDS-000127

**Description:** SRCVAR from BDS is not included in OCCDS since AVAL and AVALC are not applicable

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.2

**Cited Guidance:** SRCVAR from BDS is not included in OCCDS since AVAL and AVALC are not applicable

---

### OCCDS-000128

**Description:** SMQzzSC codelist values are BROAD and NARROW; all narrow terms are also within broad scope

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.9

**Cited Guidance:** SMQzzSC codelist values are BROAD and NARROW; all narrow terms are also within broad scope

---

### OCCDS-000129

**Description:** ANLzzFL codelist may be Y, N, or null as described in ADaMIG v1.2

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | OCCURRENCE DATA STRUCTURE |

**Reference:** ADaM OCCDS IG v1.1, Section 3.2.5

**Cited Guidance:** ANLzzFL codelist may be Y, N, or null as described in ADaMIG v1.2

---
