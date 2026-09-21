package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.MetadataNormalizer;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code plans/PLAN-expansion-over-all-variables.md} — the three {@code over: all_*}
 * expansion sources.
 *
 * <p>
 * The fixture is a <b>hand-built</b> {@link DataTableMeta} rather than a loaded table, because the
 * property that matters most cannot be expressed by one: a column whose loaded type folds to
 * <em>neither</em> {@code Char} nor {@code Num}. {@code MockTable} can produce STRING / LONG /
 * DOUBLE columns and nothing else.
 * </p>
 *
 * <p>
 * ⚠⚠ The neither-bucket is pinned on {@link DataValueType#OTHER} and {@link DataValueType#MISSING}
 * deliberately: those two exist in <b>both</b> trees, while {@code COMPLEX} and {@code VARIABLE}
 * are internal-only and a fixture built on them would not compile in {@code cumba-oss-corej}.
 * </p>
 */
class AllVariablesExpansionSourceTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static DataTableColumnMeta col(int index, String name, DataValueType type)
    {
        return DataTableColumnMeta.builder().index(index).name(name).type(type).build();
    }


    /**
     * One column per interesting fold: two Char, three Num, two neither.
     */
    private static DataTableMeta fixture()
    {
        DataTableColumnMeta[] columns =
        {
                col(0, "USUBJID", DataValueType.STRING), col(1, "AETERM", DataValueType.STRING),
                col(2, "AESEQ", DataValueType.LONG), col(3, "AESTDY", DataValueType.DOUBLE),
                col(4, "AEFLAG", DataValueType.BOOLEAN), col(5, "AEUNTYPED", DataValueType.OTHER),
                col(6, "AEABSENT", DataValueType.MISSING)
        };
        return DataTableMeta.builder().name("AE").label("Adverse Events").rowCount(0)
                .totalRowCount(0).columns(columns).build();
    }


    private static ExpansionDirective over(ExpansionSource source)
    {
        ExpansionDirective d = new ExpansionDirective();
        d.setToken("&VAR");
        d.setOver(source);
        return d;
    }


    private static Rule template(ExpansionSource source)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CDISC-SEND-0049");
        rule.setCore(core);
        String src = "var_label(\"&VAR\", \"DATA\") != \"\"";
        CheckCondition check = new CheckConditionExpression(CheckExpressionParser.parse(src), src);
        rule.setCheck(check);
        rule.setExpansion(List.of(over(source)));
        return rule;
    }


    private static List<String> expandedIds(ExpansionSource source)
    {
        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(template(source),
                fixture(), new TokenExpander.Context(null, null, "AE"));
        WildcardExpander.ExpansionResult.Expanded expanded = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result,
                () -> "expected an expansion, got " + result);
        List<String> ids = new ArrayList<>();
        for (Rule r : expanded.rules())
        {
            ids.add(r.effectiveId());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // The three sources
    // ------------------------------------------------------------------


    /**
     * ⭐ Owner ruling R3: the derived id IS the reported one, and it reads as "this rule applied to
     * this variable". Pinned, because nothing downstream un-suffixes it.
     */
    @Test
    void allVariablesExpandsOncePerColumnInColumnOrder()
    {
        assertEquals(
                List.of("CDISC-SEND-0049-USUBJID", "CDISC-SEND-0049-AETERM",
                        "CDISC-SEND-0049-AESEQ", "CDISC-SEND-0049-AESTDY", "CDISC-SEND-0049-AEFLAG",
                        "CDISC-SEND-0049-AEUNTYPED", "CDISC-SEND-0049-AEABSENT"),
                expandedIds(ExpansionSource.ALL_VARIABLES));
    }


    @Test
    void allNumericVariablesTakesLongDoubleAndBoolean()
    {
        assertEquals(
                List.of("CDISC-SEND-0049-AESEQ", "CDISC-SEND-0049-AESTDY",
                        "CDISC-SEND-0049-AEFLAG"),
                expandedIds(ExpansionSource.ALL_NUMERIC_VARIABLES));
    }


    @Test
    void allCharacterVariablesTakesStringOnly()
    {
        assertEquals(List.of("CDISC-SEND-0049-USUBJID", "CDISC-SEND-0049-AETERM"),
                expandedIds(ExpansionSource.ALL_CHARACTER_VARIABLES));
    }

    // ------------------------------------------------------------------
    // The neither-bucket — the property someone will later try to "fix"
    // ------------------------------------------------------------------


    /**
     * ⚠⚠ The two filtered sources do <b>not</b> partition {@code all_variables}. {@code OTHER} and
     * {@code MISSING} are in neither, which is consistent with {@code var_type("DATA")} answering
     * missing for such a column. Asserted as a set relation rather than by naming the columns, so
     * the test states the invariant and not the fixture.
     */
    @Test
    void theTypeFilteredSourcesDoNotPartitionAllVariables()
    {
        List<String> all = expandedIds(ExpansionSource.ALL_VARIABLES);
        List<String> numeric = expandedIds(ExpansionSource.ALL_NUMERIC_VARIABLES);
        List<String> character = expandedIds(ExpansionSource.ALL_CHARACTER_VARIABLES);

        List<String> union = new ArrayList<>(numeric);
        union.addAll(character);

        assertTrue(all.containsAll(union),
                "both filtered sources must be subsets of all_variables");
        assertTrue(union.size() < all.size(),
                "the neither-bucket must be non-empty, or this fixture no longer tests anything");

        List<String> neither = new ArrayList<>(all);
        neither.removeAll(union);
        assertEquals(List.of("CDISC-SEND-0049-AEUNTYPED", "CDISC-SEND-0049-AEABSENT"), neither);
    }


    /**
     * The fold this plan must never re-derive: {@code charOrNum} is the same function that answers
     * {@code var_type("DATA")}. If these two ever disagree, an {@code all_numeric_variables}
     * expansion contradicts the accessor <em>inside the rule it expanded</em>.
     */
    @Test
    void theFoldIsTheSameOneVarTypeDataAnswers()
    {
        assertEquals("Char", MetadataNormalizer.charOrNum(DataValueType.STRING));
        assertEquals("Num", MetadataNormalizer.charOrNum(DataValueType.LONG));
        assertEquals("Num", MetadataNormalizer.charOrNum(DataValueType.DOUBLE));
        assertEquals("Num", MetadataNormalizer.charOrNum(DataValueType.BOOLEAN));
        assertEquals(null, MetadataNormalizer.charOrNum(DataValueType.OTHER));
        assertEquals(null, MetadataNormalizer.charOrNum(DataValueType.MISSING));
        assertEquals(null, MetadataNormalizer.charOrNum(null));
    }

    // ------------------------------------------------------------------
    // Substitution reaches the accessor name — phase 1 and 2 together
    // ------------------------------------------------------------------


    /**
     * End to end: the token is bound by the new source AND substituted into the string-literal name
     * operand, so the minted rule names a real column. Before phase 1 this rule would have been
     * dropped with a surviving token.
     */
    @Test
    void theMintedRuleNamesTheColumnInTheAccessor()
    {
        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(
                template(ExpansionSource.ALL_CHARACTER_VARIABLES), fixture(),
                new TokenExpander.Context(null, null, "AE"));
        WildcardExpander.ExpansionResult.Expanded expanded = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result);

        Rule first = expanded.rules().get(0);
        String printed = net.cumba.corej.core.expr.ExpressionPrinter
                .print(((CheckConditionExpression) first.getCheck()).expr());

        assertEquals("var_label(\"USUBJID\", \"DATA\") != \"\"", printed);
    }


    /** A dataset with no column of the requested type is a stated skip, never a silent zero. */
    @Test
    void noColumnOfTheRequestedTypeIsANoMatchWithAReason()
    {
        DataTableMeta charsOnly = DataTableMeta.builder().name("AE").label("Adverse Events")
                .rowCount(0).totalRowCount(0).columns(new DataTableColumnMeta[]
                {
                        col(0, "USUBJID", DataValueType.STRING)
                }).build();

        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(
                template(ExpansionSource.ALL_NUMERIC_VARIABLES), charsOnly,
                new TokenExpander.Context(null, null, "AE"));

        WildcardExpander.ExpansionResult.NoMatch noMatch = assertInstanceOf(
                WildcardExpander.ExpansionResult.NoMatch.class, result,
                () -> "expected a stated skip, got " + result);
        assertTrue(noMatch.reason().contains("Num"),
                "the audit reason must name what was looked for: " + noMatch.reason());
    }

    // ------------------------------------------------------------------
    // G1 — the expansion cap is OPT-IN
    // ------------------------------------------------------------------


    private static DataTableMeta wideFixture(int columnCount)
    {
        DataTableColumnMeta[] columns = new DataTableColumnMeta[columnCount];
        for (int i = 0; i < columnCount; i++)
        {
            columns[i] = col(i, "VAR" + i, DataValueType.STRING);
        }
        return DataTableMeta.builder().name("WIDE").label("Wide").rowCount(0).totalRowCount(0)
                .columns(columns).build();
    }


    private static WildcardExpander.ExpansionResult expandAgainst(DataTableMeta meta)
    {
        return TokenExpander.tryExpand(template(ExpansionSource.ALL_VARIABLES), meta,
                new TokenExpander.Context(null, null, "WIDE"));
    }


    /**
     * A configured cap that is exceeded SKIPS the rule, with the count and the cap in the reason.
     */
    @Test
    void aConfiguredCapThatIsExceededSkipsTheRule()
    {
        System.setProperty("corej.maxExpansionsPerRule", "3");
        try
        {
            WildcardExpander.ExpansionResult result = expandAgainst(fixture());

            WildcardExpander.ExpansionResult.NoMatch noMatch = assertInstanceOf(
                    WildcardExpander.ExpansionResult.NoMatch.class, result,
                    () -> "the cap must SKIP, never truncate — got " + result);
            assertTrue(noMatch.reason().contains("7"), noMatch.reason());
            assertTrue(noMatch.reason().contains("3"), noMatch.reason());
        }
        finally
        {
            System.clearProperty("corej.maxExpansionsPerRule");
        }
    }


    /**
     * ⭐⭐ The test owner ruling R5 is really about, and the one that has to exist for the ruling to
     * survive. <i>"Agree to a cap, but off by default. At default we want to see every finding … a
     * cap on minted rules means the rule is not executed at all and no finding pops up."</i>
     *
     * <p>
     * With the cap unset, a candidate set larger than any plausible finite default must expand in
     * FULL. Without this, someone could give the cap a finite default later and every other test
     * here would still pass — the exact silent coverage loss the ruling names. It asserts the
     * expansion COUNT, deliberately, not that the default equals {@code Integer.MAX_VALUE}: a
     * constant can be changed without the assertion noticing.
     * </p>
     */
    @Test
    void withNoCapConfiguredAVeryWideDatasetExpandsInFull()
    {
        assertNull(System.getProperty("corej.maxExpansionsPerRule"),
                "this test is meaningless if something left the property set");

        WildcardExpander.ExpansionResult result = expandAgainst(wideFixture(2048));

        WildcardExpander.ExpansionResult.Expanded expanded = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result,
                () -> "the default must be unlimited — got " + result);
        assertEquals(2048, expanded.rules().size());
    }

}
