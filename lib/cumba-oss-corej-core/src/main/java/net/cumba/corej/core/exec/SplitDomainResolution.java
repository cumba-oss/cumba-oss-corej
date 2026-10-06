package net.cumba.corej.core.exec;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import net.cumba.datatable.merge.SuppMergeSpec;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a {@code Match_Datasets} name / {@code RDOMAIN} value / {@code domain=} literal to a
 * table — <b>the one resolution of a dataset name</b> every surface that turns a name into data
 * goes through. Three name shapes resolve beyond the exact member name:
 *
 * <ul>
 * <li>a <b>two-character domain code</b> ({@code LB}): the exact member name first (unchanged
 * behaviour), else the union of every inventory member whose CDISC domain equals the name (a split
 * domain, Fix #358), else absent;</li>
 * <li>a <b>supplemental family name</b> — {@code SUPP} + two characters ({@code SUPPLB},
 * {@code SUPPAE}) or {@code SQAP} + two characters ({@code SQAPMH}), ignoring case
 * ({@code PLAN-split-supp-resolution} D2): the table of that exact name, <em>always</em> a member
 * (D3), together with every inventory member whose family key
 * ({@link DatasetIdentity#suppFamilyKey}: {@code SUPP} / {@code SQ} + its row-0 {@code RDOMAIN})
 * equals the name — {@code SUPPLBCH} + {@code SUPPLBHE} + {@code SUPPLBUR} answer {@code SUPPLB},
 * {@code SUPPAE1} + {@code SUPPAE2} answer {@code SUPPAE}, {@code SQAPMH1} + {@code SQAPMH2} answer
 * {@code SQAPMH}, a non-IG {@code SUPPAPMH} with {@code RDOMAIN = APMH} answers {@code SQAPMH}
 * (D9). ⚠ The family arm runs <b>before</b> the exact lookup, because an exactly-named
 * {@code SUPPAE} shipped beside {@code SUPPAE1} is one member among several, not the whole family
 * (PG-L12).</li>
 * </ul>
 *
 * Single-member results are the member itself (so an unsplit-but-oddly-named file, e.g.
 * {@code ae_v2} with {@code DOMAIN=AE}, also resolves). Members join in deterministic order
 * (upper-cased member name).
 *
 * <p>
 * <b>The bound is load-bearing — three shapes, nothing else walks.</b> It mirrors
 * {@code ScopeVariableSource} ("Review M3 — bound the walk"):
 * {@link DatasetResolver.WithInventory#tablesForDomain(String)}'s default calls
 * {@code resolve(name)} for <em>every</em> inventory entry, i.e. it loads the whole submission.
 * Without the bound it would fire once per distinct <em>absent</em> dataset name any rule names
 * ({@code ADSL}, typos), not once per split domain or family. The supplemental arm additionally
 * <b>pre-filters by name</b>: it resolves only inventory entries whose name starts {@code SUPP} or
 * {@code SQ} (ignoring case), so a family request never loads a non-supplemental dataset (D2). Do
 * not "optimise" the domain arm into a name-prefix match —
 * {@code CdiscDomainResolver.cdiscDomainOf} is DOMAIN-cell-first for a reason (custom split member
 * names like {@code lbch} carry no {@code LB} digit suffix); and never derive a SUPP family from
 * the name — the heuristic gives {@code SUPPLBC} for {@code SUPPLBCH} (D1).
 * </p>
 *
 * <p>
 * <b>Memoisation (D4).</b> Results (including {@link DomainResolution.Absent} and
 * {@link DomainResolution.Invalid}) are memoised per <em>resolver instance</em>, keyed by the
 * upper-cased name, so the identity-keyed index caches ({@code JoinCache.SharedIndexCache}, the
 * child-match cache) see one stable {@link UnionDataTable} instance per domain or family for the
 * whole run — including on the cache-less study-anchor pass, where {@code LibraryValidator} passes
 * {@code joinCache == null}. The side-table holds the resolver weakly so a run's memo dies with its
 * resolver.
 * </p>
 *
 * <p>
 * ⚠ <b>Retention consequence of the memo.</b> The memo's values hold the member tables
 * <em>strongly</em> for the resolver's lifetime, so once a split domain or family resolves, its
 * members can no longer be reclaimed by the resolver's {@code SoftReference} dataset cache until
 * the validation run ends. That is the price of the stable-instance guarantee the identity-keyed
 * index caches require (D4); a {@code SoftReference} memo would trade it away and silently rebuild
 * unions (and their shared indexes) under heap pressure. Priced and accepted — see the plan's §8.
 * </p>
 *
 * <h2>The one chooser of SUPP sources</h2>
 * <p>
 * {@link #suppMergeSources} decides which supplemental datasets qualify a base dataset — for the
 * merged view's primary and its keyed partners ({@code MergedEvaluation}), for the merged-schema
 * QNAM set the existence surfaces read ({@code SuppQualifiers}: the dotted {@code var_exists}, a
 * qualified {@code Requirements.Variables} entry) and for the scope gate's bare and typed probes
 * ({@code ScopeVariableSource}). One chooser, so what an existence surface reports present is
 * exactly what the view delivers (D4 (b)).
 * </p>
 */
@CustomLog
public final class SplitDomainResolution
{

    /**
     * Length of an SDTM domain code — the only name shape for which the split-domain union can
     * possibly match, since {@code tablesForDomain} compares against each table's {@code DOMAIN}
     * cell (else the unsplit member name).
     */
    private static final int DOMAIN_CODE_LENGTH = 2;

    /** Length of a supplemental family name: {@code SUPP} / {@code SQAP} + a domain code (D2). */
    private static final int SUPP_FAMILY_LENGTH = 6;

    private static final DomainResolution.Absent ABSENT = new DomainResolution.Absent();

    /**
     * The tail of every {@link DomainResolution.Invalid} message — a split domain's and a split
     * supplemental family's alike: the rules repository's {@code ViolationNormaliser} classifies
     * the ERROR by this suffix ({@code EngineErrorMessageContractTest} pins it here).
     */
    private static final String INVALID_TAIL = "; the domain cannot be joined";

    /**
     * Test hook — the sensitivity plant of {@code SplitSuppFamilyResolutionTest}: {@code false}
     * turns the supplemental-family arm off, so every name resolves as before
     * {@code PLAN-split-supp-resolution}, and every per-site case must then fail.
     */
    private static volatile boolean suppFamilyArm = true;

    /**
     * Per-resolver memo — see the class Javadoc. Outer map: weak resolver identity (resolvers are
     * stable for a validation run and shared across its dataset threads); inner map: upper-cased
     * domain code or family name → resolution. {@code Collections.synchronizedMap} keeps the weak
     * outer map's {@code computeIfAbsent} atomic; the long-running inventory walk happens inside
     * the inner {@link ConcurrentHashMap}, so it runs once per (resolver, name).
     */
    private static final Map<DatasetResolver, ConcurrentHashMap<String, DomainResolution>> MEMO = Collections
            .synchronizedMap(new WeakHashMap<>());

    /**
     * Per-resolver memo of {@link #membersOf}: the same weak-outer / concurrent-inner shape as
     * {@link #MEMO}, keyed by the upper-cased domain, valued by the sorted, unmodifiable member
     * list (not a {@link UnionDataTable}: a caller that reads each member separately — the
     * parent-model walk — must not pay for, or fail on, a union it never joins).
     */
    private static final Map<DatasetResolver, ConcurrentHashMap<String, List<IDataTable>>> MEMBERS = Collections
            .synchronizedMap(new WeakHashMap<>());

    /**
     * Per-resolver memo of {@link #suppFamilyMembers}: keyed by the upper-cased family name, valued
     * by the sorted, unmodifiable member list — the gates and the string channels read the members,
     * never the union (an {@code Invalid} family still answers its members' columns).
     */
    private static final Map<DatasetResolver, ConcurrentHashMap<String, List<IDataTable>>> FAMILY_MEMBERS = Collections
            .synchronizedMap(new WeakHashMap<>());

    private SplitDomainResolution()
    {
    }


    /**
     * Test hook: turns the supplemental-family arm on or off (see {@link #suppFamilyArm}). Never
     * called by production code.
     *
     * @param aEnabled
     *            {@code false} to resolve every name as before the family arm existed
     */
    static void suppFamilyArmForTests(boolean aEnabled)
    {
        suppFamilyArm = aEnabled;
    }


    /** Whether the supplemental-family arm is on (the one read of the test hook). */
    private static boolean familyArmed()
    {
        return suppFamilyArm;
    }


    /**
     * Whether a name has the supplemental-family shape (D2): {@code SUPP} + two characters or
     * {@code SQAP} + two characters, ignoring case — {@code SUPPLB}, {@code SQAPMH}; never
     * {@code SUPPLBCH}, {@code SQAPMH1}, {@code SQAPFAMH} (members) or {@code SUPPQUAL}.
     *
     * @param name
     *            the dataset name
     * @return whether the family arm resolves it
     */
    static boolean isSuppFamilyName(@Nullable String name)
    {
        if (name == null || name.length() != SUPP_FAMILY_LENGTH)
        {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQAP");
    }


    /**
     * Resolves {@code name} per the class contract.
     *
     * @param resolver
     *            the run's dataset resolver.
     * @param name
     *            the {@code Match_Datasets.Name} / {@code RDOMAIN} value / dotted qualifier.
     * @param ruleId
     *            CORE id for the {@code [<ruleId>]} log prefix; {@code null} renders as
     *            {@code [?]}.
     * @return never {@code null}.
     */
    static DomainResolution resolve(DatasetResolver resolver, @Nullable String name,
            @Nullable String ruleId)
    {
        if (name == null)
        {
            return ABSENT;
        }
        if (familyArmed() && isSuppFamilyName(name)
                && resolver instanceof DatasetResolver.WithInventory inv)
        {
            // PLAN-split-supp-resolution D2 / D3: the family arm BEFORE the exact lookup — the
            // exactly-named table is one member among the RDOMAIN-keyed ones (PG-L12); a
            // non-inventory resolver cannot walk and keeps the exact lookup below
            String key = name.toUpperCase(Locale.ROOT);
            return MEMO.computeIfAbsent(resolver, _ -> new ConcurrentHashMap<>())
                    .computeIfAbsent(key, _ -> familyUnionOf(inv, key, ruleId));
        }
        // ⭐ W3d of PLAN-merged-evaluation-table (P-19 / MET #6): a dataset name is matched
        // ignoring letter case — the entry that equals the name ignoring case answers for a
        // resolver whose own policy is exact (the product's upper-case every name already); since
        // T1 for every resolver, not only an inventory one (byName)
        IDataTable named = byName(resolver, name);
        if (named != null)
        {
            return new DomainResolution.Table(named);
        }
        if (!(resolver instanceof DatasetResolver.WithInventory inv))
        {
            return ABSENT;
        }
        if (name.length() != DOMAIN_CODE_LENGTH)
        {
            return ABSENT; // the R-4 bound: never walk the inventory for a non-domain-code name
        }
        String key = name.toUpperCase(Locale.ROOT);
        return MEMO.computeIfAbsent(resolver, _ -> new ConcurrentHashMap<>()).computeIfAbsent(key,
                _ -> unionOf(inv, key, ruleId));
    }


    /**
     * ⭐ <b>The one resolution of a dataset NAME</b> ({@code PLAN-merged-evaluation-table} W3d / T1;
     * owner 2026-09-28: names — columns and datasets — match case-insensitively): the dataset the
     * resolver answers for {@code name} as written, else the one it answers for the name's upper-
     * or lower-case spelling. Every site that turns a dataset name into a table goes through it —
     * the join side ({@link #resolve}), the scope / requirements gates
     * ({@code ScopeVariableSource}), and the channels that carry a dataset name as a STRING:
     * {@code read_value(domain=)}, {@code referenced_dataset_variables(name=)} and the
     * {@code var_*} {@code dataset=} keyword ({@code QualifiedNameUniformityTest}'s C5q, an exact
     * empty set). No split-domain union, no family and no inventory walk here — that is
     * {@link #resolve}'s, under its bound; a string channel that must see a split domain or a
     * supplemental family resolves through {@link #resolveTableOrThrow} or reads
     * {@link #suppFamilyMembers} (D4 (a)).
     *
     * @param resolver
     *            the run's resolver
     * @param name
     *            the dataset name as written
     * @return the table, or {@code null} when no spelling of the name resolves
     */
    static @Nullable IDataTable byName(DatasetResolver resolver, String name)
    {
        IDataTable exact = resolver.resolve(name);
        return exact != null ? exact : resolveIgnoringCase(resolver, name);
    }


    /**
     * The dataset equal to {@code name} ignoring letter case, resolved — {@code null} when none
     * differs from {@code name} by case alone (the exact spelling is the caller's).
     *
     * @param inv
     *            the resolver
     * @param name
     *            the dataset name as written
     * @return the table, or {@code null}
     */
    static @Nullable IDataTable resolveIgnoringCase(DatasetResolver inv, String name)
    {
        // the two canonical spellings only — upper case is what the IG and the product's
        // resolvers use, lower case what a file-named inventory carries; never an inventory walk
        // (the R-4 bound: a miss on a non-domain-code name must not touch the inventory)
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.equals(name))
        {
            IDataTable t = inv.resolve(upper);
            if (t != null)
            {
                return t;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(name) ? null : inv.resolve(lower);
    }


    /**
     * Drop-in replacement for a join-side {@code resolver.resolve(name)} call:
     * {@link DomainResolution.Table} → the table, {@link DomainResolution.Absent} → {@code null}
     * (the caller's existing "not available" path), {@link DomainResolution.Invalid} → an
     * {@link InvalidJoinedDomainException}, which rule execution turns into a
     * {@code RuleExecutionStatus.ERROR} result (ruling 1).
     */
    public static @Nullable IDataTable resolveTableOrThrow(DatasetResolver resolver,
            @Nullable String name, @Nullable String ruleId)
    {
        return switch (resolve(resolver, name, ruleId))
        {
        case DomainResolution.Table t -> t.table();
        case DomainResolution.Absent _ -> null;
        case DomainResolution.Invalid inv -> throw new InvalidJoinedDomainException(inv.domain(),
                inv.message());
        };
    }


    /**
     * Presence fact for {@code ds_exists} / {@code AbsentDatasetSkip} /
     * {@code Requirements.Datasets}: the domain or supplemental family is part of the submission —
     * exactly, as a split domain, or as a split SUPP / SQAP family. A split whose members cannot be
     * unioned ({@link DomainResolution.Invalid}) is still <em>present</em>: its data ships with the
     * submission, the union merely cannot be joined.
     */
    static boolean isPresentAsDomain(DatasetResolver resolver, @Nullable String name)
    {
        return !(resolve(resolver, name, null) instanceof DomainResolution.Absent);
    }


    /**
     * Every inventory member whose data-driven domain ({@code CdiscDomainResolver.cdiscDomainOf})
     * equals {@code domain} (case-insensitive), in deterministic order (upper-cased member name —
     * never the inventory's iteration order, which for the production resolver is a
     * {@code Map.copyOf} key set and differs per JVM). The walk is fault-tolerant per entry, as
     * {@link #unionOf}'s: a dataset whose supplier throws cannot be a member and never turns the
     * walk into a rule ERROR. Memoised per (resolver, upper-cased domain) — the walk resolves every
     * inventory entry, so it runs once per run and domain, not once per rule. Unlike
     * {@link #resolve} there is no two-character bound: the caller decides when to walk.
     *
     * @param inv
     *            the run's inventory resolver.
     * @param domain
     *            the data-driven domain to collect members for.
     * @param ruleId
     *            CORE id for the log prefix; {@code null} renders as {@code [?]}.
     * @return the members, sorted, unmodifiable; empty when none.
     */
    static List<IDataTable> membersOf(DatasetResolver.WithInventory inv, String domain,
            @Nullable String ruleId)
    {
        String key = domain.toUpperCase(Locale.ROOT);
        return MEMBERS.computeIfAbsent(inv, _ -> new ConcurrentHashMap<>()).computeIfAbsent(key,
                _ -> List.copyOf(walkMembers(inv, key, ruleId)));
    }


    /**
     * The members of a supplemental family (D3 of {@code PLAN-split-supp-resolution}): the table
     * named exactly {@code family} (whatever its rows or its {@code RDOMAIN} — a zero-row or
     * empty-{@code RDOMAIN} {@code SUPPPC} keeps answering for {@code SUPPPC}, PG-H2), plus every
     * inventory member whose {@link DatasetIdentity#suppFamilyKey} equals it, de-duplicated, sorted
     * by upper-cased member name. The walk resolves only {@code SUPP} / {@code SQ}-named entries
     * (the D2 pre-filter) and is fault-tolerant per entry, like {@link #membersOf}. Memoised per
     * (resolver, upper-cased family) — one walk per run and family. For the readers of members: the
     * scope gate's metas, the string channels, the merge-source chooser — never the union (an
     * {@code Invalid} family still answers its members' columns, D3).
     *
     * @param inv
     *            the run's inventory resolver
     * @param family
     *            a family name of the D2 shape ({@code SUPPLB}, {@code SQAPMH}); any other name
     *            answers the exactly-named table alone
     * @param ruleId
     *            CORE id for the log prefix; {@code null} renders as {@code [?]}
     * @return the members, sorted, unmodifiable; empty when none
     */
    static List<IDataTable> suppFamilyMembers(DatasetResolver.WithInventory inv, String family,
            @Nullable String ruleId)
    {
        if (!familyArmed())
        {
            return exactOnly(inv, family); // the test hook: the exactly-named table alone
        }
        String key = family.toUpperCase(Locale.ROOT);
        return FAMILY_MEMBERS.computeIfAbsent(inv, _ -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, _ -> List.copyOf(walkSuppFamily(inv, key, ruleId)));
    }


    /**
     * ⭐ <b>The one chooser of SUPP sources</b> (D4 (b), D11, D12 of
     * {@code PLAN-split-supp-resolution}; Q5 ruled (b)): the supplemental datasets that qualify
     * {@code base}, as member <b>tables</b>, in the order the merge feeds them —
     *
     * <ol>
     * <li>the dataset's own {@code SUPP<dataset>} ({@link SuppMergeSpec#suppNameOf}:
     * {@code SUPPLBCH} for {@code LBCH}), by exact name — the IG's most specific name (P-7);</li>
     * <li>plus the members of the domain's family {@code SUPP<domain>} ({@link #suppFamilyMembers}:
     * {@code SUPPAE1} + {@code SUPPAE2} for an {@code AE} base, the exact {@code SUPPAE} among
     * them), <b>minus</b> every member named {@code SUPP<m>} for an inventory member {@code m} of
     * the same domain ({@link #membersOf}) that the base does not contain — those pair through
     * their own dataset (Q5 (b), D12): a base whose name is its domain code (an unsplit {@code AE},
     * or a keyed partner resolving as the split union {@code LB}) contains every member and is fed
     * the whole family; any other base contains only itself ({@code LBCH} ⇒ {@code SUPPLBCH} + the
     * domain-level members; {@code AE1} ⇒ its own {@code SUPPAE1});</li>
     * <li>minus the base itself, de-duplicated by identity — no member is fed twice (R1).</li>
     * </ol>
     *
     * Empty for a dataset that has no supplemental ({@link DatasetIdentity#hasNoSupplemental}: a
     * SUPP / SQ / RELREC base) or whose domain is unknown. A resolver without an inventory keeps
     * today's two IG names by exact lookup. The D12 exclusion walks {@code membersOf(domain)} (one
     * memoised inventory walk per domain) only when it can remove something: the family has a
     * non-exact member and the base is not its whole domain (F4).
     *
     * <p>
     * Every site that chooses SUPP sources calls this — the merged view's primary and keyed
     * partners and the SUPP-qualifier key refusal ({@code MergedEvaluation}), the merged-schema
     * QNAM set ({@code SuppQualifiers}) and the scope gate's bare and typed probes
     * ({@code ScopeVariableSource}) — so an existence answer and the view agree member for member.
     * </p>
     *
     * @param resolver
     *            the run's resolver
     * @param base
     *            the dataset the sources qualify, or {@code null} when the study does not ship it
     *            (a qualified requirement on an absent dataset — its SUPP still answers)
     * @param dataset
     *            the base's name as written
     * @param domain
     *            the base's domain code
     * @param ruleId
     *            CORE id for the log prefix; {@code null} renders as {@code [?]}
     * @return the member tables, in feeding order, unmodifiable; empty when none
     */
    static List<IDataTable> suppMergeSources(DatasetResolver resolver, @Nullable IDataTable base,
            String dataset, String domain, @Nullable String ruleId)
    {
        if (dataset.isEmpty() || domain.isBlank() || DatasetIdentity.hasNoSupplemental(dataset))
        {
            return List.of();
        }
        Set<IDataTable> out = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IDataTable> ordered = new ArrayList<>(2);
        String own = SuppMergeSpec.suppNameOf(dataset);
        String family = SuppMergeSpec.suppNameOf(domain);
        if (!(resolver instanceof DatasetResolver.WithInventory inv))
        {
            // no inventory: the two IG names by exact lookup, as before the family arm
            for (String name : SuppMergeSpec.defaultSuppNames(dataset, domain))
            {
                IDataTable t = byName(resolver, name);
                if (t != null && t != base && out.add(t))
                {
                    ordered.add(t);
                }
            }
            return Collections.unmodifiableList(ordered);
        }
        if (!own.equalsIgnoreCase(family))
        {
            IDataTable ownSupp = byName(inv, own);
            if (ownSupp != null && ownSupp != base && out.add(ownSupp))
            {
                ordered.add(ownSupp);
            }
        }
        List<IDataTable> members = isSuppFamilyName(family) ? suppFamilyMembers(inv, family, ruleId)
                : exactOnly(inv, family);
        boolean wholeDomain = dataset.equalsIgnoreCase(domain);
        List<IDataTable> siblings = null; // membersOf(domain), walked only when it can exclude
        for (IDataTable member : members)
        {
            if (member == base || out.contains(member))
            {
                continue;
            }
            String memberName = member.getMetaData().getName();
            boolean exact = memberName != null && memberName.equalsIgnoreCase(family);
            if (!exact && !wholeDomain)
            {
                if (siblings == null)
                {
                    siblings = membersOf(inv, domain, ruleId);
                }
                if (pairsWithAnotherMember(memberName, dataset, siblings))
                {
                    continue; // D12: SUPP<m> of a sibling the base does not contain
                }
            }
            out.add(member);
            ordered.add(member);
        }
        return Collections.unmodifiableList(ordered);
    }


    /** The exactly-named table alone, as a one-element list (the family arm off). */
    private static List<IDataTable> exactOnly(DatasetResolver resolver, String name)
    {
        IDataTable t = byName(resolver, name);
        return t == null ? List.of() : List.of(t);
    }


    /**
     * D12: whether {@code memberName} is {@code SUPP<m>} (ignoring case) of a domain member
     * {@code m} other than {@code dataset} — such a member pairs through its own dataset, not
     * through the family.
     */
    private static boolean pairsWithAnotherMember(@Nullable String memberName, String dataset,
            List<IDataTable> siblings)
    {
        if (memberName == null)
        {
            return false;
        }
        for (IDataTable sibling : siblings)
        {
            String siblingName = sibling.getMetaData().getName();
            if (siblingName != null && !siblingName.equalsIgnoreCase(dataset)
                    && memberName.equalsIgnoreCase(SuppMergeSpec.suppNameOf(siblingName)))
            {
                return true;
            }
        }
        return false;
    }


    private static DomainResolution unionOf(DatasetResolver.WithInventory inv, String domain,
            @Nullable String ruleId)
    {
        return unionOrInvalid(walkMembers(inv, domain, ruleId), domain, "split domain", ruleId);
    }


    /** The family arm of {@link #resolve}: the memoised members, unioned (D3). */
    private static DomainResolution familyUnionOf(DatasetResolver.WithInventory inv, String key,
            @Nullable String ruleId)
    {
        return unionOrInvalid(suppFamilyMembers(inv, key, ruleId), key, "split supplemental family",
                ruleId);
    }


    /**
     * The resolution of a member list: none ⇒ absent, one ⇒ that member, several ⇒ the union in
     * member order, a union that fails ⇒ {@link DomainResolution.Invalid} (ruling 1).
     */
    private static DomainResolution unionOrInvalid(List<IDataTable> members, String name,
            String what, @Nullable String ruleId)
    {
        if (members.isEmpty())
        {
            return ABSENT;
        }
        if (members.size() == 1)
        {
            return new DomainResolution.Table(members.get(0));
        }
        LOGGER.log(Level.DEBUG, "[{0}] {1} {2} is split into {3} members ({4}) — joining the union",
                ruleIdOr(ruleId), what, name, members.size(), names(members));
        try
        {
            return new DomainResolution.Table(
                    new UnionDataTable(name, members.toArray(IDataTable[]::new)));
        }
        catch (IllegalArgumentException e)
        {
            // Ruling 1 — a malformed split (e.g. a column type clash across members) is a
            // submission defect the sponsor must see: the rule reports ERROR, never a coerced
            // union. Cached like every other result, so the diagnosis is produced once per
            // domain, not once per rule. ⚠ The message names no resolution surface: the memo
            // means the FIRST caller's message serves every later one, and the domain can be
            // reached as a Match_Datasets name, an RDOMAIN value, or a dotted Check reference
            // alike (review F4). A split SUPP family whose members clash is Invalid the same way
            // (PLAN-split-supp-resolution D3): present, a reader's ERROR, refused by the merge.
            String message = "'" + name + "' resolves to a " + what + " whose " + e.getMessage()
                    + INVALID_TAIL;
            LOGGER.log(Level.WARNING, "[{0}] {1}", ruleIdOr(ruleId), message);
            return new DomainResolution.Invalid(name, message);
        }
    }


    /** The fault-tolerant, sorted member walk shared by {@link #unionOf} and {@link #membersOf}. */
    private static List<IDataTable> walkMembers(DatasetResolver.WithInventory inv, String domain,
            @Nullable String ruleId)
    {
        // Same walk as DatasetResolver.WithInventory.tablesForDomain, but fault-tolerant per
        // entry: a dataset whose supplier throws (a corrupt file) simply cannot be a member.
        // Load-bearing for the study-anchor pass — a presence rule's ds_exists("DM") must keep
        // answering "absent" on a study whose other datasets fail to open, not convert the walk's
        // load failure into a rule ERROR (StudyAnchorPassTest.studyRulesFireWhenEveryDataset
        // FailsToLoad pins it).
        List<IDataTable> members = new ArrayList<>();
        for (String dsName : inv.availableDatasets())
        {
            try
            {
                IDataTable table = inv.resolve(dsName);
                if (table != null && domain.equalsIgnoreCase(
                        net.cumba.corej.core.metadata.CdiscDomainResolver.cdiscDomainOf(table)))
                {
                    members.add(table);
                }
            }
            catch (RuntimeException e)
            {
                LOGGER.log(Level.DEBUG,
                        "[{0}] skipping unreadable dataset {1} while resolving domain {2}: {3}",
                        ruleIdOr(ruleId), dsName, domain, e.toString());
            }
        }
        members.sort(Comparator.comparing(SplitDomainResolution::upperNameOf));
        return members;
    }


    /**
     * The family walk (D3): the exactly-named table first, then every {@code SUPP} / {@code SQ}
     * -named inventory entry (the D2 pre-filter — nothing else is resolved) whose
     * {@link DatasetIdentity#suppFamilyKey} is the family; fault-tolerant per entry, sorted.
     */
    private static List<IDataTable> walkSuppFamily(DatasetResolver.WithInventory inv, String key,
            @Nullable String ruleId)
    {
        List<IDataTable> members = new ArrayList<>();
        IDataTable exact = byName(inv, key);
        if (exact != null)
        {
            members.add(exact);
        }
        for (String dsName : inv.availableDatasets())
        {
            if (!isSuppOrSqName(dsName))
            {
                continue; // the D2 pre-filter: a non-supplemental entry is never resolved
            }
            boolean exactName = dsName.equalsIgnoreCase(key);
            if (exactName && exact != null)
            {
                continue; // the exactly-named entry is already a member
            }
            try
            {
                IDataTable table = inv.resolve(dsName);
                // the exactly-named table is a member whatever its rows (D3); any other entry
                // joins by its RDOMAIN-decided key alone (D1, D5)
                if (table != null && table != exact
                        && (exactName || key.equals(DatasetIdentity.suppFamilyKey(table))))
                {
                    members.add(table);
                }
            }
            catch (RuntimeException e)
            {
                LOGGER.log(Level.DEBUG,
                        "[{0}] skipping unreadable dataset {1} while resolving family {2}: {3}",
                        ruleIdOr(ruleId), dsName, key, e.toString());
            }
        }
        members.sort(Comparator.comparing(SplitDomainResolution::upperNameOf));
        return members;
    }


    /** The D2 pre-filter: a {@code SUPP} / {@code SQ}-named inventory entry, ignoring case. */
    private static boolean isSuppOrSqName(String name)
    {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ");
    }


    private static String upperNameOf(IDataTable table)
    {
        String name = table.getMetaData().getName();
        return name != null ? name.toUpperCase(Locale.ROOT) : "";
    }


    private static String names(List<IDataTable> members)
    {
        StringJoiner sj = new StringJoiner(",");
        for (IDataTable t : members)
        {
            String name = t.getMetaData().getName();
            sj.add(name != null ? name : "?");
        }
        return sj.toString();
    }


    private static String ruleIdOr(@Nullable String ruleId)
    {
        return ruleId != null ? ruleId : "?";
    }

}
