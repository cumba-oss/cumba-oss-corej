package net.cumba.corej.core.gen;

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
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.GroupingSpec;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.corej.core.model.VariableUniverse;
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

    private static net.cumba.corej.core.model.CheckConditionExpression leaf(String name,
            String operator)
    {
        String ref = name;
        String source = switch (operator)
        {
        case "non_empty" -> "not empty(" + ref + ")";
        case "var_exists" -> "var_exists(" + ref + ")";
        default -> throw new IllegalArgumentException(operator);
        };
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    private static ExpansionDirective sharedWith(String token, String with)
    {
        ExpansionDirective d = new ExpansionDirective();
        d.setToken(token);
        d.setOverJson(ExpansionSource.SHARED_VARIABLES.getJsonValue());
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
        rule.setCheck(new CheckConditionAll(List.of(leaf("&VAR&", "non_empty"))));
        rule.setPrecondition(new CheckConditionAll(List.of(leaf("&VAR&", "var_exists"))));
        rule.setDescription("&VAR& must be populated");
        rule.setSensitivity(Sensitivity.DATASET);
        rule.setVariableUniverse(VariableUniverse.DATA);
        rule.setMatchDatasets(List.of(bareKeyedAdsl()));
        rule.setExpansion(List.of(sharedWith("&VAR&", "ADSL")));

        rule.setCompiledBindings(
                List.of(new CompiledBinding("$peak", net.cumba.corej.core.expr.CheckExpressionParser
                        .parse("max(&VAR&, group=[USUBJID])"), List.of(), null)));

        GroupingSpec grouping = new GroupingSpec();
        grouping.setVariables(List.of("&VAR&"));
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
        assertEquals("var_exists(AGE)", net.cumba.corej.core.expr.ExpressionPrinter.print(
                ((net.cumba.corej.core.model.CheckConditionExpression) ((CheckConditionAll) precondition)
                        .getConditions().get(0)).expr()),
                "the Precondition is substituted too, or it tests a column named '&VAR&'");
    }


    /**
     * The compiled bindings get the Check's substitution, so a token in a column position is bound
     * and every other position of the expression survives. (Until runbook W8 this was pinned on an
     * operation record rewritten through the JSON tree, with its {@code delimiter} field as the
     * non-column canary; the {@code group=} key column is that canary now.)
     */
    @Test
    @DisplayName("bindings are substituted and non-token positions survive the rewrite")
    void bindingsAreSubstitutedAndOtherPositionsSurvive()
    {
        Rule expanded = expandOnce(richTemplate(), adae(), Map.of("ADSL", adsl()));

        List<CompiledBinding> bindings = expanded.getCompiledBindings();
        assertNotNull(bindings, "without the bindings the $peak reference resolves to nothing");
        assertEquals(1, bindings.size());
        assertEquals("max(AGE, group=[USUBJID])",
                net.cumba.corej.core.expr.ExpressionPrinter.print(bindings.get(0).expression()),
                "the column position is bound (leaving '&VAR&' names a column that cannot exist),"
                        + " and the non-token group= key survives the rewrite untouched");
        assertEquals("$peak", bindings.get(0).name());
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
                String.valueOf(derived.getDerivationRationale()));
    }


    /**
     * A template that authored no {@code Match_Datasets} / {@code Bindings} must not acquire empty
     * ones. {@code Match_Datasets: []} is an authored statement that the rule joins nothing;
     * absence is the absence of the block. The two round-trip differently through the writer and
     * read differently in a report.
     */
    @Test
    @DisplayName("absent Match_Datasets and Bindings stay absent on the expansion")
    void absentBlocksStayAbsent()
    {
        Rule template = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TK-F2");
        template.setCore(core);
        template.setCheck(new CheckConditionAll(List.of(leaf("&VAR&", "non_empty"))));
        template.setExpansion(List.of(sharedWith("&VAR&", "ADSL")));

        List<Rule> rules = expand(template, adae(), Map.of("ADSL", adsl()));

        // No Match_Datasets ⇒ no join keys are excluded, so every shared column binds.
        assertEquals(List.of("TK-F2-STUDYID", "TK-F2-USUBJID", "TK-F2-AGE"),
                rules.stream().map(Rule::effectiveId).toList());
        for (Rule concrete : rules)
        {
            assertNull(concrete.getMatchDatasets(),
                    concrete.effectiveId() + " must not gain an empty Match_Datasets block");
            assertNull(concrete.getCompiledBindings(),
                    concrete.effectiveId() + " must not gain an empty bindings list");
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
     * EC-18 sided keys: only a position whose two sides are the SAME name is a tautology.
     * {@code {left: STUDYID, right: STUDYID}} merges {@code STUDYID} on {@code ADSL.STUDYID}, so
     * {@code STUDYID != ADSL.STUDYID} is decided by the merge and never bound. {@code {left:
     * USUBJID, right: SUBJID}} merges the primary's {@code USUBJID} on {@code ADSL.SUBJID}, so
     * {@code SUBJID != ADSL.SUBJID} compares the primary's {@code SUBJID} with its own
     * {@code USUBJID} — a real comparison, and it IS bound.
     *
     * <p>
     * ⚑ Moved pin (review round 1 of {@code PLAN-rprfdy-offset-tp-join}, lane 1 L2): this test was
     * {@code bindingExcludesBothSidesOfASidedJoinKey} and asserted {@code [TK-F1-AGE]}, on the
     * claim that {@code SUBJID != ADSL.SUBJID} is "false on every row" after "joining exactly those
     * two" — but the join paired {@code USUBJID} with {@code SUBJID}, not {@code SUBJID} with
     * itself, so the exclusion dropped a live binding silently.
     * </p>
     */
    @Test
    @DisplayName("only the same-named positions of a sided join key are excluded")
    void bindingExcludesOnlyTheSameNamedPositionsOfASidedJoinKey() throws Exception
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

        assertEquals(List.of("TK-F1-SUBJID", "TK-F1-AGE"),
                rules.stream().map(Rule::effectiveId).toList(),
                "STUDYID (merged on itself) is the tautology; SUBJID is the RIGHT half of a sided"
                        + " key whose LEFT half is USUBJID, so SUBJID != ADSL.SUBJID is a real"
                        + " comparison of the primary's SUBJID with its USUBJID");
        List<MatchDataset> joins = rules.get(0).getMatchDatasets();
        assertNotNull(joins);
        assertEquals(List.of("STUDYID", "SUBJID"), joins.get(0).getRightKeys(),
                "the sided key shape must survive the expansion's JSON-tree rewrite");
    }


    /**
     * {@code PLAN-expansion-token-delimiters} S2 — the substitution is scan-based, not a
     * {@code String.replace} loop. Overlapping occurrences share a delimiter: in {@code &A&B&C&}
     * the text {@code &B&} also occurs at index 2, but the scan reads {@code &A&}, {@code B},
     * {@code &C&}, so binding only {@code &B&} leaves the text unchanged. The replace loop would
     * give {@code &AYC&}. A loaded rule of this shape is a G2 error (the other two tokens are
     * undeclared), so this is a unit test on the primitive itself.
     */
    @Test
    @DisplayName("only a scanned occurrence is substituted, never an overlapping one")
    void substitutionIsScanBasedNotAReplaceLoop()
    {
        Map<String, String> onlyB = Map.of("&B&", "Y");
        assertEquals("&A&B&C&", TokenExpander.substitute("&A&B&C&", onlyB),
                "'&B&' at index 2 shares its delimiters with '&A&' and '&C&' and is not read");
        assertEquals("X.&A&B&C& and Y", TokenExpander.substitute("X.&A&B&C& and &B&", onlyB));
        assertEquals("K1&A&B&C&", TokenExpander.substitute("K1&A&B&C&", onlyB));
        // Operator text is not a token either.
        assertEquals("A&&B&&C&", TokenExpander.substitute("A&&B&&C&", onlyB));
        // Bound in insertion order, every occurrence is replaced whole; declaration order cannot
        // change the answer because occurrences never overlap.
        Map<String, String> all = new java.util.LinkedHashMap<>();
        all.put("&C&", "R");
        all.put("&A&", "P");
        assertEquals("PBR", TokenExpander.substitute("&A&B&C&", all));
        assertEquals("AE.AESEQ", TokenExpander.substitute("&DOM&.&DOM&SEQ", Map.of("&DOM&", "AE")));
        assertEquals("no token", TokenExpander.substitute("no token", all));
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
