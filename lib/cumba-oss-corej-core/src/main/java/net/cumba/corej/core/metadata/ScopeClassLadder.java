package net.cumba.corej.core.metadata;

import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The ONE answer to "which observation class is this loaded dataset in?" — the class a rule's
 * {@code Scope.Classes} is matched against, and ({@code PLAN-custom-domain-model-walk} S1) the
 * class the SDTM variable walks walk for a domain the run's IG does not define. One implementation,
 * so the two cannot disagree: {@code LibraryValidator} and {@link MetadataLibraryProvider} both
 * call {@link #classOf}.
 *
 * <p>
 * Fix #60: the provider's full ladder
 * ({@link MetadataProvider#getDatasetClass(String, String, Set)} — Define-XML / study class,
 * product reverse-walk, curated {@link DomainClassMap}, the Fix #41 custom-domain sniffer) over the
 * dataset's own columns. The member name keys tier 1; the CDISC domain code keys tiers 2/3 — needed
 * for split datasets like {@code LBHE} where the member name and the CDISC code differ. The actual
 * columns let the tier-3 sniffer classify datasets the metadata library does not carry (e.g.
 * {@code SUPP--}), mirroring Python's {@code handle_custom_domains} on the loaded dataset.
 * </p>
 *
 * <p>
 * When the ladder resolves nothing and the dataset is an Associated Persons domain ({@code AP--}
 * carrying an {@code APID} column), the class is inherited from the parent domain (e.g.
 * {@code APLB} → the {@code LB} dataset's class), mirroring Python's
 * {@code _get_associated_persons_inherit_class}. Unlike Python — which raises on a missing parent
 * or a nested AP reference — this degrades gracefully to {@code null} (class-scoped rules stay
 * SKIPPED, and the walks answer {@code []}) rather than aborting the dataset.
 * </p>
 *
 * <p>
 * Moved here from {@code LibraryValidator.classNameFor} verbatim by
 * {@code PLAN-custom-domain-model-walk} (review round 1, LOW-2): the walk had re-derived the AP
 * case from the stripped prefix instead, and so placed an {@code AP--} dataset whose parent is
 * absent in a class the scope matcher refused it.
 * </p>
 */
@CustomLog
public final class ScopeClassLadder
{

    private ScopeClassLadder()
    {
    }


    /**
     * The dataset's observation class, or {@code null} when no tier and no AP inheritance places
     * it.
     *
     * @param aProvider
     *            the provider whose {@link MetadataProvider#getDatasetClass(String, String, Set)}
     *            ladder is consulted
     * @param aMemberName
     *            the dataset's member (file) name — keys tier 1
     * @param aCdiscDomain
     *            the dataset's CDISC domain code ({@link CdiscDomainResolver#cdiscDomainOf}) — keys
     *            tiers 2 and 3
     * @param aTable
     *            the loaded dataset, whose columns the sniffer reads
     * @param aResolver
     *            resolves an {@code AP--} dataset's parent domain
     */
    public static @Nullable String classOf(MetadataProvider aProvider, @Nullable String aMemberName,
            String aCdiscDomain, IDataTable aTable, DatasetResolver aResolver)
    {
        return classOf(aProvider, aMemberName, aCdiscDomain, aTable, aResolver, false);
    }


    private static @Nullable String classOf(MetadataProvider aProvider,
            @Nullable String aMemberName, String aCdiscDomain, IDataTable aTable,
            DatasetResolver aResolver, boolean aApRecursed)
    {
        Set<String> columns = OperationExecutor.datasetColumnNames(aTable);
        String className = aProvider.getDatasetClass(aMemberName, aCdiscDomain, columns);
        if (className != null)
        {
            return className;
        }
        // AP-- inherit (Python _get_associated_persons_inherit_class): an Associated Persons domain
        // carries an APID column; its class is inherited from the parent domain named by the AP
        // suffix (e.g. APLB -> LB). Applied only after the custom-domain sniff failed, matching
        // Python's order. Single-level recursion: a nested AP parent returns null via the guard.
        // The DOMAIN column must be present: Python derives ap_suffix from dataset_metadata.domain
        // (the DOMAIN value), which is None when the column is absent — so no inherit then. Gating
        // on the column also guarantees aCdiscDomain here is the DOMAIN value, not a member-name
        // fallback, so substring(2) is the true AP suffix.
        //
        // Sibling predicate: OperationExecutor.apSuffixOf (EC-36) computes the same Python
        // ap_suffix for `--` variable-name resolution. The two are deliberately NOT shared: this
        // one gates on the DOMAIN *column* and reads the already-resolved aCdiscDomain, while
        // apSuffixOf gates on a non-empty row-0 DOMAIN *value*. Unifying them would change which
        // class an AP dataset inherits — a Scope.Classes-wide blast radius unrelated to EC-36.
        // If either is edited, re-check the other.
        // APID / DOMAIN are looked up as column names, so ignoring letter case (CIT §1).
        if (!aApRecursed && aTable.getMetaData().getColumnIndex("APID") >= 0
                && aTable.getMetaData().getColumnIndex("DOMAIN") >= 0 && aCdiscDomain.length() >= 4
                && aCdiscDomain.toUpperCase(Locale.ROOT).startsWith("AP"))
        {
            String parentDomain = aCdiscDomain.substring(2);
            IDataTable parent = aResolver.resolve(parentDomain);
            if (parent != null)
            {
                String parentCdisc = CdiscDomainResolver.cdiscDomainOf(parent);
                String parentMember = parent.getMetaData().getName();
                return classOf(aProvider, parentMember, parentCdisc, parent, aResolver, true);
            }
            LOGGER.log(Level.DEBUG,
                    "AP dataset {0}: parent domain {1} not in study; class left undetermined",
                    aMemberName, parentDomain);
        }
        return className;
    }
}
