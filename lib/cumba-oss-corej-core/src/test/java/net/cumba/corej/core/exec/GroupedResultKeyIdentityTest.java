package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import net.cumba.datatable.index.DataTableIndexFactory;
import net.cumba.datatable.index.IDataTableIndex;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.MissingValue;
import net.cumba.datatable.view.IDataTableView;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * ⭐ {@code PLAN-grouping-key-identity} — a grouped operation's result is keyed and looked up by ONE
 * key identity, the {@code KeyPart} identity the grouping itself partitions by.
 *
 * <p>
 * The plan's §1.1 table, through {@code OperationExecutor.executeOne} for every block-keyed and
 * row-keyed evaluator, read per row exactly as the Check reads it
 * ({@link GroupedResult#getForRow}). {@code VISITNUM} carries a noise pair ({@code 4.9999999999994}
 * ×2, then {@code 5.0}) and a signed zero ({@code -0.0}, then {@code 0.0} ×2). Before the plan the
 * result was keyed by the rendered text, so the noise blocks shared the key {@code "5"} and the
 * zero blocks {@code "0"}: the later block overwrote the earlier one and its rows read another
 * group's value — rows 0/1 read block 2's count, and rows 3–5 read a count of 2 over three rows.
 * </p>
 *
 * <p>
 * Expected, by precedent (§3): the noise pair is two groups (register {@code D64h}: <i>"key
 * identity always exact"</i>) and the two zeros are one (register {@code D84}; owner Q4: <i>"-0 and
 * 0 are to be treated as one key everywhere"</i>).
 * </p>
 */
class GroupedResultKeyIdentityTest
{

    private static final long TWO_53 = 9_007_199_254_740_992L;

    private static final Double[] VISITNUM =
    {
            4.9999999999994, 4.9999999999994, 5.0, -0.0, 0.0, 0.0
    };

    private static IDataTable vs()
    {
        return RealTables.of("VS").str("USUBJID", "S1", "S1", "S1", "S1", "S1", "S1")
                .dbl("VISITNUM", VISITNUM).dbl("VSSTRESN", 10.0, 11.0, 99.0, 1.0, 2.0, 3.0)
                .str("VSDTC", "2020-01-01", "2020-01-02", "2020-02-01", "2020-03-01", "2020-03-02",
                        "2020-03-03")
                .str("VSORRES", "A", "", "B", "C", "", "D")
                .dbl("VSSEQ", 1.0, 2.0, 1.0, 1.0, 2.0, 3.0).build();
    }


    private static Operation op(String aOperator, @Nullable String aName, List<String> aGroup)
    {
        Operation o = new Operation();
        o.setId("$p16");
        o.setOperator(aOperator);
        o.setName(aName);
        o.setGroup(aGroup);
        return o;
    }


    private static GroupedResult run(Operation aOp, IDataTable aTable, DatasetResolver aResolver)
    {
        Object result = OperationExecutor.executeOne(aOp, aTable, aResolver, null, Map.of(), "P16",
                null, null, Set.of());
        return assertInstanceOf(GroupedResult.class, result, aOp.getOperator());
    }


    private static GroupedResult run(Operation aOp, IDataTable aTable)
    {
        return run(aOp, aTable,
                d -> aTable.getMetaData().getName().equalsIgnoreCase(d) ? aTable : null);
    }


    /** Every row's value as the Check reads it. */
    private static List<@Nullable Object> perRow(GroupedResult aResult, IDataTable aTable)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(aTable).build();
        List<@Nullable Object> out = new ArrayList<>();
        for (long r = 0; r < aTable.getRowCount(); r++)
        {
            out.add(aResult.getForRowOrDefault(ctx, r));
        }
        return out;
    }

    private static final List<String> GROUP = List.of("USUBJID", "VISITNUM");

    // ---------------------------------------------------------------- family A (block-keyed)

    @Test
    void recordCountCountsEachRowsOwnGroup()
    {
        IDataTable t = vs();
        assertEquals(List.of(2L, 2L, 1L, 3L, 3L, 3L),
                perRow(run(op("record_count", null, GROUP), t), t));
    }


    /**
     * The noise pair alone: two groups, each row reads its own count. ⚠ The sensitivity arms of the
     * plan tell the two axes apart through this test and the next: keying the result by the
     * rendered text again reddens THIS one only (the zero-aware index already forms one zero block,
     * which renders "0"), reverting the datatable's zero fold reddens only the NEXT one.
     */
    @Test
    void theNoisePairAloneIsTwoGroups()
    {
        IDataTable t = RealTables.of("VS").str("USUBJID", "S1", "S1", "S1")
                .dbl("VISITNUM", 4.9999999999994, 4.9999999999994, 5.0).build();
        assertEquals(List.of(2L, 2L, 1L), perRow(run(op("record_count", null, GROUP), t), t));
    }


    /** The two zeros alone: one group of three. */
    @Test
    void theSignedZerosAloneAreOneGroup()
    {
        IDataTable t = RealTables.of("VS").str("USUBJID", "S1", "S1", "S1")
                .dbl("VISITNUM", -0.0, 0.0, 0.0).build();
        assertEquals(List.of(3L, 3L, 3L), perRow(run(op("record_count", null, GROUP), t), t));
    }


    @Test
    void maxIsTheMaxOfEachRowsOwnGroup()
    {
        IDataTable t = vs();
        assertEquals(List.of(11.0, 11.0, 99.0, 3.0, 3.0, 3.0),
                perRow(run(op("max", "VSSTRESN", GROUP), t), t));
    }


    @Test
    void distinctIsTheSetOfEachRowsOwnGroup()
    {
        IDataTable t = vs();
        List<String> noise = List.of("10", "11");
        List<String> zero = List.of("1", "2", "3");
        assertEquals(List.of(noise, noise, List.of("99"), zero, zero, zero),
                perRow(run(op("distinct", "VSSTRESN", GROUP), t), t));
    }


    @Test
    void theDateExtremesAreEachRowsOwnGroups()
    {
        IDataTable t = vs();
        assertEquals(List.of("2020-01-02", "2020-01-02", "2020-02-01", "2020-03-03", "2020-03-03",
                "2020-03-03"), perRow(run(op("max_date", "VSDTC", GROUP), t), t));
        assertEquals(List.of("2020-01-01", "2020-01-01", "2020-02-01", "2020-03-01", "2020-03-01",
                "2020-03-01"), perRow(run(op("min_date", "VSDTC", GROUP), t), t));
    }


    @Test
    void mixedEmptinessIsEachRowsOwnGroups()
    {
        IDataTable t = vs();
        // the noise group {A, ""} is mixed; {B} is not; the zero group {C, "", D} is
        assertEquals(List.of(true, true, false, true, true, true),
                perRow(run(op("has_mixed_emptiness_within_group", "VSORRES", GROUP), t), t));
    }

    // ---------------------------------------------------------------- family B (row-keyed)


    @Test
    void isLastInGroupDoesNotLetTwoNoiseGroupsShareAnOrderingKey()
    {
        IDataTable t = vs();
        Operation o = op("is_last_in_group", null, GROUP);
        o.setOrdering("VSSEQ");
        // groups {0,1} (last: row 1), {2} (row 2) and the zeros {3,4,5} (row 5). Rows 0 and 2 share
        // the ordering value 1 and render one VISITNUM ("5"): keyed by text, row 2's "last"
        // overwrote row 0's "not last".
        assertEquals(List.of(false, true, true, false, false, true), perRow(run(o, t), t));
    }


    @Test
    void studyDayIsEachRowsOwn()
    {
        IDataTable t = vs();
        IDataTable dm = RealTables.of("DM").str("USUBJID", "S1").str("RFSTDTC", "2020-01-01")
                .build();
        Operation o = op("dy", "VSDTC", GROUP);
        GroupedResult gr = run(o, t, d -> "DM".equals(d) ? dm : "VS".equals(d) ? t : null);
        // keyed per (USUBJID, VISITNUM); rows sharing a key share its last-computed day
        assertEquals(List.of(2L, 2L, 32L, 63L, 63L, 63L), perRow(gr, t));
    }


    /**
     * {@code date_diff_days} Mode 2 (family B′): the subtrahend is the earliest {@code SJSTDTC} of
     * the row's {@code (USUBJID, VISITNUM)} group in the FOREIGN dataset. Keyed by text, the two
     * foreign noise groups merged into one ("5") and both target rows got the earlier date.
     */
    @Test
    void dateDiffDaysJoinsTheForeignGroupByIdentity()
    {
        IDataTable target = RealTables.of("XX").str("USUBJID", "S1", "S1", "S1")
                .dbl("VISITNUM", 4.9999999999994, 5.0, -0.0)
                .str("MYDTC", "2020-01-10", "2020-01-10", "2020-01-10").build();
        IDataTable sj = RealTables.of("SJ").str("USUBJID", "S1", "S1", "S1")
                .dbl("VISITNUM", 4.9999999999994, 5.0, 0.0)
                .str("SJSTDTC", "2020-01-01", "2020-01-05", "2020-01-08").build();
        Operation o = op("date_diff_days", "MYDTC", GROUP);
        o.setDomain("SJ");
        o.setReference("SJSTDTC");
        GroupedResult gr = run(o, target, d -> "SJ".equals(d) ? sj : null);
        assertEquals(List.of(9L, 5L, 2L), perRow(gr, target));
    }

    // ---------------------------------------------------------------- the other axes of §3


    @Test
    void aLongBeyondTwoToThe53IsItsOwnGroup()
    {
        IDataTable t = RealTables.of("LB").lng("K", TWO_53, TWO_53 + 1, TWO_53 + 1).build();
        assertEquals(List.of(1L, 2L, 2L),
                perRow(run(op("record_count", null, List.of("K")), t), t));
    }


    @Test
    void aLongAndADoubleOfOneValueAreOneKeyAcrossTables()
    {
        IDataTable eval = RealTables.of("EV").dbl("K", 5.0, 6.0, 7.0).build();
        IDataTable dm = RealTables.of("DM").lng("K", 5L, 5L, 6L).build();
        Operation o = op("record_count", null, List.of("K"));
        o.setDomain("DM");
        GroupedResult gr = run(o, eval, d -> "DM".equals(d) ? dm : null);
        // LONG and DOUBLE are both NUMERIC: no key-type error, and 5L meets 5.0 (D99b)
        assertEquals(List.of(2L, 1L, 0L), perRow(gr, eval));
    }


    @Test
    void theBlanksStayPairwiseDistinct()
    {
        IDataTable num = RealTables.of("N").dbl("K", MissingValue.MIS.asDouble(),
                MissingValue.MIS_A.asDouble(), MissingValue.MIS.asDouble(), 1.0).build();
        assertEquals(List.of(2L, 1L, 2L, 1L),
                perRow(run(op("record_count", null, List.of("K")), num), num));
        IDataTable chr = RealTables.of("C").str("K", "", null, "", "A").build();
        assertEquals(List.of(2L, 1L, 2L, 1L),
                perRow(run(op("record_count", null, List.of("K")), chr), chr));
    }


    /**
     * H1: a dataset lacking EVERY group column is one whole-table group, and every row must find
     * it. With k = 2 its key is {@code GroupKey.of("", "")} on both sides; the all-absent branch
     * used to build the text key {@code "\0"}, which the identity lookup never meets.
     */
    @Test
    void anAllAbsentTwoColumnGroupIsFoundByEveryRow()
    {
        IDataTable t = RealTables.of("DS").str("USUBJID", "S1", "S2", "S3").build();
        GroupedResult gr = run(op("record_count", null, List.of("STUDYID", "SPDEVID")), t);
        assertEquals(List.of(3L, 3L, 3L), perRow(gr, t));
        assertEquals(Set.of(GroupKey.of("", "")), gr.results().keySet());
        GroupedResult one = run(op("record_count", null, List.of("SPDEVID")), t);
        assertEquals(List.of(3L, 3L, 3L), perRow(one, t));
    }


    @Test
    void aPartlyAbsentGroupStillSeparatesTheSurvivingColumn()
    {
        IDataTable t = RealTables.of("DS").str("USUBJID", "S1", "S1", "S2").build();
        assertEquals(List.of(2L, 2L, 1L),
                perRow(run(op("record_count", null, List.of("USUBJID", "EPOCH")), t), t));
    }


    /**
     * Family T stays TEXT by ruling ({@code D4-R5}): a SUPP row's {@code IDVARVAL} text {@code "1"}
     * still finds the parent whose {@code AESEQ} is the NUMBER 1.
     */
    @Test
    void theSuppJoinStaysAText()
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "S1").dbl("AESEQ", 1.0, 2.0)
                .build();
        IDataTable supp = RealTables.of("SUPPAE").str("USUBJID", "S1").str("RDOMAIN", "AE")
                .str("IDVAR", "AESEQ").str("IDVARVAL", "1").str("QNAM", "AETRTEM").str("QVAL", "Y")
                .build();
        Operation o = op("supp_qnam_value", null, List.of());
        o.setDomain("SUPPAE");
        o.setKeyValue("AETRTEM");
        o.setGroup(null);
        GroupedResult gr = run(o, ae, d -> "SUPPAE".equals(d) ? supp : null);
        assertEquals(GroupedResult.KeyMode.TEXT, gr.keyMode());
        assertEquals(java.util.Arrays.asList("Y", null), perRow(gr, ae));
    }


    /**
     * A split-domain union (review round 1, M1): a member that lacks {@code VISITNUM} reads as
     * {@code MIS} on both channels, so its rows form ONE group with a member's stored {@code MIS} —
     * the partition the key identity makes. The raw read answered {@code null} before, the index
     * formed a third block under the {@code MIS} key, and the grouped operation hit the tripwire.
     */
    @Test
    void aSplitDomainUnionGroupsAnAbsentMemberColumnWithAStoredMissing()
    {
        IDataTable lbc1 = RealTables.of("lbc1").str("USUBJID", "S1", "S1")
                .dbl("VISITNUM", MissingValue.MIS.asDouble(), 1.0).build();
        IDataTable lbc2 = RealTables.of("lbc2").str("USUBJID", "S1").build();
        UnionDataTable union = new UnionDataTable("LB", lbc1, lbc2);
        assertEquals(List.of(2L, 1L, 2L), perRow(
                run(op("record_count", null, List.of("USUBJID", "VISITNUM")), union), union));
    }

    // ---------------------------------------------------------------- the tripwire


    /**
     * A block-keyed result refuses a second block with the same key instead of overwriting — the
     * exact shape of the defect, made loud. Forced here with an index that separates what the
     * identity merges: every row its own block.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void twoBlocksWithOneIdentityAreRefused()
    {
        String property = DataTableIndexFactory.class.getName();
        String before = System.getProperty(property);
        System.setProperty(property, OneBlockPerRowIndexFactory.class.getName());
        try
        {
            IDataTable t = RealTables.of("VS").str("USUBJID", "S1", "S2", "S1").build();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> run(op("record_count", null, List.of("USUBJID")), t));
            assertTrue(ex.getMessage().contains("two groups share the key S1"), ex.getMessage());
            assertTrue(ex.getMessage().contains("the block of row 0 and the block of row 2"),
                    ex.getMessage());
        }
        finally
        {
            if (before == null)
            {
                System.clearProperty(property);
            }
            else
            {
                System.setProperty(property, before);
            }
        }
    }


    /**
     * The tripwire claims EVERY block, not only those that write a value (review round 1, M1b): a
     * block without a result (here: no resolvable date) that shares its key with a value-writing
     * block used to be invisible, and its rows silently read the other block's value.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aValueLessBlockSharingAKeyIsRefusedToo()
    {
        String property = DataTableIndexFactory.class.getName();
        String before = System.getProperty(property);
        System.setProperty(property, OneBlockPerRowIndexFactory.class.getName());
        try
        {
            IDataTable t = RealTables.of("VS").str("USUBJID", "S1", "S2", "S1")
                    .str("VSDTC", "", "2020-01-02", "2020-01-03").build();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> run(op("max_date", "VSDTC", List.of("USUBJID")), t));
            assertTrue(ex.getMessage().contains("the block of row 0 and the block of row 2"),
                    ex.getMessage());
        }
        finally
        {
            if (before == null)
            {
                System.clearProperty(property);
            }
            else
            {
                System.setProperty(property, before);
            }
        }
    }


    /**
     * A skipped block's placeholder never reaches the result: the group without a value reads the
     * operator's declared empty result, exactly as before the tripwire claimed it.
     */
    @Test
    void aSkippedBlockReadsTheDeclaredEmptyResult()
    {
        IDataTable t = RealTables.of("VS").str("USUBJID", "S1", "S2", "S1")
                .str("VSDTC", "2020-01-01", "", "2020-01-03").build();
        GroupedResult gr = run(op("max_date", "VSDTC", List.of("USUBJID")), t);
        assertEquals(Set.of("S1"), gr.results().keySet());
        assertEquals(java.util.Arrays.asList("2020-01-03", null, "2020-01-03"), perRow(gr, t));
        IDataTable n = RealTables.of("VS").str("USUBJID", "S1", "S2").str("VSSTRESC", "X", "")
                .build();
        GroupedResult distinct = run(op("distinct", "VSSTRESC", List.of("USUBJID")), n);
        assertEquals(List.of(List.of("X"), List.of()), perRow(distinct, n));
    }

    /**
     * A test index that puts every row in its own block — a stand-in for any index whose partition
     * is finer than the key identity. Public with a no-argument constructor: the factory is loaded
     * by name ({@link DataTableIndexFactory#createInstance()}).
     */
    public static final class OneBlockPerRowIndexFactory extends DataTableIndexFactory
    {

        /** Loaded reflectively. */
        public OneBlockPerRowIndexFactory()
        {
            // no state
        }


        // An override in the internal datatable twin (abstract there), a plain overload in the
        // OSS twin, whose factory has only the two-argument createIndex: this file is shared.
        @SuppressWarnings("MissingOverride")
        public IDataTableIndex createIndex(IDataTable aTable, boolean aSorted, String... aColumns)
        {
            return createIndex(aTable, aColumns);
        }


        // Abstract in the OSS twin, concrete in the internal one -- see above.
        @SuppressWarnings("MissingOverride")
        public IDataTableIndex createIndex(IDataTable aTable, String... aColumns)
        {
            long rows = aTable.getRowCount();
            return new IDataTableIndex()
            {

                @Override
                public long getBlockCount()
                {
                    return rows;
                }


                @Override
                public IDataTableView getBlock(long aIndex)
                {
                    return new IDataTableView()
                    {

                        @Override
                        public long getRowCount(IDataTable aViewTable)
                        {
                            return 1;
                        }


                        @Override
                        public long getRealRow(IDataTable aViewTable, long aRow)
                        {
                            return aIndex;
                        }
                    };
                }
            };
        }
    }
}
