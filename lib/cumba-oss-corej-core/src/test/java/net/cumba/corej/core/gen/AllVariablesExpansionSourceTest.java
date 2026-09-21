package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * ⭐ The cross-component invariant, pinned <b>from source</b>. Review round 1 caught that the
     * mapping assertions below — true but local — do not test what this name promises: someone
     * could reinstate a private {@code charOrNum} in {@code ExprCompiler} and change it, and every
     * assertion in this file would stay green while {@code var_type("X","DATA")} and
     * {@code all_numeric_variables} disagreed about the same column.
     *
     * <p>
     * So this asserts the delegation itself, the way {@code TryRaiseToExprGuardSurfaceTest} asserts
     * its call sites: {@code ExprCompiler}'s {@code VAR_TYPE} arm must route through
     * {@code MetadataNormalizer.charOrNum}, and {@code ExprCompiler} must declare no
     * {@code charOrNum} of its own.
     * </p>
     *
     * @throws IOException
     *             if the source file cannot be read
     */
    @Test
    void varTypeDataDelegatesToTheSameFoldTheExpansionSourcesUse() throws IOException
    {
        Path source = Path.of("src/main/java/net/cumba/corej/core/expr/eval/ExprCompiler.java");
        assertTrue(Files.exists(source), () -> "source not found at " + source.toAbsolutePath()
                + " — this test must fail loudly rather than pass over a missing file");
        List<String> lines = Files.readAllLines(source);

        // ⚠ There are TWO `case VAR_TYPE ->` arms and only one is the DATA-level fold; the other
        // yields the provider key "data_type". Filtering on the arm text alone gave a population
        // of 2 and a red test on correct code — a grep's line count is not a population.
        List<String> foldArms = lines.stream().map(String::strip)
                .filter(l -> l.startsWith("case VAR_TYPE ->") && l.contains("col.getType()"))
                .toList();
        assertEquals(1, foldArms.size(),
                () -> "expected exactly one DATA-level VAR_TYPE arm, found: " + foldArms);
        assertTrue(foldArms.get(0).contains("MetadataNormalizer.charOrNum"),
                () -> "var_type(\"DATA\") must use the shared fold, not a local one: "
                        + foldArms.get(0));

        List<String> localFolds = lines.stream().map(String::strip)
                .filter(l -> l.contains("String charOrNum(") && l.contains("private")).toList();
        assertTrue(localFolds.isEmpty(),
                () -> "ExprCompiler must not declare its own charOrNum — that is the drift this"
                        + " hoist removed: " + localFolds);
    }


    /** The fold's own mapping table, including the neither-bucket. */
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
     * ⛔⛔ Review round 1: the cap was applied AFTER {@code crossProduct} had built every tuple, so
     * it could not bound the explosion it exists for. Two {@code all_variables} directives over
     * this 512-column fixture are 262 144 tuples — enough that materialising them first is
     * measurable, and the same shape at 3 000 columns is 9 000 000 two-element lists. The single
     * directive case, the only shipped shape, is why every other test here passed.
     *
     * <p>
     * This asserts the SKIP happens for a multi-directive rule, which is only possible if the size
     * is projected rather than materialised.
     * </p>
     */
    @Test
    void theCapFiresOnAMultiDirectiveRuleWithoutBuildingTheProduct()
    {
        ExpansionDirective a = new ExpansionDirective();
        a.setToken("&A");
        a.setOver(ExpansionSource.ALL_VARIABLES);
        ExpansionDirective b = new ExpansionDirective();
        b.setToken("&B");
        b.setOver(ExpansionSource.ALL_VARIABLES);

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CDISC-SEND-0049");
        rule.setCore(core);
        String src = "var_label(\"&A\", \"DATA\") != var_label(\"&B\", \"DATA\")";
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(src), src));
        rule.setExpansion(List.of(a, b));

        System.setProperty("corej.maxExpansionsPerRule", "1000");
        try
        {
            WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(rule,
                    wideFixture(512), new TokenExpander.Context(null, null, "WIDE"));

            WildcardExpander.ExpansionResult.NoMatch noMatch = assertInstanceOf(
                    WildcardExpander.ExpansionResult.NoMatch.class, result,
                    () -> "the cap must fire before the product is built — got " + result);
            assertTrue(noMatch.reason().contains("262144"), noMatch.reason());
        }
        finally
        {
            System.clearProperty("corej.maxExpansionsPerRule");
        }
    }


    /** A blank column name is dropped, but never silently — the audit must say so. */
    @Test
    void aBlankColumnNameIsDroppedWithAStatedReason()
    {
        DataTableMeta withBlank = DataTableMeta.builder().name("AE").label("AE").rowCount(0)
                .totalRowCount(0).columns(new DataTableColumnMeta[]
                {
                        col(0, "USUBJID", DataValueType.STRING), col(1, " ", DataValueType.STRING)
                }).build();

        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(
                template(ExpansionSource.ALL_VARIABLES), withBlank,
                new TokenExpander.Context(null, null, "AE"));

        WildcardExpander.ExpansionResult.Expanded expanded = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result);
        assertEquals(1, expanded.rules().size(), "the blank-named column must not expand");
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
