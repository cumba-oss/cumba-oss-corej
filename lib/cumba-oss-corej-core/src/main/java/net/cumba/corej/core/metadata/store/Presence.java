package net.cumba.corej.core.metadata.store;

/**
 * The total answer to "does the store hold CT package <i>id</i>?" — every input maps to exactly one
 * of the three constants, and no input throws. The three cases are deliberately distinct because a
 * later phase aborts differently on each (PLAN-define-driven-ct-selection.md §4.4): a well-formed
 * id the store lacks ({@link #ABSENT}) is a seeding gap, while an id that is not even syntactically
 * a CT package id ({@link #MALFORMED}) is a caller bug or corrupted input and is rejected before
 * any lookup happens.
 */
public enum Presence
{
    /** The id is well-formed and the store holds the package. */
    PRESENT,

    /** The id is well-formed but the store does not hold the package. */
    ABSENT,

    /**
     * The id is not a syntactically valid CT package id ({@code null}, blank, or not matching
     * {@code <family>ct-<yyyy-MM-dd>}). No lookup was attempted.
     */
    MALFORMED
}
