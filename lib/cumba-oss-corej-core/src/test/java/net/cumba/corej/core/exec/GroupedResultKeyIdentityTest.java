package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.view.UnionDataTable;
import net.cumba.datatable.index.DataTableIndexFactory;
import net.cumba.datatable.index.IDataTableIndex;
import net.cumba.datatable.values.MissingValue;
import net.cumba.datatable.view.IDataTableView;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ⭐ {@code PLAN-grouping-key-identity} — a grouped function's result (a grouped operation's when
 * the plan landed) is keyed and looked up by ONE key identity, the {@code KeyPart} identity the
 * grouping itself partitions by.
 *
 * <p>
 * The plan's §1.1 table, through every block-keyed and row-keyed evaluator (the plan drove them
 * through {@code OperationExecutor.executeOne} and read them through
 * {@code GroupedResult.getForRow}, both retired in runbook W8; the registry functions now), read
 * per row exactly as the Check reads it. {@code VISITNUM} carries a noise pair
 * ({@code 4.9999999999994} ×2, then {@code 5.0}) and a signed zero ({@code -0.0}, then {@code 0.0}
 * ×2). Before the plan the result was keyed by the rendered text, so the noise blocks shared the
 * key {@code "5"} and the zero blocks {@code "0"}: the later block overwrote the earlier one and
 * its rows read another group's value — rows 0/1 read block 2's count, and rows 3–5 read a count of
 * 2 over three rows.
 * </p>
 *
 * <p>
 * Expected, by precedent (§3): the noise pair is two groups (register {@code D64h}: <i>"key
 * identity always exact"</i>) and the two zeros are one (register {@code D84}; owner Q4: <i>"-0 and
 * 0 are to be treated as one key everywhere"</i>).
 * </p>
 *
 * <p>
 * ⚠ Every table holding a {@code -0.0} is built BOTH ways ({@code raw=false|true}): since
 * {@code NZL O1} (PLAN-negative-zero-on-load) no DOUBLE buffer stores a {@code -0.0}, so the
 * buffer-built table ({@link RealTables#build()}) runs the production read path over a table whose
 * zeros are all {@code 0.0}, while the raw one ({@link RealTables#buildRaw()}) keeps the
 * {@code -0.0} and is what enters the key-level zero handling kept for computed keys
 * ({@code NZL Q2}). Both must answer the same.
 * </p>
 */
class GroupedResultKeyIdentityTest
{

    private static final long TWO_53 = 9_007_199_254_740_992L;

    private static final Double[] VISITNUM =
    {
            4.9999999999994, 4.9999999999994, 5.0, -0.0, 0.0, 0.0
    };

    private static IDataTable vs(boolean aRaw)
    {
        RealTables b = RealTables.of("VS").str("USUBJID", "S1", "S1", "S1", "S1", "S1", "S1")
                .dbl("VISITNUM", VISITNUM).dbl("VSSTRESN", 10.0, 11.0, 99.0, 1.0, 2.0, 3.0)
                .str("VSDTC", "2020-01-01", "2020-01-02", "2020-02-01", "2020-03-01", "2020-03-02",
                        "2020-03-03")
                .str("VSORRES", "A", "", "B", "C", "", "D")
                .dbl("VSSEQ", 1.0, 2.0, 1.0, 1.0, 2.0, 3.0);
        return aRaw ? b.buildRaw() : b.build();
    }


    /**
     * {@code record_count(group=[…][, domain=…])} as the registry function it is since runbook W6
     * ({@code RecordCount}): every row's count, read through the same identity-key broadcast the
     * retired operation's {@code GroupedResult} lookup used ({@link GroupKeyIdentity#identityKey}
     * on both sides).
     */
    private static List<@Nullable Object> counts(List<String> aGroup, @Nullable String aDomain,
            IDataTable aTable, DatasetResolver aResolver)
    {
        String call = "record_count(group=[" + String.join(", ", aGroup) + "]"
                + (aDomain != null ? ", domain=" + aDomain : "") + ")";
        EvaluationContext ctx = EvaluationContext.builder().table(aTable).datasetResolver(aResolver)
                .ruleId("P16").build();
        net.cumba.corej.core.expr.eval.Vector v = net.cumba.corej.core.expr.eval.ExprCompiler
                .evaluateValueExpression(
                        net.cumba.corej.core.expr.CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        List<@Nullable Object> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add(v.value(row).cell().getValue());
        }
        return out;
    }


    private static List<@Nullable Object> counts(List<String> aGroup, IDataTable aTable)
    {
        return counts(aGroup, null, aTable,
                d -> aTable.getMetaData().getName().equalsIgnoreCase(d) ? aTable : null);
    }


    /**
     * {@code distinct(X, group=[…])} as the registry function it is since runbook W7
     * ({@code Distinct}): every row's list, read through the same identity-key broadcast the
     * retired operation's {@code GroupedResult} lookup used.
     */
    private static List<@Nullable Object> lists(String aCall, IDataTable aTable)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(aTable).ruleId("P16")
                .datasetResolver(
                        d -> aTable.getMetaData().getName().equalsIgnoreCase(d) ? aTable : null)
                .build();
        net.cumba.corej.core.expr.eval.Vector v = net.cumba.corej.core.expr.eval.ExprCompiler
                .evaluateValueExpression(
                        net.cumba.corej.core.expr.CheckExpressionParser.parse(aCall), ctx);
        assertNotNull(v);
        List<@Nullable Object> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add(v.value(row).resolved());
        }
        return out;
    }

    private static final List<String> GROUP = List.of("USUBJID", "VISITNUM");

    /** A rule whose one binding is {@code aBinding} and whose Check fires on every row. */
    private static net.cumba.corej.core.model.Rule functionRule(String aBinding)
    {
        try
        {
            net.cumba.corej.core.model.RulePackage pkg = net.cumba.corej.core.RulePackageLoader
                    .loadFromString("{\"rules\":{\"P16\":{\"Core\":{\"Id\":\"P16\"},"
                            + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"" + aBinding
                            + "\"}],\"Check\":{\"expression\":\"not empty(USUBJID)\"},"
                            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$v\"]}}}}");
            net.cumba.corej.core.model.Rule rule = pkg.getRules().get("P16");
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(aBinding, e);
        }
    }


    /**
     * The per-row value of a W5 grouped aggregate FUNCTION, as the report renders it — the
     * function's own broadcast (one identity key per primary row), read through {@link RuleRunner}.
     */
    private static List<String> perRowFunction(String aBinding, IDataTable aTable)
    {
        return perRowFunction(aBinding, aTable, _ -> null);
    }


    /** {@link #perRowFunction(String, IDataTable)} with the datasets a {@code domain=} reads. */
    private static List<String> perRowFunction(String aBinding, IDataTable aTable,
            DatasetResolver aResolver)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(functionRule(aBinding), aTable,
                aResolver);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().stream().map(v -> v.getValues().get("$v")).toList();
    }

    // ---------------------------------------------------------------- family A (block-keyed)


    @ParameterizedTest(name = "raw={0}")
    @ValueSource(booleans =
    {
            false, true
    })
    void recordCountCountsEachRowsOwnGroup(boolean aRaw)
    {
        IDataTable t = vs(aRaw);
        assertEquals(List.of(2L, 2L, 1L, 3L, 3L, 3L), counts(GROUP, t));
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
        assertEquals(List.of(2L, 2L, 1L), counts(GROUP, t));
    }


    /** The two zeros alone: one group of three. */
    @ParameterizedTest(name = "raw={0}")
    @ValueSource(booleans =
    {
            false, true
    })
    void theSignedZerosAloneAreOneGroup(boolean aRaw)
    {
        RealTables b = RealTables.of("VS").str("USUBJID", "S1", "S1", "S1").dbl("VISITNUM", -0.0,
                0.0, 0.0);
        IDataTable t = aRaw ? b.buildRaw() : b.build();
        assertEquals(List.of(3L, 3L, 3L), counts(GROUP, t));
    }


    @ParameterizedTest(name = "raw={0}")
    @ValueSource(booleans =
    {
            false, true
    })
    void maxIsTheMaxOfEachRowsOwnGroup(boolean aRaw)
    {
        // max is a registry function since W5 (GroupedAggregate): the same claim, read through
        // the function's per-row broadcast — the primary row keys by the identity of its own
        // group columns, so the noise pair stays two groups and the zeros one.
        IDataTable t = vs(aRaw);
        assertEquals(List.of("11", "11", "99", "3", "3", "3"),
                perRowFunction("max(VSSTRESN, group=[USUBJID, VISITNUM])", t));
    }


    @ParameterizedTest(name = "raw={0}")
    @ValueSource(booleans =
    {
            false, true
    })
    void distinctIsTheSetOfEachRowsOwnGroup(boolean aRaw)
    {
        IDataTable t = vs(aRaw);
        List<String> noise = List.of("10", "11");
        List<String> zero = List.of("1", "2", "3");
        assertEquals(List.of(noise, noise, List.of("99"), zero, zero, zero),
                lists("distinct(VSSTRESN, group=[USUBJID, VISITNUM])", t));
    }


    @ParameterizedTest(name = "raw={0}")
    @ValueSource(booleans =
    {
            false, true
    })
    void theDateExtremesAreEachRowsOwnGroups(boolean aRaw)
    {
        IDataTable t = vs(aRaw);
        assertEquals(
                List.of("2020-01-02", "2020-01-02", "2020-02-01", "2020-03-03", "2020-03-03",
                        "2020-03-03"),
                perRowFunction("max_date(VSDTC, group=[USUBJID, VISITNUM])", t));
        assertEquals(
                List.of("2020-01-01", "2020-01-01", "2020-02-01", "2020-03-01", "2020-03-01",
                        "2020-03-01"),
                perRowFunction("min_date(VSDTC, group=[USUBJID, VISITNUM])", t));
    }

    // ---------------------------------------------------------------- family B (row-keyed)


    /**
     * {@code date_diff_days} with a grouped foreign reference (family B′; the retired operation's
     * Mode 2, composed since runbook W2b): the reference is the earliest {@code SJSTDTC} of the
     * row's {@code (USUBJID, VISITNUM)} group in the FOREIGN dataset, a named {@code min_date}
     * argument. Keyed by text, the two foreign noise groups merged into one ("5") and both target
     * rows got the earlier date.
     */
    @ParameterizedTest(name = "raw={0}")
    @ValueSource(booleans =
    {
            false, true
    })
    void dateDiffDaysJoinsTheForeignGroupByIdentity(boolean aRaw)
    {
        RealTables b = RealTables.of("XX").str("USUBJID", "S1", "S1", "S1")
                .dbl("VISITNUM", 4.9999999999994, 5.0, -0.0)
                .str("MYDTC", "2020-01-10", "2020-01-10", "2020-01-10");
        IDataTable target = aRaw ? b.buildRaw() : b.build();
        IDataTable sj = RealTables.of("SJ").str("USUBJID", "S1", "S1", "S1")
                .dbl("VISITNUM", 4.9999999999994, 5.0, 0.0)
                .str("SJSTDTC", "2020-01-01", "2020-01-05", "2020-01-08").build();
        assertEquals(List.of("9", "5", "2"), perRowFunction(
                "date_diff_days(MYDTC, min_date(SJSTDTC, domain=SJ, group=[USUBJID, VISITNUM]))",
                target, d -> "SJ".equals(d) ? sj : null));
    }

    // ---------------------------------------------------------------- the other axes of §3


    @Test
    void aLongBeyondTwoToThe53IsItsOwnGroup()
    {
        IDataTable t = RealTables.of("LB").lng("K", TWO_53, TWO_53 + 1, TWO_53 + 1).build();
        assertEquals(List.of(1L, 2L, 2L), counts(List.of("K"), t));
    }


    @Test
    void aLongAndADoubleOfOneValueAreOneKeyAcrossTables()
    {
        IDataTable eval = RealTables.of("EV").dbl("K", 5.0, 6.0, 7.0).build();
        IDataTable dm = RealTables.of("DM").lng("K", 5L, 5L, 6L).build();
        // LONG and DOUBLE are both NUMERIC: no key-type error, and 5L meets 5.0 (D99b)
        assertEquals(List.of(2L, 1L, 0L),
                counts(List.of("K"), "DM", eval, d -> "DM".equals(d) ? dm : null));
    }


    @Test
    void theBlanksStayPairwiseDistinct()
    {
        IDataTable num = RealTables.of("N").dbl("K", MissingValue.MIS.asDouble(),
                MissingValue.MIS_A.asDouble(), MissingValue.MIS.asDouble(), 1.0).build();
        assertEquals(List.of(2L, 1L, 2L, 1L), counts(List.of("K"), num));
        IDataTable chr = RealTables.of("C").str("K", "", null, "", "A").build();
        assertEquals(List.of(2L, 1L, 2L, 1L), counts(List.of("K"), chr));
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
        // (the one block's key is GroupKey.of("", "") on both sides — since W6 the function
        // exposes no key set, so the observable is every row finding the whole-table group)
        assertEquals(List.of(3L, 3L, 3L), counts(List.of("STUDYID", "SPDEVID"), t));
        assertEquals(List.of(3L, 3L, 3L), counts(List.of("SPDEVID"), t));
    }


    @Test
    void aPartlyAbsentGroupStillSeparatesTheSurvivingColumn()
    {
        IDataTable t = RealTables.of("DS").str("USUBJID", "S1", "S1", "S2").build();
        assertEquals(List.of(2L, 2L, 1L), counts(List.of("USUBJID", "EPOCH"), t));
    }


    /**
     * Family T stays TEXT by ruling ({@code D4-R5}): a SUPP row's {@code IDVARVAL} text {@code "1"}
     * still finds the parent whose {@code AESEQ} is the NUMBER 1.
     */
    @Test
    void theSuppPivotMatchesAsText()
    {
        // The SUPP join's text-key identity, on its successor: runbook W2a retired the
        // supp_qnam_present operation and a qualifier is read through SuppPivot — IDVARVAL "1"
        // (text) must still find the parent whose AESEQ is the NUMBER 1 (J5 / D4-R5).
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "S1").dbl("AESEQ", 1.0, 2.0)
                .build();
        IDataTable supp = RealTables.of("SUPPAE").str("USUBJID", "S1").str("RDOMAIN", "AE")
                .str("IDVAR", "AESEQ").str("IDVARVAL", "1").str("QNAM", "AETRTEM").str("QVAL", "Y")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae)
                .datasetResolver(d -> "SUPPAE".equals(d) ? supp : null).build();
        IDataTableColumn column = SuppPivot.qualifierColumn(ctx, "AETRTEM");
        assertNotNull(column, "SUPPAE delivers AETRTEM");
        assertEquals("Y", column.getDataValue(0).getValueAsString());
        assertEquals("", column.getDataValue(1).getValueAsString());
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
        assertEquals(List.of(2L, 1L, 2L), counts(List.of("USUBJID", "VISITNUM"), union));
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
                    () -> counts(List.of("USUBJID"), t));
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
            // Since W5 max_date is a registry function whose plan claims EVERY block with a
            // value, so the collision is between two value-writing blocks; the tripwire refuses
            // it all the same, and the exception leaves the runner exactly as the retired
            // evaluator's did.
            net.cumba.corej.core.model.Rule fn = functionRule("max_date(VSDTC, group=[USUBJID])");
            IllegalStateException fnEx = assertThrows(IllegalStateException.class,
                    () -> RuleRunnerCalls.execute(fn, t));
            assertTrue(fnEx.getMessage().contains("the block of row 0 and the block of row 2"),
                    fnEx.getMessage());
            // distinct, a registry function since runbook W7, claims every block with its list
            // through the same BlockResults tripwire.
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> lists("distinct(VSDTC, group=[USUBJID])", t));
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
        // (max_date's half of this claim — a group without a value reads the declared MISSING —
        // is the registry function's own missing answer since W5: GroupedAggregateFunctionsTest.)
        IDataTable n = RealTables.of("VS").str("USUBJID", "S1", "S2").str("VSSTRESC", "X", "")
                .build();
        assertEquals(List.of(List.of("X"), List.of()),
                lists("distinct(VSSTRESC, group=[USUBJID])", n));
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
