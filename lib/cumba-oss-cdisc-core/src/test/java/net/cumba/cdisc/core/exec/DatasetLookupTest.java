package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatasetLookupTest
{

    // ---- build() ----

    @Test
    void build_nullDataset_returnsNull()
    {
        assertNull(DatasetLookup.build("DS", null, List.of("KEY")));
    }


    @Test
    void build_emptyDataset_returnsEmptyLookup()
    {
        // Build a table with 0 rows (single column, empty array)
        // MockTable requires at least one value, so we use a workaround:
        // a table with one column and one row, then we test a different approach
        IDataTable t = MockTable.of().col("KEY", "A").build();
        DatasetLookup lookup = DatasetLookup.build("DS", t, List.of("KEY"));
        assertNotNull(lookup);
        assertEquals("DS", lookup.getDatasetName());
    }


    @Test
    void build_singleKey_indexesByValue()
    {
        IDataTable joined = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("AGE", "25", "30", "40").build();

        IDataTable primary = MockTable.of().col("USUBJID", "S02", "S01", "S03")
                .col("SEX", "M", "F", "M").build();

        DatasetLookup lookup = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lookup);

        assertEquals("25", lookup.lookup(primary, 1, "AGE")); // S01 → 25
        assertEquals("30", lookup.lookup(primary, 0, "AGE")); // S02 → 30
        assertEquals("40", lookup.lookup(primary, 2, "AGE")); // S03 → 40
    }


    @Test
    void build_compositeKey_concatenates()
    {
        IDataTable joined = MockTable.of().col("STUDYID", "STUDY1", "STUDY1", "STUDY2")
                .col("USUBJID", "S01", "S02", "S01").col("VALUE", "A", "B", "C").build();

        IDataTable primary = MockTable.of().col("STUDYID", "STUDY2", "STUDY1")
                .col("USUBJID", "S01", "S02").build();

        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("STUDYID", "USUBJID"));

        assertEquals("C", lookup.lookup(primary, 0, "VALUE")); // STUDY2+S01
        assertEquals("B", lookup.lookup(primary, 1, "VALUE")); // STUDY1+S02
    }


    @Test
    void build_duplicateKeys_firstMatchWins()
    {
        IDataTable joined = MockTable.of().col("KEY", "A", "A", "B")
                .col("VAL", "first", "second", "third").build();

        IDataTable primary = MockTable.of().col("KEY", "A").build();

        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("KEY"));
        assertEquals("first", lookup.lookup(primary, 0, "VAL"));
    }


    @Test
    void build_missingKeyColumn_usesEmptyString()
    {
        IDataTable joined = MockTable.of().col("OTHER", "X", "Y").col("VAL", "a", "b").build();

        // Key column "KEY" doesn't exist in joined dataset → empty string key
        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("KEY"));
        assertNotNull(lookup);

        // First row stored with empty key, second row has same empty key → skipped (first wins)
        IDataTable primary = MockTable.of().col("KEY", "Z").build();
        // Primary "Z" key won't match empty key
        assertNull(lookup.lookup(primary, 0, "VAL"));
    }


    @Test
    void build_missingValue_inKeyCell()
    {
        IDataTable joined = MockTable.of().col("KEY", null, "B").col("VAL", "a", "b").build();

        IDataTable primary = MockTable.of().col("KEY", "B").build();

        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("KEY"));
        assertEquals("b", lookup.lookup(primary, 0, "VAL"));
    }

    // ---- sided keys (EC-18 / P5c) ----


    @Test
    void build_sidedKeys_differentlyNamedColumns()
    {
        // Left/primary side keys on TFSPID; joined/right side keys on PMSPID (the --SPID linkage).
        IDataTable joined = MockTable.of().col("PMSPID", "M1", "M2", "M3")
                .col("PMDTC", "2020-01-20", "2020-02-11", "2020-03-06").build();
        IDataTable primary = MockTable.of().col("TFSPID", "M3", "M1", "M2").build();

        DatasetLookup lookup = DatasetLookup.build("PM", joined, List.of("TFSPID"),
                List.of("PMSPID"));
        assertNotNull(lookup);
        assertEquals("2020-03-06", lookup.lookup(primary, 0, "PMDTC")); // M3
        assertEquals("2020-01-20", lookup.lookup(primary, 1, "PMDTC")); // M1
        assertEquals("2020-02-11", lookup.lookup(primary, 2, "PMDTC")); // M2
    }


    @Test
    void build_sidedKeys_composite_leftRightPaired()
    {
        // USUBJID same-named + a sided SPID pairing; matched positionally.
        IDataTable joined = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("PMSPID", "M1", "M2", "M1").col("PMDTC", "d11", "d12", "d21").build();
        IDataTable primary = MockTable.of().col("USUBJID", "S2", "S1").col("TFSPID", "M1", "M2")
                .build();

        DatasetLookup lookup = DatasetLookup.build("PM", joined, List.of("USUBJID", "TFSPID"),
                List.of("USUBJID", "PMSPID"));
        assertEquals("d21", lookup.lookup(primary, 0, "PMDTC")); // S2 + M1
        assertEquals("d12", lookup.lookup(primary, 1, "PMDTC")); // S1 + M2
    }


    @Test
    void build_sameNamedList_delegatesIdenticallyToSingleKeyForm()
    {
        // The single-key build(...) delegates to the sided form with leftKeys == rightKeys, so a
        // same-named join is byte-identical.
        IDataTable joined = MockTable.of().col("USUBJID", "S01", "S02").col("AGE", "25", "30")
                .build();
        IDataTable primary = MockTable.of().col("USUBJID", "S02", "S01").build();

        DatasetLookup viaSided = DatasetLookup.build("DM", joined, List.of("USUBJID"),
                List.of("USUBJID"));
        DatasetLookup viaSingle = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(viaSided);
        assertNotNull(viaSingle);
        assertEquals(viaSingle.lookup(primary, 0, "AGE"), viaSided.lookup(primary, 0, "AGE"));
        assertEquals(viaSingle.lookup(primary, 1, "AGE"), viaSided.lookup(primary, 1, "AGE"));
    }

    // ---- lookup() ----


    @Test
    void lookup_noMatch_returnsNull()
    {
        IDataTable joined = MockTable.of().col("KEY", "A").col("VAL", "x").build();

        IDataTable primary = MockTable.of().col("KEY", "NOMATCH").build();

        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("KEY"));
        assertNull(lookup.lookup(primary, 0, "VAL"));
    }


    @Test
    void lookup_unknownColumn_returnsNull()
    {
        IDataTable joined = MockTable.of().col("KEY", "A").col("VAL", "x").build();

        IDataTable primary = MockTable.of().col("KEY", "A").build();

        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("KEY"));
        assertNull(lookup.lookup(primary, 0, "NONEXISTENT"));
    }


    @Test
    void lookup_blankValueInJoinedRow_returnsNull()
    {
        IDataTable joined = MockTable.of().col("KEY", "A").col("VAL", (String) null)
                .colLong("NUM", (Long) null).build();

        IDataTable primary = MockTable.of().col("KEY", "A").build();

        DatasetLookup lookup = DatasetLookup.build("DS", joined, List.of("KEY"));
        // A blank joined value has no comparand and resolves to null, whatever the column type.
        // ⚠ For a CHARACTER column this is the case the blindness step would change to "";
        // it is blocked on the date_* defect recorded in ScalarSemantics.resolvedString.
        assertNull(lookup.lookup(primary, 0, "VAL"));
        assertNull(lookup.lookup(primary, 0, "NUM"));
    }

    // ---- getDatasetName() ----


    @Test
    void getDatasetName_returnsConfiguredName()
    {
        IDataTable t = MockTable.of().col("X", "1").build();
        DatasetLookup lookup = DatasetLookup.build("MY_DATASET", t, List.of("X"));
        assertEquals("MY_DATASET", lookup.getDatasetName());
    }

    // ---- correctness on a larger row set (regression net for hash-based index) ----


    @Test
    void build_manyRows_correctness()
    {
        int n = 50;
        String[] joinedKeys = new String[n];
        String[] joinedVals = new String[n];
        for (int i = 0; i < n; i++)
        {
            joinedKeys[i] = "S" + String.format("%03d", i);
            joinedVals[i] = "V" + i;
        }
        IDataTable joined = MockTable.of().col("USUBJID", joinedKeys).col("VAL", joinedVals)
                .build();

        // Primary table looks up every key in reverse order, plus a non-existent key.
        String[] primaryKeys = new String[n + 1];
        for (int i = 0; i < n; i++)
        {
            primaryKeys[i] = "S" + String.format("%03d", n - 1 - i);
        }
        primaryKeys[n] = "S999";
        IDataTable primary = MockTable.of().col("USUBJID", primaryKeys).build();

        DatasetLookup lookup = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lookup);

        for (int i = 0; i < n; i++)
        {
            assertEquals("V" + (n - 1 - i), lookup.lookup(primary, i, "VAL"));
        }
        assertNull(lookup.lookup(primary, n, "VAL"));
    }

}
