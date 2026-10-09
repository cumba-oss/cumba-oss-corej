package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.loadError;
import static net.cumba.corej.core.exec.SequenceFunctionTest.rows;
import static net.cumba.corej.core.exec.SequenceFunctionTest.se;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RuleLanguage;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b SK (owner O16 / O17, 2026-10-08): the ONE sort-key reader ({@link SortKeys}) behind
 * {@code is_sorted_by(by=)} and {@code sequence(by=)} — (#3) a bare column in a sort-key list means
 * {@code asc} (the SQL default), so {@code by=$keys} with a name list splices, each key ascending;
 * (#2) {@code asc($c)} / {@code desc($c)} with a dataset-level name, the descriptor's column a
 * splice member judged at load (a per-row one refused); and O17's pure rename: {@code by=} is the
 * sort-key parameter everywhere — {@code is_last_in_group}, {@code has_next_corresponding_record}
 * and {@code empty_within_except_last_row} take {@code by=} (their {@code ordering=} renamed in
 * place; retired with the hint "write by=" since W4 OR2), exactly one ascending key as before, more
 * keys, {@code desc} or {@code nulls=} a load error <i>"not supported for &lt;f&gt;"</i>. Every
 * written form of a key answers alike; since W2 SB1 {@code is_sorted_by} honours the direction and
 * {@code nulls=} ({@link IsSortedByDirectionTest}).
 */
class SortKeysTest
{

    private static final String[] KEYS =
    {
            "$keys", "[SESTDTC]", "$c", "\"SESTDTC\"", "$two", "[USUBJID, SESTDTC]"
    };

    private static List<Long> fired(IDataTable table, String check)
    {
        return SequenceFunctionTest.fired(table, check, KEYS);
    }


    @Test
    void aBareColumnMeansAscending()
    {
        List<List<?>> asc = rows("sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)])", se());
        assertEquals(asc, rows("sequence(ETCD, group=[USUBJID], by=[SESTDTC])", se()),
                "#3: a bare column is asc(COL)");
        assertEquals(asc, rows("sequence(ETCD, group=[USUBJID], by=SESTDTC)", se()),
                "the one bare operand is the one-member list");
        assertEquals(List.of("FU", "TRT", "SCRN"),
                rows("sequence(ETCD, group=[USUBJID], by=[USUBJID, desc(SESTDTC)])", se()).get(0),
                "a mixed list");
    }


    @Test
    void aNameListBindingSplicesEachKeyAscendingAndADescriptorTakesABoundName()
    {
        List<Long> written = fired(se(), "not is_increasing($s)".replace("$s",
                "sequence(SESEQ, group=[USUBJID], by=[asc(SESTDTC)])"));
        assertEquals(List.of(4L, 5L, 6L), written, "premise");
        assertEquals(written,
                fired(se(), "not is_increasing(sequence(SESEQ, group=[USUBJID], by=$keys))"),
                "#3: by=$keys splices, ascending");
        assertEquals(written,
                fired(se(), "not is_increasing(sequence(SESEQ, group=[USUBJID], by=[$keys]))"));
        assertEquals(written,
                fired(se(), "not is_increasing(sequence(SESEQ, group=[USUBJID], by=[asc($c)]))"),
                "#2: asc($c) with a dataset-level name");
        assertEquals(List.of(7L),
                fired(se(), "is_increasing(sequence(SESEQ, group=[USUBJID], by=[desc($c)]))"),
                "desc($c) reverses: S1 by date desc is 3, 2, 1 and S2 is 2, 3, 1; S3's one is");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                fired(se(), "not is_increasing(sequence(SESEQ, group=[USUBJID], by=[desc($c)]))"));
    }


    @Test
    void aDescriptorWithABoundNameIsSplicedOncePerRun()
    {
        // the bound name is one key for the whole run, never a cell read per row: the sort is by
        // SESTDTC, not by the row's own text "SESTDTC" (which would leave file order)
        assertEquals(List.of("SCRN", "TRT", "FU"),
                rows("sequence(ETCD, group=[USUBJID], by=[asc(upper(\"sestdtc\"))])", se()).get(3),
                "a computed dataset-level name in the descriptor");
        assertEquals(List.of("SCRN", "TRT", "FU"),
                rows("sequence(ETCD, group=[USUBJID], by=[asc(minus([\"SESTDTC\", \"X\"],"
                        + " subtract=[\"X\"]))])", se()).get(3),
                "a one-name list in a descriptor is that name");
    }


    @Test
    void aPerRowDescriptorMemberIsRefusedAtLoadBoundAndInline()
    {
        String bound = loadError("count(sequence(ETCD, group=[USUBJID], by=[asc($p)])) > 0", "$p",
                "upper(ETCD)");
        String inline = loadError(
                "count(sequence(ETCD, group=[USUBJID], by=[asc(upper(ETCD))])) > 0");
        assertTrue(bound.contains("cannot splice the per-row member"), bound);
        assertEquals(bound.replace("$p", "upper(ETCD)"), inline);
        String sorted = loadError("not is_sorted_by(SESEQ, by=[asc($p)], within=USUBJID)", "$p",
                "upper(ETCD)");
        assertTrue(sorted.contains("cannot splice the per-row member"), sorted);
        assertTrue(loadError("not is_sorted_by(SESEQ, by=[asc($two)], within=USUBJID)", KEYS)
                .equals("null"), "a two-name list in a descriptor loads (spliced at run time)");
    }


    @Test
    void aWildcardKeyIsTheDotlessDomainPrefixOnly()
    {
        // R5 A-L1: the pre-SK readers took only the `--X` domain wildcard without a dot; a dotted
        // wildcard (`DM.--SEQ`, `SUPP--.QVAL`) loaded under SK and silently ordered nothing
        for (String key : List.of("DM.--SEQ", "SUPP--.QVAL"))
        {
            String bare = loadError("count(sequence(ETCD, group=[USUBJID], by=" + key + ")) > 0");
            assertTrue(bare.contains("must be a column"), key + ": " + bare);
            String asc = loadError(
                    "count(sequence(ETCD, group=[USUBJID], by=[asc(" + key + ")])) > 0");
            assertTrue(asc.contains("must be a column"), key + ": " + asc);
            String single = loadError("is_last_in_group(by=" + key + ", group=[USUBJID])");
            assertTrue(single.contains("must be a column"), key + ": " + single);
        }
        assertEquals("null",
                loadError("count(sequence(ETCD, group=[USUBJID], by=[asc(--SEQ)])) > 0"),
                "the dotless domain wildcard keeps loading");
    }


    @Test
    void aSortKeyDescriptorOutsideASortKeySlotIsRefusedAtLoad()
    {
        // R5 A-L4: asc()/desc() order a by= list only; in any other splice slot the
        // seams unwrapped it (stage A) or kept it as a computed member (the loader), so it loaded
        // and ERRORed at run time
        String within = loadError("not is_sorted_by(SESEQ, by=[SESTDTC], within=asc($c))", KEYS);
        assertTrue(within.contains("sort-key descriptor"), within);
        String group = loadError("count(distinct(ETCD, group=[asc(USUBJID)])) > 0");
        assertTrue(group.contains("sort-key descriptor"), group);
    }


    @Test
    void aSortKeyDescriptorOutsideASortKeySlotIsTheLoadersRefusalWhereStageADoesNotWalk()
    {
        // R5 A-L4, the loader's twin: a Check-less rule's binding is never walked by stage A
        Rule checkless = SequenceFunctionTest.loadCheckless("$c", "\"SESTDTC\"", "$v",
                "distinct(ETCD, group=[asc($c)])");
        assertTrue(String.valueOf(checkless.getLoadError()).contains("sort-key descriptor"),
                String.valueOf(checkless.getLoadError()));
    }


    @Test
    void isSortedByReadsEveryWrittenFormOfAKeyAlike()
    {
        // a bare column, a bound name and a spliced name list read the same key; since W2 SB1 the
        // direction is honoured (D20 retired), so a desc key reads its own answer — alike in
        // every form
        List<Long> written = fired(se(),
                "not is_sorted_by(SESEQ, by=[asc(SESTDTC)], within=USUBJID)");
        assertEquals(List.of(4L, 5L, 6L), written, "premise: S2's SESEQ is not in date order");
        for (String by : List.of("[SESTDTC]", "SESTDTC", "$keys", "[$keys]", "[asc($c)]",
                "[asc(SESTDTC, nulls=\"first\")]"))
        {
            assertEquals(written,
                    fired(se(), "not is_sorted_by(SESEQ, by=" + by + ", within=USUBJID)"), by);
        }
        for (String by : List.of("[desc(SESTDTC)]", "[desc($c)]"))
        {
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                    fired(se(), "not is_sorted_by(SESEQ, by=" + by + ", within=USUBJID)"),
                    by + ": S1 and S2 both run against descending dates");
        }
    }


    @Test
    void byIsTheSortKeyOfTheThreeSingleKeyReadersAsAPureRename()
    {
        IDataTable ae = WithinOrderingSpliceTest.ae();
        for (String[] pair : List.of(new String[]
        {
                "is_last_in_group(by=AESEQ, group=[USUBJID])", "is_last_in_group"
        }, new String[]
        {
                "empty_within_except_last_row(AEENDTC, USUBJID, by=AESEQ)",
                "empty_within_except_last_row"
        }, new String[]
        {
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=AESEQ)",
                "has_next_corresponding_record"
        }))
        {
            List<Long> written = SequenceFunctionTest.fired(ae, pair[0]);
            assertTrue(!written.isEmpty(), pair[0]);
            assertEquals(written,
                    SequenceFunctionTest.fired(ae, pair[0].replace("by=AESEQ", "by=[asc(AESEQ)]")));
            assertEquals(written,
                    SequenceFunctionTest.fired(ae, pair[0].replace("by=AESEQ", "by=[AESEQ]")));
            assertEquals(written, SequenceFunctionTest.fired(ae,
                    pair[0].replace("by=AESEQ", "by=$o"), "$o", "[AESEQ]"));
            String f = pair[1];
            String desc = loadError(pair[0].replace("by=AESEQ", "by=[desc(AESEQ)]"));
            assertTrue(desc.contains("not supported for " + f), f + " desc: " + desc);
            String two = loadError(pair[0].replace("by=AESEQ", "by=[AESEQ, AESTDTC]"));
            assertTrue(two.contains("not supported for " + f), f + " two: " + two);
            // W4 OR2: ordering= beside by= is the retired keyword's hint, no longer "not both"
            String both = loadError(pair[0].replace("by=AESEQ", "by=AESEQ, ordering=AESEQ"));
            assertTrue(both.contains("ordering= is retired"), both);
            String neither = loadError(pair[0].replace("by=AESEQ, ", "").replace(", by=AESEQ", ""));
            assertTrue(neither.contains("by="), f + ": " + neither);
        }
    }


    /**
     * Runbook W4 OR2 (owner O17, coordinator W4-D2): {@code ordering=} is retired on the three
     * single-key readers — a load error with the hint "write by=" on every path (an inline Check, a
     * binding, a Check-less rule), never ARITY's bare "has no parameter". The parameter is renamed
     * in place, so a positional key keeps its meaning.
     */
    @Test
    void orderingIsRetiredWithTheHintWriteBy()
    {
        for (String[] pair : List.of(new String[]
        {
                "is_last_in_group(ordering=AESEQ, group=[USUBJID])", "is_last_in_group"
        }, new String[]
        {
                "empty_within_except_last_row(AEENDTC, USUBJID, ordering=AESEQ)",
                "empty_within_except_last_row"
        }, new String[]
        {
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, ordering=AESEQ)",
                "has_next_corresponding_record"
        }))
        {
            String inline = loadError(pair[0]);
            assertTrue(inline.contains(pair[1] + ": ordering= is retired"), inline);
            assertTrue(inline.contains("write by=AESEQ"), inline);
            String bound = loadError("$b", "$b", pair[0].replace("not ", ""));
            assertTrue(bound.contains("ordering= is retired"), bound);
            Rule checkless = SequenceFunctionTest.loadCheckless("$b", pair[0].replace("not ", ""));
            assertTrue(String.valueOf(checkless.getLoadError()).contains("ordering= is retired"),
                    String.valueOf(checkless.getLoadError()));
        }
    }


    /**
     * OR2 renames in place: the positional key of is_last_in_group reads as by= (its compiler arm
     * reads the bound slot). The other two readers take their sort key by keyword only — their arms
     * read by= from the keywords — so a positional key there is a load error naming the keyword (W4
     * fold, R5 P1-M1: making it work would be a new feature, O17 "only as a rename"); before, it
     * passed the loader and the compiler's refusal only degraded the rule.
     */
    @Test
    void aPositionalKeyKeepsItsMeaningOnlyWhereTheReaderBindsIt()
    {
        IDataTable ae = WithinOrderingSpliceTest.ae();
        List<Long> by = SequenceFunctionTest.fired(ae,
                "is_last_in_group(by=AESEQ, group=[USUBJID])");
        assertTrue(!by.isEmpty(), "premise");
        assertEquals(by, SequenceFunctionTest.fired(ae, "is_last_in_group(AESEQ, [USUBJID])"));
        for (String[] pair : List.of(new String[]
        {
                "empty_within_except_last_row(AEENDTC, USUBJID, AESEQ)",
                "empty_within_except_last_row"
        }, new String[]
        {
                "not has_next_corresponding_record(AEENDTC, AESTDTC, USUBJID, AESEQ)",
                "has_next_corresponding_record"
        }))
        {
            String hint = pair[1] + " takes its sort key by keyword — write by=AESEQ";
            String inline = loadError(pair[0]);
            assertTrue(inline.contains(hint), inline);
            String bound = loadError("$b", "$b", pair[0].replace("not ", ""));
            assertTrue(bound.contains(hint), bound);
            Rule checkless = SequenceFunctionTest.loadCheckless("$b", pair[0].replace("not ", ""));
            assertTrue(String.valueOf(checkless.getLoadError()).contains(hint),
                    String.valueOf(checkless.getLoadError()));
        }
        assertEquals("null", loadError("empty_within_except_last_row(AEENDTC, USUBJID, by=AESEQ)"));
    }


    /**
     * Runbook W4 SB3 ({@code PLAN-is-sorted-by-direction} N2, coordinator W4-D12): a sort-key
     * descriptor's {@code nulls=} takes the string literal {@code "first"} or {@code "last"} only —
     * a case variant, another string or a non-literal is a load error (before SB3 it loaded and
     * silently kept the default) — and a descriptor keyword other than {@code nulls=} is refused.
     * Read through {@code sequence} here, whose seam is its own reader.
     */
    @Test
    void anUnknownNullsValueOrDescriptorKeywordIsALoadError()
    {
        String last = loadError(
                "count(sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC, nulls=\"Last\")])) > 0");
        assertTrue(last.contains("sequence sort key asc(SESTDTC, nulls=\"Last\"): nulls= takes"
                + " \"first\" or \"last\" (lower case), not \"Last\""), last);
        for (String value : List.of("\"bogus\"", "true", "$n"))
        {
            String error = loadError(
                    "count(sequence(ETCD, group=[USUBJID], by=[desc(SESTDTC, nulls=" + value
                            + ")])) > 0",
                    "$n", "\"last\"");
            assertTrue(error.contains("nulls= takes \"first\" or \"last\""), value + ": " + error);
        }
        String keyword = loadError(
                "count(sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC, null=\"last\")])) > 0");
        assertTrue(keyword.contains("a sort-key descriptor takes only nulls=, not null="), keyword);
        Rule checkless = SequenceFunctionTest.loadCheckless("$s",
                "sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC, nulls=\"bogus\")])");
        assertTrue(String.valueOf(checkless.getLoadError()).contains("nulls= takes"),
                String.valueOf(checkless.getLoadError()));
        assertEquals("null", loadError(
                "not is_sorted_by(SESEQ, by=[asc(SESTDTC, nulls=\"last\")], within=USUBJID)"));
    }


    /**
     * SB3: {@code is_sorted_by} meets the one sort-key reader in the loader's seam pass — its own
     * tailored reader — so an inline Check's refusal is a load error, not the compiler's degrade; a
     * dotted wildcard key (R5 A-L1) is refused there as for {@code sequence}.
     */
    @Test
    void isSortedByMeetsTheSortKeyReaderAtLoad()
    {
        String wildcard = loadError("not is_sorted_by(SESEQ, by=[asc(DM.--SEQ)], within=USUBJID)");
        assertTrue(wildcard.contains("must be a column"), wildcard);
        String nulls = loadError(
                "not is_sorted_by(SESEQ, by=[asc(SESTDTC, nulls=\"Last\")], within=USUBJID)");
        assertTrue(nulls.contains("is_sorted_by sort key asc(SESTDTC, nulls=\"Last\"): nulls= takes"
                + " \"first\" or \"last\" (lower case), not \"Last\""), nulls);
        Rule checkless = SequenceFunctionTest.loadCheckless("$b",
                "is_sorted_by(SESEQ, by=[asc(SESTDTC, nulls=\"bogus\")], within=USUBJID)");
        assertTrue(String.valueOf(checkless.getLoadError()).contains("nulls= takes"),
                String.valueOf(checkless.getLoadError()));
    }


    /**
     * SB3 / O17: {@code nulls=} on a single-key reader's key is "not supported" (no new features).
     */
    @Test
    void nullsIsNotSupportedOnASingleKeyReader()
    {
        for (String call : List.of(
                "is_last_in_group(by=[asc(AESEQ, nulls=\"last\")], group=[USUBJID])",
                "empty_within_except_last_row(AEENDTC, USUBJID, by=[asc(AESEQ, nulls=\"first\")])"))
        {
            String single = loadError(call);
            assertTrue(single.contains("is not supported for"), call + ": " + single);
        }
    }


    @Test
    void theSurfaceListsByOnEveryReader()
    {
        String surface = RuleLanguage.goldenText();
        List<String> lines = new ArrayList<>();
        for (String line : surface.lines().toList())
        {
            if (line.startsWith("is_last_in_group(") || line.startsWith("sequence(")
                    || line.startsWith("empty_within_except_last_row(")
                    || line.startsWith("has_next_corresponding_record("))
            {
                lines.add(line);
            }
        }
        assertEquals(4, lines.size(), lines.toString());
        for (String line : lines)
        {
            assertTrue(line.contains("by:"), line);
            assertTrue(!line.contains("ordering:"), "W4 OR2: ordering= is retired - " + line);
        }
        Rule loads = SequenceFunctionTest.load(
                "count(sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)], keep_missings=false)) > 0");
        assertEquals(null, loads.getLoadError());
    }
}
