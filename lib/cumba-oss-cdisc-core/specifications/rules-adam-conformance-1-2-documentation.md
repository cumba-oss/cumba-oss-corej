# ADaM Conformance Rules v5.0 — ADaMIG v1.2

Auto-generated from `ADaM Conformance Rules v5.0.xlsx`.

**Total Rules:** 570  
**Standard:** ADaMIG v1.2  
**Source:** ADaM Conformance Rules v5.0 catalogue  

## By Class

| Class | Count |
|-------|-------|
| BASIC DATA STRUCTURE | 205 |
| SUBJECT LEVEL ANALYSIS DATASET | 124 |
| ALL | 100 |
| OCCURRENCE DATA STRUCTURE | 84 |
| BASIC DATA STRUCTURE; OCCURRENCE DATA STRUCTURE | 55 |
| BASIC DATA STRUCTURE; MEDICAL DEVICE BASIC DATA STRUCTURE | 2 |

## Rule Index

| CR Rule ID | Core ID | Variable | Class | Description |
|------------|---------|----------|-------|-------------|
| CR1 | ADAMCR-0001 |  | SUBJECT LEVEL ANALYSIS DATASET | ADSL dataset does not exist |
| CR2 | ADAMCR-0002 |  | ALL | A variable is present in ADaM with the same name as a variable present in SDTM b |
| CR5 | ADAMCR-0005 | *FL | ALL | A variable with a suffix of FL has a value that is not Y, N or null |
| CR6 | ADAMCR-0006 | *FN | ALL | A variable with a suffix of FL is present and a variable with the same root and  |
| CR7 | ADAMCR-0007 | *FL | ALL | A variable with a suffix of FN is present but a variable with the same root and  |
| CR10 | ADAMCR-0010 | *FN | ALL | A variable with a suffix of FL is equal to Y and a variable with the same root a |
| CR11 | ADAMCR-0011 | *FN | ALL | A variable with a suffix of FL is equal to N and a variable with the same root a |
| CR12 | ADAMCR-0012 | *FN | ALL | A variable with a suffix of FL is equal to null and a variable with the same roo |
| CR13 | ADAMCR-0013 | ALL | ALL | The length of a variable name exceeds 8 characters |
| CR14 | ADAMCR-0014 | ALL | ALL | A variable name does not start with a letter (A-Z) |
| CR15 | ADAMCR-0015 | ALL | ALL | A variable name contains a character other than letters (A-Z), underscores (_),  |
| CR16 | ADAMCR-0016 | ALL | ALL | The length of a variable label is greater than 40 characters |
| CR17 | ADAMCR-0017 | ALL | ALL | The length of a character value is greater than 200 characters |
| CR19 | ADAMCR-0019 | COMPLFL | SUBJECT LEVEL ANALYSIS DATASET | COMPLFL is present and has a value that is not Y or N |
| CR20 | ADAMCR-0020 | FASFL | SUBJECT LEVEL ANALYSIS DATASET | FASFL is present and has a value that is not Y or N |
| CR21 | ADAMCR-0021 | ITTFL | SUBJECT LEVEL ANALYSIS DATASET | ITTFL is present and has a value that is not Y or N |
| CR22 | ADAMCR-0022 | PPROTFL | SUBJECT LEVEL ANALYSIS DATASET | PPROTFL is present and has a value that is not Y or N |
| CR23 | ADAMCR-0023 | SAFFL | SUBJECT LEVEL ANALYSIS DATASET | SAFFL is present and has a value that is not Y or N |
| CR24 | ADAMCR-0024 | RANDFL | SUBJECT LEVEL ANALYSIS DATASET | RANDFL is present and has a value that is not Y or N |
| CR25 | ADAMCR-0025 | ENRLFL | SUBJECT LEVEL ANALYSIS DATASET | ENRLFL is present and has a value that is not Y or N |
| CR26 | ADAMCR-0026 | COMPLFN | SUBJECT LEVEL ANALYSIS DATASET | COMPLFN is present and has a value that is not 1 or 0 |
| CR27 | ADAMCR-0027 | FASFN | SUBJECT LEVEL ANALYSIS DATASET | FASFN is present and has a value that is not 1 or 0 |
| CR28 | ADAMCR-0028 | ITTFN | SUBJECT LEVEL ANALYSIS DATASET | ITTFN is present and has a value that is not 1 or 0 |
| CR29 | ADAMCR-0029 | PPROTFN | SUBJECT LEVEL ANALYSIS DATASET | PPROTFN is present and has a value that is not 1 or 0 |
| CR30 | ADAMCR-0030 | SAFFN | SUBJECT LEVEL ANALYSIS DATASET | SAFFN is present and has a value that is not 1 or 0 |
| CR31 | ADAMCR-0031 | RANDFN | SUBJECT LEVEL ANALYSIS DATASET | RANDFN is present and has a value that is not 1 or 0 |
| CR32 | ADAMCR-0032 | ENRLFN | SUBJECT LEVEL ANALYSIS DATASET | ENRLFN is present and has a value that is not 1 or 0 |
| CR39 | ADAMCR-0039 | *DTF | ALL | A variable with a suffix of DTF has a value that is not within Controlled Termin |
| CR40 | ADAMCR-0040 | *TMF | ALL | A variable with a suffix of TMF has a value that is not within Controlled Termin |
| CR41 | ADAMCR-0041 | *DT | ALL | A numeric variable with a suffix of DT does not have a date format |
| CR42 | ADAMCR-0042 | *TM | ALL | A numeric variable with a suffix of TM does not have a time format, excluding AR |
| CR43 | ADAMCR-0043 | *DTM | ALL | A numeric variable with a suffix of DTM does not have a datetime format |
| CR44 | ADAMCR-0044 | *TM; *DTM | ALL | A variable with a suffix of TM and a variable with a suffix of DTM with the same |
| CR45 | ADAMCR-0045 | *DT; *DTM | ALL | A variable with a suffix of DT and a variable with a suffix of DTM with the same |
| CR46 | ADAMCR-0046 | *DY | ALL | A variable with a suffix of DY has a value of zero |
| CR47 | ADAMCR-0047 |  | SUBJECT LEVEL ANALYSIS DATASET | SITEID is not present within ADSL |
| CR48 | ADAMCR-0048 |  | SUBJECT LEVEL ANALYSIS DATASET | A variable with a suffix of FL is not present in ADSL |
| CR49 | ADAMCR-0049 |  | SUBJECT LEVEL ANALYSIS DATASET | AGE is not present within ADSL |
| CR50 | ADAMCR-0050 |  | SUBJECT LEVEL ANALYSIS DATASET | AGEU is not present within ADSL |
| CR51 | ADAMCR-0051 |  | SUBJECT LEVEL ANALYSIS DATASET | SEX is not present within ADSL |
| CR52 | ADAMCR-0052 |  | SUBJECT LEVEL ANALYSIS DATASET | RACE is not present within ADSL |
| CR53 | ADAMCR-0053 | USUBJID | ALL | The value of USUBJID is not present in SDTM.DM |
| CR54 | ADAMCR-0054 | USUBJID | SUBJECT LEVEL ANALYSIS DATASET | Within ADSL there is more than one record for a unique value of USUBJID |
| CR55 | ADAMCR-0055 |  | SUBJECT LEVEL ANALYSIS DATASET | SUBJID is not present within ADSL |
| CR58 | ADAMCR-0058 | *DT | ALL | A variable with a suffix of DT is not a numeric variable |
| CR60 | ADAMCR-0060 | *DTM | ALL | A variable with a suffix of DTM is not a numeric variable |
| CR61 | ADAMCR-0061 | TRTSDT; TRTSDTM | SUBJECT LEVEL ANALYSIS DATASET | SDTM.EX is present and neither TRTSDT or TRTSDTM are present |
| CR64 | ADAMCR-0064 | TRTxxAN where xx is  | SUBJECT LEVEL ANALYSIS DATASET | TRTxxAN is present and TRTxxA is not present |
| CR66 | ADAMCR-0066 |  | SUBJECT LEVEL ANALYSIS DATASET | A variable with a prefix of TR, containing PG and a suffix of N is present and a |
| CR70 | ADAMCR-0070 |  | SUBJECT LEVEL ANALYSIS DATASET | A variable with a prefix of TR, containing AG with a suffix of N is present and  |
| CR71 | ADAMCR-0071 |  | SUBJECT LEVEL ANALYSIS DATASET | ARM is not present within ADSL |
| CR72 | ADAMCR-0072 |  | SUBJECT LEVEL ANALYSIS DATASET | TRT01P is not present within ADSL |
| CR75 | ADAMCR-0075 | TRTxxPN where xx is  | SUBJECT LEVEL ANALYSIS DATASET | TRTxxPN is present and TRTxxP is not present |
| CR78 | ADAMCR-0078 | TRTxxP where xx is a | SUBJECT LEVEL ANALYSIS DATASET | At least one TRTxxP is present where xx is greater than 01 and TRxxSDT is not pr |
| CR79 | ADAMCR-0079 | TRTxxP where xx is a | SUBJECT LEVEL ANALYSIS DATASET | At least one TRTxxP is present where xx is greater than 01 and TRxxEDT is not pr |
| CR80 | ADAMCR-0080 | TRTxxA where xx is a | SUBJECT LEVEL ANALYSIS DATASET | TRTxxA is present and TRTxxP is not present |
| CR81 | ADAMCR-0081 | TRTxxP where xx is a | SUBJECT LEVEL ANALYSIS DATASET | TRTxxP is present and xx is greater than 01 and TRT{xx-1}P is not present |
| CR84 | ADAMCR-0084 | TRTEDT | SUBJECT LEVEL ANALYSIS DATASET | TRTEDT is not equal to the maximum value of all TRxxEDT variables |
| CR85 | ADAMCR-0085 |  | ALL | A variable is present with the same name as a variable present in ADSL but the v |
| CR86 | ADAMCR-0086 |  | ALL | A variable is present with the same name as a variable present in ADSL but the v |
| CR88 | ADAMCR-0088 |  | ALL | STUDYID is not present |
| CR89 | ADAMCR-0089 |  | ALL | USUBJID is not present |
| CR97 | ADAMCR-0097 | TRTPGy where y is an | BASIC DATA STRUCTURE | TRTPGyN is present and TRTPGy is not present |
| CR98 | ADAMCR-0098 | *SDY | BASIC DATA STRUCTURE | On a given record, a variable with a suffix of SDY has a value greater than a va |
| CR99 | ADAMCR-0099 | *STDY | BASIC DATA STRUCTURE | On a given record, a variable with a suffix of STDY has a value greater than a v |
| CR102 | ADAMCR-0102 | APERIOD | ALL | For every unique xx value of APERIOD, there is not an ADSL variable TRTxxP |
| CR103 | ADAMCR-0103 | APERIOD | ALL | For every unique xx value of APERIOD, there is not an ADSL variable TRxxSDT |
| CR104 | ADAMCR-0104 | APERIOD | ALL | For every unique xx value of APERIOD, there is not an ADSL variable TRxxEDT |
| CR111 | ADAMCR-0111 |  | BASIC DATA STRUCTURE | ARELTM is present and ARELTMU is not present |
| CR112 | ADAMCR-0112 | ARELTMU | BASIC DATA STRUCTURE | ARELTM is populated and ARELTMU is not populated |
| CR113 | ADAMCR-0113 |  | BASIC DATA STRUCTURE | ARELTMU is present and ARELTM is not present |
| CR117 | ADAMCR-0117 | ATPT | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of ATPT for a give |
| CR118 | ADAMCR-0118 | ATPTN | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of ATPTN for a giv |
| CR121 | ADAMCR-0121 | *SDT | ALL | The value of a variable with a suffix of SDT is greater than the value of a vari |
| CR122 | ADAMCR-0122 | *SDTM | ALL | The value of a variable with a suffix of SDTM is greater than the value of a var |
| CR124 | ADAMCR-0124 | PARCATy where y is a | BASIC DATA STRUCTURE | There is more than one value of PARCATy for a given value of PARAMCD |
| CR127 | ADAMCR-0127 | ABLFL | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, BASE is populated and there is no |
| CR128 | ADAMCR-0128 | ABLFL | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, BASEC is populated and there is n |
| CR131 | ADAMCR-0131 | BASETYPE | BASIC DATA STRUCTURE | Within a given value of PARAMCD, BASETYPE is populated for at least one record a |
| CR132 | ADAMCR-0132 | R2BASE | BASIC DATA STRUCTURE | R2BASE is not equal to AVAL divided by BASE |
| CR133 | ADAMCR-0133 | R2AyLO where y is an | BASIC DATA STRUCTURE | R2AyLO is not equal to AVAL divided by AyLO |
| CR134 | ADAMCR-0134 | R2AyHI where y is an | BASIC DATA STRUCTURE | R2AyHI is not equal to AVAL divided by AyHI |
| CR137 | ADAMCR-0137 | CRITy where y is an  | BASIC DATA STRUCTURE | CRITyFL is populated and CRITy is not populated |
| CR143 | ADAMCR-0143 | PARAMCD | BASIC DATA STRUCTURE | PARAMCD has more than 8 characters in length |
| CR144 | ADAMCR-0144 | PARAMCD | BASIC DATA STRUCTURE | PARAMCD starts with a character other than a letter |
| CR145 | ADAMCR-0145 | PARAMCD | BASIC DATA STRUCTURE | PARAMCD has characters that are not letters, digits, and underscores |
| CR151 | ADAMCR-0151 | CRITy where y is an  | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of CRITy |
| CR152 | ADAMCR-0152 | BASE | BASIC DATA STRUCTURE | BASETYPE is present, BASE is populated, and BASE is not equal to AVAL where ABLF |
| CR154 | ADAMCR-0154 | ABLFL | BASIC DATA STRUCTURE | Within a given PARAMCD and BASETYPE for a subject, more than one record has ABLF |
| CR155 | ADAMCR-0155 | ABLFL | BASIC DATA STRUCTURE | Within a given PARAMCD for a subject, more than one record has ABLFL equal to Y  |
| CR157 | ADAMCR-0157 | CRITy where y is an  | BASIC DATA STRUCTURE | A variable with a prefix of CRIT and a suffix of a one-digit number (CRITy) is p |
| CR159 | ADAMCR-0159 | AWTARGET | BASIC DATA STRUCTURE | AWTDIFF is populated and AWTARGET is not populated |
| CR163 | ADAMCR-0163 | BTOXGR | BASIC DATA STRUCTURE | BTOXGR is present and ATOXGR is not present |
| CR164 | ADAMCR-0164 | BTOXGR | BASIC DATA STRUCTURE | BTOXGR is present and ABLFL is not present |
| CR165 | ADAMCR-0165 | BTOXGR | BASIC DATA STRUCTURE | BASETYPE is present, BTOXGR is populated, and BTOXGR is not equal to ATOXGR wher |
| CR166 | ADAMCR-0166 | BNRIND | BASIC DATA STRUCTURE | BNRIND is present and ANRIND is not present |
| CR167 | ADAMCR-0167 | BNRIND | BASIC DATA STRUCTURE | BNRIND is present and ABLFL is not present |
| CR168 | ADAMCR-0168 | BNRIND | BASIC DATA STRUCTURE | BASETYPE is present, BNRIND is populated, and BNRIND is not equal to ANRIND wher |
| CR169 | ADAMCR-0169 | CNSR | BASIC DATA STRUCTURE | The value of CNSR is not a positive integer or 0 |
| CR181 | ADAMCR-0181 | BASE | BASIC DATA STRUCTURE | BASETYPE is not present, BASE is populated, and BASE is not equal to AVAL where  |
| CR182 | ADAMCR-0182 | BTOXGR | BASIC DATA STRUCTURE | BASETYPE is not present, BTOXGR is populated, and BTOXGR is not equal to ATOXGR  |
| CR183 | ADAMCR-0183 | BNRIND | BASIC DATA STRUCTURE | BASETYPE is not present, BNRIND is populated, and BNRIND is not equal to ANRIND  |
| CR194 | ADAMCR-0194 | PARAM | BASIC DATA STRUCTURE | PARAM is not present |
| CR195 | ADAMCR-0195 | PARAMCD | BASIC DATA STRUCTURE | PARAMCD is not present |
| CR196 | ADAMCR-0196 | PARAM | BASIC DATA STRUCTURE | PARAM is not populated |
| CR197 | ADAMCR-0197 | PARAMCD | BASIC DATA STRUCTURE | PARAMCD is not populated |
| CR198 | ADAMCR-0198 |  | BASIC DATA STRUCTURE | AVAL is not present and AVALC is not present |
| CR199 | ADAMCR-0199 | ALL | ALL | A variable is present in ADaM with the same name as a variable present in SDTM b |
| CR200 | ADAMCR-0200 | ALL | ALL | A variable is present in ADaM with the same name as a variable defined in the AD |
| CR201 | ADAMCR-0201 | TRTAGy where y is an | BASIC DATA STRUCTURE | TRTAGyN is present and TRTAGy is not present |
| CR204 | ADAMCR-0204 | AGE | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.AGE is no |
| CR205 | ADAMCR-0205 | AGEU | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.AGEU is n |
| CR206 | ADAMCR-0206 | SEX | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.SEX is no |
| CR207 | ADAMCR-0207 | RACE | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.RACE is n |
| CR208 | ADAMCR-0208 | SUBJID | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.SUBJID is |
| CR209 | ADAMCR-0209 | SITEID | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.SITEID is |
| CR210 | ADAMCR-0210 | ARM | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.ARM is no |
| CR211 | ADAMCR-0211 | ABLFN | BASIC DATA STRUCTURE | ABLFN is not equal to 1 or null |
| CR212 | ADAMCR-0212 | ANLzzFN where zz is  | BASIC DATA STRUCTURE | ANLzzFN is not equal to 1 or null where zz is a zero-padded two-digit integer [0 |
| CR223 | ADAMCR-0223 | CHG | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, CHG is populated and is not equal |
| CR225 | ADAMCR-0225 | PCHG | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, PCHG is populated and is not equa |
| CR252 | ADAMCR-0252 | AVAL; AVALC | OCCURRENCE DATA STRUCTURE | AVAL is present or AVALC is present |
| CR254 | ADAMCR-0254 | PARAM | OCCURRENCE DATA STRUCTURE | PARAM is present |
| CR256 | ADAMCR-0256 |  | ALL | The values of USUBJID are not present in ADSL |
| CR261 | ADAMCR-0261 |  | OCCURRENCE DATA STRUCTURE | AEDECOD is not present |
| CR262 | ADAMCR-0262 |  | OCCURRENCE DATA STRUCTURE | AEBODSYS is not present |
| CR268 | ADAMCR-0268 | ADURU | OCCURRENCE DATA STRUCTURE | ADURN is populated and ADURU is not populated |
| CR269 | ADAMCR-0269 | TRTEMFL | OCCURRENCE DATA STRUCTURE | TRTEMFL is not equal to Y or null |
| CR270 | ADAMCR-0270 | PREFL | OCCURRENCE DATA STRUCTURE | PREFL is not equal to Y or null |
| CR271 | ADAMCR-0271 | FUPFL | OCCURRENCE DATA STRUCTURE | FUPFL is not equal to Y or null |
| CR272 | ADAMCR-0272 | AOCCzzFL where zz is | OCCURRENCE DATA STRUCTURE | A variable with a prefix of AOCC and a suffix of FL is not equal to Y or null |
| CR278 | ADAMCR-0278 |  | OCCURRENCE DATA STRUCTURE | AESER is not present |
| CR279 | ADAMCR-0279 | AESEVN | OCCURRENCE DATA STRUCTURE | AESEVN is not equal to 1, 2, 3, or null |
| CR282 | ADAMCR-0282 | ASEVN | OCCURRENCE DATA STRUCTURE | ASEVN is not equal to 1, 2, 3, or null |
| CR304 | ADAMCR-0304 | SMQzzCD where zz is  | OCCURRENCE DATA STRUCTURE | SMQzzNAM is populated and SMQzzCD is not populated |
| CR305 | ADAMCR-0305 | SMQzzSC where zz is  | OCCURRENCE DATA STRUCTURE | SMQzzNAM is populated and SMQzzSC is not populated |
| CR306 | ADAMCR-0306 | SMQzzNAM where zz is | OCCURRENCE DATA STRUCTURE | SMQzzCD is populated and SMQzzNAM is not populated |
| CR307 | ADAMCR-0307 | SMQzzSC where zz is  | OCCURRENCE DATA STRUCTURE | SMQzzCD is populated and SMQzzSC is not populated |
| CR308 | ADAMCR-0308 | SMQzzNAM where zz is | OCCURRENCE DATA STRUCTURE | SMQzzSC is populated and SMQzzNAM is not populated |
| CR309 | ADAMCR-0309 | SMQzzCD where zz is  | OCCURRENCE DATA STRUCTURE | SMQzzSC is populated and SMQzzCD is not populated |
| CR312 | ADAMCR-0312 | SMQzzSC where zz is  | OCCURRENCE DATA STRUCTURE | SMQzzSC is not equal to BROAD or NARROW, where zz is a zero-padded two-digit int |
| CR313 | ADAMCR-0313 | SMQzzSCN where zz is | OCCURRENCE DATA STRUCTURE | SMQzzSCN is not equal to 1 or 2, where zz is a zero-padded two-digit integer [01 |
| CR320 | ADAMCR-0320 |  | SUBJECT LEVEL ANALYSIS DATASET | A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dat |
| CR321 | ADAMCR-0321 |  | SUBJECT LEVEL ANALYSIS DATASET | A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named |
| CR322 | ADAMCR-0322 | TRTPGy where y is an | BASIC DATA STRUCTURE; OCCURREN | Within a given value of TRTP, there is more than one value of TRTPGy, where y is |
| CR323 | ADAMCR-0323 | APHASEN | BASIC DATA STRUCTURE; OCCURREN | Within a study, there is more than one value of APHASEN for a given value of APH |
| CR324 | ADAMCR-0324 | APHASE | BASIC DATA STRUCTURE; OCCURREN | Within a study, there is more than one value of APHASE for a given value of APHA |
| CR325 | ADAMCR-0325 | ASPER | BASIC DATA STRUCTURE; OCCURREN | Within a value of APERIOD, there is more than one value of ASPER for a given val |
| CR326 | ADAMCR-0326 | ASPERC | BASIC DATA STRUCTURE; OCCURREN | Within a value of APERIOD, there is more than one value of ASPERC for a given va |
| CR327 | ADAMCR-0327 | AVALCATy where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of AVALCATy for a given value o |
| CR328 | ADAMCR-0328 | AVALCAyN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of AVALCAyN for a given value o |
| CR329 | ADAMCR-0329 | BASECATy where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BASECATy for a given value o |
| CR330 | ADAMCR-0330 | BASECAyN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BASECAyN for a given value o |
| CR331 | ADAMCR-0331 | CHGCATy where y is a | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of CHGCATy for a given value of |
| CR332 | ADAMCR-0332 | CHGCATyN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of CHGCATyN for a given value o |
| CR333 | ADAMCR-0333 | PCHGCATy where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of PCHGCATy for a given value o |
| CR334 | ADAMCR-0334 | PCHGCAyN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of PCHGCAyN for a given value o |
| CR335 | ADAMCR-0335 | CRITyFL where y is a | BASIC DATA STRUCTURE | CRITyFL is present and CRITy is not present |
| CR336 | ADAMCR-0336 | CRITy where y is an  | BASIC DATA STRUCTURE | CRITy is present and CRITyFL is not present |
| CR337 | ADAMCR-0337 | MCRITyML where y is  | BASIC DATA STRUCTURE | MCRITyML is present and MCRITy is not present |
| CR338 | ADAMCR-0338 | MCRITy where y is an | BASIC DATA STRUCTURE | MCRITy is present and MCRITyML is not present |
| CR339 | ADAMCR-0339 | MCRITy where y is an | BASIC DATA STRUCTURE | MCRITyML is populated and MCRITy is not populated |
| CR340 | ADAMCR-0340 | MCRITyML where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of MCRITyML for a given value o |
| CR341 | ADAMCR-0341 | MCRITyMN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of MCRITyMN for a given value o |
| CR342 | ADAMCR-0342 | ANRLO | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ANRLO for a given value of A |
| CR343 | ADAMCR-0343 | ANRLOC | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ANRLOC for a given value of  |
| CR344 | ADAMCR-0344 | ANRHI | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ANRHI for a given value of A |
| CR345 | ADAMCR-0345 | ANRHIC | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ANRHIC for a given value of  |
| CR346 | ADAMCR-0346 | R2AyLO where y is an | BASIC DATA STRUCTURE | R2AyLO is present and AyLO is not present |
| CR347 | ADAMCR-0347 | AyLO where y is an i | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of AyLO for a given value of Ay |
| CR348 | ADAMCR-0348 | AyLOC where y is an  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of AyLOC for a given value of A |
| CR349 | ADAMCR-0349 | R2AyHI where y is an | BASIC DATA STRUCTURE | R2AyHI is present and AyHI is not present |
| CR350 | ADAMCR-0350 | AyHI where y is an i | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of AyHI for a given value of Ay |
| CR351 | ADAMCR-0351 | AyHIC where y is an  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of AyHIC for a given value of A |
| CR352 | ADAMCR-0352 | AyIND where y is an  | BASIC DATA STRUCTURE | AyIND is present and AyLO, AyHI, AyLOC, and AyHIC are all not present |
| CR353 | ADAMCR-0353 | ByIND where y is an  | BASIC DATA STRUCTURE | BASETYPE is present, ByIND is populated, and ByIND is not equal to AyIND where A |
| CR354 | ADAMCR-0354 | ByIND where y is an  | BASIC DATA STRUCTURE | BASETYPE is not present, ByIND is populated, and ByIND is not equal to AyIND whe |
| CR355 | ADAMCR-0355 | REGIONy where y is a | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of REGIONy for a given value of REGIONyN, consideri |
| CR356 | ADAMCR-0356 | REGIONyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of REGIONyN for a given value of REGIONy, consideri |
| CR359 | ADAMCR-0359 | DTHCAUS | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of DTHCAUS for a given value of DTHCAUSN, consideri |
| CR360 | ADAMCR-0360 | DTHCAUSN | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of DTHCAUSN for a given value of DTHCAUS, consideri |
| CR361 | ADAMCR-0361 | ASTDT | ALL | The value of ASTDT is greater than the value of AENDT, considering only those ro |
| CR362 | ADAMCR-0362 | ASTDTM | ALL | The value of ASTDTM is greater than the value of AENDTM, considering only those  |
| CR364 | ADAMCR-0364 | DOSEON; DOSCUMA | OCCURRENCE DATA STRUCTURE | DOSEON or DOSCUMA is present and DOSEU is not present |
| CR365 | ADAMCR-0365 |  | SUBJECT LEVEL ANALYSIS DATASET | SDTM.EX is present and neither TRTEDT or TRTEDTM are present |
| CR366 | ADAMCR-0366 | RANDFL | SUBJECT LEVEL ANALYSIS DATASET | RANDDT is not present when RANDFL is equal to Y for at least one record |
| CR367 | ADAMCR-0367 | ACTARM | SUBJECT LEVEL ANALYSIS DATASET | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.ACTARM is |
| CR368 | ADAMCR-0368 |  | SUBJECT LEVEL ANALYSIS DATASET | TRxxAGy is not present and both TRxxPGy and TRTxxA are present |
| CR369 | ADAMCR-0369 | *DT; *DTM | BASIC DATA STRUCTURE; OCCURREN | *DTF is populated and neither *DT nor *DTM is populated |
| CR370 | ADAMCR-0370 | *TM; *DTM | BASIC DATA STRUCTURE; OCCURREN | *TMF is populated and neither *TM nor *DTM is populated |
| CR373 | ADAMCR-0373 |  | BASIC DATA STRUCTURE; MEDICAL  | CNSR is not present |
| CR374 | ADAMCR-0374 |  | BASIC DATA STRUCTURE; MEDICAL  | AVAL is not present |
| CR375 | ADAMCR-0375 | *GRy where y is an i | ALL | On a given record, a variable which has a suffix of GRyN is populated and a vari |
| CR376 | ADAMCR-0376 | *GRyN where y is an  | ALL | On a given record, a variable which has a suffix of GRy is populated and a varia |
| CR377 | ADAMCR-0377 | TRTxxPN where xx is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTxxP is populated and TRTxxPN is not populated |
| CR378 | ADAMCR-0378 | TRTxxP where xx is a | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTxxPN is populated and TRTxxP is not populated |
| CR379 | ADAMCR-0379 | BTOXGR | BASIC DATA STRUCTURE | On a given record, BTOXGRN is populated and BTOXGR is not populated |
| CR380 | ADAMCR-0380 | BTOXGRN | BASIC DATA STRUCTURE | On a given record, BTOXGR is populated and BTOXGRN is not populated |
| CR381 | ADAMCR-0381 | BTOXGR | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BTOXGR for a given value of  |
| CR382 | ADAMCR-0382 | BTOXGRN | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BTOXGRN for a given value of |
| CR383 | ADAMCR-0383 | BTOXGRL | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BTOXGRL for a given value of |
| CR384 | ADAMCR-0384 | BTOXGRLN | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BTOXGRLN for a given value o |
| CR385 | ADAMCR-0385 | BTOXGRLN | BASIC DATA STRUCTURE | On a given record, BTOXGRL is populated and BTOXGRLN is not populated |
| CR386 | ADAMCR-0386 | BTOXGRL | BASIC DATA STRUCTURE | On a given record, BTOXGRLN is populated and BTOXGRL is not populated |
| CR387 | ADAMCR-0387 | BTOXGRH | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BTOXGRH for a given value of |
| CR388 | ADAMCR-0388 | BTOXGRHN | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BTOXGRHN for a given value o |
| CR389 | ADAMCR-0389 | BTOXGRHN | BASIC DATA STRUCTURE | On a given record, BTOXGRH is populated and BTOXGRHN is not populated |
| CR390 | ADAMCR-0390 | BTOXGRH | BASIC DATA STRUCTURE | On a given record, BTOXGRHN is populated and BTOXGRH is not populated |
| CR391 | ADAMCR-0391 | ATOXGRN | BASIC DATA STRUCTURE; OCCURREN | On a given record, ATOXGR is populated and ATOXGRN is not populated |
| CR392 | ADAMCR-0392 | ATOXGR | BASIC DATA STRUCTURE; OCCURREN | On a given record, ATOXGRN is populated and ATOXGR is not populated |
| CR393 | ADAMCR-0393 | ATOXGRLN | BASIC DATA STRUCTURE | On a given record, ATOXGRL is populated and ATOXGRLN is not populated |
| CR394 | ADAMCR-0394 | ATOXGRL | BASIC DATA STRUCTURE | On a given record, ATOXGRLN is populated and ATOXGRL is not populated |
| CR395 | ADAMCR-0395 | ATOXGRL | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ATOXGRL for a given value of |
| CR396 | ADAMCR-0396 | ATOXGRLN | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ATOXGRLN for a given value o |
| CR397 | ADAMCR-0397 | ATOXGRHN | BASIC DATA STRUCTURE | On a given record, ATOXGRH is populated and ATOXGRHN is not populated |
| CR398 | ADAMCR-0398 | ATOXGRH | BASIC DATA STRUCTURE | On a given record, ATOXGRHN is populated and ATOXGRH is not populated |
| CR399 | ADAMCR-0399 | ATOXGRH | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ATOXGRH for a given value of |
| CR400 | ADAMCR-0400 | ATOXGRHN | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of ATOXGRHN for a given value o |
| CR401 | ADAMCR-0401 | AVAL; ATOXGRL | BASIC DATA STRUCTURE | AVAL is not populated or ATOXGRL is not populated, and ATOXDSCL is populated |
| CR402 | ADAMCR-0402 | AVAL; ATOXGRH | BASIC DATA STRUCTURE | AVAL is not populated or ATOXGRH is not populated, and ATOXDSCH is populated |
| CR403 | ADAMCR-0403 | ATOXDSCL | BASIC DATA STRUCTURE | Within a subject, there is more than one value of ATOXDSCL for a given value of  |
| CR405 | ADAMCR-0405 | ATOXDSCH | BASIC DATA STRUCTURE | Within a subject, there is more than one value of ATOXDSCH for a given value of  |
| CR407 | ADAMCR-0407 | ABLFL | BASIC DATA STRUCTURE | On a given record, ABLFN is populated and ABLFL is not populated |
| CR408 | ADAMCR-0408 | ABLFN | BASIC DATA STRUCTURE | On a given record, ABLFL is populated and ABLFN is not populated |
| CR409 | ADAMCR-0409 | ABLFN | BASIC DATA STRUCTURE | There is more than one value of ABLFN for a given value of ABLFL, considering on |
| CR410 | ADAMCR-0410 | ABLFL | BASIC DATA STRUCTURE | There is more than one value of ABLFL for a given value of ABLFN, considering on |
| CR411 | ADAMCR-0411 | ANLzzFL where zz is  | BASIC DATA STRUCTURE | On a given record, ANLzzFN is populated and ANLzzFL is not populated |
| CR412 | ADAMCR-0412 | ANLzzFN where zz is  | BASIC DATA STRUCTURE | On a given record, ANLzzFL is populated and ANLzzFN is not populated |
| CR413 | ADAMCR-0413 | ANLzzFN where zz is  | BASIC DATA STRUCTURE | There is more than one value of ANLzzFN for a given value of ANLzzFL, considerin |
| CR414 | ADAMCR-0414 | ANLzzFL where zz is  | BASIC DATA STRUCTURE | There is more than one value of ANLzzFL for a given value of ANLzzFN , consideri |
| CR415 | ADAMCR-0415 | TRTSEQP | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTSEQPN is populated and TRTSEQP is not populated |
| CR416 | ADAMCR-0416 | TRTSEQPN | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTSEQP is populated and TRTSEQPN is not populated |
| CR417 | ADAMCR-0417 | TRTSEQA | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTSEQAN is populated and TRTSEQA is not populated |
| CR418 | ADAMCR-0418 | TRTSEQAN | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTSEQA is populated and TRTSEQAN is not populated |
| CR419 | ADAMCR-0419 | TRxxPGy where xx is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRxxPGyN is populated and TRxxPGy is not populated |
| CR420 | ADAMCR-0420 | TRxxPGyN where xx is | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRxxPGy is populated and TRxxPGyN is not populated |
| CR421 | ADAMCR-0421 | TRxxAGy where xx is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRxxAGyN is populated and TRxxAGy is not populated |
| CR422 | ADAMCR-0422 | TRxxAGyN where xx is | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRxxAGy is populated and TRxxAGyN is not populated |
| CR423 | ADAMCR-0423 | TRTPGy where y is an | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTPGyN is populated and TRTPGy is not populated |
| CR424 | ADAMCR-0424 | TRTPGyN where y is a | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTPGy is populated and TRTPGyN is not populated |
| CR425 | ADAMCR-0425 | TRTAGyN where y is a | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTAGy is populated and TRTAGyN is not populated |
| CR426 | ADAMCR-0426 | TRTAGy where y is an | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTAGyN is populated and TRTAGy is not populated |
| CR427 | ADAMCR-0427 | TRTxxAN where xx is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTxxA is populated and TRTxxAN is not populated |
| CR428 | ADAMCR-0428 | TRTxxA where xx is a | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRTxxAN is populated and TRTxxA is not populated |
| CR429 | ADAMCR-0429 | APERIODC | BASIC DATA STRUCTURE; OCCURREN | On a given record, APERIOD is populated and APERIODC is not populated |
| CR430 | ADAMCR-0430 | APERIOD | BASIC DATA STRUCTURE; OCCURREN | On a given record, APERIODC is populated and APERIOD is not populated |
| CR431 | ADAMCR-0431 | APHASEN | BASIC DATA STRUCTURE; OCCURREN | On a given record, APHASE is populated and APHASEN is not populated |
| CR432 | ADAMCR-0432 | APHASE | BASIC DATA STRUCTURE; OCCURREN | On a given record, APHASEN is populated and APHASE is not populated |
| CR433 | ADAMCR-0433 | ASPERC | BASIC DATA STRUCTURE; OCCURREN | On a given record, ASPER is populated and ASPERC is not populated |
| CR434 | ADAMCR-0434 | ASPER | BASIC DATA STRUCTURE; OCCURREN | On a given record, ASPERC is populated and ASPER is not populated |
| CR435 | ADAMCR-0435 | ATPTN | BASIC DATA STRUCTURE; OCCURREN | On a given record, ATPT is populated and ATPTN is not populated |
| CR436 | ADAMCR-0436 | ATPT | BASIC DATA STRUCTURE; OCCURREN | On a given record, ATPTN is populated and ATPT is not populated |
| CR437 | ADAMCR-0437 | AVALCAyN where y is  | BASIC DATA STRUCTURE | On a given record, AVALCATy is populated and AVALCAyN is not populated |
| CR438 | ADAMCR-0438 | AVALCATy where y is  | BASIC DATA STRUCTURE | On a given record, AVALCAyN is populated and AVALCATy is not populated |
| CR439 | ADAMCR-0439 | BASECAyN where y is  | BASIC DATA STRUCTURE | On a given record, BASECATy is populated and BASECAyN is not populated |
| CR440 | ADAMCR-0440 | BASECATy where y is  | BASIC DATA STRUCTURE | On a given record, BASECAyN is populated and BASECATy is not populated |
| CR441 | ADAMCR-0441 | CHGCATyN where y is  | BASIC DATA STRUCTURE | On a given record, CHGCATy is populated and CHGCATyN is not populated |
| CR442 | ADAMCR-0442 | CHGCATy where y is a | BASIC DATA STRUCTURE | On a given record, CHGCATyN is populated and CHGCATy is not populated |
| CR443 | ADAMCR-0443 | PCHGCAyN where y is  | BASIC DATA STRUCTURE | On a given record, PCHGCATy is populated and PCHGCAyN is not populated |
| CR444 | ADAMCR-0444 | PCHGCATy where y is  | BASIC DATA STRUCTURE | On a given record, PCHGCAyN is populated and PCHGCATy is not populated |
| CR445 | ADAMCR-0445 | PARCATyN where y is  | BASIC DATA STRUCTURE | On a given record, PARCATy is populated and PARCATyN is not populated |
| CR446 | ADAMCR-0446 | PARCATy where y is a | BASIC DATA STRUCTURE | On a given record, PARCATyN is populated and PARCATy is not populated |
| CR447 | ADAMCR-0447 | AVISIT | BASIC DATA STRUCTURE; OCCURREN | On a given record, AVISITN is populated and AVISIT is not populated |
| CR448 | ADAMCR-0448 | DTHCAUSN | SUBJECT LEVEL ANALYSIS DATASET | On a given record, DTHCAUS is populated and DTHCAUSN is not populated |
| CR449 | ADAMCR-0449 | DTHCAUS | SUBJECT LEVEL ANALYSIS DATASET | On a given record, DTHCAUSN is populated and DTHCAUS is not populated |
| CR450 | ADAMCR-0450 | MCRITyMN where y is  | BASIC DATA STRUCTURE | On a given record, MCRITyML is populated and MCRITyMN is not populated |
| CR451 | ADAMCR-0451 | MCRITyML where y is  | BASIC DATA STRUCTURE | On a given record, MCRITyMN is populated and MCRITyML is not populated |
| CR452 | ADAMCR-0452 | REGIONyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, REGIONy is populated and REGIONyN is not populated |
| CR453 | ADAMCR-0453 | REGIONy where y is a | SUBJECT LEVEL ANALYSIS DATASET | On a given record, REGIONyN is populated and REGIONy is not populated |
| CR454 | ADAMCR-0454 | SHIFTyN where y is a | BASIC DATA STRUCTURE | On a given record, SHIFTy is populated and SHIFTyN is not populated |
| CR455 | ADAMCR-0455 | SHIFTy where y is an | BASIC DATA STRUCTURE | On a given record, SHIFTyN is populated and SHIFTy is not populated |
| CR456 | ADAMCR-0456 | TRTAN | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTA is populated and TRTAN is not populated |
| CR457 | ADAMCR-0457 | TRTA | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTAN is populated and TRTA is not populated |
| CR458 | ADAMCR-0458 | TRTPN | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTP is populated and TRTPN is not populated |
| CR459 | ADAMCR-0459 | TRTP | BASIC DATA STRUCTURE; OCCURREN | On a given record, TRTPN is populated and TRTP is not populated |
| CR460 | ADAMCR-0460 | TSEQPGyN where y is  | BASIC DATA STRUCTURE; OCCURREN | On a given record, TSEQPGy is populated and TSEQPGyN is not populated |
| CR461 | ADAMCR-0461 | TSEQPGy where y is a | BASIC DATA STRUCTURE; OCCURREN | On a given record, TSEQPGyN is populated and TSEQPGy is not populated |
| CR462 | ADAMCR-0462 | TSEQAGyN where y is  | BASIC DATA STRUCTURE; OCCURREN | On a given record, TSEQAGy is populated and TSEQAGyN is not populated |
| CR463 | ADAMCR-0463 | TSEQAGy where y is a | BASIC DATA STRUCTURE; OCCURREN | On a given record, TSEQAGyN is populated and TSEQAGy is not populated |
| CR464 | ADAMCR-0464 | TRCMPGyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRCMPGy is populated and TRCMPGyN is not populated |
| CR465 | ADAMCR-0465 | TRCMPGy where y is a | SUBJECT LEVEL ANALYSIS DATASET | On a given record, TRCMPGyN is populated and TRCMPGy is not populated |
| CR466 | ADAMCR-0466 | STRATARN | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATAR is populated and STRATARN is not populated |
| CR467 | ADAMCR-0467 | STRATAR | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATARN is populated and STRATAR is not populated |
| CR468 | ADAMCR-0468 | STRATAR | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATAR for a given value of STRATARN, consideri |
| CR469 | ADAMCR-0469 | STRATARN | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATARN for a given value of STRATAR, consideri |
| CR470 | ADAMCR-0470 | STRATwRN where w is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATwR is populated and STRATwRN is not populated |
| CR471 | ADAMCR-0471 | STRATwR where w is a | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATwRN is populated and STRATwR is not populated |
| CR472 | ADAMCR-0472 | STRATwR where w is a | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATwR for a given value of STRATwRN, consideri |
| CR473 | ADAMCR-0473 | STRATwRN where w is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATwRN for a given value of STRATwR, consideri |
| CR474 | ADAMCR-0474 | STRATAVN | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATAV is populated and STRATAVN is not populated |
| CR475 | ADAMCR-0475 | STRATAV | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATAVN is populated and STRATAV is not populated |
| CR476 | ADAMCR-0476 | STRATAV | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATAV for a given value of STRATAVN, consideri |
| CR477 | ADAMCR-0477 | STRATAVN | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATAVN for a given value of STRATAV, consideri |
| CR478 | ADAMCR-0478 | STRATwVN where w is  | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATwV is populated and STRATwVN is not populated |
| CR479 | ADAMCR-0479 | STRATwV where w is a | SUBJECT LEVEL ANALYSIS DATASET | On a given record, STRATwVN is populated and STRATwV is not populated |
| CR480 | ADAMCR-0480 | STRATwV where w is a | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATwV for a given value of STRATwVN, consideri |
| CR481 | ADAMCR-0481 | STRATwVN where w is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of STRATwVN for a given value of STRATwV, consideri |
| CR482 | ADAMCR-0482 | BCHGCAyN where y is  | BASIC DATA STRUCTURE | On a given record, BCHGCATy is populated and BCHGCAyN is not populated |
| CR483 | ADAMCR-0483 | BCHGCATy where y is  | BASIC DATA STRUCTURE | On a given record, BCHGCAyN is populated and BCHGCATy is not populated |
| CR484 | ADAMCR-0484 | PBCHGCyN where y is  | BASIC DATA STRUCTURE | On a given record, PBCHGCAy is populated and PBCHGCyN is not populated |
| CR485 | ADAMCR-0485 | PBCHGCAy where y is  | BASIC DATA STRUCTURE | On a given record, PBCHGCyN is populated and PBCHGCAy is not populated |
| CR486 | ADAMCR-0486 | CRITyFN where y is a | BASIC DATA STRUCTURE; OCCURREN | On a given record, CRITyFL is populated and CRITyFN is not populated |
| CR487 | ADAMCR-0487 | CRITyFL where y is a | BASIC DATA STRUCTURE; OCCURREN | On a given record, CRITyFN is populated and CRITyFL is not populated |
| CR488 | ADAMCR-0488 | ONTRTFN | BASIC DATA STRUCTURE; OCCURREN | On a given record, ONTRTFL is populated and ONTRTFN is not populated |
| CR489 | ADAMCR-0489 | ONTRTFL | BASIC DATA STRUCTURE; OCCURREN | On a given record, ONTRTFN is populated and ONTRTFL is not populated |
| CR490 | ADAMCR-0490 | LVOTFN | BASIC DATA STRUCTURE; OCCURREN | On a given record, LVOTFL is populated and LVOTFN is not populated |
| CR491 | ADAMCR-0491 | LVOTFL | BASIC DATA STRUCTURE; OCCURREN | On a given record, LVOTFN is populated and LVOTFL is not populated |
| CR492 | ADAMCR-0492 | SHIFTy where y is an | BASIC DATA STRUCTURE | SHIFTy is present and all of the following variable pairs (BASECATy, AVALCATy),  |
| CR493 | ADAMCR-0493 | ANLzzFL where zz is  | BASIC DATA STRUCTURE | ANLzzFL is equal to "N" where zz is a zero-padded two-digit integer [01-99] |
| CR494 | ADAMCR-0494 | ABLFL | BASIC DATA STRUCTURE | ABLFL is equal to "N" |
| CR496 | ADAMCR-0496 |  | ALL | A dataset name does not start with "AD" when dataset class is not missing |
| CR497 | ADAMCR-0497 |  | ALL | A dataset name starts with "AD" when the dataset class is missing |
| CR498 | ADAMCR-0498 | APERIOD; ASPER | ALL | For every unique combination of a w value of ASPER and xx value of APERIOD in BD |
| CR499 | ADAMCR-0499 | ASPER | BASIC DATA STRUCTURE; OCCURREN | ASPER is present and APERIOD is not present |
| CR500 | ADAMCR-0500 | APHASE | BASIC DATA STRUCTURE; OCCURREN | There is a value of APHASE without a matching value in an ADSL variable APHASEw |
| CR501 | ADAMCR-0501 | APERIOD | BASIC DATA STRUCTURE; OCCURREN | On a given record, ASPER is populated and APERIOD is not populated |
| CR503 | ADAMCR-0503 | *DT | ALL | A variable ending in DT must contain "Date" in the label |
| CR504 | ADAMCR-0504 | *TM | ALL | A variable ending in TM must contain "Time" in the label |
| CR505 | ADAMCR-0505 | *DTM | ALL | A variable ending in DTM must contain "Datetime" in the label |
| CR506 | ADAMCR-0506 | *ADY | ALL | A variable ending in ADY must contain "Relative Day" in the label |
| CR507 | ADAMCR-0507 | *DTF | ALL | A variable ending in DTF must contain "Date Imputation Flag" in the label |
| CR508 | ADAMCR-0508 | *TMF | ALL | A variable ending in TMF must contain "Time Imputation Flag" in the label |
| CR509 | ADAMCR-0509 | *SDT | ALL | A variable ending in SDT must contain "Start Date" in the label |
| CR510 | ADAMCR-0510 | *STM | ALL | A variable ending in STM must contain "Start Time" in the label |
| CR511 | ADAMCR-0511 | *SDTM | ALL | A variable ending in SDTM must contain "Start Datetime" in the label |
| CR512 | ADAMCR-0512 | *SDY | ALL | A variable ending in SDY must contain "Relative Start Day" in the label |
| CR513 | ADAMCR-0513 | *SDTF | ALL | A variable ending in SDTF must contain "Start Date Imputation Flag" in the label |
| CR514 | ADAMCR-0514 | *STMF | ALL | A variable ending in STMF must contain "Start Time Imputation Flag" in the label |
| CR515 | ADAMCR-0515 | *EDT | ALL | A variable ending in EDT must contain "End Date" in the label |
| CR516 | ADAMCR-0516 | *ETM | ALL | A variable ending in ETM must contain "End Time" in the label |
| CR517 | ADAMCR-0517 | *EDTM | ALL | A variable ending in EDTM must contain "End Datetime" in the label |
| CR518 | ADAMCR-0518 | *EDY | ALL | A variable ending in EDY must contain "Relative End Day" in the label |
| CR519 | ADAMCR-0519 | *EDTF | ALL | A variable ending in EDTF must contain "End Date Imputation Flag" in the label |
| CR520 | ADAMCR-0520 | *ETMF | ALL | A variable ending in ETMF must contain "End Time Imputation Flag" in the label |
| CR521 | ADAMCR-0521 |  | ALL | A variable which has a suffix of GRyN is present and a variable with the same ro |
| CR522 | ADAMCR-0522 | BTOXGRN | BASIC DATA STRUCTURE | BTOXGRN is present and BTOXGR is not present |
| CR523 | ADAMCR-0523 | ATOXGRLN | BASIC DATA STRUCTURE | ATOXGRLN is present and ATOXGRL is not present |
| CR524 | ADAMCR-0524 | ATOXGRHN | BASIC DATA STRUCTURE | ATOXGRHN is present and ATOXGRH is not present |
| CR525 | ADAMCR-0525 | ABLFN | BASIC DATA STRUCTURE | ABLFN is present and ABLFL is not present |
| CR526 | ADAMCR-0526 | ANLzzFN where zz is  | BASIC DATA STRUCTURE; OCCURREN | ANLzzFN is present and ANLzzFL is not present |
| CR527 | ADAMCR-0527 | TRTSEQPN | SUBJECT LEVEL ANALYSIS DATASET | TRTSEQPN is present and TRTSEQP is not present |
| CR528 | ADAMCR-0528 | TRTSEQAN | SUBJECT LEVEL ANALYSIS DATASET | TRTSEQAN is present and TRTSEQA is not present |
| CR529 | ADAMCR-0529 | AESEVN | OCCURRENCE DATA STRUCTURE | AESEVN is present and AESEV is not present |
| CR530 | ADAMCR-0530 | ASEVN | OCCURRENCE DATA STRUCTURE | ASEVN is present and ASEV is not present |
| CR531 | ADAMCR-0531 | SEVGRyN where y is a | OCCURRENCE DATA STRUCTURE | SEVGRyN is present and SEVGRy is not present |
| CR532 | ADAMCR-0532 | AERELN | OCCURRENCE DATA STRUCTURE | AERELN is present and AEREL is not present |
| CR533 | ADAMCR-0533 | ARELN | OCCURRENCE DATA STRUCTURE | ARELN is present and AREL is not present |
| CR534 | ADAMCR-0534 | RELGRyN where y is a | OCCURRENCE DATA STRUCTURE | RELGRyN is present and RELGRy is not present |
| CR535 | ADAMCR-0535 | AETOXGRN | OCCURRENCE DATA STRUCTURE | AETOXGRN is present and AETOXGR is not present |
| CR536 | ADAMCR-0536 | ATOXGRN | OCCURRENCE DATA STRUCTURE | ATOXGRN is present and ATOXGR is not present |
| CR537 | ADAMCR-0537 | TOXGGRyN where y is  | OCCURRENCE DATA STRUCTURE | TOXGGRyN is present and TOXGGRy is not present |
| CR538 | ADAMCR-0538 | SMQzzSCN where zz is | OCCURRENCE DATA STRUCTURE | SMQzzSCN is present and SMQzzSC is not present |
| CR539 | ADAMCR-0539 | APERIODC | BASIC DATA STRUCTURE; OCCURREN | APERIODC is present and APERIOD is not present |
| CR540 | ADAMCR-0540 | APHASEN | BASIC DATA STRUCTURE; OCCURREN | APHASEN is present and APHASE is not present |
| CR541 | ADAMCR-0541 | ASPERC | BASIC DATA STRUCTURE; OCCURREN | ASPERC is present and ASPER is not present |
| CR542 | ADAMCR-0542 | ATPTN | BASIC DATA STRUCTURE; OCCURREN | ATPTN is present and ATPT is not present |
| CR543 | ADAMCR-0543 | AVALCAyN where y is  | BASIC DATA STRUCTURE | AVALCAyN is present and AVALCATy is not present |
| CR544 | ADAMCR-0544 | BASECAyN where y is  | BASIC DATA STRUCTURE | BASECAyN is present and BASECATy is not present |
| CR545 | ADAMCR-0545 | CHGCATyN where y is  | BASIC DATA STRUCTURE | CHGCATyN is present and CHGCATy is not present |
| CR546 | ADAMCR-0546 | PCHGCAyN where y is  | BASIC DATA STRUCTURE | PCHGCAyN is present and PCHGCATy is not present |
| CR547 | ADAMCR-0547 | PARCATyN where y is  | BASIC DATA STRUCTURE | PARCATyN is present and PARCATy is not present |
| CR548 | ADAMCR-0548 | AVISITN | BASIC DATA STRUCTURE; OCCURREN | AVISITN is present and AVISIT is not present |
| CR549 | ADAMCR-0549 | BTOXGRHN | BASIC DATA STRUCTURE | BTOXGRHN is present and BTOXGRH is not present |
| CR550 | ADAMCR-0550 | BTOXGRLN | BASIC DATA STRUCTURE | BTOXGRLN is present and BTOXGRL is not present |
| CR551 | ADAMCR-0551 | DTHCAUSN | SUBJECT LEVEL ANALYSIS DATASET | DTHCAUSN is present and DTHCAUS is not present |
| CR552 | ADAMCR-0552 | MCRITyMN where y is  | BASIC DATA STRUCTURE; OCCURREN | MCRITyMN is present and MCRITyML is not present |
| CR553 | ADAMCR-0553 | REGIONyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | REGIONyN is present and REGIONy is not present |
| CR554 | ADAMCR-0554 | SHIFTyN where y is a | BASIC DATA STRUCTURE; OCCURREN | SHIFTyN is present and SHIFTy is not present |
| CR555 | ADAMCR-0555 | TRTAN | BASIC DATA STRUCTURE; OCCURREN | TRTAN is present and TRTA is not present |
| CR556 | ADAMCR-0556 | TRTPN | BASIC DATA STRUCTURE; OCCURREN | TRTPN is present and TRTP is not present |
| CR557 | ADAMCR-0557 | TSEQPGyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | TSEQPGyN is present and TSEQPGy is not present |
| CR558 | ADAMCR-0558 | TSEQAGyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | TSEQAGyN is present and TSEQAGy is not present |
| CR559 | ADAMCR-0559 | TRCMPGyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | TRCMPGyN is present and TRCMPGy is not present |
| CR560 | ADAMCR-0560 | STRATARN | SUBJECT LEVEL ANALYSIS DATASET | STRATARN is present and STRATAR is not present |
| CR561 | ADAMCR-0561 | STRATwRN where w is  | SUBJECT LEVEL ANALYSIS DATASET | STRATwRN is present and STRATwR is not present for the same value of "w" |
| CR562 | ADAMCR-0562 | STRATAVN | SUBJECT LEVEL ANALYSIS DATASET | STRATAVN is present and STRATAV is not present |
| CR563 | ADAMCR-0563 | STRATwVN where w is  | SUBJECT LEVEL ANALYSIS DATASET | STRATwVN is present and STRATwV is not present for the same value of "w" |
| CR565 | ADAMCR-0565 | BCHGCAyN where y is  | BASIC DATA STRUCTURE | BCHGCAyN is present and BCHGCATy is not present |
| CR566 | ADAMCR-0566 | PBCHGCyN where y is  | BASIC DATA STRUCTURE | PBCHGCyN is present and PBCHGCAy is not present |
| CR567 | ADAMCR-0567 | CRITyFN where y is a | BASIC DATA STRUCTURE; OCCURREN | CRITyFN is present and CRITyFL is not present |
| CR568 | ADAMCR-0568 | ONTRTFN | BASIC DATA STRUCTURE; OCCURREN | ONTRTFN is present and ONTRTFL is not present |
| CR569 | ADAMCR-0569 | LVOTFN | BASIC DATA STRUCTURE; OCCURREN | LVOTFN is present and LVOTFL is not present |
| CR570 | ADAMCR-0570 | PxxSw where xx is an | SUBJECT LEVEL ANALYSIS DATASET | PxxSw is present and TRTxxP is not present for the same "xx" value |
| CR571 | ADAMCR-0571 | PxxSwSDT where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwSDT is present and TRTxxP is not present for the same "xx" value |
| CR572 | ADAMCR-0572 | PxxSwSDM where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwSDM is present and TRTxxP is not present for the same "xx" value |
| CR573 | ADAMCR-0573 | PxxSwSTM where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwSTM is present and TRTxxP is not present for the same "xx" value |
| CR574 | ADAMCR-0574 | PxxSwSDF where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwSDF is present and TRTxxP is not present for the same "xx" value |
| CR575 | ADAMCR-0575 | PxxSwSTF where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwSTF is present and TRTxxP is not present for the same "xx" value |
| CR576 | ADAMCR-0576 | PxxSwEDT where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwEDT is present and TRTxxP is not present for the same "xx" value |
| CR577 | ADAMCR-0577 | PxxSwEDM where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwEDM is present and TRTxxP is not present for the same "xx" value |
| CR578 | ADAMCR-0578 | PxxSwETM where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwETM is present and TRTxxP is not present for the same "xx" value |
| CR579 | ADAMCR-0579 | PxxSwEDF where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwEDF is present and TRTxxP is not present for the same "xx" value |
| CR580 | ADAMCR-0580 | PxxSwETF where xx is | SUBJECT LEVEL ANALYSIS DATASET | PxxSwETF is present and TRTxxP is not present for the same "xx" value |
| CR581 | ADAMCR-0581 |  | BASIC DATA STRUCTURE | None of TRTP, TRTPGy, TRTA, and TRTAGy are present and none of the character tre |
| CR582 | ADAMCR-0582 | BCHG | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, BCHG is populated and is not equa |
| CR583 | ADAMCR-0583 | BCHGCATy where y is  | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of BCHGCATy for a  |
| CR584 | ADAMCR-0584 | BCHGCATy where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BCHGCATy for a given value o |
| CR585 | ADAMCR-0585 | BCHGCAyN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of BCHGCAyN for a given value o |
| CR586 | ADAMCR-0586 | PBCHG | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, PBCHG is populated and is not equ |
| CR587 | ADAMCR-0587 | PBCHGCAy where y is  | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of PBCHGCAy for a  |
| CR588 | ADAMCR-0588 | PBCHGCAy where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of PBCHGCAy for a given value o |
| CR589 | ADAMCR-0589 | PBCHGCyN where y is  | BASIC DATA STRUCTURE | Within a parameter, there is more than one value of PBCHGCyN for a given value o |
| CR590 | ADAMCR-0590 | ALL | ALL | A variable is present with the same name as a variable present in ADSL but the v |
| CR591 | ADAMCR-0591 | ALL | ALL | A variable is present with the same name as a variable present in ADSL but the v |
| CR592 | ADAMCR-0592 | APERSDT | ALL | On a given record, the value of APERSDT is not equal to the value of variable AP |
| CR593 | ADAMCR-0593 | APERSTM | ALL | On a given record, the value of APERSTM is not equal to the value of variable AP |
| CR594 | ADAMCR-0594 | APERSDTM | ALL | On a given record, the value of APERSDTM is not equal to the value of variable A |
| CR595 | ADAMCR-0595 | APEREDT | ALL | On a given record, the value of APEREDT is not equal to the value of variable AP |
| CR596 | ADAMCR-0596 | APERETM | ALL | On a given record, the value of APERETM is not equal to the value of variable AP |
| CR597 | ADAMCR-0597 | APEREDTM | ALL | On a given record, the value of APEREDTM is not equal to the value of variable A |
| CR598 | ADAMCR-0598 | ASPRST | ALL | On a given record, the value of ASPRSDT is not equal to the value of variable Px |
| CR599 | ADAMCR-0599 | ASPRSTM | ALL | On a given record, the value of ASPRSTM is not equal to the value of variable Px |
| CR600 | ADAMCR-0600 | ASPRSDTM | ALL | On a given record, the value of ASPRSDTM is not equal to the value of variable P |
| CR601 | ADAMCR-0601 | ASPREDT | ALL | On a given record, the value of ASPREDT is not equal to the value of variable Px |
| CR602 | ADAMCR-0602 | ASPRETM | ALL | On a given record, the value of ASPRETM is not equal to the value of variable Px |
| CR603 | ADAMCR-0603 | ASPREDTM | ALL | On a given record, the value of ASPREDTM is not equal to the value of variable P |
| CR604 | ADAMCR-0604 | PHSDT | ALL | On a given record, APHASEN is not present and the value of PHSDT is not equal to |
| CR605 | ADAMCR-0605 | PHSDT | ALL | On a given record, APHASEN is present and the value of PHSDT is not equal to the |
| CR606 | ADAMCR-0606 | PHSTM | ALL | On a given record, APHASEN is not present and the value of PHSTM is not equal to |
| CR607 | ADAMCR-0607 | PHSTM | ALL | On a given record, APHASEN is present and the value of PHSTM is not equal to the |
| CR608 | ADAMCR-0608 | PHSDTM | ALL | On a given record, APHASEN is not present and the value of PHSDTM is not equal t |
| CR609 | ADAMCR-0609 | PHSDTM | ALL | On a given record, APHASEN is present and the value of PHSDTM is not equal to th |
| CR610 | ADAMCR-0610 | PHEDT | ALL | On a given record, APHASEN is not present and the value of PHEDT is not equal to |
| CR611 | ADAMCR-0611 | PHEDT | ALL | On a given record, APHASEN is present and the value of PHEDT is not equal to the |
| CR612 | ADAMCR-0612 | PHETM | ALL | On a given record, APHASEN is not present and the value of PHETM is not equal to |
| CR613 | ADAMCR-0613 | PHETM | ALL | On a given record, APHASEN is present and the value of PHETM is not equal to the |
| CR614 | ADAMCR-0614 | PHEDTM | ALL | On a given record, APHASEN is not present and the value of PHEDTM is not equal t |
| CR615 | ADAMCR-0615 | PHEDTM | ALL | On a given record, APHASEN is present and the value of PHEDTM is not equal to th |
| CR616 | ADAMCR-0616 | TRCMPGy where y is a | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRCMPGy for a given value of TRCMPGyN, where y i |
| CR617 | ADAMCR-0617 | TRCMPGyN where y is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRCMPGyN for a given value of TRCMPGy, where y i |
| CR620 | ADAMCR-0620 |  | OCCURRENCE DATA STRUCTURE | AETERM is not present |
| CR621 | ADAMCR-0621 |  | OCCURRENCE DATA STRUCTURE | TRTEMFL is not present |
| CR622 | ADAMCR-0622 | AEOCCUR | OCCURRENCE DATA STRUCTURE | AEOCCUR is present |
| CR623 | ADAMCR-0623 |  | OCCURRENCE DATA STRUCTURE | AESEQ is not present |
| CR624 | ADAMCR-0624 |  | OCCURRENCE DATA STRUCTURE | AELLT is not present |
| CR625 | ADAMCR-0625 |  | OCCURRENCE DATA STRUCTURE | AEBDSYCD is not present |
| CR626 | ADAMCR-0626 |  | OCCURRENCE DATA STRUCTURE | AELLTCD is not present |
| CR627 | ADAMCR-0627 |  | OCCURRENCE DATA STRUCTURE | AEHLT is not present |
| CR628 | ADAMCR-0628 |  | OCCURRENCE DATA STRUCTURE | AEHLTCD is not present |
| CR629 | ADAMCR-0629 |  | OCCURRENCE DATA STRUCTURE | AEHLGT is not present |
| CR630 | ADAMCR-0630 |  | OCCURRENCE DATA STRUCTURE | AEHLGTCD is not present |
| CR631 | ADAMCR-0631 |  | OCCURRENCE DATA STRUCTURE | AEPTCD is not present |
| CR632 | ADAMCR-0632 |  | OCCURRENCE DATA STRUCTURE | AESOC is not present |
| CR633 | ADAMCR-0633 |  | OCCURRENCE DATA STRUCTURE | AESOCCD is not present |
| CR634 | ADAMCR-0634 |  | OCCURRENCE DATA STRUCTURE | AESTDTC is not present |
| CR635 | ADAMCR-0635 |  | OCCURRENCE DATA STRUCTURE | ASTDT is not present |
| CR636 | ADAMCR-0636 |  | OCCURRENCE DATA STRUCTURE | AEENDTC is not present |
| CR637 | ADAMCR-0637 |  | OCCURRENCE DATA STRUCTURE | AENDT is not present |
| CR638 | ADAMCR-0638 |  | OCCURRENCE DATA STRUCTURE | ASTDY is not present |
| CR639 | ADAMCR-0639 |  | OCCURRENCE DATA STRUCTURE | AENDY is not present |
| CR640 | ADAMCR-0640 |  | OCCURRENCE DATA STRUCTURE | SUPPAE.QNAM=AETRTEM is present but AETRTEM is not present |
| CR641 | ADAMCR-0641 | AE.AESTDY | OCCURRENCE DATA STRUCTURE | AE.AESTDY is present but AESTDY is not present |
| CR642 | ADAMCR-0642 | AE.AEENDY | OCCURRENCE DATA STRUCTURE | AE.AEENDY is present but AEENDY is not present |
| CR643 | ADAMCR-0643 | AE.AEDUR | OCCURRENCE DATA STRUCTURE | AE.AEDUR is present but AEDUR is not present |
| CR644 | ADAMCR-0644 | AE.AESEV | OCCURRENCE DATA STRUCTURE | AE.AESEV is present but AESEV is not present |
| CR645 | ADAMCR-0645 | AE.AETOXGR | OCCURRENCE DATA STRUCTURE | AE.AETOXGR is present but AETOXGR is not present |
| CR646 | ADAMCR-0646 | AE.AEACN | OCCURRENCE DATA STRUCTURE | AE.AEACN is present and populated on at least one record but AEACN is not presen |
| CR647 | ADAMCR-0647 | TRTEMFL | OCCURRENCE DATA STRUCTURE | TREMxxFL is Y but TRTEMFL is not Y |
| CR648 | ADAMCR-0648 | TRTEMFL | OCCURRENCE DATA STRUCTURE | TRTEMwFL is Y but TRTEMFL is not Y |
| CR649 | ADAMCR-0649 | ONTRTFL | OCCURRENCE DATA STRUCTURE | ONTRxxFL is Y but ONTRTFL is not Y |
| CR650 | ADAMCR-0650 | ONTRTFL | OCCURRENCE DATA STRUCTURE | ONTRTwFL is Y but ONTRTFL is not Y |
| CR651 | ADAMCR-0651 | ONTRxxFL where xx is | OCCURRENCE DATA STRUCTURE | ONTRxxFL is present but ONTRTFL is not present |
| CR652 | ADAMCR-0652 | ONTRTwFL where w is  | OCCURRENCE DATA STRUCTURE | ONTRTwFL is present but ONTRTFL is not present |
| CR653 | ADAMCR-0653 | SRCDOM | OCCURRENCE DATA STRUCTURE | SRCDOM is present |
| CR654 | ADAMCR-0654 | SRCSEQ | OCCURRENCE DATA STRUCTURE | SRCSEQ is present |
| CR655 | ADAMCR-0655 |  | BASIC DATA STRUCTURE | DOSEA is not present |
| CR656 | ADAMCR-0656 |  | BASIC DATA STRUCTURE | DOSEU is not present |
| CR657 | ADAMCR-0657 |  | BASIC DATA STRUCTURE | AVISIT is not present |
| CR658 | ADAMCR-0658 | NCAXFN | BASIC DATA STRUCTURE | NCAXFN is present and has a value that is not 1 or null |
| CR659 | ADAMCR-0659 | PKSUMXFN | BASIC DATA STRUCTURE | PKSUMXFN is present and has a value that is not 1 or null |
| CR660 | ADAMCR-0660 | METABFL | BASIC DATA STRUCTURE | METABFL is present and has a value that is not Y or null |
| CR661 | ADAMCR-0661 | ADOSEDUR | BASIC DATA STRUCTURE | ADOSEDUR is present and NDOSEDUR or DOSEDURU is not present |
| CR662 | ADAMCR-0662 | ARRLT; NRRLT | BASIC DATA STRUCTURE | TMPCTDF is populated and ARRLT and NRRLT are not populated |
| CR663 | ADAMCR-0663 | COHORT | BASIC DATA STRUCTURE | On a given record, COHORTN is populated and COHORT is not populated |
| CR664 | ADAMCR-0664 | COHORTN | BASIC DATA STRUCTURE | On a given record, COHORT is populated and COHORTN is present and not populated |
| CR665 | ADAMCR-0665 | COHORTN | BASIC DATA STRUCTURE | COHORTN is present and COHORT is not present |
| CR666 | ADAMCR-0666 | DOSEP; DOSEA | BASIC DATA STRUCTURE | DOSPCTDF is populated and DOSEP and DOSEA are not populated |
| CR667 | ADAMCR-0667 | TRTA | BASIC DATA STRUCTURE | DOSPCTDF is populated and TRTA is not populated |
| CR668 | ADAMCR-0668 | ACYCLEC | BASIC DATA STRUCTURE | On a given record, ACYCLE is populated and ACYCLEC is not populated |
| CR669 | ADAMCR-0669 | ACYCLE | BASIC DATA STRUCTURE | On a given record, ACYCLEC is populated and ACYCLE is present and not populated |
| CR670 | ADAMCR-0670 | ACYCLEC | BASIC DATA STRUCTURE | ACYCLEC is present and ACYCLE is not present |
| CR671 | ADAMCR-0671 |  | BASIC DATA STRUCTURE | AVALU is not present |
| CR706 | ADAMCR-0706 | ASPER | ALL | For every unique xx value of APERIOD in BDS or OCCDS datasets, there is not a va |
| CR707 | ADAMCR-0707 | ASPER | ALL | For every unique w value of ASPER in BDS or OCCDS datasets, there is not a varia |
| CR709 | ADAMCR-0709 | ALL | ALL | Labels for ADaM variables do not match the standard labels for ADaM variables li |
| CR710 | ADAMCR-0710 | *RFL | BASIC DATA STRUCTURE | A variable with a suffix of RFL has a value that is not Y, N, or null |
| CR711 | ADAMCR-0711 | *PFL | BASIC DATA STRUCTURE | A variable with a suffix of PFL has a value that is not Y, N, or null |
| CR712 | ADAMCR-0712 | *RFN | BASIC DATA STRUCTURE | A variable with a suffix of RFN has a value that is not 1, 0 or null |
| CR713 | ADAMCR-0713 | *PFN | BASIC DATA STRUCTURE | A variable with a suffix of PFN has a value that is not 1, 0, or null |
| CR714 | ADAMCR-0714 |  | ALL | There is more than one value of a variable which has a suffix of GRyN for a give |
| CR715 | ADAMCR-0715 |  | ALL | There is more than one value of a variable which has a suffix of GRy for a given |
| CR716 | ADAMCR-0716 | *TM | ALL | A variable with a suffix of TM is not a numeric variable excluding SDTM variable |
| CR717 | ADAMCR-0717 | TRTxxPN where xx is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTxxPN for a given value of TRTxxP, considering |
| CR718 | ADAMCR-0718 | TRTxxP where xx is a | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTxxP for a given value of TRTxxPN, considering |
| CR719 | ADAMCR-0719 |  | ALL | None of the subject-level or record-level treatment variables defined in the IG  |
| CR720 | ADAMCR-0720 | TRTP | ALL | A non-missing value of TRTP is not equal to at least one value of the character  |
| CR721 | ADAMCR-0721 | TRTPN | BASIC DATA STRUCTURE | There is more than one value of TRTPN for a given value of TRTP, considering onl |
| CR722 | ADAMCR-0722 | TRTP | BASIC DATA STRUCTURE | There is more than one value of TRTP for a given value of TRTPN, considering onl |
| CR723 | ADAMCR-0723 | TRTAN | BASIC DATA STRUCTURE | There is more than one value of TRTAN for a given value of TRTA, considering onl |
| CR724 | ADAMCR-0724 | TRTA | BASIC DATA STRUCTURE | There is more than one value of TRTA for a given value of TRTAN, considering onl |
| CR725 | ADAMCR-0725 | APERIODC | ALL | There is more than one value of APERIODC for a given value of APERIOD, consideri |
| CR726 | ADAMCR-0726 | APERIOD | ALL | There is more than one value of APERIOD for a given value of APERIODC, consideri |
| CR727 | ADAMCR-0727 | AVISITN | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of AVISITN for a g |
| CR728 | ADAMCR-0728 | AVISIT | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of AVISIT for a gi |
| CR730 | ADAMCR-0730 | PARCATy where y is a | BASIC DATA STRUCTURE | There is more than one value of PARCATy for a given value of PARCATyN, consideri |
| CR731 | ADAMCR-0731 | PARCATyN where y is  | BASIC DATA STRUCTURE | There is more than one value of PARCATyN for a given value of PARCATy, consideri |
| CR732 | ADAMCR-0732 | BASE | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, there is more than one value of B |
| CR733 | ADAMCR-0733 | BASEC | BASIC DATA STRUCTURE | Within a given value of PARAMCD for a subject, there is more than one value of B |
| CR736 | ADAMCR-0736 | SHIFTy where y is an | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of SHIFTy for a gi |
| CR737 | ADAMCR-0737 | SHIFTyN where y is a | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of SHIFTyN for a g |
| CR738 | ADAMCR-0738 | PARAM | BASIC DATA STRUCTURE | There is more than one value of PARAM for a given value of PARAMCD, considering  |
| CR739 | ADAMCR-0739 | PARAMCD | BASIC DATA STRUCTURE | There is more than one value of PARAMCD for a given value of PARAM, considering  |
| CR740 | ADAMCR-0740 | PARAM | BASIC DATA STRUCTURE | Within a dataset, there is more than one value of PARAM for a given value of PAR |
| CR741 | ADAMCR-0741 | PARAMN | BASIC DATA STRUCTURE | Within a dataset, there is more than one value of PARAMN for a given value of PA |
| CR742 | ADAMCR-0742 | AVALC | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of AVALC for a giv |
| CR743 | ADAMCR-0743 | AVAL | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of AVAL for a give |
| CR746 | ADAMCR-0746 | SRCDOM | BASIC DATA STRUCTURE; OCCURREN | SRCDOM has a value that is not an SDTM domain name, ADaM dataset name, or null |
| CR748 | ADAMCR-0748 | AVALCATy where y is  | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of AVALCATy for a  |
| CR749 | ADAMCR-0749 | BASECATy where y is  | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of BASECATy for a  |
| CR750 | ADAMCR-0750 | CHGCATy where y is a | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of CHGCATy for a g |
| CR751 | ADAMCR-0751 | PCHGCATy where y is  | BASIC DATA STRUCTURE | Within a given value of PARAMCD, there is more than one value of PCHGCATy for a  |
| CR752 | ADAMCR-0752 | TRTSEQP | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTSEQP for a given value of TRTSEQPN, consideri |
| CR753 | ADAMCR-0753 | TRTSEQPN | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTSEQPN for a given value of TRTSEQP, consideri |
| CR754 | ADAMCR-0754 | TRTSEQA | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTSEQA for a given value of TRTSEQAN, consideri |
| CR755 | ADAMCR-0755 | TRTSEQAN | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTSEQAN for a given value of TRTSEQA, consideri |
| CR756 | ADAMCR-0756 | TRxxPGy where xx is  | SUBJECT LEVEL ANALYSIS DATASET | Within a given value of TRTxxP, there is more than one value of TRxxPGy, where x |
| CR757 | ADAMCR-0757 | TRxxPGy where xx is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRxxPGy for a given value of TRxxPGyN, where xx  |
| CR758 | ADAMCR-0758 | TRxxPGyN where xx is | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRxxPGyN for a given value of TRxxPGy, where xx  |
| CR759 | ADAMCR-0759 | TRxxAGy where xx is  | SUBJECT LEVEL ANALYSIS DATASET | Within a given value of TRTxxA, there is more than one value of TRxxAGy, where x |
| CR760 | ADAMCR-0760 | TRxxAGy where xx is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRxxAGy for a given value of TRxxAGyN, where xx  |
| CR761 | ADAMCR-0761 | TRxxAGyN where xx is | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRxxAGyN for a given value of TRxxAGy, where xx  |
| CR762 | ADAMCR-0762 | TRTPGy where y is an | BASIC DATA STRUCTURE | There is more than one value of TRTPGy for a given value of TRTPGyN, where y is  |
| CR763 | ADAMCR-0763 | TRTPGyN where y is a | BASIC DATA STRUCTURE | There is more than one value of TRTPGyN for a given value of TRTPGy, where y is  |
| CR764 | ADAMCR-0764 | TRTPGy where y is an | BASIC DATA STRUCTURE | TRTPGy is present and TRTA is present but TRTAGy is not present, where y is an i |
| CR765 | ADAMCR-0765 | TRTAGy where y is an | BASIC DATA STRUCTURE | There is more than one value of TRTAGy for a given value of TRTAGyN, where y is  |
| CR766 | ADAMCR-0766 | TRTAGyN where y is a | BASIC DATA STRUCTURE | There is more than one value of TRTAGyN for a given value of TRTAGy, where y is  |
| CR767 | ADAMCR-0767 | TRTxxAN where xx is  | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTxxAN for a given value of TRTxxA, where xx is |
| CR768 | ADAMCR-0768 | TRTxxA where xx is a | SUBJECT LEVEL ANALYSIS DATASET | There is more than one value of TRTxxA for a given value of TRTxxAN, where xx is |
| CR769 | ADAMCR-0769 | AESEV | OCCURRENCE DATA STRUCTURE | There is more than one value of AESEV for a given value of AESEVN, considering o |
| CR770 | ADAMCR-0770 | AESEVN | OCCURRENCE DATA STRUCTURE | There is more than one value of AESEVN for a given value of AESEV, considering o |
| CR771 | ADAMCR-0771 | ASEV | OCCURRENCE DATA STRUCTURE | There is more than one value of ASEV for a given value of ASEVN, considering onl |
| CR772 | ADAMCR-0772 | ASEVN | OCCURRENCE DATA STRUCTURE | There is more than one value of ASEVN for a given value of ASEV, considering onl |
| CR773 | ADAMCR-0773 | SEVGRy where y is an | OCCURRENCE DATA STRUCTURE | There is more than one value of SEVGRy for a given value of SEVGRyN, considering |
| CR774 | ADAMCR-0774 | SEVGRyN where y is a | OCCURRENCE DATA STRUCTURE | There is more than one value of SEVGRyN for a given value of SEVGRy, considering |
| CR775 | ADAMCR-0775 | AEREL | OCCURRENCE DATA STRUCTURE | There is more than one value of AEREL for a given value of AERELN, considering o |
| CR776 | ADAMCR-0776 | AERELN | OCCURRENCE DATA STRUCTURE | There is more than one value of AERELN for a given value of AEREL, considering o |
| CR777 | ADAMCR-0777 | AREL | OCCURRENCE DATA STRUCTURE | There is more than one value of AREL for a given value of ARELN, considering onl |
| CR778 | ADAMCR-0778 | ARELN | OCCURRENCE DATA STRUCTURE | There is more than one value of ARELN for a given value of AREL, considering onl |
| CR779 | ADAMCR-0779 | RELGRy where y is an | OCCURRENCE DATA STRUCTURE | There is more than one value of RELGRy for a given value of RELGRyN, considering |
| CR780 | ADAMCR-0780 | RELGRyN where y is a | OCCURRENCE DATA STRUCTURE | There is more than one value of RELGRyN for a given value of RELGRy, considering |
| CR781 | ADAMCR-0781 | AETOXGR | OCCURRENCE DATA STRUCTURE | There is more than one value of AETOXGR for a given value of AETOXGRN, consideri |
| CR782 | ADAMCR-0782 | AETOXGRN | OCCURRENCE DATA STRUCTURE | There is more than one value of AETOXGRN for a given value of AETOXGR, consideri |
| CR783 | ADAMCR-0783 | ATOXGR | BASIC DATA STRUCTURE; OCCURREN | There is more than one value of ATOXGR for a given value of ATOXGRN, considering |
| CR784 | ADAMCR-0784 | ATOXGRN | BASIC DATA STRUCTURE; OCCURREN | There is more than one value of ATOXGRN for a given value of ATOXGR, considering |
| CR785 | ADAMCR-0785 | TOXGGRy where y is a | OCCURRENCE DATA STRUCTURE | There is more than one value of TOXGGRy for a given value of TOXGGRyN, consideri |
| CR786 | ADAMCR-0786 | TOXGGRyN where y is  | OCCURRENCE DATA STRUCTURE | There is more than one value of TOXGGRyN for a given value of TOXGGRy, consideri |
| CR787 | ADAMCR-0787 | SMQzzSC where zz is  | OCCURRENCE DATA STRUCTURE | There is more than one value of SMQzzSC for a given value of SMQzzSCN, consideri |
| CR788 | ADAMCR-0788 | SMQzzSCN where zz is | OCCURRENCE DATA STRUCTURE | There is more than one value of SMQzzSCN for a given value of SMQzzSC, consideri |
| CR792 | ADAMCR-0792 | *SDT | BASIC DATA STRUCTURE | On a given record, a variable with a suffix of SDT has a value greater than a va |
| CR793 | ADAMCR-0793 | *SDT | BASIC DATA STRUCTURE | On a given record, a variable with a suffix of STM has a value greater than a va |
| CR794 | ADAMCR-0794 | *SDT | BASIC DATA STRUCTURE | On a given record, a variable with a suffix of SDTM has a value greater than a v |
| CR891 | ADAMCR-0891 | COHORTN | BASIC DATA STRUCTURE | On a given record, there is more than one value of COHORTN for a given value of  |
| CR892 | ADAMCR-0892 | COHORT | BASIC DATA STRUCTURE | On a given record, there is more than one value of COHORT for a given value of C |
| CR893 | ADAMCR-0893 | ACYCLEC | BASIC DATA STRUCTURE | On a given record, there is more than one value of ACYCLEC for a given value of  |
| CR894 | ADAMCR-0894 | ACYCLE | BASIC DATA STRUCTURE | On a given record, there is more than one value of ACYCLE for a given value of A |
| CR895 | ADAMCR-0895 | CRITyFL where y is a | BASIC DATA STRUCTURE | A variable with a prefix of CRIT, a suffix of FL and containing either a one-dig |
| CR896 | ADAMCR-0896 | AWU | BASIC DATA STRUCTURE | AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present |
| CR897 | ADAMCR-0897 | TRTA | ALL | A non-missing value of TRTA is not equal to at least one value of the character  |
| CR898 | ADAMCR-0898 | --SEQ | ALL | For a value of AD*.USUBJID that is a value of --.USUBJID, a value of AD*.--SEQ i |
| CR899 | ADAMCR-0899 |  | ALL | AD*.USUBJID equals --.USUBJID, AD*.--SEQ equals --.--SEQ, and the values of a va |
