package net.cumba.corej.core.metadata;

import net.cumba.corej.core.exec.SplitDatasetUtil;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;

/**
 * Fix #59 — single canonical helper for "what is the CDISC domain code of this dataset?"
 *
 * <p>
 * The Java engine has two distinct concepts that get confused under
 * {@code IDataTable.getMetaData().getName()}:
 * </p>
 *
 * <ul>
 * <li><b>CDISC domain code</b> — value of the {@code DOMAIN} column on row 0 (e.g. {@code LB}).
 * Authoritative for class lookup in the SDTM Model, Library-API queries, the Fix #41 custom- domain
 * sniffer's {@code <domain>TERM}-style topic checks, scope matching against
 * {@code Scope.Domains.Include}, and {@code --} wildcard substitution.</li>
 * <li><b>Library member name</b> — the physical file/member identifier (e.g. {@code LBHE},
 * {@code LBCH}, {@code LBUR}, {@code LB1}/{@code LB2}, or just {@code LB} for non-split data).
 * Authoritative for the rule's {@code dataset_name} field, runtime listener events, and report
 * rows.</li>
 * </ul>
 *
 * <p>
 * {@link #cdiscDomainOf(IDataTable)} resolves the first concept by reading the {@code DOMAIN}
 * column on row 0 when present. When the column is absent (e.g. a domain that doesn't carry
 * {@code DOMAIN}, or a zero-row dataset), it falls back to
 * {@link SplitDatasetUtil#unsplitName(String)} on the member name — which handles digit-suffix
 * splits ({@code LB1} → {@code LB}) and SUPP/AP letter-suffix splits ({@code SUPPLBHM} →
 * {@code SUPPLBH}). For LBHE-style splits that {@code SplitDatasetUtil} doesn't recognise the
 * member name is returned unchanged; in that case the {@code DOMAIN} column is the only reliable
 * source.
 * </p>
 *
 * <p>
 * This class is the public-API replacement for the legacy package-private
 * {@code OperationExecutor.domainPrefix(IDataTable)}. The legacy helper is retained as a forwarder
 * for the per-row substitution sites in {@code OperationExecutor} that already use it; new call
 * sites should consume {@link #cdiscDomainOf(IDataTable)} directly.
 * </p>
 *
 * <p>
 * Stateless and thread-safe.
 * </p>
 */
public final class CdiscDomainResolver
{

    private CdiscDomainResolver()
    {
    }


    /**
     * Returns the CDISC domain code for the given dataset.
     *
     * <p>
     * Resolution order:
     * </p>
     * <ol>
     * <li>Value of the {@code DOMAIN} column on row 0 (when the column exists and the row count is
     * &gt; 0 and the value is non-missing/non-empty).</li>
     * <li>{@link SplitDatasetUtil#unsplitName(String)} of the member name — handles digit- and
     * SUPP/AP letter-suffix splits.</li>
     * <li>Raw member name as a final fallback.</li>
     * </ol>
     *
     * @param aTable
     *            the dataset; may be {@code null} (returns empty string).
     * @return the CDISC domain code, or {@code ""} when nothing can be resolved.
     */
    public static String cdiscDomainOf(IDataTable aTable)
    {
        if (aTable == null)
        {
            return "";
        }
        DataTableMeta meta = aTable.getMetaData();
        int domainCol = meta.getColumnIndex("DOMAIN");
        if (domainCol >= 0 && aTable.getRowCount() > 0)
        {
            IDataValue dv = aTable.getColumn(domainCol).getDataValue(0);
            if (!dv.isMissingOrInvalid())
            {
                String val = dv.getValueAsString();
                if (val != null && !val.isEmpty())
                {
                    return val;
                }
            }
        }
        String name = meta.getName();
        if (name == null || name.isEmpty())
        {
            return "";
        }
        return SplitDatasetUtil.unsplitName(name);
    }

}
