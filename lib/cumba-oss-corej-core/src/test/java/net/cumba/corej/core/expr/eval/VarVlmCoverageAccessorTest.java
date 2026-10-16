package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.OutputVariableDeriver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.metadata.DefineMetadataListCodec;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-stopped-rule-engine-fixes} SR1 / T1: the three variable-level accessors
 * {@code var_vlm_has_method("DEFINE")}, {@code var_vlm_has_comment("DEFINE")} and
 * {@code var_vlm_has_origin("DEFINE")} — {@code true} iff EVERY value-level entry of the variable
 * carries the item, {@code false} without a (non-empty, resolvable) value list, missing for a
 * variable the define does not declare — on {@code define-vlm-coverage.xml} (one LB variable per
 * shape, listed in the fixture's header).
 *
 * <p>
 * Why they exist: the six SEND rules ({@code CDISC-SEND-0112.1}, {@code -0129.1}, {@code -0137},
 * {@code -0014}, {@code -0340}, {@code -0008}) read the define's variable level only, so a variable
 * documented per value (Define-XML 2.1 §5.3.12.3) was reported. The per-record
 * {@code vlm_has_method(X)} cannot be their conjunct: it turns a variable-level Check into one
 * finding per record and fails to load under {@code Variable_Universe: Define}. These three answer
 * once per variable, so the Check stays variable-level under both universes.
 * </p>
 *
 * <p>
 * {@code PLAN-vlm-origin-value-accessor} VO1: the list accessor
 * {@code var_vlm_origin_types("DEFINE")} — the distinct origin types of the value-level entries in
 * value-list order ({@code ""} for an entry without an origin or whose {@code ItemDef} does not
 * resolve), {@code []} without a value list and for an undeclared variable — and
 * {@code CDISC-SEND-0111}'s exact {@code Bindings:} + {@code Check} over it.
 * </p>
 */
class VarVlmCoverageAccessorTest
{

    private static MetadataProvider define;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = VarVlmCoverageAccessorTest.class
                .getResourceAsStream("/define/define-vlm-coverage.xml"))
        {
            assertNotNull(in, "fixture");
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm), null);
    }


    /** Four rows, so a per-record reading would answer four times. */
    private static IDataTable lb()
    {
        return MockTable.of().name("LB").col("LBTESTCD", "GLUC", "HGB", "SODIUM", "XXX")
                .col("LBSTRESC", "5.1", "14", "140", "1").col("LBORRES", "a", "b", "c", "d")
                .col("LBSTAT", "", "", "NOT DONE", "").build();
    }


    private static EvaluationContext ctx(String cursor)
    {
        return EvaluationContext.builder().table(lb()).datasetResolver(_ -> null).domainName("LB")
                .defineProvider(define).variables(Map.of("variable_name", cursor)).build();
    }


    private static boolean holds(String source, String cursor)
    {
        BitSet rows = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(source),
                ctx(cursor));
        // a variable-level fact: the same answer on every row
        assertTrue(rows.isEmpty() || rows.cardinality() == 4,
                source + " on " + cursor + " must not vary by row: " + rows);
        return rows.get(0);
    }


    private static void assertCoverage(String cursor, boolean method, boolean comment,
            boolean origin)
    {
        assertEquals(method, holds("var_vlm_has_method(\"DEFINE\") == true", cursor),
                cursor + " method");
        assertEquals(!method, holds("var_vlm_has_method(\"DEFINE\") == false", cursor),
                cursor + " method is a real false, not missing");
        assertEquals(comment, holds("var_vlm_has_comment(\"DEFINE\") == true", cursor),
                cursor + " comment");
        assertEquals(!comment, holds("var_vlm_has_comment(\"DEFINE\") == false", cursor),
                cursor + " comment is a real false, not missing");
        assertEquals(origin, holds("var_vlm_has_origin(\"DEFINE\") == true", cursor),
                cursor + " origin");
        assertEquals(!origin, holds("var_vlm_has_origin(\"DEFINE\") == false", cursor),
                cursor + " origin is a real false, not missing");
    }


    @Test
    void everyEntryDocumentedIsTrueAndTheThreeFlagsAreIndependent()
    {
        assertCoverage("LBSTRESC", true, true, true);
        // LBSTAT: both entries carry a method and nothing else
        assertCoverage("LBSTAT", true, false, false);
    }


    @Test
    void oneUndocumentedEntryMakesTheVariableFalse()
    {
        // LBORRES: GLUC documented three ways, HGB by nothing — "every entry" fails
        assertCoverage("LBORRES", false, false, false);
    }


    @Test
    void aVariableWithoutAUsableValueListIsFalseNotMissing()
    {
        assertCoverage("LBTESTCD", false, false, false); // no def:ValueListRef
        assertCoverage("LBSPEC", false, false, false); // a ValueListOID the define lacks
        assertCoverage("LBMETHOD", false, false, false); // a value list without an ItemRef
        // LBNAM: the one entry names an ItemDef that does not resolve although its ItemRef carries
        // a MethodOID — undocumented for all three (T1), the conservative direction
        assertCoverage("LBNAM", false, false, false);
    }


    @Test
    void aVariableTheDefineDoesNotDeclareIsMissing()
    {
        assertTrue(holds("empty(var_vlm_has_method(\"NOPE\", \"DEFINE\"))", "LBTESTCD"));
        assertTrue(holds("empty(var_vlm_has_comment(\"NOPE\", \"DEFINE\"))", "LBTESTCD"));
        assertTrue(holds("empty(var_vlm_has_origin(\"NOPE\", \"DEFINE\"))", "LBTESTCD"));
        // the rules' conjunct `!= true` fires on missing as on false: a define without the
        // variable keeps the variable-level reading in charge
        assertTrue(holds("var_vlm_has_method(\"NOPE\", \"DEFINE\") != true", "LBTESTCD"));
        // a named variable reads the same facts as the cursor form
        assertTrue(holds("var_vlm_has_origin(\"LBSTRESC\", \"DEFINE\") == true", "LBTESTCD"));
        assertEquals("true", define.getVariableMetadata("LB", "LBSTRESC").get("vlm_has_method"));
        assertEquals("false", define.getVariableMetadata("LB", "LBORRES").get("vlm_has_comment"));
        assertNull(define.getVariableMetadata("LB", "NOPE").get("vlm_has_origin"));
    }

    // ------------------------------------------------------------------ through a rule


    private static Rule load(String body) throws Exception
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}").getRules()
                .get("R1");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /**
     * {@code CDISC-SEND-0137}'s shape over the data universe: a variable-level Check with the new
     * conjunct yields ONE finding per undocumented variable on a four-row table — never one per
     * record — and the derived Output_Variables carry {@code define_variable_vlm_has_comment} with
     * the provider's value (the {@code var_has_value_list} precedent: the derived name is
     * {@code define_variable_} + the function's suffix, and the provider key equals that suffix).
     */
    @Test
    void aDataUniverseCheckFiresOncePerVariableAndDerivesTheCoverageEntry() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "not empty(var_name(\\"DEFINE\\")) and var_has_comment(\\"DEFINE\\") == false and var_vlm_has_comment(\\"DEFINE\\") != true"},
                         "Outcome":{"Message":"m"}}""");
        assertTrue(
                rule.effectiveOutputVariablesOrAuthored()
                        .contains("define_variable_vlm_has_comment"),
                rule.effectiveOutputVariablesOrAuthored().toString());
        assertTrue(OutputVariableDeriver.derive(rule).contains("define_variable_has_comment"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, lb(), _ -> null, "LB", null,
                null, define);

        assertEquals("EXECUTED", result.getStatus().name(), result.getStatusMessage());
        // LBSTRESC is exempt (every entry has a comment); the other three data columns fire once
        assertEquals(3, result.getViolationCount(), result.getViolations().toString());
        TreeSet<String> names = new TreeSet<>();
        for (Violation v : result.getViolations())
        {
            names.add(v.getValues().get("variable_name"));
            assertEquals("false", v.getValues().get("define_variable_vlm_has_comment"),
                    "the derived entry reads the provider's value: " + v.getValues());
            assertEquals("false", v.getValues().get("define_variable_has_comment"),
                    v.getValues().toString());
        }
        assertEquals(new TreeSet<>(List.of("LBORRES", "LBSTAT", "LBTESTCD")), names);
    }


    /**
     * {@code CDISC-SEND-0014} / {@code -0340}'s shape: under {@code Variable_Universe: Define} the
     * rule iterates the define's ItemDefs — including LBSPEC, LBMETHOD and LBNAM, which the data
     * does not carry — and the conjunct loads and answers once per variable (the round's per-record
     * {@code vlm_has_comment(varname())} conjunct was a load error there: the define universe has
     * no rows to iterate).
     */
    @Test
    void aDefineUniverseCheckLoadsAndFiresOncePerItemDef() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record","Variable_Universe":"Define",
                         "Check":{"expression": "not empty(var_name(\\"DEFINE\\")) and var_has_comment(\\"DEFINE\\") == false and var_vlm_has_comment(\\"DEFINE\\") != true"},
                         "Outcome":{"Message":"m","Output_Variables":["define_variable_name"]}}""");
        assertEquals(Domain.VARIABLE, rule.getEvaluationDomain());

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, lb(), _ -> null, "LB", null,
                null, define);

        assertEquals("EXECUTED", result.getStatus().name(), result.getStatusMessage());
        TreeSet<String> names = new TreeSet<>();
        for (Violation v : result.getViolations())
        {
            names.add(v.getValues().get("define_variable_name"));
        }
        // seven ItemDefs, LBSTRESC exempt — one finding each, absent from the data or not
        assertEquals(
                new TreeSet<>(
                        List.of("LBMETHOD", "LBNAM", "LBORRES", "LBSPEC", "LBSTAT", "LBTESTCD")),
                names);
        assertEquals(6, result.getViolationCount(), "one per ItemDef, never per row");
    }

    // ------------------------------------------------------------------ var_vlm_origin_types (VO1)


    private static List<String> originTypes(String variable)
    {
        String raw = define.getVariableMetadata("LB", variable).get("vlm_origin_types");
        assertNotNull(raw, variable + ": vlm_origin_types is served under the function's suffix");
        return DefineMetadataListCodec.decode(raw);
    }


    @Test
    void theOriginTypesAreTheDistinctEntryTypesInValueListOrder()
    {
        assertEquals(List.of("Collected"), originTypes("LBSTNRC"),
                "two Collected entries, distinct");
        assertEquals(List.of("Collected", "CRF"), originTypes("LBORNRLO"),
                "the 2.1 element and the v2.0 attribute both count");
        assertEquals(List.of("Derived", "Collected"), originTypes("LBSTRESC"), "mixed, in order");
        assertEquals(List.of("Collected"), originTypes("LBSTNRU"),
                "the variable level does not leak into the value-level list");
    }


    @Test
    void anEntryWithoutAnOriginOrAResolvableItemDefContributesTheEmptyString()
    {
        assertEquals(List.of("Collected", ""), originTypes("LBORRES"));
        assertEquals(List.of(""), originTypes("LBSTAT"), "two entries without an origin, distinct");
        assertEquals(List.of(""), originTypes("LBNAM"), "the entry's ItemDef does not resolve");
    }


    @Test
    void withoutAUsableValueListAndForAnUndeclaredVariableTheListIsEmpty()
    {
        assertEquals(List.of(), originTypes("LBTESTCD"), "no def:ValueListRef");
        assertEquals(List.of(), originTypes("LBSPEC"), "a ValueListOID the define lacks");
        assertEquals(List.of(), originTypes("LBMETHOD"), "a value list without an ItemRef");
        // H2: the list channel cannot carry "missing" — an undeclared variable is not served at
        // all, and a list accessor decodes the null to []
        assertNull(define.getVariableMetadata("LB", "NOPE").get("vlm_origin_types"));
        assertEquals(List.of(), DefineMetadataListCodec.decode(null));
        assertTrue(MetadataAttribute.VAR_VLM_ORIGIN_TYPES.isList());
    }


    /** Seven data columns with values, so {@code max_value_length(variable_name) > 0} holds. */
    private static IDataTable lbRanges()
    {
        return MockTable.of().name("LB").col("LBTESTCD", "GLUC", "HGB").col("LBSTRESC", "5.1", "14")
                .col("LBORRES", "a", "b").col("LBSTNRC", "N", "N").col("LBORNRLO", "1", "2")
                .col("LBSTNRU", "u", "u").col("LBSTAT", "x", "y").build();
    }

    /** {@code CDISC-SEND-0111}'s Bindings, verbatim (PLAN-vlm-origin-value-accessor §4). */
    private static final String SEND0111_BINDINGS = "\"Bindings\":[{\"name\":\"$origin_ok\","
            + "\"expression\":\"[\\\"Collected\\\", \\\"CRF\\\"]\"}]";

    /** {@code CDISC-SEND-0111}'s Check, verbatim but for the variable name (§4). */
    private static final String SEND0111_CHECK = "equalsIgnoreCase(varname(), \"%s\") and"
            + " not empty(var_name(\"DEFINE\")) and max_value_length(variable_name) > 0 and"
            + " var_origin_type(\"DEFINE\") not in [\"Collected\", \"CRF\"] and"
            + " not (empty(var_origin_type(\"DEFINE\")) and var_vlm_has_origin(\"DEFINE\") == true"
            + " and contains_all($origin_ok, var_vlm_origin_types(\"DEFINE\")))";

    /** Today's (pre-VO1) Check: the variable level only. */
    private static final String SEND0111_OLD_CHECK = "equalsIgnoreCase(varname(), \"%s\") and"
            + " not empty(var_name(\"DEFINE\")) and max_value_length(variable_name) > 0 and"
            + " var_origin_type(\"DEFINE\") not in [\"Collected\", \"CRF\"]";

    /** §7's sensitivity control: the operands swapped (the list-accessor branch). */
    private static final String SEND0111_SWAPPED_CHECK = "equalsIgnoreCase(varname(), \"%s\") and"
            + " not empty(var_name(\"DEFINE\")) and max_value_length(variable_name) > 0 and"
            + " var_origin_type(\"DEFINE\") not in [\"Collected\", \"CRF\"] and"
            + " not (empty(var_origin_type(\"DEFINE\")) and var_vlm_has_origin(\"DEFINE\") == true"
            + " and contains_all(var_vlm_origin_types(\"DEFINE\"), $origin_ok))";

    private static RuleExecutionResult runSend0111(String check, String variable) throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\"," + SEND0111_BINDINGS
                + ",\"Check\":{\"expression\": \""
                + String.format(check, variable).replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\"}}");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, lbRanges(), _ -> null, "LB",
                null, null, define);
        assertEquals("EXECUTED", result.getStatus().name(), result.getStatusMessage());
        return result;
    }


    /**
     * H1: the exact {@code Bindings:} + {@code Check} of the edited {@code CDISC-SEND-0111} loads
     * (a {@code $}-bound list literal as {@code contains_all}'s first operand) and exempts a
     * variable whose origin lives at the value level only, every entry {@code Collected} /
     * {@code CRF}; today's Check reports it.
     */
    @Test
    void send0111ExemptsAValueLevelOnlyCollectedOrigin() throws Exception
    {
        assertEquals(1, runSend0111(SEND0111_OLD_CHECK, "LBSTNRC").getViolationCount(),
                "today's rule reports the value-level-only Collected origin (the bug)");
        assertEquals(0, runSend0111(SEND0111_CHECK, "LBSTNRC").getViolationCount(),
                "every value-level entry Collected");
        assertEquals(0, runSend0111(SEND0111_CHECK, "LBORNRLO").getViolationCount(),
                "Collected + the v2.0 CRF");
        // sensitivity: the swapped spelling asks "the entry types include both" and reports it
        assertEquals(1, runSend0111(SEND0111_SWAPPED_CHECK, "LBSTNRC").getViolationCount(),
                "swapped operands must not exempt [Collected]");
    }


    @Test
    void send0111StillFiresUnlessEveryValueLevelOriginIsCollectedAndTheVariableLevelIsEmpty()
        throws Exception
    {
        assertEquals(1, runSend0111(SEND0111_CHECK, "LBSTRESC").getViolationCount(),
                "one entry Derived");
        assertEquals(1, runSend0111(SEND0111_CHECK, "LBORRES").getViolationCount(),
                "one entry without an origin");
        assertEquals(1, runSend0111(SEND0111_CHECK, "LBTESTCD").getViolationCount(),
                "no value list");
        // T1: a variable-level origin, when present, still decides
        assertEquals(1, runSend0111(SEND0111_CHECK, "LBSTNRU").getViolationCount(),
                "variable-level Derived, every value-level entry Collected");
    }


    /**
     * The derived Output_Variable {@code define_variable_vlm_origin_types} meets the provider's
     * copy (T2: the key equals the function's suffix) on the data universe — the value is the
     * provider's list, whatever its rendering on that path.
     */
    @Test
    void theDerivedOriginTypesEntryMeetsTheProvidersCopy() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "equalsIgnoreCase(varname(), \\"LBORNRLO\\") and var_vlm_has_origin(\\"DEFINE\\") == true and not empty(var_vlm_origin_types(\\"DEFINE\\"))"},
                         "Outcome":{"Message":"m"}}""");
        assertTrue(
                rule.effectiveOutputVariablesOrAuthored()
                        .contains("define_variable_vlm_origin_types"),
                rule.effectiveOutputVariablesOrAuthored().toString());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, lbRanges(), _ -> null, "LB",
                null, null, define);
        assertEquals("EXECUTED", result.getStatus().name(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), result.getViolations().toString());
        assertEquals(List.of("Collected", "CRF"), DefineMetadataListCodec.decode(
                result.getViolations().get(0).getValues().get("define_variable_vlm_origin_types")));
    }


    /**
     * C14: on the define universe ({@code RuleRunner.formatDefineOutput}) the list reports in the
     * canonical sorted {@code [a, b]} form, not its JSON encoding — the list keys come from
     * {@link MetadataAttribute#isList()}, so a new list accessor cannot be missed.
     */
    @Test
    void onTheDefineUniverseTheOriginTypesReportAsASortedList() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record","Variable_Universe":"Define",
                         "Check":{"expression": "not empty(var_name(\\"DEFINE\\")) and var_vlm_has_origin(\\"DEFINE\\") == true"},
                         "Outcome":{"Message":"m","Output_Variables":["define_variable_name","define_variable_vlm_origin_types"]}}""");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, lb(), _ -> null, "LB", null,
                null, define);
        assertEquals("EXECUTED", result.getStatus().name(), result.getStatusMessage());
        Map<String, String> byName = new TreeMap<>();
        for (Violation v : result.getViolations())
        {
            byName.put(v.getValues().get("define_variable_name"),
                    v.getValues().get("define_variable_vlm_origin_types"));
        }
        assertEquals(Map.of("LBSTRESC", "[Collected, Derived]", "LBSTNRC", "[Collected]",
                "LBORNRLO", "[CRF, Collected]", "LBSTNRU", "[Collected]"), byName);
    }
}
