# ADaMIG 1.0 Conformance Rules Documentation

Auto-generated documentation from `rules/rules-adamig-1-0.json`.
Rules derived from ADaM v2.1 and ADaMIG v1.0 specifications.

**Total Rules:** 328  
**Standard:** ADaMIG v1.0  
**Model:** ADaM v2.1  

## Key Differences from Later Versions

| Aspect | v1.0 | v1.1 | v1.3 |
|--------|------|------|------|
| Total variables | 160 | 247 | 288 |
| ANRLO/ANRHI type | Char | Num | Num |
| AyLO/AyHI type | Char | Num | Num |
| Population flag naming | COMPxFL/COMPxFN | COMPLxFL | COMPLxFL |
| PARAMTYP | Present | Present | Removed |
| Stratification vars | Not present | Not present | Added |
| Criterion vars (CRITy) | Not present | Added | Present |

## Summary Statistics

| Rule Type | Count |
|-----------|-------|
| Dataset Metadata Check | 7 |
| Domain Presence Check | 1 |
| Record Data | 43 |
| Value Check with Dataset Metadata | 1 |
| Value Check with Variable Metadata | 2 |
| Variable Metadata Check | 274 |

### By Category

| Category | Count |
|----------|-------|
| Structural / Data Quality | 98 |
| Variable Label Conformance | 115 |
| Variable Type Conformance | 115 |

---

## Structural and Data Quality Rules

| Core ID | Description | Rule Type |
|---------|-------------|-----------|
| [ADAM10-000001](#adam10-000001) | ADSL dataset is required in a CDISC-based submission. | Domain Presence Check |
| [ADAM10-000002](#adam10-000002) | ADSL must contain exactly one record per subject. | Record Data |
| [ADAM10-000003](#adam10-000003) | STUDYID is required in ADSL. | Variable Metadata Check |
| [ADAM10-000004](#adam10-000004) | USUBJID is required in ADSL. | Variable Metadata Check |
| [ADAM10-000005](#adam10-000005) | SUBJID is required in ADSL. | Variable Metadata Check |
| [ADAM10-000006](#adam10-000006) | SITEID is required in ADSL. | Variable Metadata Check |
| [ADAM10-000007](#adam10-000007) | AGE is required in ADSL. | Variable Metadata Check |
| [ADAM10-000008](#adam10-000008) | AGEU is required in ADSL. | Variable Metadata Check |
| [ADAM10-000009](#adam10-000009) | SEX is required in ADSL. | Variable Metadata Check |
| [ADAM10-000010](#adam10-000010) | RACE is required in ADSL. | Variable Metadata Check |
| [ADAM10-000011](#adam10-000011) | ARM is required in ADSL. | Variable Metadata Check |
| [ADAM10-000012](#adam10-000012) | TRT01P is required in ADSL. At least TRT01P is required. | Variable Metadata Check |
| [ADAM10-000013](#adam10-000013) | At least one population flag variable is required in ADSL. | Variable Metadata Check |
| [ADAM10-000014](#adam10-000014) | When SAFFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000015](#adam10-000015) | When FASFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000016](#adam10-000016) | When ITTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000017](#adam10-000017) | When PPROTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000018](#adam10-000018) | When COMPLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000019](#adam10-000019) | When RANDFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000020](#adam10-000020) | When ENRLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags). | Record Data |
| [ADAM10-000021](#adam10-000021) | ADaM dataset names must start with 'AD'. | Dataset Metadata Check |
| [ADAM10-000022](#adam10-000022) | ADaM dataset names must not exceed 8 characters. | Dataset Metadata Check |
| [ADAM10-000023](#adam10-000023) | ADaM variable names must not exceed 8 characters. | Variable Metadata Check |
| [ADAM10-000024](#adam10-000024) | ADaM variable labels must not exceed 40 characters. | Variable Metadata Check |
| [ADAM10-000025](#adam10-000025) | ADaM character variables must not exceed 200 characters in length. | Variable Metadata Check |
| [ADAM10-000026](#adam10-000026) | USUBJID is required in all BDS datasets. | Variable Metadata Check |
| [ADAM10-000027](#adam10-000027) | PARAM is required in all BDS datasets. | Variable Metadata Check |
| [ADAM10-000028](#adam10-000028) | PARAMCD is required in all BDS datasets. | Variable Metadata Check |
| [ADAM10-000029](#adam10-000029) | When PARAMN exists, there must be a one-to-one relationship between PARAMN and PARAM within a dataset. | Record Data |
| [ADAM10-000030](#adam10-000030) | There must be a one-to-one relationship between PARAMCD and PARAM within a dataset. | Record Data |
| [ADAM10-000031](#adam10-000031) | PARAMCD must not exceed 8 characters. | Record Data |
| [ADAM10-000032](#adam10-000032) | At least one of AVAL or AVALC must be present in a BDS dataset. | Variable Metadata Check |
| [ADAM10-000033](#adam10-000033) | DTYPE must be present when a BDS dataset contains derived records. | Variable Metadata Check |
| [ADAM10-000034](#adam10-000034) | Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values). | Record Data |
| [ADAM10-000035](#adam10-000035) | When both *DT and *DTM variables exist, the *DT value must match the date part of the *DTM value. | Record Data |
| [ADAM10-000036](#adam10-000036) | When both *TM and *DTM variables exist, the *TM value must match the time part of the *DTM value. | Record Data |
| [ADAM10-000037](#adam10-000037) | Relative day variables ending in DY must not have a value of 0. | Record Data |
| [ADAM10-000038](#adam10-000038) | Date imputation flag (*DTF) must be populated when a date (*DT) is imputed. | Record Data |
| [ADAM10-000039](#adam10-000039) | Date imputation flag (*DTF) must have values of Y, M, or D when populated. | Record Data |
| [ADAM10-000040](#adam10-000040) | Time imputation flag (*TMF) must have values of H, M, or S when populated. | Record Data |
| [ADAM10-000041](#adam10-000041) | Character flag variables (*FL) must have values of Y, N, or null. | Record Data |
| [ADAM10-000042](#adam10-000042) | Numeric flag (*FN) cannot exist without corresponding character flag (*FL). | Variable Metadata Check |
| [ADAM10-000043](#adam10-000043) | TRT01PN cannot be present unless TRT01P is also present. | Variable Metadata Check |
| [ADAM10-000044](#adam10-000044) | TRT01AN cannot be present unless TRT01A is also present. | Variable Metadata Check |
| [ADAM10-000045](#adam10-000045) | TRTSDT and/or TRTSDTM are required in ADSL if there is an investigational product. | Variable Metadata Check |
| [ADAM10-000046](#adam10-000046) | TRTEDT and/or TRTEDTM are required in ADSL if there is an investigational product. | Variable Metadata Check |
| [ADAM10-000047](#adam10-000047) | TRTSDTF must be populated when TRTSDT is imputed. | Record Data |
| [ADAM10-000048](#adam10-000048) | Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT). | Record Data |
| [ADAM10-000049](#adam10-000049) | RANDDT is required in randomized trials. | Variable Metadata Check |
| [ADAM10-000050](#adam10-000050) | AVAL must be numeric type. | Variable Metadata Check |
| [ADAM10-000051](#adam10-000051) | AVALC must be character type. | Variable Metadata Check |
| [ADAM10-000052](#adam10-000052) | BASE must be present when CHG is present in a BDS dataset. | Variable Metadata Check |
| [ADAM10-000053](#adam10-000053) | CHG must equal AVAL minus BASE. | Record Data |
| [ADAM10-000054](#adam10-000054) | CHG should be null for baseline records (where ABLFL = 'Y'). | Record Data |
| [ADAM10-000055](#adam10-000055) | PCT (Percent Change from Baseline) should be null for baseline records. | Record Data |
| [ADAM10-000056](#adam10-000056) | ABLFL (Analysis Baseline Flag) must be 'Y' or null. | Record Data |
| [ADAM10-000057](#adam10-000057) | There should be at most one record with ABLFL = 'Y' per subject per parameter per baseline type. | Record Data |
| [ADAM10-000058](#adam10-000058) | When AVISITN exists, there must be a one-to-one relationship between AVISIT and AVISITN within a parameter. | Record Data |
| [ADAM10-000059](#adam10-000059) | When ATPTN exists, there must be a one-to-one relationship between ATPT and ATPTN. | Record Data |
| [ADAM10-000060](#adam10-000060) | CNSR is required in time-to-event BDS datasets. | Variable Metadata Check |
| [ADAM10-000061](#adam10-000061) | CNSR values should be 0 (event) or a positive integer (censored). | Record Data |
| [ADAM10-000062](#adam10-000062) | When ANRHIy exists, ANRLOy must also exist. | Variable Metadata Check |
| [ADAM10-000063](#adam10-000063) | ANLzzFL (Analysis Record Flag) must be 'Y' or null. | Record Data |
| [ADAM10-000064](#adam10-000064) | USUBJID values in BDS datasets must have a corresponding record in ADSL. | Record Data |
| [ADAM10-000065](#adam10-000065) | A variable present in both ADSL and another ADaM dataset must have the same values, type, and label. | Record Data |
| [ADAM10-000066](#adam10-000066) | TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment. | Variable Metadata Check |
| [ADAM10-000067](#adam10-000067) | TRTP must be present when TRTA is present in a BDS dataset. | Variable Metadata Check |
| [ADAM10-000068](#adam10-000068) | TRTPN cannot be present without TRTP. | Variable Metadata Check |
| [ADAM10-000069](#adam10-000069) | The ADSL dataset class must be 'SUBJECT LEVEL ANALYSIS DATASET'. | Dataset Metadata Check |
| [ADAM10-000070](#adam10-000070) | Non-ADaM analysis dataset names should not start with 'AD' prefix. | Dataset Metadata Check |
| [ADAM10-000071](#adam10-000071) | When SRCVAR exists, SRCDOM must also exist for datapoint traceability. | Variable Metadata Check |
| [ADAM10-000072](#adam10-000072) | When SRCSEQ exists, SRCDOM must also exist. | Variable Metadata Check |
| [ADAM10-000073](#adam10-000073) | DTYPE values should be from the ADaM controlled terminology. | Record Data |
| [ADAM10-000074](#adam10-000074) | BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset. | Variable Metadata Check |
| [ADAM10-000075](#adam10-000075) | ADaM datasets must be accompanied by metadata. | Dataset Metadata Check |
| [ADAM10-000076](#adam10-000076) | BASE must be a numeric variable. | Variable Metadata Check |
| [ADAM10-000077](#adam10-000077) | CHG must be a numeric variable. | Variable Metadata Check |
| [ADAM10-000078](#adam10-000078) | STUDYID must not be null in any ADaM dataset. | Record Data |
| [ADAM10-000079](#adam10-000079) | USUBJID must not be null in any ADaM dataset. | Record Data |
| [ADAM10-000080](#adam10-000080) | PARAMCD must contain only letters, underscores, and numerals. | Record Data |
| [ADAM10-000081](#adam10-000081) | PARAM must not be null in BDS datasets. | Record Data |
| [ADAM10-000082](#adam10-000082) | PARAMCD must not be null in BDS datasets. | Record Data |
| [ADAM10-000083](#adam10-000083) | CRITyFL cannot exist without corresponding CRITy. | Variable Metadata Check |
| [ADAM10-000084](#adam10-000084) | CRITyFL must have values of Y, N, or null. | Record Data |
| [ADAM10-000085](#adam10-000085) | TRTP and TRTPN must have a one-to-one relationship within a dataset. | Record Data |
| [ADAM10-000086](#adam10-000086) | AGEU should use controlled terminology values. | Record Data |
| [ADAM10-000087](#adam10-000087) | Collected Duration (--DUR) value should not be negative. | Record Data |
| [ADAM10-000088](#adam10-000088) | Text variable in submitted dataset should not contain  '.' as an entire value. | Value Check with Variable Metadata |
| [ADAM10-000089](#adam10-000089) | Text variable in submitted dataset should not contain leading spaces ' '. | Value Check with Variable Metadata |
| [ADAM10-000090](#adam10-000090) | Part A: Raise an error when a Required variable is not present in the dataset. | Variable Metadata Check |
| [ADAM10-000091](#adam10-000091) | Raise an error when an expected variable is not present in the dataset. | Variable Metadata Check |
| [ADAM10-000092](#adam10-000092) | The submitted dataset is larger than 5 GB | Dataset Metadata Check |
| [ADAM10-000093](#adam10-000093) | Raise an error when variables are not in the specified order | Variable Metadata Check |
| [ADAM10-000094](#adam10-000094) | Raise an error when a variable is not an allowed variable for an Observation Class | Variable Metadata Check |
| [ADAM10-000095](#adam10-000095) | Raise an error when a dataset has no records. | Dataset Metadata Check |
| [ADAM10-000096](#adam10-000096) | Raise an error when a variable label is not in title case | Variable Metadata Check |
| [ADAM10-000097](#adam10-000097) | Part B: Raise an error when a Required variable is null. | Value Check with Dataset Metadata |
| [ADAM10-000098](#adam10-000098) | When PARAMTYP exists, its value must be 'DERIVED' or null. | Record Data |

## Variable Label Conformance Rules

| Core ID | Variable | Expected Label |
|---------|----------|----------------|
| ADAM10-000099 | ABLFL | Baseline Record |
| ADAM10-000101 | ABLFN | Baseline Record |
| ADAM10-000103 | ADT | Analysis Date |
| ADAM10-000105 | ADTF | Analysis Date |
| ADAM10-000107 | ADTM | Analysis |
| ADAM10-000109 | AENDT | Analysis End |
| ADAM10-000111 | AENDTF | Analysis End |
| ADAM10-000113 | AENDTM | Analysis End |
| ADAM10-000115 | AENDY | Analysis End |
| ADAM10-000117 | AENTM | Analysis End |
| ADAM10-000119 | AENTMF | Analysis End |
| ADAM10-000121 | AGE | Age |
| ADAM10-000123 | AGEU | Age Units |
| ADAM10-000125 | ANRHI | Analysis Normal |
| ADAM10-000127 | ANRIND | Analysis |
| ADAM10-000129 | ANRLO | Analysis Normal |
| ADAM10-000131 | APEREDT | Period End Date |
| ADAM10-000133 | APEREDTF | Period End Date |
| ADAM10-000135 | APERIOD | Period |
| ADAM10-000137 | APERIODC | Period (C) |
| ADAM10-000139 | APHASE | Phase |
| ADAM10-000141 | ARELTM | Analysis Relative |
| ADAM10-000143 | ARM | Description of |
| ADAM10-000145 | ASTDT | Analysis Start |
| ADAM10-000147 | ASTDTF | Analysis Start |
| ADAM10-000149 | ASTDTM | Analysis Start |
| ADAM10-000151 | ASTDY | Analysis Start |
| ADAM10-000153 | ASTTM | Analysis Start |
| ADAM10-000155 | ASTTMF | Analysis Start |
| ADAM10-000157 | ATM | Analysis Time |
| ADAM10-000159 | ATMF | Analysis Time |
| ADAM10-000161 | ATOXGR | Analysis Toxicity |
| ADAM10-000163 | ATPT | Analysis |
| ADAM10-000165 | ATPTN | Analysis |
| ADAM10-000167 | ATPTREF | Analysis |
| ADAM10-000169 | AVAL | Analysis Value |
| ADAM10-000171 | AVALC | Analysis Value |
| ADAM10-000173 | AVISIT | Analysis Visit |
| ADAM10-000175 | AWRANGE | Analysis Window |
| ADAM10-000177 | AWTARGET | Analysis Window |
| ADAM10-000179 | AWTDIFF | Analysis Window |
| ADAM10-000181 | BASE | Baseline Value |
| ADAM10-000183 | BASEC | Baseline Value |
| ADAM10-000185 | BASETYPE | Baseline Type |
| ADAM10-000187 | BNRIND | Baseline |
| ADAM10-000189 | BTOXGR | Baseline Toxicity |
| ADAM10-000191 | CHG | Change from |
| ADAM10-000193 | CNSR | Censor |
| ADAM10-000195 | COMPLFL | Completers |
| ADAM10-000197 | COMPPFL | Completers |
| ADAM10-000199 | COMPPFN | Completers |
| ADAM10-000201 | COMPRFL | Completers |
| ADAM10-000203 | COMPRFN | Completers |
| ADAM10-000205 | DTYPE | Derivation Type |
| ADAM10-000207 | ENRLFL | Enrolled |
| ADAM10-000209 | EVNTDESC | Event or |
| ADAM10-000211 | FASFL | Full Analysis Set |
| ADAM10-000213 | FASPFL | Full Analysis Set |
| ADAM10-000215 | FASPFN | Full Analysis Set |
| ADAM10-000217 | FASRFL | Full Analysis Set |
| ADAM10-000219 | FASRFN | Full Analysis Set |
| ADAM10-000221 | ITTFL | Intent-To-Treat |
| ADAM10-000223 | ITTPFL | Intent-To-Treat |
| ADAM10-000225 | ITTPFN | Intent-To-Treat |
| ADAM10-000227 | ITTRFL | Intent-To-Treat |
| ADAM10-000229 | ITTRFN | Intent-To-Treat |
| ADAM10-000231 | LVOTFL | Last Value On |
| ADAM10-000233 | LVOTFN | Last Value On |
| ADAM10-000235 | ONTRTFL | On Treatment |
| ADAM10-000237 | ONTRTFN | On Treatment |
| ADAM10-000239 | PARAM | Parameter |
| ADAM10-000241 | PARAMCD | Parameter Code |
| ADAM10-000243 | PARAMN | Parameter (N) |
| ADAM10-000245 | PARAMTYP | Parameter Type |
| ADAM10-000247 | PCHG | Percent Change |
| ADAM10-000249 | PPROTFL | Per-Protocol |
| ADAM10-000251 | PPROTPFL | Per-Protocol |
| ADAM10-000253 | PPROTPFN | Per-Protocol |
| ADAM10-000255 | PPROTRFL | Per-Protocol |
| ADAM10-000257 | PPROTRFN | Per-Protocol |
| ADAM10-000259 | R2BASE | Ratio to Baseline |
| ADAM10-000261 | RACE | Race |
| ADAM10-000263 | RANDDT | Date of |
| ADAM10-000265 | RANDFL | Randomized |
| ADAM10-000267 | SAFFL | Safety Population |
| ADAM10-000269 | SAFPFL | Safety Analysis |
| ADAM10-000271 | SAFPFN | Safety Analysis |
| ADAM10-000273 | SAFRFL | Safety Analysis |
| ADAM10-000275 | SAFRFN | Safety Analysis |
| ADAM10-000277 | SEX | Sex |
| ADAM10-000279 | SITEID | Study Site |
| ADAM10-000281 | SRCDOM | Source Domain |
| ADAM10-000283 | SRCSEQ | Source Sequence |
| ADAM10-000285 | SRCVAR | Source Variable |
| ADAM10-000287 | STARTDT | Time to Event |
| ADAM10-000289 | STUDYID | Study Identifier |
| ADAM10-000291 | SUBJID | Subject Identifier |
| ADAM10-000293 | TRTA | Actual Treatment |
| ADAM10-000295 | TRTAN | Actual Treatment |
| ADAM10-000297 | TRTEDT | Date of Last |
| ADAM10-000299 | TRTEDTF | Date of Last |
| ADAM10-000301 | TRTEDTM | Datetime of Last |
| ADAM10-000303 | TRTETM | Time of Last |
| ADAM10-000305 | TRTETMF | Time of Last |
| ADAM10-000307 | TRTP | Planned |
| ADAM10-000309 | TRTPN | Planned |
| ADAM10-000311 | TRTSDT | Date of First |
| ADAM10-000313 | TRTSDTF | Date of First |
| ADAM10-000315 | TRTSDTM | Datetime of First |
| ADAM10-000317 | TRTSEQA | Actual Sequence |
| ADAM10-000319 | TRTSEQAN | Actual Sequence |
| ADAM10-000321 | TRTSEQPN | Planned Sequence |
| ADAM10-000323 | TRTSTM | Time of First |
| ADAM10-000325 | TRTSTMF | Time of First |
| ADAM10-000327 | USUBJID | Unique Subject |

## Variable Type Conformance Rules

| Core ID | Variable | Expected Type |
|---------|----------|---------------|
| ADAM10-000100 | ABLFL | Char |
| ADAM10-000102 | ABLFN | Num |
| ADAM10-000104 | ADT | Num |
| ADAM10-000106 | ADTF | Char |
| ADAM10-000108 | ADTM | Num |
| ADAM10-000110 | AENDT | Num |
| ADAM10-000112 | AENDTF | Char |
| ADAM10-000114 | AENDTM | Num |
| ADAM10-000116 | AENDY | Num |
| ADAM10-000118 | AENTM | Num |
| ADAM10-000120 | AENTMF | Char |
| ADAM10-000122 | AGE | Num |
| ADAM10-000124 | AGEU | Char |
| ADAM10-000126 | ANRHI | Char |
| ADAM10-000128 | ANRIND | Char |
| ADAM10-000130 | ANRLO | Char |
| ADAM10-000132 | APEREDT | Num |
| ADAM10-000134 | APEREDTF | Char |
| ADAM10-000136 | APERIOD | Num |
| ADAM10-000138 | APERIODC | Char |
| ADAM10-000140 | APHASE | Char |
| ADAM10-000142 | ARELTM | Num |
| ADAM10-000144 | ARM | Char |
| ADAM10-000146 | ASTDT | Num |
| ADAM10-000148 | ASTDTF | Char |
| ADAM10-000150 | ASTDTM | Num |
| ADAM10-000152 | ASTDY | Num |
| ADAM10-000154 | ASTTM | Num |
| ADAM10-000156 | ASTTMF | Char |
| ADAM10-000158 | ATM | Num |
| ADAM10-000160 | ATMF | Char |
| ADAM10-000162 | ATOXGR | Char |
| ADAM10-000164 | ATPT | Char |
| ADAM10-000166 | ATPTN | Num |
| ADAM10-000168 | ATPTREF | Char |
| ADAM10-000170 | AVAL | Num |
| ADAM10-000172 | AVALC | Char |
| ADAM10-000174 | AVISIT | Char |
| ADAM10-000176 | AWRANGE | Char |
| ADAM10-000178 | AWTARGET | Num |
| ADAM10-000180 | AWTDIFF | Num |
| ADAM10-000182 | BASE | Num |
| ADAM10-000184 | BASEC | Char |
| ADAM10-000186 | BASETYPE | Char |
| ADAM10-000188 | BNRIND | Char |
| ADAM10-000190 | BTOXGR | Char |
| ADAM10-000192 | CHG | Num |
| ADAM10-000194 | CNSR | Num |
| ADAM10-000196 | COMPLFL | Char |
| ADAM10-000198 | COMPPFL | Char |
| ADAM10-000200 | COMPPFN | Num |
| ADAM10-000202 | COMPRFL | Char |
| ADAM10-000204 | COMPRFN | Num |
| ADAM10-000206 | DTYPE | Char |
| ADAM10-000208 | ENRLFL | Char |
| ADAM10-000210 | EVNTDESC | Char |
| ADAM10-000212 | FASFL | Char |
| ADAM10-000214 | FASPFL | Char |
| ADAM10-000216 | FASPFN | Num |
| ADAM10-000218 | FASRFL | Char |
| ADAM10-000220 | FASRFN | Num |
| ADAM10-000222 | ITTFL | Char |
| ADAM10-000224 | ITTPFL | Char |
| ADAM10-000226 | ITTPFN | Num |
| ADAM10-000228 | ITTRFL | Char |
| ADAM10-000230 | ITTRFN | Num |
| ADAM10-000232 | LVOTFL | Char |
| ADAM10-000234 | LVOTFN | Num |
| ADAM10-000236 | ONTRTFL | Char |
| ADAM10-000238 | ONTRTFN | Num |
| ADAM10-000240 | PARAM | Char |
| ADAM10-000242 | PARAMCD | Char |
| ADAM10-000244 | PARAMN | Num |
| ADAM10-000246 | PARAMTYP | Char |
| ADAM10-000248 | PCHG | Num |
| ADAM10-000250 | PPROTFL | Char |
| ADAM10-000252 | PPROTPFL | Char |
| ADAM10-000254 | PPROTPFN | Num |
| ADAM10-000256 | PPROTRFL | Char |
| ADAM10-000258 | PPROTRFN | Num |
| ADAM10-000260 | R2BASE | Num |
| ADAM10-000262 | RACE | Char |
| ADAM10-000264 | RANDDT | Num |
| ADAM10-000266 | RANDFL | Char |
| ADAM10-000268 | SAFFL | Char |
| ADAM10-000270 | SAFPFL | Char |
| ADAM10-000272 | SAFPFN | Num |
| ADAM10-000274 | SAFRFL | Char |
| ADAM10-000276 | SAFRFN | Num |
| ADAM10-000278 | SEX | Char |
| ADAM10-000280 | SITEID | Char |
| ADAM10-000282 | SRCDOM | Char |
| ADAM10-000284 | SRCSEQ | Num |
| ADAM10-000286 | SRCVAR | Char |
| ADAM10-000288 | STARTDT | Num |
| ADAM10-000290 | STUDYID | Char |
| ADAM10-000292 | SUBJID | Char |
| ADAM10-000294 | TRTA | Char |
| ADAM10-000296 | TRTAN | Num |
| ADAM10-000298 | TRTEDT | Num |
| ADAM10-000300 | TRTEDTF | Char |
| ADAM10-000302 | TRTEDTM | Num |
| ADAM10-000304 | TRTETM | Num |
| ADAM10-000306 | TRTETMF | Char |
| ADAM10-000308 | TRTP | Char |
| ADAM10-000310 | TRTPN | Num |
| ADAM10-000312 | TRTSDT | Num |
| ADAM10-000314 | TRTSDTF | Char |
| ADAM10-000316 | TRTSDTM | Num |
| ADAM10-000318 | TRTSEQA | Char |
| ADAM10-000320 | TRTSEQAN | Num |
| ADAM10-000322 | TRTSEQPN | Num |
| ADAM10-000324 | TRTSTM | Num |
| ADAM10-000326 | TRTSTMF | Char |
| ADAM10-000328 | USUBJID | Char |

---

## Rule Details (Structural Rules)

### ADAM10-000001

**Description:** ADSL dataset is required in a CDISC-based submission.

| Attribute | Value |
|-----------|-------|
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 2.3.1

**Cited Guidance:** ADSL and its related metadata are required in a CDISC-based submission of data from a clinical trial even if no other analysis datasets are submitted.

---

### ADAM10-000002

**Description:** ADSL must contain exactly one record per subject.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 2.3.1

**Cited Guidance:** The ADSL contains 1 record per subject, regardless of the type of clinical trial design.

---

### ADAM10-000003

**Description:** STUDYID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** STUDYID: Req. DM.STUDYID

---

### ADAM10-000004

**Description:** USUBJID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** USUBJID: Req. DM.USUBJID

---

### ADAM10-000005

**Description:** SUBJID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** SUBJID: Req. DM.SUBJID. SUBJID is required in ADSL, but permissible in other datasets.

---

### ADAM10-000006

**Description:** SITEID is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** SITEID: Req. DM.SITEID. SITEID is required in ADSL, but permissible in other datasets.

---

### ADAM10-000007

**Description:** AGE is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** AGE: Req. DM.AGE.

---

### ADAM10-000008

**Description:** AGEU is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** AGEU: Req. DM.AGEU.

---

### ADAM10-000009

**Description:** SEX is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** SEX: Req. DM.SEX.

---

### ADAM10-000010

**Description:** RACE is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** RACE: Req. DM.RACE.

---

### ADAM10-000011

**Description:** ARM is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** ARM: Req. DM.ARM.

---

### ADAM10-000012

**Description:** TRT01P is required in ADSL. At least TRT01P is required.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** Subject-level identifier that represents the planned treatment for period xx. At least TRT01P is required.

---

### ADAM10-000013

**Description:** At least one population flag variable is required in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** A minimum of one subject-level population flag variable is required in ADSL.

---

### ADAM10-000014

**Description:** When SAFFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000015

**Description:** When FASFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000016

**Description:** When ITTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000017

**Description:** When PPROTFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000018

**Description:** When COMPLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000019

**Description:** When RANDFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000020

**Description:** When ENRLFL exists, its values must be 'Y' or 'N' (null not allowed for subject-level population flags).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** For subject-level character population flag variables: N = no (not included in the population), Y = yes (included). Null values are not allowed.

---

### ADAM10-000021

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

### ADAM10-000022

**Description:** ADaM dataset names must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.1

**Cited Guidance:** To ensure compliance with SAS Version 5 transport file format, all ADaM variable names must be no more than 8 characters in length.

---

### ADAM10-000023

**Description:** ADaM variable names must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.1

**Cited Guidance:** All ADaM variable names must be no more than 8 characters in length.

---

### ADAM10-000024

**Description:** ADaM variable labels must not exceed 40 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.1

**Cited Guidance:** All ADaM variable labels must be no more than 40 characters in length.

---

### ADAM10-000025

**Description:** ADaM character variables must not exceed 200 characters in length.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.1

**Cited Guidance:** All ADaM character variables must be no more than 200 characters in length.

---

### ADAM10-000026

**Description:** USUBJID is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.1

**Cited Guidance:** USUBJID: Req.

---

### ADAM10-000027

**Description:** PARAM is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAM: Req. Description of analysis parameter.

---

### ADAM10-000028

**Description:** PARAMCD is required in all BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAMCD: Req. Short name for the analysis parameter.

---

### ADAM10-000029

**Description:** When PARAMN exists, there must be a one-to-one relationship between PARAMN and PARAM within a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** There must be a one-to-one relationship between PARAMN and PARAM within a dataset.

---

### ADAM10-000030

**Description:** There must be a one-to-one relationship between PARAMCD and PARAM within a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** There must be a one-to-one relationship between PARAMCD and PARAM.

---

### ADAM10-000031

**Description:** PARAMCD must not exceed 8 characters.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAMCD has a maximum length of 8 characters.

---

### ADAM10-000032

**Description:** At least one of AVAL or AVALC must be present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** AVAL and AVALC: Cond. At least one is required.

---

### ADAM10-000033

**Description:** DTYPE must be present when a BDS dataset contains derived records.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** DTYPE: Cond. Required when there are derived records.

---

### ADAM10-000034

**Description:** Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.1

**Cited Guidance:** Any variable in an ADaM dataset whose name is the same as an SDTM variable must be a copy of the SDTM variable, and its label, meaning, and values must not be modified.

---

### ADAM10-000035

**Description:** When both *DT and *DTM variables exist, the *DT value must match the date part of the *DTM value.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.2

**Cited Guidance:** If a *DTM and associated *DT variable exist, then the *DT value must match the date part of the *DTM value when the *DTM variable is populated.

---

### ADAM10-000036

**Description:** When both *TM and *DTM variables exist, the *TM value must match the time part of the *DTM value.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.2

**Cited Guidance:** If a *DTM and associated *TM variable exist, then the *TM value must match the time part of the *DTM value when the *DTM variable is populated.

---

### ADAM10-000037

**Description:** Relative day variables ending in DY must not have a value of 0.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.2

**Cited Guidance:** In the ADaM as in the SDTM, there is no Day 0.

---

### ADAM10-000038

**Description:** Date imputation flag (*DTF) must be populated when a date (*DT) is imputed.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.3

**Cited Guidance:** If a date was imputed, *DTF must be populated and is required.

---

### ADAM10-000039

**Description:** Date imputation flag (*DTF) must have values of Y, M, or D when populated.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.3

**Cited Guidance:** *DTF = Y if the year is imputed. *DTF = M if year is present and month is imputed. *DTF = D if only day is imputed.

---

### ADAM10-000040

**Description:** Time imputation flag (*TMF) must have values of H, M, or S when populated.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.3

**Cited Guidance:** *TMF = H if the entire time is imputed. *TMF = M if minutes and seconds are imputed. *TMF = S if only seconds are imputed.

---

### ADAM10-000041

**Description:** Character flag variables (*FL) must have values of Y, N, or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** Variables whose names end in FL are character flag variables with at most two possible non-missing values, Y or N.

---

### ADAM10-000042

**Description:** Numeric flag (*FN) cannot exist without corresponding character flag (*FL).

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.1.4

**Cited Guidance:** If the flag is included in an ADaM dataset, the character version (*FL) is required but the corresponding numeric version (*FN) can also be included.

---

### ADAM10-000043

**Description:** TRT01PN cannot be present unless TRT01P is also present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** TRTxxPN cannot be present unless TRTxxP is also present.

---

### ADAM10-000044

**Description:** TRT01AN cannot be present unless TRT01A is also present.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** TRTxxAN cannot be present unless TRTxxA is also present.

---

### ADAM10-000045

**Description:** TRTSDT and/or TRTSDTM are required in ADSL if there is an investigational product.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** TRTSDT and/or TRTSDTM are required if there is an investigational product.

---

### ADAM10-000046

**Description:** TRTEDT and/or TRTEDTM are required in ADSL if there is an investigational product.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** TRTEDT and/or TRTEDTM are required if there is an investigational product.

---

### ADAM10-000047

**Description:** TRTSDTF must be populated when TRTSDT is imputed.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** If TRTSDT was imputed, TRTSDTF must be populated and is required.

---

### ADAM10-000048

**Description:** Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** Date of first exposure to treatment must logically precede or equal date of last exposure.

---

### ADAM10-000049

**Description:** RANDDT is required in randomized trials.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable - Possible Underreporting |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** RANDDT: Cond. Required in randomized trials.

---

### ADAM10-000050

**Description:** AVAL must be numeric type.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** AVAL: Num. Analysis Value.

---

### ADAM10-000051

**Description:** AVALC must be character type.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** AVALC: Char. Analysis Value (C).

---

### ADAM10-000052

**Description:** BASE must be present when CHG is present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** BASE: Cond. Baseline Value. Required when CHG or PCT is present.

---

### ADAM10-000053

**Description:** CHG must equal AVAL minus BASE.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** CHG = AVAL - BASE.

---

### ADAM10-000054

**Description:** CHG should be null for baseline records (where ABLFL = 'Y').

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** CHG is null for baseline records.

---

### ADAM10-000055

**Description:** PCT (Percent Change from Baseline) should be null for baseline records.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PCT is null for baseline records.

---

### ADAM10-000056

**Description:** ABLFL (Analysis Baseline Flag) must be 'Y' or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** ABLFL: Cond. Y or null.

---

### ADAM10-000057

**Description:** There should be at most one record with ABLFL = 'Y' per subject per parameter per baseline type.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** There must be only one record flagged as baseline per subject per parameter per baseline type.

---

### ADAM10-000058

**Description:** When AVISITN exists, there must be a one-to-one relationship between AVISIT and AVISITN within a parameter.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.3

**Cited Guidance:** There must be a one-to-one relationship between AVISITN and AVISIT within a parameter.

---

### ADAM10-000059

**Description:** When ATPTN exists, there must be a one-to-one relationship between ATPT and ATPTN.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.3

**Cited Guidance:** There must be a one-to-one relationship between ATPTN and ATPT within a parameter.

---

### ADAM10-000060

**Description:** CNSR is required in time-to-event BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.6

**Cited Guidance:** CNSR: Req (in TTE). Censor indicator for the event of interest described by PARAM.

---

### ADAM10-000061

**Description:** CNSR values should be 0 (event) or a positive integer (censored).

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.6

**Cited Guidance:** CNSR = 0 for subjects who have experienced the event of interest. Values greater than 0 are used for censored subjects.

---

### ADAM10-000062

**Description:** When ANRHIy exists, ANRLOy must also exist.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.7

**Cited Guidance:** ANRLOy and ANRHIy define the analysis reference range.

---

### ADAM10-000063

**Description:** ANLzzFL (Analysis Record Flag) must be 'Y' or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.8

**Cited Guidance:** ANLzzFL: Y or null. Record-level flag for analysis.

---

### ADAM10-000064

**Description:** USUBJID values in BDS datasets must have a corresponding record in ADSL.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 2.3.1

**Cited Guidance:** Within a given study, USUBJID is the key variable that links the ADSL to other datasets.

---

### ADAM10-000065

**Description:** A variable present in both ADSL and another ADaM dataset must have the same values, type, and label.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 2.3.1

**Cited Guidance:** A variable that is present in both ADSL and any other ADaM dataset must have the same values, type, and label.

---

### ADAM10-000066

**Description:** TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.2

**Cited Guidance:** TRTP: Cond. Description of Planned Treatment. Required if the dataset is used for analysis by treatment.

---

### ADAM10-000067

**Description:** TRTP must be present when TRTA is present in a BDS dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.2

**Cited Guidance:** TRTP: Required when TRTA is present.

---

### ADAM10-000068

**Description:** TRTPN cannot be present without TRTP.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.2

**Cited Guidance:** TRTPN cannot be present unless TRTP is also present.

---

### ADAM10-000069

**Description:** The ADSL dataset class must be 'SUBJECT LEVEL ANALYSIS DATASET'.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 2.3.1

**Cited Guidance:** In a study, there is only 1 dataset in the class 'SUBJECT LEVEL ANALYSIS DATASET', and its name is ADSL.

---

### ADAM10-000070

**Description:** Non-ADaM analysis dataset names should not start with 'AD' prefix.

| Attribute | Value |
|-----------|-------|
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 1.6

**Cited Guidance:** To prevent confusion, non-ADaM analysis dataset names should not start with the prefix AD. It is good practice to start the names of non-ADaM analysis datasets with the two-letter prefix 'AX'.

---

### ADAM10-000071

**Description:** When SRCVAR exists, SRCDOM must also exist for datapoint traceability.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.9

**Cited Guidance:** SRCDOM and SRCVAR are used together for datapoint traceability.

---

### ADAM10-000072

**Description:** When SRCSEQ exists, SRCDOM must also exist.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.9

**Cited Guidance:** SRCSEQ: Cond. Required when SRCDOM exists.

---

### ADAM10-000073

**Description:** DTYPE values should be from the ADaM controlled terminology.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.5

**Cited Guidance:** DTYPE: Derivation Type. Values from controlled terminology.

---

### ADAM10-000074

**Description:** BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.5

**Cited Guidance:** BASETYPE: Cond. Required when there is more than one definition of baseline.

---

### ADAM10-000075

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

### ADAM10-000076

**Description:** BASE must be a numeric variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** BASE: Num. Baseline Value.

---

### ADAM10-000077

**Description:** CHG must be a numeric variable.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** CHG: Num. Change from Baseline.

---

### ADAM10-000078

**Description:** STUDYID must not be null in any ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** STUDYID: Req.

---

### ADAM10-000079

**Description:** USUBJID must not be null in any ADaM dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** USUBJID: Req.

---

### ADAM10-000080

**Description:** PARAMCD must contain only letters, underscores, and numerals.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAMCD must start with a letter and contain only letters, underscores, and numerals.

---

### ADAM10-000081

**Description:** PARAM must not be null in BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAM: Req. Description of analysis parameter.

---

### ADAM10-000082

**Description:** PARAMCD must not be null in BDS datasets.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAMCD: Req. Short name for the analysis parameter.

---

### ADAM10-000083

**Description:** CRITyFL cannot exist without corresponding CRITy.

| Attribute | Value |
|-----------|-------|
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4.3

**Cited Guidance:** CRITy: Cond. Required when CRITyFL exists.

---

### ADAM10-000084

**Description:** CRITyFL must have values of Y, N, or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4.3

**Cited Guidance:** CRITyFL: Y/N/null or Y/null.

---

### ADAM10-000085

**Description:** TRTP and TRTPN must have a one-to-one relationship within a dataset.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.2

**Cited Guidance:** There must be a one-to-one relationship between TRTPN and TRTP within a study.

---

### ADAM10-000086

**Description:** AGEU should use controlled terminology values.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SUBJECT LEVEL ANALYSIS DATASET |
| Domains | ADSL |

**Reference:** ADaMIG v1.0, Section 3.2

**Cited Guidance:** AGEU: Req. Age units from controlled terminology (AGEU).

---

### ADAM10-000087

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

### ADAM10-000088

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

### ADAM10-000089

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

### ADAM10-000090

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

### ADAM10-000091

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

### ADAM10-000092

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

### ADAM10-000093

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

### ADAM10-000094

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

### ADAM10-000095

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

### ADAM10-000096

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

### ADAM10-000097

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

### ADAM10-000098

**Description:** When PARAMTYP exists, its value must be 'DERIVED' or null.

| Attribute | Value |
|-----------|-------|
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | BASIC DATA STRUCTURE |

**Reference:** ADaMIG v1.0, Section 3.3.4

**Cited Guidance:** PARAMTYP: Perm. Indicates whether the parameter is derived. Values: DERIVED or null.

---
