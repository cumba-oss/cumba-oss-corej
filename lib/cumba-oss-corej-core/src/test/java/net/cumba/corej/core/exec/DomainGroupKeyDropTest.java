package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.KeyDropWarning;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code REG-Q4} QK14 (b) and QK16 (b) — a {@code group=} member of a grouped function under
 * {@code domain=} keys TWO datasets, the evaluated rows and the {@code domain=} dataset, like a
 * join key ({@code REG-Q3}), bare and qualified alike ({@link GroupedAggregate.SidedKey}):
 *
 * <ul>
 * <li>present on one side only ⇒ it leaves the key on both sides, with one WARNING naming the rule
 * (owner 2026-10-09: <i>"QK14: agree as recommended."</i> — until then {@code ""} on the absent
 * side, so nothing matched);</li>
 * <li>absent from both sides ⇒ {@code EC-44}'s silent "partitions nothing", unchanged (QK5
 * (a));</li>
 * <li>no member left on both sides after a one-side drop ⇒ rule ERROR (owner 2026-10-11: <i>"B the
 * rule errors."</i>, QK16 (b)) — never the whole-dataset aggregate.</li>
 * </ul>
 *
 * <p>
 * One test per caller family ({@code max} / {@code read_value} through the target grouping,
 * {@code record_count}, {@code distinct}, {@code sequence}), each over a bare member and a
 * qualified one, plus the two corpus shapes the phase-0 census named ({@code FDA-SD0070}'s
 * {@code record_count(domain=EX, group=[USUBJID])}, {@code CDISC-CG0147}'s
 * {@code max_date(…, domain=EX, group=[USUBJID])}). The primary {@code P} has two subjects; the
 * {@code domain=} dataset {@code D} gives S1 two rows (VAL 10 and 20, VISIT 1 and 2) and S2 one
 * (VAL 30, VISIT 1), so keyed on USUBJID alone S1 reads its two rows and S2 its one, while the
 * retired "matches nothing" gave every subject the no-group answer.
 * </p>
 */
class DomainGroupKeyDropTest
{

    private static final String RULE_ID = "T-DG";

    /** A rule over P with one binding {@code $v}, firing on every record, reporting {@code $v}. */
    private static Rule rule(String binding, String matchDatasets, String requirements)
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"" + RULE_ID + "\"},"
                + "\"Sensitivity\":\"Record\",\"Requirements\":{\"Variables\":" + requirements
                + "},\"Match_Datasets\":" + matchDatasets + ",\"Bindings\":[{\"name\":\"$v\","
                + "\"expression\":\"" + binding.replace("\"", "\\\"") + "\"}],"
                + "\"Check\":{\"expression\":\"not empty(USUBJID)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\",\"$v\"]}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }


    private static Rule bare(String binding)
    {
        return rule(binding, "[]", "{\"All\":[\"USUBJID\"]}");
    }


    /** The qualified spelling: P joined to J on USUBJID, the member read as {@code J.VISIT}. */
    private static Rule qualified(String binding)
    {
        return rule(binding, "[{\"Name\":\"J\",\"Keys\":[\"USUBJID\"]}]",
                "{\"All\":[\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"J.USUBJID\"]]}");
    }


    private static IDataTable p(boolean withVisit)
    {
        RealTables t = RealTables.of("P").str("USUBJID", "S1", "S2");
        return (withVisit ? t.str("VISIT", "9", "9") : t).build();
    }


    private static IDataTable j(boolean withVisit)
    {
        RealTables t = RealTables.of("J").str("USUBJID", "S1", "S2");
        return (withVisit ? t.str("VISIT", "9", "9") : t).build();
    }


    private static IDataTable d(boolean withVisit)
    {
        RealTables t = RealTables.of("D").str("USUBJID", "S1", "S1", "S2");
        return (withVisit ? t.str("VISIT", "1", "2", "1") : t).lng("VAL", 10L, 20L, 30L)
                .str("DTC", "2020-01-10", "2020-01-20", "2020-01-30").build();
    }

    private record Run(RuleExecutionResult result, List<String> warnings)
    {

        /** The reported {@code $v} per subject. */
        Map<String, String> values()
        {
            assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                    result.getStatusMessage());
            Map<String, String> out = new TreeMap<>();
            for (Violation v : result.getViolations())
            {
                out.put(v.getValues().get("USUBJID"), v.getValues().get("$v"));
            }
            return out;
        }


        String error()
        {
            assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), result.getStatusMessage());
            String msg = String.valueOf(result.getStatusMessage());
            assertEquals(msg, result.getViolations().get(0).getValues().get("__error__"));
            return msg;
        }
    }

    private static Run run(Rule rule, IDataTable primary, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = primary;
        System.arraycopy(others, 0, all, 1, others.length);
        List<RuleExecutionResult> res = new ArrayList<>();
        List<String> warnings = JoinKeyDropWarningTest.warnings(GroupedAggregate.class,
                () -> res.add(RuleRunnerCalls.execute(rule, primary, RealTables.inventoryOf(all))));
        return new Run(res.get(0), warnings);
    }


    private static String warning(String fn, String key, String carrier)
    {
        return "[" + RULE_ID + "] " + fn + " group=: " + KeyDropWarning.format("D", key, carrier);
    }


    /**
     * One family over both spellings: the member on the record side only, on the grouped side only,
     * on neither, and as the whole key on one side only.
     */
    private static void assertFamilyDropsAOneSideMember(String fn, String bareCall,
            String qualifiedCall, Map<String, String> keyedByUsubjid, String wholeKeyCall)
    {
        // --- bare VISIT ---------------------------------------------------------------------
        Run recordOnly = run(bare(bareCall), p(true), d(false));
        assertEquals(keyedByUsubjid, recordOnly.values(), fn + ": VISIT on P only leaves the key");
        assertEquals(List.of(warning(fn, "VISIT", "P")), recordOnly.warnings());
        Run groupedOnly = run(bare(bareCall), p(false), d(true));
        assertEquals(keyedByUsubjid, groupedOnly.values(), fn + ": VISIT on D only leaves the key");
        assertEquals(List.of(warning(fn, "VISIT", "D")), groupedOnly.warnings());
        Run neither = run(bare(bareCall), p(false), d(false));
        assertEquals(keyedByUsubjid, neither.values(), fn + ": absent from both, EC-44 as before");
        assertEquals(List.of(), neither.warnings(), fn + ": both absent is silent (QK5 (a))");
        // --- qualified J.VISIT ----------------------------------------------------------------
        Run sourceLacks = run(qualified(qualifiedCall), p(false), j(false), d(true));
        assertEquals(keyedByUsubjid, sourceLacks.values(), fn + ": J lacks VISIT");
        assertEquals(List.of(warning(fn, "J.VISIT/VISIT", "D")), sourceLacks.warnings());
        Run groupedLacks = run(qualified(qualifiedCall), p(false), j(true), d(false));
        assertEquals(keyedByUsubjid, groupedLacks.values(), fn + ": D lacks VISIT");
        assertEquals(List.of(warning(fn, "J.VISIT/VISIT", "J")), groupedLacks.warnings());
        // --- the whole key on one side only: QK16 (b) ------------------------------------------
        String msg = run(bare(wholeKeyCall), p(true), d(false)).error();
        assertTrue(msg.contains(fn + " group= over 'D' has no usable key"), msg);
        assertTrue(msg.contains("VISIT present on one side only"), msg);
    }


    @Test
    void maxDropsAOneSideMemberBareAndQualified()
    {
        assertFamilyDropsAOneSideMember("max", "max(VAL, domain=\"D\", group=[USUBJID, VISIT])",
                "max(VAL, domain=\"D\", group=[USUBJID, J.VISIT])", Map.of("S1", "20", "S2", "30"),
                "max(VAL, domain=\"D\", group=[VISIT])");
    }


    @Test
    void readValueDropsAOneSideMemberBareAndQualified()
    {
        assertFamilyDropsAOneSideMember("read_value",
                "read_value(VAL, domain=\"D\", group=[USUBJID, VISIT], mode=\"MIN\")",
                "read_value(VAL, domain=\"D\", group=[USUBJID, J.VISIT], mode=\"MIN\")",
                Map.of("S1", "10", "S2", "30"),
                "read_value(VAL, domain=\"D\", group=[VISIT], mode=\"MIN\")");
    }


    @Test
    void recordCountDropsAOneSideMemberBareAndQualified()
    {
        assertFamilyDropsAOneSideMember("record_count",
                "record_count(domain=\"D\", group=[USUBJID, VISIT])",
                "record_count(domain=\"D\", group=[USUBJID, J.VISIT])",
                Map.of("S1", "2", "S2", "1"), "record_count(domain=\"D\", group=[VISIT])");
    }


    @Test
    void distinctDropsAOneSideMemberBareAndQualified()
    {
        assertFamilyDropsAOneSideMember("distinct",
                "distinct(VAL, domain=\"D\", group=[USUBJID, VISIT])",
                "distinct(VAL, domain=\"D\", group=[USUBJID, J.VISIT])",
                Map.of("S1", "[10, 20]", "S2", "[30]"),
                "distinct(VAL, domain=\"D\", group=[VISIT])");
    }


    @Test
    void sequenceDropsAOneSideMemberBareAndQualified()
    {
        assertFamilyDropsAOneSideMember("sequence",
                "sequence(VAL, domain=\"D\", group=[USUBJID, VISIT], by=[desc(VAL)])",
                "sequence(VAL, domain=\"D\", group=[USUBJID, J.VISIT], by=[desc(VAL)])",
                Map.of("S1", "[20, 10]", "S2", "[30]"),
                "sequence(VAL, domain=\"D\", group=[VISIT], by=[desc(VAL)])");
    }


    /**
     * The WARNING is logged once per rule, dataset and call — the grouping is memoised per
     * execution — even when the call is read twice in the rule.
     */
    @Test
    void theWarningIsLoggedOncePerExecutionWhateverTheReads()
    {
        String call = "max(VAL, domain=\"D\", group=[USUBJID, VISIT])";
        Run twice = run(bare(call + " + " + call), p(true), d(false));
        assertEquals(Map.of("S1", "40", "S2", "60"), twice.values());
        assertEquals(List.of(warning("max", "VISIT", "P")), twice.warnings());
    }


    /**
     * {@code FDA-SD0070}'s shape ({@code record_count(domain="EX", group=[USUBJID])} over DM): an
     * EX without USUBJID leaves the call no key — the rule ERRORs (QK16 (b)); with USUBJID absent
     * from BOTH sides the whole-table count stands, as before ({@code EC-44}).
     */
    @Test
    void anSd0070LikeCountOverAnExWithoutUsubjidErrorsAndBothAbsentStaysWholeTable()
    {
        Rule count = bare("record_count(domain=\"EX\", group=[USUBJID])");
        IDataTable dm = RealTables.of("DM").str("USUBJID", "S1", "S2").build();
        IDataTable exWithout = RealTables.of("EX").str("EXTRT", "A", "B", "C").build();
        String msg = run(count, dm, exWithout).error();
        assertTrue(msg.contains("record_count group= over 'EX' has no usable key"), msg);
        IDataTable exWith = RealTables.of("EX").str("USUBJID", "S1", "S1", "S2").build();
        assertEquals(Map.of("S1", "2", "S2", "1"), run(count, dm, exWith).values(),
                "control: EX with USUBJID counts per subject");
        Rule bothAbsent = rule("record_count(domain=\"EX\", group=[VISIT])", "[]",
                "{\"All\":[\"USUBJID\"]}");
        Run whole = run(bothAbsent, dm, exWithout);
        assertEquals(Map.of("S1", "3", "S2", "3"), whole.values(),
                "VISIT absent from DM and EX: one group over the whole of EX, as before");
        assertEquals(List.of(), whole.warnings());
    }


    /**
     * {@code CDISC-CG0147}'s shape ({@code max_date(…, domain="EX", group=[USUBJID])} over DM), and
     * {@code min_date} the same way.
     */
    @Test
    void aCg0147LikeDateExtremeOverAnExWithoutUsubjidErrors()
    {
        IDataTable dm = RealTables.of("DM").str("USUBJID", "S1", "S2").build();
        IDataTable exWithout = RealTables.of("EX").str("EXENDTC", "2020-01-05", "2020-02-01")
                .build();
        for (String fn : List.of("max_date", "min_date"))
        {
            String msg = run(bare(fn + "(EXENDTC, domain=\"EX\", group=[USUBJID])"), dm, exWithout)
                    .error();
            assertTrue(msg.contains(fn + " group= over 'EX' has no usable key"), msg);
            assertNotNull(msg);
        }
        IDataTable exWith = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXENDTC", "2020-01-05", "2020-02-01").build();
        assertEquals(Map.of("S1", "2020-01-05", "S2", "2020-02-01"),
                run(bare("max_date(EXENDTC, domain=\"EX\", group=[USUBJID])"), dm, exWith).values(),
                "control: EX with USUBJID");
    }
}
