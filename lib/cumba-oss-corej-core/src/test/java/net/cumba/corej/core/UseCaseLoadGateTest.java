package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Gate <b>R-4.10 / R-4.10a</b> — a malformed {@code Scope.Use_Case} is a load error (ruling T1-4 of
 * {@code plans/PLAN-use-case-scope-filter.md}). Since the run's use case filters rules, the gate
 * rejects a malformed value at load, so the rule's author sees it — the rule reports an ERROR
 * naming the value and R-4.10 instead of running.
 *
 * <p>
 * Driven through {@link RulePackageLoader#loadFromString}, the production load path. ⚠ A load error
 * is <b>recorded</b> on {@link Rule#getLoadError()}, never thrown, so every case asserts on the
 * recorded message — an {@code assertThrows} over this gate would pass whether or not it fires.
 * </p>
 */
class UseCaseLoadGateTest
{

    private static final String CHECK = "\"Check\":{\"expression\": \"not empty(USUBJID)\"}";

    private static Rule load(String useCaseJson) throws IOException
    {
        String json = "{\"rules\":{\"rule-1\":{\"Core\":{\"Id\":\"TEST-UC\"},\"Scope\":{\"Use_Case\":"
                + useCaseJson + "}," + CHECK + "}}}";
        return RulePackageLoader.loadFromString(json).getRules().values().iterator().next();
    }

    /** R-4.10 as every message states it. */
    private static final String RULE = "(R-4.10: upper-case letter codes, comma-separated, each"
            + " listed once)";

    private static String notWellFormed(String raw)
    {
        return "[TEST-UC] Scope.Use_Case '" + raw + "' is not a well-formed value " + RULE;
    }


    @Test
    void wellFormedValuesLoadClean() throws IOException
    {
        // blanks AROUND a comma are part of R-4.10's shape ("INDH , PROD" included)
        for (String ok : List.of("\"INDH\"", "\"INDH, PROD\"", "\"NONCLIN,INDH , PROD\"",
                "\"INDH , PROD\"", "null"))
        {
            assertNull(load(ok).getLoadError(), ok);
        }
    }

    // ---- review rounds 1-3: one case per shape, and each message must be TRUE for its input.
    // A message states what is wrong and the rule; a "write '…'" clause only when a well-formed
    // suggestion exists and differs; "lists 'C' more than once" only when C really repeats. No
    // message
    // predicts a consequence ("matches no use case"), because such claims were false for some
    // input in every round.


    @Test
    void noCode_namesNoUseCase() throws IOException
    {
        for (String[] c : new String[][]
        {
                {
                        "\"\"", ""
                },
                {
                        "\" \"", " "
                },
                {
                        "\",\"", ","
                }
        })
        {
            assertEquals("[TEST-UC] Scope.Use_Case '" + c[1] + "' names no use case " + RULE,
                    load(c[0]).getLoadError(), c[0]);
        }
    }


    @Test
    void strayComma_suggestsTheValueWithoutIt() throws IOException
    {
        assertEquals(notWellFormed("INDH,") + " — write 'INDH'", load("\"INDH,\"").getLoadError());
        assertEquals(notWellFormed("INDH,,PROD") + " — write 'INDH, PROD'",
                load("\"INDH,,PROD\"").getLoadError());
    }


    @Test
    void misCasedOrPadded_suggestsTheCanonicalSpelling() throws IOException
    {
        assertEquals(notWellFormed("indh") + " — write 'INDH'", load("\"indh\"").getLoadError());
        assertEquals(notWellFormed(" INDH") + " — write 'INDH'", load("\" INDH\"").getLoadError());
        assertEquals(notWellFormed("indh, Prod ") + " — write 'INDH, PROD'",
                load("\"indh, Prod \"").getLoadError());
    }


    /** A case-only repeat is NOT an exact repeat, and the advice never proposes "INDH, INDH". */
    @Test
    void caseOnlyRepeat_suggestsOneCode_andClaimsNoRepeat() throws IOException
    {
        assertEquals(notWellFormed("indh, INDH") + " — write 'INDH'",
                load("\"indh, INDH\"").getLoadError());
    }


    @Test
    void exactRepeat_namesTheRepeatedCode_R4_10a() throws IOException
    {
        assertEquals(
                notWellFormed("INDH, PROD, INDH") + " — lists 'INDH' more than once"
                        + " (R-4.10a) — write 'INDH, PROD'",
                load("\"INDH, PROD, INDH\"").getLoadError());
        // Review round 4: three occurrences — "twice" would have been false.
        assertEquals(notWellFormed("INDH,INDH,INDH") + " — lists 'INDH' more than once"
                + " (R-4.10a) — write 'INDH'", load("\"INDH,INDH,INDH\"").getLoadError());
    }


    /**
     * Round 3: a non-ASCII whitespace separator (U+3000). R-4.10's {@code \\s} does not match it,
     * so the value is not well-formed; the codes, once stripped, are distinct — no repeat may be
     * claimed (round 2's text said "lists twice").
     */
    @Test
    void ideographicSpaceSeparator_suggestsTheAsciiSpelling_andClaimsNoRepeat() throws IOException
    {
        assertEquals(notWellFormed("INDH,\u3000PROD") + " — write 'INDH, PROD'",
                load("\"INDH,\\u3000PROD\"").getLoadError());
    }


    /**
     * Round 3: a digit in one code. No well-formed suggestion exists, so none is offered — and the
     * message does not say the value "matches no use case" (INDH would match).
     */
    @Test
    void digitInACode_isNotWellFormed_withNoSuggestion() throws IOException
    {
        assertEquals(notWellFormed("INDH, PR0D"), load("\"INDH, PR0D\"").getLoadError());
        assertEquals(notWellFormed("INDH1"), load("\"INDH1\"").getLoadError());
    }


    @Test
    void nonCommaSeparatorOrInnerSpace_isNotWellFormed_withNoSuggestion() throws IOException
    {
        for (String raw : List.of("INDH;PROD", "INDH PROD", "IN DH, PROD", "indh;prod"))
        {
            assertEquals(notWellFormed(raw), load("\"" + raw + "\"").getLoadError(), raw);
        }
    }
}
