package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.cdisc.core.expr.eval.ColumnVector;
import net.cumba.cdisc.core.expr.eval.Primitives;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@link ScalarSemantics#resolvedString} — the one place that decides how a blank cell resolves.
 *
 * <p>
 * ⚠⚠ <b>Read {@code resolvedString}'s javadoc before changing anything here.</b> The settled design
 * wants a blank CHARACTER cell to resolve to {@code ""} so the engine cannot tell a source
 * {@code null} from an empty string. That step is <b>deliberately not taken yet</b>, and these
 * tests pin the <em>current</em> contract: a blank resolves to {@code null}.
 * </p>
 * <p>
 * ⚑ <b>The stated blocker is gone.</b> It used to be a pre-existing {@code date_*} defect — an
 * empty operand truncated the other to nothing and compared <em>equal to every date</em>, so every
 * {@code date_*_or_equal_to} fired. Q16 removed it at the operator layer (see the last test). What
 * remains is a wider question than {@code date_*}: resolving a blank to {@code ""} changes what
 * <b>every</b> operator sees, so it keeps its own acceptance criteria and its own decision
 * </p>
 */
class ScalarSemanticsResolvedStringTest
{

    private static ColumnVector vector(IDataTable t, String name)
    {
        int idx = t.getMetaData().getColumnIndex(name);
        return new ColumnVector(t.getColumn(idx), t.getMetaData().getColumn(idx).getType());
    }


    private static CachedDataTableColumn charColumn()
    {
        CachedDataTableColumn col = new CachedDataTableColumn(0, DataValueType.STRING);
        col.addElement("A");
        col.addElement(MissingValue.MIS);
        col.addElement("");
        col.addElement(null);
        return col;
    }


    /**
     * A present value comes back verbatim, and a <em>present</em> empty string stays {@code ""} —
     * it is a value, not a blank, and folding it onto the missing branch is the mistake
     * {@code resolvedString} exists to prevent.
     */
    @Test
    void aPresentValueAndAPresentEmptyStringBothSurvive()
    {
        CachedDataTableColumn col = charColumn();

        assertEquals("A", ScalarSemantics.resolvedString(col, DataValueType.STRING, 0));
        assertEquals("", ScalarSemantics.resolvedString(col, DataValueType.STRING, 2),
                "a present empty string is a value and must not resolve to null");
    }


    /**
     * The current contract: a blank resolves to {@code null} — "no comparand" — whatever the column
     * type. ⚠ Row 1 (a {@code MissingValue} in a character column) is exactly the cell the
     * blindness step would change to {@code ""}.
     */
    @Test
    void aBlankCellResolvesToNull()
    {
        CachedDataTableColumn chars = charColumn();
        assertNull(ScalarSemantics.resolvedString(chars, DataValueType.STRING, 1),
                "a MissingValue in a character column resolves to null today");

        // ⚑ A bare null stored in a STRING buffer is NOT a missing marker: Fix #161's mapping is
        // still live in AbstractDataBuffer.createDataValue ("for STRING we map from null to empty
        // string"), so the cell reads as a present empty string. That is why the loaders had to
        // store MissingValue.MIS explicitly to express a source null at all — storing null would
        // have been indistinguishable from "".
        assertEquals("", ScalarSemantics.resolvedString(chars, DataValueType.STRING, 3),
                "a bare null in a STRING buffer is Fix #161's empty string, not a missing marker");

        CachedDataTableColumn nums = new CachedDataTableColumn(1, DataValueType.DOUBLE);
        nums.addElement(1.5d);
        nums.addElement(MissingValue.MIS);
        assertEquals("1.5", ScalarSemantics.resolvedString(nums, DataValueType.DOUBLE, 0));
        assertNull(ScalarSemantics.resolvedString(nums, DataValueType.DOUBLE, 1));
    }


    /**
     * ⚠⚠ The trap {@code resolvedString} must never fall into: {@code MissingValue.MIS.toString()}
     * is {@code "."}, so resolving a blank through {@code getValueAsString()} would hand the engine
     * a literal dot, indistinguishable from a sponsor's real {@code "."} value.
     */
    @Test
    void aBlankNeverLeaksTheMissingValueDisplayString()
    {
        assertNull(ScalarSemantics.resolvedString(charColumn(), DataValueType.STRING, 1));

        // The positive control: a column that really holds "." must still resolve to ".", so the
        // assertion above is about the missing marker and not about dots in general.
        CachedDataTableColumn dots = new CachedDataTableColumn(0, DataValueType.STRING);
        dots.addElement(".");
        assertEquals(".", ScalarSemantics.resolvedString(dots, DataValueType.STRING, 0));
    }


    /**
     * ⚑ <b>The blocker is GONE — Q16 removed it, and this records how.</b>
     *
     * <p>
     * {@link ScalarSemantics#compareIso} still does what it always did: it truncates <em>both</em>
     * operands to the minimum precision, so an empty operand truncates the other to nothing and the
     * three-way comparison still answers {@code 0}. That was never a defect in {@code compareIso}
     * itself — it is a truncate-to-common-precision comparator and it says so — but it was routed
     * to from {@code Primitives.dateComparison}, and there "equal" meant every
     * {@code date_*_or_equal_to} against a blank fired on every row.
     * </p>
     * <p>
     * ⇒ Q16 rewired the operator, not the comparator. {@code compareIso} is now reached <b>only</b>
     * when both operands are calendar-complete dates — the case where truncating to the coarser
     * precision is exactly right — and since {@code Fix #250} it receives their <b>cores</b>
     * ({@code IsoDateBounds.core}), not the raw cell text. Anything else, including a blank, goes
     * to {@code IsoDateComparison}'s hull rule and compares false to everything.
     * </p>
     * <p>
     * ⚠ So the tripwire's original wording is retired: this test no longer describes a blocker.
     * What it still pins is the <b>split</b> — the surviving truncation below must stay behind the
     * both-complete gate, or the blank defect returns by the same route it left.
     * </p>
     * <p>
     * ⚠⚠ It does <b>not</b> follow that {@code resolvedString}'s blank branch can now become
     * {@code aDeclaredType == STRING ? "" : null}. That flip changes what <em>every</em> operator
     * sees, not just {@code date_*}, and it is Q5's decision with its own acceptance criteria.
     * </p>
     */
    @Test
    void compareIsoStillTruncates_butOnlyCompleteDatesReachItNow()
    {
        // Unchanged, and deliberately so — this is the comparator's documented contract.
        assertEquals(0, ScalarSemantics.compareIso("2020-05-01", ""),
                "compareIso itself is a truncate-to-common-precision comparator, untouched by Q16");
        assertEquals(0, ScalarSemantics.compareIso("", "1999-01-01"), "…in both directions");

        // The control: two real dates still compare properly, so the assertions above are about
        // the empty operand and not about compareIso being broken outright.
        assertTrue(ScalarSemantics.compareIso("2020-05-01", "2019-12-31") > 0);
        assertFalse(ScalarSemantics.compareIso("2019-12-31", "2020-05-01") > 0);

        // ⚑ …and the operator layer no longer inherits it. `>=` against a blank was TRUE before
        // Q16 (measured) and is FALSE now, without compareIso changing at all.
        IDataTable t = MockTable.of().col("A", "2020-05-01").col("B", "").build();
        BitSet ge = Primitives.dateComparison(vector(t, "A"), vector(t, "B"), 1, 1, true, false);
        assertFalse(ge.get(0),
                "date_greater_than_or_equal_to no longer fires on a blank comparand");
    }
}
