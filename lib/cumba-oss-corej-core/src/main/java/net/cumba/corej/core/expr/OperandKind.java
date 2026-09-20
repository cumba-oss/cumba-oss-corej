package net.cumba.corej.core.expr;

/**
 * The five lexically-distinguishable kinds of operand in an expression-syntax {@code Check}. The
 * kind is decided from the token text alone (see {@link OperandClassifier}); it determines how the
 * compiler resolves the operand (column read, {@code $}-operation result, builtin, …).
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
     * The join-match flag of a {@code Match_Datasets} entry — {@code <DATASET>._matched_}, e.g.
     * {@code AE._matched_}: a {@code boolean} record-level column that is {@code true} iff the row
     * found at least one partner in that dataset under the entry's keys (spec §3.3, D88;
     * {@code PLAN-join-match-flag.md}). Recognised <em>before</em> {@link #DOTTED_REF} (whose
     * pattern would not match the lowercase {@code _matched_} tail anyway) and before the
     * {@link #BUILTIN} test (which would otherwise reject the underscore-carrying token as an
     * unknown built-in). The {@code _matched_} suffix is reserved: measured 2026-09-15, zero
     * occurrences in the 3&#8239;940 authored checks and no corpus identifier with a leading or
     * trailing underscore (D88b).
     */
    MATCHED_FLAG,

    /**
     * Engine-provided metadata/library name from the closed {@link BuiltinRegistry}, e.g.
     * {@code variable_name}, {@code library_variable_role}. Identified syntactically by a
     * lowercase-leading or underscore-containing token.
     */
    BUILTIN

}
