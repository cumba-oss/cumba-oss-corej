# SDTMIG 3.4 Non-Extensible Controlled Terminology

Variables with non-extensible (closed) codelists extracted from
SDTMIG v3.4. These codelists do not allow sponsor-defined values;
all values must be from the published CDISC Controlled Terminology.

This list can be used to generate validation rules that check
whether variable values are within the allowed codelist values.

**Total:** 41 non-extensible codelists covering 411 variable-codelist pairs

## Codelist-Variable Mapping

| Codelist | Description | Variables | Count |
|----------|-------------|-----------|-------|
| ACN | Action Taken with Study Treatment | AEACN | 1 |
| AESEV | Severity/Intensity Scale for Adverse Events | AESEV | 1 |
| AGEU | Age Unit | AGEU | 1 |
| ARMNULRS | Reason Arm and/or Actual Arm is Null | ARMNRS | 1 |
| COLSTYP | Collected Sample Type | CPCOLSRT, LBCOLSRT | 2 |
| DIR | Directionality | CVDIR, ECDIR, EXDIR, MBDIR, MIDIR, MKDIR, ... (13 total) | 13 |
| DOMAIN | CDISC Domain Abbreviation | RDOMAIN | 1 |
| DSCAT | Disposition Event Category | DSCAT | 1 |
| EPOCH | Epoch | EPOCH | 1 |
| ETHNIC | Ethnicity | ETHNIC | 1 |
| EVAL | Evaluator | COEVAL, CVEVAL, DDEVAL, EGEVAL, FAEVAL, MIEVAL, ... (19 total) | 19 |
| FREQ | Frequency | AGDOSFRQ, CMDOSFRQ, ECDOSFRQ, EXDOSFRQ, PRDOSFRQ, SUDOSFRQ | 6 |
| FRM | Pharmaceutical Dosage Form | AGDOSFRM, CMDOSFRM, ECDOSFRM, EXDOSFRM, MLDOSFRM, PRDOSFRM, SUDOSFRM | 7 |
| IECAT | Inclusion/Exclusion Category | IECAT | 1 |
| LAT | Laterality | CVLAT, ECLAT, EXLAT, FALAT, MBLAT, MILAT, ... (17 total) | 17 |
| LOC | Anatomical Location | AELOC, BELOC, CVLOC, ECLOC, EXLOC, FALOC, ... (19 total) | 19 |
| MEDEVAL | Medical Evaluator | COEVALID, CVEVALID, EGEVALID, MKEVALID, MSEVALID, NVEVALID, ... (12 total) | 12 |
| METHOD | Method | BSMETHOD, CPMETHOD, CVMETHOD, GFMETHOD, ISMETHOD, LBMETHOD, ... (19 total) | 19 |
| NCOMPLT | Completion/Reason for Non-Completion | DSDECOD | 1 |
| ND | Not Done | AGSTAT, BSSTAT, CESTAT, CMSTAT, CPSTAT, CVSTAT, ... (36 total) | 36 |
| NORMABNM | Normal/Abnormal | EGSTRESC | 1 |
| NRIND | Normal Range Indicator | CPNRIND, ISNRIND, LBNRIND, MSNRIND, OENRIND | 5 |
| NY | No Yes Response | AECONTRT, AEPRESP, AESCAN, AESCONG, AESDISAB, AESDTH, ... (123 total) | 123 |
| ONCRSR | Oncology Response | RSSTRESC | 1 |
| OTHEVENT | Other Event | DSDECOD | 1 |
| OUT | Outcome of Event | AEOUT | 1 |
| PORTOT | Portion or Totality | ECPORTOT, OEPORTOT, PRPORTOT, TUPORTOT | 4 |
| POSITION | Position of Subject During Observation | CVPOS, EGPOS, FTPOS, MKPOS, REPOS, VSPOS | 6 |
| PROTMLST | Protocol Milestone | DSDECOD | 1 |
| RACE | Race | RACE, RACE01, RACE02, RACE03, RACE04, RACE05, RACE06, RACE07 | 8 |
| RELTYPE | Relationship Type | RELTYPE | 1 |
| RESTYPRS | Result or Finding Type | CPRESTYP, LBRESTYP | 2 |
| ROUTE | Route of Administration | AGROUTE, CMROUTE, ECROUTE, EXROUTE, PRROUTE, SUROUTE | 6 |
| RSLSCLRS | Result or Finding Scale | CPRESSCL, LBRESSCL | 2 |
| SEVRS | Severity | CESEV | 1 |
| SEX | Sex | SEX | 1 |
| SSTATRS | Study Status Result | SSSTRESC | 1 |
| STENRF | Start/End Relative to Reference Period | AEENRF, AEENRTPT, AGENRF, AGENRTPT, AGSTRF, AGSTRTPT, ... (26 total) | 26 |
| TSPARM | Trial Summary Parameter | TSPARM | 1 |
| TSPARMCD | Trial Summary Parameter Short Name | TSPARMCD | 1 |
| UNIT | Unit | AGDOSU, BSORRESU, BSSTRESU, CMDOSU, CPORRESU, CPSTRESU, ... (58 total) | 58 |

## Full Variable List by Codelist

### ACN — Action Taken with Study Treatment

**1 variables:**

- `AEACN`

### AESEV — Severity/Intensity Scale for Adverse Events

**1 variables:**

- `AESEV`

### AGEU — Age Unit

**1 variables:**

- `AGEU`

### ARMNULRS — Reason Arm and/or Actual Arm is Null

**1 variables:**

- `ARMNRS`

### COLSTYP — Collected Sample Type

**2 variables:**

- `CPCOLSRT`
- `LBCOLSRT`

### DIR — Directionality

**13 variables:**

- `CVDIR`
- `ECDIR`
- `EXDIR`
- `MBDIR`
- `MIDIR`
- `MKDIR`
- `MSDIR`
- `NVDIR`
- `OEDIR`
- `PRDIR`
- `REDIR`
- `TUDIR`
- `URDIR`

### DOMAIN — CDISC Domain Abbreviation

**1 variables:**

- `RDOMAIN`

### DSCAT — Disposition Event Category

**1 variables:**

- `DSCAT`

### EPOCH — Epoch

**1 variables:**

- `EPOCH`

### ETHNIC — Ethnicity

**1 variables:**

- `ETHNIC`

### EVAL — Evaluator

**19 variables:**

- `COEVAL`
- `CVEVAL`
- `DDEVAL`
- `EGEVAL`
- `FAEVAL`
- `MIEVAL`
- `MKEVAL`
- `MSEVAL`
- `NVEVAL`
- `OEEVAL`
- `PEEVAL`
- `QEVAL`
- `REEVAL`
- `RSEVAL`
- `SREVAL`
- `SSEVAL`
- `TREVAL`
- `TUEVAL`
- `UREVAL`

### FREQ — Frequency

**6 variables:**

- `AGDOSFRQ`
- `CMDOSFRQ`
- `ECDOSFRQ`
- `EXDOSFRQ`
- `PRDOSFRQ`
- `SUDOSFRQ`

### FRM — Pharmaceutical Dosage Form

**7 variables:**

- `AGDOSFRM`
- `CMDOSFRM`
- `ECDOSFRM`
- `EXDOSFRM`
- `MLDOSFRM`
- `PRDOSFRM`
- `SUDOSFRM`

### IECAT — Inclusion/Exclusion Category

**1 variables:**

- `IECAT`

### LAT — Laterality

**17 variables:**

- `CVLAT`
- `ECLAT`
- `EXLAT`
- `FALAT`
- `MBLAT`
- `MILAT`
- `MKLAT`
- `MSLAT`
- `NVLAT`
- `OELAT`
- `PELAT`
- `PRLAT`
- `RELAT`
- `SRLAT`
- `TULAT`
- `URLAT`
- `VSLAT`

### LOC — Anatomical Location

**19 variables:**

- `AELOC`
- `BELOC`
- `CVLOC`
- `ECLOC`
- `EXLOC`
- `FALOC`
- `MBLOC`
- `MILOC`
- `MKLOC`
- `MSLOC`
- `NVLOC`
- `OELOC`
- `PELOC`
- `PRLOC`
- `RELOC`
- `SRLOC`
- `TULOC`
- `URLOC`
- `VSLOC`

### MEDEVAL — Medical Evaluator

**12 variables:**

- `COEVALID`
- `CVEVALID`
- `EGEVALID`
- `MKEVALID`
- `MSEVALID`
- `NVEVALID`
- `OEEVALID`
- `REEVALID`
- `RSEVALID`
- `TREVALID`
- `TUEVALID`
- `UREVALID`

### METHOD — Method

**19 variables:**

- `BSMETHOD`
- `CPMETHOD`
- `CVMETHOD`
- `GFMETHOD`
- `ISMETHOD`
- `LBMETHOD`
- `MBMETHOD`
- `MIMETHOD`
- `MKMETHOD`
- `MSMETHOD`
- `NVMETHOD`
- `OEMETHOD`
- `PCMETHOD`
- `PEMETHOD`
- `REMETHOD`
- `SRMETHOD`
- `TRMETHOD`
- `TUMETHOD`
- `URMETHOD`

### NCOMPLT — Completion/Reason for Non-Completion

**1 variables:**

- `DSDECOD`

### ND — Not Done

**36 variables:**

- `AGSTAT`
- `BSSTAT`
- `CESTAT`
- `CMSTAT`
- `CPSTAT`
- `CVSTAT`
- `DASTAT`
- `EGSTAT`
- `FASTAT`
- `FTSTAT`
- `GFSTAT`
- `HOSTAT`
- `ISSTAT`
- `LBSTAT`
- `MBSTAT`
- `MHSTAT`
- `MISTAT`
- `MKSTAT`
- `MLSTAT`
- `MSSTAT`
- `NVSTAT`
- `OESTAT`
- `PCSTAT`
- `PESTAT`
- `PPSTAT`
- `QSSTAT`
- `RESTAT`
- `RPSTAT`
- `RSSTAT`
- `SCSTAT`
- `SRSTAT`
- `SSSTAT`
- `SUSTAT`
- `TRSTAT`
- `URSTAT`
- `VSSTAT`

### NORMABNM — Normal/Abnormal

**1 variables:**

- `EGSTRESC`

### NRIND — Normal Range Indicator

**5 variables:**

- `CPNRIND`
- `ISNRIND`
- `LBNRIND`
- `MSNRIND`
- `OENRIND`

### NY — No Yes Response

**123 variables:**

- `AECONTRT`
- `AEPRESP`
- `AESCAN`
- `AESCONG`
- `AESDISAB`
- `AESDTH`
- `AESER`
- `AESHOSP`
- `AESINTV`
- `AESLIFE`
- `AESMIE`
- `AESOD`
- `AEUNANT`
- `AGOCCUR`
- `AGPRESP`
- `BSBLFL`
- `CEOCCUR`
- `CEPRESP`
- `CMOCCUR`
- `CMPRESP`
- `CPBLFL`
- `CPCLSIG`
- `CPDRVFL`
- `CPLOBXFL`
- `CVBLFL`
- `CVDRVFL`
- `CVLOBXFL`
- `DTHFL`
- `ECFAST`
- `ECOCCUR`
- `ECPRESP`
- `EGBLFL`
- `EGCLSIG`
- `EGDRVFL`
- `EGLOBXFL`
- `EXFAST`
- `FABLFL`
- `FALOBXFL`
- `FTBLFL`
- `FTDRVFL`
- `FTLOBXFL`
- `GFBLFL`
- `GFDRVFL`
- `HOOCCUR`
- `HOPRESP`
- `IEORRES`
- `IESTRESC`
- `ISBLFL`
- `ISDRVFL`
- `ISLOBXFL`
- `ISSPCUFL`
- `LBBLFL`
- `LBCLSIG`
- `LBDRVFL`
- `LBFAST`
- `LBLOBXFL`
- `LBPTFL`
- `LBSPCUFL`
- `MBBLFL`
- `MBDRVFL`
- `MBFAST`
- `MBLOBXFL`
- `MHOCCUR`
- `MHPRESP`
- `MIBLFL`
- `MILOBXFL`
- `MKBLFL`
- `MKDRVFL`
- `MKLOBXFL`
- `MLOCCUR`
- `MLPRESP`
- `MSACPTFL`
- `MSBLFL`
- `MSDRVFL`
- `MSFAST`
- `MSLOBXFL`
- `NVBLFL`
- `NVDRVFL`
- `NVLOBXFL`
- `OEACPTFL`
- `OEBLFL`
- `OEDRVFL`
- `OELOBXFL`
- `PCDRVFL`
- `PCFAST`
- `PEBLFL`
- `PELOBXFL`
- `PROCCUR`
- `PRPRESP`
- `QSBLFL`
- `QSDRVFL`
- `QSLOBXFL`
- `REBLFL`
- `REDRVFL`
- `RELOBXFL`
- `RPBLFL`
- `RPDRVFL`
- `RPLOBXFL`
- `RSACPTFL`
- `RSBLFL`
- `RSDRVFL`
- `RSLOBXFL`
- `SRBLFL`
- `SRLOBXFL`
- `SUOCCUR`
- `SUPRESP`
- `SVEPCHGI`
- `SVOCCUR`
- `SVPRESP`
- `TMRPT`
- `TRACPTFL`
- `TRBLFL`
- `TRLOBXFL`
- `TUACPTFL`
- `TUBLFL`
- `TULOBXFL`
- `URBLFL`
- `URDRVFL`
- `URLOBXFL`
- `VSBLFL`
- `VSCLSIG`
- `VSDRVFL`
- `VSLOBXFL`

### ONCRSR — Oncology Response

**1 variables:**

- `RSSTRESC`

### OTHEVENT — Other Event

**1 variables:**

- `DSDECOD`

### OUT — Outcome of Event

**1 variables:**

- `AEOUT`

### PORTOT — Portion or Totality

**4 variables:**

- `ECPORTOT`
- `OEPORTOT`
- `PRPORTOT`
- `TUPORTOT`

### POSITION — Position of Subject During Observation

**6 variables:**

- `CVPOS`
- `EGPOS`
- `FTPOS`
- `MKPOS`
- `REPOS`
- `VSPOS`

### PROTMLST — Protocol Milestone

**1 variables:**

- `DSDECOD`

### RACE — Race

**8 variables:**

- `RACE`
- `RACE01`
- `RACE02`
- `RACE03`
- `RACE04`
- `RACE05`
- `RACE06`
- `RACE07`

### RELTYPE — Relationship Type

**1 variables:**

- `RELTYPE`

### RESTYPRS — Result or Finding Type

**2 variables:**

- `CPRESTYP`
- `LBRESTYP`

### ROUTE — Route of Administration

**6 variables:**

- `AGROUTE`
- `CMROUTE`
- `ECROUTE`
- `EXROUTE`
- `PRROUTE`
- `SUROUTE`

### RSLSCLRS — Result or Finding Scale

**2 variables:**

- `CPRESSCL`
- `LBRESSCL`

### SEVRS — Severity

**1 variables:**

- `CESEV`

### SEX — Sex

**1 variables:**

- `SEX`

### SSTATRS — Study Status Result

**1 variables:**

- `SSSTRESC`

### STENRF — Start/End Relative to Reference Period

**26 variables:**

- `AEENRF`
- `AEENRTPT`
- `AGENRF`
- `AGENRTPT`
- `AGSTRF`
- `AGSTRTPT`
- `CEENRF`
- `CEENRTPT`
- `CESTRF`
- `CESTRTPT`
- `CMENRF`
- `CMENRTPT`
- `CMSTRF`
- `CMSTRTPT`
- `HOENRTPT`
- `HOSTRTPT`
- `MHENRF`
- `MHENRTPT`
- `PRENRTPT`
- `PRSTRTPT`
- `RSENRTPT`
- `RSSTRTPT`
- `SUENRF`
- `SUENRTPT`
- `SUSTRF`
- `SUSTRTPT`

### TSPARM — Trial Summary Parameter

**1 variables:**

- `TSPARM`

### TSPARMCD — Trial Summary Parameter Short Name

**1 variables:**

- `TSPARMCD`

### UNIT — Unit

**58 variables:**

- `AGDOSU`
- `BSORRESU`
- `BSSTRESU`
- `CMDOSU`
- `CPORRESU`
- `CPSTRESU`
- `CVORRESU`
- `CVSTRESU`
- `DAORRESU`
- `DASTRESU`
- `ECDOSU`
- `ECPSTRGU`
- `EGORRESU`
- `EGSTRESU`
- `EXDOSU`
- `FAORRESU`
- `FASTRESU`
- `FTORRESU`
- `FTSTRESU`
- `GFORRESU`
- `GFSTRESU`
- `ISORRESU`
- `ISSTRESU`
- `LBORRESU`
- `LBSTRESU`
- `MBORRESU`
- `MBSTRESU`
- `MIORRESU`
- `MISTRESU`
- `MKORRESU`
- `MKSTRESU`
- `MLDOSU`
- `MSCONCU`
- `MSORRESU`
- `MSSTRESU`
- `NVORRESU`
- `NVSTRESU`
- `OEORRESU`
- `OESTRESU`
- `PEORRESU`
- `PRDOSU`
- `QSORRESU`
- `QSSTRESU`
- `REORRESU`
- `RESTRESU`
- `RPORRESU`
- `RPSTRESU`
- `RSORRESU`
- `RSSTRESU`
- `SCORRESU`
- `SCSTRESU`
- `SRORRESU`
- `SRSTRESU`
- `SUDOSU`
- `TRORRESU`
- `TRSTRESU`
- `URORRESU`
- `URSTRESU`
