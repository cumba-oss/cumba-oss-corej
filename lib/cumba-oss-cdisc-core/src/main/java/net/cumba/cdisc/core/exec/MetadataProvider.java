package net.cumba.cdisc.core.exec;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * SPI for resolving CDISC Library metadata at rule execution time.
 * <p>
 * Operations like {@code required_variables}, {@code domain_is_custom}, and {@code codelist_terms}
 * require access to the CDISC Library to look up standard variable definitions, codelist values,
 * and domain classifications.
 * </p>
 * <p>
 * Implementations bridge to a concrete Library client (e.g., {@code CdiscLibraryClient} from
 * {@code net.cumba.cdisc.library}). The standard and version context is set at construction time by
 * the caller.
 * </p>
 * <p>
 * When no provider is configured, Library-dependent Operations produce a "rule skipped" result.
 * </p>
 */
public interface MetadataProvider
{

    /**
     * Fix #119: the dataset's <em>declared</em> class straight from the study metadata (the
     * Define-XML {@code def:Class} attribute/element) — no product walk, no heuristics, no curated
     * fallback. Feeds the {@code corej.defineFirst} preference of the {@code Scope.Data_Structures}
     * determination. Distinct from {@code getDatasetClass}-style resolution chains: {@code null}
     * simply means the study metadata declares nothing.
     *
     * @param datasetName
     *            the dataset (member) name, e.g. {@code ADTTE}
     * @return the declared class name verbatim, or {@code null} when undeclared/unknown
     */
    default @Nullable String getDeclaredDatasetClass(String datasetName)
    {
        return null;
    }


    /**
     * Fix #119: the dataset's declared Define-XML 2.1 {@code <def:SubClass>} names, in declaration
     * order. Feeds the {@code corej.defineFirst} preference of the {@code Scope.Subclasses}
     * determination.
     *
     * @param datasetName
     *            the dataset (member) name
     * @return the declared subclass names, or an empty list when none are declared
     */
    default List<String> getDeclaredSubClasses(String datasetName)
    {
        return List.of();
    }


    /**
     * Returns the list of required variable names for the given domain.
     *
     * @param domain
     *            the domain name (e.g., "DM", "AE")
     * @return required variable names, or empty list if unknown
     */
    List<String> getRequiredVariables(String domain);


    /**
     * Returns the list of expected (required + expected) variable names for the given domain.
     *
     * @param domain
     *            the domain name (e.g., "DM", "AE")
     * @return expected variable names, or empty list if unknown
     */
    List<String> getExpectedVariables(String domain);


    /**
     * Whether this provider can answer {@link #getRequiredVariablesForStructure} /
     * {@link #getExpectedVariablesForStructure} — i.e. whether it is backed by a product whose
     * variable model is keyed by <b>data structure</b> rather than by domain.
     *
     * <p>
     * ADaM is the case that matters. An ADaM product's variable model is keyed by data structure
     * ({@code ADSL}, {@code BDS}, {@code TTE}), while the domain a dataset reports is its member
     * name ({@code ADSL}, {@code ADAE}, {@code ADLB}) — {@code ADSL} is the only name where the two
     * coincide. A provider returning {@code true} here must be asked by structure; the domain-keyed
     * accessors cannot answer for it and would silently return an empty list.
     * </p>
     *
     * @return {@code true} when the structure-keyed accessors are meaningful for this provider
     */
    default boolean supportsStructureKeyedVariables()
    {
        return false;
    }


    /**
     * Whether this provider's backing CDISC Library <b>could not be consulted at all</b>, as
     * distinct from being consulted and having nothing to say.
     *
     * <p>
     * <b>Fix #369.</b> {@code CdiscLibraryProviderBuilder} returns a <em>degraded</em> provider
     * when the Library product fetch throws — the state of any run whose subscription key is
     * missing or expired (an ordinary HTTP 401), not only of a network outage. A degraded provider
     * still answers the variable-list accessors from its <em>study</em> library, and when that
     * cannot answer either the result is an empty list, {@code contains_all(cols, [])} is vacuously
     * true and a rule that claims to check the Library reports {@code SUCCESS} having never
     * consulted it.
     * </p>
     *
     * <p>
     * ⚠ The distinction this predicate carries is <b>semantic, not merely operational</b>: a rule
     * whose {@code Description} says <em>"a variable that the ADaM standard designates as
     * Required"</em> has not answered that question if the answer came from a sponsor's Define-XML
     * {@code ItemRef/@Mandatory}. {@link net.cumba.cdisc.core.exec.OperationExecutor} therefore
     * maps {@code true} here to {@code LIBRARY_NOT_AVAILABLE} — a loud SKIP — for every
     * library-dependent operation, <b>without</b> first testing whether some other tier could have
     * produced a value. Substitution from Define-XML is available, but only as an explicit opt-in
     * ({@code -Dcorej.degradedDefineFallback=true}, default off).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Decorators must delegate this method.</b> The default means <em>"nothing is
     * wrong"</em>, so a wrapper that inherits it reports "library fine" on behalf of a provider
     * that failed and the skip silently does not happen — the exact failure mode Fix #368 lost two
     * cycles to. {@code MetadataProviderDecoratorDelegationGuardTest} holds this repo-wide.
     * </p>
     *
     * @return {@code true} when the CDISC Library could not be consulted for this run
     */
    default boolean isLibraryUnavailable()
    {
        return false;
    }


    /**
     * The published <b>Required</b> variable names for an ADaM data structure, or {@code null} when
     * this provider's product defines no such structure.
     *
     * <p>
     * ⚠⚠ {@code null} and the <b>empty list</b> mean different things, and the difference is
     * load-bearing. {@code null} is <em>"this run's product has no such structure"</em> — the
     * caller must skip the rule loudly rather than let it pass. An empty list is <em>"the structure
     * exists and requires nothing"</em>, which is a legitimate green pass: {@code adamig-1-3}'s
     * {@code TTE} genuinely has zero Required variables. Collapsing the two is how a rule that
     * cannot resolve its list reads as a rule that found nothing wrong.
     * </p>
     *
     * <p>
     * Names are returned <b>as the standard publishes them</b>, so an ADaM naming template
     * ({@code TRTxxP}) is returned verbatim. Substituting it against the dataset's actual columns
     * is the caller's job — see
     * {@link net.cumba.cdisc.core.gen.WildcardExpander#scopeVariableWildcardPattern}.
     * </p>
     *
     * <h4>⚠⚠ The two-arg overload is the PRIMARY method — implementations override THAT one</h4>
     *
     * <p>
     * {@code subclassTokens} carries the dataset's resolved ADaM subclass(es) and selects which of
     * the token's structures <b>governs</b> — see
     * {@link net.cumba.cdisc.core.metadata.MetadataLibraryProvider} for the precedence chain. The
     * one-arg form below is a <b>convenience for callers with no subclass context</b> and delegates
     * here with an empty list; it must never be overridden on its own.
     * </p>
     *
     * <p>
     * ⛔ The default direction is deliberate and inverting it is a trap. Were the two-arg method to
     * default to the one-arg method, a decorator that forwards only the one-arg form would leave a
     * two-arg call landing on the interface default → the one-arg → the base's one-arg → this
     * interface's "cannot answer" {@code null}: the subclass silently dropped and
     * {@code required_variables()} SKIPping. With the direction below, a decorator that forwards
     * only the one-arg form still answers — it merely loses the subclass — and
     * {@code MetadataProviderDecoratorDelegationGuardTest} rejects it outright.
     * </p>
     *
     * @param structureToken
     *            a structure token as produced by
     *            {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector#detectAll} (e.g.
     *            {@code BASIC DATA STRUCTURE})
     * @param subclassTokens
     *            the dataset's resolved subclass tokens, most-specific first
     *            ({@link net.cumba.cdisc.core.metadata.AdamSubclassDetector#resolve}); empty when
     *            the dataset has no detectable subclass, which means <b>base structures only</b>
     * @return the published Required names, or {@code null} when the structure is not in the
     *         product
     */
    default @Nullable List<String> getRequiredVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return null;
    }


    /**
     * Convenience overload of {@link #getRequiredVariablesForStructure(String, List)} for callers
     * with no subclass context — equivalent to passing {@link List#of()}, i.e. <em>"base structures
     * only"</em>.
     *
     * <p>
     * ⚠ <b>Never override this one.</b> It exists so callers and tests without a subclass context
     * keep compiling; all behaviour lives in the two-arg primary.
     * </p>
     *
     * @param structureToken
     *            a structure token, as above
     * @return the published Required names, or {@code null} when the structure is not in the
     *         product
     */
    default @Nullable List<String> getRequiredVariablesForStructure(String structureToken)
    {
        return getRequiredVariablesForStructure(structureToken, List.of());
    }


    /**
     * The {@code Req}+{@code Exp} counterpart of {@link #getRequiredVariablesForStructure}, with
     * the same {@code null}-versus-empty contract.
     *
     * <p>
     * ⚑ ADaM's {@code core} vocabulary is {@code Req} / {@code Cond} / {@code Perm} — there is
     * <b>no {@code Exp}</b> — so on an ADaM product this returns the same names as
     * {@link #getRequiredVariablesForStructure}. It exists so the two library-backed operations
     * stay symmetric, not because ADaM distinguishes them.
     * </p>
     *
     * <p>
     * ⚠⚠ As with the Required accessor, the <b>two-arg overload is primary</b> and the one-arg form
     * is a convenience delegating to it with an empty subclass list. See
     * {@link #getRequiredVariablesForStructure(String, List)} for why the default may not point the
     * other way.
     * </p>
     *
     * @param structureToken
     *            a structure token, as above
     * @param subclassTokens
     *            the dataset's resolved subclass tokens, most-specific first; empty means base
     *            structures only
     * @return the published Required+Expected names, or {@code null} when the structure is not in
     *         the product
     */
    default @Nullable List<String> getExpectedVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return null;
    }


    /**
     * Convenience overload of {@link #getExpectedVariablesForStructure(String, List)} for callers
     * with no subclass context. ⚠ Never override this one.
     *
     * @param structureToken
     *            a structure token, as above
     * @return the published Required+Expected names, or {@code null} when the structure is not in
     *         the product
     */
    default @Nullable List<String> getExpectedVariablesForStructure(String structureToken)
    {
        return getExpectedVariablesForStructure(structureToken, List.of());
    }


    /**
     * The declared CDISC Library products behind the structure-keyed accessors, as
     * {@code standards/...} cache keys in precedence order — <b>provenance for logs and SKIP
     * reasons only</b>, so a finding (or a skip) is traceable to the products consulted when
     * several are declared. Empty when unknown or not product-backed.
     *
     * <p>
     * ⚑ An inherited default here loses a <em>log detail</em>, never an answer — no engine decision
     * may ever branch on it. That is still not licence to inherit it: a decorator that wraps the
     * run provider must delegate it so provenance survives the wrapping.
     * </p>
     *
     * <p>
     * ⛔ <b>Phase 11 finding F6.</b> This was documented as "deliberately not a capability method in
     * {@code MetadataProviderDecoratorDelegationGuardTest}'s sense", and the two decorators that
     * consequently never delegated it were the rulespec and {@code .cdt} harnesses — so the
     * {@code Fix #369} SKIP diagnostic read {@code declared product(s) <unknown>} in exactly the
     * two places this area is debugged from. It <b>is</b> a capability method now, and the guard
     * enforces it.
     * </p>
     *
     * @return the declared product cache keys, highest precedence first; empty when unknown
     */
    default List<String> declaredStructureKeyedProducts()
    {
        return List.of();
    }


    /**
     * Returns the expected column order from the Library for the given domain.
     *
     * @param domain
     *            the domain name (e.g., "DM", "AE")
     * @return ordered list of variable names, or empty list if unknown
     */
    List<String> getColumnOrder(String domain);

    /**
     * One published occurrence of a variable NAME somewhere in a CDISC Library product: the domain
     * that publishes it, plus the two attributes the Library actually carries for it.
     *
     * <p>
     * ⛔ <b>Only label and data type — the Library publishes NO format and NO length.</b> Measured
     * 2026-08-28 across {@code variables_metadata.pkl} and {@code standards_details.pkl}: a
     * variable's complete attribute set is {@code _links, core, describedValueDomain, description,
     * label, name, ordinal, role, simpleDatatype, valueList}. A "same label, type and format"
     * comparison is therefore impossible against the Library; format would need Define-XML.
     * </p>
     *
     * <p>
     * ⚠ <b>{@code core} and {@code role} are deliberately absent.</b> Across the 24 repeated names
     * in SDTMIG 3.4 they disagree between domains in 54% and 38% of cases respectively — per-domain
     * variation is the norm there, not an anomaly, so comparing them would be pure noise.
     * </p>
     *
     * @param domain
     *            the domain publishing this occurrence, e.g. {@code AE}
     * @param label
     *            the published label
     * @param dataType
     *            the published {@code simpleDatatype}
     */
    record PublishedVariable(String domain, @Nullable String label, @Nullable String dataType)
    {
    }

    /**
     * <b>R11</b> — every published occurrence of a variable NAME across this provider's product,
     * regardless of domain. The name-keyed counterpart of the domain-keyed
     * {@link #getVariableMetadata(String, String)}.
     *
     * <p>
     * ⭐ <b>Why name-keyed and not "add SDTMIG as a peer standard".</b> Every ordinary variable
     * lookup is domain-keyed, and an ADaM dataset's domain is {@code ADAE}/{@code ADSL} — never an
     * SDTM domain — so a peer SDTM product would simply sit unread. Worse, ADaM runs build the
     * provider with no SDTM slot, so filling it flips {@code getRequiredVariables} /
     * {@code getExpectedVariables} / {@code getColumnOrder} onto {@code buildResolvedSdtm(domain)},
     * which for an ADaM name reaches the custom-domain sniffer and returns empty.
     * </p>
     *
     * <p>
     * ⚑ The default is an empty list, so only a provider that actually has a companion product
     * answers. An empty answer is the <b>not-applicable</b> case, not a failure: ADaM-native names
     * ({@code PARAMCD}, {@code AVAL}, {@code TRTP}, …) have no SDTM counterpart at all, and
     * measured, only 10 of 332 {@code adamig-1-3} variables do.
     * </p>
     *
     * @param variableName
     *            the variable name, e.g. {@code AETERM}
     * @return every occurrence, in domain order; empty when the name is published nowhere
     */
    default List<PublishedVariable> getPublishedVariablesByName(String variableName)
    {
        return List.of();
    }


    /**
     * Returns the KEY variable names for the given domain, ordered by their {@code KeySequence}
     * (Define-XML) / key-sequence attribute. Only the Define provider serves a meaningful ordering;
     * the default is an empty list so Library / datatable providers need not implement it. Backs
     * the {@code define_key_variables} operation (T2-residual).
     *
     * @param domain
     *            the domain name (e.g., "DM", "LB")
     * @return ordered list of key variable names, or empty list if unknown / not applicable
     */
    default List<String> getKeyVariables(String domain)
    {
        return List.of();
    }


    /**
     * Returns the set of dataset (domain) names the study Define-XML declares (the
     * {@code MetaDataVersion}'s {@code ItemGroupDef} names). Only the Define provider serves a
     * meaningful list; the default is an empty list so Library / datatable providers need not
     * implement it. Backs the {@code define_dataset_names} operation (T2-residual).
     *
     * @return the declared dataset names, or empty list if unknown / not applicable
     */
    default List<String> getDatasetNames()
    {
        return List.of();
    }


    /**
     * EC-13 — the union of variable NAMES across every dataset the run's IG standard defines (Java
     * mirror of the Python reference engine's {@code variable_names} operation). Only the
     * CDISC-Library product provider serves a meaningful list; the default is {@code null} so other
     * providers signal "not available" — the {@code variable_names} operation then SKIPs the rule
     * (mapped to {@code LIBRARY_NOT_AVAILABLE}). Backs the {@code variable_names} operation.
     *
     * @return the order-preserving union of standard variable names, or {@code null} when no
     *         product is configured / the library is unavailable
     */
    default @Nullable List<String> getStandardVariableNames()
    {
        return null;
    }


    /**
     * EC-14 layer (i) — the canonical union of standard dataset (domain) NAMES: the IG standard's
     * datasets unioned with the SDTM Model product's datasets (Java mirror of the Python reference
     * engine's {@code standard_domains} operation). Only the CDISC-Library product provider serves
     * a meaningful list; the default is {@code null} so other providers signal "not available" —
     * the {@code standard_domains} operation then SKIPs the rule (mapped to
     * {@code LIBRARY_NOT_AVAILABLE}). Backs the {@code standard_domains} operation.
     *
     * @return the order-preserving union of standard dataset names, or {@code null} when no product
     *         is configured / the library is unavailable
     */
    default @Nullable List<String> getStandardDatasetNames()
    {
        return null;
    }


    /**
     * Returns the model-level column order (all allowed variables for the observation class of the
     * given domain).
     *
     * @param domain
     *            the domain name
     * @return ordered list of variable names, or empty list if unknown
     */
    List<String> getModelColumnOrder(String domain);


    /**
     * Returns whether a domain is custom (not defined in the standard).
     *
     * @param domain
     *            the domain name
     * @return {@code true} if the domain is custom
     */
    boolean isDomainCustom(String domain);


    /**
     * Returns the codelist terms for a given codelist code.
     *
     * @param codelistCode
     *            the codelist code (e.g., "C66734")
     * @return list of term values, or empty list if unknown
     */
    List<String> getCodelistTerms(String codelistCode);


    /**
     * Returns variable metadata (type, role, label, etc.) for a specific variable in a domain.
     *
     * @param domain
     *            the domain name
     * @param variable
     *            the variable name
     * @return metadata as key-value pairs, or empty map if unknown
     */
    Map<String, String> getVariableMetadata(String domain, String variable);


    /**
     * Returns metadata for all variables defined in the Library for a domain. Each entry contains
     * keys: name, label, simpleDatatype, core, role, ordinal, codelist (codelist submission value
     * or null).
     *
     * @param domain
     *            the domain name
     * @return list of variable metadata maps, or empty list if domain unknown
     */
    List<Map<String, String>> getDomainVariables(String domain);


    /**
     * Returns metadata for all variables defined at the Model level for the observation class that
     * corresponds to the given IG domain. Entries use the same key set as
     * {@link #getDomainVariables(String)} (name, label, role, ordinal, ...). Variable names may
     * contain {@code "--"} wildcards that callers resolve to the current domain prefix.
     * <p>
     * Used by the {@code get_model_filtered_variables} operation (Fix #3), e.g. to obtain the set
     * of Model-level Timing variables the IG allows for a FINDINGS/INTERVENTIONS/EVENTS domain.
     * </p>
     *
     * @param domain
     *            the IG domain name (used to locate the observation class)
     * @return list of model-level variable metadata maps, or empty list if the class is unknown
     */
    default List<Map<String, String>> getModelVariables(String domain)
    {
        return List.of();
    }


    /**
     * Returns the list of published CDISC CT package identifiers available to the engine (e.g.
     * {@code "sdtmct-2023-10-26"}). Used by the {@code valid_codelist_dates} operation (Fix #4) to
     * derive the set of acceptable {@code TSVCDVER} values.
     *
     * @return list of CT package identifiers, or empty list if unavailable
     */
    default List<String> getPublishedCtPackages()
    {
        return List.of();
    }


    /**
     * Returns the set of values for a given CT attribute across every codelist (and term) in the
     * named CT package. Used by the {@code get_codelist_attributes} operation (CORE-001080), which
     * resolves a CT package id per-row from data columns and then extracts one of six attributes
     * from the package:
     *
     * <ul>
     * <li>{@code "Codelist CCODE"} — every codelist's {@code conceptId}</li>
     * <li>{@code "Codelist Value"} — every codelist's {@code submissionValue}</li>
     * <li>{@code "Term CCODE"} — every term's {@code conceptId} (across all codelists)</li>
     * <li>{@code "Term Value"} / {@code "Term Submission Value"} — every term's
     * {@code submissionValue}</li>
     * <li>{@code "Term Preferred Term"} — every term's {@code preferredTerm}</li>
     * </ul>
     *
     * <p>
     * The returned list is order-preserving and de-duplicated. Implementations without CT-package
     * access (or for an unknown package / attribute) return an empty list, which callers translate
     * into the {@link OperationExecutor#LIBRARY_NOT_AVAILABLE} skip sentinel.
     * </p>
     *
     * @param aCtPackageId
     *            the resolved CT package id (e.g. {@code "sdtmct-2024-09-27"})
     * @param aCtAttribute
     *            one of the six attribute names above
     * @return the attribute values (deduped, order-preserving), or empty list if unavailable
     */
    default List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
    {
        return List.of();
    }


    /**
     * Returns dataset-level metadata from the Library. Keys include: name, label, datasetStructure,
     * className.
     *
     * @param domain
     *            the domain name
     * @return metadata as key-value pairs, or empty map if unknown
     */
    Map<String, String> getDatasetMetadata(String domain);


    /**
     * Returns whether a codelist is extensible.
     *
     * @param codelistName
     *            the codelist submission value (e.g., "NY", "SEX")
     * @return {@code true} if extensible, {@code false} if non-extensible, {@code true} as default
     *         if codelist is unknown
     */
    boolean isCodelistExtensible(String codelistName);


    /**
     * Returns the codelist terms as submission-value to preferred-term mappings. Used for
     * TESTCD/TEST consistency checks.
     *
     * @param codelistName
     *            the codelist submission value
     * @return map of submission value to preferred term, or empty map if unknown
     */
    Map<String, String> getCodelistTermMappings(String codelistName);


    /**
     * E9 — the bound codelist's term submission value → term NCI concept-id (C-code) map for the
     * given variable in the given domain. Each entry maps a codelist term's {@code getCodeValue()}
     * (submission value, e.g. {@code "ALB"} / {@code "Albumin"}) to its {@code getConceptId()}
     * (e.g. {@code "C64431"}). Because paired {@code --TESTCD} / {@code --TEST} codelist terms
     * share their concept id in the CDISC CT, this map lets the
     * {@code library_variable_code_pair_matches} accessor compare a code value's concept id against
     * its paired decode value's concept id.
     *
     * <p>
     * The default is an empty map so datatable / Define providers need not implement it. The
     * Library provider ({@code MetadataLibraryProvider}) resolves the variable's bound codelist and
     * builds the map from its entries; an unbound variable or an unloaded codelist yields an empty
     * map.
     * </p>
     *
     * @param domain
     *            the domain name (e.g. {@code "LB"})
     * @param variable
     *            the variable name (e.g. {@code "LBTESTCD"} / {@code "LBTEST"})
     * @return submission value → concept id for the variable's bound codelist, or an empty map
     */
    default Map<String, String> getCodelistCodeMap(String domain, String variable)
    {
        return Map.of();
    }


    /**
     * Returns the standard name this provider is configured for.
     */
    @Nullable
    String getStandard();


    /**
     * Returns the standard version this provider is configured for.
     */
    @Nullable
    String getVersion();


    /**
     * Returns the Define-XML version of the loaded study library, if any (e.g. {@code "2.0.0"}).
     * Implementations that source metadata from a Define-XML file return its
     * {@code def:DefineVersion} attribute; implementations that read metadata from elsewhere (raw
     * study libraries, CDISC Library API only) return {@code null}.
     *
     * <p>
     * Used by {@code JsonReportWriter} to populate {@code Conformance_Details.Define_XML_Version};
     * a {@code null} return causes the field to be omitted from the report.
     * </p>
     *
     * @return the Define-XML version string, or {@code null} when the library was not built from a
     *         Define-XML
     */
    default @Nullable String getDefineVersion()
    {
        return null;
    }


    /**
     * Returns the ordered Model-level variable names that the standard exposes for the dataset's
     * observation class, including the IG-side overrides that {@code Python}'s
     * {@code get_variables_metadata_from_standard_model} produces. This is the load-bearing
     * accessor for Fix #42 Phase 2.
     *
     * <p>
     * Implementations with direct CDISC Library product access (see
     * {@code MetadataLibraryProvider}'s product-aware constructors) walk the typed
     * {@code SdtmClass.classVariables()} / ADaM {@code AdamVariableSet.analysisVariables()}
     * hierarchy. Implementations without product access return {@code null} as the "library not
     * available" signal — callers (e.g. {@code RuleRunner}) translate that into a SKIPPED rule
     * outcome, identically to how
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#LIBRARY_NOT_AVAILABLE} is treated for
     * other library-dependent operations.
     * </p>
     *
     * @param aTable
     *            the dataset whose model-level variables are requested. The dataset's domain name
     *            (and class, if known) is used to locate the owning observation class.
     * @param aResolver
     *            cross-domain resolver; may be needed when the model walk consults sibling datasets
     *            (e.g. SUPP/AP/SQ shimming). Implementations that don't need the resolver may
     *            ignore the argument.
     * @return ordered list of Model-level variable names, an empty list if the class is unknown
     *         (custom domain, etc.), or {@code null} if the implementation has no product access
     *         and therefore cannot answer.
     */
    default @Nullable List<String> getStandardModelVariables(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return null;
    }


    /**
     * Detailed companion to {@link #getStandardModelVariables} — returns the same resolved
     * variables, but each entry carries the variable's full attribute map (Python
     * {@code variables_metadata} shape: {@code name}, {@code role}, {@code core},
     * {@code simpleDatatype}, {@code label}, {@code ordinal}). The
     * {@code get_dataset_filtered_variables} and {@code get_model_filtered_variables} Operations
     * consume this output so they can filter by attribute (e.g. {@code role = "Timing"}) before
     * projecting to names.
     *
     * <p>
     * Implementations without product access return {@code null} as the "library not available"
     * signal — same semantics as the names-only counterpart.
     * </p>
     *
     * @param aTable
     *            the dataset whose model-level variables are requested.
     * @param aResolver
     *            cross-domain resolver; may be needed when the model walk consults sibling
     *            datasets. Implementations that don't need it may ignore the argument.
     * @return ordered list of attribute maps (one per resolved variable, with the substituted name
     *         under the {@code name} key), an empty list if the class is unknown, or {@code null}
     *         if the implementation has no product access.
     */
    default @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return null;
    }


    /**
     * EC-85 — the algorithm-A model walk for an <b>explicitly named</b> observation class, using
     * this dataset's domain only for the {@code --} substitution and the SUPP/AP shims. Returns the
     * same attribute-map shape as {@link #getStandardModelVariablesDetailed}. {@code null} means
     * library-not-available (no product access, an ADaM-only provider, or a class this model does
     * not carry) — the {@code get_model_filtered_variables} caller SKIPs the rule rather than
     * testing membership against nothing. Deliberately a separate method rather than a 3-arg
     * overload of the 2-arg accessor: an overload with a delegating default would orphan the
     * existing overrides in the delegating providers, which would keep compiling and stop being
     * called.
     *
     * @param aTable
     *            the dataset whose domain prefix substitutes {@code --}.
     * @param aResolver
     *            cross-domain resolver; implementations that don't need it may ignore it.
     * @param aModelClass
     *            the class name, already validated at rule load against
     *            {@link net.cumba.cdisc.core.metadata.SdtmObservationClasses#MODEL_CLASS_NAMES}.
     * @return ordered attribute maps (substituted name under {@code name}), or {@code null} when
     *         the class cannot be served.
     */
    default @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable aTable,
            DatasetResolver aResolver, String aModelClass)
    {
        return null;
    }


    /**
     * EC-85 — the class-keyed counterpart of {@link #getModelVariables(String)}: the legacy /
     * harness fallback {@code get_model_filtered_variables} takes when
     * {@link #getStandardModelVariablesForClass} returns {@code null}. Names are served
     * <em>unsubstituted</em> ({@code --TERM}); the operation substitutes the dataset's prefix.
     *
     * @param aModelClass
     *            the normalised class name (e.g. {@code "EVENTS"})
     * @return the class's model-variable metadata maps, or an empty list when unknown
     */
    default List<Map<String, String>> getModelVariablesForClass(String aModelClass)
    {
        return List.of();
    }


    /**
     * Detailed algorithm-B companion (IG-base + Model-merge) used by
     * {@code get_dataset_filtered_variables}. Mirrors Python's
     * {@code get_variables_metadata_from_standard}: the model-side class walk is overwritten by the
     * IG dataset variables (detectable classes) or replaced by the pure IG dataset variables
     * (non-detectable, non-custom), with custom domains emitting the model walk. Each entry carries
     * the variable's full attribute map (Python {@code variables_metadata} shape: {@code name},
     * {@code role}, {@code core}, {@code simpleDatatype}, {@code label}, {@code ordinal}).
     *
     * <p>
     * Contrast with {@link #getStandardModelVariablesDetailed}, which is the algorithm-A pure-Model
     * walk (no IG overwrite) consumed by {@code get_model_filtered_variables}.
     * </p>
     *
     * <p>
     * Implementations without product access return {@code null} as the "library not available"
     * signal — same semantics as the other detailed accessors.
     * </p>
     *
     * @param aTable
     *            the dataset whose IG-base + Model-merge variables are requested.
     * @param aResolver
     *            cross-domain resolver; may be needed when the walk consults sibling datasets.
     * @return ordered list of attribute maps (one per resolved variable, with the substituted name
     *         under the {@code name} key), an empty list if the class is unknown, or {@code null}
     *         if the implementation has no product access.
     */
    default @Nullable List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        return null;
    }


    /**
     * Returns the observation class name that owns the given dataset (e.g. {@code "Findings"},
     * {@code "Events"}, {@code "BASIC DATA STRUCTURE"}). Used by the Fix #41 custom-domain check
     * and Fix #42 Phase 2's class-hierarchy walk.
     *
     * <p>
     * Implementations with direct CDISC Library product access (see
     * {@code MetadataLibraryProvider}'s product-aware constructors) follow the documented
     * precedence: Define-XML class first, then product reverse-walk, then a custom-domain sniffer.
     * Implementations without product access return {@code null} — the rule path that consults this
     * method falls back to the per-table {@code IMetadataLibrary} class name.
     * </p>
     *
     * @param aDomain
     *            the dataset / domain name (e.g. {@code "AE"}, {@code "MYBP"})
     * @return the class name, or {@code null} when the implementation cannot resolve it
     */
    default @Nullable String getDatasetClass(String aDomain)
    {
        return null;
    }


    /**
     * Two-argument variant for split datasets (Fix #60). The IMetadataLibrary view is keyed by
     * <em>member name</em> (e.g. {@code LBHE}, {@code LBCH}); the CDISC products and the
     * custom-domain sniffer key off the <em>CDISC domain code</em> (e.g. {@code LB}). Callers that
     * have both — typically via
     * {@link net.cumba.cdisc.core.metadata.CdiscDomainResolver#cdiscDomainOf} — should prefer this
     * overload so the three resolution tiers each see the right key.
     *
     * <p>
     * The default implementation delegates to the single-arg form with {@code aCdiscDomain},
     * preserving pre-Fix-#60 behaviour for providers that do not override it.
     * </p>
     *
     * @param aMemberName
     *            the library member / file name as loaded (used for tier 1 IMetadataLibrary
     *            lookups)
     * @param aCdiscDomain
     *            the CDISC domain code (used for tier 2 product reverse-walk and the tier 3
     *            custom-domain sniffer's {@code --}-prefixed topic checks)
     * @return the class name, or {@code null} when no tier resolves it
     */
    default @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain)
    {
        return getDatasetClass(aCdiscDomain);
    }


    /**
     * Three-argument variant: {@code aActualColumns} are the column names of the dataset under
     * validation. The tier-3 custom-domain sniffer uses them to classify datasets the metadata
     * library does not carry (e.g. {@code SUPP--}), mirroring Python's
     * {@code handle_custom_domains} run against the actual dataset rather than a metadata-library
     * table.
     *
     * <p>
     * The default implementation ignores the columns and delegates to the two-arg form, preserving
     * behaviour for providers that do not override it.
     * </p>
     *
     * @param aMemberName
     *            the library member / file name as loaded (tier 1 IMetadataLibrary lookups)
     * @param aCdiscDomain
     *            the CDISC domain code (tier 2 product reverse-walk and the tier 3 sniffer)
     * @param aActualColumns
     *            column names of the dataset under validation; may be {@code null}/empty
     * @return the class name, or {@code null} when no tier resolves it
     */
    default @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain,
            @Nullable Set<String> aActualColumns)
    {
        return getDatasetClass(aMemberName, aCdiscDomain);
    }

}
