package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PACK I E3 (PI-C9): a list-valued <b>library</b> attribute ({@code library_variable_codelist_
 * coded_values}, {@code _coded_codes}, …) reports in the same canonical sorted {@code [a, b]} form
 * as its define twin ({@code formatListOutput}, C14) on every path that puts library keys — the
 * per-row path ({@code buildVariableMetadata}), the column-level path
 * ({@code buildVariableViolation}), the Define universe ({@code buildDefineVariableViolation}) and
 * the default projection. The library provider encodes a list with the same
 * {@code DefineMetadataListCodec} JSON the define provider uses, so one rendering serves both; the
 * raw JSON ({@code ["SUPINE","STANDING"]}) used to leak into the report on the three library puts.
 */
class LibraryCodelistListRenderingTest
{

    private static final String VALUES = "[\"SUPINE\",\"STANDING\"]";

    private static final String CODES = "[\"C62167\",\"C62166\"]";

    private static Rule load(String json) throws Exception
    {
        Rule r = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}").getRules()
                .get("R1");
        assertNull(r.getLoadError(), r.getLoadError());
        assertNotNull(r.getCheckExpr(), "must compile");
        return r;
    }


    private static Rule rule(String sensitivity, String check, String outputVariables)
        throws Exception
    {
        return load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"" + sensitivity + "\","
                + "\"Check\":{\"all\":[{\"expression\":\"" + check.replace("\"", "\\\"") + "\"}]},"
                + "\"Outcome\":{\"Message\":\"m\""
                + (outputVariables == null ? "" : ",\"Output_Variables\":" + outputVariables)
                + "}}");
    }


    /** Library provider: VS.VSPOS carries the codelist, spelled unsorted. */
    private static StubMetadataProvider library()
    {
        return new StubMetadataProvider().variable("VS", Map.of("name", "VSPOS",
                "codelist_coded_values", VALUES, "codelist_coded_codes", CODES));
    }


    private static IDataTable vs()
    {
        return MockTable.of().name("VS").col("VSPOS", "SUPINE", "STANDING").col("VSTEST", "H", "W")
                .build();
    }


    private static Map<String, String> firstValues(RuleExecutionResult r)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertTrue(r.hasViolations(), "the VSPOS finding is the fixture's whole point");
        return r.getViolations().getFirst().getValues();
    }


    /**
     * (a) The per-row path: the Check reads LIBRARY, so the per-variable metadata map carries the
     * library keys ({@code buildVariableMetadata}) and the row projection reads them from there.
     */
    @Test
    @DisplayName("(a) the per-row path renders the library list sorted")
    void perRowPath() throws Exception
    {
        Rule rule = rule("Record",
                "not empty(var_codelist_coded_values(\"LIBRARY\")) and value() == \"SUPINE\"",
                "[\"variable_name\",\"library_variable_codelist_coded_values\"]");
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, vs(), _ -> null, "VS", library(),
                null, null);
        Map<String, String> values = firstValues(r);
        assertEquals("VSPOS", values.get("variable_name"));
        assertEquals("[STANDING, SUPINE]", values.get("library_variable_codelist_coded_values"));
    }


    /**
     * The named gap (plan §2, LOW): a rule that authors a library key in {@code Output_Variables}
     * WITHOUT reading LIBRARY in its Check projects the key through its accessor form at the row
     * (the M3 path, {@code operandCell} / {@code renderCollection}), which keeps element order
     * because it also serves {@code $} bindings. No shipped rule has that shape; pinned so the gap
     * is visible, not asserted as intent.
     */
    @Test
    @DisplayName("the M3 projection of an authored library key keeps element order (named gap)")
    void theM3PathKeepsElementOrder() throws Exception
    {
        Rule rule = rule("Record", "value() == \"SUPINE\"",
                "[\"variable_name\",\"library_variable_codelist_coded_values\"]");
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, vs(), _ -> null, "VS", library(),
                null, null);
        assertEquals("[SUPINE, STANDING]",
                firstValues(r).get("library_variable_codelist_coded_values"));
    }


    @Test
    @DisplayName("(b) the column-level path renders the library list sorted")
    void columnLevelPath() throws Exception
    {
        Rule rule = rule("Dataset", "not empty(var_codelist_coded_values(\"LIBRARY\"))",
                "[\"variable_name\",\"library_variable_codelist_coded_values\"]");
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, vs(), _ -> null, "VS", library(),
                null, null);
        Map<String, String> values = firstValues(r);
        assertEquals("VSPOS", values.get("variable_name"));
        assertEquals("[STANDING, SUPINE]", values.get("library_variable_codelist_coded_values"));
    }


    @Test
    @DisplayName("(c) the Define universe renders the library list sorted")
    void defineUniverse() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"expression\":\"not empty(var_codelist_coded_values(\\\"LIBRARY\\\"))\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"library_variable_codelist_coded_values\"]}}");
        StubMetadataProvider define = new StubMetadataProvider().variable("VS",
                Map.of("name", "VSPOS", "label", "Vital Signs Position"));
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, vs(), _ -> null, "VS", library(),
                null, define);
        Map<String, String> values = firstValues(r);
        assertEquals("VSPOS", values.get("variable_name"));
        assertEquals("[STANDING, SUPINE]", values.get("library_variable_codelist_coded_values"));
    }


    @Test
    @DisplayName("(d) _coded_codes renders sorted too")
    void codedCodes() throws Exception
    {
        Rule rule = rule("Dataset", "not empty(var_codelist_coded_codes(\"LIBRARY\"))",
                "[\"variable_name\",\"library_variable_codelist_coded_codes\"]");
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, vs(), _ -> null, "VS", library(),
                null, null);
        assertEquals("[C62166, C62167]",
                firstValues(r).get("library_variable_codelist_coded_codes"));
    }


    /**
     * (e) REQUIRED (P0: reachable — 27 {@code PER_VARIABLE} LIBRARY rules without
     * {@code Output_Variables}, e.g. CG0010): the default projection reports the column's library
     * keys, rendered the same way.
     */
    @Test
    @DisplayName("(e) the default projection renders the library lists sorted")
    void defaultProjection() throws Exception
    {
        Rule rule = rule("Dataset", "not empty(var_codelist_coded_values(\"LIBRARY\"))", null);
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, vs(), _ -> null, "VS", library(),
                null, null);
        Map<String, String> values = firstValues(r);
        assertEquals("VSPOS", values.get("variable_name"));
        // (the default projection reports the library keys the Check read — coded_values here)
        assertEquals("[STANDING, SUPINE]", values.get("library_variable_codelist_coded_values"),
                values.toString());
    }
}
