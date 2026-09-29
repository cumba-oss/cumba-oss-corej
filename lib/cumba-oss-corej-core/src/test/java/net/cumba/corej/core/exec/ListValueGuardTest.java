package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.gen.DefineXMLProvider;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.testkit.SyntheticDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Register {@code NNL §1} ({@code PLAN-no-null-list-elements} §3, row 14): a {@code null} element
 * in a list value is a producer defect that throws <b>where the list is born</b>, naming the
 * producer — at {@link OperationExecutor#executeOne} for an operation result and at
 * {@link ConstVector#of} for a constant list — and nowhere per row.
 *
 * <p>
 * Mockito-free: a five-method {@link DefineXMLProvider} fake, real {@link SyntheticDataTable}, real
 * {@link GroupedResult}. Red-before on HEAD: (1) returned the list with its {@code null} intact,
 * (2) built the vector.
 * </p>
 */
class ListValueGuardTest
{

    /** A Define provider whose dataset list carries a {@code null} — the producer defect. */
    private static final class DefectiveDefine implements DefineXMLProvider
    {

        @Override
        public Map<String, String> getDatasetMetadata(String datasetName)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getVariables(String datasetName)
        {
            return List.of();
        }


        @Override
        public List<Map<String, String>> getCodelistTerms(String codelistOID)
        {
            return List.of();
        }


        @Override
        public List<String> getDatasetNames()
        {
            return Arrays.asList("AE", null);
        }


        @Override
        public List<String> getKeyVariables(String datasetName)
        {
            return List.of();
        }
    }

    @Test
    void executeOneRejectsAnOperationResultWithANullElementNamingTheOperation()
    {
        Operation op = new Operation();
        op.setId("$dsn");
        op.setOperator("define_dataset_names");
        SyntheticDataTable dm = new SyntheticDataTable("DM", List.of("STUDYID"), new String[]
        {
                "S1"
        }, 1);
        DefineXmlMetadataProvider define = new DefineXmlMetadataProvider(new DefectiveDefine(),
                null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> OperationExecutor
                .executeOne(op, dm, _ -> null, null, Map.of(), "T-NNL", null, define));

        assertEquals(
                "operation define_dataset_names (id=$dsn) of rule T-NNL produced a list with"
                        + " a null element at index 1 — nothing is ever null (register NNL §1)",
                e.getMessage());
    }


    @Test
    void constVectorRejectsANullElementAndAcceptsMissingAndEmpty()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConstVector.of(Arrays.asList("A", null)));
        assertTrue(e.getMessage().startsWith(
                "a constant list value (ConstVector.of) produced a list with a null element at"
                        + " index 1"),
                e.getMessage());

        // Not throwing IS the contract for a clean list: a MissingValue and "" are values.
        List<Object> clean = List.of("A", MissingValue.MIS, "");
        ConstVector v = assertDoesNotThrow(() -> ConstVector.of(clean));
        assertEquals(clean, v.value(0).resolved(), "the clean list is carried unchanged");
    }


    @Test
    void groupedResultListValuesAreScannedScalarNullsAreNot()
    {
        GroupedResult withNullInList = new GroupedResult(List.of("G"),
                Map.<String, Object> of("g1", List.of("A"), "g2", Arrays.asList("B", null)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ListValueGuard.requireNoNullElement(withNullInList, () -> "grouped op"));
        assertEquals("grouped op produced a list with a null element at index 1 — nothing is"
                + " ever null (register NNL §1)", e.getMessage());

        // A scalar null value of a group is the untyped scalar channel (NF §1, still target) —
        // out of this guard's scope, so not throwing is the contract here.
        Map<String, Object> scalars = new java.util.HashMap<>();
        scalars.put("g1", "A");
        scalars.put("g2", null);
        GroupedResult withScalarNull = new GroupedResult(List.of("G"), scalars);
        assertDoesNotThrow(
                () -> ListValueGuard.requireNoNullElement(withScalarNull, () -> "grouped op"));
        assertDoesNotThrow(() -> ListValueGuard.requireNoNullElement(null, () -> "scalar op"),
                "a scalar null result is not a list value");
    }


    @Test
    void immutableTuplesPassAndOneNestingLevelIsScanned()
    {
        // List.of(...).contains(null) throws NPE — the scan is a loop, so the immutable tuples a
        // distinct([A, B]) result is made of pass without touching contains(null).
        List<List<String>> tuples = List.of(List.of("A", "B"), List.of("C", "D"));
        assertDoesNotThrow(() -> ListValueGuard.requireNoNullElement(tuples, () -> "distinct"));

        // One nesting level: a null INSIDE a tuple is found, and its index is the inner one.
        List<List<String>> nested = List.of(List.of("A", "B"), Arrays.asList("C", null));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ListValueGuard.requireNoNullElement(nested, () -> "distinct"));
        assertEquals("distinct produced a list with a null element at index 1 — nothing is ever"
                + " null (register NNL §1)", e.getMessage());
    }

}
