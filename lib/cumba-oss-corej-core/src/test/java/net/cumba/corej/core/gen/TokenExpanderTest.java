package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #147 — declared-token template expansion ({@code Expansion:}).
 *
 * <p>
 * The properties pinned here are the ones whose absence is <b>silent</b>: the
 * {@code known_domain_only} exclusion (without it {@code ASEQ} expands to {@code ASEQ ∈ AS.ASEQ}),
 * the skip-with-a-reason contract for an unreadable source (without it the rule expands to zero
 * rules and checks nothing, which is how {@code CDISC-AD0591}/{@code CDISC-AD0898} shipped as
 * no-ops for months), and byte-equality of the {@code CDISC-AD0898} expansion with the
 * hand-authored {@code PMDA-AD0258} shape.
 * </p>
 */
class TokenExpanderTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ExpansionDirective sharedWith(String token, String with)
    {
        ExpansionDirective d = new ExpansionDirective();
        d.setToken(token);
        d.setOverJson(ExpansionSource.SHARED_VARIABLES.getJsonValue());
        d.setWith(with);
        return d;
    }


    private static ExpansionDirective domainFrom(String token, String pattern, boolean knownOnly)
    {
        ExpansionDirective d = new ExpansionDirective();
        d.setToken(token);
        d.setOverJson(ExpansionSource.DOMAIN_FROM_VARIABLE.getJsonValue());
        d.setPattern(pattern);
        d.setKnownDomainOnly(knownOnly);
        return d;
    }


    private static net.cumba.corej.core.model.CheckConditionExpression leaf(String name,
            String operator, @Nullable String value)
    {
        // Bare, as the authored corpus templates spell them: a `&NAME&` token lexes inside an
        // identifier (PLAN-expansion-token-delimiters), so `&VAR&` / `ADSL.&VAR&` need no
        // backticks.
        String ref = name;
        String source = switch (operator)
        {
        case "non_empty" -> "not empty(" + ref + ")";
        case "empty" -> "empty(" + ref + ")";
        case "var_exists" -> "var_exists(" + ref + ")";
        case "not_equal_to" -> ref + " != " + value;
        default -> throw new IllegalArgumentException(operator);
        };
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    private static Rule template(String coreId, CheckCondition check,
            @Nullable List<MatchDataset> matchDatasets, ExpansionDirective... directives)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setMatchDatasets(matchDatasets);
        rule.setExpansion(List.of(directives));
        return rule;
    }


    private static MatchDataset match(String name, String... keys)
    {
        MatchDataset md = new MatchDataset();
        md.setName(name);
        md.setKeys(List.of(keys));
        md.setJoinType("left");
        return md;
    }


    /** The {@code CDISC-AD0591} template shape, minus the corpus boilerplate. */
    private static Rule ad0591Template()
    {
        Rule rule = template("CDISC-AD0591",
                new CheckConditionAll(List.of(leaf("&VAR&", "non_empty", null),
                        leaf("ADSL.&VAR&", "non_empty", null),
                        leaf("&VAR&", "not_equal_to", "ADSL.&VAR&"))),
                List.of(match("ADSL", "STUDYID", "USUBJID")), sharedWith("&VAR&", "ADSL"));
        Outcome outcome = new Outcome();
        outcome.setMessage("&VAR& does not match ADSL.&VAR&");
        outcome.setOutputVariables(List.of("&VAR&", "ADSL.&VAR&"));
        rule.setOutcome(outcome);
        return rule;
    }


    /** The {@code CDISC-AD0898} template shape. */
    private static Rule ad0898Template()
    {
        Rule rule = template("CDISC-AD0898",
                new CheckConditionAll(List.of(leaf("&DOM&.&DOM&SEQ", "var_exists", null),
                        leaf("&DOM&.&DOM&SEQ", "empty", null))),
                List.of(match("&DOM&", "STUDYID", "USUBJID", "&DOM&SEQ")),
                domainFrom("&DOM&", "&DOM&SEQ", true));
        Outcome outcome = new Outcome();
        outcome.setMessage(
                "The ADaM --SEQ value is not present in the parent SDTM domain's --SEQ.");
        outcome.setOutputVariables(List.of("&DOM&SEQ", "&DOM&.&DOM&SEQ"));
        rule.setOutcome(outcome);
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


    private static Map<String, IDataTable> map(Object... pairs)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            m.put((String) pairs[i], (IDataTable) pairs[i + 1]);
        }
        return m;
    }


    /** A provider whose library attests exactly {@code domains} as standard dataset names. */
    private static MetadataProvider libraryWith(@Nullable List<String> domains)
    {
        MetadataProvider provider = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito.lenient().when(provider.getStandardDatasetNames()).thenReturn(domains);
        return provider;
    }


    private static TokenExpander.Context ctx(IDataTable primary, Map<String, IDataTable> others,
            @Nullable List<String> standardDomains)
    {
        ScopeVariableSource source = ScopeVariableSource.of(inventory(others), primary);
        return new TokenExpander.Context(source, libraryWith(standardDomains),
                primary.getMetaData().getName());
    }


    private static List<Rule> expanded(WildcardExpander.ExpansionResult result)
    {
        return assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class, result).rules();
    }


    private static String reason(WildcardExpander.ExpansionResult result)
    {
        return assertInstanceOf(WildcardExpander.ExpansionResult.NoMatch.class, result).reason();
    }


    /**
     * Renders a rule's Check back to expression text — the same rendering the corpus generator
     * uses, so an assertion here reads like the shipped {@code rules/} form.
     */
    private static String expressionOf(Rule rule)
    {
        CheckCondition check = rule.getCheck();
        assertNotNull(check);
        return net.cumba.corej.core.expr.ExpressionPrinter
                .print(net.cumba.corej.core.expr.CheckToExpr.toExpr(check));
    }

    // ------------------------------------------------------------------
    // over: shared_variables — sets of n, 1 and 0
    // ------------------------------------------------------------------


    @Test
    void sharedVariablesExpandsOncePerSharedColumn()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").col("TRT01P", "A").col("AESEQ", "1").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "51").col("TRT01P", "A").build();

        List<Rule> rules = expanded(TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(List.of("CDISC-AD0591-AGE", "CDISC-AD0591-TRT01P"),
                rules.stream().map(Rule::effectiveId).toList());
        assertEquals("not empty(AGE) and not empty(ADSL.AGE) and AGE != ADSL.AGE",
                expressionOf(rules.get(0)));
        assertEquals(List.of("AGE", "ADSL.AGE"), rules.get(0).getOutcome().getOutputVariables());
        assertEquals("AGE does not match ADSL.AGE", rules.get(0).getOutcome().getMessage());
    }


    /**
     * {@code Fix #356} — the {@code Output_Variables} rename is a whole-name map lookup, so an
     * {@code !X} exclusion token ({@code OutputVariableToken}) must be renamed by the name INSIDE
     * it. A raw {@code "!&VAR&"} is not a key of the substitution map and would survive with its
     * token unresolved while the Check resolved; the {@code deriveOutputVariables} call at the end
     * of the expansion then rejects it (E-3.1) and the rule reports ENGINE_ERROR.
     */

    @Test
    void sharedVariablesRenamesTheNameInsideAnExclusionToken()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "51").build();

        Rule template = ad0591Template();
        assertNotNull(template.getOutcome());
        template.getOutcome().setOutputVariables(List.of("!&VAR&", "ADSL.&VAR&"));

        List<Rule> rules = expanded(TokenExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(1, rules.size());
        Rule concrete = rules.get(0);
        // Both arms resolved — the marker survives, the wildcard behind it does not.
        assertEquals(List.of("!AGE", "ADSL.AGE"), concrete.getOutcome().getOutputVariables());
        // … and the expansion's own load validation accepts it (an unresolved `!&VAR&` would not).
        assertNull(concrete.getLoadError(), concrete.getLoadError());
        assertEquals(List.of("ADSL.AGE"), concrete.getEffectiveOutputVariables());
    }


    /**
     * Combined review W2 M2: {@code Supp_Merge} is carried onto every expanded child; before the
     * fix the fresh {@code new Rule()} dropped a template's {@code Supp_Merge: false} and each
     * child read the default {@code true}.
     */
    @Test
    void sharedVariablesCarriesSuppMergeOntoEveryExpandedChild()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").col("TRT01P", "A").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "51").col("TRT01P", "A").build();
        Rule template = ad0591Template();
        template.setSuppMerge(Boolean.FALSE);

        List<Rule> rules = expanded(TokenExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(2, rules.size(), "AGE and TRT01P");
        for (Rule child : rules)
        {
            assertEquals(false, child.getSuppMerge(), child.effectiveId());
            assertFalse(child.isSuppMergeEnabled(), child.effectiveId());
        }
    }


    @Test
    void sharedVariablesExcludesTheColumnsTheRuleAlreadyMergesOn()
    {
        // STUDYID and USUBJID are shared by name, but the rule joins ON them: after the merge
        // `USUBJID != ADSL.USUBJID` is false on every row, so expanding over them can only cost
        // execution time. AESEQ is not in ADSL, so it is not shared at all.
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").col("AESEQ", "1").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "51").build();

        List<Rule> rules = expanded(TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(List.of("CDISC-AD0591-AGE"), rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void sharedVariablesWithNoSharedColumnSkipsWithAReason()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .build();

        String reason = reason(TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertTrue(reason.contains("no variable is shared by name with 'ADSL'"), reason);
    }


    @Test
    void sharedVariablesWithTheForeignDatasetAbsentSkipsWithAReason()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("USUBJID", "U").col("AGE", "50").build();

        String reason = reason(TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(),
                ctx(adae, map(), List.of())));

        assertTrue(reason.contains("'ADSL' is not among the loaded datasets"), reason);
    }


    @Test
    void sharedVariablesWithABlindResolverSkipsWithAReason()
    {
        // The RuleEditorService preview supplies a non-null resolver that resolves nothing; a
        // plain (non-inventory) resolver yields a null ScopeVariableSource. Zero-expansion
        // silence here is exactly what made AD0591 invisible.
        IDataTable adae = MockTable.of().name("ADAE").col("USUBJID", "U").col("AGE", "50").build();
        TokenExpander.Context blind = new TokenExpander.Context(null, libraryWith(List.of()),
                "ADAE");

        String reason = reason(
                TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(), blind));

        assertTrue(reason.contains("no inventory-capable dataset resolver"), reason);
    }


    @Test
    void sharedVariablesSkipsWhenTheDatasetUnderValidationIsTheForeignDataset()
    {
        // AD0591 ships Scope.Domains.Include: [ALL], so it reaches ADSL itself. Every shared
        // variable would then be compared with itself after a self-join — never a finding, and on
        // a wide ADSL that is hundreds of vacuous expansions per run.
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").col("SEX", "M").build();

        String reason = reason(TokenExpander.tryExpand(ad0591Template(), adsl.getMetaData(),
                ctx(adsl, map("ADSL", adsl), List.of())));

        assertTrue(reason.contains("the dataset under validation IS 'ADSL'"), reason);
    }


    @Test
    void sharedVariablesUnionsTheMembersOfASplitDomain()
    {
        // metasOf falls back to tablesForDomain for a two-character domain code, so a split
        // domain contributes every member's columns.
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "U").col("LBORRES", "1")
                .col("LBSTRESC", "x").build();
        IDataTable lbch = MockTable.of().name("lbch").col("DOMAIN", "LB").col("LBORRES", "2")
                .build();
        IDataTable lbhe = MockTable.of().name("lbhe").col("DOMAIN", "LB").col("LBSTRESC", "y")
                .build();

        Rule rule = template("T",
                new CheckConditionAll(List.of(leaf("&V&", "not_equal_to", "LB.&V&"))),
                List.of(match("LB", "USUBJID")), sharedWith("&V&", "LB"));

        List<Rule> rules = expanded(TokenExpander.tryExpand(rule, adlb.getMetaData(),
                ctx(adlb, map("lbch", lbch, "lbhe", lbhe), List.of())));

        assertEquals(List.of("T-LBORRES", "T-LBSTRESC"),
                rules.stream().map(Rule::effectiveId).toList());
    }

    // ------------------------------------------------------------------
    // over: domain_from_variable — the known_domain_only exclusion
    // ------------------------------------------------------------------


    @Test
    void domainFromVariableExpansionIsByteEqualToTheHandAuthoredPmdaAd0258Shape()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();

        List<Rule> rules = expanded(TokenExpander.tryExpand(ad0898Template(), adae.getMetaData(),
                ctx(adae, map("AE", ae), List.of("AE", "CM", "MH"))));

        assertEquals(1, rules.size());
        Rule concrete = rules.get(0);
        assertEquals("CDISC-AD0898-AESEQ", concrete.effectiveId());
        // PMDA-AD0258 ships exactly this Check and exactly these Match_Datasets.
        assertEquals("var_exists(AE.AESEQ) and empty(AE.AESEQ)", expressionOf(concrete));
        List<MatchDataset> matched = concrete.getMatchDatasets();
        assertNotNull(matched);
        assertEquals(1, matched.size());
        assertEquals("AE", matched.get(0).getName());
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"), matched.get(0).getKeys());
        assertEquals("left", matched.get(0).getJoinType());
        assertEquals(List.of("AESEQ", "AE.AESEQ"), concrete.getOutcome().getOutputVariables());
    }


    @Test
    void knownDomainOnlyExcludesAdamsOwnSeqVariables()
    {
        // ASEQ / SRCSEQ / RECSEQ all match the &DOM&SEQ pattern but are NOT parent references:
        // ASEQ is ADaM's own sequence, SRCSEQ names its parent in SRCDOM, RECSEQ is neither.
        // Without the filter they would expand to ASEQ ∈ A.ASEQ, SRCSEQ ∈ SRC.SRCSEQ, …
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("ASEQ", "1").col("SRCSEQ", "1").col("RECSEQ", "1").col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U").col("AESEQ", "1").build();
        // Datasets literally named A / SRC / REC are loaded on purpose. Without them the
        // availability check would drop those three anyway and this test would pin nothing —
        // measured: neutering known_domain_only left it green.
        IDataTable a = MockTable.of().name("A").col("ASEQ", "1").build();
        IDataTable src = MockTable.of().name("SRC").col("SRCSEQ", "1").build();
        IDataTable rec = MockTable.of().name("REC").col("RECSEQ", "1").build();

        List<Rule> rules = expanded(TokenExpander.tryExpand(ad0898Template(), adae.getMetaData(),
                ctx(adae, map("AE", ae, "A", a, "SRC", src, "REC", rec),
                        List.of("AE", "CM", "MH", "DM"))));

        assertEquals(List.of("CDISC-AD0898-AESEQ"), rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void knownDomainOnlyWithoutALibraryDatasetListSkipsWithAReason()
    {
        // A null standard-dataset list means the filter cannot be applied. Expanding anyway would
        // emit ASEQ ∈ A.ASEQ; expanding nothing silently would repeat the original defect.
        IDataTable adae = MockTable.of().name("ADAE").col("USUBJID", "U").col("ASEQ", "1")
                .col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U").col("AESEQ", "1").build();

        String reason = reason(TokenExpander.tryExpand(ad0898Template(), adae.getMetaData(),
                ctx(adae, map("AE", ae), null)));

        assertTrue(reason.contains("known_domain_only is set but the library serves no standard"),
                reason);
    }


    @Test
    void domainFromVariableDropsACandidateWhoseParentDatasetIsNotLoaded()
    {
        // CMSEQ names a real domain, but CM is not among the loaded datasets, so that expansion
        // could not check anything. AESEQ still expands — a partial drop must not kill the rule.
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").col("CMSEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U").col("AESEQ", "1").build();

        List<Rule> rules = expanded(TokenExpander.tryExpand(ad0898Template(), adae.getMetaData(),
                ctx(adae, map("AE", ae), List.of("AE", "CM"))));

        assertEquals(List.of("CDISC-AD0898-AESEQ"), rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void domainFromVariableDropsASelfReferentialExpansion()
    {
        // Validating AE itself: AESEQ's inferred parent IS this dataset, so the expansion would
        // join AE to AE on AESEQ and could never fire.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U").col("AESEQ", "1").build();

        String reason = reason(TokenExpander.tryExpand(ad0898Template(), ae.getMetaData(),
                ctx(ae, map("AE", ae), List.of("AE"))));

        assertTrue(reason.contains("was filtered out"), reason);
    }


    @Test
    void domainFromVariableWithNoMatchingColumnSkipsWithAReason()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "U").col("AGE", "50").build();

        String reason = reason(TokenExpander.tryExpand(ad0898Template(), adsl.getMetaData(),
                ctx(adsl, map(), List.of("AE"))));

        assertTrue(reason.contains("no column matches the pattern '&DOM&SEQ'"), reason);
    }


    @Test
    void domainFromVariableRequiresANonEmptyCapture()
    {
        // A column named exactly "SEQ" would capture the empty string — not a domain code.
        IDataTable ds = MockTable.of().name("ADX").col("USUBJID", "U").col("SEQ", "1").build();

        String reason = reason(TokenExpander.tryExpand(ad0898Template(), ds.getMetaData(),
                ctx(ds, map(), List.of("AE"))));

        assertTrue(reason.contains("no column matches the pattern"), reason);
    }


    @Test
    void knownDomainOnlyOffAcceptsEveryCapture()
    {
        // The filter is opt-in; with it off the mechanism itself imposes no domain vocabulary.
        IDataTable adae = MockTable.of().name("ADAE").col("USUBJID", "U").col("ASEQ", "1").build();
        IDataTable a = MockTable.of().name("A").col("USUBJID", "U").col("ASEQ", "1").build();
        Rule rule = template("T",
                new CheckConditionAll(List.of(leaf("&D&.&D&SEQ", "var_exists", null))),
                List.of(match("&D&", "USUBJID")), domainFrom("&D&", "&D&SEQ", false));

        List<Rule> rules = expanded(
                TokenExpander.tryExpand(rule, adae.getMetaData(), ctx(adae, map("A", a), null)));

        assertEquals(List.of("T-ASEQ"), rules.stream().map(Rule::effectiveId).toList());
    }

    // ------------------------------------------------------------------
    // Routing and the source-blind substitution contract
    // ------------------------------------------------------------------


    @Test
    void wildcardExpanderRoutesAnExpansionBearingRuleToTheTokenExpander()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "51").build();

        List<Rule> rules = expanded(WildcardExpander.tryExpand(ad0591Template(), adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(List.of("CDISC-AD0591-AGE"), rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void wildcardExpanderLeavesAnUntokenisedRuleOnTheTwoArgPath()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("TRT01P", "A").col("TR01SDT", "").build();
        Rule wildcardRule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("W");
        wildcardRule.setCore(core);
        wildcardRule.setCheck(new CheckConditionAll(List.of(leaf("TRTxxP", "var_exists", null))));

        List<Rule> rules = expanded(WildcardExpander.tryExpand(wildcardRule, adsl.getMetaData(),
                new TokenExpander.Context(null, null, "ADSL")));

        assertEquals(List.of("W-TRT01P"), rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void substitutionIsIndependentOfHowTheValuesWereDerived()
    {
        // The same rule body, expanded once through each source, must be rewritten identically —
        // substitution is handed a token→value binding and knows nothing about `over:`.
        IDataTable ds = MockTable.of().name("ADX").col("USUBJID", "U").col("AE", "x")
                .col("AESEQ", "1").build();
        IDataTable other = MockTable.of().name("OTHER").col("AE", "y").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U").col("AESEQ", "1").build();

        CheckCondition body = new CheckConditionAll(List.of(leaf("&T&", "non_empty", null)));
        Rule viaShared = template("S", body, List.of(match("OTHER", "USUBJID")),
                sharedWith("&T&", "OTHER"));
        Rule viaDomain = template("S", body, List.of(match("OTHER", "USUBJID")),
                domainFrom("&T&", "&T&SEQ", false));

        String fromShared = expressionOf(expanded(TokenExpander.tryExpand(viaShared,
                ds.getMetaData(), ctx(ds, map("OTHER", other), null))).get(0));
        String fromDomain = expressionOf(expanded(
                TokenExpander.tryExpand(viaDomain, ds.getMetaData(), ctx(ds, map("AE", ae), null)))
                        .get(0));

        assertEquals("not empty(AE)", fromShared);
        assertEquals(fromShared, fromDomain);
    }


    @Test
    void aMergeKeyAbsentFromTheDatasetUnderValidationDropsTheExpansion()
    {
        // The join key is missing from the PRIMARY, not the joined side. RuleRunner still registers
        // the lookup, so every primary row fails to match and the absence-shaped Check
        // (var_exists(AE.AESEQ) and empty(AE.AESEQ)) would fire on EVERY RECORD. PMDA-AD0258 is
        // insulated by its structure/subclass scope; CDISC-AD0898 ships Domains: Include: [ALL].
        IDataTable adae = MockTable.of().name("ADAE").col("USUBJID", "U").col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();

        String reason = reason(TokenExpander.tryExpand(ad0898Template(), adae.getMetaData(),
                ctx(adae, map("AE", ae), List.of("AE"))));

        assertTrue(reason.contains("joins on 'STUDYID'"), reason);
        assertTrue(reason.contains("fired on every record"), reason);
    }


    @Test
    void domainFromVariableWithABlindResolverSkipsWithAReason()
    {
        // Symmetric with shared_variables: without an inventory, "the parent domain is absent" and
        // "this resolver is blind" are indistinguishable, so binding would emit a rule joining to a
        // dataset there is no evidence exists.
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();
        TokenExpander.Context blind = new TokenExpander.Context(null, libraryWith(List.of("AE")),
                "ADAE");

        String reason = reason(
                TokenExpander.tryExpand(ad0898Template(), adae.getMetaData(), blind));

        assertTrue(reason.contains("no inventory-capable dataset resolver"), reason);
    }


    @Test
    void columnMatchingHonoursTheEngineDefaultOfCaseInsensitiveNames()
    {
        // DataTableMeta.columnNameCaseSensitive defaults to false and getColumnIndex honours it, so
        // a submission whose ADSL is read with different casing from its ADAE must still expand.
        // Flattening the foreign metas into an exact-match name set would silently skip it.
        IDataTable adae = MockTable.of().name("ADAE").caseInsensitiveColumnNames()
                .col("STUDYID", "S").col("USUBJID", "U").col("AGE", "50").build();
        IDataTable adsl = MockTable.of().name("ADSL").caseInsensitiveColumnNames()
                .col("studyid", "S").col("usubjid", "U").col("age", "51").build();

        List<Rule> rules = expanded(TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(List.of("CDISC-AD0591-AGE"), rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void twoDirectivesProduceTheCrossProduct()
    {
        IDataTable ds = MockTable.of().name("ADX").col("USUBJID", "U").col("AGE", "1")
                .col("SEX", "M").col("AESEQ", "1").col("CMSEQ", "2").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("AGE", "1").col("SEX", "F").build();
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1").build();
        IDataTable cm = MockTable.of().name("CM").col("CMSEQ", "2").build();

        Rule rule = template("X",
                new CheckConditionAll(List.of(leaf("&V&", "non_empty", null),
                        leaf("&D&.&D&SEQ", "var_exists", null))),
                null, sharedWith("&V&", "ADSL"), domainFrom("&D&", "&D&SEQ", false));

        List<Rule> rules = expanded(TokenExpander.tryExpand(rule, ds.getMetaData(),
                ctx(ds, map("ADSL", adsl, "AE", ae, "CM", cm), null)));

        assertEquals(List.of("X-AGE-AESEQ", "X-AGE-CMSEQ", "X-SEX-AESEQ", "X-SEX-CMSEQ"),
                rules.stream().map(Rule::effectiveId).toList());
    }


    @Test
    void aTokenSurvivingSubstitutionMeansTheExpansionIsDroppedNotEvaluated()
    {
        // Scope is carried over verbatim (a token is barred from Scope.Variables at load, and a
        // token in Scope.Domains would have made the domain gate skip the rule before expansion
        // anyway). So a token there is an unsubstituted position — the post-expansion assertion
        // must drop that rule rather than evaluate a Check against a column that cannot exist.
        IDataTable ds = MockTable.of().name("ADX").col("USUBJID", "U").col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1").build();
        Rule rule = template("G",
                new CheckConditionAll(List.of(leaf("&D&.&D&SEQ", "var_exists", null))), null,
                domainFrom("&D&", "&D&SEQ", false));
        rule.setDescription("mentions &D& and &D&SEQ");

        // Control: without the unsubstituted position the expansion is produced and the free text
        // is rewritten too.
        List<Rule> ok = expanded(
                TokenExpander.tryExpand(rule, ds.getMetaData(), ctx(ds, map("AE", ae), null)));
        assertEquals("mentions AE and AESEQ", ok.get(0).getDescription());

        net.cumba.corej.core.model.Scope scope = new net.cumba.corej.core.model.Scope();
        net.cumba.corej.core.model.DomainScope domains = new net.cumba.corej.core.model.DomainScope();
        domains.setInclude(List.of("&D&"));
        scope.setDomains(domains);
        rule.setScope(scope);

        String reason = reason(
                TokenExpander.tryExpand(rule, ds.getMetaData(), ctx(ds, map("AE", ae), null)));
        assertTrue(reason.contains("still carries the token '&D&'"), reason);
    }


    /**
     * {@code PLAN-expansion-token-delimiters} S7 — the survivor check scans for ANY complete token,
     * not only the tuple's declared ones, and its drop reason is recorded rather than only logged.
     * Data can carry a column literally named {@code X&A&} (xlsx/csv) under {@code all_variables},
     * and a rule built past the loader can carry an undeclared token anywhere.
     */
    @Test
    void anUndeclaredCompleteTokenSurvivingSubstitutionDropsTheRuleWithAReason()
    {
        IDataTable ds = MockTable.of().name("ADX").col("USUBJID", "U").col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1").build();
        Rule rule = template("H",
                new CheckConditionAll(List.of(leaf("&D&.&D&SEQ", "var_exists", null))), null,
                domainFrom("&D&", "&D&SEQ", false));
        rule.setDescription("mentions &D& and an undeclared &Z&");

        String reason = reason(
                TokenExpander.tryExpand(rule, ds.getMetaData(), ctx(ds, map("AE", ae), null)));

        assertTrue(reason.contains("still carries the token '&Z&'"), reason);
        assertTrue(reason.contains("H-AESEQ"), reason);
    }


    /**
     * Review (b) M1 of {@code PLAN-expansion-token-delimiters} — a PARTIAL drop keeps its reason:
     * one tuple survives, one carries a token after substitution (a column literally named
     * {@code X&A&}), and the result is {@code Expanded} with the survivor in {@code rules} and the
     * drop's reason in {@code dropped}, for {@code DatasetRuleResolver} to audit. Before this the
     * reason was a log line and the audit showed a clean expansion.
     */
    @Test
    void aPartialDropCarriesTheDroppedExpansionsReason()
    {
        IDataTable ds = MockTable.of().name("ADX").col("AGE", "1").col("X&A&", "2").build();
        ExpansionDirective all = new ExpansionDirective();
        all.setToken("&V&");
        all.setOverJson(ExpansionSource.ALL_VARIABLES.getJsonValue());
        Rule rule = template("P", new CheckConditionAll(List.of(leaf("&V&", "non_empty", null))),
                null, all);

        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(rule, ds.getMetaData(),
                ctx(ds, map(), null));

        WildcardExpander.ExpansionResult.Expanded expanded = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result, result.toString());
        assertEquals(List.of("P-AGE"), expanded.rules().stream().map(Rule::effectiveId).toList());
        assertEquals(List.of("Expansion P-X&A& still carries the token '&A&' after substitution"
                + " — dropped, not evaluated"), expanded.dropped());
    }


    /**
     * The other post-build drop, a {@code Match_Datasets} key the dataset under validation does not
     * carry, travels on the same channel: {@code &D&ID} resolves to {@code AEID} (present) and
     * {@code CMID} (absent), so the CM expansion is dropped with its reason and the AE one runs.
     */
    @Test
    void aPartiallyUnjoinableExpansionCarriesTheDroppedExpansionsReason()
    {
        IDataTable ds = MockTable.of().name("ADX").col("USUBJID", "U").col("AESEQ", "1")
                .col("CMSEQ", "1").col("AEID", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U").col("AEID", "1").build();
        IDataTable cm = MockTable.of().name("CM").col("USUBJID", "U").col("CMID", "1").build();
        Rule rule = template("J",
                new CheckConditionAll(List.of(leaf("&D&.&D&ID", "var_exists", null))),
                List.of(match("&D&", "USUBJID", "&D&ID")), domainFrom("&D&", "&D&SEQ", false));

        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(rule, ds.getMetaData(),
                ctx(ds, map("AE", ae, "CM", cm), null));

        WildcardExpander.ExpansionResult.Expanded expanded = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result, result.toString());
        assertEquals(List.of("J-AESEQ"), expanded.rules().stream().map(Rule::effectiveId).toList());
        assertEquals(
                List.of("Expansion J-CMSEQ joins on 'CMID', which the dataset under"
                        + " validation does not carry — dropped rather than fired on every record"),
                expanded.dropped());
    }


    /** A total drop still joins every reason into the NoMatch audit reason. */
    @Test
    void aTotalDropJoinsEveryReasonIntoTheNoMatchReason()
    {
        IDataTable ds = MockTable.of().name("ADX").col("X&A&", "2").col("Y&B&", "3").build();
        ExpansionDirective all = new ExpansionDirective();
        all.setToken("&V&");
        all.setOverJson(ExpansionSource.ALL_VARIABLES.getJsonValue());
        Rule rule = template("T", new CheckConditionAll(List.of(leaf("&V&", "non_empty", null))),
                null, all);

        String reason = reason(
                TokenExpander.tryExpand(rule, ds.getMetaData(), ctx(ds, map(), null)));

        assertTrue(reason.startsWith("Expansion produced no usable rule ("), reason);
        assertTrue(reason.contains("Expansion T-X&A& still carries the token '&A&'"), reason);
        assertTrue(reason.contains("Expansion T-Y&B& still carries the token '&B&'"), reason);
        assertTrue(reason.endsWith("); not expanded for"), reason);
    }


    /**
     * {@code PLAN-expansion-token-delimiters} D1/S8 — the bare template form and the backtick form
     * are the same template: both expand to the identical concrete rule.
     */
    @Test
    void theBareAndBacktickTemplateFormsExpandToTheSameConcreteRule()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AESEQ", "1").build();
        String bare = "var_exists(&DOM&.&DOM&SEQ) and empty(&DOM&.&DOM&SEQ)";
        String quoted = "var_exists(`&DOM&.&DOM&SEQ`) and empty(`&DOM&.&DOM&SEQ`)";

        List<String> printed = new java.util.ArrayList<>();
        for (String source : List.of(bare, quoted))
        {
            Rule rule = template("X",
                    new net.cumba.corej.core.model.CheckConditionExpression(
                            net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source),
                    List.of(match("&DOM&", "STUDYID", "USUBJID", "&DOM&SEQ")),
                    domainFrom("&DOM&", "&DOM&SEQ", true));
            List<Rule> rules = expanded(TokenExpander.tryExpand(rule, adae.getMetaData(),
                    ctx(adae, map("AE", ae), List.of("AE"))));
            assertEquals(List.of("X-AESEQ"), rules.stream().map(Rule::effectiveId).toList());
            printed.add(net.cumba.corej.core.expr.ExpressionPrinter.print(
                    ((net.cumba.corej.core.model.CheckConditionExpression) rules.get(0).getCheck())
                            .expr()));
            assertNotNull(rules.get(0).getMatchDatasets());
            assertEquals("AE", rules.get(0).getMatchDatasets().get(0).getName());
            assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"),
                    rules.get(0).getMatchDatasets().get(0).getKeys());
        }
        assertEquals("var_exists(AE.AESEQ) and empty(AE.AESEQ)", printed.get(0));
        assertEquals(printed.get(0), printed.get(1));
    }

}
