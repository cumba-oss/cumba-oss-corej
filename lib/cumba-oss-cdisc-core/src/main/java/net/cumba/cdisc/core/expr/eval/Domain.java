package net.cumba.cdisc.core.expr.eval;

/**
 * A rule's <b>evaluation domain</b> — the set of cursors its Check varies over — as
 * {@link DomainScan} infers it from the Check expression alone ({@code PLAN-leaf-scope-domain-
 * inference.md} §3.2). Four values exist: {@code {}} (one broadcast verdict per dataset),
 * {@code {VAR}} (one verdict per variable), {@code {ROW}} (one verdict per row) and
 * {@code {VAR,ROW}} (one verdict per variable × row).
 *
 * <p>
 * This is a <em>derived cache of the inference</em>, never an input to it and never serialised: the
 * engine routes on the cursors the expression demands, not on a declared taxonomy.
 * </p>
 *
 * @param varCursor
 *            whether the Check reads the variable cursor ({@code varname()}, a cursor-form
 *            {@code var_*} accessor, the {@code variable_name} anchor, a per-variable
 *            {@code $}-operation, or {@code value()})
 * @param rowCursor
 *            whether the Check reads the row cursor (a data column, a dotted joined column, a
 *            per-row {@code $}-operation, or {@code value()})
 */
public record Domain(boolean varCursor, boolean rowCursor)
{

    /** {@code {}} — one broadcast verdict per dataset. */
    public static final Domain DATASET = new Domain(false, false);

    /** {@code {VAR}} — one verdict per variable. */
    public static final Domain VARIABLE = new Domain(true, false);

    /** {@code {ROW}} — one verdict per row. */
    public static final Domain ROW = new Domain(false, true);

    /** {@code {VAR,ROW}} — one verdict per (variable, row). */
    public static final Domain CELL = new Domain(true, true);

    /** The set union of two domains (§3.2: the join of the leaves' cursor demands). */
    public Domain join(Domain other)
    {
        return of(varCursor || other.varCursor, rowCursor || other.rowCursor);
    }


    /** The interned instance for a pair of cursor flags. */
    public static Domain of(boolean var, boolean row)
    {
        if (var)
        {
            return row ? CELL : VARIABLE;
        }
        return row ? ROW : DATASET;
    }


    /** Whether this domain demands no cursor at all (one verdict per dataset). */
    public boolean isBroadcast()
    {
        return !varCursor && !rowCursor;
    }


    /** The set notation used in documentation and censuses: {@code {}}, {@code {VAR}}, …. */
    public String label()
    {
        if (varCursor)
        {
            return rowCursor ? "{VAR,ROW}" : "{VAR}";
        }
        return rowCursor ? "{ROW}" : "{}";
    }


    /**
     * Parses a {@link #label()} back into a domain.
     *
     * @throws IllegalArgumentException
     *             on any other spelling
     */
    public static Domain parse(String label)
    {
        return switch (label)
        {
        case "{}" -> DATASET;
        case "{VAR}" -> VARIABLE;
        case "{ROW}" -> ROW;
        case "{VAR,ROW}" -> CELL;
        default -> throw new IllegalArgumentException("not a domain label: " + label);
        };
    }
}
