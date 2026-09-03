# ADaMIG 1.1 Conformance Rules Documentation

Auto-generated documentation from `rules/rules-adamig-1-1.json`.
Rules derived from ADaM v2.1 and ADaMIG v1.1 specifications.

**Total Rules:** 438  
**Standard:** ADaMIG v1.1  
**Model:** ADaM v2.1  

## Key Differences from ADaMIG v1.3

| Change | Details |
|--------|---------|
| Variables only in v1.1 | PARAMTYP, ONTRTFN, AENDTF, ANRHI, ANRHIC, TRCMP, TRCMPGy, TRCMPGyN, DCTREAS, ASPRSTMF, PHwEDTF, PHwETMF |
| New variables in v1.3 | 53 variables added (stratification, toxicity grading, criterion, region, etc.) |
| Label changes | ~167 labels refined/expanded in v1.3 (mostly completions of truncated labels) |
| Type changes | None |

## Summary Statistics

| Rule Type | Count |
|-----------|-------|
| Dataset Metadata Check | 7 |
| Domain Presence Check | 1 |
| Record Data | 46 |
| Value Check with Dataset Metadata | 1 |
| Value Check with Variable Metadata | 2 |
| Variable Metadata Check | 381 |

### By Category

| Category | Count |
|----------|-------|
| Structural / Data Quality | 102 |
| Variable Label Conformance | 168 |
| Variable Type Conformance | 168 |

---

## Structural and Data Quality Rules

| Core ID | Description | Rule Type |
|---------|-------------|-----------|
| [ADAM11-000001](#adam11-000001) | ADSL dataset is required in a CDISC-based submission. | Domain Presence Check |
| [ADAM11-000002](#adam11-000002) | ADSL must contain exactly one record per subject. | Record Data |
| [ADAM11-000003](#adam11-000003) | STUDYID is required in ADSL. | Variable Metadata Check |
| [ADAM11-000004](#adam11-000004) | USUBJID is required in ADSL. | Variable Metadata Check |
| [ADAM11-000005](#adam11-000005) | SUBJID is required in ADSL. | Variable Metadata Check |
| [ADAM11-000006](#adam11-000006) | SITEID is required in ADSL. | Variable Metadata Check |
| [ADAM11-000007](#adam11-000007) | AGE is required in ADSL. | Variable Metadata Check |
| [ADAM11-000008](#adam11-000008) | AGEU is required in ADSL. | Variable Metadata Check |
| [ADAM11-000009](#adam11-000009) | SEX is required in ADSL. | Variable Metadata Check |
| [ADAM11-000010](#adam11-000010) | RACE is required in ADSL. | Variable Metadata Check |
| [ADAM11-000011](#adam11-000011) | ARM is required in ADSL. | Variable Metadata Check |
| [ADAM11-000012](#adam11-000012) | TRT01P is required in ADSL. At least TRT01P is required. | Variable Metadata Check |
| [ADAM11-000013](#adam11-000013) | At least one population flag variable is required in ADSL. | Variable Metadata Check |
| [ADAM11-000014](#adam11-000014) | When SAFFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000015](#adam11-000015) | When FASFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000016](#adam11-000016) | When ITTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000017](#adam11-000017) | When PPROTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000018](#adam11-000018) | When COMPLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000019](#adam11-000019) | When RANDFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000020](#adam11-000020) | When ENRLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM11-000021](#adam11-000021) | ADaM dataset names must start with 'AD'. | Dataset Metadata Check |
| [ADAM11-000022](#adam11-000022) | ADaM dataset names must not exceed 8 characters. | Dataset Metadata Check |
| [ADAM11-000023](#adam11-000023) | ADaM variable names must not exceed 8 characters. | Variable Metadata Check |
| [ADAM11-000024](#adam11-000024) | ADaM variable labels must not exceed 40 characters. | Variable Metadata Check |
| [ADAM11-000025](#adam11-000025) | ADaM character variables must not exceed 200 characters in length. | Variable Metadata Check |
| [ADAM11-000026](#adam11-000026) | USUBJID is required in all BDS datasets. | Variable Metadata Check |
| [ADAM11-000027](#adam11-000027) | PARAM is required in all BDS datasets. | Variable Metadata Check |
| [ADAM11-000028](#adam11-000028) | PARAMCD is required in all BDS datasets. | Variable Metadata Check |
| [ADAM11-000029](#adam11-000029) | When PARAMN exists, there must be a one-to-one relationship between PARAMN and PARAM within a dataset. | Record Data |
| [ADAM11-000030](#adam11-000030) | There must be a one-to-one relationship between PARAMCD and PARAM within a dataset. | Record Data |
| [ADAM11-000031](#adam11-000031) | PARAMCD must not exceed 8 characters. | Record Data |
| [ADAM11-000032](#adam11-000032) | At least one of AVAL or AVALC must be present in a BDS dataset. | Variable Metadata Check |
| [ADAM11-000033](#adam11-000033) | DTYPE must be present when a BDS dataset contains derived records. | Variable Metadata Check |
| [ADAM11-000034](#adam11-000034) | Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values). | Record Data |
| [ADAM11-000035](#adam11-000035) | When both *DT and *DTM variables exist, the *DT value must match the date part of the *DTM value. | Record Data |
| [ADAM11-000036](#adam11-000036) | When both *TM and *DTM variables exist, the *TM value must match the time part of the *DTM value. | Record Data |
| [ADAM11-000037](#adam11-000037) | Relative day variables ending in DY must not have a value of 0. | Record Data |
| [ADAM11-000038](#adam11-000038) | Date imputation flag (*DTF) must be populated when a date (*DT) is imputed. | Record Data |
| [ADAM11-000039](#adam11-000039) | Date imputation flag (*DTF) must have values of Y, M, or D when populated. | Record Data |
| [ADAM11-000040](#adam11-000040) | Time imputation flag (*TMF) must have values of H, M, or S when populated. | Record Data |
| [ADAM11-000041](#adam11-000041) | Character flag variables (*FL) must have values of Y, N, or null. | Record Data |
| [ADAM11-000042](#adam11-000042) | Numeric flag (*FN) cannot exist without corresponding character flag (*FL). | Variable Metadata Check |
| [ADAM11-000043](#adam11-000043) | TRT01PN cannot be present unless TRT01P is also present. | Variable Metadata Check |
| [ADAM11-000044](#adam11-000044) | TRT01AN cannot be present unless TRT01A is also present. | Variable Metadata Check |
| [ADAM11-000045](#adam11-000045) | TRTSDT and/or TRTSDTM are required in ADSL if there is an investigational product. | Variable Metadata Check |
| [ADAM11-000046](#adam11-000046) | TRTEDT and/or TRTEDTM are required in ADSL if there is an investigational product. | Variable Metadata Check |
| [ADAM11-000047](#adam11-000047) | TRTSDTF must be populated when TRTSDT is imputed. | Record Data |
| [ADAM11-000048](#adam11-000048) | Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT). | Record Data |
| [ADAM11-000049](#adam11-000049) | RANDDT is required in randomized trials. | Variable Metadata Check |
| [ADAM11-000050](#adam11-000050) | AVAL must be numeric type. | Variable Metadata Check |
| [ADAM11-000051](#adam11-000051) | AVALC must be character type. | Variable Metadata Check |
| [ADAM11-000052](#adam11-000052) | BASE must be present when CHG is present in a BDS dataset. | Variable Metadata Check |
| [ADAM11-000053](#adam11-000053) | CHG must equal AVAL minus BASE. | Record Data |
| [ADAM11-000054](#adam11-000054) | CHG should be null for baseline records (where ABLFL = 'Y'). | Record Data |
| [ADAM11-000055](#adam11-000055) | PCT (Percent Change from Baseline) should be null for baseline records. | Record Data |
| [ADAM11-000056](#adam11-000056) | ABLFL (Analysis Baseline Flag) must be 'Y' or null. | Record Data |
| [ADAM11-000057](#adam11-000057) | There should be at most one record with ABLFL = 'Y' per subject per parameter per baseline type. | Record Data |
| [ADAM11-000058](#adam11-000058) | When AVISITN exists, there must be a one-to-one relationship between AVISIT and AVISITN within a parameter. | Record Data |
| [ADAM11-000059](#adam11-000059) | ADT must be present when ADY is present in a dataset. | Variable Metadata Check |
| [ADAM11-000060](#adam11-000060) | When ATPTN exists, there must be a one-to-one relationship between ATPT and ATPTN. | Record Data |
| [ADAM11-000061](#adam11-000061) | CNSR is required in time-to-event BDS datasets. | Variable Metadata Check |
| [ADAM11-000062](#adam11-000062) | CNSR values should be 0 (event) or a positive integer (censored). | Record Data |
| [ADAM11-000063](#adam11-000063) | When ANRHIy exists, ANRLOy must also exist. | Variable Metadata Check |
| [ADAM11-000064](#adam11-000064) | ANLzzFL (Analysis Record Flag) must be 'Y' or null. | Record Data |
| [ADAM11-000065](#adam11-000065) | USUBJID values in BDS datasets must have a corresponding record in ADSL. | Record Data |
| [ADAM11-000066](#adam11-000066) | A variable present in both ADSL and another ADaM dataset must have the same values, type, and label. | Record Data |
| [ADAM11-000067](#adam11-000067) | TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment. | Variable Metadata Check |
| [ADAM11-000068](#adam11-000068) | TRTP must be present when TRTA is present in a BDS dataset. | Variable Metadata Check |
| [ADAM11-000069](#adam11-000069) | TRTPN cannot be present without TRTP. | Variable Metadata Check |
| [ADAM11-000070](#adam11-000070) | The ADSL dataset class must be 'SUBJECT LEVEL ANALYSIS DATASET'. | Dataset Metadata Check |
| [ADAM11-000071](#adam11-000071) | Non-ADaM analysis dataset names should not start with 'AD' prefix. | Dataset Metadata Check |
| [ADAM11-000072](#adam11-000072) | When SRCVAR exists, SRCDOM must also exist for datapoint traceability. | Variable Metadata Check |
| [ADAM11-000073](#adam11-000073) | When SRCSEQ exists, SRCDOM must also exist. | Variable Metadata Check |
| [ADAM11-000074](#adam11-000074) | DTYPE values should be from the ADaM controlled terminology. | Record Data |
| [ADAM11-000075](#adam11-000075) | BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset. | Variable Metadata Check |
| [ADAM11-000076](#adam11-000076) | EOSSTT values should be from SBJTSTAT controlled terminology. | Record Data |
| [ADAM11-000077](#adam11-000077) | DCSREAS should be null when EOSSTT is 'COMPLETED'. | Record Data |
| [ADAM11-000078](#adam11-000078) | ADaM datasets must be accompanied by metadata. | Dataset Metadata Check |
| [ADAM11-000079](#adam11-000079) | BASE must be a numeric variable. | Variable Metadata Check |
| [ADAM11-000080](#adam11-000080) | CHG must be a numeric variable. | Variable Metadata Check |
| [ADAM11-000081](#adam11-000081) | AAGE is required if analysis age differs from DM.AGE. | Record Data |
| [ADAM11-000082](#adam11-000082) | STUDYID must not be null in any ADaM dataset. | Record Data |
| [ADAM11-000083](#adam11-000083) | USUBJID must not be null in any ADaM dataset. | Record Data |
| [ADAM11-000084](#adam11-000084) | PARAMCD must contain only letters, underscores, and numerals. | Record Data |
| [ADAM11-000085](#adam11-000085) | PARAM must not be null in BDS datasets. | Record Data |
| [ADAM11-000086](#adam11-000086) | PARAMCD must not be null in BDS datasets. | Record Data |
| [ADAM11-000087](#adam11-000087) | CRITyFL cannot exist without corresponding CRITy. | Variable Metadata Check |
| [ADAM11-000088](#adam11-000088) | CRITyFL must have values of Y, N, or null. | Record Data |
| [ADAM11-000089](#adam11-000089) | TRTP and TRTPN must have a one-to-one relationship within a dataset. | Record Data |
| [ADAM11-000090](#adam11-000090) | AGEU should use controlled terminology values. | Record Data |
| [ADAM11-000091](#adam11-000091) | Collected Duration (--DUR) value should not be negative. | Record Data |
| [ADAM11-000092](#adam11-000092) | Text variable in submitted dataset should not contain  '.' as an entire value. | Value Check with Variable Metadata |
| [ADAM11-000093](#adam11-000093) | Text variable in submitted dataset should not contain leading spaces ' '. | Value Check with Variable Metadata |
| [ADAM11-000094](#adam11-000094) | Part A: Raise an error when a Required variable is not present in the dataset. | Variable Metadata Check |
| [ADAM11-000095](#adam11-000095) | Raise an error when an expected variable is not present in the dataset. | Variable Metadata Check |
| [ADAM11-000096](#adam11-000096) | The submitted dataset is larger than 5 GB | Dataset Metadata Check |
| [ADAM11-000097](#adam11-000097) | Raise an error when variables are not in the specified order | Variable Metadata Check |
| [ADAM11-000098](#adam11-000098) | Raise an error when a variable is not an allowed variable for an Observation Class | Variable Metadata Check |
| [ADAM11-000099](#adam11-000099) | Raise an error when a dataset has no records. | Dataset Metadata Check |
| [ADAM11-000100](#adam11-000100) | Raise an error when a variable label is not in title case | Variable Metadata Check |
| [ADAM11-000101](#adam11-000101) | Part B: Raise an error when a Required variable is null. | Value Check with Dataset Metadata |
| [ADAM11-000438](#adam11-000438) | When PARAMTYP exists, its value must be 'DERIVED' or null. | Record Data |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| ADAM11-000102 | AAGE | Analysis Age |
| ADAM11-000104 | ABLFL | Baseline Record |
| ADAM11-000106 | ABLFN | Baseline Record |
| ADAM11-000108 | ACTARM | Description of |
| ADAM11-000110 | ADT | Analysis Date |
| ADAM11-000112 | ADTF | Analysis Date |
| ADAM11-000114 | ADTM | Analysis Datetime |
| ADAM11-000116 | ADY | Analysis Relative |
| ADAM11-000118 | AENDT | Analysis End Date |
| ADAM11-000120 | AENDTF | Analysis End Date |
| ADAM11-000122 | AENDTM | Analysis End |
| ADAM11-000124 | AENDY | Analysis End |
| ADAM11-000126 | AENTM | Analysis End Time |
| ADAM11-000128 | AENTMF | Analysis End Time |
| ADAM11-000130 | AGE | Age |
| ADAM11-000132 | AGEU | Age Units |
| ADAM11-000134 | ANRHI | Analysis Normal |
| ADAM11-000136 | ANRHIC | Analysis Normal |
| ADAM11-000138 | ANRIND | Analysis Reference |
| ADAM11-000140 | ANRLO | Analysis Normal |
| ADAM11-000142 | ANRLOC | Analysis Normal |
| ADAM11-000144 | APEREDT | Period End Date |
| ADAM11-000146 | APEREDTF | Period End Date |
| ADAM11-000148 | APEREDTM | Period End |
| ADAM11-000150 | APERETM | Period End Time |
| ADAM11-000152 | APERETMF | Period End Time |
| ADAM11-000154 | APERIOD | Period |
| ADAM11-000156 | APERIODC | Period (C) |
| ADAM11-000158 | APERSDT | Period Start Date |
| ADAM11-000160 | APERSDTF | Period Start Date |
| ADAM11-000162 | APERSDTM | Period Start |
| ADAM11-000164 | APERSTM | Period Start Time |
| ADAM11-000166 | APERSTMF | Period Start Time |
| ADAM11-000168 | APHASE | Phase |
| ADAM11-000170 | APHASEN | Phase (N) |
| ADAM11-000172 | ARELTM | Analysis Relative |
| ADAM11-000174 | ARELTMU | Analysis Relative |
| ADAM11-000176 | ARM | Description of |
| ADAM11-000178 | ASPER | Subperiod within |
| ADAM11-000180 | ASPERC | Subperiod within |
| ADAM11-000182 | ASPREDTM | Subperiod End |
| ADAM11-000184 | ASPRSDT | Subperiod Start |
| ADAM11-000186 | ASPRSDTF | Subperiod Start |
| ADAM11-000188 | ASPRSDTM | Subperiod Start |
| ADAM11-000190 | ASPRSTM | Subperiod Start |
| ADAM11-000192 | ASPRSTMF | Subperiod Start |
| ADAM11-000194 | ASTDT | Analysis Start Date |
| ADAM11-000196 | ASTDTF | Analysis Start Date |
| ADAM11-000198 | ASTDTM | Analysis Start |
| ADAM11-000200 | ASTDY | Analysis Start |
| ADAM11-000202 | ASTTM | Analysis Start Time |
| ADAM11-000204 | ASTTMF | Analysis Start Time |
| ADAM11-000206 | ATM | Analysis Time |
| ADAM11-000208 | ATMF | Analysis Time |
| ADAM11-000210 | ATOXGR | Analysis Toxicity |
| ADAM11-000212 | ATPT | Analysis Timepoint |
| ADAM11-000214 | ATPTN | Analysis Timepoint |
| ADAM11-000216 | ATPTREF | Analysis Timepoint |
| ADAM11-000218 | AVAL | Analysis Value |
| ADAM11-000220 | AVALC | Analysis Value (C) |
| ADAM11-000222 | AVISIT | Analysis Visit |
| ADAM11-000224 | AVISITN | Analysis Visit (N) |
| ADAM11-000226 | AWHI | Analysis Window |
| ADAM11-000228 | AWLO | Analysis Window |
| ADAM11-000230 | AWRANGE | Analysis Window |
| ADAM11-000232 | AWTARGET | Analysis Window |
| ADAM11-000234 | AWTDIFF | Analysis Window |
| ADAM11-000236 | AWU | Analysis Window |
| ADAM11-000238 | BASE | Baseline Value |
| ADAM11-000240 | BASEC | Baseline Value (C) |
| ADAM11-000242 | BASETYPE | Baseline Type |
| ADAM11-000244 | BNRIND | Baseline Reference |
| ADAM11-000246 | BTOXGR | Baseline Toxicity |
| ADAM11-000248 | CHG | Change from |
| ADAM11-000250 | CNSDTDSC | Censor Date |
| ADAM11-000252 | CNSR | Censor |
| ADAM11-000254 | COMPLFL | Completers |
| ADAM11-000256 | COMPLPFL | Completers |
| ADAM11-000258 | COMPLRFL | Completers Record- |
| ADAM11-000260 | DCSREAS | Reason for |
| ADAM11-000262 | DCSREASP | Reason Spec for |
| ADAM11-000264 | DCTREAS | Reason for |
| ADAM11-000266 | DOSCUMA | Cumulative Actual |
| ADAM11-000268 | DOSCUMP | Cumulative Planned |
| ADAM11-000270 | DOSEA | Actual Treatment |
| ADAM11-000272 | DOSEP | Planned Treatment |
| ADAM11-000274 | DOSEU | Treatment Dose |
| ADAM11-000276 | DTHCAUS | Cause of Death |
| ADAM11-000278 | DTHCAUSN | Cause of Death (N) |
| ADAM11-000280 | DTHDT | Date of Death |
| ADAM11-000282 | DTHDTF | Date of Death |
| ADAM11-000284 | DTYPE | Derivation Type |
| ADAM11-000286 | ENRLDT | Date of Enrollment |
| ADAM11-000288 | ENRLFL | Enrolled Population |
| ADAM11-000290 | EOSDT | End of Study Date |
| ADAM11-000292 | EOSSTT | End of Study Status |
| ADAM11-000294 | EOTSTT | End of Treatment |
| ADAM11-000296 | EVNTDESC | Event or Censoring |
| ADAM11-000298 | FASFL | Full Analysis Set |
| ADAM11-000300 | FASPFL | Full Analysis Set |
| ADAM11-000302 | FASRFL | Full Analysis Set |
| ADAM11-000304 | ITTFL | Intent-To-Treat |
| ADAM11-000306 | ITTPFL | Intent-To-Treat |
| ADAM11-000308 | ITTRFL | Intent-To-Treat |
| ADAM11-000310 | LSTALVDT | Date Last Known |
| ADAM11-000312 | LVOTFL | Last Value On |
| ADAM11-000314 | LVOTFN | Last Value On |
| ADAM11-000316 | ONTRTFL | On Treatment |
| ADAM11-000318 | ONTRTFN | On Treatment |
| ADAM11-000320 | PARAM | Parameter |
| ADAM11-000322 | PARAMCD | Parameter Code |
| ADAM11-000324 | PARAMN | Parameter (N) |
| ADAM11-000326 | PARAMTYP | Parameter Type |
| ADAM11-000328 | PCHG | Percent Change |
| ADAM11-000330 | PHEDT | Phase End Date |
| ADAM11-000332 | PHEDTF | Phase End Date |
| ADAM11-000334 | PHETM | Phase End Time |
| ADAM11-000336 | PHETMF | Phase End Time |
| ADAM11-000338 | PHSDT | Phase Start Date |
| ADAM11-000340 | PHSDTF | Phase Start Date |
| ADAM11-000342 | PHSDTM | Phase Start |
| ADAM11-000344 | PHSTM | Phase Start Time |
| ADAM11-000346 | PHSTMF | Phase Start Time |
| ADAM11-000348 | PPROTFL | Per-Protocol |
| ADAM11-000350 | PPROTPFL | Per-Protocol |
| ADAM11-000352 | PPROTRFL | Per-Protocol |
| ADAM11-000354 | R2BASE | Ratio to Baseline |
| ADAM11-000356 | RACE | Race |
| ADAM11-000358 | RANDDT | Date of |
| ADAM11-000360 | RANDFL | Randomized |
| ADAM11-000362 | RFICDT | Date of Informed |
| ADAM11-000364 | SAFFL | Safety Population |
| ADAM11-000366 | SAFPFL | Safety Analysis |
| ADAM11-000368 | SAFRFL | Safety Analysis |
| ADAM11-000370 | SEX | Sex |
| ADAM11-000372 | SITEID | Study Site Identifier |
| ADAM11-000374 | SRCDOM | Source Data |
| ADAM11-000376 | SRCSEQ | Source Sequence |
| ADAM11-000378 | SRCVAR | Source Variable |
| ADAM11-000380 | STARTDT | Time-to-Event |
| ADAM11-000382 | STARTDTF | Origin Date |
| ADAM11-000384 | STARTDTM | Time-to-Event |
| ADAM11-000386 | STARTTMF | Origin Time |
| ADAM11-000388 | STUDYID | Study Identifier |
| ADAM11-000390 | SUBJID | Subject Identifier |
| ADAM11-000392 | TRCMP | Treatment |
| ADAM11-000394 | TRTA | Actual Treatment |
| ADAM11-000396 | TRTAN | Actual Treatment |
| ADAM11-000398 | TRTDURD | Total Treatment |
| ADAM11-000400 | TRTDURM | Total Treatment |
| ADAM11-000402 | TRTDURY | Total Treatment |
| ADAM11-000404 | TRTEDT | Date of Last |
| ADAM11-000406 | TRTEDTF | Date of Last |
| ADAM11-000408 | TRTEDTM | Datetime of Last |
| ADAM11-000410 | TRTETM | Time of Last |
| ADAM11-000412 | TRTETMF | Time of Last |
| ADAM11-000414 | TRTP | Planned Treatment |
| ADAM11-000416 | TRTPN | Planned Treatment |
| ADAM11-000418 | TRTSDT | Date of First |
| ADAM11-000420 | TRTSDTF | Date of First |
| ADAM11-000422 | TRTSDTM | Datetime of First |
| ADAM11-000424 | TRTSEQA | Actual Sequence of |
| ADAM11-000426 | TRTSEQAN | Actual Sequence of |
| ADAM11-000428 | TRTSEQP | Planned Sequence |
| ADAM11-000430 | TRTSEQPN | Planned Sequence |
| ADAM11-000432 | TRTSTM | Time of First |
| ADAM11-000434 | TRTSTMF | Time of First |
| ADAM11-000436 | USUBJID | Unique Subject |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| ADAM11-000103 | AAGE | Num |
| ADAM11-000105 | ABLFL | Char |
| ADAM11-000107 | ABLFN | Num |
| ADAM11-000109 | ACTARM | Char |
| ADAM11-000111 | ADT | Num |
| ADAM11-000113 | ADTF | Char |
| ADAM11-000115 | ADTM | Num |
| ADAM11-000117 | ADY | Num |
| ADAM11-000119 | AENDT | Num |
| ADAM11-000121 | AENDTF | Char |
| ADAM11-000123 | AENDTM | Num |
| ADAM11-000125 | AENDY | Num |
| ADAM11-000127 | AENTM | Num |
| ADAM11-000129 | AENTMF | Char |
| ADAM11-000131 | AGE | Num |
| ADAM11-000133 | AGEU | Char |
| ADAM11-000135 | ANRHI | Num |
| ADAM11-000137 | ANRHIC | Char |
| ADAM11-000139 | ANRIND | Char |
| ADAM11-000141 | ANRLO | Num |
| ADAM11-000143 | ANRLOC | Char |
| ADAM11-000145 | APEREDT | Num |
| ADAM11-000147 | APEREDTF | Char |
| ADAM11-000149 | APEREDTM | Num |
| ADAM11-000151 | APERETM | Num |
| ADAM11-000153 | APERETMF | Char |
| ADAM11-000155 | APERIOD | Num |
| ADAM11-000157 | APERIODC | Char |
| ADAM11-000159 | APERSDT | Num |
| ADAM11-000161 | APERSDTF | Char |
| ADAM11-000163 | APERSDTM | Num |
| ADAM11-000165 | APERSTM | Num |
| ADAM11-000167 | APERSTMF | Char |
| ADAM11-000169 | APHASE | Char |
| ADAM11-000171 | APHASEN | Num |
| ADAM11-000173 | ARELTM | Num |
| ADAM11-000175 | ARELTMU | Char |
| ADAM11-000177 | ARM | Char |
| ADAM11-000179 | ASPER | Num |
| ADAM11-000181 | ASPERC | Char |
| ADAM11-000183 | ASPREDTM | Num |
| ADAM11-000185 | ASPRSDT | Num |
| ADAM11-000187 | ASPRSDTF | Char |
| ADAM11-000189 | ASPRSDTM | Num |
| ADAM11-000191 | ASPRSTM | Num |
| ADAM11-000193 | ASPRSTMF | Char |
| ADAM11-000195 | ASTDT | Num |
| ADAM11-000197 | ASTDTF | Char |
| ADAM11-000199 | ASTDTM | Num |
| ADAM11-000201 | ASTDY | Num |
| ADAM11-000203 | ASTTM | Num |
| ADAM11-000205 | ASTTMF | Char |
| ADAM11-000207 | ATM | Num |
| ADAM11-000209 | ATMF | Char |
| ADAM11-000211 | ATOXGR | Char |
| ADAM11-000213 | ATPT | Char |
| ADAM11-000215 | ATPTN | Num |
| ADAM11-000217 | ATPTREF | Char |
| ADAM11-000219 | AVAL | Num |
| ADAM11-000221 | AVALC | Char |
| ADAM11-000223 | AVISIT | Char |
| ADAM11-000225 | AVISITN | Num |
| ADAM11-000227 | AWHI | Num |
| ADAM11-000229 | AWLO | Num |
| ADAM11-000231 | AWRANGE | Char |
| ADAM11-000233 | AWTARGET | Num |
| ADAM11-000235 | AWTDIFF | Num |
| ADAM11-000237 | AWU | Char |
| ADAM11-000239 | BASE | Num |
| ADAM11-000241 | BASEC | Char |
| ADAM11-000243 | BASETYPE | Char |
| ADAM11-000245 | BNRIND | Char |
| ADAM11-000247 | BTOXGR | Char |
| ADAM11-000249 | CHG | Num |
| ADAM11-000251 | CNSDTDSC | Char |
| ADAM11-000253 | CNSR | Num |
| ADAM11-000255 | COMPLFL | Char |
| ADAM11-000257 | COMPLPFL | Char |
| ADAM11-000259 | COMPLRFL | Char |
| ADAM11-000261 | DCSREAS | Char |
| ADAM11-000263 | DCSREASP | Char |
| ADAM11-000265 | DCTREAS | Char |
| ADAM11-000267 | DOSCUMA | Num |
| ADAM11-000269 | DOSCUMP | Num |
| ADAM11-000271 | DOSEA | Num |
| ADAM11-000273 | DOSEP | Num |
| ADAM11-000275 | DOSEU | Char |
| ADAM11-000277 | DTHCAUS | Char |
| ADAM11-000279 | DTHCAUSN | Num |
| ADAM11-000281 | DTHDT | Num |
| ADAM11-000283 | DTHDTF | Char |
| ADAM11-000285 | DTYPE | Char |
| ADAM11-000287 | ENRLDT | Num |
| ADAM11-000289 | ENRLFL | Char |
| ADAM11-000291 | EOSDT | Num |
| ADAM11-000293 | EOSSTT | Char |
| ADAM11-000295 | EOTSTT | Char |
| ADAM11-000297 | EVNTDESC | Char |
| ADAM11-000299 | FASFL | Char |
| ADAM11-000301 | FASPFL | Char |
| ADAM11-000303 | FASRFL | Char |
| ADAM11-000305 | ITTFL | Char |
| ADAM11-000307 | ITTPFL | Char |
| ADAM11-000309 | ITTRFL | Char |
| ADAM11-000311 | LSTALVDT | Num |
| ADAM11-000313 | LVOTFL | Char |
| ADAM11-000315 | LVOTFN | Num |
| ADAM11-000317 | ONTRTFL | Char |
| ADAM11-000319 | ONTRTFN | Num |
| ADAM11-000321 | PARAM | Char |
| ADAM11-000323 | PARAMCD | Char |
| ADAM11-000325 | PARAMN | Num |
| ADAM11-000327 | PARAMTYP | Char |
| ADAM11-000329 | PCHG | Num |
| ADAM11-000331 | PHEDT | Num |
| ADAM11-000333 | PHEDTF | Char |
| ADAM11-000335 | PHETM | Num |
| ADAM11-000337 | PHETMF | Char |
| ADAM11-000339 | PHSDT | Num |
| ADAM11-000341 | PHSDTF | Char |
| ADAM11-000343 | PHSDTM | Num |
| ADAM11-000345 | PHSTM | Num |
| ADAM11-000347 | PHSTMF | Char |
| ADAM11-000349 | PPROTFL | Char |
| ADAM11-000351 | PPROTPFL | Char |
| ADAM11-000353 | PPROTRFL | Char |
| ADAM11-000355 | R2BASE | Num |
| ADAM11-000357 | RACE | Char |
| ADAM11-000359 | RANDDT | Num |
| ADAM11-000361 | RANDFL | Char |
| ADAM11-000363 | RFICDT | Num |
| ADAM11-000365 | SAFFL | Char |
| ADAM11-000367 | SAFPFL | Char |
| ADAM11-000369 | SAFRFL | Char |
| ADAM11-000371 | SEX | Char |
| ADAM11-000373 | SITEID | Char |
| ADAM11-000375 | SRCDOM | Char |
| ADAM11-000377 | SRCSEQ | Num |
| ADAM11-000379 | SRCVAR | Char |
| ADAM11-000381 | STARTDT | Num |
| ADAM11-000383 | STARTDTF | Char |
| ADAM11-000385 | STARTDTM | Num |
| ADAM11-000387 | STARTTMF | Char |
| ADAM11-000389 | STUDYID | Char |
| ADAM11-000391 | SUBJID | Char |
| ADAM11-000393 | TRCMP | Num |
| ADAM11-000395 | TRTA | Char |
| ADAM11-000397 | TRTAN | Num |
| ADAM11-000399 | TRTDURD | Num |
| ADAM11-000401 | TRTDURM | Num |
| ADAM11-000403 | TRTDURY | Num |
| ADAM11-000405 | TRTEDT | Num |
| ADAM11-000407 | TRTEDTF | Char |
| ADAM11-000409 | TRTEDTM | Num |
| ADAM11-000411 | TRTETM | Num |
| ADAM11-000413 | TRTETMF | Char |
| ADAM11-000415 | TRTP | Char |
| ADAM11-000417 | TRTPN | Num |
| ADAM11-000419 | TRTSDT | Num |
| ADAM11-000421 | TRTSDTF | Char |
| ADAM11-000423 | TRTSDTM | Num |
| ADAM11-000425 | TRTSEQA | Char |
| ADAM11-000427 | TRTSEQAN | Num |
| ADAM11-000429 | TRTSEQP | Char |
| ADAM11-000431 | TRTSEQPN | Num |
| ADAM11-000433 | TRTSTM | Num |
| ADAM11-000435 | TRTSTMF | Char |
| ADAM11-000437 | USUBJID | Char |

---

## Rule Details (Structural Rules)

### ADAM11-000001

**Description:** ADSL dataset is required in a CDISC-based submission.

| Attribute | Value |
|-----------|-------|
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 2.3.1

**Cited Guidance:** ADSL and its related metadata are required in a CDISC-based submission of data from a clinical trial even if no other analysis datasets are submitted.

---

### ADAM11-000002

**Description:** ADSL must contain exactly one record per subject.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 2.3.1

**Cited Guidance:** The ADSL contains 1 record per subject, regardless of the type of clinical trial design.

---

### ADAM11-000003

**Description:** STUDYID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** STUDYID: Req. DM.STUDYID

---

### ADAM11-000004

**Description:** USUBJID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** USUBJID: Req. DM.USUBJID

---

### ADAM11-000005

**Description:** SUBJID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** SUBJID: Req. DM.SUBJID. SUBJID is required in ADSL, but permissible in other datasets.

---

### ADAM11-000006

**Description:** SITEID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** SITEID: Req. DM.SITEID. SITEID is required in ADSL, but permissible in other datasets.

---

### ADAM11-000007

**Description:** AGE is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** AGE: Req. DM.AGE.

---

### ADAM11-000008

**Description:** AGEU is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** AGEU: Req. DM.AGEU.

---

### ADAM11-000009

**Description:** SEX is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** SEX: Req. DM.SEX.

---

### ADAM11-000010

**Description:** RACE is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** RACE: Req. DM.RACE.

---

### ADAM11-000011

**Description:** ARM is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** ARM: Req. DM.ARM.

---

### ADAM11-000012

**Description:** TRT01P is required in ADSL. At least TRT01P is required.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** Subject-level identifier that represents the planned treatment for period xx. At least TRT01P is required.

---

### ADAM11-000013

**Description:** At least one population flag variable is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** A minimum of one subject-level population flag variable is required in ADSL.

---

### ADAM11-000014

**Description:** When SAFFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000015

**Description:** When FASFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000016

**Description:** When ITTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000017

**Description:** When PPROTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000018

**Description:** When COMPLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000019

**Description:** When RANDFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000020

**Description:** When ENRLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM11-000021

**Description:** ADaM dataset names must start with 'AD'.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaM v2.1, Section 4.1.2

**Cited Guidance:** Analysis datasets are named using the convention 'ADxxxxxx.'

---

### ADAM11-000022

**Description:** ADaM dataset names must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.1

**Cited Guidance:** To ensure compliance with SAS Version 5 transport file format, all ADaM variable names must be no more than 8 characters in length.

---

### ADAM11-000023

**Description:** ADaM variable names must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.1

**Cited Guidance:** All ADaM variable names must be no more than 8 characters in length.

---

### ADAM11-000024

**Description:** ADaM variable labels must not exceed 40 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.1

**Cited Guidance:** All ADaM variable labels must be no more than 40 characters in length.

---

### ADAM11-000025

**Description:** ADaM character variables must not exceed 200 characters in length.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.1

**Cited Guidance:** All ADaM character variables must be no more than 200 characters in length.

---

### ADAM11-000026

**Description:** USUBJID is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.1

**Cited Guidance:** USUBJID: Req.

---

### ADAM11-000027

**Description:** PARAM is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAM: Req. Description of analysis parameter.

---

### ADAM11-000028

**Description:** PARAMCD is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAMCD: Req. Short name for the analysis parameter.

---

### ADAM11-000029

**Description:** When PARAMN exists, there must be a one-to-one relationship between PARAMN and PARAM within a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** There must be a one-to-one relationship between PARAMN and PARAM within a dataset.

---

### ADAM11-000030

**Description:** There must be a one-to-one relationship between PARAMCD and PARAM within a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** There must be a one-to-one relationship between PARAMCD and PARAM.

---

### ADAM11-000031

**Description:** PARAMCD must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAMCD has a maximum length of 8 characters.

---

### ADAM11-000032

**Description:** At least one of AVAL or AVALC must be present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** AVAL and AVALC: Cond. At least one is required.

---

### ADAM11-000033

**Description:** DTYPE must be present when a BDS dataset contains derived records.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** DTYPE: Cond. Required when there are derived records.

---

### ADAM11-000034

**Description:** Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.1

**Cited Guidance:** Any variable in an ADaM dataset whose name is the same as an SDTM variable must be a copy of the SDTM variable, and its label, meaning, and values must not be modified.

---

### ADAM11-000035

**Description:** When both *DT and *DTM variables exist, the *DT value must match the date part of the *DTM value.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.2

**Cited Guidance:** If a *DTM and associated *DT variable exist, then the *DT value must match the date part of the *DTM value when the *DTM variable is populated.

---

### ADAM11-000036

**Description:** When both *TM and *DTM variables exist, the *TM value must match the time part of the *DTM value.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.2

**Cited Guidance:** If a *DTM and associated *TM variable exist, then the *TM value must match the time part of the *DTM value when the *DTM variable is populated.

---

### ADAM11-000037

**Description:** Relative day variables ending in DY must not have a value of 0.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.2

**Cited Guidance:** In the ADaM as in the SDTM, there is no Day 0.

---

### ADAM11-000038

**Description:** Date imputation flag (*DTF) must be populated when a date (*DT) is imputed.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.3

**Cited Guidance:** If a date was imputed, *DTF must be populated and is required.

---

### ADAM11-000039

**Description:** Date imputation flag (*DTF) must have values of Y, M, or D when populated.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.3

**Cited Guidance:** *DTF = Y if the year is imputed. *DTF = M if year is present and month is imputed. *DTF = D if only day is imputed.

---

### ADAM11-000040

**Description:** Time imputation flag (*TMF) must have values of H, M, or S when populated.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.3

**Cited Guidance:** *TMF = H if the entire time is imputed. *TMF = M if minutes and seconds are imputed. *TMF = S if only seconds are imputed.

---

### ADAM11-000041

**Description:** Character flag variables (*FL) must have values of Y, N, or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** Variables whose names end in FL are character flag variables with at most two possible non-missing values, Y or N.

---

### ADAM11-000042

**Description:** Numeric flag (*FN) cannot exist without corresponding character flag (*FL).

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.1.4

**Cited Guidance:** If the flag is included in an ADaM dataset, the character version (*FL) is required but the corresponding numeric version (*FN) can also be included.

---

### ADAM11-000043

**Description:** TRT01PN cannot be present unless TRT01P is also present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** TRTxxPN cannot be present unless TRTxxP is also present.

---

### ADAM11-000044

**Description:** TRT01AN cannot be present unless TRT01A is also present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** TRTxxAN cannot be present unless TRTxxA is also present.

---

### ADAM11-000045

**Description:** TRTSDT and/or TRTSDTM are required in ADSL if there is an investigational product.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** TRTSDT and/or TRTSDTM are required if there is an investigational product.

---

### ADAM11-000046

**Description:** TRTEDT and/or TRTEDTM are required in ADSL if there is an investigational product.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** TRTEDT and/or TRTEDTM are required if there is an investigational product.

---

### ADAM11-000047

**Description:** TRTSDTF must be populated when TRTSDT is imputed.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** If TRTSDT was imputed, TRTSDTF must be populated and is required.

---

### ADAM11-000048

**Description:** Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** Date of first exposure to treatment must logically precede or equal date of last exposure.

---

### ADAM11-000049

**Description:** RANDDT is required in randomized trials.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable - Possible Underreporting |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** RANDDT: Cond. Required in randomized trials.

---

### ADAM11-000050

**Description:** AVAL must be numeric type.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** AVAL: Num. Analysis Value.

---

### ADAM11-000051

**Description:** AVALC must be character type.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** AVALC: Char. Analysis Value (C).

---

### ADAM11-000052

**Description:** BASE must be present when CHG is present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** BASE: Cond. Baseline Value. Required when CHG or PCT is present.

---

### ADAM11-000053

**Description:** CHG must equal AVAL minus BASE.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** CHG = AVAL - BASE.

---

### ADAM11-000054

**Description:** CHG should be null for baseline records (where ABLFL = 'Y').

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** CHG is null for baseline records.

---

### ADAM11-000055

**Description:** PCT (Percent Change from Baseline) should be null for baseline records.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PCT is null for baseline records.

---

### ADAM11-000056

**Description:** ABLFL (Analysis Baseline Flag) must be 'Y' or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** ABLFL: Cond. Y or null.

---

### ADAM11-000057

**Description:** There should be at most one record with ABLFL = 'Y' per subject per parameter per baseline type.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** There must be only one record flagged as baseline per subject per parameter per baseline type.

---

### ADAM11-000058

**Description:** When AVISITN exists, there must be a one-to-one relationship between AVISIT and AVISITN within a parameter.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.3

**Cited Guidance:** There must be a one-to-one relationship between AVISITN and AVISIT within a parameter.

---

### ADAM11-000059

**Description:** ADT must be present when ADY is present in a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.3

**Cited Guidance:** ADT: Cond. Required when ADY exists.

---

### ADAM11-000060

**Description:** When ATPTN exists, there must be a one-to-one relationship between ATPT and ATPTN.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.3

**Cited Guidance:** There must be a one-to-one relationship between ATPTN and ATPT within a parameter.

---

### ADAM11-000061

**Description:** CNSR is required in time-to-event BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.6

**Cited Guidance:** CNSR: Req (in TTE). Censor indicator for the event of interest described by PARAM.

---

### ADAM11-000062

**Description:** CNSR values should be 0 (event) or a positive integer (censored).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.6

**Cited Guidance:** CNSR = 0 for subjects who have experienced the event of interest. Values greater than 0 are used for censored subjects.

---

### ADAM11-000063

**Description:** When ANRHIy exists, ANRLOy must also exist.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.7

**Cited Guidance:** ANRLOy and ANRHIy define the analysis reference range.

---

### ADAM11-000064

**Description:** ANLzzFL (Analysis Record Flag) must be 'Y' or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.8

**Cited Guidance:** ANLzzFL: Y or null. Record-level flag for analysis.

---

### ADAM11-000065

**Description:** USUBJID values in BDS datasets must have a corresponding record in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 2.3.1

**Cited Guidance:** Within a given study, USUBJID is the key variable that links the ADSL to other datasets.

---

### ADAM11-000066

**Description:** A variable present in both ADSL and another ADaM dataset must have the same values, type, and label.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 2.3.1

**Cited Guidance:** A variable that is present in both ADSL and any other ADaM dataset must have the same values, type, and label.

---

### ADAM11-000067

**Description:** TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.2

**Cited Guidance:** TRTP: Cond. Description of Planned Treatment. Required if the dataset is used for analysis by treatment.

---

### ADAM11-000068

**Description:** TRTP must be present when TRTA is present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.2

**Cited Guidance:** TRTP: Required when TRTA is present.

---

### ADAM11-000069

**Description:** TRTPN cannot be present without TRTP.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.2

**Cited Guidance:** TRTPN cannot be present unless TRTP is also present.

---

### ADAM11-000070

**Description:** The ADSL dataset class must be 'SUBJECT LEVEL ANALYSIS DATASET'.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 2.3.1

**Cited Guidance:** In a study, there is only 1 dataset in the class 'SUBJECT LEVEL ANALYSIS DATASET', and its name is ADSL.

---

### ADAM11-000071

**Description:** Non-ADaM analysis dataset names should not start with 'AD' prefix.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 1.6

**Cited Guidance:** To prevent confusion, non-ADaM analysis dataset names should not start with the prefix AD. It is good practice to start the names of non-ADaM analysis datasets with the two-letter prefix 'AX'.

---

### ADAM11-000072

**Description:** When SRCVAR exists, SRCDOM must also exist for datapoint traceability.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.9

**Cited Guidance:** SRCDOM and SRCVAR are used together for datapoint traceability.

---

### ADAM11-000073

**Description:** When SRCSEQ exists, SRCDOM must also exist.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.9

**Cited Guidance:** SRCSEQ: Cond. Required when SRCDOM exists.

---

### ADAM11-000074

**Description:** DTYPE values should be from the ADaM controlled terminology.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.5

**Cited Guidance:** DTYPE: Derivation Type. Values from controlled terminology.

---

### ADAM11-000075

**Description:** BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.5

**Cited Guidance:** BASETYPE: Cond. Required when there is more than one definition of baseline.

---

### ADAM11-000076

**Description:** EOSSTT values should be from SBJTSTAT controlled terminology.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** EOSSTT: The subject's status as of the end of study or data cutoff. Examples: COMPLETED, DISCONTINUED, ONGOING.

---

### ADAM11-000077

**Description:** DCSREAS should be null when EOSSTT is 'COMPLETED'.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** Reason for subject's discontinuation from study. Null for subjects who completed the study.

---

### ADAM11-000078

**Description:** ADaM datasets must be accompanied by metadata.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaM v2.1, Section 3.1

**Cited Guidance:** Analysis datasets must be accompanied by metadata.

---

### ADAM11-000079

**Description:** BASE must be a numeric variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** BASE: Num. Baseline Value.

---

### ADAM11-000080

**Description:** CHG must be a numeric variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** CHG: Num. Change from Baseline.

---

### ADAM11-000081

**Description:** AAGE is required if analysis age differs from DM.AGE.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** AAGE: Cond. Age used for analysis that may be derived differently than DM.AGE. AAGE is required if age is calculated differently than DM.AGE.

---

### ADAM11-000082

**Description:** STUDYID must not be null in any ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** STUDYID: Req.

---

### ADAM11-000083

**Description:** USUBJID must not be null in any ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** USUBJID: Req.

---

### ADAM11-000084

**Description:** PARAMCD must contain only letters, underscores, and numerals.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAMCD must start with a letter and contain only letters, underscores, and numerals.

---

### ADAM11-000085

**Description:** PARAM must not be null in BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAM: Req. Description of analysis parameter.

---

### ADAM11-000086

**Description:** PARAMCD must not be null in BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAMCD: Req. Short name for the analysis parameter.

---

### ADAM11-000087

**Description:** CRITyFL cannot exist without corresponding CRITy.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4.3

**Cited Guidance:** CRITy: Cond. Required when CRITyFL exists.

---

### ADAM11-000088

**Description:** CRITyFL must have values of Y, N, or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4.3

**Cited Guidance:** CRITyFL: Y/N/null or Y/null.

---

### ADAM11-000089

**Description:** TRTP and TRTPN must have a one-to-one relationship within a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.2

**Cited Guidance:** There must be a one-to-one relationship between TRTPN and TRTP within a study.

---

### ADAM11-000090

**Description:** AGEU should use controlled terminology values.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.1, Section 3.2

**Cited Guidance:** AGEU: Req. Age units from controlled terminology (AGEU).

---

### ADAM11-000091

**Description:** Collected Duration (--DUR) value should not be negative.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB040

**Cited Guidance:** Values for the following should not be negative: age, dose, and duration of event, exposure or observation.

---

### ADAM11-000092

**Description:** Text variable in submitted dataset should not contain  '.' as an entire value.

| Attribute | Value |
|-----------|-------|
| Rule Type | Value Check with Variable Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB015

**Cited Guidance:** Character values should not have leading spaces or only have a period character.

---

### ADAM11-000093

**Description:** Text variable in submitted dataset should not contain leading spaces ' '.

| Attribute | Value |
|-----------|-------|
| Rule Type | Value Check with Variable Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB015

**Cited Guidance:** Character values should not have leading spaces or only have a period character.

---

### ADAM11-000094

**Description:** Part A: Raise an error when a Required variable is not present in the dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.2, Section 4.1.1.5

**Cited Guidance:** Required variables must always be included in the dataset and cannot be null for any record.

---

### ADAM11-000095

**Description:** Raise an error when an expected variable is not present in the dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** SDTM v1.4, Section 4.1.5

**Cited Guidance:** When the study does not include the data item for an expected variable, however, a null column must still be included in the dataset, and a comment must be included in the Define-XML document to st...

---

### ADAM11-000096

**Description:** The submitted dataset is larger than 5 GB

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB024

**Cited Guidance:** Large datasets should be split into smaller datasets no larger than 5 GB in size.

---

### ADAM11-000097

**Description:** Raise an error when variables are not in the specified order

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.2, Section 4.1.1.4

**Cited Guidance:** Variables for the three general observation classes must be ordered with Identifiers first; followed by the Topic; Qualifier; and Timing variables. Within each role; variables must be ordered as sh...

---

### ADAM11-000098

**Description:** Raise an error when a variable is not an allowed variable for an Observation Class

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Underreporting |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.4, Section 2.5

**Cited Guidance:** Sponsors may not add any variables other than those described in the preceding three bullets. . . . Standard variables must not be renamed or modified for novel usage.

---

### ADAM11-000099

**Description:** Raise an error when a dataset has no records.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.4, Section 3.2

**Cited Guidance:** In the event that no records are present in a dataset (e.g., a small PK study where no subjects took concomitant medications), the empty dataset should not be submitted and should not be described ...

---

### ADAM11-000100

**Description:** Raise an error when a variable label is not in title case

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.4, Section 2.6

**Cited Guidance:** Use title case for all labels (title case means to capitalize the first letter of every word except for articles, prepositions, and conjunctions).

---

### ADAM11-000101

**Description:** Part B: Raise an error when a Required variable is null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Value Check with Dataset Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.2, Section 4.1.1.5

**Cited Guidance:** Required variables must always be included in the dataset and cannot be null for any record.

---

### ADAM11-000438

**Description:** When PARAMTYP exists, its value must be 'DERIVED' or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.1, Section 3.3.4

**Cited Guidance:** PARAMTYP: Perm. Indicates whether the parameter is derived. Values: DERIVED or null.

---
