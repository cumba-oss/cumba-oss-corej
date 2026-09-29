package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.testkit.SyntheticDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code FINDINGS-unowned-residuals} §E, closed by {@code PLAN-no-null-list-elements} review round
 * 1 (M-1): a {@link MissingValue} element of a list value is its <b>identity</b>, never its display
 * string. {@code MissingValue.MIS.toString()} is {@code "."}, so every consumer that compared list
 * elements by {@code toString()} made a missing element and a present {@code "."} the same member —
 * the {@code W38-A1} collision class. Under {@code D34 #5-2} two values are equal iff they are the
 * same missing or the same present value; under {@code D81} membership is a disjunction of that
 * equality. So a missing element meets only the same missing.
 *
 * <p>
 * One test per consumer that §E named, besides {@code Primitives.containsElement} (pinned in
 * {@code SubstringMissingLimbTest} / {@code PrimitivesTest}): {@code GroupSemantics.toStringSet} /
 * {@code toStringList} — now {@code toKeySet} / {@code toKeyList} ({@code shares_no_elements_with},
 * {@code is_not_ordered_subset_of}) — and {@code OperationExecutor.normalizeToList}
 * ({@code minus}). Each pairs the collision (red before the fix) with the same-missing control
 * (green before and after), so the test cannot pass by the consumer ignoring missing elements
 * altogether. Mockito-free.
 * </p>
 */
class MissingListElementIdentityTest
{

    private static final int ROWS = 2;

    private static final BitSet NONE = new BitSet();

    private static BitSet all()
    {
        BitSet b = new BitSet();
        b.set(0, ROWS);
        return b;
    }


    @Test
    void sharesNoElementsWithDoesNotMatchAMissingElementAgainstAPresentDot()
    {
        assertEquals(all(),
                GroupSemantics.sharesNoElementsVerdict(List.of(MissingValue.MIS), List.of("."),
                        ROWS),
                "MIS and a present \".\" share nothing — the disjoint verdict flags every row");
        assertEquals(NONE,
                GroupSemantics.sharesNoElementsVerdict(List.of(MissingValue.MIS),
                        List.of("A", MissingValue.MIS), ROWS),
                "control: the same missing IS a shared element (D34 #5-2)");
        assertEquals(all(),
                GroupSemantics.sharesNoElementsVerdict(List.of(MissingValue.MIS),
                        List.of(MissingValue.MIS_A), ROWS),
                "and a different missing is not (.A is not .)");
    }


    @Test
    void isNotOrderedSubsetOfDoesNotMatchAMissingElementAgainstAPresentDot()
    {
        assertEquals(all(),
                GroupSemantics.isNotOrderedSubsetVerdict(List.of("A", MissingValue.MIS),
                        List.of("A", "."), ROWS),
                "[A, MIS] is not an ordered subset of [A, \".\"] — every row is flagged");
        assertEquals(NONE,
                GroupSemantics.isNotOrderedSubsetVerdict(List.of("A", MissingValue.MIS),
                        List.of("A", "B", MissingValue.MIS), ROWS),
                "control: [A, MIS] IS an ordered subset of [A, B, MIS]");
    }


    @Test
    void minusKeepsAMissingElementThatOnlyAPresentDotWouldHaveRemoved()
    {
        assertEquals(List.of("X", MissingValue.MIS),
                minus(List.of("X", MissingValue.MIS), List.of(".")),
                "subtracting a present \".\" does not remove the missing element — and the"
                        + " missing survives as itself, not as its \".\" rendering");
        assertEquals(List.of("."), minus(List.of(".", MissingValue.MIS), List.of(MissingValue.MIS)),
                "subtracting MIS removes exactly MIS and keeps the present \".\"");
        assertEquals(List.of("X"),
                minus(List.of("X", MissingValue.MIS_A), List.of(MissingValue.MIS_A)),
                "control: the same missing is removed");
    }


    private static Object minus(List<Object> minuend, List<Object> subtrahend)
    {
        Operation op = new Operation();
        op.setId("$gap");
        op.setOperator("minus");
        op.setName("$a");
        op.setSubtract("$b");
        Map<String, Object> priors = new LinkedHashMap<>();
        priors.put("$a", minuend);
        priors.put("$b", subtrahend);
        SyntheticDataTable dm = new SyntheticDataTable("DM", List.of("STUDYID"), new String[]
        {
                "S1"
        }, 1);
        return OperationExecutor.executeOne(op, dm, _ -> null, null, priors, "T-E", null, null);
    }

}
