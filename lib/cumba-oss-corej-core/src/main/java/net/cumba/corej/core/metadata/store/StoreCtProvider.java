package net.cumba.corej.core.metadata.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.define.conformance.ct.CtCodelist;
import net.cumba.corej.define.conformance.ct.CtProvider;

/**
 * A {@link CtProvider} over the unified metadata store's CT packages, merged in precedence order
 * (PLAN-define-ct-evaluation §4.1): the FIRST package in {@code aPackageIds} that holds a c-code
 * (or a name) wins — D1's "merge, never first-wins" at package level, first-wins per codelist. The
 * order itself is the caller's (D-2 lives in {@code DefineStoreBinding}), so this class stays a
 * pure projection with one test surface for the order.
 *
 * <p>
 * D-6: a codelist whose {@code conceptId} or {@code extensible} is unpublished is left out entirely
 * — "unknown to CT", which every CT kind treats as out of reach — and a term whose
 * {@code submissionValue} or {@code conceptId} is unpublished is dropped. Measured 0 / 38 575
 * codelists in the real corpus, so this only keeps a future unpublished field from mis-firing.
 * D-15: names are indexed exactly as CT spells them (no case folding — the rule says "defined in
 * CT", and CT spells a name one way); a codelist without a name stays reachable by c-code.
 * </p>
 *
 * <p>
 * Perf: only the SELECTED packages are materialised, once per define run — one {@code sdtmct}
 * package is ~1 100 codelists, a few MB of {@code String} maps; no boxed-primitive structures.
 * </p>
 */
public final class StoreCtProvider implements CtProvider
{

    private final Map<String, CtCodelist> byCCode;

    private final Map<String, CtCodelist> byName;

    private final List<String> packageIds;

    private StoreCtProvider(Map<String, CtCodelist> aByCCode, Map<String, CtCodelist> aByName,
            List<String> aPackageIds)
    {
        byCCode = aByCCode;
        byName = aByName;
        packageIds = aPackageIds;
    }


    /**
     * Materialises the named packages from {@code aStore}.
     *
     * @param aStore
     *            the open store
     * @param aPackageIds
     *            the packages to bind, in precedence order; every id must be
     *            {@link Presence#PRESENT} — the caller checks (D3) and this method fails loudly on
     *            one that is not
     * @return the provider
     * @throws IllegalStateException
     *             if a package is not held after all
     */
    public static StoreCtProvider of(MetadataStore aStore, List<String> aPackageIds)
    {
        Map<String, CtCodelist> byCCode = new HashMap<>();
        Map<String, CtCodelist> byName = new HashMap<>();
        for (String id : aPackageIds)
        {
            StoredCtPackage pkg = aStore.ctPackage(id).orElseThrow(() -> new IllegalStateException(
                    "CT package " + id + " vanished after its presence check"));
            for (StoredCodelist cl : pkg.codelists())
            {
                if (cl.conceptId() == null || cl.extensible() == null)
                {
                    continue; // D-6: unpublished identity or extensibility => unknown to CT
                }
                if (byCCode.containsKey(cl.conceptId())
                        && (cl.name() == null || byName.containsKey(cl.name())))
                {
                    continue; // first wins on both indexes: no term map is built for a loser
                }
                CtCodelist codelist = toCtCodelist(cl);
                byCCode.putIfAbsent(cl.conceptId(), codelist);
                if (cl.name() != null)
                {
                    byName.putIfAbsent(cl.name(), codelist);
                }
            }
        }
        return new StoreCtProvider(Map.copyOf(byCCode), Map.copyOf(byName),
                List.copyOf(aPackageIds));
    }


    private static CtCodelist toCtCodelist(StoredCodelist aCodelist)
    {
        Map<String, String> terms = new HashMap<>();
        for (StoredTerm term : aCodelist.terms())
        {
            if (term.submissionValue() != null && term.conceptId() != null)
            {
                terms.putIfAbsent(term.submissionValue(), term.conceptId());
            }
        }
        return new CtCodelist(java.util.Objects.requireNonNull(aCodelist.conceptId()),
                Boolean.TRUE.equals(aCodelist.extensible()), terms);
    }


    @Override
    public Optional<CtCodelist> codelistByCCode(String aCCode)
    {
        return Optional.ofNullable(byCCode.get(aCCode));
    }


    @Override
    public Optional<CtCodelist> codelistByName(String aName)
    {
        return Optional.ofNullable(byName.get(aName));
    }


    /** {@code true}: the store carries codelist names since format 3 (T1-3 b). */
    @Override
    public boolean hasNameLookup()
    {
        return true;
    }


    /** The bound package ids, in precedence order (an immutable copy). */
    public List<String> packageIds()
    {
        return List.copyOf(packageIds);
    }


    /** How many distinct codelists (by c-code) the bound packages contribute. */
    public int codelistCount()
    {
        return byCCode.size();
    }
}
