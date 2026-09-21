package net.cumba.corej.ruletest.cdt.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetRuleResolver;
import net.cumba.corej.core.gen.GeneratedRulePackage;
import net.cumba.corej.core.gen.SkippedSourceRule;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.corej.ruletest.cdt.CdtLoader;
import net.cumba.corej.ruletest.cdt.ruletest.MapBackedLibraryMetadataProvider;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code :N} / {@code :C} type suffix on a {@code Requirements.Variables} entry, driven from
 * <b>real dataset files</b> through the production generation path
 * ({@code plans/PLAN-variable-type-requirements.md} phase 5).
 *
 * <h2>Why this class exists beside the unit tests</h2>
 *
 * <p>
 * {@code corej-core}'s {@code ScopeMatcherTypeRequirementTest} pins the matcher against hand-built
 * {@code DataTableMeta}. That proves the comparison and nothing about the step before it: that a
 * column's type <em>survives</em> the loader and reaches {@link DatasetRuleResolver#generate}. This
 * class runs the whole of the generator over loaded tables, so a verdict here means the requirement
 * was decided against the data the engine actually holds.
 * </p>
 *
 * <h2>Why the fixtures come in pairs</h2>
 *
 * <p>
 * {@code AETYPED} and {@code AESWAPPED} carry the SAME column names with the OPPOSITE types. An
 * implementation that answered off the column name rather than its type would give both the same
 * verdict, and a fixture set containing only one of them could not tell that apart from a correct
 * one — the self-confirming-fixture shape this codebase already pays for elsewhere.
 * </p>
 *
 * <p>
 * ⛔⛔ The fixture declares only {@code type=Char} and {@code type=Num}, and that constraint is not
 * cosmetic: {@code CdtValues.toDataValueType} maps {@code NUM}, {@code DATE}, {@code TIME} and
 * {@code DATETIME} all to {@code DataValueType.DOUBLE}, so a {@code type=Date} column reads here as
 * NUMERIC — while a real ISO-8601 {@code --DTC} column arrives from every provider as
 * {@code STRING}, i.e. CHARACTER. A date column in this file would test a kind production never
 * produces (finding F1 of the plan, §5.6).
 * </p>
 *
 * <p>
 * The rules are synthetic on purpose. The contract under test is the engine's, and no shipped rule
 * carries a type suffix — by design, since adopting one is a per-rule authoring decision under
 * ruling D4.
 * </p>
 */
class TypedVariableRequirementFromDataTest
{

    private static final String FIXTURES = "net/cumba/corej/ruletest/type_fixtures/";

    private static final String RULE_ID = "TEST-TYPED-REQ";

    private static Map<String, OverlayDataTable> datasets;

    @BeforeAll
    static void loadFixtures() throws IOException
    {
        datasets = new LinkedHashMap<>();
        for (OverlayDataTable table : CdtLoader.loadAllResource(FIXTURES + "typed-columns.cdt"))
        {
            String name = table.getMetaData().getName();
            assertNotNull(name, "fixture dataset has no name");
            assertNull(datasets.put(name.toUpperCase(Locale.ROOT), table),
                    "duplicate fixture dataset " + name);
        }
        assertEquals(2, datasets.size(),
                "fixture inventory changed — update the expectations in this class");
    }

    // ------------------------------------------------------------------
    // the fixture itself, asserted before anything is concluded from it
    // ------------------------------------------------------------------


    /**
     * ⚠ A non-vacuity control. Every verdict below is a claim about the fixture's column types, so
     * a fixture whose types silently failed to load would make the whole class pass by answering
     * "not decidable" everywhere (ruling D2 never blocks). This asserts the types are really there
     * and really opposite.
     */
    @Test
    void theFixturesCarryOppositeTypesAndTheLoaderKeptThem()
    {
        assertEquals(net.cumba.datatable.values.DataValueType.DOUBLE, typeOf("AETYPED", "AESEQ"),
                "AETYPED.AESEQ must load as numeric");
        assertEquals(net.cumba.datatable.values.DataValueType.STRING, typeOf("AETYPED", "AETERM"),
                "AETYPED.AETERM must load as character");
        assertEquals(net.cumba.datatable.values.DataValueType.STRING, typeOf("AESWAPPED", "AESEQ"),
                "AESWAPPED.AESEQ must load as character");
        assertEquals(net.cumba.datatable.values.DataValueType.DOUBLE, typeOf("AESWAPPED", "AETERM"),
                "AESWAPPED.AETERM must load as numeric");
    }

    // ------------------------------------------------------------------
    // the requirement, through the generator
    // ------------------------------------------------------------------


    @Test
    void aConformingDatasetKeepsTheRule()
    {
        assertEquals(RULE_ID, generatedIdOrNull("AESEQ:N", "AETYPED"));
        assertEquals(RULE_ID, generatedIdOrNull("AETERM:C", "AETYPED"));
    }


    @Test
    void aWronglyTypedColumnSkipsTheRuleAndTheReasonNamesBothTypes()
    {
        assertNull(generatedIdOrNull("AESEQ:N", "AESWAPPED"),
                "a character AESEQ must not satisfy AESEQ:N");
        String reason = skipReason("AESEQ:N", "AESWAPPED");
        assertNotNull(reason, "the generator must record why it dropped the rule");
        assertTrue(reason.contains("AESEQ:N"), "the reason names the entry as authored: " + reason);
        assertTrue(reason.contains("required to be Numeric"), reason);
        assertTrue(reason.contains("but is Character"), reason);
    }


    @Test
    void theMirrorDirectionSkipsToo()
    {
        assertNull(generatedIdOrNull("AETERM:C", "AESWAPPED"));
        String reason = skipReason("AETERM:C", "AESWAPPED");
        assertNotNull(reason);
        assertTrue(reason.contains("required to be Character"), reason);
        assertTrue(reason.contains("but is Numeric"), reason);
    }


    /**
     * ⭐⭐ Ruling D5 through the generator: an UNTAGGED entry keeps the rule on both datasets,
     * whatever the column's type. This is the whole backwards-compatibility promise, measured on
     * the production path rather than argued.
     */
    @Test
    void anUntaggedEntryIsIndifferentToTheType()
    {
        assertEquals(RULE_ID, generatedIdOrNull("AESEQ", "AETYPED"));
        assertEquals(RULE_ID, generatedIdOrNull("AESEQ", "AESWAPPED"));
        assertEquals(RULE_ID, generatedIdOrNull("AETERM", "AETYPED"));
        assertEquals(RULE_ID, generatedIdOrNull("AETERM", "AESWAPPED"));
    }


    /** An absent column still reports ABSENCE, never a type — presence is decided first. */
    @Test
    void anAbsentColumnStillReportsAbsence()
    {
        String reason = skipReason("NOSUCH:N", "AETYPED");
        assertNotNull(reason);
        assertTrue(reason.contains("not present"), reason);
        assertTrue(!reason.contains("required to be"), reason);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------


    private static net.cumba.datatable.values.DataValueType typeOf(String dataset, String column)
    {
        OverlayDataTable table = dataset(dataset);
        return table.getMetaData().getColumn(table.getMetaData().getColumnIndex(column)).getType();
    }


    private static OverlayDataTable dataset(String name)
    {
        OverlayDataTable table = datasets.get(name.toUpperCase(Locale.ROOT));
        assertNotNull(table, "no fixture dataset called " + name);
        return table;
    }


    private static Rule requiring(String entry)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(RULE_ID);
        rule.setCore(core);
        rule.setDescription("USUBJID must be present");
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse("var_exists(\"USUBJID\")"),
                "var_exists(\"USUBJID\")"));
        Outcome outcome = new Outcome();
        outcome.setMessage("USUBJID is missing");
        rule.setOutcome(outcome);
        VariableRequirement vars = new VariableRequirement();
        vars.setAll(List.of(entry));
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    private static GeneratedRulePackage generateFor(String entry, String datasetName)
    {
        DatasetRuleResolver generator = new DatasetRuleResolver(
                MapBackedLibraryMetadataProvider.empty());
        generator.setStaticRules(List.of(requiring(entry)));
        return generator.generate(dataset(datasetName));
    }


    private static @Nullable String generatedIdOrNull(String entry, String datasetName)
    {
        return generateFor(entry, datasetName).getRules().stream()
                .map(r -> r.getCore() != null ? r.getCore().getId() : null).filter(RULE_ID::equals)
                .findFirst().orElse(null);
    }


    private static @Nullable String skipReason(String entry, String datasetName)
    {
        return generateFor(entry, datasetName).getSkippedSourceRules().stream()
                .filter(s -> RULE_ID.equals(s.rule().getCore().getId()))
                .map(SkippedSourceRule::reason).findFirst().orElse(null);
    }

}
