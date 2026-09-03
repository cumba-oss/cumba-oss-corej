package net.cumba.corej.core.exec;

import java.util.Collections;
import java.util.List;

import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Common interface for cross-dataset lookup strategies used by Match_Datasets. Implementations
 * include key-based joins ({@link DatasetLookup}) and relationship-based joins
 * ({@link RelrecExpandedLookup}).
 */
public interface JoinLookup
{

    /**
     * Looks up a column value from the joined dataset for the given row in the primary table.
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up (may include domain prefix)
     * @return the value, or {@code null} if no match or column not found
     */
    @Nullable
    String lookup(IDataTable primaryTable, long row, String columnName);


    /**
     * Looks up every matched child row's value for the given column. Relationship-based lookups
     * (e.g. {@link RelrecExpandedLookup}) return 0 or 1 element; key-based lookups
     * ({@link DatasetLookup}) may return 0..N when the join key is not unique on the child side.
     * <p>
     * Default implementation delegates to {@link #lookup} and wraps the scalar result — non-null
     * values become a singleton list, {@code null} becomes an empty list. Implementations that can
     * return multiple matches per primary row should override.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @return 0..N values (never {@code null}; empty when there is no match)
     */
    default List<String> lookupAll(IDataTable primaryTable, long row, String columnName)
    {
        String v = lookup(primaryTable, row, columnName);
        return v == null ? List.of() : Collections.singletonList(v);
    }


    /**
     * Returns whether the joined column physically exists for the given primary row. Used to
     * distinguish "the column is absent" (omit the output variable, matching Python's merged-frame
     * semantics) from "the column exists but the value is missing" (keep it as a null/empty value).
     * <p>
     * The default returns {@code true} (assume present), preserving the historical behaviour for
     * key-based joins. Relationship lookups whose related domain may lack a wildcard-resolved
     * column (e.g. {@link RelrecExpandedLookup} resolving {@code **TRT} against a parent with no
     * {@code AETRT}) override this.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to test
     * @return {@code true} if the column exists in the joined dataset for this row
     */
    default boolean hasColumn(IDataTable primaryTable, long row, String columnName)
    {
        return true;
    }


    /**
     * Returns the dataset name this lookup was built from.
     */
    String getDatasetName();

}
