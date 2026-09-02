package net.cumba.cdisc.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import net.cumba.cdisc.define.conformance.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * One Define-XML conformance rule, mapped 1-1 from its YAML file under the external rules directory
 * ({@code rules/<RULE_SET>/<Rule_Id>.yaml}).
 *
 * @param ruleId
 *            {@code DEFINE-XML-0065} / {@code PMDA-DD0024}
 * @param sheetRuleIdentifier
 *            the source sheet's own id, verbatim ({@code "65"} / {@code "DD0024"})
 * @param ruleSet
 *            which sheet this mirrors
 * @param element
 *            the scoped element selector: a bare local name ({@code ItemRef}), a parent-qualified
 *            form ({@code ValueListDef/ItemRef} — the last segment scoped to elements whose
 *            ancestor chain matches), or {@code Document} for document-level rules
 * @param attribute
 *            the attribute the rule is about, for reporting (the check body names its own operands)
 * @param applicableVersions
 *            Define-XML versions the rule applies to, from the sheet ({@code ["2.0","2.1"]})
 * @param sourceType
 *            CDISC sheet's {@code Source Type} ({@code Schema}/{@code Specification}); null for
 *            PMDA rules
 * @param severity
 *            PMDA severity column verbatim; null ⇒ {@code ERROR} (all CDISC rules)
 * @param requires
 *            optional external input; absent input ⇒ SKIP (plan §3.6)
 * @param plainTextRule
 *            the sheet's normative rule text, verbatim
 * @param message
 *            the finding message template; {@code ${value}} is replaced with the offending value
 * @param check
 *            the declarative check body
 */
public record ConformanceRule(//
        @JsonProperty("Rule_Id") String ruleId, //
        @JsonProperty("Sheet_Rule_Identifier") String sheetRuleIdentifier, //
        @JsonProperty("Rule_Set") RuleSet ruleSet, //
        @JsonProperty("Element") String element, //
        @JsonProperty("Attribute") @Nullable String attribute, //
        @JsonProperty("Applicable_Versions") List<String> applicableVersions, //
        @JsonProperty("Source_Type") @Nullable String sourceType, //
        @JsonProperty("Severity") @Nullable Severity severity, //
        @JsonProperty("Requires") @Nullable Requires requires, //
        @JsonProperty("Plain_Text_Rule") String plainTextRule, //
        @JsonProperty("Message") String message, //
        @JsonProperty("Check") CheckDefinition check)
{

    public ConformanceRule
    {
        applicableVersions = applicableVersions == null ? List.of()
                : List.copyOf(applicableVersions);
    }


    /** The effective severity: the PMDA sheet column, or {@code ERROR} for CDISC rules. */
    public Severity effectiveSeverity()
    {
        return severity == null ? Severity.ERROR : severity;
    }


    /**
     * Load-time validation: id/element/message present, the check body is structurally sound, and
     * folder-gated kinds carry the matching {@code Requires} declaration.
     */
    public void validate()
    {
        require(!ruleId.isBlank(), "Rule_Id is blank");
        require(!element.isBlank(), "Element is blank");
        require(!message.isBlank(), "Message is blank");
        require(!applicableVersions.isEmpty(), "Applicable_Versions is empty");
        check.validate();
        if (check instanceof CheckDefinition.ReferencedFileExists && requires != Requires.FOLDER)
        {
            throw new IllegalStateException(
                    ruleId + ": referenced_file_exists requires 'Requires: folder'");
        }
        if (check instanceof CheckDefinition.StylesheetFileExists)
        {
            if (requires != Requires.FOLDER)
            {
                throw new IllegalStateException(
                        ruleId + ": stylesheet_file_exists requires 'Requires: folder'");
            }
            // The hrefs live on the context, not the scoped node — any wider scope would repeat
            // every finding once per scoped node.
            if (!"Document".equals(element))
            {
                throw new IllegalStateException(
                        ruleId + ": stylesheet_file_exists must be scoped 'Element: Document'");
            }
        }
        boolean libraryKind = check instanceof CheckDefinition.LibraryDatasetLabelMatches
                || check instanceof CheckDefinition.LibraryVariableLabelMatches
                || check instanceof CheckDefinition.LibraryCodelistRefRequired
                || check instanceof CheckDefinition.LibraryCodelistCCodeMatches
                || check instanceof CheckDefinition.LibraryQualifierLabelDecode
                || check instanceof CheckDefinition.LibraryCoreMandatory
                || check instanceof CheckDefinition.LibraryCtAliasRequired
                || check instanceof CheckDefinition.LibraryStandardVersionKnown;
        if (libraryKind && requires != Requires.LIBRARY)
        {
            throw new IllegalStateException(
                    ruleId + ": library-backed kinds require 'Requires: library'");
        }
    }


    private void require(boolean aCondition, String aMessage)
    {
        if (!aCondition)
        {
            throw new IllegalStateException(ruleId + ": " + aMessage);
        }
    }

}
