package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreTextNormaliser}: the three normalisation rules on the exact shapes the census found
 * (PLAN-store-library-content-errata §2.2 / §2.6), and that nothing else moves — a doubled space or
 * a trailing space is an errata-register row, never a blanket rewrite.
 */
class StoreTextNormaliserTest
{

    @Test
    void aLiteralBackslashNInAShortFieldBecomesOneSpaceAndTheFieldIsTrimmed()
    {
        StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("label",
                "Sequence Identifier \\n");
        assertEquals("Sequence Identifier", r.value(), "#283.34");
        assertEquals(Set.of(StoreTextNormaliser.Kind.SHORT_FIELD_NEWLINE), r.kinds());
        assertEquals("Not in AE domain",
                StoreTextNormaliser.normalise("usageRestrictions", "Not in AE domain \\n").value(),
                "#282.14");
    }


    @Test
    void aRealLineBreakInAShortFieldBecomesOneSpace()
    {
        assertEquals("On Treatment Record Flag",
                StoreTextNormaliser.normalise("label", "On Treatment\nRecord Flag").value(),
                "#282.13");
        assertEquals("ECG Clinical Significance",
                StoreTextNormaliser.normalise("label", "ECG Clinical Significance \n").value(),
                "#283.39");
        assertEquals(
                "([Intended/Planned/Actual]) Second", StoreTextNormaliser
                        .normalise("prompt", "([Intended/Planned/Actual]) Second \r\n").value(),
                "#283.42");
    }


    @Test
    void aLiteralBackslashNInALongFieldBecomesALineBreakWithTheSpacesFolded()
    {
        StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("notes",
                "format. \\n Equal to the latest value.");
        assertEquals("format.\nEqual to the latest value.", r.value(), "#283.35");
        assertEquals(Set.of(StoreTextNormaliser.Kind.LITERAL_NEWLINE), r.kinds());
        assertEquals("Text. \\n Next".replace(" \\n ", "\n"),
                StoreTextNormaliser.normalise("description", "Text. \\n Next").value());
        assertEquals("trial.\n\nGenerally",
                StoreTextNormaliser.normalise("description", "trial. \\n \\n Generally").value());
    }


    @Test
    void aTrailingLiteralBreakInALongFieldIsDropped()
    {
        assertEquals("An NCI CTCAE Short Name.\n\"HYPERCALCEMIA\"",
                StoreTextNormaliser
                        .normalise("examples", "An NCI CTCAE Short Name. \\n \"HYPERCALCEMIA\" \\n")
                        .value());
    }


    @Test
    void aRealLineBreakInALongFieldIsLeftAlone()
    {
        String text = "Line one.\nLine two.";
        StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("description", text);
        assertEquals(text, r.value());
        assertEquals(Set.of(), r.kinds());
    }


    @Test
    void htmlEntitiesAreDecodedInEveryField()
    {
        StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("definition",
                "Record &quot;Not Done&quot; &amp; move on &lt;3 &#39;x&#x41;&nbsp;");
        assertEquals("Record \"Not Done\" & move on <3 'xA ", r.value(), "#283.40");
        assertEquals(Set.of(StoreTextNormaliser.Kind.HTML_ENTITY), r.kinds());
        assertEquals("\"Yes\"", StoreTextNormaliser.normalise("label", "&quot;Yes&quot;").value());
    }


    @Test
    void bothKindsInOneFieldAreBothCounted()
    {
        StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("label", "&quot;A&quot; \\n");
        assertEquals("\"A\"", r.value());
        assertEquals(Set.of(StoreTextNormaliser.Kind.HTML_ENTITY,
                StoreTextNormaliser.Kind.SHORT_FIELD_NEWLINE), r.kinds());
    }


    @Test
    void nothingElseMoves()
    {
        for (String untouched : new String[]
        {
                "Response  Assessment Short Name", "Reported Name of Drug, Med, or Therapy ",
                " A leading space", "Plain & simple", "a < b"
        })
        {
            StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("label", untouched);
            assertEquals(untouched, r.value(), "doubled, trailing and leading spaces and bare"
                    + " ampersands are register rows, not normalisation");
            assertEquals(Set.of(), r.kinds());
        }
        StoreTextNormaliser.Result n = StoreTextNormaliser.normalise("label", null);
        assertNull(n.value());
        assertEquals(Set.of(), n.kinds());
    }


    /**
     * Review F7: an entity that names no valid character — a numeric value past
     * {@code Integer.MAX_VALUE}, past {@code U+10FFFF}, in the surrogate range — or a name outside
     * the decoded list stays verbatim; normalising source text never throws.
     */
    @Test
    void anEntityThatDoesNotDecodeIsLeftVerbatim()
    {
        for (String verbatim : new String[]
        {
                "overflow &#99999999999; here", "hex overflow &#xFFFFFFFFFF; here",
                "past the last code point &#1114112; and &#x110000;", "a surrogate &#xD800;",
                "an unknown name &hellip; &foo;"
        })
        {
            StoreTextNormaliser.Result r = StoreTextNormaliser.normalise("definition", verbatim);
            assertEquals(verbatim, r.value());
            assertEquals(Set.of(), r.kinds(), "nothing changed, nothing counted");
        }
        StoreTextNormaliser.Result mixed = StoreTextNormaliser.normalise("definition",
                "&#99999999999; &quot;Not Done&quot; &#x1F600; &#1114111;");
        assertEquals("&#99999999999; \"Not Done\" \uD83D\uDE00 \uDBFF\uDFFF", mixed.value(),
                "the valid entities around a bad one still decode, the last code point included");
        assertEquals(Set.of(StoreTextNormaliser.Kind.HTML_ENTITY), mixed.kinds());
    }
}
