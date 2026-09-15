package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
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
 * Shape 1 of {@code PLAN-joined-column-typing}: a dotted joined reference ({@code DM.AGE}) carries
 * the foreign column's real declared type instead of being published as {@code STRING}.
 *
 * <p>
 * This is the shape behind {@code CDISC-AD0204} / {@code PMDA-AD0204} ({@code AGE != DM.AGE}) and
 * {@code CDISC-CG0032} / {@code CORE-000249} ({@code VISITDY != TV.VISITDY}) — the four rules that
 * do a genuine typed comparison across a join.
 * </p>
 *
 * <p>
 * ⚠ Real {@link CachedDataTableColumn} fixtures, never {@code MockTable}: MockTable stubs
 * {@code IDataValue} and renders {@code raw.toString()}, so {@code getAsDoubleCleaned}'s rounding
 * never happens there and the precision assertions could not fail.
 * </p>
 */
class DottedJoinedColumnTypingTest
{

    private static final double BIG = 1.0E13 + 1;

    private static EvaluationContext ctx(IDataTable primary, IDataTable dm)
    {
        JoinLookup lk = DatasetLookup.build("DM", dm, List.of("USUBJID"));
        assertNotNull(lk);
        DatasetResolver res = n -> "DM".equals(n) ? dm : null;
        return EvaluationContext.builder().table(primary).datasetResolver(res)
                .joinedDatasets(Map.of("DM", lk)).build();
    }


    private static BitSet eval(String expr, EvaluationContext c)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), c);
    }


    @Test
    @DisplayName("AD0204's shape: identical numeric values across a join are equal")
    void ad0204ShapeIsExact()
    {
        IDataTable ae = t("AE", c("USUBJID", DataValueType.STRING, "U1"),
                c("AGE", DataValueType.DOUBLE, BIG));
        IDataTable dm = t("DM", c("USUBJID", DataValueType.STRING, "U1"),
                c("AGE", DataValueType.DOUBLE, BIG));
        EvaluationContext e = ctx(ae, dm);

        // Before shape 1 was typed, DM.AGE resolved as "10000000000000" -- the last digit rounded
        // away by getAsDoubleCleaned -- so AGE != DM.AGE fired on two identical values.
        assertEquals(new BitSet(), eval("AGE != DM.AGE", e));
        assertEquals(bits(0), eval("AGE == DM.AGE", e));
    }


    @Test
    @DisplayName("a joined numeric column orders numerically, not lexically")
    void joinedNumericOrdersNumerically()
    {
        IDataTable ae = t("AE", c("USUBJID", DataValueType.STRING, "U1"),
                c("AGE", DataValueType.LONG, 9L));
        IDataTable dm = t("DM", c("USUBJID", DataValueType.STRING, "U1"),
                c("AGE", DataValueType.LONG, 30L));
        EvaluationContext e = ctx(ae, dm);

        // "30" < "9" lexically; 30 > 9 numerically.
        assertEquals(bits(0), eval("DM.AGE > 10", e));
        assertEquals(bits(0), eval("AGE < DM.AGE", e));
    }


    @Test
    @DisplayName("a joined CHARACTER column is untouched, blanks included")
    void joinedCharacterUnchanged()
    {
        IDataTable ae = t("AE", c("USUBJID", DataValueType.STRING, "U1", "U2"),
                c("ARM", DataValueType.STRING, "A", "B"));
        IDataTable dm = t("DM", c("USUBJID", DataValueType.STRING, "U1", "U2"),
                c("ARM", DataValueType.STRING, "A", null));
        EvaluationContext e = ctx(ae, dm);

        assertEquals(bits(0), eval("ARM == DM.ARM", e));
        // A blank joined character cell still reads "" -- the lookup() contract, preserved by
        // lookupValue. If it had become null, empty() would change meaning on every joined Char.
        assertEquals(bits(1), eval("empty(DM.ARM)", e));
    }


    @Test
    @DisplayName("J7: the gate now REACHES a dotted joined reference")
    void gateReachesDottedReference()
    {
        // Before J7 the gate inspected only a named ColumnVector, so a dotted reference was never
        // type-checked however wrong its declared type was. ARM is Char on DM, so reading it in a
        // numeric position must now error -- and the message must NAME it, which is what
        // Vector.gatedName() carries.
        IDataTable ae = t("AE", c("USUBJID", DataValueType.STRING, "U1"),
                c("AGE", DataValueType.LONG, 30L));
        IDataTable dm = t("DM", c("USUBJID", DataValueType.STRING, "U1"),
                c("ARM", DataValueType.STRING, "PLACEBO"), c("AGE", DataValueType.LONG, 30L));
        EvaluationContext e = ctx(ae, dm);

        ColumnTypeMismatchException ex = assertThrows(ColumnTypeMismatchException.class,
                () -> eval("DM.ARM > 5", e));
        assertTrue(ex.getMessage().contains("DM.ARM"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Char"), ex.getMessage());

        // A Num joined column against a string literal errors in the other direction (R9).
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DM.AGE == \"30\"", e));

        // ...and a correctly-typed comparison still passes, which is the point of gating on the
        // REAL type rather than on a hardcoded STRING.
        assertEquals(bits(0), eval("AGE == DM.AGE", e));
        assertEquals(bits(0), eval("DM.ARM == \"PLACEBO\"", e));
    }

    private record Col(String name, DataValueType type, Object[] values)
    {
    }

    private static Col c(String name, DataValueType type, Object... values)
    {
        return new Col(name, type, values);
    }


    private static IDataTable t(String name, Col... cols)
    {
        int rc = cols[0].values().length;
        CachedDataTableColumn[] cc = new CachedDataTableColumn[cols.length];
        DataTableColumnMeta[] mm = new DataTableColumnMeta[cols.length];
        for (int i = 0; i < cols.length; i++)
        {
            cc[i] = new CachedDataTableColumn(i, cols[i].type());
            mm[i] = DataTableColumnMeta.builder().index(i).name(cols[i].name())
                    .label(cols[i].name()).type(cols[i].type()).build();
            for (int r = 0; r < rc; r++)
            {
                cc[i].addElement(cols[i].values()[r]);
            }
            cc[i].complete();
        }
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name).columns(mm)
                .rowCount(rc).totalRowCount(rc).build(), cc);
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
