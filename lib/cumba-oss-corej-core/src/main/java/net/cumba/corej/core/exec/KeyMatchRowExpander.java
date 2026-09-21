package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.exec.GroupKeyPolicy.KeyPart;
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

    private static final String RELREC = "RELREC";

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
        List<MatchDataset> entries = expandableEntries(matchDatasets);
        if (entries.isEmpty())
        {
            return null;
        }

        int nEntries = entries.size();
        long primaryRows = primaryTable.getRowCount();

        // Each binding: [primaryRow, childRow_0, ..., childRow_{n-1}]; childRow = -1 when unbound.
        List<long[]> bindings = new ArrayList<>();
        for (long p = 0; p < primaryRows; p++)
        {
            long[] b = new long[nEntries + 1];
            Arrays.fill(b, -1L);
            b[0] = p;
            bindings.add(b);
        }

        Map<Integer, IDataTable> resolvedChildren = new LinkedHashMap<>();
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
            // 5b-J: the pre-merge Filter (spec §3.3) restricts the child BEFORE the key index is
            // built — a dropped row can never become a join partner, and an unmatched-by-filter
            // primary row reads exactly like an unmatched-by-key one (left keeps it null-bound,
            // inner drops it, D._matched_ reads false).
            child = Objects.requireNonNull(MatchFilter.apply(md, child, ruleId));
            resolvedChildren.put(ei, child);
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
            KeySpec spec = keySpec(primaryTable, child, keys, Objects.requireNonNull(md.getName()),
                    md.keepMissingKeys());
            Map<List<String>, List<Long>> childIndex = buildChildIndex(child, spec);

            List<long[]> next = new ArrayList<>();
            for (long[] b : bindings)
            {
                List<String> keyTuple = tuple(primaryTable, spec.primaryColIds(), spec, b[0]);
                List<Long> matches = keyTuple == null ? null : childIndex.get(keyTuple);
                if (matches == null || matches.isEmpty())
                {
                    if (left)
                    {
                        next.add(b.clone()); // child stays -1 (null-bound)
                    }
                    // inner: drop the unmatched primary row
                }
                else
                {
                    for (long cr : matches)
                    {
                        long[] nb = b.clone();
                        nb[ei + 1] = cr;
                        next.add(nb);
                    }
                }
            }
            bindings = next;
        }

        int n = bindings.size();
        IDataBufferNumeric rowMap = DataBufferFactory.get().createForRange(0,
                Math.max(0, primaryRows - 1L));
        rowMap.setExpectedSize(n);
        for (long[] b : bindings)
        {
            rowMap.addValue(b[0]);
        }
        IDataTable expandedTable = new RelrecExpandedTable(primaryTable, rowMap);

        Map<String, JoinLookup> lookups = new LinkedHashMap<>();
        List<MatchDataset> expandedEntries = new ArrayList<>();
        for (int ei = 0; ei < nEntries; ei++)
        {
            IDataTable child = resolvedChildren.get(ei);
            if (child == null)
            {
                continue; // unresolved child: not expanded, no lookup
            }
            long[] bound = new long[n];
            for (int i = 0; i < n; i++)
            {
                bound[i] = bindings.get(i)[ei + 1];
            }
            String name = Objects.requireNonNull(entries.get(ei).getName());
            lookups.put(name, new KeyMatchExpandedLookup(name, child, bound));
            expandedEntries.add(entries.get(ei));
        }
        return new KeyMatchExpansion(expandedTable, lookups, expandedEntries);
    }


    /**
     * Key-based entries eligible for row expansion: a non-blank {@code Keys} list, not
     * {@code Child:true}, not RELREC, not a SUPP/SQ qualifier dataset (Python pivots those via
     * {@code merge_pivot_supp_dataset} rather than a plain key merge), and not a {@code --}
     * wildcard dataset name (those need name resolution and are left to the existing key-join
     * path).
     */
    private static List<MatchDataset> expandableEntries(@Nullable List<MatchDataset> mds)
    {
        List<MatchDataset> out = new ArrayList<>();
        if (mds == null)
        {
            return out;
        }
        for (MatchDataset md : mds)
        {
            String name = md.getName();
            if (name == null || Boolean.TRUE.equals(md.getChild()) || RELREC.equalsIgnoreCase(name)
                    || name.contains("--") || isSuppOrQualifier(name))
            {
                continue;
            }
            if (md.getKeys() != null && !md.getKeys().isEmpty())
            {
                out.add(md);
            }
        }
        return out;
    }


    /** SUPP / SQ qualifier datasets, which Python merges by pivot, not a plain key merge. */
    private static boolean isSuppOrQualifier(String name)
    {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ");
    }


    private static Map<List<String>, List<Long>> buildChildIndex(IDataTable child, KeySpec spec)
    {
        Map<List<String>, List<Long>> index = new LinkedHashMap<>();
        long rows = child.getRowCount();
        for (long r = 0; r < rows; r++)
        {
            List<String> t = tuple(child, spec.childColIds(), spec, r);
            if (t == null)
            {
                // Only reachable under an authored keep_missings:false — the flag-OFF path.
                continue;
            }
            index.computeIfAbsent(t, _ -> new ArrayList<>()).add(r);
        }
        return index;
    }

    /**
     * The shape of one entry's join key, computed once from the two tables' metadata.
     *
     * <p>
     * ⭐⭐ <b>{@code JKM R7} lives here, not in {@link #tuple}.</b> <i>"Absent columns are treated
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

        private KeySpec(int[] aPrimaryColIds, int[] aChildColIds, KeyPart[] aAbsentPart,
                boolean[] aActive, boolean aKeepMissings)
        {
            primaryColIds = aPrimaryColIds;
            childColIds = aChildColIds;
            absentPart = aAbsentPart;
            active = aActive;
            keepMissings = aKeepMissings;
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
            String childName, boolean keepMissings)
    {
        int[] primaryColIds = resolveColIds(primary.getMetaData(), keys);
        int[] childColIds = resolveColIds(child.getMetaData(), keys);
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
            // The present side's declared type decides what the absent side contributes. A
            // character column's default is the empty string (a PRESENT value, D34 #1); a numeric
            // column's is a MissingValue, since a numeric cell cannot hold "".
            DataValueType type = inPrimary
                    ? primary.getMetaData().getColumn(primaryColIds[i]).getType()
                    : child.getMetaData().getColumn(childColIds[i]).getType();
            absentPart[i] = isNumeric(type) ? KeyPart.missing(MissingValue.MIS) : KeyPart.EMPTY;
        }
        if (nActive == 0)
        {
            throw new DegenerateJoinKeyException(childName, keys);
        }
        return new KeySpec(primaryColIds, childColIds, absentPart, active, keepMissings);
    }


    private static boolean isNumeric(DataValueType type)
    {
        return type == DataValueType.DOUBLE || type == DataValueType.LONG;
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
     * The row's composite join key as a list of <b>rendered</b> {@link KeyPart}s, or {@code null}
     * when the row does not participate — which, since {@code JKM R4}, happens <b>only</b> under an
     * authored {@code keep_missings: false}.
     *
     * <p>
     * ⭐⭐ <b>Why RENDERED and not the {@code KeyPart} itself — this is a deliberate choice on an
     * UNRULED axis, corrected 2026-09-21 in the plan's own review round.</b> The first
     * implementation put {@code KeyPart}s in the key, which is the stronger identity
     * ({@code Present("5")} is then <b>not</b> {@code PresentNumber(5.0)}). ⛔ But this key used to
     * be a {@code \0}-joined {@code getValueAsString()} string, in which a <b>character</b>
     * {@code "5"} on one side and a <b>numeric</b> {@code 5} on the other DID match — routine
     * across providers and across a split-domain union. Narrowing that is {@code D4} in the plan's
     * divergence table, which has <b>four answers and no ruling</b>, and on a
     * {@code Join_Type: left} entry it would mint a NEW false finding for every such row under an
     * {@code empty(FOREIGN.X)} check — the shape of {@code SD1018} and its siblings. ⇒ <b>fix the
     * ruled axis, leave the unruled one where it was</b>: {@code KeyPart} still does the
     * classifying (so {@code MissingValue.MIS} keeps its {@code \u0001}-prefixed rendering and
     * cannot collide with a present {@code "."} — the +9 528-finding bug class), and the rendering
     * keeps {@code D4}'s historical answer.
     * </p>
     *
     * <p>
     * ⚠ The cost is the weaker guarantee, the same one {@code RelrecRowExpander.keyCell} and
     * {@code GroupedResult.buildKey} accept: a rendering is <em>unlikely</em> to collide, a sealed
     * type <em>cannot</em>. ⇒ when {@code D4} is ruled, this becomes one line — drop
     * {@code .reportingForm()} and widen the list's type back.
     * </p>
     *
     * <p>
     * ⭐⭐ <b>The RULED semantics: a {@code MissingValue} is a NORMAL value, and it CAN be a join
     * key</b> (⚑ TARGET-INVARIANT(null-free-value-channel); owner, 2026-09-19, recorded with its
     * derivation in {@code PLAN-null-free-value-channel} §9j). Verbatim: <i>"MissingValue can be a
     * join key. We have ruled, that a general flag is available if missing values (incl "") are
     * considered on a join / grouping, but if they are in, then different missing values are
     * different keys."</i> and <i>"a missing value is not something special at all … So a Missing
     * and a "" are expected values and should be treated as such."</i> Every part of that was
     * already ruled elsewhere: a {@code MissingValue} is <i>"a value with meaning, expected in any
     * column or expression result — not a special case"</i> ({@code D34 #2}); <i>"two missings are
     * equal iff they are the same missing"</i> ({@code D34 #5-2}) is the equality that <i>different
     * missings are different keys</i> rests on, and it is taken EXACTLY rather than tolerantly
     * because keys are <i>"ASSIGNED values, not calculated ones"</i> and <i>"key identity always
     * exact"</i> ({@code D64f}/{@code D64h}); and an empty string is a PRESENT value, not a missing
     * one ({@code D34 #1}/{@code #3}) — it appears in the ruling only because it is governed by the
     * same flag ({@code D34 #6}).
     * </p>
     *
     * <p>
     * ⛔⛔ <b>What THIS implementation does today is NOT that, and the paragraph above must not be
     * read as a description of the code below.</b> Every row whose key tuple has a missing — or
     * absent — component is DROPPED from the join: {@link #buildChildIndex} does not index such a
     * child row, and the primary loop treats it as matching nothing (kept unbound under
     * {@code left}, dropped under {@code inner}). That holds on both sides of the join and under
     * both join types. It is the <i>missings-excluded</i> setting of the ruled flag, <b>hard-coded
     * here rather than authored</b>: {@link MatchDataset} declares {@code Name}, {@code Keys},
     * {@code Wildcard}, {@code Child}, {@code Join_Type} and {@code Filter} — there is <b>no
     * {@code keep_missings} channel on the join surface at all</b>. The two grouping surfaces do
     * have the authored flag and differ only in their DEFAULT: Check-level {@code Grouping:}
     * defaults to {@code GroupKeyPolicy.DROP_MISSING_KEYS} ({@code RuleRunner}), Operation-level
     * {@code group:} to {@code GroupKeyPolicy.KEEP_MISSING_KEYS} ({@code OperationExecutor}), each
     * overridable per rule.
     * </p>
     *
     * <p>
     * ⭐⭐ <b>The join surface's default IS RULED — it is KEEP</b> (owner, 2026-09-21,
     * {@code PLAN-join-key-missing-semantics} §4; register {@code JKM R4}). This paragraph used to
     * end <i>"the join surface's default is therefore genuinely UNRULED, and nothing here may be
     * read as having chosen it"</i>, and the paragraph below used to end <i>"the default that flag
     * takes here is an owner decision"</i>. <b>Both are withdrawn.</b> Verbatim: <i>"From my point
     * of view the DROP is the bug we need to fix. There is no reason to remove a row from the merge
     * because there is a missing value in one of the keys. … Therefore I rule KEEP is the default
     * and DROP must explicitly be authored if needed."</i> ⇒ the hard-coded drop above is not a
     * conservative default awaiting a decision; it is the defect.
     * </p>
     *
     * <p>
     * ⛔ <b>Two further rulings of the same day, because a reader who takes KEEP alone will build
     * the wrong thing:</b> {@code JKM R5} — the flag governs <b>participation only</b>, never
     * identity: <i>"if they are kept, they are kept as separate identities. a MIS will not join a
     * record with an empty string and a MIS_A will not join a record with a MIS or MIS_B."</i> ⇒
     * identity is exact, flag on or off. {@code JKM R7} — an <b>absent</b> key column is
     * <i>present-but-empty</i>: absent on one side contributes the column's type default, absent on
     * <b>both</b> sides drops the component from the key, and <b>all</b> components absent is a
     * rule <b>ERROR</b>, because an empty key is a cartesian product rather than a join.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Implementing R5 here is NOT a matter of deleting the guard below.</b> This method
     * builds a {@code \0}-joined STRING of {@code getValueAsString()}, and
     * {@code MissingValue.toString()} renders its display string — so a participating {@code MIS}
     * would key as {@code "."} and <b>collide with a present text cell containing a literal
     * {@code "."}</b>, {@code NA} with the present string {@code "NA"}. That is the conflation R5
     * forbids, and it is measured: {@link GroupKeyPolicy.KeyPart}'s javadoc records <i>"a naive
     * distinct-as-strings encoding silently turned every missing into a participating value
     * (measured: +9 528 findings, 8 rules)"</i>. ⇒ the key must be built from
     * {@link GroupKeyPolicy#keyPart}, whose sealed type <b>cannot</b> collide by construction.
     * </p>
     *
     * <p>
     * ⚑ <b>Open work</b> — {@code PLAN-join-key-missing-semantics}: wire the flag through
     * {@code Match_Datasets}, and with it {@code KeyHashing.anyKeyMissing}, which has zero callers
     * and is therefore the flag-OFF path of an unfinished feature rather than a guard nobody hooked
     * up. ⚠ One consequence recorded there rather than left to be rediscovered: for a KEY, "normal
     * value" carries a fan-out that a compared variable does not — {@code k} primary and {@code m}
     * joined rows sharing one missing key pair {@code k×m}, where grouping would make one group of
     * {@code k+m}. That is what the flag is <i>for</i>, not an exception to the ruling; ⭐ and under
     * R5 the fan-out is per <b>distinct marker</b>, so mixed blanks give several small blocks
     * rather than one large one.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>A real join behaviour MOVED underneath this guard on 2026-09-18 and nothing recorded
     * it.</b> The {@code isMissingOrInvalid()} test has been here since the initial commit, but
     * what a raw {@code null} CHARACTER cell wraps to changed in the datatable repository's {@code
     * d4edd59} ("a null character cell is {@code MissingValue.MIS}, on both wrap paths"). Before
     * it, such a cell arrived as {@code DataValueString("")} — {@code isMissingOrInvalid() ==
     * false} — so the key was built with an EMPTY SEGMENT and <b>two rows with a null key joined
     * each other</b>. At HEAD they do not join at all. ⇒ The composition changed even though this
     * file did not. ⭐ Reachable on real data: an {@code .rds} dataset whose join-key character
     * column carries an R {@code NA} ({@code factor(..., exclude = NULL)}, stored as an explicit
     * null level by {@code RdataTableProvider.copyFactorData}).
     * </p>
     *
     * <p>
     * ⛔ <b>The behaviour below is UNCHANGED by this correction — only the doctrine sentence was
     * wrong.</b> {@code KeyMatchMissingJoinKeyTest} pins it from both join types plus a fixture
     * control, and removing the guard reds that class; what it pins is <b>what the engine does</b>
     * — one hard-coded setting of the flag — and not the ruled semantics. ⚠ That class' own javadoc
     * still asserts the retired claim and needs the same correction. The mechanical note this
     * file's model cites survives either reading: <i>pandas drops NaN keys</i>
     * ({@link #buildChildIndex}).
     * </p>
     */
    private static @Nullable List<String> tuple(IDataTable t, int[] colIds, KeySpec spec, long row)
    {
        if (!spec.keepMissings() && anyActiveKeyBlank(t, colIds, spec, row))
        {
            // The flag-OFF path of JKM R4, and the first caller KeyHashing.anyKeyMissing has ever
            // had: an authored keep_missings:false drops the row. ⚠ Only ACTIVE components are
            // consulted — an inactive one is absent on both sides and is not part of the key, so
            // asking whether it is blank would drop every row of every such join.
            return null;
        }
        List<String> parts = new ArrayList<>(colIds.length);
        for (int i = 0; i < colIds.length; i++)
        {
            if (!spec.active()[i])
            {
                continue; // R7: absent on both sides -> not part of the key
            }
            if (colIds[i] < 0)
            {
                // R7: absent on THIS side -> the column's type default, exactly as if every row
                // carried a blank cell. It still participates and still compares by identity.
                parts.add(spec.absentPart()[i].reportingForm());
                continue;
            }
            // R5: the identity is CLASSIFIED by the sealed KeyPart — which is what separates a
            // MissingValue from a present "." — and then RENDERED. See the method javadoc for why
            // the rendering, not the KeyPart itself, is what goes into the key.
            parts.add(GroupKeyPolicy.KEEP_MISSING_KEYS
                    .keyPart(t.getColumn(colIds[i]).getDataValue(row)).reportingForm());
        }
        return parts;
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
