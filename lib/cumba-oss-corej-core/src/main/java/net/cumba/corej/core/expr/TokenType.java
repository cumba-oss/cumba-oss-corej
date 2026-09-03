package net.cumba.corej.core.expr;

/**
 * Lexical token categories produced by {@link ExpressionLexer}. Keyword categories
 * ({@link #AND}/{@link #OR}/{@link #NOT}/{@link #IN}/{@link #TRUE}/{@link #FALSE}) are recognised
 * from otherwise-identifier text; everything else is punctuation, an operator, a literal, or a bare
 * {@link #IDENT} (column / wildcard / {@code $}-ref / dotted / built-in / function name —
 * disambiguated by the parser).
 */
public enum TokenType
{

    /** Quoted string literal; {@code text} holds the unescaped content. */
    STRING,
    /** Numeric literal; {@code text} holds the numeral (possibly negative, possibly decimal). */
    NUMBER,
    /** Regex literal {@code /pat/}; {@code text} holds the pattern without the delimiters. */
    REGEX,
    /**
     * Bare identifier (column, wildcard, {@code $}-ref, dotted ref, built-in, or function name).
     */
    IDENT,

    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    COMMA,

    /** Single {@code =} — keyword-argument separator (e.g. {@code within=[...]}). */
    EQ,

    EQEQ,
    NEQ,
    LT,
    GT,
    LE,
    GE,
    /** {@code =~} regex match. */
    MATCH,
    /** {@code !~} regex non-match. */
    NMATCH,

    /** Arithmetic {@code +} (only after an operand; otherwise unexpected). */
    PLUS,
    /**
     * Arithmetic {@code -} (only after an operand; otherwise negative-number / {@code --}
     * wildcard).
     */
    MINUS,
    /** Arithmetic {@code *} (only after an operand; otherwise a {@code *} wildcard column). */
    STAR,
    /** Arithmetic {@code /} (only after an operand; otherwise a {@code /regex/} literal). */
    SLASH,

    AND,
    OR,
    NOT,
    IN,
    TRUE,
    FALSE,

    EOF

}
