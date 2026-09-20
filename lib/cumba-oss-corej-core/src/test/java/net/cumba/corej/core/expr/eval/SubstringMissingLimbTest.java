package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ <b>R2-20 — the substring family answers FALSE once an operand is a genuine missing</b> (owner,
 * 2026-09-17: <i>"all three return false once one (or more) parameter is missing."</i>).
 * {@code contains} / {@code starts_with} / {@code ends_with} become the fifth, sixth and seventh
 * sites of D13's principle, <b>a {@link MissingValue} is not a string</b>.
 *
 * <p>
 * ⚠⚠ <b>Every test here pins the ABSENT / BLANK / GENUINE-MISSING triple</b>, because the whole
 * content of the ruling is <em>where the boundary sits</em> (D96c): it is
 * {@link TypedValue#missingIdentityOf} / {@link TypedValue#missing()} and <b>not</b>
 * {@code ScalarSemantics.isMissing}. A blank character cell is a present {@code ""} (D34 #1), an
 * absent character column is a column of empty strings (D34 #3 / D76 — the compiler mints
 * {@code ConstVector.of("")} for it), and {@code "".contains("")} is legitimately <b>true</b>.
 * Widening the arm to {@code isMissing} reds the blank and absent rows of every test below —
 * measured, not assumed.
 * </p>
 *
 * <p>
 * ⚑ <b>{@code MockTable} cannot express a missing identity</b> — {@code colSasMissing} only ever
 * yields {@link MissingValue#MIS} and a {@code colDouble} carrying the encoded NaN is not decoded
 * as missing by the mock cell at all. A typed {@link ComputedVector} over a
 * {@link DataValueMissing} is the only shape that carries one, so that is what {@link #missing} and
 * {@link #cells} build. ⚠ And {@code MockTable.col(name, (String) null)} is NOT a blank: a real
 * character buffer substitutes {@code ""} for {@code null}, so a real char blank is a present
 * {@code ""} — the blank controls here use a literal {@code ""} cell for exactly that reason.
 * </p>
 */
class SubstringMissingLimbTest
{

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /** A one-row vector carrying a genuine {@link MissingValue} identity. */
    private static Vector missing(MissingValue identity)
    {
        return ComputedVector.typed(1, DataValueType.STRING, _ -> new DataValueMissing(identity));
    }


    /**
     * A vector over explicit cells: {@code null} means a genuine missing ({@code MissingValue.MIS}
     * via {@link DataValueMissing}), never the {@code ""} a real character buffer would substitute.
     */
    private static Vector cells(String... values)
    {
        return ComputedVector.typed(values.length, DataValueType.STRING,
                row -> values[row] == null ? new DataValueMissing(MissingValue.MIS)
                        : DataValues.of(values[row]));
    }


    /**
     * ⭐ <b>The NEEDLE position — the row shape the ruling actually moves.</b> Before R2-20 a
     * missing needle folded to {@code ""} ("decision #1"), so {@code s.contains("")} held and
     * {@code contains(X, MISSINGCOL)} fired on <b>every</b> row. It now answers false.
     */
    @Test
    void needlePosition_absentBesideBlankBesideGenuineMissing()
    {
        IDataTable t = MockTable.of().col("X", "HELLO").build();
        Vector x = col(t, "X");
        Vector blankNeedle = cells(""); // a present empty string — a blank character cell
        Vector absentNeedle = ConstVector.of(""); // what the compiler mints for an absent column
        Vector missingNeedle = missing(MissingValue.MIS);

        assertEquals(bits(0), Primitives.contains(x, blankNeedle, 1, false),
                "BLANK needle is a present \"\" — \"HELLO\".contains(\"\") is TRUE (D34 #1)");
        assertEquals(bits(0), Primitives.contains(x, absentNeedle, 1, false),
                "ABSENT needle folds to \"\" (D76) and answers exactly as the blank does");
        assertEquals(new BitSet(), Primitives.contains(x, missingNeedle, 1, false),
                "MISSING needle ⇒ FALSE (R2-20/D13) — it was TRUE on every row before the ruling");

        assertEquals(bits(0), Primitives.startsWith(x, blankNeedle, 1), "blank control");
        assertEquals(bits(0), Primitives.startsWith(x, absentNeedle, 1), "absent control");
        assertEquals(new BitSet(), Primitives.startsWith(x, missingNeedle, 1),
                "starts_with — the sixth site of D13");

        assertEquals(bits(0), Primitives.endsWith(x, blankNeedle, 1), "blank control");
        assertEquals(bits(0), Primitives.endsWith(x, absentNeedle, 1), "absent control");
        assertEquals(new BitSet(), Primitives.endsWith(x, missingNeedle, 1),
                "ends_with — the seventh site of D13");
    }


    /**
     * ⚠ <b>The stated consequence</b>: because the predicate is false and {@code negate} flips it —
     * exactly as D13's regex limb does — {@code not contains(X, MISSINGNEEDLE)} now fires on every
     * row, where before the ruling the <em>un</em>-negated form did. The owner accepted this; the
     * gain is consistency with D13's other four sites, and {@code not empty(...)} remains the
     * author's guard either way.
     */
    @Test
    void negatedFormFiresOnEveryRowForAMissingNeedle()
    {
        IDataTable t = MockTable.of().col("X", "HELLO").build();
        Vector x = col(t, "X");
        assertEquals(bits(0), Primitives.contains(x, missing(MissingValue.MIS), 1, true),
                "does_not_contain against a missing needle FIRES (negate flips the false)");
        assertEquals(new BitSet(), Primitives.contains(x, cells(""), 1, true),
                "…and against a BLANK needle it stays silent — the boundary, in one pair");
    }


    /**
     * ⭐ The SUBJECT position. A non-empty needle already answered {@code negate} by a different
     * route ({@code "".contains("ABC")} is false), so the only subject row that moves is the one
     * probed with an <b>empty</b> needle — where {@code "".contains("")} used to be true.
     */
    @Test
    void subjectPosition_absentBesideBlankBesideGenuineMissing()
    {
        Vector subject = cells("HELLO", "", null); // present · blank · genuine missing
        Vector absentSubject = ConstVector.of("");

        assertEquals(bits(0, 1), Primitives.contains(subject, "", 3, false),
                "BLANK subject contains \"\" (row 1, D34 #1); the MISSING subject (row 2) does NOT");
        // ⚠⚠ The same triple through the VECTOR-needle overload — which is the ONE the corpus
        // actually reaches (BuiltinFunctions binds all three arities to it). Without this row the
        // D96c boundary in that overload's SUBJECT half is unpinned: widening it from
        // TypedValue.missingIdentityOf to ScalarSemantics.isMissing reds nothing. Measured: that
        // sabotage was GREEN until this assertion existed.
        assertEquals(bits(0, 1), Primitives.contains(subject, ConstVector.of(""), 3, false),
                "vector-needle overload, same triple: blank yes, missing no");
        assertEquals(bits(0, 1), Primitives.startsWith(subject, ConstVector.of(""), 3),
                "vector-needle starts_with, same triple");
        assertEquals(bits(0, 1), Primitives.endsWith(subject, ConstVector.of(""), 3),
                "vector-needle ends_with, same triple");
        assertEquals(bits(0), Primitives.contains(absentSubject, "", 1, false),
                "ABSENT subject folds to \"\" and answers as the blank does");
        assertEquals(bits(0, 1), Primitives.startsWith(subject, "", 3), "starts_with, same triple");
        assertEquals(bits(0, 1), Primitives.endsWith(subject, "", 3), "ends_with, same triple");

        assertEquals(bits(0), Primitives.contains(subject, "ELL", 3, false),
                "unchanged for a non-empty needle: only \"HELLO\" matches");
        assertEquals(bits(1, 2), Primitives.contains(subject, "ELL", 3, true),
                "…and does_not_contain still fires on BOTH the blank and the missing row");
    }


    /**
     * ⛔ <b>(a) The collection branch takes the same rule</b> (owner, explicitly): a genuinely
     * missing needle answers false before {@code membershipOperand} is consulted, so the
     * {@code $}-operation membership arm cannot disagree with the scalar one.
     */
    @Test
    void collectionBranchTakesTheSameRule()
    {
        ComputedVector sets = new ComputedVector(1, DataValueType.STRING, _ -> List.of("Y", "N"));
        assertEquals(new BitSet(), Primitives.contains(sets, missing(MissingValue.MIS), 1, false),
                "a MISSING needle is a member of no list (R2-20/D13, the collection branch)");
        assertEquals(bits(0), Primitives.contains(sets, missing(MissingValue.MIS), 1, true),
                "…so the negated form fires, as everywhere else");
        assertEquals(bits(0), Primitives.contains(sets, ConstVector.of("Y"), 1, false),
                "control: a present needle still runs exact membership (EC-28(a))");
        assertEquals(new BitSet(), Primitives.contains(sets, cells(""), 1, false),
                "control: a BLANK needle is the empty string, which this list does not hold");
    }


    /**
     * ⛔ <b>(b) A missing ELEMENT inside the collection is a DIFFERENT position, and is out of
     * scope</b> (owner). It simply does not match the needle — {@code containsElement} folds it to
     * {@code ""} — and it does not make the whole call false.
     */
    @Test
    void aMissingElementInsideTheCollectionIsADifferentPositionAndIsOutOfScope()
    {
        ComputedVector withNull = new ComputedVector(1, DataValueType.STRING,
                _ -> Arrays.asList("N", null));
        assertEquals(new BitSet(), Primitives.contains(withNull, ConstVector.of("Y"), 1, false),
                "the null element does not match \"Y\" — and the call is NOT false-by-ruling");
        assertEquals(bits(0), Primitives.contains(withNull, ConstVector.of("N"), 1, false),
                "…proved by the sibling member still matching: the call was evaluated, not shorted");
        assertEquals(bits(0), Primitives.contains(withNull, ConstVector.of(""), 1, false),
                "and the null element still folds to \"\" — unchanged by R2-20");
    }

}
