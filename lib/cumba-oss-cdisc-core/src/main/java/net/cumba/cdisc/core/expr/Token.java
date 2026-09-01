package net.cumba.cdisc.core.expr;

/**
 * A single lexical token. {@code text} carries the decoded payload for literals (unquoted string
 * content, the numeral, the regex pattern) and the raw lexeme for identifiers; for punctuation and
 * operators it is the literal lexeme. {@code position} is the 0-based offset of the token's first
 * character in the source expression.
 */
public record Token(TokenType type, String text, int position)
{

}
