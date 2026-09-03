package net.cumba.corej.core.metadata;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * EC-14 layer (ii) — a plain delegating {@link MetadataProvider} that overrides <em>only</em>
 * {@link #getStandardDatasetNames()} to return the companion SDTM product's domain list, forwarding
 * every other call to the wrapped run provider.
 *
 * <p>
 * An ADaM run's own provider carries no SDTM IG product, so {@link #getStandardDatasetNames()}
 * returns {@code null} there and the {@code standard_domains} operation SKIPs. This decorator makes
 * that one accessor answer from a companion SDTMIG product (resolved by
 * {@link net.cumba.corej.core.run.CompanionSdtmDefaults}), so {@code SRCDOM is an SDTM domain name}
 * rules can validate against the canonical set. All other metadata (variables, classes, CT, define,
 * dataset classes) still comes from the ADaM run provider.
 * </p>
 */
public final class CompanionDomainsProvider implements MetadataProvider
{

    private final MetadataProvider base;

    private final MetadataProvider companion;

    /**
     * @param aBase
     *            the run's metadata provider (all calls but {@link #getStandardDatasetNames()}
     *            delegate here).
     * @param aCompanion
     *            the companion SDTM product provider whose {@link #getStandardDatasetNames()} is
     *            surfaced.
     */
    public CompanionDomainsProvider(MetadataProvider aBase, MetadataProvider aCompanion)
    {
        base = aBase;
        companion = aCompanion;
    }


    /** The canonical SDTM domain names come from the companion. */
    @Override
    public @Nullable List<String> getStandardDatasetNames()
    {
        return companion.getStandardDatasetNames();
    }


    /**
     * <b>R11</b> — the name-keyed carry-over lookup, answered from the <b>companion</b> product.
     *
     * <p>
     * This is the second thing the wrapper answers from the companion rather than the base: the
     * base is the ADaM library, which by construction publishes nothing under an SDTM domain.
     * Forwarding here is what lets an ADaM run compare a carried-over variable against the SDTM it
     * claims to derive from, <b>with no SDTM datasets supplied</b>. The scan itself lives in the
     * leaf provider that owns the product.
     * </p>
     */
    @Override
    public List<PublishedVariable> getPublishedVariablesByName(String variableName)
    {
        return companion.getPublishedVariablesByName(variableName);
    }

    // ------------------------------------------------------------------
    // Everything else delegates to the run provider.
    // ------------------------------------------------------------------


    @Override
    public List<String> getRequiredVariables(String domain)
    {
        return base.getRequiredVariables(domain);
    }


    @Override
    public List<String> getExpectedVariables(String domain)
    {
        return base.getExpectedVariables(domain);
    }


    /**
     * ⛔ <b>Phase 11 finding F6b — the declared tier (Fix #119), delegated.</b> Inheriting
     * {@link MetadataProvider#getDeclaredDatasetClass(String)}'s {@code null} default makes this
     * wrapper answer "the study declares no class" on behalf of a base provider that can read the
     * sponsor's Define-XML {@code def:Class}.
     *
     * <p>
     * ⚠⚠ Why it was not caught by the run: on the {@code RuleRunner} path the loss is normally
     * <em>masked</em>, because the define provider is consulted first and answers. It is unmasked
     * in rule <b>generation</b>, which has no define tier at all — {@code LibraryValidator} builds
     * {@code RuleGenerator} with this wrapper, and {@code RuleGenerator} reads both declared-tier
     * accessors straight off it to feed {@code AdamDataStructureDetector.detectAll} /
     * {@code AdamSubclassDetector.resolve}. With the declaration lost, the generation-time
     * {@code Scope.Data_Structures} / {@code Scope.Subclasses} gate falls back to the column
     * heuristic, the rule lands in {@code skippedSourceRules} and is never executed — so
     * {@code RuleRunner}'s correctly-fed gate never gets the chance to disagree, and nothing looks
     * wrong anywhere.
     * </p>
     */
    @Override
    public @Nullable String getDeclaredDatasetClass(String datasetName)
    {
        return base.getDeclaredDatasetClass(datasetName);
    }


    /** Phase 11 finding F6b — the {@code def:SubClass} half of the declared tier. */
    @Override
    public List<String> getDeclaredSubClasses(String datasetName)
    {
        return base.getDeclaredSubClasses(datasetName);
    }

    // ⚠⚠ The structure-keyed trio MUST be delegated, not inherited. Their interface defaults are
    // "I cannot answer" — a decorator that forgets them silently turns an ADaM run's
    // required_variables() back into the domain-keyed lookup that Fix #368 removed, and the rule
    // goes green again with no signal anywhere. That is exactly how this wrapper hid the fix for
    // one measurement cycle.


    @Override
    public boolean supportsStructureKeyedVariables()
    {
        return base.supportsStructureKeyedVariables();
    }


    /**
     * Fix #369 — delegated to {@code base}. The companion provider serves {@code standard_domains}
     * only; whether <em>this run's</em> Library could be consulted is the base provider's fact.
     */
    @Override
    public boolean isLibraryUnavailable()
    {
        return base.isLibraryUnavailable();
    }


    /**
     * ⚠ The <b>two-arg</b> form is the primary one (see
     * {@link MetadataProvider#getRequiredVariablesForStructure(String, List)}). Delegating only the
     * one-arg convenience would drop the dataset's subclass on the floor and re-open the
     * most-strict-wins union this decorator is not entitled to decide.
     */
    @Override
    public @Nullable List<String> getRequiredVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return base.getRequiredVariablesForStructure(structureToken, subclassTokens);
    }


    @Override
    public @Nullable List<String> getExpectedVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return base.getExpectedVariablesForStructure(structureToken, subclassTokens);
    }


    /** Provenance (log-only) — the run provider's declared products, not the companion's. */
    @Override
    public List<String> declaredStructureKeyedProducts()
    {
        return base.declaredStructureKeyedProducts();
    }


    @Override
    public List<String> getColumnOrder(String domain)
    {
        return base.getColumnOrder(domain);
    }


    @Override
    public List<String> getKeyVariables(String domain)
    {
        return base.getKeyVariables(domain);
    }


    @Override
    public List<String> getDatasetNames()
    {
        return base.getDatasetNames();
    }


    @Override
    public @Nullable List<String> getStandardVariableNames()
    {
        return base.getStandardVariableNames();
    }


    @Override
    public List<String> getModelColumnOrder(String domain)
    {
        return base.getModelColumnOrder(domain);
    }


    @Override
    public boolean isDomainCustom(String domain)
    {
        return base.isDomainCustom(domain);
    }


    @Override
    public List<String> getCodelistTerms(String codelistCode)
    {
        return base.getCodelistTerms(codelistCode);
    }


    @Override
    public Map<String, String> getVariableMetadata(String domain, String variable)
    {
        return base.getVariableMetadata(domain, variable);
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String domain)
    {
        return base.getDomainVariables(domain);
    }


    @Override
    public List<Map<String, String>> getModelVariables(String domain)
    {
        return base.getModelVariables(domain);
    }


    @Override
    public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
    {
        return base.getModelVariablesForClass(aModelClass);
    }


    @Override
    public List<String> getPublishedCtPackages()
    {
        return base.getPublishedCtPackages();
    }


    @Override
    public List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
    {
        return base.getCodelistAttribute(aCtPackageId, aCtAttribute);
    }


    @Override
    public Map<String, String> getDatasetMetadata(String domain)
    {
        return base.getDatasetMetadata(domain);
    }


    @Override
    public boolean isCodelistExtensible(String codelistName)
    {
        return base.isCodelistExtensible(codelistName);
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String codelistName)
    {
        return base.getCodelistTermMappings(codelistName);
    }


    @Override
    public Map<String, String> getCodelistCodeMap(String domain, String variable)
    {
        return base.getCodelistCodeMap(domain, variable);
    }


    @Override
    public @Nullable String getStandard()
    {
        return base.getStandard();
    }


    @Override
    public @Nullable String getVersion()
    {
        return base.getVersion();
    }


    @Override
    public @Nullable String getDefineVersion()
    {
        return base.getDefineVersion();
    }


    @Override
    public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return base.getStandardModelVariables(aTable, aResolver);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return base.getStandardModelVariablesDetailed(aTable, aResolver);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable aTable,
            DatasetResolver aResolver, String aModelClass)
    {
        return base.getStandardModelVariablesForClass(aTable, aResolver, aModelClass);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return base.getStandardVariablesDetailed(aTable, aResolver);
    }


    @Override
    public @Nullable String getDatasetClass(String aDomain)
    {
        return base.getDatasetClass(aDomain);
    }


    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain)
    {
        return base.getDatasetClass(aMemberName, aCdiscDomain);
    }


    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain,
            @Nullable Set<String> aActualColumns)
    {
        return base.getDatasetClass(aMemberName, aCdiscDomain, aActualColumns);
    }
}
