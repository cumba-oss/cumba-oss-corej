package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step B of {@code PLAN-joined-column-typing}: {@code JoinLookup} gains typed accessors
 * <b>beside</b> the text ones, and {@link DatasetLookup} answers them from the parent cell.
 *
 * <p>
 * Step B's safety argument was that the typed accessor agrees with the text accessor everywhere
 * except precision. ⭐ Since D72/D75a (typed-expression-engine phase 3) the typed accessor
 * <b>deliberately diverges</b> on the value layer: an unmatched row reads the column's type default
 * ({@code ""} char / {@code MIS} numeric, D72a-1) and a supplied {@code MissingValue} passes
 * through unchanged (D75a case 4) — while {@code lookup}'s text stays legacy for the report-text
 * and wildcard consumers. These tests pin both halves.
 * </p>
 *
 * <p>
 * ⚠ Real {@link CachedDataTableColumn}s, never {@code MockTable}: MockTable stubs
 * {@code IDataValue} and renders {@code raw.toString()}, so {@code getAsDoubleCleaned} never runs
 * and the precision assertion below could not fail.
 * </p>
 */
class DatasetLookupTypedValueTest
{

    private static final double BIG = 1.0E13 + 1;

    @Test
    @DisplayName("typed lookup keeps the exact value where the text lookup rounds to 12 digits")
    void typedLookupIsExact()
    {
        IDataTable primary = t("AE", col("USUBJID", DataValueType.STRING, "U1"));
        IDataTable joined = t("DM", col("USUBJID", DataValueType.STRING, "U1"),
                col("VAL", DataValueType.DOUBLE, BIG));
        DatasetLookup lk = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lk);

        // The text accessor still rounds -- unchanged on purpose, report text depends on it.
        assertEquals("10000000000000", lk.lookup(primary, 0, "VAL"));
        // The typed accessor does not.
        IDataValue dv = lk.lookupValue(primary, 0, "VAL");
        assertNotNull(dv);
        assertEquals(BIG, dv.getValueAsDouble());
        assertEquals(DataValueType.DOUBLE, lk.declaredTypeOf("VAL"));
    }


    @Test
    @DisplayName("typed lookup: D72 type defaults for unmatched rows, D75a pass-through for "
            + "supplied missings; text lookup unchanged")
    void typedFollowsD72WhereTextStaysLegacy()
    {
        IDataTable primary = t("AE", col("USUBJID", DataValueType.STRING, "U1", "U2", "U3"));
        IDataTable joined = t("DM", col("USUBJID", DataValueType.STRING, "U1", "U2"),
                col("ARM", DataValueType.STRING, "A", null),
                col("SITE", DataValueType.STRING, "S1", ""),
                col("AGE", DataValueType.LONG, 30L, null));
        DatasetLookup lk = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lk);

        // row 0 -- a present character value
        assertEquals("A", lk.lookup(primary, 0, "ARM"));
        assertEquals("A", lk.lookupValue(primary, 0, "ARM").getValueAsString());
        // row 1 -- a MATCHED char cell stored as a raw null. ⭐ RE-BASED on the owner ruling of
        // 2026-09-18 ("a present column can be missing, and missing is not empty string"), which
        // The datatable repository's d4edd59 landed: a null in a STRING buffer is MissingValue.MIS.
        // Both assertions read "" before, on the then-true premise that "this loader stores a null
        // char as '', a present value (D34 #1)". That premise is gone, so the cell is now MISSING
        // and the two channels split exactly as they do for a missing NUMERIC cell below: the text
        // accessor answers null, and the typed accessor passes the parent's own marker through
        // (D75a case 4). ⚑ Re-based rather than deleted — the same cell, the new polarity, is what
        // proves the ruling reaches the join channel.
        assertNull(lk.lookup(primary, 1, "ARM"),
                "a matched but MISSING char cell has no comparand: null, never \"\"");
        IDataValue suppliedMissingChar = lk.lookupValue(primary, 1, "ARM");
        assertNotNull(suppliedMissingChar);
        assertTrue(suppliedMissingChar.isMissingOrInvalid(),
                "a supplied char MissingValue passes through unchanged (D75a case 4)");
        // ⭐⭐ …and the OTHER direction, which is the half the ruling calls dangerous to lose: a
        // matched char cell holding a genuinely STORED empty string is a PRESENT value and must
        // still read "" through both channels. Without this the re-base above would be satisfied by
        // an engine that simply called every blank char cell missing.
        assertEquals("S1", lk.lookup(primary, 0, "SITE"));
        assertEquals("", lk.lookup(primary, 1, "SITE"),
                "a stored \"\" is a value — missing and empty string must stay distinguishable");
        IDataValue storedEmpty = lk.lookupValue(primary, 1, "SITE");
        assertNotNull(storedEmpty);
        assertFalse(storedEmpty.isMissingOrInvalid(), "a stored \"\" is NOT missing");
        assertEquals("", storedEmpty.getValueAsString());
        // row 1 -- a matched NUMERIC cell that IS a genuine missing: D75a case 4, the typed
        // accessor passes the parent's own cell through UNCHANGED, where it used to rewrite it
        // to null. The text accessor keeps legacy.
        assertNull(lk.lookup(primary, 1, "AGE"));
        IDataValue suppliedMissingNum = lk.lookupValue(primary, 1, "AGE");
        assertNotNull(suppliedMissingNum);
        assertTrue(suppliedMissingNum.isMissingOrInvalid(),
                "a supplied numeric MissingValue passes through, never null (D75a case 4)");
        // row 2 -- no matched joined row: D72/D72a-1, the TYPE default — a merged column behaves
        // like a primary column, so a char column reads "" and a numeric one MissingValue.MIS.
        assertNull(lk.lookup(primary, 2, "ARM"));
        IDataValue unmatchedChar = lk.lookupValue(primary, 2, "ARM");
        assertNotNull(unmatchedChar);
        assertEquals("", unmatchedChar.getValueAsString(),
                "an unmatched row reads the char default \"\" (D72a-1)");
        IDataValue unmatchedNum = lk.lookupValue(primary, 2, "AGE");
        assertNotNull(unmatchedNum);
        assertTrue(unmatchedNum.isMissingOrInvalid(),
                "an unmatched row reads the numeric default MIS (D72a-1)");
        // an absent column: the D76 type default. ⚑ This three-argument form carries NO
        // expectation and so delegates with numericExpected = false (§9c) — which is exactly why
        // the char answer below is still "". ⭐ The numeric arm of the same site is pinned by
        // absentJoinedColumnTakesTheRuleExpectedDefault; before §9c landed there was no way to ask
        // for it, and this line was the whole of the site's coverage.
        // So the typed accessor answers "" — a PRESENT empty string,
        // exactly like the unmatched-row char
        // default above, never null/MIS (the D96a order-arm regression: a null here became a
        // computed MissingValue.MIS the phase-6c D34 #5 arm sorted below every value, so
        // `date(X) > DM.ABSENT` fired on every row while a blank DM column reported nothing).
        // The TEXT accessor keeps legacy null — only the typed channel carries the default,
        // mirroring the unmatched-row split pinned above.
        assertNull(lk.lookup(primary, 0, "NOSUCH"));
        IDataValue absentChar = lk.lookupValue(primary, 0, "NOSUCH");
        assertNotNull(absentChar, "an absent foreign column folds to its D76 default, not null");
        assertEquals("", absentChar.getValueAsString(),
                "the absent-foreign-column default is the char default \"\" (D76 'otherwise char')");
        // ⛔ MISSING, not STRING: an absent column's type is UNKNOWN, and STRING would make it
        // indistinguishable from a character column -- which is what made the gate error
        // "DM.AGE is declared Char" on a Num column.
        assertEquals(DataValueType.MISSING, lk.declaredTypeOf("NOSUCH"));
    }


    /**
     * ⭐⭐ §9c DOTTED PARITY — the absent joined column takes the RULE's expected default.
     *
     * <p>
     * A joined variable and a primary variable differ in exactly one respect, the dotted access
     * form; in every other respect they behave identically, for available AND absent variables
     * alike (owner, 2026-09-18). An absent PRIMARY numeric column reads {@code MissingValue.MIS};
     * before this fix an absent JOINED one read {@code ""} whatever the expression expected, so
     * {@code DM.AGE} answered {@code MIS} when {@code DM} was missing entirely and {@code ""} when
     * {@code DM} was present but lacked {@code AGE} — same variable, same expression, two answers.
     * </p>
     *
     * <p>
     * ⛔ <b>Arm 1 is the discriminating one and REDS without the fix</b> (the old arm answered
     * {@code DataValueString("")}, whose {@code isMissingOrInvalid()} is {@code false}). Arm 2 is
     * the control that stops this becoming "make everything MIS" — it is green both before and
     * after, deliberately, and it is what keeps D96a closed. Arm 3 is the anti-corruption control:
     * a genuinely STORED empty string must stay a present value, which is precisely what a
     * call-site rewrite of {@code "" → MIS} would have destroyed.
     * </p>
     */
    @Test
    @DisplayName("§9c: an absent joined column reads MIS when the rule expects a number, \"\" otherwise")
    void absentJoinedColumnTakesTheRuleExpectedDefault()
    {
        IDataTable primary = t("AE", col("USUBJID", DataValueType.STRING, "U1"));
        IDataTable joined = t("DM", col("USUBJID", DataValueType.STRING, "U1"),
                col("SITE", DataValueType.STRING, ""));
        DatasetLookup lk = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lk);

        // ARM 1 — numeric expected: the absent column is a CONSTANT MissingValue.MIS, exactly as
        // an absent primary numeric column is. This is the assertion the defect failed.
        IDataValue numericAbsent = lk.lookupValue(primary, 0, "NOSUCH", true);
        assertTrue(numericAbsent.isMissingOrInvalid(),
                "⛔ §9c: a numeric-expected absent joined column is MIS, never a present \"\"");
        assertEquals(MissingValue.MIS, numericAbsent.getValue());

        // ARM 2 — CONTROL, no numeric expectation: still "", a PRESENT empty string. Green both
        // before and after the fix on purpose: the fix is type-directed, not a blanket MIS.
        IDataValue charAbsent = lk.lookupValue(primary, 0, "NOSUCH", false);
        assertFalse(charAbsent.isMissingOrInvalid(),
                "⛔ the char default stays a present \"\" — this is what keeps D96a closed");
        assertEquals("", charAbsent.getValueAsString());
        // …and the three-argument form delegates with false, so every legacy caller is unmoved.
        assertFalse(lk.lookupValue(primary, 0, "NOSUCH").isMissingOrInvalid());

        // ARM 3 — CONTROL, anti-corruption: a genuinely STORED "" is a value and stays one even
        // under a numeric expectation, because the column EXISTS and the flag never reaches a
        // present cell. Rewriting "" -> MIS at the call site would have broken exactly this.
        IDataValue storedEmpty = lk.lookupValue(primary, 0, "SITE", true);
        assertFalse(storedEmpty.isMissingOrInvalid(),
                "⛔ a stored empty string is a PRESENT value, expectation or no expectation");
        assertEquals("", storedEmpty.getValueAsString());
    }


    @Test
    @DisplayName("lookupAllValues mirrors lookupAll's 0..N shape and missing-cell filter")
    void lookupAllValuesMirrorsLookupAll()
    {
        IDataTable primary = t("DM", col("USUBJID", DataValueType.STRING, "U1"));
        IDataTable joined = t("AE", col("USUBJID", DataValueType.STRING, "U1", "U1", "U1"),
                col("SEQ", DataValueType.LONG, 1L, null, 3L));
        DatasetLookup lk = DatasetLookup.build("AE", joined, List.of("USUBJID"));
        assertNotNull(lk);

        List<String> text = lk.lookupAll(primary, 0, "SEQ");
        List<IDataValue> typed = lk.lookupAllValues(primary, 0, "SEQ");
        assertEquals(text.size(), typed.size(), "the missing middle cell is filtered by both");
        assertEquals(2, typed.size());
        for (int i = 0; i < text.size(); i++)
        {
            assertEquals(text.get(i), typed.get(i).getValueAsString());
        }
        assertTrue(lk.lookupAllValues(primary, 0, "NOSUCH").isEmpty());
    }


    @Test
    @DisplayName("the JoinLookup defaults keep an unmigrated implementation on today's behaviour")
    void defaultsWrapTheTextAccessor()
    {
        JoinLookup unmigrated = new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                return "7";
            }


            @Override
            public String getDatasetName()
            {
                return "DS";
            }
        };
        IDataTable primary = t("AE", col("X", DataValueType.STRING, "a"));
        assertEquals("7", unmigrated.lookupValue(primary, 0, "ANY").getValueAsString());
        assertEquals(DataValueType.STRING, unmigrated.lookupValue(primary, 0, "ANY").getType());
        // The interface default means "unknown", so an unmigrated implementation is NOT gated
        // rather than being gated as Char.
        assertEquals(DataValueType.MISSING, unmigrated.declaredTypeOf("ANY"));
    }

    // [ArrayRecordComponent] suppressed: a private fixture carrier that only ferries a varargs
    // cell list from the factory below into the table builder. It is never compared, hashed or
    // put in a collection, so the record's identity-based array equals/hashCode is unobservable —
    // the only thing a defensive copy would buy here is the copy.
    @SuppressWarnings("ArrayRecordComponent")
    private record Col(String name, DataValueType type, Object[] values)
    {
    }

    private static Col col(String name, DataValueType type, Object... values)
    {
        return new Col(name, type, values);
    }


    private static IDataTable t(String name, Col... cols)
    {
        int rc = cols[0].values().length;
        CachedDataTableColumn[] cc = new CachedDataTableColumn[cols.length];
        DataTableColumnMeta[] mm = new DataTableColumnMeta[cols.length];
        for (int c = 0; c < cols.length; c++)
        {
            cc[c] = new CachedDataTableColumn(c, cols[c].type());
            mm[c] = DataTableColumnMeta.builder().index(c).name(cols[c].name())
                    .label(cols[c].name()).type(cols[c].type()).build();
            for (int r = 0; r < rc; r++)
            {
                cc[c].addElement(cols[c].values()[r]);
            }
            cc[c].complete();
        }
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name).columns(mm)
                .rowCount(rc).totalRowCount(rc).build(), cc);
    }
}
