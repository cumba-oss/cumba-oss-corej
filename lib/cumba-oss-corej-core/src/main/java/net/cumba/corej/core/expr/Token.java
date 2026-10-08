package net.cumba.corej.core.expr;

import org.jspecify.annotations.Nullable;

/**
 * A single lexical token. {@code text} carries the decoded payload for literals (unquoted string
 * content, the numeral, the regex pattern — for a {@link TokenType#SUBSTITUTION} literal its
 * pattern, with the decoded replacement in {@code replacement}) and the raw lexeme for identifiers;
 * for punctuation and operators it is the literal lexeme. {@code position} is the 0-based offset of
 * the token's first character in the source expression. {@code replacement} is {@code null} for
 * every token but a substitution literal.
 */
public record Token(TokenType type, String text, int position, @Nullable String replacement)
{

    /** A token without a replacement part — every token but a substitution literal. */
    public Token(TokenType type, String text, int position)
    {
        this(type, text, position, null);
    }
}
