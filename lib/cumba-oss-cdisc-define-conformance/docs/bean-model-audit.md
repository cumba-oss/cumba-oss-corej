# Bean-model audit — Define-XML conformance validator

Cross-check of every distinct `(element, attribute)` pair referenced by the
two rule inventories
(`src/test/resources/inventory/define-rules-inventory.json`,
`src/test/resources/inventory/pmda-define-rules-inventory.json`) against the
Jackson bean model in
`lib/corej-cdisc-define/src/main/java/net/cumba/cdisc/define/`.
Matching is by `@JacksonXmlProperty(localName = …)`; namespace prefixes
(`def:`, `xml:`, `xlink:`) are irrelevant because the parser is
namespace-agnostic. Generated 2026-07-03 from the inventories; **no bean
file was modified**.

## 1. Missing attribute fields

Every pair below has a bean class for the element but **no field** with the
attribute's localName. All other inventory-referenced attribute pairs
resolve to an existing field.

| Element | Attribute | Needed by (examples) | Bean file | Proposed field |
|---|---|---|---|---|
| ODM | `def:Context` | DEFINE-XML-0016, 0017 (also the `when` guard of 0119) | `ODM.java` | `@JacksonXmlProperty(isAttribute = true, localName = "Context")`<br>`    String context;` |
| MetaDataVersion | `def:CommentOID` | DEFINE-XML-0030, PMDA-DD0071 | `MetaDataVersion.java` | `@JacksonXmlProperty(isAttribute = true, localName = "CommentOID")`<br>`    String commentOID;` |
| ItemRef | `def:IsNonStandard` | DEFINE-XML-0075, PMDA-DD0130 | `ItemRef.java` | `@JacksonXmlProperty(isAttribute = true, localName = "IsNonStandard")`<br>`    String isNonStandard;` |
| ItemGroupDef | `def:IsNonStandard` | DEFINE-XML-0121, 0122, 0123, PMDA-DD0120, DD0121, DD0130 | `ItemGroupDef.java` | `@JacksonXmlProperty(isAttribute = true, localName = "IsNonStandard")`<br>`    String isNonStandard;` |
| CodeList | `def:IsNonStandard` | DEFINE-XML-0153, 0167, PMDA-DD0126, DD0128, DD0130 | `CodeList.java` | `@JacksonXmlProperty(isAttribute = true, localName = "IsNonStandard")`<br>`    String isNonStandard;` |
| EnumeratedItem | `def:ExtendedValue` | DEFINE-XML-0178, 0186, 0187, PMDA-DD0029, DD0030, DD0132 | `EnumeratedItem.java` | `@JacksonXmlProperty(isAttribute = true, localName = "ExtendedValue")`<br>`    String extendedValue;` |

Both known gaps are hereby confirmed: `ODM` lacks `def:Context`, and
`MetaDataVersion` lacks `CommentOID`. Note that `CodeListItem` already has
`ExtendedValue` — only its sibling `EnumeratedItem` is missing it.

## 2. Elements with no bean class at all

| Element | Needed by (examples) | Remark |
|---|---|---|
| `def:Class` | DEFINE-XML-0131, 0132, PMDA-DD0054, DD0055 | v2.1 models Class as an **element** with a `Name` attribute (and `def:SubClass` children). `ItemGroupDef.java` only carries the v2.0 **attribute** form (`localName = "Class"`, field `clazz`) and has no child-element field either. A `Class.java` bean plus an `ItemGroupDef` child field are needed for v2.1 rules (incl. `ParentClass`-related DEFINE-XML-0262). |
| `def:SubClass` | DEFINE-XML-0134, 0261, 0262, PMDA-DD0140 | Needs `Name` and `ParentClass` attributes. |
| `arm:AnalysisResultDisplays` | PMDA-DD0087 | No ARM (Analysis Results Metadata) bean exists at all — the whole `arm:` cluster below is unparseable with the current model. |
| `arm:ResultDisplay` | PMDA-DD0088, DD0089, DD0090 | Needs `OID`, `Name` attributes + `Description` child. |
| `arm:AnalysisResult` | PMDA-DD0091, DD0092, DD0093, DD0099, DD0100 | Needs `OID`, `ParameterOID`, `AnalysisReason`, `AnalysisPurpose` attributes. |
| `arm:AnalysisDatasets` | PMDA-DD0094 | Needs `def:CommentOID` attribute. |
| `arm:AnalysisDataset` | PMDA-DD0095 | Needs `ItemGroupOID` attribute. |
| `arm:AnalysisVariable` | PMDA-DD0096, DD0097 | Needs `ItemOID` attribute. |
| `arm:Documentation` | PMDA-DD0117 | Needs `Description` child. |
| `arm:Code` | PMDA-DD0098 | Needs `Context` attribute. |
| `StudyName` | DEFINE-XML-0018, 0019 | Not really missing: mapped as a `String` **field** on `GlobalVariables.java`. Sufficient for the presence rule (0018), but the "no more than one" cardinality rule (0019) cannot count occurrences through a scalar `String` field — needs the DOM/XSD pre-pass or a `List<String>` mapping. Same caveat for the two rows below. |
| `StudyDescription` | DEFINE-XML-0020, 0021 | See `StudyName` remark. |
| `ProtocolName` | DEFINE-XML-0022, 0023 | See `StudyName` remark. |

## 3. Parent→child element field gaps (secondary finding)

Not `(element, attribute)` pairs, but child-element wiring the slash-form
selectors need and the parent beans lack (Define-XML 2.1 added `Description`
in these places):

| Parent bean | Missing child field | Needed by | Proposed field |
|---|---|---|---|
| `ValueListDef.java` | `Description` | DEFINE-XML-0235 | `@JacksonXmlProperty(localName = "Description")`<br>`    Description description;` |
| `EnumeratedItem.java` | `Description` | DEFINE-XML-0245 | `@JacksonXmlProperty(localName = "Description")`<br>`    Description description;` |
| `CodeListItem.java` | `Description` | DEFINE-XML-0246 | `@JacksonXmlProperty(localName = "Description")`<br>`    Description description;` |

> Cardinality caveat: a scalar `Description description;` field satisfies
> presence checks but cannot detect duplicates for the three
> "no more than one Description" rules above — same situation as the
> `GlobalVariables` scalars in §2; those rules likely need the XSD pre-pass.

## 4. Dual-version representations covered

PMDA-DD0021/DD0022 describe the v2.0 representation
(`def:StandardName`/`def:StandardVersion` **on MetaDataVersion**) while v2.1
uses `def:Standard@Name`/`@Version`. Both forms were checked: all four
fields exist (`MetaDataVersion.standardName`, `MetaDataVersion.standardVersion`,
`Standard.name`, `Standard.version`). No gap.
