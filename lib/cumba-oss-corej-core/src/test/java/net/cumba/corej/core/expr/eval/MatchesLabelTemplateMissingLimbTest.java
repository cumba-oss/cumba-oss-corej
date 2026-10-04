package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code matches_label_template(label, template)} — {@code PLAN-library-template-label-resolution}
 * T2 review L2: the MissingValue contract of {@link Primitives#matchesLabelTemplate}, pinned
 * directly. A genuine {@link MissingValue} in EITHER operand answers {@code false} — the substring
 * limb's contract (D13 / R2-20, {@link SubstringMissingLimbTest}): a column without a template is
 * judged by the rule's own {@code not empty(label_template(…))} guard, never by this function. A
 * blank cell is a present {@code ""} (D34 #1) and is matched as text, which is the control that
 * shows the boundary sits at the missing IDENTITY, not at {@code ScalarSemantics.isMissing}.
 */
class MatchesLabelTemplateMissingLimbTest
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


    /** A vector over explicit cells: {@code null} is a genuine missing, never a blank. */
    private static Vector cells(String... values)
    {
        return ComputedVector.typed(values.length, DataValueType.STRING,
                row -> values[row] == null ? new DataValueMissing(MissingValue.MIS)
                        : DataValues.of(values[row]));
    }


    /**
     * A missing label against a template that accepts the EMPTY text ({@code ...}): read as
     * {@code ""} the label would conform, so only the missing arm keeps it false.
     */
    @Test
    void aMissingLabelAnswersFalseEvenWhereTheEmptyTextWouldConform()
    {
        assertEquals(bits(0), Primitives.matchesLabelTemplate(cells(""), cells("..."), 1),
                "control: the empty text conforms to '...'");
        assertEquals(new BitSet(),
                Primitives.matchesLabelTemplate(cells((String) null), cells("..."), 1),
                "label MissingValue, template present");
    }


    /**
     * A missing template against a blank label: read as {@code ""} the template would accept the
     * blank, and read as its {@code String.valueOf} a label {@code "null"} — only the missing arm
     * keeps both false.
     */
    @Test
    void aMissingTemplateAnswersFalseHoweverItWouldPrint()
    {
        assertEquals(new BitSet(),
                Primitives.matchesLabelTemplate(cells(""), cells((String) null), 1),
                "a blank label, template MissingValue");
        assertEquals(new BitSet(),
                Primitives.matchesLabelTemplate(cells("null"), cells((String) null), 1),
                "a label spelling the missing's print form, template MissingValue");
    }


    @Test
    void bothMissingAnswersFalse()
    {
        assertEquals(new BitSet(),
                Primitives.matchesLabelTemplate(cells((String) null), cells((String) null), 1),
                "both MissingValue");
    }


    @Test
    void presentOperandsAreMatchedAsTextBlankIncluded()
    {
        assertEquals(bits(0), Primitives.matchesLabelTemplate(cells("Analysis Flag 01"),
                cells("Analysis Flag 01 ..."), 1), "control: a conforming label");
        assertEquals(new BitSet(), Primitives.matchesLabelTemplate(cells("Analysis Flag 012"),
                cells("Analysis Flag 01 ..."), 1), "control: a non-conforming label");
        assertEquals(bits(0), Primitives.matchesLabelTemplate(cells(""), cells(""), 1),
                "a blank is a present \"\" on both sides, matched as text");
        assertEquals(bits(0, 2),
                Primitives.matchesLabelTemplate(cells("Analysis Flag 01", null, "Analysis Flag 01"),
                        cells("Analysis Flag 01 ...", "Analysis Flag 01 ...",
                                "Analysis Flag 01 ..."),
                        3),
                "per row: only the missing row answers false");
    }
}
