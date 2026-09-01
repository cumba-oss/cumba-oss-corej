package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.exec.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueDouble;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueString;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import net.cumba.datatable.view.IDataTableView;
import org.junit.jupiter.api.Test;

/**
 * {@code W38-A1} / Fix #249 / EC-75 — the composite grouping-key identity
 * ({@code PLAN-grouping-key-composite-identity.md} phase 3), pinned on data that actually carries
 * every identity.
 *
 * <p>
 * The owner's relation, asserted directly: {@code Empty}, {@code Missing(MIS)},
 * {@code Missing(MIS_UNKNOWN)}, {@code Missing(MIS_ERROR)} and {@code Present(".")} are <b>five
 * distinct group identities</b>; {@code keep_missings: false} still drops every blank (part 1
 * unchanged); and the reporting key distinguishes exactly what the grouping distinguishes, with
 * {@code IndexHelper.buildGroupKey} and {@code GroupedResult.buildKey} in lockstep.
 * </p>
 *
 * <p>
 * ⚠ Fixtures use <b>real</b> {@link IDataValue} objects ({@link DataValueString} /
 * {@link DataValueMissing} / {@link DataValueDouble}) behind mocked column plumbing — a mocked
 * cell's unstubbed defaults would silently answer the very classification under test.
 * {@code MIS_UNKNOWN} / {@code MIS_ERROR} have no loader path into a character column (predecessor
 * plan §9), so direct construction is the only honest fixture for them.
 * </p>
 */
class GroupKeyCompositeIdentityTest
{

    private static final KeyPart DOT = new KeyPart.Present(".");

    // ---------------------------------------------------------------- fixture plumbing

    /** A single-column table over real {@link IDataValue} cells. */
    private static IDataTable table(String colName, IDataValue... cells)
    {
        return table(new String[]
        {
                colName
        }, new IDataValue[][]
        {
                cells
        });
    }


    /**
     * A multi-column table over real {@link IDataValue} cells; {@code cols[i]} backs
     * {@code names[i]}.
     */
    private static IDataTable table(String[] names, IDataValue[][] cols)
    {
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getColumnIndex(anyString())).thenReturn(-1);
        IDataTable t = mock(IDataTable.class);
        for (int i = 0; i < names.length; i++)
        {
            lenient().when(meta.getColumnIndex(names[i])).thenReturn(i);
            IDataValue[] cells = cols[i];
            IDataTableColumn col = mock(IDataTableColumn.class);
            lenient().when(col.getDataValue(anyLong()))
                    .thenAnswer(inv -> cells[((Long) inv.getArgument(0)).intValue()]);
            lenient().when(t.getColumn(i)).thenReturn(col);
        }
        lenient().when(t.getMetaData()).thenReturn(meta);
        lenient().when(t.getRowCount()).thenReturn((long) cols[0].length);
        return t;
    }


    private static IDataValue str(String v)
    {
        return new DataValueString(v);
    }


    private static IDataValue mis(MissingValue m)
    {
        return new DataValueMissing(m);
    }


    /** The five-identity column: {@code ""}, MIS, MIS_UNKNOWN, MIS_ERROR, {@code "."}. */
    private static IDataTable fiveKinds()
    {
        return table("K", str(""), mis(MissingValue.MIS), mis(MissingValue.MIS_UNKNOWN),
                mis(MissingValue.MIS_ERROR), str("."));
    }


    private static Set<Integer> bits(BitSet bs)
    {
        Set<Integer> out = new HashSet<>();
        bs.stream().forEach(out::add);
        return out;
    }

    // ---------------------------------------------------------------- classification


    @Test
    void classificationCoversEveryCellShape()
    {
        GroupKeyPolicy p = GroupKeyPolicy.FOLD_BLANK_KEYS;
        assertEquals(new KeyPart.Present("x"), p.keyPart(str("x")));
        assertEquals(DOT, p.keyPart(str(".")));
        assertSame(KeyPart.EMPTY, p.keyPart(str("")), "Empty is interned");
        assertSame(KeyPart.MISSING_MIS, p.keyPart(mis(MissingValue.MIS)), "Missing is interned");
        assertSame(KeyPart.MISSING_UNKNOWN, p.keyPart(mis(MissingValue.MIS_UNKNOWN)));
        assertSame(KeyPart.MISSING_ERROR, p.keyPart(mis(MissingValue.MIS_ERROR)));
        assertSame(KeyPart.MISSING_UNKNOWN, p.keyPart(null), "a null cell is an anomaly marker");
        // A NaN double is the numeric missing: a payload-less NaN is generic MIS, an encoded
        // payload keeps its marker.
        assertSame(KeyPart.MISSING_MIS, p.keyPart(new DataValueDouble(Double.NaN)));
        assertSame(KeyPart.MISSING_ERROR,
                p.keyPart(new DataValueDouble(MissingValue.MIS_ERROR.asDouble())));
        // Whitespace-only: a real value under MISSING_OR_EMPTY, blank (Empty) under the
        // coalesce notion.
        assertEquals(new KeyPart.Present(" "), p.keyPart(str(" ")));
        assertSame(KeyPart.EMPTY, GroupKeyPolicy.COALESCE_COMPONENT.keyPart(str(" ")));
    }


    @Test
    void presentCannotSpellTheEmptyIdentity()
    {
        assertThrows(IllegalArgumentException.class, () -> new KeyPart.Present(""));
    }

    // ---------------------------------------------------------------- the identity relation


    @Test
    void fiveDistinctIdentities()
    {
        Set<KeyPart> distinct = Set.of(KeyPart.EMPTY, KeyPart.MISSING_MIS, KeyPart.MISSING_UNKNOWN,
                KeyPart.MISSING_ERROR, DOT);
        assertEquals(5, distinct.size());
        // Part 4's literal case — the one a getValueAsString() sentinel would have got wrong:
        // Missing(MIS) renders "." there, and a real "." key would have collided.
        assertNotEquals(KeyPart.MISSING_MIS, DOT);
        assertNotEquals(KeyPart.EMPTY, KeyPart.MISSING_MIS);
        assertNotEquals(KeyPart.MISSING_MIS, KeyPart.MISSING_UNKNOWN);
        // Interning is an optimisation, not the identity: a fresh record still equals its
        // constant.
        assertEquals(KeyPart.MISSING_MIS, new KeyPart.Missing(MissingValue.MIS));
        assertEquals(KeyPart.EMPTY, new KeyPart.Empty());
    }


    @Test
    void reportingFormDistinguishesWhatTheGroupingDistinguishes()
    {
        Set<String> rendered = new HashSet<>();
        for (KeyPart part : List.of(KeyPart.EMPTY, KeyPart.MISSING_MIS, KeyPart.MISSING_UNKNOWN,
                KeyPart.MISSING_ERROR, DOT))
        {
            rendered.add(part.reportingForm());
        }
        assertEquals(5, rendered.size(), "five identities render five distinct reporting keys");
        assertEquals("", KeyPart.EMPTY.reportingForm());
        assertEquals(".", DOT.reportingForm());
        assertTrue(KeyPart.MISSING_MIS.reportingForm().startsWith("\u0001"),
                "a Missing renders an SOH-prefixed token no clinical string can equal");
    }

    // ---------------------------------------------------------------- is_(not_)unique_set


    @Test
    void fiveKindsAreFiveDistinctKeyTuples()
    {
        IDataTable t = fiveKinds();
        BitSet dup = GroupSemantics.uniqueSetViolations(t, 5, List.of("K"), null, true,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertTrue(dup.isEmpty(), "five distinct identities — no duplicates: " + dup);
        BitSet unique = GroupSemantics.uniqueSetViolations(t, 5, List.of("K"), null, false,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertEquals(Set.of(0, 1, 2, 3, 4), bits(unique), "every row is a unique candidate");
    }


    @Test
    void sameKindStillDuplicates()
    {
        IDataTable two = table("K", mis(MissingValue.MIS), mis(MissingValue.MIS));
        assertEquals(Set.of(0, 1),
                bits(GroupSemantics.uniqueSetViolations(two, 2, List.of("K"), null, true,
                        GroupKeyPolicy.FOLD_BLANK_KEYS)),
                "two MIS keys are duplicates of each other");
        IDataTable empties = table("K", str(""), str(""));
        assertEquals(Set.of(0, 1),
                bits(GroupSemantics.uniqueSetViolations(empties, 2, List.of("K"), null, true,
                        GroupKeyPolicy.FOLD_BLANK_KEYS)),
                "two \"\" keys are still duplicates (D.1's keep axis is untouched)");
    }


    @Test
    void keepMissingsFalseStillDropsEveryBlank()
    {
        // Part 1 of the ruling is untouched: under a discarding policy "" and every MissingValue
        // are one blank bucket and all of them drop — only the "." row takes part.
        BitSet unique = GroupSemantics.uniqueSetViolations(fiveKinds(), 5, List.of("K"), null,
                false, GroupKeyPolicy.FOLD_BLANK_KEYS.withKeepMissings(false));
        assertEquals(Set.of(4), bits(unique), "only Present(\".\") participates");
    }

    // ---------------------------------------------------------------- has_multiple_values_for


    @Test
    void blanksStayExcludedFromTheDependencyByType()
    {
        // Key "a" maps to dependents {MIS, "x"} — the MIS dependent is excluded by TYPE (D.13),
        // so the dependency never sees two values and nothing fires.
        IDataTableColumn key = column("a", "a");
        IDataTableColumn dep = columnCells(mis(MissingValue.MIS), str("x"));
        BitSet fired = GroupSemantics.hasMultipleValuesForRows(dep, key, i -> i, 2, false);
        assertTrue(fired.isEmpty(), "a blank dependent never enters the dependency: " + fired);
    }


    @Test
    void includeEmptyKeepsBlankIdentitiesApart()
    {
        // include_empty: blanks participate as real values — and a "" dependent and a MIS
        // dependent are TWO distinct dependents now (kept blanks are separate identities), so the
        // key fires.
        IDataTableColumn key = column("a", "a");
        IDataTableColumn dep = columnCells(mis(MissingValue.MIS), str(""));
        BitSet fired = GroupSemantics.hasMultipleValuesForRows(dep, key, i -> i, 2, true);
        assertEquals(Set.of(0, 1), bits(fired),
                "Empty and Missing(MIS) are distinct participating dependents");
    }

    // ---------------------------------------------------------------- has_same_values


    @Test
    void hasSameValuesSeesTwoBlankKindsAsTwoValues()
    {
        assertTrue(
                GroupSemantics
                        .hasSameValuesViolations(table("K", str(""), mis(MissingValue.MIS)), "K", 2)
                        .isEmpty(),
                "\"\" and MIS are two distinct values — categorization is real");
        assertEquals(Set.of(0, 1),
                bits(GroupSemantics.hasSameValuesViolations(
                        table("K", mis(MissingValue.MIS), mis(MissingValue.MIS)), "K", 2)),
                "one identical value throughout still fires");
    }

    // ---------------------------------------------------------------- (not_)contains_all


    @Test
    void missingNeverSatisfiesARequiredStringValue()
    {
        IDataTable t = table("K", str(""), mis(MissingValue.MIS));
        Set<String> distinct = GroupSemantics.distinctColumnValues(t, "K", 2);
        // Required "" is satisfied by the Empty cell…
        assertTrue(GroupSemantics.notContainsAllVerdict(distinct, List.of(""), 2).isEmpty());
        // …but a required "." is NOT satisfied by Missing(MIS) — part 4: a MissingValue is never
        // equal to any String value, in particular not to the "." its display form spells.
        assertEquals(Set.of(0, 1),
                bits(GroupSemantics.notContainsAllVerdict(distinct, List.of("."), 2)));
    }

    // ---------------------------------------------------------------- next corresponding record


    @Test
    void correspondenceRequiresTheSameIdentity()
    {
        // name[MIS] vs next value[""] — blanks of different kinds do not correspond; MIS vs MIS
        // does (D.5's ""=="" contract, generalised per identity).
        IDataTableColumn name = columnCells(mis(MissingValue.MIS), str("x"));
        IDataTableColumn valueDifferent = columnCells(str("ignored"), str(""));
        IDataTableColumn valueSame = columnCells(str("ignored"), mis(MissingValue.MIS));
        IDataTableColumn order = columnCells(str("1"), str("2"));
        BitSet fires = new BitSet();
        GroupSemantics.flagNoNextCorrespondingRecord(name, valueDifferent, order, new int[]
        {
                0, 1
        }, fires);
        assertEquals(Set.of(0), bits(fires), "Missing(MIS) does not correspond to \"\"");
        BitSet quiet = new BitSet();
        GroupSemantics.flagNoNextCorrespondingRecord(name, valueSame, order, new int[]
        {
                0, 1
        }, quiet);
        assertTrue(quiet.isEmpty(), "Missing(MIS) corresponds to Missing(MIS)");
    }

    // ---------------------------------------------------------------- the reporting key


    @Test
    void reportingKeyLockstep()
    {
        // Block side (IndexHelper.buildGroupKey) and per-row lookup side (GroupedResult.buildKey)
        // must render the same key for the same blank-keyed row — and different keys for the
        // different blank kinds.
        IDataTable t = fiveKinds();
        DataTableMeta meta = t.getMetaData();
        Set<String> keys = new HashSet<>();
        for (long r = 0; r < 5; r++)
        {
            IDataTableView block = mock(IDataTableView.class);
            long row = r;
            lenient().when(block.getRealRow(t, 0)).thenReturn(row);
            String blockKey = IndexHelper.buildGroupKey(block, t, meta, List.of("K"));
            String rowKey = GroupedResult.buildKey(meta, t, List.of("K"), r);
            assertEquals(blockKey, rowKey, "lockstep at row " + r);
            keys.add(rowKey);
        }
        assertEquals(5, keys.size(), "five identities — five reported keys");
    }


    @Test
    void groupedLookupHitsItsOwnBlankKeyedGroup()
    {
        // The failure §5.3 warns about: a value stored under a blank group's key must be FOUND
        // for a row of that group. One column, one MIS-keyed row.
        IDataTable t = table("G", mis(MissingValue.MIS), str("v"));
        DataTableMeta meta = t.getMetaData();
        Map<String, Object> results = new LinkedHashMap<>();
        results.put(GroupedResult.buildKey(meta, t, List.of("G"), 0), "missing-group");
        results.put(GroupedResult.buildKey(meta, t, List.of("G"), 1), "v-group");
        assertEquals("missing-group", results.get(GroupedResult.buildKey(meta, t, List.of("G"), 0)),
                "a blank-keyed row's lookup lands on its own group");
        assertFalse(results.containsKey(""),
                "the Missing group is not filed under the \"\" key any more");
    }

    // ---------------------------------------------------------------- is_inconsistent (D.2)


    @Test
    void inconsistencyTargetParticipationIsATypeTest()
    {
        // Group of three rows, target [MIS, "x", "x"]: the missing target is excluded by TYPE, one
        // distinct value remains, nothing fires — a rendering-based test would have counted "."
        // as a second value and flagged the group (arm1's +9 528 mechanism).
        IDataTable t = table("T", mis(MissingValue.MIS), str("x"), str("x"));
        BitSet fired = GroupSemantics.inconsistentAcrossDatasetViolations(t, "T", List.of(), 3);
        assertTrue(fired.isEmpty(), "a genuinely missing target never participates: " + fired);
    }

    // ---------------------------------------------------------------- coalesced within (EC-24)


    @Test
    void coalescedKeepPathKeepsBlankIdentities()
    {
        // partitionCoalesced's computed-key path under an authored keep_missings: true — the
        // review-found part-4 collision: a KEPT Missing(MIS) singleton used to render its display
        // string "." and silently grouped with a real "." key. The coalesce component [A, B] is
        // all-unpopulated on every row, forcing the computed-key path (and resolving to the one
        // Empty bucket when kept).
        IDataTable t = table(new String[]
        {
                "K", "A", "B"
        }, new IDataValue[][]
        {
                {
                        mis(MissingValue.MIS), str("."), mis(MissingValue.MIS)
                },
                {
                        str(""), str(""), str("")
                },
                {
                        str(""), str(""), str("")
                }
        });
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                List.of(List.of("K"), List.of("A", "B")),
                GroupKeyPolicy.DROP_MISSING_KEYS.withKeepMissings(true));
        Set<Set<Integer>> got = new HashSet<>();
        for (int[] g : groups)
        {
            Set<Integer> s = new HashSet<>();
            for (int r : g)
            {
                s.add(r);
            }
            got.add(s);
        }
        assertEquals(Set.of(Set.of(0, 2), Set.of(1)), got,
                "kept Missing(MIS) groups with itself, never with a real \".\"");
    }


    /** Column of real string cells. */
    private static IDataTableColumn column(String... values)
    {
        IDataValue[] cells = new IDataValue[values.length];
        for (int i = 0; i < values.length; i++)
        {
            cells[i] = str(values[i]);
        }
        return columnCells(cells);
    }


    /** Column over explicit real cells. */
    private static IDataTableColumn columnCells(IDataValue... cells)
    {
        IDataTableColumn col = mock(IDataTableColumn.class);
        lenient().when(col.getDataValue(anyLong()))
                .thenAnswer(inv -> cells[((Long) inv.getArgument(0)).intValue()]);
        return col;
    }

}
