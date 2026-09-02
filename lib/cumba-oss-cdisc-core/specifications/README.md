# CDISC Specifications and Documentation

This directory contains CDISC specification documents (PDFs, XLSX),
generated documentation (MD), schema files (JSON), and non-extensible
controlled terminology extractions.

---

## Specification Documents (PDFs)

### SDTM

| File | Description |
|------|-------------|
| `study_data_tabulation_model_v1_4.pdf` | SDTM Model v1.4 |
| `SDTM v1.5.pdf` | SDTM Model v1.5 |
| `SDTM v1.7.pdf` | SDTM Model v1.7 |
| `SDTM_v2.0.pdf` | SDTM Model v2.0 |

### SDTMIG

| File | Description |
|------|-------------|
| `SDTMIG_v3.2.pdf` | SDTM Implementation Guide v3.2 |
| `SDTMIG_v3.3_FINAL.pdf` | SDTM Implementation Guide v3.3 (Final) |
| `SDTMIG v3.4-FINAL_2022-07-21.pdf` | SDTM Implementation Guide v3.4 (Final) |

### SENDIG

| File | Description |
|------|-------------|
| `SENDIG_v3.0.pdf` | SEND Implementation Guide v3.0 |
| `SENDIG_3_1.pdf` | SEND Implementation Guide v3.1 |
| `SENDIG_v3.1.1.pdf` | SEND Implementation Guide v3.1.1 |

### ADaM

| File | Description |
|------|-------------|
| `analysis_data_model_v2.1.pdf` | ADaM Model v2.1 (foundational document) |
| `Important Considerations When Using ADaM v2.1.pdf` | ADaM v2.1 addendum (key updates since 2009) |
| `ADaMIG_v1.0.pdf` | ADaM Implementation Guide v1.0 |
| `ADaMIG_v1.1.pdf` | ADaM Implementation Guide v1.1 |
| `ADaMIG v1.2-Final.pdf` | ADaM Implementation Guide v1.2 (Final) |
| `ADaMIG_v1.3.pdf` | ADaM Implementation Guide v1.3 (Final) |
| `ADaM_Implementation_Guide_for_Medical_Devices_v1.0.pdf` | ADaMIG for Medical Devices v1.0 |
| `ADaM_OCCDS_Implementation_Guide v1.1.pdf` | ADaM Occurrence Data Structure IG v1.1 |
| `ADaMIG_for_Non-compartmental_Analysis_Input_Data_v1.0_1.pdf` | ADaMIG for NCA Input Data v1.0 |
| `BDS for ADaM PopPK Implementation Guide_v1.0_0.pdf` | ADaM BDS for Population PK IG v1.0 |

### Define-XML

| File | Description |
|------|-------------|
| `Define_XML_v1.0.pdf` | Define-XML v1.0 specification |
| `define_xml_2_0_releasepackage20140424.zip` | Define-XML v2.0 release package |
| `DefineV2110.zip` | Define-XML v2.1.0 release package |
| `ARM-for-Define-XML.zip` | Analysis Results Metadata for Define-XML |
| `odm1_3_2.zip` | ODM v1.3.2 (base schema for Define-XML) |

### Conformance Rules (Source)

| File | Description |
|------|-------------|
| `SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx` | SDTM/SDTMIG Conformance Rules v2.0 catalogue (455 rules for IG 3.4) |
| `SDTM and SDTMIG Conformance Rules Guide v2.0.pdf` | SDTM/SDTMIG Conformance Rules v2.0 guide document |
| `ADaM Conformance Rules v5.0.xlsx` | ADaM Conformance Rules v5.0 catalogue (790 unique rules across all IG versions) |
| `Define-XML_v2.1_Conformance_Rules.xlsx` | Define-XML v2.1 Conformance Rules |

---

## Our Documentation

### Core Rules Engine

> ⚠ **The rule documentation set does not live in this module.** It is
> [`../../corej-cdisc-rules/documentation/`](../../corej-cdisc-rules/documentation/), and it has
> two layers plus a citing layer:
>
> * **structure** — [`CORE-RULES-SPECIFICATION.md`](../../corej-cdisc-rules/documentation/CORE-RULES-SPECIFICATION.md)
>   (which fields a rule has, the load and execution pipeline);
> * **syntax** — [`CORE-EXPRESSION-CHECK-SPECIFICATION.md`](../../corej-cdisc-rules/documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md)
>   (every operator, function, accessor and operand form of a `Check`, registry-gated);
> * **semantics / typing** — `operator-examples.md`, `function-examples.md`,
>   `operand-type-examples.md`;
> * **citing layer** — [`CORE-RULES-AUTHORING-GUIDELINES.md`](../../corej-cdisc-rules/documentation/CORE-RULES-AUTHORING-GUIDELINES.md)
>   (the `R-x.y` conventions), [`rule-review-manual.md`](../../corej-cdisc-rules/documentation/rule-review-manual.md),
>   [`rule-step-by-step.md`](../../corej-cdisc-rules/documentation/rule-step-by-step.md) and
>   [`CORE-RULES-GUARD-MATRIX.md`](../../corej-cdisc-rules/documentation/CORE-RULES-GUARD-MATRIX.md);
> * plus [`DEFINE-XML-SPECIFICATION.md`](../../corej-cdisc-rules/documentation/DEFINE-XML-SPECIFICATION.md).
>
> **Two documents were deleted after absorption** and are indexed entry-by-entry in
> [`expression-docs-disposition.md`](../../corej-cdisc-rules/documentation/expression-docs-disposition.md):
> `CORE-RULES-JAVA-EXTENSIONS.md` (dissolved into the two specifications) and **this module's own**
> `documentation/expression-language-reference.md` (folded into the check-spec; its registry gate
> `ExpressionDocCoverageTest` retired in the same change, superseded by
> `ExpressionCheckSpecDriftTest`).

| File | Description |
|------|-------------|
| `adam-specifications-guide.md` | Guide to ADaM conformance rules sources, CDISC Library API endpoints, integration notes |

### Rule Documentation — SDTMIG

| File | Description |
|------|-------------|
| `rules-sdtmig-3-4-documentation.md` | Documentation for 430 SDTMIG 3.4 rules (from CDISC Library API) |

### Rule Documentation — SDTMIG Controlled Terminology

| File | Description |
|------|-------------|
| `rules-sdtmig-3-2-ct-documentation.md` | Documentation for 216 SDTMIG 3.2 non-extensible CT validation rules |
| `rules-sdtmig-3-3-ct-documentation.md` | Documentation for 376 SDTMIG 3.3 non-extensible CT validation rules |
| `rules-sdtmig-3-4-ct-documentation.md` | Documentation for 411 SDTMIG 3.4 non-extensible CT validation rules |

### Rule Documentation — ADaMIG (generated from PDFs)

| File | Description |
|------|-------------|
| `rules-adamig-1-0-documentation.md` | Documentation for 328 ADaMIG 1.0 rules (label, type, structural) |
| `rules-adamig-1-1-documentation.md` | Documentation for 438 ADaMIG 1.1 rules |
| `rules-adamig-1-3-documentation.md` | Documentation for 471 ADaMIG 1.3 rules |

### Rule Documentation — ADaMIG Controlled Terminology

| File | Description |
|------|-------------|
| `rules-adamig-1-0-ct-documentation.md` | Documentation for 17 ADaMIG 1.0 non-extensible CT rules |
| `rules-adamig-1-1-ct-documentation.md` | Documentation for 28 ADaMIG 1.1 non-extensible CT rules |
| `rules-adamig-1-3-ct-documentation.md` | Documentation for 30 ADaMIG 1.3 non-extensible CT rules |

### Rule Documentation — ADaM Specialized IGs

| File | Description |
|------|-------------|
| `rules-adamig-md-1-0-documentation.md` | Documentation for 67 ADaMIG-MD v1.0 rules (Medical Devices) |
| `rules-adamig-md-1-0-ct-documentation.md` | Documentation for 2 ADaMIG-MD v1.0 CT rules |
| `rules-adam-occds-1-1-documentation.md` | Documentation for 129 ADaM OCCDS v1.1 rules |
| `rules-adam-occds-1-1-ct-documentation.md` | Documentation for 5 ADaM OCCDS v1.1 CT rules |
| `rules-adam-adnca-1-0-documentation.md` | Documentation for 246 ADaM ADNCA v1.0 rules (NCA) |
| `rules-adam-adnca-1-0-ct-documentation.md` | Documentation for 10 ADaM ADNCA v1.0 CT rules |
| `rules-adam-adppk-1-0-documentation.md` | Documentation for 206 ADaM PopPK v1.0 rules |
| `rules-adam-adppk-1-0-ct-documentation.md` | Documentation for 4 ADaM PopPK v1.0 CT rules |

### Rule Documentation — ADaM Conformance Rules (from xlsx)

| File | Description |
|------|-------------|
| `rules-adam-conformance-1-0-documentation.md` | Documentation for 298 ADaM CR v5.0 rules (ADaMIG 1.0 set) |
| `rules-adam-conformance-1-1-documentation.md` | Documentation for 396 ADaM CR v5.0 rules (ADaMIG 1.1 set) |
| `rules-adam-conformance-1-2-documentation.md` | Documentation for 570 ADaM CR v5.0 rules (ADaMIG 1.2 set) |
| `rules-adam-conformance-1-3-documentation.md` | Documentation for 699 ADaM CR v5.0 rules (ADaMIG 1.3 set) |
| `adam-conformance-rules-conversion-report-1-0.md` | Conversion report ADaMIG 1.0 — 197 converted, 68 template, 33 manual |
| `adam-conformance-rules-conversion-report-1-1.md` | Conversion report ADaMIG 1.1 — 232 converted, 113 template, 51 manual |
| `adam-conformance-rules-conversion-report-1-2.md` | Conversion report ADaMIG 1.2 — 303 converted, 216 template, 51 manual |
| `adam-conformance-rules-conversion-report-1-3.md` | Conversion report ADaMIG 1.3 — 417 converted, 230 template, 52 manual |

---

## Non-Extensible Controlled Terminology Extractions

These files contain variable-to-codelist mappings extracted from the specification
PDFs, filtered to non-extensible (closed) codelists only. Each pair consists of
a `.json` (machine-readable) and `.md` (documentation) file.

### SDTMIG

| JSON | MD | Standard | Codelists | Variables |
|------|----|----------|-----------|-----------|
| `sdtmig-3-2-non-extensible-ct.json` | `sdtmig-3-2-non-extensible-ct.md` | SDTMIG 3.2 | 26 | 216 |
| `sdtmig-3-3-non-extensible-ct.json` | `sdtmig-3-3-non-extensible-ct.md` | SDTMIG 3.3 | 34 | 376 |
| `sdtmig-34-non-extensible-ct.json` | `sdtmig-34-non-extensible-ct.md` | SDTMIG 3.4 | 41 | 411 |

### ADaM

| JSON | MD | Standard | Codelists | Variables |
|------|----|----------|-----------|-----------|
| `adamig-1-0-non-extensible-ct.json` | `adamig-1-0-non-extensible-ct.md` | ADaMIG 1.0 | 6 | 17 |
| `adamig-1-1-non-extensible-ct.json` | `adamig-1-1-non-extensible-ct.md` | ADaMIG 1.1 | 6 | 28 |
| `adamig-13-non-extensible-ct.json` | `adamig-13-non-extensible-ct.md` | ADaMIG 1.3 | 7 | 30 |
| `adamig-md-10-non-extensible-ct.json` | `adamig-md-10-non-extensible-ct.md` | ADaMIG-MD 1.0 | 2 | 2 |
| `adam-occds-11-non-extensible-ct.json` | `adam-occds-11-non-extensible-ct.md` | OCCDS 1.1 | 3 | 5 |
| `adam-adnca-10-non-extensible-ct.json` | `adam-adnca-10-non-extensible-ct.md` | ADNCA 1.0 | 5 | 10 |
| `adam-adppk-10-non-extensible-ct.json` | `adam-adppk-10-non-extensible-ct.md` | PopPK 1.0 | 4 | 4 |

---

## Schema Files (from CDISC Rules Engine)

Downloaded from [cdisc-org/cdisc-rules-engine](https://github.com/cdisc-org/cdisc-rules-engine) (MIT License).

| File | Description |
|------|-------------|
| `CORE-base.json` | Master JSON schema for CORE rule definitions (16KB) |
| `Operator.json` | JSON schema for check condition operators (57 operators) |
| `Operations.json` | JSON schema for rule operations (24 types) |
| `Rule_Type.json` | JSON schema for rule type classification (8 types) |
| `Rule-CG0027-example.json` | Example complete CORE rule definition (CORE-000237) |

---

## Extracted Variable Data (intermediate)

These JSON files were extracted by agents during rule generation and may be
useful for reference but are not primary deliverables.

| File | Description |
|------|-------------|
| `ADaMIG-MD_v1.0_variables.json` | Variables extracted from ADaMIG-MD v1.0 PDF |
| `adnca_v1.0_variables.json` | Variables extracted from ADNCA v1.0 PDF |
| `adppk_variables.json` | Variables extracted from PopPK v1.0 PDF |
