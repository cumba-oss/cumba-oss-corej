package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.values.DataValueDouble;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueString;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The uniform typed value carrier (phase 3, channels retyped in phase 3d): construction per source,
 * the D85a missing-identity decode, and the typed comparison reads
 * ({@code Primitives.targetAsDouble} / {@code targetString}) that replaced the retired
 * {@code comparisonOperand()} derivation.
 */
class TypedValueTest
{

    // -------------------------------------------------------------------
    // D85a — the carrier-boundary decode
    // -------------------------------------------------------------------

    @Test
    @DisplayName("a MissingValue-carrying cell decodes to that identity, not a generic MIS")
    void missingObjectKeepsIdentity()
    {
        IDataValue cell = new DataValueMissing(MissingValue.MIS_A);
        assertSame(MissingValue.MIS_A, TypedValue.missingIdentityOf(cell));

        TypedValue tv = TypedValue.column(DataValueType.DOUBLE, cell);
        assertTrue(tv.isMissing());
        assertSame(MissingValue.MIS_A, tv.missing());
        assertSame(cell, tv.cell(), "the cell travels verbatim");
        assertNull(tv.resolved(), "legacy channel: a missing resolves to null");
    }


    @Test
    @DisplayName("a payload NaN double decodes its marker byte; a plain NaN is MIS")
    void nanPayloadDecodes()
    {
        double encodedA = MissingValue.MIS_A.asDouble();
        assertSame(MissingValue.MIS_A, TypedValue.missingIdentityOf(new DataValueDouble(encodedA)),
                "the quiet-NaN mantissa carries the marker byte (D85a)");
        assertSame(MissingValue.MIS, TypedValue.missingIdentityOf(new DataValueDouble(Double.NaN)),
                "a payload-less NaN is the generic numeric missing");
    }


    @Test
    @DisplayName("a present value has no missing identity")
    void presentValuesAreNotMissing()
    {
        assertNull(TypedValue.missingIdentityOf(new DataValueString("abc")));
        assertNull(TypedValue.missingIdentityOf(new DataValueString("")),
                "\"\" is a value, not a MissingValue (D34 #1)");
        assertNull(TypedValue.missingIdentityOf(new DataValueDouble(5.0)));
    }

    // -------------------------------------------------------------------
    // The column source — legacy ColumnVector channels
    // -------------------------------------------------------------------


    @Test
    @DisplayName("column: a present numeric cell is read exactly through the typed channel (D84)")
    void columnNumericTypedReadIsExact()
    {
        IDataValue cell = new DataValueDouble(10000000000001.0);
        TypedValue tv = TypedValue.column(DataValueType.DOUBLE, cell);
        assertSame(cell, tv.sourceCell(), "the cell travels verbatim on the typed channel");
        assertEquals(10000000000001.0, Primitives.targetAsDouble(tv),
                "the exact value, never the getAsDoubleCleaned text round-trip (phase 3d)");
        assertEquals(cell.getValueAsString(), tv.resolved(),
                "the untyped transport keeps the legacy text form");
    }


    @Test
    @DisplayName("column: a character cell resolves to its text, and compares as text")
    void columnCharacterResolvesText()
    {
        TypedValue tv = TypedValue.column(DataValueType.STRING, new DataValueString("abc"));
        assertEquals("abc", tv.resolved());
        assertEquals("abc", Primitives.targetString(tv),
                "a character cell folds to its text; its numeric read is null");
        assertNull(Primitives.targetAsDouble(tv));
        assertFalse(tv.isMissing());
        assertEquals(DataValueType.STRING, tv.type());
    }


    @Test
    @DisplayName("column: a missing numeric cell resolves and compares as null")
    void columnMissingNumeric()
    {
        TypedValue tv = TypedValue.column(DataValueType.DOUBLE, new DataValueDouble(Double.NaN));
        assertTrue(tv.isMissing());
        assertNull(tv.resolved());
        assertNull(Primitives.targetAsDouble(tv), "a missing target has no numeric read");
        assertEquals("", Primitives.targetString(tv), "a missing target folds to \"\"");
    }

    // -------------------------------------------------------------------
    // The typed-producer source — legacy ComputedVector.typed channels
    // -------------------------------------------------------------------


    @Test
    @DisplayName("typedCell: any present cell is the typed source cell, whatever the type")
    void typedCellSourceCell()
    {
        IDataValue cell = new DataValueString("SCREENING");
        TypedValue tv = TypedValue.typedCell(DataValueType.STRING, cell);
        assertSame(cell, tv.sourceCell());
        assertEquals("SCREENING", Primitives.targetString(tv));
        assertEquals("SCREENING", tv.resolved());
    }


    /**
     * ⭐ RE-BASED 2026-09-18: this used to call {@code typedCell(type, null)} and so pinned that a
     * {@code null} cell was an accepted input. It is not: a value is a real value or a
     * {@link MissingValue}, never {@code null} (see {@code ScalarSemantics.computedMissing}), and
     * the parameter's {@code @Nullable} is gone so NullAway rejects the {@code null} at compile
     * time. The carrier's behaviour for a computed missing is unchanged and is what is pinned here
     * — only the way the caller expresses "no result" moved, from {@code null} to the MIS cell.
     */
    @Test
    @DisplayName("typedCell(computedMissing()): a computed missing is MIS")
    void typedCellNullIsComputedMis()
    {
        TypedValue tv = TypedValue.typedCell(DataValueType.DOUBLE,
                net.cumba.corej.core.exec.ScalarSemantics.computedMissing());
        assertTrue(tv.isMissing());
        assertSame(MissingValue.MIS, tv.missing(), "a computed missing is always MIS (D36 #8)");
        assertTrue(tv.cell().isMissingOrInvalid());
        assertNull(tv.resolved());
        assertNull(Primitives.targetAsDouble(tv));
    }


    @Test
    @DisplayName("typedCell: a carried MissingValue keeps its identity (D75a case 4 shape)")
    void typedCellCarriedIdentitySurvives()
    {
        IDataValue cell = new DataValueMissing(MissingValue.MIS_B);
        TypedValue tv = TypedValue.typedCell(DataValueType.DOUBLE, cell);
        assertSame(MissingValue.MIS_B, tv.missing(), "carried, not created — identity preserved");
        assertSame(cell, tv.cell());
        assertNull(tv.resolved());
    }

    // -------------------------------------------------------------------
    // The resolved source — legacy ConstVector / untyped ComputedVector channels
    // -------------------------------------------------------------------


    @Test
    @DisplayName("resolved: the payload is the operand on every legacy channel")
    void resolvedPayloadChannels()
    {
        TypedValue tv = TypedValue.resolved(DataValueType.LONG, 30L);
        assertEquals(30L, tv.resolved());
        assertNull(tv.sourceCell(), "a resolved payload has no source cell");
        assertEquals(30.0, Primitives.targetAsDouble(tv),
                "a Number payload converts directly (the numeric-mode trigger case)");
        assertEquals(30.0, tv.cell().getValueAsDouble());
        assertFalse(tv.isMissing());
    }


    @Test
    @DisplayName("resolved: a list payload passes through untouched")
    void resolvedListPayload()
    {
        List<String> list = List.of("A", "B");
        TypedValue tv = TypedValue.resolved(DataValueType.STRING, list);
        assertSame(list, tv.resolved(), "the untyped transport carries collections until 6b");
    }


    @Test
    @DisplayName("resolved(null): a computed missing is MIS and derives a missing cell once")
    void resolvedNullIsComputedMis()
    {
        TypedValue tv = TypedValue.resolved(DataValueType.MISSING, null);
        assertTrue(tv.isMissing());
        assertSame(MissingValue.MIS, tv.missing());
        assertNull(tv.resolved());
        assertSame(tv.cell(), tv.cell(), "the derived cell wrapper is memoised");
        assertTrue(tv.cell().isMissingOrInvalid());
    }

}
