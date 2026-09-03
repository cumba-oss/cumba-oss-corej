# ADaM Conformance Rules v5.0 — ADaMIG v1.3 Conversion Report

Conversion report for the ADaMIG v1.3 rule set.

## Summary

| Status | Count | % |
|--------|-------|---|
| Fully Executable | 403 | 57% |
| Template (RuleGenerator) | 291 | 41% |
| Manual | 5 | 0% |
| **Total** | **699** | |

---

## Template Rules (291 rules)

Rules handled by `RuleGenerator` via wildcard expansion at runtime.

### Value Validation (10 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0005 | *FL | A variable with a suffix of FL has a value that is not Y, N or null |
| ADAMCR-0006 | *FN | A variable with a suffix of FL is present and a variable with the same root and a suffix of FN has a value that is not  0, 1 or null |
| ADAMCR-0039 | *DTF | A variable with a suffix of DTF has a value that is not within Controlled Terminology for DATEFL |
| ADAMCR-0040 | *TMF | A variable with a suffix of TMF has a value that is not within Controlled Terminology for TIMEFL |
| ADAMCR-0312 | SMQzzSC | SMQzzSC is not equal to BROAD or NARROW, where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0313 | SMQzzSCN | SMQzzSCN is not equal to 1 or 2, where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0710 | *RFL | A variable with a suffix of RFL has a value that is not Y, N, or null |
| ADAMCR-0711 | *PFL | A variable with a suffix of PFL has a value that is not Y, N, or null |
| ADAMCR-0712 | *RFN | A variable with a suffix of RFN has a value that is not 1, 0 or null |
| ADAMCR-0713 | *PFN | A variable with a suffix of PFN has a value that is not 1, 0, or null |

### Variable Type / Format (6 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0041 | *DT | A numeric variable with a suffix of DT does not have a date format |
| ADAMCR-0042 | *TM | A numeric variable with a suffix of TM does not have a time format, excluding ARELTM and variables with a suffix of DTM |
| ADAMCR-0043 | *DTM | A numeric variable with a suffix of DTM does not have a datetime format |
| ADAMCR-0058 | *DT | A variable with a suffix of DT is not a numeric variable |
| ADAMCR-0060 | *DTM | A variable with a suffix of DTM is not a numeric variable |
| ADAMCR-0716 | *TM | A variable with a suffix of TM is not a numeric variable excluding SDTM variables with a suffix of ELTM |

### Label Content (18 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0503 | *DT | A variable ending in DT must contain "Date" in the label |
| ADAMCR-0504 | *TM | A variable ending in TM must contain "Time" in the label |
| ADAMCR-0505 | *DTM | A variable ending in DTM must contain "Datetime" in the label |
| ADAMCR-0506 | *ADY | A variable ending in ADY must contain "Relative Day" in the label |
| ADAMCR-0507 | *DTF | A variable ending in DTF must contain "Date Imputation Flag" in the label |
| ADAMCR-0508 | *TMF | A variable ending in TMF must contain "Time Imputation Flag" in the label |
| ADAMCR-0509 | *SDT | A variable ending in SDT must contain "Start Date" in the label |
| ADAMCR-0510 | *STM | A variable ending in STM must contain "Start Time" in the label |
| ADAMCR-0511 | *SDTM | A variable ending in SDTM must contain "Start Datetime" in the label |
| ADAMCR-0512 | *SDY | A variable ending in SDY must contain "Relative Start Day" in the label |
| ADAMCR-0513 | *SDTF | A variable ending in SDTF must contain "Start Date Imputation Flag" in the label |
| ADAMCR-0514 | *STMF | A variable ending in STMF must contain "Start Time Imputation Flag" in the label |
| ADAMCR-0515 | *EDT | A variable ending in EDT must contain "End Date" in the label |
| ADAMCR-0516 | *ETM | A variable ending in ETM must contain "End Time" in the label |
| ADAMCR-0517 | *EDTM | A variable ending in EDTM must contain "End Datetime" in the label |
| ADAMCR-0518 | *EDY | A variable ending in EDY must contain "Relative End Day" in the label |
| ADAMCR-0519 | *EDTF | A variable ending in EDTF must contain "End Date Imputation Flag" in the label |
| ADAMCR-0520 | *ETMF | A variable ending in ETMF must contain "End Time Imputation Flag" in the label |

### Variable Existence Pairing (61 rules)

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
| ADAMCR-0157 | CRITy, CRITyFL | A variable with a prefix of CRIT and a suffix of a one-digit number (CRITy) is present and a variable with the same root with a suffix of FL (CRITyFL) is not present |
| ADAMCR-0201 | TRTAGy, TRTAGyN | TRTAGyN is present and TRTAGy is not present |
| ADAMCR-0335 | CRITy, CRITyFL | CRITyFL is present and CRITy is not present |
| ADAMCR-0336 | CRITy, CRITyFL | CRITy is present and CRITyFL is not present |
| ADAMCR-0337 | MCRITy, MCRITyML | MCRITyML is present and MCRITy is not present |
| ADAMCR-0338 | MCRITy, MCRITyML | MCRITy is present and MCRITyML is not present |
| ADAMCR-0346 | AyLO, R2AyLO | R2AyLO is present and AyLO is not present |
| ADAMCR-0349 | AyHI, R2AyHI | R2AyHI is present and AyHI is not present |
| ADAMCR-0352 | AyHI, AyHIC, AyIND, AyLO, AyLOC | AyIND is present and AyLO, AyHI, AyLOC, and AyHIC are all not present |
| ADAMCR-0368 | TRTxxA, TRxxAGy, TRxxPGy | TRxxAGy is not present and both TRxxPGy and TRTxxA are present |
| ADAMCR-0492 | BASECATy, ByIND, SHIFTy | SHIFTy is present and all of the following variable pairs (BASECATy, AVALCATy), (BNRIND, ANRIND), (ByIND, AyIND), (BTOXGR, ATOXGR), (BTOXGRL, ATOXGRL), (BTOXGRH, ATOXGRH), (BASE, AVAL) and (BASEC, AVALC) are not present |
| ADAMCR-0521 | *GRy, *GRyN | A variable which has a suffix of GRyN is present and a variable with the same root name and suffix of GRy is not present |
| ADAMCR-0526 | ANLzzFL, ANLzzFN | ANLzzFN is present and ANLzzFL is not present |
| ADAMCR-0531 | SEVGRy, SEVGRyN | SEVGRyN is present and SEVGRy is not present |
| ADAMCR-0534 | RELGRy, RELGRyN | RELGRyN is present and RELGRy is not present |
| ADAMCR-0537 | TOXGGRy, TOXGGRyN | TOXGGRyN is present and TOXGGRy is not present |
| ADAMCR-0538 | SMQzzSC, SMQzzSCN | SMQzzSCN is present and SMQzzSC is not present |
| ADAMCR-0543 | AVALCATy, AVALCAyN | AVALCAyN is present and AVALCATy is not present |
| ADAMCR-0544 | BASECATy, BASECAyN | BASECAyN is present and BASECATy is not present |
| ADAMCR-0545 | CHGCATy, CHGCATyN | CHGCATyN is present and CHGCATy is not present |
| ADAMCR-0546 | PCHGCATy, PCHGCAyN | PCHGCAyN is present and PCHGCATy is not present |
| ADAMCR-0547 | PARCATy, PARCATyN | PARCATyN is present and PARCATy is not present |
| ADAMCR-0552 | MCRITyML, MCRITyMN | MCRITyMN is present and MCRITyML is not present |
| ADAMCR-0553 | REGIONy, REGIONyN | REGIONyN is present and REGIONy is not present |
| ADAMCR-0554 | SHIFTy, SHIFTyN | SHIFTyN is present and SHIFTy is not present |
| ADAMCR-0557 | TSEQPGy, TSEQPGyN | TSEQPGyN is present and TSEQPGy is not present |
| ADAMCR-0558 | TSEQAGy, TSEQAGyN | TSEQAGyN is present and TSEQAGy is not present |
| ADAMCR-0559 | TRCMPGy, TRCMPGyN | TRCMPGyN is present and TRCMPGy is not present |
| ADAMCR-0561 | STRATwR, STRATwRN | STRATwRN is present and STRATwR is not present for the same value of "w" |
| ADAMCR-0563 | STRATwV, STRATwVN | STRATwVN is present and STRATwV is not present for the same value of "w" |
| ADAMCR-0565 | BCHGCATy, BCHGCAyN | BCHGCAyN is present and BCHGCATy is not present |
| ADAMCR-0566 | PBCHGCAy, PBCHGCyN | PBCHGCyN is present and PBCHGCAy is not present |
| ADAMCR-0567 | CRITyFL, CRITyFN | CRITyFN is present and CRITyFL is not present |
| ADAMCR-0570 | PxxSw, TRTxxP | PxxSw is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0571 | PxxSwSDT, TRTxxP | PxxSwSDT is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0572 | PxxSwSDM, TRTxxP | PxxSwSDM is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0573 | PxxSwSTM, TRTxxP | PxxSwSTM is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0574 | PxxSwSDF, TRTxxP | PxxSwSDF is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0575 | PxxSwSTF, TRTxxP | PxxSwSTF is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0576 | PxxSwEDT, TRTxxP | PxxSwEDT is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0577 | PxxSwEDM, TRTxxP | PxxSwEDM is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0578 | PxxSwETM, TRTxxP | PxxSwETM is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0579 | PxxSwEDF, TRTxxP | PxxSwEDF is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0580 | PxxSwETF, TRTxxP | PxxSwETF is present and TRTxxP is not present for the same "xx" value |
| ADAMCR-0581 | TRTAGy, TRTPGy, TRTxxA, TRTxxP, TRxxAGy, TRxxPGy, TSEQAGy, TSEQPGy | None of TRTP, TRTPGy, TRTA, and TRTAGy are present and none of the character treatment variables in ADSL defined in the IG are present |
| ADAMCR-0651 | ONTRxxFL | ONTRxxFL is present but ONTRTFL is not present |
| ADAMCR-0652 | ONTRTwFL | ONTRTwFL is present but ONTRTFL is not present |
| ADAMCR-0719 | TRTAGy, TRTPGy, TRTxxA, TRTxxAN, TRTxxP, TRTxxPN | None of the subject-level or record-level treatment variables defined in the IG is present |
| ADAMCR-0764 | TRTAGy, TRTPGy | TRTPGy is present and TRTA is present but TRTAGy is not present, where y is an integer [1-99, not zero-padded] |
| ADAMCR-0895 | CRITyFL, CRITyFN | A variable with a prefix of CRIT, a suffix of FL and containing either a one-digit or two-digit number (CRITyFL) is present and a variable with the same root without a suffix of FL (CRITy) is not present |

### Record-Level Populated Pairing (65 rules)

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
| ADAMCR-0339 | MCRITy, MCRITyML | MCRITyML is populated and MCRITy is not populated |
| ADAMCR-0369 | *DT, *DTF, *DTM | *DTF is populated and neither *DT nor *DTM is populated |
| ADAMCR-0370 | *DTM, *TM, *TMF | *TMF is populated and neither *TM nor *DTM is populated |
| ADAMCR-0375 | *GRy, *GRyN | On a given record, a variable which has a suffix of GRyN is populated and a variable with the same root name and suffix of GRy is not populated |
| ADAMCR-0376 | *GRy, *GRyN | On a given record, a variable which has a suffix of GRy is populated and a variable with the same root name and suffix of GRyN is not populated |
| ADAMCR-0377 | TRTxxP, TRTxxPN | On a given record, TRTxxP is populated and TRTxxPN is not populated |
| ADAMCR-0378 | TRTxxP, TRTxxPN | On a given record, TRTxxPN is populated and TRTxxP is not populated |
| ADAMCR-0411 | ANLzzFL, ANLzzFN | On a given record, ANLzzFN is populated and ANLzzFL is not populated |
| ADAMCR-0412 | ANLzzFL, ANLzzFN | On a given record, ANLzzFL is populated and ANLzzFN is not populated |
| ADAMCR-0419 | TRxxPGy, TRxxPGyN | On a given record, TRxxPGyN is populated and TRxxPGy is not populated |
| ADAMCR-0420 | TRxxPGy, TRxxPGyN | On a given record, TRxxPGy is populated and TRxxPGyN is not populated |
| ADAMCR-0421 | TRxxAGy, TRxxAGyN | On a given record, TRxxAGyN is populated and TRxxAGy is not populated |
| ADAMCR-0422 | TRxxAGy, TRxxAGyN | On a given record, TRxxAGy is populated and TRxxAGyN is not populated |
| ADAMCR-0423 | TRTPGy, TRTPGyN | On a given record, TRTPGyN is populated and TRTPGy is not populated |
| ADAMCR-0424 | TRTPGy, TRTPGyN | On a given record, TRTPGy is populated and TRTPGyN is not populated |
| ADAMCR-0425 | TRTAGy, TRTAGyN | On a given record, TRTAGy is populated and TRTAGyN is not populated |
| ADAMCR-0426 | TRTAGy, TRTAGyN | On a given record, TRTAGyN is populated and TRTAGy is not populated |
| ADAMCR-0427 | TRTxxA, TRTxxAN | On a given record, TRTxxA is populated and TRTxxAN is not populated |
| ADAMCR-0428 | TRTxxA, TRTxxAN | On a given record, TRTxxAN is populated and TRTxxA is not populated |
| ADAMCR-0437 | AVALCATy, AVALCAyN | On a given record, AVALCATy is populated and AVALCAyN is not populated |
| ADAMCR-0438 | AVALCATy, AVALCAyN | On a given record, AVALCAyN is populated and AVALCATy is not populated |
| ADAMCR-0439 | BASECATy, BASECAyN | On a given record, BASECATy is populated and BASECAyN is not populated |
| ADAMCR-0440 | BASECATy, BASECAyN | On a given record, BASECAyN is populated and BASECATy is not populated |
| ADAMCR-0441 | CHGCATy, CHGCATyN | On a given record, CHGCATy is populated and CHGCATyN is not populated |
| ADAMCR-0442 | CHGCATy, CHGCATyN | On a given record, CHGCATyN is populated and CHGCATy is not populated |
| ADAMCR-0443 | PCHGCATy, PCHGCAyN | On a given record, PCHGCATy is populated and PCHGCAyN is not populated |
| ADAMCR-0444 | PCHGCATy, PCHGCAyN | On a given record, PCHGCAyN is populated and PCHGCATy is not populated |
| ADAMCR-0445 | PARCATy, PARCATyN | On a given record, PARCATy is populated and PARCATyN is not populated |
| ADAMCR-0446 | PARCATy, PARCATyN | On a given record, PARCATyN is populated and PARCATy is not populated |
| ADAMCR-0450 | MCRITyML, MCRITyMN | On a given record, MCRITyML is populated and MCRITyMN is not populated |
| ADAMCR-0451 | MCRITyML, MCRITyMN | On a given record, MCRITyMN is populated and MCRITyML is not populated |
| ADAMCR-0452 | REGIONy, REGIONyN | On a given record, REGIONy is populated and REGIONyN is not populated |
| ADAMCR-0453 | REGIONy, REGIONyN | On a given record, REGIONyN is populated and REGIONy is not populated |
| ADAMCR-0454 | SHIFTy, SHIFTyN | On a given record, SHIFTy is populated and SHIFTyN is not populated |
| ADAMCR-0455 | SHIFTy, SHIFTyN | On a given record, SHIFTyN is populated and SHIFTy is not populated |
| ADAMCR-0460 | TSEQPGy, TSEQPGyN | On a given record, TSEQPGy is populated and TSEQPGyN is not populated |
| ADAMCR-0461 | TSEQPGy, TSEQPGyN | On a given record, TSEQPGyN is populated and TSEQPGy is not populated |
| ADAMCR-0462 | TSEQAGy, TSEQAGyN | On a given record, TSEQAGy is populated and TSEQAGyN is not populated |
| ADAMCR-0463 | TSEQAGy, TSEQAGyN | On a given record, TSEQAGyN is populated and TSEQAGy is not populated |
| ADAMCR-0464 | TRCMPGy, TRCMPGyN | On a given record, TRCMPGy is populated and TRCMPGyN is not populated |
| ADAMCR-0465 | TRCMPGy, TRCMPGyN | On a given record, TRCMPGyN is populated and TRCMPGy is not populated |
| ADAMCR-0470 | STRATwR, STRATwRN | On a given record, STRATwR is populated and STRATwRN is not populated |
| ADAMCR-0471 | STRATwR, STRATwRN | On a given record, STRATwRN is populated and STRATwR is not populated |
| ADAMCR-0478 | STRATwV, STRATwVN | On a given record, STRATwV is populated and STRATwVN is not populated |
| ADAMCR-0479 | STRATwV, STRATwVN | On a given record, STRATwVN is populated and STRATwV is not populated |
| ADAMCR-0482 | BCHGCATy, BCHGCAyN | On a given record, BCHGCATy is populated and BCHGCAyN is not populated |
| ADAMCR-0483 | BCHGCATy, BCHGCAyN | On a given record, BCHGCAyN is populated and BCHGCATy is not populated |
| ADAMCR-0484 | PBCHGCAy, PBCHGCyN | On a given record, PBCHGCAy is populated and PBCHGCyN is not populated |
| ADAMCR-0485 | PBCHGCAy, PBCHGCyN | On a given record, PBCHGCyN is populated and PBCHGCAy is not populated |
| ADAMCR-0486 | CRITyFL, CRITyFN | On a given record, CRITyFL is populated and CRITyFN is not populated |
| ADAMCR-0487 | CRITyFL, CRITyFN | On a given record, CRITyFN is populated and CRITyFL is not populated |
| ADAMCR-0675 | DEVGRy, DEVGRyN | On a given record, DEVGRy is populated and DEVGRyN is not populated |
| ADAMCR-0676 | DEVGRy, DEVGRyN | On a given record, DEVGRyN is populated and DEVGRy is not populated |
| ADAMCR-0679 | DEVTYGy, DEVTYGyN | On a given record, DEVTYGy is populated and DEVTYGyN is not populated |
| ADAMCR-0680 | DEVTYGy, DEVTYGyN | On a given record, DEVTYGyN is populated and DEVTYGy is not populated |
| ADAMCR-0683 | MODELGy, MODELGyN | On a given record, MODELGy is populated and MODELGyN is not populated |
| ADAMCR-0684 | MODELGy, MODELGyN | On a given record, MODELGyN is populated and MODELGy is not populated |

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

### Uniqueness / 1:1 Relationship (69 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0124 | PARCATy | There is more than one value of PARCATy for a given value of PARAMCD |
| ADAMCR-0151 | CRITy | Within a given value of PARAMCD, there is more than one value of CRITy |
| ADAMCR-0322 | TRTPGy | Within a given value of TRTP, there is more than one value of TRTPGy, where y is an integer [1-99, not zero-padded] |
| ADAMCR-0327 | AVALCATy, AVALCAyN | Within a parameter, there is more than one value of AVALCATy for a given value of AVALCAyN, considering only those rows on which both variables are populated |
| ADAMCR-0328 | AVALCATy, AVALCAyN | Within a parameter, there is more than one value of AVALCAyN for a given value of AVALCATy, considering only those rows on which both variables are populated |
| ADAMCR-0329 | BASECATy, BASECAyN | Within a parameter, there is more than one value of BASECATy for a given value of BASECAyN, considering only those rows on which both variables are populated |
| ADAMCR-0330 | BASECATy, BASECAyN | Within a parameter, there is more than one value of BASECAyN for a given value of BASECATy, considering only those rows on which both variables are populated |
| ADAMCR-0331 | CHGCATy, CHGCATyN | Within a parameter, there is more than one value of CHGCATy for a given value of CHGCATyN, considering only those rows on which both variables are populated |
| ADAMCR-0332 | CHGCATy, CHGCATyN | Within a parameter, there is more than one value of CHGCATyN for a given value of CHGCATy, considering only those rows on which both variables are populated |
| ADAMCR-0333 | PCHGCATy, PCHGCAyN | Within a parameter, there is more than one value of PCHGCATy for a given value of PCHGCAyN, considering only those rows on which both variables are populated |
| ADAMCR-0334 | PCHGCATy, PCHGCAyN | Within a parameter, there is more than one value of PCHGCAyN for a given value of PCHGCATy, considering only those rows on which both variables are populated |
| ADAMCR-0340 | MCRITyML, MCRITyMN | Within a parameter, there is more than one value of MCRITyML for a given value of MCRITyMN, considering only those rows on which both variables are populated |
| ADAMCR-0341 | MCRITyML, MCRITyMN | Within a parameter, there is more than one value of MCRITyMN for a given value of MCRITyML, considering only those rows on which both variables are populated |
| ADAMCR-0347 | AyLO, AyLOC | Within a parameter, there is more than one value of AyLO for a given value of AyLOC |
| ADAMCR-0348 | AyLO, AyLOC | Within a parameter, there is more than one value of AyLOC for a given value of AyLO |
| ADAMCR-0350 | AyHI, AyHIC | Within a parameter, there is more than one value of AyHI for a given value of AyHIC |
| ADAMCR-0351 | AyHI, AyHIC | Within a parameter, there is more than one value of AyHIC for a given value of AyHI |
| ADAMCR-0355 | REGIONy, REGIONyN | There is more than one value of REGIONy for a given value of REGIONyN, considering only those rows on which both variables are populated |
| ADAMCR-0356 | REGIONy, REGIONyN | There is more than one value of REGIONyN for a given value of REGIONy, considering only those rows on which both variables are populated |
| ADAMCR-0413 | ANLzzFL, ANLzzFN | There is more than one value of ANLzzFN for a given value of ANLzzFL, considering only those rows on which both variables are populated |
| ADAMCR-0414 | ANLzzFL, ANLzzFN | There is more than one value of ANLzzFL for a given value of ANLzzFN , considering only those rows on which both variables are populated |
| ADAMCR-0472 | STRATwR, STRATwRN | There is more than one value of STRATwR for a given value of STRATwRN, considering only those rows on which both variables are populated |
| ADAMCR-0473 | STRATwR, STRATwRN | There is more than one value of STRATwRN for a given value of STRATwR, considering only those rows on which both variables are populated |
| ADAMCR-0480 | STRATwV, STRATwVN | There is more than one value of STRATwV for a given value of STRATwVN, considering only those rows on which both variables are populated |
| ADAMCR-0481 | STRATwV, STRATwVN | There is more than one value of STRATwVN for a given value of STRATwV, considering only those rows on which both variables are populated |
| ADAMCR-0583 | BCHGCATy | Within a given value of PARAMCD, there is more than one value of BCHGCATy for a given value of BCHG and y, where y is an integer [1-99, not zero-padded] |
| ADAMCR-0584 | BCHGCATy, BCHGCAyN | Within a parameter, there is more than one value of BCHGCATy for a given value of BCHGCAyN, considering only those rows on which both variables are populated |
| ADAMCR-0585 | BCHGCATy, BCHGCAyN | Within a parameter, there is more than one value of BCHGCAyN for a given value of BCHGCATy, considering only those rows on which both variables are populated |
| ADAMCR-0587 | PBCHGCAy | Within a given value of PARAMCD, there is more than one value of PBCHGCAy for a given value of PBCHG and y, where y is a single-digit integer [1-9] |
| ADAMCR-0588 | PBCHGCAy, PBCHGCyN | Within a parameter, there is more than one value of PBCHGCAy for a given value of PBCHGCyN, considering only those rows on which both variables are populated |
| ADAMCR-0589 | PBCHGCAy, PBCHGCyN | Within a parameter, there is more than one value of PBCHGCyN for a given value of PBCHGCAy, considering only those rows on which both variables are populated |
| ADAMCR-0616 | TRCMPGy, TRCMPGyN | There is more than one value of TRCMPGy for a given value of TRCMPGyN, where y is an integer [1-99, not zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0617 | TRCMPGy, TRCMPGyN | There is more than one value of TRCMPGyN for a given value of TRCMPGy, where y is an integer [1-99, not zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0673 | DEVGRy, DEVGRyN | There is more than one value of DEVGRy for a given value of DEVGRyN, considering only those rows on which both variables are populated |
| ADAMCR-0674 | DEVGRy, DEVGRyN | There is more than one value of DEVGRyN for a given value of DEVGRy, considering only those rows on which both variables are populated |
| ADAMCR-0677 | DEVTYGy, DEVTYGyN | There is more than one value of DEVTYGy for a given value of DEVTYGyN, considering only those rows on which both variables are populated |
| ADAMCR-0678 | DEVTYGy, DEVTYGyN | There is more than one value of DEVTYGyN for a given value of DEVTYGy, considering only those rows on which both variables are populated |
| ADAMCR-0681 | MODELGy, MODELGyN | There is more than one value of MODELGy for a given value of MODELGyN, considering only those rows on which both variables are populated |
| ADAMCR-0682 | MODELGy, MODELGyN | There is more than one value of MODELGyN for a given value of MODELGy, considering only those rows on which both variables are populated |
| ADAMCR-0717 | TRTxxP, TRTxxPN | There is more than one value of TRTxxPN for a given value of TRTxxP, considering only those rows on which both variables are populated |
| ADAMCR-0718 | TRTxxP, TRTxxPN | There is more than one value of TRTxxP for a given value of TRTxxPN, considering only those rows on which both variables are populated |
| ADAMCR-0730 | PARCATy, PARCATyN | There is more than one value of PARCATy for a given value of PARCATyN, considering only those rows on which both variables are populated |
| ADAMCR-0731 | PARCATy, PARCATyN | There is more than one value of PARCATyN for a given value of PARCATy, considering only those rows on which both variables are populated |
| ADAMCR-0736 | SHIFTy, SHIFTyN | Within a given value of PARAMCD, there is more than one value of SHIFTy for a given value of SHIFTyN, considering only those rows on which both variables are populated |
| ADAMCR-0737 | SHIFTy, SHIFTyN | Within a given value of PARAMCD, there is more than one value of SHIFTyN for a given value of SHIFTy, considering only those rows on which both variables are populated |
| ADAMCR-0748 | AVALCATy | Within a given value of PARAMCD, there is more than one value of AVALCATy for a given value of AVAL and y, where y is a single-digit integer [1-9] |
| ADAMCR-0749 | BASECATy | Within a given value of PARAMCD, there is more than one value of BASECATy for a given value of BASE and y, where y is a single-digit integer [1-9] |
| ADAMCR-0750 | CHGCATy | Within a given value of PARAMCD, there is more than one value of CHGCATy for a given value of CHG and y, where y is an integer [1-99, not zero-padded] |
| ADAMCR-0751 | PCHGCATy | Within a given value of PARAMCD, there is more than one value of PCHGCATy for a given value of PCHG and y, where y is a single-digit integer [1-9] |
| ADAMCR-0756 | TRTxxP, TRxxPGy | Within a given value of TRTxxP, there is more than one value of TRxxPGy, where xx is an integer [01-99, zero-padded] and y is an integer [1-99, not zero-padded] |
| ADAMCR-0757 | TRxxPGy, TRxxPGyN | There is more than one value of TRxxPGy for a given value of TRxxPGyN, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9], considering only those rows on which both variables are populated |
| ADAMCR-0758 | TRxxPGy, TRxxPGyN | There is more than one value of TRxxPGyN for a given value of TRxxPGy, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9], considering only those rows on which both variables are populated |
| ADAMCR-0759 | TRTxxA, TRxxAGy | Within a given value of TRTxxA, there is more than one value of TRxxAGy, where xx is an integer [01-99, zero-padded] and y is an integer [1-99, not zero-padded] |
| ADAMCR-0760 | TRxxAGy, TRxxAGyN | There is more than one value of TRxxAGy for a given value of TRxxAGyN, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9], considering only those rows on which both variables are populated |
| ADAMCR-0761 | TRxxAGy, TRxxAGyN | There is more than one value of TRxxAGyN for a given value of TRxxAGy, where xx is an integer [01-99, zero-padded] and y is a single-digit integer [1-9], considering only those rows on which both variables are populated |
| ADAMCR-0762 | TRTPGy, TRTPGyN | There is more than one value of TRTPGy for a given value of TRTPGyN, where y is an integer [1-99, not zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0763 | TRTPGy, TRTPGyN | There is more than one value of TRTPGyN for a given value of TRTPGy, where y is an integer [1-99, not zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0765 | TRTAGy, TRTAGyN | There is more than one value of TRTAGy for a given value of TRTAGyN, where y is an integer [1-99, not zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0766 | TRTAGy, TRTAGyN | There is more than one value of TRTAGyN for a given value of TRTAGy, where y is an integer [1-99, not zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0767 | TRTxxA, TRTxxAN | There is more than one value of TRTxxAN for a given value of TRTxxA, where xx is an integer [01-99, zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0768 | TRTxxA, TRTxxAN | There is more than one value of TRTxxA for a given value of TRTxxAN, where xx is an integer [01-99, zero-padded], considering only those rows on which both variables are populated |
| ADAMCR-0773 | SEVGRy, SEVGRyN | There is more than one value of SEVGRy for a given value of SEVGRyN, considering only those rows on which both variables are populated |
| ADAMCR-0774 | SEVGRy, SEVGRyN | There is more than one value of SEVGRyN for a given value of SEVGRy, considering only those rows on which both variables are populated |
| ADAMCR-0779 | RELGRy, RELGRyN | There is more than one value of RELGRy for a given value of RELGRyN, considering only those rows on which both variables are populated |
| ADAMCR-0780 | RELGRy, RELGRyN | There is more than one value of RELGRyN for a given value of RELGRy, considering only those rows on which both variables are populated |
| ADAMCR-0785 | TOXGGRy, TOXGGRyN | There is more than one value of TOXGGRy for a given value of TOXGGRyN, considering only those rows on which both variables are populated |
| ADAMCR-0786 | TOXGGRy, TOXGGRyN | There is more than one value of TOXGGRyN for a given value of TOXGGRy, considering only those rows on which both variables are populated |
| ADAMCR-0787 | SMQzzSC, SMQzzSCN | There is more than one value of SMQzzSC for a given value of SMQzzSCN, considering only those rows on which both variables are populated |
| ADAMCR-0788 | SMQzzSC, SMQzzSCN | There is more than one value of SMQzzSCN for a given value of SMQzzSC, considering only those rows on which both variables are populated |

### Cross-Dataset / Library Comparison (8 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0002 | — | A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels |
| ADAMCR-0085 | — | A variable is present with the same name as a variable present in ADSL but the variables do not have identical labels |
| ADAMCR-0086 | — | A variable is present with the same name as a variable present in ADSL but the variables do not have identical formats |
| ADAMCR-0199 | — | A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types |
| ADAMCR-0200 | — | A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types |
| ADAMCR-0590 | — | A variable is present with the same name as a variable present in ADSL but the variables do not have identical data types |
| ADAMCR-0591 | — | A variable is present with the same name as a variable present in ADSL but the variables do not have identical values for a given value of USUBJID |
| ADAMCR-0709 | — | Labels for ADaM variables do not match the standard labels for ADaM variables listed in the implementation guide that cannot be modified (with the exception of (1) variables whose names contain indexes "w", “y”, or “zz”; and (2) variable labels with asterisks (*), braces ({...}), and ellipses (...) indicated for sponsor appropriate text) |

### Dynamic Index Lookup (24 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0102 | TRTxxP | For every unique xx value of APERIOD, there is not an ADSL variable TRTxxP |
| ADAMCR-0103 | TRxxSDT | For every unique xx value of APERIOD, there is not an ADSL variable TRxxSDT |
| ADAMCR-0104 | TRxxEDT | For every unique xx value of APERIOD, there is not an ADSL variable TRxxEDT |
| ADAMCR-0498 | PxxSw | For every unique combination of a w value of ASPER and xx value of APERIOD in BDS or OCCDS datasets, there is not a variable PxxSw |
| ADAMCR-0592 | APxxSDT | On a given record, the value of APERSDT is not equal to the value of variable APxxSDT where xx equals the value of APERIOD |
| ADAMCR-0593 | APxxSTM | On a given record, the value of APERSTM is not equal to the value of variable APxxSTM where xx equals the value of APERIOD |
| ADAMCR-0594 | APxxSDTM | On a given record, the value of APERSDTM is not equal to the value of variable APxxSDTM where xx equals the value of APERIOD |
| ADAMCR-0595 | APxxEDT | On a given record, the value of APEREDT is not equal to the value of variable APxxEDT where xx equals the value of APERIOD |
| ADAMCR-0596 | APxxETM | On a given record, the value of APERETM is not equal to the value of variable APxxETM where xx equals the value of APERIOD |
| ADAMCR-0597 | APxxEDTM | On a given record, the value of APEREDTM is not equal to the value of variable APxxEDTM where xx equals the value of APERIOD |
| ADAMCR-0598 | PxxSwSDT | On a given record, the value of ASPRSDT is not equal to the value of variable PxxSwSDT where xx equals the value of APERIOD and w equals the value of ASPER |
| ADAMCR-0599 | PxxSwSTM | On a given record, the value of ASPRSTM is not equal to the value of variable PxxSwSTM where xx equals the value of APERIOD and w equals the value of ASPER |
| ADAMCR-0600 | PxxSwSDM | On a given record, the value of ASPRSDTM is not equal to the value of variable PxxSwSDM where xx equals the value of APERIOD and w equals the value of ASPER |
| ADAMCR-0601 | PxxSwEDT | On a given record, the value of ASPREDT is not equal to the value of variable PxxSwEDT where xx equals the value of APERIOD and w equals the value of ASPER |
| ADAMCR-0602 | PxxSwETM | On a given record, the value of ASPRETM is not equal to the value of variable PxxSwETM where xx equals the value of APERIOD and w equals the value of ASPER |
| ADAMCR-0603 | PxxSwEDM | On a given record, the value of ASPREDTM is not equal to the value of variable PxxSwEDM where xx equals the value of APERIOD and w equals the value of ASPER |
| ADAMCR-0605 | — | On a given record, APHASEN is present and the value of PHSDT is not equal to the value of variable PHwSDT where w equals the value of APHASEN |
| ADAMCR-0607 | — | On a given record, APHASEN is present and the value of PHSTM is not equal to the value of variable PHwSTM where w equals the value of APHASEN |
| ADAMCR-0609 | — | On a given record, APHASEN is present and the value of PHSDTM is not equal to the value of variable PHwSDTM where w equals the value of APHASEN |
| ADAMCR-0611 | — | On a given record, APHASEN is present and the value of PHEDT is not equal to the value of variable PHwEDT where w equals the value of APHASEN |
| ADAMCR-0613 | — | On a given record, APHASEN is present and the value of PHETM is not equal to the value of variable PHwETM where w equals the value of APHASEN |
| ADAMCR-0615 | — | On a given record, APHASEN is present and the value of PHEDTM is not equal to the value of variable PHwEDTM where w equals the value of APHASEN |
| ADAMCR-0706 | PxxSw | For every unique xx value of APERIOD in BDS or OCCDS datasets, there is not a variable PxxSw |
| ADAMCR-0707 | PxxSw | For every unique w value of ASPER in BDS or OCCDS datasets, there is not a variable PxxSw |

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

### Complex / Multi-Variable (19 rules)

| Core ID | Wildcards | Description |
|---------|-----------|-------------|
| ADAMCR-0010 | *FL, *FN | A variable with a suffix of FL is equal to Y and a variable with the same root and a suffix of FN is not equal to 1 |
| ADAMCR-0011 | *FL, *FN | A variable with a suffix of FL is equal to N and a variable with the same root and a suffix of FN is not equal to 0 |
| ADAMCR-0046 | *DY | A variable with a suffix of DY has a value of zero |
| ADAMCR-0084 | — | TRTEDT is not equal to the maximum value of all TRxxEDT variables |
| ADAMCR-0212 | ANLzzFN | ANLzzFN is not equal to 1 or null where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0272 | AOCCzzFL | A variable with a prefix of AOCC and a suffix of FL is not equal to Y or null |
| ADAMCR-0354 | ByIND | BASETYPE is not present, ByIND is populated, and ByIND is not equal to AyIND where ABLFL is equal to Y for a given value of PARAMCD for a subject |
| ADAMCR-0493 | ANLzzFL | ANLzzFL is equal to "N" where zz is a zero-padded two-digit integer [01-99] |
| ADAMCR-0604 | — | On a given record, APHASEN is not present and the value of PHSDT is not equal to the value of at least one PHwSDT variable |
| ADAMCR-0606 | — | On a given record, APHASEN is not present and the value of PHSTM is not equal to the value of at least one PHwSTM variable |
| ADAMCR-0608 | — | On a given record, APHASEN is not present and the value of PHSDTM is not equal to the value of at least one PHwSDTM variable |
| ADAMCR-0610 | — | On a given record, APHASEN is not present and the value of PHEDT is not equal to the value of at least one PHwEDT variable |
| ADAMCR-0612 | — | On a given record, APHASEN is not present and the value of PHETM is not equal to the value of at least one PHwETM variable |
| ADAMCR-0614 | — | On a given record, APHASEN is not present and the value of PHEDTM is not equal to the value of at least one PHwEDTM variable |
| ADAMCR-0647 | TREMxxFL | TREMxxFL is Y but TRTEMFL is not Y |
| ADAMCR-0649 | ONTRxxFL | ONTRxxFL is Y but ONTRTFL is not Y |
| ADAMCR-0702 | ByIND | BASETYPE is present, ByIND is populated, and ByIND is not equal to AyIND where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a combination of device and subject |
| ADAMCR-0703 | ByIND | BASETYPE is not present, ByIND is populated, and ByIND is not equal to AyIND where ABLFL is equal to Y for a given value of PARAMCD for a combination of device and subject |
| ADAMCR-0790 | ByIND | BASETYPE is populated, ByIND is populated, and ByIND is not equal to AyIND where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject |

---

## Fully Executable (403 rules)

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

### Variable Presence (54 rules)

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
| ADAMCR-0194 | PARAM is not present |
| ADAMCR-0195 | PARAMCD is not present |
| ADAMCR-0198 | AVAL is not present and AVALC is not present |
| ADAMCR-0261 | AEDECOD is not present |
| ADAMCR-0262 | AEBODSYS is not present |
| ADAMCR-0278 | AESER is not present |
| ADAMCR-0364 | DOSEON or DOSCUMA is present and DOSEU is not present |
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
| ADAMCR-0672 | SPDEVID is not present within ADDL |
| ADAMCR-0685 | DEVSDT is not present within ADDL |
| ADAMCR-0686 | DEVEDT is not present within ADDL |
| ADAMCR-0689 | SPDEVID is not present within dataset |
| ADAMCR-0885 | USUBJIDN is not present |
| ADAMCR-0886 | AFRLT is not present |
| ADAMCR-0887 | EVID is not present |
| ADAMCR-0888 | DV is not present |
| ADAMCR-0889 | MDV is not present |
| ADAMCR-0890 | AMT is not present |

### Variable Metadata (76 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0013 | The length of a variable name exceeds 8 characters |
| ADAMCR-0014 | A variable name does not start with a letter (A-Z) |
| ADAMCR-0015 | A variable name contains a character other than letters (A-Z), underscores (_), or numerals (0-9) |
| ADAMCR-0016 | The length of a variable label is greater than 40 characters |
| ADAMCR-0111 | ARELTM is present and ARELTMU is not present |
| ADAMCR-0113 | ARELTMU is present and ARELTM is not present |
| ADAMCR-0163 | BTOXGR is present and ATOXGR is not present |
| ADAMCR-0164 | BTOXGR is present and ABLFL is not present |
| ADAMCR-0166 | BNRIND is present and ANRIND is not present |
| ADAMCR-0167 | BNRIND is present and ABLFL is not present |
| ADAMCR-0252 | AVAL is present or AVALC is present |
| ADAMCR-0254 | PARAM is present |
| ADAMCR-0499 | ASPER is present and APERIOD is not present |
| ADAMCR-0522 | BTOXGRN is present and BTOXGR is not present |
| ADAMCR-0523 | ATOXGRLN is present and ATOXGRL is not present |
| ADAMCR-0524 | ATOXGRHN is present and ATOXGRH is not present |
| ADAMCR-0525 | ABLFN is present and ABLFL is not present |
| ADAMCR-0527 | TRTSEQPN is present and TRTSEQP is not present |
| ADAMCR-0528 | TRTSEQAN is present and TRTSEQA is not present |
| ADAMCR-0529 | AESEVN is present and AESEV is not present |
| ADAMCR-0530 | ASEVN is present and ASEV is not present |
| ADAMCR-0532 | AERELN is present and AEREL is not present |
| ADAMCR-0533 | ARELN is present and AREL is not present |
| ADAMCR-0535 | AETOXGRN is present and AETOXGR is not present |
| ADAMCR-0536 | ATOXGRN is present and ATOXGR is not present |
| ADAMCR-0539 | APERIODC is present and APERIOD is not present |
| ADAMCR-0540 | APHASEN is present and APHASE is not present |
| ADAMCR-0541 | ASPERC is present and ASPER is not present |
| ADAMCR-0542 | ATPTN is present and ATPT is not present |
| ADAMCR-0548 | AVISITN is present and AVISIT is not present |
| ADAMCR-0549 | BTOXGRHN is present and BTOXGRH is not present |
| ADAMCR-0550 | BTOXGRLN is present and BTOXGRL is not present |
| ADAMCR-0551 | DTHCAUSN is present and DTHCAUS is not present |
| ADAMCR-0555 | TRTAN is present and TRTA is not present |
| ADAMCR-0556 | TRTPN is present and TRTP is not present |
| ADAMCR-0560 | STRATARN is present and STRATAR is not present |
| ADAMCR-0562 | STRATAVN is present and STRATAV is not present |
| ADAMCR-0568 | ONTRTFN is present and ONTRTFL is not present |
| ADAMCR-0569 | LVOTFN is present and LVOTFL is not present |
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
| ADAMCR-0746 | SRCDOM has a value that is not an SDTM domain name, ADaM dataset name, or null |
| ADAMCR-0799 | PROJIDN is present and PROJID is not present |
| ADAMCR-0804 | STUDYIDN is present and STUDYID is not present |
| ADAMCR-0809 | SUBJTYPC is present and SUBJTYP is not present |
| ADAMCR-0814 | USUBJIDN is present and USUBJID is not present |
| ADAMCR-0819 | SUBJIDN is present and SUBJID is not present |
| ADAMCR-0824 | SITEIDN is present and SITEID is not present |
| ADAMCR-0829 | FLGREASC is present and FLGREAS is not present |
| ADAMCR-0834 | DVIDN is present and DVID is not present |
| ADAMCR-0839 | BLQFN is present and BLQFL is not present |
| ADAMCR-0844 | ALQFN is present and ALQFL is not present |
| ADAMCR-0849 | FORMN is present and FORM is not present |
| ADAMCR-0854 | ROUTEN is present and ROUTE is not present |
| ADAMCR-0859 | ACYCLEC is present and ACYCLE is not present |
| ADAMCR-0864 | COHORTC is present and COHORT is not present |
| ADAMCR-0869 | RACEN is present and RACE is not present |
| ADAMCR-0874 | SEXN is present and SEX is not present |
| ADAMCR-0879 | COUNTRYN is present and COUNTRY is not present |
| ADAMCR-0884 | COUNTRYL is present and COUNTRY is not present |
| ADAMCR-0896 | AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present |
| ADAMCR-0898 | For a value of AD*.USUBJID that is a value of --.USUBJID, a value of AD*.--SEQ is not a value of --.--SEQ |
| ADAMCR-0899 | AD*.USUBJID equals --.USUBJID, AD*.--SEQ equals --.--SEQ, and the values of a variable with prefix -- which is present in both datasets are not equal |

### Uniqueness / 1:1 Relationship (109 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0054 | Within ADSL there is more than one record for a unique value of USUBJID |
| ADAMCR-0117 | Within a given value of PARAMCD, there is more than one value of ATPT for a given value of ATPTN |
| ADAMCR-0118 | Within a given value of PARAMCD, there is more than one value of ATPTN for a given value of ATPT |
| ADAMCR-0323 | Within a study, there is more than one value of APHASEN for a given value of APHASE, considering only those rows on which both variables are populated |
| ADAMCR-0324 | Within a study, there is more than one value of APHASE for a given value of APHASEN, considering only those rows on which both variables are populated |
| ADAMCR-0325 | Within a value of APERIOD, there is more than one value of ASPER for a given value of ASPERC, considering only those rows on which both variables are populated |
| ADAMCR-0326 | Within a value of APERIOD, there is more than one value of ASPERC for a given value of ASPER, considering only those rows on which both variables are populated |
| ADAMCR-0342 | Within a parameter, there is more than one value of ANRLO for a given value of ANRLOC |
| ADAMCR-0343 | Within a parameter, there is more than one value of ANRLOC for a given value of ANRLO |
| ADAMCR-0344 | Within a parameter, there is more than one value of ANRHI for a given value of ANRHIC |
| ADAMCR-0345 | Within a parameter, there is more than one value of ANRHIC for a given value of ANRHI |
| ADAMCR-0359 | There is more than one value of DTHCAUS for a given value of DTHCAUSN, considering only those rows on which both variables are populated |
| ADAMCR-0360 | There is more than one value of DTHCAUSN for a given value of DTHCAUS, considering only those rows on which both variables are populated |
| ADAMCR-0381 | Within a parameter, there is more than one value of BTOXGR for a given value of BTOXGRN, considering only those rows on which both variables are populated |
| ADAMCR-0382 | Within a parameter, there is more than one value of BTOXGRN for a given value of BTOXGR, considering only those rows on which both variables are populated |
| ADAMCR-0383 | Within a parameter, there is more than one value of BTOXGRL for a given value of BTOXGRLN, considering only those rows on which both variables are populated |
| ADAMCR-0384 | Within a parameter, there is more than one value of BTOXGRLN for a given value of BTOXGRL, considering only those rows on which both variables are populated |
| ADAMCR-0387 | Within a parameter, there is more than one value of BTOXGRH for a given value of BTOXGRHN, considering only those rows on which both variables are populated |
| ADAMCR-0388 | Within a parameter, there is more than one value of BTOXGRHN for a given value of BTOXGRH, considering only those rows on which both variables are populated |
| ADAMCR-0395 | Within a parameter, there is more than one value of ATOXGRL for a given value of ATOXGRLN, considering only those rows on which both variables are populated |
| ADAMCR-0396 | Within a parameter, there is more than one value of ATOXGRLN for a given value of ATOXGRL, considering only those rows on which both variables are populated |
| ADAMCR-0399 | Within a parameter, there is more than one value of ATOXGRH for a given value of ATOXGRHN, considering only those rows on which both variables are populated |
| ADAMCR-0400 | Within a parameter, there is more than one value of ATOXGRHN for a given value of ATOXGRH, considering only those rows on which both variables are populated |
| ADAMCR-0403 | Within a subject, there is more than one value of ATOXDSCL for a given value of PARAM, considering only those rows on which both variables are populated |
| ADAMCR-0405 | Within a subject, there is more than one value of ATOXDSCH for a given value of PARAM, considering only those rows on which both variables are populated |
| ADAMCR-0409 | There is more than one value of ABLFN for a given value of ABLFL, considering only those rows on which both variables are populated |
| ADAMCR-0410 | There is more than one value of ABLFL for a given value of ABLFN, considering only those rows on which both variables are populated |
| ADAMCR-0468 | There is more than one value of STRATAR for a given value of STRATARN, considering only those rows on which both variables are populated |
| ADAMCR-0469 | There is more than one value of STRATARN for a given value of STRATAR, considering only those rows on which both variables are populated |
| ADAMCR-0476 | There is more than one value of STRATAV for a given value of STRATAVN, considering only those rows on which both variables are populated |
| ADAMCR-0477 | There is more than one value of STRATAVN for a given value of STRATAV, considering only those rows on which both variables are populated |
| ADAMCR-0688 | Within ADDL there is more than one record for unique values of USUBJID and SPDEVID |
| ADAMCR-0693 | Within a given value of PARAMCD for a combination of device and subject, there is more than one value of BASE for a given value of BASEC, considering only those rows on which both variables are populated |
| ADAMCR-0694 | Within a given value of PARAMCD for a combination of device and subject, there is more than one value of BASEC for a given value of BASE, considering only those rows on which both variables are populated |
| ADAMCR-0696 | Within a given PARAMCD and BASETYPE for a combination of device and subject, more than one record has ABLFL equal to Y |
| ADAMCR-0714 | There is more than one value of a variable which has a suffix of GRyN for a given value of a variable with the same root name and suffix of GRy, considering only those rows on which both variables are populated |
| ADAMCR-0715 | There is more than one value of a variable which has a suffix of GRy for a given value of a variable with the same root name and suffix of GRyN, considering only those rows on which both variables are populated |
| ADAMCR-0721 | There is more than one value of TRTPN for a given value of TRTP, considering only those rows on which both variables are populated |
| ADAMCR-0722 | There is more than one value of TRTP for a given value of TRTPN, considering only those rows on which both variables are populated |
| ADAMCR-0723 | There is more than one value of TRTAN for a given value of TRTA, considering only those rows on which both variables are populated |
| ADAMCR-0724 | There is more than one value of TRTA for a given value of TRTAN, considering only those rows on which both variables are populated |
| ADAMCR-0725 | There is more than one value of APERIODC for a given value of APERIOD, considering only those rows on which both variables are populated |
| ADAMCR-0726 | There is more than one value of APERIOD for a given value of APERIODC, considering only those rows on which both variables are populated |
| ADAMCR-0727 | Within a given value of PARAMCD, there is more than one value of AVISITN for a given value of AVISIT, considering only those rows on which both variables are populated |
| ADAMCR-0728 | Within a given value of PARAMCD, there is more than one value of AVISIT for a given value of AVISITN, considering only those rows on which both variables are populated |
| ADAMCR-0732 | Within a given value of PARAMCD for a subject, there is more than one value of BASE for a given value of BASEC, considering only those rows on which both variables are populated |
| ADAMCR-0733 | Within a given value of PARAMCD for a subject, there is more than one value of BASEC for a given value of BASE, considering only those rows on which both variables are populated |
| ADAMCR-0738 | There is more than one value of PARAM for a given value of PARAMCD, considering only those rows on which both variables are populated |
| ADAMCR-0739 | There is more than one value of PARAMCD for a given value of PARAM, considering only those rows on which both variables are populated |
| ADAMCR-0740 | Within a dataset, there is more than one value of PARAM for a given value of PARAMN, considering only those rows on which both variables are populated |
| ADAMCR-0741 | Within a dataset, there is more than one value of PARAMN for a given value of PARAM, considering only those rows on which both variables are populated |
| ADAMCR-0742 | Within a given value of PARAMCD, there is more than one value of AVALC for a given value of AVAL, considering only those rows on which both variables are populated |
| ADAMCR-0743 | Within a given value of PARAMCD, there is more than one value of AVAL for a given value of AVALC, considering only those rows on which both variables are populated |
| ADAMCR-0752 | There is more than one value of TRTSEQP for a given value of TRTSEQPN, considering only those rows on which both variables are populated |
| ADAMCR-0753 | There is more than one value of TRTSEQPN for a given value of TRTSEQP, considering only those rows on which both variables are populated |
| ADAMCR-0754 | There is more than one value of TRTSEQA for a given value of TRTSEQAN, considering only those rows on which both variables are populated |
| ADAMCR-0755 | There is more than one value of TRTSEQAN for a given value of TRTSEQA, considering only those rows on which both variables are populated |
| ADAMCR-0769 | There is more than one value of AESEV for a given value of AESEVN, considering only those rows on which both variables are populated |
| ADAMCR-0770 | There is more than one value of AESEVN for a given value of AESEV, considering only those rows on which both variables are populated |
| ADAMCR-0771 | There is more than one value of ASEV for a given value of ASEVN, considering only those rows on which both variables are populated |
| ADAMCR-0772 | There is more than one value of ASEVN for a given value of ASEV, considering only those rows on which both variables are populated |
| ADAMCR-0775 | There is more than one value of AEREL for a given value of AERELN, considering only those rows on which both variables are populated |
| ADAMCR-0776 | There is more than one value of AERELN for a given value of AEREL, considering only those rows on which both variables are populated |
| ADAMCR-0777 | There is more than one value of AREL for a given value of ARELN, considering only those rows on which both variables are populated |
| ADAMCR-0778 | There is more than one value of ARELN for a given value of AREL, considering only those rows on which both variables are populated |
| ADAMCR-0781 | There is more than one value of AETOXGR for a given value of AETOXGRN, considering only those rows on which both variables are populated |
| ADAMCR-0782 | There is more than one value of AETOXGRN for a given value of AETOXGR, considering only those rows on which both variables are populated |
| ADAMCR-0783 | There is more than one value of ATOXGR for a given value of ATOXGRN, considering only those rows on which both variables are populated |
| ADAMCR-0784 | There is more than one value of ATOXGRN for a given value of ATOXGR, considering only those rows on which both variables are populated |
| ADAMCR-0797 | Within a dataset, there is more than one value of PROJIDN for a given value of PROJID, considering only those rows on which both variables are populated. |
| ADAMCR-0798 | Within a dataset, there is more than one value of PROJID for a given value of PROJIDN, considering only those rows on which both variables are populated |
| ADAMCR-0802 | Within a dataset, there is more than one value of STUDYIDN for a given value of STUDYID, considering only those rows on which both variables are populated. |
| ADAMCR-0803 | Within a dataset, there is more than one value of STUDYID for a given value of STUDYIDN, considering only those rows on which both variables are populated |
| ADAMCR-0807 | Within a dataset, there is more than one value of SUBJTYPC for a given value of SUBJTYP, considering only those rows on which both variables are populated. |
| ADAMCR-0808 | Within a dataset, there is more than one value of SUBJTYP for a given value of SUBJTYPC, considering only those rows on which both variables are populated |
| ADAMCR-0812 | Within a dataset, there is more than one value of USUBJIDN for a given value of USUBJID, considering only those rows on which both variables are populated. |
| ADAMCR-0813 | Within a dataset, there is more than one value of USUBJID for a given value of USUBJIDN, considering only those rows on which both variables are populated |
| ADAMCR-0817 | Within a dataset, there is more than one value of SUBJIDN for a given value of SUBJID, considering only those rows on which both variables are populated. |
| ADAMCR-0818 | Within a dataset, there is more than one value of SUBJID for a given value of SUBJIDN, considering only those rows on which both variables are populated |
| ADAMCR-0822 | Within a dataset, there is more than one value of SITEIDN for a given value of SITEID, considering only those rows on which both variables are populated. |
| ADAMCR-0823 | Within a dataset, there is more than one value of SITEID for a given value of SITEIDN, considering only those rows on which both variables are populated |
| ADAMCR-0827 | Within a dataset, there is more than one value of FLGREASC for a given value of FLGREAS, considering only those rows on which both variables are populated. |
| ADAMCR-0828 | Within a dataset, there is more than one value of FLGREAS for a given value of FLGREASC, considering only those rows on which both variables are populated |
| ADAMCR-0832 | Within a dataset, there is more than one value of DVIDN for a given value of DVID, considering only those rows on which both variables are populated. |
| ADAMCR-0833 | Within a dataset, there is more than one value of DVID for a given value of DVIDN, considering only those rows on which both variables are populated |
| ADAMCR-0837 | Within a dataset, there is more than one value of BLQFN for a given value of BLQFL, considering only those rows on which both variables are populated. |
| ADAMCR-0838 | Within a dataset, there is more than one value of BLQFL for a given value of BLQFN, considering only those rows on which both variables are populated |
| ADAMCR-0842 | Within a dataset, there is more than one value of ALQFN for a given value of ALQFL, considering only those rows on which both variables are populated. |
| ADAMCR-0843 | Within a dataset, there is more than one value of ALQFL for a given value of ALQFN, considering only those rows on which both variables are populated |
| ADAMCR-0847 | Within a dataset, there is more than one value of FORMN for a given value of FORM, considering only those rows on which both variables are populated. |
| ADAMCR-0848 | Within a dataset, there is more than one value of FORM for a given value of FORMN, considering only those rows on which both variables are populated |
| ADAMCR-0852 | Within a dataset, there is more than one value of ROUTEN for a given value of ROUTE, considering only those rows on which both variables are populated. |
| ADAMCR-0853 | Within a dataset, there is more than one value of ROUTE for a given value of ROUTEN, considering only those rows on which both variables are populated |
| ADAMCR-0857 | Within a dataset, there is more than one value of ACYCLEC for a given value of ACYCLE, considering only those rows on which both variables are populated. |
| ADAMCR-0858 | Within a dataset, there is more than one value of ACYCLE for a given value of ACYCLEC, considering only those rows on which both variables are populated |
| ADAMCR-0862 | Within a dataset, there is more than one value of COHORTC for a given value of COHORT, considering only those rows on which both variables are populated. |
| ADAMCR-0863 | Within a dataset, there is more than one value of COHORT for a given value of COHORTC, considering only those rows on which both variables are populated |
| ADAMCR-0867 | Within a dataset, there is more than one value of RACEN for a given value of RACE, considering only those rows on which both variables are populated. |
| ADAMCR-0868 | Within a dataset, there is more than one value of RACE for a given value of RACEN, considering only those rows on which both variables are populated |
| ADAMCR-0872 | Within a dataset, there is more than one value of SEXN for a given value of SEX, considering only those rows on which both variables are populated. |
| ADAMCR-0873 | Within a dataset, there is more than one value of SEX for a given value of SEXN, considering only those rows on which both variables are populated |
| ADAMCR-0877 | Within a dataset, there is more than one value of COUNTRYN for a given value of COUNTRY, considering only those rows on which both variables are populated. |
| ADAMCR-0878 | Within a dataset, there is more than one value of COUNTRY for a given value of COUNTRYN, considering only those rows on which both variables are populated |
| ADAMCR-0882 | Within a dataset, there is more than one value of COUNTRYL for a given value of COUNTRY, considering only those rows on which both variables are populated. |
| ADAMCR-0883 | Within a dataset, there is more than one value of COUNTRY for a given value of COUNTRYL, considering only those rows on which both variables are populated |
| ADAMCR-0891 | On a given record, there is more than one value of COHORTN for a given value of COHORT, considering only those rows on which both variables are populated |
| ADAMCR-0892 | On a given record, there is more than one value of COHORT for a given value of COHORTN, considering only those rows on which both variables are populated |
| ADAMCR-0893 | On a given record, there is more than one value of ACYCLEC for a given value of ACYCLE, considering only those rows on which both variables are populated |
| ADAMCR-0894 | On a given record, there is more than one value of ACYCLE for a given value of ACYCLEC, considering only those rows on which both variables are populated |

### Grouped Check (1 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0735 | Within a given value of PARAMCD where either BASE or BASEC are populated, BASETYPE is populated for at least one record and is not populated for at least one record |

### Cross-Dataset (2 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0053 | The value of USUBJID is not present in SDTM.DM |
| ADAMCR-0256 | The values of USUBJID are not present in ADSL |

### Conditional Presence (86 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0112 | ARELTM is populated and ARELTMU is not populated |
| ADAMCR-0159 | AWTDIFF is populated and AWTARGET is not populated |
| ADAMCR-0268 | ADURN is populated and ADURU is not populated |
| ADAMCR-0379 | On a given record, BTOXGRN is populated and BTOXGR is not populated |
| ADAMCR-0380 | On a given record, BTOXGR is populated and BTOXGRN is not populated |
| ADAMCR-0385 | On a given record, BTOXGRL is populated and BTOXGRLN is not populated |
| ADAMCR-0386 | On a given record, BTOXGRLN is populated and BTOXGRL is not populated |
| ADAMCR-0389 | On a given record, BTOXGRH is populated and BTOXGRHN is not populated |
| ADAMCR-0390 | On a given record, BTOXGRHN is populated and BTOXGRH is not populated |
| ADAMCR-0391 | On a given record, ATOXGR is populated and ATOXGRN is not populated |
| ADAMCR-0392 | On a given record, ATOXGRN is populated and ATOXGR is not populated |
| ADAMCR-0393 | On a given record, ATOXGRL is populated and ATOXGRLN is not populated |
| ADAMCR-0394 | On a given record, ATOXGRLN is populated and ATOXGRL is not populated |
| ADAMCR-0397 | On a given record, ATOXGRH is populated and ATOXGRHN is not populated |
| ADAMCR-0398 | On a given record, ATOXGRHN is populated and ATOXGRH is not populated |
| ADAMCR-0401 | AVAL is not populated or ATOXGRL is not populated, and ATOXDSCL is populated |
| ADAMCR-0402 | AVAL is not populated or ATOXGRH is not populated, and ATOXDSCH is populated |
| ADAMCR-0407 | On a given record, ABLFN is populated and ABLFL is not populated |
| ADAMCR-0408 | On a given record, ABLFL is populated and ABLFN is not populated |
| ADAMCR-0415 | On a given record, TRTSEQPN is populated and TRTSEQP is not populated |
| ADAMCR-0416 | On a given record, TRTSEQP is populated and TRTSEQPN is not populated |
| ADAMCR-0417 | On a given record, TRTSEQAN is populated and TRTSEQA is not populated |
| ADAMCR-0418 | On a given record, TRTSEQA is populated and TRTSEQAN is not populated |
| ADAMCR-0429 | On a given record, APERIOD is populated and APERIODC is not populated |
| ADAMCR-0430 | On a given record, APERIODC is populated and APERIOD is not populated |
| ADAMCR-0431 | On a given record, APHASE is populated and APHASEN is not populated |
| ADAMCR-0432 | On a given record, APHASEN is populated and APHASE is not populated |
| ADAMCR-0433 | On a given record, ASPER is populated and ASPERC is not populated |
| ADAMCR-0434 | On a given record, ASPERC is populated and ASPER is not populated |
| ADAMCR-0435 | On a given record, ATPT is populated and ATPTN is not populated |
| ADAMCR-0436 | On a given record, ATPTN is populated and ATPT is not populated |
| ADAMCR-0447 | On a given record, AVISITN is populated and AVISIT is not populated |
| ADAMCR-0448 | On a given record, DTHCAUS is populated and DTHCAUSN is not populated |
| ADAMCR-0449 | On a given record, DTHCAUSN is populated and DTHCAUS is not populated |
| ADAMCR-0456 | On a given record, TRTA is populated and TRTAN is not populated |
| ADAMCR-0457 | On a given record, TRTAN is populated and TRTA is not populated |
| ADAMCR-0458 | On a given record, TRTP is populated and TRTPN is not populated |
| ADAMCR-0459 | On a given record, TRTPN is populated and TRTP is not populated |
| ADAMCR-0466 | On a given record, STRATAR is populated and STRATARN is not populated |
| ADAMCR-0467 | On a given record, STRATARN is populated and STRATAR is not populated |
| ADAMCR-0474 | On a given record, STRATAV is populated and STRATAVN is not populated |
| ADAMCR-0475 | On a given record, STRATAVN is populated and STRATAV is not populated |
| ADAMCR-0488 | On a given record, ONTRTFL is populated and ONTRTFN is not populated |
| ADAMCR-0489 | On a given record, ONTRTFN is populated and ONTRTFL is not populated |
| ADAMCR-0490 | On a given record, LVOTFL is populated and LVOTFN is not populated |
| ADAMCR-0491 | On a given record, LVOTFN is populated and LVOTFL is not populated |
| ADAMCR-0501 | On a given record, ASPER is populated and APERIOD is not populated |
| ADAMCR-0663 | On a given record, COHORTN is populated and COHORT is not populated |
| ADAMCR-0667 | DOSPCTDF is populated and TRTA is not populated |
| ADAMCR-0668 | On a given record, ACYCLE is populated and ACYCLEC is not populated |
| ADAMCR-0795 | PROJID is populated and PROJIDN is not populated |
| ADAMCR-0796 | PROJIDN is populated and PROJID is not populated |
| ADAMCR-0800 | STUDYID is populated and STUDYIDN is not populated |
| ADAMCR-0801 | STUDYIDN is populated and STUDYID is not populated |
| ADAMCR-0805 | SUBJTYP is populated and SUBJTYPC is not populated |
| ADAMCR-0806 | SUBJTYPC is populated and SUBJTYP is not populated |
| ADAMCR-0810 | USUBJID is populated and USUBJIDN is not populated |
| ADAMCR-0811 | USUBJIDN is populated and USUBJID is not populated |
| ADAMCR-0815 | SUBJID is populated and SUBJIDN is not populated |
| ADAMCR-0816 | SUBJIDN is populated and SUBJID is not populated |
| ADAMCR-0820 | SITEID is populated and SITEIDN is not populated |
| ADAMCR-0821 | SITEIDN is populated and SITEID is not populated |
| ADAMCR-0825 | FLGREAS is populated and FLGREASC is not populated |
| ADAMCR-0826 | FLGREASC is populated and FLGREAS is not populated |
| ADAMCR-0830 | DVID is populated and DVIDN is not populated |
| ADAMCR-0831 | DVIDN is populated and DVID is not populated |
| ADAMCR-0835 | BLQFL is populated and BLQFN is not populated |
| ADAMCR-0836 | BLQFN is populated and BLQFL is not populated |
| ADAMCR-0840 | ALQFL is populated and ALQFN is not populated |
| ADAMCR-0841 | ALQFN is populated and ALQFL is not populated |
| ADAMCR-0845 | FORM is populated and FORMN is not populated |
| ADAMCR-0846 | FORMN is populated and FORM is not populated |
| ADAMCR-0850 | ROUTE is populated and ROUTEN is not populated |
| ADAMCR-0851 | ROUTEN is populated and ROUTE is not populated |
| ADAMCR-0855 | ACYCLE is populated and ACYCLEC is not populated |
| ADAMCR-0856 | ACYCLEC is populated and ACYCLE is not populated |
| ADAMCR-0860 | COHORT is populated and COHORTC is not populated |
| ADAMCR-0861 | COHORTC is populated and COHORT is not populated |
| ADAMCR-0865 | RACE is populated and RACEN is not populated |
| ADAMCR-0866 | RACEN is populated and RACE is not populated |
| ADAMCR-0870 | SEX is populated and SEXN is not populated |
| ADAMCR-0871 | SEXN is populated and SEX is not populated |
| ADAMCR-0875 | COUNTRY is populated and COUNTRYN is not populated |
| ADAMCR-0876 | COUNTRYN is populated and COUNTRY is not populated |
| ADAMCR-0880 | COUNTRY is populated and COUNTRYL is not populated |
| ADAMCR-0881 | COUNTRYL is populated and COUNTRY is not populated |

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

### Value Check (29 rules)

| Core ID | Description |
|---------|-------------|
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
| ADAMCR-0366 | RANDDT is not present when RANDFL is equal to Y for at least one record |
| ADAMCR-0367 | The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.ACTARM is not equal to DM.ACTARM |
| ADAMCR-0494 | ABLFL is equal to "N" |
| ADAMCR-0648 | TRTEMwFL is Y but TRTEMFL is not Y |
| ADAMCR-0650 | ONTRTwFL is Y but ONTRTFL is not Y |
| ADAMCR-0695 | BASETYPE is present, BASE is populated, and BASE is not equal to AVAL where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a combination of device and subject |
| ADAMCR-0698 | BASETYPE is not present, BASE is populated, and BASE is not equal to AVAL where ABLFL is equal to Y for a given value of PARAMCD for a combination of device and subject |
| ADAMCR-0699 | BASETYPE is not present, BNRIND is populated, and BNRIND is not equal to ANRIND where ABLFL is equal to Y for a given value of PARAMCD for a combination of device and subject |
| ADAMCR-0720 | A non-missing value of TRTP is not equal to at least one value of the character planned treatment variables in ADSL defined in the IG |
| ADAMCR-0744 | BASETYPE is populated, BTOXGR is populated, and BTOXGR is not equal to ATOXGR where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject |
| ADAMCR-0745 | BASETYPE is populated, BNRIND is populated, and BNRIND is not equal to ANRIND where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject |
| ADAMCR-0789 | BASETYPE is populated, BASE is populated, and BASE is not equal to AVAL where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject |
| ADAMCR-0897 | A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG |

### Metadata Check (6 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0017 | The length of a character value is greater than 200 characters |
| ADAMCR-0143 | PARAMCD has more than 8 characters in length |
| ADAMCR-0144 | PARAMCD starts with a character other than a letter |
| ADAMCR-0145 | PARAMCD has characters that are not letters, digits, and underscores |
| ADAMCR-0496 | A dataset name does not start with "AD" when dataset class is not missing |
| ADAMCR-0497 | A dataset name starts with "AD" when the dataset class is missing |

### Comparison (3 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0361 | The value of ASTDT is greater than the value of AENDT, considering only those rows on which both variables are populated |
| ADAMCR-0362 | The value of ASTDTM is greater than the value of AENDTM, considering only those rows on which both variables are populated |
| ADAMCR-0687 | The value of DEVIPDT is greater than the value of DEVXPDT, considering only rows on which both variables are populated |

### Other (17 rules)

| Core ID | Description |
|---------|-------------|
| ADAMCR-0169 | The value of CNSR is not a positive integer or 0 |
| ADAMCR-0196 | PARAM is not populated |
| ADAMCR-0197 | PARAMCD is not populated |
| ADAMCR-0500 | There is a value of APHASE without a matching value in an ADSL variable APHASEw |
| ADAMCR-0582 | Within a given value of PARAMCD for a subject, BCHG is populated and is not equal to BASE-AVAL |
| ADAMCR-0586 | Within a given value of PARAMCD for a subject, PBCHG is populated and is not equal to ((BASE-AVAL)/AVAL)*100 |
| ADAMCR-0662 | TMPCTDF is populated and ARRLT and NRRLT are not populated |
| ADAMCR-0664 | On a given record, COHORT is populated and COHORTN is present and not populated |
| ADAMCR-0666 | DOSPCTDF is populated and DOSEP and DOSEA are not populated |
| ADAMCR-0669 | On a given record, ACYCLEC is populated and ACYCLE is present and not populated |
| ADAMCR-0691 | Within a given value of PARAMCD for a combination of device and subject, BASE is populated and there is not at least one record with ABLFL equal to Y |
| ADAMCR-0692 | Within a given value of PARAMCD for a combination of device and subject, BASEC is populated and there is not at least one record with ABLFL equal to Y |
| ADAMCR-0697 | Within a given PARAMCD for a combination of device and subject, more than one record has ABLFL equal to Y and BASETYPE is not present |
| ADAMCR-0700 | Within a given value of PARAMCD for a combination of device and subject, CHG is populated and is not equal to AVAL - BASE |
| ADAMCR-0701 | Within a given value of PARAMCD for a combination of device and subject, PCHG is populated and is not equal to ((AVAL - BASE)/BASE)*100 |
| ADAMCR-0704 | Within a given value of PARAMCD for a combination of device and subject, BCHG is populated and is not equal to BASE-AVAL |
| ADAMCR-0705 | Within a given value of PARAMCD for a combination of device and subject, PBCHG is populated and is not equal to ((BASE-AVAL)/AVAL)*100 |

---

## Manual (5 rules)

Rules requiring group-level existential logic not yet expressible in CORE Check format.

| Core ID | Description |
|---------|-------------|
| ADAMCR-0127 | Within a given value of PARAMCD for a subject, BASE is populated and there is not at least one record with ABLFL equal to Y |
| ADAMCR-0128 | Within a given value of PARAMCD for a subject, BASEC is populated and there is not at least one record with ABLFL equal to Y |
| ADAMCR-0132 | R2BASE is not equal to AVAL divided by BASE |
| ADAMCR-0154 | Within a given PARAMCD and BASETYPE for a subject, more than one record has ABLFL equal to Y |
| ADAMCR-0155 | Within a given PARAMCD for a subject, more than one record has ABLFL equal to Y and BASETYPE is not present |

