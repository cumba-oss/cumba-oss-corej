package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lombok.CustomLog;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.view.HashLookup;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Pre-built {@code (standard-keys…, IDVARVAL)} → parent-row index for a single {@code (parent,
 * IDVAR)} combination. Used by {@link ChildMatchPreMerger} to dispatch each primary row to its
 * matched parent row in O(1) once the index is built.
 * <p>
 * The <em>standard keys</em> are the rule's declared {@code Match_Datasets} keys minus
 * {@code IDVAR}/{@code IDVARVAL} (e.g. {@code [USUBJID]} for the shipped SUPP/CO/RELREC rules) —
 * derived from the rule, <strong>not</strong> hard-coded. This mirrors the Python reference engine
 * (<code>cdisc-rules-engine</code> {@code dataset_preprocessor._merge_with_idvar_logic}:
 * {@code standard_keys = [k for k in match_keys if k not in ("IDVAR","IDVARVAL")]}), which keys the
 * equi-join on exactly those columns and <strong>never</strong> adds {@code STUDYID} unless it is
 * declared.
 * </p>
 * <p>
 * Each standard-key column and the IDVAR-named column are pre-stringified at build time (missing
 * cell → {@code null}); the same coercions apply on the probe side, so missing/empty cases remain
 * symmetric across build and probe (E16, E17). Parent rows where the IDVAR-named cell or any
 * standard-key cell is missing are NOT indexed (they could not be an exact-hash match target); they
 * remain reachable through {@link #scanFallback} for primary rows that drop a key (per-key null
 * semantics — see below).
 * </p>
 * <p>
 * <b>Per-key null semantics (Python parity).</b> The probe side ({@link ChildMatchPreMerger}) uses
 * the hash fast-path only when <em>every</em> standard-key value is present on the primary row.
 * When a standard-key value is missing, that key is dropped for the row (Python applies a key only
 * {@code if pd.notna(child_value)}) and {@link #scanFallback} performs a linear scan applying only
 * the present keys plus the IDVAR match.
 * </p>
 * <p>
 * Instances are immutable after {@link #build} returns and safe to share across threads; the cache
 * in {@link JoinCache.SharedIndexCache} relies on this.
 * </p>
 */
@CustomLog
final class ChildMatchIndex
{

    /** Open-addressed hash → parent row id; populated by {@link #build}. */
    final HashLookup lookup;

    /**
     * Pre-stringified standard-key columns on the parent, in declared order — {@code keyStr[k][r]}
     * is the {@code k}-th standard key for parent row {@code r}. Missing cell becomes {@code null};
     * rows with any {@code null} key slot are not indexed (but are still scanned by
     * {@link #scanFallback}).
     */
    final @Nullable String[][] keyStr;

    /** Pre-stringified IDVAR-named column on the parent — missing cell becomes {@code null}. */
    final @Nullable String[] joinValueStr;

    /** The parent table; carried for downstream column access. */
    final IDataTable parent;

    /**
     * Whether the parent {@code IDVAR}-named column is numeric — drives the type-gated coercion in
     * {@link #normalizeJoinToken}. The child {@code IDVARVAL} is coerced with this same flag at
     * probe time so both sides of the join are byte-consistent (J5 / Python parity).
     */
    final boolean parentIdvarNumeric;

    private ChildMatchIndex(HashLookup aLookup, @Nullable String[][] aKeyStr,
            @Nullable String[] aJoinValueStr, IDataTable aParent, boolean aParentIdvarNumeric)
    {
        lookup = aLookup;
        keyStr = aKeyStr;
        joinValueStr = aJoinValueStr;
        parent = aParent;
        parentIdvarNumeric = aParentIdvarNumeric;
    }


    /**
     * Build a child-match index for the given parent, declared standard keys and IDVAR column name.
     * Returns {@code null} if the parent lacks the named IDVAR column or lacks any declared
     * standard-key column — the caller treats this as "no match possible from any primary row using
     * this {@code (parent, standardKeys, idvarCol)} combination".
     * <p>
     * <b>Divergence note (non-conformant data only):</b> Python drops a standard key whose column
     * is absent on the parent and still merges on the rest; we instead return {@code null} (no
     * merge), preserving the original engine's "{@code USUBJID} absent ⇒ no index" contract. For
     * conformant SDTM the standard keys (e.g. {@code USUBJID}) are always present on the parent, so
     * the two agree.
     * </p>
     * <p>
     * Caller must ensure {@code parent.getRowCount() <= Integer.MAX_VALUE} (HashLookup row indices
     * are {@code int}); {@link Math#toIntExact} throws on overflow as a defensive belt-and-braces
     * check.
     * </p>
     */
    static @Nullable ChildMatchIndex build(IDataTable aParent, List<String> aStandardKeyCols,
            String aIdvarCol)
    {
        DataTableMeta pm = aParent.getMetaData();
        int idvarIdx = pm.getColumnIndex(aIdvarCol);
        if (idvarIdx < 0)
        {
            return null;
        }
        int nKeys = aStandardKeyCols.size();
        IDataTableColumn[] keyCols = new IDataTableColumn[nKeys];
        for (int k = 0; k < nKeys; k++)
        {
            int ci = pm.getColumnIndex(aStandardKeyCols.get(k));
            if (ci < 0)
            {
                return null;
            }
            keyCols[k] = aParent.getColumn(ci);
        }

        int rowCount = Math.toIntExact(aParent.getRowCount());
        HashLookup lookup = new HashLookup(Math.max(1, rowCount), 0.75f);

        @Nullable
        String[][] keyStr = newKeyGrid(nKeys, rowCount);
        @Nullable
        String[] joinValueStr = new String[rowCount];
        IDataTableColumn idvarColumn = aParent.getColumn(idvarIdx);
        DataValueType idvarType = pm.getColumn(idvarIdx).getType();
        boolean parentIdvarNumeric = idvarType == DataValueType.LONG
                || idvarType == DataValueType.DOUBLE;
        for (int r = 0; r < rowCount; r++)
        {
            for (int k = 0; k < nKeys; k++)
            {
                putKeyCell(keyStr, k, r, stringOrNull(keyCols[k], r));
            }
            joinValueStr[r] = normalizeJoinToken(stringOrNull(idvarColumn, r), parentIdvarNumeric);
        }

        SelfMatcher selfMatcher = new SelfMatcher(keyStr, joinValueStr);
        int dupes = 0;
        for (int r = 0; r < rowCount; r++)
        {
            // Skip rows where the IDVAR cell or any standard-key cell is missing — they could never
            // be an exact-hash match target. They remain reachable via scanFallback.
            if (joinValueStr[r] == null || anyKeyNull(keyStr, r))
            {
                continue;
            }
            int h = childMatchHash(slotsAt(keyStr, r), joinValueStr[r]);
            if (lookup.get(r, h, selfMatcher) != -1)
            {
                // E11: first-wins per CDISC convention — skip the duplicate.
                dupes++;
                continue;
            }
            lookup.put(h, r);
        }
        if (dupes > 0)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Parent dataset ''{0}'' has {1} duplicate-key row(s) for IDVAR=''{2}''; "
                            + "first-wins per CDISC convention",
                    pm.getName(), dupes, aIdvarCol);
        }
        return new ChildMatchIndex(lookup, keyStr, joinValueStr, aParent, parentIdvarNumeric);
    }


    /**
     * Linear-scan fallback for a primary row that has at least one missing standard-key value (so
     * the hash fast-path cannot serve it). Applies an equality filter for only the present standard
     * keys ({@code aPrimaryKeyVals[k] != null}) plus the IDVAR-named match, and returns the first
     * matching parent row (first-wins), or {@code -1} if none. Mirrors Python
     * {@code _filter_parents_by_standard_keys} (a key with a missing child value is dropped) +
     * {@code _find_idvar_match_in_candidates}.
     *
     * @param aPrimaryKeyVals
     *            stringified primary standard-key values, aligned to the index's standard keys; a
     *            {@code null} slot means "drop this key for this row"
     * @param aPrimaryIdvarval
     *            stringified primary {@code IDVARVAL} (non-null; callers skip IDVARVAL-missing
     *            rows)
     */
    int scanFallback(@Nullable String[] aPrimaryKeyVals, String aPrimaryIdvarval)
    {
        int rowCount = joinValueStr.length;
        for (int r = 0; r < rowCount; r++)
        {
            if (joinValueStr[r] == null || !joinValueStr[r].equals(aPrimaryIdvarval))
            {
                continue;
            }
            boolean ok = true;
            for (int k = 0; k < keyStr.length; k++)
            {
                String primaryVal = aPrimaryKeyVals[k];
                if (primaryVal == null)
                {
                    continue; // key dropped for this row (Python pd.notna(child_value))
                }
                String parentVal = keyStr[k][r];
                if (parentVal == null || !parentVal.equals(primaryVal))
                {
                    ok = false;
                    break;
                }
            }
            if (ok)
            {
                return r;
            }
        }
        return -1;
    }


    /**
     * Allocates a key grid whose cells may be {@code null}, and writes one such cell.
     *
     * <p>
     * NullAway 0.14 cannot fully model a multi-dimensional array with nullable elements. Two things
     * it rejects, whichever of the four legal type-use annotation positions is chosen (measured
     * against 0.14.1):
     * </p>
     * <ul>
     * <li>the allocation — {@code new @Nullable String[k][r]} is typed one dimension short, so it
     * needs the suppression below;</li>
     * <li>a write through a <em>local</em> of grid type — which is why the write is routed through
     * {@link #putKeyCell}, whose grid is a <em>parameter</em>: NullAway trusts a declared parameter
     * type and checks that write normally, so no suppression is needed there.</li>
     * </ul>
     * <p>
     * Everything else about a key grid — reads, parameters, returns, row extraction — is fully
     * checked. Drop the remaining suppression once NullAway models nested array element types.
     * </p>
     */
    @SuppressWarnings("NullAway")
    static @Nullable String[][] newKeyGrid(int aKeys, int aRows)
    {
        return new @Nullable String[aKeys][aRows];
    }


    /** Writes one possibly-null cell into a key grid; see {@link #newKeyGrid(int, int)}. */
    static void putKeyCell(@Nullable String[][] aGrid, int aKey, int aRow, @Nullable String aValue)
    {
        aGrid[aKey][aRow] = aValue;
    }


    private static boolean anyKeyNull(@Nullable String[][] keyStr, int row)
    {
        for (String[] slot : keyStr)
        {
            if (slot[row] == null)
            {
                return true;
            }
        }
        return false;
    }


    private static @Nullable String[] slotsAt(@Nullable String[][] keyStr, int row)
    {
        @Nullable
        String[] out = new @Nullable String[keyStr.length];
        for (int k = 0; k < keyStr.length; k++)
        {
            out[k] = keyStr[k][row];
        }
        return out;
    }


    /**
     * Variable-length 32-bit hash over the pre-stringified standard-key slots then the IDVAR join
     * value, using Java's standard {@code 31 * h + slot} accumulation. Every argument is non-null
     * at call time (callers filter null slots/join values).
     */
    static int childMatchHash(@Nullable String[] aKeySlots, String aJoinValueStr)
    {
        int h = 0;
        for (@Nullable
        String slot : aKeySlots)
        {
            // Every caller has already rejected rows with a missing key cell — anyKeyNull() in
            // buildIndex(), allPresent() in ChildMatchPreMerger — but that is a boolean guard on
            // a separate method, which NullAway cannot relate to the array's contents. The
            // requireNonNull states the precondition and costs nothing: hashCode() would null-check
            // the same reference anyway.
            h = 31 * h + Objects.requireNonNull(slot).hashCode();
        }
        h = 31 * h + aJoinValueStr.hashCode();
        return h != 0 ? h : 1;
    }


    private static @Nullable String stringOrNull(IDataTableColumn aCol, int aRow)
    {
        IDataValue dv = aCol.getDataValue(aRow);
        return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }


    /**
     * Normalizes an IDVAR-join token so the parent's IDVAR-named column value (e.g. {@code AESEQ}
     * &rarr; {@code "1"}) and the child's {@code IDVARVAL} value (which can carry SAS padding, e.g.
     * {@code "       1"}, or a float rendering such as {@code "1.0"}) compare equal. Mirrors
     * Python's {@code dataset_preprocessor} coercion of the child IDVARVAL to the parent key's
     * type: a numeric token is canonicalized (integral &rarr; {@code "1"}, matching
     * {@code DataValueDouble}; non-integral &rarr; {@code "1.5"}); a non-numeric token is returned
     * stripped. Applied only to the two IDVAR-join arrays, so the hash and every matcher
     * ({@code ProbeMatcher} / {@code scanFallback} / {@code SelfMatcher}) stay byte-consistent.
     *
     * @param raw
     *            the raw join token, or {@code null}
     * @param numericParent
     *            whether the parent {@code IDVAR}-named column is numeric (gates the numeric
     *            canonicalization; stripping is always applied)
     * @return the coerced token, or {@code null} when {@code raw} is {@code null}
     */
    static @Nullable String normalizeJoinToken(@Nullable String raw, boolean numericParent)
    {
        if (raw == null)
        {
            return null;
        }
        String t = raw.strip();
        if (!numericParent)
        {
            // String parent: strip only (Python coerces the child IDVARVAL to the parent column's
            // type — a string parent keeps the value as a stripped string, so "01" != "1").
            return t;
        }
        try
        {
            double d = Double.parseDouble(t);
            if (Double.isFinite(d) && d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15)
            {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        catch (NumberFormatException _)
        {
            return t;
        }
    }

    /**
     * {@link HashLookup.BiRowMatcher} used at build time to detect duplicate parent keys. Compares
     * two parent rows by their stringified standard-key slots and join value.
     */
    static final class SelfMatcher implements HashLookup.BiRowMatcher
    {

        private final @Nullable String[][] keyStr;

        private final @Nullable String[] joinValueStr;

        SelfMatcher(@Nullable String[][] aKeyStr, @Nullable String[] aJoinValueStr)
        {
            keyStr = aKeyStr;
            joinValueStr = aJoinValueStr;
        }


        @Override
        public boolean matches(int aRow1, int aRow2)
        {
            // joinValue/key slots non-null at this point — build() filters null entries before
            // probing. The explicit guard keeps the array's @Nullable element type honest.
            String j1 = joinValueStr[aRow1];
            String j2 = joinValueStr[aRow2];
            if (j1 == null || j2 == null || !j1.equals(j2))
            {
                return false;
            }
            for (String[] slot : keyStr)
            {
                if (!Objects.equals(slot[aRow1], slot[aRow2]))
                {
                    return false;
                }
            }
            return true;
        }
    }


    /**
     * {@link HashLookup.BiRowMatcher} used at probe time. Compares a candidate parent row against a
     * primary row via the pre-stringified arrays — same coercion shape on both sides.
     */
    static final class ProbeMatcher implements HashLookup.BiRowMatcher
    {

        private final @Nullable String[][] parentKeyStr;

        private final @Nullable String[] parentJoinValueStr;

        private final @Nullable String[][] primaryKeyStr;

        private final @Nullable String[] primaryIdvarvalStr;

        ProbeMatcher(@Nullable String[][] aParentKeyStr, @Nullable String[] aParentJoinValueStr,
                @Nullable String[][] aPrimaryKeyStr, @Nullable String[] aPrimaryIdvarvalStr)
        {
            parentKeyStr = aParentKeyStr;
            parentJoinValueStr = aParentJoinValueStr;
            primaryKeyStr = aPrimaryKeyStr;
            primaryIdvarvalStr = aPrimaryIdvarvalStr;
        }


        @Override
        public boolean matches(int aParentRow, int aPrimaryRow)
        {
            // The fast-path probe is taken only when every primary standard-key value is present
            // and IDVARVAL is present; build() skipped parent rows with a missing key/IDVAR. The
            // explicit guard keeps the arrays' @Nullable element type honest.
            String pj = parentJoinValueStr[aParentRow];
            String pi = primaryIdvarvalStr[aPrimaryRow];
            if (pj == null || pi == null || !pj.equals(pi))
            {
                return false;
            }
            for (int k = 0; k < parentKeyStr.length; k++)
            {
                if (!parentKeyStr[k][aParentRow].equals(primaryKeyStr[k][aPrimaryRow]))
                {
                    return false;
                }
            }
            return true;
        }
    }

    /** Exposed for callers building per-row probe state. */
    static List<String> standardKeysOf(List<String> declaredKeys)
    {
        List<String> out = new ArrayList<>(declaredKeys.size());
        for (String k : declaredKeys)
        {
            if (!"IDVAR".equals(k) && !"IDVARVAL".equals(k))
            {
                out.add(k);
            }
        }
        return out;
    }

}
