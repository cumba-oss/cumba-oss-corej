package net.cumba.corej.core.expr;

import java.util.regex.Pattern;

/**
 * Classifies a bare (unquoted) expression operand token into its {@link OperandKind}. This is the
 * text-driven core of the 7-step classifier from {@code plans/PLAN-expression-rule-format.md}
 * (steps 1–4 plus the column fallback); steps 5–7 are operator-context / fixture rules used only by
 * the old→new converter, not by parsing fresh expression text where quoting already separates
 * literal from reference.
 *
 * <p>
 * Order is significant — <strong>wildcard precedes built-in</strong> so that an ADaM template name
 * such as {@code AyIND} (which contains a lowercase capture letter {@code y}) is recognised as a
 * wildcard column rather than misclassified as a built-in.
 * </p>
 */
public final class OperandClassifier
{

    /** Plain dotted cross-dataset reference {@code DOMAIN.COL} (no wildcard markers). */
    private static final Pattern DOTTED = Pattern.compile("^[A-Z][A-Z0-9]*\\.[A-Z][A-Z0-9_]*$");

    /**
     * ADaM capture-letter wildcard embedded in an otherwise upper-case name, e.g. {@code AyIND}.
     */
    private static final Pattern ADAM_WILDCARD = Pattern.compile("[A-Z](xx|zz|y|w)[A-Z0-9]");

    private OperandClassifier()
    {
    }


    /**
     * Classifies a bareword operand token into its {@link OperandKind}.
     *
     * @param token
     *            the raw bareword operand (never quoted)
     * @param position
     *            0-based source offset for error reporting, or {@code -1}
     * @return the operand kind
     * @throws ExpressionException
     *             if the token looks like a built-in (lowercase-leading or underscore-containing)
     *             but is not in the closed {@link BuiltinRegistry}
     */
    public static OperandKind classify(String token, int position)
    {
        if (token == null || token.isEmpty())
        {
            throw new ExpressionException("Empty operand", position);
        }
        // 1. Wildcard column (must precede the built-in test).
        if (isWildcard(token))
        {
            return OperandKind.WILDCARD_COLUMN;
        }
        // 2. Operation result reference.
        if (token.charAt(0) == '$')
        {
            return OperandKind.OPERATION_REF;
        }
        // 3. Plain dotted cross-dataset reference.
        if (DOTTED.matcher(token).matches())
        {
            return OperandKind.DOTTED_REF;
        }
        // 4. Built-in: lowercase-leading or underscore-containing -> must be registered.
        if (looksBuiltin(token))
        {
            if (BuiltinRegistry.isBuiltin(token))
            {
                return OperandKind.BUILTIN;
            }
            throw new ExpressionException("Unknown built-in reference: '" + token
                    + "' (a lowercase or underscore-containing operand must be a registered "
                    + "built-in; column names are upper-case)", position);
        }
        // 5. Otherwise an ordinary column reference.
        return OperandKind.COLUMN;
    }


    private static boolean isWildcard(String token)
    {
        // '**' contains '*', so the '*' check covers both. '--' is the SDTM prefix wildcard. A
        // '${...}' operand-substitution template makes the name a non-plain-column reference too:
        // it is preserved verbatim for the converter round-trip but the native evaluator declines
        // it (like the other wildcard kinds), so it must not be classified as a plain COLUMN.
        return token.indexOf('*') >= 0 || token.contains("--") || token.contains("${")
                || ADAM_WILDCARD.matcher(token).find();
    }


    private static boolean looksBuiltin(String token)
    {
        char c = token.charAt(0);
        return (c >= 'a' && c <= 'z') || token.indexOf('_') >= 0;
    }

}
