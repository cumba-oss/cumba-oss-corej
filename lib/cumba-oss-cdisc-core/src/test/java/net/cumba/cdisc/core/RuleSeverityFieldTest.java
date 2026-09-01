package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Plan C phase 2 — the rule-level {@code Severity} field: default, round-trip, and load gate. */
class RuleSeverityFieldTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static Rule parse(String aSeverityLine) throws IOException
    {
        return YAML.readValue("""
                Core:
                  Id: "T-0001"
                Description: "Raise an error when something is wrong."
                """ + aSeverityLine + """
                Check:
                  expression: >-
                    1 == 1
                """, Rule.class);
    }


    @Test
    @DisplayName("absent ⇒ ERROR, and the raw stays null")
    void absentMeansError() throws IOException
    {
        Rule r = parse("");
        assertNull(r.getSeverity(), "the typed field really is absent");
        assertNull(r.getRawSeverity());
        assertEquals(Severity.ERROR, r.effectiveSeverity(),
                "absent is not 'unknown' — it is ERROR, stated once on the model");
        assertNull(r.getSeverityJson(), "and it is never written back");
    }


    @ParameterizedTest(name = "Severity: \"{0}\" -> {1}")
    @CsvSource(
    {
            "Reject, REJECT", "Error, ERROR", "Warning, WARNING", "Info, INFO"
    })
    @DisplayName("all four authorable values round-trip through the Jackson pair")
    void allFourRoundTrip(String authored, Severity expected) throws IOException
    {
        Rule r = parse("Severity: \"" + authored + "\"\n");
        assertEquals(expected, r.getSeverity());
        assertEquals(expected, r.effectiveSeverity());
        assertEquals(authored, r.getSeverityJson(), "serialises back to the canonical spelling");
        RulePackageLoader.validateEnumFields(r);
        assertNull(r.getLoadError(), authored + " is a valid authored value");
    }


    @Test
    @DisplayName("⚑ Severity: \"Error\" LOADS — it is non-canonical, not invalid")
    void redundantErrorLoadsAndIsNotRejected() throws IOException
    {
        Rule r = parse("Severity: \"Error\"\n");
        RulePackageLoader.validateEnumFields(r);
        assertNull(r.getLoadError(),
                "rejecting it would make the default asymmetric with Sensitivity; stripping it is"
                        + " RuleCanonicalizer's job, not the loader's");
        assertEquals(Severity.ERROR, r.effectiveSeverity());
    }


    @Test
    @DisplayName("present-but-invalid is a per-rule LOAD ERROR naming the rule")
    void invalidValueIsALoadError() throws IOException
    {
        Rule r = parse("Severity: \"Severe\"\n");
        assertNull(r.getSeverity(), "the lenient authoring door leaves it unparsed…");
        assertEquals("Severe", r.getRawSeverity(), "…and keeps the raw for the error message");
        RulePackageLoader.validateEnumFields(r);
        assertNotNull(r.getLoadError());
        assertEquals("[T-0001] Invalid Severity 'Severe' — expected one of: "
                + "Reject, Error, Warning, Info", r.getLoadError());
    }


    @Test
    @DisplayName("⛔ NOTICE parses as a report constant but is NOT authorable on a rule")
    void noticeIsNotAuthorable() throws IOException
    {
        // The generic present-but-unrecognised gate would have accepted this, because it parses.
        Rule r = parse("Severity: \"Notice\"\n");
        RulePackageLoader.validateEnumFields(r);
        assertNotNull(r.getLoadError(), "NOTICE is a report-only kind, authored by no rule");
        assertTrue(r.getLoadError().contains("Invalid Severity 'Notice'"));
    }


    @Test
    @DisplayName("the typed programmatic setter keeps the raw in sync (F5)")
    void typedSetterSyncsRaw()
    {
        Rule r = new Rule();
        r.setSeverity(Severity.WARNING);
        assertEquals("Warning", r.getRawSeverity());
        r.setSeverity(null);
        assertNull(r.getRawSeverity());
        assertEquals(Severity.ERROR, r.effectiveSeverity());
    }

}
