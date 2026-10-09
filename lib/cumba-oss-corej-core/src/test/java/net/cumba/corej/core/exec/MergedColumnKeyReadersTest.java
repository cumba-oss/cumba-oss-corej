package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeKind;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ⭐ {@code PLAN-merged-evaluation-table} W3c — the key and group readers over the ONE evaluation
 * table: a merged column is read by every key / group reader exactly as the record's own column
 * would be (D72: <i>"a merged column behaves in EVERY respect like a primary column"</i>), for both
 * kinds of merged column W3c serves:
 *
 * <ul>
 * <li><b>{@code SUPP}</b> — {@code N16} ({@code PLAN-supp-pivot-key-readers}, absorbed): the bare
 * qualifier {@code DSSCAT} delivered by SUPPDS. Until W3c every reader below dropped it as an
 * absent column — the corpus shape is {@code CDISC-CG0536} / {@code FDA-SD1367} /
 * {@code PMDA-SD1367}: {@code Grouping [USUBJID, DSSCAT, EPOCH]} and
 * {@code is_unique_set([EPOCH, USUBJID, DSSCAT,
 * DSCAT])} with DSSCAT held in SUPPDS, where two records differing only in DSSCAT read as
 * duplicates (a false finding).</li>
 * <li><b>{@code JOINED}</b> — {@code N5} lifted per MQ8: the qualified {@code J.DSSCAT} of a keyed
 * {@code Match_Datasets} entry, refused at load until W3c ("write it bare"). This is the
 * sensitivity instrument of the group-operator rows {@code QualifiedNameUniformityTest} lists as
 * blind on its fixture.</li>
 * </ul>
 *
 * <p>
 * The differential: each probe runs over DS + the merge's source and over DS' (DS carrying the same
 * DSSCAT values itself, {@code ""} where no source row qualifies the record — the type default, D34
 * #3); the findings (rows and reported values) must be equal (until W6 on the legacy arm too, which
 * W6 retired). Non-vacuity: every probe must differ from the run WITHOUT the source (the column
 * resolves nowhere — the probe can see it), and on the view arm the plant of the source's kind
 * ({@link MergedEvaluation#plantForTests}: every read of that part one merged row on) must move it
 * — the read goes through the view — unless the probe's answer is a whole-dataset set or count,
 * invariant under a row permutation by construction (listed per probe).
 * </p>
 */
class MergedColumnKeyReadersTest
{

    @AfterEach
    void reset()
    {
        MergedEvaluation.plantForTests(null);
    }

    /** Where the merged column comes from. */
    enum Source
    {

        /** {@code SUPPDS}, read bare. */
        SUPP("DSSCAT", MergeKind.SUPP),
        /** A keyed {@code Match_Datasets} entry {@code J}, read qualified. */
        JOINED("J.DSSCAT", MergeKind.KEYED);

        private final String name;

        private final MergeKind plant;

        Source(String aName, MergeKind aPlant)
        {
            name = aName;
            plant = aPlant;
        }
    }

    /**
     * DSSCAT per record: S1's three TREATMENT records split 1 / 2 over two DSSCATs, S2's carry none
     * on two records (the present blank) and one on the third.
     */
    private static final String[] DSSCAT =
    {
            "STUDY PARTICIPATION", "STUDY TREATMENT", "STUDY TREATMENT", "", "", "X"
    };

    /** One DSSCAT on every record (has_same_values answers only for one value). */
    private static final String[] CONSTANT =
    {
            "A", "A", "A", "A", "A", "A"
    };

    private static final String[] SUBJECTS =
    {
            "S1", "S1", "S1", "S2", "S2", "S2"
    };

    private static RealTables dsBase()
    {
        return RealTables.of("DS").str("STUDYID", "S", "S", "S", "S", "S", "S")
                .str("DOMAIN", "DS", "DS", "DS", "DS", "DS", "DS").str("USUBJID", SUBJECTS)
                .lng("DSSEQ", 1L, 2L, 3L, 1L, 2L, 3L)
                .str("DSCAT", "DISPOSITION EVENT", "DISPOSITION EVENT", "DISPOSITION EVENT",
                        "DISPOSITION EVENT", "DISPOSITION EVENT", "PROTOCOL MILESTONE")
                .str("EPOCH", "TREATMENT", "TREATMENT", "TREATMENT", "SCREENING", "SCREENING",
                        "SCREENING")
                .dbl("DSN", 1.0, 3.0, 2.0, 4.0, 5.0, 6.0)
                .str("DSDECOD", "", "", "COMPLETED", "", "", "").str("DSSTDTC", "2020-01-01",
                        "2020-02-01", "2020-03-01", "2020-01-05", "2020-01-06", "2020-01-07");
    }


    /** DS without DSSCAT — the merge delivers it. */
    private static IDataTable ds()
    {
        return dsBase().build();
    }


    /** DS' — DS carrying the probe's DSSCAT itself, the reference. */
    private static IDataTable dsCarrying(Probe p)
    {
        return dsBase().str("DSSCAT", p.dsscat()).build();
    }


    /**
     * The merge's source: SUPPDS (one record-level row per non-blank value) or J (every record).
     */
    private static IDataTable source(Source source, Probe p)
    {
        String[] dsscat = p.dsscat();
        if (source == Source.JOINED)
        {
            return RealTables.of("J").str("USUBJID", SUBJECTS).lng("DSSEQ", 1L, 2L, 3L, 1L, 2L, 3L)
                    .str("DSSCAT", dsscat).build();
        }
        List<String> usubjid = new ArrayList<>();
        List<String> idvarval = new ArrayList<>();
        List<String> qval = new ArrayList<>();
        String[] seq =
        {
                "1", "2", "3", "1", "2", "3"
        };
        for (int i = 0; i < dsscat.length; i++)
        {
            if (!dsscat[i].isEmpty())
            {
                usubjid.add(SUBJECTS[i]);
                idvarval.add(seq[i]);
                qval.add(dsscat[i]);
            }
        }
        int n = qval.size();
        return RealTables.of("SUPPDS").str("STUDYID", repeat("S", n))
                .str("RDOMAIN", repeat("DS", n)).str("USUBJID", usubjid.toArray(String[]::new))
                .str("IDVAR", repeat("DSSEQ", n)).str("IDVARVAL", idvarval.toArray(String[]::new))
                .str("QNAM", repeat("DSSCAT", n)).str("QLABEL", repeat("Subcategory", n))
                .str("QVAL", qval.toArray(String[]::new)).build();
    }


    /**
     * The merge's source WITHOUT the column: no SUPPDS at all; J with its keys only (an absent J
     * would SKIP the rule by its declared keys — the sensitivity must reach the readers).
     */
    private static IDataTable[] sourceWithoutTheColumn(Source source)
    {
        return source == Source.JOINED ? new IDataTable[]
        {
                RealTables.of("J").str("USUBJID", SUBJECTS).lng("DSSEQ", 1L, 2L, 3L, 1L, 2L, 3L)
                        .build()
        } : new IDataTable[0];
    }


    private static String[] repeat(String value, int n)
    {
        String[] out = new String[n];
        java.util.Arrays.fill(out, value);
        return out;
    }

    /**
     * One probe: the sensitivity, the Grouping ({@code null} for none) and the Check, the merged
     * column written {@code @Q}; the DSSCAT values it runs on; and whether its answer is invariant
     * under a row PERMUTATION of DSSCAT — a whole-dataset set or count, which the row-shift plant
     * cannot move by construction (stated per probe, not measured).
     */
    @SuppressWarnings("ArrayRecordComponent")
    record Probe(String id, String sensitivity, @Nullable String grouping, String check,
            String[] dsscat, boolean permutationInvariant)
    {

        Probe(String id, String sensitivity, @Nullable String grouping, String check)
        {
            this(id, sensitivity, grouping, check, DSSCAT, false);
        }


        @Override
        public String toString()
        {
            return id;
        }
    }

    /**
     * The key / group readers the plan's N16 row and the N5 rows of MQ8 name: the rule-level
     * {@code Grouping}, {@code is_unique_set}, the group operators, the extremes' {@code name=} /
     * {@code group=} / {@code filter=}, {@code record_count}'s {@code group=} / {@code filter=},
     * {@code distinct} — each reading the merged column in the position named by its id.
     */
    static List<Probe> probes()
    {
        return List.of(
                // the corpus shape (CDISC-CG0536 / FDA-SD1367 / PMDA-SD1367), verbatim
                new Probe("Grouping+is_unique_set (CG0536)", "Group",
                        "\"USUBJID\",\"@Q\",\"EPOCH\"",
                        "DSCAT == \"DISPOSITION EVENT\" and not is_unique_set([EPOCH, USUBJID,"
                                + " @Q, DSCAT])"),
                new Probe("Grouping key", "Group", "\"USUBJID\",\"@Q\"", "DSSEQ > 0"),
                new Probe("is_unique_set/members", "Record", null,
                        "not is_unique_set([USUBJID, @Q])"),
                new Probe("is_not_unique_set/members", "Record", null,
                        "is_not_unique_set([USUBJID, @Q])"),
                new Probe("has_multiple_values_for/name", "Record", null,
                        "has_multiple_values_for(@Q, USUBJID)"),
                new Probe("has_multiple_values_for/key", "Record", null,
                        "has_multiple_values_for(DSSTDTC, @Q)"),
                new Probe("has_multiple_values_for/within", "Record", null,
                        "has_multiple_values_for(DSSEQ, EPOCH, within=@Q)"),
                new Probe("present_on_multiple_rows_within/name", "Record", null,
                        "present_on_multiple_rows_within(@Q, within=USUBJID)"),
                new Probe("present_on_multiple_rows_within/within", "Record", null,
                        "present_on_multiple_rows_within(EPOCH, within=@Q)"),
                new Probe("is_inconsistent_across_dataset/name", "Record", null,
                        "is_inconsistent_across_dataset(@Q, keys=[USUBJID])"),
                new Probe("is_inconsistent_across_dataset/keys", "Record", null,
                        "is_inconsistent_across_dataset(DSSEQ, keys=[@Q])"),
                new Probe("is_not_unique_relationship/name", "Record", null,
                        "is_not_unique_relationship(@Q, USUBJID)"),
                new Probe("is_not_unique_relationship/value", "Record", null,
                        "is_not_unique_relationship(USUBJID, @Q)"),
                new Probe("is_not_unique_value/name", "Record", null, "is_not_unique_value(@Q)"),
                new Probe("has_same_values/name", "Record", null, "has_same_values(@Q)", CONSTANT,
                        true),
                new Probe("empty_within_except_last_row/name", "Record", null,
                        "empty_within_except_last_row(@Q, USUBJID, by=DSSEQ)"),
                new Probe("empty_within_except_last_row/group", "Record", null,
                        "empty_within_except_last_row(DSDECOD, @Q, by=DSSEQ)"),
                new Probe("has_next_corresponding_record/name", "Record", null,
                        "not has_next_corresponding_record(@Q, EPOCH, within=USUBJID,"
                                + " by=DSSEQ)"),
                new Probe("has_next_corresponding_record/within", "Record", null,
                        "not has_next_corresponding_record(DSDECOD, DSDECOD, within=@Q,"
                                + " by=DSSEQ)"),
                new Probe("is_sorted_by/within", "Record", null,
                        "not is_sorted_by(DSSEQ, by=[asc(DSN)], within=@Q)"),
                new Probe("is_last_in_group/group", "Record", null,
                        "is_last_in_group(by=DSSEQ, group=[@Q])"),
                new Probe("has_mixed_emptiness_within_group/name", "Record", null,
                        "has_mixed_emptiness_within_group(@Q, group=[USUBJID])"),
                new Probe("has_mixed_emptiness_within_group/group", "Record", null,
                        "has_mixed_emptiness_within_group(DSDECOD, group=[@Q])"),
                new Probe("contains_all/source", "Record", null,
                        "contains_all(@Q, split_by(EPOCH, \",\"))", DSSCAT, true),
                new Probe("max/group", "Record", null, "max(DSN, group=[@Q]) == DSN"),
                new Probe("max/name", "Record", null,
                        "max(@Q, group=[USUBJID]) == \"STUDY TREATMENT\""),
                new Probe("max/filter", "Record", null,
                        "max(DSN, group=[USUBJID], filter=(@Q == \"STUDY PARTICIPATION\")) == DSN"),
                new Probe("max_date/group", "Record", null,
                        "max_date(DSSTDTC, group=[@Q]) == DSSTDTC"),
                new Probe("min_date/group", "Record", null,
                        "min_date(DSSTDTC, group=[@Q]) == DSSTDTC"),
                new Probe("record_count/group", "Record", null, "record_count(group=[@Q]) > 1"),
                new Probe("record_count/filter", "Record", null,
                        "record_count(filter=(@Q == \"STUDY TREATMENT\")) == 2", DSSCAT, true),
                new Probe("distinct/group", "Record", null,
                        "count(distinct(EPOCH, group=[@Q])) == 1 and DSSEQ == 1"),
                new Probe("distinct/name", "Record", null,
                        "count(distinct(@Q, group=[USUBJID])) > 1"));
    }


    static Stream<Arguments> cases()
    {
        List<Arguments> out = new ArrayList<>();
        for (Source source : Source.values())
        {
            for (Probe p : probes())
            {
                out.add(Arguments.of(source, p));
            }
        }
        return out.stream();
    }


    private static Rule rule(Source source, Probe p, String outputs)
    {
        String body = "\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"" + p.sensitivity() + "\","
                + (source == Source.JOINED
                        ? "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"USUBJID\",\"DSSEQ\"],"
                                + "\"Join_Type\":\"inner\"}],"
                        : "")
                + (p.grouping() == null ? ""
                        : "\"Grouping\":{\"Variables\":[" + p.grouping().replace("@Q", source.name)
                                + "]},")
                + "\"Check\":{\"expression\":\""
                + p.check().replace("@Q", source.name).replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":["
                + outputs.replace("@Q", source.name) + "]}";
        String json = KeyedJoinFixtures.declared("{\"rules\":{\"X-1\":{" + body + "}}}");
        try
        {
            Rule r = RulePackageLoader.loadFromString(json).getRules().get("X-1");
            assertNull(r.getLoadError(), source + " " + p + ": " + r.getLoadError());
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Both outputs: the record's sequence and the merged column as read (the differential). */
    private static final String BOTH = "\"DSSEQ\",\"@Q\"";

    /**
     * The sequence only: a sensitivity check must not pass merely because the printed merged column
     * differs (Output_Variables reads the view since W3b).
     */
    private static final String SEQ_ONLY = "\"DSSEQ\"";

    /**
     * The findings as (row → reported values), the merged column's printed name normalised to the
     * reference's {@code DSSCAT}; the status line when not EXECUTED.
     */
    private static String outcome(Source source, Probe p, String outputs, IDataTable primary,
            IDataTable... others)
    {
        List<IDataTable> all = new ArrayList<>();
        all.add(primary);
        all.addAll(List.of(others));
        RuleExecutionResult r = RuleRunnerCalls.execute(rule(source, p, outputs), primary,
                RealTables.inventoryOf(all.toArray(IDataTable[]::new)));
        if (r.getStatus() != RuleExecutionStatus.EXECUTED)
        {
            return r.getStatus() + ": " + r.getStatusMessage();
        }
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        for (Violation v : r.getViolations())
        {
            Map<String, String> values = new LinkedHashMap<>();
            v.getValues()
                    .forEach((k, val) -> values.put(k.equals(source.name) ? "DSSCAT" : k, val));
            out.put(v.getRowNumber(), values);
        }
        return out.toString();
    }


    /** The reference: DS' carrying DSSCAT itself, the probe written bare, no merge at all. */
    private static String reference(Probe p, String outputs)
    {
        return outcome(Source.SUPP, p, outputs, dsCarrying(p));
    }


    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void aMergedColumnReadsLikeTheColumnTheRecordCarries(Source source, Probe p)
    {
        String reference = reference(p, BOTH);
        assertFalse(reference.startsWith("ERROR") || reference.startsWith("SKIPPED"),
                p + ": the reference must execute — " + reference);
        assertNotEquals(reference(p, SEQ_ONLY),
                outcome(source, p, SEQ_ONLY, ds(), sourceWithoutTheColumn(source)),
                source + " " + p + ": a probe that cannot see the merged column measures nothing");
        assertEquals(reference, outcome(source, p, BOTH, ds(), source(source, p)),
                source + " " + p + " — merged view");
    }


    /**
     * The planted control: every read of the source's part one merged row on. A reader that reads
     * the merged column anywhere but through the view stays equal to the reference under it; every
     * probe must move, bar the listed permutation-invariant ones, which must stay equal.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void thePlantMovesEveryProbe(Source source, Probe p)
    {
        String reference = reference(p, SEQ_ONLY);
        assertEquals(reference, outcome(source, p, SEQ_ONLY, ds(), source(source, p)),
                source + " " + p + " unplanted");
        MergedEvaluation.plantForTests(source.plant);
        String planted = outcome(source, p, SEQ_ONLY, ds(), source(source, p));
        if (p.permutationInvariant())
        {
            assertEquals(reference, planted, source + " " + p + ": a whole-dataset set or count is"
                    + " invariant under the row shift — listed as such, so it must stay equal");
            return;
        }
        assertNotEquals(reference, planted, source + " " + p
                + ": the planted row shift must move the reader (it reads the view)");
    }


    /**
     * The CG0536 shape in words: of S1's three TREATMENT records, the one in STUDY PARTICIPATION is
     * its own group (no finding) and the two sharing STUDY TREATMENT are a duplicate — one finding,
     * anchored at the first of them. Before W3c the SUPP-held DSSCAT was dropped from the key and
     * all three were one group.
     */
    @Test
    void theCorpusShapeReportsOnlyTheTrueDuplicate()
    {
        Probe cg0536 = probes().get(0);
        String merged = outcome(Source.SUPP, cg0536, SEQ_ONLY, ds(), source(Source.SUPP, cg0536));
        assertEquals(1, merged.split("\\}, ").length, merged);
        assertEquals(true, merged.startsWith("{2={DSSEQ=[2, 3],"), merged);
    }
}
