package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@code D4-R3} — the load-time half of {@code PLAN-join-key-type-identity}.
 *
 * <p>
 * {@code Join_As_String} is the author's override for join-key type identity, and it is validated
 * <b>strictly</b> in two independent ways, because both failure shapes are the same one: a flag
 * that looks as though it took effect and did not.
 * </p>
 *
 * <ol>
 * <li><b>Not a boolean → load error.</b> Bound as a {@code Boolean}, Jackson silently coerces the
 * <em>strings</em> {@code "true"} / {@code "false"} and the numbers {@code 1} / {@code 0}, so a
 * quoted spelling would work by accident and an author would never learn it was wrong. The field is
 * therefore held as a raw {@code JsonNode} purely so this can be rejected.</li>
 * <li><b>On an entry the type check does not govern → load error.</b> {@code Child:true} /
 * {@code RELREC} / {@code SUPP--} entries match a text-carried foreign key against a typed column
 * by design ({@code JKM R6}); the flag does nothing there.</li>
 * </ol>
 *
 * <p>
 * ⚑ Strictness costs nothing: <b>zero</b> of the 3 435 corpus rule files carry the flag.
 * </p>
 */
class JoinAsStringValidationTest
{

    private static String pkg(String entryExtra)
    {
        return """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},\
                "Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID"]%s}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != ADSL.AGE"}]}}}}""".formatted(entryExtra);
    }


    private static Rule load(String entryExtra) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(pkg(entryExtra)).getRules().get("x");
        assertNotNull(rule, "the fixture must bind, or nothing below is measuring anything");
        return rule;
    }


    /** The legal spellings load clean and mean what they say. */
    @Test
    void anUnquotedBooleanLoadsCleanly() throws IOException
    {
        assertNull(load(",\"Join_As_String\":true").getLoadError(),
                "an unquoted true is the authored form and must load");
        assertNull(load(",\"Join_As_String\":false").getLoadError(),
                "an explicit false is legal and means the D4-R1 default");
        assertNull(load("").getLoadError(),
                "⛔ ABSENT must stay legal — every one of the 214 shipped Match_Datasets entries"
                        + " omits this field, so rejecting absence would reject the whole corpus"
                        + " while looking like a working validation");
    }


    /**
     * ⛔ The quoted string is REJECTED, not coerced — the point of holding the field raw.
     */
    @Test
    void aQuotedBooleanFilesALoadError() throws IOException
    {
        Rule rule = load(",\"Join_As_String\":\"true\"");
        String error = rule.getLoadError();
        assertNotNull(error, "⛔ Jackson would coerce the STRING \"true\" to true and the flag would"
                + " take effect by accident; that silent success is what this rejects");
        assertTrue(error.contains("Invalid Join_As_String 'true'"),
                "the error must quote the authored value verbatim; was: " + error);
        assertTrue(error.contains("ADSL"),
                "the error must name the entry so a multi-entry rule is actionable; was: " + error);
        assertTrue(error.contains("unquoted"),
                "the error must say what the legal form IS, not only that this one is wrong; was: "
                        + error);
    }


    @Test
    void aNumberFilesALoadError() throws IOException
    {
        assertNotNull(load(",\"Join_As_String\":1").getLoadError(),
                "1 coerces to true under Jackson's default coercion; reject it");
        assertNotNull(load(",\"Join_As_String\":\"yes\"").getLoadError(),
                "an arbitrary string is rejected too");
    }


    /**
     * ⭐⭐ {@code D4-R5} — the flag on an excluded entry is a load error, not a silent no-op.
     */
    @Test
    void theFlagOnAnExcludedEntryFilesALoadError() throws IOException
    {
        String error = String
                .valueOf(load(",\"Child\":true,\"Join_As_String\":true").getLoadError());
        assertTrue(error.contains("no effect"),
                "a Child:true entry is governed by JKM R6, not by this flag — an author who writes"
                        + " it there believes they controlled the join's type behaviour and did"
                        + " not; was: " + error);
        assertNull(load(",\"Child\":true").getLoadError(),
                "⛔ and a Child entry WITHOUT the flag must still load — otherwise this check has"
                        + " broken the eleven shipped Child entries rather than guarded them");
    }


    /** The same rejection for the name-based members of the excluded family. */
    @Test
    void theFlagOnRelrecOrSuppFilesALoadError() throws IOException
    {
        assertTrue(
                String.valueOf(RulePackageLoader
                        .loadFromString(pkg(",\"Join_As_String\":true").replace("ADSL", "RELREC"))
                        .getRules().get("x").getLoadError()).contains("no effect"),
                "RELREC is excluded");
        assertTrue(
                String.valueOf(RulePackageLoader
                        .loadFromString(pkg(",\"Join_As_String\":true").replace("ADSL", "SUPPAE"))
                        .getRules().get("x").getLoadError()).contains("no effect"),
                "SUPP-- is excluded");
        assertFalse(
                String.valueOf(load(",\"Join_As_String\":true").getLoadError())
                        .contains("no effect"),
                "⛔ and a PLAIN entry must NOT be rejected — without this the check would reject"
                        + " every use of the flag and still look like it was discriminating");
    }

}
