package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plan J1 / J6b coverage for {@link GroupSemantics}:
 *
 * <ul>
 * <li><b>J1</b> — {@code relationshipNotUniqueViolations} excludes a row whose name/value cell is
 * empty or genuinely missing (folded to {@code ""}); such a cell is not a participant, so an
 * all-empty code column no longer flags every row.</li>
 * <li><b>J6b</b> — {@code uniqueSetViolations} honours the optional {@code regex=} normalization (a
 * matching key column groups at its first-regex-match granularity, e.g. a datetime at date
 * granularity), mirroring Python {@code is_unique_set}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GroupSemanticsRelationshipTest
{

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    private static IDataTableColumn col(IDataTable t, String name)
    {
        return t.getColumn(t.getMetaData().getColumnIndex(name));
    }


    private static BitSet relationship(IDataTable t, String name, String value, int rowCount)
    {
        return GroupSemantics.relationshipNotUniqueViolations(col(t, name), col(t, value),
                rowCount);
    }


    private static BitSet relationshipMulti(IDataTable t, String name, List<String> values,
            int rowCount)
    {
        return GroupSemantics.relationshipNotUniqueViolations(t, rowCount, name, values);
    }

    // ---- J1: relationshipNotUniqueViolations null/empty exclusion -----------------------------


    @Test
    void allEmptyCodeColumnFlagsNothing()
    {
        // The pilot MedDRA case: every code cell empty/missing -> "" is not a relationship value,
        // so
        // no row is flagged (was: "" -> {all decodes} -> every row).
        IDataTable t = MockTable.of().col("AEDECOD", "HEADACHE", "NAUSEA", "FEVER")
                .col("AEPTCD", "", null, "").build();
        assertEquals(new BitSet(), relationship(t, "AEDECOD", "AEPTCD", 3));
    }


    @Test
    void mixedEmptyAndCollisionFlagsOnlyPopulatedConflict()
    {
        // (x,1),(x,2) collide -> rows 0,1; row 2 has an empty code (excluded); row 3 is clean.
        IDataTable t = MockTable.of().col("AEDECOD", "x", "x", "y", "z")
                .col("AEPTCD", "1", "2", "", "3").build();
        assertEquals(bits(0, 1), relationship(t, "AEDECOD", "AEPTCD", 4));
    }


    @Test
    void cleanOneToOneWithRepeatsFlagsNothing()
    {
        IDataTable t = MockTable.of().col("AEDECOD", "x", "x", "y").col("AEPTCD", "1", "1", "2")
                .build();
        assertEquals(new BitSet(), relationship(t, "AEDECOD", "AEPTCD", 3));
    }


    @Test
    void genuineManyToOneFlagsAll()
    {
        // x maps to both 1 and 2 (both populated) -> a real violation -> both rows.
        IDataTable t = MockTable.of().col("AEDECOD", "x", "x").col("AEPTCD", "1", "2").build();
        assertEquals(bits(0, 1), relationship(t, "AEDECOD", "AEPTCD", 2));
    }


    @Test
    void decodePopulatedCodeBlankIsNotFlagged_documentsDeliberateNonParity()
    {
        // Same decode, one row coded and one row blank. Python's has_null path would flag this;
        // Java intentionally does NOT (the decode/code coverage rules own that signal). Documents
        // the deliberate non-parity: the blank row is excluded, the decode maps to {1} only -> 0.
        IDataTable t = MockTable.of().col("AEDECOD", "HEADACHE", "HEADACHE").col("AEPTCD", "1", "")
                .build();
        assertEquals(new BitSet(), relationship(t, "AEDECOD", "AEPTCD", 2));
    }

    // ---- multi-column keys= relationship (is_unique_relationship(NAME, keys=[A, B, …])) --------


    @Test
    void multiColumnSingleKeyMatchesPositionalForm()
    {
        // The keys=[VALUE] form (one comparator column) must produce the identical verdict to the
        // two-positional overload — backward-compatibility guard for the shared core.
        IDataTable t = MockTable.of().col("AEDECOD", "x", "x", "y", "z")
                .col("AEPTCD", "1", "2", "", "3").build();
        assertEquals(relationship(t, "AEDECOD", "AEPTCD", 4),
                relationshipMulti(t, "AEDECOD", List.of("AEPTCD"), 4));
    }


    @Test
    void multiColumnNameMapsToTwoDistinctTuplesFlagsBoth()
    {
        // Multi-column shape: ETCD must be a 1:1 with the (TESTRL, TEENRL, TEDUR) tuple. E1 maps to
        // two distinct tuples (A,B,C) and (A,B,D) -> not unique -> both rows flagged. E2 is clean.
        IDataTable t = MockTable.of().col("ETCD", "E1", "E1", "E2").col("TESTRL", "A", "A", "Z")
                .col("TEENRL", "B", "B", "Y").col("TEDUR", "C", "D", "X").build();
        assertEquals(bits(0, 1),
                relationshipMulti(t, "ETCD", List.of("TESTRL", "TEENRL", "TEDUR"), 3));
    }


    @Test
    void multiColumnTupleMapsToTwoNamesFlagsBoth()
    {
        // The reverse direction: the tuple (A,B,C) maps to two distinct ETCDs -> not 1:1 -> both
        // participating rows flagged.
        IDataTable t = MockTable.of().col("ETCD", "E1", "E2", "E3").col("TESTRL", "A", "A", "Q")
                .col("TEENRL", "B", "B", "R").col("TEDUR", "C", "C", "S").build();
        assertEquals(bits(0, 1),
                relationshipMulti(t, "ETCD", List.of("TESTRL", "TEENRL", "TEDUR"), 3));
    }


    @Test
    void multiColumnCleanBijectionFlagsNothing()
    {
        // Each ETCD maps to exactly one tuple and vice versa (repeats allowed) -> nothing fires.
        IDataTable t = MockTable.of().col("ETCD", "E1", "E1", "E2").col("TESTRL", "A", "A", "Z")
                .col("TEENRL", "B", "B", "Y").col("TEDUR", "C", "C", "X").build();
        assertEquals(new BitSet(),
                relationshipMulti(t, "ETCD", List.of("TESTRL", "TEENRL", "TEDUR"), 3));
    }


    @Test
    void multiColumnEmptyComponentExcludesRow()
    {
        // Row 1 has an empty TEDUR component -> the whole tuple is a non-participant, so E1 maps to
        // only (A,B,C) -> no violation; the excluded row is never flagged.
        IDataTable t = MockTable.of().col("ETCD", "E1", "E1").col("TESTRL", "A", "A")
                .col("TEENRL", "B", "B").col("TEDUR", "C", "").build();
        assertEquals(new BitSet(),
                relationshipMulti(t, "ETCD", List.of("TESTRL", "TEENRL", "TEDUR"), 2));
    }


    @Test
    void multiColumnAllValueColumnsAbsentFiresNothing()
    {
        // No comparator column resolves -> the relationship has no value side -> empty verdict.
        IDataTable t = MockTable.of().col("ETCD", "E1", "E1").build();
        assertEquals(new BitSet(), relationshipMulti(t, "ETCD", List.of("TESTRL", "TEENRL"), 2));
    }


    @Test
    void absentNameColumnMatchesAllBlankNameColumn()
    {
        // EC-53 asked, of each of GroupSemantics' three `nameIdx < 0` early-outs, whether it is a
        // carve-out from the all-missing contract or a fast path that already agrees with it.
        // uniqueSetViolations was the carve-out (Fix #143 removed it). THIS one is a fast path,
        // and this test is what says so rather than assuming it: a blank cell is a NON-PARTICIPANT
        // in the relation (J1 above), so an all-blank name column leaves zero participants and
        // flags nothing — the identical answer the early-out returns without looking.
        IDataTable absent = MockTable.of().col("ETVAL", "A", "B", "A").build();
        IDataTable allBlank = MockTable.of().col("ETCD", "", "", "").col("ETVAL", "A", "B", "A")
                .build();

        assertEquals(relationshipMulti(allBlank, "ETCD", List.of("ETVAL"), 3),
                relationshipMulti(absent, "ETCD", List.of("ETVAL"), 3),
                "an absent name column must evaluate exactly like a present-but-all-blank one");
        assertEquals(new BitSet(), relationshipMulti(absent, "ETCD", List.of("ETVAL"), 3),
                "…and the shared answer is 'nothing fires' — no participants, no relation");
        // The single-column overload takes columns, so it cannot be handed an absent one; assert
        // the all-blank half directly, which is the behaviour the early-out stands in for.
        assertEquals(new BitSet(), relationship(allBlank, "ETCD", "ETVAL", 3));
    }

    // ---- J6b: uniqueSetViolations regex normalization -----------------------------------------


    @Test
    void regexCollapsesDatetimeKeyToDateGranularity()
    {
        // Same subject + same date, different times: with the date regex both rows share the key
        // (S1, 2020-01-15) -> duplicates {0,1}.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1")
                .col("VSDTC", "2020-01-15T08:00", "2020-01-15T09:00").build();
        BitSet dup = GroupSemantics.uniqueSetViolations(t, 2, List.of("USUBJID", "VSDTC"),
                "^\\d{4}-\\d{2}-\\d{2}", true, GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertEquals(bits(0, 1), dup);
    }


    @Test
    void withoutRegexDatetimeKeysStayDistinct()
    {
        // No regex: the full datetime is the key -> the two rows differ -> not duplicates.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1")
                .col("VSDTC", "2020-01-15T08:00", "2020-01-15T09:00").build();
        BitSet dup = GroupSemantics.uniqueSetViolations(t, 2, List.of("USUBJID", "VSDTC"), null,
                true, GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertEquals(new BitSet(), dup);
    }


    @Test
    void nonMatchingColumnIsNotNormalized()
    {
        // VSTESTCD does not match the date pattern (sample "SYSBP") -> left verbatim, so two
        // distinct test codes on the same date stay distinct keys -> no duplicates.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1").col("VSTESTCD", "SYSBP", "DIABP")
                .build();
        BitSet dup = GroupSemantics.uniqueSetViolations(t, 2, List.of("USUBJID", "VSTESTCD"),
                "^\\d{4}-\\d{2}-\\d{2}", true, GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertEquals(new BitSet(), dup);
    }


    @Test
    void invalidRegexDisablesNormalization()
    {
        // A malformed pattern never throws here (lenient legacy contract) -> behaves as no-regex.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1")
                .col("VSDTC", "2020-01-15T08:00", "2020-01-15T09:00").build();
        BitSet dup = GroupSemantics.uniqueSetViolations(t, 2, List.of("USUBJID", "VSDTC"), "[",
                true, GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertEquals(new BitSet(), dup);
    }
}
