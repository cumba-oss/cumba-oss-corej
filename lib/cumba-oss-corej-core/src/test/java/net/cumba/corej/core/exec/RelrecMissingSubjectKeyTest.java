package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ⭐⭐ {@code JKM R5} on the <b>RELREC</b> path — a <b>live violation</b> until 2026-09-21, and not
 * the DROP/KEEP question at all — plus the key-identity case table the manager twin carries
 * ({@code cumba-datatable-manager-local}'s {@code RelrecMissingKeyIdentityTest}), with the E1 / E2
 * rows of {@code PLAN-numeric-cleaning-and-key-text}.
 *
 * <p>
 * {@code RelrecRowExpander} built every join key through {@code cell()} + an {@code nz()} collapse,
 * and {@code cell()} answers {@code null} for a MISSING cell while {@code nz()} mapped {@code null}
 * to {@code ""}. ⇒ a subject whose {@code USUBJID} was <b>missing</b> joined a row whose
 * {@code USUBJID} was a genuine <b>empty string</b>, and two <em>different</em> missing markers
 * joined each other. Owner, 2026-09-21: <i>"a MIS will not join a record with an empty string and a
 * MIS_A will not join a record with a MIS or MIS_B."</i>
 * </p>
 *
 * <p>
 * The table covers every arm of {@code keyCell}: a stored marker; a missing {@code NaN} cell, both
 * one whose payload encodes a marker ({@code MIS_A} / {@code MIS_B}, decoded by {@code forValue})
 * and a bare one (the {@code MIS} default); {@code ""}; a present number of either type; present
 * text; and an absent column. It is crossed with every place a key component is built <b>and</b>
 * probed: the RELREC group key, the record-level {@code bySubject} index and its probe, the
 * dataset-level {@code byStudySubject} index and its probe, and the full-scan fallback's index,
 * probe and subject filter, on {@code USUBJID} and on {@code STUDYID}. On the record-level site a
 * MISSING RELREC {@code USUBJID} falls back to the full scan by design (a blank RELREC
 * {@code USUBJID} is a cross-subject link), so its {@code bySubject} fast path is reached by the
 * rows whose left value is present.
 * </p>
 *
 * <p>
 * ⭐ <b>E1</b> — the {@code LONG above 2^53} row is the regression row for the probe/index
 * asymmetry: the index side keys a present number through {@code keyCell} (the rendering of its
 * double), the probe used to carry the raw {@code cell()} text. Since E7 a 13-digit LONG renders
 * identically through both, so only a LONG beyond {@code 2^53} — {@code 9007199254740993}, whose
 * double is {@code 9007199254740992} — still separates the two paths. The row goes red on the
 * record-level fast path and on the subject-scoped scan when the probe is keyed raw again.
 * </p>
 *
 * <p>
 * ⭐ <b>E2</b> — the numeric text is lossless outside the ruled noise: {@code 1234567890123} and
 * {@code 1234567890124} are two keys (they rendered {@code "1234567890120"} both, and merged), two
 * non-integral values beyond the threshold stay apart, one within it merges, and a {@code STRING}
 * and a {@code LONG} spelling the same 13 digits merge — RELREC is a text join by design
 * ({@code D4-R5}), and the text is now the digits.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why a dedicated class rather than a case in {@code RelrecRowExpanderTest}:</b> every
 * fixture there is built through a helper that takes {@code String[][]}, so it can express
 * {@code ""} but <b>not</b> a missing cell — which is exactly the input under test. A case added
 * there would have looked like coverage while testing the empty string against itself.
 * {@link #checkFixture} proves each fixture holds the value it names.
 * </p>
 */
class RelrecMissingSubjectKeyTest
{

    /** Marks a column the table must not have at all (JKM R7: absent is present-but-empty). */
    private static final Object ABSENT = new Object()
    {

        @Override
        public String toString()
        {
            return "<absent>";
        }
    };

    /** A {@code NaN} whose payload decodes to no marker: the {@code forValue(..., MIS)} arm. */
    private static final double BARE_NAN = Double.NaN;

    /**
     * One row of the key-identity case table, shared (by hand) with the manager and dialog tests.
     *
     * @param name
     *            the row's name, shown as the test's display name.
     * @param left
     *            the AE-side (or left RELREC row's) key cell.
     * @param right
     *            the FA-side (or right RELREC row's) key cell.
     * @param merges
     *            whether the two are one key identity.
     */
    record KeyCase(String name, Object left, Object right, boolean merges)
    {

        @Override
        public String toString()
        {
            return name;
        }


        boolean leftPresent()
        {
            return isPresent(left);
        }
    }

    /** The case table: the violations the nz() collapse committed, the controls, E1 and E2. */
    private static final List<KeyCase> CASES = List.of(
            // the violation: a missing key is not the empty string
            new KeyCase("MIS vs empty", MissingValue.MIS, "", false),
            // two different markers are two identities, whichever two they are
            new KeyCase("MIS_A vs MIS", MissingValue.MIS_A, MissingValue.MIS, false),
            new KeyCase("MIS_A vs MIS_B", MissingValue.MIS_A, MissingValue.MIS_B, false),
            // control, not a detector (the collapse keys P1 and MIS apart too): a missing is
            // never a present value
            new KeyCase("present vs MIS", "P1", MissingValue.MIS, false),
            // NaN-encoded markers keep their identity: forValue decodes each payload
            new KeyCase("NaN-coded MIS_A vs MIS_B", MissingValue.MIS_A.asDouble(),
                    MissingValue.MIS_B.asDouble(), false),
            // a NaN carrying no marker is the generic MIS -- not MIS_A ...
            new KeyCase("bare NaN vs MIS_A", BARE_NAN, MissingValue.MIS_A, false),
            // control ... but MIS itself
            new KeyCase("bare NaN vs MIS", BARE_NAN, MissingValue.MIS, true),
            // control: the SAME marker is one key, so the link still joins (no over-correction)
            new KeyCase("MIS vs MIS", MissingValue.MIS, MissingValue.MIS, true),
            // controls against the nz() collapse: present keys are untouched by the encoding, text
            // and both numeric types
            new KeyCase("present vs present", "P1", "P1", true),
            new KeyCase("DOUBLE vs DOUBLE", 2.5d, 2.5d, true),
            // E2 control: a 13-digit LONG renders its digits on both sides (it detected the raw
            // probe only while the rendering was lossy)
            new KeyCase("LONG 13 digits", 1_234_567_890_123L, 1_234_567_890_123L, true),
            // E1 regression row: beyond 2^53 the key encoding renders the DOUBLE
            // (9007199254740992) while the raw text is the LONG's own digits -- the probe must
            // be key-encoded like the index
            new KeyCase("LONG above 2^53", 9_007_199_254_740_993L, 9_007_199_254_740_993L, true),
            // E2: two integral values 13 digits long are two keys (both rendered
            // "1234567890120" under the old cleaning and merged)
            new KeyCase("LONG 13 digits vs +1", 1_234_567_890_123L, 1_234_567_890_124L, false),
            // E2: two non-integral values beyond the threshold (e = 11: tails 0.4 and 0.3 against
            // 0.1) stay apart; both rendered "123456789012" before. ⚠ Not the e = 12 decade: from
            // 1e12 the units floor (D3) snaps every fraction to the integer, so 1234567890123.4 and
            // ...123.3 are ONE text there by ruling.
            new KeyCase("DOUBLE beyond threshold", 123_456_789_012.4d, 123_456_789_012.3d, false),
            // E7: noise within 1e-12 of the decade is one value (4.9999999999994 was NOT cleaned
            // under the old rule, so this row is red on it)
            new KeyCase("DOUBLE within noise", 4.9999999999994d, 5.0d, true));

    /**
     * E2 / D4-R5: a text key and a numeric key spelling the same digits are one text key. Only on
     * the sites where the two values live in different tables (a LONG column cannot hold text).
     */
    private static final List<KeyCase> CROSS_TYPE_CASES = List.of(
            new KeyCase("STRING vs LONG 13 digits", "1234567890123", 1_234_567_890_123L, true),
            new KeyCase("STRING vs LONG 13 digits +1", "1234567890123", 1_234_567_890_124L, false));

    /** Absent-column rows (JKM R7), only meaningful where a whole STUDYID column can be absent. */
    private static final List<KeyCase> ABSENT_CASES = List.of(
            new KeyCase("absent vs empty", ABSENT, "", true),
            new KeyCase("absent vs MIS", ABSENT, MissingValue.MIS, false));

    /** Which key site the case's two values are placed in. */
    enum Site
    {

        /** RELREC's own group key, USUBJID component (the AE row vs the FA row). */
        RELREC_GROUP_USUBJID,
        /** RELREC's own group key, STUDYID component. */
        RELREC_GROUP_STUDYID,
        /** Record-level link: {@code bySubject} index and its probe (the full scan if missing). */
        RECORD_INDEX_USUBJID,
        /** Dataset-level fast path: {@code byStudySubject} index + probe, USUBJID component. */
        DATASET_INDEX_USUBJID,
        /** Dataset-level fast path, STUDYID component. */
        DATASET_INDEX_STUDYID,
        /** Record-level link with a blank RELREC USUBJID: the full scan, USUBJID component. */
        SCAN_USUBJID,
        /** Subject-scoped dataset-level link: the full scan, STUDYID component. */
        SCAN_STUDYID;

        /** The sites where the left value and the right value sit in two different tables. */
        boolean twoTables()
        {
            return this != RELREC_GROUP_USUBJID && this != RELREC_GROUP_STUDYID;
        }
    }

    private static final String[] RELREC_COLS =
    {
            "STUDYID", "USUBJID", "RDOMAIN", "IDVAR", "IDVARVAL", "RELID"
    };

    private static final String[] AE_COLS =
    {
            "STUDYID", "USUBJID", "AESEQ", "AELNKID"
    };

    private static final String[] FA_COLS =
    {
            "STUDYID", "USUBJID", "FASEQ", "FALNKGRP"
    };

    static Stream<Arguments> cases()
    {
        List<Arguments> out = new ArrayList<>();
        for (Site site : Site.values())
        {
            for (KeyCase c : CASES)
            {
                out.add(Arguments.of(site, c));
            }
            if (site.twoTables())
            {
                for (KeyCase c : CROSS_TYPE_CASES)
                {
                    out.add(Arguments.of(site, c));
                }
            }
        }
        for (KeyCase c : ABSENT_CASES)
        {
            out.add(Arguments.of(Site.DATASET_INDEX_STUDYID, c));
            out.add(Arguments.of(Site.SCAN_STUDYID, c));
        }
        return out.stream();
    }


    private static boolean isPresent(Object aValue)
    {
        return !(aValue instanceof MissingValue) && !(aValue instanceof Double d && d.isNaN())
                && aValue != ABSENT;
    }


    /** A column's type: LONG / DOUBLE if any of its cells is one, else STRING. */
    private static DataValueType typeOf(Object[][] aRows, int aCol)
    {
        DataValueType t = DataValueType.STRING;
        for (Object[] row : aRows)
        {
            if (row[aCol] instanceof Long)
            {
                return DataValueType.LONG;
            }
            if (row[aCol] instanceof Double)
            {
                t = DataValueType.DOUBLE;
            }
        }
        return t;
    }


    private static boolean isNan(Object aValue)
    {
        return aValue instanceof Double d && d.isNaN();
    }


    /**
     * Builds a table; a column whose every cell is {@link #ABSENT} is left out. It is column-cached
     * (the readers' shape) unless it holds a {@code NaN}: a cached DOUBLE column decodes a
     * marker-coded NaN to its {@link MissingValue} and folds a bare one on the way in, so the
     * {@code forValue} arm of {@code keyCell} is reached only through {@link IDataTable}'s default
     * {@code getDataValue} wrap (views, overlays), which keeps the cell a {@code NaN}
     * {@code DataValueDouble} with its payload. That table is built here for the NaN rows.
     */
    private static IDataTable table(String aName, String[] aCols, Object[]... aRows)
    {
        List<Integer> kept = new ArrayList<>();
        List<DataTableColumnMeta> metas = new ArrayList<>();
        for (int c = 0; c < aCols.length; c++)
        {
            int col = c;
            if (aRows.length > 0 && Stream.of(aRows).allMatch(r -> r[col] == ABSENT))
            {
                continue;
            }
            metas.add(DataTableColumnMeta.builder().index(kept.size()).name(aCols[c])
                    .type(typeOf(aRows, c)).build());
            kept.add(c);
        }
        DataTableMeta meta = DataTableMeta.builder().name(aName)
                .columns(metas.toArray(DataTableColumnMeta[]::new)).rowCount(aRows.length)
                .totalRowCount(aRows.length).build();
        if (Stream.of(aRows).flatMap(Stream::of).anyMatch(RelrecMissingSubjectKeyTest::isNan))
        {
            return new IDataTable()
            {

                @Override
                public DataTableMeta getMetaData()
                {
                    return meta;
                }


                @Override
                public long getRowCount()
                {
                    return aRows.length;
                }


                @Override
                public Object getValue(long aRow, int aColumn)
                {
                    return aRows[(int) aRow][kept.get(aColumn)];
                }
            };
        }
        CachedDataTableColumn[] columns = new CachedDataTableColumn[kept.size()];
        for (int i = 0; i < columns.length; i++)
        {
            columns[i] = new CachedDataTableColumn(i, metas.get(i).getType());
            for (Object[] row : aRows)
            {
                columns[i].addElement(row[kept.get(i)]);
            }
            columns[i].complete();
        }
        return new ColumnCachedDataTable(meta, columns);
    }


    /** The three tables for one site, with the case's left value on AE, its right value on FA. */
    private static IDataTable[] fixture(Site aSite, KeyCase aCase)
    {
        Object l = aCase.left();
        Object r = aCase.right();
        // Where the case sits in RELREC, the domains carry the left value when it is present
        // (so a subject-scoped link can find them) and a plain "P1" / "S1" otherwise.
        Object dom = aCase.leftPresent() ? l : "P1";
        Object study = aCase.leftPresent() ? l : "S1";
        return switch (aSite)
        {
        case RELREC_GROUP_USUBJID -> new IDataTable[]
            {
                    table("RELREC", RELREC_COLS, row("S1", l, "AE", "AELNKID", "", "R1"),
                            row("S1", r, "FA", "FALNKGRP", "", "R1")),
                    table("AE", AE_COLS, row("S1", dom, "1", "L1")),
                    table("FA", FA_COLS, row("S1", dom, "9", "L1"))
            };
        case RELREC_GROUP_STUDYID -> new IDataTable[]
            {
                    table("RELREC", RELREC_COLS, row(l, "", "AE", "AELNKID", "", "R1"),
                            row(r, "", "FA", "FALNKGRP", "", "R1")),
                    // The domains carry the RELREC row's study when it is present: a populated
                    // RELREC STUDYID names the study the linked rows must be in (T1-2 (c)), so
                    // the case is decided by the group key alone, as this site intends. A
                    // missing one names no study (T1-3 (i)) and "S1" = "S1" then holds (b).
                    table("AE", AE_COLS, row(study, "U1", "1", "L1")),
                    table("FA", FA_COLS, row(study, "U1", "9", "L1"))
            };
        case RECORD_INDEX_USUBJID -> new IDataTable[]
            {
                    // Both RELREC rows carry the LEFT value, so they always group; the case is
                    // then decided by the bySubject index (or, for a missing left, the scan).
                    table("RELREC", RELREC_COLS, row("S1", l, "AE", "AESEQ", "1", "R1"),
                            row("S1", l, "FA", "FASEQ", "9", "R1")),
                    table("AE", AE_COLS, row("S1", l, "1", "L1")),
                    table("FA", FA_COLS, row("S1", r, "9", "L1"))
            };
        case DATASET_INDEX_USUBJID -> new IDataTable[]
            {
                    datasetLevelRelrec("S1", ""), table("AE", AE_COLS, row("S1", l, "1", "L1")),
                    table("FA", FA_COLS, row("S1", r, "9", "L1"))
            };
        case DATASET_INDEX_STUDYID -> new IDataTable[]
            {
                    // A BLANK RELREC STUDYID (T1-3 (i)): no study is named, so the two domain
                    // rows' own STUDYID identity (b) is what decides — the claim of this site.
                    datasetLevelRelrec("", ""), table("AE", AE_COLS, row(l, "U1", "1", "L1")),
                    table("FA", FA_COLS, row(r, "U1", "9", "L1"))
            };
        case SCAN_USUBJID -> new IDataTable[]
            {
                    table("RELREC", RELREC_COLS, row("S1", "", "AE", "AESEQ", "1", "R1"),
                            row("S1", "", "FA", "FASEQ", "9", "R1")),
                    table("AE", AE_COLS, row("S1", l, "1", "L1")),
                    table("FA", FA_COLS, row("S1", r, "9", "L1"))
            };
        case SCAN_STUDYID -> new IDataTable[]
            {
                    // Blank RELREC STUDYID for the same reason as DATASET_INDEX_STUDYID.
                    datasetLevelRelrec("", "U1"), table("AE", AE_COLS, row(l, "U1", "1", "L1")),
                    table("FA", FA_COLS, row(r, "U1", "9", "L1"))
            };
        };
    }


    /**
     * A dataset-level AELNKID &harr; FALNKGRP link; a blank USUBJID takes the indexed path, a blank
     * STUDYID names no study (T1-3 (i)).
     */
    private static IDataTable datasetLevelRelrec(String aStudy, String aUsubj)
    {
        return table("RELREC", RELREC_COLS, row(aStudy, aUsubj, "AE", "AELNKID", "", "R1"),
                row(aStudy, aUsubj, "FA", "FALNKGRP", "", "R1"));
    }


    private static Object[] row(Object... aCells)
    {
        return aCells;
    }


    /**
     * Non-vacuity of the fixture: the cell must hold what the case names. A stored marker reads
     * back as that marker; a NaN (marker-coded or bare) must stay a missing NaN {@code Double} with
     * the case's payload bits (that is the carrier that reaches keyCell's {@code forValue} arm; a
     * column-cached buffer would decode a coded NaN to its marker first); a present value reads
     * back as itself, typed.
     */
    private static void assertHolds(@Nullable IDataValue aCell, Object aExpected, String aWhere)
    {
        if (aExpected == ABSENT)
        {
            assertNull(aCell, aWhere + " must be an absent column");
            return;
        }
        assertNotNull(aCell, aWhere + " must exist");
        Object got = aCell.getValue();
        if (aExpected instanceof Double d && d.isNaN())
        {
            // The RAW payload bits, not a decoded marker: keyCell's payload-decoding arm is only
            // reached by a missing NaN Double carrying the case's payload.
            assertTrue(
                    aCell.isMissingOrInvalid() && got instanceof Double g
                            && Double.doubleToRawLongBits(g) == Double.doubleToRawLongBits(d),
                    aWhere + " must stay a missing NaN Double with the case's payload (the"
                            + " forValue arm), got " + got);
            return;
        }
        assertEquals(aExpected, got, aWhere + " must hold the value the case names");
    }


    private static @Nullable IDataValue cellOf(IDataTable aTable, String aColumn, int aRow)
    {
        int c = aTable.getMetaData().getColumnIndex(aColumn);
        return c < 0 ? null : aTable.getColumn(c).getDataValue(aRow);
    }


    private static void checkFixture(Site aSite, IDataTable[] aTables, KeyCase aCase)
    {
        switch (aSite)
        {
        case RELREC_GROUP_USUBJID, RELREC_GROUP_STUDYID ->
        {
            String col = aSite == Site.RELREC_GROUP_USUBJID ? "USUBJID" : "STUDYID";
            assertHolds(cellOf(aTables[0], col, 0), aCase.left(), "RELREC left row " + col);
            assertHolds(cellOf(aTables[0], col, 1), aCase.right(), "RELREC right row " + col);
        }
        case RECORD_INDEX_USUBJID, DATASET_INDEX_USUBJID, SCAN_USUBJID ->
        {
            assertHolds(cellOf(aTables[1], "USUBJID", 0), aCase.left(), "AE.USUBJID");
            assertHolds(cellOf(aTables[2], "USUBJID", 0), aCase.right(), "FA.USUBJID");
        }
        case DATASET_INDEX_STUDYID, SCAN_STUDYID ->
        {
            assertHolds(cellOf(aTables[1], "STUDYID", 0), aCase.left(), "AE.STUDYID");
            assertHolds(cellOf(aTables[2], "STUDYID", 0), aCase.right(), "FA.STUDYID");
        }
        }
    }


    private static MatchDataset forwardRelrec()
    {
        MatchDataset md = new MatchDataset();
        md.setName("RELREC");
        return md;
    }


    /**
     * The expansion's pairs as {@code primaryRow:FASEQ} — the FA row is identified by its FASEQ
     * ({@code "9"} in every fixture), read through the expansion's own lookup.
     */
    private static List<String> pairs(IDataTable aAe, IDataTable aRelrec, IDataTable aFa)
    {
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("RELREC", aRelrec);
        tables.put("FA", aFa);
        var exp = RelrecRowExpander.expand(aAe, List.of(forwardRelrec()), tables::get, "R-TEST");
        if (exp == null)
        {
            return List.of();
        }
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookup();
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, "FASEQ"));
        }
        return out;
    }


    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cases")
    void aKeyJoinsOnlyWithItsOwnIdentity(Site aSite, KeyCase aCase)
    {
        IDataTable[] t = fixture(aSite, aCase);
        checkFixture(aSite, t, aCase);
        assertEquals(aCase.merges() ? List.of("0:9") : List.of(), pairs(t[1], t[0], t[2]),
                "key identity at " + aSite + ": " + aCase.left() + " vs " + aCase.right()
                        + (aCase.merges()
                                ? " must join (an empty result is an over-correction, or a probe"
                                        + " keyed differently from its index)"
                                : " must NOT join (a pair here is the nz() collapse, or the lossy"
                                        + " numeric text)"));
    }

    // ---------------------------------------------------- the original three, MockTable-shaped


    /**
     * Dataset-level RELREC: blank {@code IDVARVAL} on both sides, linking AE.AELNKID ↔ FA.FALNKGRP.
     */
    private static IDataTable mockDatasetLevelRelrec()
    {
        return MockTable.of().col("STUDYID", "S1", "S1").col("RDOMAIN", "AE", "FA")
                .col("USUBJID", "", "").col("IDVAR", "AELNKID", "FALNKGRP").col("IDVARVAL", "", "")
                .col("RELID", "G1", "G1").name("RELREC").build();
    }


    private static List<String> join(IDataTable ae, IDataTable fa, String readColumn)
    {
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("RELREC", mockDatasetLevelRelrec());
        tables.put("FA", fa);
        var exp = RelrecRowExpander.expand(ae, List.of(forwardRelrec()), tables::get, "R-TEST");
        if (exp == null)
        {
            return List.of();
        }
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookup();
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, readColumn));
        }
        return out;
    }


    /** ⛔ The violation: a MISSING USUBJID must not join a present EMPTY one. */
    @Test
    void aMissingSubjectDoesNotJoinAnEmptyOne()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "S1").colSasMissing("USUBJID", (String) null)
                .col("AELNKID", "1").col("AETERM", "REACTION").name("AE").build();
        IDataTable fa = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "FA").col("USUBJID", "")
                .col("FALNKGRP", "1").col("FAOBJ", "ERYTHEMA").name("FA").build();
        assertEquals(List.of(), join(ae, fa, "FAOBJ"),
                "JKM R5: a MissingValue USUBJID is not the empty string. '0:ERYTHEMA' here is the"
                        + " pre-2026-09-21 nz() collapse, in which both sides keyed as \"\"");
    }


    /**
     * ⭐ Non-vacuity control, and the half that makes the test above mean something: the SAME marker
     * on both sides <b>does</b> join. Without this, the assertion above would pass just as well
     * over a fixture whose RELREC link was simply broken.
     */
    @Test
    void theSameMissingMarkerOnBothSidesDoesJoin()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "S1").colSasMissing("USUBJID", (String) null)
                .col("AELNKID", "1").col("AETERM", "REACTION").name("AE").build();
        IDataTable fa = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "FA")
                .colSasMissing("USUBJID", (String) null).col("FALNKGRP", "1")
                .col("FAOBJ", "ERYTHEMA").name("FA").build();
        assertEquals(List.of("0:ERYTHEMA"), join(ae, fa, "FAOBJ"),
                "JKM R4/R5: the same missing marker is one key, so the link still joins. An empty"
                        + " result means the fix over-corrected into a drop");
    }


    /** ⭐ And the ordinary present case still joins — the control for the control. */
    @Test
    void twoPresentSubjectsStillJoin()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "S1").col("USUBJID", "P1").col("AELNKID", "1")
                .col("AETERM", "REACTION").name("AE").build();
        IDataTable fa = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "FA").col("USUBJID", "P1")
                .col("FALNKGRP", "1").col("FAOBJ", "ERYTHEMA").name("FA").build();
        List<String> joined = join(ae, fa, "FAOBJ");
        assertNotNull(joined);
        assertEquals(List.of("0:ERYTHEMA"), joined,
                "control: the plain present-key RELREC join must be untouched by the key encoding");
    }
}
