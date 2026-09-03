# ADaMIG 1.3 Conformance Rules Documentation

Auto-generated documentation from `rules/rules-adamig-1-3.json`.
Rules derived from ADaM v2.1 and ADaMIG v1.3 specifications.

**Total Rules:** 471  
**Standard:** ADaMIG v1.3  
**Model:** ADaM v2.1  
**Source:** CDISC Analysis Data Model specifications  

## Summary Statistics

### By Rule Type

| Rule Type | Count |
|-----------|-------|
| Dataset Metadata Check | 7 |
| Domain Presence Check | 1 |
| Record Data | 45 |
| Value Check with Dataset Metadata | 1 |
| Value Check with Variable Metadata | 2 |
| Variable Metadata Check | 415 |

### By Executability

| Executability | Count |
|--------------|-------|
| Fully Executable | 454 |
| Partially Executable | 15 |
| Partially Executable - Possible Underreporting | 2 |

### By Category

| Category | Count | ID Range |
|----------|-------|----------|
| Structural / Data Quality | 101 | ADAM-000001 to ADAM-000090, ADAM-000461 to ADAM-000471 |
| Variable Label Conformance | 185 | ADAM-000091 to ADAM-000275 |
| Variable Type Conformance | 185 | ADAM-000276 to ADAM-000460 |

## Source Documents

| Document | Description |
|----------|-------------|
| ADaMIG v1.3 | Analysis Data Model Implementation Guide, Version 1.3 (Final, 2021-11-29) |
| ADaM v2.1 | Analysis Data Model, Version 2.1 (Final, 2009-12-17) |
| SDTMIG v3.4 | Generic data quality rules also applicable to ADaM (ADAM-000461 to ADAM-000471) |

---

## Structural and Data Quality Rules

| Core ID | Description | Rule Type | Sensitivity | Executability |
|---------|-------------|-----------|-------------|---------------|
| [ADAM-000001](#adam-000001) | ADSL dataset is required in a CDISC-based submission. | Domain Presence Check | Dataset | Fully Executable |
| [ADAM-000002](#adam-000002) | ADSL must contain exactly one record per subject. | Record Data | Record | Fully Executable |
| [ADAM-000003](#adam-000003) | STUDYID is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000004](#adam-000004) | USUBJID is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000005](#adam-000005) | SUBJID is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000006](#adam-000006) | SITEID is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000007](#adam-000007) | AGE is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000008](#adam-000008) | AGEU is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000009](#adam-000009) | SEX is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000010](#adam-000010) | RACE is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000011](#adam-000011) | ARM is required in ADSL. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000012](#adam-000012) | TRT01P is required in ADSL. At least TRT01P is required. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000013](#adam-000013) | At least one population flag variable is required in ADSL. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000014](#adam-000014) | When SAFFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000015](#adam-000015) | When FASFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000016](#adam-000016) | When ITTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000017](#adam-000017) | When PPROTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000018](#adam-000018) | When COMPLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000019](#adam-000019) | When RANDFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000020](#adam-000020) | When ENRLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data | Record | Fully Executable |
| [ADAM-000021](#adam-000021) | ADaM dataset names must start with 'AD'. | Dataset Metadata Check | Dataset | Fully Executable |
| [ADAM-000022](#adam-000022) | ADaM dataset names must not exceed 8 characters. | Dataset Metadata Check | Dataset | Fully Executable |
| [ADAM-000023](#adam-000023) | ADaM variable names must not exceed 8 characters. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000024](#adam-000024) | ADaM variable labels must not exceed 40 characters. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000025](#adam-000025) | ADaM character variables must not exceed 200 characters in length. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000026](#adam-000026) | USUBJID is required in all BDS datasets. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000027](#adam-000027) | PARAM is required in all BDS datasets. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000028](#adam-000028) | PARAMCD is required in all BDS datasets. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000029](#adam-000029) | When PARAMN exists, there must be a one-to-one relationship between PARAMN and PARAM within a dataset. | Record Data | Record | Fully Executable |
| [ADAM-000030](#adam-000030) | There must be a one-to-one relationship between PARAMCD and PARAM within a dataset. | Record Data | Record | Fully Executable |
| [ADAM-000031](#adam-000031) | PARAMCD must not exceed 8 characters. | Record Data | Record | Fully Executable |
| [ADAM-000032](#adam-000032) | At least one of AVAL or AVALC must be present in a BDS dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000033](#adam-000033) | DTYPE must be present when a BDS dataset contains derived records. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000034](#adam-000034) | Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values). | Record Data | Record | Partially Executable |
| [ADAM-000035](#adam-000035) | When both *DT and *DTM variables exist, the *DT value must match the date part of the *DTM value. | Record Data | Record | Fully Executable |
| [ADAM-000036](#adam-000036) | When both *TM and *DTM variables exist, the *TM value must match the time part of the *DTM value. | Record Data | Record | Fully Executable |
| [ADAM-000037](#adam-000037) | Relative day variables ending in DY must not have a value of 0. | Record Data | Record | Fully Executable |
| [ADAM-000038](#adam-000038) | Date imputation flag (*DTF) must be populated when a date (*DT) is imputed. | Record Data | Record | Partially Executable |
| [ADAM-000039](#adam-000039) | Date imputation flag (*DTF) must have values of Y, M, or D when populated. | Record Data | Record | Fully Executable |
| [ADAM-000040](#adam-000040) | Time imputation flag (*TMF) must have values of H, M, or S when populated. | Record Data | Record | Fully Executable |
| [ADAM-000041](#adam-000041) | Character flag variables (*FL) must have values of Y, N, or null. | Record Data | Record | Fully Executable |
| [ADAM-000042](#adam-000042) | Numeric flag (*FN) cannot exist without corresponding character flag (*FL). | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000043](#adam-000043) | TRT01PN cannot be present unless TRT01P is also present. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000044](#adam-000044) | TRT01AN cannot be present unless TRT01A is also present. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000045](#adam-000045) | TRTSDT and/or TRTSDTM are required in ADSL if there is an investigational product. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000046](#adam-000046) | TRTEDT and/or TRTEDTM are required in ADSL if there is an investigational product. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000047](#adam-000047) | TRTSDTF must be populated when TRTSDT is imputed. | Record Data | Record | Partially Executable |
| [ADAM-000048](#adam-000048) | Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT). | Record Data | Record | Fully Executable |
| [ADAM-000049](#adam-000049) | RANDDT is required in randomized trials. | Variable Metadata Check | Dataset | Partially Executable - Possible Underreporting |
| [ADAM-000050](#adam-000050) | AVAL must be numeric type. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000051](#adam-000051) | AVALC must be character type. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000052](#adam-000052) | BASE must be present when CHG is present in a BDS dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000053](#adam-000053) | CHG must equal AVAL minus BASE. | Record Data | Record | Fully Executable |
| [ADAM-000054](#adam-000054) | CHG should be null for baseline records (where ABLFL = 'Y'). | Record Data | Record | Fully Executable |
| [ADAM-000055](#adam-000055) | PCT (Percent Change from Baseline) should be null for baseline records. | Record Data | Record | Fully Executable |
| [ADAM-000056](#adam-000056) | ABLFL (Analysis Baseline Flag) must be 'Y' or null. | Record Data | Record | Fully Executable |
| [ADAM-000057](#adam-000057) | There should be at most one record with ABLFL = 'Y' per subject per parameter per baseline type. | Record Data | Record | Fully Executable |
| [ADAM-000058](#adam-000058) | When AVISITN exists, there must be a one-to-one relationship between AVISIT and AVISITN within a parameter. | Record Data | Record | Fully Executable |
| [ADAM-000059](#adam-000059) | ADT must be present when ADY is present in a dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000060](#adam-000060) | When ATPTN exists, there must be a one-to-one relationship between ATPT and ATPTN. | Record Data | Record | Fully Executable |
| [ADAM-000061](#adam-000061) | CNSR is required in time-to-event BDS datasets. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000062](#adam-000062) | CNSR values should be 0 (event) or a positive integer (censored). | Record Data | Record | Fully Executable |
| [ADAM-000063](#adam-000063) | When ANRHIy exists, ANRLOy must also exist. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000064](#adam-000064) | ANLzzFL (Analysis Record Flag) must be 'Y' or null. | Record Data | Record | Fully Executable |
| [ADAM-000065](#adam-000065) | USUBJID values in BDS datasets must have a corresponding record in ADSL. | Record Data | Record | Fully Executable |
| [ADAM-000066](#adam-000066) | A variable present in both ADSL and another ADaM dataset must have the same values, type, and label. | Record Data | Record | Partially Executable |
| [ADAM-000067](#adam-000067) | TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000068](#adam-000068) | TRTP must be present when TRTA is present in a BDS dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000069](#adam-000069) | TRTPN cannot be present without TRTP. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000070](#adam-000070) | The ADSL dataset class must be 'SUBJECT LEVEL ANALYSIS DATASET'. | Dataset Metadata Check | Dataset | Fully Executable |
| [ADAM-000071](#adam-000071) | Non-ADaM analysis dataset names should not start with 'AD' prefix. | Dataset Metadata Check | Dataset | Partially Executable |
| [ADAM-000072](#adam-000072) | When SRCVAR exists, SRCDOM must also exist for datapoint traceability. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000073](#adam-000073) | When SRCSEQ exists, SRCDOM must also exist. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000074](#adam-000074) | DTYPE values should be from the ADaM controlled terminology. | Record Data | Record | Partially Executable |
| [ADAM-000075](#adam-000075) | BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset. | Variable Metadata Check | Dataset | Partially Executable |
| [ADAM-000076](#adam-000076) | EOSSTT values should be from SBJTSTAT controlled terminology. | Record Data | Record | Fully Executable |
| [ADAM-000077](#adam-000077) | DCSREAS should be null when EOSSTT is 'COMPLETED'. | Record Data | Record | Fully Executable |
| [ADAM-000078](#adam-000078) | ADaM datasets must be accompanied by metadata. | Dataset Metadata Check | Dataset | Partially Executable |
| [ADAM-000079](#adam-000079) | BASE must be a numeric variable. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000080](#adam-000080) | CHG must be a numeric variable. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000081](#adam-000081) | AAGE is required if analysis age differs from DM.AGE. | Record Data | Record | Partially Executable |
| [ADAM-000082](#adam-000082) | STUDYID must not be null in any ADaM dataset. | Record Data | Record | Fully Executable |
| [ADAM-000083](#adam-000083) | USUBJID must not be null in any ADaM dataset. | Record Data | Record | Fully Executable |
| [ADAM-000084](#adam-000084) | PARAMCD must contain only letters, underscores, and numerals. | Record Data | Record | Fully Executable |
| [ADAM-000085](#adam-000085) | PARAM must not be null in BDS datasets. | Record Data | Record | Fully Executable |
| [ADAM-000086](#adam-000086) | PARAMCD must not be null in BDS datasets. | Record Data | Record | Fully Executable |
| [ADAM-000087](#adam-000087) | CRITyFL cannot exist without corresponding CRITy. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000088](#adam-000088) | CRITyFL must have values of Y, N, or null. | Record Data | Record | Fully Executable |
| [ADAM-000089](#adam-000089) | TRTP and TRTPN must have a one-to-one relationship within a dataset. | Record Data | Record | Fully Executable |
| [ADAM-000090](#adam-000090) | AGEU should use controlled terminology values. | Record Data | Record | Fully Executable |
| [ADAM-000461](#adam-000461) | Collected Duration (--DUR) value should not be negative. | Record Data | Record | Fully Executable |
| [ADAM-000462](#adam-000462) | Text variable in submitted dataset should not contain  '.' as an entire value. | Value Check with Variable Metadata | Record | Fully Executable |
| [ADAM-000463](#adam-000463) | Text variable in submitted dataset should not contain leading spaces ' '. | Value Check with Variable Metadata | Record | Fully Executable |
| [ADAM-000464](#adam-000464) | Part A: Raise an error when a Required variable is not present in the dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000465](#adam-000465) | Raise an error when an expected variable is not present in the dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000466](#adam-000466) | The submitted dataset is larger than 5 GB | Dataset Metadata Check | Dataset | Fully Executable |
| [ADAM-000467](#adam-000467) | Raise an error when variables are not in the specified order | Variable Metadata Check | Dataset | Fully Executable |
| [ADAM-000468](#adam-000468) | Raise an error when a variable is not an allowed variable for an Observation Class | Variable Metadata Check | Record | Partially Executable - Possible Underreporting |
| [ADAM-000469](#adam-000469) | Raise an error when a dataset has no records. | Dataset Metadata Check | Dataset | Fully Executable |
| [ADAM-000470](#adam-000470) | Raise an error when a variable label is not in title case | Variable Metadata Check | Record | Fully Executable |
| [ADAM-000471](#adam-000471) | Part B: Raise an error when a Required variable is null. | Value Check with Dataset Metadata | Record | Fully Executable |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| ADAM-000091 | AAGE | Analysis Age |
| ADAM-000092 | ABLFL | Baseline Record Flag |
| ADAM-000093 | ABLFN | Baseline Record Flag (N) |
| ADAM-000094 | ACTARM | Description of Actual Arm |
| ADAM-000095 | ADT | Analysis Date |
| ADAM-000096 | ADTF | Analysis Date Imputation |
| ADAM-000097 | ADTM | Analysis Datetime |
| ADAM-000098 | ADY | Analysis Relative Day |
| ADAM-000099 | AENDT | Analysis End Date |
| ADAM-000100 | AENDTM | Analysis End Datetime |
| ADAM-000101 | AENDY | Analysis End Relative Day |
| ADAM-000102 | AENTM | Analysis End Time |
| ADAM-000103 | AENTMF | Analysis End Time |
| ADAM-000104 | AGE | Age |
| ADAM-000105 | AGEU | Age Units |
| ADAM-000106 | ANRIND | Analysis Reference Range |
| ADAM-000107 | ANRLO | Analysis Normal Range Lower |
| ADAM-000108 | ANRLOC | Analysis Normal Range Lower |
| ADAM-000109 | APEREDT | Period End Date |
| ADAM-000110 | APEREDTF | Period End Date Imput. |
| ADAM-000111 | APEREDTM | Period End Datetime |
| ADAM-000112 | APERETM | Period End Time |
| ADAM-000113 | APERETMF | Period End Time Imput. |
| ADAM-000114 | APERIOD | Period |
| ADAM-000115 | APERIODC | Period (C) |
| ADAM-000116 | APERSDT | Period Start Date |
| ADAM-000117 | APERSDTF | Period Start Date Imput. |
| ADAM-000118 | APERSDTM | Period Start Datetime |
| ADAM-000119 | APERSTM | Period Start Time |
| ADAM-000120 | APERSTMF | Period Start Time Imput. |
| ADAM-000121 | APHASE | Phase |
| ADAM-000122 | APHASEN | Phase (N) |
| ADAM-000123 | ARELTM | Analysis Relative Time |
| ADAM-000124 | ARELTMU | Analysis Relative Time Unit |
| ADAM-000125 | ARM | Description of Planned Arm |
| ADAM-000126 | ASEQ | Analysis Sequence Number |
| ADAM-000127 | ASPER | Subperiod within Period |
| ADAM-000128 | ASPERC | Subperiod within Period (C) |
| ADAM-000129 | ASPREDT | Subperiod End Date |
| ADAM-000130 | ASPREDTF | Subperiod End Date Imput. |
| ADAM-000131 | ASPREDTM | Subperiod End Datetime |
| ADAM-000132 | ASPRETM | Subperiod End Time |
| ADAM-000133 | ASPRETMF | Subperiod End Time Imput. |
| ADAM-000134 | ASPRSDT | Subperiod Start Date |
| ADAM-000135 | ASPRSDTF | Subperiod Start Date Imput. |
| ADAM-000136 | ASPRSDTM | Subperiod Start Datetime |
| ADAM-000137 | ASPRSTM | Subperiod Start Time |
| ADAM-000138 | ASTDT | Analysis Start Date |
| ADAM-000139 | ASTDTF | Analysis Start Date |
| ADAM-000140 | ASTDTM | Analysis Start Datetime |
| ADAM-000141 | ASTDY | Analysis Start Relative Day |
| ADAM-000142 | ASTTM | Analysis Start Time |
| ADAM-000143 | ASTTMF | Analysis Start Time |
| ADAM-000144 | ATM | Analysis Time |
| ADAM-000145 | ATMF | Analysis Time Imputation |
| ADAM-000146 | ATOXDSCH | Analysis Toxicity Description |
| ADAM-000147 | ATOXDSCL | Analysis Toxicity Description |
| ADAM-000148 | ATOXGR | Analysis Toxicity Grade |
| ADAM-000149 | ATOXGRH | Analysis Toxicity Grade High |
| ADAM-000150 | ATOXGRHN | Analysis Toxicity Grade High |
| ADAM-000151 | ATOXGRL | Analysis Toxicity Grade Low |
| ADAM-000152 | ATOXGRLN | Analysis Toxicity Grade Low |
| ADAM-000153 | ATOXGRN | Analysis Toxicity Grade (N) |
| ADAM-000154 | ATPT | Analysis Timepoint |
| ADAM-000155 | ATPTN | Analysis Timepoint (N) |
| ADAM-000156 | ATPTREF | Analysis Timepoint |
| ADAM-000157 | AVAL | Analysis Value |
| ADAM-000158 | AVALC | Analysis Value (C) |
| ADAM-000159 | AVISIT | Analysis Visit |
| ADAM-000160 | AVISITN | Analysis Visit (N) |
| ADAM-000161 | AWHI | Analysis Window Ending |
| ADAM-000162 | AWLO | Analysis Window Beginning |
| ADAM-000163 | AWRANGE | Analysis Window Valid |
| ADAM-000164 | AWTARGET | Analysis Window Target |
| ADAM-000165 | AWTDIFF | Analysis Window Diff from |
| ADAM-000166 | AWU | Analysis Window Unit |
| ADAM-000167 | BASE | Baseline Value |
| ADAM-000168 | BASEC | Baseline Value (C) |
| ADAM-000169 | BASETYPE | Baseline Type |
| ADAM-000170 | BCHG | Change to Baseline |
| ADAM-000171 | BNRIND | Baseline Reference Range |
| ADAM-000172 | BTOXGR | Baseline Toxicity Grade |
| ADAM-000173 | BTOXGRH | Baseline Toxicity Grade High |
| ADAM-000174 | BTOXGRHN | Baseline Toxicity Grade High |
| ADAM-000175 | BTOXGRL | Baseline Toxicity Grade Low |
| ADAM-000176 | BTOXGRLN | Baseline Toxicity Grade Low |
| ADAM-000177 | BTOXGRN | Baseline Toxicity Grade (N) |
| ADAM-000178 | CHG | Change from Baseline |
| ADAM-000179 | CNSDTDSC | Censor Date Description |
| ADAM-000180 | CNSR | Censor |
| ADAM-000181 | COMPLFL | Completers Population Flag |
| ADAM-000182 | COMPLPFL | Completers Parameter-Level |
| ADAM-000183 | COMPLRFL | Completers Record-Level |
| ADAM-000184 | DCSREAS | Reason for Discontinuation |
| ADAM-000185 | DCSREASP | Reason Spec for Discont |
| ADAM-000186 | DCTREASP | Reason Specify for Discont of |
| ADAM-000187 | DOSCUMA | Cumulative Actual |
| ADAM-000188 | DOSCUMP | Cumulative Planned |
| ADAM-000189 | DOSEA | Actual Treatment Dose |
| ADAM-000190 | DOSEP | Planned Treatment Dose |
| ADAM-000191 | DOSEU | Treatment Dose Units |
| ADAM-000192 | DTHCAUS | Cause of Death |
| ADAM-000193 | DTHCAUSN | Cause of Death (N) |
| ADAM-000194 | DTHDT | Date of Death |
| ADAM-000195 | DTHDTF | Date of Death Imputation |
| ADAM-000196 | DTYPE | Derivation Type |
| ADAM-000197 | ENRLDT | Date of Enrollment |
| ADAM-000198 | ENRLFL | Enrolled Population Flag |
| ADAM-000199 | EOSDT | End of Study Date |
| ADAM-000200 | EOSSTT | End of Study Status |
| ADAM-000201 | EOTSTT | End of Treatment Status |
| ADAM-000202 | EVNTDESC | Event or Censoring |
| ADAM-000203 | FASFL | Full Analysis Set Population |
| ADAM-000204 | FASPFL | Full Analysis Set Parameter- |
| ADAM-000205 | FASRFL | Full Analysis Set Record- |
| ADAM-000206 | ITTFL | Intent-To-Treat Population |
| ADAM-000207 | ITTPFL | Intent-To-Treat Parameter- |
| ADAM-000208 | ITTRFL | Intent-To-Treat Record-Level |
| ADAM-000209 | LSTALVDT | Date Last Known Alive |
| ADAM-000210 | LVOTFL | Last Value On Treatment |
| ADAM-000211 | LVOTFN | Last Value On Treatment |
| ADAM-000212 | ONTRTFL | On Treatment Record Flag |
| ADAM-000213 | PARAM | Parameter |
| ADAM-000214 | PARAMCD | Parameter Code |
| ADAM-000215 | PARAMN | Parameter (N) |
| ADAM-000216 | PBCHG | Percent Change to |
| ADAM-000217 | PCHG | Percent Change from |
| ADAM-000218 | PHEDT | Phase End Date |
| ADAM-000219 | PHEDTF | Phase End Date Imput. Flag |
| ADAM-000220 | PHEDTM | Phase End Datetime |
| ADAM-000221 | PHETM | Phase End Time |
| ADAM-000222 | PHETMF | Phase End Time Imput. |
| ADAM-000223 | PHSDT | Phase Start Date |
| ADAM-000224 | PHSDTF | Phase Start Date Imput. |
| ADAM-000225 | PHSDTM | Phase Start Datetime |
| ADAM-000226 | PHSTM | Phase Start Time |
| ADAM-000227 | PHSTMF | Phase Start Time Imput. |
| ADAM-000228 | PPROTFL | Per-Protocol Population Flag |
| ADAM-000229 | PPROTPFL | Per-Protocol Parameter- |
| ADAM-000230 | PPROTRFL | Per-Protocol Record-Level |
| ADAM-000231 | R2BASE | Ratio to Baseline |
| ADAM-000232 | RACE | Race |
| ADAM-000233 | RANDDT | Date of Randomization |
| ADAM-000234 | RANDFL | Randomized Population Flag |
| ADAM-000235 | RFICDT | Date of Informed Consent |
| ADAM-000236 | SAFFL | Safety Population Flag |
| ADAM-000237 | SAFPFL | Safety Analysis Parameter- |
| ADAM-000238 | SAFRFL | Safety Analysis Record- |
| ADAM-000239 | SEX | Sex |
| ADAM-000240 | SITEID | Study Site Identifier |
| ADAM-000241 | SRCDOM | Source Data |
| ADAM-000242 | SRCSEQ | Source Sequence Number |
| ADAM-000243 | SRCVAR | Source Variable |
| ADAM-000244 | STARTDT | Time-to-Event Origin Date for |
| ADAM-000245 | STARTDTF | Origin Date Imputation Flag |
| ADAM-000246 | STARTDTM | Time-to-Event Origin |
| ADAM-000247 | STARTTMF | Origin Time Imputation Flag |
| ADAM-000248 | STRATAR | Strata Used for |
| ADAM-000249 | STRATARN | Strata Used for |
| ADAM-000250 | STRATAV | Strata from Verification |
| ADAM-000251 | STRATAVN | Strata from Verification |
| ADAM-000252 | STUDYID | Study Identifier |
| ADAM-000253 | SUBJID | Subject Identifier for the |
| ADAM-000254 | TRTA | Actual Treatment |
| ADAM-000255 | TRTAN | Actual Treatment (N) |
| ADAM-000256 | TRTDURD | Total Treatment Duration |
| ADAM-000257 | TRTDURM | Total Treatment Duration |
| ADAM-000258 | TRTDURY | Total Treatment Duration |
| ADAM-000259 | TRTEDT | Date of Last Exposure to |
| ADAM-000260 | TRTEDTF | Date of Last Exposure Imput. |
| ADAM-000261 | TRTEDTM | Datetime of Last Exposure to |
| ADAM-000262 | TRTETM | Time of Last Exposure to |
| ADAM-000263 | TRTETMF | Time of Last Exposure Imput. |
| ADAM-000264 | TRTP | Planned Treatment |
| ADAM-000265 | TRTPN | Planned Treatment (N) |
| ADAM-000266 | TRTSDT | Date of First Exposure to |
| ADAM-000267 | TRTSDTF | Date of First Exposure Imput. |
| ADAM-000268 | TRTSDTM | Datetime of First Exposure to |
| ADAM-000269 | TRTSEQA | Actual Sequence of |
| ADAM-000270 | TRTSEQAN | Actual Sequence of |
| ADAM-000271 | TRTSEQP | Planned Sequence of |
| ADAM-000272 | TRTSEQPN | Planned Sequence of |
| ADAM-000273 | TRTSTM | Time of First Exposure to |
| ADAM-000274 | TRTSTMF | Time of First Exposure Imput. |
| ADAM-000275 | USUBJID | Unique Subject Identifier |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| ADAM-000276 | AAGE | Num |
| ADAM-000277 | ABLFL | Char |
| ADAM-000278 | ABLFN | Num |
| ADAM-000279 | ACTARM | Char |
| ADAM-000280 | ADT | Num |
| ADAM-000281 | ADTF | Char |
| ADAM-000282 | ADTM | Num |
| ADAM-000283 | ADY | Num |
| ADAM-000284 | AENDT | Num |
| ADAM-000285 | AENDTM | Num |
| ADAM-000286 | AENDY | Num |
| ADAM-000287 | AENTM | Num |
| ADAM-000288 | AENTMF | Char |
| ADAM-000289 | AGE | Num |
| ADAM-000290 | AGEU | Char |
| ADAM-000291 | ANRIND | Char |
| ADAM-000292 | ANRLO | Num |
| ADAM-000293 | ANRLOC | Char |
| ADAM-000294 | APEREDT | Num |
| ADAM-000295 | APEREDTF | Char |
| ADAM-000296 | APEREDTM | Num |
| ADAM-000297 | APERETM | Num |
| ADAM-000298 | APERETMF | Char |
| ADAM-000299 | APERIOD | Num |
| ADAM-000300 | APERIODC | Char |
| ADAM-000301 | APERSDT | Num |
| ADAM-000302 | APERSDTF | Char |
| ADAM-000303 | APERSDTM | Num |
| ADAM-000304 | APERSTM | Num |
| ADAM-000305 | APERSTMF | Char |
| ADAM-000306 | APHASE | Char |
| ADAM-000307 | APHASEN | Num |
| ADAM-000308 | ARELTM | Num |
| ADAM-000309 | ARELTMU | Char |
| ADAM-000310 | ARM | Char |
| ADAM-000311 | ASEQ | Num |
| ADAM-000312 | ASPER | Num |
| ADAM-000313 | ASPERC | Char |
| ADAM-000314 | ASPREDT | Num |
| ADAM-000315 | ASPREDTF | Char |
| ADAM-000316 | ASPREDTM | Num |
| ADAM-000317 | ASPRETM | Num |
| ADAM-000318 | ASPRETMF | Char |
| ADAM-000319 | ASPRSDT | Num |
| ADAM-000320 | ASPRSDTF | Char |
| ADAM-000321 | ASPRSDTM | Num |
| ADAM-000322 | ASPRSTM | Num |
| ADAM-000323 | ASTDT | Num |
| ADAM-000324 | ASTDTF | Char |
| ADAM-000325 | ASTDTM | Num |
| ADAM-000326 | ASTDY | Num |
| ADAM-000327 | ASTTM | Num |
| ADAM-000328 | ASTTMF | Char |
| ADAM-000329 | ATM | Num |
| ADAM-000330 | ATMF | Char |
| ADAM-000331 | ATOXDSCH | Char |
| ADAM-000332 | ATOXDSCL | Char |
| ADAM-000333 | ATOXGR | Char |
| ADAM-000334 | ATOXGRH | Char |
| ADAM-000335 | ATOXGRHN | Num |
| ADAM-000336 | ATOXGRL | Char |
| ADAM-000337 | ATOXGRLN | Num |
| ADAM-000338 | ATOXGRN | Num |
| ADAM-000339 | ATPT | Char |
| ADAM-000340 | ATPTN | Num |
| ADAM-000341 | ATPTREF | Char |
| ADAM-000342 | AVAL | Num |
| ADAM-000343 | AVALC | Char |
| ADAM-000344 | AVISIT | Char |
| ADAM-000345 | AVISITN | Num |
| ADAM-000346 | AWHI | Num |
| ADAM-000347 | AWLO | Num |
| ADAM-000348 | AWRANGE | Char |
| ADAM-000349 | AWTARGET | Num |
| ADAM-000350 | AWTDIFF | Num |
| ADAM-000351 | AWU | Char |
| ADAM-000352 | BASE | Num |
| ADAM-000353 | BASEC | Char |
| ADAM-000354 | BASETYPE | Char |
| ADAM-000355 | BCHG | Num |
| ADAM-000356 | BNRIND | Char |
| ADAM-000357 | BTOXGR | Char |
| ADAM-000358 | BTOXGRH | Char |
| ADAM-000359 | BTOXGRHN | Num |
| ADAM-000360 | BTOXGRL | Char |
| ADAM-000361 | BTOXGRLN | Num |
| ADAM-000362 | BTOXGRN | Num |
| ADAM-000363 | CHG | Num |
| ADAM-000364 | CNSDTDSC | Char |
| ADAM-000365 | CNSR | Num |
| ADAM-000366 | COMPLFL | Char |
| ADAM-000367 | COMPLPFL | Char |
| ADAM-000368 | COMPLRFL | Char |
| ADAM-000369 | DCSREAS | Char |
| ADAM-000370 | DCSREASP | Char |
| ADAM-000371 | DCTREASP | Char |
| ADAM-000372 | DOSCUMA | Num |
| ADAM-000373 | DOSCUMP | Num |
| ADAM-000374 | DOSEA | Num |
| ADAM-000375 | DOSEP | Num |
| ADAM-000376 | DOSEU | Char |
| ADAM-000377 | DTHCAUS | Char |
| ADAM-000378 | DTHCAUSN | Num |
| ADAM-000379 | DTHDT | Num |
| ADAM-000380 | DTHDTF | Char |
| ADAM-000381 | DTYPE | Char |
| ADAM-000382 | ENRLDT | Num |
| ADAM-000383 | ENRLFL | Char |
| ADAM-000384 | EOSDT | Num |
| ADAM-000385 | EOSSTT | Char |
| ADAM-000386 | EOTSTT | Char |
| ADAM-000387 | EVNTDESC | Char |
| ADAM-000388 | FASFL | Char |
| ADAM-000389 | FASPFL | Char |
| ADAM-000390 | FASRFL | Char |
| ADAM-000391 | ITTFL | Char |
| ADAM-000392 | ITTPFL | Char |
| ADAM-000393 | ITTRFL | Char |
| ADAM-000394 | LSTALVDT | Num |
| ADAM-000395 | LVOTFL | Char |
| ADAM-000396 | LVOTFN | Num |
| ADAM-000397 | ONTRTFL | Char |
| ADAM-000398 | PARAM | Char |
| ADAM-000399 | PARAMCD | Char |
| ADAM-000400 | PARAMN | Num |
| ADAM-000401 | PBCHG | Num |
| ADAM-000402 | PCHG | Num |
| ADAM-000403 | PHEDT | Num |
| ADAM-000404 | PHEDTF | Char |
| ADAM-000405 | PHEDTM | Num |
| ADAM-000406 | PHETM | Num |
| ADAM-000407 | PHETMF | Char |
| ADAM-000408 | PHSDT | Num |
| ADAM-000409 | PHSDTF | Char |
| ADAM-000410 | PHSDTM | Num |
| ADAM-000411 | PHSTM | Num |
| ADAM-000412 | PHSTMF | Char |
| ADAM-000413 | PPROTFL | Char |
| ADAM-000414 | PPROTPFL | Char |
| ADAM-000415 | PPROTRFL | Char |
| ADAM-000416 | R2BASE | Num |
| ADAM-000417 | RACE | Char |
| ADAM-000418 | RANDDT | Num |
| ADAM-000419 | RANDFL | Char |
| ADAM-000420 | RFICDT | Num |
| ADAM-000421 | SAFFL | Char |
| ADAM-000422 | SAFPFL | Char |
| ADAM-000423 | SAFRFL | Char |
| ADAM-000424 | SEX | Char |
| ADAM-000425 | SITEID | Char |
| ADAM-000426 | SRCDOM | Char |
| ADAM-000427 | SRCSEQ | Num |
| ADAM-000428 | SRCVAR | Char |
| ADAM-000429 | STARTDT | Num |
| ADAM-000430 | STARTDTF | Char |
| ADAM-000431 | STARTDTM | Num |
| ADAM-000432 | STARTTMF | Char |
| ADAM-000433 | STRATAR | Char |
| ADAM-000434 | STRATARN | Num |
| ADAM-000435 | STRATAV | Char |
| ADAM-000436 | STRATAVN | Num |
| ADAM-000437 | STUDYID | Char |
| ADAM-000438 | SUBJID | Char |
| ADAM-000439 | TRTA | Char |
| ADAM-000440 | TRTAN | Num |
| ADAM-000441 | TRTDURD | Num |
| ADAM-000442 | TRTDURM | Num |
| ADAM-000443 | TRTDURY | Num |
| ADAM-000444 | TRTEDT | Num |
| ADAM-000445 | TRTEDTF | Char |
| ADAM-000446 | TRTEDTM | Num |
| ADAM-000447 | TRTETM | Num |
| ADAM-000448 | TRTETMF | Char |
| ADAM-000449 | TRTP | Char |
| ADAM-000450 | TRTPN | Num |
| ADAM-000451 | TRTSDT | Num |
| ADAM-000452 | TRTSDTF | Char |
| ADAM-000453 | TRTSDTM | Num |
| ADAM-000454 | TRTSEQA | Char |
| ADAM-000455 | TRTSEQAN | Num |
| ADAM-000456 | TRTSEQP | Char |
| ADAM-000457 | TRTSEQPN | Num |
| ADAM-000458 | TRTSTM | Num |
| ADAM-000459 | TRTSTMF | Char |
| ADAM-000460 | USUBJID | Char |

---

## Rule Details (Structural Rules)

### ADAM-000001

**Description:** ADSL dataset is required in a CDISC-based submission.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000001 |
| Version | 1 |
| Status | Draft |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 2.3.1

**Cited Guidance:** ADSL and its related metadata are required in a CDISC-based submission of data from a clinical trial even if no other analysis datasets are submitted.

---

### ADAM-000002

**Description:** ADSL must contain exactly one record per subject.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000002 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 2.3.1

**Cited Guidance:** The ADSL contains 1 record per subject, regardless of the type of clinical trial design.

---

### ADAM-000003

**Description:** STUDYID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000003 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** STUDYID: Req. DM.STUDYID

---

### ADAM-000004

**Description:** USUBJID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000004 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** USUBJID: Req. DM.USUBJID

---

### ADAM-000005

**Description:** SUBJID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000005 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** SUBJID: Req. DM.SUBJID. SUBJID is required in ADSL, but permissible in other datasets.

---

### ADAM-000006

**Description:** SITEID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000006 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** SITEID: Req. DM.SITEID. SITEID is required in ADSL, but permissible in other datasets.

---

### ADAM-000007

**Description:** AGE is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000007 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** AGE: Req. DM.AGE.

---

### ADAM-000008

**Description:** AGEU is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000008 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** AGEU: Req. DM.AGEU.

---

### ADAM-000009

**Description:** SEX is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000009 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** SEX: Req. DM.SEX.

---

### ADAM-000010

**Description:** RACE is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000010 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** RACE: Req. DM.RACE.

---

### ADAM-000011

**Description:** ARM is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000011 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** ARM: Req. DM.ARM.

---

### ADAM-000012

**Description:** TRT01P is required in ADSL. At least TRT01P is required.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000012 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** Subject-level identifier that represents the planned treatment for period xx. At least TRT01P is required.

---

### ADAM-000013

**Description:** At least one population flag variable is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000013 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** A minimum of one subject-level population flag variable is required in ADSL.

---

### ADAM-000014

**Description:** When SAFFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000014 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000015

**Description:** When FASFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000015 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000016

**Description:** When ITTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000016 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000017

**Description:** When PPROTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000017 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000018

**Description:** When COMPLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000018 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000019

**Description:** When RANDFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000019 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000020

**Description:** When ENRLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000020 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM-000021

**Description:** ADaM dataset names must start with 'AD'.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000021 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaM v2.1, Section 4.1.2

**Cited Guidance:** Analysis datasets are named using the convention 'ADxxxxxx.'

---

### ADAM-000022

**Description:** ADaM dataset names must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000022 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.1

**Cited Guidance:** To ensure compliance with SAS Version 5 transport file format, all ADaM variable names must be no more than 8 characters in length.

---

### ADAM-000023

**Description:** ADaM variable names must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000023 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.1

**Cited Guidance:** All ADaM variable names must be no more than 8 characters in length.

---

### ADAM-000024

**Description:** ADaM variable labels must not exceed 40 characters.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000024 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.1

**Cited Guidance:** All ADaM variable labels must be no more than 40 characters in length.

---

### ADAM-000025

**Description:** ADaM character variables must not exceed 200 characters in length.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000025 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.1

**Cited Guidance:** All ADaM character variables must be no more than 200 characters in length.

---

### ADAM-000026

**Description:** USUBJID is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000026 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.1

**Cited Guidance:** USUBJID: Req.

---

### ADAM-000027

**Description:** PARAM is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000027 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PARAM: Req. Description of analysis parameter.

---

### ADAM-000028

**Description:** PARAMCD is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000028 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PARAMCD: Req. Short name for the analysis parameter.

---

### ADAM-000029

**Description:** When PARAMN exists, there must be a one-to-one relationship between PARAMN and PARAM within a dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000029 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** There must be a one-to-one relationship between PARAMN and PARAM within a dataset.

---

### ADAM-000030

**Description:** There must be a one-to-one relationship between PARAMCD and PARAM within a dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000030 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** There must be a one-to-one relationship between PARAMCD and PARAM.

---

### ADAM-000031

**Description:** PARAMCD must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000031 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PARAMCD has a maximum length of 8 characters.

---

### ADAM-000032

**Description:** At least one of AVAL or AVALC must be present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000032 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** AVAL and AVALC: Cond. At least one is required.

---

### ADAM-000033

**Description:** DTYPE must be present when a BDS dataset contains derived records.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000033 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** DTYPE: Cond. Required when there are derived records.

---

### ADAM-000034

**Description:** Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000034 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.1

**Cited Guidance:** Any variable in an ADaM dataset whose name is the same as an SDTM variable must be a copy of the SDTM variable, and its label, meaning, and values must not be modified.

---

### ADAM-000035

**Description:** When both *DT and *DTM variables exist, the *DT value must match the date part of the *DTM value.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000035 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.2

**Cited Guidance:** If a *DTM and associated *DT variable exist, then the *DT value must match the date part of the *DTM value when the *DTM variable is populated.

---

### ADAM-000036

**Description:** When both *TM and *DTM variables exist, the *TM value must match the time part of the *DTM value.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000036 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.2

**Cited Guidance:** If a *DTM and associated *TM variable exist, then the *TM value must match the time part of the *DTM value when the *DTM variable is populated.

---

### ADAM-000037

**Description:** Relative day variables ending in DY must not have a value of 0.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000037 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.2

**Cited Guidance:** In the ADaM as in the SDTM, there is no Day 0.

---

### ADAM-000038

**Description:** Date imputation flag (*DTF) must be populated when a date (*DT) is imputed.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000038 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.3

**Cited Guidance:** If a date was imputed, *DTF must be populated and is required.

---

### ADAM-000039

**Description:** Date imputation flag (*DTF) must have values of Y, M, or D when populated.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000039 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.3

**Cited Guidance:** *DTF = Y if the year is imputed. *DTF = M if year is present and month is imputed. *DTF = D if only day is imputed.

---

### ADAM-000040

**Description:** Time imputation flag (*TMF) must have values of H, M, or S when populated.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000040 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.3

**Cited Guidance:** *TMF = H if the entire time is imputed. *TMF = M if minutes and seconds are imputed. *TMF = S if only seconds are imputed.

---

### ADAM-000041

**Description:** Character flag variables (*FL) must have values of Y, N, or null.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000041 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** Variables whose names end in FL are character flag variables with at most two possible non-missing values, Y or N.

---

### ADAM-000042

**Description:** Numeric flag (*FN) cannot exist without corresponding character flag (*FL).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000042 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.1.4

**Cited Guidance:** If the flag is included in an ADaM dataset, the character version (*FL) is required but the corresponding numeric version (*FN) can also be included.

---

### ADAM-000043

**Description:** TRT01PN cannot be present unless TRT01P is also present.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000043 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** TRTxxPN cannot be present unless TRTxxP is also present.

---

### ADAM-000044

**Description:** TRT01AN cannot be present unless TRT01A is also present.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000044 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** TRTxxAN cannot be present unless TRTxxA is also present.

---

### ADAM-000045

**Description:** TRTSDT and/or TRTSDTM are required in ADSL if there is an investigational product.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000045 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** TRTSDT and/or TRTSDTM are required if there is an investigational product.

---

### ADAM-000046

**Description:** TRTEDT and/or TRTEDTM are required in ADSL if there is an investigational product.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000046 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** TRTEDT and/or TRTEDTM are required if there is an investigational product.

---

### ADAM-000047

**Description:** TRTSDTF must be populated when TRTSDT is imputed.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000047 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** If TRTSDT was imputed, TRTSDTF must be populated and is required.

---

### ADAM-000048

**Description:** Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000048 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** Date of first exposure to treatment must logically precede or equal date of last exposure.

---

### ADAM-000049

**Description:** RANDDT is required in randomized trials.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000049 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable - Possible Underreporting |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** RANDDT: Cond. Required in randomized trials.

---

### ADAM-000050

**Description:** AVAL must be numeric type.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000050 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** AVAL: Num. Analysis Value.

---

### ADAM-000051

**Description:** AVALC must be character type.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000051 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** AVALC: Char. Analysis Value (C).

---

### ADAM-000052

**Description:** BASE must be present when CHG is present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000052 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** BASE: Cond. Baseline Value. Required when CHG or PCT is present.

---

### ADAM-000053

**Description:** CHG must equal AVAL minus BASE.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000053 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** CHG = AVAL - BASE.

---

### ADAM-000054

**Description:** CHG should be null for baseline records (where ABLFL = 'Y').

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000054 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** CHG is null for baseline records.

---

### ADAM-000055

**Description:** PCT (Percent Change from Baseline) should be null for baseline records.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000055 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PCT is null for baseline records.

---

### ADAM-000056

**Description:** ABLFL (Analysis Baseline Flag) must be 'Y' or null.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000056 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** ABLFL: Cond. Y or null.

---

### ADAM-000057

**Description:** There should be at most one record with ABLFL = 'Y' per subject per parameter per baseline type.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000057 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** There must be only one record flagged as baseline per subject per parameter per baseline type.

---

### ADAM-000058

**Description:** When AVISITN exists, there must be a one-to-one relationship between AVISIT and AVISITN within a parameter.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000058 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.3

**Cited Guidance:** There must be a one-to-one relationship between AVISITN and AVISIT within a parameter.

---

### ADAM-000059

**Description:** ADT must be present when ADY is present in a dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000059 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.3

**Cited Guidance:** ADT: Cond. Required when ADY exists.

---

### ADAM-000060

**Description:** When ATPTN exists, there must be a one-to-one relationship between ATPT and ATPTN.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000060 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.3

**Cited Guidance:** There must be a one-to-one relationship between ATPTN and ATPT within a parameter.

---

### ADAM-000061

**Description:** CNSR is required in time-to-event BDS datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000061 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.6

**Cited Guidance:** CNSR: Req (in TTE). Censor indicator for the event of interest described by PARAM.

---

### ADAM-000062

**Description:** CNSR values should be 0 (event) or a positive integer (censored).

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000062 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.6

**Cited Guidance:** CNSR = 0 for subjects who have experienced the event of interest. Values greater than 0 are used for censored subjects.

---

### ADAM-000063

**Description:** When ANRHIy exists, ANRLOy must also exist.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000063 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.7

**Cited Guidance:** ANRLOy and ANRHIy define the analysis reference range.

---

### ADAM-000064

**Description:** ANLzzFL (Analysis Record Flag) must be 'Y' or null.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000064 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.8

**Cited Guidance:** ANLzzFL: Y or null. Record-level flag for analysis.

---

### ADAM-000065

**Description:** USUBJID values in BDS datasets must have a corresponding record in ADSL.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000065 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 2.3.1

**Cited Guidance:** Within a given study, USUBJID is the key variable that links the ADSL to other datasets.

---

### ADAM-000066

**Description:** A variable present in both ADSL and another ADaM dataset must have the same values, type, and label.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000066 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 2.3.1

**Cited Guidance:** A variable that is present in both ADSL and any other ADaM dataset must have the same values, type, and label.

---

### ADAM-000067

**Description:** TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000067 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.2

**Cited Guidance:** TRTP: Cond. Description of Planned Treatment. Required if the dataset is used for analysis by treatment.

---

### ADAM-000068

**Description:** TRTP must be present when TRTA is present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000068 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.2

**Cited Guidance:** TRTP: Required when TRTA is present.

---

### ADAM-000069

**Description:** TRTPN cannot be present without TRTP.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000069 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.2

**Cited Guidance:** TRTPN cannot be present unless TRTP is also present.

---

### ADAM-000070

**Description:** The ADSL dataset class must be 'SUBJECT LEVEL ANALYSIS DATASET'.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000070 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 2.3.1

**Cited Guidance:** In a study, there is only 1 dataset in the class 'SUBJECT LEVEL ANALYSIS DATASET', and its name is ADSL.

---

### ADAM-000071

**Description:** Non-ADaM analysis dataset names should not start with 'AD' prefix.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000071 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 1.6

**Cited Guidance:** To prevent confusion, non-ADaM analysis dataset names should not start with the prefix AD. It is good practice to start the names of non-ADaM analysis datasets with the two-letter prefix 'AX'.

---

### ADAM-000072

**Description:** When SRCVAR exists, SRCDOM must also exist for datapoint traceability.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000072 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.9

**Cited Guidance:** SRCDOM and SRCVAR are used together for datapoint traceability.

---

### ADAM-000073

**Description:** When SRCSEQ exists, SRCDOM must also exist.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000073 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.9

**Cited Guidance:** SRCSEQ: Cond. Required when SRCDOM exists.

---

### ADAM-000074

**Description:** DTYPE values should be from the ADaM controlled terminology.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000074 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.5

**Cited Guidance:** DTYPE: Derivation Type. Values from controlled terminology.

---

### ADAM-000075

**Description:** BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000075 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.5

**Cited Guidance:** BASETYPE: Cond. Required when there is more than one definition of baseline.

---

### ADAM-000076

**Description:** EOSSTT values should be from SBJTSTAT controlled terminology.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000076 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** EOSSTT: The subject's status as of the end of study or data cutoff. Examples: COMPLETED, DISCONTINUED, ONGOING.

---

### ADAM-000077

**Description:** DCSREAS should be null when EOSSTT is 'COMPLETED'.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000077 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** Reason for subject's discontinuation from study. Null for subjects who completed the study.

---

### ADAM-000078

**Description:** ADaM datasets must be accompanied by metadata.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000078 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaM v2.1, Section 3.1

**Cited Guidance:** Analysis datasets must be accompanied by metadata.

---

### ADAM-000079

**Description:** BASE must be a numeric variable.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000079 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** BASE: Num. Baseline Value.

---

### ADAM-000080

**Description:** CHG must be a numeric variable.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000080 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** CHG: Num. Change from Baseline.

---

### ADAM-000081

**Description:** AAGE is required if analysis age differs from DM.AGE.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000081 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** AAGE: Cond. Age used for analysis that may be derived differently than DM.AGE. AAGE is required if age is calculated differently than DM.AGE.

---

### ADAM-000082

**Description:** STUDYID must not be null in any ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000082 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** STUDYID: Req.

---

### ADAM-000083

**Description:** USUBJID must not be null in any ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000083 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** USUBJID: Req.

---

### ADAM-000084

**Description:** PARAMCD must contain only letters, underscores, and numerals.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000084 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PARAMCD must start with a letter and contain only letters, underscores, and numerals.

---

### ADAM-000085

**Description:** PARAM must not be null in BDS datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000085 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PARAM: Req. Description of analysis parameter.

---

### ADAM-000086

**Description:** PARAMCD must not be null in BDS datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000086 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4

**Cited Guidance:** PARAMCD: Req. Short name for the analysis parameter.

---

### ADAM-000087

**Description:** CRITyFL cannot exist without corresponding CRITy.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000087 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4.3

**Cited Guidance:** CRITy: Cond. Required when CRITyFL exists.

---

### ADAM-000088

**Description:** CRITyFL must have values of Y, N, or null.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000088 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.4.3

**Cited Guidance:** CRITyFL: Y/N/null or Y/null.

---

### ADAM-000089

**Description:** TRTP and TRTPN must have a one-to-one relationship within a dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000089 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.3, Section 3.3.2

**Cited Guidance:** There must be a one-to-one relationship between TRTPN and TRTP within a study.

---

### ADAM-000090

**Description:** AGEU should use controlled terminology values.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000090 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.3, Section 3.2

**Cited Guidance:** AGEU: Req. Age units from controlled terminology (AGEU).

---

### ADAM-000461

**Description:** Collected Duration (--DUR) value should not be negative.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000461 |
| Version | 1 |
| Status | Draft |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB040

**Cited Guidance:** Values for the following should not be negative: age, dose, and duration of event, exposure or observation.

**Rule Identifiers:** FB4005, FB4005, FB4005, FB4005, FB4005, FB4005, FB4005, FB4005, FB4005, FB4005, CORE-000305

---

### ADAM-000462

**Description:** Text variable in submitted dataset should not contain  '.' as an entire value.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000462 |
| Version | 1 |
| Status | Draft |
| Rule Type | Value Check with Variable Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB015

**Cited Guidance:** Character values should not have leading spaces or only have a period character.

**Rule Identifiers:** FB1502, FB1502, FB1502, FB1502, FB1502, FB1502, FB1502, FB1502, FB1502, FB1502, CORE-000890

---

### ADAM-000463

**Description:** Text variable in submitted dataset should not contain leading spaces ' '.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000463 |
| Version | 1 |
| Status | Draft |
| Rule Type | Value Check with Variable Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB015

**Cited Guidance:** Character values should not have leading spaces or only have a period character.

**Rule Identifiers:** FB1501, FB1501, FB1501, FB1501, FB1501, FB1501, FB1501, FB1501, FB1501, FB1501, CORE-000867

---

### ADAM-000464

**Description:** Part A: Raise an error when a Required variable is not present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000464 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.2, Section 4.1.1.5

**Cited Guidance:** Required variables must always be included in the dataset and cannot be null for any record.

**Rule Identifiers:** CG0014, CG0014, CG0014, SEND12, SEND12, SEND12, SEND12, SEND12, SEND12, TIG0299, TIG0057, CORE-000355

---

### ADAM-000465

**Description:** Raise an error when an expected variable is not present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000465 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** SDTM v1.4, Section 4.1.5

**Cited Guidance:** When the study does not include the data item for an expected variable, however, a null column must still be included in the dataset, and a comment must be included in the Define-XML document to st...

**Rule Identifiers:** CG0016, CG0016, CG0016, TIG0301, SEND13, SEND13, SEND13, SEND13, SEND13, SEND13, TIG0065, CORE-000334

---

### ADAM-000466

**Description:** The submitted dataset is larger than 5 GB

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000466 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** FDA, Section FDAB024

**Cited Guidance:** Large datasets should be split into smaller datasets no larger than 5 GB in size.

**Rule Identifiers:** FB2401, FB2401, FB2401, FB2401, FB2401, FB2401, FB2401, FB2401, FB2401, FB2401, CORE-000765

---

### ADAM-000467

**Description:** Raise an error when variables are not in the specified order

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000467 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.2, Section 4.1.1.4

**Cited Guidance:** Variables for the three general observation classes must be ordered with Identifiers first; followed by the Topic; Qualifier; and Timing variables. Within each role; variables must be ordered as sh...

**Rule Identifiers:** CG0330, CG0330, CG0664, TIG0698, SEND48, SEND48, SEND48, SEND48, SEND48, SEND48, CORE-000852

---

### ADAM-000468

**Description:** Raise an error when a variable is not an allowed variable for an Observation Class

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000468 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Underreporting |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.4, Section 2.5

**Cited Guidance:** Sponsors may not add any variables other than those described in the preceding three bullets. . . . Standard variables must not be renamed or modified for novel usage.

**Rule Identifiers:** CG0013, CG0013, TIG0298, CG0351, CG0351, CORE-000550

---

### ADAM-000469

**Description:** Raise an error when a dataset has no records.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000469 |
| Version | 1 |
| Status | Draft |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.4, Section 3.2

**Cited Guidance:** In the event that no records are present in a dataset (e.g., a small PK study where no subjects took concomitant medications), the empty dataset should not be submitted and should not be described ...

**Rule Identifiers:** CG0408, CG0408, CG0408, CORE-000579

---

### ADAM-000470

**Description:** Raise an error when a variable label is not in title case

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000470 |
| Version | 1 |
| Status | Draft |
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.4, Section 2.6

**Cited Guidance:** Use title case for all labels (title case means to capitalize the first letter of every word except for articles, prepositions, and conjunctions).

**Rule Identifiers:** CG0359, CG0359, CG0359, SEND29, SEND29, SEND29, SEND29, SEND29, SEND29, TIG0524, TIG0205, CORE-000594

---

### ADAM-000471

**Description:** Part B: Raise an error when a Required variable is null.

| Attribute | Value |
|-----------|-------|
| Core ID | ADAM-000471 |
| Version | 1 |
| Status | Draft |
| Rule Type | Value Check with Dataset Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Reference:** IG v3.2, Section 4.1.1.5

**Cited Guidance:** Required variables must always be included in the dataset and cannot be null for any record.

**Rule Identifiers:** CG0014, CG0014, CG0014, SEND12, SEND12, SEND12, SEND12, SEND12, SEND12, TIG0299, TIG0057, CORE-000356

---
