package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RelrecExpandedLookup} — the per-expanded-row RELREC join lookup.
 */
class RelrecExpandedLookupTest
{

    /**
     * Two target domains (AE ordinal 0, CM ordinal 1) plus two defensive ordinals (-1 and an
     * out-of-range 5) so every branch of {@link RelrecExpandedLookup#lookup} is exercised.
     */
    private static RelrecExpandedLookup fixture()
    {
        IDataTable ae = MockTable.of().col("AETERM", "HEADACHE", "NAUSEA")
                .col("AEDECOD", "HA", null).name("AE").build();
        IDataTable cm = MockTable.of().col("CMTERM", "ASPIRIN").col("CMTRT", "ASA").name("CM")
                .build();
        // expanded rows: 0->AE/0, 1->AE/1, 2->CM/0, 3->ord -1 (none), 4->ord 5 (out of range)
        int[] ord =
        {
                0, 0, 1, -1, 5
        };
        long[] tgtRow =
        {
                0, 1, 0, 0, 0
        };
        return new RelrecExpandedLookup(List.of(ae, cm), ord, tgtRow);
    }


    @Test
    void literalColumnLookup()
    {
        RelrecExpandedLookup lk = fixture();
        assertEquals("HEADACHE", lk.lookup(null, 0, "AETERM"));
        assertEquals("NAUSEA", lk.lookup(null, 1, "AETERM"));
        assertEquals("ASPIRIN", lk.lookup(null, 2, "CMTERM"));
    }


    @Test
    void wildcardResolvesPerTargetDomain()
    {
        RelrecExpandedLookup lk = fixture();
        // "**TERM" -> domainPrefix(AE)="AE" -> "AETERM" for ordinal 0
        assertEquals("HEADACHE", lk.lookup(null, 0, "**TERM"));
        // same suffix, different ordinal -> domainPrefix(CM)="CM" -> "CMTERM"
        assertEquals("ASPIRIN", lk.lookup(null, 2, "**TERM"));
        // second call hits the per-ordinal column-index cache
        assertEquals("NAUSEA", lk.lookup(null, 1, "**TERM"));
    }


    @Test
    void missingCellAndMissingColumnReturnNull()
    {
        RelrecExpandedLookup lk = fixture();
        // row 1 AEDECOD is blank -> missing-or-invalid -> null. ⛔ Resolving a MISSING character
        // cell to "" instead was the "blindness step"; the owner ruling of 2026-09-18 RETIRED it
        // ("missing is not empty string"), so null is settled here rather than interim.
        assertNull(lk.lookup(null, 1, "AEDECOD"));
        // column not present on the target -> colIdx < 0 -> null (cached)
        assertNull(lk.lookup(null, 0, "NOSUCH"));
        assertNull(lk.lookup(null, 0, "NOSUCH"));
    }


    @Test
    void outOfRangeNegativeNullColumnAndBadOrdinalReturnNull()
    {
        RelrecExpandedLookup lk = fixture();
        assertNull(lk.lookup(null, -1, "AETERM"));
        assertNull(lk.lookup(null, 99, "AETERM"));
        assertNull(lk.lookup(null, 0, null));
        assertNull(lk.lookup(null, 3, "AETERM")); // ordinal -1
        assertNull(lk.lookup(null, 4, "AETERM")); // ordinal 5 >= size
    }


    @Test
    void hasColumnDistinguishesAbsentFromMissing()
    {
        RelrecExpandedLookup lk = fixture();
        // present columns (literal + wildcard-resolved) -> true
        assertTrue(lk.hasColumn(null, 0, "AETERM"));
        assertTrue(lk.hasColumn(null, 0, "**TERM")); // -> AETERM
        assertTrue(lk.hasColumn(null, 2, "**TRT")); // CM -> CMTRT
        // absent column (wildcard resolves to a non-existent parent column) -> false
        assertFalse(lk.hasColumn(null, 0, "**TRT")); // AE has no AETRT
        // defensive: out-of-range / bad ordinal / null -> false
        assertFalse(lk.hasColumn(null, -1, "AETERM"));
        assertFalse(lk.hasColumn(null, 99, "AETERM"));
        assertFalse(lk.hasColumn(null, 3, "AETERM")); // ordinal -1
        assertFalse(lk.hasColumn(null, 4, "AETERM")); // ordinal 5 >= size
        assertFalse(lk.hasColumn(null, 0, null));
    }


    @Test
    void datasetNameIsRelrec()
    {
        assertEquals("RELREC", fixture().getDatasetName());
    }


    @Test
    void lookupAllReturnsSingletonOrEmpty()
    {
        RelrecExpandedLookup lk = fixture();
        assertEquals(List.of("HEADACHE"), lk.lookupAll(null, 0, "AETERM"));
        assertTrue(lk.lookupAll(null, 1, "AEDECOD").isEmpty());
        assertTrue(lk.lookupAll(null, 0, "NOSUCH").isEmpty());
    }


    /**
     * ⭐⭐ The TYPED channel, and the three not-supplied cases it must keep APART
     * (PLAN-null-free-value-channel §3b).
     *
     * <p>
     * ⛔ This lookup used to inherit {@link JoinLookup}'s default {@code lookupValue}, which wraps
     * the {@link RelrecExpandedLookup#lookup} {@code String} channel — and that channel answers one
     * single {@code null} for "no such column", "no bound row" AND "present but missing", so every
     * one of them became one computed {@code MissingValue.MIS}. The assertions below are the
     * discriminations the default structurally cannot make; {@link DatasetLookup} and
     * {@link KeyMatchExpandedLookup} already made them (D72 / D72a-1 / D75a).
     * </p>
     *
     * <p>
     * ⚠ Read against {@code lookup}'s own assertions two tests up: every case below still answers
     * {@code null} on the text channel, deliberately and by ruling, which is precisely why no
     * String-channel test could ever have caught this.
     * </p>
     */
    @Test
    void lookupValueKeepsTheThreeNotSuppliedCasesApart()
    {
        RelrecExpandedLookup lk = fixture();

        // 1. A PRESENT value arrives as the target's own cell, not as cleaned text.
        IDataValue present = lk.lookupValue(null, 0, "AETERM");
        assertFalse(present.isMissingOrInvalid());
        assertEquals("HEADACHE", present.getValue(), "the target cell's own value, verbatim");

        // 2. An ABSENT column is a CONSTANT-VALUE column, and with NO numeric expectation (this
        // three-argument form delegates with false, §9c) its constant is "" — a PRESENT empty
        // string, never a missing. Exactly as DatasetLookup rules it. ⭐ The numeric arm of the
        // same site is pinned by absentColumnTakesTheRuleExpectedDefault below.
        IDataValue absent = lk.lookupValue(null, 0, "NOSUCH");
        assertFalse(absent.isMissingOrInvalid(),
                "⛔ absent ≠ missing: the inherited default answered MIS here, which phase 6c's"
                        + " D34 #5 order arm sorts below every value while \"\" is ordered normally"
                        + " (the D96a absent-vs-blank regression)");
        assertEquals("", absent.getValue(), "the char default is the empty string (D34 #3)");

        // 3. A PRESENT column whose cell is MISSING stays missing — distinct from case 2.
        IDataValue blank = lk.lookupValue(null, 1, "AEDECOD");
        assertTrue(blank.isMissingOrInvalid(), "a supplied missing is a missing, not \"\" (D75a)");

        // ⭐ And the pair that makes this test non-vacuous: cases 2 and 3 must DISAGREE. Under the
        // inherited default they were the same object shape, so any assertion that passed for one
        // passed for the other.
        assertTrue(absent.isMissingOrInvalid() != blank.isMissingOrInvalid(),
                "CONTROL: an absent column and a present-but-missing cell must not answer the"
                        + " same thing — that collapse WAS the defect");

        // Engine plumbing, outside the four value channels: no expanded row and no bound target,
        // so there is no column to take a type from — a genuinely computed MIS (D36 #8).
        assertEquals(MissingValue.MIS, lk.lookupValue(null, 99, "AETERM").getValue());
        assertEquals(MissingValue.MIS, lk.lookupValue(null, 3, "AETERM").getValue(), "ordinal -1");
        assertEquals(MissingValue.MIS, lk.lookupValue(null, 4, "AETERM").getValue(), "ordinal 5");
        assertEquals(MissingValue.MIS, lk.lookupValue(null, 0, null).getValue(), "null column");
    }


    /**
     * ⭐⭐ §9c DOTTED PARITY, the third production site of the absent-column arm — see
     * {@code DatasetLookupTypedValueTest.absentJoinedColumnTakesTheRuleExpectedDefault} for the
     * ruling and for why arm 1 REDS without the fix while arms 2 and 3 are green either way.
     *
     * <p>
     * ⭐ Worth pinning HERE specifically because this lookup binds a different target table per
     * expanded row: the DECLARED TYPE is therefore per-row-incoherent (which is why
     * {@code declaredTypeOf} stays {@code MISSING}), but the rule's EXPECTATION is a property of
     * the RULE and is constant for the whole vector, so passing it per row is well defined.
     * </p>
     */
    @Test
    void absentColumnTakesTheRuleExpectedDefault()
    {
        RelrecExpandedLookup lk = fixture();

        // ARM 1 — numeric expected: MIS, the same answer an absent primary numeric column gives.
        IDataValue numericAbsent = lk.lookupValue(null, 0, "NOSUCH", true);
        assertTrue(numericAbsent.isMissingOrInvalid(),
                "⛔ §9c: a numeric-expected absent column is MIS, never a present \"\"");
        assertEquals(MissingValue.MIS, numericAbsent.getValue());

        // ARM 2 — CONTROL: no expectation, still the present "" (D34 #3, D96a stays closed).
        IDataValue charAbsent = lk.lookupValue(null, 0, "NOSUCH", false);
        assertFalse(charAbsent.isMissingOrInvalid());
        assertEquals("", charAbsent.getValue());

        // ARM 3 — CONTROL: a genuinely STORED "" is a present value under either flag, because
        // the column exists and the expectation never reaches a present cell.
        IDataTable ae = MockTable.of().col("AETERM", "").name("AE").build();
        RelrecExpandedLookup stored = new RelrecExpandedLookup(List.of(ae), new int[]
        {
                0
        }, new long[]
        {
                0
        });
        IDataValue storedEmpty = stored.lookupValue(null, 0, "AETERM", true);
        assertFalse(storedEmpty.isMissingOrInvalid(),
                "⛔ a stored empty string is a PRESENT value, expectation or no expectation");
        assertEquals("", storedEmpty.getValue());
    }


    /**
     * The {@code **} prefix resolves per bound target in the typed channel too, not only in text.
     */
    @Test
    void lookupValueResolvesTheWildcardPrefixPerTargetDomain()
    {
        RelrecExpandedLookup lk = fixture();
        assertEquals("HEADACHE", lk.lookupValue(null, 0, "**TERM").getValue(), "AE -> AETERM");
        assertEquals("ASPIRIN", lk.lookupValue(null, 2, "**TERM").getValue(), "CM -> CMTERM");
        // AE has no AETRT, so this is case 2 above resolved through the wildcard.
        assertEquals("", lk.lookupValue(null, 0, "**TRT").getValue(), "absent -> the char default");
    }


    /**
     * ⚠ {@code declaredTypeOf} is deliberately NOT overridden alongside {@code lookupValue}, and
     * this pins that as a decision rather than an omission: a RELREC expansion binds a DIFFERENT
     * target table per expanded row, so no single declared type is correct for the vector.
     * {@code MISSING} means "unknown" (D1/D2) and leaves the operand un-gated by
     * {@code ColumnTypeGate} — the same disposition an absent column has. ⛔ Do not "complete" the
     * pair by answering row 0's type: {@link KeyMatchExpandedLookup} can only do that because it
     * holds exactly one child table.
     */
    @Test
    void declaredTypeStaysUnknownBecauseTheTargetVariesPerRow()
    {
        assertEquals(DataValueType.MISSING, fixture().declaredTypeOf("AETERM"),
                "unknown, not Char — a STRING answer would make ColumnTypeGate error a good"
                        + " numeric comparison");
    }
}
