# SDTMIG 3.4 Conformance Rules Documentation

Auto-generated documentation from `rules/rules-sdtmig-3-4.json`.

**Total Rules:** 430  
**Standard:** SDTMIG v3.4  
**Source:** CDISC Rules Engine (CORE)  

## Summary Statistics

### By Rule Type

| Rule Type | Count |
|-----------|-------|
| Dataset Metadata Check | 9 |
| Define Item Metadata Check against Library Metadata | 2 |
| Domain Presence Check | 12 |
| Record Data | 394 |
| Value Check with Dataset Metadata | 1 |
| Value Check with Variable Metadata | 2 |
| Variable Metadata Check | 9 |
| Variable Metadata Check against Library Metadata | 1 |

### By Executability

| Executability | Count |
|--------------|-------|
| Fully Executable | 412 |
| Partially Executable | 8 |
| Partially Executable - Possible Overreporting | 7 |
| Partially Executable - Possible Underreporting | 3 |

### By Sensitivity

| Sensitivity | Count |
|------------|-------|
| Dataset | 108 |
| Group | 1 |
| Record | 321 |

## Referenced Documents

The following specification documents are cited by rules in this package:

### SDTMIG (Implementation Guide)

- AP Guide v1.0|IG v3.2
- AP Guide v1.0|IG v3.3
- IG 3.3
- IG 3.4
- IG V3.2
- IG V3.3
- IG V3.4
- IG v.3.0
- IG v.3.1
- IG v.3.1.1
- IG v1.1
- IG v3.0
- IG v3.1
- IG v3.1.1
- IG v3.2
- IG v3.2|AP Guide v1.0
- IG v3.2|Model v1.4
- IG v3.3
- IG v3.3|AP Guide v1.0
- IG v3.3|Model v1.7
- IG v3.4
- Model v1.4|IG v3.2
- Model v1.7|IG v3.3
- Model v2.0|IG v3.4
- SDTM v2.1|TIG v1.0
- SDTMIG v3.2
- SDTMIG v3.3
- SDTMIG v3.4
- SENDIG v3.0
- SENDIG v3.1
- SENDIG v3.1.1
- TIG v1.0

### SDTM (Tabulation Model)

- Model 1.4
- Model 1.7
- Model 2.0
- Model v1.4
- Model v1.7
- Model v2.0
- SDTM Model v1.4
- SDTM Model v1.7
- SDTM Model v2.0
- SDTM v1.0
- SDTM v1.4
- SDTM v1.7
- SDTM v2.0
- SDTM v2.1
- SDTM v3.4

### TIG (Trial Implementation Guide)

- TIG 1.0

### AP Guide (Analysis & Processing)

- AP Guide v1.0

### FDA

- FDA

### Other

- Time points will be represented using both --TPT and --TPTNUM. There will be a one-to-one relationship between values of --TPT and --TPTNUM.

### Source Specifications

| Document | CDISC URL | Status |
|----------|-----------|--------|
| SDTMIG v3.4 | https://www.cdisc.org/standards/foundational/sdtmig/sdtmig-v3-4 | Published |
| SDTM v2.0 | https://www.cdisc.org/standards/foundational/sdtm/sdtm-v2-0 | Published |
| SDTM v1.7 | https://www.cdisc.org/standards/foundational/sdtm/sdtm-v1-7 | Published |
| SDTM v1.4 | https://www.cdisc.org/standards/foundational/sdtm/sdtm-v1-4 | Published |
| SENDIG v3.1.1 | https://www.cdisc.org/standards/foundational/send/sendig-v3-1-1 | Published |
| SENDIG v3.1 | https://www.cdisc.org/standards/foundational/send/sendig-v3-1 | Published |
| SENDIG v3.0 | https://www.cdisc.org/standards/foundational/send/sendig-v3-0 | Published |
| SDTM & SDTMIG Conformance Rules v2.0 | https://www.cdisc.org/standards/foundational/sdtmig | Published |

> **Note:** CDISC specification documents require a CDISC membership or CDISC Library API key to download.
> They are proprietary and cannot be redistributed. See the `specifications/` directory for any locally available copies.

---

## Rule Index

| Core ID | Description | Rule Type | Sensitivity | Executability |
|---------|-------------|-----------|-------------|---------------|
| [CORE-000001](#core-000001) | Raise an error when IECAT is equal to 'INCLUSION' and IEORRES is not equal to 'N'. | Record Data | Record | Fully Executable |
| [CORE-000002](#core-000002) | Raise an error when SESTDTC is null. | Record Data | Record | Fully Executable |
| [CORE-000003](#core-000003) | Raise an error when TRLOC is present. | Record Data | Dataset | Fully Executable |
| [CORE-000004](#core-000004) | When ECOCCUR indicates no dose, then ECDOSE needs to be null or > 0; it cannot be used to indicate a dose wasn't taken. | Record Data | Record | Fully Executable |
| [CORE-000005](#core-000005) | When EXTRT is PLACEBO, EXDOSE must equal 0 | Record Data | Record | Fully Executable |
| [CORE-000006](#core-000006) | Raise an error when DTHFL ^= "Y" and not null | Record Data | Record | Fully Executable |
| [CORE-000007](#core-000007) | When Date/Time of Death (DTHDTC) in the DM dataset is populated then death flag (DTHFL) should be populated as 'Y' in... | Record Data | Record | Fully Executable |
| [CORE-000008](#core-000008) | When survival status is completed as 'DEAD' in the SS dataset then death flag (DTHFL) should be populated as 'Y' in t... | Record Data | Record | Fully Executable |
| [CORE-000009](#core-000009) | Verify that ELEMENT value is blank when ETCD is equal to UNPLAN | Record Data | Record | Fully Executable |
| [CORE-000010](#core-000010) | Verify ARMCD value length is <= 20 | Record Data | Record | Fully Executable |
| [CORE-000011](#core-000011) | Raise an error when IECAT is equal to 'EXCLUSION' and IEORRES is not equal to 'Y'. | Record Data | Record | Fully Executable |
| [CORE-000012](#core-000012) | Raise an error when AEOCCUR exists in AE dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000013](#core-000013) | Raise an error when AESTAT is present in AE dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000014](#core-000014) | Raise an error when --PRESP is not equals to "Y" and --OCCUR is present in dataset or --STAT is equal to "NOT DONE" a... | Record Data | Record | Fully Executable |
| [CORE-000015](#core-000015) | Raise an error when --PRESP does not exists in a dataset and --OCCUR exist. | Record Data | Dataset | Fully Executable |
| [CORE-000016](#core-000016) | Raise an error when --OCCUR is not empty and --PRESP is not equal to "Y". | Record Data | Record | Fully Executable |
| [CORE-000017](#core-000017) | Raise an error when RDOMAIN is null when IDVARVAL is not null. | Record Data | Record | Fully Executable |
| [CORE-000018](#core-000018) | Raise an error when --PRESP is equal to "Y", --STAT is blank, --OCCUR is present in dataset and --OCCUR is blank | Record Data | Record | Fully Executable |
| [CORE-000019](#core-000019) | Raise and error if Variable label length > 40 characters | Variable Metadata Check | Dataset | Fully Executable |
| [CORE-000020](#core-000020) | Raise an error when ETCD="UNPLAN" and TAETORD is not null. | Record Data | Record | Fully Executable |
| [CORE-000021](#core-000021) | Raise an error when the value for --STRESC is null. | Record Data | Record | Fully Executable |
| [CORE-000022](#core-000022) | Raise an error when AESCAN, AESCONG, AESDISAB, AESDTH, AESHOSP, AESLIFE, AESOD or AESMIE = 'Y' and AESER = 'N' or is ... | Record Data | Record | Fully Executable |
| [CORE-000023](#core-000023) | Raise an error when --TOXGR is not present in dataset, but --TOX is present | Record Data | Dataset | Fully Executable |
| [CORE-000024](#core-000024) | Raise an error if --BODSYS is not empty, --BDSYCD is empty | Record Data | Record | Fully Executable |
| [CORE-000025](#core-000025) | IESTRESC is not equal to IEORRES | Record Data | Record | Fully Executable |
| [CORE-000026](#core-000026) | When --TPT is present in a dataset, --TPTNUM must also be present. | Record Data | Dataset | Fully Executable |
| [CORE-000027](#core-000027) | Either TEENRL or TEDUR must be present for each Element. | Record Data | Record | Fully Executable |
| [CORE-000028](#core-000028) | Raise an error when --TPTREF is empty, but --ELTM is not empty | Record Data | Record | Fully Executable |
| [CORE-000029](#core-000029) | Raise an error when --TPTNUM exists in a dataset and --TPT does not exist. | Record Data | Dataset | Fully Executable |
| [CORE-000030](#core-000030) | Raise and error when --REASND is present in dataset and --PRESP is not present in dataset | Record Data | Dataset | Fully Executable |
| [CORE-000031](#core-000031) | --EVAL must not be used to model QRS data.   This includes the 'QS' and 'FT' domains as well as the 'RS' domain when ... | Record Data | Dataset | Fully Executable |
| [CORE-000032](#core-000032) | --EVALID must not be used to model QRS data.   This includes the 'QS' and 'FT' domains as well as the 'RS' domain whe... | Record Data | Dataset | Fully Executable |
| [CORE-000033](#core-000033) | Raise an error when DSTERM = "COMPLETED" and DSDECOD not equal to "COMPLETED". | Record Data | Record | Fully Executable |
| [CORE-000034](#core-000034) | Raise an error when DSSTDTC is not equal to DM.DTHDTC and DSDECOD is equal to "DEATH" | Record Data | Record | Fully Executable |
| [CORE-000035](#core-000035) | Raise an error when SVPRESP is null and VISITDY is not null. | Record Data | Record | Fully Executable |
| [CORE-000036](#core-000036) | Raise an error when SVPRESP is "Y" and VISIT is not present in TV.VISIT. | Record Data | Record | Fully Executable |
| [CORE-000037](#core-000037) | Raise an error when SVPRESP not "Y" or null. | Record Data | Record | Fully Executable |
| [CORE-000038](#core-000038) | Raise an error when SVOCCUR is not null and SVPRESP is not "Y". | Record Data | Record | Fully Executable |
| [CORE-000039](#core-000039) | Raise an error when SVPRESP="Y" and VISITNUM is not in TV.VISITNUM | Record Data | Record | Fully Executable |
| [CORE-000040](#core-000040) | Raise an error when SVPRESP is null and VISITNUM is present in TV.VISITNUM | Record Data | Record | Fully Executable |
| [CORE-000041](#core-000041) | Raise and error when TSVAL is not populated TSVAL is populated with values or synonyms of values in the ISO 21090 nul... | Record Data | Record | Fully Executable |
| [CORE-000042](#core-000042) | Raise an error when TT dataset is present. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000043](#core-000043) | Raise an error when TP dataset is present. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000044](#core-000044) | Raise an error when SJ dataset is present. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000045](#core-000045) | Verify ARMNRS is not null when ARMCD is null | Record Data | Record | Fully Executable |
| [CORE-000046](#core-000046) | Verify ARMNRS is not null when ARM is null | Record Data | Record | Fully Executable |
| [CORE-000047](#core-000047) | When study does not use multi-stage arm assignments and ARM is populated, ARM must be present in TA.ARM. This rule ha... | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000048](#core-000048) | Raise an error when --METHOD is present in an Interventions dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000049](#core-000049) | Raise an error when --USCHFL is present. | Record Data | Dataset | Fully Executable |
| [CORE-000050](#core-000050) | Raise an error when --RSTIND is present. | Record Data | Dataset | Fully Executable |
| [CORE-000051](#core-000051) | Raise an error when --RSTMOD is present. | Record Data | Dataset | Fully Executable |
| [CORE-000052](#core-000052) | Raise an error when --IMPLBL is present. | Record Data | Dataset | Fully Executable |
| [CORE-000054](#core-000054) | Raise an error when --DTHREL is present. | Record Data | Dataset | Fully Executable |
| [CORE-000055](#core-000055) | Raise an error when --EXCLFL is present. | Record Data | Dataset | Fully Executable |
| [CORE-000056](#core-000056) | Raise an error when --REASEX is present. | Record Data | Dataset | Fully Executable |
| [CORE-000057](#core-000057) | FETUSID must not be present in SDTM domains. | Record Data | Dataset | Fully Executable |
| [CORE-000058](#core-000058) | Raise an error when RPHASE is present. | Record Data | Dataset | Fully Executable |
| [CORE-000059](#core-000059) | Raise an error when RPPLDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000060](#core-000060) | Raise an error when RPPLSTDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000061](#core-000061) | Raise an error when RPPLENDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000064](#core-000064) | Raise an error when --RPDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000065](#core-000065) | Raise an error when --RPSTDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000066](#core-000066) | Raise an error when --RPENDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000067](#core-000067) | Raise an error when --DETECT is present. | Record Data | Dataset | Fully Executable |
| [CORE-000068](#core-000068) | Raise an error when AGETXT is present. | Record Data | Dataset | Fully Executable |
| [CORE-000069](#core-000069) | Raise an error when SPECIES is present. | Record Data | Dataset | Fully Executable |
| [CORE-000070](#core-000070) | Raise an error when STRAIN is present. | Record Data | Dataset | Fully Executable |
| [CORE-000071](#core-000071) | Raise an error when SBSTRAIN is present. | Record Data | Dataset | Fully Executable |
| [CORE-000072](#core-000072) | Raise an error when --BEATNO is present. | Record Data | Dataset | Fully Executable |
| [CORE-000073](#core-000073) | Raise an error when RPATHCD is present. | Record Data | Dataset | Fully Executable |
| [CORE-000074](#core-000074) | Raise an error when --IMPLBL is present. | Record Data | Dataset | Fully Executable |
| [CORE-000075](#core-000075) | Raise an error when AEREASND is present. | Record Data | Dataset | Fully Executable |
| [CORE-000076](#core-000076) | Raise an error when TRPORTOT is present. | Record Data | Dataset | Fully Executable |
| [CORE-000077](#core-000077) | Raise an error when TRDIR is present. | Record Data | Dataset | Fully Executable |
| [CORE-000078](#core-000078) | Raise an error when TRLAT is present. | Record Data | Dataset | Fully Executable |
| [CORE-000079](#core-000079) | Raise an error when --LOC is not present and --LAT is present. | Record Data | Dataset | Fully Executable |
| [CORE-000080](#core-000080) | Raise an error when --ELTM, --TPTNUM, and --TPT are not present in dataset, but --TPTREF is present. | Record Data | Dataset | Fully Executable |
| [CORE-000081](#core-000081) | Raise an error when --PRESP is not present but --STAT is present. | Record Data | Dataset | Fully Executable |
| [CORE-000082](#core-000082) | Verify that PESTRESC is null when PEORRES is null | Record Data | Record | Fully Executable |
| [CORE-000083](#core-000083) | Verify that --ORRES is not null when --LOBXFL = Y and --DRVFL= null or --DRVFL is not present in the dataset | Record Data | Record | Fully Executable |
| [CORE-000084](#core-000084) | Raise an error when --ENTPT exists in a dataset and --ENRTPT does not exist. | Record Data | Dataset | Fully Executable |
| [CORE-000085](#core-000085) | Raise an error when --STTPT is completed and --STRTPT is empty | Record Data | Record | Fully Executable |
| [CORE-000086](#core-000086) | Raise an error when DVSTDTC is earlier than RFICDTC in DM. | Record Data | Record | Fully Executable |
| [CORE-000087](#core-000087) | Raise an error when AESER is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000088](#core-000088) | Verify that the length of value in SETCD variable is <= 8 | Record Data | Record | Fully Executable |
| [CORE-000089](#core-000089) | Raise an error when --TRTV is empty, --VAMT is not empty | Record Data | Record | Fully Executable |
| [CORE-000090](#core-000090) | Verify IDVAR is null when RDOMAIN is null | Record Data | Record | Fully Executable |
| [CORE-000091](#core-000091) | Raise an error when --TRTV is null, --VAMTU is not null | Record Data | Record | Fully Executable |
| [CORE-000092](#core-000092) | Raise an error when --DOSE ^= null and --DOSTXT ^= null. | Record Data | Record | Fully Executable |
| [CORE-000093](#core-000093) | Raise an error when --DOSU = null and (--DOSE ^= null or --DOSTOT ^= null or --DOSTXT ^= null) | Record Data | Record | Fully Executable |
| [CORE-000094](#core-000094) | Raise an error if --DOSTXT value is numeric. | Record Data | Record | Fully Executable |
| [CORE-000095](#core-000095) | Raise an error when SEUPDES ^= null, ETCD ^= 'UNPLAN' | Record Data | Record | Fully Executable |
| [CORE-000096](#core-000096) | Raise an error when --LOC does not exists in a dataset, --PORTOT exists. | Record Data | Dataset | Fully Executable |
| [CORE-000097](#core-000097) | Raise an error when variable EPOCH values don't match between Subject Visits (SV) and Subject Elements (SE) datasets. | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000098](#core-000098) | Raise an error when --LOC does not exists in a dataset, --DIR exists. | Record Data | Dataset | Fully Executable |
| [CORE-000099](#core-000099) | Raise an error when both --ORRES and --STAT values are populated | Record Data | Record | Fully Executable |
| [CORE-000100](#core-000100) | Raise an error when --VAMT is not empty, but --TRTV is empty. | Record Data | Record | Fully Executable |
| [CORE-000101](#core-000101) | Raise an error when --RESCAT is not empty, but --STRESC is empty | Record Data | Record | Fully Executable |
| [CORE-000102](#core-000102) | Raise an error when --TOX is not empty, but --TOXGR is empty. | Record Data | Record | Fully Executable |
| [CORE-000103](#core-000103) | Part A - Raise an error when --SCAT is not empty, but --CAT is empty. | Record Data | Record | Fully Executable |
| [CORE-000104](#core-000104) | Part A - Raise an error when --SCAT exists in a dataset, but --CAT does not exist. | Record Data | Dataset | Fully Executable |
| [CORE-000105](#core-000105) | Raise an error when --LOBXFL = 'Y' and --STRESC is empty. | Record Data | Record | Fully Executable |
| [CORE-000106](#core-000106) | Raise an error when --ENTPT is completed and --ENRTPT is not completed. | Record Data | Record | Fully Executable |
| [CORE-000107](#core-000107) | APID is required in all Associated Persons Data. In addition to STUDYID, DOMAIN, and --SEQ being required for all dom... | Record Data | Dataset | Fully Executable |
| [CORE-000108](#core-000108) | When a record is present in the DD dataset then death flag (DTHFL) should be populated as 'Y' in the DM dataset for t... | Record Data | Dataset | Fully Executable |
| [CORE-000109](#core-000109) | Raise an error when SMSTDTC is null. | Record Data | Record | Fully Executable |
| [CORE-000110](#core-000110) | Raise an error when (--ORREF ^= null or --DRVFL='Y') and --STREFC is null. | Record Data | Record | Fully Executable |
| [CORE-000111](#core-000111) | Raise an error when --AGENT is present in a dataset other than MS. | Record Data | Dataset | Fully Executable |
| [CORE-000112](#core-000112) | Raise an error when --CONC is present in a dataset other than MS. | Record Data | Dataset | Fully Executable |
| [CORE-000113](#core-000113) | Raise an error when --CONCU is present in a dataset other than MS. | Record Data | Dataset | Fully Executable |
| [CORE-000114](#core-000114) | Raise an error when --EVDTYP is present in a dataset other than MH. | Record Data | Dataset | Fully Executable |
| [CORE-000115](#core-000115) | Raise an error when ARM is 'Screen Failure', 'Not Assigned', 'Unplanned Treatment', 'Not Treated' | Record Data | Record | Fully Executable |
| [CORE-000116](#core-000116) | Raise an error when --SPCUFL is not null or equal to "N" | Record Data | Record | Fully Executable |
| [CORE-000117](#core-000117) | Raise an error when --STAT not equal to 'NOT DONE' when --PRESP = 'Y' and --OCCUR is null | Record Data | Record | Fully Executable |
| [CORE-000118](#core-000118) | Raise an error when --PRESP is equal to 'Y' and --OCCUR is empty and --STAT is not present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000119](#core-000119) | Raise an error when ARM is not empty, but ARMCD is empty | Record Data | Record | Fully Executable |
| [CORE-000120](#core-000120) | Raise an error when ACTARM is not empty, but ACTARMCD is empty | Record Data | Record | Fully Executable |
| [CORE-000121](#core-000121) | Verify the value for ARMNRS, when both ARMCD and ACTARMCD values are populated | Record Data | Record | Fully Executable |
| [CORE-000122](#core-000122) | Raise an error when AGEU is completed but both AGETXT and AGE are not completed. | Record Data | Record | Fully Executable |
| [CORE-000123](#core-000123) | Raise an error when AESCAN is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000124](#core-000124) | Raise an error when AESCONG is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000125](#core-000125) | Raise an error when AESDISAB is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000126](#core-000126) | Raise an error when AESDTH is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000127](#core-000127) | Raise an error when AESHOSP is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000128](#core-000128) | Raise an error when AESLIFE is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000129](#core-000129) | Raise an error when AESOD is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000130](#core-000130) | Raise an error when AESMIE is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000131](#core-000131) | Raise an error when AECONTRT is completed and value is not 'Y' or 'N' | Record Data | Record | Fully Executable |
| [CORE-000132](#core-000132) | ETCD and ELEMENT should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000133](#core-000133) | When --STRESU is populated, --STRESC must also be populated. Please note that this rule, as executed, cannot check if... | Record Data | Record | Fully Executable |
| [CORE-000134](#core-000134) | Raise an error when RDOMAIN ^= 'DM' and IDVAR is empty. | Record Data | Record | Fully Executable |
| [CORE-000135](#core-000135) | Raise an error when IDVAR is not empty and IDVARVAL is empty. | Record Data | Record | Fully Executable |
| [CORE-000136](#core-000136) | Raise an error when IDVARVAL and USUBJID are empty and IDVAR = Sequence Number. | Record Data | Record | Fully Executable |
| [CORE-000137](#core-000137) | Raise an error when ECOCCUR is not equal to 'N', ECSTAT and ECDOSTXT are both empty but ECDOSE less than or equal to 0. | Record Data | Record | Fully Executable |
| [CORE-000138](#core-000138) | If --STDTC or DM.RFSTDTC does not contain a complete values, --STDY must be null. | Record Data | Record | Fully Executable |
| [CORE-000139](#core-000139) | Trigger error if --ENDTC or DM.RFSTDTC does not contain complete values in their date portion, and --ENDY is not null. | Record Data | Record | Fully Executable |
| [CORE-000140](#core-000140) | Trigger error if VISITDY is populated when VISITNUM is not in TV. | Record Data | Record | Fully Executable |
| [CORE-000141](#core-000141) | VISITNUM and --TPTREF are not present then --TPT and --TPTNUM should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000142](#core-000142) | Raise an error when --TPT ^= null and --TPTNUM ^= null and --ELTM ^= null and --ELTM does not have the same value acr... | Record Data | Record | Fully Executable |
| [CORE-000143](#core-000143) | The length of ETCD should be no greater than 8 | Record Data | Record | Fully Executable |
| [CORE-000144](#core-000144) | Trigger error when TAETORD is not unique within an ARM | Record Data | Record | Fully Executable |
| [CORE-000145](#core-000145) | Trigger error when TIVERS is present and IETESTCD is not unique within TIVERS | Record Data | Record | Fully Executable |
| [CORE-000146](#core-000146) | Trigger error when IETESTCD is not unique within dataset | Record Data | Record | Fully Executable |
| [CORE-000147](#core-000147) | Trigger error when length of TSPARMCD is greater than 8 | Record Data | Record | Fully Executable |
| [CORE-000148](#core-000148) | Trigger error when length of TSPARM value is greater than 40 | Record Data | Record | Fully Executable |
| [CORE-000149](#core-000149) | Trigger error when TSVAL is null and TSVALNF is null | Record Data | Record | Fully Executable |
| [CORE-000150](#core-000150) | Trigger error when TSVAL is populated and TSVALNF is also populated. | Record Data | Record | Fully Executable |
| [CORE-000151](#core-000151) | Trigger error when TSVAL1 is populated and TSVAL is null | Record Data | Record | Fully Executable |
| [CORE-000152](#core-000152) | When TSVAL and TSVALCD are populated, there must be a one-to-one relationship between TSVALCD and TSVAL | Record Data | Record | Fully Executable |
| [CORE-000153](#core-000153) | Trigger error when TSVCDVER is populated and TSVCDREF is null | Record Data | Record | Fully Executable |
| [CORE-000154](#core-000154) | TSSEQ must be unique within TSPARMCD | Record Data | Record | Fully Executable |
| [CORE-000155](#core-000155) | Trigger error when ARMCD is populated and ARMCD is not in TA.ARMCD | Record Data | Record | Fully Executable |
| [CORE-000156](#core-000156) | Trigger error when ARM is populated and ARM is not in TA.ARM | Record Data | Record | Fully Executable |
| [CORE-000157](#core-000157) | The length of ARMCD is limited to 20 characters. | Record Data | Record | Fully Executable |
| [CORE-000158](#core-000158) | IDVAR must be specified. | Record Data | Record | Fully Executable |
| [CORE-000159](#core-000159) | Raise an error when --TESTCD = 'OTHER' | Record Data | Record | Fully Executable |
| [CORE-000160](#core-000160) | Raise an error when --TRT = 'OTHER' | Record Data | Record | Fully Executable |
| [CORE-000161](#core-000161) | Raise an error when --TERM = 'OTHER' | Record Data | Record | Fully Executable |
| [CORE-000162](#core-000162) | Raise an error when --TESTCD = 'MULTIPLE' | Record Data | Record | Fully Executable |
| [CORE-000163](#core-000163) | Raise an error when --TRT = 'MULTIPLE' | Record Data | Record | Fully Executable |
| [CORE-000164](#core-000164) | Raise an error when --TERM = 'MULTIPLE' | Record Data | Record | Fully Executable |
| [CORE-000165](#core-000165) | Time point reference (--TPTREF) should be present in the dataset, when reference time point (--RFTDTC) is also presen... | Record Data | Dataset | Fully Executable |
| [CORE-000166](#core-000166) | Raise an error when --TPT is not present but --TPTNUM is present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000167](#core-000167) | Raise an error when--ELTM is present but --TPTREF is not present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000168](#core-000168) | If VISITNUM is not null then VISITNUM should be among SV.VISITNUM | Record Data | Record | Fully Executable |
| [CORE-000169](#core-000169) | Raise a warning when LBTOXGR is not numeric | Record Data | Record | Fully Executable |
| [CORE-000170](#core-000170) | Raise an error when --LOBXFL is not 'Y' or null | Record Data | Record | Fully Executable |
| [CORE-000171](#core-000171) | Raises an error when --ENRTPT is present in dataset but --ENTPT is not present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000172](#core-000172) | Raise an error when STUDYID is not equal to DM.STUDYID. The STUDYID in all domains must be the same in all records ac... | Record Data | Record | Fully Executable |
| [CORE-000173](#core-000173) | Raise an error when ETCD is not equal to 'UNPLAN' and not equal to TE.ETCD | Record Data | Record | Fully Executable |
| [CORE-000174](#core-000174) | Verify variable SPECIES does not exist in DM dataset | Record Data | Dataset | Fully Executable |
| [CORE-000175](#core-000175) | Verify variable STRAIN does not exist in DM dataset | Record Data | Dataset | Fully Executable |
| [CORE-000176](#core-000176) | Verify variable SBSTRAIN does not exist in DM dataset | Record Data | Dataset | Fully Executable |
| [CORE-000177](#core-000177) | Raise an error when DM.RFENDTC is empty and --ENRF is not empty. | Record Data | Record | Fully Executable |
| [CORE-000178](#core-000178) | Raise an error when SSSTRESC = 'DEAD' and SSDTC < max DS.DSSTDTC. | Record Data | Record | Fully Executable |
| [CORE-000179](#core-000179) | TSPARMCD and TSPARM should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000180](#core-000180) | Raise an error when DOMAIN value length is not equal 2. | Record Data | Dataset | Fully Executable |
| [CORE-000181](#core-000181) | Raise an error when AP-- domain value length is not equal to 4. | Record Data | Record | Fully Executable |
| [CORE-000182](#core-000182) | Raise an error when variable name length is greater than 8. | Variable Metadata Check | Dataset | Fully Executable |
| [CORE-000183](#core-000183) | Raise an error when a PP dataset is present in study, but a PC dataset is not present in study. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000184](#core-000184) | --BODSYS and --BDSYCD  have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000185](#core-000185) | Trigger error if length of ACTARMCD is greater than 20 characters | Record Data | Record | Fully Executable |
| [CORE-000186](#core-000186) | Trigger error if SUBJID is not unique | Record Data | Dataset | Fully Executable |
| [CORE-000187](#core-000187) | Trigger error when IDVAR is not null and CODTC is not null | Record Data | Record | Fully Executable |
| [CORE-000188](#core-000188) | Trigger error if MS dataset is present and MB dataset is not present. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000189](#core-000189) | Raise an error when AGE is not blank and AGEU is blank. | Record Data | Record | Fully Executable |
| [CORE-000190](#core-000190) | Raise an error when AGEU is not blank and AGE is blank. | Record Data | Record | Fully Executable |
| [CORE-000191](#core-000191) | Raise an error when ARM is not empty and RFENDTC is empty. | Record Data | Record | Fully Executable |
| [CORE-000192](#core-000192) | Raise an error when ARMNRS is not blank and RFENDTC is provided. | Record Data | Record | Fully Executable |
| [CORE-000193](#core-000193) | Raise an error when variable MIDSDTC is present and MIDS variable is missing. | Record Data | Dataset | Fully Executable |
| [CORE-000195](#core-000195) | Raise an error when --SCAT = --DECOD. | Record Data | Record | Fully Executable |
| [CORE-000196](#core-000196) | Raise an error when --CAT = --DECOD. | Record Data | Record | Fully Executable |
| [CORE-000197](#core-000197) | Raise an error when --CAT = --BODSYS. | Record Data | Record | Fully Executable |
| [CORE-000198](#core-000198) | Raise an error when --SCAT = --BODSYS. | Record Data | Record | Fully Executable |
| [CORE-000199](#core-000199) | Raise an error when length of --TEST > 40. | Record Data | Record | Fully Executable |
| [CORE-000200](#core-000200) | Verify that --ORRES is not missing when either --STAT is null or --DRVFL not equal to 'Y' | Record Data | Record | Fully Executable |
| [CORE-000201](#core-000201) | Trigger error when domain is not an AP-- domain and USUBJID is not present in DM.USUBJID | Record Data | Record | Fully Executable |
| [CORE-000202](#core-000202) | When IDVAR is populated with a --SEQ value, RELTYPE must be null. | Record Data | Record | Fully Executable |
| [CORE-000203](#core-000203) | Trigger error when the combination of IDVAR, IDVARVAL, and QNAM is not unique per parent subject record | Record Data | Record | Fully Executable |
| [CORE-000204](#core-000204) | When VISITNUM is present in TV.VISITNUM, SV.VISITNUM must be unique within subject | Record Data | Record | Fully Executable |
| [CORE-000206](#core-000206) | When IDVAR is populated, IDVARVAL must equal a value of the variable referenced by IDVAR within the domain referenced... | Record Data | Record | Fully Executable |
| [CORE-000207](#core-000207) | Trigger error when --STDTC is present in a Findings general observation class | Record Data | Dataset | Fully Executable |
| [CORE-000208](#core-000208) | Trigger error when study does not use multi-stage arm assignments and ACTARMCD ^= null, and ACTARMCD is not in TA.ARMCD | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000209](#core-000209) | When study does not use multi-stage arm assignments and ACTARM is populated, ACTARM must be present in TA.ARM. This r... | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000210](#core-000210) | When study does not use multi-stage arm assignments and ARMCD is populated, ARMCD must be present in TA.ARMCD. This r... | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000211](#core-000211) | SUPPDM must not include population flags. | Record Data | Record | Fully Executable |
| [CORE-000212](#core-000212) | When subject has more than one record per Epoch with DSCAT = DISPOSITION EVENT, DSSCAT must be present. | Record Data | Dataset | Fully Executable |
| [CORE-000213](#core-000213) | Trigger error when there is more than one record per subject per DSSCAT per EPOCH | Record Data | Record | Fully Executable |
| [CORE-000214](#core-000214) | Trigger error when subject has more than one record per Epoch with DSCAT = 'DISPOSITION EVENT' and more than one reco... | Record Data | Record | Fully Executable |
| [CORE-000215](#core-000215) | Trigger error when subject has more than one record per Epoch with DSCAT = 'DISPOSITION EVENT' and more than one reco... | Record Data | Record | Fully Executable |
| [CORE-000216](#core-000216) | MIDSTYPE must be unique within subject when MIDSTYPE = TM.MIDSTYPE and TM.TMRPT = 'N' | Record Data | Record | Fully Executable |
| [CORE-000217](#core-000217) | Raise an error when ECDOSE is empty, ECOCCUR is not equal to 'N', ECSTAT is empty and ECDOSTXT is empty | Record Data | Record | Fully Executable |
| [CORE-000218](#core-000218) | Raise an error when IDVAR is empty and IDVARVAL is not empty | Record Data | Record | Fully Executable |
| [CORE-000219](#core-000219) | Raise an error when --SCAT = domain dataset label. | Record Data | Record | Fully Executable |
| [CORE-000220](#core-000220) | Part A - Raise an error when --TESTCD > 8 chars or contains more than only letters, numbers and underscores, or start... | Record Data | Record | Fully Executable |
| [CORE-000221](#core-000221) | Raise an error when QNAM > 8 chars or contains more than only letters in uppercase, numbers and underscores, or start... | Record Data | Record | Fully Executable |
| [CORE-000222](#core-000222) | Raise an error when QLABEL > 40 chars. | Record Data | Record | Fully Executable |
| [CORE-000223](#core-000223) | Raise an error when ACTARMCD is empty and ARMNRS is not completed. | Record Data | Record | Fully Executable |
| [CORE-000224](#core-000224) | Raise an error when ACTARM is empty and ARMNRS is not completed. | Record Data | Record | Fully Executable |
| [CORE-000225](#core-000225) | Raise an error when --REASND is provided and --STAT is not equal to "NOT DONE". | Record Data | Record | Fully Executable |
| [CORE-000226](#core-000226) | Verifying presence of SEND variable --DTHREL | Record Data | Dataset | Fully Executable |
| [CORE-000227](#core-000227) | Check if IETEST is not in TI.IETEST | Record Data | Record | Fully Executable |
| [CORE-000228](#core-000228) | Check if IETESTCD is present in TI.IETESTCD | Record Data | Record | Fully Executable |
| [CORE-000229](#core-000229) | Verify that When USUBJID is populated, POOLID is not populated | Record Data | Record | Fully Executable |
| [CORE-000230](#core-000230) | Verify that when POOLID is populated, USUBJID is not populated | Record Data | Record | Fully Executable |
| [CORE-000231](#core-000231) | Verify that when RSUBJID populated, RSUBJID does not equal USUBJID | Record Data | Record | Fully Executable |
| [CORE-000232](#core-000232) | Verify when RSUBJID is populated it does not equal POOLID | Record Data | Record | Fully Executable |
| [CORE-000233](#core-000233) | Related Device Identifier (RDEVID) and Related Subject or Pool Identifier (RSUBJID) should not both be populated for ... | Record Data | Record | Fully Executable |
| [CORE-000234](#core-000234) | Verify that RSUBJID is empty when RDEVID is populated | Record Data | Record | Fully Executable |
| [CORE-000235](#core-000235) | Verify that RSUBJID=DM.USUBJID when RSUBJID is populated and RSUBJID is not equal to POOLID | Record Data | Record | Fully Executable |
| [CORE-000236](#core-000236) | Raise an issue if MHSTDTC has a non-empty date that later than DM.RFSTDTC | Record Data | Record | Fully Executable |
| [CORE-000237](#core-000237) | Trigger error when --SCAT is not null and --SCAT is equal to --CAT | Record Data | Record | Fully Executable |
| [CORE-000238](#core-000238) | Raise an error when EX records are present for subject but RFXENDTC does not equal the latest value of EX.EXSTDTC or ... | Record Data | Record | Fully Executable |
| [CORE-000239](#core-000239) | Raise an error when EX records are present for subject but RFXSTDTC does not equal the earliest value of EX.EXSTDTC | Record Data | Record | Fully Executable |
| [CORE-000240](#core-000240) | When --OCCUR = 'N', --STRF must not be populated | Record Data | Record | Fully Executable |
| [CORE-000241](#core-000241) | When --OCCUR = 'N', --ENRF must not be populated | Record Data | Record | Fully Executable |
| [CORE-000242](#core-000242) | Raise an error when --EXCLFL is present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000243](#core-000243) | Raise an error when --REASEX is present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000244](#core-000244) | Raise an error when --DETECT is present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000245](#core-000245) | Raise an error when --NOMDY is present. | Record Data | Dataset | Fully Executable |
| [CORE-000246](#core-000246) | Raise an error when --NOMLBL is present. | Record Data | Dataset | Fully Executable |
| [CORE-000247](#core-000247) | Raise an error when --RESLOC is present. | Record Data | Dataset | Fully Executable |
| [CORE-000248](#core-000248) | Time point reference (--TPTREF) should be populated when reference time point (--RFTDTC) is populated. | Record Data | Record | Fully Executable |
| [CORE-000249](#core-000249) | Verify that visitdy is planned and exists in TV | Record Data | Record | Fully Executable |
| [CORE-000250](#core-000250) | Raise an issue if MHENDTC has a non-empty date that is on or after DM.RFSTDTC | Record Data | Record | Fully Executable |
| [CORE-000251](#core-000251) | When survival status is completed as 'DEAD' in the SS dataset then a record should be present in the DS dataset where... | Record Data | Record | Fully Executable |
| [CORE-000252](#core-000252) | When a record is present in the DS dataset where DSDECOD = 'DEATH' then death flag (DTHFL) should be populated as 'Y'... | Record Data | Record | Fully Executable |
| [CORE-000253](#core-000253) | When a record is present in the AE dataset where AESDTH = 'Y' then death flag (DTHFL) should be populated as 'Y' in t... | Record Data | Record | Fully Executable |
| [CORE-000254](#core-000254) | When a record is present in the AE dataset where AEOUT = 'FATAL' then death flag (DTHFL) should be populated as 'Y' i... | Record Data | Record | Fully Executable |
| [CORE-000256](#core-000256) | Raise an error when ETCD = 'UNPLAN', SEUPDES = null | Record Data | Record | Fully Executable |
| [CORE-000258](#core-000258) | Raise an error when --STRTPT is present in dataset and --STTPT is not present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000259](#core-000259) | Date/time of collection of subject status in SS dataset where survival status (SSSTRESC) = 'DEAD' should be equal to ... | Record Data | Record | Fully Executable |
| [CORE-000260](#core-000260) | Raise an error when --PRESP does not equal 'Y' or is not empty. | Record Data | Record | Fully Executable |
| [CORE-000261](#core-000261) | Raise an error when --STTPT is present in dataset and --STRTPT is not present in dataset. | Record Data | Dataset | Fully Executable |
| [CORE-000262](#core-000262) | Raise an error when RFSTDTC in DM is empty and --STRF is not empty. | Record Data | Record | Fully Executable |
| [CORE-000264](#core-000264) | Raise an error if primary SOC is used for analysis and -BODSYS is not equal to -SOC | Record Data | Record | Fully Executable |
| [CORE-000266](#core-000266) | Select record where AESCAN and AESCONG and AESDISAB and AESDTH and AESHOSP and AESLIFE and AESOD and AESMIE ^= "Y" Ho... | Record Data | Record | Fully Executable |
| [CORE-000267](#core-000267) | Raise an error when --DECOD is not populated but --PTCD is populated | Record Data | Record | Fully Executable |
| [CORE-000268](#core-000268) | --DECOD and --PTCD should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000269](#core-000269) | Verify when VISIT is not null in any given domain and VISIT is planned (SVPRESP = 'Y'), then VISIT should be among TV... | Record Data | Record | Fully Executable |
| [CORE-000270](#core-000270) | Verify when VISITNUM is not null in any given domain and VISITNUM is planned (SVPRESP = 'Y'), then VISIT should be am... | Record Data | Record | Fully Executable |
| [CORE-000271](#core-000271) | Raise an error when EPOCH is not in TA.EPOCH | Record Data | Record | Fully Executable |
| [CORE-000272](#core-000272) | Raise an error when --CAT = DOMAIN. | Record Data | Record | Fully Executable |
| [CORE-000289](#core-000289) | Raise an error when LBORRES is not a continuous measurement and LBORNRHI is not empty. The interpretation of the guid... | Record Data | Record | Fully Executable |
| [CORE-000290](#core-000290) | Raise an error when LBORRES ^= continuous measurement and LBORNRLO is not empty. The interpretation of the guidance i... | Record Data | Record | Fully Executable |
| [CORE-000291](#core-000291) | When EC exists, EXVAMT should not be used. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000292](#core-000292) | When EC exists, EXVAMTU should not be used. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000293](#core-000293) | Trigger error if length of dataset name is greater than 8 | Dataset Metadata Check | Dataset | Fully Executable |
| [CORE-000294](#core-000294) | Trigger error when TSPARMCD = 'AGEMAX' and TSVAL ^= null and TSVAL does not conform to ISO 8601 | Record Data | Record | Fully Executable |
| [CORE-000295](#core-000295) | When --STREFC is populated, --STREFN should also be populated. | Record Data | Record | Fully Executable |
| [CORE-000296](#core-000296) | When ACTARMCD is populated, disposition data for subject must be present | Record Data | Record | Fully Executable |
| [CORE-000297](#core-000297) | Raise an error when variable MIDS is present in any of the dataset and TM dataset is missing. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000298](#core-000298) | Raise an error when LBORRES is not a continuous measurement and LBSTNRLO is not empty. The interpretation of the guid... | Record Data | Record | Fully Executable |
| [CORE-000299](#core-000299) | Raise an error when LBORRES is not a continuous measurement and LBSTNRHI is not empty. The interpretation of the guid... | Record Data | Record | Fully Executable |
| [CORE-000302](#core-000302) | QNAM and QLABEL should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000303](#core-000303) | --TESTCD and --TEST should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000305](#core-000305) | Collected Duration (--DUR) value should not be negative. | Record Data | Record | Fully Executable |
| [CORE-000308](#core-000308) | Dose (--DOSE) value should not be negative | Record Data | Record | Fully Executable |
| [CORE-000310](#core-000310) | Age (AGE) value should not be negative | Record Data | Record | Fully Executable |
| [CORE-000318](#core-000318) | ARMCD and ARM should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000321](#core-000321) | When Date/Time of Collection (--DTC) is present in the dataset, then the Study Day of Visit/Collection/Exam (--DY) sh... | Record Data | Dataset | Fully Executable |
| [CORE-000324](#core-000324) | Trigger error when --ENRTPT is not in ('BEFORE', 'COINCIDENT', 'ONGOING', 'UNKNOWN') | Record Data | Record | Partially Executable |
| [CORE-000328](#core-000328) | Study Day of Start (--STDY) variable should be included into dataset, when Start Study Date/Time (--STDTC) variable i... | Record Data | Dataset | Fully Executable |
| [CORE-000334](#core-000334) | Raise an error when an expected variable is not present in the dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [CORE-000337](#core-000337) | Raise an error when multiple races are collected in SUPPDM but RACE in DM does not equal "MULTIPLE". | Record Data | Record | Fully Executable |
| [CORE-000351](#core-000351) | USUBJID must be unique across all studies | Record Data | Record | Partially Executable |
| [CORE-000352](#core-000352) | Trigger error where SEENDTC is not equal to SESTDTC of the next element. | Record Data | Record | Fully Executable |
| [CORE-000354](#core-000354) | Raise an error when the date portion of --DTC is an incomplete date or the date portion of DM.RFSTDTC is an incomplet... | Record Data | Record | Fully Executable |
| [CORE-000355](#core-000355) | Part A: Raise an error when a Required variable is not present in the dataset. | Variable Metadata Check | Dataset | Fully Executable |
| [CORE-000356](#core-000356) | Part B: Raise an error when a Required variable is null. | Value Check with Dataset Metadata | Record | Fully Executable |
| [CORE-000357](#core-000357) | When a supplemental qualifier dataset is associated with a split dataset, the dataset name length must not be greater... | Dataset Metadata Check | Record | Fully Executable |
| [CORE-000358](#core-000358) | Verify that when --LNKGRP is present in one domain it is also present in another domain. | Record Data | Dataset | Fully Executable |
| [CORE-000361](#core-000361) | When VISITNUM is present in TV, VISIT and VISITNUM should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000362](#core-000362) | Raise an error when primary SOC is used for analysis and SOC Code is not equal to Body System Code | Record Data | Record | Fully Executable |
| [CORE-000363](#core-000363) | When there are multiple informed consents obtained and DSDECOD = 'INFORMED CONSENT OBTAINED', the earliest DSSTDTC fo... | Record Data | Record | Fully Executable |
| [CORE-000365](#core-000365) | MHCAT should not group all records.  If no smaller categorization can be applied, then it is not necessary to include... | Record Data | Record | Fully Executable |
| [CORE-000370](#core-000370) | DM.RFICDTC must equal the earliest DS.DSSTDTC when DSTERM indicates informed consent obtained. | Record Data | Record | Fully Executable |
| [CORE-000374](#core-000374) | Trigger error when there is more than one record per subject per EPOCH | Record Data | Record | Fully Executable |
| [CORE-000376](#core-000376) | Raise an error when the two first characters of a prefixed variable within a custom domain do not match the DOMAIN va... | Variable Metadata Check | Record | Fully Executable |
| [CORE-000384](#core-000384) | RELREC.RDOMAIN must represent a dataset that is present in the study. | Record Data | Record | Fully Executable |
| [CORE-000457](#core-000457) | Trigger error when SUPP--.RDOMAIN does not represent a dataset present in the study | Record Data | Dataset | Fully Executable |
| [CORE-000484](#core-000484) | Within a subject, each unique RELID should be present on multiple RELREC records | Record Data | Record | Fully Executable |
| [CORE-000502](#core-000502) | Raise an error when RDOMAIN is populated but the referenced RDOMAIN doesn't exist as a dataset in the present study | Record Data | Dataset | Fully Executable |
| [CORE-000505](#core-000505) | Study Start Date (SSTDTC) value in the TS dataset should be populated in ISO 8601 format. | Record Data | Record | Fully Executable |
| [CORE-000510](#core-000510) | Split datasets (excluding supplemental qualifiers) is expected to have a have a length of 3 or 4 characters | Dataset Metadata Check | Record | Fully Executable |
| [CORE-000517](#core-000517) | Total Daily Dose (--DOSTOT) value should not be negative. | Record Data | Record | Fully Executable |
| [CORE-000518](#core-000518) | Value for Treatment Vehicle Amount (--VAMT) should not be negative. | Record Data | Record | Fully Executable |
| [CORE-000522](#core-000522) | Category for Disposition Event (DSCAT) should be populated. | Record Data | Record | Fully Executable |
| [CORE-000527](#core-000527) | Trigger error where element is not the last element and SEENDTC is null. | Record Data | Record | Fully Executable |
| [CORE-000529](#core-000529) | Raise an error when --DY is not calculated as per the study day algorithm as a non-zero integer value when the date p... | Record Data | Record | Fully Executable |
| [CORE-000534](#core-000534) | TAETORD must be an integer | Record Data | Record | Fully Executable |
| [CORE-000535](#core-000535) | Trigger error when the order of --SEQ is not chronological (based on --STDTC) within USUBJID | Record Data | Record | Fully Executable |
| [CORE-000538](#core-000538) | Trigger error when RDOMAIN does not match characters 5 and 6 of the Supplementary dataset name | Record Data | Record | Fully Executable |
| [CORE-000539](#core-000539) | Raise an error when split domains (i.e three/four-letter domain name) do not have a two-letter parent domain present ... | Dataset Metadata Check | Record | Fully Executable |
| [CORE-000540](#core-000540) | Raise an error when parent domain referenced in Findings About dataset name is not present in the study. | Dataset Metadata Check | Dataset | Fully Executable |
| [CORE-000541](#core-000541) | Part B - Raise an error when IETESTCD > 8 chars or contains more than only letters, numbers and underscores, or start... | Record Data | Record | Fully Executable |
| [CORE-000542](#core-000542) | Raise an error when --STRESC is populated with a numeric value, but --STRESN is not populated, or --STRESN is not equ... | Record Data | Record | Fully Executable |
| [CORE-000544](#core-000544) | Excluding TS.TSSEQ, raise an error when --SEQ is not a unique number per USUBJID per domain, or not a unique number p... | Record Data | Record | Fully Executable |
| [CORE-000550](#core-000550) | Raise an error when a variable is not an allowed variable for an Observation Class | Variable Metadata Check | Record | Partially Executable - Possible Underreporting |
| [CORE-000552](#core-000552) | Study day must be calculated using the algorithm detailed in the SDTMIG. | Record Data | Record | Fully Executable |
| [CORE-000553](#core-000553) | Study day must be calculated using the algorithm detailed in the SDTMIG. | Record Data | Record | Fully Executable |
| [CORE-000571](#core-000571) | Verify that when --LNKID is present in one domain it is also present in another domain. | Record Data | Dataset | Fully Executable |
| [CORE-000572](#core-000572) | Trigger error when --ENRTPT is not in ('BEFORE', 'COINCIDENT', 'ONGOING', 'AFTER', 'UNKNOWN') | Record Data | Record | Partially Executable |
| [CORE-000575](#core-000575) | There must be at least one timing variable for any of the domains based on the three general observation classes | Variable Metadata Check | Dataset | Partially Executable - Possible Underreporting |
| [CORE-000579](#core-000579) | Raise an error when a dataset has no records. | Dataset Metadata Check | Dataset | Fully Executable |
| [CORE-000580](#core-000580) | The combination of TESTRL, TEENRL, and TEDUR must be unique for each ETCD. | Record Data | Record | Fully Executable |
| [CORE-000581](#core-000581) | DM dataset should be present. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000582](#core-000582) | Raise an error when TSVAL(n+1) ^= null and TSVALn = null | Record Data | Record | Fully Executable |
| [CORE-000594](#core-000594) | Raise an error when a variable label is not in title case | Variable Metadata Check | Record | Fully Executable |
| [CORE-000597](#core-000597) | When SUPPAE.QNAM=AESOSP is present, a record with AESMIE=Y should be present | Record Data | Record | Fully Executable |
| [CORE-000598](#core-000598) | The dataset name must begin with the DOMAIN value. | Dataset Metadata Check | Dataset | Fully Executable |
| [CORE-000616](#core-000616) | When Planned Start of Assessment Interval (--STINT) is populated, Time Point Reference (--TPTREF) should also be popu... | Record Data | Record | Fully Executable |
| [CORE-000642](#core-000642) | When Planned End of Assessment Interval (--ENINT) is populated, Time Point Reference (--TPTREF) should also be popula... | Record Data | Record | Fully Executable |
| [CORE-000643](#core-000643) | Records with a baseline flag (--BLFL) have a non missing value in character standard result variable (--STRESC) | Record Data | Record | Fully Executable |
| [CORE-000655](#core-000655) | Values in ARMCD and ACTARMCD should match | Record Data | Record | Fully Executable |
| [CORE-000656](#core-000656) | Values in ARM and ACTARM should match | Record Data | Record | Fully Executable |
| [CORE-000657](#core-000657) | When Adverse Event Outcome (AEOUT) is populated with 'NOT RECOVERED/NOT RESOLVED', then the End Date/Time of Adverse ... | Record Data | Record | Fully Executable |
| [CORE-000658](#core-000658) | Date/Time of Informed Consent (RFICDTC) should be prior or equal to the Date/Time of First Study Treatment (RFXSTDTC). | Record Data | Record | Fully Executable |
| [CORE-000659](#core-000659) | When Adverse Event Outcome (AEOUT) is populated with RECOVERED/RESOLVED, then the End Date/Time of Adverse Event (AEE... | Record Data | Record | Fully Executable |
| [CORE-000672](#core-000672) | Normal Range Upper Limit-Standard Units (--STNRHI) should be greater than the Normal Range Lower Limit-Standard Units... | Record Data | Record | Fully Executable |
| [CORE-000679](#core-000679) | When Death flag (DTHFL) in the DM dataset is populated as 'Y' then a record should be present in the DS dataset where... | Record Data | Dataset | Fully Executable |
| [CORE-000685](#core-000685) | VISITNUM is present in the dataset and --TPTREF is not present in the dataset then --TPT and --TPTNUM should have a o... | Record Data | Record | Fully Executable |
| [CORE-000686](#core-000686) | VISITNUM not present in dataset and --TPTREF present in dataset then --TPT and --TPTNUM should have a one-to-one rela... | Record Data | Record | Fully Executable |
| [CORE-000689](#core-000689) | VISITNUM and --TPTREF are present then --TPT and --TPTNUM should have a one-to-one relationship per unique combinatio... | Record Data | Record | Fully Executable |
| [CORE-000699](#core-000699) | Standard units should be consistent within the same assessment (having the same --TESTCD, --CAT, --SCAT, --SPEC, --ME... | Record Data | Record | Fully Executable |
| [CORE-000700](#core-000700) | When Study Day of Visit/Collection/Exam (--DY) is present in the dataset, then the Date/Time of Collection (--DTC) sh... | Record Data | Dataset | Fully Executable |
| [CORE-000701](#core-000701) | EPOCH should be populated for clinical subject-level observations. | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000705](#core-000705) | When Death flag (DTHFL) in the DM dataset is populated as 'Y' then Date/Time of Death (DTHDTC)should be populated in ... | Record Data | Record | Fully Executable |
| [CORE-000706](#core-000706) | --LLTCD and --LLT should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000707](#core-000707) | Study Day of Visit/Collection/Exam (--DY) should be less than or equal to Study Day of End of Observation (--ENDY). | Record Data | Record | Fully Executable |
| [CORE-000708](#core-000708) | When Study Day of End of Observation (--ENDY) is present in the dataset, then the End Date/Time of Observation (--END... | Record Data | Record | Fully Executable |
| [CORE-000709](#core-000709) | When a record is present in the CE dataset where CETERM or CEDECOD  = 'DEATH' then death flag (DTHFL) should be popul... | Record Data | Record | Fully Executable |
| [CORE-000710](#core-000710) | Trigger error when --STRTPT is not in ('BEFORE', 'COINCIDENT', 'AFTER', 'UNKNOWN') and --STTPT is prior to the date o... | Record Data | Record | Partially Executable |
| [CORE-000711](#core-000711) | Subject Reference Start Date/Time (RFSTDTC) should be prior or equal to the Subject Reference End Date/Time (RFENDTC). | Record Data | Record | Fully Executable |
| [CORE-000712](#core-000712) | Part A - Raise and error when IDVAR in SUPP-- is not populated with a valid variable from the dataset referenced in R... | Record Data | Record | Fully Executable |
| [CORE-000713](#core-000713) | Date/Time of Informed Consent (RFICDTC) should be prior or equal to the Subject Reference Start Date/Time (RFSTDTC). | Record Data | Record | Fully Executable |
| [CORE-000714](#core-000714) | Date/Time of First Study Treatment (RFXSTDTC) variable value should be prior or equal to the Date/Time of Last Study ... | Record Data | Record | Fully Executable |
| [CORE-000716](#core-000716) | --SOCCD and --SOC should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000717](#core-000717) | Start Date/Time of Adverse Event (AESTDTC) should be prior or equal to the Start Date/Time of the latest Disposition ... | Record Data | Record | Fully Executable |
| [CORE-000718](#core-000718) | Start Date/Time of Observation (--STDTC) should be prior or equal to the End Date/Time of Observation (--ENDTC). | Record Data | Record | Fully Executable |
| [CORE-000719](#core-000719) | --HLGTCD and --HLGT should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000720](#core-000720) | Date/Time of Collection (--DTC) variable value should be prior or equal to Date/Time of End of Participation (RFPENDTC). | Record Data | Record | Fully Executable |
| [CORE-000723](#core-000723) | --HLTCD and --HLT should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000726](#core-000726) | When Ethnicity was collected and it was not mapped as "HISPANIC OR LATINO" or "NOT HISPANIC OR LATINO". | Record Data | Record | Partially Executable |
| [CORE-000728](#core-000728) | TSVALCD and TSVAL should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000729](#core-000729) | INVID and INVNAM shoumd have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000732](#core-000732) | --STRESC is not populated with a numeric value, but --STRESN is not empty. | Record Data | Record | Fully Executable |
| [CORE-000736](#core-000736) | Either AGE or AGETXT is in the list of TSPARMCDs with TSVAL populated, not both. | Record Data | Record | Fully Executable |
| [CORE-000739](#core-000739) | Trigger error when study includes protocol-specified study treatment and EX is not present | Domain Presence Check | Record | Fully Executable |
| [CORE-000741](#core-000741) | The set  ('INTMODEL’, 'INTTYPE','PCLASS') is not in the list of TSPARAMCD with TSVAL populated. | Record Data | Record | Fully Executable |
| [CORE-000742](#core-000742) | TSVALNF is not equal to NA when TSPARAMCD=INDIC | Record Data | Record | Fully Executable |
| [CORE-000743](#core-000743) | IETESTCD and IETEST should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000744](#core-000744) | Raise an error when a related record is present in the parent domain dataset but FAOBJ is not equal to the --TERM, --... | Record Data | Record | Fully Executable |
| [CORE-000745](#core-000745) | OIPARMCD and OIPARM should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000746](#core-000746) | When ethnicity term 'Spanish origin' is collected (SUPPDM.CETHNIC), it should be mapped to Ethnicity (ETHNIC) 'HISPAN... | Record Data | Record | Fully Executable |
| [CORE-000747](#core-000747) | When Race Term 'Haitian' or 'Negro' (SUPPDM.CRACE) is collected, it should be mapped to Race (RACE) 'BLACK OR AFRICAN... | Record Data | Record | Fully Executable |
| [CORE-000757](#core-000757) | When findings are collected about an intervention, and the interventions parent record exists and --DECOD = null, FAO... | Record Data | Record | Fully Executable |
| [CORE-000758](#core-000758) | Raise an error when milestone associated with RFSTDTC is start of treatment and ARMNRS is not null and different from... | Record Data | Record | Partially Executable |
| [CORE-000760](#core-000760) | Date/Time of First Challenge Agent Admin (RFCSTDTC) should be prior or equal to the Date/Time of Last Challenge Agent... | Record Data | Record | Fully Executable |
| [CORE-000761](#core-000761) | Trigger error when TSVCDREF = 'CDISC' and TSVCDVER is not a valid published version (date) | Record Data | Record | Fully Executable |
| [CORE-000763](#core-000763) | Trigger error when --STRTPT is not in ('BEFORE', 'COINCIDENT', 'UNKNOWN') | Record Data | Record | Partially Executable |
| [CORE-000765](#core-000765) | The submitted dataset is larger than 5 GB | Dataset Metadata Check | Dataset | Fully Executable |
| [CORE-000766](#core-000766) | When findings are collected, and the Events parent record exists and --DECOD is null, FAOBJ should not be equal to --... | Record Data | Record | Fully Executable |
| [CORE-000767](#core-000767) | When findings are collected, and the parent record exists and --DECOD is not null, FAOBJ should not be equal to --DECOD | Record Data | Record | Fully Executable |
| [CORE-000774](#core-000774) | When a test is not done (--STAT = NOT DONE), the Reason Not Done (--REASND) should be populated. | Record Data | Record | Partially Executable - Possible Overreporting |
| [CORE-000776](#core-000776) | When End Date/Time of Observation (--ENDTC) is present in the dataset, then the Study Day of End of Observation (--EN... | Record Data | Dataset | Fully Executable |
| [CORE-000777](#core-000777) | Raise an error when variable RELMIDS is present in any of the dataset and TM dataset is missing in a study. | Domain Presence Check | Dataset | Fully Executable |
| [CORE-000778](#core-000778) | When an Associated Persons non-supplemental qualifier dataset is associated with a split dataset, the dataset name le... | Dataset Metadata Check | Dataset | Fully Executable |
| [CORE-000779](#core-000779) | TDSTOFF must be equal to 0 or a positive value in ISO 8601 Duration format | Record Data | Record | Fully Executable |
| [CORE-000783](#core-000783) | Raise an error when SUPP--.QNAM is present in dataset, but value of SUPP--.QNAM is equal to a variable name defined i... | Record Data | Record | Fully Executable |
| [CORE-000784](#core-000784) | Raise an error when variable TAETORD values don't match between Subject Visits (SV) and Subject Elements (SE) datasets. | Record Data | Record | Fully Executable |
| [CORE-000785](#core-000785) | Part B - Raise an error when IESCAT is not empty, but IECAT is empty. | Record Data | Record | Fully Executable |
| [CORE-000786](#core-000786) | Part B - Raise an error when IESCAT exists in a dataset, but IECAT does not exist. | Record Data | Dataset | Fully Executable |
| [CORE-000787](#core-000787) | Raise an error when TSVALNF is empty and TSVAL is populated with values or synonyms of values in the ISO 21090 null f... | Record Data | Record | Fully Executable |
| [CORE-000791](#core-000791) | ACTARMCD and ACTARM should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000792](#core-000792) | --CLASCD and --CLAS should have a one-to-one relationship. | Record Data | Record | Fully Executable |
| [CORE-000793](#core-000793) | Collection study day (--DY) should be present when date/time of collection (--DTC) is populated | Record Data | Dataset | Fully Executable |
| [CORE-000841](#core-000841) | End Date/Time of Adverse Event (AEENDTC) in AE dataset of Adverse Event where AEOUT = 'FATAL' should be equal to the ... | Record Data | Record | Fully Executable |
| [CORE-000844](#core-000844) | Part A - Raise an error when RACE in DM equals 'MULTIPLE' and SUPPDM dataset is not present. | Domain Presence Check | Record | Fully Executable |
| [CORE-000845](#core-000845) | Part B -  Raise an error when RACE in DM equals 'MULTIPLE' but no records are present in SUPPDM. | Record Data | Record | Fully Executable |
| [CORE-000846](#core-000846) | Part C - Raise an error when RACE in DM equals 'MULTIPLE' but no multiple RACE records are present in SUPPDM. | Record Data | Record | Fully Executable |
| [CORE-000852](#core-000852) | Raise an error when variables are not in the specified order | Variable Metadata Check | Dataset | Fully Executable |
| [CORE-000853](#core-000853) | Collection study day (--DY) should be populated when date/time of collection (--DTC) is populated | Record Data | Record | Fully Executable |
| [CORE-000862](#core-000862) | When Study Day of Start of Observation (--STDY) is present in the dataset, then the Start Date/Time of Observation (-... | Record Data | Dataset | Fully Executable |
| [CORE-000863](#core-000863) | When --STRESC is populated with a numeric value, --STRESN should be populated. | Record Data | Record | Fully Executable |
| [CORE-000864](#core-000864) | When Study Day of End of Observation (--ENDY) is present in the dataset, then the End Date/Time of Observation (--END... | Record Data | Dataset | Fully Executable |
| [CORE-000865](#core-000865) | When Planned Elapsed Time from Time Point Ref (--ELTM) is populated, Time Point Reference (--TPTREF) should also be p... | Record Data | Record | Fully Executable |
| [CORE-000866](#core-000866) | Date/Time of Collection (--DTC) should be prior or equal to the End Date/Time of Observation (--ENDTC) | Record Data | Record | Fully Executable |
| [CORE-000867](#core-000867) | Text variable in submitted dataset should not contain leading spaces ' '. | Value Check with Variable Metadata | Record | Fully Executable |
| [CORE-000880](#core-000880) | Planned Duration (--PDUR) value should not be negative. | Record Data | Record | Fully Executable |
| [CORE-000885](#core-000885) | All subjects in the Demographics domain who are participating in a study that includes an interventional product must... | Record Data | Record | Fully Executable |
| [CORE-000886](#core-000886) | All subjects that have no record in the Exposure domain who are participating in a study that includes an interventio... | Record Data | Record | Fully Executable |
| [CORE-000889](#core-000889) | Normal Range Upper Limit-Original Units (--ORNRHI) is greater than the Normal Range Lower Limit-Original Units (--ORN... | Record Data | Record | Partially Executable - Possible Underreporting |
| [CORE-000890](#core-000890) | Text variable in submitted dataset should not contain  '.' as an entire value. | Value Check with Variable Metadata | Record | Fully Executable |
| [CORE-000892](#core-000892) | When Eed timepoint (--ENDTC, --ENRF or --ENRTPT) is populated, related start timepoint (--STDTC, --STRF or --STRTPT s... | Record Data | Record | Fully Executable |
| [CORE-000901](#core-000901) | The values of PPCAT and PCTEST do not match at the same reference timepoint | Record Data | Record | Partially Executable |
| [CORE-000913](#core-000913) | Disposition date time (DSSTDTC) for DEATH record in the DS dataset should be equal to the Date of Death (DTHDTC) in t... | Record Data | Record | Fully Executable |
| [CORE-000914](#core-000914) | There should be only one record with a baseline flag and non missing character standard result value having the same ... | Record Data | Record | Fully Executable |
| [CORE-000915](#core-000915) | There should be only one record with a last observation before exposure flag and non missing character standard resul... | Record Data | Record | Fully Executable |
| [CORE-000916](#core-000916) | Part B - Raise and error when IDVAR in RELREC is not populated with a valid variable from the dataset referenced in R... | Record Data | Record | Fully Executable |
| [CORE-000927](#core-000927) | All subjects in the Exposure domain who are participating in a study that includes an interventional product must be ... | Record Data | Record | Fully Executable |
| [CORE-000929](#core-000929) | Raise and error when the DOMAIN Code is not a valid Domain Code published by CDISC. | Define Item Metadata Check against Library Metadata | Record | Fully Executable |
| [CORE-000952](#core-000952) | End Date/Time (--ENDTC) variable value should be prior or equal to the Date/Time of End of Participation (RFPENDTC). | Record Data | Record | Fully Executable |
| [CORE-000953](#core-000953) | Part C - Raise and error when IDVAR in CO is not populated with a valid variable from the dataset referenced in RDOMAIN. | Record Data | Record | Fully Executable |
| [CORE-001034](#core-001034) | Raise an error when REPNUM is in the dataset and there are multiple records for a subject for a test within the timef... | Record Data | Record | Fully Executable |
| [CORE-001043](#core-001043) | Age should be provided for all subjects, except where not collected for screen failures. | Record Data | Record | Fully Executable |
| [CORE-001078](#core-001078) | When Death flag (DTHFL) in the DM dataset is populated as 'Y' then a record should be present in the AE dataset where... | Record Data | Group | Fully Executable |
| [CORE-001080](#core-001080) | Raise an error when TSVCDREF ='CDISC' and TSVALCD is not a valid code in the version identified in TSVCDVER. | Record Data | Record | Fully Executable |
| [CORE-001081](#core-001081) | Raise an error when the metadata attribute of variable role does not match the IG role for domain in IG, or model rol... | Define Item Metadata Check against Library Metadata | Record | Fully Executable |
| [CORE-001082](#core-001082) | Raise an error when the variable type does not match IG Type (for domains in IG) or Model Type (custom domains) | Variable Metadata Check against Library Metadata | Record | Fully Executable |

---

## Rule Details

### CORE-000001

**Description:** Raise an error when IECAT is equal to 'INCLUSION' and IEORRES is not equal to 'N'.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000001 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTM v3.4, Section 6.3.4

**Rule Identifiers:** CG0176, TIG0405

---

### CORE-000002

**Description:** Raise an error when SESTDTC is null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000002 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0208

---

### CORE-000003

**Description:** Raise an error when TRLOC is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000003 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | TR |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTM v3.4, Section 6.3.12.2

**Rule Identifiers:** CG0299

---

### CORE-000004

**Description:** When ECOCCUR indicates no dose, then ECDOSE needs to be null or > 0; it cannot be used to indicate a dose wasn't taken.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000004 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | EC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.1.3.2

**Rule Identifiers:** CG0101, TIG0366

---

### CORE-000005

**Description:** When EXTRT is PLACEBO, EXDOSE must equal 0

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000005 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.1.3.1

**Rule Identifiers:** CG0102

---

### CORE-000006

**Description:** Raise an error when DTHFL ^= "Y" and not null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000006 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0131, TIG0381

---

### CORE-000007

**Description:** When Date/Time of Death (DTHDTC) in the DM dataset is populated then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000007 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0435, FB0606, TIG0587

---

### CORE-000008

**Description:** When survival status is completed as 'DEAD' in the SS dataset then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000008 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.5.8|6.3.11

**Rule Identifiers:** CG0132, FB0601

---

### CORE-000009

**Description:** Verify that ELEMENT value is blank when ETCD is equal to UNPLAN

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000009 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.3 Specification

**Rule Identifiers:** CG0152, SEND124.1, TIG0061, TIG0393

---

### CORE-000010

**Description:** Verify ARMCD value length is <= 20

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000010 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, TRIAL DESIGN |
| Domains | DM, TA |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 4.2.1

**Rule Identifiers:** CG0153, TIG0394

---

### CORE-000011

**Description:** Raise an error when IECAT is equal to 'EXCLUSION' and IEORRES is not equal to 'Y'.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000011 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.4

**Rule Identifiers:** CG0175, TIG0404

---

### CORE-000012

**Description:** Raise an error when AEOCCUR exists in AE dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000012 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.2.1

**Rule Identifiers:** CG0040, TIG0319

---

### CORE-000013

**Description:** Raise an error when AESTAT is present in AE dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000013 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.2.1

**Rule Identifiers:** CG0044, TIG0323

---

### CORE-000014

**Description:** Raise an error when --PRESP is not equals to "Y" and --OCCUR is present in dataset or --STAT is equal to "NOT DONE" and --OCCUR is not blank

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000014 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:AE, Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0087, TIG0352

---

### CORE-000015

**Description:** Raise an error when --PRESP does not exists in a dataset and --OCCUR exist.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000015 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:AE, Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0088, TIG0353

---

### CORE-000016

**Description:** Raise an error when --OCCUR is not empty and --PRESP is not equal to "Y".

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000016 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:AE, Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0089, TIG0354

---

### CORE-000017

**Description:** Raise an error when RDOMAIN is null when IDVARVAL is not null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000017 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | CO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.1

**Rule Identifiers:** CG0166, TIG0398

---

### CORE-000018

**Description:** Raise an error when --PRESP is equal to "Y", --STAT is blank, --OCCUR is present in dataset and --OCCUR is blank

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000018 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:AE, Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0086, TIG0351

---

### CORE-000019

**Description:** Raise and error if Variable label length > 40 characters

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000019 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.1

**Rule Identifiers:** CG0311, SEND3, TIG0211, TIG0486

---

### CORE-000020

**Description:** Raise an error when ETCD="UNPLAN" and TAETORD is not null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000020 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3

**Rule Identifiers:** CG0206, TIG0426

---

### CORE-000021

**Description:** Raise an error when the value for --STRESC is null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000021 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** Model v2.0|IG v3.4, Section Findings|6.3.5.3

**Rule Identifiers:** CG0397, TIG0559

---

### CORE-000022

**Description:** Raise an error when AESCAN, AESCONG, AESDISAB, AESDTH, AESHOSP, AESLIFE, AESOD or AESMIE = 'Y' and AESER = 'N' or is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000022 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.2.1

**Rule Identifiers:** CG0041, TIG0320

---

### CORE-000023

**Description:** Raise an error when --TOXGR is not present in dataset, but --TOX is present

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000023 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS, FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0084, TIG0349

---

### CORE-000024

**Description:** Raise an error if --BODSYS is not empty, --BDSYCD is empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000024 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0082, TIG0347

---

### CORE-000025

**Description:** IESTRESC is not equal to IEORRES

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000025 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.4

**Rule Identifiers:** CG0177, TIG0406

---

### CORE-000026

**Description:** When --TPT is present in a dataset, --TPTNUM must also be present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000026 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0468, TIG0598

---

### CORE-000027

**Description:** Either TEENRL or TEDUR must be present for each Element.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000027 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 7.2.2

**Rule Identifiers:** CG0328, CG0329, SEND214, TIG0141, TIG0496, TIG0497

---

### CORE-000028

**Description:** Raise an error when --TPTREF is empty, but --ELTM is not empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000028 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0008, TIG0293

---

### CORE-000029

**Description:** Raise an error when --TPTNUM exists in a dataset and --TPT does not exist.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000029 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.10

**Rule Identifiers:** CG0661, TIG0697

---

### CORE-000030

**Description:** Raise and error when --REASND is present in dataset and --PRESP is not present in dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000030 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0053, TIG0329

---

### CORE-000031

**Description:** --EVAL must not be used to model QRS data.   This includes the 'QS' and 'FT' domains as well as the 'RS' domain when the record pertains to a Clinical Classification Use Case). This rule has been fully executed for the QS and FT domains only.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000031 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | QS, FT, RS |

**Applicable Standards:** SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.9.1

**Rule Identifiers:** CG0659

---

### CORE-000032

**Description:** --EVALID must not be used to model QRS data.   This includes the 'QS' and 'FT' domains as well as the 'RS' domain when the record pertains to a Clinical Classification Use Case). This rule has been fully executed for the QS and FT domains only.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000032 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | QS, FT, RS |

**Applicable Standards:** SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.9.1

**Rule Identifiers:** CG0660

---

### CORE-000033

**Description:** Raise an error when DSTERM = "COMPLETED" and DSDECOD not equal to "COMPLETED".

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000033 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.2.3

**Rule Identifiers:** CG0065, TIG0337

---

### CORE-000034

**Description:** Raise an error when DSSTDTC is not equal to DM.DTHDTC and DSDECOD is equal to "DEATH"

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000034 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.2

**Rule Identifiers:** CG0069, TIG0339

---

### CORE-000035

**Description:** Raise an error when SVPRESP is null and VISITDY is not null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000035 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.5

**Rule Identifiers:** CG0658, TIG0696

---

### CORE-000036

**Description:** Raise an error when SVPRESP is "Y" and VISIT is not present in TV.VISIT.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000036 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.5

**Rule Identifiers:** CG0657, TIG0695

---

### CORE-000037

**Description:** Raise an error when SVPRESP not "Y" or null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000037 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0653, TIG0691

---

### CORE-000038

**Description:** Raise an error when SVOCCUR is not null and SVPRESP is not "Y".

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000038 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0654, TIG0692

---

### CORE-000039

**Description:** Raise an error when SVPRESP="Y" and VISITNUM is not in TV.VISITNUM

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000039 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.5

**Rule Identifiers:** CG0655, TIG0693

---

### CORE-000040

**Description:** Raise an error when SVPRESP is null and VISITNUM is present in TV.VISITNUM

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000040 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.5

**Rule Identifiers:** CG0656, TIG0694

---

### CORE-000041

**Description:** Raise and error when TSVAL is not populated TSVAL is populated with values or synonyms of values in the ISO 21090 null flavor codelist (or other terms that can be represented as null flavors) but TSVALNF is completed.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000041 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 7.4.2

**Rule Identifiers:** CG0459, CG0649, TIG0689

---

### CORE-000042

**Description:** Raise an error when TT dataset is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000042 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | TT |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0647, TIG0687

---

### CORE-000043

**Description:** Raise an error when TP dataset is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000043 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | TP |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0648, TIG0688

---

### CORE-000044

**Description:** Raise an error when SJ dataset is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000044 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SJ |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0646, TIG0686

---

### CORE-000045

**Description:** Verify ARMNRS is not null when ARMCD is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000045 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0517, TIG0612

---

### CORE-000046

**Description:** Verify ARMNRS is not null when ARM is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000046 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0519, TIG0614

---

### CORE-000047

**Description:** When study does not use multi-stage arm assignments and ARM is populated, ARM must be present in TA.ARM. This rule has been executed to identify all cases when ARM is not present in TA.ARM and therefore acknowledges that false positives may be recorded when multi-stage arm assignments are in use.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000047 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.2

**Rule Identifiers:** CG0518, TIG0613

---

### CORE-000048

**Description:** Raise an error when --METHOD is present in an Interventions dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000048 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0621, TIG0662

---

### CORE-000049

**Description:** Raise an error when --USCHFL is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000049 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0507, CG0622, TIG0602, TIG0663

---

### CORE-000050

**Description:** Raise an error when --RSTIND is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000050 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0623, TIG0664

---

### CORE-000051

**Description:** Raise an error when --RSTMOD is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000051 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0624, TIG0665

---

### CORE-000052

**Description:** Raise an error when --IMPLBL is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000052 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0625, TIG0666

---

### CORE-000054

**Description:** Raise an error when --DTHREL is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000054 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0627, TIG0668

---

### CORE-000055

**Description:** Raise an error when --EXCLFL is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000055 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0628, TIG0669

---

### CORE-000056

**Description:** Raise an error when --REASEX is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000056 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0629, TIG0670

---

### CORE-000057

**Description:** FETUSID must not be present in SDTM domains.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000057 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0509, CG0630, TIG0604, TIG0671

---

### CORE-000058

**Description:** Raise an error when RPHASE is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000058 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0631, TIG0672

---

### CORE-000059

**Description:** Raise an error when RPPLDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000059 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0632, TIG0673

---

### CORE-000060

**Description:** Raise an error when RPPLSTDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000060 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0633, TIG0674

---

### CORE-000061

**Description:** Raise an error when RPPLENDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000061 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0634, TIG0675

---

### CORE-000064

**Description:** Raise an error when --RPDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000064 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0637, TIG0678

---

### CORE-000065

**Description:** Raise an error when --RPSTDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000065 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0638, TIG0679

---

### CORE-000066

**Description:** Raise an error when --RPENDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000066 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0639, TIG0680

---

### CORE-000067

**Description:** Raise an error when --DETECT is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000067 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0640, TIG0681

---

### CORE-000068

**Description:** Raise an error when AGETXT is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000068 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0641, TIG0682

---

### CORE-000069

**Description:** Raise an error when SPECIES is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000069 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0642, TIG0683

---

### CORE-000070

**Description:** Raise an error when STRAIN is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000070 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0643, TIG0684

---

### CORE-000071

**Description:** Raise an error when SBSTRAIN is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000071 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0644, TIG0685

---

### CORE-000072

**Description:** Raise an error when --BEATNO is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000072 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | Excl:EG |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0542

---

### CORE-000073

**Description:** Raise an error when RPATHCD is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000073 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0533

---

### CORE-000074

**Description:** Raise an error when --IMPLBL is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000074 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0508, TIG0603

---

### CORE-000075

**Description:** Raise an error when AEREASND is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000075 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.1

**Rule Identifiers:** CG0304, TIG0481

---

### CORE-000076

**Description:** Raise an error when TRPORTOT is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000076 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | TR |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.12.2

**Rule Identifiers:** CG0302

---

### CORE-000077

**Description:** Raise an error when TRDIR is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000077 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | TR |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.12.2

**Rule Identifiers:** CG0301

---

### CORE-000078

**Description:** Raise an error when TRLAT is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000078 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | TR |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.12.2

**Rule Identifiers:** CG0300

---

### CORE-000079

**Description:** Raise an error when --LOC is not present and --LAT is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000079 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0095

---

### CORE-000080

**Description:** Raise an error when --ELTM, --TPTNUM, and --TPT are not present in dataset, but --TPTREF is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000080 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0093, TIG0358

---

### CORE-000081

**Description:** Raise an error when --PRESP is not present but --STAT is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000081 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0056, TIG0330

---

### CORE-000082

**Description:** Verify that PESTRESC is null when PEORRES is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000082 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | PE |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.8

**Rule Identifiers:** CG0561

---

### CORE-000083

**Description:** Verify that --ORRES is not null when --LOBXFL = Y and --DRVFL= null or --DRVFL is not present in the dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000083 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0553, TIG0641

---

### CORE-000084

**Description:** Raise an error when --ENTPT exists in a dataset and --ENRTPT does not exist.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000084 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0057, TIG0331

---

### CORE-000085

**Description:** Raise an error when --STTPT is completed and --STRTPT is empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000085 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0059, TIG0333

---

### CORE-000086

**Description:** Raise an error when DVSTDTC is earlier than RFICDTC in DM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000086 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.7

**Rule Identifiers:** CG0075, TIG0341

---

### CORE-000087

**Description:** Raise an error when AESER is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000087 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0387, TIG0549

---

### CORE-000088

**Description:** Verify that the length of value in SETCD variable is <= 8

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000088 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, TRIAL DESIGN |
| Domains | DM, TX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0149, SEND25, TIG0169, TIG0390

---

### CORE-000089

**Description:** Raise an error when --TRTV is empty, --VAMT is not empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000089 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0106

---

### CORE-000090

**Description:** Verify IDVAR is null when RDOMAIN is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000090 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | CO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.1

**Rule Identifiers:** CG0164, TIG0397

---

### CORE-000091

**Description:** Raise an error when --TRTV is null, --VAMTU is not null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000091 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0108

---

### CORE-000092

**Description:** Raise an error when --DOSE ^= null and --DOSTXT ^= null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000092 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0110, CG0111, TIG0374, TIG0375

---

### CORE-000093

**Description:** Raise an error when --DOSU = null and (--DOSE ^= null or --DOSTOT ^= null or --DOSTXT ^= null)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000093 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0114, TIG0377

---

### CORE-000094

**Description:** Raise an error if --DOSTXT value is numeric.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000094 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0112, TIG0376

---

### CORE-000095

**Description:** Raise an error when SEUPDES ^= null, ETCD ^= 'UNPLAN'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000095 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3

**Rule Identifiers:** CG0211, TIG0430

---

### CORE-000096

**Description:** Raise an error when --LOC does not exists in a dataset, --PORTOT exists.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000096 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, FINDINGS, EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0115, TIG0378

---

### CORE-000097

**Description:** Raise an error when variable EPOCH values don't match between Subject Visits (SV) and Subject Elements (SE) datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000097 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.5

**Rule Identifiers:** CG0218, TIG0432

---

### CORE-000098

**Description:** Raise an error when --LOC does not exists in a dataset, --DIR exists.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000098 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, FINDINGS, EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0116, TIG0379

---

### CORE-000099

**Description:** Raise an error when both --ORRES and --STAT values are populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000099 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.5.1.1

**Rule Identifiers:** CG0422, TIG0577

---

### CORE-000100

**Description:** Raise an error when --VAMT is not empty, but --TRTV is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000100 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0423, TIG0578

---

### CORE-000101

**Description:** Raise an error when --RESCAT is not empty, but --STRESC is empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000101 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0427, TIG0581

---

### CORE-000102

**Description:** Raise an error when --TOX is not empty, but --TOXGR is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000102 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS, FINDINGS |
| Domains | AE, MH, CE, EG, LB, PC, PP |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0428, TIG0582

---

### CORE-000103

**Description:** Part A - Raise an error when --SCAT is not empty, but --CAT is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000103 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0429, TIG0583

---

### CORE-000104

**Description:** Part A - Raise an error when --SCAT exists in a dataset, but --CAT does not exist.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000104 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0430, TIG0584

---

### CORE-000105

**Description:** Raise an error when --LOBXFL = 'Y' and --STRESC is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000105 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0569, FB2602, TIG0653

---

### CORE-000106

**Description:** Raise an error when --ENTPT is completed and --ENRTPT is not completed.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000106 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0045, TIG0324

---

### CORE-000107

**Description:** APID is required in all Associated Persons Data. In addition to STUDYID, DOMAIN, and --SEQ being required for all domains based on one of the 3 general observation classes, one of USUBJID, APID, SPDEVID, or POOLID must also be present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000107 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS, FINDINGS ABOUT, FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0554, TIG0642

---

### CORE-000108

**Description:** When a record is present in the DD dataset then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000108 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0133, FB0602, TIG0382

---

### CORE-000109

**Description:** Raise an error when SMSTDTC is null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000109 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.4

**Rule Identifiers:** CG0547

---

### CORE-000110

**Description:** Raise an error when (--ORREF ^= null or --DRVFL='Y') and --STREFC is null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000110 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0549, TIG0637

---

### CORE-000111

**Description:** Raise an error when --AGENT is present in a dataset other than MS.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000111 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | Excl:MS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0564, TIG0649

---

### CORE-000112

**Description:** Raise an error when --CONC is present in a dataset other than MS.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000112 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | Excl:MS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0565, TIG0650

---

### CORE-000113

**Description:** Raise an error when --CONCU is present in a dataset other than MS.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000113 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | Excl:MS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0566, TIG0651

---

### CORE-000114

**Description:** Raise an error when --EVDTYP is present in a dataset other than MH.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000114 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | Excl:MH |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0567, TIG0652

---

### CORE-000115

**Description:** Raise an error when ARM is 'Screen Failure', 'Not Assigned', 'Unplanned Treatment', 'Not Treated'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000115 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN, SPECIAL PURPOSE |
| Domains | DM, TA, TV |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 7.2

**Rule Identifiers:** CG0570

---

### CORE-000116

**Description:** Raise an error when --SPCUFL is not null or equal to "N"

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000116 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0619, TIG0660

---

### CORE-000117

**Description:** Raise an error when --STAT not equal to 'NOT DONE' when --PRESP = 'Y' and --OCCUR is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000117 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0081, TIG0346

---

### CORE-000118

**Description:** Raise an error when --PRESP is equal to 'Y' and --OCCUR is empty and --STAT is not present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000118 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0404, TIG0562

---

### CORE-000119

**Description:** Raise an error when ARM is not empty, but ARMCD is empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000119 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0521, TIG0616

---

### CORE-000120

**Description:** Raise an error when ACTARM is not empty, but ACTARMCD is empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000120 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0522, TIG0617

---

### CORE-000121

**Description:** Verify the value for ARMNRS, when both ARMCD and ACTARMCD values are populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000121 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0520, TIG0615

---

### CORE-000122

**Description:** Raise an error when AGEU is completed but both AGETXT and AGE are not completed.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000122 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0434, TIG0586

---

### CORE-000123

**Description:** Raise an error when AESCAN is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000123 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0388, TIG0550

---

### CORE-000124

**Description:** Raise an error when AESCONG is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000124 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0389, TIG0551

---

### CORE-000125

**Description:** Raise an error when AESDISAB is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000125 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0390, TIG0552

---

### CORE-000126

**Description:** Raise an error when AESDTH is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000126 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0391, TIG0553

---

### CORE-000127

**Description:** Raise an error when AESHOSP is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000127 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0392, TIG0554

---

### CORE-000128

**Description:** Raise an error when AESLIFE is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000128 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0393, TIG0555

---

### CORE-000129

**Description:** Raise an error when AESOD is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000129 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0394, TIG0556

---

### CORE-000130

**Description:** Raise an error when AESMIE is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000130 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0395, TIG0557

---

### CORE-000131

**Description:** Raise an error when AECONTRT is completed and value is not 'Y' or 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000131 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0396, TIG0558

---

### CORE-000132

**Description:** ETCD and ELEMENT should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000132 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TE, TA, SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3|7.2.1|7.2.2

**Rule Identifiers:** CG0154, FB0914, SEND213

---

### CORE-000133

**Description:** When --STRESU is populated, --STRESC must also be populated. Please note that this rule, as executed, cannot check if --STRESC is populated with the standardized unit.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000133 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0426, TIG0580

---

### CORE-000134

**Description:** Raise an error when RDOMAIN ^= 'DM' and IDVAR is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000134 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 8.4.1

**Rule Identifiers:** CG0203, TIG0423

---

### CORE-000135

**Description:** Raise an error when IDVAR is not empty and IDVARVAL is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000135 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 8.4.1

**Rule Identifiers:** CG0204, TIG0424

---

### CORE-000136

**Description:** Raise an error when IDVARVAL and USUBJID are empty and IDVAR = Sequence Number.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000136 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | RELREC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 8.3.1 RELREC Dataset Relationship Example

**Rule Identifiers:** CG0201, TIG0422

---

### CORE-000137

**Description:** Raise an error when ECOCCUR is not equal to 'N', ECSTAT and ECDOSTXT are both empty but ECDOSE less than or equal to 0.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000137 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | EC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.1.3.2

**Rule Identifiers:** CG0100, TIG0365

---

### CORE-000138

**Description:** If --STDTC or DM.RFSTDTC does not contain a complete values, --STDY must be null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000138 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.4

**Rule Identifiers:** CG0221, TIG0435

---

### CORE-000139

**Description:** Trigger error if --ENDTC or DM.RFSTDTC does not contain complete values in their date portion, and --ENDY is not null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000139 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.4

**Rule Identifiers:** CG0223, TIG0437

---

### CORE-000140

**Description:** Trigger error if VISITDY is populated when VISITNUM is not in TV.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000140 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.5

**Rule Identifiers:** CG0225, TIG0438

---

### CORE-000141

**Description:** VISITNUM and --TPTREF are not present then --TPT and --TPTNUM should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000141 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.10

**Rule Identifiers:** CG0240, FB0922, TIG0448

---

### CORE-000142

**Description:** Raise an error when --TPT ^= null and --TPTNUM ^= null and --ELTM ^= null and --ELTM does not have the same value across records with the same values of DOMAIN, VISITNUM, --TPTREF, and --TPTNUM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000142 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.10

**Rule Identifiers:** CG0241, TIG0449

---

### CORE-000143

**Description:** The length of ETCD should be no greater than 8

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000143 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN, SPECIAL PURPOSE |
| Domains | TA, TE, SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3

**Rule Identifiers:** CG0246, SEND24, TIG0165, TIG0450

---

### CORE-000144

**Description:** Trigger error when TAETORD is not unique within an ARM

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000144 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TA |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.2.1

**Rule Identifiers:** CG0247, TIG0451

---

### CORE-000145

**Description:** Trigger error when TIVERS is present and IETESTCD is not unique within TIVERS

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000145 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TI |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.1

**Rule Identifiers:** CG0255

---

### CORE-000146

**Description:** Trigger error when IETESTCD is not unique within dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000146 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TI |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.1

**Rule Identifiers:** CG0256

---

### CORE-000147

**Description:** Trigger error when length of TSPARMCD is greater than 8

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000147 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0257, SEND26, TIG0178, TIG0461

---

### CORE-000148

**Description:** Trigger error when length of TSPARM value is greater than 40

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000148 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0258, TIG0462

---

### CORE-000149

**Description:** Trigger error when TSVAL is null and TSVALNF is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000149 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0259

---

### CORE-000150

**Description:** Trigger error when TSVAL is populated and TSVALNF is also populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000150 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0260

---

### CORE-000151

**Description:** Trigger error when TSVAL1 is populated and TSVAL is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000151 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0261, SEND281

---

### CORE-000152

**Description:** When TSVAL and TSVALCD are populated, there must be a one-to-one relationship between TSVALCD and TSVAL

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000152 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0265, TIG0467

---

### CORE-000153

**Description:** Trigger error when TSVCDVER is populated and TSVCDREF is null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000153 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0266

---

### CORE-000154

**Description:** TSSEQ must be unique within TSPARMCD

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000154 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0268, SEND246, TIG0167, TIG0470

---

### CORE-000155

**Description:** Trigger error when ARMCD is populated and ARMCD is not in TA.ARMCD

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000155 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.3.1

**Rule Identifiers:** CG0293, TIG0475

---

### CORE-000156

**Description:** Trigger error when ARM is populated and ARM is not in TA.ARM

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000156 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.3.1

**Rule Identifiers:** CG0294, TIG0476

---

### CORE-000157

**Description:** The length of ARMCD is limited to 20 characters.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000157 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.3.1

**Rule Identifiers:** CG0297, TIG0479

---

### CORE-000158

**Description:** IDVAR must be specified.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000158 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | CO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.1

**Rule Identifiers:** CG0163, TIG0396

---

### CORE-000159

**Description:** Raise an error when --TESTCD = 'OTHER'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000159 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.7.3

**Rule Identifiers:** CG0341, TIG0506

---

### CORE-000160

**Description:** Raise an error when --TRT = 'OTHER'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000160 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.7.3

**Rule Identifiers:** CG0342, TIG0507

---

### CORE-000161

**Description:** Raise an error when --TERM = 'OTHER'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000161 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.7.3

**Rule Identifiers:** CG0343, TIG0508

---

### CORE-000162

**Description:** Raise an error when --TESTCD = 'MULTIPLE'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000162 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.8.2

**Rule Identifiers:** CG0344, TIG0509

---

### CORE-000163

**Description:** Raise an error when --TRT = 'MULTIPLE'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000163 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.8.1

**Rule Identifiers:** CG0345, TIG0510

---

### CORE-000164

**Description:** Raise an error when --TERM = 'MULTIPLE'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000164 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.8.1

**Rule Identifiers:** CG0346, TIG0511

---

### CORE-000165

**Description:** Time point reference (--TPTREF) should be present in the dataset, when reference time point (--RFTDTC) is also present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000165 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0090, FB3701, TIG0355

---

### CORE-000166

**Description:** Raise an error when --TPT is not present but --TPTNUM is present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000166 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0091, TIG0356

---

### CORE-000167

**Description:** Raise an error when--ELTM is present but --TPTREF is not present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000167 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0092, TIG0357

---

### CORE-000168

**Description:** If VISITNUM is not null then VISITNUM should be among SV.VISITNUM

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000168 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:SV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.5

**Rule Identifiers:** CG0034, TIG0315

---

### CORE-000169

**Description:** Raise a warning when LBTOXGR is not numeric

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000169 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | LB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.5.6

**Rule Identifiers:** CG0185, TIG0414

---

### CORE-000170

**Description:** Raise an error when --LOBXFL is not 'Y' or null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000170 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section Various Findings Domains

**Rule Identifiers:** CG0541, TIG0634

---

### CORE-000171

**Description:** Raises an error when --ENRTPT is present in dataset but --ENTPT is not present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000171 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0058, TIG0332

---

### CORE-000172

**Description:** Raise an error when STUDYID is not equal to DM.STUDYID. The STUDYID in all domains must be the same in all records across the study.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000172 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0409, SEND249.1, TIG0168, TIG0565

---

### CORE-000173

**Description:** Raise an error when ETCD is not equal to 'UNPLAN' and not equal to TE.ETCD

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000173 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, TRIAL DESIGN |
| Domains | SE, TA |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG V3.4, Section 5.3

**Rule Identifiers:** CG0414, TIG0569

---

### CORE-000174

**Description:** Verify variable SPECIES does not exist in DM dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000174 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG 3.4, Section 2.7

**Rule Identifiers:** CG0356

---

### CORE-000175

**Description:** Verify variable STRAIN does not exist in DM dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000175 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG 3.4, Section 2.7

**Rule Identifiers:** CG0357

---

### CORE-000176

**Description:** Verify variable SBSTRAIN does not exist in DM dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000176 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG 3.4, Section 2.7

**Rule Identifiers:** CG0358

---

### CORE-000177

**Description:** Raise an error when DM.RFENDTC is empty and --ENRF is not empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000177 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0227, TIG0440

---

### CORE-000178

**Description:** Raise an error when SSSTRESC = 'DEAD' and SSDTC < max DS.DSSTDTC.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000178 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | SS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.11

**Rule Identifiers:** CG0172

---

### CORE-000179

**Description:** TSPARMCD and TSPARM should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000179 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0307, FB0916, TIG0482

---

### CORE-000180

**Description:** Raise an error when DOMAIN value length is not equal 2.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000180 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Domains | Excl:AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.2

**Rule Identifiers:** CG0308, TIG0483

---

### CORE-000181

**Description:** Raise an error when AP-- domain value length is not equal to 4.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000181 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.2

**Rule Identifiers:** CG0309

---

### CORE-000182

**Description:** Raise an error when variable name length is greater than 8.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000182 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.8.3

**Rule Identifiers:** CG0310, SEND2, TIG0128, TIG0485

---

### CORE-000183

**Description:** Raise an error when a PP dataset is present in study, but a PC dataset is not present in study.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000183 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | PC, PP |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.6

**Rule Identifiers:** CG0318, TIG0489

---

### CORE-000184

**Description:** --BODSYS and --BDSYCD  have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000184 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0083, FB0910, TIG0348

---

### CORE-000185

**Description:** Trigger error if length of ACTARMCD is greater than 20 characters

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000185 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0123, TIG0380

---

### CORE-000186

**Description:** Trigger error if SUBJID is not unique

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000186 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0150, TIG0391

---

### CORE-000187

**Description:** Trigger error when IDVAR is not null and CODTC is not null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000187 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | CO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.1

**Rule Identifiers:** CG0168, TIG0400

---

### CORE-000188

**Description:** Trigger error if MS dataset is present and MB dataset is not present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000188 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | MS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.6

**Rule Identifiers:** CG0191

---

### CORE-000189

**Description:** Raise an error when AGE is not blank and AGEU is blank.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000189 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0665, TIG0699

---

### CORE-000190

**Description:** Raise an error when AGEU is not blank and AGE is blank.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000190 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0666, TIG0700

---

### CORE-000191

**Description:** Raise an error when ARM is not empty and RFENDTC is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000191 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0529, TIG0622

---

### CORE-000192

**Description:** Raise an error when ARMNRS is not blank and RFENDTC is provided.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000192 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0530, TIG0623

---

### CORE-000193

**Description:** Raise an error when variable MIDSDTC is present and MIDS variable is missing.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000193 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0503

---

### CORE-000195

**Description:** Raise an error when --SCAT = --DECOD.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000195 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0338, TIG0503

---

### CORE-000196

**Description:** Raise an error when --CAT = --DECOD.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000196 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0337, TIG0502

---

### CORE-000197

**Description:** Raise an error when --CAT = --BODSYS.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000197 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0339, TIG0504

---

### CORE-000198

**Description:** Raise an error when --SCAT = --BODSYS.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000198 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0340, TIG0505

---

### CORE-000199

**Description:** Raise an error when length of --TEST > 40.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000199 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | Excl:IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.5.3.1

**Rule Identifiers:** CG0406, SEND64, TIG0563

---

### CORE-000200

**Description:** Verify that --ORRES is not missing when either --STAT is null or --DRVFL not equal to 'Y'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000200 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.5.1.1

**Rule Identifiers:** CG0348, TIG0513

---

### CORE-000201

**Description:** Trigger error when domain is not an AP-- domain and USUBJID is not present in DM.USUBJID

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000201 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.3

**Rule Identifiers:** CG0029, SEND109, TIG0046, TIG0311

---

### CORE-000202

**Description:** When IDVAR is populated with a --SEQ value, RELTYPE must be null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000202 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | RELREC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG 3.4, Section 8.2.1

**Rule Identifiers:** CG0419, TIG0574

---

### CORE-000203

**Description:** Trigger error when the combination of IDVAR, IDVARVAL, and QNAM is not unique per parent subject record

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000203 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 8.4

**Rule Identifiers:** CG0411, TIG0567

---

### CORE-000204

**Description:** When VISITNUM is present in TV.VISITNUM, SV.VISITNUM must be unique within subject

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000204 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.5

**Rule Identifiers:** CG0410, TIG0566

---

### CORE-000206

**Description:** When IDVAR is populated, IDVARVAL must equal a value of the variable referenced by IDVAR within the domain referenced by RDOMAIN

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000206 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, RELATIONSHIP |
| Domains | CO, SUPP--, RELREC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0371, SEND121, TIG0058, TIG0535

---

### CORE-000207

**Description:** Trigger error when --STDTC is present in a Findings general observation class

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000207 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.8

**Rule Identifiers:** CG0467, SEND78, TIG0276, TIG0597

---

### CORE-000208

**Description:** Trigger error when study does not use multi-stage arm assignments and ACTARMCD ^= null, and ACTARMCD is not in TA.ARMCD

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000208 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0512, TIG0607

---

### CORE-000209

**Description:** When study does not use multi-stage arm assignments and ACTARM is populated, ACTARM must be present in TA.ARM. This rule has been executed to identify all cases when ACTARM is not present in TA.ARM and therefore acknowledges that false positives may be recorded when multi-stage arm assignments are in use.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000209 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0514, TIG0609

---

### CORE-000210

**Description:** When study does not use multi-stage arm assignments and ARMCD is populated, ARMCD must be present in TA.ARMCD. This rule has been executed to identify all cases when ARMCD is not present in TA.ARMCD and therefore acknowledges that false positives may be recorded when multi-stage arm assignments are in use.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000210 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0516, TIG0611

---

### CORE-000211

**Description:** SUPPDM must not include population flags.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000211 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0528

---

### CORE-000212

**Description:** When subject has more than one record per Epoch with DSCAT = DISPOSITION EVENT, DSSCAT must be present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000212 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.3

**Rule Identifiers:** CG0535, TIG0628

---

### CORE-000213

**Description:** Trigger error when there is more than one record per subject per DSSCAT per EPOCH

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000213 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.3

**Rule Identifiers:** CG0536, TIG0629

---

### CORE-000214

**Description:** Trigger error when subject has more than one record per Epoch with DSCAT = 'DISPOSITION EVENT' and more than one record where DSSCAT = 'STUDY PARTICIPATION'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000214 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.3

**Rule Identifiers:** CG0538, TIG0631

---

### CORE-000215

**Description:** Trigger error when subject has more than one record per Epoch with DSCAT = 'DISPOSITION EVENT' and more than one record where DSSCAT = 'STUDY TREATMENT'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000215 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.3

**Rule Identifiers:** CG0539

---

### CORE-000216

**Description:** MIDSTYPE must be unique within subject when MIDSTYPE = TM.MIDSTYPE and TM.TMRPT = 'N'

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000216 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.4

**Rule Identifiers:** CG0545

---

### CORE-000217

**Description:** Raise an error when ECDOSE is empty, ECOCCUR is not equal to 'N', ECSTAT is empty and ECDOSTXT is empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000217 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | EC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.1.3.2

**Rule Identifiers:** CG0462, TIG0592

---

### CORE-000218

**Description:** Raise an error when IDVAR is empty and IDVARVAL is not empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000218 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, RELATIONSHIP |
| Domains | CO, SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0465, SEND118, TIG0055, TIG0595

---

### CORE-000219

**Description:** Raise an error when --SCAT = domain dataset label.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000219 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0350, SEND44, TIG0515

---

### CORE-000220

**Description:** Part A - Raise an error when --TESTCD > 8 chars or contains more than only letters, numbers and underscores, or starts with a number.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000220 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.1

**Rule Identifiers:** CG0372, TIG0536

---

### CORE-000221

**Description:** Raise an error when QNAM > 8 chars or contains more than only letters in uppercase, numbers and underscores, or starts with a number.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000221 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0417, TIG0572

---

### CORE-000222

**Description:** Raise an error when QLABEL > 40 chars.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000222 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0416, TIG0571

---

### CORE-000223

**Description:** Raise an error when ACTARMCD is empty and ARMNRS is not completed.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000223 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0513, TIG0608

---

### CORE-000224

**Description:** Raise an error when ACTARM is empty and ARMNRS is not completed.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000224 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0515, TIG0610

---

### CORE-000225

**Description:** Raise an error when --REASND is provided and --STAT is not equal to "NOT DONE".

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000225 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:EX, Excl:AE, Excl:DS, Excl:DV, Excl:IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0094, TIG0359

---

### CORE-000226

**Description:** Verifying presence of SEND variable --DTHREL

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000226 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0352

---

### CORE-000227

**Description:** Check if IETEST is not in TI.IETEST

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000227 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.4

**Rule Identifiers:** CG0178, TIG0407

---

### CORE-000228

**Description:** Check if IETESTCD is present in TI.IETESTCD

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000228 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | IE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.4

**Rule Identifiers:** CG0179, TIG0408

---

### CORE-000229

**Description:** Verify that When USUBJID is populated, POOLID is not populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000229 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | RELSUB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0361

---

### CORE-000230

**Description:** Verify that when POOLID is populated, USUBJID is not populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000230 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | RELSUB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0362

---

### CORE-000231

**Description:** Verify that when RSUBJID populated, RSUBJID does not equal USUBJID

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000231 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | RELSUB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0363

---

### CORE-000232

**Description:** Verify when RSUBJID is populated it does not equal POOLID

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000232 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | RELSUB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0364

---

### CORE-000233

**Description:** Related Device Identifier (RDEVID) and Related Subject or Pool Identifier (RSUBJID) should not both be populated for the same record.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000233 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0365

---

### CORE-000234

**Description:** Verify that RSUBJID is empty when RDEVID is populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000234 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0366

---

### CORE-000235

**Description:** Verify that RSUBJID=DM.USUBJID when RSUBJID is populated and RSUBJID is not equal to POOLID

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000235 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0367

---

### CORE-000236

**Description:** Raise an issue if MHSTDTC has a non-empty date that later than DM.RFSTDTC

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000236 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | MH |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.6

**Rule Identifiers:** CG0079, TIG0345

---

### CORE-000237

**Description:** Trigger error when --SCAT is not null and --SCAT is equal to --CAT

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000237 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0027, SEND43, TIG0309

---

### CORE-000238

**Description:** Raise an error when EX records are present for subject but RFXENDTC does not equal the latest value of EX.EXSTDTC or EX.EXENDTC

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000238 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0147, TIG0388

---

### CORE-000239

**Description:** Raise an error when EX records are present for subject but RFXSTDTC does not equal the earliest value of EX.EXSTDTC

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000239 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0148, TIG0389

---

### CORE-000240

**Description:** When --OCCUR = 'N', --STRF must not be populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000240 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0420, TIG0575

---

### CORE-000241

**Description:** When --OCCUR = 'N', --ENRF must not be populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000241 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0421, TIG0576

---

### CORE-000242

**Description:** Raise an error when --EXCLFL is present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000242 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0353, TIG0518

---

### CORE-000243

**Description:** Raise an error when --REASEX is present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000243 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0354, TIG0519

---

### CORE-000244

**Description:** Raise an error when --DETECT is present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000244 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0355, TIG0520

---

### CORE-000245

**Description:** Raise an error when --NOMDY is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000245 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0510, CG0635, TIG0605, TIG0676

---

### CORE-000246

**Description:** Raise an error when --NOMLBL is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000246 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.7

**Rule Identifiers:** CG0511, CG0636, TIG0606, TIG0677

---

### CORE-000247

**Description:** Raise an error when --RESLOC is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000247 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0552, CG0626, TIG0640, TIG0667

---

### CORE-000248

**Description:** Time point reference (--TPTREF) should be populated when reference time point (--RFTDTC) is populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000248 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0026, FB3702, SEND63, TIG0265, TIG0308

---

### CORE-000249

**Description:** Verify that visitdy is planned and exists in TV

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000249 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:TV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.5

**Rule Identifiers:** CG0032, TIG0313

---

### CORE-000250

**Description:** Raise an issue if MHENDTC has a non-empty date that is on or after DM.RFSTDTC

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000250 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | MH |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 2.6

**Rule Identifiers:** CG0078, TIG0344

---

### CORE-000251

**Description:** When survival status is completed as 'DEAD' in the SS dataset then a record should be present in the DS dataset where DSDECOD = 'DEATH' for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000251 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | SS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.11

**Rule Identifiers:** CG0067, FB0614

---

### CORE-000252

**Description:** When a record is present in the DS dataset where DSDECOD = 'DEATH' then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000252 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0136, FB0605, TIG0385

---

### CORE-000253

**Description:** When a record is present in the AE dataset where AESDTH = 'Y' then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000253 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0135, FB0604, TIG0384

---

### CORE-000254

**Description:** When a record is present in the AE dataset where AEOUT = 'FATAL' then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the  corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000254 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0134, FB0603, TIG0383

---

### CORE-000256

**Description:** Raise an error when ETCD = 'UNPLAN', SEUPDES = null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000256 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3

**Rule Identifiers:** CG0210, SEND125.1, TIG0062, TIG0429

---

### CORE-000258

**Description:** Raise an error when --STRTPT is present in dataset and --STTPT is not present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000258 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0062, TIG0336

---

### CORE-000259

**Description:** Date/time of collection of subject status in SS dataset where survival status (SSSTRESC) = 'DEAD' should be equal to or after the Date/Time of Death (DTHDTC) in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000259 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | SS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.3.11

**Rule Identifiers:** CG0171, FB0613

---

### CORE-000260

**Description:** Raise an error when --PRESP does not equal 'Y' or is not empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000260 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS, INTERVENTIONS |
| Domains | Excl:DS, Excl:DV, Excl:EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0085, TIG0350

---

### CORE-000261

**Description:** Raise an error when --STTPT is present in dataset and --STRTPT is not present in dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000261 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0060, TIG0334

---

### CORE-000262

**Description:** Raise an error when RFSTDTC in DM is empty and --STRF is not empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000262 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0226, TIG0439

---

### CORE-000264

**Description:** Raise an error if primary SOC is used for analysis and -BODSYS is not equal to -SOC

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000264 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | Excl:DS, Excl:DV, Excl:HO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.2.1

**Rule Identifiers:** CG0039

---

### CORE-000266

**Description:** Select record where AESCAN and AESCONG and AESDISAB and AESDTH and AESHOSP and AESLIFE and AESOD and AESMIE ^= "Y" However, AESER ^= "N"

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000266 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.1

**Rule Identifiers:** CG0042, TIG0321

---

### CORE-000267

**Description:** Raise an error when --DECOD is not populated but --PTCD is populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000267 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0050, TIG0328

---

### CORE-000268

**Description:** --DECOD and --PTCD should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000268 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0049, FB0909, TIG0327

---

### CORE-000269

**Description:** Verify when VISIT is not null in any given domain and VISIT is planned (SVPRESP = 'Y'), then VISIT should be among TV.VISIT.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000269 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:TV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.5

**Rule Identifiers:** CG0031, TIG0312

---

### CORE-000270

**Description:** Verify when VISITNUM is not null in any given domain and VISITNUM is planned (SVPRESP = 'Y'), then VISIT should be among TV.VISITNUM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000270 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:TV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 4.4.5

**Rule Identifiers:** CG0033, TIG0314

---

### CORE-000271

**Description:** Raise an error when EPOCH is not in TA.EPOCH

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000271 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.2.3

**Rule Identifiers:** CG0009, TIG0294

---

### CORE-000272

**Description:** Raise an error when --CAT = DOMAIN.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000272 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0336, SEND46, TIG0501

---

### CORE-000289

**Description:** Raise an error when LBORRES is not a continuous measurement and LBORNRHI is not empty. The interpretation of the guidance is that LBORRES is populated (not null).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000289 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | LB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.5.6

**Rule Identifiers:** CG0181, TIG0410

---

### CORE-000290

**Description:** Raise an error when LBORRES ^= continuous measurement and LBORNRLO is not empty. The interpretation of the guidance is that LBORRES is populated (not null).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000290 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | LB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.5.6

**Rule Identifiers:** CG0180, TIG0409

---

### CORE-000291

**Description:** When EC exists, EXVAMT should not be used.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000291 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.1.3.1

**Rule Identifiers:** CG0105, TIG0370

---

### CORE-000292

**Description:** When EC exists, EXVAMTU should not be used.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000292 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | EX |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.1.3.1

**Rule Identifiers:** CG0107

---

### CORE-000293

**Description:** Trigger error if length of dataset name is greater than 8

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000293 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 8.4.2

**Rule Identifiers:** CG0205, TIG0425

---

### CORE-000294

**Description:** Trigger error when TSPARMCD = 'AGEMAX' and TSVAL ^= null and TSVAL does not conform to ISO 8601

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000294 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2.1

**Rule Identifiers:** CG0270, TIG0471

---

### CORE-000295

**Description:** When --STREFC is populated, --STREFN should also be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000295 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0550, TIG0638

---

### CORE-000296

**Description:** When ACTARMCD is populated, disposition data for subject must be present

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000296 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.3

**Rule Identifiers:** CG0540, TIG0633

---

### CORE-000297

**Description:** Raise an error when variable MIDS is present in any of the dataset and TM dataset is missing.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000297 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0501

---

### CORE-000298

**Description:** Raise an error when LBORRES is not a continuous measurement and LBSTNRLO is not empty. The interpretation of the guidance is that LBORRES is populated (not null).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000298 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | LB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.5.6

**Rule Identifiers:** CG0182, TIG0411

---

### CORE-000299

**Description:** Raise an error when LBORRES is not a continuous measurement and LBSTNRHI is not empty. The interpretation of the guidance is that LBORRES is populated (not null).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000299 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | LB |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 6.3.5.6

**Rule Identifiers:** CG0183, TIG0412

---

### CORE-000302

**Description:** QNAM and QLABEL should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000302 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB0920

---

### CORE-000303

**Description:** --TESTCD and --TEST should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000303 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS, FINDINGS ABOUT |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB0901

---

### CORE-000305

**Description:** Collected Duration (--DUR) value should not be negative.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000305 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4005

---

### CORE-000308

**Description:** Dose (--DOSE) value should not be negative

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000308 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4002

---

### CORE-000310

**Description:** Age (AGE) value should not be negative

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000310 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4001

---

### CORE-000318

**Description:** ARMCD and ARM should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000318 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN, SPECIAL PURPOSE |
| Domains | TA, TV, DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB0902

---

### CORE-000321

**Description:** When Date/Time of Collection (--DTC) is present in the dataset, then the Study Day of Visit/Collection/Exam (--DY) should also present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000321 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3201

---

### CORE-000324

**Description:** Trigger error when --ENRTPT is not in ('BEFORE', 'COINCIDENT', 'ONGOING', 'UNKNOWN')

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000324 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.7

**Rule Identifiers:** CG0234, TIG0443

---

### CORE-000328

**Description:** Study Day of Start (--STDY) variable should be included into dataset, when Start Study Date/Time (--STDTC) variable is present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000328 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, FINDINGS ABOUT, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3202

---

### CORE-000334

**Description:** Raise an error when an expected variable is not present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000334 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0016, SEND13, TIG0065, TIG0301

---

### CORE-000337

**Description:** Raise an error when multiple races are collected in SUPPDM but RACE in DM does not equal "MULTIPLE".

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000337 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SUPPDM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0140, TIG0386

---

### CORE-000351

**Description:** USUBJID must be unique across all studies

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000351 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0151, SEND37, TIG0255, TIG0392

---

### CORE-000352

**Description:** Trigger error where SEENDTC is not equal to SESTDTC of the next element.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000352 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3

**Rule Identifiers:** CG0207, SEND126, TIG0427

---

### CORE-000354

**Description:** Raise an error when the date portion of --DTC is an incomplete date or the date portion of DM.RFSTDTC is an incomplete date, but --DY is not empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000354 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 4.4.4

**Rule Identifiers:** CG0007, TIG0292

---

### CORE-000355

**Description:** Part A: Raise an error when a Required variable is not present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000355 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.5

**Rule Identifiers:** CG0014, SEND12, TIG0057, TIG0299

---

### CORE-000356

**Description:** Part B: Raise an error when a Required variable is null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000356 |
| Version | 1 |
| Rule Type | Value Check with Dataset Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.5

**Rule Identifiers:** CG0014, SEND12, TIG0057, TIG0299

---

### CORE-000357

**Description:** When a supplemental qualifier dataset is associated with a split dataset, the dataset name length must not be greater than 8 characters.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000357 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.7

**Rule Identifiers:** CG0018, TIG0303

---

### CORE-000358

**Description:** Verify that when --LNKGRP is present in one domain it is also present in another domain.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000358 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 4.2.6

**Rule Identifiers:** CG0022, TIG0306

---

### CORE-000361

**Description:** When VISITNUM is present in TV, VISIT and VISITNUM should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000361 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.5

**Rule Identifiers:** CG0035, FB0919, TIG0316

---

### CORE-000362

**Description:** Raise an error when primary SOC is used for analysis and SOC Code is not equal to Body System Code

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000362 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | Excl:DS, Excl:DV, Excl:HO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section Events

**Rule Identifiers:** CG0037

---

### CORE-000363

**Description:** When there are multiple informed consents obtained and DSDECOD = 'INFORMED CONSENT OBTAINED', the earliest DSSTDTC for each subject should be equal to DM.RFICDTC.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000363 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0068, TIG0338

---

### CORE-000365

**Description:** MHCAT should not group all records.  If no smaller categorization can be applied, then it is not necessary to include or populate this variable.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000365 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | MH |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.6

**Rule Identifiers:** CG0077

---

### CORE-000370

**Description:** DM.RFICDTC must equal the earliest DS.DSSTDTC when DSTERM indicates informed consent obtained.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000370 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 5.2

**Rule Identifiers:** CG0143, TIG0387

---

### CORE-000374

**Description:** Trigger error when there is more than one record per subject per EPOCH

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000374 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.3

**Rule Identifiers:** CG0537, TIG0630

---

### CORE-000376

**Description:** Raise an error when the two first characters of a prefixed variable within a custom domain do not match the DOMAIN value.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000376 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS, INTERVENTIONS, EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.2

**Rule Identifiers:** CG0349, TIG0514

---

### CORE-000384

**Description:** RELREC.RDOMAIN must represent a dataset that is present in the study.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000384 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | RELREC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0374, TIG0538

---

### CORE-000457

**Description:** Trigger error when SUPP--.RDOMAIN does not represent a dataset present in the study

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000457 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0373, TIG0537

---

### CORE-000484

**Description:** Within a subject, each unique RELID should be present on multiple RELREC records

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000484 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | RELREC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 8.2.1

**Rule Identifiers:** CG0200, TIG0421

---

### CORE-000502

**Description:** Raise an error when RDOMAIN is populated but the referenced RDOMAIN doesn't exist as a dataset in the present study

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000502 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | CO, RELREC, SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0369, TIG0533

---

### CORE-000505

**Description:** Study Start Date (SSTDTC) value in the TS dataset should be populated in ISO 8601 format.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000505 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** CG0285, TRC1734c

---

### CORE-000510

**Description:** Split datasets (excluding supplemental qualifiers) is expected to have a have a length of 3 or 4 characters

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000510 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:SUPP--, Excl:AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.7

**Rule Identifiers:** CG0017

---

### CORE-000517

**Description:** Total Daily Dose (--DOSTOT) value should not be negative.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000517 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4003

---

### CORE-000518

**Description:** Value for Treatment Vehicle Amount (--VAMT) should not be negative.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000518 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4004

---

### CORE-000522

**Description:** Category for Disposition Event (DSCAT) should be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000522 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB1401

---

### CORE-000527

**Description:** Trigger error where element is not the last element and SEENDTC is null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000527 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3

**Rule Identifiers:** CG0209, TIG0428

---

### CORE-000529

**Description:** Raise an error when --DY is not calculated as per the study day algorithm as a non-zero integer value when the date portion of --DTC is complete and the date portion of DM.RFSTDTC is a complete date AND --DY is not empty

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000529 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 4.4.4

**Rule Identifiers:** CG0006, FB1603, SEND73, SEND74, TIG0274, TIG0275, TIG0291

---

### CORE-000534

**Description:** TAETORD must be an integer

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000534 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TA |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.2.1

**Rule Identifiers:** CG0248, SEND221, TIG0148, TIG0452

---

### CORE-000535

**Description:** Trigger error when the order of --SEQ is not chronological (based on --STDTC) within USUBJID

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000535 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SM, SE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.3|5.4

**Rule Identifiers:** CG0620, CG0662, SEND130, SEND130.1, TIG0066, TIG0661

---

### CORE-000538

**Description:** Trigger error when RDOMAIN does not match characters 5 and 6 of the Supplementary dataset name

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000538 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.7

**Rule Identifiers:** CG0334, TIG0500

---

### CORE-000539

**Description:** Raise an error when split domains (i.e three/four-letter domain name) do not have a two-letter parent domain present in the study.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000539 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:RELREC, Excl:RELSUB, Excl:SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.7

**Rule Identifiers:** CG0332, TIG0498

---

### CORE-000540

**Description:** Raise an error when parent domain referenced in Findings About dataset name is not present in the study.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000540 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.7

**Rule Identifiers:** CG0333, TIG0499

---

### CORE-000541

**Description:** Part B - Raise an error when IETESTCD > 8 chars or contains more than only letters, numbers and underscores, or starts with a number.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000541 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.1

**Rule Identifiers:** CG0372, TIG0536

---

### CORE-000542

**Description:** Raise an error when --STRESC is populated with a numeric value, but --STRESN is not populated, or --STRESN is not equal to --STRESC.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000542 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** FB3102, SEND88, TIG0280

---

### CORE-000544

**Description:** Excluding TS.TSSEQ, raise an error when --SEQ is not a unique number per USUBJID per domain, or not a unique number per POOLID per domain, including when the domain is split into multiple files.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000544 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0028

---

### CORE-000550

**Description:** Raise an error when a variable is not an allowed variable for an Observation Class

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000550 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Underreporting |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.5

**Rule Identifiers:** CG0013, CG0351, TIG0298

---

### CORE-000552

**Description:** Study day must be calculated using the algorithm detailed in the SDTMIG.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000552 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.4

**Rule Identifiers:** CG0220, TIG0434

---

### CORE-000553

**Description:** Study day must be calculated using the algorithm detailed in the SDTMIG.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000553 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.4

**Rule Identifiers:** CG0222, TIG0436

---

### CORE-000571

**Description:** Verify that when --LNKID is present in one domain it is also present in another domain.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000571 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.6

**Rule Identifiers:** CG0024, TIG0307

---

### CORE-000572

**Description:** Trigger error when --ENRTPT is not in ('BEFORE', 'COINCIDENT', 'ONGOING', 'AFTER', 'UNKNOWN')

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000572 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.7

**Rule Identifiers:** CG0235, TIG0444

---

### CORE-000575

**Description:** There must be at least one timing variable for any of the domains based on the three general observation classes

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000575 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Partially Executable - Possible Underreporting |
| Classes | FINDINGS, INTERVENTIONS, EVENTS |
| Domains | Excl:IE, Excl:SC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4

**Rule Identifiers:** CG0219, SEND65, TIG0266, TIG0433

---

### CORE-000579

**Description:** Raise an error when a dataset has no records.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000579 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 3.2

**Rule Identifiers:** CG0408

---

### CORE-000580

**Description:** The combination of TESTRL, TEENRL, and TEDUR must be unique for each ETCD.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000580 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.2.2

**Rule Identifiers:** CG0325, TIG0495

---

### CORE-000581

**Description:** DM dataset should be present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000581 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0368, TIG0532, TRC1736a, TRC1736c

---

### CORE-000582

**Description:** Raise an error when TSVAL(n+1) ^= null and TSVALn = null

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000582 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0262

---

### CORE-000594

**Description:** Raise an error when a variable label is not in title case

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000594 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.6

**Rule Identifiers:** CG0359, SEND29, TIG0205, TIG0524

---

### CORE-000597

**Description:** When SUPPAE.QNAM=AESOSP is present, a record with AESMIE=Y should be present

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000597 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.2.1

**Rule Identifiers:** CG0043, TIG0322

---

### CORE-000598

**Description:** The dataset name must begin with the DOMAIN value.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000598 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | Excl:SUPP--, Excl:RELREC, Excl:RELREF, Excl:POOLDEF |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**Rule Identifiers:** CG0413, SEND1, TIG0037, TIG0568

---

### CORE-000616

**Description:** When Planned Start of Assessment Interval (--STINT) is populated, Time Point Reference (--TPTREF) should also be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000616 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4403

---

### CORE-000642

**Description:** When Planned End of Assessment Interval (--ENINT) is populated, Time Point Reference (--TPTREF) should also be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000642 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4404

---

### CORE-000643

**Description:** Records with a baseline flag (--BLFL) have a non missing value in character standard result variable (--STRESC)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000643 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB2601

---

### CORE-000655

**Description:** Values in ARMCD and ACTARMCD should match

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000655 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2501

---

### CORE-000656

**Description:** Values in ARM and ACTARM should match

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000656 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2502

---

### CORE-000657

**Description:** When Adverse Event Outcome (AEOUT) is populated with 'NOT RECOVERED/NOT RESOLVED', then the End Date/Time of Adverse Event (AEENDTC) should not be populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000657 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3409

---

### CORE-000658

**Description:** Date/Time of Informed Consent (RFICDTC) should be prior or equal to the Date/Time of First Study Treatment (RFXSTDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000658 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3412

---

### CORE-000659

**Description:** When Adverse Event Outcome (AEOUT) is populated with RECOVERED/RESOLVED, then the End Date/Time of Adverse Event (AEENDTC) should be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000659 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3410

---

### CORE-000672

**Description:** Normal Range Upper Limit-Standard Units (--STNRHI) should be greater than the Normal Range Lower Limit-Standard Units (--STNRLO).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000672 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3901

---

### CORE-000679

**Description:** When Death flag (DTHFL) in the DM dataset is populated as 'Y' then a record should be present in the DS dataset where DSDECOD = 'DEATH' for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000679 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0608

---

### CORE-000685

**Description:** VISITNUM is present in the dataset and --TPTREF is not present in the dataset then --TPT and --TPTNUM should have a one-to-one relationship per unique value of VISITNUM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000685 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0571, FB0923

---

### CORE-000686

**Description:** VISITNUM not present in dataset and --TPTREF present in dataset then --TPT and --TPTNUM should have a one-to-one relationship per unique values of --TPTREF.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000686 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0572, FB0924

---

### CORE-000689

**Description:** VISITNUM and --TPTREF are present then --TPT and --TPTNUM should have a one-to-one relationship per unique combination of VISITNUM and --TPTREF values.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000689 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0573, FB0925

---

### CORE-000699

**Description:** Standard units should be consistent within the same assessment (having the same --TESTCD, --CAT, --SCAT, --SPEC, --METHOD values)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000699 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS, FINDINGS ABOUT |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3001

---

### CORE-000700

**Description:** When Study Day of Visit/Collection/Exam (--DY) is present in the dataset, then the Date/Time of Collection (--DTC) should also be present in the dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000700 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, FINDINGS ABOUT, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3204

---

### CORE-000701

**Description:** EPOCH should be populated for clinical subject-level observations.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000701 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | INTERVENTIONS, FINDINGS, EVENTS |
| Domains | Excl:DD, Excl:PP, Excl:MH |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2201

---

### CORE-000705

**Description:** When Death flag (DTHFL) in the DM dataset is populated as 'Y' then Date/Time of Death (DTHDTC)should be populated in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000705 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0610

---

### CORE-000706

**Description:** --LLTCD and --LLT should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000706 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0906

---

### CORE-000707

**Description:** Study Day of Visit/Collection/Exam (--DY) should be less than or equal to Study Day of End of Observation (--ENDY).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000707 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3208

---

### CORE-000708

**Description:** When Study Day of End of Observation (--ENDY) is present in the dataset, then the End Date/Time of Observation (--ENDTC) should also be present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000708 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3207

---

### CORE-000709

**Description:** When a record is present in the CE dataset where CETERM or CEDECOD  = 'DEATH' then death flag (DTHFL) should be populated as 'Y' in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000709 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0607

---

### CORE-000710

**Description:** Trigger error when --STRTPT is not in ('BEFORE', 'COINCIDENT', 'AFTER', 'UNKNOWN') and --STTPT is prior to the date of collection or assessment.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000710 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.7

**Rule Identifiers:** CG0233, TIG0442

---

### CORE-000711

**Description:** Subject Reference Start Date/Time (RFSTDTC) should be prior or equal to the Subject Reference End Date/Time (RFENDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000711 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3404

---

### CORE-000712

**Description:** Part A - Raise and error when IDVAR in SUPP-- is not populated with a valid variable from the dataset referenced in RDOMAIN.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000712 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0370, TIG0534

---

### CORE-000713

**Description:** Date/Time of Informed Consent (RFICDTC) should be prior or equal to the Subject Reference Start Date/Time (RFSTDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000713 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3411

---

### CORE-000714

**Description:** Date/Time of First Study Treatment (RFXSTDTC) variable value should be prior or equal to the Date/Time of Last Study Treatment (RFXENDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000714 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3408

---

### CORE-000716

**Description:** --SOCCD and --SOC should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000716 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0911

---

### CORE-000717

**Description:** Start Date/Time of Adverse Event (AESTDTC) should be prior or equal to the Start Date/Time of the latest Disposition Event (DSSTDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000717 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3401

---

### CORE-000718

**Description:** Start Date/Time of Observation (--STDTC) should be prior or equal to the End Date/Time of Observation (--ENDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000718 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, SPECIAL PURPOSE |
| Domains | Excl:DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3209

---

### CORE-000719

**Description:** --HLGTCD and --HLGT should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000719 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0908

---

### CORE-000720

**Description:** Date/Time of Collection (--DTC) variable value should be prior or equal to Date/Time of End of Participation (RFPENDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000720 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3406

---

### CORE-000723

**Description:** --HLTCD and --HLT should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000723 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0907

---

### CORE-000726

**Description:** When Ethnicity was collected and it was not mapped as "HISPANIC OR LATINO" or "NOT HISPANIC OR LATINO".

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000726 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SPECIAL PURPOSE, RELATIONSHIP |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB5701

---

### CORE-000728

**Description:** TSVALCD and TSVAL should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000728 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0904

---

### CORE-000729

**Description:** INVID and INVNAM shoumd have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000729 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0912

---

### CORE-000732

**Description:** --STRESC is not populated with a numeric value, but --STRESN is not empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000732 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3103

---

### CORE-000736

**Description:** Either AGE or AGETXT is in the list of TSPARMCDs with TSVAL populated, not both.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000736 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB1116

---

### CORE-000739

**Description:** Trigger error when study includes protocol-specified study treatment and EX is not present

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000739 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.1.3.1

**Rule Identifiers:** CG0407

---

### CORE-000741

**Description:** The set  ('INTMODEL’, 'INTTYPE','PCLASS') is not in the list of TSPARAMCD with TSVAL populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000741 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB1111

---

### CORE-000742

**Description:** TSVALNF is not equal to NA when TSPARAMCD=INDIC

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000742 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB1110

---

### CORE-000743

**Description:** IETESTCD and IETEST should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000743 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TI |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0918

---

### CORE-000744

**Description:** Raise an error when a related record is present in the parent domain dataset but FAOBJ is not equal to the --TERM, --TRT or --DECOD of the parent domain.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000744 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS ABOUT |
| Domains | FA |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 6.4.3

**Rule Identifiers:** CG0174, TIG0403

---

### CORE-000745

**Description:** OIPARMCD and OIPARM should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000745 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | STUDY REFERENCE |
| Domains | OI |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0915

---

### CORE-000746

**Description:** When ethnicity term 'Spanish origin' is collected (SUPPDM.CETHNIC), it should be mapped to Ethnicity (ETHNIC) 'HISPANIC OR LATINO' in the DM dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000746 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, RELATIONSHIP |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB5801

---

### CORE-000747

**Description:** When Race Term 'Haitian' or 'Negro' (SUPPDM.CRACE) is collected, it should be mapped to Race (RACE) 'BLACK OR AFRICAN AMERICAN' in the DM dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000747 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE, RELATIONSHIP |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB6001

---

### CORE-000757

**Description:** When findings are collected about an intervention, and the interventions parent record exists and --DECOD = null, FAOBJ should be equal to --TRT.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000757 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.7.4

**Rule Identifiers:** CG0602, TIG0656

---

### CORE-000758

**Description:** Raise an error when milestone associated with RFSTDTC is start of treatment and ARMNRS is not null and different from 'UNPLANNED TREATMENT', but RFSTDTC is not null.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000758 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0534, TIG0627

---

### CORE-000760

**Description:** Date/Time of First Challenge Agent Admin (RFCSTDTC) should be prior or equal to the Date/Time of Last Challenge Agent Admin (RFCENDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000760 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.4, SENDIG-AR 1.0

**Rule Identifiers:** FB3413

---

### CORE-000761

**Description:** Trigger error when TSVCDREF = 'CDISC' and TSVCDVER is not a valid published version (date)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000761 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0289

---

### CORE-000763

**Description:** Trigger error when --STRTPT is not in ('BEFORE', 'COINCIDENT', 'UNKNOWN')

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000763 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.4.7

**Rule Identifiers:** CG0232, TIG0441

---

### CORE-000765

**Description:** The submitted dataset is larger than 5 GB

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000765 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB2401

---

### CORE-000766

**Description:** When findings are collected, and the Events parent record exists and --DECOD is null, FAOBJ should not be equal to --TERM

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000766 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.7.4

**Rule Identifiers:** CG0601

---

### CORE-000767

**Description:** When findings are collected, and the parent record exists and --DECOD is not null, FAOBJ should not be equal to --DECOD

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000767 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.2.7.4

**Rule Identifiers:** CG0603

---

### CORE-000774

**Description:** When a test is not done (--STAT = NOT DONE), the Reason Not Done (--REASND) should be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000774 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Overreporting |
| Classes | FINDINGS, FINDINGS ABOUT |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4202

---

### CORE-000776

**Description:** When End Date/Time of Observation (--ENDTC) is present in the dataset, then the Study Day of End of Observation (--ENDY) should also be present in the dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000776 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, FINDINGS ABOUT, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3203

---

### CORE-000777

**Description:** Raise an error when variable RELMIDS is present in any of the dataset and TM dataset is missing in a study.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000777 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** CG0502

---

### CORE-000778

**Description:** When an Associated Persons non-supplemental qualifier dataset is associated with a split dataset, the dataset name length must be greater than 4 and less than, or equal to, 6.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000778 |
| Version | 1 |
| Rule Type | Dataset Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | AP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.7

**Rule Identifiers:** CG0650

---

### CORE-000779

**Description:** TDSTOFF must be equal to 0 or a positive value in ISO 8601 Duration format

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000779 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TD |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.3.2

**Rule Identifiers:** CG0376

---

### CORE-000783

**Description:** Raise an error when SUPP--.QNAM is present in dataset, but value of SUPP--.QNAM is equal to a variable name defined in the corresponding SDTM version.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000783 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | SUPP-- |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.5

**Rule Identifiers:** CG0314, SEND274, SEND274.1

---

### CORE-000784

**Description:** Raise an error when variable TAETORD values don't match between Subject Visits (SV) and Subject Elements (SE) datasets.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000784 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | SV |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.5

**Rule Identifiers:** CG0217, TIG0431

---

### CORE-000785

**Description:** Part B - Raise an error when IESCAT is not empty, but IECAT is empty.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000785 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TI |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0429, TIG0583

---

### CORE-000786

**Description:** Part B - Raise an error when IESCAT exists in a dataset, but IECAT does not exist.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000786 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TI |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0430, TIG0584

---

### CORE-000787

**Description:** Raise an error when TSVALNF is empty and TSVAL is populated with values or synonyms of values in the ISO 21090 null flavor codelist (or other terms that can be represented as null flavors)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000787 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2.1

**Rule Identifiers:** CG0291

---

### CORE-000791

**Description:** ACTARMCD and ACTARM should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000791 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB0903

---

### CORE-000792

**Description:** --CLASCD and --CLAS should have a one-to-one relationship.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000792 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS |
| Domains | AG, CM, SU |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0905

---

### CORE-000793

**Description:** Collection study day (--DY) should be present when date/time of collection (--DTC) is populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000793 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB1601

---

### CORE-000841

**Description:** End Date/Time of Adverse Event (AEENDTC) in AE dataset of Adverse Event where AEOUT = 'FATAL' should be equal to the Date/Time of Death (DTHDTC) in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000841 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0612

---

### CORE-000844

**Description:** Part A - Raise an error when RACE in DM equals 'MULTIPLE' and SUPPDM dataset is not present.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000844 |
| Version | 1 |
| Rule Type | Domain Presence Check |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0531

---

### CORE-000845

**Description:** Part B -  Raise an error when RACE in DM equals 'MULTIPLE' but no records are present in SUPPDM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000845 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0531

---

### CORE-000846

**Description:** Part C - Raise an error when RACE in DM equals 'MULTIPLE' but no multiple RACE records are present in SUPPDM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000846 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 5.2

**Rule Identifiers:** CG0531

---

### CORE-000852

**Description:** Raise an error when variables are not in the specified order

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000852 |
| Version | 1 |
| Rule Type | Variable Metadata Check |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** IG v3.4, Section 4.1.4

**Rule Identifiers:** CG0330, CG0664, SEND48, TIG0698

---

### CORE-000853

**Description:** Collection study day (--DY) should be populated when date/time of collection (--DTC) is populated

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000853 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB1602

---

### CORE-000862

**Description:** When Study Day of Start of Observation (--STDY) is present in the dataset, then the Start Date/Time of Observation (--STDTC) is also present in the dataset.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000862 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, FINDINGS ABOUT, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3205

---

### CORE-000863

**Description:** When --STRESC is populated with a numeric value, --STRESN should be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000863 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3101

---

### CORE-000864

**Description:** When Study Day of End of Observation (--ENDY) is present in the dataset, then the End Date/Time of Observation (--ENDTC) is also present in the dataset

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000864 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Dataset |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, FINDINGS ABOUT, SPECIAL PURPOSE |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3206

---

### CORE-000865

**Description:** When Planned Elapsed Time from Time Point Ref (--ELTM) is populated, Time Point Reference (--TPTREF) should also be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000865 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS, EVENTS, INTERVENTIONS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4405

---

### CORE-000866

**Description:** Date/Time of Collection (--DTC) should be prior or equal to the End Date/Time of Observation (--ENDTC)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000866 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3210

---

### CORE-000867

**Description:** Text variable in submitted dataset should not contain leading spaces ' '.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000867 |
| Version | 1 |
| Rule Type | Value Check with Variable Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB1501

---

### CORE-000880

**Description:** Planned Duration (--PDUR) value should not be negative.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000880 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | BS, CP, GF, IS, LB, MB, MS, MI, PC, PP |

**Applicable Standards:** SDTMIG 3.4

**Rule Identifiers:** FB4006

---

### CORE-000885

**Description:** All subjects in the Demographics domain who are participating in a study that includes an interventional product must have at least one corresponding record in the Exposure domain, except for subjects who failed screening, were not fully assigned to an Arm, or did not receive study treatment.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000885 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2302

---

### CORE-000886

**Description:** All subjects that have no record in the Exposure domain who are participating in a study that includes an interventional product should have ARMNRS populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000886 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2306

---

### CORE-000889

**Description:** Normal Range Upper Limit-Original Units (--ORNRHI) is greater than the Normal Range Lower Limit-Original Units (--ORNRLO).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000889 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable - Possible Underreporting |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB3902

---

### CORE-000890

**Description:** Text variable in submitted dataset should not contain  '.' as an entire value.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000890 |
| Version | 1 |
| Rule Type | Value Check with Variable Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB1502

---

### CORE-000892

**Description:** When Eed timepoint (--ENDTC, --ENRF or --ENRTPT) is populated, related start timepoint (--STDTC, --STRF or --STRTPT should also be populated.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000892 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS |
| Domains | Excl:NONE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB4301

---

### CORE-000901

**Description:** The values of PPCAT and PCTEST do not match at the same reference timepoint

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000901 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Partially Executable |
| Classes | FINDINGS |
| Domains | PP |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB6901

---

### CORE-000913

**Description:** Disposition date time (DSSTDTC) for DEATH record in the DS dataset should be equal to the Date of Death (DTHDTC) in the DM dataset for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000913 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0611

---

### CORE-000914

**Description:** There should be only one record with a baseline flag and non missing character standard result value having the same --TESTCD, --CAT, --SCAT, --SPEC, --METHOD values.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000914 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-AR 1.0, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0

**Rule Identifiers:** FB2603

---

### CORE-000915

**Description:** There should be only one record with a last observation before exposure flag and non missing character standard result value having the same --TESTCD, --CAT, --SCAT, --SPEC, --METHOD values.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000915 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2604

---

### CORE-000916

**Description:** Part B - Raise and error when IDVAR in RELREC is not populated with a valid variable from the dataset referenced in RDOMAIN.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000916 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | RELATIONSHIP |
| Domains | RELREC |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0370, TIG0534

---

### CORE-000927

**Description:** All subjects in the Exposure domain who are participating in a study that includes an interventional product must be assigned to an ARM.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000927 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB2304

---

### CORE-000929

**Description:** Raise and error when the DOMAIN Code is not a valid Domain Code published by CDISC.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000929 |
| Version | 1 |
| Rule Type | Define Item Metadata Check against Library Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, SENDIG 3.0, SENDIG 3.1, SENDIG 3.1.1, SENDIG-DART 1.1, SENDIG-DART 1.2, SENDIG-GENETOX 1.0, TIG 1.0

**SDTMIG 3.4 Reference:** SDTMIG v3.4, Section 3.2.2

**Rule Identifiers:** CG0001, SEND16, TIG0090, TIG0289

---

### CORE-000952

**Description:** End Date/Time (--ENDTC) variable value should be prior or equal to the Date/Time of End of Participation (RFPENDTC).

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000952 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | INTERVENTIONS, EVENTS, FINDINGS, FINDINGS ABOUT, SPECIAL PURPOSE |
| Domains | Excl:DM, Excl:DS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB3407

---

### CORE-000953

**Description:** Part C - Raise and error when IDVAR in CO is not populated with a valid variable from the dataset referenced in RDOMAIN.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-000953 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | CO |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0370, TIG0534

---

### CORE-001034

**Description:** Raise an error when REPNUM is in the dataset and there are multiple records for a subject for a test within the timeframe identified by the timing variables on the record, and --REPNUM is null or not unique per subject per test per timing variables.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-001034 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | FINDINGS |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.3, SDTMIG 3.4, TIG 1.0

**Rule Identifiers:** CG0562, TIG0648

---

### CORE-001043

**Description:** Age should be provided for all subjects, except where not collected for screen failures.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-001043 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | SPECIAL PURPOSE |
| Domains | DM |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0501

---

### CORE-001078

**Description:** When Death flag (DTHFL) in the DM dataset is populated as 'Y' then a record should be present in the AE dataset where AEOUT = 'FATAL' and AESDTH = 'Y' for the corresponding subject.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-001078 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Group |
| Executability | Fully Executable |
| Classes | EVENTS |
| Domains | AE |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**Rule Identifiers:** FB0609

---

### CORE-001080

**Description:** Raise an error when TSVCDREF ='CDISC' and TSVALCD is not a valid code in the version identified in TSVCDVER.

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-001080 |
| Version | 1 |
| Rule Type | Record Data |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | TRIAL DESIGN |
| Domains | TS |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 7.4.2

**Rule Identifiers:** CG0288

---

### CORE-001081

**Description:** Raise an error when the metadata attribute of variable role does not match the IG role for domain in IG, or model role (for custom domains)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-001081 |
| Version | 1 |
| Rule Type | Define Item Metadata Check against Library Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 2.1

**Rule Identifiers:** CG0010

---

### CORE-001082

**Description:** Raise an error when the variable type does not match IG Type (for domains in IG) or Model Type (custom domains)

| Attribute | Value |
|-----------|-------|
| Core ID | CORE-001082 |
| Version | 1 |
| Rule Type | Variable Metadata Check against Library Metadata |
| Sensitivity | Record |
| Executability | Fully Executable |
| Classes | ALL |
| Domains | ALL |

**Applicable Standards:** SDTMIG 3.2, SDTMIG 3.3, SDTMIG 3.4

**SDTMIG 3.4 Reference:** IG v3.4, Section 3.2.2

**Rule Identifiers:** CG0012

---
