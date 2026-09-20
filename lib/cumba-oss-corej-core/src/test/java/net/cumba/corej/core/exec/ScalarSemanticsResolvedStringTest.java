package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@link ScalarSemantics#resolvedString} — the one place that decides how a blank cell resolves.
 *
 * <p>
 * ⚠⚠ <b>Read {@code resolvedString}'s javadoc before changing anything here.</b>
 * </p>
 * <p>
 * ⭐⭐ <b>Owner ruling, 2026-09-18 — these tests were RE-BASED onto it, not deleted.</b> <i>"'the
 * engine treats a missing character cell exactly like an empty string' is not required anymore and
 * now even dangerous. An <b>absent column</b> gets empty string. A <b>present column can be
 * missing</b>, and <b>missing is not empty string</b>."</i> The step this class used to describe as
 * "deliberately not taken yet" — resolving a blank CHARACTER cell to {@code ""} so the engine could
 * not tell a source {@code null} from an empty string — is therefore <b>retired, not pending</b>,
 * and what these tests now pin is the <em>distinction</em>: on a column that exists, missing
 * answers {@code null} and only a stored empty string answers {@code ""}.
 * </p>
 * <p>
 * ⚑ Two assertions moved with the ruling rather than being dropped, because deleting them would
 * have left the new contract unpinned — the same reason {@code Fix #161}'s two guards were
 * re-based. The row-3 assertion in {@link #aBlankCellResolvesToNull} flipped from {@code ""} to
 * {@code null} (a raw {@code null} in a STRING buffer is a {@code MissingValue.MIS} since the
 * datatable repository's {@code d4edd59}), and the final test's closing paragraph no longer argues
 * that the flip is a live option.
 * </p>
 * <p>
 * ⛔ <b>Out of scope, and pinned as unchanged:</b> the {@code empty()} / {@code non_empty} fold,
 * where {@code null}, a {@code MissingValue} and {@code ""} are all one "blank" answer. Whether
 * <em>that</em> must also change under this ruling is a separate owner question, open as of
 * 2026-09-18 — see {@link #theBlankFoldBehindEmptyIsUnchanged}.
 * </p>
 */
class ScalarSemanticsResolvedStringTest
{

    private static ColumnVector vector(IDataTable t, String name)
    {
        int idx = t.getMetaData().getColumnIndex(name);
        return new ColumnVector(name, t.getColumn(idx), t.getMetaData().getColumn(idx).getType());
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
     * The contract, and since the 2026-09-18 ruling the <em>settled</em> one rather than an interim
     * step: a missing cell resolves to {@code null} — "no comparand" — whatever the column type,
     * <b>character columns included</b>. ⚠ Row 1 is a {@code MissingValue} in a character column
     * and row 3 a raw {@code null} in one; under the ruling both are missing, so both answer
     * {@code null}.
     */
    @Test
    void aBlankCellResolvesToNull()
    {
        CachedDataTableColumn chars = charColumn();
        assertNull(ScalarSemantics.resolvedString(chars, DataValueType.STRING, 1),
                "a MissingValue in a character column resolves to null — missing is not \"\"");

        // ⭐ RE-BASED (owner ruling, 2026-09-18). This assertion read assertEquals("", …) with the
        // message "a bare null in a STRING buffer is Fix #161's empty string, not a missing
        // marker". That was true of the shipped engine: AbstractDataBuffer.createDataValue's STRING
        // arm mapped a null to DataValueString(""). The datatable repository's d4edd59 makes it
        // MissingValue.MIS — "a null char cell is MissingValue.MIS" — so the cell is now missing
        // and resolves to null. ⚑ Flipped rather than deleted: the same cell, opposite polarity, is
        // what proves the ruling reaches this channel.
        assertNull(ScalarSemantics.resolvedString(chars, DataValueType.STRING, 3),
                "a raw null in a STRING buffer is a MissingValue.MIS (d4edd59), so it has no "
                        + "comparand — it must NOT resolve to the empty string");

        CachedDataTableColumn nums = new CachedDataTableColumn(1, DataValueType.DOUBLE);
        nums.addElement(1.5d);
        nums.addElement(MissingValue.MIS);
        assertEquals("1.5", ScalarSemantics.resolvedString(nums, DataValueType.DOUBLE, 0));
        assertNull(ScalarSemantics.resolvedString(nums, DataValueType.DOUBLE, 1));
    }


    /**
     * ⭐⭐ <b>The property the 2026-09-18 ruling exists to protect, and which nothing pinned before
     * it.</b> On a column that exists, {@code resolvedString} must let a caller tell a
     * <em>missing</em> character cell from a <em>stored empty string</em>. Both directions are
     * asserted, and the two answers are asserted to differ — a one-sided test would stay green if
     * the retired step were ever re-applied, since it would make every blank {@code ""}.
     *
     * <p>
     * ⚠ {@code charColumn()} carries all three spellings of "blank" a character column can hold: a
     * {@code MissingValue} (row 1), a stored {@code ""} (row 2) and a raw {@code null} (row 3).
     * Only row 2 is a value.
     * </p>
     */
    @Test
    void missingAndEmptyStringStayDistinguishable()
    {
        CachedDataTableColumn chars = charColumn();

        String stored = ScalarSemantics.resolvedString(chars, DataValueType.STRING, 2);
        String marker = ScalarSemantics.resolvedString(chars, DataValueType.STRING, 1);
        String bareNull = ScalarSemantics.resolvedString(chars, DataValueType.STRING, 3);

        // Direction 1 — a stored empty string is a PRESENT value and stays "".
        assertEquals("", stored, "a stored \"\" is a value: it must resolve to \"\", never null");

        // Direction 2 — a missing cell, however it is spelled, is NOT the empty string.
        assertNull(marker, "a MissingValue in a character column must not resolve to \"\"");
        assertNull(bareNull, "a raw null in a character column must not resolve to \"\"");

        // …and the two must be TELLABLE APART, which is the ruling's own wording: "missing is not
        // empty string". This is the assertion that reds if the retired blindness step is ever
        // re-applied (aDeclaredType == STRING ? "" : null), because it would collapse both to "".
        assertNotEquals(stored, marker,
                "missing and the empty string must stay distinguishable in the text channel");
        assertNotEquals(stored, bareNull, "…for a raw null too");
    }


    /**
     * ⛔⛔ <b>A deliberate NON-change, pinned so a later ruling on it is an act and not a drift.</b>
     *
     * <p>
     * {@code Primitives.empty} / {@code non_empty} route through {@link ScalarSemantics#isMissing}
     * → {@code DataValueSupport.isEmptyOrMissing}, which folds {@code null}, a {@code MissingValue}
     * <b>and</b> {@code ""} into one "blank" answer — so a rule written {@code empty(X)} cannot
     * tell them apart. The 2026-09-18 ruling ("missing is not empty string") is about
     * {@link ScalarSemantics#resolvedString}'s text channel and says nothing about this fold;
     * whether it must change too is a <b>separate owner question, open</b>.
     * </p>
     * <p>
     * ⇒ This test asserts the fold is <b>exactly as it was</b>: all three blank spellings fire
     * {@code empty()}, and only a real value does not. If a later change narrows
     * {@code isEmptyOrMissing} this reds, which is the point — the narrowing must be a ruling, not
     * a side effect of some other edit.
     * </p>
     */
    @Test
    void theBlankFoldBehindEmptyIsUnchanged()
    {
        CachedDataTableColumn chars = charColumn();
        ColumnVector v = new ColumnVector("C", chars, DataValueType.STRING);

        BitSet empty = Primitives.empty(v, 4);
        assertFalse(empty.get(0), "\"A\" is a value — empty() must not fire");
        assertTrue(empty.get(1), "a MissingValue is blank under empty() — unchanged");
        assertTrue(empty.get(2), "a stored \"\" is blank under empty() — unchanged, and this is "
                + "exactly the fold the 2026-09-18 ruling does NOT touch");
        assertTrue(empty.get(3), "a raw null is blank under empty() — unchanged");

        // non_empty is the exact complement, and stays so.
        BitSet nonEmpty = Primitives.nonEmpty(v, 4);
        for (int r = 0; r < 4; r++)
        {
            assertEquals(!empty.get(r), nonEmpty.get(r),
                    "non_empty must stay the complement of empty at row " + r);
        }
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
     * ⛔ <b>RETIRED (owner, 2026-09-18).</b> This paragraph used to warn that Q16 clearing the
     * {@code date_*} blocker did not license flipping {@code resolvedString}'s blank branch to
     * {@code aDeclaredType == STRING ? "" : null}, that being Q5's decision. Q5 has now been
     * decided <b>against</b> the flip — "missing is not empty string" — so there is no pending
     * option left to warn about. What this test still pins is the <b>split</b> above.
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
