package net.cumba.cdisc.core.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Hand-written scanner that turns an expression-syntax string into a flat {@link Token} stream
 * (always terminated by an {@link TokenType#EOF} token). It is deliberately small: literals (quoted
 * string, number, {@code /regex/}), bare identifiers, the comparison / logical operators, and
 * bracketing punctuation. Bare identifiers are emitted as {@link TokenType#IDENT} (with the
 * reserved words {@code and}/{@code or}/{@code not}/{@code in}/{@code true}/{@code false} promoted
 * to their keyword categories); the parser later decides whether an {@code IDENT} is a function
 * name or an operand, applying {@link OperandClassifier} in the latter case.
 *
 * <p>
 * Malformed input — an unterminated string/regex, a stray character, a lone {@code -} — raises an
 * {@link ExpressionException} carrying the offending offset (fail loudly).
 * </p>
 */
public final class ExpressionLexer
{

    private static final Map<String, TokenType> KEYWORDS = Map.of("and", TokenType.AND, "or",
            TokenType.OR, "not", TokenType.NOT, "in", TokenType.IN, "true", TokenType.TRUE, "false",
            TokenType.FALSE);

    private final String src;

    private int pos;

    /**
     * Type of the previous emitted token, used to disambiguate arithmetic operators from operands.
     */
    private @Nullable TokenType prev;

    private ExpressionLexer(String src)
    {
        this.src = src;
    }


    public static List<Token> tokenize(String source)
    {
        if (source == null)
        {
            throw new ExpressionException("Null expression");
        }
        return new ExpressionLexer(source).scan();
    }


    private List<Token> scan()
    {
        List<Token> tokens = new ArrayList<>();
        while (true)
        {
            skipWhitespace();
            if (pos >= src.length())
            {
                tokens.add(new Token(TokenType.EOF, "", pos));
                return tokens;
            }
            Token t = nextToken();
            tokens.add(t);
            prev = t.type();
        }
    }


    /**
     * Whether the previous token ends an operand, so that a following {@code + - * /} is an
     * arithmetic operator rather than a wildcard-column marker, {@code /regex/} delimiter, or
     * negative-number sign.
     */
    private boolean afterOperand()
    {
        return prev == TokenType.IDENT || prev == TokenType.NUMBER || prev == TokenType.STRING
                || prev == TokenType.RPAREN || prev == TokenType.RBRACKET;
    }


    private void skipWhitespace()
    {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos)))
        {
            pos++;
        }
    }


    private Token nextToken()
    {
        int start = pos;
        char c = src.charAt(pos);

        // Contextual arithmetic: after an operand, '+ - * /' are arithmetic operators; otherwise
        // they keep their operand-position meaning ('*'/'--' wildcard, '/regex/', negative number).
        if (afterOperand())
        {
            Token arith = switch (c)
            {
            case '+' -> single(TokenType.PLUS, "+", start);
            case '-' -> single(TokenType.MINUS, "-", start);
            case '*' -> single(TokenType.STAR, "*", start);
            case '/' -> single(TokenType.SLASH, "/", start);
            default -> null;
            };
            if (arith != null)
            {
                return arith;
            }
        }

        Token punctuation = switch (c)
        {
        case '(' -> single(TokenType.LPAREN, "(", start);
        case ')' -> single(TokenType.RPAREN, ")", start);
        case '[' -> single(TokenType.LBRACKET, "[", start);
        case ']' -> single(TokenType.RBRACKET, "]", start);
        case ',' -> single(TokenType.COMMA, ",", start);
        case '"' -> scanString();
        case '/' -> scanRegex();
        case '=' -> scanEquals(start);
        case '!' -> scanBang(start);
        case '<' -> scanRelational(start, TokenType.LE, TokenType.LT);
        case '>' -> scanRelational(start, TokenType.GE, TokenType.GT);
        case '&' -> scanDoubled(start, '&', TokenType.AND, "&&");
        case '|' -> scanDoubled(start, '|', TokenType.OR, "||");
        case '`' -> scanQuotedRef();
        default -> null;
        };
        if (punctuation != null)
        {
            return punctuation;
        }

        if (c == '-')
        {
            // "--" begins a wildcard identifier; "-<digit>" begins a negative number.
            if (peek(1) == '-')
            {
                return scanIdentifier();
            }
            if (Character.isDigit(peek(1)))
            {
                return scanNumber();
            }
            throw new ExpressionException("Unexpected '-'", start);
        }
        if (Character.isDigit(c))
        {
            return scanNumber();
        }
        if (isIdentStart(c))
        {
            return scanIdentifier();
        }
        throw new ExpressionException("Unexpected character '" + c + "'", start);
    }


    private Token single(TokenType type, String lexeme, int start)
    {
        pos++;
        return new Token(type, lexeme, start);
    }


    private Token scanString()
    {
        int start = pos;
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length())
        {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length())
            {
                char n = src.charAt(pos + 1);
                if (n == '"' || n == '\\')
                {
                    sb.append(n);
                    pos += 2;
                    continue;
                }
            }
            if (c == '"')
            {
                pos++; // closing quote
                return new Token(TokenType.STRING, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw new ExpressionException("Unterminated string literal", start);
    }


    private Token scanRegex()
    {
        int start = pos;
        pos++; // opening slash
        StringBuilder sb = new StringBuilder();
        while (pos < src.length())
        {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length())
            {
                char n = src.charAt(pos + 1);
                // The printer escapes '\' -> \\ and the delimiter '/' -> \/; reverse both. Any
                // other
                // backslash sequence stays verbatim so the pattern reaches Pattern.compile
                // unchanged.
                if (n == '/')
                {
                    sb.append('/');
                }
                else if (n == '\\')
                {
                    sb.append('\\');
                }
                else
                {
                    sb.append('\\').append(n);
                }
                pos += 2;
                continue;
            }
            if (c == '/')
            {
                pos++; // closing slash
                return new Token(TokenType.REGEX, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw new ExpressionException("Unterminated regex literal", start);
    }


    private Token scanEquals(int start)
    {
        if (peek(1) == '=')
        {
            pos += 2;
            return new Token(TokenType.EQEQ, "==", start);
        }
        if (peek(1) == '~')
        {
            pos += 2;
            return new Token(TokenType.MATCH, "=~", start);
        }
        pos++;
        return new Token(TokenType.EQ, "=", start);
    }


    private Token scanBang(int start)
    {
        if (peek(1) == '=')
        {
            pos += 2;
            return new Token(TokenType.NEQ, "!=", start);
        }
        if (peek(1) == '~')
        {
            pos += 2;
            return new Token(TokenType.NMATCH, "!~", start);
        }
        pos++;
        return new Token(TokenType.NOT, "!", start);
    }


    private Token scanRelational(int start, TokenType withEq, TokenType bare)
    {
        if (peek(1) == '=')
        {
            pos += 2;
            return new Token(withEq, src.substring(start, pos), start);
        }
        pos++;
        return new Token(bare, src.substring(start, start + 1), start);
    }


    private Token scanDoubled(int start, char ch, TokenType type, String lexeme)
    {
        if (peek(1) == ch)
        {
            pos += 2;
            return new Token(type, lexeme, start);
        }
        throw new ExpressionException("Expected '" + lexeme + "'", start);
    }


    private Token scanNumber()
    {
        int start = pos;
        if (src.charAt(pos) == '-')
        {
            pos++;
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos)))
        {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '.' && Character.isDigit(peek(1)))
        {
            pos++; // decimal point
            while (pos < src.length() && Character.isDigit(src.charAt(pos)))
            {
                pos++;
            }
        }
        return new Token(TokenType.NUMBER, src.substring(start, pos), start);
    }


    private Token scanIdentifier()
    {
        int start = pos;
        while (pos < src.length())
        {
            char c = src.charAt(pos);
            // A '${...}' operand-substitution template is consumed whole (its inner ':', '%', ...
            // are not identifier characters but are preserved verbatim in the token text).
            if (c == '$' && peek(1) == '{')
            {
                consumeSubstitution();
                continue;
            }
            if (!isIdentPart(c))
            {
                break;
            }
            pos++;
        }
        String text = src.substring(start, pos);
        TokenType keyword = KEYWORDS.get(text);
        return new Token(keyword != null ? keyword : TokenType.IDENT, text, start);
    }


    private void consumeSubstitution()
    {
        int start = pos;
        pos += 2; // skip "${"
        while (pos < src.length() && src.charAt(pos) != '}')
        {
            pos++;
        }
        if (pos >= src.length())
        {
            throw new ExpressionException("Unterminated '${...}' substitution template", start);
        }
        pos++; // skip closing "}"
    }


    /**
     * Scans a backtick-quoted reference operand, e.g. {@code `PROTOCOL MILESTONE`}, used for
     * column/value references that are not bare identifiers (whitespace or other non-identifier
     * characters). Backslash escapes a literal backtick or backslash. Emitted as an
     * {@link TokenType#IDENT} carrying the unquoted text.
     */
    private Token scanQuotedRef()
    {
        int start = pos;
        pos++; // opening backtick
        StringBuilder sb = new StringBuilder();
        while (pos < src.length())
        {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length())
            {
                char n = src.charAt(pos + 1);
                if (n == '`' || n == '\\')
                {
                    sb.append(n);
                    pos += 2;
                    continue;
                }
            }
            if (c == '`')
            {
                pos++; // closing backtick
                return new Token(TokenType.IDENT, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw new ExpressionException("Unterminated `quoted reference`", start);
    }


    private char peek(int ahead)
    {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }


    private static boolean isIdentStart(char c)
    {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '*';
    }


    private static boolean isIdentPart(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '*' || c == '-'
                || c == '$';
    }

}
