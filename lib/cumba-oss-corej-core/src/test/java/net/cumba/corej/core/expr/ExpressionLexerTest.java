package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExpressionLexerTest
{

    private static List<Token> lex(String s)
    {
        return ExpressionLexer.tokenize(s);
    }


    private static TokenType[] types(String s)
    {
        List<Token> t = lex(s);
        TokenType[] out = new TokenType[t.size()];
        for (int i = 0; i < t.size(); i++)
        {
            out[i] = t.get(i).type();
        }
        return out;
    }


    @Test
    void simpleEquality()
    {
        List<Token> t = lex("DTHFL == \"Y\"");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals("DTHFL", t.get(0).text());
        assertEquals(TokenType.EQEQ, t.get(1).type());
        assertEquals(TokenType.STRING, t.get(2).type());
        assertEquals("Y", t.get(2).text());
        assertEquals(TokenType.EOF, t.get(3).type());
    }


    @Test
    void allComparisonAndLogicalOperators()
    {
        assertArrayEquals(new TokenType[]
        {
                TokenType.GE, TokenType.LE, TokenType.NEQ, TokenType.EQEQ, TokenType.MATCH,
                TokenType.NMATCH, TokenType.LT, TokenType.GT, TokenType.AND, TokenType.OR,
                TokenType.NOT, TokenType.EOF
        }, types(">= <= != == =~ !~ < > && || !"));
    }


    @Test
    void wordKeywords()
    {
        assertArrayEquals(new TokenType[]
        {
                TokenType.AND, TokenType.OR, TokenType.NOT, TokenType.IN, TokenType.TRUE,
                TokenType.FALSE, TokenType.EOF
        }, types("and or not in true false"));
    }


    /**
     * Runbook W1b KW (owner O21, 2026-10-08): a keyword is read in any letter case — the identifier
     * is lower-cased before the lookup — so {@code AND} / {@code Or} / {@code In} / {@code True}
     * lex as the keyword; a back-quoted {@code `IN`} stays the column it names.
     */
    @Test
    void keywordsAreReadInAnyCase()
    {
        assertArrayEquals(new TokenType[]
        {
                TokenType.AND, TokenType.OR, TokenType.NOT, TokenType.IN, TokenType.TRUE,
                TokenType.FALSE, TokenType.EOF
        }, types("AND OR NOT IN TRUE FALSE"));
        assertArrayEquals(new TokenType[]
        {
                TokenType.AND, TokenType.OR, TokenType.NOT, TokenType.IN, TokenType.TRUE,
                TokenType.FALSE, TokenType.EOF
        }, types("And oR Not iN True fAlSe"));
        for (String keyword : ExpressionLexer.keywords())
        {
            Token upper = ExpressionLexer.tokenize(keyword.toUpperCase(java.util.Locale.ROOT))
                    .get(0);
            assertEquals(ExpressionLexer.tokenize(keyword).get(0).type(), upper.type(),
                    keyword + " upper-cased");
            assertEquals(keyword.toUpperCase(java.util.Locale.ROOT), upper.text(),
                    "the token keeps the spelling as written");
        }
        List<Token> quoted = lex("`IN` == \"x\"");
        assertEquals(TokenType.IDENT, quoted.get(0).type(), "a back-quoted name is a column");
        assertEquals("IN", quoted.get(0).text());
        assertEquals(TokenType.IDENT, lex("INX").get(0).type(), "a longer identifier is not one");
        assertEquals(TokenType.IDENT, lex("$AND").get(0).type());
    }


    @Test
    void numbers()
    {
        // Negative numbers lex in operand position (after a comma/bracket/operator). After an
        // operand, '-' is the arithmetic MINUS operator (see arithmeticAfterOperand).
        List<Token> t = lex("[8, -3, 4.5]");
        assertEquals(TokenType.NUMBER, t.get(1).type());
        assertEquals("8", t.get(1).text());
        assertEquals("-3", t.get(3).text());
        assertEquals("4.5", t.get(5).text());
    }


    @Test
    void regexLiteralStripsDelimitersAndUnescapesSlash()
    {
        List<Token> t = lex("AESTDTC =~ /^\\d{4}\\/\\d{2}/");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals(TokenType.MATCH, t.get(1).type());
        assertEquals(TokenType.REGEX, t.get(2).type());
        // \d preserved; the escaped delimiter \/ becomes a literal /.
        assertEquals("^\\d{4}/\\d{2}", t.get(2).text());
    }


    @Test
    void stringEscapes()
    {
        assertEquals("a\"b\\c", lex("\"a\\\"b\\\\c\"").get(0).text());
    }


    @Test
    void listAndCallPunctuation()
    {
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.IN, TokenType.LBRACKET, TokenType.STRING,
                TokenType.COMMA, TokenType.STRING, TokenType.RBRACKET, TokenType.EOF
        }, types("DTHFL in [\"Y\", \"\"]"));
    }


    @Test
    void keywordArgumentEquals()
    {
        List<Token> t = lex("present_on_multiple_rows(USUBJID, within=[DOMAIN])");
        assertEquals(TokenType.IDENT, t.get(0).type()); // function name
        assertEquals(TokenType.LPAREN, t.get(1).type());
        assertEquals(TokenType.IDENT, t.get(2).type());
        assertEquals(TokenType.COMMA, t.get(3).type());
        assertEquals(TokenType.IDENT, t.get(4).type());
        assertEquals(TokenType.EQ, t.get(5).type());
        assertEquals(TokenType.LBRACKET, t.get(6).type());
    }


    @Test
    void identifierFlavours()
    {
        assertEquals("DM.DTHDTC", lex("DM.DTHDTC").get(0).text());
        assertEquals("--STDTC", lex("--STDTC").get(0).text());
        assertEquals("*DT", lex("*DT").get(0).text());
        assertEquals("$tv_visitnum", lex("$tv_visitnum").get(0).text());
        assertEquals("RELREC.**TERM", lex("RELREC.**TERM").get(0).text());
    }


    @Test
    void unterminatedStringThrows()
    {
        assertThrows(ExpressionException.class, () -> lex("\"abc"));
    }


    @Test
    void unterminatedRegexThrows()
    {
        assertThrows(ExpressionException.class, () -> lex("/abc"));
    }


    @Test
    void loneDashThrows()
    {
        // A '-' in operand position that forms neither '--' nor a negative number is still invalid.
        // (After an operand, '-' is the MINUS operator — see arithmeticAfterOperand.)
        assertThrows(ExpressionException.class, () -> lex("- B"));
    }


    @Test
    void arithmeticAfterOperand()
    {
        List<Token> t = lex("AVAL / BASE - X * Y + Z");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals(TokenType.SLASH, t.get(1).type());
        assertEquals(TokenType.MINUS, t.get(3).type());
        assertEquals(TokenType.STAR, t.get(5).type());
        assertEquals(TokenType.PLUS, t.get(7).type());
    }


    @Test
    void strayCharacterThrows()
    {
        assertThrows(ExpressionException.class, () -> lex("A @ B"));
    }


    @Test
    void singleAmpersandThrows()
    {
        assertThrows(ExpressionException.class, () -> lex("A & B"));
    }


    @Test
    void nullSourceThrows()
    {
        assertThrows(ExpressionException.class, () -> ExpressionLexer.tokenize(null));
    }

    // ------------------------------------------------------------------
    // PLAN-expansion-token-delimiters — a bare `&NAME&` token inside an identifier
    // ------------------------------------------------------------------


    @Test
    void aBareExpansionTokenIsOneIdentifier()
    {
        assertEquals("&DOM&", lex("&DOM&").get(0).text());
        assertEquals(TokenType.IDENT, lex("&DOM&").get(0).type());
        assertEquals("&DOM&.&DOM&SEQ", lex("&DOM&.&DOM&SEQ").get(0).text());
        assertEquals("ADSL.&VAR&", lex("ADSL.&VAR&").get(0).text());
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.EOF
        }, types("&DOM&.&DOM&SEQ"));
        // `&A&B&C&` reads `&A&`, `B`, `&C&` — one identifier, never a token `&B&`.
        assertEquals("&A&B&C&", lex("&A&B&C&").get(0).text());
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.EOF
        }, types("&A&B&C&"));
    }


    @Test
    void theBareTokenLexesLikeItsBacktickForm()
    {
        List<Token> bare = lex("var_exists(&DOM&.&DOM&SEQ) and empty(&DOM&.&DOM&SEQ)");
        List<Token> quoted = lex("var_exists(`&DOM&.&DOM&SEQ`) and empty(`&DOM&.&DOM&SEQ`)");
        assertEquals(bare.size(), quoted.size());
        for (int i = 0; i < bare.size(); i++)
        {
            assertEquals(bare.get(i).type(), quoted.get(i).type(), "token " + i);
            assertEquals(bare.get(i).text(), quoted.get(i).text(), "token " + i);
        }
    }


    @Test
    void theAndOperatorSurvivesNextToTokens()
    {
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.AND, TokenType.IDENT, TokenType.EOF
        }, types("A&&B"));
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.AND, TokenType.IDENT, TokenType.EOF
        }, types("&A& && &B&"));
        assertEquals("&A&", lex("&A& && &B&").get(0).text());
        assertEquals("&B&", lex("&A& && &B&").get(2).text());
    }


    @Test
    void anUnterminatedTokenThrowsNamingTheMissingDelimiter()
    {
        ExpressionException ex = assertThrows(ExpressionException.class, () -> lex("&DOM"));
        assertTrue(ex.getMessage().contains("unterminated expansion token '&DOM'"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("&NAME&"), ex.getMessage());
        ExpressionException inName = assertThrows(ExpressionException.class,
                () -> lex("var_exists(&DOM.&DOMSEQ)"));
        assertTrue(inName.getMessage().contains("'&DOM'"), inName.getMessage());
    }


    @Test
    void aLowerCaseTokenNameThrows()
    {
        ExpressionException ex = assertThrows(ExpressionException.class, () -> lex("&dom&"));
        assertTrue(ex.getMessage().contains("upper case"), ex.getMessage());
        assertThrows(ExpressionException.class, () -> lex("&1&"));
        assertThrows(ExpressionException.class, () -> lex("&V_1&"));
    }


    @Test
    void aTokenAdjacentToAnAmpersandThrows()
    {
        for (String text : List.of("&A&&B&", "&A&&&COL", "COL&&&A&", ")&&&A&"))
        {
            ExpressionException ex = assertThrows(ExpressionException.class, () -> lex(text), text);
            assertTrue(ex.getMessage().contains("separate an expansion token from '&' / '&&'"),
                    text + ": " + ex.getMessage());
        }
        // No token behind the operators: the lexer reads two operators and the PARSER rejects
        // them — an operator-shaped error, not an adjacency one.
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.AND, TokenType.AND, TokenType.IDENT, TokenType.EOF
        }, types("X&&&&Y"));
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> CheckExpressionParser.parse("X&&&&Y"));
        assertFalse(ex.getMessage().contains("separate"), ex.getMessage());
    }

    // ---- the substitution operator `~` (PLAN-engine-gap-string-extract N1, runbook W1b SX) ----


    @Test
    void tildeIsTheSubstitutionOperatorAndItsLiteralFollowsIt()
    {
        List<Token> t = lex("AESTDTC ~ /(?i)^(.*)DTC$/$1/");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals(TokenType.SUBST, t.get(1).type());
        assertEquals("~", t.get(1).text());
        assertEquals(TokenType.SUBSTITUTION, t.get(2).type());
        // the pattern reaches Pattern.compile unchanged ((?i) included), the replacement verbatim
        assertEquals("(?i)^(.*)DTC$", t.get(2).text());
        assertEquals("$1", t.get(2).replacement());
        assertEquals(TokenType.EOF, t.get(3).type());
        // a token that is not a substitution literal carries no replacement
        assertNull(t.get(0).replacement());
    }


    @Test
    void theSubstitutionLiteralUsesTheRegexLiteralsEscapes()
    {
        // \/ is the delimiter in both parts, \\ a backslash, every other backslash sequence stays
        List<Token> t = lex("X ~ /^\\d+\\/\\d+$/a\\/b\\$1/");
        assertEquals(TokenType.SUBSTITUTION, t.get(2).type());
        assertEquals("^\\d+/\\d+$", t.get(2).text());
        assertEquals("a/b\\$1", t.get(2).replacement());
        assertEquals("", lex("X ~ /a//").get(2).replacement(), "an empty replacement deletes");
    }


    @Test
    void theSubstitutionLiteralCountsAsAnOperand()
    {
        // after the literal, `/` and `-` are arithmetic again, not a regex or a negative number
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.SUBST, TokenType.SUBSTITUTION, TokenType.SLASH,
                TokenType.NUMBER, TokenType.MINUS, TokenType.NUMBER, TokenType.EOF
        }, types("X ~ /a/b/ / 2 - 1"));
        // a second `~` chains
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.SUBST, TokenType.SUBSTITUTION, TokenType.SUBST,
                TokenType.SUBSTITUTION, TokenType.EOF
        }, types("X ~ /a/b/ ~ /b/c/"));
    }


    @Test
    void tildeLeavesTheMatchOperatorsAndDivisionAlone()
    {
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.MATCH, TokenType.REGEX, TokenType.AND, TokenType.IDENT,
                TokenType.NMATCH, TokenType.REGEX, TokenType.EOF
        }, types("A =~ /x/ and B !~ /y/"));
        assertArrayEquals(new TokenType[]
        {
                TokenType.IDENT, TokenType.SLASH, TokenType.IDENT, TokenType.EOF
        }, types("A / B"));
        // `/` only starts a substitution literal directly after `~`: elsewhere a regex literal
        assertEquals(TokenType.REGEX, lex("X =~ /a/b/").get(2).type());
    }


    @Test
    void anUnterminatedSubstitutionLiteralThrows()
    {
        ExpressionException noReplacement = assertThrows(ExpressionException.class,
                () -> lex("X ~ /a/"));
        assertTrue(noReplacement.getMessage().contains("Unterminated substitution literal"),
                noReplacement.getMessage());
        assertThrows(ExpressionException.class, () -> lex("X ~ /a/b"));
        assertThrows(ExpressionException.class, () -> lex("X ~ /a"));
    }


    /**
     * The language-surface rosters ({@code PLAN-engine-language-runbook} §6.0 G): every spelling
     * {@link ExpressionLexer#operators()} lists tokenises as its own non-identifier token, and
     * every {@link ExpressionLexer#keywords()} entry as a keyword token — a roster entry nothing
     * reads would list a surface that does not exist.
     */
    @Test
    void theOperatorAndKeywordRostersAreWhatTheLexerReads()
    {
        for (String op : ExpressionLexer.operators())
        {
            String source = "!".equals(op) ? "! B" : "A " + op + " B";
            List<Token> tokens = ExpressionLexer.tokenize(source);
            Token operator = tokens.get("!".equals(op) ? 0 : 1);
            assertEquals(op, operator.text(), source);
            assertTrue(
                    operator.type() != TokenType.IDENT && operator.type() != TokenType.STRING
                            && operator.type() != TokenType.NUMBER,
                    source + " -> " + operator.type());
        }
        assertEquals(17, ExpressionLexer.operators().size());
        for (String keyword : ExpressionLexer.keywords())
        {
            Token token = ExpressionLexer.tokenize(keyword).get(0);
            assertEquals(keyword, token.text());
            assertTrue(token.type() != TokenType.IDENT, keyword + " -> " + token.type());
        }
        assertEquals(6, ExpressionLexer.keywords().size());
        assertEquals(12, ExpressionLexer.literalForms().size());
    }


    @Test
    void bracesAndColonAreTheSidedKeyLiteralsTokens()
    {
        // runbook W1b RO (coordinator D9): the sided group= member {left: X, right: Y}
        List<Token> t = lex("{left: ACTARMCD, right: ARMCD}");
        assertEquals(TokenType.LBRACE, t.get(0).type());
        assertEquals(TokenType.IDENT, t.get(1).type());
        assertEquals("left", t.get(1).text());
        assertEquals(TokenType.COLON, t.get(2).type());
        assertEquals(TokenType.IDENT, t.get(3).type());
        assertEquals(TokenType.COMMA, t.get(4).type());
        assertEquals(TokenType.IDENT, t.get(5).type());
        assertEquals(TokenType.COLON, t.get(6).type());
        assertEquals(TokenType.IDENT, t.get(7).type());
        assertEquals(TokenType.RBRACE, t.get(8).type());
        assertEquals(TokenType.EOF, t.get(9).type());
        assertTrue(ExpressionLexer.literalForms().contains("{left: X, right: Y}"));
    }

}
