package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Gate <b>R-4.10 / R-4.10a</b> — a malformed {@code Scope.Use_Case} is a load error (ruling T1-4 of
 * {@code plans/PLAN-use-case-scope-filter.md}). Once the run's use case filters rules, a misspelt
 * value is no longer inert: it matches no use case and would silently exclude the rule from every
 * run that names one.
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


    @Test
    void wellFormedValuesLoadClean() throws IOException
    {
        for (String ok : List.of("\"INDH\"", "\"INDH, PROD\"", "\"NONCLIN,INDH , PROD\"", "null"))
        {
            assertNull(load(ok).getLoadError(), ok);
        }
    }


    @Test
    void aNonCommaSeparatorIsALoadError() throws IOException
    {
        String error = load("\"INDH;PROD\"").getLoadError();
        assertNotNull(error, "\"INDH;PROD\" is one code that matches no use case");
        assertTrue(error.contains("[TEST-UC] Scope.Use_Case 'INDH;PROD' is not a comma-separated"
                + " list of upper-case use-case codes (R-4.10"), error);
    }


    @Test
    void everyOtherMalformedShapeIsALoadError() throws IOException
    {
        // empty / blank / comma-only (names nothing), an empty code between commas, a trailing
        // comma, a digit, a space instead of a comma.
        for (String bad : List.of("\"\"", "\" \"", "\",\"", "\"INDH,,PROD\"", "\"INDH,\"",
                "\"INDH1\"", "\"INDH PROD\""))
        {
            String error = load(bad).getLoadError();
            assertNotNull(error, bad);
            assertTrue(error.contains("R-4.10,") && error.contains("matches no use case"),
                    bad + " -> " + error);
        }
    }


    /**
     * Review L2: a value that is only mis-cased or padded still MATCHES at run time (the matcher is
     * case-insensitive and strips), so its message must not claim it "matches no use case" — it
     * names the spelling R-4.10 requires instead.
     */
    @Test
    void aMisCasedOrPaddedValueIsALoadErrorWithItsOwnMessage() throws IOException
    {
        for (String[] c : new String[][]
        {
                {
                        "\"indh\"", "indh", "INDH"
                },
                {
                        "\" INDH\"", " INDH", "INDH"
                },
                {
                        "\"indh, Prod \"", "indh, Prod ", "INDH, PROD"
                }
        })
        {
            String error = load(c[0]).getLoadError();
            assertEquals("[TEST-UC] Scope.Use_Case '" + c[1] + "': R-4.10 requires upper-case codes"
                    + " without surrounding blanks — write '" + c[2] + "'", error, c[0]);
        }
    }


    @Test
    void aDuplicateCodeIsALoadError_R4_10a() throws IOException
    {
        String error = load("\"INDH, PROD, INDH\"").getLoadError();
        assertNotNull(error);
        assertEquals("[TEST-UC] Scope.Use_Case 'INDH, PROD, INDH' lists INDH twice (R-4.10a) —"
                + " remove the duplicate", error);
    }
}
