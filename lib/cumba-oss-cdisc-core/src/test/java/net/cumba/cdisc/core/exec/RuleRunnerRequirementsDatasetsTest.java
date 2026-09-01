package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Requirements;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Requirements.Datasets} at runtime ({@code plans/PLAN-scope-requirements-split.md}
 * &#167;4.4): a declared dataset the run does not ship makes the rule unanswerable, so it SKIPs
 * whole with a reason naming the requirement.
 *
 * <p>
 * The two properties that are easy to get wrong, and that this class exists for:
 * </p>
 * <ol>
 * <li>⭐ <b>the predicate is per ENTRY, not per axis.</b> An entry the rule reaches only through a
 * surface that still resolves by exact name uses the exact-name test; everything else uses the
 * widened {@code SplitDomainResolution.isPresentAsDomain} that {@code ds_exists} has evaluated
 * since {@code Fix #358}. Getting this wrong in either direction is a finding-mover: widening an
 * {@code Operations[].domain}-only entry un-skips the rule into the {@code W34-C1} flood, and
 * narrowing a {@code ds_exists} migration re-opens the divergence D7 closed;</li>
 * <li>⚠ the requirement is judged <b>before</b> {@code AbsentDatasetSkip.decide}, so a declaring
 * rule never enters the dependency-scoped suppression path.</li>
 * </ol>
 */
class RuleRunnerRequirementsDatasetsTest
{

    private static Rule ruleRequiring(String checkExpression, String... datasets) throws IOException
    {
        String body = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-RD\"},"
                + "\"Sensitivity\":\"Record\",\"Check\":{\"expression\":\"" + checkExpression
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(body).getRules().get("R1");
        assertNotNull(rule);
        assertEquals(null, rule.getLoadError(), "fixture must load cleanly");
        Requirements req = new Requirements();
        req.setDatasets(List.of(datasets));
        rule.setRequirements(req);
        return rule;
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    private static Map<String, IDataTable> study(IDataTable... tables)
    {
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            byName.put(t.getMetaData().getName(), t);
        }
        return byName;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver resolver)
    {
        return RuleRunner.execute(rule, primary, resolver, "AE", null, null, null);
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("AETERM", "", "headache").build();
    }


    @Test
    @DisplayName("a declared dataset the run ships leaves the rule running")
    void declaredAndPresentRuns() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "EX");
        IDataTable ex = MockTable.of().name("EX").col("EXDOSE", "1").build();
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae(), ex)));
        assertNotEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), r.getStatusMessage());
        assertEquals(1, r.getViolations().size(), "row 0 has an empty AETERM");
    }


    @Test
    @DisplayName("a declared dataset the run does NOT ship is an auditable SKIP")
    void declaredAndAbsentSkips() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "EX");
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae())));
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus());
        assertEquals("Rule skipped — Requirements.Datasets dataset EX not available",
                r.getStatusMessage());
    }


    @Test
    @DisplayName("a blank entry is skipped, not treated as a dataset named \"\"")
    void blankEntriesAreSkipped() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "");
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae())));
        assertNotEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), r.getStatusMessage());
        // Loader gate R3 rejects such an entry at load, so this is the runtime belt to that
        // braces: an entry that reached here anyway must not silently skip every rule.
    }


    @Test
    @DisplayName("no Requirements block at all changes nothing")
    void noRequirementsIsInert() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "EX");
        rule.setRequirements(null);
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae())));
        assertNotEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), r.getStatusMessage());
    }


    /**
     * ⭐ The widened half of the predicate. On a split {@code MS1}/{@code MS2} submission there is
     * no dataset named {@code MS}, but the domain <b>is</b> present — which is exactly what
     * {@code ds_exists("MS")} has answered since {@code Fix #358}. A requirement that answered
     * "absent" here would SKIP a rule the Check-side guard runs, re-opening the divergence D7
     * closed.
     */
    @Test
    @DisplayName("⭐ a SPLIT domain satisfies the requirement — the Fix #358 widening")
    void splitDomainSatisfiesTheRequirement() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "MS");
        // ⚠ REAL tables, not MockTable: the widened predicate unions the split parts, and
        // UnionDataTable copies column metadata through DataTableColumnMeta.builderFrom, which a
        // mocked column answers null for. Every split-domain fixture in this module uses RealTables
        // for that reason.
        IDataTable ms1 = RealTables.of("MS1").str("DOMAIN", "MS").str("MSTESTCD", "X").build();
        IDataTable ms2 = RealTables.of("MS2").str("DOMAIN", "MS").str("MSTESTCD", "Y").build();
        DatasetResolver.WithInventory resolver = inventory(study(ae(), ms1, ms2));
        // The control that makes the assertion mean something: exact-name resolution MISSES here.
        assertEquals(null, resolver.resolve("MS"), "control: no dataset is named MS");
        RuleExecutionResult r = run(rule, ae(), resolver);
        assertNotEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), r.getStatusMessage());
    }


    /**
     * ⛔⭐ The exact-name half, and the reason the predicate cannot be one unconditional rule.
     *
     * <p>
     * This is {@code CORE-000208}'s shape: the rule's <em>only</em> route to {@code TA} is an
     * {@code Operations[].domain}, the one surface {@code AbsentDatasetSkip.splitWidenedCandidates}
     * deliberately excludes because operations still resolve by exact name downstream. If the
     * requirement gated on the widened fact the rule would run on a split submission and then
     * evaluate against an <b>empty</b> operand — one finding per populated row, the {@code W34-C1}
     * flood. So the entry falls back to exact-name resolution and the rule SKIPs.
     * </p>
     */
    @Test
    @DisplayName("⛔ an Operations[].domain-only entry uses the EXACT-name predicate")
    void operationsDomainOnlyEntryUsesExactName() throws IOException
    {
        String pkg = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-RD-TA\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Operations\":[{\"id\":\"$ta_armcd\",\"operator\":\"distinct\","
                + "\"domain\":\"TA\",\"name\":\"ARMCD\"}],"
                + "\"Check\":{\"expression\":\"not empty(AETERM) and AETERM not in $ta_armcd\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("R1");
        assertNotNull(rule);
        assertEquals(null, rule.getLoadError(), "fixture must load cleanly");
        Requirements req = new Requirements();
        req.setDatasets(List.of("TA"));
        rule.setRequirements(req);

        IDataTable ta1 = RealTables.of("TA1").str("DOMAIN", "TA").str("ARMCD", "A").build();
        IDataTable ta2 = RealTables.of("TA2").str("DOMAIN", "TA").str("ARMCD", "B").build();
        DatasetResolver.WithInventory resolver = inventory(study(ae(), ta1, ta2));
        // Control: the WIDENED fact says present — so a green below can only come from the
        // per-entry exact-name fallback, not from the split domain being invisible.
        assertTrue(SplitDomainResolution.isPresentAsDomain(resolver, "TA"),
                "control: the widened predicate DOES see the split TA");

        RuleExecutionResult r = run(rule, ae(), resolver);
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus(),
                "the distinct operation still resolves TA exactly, so gating on the widened fact"
                        + " would un-skip the rule into the W34-C1 flood");
        assertEquals("Rule skipped — Requirements.Datasets dataset TA not available",
                r.getStatusMessage());
    }


    /**
     * The other side of the same partition: the ten {@code ds_exists} migration candidates name
     * their dataset only inside the presence call, which {@code AbsentDatasetSkip} steps over by
     * design — so the entry is reached by no surface at all and takes the widened predicate. That
     * is the right answer for the right reason: {@code Requirements.Datasets} then evaluates the
     * very predicate {@code ds_exists} does, and the migration is predicate-identical by
     * construction.
     */
    @Test
    @DisplayName("a ds_exists-guarded entry takes the WIDENED predicate")
    void dsExistsGuardedEntryUsesTheWidenedPredicate() throws IOException
    {
        String pkg = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-RD-EC\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"ds_exists(\\\"MS\\\") and empty(AETERM)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("R1");
        assertNotNull(rule);
        assertEquals(null, rule.getLoadError(),
                "fixture must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(),
                "the fixture must raise, or the surface scan sees nothing");
        Requirements req = new Requirements();
        req.setDatasets(List.of("MS"));
        rule.setRequirements(req);

        IDataTable ms1 = RealTables.of("MS1").str("DOMAIN", "MS").str("MSTESTCD", "X").build();
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae(), ms1)));
        assertNotEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), r.getStatusMessage());
    }


    @Test
    @DisplayName("a non-domain-code name falls straight back to exact resolution")
    void nonDomainCodeNameIsExact() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "ADSL");
        // The R-4 bound: the inventory walk never runs for a name that is not 2 characters, so a
        // study with ADSL1/ADSL2 and no ADSL does NOT satisfy the requirement.
        IDataTable adsl1 = RealTables.of("ADSL1").str("DOMAIN", "ADSL").str("USUBJID", "S1")
                .build();
        RuleExecutionResult absent = run(rule, ae(), inventory(study(ae(), adsl1)));
        assertEquals(RuleExecutionStatus.SKIPPED, absent.getStatus());

        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1").build();
        RuleExecutionResult present = run(rule, ae(), inventory(study(ae(), adsl)));
        assertNotEquals(RuleExecutionStatus.SKIPPED, present.getStatus(),
                present.getStatusMessage());
    }


    @Test
    @DisplayName("a `--` entry resolves against the primary, as an Operation's domain does")
    void dashDashEntryResolvesAgainstThePrimary() throws IOException
    {
        Rule rule = ruleRequiring("empty(AETERM)", "SUPP--");
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "X").build();
        RuleExecutionResult present = run(rule, ae(), inventory(study(ae(), suppae)));
        assertNotEquals(RuleExecutionStatus.SKIPPED, present.getStatus(),
                present.getStatusMessage());

        RuleExecutionResult absent = run(rule, ae(), inventory(study(ae())));
        assertEquals(RuleExecutionStatus.SKIPPED, absent.getStatus());
        assertTrue(absent.getStatusMessage().contains("SUPPAE"), absent.getStatusMessage());
    }


    /**
     * The same partition on a rule with <b>no native form</b> — an externally supplied or
     * legacy-only rule whose {@code checkExpr} never raised. The dotted walk needs a raised
     * expression, so the declared surfaces are read alone; the {@code Operations[].domain}
     * exclusion must survive that, or such a rule silently gets the widened predicate and the
     * {@code W34-C1} flood comes back through the back door.
     */
    @Test
    @DisplayName("the exact-name exclusion survives a rule with no raised Check")
    void exactNameExclusionSurvivesAnUnraisedCheck() throws IOException
    {
        String pkg = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-RD-TA2\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Operations\":[{\"id\":\"$ta_armcd\",\"operator\":\"distinct\","
                + "\"domain\":\"TA\",\"name\":\"ARMCD\"}],"
                + "\"Check\":{\"expression\":\"not empty(AETERM) and AETERM not in $ta_armcd\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("R1");
        assertNotNull(rule);
        rule.setCheckExpr(null);
        Requirements req = new Requirements();
        req.setDatasets(List.of("TA"));
        rule.setRequirements(req);

        IDataTable ta1 = RealTables.of("TA1").str("DOMAIN", "TA").str("ARMCD", "A").build();
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae(), ta1)));
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus(),
                "the declared Operations[].domain still resolves exact, raised Check or not");
    }


    /**
     * The complement of the test above: with no raised Check, a {@code Match_Datasets} name is
     * still a <b>widened</b> candidate — join sites resolve a split domain to its union — so the
     * declaration must NOT fall back to exact-name for it.
     */
    @Test
    @DisplayName("a Match_Datasets name stays WIDENED even with no raised Check")
    void matchDatasetsNameStaysWidenedWithoutARaisedCheck() throws IOException
    {
        String pkg = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-RD-MD\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Match_Datasets\":[{\"Name\":\"MS\",\"Keys\":[\"USUBJID\"]}],"
                + "\"Check\":{\"expression\":\"empty(AETERM)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("R1");
        assertNotNull(rule);
        rule.setCheckExpr(null);
        Requirements req = new Requirements();
        req.setDatasets(List.of("MS"));
        rule.setRequirements(req);

        IDataTable ms1 = RealTables.of("MS1").str("DOMAIN", "MS").str("USUBJID", "U1").build();
        RuleExecutionResult r = run(rule, ae(), inventory(study(ae(), ms1)));
        assertNotEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), r.getStatusMessage());
    }


    /**
     * ⚠ The ordering pin. A rule that both declares the requirement <em>and</em> reads the dataset
     * through a dotted reference the run already reports would, if the gates ran the other way
     * round, come back with {@code AbsentDatasetSkip}'s reason instead. The declared requirement is
     * the stronger statement — "this rule cannot answer at all" — so it must win.
     */
    @Test
    @DisplayName("⚠ the requirement is judged BEFORE AbsentDatasetSkip")
    void theRequirementPrecedesAbsentDatasetSkip() throws IOException
    {
        String pkg = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-RD-ORDER\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"empty(AETERM) and empty(EX.EXDOSE)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("R1");
        assertNotNull(rule);
        assertEquals(null, rule.getLoadError(),
                "fixture must load cleanly: " + rule.getLoadError());
        Requirements req = new Requirements();
        req.setDatasets(List.of("EX"));
        rule.setRequirements(req);

        DatasetResolver.WithInventory resolver = inventory(study(ae()));
        RuleExecutionResult declared = RuleRunner.execute(rule, ae(), resolver, "AE", null, null,
                null, 100, null, null, null, Set.of("EX"));
        assertEquals(RuleExecutionStatus.SKIPPED, declared.getStatus());
        assertEquals("Rule skipped — Requirements.Datasets dataset EX not available",
                declared.getStatusMessage(),
                "the declared requirement must win: a rule that cannot answer at all never enters"
                        + " the dependency-scoped suppression path");

        // Control: WITHOUT the declaration the same run reaches AbsentDatasetSkip and reports its
        // reason instead — so the assertion above is measuring the ordering, not an empty branch.
        rule.setRequirements(null);
        RuleExecutionResult underived = RuleRunner.execute(rule, ae(), resolver, "AE", null, null,
                null, 100, null, null, null, Set.of("EX"));
        assertEquals(RuleExecutionStatus.SKIPPED, underived.getStatus());
        assertNotEquals("Rule skipped — Requirements.Datasets dataset EX not available",
                underived.getStatusMessage());
    }

}
