package net.cumba.corej.core.expr.typed;

/**
 * The expression level as the <b>product</b> {@code granularity × cursor}
 * ({@code plans/SPEC-typed-expression-engine.md} §1.3, D30/D30a) — a partial order, not a chain.
 * D30's six author-facing names are spellings of cells in this product: study, dataset, group, row,
 * <em>variable metadata</em> = {@code dataset × present}, <em>record × cursor</em> =
 * {@code record × present}.
 *
 * <p>
 * ⛔ {@code group(K) × present} is <b>not a cell</b> (D67): constructing it throws
 * {@link ExcludedLevelCellException}, so the excluded combination is unrepresentable rather than
 * computable-and-then-forbidden. The stage-A checker catches the throw at each combining site and
 * files it as the D67 load error.
 * </p>
 */
public record Level(Granularity granularity, Cursor cursor)
{

    /** Study-level constant — literals, study facts. */
    public static final Level STUDY = new Level(Granularity.Simple.STUDY, Cursor.ABSENT);

    /** Dataset-level fact. */
    public static final Level DATASET = new Level(Granularity.Simple.DATASET, Cursor.ABSENT);

    /** Per-row value. */
    public static final Level RECORD = new Level(Granularity.Simple.RECORD, Cursor.ABSENT);

    /** D30 level 5, "variable metadata": {@code dataset × present}. */
    public static final Level VARIABLE_METADATA = new Level(Granularity.Simple.DATASET,
            Cursor.PRESENT);

    /** D30 level 6, "variable value": {@code record × present}. */
    public static final Level VARIABLE_VALUE = new Level(Granularity.Simple.RECORD, Cursor.PRESENT);

    public Level
    {
        if (granularity instanceof Granularity.Group && cursor == Cursor.PRESENT)
        {
            throw new ExcludedLevelCellException(
                    "the level " + granularity.describe() + " × cursor is not a cell of the level "
                            + "product (D67): a per-variable aggregate over a group is a compile "
                            + "error, not a computable level");
        }
    }


    /**
     * D5's combination: the join (finest) of the two levels, granularity and cursor independently.
     *
     * @throws ExcludedLevelCellException
     *             when the join lands on the excluded {@code group × present} cell
     */
    public Level join(Level other)
    {
        return new Level(Granularity.join(granularity, other.granularity),
                cursor.join(other.cursor));
    }


    /**
     * §1.4 raising: an aggregate raises its operand to the level of the axis it folds — the whole
     * dataset, or {@code group(K)} when {@code group=} names one. The cursor survives the fold ("…
     * or the cursor names one": a per-variable aggregate stays per-variable).
     *
     * @throws ExcludedLevelCellException
     *             when the axis is a group and the operand carries the cursor (D67)
     */
    public Level raise(Granularity axis)
    {
        return new Level(axis, cursor);
    }


    /** A short human-readable spelling for error messages, e.g. {@code dataset × cursor}. */
    public String describe()
    {
        return granularity.describe() + (cursor == Cursor.PRESENT ? " × cursor" : "");
    }

}
