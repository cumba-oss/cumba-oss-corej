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

    /** The {@code __error__} sentinel sites (2-arg form). */
    private static final int SENTINEL_SITES = 7;

    /** Record-level and Group sites: the shared empty constant. */
    private static final int EMPTY_LIST_SITES = 5;

    /** Dataset verdicts: the name-only projection's list. */
    private static final int PROJECTION_SITES = 3;

    /** Column-level per-variable sites: the list the resolver filled. */
    private static final int PER_VARIABLE_SITES = 2;

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
            else if ("List.of()".equals(last))
            {
                kinds.merge("empty", 1, Integer::sum);
            }
            else if (last.matches("\\w+\\.namedColumns\\(\\)"))
            {
                kinds.merge("projection", 1, Integer::sum);
            }
            else if ("namedColumns(named)".equals(last))
            {
                kinds.merge("per-variable", 1, Integer::sum);
            }
            else
            {
                offenders.add("line " + line + ": unclassified name-only argument '" + last + "'");
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
