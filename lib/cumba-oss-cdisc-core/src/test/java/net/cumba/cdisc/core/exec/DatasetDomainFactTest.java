package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.eval.MetadataAttribute;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code dataset_domain} — {@code plans/PLAN-domain-expression-function.md}.
 *
 * <p>
 * The fact is registered on three surfaces that must all resolve to the SAME value, the
 * {@code Scope.Domains} base leg {@link OperationExecutor#unsplitNameFromData}:
 * </p>
 * <ul>
 * <li>{@code BuiltinRegistry} bareword — authored as a leaf {@code name: dataset_domain};</li>
 * <li>{@code OperationType} — authored as {@code Operations: [{id: $x, operator:
 * dataset_domain}]};</li>
 * <li>{@code MetadataAttribute.DS_DOMAIN} — never authored, the lowering target
 * {@code ds_domain("DATA")} that {@code MetadataOperandMapping}'s {@code dataset_} prefix produces
 * from the bareword.</li>
 * </ul>
 *
 * <p>
 * ⚠⚠ <b>The load-bearing assertion in this class is the FOLD, not the value.</b> The whole point of
 * the fact is that a Check written against it decides <em>once per dataset</em>
 * ({@code BroadcastFold.isDatasetFactCall}); a {@code dataset_domain} that returns the right value
 * but is not dataset-constant passes every value assertion and silently keeps per-record reporting.
 * {@link #barewordFoldsToOneFindingPerDataset()} and
 * {@link #operationFormFoldsToOneFindingPerDataset()} are the ones that would go red.
 * </p>
 */
class DatasetDomainFactTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /**
     * The {@code CDISC-CG0308} shape, carried by the BAREWORD: a Record-Data rule whose whole Check
     * is a length test of the fact.
     */
    private static final String BAREWORD_LENGTH_RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\"," + "\"Check\":{\"any\":["
            + "{\"name\":\"dataset_domain\",\"operator\":\"longer_than\",\"value\":2},"
            + "{\"name\":\"dataset_domain\",\"operator\":\"shorter_than\",\"value\":2}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"dataset_domain\"]}}";

    /** The same shape carried by the OPERATION, referenced through its {@code $}-id. */
    private static final String OPERATION_LENGTH_RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\","
            + "\"Operations\":[{\"id\":\"$dd\",\"operator\":\"dataset_domain\"}],"
            + "\"Check\":{\"any\":["
            + "{\"name\":\"$dd\",\"operator\":\"longer_than\",\"value\":2},"
            + "{\"name\":\"$dd\",\"operator\":\"shorter_than\",\"value\":2}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$dd\"]}}";

    private static Rule loadRule(String ruleBody) throws Exception
    {
        String json = "{\"rules\":{\"R1\":" + ruleBody + "}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<Long, Map<String, String>> findings(Rule rule, IDataTable primary,
            String domainPrefix)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, primary, NO_RESOLVER, domainPrefix, null,
                null, null);
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Violation v : r.getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }


    /**
     * Runs the equality rule and reports whether the fact equalled {@code expected} — the probe
     * used for the value table, so each row is measured through the real engine rather than by
     * calling the derivation directly.
     */
    private static boolean factEquals(IDataTable table, String domainPrefix, String expected)
        throws Exception
    {
        // An equality test of the fact against a literal — the probe for the four §3 value rows.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"dataset_domain\","
                + "\"operator\":\"equal_to\",\"value\":\"" + expected
                + "\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr(), "the equality rule must compile natively");
        return !findings(rule, table, domainPrefix).isEmpty();
    }

    // ------------------------------------------------------------------
    // The three registrations
    // ------------------------------------------------------------------


    @Test
    void theFactIsRegisteredOnAllThreeSurfaces()
    {
        assertTrue(net.cumba.cdisc.core.expr.BuiltinRegistry.isBuiltin("dataset_domain"),
                "bareword surface");
        assertEquals(OperationType.DATASET_DOMAIN, OperationType.fromJson("dataset_domain"),
                "Operations surface");
        MetadataAttribute attr = MetadataAttribute.fromFunction("ds_domain");
        assertNotNull(attr, "accessor surface");
        assertEquals(MetadataAttribute.Scope.DATASET, attr.scope());
        assertTrue(attr.supports(net.cumba.cdisc.core.expr.eval.MetadataLevel.DATA));
    }


    /**
     * DATA is the ONLY level. Neither the sponsor Define nor the CDISC Library publishes a domain
     * field, so an unsupported level must raise the honest {@code RuleDefinitionException} rather
     * than resolve to a silent missing. This pins that choice: relaxing it would make
     * {@code define_dataset_domain} load and always answer "nothing".
     */
    @Test
    void theAccessorIsDataLevelOnly()
    {
        MetadataAttribute attr = MetadataAttribute.fromFunction("ds_domain");
        assertNotNull(attr);
        assertFalse(attr.supports(net.cumba.cdisc.core.expr.eval.MetadataLevel.DEFINE));
        assertFalse(attr.supports(net.cumba.cdisc.core.expr.eval.MetadataLevel.LIBRARY));
        assertNull(
                net.cumba.cdisc.core.expr.MetadataOperandMapping
                        .forwardOperand("define_dataset_domain"),
                "define_dataset_domain has no accessor and must not be lowered");
    }


    /**
     * ⚠ The bareword must be LOWERED to the accessor. {@code MetadataOperandMapping}'s prefix table
     * would map any {@code dataset_<suffix>}, but on a non-metadata rule type the lowering is gated
     * by {@code isFoldFactName} — and a name missing from that gate compiles as a read of a column
     * literally called {@code dataset_domain}, absent on every dataset. That failure is silent, so
     * it is pinned on the printed shape as well as behaviourally.
     */
    @Test
    void theBarewordLowersToTheAccessorOnARecordDataRule() throws Exception
    {
        Rule rule = loadRule(BAREWORD_LENGTH_RULE);
        String printed = ExpressionPrinter.print(rule.getCheckExpr());
        assertTrue(printed.contains("ds_domain(\"DATA\")"),
                "the bareword must lower to the DATA-level accessor, got: " + printed);
    }


    /**
     * Since phase 4 of {@code PLAN-leaf-scope-domain-inference.md} the canonicalisation is uniform
     * (the R-P7 value-position exemption died with the type split): a {@code dataset_domain} on the
     * VALUE side of a comparison reads the fact on a Record Data rule too.
     */
    @Test
    void theFactInValuePositionIsCanonicalisedUniformly() throws Exception
    {
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"AETERM\",\"operator\":\"equal_to\","
                + "\"value\":\"dataset_domain\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        String printed = ExpressionPrinter.print(rule.getCheckExpr());
        assertTrue(printed.contains("ds_domain(\"DATA\")"),
                "value-position dataset_domain reads the fact: " + printed);
    }

    // ------------------------------------------------------------------
    // ⚠⚠ The fold — the entire point of the feature
    // ------------------------------------------------------------------


    /**
     * ⚠⚠ THE test. Three malformed rows, ONE finding. The pre-feature {@code CDISC-CG0308} shape (a
     * {@code DOMAIN} column leaf) gives three.
     *
     * <p>
     * <b>Neuter-and-watch, 2026-08-07:</b> excluding {@code DS_DOMAIN} from
     * {@code BroadcastFold.isDatasetFactCall} — the only constancy authority; there is no flag —
     * turns this into 3 findings and the assertion goes red. Recorded here because the neutering
     * cannot live in the test itself.
     * </p>
     */
    @Test
    void barewordFoldsToOneFindingPerDataset() throws Exception
    {
        Rule rule = loadRule(BAREWORD_LENGTH_RULE);
        assertTrue(rule.isBroadcastCheckExpr(),
                "a pure dataset-fact Check must be broadcast-flagged");
        IDataTable malformed = MockTable.of().name("AEX").col("DOMAIN", "AEX", "AEX", "AEX")
                .col("AETERM", "a", "b", "c").build();
        assertEquals(1, findings(rule, malformed, "AE").size(),
                "3 malformed rows must yield exactly ONE dataset-level finding");
    }


    /** The Operations carriage folds too — {@code $dd} is a broadcast scalar, not a per-row set. */
    @Test
    void operationFormFoldsToOneFindingPerDataset() throws Exception
    {
        Rule rule = loadRule(OPERATION_LENGTH_RULE);
        IDataTable malformed = MockTable.of().name("AEX").col("DOMAIN", "AEX", "AEX", "AEX")
                .col("AETERM", "a", "b", "c").build();
        assertEquals(1, findings(rule, malformed, "AE").size(),
                "the Operation carriage must fold identically to the bareword");
    }


    /** A conformant dataset fires on neither carriage. */
    @Test
    void neitherCarriageFiresOnAConformantDataset() throws Exception
    {
        IDataTable ok = MockTable.of().name("AE").col("DOMAIN", "AE", "AE", "AE")
                .col("AETERM", "a", "b", "c").build();
        assertEquals(Map.of(), findings(loadRule(BAREWORD_LENGTH_RULE), ok, "AE"));
        assertEquals(Map.of(), findings(loadRule(OPERATION_LENGTH_RULE), ok, "AE"));
    }

    // ------------------------------------------------------------------
    // The value — §3's four rows, plus the malformation row
    // ------------------------------------------------------------------


    /** {@code AE} with {@code DOMAIN=AE} ⇒ {@code AE}. */
    @Test
    void plainDatasetYieldsItsDomain() throws Exception
    {
        IDataTable ae = MockTable.of().name("AE").col("DOMAIN", "AE", "AE").build();
        assertTrue(factEquals(ae, "AE", "AE"));
    }


    /** A SPLIT member {@code LBXY} with {@code DOMAIN=LB} ⇒ {@code LB}, not {@code LBXY}. */
    @Test
    void splitMemberYieldsTheParentDomainFromTheDomainCell() throws Exception
    {
        IDataTable lbxy = MockTable.of().name("LBXY").col("DOMAIN", "LB", "LB").build();
        assertTrue(factEquals(lbxy, "LB", "LB"), "the DOMAIN cell wins over the dataset name");
        assertFalse(factEquals(lbxy, "LB", "LBXY"));
    }


    /**
     * ⚠ A split SUPP member carries {@code RDOMAIN}, not {@code DOMAIN}: {@code SUPPLBHM} ⇒
     * {@code SUPPLB} — the Scope base leg, NOT the parent domain {@code LB} and NOT
     * {@code domainPrefix}'s bogus {@code SUPPLBH}. This is the value that makes the
     * {@code Scope.Variables.Include: ["DOMAIN"]} guard necessary on a length-checking consumer.
     */
    @Test
    void splitSuppMemberYieldsSuppPlusRdomain() throws Exception
    {
        IDataTable supp = MockTable.of().name("SUPPLBHM").col("RDOMAIN", "LB", "LB")
                .col("QNAM", "X", "Y").build();
        assertTrue(factEquals(supp, "SUPPLBHM", "SUPPLB"));
        assertFalse(factEquals(supp, "SUPPLBHM", "LB"), "not the parent domain");
        assertFalse(factEquals(supp, "SUPPLBHM", "SUPPLBH"), "not domainPrefix's value");
    }


    /** A 0-row table has no cell to read, so the fact is the raw dataset name. */
    @Test
    void zeroRowDatasetYieldsTheRawName() throws Exception
    {
        IDataTable empty = MockTable.of().name("AE").col("DOMAIN").build();
        assertTrue(factEquals(empty, "AE", "AE"));
    }


    /**
     * ⚑ The value is the row-0 cell VERBATIM, so a malformed domain stays visible and a length
     * check still fires. A normalising implementation would hide exactly what the consumer exists
     * to detect.
     */
    @Test
    void aMalformedDomainCellIsReturnedVerbatim() throws Exception
    {
        IDataTable bad = MockTable.of().name("AE").col("DOMAIN", "XXX", "XXX").build();
        assertTrue(factEquals(bad, "AE", "XXX"), "the cell is returned unchanged");
    }


    /**
     * The Operations carriage reads the same derivation — asserted against the SUPP row, the one
     * place the three candidate derivations disagree.
     */
    @Test
    void theOperationCarriageReadsTheSameDerivation()
    {
        IDataTable supp = MockTable.of().name("SUPPLBHM").col("RDOMAIN", "LB").build();
        net.cumba.cdisc.core.model.Operation op = new net.cumba.cdisc.core.model.Operation();
        op.setId("$dd");
        op.setOperator("dataset_domain");
        assertEquals("SUPPLB",
                OperationExecutor.executeOne(op, supp, NO_RESOLVER, null, new HashMap<>()));
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------


    /**
     * The fact projects into {@code Output_Variables} — the property that made a bareword carriage
     * acceptable at all (a function result cannot be projected).
     */
    @Test
    void theFactProjectsIntoOutputVariables() throws Exception
    {
        Rule rule = loadRule(BAREWORD_LENGTH_RULE);
        IDataTable malformed = MockTable.of().name("AEX").col("DOMAIN", "AEX", "AEX").build();
        Map<Long, Map<String, String>> fired = findings(rule, malformed, "AE");
        assertEquals(1, fired.size());
        assertEquals("AEX", fired.values().iterator().next().get("dataset_domain"));
    }

}
