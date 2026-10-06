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
 * Unit tests for {@link MergedViewLookup}'s <b>typed</b> value channel — the merged view's keyed
 * part, since W6 of {@code PLAN-merged-evaluation-table} the one production {@link JoinLookup}
 * (this class carried the retired row-expanded lookup's tests, which pinned the same three cases;
 * they now pin the view).
 */
class MergedViewLookupTypedChannelTest
{

    /** The primary: three records, the third without a DM partner. */
    private static final IDataTable PRIMARY = MockTable.of().name("AE")
            .col("USUBJID", "P0", "P1", "P2").build();

    /**
     * One child table, three merged rows: 0 → child row 0, 1 → child row 1, 2 → a left-join row
     * with no match. {@code ARM} carries a present value and a missing one, {@code SITE} carries a
     * genuinely stored empty string.
     */
    private static JoinLookup fixture()
    {
        IDataTable child = MockTable.of().col("USUBJID", "P0", "P1").col("ARM", "A", null)
                .col("SITE", "", "S2").name("DM").build();
        return ExecCalls.viewLookup(PRIMARY, "DM", child, java.util.List.of("USUBJID"));
    }


    /**
     * The three not-supplied cases stay apart (the retired key-join lookups kept them apart too)
     * (D72 / D72a-1 / D75a): a bound cell passes through with its own identity, an unmatched row
     * reads the column's TYPE default, and an absent column reads the RULE's expected default.
     */
    @Test
    @DisplayName("the typed channel keeps present, unmatched and absent apart")
    void typedChannelKeepsTheNotSuppliedCasesApart()
    {
        JoinLookup lk = fixture();

        IDataValue present = lk.lookupValue(PRIMARY, 0, "ARM", false);
        assertFalse(present.isMissingOrInvalid());
        assertEquals("A", present.getValueAsString(), "the bound child cell, verbatim");

        // A SUPPLIED missing is a missing (D75a case 4) — distinct from an absent column's "".
        assertTrue(lk.lookupValue(PRIMARY, 1, "ARM", false).isMissingOrInvalid());

        // An unmatched left-join row reads the column's own declared TYPE default (D72a-1) — for a
        // char column that is "", and it comes from the metadata, not from the rule.
        IDataValue unmatched = lk.lookupValue(PRIMARY, 2, "ARM", false);
        assertFalse(unmatched.isMissingOrInvalid(), "char type default is a present \"\"");
        assertEquals("", unmatched.getValueAsString());

        // A stored "" is a present value.
        assertFalse(lk.lookupValue(PRIMARY, 0, "SITE", false).isMissingOrInvalid());
        assertEquals("", lk.lookupValue(PRIMARY, 0, "SITE", false).getValueAsString());

        // ⚠ The override must travel with declaredTypeOf: the part publishes a real type (it reads
        // exactly ONE child table), so leaving the values on JoinLookup's text default would
        // publish a vector whose meta and cells disagree.
        assertEquals(DataValueType.STRING, lk.declaredTypeOf("ARM"));
    }


    /**
     * ⭐⭐ §9c DOTTED PARITY (owner ruling, 2026-09-18): a joined variable behaves like a primary one
     * in every respect but the dotted access form, so an absent joined column takes the RULE's
     * expected default exactly as an absent primary column does. Arm 1 REDS without the fix; arms 2
     * and 3 are green either way, deliberately — arm 2 stops the fix becoming "make everything MIS"
     * (keeping D96a closed) and arm 3 is the anti-corruption control for a genuinely stored empty
     * string.
     */
    @Test
    @DisplayName("§9c: an absent child column reads MIS when the rule expects a number")
    void absentChildColumnTakesTheRuleExpectedDefault()
    {
        JoinLookup lk = fixture();

        // ARM 1 — numeric expected: MIS, the same answer an absent primary numeric column gives.
        IDataValue numericAbsent = lk.lookupValue(PRIMARY, 0, "NOSUCH", true);
        assertTrue(numericAbsent.isMissingOrInvalid(),
                "⛔ §9c: a numeric-expected absent joined column is MIS, never a present \"\"");
        assertEquals(MissingValue.MIS, numericAbsent.getValue());

        // ARM 2 — CONTROL: no expectation, still the present "" (D34 #3, D96a stays closed).
        IDataValue charAbsent = lk.lookupValue(PRIMARY, 0, "NOSUCH", false);
        assertFalse(charAbsent.isMissingOrInvalid());
        assertEquals("", charAbsent.getValueAsString());

        // ARM 3 — CONTROL: a genuinely STORED "" stays a present value under either flag, because
        // the column EXISTS and the expectation never reaches a present cell.
        IDataValue storedEmpty = lk.lookupValue(PRIMARY, 0, "SITE", true);
        assertFalse(storedEmpty.isMissingOrInvalid(),
                "⛔ a stored empty string is a PRESENT value, expectation or no expectation");
        assertEquals("", storedEmpty.getValueAsString());

        // ⚠ And the flag must NOT leak into the unmatched-row arm: that one has real metadata and
        // the column's declared type is the more specific answer (a char column stays "").
        assertFalse(lk.lookupValue(PRIMARY, 2, "ARM", true).isMissingOrInvalid(),
                "⛔ numericExpected must not override a PRESENT column's own declared type");
    }
}
