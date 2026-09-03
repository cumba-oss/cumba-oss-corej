package net.cumba.corej.core.metadata;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import org.jspecify.annotations.Nullable;

/**
 * Fix #41 — sniffs the observation class of a dataset that the CDISC Library could not resolve.
 * Mirrors Python's {@code _handle_custom_domains} (see
 * {@code cdisc-rules-engine/cdisc_rules_engine/services/data_services/base_data_service.py:239-254}
 * + {@code _contains_topic_variable}) by inspecting topic-variable column patterns:
 *
 * <table>
 * <caption>Topic-variable patterns</caption>
 * <tr>
 * <th>Pattern present on dataset</th>
 * <th>Class assigned</th>
 * </tr>
 * <tr>
 * <td>{@code <domain>TERM} (or literal {@code TERM} if the dataset carries {@code RDOMAIN})</td>
 * <td>{@code EVENTS}</td>
 * </tr>
 * <tr>
 * <td>{@code <domain>TRT} / {@code TRT}</td>
 * <td>{@code INTERVENTIONS}</td>
 * </tr>
 * <tr>
 * <td>{@code <domain>QNAM} / {@code QNAM}</td>
 * <td>{@code RELATIONSHIP}</td>
 * </tr>
 * <tr>
 * <td>{@code <domain>TESTCD} + {@code <domain>OBJ}</td>
 * <td>{@code FINDINGS ABOUT}</td>
 * </tr>
 * <tr>
 * <td>{@code <domain>TESTCD} (no {@code OBJ})</td>
 * <td>{@code FINDINGS}</td>
 * </tr>
 * </table>
 *
 * <p>
 * The DOMAIN-vs-RDOMAIN switch follows Python's {@code _contains_topic_variable}: when the dataset
 * carries a literal {@code DOMAIN} column (regular SDTM domains), topic variables are checked with
 * the dataset's domain prefix; when the dataset carries a literal {@code RDOMAIN} column (SUPP/SQAP
 * family), topic variables are checked without prefix because those datasets follow
 * {@code QNAM}/{@code QVAL} naming, not {@code <domain><suffix>}.
 * </p>
 *
 * <p>
 * Returns {@code null} when no pattern matches — the caller (e.g.
 * {@link MetadataLibraryProvider#getDatasetClass(String)}) propagates that to the rule generator,
 * where Fix #41's strict-on-null {@code ScopeMatcher.matchesClass} flip skips the affected
 * class-scoped rules.
 * </p>
 *
 * <p>
 * Stateless and immutable — safe for concurrent use.
 * </p>
 */
public final class CustomDomainClassDetector
{

    private CustomDomainClassDetector()
    {
    }


    /**
     * Returns the inferred observation class for a dataset whose class the Library could not
     * resolve, or {@code null} when no topic-variable pattern matches.
     *
     * @param meta
     *            the dataset's column metadata. The sniffer reads only column names — values are
     *            never consulted.
     * @param domain
     *            the dataset's domain identifier (e.g. {@code "AE"}, {@code "MYDM"}). Used as the
     *            topic-variable prefix when the dataset carries a {@code DOMAIN} column.
     */
    public static @Nullable String detectClass(IDataTableMetadata meta, String domain)
    {
        if (meta == null)
        {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (IColumnMetadata col : meta.getColumns())
        {
            names.add(col.getName());
        }
        return detectClass(names, domain);
    }


    /**
     * Column-name-based class sniffer — the core used by both the {@link IDataTableMetadata}
     * overload (metadata-library tables) and callers holding the actual dataset's columns (e.g.
     * {@code LibraryValidator}, mirroring Python's {@code handle_custom_domains} on the loaded
     * dataset). Matching is case-insensitive; values are never consulted (see class doc / decision
     * on the Python {@code in_values} arm).
     *
     * @param columnNames
     *            the dataset's column names.
     * @param domain
     *            the dataset's domain identifier (e.g. {@code "AE"}, {@code "MYDM"}). Used as the
     *            topic-variable prefix when the dataset carries a {@code DOMAIN} column.
     */
    public static @Nullable String detectClass(@Nullable Set<String> columnNames, String domain)
    {
        if (columnNames == null || columnNames.isEmpty() || domain == null || domain.isEmpty())
        {
            return null;
        }
        Set<String> upper = HashSet.newHashSet(columnNames.size());
        for (String name : columnNames)
        {
            if (name != null)
            {
                upper.add(name.toUpperCase(Locale.ROOT));
            }
        }
        String domainUpper = domain.toUpperCase(Locale.ROOT);
        boolean hasDomain = upper.contains("DOMAIN");
        boolean hasRdomain = upper.contains("RDOMAIN");
        if (!hasDomain && !hasRdomain)
        {
            return null;
        }

        if (hasTopic(upper, domainUpper, "TERM", hasDomain))
        {
            return "EVENTS";
        }
        if (hasTopic(upper, domainUpper, "TRT", hasDomain))
        {
            return "INTERVENTIONS";
        }
        if (hasTopic(upper, domainUpper, "QNAM", hasDomain))
        {
            return "RELATIONSHIP";
        }
        if (hasTopic(upper, domainUpper, "TESTCD", hasDomain))
        {
            if (hasTopic(upper, domainUpper, "OBJ", hasDomain))
            {
                return "FINDINGS ABOUT";
            }
            return "FINDINGS";
        }
        return null;
    }


    /**
     * Mirrors Python's {@code _contains_topic_variable}: with a {@code DOMAIN} column the topic is
     * checked as {@code <domain><suffix>} (e.g. {@code AETERM}); with only {@code RDOMAIN}
     * (SUPP/SQAP family) it's checked literally as {@code <suffix>} (e.g. {@code QNAM}).
     *
     * @param upperColumns
     *            the dataset's column names, already upper-cased.
     */
    private static boolean hasTopic(Set<String> upperColumns, String domainUpper, String suffix,
            boolean usingDomainPrefix)
    {
        if (usingDomainPrefix)
        {
            return upperColumns.contains(domainUpper + suffix);
        }
        return upperColumns.contains(suffix);
    }

}
