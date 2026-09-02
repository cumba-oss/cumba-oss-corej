# ADaM Conformance Rules v2.0 — ADaMIG v1.0 Conversion Report

Conversion report for the ADaMIG v1.0 rule set.

## Summary

| Status | Count | % |
|--------|-------|---|
| Fully Executable | 185 | 62% |
| Template (RuleGenerator) | 106 | 35% |
| Manual | 7 | 2% |
| **Total** | **298** | |

---

## Template Rules (106 rules)

Rules handled by `RuleGenerator` via wildcard expansion at runtime.

### Value Validation (6 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0005 | *FL | A variable with a suffix of FL has a value that is not Y, N or null |
| ADAMCR-0006 | *FN | A variable with a suffix of FL is present and a variable with the same root and a suffix of FN has a value that is not  0, 1 or null |
| ADAMCR-0039 | *DTF | A variable with a suffix of DTF has a value that is not within Controlled Terminology for DATEFL |
| ADAMCR-0040 | *TMF | A variable with a suffix of TMF has a value that is not within Controlled Terminology for TIMEFL |
| ADAMCR-0312 | SMQzzSC | SMQzzSC is not equal to BROAD or NARROW, where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0313 | SMQzzSCN | SMQzzSCN is not equal to 1 or 2, where zz is a zero-padded two-digit integer [01-99] |

### Variable Type / Format (6 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0041 | *DT | A numeric variable with a suffix of DT does not have a date format |
| ADAMCR-0042 | *TM | A numeric variable with a suffix of TM does not have a time format, excluding ARELTM and variables with a suffix of DTM |
| ADAMCR-0043 | *DTM | A numeric variable with a suffix of DTM does not have a datetime format |
| ADAMCR-0058 | *DT | A variable with a suffix of DT is not a numeric variable |
| ADAMCR-0059 | *TM | A variable with a suffix of TM is not a numeric variable |
| ADAMCR-0060 | *DTM | A variable with a suffix of DTM is not a numeric variable |

### Variable Existence Pairing (20 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0007 | *FL, *FN | A variable with a suffix of FN is present but a variable with the same root and a suffix of FL is not present |
| ADAMCR-0048 | *FL | A variable with a suffix of FL is not present in ADSL |
| ADAMCR-0064 | TRTxxA, TRTxxAN | TRTxxAN is present and TRTxxA is not present |
| ADAMCR-0066 | TRxxPGy, TRxxPGyN | A variable with a prefix of TR, containing PG and a suffix of N is present and a variable with the same root without a suffix of N is not present |
| ADAMCR-0070 | TRxxAGy, TRxxAGyN | A variable with a prefix of TR, containing AG with a suffix of N is present and a variable with the same root without a suffix of N is not present |
| ADAMCR-0075 | TRTxxP, TRTxxPN | TRTxxPN is present and TRTxxP is not present |
| ADAMCR-0078 | TRTxxP, TRxxSDT | At least one TRTxxP is present where xx is greater than 01 and TRxxSDT is not present |
| ADAMCR-0079 | TRTxxP, TRxxEDT | At least one TRTxxP is present where xx is greater than 01 and TRxxEDT is not present |
| ADAMCR-0080 | TRTxxA, TRTxxP | TRTxxA is present and TRTxxP is not present |
| ADAMCR-0081 | TRTxxP | TRTxxP is present and xx is greater than 01 and TRT{xx-1}P is not present |
| ADAMCR-0097 | TRTPGy, TRTPGyN | TRTPGyN is present and TRTPGy is not present |
| ADAMCR-0156 | CRITy, CRITyFL | A variable with a prefix of CRIT, a suffix of FL and containing a one-digit number (CRITyFL) is present and a variable with the same root without a suffix of FL (CRITy) is not present |
| ADAMCR-0157 | CRITy, CRITyFL | A variable with a prefix of CRIT and a suffix of a one-digit number (CRITy) is present and a variable with the same root with a suffix of FL (CRITyFL) is not present |
| ADAMCR-0201 | TRTAGy, TRTAGyN | TRTAGyN is present and TRTAGy is not present |
| ADAMCR-0239 | TRTAGy, TRTPGy | TRTPGy is present and TRTA is present but TRTAGy is not present, where y is a single-digit integer [1-9] |
| ADAMCR-0335 | CRITy, CRITyFL | CRITyFL is present and CRITy is not present |
| ADAMCR-0336 | CRITy, CRITyFL | CRITy is present and CRITyFL is not present |
| ADAMCR-0368 | TRTxxA, TRxxAGy, TRxxPGy | TRxxAGy is not present and both TRxxPGy and TRTxxA are present |
| ADAMCR-0651 | ONTRxxFL | ONTRxxFL is present but ONTRTFL is not present |
| ADAMCR-0652 | ONTRTwFL | ONTRTwFL is present but ONTRTFL is not present |

### Record-Level Populated Pairing (10 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0012 | *FL, *FN | A variable with a suffix of FL is equal to null and a variable with the same root and a suffix of FN is not equal to null |
| ADAMCR-0137 | CRITy, CRITyFL | CRITyFL is populated and CRITy is not populated |
| ADAMCR-0304 | SMQzzCD, SMQzzNAM | SMQzzNAM is populated and SMQzzCD is not populated |
| ADAMCR-0305 | SMQzzNAM, SMQzzSC | SMQzzNAM is populated and SMQzzSC is not populated |
| ADAMCR-0306 | SMQzzCD, SMQzzNAM | SMQzzCD is populated and SMQzzNAM is not populated |
| ADAMCR-0307 | SMQzzCD, SMQzzSC | SMQzzCD is populated and SMQzzSC is not populated |
| ADAMCR-0308 | SMQzzNAM, SMQzzSC | SMQzzSC is populated and SMQzzNAM is not populated |
| ADAMCR-0309 | SMQzzCD, SMQzzSC | SMQzzSC is populated and SMQzzCD is not populated |
| ADAMCR-0369 | *DT, *DTF, *DTM | *DTF is populated and neither *DT nor *DTM is populated |
| ADAMCR-0370 | *DTM, *TM, *TMF | *TMF is populated and neither *TM nor *DTM is populated |

### Value Comparison (Start > End) (7 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0098 | *EDY, *SDY | On a given record, a variable with a suffix of SDY has a value greater than a value of a variable with the same root and a suffix of EDY, and both variables are populated |
| ADAMCR-0099 | *ENDY, *STDY | On a given record, a variable with a suffix of STDY has a value greater than a value of a variable with the same root and a suffix of ENDY, and both variables are populated |
| ADAMCR-0121 | *EDT, *SDT | The value of a variable with a suffix of SDT is greater than the value of a variable with the same root and a suffix of EDT, considering only rows on which both variables are populated |
| ADAMCR-0122 | *EDTM, *SDTM | The value of a variable with a suffix of SDTM is greater than the value of a variable with the same root and a suffix of EDTM, considering only rows on which both variables are populated |
| ADAMCR-0792 | *EDT, *SDT | On a given record, a variable with a suffix of SDT has a value greater than a value of a variable with the same root and a suffix of EDT, and both variables are populated |
| ADAMCR-0793 | *ETM, *STM | On a given record, a variable with a suffix of STM has a value greater than a value of a variable with the same root and a suffix of ETM, and both variables are populated |
| ADAMCR-0794 | *EDTM, *SDTM | On a given record, a variable with a suffix of SDTM has a value greater than a value of a variable with the same root and a suffix of EDTM, and both variables are populated |

### Uniqueness / 1:1 Relationship (32 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0076 | TRTxxP, TRTxxPN | There is more than one value of TRTxxPN for a given value of TRTxxP |
| ADAMCR-0077 | TRTxxP, TRTxxPN | There is more than one value of TRTxxP for a given value of TRTxxPN |
| ADAMCR-0124 | PARCATy | There is more than one value of PARCATy for a given value of PARAMCD |
| ADAMCR-0125 | PARCATy, PARCATyN | There is more than one value of PARCATy for a given value of PARCATyN |
| ADAMCR-0126 | PARCATy, PARCATyN | There is more than one value of PARCATyN for a given value of PARCATy |
| ADAMCR-0135 | SHIFTy, SHIFTyN | Within a given value of PARAMCD, there is more than one value of SHIFTy for a given value of SHIFTyN |
| ADAMCR-0136 | SHIFTy, SHIFTyN | Within a given value of PARAMCD, there is more than one value of SHIFTyN for a given value of SHIFTy |
| ADAMCR-0151 | CRITy | Within a given value of PARAMCD, there is more than one value of CRITy |
| ADAMCR-0221 | AVALCATy | Within a given value of PARAMCD, there is more than one value of AVALCATy for a given value of AVAL |
| ADAMCR-0222 | BASECATy | Within a given value of PARAMCD, there is more than one value of BASECATy for a given value of BASE and y |
| ADAMCR-0224 | CHGCATy | Within a given value of PARAMCD, there is more than one value of CHGCATy for a given value of CHG and y |
| ADAMCR-0226 | PCHGCATy | Within a given value of PARAMCD, there is more than one value of PCHGCATy for a given value of PCHG and y |
| ADAMCR-0231 | TRTxxP, TRxxPGy | Within a given value of TRTxxP, there is more than one value of TRxxPGy, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9] |
| ADAMCR-0232 | TRxxPGy, TRxxPGyN | There is more than one value of TRxxPGy for a given value of TRxxPGyN, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9] |
| ADAMCR-0233 | TRxxPGy, TRxxPGyN | There is more than one value of TRxxPGyN for a given value of TRxxPGy, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9] |
| ADAMCR-0234 | TRTxxA, TRxxAGy | Within a given value of TRTxxA, there is more than one value of TRxxAGy, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9] |
| ADAMCR-0235 | TRxxAGy, TRxxAGyN | There is more than one value of TRxxAGy for a given value of TRxxAGyN, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9] |
| ADAMCR-0236 | TRxxAGy, TRxxAGyN | There is more than one value of TRxxAGyN for a given value of TRxxAGy, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9] |
| ADAMCR-0237 | TRTPGy, TRTPGyN | There is more than one value of TRTPGy for a given value of TRTPGyN, where y is a single-digit integer [1-9] |
| ADAMCR-0238 | TRTPGy, TRTPGyN | There is more than one value of TRTPGyN for a given value of TRTPGy, where y is a single-digit integer [1-9] |
| ADAMCR-0240 | TRTAGy, TRTAGyN | There is more than one value of TRTAGy for a given value of TRTAGyN, where y is a single-digit integer [1-9] |
| ADAMCR-0241 | TRTAGy, TRTAGyN | There is more than one value of TRTAGyN for a given value of TRTAGy, where y is a single-digit integer [1-9] |
| ADAMCR-0242 | TRTxxA, TRTxxAN | There is more than one value of TRTxxAN for a given value of TRTxxA, where xx is an integer [01-99, zero-padded] |
| ADAMCR-0243 | TRTxxA, TRTxxAN | There is more than one value of TRTxxA for a given value of TRTxxAN, where xx is an integer [01-99, zero-padded] |
| ADAMCR-0285 | SEVGRy, SEVGRyN | There is more than one value of SEVGRy for a given value of SEVGRyN |
| ADAMCR-0286 | SEVGRy, SEVGRyN | There is more than one value of SEVGRyN for a given value of SEVGRy |
| ADAMCR-0291 | RELGRy, RELGRyN | There is more than one value of RELGRy for a given value of RELGRyN |
| ADAMCR-0292 | RELGRy, RELGRyN | There is more than one value of RELGRyN for a given value of RELGRy |
| ADAMCR-0297 | TOXGGRy, TOXGGRyN | There is more than one value of TOXGGRy for a given value of TOXGGRyN |
| ADAMCR-0298 | TOXGGRy, TOXGGRyN | There is more than one value of TOXGGRyN for a given value of TOXGGRy |
| ADAMCR-0310 | SMQzzSC, SMQzzSCN | There is more than one value of SMQzzSC for a given value of SMQzzSCN |
| ADAMCR-0311 | SMQzzSC, SMQzzSCN | There is more than one value of SMQzzSCN for a given value of SMQzzSC |

### Cross-Dataset / Library Comparison (5 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0002 | — | A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels |
| ADAMCR-0085 | — | A variable is present with the same name as a variable present in ADSL but the variables do not have identical labels |
| ADAMCR-0086 | — | A variable is present with the same name as a variable present in ADSL but the variables do not have identical formats |
| ADAMCR-0199 | — | A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types |
| ADAMCR-0200 | — | A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types |

### Dynamic Index Lookup (3 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0102 | TRTxxP | For every unique xx value of APERIOD, there is not an ADSL variable TRTxxP |
| ADAMCR-0103 | TRxxSDT | For every unique xx value of APERIOD, there is not an ADSL variable TRxxSDT |
| ADAMCR-0104 | TRxxEDT | For every unique xx value of APERIOD, there is not an ADSL variable TRxxEDT |

### Calculation (2 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0133 | AyLO, R2AyLO | R2AyLO is not equal to AVAL divided by AyLO |
| ADAMCR-0134 | AyHI, R2AyHI | R2AyHI is not equal to AVAL divided by AyHI |

### Date/Time Part Comparison (2 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0044 | *DTM, *TM | A variable with a suffix of TM and a variable with a suffix of DTM with the same root name have different time values |
| ADAMCR-0045 | *DT, *DTM | A variable with a suffix of DT and a variable with a suffix of DTM with the same root name have different date values |

### Complex / Multi-Variable (13 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0010 | *FL, *FN | A variable with a suffix of FL is equal to Y and a variable with the same root and a suffix of FN is not equal to 1 |
| ADAMCR-0011 | *FL, *FN | A variable with a suffix of FL is equal to N and a variable with the same root and a suffix of FN is not equal to 0 |
| ADAMCR-0033 | *RFL | A variable with a suffix of RFL has a value that is not Y or null |
| ADAMCR-0034 | *PFL | A variable with a suffix of PFL has a value that is not Y or null |
| ADAMCR-0035 | *RFN | A variable with a suffix of RFN has a value that is not 1 or null |
| ADAMCR-0036 | *PFN | A variable with a suffix of PFN has a value that is not 1 or null |
| ADAMCR-0046 | *DY | A variable with a suffix of DY has a value of zero |
| ADAMCR-0084 | — | TRTEDT is not equal to the maximum value of all TRxxEDT variables |
| ADAMCR-0178 | ANLzzFL | ANLzzFL is not equal to Y or null where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0212 | ANLzzFN | ANLzzFN is not equal to 1 or null where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0272 | AOCCzzFL | A variable with a prefix of AOCC and a suffix of FL is not equal to Y or null |
| ADAMCR-0647 | TREMxxFL | TREMxxFL is Y but TRTEMFL is not Y |
| ADAMCR-0649 | ONTRxxFL | ONTRxxFL is Y but ONTRTFL is not Y |

---

## Fully Executable (185 rules)

Rules with concrete variable names and complete Check conditions.

### Domain Presence (1 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0001 | ADSL dataset does not exist |

### Dataset Metadata (2 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0320 | A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset" |
| ADAMCR-0321 | A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL |

### Variable Presence (44 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0047 | SITEID is not present within ADSL |
| ADAMCR-0049 | AGE is not present within ADSL |
| ADAMCR-0050 | AGEU is not present within ADSL |
| ADAMCR-0051 | SEX is not present within ADSL |
| ADAMCR-0052 | RACE is not present within ADSL |
| ADAMCR-0055 | SUBJID is not present within ADSL |
| ADAMCR-0061 | SDTM.EX is present and neither TRTSDT or TRTSDTM are present |
| ADAMCR-0071 | ARM is not present within ADSL |
| ADAMCR-0072 | TRT01P is not present within ADSL |
| ADAMCR-0088 | STUDYID is not present |
| ADAMCR-0089 | USUBJID is not present |
| ADAMCR-0090 | TRTP is not present |
| ADAMCR-0194 | PARAM is not present |
| ADAMCR-0195 | PARAMCD is not present |
| ADAMCR-0198 | AVAL is not present and AVALC is not present |
| ADAMCR-0261 | AEDECOD is not present |
| ADAMCR-0262 | AEBODSYS is not present |
| ADAMCR-0278 | AESER is not present |
| ADAMCR-0365 | SDTM.EX is present and neither TRTEDT or TRTEDTM are present |
| ADAMCR-0373 | CNSR is not present |
| ADAMCR-0374 | AVAL is not present |
| ADAMCR-0620 | AETERM is not present |
| ADAMCR-0621 | TRTEMFL is not present |
| ADAMCR-0623 | AESEQ is not present |
| ADAMCR-0624 | AELLT is not present |
| ADAMCR-0625 | AEBDSYCD is not present |
| ADAMCR-0626 | AELLTCD is not present |
| ADAMCR-0627 | AEHLT is not present |
| ADAMCR-0628 | AEHLTCD is not present |
| ADAMCR-0629 | AEHLGT is not present |
| ADAMCR-0630 | AEHLGTCD is not present |
| ADAMCR-0631 | AEPTCD is not present |
| ADAMCR-0632 | AESOC is not present |
| ADAMCR-0633 | AESOCCD is not present |
| ADAMCR-0634 | AESTDTC is not present |
| ADAMCR-0635 | ASTDT is not present |
| ADAMCR-0636 | AEENDTC is not present |
| ADAMCR-0637 | AENDT is not present |
| ADAMCR-0638 | ASTDY is not present |
| ADAMCR-0639 | AENDY is not present |
| ADAMCR-0655 | DOSEA is not present |
| ADAMCR-0656 | DOSEU is not present |
| ADAMCR-0657 | AVISIT is not present |
| ADAMCR-0671 | AVALU is not present |

### Variable Metadata (31 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0013 | The length of a variable name exceeds 8 characters |
| ADAMCR-0014 | A variable name does not start with a letter (A-Z) |
| ADAMCR-0015 | A variable name contains a character other than letters (A-Z), underscores (_), or numerals (0-9) |
| ADAMCR-0016 | The length of a variable label is greater than 40 characters |
| ADAMCR-0111 | ARELTM is present and ARELTMU is not present |
| ADAMCR-0113 | ARELTMU is present and ARELTM is not present |
| ADAMCR-0160 | AWU is present and both AWLO and AWHI are not present |
| ADAMCR-0163 | BTOXGR is present and ATOXGR is not present |
| ADAMCR-0164 | BTOXGR is present and ABLFL is not present |
| ADAMCR-0166 | BNRIND is present and ANRIND is not present |
| ADAMCR-0167 | BNRIND is present and ABLFL is not present |
| ADAMCR-0180 | SRCDOM has a value that is not an SDTM domain name or null |
| ADAMCR-0252 | AVAL is present or AVALC is present |
| ADAMCR-0254 | PARAM is present |
| ADAMCR-0622 | AEOCCUR is present |
| ADAMCR-0640 | SUPPAE.QNAM=AETRTEM is present but AETRTEM is not present |
| ADAMCR-0641 | AE.AESTDY is present but AESTDY is not present |
| ADAMCR-0642 | AE.AEENDY is present but AEENDY is not present |
| ADAMCR-0643 | AE.AEDUR is present but AEDUR is not present |
| ADAMCR-0644 | AE.AESEV is present but AESEV is not present |
| ADAMCR-0645 | AE.AETOXGR is present but AETOXGR is not present |
| ADAMCR-0646 | AE.AEACN is present and populated on at least one record but AEACN is not present |
| ADAMCR-0653 | SRCDOM is present |
| ADAMCR-0654 | SRCSEQ is present |
| ADAMCR-0658 | NCAXFN is present and has a value that is not 1 or null |
| ADAMCR-0659 | PKSUMXFN is present and has a value that is not 1 or null |
| ADAMCR-0661 | ADOSEDUR is present and NDOSEDUR or DOSEDURU is not present |
| ADAMCR-0665 | COHORTN is present and COHORT is not present |
| ADAMCR-0670 | ACYCLEC is present and ACYCLE is not present |
| ADAMCR-0898 | For a value of AD*.USUBJID that is a value of --.USUBJID, a value of AD*.--SEQ is not a value of --.--SEQ |
| ADAMCR-0899 | AD*.USUBJID equals --.USUBJID, AD*.--SEQ equals --.--SEQ, and the values of a variable with prefix -- which is present in both datasets are not equal |

### Uniqueness / 1:1 Relationship (42 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0037 | There is more than one value of a variable which has a suffix of GRyN for a given value of a variable with the same root name and suffix of GRy |
| ADAMCR-0038 | There is more than one value of a variable which has a suffix of GRy for a given value of a variable with the same root name and suffix of GRyN |
| ADAMCR-0054 | Within ADSL there is more than one record for a unique value of USUBJID |
| ADAMCR-0092 | There is more than one value of TRTPN for a given value of TRTP |
| ADAMCR-0093 | There is more than one value of TRTP for a given value of TRTPN |
| ADAMCR-0095 | There is more than one value of TRTAN for a given value of TRTA |
| ADAMCR-0096 | There is more than one value of TRTA for a given value of TRTAN |
| ADAMCR-0105 | There is more than one value of APERIODC for a given value of APERIOD |
| ADAMCR-0106 | There is more than one value of APERIOD for a given value of APERIODC |
| ADAMCR-0109 | Within a given value of PARAMCD, there is more than one value of AVISITN for a given value of AVISIT |
| ADAMCR-0110 | Within a given value of PARAMCD, there is more than one value of AVISIT for a given value of AVISITN |
| ADAMCR-0117 | Within a given value of PARAMCD, there is more than one value of ATPT for a given value of ATPTN |
| ADAMCR-0118 | Within a given value of PARAMCD, there is more than one value of ATPTN for a given value of ATPT |
| ADAMCR-0123 | There is more than one value of PARAMTYP for a given value of PARAMCD |
| ADAMCR-0129 | Within a given value of PARAMCD for a subject, there is more than one value of BASE for a given value of BASEC |
| ADAMCR-0130 | Within a given value of PARAMCD for a subject, there is more than one value of BASEC for a given value of BASE |
| ADAMCR-0141 | There is more than one value of PARAM for a given value of PARAMCD |
| ADAMCR-0142 | There is more than one value of PARAMCD for a given value of PARAM |
| ADAMCR-0146 | Within a dataset, there is more than one value of PARAM for a given value of PARAMN |
| ADAMCR-0147 | Within a dataset, there is more than one value of PARAMN for a given value of PARAM |
| ADAMCR-0149 | Within a given value of PARAMCD, there is more than one value of AVALC for a given value of AVAL |
| ADAMCR-0150 | Within a given value of PARAMCD, there is more than one value of AVAL for a given value of AVALC |
| ADAMCR-0227 | There is more than one value of TRTSEQP for a given value of TRTSEQPN |
| ADAMCR-0228 | There is more than one value of TRTSEQPN for a given value of TRTSEQP |
| ADAMCR-0229 | There is more than one value of TRTSEQA for a given value of TRTSEQAN |
| ADAMCR-0230 | There is more than one value of TRTSEQAN for a given value of TRTSEQA |
| ADAMCR-0280 | There is more than one value of AESEV for a given value of AESEVN |
| ADAMCR-0281 | There is more than one value of AESEVN for a given value of AESEV |
| ADAMCR-0283 | There is more than one value of ASEV for a given value of ASEVN |
| ADAMCR-0284 | There is more than one value of ASEVN for a given value of ASEV |
| ADAMCR-0287 | There is more than one value of AEREL for a given value of AERELN |
| ADAMCR-0288 | There is more than one value of AERELN for a given value of AEREL |
| ADAMCR-0289 | There is more than one value of AREL for a given value of ARELN |
| ADAMCR-0290 | There is more than one value of ARELN for a given value of AREL |
| ADAMCR-0293 | There is more than one value of AETOXGR for a given value of AETOXGRN |
| ADAMCR-0294 | There is more than one value of AETOXGRN for a given value of AETOXGR |
| ADAMCR-0295 | There is more than one value of ATOXGR for a given value of ATOXGRN |
| ADAMCR-0296 | There is more than one value of ATOXGRN for a given value of ATOXGR |
| ADAMCR-0891 | On a given record, there is more than one value of COHORTN for a given value of COHORT |
| ADAMCR-0892 | On a given record, there is more than one value of COHORT for a given value of COHORTN |
| ADAMCR-0893 | On a given record, there is more than one value of ACYCLEC for a given value of ACYCLE |
| ADAMCR-0894 | On a given record, there is more than one value of ACYCLE for a given value of ACYCLEC |

### Cross-Dataset (2 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0053 | The value of USUBJID is not present in SDTM.DM |
| ADAMCR-0256 | The values of USUBJID are not present in ADSL |

### Conditional Presence (6 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0112 | ARELTM is populated and ARELTMU is not populated |
| ADAMCR-0159 | AWTDIFF is populated and AWTARGET is not populated |
| ADAMCR-0268 | ADURN is populated and ADURU is not populated |
| ADAMCR-0663 | On a given record, COHORTN is populated and COHORT is not populated |
| ADAMCR-0667 | DOSPCTDF is populated and TRTA is not populated |
| ADAMCR-0668 | On a given record, ACYCLE is populated and ACYCLEC is not populated |

### Value Set Validation (17 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0019 | COMPLFL is present and has a value that is not Y or N |
| ADAMCR-0020 | FASFL is present and has a value that is not Y or N |
| ADAMCR-0021 | ITTFL is present and has a value that is not Y or N |
| ADAMCR-0022 | PPROTFL is present and has a value that is not Y or N |
| ADAMCR-0023 | SAFFL is present and has a value that is not Y or N |
| ADAMCR-0024 | RANDFL is present and has a value that is not Y or N |
| ADAMCR-0025 | ENRLFL is present and has a value that is not Y or N |
| ADAMCR-0026 | COMPLFN is present and has a value that is not 1 or 0 |
| ADAMCR-0027 | FASFN is present and has a value that is not 1 or 0 |
| ADAMCR-0028 | ITTFN is present and has a value that is not 1 or 0 |
| ADAMCR-0029 | PPROTFN is present and has a value that is not 1 or 0 |
| ADAMCR-0030 | SAFFN is present and has a value that is not 1 or 0 |
| ADAMCR-0031 | RANDFN is present and has a value that is not 1 or 0 |
| ADAMCR-0032 | ENRLFN is present and has a value that is not 1 or 0 |
| ADAMCR-0279 | AESEVN is not equal to 1, 2, 3, or null |
| ADAMCR-0282 | ASEVN is not equal to 1, 2, 3, or null |
| ADAMCR-0660 | METABFL is present and has a value that is not Y or null |

### Value Check (26 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0018 | Labels for ADaM variables do not match the standard labels for ADaM variables listed in the implementation guide that cannot be modified (with the exception of 1) variables whose names contain indexes “y”, "xx", or “zz”; and (2) variable labels with asterisks (*) and ellipses (...) indicated for sponsor appropriate text) |
| ADAMCR-0152 | BASETYPE is present, BASE is populated, and BASE is not equal to AVAL where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject |
| ADAMCR-0165 | BASETYPE is present, BTOXGR is populated, and BTOXGR is not equal to ATOXGR where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE |
| ADAMCR-0168 | BASETYPE is present, BNRIND is populated, and BNRIND is not equal to ANRIND where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE |
| ADAMCR-0176 | ABLFL is not equal to Y or null |
| ADAMCR-0181 | BASETYPE is not present, BASE is populated, and BASE is not equal to AVAL where ABLFL is equal to Y for a given value of PARAMCD for a subject |
| ADAMCR-0182 | BASETYPE is not present, BTOXGR is populated, and BTOXGR is not equal to ATOXGR where ABLFL is equal to Y for a given value of PARAMCD for a subject |
| ADAMCR-0183 | BASETYPE is not present, BNRIND is populated, and BNRIND is not equal to ANRIND where ABLFL is equal to Y for a given value of PARAMCD for a subject |
| ADAMCR-0204 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.AGE is not equal to DM.AGE |
| ADAMCR-0205 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.AGEU is not equal to DM.AGEU |
| ADAMCR-0206 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.SEX is not equal to DM.SEX |
| ADAMCR-0207 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.RACE is not equal to DM.RACE |
| ADAMCR-0208 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.SUBJID is not equal to DM.SUBJID |
| ADAMCR-0209 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.SITEID is not equal to DM.SITEID |
| ADAMCR-0210 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.ARM is not equal to DM.ARM |
| ADAMCR-0211 | ABLFN is not equal to 1 or null |
| ADAMCR-0223 | Within a given value of PARAMCD for a subject, CHG is populated and is not equal to AVAL - BASE |
| ADAMCR-0225 | Within a given value of PARAMCD for a subject, PCHG is populated and is not equal to ((AVAL - BASE)/BASE)*100 |
| ADAMCR-0269 | TRTEMFL is not equal to Y or null |
| ADAMCR-0270 | PREFL is not equal to Y or null |
| ADAMCR-0271 | FUPFL is not equal to Y or null |
| ADAMCR-0363 | ONTRTFL is not equal to Y or null |
| ADAMCR-0366 | RANDDT is not present when RANDFL is equal to Y for at least one record |
| ADAMCR-0619 | LVOTFL is not equal to Y or null |
| ADAMCR-0648 | TRTEMwFL is Y but TRTEMFL is not Y |
| ADAMCR-0650 | ONTRTwFL is Y but ONTRTFL is not Y |

### Metadata Check (5 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0017 | The length of a character value is greater than 200 characters |
| ADAMCR-0143 | PARAMCD has more than 8 characters in length |
| ADAMCR-0144 | PARAMCD starts with a character other than a letter |
| ADAMCR-0145 | PARAMCD has characters that are not letters, digits, and underscores |
| ADAMCR-0496 | A dataset name does not start with "AD" when dataset class is not missing |

### Comparison (2 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0361 | The value of ASTDT is greater than the value of AENDT, considering only those rows on which both variables are populated |
| ADAMCR-0362 | The value of ASTDTM is greater than the value of AENDTM, considering only those rows on which both variables are populated |

### Other (7 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0169 | The value of CNSR is not a positive integer or 0 |
| ADAMCR-0196 | PARAM is not populated |
| ADAMCR-0197 | PARAMCD is not populated |
| ADAMCR-0662 | TMPCTDF is populated and ARRLT and NRRLT are not populated |
| ADAMCR-0664 | On a given record, COHORT is populated and COHORTN is present and not populated |
| ADAMCR-0666 | DOSPCTDF is populated and DOSEP and DOSEA are not populated |
| ADAMCR-0669 | On a given record, ACYCLEC is populated and ACYCLE is present and not populated |

---

## Manual (7 rules)

Rules requiring group-level existential logic not yet expressible in CORE Check format.

| Core ID | Description |
|---------|-------------|
| ADAMCR-0127 | Within a given value of PARAMCD for a subject, BASE is populated and there is not at least one record with ABLFL equal to Y |
| ADAMCR-0128 | Within a given value of PARAMCD for a subject, BASEC is populated and there is not at least one record with ABLFL equal to Y |
| ADAMCR-0131 | Within a given value of PARAMCD, BASETYPE is populated for at least one record and is not populated for at least one record |
| ADAMCR-0132 | R2BASE is not equal to AVAL divided by BASE |
| ADAMCR-0148 | PARAMN is not an integer |
| ADAMCR-0154 | Within a given PARAMCD and BASETYPE for a subject, more than one record has ABLFL equal to Y |
| ADAMCR-0155 | Within a given PARAMCD for a subject, more than one record has ABLFL equal to Y and BASETYPE is not present |

