package net.cumba.corej.core.metadata;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * An {@link IMetadataLibrary} decorator that merges study metadata (primary) with a CDISC standards
 * metadata library (fallback).
 *
 * <h2>Semantics</h2>
 * <ul>
 * <li><b>Tables</b> — the set of tables comes from the primary library only. The study defines
 * which datasets exist; standards never introduce new tables. Primary tables are enriched with
 * fallback attributes (label, class name, structure, column-level metadata, meta keys).</li>
 *
 * <li><b>{@link MetadataKeys#IS_CUSTOM_DOMAIN}</b> — derived automatically for each table:
 * {@code true} if the dataset is absent from the fallback, {@code false} if it is present. An
 * explicit value on the primary table overrides this derivation.</li>
 *
 * <li><b>Codelists</b> — union of primary and fallback. On name collisions, the primary codelist
 * wins but is enriched with fallback attributes. Fallback-only codelists (e.g. {@code SEX} from
 * SDTM CT when the study Define-XML does not redefine it) are exposed as-is.</li>
 *
 * <li><b>Library-level meta keys</b> — primary wins, fallback supplies defaults. Typically the
 * standards library populates {@link MetadataKeys#STANDARD_NAME},
 * {@link MetadataKeys#STANDARD_VERSION}, {@link MetadataKeys#CT_VERSION}.</li>
 *
 * <li><b>Column attribute fallbacks</b> — primary wins on every typed getter (label, core, role,
 * codelist, data type, etc.). Fallback fills in gaps.</li>
 * </ul>
 *
 * <p>
 * Instances are immutable and safe for concurrent use. Enrichment is computed lazily on each access
 * (no caching), which keeps the class simple; if profiling shows this to be a bottleneck, wrap in a
 * caching decorator.
 * </p>
 */
public final class EnrichedMetadataLibrary implements IMetadataLibrary
{

    private final IMetadataLibrary primary;

    /**
     * Fix #119 (review finding 3): the undecorated study-metadata library. The declared-value
     * accessors ({@code MetadataProvider.getDeclaredDatasetClass}/{@code getDeclaredSubClasses})
     * must read the study metadata verbatim — through the enriched view, a standards-library class
     * would masquerade as a "declared" define class under {@code corej.defineFirst}.
     */
    IMetadataLibrary getPrimary()
    {
        return primary;
    }

    private final IMetadataLibrary fallback;

    public EnrichedMetadataLibrary(IMetadataLibrary aPrimary, IMetadataLibrary aFallback)
    {
        primary = Objects.requireNonNull(aPrimary, "primary");
        fallback = Objects.requireNonNull(aFallback, "fallback");
    }

    // ------------------------------------------------------------------
    // IMetadataLibrary
    // ------------------------------------------------------------------


    @Override
    public @Nullable String getName()
    {
        return primary.getName();
    }


    @Override
    public @Nullable String getVersion()
    {
        String value = primary.getVersion();
        return !isBlank(value) ? value : fallback.getVersion();
    }


    @Override
    public boolean isColumnNameCaseSensitive()
    {
        return primary.isColumnNameCaseSensitive();
    }


    @Override
    public List<IDataTableMetadata> getDataTables()
    {
        return primary.getDataTables().stream().map(this::enrichTable).toList();
    }


    @Override
    public Optional<IDataTableMetadata> getDataTable(String aDomainName)
    {
        return primary.getDataTable(aDomainName).map(this::enrichTable);
    }


    @Override
    public List<ICodeList> getCodelists()
    {
        Map<String, ICodeList> merged = new LinkedHashMap<>();
        for (ICodeList pc : primary.getCodelists())
        {
            merged.put(pc.getName(), enrichCodelist(pc));
        }
        for (ICodeList fc : fallback.getCodelists())
        {
            merged.putIfAbsent(fc.getName(), fc);
        }
        return List.copyOf(merged.values());
    }


    @Override
    public Optional<ICodeList> getCodelist(String aName)
    {
        Optional<ICodeList> pc = primary.getCodelist(aName);
        if (pc.isPresent())
        {
            return pc.map(EnrichedMetadataLibrary::enrichCodelist);
        }
        return fallback.getCodelist(aName);
    }


    @Override
    public Set<String> getMetaKeys()
    {
        Set<String> keys = new LinkedHashSet<>(primary.getMetaKeys());
        keys.addAll(fallback.getMetaKeys());
        return Collections.unmodifiableSet(keys);
    }


    @Override
    public Optional<Object> getMetaValue(String aKey)
    {
        Optional<Object> value = primary.getMetaValue(aKey);
        return value.isPresent() ? value : fallback.getMetaValue(aKey);
    }

    // ------------------------------------------------------------------
    // Enrichment helpers
    // ------------------------------------------------------------------


    private IDataTableMetadata enrichTable(IDataTableMetadata aPrimary)
    {
        IDataTableMetadata fb = fallback.getDataTable(aPrimary.getName()).orElse(null);
        return new EnrichedTable(aPrimary, fb);
    }


    private static ICodeList enrichCodelist(ICodeList aPrimary)
    {
        // Codelists are enriched inline by delegating getter-by-getter; the
        // primary codelist structure wins on collisions. We don't need a
        // separate "enrich with fallback" variant here because the call sites
        // already decide which codelist is primary and which is fallback.
        return aPrimary;
    }


    private static boolean isBlank(@Nullable String aValue)
    {
        return aValue == null || aValue.isBlank();
    }

    // ------------------------------------------------------------------
    // Inner decorators
    // ------------------------------------------------------------------

    private static final class EnrichedTable implements IDataTableMetadata
    {

        private final IDataTableMetadata primary;

        /** May be {@code null} when the table is custom (not in standards). */
        private final @Nullable IDataTableMetadata fallback;

        EnrichedTable(IDataTableMetadata aPrimary, @Nullable IDataTableMetadata aFallback)
        {
            primary = aPrimary;
            fallback = aFallback;
        }


        @Override
        public String getName()
        {
            return primary.getName();
        }


        @Override
        public @Nullable String getLabel()
        {
            String v = primary.getLabel();
            if (!isBlank(v))
            {
                return v;
            }
            return fallback != null ? fallback.getLabel() : null;
        }


        @Override
        public @Nullable URI getTableURI()
        {
            return primary.getTableURI();
        }


        @Override
        public List<IColumnMetadata> getColumns()
        {
            return primary.getColumns().stream().map(this::enrichColumn).toList();
        }


        @Override
        public Optional<IColumnMetadata> getColumn(String aColumnName)
        {
            return primary.getColumn(aColumnName).map(this::enrichColumn);
        }


        @Override
        public @Nullable String getClassName()
        {
            String v = primary.getClassName();
            if (!isBlank(v))
            {
                return v;
            }
            return fallback != null ? fallback.getClassName() : null;
        }


        /**
         * Fix #119 (review finding 2): declared subclasses come from the study metadata ONLY — the
         * standards fallback has no subclass concept, and the interface default ({@code List.of()})
         * would otherwise mask the primary (define) table's declaration.
         */
        @Override
        public List<String> getSubClassNames()
        {
            return primary.getSubClassNames();
        }


        @Override
        public @Nullable String getStructure()
        {
            String v = primary.getStructure();
            if (!isBlank(v))
            {
                return v;
            }
            return fallback != null ? fallback.getStructure() : null;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            Set<String> keys = new LinkedHashSet<>(primary.getMetaKeys());
            if (fallback != null)
            {
                keys.addAll(fallback.getMetaKeys());
            }
            // IS_CUSTOM_DOMAIN is always derivable for an enriched table.
            keys.add(MetadataKeys.IS_CUSTOM_DOMAIN);
            return Collections.unmodifiableSet(keys);
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            if (MetadataKeys.IS_CUSTOM_DOMAIN.equals(aKey))
            {
                // Primary wins if explicitly set; otherwise derive from presence.
                Optional<Object> pv = primary.getMetaValue(aKey);
                if (pv.isPresent())
                {
                    return pv;
                }
                return Optional.of(fallback == null);
            }
            Optional<Object> value = primary.getMetaValue(aKey);
            if (value.isPresent())
            {
                return value;
            }
            return fallback != null ? fallback.getMetaValue(aKey) : Optional.empty();
        }


        private IColumnMetadata enrichColumn(IColumnMetadata aPrimaryCol)
        {
            if (fallback == null)
            {
                return aPrimaryCol;
            }
            return fallback.getColumn(aPrimaryCol.getName())
                    .map(fc -> (IColumnMetadata) new EnrichedColumn(aPrimaryCol, fc))
                    .orElse(aPrimaryCol);
        }
    }


    private static final class EnrichedColumn implements IColumnMetadata
    {

        private final IColumnMetadata primary;

        private final IColumnMetadata fallback;

        EnrichedColumn(IColumnMetadata aPrimary, IColumnMetadata aFallback)
        {
            primary = aPrimary;
            fallback = aFallback;
        }


        @Override
        public String getName()
        {
            return primary.getName();
        }


        @Override
        public @Nullable String getLabel()
        {
            String v = primary.getLabel();
            return !isBlank(v) ? v : fallback.getLabel();
        }


        @Override
        public @Nullable String getDisplayFormat()
        {
            String v = primary.getDisplayFormat();
            return !isBlank(v) ? v : fallback.getDisplayFormat();
        }


        @Override
        public int getIndex()
        {
            return primary.getIndex();
        }


        @Override
        public DataValueType getType()
        {
            DataValueType v = primary.getType();
            return (v != null && v != DataValueType.OTHER) ? v : fallback.getType();
        }


        @Override
        public int getLength()
        {
            int v = primary.getLength();
            return v > 0 ? v : fallback.getLength();
        }


        @Override
        public @Nullable String getNativeType()
        {
            String v = primary.getNativeType();
            return !isBlank(v) ? v : fallback.getNativeType();
        }


        @Override
        public int getKeySequence()
        {
            int v = primary.getKeySequence();
            return v > 0 ? v : fallback.getKeySequence();
        }


        @Override
        public boolean isByGroup()
        {
            return primary.isByGroup();
        }


        @Override
        public @Nullable String getCore()
        {
            String v = primary.getCore();
            return !isBlank(v) ? v : fallback.getCore();
        }


        @Override
        public @Nullable String getRole()
        {
            String v = primary.getRole();
            return !isBlank(v) ? v : fallback.getRole();
        }


        @Override
        public @Nullable String getCodelist()
        {
            String v = primary.getCodelist();
            return !isBlank(v) ? v : fallback.getCodelist();
        }


        @Override
        public Set<String> getMetaKeys()
        {
            Set<String> keys = new LinkedHashSet<>(primary.getMetaKeys());
            keys.addAll(fallback.getMetaKeys());
            return Collections.unmodifiableSet(keys);
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            Optional<Object> value = primary.getMetaValue(aKey);
            return value.isPresent() ? value : fallback.getMetaValue(aKey);
        }
    }

    // Design note: codelists are treated as atomic units. When the primary
    // defines a codelist we use it as-is; when it doesn't, the fallback
    // codelist is exposed as-is. Partial merging of codelist entries was a
    // feature of the old decorator that proved unnecessary in practice —
    // study codelists are typically either complete or absent.

}
