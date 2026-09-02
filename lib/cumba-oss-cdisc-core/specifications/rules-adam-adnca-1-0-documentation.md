# ADaM ADNCA v1.0 Conformance Rules Documentation

Auto-generated documentation from `rules-adam-adnca-1-0.json`.

**Total Rules:** 246  
**Standard:** ADaMIG ADNCA v1.0  
**Model:** ADaM v2.1  

## Summary Statistics

| Rule Type | Count |
|-----------|-------|
| Record Data | 31 |
| Variable Metadata Check | 215 |

### By Category

| Category | Count |
|----------|-------|
| Structural / Conformance | 32 |
| Required Variable Checks | 24 |
| Variable Label Conformance | 95 |
| Variable Type Conformance | 95 |

---

## Structural and Conformance Rules

| Core ID | Description | Rule Type |
|---------|-------------|-----------|
| [ADNCA-000215](#adnca-000215) | ADNCA follows the ADaM BDS (Basic Data Structure). | Record Data |
| [ADNCA-000216](#adnca-000216) | Dataset structure: One record per subject per parameter per analysis visit per analysis timepoint. | Record Data |
| [ADNCA-000217](#adnca-000217) | Class is BASIC DATA STRUCTURE, SubClass is NON-COMPARTMENTAL ANALYSIS. | Record Data |
| [ADNCA-000218](#adnca-000218) | NCAXFN can only be included if NCAXFL is also included. | Record Data |
| [ADNCA-000219](#adnca-000219) | PKSUMXFN can only be included if PKSUMXFL is also included. | Record Data |
| [ADNCA-000220](#adnca-000220) | METABFL is required if parent drug and metabolites are present in the dataset. | Record Data |
| [ADNCA-000221](#adnca-000221) | There must be a one-to-one mapping between COHORT and COHORTN. When both present, on a given record, either both must... | Record Data |
| [ADNCA-000222](#adnca-000222) | DOSPCTDF is required if both DOSEA and DOSEP are populated. Formula: 100*(DOSEA-DOSEP)/(DOSEP). | Record Data |
| [ADNCA-000223](#adnca-000223) | There must be a one-to-one mapping between ACYCLE and ACYCLEC. When both present, on a given record, either both must... | Record Data |
| [ADNCA-000224](#adnca-000224) | PCRFEDT, PCRFETM, PCRFEDTM: If dosing occurs over an interval, these should be populated. If populated, ADOSEDUR and ... | Record Data |
| [ADNCA-000225](#adnca-000225) | TMPCTDF is derived using formula: 100*(NRRLT - ARRLT)/(NRRLT). | Record Data |
| [ADNCA-000226](#adnca-000226) | ADOSEDUR is derived from PCRFEDTM - PCRFDTM. | Record Data |
| [ADNCA-000227](#adnca-000227) | When ADOSEDUR is present, NDOSEDUR and/or DOSEDURU must also be included in the dataset. | Record Data |
| [ADNCA-000228](#adnca-000228) | PCSPEC must be a direct copy of PC.PCSPEC. | Record Data |
| [ADNCA-000229](#adnca-000229) | PCSTRESC must be a direct copy of PC.PCSTRESC. | Record Data |
| [ADNCA-000230](#adnca-000230) | PCSTRESU must be a direct copy of PC.PCSTRESU. | Record Data |
| [ADNCA-000231](#adnca-000231) | PCLLOQ must be a direct copy of PC.PCLLOQ. | Record Data |
| [ADNCA-000232](#adnca-000232) | PCGRPID must be a direct copy of PC.PCGRPID. | Record Data |
| [ADNCA-000233](#adnca-000233) | VOLUME is conditionally required if sample is interval-based collection (e.g., urine). | Record Data |
| [ADNCA-000234](#adnca-000234) | VOLUMEU is conditionally required if VOLUME is present. | Record Data |
| [ADNCA-000235](#adnca-000235) | SPWEIGHT is conditionally required if sample is interval-based collection from a non-fluid matrix (e.g., feces). | Record Data |
| [ADNCA-000236](#adnca-000236) | SPWEIGHU is conditionally required if SPWEIGHT is present. | Record Data |
| [ADNCA-000237](#adnca-000237) | DOSEA, DOSEU, and AVISIT have stronger core (Req) for NCA than in general BDS use. | Record Data |
| [ADNCA-000238](#adnca-000238) | Select ADSL Permissible variables are Required in ADNCA because they are scientifically necessary to support PK analy... | Variable Metadata Check |
| [ADNCA-000239](#adnca-000239) | PARAM should completely describe the value in AVAL, including units and specimen type (if applicable). | Record Data |
| [ADNCA-000240](#adnca-000240) | When a record is duplicated for analysis purposes (e.g., 24h post-dose doubles as next-period pre-dose), DTYPE must b... | Record Data |
| [ADNCA-000241](#adnca-000241) | Baseline units for NCA characteristics (BMIBL, HTBL, WTBL) are stored in separate variables (BMIBLU, HTBLU, WTBLU) ra... | Record Data |
| [ADNCA-000242](#adnca-000242) | Usage of start and end datetimes in nominal or actual relative time calculation needs to be described in the define. | Record Data |
| [ADNCA-000243](#adnca-000243) | AVISIT should be unique for a given analysis visit window. Values and derivation rules may differ for different param... | Record Data |
| [ADNCA-000244](#adnca-000244) | ADNCA datasets should be 'analysis-ready' containing all variables needed for performing NCA. | Record Data |
| [ADNCA-000245](#adnca-000245) | Excluded records should still be included in the dataset for traceability and consistent reporting, with NCAXFL set t... | Record Data |
| [ADNCA-000246](#adnca-000246) | For extended duration infusions, use the define to document whether NFRLT is measured from start or end of infusion. | Record Data |

## Required Variable Rules

| Core ID | Variable |
|---------|----------|
| ADNCA-000043 | PCRFTDT |
| ADNCA-000046 | PCRFTTM |
| ADNCA-000049 | PCRFTDTM |
| ADNCA-000068 | NRRLT |
| ADNCA-000071 | ARRLT |
| ADNCA-000082 | RRLTU |
| ADNCA-000093 | AVALU |
| ADNCA-000118 | DOSEA |
| ADNCA-000121 | DOSEU |
| ADNCA-000124 | AVISIT |
| ADNCA-000127 | STUDYID |
| ADNCA-000130 | USUBJID |
| ADNCA-000133 | SUBJID |
| ADNCA-000136 | SITEID |
| ADNCA-000139 | AGE |
| ADNCA-000142 | AGEU |
| ADNCA-000145 | SEX |
| ADNCA-000148 | RACE |
| ADNCA-000151 | TRTP |
| ADNCA-000156 | TRTA |
| ADNCA-000187 | ATPT |
| ADNCA-000192 | PARAM |
| ADNCA-000195 | PARAMCD |
| ADNCA-000200 | AVAL |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| ADNCA-000001 | NCAXFL | PK NCA Exclusion Flag |
| ADNCA-000003 | NCAXFN | PK NCA Exclusion Flag (N) |
| ADNCA-000005 | PKSUMXF | PK Summary Exclusion Flag |
| ADNCA-000007 | PKSUMXFN | PK Summary Exclusion Flag (N) |
| ADNCA-000009 | METABFL | Metabolite Flag |
| ADNCA-000011 | COHORT | Subject Cohort |
| ADNCA-000013 | COHORTN | Subject Cohort (N) |
| ADNCA-000015 | ROUTE | Route |
| ADNCA-000017 | TRTRINT | Planned Treatment Interval |
| ADNCA-000019 | TRTRINTU | Planned Treatment Interval Units |
| ADNCA-000021 | DOSPCTDF | Percent Diff. Nominal vs. Actual Dose |
| ADNCA-000023 | DOSEFRQ | Dose Frequency |
| ADNCA-000025 | ACYCLE | Analysis Cycle |
| ADNCA-000027 | ACYCLEC | Analysis Cycle (C) |
| ADNCA-000029 | FANLDT | First Date of Dose for Analyte |
| ADNCA-000031 | FANLTM | First Time of Dose for Analyte |
| ADNCA-000033 | FANLDTM | First Datetime of Dose for Analyte |
| ADNCA-000035 | FANLEDT | First End Date of Dose for Analyte |
| ADNCA-000037 | FANLETM | First End Time of Dose for Analyte |
| ADNCA-000039 | FANLEDTM | First End Datetime of Dose for Analyte |
| ADNCA-000041 | PCRFTDT | Reference Date of Dose for Analyte |
| ADNCA-000044 | PCRFTTM | Reference Time of Dose for Analyte |
| ADNCA-000047 | PCRFTDTM | Reference Datetime of Dose for Analyte |
| ADNCA-000050 | PCRFEDT | Reference End Date of Dose for Analyte |
| ADNCA-000052 | PCRFETM | Reference End Time of Dose for Analyte |
| ADNCA-000054 | PCRFEDTM | Ref. End Datetime of Dose for Analyte |
| ADNCA-000056 | NFRLT | Nom. Rel. Time from Analyte First Dose |
| ADNCA-000058 | AFRLT | Act. Rel. Time from Analyte First Dose |
| ADNCA-000060 | NEFRLT | Nom. Rel. End Time from First Dose |
| ADNCA-000062 | AEFRLT | Act. Rel. End Time from First Dose |
| ADNCA-000064 | FRLTU | Rel. Time from First Dose Unit |
| ADNCA-000066 | NRRLT | Nominal Rel. Time from Ref. Dose |
| ADNCA-000069 | ARRLT | Actual Rel. Time from Ref. Dose |
| ADNCA-000072 | MRRLT | Modified Rel. Time from Ref. Dose |
| ADNCA-000074 | NERRLT | Nominal Rel. End Time from Ref. Dose |
| ADNCA-000076 | AERRLT | Actual Rel. End Time from Ref. Dose |
| ADNCA-000078 | MERRLT | Modified Rel. End Time from Ref. Dose |
| ADNCA-000080 | RRLTU | Rel. Time from Ref. Dose Unit |
| ADNCA-000083 | TMPCTDF | Percent Diff. Nominal vs. Actual Time |
| ADNCA-000085 | ADOSEDUR | Actual Duration of Treatment Dose |
| ADNCA-000087 | NDOSEDUR | Nominal duration of Treatment Dose |
| ADNCA-000089 | DOSEDURU | Duration of Treatment Dose Units |
| ADNCA-000091 | AVALU | Analysis Value Unit |
| ADNCA-000094 | PCSPEC | Specimen Material Type |
| ADNCA-000096 | PCSTRESC | Character Result/Finding in Std Format |
| ADNCA-000098 | PCSTRESU | Standard Units |
| ADNCA-000100 | ALLOQ | Analysis Lower Limit of Quantitation |
| ADNCA-000102 | PCLLOQ | Lower Limit of Quantitation |
| ADNCA-000104 | VOLUME | Volume Value |
| ADNCA-000106 | VOLUMEU | Volume Value Unit |
| ADNCA-000108 | SPWEIGHT | Specimen Weight Value |
| ADNCA-000110 | SPWEIGHU | Specimen Weight Value Unit |
| ADNCA-000112 | PCGRPID | Group ID |
| ADNCA-000114 | PCSEQ | Sequence Number |
| ADNCA-000116 | DOSEA | Actual Treatment Dose |
| ADNCA-000119 | DOSEU | Treatment Dose Units |
| ADNCA-000122 | AVISIT | Analysis Visit |
| ADNCA-000125 | STUDYID | Study Identifier |
| ADNCA-000128 | USUBJID | Unique Subject Identifier |
| ADNCA-000131 | SUBJID | Subject Identifier for the Study |
| ADNCA-000134 | SITEID | Study Site Identifier |
| ADNCA-000137 | AGE | Age |
| ADNCA-000140 | AGEU | Age Units |
| ADNCA-000143 | SEX | Sex |
| ADNCA-000146 | RACE | Race |
| ADNCA-000149 | TRTP | Planned Treatment |
| ADNCA-000152 | TRTPN | Planned Treatment (N) |
| ADNCA-000154 | TRTA | Actual Treatment |
| ADNCA-000157 | TRTAN | Actual Treatment (N) |
| ADNCA-000159 | DOSEP | Planned Treatment Dose |
| ADNCA-000161 | APERIOD | Period |
| ADNCA-000163 | APERIODC | Period (C) |
| ADNCA-000165 | AVISITN | Analysis Visit (N) |
| ADNCA-000167 | ADT | Analysis Date |
| ADNCA-000169 | ATM | Analysis Time |
| ADNCA-000171 | ADTM | Analysis Datetime |
| ADNCA-000173 | ASTDT | Analysis Start Date |
| ADNCA-000175 | ASTTM | Analysis Start Time |
| ADNCA-000177 | ASTDTM | Analysis Start Datetime |
| ADNCA-000179 | AENDT | Analysis End Date |
| ADNCA-000181 | AENTM | Analysis End Time |
| ADNCA-000183 | AENDTM | Analysis End Datetime |
| ADNCA-000185 | ATPT | Analysis Timepoint |
| ADNCA-000188 | ATPTN | Analysis Timepoint (N) |
| ADNCA-000190 | PARAM | Parameter |
| ADNCA-000193 | PARAMCD | Parameter Code |
| ADNCA-000196 | PARAMN | Parameter (N) |
| ADNCA-000198 | AVAL | Analysis Value |
| ADNCA-000201 | DTYP | Derivation Type |
| ADNCA-000203 | BMIBL | Baseline BMI |
| ADNCA-000205 | BMIBLU | Baseline BMI Units |
| ADNCA-000207 | HTBL | Baseline Height |
| ADNCA-000209 | HTBLU | Baseline Height Units |
| ADNCA-000211 | WTBL | Baseline Weight |
| ADNCA-000213 | WTBLU | Baseline Weight Units |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| ADNCA-000002 | NCAXFL | Char |
| ADNCA-000004 | NCAXFN | Num |
| ADNCA-000006 | PKSUMXF | Char |
| ADNCA-000008 | PKSUMXFN | Num |
| ADNCA-000010 | METABFL | Char |
| ADNCA-000012 | COHORT | Char |
| ADNCA-000014 | COHORTN | Num |
| ADNCA-000016 | ROUTE | Char |
| ADNCA-000018 | TRTRINT | Num |
| ADNCA-000020 | TRTRINTU | Char |
| ADNCA-000022 | DOSPCTDF | Num |
| ADNCA-000024 | DOSEFRQ | Char |
| ADNCA-000026 | ACYCLE | Num |
| ADNCA-000028 | ACYCLEC | Char |
| ADNCA-000030 | FANLDT | Num |
| ADNCA-000032 | FANLTM | Num |
| ADNCA-000034 | FANLDTM | Num |
| ADNCA-000036 | FANLEDT | Num |
| ADNCA-000038 | FANLETM | Num |
| ADNCA-000040 | FANLEDTM | Num |
| ADNCA-000042 | PCRFTDT | Num |
| ADNCA-000045 | PCRFTTM | Num |
| ADNCA-000048 | PCRFTDTM | Num |
| ADNCA-000051 | PCRFEDT | Num |
| ADNCA-000053 | PCRFETM | Num |
| ADNCA-000055 | PCRFEDTM | Num |
| ADNCA-000057 | NFRLT | Num |
| ADNCA-000059 | AFRLT | Num |
| ADNCA-000061 | NEFRLT | Num |
| ADNCA-000063 | AEFRLT | Num |
| ADNCA-000065 | FRLTU | Char |
| ADNCA-000067 | NRRLT | Num |
| ADNCA-000070 | ARRLT | Num |
| ADNCA-000073 | MRRLT | Num |
| ADNCA-000075 | NERRLT | Num |
| ADNCA-000077 | AERRLT | Num |
| ADNCA-000079 | MERRLT | Num |
| ADNCA-000081 | RRLTU | Char |
| ADNCA-000084 | TMPCTDF | Num |
| ADNCA-000086 | ADOSEDUR | Num |
| ADNCA-000088 | NDOSEDUR | Num |
| ADNCA-000090 | DOSEDURU | Char |
| ADNCA-000092 | AVALU | Char |
| ADNCA-000095 | PCSPEC | Char |
| ADNCA-000097 | PCSTRESC | Char |
| ADNCA-000099 | PCSTRESU | Char |
| ADNCA-000101 | ALLOQ | Num |
| ADNCA-000103 | PCLLOQ | Num |
| ADNCA-000105 | VOLUME | Num |
| ADNCA-000107 | VOLUMEU | Char |
| ADNCA-000109 | SPWEIGHT | Num |
| ADNCA-000111 | SPWEIGHU | Char |
| ADNCA-000113 | PCGRPID | Char |
| ADNCA-000115 | PCSEQ | Num |
| ADNCA-000117 | DOSEA | Num |
| ADNCA-000120 | DOSEU | Char |
| ADNCA-000123 | AVISIT | Char |
| ADNCA-000126 | STUDYID | Char |
| ADNCA-000129 | USUBJID | Char |
| ADNCA-000132 | SUBJID | Char |
| ADNCA-000135 | SITEID | Char |
| ADNCA-000138 | AGE | Num |
| ADNCA-000141 | AGEU | Char |
| ADNCA-000144 | SEX | Char |
| ADNCA-000147 | RACE | Char |
| ADNCA-000150 | TRTP | Char |
| ADNCA-000153 | TRTPN | Num |
| ADNCA-000155 | TRTA | Char |
| ADNCA-000158 | TRTAN | Num |
| ADNCA-000160 | DOSEP | Num |
| ADNCA-000162 | APERIOD | Num |
| ADNCA-000164 | APERIODC | Char |
| ADNCA-000166 | AVISITN | Num |
| ADNCA-000168 | ADT | Num |
| ADNCA-000170 | ATM | Num |
| ADNCA-000172 | ADTM | Num |
| ADNCA-000174 | ASTDT | Num |
| ADNCA-000176 | ASTTM | Num |
| ADNCA-000178 | ASTDTM | Num |
| ADNCA-000180 | AENDT | Num |
| ADNCA-000182 | AENTM | Num |
| ADNCA-000184 | AENDTM | Num |
| ADNCA-000186 | ATPT | Char |
| ADNCA-000189 | ATPTN | Num |
| ADNCA-000191 | PARAM | Char |
| ADNCA-000194 | PARAMCD | Char |
| ADNCA-000197 | PARAMN | Num |
| ADNCA-000199 | AVAL | Num |
| ADNCA-000202 | DTYP | Char |
| ADNCA-000204 | BMIBL | Num |
| ADNCA-000206 | BMIBLU | Char |
| ADNCA-000208 | HTBL | Num |
| ADNCA-000210 | HTBLU | Char |
| ADNCA-000212 | WTBL | Num |
| ADNCA-000214 | WTBLU | Char |

---

## Rule Details (Structural Rules)

### ADNCA-000215

**Description:** ADNCA follows the ADaM BDS (Basic Data Structure).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 4

**Cited Guidance:** ADNCA follows the ADaM BDS (Basic Data Structure).

---

### ADNCA-000216

**Description:** Dataset structure: One record per subject per parameter per analysis visit per analysis timepoint.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.1.1

**Cited Guidance:** Dataset structure: One record per subject per parameter per analysis visit per analysis timepoint.

---

### ADNCA-000217

**Description:** Class is BASIC DATA STRUCTURE, SubClass is NON-COMPARTMENTAL ANALYSIS.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.1 / Table 4.1.1

**Cited Guidance:** Class is BASIC DATA STRUCTURE, SubClass is NON-COMPARTMENTAL ANALYSIS.

---

### ADNCA-000218

**Description:** NCAXFN can only be included if NCAXFL is also included.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** NCAXFN can only be included if NCAXFL is also included.

---

### ADNCA-000219

**Description:** PKSUMXFN can only be included if PKSUMXFL is also included.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PKSUMXFN can only be included if PKSUMXFL is also included.

---

### ADNCA-000220

**Description:** METABFL is required if parent drug and metabolites are present in the dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** METABFL is required if parent drug and metabolites are present in the dataset.

---

### ADNCA-000221

**Description:** There must be a one-to-one mapping between COHORT and COHORTN. When both present, on a given record, either both must be populated or both must be null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** There must be a one-to-one mapping between COHORT and COHORTN. When both present, on a given record, either both must be populated or both must be null.

---

### ADNCA-000222

**Description:** DOSPCTDF is required if both DOSEA and DOSEP are populated. Formula: 100*(DOSEA-DOSEP)/(DOSEP).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** DOSPCTDF is required if both DOSEA and DOSEP are populated. Formula: 100*(DOSEA-DOSEP)/(DOSEP).

---

### ADNCA-000223

**Description:** There must be a one-to-one mapping between ACYCLE and ACYCLEC. When both present, on a given record, either both must be populated or both must be null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** There must be a one-to-one mapping between ACYCLE and ACYCLEC. When both present, on a given record, either both must be populated or both must be null.

---

### ADNCA-000224

**Description:** PCRFEDT, PCRFETM, PCRFEDTM: If dosing occurs over an interval, these should be populated. If populated, ADOSEDUR and DOSEDURU are required.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PCRFEDT, PCRFETM, PCRFEDTM: If dosing occurs over an interval, these should be populated. If populated, ADOSEDUR and DOSEDURU are required.

---

### ADNCA-000225

**Description:** TMPCTDF is derived using formula: 100*(NRRLT - ARRLT)/(NRRLT).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** TMPCTDF is derived using formula: 100*(NRRLT - ARRLT)/(NRRLT).

---

### ADNCA-000226

**Description:** ADOSEDUR is derived from PCRFEDTM - PCRFDTM.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** ADOSEDUR is derived from PCRFEDTM - PCRFDTM.

---

### ADNCA-000227

**Description:** When ADOSEDUR is present, NDOSEDUR and/or DOSEDURU must also be included in the dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** When ADOSEDUR is present, NDOSEDUR and/or DOSEDURU must also be included in the dataset.

---

### ADNCA-000228

**Description:** PCSPEC must be a direct copy of PC.PCSPEC.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PCSPEC must be a direct copy of PC.PCSPEC.

---

### ADNCA-000229

**Description:** PCSTRESC must be a direct copy of PC.PCSTRESC.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PCSTRESC must be a direct copy of PC.PCSTRESC.

---

### ADNCA-000230

**Description:** PCSTRESU must be a direct copy of PC.PCSTRESU.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PCSTRESU must be a direct copy of PC.PCSTRESU.

---

### ADNCA-000231

**Description:** PCLLOQ must be a direct copy of PC.PCLLOQ.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PCLLOQ must be a direct copy of PC.PCLLOQ.

---

### ADNCA-000232

**Description:** PCGRPID must be a direct copy of PC.PCGRPID.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** PCGRPID must be a direct copy of PC.PCGRPID.

---

### ADNCA-000233

**Description:** VOLUME is conditionally required if sample is interval-based collection (e.g., urine).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** VOLUME is conditionally required if sample is interval-based collection (e.g., urine).

---

### ADNCA-000234

**Description:** VOLUMEU is conditionally required if VOLUME is present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** VOLUMEU is conditionally required if VOLUME is present.

---

### ADNCA-000235

**Description:** SPWEIGHT is conditionally required if sample is interval-based collection from a non-fluid matrix (e.g., feces).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** SPWEIGHT is conditionally required if sample is interval-based collection from a non-fluid matrix (e.g., feces).

---

### ADNCA-000236

**Description:** SPWEIGHU is conditionally required if SPWEIGHT is present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** SPWEIGHU is conditionally required if SPWEIGHT is present.

---

### ADNCA-000237

**Description:** DOSEA, DOSEU, and AVISIT have stronger core (Req) for NCA than in general BDS use.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.2

**Cited Guidance:** DOSEA, DOSEU, and AVISIT have stronger core (Req) for NCA than in general BDS use.

---

### ADNCA-000238

**Description:** Select ADSL Permissible variables are Required in ADNCA because they are scientifically necessary to support PK analyses.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 3 - Points to Consider

**Cited Guidance:** Select ADSL Permissible variables are Required in ADNCA because they are scientifically necessary to support PK analyses.

---

### ADNCA-000239

**Description:** PARAM should completely describe the value in AVAL, including units and specimen type (if applicable).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 5 - Example 2: Urine and Plasma

**Cited Guidance:** PARAM should completely describe the value in AVAL, including units and specimen type (if applicable).

---

### ADNCA-000240

**Description:** When a record is duplicated for analysis purposes (e.g., 24h post-dose doubles as next-period pre-dose), DTYPE must be populated (e.g., 'COPY') and PCSEQ is repeated.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 5 - Duplicated Records Example

**Cited Guidance:** When a record is duplicated for analysis purposes (e.g., 24h post-dose doubles as next-period pre-dose), DTYPE must be populated (e.g., 'COPY') and PCSEQ is repeated.

---

### ADNCA-000241

**Description:** Baseline units for NCA characteristics (BMIBL, HTBL, WTBL) are stored in separate variables (BMIBLU, HTBLU, WTBLU) rather than in the label, due to NCA tool requirements.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 4.2

**Cited Guidance:** Baseline units for NCA characteristics (BMIBL, HTBL, WTBL) are stored in separate variables (BMIBLU, HTBLU, WTBLU) rather than in the label, due to NCA tool requirements.

---

### ADNCA-000242

**Description:** Usage of start and end datetimes in nominal or actual relative time calculation needs to be described in the define.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 4.2

**Cited Guidance:** Usage of start and end datetimes in nominal or actual relative time calculation needs to be described in the define.

---

### ADNCA-000243

**Description:** AVISIT should be unique for a given analysis visit window. Values and derivation rules may differ for different parameters within the same dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.2

**Cited Guidance:** AVISIT should be unique for a given analysis visit window. Values and derivation rules may differ for different parameters within the same dataset.

---

### ADNCA-000244

**Description:** ADNCA datasets should be 'analysis-ready' containing all variables needed for performing NCA.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 3 - Points to Consider

**Cited Guidance:** ADNCA datasets should be 'analysis-ready' containing all variables needed for performing NCA.

---

### ADNCA-000245

**Description:** Excluded records should still be included in the dataset for traceability and consistent reporting, with NCAXFL set to 'Y' and appropriate NCAwXRS reason populated.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Section 5 - Exclusion Flags Example 1

**Cited Guidance:** Excluded records should still be included in the dataset for traceability and consistent reporting, with NCAXFL set to 'Y' and appropriate NCAwXRS reason populated.

---

### ADNCA-000246

**Description:** For extended duration infusions, use the define to document whether NFRLT is measured from start or end of infusion.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |
| Domains | ADNCA |

**Reference:** ADaMIG ADNCA v1.0, Section Table 4.2.1

**Cited Guidance:** For extended duration infusions, use the define to document whether NFRLT is measured from start or end of infusion.

---
