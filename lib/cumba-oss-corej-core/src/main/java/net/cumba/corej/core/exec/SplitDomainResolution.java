package net.cumba.corej.core.exec;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a {@code Match_Datasets} name / {@code RDOMAIN} value to a table: the exact member name
 * first (unchanged behaviour), else — for a two-character domain code only — the union of every
 * inventory member whose CDISC domain equals the name (a split domain), else absent. Single-member
 * results are the member itself (so an unsplit-but-oddly-named file, e.g. {@code ae_v2} with
 * {@code DOMAIN=AE}, also resolves). Members join in deterministic order (upper-cased member name).
 *
 * <p>
 * <b>The two-character bound is load-bearing.</b> It mirrors {@code ScopeVariableSource}
 * ({@code DOMAIN_CODE_LENGTH}, "Review M3 — bound the walk"):
 * {@link DatasetResolver.WithInventory#tablesForDomain(String)}'s default calls
 * {@code resolve(name)} for <em>every</em> inventory entry, i.e. it loads the whole submission.
 * Without the bound it would fire once per distinct <em>absent</em> dataset name any rule names
 * ({@code ADSL}, {@code SUPP--} expansions, typos), not once per split domain. Do not "optimise" it
 * into a name-prefix match — {@code CdiscDomainResolver.cdiscDomainOf} is DOMAIN-cell-first for a
 * reason (custom split member names like {@code lbch} carry no {@code LB} digit suffix).
 * </p>
 *
 * <p>
 * <b>Memoisation (D4).</b> Results (including {@link DomainResolution.Absent} and
 * {@link DomainResolution.Invalid}) are memoised per <em>resolver instance</em>, keyed by the
 * upper-cased domain code, so the identity-keyed index caches ({@code JoinCache.SharedIndexCache},
 * the child-match cache) see one stable {@link UnionDataTable} instance per domain for the whole
 * run — including on the cache-less study-anchor pass, where {@code LibraryValidator} passes
 * {@code joinCache == null}. The side-table holds the resolver weakly so a run's memo dies with its
 * resolver.
 * </p>
 *
 * <p>
 * ⚠ <b>Retention consequence of the memo.</b> The memo's values hold the member tables
 * <em>strongly</em> for the resolver's lifetime, so once a split domain resolves, its members can
 * no longer be reclaimed by the resolver's {@code SoftReference} dataset cache until the validation
 * run ends. That is the price of the stable-instance guarantee the identity-keyed index caches
 * require (D4); a {@code SoftReference} memo would trade it away and silently rebuild unions (and
 * their shared indexes) under heap pressure. Priced and accepted — see the plan's §8.
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

    private static final DomainResolution.Absent ABSENT = new DomainResolution.Absent();

    /**
     * Per-resolver memo — see the class Javadoc. Outer map: weak resolver identity (resolvers are
     * stable for a validation run and shared across its dataset threads); inner map: upper-cased
     * domain code → resolution. {@code Collections.synchronizedMap} keeps the weak outer map's
     * {@code computeIfAbsent} atomic; the long-running inventory walk happens inside the inner
     * {@link ConcurrentHashMap}, so it runs once per (resolver, domain).
     */
    private static final Map<DatasetResolver, ConcurrentHashMap<String, DomainResolution>> MEMO = Collections
            .synchronizedMap(new WeakHashMap<>());

    private SplitDomainResolution()
    {
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
        IDataTable exact = resolver.resolve(name);
        if (exact != null)
        {
            return new DomainResolution.Table(exact);
        }
        if (!(resolver instanceof DatasetResolver.WithInventory inv)
                || name.length() != DOMAIN_CODE_LENGTH)
        {
            return ABSENT; // the R-4 bound: never walk the inventory for a non-domain-code name
        }
        String key = name.toUpperCase(Locale.ROOT);
        return MEMO.computeIfAbsent(resolver, _ -> new ConcurrentHashMap<>()).computeIfAbsent(key,
                _ -> unionOf(inv, key, ruleId));
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
     * Presence fact for {@code ds_exists} / {@code AbsentDatasetSkip}: the domain is part of the
     * submission — exactly, or as a split domain. A split domain whose members cannot be unioned
     * ({@link DomainResolution.Invalid}) is still <em>present</em>: its data ships with the
     * submission, the union merely cannot be joined.
     */
    static boolean isPresentAsDomain(DatasetResolver resolver, @Nullable String name)
    {
        return !(resolve(resolver, name, null) instanceof DomainResolution.Absent);
    }


    private static DomainResolution unionOf(DatasetResolver.WithInventory inv, String domain,
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
        if (members.isEmpty())
        {
            return ABSENT;
        }
        members.sort(Comparator.comparing(SplitDomainResolution::upperNameOf));
        if (members.size() == 1)
        {
            return new DomainResolution.Table(members.get(0));
        }
        LOGGER.log(Level.DEBUG,
                "[{0}] domain {1} is split into {2} members ({3}) — joining the union",
                ruleIdOr(ruleId), domain, members.size(), names(members));
        try
        {
            return new DomainResolution.Table(
                    new UnionDataTable(domain, members.toArray(IDataTable[]::new)));
        }
        catch (IllegalArgumentException e)
        {
            // Ruling 1 — a malformed split (e.g. a column type clash across members) is a
            // submission defect the sponsor must see: the rule reports ERROR, never a coerced
            // union. Cached like every other result, so the diagnosis is produced once per
            // domain, not once per rule. ⚠ The message names no resolution surface: the memo
            // means the FIRST caller's message serves every later one, and the domain can be
            // reached as a Match_Datasets name, an RDOMAIN value, or a dotted Check reference
            // alike (review F4).
            String message = "'" + domain + "' resolves to a split domain whose " + e.getMessage()
                    + "; the domain cannot be joined";
            LOGGER.log(Level.WARNING, "[{0}] {1}", ruleIdOr(ruleId), message);
            return new DomainResolution.Invalid(domain, message);
        }
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
