package net.cumba.corej.core.exec;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * The engine's own gates on an ordinary keyed {@code Match_Datasets} entry, run before the merged
 * evaluation view is asked for the entry's part ({@link MergedEvaluation}) — the checks the retired
 * key-match expander made in its key-shape step ({@code PLAN-merged-evaluation-table} W6 moved them
 * here unchanged: the same exceptions, the same messages), so a rule ERROR names the authored key
 * rather than the datatable's spelling of it.
 *
 * <ul>
 * <li>a malformed sided key element ({@link MalformedSidedKeyException});</li>
 * <li>the D4 key-type identity ({@code PLAN-join-key-type-identity} D4-R1/R2): a component that is
 * numeric on one side and character on the other is a {@link JoinKeyTypeMismatchException}, unless
 * the entry declares {@code Join_As_String} (D4-R3) or the component is absent on one side (one
 * type only — JKM R7's absent-side default stands), or either kind classifies {@code null} (D4-R6a:
 * an all-NA R column is {@code logical});</li>
 * <li>a degenerate key — no component present on either side ({@link DegenerateJoinKeyException},
 * JKM R7);</li>
 * <li>a <b>qualified</b> component ({@code DM.RPATHCD}, {@code PLAN-rprfdy-offset-tp-join} C1) has
 * a SOURCE: an EARLIER ordinary entry of the rule, whose part the view builds first. Its three
 * D-ABSENT cases are {@link UnresolvedQualifiedKeyException} — the study lacks the source dataset,
 * the source dataset lacks the column, the joined dataset lacks the unqualified variable — never a
 * type default on one side and never a drop; D4 compares the SOURCE column's kind with the joined
 * one. A qualifier naming no earlier ordinary entry never came through the loader
 * ({@code RulePackageLoader.checkQualifiedKeys}) and is refused as a bypass.</li>
 * </ul>
 */
final class KeyGates
{

    private KeyGates()
    {
    }


    /**
     * Runs every gate of one entry against its resolved partner.
     *
     * @param primary
     *            the evaluated dataset (the record side of a plain component)
     * @param partner
     *            the entry's resolved partner
     * @param md
     *            the entry — an ordinary keyed join ({@link JoinKeyTypes#governedByKeyTypeCheck})
     * @param entries
     *            the rule's entries, in order — a qualified component's source is an EARLIER one
     * @param resolved
     *            the resolved partner of every earlier ordinary entry whose dataset the study
     *            supplies (identity-keyed); an earlier entry missing here was not supplied
     * @throws MalformedSidedKeyException
     *             a sided key element names one side only
     * @throws JoinKeyTypeMismatchException
     *             a key compares a numeric column with a character one (D4)
     * @throws DegenerateJoinKeyException
     *             no key column exists on either side
     * @throws UnresolvedQualifiedKeyException
     *             a qualified component's source dataset, source column or joined variable is
     *             absent (D-ABSENT)
     */
    static void check(IDataTable primary, IDataTable partner, MatchDataset md,
            List<MatchDataset> entries, IdentityHashMap<MatchDataset, IDataTable> resolved)
    {
        String childName = Objects.requireNonNull(md.getName());
        List<String> keys = Objects.requireNonNull(md.getKeys());
        boolean asString = md.joinKeysAsString();
        boolean typeChecked = !JoinKeyTypes.excludedFromKeyTypeCheck(md);
        // ⚠⚠ The two key lists are index-aligned ONLY when every element is readable from both
        // sides. `MatchDataset.sidedKeys` SKIPS what it cannot read, so `{"left":"X"}` yields a
        // SHORTER right list, and `[{"left":"A"},{"right":"B"}]` two lists of EQUAL length
        // describing different things, which a size check would wave through. ⇒ ask the same
        // per-element predicate the loader asks.
        String malformedKey = md.malformedKeyElement();
        if (malformedKey != null)
        {
            throw new MalformedSidedKeyException(childName, malformedKey);
        }
        // getRightKeys() unconditionally: it equals `keys` for every unqualified bare key, answers
        // the `right` side of a sided element, and the UNQUALIFIED name of a qualified one.
        List<String> childKeys = Objects.requireNonNull(md.getRightKeys());
        List<@Nullable String> qualifiers = md.keyQualifiers();
        DataTableMeta primaryMeta = primary.getMetaData();
        DataTableMeta childMeta = partner.getMetaData();
        int nActive = 0;
        for (int i = 0; i < keys.size(); i++)
        {
            int childCol = childMeta.getColumnIndex(childKeys.get(i));
            String qualifier = i < qualifiers.size() ? qualifiers.get(i) : null;
            if (qualifier != null)
            {
                checkQualified(partner, md, keys.get(i), childKeys.get(i), childCol, qualifier,
                        entries, resolved, typeChecked && !asString);
                nActive++; // always active: neither side may be absent
                continue;
            }
            int recordCol = primaryMeta.getColumnIndex(keys.get(i));
            boolean inPrimary = recordCol >= 0;
            boolean inChild = childCol >= 0;
            if (!inPrimary && !inChild)
            {
                continue; // absent on both sides: the component is dropped (JKM R7)
            }
            nActive++;
            if (typeChecked && !asString && inPrimary && inChild)
            {
                ColumnTypeGate.Kind primaryKind = ColumnTypeGate
                        .kindOf(primaryMeta.getColumn(recordCol).getType());
                ColumnTypeGate.Kind childKind = ColumnTypeGate
                        .kindOf(childMeta.getColumn(childCol).getType());
                if (primaryKind != null && childKind != null && primaryKind != childKind)
                {
                    throw new JoinKeyTypeMismatchException(childName, keys.get(i), childKeys.get(i),
                            describeKind(primaryKind), describeKind(childKind));
                }
            }
        }
        if (nActive == 0)
        {
            throw new DegenerateJoinKeyException(childName, keys);
        }
    }


    /** The D-SRC / D-ABSENT / D4 gates of one qualified component. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void checkQualified(IDataTable partner, MatchDataset md, String key,
            String childKey, int childCol, String qualifier, List<MatchDataset> entries,
            IdentityHashMap<MatchDataset, IDataTable> resolved, boolean typeChecked)
    {
        String childName = Objects.requireNonNull(md.getName());
        MatchDataset sourceEntry = earlierEntryNamed(entries, md, qualifier);
        if (sourceEntry == null)
        {
            throw new IllegalStateException("[" + childName + "] Match_Datasets key " + key
                    + " names no EARLIER ordinary entry of this rule — every rule comes through"
                    + " RulePackageLoader.checkQualifiedKeys, which refuses it; a hand-built rule"
                    + " orders its entries");
        }
        String where = "Match_Datasets " + childName;
        IDataTable source = resolved.get(sourceEntry);
        if (source == null)
        {
            throw new UnresolvedQualifiedKeyException(where, key,
                    "the study has no " + qualifier + " dataset to read " + childKey + " from");
        }
        int sourceCol = source.getMetaData().getColumnIndex(childKey);
        if (sourceCol < 0)
        {
            throw new UnresolvedQualifiedKeyException(where, key,
                    qualifier + " has no column " + childKey);
        }
        if (childCol < 0)
        {
            throw new UnresolvedQualifiedKeyException(where, key, childName + " has no column "
                    + childKey + " (the joined dataset needs the same variable, unqualified)");
        }
        if (typeChecked)
        {
            DataValueType sourceType = source.getMetaData().getColumn(sourceCol).getType();
            DataValueType childType = partner.getMetaData().getColumn(childCol).getType();
            ColumnTypeGate.Kind sourceKind = ColumnTypeGate.kindOf(sourceType);
            ColumnTypeGate.Kind childKind = ColumnTypeGate.kindOf(childType);
            if (sourceKind != null && childKind != null && sourceKind != childKind)
            {
                throw new JoinKeyTypeMismatchException(childName, key, qualifier, childKey,
                        describeKind(sourceKind), describeKind(childKind));
            }
        }
    }


    /**
     * The ordinary entry named {@code qualifier} (exactly) declared before {@code md}, or
     * {@code null}.
     */
    private static @Nullable MatchDataset earlierEntryNamed(List<MatchDataset> entries,
            MatchDataset md, String qualifier)
    {
        for (MatchDataset e : entries)
        {
            if (e == md)
            {
                return null;
            }
            if (e != null && JoinKeyTypes.governedByKeyTypeCheck(e)
                    && qualifier.equals(e.getName()))
            {
                return e;
            }
        }
        return null;
    }


    /**
     * The {@code Requirements.Variables} spelling of a kind, so the error message names the tag the
     * author would actually write ({@code :N} / {@code :C}).
     */
    private static String describeKind(ColumnTypeGate.Kind aKind)
    {
        return aKind == ColumnTypeGate.Kind.NUMERIC ? "Numeric" : "Character";
    }
}
