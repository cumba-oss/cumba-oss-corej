package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.exec.GroupKeyPolicy.KeyPart;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.model.JoinType;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.DataValueType;
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
 * with no matching child and binds it to a {@code null} child (so a dotted reference resolves to
 * {@code null} — preserving the engine's historical scalar-lookup behaviour and firing
 * absence/empty checks); {@code inner} drops the unmatched primary row. Keys compare by exact
 * string value, matching pandas' object-dtype merge for CDISC string keys.
 * </p>
 *
 * <p>
 * ⚠ <b>The {@code left} fallback is defensive, not the corpus path.</b> It applies only when
 * {@code Join_Type} is absent, and {@code RulePackageLoader.normalizeJoinTypes} stamps
 * {@code inner} onto every entry that omits it — so a rule that came through the loader always
 * arrives with a value, and only a loader-bypassing rule reached the fallback — in practice the
 * per-dataset {@code CDISC-AD0591-}/{@code GEN-XDVAL-} family minted by the retired
 * {@code CROSS_DATASET_METADATA} generator. Saying this class "defaults to {@code left}" without
 * that qualification is misleading: for the shipped corpus the effective default is {@code inner}.
 * ⚑ <b>That generator is now deleted</b> ({@code plans/done/PLAN-remove-rule-generator.md}), so no
 * <b>production</b> path reaches the fallback any more.
 *
 * <p>
 * ⛔⛔ <b>Removing it was TRIED on 2026-09-15 (owner request) and REVERTED — measured, not
 * assumed.</b> Replacing the implicit default with {@code Objects.requireNonNull(md.getJoinType())}
 * reds <b>28 cases across 9 test classes</b> ({@code KeyMatchRowExpanderTest},
 * {@code JoinCacheConcurrencyTest}, {@code OutputVariableExclusionProjectionTest},
 * {@code RuleCheckLevelsExecutionTest}, the two {@code OperandTemplate*IntegrationTest}s and three
 * probe tests). They build {@link MatchDataset} by hand and never set {@code Join_Type}, so the
 * fallback is unreachable only from <em>production</em> — it is load-bearing for the fixtures.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>And the two defaults disagree, which is why this is not a mechanical fix.</b>
 * {@code RulePackageLoader.normalizeJoinTypes} stamps {@code inner}; this site defaults to
 * {@code left}. A hand-built fixture that omits {@code Join_Type} is therefore asserting a
 * semantics the loader would never have produced for it. Making those fixtures explicit means
 * choosing {@code left} (preserving every current assertion, but pinning a default production
 * cannot reach) or {@code inner} (loader-faithful, but changing what several of them assert —
 * unmatched primary rows would be dropped rather than kept). That is a behavioural decision for the
 * owner, not a cleanup, and it is the same open question as triage finding {@code S2}
 * ({@code plans/done/PLAN-expired-justifications-triage.md}). <b>Settle S2 first; do not retry the
 * deletion on its own.</b>
 * </p>
 * </p>
 *
 * <p>
 * ⚠⚠ <b>The test is a NEGATION</b> — {@code !JoinType.INNER.getJsonValue().equalsIgnoreCase(…)} —
 * i.e. <b>anything that is not {@code inner} is executed as {@code left}</b>, including a value
 * this engine does not understand. Until {@code Fix #236} an authored {@code Join_Type: outer} (or
 * the typo {@code iner}) was therefore run silently as a left join, producing plausible-but-wrong
 * rows and reporting nothing.
 * </p>
 *
 * <p>
 * ✅ {@code Fix #236} closes that at <b>load</b>, not here:
 * {@link net.cumba.corej.core.model.JoinType} is the closed vocabulary and
 * {@code RulePackageLoader.validateEnumFields} files a {@code loadError} for any
 * present-but-unrecognised value, so such a rule reports ERROR and never reaches this expander. The
 * negation below is left exactly as it was — this site's semantics for the two <em>legal</em>
 * values are unchanged, and changing them would move findings on the 38 shipped rules that author
 * {@code left}. ⚠ Adding a third join type still means auditing every {@code inner} comparison
 * site, not adding a branch here.
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


    static @Nullable KeyMatchExpansion expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId)
    {
        return expand(primaryTable, matchDatasets, resolver, ruleId, null);
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
            // Default LEFT, honoring an explicit join_type=inner. Left preserves the engine's
            // historical scalar-lookup behaviour (an unmatched primary row keeps a null-valued
            // joined column) and is what absence/empty checks rely on (e.g. CDISC-AD0053 fires on
            // DM.USUBJID empty for a subject not in DM). ⚠ The default is DEFENSIVE, not the
            // corpus path: RulePackageLoader defaults an absent Join_Type to `inner` (mirroring
            // the Python engine's merge_sdtm_datasets), so a rule loaded through it always arrives
            // with a join type set and only a rule that bypasses the loader reaches this fallback.
            // The corpus does author Join_Type, and every authored value is `left` — never
            // `inner` — which is why left is the safer fallback here. Whether the loader's `inner`
            // default should itself be `left` is an open behavioural question (triage finding S2,
            // plans/done/PLAN-expired-justifications-triage.md), deliberately not settled here.
            // Fix #236: same comparison, now sourced from the JoinType vocabulary so the constant
            // and the load-time gate cannot drift apart. Semantics for `inner` / `left` unchanged.
            boolean left = !JoinType.INNER.getJsonValue().equalsIgnoreCase(md.getJoinType());
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

            Bindings next = new Bindings(size, primaryCol, childCols, ei);
            for (int i = 0; i < size; i++)
            {
                int id = probeKeyId(primaryTable, spec, primaryCol[i], index, probe);
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
     * ({@code D4-R3}) — its rendered text as a {@link KeyPart.Present}.
     *
     * <p>
     * ⛔⛔ <b>Only a PRESENT part is coerced.</b> {@link KeyPart.Missing} and {@link KeyPart#EMPTY}
     * keep their own classification, because that classification is what stops a
     * {@code MissingValue} colliding with a present {@code "."} — the {@code +9 528-finding} bug
     * class this file's own javadoc records. ⚑ {@code Present}'s constructor rejects {@code ""}
     * (GroupKeyPolicy:153), so the coercion cannot manufacture an {@code EMPTY} either.
     * </p>
     *
     * <p>
     * ⚠ Because the rendering cleans a double to <b>12 significant digits</b>, a flagged entry
     * compares its numeric keys at that precision rather than exactly. That is a property the
     * author opts into, and it is the behaviour every entry had before D4.
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
        if (!aAsString || !aPart.present())
        {
            return aPart;
        }
        return new KeyPart.Present(aPart.reportingForm());
    }


    /**
     * The child's key index for {@code spec} — the one build routine, used with and without the
     * shared cache. A row dropped by an authored {@code keep_missings: false} is not indexed.
     */
    private static KeyMatchIndex buildChildIndex(IDataTable child, int childRows, KeySpec spec)
    {
        int[] colIds = spec.childColIds();
        return KeyMatchIndex.build(childRows, row ->
        {
            if (!spec.keepMissings() && anyActiveKeyBlank(child, colIds, spec, row))
            {
                return null;
            }
            if (spec.nActive() == 1)
            {
                return keyPart(child, colIds, spec, spec.singleActive(), row);
            }
            KeyPart[] parts = new KeyPart[spec.nActive()];
            fillKeyParts(child, colIds, spec, row, parts);
            return new KeyMatchIndex.CompositeKey(parts);
        });
    }


    /**
     * The key id of a primary row in {@code index}, or {@link KeyMatchIndex#NOT_FOUND} when the row
     * matches nothing — including a row an authored {@code keep_missings: false} drops (P3: no key
     * object is built for the lookup beyond the parts themselves).
     */
    private static int probeKeyId(IDataTable primary, KeySpec spec, int row, KeyMatchIndex index,
            KeyPart @Nullable [] scratch)
    {
        int[] colIds = spec.primaryColIds();
        if (!spec.keepMissings() && anyActiveKeyBlank(primary, colIds, spec, row))
        {
            return KeyMatchIndex.NOT_FOUND;
        }
        if (scratch == null)
        {
            return index.find(keyPart(primary, colIds, spec, spec.singleActive(), row));
        }
        fillKeyParts(primary, colIds, spec, row, scratch);
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
    private static KeyPart keyPart(IDataTable t, int[] colIds, KeySpec spec, int component, int row)
    {
        if (colIds[component] < 0)
        {
            return coerce(spec.absentPart()[component], spec.asString());
        }
        return coerce(GroupKeyPolicy.KEEP_MISSING_KEYS
                .keyPart(t.getColumn(colIds[component]).getDataValue(row)), spec.asString());
    }


    /** Fills {@code out} with the row's active key components, in key order. */
    private static void fillKeyParts(IDataTable t, int[] colIds, KeySpec spec, int row,
            KeyPart[] out)
    {
        int j = 0;
        for (int i = 0; i < colIds.length; i++)
        {
            if (spec.active()[i])
            {
                out[j++] = keyPart(t, colIds, spec, i, row);
            }
        }
    }


    /**
     * {@code true} when any <b>active</b> key component of this row is blank — {@code ""} or a
     * {@link MissingValue} — or its column is absent on this side (every row is then blank there).
     *
     * <p>
     * Delegates per component to {@link KeyHashing#anyKeyMissing}, which reads the typed buffer's
     * missing sentinel directly (no {@link IDataValue} allocation, no autoboxing). ⚠ It counts a
     * {@code -1} column as blank, which is precisely right for this predicate: an absent column
     * contributes its type default to <em>every</em> row, and a default is blank.
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
            if (KeyHashing.anyKeyMissing(t, new int[]
            {
                    colIds[i]
            }, row))
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
