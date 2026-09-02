package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.ScopeVariableSource;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.ExpansionDirective;
import net.cumba.cdisc.core.model.ExpansionSource;
import net.cumba.cdisc.core.model.GroupingSpec;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Requirements;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.VariableRequirement;
import net.cumba.cdisc.core.model.VariableUniverse;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fix #147 — {@code TokenExpander.buildExpansion} is the third {@code new Rule()} clone site in the
 * engine, and it has the same silent-loss shape as the other two: the expanded rule is assembled
 * field by field, so anything the assembly does not name is dropped from every concrete child while
 * the TEMPLATE still carries it. Nothing in the loader, the schemas or the writer can see the loss.
 *
 * <p>
 * The other half pinned here is the <b>binding</b> side's tautology filter: the rule's own
 * {@code Match_Datasets} keys are excluded from a {@code shared_variables} expansion, because after
 * merging on {@code USUBJID} the expansion {@code USUBJID != ADSL.USUBJID} is false on every row.
 * Losing the exclusion does not break anything visibly — it just adds rules that can never fire,
 * one per join key, on every dataset.
 * </p>
 */
class TokenExpansionRuleFieldsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CheckConditionLeaf leaf(String name, String operator)
    {
        return CheckConditionLeaf.builder().name(name).operator(operator).build();
    }


    private static ExpansionDirective sharedWith(String token, String with)
    {
        ExpansionDirective d = new ExpansionDirective();
        d.setToken(token);
        d.setOver(ExpansionSource.SHARED_VARIABLES);
        d.setWith(with);
        return d;
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


    private static TokenExpander.Context ctx(IDataTable primary, Map<String, IDataTable> others)
    {
        return new TokenExpander.Context(ScopeVariableSource.of(inventory(others), primary), null,
                primary.getMetaData().getName());
    }


    /** ADAE and ADSL share STUDYID, USUBJID and AGE; only AGE is not a join key. */
    private static IDataTable adae()
    {
        return MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U").col("AGE", "50")
                .build();
    }


    private static IDataTable adsl()
    {
        return MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U").col("AGE", "51")
                .build();
    }


    private static MatchDataset bareKeyedAdsl()
    {
        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("STUDYID", "USUBJID"));
        md.setJoinType("left");
        return md;
    }


    private static Rule richTemplate()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TK-F1");
        core.setStatus("Published");
        core.setVersion("9");
        rule.setCore(core);
        rule.setCheck(new CheckConditionAll(List.of(leaf("&VAR", "non_empty"))));
        rule.setPrecondition(new CheckConditionAll(List.of(leaf("&VAR", "var_exists"))));
        rule.setDescription("&VAR must be populated");
        rule.setSensitivity(Sensitivity.DATASET);
        rule.setVariableUniverse(VariableUniverse.DATA);
        rule.setMatchDatasets(List.of(bareKeyedAdsl()));
        rule.setExpansion(List.of(sharedWith("&VAR", "ADSL")));

        Operation op = new Operation();
        op.setId("$peak");
        op.setOperator("max");
        op.setName("&VAR");
        op.setCaseSensitive(Boolean.TRUE);
        rule.setOperations(List.of(op));

        GroupingSpec grouping = new GroupingSpec();
        grouping.setVariables(List.of("&VAR"));
        grouping.setKeepMissings(Boolean.TRUE);
        rule.setGrouping(grouping);

        VariableRequirement vars = new VariableRequirement();
        vars.setAll(List.of("USUBJID"));
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    private static List<Rule> expand(Rule template, IDataTable primary,
            Map<String, IDataTable> others)
    {
        return assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                TokenExpander.tryExpand(template, primary.getMetaData(), ctx(primary, others)))
                        .rules();
    }


    private static Rule expandOnce(Rule template, IDataTable primary,
            Map<String, IDataTable> others)
    {
        List<Rule> rules = expand(template, primary, others);
        assertEquals(1, rules.size(), "expected exactly one binding, got "
                + rules.stream().map(Rule::effectiveId).toList());
        Rule concrete = rules.get(0);
        // A load-errored expansion short-circuits the derivations, which would make several
        // assertions below vacuously green. The fixture must be clean.
        assertNull(concrete.getLoadError(), concrete.getLoadError());
        return concrete;
    }


    /**
     * The concrete rule's identity. The id must be the deterministic UUID of the expanded Core id,
     * or two runs over the same package produce rules that no report can correlate.
     */
    @Test
    @DisplayName("expansion identity: deterministic UUID, Status=Generated, Version=1")
    void expansionCarriesItsGeneratedIdentity()
    {
        Rule expanded = expandOnce(richTemplate(), adae(), Map.of("ADSL", adsl()));

        assertEquals("TK-F1-AGE", expanded.effectiveId());
        assertEquals(
                UUID.nameUUIDFromBytes("TK-F1-AGE".getBytes(StandardCharsets.UTF_8)).toString(),
                expanded.getId(),
                "a null or empty rule Id makes every expansion indistinguishable downstream");
        assertNotNull(expanded.getCore());
        assertEquals("Generated", expanded.getCore().getStatus(),
                "the expansion is generated — not the template's authored Published");
        assertEquals("1", expanded.getCore().getVersion(),
                "…and carries its own version, not the template's 9");
    }


    /**
     * Every top-level block that changes what the rule <em>evaluates</em>. {@code Requirements} is
     * the sharpest of these: losing it does not skip the rule, it runs it <b>unguarded</b>
     * (PLAN-scope-requirements-split §9 trap 3).
     */
    @Test
    @DisplayName("expansion carries Variable_Universe, Requirements, Grouping and the Precondition")
    void expansionCarriesTheEvaluationBlocks()
    {
        Rule expanded = expandOnce(richTemplate(), adae(), Map.of("ADSL", adsl()));

        assertEquals(VariableUniverse.DATA, expanded.getVariableUniverse(),
                "dropping Variable_Universe silently flips the rule between Define metadata and "
                        + "data columns");
        Requirements req = expanded.getRequirements();
        assertNotNull(req,
                "a dropped Requirements block is not a skipped rule but an UNGUARDED one");
        assertNotNull(req.getVariables());
        assertEquals(List.of("USUBJID"), req.getVariables().getAll());

        GroupingSpec grouping = expanded.getGrouping();
        assertNotNull(grouping, "the Grouping: block must ride onto the expansion");
        assertEquals(List.of("AGE"), grouping.getVariables(),
                "the grouping keys are token positions and must be substituted");
        assertEquals(true, grouping.getKeepMissings(),
                "the missing-key disposition rides along with the keys");

        CheckCondition precondition = expanded.getPrecondition();
        assertNotNull(precondition, "a dropped Precondition makes the rule run where it must not");
        assertEquals("AGE",
                ((CheckConditionLeaf) ((CheckConditionAll) precondition).getConditions().get(0))
                        .getName(),
                "the Precondition is substituted too, or it tests a column named '&VAR'");
    }


    /**
     * The operations are rewritten through the JSON tree, so a token in a column position is bound
     * and every non-textual field survives the round trip. {@code case_sensitive} is the canary: it
     * is a boolean, so it exercises the scalar leg of the tree rewrite that a string field does
     * not.
     */
    @Test
    @DisplayName("operations are substituted and non-textual fields survive the tree rewrite")
    void operationsAreSubstitutedAndScalarsSurvive()
    {
        Rule expanded = expandOnce(richTemplate(), adae(), Map.of("ADSL", adsl()));

        List<Operation> ops = expanded.getOperations();
        assertNotNull(ops, "without the operations the $peak reference resolves to nothing");
        assertEquals(1, ops.size());
        assertEquals("AGE", ops.get(0).getName(),
                "the column position is bound; leaving '&VAR' names a column that cannot exist");
        assertEquals("$peak", ops.get(0).getId());
        assertEquals(true, ops.get(0).getCaseSensitive(),
                "a non-textual field must survive the JSON-tree rewrite — nulling it silently "
                        + "flips the operation's comparison semantics");
    }


    /**
     * An authored {@code Sensitivity} survives untouched (and is not re-derived); an omitted one is
     * derived, because the expansion bypasses {@code RulePackageLoader} entirely. The native
     * program must also be compiled onto the expansion, or it takes a different evaluation path
     * from the identical rule loaded concretely.
     */
    @Test
    @DisplayName("Sensitivity: authored survives, omitted is derived; the native form is installed")
    void sensitivityIsCarriedOrDerivedAndTheNativeFormIsInstalled()
    {
        Rule authored = expandOnce(richTemplate(), adae(), Map.of("ADSL", adsl()));
        assertEquals(Sensitivity.DATASET, authored.getSensitivity(),
                "the template authored Dataset; losing it re-derives a different granularity");
        Map<String, String> rationale = authored.getDerivationRationale();
        assertNull(rationale == null ? null : rationale.get("Sensitivity"),
                "nothing was omitted, so nothing may be derived over the authored value: "
                        + rationale);
        assertNotNull(authored.getCheckExpr(),
                "the expansion must carry the compiled native program, like a loader-loaded rule");

        Rule template = richTemplate();
        template.setSensitivity(null);
        Rule derived = expandOnce(template, adae(), Map.of("ADSL", adsl()));
        assertNotNull(derived.getSensitivity(),
                "the loader never sees this rule, so the expander must derive what the template "
                        + "omitted");
        assertNotNull(derived.getDerivationRationale(),
                "…and the rationale is the receipt that the derivation actually ran");
        assertNotNull(derived.getDerivationRationale().get("Sensitivity"),
                "" + derived.getDerivationRationale());
    }


    /**
     * A template that authored no {@code Match_Datasets} / {@code Operations} must not acquire
     * empty ones. {@code Match_Datasets: []} is an authored statement that the rule joins nothing;
     * absence is the absence of the block. The two round-trip differently through the writer and
     * read differently in a report.
     */
    @Test
    @DisplayName("absent Match_Datasets and Operations stay absent on the expansion")
    void absentBlocksStayAbsent()
    {
        Rule template = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TK-F2");
        template.setCore(core);
        template.setCheck(new CheckConditionAll(List.of(leaf("&VAR", "non_empty"))));
        template.setExpansion(List.of(sharedWith("&VAR", "ADSL")));

        List<Rule> rules = expand(template, adae(), Map.of("ADSL", adsl()));

        // No Match_Datasets ⇒ no join keys are excluded, so every shared column binds.
        assertEquals(List.of("TK-F2-STUDYID", "TK-F2-USUBJID", "TK-F2-AGE"),
                rules.stream().map(Rule::effectiveId).toList());
        for (Rule concrete : rules)
        {
            assertNull(concrete.getMatchDatasets(),
                    concrete.effectiveId() + " must not gain an empty Match_Datasets block");
            assertNull(concrete.getOperations(),
                    concrete.effectiveId() + " must not gain an empty Operations block");
        }
    }


    /**
     * The tautology filter. After merging on {@code STUDYID}/{@code USUBJID} the expansion
     * {@code USUBJID != ADSL.USUBJID} is false by construction on every row, so binding over a join
     * key can only add rules that never fire. The exclusion is read off the rule, not a maintained
     * list — the previous test proves the same fixture binds all three columns without it.
     */
    @Test
    @DisplayName("shared_variables never binds over the rule's own join keys")
    void bindingExcludesTheRulesOwnJoinKeys()
    {
        List<Rule> rules = expand(richTemplate(), adae(), Map.of("ADSL", adsl()));

        assertEquals(List.of("TK-F1-AGE"), rules.stream().map(Rule::effectiveId).toList(),
                "STUDYID and USUBJID are shared by name but joined ON — expanding over them "
                        + "yields rules that are false on every row");
    }


    /**
     * EC-18 sided keys: a join whose column is named differently on each side must have <b>both</b>
     * names excluded. Only the right-hand name is at risk — it is not in {@code getKeys()} — and
     * missing it binds the token to the joined side's own key, i.e. a rule comparing {@code SUBJID}
     * with {@code ADSL.SUBJID} after joining exactly those two.
     */
    @Test
    @DisplayName("sided join keys are excluded on BOTH sides")
    void bindingExcludesBothSidesOfASidedJoinKey() throws Exception
    {
        IDataTable primary = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("SUBJID", "1").col("AGE", "50").build();
        IDataTable foreign = MockTable.of().name("ADSL").col("STUDYID", "S").col("SUBJID", "1")
                .col("AGE", "51").build();
        MatchDataset sided = MAPPER.readValue("""
                {"Name":"ADSL",
                 "Keys":[{"left":"STUDYID","right":"STUDYID"},{"left":"USUBJID","right":"SUBJID"}],
                 "Join_Type":"left"}
                """, MatchDataset.class);
        assertEquals(List.of("STUDYID", "USUBJID"), sided.getKeys(), "fixture sanity");
        assertEquals(List.of("STUDYID", "SUBJID"), sided.getRightKeys(), "fixture sanity");

        Rule template = richTemplate();
        template.setMatchDatasets(List.of(sided));

        List<Rule> rules = expand(template, primary, Map.of("ADSL", foreign));

        assertEquals(List.of("TK-F1-AGE"), rules.stream().map(Rule::effectiveId).toList(),
                "SUBJID is shared by name and is the RIGHT half of a sided join key — binding "
                        + "over it produces a rule that is false on every row");
        List<MatchDataset> joins = rules.get(0).getMatchDatasets();
        assertNotNull(joins);
        assertEquals(List.of("STUDYID", "SUBJID"), joins.get(0).getRightKeys(),
                "the sided key shape must survive the expansion's JSON-tree rewrite");
    }


    /**
     * A declared token that <em>contains</em> another must not make the result depend on iteration
     * order: the substitutions run longest-token-first. Load-time validation rejects the shape
     * outright, so this pins that the mechanism is still well defined for a rule loaded past it —
     * without the ordering, {@code &VX} becomes {@code AGEX}, a column that cannot exist, and the
     * rule silently checks nothing.
     */
    @Test
    @DisplayName("overlapping tokens substitute longest-first, whatever order they are declared in")
    void overlappingTokensSubstituteLongestFirst()
    {
        Rule template = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TK-ORD");
        template.setCore(core);
        template.setCheck(new CheckConditionAll(List.of(leaf("&VX", "non_empty"))));
        template.setMatchDatasets(List.of(bareKeyedAdsl()));
        // Declared SHORTEST first on purpose: declaration order is the tuple order, so only an
        // explicit longest-first sort saves the longer token from being clobbered.
        template.setExpansion(List.of(sharedWith("&V", "ADSL"), sharedWith("&VX", "ADSL")));

        Rule expanded = expandOnce(template, adae(), Map.of("ADSL", adsl()));

        CheckConditionLeaf got = (CheckConditionLeaf) ((CheckConditionAll) expanded.getCheck())
                .getConditions().get(0);
        assertEquals("AGE", got.getName(),
                "'&VX' must be replaced whole; substituting '&V' first leaves 'AGEX'");
    }


    /**
     * Silence is the failure mode this mechanism exists to prevent: a source that cannot be read
     * must produce a NoMatch carrying the <b>stated reason</b>, which the generator turns into a
     * SKIPPED audit row. A generic "no candidates" would tell the reader nothing about why the rule
     * did not run.
     */
    @Test
    @DisplayName("an unreadable source yields a NoMatch naming the actual reason")
    void anUnreadableSourceStatesItsReason()
    {
        Rule template = richTemplate();

        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(template,
                adae().getMetaData(), ctx(adae(), Map.of()));

        String reason = assertInstanceOf(WildcardExpander.ExpansionResult.NoMatch.class, result)
                .reason();
        assertTrue(
                reason.endsWith(
                        "(dataset 'ADSL' is not among the loaded datasets);" + " not expanded for"),
                reason);
        assertFalse(reason.contains("no candidates"),
                "the concrete reason must be reported, not the empty-list placeholder: " + reason);
    }

}
