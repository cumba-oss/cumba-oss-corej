package net.cumba.corej.core.exec;

import java.util.Set;

import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Resolves domain names to data tables for cross-domain operation lookup. Returns {@code null} if
 * the domain is not available.
 */
@FunctionalInterface
public interface DatasetResolver
{

    @Nullable
    IDataTable resolve(String domainName);

    /**
     * Extended resolver that can also enumerate all available datasets. Required for Domain
     * Presence Check rules and Operations like {@code dataset_names} / {@code study_domains}.
     */
    interface WithInventory extends DatasetResolver
    {

        /**
         * Returns the names of all available datasets in the submission.
         */
        Set<String> availableDatasets();


        /**
         * J7: the data-driven SDTM <em>domains</em> across all datasets — the {@code DOMAIN} cell
         * (via {@link OperationExecutor#domainOfDataset}), not the member names. So split members
         * ({@code lbch}/{@code lbhe}/{@code lbur}) collapse to their domain ({@code LB}), and
         * {@code study_domains()} matches an {@code RDOMAIN} value. A dataset with no domain
         * (SUPP/SQ/RELREC) contributes {@code ""} (Python includes the empty domain). Default so
         * the ~13 implementers need no edit.
         *
         * @return the distinct data-driven domains
         */
        default Set<String> availableDomains()
        {
            Set<String> domains = new java.util.LinkedHashSet<>();
            boolean anyNoDomain = false;
            for (String name : availableDatasets())
            {
                String domain = OperationExecutor.domainOfDataset(name, this);
                if (domain != null && !domain.isEmpty())
                {
                    domains.add(domain);
                }
                else
                {
                    anyNoDomain = true;
                }
            }
            if (anyNoDomain)
            {
                domains.add("");
            }
            return domains;
        }


        /**
         * J7: every available table whose data-driven domain equals {@code domain}
         * (case-insensitive) — unions split members so a {@code $rdomain_variables} lookup over
         * {@code LB} sees the columns of all of {@code lbch}/{@code lbhe}/{@code lbur}.
         *
         * @param domain
         *            the data-driven domain to collect tables for
         * @return the matching tables (empty when none)
         */
        default java.util.Collection<IDataTable> tablesForDomain(String domain)
        {
            java.util.List<IDataTable> tables = new java.util.ArrayList<>();
            for (String name : availableDatasets())
            {
                IDataTable table = resolve(name);
                if (table != null && domain.equalsIgnoreCase(
                        net.cumba.corej.core.metadata.CdiscDomainResolver.cdiscDomainOf(table)))
                {
                    tables.add(table);
                }
            }
            return tables;
        }

    }

}
