# ADaMIG 1.3 — Generated-vs-Conformance rule gap analysis

Comparing `rules/generated/rules-adamig-1-3.json` (471 rules, derived from ADaMIG v1.3 IG PDF) against `rules/rules-adamig-1-3.json` (696 rules, derived from ADaM Conformance Rules v5.0 xlsx).

Goal: identify generated (IG-derived) rules that have NO corresponding rule in the conformance file.

Matching: fuzzy description + message comparison, scored as `0.65·var_name_Jaccard + 0.35·IDF_token_Jaccard`.

## Summary

| Bucket | Count | Meaning |
|---|---:|---|
| strong match (≥ 0.45) | 130 | conformance file likely covers this rule |
| weak match (0.30 – 0.45) | 156 | manual review recommended |
| no credible match (< 0.30) | 185 | likely NOT covered |

## No credible match (review as potential gaps)

### ADAM-000013
- **Description:** At least one population flag variable is required in ADSL.
- **Message:** No population flag variable found in ADSL. At least one population flag is required.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.11` **ADAMCR-0072** — TRT01P is not present within ADSL
  - `0.10` **ADAMCR-0256** — The values of USUBJID are not present in ADSL
  - `0.10` **ADAMCR-0055** — SUBJID is not present within ADSL

### ADAM-000022
- **Description:** ADaM dataset names must not exceed 8 characters.
- **Message:** ADaM dataset name exceeds 8 characters (SAS transport file format limit).
- **Variables referenced:** `SAS`
- **Best conformance candidates:**
  - `0.21` **ADAMCR-0013** — The length of a variable name exceeds 8 characters
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.10` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types

### ADAM-000024
- **Description:** ADaM variable labels must not exceed 40 characters.
- **Message:** Variable label exceeds 40 characters.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.27` **ADAMCR-0013** — The length of a variable name exceeds 8 characters
  - `0.20` **ADAMCR-0016** — The length of a variable label is greater than 40 characters
  - `0.18` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000033
- **Description:** DTYPE must be present when a BDS dataset contains derived records.
- **Message:** DTYPE variable is not present. DTYPE is conditionally required when derived records exist in the dataset.
- **Variables referenced:** `DTYPE`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.07` **ADAMCR-0015** — A variable name contains a character other than letters (A-Z), underscores (_), or numerals (0-9)
  - `0.05` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000034
- **Description:** Variables with the same name as SDTM variables must have the same values (same name, same meaning, same values).
- **Message:** ADaM variable has the same name as an SDTM variable but different values. ADaM adheres to the principle 'same name, same meaning, same values.'
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.24` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.20` **ADAMCR-0746** — SRCDOM has a value that is not an SDTM domain name, ADaM dataset name, or null

### ADAM-000037
- **Description:** Relative day variables ending in DY must not have a value of 0.
- **Message:** Relative day variable (*DY) contains value 0. There is no Day 0 in ADaM.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0506** — A variable ending in ADY must contain "Relative Day" in the label
  - `0.24` **ADAMCR-0512** — A variable ending in SDY must contain "Relative Start Day" in the label
  - `0.24` **ADAMCR-0518** — A variable ending in EDY must contain "Relative End Day" in the label

### ADAM-000042
- **Description:** Numeric flag (*FN) cannot exist without corresponding character flag (*FL).
- **Message:** Numeric flag variable (*FN) is present but the corresponding character flag (*FL) is missing. Character version is required.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0007** — A variable with a suffix of FN is present but a variable with the same root and a suffix of FL is not present
  - `0.14` **ADAMCR-0006** — A variable with a suffix of FL is present and a variable with the same root and a suffix of FN has a value that is not  0, 1 or null
  - `0.12` **ADAMCR-0012** — A variable with a suffix of FL is equal to null and a variable with the same root and a suffix of FN is not equal to null

### ADAM-000044
- **Description:** TRT01AN cannot be present unless TRT01A is also present.
- **Message:** TRT01AN is present but TRT01A is not. The primary variable must be present when the secondary variable exists.
- **Variables referenced:** `TRT01A, TRT01AN`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0007** — A variable with a suffix of FN is present but a variable with the same root and a suffix of FL is not present
  - `0.09` **ADAMCR-0085** — A variable is present with the same name as a variable present in ADSL but the variables do not have identical labels
  - `0.09` **ADAMCR-0086** — A variable is present with the same name as a variable present in ADSL but the variables do not have identical formats

### ADAM-000047
- **Description:** TRTSDTF must be populated when TRTSDT is imputed.
- **Message:** TRTSDTF has an invalid value. Must be Y, M, or D when TRTSDT is imputed.
- **Variables referenced:** `TRTSDT, TRTSDTF`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0061** — SDTM.EX is present and neither TRTSDT or TRTSDTM are present
  - `0.05` **ADAMCR-0005** — A variable with a suffix of FL has a value that is not Y, N or null
  - `0.05` **ADAMCR-0010** — A variable with a suffix of FL is equal to Y and a variable with the same root and a suffix of FN is not equal to 1

### ADAM-000063
- **Description:** When ANRHIy exists, ANRLOy must also exist.
- **Message:** ANRHI1 is present but ANRLO1 is not. Both analysis range variables should be present together.
- **Variables referenced:** `ANRHI1, ANRLO1`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0085** — A variable is present with the same name as a variable present in ADSL but the variables do not have identical labels
  - `0.10` **ADAMCR-0086** — A variable is present with the same name as a variable present in ADSL but the variables do not have identical formats
  - `0.09` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000064
- **Description:** ANLzzFL (Analysis Record Flag) must be 'Y' or null.
- **Message:** ANLzzFL has a value other than 'Y' or null.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0412** — On a given record, ANLzzFL is populated and ANLzzFN is not populated
  - `0.22` **ADAMCR-0411** — On a given record, ANLzzFN is populated and ANLzzFL is not populated
  - `0.16` **ADAMCR-0144** — PARAMCD starts with a character other than a letter

### ADAM-000066
- **Description:** A variable present in both ADSL and another ADaM dataset must have the same values, type, and label.
- **Message:** Variable has different values in ADSL and the current dataset. Variables present in both must have the same values.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.21` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.16` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000071
- **Description:** Non-ADaM analysis dataset names should not start with 'AD' prefix.
- **Message:** Non-ADaM analysis dataset name starts with 'AD'. Non-ADaM datasets should use 'AX' prefix instead.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0496** — A dataset name does not start with "AD" when dataset class is not missing
  - `0.17` **ADAMCR-0497** — A dataset name starts with "AD" when the dataset class is missing
  - `0.13` **ADAMCR-0746** — SRCDOM has a value that is not an SDTM domain name, ADaM dataset name, or null

### ADAM-000074
- **Description:** DTYPE values should be from the ADaM controlled terminology.
- **Message:** DTYPE value is not from the ADaM controlled terminology for derivation type.
- **Variables referenced:** `DTYPE`
- **Best conformance candidates:**
  - `0.11` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.11` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.07` **ADAMCR-0040** — A variable with a suffix of TMF has a value that is not within Controlled Terminology for TIMEFL

### ADAM-000076
- **Description:** EOSSTT values should be from SBJTSTAT controlled terminology.
- **Message:** EOSSTT has a value not in the expected controlled terminology (COMPLETED, DISCONTINUED, ONGOING).
- **Variables referenced:** `COMPLETED, DISCONTINUED, EOSSTT, ONGOING, SBJTSTAT`
- **Best conformance candidates:**
  - `0.06` **ADAMCR-0040** — A variable with a suffix of TMF has a value that is not within Controlled Terminology for TIMEFL
  - `0.06` **ADAMCR-0039** — A variable with a suffix of DTF has a value that is not within Controlled Terminology for DATEFL
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000077
- **Description:** DCSREAS should be null when EOSSTT is 'COMPLETED'.
- **Message:** DCSREAS is populated when EOSSTT is 'COMPLETED'. Discontinuation reason should be null for subjects who completed the study.
- **Variables referenced:** `COMPLETED, DCSREAS, EOSSTT`
- **Best conformance candidates:**
  - `0.02` **ADAMCR-0324** — Within a study, there is more than one value of APHASE for a given value of APHASEN, considering only those rows on which both variables are populated
  - `0.02` **ADAMCR-0323** — Within a study, there is more than one value of APHASEN for a given value of APHASE, considering only those rows on which both variables are populated
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000078
- **Description:** ADaM datasets must be accompanied by metadata.
- **Message:** ADaM dataset does not have associated metadata. Metadata is required for all ADaM datasets.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.15` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.14` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.13` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types

### ADAM-000087
- **Description:** CRITyFL cannot exist without corresponding CRITy.
- **Message:** CRIT1FL is present but CRIT1 is not. The criterion description variable (CRITy) must be present when CRITyFL exists.
- **Variables referenced:** `CRIT1, CRIT1FL`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0336** — CRITy is present and CRITyFL is not present
  - `0.22` **ADAMCR-0335** — CRITyFL is present and CRITy is not present
  - `0.19` **ADAMCR-0137** — CRITyFL is populated and CRITy is not populated

### ADAM-000088
- **Description:** CRITyFL must have values of Y, N, or null.
- **Message:** CRITyFL has a value other than Y, N, or null.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.23` **ADAMCR-0024** — RANDFL is present and has a value that is not Y or N
  - `0.23` **ADAMCR-0025** — ENRLFL is present and has a value that is not Y or N
  - `0.23` **ADAMCR-0023** — SAFFL is present and has a value that is not Y or N

### ADAM-000091
- **Description:** Variable AAGE must have label 'Analysis Age'.
- **Message:** Variable AAGE does not have the expected label 'Analysis Age'.
- **Variables referenced:** `AAGE`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.20` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.12` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000095
- **Description:** Variable ADT must have label 'Analysis Date'.
- **Message:** Variable ADT does not have the expected label 'Analysis Date'.
- **Variables referenced:** `ADT`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.21` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.20` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL

### ADAM-000096
- **Description:** Variable ADTF must have label 'Analysis Date Imputation'.
- **Message:** Variable ADTF does not have the expected label 'Analysis Date Imputation'.
- **Variables referenced:** `ADTF`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.19` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL

### ADAM-000097
- **Description:** Variable ADTM must have label 'Analysis Datetime'.
- **Message:** Variable ADTM does not have the expected label 'Analysis Datetime'.
- **Variables referenced:** `ADTM`
- **Best conformance candidates:**
  - `0.21` **ADAMCR-0511** — A variable ending in SDTM must contain "Start Datetime" in the label
  - `0.20` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.20` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"

### ADAM-000102
- **Description:** Variable AENTM must have label 'Analysis End Time'.
- **Message:** Variable AENTM does not have the expected label 'Analysis End Time'.
- **Variables referenced:** `AENTM`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"

### ADAM-000103
- **Description:** Variable AENTMF must have label 'Analysis End Time'.
- **Message:** Variable AENTMF does not have the expected label 'Analysis End Time'.
- **Variables referenced:** `AENTMF`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"

### ADAM-000110
- **Description:** Variable APEREDTF must have label 'Period End Date Imput.'.
- **Message:** Variable APEREDTF does not have the expected label 'Period End Date Imput.'.
- **Variables referenced:** `APEREDTF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.09` **ADAMCR-0515** — A variable ending in EDT must contain "End Date" in the label

### ADAM-000113
- **Description:** Variable APERETMF must have label 'Period End Time Imput.'.
- **Message:** Variable APERETMF does not have the expected label 'Period End Time Imput.'.
- **Variables referenced:** `APERETMF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.09` **ADAMCR-0516** — A variable ending in ETM must contain "End Time" in the label
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000117
- **Description:** Variable APERSDTF must have label 'Period Start Date Imput.'.
- **Message:** Variable APERSDTF does not have the expected label 'Period Start Date Imput.'.
- **Variables referenced:** `APERSDTF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format

### ADAM-000120
- **Description:** Variable APERSTMF must have label 'Period Start Time Imput.'.
- **Message:** Variable APERSTMF does not have the expected label 'Period Start Time Imput.'.
- **Variables referenced:** `APERSTMF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.15` **ADAMCR-0511** — A variable ending in SDTM must contain "Start Datetime" in the label

### ADAM-000126
- **Description:** Variable ASEQ must have label 'Analysis Sequence Number'.
- **Message:** Variable ASEQ does not have the expected label 'Analysis Sequence Number'.
- **Variables referenced:** `ASEQ`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000129
- **Description:** Variable ASPREDT must have label 'Subperiod End Date'.
- **Message:** Variable ASPREDT does not have the expected label 'Subperiod End Date'.
- **Variables referenced:** `ASPREDT`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0601** — On a given record, the value of ASPREDT is not equal to the value of variable PxxSwEDT where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.20` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.19` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format

### ADAM-000130
- **Description:** Variable ASPREDTF must have label 'Subperiod End Date Imput.'.
- **Message:** Variable ASPREDTF does not have the expected label 'Subperiod End Date Imput.'.
- **Variables referenced:** `ASPREDTF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.09` **ADAMCR-0515** — A variable ending in EDT must contain "End Date" in the label

### ADAM-000131
- **Description:** Variable ASPREDTM must have label 'Subperiod End Datetime'.
- **Message:** Variable ASPREDTM does not have the expected label 'Subperiod End Datetime'.
- **Variables referenced:** `ASPREDTM`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0603** — On a given record, the value of ASPREDTM is not equal to the value of variable PxxSwEDM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.19` **ADAMCR-0511** — A variable ending in SDTM must contain "Start Datetime" in the label
  - `0.11` **ADAMCR-0517** — A variable ending in EDTM must contain "End Datetime" in the label

### ADAM-000132
- **Description:** Variable ASPRETM must have label 'Subperiod End Time'.
- **Message:** Variable ASPRETM does not have the expected label 'Subperiod End Time'.
- **Variables referenced:** `ASPRETM`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0602** — On a given record, the value of ASPRETM is not equal to the value of variable PxxSwETM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.11` **ADAMCR-0516** — A variable ending in ETM must contain "End Time" in the label

### ADAM-000133
- **Description:** Variable ASPRETMF must have label 'Subperiod End Time Imput.'.
- **Message:** Variable ASPRETMF does not have the expected label 'Subperiod End Time Imput.'.
- **Variables referenced:** `ASPRETMF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.09` **ADAMCR-0516** — A variable ending in ETM must contain "End Time" in the label
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000134
- **Description:** Variable ASPRSDT must have label 'Subperiod Start Date'.
- **Message:** Variable ASPRSDT does not have the expected label 'Subperiod Start Date'.
- **Variables referenced:** `ASPRSDT`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0598** — On a given record, the value of ASPRSDT is not equal to the value of variable PxxSwSDT where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.20` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.20` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label

### ADAM-000135
- **Description:** Variable ASPRSDTF must have label 'Subperiod Start Date Imput.'.
- **Message:** Variable ASPRSDTF does not have the expected label 'Subperiod Start Date Imput.'.
- **Variables referenced:** `ASPRSDTF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format

### ADAM-000137
- **Description:** Variable ASPRSTM must have label 'Subperiod Start Time'.
- **Message:** Variable ASPRSTM does not have the expected label 'Subperiod Start Time'.
- **Variables referenced:** `ASPRSTM`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0599** — On a given record, the value of ASPRSTM is not equal to the value of variable PxxSwSTM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.20` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label

### ADAM-000139
- **Description:** Variable ASTDTF must have label 'Analysis Start Date'.
- **Message:** Variable ASTDTF does not have the expected label 'Analysis Start Date'.
- **Variables referenced:** `ASTDTF`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.20` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.19` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format

### ADAM-000142
- **Description:** Variable ASTTM must have label 'Analysis Start Time'.
- **Message:** Variable ASTTM does not have the expected label 'Analysis Start Time'.
- **Variables referenced:** `ASTTM`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.19` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL

### ADAM-000143
- **Description:** Variable ASTTMF must have label 'Analysis Start Time'.
- **Message:** Variable ASTTMF does not have the expected label 'Analysis Start Time'.
- **Variables referenced:** `ASTTMF`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.19` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL

### ADAM-000144
- **Description:** Variable ATM must have label 'Analysis Time'.
- **Message:** Variable ATM does not have the expected label 'Analysis Time'.
- **Variables referenced:** `ATM`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.20` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.20` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"

### ADAM-000145
- **Description:** Variable ATMF must have label 'Analysis Time Imputation'.
- **Message:** Variable ATMF does not have the expected label 'Analysis Time Imputation'.
- **Variables referenced:** `ATMF`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"

### ADAM-000156
- **Description:** Variable ATPTREF must have label 'Analysis Timepoint'.
- **Message:** Variable ATPTREF does not have the expected label 'Analysis Timepoint'.
- **Variables referenced:** `ATPTREF`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.20` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000161
- **Description:** Variable AWHI must have label 'Analysis Window Ending'.
- **Message:** Variable AWHI does not have the expected label 'Analysis Window Ending'.
- **Variables referenced:** `AWHI`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.18` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label

### ADAM-000162
- **Description:** Variable AWLO must have label 'Analysis Window Beginning'.
- **Message:** Variable AWLO does not have the expected label 'Analysis Window Beginning'.
- **Variables referenced:** `AWLO`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.17` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.17` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present

### ADAM-000163
- **Description:** Variable AWRANGE must have label 'Analysis Window Valid'.
- **Message:** Variable AWRANGE does not have the expected label 'Analysis Window Valid'.
- **Variables referenced:** `AWRANGE`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.17` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000166
- **Description:** Variable AWU must have label 'Analysis Window Unit'.
- **Message:** Variable AWU does not have the expected label 'Analysis Window Unit'.
- **Variables referenced:** `AWU`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.17` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.17` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present

### ADAM-000168
- **Description:** Variable BASEC must have label 'Baseline Value (C)'.
- **Message:** Variable BASEC does not have the expected label 'Baseline Value (C)'.
- **Variables referenced:** `BASEC`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0128** — Within a given value of PARAMCD for a subject, BASEC is populated and there is not at least one record with ABLFL equal to Y
  - `0.25` **ADAMCR-0692** — Within a given value of PARAMCD for a combination of device and subject, BASEC is populated and there is not at least one record with ABLFL equal to Y
  - `0.25` **ADAMCR-0733** — Within a given value of PARAMCD for a subject, there is more than one value of BASEC for a given value of BASE, considering only those rows on which both variables are populated

### ADAM-000169
- **Description:** Variable BASETYPE must have label 'Baseline Type'.
- **Message:** Variable BASETYPE does not have the expected label 'Baseline Type'.
- **Variables referenced:** `BASETYPE`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0154** — Within a given PARAMCD and BASETYPE for a subject, more than one record has ABLFL equal to Y
  - `0.24` **ADAMCR-0790** — BASETYPE is populated, ByIND is populated, and ByIND is not equal to AyIND where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject
  - `0.24` **ADAMCR-0155** — Within a given PARAMCD for a subject, more than one record has ABLFL equal to Y and BASETYPE is not present

### ADAM-000179
- **Description:** Variable CNSDTDSC must have label 'Censor Date Description'.
- **Message:** Variable CNSDTDSC does not have the expected label 'Censor Date Description'.
- **Variables referenced:** `CNSDTDSC`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.18` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000182
- **Description:** Variable COMPLPFL must have label 'Completers Parameter-Level'.
- **Message:** Variable COMPLPFL does not have the expected label 'Completers Parameter-Level'.
- **Variables referenced:** `COMPLPFL`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000183
- **Description:** Variable COMPLRFL must have label 'Completers Record-Level'.
- **Message:** Variable COMPLRFL does not have the expected label 'Completers Record-Level'.
- **Variables referenced:** `COMPLRFL`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.13` **ADAMCR-0719** — None of the subject-level or record-level treatment variables defined in the IG is present

### ADAM-000184
- **Description:** Variable DCSREAS must have label 'Reason for Discontinuation'.
- **Message:** Variable DCSREAS does not have the expected label 'Reason for Discontinuation'.
- **Variables referenced:** `DCSREAS`
- **Best conformance candidates:**
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000185
- **Description:** Variable DCSREASP must have label 'Reason Spec for Discont'.
- **Message:** Variable DCSREASP does not have the expected label 'Reason Spec for Discont'.
- **Variables referenced:** `DCSREASP`
- **Best conformance candidates:**
  - `0.09` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.08` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000186
- **Description:** Variable DCTREASP must have label 'Reason Specify for Discont of'.
- **Message:** Variable DCTREASP does not have the expected label 'Reason Specify for Discont of'.
- **Variables referenced:** `DCTREASP`
- **Best conformance candidates:**
  - `0.09` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.08` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000187
- **Description:** Variable DOSCUMA must have label 'Cumulative Actual'.
- **Message:** Variable DOSCUMA does not have the expected label 'Cumulative Actual'.
- **Variables referenced:** `DOSCUMA`
- **Best conformance candidates:**
  - `0.27` **ADAMCR-0364** — DOSEON or DOSCUMA is present and DOSEU is not present
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000188
- **Description:** Variable DOSCUMP must have label 'Cumulative Planned'.
- **Message:** Variable DOSCUMP does not have the expected label 'Cumulative Planned'.
- **Variables referenced:** `DOSCUMP`
- **Best conformance candidates:**
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000190
- **Description:** Variable DOSEP must have label 'Planned Treatment Dose'.
- **Message:** Variable DOSEP does not have the expected label 'Planned Treatment Dose'.
- **Variables referenced:** `DOSEP`
- **Best conformance candidates:**
  - `0.27` **ADAMCR-0666** — DOSPCTDF is populated and DOSEP and DOSEA are not populated
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000194
- **Description:** Variable DTHDT must have label 'Date of Death'.
- **Message:** Variable DTHDT does not have the expected label 'Date of Death'.
- **Variables referenced:** `DTHDT`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.20` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.12` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000195
- **Description:** Variable DTHDTF must have label 'Date of Death Imputation'.
- **Message:** Variable DTHDTF does not have the expected label 'Date of Death Imputation'.
- **Variables referenced:** `DTHDTF`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.18` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000196
- **Description:** Variable DTYPE must have label 'Derivation Type'.
- **Message:** Variable DTYPE does not have the expected label 'Derivation Type'.
- **Variables referenced:** `DTYPE`
- **Best conformance candidates:**
  - `0.12` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.11` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000197
- **Description:** Variable ENRLDT must have label 'Date of Enrollment'.
- **Message:** Variable ENRLDT does not have the expected label 'Date of Enrollment'.
- **Variables referenced:** `ENRLDT`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.20` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.12` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000199
- **Description:** Variable EOSDT must have label 'End of Study Date'.
- **Message:** Variable EOSDT does not have the expected label 'End of Study Date'.
- **Variables referenced:** `EOSDT`
- **Best conformance candidates:**
  - `0.20` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.19` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.11` **ADAMCR-0515** — A variable ending in EDT must contain "End Date" in the label

### ADAM-000200
- **Description:** Variable EOSSTT must have label 'End of Study Status'.
- **Message:** Variable EOSSTT does not have the expected label 'End of Study Status'.
- **Variables referenced:** `EOSSTT`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000201
- **Description:** Variable EOTSTT must have label 'End of Treatment Status'.
- **Message:** Variable EOTSTT does not have the expected label 'End of Treatment Status'.
- **Variables referenced:** `EOTSTT`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000202
- **Description:** Variable EVNTDESC must have label 'Event or Censoring'.
- **Message:** Variable EVNTDESC does not have the expected label 'Event or Censoring'.
- **Variables referenced:** `EVNTDESC`
- **Best conformance candidates:**
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000204
- **Description:** Variable FASPFL must have label 'Full Analysis Set Parameter-'.
- **Message:** Variable FASPFL does not have the expected label 'Full Analysis Set Parameter-'.
- **Variables referenced:** `FASPFL`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.16` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000205
- **Description:** Variable FASRFL must have label 'Full Analysis Set Record-'.
- **Message:** Variable FASRFL does not have the expected label 'Full Analysis Set Record-'.
- **Variables referenced:** `FASRFL`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.17` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000207
- **Description:** Variable ITTPFL must have label 'Intent-To-Treat Parameter-'.
- **Message:** Variable ITTPFL does not have the expected label 'Intent-To-Treat Parameter-'.
- **Variables referenced:** `ITTPFL`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000208
- **Description:** Variable ITTRFL must have label 'Intent-To-Treat Record-Level'.
- **Message:** Variable ITTRFL does not have the expected label 'Intent-To-Treat Record-Level'.
- **Variables referenced:** `ITTRFL`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.16` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.12` **ADAMCR-0719** — None of the subject-level or record-level treatment variables defined in the IG is present

### ADAM-000209
- **Description:** Variable LSTALVDT must have label 'Date Last Known Alive'.
- **Message:** Variable LSTALVDT does not have the expected label 'Date Last Known Alive'.
- **Variables referenced:** `LSTALVDT`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000219
- **Description:** Variable PHEDTF must have label 'Phase End Date Imput. Flag'.
- **Message:** Variable PHEDTF does not have the expected label 'Phase End Date Imput. Flag'.
- **Variables referenced:** `PHEDTF`
- **Best conformance candidates:**
  - `0.15` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.15` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.10` **ADAMCR-0519** — A variable ending in EDTF must contain "End Date Imputation Flag" in the label

### ADAM-000222
- **Description:** Variable PHETMF must have label 'Phase End Time Imput.'.
- **Message:** Variable PHETMF does not have the expected label 'Phase End Time Imput.'.
- **Variables referenced:** `PHETMF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.09` **ADAMCR-0516** — A variable ending in ETM must contain "End Time" in the label
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000224
- **Description:** Variable PHSDTF must have label 'Phase Start Date Imput.'.
- **Message:** Variable PHSDTF does not have the expected label 'Phase Start Date Imput.'.
- **Variables referenced:** `PHSDTF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format

### ADAM-000227
- **Description:** Variable PHSTMF must have label 'Phase Start Time Imput.'.
- **Message:** Variable PHSTMF does not have the expected label 'Phase Start Time Imput.'.
- **Variables referenced:** `PHSTMF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.15` **ADAMCR-0511** — A variable ending in SDTM must contain "Start Datetime" in the label

### ADAM-000229
- **Description:** Variable PPROTPFL must have label 'Per-Protocol Parameter-'.
- **Message:** Variable PPROTPFL does not have the expected label 'Per-Protocol Parameter-'.
- **Variables referenced:** `PPROTPFL`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000230
- **Description:** Variable PPROTRFL must have label 'Per-Protocol Record-Level'.
- **Message:** Variable PPROTRFL does not have the expected label 'Per-Protocol Record-Level'.
- **Variables referenced:** `PPROTRFL`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.16` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.12` **ADAMCR-0719** — None of the subject-level or record-level treatment variables defined in the IG is present

### ADAM-000231
- **Description:** Variable R2BASE must have label 'Ratio to Baseline'.
- **Message:** Variable R2BASE does not have the expected label 'Ratio to Baseline'.
- **Variables referenced:** `R2BASE`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0132** — R2BASE is not equal to AVAL divided by BASE
  - `0.13` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.11` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000235
- **Description:** Variable RFICDT must have label 'Date of Informed Consent'.
- **Message:** Variable RFICDT does not have the expected label 'Date of Informed Consent'.
- **Variables referenced:** `RFICDT`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.18` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000237
- **Description:** Variable SAFPFL must have label 'Safety Analysis Parameter-'.
- **Message:** Variable SAFPFL does not have the expected label 'Safety Analysis Parameter-'.
- **Variables referenced:** `SAFPFL`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.18` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000238
- **Description:** Variable SAFRFL must have label 'Safety Analysis Record-'.
- **Message:** Variable SAFRFL does not have the expected label 'Safety Analysis Record-'.
- **Variables referenced:** `SAFRFL`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0321** — A dataset label is "Subject-Level Analysis Dataset" and the dataset is not named ADSL
  - `0.19` **ADAMCR-0320** — A dataset is named ADSL and the dataset label is not "Subject-Level Analysis Dataset"
  - `0.11` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000243
- **Description:** Variable SRCVAR must have label 'Source Variable'.
- **Message:** Variable SRCVAR does not have the expected label 'Source Variable'.
- **Variables referenced:** `SRCVAR`
- **Best conformance candidates:**
  - `0.13` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.11` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.11` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000244
- **Description:** Variable STARTDT must have label 'Time-to-Event Origin Date for'.
- **Message:** Variable STARTDT does not have the expected label 'Time-to-Event Origin Date for'.
- **Variables referenced:** `STARTDT`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format

### ADAM-000245
- **Description:** Variable STARTDTF must have label 'Origin Date Imputation Flag'.
- **Message:** Variable STARTDTF does not have the expected label 'Origin Date Imputation Flag'.
- **Variables referenced:** `STARTDTF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.17` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.13` **ADAMCR-0507** — A variable ending in DTF must contain "Date Imputation Flag" in the label

### ADAM-000246
- **Description:** Variable STARTDTM must have label 'Time-to-Event Origin'.
- **Message:** Variable STARTDTM does not have the expected label 'Time-to-Event Origin'.
- **Variables referenced:** `STARTDTM`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000247
- **Description:** Variable STARTTMF must have label 'Origin Time Imputation Flag'.
- **Message:** Variable STARTTMF does not have the expected label 'Origin Time Imputation Flag'.
- **Variables referenced:** `STARTTMF`
- **Best conformance candidates:**
  - `0.17` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.13` **ADAMCR-0508** — A variable ending in TMF must contain "Time Imputation Flag" in the label
  - `0.12` **ADAMCR-0514** — A variable ending in STMF must contain "Start Time Imputation Flag" in the label

### ADAM-000256
- **Description:** Variable TRTDURD must have label 'Total Treatment Duration'.
- **Message:** Variable TRTDURD does not have the expected label 'Total Treatment Duration'.
- **Variables referenced:** `TRTDURD`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.08` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000257
- **Description:** Variable TRTDURM must have label 'Total Treatment Duration'.
- **Message:** Variable TRTDURM does not have the expected label 'Total Treatment Duration'.
- **Variables referenced:** `TRTDURM`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.08` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000258
- **Description:** Variable TRTDURY must have label 'Total Treatment Duration'.
- **Message:** Variable TRTDURY does not have the expected label 'Total Treatment Duration'.
- **Variables referenced:** `TRTDURY`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.08` **ADAMCR-0014** — A variable name does not start with a letter (A-Z)

### ADAM-000260
- **Description:** Variable TRTEDTF must have label 'Date of Last Exposure Imput.'.
- **Message:** Variable TRTEDTF does not have the expected label 'Date of Last Exposure Imput.'.
- **Variables referenced:** `TRTEDTF`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000262
- **Description:** Variable TRTETM must have label 'Time of Last Exposure to'.
- **Message:** Variable TRTETM does not have the expected label 'Time of Last Exposure to'.
- **Variables referenced:** `TRTETM`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000263
- **Description:** Variable TRTETMF must have label 'Time of Last Exposure Imput.'.
- **Message:** Variable TRTETMF does not have the expected label 'Time of Last Exposure Imput.'.
- **Variables referenced:** `TRTETMF`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000267
- **Description:** Variable TRTSDTF must have label 'Date of First Exposure Imput.'.
- **Message:** Variable TRTSDTF does not have the expected label 'Date of First Exposure Imput.'.
- **Variables referenced:** `TRTSDTF`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label
  - `0.16` **ADAMCR-0041** — A numeric variable with a suffix of DT does not have a date format
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000273
- **Description:** Variable TRTSTM must have label 'Time of First Exposure to'.
- **Message:** Variable TRTSTM does not have the expected label 'Time of First Exposure to'.
- **Variables referenced:** `TRTSTM`
- **Best conformance candidates:**
  - `0.19` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.10` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.10` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels

### ADAM-000274
- **Description:** Variable TRTSTMF must have label 'Time of First Exposure Imput.'.
- **Message:** Variable TRTSTMF does not have the expected label 'Time of First Exposure Imput.'.
- **Variables referenced:** `TRTSTMF`
- **Best conformance candidates:**
  - `0.16` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.09` **ADAMCR-0002** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical labels
  - `0.09` **ADAMCR-0001** — ADSL dataset does not exist

### ADAM-000276
- **Description:** Variable AAGE must be of type Num.
- **Message:** Variable AAGE is not of the expected type Num.
- **Variables referenced:** `AAGE`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000280
- **Description:** Variable ADT must be of type Num.
- **Message:** Variable ADT is not of the expected type Num.
- **Variables referenced:** `ADT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000281
- **Description:** Variable ADTF must be of type Char.
- **Message:** Variable ADTF is not of the expected type Char.
- **Variables referenced:** `ADTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000282
- **Description:** Variable ADTM must be of type Num.
- **Message:** Variable ADTM is not of the expected type Num.
- **Variables referenced:** `ADTM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000287
- **Description:** Variable AENTM must be of type Num.
- **Message:** Variable AENTM is not of the expected type Num.
- **Variables referenced:** `AENTM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000288
- **Description:** Variable AENTMF must be of type Char.
- **Message:** Variable AENTMF is not of the expected type Char.
- **Variables referenced:** `AENTMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000295
- **Description:** Variable APEREDTF must be of type Char.
- **Message:** Variable APEREDTF is not of the expected type Char.
- **Variables referenced:** `APEREDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000298
- **Description:** Variable APERETMF must be of type Char.
- **Message:** Variable APERETMF is not of the expected type Char.
- **Variables referenced:** `APERETMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000302
- **Description:** Variable APERSDTF must be of type Char.
- **Message:** Variable APERSDTF is not of the expected type Char.
- **Variables referenced:** `APERSDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000305
- **Description:** Variable APERSTMF must be of type Char.
- **Message:** Variable APERSTMF is not of the expected type Char.
- **Variables referenced:** `APERSTMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000311
- **Description:** Variable ASEQ must be of type Num.
- **Message:** Variable ASEQ is not of the expected type Num.
- **Variables referenced:** `ASEQ`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000314
- **Description:** Variable ASPREDT must be of type Num.
- **Message:** Variable ASPREDT is not of the expected type Num.
- **Variables referenced:** `ASPREDT`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0601** — On a given record, the value of ASPREDT is not equal to the value of variable PxxSwEDT where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000315
- **Description:** Variable ASPREDTF must be of type Char.
- **Message:** Variable ASPREDTF is not of the expected type Char.
- **Variables referenced:** `ASPREDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000316
- **Description:** Variable ASPREDTM must be of type Num.
- **Message:** Variable ASPREDTM is not of the expected type Num.
- **Variables referenced:** `ASPREDTM`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0603** — On a given record, the value of ASPREDTM is not equal to the value of variable PxxSwEDM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000317
- **Description:** Variable ASPRETM must be of type Num.
- **Message:** Variable ASPRETM is not of the expected type Num.
- **Variables referenced:** `ASPRETM`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0602** — On a given record, the value of ASPRETM is not equal to the value of variable PxxSwETM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000318
- **Description:** Variable ASPRETMF must be of type Char.
- **Message:** Variable ASPRETMF is not of the expected type Char.
- **Variables referenced:** `ASPRETMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000319
- **Description:** Variable ASPRSDT must be of type Num.
- **Message:** Variable ASPRSDT is not of the expected type Num.
- **Variables referenced:** `ASPRSDT`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0598** — On a given record, the value of ASPRSDT is not equal to the value of variable PxxSwSDT where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000320
- **Description:** Variable ASPRSDTF must be of type Char.
- **Message:** Variable ASPRSDTF is not of the expected type Char.
- **Variables referenced:** `ASPRSDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000321
- **Description:** Variable ASPRSDTM must be of type Num.
- **Message:** Variable ASPRSDTM is not of the expected type Num.
- **Variables referenced:** `ASPRSDTM`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0600** — On a given record, the value of ASPRSDTM is not equal to the value of variable PxxSwSDM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000322
- **Description:** Variable ASPRSTM must be of type Num.
- **Message:** Variable ASPRSTM is not of the expected type Num.
- **Variables referenced:** `ASPRSTM`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0599** — On a given record, the value of ASPRSTM is not equal to the value of variable PxxSwSTM where xx equals the value of APERIOD and w equals the value of ASPER
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000324
- **Description:** Variable ASTDTF must be of type Char.
- **Message:** Variable ASTDTF is not of the expected type Char.
- **Variables referenced:** `ASTDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000327
- **Description:** Variable ASTTM must be of type Num.
- **Message:** Variable ASTTM is not of the expected type Num.
- **Variables referenced:** `ASTTM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000328
- **Description:** Variable ASTTMF must be of type Char.
- **Message:** Variable ASTTMF is not of the expected type Char.
- **Variables referenced:** `ASTTMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000329
- **Description:** Variable ATM must be of type Num.
- **Message:** Variable ATM is not of the expected type Num.
- **Variables referenced:** `ATM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000330
- **Description:** Variable ATMF must be of type Char.
- **Message:** Variable ATMF is not of the expected type Char.
- **Variables referenced:** `ATMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000341
- **Description:** Variable ATPTREF must be of type Char.
- **Message:** Variable ATPTREF is not of the expected type Char.
- **Variables referenced:** `ATPTREF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000346
- **Description:** Variable AWHI must be of type Num.
- **Message:** Variable AWHI is not of the expected type Num.
- **Variables referenced:** `AWHI`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000347
- **Description:** Variable AWLO must be of type Num.
- **Message:** Variable AWLO is not of the expected type Num.
- **Variables referenced:** `AWLO`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000348
- **Description:** Variable AWRANGE must be of type Char.
- **Message:** Variable AWRANGE is not of the expected type Char.
- **Variables referenced:** `AWRANGE`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000351
- **Description:** Variable AWU must be of type Char.
- **Message:** Variable AWU is not of the expected type Char.
- **Variables referenced:** `AWU`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000353
- **Description:** Variable BASEC must be of type Char.
- **Message:** Variable BASEC is not of the expected type Char.
- **Variables referenced:** `BASEC`
- **Best conformance candidates:**
  - `0.26` **ADAMCR-0128** — Within a given value of PARAMCD for a subject, BASEC is populated and there is not at least one record with ABLFL equal to Y
  - `0.25` **ADAMCR-0692** — Within a given value of PARAMCD for a combination of device and subject, BASEC is populated and there is not at least one record with ABLFL equal to Y
  - `0.25` **ADAMCR-0733** — Within a given value of PARAMCD for a subject, there is more than one value of BASEC for a given value of BASE, considering only those rows on which both variables are populated

### ADAM-000354
- **Description:** Variable BASETYPE must be of type Char.
- **Message:** Variable BASETYPE is not of the expected type Char.
- **Variables referenced:** `BASETYPE`
- **Best conformance candidates:**
  - `0.25` **ADAMCR-0154** — Within a given PARAMCD and BASETYPE for a subject, more than one record has ABLFL equal to Y
  - `0.25` **ADAMCR-0790** — BASETYPE is populated, ByIND is populated, and ByIND is not equal to AyIND where ABLFL is equal to Y for a given value of PARAMCD and BASETYPE for a subject
  - `0.25` **ADAMCR-0155** — Within a given PARAMCD for a subject, more than one record has ABLFL equal to Y and BASETYPE is not present

### ADAM-000364
- **Description:** Variable CNSDTDSC must be of type Char.
- **Message:** Variable CNSDTDSC is not of the expected type Char.
- **Variables referenced:** `CNSDTDSC`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000367
- **Description:** Variable COMPLPFL must be of type Char.
- **Message:** Variable COMPLPFL is not of the expected type Char.
- **Variables referenced:** `COMPLPFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000368
- **Description:** Variable COMPLRFL must be of type Char.
- **Message:** Variable COMPLRFL is not of the expected type Char.
- **Variables referenced:** `COMPLRFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000369
- **Description:** Variable DCSREAS must be of type Char.
- **Message:** Variable DCSREAS is not of the expected type Char.
- **Variables referenced:** `DCSREAS`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000370
- **Description:** Variable DCSREASP must be of type Char.
- **Message:** Variable DCSREASP is not of the expected type Char.
- **Variables referenced:** `DCSREASP`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000371
- **Description:** Variable DCTREASP must be of type Char.
- **Message:** Variable DCTREASP is not of the expected type Char.
- **Variables referenced:** `DCTREASP`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000372
- **Description:** Variable DOSCUMA must be of type Num.
- **Message:** Variable DOSCUMA is not of the expected type Num.
- **Variables referenced:** `DOSCUMA`
- **Best conformance candidates:**
  - `0.28` **ADAMCR-0364** — DOSEON or DOSCUMA is present and DOSEU is not present
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000373
- **Description:** Variable DOSCUMP must be of type Num.
- **Message:** Variable DOSCUMP is not of the expected type Num.
- **Variables referenced:** `DOSCUMP`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000375
- **Description:** Variable DOSEP must be of type Num.
- **Message:** Variable DOSEP is not of the expected type Num.
- **Variables referenced:** `DOSEP`
- **Best conformance candidates:**
  - `0.29` **ADAMCR-0666** — DOSPCTDF is populated and DOSEP and DOSEA are not populated
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000379
- **Description:** Variable DTHDT must be of type Num.
- **Message:** Variable DTHDT is not of the expected type Num.
- **Variables referenced:** `DTHDT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000380
- **Description:** Variable DTHDTF must be of type Char.
- **Message:** Variable DTHDTF is not of the expected type Char.
- **Variables referenced:** `DTHDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000381
- **Description:** Variable DTYPE must be of type Char.
- **Message:** Variable DTYPE is not of the expected type Char.
- **Variables referenced:** `DTYPE`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000382
- **Description:** Variable ENRLDT must be of type Num.
- **Message:** Variable ENRLDT is not of the expected type Num.
- **Variables referenced:** `ENRLDT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000384
- **Description:** Variable EOSDT must be of type Num.
- **Message:** Variable EOSDT is not of the expected type Num.
- **Variables referenced:** `EOSDT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000385
- **Description:** Variable EOSSTT must be of type Char.
- **Message:** Variable EOSSTT is not of the expected type Char.
- **Variables referenced:** `EOSSTT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000386
- **Description:** Variable EOTSTT must be of type Char.
- **Message:** Variable EOTSTT is not of the expected type Char.
- **Variables referenced:** `EOTSTT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000387
- **Description:** Variable EVNTDESC must be of type Char.
- **Message:** Variable EVNTDESC is not of the expected type Char.
- **Variables referenced:** `EVNTDESC`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000389
- **Description:** Variable FASPFL must be of type Char.
- **Message:** Variable FASPFL is not of the expected type Char.
- **Variables referenced:** `FASPFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000390
- **Description:** Variable FASRFL must be of type Char.
- **Message:** Variable FASRFL is not of the expected type Char.
- **Variables referenced:** `FASRFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000392
- **Description:** Variable ITTPFL must be of type Char.
- **Message:** Variable ITTPFL is not of the expected type Char.
- **Variables referenced:** `ITTPFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000393
- **Description:** Variable ITTRFL must be of type Char.
- **Message:** Variable ITTRFL is not of the expected type Char.
- **Variables referenced:** `ITTRFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000394
- **Description:** Variable LSTALVDT must be of type Num.
- **Message:** Variable LSTALVDT is not of the expected type Num.
- **Variables referenced:** `LSTALVDT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000404
- **Description:** Variable PHEDTF must be of type Char.
- **Message:** Variable PHEDTF is not of the expected type Char.
- **Variables referenced:** `PHEDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000407
- **Description:** Variable PHETMF must be of type Char.
- **Message:** Variable PHETMF is not of the expected type Char.
- **Variables referenced:** `PHETMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000409
- **Description:** Variable PHSDTF must be of type Char.
- **Message:** Variable PHSDTF is not of the expected type Char.
- **Variables referenced:** `PHSDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000412
- **Description:** Variable PHSTMF must be of type Char.
- **Message:** Variable PHSTMF is not of the expected type Char.
- **Variables referenced:** `PHSTMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000414
- **Description:** Variable PPROTPFL must be of type Char.
- **Message:** Variable PPROTPFL is not of the expected type Char.
- **Variables referenced:** `PPROTPFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000415
- **Description:** Variable PPROTRFL must be of type Char.
- **Message:** Variable PPROTRFL is not of the expected type Char.
- **Variables referenced:** `PPROTRFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000416
- **Description:** Variable R2BASE must be of type Num.
- **Message:** Variable R2BASE is not of the expected type Num.
- **Variables referenced:** `R2BASE`
- **Best conformance candidates:**
  - `0.22` **ADAMCR-0132** — R2BASE is not equal to AVAL divided by BASE
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.07` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types

### ADAM-000420
- **Description:** Variable RFICDT must be of type Num.
- **Message:** Variable RFICDT is not of the expected type Num.
- **Variables referenced:** `RFICDT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000422
- **Description:** Variable SAFPFL must be of type Char.
- **Message:** Variable SAFPFL is not of the expected type Char.
- **Variables referenced:** `SAFPFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000423
- **Description:** Variable SAFRFL must be of type Char.
- **Message:** Variable SAFRFL is not of the expected type Char.
- **Variables referenced:** `SAFRFL`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000428
- **Description:** Variable SRCVAR must be of type Char.
- **Message:** Variable SRCVAR is not of the expected type Char.
- **Variables referenced:** `SRCVAR`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000429
- **Description:** Variable STARTDT must be of type Num.
- **Message:** Variable STARTDT is not of the expected type Num.
- **Variables referenced:** `STARTDT`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000430
- **Description:** Variable STARTDTF must be of type Char.
- **Message:** Variable STARTDTF is not of the expected type Char.
- **Variables referenced:** `STARTDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000431
- **Description:** Variable STARTDTM must be of type Num.
- **Message:** Variable STARTDTM is not of the expected type Num.
- **Variables referenced:** `STARTDTM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000432
- **Description:** Variable STARTTMF must be of type Char.
- **Message:** Variable STARTTMF is not of the expected type Char.
- **Variables referenced:** `STARTTMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000441
- **Description:** Variable TRTDURD must be of type Num.
- **Message:** Variable TRTDURD is not of the expected type Num.
- **Variables referenced:** `TRTDURD`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000442
- **Description:** Variable TRTDURM must be of type Num.
- **Message:** Variable TRTDURM is not of the expected type Num.
- **Variables referenced:** `TRTDURM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000443
- **Description:** Variable TRTDURY must be of type Num.
- **Message:** Variable TRTDURY is not of the expected type Num.
- **Variables referenced:** `TRTDURY`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000445
- **Description:** Variable TRTEDTF must be of type Char.
- **Message:** Variable TRTEDTF is not of the expected type Char.
- **Variables referenced:** `TRTEDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000447
- **Description:** Variable TRTETM must be of type Num.
- **Message:** Variable TRTETM is not of the expected type Num.
- **Variables referenced:** `TRTETM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000448
- **Description:** Variable TRTETMF must be of type Char.
- **Message:** Variable TRTETMF is not of the expected type Char.
- **Variables referenced:** `TRTETMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000452
- **Description:** Variable TRTSDTF must be of type Char.
- **Message:** Variable TRTSDTF is not of the expected type Char.
- **Variables referenced:** `TRTSDTF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000458
- **Description:** Variable TRTSTM must be of type Num.
- **Message:** Variable TRTSTM is not of the expected type Num.
- **Variables referenced:** `TRTSTM`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000459
- **Description:** Variable TRTSTMF must be of type Char.
- **Message:** Variable TRTSTMF is not of the expected type Char.
- **Variables referenced:** `TRTSTMF`
- **Best conformance candidates:**
  - `0.07` **ADAMCR-0199** — A variable is present in ADaM with the same name as a variable present in SDTM but the variables do not have identical data types
  - `0.06` **ADAMCR-0200** — A variable is present in ADaM with the same name as a variable defined in the ADaM IG but the variables do not have identical data types
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG

### ADAM-000461
- **Description:** Collected Duration (--DUR) value should not be negative.
- **Message:** Negative value for --DUR.
- **Variables referenced:** `DUR`
- **Best conformance candidates:**
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG
  - `0.00` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present
  - `0.00` **ADAMCR-0895** — A variable with a prefix of CRIT, a suffix of FL and containing either a one-digit or two-digit number (CRITyFL) is present and a variable with the same root without a suffix of FL (CRITy) is not present

### ADAM-000462
- **Description:** Text variable in submitted dataset should not contain  '.' as an entire value.
- **Message:** Text variable contains '.' as an entire value.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.08` **ADAMCR-0015** — A variable name contains a character other than letters (A-Z), underscores (_), or numerals (0-9)
  - `0.08` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.08` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label

### ADAM-000463
- **Description:** Text variable in submitted dataset should not contain leading spaces ' '.
- **Message:** Text variable contains leading spaces.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.08` **ADAMCR-0015** — A variable name contains a character other than letters (A-Z), underscores (_), or numerals (0-9)
  - `0.07` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.07` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label

### ADAM-000464
- **Description:** Part A: Raise an error when a Required variable is not present in the dataset.
- **Message:** At least one required variable is missing from dataset
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.15` **ADAMCR-0496** — A dataset name does not start with "AD" when dataset class is not missing
  - `0.13` **ADAMCR-0689** — SPDEVID is not present within dataset
  - `0.12` **ADAMCR-0497** — A dataset name starts with "AD" when the dataset class is missing

### ADAM-000465
- **Description:** Raise an error when an expected variable is not present in the dataset.
- **Message:** At least one expected variable is missing from dataset
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.18` **ADAMCR-0496** — A dataset name does not start with "AD" when dataset class is not missing
  - `0.16` **ADAMCR-0689** — SPDEVID is not present within dataset
  - `0.13` **ADAMCR-0497** — A dataset name starts with "AD" when the dataset class is missing

### ADAM-000466
- **Description:** The submitted dataset is larger than 5 GB
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.10` **ADAMCR-0813** — Within a dataset, there is more than one value of USUBJID for a given value of USUBJIDN, considering only those rows on which both variables are populated
  - `0.10` **ADAMCR-0812** — Within a dataset, there is more than one value of USUBJIDN for a given value of USUBJID, considering only those rows on which both variables are populated.
  - `0.10` **ADAMCR-0858** — Within a dataset, there is more than one value of ACYCLE for a given value of ACYCLEC, considering only those rows on which both variables are populated

### ADAM-000467
- **Description:** Raise an error when variables are not in the specified order
- **Message:** Variables are not in the correct order as shown in SDTM for the observation class.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.08` **ADAMCR-0716** — A variable with a suffix of TM is not a numeric variable excluding SDTM variables with a suffix of ELTM
  - `0.08` **ADAMCR-0794** — On a given record, a variable with a suffix of SDTM has a value greater than a value of a variable with the same root and a suffix of EDTM, and both variables are populated
  - `0.07` **ADAMCR-0122** — The value of a variable with a suffix of SDTM is greater than the value of a variable with the same root and a suffix of EDTM, considering only rows on which both variables are populated

### ADAM-000468
- **Description:** Raise an error when a variable is not an allowed variable for an Observation Class
- **Message:** Variables not listed in the Model List of Allowed Variables for Observation Class should be in SUPPQUAL.
- **Variables referenced:** `SUPPQUAL`
- **Best conformance candidates:**
  - `0.06` **ADAMCR-0496** — A dataset name does not start with "AD" when dataset class is not missing
  - `0.04` **ADAMCR-0709** — Labels for ADaM variables do not match the standard labels for ADaM variables listed in the implementation guide that cannot be modified (with the exception of (1) variables whose names contain indexes "w", “y”, or “zz”; and (2) variable labels with asterisks (*), braces ({...}), and ellipses (...) indicated for sponsor appropriate text)
  - `0.02` **ADAMCR-0718** — There is more than one value of TRTxxP for a given value of TRTxxPN, considering only those rows on which both variables are populated

### ADAM-000469
- **Description:** Raise an error when a dataset has no records.
- **Message:** Dataset has no record.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.08` **ADAMCR-0689** — SPDEVID is not present within dataset
  - `0.07` **ADAMCR-0001** — ADSL dataset does not exist
  - `0.06` **ADAMCR-0501** — On a given record, ASPER is populated and APERIOD is not populated

### ADAM-000470
- **Description:** Raise an error when a variable label is not in title case
- **Message:** Variable label is not in title case.
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.08` **ADAMCR-0016** — The length of a variable label is greater than 40 characters
  - `0.08` **ADAMCR-0504** — A variable ending in TM must contain "Time" in the label
  - `0.08` **ADAMCR-0503** — A variable ending in DT must contain "Date" in the label

### ADAM-000471
- **Description:** Part B: Raise an error when a Required variable is null.
- **Message:** At least one Required variable has a null value
- **Variables referenced:** `—`
- **Best conformance candidates:**
  - `0.00` **ADAMCR-0897** — A non-missing value of TRTA is not equal to at least one value of the character actual treatment variables in ADSL defined in the IG
  - `0.00` **ADAMCR-0896** — AWU is present and AWLO, AWHI, AWTARGET, and AWTDIFF are not present
  - `0.00` **ADAMCR-0895** — A variable with a prefix of CRIT, a suffix of FL and containing either a one-digit or two-digit number (CRITyFL) is present and a variable with the same root without a suffix of FL (CRITy) is not present


## Weak match (manual review — top candidate may or may not actually cover)

### ADAM-000021
- **Description:** ADaM dataset names must start with 'AD'.
- **Message:** ADaM dataset name does not follow the naming convention 'ADxxxxxx'.
- **Top match:** `0.31` **ADAMCR-0496** — A dataset name does not start with "AD" when dataset class is not missing

### ADAM-000023
- **Description:** ADaM variable names must not exceed 8 characters.
- **Message:** Variable name exceeds 8 characters.
- **Top match:** `0.40` **ADAMCR-0013** — The length of a variable name exceeds 8 characters

### ADAM-000025
- **Description:** ADaM character variables must not exceed 200 characters in length.
- **Message:** Character variable exceeds 200 characters in length.
- **Top match:** `0.42` **ADAMCR-0013** — The length of a variable name exceeds 8 characters

### ADAM-000041
- **Description:** Character flag variables (*FL) must have values of Y, N, or null.
- **Message:** Flag variable (*FL) has a value other than Y, N, or null.
- **Top match:** `0.31` **ADAMCR-0005** — A variable with a suffix of FL has a value that is not Y, N or null

### ADAM-000043
- **Description:** TRT01PN cannot be present unless TRT01P is also present.
- **Message:** TRT01PN is present but TRT01P is not. The primary variable must be present when the secondary variable exists.
- **Top match:** `0.34` **ADAMCR-0072** — TRT01P is not present within ADSL

### ADAM-000048
- **Description:** Date of first exposure (TRTSDT) should be on or before date of last exposure (TRTEDT).
- **Message:** TRTSDT (date of first exposure) is after TRTEDT (date of last exposure).
- **Top match:** `0.35` **ADAMCR-0084** — TRTEDT is not equal to the maximum value of all TRxxEDT variables

### ADAM-000051
- **Description:** AVALC must be character type.
- **Message:** AVALC must be a character variable.
- **Top match:** `0.41` **ADAMCR-0252** — AVAL is present or AVALC is present

### ADAM-000052
- **Description:** BASE must be present when CHG is present in a BDS dataset.
- **Message:** CHG (Change from Baseline) is present but BASE (Baseline Value) is not. BASE is required when CHG exists.
- **Top match:** `0.39` **ADAMCR-0223** — Within a given value of PARAMCD for a subject, CHG is populated and is not equal to AVAL - BASE

### ADAM-000054
- **Description:** CHG should be null for baseline records (where ABLFL = 'Y').
- **Message:** CHG is populated on a baseline record (ABLFL = 'Y'). CHG should be null for baseline records.
- **Top match:** `0.36` **ADAMCR-0494** — ABLFL is equal to "N"

### ADAM-000055
- **Description:** PCT (Percent Change from Baseline) should be null for baseline records.
- **Message:** PCT is populated on a baseline record (ABLFL = 'Y'). PCT should be null for baseline records.
- **Top match:** `0.35` **ADAMCR-0494** — ABLFL is equal to "N"

### ADAM-000059
- **Description:** ADT must be present when ADY is present in a dataset.
- **Message:** ADY (Analysis Relative Day) is present but ADT (Analysis Date) is not. ADT is conditionally required when ADY exists.
- **Top match:** `0.43` **ADAMCR-0506** — A variable ending in ADY must contain "Relative Day" in the label

### ADAM-000067
- **Description:** TRTP (Planned Treatment) is required in BDS datasets used for analysis by treatment.
- **Message:** Neither TRTP nor TRTA is present. At least one treatment variable is expected in BDS datasets used for analysis by treatment.
- **Top match:** `0.39` **ADAMCR-0720** — A non-missing value of TRTP is not equal to at least one value of the character planned treatment variables in ADSL defined in the IG

### ADAM-000068
- **Description:** TRTP must be present when TRTA is present in a BDS dataset.
- **Message:** TRTA is present but TRTP is not. TRTP is required when TRTA exists.
- **Top match:** `0.40` **ADAMCR-0764** — TRTPGy is present and TRTA is present but TRTAGy is not present, where y is an integer [1-99, not zero-padded]

### ADAM-000072
- **Description:** When SRCVAR exists, SRCDOM must also exist for datapoint traceability.
- **Message:** SRCVAR is present but SRCDOM is not. Both are needed for datapoint traceability.
- **Top match:** `0.37` **ADAMCR-0653** — SRCDOM is present

### ADAM-000073
- **Description:** When SRCSEQ exists, SRCDOM must also exist.
- **Message:** SRCSEQ is present but SRCDOM is not. SRCDOM is required when SRCSEQ exists.
- **Top match:** `0.42` **ADAMCR-0654** — SRCSEQ is present

### ADAM-000075
- **Description:** BASETYPE is required when there is more than one definition of baseline for a given parameter in the dataset.
- **Message:** BASE is present but BASETYPE is not. BASETYPE is conditionally required when there are multiple baseline definitions.
- **Top match:** `0.36` **ADAMCR-0735** — Within a given value of PARAMCD where either BASE or BASEC are populated, BASETYPE is populated for at least one record and is not populated for at least one record

### ADAM-000079
- **Description:** BASE must be a numeric variable.
- **Top match:** `0.36` **ADAMCR-0749** — Within a given value of PARAMCD, there is more than one value of BASECATy for a given value of BASE and y, where y is a single-digit integer [1-9]

### ADAM-000080
- **Description:** CHG must be a numeric variable.
- **Top match:** `0.37` **ADAMCR-0750** — Within a given value of PARAMCD, there is more than one value of CHGCATy for a given value of CHG and y, where y is an integer [1-99, not zero-padded]

### ADAM-000081
- **Description:** AAGE is required if analysis age differs from DM.AGE.
- **Message:** AAGE (Analysis Age) is not present. AAGE is required if age is calculated differently than DM.AGE.
- **Top match:** `0.37` **ADAMCR-0049** — AGE is not present within ADSL

### ADAM-000094
- **Description:** Variable ACTARM must have label 'Description of Actual Arm'.
- **Message:** Variable ACTARM does not have the expected label 'Description of Actual Arm'.
- **Top match:** `0.37` **ADAMCR-0367** — The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.ACTARM is not equal to DM.ACTARM

### ADAM-000100
- **Description:** Variable AENDTM must have label 'Analysis End Datetime'.
- **Message:** Variable AENDTM does not have the expected label 'Analysis End Datetime'.
- **Top match:** `0.36` **ADAMCR-0362** — The value of ASTDTM is greater than the value of AENDTM, considering only those rows on which both variables are populated

### ADAM-000106
- **Description:** Variable ANRIND must have label 'Analysis Reference Range'.
- **Message:** Variable ANRIND does not have the expected label 'Analysis Reference Range'.
- **Top match:** `0.37` **ADAMCR-0166** — BNRIND is present and ANRIND is not present

### ADAM-000107
- **Description:** Variable ANRLO must have label 'Analysis Normal Range Lower'.
- **Message:** Variable ANRLO does not have the expected label 'Analysis Normal Range Lower'.
- **Top match:** `0.36` **ADAMCR-0343** — Within a parameter, there is more than one value of ANRLOC for a given value of ANRLO

### ADAM-000108
- **Description:** Variable ANRLOC must have label 'Analysis Normal Range Lower'.
- **Message:** Variable ANRLOC does not have the expected label 'Analysis Normal Range Lower'.
- **Top match:** `0.36` **ADAMCR-0343** — Within a parameter, there is more than one value of ANRLOC for a given value of ANRLO

### ADAM-000109
- **Description:** Variable APEREDT must have label 'Period End Date'.
- **Message:** Variable APEREDT does not have the expected label 'Period End Date'.
- **Top match:** `0.36` **ADAMCR-0595** — On a given record, the value of APEREDT is not equal to the value of variable APxxEDT where xx equals the value of APERIOD

### ADAM-000111
- **Description:** Variable APEREDTM must have label 'Period End Datetime'.
- **Message:** Variable APEREDTM does not have the expected label 'Period End Datetime'.
- **Top match:** `0.36` **ADAMCR-0597** — On a given record, the value of APEREDTM is not equal to the value of variable APxxEDTM where xx equals the value of APERIOD

### ADAM-000112
- **Description:** Variable APERETM must have label 'Period End Time'.
- **Message:** Variable APERETM does not have the expected label 'Period End Time'.
- **Top match:** `0.36` **ADAMCR-0596** — On a given record, the value of APERETM is not equal to the value of variable APxxETM where xx equals the value of APERIOD

### ADAM-000115
- **Description:** Variable APERIODC must have label 'Period (C)'.
- **Message:** Variable APERIODC does not have the expected label 'Period (C)'.
- **Top match:** `0.38` **ADAMCR-0539** — APERIODC is present and APERIOD is not present

### ADAM-000116
- **Description:** Variable APERSDT must have label 'Period Start Date'.
- **Message:** Variable APERSDT does not have the expected label 'Period Start Date'.
- **Top match:** `0.37` **ADAMCR-0592** — On a given record, the value of APERSDT is not equal to the value of variable APxxSDT where xx equals the value of APERIOD

### ADAM-000118
- **Description:** Variable APERSDTM must have label 'Period Start Datetime'.
- **Message:** Variable APERSDTM does not have the expected label 'Period Start Datetime'.
- **Top match:** `0.36` **ADAMCR-0594** — On a given record, the value of APERSDTM is not equal to the value of variable APxxSDTM where xx equals the value of APERIOD

### ADAM-000119
- **Description:** Variable APERSTM must have label 'Period Start Time'.
- **Message:** Variable APERSTM does not have the expected label 'Period Start Time'.
- **Top match:** `0.37` **ADAMCR-0593** — On a given record, the value of APERSTM is not equal to the value of variable APxxSTM where xx equals the value of APERIOD

### ADAM-000122
- **Description:** Variable APHASEN must have label 'Phase (N)'.
- **Message:** Variable APHASEN does not have the expected label 'Phase (N)'.
- **Top match:** `0.37` **ADAMCR-0540** — APHASEN is present and APHASE is not present

### ADAM-000123
- **Description:** Variable ARELTM must have label 'Analysis Relative Time'.
- **Message:** Variable ARELTM does not have the expected label 'Analysis Relative Time'.
- **Top match:** `0.41` **ADAMCR-0042** — A numeric variable with a suffix of TM does not have a time format, excluding ARELTM and variables with a suffix of DTM

### ADAM-000124
- **Description:** Variable ARELTMU must have label 'Analysis Relative Time Unit'.
- **Message:** Variable ARELTMU does not have the expected label 'Analysis Relative Time Unit'.
- **Top match:** `0.37` **ADAMCR-0112** — ARELTM is populated and ARELTMU is not populated

### ADAM-000128
- **Description:** Variable ASPERC must have label 'Subperiod within Period (C)'.
- **Message:** Variable ASPERC does not have the expected label 'Subperiod within Period (C)'.
- **Top match:** `0.37` **ADAMCR-0541** — ASPERC is present and ASPER is not present

### ADAM-000136
- **Description:** Variable ASPRSDTM must have label 'Subperiod Start Datetime'.
- **Message:** Variable ASPRSDTM does not have the expected label 'Subperiod Start Datetime'.
- **Top match:** `0.32` **ADAMCR-0511** — A variable ending in SDTM must contain "Start Datetime" in the label

### ADAM-000140
- **Description:** Variable ASTDTM must have label 'Analysis Start Datetime'.
- **Message:** Variable ASTDTM does not have the expected label 'Analysis Start Datetime'.
- **Top match:** `0.36` **ADAMCR-0362** — The value of ASTDTM is greater than the value of AENDTM, considering only those rows on which both variables are populated

### ADAM-000146
- **Description:** Variable ATOXDSCH must have label 'Analysis Toxicity Description'.
- **Message:** Variable ATOXDSCH does not have the expected label 'Analysis Toxicity Description'.
- **Top match:** `0.36` **ADAMCR-0405** — Within a subject, there is more than one value of ATOXDSCH for a given value of PARAM, considering only those rows on which both variables are populated

### ADAM-000147
- **Description:** Variable ATOXDSCL must have label 'Analysis Toxicity Description'.
- **Message:** Variable ATOXDSCL does not have the expected label 'Analysis Toxicity Description'.
- **Top match:** `0.36` **ADAMCR-0403** — Within a subject, there is more than one value of ATOXDSCL for a given value of PARAM, considering only those rows on which both variables are populated

### ADAM-000148
- **Description:** Variable ATOXGR must have label 'Analysis Toxicity Grade'.
- **Message:** Variable ATOXGR does not have the expected label 'Analysis Toxicity Grade'.
- **Top match:** `0.37` **ADAMCR-0163** — BTOXGR is present and ATOXGR is not present

### ADAM-000149
- **Description:** Variable ATOXGRH must have label 'Analysis Toxicity Grade High'.
- **Message:** Variable ATOXGRH does not have the expected label 'Analysis Toxicity Grade High'.
- **Top match:** `0.36` **ADAMCR-0524** — ATOXGRHN is present and ATOXGRH is not present

### ADAM-000150
- **Description:** Variable ATOXGRHN must have label 'Analysis Toxicity Grade High'.
- **Message:** Variable ATOXGRHN does not have the expected label 'Analysis Toxicity Grade High'.
- **Top match:** `0.36` **ADAMCR-0524** — ATOXGRHN is present and ATOXGRH is not present

### ADAM-000151
- **Description:** Variable ATOXGRL must have label 'Analysis Toxicity Grade Low'.
- **Message:** Variable ATOXGRL does not have the expected label 'Analysis Toxicity Grade Low'.
- **Top match:** `0.36` **ADAMCR-0523** — ATOXGRLN is present and ATOXGRL is not present

### ADAM-000152
- **Description:** Variable ATOXGRLN must have label 'Analysis Toxicity Grade Low'.
- **Message:** Variable ATOXGRLN does not have the expected label 'Analysis Toxicity Grade Low'.
- **Top match:** `0.36` **ADAMCR-0523** — ATOXGRLN is present and ATOXGRL is not present

### ADAM-000153
- **Description:** Variable ATOXGRN must have label 'Analysis Toxicity Grade (N)'.
- **Message:** Variable ATOXGRN does not have the expected label 'Analysis Toxicity Grade (N)'.
- **Top match:** `0.37` **ADAMCR-0536** — ATOXGRN is present and ATOXGR is not present

### ADAM-000154
- **Description:** Variable ATPT must have label 'Analysis Timepoint'.
- **Message:** Variable ATPT does not have the expected label 'Analysis Timepoint'.
- **Top match:** `0.38` **ADAMCR-0542** — ATPTN is present and ATPT is not present

### ADAM-000155
- **Description:** Variable ATPTN must have label 'Analysis Timepoint (N)'.
- **Message:** Variable ATPTN does not have the expected label 'Analysis Timepoint (N)'.
- **Top match:** `0.37` **ADAMCR-0542** — ATPTN is present and ATPT is not present

### ADAM-000158
- **Description:** Variable AVALC must have label 'Analysis Value (C)'.
- **Message:** Variable AVALC does not have the expected label 'Analysis Value (C)'.
- **Top match:** `0.38` **ADAMCR-0252** — AVAL is present or AVALC is present

### ADAM-000160
- **Description:** Variable AVISITN must have label 'Analysis Visit (N)'.
- **Message:** Variable AVISITN does not have the expected label 'Analysis Visit (N)'.
- **Top match:** `0.37` **ADAMCR-0548** — AVISITN is present and AVISIT is not present

### ADAM-000164
- **Description:** Variable AWTARGET must have label 'Analysis Window Target'.
- **Message:** Variable AWTARGET does not have the expected label 'Analysis Window Target'.
- **Top match:** `0.38` **ADAMCR-0159** — AWTDIFF is populated and AWTARGET is not populated

### ADAM-000165
- **Description:** Variable AWTDIFF must have label 'Analysis Window Diff from'.
- **Message:** Variable AWTDIFF does not have the expected label 'Analysis Window Diff from'.
- **Top match:** `0.38` **ADAMCR-0159** — AWTDIFF is populated and AWTARGET is not populated

### ADAM-000167
- **Description:** Variable BASE must have label 'Baseline Value'.
- **Message:** Variable BASE does not have the expected label 'Baseline Value'.
- **Top match:** `0.35` **ADAMCR-0749** — Within a given value of PARAMCD, there is more than one value of BASECATy for a given value of BASE and y, where y is a single-digit integer [1-9]

### ADAM-000170
- **Description:** Variable BCHG must have label 'Change to Baseline'.
- **Message:** Variable BCHG does not have the expected label 'Change to Baseline'.
- **Top match:** `0.36` **ADAMCR-0583** — Within a given value of PARAMCD, there is more than one value of BCHGCATy for a given value of BCHG and y, where y is an integer [1-99, not zero-padded]

### ADAM-000171
- **Description:** Variable BNRIND must have label 'Baseline Reference Range'.
- **Message:** Variable BNRIND does not have the expected label 'Baseline Reference Range'.
- **Top match:** `0.37` **ADAMCR-0167** — BNRIND is present and ABLFL is not present

### ADAM-000172
- **Description:** Variable BTOXGR must have label 'Baseline Toxicity Grade'.
- **Message:** Variable BTOXGR does not have the expected label 'Baseline Toxicity Grade'.
- **Top match:** `0.37` **ADAMCR-0164** — BTOXGR is present and ABLFL is not present

### ADAM-000173
- **Description:** Variable BTOXGRH must have label 'Baseline Toxicity Grade High'.
- **Message:** Variable BTOXGRH does not have the expected label 'Baseline Toxicity Grade High'.
- **Top match:** `0.36` **ADAMCR-0549** — BTOXGRHN is present and BTOXGRH is not present

### ADAM-000174
- **Description:** Variable BTOXGRHN must have label 'Baseline Toxicity Grade High'.
- **Message:** Variable BTOXGRHN does not have the expected label 'Baseline Toxicity Grade High'.
- **Top match:** `0.36` **ADAMCR-0549** — BTOXGRHN is present and BTOXGRH is not present

### ADAM-000175
- **Description:** Variable BTOXGRL must have label 'Baseline Toxicity Grade Low'.
- **Message:** Variable BTOXGRL does not have the expected label 'Baseline Toxicity Grade Low'.
- **Top match:** `0.36` **ADAMCR-0550** — BTOXGRLN is present and BTOXGRL is not present

### ADAM-000176
- **Description:** Variable BTOXGRLN must have label 'Baseline Toxicity Grade Low'.
- **Message:** Variable BTOXGRLN does not have the expected label 'Baseline Toxicity Grade Low'.
- **Top match:** `0.36` **ADAMCR-0550** — BTOXGRLN is present and BTOXGRL is not present

### ADAM-000177
- **Description:** Variable BTOXGRN must have label 'Baseline Toxicity Grade (N)'.
- **Message:** Variable BTOXGRN does not have the expected label 'Baseline Toxicity Grade (N)'.
- **Top match:** `0.37` **ADAMCR-0522** — BTOXGRN is present and BTOXGR is not present

### ADAM-000178
- **Description:** Variable CHG must have label 'Change from Baseline'.
- **Message:** Variable CHG does not have the expected label 'Change from Baseline'.
- **Top match:** `0.36` **ADAMCR-0750** — Within a given value of PARAMCD, there is more than one value of CHGCATy for a given value of CHG and y, where y is an integer [1-99, not zero-padded]

### ADAM-000192
- **Description:** Variable DTHCAUS must have label 'Cause of Death'.
- **Message:** Variable DTHCAUS does not have the expected label 'Cause of Death'.
- **Top match:** `0.38` **ADAMCR-0551** — DTHCAUSN is present and DTHCAUS is not present

### ADAM-000193
- **Description:** Variable DTHCAUSN must have label 'Cause of Death (N)'.
- **Message:** Variable DTHCAUSN does not have the expected label 'Cause of Death (N)'.
- **Top match:** `0.37` **ADAMCR-0551** — DTHCAUSN is present and DTHCAUS is not present

### ADAM-000210
- **Description:** Variable LVOTFL must have label 'Last Value On Treatment'.
- **Message:** Variable LVOTFL does not have the expected label 'Last Value On Treatment'.
- **Top match:** `0.38` **ADAMCR-0569** — LVOTFN is present and LVOTFL is not present

### ADAM-000211
- **Description:** Variable LVOTFN must have label 'Last Value On Treatment'.
- **Message:** Variable LVOTFN does not have the expected label 'Last Value On Treatment'.
- **Top match:** `0.38` **ADAMCR-0569** — LVOTFN is present and LVOTFL is not present

### ADAM-000215
- **Description:** Variable PARAMN must have label 'Parameter (N)'.
- **Message:** Variable PARAMN does not have the expected label 'Parameter (N)'.
- **Top match:** `0.36` **ADAMCR-0741** — Within a dataset, there is more than one value of PARAMN for a given value of PARAM, considering only those rows on which both variables are populated

### ADAM-000216
- **Description:** Variable PBCHG must have label 'Percent Change to'.
- **Message:** Variable PBCHG does not have the expected label 'Percent Change to'.
- **Top match:** `0.36` **ADAMCR-0587** — Within a given value of PARAMCD, there is more than one value of PBCHGCAy for a given value of PBCHG and y, where y is a single-digit integer [1-9]

### ADAM-000217
- **Description:** Variable PCHG must have label 'Percent Change from'.
- **Message:** Variable PCHG does not have the expected label 'Percent Change from'.
- **Top match:** `0.36` **ADAMCR-0751** — Within a given value of PARAMCD, there is more than one value of PCHGCATy for a given value of PCHG and y, where y is a single-digit integer [1-9]

### ADAM-000218
- **Description:** Variable PHEDT must have label 'Phase End Date'.
- **Message:** Variable PHEDT does not have the expected label 'Phase End Date'.
- **Top match:** `0.37` **ADAMCR-0610** — On a given record, APHASEN is not present and the value of PHEDT is not equal to the value of at least one PHwEDT variable

### ADAM-000220
- **Description:** Variable PHEDTM must have label 'Phase End Datetime'.
- **Message:** Variable PHEDTM does not have the expected label 'Phase End Datetime'.
- **Top match:** `0.37` **ADAMCR-0614** — On a given record, APHASEN is not present and the value of PHEDTM is not equal to the value of at least one PHwEDTM variable

### ADAM-000221
- **Description:** Variable PHETM must have label 'Phase End Time'.
- **Message:** Variable PHETM does not have the expected label 'Phase End Time'.
- **Top match:** `0.37` **ADAMCR-0612** — On a given record, APHASEN is not present and the value of PHETM is not equal to the value of at least one PHwETM variable

### ADAM-000223
- **Description:** Variable PHSDT must have label 'Phase Start Date'.
- **Message:** Variable PHSDT does not have the expected label 'Phase Start Date'.
- **Top match:** `0.37` **ADAMCR-0604** — On a given record, APHASEN is not present and the value of PHSDT is not equal to the value of at least one PHwSDT variable

### ADAM-000225
- **Description:** Variable PHSDTM must have label 'Phase Start Datetime'.
- **Message:** Variable PHSDTM does not have the expected label 'Phase Start Datetime'.
- **Top match:** `0.37` **ADAMCR-0608** — On a given record, APHASEN is not present and the value of PHSDTM is not equal to the value of at least one PHwSDTM variable

### ADAM-000226
- **Description:** Variable PHSTM must have label 'Phase Start Time'.
- **Message:** Variable PHSTM does not have the expected label 'Phase Start Time'.
- **Top match:** `0.37` **ADAMCR-0606** — On a given record, APHASEN is not present and the value of PHSTM is not equal to the value of at least one PHwSTM variable

### ADAM-000233
- **Description:** Variable RANDDT must have label 'Date of Randomization'.
- **Message:** Variable RANDDT does not have the expected label 'Date of Randomization'.
- **Top match:** `0.38` **ADAMCR-0366** — RANDDT is not present when RANDFL is equal to Y for at least one record

### ADAM-000248
- **Description:** Variable STRATAR must have label 'Strata Used for'.
- **Message:** Variable STRATAR does not have the expected label 'Strata Used for'.
- **Top match:** `0.38` **ADAMCR-0560** — STRATARN is present and STRATAR is not present

### ADAM-000249
- **Description:** Variable STRATARN must have label 'Strata Used for'.
- **Message:** Variable STRATARN does not have the expected label 'Strata Used for'.
- **Top match:** `0.38` **ADAMCR-0560** — STRATARN is present and STRATAR is not present

### ADAM-000250
- **Description:** Variable STRATAV must have label 'Strata from Verification'.
- **Message:** Variable STRATAV does not have the expected label 'Strata from Verification'.
- **Top match:** `0.38` **ADAMCR-0562** — STRATAVN is present and STRATAV is not present

### ADAM-000251
- **Description:** Variable STRATAVN must have label 'Strata from Verification'.
- **Message:** Variable STRATAVN does not have the expected label 'Strata from Verification'.
- **Top match:** `0.38` **ADAMCR-0562** — STRATAVN is present and STRATAV is not present

### ADAM-000255
- **Description:** Variable TRTAN must have label 'Actual Treatment (N)'.
- **Message:** Variable TRTAN does not have the expected label 'Actual Treatment (N)'.
- **Top match:** `0.38` **ADAMCR-0555** — TRTAN is present and TRTA is not present

### ADAM-000261
- **Description:** Variable TRTEDTM must have label 'Datetime of Last Exposure to'.
- **Message:** Variable TRTEDTM does not have the expected label 'Datetime of Last Exposure to'.
- **Top match:** `0.36` **ADAMCR-0365** — SDTM.EX is present and neither TRTEDT or TRTEDTM are present

### ADAM-000265
- **Description:** Variable TRTPN must have label 'Planned Treatment (N)'.
- **Message:** Variable TRTPN does not have the expected label 'Planned Treatment (N)'.
- **Top match:** `0.38` **ADAMCR-0556** — TRTPN is present and TRTP is not present

### ADAM-000266
- **Description:** Variable TRTSDT must have label 'Date of First Exposure to'.
- **Message:** Variable TRTSDT does not have the expected label 'Date of First Exposure to'.
- **Top match:** `0.36` **ADAMCR-0061** — SDTM.EX is present and neither TRTSDT or TRTSDTM are present

### ADAM-000268
- **Description:** Variable TRTSDTM must have label 'Datetime of First Exposure to'.
- **Message:** Variable TRTSDTM does not have the expected label 'Datetime of First Exposure to'.
- **Top match:** `0.36` **ADAMCR-0061** — SDTM.EX is present and neither TRTSDT or TRTSDTM are present

### ADAM-000269
- **Description:** Variable TRTSEQA must have label 'Actual Sequence of'.
- **Message:** Variable TRTSEQA does not have the expected label 'Actual Sequence of'.
- **Top match:** `0.38` **ADAMCR-0528** — TRTSEQAN is present and TRTSEQA is not present

### ADAM-000270
- **Description:** Variable TRTSEQAN must have label 'Actual Sequence of'.
- **Message:** Variable TRTSEQAN does not have the expected label 'Actual Sequence of'.
- **Top match:** `0.38` **ADAMCR-0528** — TRTSEQAN is present and TRTSEQA is not present

### ADAM-000271
- **Description:** Variable TRTSEQP must have label 'Planned Sequence of'.
- **Message:** Variable TRTSEQP does not have the expected label 'Planned Sequence of'.
- **Top match:** `0.38` **ADAMCR-0527** — TRTSEQPN is present and TRTSEQP is not present

### ADAM-000272
- **Description:** Variable TRTSEQPN must have label 'Planned Sequence of'.
- **Message:** Variable TRTSEQPN does not have the expected label 'Planned Sequence of'.
- **Top match:** `0.38` **ADAMCR-0527** — TRTSEQPN is present and TRTSEQP is not present

### ADAM-000279
- **Description:** Variable ACTARM must be of type Char.
- **Message:** Variable ACTARM is not of the expected type Char.
- **Top match:** `0.39` **ADAMCR-0367** — The value of ADSL.USUBJID is equal to the value of DM.USUBJID and ADSL.ACTARM is not equal to DM.ACTARM

### ADAM-000285
- **Description:** Variable AENDTM must be of type Num.
- **Message:** Variable AENDTM is not of the expected type Num.
- **Top match:** `0.37` **ADAMCR-0362** — The value of ASTDTM is greater than the value of AENDTM, considering only those rows on which both variables are populated

### ADAM-000291
- **Description:** Variable ANRIND must be of type Char.
- **Message:** Variable ANRIND is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0166** — BNRIND is present and ANRIND is not present

### ADAM-000292
- **Description:** Variable ANRLO must be of type Num.
- **Message:** Variable ANRLO is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0343** — Within a parameter, there is more than one value of ANRLOC for a given value of ANRLO

### ADAM-000293
- **Description:** Variable ANRLOC must be of type Char.
- **Message:** Variable ANRLOC is not of the expected type Char.
- **Top match:** `0.38` **ADAMCR-0343** — Within a parameter, there is more than one value of ANRLOC for a given value of ANRLO

### ADAM-000294
- **Description:** Variable APEREDT must be of type Num.
- **Message:** Variable APEREDT is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0595** — On a given record, the value of APEREDT is not equal to the value of variable APxxEDT where xx equals the value of APERIOD

### ADAM-000296
- **Description:** Variable APEREDTM must be of type Num.
- **Message:** Variable APEREDTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0597** — On a given record, the value of APEREDTM is not equal to the value of variable APxxEDTM where xx equals the value of APERIOD

### ADAM-000297
- **Description:** Variable APERETM must be of type Num.
- **Message:** Variable APERETM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0596** — On a given record, the value of APERETM is not equal to the value of variable APxxETM where xx equals the value of APERIOD

### ADAM-000300
- **Description:** Variable APERIODC must be of type Char.
- **Message:** Variable APERIODC is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0539** — APERIODC is present and APERIOD is not present

### ADAM-000301
- **Description:** Variable APERSDT must be of type Num.
- **Message:** Variable APERSDT is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0592** — On a given record, the value of APERSDT is not equal to the value of variable APxxSDT where xx equals the value of APERIOD

### ADAM-000303
- **Description:** Variable APERSDTM must be of type Num.
- **Message:** Variable APERSDTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0594** — On a given record, the value of APERSDTM is not equal to the value of variable APxxSDTM where xx equals the value of APERIOD

### ADAM-000304
- **Description:** Variable APERSTM must be of type Num.
- **Message:** Variable APERSTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0593** — On a given record, the value of APERSTM is not equal to the value of variable APxxSTM where xx equals the value of APERIOD

### ADAM-000307
- **Description:** Variable APHASEN must be of type Num.
- **Message:** Variable APHASEN is not of the expected type Num.
- **Top match:** `0.39` **ADAMCR-0540** — APHASEN is present and APHASE is not present

### ADAM-000308
- **Description:** Variable ARELTM must be of type Num.
- **Message:** Variable ARELTM is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0112** — ARELTM is populated and ARELTMU is not populated

### ADAM-000309
- **Description:** Variable ARELTMU must be of type Char.
- **Message:** Variable ARELTMU is not of the expected type Char.
- **Top match:** `0.41` **ADAMCR-0112** — ARELTM is populated and ARELTMU is not populated

### ADAM-000313
- **Description:** Variable ASPERC must be of type Char.
- **Message:** Variable ASPERC is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0541** — ASPERC is present and ASPER is not present

### ADAM-000325
- **Description:** Variable ASTDTM must be of type Num.
- **Message:** Variable ASTDTM is not of the expected type Num.
- **Top match:** `0.37` **ADAMCR-0362** — The value of ASTDTM is greater than the value of AENDTM, considering only those rows on which both variables are populated

### ADAM-000331
- **Description:** Variable ATOXDSCH must be of type Char.
- **Message:** Variable ATOXDSCH is not of the expected type Char.
- **Top match:** `0.37` **ADAMCR-0405** — Within a subject, there is more than one value of ATOXDSCH for a given value of PARAM, considering only those rows on which both variables are populated

### ADAM-000332
- **Description:** Variable ATOXDSCL must be of type Char.
- **Message:** Variable ATOXDSCL is not of the expected type Char.
- **Top match:** `0.37` **ADAMCR-0403** — Within a subject, there is more than one value of ATOXDSCL for a given value of PARAM, considering only those rows on which both variables are populated

### ADAM-000333
- **Description:** Variable ATOXGR must be of type Char.
- **Message:** Variable ATOXGR is not of the expected type Char.
- **Top match:** `0.39` **ADAMCR-0163** — BTOXGR is present and ATOXGR is not present

### ADAM-000334
- **Description:** Variable ATOXGRH must be of type Char.
- **Message:** Variable ATOXGRH is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0524** — ATOXGRHN is present and ATOXGRH is not present

### ADAM-000335
- **Description:** Variable ATOXGRHN must be of type Num.
- **Message:** Variable ATOXGRHN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0524** — ATOXGRHN is present and ATOXGRH is not present

### ADAM-000336
- **Description:** Variable ATOXGRL must be of type Char.
- **Message:** Variable ATOXGRL is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0523** — ATOXGRLN is present and ATOXGRL is not present

### ADAM-000337
- **Description:** Variable ATOXGRLN must be of type Num.
- **Message:** Variable ATOXGRLN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0523** — ATOXGRLN is present and ATOXGRL is not present

### ADAM-000338
- **Description:** Variable ATOXGRN must be of type Num.
- **Message:** Variable ATOXGRN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0536** — ATOXGRN is present and ATOXGR is not present

### ADAM-000339
- **Description:** Variable ATPT must be of type Char.
- **Message:** Variable ATPT is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0542** — ATPTN is present and ATPT is not present

### ADAM-000340
- **Description:** Variable ATPTN must be of type Num.
- **Message:** Variable ATPTN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0542** — ATPTN is present and ATPT is not present

### ADAM-000343
- **Description:** Variable AVALC must be of type Char.
- **Message:** Variable AVALC is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0252** — AVAL is present or AVALC is present

### ADAM-000345
- **Description:** Variable AVISITN must be of type Num.
- **Message:** Variable AVISITN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0548** — AVISITN is present and AVISIT is not present

### ADAM-000349
- **Description:** Variable AWTARGET must be of type Num.
- **Message:** Variable AWTARGET is not of the expected type Num.
- **Top match:** `0.41` **ADAMCR-0159** — AWTDIFF is populated and AWTARGET is not populated

### ADAM-000350
- **Description:** Variable AWTDIFF must be of type Num.
- **Message:** Variable AWTDIFF is not of the expected type Num.
- **Top match:** `0.41` **ADAMCR-0159** — AWTDIFF is populated and AWTARGET is not populated

### ADAM-000352
- **Description:** Variable BASE must be of type Num.
- **Message:** Variable BASE is not of the expected type Num.
- **Top match:** `0.35` **ADAMCR-0749** — Within a given value of PARAMCD, there is more than one value of BASECATy for a given value of BASE and y, where y is a single-digit integer [1-9]

### ADAM-000355
- **Description:** Variable BCHG must be of type Num.
- **Message:** Variable BCHG is not of the expected type Num.
- **Top match:** `0.36` **ADAMCR-0583** — Within a given value of PARAMCD, there is more than one value of BCHGCATy for a given value of BCHG and y, where y is an integer [1-99, not zero-padded]

### ADAM-000356
- **Description:** Variable BNRIND must be of type Char.
- **Message:** Variable BNRIND is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0167** — BNRIND is present and ABLFL is not present

### ADAM-000357
- **Description:** Variable BTOXGR must be of type Char.
- **Message:** Variable BTOXGR is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0164** — BTOXGR is present and ABLFL is not present

### ADAM-000358
- **Description:** Variable BTOXGRH must be of type Char.
- **Message:** Variable BTOXGRH is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0549** — BTOXGRHN is present and BTOXGRH is not present

### ADAM-000359
- **Description:** Variable BTOXGRHN must be of type Num.
- **Message:** Variable BTOXGRHN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0549** — BTOXGRHN is present and BTOXGRH is not present

### ADAM-000360
- **Description:** Variable BTOXGRL must be of type Char.
- **Message:** Variable BTOXGRL is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0550** — BTOXGRLN is present and BTOXGRL is not present

### ADAM-000361
- **Description:** Variable BTOXGRLN must be of type Num.
- **Message:** Variable BTOXGRLN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0550** — BTOXGRLN is present and BTOXGRL is not present

### ADAM-000362
- **Description:** Variable BTOXGRN must be of type Num.
- **Message:** Variable BTOXGRN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0522** — BTOXGRN is present and BTOXGR is not present

### ADAM-000363
- **Description:** Variable CHG must be of type Num.
- **Message:** Variable CHG is not of the expected type Num.
- **Top match:** `0.36` **ADAMCR-0750** — Within a given value of PARAMCD, there is more than one value of CHGCATy for a given value of CHG and y, where y is an integer [1-99, not zero-padded]

### ADAM-000377
- **Description:** Variable DTHCAUS must be of type Char.
- **Message:** Variable DTHCAUS is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0551** — DTHCAUSN is present and DTHCAUS is not present

### ADAM-000378
- **Description:** Variable DTHCAUSN must be of type Num.
- **Message:** Variable DTHCAUSN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0551** — DTHCAUSN is present and DTHCAUS is not present

### ADAM-000395
- **Description:** Variable LVOTFL must be of type Char.
- **Message:** Variable LVOTFL is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0569** — LVOTFN is present and LVOTFL is not present

### ADAM-000396
- **Description:** Variable LVOTFN must be of type Num.
- **Message:** Variable LVOTFN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0569** — LVOTFN is present and LVOTFL is not present

### ADAM-000400
- **Description:** Variable PARAMN must be of type Num.
- **Message:** Variable PARAMN is not of the expected type Num.
- **Top match:** `0.37` **ADAMCR-0741** — Within a dataset, there is more than one value of PARAMN for a given value of PARAM, considering only those rows on which both variables are populated

### ADAM-000401
- **Description:** Variable PBCHG must be of type Num.
- **Message:** Variable PBCHG is not of the expected type Num.
- **Top match:** `0.36` **ADAMCR-0587** — Within a given value of PARAMCD, there is more than one value of PBCHGCAy for a given value of PBCHG and y, where y is a single-digit integer [1-9]

### ADAM-000402
- **Description:** Variable PCHG must be of type Num.
- **Message:** Variable PCHG is not of the expected type Num.
- **Top match:** `0.36` **ADAMCR-0751** — Within a given value of PARAMCD, there is more than one value of PCHGCATy for a given value of PCHG and y, where y is a single-digit integer [1-9]

### ADAM-000403
- **Description:** Variable PHEDT must be of type Num.
- **Message:** Variable PHEDT is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0610** — On a given record, APHASEN is not present and the value of PHEDT is not equal to the value of at least one PHwEDT variable

### ADAM-000405
- **Description:** Variable PHEDTM must be of type Num.
- **Message:** Variable PHEDTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0614** — On a given record, APHASEN is not present and the value of PHEDTM is not equal to the value of at least one PHwEDTM variable

### ADAM-000406
- **Description:** Variable PHETM must be of type Num.
- **Message:** Variable PHETM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0612** — On a given record, APHASEN is not present and the value of PHETM is not equal to the value of at least one PHwETM variable

### ADAM-000408
- **Description:** Variable PHSDT must be of type Num.
- **Message:** Variable PHSDT is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0604** — On a given record, APHASEN is not present and the value of PHSDT is not equal to the value of at least one PHwSDT variable

### ADAM-000410
- **Description:** Variable PHSDTM must be of type Num.
- **Message:** Variable PHSDTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0608** — On a given record, APHASEN is not present and the value of PHSDTM is not equal to the value of at least one PHwSDTM variable

### ADAM-000411
- **Description:** Variable PHSTM must be of type Num.
- **Message:** Variable PHSTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0606** — On a given record, APHASEN is not present and the value of PHSTM is not equal to the value of at least one PHwSTM variable

### ADAM-000418
- **Description:** Variable RANDDT must be of type Num.
- **Message:** Variable RANDDT is not of the expected type Num.
- **Top match:** `0.39` **ADAMCR-0366** — RANDDT is not present when RANDFL is equal to Y for at least one record

### ADAM-000433
- **Description:** Variable STRATAR must be of type Char.
- **Message:** Variable STRATAR is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0560** — STRATARN is present and STRATAR is not present

### ADAM-000434
- **Description:** Variable STRATARN must be of type Num.
- **Message:** Variable STRATARN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0560** — STRATARN is present and STRATAR is not present

### ADAM-000435
- **Description:** Variable STRATAV must be of type Char.
- **Message:** Variable STRATAV is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0562** — STRATAVN is present and STRATAV is not present

### ADAM-000436
- **Description:** Variable STRATAVN must be of type Num.
- **Message:** Variable STRATAVN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0562** — STRATAVN is present and STRATAV is not present

### ADAM-000440
- **Description:** Variable TRTAN must be of type Num.
- **Message:** Variable TRTAN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0555** — TRTAN is present and TRTA is not present

### ADAM-000446
- **Description:** Variable TRTEDTM must be of type Num.
- **Message:** Variable TRTEDTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0365** — SDTM.EX is present and neither TRTEDT or TRTEDTM are present

### ADAM-000450
- **Description:** Variable TRTPN must be of type Num.
- **Message:** Variable TRTPN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0556** — TRTPN is present and TRTP is not present

### ADAM-000451
- **Description:** Variable TRTSDT must be of type Num.
- **Message:** Variable TRTSDT is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0061** — SDTM.EX is present and neither TRTSDT or TRTSDTM are present

### ADAM-000453
- **Description:** Variable TRTSDTM must be of type Num.
- **Message:** Variable TRTSDTM is not of the expected type Num.
- **Top match:** `0.38` **ADAMCR-0061** — SDTM.EX is present and neither TRTSDT or TRTSDTM are present

### ADAM-000454
- **Description:** Variable TRTSEQA must be of type Char.
- **Message:** Variable TRTSEQA is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0528** — TRTSEQAN is present and TRTSEQA is not present

### ADAM-000455
- **Description:** Variable TRTSEQAN must be of type Num.
- **Message:** Variable TRTSEQAN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0528** — TRTSEQAN is present and TRTSEQA is not present

### ADAM-000456
- **Description:** Variable TRTSEQP must be of type Char.
- **Message:** Variable TRTSEQP is not of the expected type Char.
- **Top match:** `0.40` **ADAMCR-0527** — TRTSEQPN is present and TRTSEQP is not present

### ADAM-000457
- **Description:** Variable TRTSEQPN must be of type Num.
- **Message:** Variable TRTSEQPN is not of the expected type Num.
- **Top match:** `0.40` **ADAMCR-0527** — TRTSEQPN is present and TRTSEQP is not present


## Strong match (likely covered — not listed individually)

130 generated rules have a conformance match at score ≥ 0.45 and are treated as covered. Full list available on request.
