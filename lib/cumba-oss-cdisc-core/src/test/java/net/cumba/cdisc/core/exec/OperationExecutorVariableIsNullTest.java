package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@link OperationExecutor} {@code variable_is_null} (T5a) — mirrors the Python reference engine's
 * {@code operations/variable_is_null.py}: {@code true} when the target variable is absent from the
 * dataset, or present but empty ("" / missing) for every record; {@code false} as soon as any row
 * carries a value.
 */
class OperationExecutorVariableIsNullTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation op(String var)
    {
        Operation o = new Operation();
        o.setId("$null");
        o.setOperator("variable_is_null");
        o.setName(var);
        return o;
    }


    private static boolean run(Operation o, IDataTable t)
    {
        return (Boolean) OperationExecutor.executeOne(o, t, NO_RESOLVER, null,
                new LinkedHashMap<>());
    }


    @Test
    void allEmptyColumnIsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2").col("VAR", "", "").build();
        assertTrue(run(op("VAR"), t));
    }


    @Test
    void anyPopulatedRowIsNotNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2").col("VAR", "", "x").build();
        assertFalse(run(op("VAR"), t));
    }


    @Test
    void fullyPopulatedColumnIsNotNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2").col("VAR", "a", "b").build();
        assertFalse(run(op("VAR"), t));
    }


    @Test
    void absentColumnIsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1").build();
        assertTrue(run(op("MISSING"), t));
    }
}
