# SDTMIG 3.4 Non-Extensible Controlled Terminology Validation Rules

Auto-generated documentation from `rules/rules-sdtmig-3-4-ct.json`.

**Total Rules:** 411  
**Codelists Covered:** 41  
**Standard:** SDTMIG v3.4  
**Rule Type:** Record Data (all rules)  

Each rule validates that a variable's values are within the allowed values
of its non-extensible (closed) codelist. The codelist values are resolved
at runtime via the `codelist_terms` Operation, which requires a
`LibraryMetadataProvider` to be configured.

## Rules by Codelist

### ACN — Action Taken with Study Treatment (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000001 | AEACN | AE |

### AESEV — Severity/Intensity Scale for Adverse Events (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000002 | AESEV | AE |

### AGEU — Age Unit (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000003 | AGEU | AG |

### ARMNULRS — Reason Arm and/or Actual Arm is Null (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000004 | ARMNRS | AR |

### COLSTYP — Collected Sample Type (2 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000005 | CPCOLSRT | CP |
| SDTM-CT-000006 | LBCOLSRT | LB |

### DIR — Directionality (13 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000007 | CVDIR | CV |
| SDTM-CT-000008 | ECDIR | EC |
| SDTM-CT-000009 | EXDIR | EX |
| SDTM-CT-000010 | MBDIR | MB |
| SDTM-CT-000011 | MIDIR | MI |
| SDTM-CT-000012 | MKDIR | MK |
| SDTM-CT-000013 | MSDIR | MS |
| SDTM-CT-000014 | NVDIR | NV |
| SDTM-CT-000015 | OEDIR | OE |
| SDTM-CT-000016 | PRDIR | PR |
| SDTM-CT-000017 | REDIR | RE |
| SDTM-CT-000018 | TUDIR | TU |
| SDTM-CT-000019 | URDIR | UR |

### DOMAIN — CDISC Domain Abbreviation (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000020 | RDOMAIN | RD |

### DSCAT — Disposition Event Category (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000021 | DSCAT | DS |

### EPOCH — Epoch (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000022 | EPOCH | EP |

### ETHNIC — Ethnicity (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000023 | ETHNIC | ET |

### EVAL — Evaluator (19 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000024 | COEVAL | CO |
| SDTM-CT-000025 | CVEVAL | CV |
| SDTM-CT-000026 | DDEVAL | DD |
| SDTM-CT-000027 | EGEVAL | EG |
| SDTM-CT-000028 | FAEVAL | FA |
| SDTM-CT-000029 | MIEVAL | MI |
| SDTM-CT-000030 | MKEVAL | MK |
| SDTM-CT-000031 | MSEVAL | MS |
| SDTM-CT-000032 | NVEVAL | NV |
| SDTM-CT-000033 | OEEVAL | OE |
| SDTM-CT-000034 | PEEVAL | PE |
| SDTM-CT-000035 | QEVAL | QE |
| SDTM-CT-000036 | REEVAL | RE |
| SDTM-CT-000037 | RSEVAL | RS |
| SDTM-CT-000038 | SREVAL | SR |
| SDTM-CT-000039 | SSEVAL | SS |
| SDTM-CT-000040 | TREVAL | TR |
| SDTM-CT-000041 | TUEVAL | TU |
| SDTM-CT-000042 | UREVAL | UR |

### FREQ — Frequency (6 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000043 | AGDOSFRQ | AG |
| SDTM-CT-000044 | CMDOSFRQ | CM |
| SDTM-CT-000045 | ECDOSFRQ | EC |
| SDTM-CT-000046 | EXDOSFRQ | EX |
| SDTM-CT-000047 | PRDOSFRQ | PR |
| SDTM-CT-000048 | SUDOSFRQ | SU |

### FRM — Pharmaceutical Dosage Form (7 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000049 | AGDOSFRM | AG |
| SDTM-CT-000050 | CMDOSFRM | CM |
| SDTM-CT-000051 | ECDOSFRM | EC |
| SDTM-CT-000052 | EXDOSFRM | EX |
| SDTM-CT-000053 | MLDOSFRM | ML |
| SDTM-CT-000054 | PRDOSFRM | PR |
| SDTM-CT-000055 | SUDOSFRM | SU |

### IECAT — Inclusion/Exclusion Category (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000056 | IECAT | IE |

### LAT — Laterality (17 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000057 | CVLAT | CV |
| SDTM-CT-000058 | ECLAT | EC |
| SDTM-CT-000059 | EXLAT | EX |
| SDTM-CT-000060 | FALAT | FA |
| SDTM-CT-000061 | MBLAT | MB |
| SDTM-CT-000062 | MILAT | MI |
| SDTM-CT-000063 | MKLAT | MK |
| SDTM-CT-000064 | MSLAT | MS |
| SDTM-CT-000065 | NVLAT | NV |
| SDTM-CT-000066 | OELAT | OE |
| SDTM-CT-000067 | PELAT | PE |
| SDTM-CT-000068 | PRLAT | PR |
| SDTM-CT-000069 | RELAT | RE |
| SDTM-CT-000070 | SRLAT | SR |
| SDTM-CT-000071 | TULAT | TU |
| SDTM-CT-000072 | URLAT | UR |
| SDTM-CT-000073 | VSLAT | VS |

### LOC — Anatomical Location (19 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000074 | AELOC | AE |
| SDTM-CT-000075 | BELOC | BE |
| SDTM-CT-000076 | CVLOC | CV |
| SDTM-CT-000077 | ECLOC | EC |
| SDTM-CT-000078 | EXLOC | EX |
| SDTM-CT-000079 | FALOC | FA |
| SDTM-CT-000080 | MBLOC | MB |
| SDTM-CT-000081 | MILOC | MI |
| SDTM-CT-000082 | MKLOC | MK |
| SDTM-CT-000083 | MSLOC | MS |
| SDTM-CT-000084 | NVLOC | NV |
| SDTM-CT-000085 | OELOC | OE |
| SDTM-CT-000086 | PELOC | PE |
| SDTM-CT-000087 | PRLOC | PR |
| SDTM-CT-000088 | RELOC | RE |
| SDTM-CT-000089 | SRLOC | SR |
| SDTM-CT-000090 | TULOC | TU |
| SDTM-CT-000091 | URLOC | UR |
| SDTM-CT-000092 | VSLOC | VS |

### MEDEVAL — Medical Evaluator (12 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000093 | COEVALID | CO |
| SDTM-CT-000094 | CVEVALID | CV |
| SDTM-CT-000095 | EGEVALID | EG |
| SDTM-CT-000096 | MKEVALID | MK |
| SDTM-CT-000097 | MSEVALID | MS |
| SDTM-CT-000098 | NVEVALID | NV |
| SDTM-CT-000099 | OEEVALID | OE |
| SDTM-CT-000100 | REEVALID | RE |
| SDTM-CT-000101 | RSEVALID | RS |
| SDTM-CT-000102 | TREVALID | TR |
| SDTM-CT-000103 | TUEVALID | TU |
| SDTM-CT-000104 | UREVALID | UR |

### METHOD — Method (19 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000105 | BSMETHOD | BS |
| SDTM-CT-000106 | CPMETHOD | CP |
| SDTM-CT-000107 | CVMETHOD | CV |
| SDTM-CT-000108 | GFMETHOD | GF |
| SDTM-CT-000109 | ISMETHOD | IS |
| SDTM-CT-000110 | LBMETHOD | LB |
| SDTM-CT-000111 | MBMETHOD | MB |
| SDTM-CT-000112 | MIMETHOD | MI |
| SDTM-CT-000113 | MKMETHOD | MK |
| SDTM-CT-000114 | MSMETHOD | MS |
| SDTM-CT-000115 | NVMETHOD | NV |
| SDTM-CT-000116 | OEMETHOD | OE |
| SDTM-CT-000117 | PCMETHOD | PC |
| SDTM-CT-000118 | PEMETHOD | PE |
| SDTM-CT-000119 | REMETHOD | RE |
| SDTM-CT-000120 | SRMETHOD | SR |
| SDTM-CT-000121 | TRMETHOD | TR |
| SDTM-CT-000122 | TUMETHOD | TU |
| SDTM-CT-000123 | URMETHOD | UR |

### NCOMPLT — Completion/Reason for Non-Completion (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000124 | DSDECOD | DS |

### ND — Not Done (36 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000125 | AGSTAT | AG |
| SDTM-CT-000126 | BSSTAT | BS |
| SDTM-CT-000127 | CESTAT | CE |
| SDTM-CT-000128 | CMSTAT | CM |
| SDTM-CT-000129 | CPSTAT | CP |
| SDTM-CT-000130 | CVSTAT | CV |
| SDTM-CT-000131 | DASTAT | DA |
| SDTM-CT-000132 | EGSTAT | EG |
| SDTM-CT-000133 | FASTAT | FA |
| SDTM-CT-000134 | FTSTAT | FT |
| SDTM-CT-000135 | GFSTAT | GF |
| SDTM-CT-000136 | HOSTAT | HO |
| SDTM-CT-000137 | ISSTAT | IS |
| SDTM-CT-000138 | LBSTAT | LB |
| SDTM-CT-000139 | MBSTAT | MB |
| SDTM-CT-000140 | MHSTAT | MH |
| SDTM-CT-000141 | MISTAT | MI |
| SDTM-CT-000142 | MKSTAT | MK |
| SDTM-CT-000143 | MLSTAT | ML |
| SDTM-CT-000144 | MSSTAT | MS |
| SDTM-CT-000145 | NVSTAT | NV |
| SDTM-CT-000146 | OESTAT | OE |
| SDTM-CT-000147 | PCSTAT | PC |
| SDTM-CT-000148 | PESTAT | PE |
| SDTM-CT-000149 | PPSTAT | PP |
| SDTM-CT-000150 | QSSTAT | QS |
| SDTM-CT-000151 | RESTAT | RE |
| SDTM-CT-000152 | RPSTAT | RP |
| SDTM-CT-000153 | RSSTAT | RS |
| SDTM-CT-000154 | SCSTAT | SC |
| SDTM-CT-000155 | SRSTAT | SR |
| SDTM-CT-000156 | SSSTAT | SS |
| SDTM-CT-000157 | SUSTAT | SU |
| SDTM-CT-000158 | TRSTAT | TR |
| SDTM-CT-000159 | URSTAT | UR |
| SDTM-CT-000160 | VSSTAT | VS |

### NORMABNM — Normal/Abnormal (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000161 | EGSTRESC | EG |

### NRIND — Normal Range Indicator (5 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000162 | CPNRIND | CP |
| SDTM-CT-000163 | ISNRIND | IS |
| SDTM-CT-000164 | LBNRIND | LB |
| SDTM-CT-000165 | MSNRIND | MS |
| SDTM-CT-000166 | OENRIND | OE |

### NY — No Yes Response (123 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000167 | AECONTRT | AE |
| SDTM-CT-000168 | AEPRESP | AE |
| SDTM-CT-000169 | AESCAN | AE |
| SDTM-CT-000170 | AESCONG | AE |
| SDTM-CT-000171 | AESDISAB | AE |
| SDTM-CT-000172 | AESDTH | AE |
| SDTM-CT-000173 | AESER | AE |
| SDTM-CT-000174 | AESHOSP | AE |
| SDTM-CT-000175 | AESINTV | AE |
| SDTM-CT-000176 | AESLIFE | AE |
| SDTM-CT-000177 | AESMIE | AE |
| SDTM-CT-000178 | AESOD | AE |
| SDTM-CT-000179 | AEUNANT | AE |
| SDTM-CT-000180 | AGOCCUR | AG |
| SDTM-CT-000181 | AGPRESP | AG |
| SDTM-CT-000182 | BSBLFL | BS |
| SDTM-CT-000183 | CEOCCUR | CE |
| SDTM-CT-000184 | CEPRESP | CE |
| SDTM-CT-000185 | CMOCCUR | CM |
| SDTM-CT-000186 | CMPRESP | CM |
| SDTM-CT-000187 | CPBLFL | CP |
| SDTM-CT-000188 | CPCLSIG | CP |
| SDTM-CT-000189 | CPDRVFL | CP |
| SDTM-CT-000190 | CPLOBXFL | CP |
| SDTM-CT-000191 | CVBLFL | CV |
| SDTM-CT-000192 | CVDRVFL | CV |
| SDTM-CT-000193 | CVLOBXFL | CV |
| SDTM-CT-000194 | DTHFL | DT |
| SDTM-CT-000195 | ECFAST | EC |
| SDTM-CT-000196 | ECOCCUR | EC |
| SDTM-CT-000197 | ECPRESP | EC |
| SDTM-CT-000198 | EGBLFL | EG |
| SDTM-CT-000199 | EGCLSIG | EG |
| SDTM-CT-000200 | EGDRVFL | EG |
| SDTM-CT-000201 | EGLOBXFL | EG |
| SDTM-CT-000202 | EXFAST | EX |
| SDTM-CT-000203 | FABLFL | FA |
| SDTM-CT-000204 | FALOBXFL | FA |
| SDTM-CT-000205 | FTBLFL | FT |
| SDTM-CT-000206 | FTDRVFL | FT |
| SDTM-CT-000207 | FTLOBXFL | FT |
| SDTM-CT-000208 | GFBLFL | GF |
| SDTM-CT-000209 | GFDRVFL | GF |
| SDTM-CT-000210 | HOOCCUR | HO |
| SDTM-CT-000211 | HOPRESP | HO |
| SDTM-CT-000212 | IEORRES | IE |
| SDTM-CT-000213 | IESTRESC | IE |
| SDTM-CT-000214 | ISBLFL | IS |
| SDTM-CT-000215 | ISDRVFL | IS |
| SDTM-CT-000216 | ISLOBXFL | IS |
| SDTM-CT-000217 | ISSPCUFL | IS |
| SDTM-CT-000218 | LBBLFL | LB |
| SDTM-CT-000219 | LBCLSIG | LB |
| SDTM-CT-000220 | LBDRVFL | LB |
| SDTM-CT-000221 | LBFAST | LB |
| SDTM-CT-000222 | LBLOBXFL | LB |
| SDTM-CT-000223 | LBPTFL | LB |
| SDTM-CT-000224 | LBSPCUFL | LB |
| SDTM-CT-000225 | MBBLFL | MB |
| SDTM-CT-000226 | MBDRVFL | MB |
| SDTM-CT-000227 | MBFAST | MB |
| SDTM-CT-000228 | MBLOBXFL | MB |
| SDTM-CT-000229 | MHOCCUR | MH |
| SDTM-CT-000230 | MHPRESP | MH |
| SDTM-CT-000231 | MIBLFL | MI |
| SDTM-CT-000232 | MILOBXFL | MI |
| SDTM-CT-000233 | MKBLFL | MK |
| SDTM-CT-000234 | MKDRVFL | MK |
| SDTM-CT-000235 | MKLOBXFL | MK |
| SDTM-CT-000236 | MLOCCUR | ML |
| SDTM-CT-000237 | MLPRESP | ML |
| SDTM-CT-000238 | MSACPTFL | MS |
| SDTM-CT-000239 | MSBLFL | MS |
| SDTM-CT-000240 | MSDRVFL | MS |
| SDTM-CT-000241 | MSFAST | MS |
| SDTM-CT-000242 | MSLOBXFL | MS |
| SDTM-CT-000243 | NVBLFL | NV |
| SDTM-CT-000244 | NVDRVFL | NV |
| SDTM-CT-000245 | NVLOBXFL | NV |
| SDTM-CT-000246 | OEACPTFL | OE |
| SDTM-CT-000247 | OEBLFL | OE |
| SDTM-CT-000248 | OEDRVFL | OE |
| SDTM-CT-000249 | OELOBXFL | OE |
| SDTM-CT-000250 | PCDRVFL | PC |
| SDTM-CT-000251 | PCFAST | PC |
| SDTM-CT-000252 | PEBLFL | PE |
| SDTM-CT-000253 | PELOBXFL | PE |
| SDTM-CT-000254 | PROCCUR | PR |
| SDTM-CT-000255 | PRPRESP | PR |
| SDTM-CT-000256 | QSBLFL | QS |
| SDTM-CT-000257 | QSDRVFL | QS |
| SDTM-CT-000258 | QSLOBXFL | QS |
| SDTM-CT-000259 | REBLFL | RE |
| SDTM-CT-000260 | REDRVFL | RE |
| SDTM-CT-000261 | RELOBXFL | RE |
| SDTM-CT-000262 | RPBLFL | RP |
| SDTM-CT-000263 | RPDRVFL | RP |
| SDTM-CT-000264 | RPLOBXFL | RP |
| SDTM-CT-000265 | RSACPTFL | RS |
| SDTM-CT-000266 | RSBLFL | RS |
| SDTM-CT-000267 | RSDRVFL | RS |
| SDTM-CT-000268 | RSLOBXFL | RS |
| SDTM-CT-000269 | SRBLFL | SR |
| SDTM-CT-000270 | SRLOBXFL | SR |
| SDTM-CT-000271 | SUOCCUR | SU |
| SDTM-CT-000272 | SUPRESP | SU |
| SDTM-CT-000273 | SVEPCHGI | SV |
| SDTM-CT-000274 | SVOCCUR | SV |
| SDTM-CT-000275 | SVPRESP | SV |
| SDTM-CT-000276 | TMRPT | TM |
| SDTM-CT-000277 | TRACPTFL | TR |
| SDTM-CT-000278 | TRBLFL | TR |
| SDTM-CT-000279 | TRLOBXFL | TR |
| SDTM-CT-000280 | TUACPTFL | TU |
| SDTM-CT-000281 | TUBLFL | TU |
| SDTM-CT-000282 | TULOBXFL | TU |
| SDTM-CT-000283 | URBLFL | UR |
| SDTM-CT-000284 | URDRVFL | UR |
| SDTM-CT-000285 | URLOBXFL | UR |
| SDTM-CT-000286 | VSBLFL | VS |
| SDTM-CT-000287 | VSCLSIG | VS |
| SDTM-CT-000288 | VSDRVFL | VS |
| SDTM-CT-000289 | VSLOBXFL | VS |

### ONCRSR — Oncology Response (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000290 | RSSTRESC | RS |

### OTHEVENT — Other Event (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000291 | DSDECOD | DS |

### OUT — Outcome of Event (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000292 | AEOUT | AE |

### PORTOT — Portion or Totality (4 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000293 | ECPORTOT | EC |
| SDTM-CT-000294 | OEPORTOT | OE |
| SDTM-CT-000295 | PRPORTOT | PR |
| SDTM-CT-000296 | TUPORTOT | TU |

### POSITION — Position of Subject During Observation (6 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000297 | CVPOS | CV |
| SDTM-CT-000298 | EGPOS | EG |
| SDTM-CT-000299 | FTPOS | FT |
| SDTM-CT-000300 | MKPOS | MK |
| SDTM-CT-000301 | REPOS | RE |
| SDTM-CT-000302 | VSPOS | VS |

### PROTMLST — Protocol Milestone (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000303 | DSDECOD | DS |

### RACE — Race (8 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000304 | RACE | RA |
| SDTM-CT-000305 | RACE01 | RA |
| SDTM-CT-000306 | RACE02 | RA |
| SDTM-CT-000307 | RACE03 | RA |
| SDTM-CT-000308 | RACE04 | RA |
| SDTM-CT-000309 | RACE05 | RA |
| SDTM-CT-000310 | RACE06 | RA |
| SDTM-CT-000311 | RACE07 | RA |

### RELTYPE — Relationship Type (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000312 | RELTYPE | RE |

### RESTYPRS — Result or Finding Type (2 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000313 | CPRESTYP | CP |
| SDTM-CT-000314 | LBRESTYP | LB |

### ROUTE — Route of Administration (6 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000315 | AGROUTE | AG |
| SDTM-CT-000316 | CMROUTE | CM |
| SDTM-CT-000317 | ECROUTE | EC |
| SDTM-CT-000318 | EXROUTE | EX |
| SDTM-CT-000319 | PRROUTE | PR |
| SDTM-CT-000320 | SUROUTE | SU |

### RSLSCLRS — Result or Finding Scale (2 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000321 | CPRESSCL | CP |
| SDTM-CT-000322 | LBRESSCL | LB |

### SEVRS — Severity (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000323 | CESEV | CE |

### SEX — Sex (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000324 | SEX | ALL |

### SSTATRS — Study Status Result (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000325 | SSSTRESC | SS |

### STENRF — Start/End Relative to Reference Period (26 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000326 | AEENRF | AE |
| SDTM-CT-000327 | AEENRTPT | AE |
| SDTM-CT-000328 | AGENRF | AG |
| SDTM-CT-000329 | AGENRTPT | AG |
| SDTM-CT-000330 | AGSTRF | AG |
| SDTM-CT-000331 | AGSTRTPT | AG |
| SDTM-CT-000332 | CEENRF | CE |
| SDTM-CT-000333 | CEENRTPT | CE |
| SDTM-CT-000334 | CESTRF | CE |
| SDTM-CT-000335 | CESTRTPT | CE |
| SDTM-CT-000336 | CMENRF | CM |
| SDTM-CT-000337 | CMENRTPT | CM |
| SDTM-CT-000338 | CMSTRF | CM |
| SDTM-CT-000339 | CMSTRTPT | CM |
| SDTM-CT-000340 | HOENRTPT | HO |
| SDTM-CT-000341 | HOSTRTPT | HO |
| SDTM-CT-000342 | MHENRF | MH |
| SDTM-CT-000343 | MHENRTPT | MH |
| SDTM-CT-000344 | PRENRTPT | PR |
| SDTM-CT-000345 | PRSTRTPT | PR |
| SDTM-CT-000346 | RSENRTPT | RS |
| SDTM-CT-000347 | RSSTRTPT | RS |
| SDTM-CT-000348 | SUENRF | SU |
| SDTM-CT-000349 | SUENRTPT | SU |
| SDTM-CT-000350 | SUSTRF | SU |
| SDTM-CT-000351 | SUSTRTPT | SU |

### TSPARM — Trial Summary Parameter (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000352 | TSPARM | TS |

### TSPARMCD — Trial Summary Parameter Short Name (1 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000353 | TSPARMCD | TS |

### UNIT — Unit (58 rules)

| Core ID | Variable | Domain |
|---------|----------|--------|
| SDTM-CT-000354 | AGDOSU | AG |
| SDTM-CT-000355 | BSORRESU | BS |
| SDTM-CT-000356 | BSSTRESU | BS |
| SDTM-CT-000357 | CMDOSU | CM |
| SDTM-CT-000358 | CPORRESU | CP |
| SDTM-CT-000359 | CPSTRESU | CP |
| SDTM-CT-000360 | CVORRESU | CV |
| SDTM-CT-000361 | CVSTRESU | CV |
| SDTM-CT-000362 | DAORRESU | DA |
| SDTM-CT-000363 | DASTRESU | DA |
| SDTM-CT-000364 | ECDOSU | EC |
| SDTM-CT-000365 | ECPSTRGU | EC |
| SDTM-CT-000366 | EGORRESU | EG |
| SDTM-CT-000367 | EGSTRESU | EG |
| SDTM-CT-000368 | EXDOSU | EX |
| SDTM-CT-000369 | FAORRESU | FA |
| SDTM-CT-000370 | FASTRESU | FA |
| SDTM-CT-000371 | FTORRESU | FT |
| SDTM-CT-000372 | FTSTRESU | FT |
| SDTM-CT-000373 | GFORRESU | GF |
| SDTM-CT-000374 | GFSTRESU | GF |
| SDTM-CT-000375 | ISORRESU | IS |
| SDTM-CT-000376 | ISSTRESU | IS |
| SDTM-CT-000377 | LBORRESU | LB |
| SDTM-CT-000378 | LBSTRESU | LB |
| SDTM-CT-000379 | MBORRESU | MB |
| SDTM-CT-000380 | MBSTRESU | MB |
| SDTM-CT-000381 | MIORRESU | MI |
| SDTM-CT-000382 | MISTRESU | MI |
| SDTM-CT-000383 | MKORRESU | MK |
| SDTM-CT-000384 | MKSTRESU | MK |
| SDTM-CT-000385 | MLDOSU | ML |
| SDTM-CT-000386 | MSCONCU | MS |
| SDTM-CT-000387 | MSORRESU | MS |
| SDTM-CT-000388 | MSSTRESU | MS |
| SDTM-CT-000389 | NVORRESU | NV |
| SDTM-CT-000390 | NVSTRESU | NV |
| SDTM-CT-000391 | OEORRESU | OE |
| SDTM-CT-000392 | OESTRESU | OE |
| SDTM-CT-000393 | PEORRESU | PE |
| SDTM-CT-000394 | PRDOSU | PR |
| SDTM-CT-000395 | QSORRESU | QS |
| SDTM-CT-000396 | QSSTRESU | QS |
| SDTM-CT-000397 | REORRESU | RE |
| SDTM-CT-000398 | RESTRESU | RE |
| SDTM-CT-000399 | RPORRESU | RP |
| SDTM-CT-000400 | RPSTRESU | RP |
| SDTM-CT-000401 | RSORRESU | RS |
| SDTM-CT-000402 | RSSTRESU | RS |
| SDTM-CT-000403 | SCORRESU | SC |
| SDTM-CT-000404 | SCSTRESU | SC |
| SDTM-CT-000405 | SRORRESU | SR |
| SDTM-CT-000406 | SRSTRESU | SR |
| SDTM-CT-000407 | SUDOSU | SU |
| SDTM-CT-000408 | TRORRESU | TR |
| SDTM-CT-000409 | TRSTRESU | TR |
| SDTM-CT-000410 | URORRESU | UR |
| SDTM-CT-000411 | URSTRESU | UR |
