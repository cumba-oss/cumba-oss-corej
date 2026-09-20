package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeyMatchExpandedLookup}'s <b>typed</b> value channel — the row-expanded key
 * join, which is the corpus path for a plain named {@code Match_Dataset} entry.
 *
 * <p>
 * ⚠⚠ <b>This class exists because the site had no direct test at all.</b>
 * {@code KeyMatchExpandedLookup} was reached only through {@code MatchedFlagExecutionTest} (which
 * asserts the {@code _matched_} flag, not cell values), so its {@code lookupValue} arms — including
 * the absent-column arm that is the THIRD production instance of the same defect — were covered
 * only indirectly. That is exactly how the §9c fix could have been applied to the two
 * implementations the plan named and silently missed this one.
 * </p>
 */
class KeyMatchExpandedLookupTest
{

    /**
     * One child table, three expanded rows: 0 → child row 0, 1 → child row 1, 2 → a left-join row
     * with no match. {@code ARM} carries a present value and a missing one, {@code SITE} carries a
     * genuinely stored empty string.
     */
    private static KeyMatchExpandedLookup fixture()
    {
        IDataTable child = MockTable.of().col("ARM", "A", null).col("SITE", "", "S2").name("DM")
                .build();
        return new KeyMatchExpandedLookup("DM", child, new long[]
        {
                0, 1, -1
        });
    }


    /**
     * The three not-supplied cases stay apart, exactly as {@link DatasetLookup} keeps them apart
     * (D72 / D72a-1 / D75a): a bound cell passes through with its own identity, an unmatched row
     * reads the column's TYPE default, and an absent column reads the RULE's expected default.
     */
    @Test
    @DisplayName("the typed channel keeps present, unmatched and absent apart")
    void typedChannelKeepsTheNotSuppliedCasesApart()
    {
        KeyMatchExpandedLookup lk = fixture();

        IDataValue present = lk.lookupValue(null, 0, "ARM");
        assertFalse(present.isMissingOrInvalid());
        assertEquals("A", present.getValueAsString(), "the bound child cell, verbatim");

        // A SUPPLIED missing is a missing (D75a case 4) — distinct from an absent column's "".
        assertTrue(lk.lookupValue(null, 1, "ARM").isMissingOrInvalid());

        // An unmatched left-join row reads the column's own declared TYPE default (D72a-1) — for a
        // char column that is "", and it comes from the metadata, not from the rule.
        IDataValue unmatched = lk.lookupValue(null, 2, "ARM");
        assertFalse(unmatched.isMissingOrInvalid(), "char type default is a present \"\"");
        assertEquals("", unmatched.getValueAsString());

        // A stored "" is a present value.
        assertFalse(lk.lookupValue(null, 0, "SITE").isMissingOrInvalid());
        assertEquals("", lk.lookupValue(null, 0, "SITE").getValueAsString());

        // ⚠ The override must travel with declaredTypeOf: this class publishes a real type (it
        // holds exactly ONE child table), so leaving the values on JoinLookup's text default would
        // publish a vector whose meta and cells disagree.
        assertEquals(DataValueType.STRING, lk.declaredTypeOf("ARM"));
    }


    /**
     * ⭐⭐ §9c DOTTED PARITY — see
     * {@code DatasetLookupTypedValueTest.absentJoinedColumnTakesTheRuleExpectedDefault} for the
     * ruling. Arm 1 REDS without the fix; arms 2 and 3 are green either way, deliberately — arm 2
     * stops the fix becoming "make everything MIS" (keeping D96a closed) and arm 3 is the
     * anti-corruption control for a genuinely stored empty string.
     */
    @Test
    @DisplayName("§9c: an absent child column reads MIS when the rule expects a number")
    void absentChildColumnTakesTheRuleExpectedDefault()
    {
        KeyMatchExpandedLookup lk = fixture();

        // ARM 1 — numeric expected: MIS, the same answer an absent primary numeric column gives.
        IDataValue numericAbsent = lk.lookupValue(null, 0, "NOSUCH", true);
        assertTrue(numericAbsent.isMissingOrInvalid(),
                "⛔ §9c: a numeric-expected absent joined column is MIS, never a present \"\"");
        assertEquals(MissingValue.MIS, numericAbsent.getValue());

        // ARM 2 — CONTROL: no expectation, still the present "" (D34 #3, D96a stays closed). The
        // three-argument form delegates with false, so every legacy caller is unmoved.
        IDataValue charAbsent = lk.lookupValue(null, 0, "NOSUCH", false);
        assertFalse(charAbsent.isMissingOrInvalid());
        assertEquals("", charAbsent.getValueAsString());
        assertFalse(lk.lookupValue(null, 0, "NOSUCH").isMissingOrInvalid());

        // ARM 3 — CONTROL: a genuinely STORED "" stays a present value under either flag, because
        // the column EXISTS and the expectation never reaches a present cell.
        IDataValue storedEmpty = lk.lookupValue(null, 0, "SITE", true);
        assertFalse(storedEmpty.isMissingOrInvalid(),
                "⛔ a stored empty string is a PRESENT value, expectation or no expectation");
        assertEquals("", storedEmpty.getValueAsString());

        // ⚠ And the flag must NOT leak into the unmatched-row arm: that one has real metadata and
        // the column's declared type is the more specific answer (a char column stays "").
        assertFalse(lk.lookupValue(null, 2, "ARM", true).isMissingOrInvalid(),
                "⛔ numericExpected must not override a PRESENT column's own declared type");
    }
}
