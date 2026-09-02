# Define-XML conformance rules — input gaps (not authored)

Rules whose check needs an input this validator deliberately does not take
(plan §3.35 item 6; FDA-plan engine-gaps discipline: recorded here, never
authored as broken rules). One row per rule.

| Rule | Sheet | Requirement | Missing input | Notes |
|---|---|---|---|---|
| ~~DEFINE-XML-0263~~ | CDISC 263 | `def:Standard` Version must be a version actually published for that standard Name | ~~CDISC Library standards-version catalogue~~ **SHIPPED** via `Requires: library` (`library_standard_version_known`, + `-B` 2.0 MetaDataVersion leg) | resolved |
| ~~DEFINE-XML-0265~~ | CDISC 265 | English dataset Description must match the label the referenced SDTMIG/SENDIG standard defines | ~~CDISC IG library (dataset labels)~~ **SHIPPED** via `Requires: library` (`library_dataset_label_matches`, PMDA-DD0136 twin) | resolved |

## Deferred to the P5 CT-gated batch (authorable once `Requires: ct` kinds land)

| Rule | Sheet | Requirement | Why deferred |
|---|---|---|---|
| ~~DEFINE-XML-0132~~ | CDISC 132 | `def:Class` Name ∈ CT codelist C103329 (General Observation Class) | ~~value set is CT-published~~ SHIPPED at P5a as `term_in_ct_codelist` with the explicit `cCode: C103329` (`Requires: ct`) |
| DEFINE-XML-0261 | CDISC 261 | `def:SubClass` Name ∈ C165635/C165636/C176227 "+ any other subclass C-Code" | **P5a verdict: stays a gap even with CT.** The Rule column's value set is "C165635, C165636, C176227, and any other subclass C-Code that is included in the applicable schema" — open-ended by construction, and the closure lives in the *schema package*, not in CT (the `CtProvider` SPI can test membership in named codelists but cannot enumerate "all subclass codelists"). Checking only the three named lists would false-positive on schema-legal newer subclasses. |
| DEFINE-XML-0262 | CDISC 262 | `def:SubClass` ParentClass ∈ Class∪SubClass codelists | same P5a verdict as 261 (the union inherits 261's open-ended subclass set) |
| ~~DEFINE-XML-0097~~ | CDISC 97 | Alias/nci:ExtCodeID required when the variable requires CDISC CT per the standard | **P5a verdict: reclassified library-gap, NOT CT-authorable.** The trigger — "referenced by the ItemDef element for a standard variable **that requires CDISC Controlled Terminology**" (Rule column, verbatim) — is per-variable CT-requirement knowledge from the IG, the same missing input as CDISC-149. CT term data alone cannot decide which codelists the rule reaches; PMDA-DD0031 ships the CT-identifiable slice (codelist known to CT by name). | **SHIPPED** via `Requires: library` (`library_ct_alias_required` level=codelist; the library decides the CT-requirement trigger) |
| ~~DEFINE-XML-0098~~ | CDISC 98 | as 97, EnumeratedItem leg (fires only where `@def:ExtendedValue` absent) | same P5a verdict as 97; the CT-decidable slice ("term is a CT member ⇒ needs its alias") ships as PMDA-DD0032 | **SHIPPED** via `Requires: library` (`library_ct_alias_required` level=enumerated_item) |
| ~~DEFINE-XML-0099~~ | CDISC 99 | as 97, CodeListItem leg | same P5a verdict as 97; CT-decidable slice ships as PMDA-DD0032-B | **SHIPPED** via `Requires: library` (`library_ct_alias_required` level=code_list_item) |
| ~~DEFINE-XML-0249~~ | CDISC 249 | CodeList with @StandardOID needs exactly one nci:ExtCodeID Alias | ~~custom candidate~~ SHIPPED as the CodeListStandardAliasCheck custom (batch E) | resolved |
| ~~DEFINE-XML-0067~~ | CDISC 67 | ItemRef Mandatory must be "Yes" when the IG defines the variable Core="Req" | CDISC IG library (variable Core designations) | **SHIPPED** via `Requires: library` (`library_core_mandatory` over `variableCoreDesignation`) |
| DEFINE-XML-0076 | CDISC 76 | def:HasNoData required when the variable has no data values | the submitted DATASET CONTENT (xpt), not the define — a data-gap, new category | data-gap (out of validator scope by design) |
| ~~DEFINE-XML-0155 (branch 2)~~ | CDISC 155 | every value-level ItemDef needs def:Origin | ~~grammar-gap~~ SHIPPED as DEFINE-XML-0155-B via the 0142-style reverse join (review-batch-d verdict) | resolved |
| ~~DEFINE-XML-0149~~ | CDISC 149 | variable with a CT requirement in the referenced standard must have a CodeListRef | CDISC IG library (per-variable CT requirements) — same family as 0067 | **SHIPPED** via `Requires: library` (`library_codelist_ref_required`, PMDA-DD0124 twin) |
| ~~DEFINE-XML-0179~~ | CDISC 179 | EnumeratedItem CodedValue must exactly match the published CT submission value | ~~published CDISC CT~~ SHIPPED at P5a as `term_in_ct_codelist` (`exemptExtendedValues`, `@def:StandardOID` guard) | resolved |
| ~~DEFINE-XML-0192~~ | CDISC 192 | CodeListItem CodedValue must exactly match the published CT submission value | ~~published CDISC CT~~ SHIPPED at P5a, CodeListItem twin of 179 | resolved |
| ~~DEFINE-XML-0153~~ | CDISC 153 | IsNonStandard required on a CodeList where CT is required and no nci:ExtCodeID Alias present | **P5a picked the FORMAL Rule column reading** — '@def:IsNonStandard must be provided when there is no CodeList/Alias[@Context="nci:ExtCodeID"]' is purely intra-document, so the rule SHIPPED as `exists` + `when`, with **no** `Requires: ct`. The plain-text "where CT is required" trigger is IG-library knowledge (CDISC-149 family) and is not encoded. | resolved |
| ~~DEFINE-XML-0186~~ | CDISC 186 | EnumeratedItem def:ExtendedValue required when the CodedValue is an extension of the referenced CT codelist | ~~published CDISC CT~~ SHIPPED at P5a as `extended_value_marking` mode=required | resolved |
| ~~DEFINE-XML-0187~~ | CDISC 187 | EnumeratedItem def:ExtendedValue must not be present when the CT codelist is non-extensible | ~~published CDISC CT~~ SHIPPED at P5a as `extended_value_marking` mode=forbidden | resolved |
| ~~DEFINE-XML-0201~~ | CDISC 201 | CodeListItem def:ExtendedValue required when the CodedValue is an extension | ~~published CDISC CT~~ SHIPPED at P5a, CodeListItem twin of 186 | resolved |
| ~~DEFINE-XML-0202~~ | CDISC 202 | CodeListItem def:ExtendedValue must not be present when the CT codelist is non-extensible | ~~published CDISC CT~~ SHIPPED at P5a, CodeListItem twin of 187 | resolved |
| ~~DEFINE-XML-0266~~ | CDISC 266 | English variable Description must match the label the referenced standard defines | CDISC IG library (variable labels) — twin of 0265 | **SHIPPED** via `Requires: library` (`library_variable_label_matches`, PMDA-DD0137 twin) |

## PMDA-sheet deferrals (P5 batch)

| Rule | Sheet | Requirement | Why deferred |
|---|---|---|---|
| ~~PMDA-DD0021 (2.1 leg)~~ | DD0021 | def:Standard Name ∈ Define-XML STDNAM CT codelist | SHIPPED at P5a as PMDA-DD0021-B (`term_in_ct_codelist`, explicit `cCode: C170452` — STDNAM's published c-code) |
| ~~PMDA-DD0055 (2.1 leg)~~ | DD0055 | def:Class Name ∈ GNRLOBSC (C103329) | SHIPPED at P5a as PMDA-DD0055-C (`term_in_ct_codelist`, explicit `cCode: C103329`; twin of CDISC 132) |
| ~~PMDA-DD0049~~ | DD0049 | non-split name consistency | SHIPPED (P5b custom NonSplitDatasetNameConsistencyCheck) |
| ~~PMDA-DD0050~~ | DD0050 | split SASDatasetName prefix | SHIPPED (P5b custom) |
| ~~PMDA-DD0063~~ | DD0063 | Alias for split datasets | SHIPPED (P5b custom SplitDatasetAliasCheck) |
| ~~PMDA-DD0075~~ | DD0075 | CRF Origin → aCRF reference | SHIPPED (P5b custom, incl. the W1 absent-DocumentRef shape) |
| ~~PMDA-DD0114 / DD0115~~ | DD0114/5 | split-dataset usage rules | SHIPPED (P5b customs) |
| PMDA-DD0140 | DD0140 | SubClass values from the CDISC/NCI subclass codelists | open-ended CT (CDISC-261 precedent) |
| PMDA-DD0101 | DD0101 | eCTD submission must contain a define.xml per study (Module 4/5) | submission-PACKAGE rule across the eCTD tree; this validator is invoked ON a define.xml — not applicable by construction (P5b adjudication) | not-applicable |

## PMDA-sheet residual-batch gaps (final 16 rows)

The residual batch authored 11 rule files (DD0029/-B, DD0132/-B, DD0099, DD0100,
DD0105, OD0082, DD0138, and the customs DD0093, DD0150). The rows below are the
seven it adjudicated as gaps rather than author.

| Rule | Sheet | Requirement | Why deferred |
|---|---|---|---|
| PMDA-DD0092 | DD0092 | ParameterOID required when the arm:AnalysisResult is "based on specific parameter(s) from a BDS structured dataset" | **ARM-semantic gap.** "Based on specific parameter(s)" is an analysis-design judgement with no reliable structural marker in the document (an AnalysisResult referencing a BDS dataset is not necessarily parameter-specific, and parameter-specificity is not otherwise encoded). Authoring it would false-positive/negative on an Error-severity rule. The companion DD0093 (the "when ParameterOID IS present, use it correctly" direction) IS authored as a custom. |
| ~~PMDA-DD0116~~ | DD0116 | When an SDTM Event/Intervention qualifier variable is used as FATESTCD, FATEST must be that qualifier variable's label | ~~library-gap~~ **SHIPPED** via `Requires: library` (`library_qualifier_label_decode`, plan define-library-provider) — the qualifier-label catalogue comes from the caller-supplied `LibraryProvider`. |
| ~~PMDA-DD0118~~ | DD0118 | The NCI code of a codelist on a variable must match the NCI code of the standard codelist for that variable | ~~library-gap + duplication~~ **SHIPPED** via `Requires: library` (`library_codelist_ccode_matches`); the CT-decidable slice still ships as PMDA-DD0033/DD0034. |
| ~~PMDA-DD0124~~ | DD0124 | A variable defined in a Standard with a CT requirement must have a CodeList/CodeListRef | ~~library-gap~~ **SHIPPED** via `Requires: library` (`library_codelist_ref_required` — `variableCodelistCCode` present = "requires CT"). |
| ~~PMDA-DD0136~~ | DD0136 | The English dataset Description (label) must match the SDTMIG/SENDIG standard's label | ~~library-gap~~ **SHIPPED** via `Requires: library` (`library_dataset_label_matches`). The CDISC twin 0265 now also ships. |
| ~~PMDA-DD0137~~ | DD0137 | The English variable Description (label) must match the standard's label | ~~library-gap~~ **SHIPPED** via `Requires: library` (`library_variable_label_matches`). The CDISC twin 0266 now also ships. |

> **`Requires: library` (plan define-library-provider):** the validator now
> takes an optional caller-supplied `LibraryProvider` (IG dataset/variable
> labels, per-variable codelist c-codes, qualifier-variable labels) — the
> `CtProvider` pattern. Without one, library-gated rules SKIP
> (`SKIPPED_MISSING_LIBRARY`). The library-gap rows above are resolved by it;
> the CDISC-family twins (0067, 0097–0099, 0149, 0263/-B, 0265/0266) are
> authored against the same SPI, and the CLI binds a production
> `CdiscLibraryBackedLibraryProvider` over the Library API when an API key is
> configured.
