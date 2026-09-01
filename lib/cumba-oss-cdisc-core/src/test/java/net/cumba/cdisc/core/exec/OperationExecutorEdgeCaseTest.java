package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationExecutorEdgeCaseTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    // ---- resolveWildcard ----

    @Test
    void testResolveWildcard_nullDomain()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        assertNull(OperationExecutor.resolveWildcard(null, table));
    }


    @Test
    void testResolveWildcard_noDashDash()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        assertEquals("DM", OperationExecutor.resolveWildcard("DM", table));
    }


    @Test
    void testResolveWildcard_withDashDash()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        assertEquals("SUPPAE", OperationExecutor.resolveWildcard("SUPP--", table));
    }


    @Test
    void testResolveWildcard_nullTableName()
    {
        IDataTable table = MockTable.of().col("X", "1").build(); // no name set
        assertEquals("SUPP--", OperationExecutor.resolveWildcard("SUPP--", table));
    }

    // ---- extract_metadata edge cases ----


    @Test
    void testExtractMetadata_nullName()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        Operation op = new Operation();
        op.setId("$result");
        op.setOperator("extract_metadata");
        // op.setName is NOT called

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertFalse(vars.containsKey("$result"));
    }


    @Test
    void testExtractMetadata_customField()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        Operation op = new Operation();
        op.setId("$result");
        op.setOperator("extract_metadata");
        op.setName("some_custom_field");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        // Custom metadata not available on mock → null → not stored
        assertFalse(vars.containsKey("$result"));
    }

    // ---- distinct edge cases ----


    @Test
    void testDistinct_nullName()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = new Operation();
        op.setId("$result");
        op.setOperator("distinct");
        // op.setName is NOT called

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$result");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ---- max edge cases ----


    @Test
    void testMax_nullName()
    {
        IDataTable table = MockTable.of().col("X", "10").build();

        Operation op = new Operation();
        op.setId("$result");
        op.setOperator("max");
        // op.setName is NOT called

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertFalse(vars.containsKey("$result"));
    }


    @Test
    void testMax_nonNumericValues()
    {
        IDataTable table = MockTable.of().col("X", "abc", "def").build();

        Operation op = new Operation();
        op.setId("$result");
        op.setOperator("max");
        op.setName("X");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertEquals("def", vars.get("$result")); // string fallback returns lexicographic max
    }

    // ---- null operation id ----


    @Test
    void testNullOperationId_resultNotStored()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = new Operation();
        // op.setId is NOT called → id is null
        op.setOperator("variable_count");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertTrue(vars.isEmpty());
    }

    // ---- empty operations list ----


    @Test
    void testEmptyOperationsList()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        Map<String, Object> vars = OperationExecutor.execute(List.of(), table, NO_RESOLVER);
        assertTrue(vars.isEmpty());
    }

}
