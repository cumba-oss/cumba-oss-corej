package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-identity-safe-join-caches} Phase 3 — {@link KeyCellReader} answers EXACTLY what
 * {@code KEEP_MISSING_KEYS.keyPart(table.getColumn(c).getDataValue(row))} answers, over real tables
 * loaded through the production path, and its fast path genuinely fires.
 *
 * <p>
 * ⚠ This class is byte-identical in both engine twins, so it names no buffer class: the fast read
 * belongs to each datatable twin ({@code CachedDataTableColumn.presentStringValue} /
 * {@code presentNumericValue}), whose OWN differential test covers every buffer class of that repo.
 * Here the values are chosen so the production factory lands them in its various buffers.
 * </p>
 */
class KeyCellReaderTest
{

    /** An unrecognised NaN payload — the value the two buffer families classify differently. */
    private static final double ODD_NAN = Double.longBitsToDouble(0x7ff8000000000123L);

    @AfterEach
    void restoreSwitch()
    {
        KeyCellReader.DirectReads.setEnabledForTest(true);
    }


    /** A one-column real table "K" of the given declared type, loaded the production way. */
    private static IDataTable table(DataValueType aType, @Nullable Object... aValues)
    {
        CachedDataTableColumn col = new CachedDataTableColumn(0, aType);
        for (Object v : aValues)
        {
            col.addElement(v);
        }
        col.complete();
        return wrap(col, aValues.length);
    }


    private static IDataTable wrap(CachedDataTableColumn aColumn, int aRows)
    {
        DataTableColumnMeta meta = DataTableColumnMeta.builder().index(0).name("K").label("K")
                .type(aColumn.getType()).build();
        DataTableMeta tm = DataTableMeta.builder().name("T").label("T")
                .columns(new DataTableColumnMeta[]
                {
                        meta
                }).rowCount(aRows).totalRowCount(aRows).build();
        return new ColumnCachedDataTable(tm, new CachedDataTableColumn[]
        {
                aColumn
        });
    }


    /**
     * Every row — plus two past the end — must read identically through the reader and the old
     * path; a present cell of a listed buffer must take the FAST arm.
     */
    private static void assertExact(String aLabel, IDataTable aTable)
    {
        KeyCellReader reader = KeyCellReader.of(aTable, 0);
        long rows = aTable.getRowCount();
        for (long r = 0; r < rows + 2; r++)
        {
            KeyPart slow;
            try
            {
                slow = GroupKeyPolicy.KEEP_MISSING_KEYS
                        .keyPart(aTable.getColumn(0).getDataValue(r));
            }
            catch (RuntimeException expected)
            {
                try
                {
                    reader.read(r);
                    fail(aLabel + " row " + r + ": the old path threw, the reader did not");
                }
                catch (RuntimeException actual)
                {
                    assertEquals(expected.getClass(), actual.getClass(), aLabel + " row " + r);
                    assertEquals(expected.getMessage(), actual.getMessage(), aLabel + " row " + r);
                }
                continue;
            }
            KeyPart fast = reader.read(r);
            String where = aLabel + " row " + r;
            assertEquals(slow, fast, where);
            assertEquals(slow.getClass(), fast.getClass(), where);
            if (slow instanceof KeyPart.PresentNumber sp
                    && fast instanceof KeyPart.PresentNumber fp)
            {
                assertEquals(Double.doubleToRawLongBits(sp.value()),
                        Double.doubleToRawLongBits(fp.value()), where + " (raw bits)");
            }
        }
    }


    @Test
    void everyTypeBufferAndAwkwardValueReadsExactly()
    {
        List<@Nullable Object> manyStrings = new ArrayList<>();
        for (int i = 0; i < 60; i++)
        {
            manyStrings.add("S" + i);
        }
        manyStrings.addAll(java.util.Arrays.asList("", " ", ".", null, MissingValue.MIS_A,
                MissingValue.MIS, "NaN"));
        assertExact("STRING unique", table(DataValueType.STRING, manyStrings.toArray()));

        List<@Nullable Object> fewStrings = new ArrayList<>();
        for (int i = 0; i < 30; i++)
        {
            fewStrings.addAll(java.util.Arrays.asList("A", "B", "", null, MissingValue.MIS_B));
        }
        assertExact("STRING factor", table(DataValueType.STRING, fewStrings.toArray()));
        assertExact("STRING single", table(DataValueType.STRING, "ONLY", "ONLY", "ONLY"));
        assertExact("STRING numbers", table(DataValueType.STRING, 1.5, 7L, Double.NaN, ODD_NAN));

        List<@Nullable Object> manyLongs = new ArrayList<>();
        for (long i = 0; i < 60; i++)
        {
            manyLongs.add(i * 1_000_003L);
        }
        // Only values BOTH datatable twins' LONG buffers accept (the OSS one refuses a fractional,
        // NaN or non-numeric value); the twins' own tests cover those (review, MEDIUM 1).
        manyLongs.addAll(java.util.Arrays.asList(null, MissingValue.MIS_C, 7, -0.0, (1L << 53) + 1,
                Long.MAX_VALUE, Long.MIN_VALUE + 1));
        assertExact("LONG unique", table(DataValueType.LONG, manyLongs.toArray()));
        List<@Nullable Object> fewLongs = new ArrayList<>();
        for (int i = 0; i < 30; i++)
        {
            fewLongs.addAll(java.util.Arrays.asList(1L, 2L, null, MissingValue.MIS));
        }
        assertExact("LONG factor", table(DataValueType.LONG, fewLongs.toArray()));
        assertExact("LONG single", table(DataValueType.LONG, 5L, 5L, 5L));
        // A column whose one value is MISSING (internally a single-value buffer that reports
        // isMissing).
        assertExact("LONG all-missing", table(DataValueType.LONG, null, null, null));
        assertExact("LONG all-.A",
                table(DataValueType.LONG, MissingValue.MIS_A, MissingValue.MIS_A));

        List<@Nullable Object> manyDoubles = new ArrayList<>();
        for (int i = 0; i < 60; i++)
        {
            manyDoubles.add(i * Math.PI);
        }
        manyDoubles.addAll(java.util.Arrays.asList(-0.0, 0.0, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN, ODD_NAN, MissingValue.MIS_Z.asDouble(),
                MissingValue.MIS, MissingValue.MIS_D, 1e300, Double.MIN_VALUE));
        assertExact("DOUBLE unique", table(DataValueType.DOUBLE, manyDoubles.toArray()));
        List<@Nullable Object> floats = new ArrayList<>();
        for (int i = 0; i < 60; i++)
        {
            floats.add(i + 0.5);
        }
        // Float-exact values (internally the factory then chooses a 32-bit buffer); the other
        // DOUBLE fixtures carry ODD_NAN.
        floats.addAll(
                java.util.Arrays.asList(-0.0, Double.NaN, MissingValue.MIS, MissingValue.MIS_B));
        assertExact("DOUBLE float-exact", table(DataValueType.DOUBLE, floats.toArray()));
        List<@Nullable Object> fewDoubles = new ArrayList<>();
        for (int i = 0; i < 30; i++)
        {
            fewDoubles.addAll(java.util.Arrays.asList(1.25, -0.0, MissingValue.MIS, ODD_NAN));
        }
        assertExact("DOUBLE factor", table(DataValueType.DOUBLE, fewDoubles.toArray()));
        assertExact("DOUBLE single", table(DataValueType.DOUBLE, 2.5, 2.5));
        // (null refused by the OSS DOUBLE buffer; MIS is the portable missing)
        assertExact("DOUBLE all-missing",
                table(DataValueType.DOUBLE, MissingValue.MIS, MissingValue.MIS));
    }


    /**
     * D8: a split-domain union reads through its members exactly — members of different buffer
     * classes and declared types, a member lacking the column, a member that is itself a union, and
     * a member that is a view (review round 1, lane B, LOW: the first version covered STRING only).
     */
    @Test
    void aUnionReadsThroughItsMembersExactly()
    {
        List<@Nullable Object> manyLongs = new ArrayList<>();
        for (long i = 0; i < 40; i++)
        {
            manyLongs.add(i * 7L);
        }
        manyLongs.add(null);
        IDataTable objectLongs = table(DataValueType.LONG, manyLongs.toArray());
        IDataTable singleLong = table(DataValueType.LONG, 9L, 9L);
        IDataTable missingLongs = table(DataValueType.LONG, null, MissingValue.MIS_A);
        CachedDataTableColumn other = new CachedDataTableColumn(0, DataValueType.STRING);
        other.addElement("x");
        other.complete();
        DataTableColumnMeta om = DataTableColumnMeta.builder().index(0).name("OTHER").label("OTHER")
                .type(DataValueType.STRING).build();
        IDataTable lacksK = new ColumnCachedDataTable(
                DataTableMeta.builder().name("T").label("T").columns(new DataTableColumnMeta[]
                {
                        om
                }).rowCount(1).totalRowCount(1).build(), new CachedDataTableColumn[]
                {
                        other
                });
        UnionDataTable inner = new UnionDataTable("LB", singleLong, missingLongs);
        IDataTable view = MockTable.of().colLong("K", 3L, null).build();
        UnionDataTable union = new UnionDataTable("LB", objectLongs, lacksK, inner, view);
        assertExactUnion(union);
        assertExactUnion(new UnionDataTable("LB", table(DataValueType.STRING, "U1", "", null),
                lacksK, table(DataValueType.STRING, "U3", MissingValue.MIS_A)));
    }


    private static void assertExactUnion(UnionDataTable aUnion)
    {
        int k = aUnion.getMetaData().getColumnIndex("K");
        KeyCellReader reader = KeyCellReader.of(aUnion, k);
        for (long r = 0; r < aUnion.getRowCount() + 2; r++)
        {
            KeyPart slow;
            try
            {
                slow = GroupKeyPolicy.KEEP_MISSING_KEYS
                        .keyPart(aUnion.getColumn(k).getDataValue(r));
            }
            catch (RuntimeException expected)
            {
                try
                {
                    reader.read(r);
                    fail("row " + r + ": the old path threw, the reader did not");
                }
                catch (RuntimeException actual)
                {
                    assertEquals(expected.getClass(), actual.getClass(), "row " + r);
                    assertEquals(expected.getMessage(), actual.getMessage(), "row " + r);
                }
                continue;
            }
            assertEquals(slow, reader.read(r), "union row " + r);
        }
    }


    /**
     * The on/off switch: the key-match suites' shapes expand identically with direct reads forced
     * off and on — over REAL tables, where the fast arms fire.
     */
    @Test
    void expansionsAreIdenticalWithDirectReadsOnAndOff()
    {
        IDataTable primary = table(DataValueType.STRING, "P1", "P2", "", null, "P3");
        IDataTable child = realChild();
        MatchDataset md = new MatchDataset();
        md.setName("AE");
        md.setKeys(List.of("K"));
        md.setJoinType("left");
        List<String> on = expansion(primary, child, md);
        KeyCellReader.DirectReads.setEnabledForTest(false);
        List<String> off = expansion(primary, child, md);
        assertEquals(off, on);
        assertEquals(List.of("0:A0", "0:A1", "1:A2", "2:A3", "3:A4", "4:null"), on,
                "the fixture must genuinely match, including the blank and missing keys");
    }


    private static IDataTable realChild()
    {
        CachedDataTableColumn k = new CachedDataTableColumn(0, DataValueType.STRING);
        CachedDataTableColumn id = new CachedDataTableColumn(1, DataValueType.STRING);
        String[][] rows =
        {
                {
                        "P1", "A0"
                },
                {
                        "P1", "A1"
                },
                {
                        "P2", "A2"
                },
                {
                        "", "A3"
                },
                {
                        null, "A4"
                }
        };
        for (String[] row : rows)
        {
            k.addElement(row[0]);
            id.addElement(row[1]);
        }
        k.complete();
        id.complete();
        DataTableColumnMeta km = DataTableColumnMeta.builder().index(0).name("K").label("K")
                .type(DataValueType.STRING).build();
        DataTableColumnMeta im = DataTableColumnMeta.builder().index(1).name("ROWID").label("ROWID")
                .type(DataValueType.STRING).build();
        return new ColumnCachedDataTable(
                DataTableMeta.builder().name("AE").label("AE").columns(new DataTableColumnMeta[]
                {
                        km, im
                }).rowCount(rows.length).totalRowCount(rows.length).build(),
                new CachedDataTableColumn[]
                {
                        k, id
                });
    }


    private static List<String> expansion(IDataTable aPrimary, IDataTable aChild,
            MatchDataset aEntry)
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(aPrimary, List.of(aEntry),
                Map.of("AE", aChild)::get, "R", null);
        assertNotNull(exp);
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookups().get("AE");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, "ROWID"));
        }
        return out;
    }


    /**
     * Phase 3b: the two {@code computeKeyHashSafe} overloads must agree — the joined side of a
     * {@code DatasetLookup} now hashes through readers while a single-row probe still hashes
     * through the table, and a disagreement would make every such probe miss. Includes an absent
     * ({@code -1}) key column.
     */
    @Test
    void theReaderHashEqualsTheTableHash()
    {
        CachedDataTableColumn k = new CachedDataTableColumn(0, DataValueType.STRING);
        CachedDataTableColumn n = new CachedDataTableColumn(1, DataValueType.LONG);
        Object[][] rows =
        {
                {
                        "P1", 1L
                },
                {
                        "", 2L
                },
                {
                        null, null
                },
                {
                        "P2", MissingValue.MIS_A
                }
        };
        for (Object[] row : rows)
        {
            k.addElement(row[0]);
            n.addElement(row[1]);
        }
        k.complete();
        n.complete();
        IDataTable t = new ColumnCachedDataTable(
                DataTableMeta.builder().name("T").label("T").columns(new DataTableColumnMeta[]
                {
                        DataTableColumnMeta.builder().index(0).name("K").label("K")
                                .type(DataValueType.STRING).build(),
                        DataTableColumnMeta.builder().index(1).name("N").label("N")
                                .type(DataValueType.LONG).build()
                }).rowCount(rows.length).totalRowCount(rows.length).build(),
                new CachedDataTableColumn[]
                {
                        k, n
                });
        int[] cols =
        {
                0, -1, 1
        };
        @Nullable
        KeyCellReader[] readers = KeyCellReader.of(t, cols);
        for (int r = 0; r < rows.length; r++)
        {
            assertEquals(KeyHashing.computeKeyHashSafe(t, r, cols),
                    KeyHashing.computeKeyHashSafe(readers, r), "row " + r);
        }
    }


    /**
     * ⭐ Review round 1, lane B, MEDIUM: the dispatcher must CHOOSE the fast reader for a real
     * table, and that reader must ANSWER present cells itself. Equality with the old path alone
     * cannot show either, since always falling back is always exact.
     */
    @Test
    void theDispatcherChoosesTheFastReaderAndItAnswersPresentCellsItself()
    {
        IDataTable real = table(DataValueType.STRING, "A", "B", "", null, "C");
        KeyCellReader reader = KeyCellReader.of(real, 0);
        assertTrue(reader instanceof KeyCellReader.Direct,
                "a ColumnCachedDataTable column must get the direct reader: " + reader);
        KeyCellReader.Direct direct = (KeyCellReader.Direct) reader;
        for (long r : new long[]
        {
                0, 1, 4
        })
        {
            direct.read(r);
        }
        assertEquals(0, direct.fallbacks(), "present cells must be answered by the fast arm");
        direct.read(2);
        direct.read(3);
        assertEquals(2, direct.fallbacks(), "the blank and the missing cell take the old path");

        // The numeric fast path too (review, MEDIUM 2): the datatable tests prove the COLUMN
        // answers, this proves the ENGINE asks — for LONG and DOUBLE, with portable values.
        for (IDataTable numeric : List.of(table(DataValueType.LONG, 5L, 7L, MissingValue.MIS),
                table(DataValueType.DOUBLE, 2.5, 3.5, MissingValue.MIS)))
        {
            KeyCellReader.Direct nd = (KeyCellReader.Direct) KeyCellReader.of(numeric, 0);
            nd.read(0);
            nd.read(1);
            assertEquals(0, nd.fallbacks(), "present numbers must be answered by the fast arm");
            nd.read(2);
            assertEquals(1, nd.fallbacks(), "the missing cell takes the old path");
        }

        UnionDataTable union = new UnionDataTable("LB", real, table(DataValueType.STRING, "D"));
        KeyCellReader ur = KeyCellReader.of(union, union.getMetaData().getColumnIndex("K"));
        assertTrue(ur instanceof KeyCellReader.Union, "a union must get the union reader: " + ur);
        ur.read(5);
        assertTrue(((KeyCellReader.Union) ur).memberReader(1) instanceof KeyCellReader.Direct,
                "a union member that is a real table must get the direct reader");

        KeyCellReader.DirectReads.setEnabledForTest(false);
        assertFalse(KeyCellReader.of(real, 0) instanceof KeyCellReader.Direct,
                "the switch must force the old path");
    }


    /**
     * Review round 1, lane B, LOW: a column the metadata names but the held columns lack used to
     * throw only when a CELL was read — a zero-row table never threw. Building the reader must not
     * move that throw to build time.
     */
    @Test
    void aColumnBeyondTheHeldColumnsDoesNotThrowAtBuildTime()
    {
        IDataTable empty = table(DataValueType.STRING);
        KeyCellReader reader = KeyCellReader.of(empty, 5);
        assertFalse(reader instanceof KeyCellReader.Direct);
    }


    /** A mock (unknown class) always takes the old path — safe, never wrong. */
    @Test
    void anUnknownTableClassTakesTheOldPath()
    {
        IDataTable mock = MockTable.of().col("K", "a", "", null).build();
        KeyCellReader reader = KeyCellReader.of(mock, 0);
        for (long r = 0; r < 3; r++)
        {
            assertEquals(
                    GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(mock.getColumn(0).getDataValue(r)),
                    reader.read(r));
        }
    }
}
