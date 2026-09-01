package net.cumba.cdisc.core.metadata;

import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataElement;
import net.cumba.datatable.metadata.IMetadataLibrary;

/**
 * Public constants for the meta-key contract between {@link IMetadataLibrary} producers and
 * {@link MetadataLibraryProvider}.
 *
 * <p>
 * A metadata library producer (Define-XML adapter, CDISC Library adapter, {@code .dblib} adapter,
 * etc.) populates these keys via {@link IMetadataElement#getMetaValue(String)}. The provider reads
 * them in a source-agnostic way, so that any well-behaved producer can be plugged into the rule
 * engine.
 * </p>
 *
 * <p>
 * Keys are grouped by the element level on which they are expected.
 * </p>
 */
public final class MetadataKeys
{

    private MetadataKeys()
    {
        // constants holder
    }

    // ------------------------------------------------------------------
    // IMetadataLibrary level
    // ------------------------------------------------------------------

    /**
     * {@code String} — the CDISC standard identifier the library represents. Typical values:
     * {@code "sdtmig"}, {@code "adamig"}, {@code "sendig"}.
     *
     * @see IMetadataLibrary
     */
    public static final String STANDARD_NAME = "StandardName";

    /**
     * {@code String} — the version of the standard referenced by {@link #STANDARD_NAME}. Typical
     * values: {@code "3-4"}, {@code "1-3"}.
     *
     * @see IMetadataLibrary
     */
    public static final String STANDARD_VERSION = "StandardVersion";

    /**
     * {@code String} — the identifier of the CDISC CT package associated with the library, e.g.
     * {@code "sdtmct-2024-03-29"}.
     *
     * @see IMetadataLibrary
     */
    public static final String CT_VERSION = "CtVersion";

    // ------------------------------------------------------------------
    // IDataTableMetadata level
    // ------------------------------------------------------------------

    /**
     * {@code Boolean} — {@code true} if the dataset is custom (not part of the referenced
     * standard). If absent, the dataset is assumed to be standard.
     *
     * @see IDataTableMetadata
     */
    public static final String IS_CUSTOM_DOMAIN = "IsCustomDomain";

    /**
     * {@code List<String>} — the ordered list of variable names defined at the observation class
     * level (SDTM) or data-structure level (ADaM). This is distinct from the dataset's own column
     * order returned by {@link IDataTableMetadata#getColumns()}.
     *
     * @see IDataTableMetadata
     */
    public static final String MODEL_COLUMN_ORDER = "ModelColumnOrder";

    /**
     * {@code List<Map<String,String>>} — Model-level variable metadata for the observation class of
     * this dataset. Each entry uses the same key set as the per-variable map returned by
     * {@code MetadataLibraryProvider.getDomainVariables} (name, label, simpleDatatype, core, role,
     * ordinal, codelist). Variable names may contain the {@code "--"} wildcard which the rule
     * engine expands at operation evaluation time.
     *
     * @see IDataTableMetadata
     */
    public static final String MODEL_VARIABLES = "ModelVariables";

    /**
     * {@code List<String>} — published CT-package identifiers available to this metadata library
     * (e.g. {@code "sdtmct-2024-03-29"}, {@code "adamct-2024-03-29"}). Populated at the library
     * level by producers that can enumerate more than one CT package; the rule engine's
     * {@code valid_codelist_dates} operation (Fix #4) derives the set of acceptable package dates
     * from this list.
     *
     * @see IMetadataLibrary
     */
    public static final String PUBLISHED_CT_PACKAGES = "PublishedCtPackages";

    /**
     * {@code String} — mirror of {@link IDataTableMetadata#getClassName()} as a meta-key. Producers
     * that cannot populate the typed getter may populate this key instead; readers fall through the
     * getter first.
     *
     * @see IDataTableMetadata
     */
    public static final String CLASS_NAME = "ClassName";

    /**
     * {@code String} — mirror of {@link IDataTableMetadata#getStructure()} as a meta-key. Producers
     * that cannot populate the typed getter may populate this key instead; readers fall through the
     * getter first.
     *
     * @see IDataTableMetadata
     */
    public static final String DATASET_STRUCTURE = "DatasetStructure";

    // ------------------------------------------------------------------
    // IColumnMetadata level — fallbacks for typed getters
    // ------------------------------------------------------------------

    /**
     * {@code String} — fallback for {@link IColumnMetadata#getCore()} when the typed getter returns
     * {@code null}. Typical values: {@code "Req"}, {@code "Exp"}, {@code "Perm"}, {@code "Cond"}.
     *
     * @see IColumnMetadata
     */
    public static final String CORE = "Core";

    /**
     * {@code String} — fallback for {@link IColumnMetadata#getRole()} when the typed getter returns
     * {@code null}.
     *
     * @see IColumnMetadata
     */
    public static final String ROLE = "Role";

    /**
     * {@code String} — fallback for {@link IColumnMetadata#getCodelist()} when the typed getter
     * returns {@code null}.
     *
     * @see IColumnMetadata
     */
    public static final String CODELIST = "Codelist";

    // ------------------------------------------------------------------
    // ICodeList level
    // ------------------------------------------------------------------

    /**
     * {@code String} — the NCI Thesaurus concept identifier of the codelist (pattern:
     * {@code C\d+}). Allows the provider to find codelists by C-code as well as by submission
     * value.
     *
     * @see ICodeList
     */
    public static final String CODELIST_CONCEPT_ID = "CodelistConceptId";

    /**
     * {@code String} — the codelist submission value, when it differs from
     * {@link ICodeList#getName()}. Used as a secondary lookup key in the provider.
     *
     * @see ICodeList
     */
    public static final String CODELIST_SUBMISSION_VALUE = "CodelistSubmissionValue";

}
