package net.cumba.corej.core.exec;

import java.util.ArrayList;
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
 * <li>where each component's column is ({@link KeySide}): owner ruling {@code REG-Q3} (2026-10-09,
 * replacing {@code JKM R7}'s one-side type default) — a component joins only when <b>both</b> sides
 * carry it; present on one side only it leaves the key (the caller logs the WARNING,
 * {@link MergedEvaluation}), absent from both it leaves the key silently;</li>
 * <li>the D4 key-type identity ({@code PLAN-join-key-type-identity} D4-R1/R2): a component that is
 * numeric on one side and character on the other is a {@link JoinKeyTypeMismatchException}, unless
 * the entry declares {@code Join_As_String} (D4-R3), or either kind classifies {@code null}
 * (D4-R6a: an all-NA R column is {@code logical}) — judged only for a component both sides
 * carry;</li>
 * <li>a degenerate key — no component present on both sides ({@link DegenerateJoinKeyException},
 * {@code REG-Q3}: no key column left ⇒ rule ERROR);</li>
 * <li>a <b>qualified</b> component ({@code DM.RPATHCD}, {@code PLAN-rprfdy-offset-tp-join} C1) has
 * a SOURCE: an EARLIER ordinary entry of the rule, whose part the view builds first. Its record
 * side is the source entry's column: the study lacking the source dataset (QK2: an unsupplied
 * dataset is its column absent, {@code D72a-1}) or the source dataset lacking the column is the
 * record side absent, the joined dataset lacking the unqualified variable the joined side absent —
 * the component then leaves the key like an unqualified one ({@code REG-Q3}, the owner's principle:
 * <i>"a qualified variable reference should ALWAYS be treated like an unqualified one"</i>); D4
 * compares the SOURCE column's kind with the joined one. A qualifier naming no earlier ordinary
 * entry never came through the loader ({@code RulePackageLoader.checkQualifiedKeys}) and is refused
 * as a bypass.</li>
 * </ul>
 */
final class KeyGates
{

    private KeyGates()
    {
    }

    /**
     * Where one key component's column is ({@code REG-Q3}).
     *
     * @param onRecordSide
     *            the record side carries it (the evaluated dataset; a qualified component's source
     *            dataset)
     * @param onPartnerSide
     *            the entry's partner carries it
     * @param recordDataset
     *            the dataset the record side reads it from, for the WARNING
     */
    record KeySide(boolean onRecordSide, boolean onPartnerSide, String recordDataset)
    {

        /**
         * Whether the component stays in the key — both sides carry it.
         *
         * @return whether it joins
         */
        boolean joins()
        {
            return onRecordSide && onPartnerSide;
        }


        /**
         * Whether it leaves the key with a WARNING — exactly one side carries it.
         *
         * @return whether it is on one side only
         */
        boolean oneSideOnly()
        {
            return onRecordSide != onPartnerSide;
        }
    }

    /**
     * Where each component of a keyed entry's key is, with no gate run — for a keyed part outside
     * {@link JoinKeyTypes#governedByKeyTypeCheck} (a {@code SUPP*} / {@code SQ*} / {@code --} /
     * {@code RELREC} named entry), which carries no qualified component (the loader refuses one,
     * {@code RulePackageLoader.checkQualifiedKeys}).
     *
     * @param primary
     *            the evaluated dataset
     * @param partner
     *            the entry's resolved partner
     * @param md
     *            the entry
     * @return one side per key component, in key order
     */
    static List<KeySide> sides(IDataTable primary, IDataTable partner, MatchDataset md)
    {
        List<String> keys = Objects.requireNonNull(md.getKeys());
        List<String> childKeys = Objects.requireNonNull(md.getRightKeys());
        DataTableMeta primaryMeta = primary.getMetaData();
        DataTableMeta childMeta = partner.getMetaData();
        String primaryName = Objects.requireNonNullElse(primaryMeta.getName(), "?");
        List<KeySide> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++)
        {
            String right = i < childKeys.size() ? childKeys.get(i) : keys.get(i);
            out.add(new KeySide(primaryMeta.getColumnIndex(keys.get(i)) >= 0,
                    childMeta.getColumnIndex(right) >= 0, primaryName));
        }
        return out;
    }


    /**
     * Runs every gate of one entry against its resolved partner and answers where each key
     * component is ({@code REG-Q3}: only a component both sides carry joins).
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
     * @return one side per key component, in key order
     * @throws DegenerateJoinKeyException
     *             no key column is present on both sides
     */
    static List<KeySide> check(IDataTable primary, IDataTable partner, MatchDataset md,
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
        String primaryName = Objects.requireNonNullElse(primaryMeta.getName(), "?");
        List<KeySide> sides = new ArrayList<>(keys.size());
        int nActive = 0;
        for (int i = 0; i < keys.size(); i++)
        {
            int childCol = childMeta.getColumnIndex(childKeys.get(i));
            String qualifier = i < qualifiers.size() ? qualifiers.get(i) : null;
            KeySide side = qualifier != null
                    ? checkQualified(partner, md, keys.get(i), childKeys.get(i), childCol,
                            qualifier, entries, resolved, typeChecked && !asString)
                    : new KeySide(primaryMeta.getColumnIndex(keys.get(i)) >= 0, childCol >= 0,
                            primaryName);
            sides.add(side);
            if (!side.joins())
            {
                continue; // REG-Q3: on one side only, or on neither, the component leaves the key
            }
            nActive++;
            if (qualifier == null && typeChecked && !asString)
            {
                int recordCol = primaryMeta.getColumnIndex(keys.get(i));
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
        return sides;
    }


    /**
     * The D-SRC / D4 gates of one qualified component and where its two sides are: the record side
     * is the source entry's column — absent when the study does not supply the source dataset (QK2)
     * or the source dataset lacks the column — the joined side the unqualified variable in the
     * partner ({@code REG-Q3}).
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static KeySide checkQualified(IDataTable partner, MatchDataset md, String key,
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
        IDataTable source = resolved.get(sourceEntry);
        int sourceCol = source == null ? -1 : source.getMetaData().getColumnIndex(childKey);
        String sourceName = source == null ? qualifier
                : Objects.requireNonNullElse(source.getMetaData().getName(), qualifier);
        KeySide side = new KeySide(sourceCol >= 0, childCol >= 0, sourceName);
        if (source != null && side.joins() && typeChecked)
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
        return side;
    }


    /**
     * The ordinary entry named {@code qualifier} declared before {@code md}, or {@code null} — the
     * name matched ignoring letter case (P-19, §3.2: {@code dm.RPATHCD} names the {@code DM} entry,
     * as the loader's D-SRC already reads it), so a loaded rule never reaches the bypass refusal.
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
                    && qualifier.equalsIgnoreCase(e.getName()))
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
