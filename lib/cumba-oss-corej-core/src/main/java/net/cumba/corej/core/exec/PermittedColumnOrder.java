package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.cumba.corej.core.metadata.CdiscDomainResolver;
import net.cumba.corej.core.metadata.SdtmObservationClasses;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The reference order behind {@code get_permitted_column_order()}
 * ({@code PLAN-permitted-addition-position}): the dataset's model order {@code O}
 * ({@code get_model_column_order()}) with every role-known additional variable the dataset carries
 * placed where SDTMIG sec. 4.1.1.4 puts it — <i>"Variables for the three general observation
 * classes must be ordered with Identifiers first, followed by the Topic, Qualifier, and Timing
 * variables. Within each role, variables must be ordered as shown in SDTM: Tables 2.2.1 …
 * 2.2.5."</i> — which the IG applies to the additions of the special-purpose datasets too (DM
 * Assumption 9; SENDIG sec. 4.1.2, not contrary, owner N4).
 *
 * <h2>The composition ({@link #resolve})</h2>
 * <ol>
 * <li><b>{@code O}</b> = {@link MetadataProvider#getStandardModelVariables}; {@code null} / empty
 * answers {@code null} and the caller SKIPs. ⭐ {@code O} is the <b>only</b> input that can SKIP
 * (P10): every other missing input answers {@code O} unchanged — today's verdict.</li>
 * <li><b>The class gate</b> (owner N3 (a), P8): the class {@code O} was built from —
 * {@code getDatasetClass(null, cdiscDomain)}, whose {@code null} member skips the Define-XML tier —
 * normalised once; {@code SPECIAL PURPOSE} and {@code SPECIAL-PURPOSE} open it, anything else
 * answers {@code O}. A define declaring {@code AE} special-purpose therefore cannot open it (P5
 * rests on this gate alone).</li>
 * <li><b>The role tables</b>: the model walks of the three general classes, each through the seam
 * {@code get_model_filtered_variables(model_class=)} reads (the class seam, else the legacy
 * class-keyed map with {@code --} substituted), never its SKIP disposition. The <b>Findings</b>
 * walk {@code T} decides WHICH columns are role-known additions (owner N1, P2 — the source the
 * {@code CG0013} siblings use); an empty {@code T} answers {@code O}.</li>
 * <li><b>The table ordinals</b> (P2 refined at phase 0, coordinator 2026-10-07): Topic and
 * qualifier ordinals are {@code T}'s; Identifier and Timing ordinals come from the <b>union</b> of
 * the three walks (the unrestricted GENERAL OBSERVATIONS order — Findings first, each later walk's
 * missing name inserted after its nearest preceding present name). At SDTMIG 3.4 the Findings walk
 * omits {@code --STDTC} / {@code --STDY}, so without the union an own {@code SVSTDTC} would be no
 * anchor and a correctly placed {@code EPOCH} would be judged misplaced.</li>
 * <li><b>Roles of the own variables</b> (owner Q2, P1): the IG row of the same name (read
 * unfiltered — {@code getStandardVariablesDetailed}, else {@code getDomainVariables}), else the
 * model dataset's row, else the first walk that names it (0.5 (c): SENDIG 3.0 SV's model rows carry
 * no role for its identifiers).</li>
 * <li><b>The dataset's columns</b>: its own columns, the source of
 * {@code get_column_order_from_dataset()} — a merged SUPP qualifier or joined column is never an
 * addition.</li>
 * </ol>
 *
 * <h2>The placement ({@link #place})</h2>
 * <p>
 * Ranks: Identifier 0, Topic 1, any {@code … Qualifier} 2, Timing 3. An addition is a column in
 * {@code T} and not in {@code O} (case-insensitive, de-duplicated; only present columns, P3); its
 * rank is {@code T}'s role — an addition of another role is left out, unjudged. Each addition's gap
 * is computed against {@code O} <b>alone</b> (M2), so additions never reorder one another: gap
 * {@code g} is just before {@code O[g]}, gap {@code |O|} the end. <b>Anchored</b> — the {@code O}
 * variables of the addition's rank that its rank's table knows: before the first anchor (in
 * {@code O}'s order) whose table ordinal exceeds the addition's, else just after the last anchor.
 * <b>Unanchored</b>: just after the last {@code O} variable of rank &le; the addition's, else the
 * start (M1). An {@code O} variable of no rank is neither anchor nor boundary (L2). Each gap is
 * emitted sorted by (rank, ordinal); names upper-cased.
 * </p>
 *
 * <p>
 * <b>P4 — monotone.</b> Restricted to {@code O}'s variables the reference is {@code O} (insertion
 * only, and every fallback answers {@code O}), so a rule whose model-order verdict fails still
 * fails against it, and the function SKIPs exactly when {@code get_model_column_order()} does.
 * </p>
 */
public final class PermittedColumnOrder
{

    /** The three general-observation classes whose walks give the role tables, Findings first. */
    static final List<String> WALK_CLASSES = List.of("FINDINGS", "EVENTS", "INTERVENTIONS");

    private static final int NO_RANK = -1;

    private static final int IDENTIFIER = 0;

    private static final int TOPIC = 1;

    private static final int QUALIFIER = 2;

    private static final int TIMING = 3;

    private PermittedColumnOrder()
    {
    }

    /**
     * One row of a role table: a variable name (any case) and its model role.
     *
     * @param name
     *            the variable name
     * @param role
     *            the role, or {@code null} when the row carries none
     */
    public record RoleRow(String name, @Nullable String role)
    {
    }

    /**
     * The reference order for {@code aTable}, or {@code null} when the model order is unusable (the
     * caller SKIPs, as {@code get_model_column_order()} does).
     *
     * @param aProvider
     *            the library provider
     * @param aTable
     *            the dataset under validation
     * @param aResolver
     *            the cross-domain resolver
     * @return the reference, or {@code null}
     */
    public static @Nullable List<String> resolve(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver)
    {
        List<String> own = aProvider.getStandardModelVariables(aTable, aResolver);
        if (own == null || own.isEmpty())
        {
            return null;
        }
        String cdiscDomain = CdiscDomainResolver.cdiscDomainOf(aTable);
        if (!isSpecialPurpose(aProvider.getDatasetClass(null, cdiscDomain)))
        {
            return own;
        }
        List<List<RoleRow>> walks = new ArrayList<>(WALK_CLASSES.size());
        for (String modelClass : WALK_CLASSES)
        {
            walks.add(walk(aProvider, aTable, aResolver, modelClass));
        }
        if (walks.getFirst().isEmpty())
        {
            return own;
        }
        return place(own, ownRoles(aProvider, aTable, aResolver, cdiscDomain), walks,
                columns(aTable));
    }


    /**
     * Whether a dataset class opens the gate: {@code SPECIAL PURPOSE} (from "Special-Purpose") or
     * {@code SPECIAL-PURPOSE} (from "Special-Purpose Datasets") after ONE
     * {@link SdtmObservationClasses#normalise} (it is not idempotent on these aliases).
     *
     * @param aRawClass
     *            the class as the provider answers it, possibly {@code null}
     * @return whether additions are placed
     */
    static boolean isSpecialPurpose(@Nullable String aRawClass)
    {
        String normalised = SdtmObservationClasses.normalise(aRawClass);
        return "SPECIAL PURPOSE".equals(normalised) || "SPECIAL-PURPOSE".equals(normalised);
    }


    /**
     * The model walk of one class, through the seam {@code get_model_filtered_variables} reads and
     * its legacy fallback ({@code --} substituted with the dataset's prefix), as role rows; empty
     * when neither answers.
     */
    private static List<RoleRow> walk(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver, String aModelClass)
    {
        List<Map<String, String>> rows = aProvider.getStandardModelVariablesForClass(aTable,
                aResolver, aModelClass);
        boolean fromResolver = rows != null;
        if (rows == null)
        {
            rows = aProvider.getModelVariablesForClass(aModelClass);
        }
        return roleRows(rows, fromResolver ? null : prefix(aTable));
    }


    private static List<RoleRow> roleRows(@Nullable List<Map<String, String>> aRows,
            @Nullable String aPrefix)
    {
        if (aRows == null)
        {
            return List.of();
        }
        List<RoleRow> out = new ArrayList<>(aRows.size());
        for (Map<String, String> row : aRows)
        {
            String name = row.get("name");
            if (name == null || name.isBlank())
            {
                continue;
            }
            out.add(new RoleRow(
                    aPrefix != null && name.contains("--") ? name.replace("--", aPrefix) : name,
                    row.get("role")));
        }
        return out;
    }


    private static String prefix(IDataTable aTable)
    {
        return Objects.requireNonNullElse(DatasetIdentity.variableWildcardPrefix(aTable,
                DatasetIdentity.domainPrefix(aTable)), "");
    }


    /**
     * The own variables' roles, upper-cased name to role: the IG row (unfiltered — never the
     * dataset-intersected {@code StandardVariableSelector} view, M3), else the model dataset's row
     * (P1). A name neither carries is absent; {@link #place} then falls back to the walks.
     */
    private static Map<String, String> ownRoles(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver, String aCdiscDomain)
    {
        Map<String, String> roles = new HashMap<>();
        List<Map<String, String>> ig = aProvider.getStandardVariablesDetailed(aTable, aResolver);
        if (ig == null)
        {
            ig = aProvider.getDomainVariables(aCdiscDomain);
        }
        putRoles(roles, roleRows(ig, null));
        List<Map<String, String>> model = aProvider.getStandardModelVariablesDetailed(aTable,
                aResolver);
        boolean fromResolver = model != null;
        if (model == null)
        {
            model = aProvider.getModelVariables(aCdiscDomain);
        }
        putRoles(roles, roleRows(model, fromResolver ? null : prefix(aTable)));
        return roles;
    }


    /** Adds each row's role under its upper-cased name unless one is already there. */
    private static void putRoles(Map<String, String> aRoles, List<RoleRow> aRows)
    {
        for (RoleRow row : aRows)
        {
            if (row.role() != null && !row.role().isBlank())
            {
                aRoles.putIfAbsent(upper(row.name()), row.role());
            }
        }
    }


    /**
     * The dataset's own columns in declaration order — the {@code get_column_order_from_dataset()}
     * source.
     */
    private static List<String> columns(IDataTable aTable)
    {
        DataTableMeta meta = aTable.getMetaData();
        int count = meta.getColumnCount();
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }


    /**
     * The placement (§2.3 steps 6–9) over plain inputs.
     *
     * @param aOwn
     *            the model order {@code O}, non-empty
     * @param aOwnRoles
     *            upper-cased own variable name to its IG (else model) role
     * @param aWalks
     *            the Findings, Events and Interventions walks, in that order; the first is
     *            {@code T}
     * @param aDatasetColumns
     *            the dataset's own columns, in order
     * @return {@code O} with the additions inserted, upper-cased and unique
     */
    public static List<String> place(List<String> aOwn, Map<String, String> aOwnRoles,
            List<List<RoleRow>> aWalks, List<String> aDatasetColumns)
    {
        List<String> own = new ArrayList<>(
                new LinkedHashSet<>(aOwn.stream().map(PermittedColumnOrder::upper).toList()));
        Set<String> ownSet = Set.copyOf(own);
        List<RoleRow> findings = aWalks.isEmpty() ? List.of() : aWalks.getFirst();
        Map<String, Integer> tIndex = new HashMap<>();
        Map<String, Integer> tRank = new HashMap<>();
        for (int i = 0; i < findings.size(); i++)
        {
            String name = upper(findings.get(i).name());
            if (tIndex.putIfAbsent(name, i) == null)
            {
                tRank.put(name, rank(findings.get(i).role()));
            }
        }
        Map<String, Integer> uIndex = new HashMap<>();
        Map<String, Integer> uRank = new HashMap<>();
        unionTable(aWalks, uIndex, uRank);
        Map<String, String> walkRole = new HashMap<>();
        for (List<RoleRow> walk : aWalks)
        {
            for (RoleRow row : walk)
            {
                if (row.role() != null && !row.role().isBlank())
                {
                    walkRole.putIfAbsent(upper(row.name()), row.role());
                }
            }
        }
        int[] ownRank = new int[own.size()];
        for (int i = 0; i < own.size(); i++)
        {
            String role = aOwnRoles.get(own.get(i));
            ownRank[i] = rank(role != null && !role.isBlank() ? role : walkRole.get(own.get(i)));
        }
        Ordinals ordinals = new Ordinals(tIndex, tRank, uIndex, uRank);
        List<List<Addition>> gaps = new ArrayList<>(own.size() + 1);
        for (int g = 0; g <= own.size(); g++)
        {
            gaps.add(new ArrayList<>());
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String column : aDatasetColumns)
        {
            String name = upper(column);
            if (!tIndex.containsKey(name) || ownSet.contains(name) || !seen.add(name))
            {
                continue;
            }
            int k = Objects.requireNonNull(tRank.get(name));
            if (k == NO_RANK)
            {
                continue; // a role outside the four ranks: unjudged, like a sponsor variable
            }
            int ordinal = ordinals.of(name, k);
            gaps.get(gapOf(own, ownRank, ordinals, k, ordinal)).add(new Addition(name, k, ordinal));
        }
        List<String> out = new ArrayList<>(own.size() + seen.size());
        for (int g = 0; g <= own.size(); g++)
        {
            List<Addition> here = gaps.get(g);
            here.sort(java.util.Comparator.comparingInt(Addition::rank)
                    .thenComparingInt(Addition::ordinal));
            for (Addition a : here)
            {
                out.add(a.name());
            }
            if (g < own.size())
            {
                out.add(own.get(g));
            }
        }
        return out;
    }


    /**
     * The gap of an addition of rank {@code k} and table ordinal {@code ordinal}, against {@code O}
     * alone.
     */
    private static int gapOf(List<String> aOwn, int[] aOwnRank, Ordinals aOrdinals, int k,
            int ordinal)
    {
        int lastAnchor = NO_RANK;
        for (int i = 0; i < aOwn.size(); i++)
        {
            if (aOwnRank[i] != k)
            {
                continue;
            }
            int anchor = aOrdinals.of(aOwn.get(i), k);
            if (anchor == NO_RANK)
            {
                continue;
            }
            if (anchor > ordinal)
            {
                return i;
            }
            lastAnchor = i;
        }
        if (lastAnchor != NO_RANK)
        {
            return lastAnchor + 1;
        }
        int lastBoundary = NO_RANK;
        for (int i = 0; i < aOwn.size(); i++)
        {
            if (aOwnRank[i] != NO_RANK && aOwnRank[i] <= k)
            {
                lastBoundary = i;
            }
        }
        return lastBoundary + 1;
    }


    /**
     * The Identifier and Timing rows of every walk merged into one order: the first walk as is,
     * each later walk's missing name inserted just after its nearest preceding name already present
     * (the start when none). The first walk naming a variable gives its rank.
     */
    private static void unionTable(List<List<RoleRow>> aWalks, Map<String, Integer> aIndex,
            Map<String, Integer> aRank)
    {
        List<String> union = new ArrayList<>();
        for (List<RoleRow> walk : aWalks)
        {
            List<String> names = new ArrayList<>();
            for (RoleRow row : walk)
            {
                int r = rank(row.role());
                String name = upper(row.name());
                if ((r == IDENTIFIER || r == TIMING) && !names.contains(name))
                {
                    names.add(name);
                    aRank.putIfAbsent(name, r);
                }
            }
            for (int i = 0; i < names.size(); i++)
            {
                String name = names.get(i);
                if (union.contains(name))
                {
                    continue;
                }
                int at = 0;
                for (int j = i - 1; j >= 0; j--)
                {
                    int pos = union.indexOf(names.get(j));
                    if (pos >= 0)
                    {
                        at = pos + 1;
                        break;
                    }
                }
                union.add(at, name);
            }
        }
        for (int i = 0; i < union.size(); i++)
        {
            aIndex.put(union.get(i), i);
        }
    }


    /**
     * Identifier 0, Topic 1, any qualifier 2, Timing 3; {@value #NO_RANK} for anything else.
     *
     * @param aRole
     *            a model / IG role, possibly {@code null}
     * @return its rank
     */
    static int rank(@Nullable String aRole)
    {
        if (aRole == null)
        {
            return NO_RANK;
        }
        String role = aRole.trim().toLowerCase(Locale.ROOT);
        return switch (role)
        {
        case "identifier" -> IDENTIFIER;
        case "topic" -> TOPIC;
        case "timing" -> TIMING;
        default -> role.endsWith("qualifier") ? QUALIFIER : NO_RANK;
        };
    }


    private static String upper(String aName)
    {
        return aName.trim().toUpperCase(Locale.ROOT);
    }

    /** A placed addition. */
    private record Addition(String name, int rank, int ordinal)
    {
    }


    /**
     * The table ordinal of a name for a rank: Identifier / Timing from the union table, Topic /
     * qualifier from {@code T}; {@value #NO_RANK} when that rank's table does not know it at that
     * rank.
     */
    private record Ordinals(Map<String, Integer> tIndex, Map<String, Integer> tRank,
            Map<String, Integer> uIndex, Map<String, Integer> uRank)
    {

        int of(String aName, int aRank)
        {
            boolean union = aRank == IDENTIFIER || aRank == TIMING;
            Map<String, Integer> index = union ? uIndex : tIndex;
            Map<String, Integer> ranks = union ? uRank : tRank;
            Integer at = index.get(aName);
            return at != null && Objects.equals(ranks.get(aName), aRank) ? at : NO_RANK;
        }
    }
}
