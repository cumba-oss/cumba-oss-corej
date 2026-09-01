package net.cumba.cdisc.core.metadata;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.ICodelistEntry;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight in-memory {@link IMetadataLibrary} fixtures for tests in this package and sibling
 * test packages. No dependency on {@code datatable.impl}.
 */
public final class TestMetadataFixtures
{

    private TestMetadataFixtures()
    {
    }


    public static LibBuilder lib(String aName)
    {
        return new LibBuilder(aName);
    }


    public static TableBuilder table(String aName)
    {
        return new TableBuilder(aName);
    }


    public static ColumnBuilder column(String aName, int aIndex, DataValueType aType)
    {
        return new ColumnBuilder(aName, aIndex, aType);
    }


    public static CodelistBuilder codelist(String aName)
    {
        return new CodelistBuilder(aName);
    }


    public static ICodelistEntry entry(String aCode, String aDecode)
    {
        return new FixtureCodelistEntry(aCode, aDecode, null, Map.of());
    }


    /**
     * A codelist term that also carries its NCI concept id.
     *
     * <p>
     * ⚠ {@code ICodelistEntry.getConceptId()} defaults to {@code null}, and
     * {@code MetadataLibraryProvider.getCodelistCodeMap} keeps only entries where <b>both</b>
     * {@code getCodeValue()} and {@code getConceptId()} are non-null. A fixture built with the
     * two-argument {@link #entry(String, String)} therefore yields an <b>empty</b> code map however
     * many terms it declares — so any test of that accessor built on it passes vacuously. Use this
     * overload whenever the assertion is about {@code getCodelistCodeMap}.
     * </p>
     */
    public static ICodelistEntry entry(String aCode, String aDecode, String aConceptId)
    {
        return new FixtureCodelistEntry(aCode, aDecode, aConceptId, Map.of());
    }

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    public static final class LibBuilder
    {

        private final String name;

        private String version;

        private final List<IDataTableMetadata> tables = new ArrayList<>();

        private final List<ICodeList> codelists = new ArrayList<>();

        private final Map<String, Object> meta = new LinkedHashMap<>();

        LibBuilder(String aName)
        {
            name = aName;
        }


        public LibBuilder version(String aVersion)
        {
            version = aVersion;
            return this;
        }


        public LibBuilder table(IDataTableMetadata aTable)
        {
            tables.add(aTable);
            return this;
        }


        public LibBuilder codelist(ICodeList aCodelist)
        {
            codelists.add(aCodelist);
            return this;
        }


        public LibBuilder meta(String aKey, Object aValue)
        {
            meta.put(aKey, aValue);
            return this;
        }


        public IMetadataLibrary build()
        {
            return new FixtureLibrary(name, version, List.copyOf(tables), List.copyOf(codelists),
                    Map.copyOf(meta));
        }
    }


    public static final class TableBuilder
    {

        private final String name;

        private String label;

        private String className;

        private List<String> subClassNames = List.of();

        private String structure;

        private final List<IColumnMetadata> columns = new ArrayList<>();

        private final Map<String, Object> meta = new LinkedHashMap<>();

        TableBuilder(String aName)
        {
            name = aName;
        }


        public TableBuilder label(String aLabel)
        {
            label = aLabel;
            return this;
        }


        public TableBuilder className(String aClassName)
        {
            className = aClassName;
            return this;
        }


        /**
         * The study-declared {@code def:SubClass} names, most specific first — the tier
         * {@code AdamStructureContext.declaredSubClassesOf} and
         * {@code MetadataLibraryProvider.getDeclaredSubClasses} read.
         */
        public TableBuilder subClassNames(String... aSubClassNames)
        {
            subClassNames = List.of(aSubClassNames);
            return this;
        }


        public TableBuilder structure(String aStructure)
        {
            structure = aStructure;
            return this;
        }


        public TableBuilder column(IColumnMetadata aCol)
        {
            columns.add(aCol);
            return this;
        }


        public TableBuilder meta(String aKey, Object aValue)
        {
            meta.put(aKey, aValue);
            return this;
        }


        public IDataTableMetadata build()
        {
            return new FixtureTable(name, label, className, List.copyOf(subClassNames), structure,
                    List.copyOf(columns), Map.copyOf(meta));
        }
    }


    public static final class ColumnBuilder
    {

        private final String name;

        private final int index;

        private final DataValueType type;

        private String label;

        private String core;

        private String role;

        private String codelist;

        private int length;

        private final Map<String, Object> meta = new LinkedHashMap<>();

        ColumnBuilder(String aName, int aIndex, DataValueType aType)
        {
            name = aName;
            index = aIndex;
            type = aType;
        }


        public ColumnBuilder label(String aLabel)
        {
            label = aLabel;
            return this;
        }


        public ColumnBuilder core(String aCore)
        {
            core = aCore;
            return this;
        }


        public ColumnBuilder role(String aRole)
        {
            role = aRole;
            return this;
        }


        public ColumnBuilder codelist(String aCodelist)
        {
            codelist = aCodelist;
            return this;
        }


        public ColumnBuilder length(int aLength)
        {
            length = aLength;
            return this;
        }


        public ColumnBuilder meta(String aKey, Object aValue)
        {
            meta.put(aKey, aValue);
            return this;
        }


        public IColumnMetadata build()
        {
            return new FixtureColumn(name, label, index, type, core, role, codelist, length,
                    Map.copyOf(meta));
        }
    }


    public static final class CodelistBuilder
    {

        private final String name;

        private DataValueType type = DataValueType.STRING;

        private Boolean extensible;

        private final List<ICodelistEntry> entries = new ArrayList<>();

        private final Map<String, Object> meta = new LinkedHashMap<>();

        CodelistBuilder(String aName)
        {
            name = aName;
        }


        public CodelistBuilder extensible(Boolean aExt)
        {
            extensible = aExt;
            return this;
        }


        public CodelistBuilder entry(ICodelistEntry aEntry)
        {
            entries.add(aEntry);
            return this;
        }


        public CodelistBuilder entry(String aCode, String aDecode)
        {
            return entry(TestMetadataFixtures.entry(aCode, aDecode));
        }


        /**
         * See {@link TestMetadataFixtures#entry(String, String, String)} — needed for code maps.
         */
        public CodelistBuilder entry(String aCode, String aDecode, String aConceptId)
        {
            return entry(TestMetadataFixtures.entry(aCode, aDecode, aConceptId));
        }


        public CodelistBuilder meta(String aKey, Object aValue)
        {
            meta.put(aKey, aValue);
            return this;
        }


        public ICodeList build()
        {
            return new FixtureCodelist(name, type, extensible, List.copyOf(entries),
                    Map.copyOf(meta));
        }
    }

    // ------------------------------------------------------------------
    // Immutable records backing the builders
    // ------------------------------------------------------------------


    private record FixtureLibrary(String name, String version, List<IDataTableMetadata> tables,
            List<ICodeList> codelists, Map<String, Object> meta) implements IMetadataLibrary
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public String getVersion()
        {
            return version;
        }


        @Override
        public boolean isColumnNameCaseSensitive()
        {
            return false;
        }


        @Override
        public List<IDataTableMetadata> getDataTables()
        {
            return tables;
        }


        @Override
        public Optional<IDataTableMetadata> getDataTable(String aName)
        {
            for (IDataTableMetadata t : tables)
            {
                if (t.getName().equalsIgnoreCase(aName))
                {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }


        @Override
        public List<ICodeList> getCodelists()
        {
            return codelists;
        }


        @Override
        public Optional<ICodeList> getCodelist(String aName)
        {
            for (ICodeList c : codelists)
            {
                if (c.getName().equals(aName))
                {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }


    private record FixtureTable(String name, String label, String className,
            List<String> subClassNames, String structure, List<IColumnMetadata> columns,
            Map<String, Object> meta) implements IDataTableMetadata
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public String getLabel()
        {
            return label;
        }


        @Override
        public URI getTableURI()
        {
            return null;
        }


        @Override
        public List<IColumnMetadata> getColumns()
        {
            return columns;
        }


        @Override
        public Optional<IColumnMetadata> getColumn(String aName)
        {
            for (IColumnMetadata c : columns)
            {
                if (c.getName().equalsIgnoreCase(aName))
                {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }


        @Override
        public String getClassName()
        {
            return className;
        }


        @Override
        public List<String> getSubClassNames()
        {
            return subClassNames;
        }


        @Override
        public String getStructure()
        {
            return structure;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }


    private record FixtureColumn(String name, String label, int index, DataValueType type,
            String core, String role, String codelist, int length,
            Map<String, Object> meta) implements IColumnMetadata
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public String getLabel()
        {
            return label;
        }


        @Override
        public String getDisplayFormat()
        {
            return null;
        }


        @Override
        public int getIndex()
        {
            return index;
        }


        @Override
        public DataValueType getType()
        {
            return type;
        }


        @Override
        public int getLength()
        {
            return length;
        }


        @Override
        public String getNativeType()
        {
            return null;
        }


        @Override
        public int getKeySequence()
        {
            return 0;
        }


        @Override
        public boolean isByGroup()
        {
            return false;
        }


        @Override
        public String getCore()
        {
            return core;
        }


        @Override
        public String getRole()
        {
            return role;
        }


        @Override
        public String getCodelist()
        {
            return codelist;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }


    private record FixtureCodelist(String name, DataValueType type, Boolean extensible,
            List<ICodelistEntry> entries, Map<String, Object> meta) implements ICodeList
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public DataValueType getValueType()
        {
            return type;
        }


        @Override
        public List<ICodelistEntry> getEntries()
        {
            return entries;
        }


        @Override
        public Boolean isExtensible()
        {
            return extensible;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }


    private record FixtureCodelistEntry(String code, String decode, @Nullable String conceptId,
            Map<String, Object> meta) implements ICodelistEntry
    {

        @Override
        public String getCodeValue()
        {
            return code;
        }


        @Override
        public @Nullable String getConceptId()
        {
            return conceptId;
        }


        @Override
        public String getDecodeValue()
        {
            return decode;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }

}
