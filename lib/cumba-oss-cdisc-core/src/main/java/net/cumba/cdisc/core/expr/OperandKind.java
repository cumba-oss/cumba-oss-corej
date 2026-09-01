package net.cumba.cdisc.core.expr;

/**
 * The five lexically-distinguishable kinds of operand in an expression-syntax {@code Check} leaf.
 * The kind is decided from the token text alone (see {@link OperandClassifier}); it determines how
 * the operand lowers into a {@link net.cumba.cdisc.core.model.CheckConditionLeaf}.
 *
 * <p>
 * Quoted strings, numbers, booleans, and list literals are <em>not</em> represented here — those
 * are literals by lexical type and never go through the bareword classifier.
 * </p>
 */
public enum OperandKind
{

    /** Bare ALL-CAPS column name, e.g. {@code DTHFL}. */
    COLUMN,

    /**
     * Column name carrying a wildcard marker — {@code *}, {@code --}, {@code **}, or an ADaM
     * capture letter ({@code xx}/{@code zz}/{@code y}/{@code w}) — e.g. {@code --STDTC},
     * {@code *DT}, {@code AyIND}. Checked <em>before</em> {@link #BUILTIN} so that an ADaM name
     * with an embedded lowercase capture letter is not mistaken for a built-in.
     */
    WILDCARD_COLUMN,

    /** {@code $}-prefixed reference to an {@code Operations} result, e.g. {@code $tv_visitnum}. */
    OPERATION_REF,

    /** Dotted cross-dataset reference, e.g. {@code DM.DTHDTC}. */
    DOTTED_REF,

    /**
     * Engine-provided metadata/library name from the closed {@link BuiltinRegistry}, e.g.
     * {@code variable_name}, {@code library_variable_role}. Identified syntactically by a
     * lowercase-leading or underscore-containing token.
     */
    BUILTIN

}
