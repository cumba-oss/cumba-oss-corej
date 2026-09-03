package net.cumba.corej.define.conformance.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.define.conformance.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * {@link CheckDefinition} Jackson-YAML mapping per {@code kind}, plus {@link ConformanceRule}
 * load-time validation and the case-insensitive enum factories.
 */
class CheckDefinitionTest
{

    /** A structurally valid rule around the given {@code Check:} body (indented two spaces). */
    private static ConformanceRule rule(String aCheckBody)
    {
        return RuleRepository.parse("""
                Rule_Id: "DEFINE-XML-9999"
                Sheet_Rule_Identifier: "9999"
                Rule_Set: "CDISC"
                Element: "ItemRef"
                Attribute: null
                Applicable_Versions: ["2.0", "2.1"]
                Source_Type: "Specification"
                Plain_Text_Rule: "Test rule."
                Message: "Test message."
                Check:
                """ + aCheckBody.indent(2), "test");
    }


    @Test
    void existsRoundTrip()
    {
        ConformanceRule rule = rule("""
                kind: "exists"
                target: "@ItemOID"
                """);
        CheckDefinition.Exists check = assertInstanceOf(CheckDefinition.Exists.class, rule.check());
        assertEquals("@ItemOID", check.target());
        assertNull(check.when());
    }


    @Test
    void notExistsRoundTrip()
    {
        CheckDefinition.NotExists check = assertInstanceOf(CheckDefinition.NotExists.class, rule("""
                kind: "not_exists"
                target: "Alias"
                """).check());
        assertEquals("Alias", check.target());
    }


    @Test
    void cardinalityAtMostRoundTrip()
    {
        CheckDefinition.CardinalityAtMost check = assertInstanceOf(
                CheckDefinition.CardinalityAtMost.class, rule("""
                        kind: "cardinality_at_most"
                        target: "Study"
                        max: 1
                        """).check());
        assertEquals("Study", check.target());
        assertEquals(1, check.max());
    }


    @Test
    void matchesRegexRoundTripWithPattern()
    {
        CheckDefinition.MatchesRegex check = assertInstanceOf(CheckDefinition.MatchesRegex.class,
                rule("""
                        kind: "matches_regex"
                        attribute: "OrderNumber"
                        pattern: "[0-9]+"
                        """).check());
        assertEquals("OrderNumber", check.attribute());
        assertEquals("[0-9]+", check.pattern());
        assertNull(check.format());
    }


    @Test
    void matchesRegexRoundTripWithFormat()
    {
        CheckDefinition.MatchesRegex check = assertInstanceOf(CheckDefinition.MatchesRegex.class,
                rule("""
                        kind: "matches_regex"
                        attribute: "CreationDateTime"
                        format: "iso8601-datetime"
                        """).check());
        assertEquals("iso8601-datetime", check.format());
        assertNull(check.pattern());
    }


    @Test
    void matchesRegexRejectsPatternAndFormatTogether()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rule("""
                kind: "matches_regex"
                attribute: "OrderNumber"
                pattern: "[0-9]+"
                format: "integer"
                """));
        assertTrue(e.getMessage().contains("exactly one of pattern/format"), e.getMessage());
    }


    @Test
    void matchesRegexRejectsNeitherPatternNorFormat()
    {
        assertThrows(IllegalStateException.class, () -> rule("""
                kind: "matches_regex"
                attribute: "OrderNumber"
                """));
    }


    @Test
    void matchesRegexRejectsUnknownFormatName()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rule("""
                kind: "matches_regex"
                attribute: "OrderNumber"
                format: "no-such-format"
                """));
        assertTrue(e.getMessage().contains("no-such-format"), e.getMessage());
    }


    @Test
    void oneOfRoundTripAndCaseInsensitiveDefault()
    {
        CheckDefinition.OneOf check = assertInstanceOf(CheckDefinition.OneOf.class, rule("""
                kind: "one_of"
                attribute: "Mandatory"
                values: ["Yes", "No"]
                """).check());
        assertEquals(List.of("Yes", "No"), check.values());
        assertFalse(check.caseInsensitiveOrDefault());
    }


    @Test
    void oneOfCaseInsensitiveTrueWhenDeclared()
    {
        CheckDefinition.OneOf check = assertInstanceOf(CheckDefinition.OneOf.class, rule("""
                kind: "one_of"
                attribute: "Mandatory"
                values: ["Yes", "No"]
                caseInsensitive: true
                """).check());
        assertTrue(check.caseInsensitiveOrDefault());
    }


    @Test
    void referencesRoundTripAndTargetKeyDefault()
    {
        CheckDefinition.References check = assertInstanceOf(CheckDefinition.References.class,
                rule("""
                        kind: "references"
                        attribute: "ItemOID"
                        targetElement: "ItemDef"
                        """).check());
        assertEquals("ItemDef", check.targetElement());
        assertNull(check.targetKey());
        assertEquals("OID", check.targetKeyOrDefault());
    }


    @Test
    void referencesExplicitTargetKeyWins()
    {
        CheckDefinition.References check = assertInstanceOf(CheckDefinition.References.class,
                rule("""
                        kind: "references"
                        attribute: "leafID"
                        targetElement: "leaf"
                        targetKey: "ID"
                        """).check());
        assertEquals("ID", check.targetKeyOrDefault());
    }


    @Test
    void uniqueAmongSiblingsRoundTrip()
    {
        CheckDefinition.UniqueAmongSiblings check = assertInstanceOf(
                CheckDefinition.UniqueAmongSiblings.class, rule("""
                        kind: "unique_among_siblings"
                        attribute: "lang"
                        """).check());
        assertEquals("lang", check.attribute());
    }


    @Test
    void uniqueInDocumentRoundTrip()
    {
        CheckDefinition.UniqueInDocument check = assertInstanceOf(
                CheckDefinition.UniqueInDocument.class, rule("""
                        kind: "unique_in_document"
                        attribute: "OID"
                        """).check());
        assertEquals("OID", check.attribute());
    }


    @Test
    void consistentAcrossDocumentRoundTrip()
    {
        CheckDefinition.ConsistentAcrossDocument check = assertInstanceOf(
                CheckDefinition.ConsistentAcrossDocument.class, rule("""
                        kind: "consistent_across_document"
                        path: "@StudyOID"
                        """).check());
        assertEquals("@StudyOID", check.path());
    }


    @Test
    void customRoundTripWithWhenGuard()
    {
        CheckDefinition.Custom check = assertInstanceOf(CheckDefinition.Custom.class, rule("""
                kind: "custom"
                className: "com.example.SomeCheck"
                when:
                  path: "@Type"
                  equals: "Collected"
                """).check());
        assertEquals("com.example.SomeCheck", check.className());
        assertNotNull(check.when());
    }


    @Test
    void malformedWhenGuardFailsRuleValidation()
    {
        assertThrows(IllegalStateException.class, () -> rule("""
                kind: "exists"
                target: "@ItemOID"
                when:
                  path: "@Type"
                """));
    }


    @Test
    void referencedFileExistsRequiresFolderDeclaration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rule("""
                kind: "referenced_file_exists"
                attribute: "href"
                """));
        assertTrue(e.getMessage().contains("Requires: folder"), e.getMessage());
    }


    @Test
    void referencedFileExistsAcceptedWithFolderRequirement()
    {
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "PMDA-DD9999"
                Sheet_Rule_Identifier: "DD9999"
                Rule_Set: "PMDA"
                Element: "def:leaf"
                Attribute: "href"
                Applicable_Versions: ["2.0"]
                Severity: "Warning"
                Requires: "folder"
                Plain_Text_Rule: "Test rule."
                Message: "Missing file [${value}]."
                Check:
                  kind: "referenced_file_exists"
                  attribute: "href"
                """, "test");
        assertInstanceOf(CheckDefinition.ReferencedFileExists.class, rule.check());
        assertEquals(Requires.FOLDER, rule.requires());
        assertEquals(RuleSet.PMDA, rule.ruleSet());
        assertEquals(Severity.WARNING, rule.effectiveSeverity());
    }

    // ------------------------------------------------------------------
    // ConformanceRule.validate()
    // ------------------------------------------------------------------


    private static ConformanceRule ruleWith(String aRuleId, String aElement, String aMessage,
            String aVersions)
    {
        return new ConformanceRule(aRuleId, "1", RuleSet.CDISC, aElement, null,
                aVersions.isEmpty() ? List.of() : List.of(aVersions), "Specification", null, null,
                "Plain text.", aMessage, new CheckDefinition.Exists("@ItemOID", null));
    }


    @Test
    void validateRejectsBlankRuleId()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ruleWith(" ", "ItemRef", "m", "2.1").validate());
        assertTrue(e.getMessage().contains("Rule_Id is blank"), e.getMessage());
    }


    @Test
    void validateRejectsBlankElement()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ruleWith("R-1", " ", "m", "2.1").validate());
        assertTrue(e.getMessage().contains("Element is blank"), e.getMessage());
    }


    @Test
    void validateRejectsBlankMessage()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ruleWith("R-1", "ItemRef", " ", "2.1").validate());
        assertTrue(e.getMessage().contains("Message is blank"), e.getMessage());
    }


    @Test
    void validateRejectsEmptyVersions()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ruleWith("R-1", "ItemRef", "m", "").validate());
        assertTrue(e.getMessage().contains("Applicable_Versions is empty"), e.getMessage());
    }


    @Test
    void effectiveSeverityDefaultsToError()
    {
        assertEquals(Severity.ERROR, ruleWith("R-1", "ItemRef", "m", "2.1").effectiveSeverity());
    }

    // ------------------------------------------------------------------
    // Enum factories
    // ------------------------------------------------------------------


    @Test
    void severityFromJsonIsCaseInsensitive()
    {
        assertEquals(Severity.REJECT, Severity.fromJson("Reject"));
        assertEquals(Severity.ERROR, Severity.fromJson("error"));
        assertEquals(Severity.WARNING, Severity.fromJson("WARNING"));
        assertNull(Severity.fromJson(null));
    }


    @Test
    void requiresFromJsonIsCaseInsensitive()
    {
        assertEquals(Requires.CT, Requires.fromJson("ct"));
        assertEquals(Requires.FOLDER, Requires.fromJson("Folder"));
        assertNull(Requires.fromJson(null));
    }

}
