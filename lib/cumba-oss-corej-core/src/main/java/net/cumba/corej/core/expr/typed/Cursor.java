package net.cumba.corej.core.expr.typed;

/**
 * The cursor half of the level product ({@code plans/SPEC-typed-expression-engine.md} §1.3, D30a):
 * whether an expression reads the per-variable cursor. {@link #PRESENT} is finer than
 * {@link #ABSENT} at the same granularity.
 */
public enum Cursor
{

    ABSENT, PRESENT;

    /** The finer of the two cursors (D5's combination, cursor axis). */
    public Cursor join(Cursor other)
    {
        return this == PRESENT || other == PRESENT ? PRESENT : ABSENT;
    }

}
