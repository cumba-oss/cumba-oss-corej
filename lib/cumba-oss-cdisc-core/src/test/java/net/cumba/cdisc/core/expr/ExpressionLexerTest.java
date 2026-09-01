package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

}
