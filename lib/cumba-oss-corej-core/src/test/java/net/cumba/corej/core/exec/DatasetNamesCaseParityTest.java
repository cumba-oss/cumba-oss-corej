package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * FU-2: {@code dataset_names()} and {@code define_dataset_names()} upper-case their answers so the
 * set compares of SD0061 / SD1063 are case-invariant. Both are registry functions since wave 4
 * ({@code PLAN-list-functions}); the claims are unchanged. Real objects, no Mockito.
 */
class DatasetNamesCaseParityTest
{

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


    @SuppressWarnings("unchecked")
    private static List<String> defineNames(MetadataProvider define)
    {
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();
        return (List<String>) DefineLists.defineDatasetNames(
                EvalRun.fullRange(
                        EvaluationContext.builder().table(table).defineProvider(define).build()),
                List.of()).value(0).resolved();
    }


    @SuppressWarnings("unchecked")
    private static List<String> studyNames(DatasetResolver.WithInventory resolver)
    {
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();
        return (List<String>) InventoryLists.datasetNames(
                EvalRun.fullRange(
                        EvaluationContext.builder().table(table).datasetResolver(resolver).build()),
                List.of()).value(0).resolved();
    }


    @Test
    void defineDatasetNames_uppercased()
    {
        assertEquals(List.of("DM", "LB"), defineNames(new Define(List.of("dm", "lb"))));
    }


    @Test
    void datasetNames_uppercased()
    {
        assertEquals(Set.of("DM", "LB"), new HashSet<>(studyNames(inventory(Set.of("dm", "lb")))));
    }


    @Test
    void sd0061Shape_lowercaseDefineVsUppercaseStudy_noSpuriousFire()
    {
        // define declares lowercase names; study inventory is uppercase. Once both list functions
        // uppercase, the define set is fully contained by the study set (no SD0061 fire).
        List<String> defineNames = defineNames(new Define(List.of("dm", "lb")));
        List<String> studyNames = studyNames(inventory(Set.of("DM", "LB", "AE")));
        assertTrue(new HashSet<>(studyNames).containsAll(defineNames),
                "every define dataset name is contained by the study inventory (case-invariant)");
    }

    /** A sponsor Define-XML declaring the given dataset names and nothing else. */
    private record Define(List<String> names) implements MetadataProvider
    {

        @Override
        public List<String> getDatasetNames()
        {
            return names;
        }


        @Override
        public List<String> getRequiredVariables(String d)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String d)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String d)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String d)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String c)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String d)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String d)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String cl)
        {
            return Optional.empty();
        }


        @Override
        public @Nullable String getStandard()
        {
            return "sdtmig";
        }
    }
}
