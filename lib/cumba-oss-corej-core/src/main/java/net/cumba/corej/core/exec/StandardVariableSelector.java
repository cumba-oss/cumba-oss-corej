package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import net.cumba.corej.core.metadata.CdiscDomainResolver;
import net.cumba.datatable.IDataTable;

/**
 * Shared resolution of a dataset's <em>standard</em> (CDISC Library) variables, filtered by an
 * attribute predicate and intersected with the columns the dataset actually carries.
 *
 * <p>
 * Three callers need exactly this walk and differ only in the predicate:
 * </p>
 * <ul>
 * <li>{@code get_dataset_filtered_variables} — filter on a {@code key_name = key_value} attribute
 * pair supplied by the rule;</li>
 * <li>{@code natural_key_variables} — filter on {@link #NATURAL_KEY_ROLES} membership;</li>
 * <li>{@link RecordKeyResolver}'s {@code NATURAL} key tier — the same role filter, used to build a
 * finding's record key rather than a rule operand.</li>
 * </ul>
 *
 * <p>
 * The source is the class-aware algorithm-B resolver
 * ({@link MetadataProvider#getStandardVariablesDetailed}, IG-base + Model-merge), with the legacy
 * {@link MetadataProvider#getDomainVariables} fallback for degraded providers that have no product
 * access. The two paths differ in one respect that this class normalises away: the resolver
 * pre-substitutes {@code --}, the legacy path does not, so the legacy names get the EC-36
 * variable-wildcard prefix applied here. Output is therefore identical regardless of source.
 * </p>
 */
final class StandardVariableSelector
{

    /**
     * The SDTM {@code role} values that form a Findings natural key. Comparisons are case-sensitive
     * against the exact CDISC Library casing. Deliberately excludes {@code Identifier} and
     * {@code Topic} — {@code USUBJID} / {@code --TESTCD} are added explicitly by the consumer (the
     * rule, for {@code natural_key_variables}; {@link RecordKeyResolver} for the key tier).
     */
    static final Set<String> NATURAL_KEY_ROLES = Set.of("Timing", "Record Qualifier",
            "Variable Qualifier", "Result Qualifier", "Synonym Qualifier", "Grouping Qualifier");

    private StandardVariableSelector()
    {
    }


    /**
     * Whether a variable's attribute map carries a natural-key-forming {@code role}. Used as a
     * method reference where a {@link Predicate} is required.
     *
     * @param aVarRow
     *            the variable's attribute map.
     * @return {@code true} when the {@code role} is in {@link #NATURAL_KEY_ROLES}.
     */
    static boolean isNaturalKeyRole(Map<String, String> aVarRow)
    {
        return NATURAL_KEY_ROLES.contains(aVarRow.get("role"));
    }


    /**
     * Returns the dataset's standard variables matching {@code filter}, in library iteration order,
     * intersected with the dataset's actual columns.
     *
     * @param provider
     *            the Library metadata provider; must be non-null (callers handle the
     *            absent-provider case themselves, because they differ on whether it means SKIP or
     *            degrade).
     * @param table
     *            the dataset whose variables are resolved.
     * @param resolver
     *            cross-domain resolver, passed through to the provider.
     * @param filter
     *            attribute-map predicate; a variable is kept only when it passes.
     * @return the matching column names present on the dataset; empty when the provider yields no
     *         variables for it.
     */
    static List<String> select(MetadataProvider provider, IDataTable table,
            DatasetResolver resolver, Predicate<Map<String, String>> filter)
    {
        List<Map<String, String>> source = provider.getStandardVariablesDetailed(table, resolver);
        boolean fromResolver = source != null;
        if (!fromResolver)
        {
            // Fix #59: the legacy fallback path uses the CDISC domain code (not the file/member
            // name) so split datasets like LBHE — DOMAIN=LB still resolve their LB metadata.
            source = provider.getDomainVariables(CdiscDomainResolver.cdiscDomainOf(table));
        }
        if (source == null || source.isEmpty())
        {
            return List.of();
        }
        // EC-36: variable names -> variable prefix; "" for SUPP, AP suffix for AP.
        String prefix = Objects.requireNonNullElse(OperationExecutor.variableWildcardPrefix(table,
                OperationExecutor.domainPrefix(table)), "");
        Set<String> datasetCols = OperationExecutor.datasetColumnNames(table);
        List<String> out = new ArrayList<>();
        for (Map<String, String> varRow : source)
        {
            if (!filter.test(varRow))
            {
                continue;
            }
            String name = varRow.get("name");
            if (name == null)
            {
                continue;
            }
            // The resolver pre-substitutes `--`. The legacy fallback path does not, so
            // substitute here to preserve identical output regardless of source.
            String resolved = (!fromResolver && name.contains("--")) ? name.replace("--", prefix)
                    : name;
            if (datasetCols.contains(resolved))
            {
                out.add(resolved);
            }
        }
        return out;
    }

}
