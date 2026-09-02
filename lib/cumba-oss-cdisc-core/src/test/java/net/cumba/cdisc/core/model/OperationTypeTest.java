package net.cumba.cdisc.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OperationTypeTest
{

    @Test
    void testFromJson_knownValues()
    {
        assertEquals(OperationType.DISTINCT, OperationType.fromJson("distinct"));
        assertEquals(OperationType.RECORD_COUNT, OperationType.fromJson("record_count"));
        assertEquals(OperationType.VARIABLE_COUNT, OperationType.fromJson("variable_count"));
        assertEquals(OperationType.MAX, OperationType.fromJson("max"));
        assertEquals(OperationType.MAX_DATE, OperationType.fromJson("max_date"));
        assertEquals(OperationType.MIN_DATE, OperationType.fromJson("min_date"));
        assertEquals(OperationType.MINUS, OperationType.fromJson("minus"));
        assertEquals(OperationType.ROW_MAX, OperationType.fromJson("row_max"));
        assertEquals(OperationType.ROW_MIN, OperationType.fromJson("row_min"));
        assertEquals(OperationType.EXTRACT_METADATA, OperationType.fromJson("extract_metadata"));
        // EC-13 / EC-14 layer (i) — Java mirrors of the Python variable_names / standard_domains
        // library-dependent ops.
        assertEquals(OperationType.VARIABLE_NAMES, OperationType.fromJson("variable_names"));
        assertEquals(OperationType.STANDARD_DOMAINS, OperationType.fromJson("standard_domains"));
    }


    @Test
    void testFromJson_unknownReturnsNull()
    {
        assertNull(OperationType.fromJson("unknown"));
        assertNull(OperationType.fromJson(null));
        // split_by has no OperationType: it is a per-row value function (see SplitByInliner), and
        // plans/PLAN-retired-operators-as-operations.md Phase 2 parked the operation shape.
        assertNull(OperationType.fromJson("split_by"));
    }


    /**
     * ⚠⚠ This case used to assert the opposite — {@code variable_exists} was pinned as NOT
     * resolving, recording its retirement in favour of the {@code var_exists} function
     * ({@code plans/done/PLAN-variable-exists-cross-dataset.md}).
     *
     * <p>
     * <b>That retirement is not reversed, and the pin flipped for a narrower reason.</b> The
     * <em>verdict</em> still lives on {@code var_exists(X)}; what came back is the operation as the
     * operator's <em>reporting</em> carriage, so a rule declaring {@code $X} in
     * {@code Outcome.Output_Variables} has a value to report (the {@code Fix #181} warrant applied
     * to the one operator it could not reach — see
     * {@code plans/PLAN-retired-operators-as-operations.md}). Resolving here is precisely what
     * stops a Form B {@code variable_exists("X")} declaration failing to load with
     * {@code unknown operation function}.
     * </p>
     */
    @Test
    void variableExistsResolvesAgainAsTheReportingCarriage()
    {
        assertEquals(OperationType.VARIABLE_EXISTS, OperationType.fromJson("variable_exists"));
    }


    @Test
    void testJsonValue_roundTrip()
    {
        for (OperationType type : OperationType.values())
        {
            assertEquals(type, OperationType.fromJson(type.getJsonValue()),
                    "Round-trip failed for " + type);
        }
    }

}
