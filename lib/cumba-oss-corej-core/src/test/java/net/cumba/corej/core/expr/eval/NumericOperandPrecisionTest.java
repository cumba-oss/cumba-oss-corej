package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step A of {@code PLAN-joined-column-typing} (§3.5, review finding F2): a numeric comparison must
 * read its right-hand operand as a <b>value</b>, not as text.
 *
 * <p>
 * Resolving it as text routed it through {@code DataValueSupport.getAsDoubleCleaned}, which rounds
 * to <b>12 significant digits</b>, so a 14-digit {@code DOUBLE} lost its last digit on the way into
 * the comparison while the left-hand cell kept its exact value. The engine then reported two
 * identical values as different. ⚠ No join is involved — the plan's headline example was a joined
 * column, but the defect is engine-wide and lives in operand resolution.
 * </p>
 */
class NumericOperandPrecisionTest
{

    private static final double BIG = 1.0E13 + 1; // 10000000000001 — 14 significant digits

    private static BitSet eval(String expr, IDataTable t)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr),
                EvaluationContext.builder().table(t).build());
    }


    /**
     * Two primary DOUBLE columns, same value, no join anywhere.
     *
     * <p>
     * ⚠⚠ Built on real {@link CachedDataTableColumn}s, <b>never</b> {@code MockTable}. MockTable
     * stubs {@code IDataValue} with Mockito and answers {@code getValueAsString()} with
     * {@code raw.toString()}, so {@code DataValueSupport.getAsDoubleCleaned} never runs and the
     * defect under test <b>cannot occur</b> in that fixture. A first version of this test used
     * MockTable, passed, and went on passing with the fix reverted — a self-confirming fixture.
     * </p>
     */
    private static IDataTable table()
    {
        return doubles("T", List.of("A", "B"), new Double[][]
        {
                {
                        BIG, 4.0, 1.2E-10
                },
                {
                        BIG, 4.0, 1.2E-10
                }
        });
    }


    private static IDataTable doubles(String name, List<String> names, Double[][] data)
    {
        int cc = names.size();
        int rc = data[0].length;
        CachedDataTableColumn[] cols = new CachedDataTableColumn[cc];
        DataTableColumnMeta[] metas = new DataTableColumnMeta[cc];
        for (int c = 0; c < cc; c++)
        {
            cols[c] = new CachedDataTableColumn(c, DataValueType.DOUBLE);
            metas[c] = DataTableColumnMeta.builder().index(c).name(names.get(c)).label(names.get(c))
                    .type(DataValueType.DOUBLE).build();
            for (int r = 0; r < rc; r++)
            {
                cols[c].addElement(data[c][r]);
            }
            cols[c].complete();
        }
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name)
                .columns(metas).rowCount(rc).totalRowCount(rc).build(), cols);
    }


    private static IDataTable chars(String name, String[] s, String[] u)
    {
        CachedDataTableColumn cs = new CachedDataTableColumn(0, DataValueType.STRING);
        CachedDataTableColumn cu = new CachedDataTableColumn(1, DataValueType.STRING);
        for (int r = 0; r < s.length; r++)
        {
            cs.addElement(s[r]);
            cu.addElement(u[r]);
        }
        cs.complete();
        cu.complete();
        DataTableColumnMeta[] metas =
        {
                DataTableColumnMeta.builder().index(0).name("S").label("S")
                        .type(DataValueType.STRING).build(),
                DataTableColumnMeta.builder().index(1).name("U").label("U")
                        .type(DataValueType.STRING).build()
        };
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name)
                .columns(metas).rowCount(s.length).totalRowCount(s.length).build(),
                new CachedDataTableColumn[]
                {
                        cs, cu
                });
    }


    @Test
    @DisplayName("column vs column: identical DOUBLEs are equal, and 14 digits survive")
    void columnVsColumnIsExact()
    {
        IDataTable t = table();
        // Before Step A this returned {0}: A != B was TRUE for two identical cells, because the
        // RHS arrived as "10000000000000" — the last digit rounded away.
        assertEquals(new BitSet(), eval("A != B", t), "identical values must not compare unequal");
        assertEquals(bits(0, 1, 2), eval("A == B", t));
    }


    @Test
    @DisplayName("ordering carries Step C's tolerance — 14 digits are within ε at N=12")
    void orderingIsTolerant()
    {
        IDataTable t = table();
        // ⭐ Step A ∩ Step C. At the default N=12, ε for 1e13 is ~100, so 10000000000001 and
        // 10000000000000 are EQUAL within tolerance: `>` is false and `==` is true. That is the
        // ruled semantics (D8/D12), not a regression -- and it means the default tolerance
        // SUBSUMES the §3.5 verdict defect, since the rounding error it came from is far below ε.
        //
        // Step A still matters, for two reasons the tolerance cannot supply: both operands now
        // reach the comparison EXACT, so the tolerance is applied symmetrically and deliberately
        // rather than on top of one already-rounded side; and at a tighter setting (N >= 15) the
        // rounding error exceeds ε and Step A is what keeps the verdict right.
        assertEquals(new BitSet(), eval("A > 10000000000000", t));
        assertEquals(bits(0), eval("A == 10000000000000", t));
        // Rows 1 and 2 are below it by far more than ε, so ordering still works where it should.
        assertEquals(bits(1, 2), eval("A < 10000000000000", t));
    }


    @Test
    @DisplayName("the five relations stay coherent: exactly one of <, ==, > holds")
    void relationsAreCoherent()
    {
        IDataTable t = table();
        // The invariant a one-sided epsilon would break. Whatever the tolerance decides for a pair,
        // it must decide it consistently across all five operators.
        BitSet lt = eval("A < 10000000000000", t);
        BitSet eq = eval("A == 10000000000000", t);
        BitSet gt = eval("A > 10000000000000", t);
        for (int r = 0; r < 3; r++)
        {
            int n = (lt.get(r) ? 1 : 0) + (eq.get(r) ? 1 : 0) + (gt.get(r) ? 1 : 0);
            assertEquals(1, n, "row " + r + ": exactly one of <, ==, > must hold");
        }
        // <= is (< or ==), >= is (> or ==)
        BitSet le = eval("A <= 10000000000000", t);
        BitSet ge = eval("A >= 10000000000000", t);
        BitSet expectLe = (BitSet) lt.clone();
        expectLe.or(eq);
        BitSet expectGe = (BitSet) gt.clone();
        expectGe.or(eq);
        assertEquals(expectLe, le);
        assertEquals(expectGe, ge);
    }


    @Test
    @DisplayName("a numeric literal target still compares correctly (the pre-existing path)")
    void literalTargetUnchanged()
    {
        IDataTable t = table();
        assertEquals(bits(0), eval("A == 10000000000001", t));
        assertEquals(bits(1), eval("A == 4", t));
    }


    @Test
    @DisplayName("character columns are untouched — the typed operand never reaches a textual fold")
    void characterColumnsUnchanged()
    {
        // ColumnVector.comparisonOperand falls through to resolvedObject for a Char column, so this
        // path is byte-identical to before. It matters because equalsNumericAware's textual
        // fallback folds the target with toString(): DataValueString.toString() QUOTES its value
        // and the anonymous DataValues.of has no override at all, so a typed operand leaking in
        // here would compare "\"x\"" against "x" and silently invert every character verdict.
        IDataTable c = chars("C", new String[]
        {
                "x", "y", ""
        }, new String[]
        {
                "x", "z", ""
        });
        assertEquals(bits(0, 2), eval("S == U", c));
        assertEquals(bits(1), eval("S != U", c));
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }
}
