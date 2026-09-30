package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code get_parent_model_column_order}'s reach into the study inventory (combined review W4 M1 /
 * W4 L2): the parent resolves by exact name first and otherwise walks the inventory
 * fault-tolerantly and in a deterministic order ({@code SplitDomainResolution.membersOf}).
 */
class ParentModelColumnOrderTest
{

    /**
     * W4 M1: one unopenable UNRELATED dataset in the inventory must not turn the parent lookup into
     * a rule ERROR. Pre-fix {@code WithInventory.tablesForDomain} resolved every entry unguarded,
     * so the broken entry's exception escaped for every SUPP-- dataset.
     */
    @Test
    void anUnopenableUnrelatedDatasetDoesNotBreakAnExactParent()
    {
        IDataTable suppae = MockTable.of().col("RDOMAIN", "AE").col("QNAM", "AETERM").name("SUPPAE")
                .build();
        Library lib = new Library();
        lib.modelColumnOrder.put("AE", List.of("STUDYID", "AETERM"));
        Map<String, IDataTable> tables = new LinkedHashMap<>();
        tables.put("BROKEN", suppae); // placeholder; the entry throws on resolve
        tables.put("AE", table("AE", "AE"));
        Vector answer = ParentModelColumnOrder.evaluate(run(suppae, lib, inventory(tables)),
                List.of(column(suppae, "RDOMAIN")));
        assertEquals(List.of("STUDYID", "AETERM"), answer.value(0).resolved(),
                "the named parent's model order, the broken entry never consulted");
    }


    /**
     * W4 M1, split arm: the member walk skips the unreadable entry instead of failing — the parent
     * LB (submitted as LBCH + LBHE) still answers the union of its members.
     */
    @Test
    void anUnopenableUnrelatedDatasetDoesNotBreakASplitParent()
    {
        IDataTable supplb = MockTable.of().col("RDOMAIN", "LB").col("QNAM", "LBX").name("SUPPLB")
                .build();
        Library lib = new Library();
        lib.modelColumnOrder.put("LBCH", List.of("STUDYID", "LBTESTCD"));
        lib.modelColumnOrder.put("LBHE", List.of("STUDYID", "LBTEST"));
        Map<String, IDataTable> tables = new LinkedHashMap<>();
        tables.put("LBCH", table("LBCH", "LB"));
        tables.put("BROKEN", supplb);
        tables.put("LBHE", table("LBHE", "LB"));
        Vector answer = ParentModelColumnOrder.evaluate(run(supplb, lib, inventory(tables)),
                List.of(column(supplb, "RDOMAIN")));
        assertEquals(List.of("STUDYID", "LBTESTCD", "LBTEST"), answer.value(0).resolved());
    }


    /**
     * W4 L2: the member order — and so the unioned model order — is the upper-cased member name,
     * never the inventory's iteration order (the production inventory is a {@code Map.copyOf} key
     * set, random per JVM). Two inventories holding the same members in opposite orders answer the
     * same list, and it is the sorted one. Pre-fix the reversed inventory answered
     * {@code [STUDYID, LBTEST, LBTESTCD]}.
     */
    @Test
    void theSplitParentsMemberOrderIsSortedNotTheInventoryOrder()
    {
        IDataTable supplb = MockTable.of().col("RDOMAIN", "LB").col("QNAM", "LBX").name("SUPPLB")
                .build();
        Library lib = new Library();
        lib.modelColumnOrder.put("LBCH", List.of("STUDYID", "LBTESTCD"));
        lib.modelColumnOrder.put("LBHE", List.of("STUDYID", "LBTEST"));
        IDataTable lbch = table("LBCH", "LB");
        IDataTable lbhe = table("LBHE", "LB");
        Map<String, IDataTable> forward = new LinkedHashMap<>();
        forward.put("LBCH", lbch);
        forward.put("LBHE", lbhe);
        Map<String, IDataTable> reversed = new LinkedHashMap<>();
        reversed.put("LBHE", lbhe);
        reversed.put("LBCH", lbch);
        Object first = ParentModelColumnOrder
                .evaluate(run(supplb, lib, inventory(forward)), List.of(column(supplb, "RDOMAIN")))
                .value(0).resolved();
        Object second = ParentModelColumnOrder
                .evaluate(run(supplb, lib, inventory(reversed)), List.of(column(supplb, "RDOMAIN")))
                .value(0).resolved();
        assertEquals(List.of("STUDYID", "LBTESTCD", "LBTEST"), second,
                "sorted by member name: LBCH before LBHE");
        assertEquals(first, second, "the same members answer the same order");
    }

    // ------------------------------------------------------------ helpers


    private static IDataTable table(String name, String domain)
    {
        return MockTable.of().col("DOMAIN", domain).name(name).build();
    }


    private static EvalRun run(IDataTable table, MetadataProvider library, DatasetResolver resolver)
    {
        return EvalRun.fullRange(EvaluationContext.builder().table(table).ruleId("W4-M1")
                .libraryProvider(library).datasetResolver(resolver).build());
    }


    private static Vector column(IDataTable table, String name)
    {
        int idx = table.getMetaData().getColumnIndex(name);
        return new ColumnVector(name, table.getColumn(idx),
                table.getMetaData().getColumn(idx).getType());
    }


    /** An inventory whose {@code BROKEN} entry throws on resolve, as a corrupt file's supplier. */
    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> tables)
    {
        Map<String, IDataTable> upper = new LinkedHashMap<>();
        tables.forEach((k, v) -> upper.put(k.toUpperCase(Locale.ROOT), v));
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                String key = name.toUpperCase(Locale.ROOT);
                if ("BROKEN".equals(key))
                {
                    throw new IllegalStateException("BROKEN cannot be opened");
                }
                return upper.get(key);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return upper.keySet();
            }
        };
    }

    /** A Library answering the model column order by dataset name; everything else empty. */
    private static final class Library implements MetadataProvider
    {

        final Map<String, List<String>> modelColumnOrder = new LinkedHashMap<>();

        @Override
        public @Nullable List<String> getStandardModelVariables(IDataTable t, DatasetResolver r)
        {
            return modelColumnOrder.get(t.getMetaData().getName());
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String codelistName)
        {
            return Optional.empty();
        }


        @Override
        public String getStandard()
        {
            return "SDTMIG";
        }

    }

}
