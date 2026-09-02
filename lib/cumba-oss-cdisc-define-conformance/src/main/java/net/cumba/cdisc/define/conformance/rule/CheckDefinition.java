package net.cumba.cdisc.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The declarative {@code Check:} body of a rule — one implementation per {@code kind} (plan §3.3).
 * Every kind carries an optional {@code when} guard ({@link Condition}); a scoped element failing
 * the guard is simply out of the rule's reach (no finding).
 *
 * <p>
 * {@code target} values are child-element bare local names, or {@code "@Attr"} for an attribute of
 * the scoped element itself. {@code attribute} values are attribute local names on the scoped
 * element. Namespace prefixes are stripped on load (the tree is namespace-agnostic).
 * </p>
 *
 * <p>
 * The CT-backed kinds ({@code term_in_ct_codelist}, {@code nci_code_known},
 * {@code term_matches_nci_code}, {@code extended_value_marking}, {@code nci_alias_required}) are
 * evaluated against the {@code CtProvider} (plan §3.6) and belong to rules declaring
 * {@code Requires: ct}; without a provider those rules SKIP before the kind is reached.
 * </p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
{
        @JsonSubTypes.Type(value = CheckDefinition.Exists.class, name = "exists"),
        @JsonSubTypes.Type(value = CheckDefinition.NotExists.class, name = "not_exists"),
        @JsonSubTypes.Type(value = CheckDefinition.CardinalityAtMost.class,
                name = "cardinality_at_most"),
        @JsonSubTypes.Type(value = CheckDefinition.MatchesRegex.class, name = "matches_regex"),
        @JsonSubTypes.Type(value = CheckDefinition.OneOf.class, name = "one_of"),
        @JsonSubTypes.Type(value = CheckDefinition.References.class, name = "references"),
        @JsonSubTypes.Type(value = CheckDefinition.UniqueAmongSiblings.class,
                name = "unique_among_siblings"),
        @JsonSubTypes.Type(value = CheckDefinition.UniqueInDocument.class,
                name = "unique_in_document"),
        @JsonSubTypes.Type(value = CheckDefinition.ConsistentAcrossDocument.class,
                name = "consistent_across_document"),
        @JsonSubTypes.Type(value = CheckDefinition.ReferencedFileExists.class,
                name = "referenced_file_exists"),
        @JsonSubTypes.Type(value = CheckDefinition.StylesheetFileExists.class,
                name = "stylesheet_file_exists"),
        @JsonSubTypes.Type(value = CheckDefinition.Custom.class, name = "custom"),
        @JsonSubTypes.Type(value = CheckDefinition.IsReferenced.class, name = "is_referenced"),
        @JsonSubTypes.Type(value = CheckDefinition.Compare.class, name = "compare"),
        @JsonSubTypes.Type(value = CheckDefinition.TermInCtCodelist.class,
                name = "term_in_ct_codelist"),
        @JsonSubTypes.Type(value = CheckDefinition.NciCodeKnown.class, name = "nci_code_known"),
        @JsonSubTypes.Type(value = CheckDefinition.TermMatchesNciCode.class,
                name = "term_matches_nci_code"),
        @JsonSubTypes.Type(value = CheckDefinition.ExtendedValueMarking.class,
                name = "extended_value_marking"),
        @JsonSubTypes.Type(value = CheckDefinition.NciAliasRequired.class,
                name = "nci_alias_required"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryDatasetLabelMatches.class,
                name = "library_dataset_label_matches"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryVariableLabelMatches.class,
                name = "library_variable_label_matches"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryCodelistRefRequired.class,
                name = "library_codelist_ref_required"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryCodelistCCodeMatches.class,
                name = "library_codelist_ccode_matches"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryQualifierLabelDecode.class,
                name = "library_qualifier_label_decode"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryCoreMandatory.class,
                name = "library_core_mandatory"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryCtAliasRequired.class,
                name = "library_ct_alias_required"),
        @JsonSubTypes.Type(value = CheckDefinition.LibraryStandardVersionKnown.class,
                name = "library_standard_version_known"),
})
public sealed interface CheckDefinition
{

    /** The optional guard; a scoped element failing it is out of the rule's reach. */
    @Nullable
    Condition when();


    /** Load-time structural validation; implementations throw {@link IllegalStateException}. */
    default void validate()
    {
        Condition guard = when();
        if (guard != null)
        {
            guard.validate();
        }
    }

    /** The scoped element must have the target child element / attribute. */
    record Exists(String target, @Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * The scoped element must NOT have the target child element / attribute. Note the deliberate
     * symmetry with {@link Exists}: a present-but-blank attribute counts as <em>missing</em> for
     * both kinds (the sheets' "must be included and cannot be empty" presence wording). Where a
     * specific sheet row treats an empty attribute as "provided", the author flags it at review.
     */
    record NotExists(String target, @Nullable Condition when) implements CheckDefinition
    {
    }


    /** At most {@code max} occurrences of the target child element under the scoped element. */
    record CardinalityAtMost(String target, int max,
            @Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * The attribute's value (when present — presence is a separate rule) must match. Exactly one of
     * {@code pattern} (an explicit regex, full-match) or {@code format} (a named canned pattern
     * from {@link RegexFormats}) must be given.
     */
    record MatchesRegex(String attribute, @Nullable String pattern, @Nullable String format,
            @Nullable Condition when) implements CheckDefinition
    {

        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if ((pattern == null) == (format == null))
            {
                throw new IllegalStateException(
                        "matches_regex needs exactly one of pattern/format");
            }
            if (format != null)
            {
                RegexFormats.byName(format);
            }
            else
            {
                try
                {
                    java.util.regex.Pattern.compile(pattern);
                }
                catch (java.util.regex.PatternSyntaxException e)
                {
                    throw new IllegalStateException(
                            "matches_regex pattern does not compile: " + e.getMessage(), e);
                }
            }
        }
    }


    /** The attribute's value (when present) must be one of the enumerated values. */
    record OneOf(String attribute, List<String> values, @Nullable Boolean caseInsensitive,
            @Nullable Condition when) implements CheckDefinition
    {

        public OneOf
        {
            values = values == null ? List.of() : List.copyOf(values);
        }


        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if (values.isEmpty())
            {
                throw new IllegalStateException("one_of needs a non-empty values list");
            }
        }


        public boolean caseInsensitiveOrDefault()
        {
            return Boolean.TRUE.equals(caseInsensitive);
        }
    }


    /**
     * The attribute's value (when present) must resolve, via the document-wide OID index, to an
     * existing element of type {@code targetElement} keyed by {@code targetKey} (default
     * {@code OID}).
     */
    record References(String attribute, String targetElement, @Nullable String targetKey,
            @Nullable Condition when) implements CheckDefinition
    {

        public String targetKeyOrDefault()
        {
            return targetKey == null ? "OID" : targetKey;
        }
    }


    /** The attribute's value must be unique among same-named siblings under one parent. */
    record UniqueAmongSiblings(String attribute,
            @Nullable Condition when) implements CheckDefinition
    {
    }


    /** The attribute's value must be unique across all scoped elements in the document. */
    record UniqueInDocument(String attribute, @Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * The value the {@code path} resolves to (first match per scoped element) must be consistent
     * across all scoped elements passing the guard; elements deviating from the first-seen value
     * are flagged.
     */
    record ConsistentAcrossDocument(String path,
            @Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * The attribute's href-like value must resolve to an existing file relative to the submission
     * folder. The owning rule must declare {@code Requires: folder} (enforced at load).
     */
    record ReferencedFileExists(String attribute,
            @Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * Every {@code <?xml-stylesheet?>} prolog PI's {@code href} must resolve to an existing file
     * relative to the submission folder (PMDA DD0085 — "Missing Define XSL"). The hrefs come from
     * {@code DocumentContext#stylesheetHrefs()} (the PI is prolog content the element tree cannot
     * see); scope the rule to {@code Document}. The owning rule must declare
     * {@code Requires: folder} (enforced at load).
     */
    record StylesheetFileExists(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * Escape hatch: a hand-written {@code CustomCheck} implementation class. Budget-capped at 15
     * across the whole corpus (plan §11 Q4) — every use is called out at review.
     */
    record Custom(String className, @Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * Orphan detection (plan §3.35): the scoped element's {@code key} attribute value (default
     * {@code OID}) must be referenced by at least one {@code by} descriptor — an attribute,
     * optionally restricted to one referring element type ({@code element} absent = any element).
     * E.g. PMDA DD0080: every {@code MethodDef} must be referenced by some {@code ItemRef}
     * {@code MethodOID}.
     */
    record IsReferenced(@Nullable String key, List<Referrer> by,
            @Nullable Condition when) implements CheckDefinition
    {

        public IsReferenced
        {
            by = by == null ? List.of() : List.copyOf(by);
        }


        public String keyOrDefault()
        {
            return key == null ? "OID" : key;
        }


        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if (by.isEmpty())
            {
                throw new IllegalStateException("is_referenced needs a non-empty by list");
            }
        }
    }


    /** One referrer descriptor of {@link IsReferenced}. */
    record Referrer(@Nullable String element, String attribute)
    {
    }


    /**
     * Cross-path comparison (plan §3.35): the first value each side resolves to must satisfy
     * {@code op}. Either side missing ⇒ no finding (presence is a separate rule). Paths may use the
     * deref segment {@code @Attr->Element@Key} (follow an OID-valued attribute to its target).
     * {@code op}: {@code equals} (default; optionally case-insensitive) or {@code less_or_equal}
     * (numeric; non-numeric values are skipped). A side's {@code *Transform: "file-basename"}
     * reduces a href-like value to its bare file name without extension (PMDA DD0052 / CDISC 112
     * SASDatasetName ↔ ArchiveLocation file name).
     */
    record Compare(String left, String right, @Nullable String op,
            @Nullable Boolean caseInsensitive, @Nullable String leftTransform,
            @Nullable String rightTransform, @Nullable Condition when) implements CheckDefinition
    {

        public String opOrDefault()
        {
            return op == null ? "equals" : op;
        }


        public boolean caseInsensitiveOrDefault()
        {
            return Boolean.TRUE.equals(caseInsensitive);
        }


        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if (!"equals".equals(opOrDefault()) && !"less_or_equal".equals(opOrDefault()))
            {
                throw new IllegalStateException(
                        "compare op must be equals or less_or_equal, got " + opOrDefault());
            }
            for (String transform : new String[]
            {
                    leftTransform, rightTransform
            })
            {
                if (transform != null && !"file-basename".equals(transform))
                {
                    throw new IllegalStateException(
                            "unknown compare transform '" + transform + "'");
                }
            }
        }
    }


    /**
     * CT membership (plan §3.3, {@code Requires: ct}): the scoped element's {@code attribute} value
     * (default {@code CodedValue}) must be a term submission value of the CT codelist. The codelist
     * is either named explicitly by {@code cCode} (codelist-less targets like
     * {@code def:Class/@Name} against GNRLOBSC — PMDA DD0055 2.1 leg / CDISC 132) or resolved from
     * the enclosing {@code CodeList}'s {@code Alias[@Context="nci:ExtCodeID"]/@Name} (PMDA DD0024,
     * CDISC 179/192). An unresolvable codelist produces no finding.
     *
     * <p>
     * {@code nonExtensibleOnly} restricts the check to non-extensible CT codelists (DD0024's "a
     * non-extensible CDISC controlled terminology"); {@code exemptExtendedValues} skips items
     * marked {@code def:ExtendedValue="Yes"} (CDISC 179/192 — declared extensions are governed by
     * the {@code extended_value_marking} rules instead).
     * </p>
     */
    record TermInCtCodelist(@Nullable String attribute, @Nullable String cCode,
            @Nullable Boolean nonExtensibleOnly, @Nullable Boolean exemptExtendedValues,
            @Nullable Condition when) implements CheckDefinition
    {

        public String attributeOrDefault()
        {
            return attribute == null ? "CodedValue" : attribute;
        }


        public boolean nonExtensibleOnlyOrDefault()
        {
            return Boolean.TRUE.equals(nonExtensibleOnly);
        }


        public boolean exemptExtendedValuesOrDefault()
        {
            return Boolean.TRUE.equals(exemptExtendedValues);
        }
    }


    /**
     * CT c-code existence ({@code Requires: ct}): the scoped {@code Alias} element's {@code Name}
     * (only aliases with {@code Context="nci:ExtCodeID"} are considered) must be known to CT.
     * {@code level: "codelist"} (PMDA DD0033) checks the c-code resolves to a CT codelist;
     * {@code level: "term"} (PMDA DD0034) checks the c-code is among the term c-codes of the
     * enclosing {@code CodeList}'s resolved CT codelist (unresolvable enclosing codelist ⇒ no
     * finding).
     */
    record NciCodeKnown(String level, @Nullable Condition when) implements CheckDefinition
    {

        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            // Inlined (not a shared private helper): SpotBugs' UPM detector cannot see
            // nested-record calls into a private interface method and reports it uncalled.
            if (!"codelist".equals(level) && !"term".equals(level))
            {
                throw new IllegalStateException(
                        "nci_code_known level must be codelist or term, got '" + level + "'");
            }
        }
    }


    /**
     * Term / NCI-code agreement ({@code Requires: ct}, PMDA DD0028): the scoped item's
     * {@code CodedValue} must equal the CT submission value carried by the item's own
     * {@code Alias[@Context="nci:ExtCodeID"]/@Name} c-code, looked up in the enclosing
     * {@code CodeList}'s resolved CT codelist. Items without an nci alias, unresolvable codelists,
     * and c-codes absent from the codelist (DD0034's finding) are skipped.
     */
    record TermMatchesNciCode(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * {@code def:ExtendedValue} vs CT extensibility ({@code Requires: ct}), on the scoped
     * {@code EnumeratedItem}/{@code CodeListItem}; the codelist is resolved via the enclosing
     * {@code CodeList}'s nci:ExtCodeID alias (unresolvable ⇒ no finding).
     *
     * <ul>
     * <li>{@code mode: "required"} (CDISC 186/201): the attribute must be present when the CT
     * codelist is extensible and the {@code CodedValue} is not one of its submission values (the
     * sheets' {@code @def:StandardOID}-provided guard is the rule's {@code when} clause).</li>
     * <li>{@code mode: "forbidden"} (CDISC 187/202): the attribute must not be present when the CT
     * codelist is non-extensible.</li>
     * </ul>
     */
    record ExtendedValueMarking(String mode, @Nullable Condition when) implements CheckDefinition
    {

        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if (!"required".equals(mode) && !"forbidden".equals(mode))
            {
                throw new IllegalStateException(
                        "extended_value_marking mode must be required or forbidden, got '" + mode
                                + "'");
            }
        }
    }


    /**
     * IG dataset-label agreement ({@code Requires: library}, PMDA DD0136): the scoped
     * {@code ItemGroupDef}'s English {@code Description/TranslatedText} must equal the label the
     * governing SDTMIG/SENDIG standard defines for the dataset ({@code @Domain}, else
     * {@code @Name}). The standard resolves via {@code @def:StandardOID → def:Standard} (2.1), else
     * the {@code MetaDataVersion}'s {@code @def:StandardName}/{@code @def:StandardVersion} (2.0);
     * non-SDTMIG/SENDIG standards, unresolvable standards, library-unknown datasets, and absent
     * descriptions are out of reach (their presence is other rules' beat).
     */
    record LibraryDatasetLabelMatches(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * IG variable-label agreement ({@code Requires: library}, PMDA DD0137, 2.1-only): scoped to
     * {@code ItemGroupDef/ItemRef}, the referenced {@code ItemDef}'s English description must equal
     * the label the parent dataset's governing SDTMIG/SENDIG standard defines for the variable.
     * Same out-of-reach semantics as {@link LibraryDatasetLabelMatches}.
     */
    record LibraryVariableLabelMatches(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * IG CT-requirement → CodeListRef presence ({@code Requires: library}, PMDA DD0124, 2.1-only):
     * scoped to {@code ItemGroupDef/ItemRef}; fires when the library assigns the variable a CT
     * codelist ({@code variableCodelistCCode} present) but the referenced {@code ItemDef} carries
     * no {@code CodeListRef}.
     */
    record LibraryCodelistRefRequired(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * IG codelist c-code agreement ({@code Requires: library}, PMDA DD0118): scoped to
     * {@code ItemGroupDef/ItemRef}; when the library assigns the variable a CT codelist AND the
     * referenced {@code ItemDef}'s {@code CodeListRef} resolves to a {@code CodeList} carrying an
     * {@code Alias[@Context="nci:ExtCodeID"]}, the two c-codes must match. A missing alias is
     * DD0031's beat, not this rule's.
     */
    record LibraryCodelistCCodeMatches(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * FATESTCD/FATEST qualifier-label agreement ({@code Requires: library}, PMDA DD0116): scoped
     * (via {@code when}) to the {@code ItemDef} named FATESTCD; for every {@code CodeListItem} of
     * its {@code CodeListRef}-resolved {@code CodeList} whose {@code CodedValue} names an SDTM
     * Event/Intervention qualifier fragment ({@code qualifierVariableLabel} present), the English
     * {@code Decode/TranslatedText} — define.xml's carrier of the FATEST value — must equal that
     * qualifier's label. {@code EnumeratedItem}s carry no decode and are out of reach.
     */
    record LibraryQualifierLabelDecode(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * IG Core designation → ItemRef Mandatory ({@code Requires: library}, CDISC 67, 2.1-only):
     * scoped to {@code ItemGroupDef/ItemRef}; fires when the library designates the variable
     * {@code Core="Req"} in the parent dataset's governing SDTM/SEND-family standard but the
     * ItemRef's {@code Mandatory} is present and not {@code "Yes"}. An absent {@code Mandatory} is
     * the XSD's beat (required attribute) and out of reach here.
     */
    record LibraryCoreMandatory(@Nullable Condition when) implements CheckDefinition
    {
    }


    /**
     * nci:ExtCodeID alias presence keyed off the library's per-variable CT requirement
     * ({@code Requires: library}, CDISC 97/98/99): a CodeList is in reach when at least one
     * {@code ItemGroupDef/ItemRef}-bound variable referencing it has a library-assigned CT codelist
     * ({@code variableCodelistCCode} present) — the sheets' "variable that requires CDISC
     * Controlled Terminology according to the standard".
     *
     * <ul>
     * <li>{@code level: "codelist"} (97): the CodeList itself must carry an
     * {@code Alias[@Context="nci:ExtCodeID"]} — the spec's codelist alias.</li>
     * <li>{@code level: "enumerated_item"} (98): every {@code EnumeratedItem} without a
     * {@code def:ExtendedValue} attribute must carry one.</li>
     * <li>{@code level: "code_list_item"} (99): the {@code CodeListItem} twin of 98.</li>
     * </ul>
     */
    record LibraryCtAliasRequired(String level, @Nullable Condition when) implements CheckDefinition
    {

        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if (!"codelist".equals(level) && !"enumerated_item".equals(level)
                    && !"code_list_item".equals(level))
            {
                throw new IllegalStateException(
                        "library_ct_alias_required level must be codelist, enumerated_item or "
                                + "code_list_item, got '" + level + "'");
            }
        }
    }


    /**
     * Published standard version ({@code Requires: library}, CDISC 263): the scoped element's
     * version attribute must be one of {@code publishedStandardVersions} for its name attribute; a
     * name the library does not know is out of reach. Attribute names default to
     * {@code Name}/{@code Version} ({@code def:Standard}, 2.1); the 2.0 leg re-targets the
     * {@code MetaDataVersion}'s {@code def:StandardName}/{@code def:StandardVersion}.
     */
    record LibraryStandardVersionKnown(@Nullable String nameAttribute,
            @Nullable String versionAttribute, @Nullable Condition when) implements CheckDefinition
    {

        public String nameAttributeOrDefault()
        {
            return nameAttribute == null ? "Name" : nameAttribute;
        }


        public String versionAttributeOrDefault()
        {
            return versionAttribute == null ? "Version" : versionAttribute;
        }
    }


    /**
     * nci:ExtCodeID alias presence ({@code Requires: ct}): fires when the scoped element carries no
     * {@code Alias[@Context="nci:ExtCodeID"]} although CT says it should.
     *
     * <ul>
     * <li>{@code level: "codelist"} (PMDA DD0031): the scoped {@code CodeList} is "defined in CDISC
     * Controlled Terminology" — operationalised as its {@code Name} resolving via
     * {@code CtProvider.codelistByName} (no name match ⇒ no finding, conservatively).</li>
     * <li>{@code level: "term"} (PMDA DD0032): the scoped item's {@code CodedValue} is a term
     * submission value of the enclosing {@code CodeList}'s resolved CT codelist.</li>
     * </ul>
     */
    record NciAliasRequired(String level, @Nullable Condition when) implements CheckDefinition
    {

        @Override
        public void validate()
        {
            CheckDefinition.super.validate();
            if (!"codelist".equals(level) && !"term".equals(level))
            {
                throw new IllegalStateException(
                        "nci_alias_required level must be codelist or term, got '" + level + "'");
            }
        }
    }

}
