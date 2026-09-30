package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-expansion-token-delimiters} S1 — the one scanner every token-aware site reads. The
 * cases pin the lexer's reading order: a complete token first, then the {@code &&} operator, then a
 * stray; overlapping occurrences share a delimiter and are never both read.
 */
class ExpansionTokensTest
{

    private static List<String> occurrences(String text)
    {
        return ExpansionTokens.scan(text).occurrences().stream()
                .map(ExpansionTokens.Occurrence::text).toList();
    }


    private static List<String> strays(String text)
    {
        return ExpansionTokens.scan(text).strays().stream().map(ExpansionTokens.Stray::text)
                .toList();
    }


    @Test
    void aCompleteTokenIsOneOccurrenceWithItsOffsets()
    {
        ExpansionTokens.Scan scan = ExpansionTokens.scan("var_exists(&DOM&.&DOM&SEQ)");
        assertEquals(List.of("&DOM&", "&DOM&"), occurrences("var_exists(&DOM&.&DOM&SEQ)"));
        assertEquals(11, scan.occurrences().get(0).start());
        assertEquals(16, scan.occurrences().get(0).end());
        assertEquals(17, scan.occurrences().get(1).start());
        assertTrue(scan.isClean(), scan.toString());
    }


    @Test
    void overlappingOccurrencesShareADelimiterAndAreReadLeftToRight()
    {
        // `&B&` also occurs at index 2, but the lexer reads `&A&`, `B`, `&C&` — so must the scan.
        assertEquals(List.of("&A&", "&C&"), occurrences("&A&B&C&"));
        assertTrue(ExpansionTokens.scan("&A&B&C&").isClean(), "a letter between tokens is legal");
    }


    @Test
    void operatorTextIsNotAToken()
    {
        // Without the `&&` step the scan would read the "token" `&B&` inside valid operator text.
        assertEquals(List.of(), occurrences("A&&B&&C"));
        assertEquals(List.of(), occurrences("X&&Y"));
        assertTrue(ExpansionTokens.scan("A&&B&&C").isClean());
        assertEquals(List.of(), strays("X&&Y"), "the operator is not a stray");
    }


    @Test
    void aTokenDirectlyAfterTheOperatorIsAnAdjacencyViolation()
    {
        assertEquals(1, ExpansionTokens.scan("COL&&&A&").adjacencies().size());
        assertEquals(1, ExpansionTokens.scan(")&&&A&").adjacencies().size());
        // The token itself is still read, so a message can name it.
        assertEquals(List.of("&A&"), occurrences("COL&&&A&"));
    }


    @Test
    void aTokenDirectlyFollowedByAnAmpersandIsAnAdjacencyViolation()
    {
        assertEquals(1, ExpansionTokens.scan("&A&&B&").adjacencies().size());
        assertEquals(1, ExpansionTokens.scan("&A&&&COL").adjacencies().size());
    }


    @Test
    void anUndelimitedTokenIsAStray()
    {
        assertEquals(List.of("&DOM"), strays("var_exists(&DOM.&DOMSEQ)").subList(0, 1));
        assertEquals(List.of("&DOM", "&DOMSEQ"), strays("var_exists(&DOM.&DOMSEQ)"));
        assertEquals(List.of(), occurrences("var_exists(&DOM.&DOMSEQ)"));
        assertFalse(ExpansionTokens.scan("&DOM").isClean());
    }


    @Test
    void namesAreUpperCaseAscii()
    {
        assertEquals(List.of(), occurrences("&dom&"));
        assertEquals(List.of("&dom"), strays("&dom&"));
        assertEquals(List.of(), occurrences("&Ä&"));
        assertEquals(List.of("&Ä"), strays("&Ä&"));
        assertEquals(List.of(), occurrences("&1&"), "a NAME starts with a letter");
        assertEquals(List.of(), strays("&1&"), "a stray needs a letter after the '&'");
        assertEquals(List.of("&V1&"), occurrences("&V1&"));
        assertTrue(ExpansionTokens.TOKEN.matcher("&V1&").matches());
        assertFalse(ExpansionTokens.TOKEN.matcher("&V_1&").matches());
        assertFalse(ExpansionTokens.TOKEN.matcher("&DOM").matches());
        assertFalse(ExpansionTokens.TOKEN.matcher("DOM&").matches());
        assertFalse(ExpansionTokens.TOKEN.matcher("&&").matches());
    }


    @Test
    void proseAmpersandsAreNeitherTokensNorStrays()
    {
        assertTrue(ExpansionTokens.scan("PCNOMDY & PCTPTREF = PPNOMDY & PPTPTREF").isClean());
        assertEquals(List.of(), occurrences("PCNOMDY & PCTPTREF"));
        // An HTML entity IS a stray (letter after the '&') — which is why G3 is scoped to rules
        // that declare an Expansion block.
        assertEquals(List.of("&lt", "&gt"), strays("base &lt;&gt; 0"));
        assertTrue(ExpansionTokens.scan(null).isClean());
        assertTrue(ExpansionTokens.scan("").isClean());
    }


    @Test
    void strayMessagesNameTheOffendingCharacterOrTheMissingDelimiter()
    {
        assertTrue(ExpansionTokens.strayMessage("&DOM").startsWith(
                "unterminated expansion token '&DOM' (a token is &NAME& with NAME = [A-Z][A-Z0-9]*"),
                ExpansionTokens.strayMessage("&DOM"));
        assertTrue(ExpansionTokens.strayMessage("&DOM").contains("operator '&&'"));
        assertTrue(ExpansionTokens.strayMessage("&dom").contains("upper case"));
        assertTrue(ExpansionTokens.strayMessage("&dom").contains("found 'd'"));
        assertTrue(ExpansionTokens.strayMessage("&V_1").contains("found '_'"));
        assertEquals("&V_1", ExpansionTokens.strayTextAt("x &V_1& y", 2));
        assertEquals("&", ExpansionTokens.strayTextAt("&", 0));
    }


    @Test
    void tokenEndAtReadsExactlyOneToken()
    {
        assertEquals(5, ExpansionTokens.tokenEndAt("&DOM&SEQ", 0));
        assertEquals(-1, ExpansionTokens.tokenEndAt("&DOM&SEQ", 1));
        assertEquals(-1, ExpansionTokens.tokenEndAt("&DOMSEQ", 0));
        assertEquals(-1, ExpansionTokens.tokenEndAt("&&", 0));
        assertEquals(-1, ExpansionTokens.tokenEndAt("", 0));
        assertEquals(-1, ExpansionTokens.tokenEndAt("&", 0));
    }

}
