package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ A {@code MissingValue} <b>can</b> be a member of a list, and it is a member of exactly the
 * list that contains <b>that same missing</b>.
 *
 * <p>
 * Owner, 2026-09-21: <i>"A MissingValue is a value and can be in a list, therefore the check if a
 * MissingValue is in a list is a valid check, for string and for numeric columns missing values can
 * be in a list."</i> ⇒ {@code D81} (<i>"`in` IS DEFINED AS A DISJUNCTION OF `==`"</i>) composed
 * with {@code D34 #5-2} (<i>two missings are equal iff they are the same missing</i>).
 * </p>
 *
 * <p>
 * ⛔⛔ <b>What this class replaces.</b> {@link Primitives#isMember(IDataValue, Set, boolean)} carried
 * a blanket early return for any missing probe, commented <i>"a genuine MissingValue is a member of
 * no list, "" included. Same arm as equalsTypedAware's"</i>. Both halves were false: {@code ==}
 * delegates to {@link Primitives#equalsWithMissing}, where the same missing IS equal, and
 * {@code D13} says a missing equals no <b>string literal</b> — never that it is a member of no
 * list. The over-generalisation survived because <b>an authored list has no spelling for a missing
 * member</b>, so the wrong case was unreachable. That is a reachability argument frozen as a
 * semantic.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why these are unit assertions and not a corpus differential.</b> Measured 2026-09-21:
 * <b>no</b> membership set in the shipped corpus can contain a missing — list literals have no
 * spelling for one, and {@code evalDistinct} / {@code evalDistinctGrouped} filter both missings and
 * empties out at construction. ⇒ a corpus run cannot see this change at all, and a green one would
 * be the silent-self-disarming condition rather than evidence. These fixtures put a missing in a
 * member set directly, which is the only way to observe it.
 * </p>
 */
class MissingAsMemberTest
{

    /**
     * A cell carrying a genuine {@link MissingValue}.
     *
     * <p>
     * ⚠⚠ <b>Only a {@code null} entry becomes a missing</b>, and it is always
     * {@code MissingValue.MIS} — measured in {@code MockTable.mockSasMissingDataValue}: a
     * {@code null} yields {@code MIS} (which renders {@code "."}), while a string such as
     * {@code ".A"} is a <b>present string</b>. The first version of this helper passed {@code "."}
     * and got a present cell; the control below caught it on six of seven cases before any
     * conclusion was drawn.
     * </p>
     *
     * <p>
     * ⇒ the testkit cannot put a SPECIFIC marker in a cell, so marker-distinctness is asserted from
     * the <b>member</b> side ({@link #aMissingIsNotAMemberOfAListContainingADifferentMissing()}),
     * where any {@link MissingValue} can be named directly. ⚠ A PRESENT peer sits in row 0 because
     * an all-missing {@code colSasMissing} column is classified numeric by the testkit.
     * </p>
     */
    private static IDataValue missingCell()
    {
        IDataTable t = MockTable.of().colSasMissing("X", "PRESENT", null).build();
        IDataValue dv = t.getColumn(0).getDataValue(1L);
        assertTrue(TypedValue.missingIdentityOf(dv) != null,
                "fixture control: the cell must arrive as a genuine MissingValue, not a present"
                        + " string — every assertion below is vacuous otherwise");
        return dv;
    }


    private static IDataValue presentCell(String value)
    {
        IDataTable t = MockTable.of().col("X", value).build();
        IDataValue dv = t.getColumn(0).getDataValue(0L);
        assertTrue(TypedValue.missingIdentityOf(dv) == null,
                "fixture control: '" + value + "' must be a PRESENT value");
        return dv;
    }


    /** ⭐ The headline: the same marker matches. This was {@code false} before 2026-09-21. */
    @Test
    void aMissingIsAMemberOfAListContainingTheSameMissing()
    {
        Primitives.MemberSet set = Primitives.MemberSet.of(List.of(MissingValue.MIS), false);
        assertTrue(Primitives.isMember(missingCell(), set, false),
                "D81 + D34 #5-2: `MIS in [MIS]` is `MIS == MIS`, which is true. A false here is the"
                        + " retired blanket early return");
    }


    /** ⛔ {@code JKM R5}'s other half: a different marker does NOT match. */
    @Test
    void aMissingIsNotAMemberOfAListContainingADifferentMissing()
    {
        Primitives.MemberSet set = Primitives.MemberSet.of(List.of(MissingValue.MIS_A), false);
        assertFalse(Primitives.isMember(missingCell(), set, false),
                "two DIFFERENT markers are different values — .A is not the generic missing");
    }


    /**
     * ⛔⛔ The collision case, and the reason the set keeps an identity rather than a rendering:
     * {@code MissingValue.toString()} renders {@code "."}, so a rendered member set would have made
     * this pass.
     */
    @Test
    void aMissingIsNotAMemberOfAListContainingAPresentDot()
    {
        Primitives.MemberSet set = Primitives.MemberSet.of(List.of("."), false);
        assertTrue(set.missing().isEmpty(), "control: the '.' member must be classified PRESENT");
        assertFalse(Primitives.isMember(missingCell(), set, false),
                "a MissingValue is not the text '.'. A true here means the member set rendered the"
                        + " missing instead of keeping its identity — the JKM R5 collision class");
    }


    /** ⛔ {@code D11}/{@code D12}: a missing is not the empty string, in a list either. */
    @Test
    void aMissingIsNotAMemberOfAListContainingTheEmptyString()
    {
        Primitives.MemberSet set = Primitives.MemberSet.of(List.of(""), false);
        assertFalse(Primitives.isMember(missingCell(), set, false),
                "D13 keeps this arm: a missing equals no string literal, \"\" included");
    }


    /** ⭐ And the present side is untouched: a stored blank still matches a blank member. */
    @Test
    void aPresentEmptyStringIsStillAMemberOfAListContainingIt()
    {
        Primitives.MemberSet set = Primitives.MemberSet.of(List.of(""), false);
        assertTrue(Primitives.isMember(presentCell(""), set, false),
                "a stored empty string is a PRESENT value (D34 #1/#3) and matches an empty member");
    }


    /** ⭐ Nothing is a member of an empty set — including a missing. */
    @Test
    void nothingIsAMemberOfAnEmptySet()
    {
        assertFalse(Primitives.isMember(missingCell(), Primitives.MemberSet.EMPTY, false),
                "an empty set has no members at all");
        assertFalse(Primitives.isMember(presentCell("A"), Primitives.MemberSet.EMPTY, false),
                "control: the same holds for a present value");
    }


    /**
     * ⚠ The {@code Set<String>} overload keeps its early return, and this pins WHY: a string member
     * set cannot hold a missing, so there is nothing for a missing probe to match. ⛔ It is not the
     * retired claim — it is that claim's one true case.
     */
    @Test
    void theStringOnlyOverloadStillAnswersFalseBecauseNoStringMemberCanBeAMissing()
    {
        assertFalse(Primitives.isMember(missingCell(), Set.of(".", ""), false),
                "a Set<String> cannot carry a MissingValue member, so a missing probe matches"
                        + " nothing — D13's actual scope");
    }
}
