package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
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
     * The <b>typed</b> sibling of {@link #lookup}: the matched joined cell as an
     * {@link IDataValue}, keeping the joined column's own type instead of rendering it to text.
     *
     * <p>
     * Step B of {@code PLAN-joined-column-typing}. This is an <b>addition</b>, never a change to
     * {@link #lookup}'s signature, and that is deliberate: a signature change would break all
     * implementations and every call site at once, forcing the report-text, cohort-parity and
     * wildcard-collection consumers to move in the same commit as the behaviour, along with the
     * test files that pin them. Adding beside it lets each value reader migrate on its own, green
     * at every step, and leaves the consumers that legitimately want text untouched.
     * </p>
     *
     * <p>
     * The default wraps {@link #lookup}'s text, so an implementation that has not migrated keeps
     * exactly its current behaviour. {@link DatasetLookup} overrides it to hand back the parent
     * cell directly — which is the whole point, since the text form routes a numeric value through
     * {@code DataValueSupport.getAsDoubleCleaned}'s 12-significant-digit rounding.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @return the typed value, or {@code null} when there is no match or no such column
     */
    default @Nullable IDataValue lookupValue(IDataTable primaryTable, long row, String columnName)
    {
        String s = lookup(primaryTable, row, columnName);
        return s == null ? null : DataValueSupport.getAsDataValue(s, DataValueType.STRING);
    }


    /**
     * The typed sibling of {@link #lookupAll}, with the same 0..N contract.
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @return 0..N typed values (never {@code null}; empty when there is no match)
     */
    default List<IDataValue> lookupAllValues(IDataTable primaryTable, long row, String columnName)
    {
        List<String> raw = lookupAll(primaryTable, row, columnName);
        List<IDataValue> out = new ArrayList<>(raw.size());
        for (String v : raw)
        {
            out.add(DataValueSupport.getAsDataValue(v, DataValueType.STRING));
        }
        return out;
    }


    /**
     * The joined column's <b>declared</b> type, or {@link DataValueType#MISSING} — meaning
     * <em>unknown</em> — when this lookup cannot say.
     *
     * <p>
     * Decision <b>D1</b>. A dotted reference needs the foreign column's type to publish it, and the
     * obvious route — resolving the foreign table through {@code SplitDomainResolution
     * .resolveTableOrThrow} — was rejected: that method <b>throws</b> on a split domain whose
     * members cannot be unioned, which would convert a silent {@code null} into a rule ERROR on
     * shipped data. It is also unnecessary. The join map is already built through that same
     * resolver, so a dataset reachable here has already resolved successfully; and the lookup
     * already holds the foreign metadata it needs.
     * </p>
     *
     * ⛔⛔ <b>The default is {@link DataValueType#MISSING}, which means "unknown" — NOT
     * {@code STRING}.</b> This distinction is load-bearing and was got wrong once: a default of
     * {@code STRING} makes an <em>unknown</em> type indistinguishable from a <em>character</em>
     * one, and {@code ColumnTypeGate} then errors a perfectly good numeric comparison with "DM.AGE
     * is declared Char". {@code MISSING} maps to {@code null} in {@code ColumnTypeGate
     * .kindOf}, so an unknown type is simply <b>not gated</b> — the same disposition an absent
     * column has (decision D2).
     *
     * @param columnName
     *            the joined column
     * @return the declared type, or {@code MISSING} when unknown or the column is absent
     */
    default DataValueType declaredTypeOf(String columnName)
    {
        return DataValueType.MISSING;
    }


    /**
     * Returns the dataset name this lookup was built from.
     */
    String getDatasetName();

}
