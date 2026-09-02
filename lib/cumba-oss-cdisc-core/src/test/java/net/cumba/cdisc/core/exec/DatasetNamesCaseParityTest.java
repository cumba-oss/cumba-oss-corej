package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FU-2 — {@code dataset_names} (study) and {@code define_dataset_names} are uppercased in both
 * engines so the SD0061/SD1063 set-compares are case-invariant. A Define-XML that declares a
 * lowercase {@code ItemGroupDef} name against an uppercase study inventory (or vice versa) must not
 * produce a spurious {@code is_not_contained_by} fire.
 */
@ExtendWith(MockitoExtension.class)
class DatasetNamesCaseParityTest
{

    /** A study inventory whose data-driven dataset names are UPPER case. */
    private static DatasetResolver.WithInventory inventory(Set<String> names)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String domainName)
            {
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return names;
            }
        };
    }


    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }


    @Test
    void defineDatasetNames_uppercased()
    {
        MetadataProvider define = mock(MetadataProvider.class);
        when(define.getDatasetNames()).thenReturn(List.of("dm", "lb"));
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();

        Object result = OperationExecutor.executeOne(makeOp("$define_ds", "define_dataset_names"),
                table, _ -> null, null, Map.of(), null, null, define);
        assertEquals(List.of("DM", "LB"), result);
    }


    @Test
    void datasetNames_uppercased()
    {
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();
        DatasetResolver.WithInventory resolver = inventory(Set.of("dm", "lb"));

        Object result = OperationExecutor.executeOne(makeOp("$study_ds", "dataset_names"), table,
                resolver, null, Map.of(), null, null, null);
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) result;
        assertEquals(Set.of("DM", "LB"), new HashSet<>(names));
    }


    @Test
    void sd0061Shape_lowercaseDefineVsUppercaseStudy_noSpuriousFire()
    {
        // define declares lowercase names; study inventory is uppercase. Once both list ops
        // uppercase, the define set is fully contained by the study set (no SD0061 fire).
        MetadataProvider define = mock(MetadataProvider.class);
        when(define.getDatasetNames()).thenReturn(List.of("dm", "lb"));
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();
        DatasetResolver.WithInventory resolver = inventory(Set.of("DM", "LB", "AE"));

        @SuppressWarnings("unchecked")
        List<String> defineNames = (List<String>) OperationExecutor.executeOne(
                makeOp("$define_ds", "define_dataset_names"), table, resolver, null, Map.of(), null,
                null, define);
        @SuppressWarnings("unchecked")
        List<String> studyNames = (List<String>) OperationExecutor.executeOne(
                makeOp("$study_ds", "dataset_names"), table, resolver, null, Map.of(), null, null,
                define);

        assertTrue(new HashSet<>(studyNames).containsAll(defineNames),
                "every define dataset name is contained by the study inventory (case-invariant)");
    }
}
