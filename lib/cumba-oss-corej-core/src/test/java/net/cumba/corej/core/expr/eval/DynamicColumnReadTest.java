package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} §2.3 steps 3 and 4 at the resolver:
 * {@link DynamicColumnRead#cell} over a context, and the compiled {@code colref} plan reading its
 * site's kind per EVALUATION (phase 0 D4: compiled plans are shared across rules through
 * {@code NativeExprEvaluator.CACHE}, so a kind bound at compile time would leak from one rule into
 * the next).
 */
class DynamicColumnReadTest
{

    private static final IDataTable TABLE = new SyntheticDataTable("DM",
            Map.of("USUBJID", DataValueType.STRING), 3);

    private static EvaluationContext ctx(Set<Expr.Call> sites)
    {
        return EvaluationContext.builder().table(TABLE).numericExpectedDynamicSites(sites).build();
    }


    @Test
    void aSitesKindIsReadPerEvaluationNeverBoundAtCompileTime()
    {
        Expr check = CheckExpressionParser.parse("colref(\"ZZ\") < 3");
        Expr.Call site = (Expr.Call) ((Expr.Binary) check).left();
        BitSet numeric = NativeExprEvaluator.evaluate(check, ctx(Set.of(site)));
        BitSet character = NativeExprEvaluator.evaluate(check, ctx(Set.of()));
        assertEquals(3, numeric.cardinality(), "numeric-expected site: MIS < 3 holds on every row");
        assertEquals(0, character.cardinality(),
                "the SAME cached program under a context without the site answers \"\" (no fire)");
        assertEquals(3, NativeExprEvaluator.evaluate(check, ctx(Set.of(site))).cardinality(),
                "and flips back — nothing was memoised from the first context");
    }


    @Test
    void anUnsuppliedDatasetTakesTheOverloadsDefault()
    {
        EvaluationContext c = ctx(Set.of());
        IDataValue numeric = DynamicColumnRead.cell(c, "ADSL.AP01SDT", 0, true);
        IDataValue character = DynamicColumnRead.cell(c, "ADSL.AP01SDT", 0, false);
        assertSame(MissingValue.MIS, numeric.getValue());
        assertEquals("", character.getValueAsString());
        assertEquals(DataValueType.STRING, character.getType());
        assertEquals(ExprCompiler.dottedNotSuppliedDefault(true).getValue(), numeric.getValue());
    }


    @Test
    void theResolvedNamesRuleWideKindIsTheSecondTerm()
    {
        EvaluationContext c = EvaluationContext.builder().table(TABLE)
                .numericExpectedColumns(Set.of("ZZ")).build();
        assertSame(MissingValue.MIS, DynamicColumnRead.cell(c, "ZZ", 0, false).getValue(),
                "a name the rule authors numerically elsewhere defaults as that name does");
        assertEquals("", DynamicColumnRead.cell(c, "YY", 0, false).getValueAsString());
        assertEquals("", DynamicColumnRead.cell(c, "", 0, false).getValueAsString(),
                "\"\" names no column: the absent default");
    }


    @Test
    void aDashNameReachingEvaluationAsserts()
    {
        assertThrows(ExpressionException.class,
                () -> DynamicColumnRead.cell(ctx(Set.of()), "--SEQ", 0, false),
                "D77b: an unresolved -- name is a specialiser defect");
    }


    @Test
    void aListFirstHopKeepsEveryElementAsATypedCell()
    {
        EvaluationContext c = ctx(Set.of());
        Vector names = ConstVector.of(List.of("USUBJID", "ZZ"));
        Vector out = DynamicColumnRead.vector(EvalRun.fullRange(c), names, false, true);
        Object row0 = out.value(0).resolved();
        assertEquals(2, ((List<?>) row0).size(), "one element per name, in the list's order");
        assertEquals("", ((IDataValue) ((List<?>) row0).get(1)).getValueAsString(),
                "an absent element takes its default");
    }


    /**
     * Review rounds 2-3 (engine 4): names that read no evaluation-table column are memoised only up
     * to a cap, so per-row unique data values cannot grow the evaluation's memo per row — and every
     * row still answers its absent default, before and after the cap. The memo's SIZE is asserted,
     * so the test fails if the cap goes (round 3: the answer alone could not).
     */
    @Test
    void perRowUniqueNamesStayWithinTheMemoCap()
    {
        // ADSL is JOINED, so a dotted name is a joined read (round 3: only evaluation-table
        // columns are exempt from the cap — a per-row unique DS.X is not).
        net.cumba.corej.core.exec.JoinLookup adsl = new net.cumba.corej.core.exec.JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                return "";
            }


            @Override
            public String getDatasetName()
            {
                return "ADSL";
            }
        };
        EvaluationContext c = EvaluationContext.builder().table(TABLE)
                .joinedDatasets(Map.of("ADSL", adsl)).build();
        int rows = 3000;
        DynamicColumnRead.Resolver resolver = new DynamicColumnRead.Resolver(c, false, false);
        for (int r = 0; r < rows; r++)
        {
            String name = r % 2 == 0 ? "ZZ" + r : "ADSL.ZZ" + r;
            IDataValue cell = (IDataValue) resolver.resolve(ConstVector.of(name).value(0), r);
            assertEquals("", cell.getValueAsString(), name);
        }
        assertEquals(DynamicColumnRead.MAX_UNBOUNDED_NAMES, resolver.memoSize(),
                "exactly the cap after " + rows + " unique non-column names");
        // An evaluation-table column is memoised even PAST the cap (review round 4: an exact
        // count, so a cap that stopped exempting Primary reds here).
        resolver.resolve(ConstVector.of("USUBJID").value(0), 0);
        assertEquals(DynamicColumnRead.MAX_UNBOUNDED_NAMES + 1, resolver.memoSize(),
                "the Primary target is memoised beyond the cap");
    }
}
