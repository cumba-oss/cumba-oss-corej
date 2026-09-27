package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.model.JoinType;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * Builds a row-EXPANDED evaluation table for key-based {@code Match_Datasets} joins, mirroring the
 * Python engine's {@code merge_datasets} preprocessing ({@code DataProcessor.merge_sdtm_datasets}):
 * one expanded row per (primary record, matched child record) pair, sequentially left-folded across
 * every key entry. Each expanded row binds to exactly one child row per joined dataset, exposed via
 * a {@link KeyMatchExpandedLookup} (registered under the child dataset name), so the existing
 * dot-qualified {@code AE.AESDTH} consumption resolves the <em>matching</em> child's value rather
 * than a first-wins guess.
 *
 * <p>
 * Join type is per the rule's {@link MatchDataset#getJoinType()}: {@code left} keeps a primary row
 * with no matching child and binds it to no child row. A dotted reference then reads the joined
 * column's TYPE default through the typed {@code lookupValue} (D72/D72a-1: {@code ""} for
 * character, {@code MissingValue.MIS} for numeric), and {@code null} only through the text
 * {@code lookup}; see {@link KeyMatchExpandedLookup}. That is what makes absence/empty checks fire.
 * {@code inner} drops the unmatched primary row.
 * </p>
 *
 * <p>
 * Keys compare by the classified {@link GroupKeyPolicy.KeyPart} of each component, not by rendered
 * text (see {@link #keyPart}). A {@code MissingValue} is a key of its own, distinct from {@code ""}
 * and from every other marker (JKM R5), and a character key never equals a numeric one. A mismatch
 * of the two sides' declared kinds is a rule ERROR (D4-R2) unless the entry declares
 * {@code Join_As_String}, which compares present parts as text (D4-R3).
 * </p>
 *
 * <p>
 * The child side of each join is a {@link KeyMatchIndex}, shared across rules through
 * {@link JoinCache.SharedIndexCache} ({@code PLAN-keymatch-shared-join-index}).
 * </p>
 *
 * <p>
 * ⛔ <b>There is no join-type default here.</b> {@code RulePackageLoader.normalizeJoinTypes} stamps
 * {@code inner} onto every entry that omits {@code Join_Type} and the load gate ({@code Fix #236}:
 * {@link net.cumba.corej.core.model.JoinType} is the closed vocabulary,
 * {@code RulePackageLoader.validateEnumFields} files a {@code loadError} for anything else) admits
 * only {@code inner} and {@code left}, so a rule that came through the loader always arrives with
 * one of the two. An entry that reaches {@link #expand} <em>without</em> a value is a rule that
 * bypassed the loader, and it is refused with an {@link IllegalStateException} — never run.
 * </p>
 *
 * <p>
 * ⚑ <b>History.</b> Until U15 of {@code PLAN-retire-dead-multi-match-lookup} (2026-09-25) an absent
 * value fell through to {@code left} here: a second default on the execution path that disagreed
 * with the loader's {@code inner}, unreachable from production (the last minter of loader-bypassing
 * rules, the {@code CROSS_DATASET_METADATA} generator, is deleted) and load-bearing only for
 * hand-built test fixtures — which were therefore asserting a semantics the loader would never have
 * produced for them. A 2026-09-15 attempt to delete it was reverted for exactly that reason. Since
 * U15 those fixtures either author {@code Join_Type} explicitly ({@code left}, the value their
 * assertions were written for) or — the three integration probe classes — pass their hand-built
 * rule through {@code RulePackageLoader.normalizeJoinTypes(Rule)}, exactly as a load does. The
 * <em>loader's</em> {@code inner} default is itself RULED: triage finding {@code S2}
 * ({@code plans/done/PLAN-expired-justifications-triage.md}) was answered by the owner on
 * 2026-09-25 — <i>"inner stays default"</i> — and is registered in
 * {@code .claude/docs/rulings/value-semantics.md} §11. This class holds no default of its own.
 * </p>
 *
 * <p>
 * ⚠ The test below is still a NEGATION — {@code !JoinType.INNER.getJsonValue().equalsIgnoreCase(…)}
 * — because the gate leaves exactly two legal values, and changing the semantics of either would
 * move findings on the 17 {@code rules-src} rules that author {@code left} (measured 2026-09-25 in
 * {@code cumba-corej-rules}: every authored value is {@code left}). Adding a third join type means
 * auditing every {@code inner} comparison site, not adding a branch here.
 * </p>
 *
 * <p>
 * Returns {@code null} when the rule declares no expandable key entry, leaving evaluation
 * unchanged. Child / RELREC entries are handled by {@link ChildMatchPreMerger} /
 * {@link RelrecRowExpander} and are excluded here.
 * </p>
 */
final class KeyMatchRowExpander
{

    /**
     * @param table
     *            the expanded evaluation table (row {@code i} maps back to its primary record).
     * @param lookups
     *            the bound-child lookup per expanded child dataset, keyed by dataset name.
     * @param expandedEntries
     *            the {@link MatchDataset} entries consumed here (to exclude from key-join
     *            building).
     */
    record KeyMatchExpansion(IDataTable table, Map<String, JoinLookup> lookups,
            List<MatchDataset> expandedEntries)
    {
    }

    private KeyMatchRowExpander()
    {
    }


    /**
     * The row expansion, with the child indexes taken from — and left in — {@code aShared} when one
     * is given ({@code PLAN-keymatch-shared-join-index}). With {@code null} every index is built
     * for this call only; the result is identical either way.
     *
     * <p>
     * ⭐ Built for big tables and many rules (D7, proposals P1–P6 in
     * {@code plans/findings/FINDINGS-keymatch-data-structures.md}): the child index is a shared,
     * compact {@link KeyMatchIndex}; a {@code Filter} is a per-rule {@link BitSet} over the
     * <b>unfiltered</b> child rather than a filtered copy, so it does not split the cache; and the
     * bindings are {@code int} columns — one for the primary row, one per entry — with no object
     * per binding and no per-row key list.
     * </p>
     *
     * @param aShared
     *            the run's shared index cache, or {@code null} to build every index locally.
     */
    static @Nullable KeyMatchExpansion expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId, JoinCache.@Nullable SharedIndexCache aShared)
    {
        List<MatchDataset> entries = expandableEntries(matchDatasets);
        if (entries.isEmpty())
        {
            return null;
        }

        int nEntries = entries.size();
        // P5: the key-match path indexes rows as int; a table past 2^31 rows fails loudly here
        // (rule ERROR) instead of wrapping silently further down.
        int primaryRows = Math.toIntExact(primaryTable.getRowCount());

        // The bindings, column-wise: expanded row i binds primary row primaryCol[i] and, for entry
        // e, child row childCols[e][i] (-1 when unbound). A null column is unbound throughout —
        // an entry not yet folded in, or one whose child did not resolve.
        int size = primaryRows;
        int[] primaryCol = new int[size];
        for (int i = 0; i < size; i++)
        {
            primaryCol[i] = i;
        }
        int[][] childCols = new int[nEntries][];

        IDataTable[] resolvedChildren = new IDataTable[nEntries];
        for (int ei = 0; ei < nEntries; ei++)
        {
            MatchDataset md = entries.get(ei);
            // expandableEntries guarantees a non-null name and non-empty keys.
            // Fix #358: exact name first, else the row-stacked union of a split domain
            // (lbch/lbhe/lbur → LB); an un-unionable split throws → rule ERROR (ruling 1).
            IDataTable child = SplitDomainResolution.resolveTableOrThrow(resolver,
                    Objects.requireNonNull(md.getName()), ruleId);
            if (child == null)
            {
                // Python: related dataset not found -> skip the merge (leave bindings unchanged).
                continue;
            }
            // 5b-J: the pre-merge Filter (spec §3.3) restricts the child BEFORE any row is bound —
            // a dropped row can never become a join partner, and an unmatched-by-filter primary
            // row reads exactly like an unmatched-by-key one (left keeps it null-bound, inner
            // drops it, D._matched_ reads false). Q1: it is a MASK over the unfiltered child,
            // skipped at probe time, so the shared index never depends on it. ⚠ Evaluated before
            // keySpec, as it always was: a rule whose filter and key check both throw keeps
            // reporting the filter's error.
            BitSet keep = MatchFilter.mask(md, child, ruleId);
            resolvedChildren[ei] = child;
            List<String> keys = Objects.requireNonNull(md.getKeys());
            // The join type is the loader's: RulePackageLoader.normalizeJoinTypes stamps `inner`
            // on every entry that omits it and the load gate rejects anything outside the
            // vocabulary, so an absent value here means a rule that never came through the
            // loader. Until U15 of PLAN-retire-dead-multi-match-lookup that case fell through to
            // `left` silently; it is refused now — a second default on the execution path is
            // what let hand-built fixtures assert a semantics the loader never produced.
            String joinType = md.getJoinType();
            if (JoinType.isAbsent(joinType))
            {
                throw new IllegalStateException("[" + ruleId + "] Match_Datasets entry "
                        + md.getName() + " reached execution without a Join_Type — every rule"
                        + " comes through RulePackageLoader.normalizeJoinTypes, which stamps"
                        + " `inner`; a hand-built rule sets it explicitly"
                        + " (PLAN-retire-dead-multi-match-lookup U15)");
            }
            // Fix #236: the comparison is sourced from the JoinType vocabulary so the constant and
            // the load-time gate cannot drift apart. Anything that is not `inner` is `left` —
            // the gate admits nothing else. Left keeps an unmatched primary row with a null-valued
            // joined column, which is what absence/empty checks rely on (e.g. CDISC-AD0053 fires
            // on DM.USUBJID empty for a subject not in DM).
            boolean left = !JoinType.INNER.getJsonValue().equalsIgnoreCase(joinType);
            // JKM R4/R5/R7 (PLAN-join-key-missing-semantics): the key's shape is decided ONCE per
            // entry — which components survive (R7's both-sides-absent drop), what an absent side
            // contributes, and whether blanks participate (R4's KEEP default). A per-row test could
            // not express the drop, because "absent on both sides" is a property of the two tables,
            // not of a row.
            KeySpec spec = keySpec(primaryTable, child, keys, md);
            // P5, as for the primary. After keySpec, so a rule whose key check throws keeps
            // reporting that error first, as it did when the row count was first read in the build.
            int childRows = Math.toIntExact(child.getRowCount());
            KeyMatchIndex index = aShared == null ? buildChildIndex(child, childRows, spec)
                    : aShared.getOrBuildKeyMatchIndex(child, spec.cacheKey(),
                            () -> buildChildIndex(child, childRows, spec));
            KeyPart[] probe = spec.nActive() > 1 ? new KeyPart[spec.nActive()] : null;
            // D6: the primary side's readers, once per entry.
            @Nullable
            KeyCellReader[] primaryReaders = KeyCellReader.of(primaryTable, spec.primaryColIds());

            Bindings next = new Bindings(size, primaryCol, childCols, ei);
            // #5(a), PLAN-identity-safe-join-caches Phase 2: a one-slot probe memo, declared HERE
            // so it is reset for every entry. The probe is a pure function of the PRIMARY row
            // (nothing an earlier entry bound reaches it), and the rows expanded from one primary
            // row are contiguous (Bindings appends in ascending i, starting from the identity), so
            // a primary row fanned out k times by an earlier entry is probed once, not k times.
            int memoPrimary = -1;
            int memoId = KeyMatchIndex.NOT_FOUND;
            for (int i = 0; i < size; i++)
            {
                int p = primaryCol[i];
                int id = p == memoPrimary ? memoId
                        : probeKeyId(primaryTable, primaryReaders, spec, p, index, probe);
                memoPrimary = p;
                memoId = id;
                boolean bound = false;
                if (id != KeyMatchIndex.NOT_FOUND)
                {
                    for (int pos = index.start(id), end = index.end(id); pos < end; pos++)
                    {
                        int cr = index.row(pos);
                        if (keep == null || keep.get(cr))
                        {
                            next.add(i, cr);
                            bound = true;
                        }
                    }
                }
                if (!bound && left)
                {
                    next.add(i, -1); // child stays unbound (null-bound)
                }
                // inner: an unmatched primary row is dropped
            }
            size = next.size;
            primaryCol = next.primary();
            childCols = next.children();
        }

        IDataBufferNumeric rowMap = DataBufferFactory.get().createForRange(0,
                Math.max(0, primaryRows - 1L));
        rowMap.setExpectedSize(size);
        for (int i = 0; i < size; i++)
        {
            rowMap.addValue(primaryCol[i]);
        }
        IDataTable expandedTable = new RelrecExpandedTable(primaryTable, rowMap);

        Map<String, JoinLookup> lookups = new LinkedHashMap<>();
        List<MatchDataset> expandedEntries = new ArrayList<>();
        for (int ei = 0; ei < nEntries; ei++)
        {
            IDataTable child = resolvedChildren[ei];
            if (child == null)
            {
                continue; // unresolved child: not expanded, no lookup
            }
            // Bound rows index the UNFILTERED child (Q1): the mask chose which rows may bind, the
            // lookup reads them straight from the table.
            String name = Objects.requireNonNull(entries.get(ei).getName());
            lookups.put(name,
                    new KeyMatchExpandedLookup(name, child, Objects.requireNonNull(childCols[ei])));
            expandedEntries.add(entries.get(ei));
        }
        return new KeyMatchExpansion(expandedTable, lookups, expandedEntries);
    }

    /**
     * One fold step's output bindings, column-wise (P4): appending expanded row {@code i} of the
     * previous step copies its primary row and every other entry's binding, and sets this step's
     * entry. Grows by doubling; nothing is allocated per binding.
     */
    private static final class Bindings
    {

        private final int[] prevPrimary;

        private final int[][] prevChildren;

        private final int entry;

        private int[] primary;

        private final int[][] children;

        private int size;

        private Bindings(int aExpected, int[] aPrevPrimary, int[][] aPrevChildren, int aEntry)
        {
            prevPrimary = aPrevPrimary;
            prevChildren = aPrevChildren;
            entry = aEntry;
            int capacity = Math.max(aExpected, 1);
            primary = new int[capacity];
            children = new int[aPrevChildren.length][];
            for (int e = 0; e < children.length; e++)
            {
                if (e == aEntry || aPrevChildren[e] != null)
                {
                    children[e] = new int[capacity];
                }
            }
        }


        private void add(int aPrevRow, int aChildRow)
        {
            if (size == primary.length)
            {
                int capacity = Math.addExact(primary.length, Math.max(primary.length >> 1, 16));
                primary = Arrays.copyOf(primary, capacity);
                for (int e = 0; e < children.length; e++)
                {
                    if (children[e] != null)
                    {
                        children[e] = Arrays.copyOf(children[e], capacity);
                    }
                }
            }
            primary[size] = prevPrimary[aPrevRow];
            for (int e = 0; e < children.length; e++)
            {
                int[] column = children[e];
                if (column != null)
                {
                    column[size] = e == entry ? aChildRow : prevChildren[e][aPrevRow];
                }
            }
            size++;
        }


        private int[] primary()
        {
            return primary.length == size ? primary : Arrays.copyOf(primary, size);
        }


        private int[][] children()
        {
            int[][] out = new int[children.length][];
            for (int e = 0; e < children.length; e++)
            {
                int[] column = children[e];
                out[e] = column == null || column.length == size ? column
                        : Arrays.copyOf(column, size);
            }
            return out;
        }
    }

    /**
     * Key-based entries eligible for row expansion: a non-blank {@code Keys} list, not
     * {@code Child:true}, not RELREC, not a SUPP/SQ qualifier dataset (Python pivots those via
     * {@code merge_pivot_supp_dataset} rather than a plain key merge), and not a {@code --}
     * wildcard dataset name (those need name resolution and are left to the existing key-join
     * path).
     */
    static List<MatchDataset> expandableEntries(@Nullable List<MatchDataset> mds)
    {
        List<MatchDataset> out = new ArrayList<>();
        if (mds == null)
        {
            return out;
        }
        for (MatchDataset md : mds)
        {
            // ⭐ 2026-09-22: the test moved to JoinKeyTypes so the D4 type check, the expander and
            // the loader's Join_As_String guard ask ONE predicate. Behaviour unchanged.
            if (JoinKeyTypes.governedByKeyTypeCheck(md))
            {
                out.add(md);
            }
        }
        return out;
    }


    /**
     * The key part as it participates in the join: itself, or — under {@code Join_As_String}
     * ({@code D4-R3}) — its text-join projection {@link KeyPart#asText()}, the one definition of
     * that projection, shared with the RELREC link identity ({@code PLAN-shared-key-identity}).
     *
     * <p>
     * ⛔⛔ <b>Only a PRESENT part is coerced.</b> {@link KeyPart.Missing} and {@link KeyPart#EMPTY}
     * keep their own classification, because that classification is what stops a
     * {@code MissingValue} colliding with a present {@code "."} — the {@code +9 528-finding} bug
     * class this file's own javadoc records. ⚑ {@code Present}'s constructor rejects {@code ""}, so
     * the coercion cannot manufacture an {@code EMPTY} either; a {@link KeyPart.Present} projects
     * to itself.
     * </p>
     *
     * <p>
     * ⚠ Because the rendering is the cleaned text ({@code DataValueSupport.toCleanText}: noise
     * within {@code 1e-12} of the value's decade folds onto the 12-significant-digit value, every
     * other value is rendered losslessly), a flagged entry compares its numeric keys with that
     * noise folded rather than exactly. That is a property the author opts into, and it is the
     * behaviour every entry had before D4.
     * </p>
     *
     * @param aPart
     *            the classified key part.
     * @param aAsString
     *            whether the entry declares {@code Join_As_String: true}.
     * @return the part to put in the key.
     */
    private static KeyPart coerce(KeyPart aPart, boolean aAsString)
    {
        return aAsString ? aPart.asText() : aPart;
    }


    /**
     * The child's key index for {@code spec} — the one build routine, used with and without the
     * shared cache. A row dropped by an authored {@code keep_missings: false} is not indexed.
     */
    private static KeyMatchIndex buildChildIndex(IDataTable child, int childRows, KeySpec spec)
    {
        int[] colIds = spec.childColIds();
        // D6: one reader per key column, built once per index build — never per row.
        @Nullable
        KeyCellReader[] readers = KeyCellReader.of(child, colIds);
        return KeyMatchIndex.build(childRows, row ->
        {
            if (!spec.keepMissings() && anyActiveKeyBlank(child, colIds, spec, row))
            {
                return null;
            }
            if (spec.nActive() == 1)
            {
                return keyPart(readers, spec, spec.singleActive(), row);
            }
            KeyPart[] parts = new KeyPart[spec.nActive()];
            fillKeyParts(readers, spec, row, parts);
            return new KeyMatchIndex.CompositeKey(parts);
        });
    }


    /**
     * The key id of a primary row in {@code index}, or {@link KeyMatchIndex#NOT_FOUND} when the row
     * matches nothing — including a row an authored {@code keep_missings: false} drops (P3: no key
     * object is built for the lookup beyond the parts themselves).
     */
    private static int probeKeyId(IDataTable primary, @Nullable KeyCellReader[] readers,
            KeySpec spec, int row, KeyMatchIndex index, KeyPart @Nullable [] scratch)
    {
        int[] colIds = spec.primaryColIds();
        if (!spec.keepMissings() && anyActiveKeyBlank(primary, colIds, spec, row))
        {
            return KeyMatchIndex.NOT_FOUND;
        }
        if (scratch == null)
        {
            return index.find(keyPart(readers, spec, spec.singleActive(), row));
        }
        fillKeyParts(readers, spec, row, scratch);
        return index.find(scratch);
    }

    /**
     * The shape of one entry's join key, computed once from the two tables' metadata.
     *
     * <p>
     * ⭐⭐ <b>{@code JKM R7} lives here, not in {@link #keyPart}.</b> <i>"Absent columns are treated
     * like present, but empty (with default value). … Absent in both sides can be dropped from the
     * join. if all columns are absent, then the rule should fail with an error."</i> (owner,
     * 2026-09-21). Both halves are properties of the two <em>tables</em>, so deciding them per row
     * would be both wasteful and unable to express the drop at all.
     * </p>
     *
     * <b>Fields.</b>
     * <ul>
     * <li>{@code primaryColIds} — the key columns in the validated dataset; {@code -1} where
     * absent</li>
     * <li>{@code childColIds} — the key columns in the joined dataset; {@code -1} where absent</li>
     * <li>{@code absentPart} — * per component, the {@link KeyPart} an <b>absent</b> side
     * contributes — derived from the type of the side that <em>does</em> carry the column, because
     * an absent column has no type of its own: {@link KeyPart#EMPTY} for a character column, the
     * generic numeric missing for a numeric one. Unused where both sides carry the column.
     * <li>{@code active} — * per component, whether it takes part in the key at all; {@code false}
     * exactly where the column is absent from <b>both</b> sides
     * <li>{@code keepMissings} — {@code JKM R4}: whether blank-keyed rows participate. Default
     * {@code true}</li>
     * </ul>
     */
    private static final class KeySpec
    {

        // ⚠ A record was the obvious spelling and Error Prone rejects it: ArrayRecordComponent —
        // a record's generated equals/hashCode would compare arrays by IDENTITY. This carrier is
        // never compared, but a class states that rather than relying on nobody trying.
        private final int[] primaryColIds;

        private final int[] childColIds;

        private final KeyPart[] absentPart;

        private final boolean[] active;

        private final boolean keepMissings;

        /** D4-R3: compare this entry's present key parts as rendered text. */
        private final boolean asString;

        /** The number of active components — at least one, or keySpec had thrown. */
        private final int nActive;

        /** The first active component; the only one when {@link #nActive} is 1. */
        private final int singleActive;

        private KeySpec(int[] aPrimaryColIds, int[] aChildColIds, KeyPart[] aAbsentPart,
                boolean[] aActive, boolean aKeepMissings, boolean aAsString)
        {
            primaryColIds = aPrimaryColIds;
            childColIds = aChildColIds;
            absentPart = aAbsentPart;
            active = aActive;
            keepMissings = aKeepMissings;
            asString = aAsString;
            int count = 0;
            int first = -1;
            for (int i = 0; i < aActive.length; i++)
            {
                if (aActive[i])
                {
                    count++;
                    first = first < 0 ? i : first;
                }
            }
            nActive = count;
            singleActive = first;
        }


        private int nActive()
        {
            return nActive;
        }


        private int singleActive()
        {
            return singleActive;
        }


        /**
         * The child-side half of this shape — everything {@link #buildChildIndex} reads besides the
         * table ({@code PLAN-keymatch-shared-join-index} D2, see {@link KeyMatchIndex.SpecKey}).
         * {@code primaryColIds}, and {@code absentPart} where the child HAS the column, are
         * deliberately left out: the child's index never reads them.
         */
        private KeyMatchIndex.SpecKey cacheKey()
        {
            List<Integer> cols = new ArrayList<>(childColIds.length);
            List<Boolean> act = new ArrayList<>(active.length);
            List<KeyPart> childAbsent = new ArrayList<>();
            for (int i = 0; i < childColIds.length; i++)
            {
                cols.add(childColIds[i]);
                act.add(active[i]);
                if (active[i] && childColIds[i] < 0)
                {
                    childAbsent.add(absentPart[i]);
                }
            }
            return new KeyMatchIndex.SpecKey(cols, act, childAbsent, keepMissings, asString);
        }


        private boolean asString()
        {
            return asString;
        }


        private int[] primaryColIds()
        {
            return primaryColIds;
        }


        private int[] childColIds()
        {
            return childColIds;
        }


        private KeyPart[] absentPart()
        {
            return absentPart;
        }


        private boolean[] active()
        {
            return active;
        }


        private boolean keepMissings()
        {
            return keepMissings;
        }
    }

    private static KeySpec keySpec(IDataTable primary, IDataTable child, List<String> keys,
            MatchDataset md)
    {
        String childName = Objects.requireNonNull(md.getName());
        boolean asString = md.joinKeysAsString();
        boolean typeChecked = !JoinKeyTypes.excludedFromKeyTypeCheck(md);
        // ⭐⭐ Sided keys ({left: .., right: ..}): the joined side is named by getRightKeys(), NOT
        // by the left names. Before 2026-09-22 BOTH sides were resolved from md.getKeys() -- the
        // LEFT list -- so a sided entry joined the child on a column the entry never named, and
        // D4's type check would then have compared the wrong column's type (a false ERROR naming a
        // column that is not a key of this entry, or a real mismatch going undetected because the
        // left name is absent on the child). Zero corpus entries use the sided shape today, which
        // is why it stayed latent; it is fixed here rather than propagated.
        // ⚠⚠ The two key lists are index-aligned ONLY when every element is readable from both
        // sides. `MatchDataset.sidedKeys` SKIPS what it cannot read, so `{"left":"X"}` yields a
        // SHORTER right list while `keys.size()` still drives every loop and array below -- an
        // ArrayIndexOutOfBoundsException, not a diagnosis -- and `[{"left":"A"},{"right":"B"}]`
        // yields two lists of EQUAL length describing different things, which a size check would
        // wave through. ⇒ ask the same per-element predicate the loader asks.
        String malformedKey = md.malformedKeyElement();
        if (malformedKey != null)
        {
            throw new MalformedSidedKeyException(childName, malformedKey);
        }
        List<String> childKeys = md.hasSidedKeys() ? Objects.requireNonNull(md.getRightKeys())
                : keys;
        int[] primaryColIds = resolveColIds(primary.getMetaData(), keys);
        int[] childColIds = resolveColIds(child.getMetaData(), childKeys);
        KeyPart[] absentPart = new KeyPart[keys.size()];
        boolean[] active = new boolean[keys.size()];
        int nActive = 0;
        for (int i = 0; i < keys.size(); i++)
        {
            boolean inPrimary = primaryColIds[i] >= 0;
            boolean inChild = childColIds[i] >= 0;
            active[i] = inPrimary || inChild;
            if (!active[i])
            {
                continue;
            }
            nActive++;
            DataValueType primaryType = inPrimary
                    ? primary.getMetaData().getColumn(primaryColIds[i]).getType()
                    : null;
            DataValueType childType = inChild
                    ? child.getMetaData().getColumn(childColIds[i]).getType()
                    : null;
            // ⭐⭐ D4-R1/R2 — the join-key type identity, and THIS is the only site that holds both
            // sides' declared types. ⛔ It cannot live in RuleRunner.buildJoinedDatasets: that
            // method is reached only AFTER RuleRunner:918-920 removes every expanded entry, so a
            // check sited there would fire on nothing and green every gate.
            //
            // Four ways this does NOT fire, each deliberate:
            // - the entry is excluded (D4-R5): Child/RELREC/SUPP-- join a text-carried foreign
            // key against a typed column BY DESIGN -- 11 keyed entries in 5 rule files;
            // - the author declared Join_As_String (D4-R3): the divergence is understood and the
            // keys compare as text below, in keyPart();
            // - the component is absent on one side: there is only ONE type, so a mismatch is not
            // expressible, and JKM R7's absent-side default stands untouched;
            // - either kind classifies null -- BOOLEAN/COMPLEX/MISSING/VARIABLE/OTHER (D4-R6a).
            // ⚠⚠ That last arm is LOAD-BEARING, not a formality: an all-NA R column is
            // `logical`, which RdataTableProvider maps to BOOLEAN, so an .rds study whose key
            // variable is wholly missing would otherwise ERROR on every keyed rule. The engine
            // itself also publishes MISSING for an absent joined column (JoinLookup:264).
            if (typeChecked && !asString && primaryType != null && childType != null)
            {
                ColumnTypeGate.Kind primaryKind = ColumnTypeGate.kindOf(primaryType);
                ColumnTypeGate.Kind childKind = ColumnTypeGate.kindOf(childType);
                if (primaryKind != null && childKind != null && primaryKind != childKind)
                {
                    throw new JoinKeyTypeMismatchException(childName, keys.get(i), childKeys.get(i),
                            describeKind(primaryKind), describeKind(childKind));
                }
            }
            // The present side's declared type decides what the absent side contributes. A
            // character column's default is the empty string (a PRESENT value, D34 #1); a numeric
            // column's is a MissingValue, since a numeric cell cannot hold "".
            DataValueType type = inPrimary ? primaryType : childType;
            absentPart[i] = isNumeric(Objects.requireNonNull(type))
                    ? KeyPart.missing(MissingValue.MIS)
                    : KeyPart.EMPTY;
        }
        if (nActive == 0)
        {
            throw new DegenerateJoinKeyException(childName, keys);
        }
        return new KeySpec(primaryColIds, childColIds, absentPart, active, md.keepMissingKeys(),
                asString);
    }


    /**
     * The {@code Requirements.Variables} spelling of a kind, so the error message names the tag the
     * author would actually write ({@code :N} / {@code :C}).
     *
     * @param aKind
     *            the classified kind, never {@code null}.
     * @return {@code "Numeric"} or {@code "Character"}.
     */
    private static String describeKind(ColumnTypeGate.Kind aKind)
    {
        return aKind == ColumnTypeGate.Kind.NUMERIC ? "Numeric" : "Character";
    }


    /**
     * ⭐ Reconciled onto {@link ColumnTypeGate#kindOf} 2026-09-22 (D4 §6): this method WAS the
     * single home of the numeric classification, and D4 added a second caller of the same question.
     * Two classifiers for one question is how they drift, so there is now one. Behaviour is
     * unchanged — {@code kindOf} answers {@code NUMERIC} for exactly {@code DOUBLE}/{@code LONG},
     * and every other type (including the null-classifying {@code BOOLEAN}/{@code MISSING}/…) takes
     * the same {@code false} branch it took before.
     *
     * @param type
     *            the declared column type.
     * @return whether the column is numeric.
     */
    private static boolean isNumeric(DataValueType type)
    {
        return ColumnTypeGate.kindOf(type) == ColumnTypeGate.Kind.NUMERIC;
    }


    private static int[] resolveColIds(DataTableMeta meta, List<String> keys)
    {
        int[] ids = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++)
        {
            ids[i] = meta.getColumnIndex(keys.get(i));
        }
        return ids;
    }


    /**
     * One key component of a row: the {@link KeyPart} it contributes to the join key.
     *
     * <p>
     * ⭐⭐ <b>The key is the classified {@code KeyPart} itself, not a rendering.</b> Identity is the
     * sealed type's: a {@code MissingValue} can never collide with a present {@code "."} (the +9
     * 528-finding bug class {@link GroupKeyPolicy.KeyPart} records), and a character {@code "5"} is
     * not a numeric {@code 5} — a mismatch of the two sides' declared kinds is a rule ERROR raised
     * in {@link #keySpec} (D4-R1/R2), unless the entry declares {@code Join_As_String}, which
     * compares present parts as text ({@link #coerce}, D4-R3).
     * </p>
     *
     * <p>
     * ⭐⭐ <b>A {@code MissingValue} is a NORMAL key value</b> (⚑
     * TARGET-INVARIANT(null-free-value-channel); owner, 2026-09-19,
     * {@code PLAN-null-free-value-channel} §9j): <i>"MissingValue can be a join key. We have ruled,
     * that a general flag is available if missing values (incl "") are considered on a join /
     * grouping, but if they are in, then different missing values are different keys."</i> The join
     * surface's flag defaults to KEEP ({@code JKM R4}, owner 2026-09-21: <i>"KEEP is the default
     * and DROP must explicitly be authored if needed"</i>); an authored
     * {@code keep_missings: false} drops the row before any key is built
     * ({@link #anyActiveKeyBlank}). Identity is exact either way ({@code JKM R5}: <i>"a MIS will
     * not join a record with an empty string and a MIS_A will not join a record with a MIS or
     * MIS_B"</i>).
     * </p>
     *
     * <p>
     * {@code JKM R7}: a column absent on <b>this</b> side contributes the type default the spec
     * derived from the other side, exactly as if every row carried a blank cell; a component absent
     * on both sides is inactive and is never asked for.
     * </p>
     */
    private static KeyPart keyPart(@Nullable KeyCellReader[] readers, KeySpec spec, int component,
            int row)
    {
        KeyCellReader reader = readers[component];
        if (reader == null)
        {
            return coerce(spec.absentPart()[component], spec.asString());
        }
        // D6: exactly KEEP_MISSING_KEYS.keyPart(t.getColumn(c).getDataValue(row)), read directly
        // for a present cell (KeyCellReader).
        return coerce(reader.read(row), spec.asString());
    }


    /** Fills {@code out} with the row's active key components, in key order. */
    private static void fillKeyParts(@Nullable KeyCellReader[] readers, KeySpec spec, int row,
            KeyPart[] out)
    {
        int j = 0;
        for (int i = 0; i < readers.length; i++)
        {
            if (spec.active()[i])
            {
                out[j++] = keyPart(readers, spec, i, row);
            }
        }
    }


    /**
     * {@code true} when any <b>active</b> key component of this row is blank — {@code ""} or a
     * {@link MissingValue} — or its column is absent on this side (every row is then blank there).
     *
     * <p>
     * Per component it applies the one-column blank test — {@code colId < 0 || isMissingOrNull},
     * routed through {@link IDataTable#isMissingOrNull(long, int)} so a buffer-backed table hits
     * its typed missing sentinel without a value wrapper — inline, so no one-element {@code int[]}
     * is allocated per component and row. ⚠ It counts a {@code -1} column as blank, which is
     * precisely right for this predicate: an absent column contributes its type default to
     * <em>every</em> row, and a default is blank.
     * </p>
     */
    private static boolean anyActiveKeyBlank(IDataTable t, int[] colIds, KeySpec spec, long row)
    {
        for (int i = 0; i < colIds.length; i++)
        {
            if (!spec.active()[i])
            {
                continue;
            }
            // #5(b) step 1: the one-column blank test, without allocating a one-element int[] per
            // component and row.
            if (colIds[i] < 0 || t.isMissingOrNull(row, colIds[i]))
            {
                return true;
            }
            IDataValue dv = t.getColumn(colIds[i]).getDataValue(row);
            if (!GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(dv).present())
            {
                // An empty string is not "missing" to isMissingOrNull but IS blank for a
                // keep/drop-missing policy (D34 #6 rules "" and every MissingValue together).
                return true;
            }
        }
        return false;
    }
}
