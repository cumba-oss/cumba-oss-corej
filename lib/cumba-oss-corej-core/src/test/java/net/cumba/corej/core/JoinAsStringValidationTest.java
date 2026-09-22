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


    /**
     * The same fixture with the entry's {@code Name} changed — ⚠ replacing only the {@code "Name"}
     * value, never blanket-replacing the string across the whole package, which would also rewrite
     * {@code ADSL.AGE} inside the Check and silently change what is under test.
     */
    private static Rule loadNamed(String name) throws IOException
    {
        String json = pkg(",\"Join_As_String\":true").replace("\"Name\":\"ADSL\"",
                "\"Name\":\"" + name + "\"");
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the renamed fixture must still bind");
        return rule;
    }


    /** {@code Join_As_String} on a KEYLESS entry is also a no-op, and also a load error. */
    @Test
    void theFlagOnAKeylessEntryFilesALoadError() throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Join_As_String":true}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != ADSL.AGE"}]}}}}""";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the fixture must bind");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("no effect"),
                "⛔ an entry with no Keys builds no key comparison, so the flag does nothing there."
                        + " The guard originally asked only whether the entry was an EXCLUDED"
                        + " family, so this shape loaded clean and silently did nothing — the exact"
                        + " defect the guard exists to prevent, inside the guard; was: " + error);
        assertTrue(error.contains("no Keys"),
                "and it must say WHICH of the two reasons applies, or the author looks at the wrong"
                        + " half of the entry; was: " + error);
    }


    /**
     * ⭐⭐ A half-declared sided key is a load error — found in review round 2, in the round-1 fix.
     *
     * <p>
     * ⛔ {@code getKeys()} and {@code getRightKeys()} each SKIP an object element lacking their
     * side, so {@code {"left":"A"}} yields lists of different LENGTHS while {@code hasSidedKeys()}
     * still answers {@code true}. Every consumer indexes them in lockstep. Before round 1's
     * sided-key fix this shape resolved the child on the left names (silently wrong); after it, and
     * without this check, it would be an {@code ArrayIndexOutOfBoundsException}.
     * </p>
     */
    @Test
    void aHalfDeclaredSidedKeyFilesALoadError() throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID",{"left":"AGE"}]}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != ADSL.AGE"}]}}}}""";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the fixture must bind");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("malformed Keys element"),
                "a sided element declaring just \"left\" makes the two key lists different lengths"
                        + " and is unusable; was: " + error);
        String ok = """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID",{"left":"AGE","right":"AGEY"}]}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != ADSL.AGE"}]}}}}""";
        assertNull(RulePackageLoader.loadFromString(ok).getRules().get("x").getLoadError(),
                "⛔ and a WELL-FORMED sided entry must still load — otherwise this check has banned"
                        + " the sided shape rather than guarding it");
    }


    /**
     * ⭐⭐ The two shapes a LIST-SIZE check waves through — review round 2's M1, found in the round-1
     * fix's own guard.
     *
     * <p>
     * ⛔ The first guard compared {@code getKeys().size()} with {@code getRightKeys().size()}. That
     * is a <em>proxy</em> for "every element is usable", and the proxy is satisfiable without the
     * property: {@code [{"left":"A"},{"right":"B"}]} gives sizes 1 and 1, so it passed — and the
     * engine would then join {@code A} against {@code B} as ONE component where the author declared
     * two. An element skipped on BOTH sides ({@code {}}) is the same class: sizes agree and the
     * component silently leaves the join, widening every match.
     * </p>
     */
    @Test
    void theTwoShapesASizeCheckWouldWaveThrough() throws IOException
    {
        String twoHalves = keysFixture("[{\"left\":\"A\"},{\"right\":\"B\"}]");
        assertTrue(
                String.valueOf(RulePackageLoader.loadFromString(twoHalves).getRules().get("x")
                        .getLoadError()).contains("malformed Keys element"),
                "two half-declared elements have EQUAL list sizes (1 and 1) and must still be"
                        + " rejected — this is the hole a size comparison leaves");
        String emptyObject = keysFixture("[\"USUBJID\",{}]");
        assertTrue(
                String.valueOf(RulePackageLoader.loadFromString(emptyObject).getRules().get("x")
                        .getLoadError()).contains("malformed Keys element"),
                "an element neither side can read is dropped from BOTH lists, so the sizes still"
                        + " agree while a key component silently leaves the join");
        String nonTextual = keysFixture("[{\"left\":\"A\",\"right\":5}]");
        assertTrue(
                String.valueOf(RulePackageLoader.loadFromString(nonTextual).getRules().get("x")
                        .getLoadError()).contains("malformed Keys element"),
                "a non-textual side is unreadable too");
        assertNull(
                RulePackageLoader.loadFromString(keysFixture("[\"USUBJID\",\"AGE\"]")).getRules()
                        .get("x").getLoadError(),
                "⛔ and the ordinary all-bare-string shape -- every one of the 252 shipped key"
                        + " occurrences -- must still load clean");
    }


    /**
     * ⭐ Round 3, L2 — a well-formed sided key on a {@code Child} entry is rejected, because
     * {@code ChildMatchPreMerger} reads the LEFT names on both sides and would ignore the
     * declaration silently.
     */
    @Test
    void aSidedKeyOnAChildEntryFilesALoadError() throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"AE","Child":true,\
                "Keys":["USUBJID",{"left":"IDVAR","right":"XIDVAR"}]}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != AE.AGE"}]}}}}""";
        String error = String
                .valueOf(RulePackageLoader.loadFromString(json).getRules().get("x").getLoadError());
        assertTrue(error.contains("combines sided Keys"),
                "the element is WELL FORMED, so malformedKeyElement() passes it — this is the"
                        + " separate case: the Child/SUPP pivot reads the left names only; was: "
                        + error);
        String ordinaryChild = """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"AE","Child":true,"Keys":["USUBJID","IDVAR"]}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != AE.AGE"}]}}}}""";
        assertNull(
                RulePackageLoader.loadFromString(ordinaryChild).getRules().get("x").getLoadError(),
                "⛔ a Child entry with ORDINARY bare-string keys must still load clean — that is"
                        + " every one of the 11 shipped Child entries, and without this arm the"
                        + " check would have banned the family rather than the combination");
    }


    /**
     * ⭐⭐ Round 4, M1 — a NAMELESS entry is not a Child/RELREC/SUPP entry, and must not be told it
     * is.
     *
     * <p>
     * ⛔ {@code excludedFromKeyTypeCheck} answers {@code true} for a nameless entry via its own
     * {@code name == null} clause, so asking it *"is this the text-carried foreign-key family?"*
     * blames Child/RELREC/SUPP for an entry that is none of them — and newly fails a rule that
     * previously loaded. The predicate is now decomposed
     * ({@code JoinKeyTypes.textCarriedForeignKeyFamily}) precisely so this arm cannot ask the wider
     * question. ⚑ The same defect was written twice in one change before the decomposition existed,
     * which is why it is pinned rather than trusted.
     * </p>
     */
    @Test
    void aNamelessSidedEntryIsNotBlamedOnTheChildFamily() throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Keys":["USUBJID",{"left":"A","right":"B"}]}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != ADSL.AGE"}]}}}}""";
        String error = String
                .valueOf(RulePackageLoader.loadFromString(json).getRules().get("x").getLoadError());
        assertFalse(error.contains("combines sided Keys"),
                "⛔ the entry has no Name — it is not Child, not RELREC, not SUPP. Blaming that"
                        + " family is both the wrong diagnosis and a NEW load failure on a shape"
                        + " that loaded before; was: " + error);
    }


    /** A package whose single entry carries the given raw {@code Keys} JSON. */
    private static String keysFixture(String keysJson)
    {
        return """
                {"rules":{"x":{"Core":{"Id":"T-JAS"},"Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":%s}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"expression": "AGE != ADSL.AGE"}]}}}}""".formatted(keysJson);
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
        assertTrue(String.valueOf(loadNamed("RELREC").getLoadError()).contains("no effect"),
                "RELREC is excluded");
        assertTrue(String.valueOf(loadNamed("SUPPAE").getLoadError()).contains("no effect"),
                "SUPP-- is excluded");
        assertFalse(
                String.valueOf(load(",\"Join_As_String\":true").getLoadError())
                        .contains("no effect"),
                "⛔ and a PLAIN entry must NOT be rejected — without this the check would reject"
                        + " every use of the flag and still look like it was discriminating");
    }

}
