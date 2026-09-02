# ADaMIG-MD v1.0 Conformance Rules Documentation

Auto-generated documentation from `rules-adamig-md-1-0.json`.

**Total Rules:** 67  
**Standard:** ADaMIG-MD v1.0  
**Model:** ADaM v2.1  

## Summary Statistics

| Rule Type | Count |
|-----------|-------|
| Record Data | 20 |
| Variable Metadata Check | 47 |

### By Category

| Category | Count |
|----------|-------|
| Structural / Conformance | 23 |
| Required Variable Checks | 6 |
| Variable Label Conformance | 19 |
| Variable Type Conformance | 19 |

---

## Structural and Conformance Rules

| Core ID | Description | Rule Type |
|---------|-------------|-----------|
| [ADAMMD-000045](#adammd-000045) | ADDL must contain 1 record per device per subject combination. | Record Data |
| [ADAMMD-000046](#adammd-000046) | In a study, there is only 1 dataset in the class 'DEVICE LEVEL ANALYSIS DATASET', and its name is ADDL. | Record Data |
| [ADAMMD-000047](#adammd-000047) | USUBJID is conditionally required: required when USUBJID is linked to one or more devices (SPDEVID) in SDTM DR dataset. | Record Data |
| [ADAMMD-000048](#adammd-000048) | DEVGRyN cannot be present unless DEVGRy is also present. There must be a one-to-one relationship between DEVGRyN and ... | Record Data |
| [ADAMMD-000049](#adammd-000049) | DEVTYGyN cannot be present unless DEVTYGy is also present. There must be a one-to-one relationship between DEVTYGyN a... | Record Data |
| [ADAMMD-000050](#adammd-000050) | MODELGyN cannot be present unless MODELGy is also present. There must be a one-to-one relationship between MODELGyN a... | Record Data |
| [ADAMMD-000051](#adammd-000051) | DEVAFL (and DEVAyFL) character flag variables whose names end in FL can have at most two possible non-missing values:... | Record Data |
| [ADAMMD-000052](#adammd-000052) | DEVIPDT is required when device is implanted. | Record Data |
| [ADAMMD-000053](#adammd-000053) | DEVXPDT is required when device is explanted or DEVIPDT is present. | Record Data |
| [ADAMMD-000054](#adammd-000054) | DEVONDT is required when the device is able to be turned on. | Record Data |
| [ADAMMD-000055](#adammd-000055) | DEVOFDT is required when the device is able to be turned off, or DEVONDT is present. | Record Data |
| [ADAMMD-000056](#adammd-000056) | DEVRPDT is required when a device could be repositioned and included in analysis. | Record Data |
| [ADAMMD-000057](#adammd-000057) | DEVMDDT is required when the device could be modified and included in analysis. | Record Data |
| [ADAMMD-000058](#adammd-000058) | AGEDSTU is required when age unit differs from ADSL.AGEU. | Record Data |
| [ADAMMD-000059](#adammd-000059) | ADSL is required for medical device studies only when subject information is collected and needs to be reported. If A... | Record Data |
| [ADAMMD-000060](#adammd-000060) | SPDEVID is a required identifier variable. USUBJID is conditionally required when subject-device relationship is defi... | Variable Metadata Check |
| [ADAMMD-000061](#adammd-000061) | DETERM and DEDECOD are required variables in MDOCCDS. This differs from OCCDS(ADVERSE EVENT) where AETERM, AEDECOD, a... | Variable Metadata Check |
| [ADAMMD-000062](#adammd-000062) | The following OCCDS qualifiers would not generally be used in DE/MDOCCDS: --BODSYS, --SER, --ACN, --REL, --RELNST, --... | Record Data |
| [ADAMMD-000063](#adammd-000063) | The SDTM input dataset for MDOCCDS is always Device Events (DE), with additional information from related datasets su... | Record Data |
| [ADAMMD-000064](#adammd-000064) | SPDEVID is a required key variable. USUBJID is conditionally required when subject-device relationship is defined in ... | Variable Metadata Check |
| [ADAMMD-000065](#adammd-000065) | ASEQ uniquely indexes records within a subject, or within a device, or within a subject and device; within an ADaM da... | Record Data |
| [ADAMMD-000066](#adammd-000066) | When the relationship between subjects and devices is not collected, ADDL must be included. | Record Data |
| [ADAMMD-000067](#adammd-000067) | If multiple records per device are needed in a device exposure analysis dataset, this information does not belong in ... | Record Data |

## Required Variable Rules

| Core ID | Variable |
|---------|----------|
| ADAMMD-000003 | STUDYID |
| ADAMMD-000006 | SPDEVID |
| ADAMMD-000011 | DEVSDT |
| ADAMMD-000014 | DEVEDT |
| ADAMMD-000035 | SPDEVID |
| ADAMMD-000040 | SPDEVID |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| ADAMMD-000001 | STUDYID | Study Identifier |
| ADAMMD-000004 | SPDEVID | Sponsor Device Identifier |
| ADAMMD-000007 | USUBJID | Unique Subject Identifier |
| ADAMMD-000009 | DEVSDT | Date of First Exposure to Device |
| ADAMMD-000012 | DEVEDT | Date of Last Exposure to Device |
| ADAMMD-000015 | DEVAFL | Device Active Flag |
| ADAMMD-000017 | DEVIPDT | Date Device Implanted |
| ADAMMD-000019 | DEVXPDT | Date Device Explanted |
| ADAMMD-000021 | DEVONDT | Date Device Turned On |
| ADAMMD-000023 | DEVOFDT | Date Device Turned Off |
| ADAMMD-000025 | DEVRPDT | Date Device Repositioned |
| ADAMMD-000027 | DEVMDDT | Date Device Modified |
| ADAMMD-000029 | AGEDST | Subject Age at First Exposure to Device |
| ADAMMD-000031 | AGEDSTU | Age at First Exposure to Device Unit |
| ADAMMD-000033 | SPDEVID | Sponsor Device Identifier |
| ADAMMD-000036 | USUBJID | Unique Subject Identifier |
| ADAMMD-000038 | SPDEVID | Sponsor Device Identifier |
| ADAMMD-000041 | USUBJID | Unique Subject Identifier |
| ADAMMD-000043 | ASEQ | Analysis Sequence Number |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| ADAMMD-000002 | STUDYID | Char |
| ADAMMD-000005 | SPDEVID | Char |
| ADAMMD-000008 | USUBJID | Char |
| ADAMMD-000010 | DEVSDT | Num |
| ADAMMD-000013 | DEVEDT | Num |
| ADAMMD-000016 | DEVAFL | Char |
| ADAMMD-000018 | DEVIPDT | Num |
| ADAMMD-000020 | DEVXPDT | Num |
| ADAMMD-000022 | DEVONDT | Num |
| ADAMMD-000024 | DEVOFDT | Num |
| ADAMMD-000026 | DEVRPDT | Num |
| ADAMMD-000028 | DEVMDDT | Num |
| ADAMMD-000030 | AGEDST | Num |
| ADAMMD-000032 | AGEDSTU | Char |
| ADAMMD-000034 | SPDEVID | Char |
| ADAMMD-000037 | USUBJID | Char |
| ADAMMD-000039 | SPDEVID | Char |
| ADAMMD-000042 | USUBJID | Char |
| ADAMMD-000044 | ASEQ | Num |

---

## Rule Details (Structural Rules)

### ADAMMD-000045

**Description:** ADDL must contain 1 record per device per subject combination.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 1.3.1 / 3.1.1

**Cited Guidance:** ADDL must contain 1 record per device per subject combination.

---

### ADAMMD-000046

**Description:** In a study, there is only 1 dataset in the class 'DEVICE LEVEL ANALYSIS DATASET', and its name is ADDL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 1.3.1

**Cited Guidance:** In a study, there is only 1 dataset in the class 'DEVICE LEVEL ANALYSIS DATASET', and its name is ADDL.

---

### ADAMMD-000047

**Description:** USUBJID is conditionally required: required when USUBJID is linked to one or more devices (SPDEVID) in SDTM DR dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.1

**Cited Guidance:** USUBJID is conditionally required: required when USUBJID is linked to one or more devices (SPDEVID) in SDTM DR dataset.

---

### ADAMMD-000048

**Description:** DEVGRyN cannot be present unless DEVGRy is also present. There must be a one-to-one relationship between DEVGRyN and DEVGRy within a study. When both are present, on a given record either both must be

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.2

**Cited Guidance:** DEVGRyN cannot be present unless DEVGRy is also present. There must be a one-to-one relationship between DEVGRyN and DEVGRy within a study. When both are present, on a given record either both must be

---

### ADAMMD-000049

**Description:** DEVTYGyN cannot be present unless DEVTYGy is also present. There must be a one-to-one relationship between DEVTYGyN and DEVTYGy within a study. When both are present, on a given record either both mus

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.2

**Cited Guidance:** DEVTYGyN cannot be present unless DEVTYGy is also present. There must be a one-to-one relationship between DEVTYGyN and DEVTYGy within a study. When both are present, on a given record either both mus

---

### ADAMMD-000050

**Description:** MODELGyN cannot be present unless MODELGy is also present. There must be a one-to-one relationship between MODELGyN and MODELGy within a study. When both are present, on a given record either both mus

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.2

**Cited Guidance:** MODELGyN cannot be present unless MODELGy is also present. There must be a one-to-one relationship between MODELGyN and MODELGy within a study. When both are present, on a given record either both mus

---

### ADAMMD-000051

**Description:** DEVAFL (and DEVAyFL) character flag variables whose names end in FL can have at most two possible non-missing values: Y or N. If it satisfies the analysis need, Y and missing can be used instead of Y 

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVAFL (and DEVAyFL) character flag variables whose names end in FL can have at most two possible non-missing values: Y or N. If it satisfies the analysis need, Y and missing can be used instead of Y 

---

### ADAMMD-000052

**Description:** DEVIPDT is required when device is implanted.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVIPDT is required when device is implanted.

---

### ADAMMD-000053

**Description:** DEVXPDT is required when device is explanted or DEVIPDT is present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVXPDT is required when device is explanted or DEVIPDT is present.

---

### ADAMMD-000054

**Description:** DEVONDT is required when the device is able to be turned on.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVONDT is required when the device is able to be turned on.

---

### ADAMMD-000055

**Description:** DEVOFDT is required when the device is able to be turned off, or DEVONDT is present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVOFDT is required when the device is able to be turned off, or DEVONDT is present.

---

### ADAMMD-000056

**Description:** DEVRPDT is required when a device could be repositioned and included in analysis.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVRPDT is required when a device could be repositioned and included in analysis.

---

### ADAMMD-000057

**Description:** DEVMDDT is required when the device could be modified and included in analysis.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3

**Cited Guidance:** DEVMDDT is required when the device could be modified and included in analysis.

---

### ADAMMD-000058

**Description:** AGEDSTU is required when age unit differs from ADSL.AGEU.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.4

**Cited Guidance:** AGEDSTU is required when age unit differs from ADSL.AGEU.

---

### ADAMMD-000059

**Description:** ADSL is required for medical device studies only when subject information is collected and needs to be reported. If ADSL is not included, an explanation should be included in the ADRG.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 1.3.2

**Cited Guidance:** ADSL is required for medical device studies only when subject information is collected and needs to be reported. If ADSL is not included, an explanation should be included in the ADRG.

---

### ADAMMD-000060

**Description:** SPDEVID is a required identifier variable. USUBJID is conditionally required when subject-device relationship is defined in ADDL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 1.3.3 / 3.2.3

**Cited Guidance:** SPDEVID is a required identifier variable. USUBJID is conditionally required when subject-device relationship is defined in ADDL.

---

### ADAMMD-000061

**Description:** DETERM and DEDECOD are required variables in MDOCCDS. This differs from OCCDS(ADVERSE EVENT) where AETERM, AEDECOD, and AEBODSYS are required.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.3

**Cited Guidance:** DETERM and DEDECOD are required variables in MDOCCDS. This differs from OCCDS(ADVERSE EVENT) where AETERM, AEDECOD, and AEBODSYS are required.

---

### ADAMMD-000062

**Description:** The following OCCDS qualifiers would not generally be used in DE/MDOCCDS: --BODSYS, --SER, --ACN, --REL, --RELNST, --PATT, --OUT, --SCAN, --SCONG, --SDISAB, --SDTH, --SHOSP, --SLIFE, --SOD, --SMIE, --

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.3

**Cited Guidance:** The following OCCDS qualifiers would not generally be used in DE/MDOCCDS: --BODSYS, --SER, --ACN, --REL, --RELNST, --PATT, --OUT, --SCAN, --SCONG, --SDISAB, --SDTH, --SHOSP, --SLIFE, --SOD, --SMIE, --

---

### ADAMMD-000063

**Description:** The SDTM input dataset for MDOCCDS is always Device Events (DE), with additional information from related datasets such as SUPPDE and Findings About Events or Interventions (FA).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.1.2

**Cited Guidance:** The SDTM input dataset for MDOCCDS is always Device Events (DE), with additional information from related datasets such as SUPPDE and Findings About Events or Interventions (FA).

---

### ADAMMD-000064

**Description:** SPDEVID is a required key variable. USUBJID is conditionally required when subject-device relationship is defined in ADDL. Structure is 1 or more records per subject (optional), per device, per analys

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 1.3.4 / 3.2.4

**Cited Guidance:** SPDEVID is a required key variable. USUBJID is conditionally required when subject-device relationship is defined in ADDL. Structure is 1 or more records per subject (optional), per device, per analys

---

### ADAMMD-000065

**Description:** ASEQ uniquely indexes records within a subject, or within a device, or within a subject and device; within an ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.4

**Cited Guidance:** ASEQ uniquely indexes records within a subject, or within a device, or within a subject and device; within an ADaM dataset.

---

### ADAMMD-000066

**Description:** When the relationship between subjects and devices is not collected, ADDL must be included.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 1.3.1

**Cited Guidance:** When the relationship between subjects and devices is not collected, ADDL must be included.

---

### ADAMMD-000067

**Description:** If multiple records per device are needed in a device exposure analysis dataset, this information does not belong in ADDL. Instead, it should be put into a BDS structure.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ADAM OTHER |

**Reference:** ADaMIG-MD v1.0, Section 3.2.1.3 (end)

**Cited Guidance:** If multiple records per device are needed in a device exposure analysis dataset, this information does not belong in ADDL. Instead, it should be put into a BDS structure.

---
