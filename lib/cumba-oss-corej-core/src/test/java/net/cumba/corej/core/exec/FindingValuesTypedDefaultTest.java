package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-output-variable-null-key-provenance} §2.1 / §6.2 (owner, 2026-10-04: <i>"nothing can
 * be null … the variable based default absence value (empty string on char or MissingValue MIS on
 * num)"</i>; <i>"In Output_Variables an absent column should be omitted"</i>) — one projection
 * policy on every finding path, authored and derived alike:
 * <ol>
 * <li>an entry naming an <b>absent column</b> is omitted;</li>
 * <li>a <b>present</b> column's missing cell reports that cell's own {@link MissingValue};</li>
 * <li>any other entry that resolves to no value carries its typed default — {@code ""} for a char
 * entry, {@code .} ({@link MissingValue#MIS}) for a numeric, boolean or list one (H3).</li>
 * </ol>
 * Plus M3: a projection gap (a value that exists but the variable map did not reach —
 * {@code variable_size}, {@code variable_max_size}, …) is <em>resolved</em>, never defaulted.
 *
 * <p>
 * One test per birth site of the plan's §6.1: S1 {@code buildVariableViolation} (authored and
 * derived, char / numeric / boolean / list), S2 {@code buildDefineVariableViolation}, S3
 * {@code addVariableRowViolations}, S4 {@code buildVariableMetadata} (through S1), S5
 * {@code extractOutputValues} (a numeric binding on an empty table, an unresolved dataset virtual,
 * a missing binding). Each asserted on the {@link Violation#getValues()} map, which the writers
 * print verbatim.
 * </p>
 */
class FindingValuesTypedDefaultTest
{

    private static final String MIS = MissingValue.MIS.toString();

    private static Rule load(String body) throws Exception
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}").getRules()
                .get("R1");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<String, String> single(RuleExecutionResult result)
    {
        assertNotNull(result);
        assertEquals(1, result.getViolationCount(), "status=" + result.getStatus() + " "
                + result.getStatusMessage() + " violations=" + result.getViolations());
        return result.getViolations().get(0).getValues();
    }


    private static void assertNullFree(Map<String, String> values)
    {
        values.forEach((k, v) -> assertNotNull(v, k + " carries a null value in " + values));
    }

    // ------------------------------------------------------------------ S1 / S4


    @Test
    void s1AuthoredAndDerivedCharAttributesWithNoValueBothReportTheEmptyString() throws Exception
    {
        // variable_format is read by the Check (derived); variable_label is authored only.
        String check = "{\"all\":[{\"expression\": \"ends_with(varname(), \\\"AETERM\\\")\"},"
                + "{\"expression\": \"empty(var_format(\\\"DATA\\\"))\"}]}";
        Rule authored = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\",\"Check\":"
                + check + ",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"variable_label\",\"variable_format\"]}}");
        Rule derived = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\",\"Check\":"
                + check + ",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"variable_label\"]}}");
        assertTrue(derived.getEffectiveOutputVariables().contains("variable_format"),
                "precondition: variable_format is derived: "
                        + derived.getEffectiveOutputVariables());
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        Map<String, String> a = single(RuleRunnerCalls.execute(authored, table));
        Map<String, String> d = single(RuleRunnerCalls.execute(derived, table));

        assertEquals("", a.get("variable_label"), a.toString());
        assertEquals("", a.get("variable_format"), "authored, no format: \"\" — not null");
        assertEquals("", d.get("variable_format"), "derived, no format: \"\" — not omitted");
        assertEquals(a, d, "authored and derived project identically (D1: no provenance split)");
        assertNullFree(a);
    }


    @Test
    void s1NumericBooleanAndListAttributesWithNoValueReportTheMissingMarker() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"all":[{"expression": "not empty(var_role(\\"LIBRARY\\"))"},
                                 {"expression": "not empty(var_role(\\"DEFINE\\"))"}]},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name",
                   "library_variable_length","library_variable_codelist_coded_values",
                   "define_variable_has_comment","define_variable_label","variable_length"]}}""");
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Identifier"));
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Qualifier"));
        IDataTable dm = MockTable.of().name("DM").col("SEX", "F").build();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, dm, _ -> null, "DM", library, null, define));

        assertEquals(MIS, v.get("library_variable_length"), "numeric: MIS — " + v);
        // A list accessor never answers a missing: no codelist IS the empty list, the value the
        // Check compared against — so it is reported, not defaulted (M3).
        assertEquals("[]", v.get("library_variable_codelist_coded_values"), "list: [] — " + v);
        assertEquals(MIS, v.get("define_variable_has_comment"), "boolean (D36): MIS — " + v);
        assertEquals("", v.get("define_variable_label"), "char: \"\" — " + v);
        assertEquals("0", v.get("variable_length"), "the mock's unspecified length stays 0 — " + v);
        assertNullFree(v);
    }


    @Test
    void s1AnAbsentColumnIsOmittedAndAPresentColumnKeepsTheDeferredKey() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "ends_with(varname(), \\"AETERM\\")"},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","DOMAIN","NOPE"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("DOMAIN", "AE").col("AETERM", "X").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertFalse(v.containsKey("NOPE"), "an absent column is omitted (§2.1 case 1): " + v);
        // The one deferred state: a PRESENT column on a finding without a row (no cell to read).
        // PLAN-finding-values-record-level-only (V8) carries it as a name-only entry; until then
        // the key stays with a null, listed in the corpus roster
        // nullkey/present-column-no-cell.tsv.
        assertTrue(v.containsKey("DOMAIN"), v.toString());
        assertNull(v.get("DOMAIN"));
    }


    @Test
    void m3TheLengthFactsAreProjectedNotDefaulted() throws Exception
    {
        // FDA-SD1082's shape: both facts are read by the Check and authored as Output_Variables,
        // yet the variable map carried neither — the finding printed two nulls (M3).
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "var_type(\\"DATA\\") == \\"Char\\" and var_length(\\"DATA\\") != max_value_length(variable_name)"},
                         "Outcome":{"Message":"m","Output_Variables":["variable_size","variable_max_size"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "ab", "abcd")
                .colMeta("AETERM", "Term", 10, null).build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals("10", v.get("variable_size"),
                "declared length, as var_length(\"DATA\"): " + v);
        assertEquals("4", v.get("variable_max_size"),
                "max stored length, as max_value_length: " + v);
        assertNullFree(v);
    }


    @Test
    void m3AnUnspecifiedDeclaredLengthReportsTheNumericDefault() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "var_type(\\"DATA\\") == \\"Char\\" and max_value_length(variable_name) > 2"},
                         "Outcome":{"Message":"m","Output_Variables":["variable_size","variable_max_size"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "ab", "abcd").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals(MIS, v.get("variable_size"), "no declared length: the numeric default — " + v);
        assertEquals("4", v.get("variable_max_size"), v.toString());
    }

    // ------------------------------------------------------------------ S2


    @Test
    void s2DefineDrivenProjectionReportsTypedDefaultsForBothProvenances() throws Exception
    {
        // library_variable_role is derived (the Check reads it) and the library knows no SEX;
        // library_variable_length is authored and numeric.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Variable_Universe":"Define","Sensitivity":"Dataset",
                         "Check":{"all":[{"expression": "var_role(\\"DEFINE\\") != var_role(\\"LIBRARY\\")"}]},
                         "Outcome":{"Message":"m","Output_Variables":["define_variable_name","library_variable_length"]}}""");
        assertTrue(rule.getEffectiveOutputVariables().contains("library_variable_role"),
                "precondition: library_variable_role is derived: "
                        + rule.getEffectiveOutputVariables());
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Qualifier"));
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                Map.of("name", "AGE", "role", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("SEX", "F").build();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, dm, _ -> null, "DM", library, null, define));

        assertEquals("SEX", v.get("define_variable_name"));
        assertEquals("", v.get("library_variable_role"), "derived char, no value: \"\" — " + v);
        assertEquals(MIS, v.get("library_variable_length"), "authored numeric, no value — " + v);
        assertNullFree(v);
    }

    // ------------------------------------------------------------------ S3


    @Test
    void s3PerVariableRowProjectionReportsAnAbsentLabelAsTheEmptyString() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"expression": "ends_with(varname(), \\"DESC\\")"},
                                         {"expression": "len(value()) > 3"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_value","variable_label"]}}""");
        IDataTable table = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("DESC", "abcdef", "ab").build();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, table, _ -> null, "ADSL", null, null, null));

        assertEquals("DESC", v.get("variable_name"));
        assertEquals("abcdef", v.get("variable_value"));
        assertEquals("", v.get("variable_label"), "no label: \"\" (it was null) — " + v);
        assertNullFree(v);
    }

    // ------------------------------------------------------------------ S5


    @Test
    void s5ARecordRuleReportsAMissingBindingByItsType() throws Exception
    {
        // $lbl reads a char attribute of a column the dataset lacks (a missing string);
        // $len a numeric one. Both print their typed default — never null, never an untyped "".
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$lbl","expression":"var_label(\\"ZZZ\\", \\"DATA\\")"},
                             {"name":"$len","expression":"var_length(\\"ZZZ\\", \\"DATA\\")"}],
                 "Check":{"expression": "AETERM != \\"zz\\""},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$lbl","$len"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals("X", v.get("AETERM"));
        assertEquals("", v.get("$lbl"), "a missing char binding: \"\" — " + v);
        assertEquals(MIS, v.get("$len"), "a missing numeric binding: MIS — " + v);
        assertNullFree(v);
    }


    @Test
    void s5AnUnresolvedDatasetVirtualCarriesItsDefaultOnTheRecordPath() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression": "AETERM != \\"zz\\""},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","dataset_label"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertTrue(v.containsKey("dataset_label"), "a resolvable virtual is never omitted: " + v);
        assertEquals("", v.get("dataset_label"), "no label: the char default — " + v);
        assertNullFree(v);
    }


    @Test
    void s5APerRowNumericBindingOnAnEmptyTableReportsTheMissingMarker() throws Exception
    {
        // M4: the empty-table arm printed an untyped "" for every binding; a numeric one now
        // prints MIS, a char one still "". The PRESENT column's "" on an empty table is N20's,
        // handed to PLAN-finding-values-record-level-only (it does not move here).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$n","expression":"AENUM + 1"},
                             {"name":"$s","expression":"upper(AETERM)"}],
                 "Check":{"expression": "record_count() == 0"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$n","$s"]}}""");
        IDataTable empty = MockTable.of().name("AE").col("AETERM").colDouble("AENUM").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, empty));

        assertEquals("", v.get("AETERM"), "N20: a present column on an empty table — " + v);
        assertEquals(MIS, v.get("$n"), "a per-row numeric binding with no row: MIS — " + v);
        assertEquals("", v.get("$s"), "a per-row char binding with no row: \"\" — " + v);
        assertNullFree(v);
    }


    @Test
    void aBindingWithoutAnyTypeSourceKeepsTheRecordPathsEmptyStringUntilRuled() throws Exception
    {
        // ⚑ OPEN question for the owner (reported 2026-10-04, not decided here): a binding whose
        // type neither the evaluation nor stage A knows (dataset_class_from_library has no
        // ElementTable result type) and that answers nothing. The record path has always printed
        // "" for it; that spelling is kept — never null, never a MissingValue invented for a
        // value that may be a string.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$c","expression":"dataset_class_from_library()"}],
                 "Check":{"expression": "empty($c)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$c"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();
        MetadataProvider library = new StubMetadataProvider();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, ae, _ -> null, "AE", library, null, null));

        assertEquals("", v.get("$c"), "no type source: the record path's \"\" is kept — " + v);
        assertNullFree(v);
    }


    @Test
    void theClassifierIsTheOneTypeSource()
    {
        assertEquals(OutputEntryKind.NAME, OutputEntryKind.of("variable_name"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("variable_value"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("AETERM"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("DM.ARM"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("--CAT"));
        assertEquals(OutputEntryKind.BINDING, OutputEntryKind.of("$x"));
        assertEquals(OutputEntryKind.CHAR, OutputEntryKind.of("variable_label"));
        assertEquals(OutputEntryKind.CHAR, OutputEntryKind.of("dataset_label"));
        assertEquals(OutputEntryKind.CHAR, OutputEntryKind.of("library_variable_label_template"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("variable_length"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("variable_max_size"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("record_count"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("define_vlm_length"));
        assertEquals(OutputEntryKind.BOOLEAN, OutputEntryKind.of("define_variable_has_comment"));
        assertEquals(OutputEntryKind.BOOLEAN,
                OutputEntryKind.of("library_variable_codelist_extensible"));
        assertEquals(OutputEntryKind.LIST,
                OutputEntryKind.of("library_variable_codelist_coded_values"));
        assertEquals(OutputEntryKind.LIST, OutputEntryKind.of("library_variable_label_values"));
        assertEquals("", OutputEntryKind.CHAR.defaultValue());
        for (OutputEntryKind k : List.of(OutputEntryKind.NUM, OutputEntryKind.BOOLEAN,
                OutputEntryKind.LIST))
        {
            assertEquals(MIS, k.defaultValue(), k.toString());
        }
    }
}
