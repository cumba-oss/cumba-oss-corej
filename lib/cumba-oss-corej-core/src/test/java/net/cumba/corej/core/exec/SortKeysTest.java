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
 * and {@code empty_within_except_last_row} accept {@code by=} beside {@code ordering=}, exactly one
 * ascending key as today, more keys or {@code desc} a load error <i>"not supported for
 * &lt;f&gt;"</i>. {@code is_sorted_by} keeps reading the key NAMES only (a written {@code desc} /
 * {@code nulls=} ignored, D20 — W2 SB1 honours the direction), so every written form answers as
 * before.
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
    void isSortedByReadsTheKeyNamesOnlyUntilW2()
    {
        // D20: direction and nulls= stay ignored; a bare column, a bound name and a spliced name
        // list read the same names
        List<Long> written = fired(se(),
                "not is_sorted_by(SESEQ, by=[asc(SESTDTC)], within=USUBJID)");
        assertEquals(List.of(4L, 5L, 6L), written, "premise: S2's SESEQ is not in date order");
        for (String by : List.of("[desc(SESTDTC)]", "[SESTDTC]", "SESTDTC", "$keys", "[$keys]",
                "[asc($c)]", "[desc($c)]", "[asc(SESTDTC, nulls=\"first\")]"))
        {
            assertEquals(written,
                    fired(se(), "not is_sorted_by(SESEQ, by=" + by + ", within=USUBJID)"), by);
        }
    }


    @Test
    void byIsAcceptedOnTheThreeSingleKeyReadersAsAPureRename()
    {
        IDataTable ae = WithinOrderingSpliceTest.ae();
        for (String[] pair : List.of(new String[]
        {
                "is_last_in_group(ordering=AESEQ, group=[USUBJID])",
                "is_last_in_group(by=AESEQ, group=[USUBJID])", "is_last_in_group"
        }, new String[]
        {
                "empty_within_except_last_row(AEENDTC, USUBJID, ordering=AESEQ)",
                "empty_within_except_last_row(AEENDTC, USUBJID, by=AESEQ)",
                "empty_within_except_last_row"
        }, new String[]
        {
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, ordering=AESEQ)",
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=AESEQ)",
                "has_next_corresponding_record"
        }))
        {
            List<Long> written = SequenceFunctionTest.fired(ae, pair[0]);
            assertTrue(!written.isEmpty(), pair[0]);
            assertEquals(written, SequenceFunctionTest.fired(ae, pair[1]), pair[1]);
            assertEquals(written,
                    SequenceFunctionTest.fired(ae, pair[1].replace("by=AESEQ", "by=[asc(AESEQ)]")));
            assertEquals(written,
                    SequenceFunctionTest.fired(ae, pair[1].replace("by=AESEQ", "by=[AESEQ]")));
            assertEquals(written, SequenceFunctionTest.fired(ae,
                    pair[1].replace("by=AESEQ", "by=$o"), "$o", "[AESEQ]"));
            String f = pair[2];
            String desc = loadError(pair[1].replace("by=AESEQ", "by=[desc(AESEQ)]"));
            assertTrue(desc.contains("not supported for " + f), f + " desc: " + desc);
            String two = loadError(pair[1].replace("by=AESEQ", "by=[AESEQ, AESTDTC]"));
            assertTrue(two.contains("not supported for " + f), f + " two: " + two);
            String both = loadError(pair[1].replace("by=AESEQ", "by=AESEQ, ordering=AESEQ"));
            assertTrue(both.contains("by=") && both.contains("ordering="), both);
            String neither = loadError(pair[1].replace("by=AESEQ, ", "").replace(", by=AESEQ", ""));
            assertTrue(neither.contains("by="), f + ": " + neither);
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
        }
        Rule loads = SequenceFunctionTest.load(
                "count(sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)], keep_missings=false)) > 0");
        assertEquals(null, loads.getLoadError());
    }
}
