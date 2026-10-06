package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-finding-values-record-level-only} §7 M3 — <b>every {@code new Violation(} site of
 * {@code RuleRunner} carries the name-only list explicitly</b>, so a sibling field can never be
 * lost in silence the way {@code groupKey} was at the level merge (rebuilt with the 7-arg
 * constructor, which defaulted it to {@code null}).
 *
 * <p>
 * Read from the SOURCE, like {@code EngineErrorMessageContractTest}: a compiled form keeps no
 * record of which constructor overload a site chose. The contract, per site:
 * </p>
 * <ul>
 * <li>a {@code __error__} sentinel uses the 2-argument form (it lists nothing, by definition);</li>
 * <li>every other site passes all 9 arguments, the last being the name-only list — the shared
 * {@code List.of()} on a record-level or Group site, a projection's {@code namedColumns()} on a
 * dataset verdict, {@code namedColumns(named)} on a column-level per-variable site;</li>
 * <li>the level merge re-stamps through {@code withLevel}, never a constructor.</li>
 * </ul>
 * <p>
 * <b>And each site's list kind agrees with the unit it stamps</b> (P1 review M-1 (b)) — the
 * producing site decides record-level (§7 M1), so the pairing is the contract:
 * </p>
 * <ul>
 * <li>{@code Unit.DATASET} ⇒ the name-only projection's {@code *.namedColumns()};</li>
 * <li>{@code Unit.Row(…)} and {@code Unit.Column(…, realRow)} (a row of the table) ⇒
 * {@code List.of()};</li>
 * <li>a Group site (it passes a group key) ⇒ {@code List.of()} (Q-A);</li>
 * <li>{@code Unit.Column(…, violationRow)} (a column-level finding, no row) ⇒
 * {@code namedColumns(named)} over the projected {@code filtered} map, or {@code List.of()} over
 * the default metadata projection {@code values}, which projects no raw column.</li>
 * </ul>
 * <p>
 * And the record-level projection {@code extractOutputValues(} has exactly
 * {@value #RECORD_PROJECTION_CALLS} call sites (the row path, the per-(variable, row) path, the
 * Group anchor read): a fourth caller is a new producing site that must say which projection it is.
 * </p>
 *
 * <p>
 * The counts are an <b>exact-equality ratchet</b>: a new producing site must be classified here,
 * and a site that moves between kinds must say so. A looser floor would let a 7-arg site slip back
 * in.
 * </p>
 */
class ViolationConstructionSitesTest
{

    private static final Path RULE_RUNNER = Path.of(System.getProperty("projectBasedir"), "src",
            "main", "java", "net", "cumba", "corej", "core", "exec", "RuleRunner.java");

    /**
     * The {@code __error__} sentinel sites (2-arg form). 7 -> 9 (PLAN-merged-evaluation-table W3a):
     * {@code RuleRunner.mergeErrorGate} (a rule references a merged column carrying MERGE_ERROR,
     * OD-7) and the {@code MergeException | MergeErrorReadException} catch. 9 -> 8 (W5, MQ11): the
     * forward-RELREC × key-expansion combination sentinel went with the RELREC row expander.
     */
    private static final int SENTINEL_SITES = 8;

    /** Record-level and Group sites: the shared empty constant. */
    private static final int EMPTY_LIST_SITES = 5;

    /** Dataset verdicts: the name-only projection's list. */
    private static final int PROJECTION_SITES = 3;

    /** Column-level per-variable sites: the list the resolver filled. */
    private static final int PER_VARIABLE_SITES = 2;

    /** Call sites of the record-level projection {@code extractOutputValues(}. */
    static final int RECORD_PROJECTION_CALLS = 3;

    @Test
    void everyConstructionSiteCarriesTheNameOnlyListExplicitly() throws IOException
    {
        String src = Files.readString(RULE_RUNNER, StandardCharsets.UTF_8);
        Map<String, Integer> kinds = new TreeMap<>();
        List<String> offenders = new ArrayList<>();
        int from = 0;
        int sites = 0;
        while (true)
        {
            int at = src.indexOf("new Violation(", from);
            if (at < 0)
            {
                break;
            }
            sites++;
            List<String> args = arguments(src, at + "new Violation(".length());
            String last = args.isEmpty() ? "" : args.get(args.size() - 1).trim();
            int line = lineOf(src, at);
            if (args.size() == 2)
            {
                if (args.get(1).contains("\"__error__\""))
                {
                    kinds.merge("sentinel", 1, Integer::sum);
                }
                else
                {
                    offenders.add("line " + line + ": 2-arg site that is no __error__ sentinel");
                }
            }
            else if (args.size() != 9)
            {
                offenders.add("line " + line + ": " + args.size() + " arguments — a compatibility"
                        + " constructor defaults the sibling fields (groupKey, namedColumns);"
                        + " pass all 9");
            }
            else
            {
                String kind = listKind(last);
                if (kind == null)
                {
                    offenders.add(
                            "line " + line + ": unclassified name-only argument '" + last + "'");
                }
                else
                {
                    kinds.merge(kind, 1, Integer::sum);
                    String expected = expectedListKind(args);
                    if (!kind.equals(expected))
                    {
                        offenders.add("line " + line + ": unit '" + args.get(6).trim()
                                + "' (values '" + args.get(1).trim() + "', group key '"
                                + args.get(7).trim() + "') wants the " + expected
                                + " list, the site passes the " + kind + " one ('" + last + "')");
                    }
                }
            }
            from = at + 1;
        }
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
        assertEquals(SENTINEL_SITES, kinds.getOrDefault("sentinel", 0), kinds.toString());
        assertEquals(EMPTY_LIST_SITES, kinds.getOrDefault("empty", 0), kinds.toString());
        assertEquals(PROJECTION_SITES, kinds.getOrDefault("projection", 0), kinds.toString());
        assertEquals(PER_VARIABLE_SITES, kinds.getOrDefault("per-variable", 0), kinds.toString());
        assertEquals(SENTINEL_SITES + EMPTY_LIST_SITES + PROJECTION_SITES + PER_VARIABLE_SITES,
                sites, "every site is classified");
    }


    @Test
    void theRecordLevelProjectionHasExactlyItsThreeCallSites() throws IOException
    {
        String src = Files.readString(RULE_RUNNER, StandardCharsets.UTF_8);
        int calls = count(src, "extractOutputValues(")
                - count(src, "static Map<String, String> extractOutputValues(");
        assertEquals(1, count(src, "static Map<String, String> extractOutputValues("),
                "one declaration");
        assertEquals(RECORD_PROJECTION_CALLS, calls,
                "the record-level projection's callers are exactly the row path, the"
                        + " per-(variable, row) path and the Group anchor read — a new caller is a"
                        + " new producing site that must be classified (record-level or name-only)");
    }


    /** The list kind of a site's last argument, or {@code null} when unclassified. */
    private static String listKind(String last)
    {
        if ("List.of()".equals(last))
        {
            return "empty";
        }
        if (last.matches("\\w+\\.namedColumns\\(\\)"))
        {
            return "projection";
        }
        if ("namedColumns(named)".equals(last))
        {
            return "per-variable";
        }
        return null;
    }


    /**
     * The list kind a 9-argument site's unit (argument 7) demands — see the class javadoc. A unit
     * the table does not know is {@code "unclassified unit"}, which no list kind equals.
     */
    private static String expectedListKind(List<String> args)
    {
        String unit = args.get(6).replaceAll("\\s+", " ").trim();
        String values = args.get(1).trim();
        boolean grouped = !"null".equals(args.get(7).trim());
        if (grouped)
        {
            return "empty";
        }
        if (unit.contains("Violation.Unit.DATASET"))
        {
            return "projection";
        }
        if (unit.contains("new Violation.Unit.Row("))
        {
            return "empty";
        }
        if (unit.matches(".*new Violation\\.Unit\\.Column\\([^,]+, realRow\\).*"))
        {
            return "empty";
        }
        if (unit.matches(".*new Violation\\.Unit\\.Column\\([^,]+, violationRow\\).*"))
        {
            return "filtered".equals(values) ? "per-variable" : "empty";
        }
        return "unclassified unit";
    }


    @Test
    void theLevelMergeReStampsThroughWithLevel() throws IOException
    {
        String src = Files.readString(RULE_RUNNER, StandardCharsets.UTF_8);
        assertEquals(1, count(src, ".withLevel("),
                "exactly one level merge, and it copies every sibling field through withLevel");
    }


    /**
     * The top-level comma-separated arguments of a call whose opening paren is just before
     * {@code from}.
     */
    private static List<String> arguments(String src, int from)
    {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = from;
        for (int i = from; i < src.length(); i++)
        {
            char c = src.charAt(i);
            if (inString)
            {
                if (c == '\\')
                {
                    i++;
                }
                else if (c == '"')
                {
                    inString = false;
                }
                continue;
            }
            switch (c)
            {
            case '"' -> inString = true;
            case '(', '[', '{' -> depth++;
            case ')', ']', '}' ->
            {
                if (depth == 0)
                {
                    out.add(src.substring(start, i));
                    return out;
                }
                depth--;
            }
            case ',' ->
            {
                if (depth == 0)
                {
                    out.add(src.substring(start, i));
                    start = i + 1;
                }
            }
            default ->
            {
                // part of the current argument
            }
            }
        }
        throw new IllegalStateException("unbalanced call at offset " + from);
    }


    private static int lineOf(String src, int offset)
    {
        int line = 1;
        for (int i = 0; i < offset; i++)
        {
            if (src.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }


    private static int count(String haystack, String needle)
    {
        int n = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0)
        {
            n++;
            from += needle.length();
        }
        return n;
    }
}
