package net.cumba.dataviewer.examples.cdt.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.cdisc.core.exec.OperationExecutor;
import net.cumba.cdisc.core.exec.ScopeMatcher;
import net.cumba.cdisc.core.gen.GeneratedRulePackage;
import net.cumba.cdisc.core.gen.RuleCategory;
import net.cumba.cdisc.core.gen.RuleGenerator;
import net.cumba.cdisc.core.gen.SkippedSourceRule;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.dataviewer.examples.cdt.CdtLoader;
import net.cumba.dataviewer.examples.cdt.ruletest.MapBackedLibraryMetadataProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Domain-scope matching for {@code SUPP--} / {@code SQ--} / {@code AP--} / {@code APFA--} driven
 * from <b>real dataset files</b> rather than hand-passed strings.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>
 * {@code Fix #160} deleted the SUPP/AP family wildcard and made the four tokens independent and
 * strict. It shipped materially unexercised: {@code /data/testdata} carries <b>zero</b> {@code AP*}
 * and <b>zero</b> {@code SQ*} datasets (control: 24 {@code SUPP*}, including the split forms
 * {@code supplbch}/{@code supplbhe}/{@code supplbur}), and the existing unit coverage —
 * {@code ScopeMatcherSuppApFamilyTest} in {@code cumba-oss-cdisc-core} — hands
 * {@link ScopeMatcher#describeDomainMismatch(Rule, String, String)} its {@code unsplitName}
 * argument as a <em>string literal</em>. That pins the matcher but proves nothing about the step
 * before it: that a real {@code SUPPLBHM} dataset actually <em>yields</em> {@code SUPPLB} from its
 * own columns.
 * </p>
 *
 * <p>
 * The datasets in {@code net/cumba/dataviewer/scope_fixtures/} close that gap. Every assertion
 * below runs the production two-step exactly as {@code RuleGenerator.doGenerate} does —
 * {@link OperationExecutor#unsplitNameFromData} on the loaded table, then
 * {@code ScopeMatcher.describeDomainMismatch(rule, name, base)} — and
 * {@link #ruleGeneratorSelectsByFamily()} drives the whole of {@link RuleGenerator} so the
 * selection path (rule emitted vs. rule skipped, with its reason) is covered too.
 * </p>
 *
 * <h2>The rules here are synthetic, deliberately</h2>
 *
 * <p>
 * Nothing in this class reads the shipped corpus. The contract under test is the engine's, not any
 * individual rule's, and a corpus-coupled test would go red every time a lane re-scopes an
 * {@code AP--} rule — which is precisely the maintenance debt that let this behaviour ship
 * unexercised in the first place.
 * </p>
 */
class ApSqDomainScopeFromDataTest
{

    private static final String FIXTURES = "net/cumba/dataviewer/scope_fixtures/";

    /** Every fixture dataset, keyed by its dataset name. */
    private static Map<String, OverlayDataTable> datasets;

    @BeforeAll
    static void loadFixtures() throws IOException
    {
        datasets = new LinkedHashMap<>();
        for (String file : List.of("supp-family.cdt", "sq-family.cdt", "ap-family.cdt"))
        {
            for (OverlayDataTable table : CdtLoader.loadAllResource(FIXTURES + file))
            {
                String name = table.getMetaData().getName();
                assertNotNull(name, "fixture " + file + " has an unnamed dataset");
                assertNull(datasets.put(name.toUpperCase(Locale.ROOT), table),
                        "duplicate fixture dataset " + name);
            }
        }
        assertEquals(10, datasets.size(),
                "fixture inventory changed — update the expectations in this class");
    }


    private static OverlayDataTable dataset(String aName)
    {
        OverlayDataTable table = datasets.get(aName.toUpperCase(Locale.ROOT));
        assertNotNull(table, "no fixture dataset named " + aName);
        return table;
    }


    /** The dataset's canonical base name, derived the way production derives it. */
    private static String base(String aName)
    {
        return OperationExecutor.unsplitNameFromData(dataset(aName));
    }


    /**
     * The production two-step: the dataset's own name plus its <em>data-derived</em> base, exactly
     * as {@code RuleGenerator.doGenerate} assembles them.
     */
    private static String mismatch(Rule aRule, String aDataset)
    {
        return ScopeMatcher.describeDomainMismatch(aRule, aDataset, base(aDataset));
    }


    private static void assertInScope(Rule aRule, String aDataset, String aWhy)
    {
        assertNull(mismatch(aRule, aDataset), aWhy);
    }


    private static void assertOutOfScope(Rule aRule, String aDataset, String aWhy)
    {
        assertNotNull(mismatch(aRule, aDataset), aWhy);
    }


    private static Rule scoped(List<String> aInclude, List<String> aExclude)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-APSQ-SCOPE");
        rule.setCore(core);
        rule.setDescription("STUDYID must be present");
        rule.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        Outcome outcome = new Outcome();
        outcome.setMessage("STUDYID is missing");
        rule.setOutcome(outcome);
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        if (aInclude != null)
        {
            domains.setInclude(aInclude);
        }
        if (aExclude != null)
        {
            domains.setExclude(aExclude);
        }
        scope.setDomains(domains);
        rule.setScope(scope);
        return rule;
    }

    // ------------------------------------------------------------------------------------
    // 1. The base comes from the DATA
    // ------------------------------------------------------------------------------------


    /**
     * The link the string-literal unit tests cannot make. {@code SUPP}/{@code SQ} datasets carry
     * {@code RDOMAIN} and no {@code DOMAIN} column, so the base is {@code "SUPP"}/{@code "SQ"} plus
     * the {@code RDOMAIN} cell; {@code AP} datasets carry {@code DOMAIN}, so the base is that cell
     * verbatim.
     *
     * <p>
     * ⚠ {@code SUPPLBHM} is the case that separates this path from the table-less fallback: the
     * name-only heuristic strips one letter and yields the 7-character {@code SUPPLBH}, which
     * strict {@code SUPP--} misses. Only the data gives {@code SUPPLB}.
     * </p>
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
    {
            "SUPPLB,SUPPLB", "SUPPLBHM,SUPPLB", "SUPPAPFAMH,SUPPAPFA", "SQLB,SQLB", "SQLBHM,SQLB",
            "SQAPFAMH,SQAPFA", "APMH,APMH", "APMH1,APMH", "APFAMH,APFA", "APRELSUB,APRELSUB"
    })
    void unsplitNameIsDerivedFromTheDataset(String aDataset, String aExpectedBase)
    {
        assertEquals(aExpectedBase, base(aDataset));
    }


    /**
     * The positive control for the assertion above: the name-only overload disagrees on the split
     * SUPP form, so a test that accidentally used it would be measuring the wrong thing.
     */
    @Test
    void theNameOnlyHeuristicDisagreesOnTheSplitSuppForm()
    {
        Rule supp = scoped(null, List.of("SUPP--"));
        assertNull(ScopeMatcher.describeDomainMismatch(supp, "SUPPLBHM"),
                "the 2-arg overload strips one letter (SUPPLBH, 7 chars) and misses SUPP--");
        assertOutOfScope(supp, "SUPPLBHM",
                "the data-derived base SUPPLB (6 chars) is what SUPP-- excludes");
    }

    // ------------------------------------------------------------------------------------
    // 2. Each token reaches its own family, at both the canonical and the split length
    // ------------------------------------------------------------------------------------


    @Test
    void suppTokenReachesItsOwnFamily()
    {
        Rule include = scoped(List.of("SUPP--"), null);
        assertInScope(include, "SUPPLB", "SUPPLB is 6 characters — strict SUPP-- matches the name");
        assertInScope(include, "SUPPLBHM", "split SUPPLBHM resolves to the base SUPPLB");
    }


    @Test
    void sqTokenReachesItsOwnFamily()
    {
        Rule include = scoped(List.of("SQ--"), null);
        assertInScope(include, "SQLB", "SQLB is 4 characters — strict SQ-- matches the name");
        assertInScope(include, "SQLBHM", "split SQLBHM resolves to the base SQLB");
    }


    @Test
    void apTokenReachesItsOwnFamily()
    {
        Rule include = scoped(List.of("AP--"), null);
        assertInScope(include, "APMH", "APMH is 4 characters — strict AP-- matches the name");
        assertInScope(include, "APMH1", "split APMH1 carries DOMAIN=APMH, so the base is APMH");
    }


    /**
     * One dataset, two tokens, two different mechanisms: {@code APFA--} matches the 6-character
     * <em>name</em> directly, {@code AP--} matches the 4-character <em>data-derived base</em> via
     * the split re-test. Neither is a {@code startsWith} test.
     */
    @Test
    void apfaDatasetIsReachedByBothApfaAndApButByDifferentRoutes()
    {
        assertInScope(scoped(List.of("APFA--"), null), "APFAMH",
                "APFA-- is APFA plus exactly two characters — the NAME APFAMH");
        assertInScope(scoped(List.of("AP--"), null), "APFAMH",
                "AP-- reaches APFAMH only through its data-derived base APFA");
        assertEquals("APFA", base("APFAMH"));
    }

    // ------------------------------------------------------------------------------------
    // 3. ⚑ Cross-family reach must NOT work — this is what Fix #160 deleted
    // ------------------------------------------------------------------------------------


    /**
     * Under the deleted {@code firstMatchingSuppApFamilyEntry} leg, <em>any</em> of the four tokens
     * matched <em>any</em> dataset whose name began with {@code SUPP}/{@code SQ}/{@code AP}, at any
     * length. Each assertion below is one of the pairings that leg silently created.
     */
    @Test
    void includeApDashDashDoesNotReachSuppOrSq()
    {
        Rule include = scoped(List.of("AP--"), null);
        assertOutOfScope(include, "SUPPLB", "Include:[AP--] must not select a SUPP dataset");
        assertOutOfScope(include, "SUPPLBHM", "…nor its split form");
        assertOutOfScope(include, "SQLB", "Include:[AP--] must not select an SQ dataset");
    }


    @Test
    void excludeSuppDashDashDoesNotExcludeApOrSq()
    {
        Rule exclude = scoped(null, List.of("SUPP--"));
        assertInScope(exclude, "APMH", "Exclude:[SUPP--] must not exclude an AP dataset");
        assertInScope(exclude, "APMH1", "…nor its split form");
        assertInScope(exclude, "APRELSUB", "…nor the 8-character AP dataset");
        assertInScope(exclude, "SQLB", "Exclude:[SUPP--] must not exclude an SQ dataset");
        assertInScope(exclude, "SQLBHM", "…nor its split form");
    }


    @Test
    void excludeSqDashDashDoesNotExcludeSuppOrAp()
    {
        Rule exclude = scoped(null, List.of("SQ--"));
        assertInScope(exclude, "SUPPLB", "Exclude:[SQ--] must not exclude a SUPP dataset");
        assertInScope(exclude, "APMH", "Exclude:[SQ--] must not exclude an AP dataset");
    }


    @Test
    void includeSuppDashDashDoesNotReachApOrSq()
    {
        Rule include = scoped(List.of("SUPP--"), null);
        assertOutOfScope(include, "APMH", "Include:[SUPP--] must not select an AP dataset");
        assertOutOfScope(include, "APFAMH", "…nor an AP findings-about dataset");
        assertOutOfScope(include, "SQLB", "Include:[SUPP--] must not select an SQ dataset");
    }


    /**
     * {@code APFA--} is an ordinary wildcard once the family set is gone: it reaches neither the
     * plain AP datasets nor anything under SUPP/SQ.
     */
    @Test
    void apfaTokenIsAnOrdinaryWildcard()
    {
        Rule include = scoped(List.of("APFA--"), null);
        assertOutOfScope(include, "APMH", "APFA-- needs the APFA prefix, and APMH has AP+MH");
        assertOutOfScope(include, "APRELSUB", "APFA-- is APFA plus exactly two characters");
        assertOutOfScope(include, "SUPPAPFAMH",
                "APFA-- must not reach a SUPPQUAL dataset that merely mentions APFA");
    }

    // ------------------------------------------------------------------------------------
    // 4. The two shapes no strict token can express — recorded, not repaired
    // ------------------------------------------------------------------------------------


    /**
     * {@code SUPPAPFAMH} resolves to the 8-character base {@code SUPPAPFA}, which strict
     * {@code SUPP--} cannot match — by design, because <b>SDTMIG v3.4 §8.4.2</b> requires exactly
     * this dataset to be renamed {@code SQAPFAMH}.
     *
     * <p>
     * ⚠⚠ …and the rename does <em>not</em> restore reachability: {@code SQAPFAMH} resolves to the
     * 6-character base {@code SQAPFA}, which strict {@code SQ--} (SQ plus exactly two) also misses.
     * The standard's remedy moves which token fails; it does not close the hole. That is the open
     * discussion recorded as W4, and this test is what will go red when it is resolved.
     * </p>
     */
    @Test
    void neitherSuppApfaNorItsSdtmigRenameIsReachableByAStrictToken()
    {
        assertOutOfScope(scoped(List.of("SUPP--"), null), "SUPPAPFAMH",
                "base SUPPAPFA is 8 characters — strict SUPP-- cannot reach it");
        assertOutOfScope(scoped(List.of("SQ--"), null), "SQAPFAMH",
                "⚠ W4: base SQAPFA is 6 characters — the SDTMIG rename is not reachable either");
        assertEquals("SUPPAPFA", base("SUPPAPFAMH"));
        assertEquals("SQAPFA", base("SQAPFAMH"));
    }


    /**
     * {@code APRELSUB} is a real, conformant 8-character AP dataset that strict {@code AP--} cannot
     * reach. The corpus answer is the <b>literal</b> token, never an {@code AP*} glob — so both
     * halves are pinned here: {@code AP--} misses it, the literal reaches it.
     */
    @Test
    void aprelsubNeedsTheLiteralTokenNotApDashDash()
    {
        assertOutOfScope(scoped(List.of("AP--"), null), "APRELSUB",
                "AP-- is AP plus exactly two characters; APRELSUB is eight");
        assertInScope(scoped(List.of("AP--", "APRELSUB"), null), "APRELSUB",
                "the literal APRELSUB is what puts it back in scope");
        assertOutOfScope(scoped(null, List.of("AP--", "APRELSUB")), "APRELSUB",
                "and the same literal is what excludes it on the Exclude side");
        assertInScope(scoped(null, List.of("AP--", "APRELSUB")), "SUPPLB",
                "adding the literal must not widen the rule beyond the AP family");
    }

    // ------------------------------------------------------------------------------------
    // 5. End-to-end through RuleGenerator — the production selection path
    // ------------------------------------------------------------------------------------


    /**
     * Everything above calls {@link ScopeMatcher} directly with production's inputs. This test
     * instead hands the loaded table to {@link RuleGenerator} and asserts on what the generator
     * emitted versus what it skipped and why — so the wiring itself ({@code doGenerate} →
     * {@code unsplitNameFromData} → {@code describeScopeSkip}) is covered, not merely the matcher
     * it calls.
     */
    @Test
    void ruleGeneratorSelectsByFamily()
    {
        assertEquals("TEST-APSQ-SCOPE", generatedIdOrNull("AP--", "APMH1"),
                "an AP---scoped rule must be emitted for the split AP dataset");
        assertNull(generatedIdOrNull("AP--", "SUPPLBHM"),
                "an AP---scoped rule must NOT be emitted for a SUPP dataset");
        assertEquals("TEST-APSQ-SCOPE", generatedIdOrNull("SUPP--", "SUPPLBHM"),
                "a SUPP---scoped rule must be emitted for the split SUPP dataset");
        assertEquals("TEST-APSQ-SCOPE", generatedIdOrNull("SQ--", "SQLBHM"),
                "an SQ---scoped rule must be emitted for the split SQ dataset");
        assertNull(generatedIdOrNull("SQ--", "APMH"),
                "an SQ---scoped rule must NOT be emitted for an AP dataset");

        // …and the skip carries the reason, naming the responsible scope entry.
        String reason = skipReason("AP--", "SUPPLB");
        assertNotNull(reason, "the generator must record why it dropped the rule");
        assertTrue(reason.contains("SUPPLB") && reason.contains("AP--"),
                "skip reason should name the dataset and the scope entry, was: " + reason);
    }


    /**
     * ⚠ {@code SDTM_PREFIX_EXPANSION} is not optional here even though nothing in this class uses a
     * {@code --} variable prefix: {@code RuleGenerator} drains {@code scopedStaticRules} into the
     * output <em>inside</em> that category's guard, so with the category disabled a static rule is
     * scope-matched and then silently dropped. Every other category stays off so the package holds
     * exactly the rule under test.
     */
    private static GeneratedRulePackage generateFor(String aIncludeToken, String aDataset)
    {
        RuleGenerator generator = new RuleGenerator(MapBackedLibraryMetadataProvider.empty(), null,
                null, null, EnumSet.of(RuleCategory.SDTM_PREFIX_EXPANSION));
        generator.setStaticRules(List.of(scoped(List.of(aIncludeToken), null)));
        return generator.generate(dataset(aDataset));
    }


    private static String generatedIdOrNull(String aIncludeToken, String aDataset)
    {
        return generateFor(aIncludeToken, aDataset).getRules().stream()
                .map(r -> r.getCore() != null ? r.getCore().getId() : null)
                .filter("TEST-APSQ-SCOPE"::equals).findFirst().orElse(null);
    }


    private static String skipReason(String aIncludeToken, String aDataset)
    {
        return generateFor(aIncludeToken, aDataset).getSkippedSourceRules().stream()
                .filter(s -> "TEST-APSQ-SCOPE".equals(s.rule().getCore().getId()))
                .map(SkippedSourceRule::reason).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------------------------
    // 6. Guards on the fixtures themselves — a fixture that cannot fail proves nothing
    // ------------------------------------------------------------------------------------


    /**
     * The SUPP/SQ datasets must carry {@code RDOMAIN} and <em>no</em> {@code DOMAIN} column, and
     * the AP datasets the reverse. If that ever inverts,
     * {@link OperationExecutor#unsplitNameFromData} takes the other branch and every expectation
     * above silently starts measuring something else.
     */
    @Test
    void fixturesExerciseBothBranchesOfTheBaseDerivation()
    {
        for (String name : List.of("SUPPLB", "SUPPLBHM", "SUPPAPFAMH", "SQLB", "SQLBHM",
                "SQAPFAMH"))
        {
            assertTrue(dataset(name).getMetaData().getColumnIndex("RDOMAIN") >= 0,
                    name + " must carry RDOMAIN");
            assertTrue(dataset(name).getMetaData().getColumnIndex("DOMAIN") < 0,
                    name + " must NOT carry a DOMAIN column — that is the SUPP/SQ branch");
        }
        for (String name : List.of("APMH", "APMH1", "APFAMH", "APRELSUB"))
        {
            assertTrue(dataset(name).getMetaData().getColumnIndex("DOMAIN") >= 0,
                    name + " must carry DOMAIN — that is the AP branch");
        }
    }


    /**
     * Every fixture has rows. {@link OperationExecutor#unsplitNameFromData} falls back to the bare
     * dataset name when the table is empty, which would make the SUPP/SQ expectations pass for the
     * wrong reason.
     */
    @Test
    void everyFixtureHasRows()
    {
        datasets.forEach((name, table) -> assertFalse(table.getRowCount() == 0,
                name + " has no rows — the base derivation would fall back to the name"));
    }

}
