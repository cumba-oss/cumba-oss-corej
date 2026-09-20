package net.cumba.corej.core.expr.typed;

/**
 * Thrown when a {@link Level} would land on the excluded {@code group(K) × cursor=present} cell
 * (D67, spec §1.3). The cell is excluded from the product <b>by construction</b> — DRAFT-1's total
 * order made it computable-and-then-forbidden, which is the shape D67 exists to remove — so the
 * type refuses to represent it, and the stage-A checker converts this throw into the D67 load
 * error.
 */
public class ExcludedLevelCellException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public ExcludedLevelCellException(String message)
    {
        super(message);
    }

}
