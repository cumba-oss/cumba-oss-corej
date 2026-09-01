package net.cumba.cdisc.core.expr.eval;

import static net.cumba.cdisc.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 5 — timezone normalization through the native primitives: {@code dateComparison} compares
 * offset-bearing ISO values instant-preserving in UTC (via the shared
 * {@code ScalarSemantics.compareIso}), and {@code datePartComparison} normalizes <i>before</i> the
 * {@code 'T'} split — fixing the pre-existing gap where a bare time part kept its offset glued on.
 */
@ExtendWith(MockitoExtension.class)
class PrimitivesTimezoneTest
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


    @Test
    void dateComparison_offsetsNormalized_instantEquality()
    {
        // row0: same instant across offsets; row1: same wall-clock, different offsets.
        IDataTable t = MockTable.of()
                .col("A", "2024-01-15T13:30:00+02:00", "2024-01-15T13:30:00+02:00")
                .col("B", "2024-01-15T11:30:00Z", "2024-01-15T13:30:00Z").build();
        BitSet eq = Primitives.dateComparison(col(t, "A"), col(t, "B"), 2, 0, true, false);
        assertEquals(bits(0), eq, "same instant equal; same wall-clock not equal");
    }


    @Test
    void dateComparison_orderingAcrossOffsets()
    {
        // 13:30+02:00 = 11:30Z: less than 12:00Z, not less than 11:00Z.
        IDataTable t = MockTable.of()
                .col("A", "2024-01-15T13:30:00+02:00", "2024-01-15T13:30:00+02:00")
                .col("B", "2024-01-15T12:00:00Z", "2024-01-15T11:00:00Z").build();
        BitSet lt = Primitives.dateComparison(col(t, "A"), col(t, "B"), 2, -1, false, false);
        assertEquals(bits(0), lt, "11:30Z < 12:00Z fires; 11:30Z > 11:00Z does not");
    }


    @Test
    void datePartComparison_datePart_offsetShiftsAcrossMidnight()
    {
        // 2024-03-16T01:30+02:00 is 2024-03-15T23:30 UTC → date part is 2024-03-15.
        IDataTable t = MockTable.of().col("ASTDTM", "2024-03-16T01:30+02:00").build();
        BitSet eqPrev = Primitives.datePartComparison(col(t, "ASTDTM"),
                ConstVector.of("2024-03-15"), 1, false, false);
        assertEquals(bits(0), eqPrev, "date part shifts to the previous day in UTC");

        BitSet eqSame = Primitives.datePartComparison(col(t, "ASTDTM"),
                ConstVector.of("2024-03-16"), 1, false, false);
        assertEquals(bits(), eqSame, "wall-clock date no longer matches after the UTC shift");
    }


    @Test
    void datePartComparison_timePart_offsetNormalizedOnDatetimeLhs()
    {
        // The LHS datetime normalizes to ...T11:30:00 — its time part equals 11:30:00.
        IDataTable t = MockTable.of().col("ASTDTM", "2024-01-15T13:30:00+02:00").build();
        BitSet eq = Primitives.datePartComparison(col(t, "ASTDTM"), ConstVector.of("11:30:00"), 1,
                true, false);
        assertEquals(bits(0), eq, "time part is rendered in UTC");

        BitSet wallClock = Primitives.datePartComparison(col(t, "ASTDTM"),
                ConstVector.of("13:30:00"), 1, true, false);
        assertEquals(bits(), wallClock, "the wall-clock time no longer matches");

        BitSet leadingT = Primitives.datePartComparison(col(t, "ASTDTM"),
                ConstVector.of("T11:30:00"), 1, true, false);
        assertEquals(bits(0), leadingT, "a leading-T bare time is still accepted");
    }


    @Test
    void datePartComparison_timePart_bareTimeRhsNormalizedToUtc_reviewF7()
    {
        // Review F7: a bare time with an offset is normalized to UTC like a full datetime.
        // Same-offset pair: both sides denote 11:30Z → equal again.
        IDataTable sameOffset = MockTable.of().col("ASTDTM", "2024-01-15T13:30:00+02:00").build();
        BitSet eq = Primitives.datePartComparison(col(sameOffset, "ASTDTM"),
                ConstVector.of("13:30:00+02:00"), 1, true, false);
        assertEquals(bits(0), eq, "13:30:00+02:00 vs 13:30:00+02:00 → both 11:30:00Z, equal");

        // Cross-offset pair: 13:30+02:00 and 12:30+01:00 are the same instant-of-day.
        BitSet crossOffset = Primitives.datePartComparison(col(sameOffset, "ASTDTM"),
                ConstVector.of("12:30:00+01:00"), 1, true, false);
        assertEquals(bits(0), crossOffset, "12:30:00+01:00 is also 11:30:00Z → equal");

        // An offset-less datetime LHS (assumed UTC, time part 13:30:00) no longer equals the
        // offset-bearing bare time (11:30:00Z) — replacing the old strip-only verdict.
        IDataTable utcLhs = MockTable.of().col("ASTDTM", "2024-01-15T13:30:00").build();
        BitSet unequal = Primitives.datePartComparison(col(utcLhs, "ASTDTM"),
                ConstVector.of("13:30:00+02:00"), 1, true, false);
        assertEquals(bits(), unequal, "13:30:00 UTC vs 11:30:00Z → no longer equal");
    }


    @Test
    void bareTimeOffsetTimeParts_reviewF7()
    {
        // row0: same-offset pair equal; row1: cross-offset pair equal; row2: offset-less LHS
        // vs offset-bearing bare time NOT equal.
        IDataTable t = MockTable.of()
                .col("ASTDTM", "2024-01-15T13:30:00+02:00", "2024-01-15T13:30:00+02:00",
                        "2024-01-15T13:30:00")
                .col("ATM", "13:30:00+02:00", "12:30:00+01:00", "13:30:00+02:00").build();

        BitSet nativeBits = Primitives.datePartComparison(col(t, "ASTDTM"), col(t, "ATM"), 3, true,
                false);
        assertEquals(bits(0, 1), nativeBits, "same-offset and cross-offset pairs equal");
    }


    @Test
    void offsetBearingComparison()
    {
        // row0 equal across offsets, row1 not.
        IDataTable t = MockTable.of()
                .col("A", "2024-01-15T13:30:00+02:00", "2024-01-15T13:30:00+02:00")
                .col("B", "2024-01-15T11:30:00Z", "2024-01-15T13:30:00Z").build();

        BitSet nativeBits = Primitives.dateComparison(col(t, "A"), col(t, "B"), 2, 0, true, false);

        assertEquals(bits(0), nativeBits, "the time-correct verdict: only row 0 is equal");
    }
}
