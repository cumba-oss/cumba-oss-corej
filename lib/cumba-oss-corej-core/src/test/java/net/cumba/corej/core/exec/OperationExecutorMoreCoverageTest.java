package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Additional coverage for {@link OperationExecutor} targeting under-tested operation types
 * (variable_value_count, has_mixed_emptiness_within_group, dataset_names, constant,
 * cross_dataset_variable_metadata, get_column_order_from_dataset variants) plus the
 * {@link OperationExecutor#isLibraryDependent} helper, the {@code LIBRARY_NOT_AVAILABLE} sentinel
 * rendering, and the {@code expandGroupRefs} variants for non-list collection and
 * unrecognised-shape branches.
 */
@ExtendWith(MockitoExtension.class)
class OperationExecutorMoreCoverageTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    // -----------------------------------------------------------------------
    // isLibraryDependent: switch arm coverage
    // -----------------------------------------------------------------------

    @Test
    void isLibraryDependent_libraryOps_returnTrue()
    {
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.REQUIRED_VARIABLES));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.EXPECTED_VARIABLES));
        assertTrue(
                OperationExecutor.isLibraryDependent(OperationType.GET_COLUMN_ORDER_FROM_LIBRARY));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.GET_MODEL_COLUMN_ORDER));
        assertTrue(
                OperationExecutor.isLibraryDependent(OperationType.GET_PARENT_MODEL_COLUMN_ORDER));
        assertTrue(
                OperationExecutor.isLibraryDependent(OperationType.GET_DATASET_FILTERED_VARIABLES));
        assertTrue(
                OperationExecutor.isLibraryDependent(OperationType.GET_MODEL_FILTERED_VARIABLES));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.VALID_CODELIST_DATES));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.DATASET_CLASS_FROM_LIBRARY));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.DOMAIN_IS_CUSTOM));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.CODELIST_TERMS));
        assertTrue(OperationExecutor.isLibraryDependent(OperationType.GET_CODELIST_ATTRIBUTES));
    }


    @Test
    void isLibraryDependent_nonLibraryOps_returnFalse()
    {
        assertFalse(OperationExecutor.isLibraryDependent(OperationType.RECORD_COUNT));
        assertFalse(OperationExecutor.isLibraryDependent(OperationType.DISTINCT));
        assertFalse(OperationExecutor.isLibraryDependent(OperationType.MAX));
        assertFalse(OperationExecutor.isLibraryDependent(OperationType.EXTRACT_METADATA));
        assertFalse(OperationExecutor.isLibraryDependent(OperationType.CONSTANT));
    }


    @Test
    void isLibraryDependent_null_returnsFalse()
    {
        assertFalse(OperationExecutor.isLibraryDependent(null));
    }

    // -----------------------------------------------------------------------
    // LIBRARY_NOT_AVAILABLE sentinel + executeOne ruleId variant
    // -----------------------------------------------------------------------


    @Test
    void libraryNotAvailable_sentinel_returnedAndStored()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        Operation op = makeOp("$req", "required_variables");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, null);

        Object sentinel = vars.get("$req");
        assertNotNull(sentinel);
        // The sentinel toString is meaningful for log diagnostics.
        assertEquals("<library not available>", sentinel.toString());
    }


    @Test
    void executeOne_unknownOperator_returnsNull()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = new Operation();
        op.setId("$x");
        op.setOperator("not_a_real_operator");

        Object result = OperationExecutor.executeOne(op, table, NO_RESOLVER, null, new HashMap<>(),
                "RULE-XYZ");
        assertNull(result);
    }


    @Test
    void executeOne_unknownOperator_noRuleId_returnsNull()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = new Operation();
        op.setId("$x");
        op.setOperator("nope");

        // Two-arg overload (legacy) — covers the wrapper line that defaults ruleId to null.
        Object result = OperationExecutor.executeOne(op, table, NO_RESOLVER, null, new HashMap<>());
        assertNull(result);
    }

    // -----------------------------------------------------------------------
    // CONSTANT operator — returns the name field as a literal string
    // -----------------------------------------------------------------------


    @Test
    void constant_returnsName()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$lit", "constant");
        op.setName("SOME-LITERAL");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertEquals("SOME-LITERAL", vars.get("$lit"));
    }

    // -----------------------------------------------------------------------
    // get_column_order_from_dataset
    // -----------------------------------------------------------------------


    @Test
    void getColumnOrderFromDataset_returnsAllColumnNames()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01").col("STUDYID", "S")
                .col("AESEQ", "1").build();

        Operation op = makeOp("$cols", "get_column_order_from_dataset");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) vars.get("$cols");
        assertEquals(List.of("USUBJID", "STUDYID", "AESEQ"), cols);
    }

    // -----------------------------------------------------------------------
    // dataset_names / study_domains — WithInventory path and degraded path
    // -----------------------------------------------------------------------


    @Test
    void datasetNames_withInventory_returnsAvailableDatasets()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        Set<String> available = new LinkedHashSet<>();
        available.add("DM");
        available.add("AE");
        available.add("LB");

        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return available;
            }
        };

        Operation op = makeOp("$names", "dataset_names");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, inv);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$names");
        assertEquals(List.of("DM", "AE", "LB"), result);
    }


    @Test
    void studyDomains_withoutInventory_returnsEmptyList()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$domains", "study_domains");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$domains");
        // An empty list result is still a list (returned from the degraded-resolver fallback).
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // variable_value_count — fan-in across study datasets
    // -----------------------------------------------------------------------


    @Test
    void variableValueCount_withInventory_accumulatesAcrossDatasets()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S02", "S01").name("AE").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01", "S02", "S03").name("DM").build();

        Set<String> available = new LinkedHashSet<>();
        available.add("AE");
        available.add("DM");

        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return "AE".equals(n) ? ae : "DM".equals(n) ? dm : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return available;
            }
        };

        Operation op = makeOp("$counts", "variable_value_count");
        op.setName("USUBJID");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, inv);

        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) vars.get("$counts");
        assertNotNull(counts);
        // Dataset-presence counting (EC-30): a value counts ONCE per dataset family, however many
        // rows carry it. S01 is in AE and DM = 2 ; S02 in AE and DM = 2 ; S03 in DM only = 1.
        assertEquals(2L, counts.get("S01"));
        assertEquals(2L, counts.get("S02"));
        assertEquals(1L, counts.get("S03"));
    }


    @Test
    void variableValueCount_noInventory_usesCurrentTable()
    {
        IDataTable table = MockTable.of().col("X", "a", "b", "a", "c", "").build();

        Operation op = makeOp("$counts", "variable_value_count");
        op.setName("X");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) vars.get("$counts");
        assertNotNull(counts);
        // Degraded mode: the current table is the only family, so every distinct value maps to 1
        // even though "a" occurs twice. Empty string is skipped per collectDistinctValues.
        assertEquals(1L, counts.get("a"));
        assertEquals(1L, counts.get("b"));
        assertEquals(1L, counts.get("c"));
        assertFalse(counts.containsKey(""));
    }


    @Test
    void variableValueCount_nullName_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = new Operation();
        op.setId("$counts");
        op.setOperator("variable_value_count");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) vars.get("$counts");
        assertNotNull(counts);
        assertTrue(counts.isEmpty());
    }


    @Test
    void variableValueCount_missingColumn_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$counts", "variable_value_count");
        op.setName("NOPE");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) vars.get("$counts");
        assertNotNull(counts);
        assertTrue(counts.isEmpty());
    }


    @Test
    void variableValueCount_splitFamily_unionsMembers()
    {
        // LB1 + LB2 are one split family (both DOMAIN=LB). Python concatenates the members before
        // taking .unique(); the pre-EC-30 Java scanned only the first member, so C was invisible.
        IDataTable lb1 = MockTable.of().col("DOMAIN", "LB", "LB").col("LBTESTCD", "A", "B")
                .name("LB1").build();
        IDataTable lb2 = MockTable.of().col("DOMAIN", "LB").col("LBTESTCD", "C").name("LB2")
                .build();

        Map<String, Long> counts = runValueCount("LBTESTCD", inventoryOf("LB1", lb1, "LB2", lb2));

        assertEquals(3, counts.size());
        assertEquals(1L, counts.get("A"));
        assertEquals(1L, counts.get("B"));
        assertEquals(1L, counts.get("C"));
    }


    @Test
    void variableValueCount_splitFamily_sharedValueCountsOnce()
    {
        // The family is unioned, not summed: A is present in both members but the family is ONE
        // dataset for counting purposes. LB1 repeats A so the pre-EC-30 row-occurrence code would
        // yield 2 (it dropped LB2 and counted LB1's two rows) — this now fails against the old
        // impl.
        IDataTable lb1 = MockTable.of().col("DOMAIN", "LB", "LB").col("LBTESTCD", "A", "A")
                .name("LB1").build();
        IDataTable lb2 = MockTable.of().col("DOMAIN", "LB").col("LBTESTCD", "A").name("LB2")
                .build();

        Map<String, Long> counts = runValueCount("LBTESTCD", inventoryOf("LB1", lb1, "LB2", lb2));

        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("A"));
    }


    @Test
    void variableValueCount_letterSuffixSplit_groupsByDataDrivenKey()
    {
        // FAAE/FACM are Findings About splits of FA (both DOMAIN=FA). The family key is the
        // data-driven unsplit name, so they collapse to one family — a name-only key
        // (SplitDatasetUtil.unsplitName) would see two and yield 2. Mirrors parity spec
        // CORE-000358a, which pins the same key for variable_count.
        IDataTable faae = MockTable.of().col("DOMAIN", "FA").col("FATESTCD", "OCCUR").name("FAAE")
                .build();
        IDataTable facm = MockTable.of().col("DOMAIN", "FA").col("FATESTCD", "OCCUR").name("FACM")
                .build();

        Map<String, Long> counts = runValueCount("FATESTCD",
                inventoryOf("FAAE", faae, "FACM", facm));

        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("OCCUR"));
    }


    @Test
    void variableValueCount_domainlessDatasets_stayDistinctFamilies()
    {
        // DELIBERATE DEVIATION FROM UPSTREAM PYTHON (EC-30). SUPPAE and SUPPDM carry no DOMAIN, so
        // upstream's `{dataset.domain: dataset}` dedup collapses both under the None key and counts
        // only the last => X:1. Keying by unsplitNameFromData (SUPP + RDOMAIN) keeps them apart
        // => X:2. Do NOT "fix" this toward upstream; the parity fork is aligned to Java whenever
        // RDOMAIN resolves. SUPPAE repeats X so the pre-EC-30 row-occurrence code would yield 3.
        IDataTable suppae = MockTable.of().col("RDOMAIN", "AE", "AE").col("QNAM", "X", "X")
                .name("SUPPAE").build();
        IDataTable suppdm = MockTable.of().col("RDOMAIN", "DM").col("QNAM", "X").name("SUPPDM")
                .build();

        Map<String, Long> counts = runValueCount("QNAM",
                inventoryOf("SUPPAE", suppae, "SUPPDM", suppdm));

        assertEquals(1, counts.size());
        assertEquals(2L, counts.get("X"));
    }


    @Test
    void variableValueCount_repeatedValueWithinOneDataset_countsOnce()
    {
        // Dataset presence, not row occurrences: three rows of "a" in one family is still 1.
        IDataTable ae = MockTable.of().col("DOMAIN", "AE", "AE", "AE").col("X", "a", "a", "a")
                .name("AE").build();

        Map<String, Long> counts = runValueCount("X", inventoryOf("AE", ae, null, null));

        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("a"));
    }


    @Test
    void variableValueCount_missingAndEmptyValues_skipped()
    {
        // Both arms of the empty/missing skip: "" is a present-but-empty cell, null is
        // missing-or-invalid. pandas .unique() would retain "" and NaN as keys; Java does not.
        // "a" is repeated so the pre-EC-30 row-occurrence code would yield 2 for it.
        IDataTable ae = MockTable.of().col("DOMAIN", "AE", "AE", "AE", "AE")
                .col("X", "a", "a", "", null).name("AE").build();

        Map<String, Long> counts = runValueCount("X", inventoryOf("AE", ae, null, null));

        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("a"));
        assertFalse(counts.containsKey(""));
    }


    @Test
    void variableValueCount_wildcardTemplate_resolvedPerFamily()
    {
        // The -- template resolves against each family member's own DOMAIN cell, so AETESTCD and
        // LBTESTCD are both read for the same authored name "--TESTCD". AE repeats P so the
        // pre-EC-30 row-occurrence code would yield 3.
        IDataTable ae = MockTable.of().col("DOMAIN", "AE", "AE").col("AETESTCD", "P", "P")
                .name("AE").build();
        IDataTable lb = MockTable.of().col("DOMAIN", "LB").col("LBTESTCD", "P").name("LB").build();

        Operation op = makeOp("$counts", "variable_value_count");
        op.setOriginalName("--TESTCD");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae,
                inventoryOf("AE", ae, "LB", lb));
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) vars.get("$counts");

        assertNotNull(counts);
        assertEquals(1, counts.size());
        assertEquals(2L, counts.get("P"));
    }


    @Test
    void variableValueCount_columnAbsentInOneFamily_ignoresIt()
    {
        // The inventory variant of variableValueCount_missingColumn_returnsEmpty: a family without
        // the target column contributes nothing rather than failing the whole operation. "a" is
        // repeated so the pre-EC-30 row-occurrence code would yield 2.
        IDataTable ae = MockTable.of().col("DOMAIN", "AE", "AE").col("X", "a", "a").name("AE")
                .build();
        IDataTable dm = MockTable.of().col("DOMAIN", "DM").col("OTHER", "z").name("DM").build();

        Map<String, Long> counts = runValueCount("X", inventoryOf("AE", ae, "DM", dm));

        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("a"));
    }


    @Test
    void variableValueCount_unresolvableDataset_skipped()
    {
        // An inventory entry the resolver cannot resolve is skipped, not fatal. "a" is repeated so
        // the pre-EC-30 row-occurrence code would yield 2.
        IDataTable ae = MockTable.of().col("DOMAIN", "AE", "AE").col("X", "a", "a").name("AE")
                .build();

        Map<String, Long> counts = runValueCount("X", inventoryOf("AE", ae, "GONE", null));

        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("a"));
    }


    /**
     * Runs {@code variable_value_count} over {@code inv} for the given (already {@code --}-free)
     * target column, returning the resulting map. The primary table is irrelevant on the inventory
     * path, so the first resolvable dataset is used.
     */
    private static Map<String, Long> runValueCount(String targetColumn,
            DatasetResolver.WithInventory inv)
    {
        Operation op = makeOp("$counts", "variable_value_count");
        op.setName(targetColumn);

        IDataTable primary = null;
        for (String name : inv.availableDatasets())
        {
            primary = inv.resolve(name);
            if (primary != null)
            {
                break;
            }
        }
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), primary, inv);
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) vars.get("$counts");
        assertNotNull(counts);
        return counts;
    }


    /**
     * A two-entry inventory preserving declaration order. Either slot may carry a {@code null}
     * table (an unresolvable dataset); a {@code null} name drops the slot entirely.
     */
    private static DatasetResolver.WithInventory inventoryOf(String firstName, IDataTable first,
            String secondName, IDataTable second)
    {
        Map<String, IDataTable> tables = new LinkedHashMap<>();
        if (firstName != null)
        {
            tables.put(firstName, first);
        }
        if (secondName != null)
        {
            tables.put(secondName, second);
        }
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                return tables.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return tables.keySet();
            }
        };
    }

    // -----------------------------------------------------------------------
    // variable_count with name_pattern regex
    // -----------------------------------------------------------------------


    @Test
    void variableCount_namePattern_matchesSuffix()
    {
        IDataTable table = MockTable.of().col("AESEV", "1").col("AESER", "0").col("AESEQ", "1")
                .col("USUBJID", "S").build();

        Operation op = makeOp("$cnt", "variable_count");
        op.setNamePattern("AESE.+");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertEquals(3L, vars.get("$cnt"));
    }


    @Test
    void variableCount_namePattern_invalidRegex_returnsZero()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$cnt", "variable_count");
        op.setNamePattern("[unbalanced");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertEquals(0L, vars.get("$cnt"));
    }


    @Test
    void variableCount_emptyNamePattern_fallsThroughToNamePath()
    {
        // Empty pattern → name_pattern branch is skipped; with no name set, the whole-table count
        // fast path applies and returns the column count.
        IDataTable table = MockTable.of().col("A", "1").col("B", "1").build();

        Operation op = makeOp("$cnt", "variable_count");
        op.setNamePattern("");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertEquals(2L, vars.get("$cnt"));
    }

    // -----------------------------------------------------------------------
    // expandGroupRefs: collection (non-List), string scalar, and unrecognised shape
    // -----------------------------------------------------------------------


    @Test
    void expandGroupRefs_collection_expandsToToString()
    {
        // Set is a Collection but not a List → exercises the Collection branch.
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("EXTRA", "a", "b", "c").build();

        Operation op1 = makeOp("$grouping", "constant");
        op1.setName("USUBJID"); // first op returns "USUBJID" via constant

        // We can't easily wire a Set via constant; use a Collection-producing distinct.
        // Instead, simulate by setting a list group that includes a String reference.
        // Test the String-scalar branch in expandGroupRefs.
        Operation op2 = makeOp("$count", "record_count");
        op2.setGroup(List.of("$grouping"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op1, op2), table, NO_RESOLVER);

        // op2 group becomes ["USUBJID"] (string scalar branch), then grouped record_count runs.
        assertInstanceOf(GroupedResult.class, vars.get("$count"));
        GroupedResult gr = (GroupedResult) vars.get("$count");
        assertEquals(List.of("USUBJID"), gr.groupColumns());
        assertEquals(3, gr.results().size());
    }


    @Test
    void expandGroupRefs_unrecognisedShape_keepsRefLiteral()
    {
        // $foo bound to a Long (not List/Collection/String) → the group entry stays as "$foo".
        // Downstream, grouping on "$foo" fails to find a column, so the grouped op returns null.
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op1 = makeOp("$N", "variable_count");
        Operation op2 = makeOp("$rcount", "record_count");
        op2.setGroup(List.of("$N"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op1, op2), table, NO_RESOLVER);

        // op1 returns a Long → expansion keeps "$N" as-is → grouping fails → null → key absent.
        assertFalse(vars.containsKey("$rcount"));
    }


    @Test
    void expandGroupRefs_nullEntry_passedThrough()
    {
        // A literal null entry in the group list is not a $ ref — passes through unchanged.
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01").build();

        Operation op = makeOp("$count", "record_count");
        java.util.ArrayList<String> group = new java.util.ArrayList<>();
        group.add("USUBJID");
        op.setGroup(group);

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertInstanceOf(GroupedResult.class, vars.get("$count"));
    }


    @Test
    void expandGroupRefs_noDollarRef_skipsExpansion()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02").build();

        Operation op = makeOp("$count", "record_count");
        op.setGroup(List.of("USUBJID")); // no $-refs anywhere

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertInstanceOf(GroupedResult.class, vars.get("$count"));
    }


    @Test
    void expandGroupRefs_preservesAllOperationFields() throws Exception
    {
        // Guards the full field-by-field copy in expandGroupRefs: an op whose group carries a
        // $-ref is rebuilt into a fresh Operation, and EVERY other field must survive that rebuild.
        // Previously offset / min_length / reference_extreme / ordering / name_pattern / expression
        // / names / subtract / dictionary_parent were silently dropped. Invoked reflectively
        // because
        // expandGroupRefs is private static.
        Operation op = makeOp("$diff", "date_diff_days");
        op.setExpression("date_diff_days(RFSTDTC)");
        op.setName("RFSTDTC");
        op.setNames(List.of("VISIT", "VISITNUM"));
        op.setSubtract("$other");
        op.setDomain("DM");
        op.setReference("RFXSTDTC");
        op.setDelimiter(",");
        op.setGroup(List.of("$grp"));
        op.setOffset("RPRFDY");
        op.setReferenceExtreme("max");
        op.setOrdering("SESEQ");
        op.setFilter(Map.of("DSDECOD", "RANDOMIZED"));
        op.setCodelists(List.of("CL1"));
        op.setLevel("PT");
        op.setReturntype("Boolean");
        op.setKeyName("USUBJID");
        op.setKeyValue("S01");
        op.setCtAttribute("CDISCSubmissionValue");
        op.setVersion("2024-03-29");
        op.setCtPackageTypes(List.of("sdtmct"));
        op.setRegex("^.+FL$");
        op.setNamePattern(".+FL$");
        op.setValueIsReference(Boolean.TRUE);
        op.setMinLength(200);
        op.setExternalDictionaryType("meddra");
        op.setDictionaryTermType("PT");
        op.setCaseSensitive(Boolean.TRUE);
        op.setExternalDictionaryTermVariable("AEDECOD");
        op.setDictionaryParent("AESOC");
        op.setQualifyingAnyPopulated(List.of("BASE", "BASEC"));
        op.setOriginalName("--STDTC");

        Map<String, Object> vars = new HashMap<>();
        vars.put("$grp", List.of("USUBJID"));

        java.lang.reflect.Method m = OperationExecutor.class.getDeclaredMethod("expandGroupRefs",
                Operation.class, Map.class);
        m.setAccessible(true);
        Operation copy = (Operation) m.invoke(null, op, vars);

        // The group $-ref was expanded, and the copy is a distinct instance.
        assertNotSame(op, copy, "expected a fresh copy, not the same instance");
        assertEquals(List.of("USUBJID"), copy.getGroup());

        // Every other field survived the rebuild — including the ones that used to be dropped.
        assertEquals("$diff", copy.getId());
        assertEquals("date_diff_days", copy.getOperator());
        assertEquals("date_diff_days(RFSTDTC)", copy.getExpression());
        assertEquals("RFSTDTC", copy.getName());
        assertEquals(List.of("VISIT", "VISITNUM"), copy.getNames());
        assertEquals("$other", copy.getSubtract());
        assertEquals("DM", copy.getDomain());
        assertEquals("RFXSTDTC", copy.getReference());
        assertEquals(",", copy.getDelimiter());
        assertEquals("RPRFDY", copy.getOffset());
        assertEquals("max", copy.getReferenceExtreme());
        assertEquals("SESEQ", copy.getOrdering());
        assertEquals(Map.of("DSDECOD", "RANDOMIZED"), copy.getFilter());
        assertEquals(List.of("CL1"), copy.getCodelists());
        assertEquals("PT", copy.getLevel());
        assertEquals("Boolean", copy.getReturntype());
        assertEquals("USUBJID", copy.getKeyName());
        assertEquals("S01", copy.getKeyValue());
        assertEquals("CDISCSubmissionValue", copy.getCtAttribute());
        assertEquals("2024-03-29", copy.getVersion());
        assertEquals(List.of("sdtmct"), copy.getCtPackageTypes());
        assertEquals("^.+FL$", copy.getRegex());
        assertEquals(".+FL$", copy.getNamePattern());
        assertTrue(copy.getValueIsReference());
        assertEquals(Integer.valueOf(200), copy.getMinLength());
        assertEquals("meddra", copy.getExternalDictionaryType());
        assertEquals("PT", copy.getDictionaryTermType());
        assertTrue(copy.getCaseSensitive());
        assertEquals("AEDECOD", copy.getExternalDictionaryTermVariable());
        assertEquals("AESOC", copy.getDictionaryParent());
        assertEquals(List.of("BASE", "BASEC"), copy.getQualifyingAnyPopulated());
        assertEquals("--STDTC", copy.getOriginalName());
    }

    // -----------------------------------------------------------------------
    // has_mixed_emptiness_within_group
    // -----------------------------------------------------------------------


    @Test
    void hasMixedEmptiness_mixedAndPureGroups()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02", "S03", "S03")
                // S01 mixed: has value + empty ; S02 all populated ; S03 all empty
                .col("VAL", "a", "", "b", "c", "", "").build();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("VAL");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(true, gr.results().get("S01"));
        assertEquals(false, gr.results().get("S02"));
        assertEquals(false, gr.results().get("S03"));
    }


    @Test
    void hasMixedEmptiness_nullName_returnsNull()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01").build();

        Operation op = new Operation();
        op.setId("$mixed");
        op.setOperator("has_mixed_emptiness_within_group");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertFalse(vars.containsKey("$mixed"));
    }


    /**
     * EC-45 §1.3(3) — no {@code group:} at all is the dataset-wide reading, not a malformed
     * operation. It used to yield {@code null} (the operator was the only family-1 dispatch arm
     * with no ungrouped sibling, and the {@code null} was that gap); it now partitions the table
     * into one total group and answers that group's verdict. Here the single row is populated, so
     * the group is homogeneous and the verdict is {@code false}.
     */
    @Test
    void hasMixedEmptiness_nullGroup_isOneTotalGroup()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01").col("VAL", "a").build();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("VAL"); // no group set

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(List.of(), gr.groupColumns());
        assertEquals(List.of(false), List.copyOf(gr.results().values()));
        assertEquals(false, gr.defaultForMissingKey());
    }


    /**
     * EC-45 §1.3(3) — a dataset-wide group whose rows really are mixed still answers {@code true},
     * so the widening reports rather than merely no-firing.
     */
    @Test
    void hasMixedEmptiness_nullGroup_datasetWideMixedFires()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02").col("VAL", "a", "").build();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("VAL"); // no group set

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(List.of(true), List.copyOf(gr.results().values()));
    }


    /**
     * EC-45 §1.3(2) — an absent subject column is all-missing, all-missing is homogeneous, and
     * homogeneous is NOT mixed: every group answers {@code false} instead of the operation
     * collapsing to {@code null}. The same method already returned {@code false} for the identical
     * fact reached through EC-23's qualifier filter; one method now gives one answer.
     */
    @Test
    void hasMixedEmptiness_missingColumn_isNotMixed()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02").build();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("NOPE");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(List.of(false, false), List.copyOf(gr.results().values()));
        assertEquals(false, gr.defaultForMissingKey());
    }


    /**
     * EC-45 §1.3(2) — {@code name} itself is still mandatory. A malformed operation with nothing to
     * read is not a dataset-shape fact and keeps yielding {@code null}.
     */
    @Test
    void hasMixedEmptiness_noNameStillNull()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01").build();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertFalse(vars.containsKey("$mixed"));
    }


    /**
     * EC-23 — a scenario where the qualifier flips the verdict. S01: only one row qualifies (BASE
     * populated) and its VAL is populated ⇒ NOT mixed; S02: two rows qualify (BASE then BASEC) with
     * populated + empty VAL ⇒ mixed; S03: no row qualifies (BASE/BASEC blank) ⇒ empty scan ⇒ not
     * mixed even though VAL is populated-then-empty across the group.
     */
    private static IDataTable ec23Table()
    {
        return MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02", "S03", "S03")
                .col("BASE", "x", "", "p", "", "", "").col("BASEC", "", "", "", "q", "", "")
                .col("VAL", "a", "", "b", "", "z", "").build();
    }


    @Test
    void hasMixedEmptiness_qualifierAbsent_scansAllRows()
    {
        IDataTable table = ec23Table();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("VAL");
        op.setGroup(List.of("USUBJID"));
        // No qualifier ⇒ every row scanned (today's behavior): each group is populated-then-empty.

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(true, gr.results().get("S01"));
        assertEquals(true, gr.results().get("S02"));
        assertEquals(true, gr.results().get("S03"));
    }


    @Test
    void hasMixedEmptiness_qualifierSet_skipsNonQualifyingRows()
    {
        IDataTable table = ec23Table();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("VAL");
        op.setGroup(List.of("USUBJID"));
        op.setQualifyingAnyPopulated(List.of("BASE", "BASEC"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        // S01: only the BASE="x" row qualifies (VAL="a") ⇒ no unpopulated survivor ⇒ not mixed.
        assertEquals(false, gr.results().get("S01"));
        // S02: BASE="p" row (VAL="b") AND BASEC="q" row (VAL="") both qualify ⇒ mixed.
        assertEquals(true, gr.results().get("S02"));
        // S03: neither BASE nor BASEC populated on any row ⇒ all rows skipped ⇒ not mixed.
        assertEquals(false, gr.results().get("S03"));
    }


    @Test
    void hasMixedEmptiness_qualifierBlankOnly_countsAsUnpopulated()
    {
        // A whitespace-only qualifier cell does not qualify (strip().isEmpty()); the single VAL row
        // is skipped, so the group is not mixed.
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01").col("BASE", "  ", "")
                .col("VAL", "a", "").build();

        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("VAL");
        op.setGroup(List.of("USUBJID"));
        op.setQualifyingAnyPopulated(List.of("BASE"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(false, gr.results().get("S01"));
    }

    // -----------------------------------------------------------------------
    // distinct: value_is_reference branch (returns GroupedResult keyed by RDOMAIN)
    // -----------------------------------------------------------------------


    @Test
    void distinct_valueIsReference_returnsRdomainGroupedResult()
    {
        // table mimics a SUPP-style dataset where each row's IDVAR references a column in the
        // dataset named by RDOMAIN.
        IDataTable supp = MockTable.of().col("RDOMAIN", "AE", "AE", "DM")
                .col("IDVAR", "AESEQ", "AESEQ", "STUDYID").name("SUPPAE").build();

        IDataTable ae = MockTable.of().col("USUBJID", "x").col("AESEQ", "1").name("AE").build();
        IDataTable dm = MockTable.of().col("STUDYID", "S").col("USUBJID", "x").name("DM").build();

        DatasetResolver resolver = name -> "AE".equals(name) ? ae : "DM".equals(name) ? dm : null;

        Operation op = makeOp("$dvn", "distinct");
        op.setName("IDVAR");
        op.setValueIsReference(Boolean.TRUE);

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), supp, resolver);
        GroupedResult gr = (GroupedResult) vars.get("$dvn");
        assertNotNull(gr);
        assertEquals(List.of("RDOMAIN"), gr.groupColumns());

        @SuppressWarnings("unchecked")
        List<String> aeCols = (List<String>) gr.results().get("AE");
        assertNotNull(aeCols);
        assertTrue(aeCols.contains("USUBJID"));
        assertTrue(aeCols.contains("AESEQ"));

        @SuppressWarnings("unchecked")
        List<String> dmCols = (List<String>) gr.results().get("DM");
        assertNotNull(dmCols);
        assertTrue(dmCols.contains("STUDYID"));
    }


    @Test
    void distinct_valueIsReference_noRdomainColumn_returnsNull()
    {
        IDataTable t = MockTable.of().col("IDVAR", "AESEQ").build();

        Operation op = makeOp("$dvn", "distinct");
        op.setName("IDVAR");
        op.setValueIsReference(Boolean.TRUE);

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$dvn"));
    }

    // -----------------------------------------------------------------------
    // cross_dataset_variable_metadata
    // -----------------------------------------------------------------------


    @Test
    void crossDatasetVariableMetadata_resolvesOnAnotherDataset()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01").col("AESEQ", "1").name("AE").build();

        Operation op = makeOp("$meta", "cross_dataset_variable_metadata");
        op.setDomain("AE");
        op.setName("AESEQ");

        DatasetResolver resolver = name -> "AE".equals(name) ? ae : null;
        IDataTable base = MockTable.of().col("X", "1").name("DM").build();

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), base, resolver);
        Object result = vars.get("$meta");
        assertNotNull(result);
        assertInstanceOf(VariableMetadataResult.class, result);
    }


    @Test
    void crossDatasetVariableMetadata_wildcardDomain_executesAndScansInventory()
    {
        // Regression: before the executeOne wildcard bypass, resolveTargetTable resolved "*" to
        // null and the operation was skipped — $sdtm_label never reached the variables map, so the
        // rule (CDISC-AD0002/0199) could never fire. The op must now produce a result.
        IDataTable adae = MockTable.of().col("AESEQ", "1").name("ADAE").build();
        IDataTable ae = MockTable.of().col("AESEQ", "1").name("AE").build();

        Operation op = makeOp("$sdtm_label", "cross_dataset_variable_metadata");
        op.setDomain("*");
        op.setName("label");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), adae,
                inventory(Map.of("ADAE", adae, "AE", ae)));
        assertTrue(vars.containsKey("$sdtm_label"));
        assertInstanceOf(VariableMetadataResult.class, vars.get("$sdtm_label"));
    }


    @Test
    void crossDatasetVariableMetadata_wildcardDomain_prefersShortestDatasetName()
    {
        // A variable shared by an SDTM domain (short name "AE") and an ADaM dataset (long name
        // "ADSL"): shortest-name-first ordering makes the SDTM copy win the "first match". Uses
        // data_type (Char/Num) because MockTable stubs column type but not label.
        IDataTable adae = MockTable.of().col("X", "1").name("ADAE").build(); // primary, no shared
                                                                             // var
        IDataTable ae = MockTable.of().colLong("SHARED", 1L).name("AE").build(); // Num
        IDataTable adsl = MockTable.of().col("SHARED", "1").name("ADSL").build(); // Char

        Operation op = makeOp("$sdtm_type", "cross_dataset_variable_metadata");
        op.setDomain("*");
        op.setName("data_type");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), adae,
                inventory(Map.of("ADAE", adae, "AE", ae, "ADSL", adsl)));
        VariableMetadataResult vmr = (VariableMetadataResult) vars.get("$sdtm_type");
        assertEquals("Num", vmr.getForVariable("SHARED")); // AE (shortest) wins, not ADSL
    }


    @Test
    void crossDatasetVariableMetadata_wildcardDomain_excludesPrimaryDataset()
    {
        // A variable in both the primary (ADAE) and an SDTM domain (AE): the primary is excluded so
        // the SDTM copy supplies the value (no self-comparison). A variable present only in the
        // primary resolves to null.
        IDataTable adae = MockTable.of().col("SHARED", "1").col("ONLYADAE", "1").name("ADAE")
                .build();
        IDataTable ae = MockTable.of().colLong("SHARED", 1L).name("AE").build();

        Operation op = makeOp("$sdtm_type", "cross_dataset_variable_metadata");
        op.setDomain("*");
        op.setName("data_type");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), adae,
                inventory(Map.of("ADAE", adae, "AE", ae)));
        VariableMetadataResult vmr = (VariableMetadataResult) vars.get("$sdtm_type");
        assertEquals("Num", vmr.getForVariable("SHARED")); // AE wins; ADAE (primary) skipped
        assertNull(vmr.getForVariable("ONLYADAE")); // only in primary → excluded → null
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> tables)
    {
        Set<String> names = new LinkedHashSet<>(tables.keySet());
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return tables.get(n);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return names;
            }
        };
    }

    // -----------------------------------------------------------------------
    // record_count filter: prefix-wildcard ("&") + missing-or-empty column value
    // -----------------------------------------------------------------------


    @Test
    void recordCount_prefixWildcardFilter_matchesByPrefix()
    {
        IDataTable supp = MockTable.of().col("QNAM", "RACE", "RACE1", "RACE2", "AGE", "RACEX")
                .build();

        Operation op = makeOp("$cnt", "record_count");
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("QNAM", "RACE&");
        op.setFilter(filter);

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), supp, NO_RESOLVER);
        // All five rows start with "RACE" prefix: RACE, RACE1, RACE2, RACEX (not AGE).
        assertEquals(4L, vars.get("$cnt"));
    }


    @Test
    void recordCount_filter_missingColumn_returnsZero()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02").build();

        Operation op = makeOp("$cnt", "record_count");
        op.setFilter(Map.of("NOPE", "anything"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertEquals(0L, vars.get("$cnt"));
    }


    @Test
    void recordCount_filter_emptyStringValueExcluded()
    {
        IDataTable t = MockTable.of().col("VAL", "AE", "", "AE").build();

        Operation op = makeOp("$cnt", "record_count");
        op.setFilter(Map.of("VAL", "AE"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertEquals(2L, vars.get("$cnt"));
    }

    // -----------------------------------------------------------------------
    // resolveTemplate / resolveWildcard: SUPP/SQAP special-case for primary tables
    // -----------------------------------------------------------------------


    @Test
    void resolveWildcard_suppPrimaryTable_substitutesParentPrefix()
    {
        // Table is named "SUPPAE" (a SUPP primary). The "SUPP--" template must resolve to
        // "SUPPAE" using the parent-prefix "AE" — not "SUPPSUPPAE".
        IDataTable suppae = MockTable.of().col("X", "1").name("SUPPAE").build();
        assertEquals("SUPPAE", OperationExecutor.resolveWildcard("SUPP--", suppae));
    }


    @Test
    void resolveWildcard_sqapPrimary_substitutesParentPrefix()
    {
        IDataTable sqap = MockTable.of().col("X", "1").name("SQAPAP").build();
        assertEquals("SQAPAP", OperationExecutor.resolveWildcard("SQAP--", sqap));
    }


    @Test
    void resolveWildcard_suppNoSuffix_passedThrough()
    {
        // Length 4 means tableName is just "SUPP" with nothing after — no parent prefix to
        // extract, so the literal-substitution branch runs.
        IDataTable supp = MockTable.of().col("X", "1").name("SUPP").build();
        assertEquals("SUPPSUPP", OperationExecutor.resolveWildcard("SUPP--", supp));
    }

    // -----------------------------------------------------------------------
    // domainPrefix: DOMAIN column wins over table name
    // -----------------------------------------------------------------------


    @Test
    void domainPrefix_fromDomainColumn()
    {
        IDataTable t = MockTable.of().col("DOMAIN", "AE", "AE").name("AE1").build();
        assertEquals("AE", OperationExecutor.domainPrefix(t));
    }


    @Test
    void domainPrefix_fallback_unsplitTableName()
    {
        // No DOMAIN column → falls back to SplitDatasetUtil.unsplitName("LB1") → "LB".
        IDataTable t = MockTable.of().col("USUBJID", "S").name("LB1").build();
        assertEquals("LB", OperationExecutor.domainPrefix(t));
    }


    @Test
    void domainPrefix_nullTable_returnsEmpty()
    {
        assertEquals("", OperationExecutor.domainPrefix(null));
    }


    @Test
    void domainPrefix_emptyName_returnsEmpty()
    {
        // No DOMAIN column, no name on the table → returns "".
        IDataTable t = MockTable.of().col("X", "1").build();
        assertEquals("", OperationExecutor.domainPrefix(t));
    }

    // -----------------------------------------------------------------------
    // resolveTemplate: null pass-through and no-wildcard pass-through
    // -----------------------------------------------------------------------


    @Test
    void resolveTemplate_nullTemplate()
    {
        IDataTable t = MockTable.of().col("X", "1").name("AE").build();
        assertNull(OperationExecutor.resolveTemplate(null, t));
    }


    @Test
    void resolveTemplate_noWildcard_passedThrough()
    {
        IDataTable t = MockTable.of().col("X", "1").name("AE").build();
        assertEquals("AESEQ", OperationExecutor.resolveTemplate("AESEQ", t));
    }


    @Test
    void resolveTemplate_substitutesFromDomainColumn()
    {
        IDataTable t = MockTable.of().col("DOMAIN", "AE").name("AE1").build();
        assertEquals("AESEQ", OperationExecutor.resolveTemplate("--SEQ", t));
    }

    // -----------------------------------------------------------------------
    // DY (study day) calculation
    // -----------------------------------------------------------------------


    @Test
    void dy_calculationFromDmRfstdtc()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S01", "S02")
                .col("AESTDTC", "2024-01-10", "2024-01-15", "2024-02-01").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01", "S02")
                .col("RFSTDTC", "2024-01-05", "2024-01-25").name("DM").build();

        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;

        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, resolver);
        GroupedResult gr = (GroupedResult) vars.get("$dy");
        assertNotNull(gr);
        // Group cols default to [USUBJID, AESTDTC] because name was provided.
        assertEquals(List.of("USUBJID", "AESTDTC"), gr.groupColumns());
        // Three rows yield three (USUBJID, date) key entries each with a DY value.
        assertEquals(3, gr.results().size());
    }


    @Test
    void dy_withReferenceColumn_usesReferenceInsteadOfRfstdtc()
    {
        // T6: a `reference` on the dy operation recomputes the study day against a non-default DM
        // column (RFXSTDTC) rather than RFSTDTC. DM carries both columns; only the referenced one
        // must drive the result.
        IDataTable ae = MockTable.of().col("USUBJID", "S01").col("AESTDTC", "2024-01-10").build();
        // RFSTDTC=2024-01-05 would give dy=6; RFXSTDTC=2024-01-08 gives dy=3.
        IDataTable dm = MockTable.of().col("USUBJID", "S01").col("RFSTDTC", "2024-01-05")
                .col("RFXSTDTC", "2024-01-08").name("DM").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;

        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC");
        op.setReference("RFXSTDTC");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, resolver);
        GroupedResult gr = (GroupedResult) vars.get("$dy");
        assertNotNull(gr);
        assertEquals(1, gr.results().size());
        // 2024-01-10 − 2024-01-08 = 2 days, +1 (on/after the reference) = 3.
        assertEquals(3L, gr.results().values().iterator().next());
    }


    @Test
    void dy_bareOperation_stillUsesRfstdtc()
    {
        // Regression guard: a dy operation with no `reference` must keep computing against RFSTDTC,
        // ignoring any other reference column present in DM (byte-identical legacy behaviour).
        IDataTable ae = MockTable.of().col("USUBJID", "S01").col("AESTDTC", "2024-01-10").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").col("RFSTDTC", "2024-01-05")
                .col("RFXSTDTC", "2024-01-08").name("DM").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;

        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC");
        // No reference set → RFSTDTC (2024-01-05): 2024-01-10 − 2024-01-05 = 5, +1 = 6.

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, resolver);
        GroupedResult gr = (GroupedResult) vars.get("$dy");
        assertNotNull(gr);
        assertEquals(6L, gr.results().values().iterator().next());
    }


    @ParameterizedTest(name = "{0} with null name → result absent")
    @ValueSource(strings =
    {
            "dy", "max_date", "min_date"
    })
    void ungroupedDateOp_nullName_returnsNull(String operator)
    {
        IDataTable t = MockTable.of().col("X", "1").build();
        Operation op = new Operation();
        op.setId("$d");
        op.setOperator(operator);
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$d"));
    }


    @Test
    void dy_missingDateColumn_returnsNull()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01").build();
        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC"); // not in dataset

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, _ -> null);
        assertFalse(vars.containsKey("$dy"));
    }


    @Test
    void dy_noDmDataset_returnsNull()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01").col("AESTDTC", "2024-01-10").build();
        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC");

        // DM resolver returns null → eval skips
        DatasetResolver resolver = _ -> null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, resolver);
        assertFalse(vars.containsKey("$dy"));
    }


    @Test
    void dy_invalidDate_skipsRow()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S01")
                // First date is parseable, second is too short to even parse the first 10 chars.
                .col("AESTDTC", "2024-01-10", "abc").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").col("RFSTDTC", "2024-01-05").name("DM")
                .build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;

        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, resolver);
        GroupedResult gr = (GroupedResult) vars.get("$dy");
        assertNotNull(gr);
        // One result (for the valid date row); the short string is skipped.
        assertEquals(1, gr.results().size());
    }


    @Test
    void dy_explicitGroup_usesProvidedGroupCols()
    {
        // When op.group is set, the dy dispatch uses it verbatim instead of defaulting to
        // [USUBJID, name].
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S01")
                .col("STUDYID", "STUDY-A", "STUDY-A").col("AESTDTC", "2024-01-10", "2024-01-15")
                .build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").col("RFSTDTC", "2024-01-05").name("DM")
                .build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;

        Operation op = makeOp("$dy", "dy");
        op.setName("AESTDTC");
        op.setGroup(List.of("STUDYID", "USUBJID")); // explicit group

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, resolver);
        GroupedResult gr = (GroupedResult) vars.get("$dy");
        assertNotNull(gr);
        assertEquals(List.of("STUDYID", "USUBJID"), gr.groupColumns());
    }

    // -----------------------------------------------------------------------
    // max_date / min_date with grouping
    // -----------------------------------------------------------------------

    // Note: ungrouped null-name behaviour for max_date / min_date / dy is covered by the
    // parameterised ungroupedDateOp_nullName_returnsNull test above.


    @Test
    void maxDate_ungrouped_missingColumn_returnsNull()
    {
        IDataTable t = MockTable.of().col("X", "1").build();
        Operation op = makeOp("$d", "max_date");
        op.setName("NOPE");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$d"));
    }


    @Test
    void maxDate_missingColumn_returnsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").build();
        Operation op = makeOp("$d", "max_date");
        op.setName("MISSING");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$d"));
    }


    @Test
    void maxGrouped_nullName_returnsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").build();
        Operation op = new Operation();
        op.setId("$m");
        op.setOperator("max");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$m"));
    }


    @Test
    void max_missingColumn_grouped_returnsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").build();
        Operation op = makeOp("$m", "max");
        op.setName("NOPE");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$m"));
    }


    @Test
    void distinct_missingColumn_grouped_returnsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").build();
        Operation op = makeOp("$d", "distinct");
        op.setName("NOPE");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$d"));
    }


    @Test
    void dateExtremeGrouped_nullName_returnsNull()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").build();
        Operation op = new Operation();
        op.setId("$d");
        op.setOperator("max_date");
        op.setGroup(List.of("USUBJID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        assertFalse(vars.containsKey("$d"));
    }

    // -----------------------------------------------------------------------
    // has_mixed_emptiness_within_group: explicit empty-list group
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // variable_count: WithInventory (named) and originalName paths
    // -----------------------------------------------------------------------


    @Test
    void variableCount_withInventoryAndName_iteratesAllDatasets()
    {
        IDataTable ae = MockTable.of().col("AESTDTC", "x").col("USUBJID", "y").name("AE").build();
        IDataTable dm = MockTable.of().col("USUBJID", "y").name("DM").build();

        java.util.Set<String> available = new java.util.LinkedHashSet<>();
        available.add("AE");
        available.add("DM");

        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return "AE".equals(n) ? ae : "DM".equals(n) ? dm : null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return available;
            }
        };

        Operation op = makeOp("$cnt", "variable_count");
        op.setName("USUBJID");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, inv);
        // Both AE and DM have USUBJID → count = 2.
        assertEquals(2L, vars.get("$cnt"));
    }


    @Test
    void variableCount_withInventoryAndOriginalName_reresolvesPerDataset()
    {
        // Template uses -- so each iterated dataset re-resolves with its own prefix.
        IDataTable ae = MockTable.of().col("AELNKGRP", "1").name("AE").build();
        IDataTable cm = MockTable.of().col("CMLNKGRP", "1").name("CM").build();
        IDataTable dm = MockTable.of()
                // no LNKGRP column at all
                .col("USUBJID", "x").name("DM").build();

        java.util.Set<String> available = new java.util.LinkedHashSet<>();
        available.add("AE");
        available.add("CM");
        available.add("DM");

        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return "AE".equals(n) ? ae : "CM".equals(n) ? cm : "DM".equals(n) ? dm : null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return available;
            }
        };

        Operation op = makeOp("$cnt", "variable_count");
        op.setOriginalName("--LNKGRP");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, inv);
        // AE has AELNKGRP, CM has CMLNKGRP, DM has no DMLNKGRP → count = 2.
        assertEquals(2L, vars.get("$cnt"));
    }


    @Test
    void variableCount_withInventory_dedupsSplitDatasets()
    {
        // Both carry DOMAIN=AE, so the data-driven unsplit name
        // (OperationExecutor.unsplitNameFromData) is "AE" for each — they dedup to one logical
        // family, mirroring Python's grouping by SDTMDatasetMetadata.unsplit_name (read from the
        // DOMAIN column, not guessed from the AE1/AE2 names).
        IDataTable ae1 = MockTable.of().col("USUBJID", "S01").col("DOMAIN", "AE").name("AE1")
                .build();
        IDataTable ae2 = MockTable.of().col("USUBJID", "S02").col("DOMAIN", "AE").name("AE2")
                .build();

        java.util.Set<String> available = new java.util.LinkedHashSet<>();
        available.add("AE1");
        available.add("AE2");

        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return "AE1".equals(n) ? ae1 : "AE2".equals(n) ? ae2 : null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return available;
            }
        };

        Operation op = makeOp("$cnt", "variable_count");
        op.setName("USUBJID");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae1, inv);
        // AE1 and AE2 both unsplit to "AE" → counted once.
        assertEquals(1L, vars.get("$cnt"));
    }


    @Test
    void variableCount_noInventory_namedFallback_returnsZeroOrOne()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "x").name("AE").build();

        Operation op = makeOp("$cnt", "variable_count");
        op.setName("USUBJID");
        // NO_RESOLVER is not a WithInventory → fallback path runs.

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, NO_RESOLVER);
        assertEquals(1L, vars.get("$cnt"));

        Operation op2 = makeOp("$cnt2", "variable_count");
        op2.setName("NOPE");

        Map<String, Object> vars2 = OperationExecutor.execute(List.of(op2), ae, NO_RESOLVER);
        assertEquals(0L, vars2.get("$cnt2"));
    }


    @Test
    void variableCount_noInventory_originalNameTemplate()
    {
        IDataTable ae = MockTable.of().col("AESEQ", "1").name("AE").build();

        Operation op = makeOp("$cnt", "variable_count");
        op.setOriginalName("--SEQ");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, NO_RESOLVER);
        // resolveTemplate("--SEQ", AE-table) → "AESEQ"; column exists → 1.
        assertEquals(1L, vars.get("$cnt"));
    }


    @Test
    void variableCount_withInventory_skipsNullResolvedDataset()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "x").name("AE").build();

        java.util.Set<String> available = new java.util.LinkedHashSet<>();
        available.add("AE");
        available.add("MISSING");

        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String n)
            {
                return "AE".equals(n) ? ae : null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return available;
            }
        };

        Operation op = makeOp("$cnt", "variable_count");
        op.setName("USUBJID");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), ae, inv);
        // Only AE counted; MISSING resolves to null and is skipped.
        assertEquals(1L, vars.get("$cnt"));
    }


    /**
     * EC-45 §1.3(3) — an explicitly empty {@code group:} list means the same as no list: one total
     * group over the whole table, not {@code null}.
     */
    @Test
    void hasMixedEmptiness_emptyGroup_isOneTotalGroup()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").col("X", "a").build();
        Operation op = makeOp("$mixed", "has_mixed_emptiness_within_group");
        op.setName("X");
        op.setGroup(List.of());

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = (GroupedResult) vars.get("$mixed");
        assertNotNull(gr);
        assertEquals(List.of(), gr.groupColumns());
        assertEquals(List.of(false), List.copyOf(gr.results().values()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }


    // Suppress unused-import warning for assertSame when not used.
    @SuppressWarnings("unused")
    private static void touchAssertSame(Object a, Object b)
    {
        assertSame(a, b);
    }
}
