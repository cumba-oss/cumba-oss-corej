package net.cumba.corej.core.run;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.AbstractDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Mockito-free fixtures for the Trial Summary CT source tests
 * (PLAN-ct-packages-default-and-ts-source C10): a real character-only {@link IDataTable} and a
 * small reflective fake manager that serves a fixed set of members.
 */
final class TsTestSupport
{

    private TsTestSupport()
    {
    }


    /**
     * A character table; a {@code null} cell is a missing value.
     *
     * @param aName
     *            the table name
     * @param aCaseSensitive
     *            the meta's {@code columnNameCaseSensitive} — {@code true} proves a reader walks
     *            the names itself instead of trusting {@code getColumnIndex}
     * @param aColumns
     *            the column names
     * @param aRows
     *            the rows, one value per column
     * @return the table
     */
    static IDataTable table(String aName, boolean aCaseSensitive, List<String> aColumns,
            List<List<@Nullable String>> aRows)
    {
        DataTableColumnMeta[] cols = new DataTableColumnMeta[aColumns.size()];
        for (int c = 0; c < cols.length; c++)
        {
            cols[c] = DataTableColumnMeta.builder().index(c).name(aColumns.get(c))
                    .label(aColumns.get(c)).length(200).nativeType("Char")
                    .type(DataValueType.STRING)._build();
        }
        DataTableMeta meta = DataTableMeta.builder().name(aName).label(aName).rowCount(aRows.size())
                .totalRowCount(aRows.size()).columns(cols).columnNameCaseSensitive(aCaseSensitive)
                .build();
        return new AbstractDataTable()
        {

            @Override
            public long getRowCount()
            {
                return aRows.size();
            }


            @Override
            public @Nullable Object getValue(long aRow, int aColumn)
            {
                return aRows.get((int) aRow).get(aColumn);
            }


            @Override
            public DataTableMeta getMetaData()
            {
                return meta;
            }
        };
    }


    /** A Trial Summary with the SDTM TS CT columns, upper-case names. */
    static IDataTable sdtmTs(List<List<@Nullable String>> aRows)
    {
        return table("TS", true, List.of("STUDYID", "TSPARMCD", "TSVAL", "TSVCDREF", "TSVCDVER"),
                aRows);
    }


    /** A Trial Summary with the SEND TS columns (no TSVCDREF / TSVCDVER). */
    static IDataTable sendTs(List<List<@Nullable String>> aRows)
    {
        return table("TS", true, List.of("STUDYID", "TSPARMCD", "TSVAL"), aRows);
    }


    static List<@Nullable String> row(@Nullable String... aValues)
    {
        // Arrays.asList, not List.of: a null entry is a missing cell (List.of rejects nulls).
        return java.util.Arrays.asList(aValues);
    }


    /**
     * A fake manager over the given members (name → table supplier, in order): the methods a
     * validation run calls are answered, every other call returns the type's empty answer.
     *
     * @param aMembers
     *            the library members
     * @param aMetadata
     *            what {@code getMetadataLibrary} answers
     * @return the manager
     */
    static IDataTableManager manager(Map<String, Supplier<IDataTable>> aMembers,
            @Nullable IMetadataLibrary aMetadata)
    {
        IDataTableLibraryRef lib = fake(IDataTableLibraryRef.class,
                (m, a) -> "getUri".equals(m.getName()) ? "file:///study" : null);
        List<ILibraryMemberRef> members = new ArrayList<>();
        List<IDataTableRef> refs = new ArrayList<>();
        List<Supplier<IDataTable>> loads = new ArrayList<>();
        for (Map.Entry<String, Supplier<IDataTable>> e : aMembers.entrySet())
        {
            String name = e.getKey();
            members.add(fake(ILibraryMemberRef.class, (m, a) -> switch (m.getName())
            {
            case "getName" -> name;
            case "getUri" -> "file:///study/" + name.toLowerCase(java.util.Locale.ROOT) + ".csv";
            default -> null;
            }));
            refs.add(fake(IDataTableRef.class, (m, a) -> null));
            loads.add(e.getValue());
        }
        return fake(IDataTableManager.class, (m, a) -> switch (m.getName())
        {
        case "getLibraryRef" -> lib;
        case "getLibraryMembers" -> members.stream();
        case "getDataTableRef" -> refs.get(members.indexOf(a[0]));
        case "getDataTable" -> loads.get(refs.indexOf(a[0])).get();
        case "getMetadataLibrary" -> aMetadata;
        default -> null;
        });
    }

    /** What a fake answers for one call; {@code null} falls back to the type's empty answer. */
    @FunctionalInterface
    interface Answer
    {

        @Nullable
        Object answer(Method aMethod, @Nullable Object[] aArgs);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> aType, Answer aAnswer)
    {
        InvocationHandler handler = (proxy, method, args) ->
        {
            Object answer = switch (method.getName())
            {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> aType.getSimpleName() + "@fake";
            default -> aAnswer.answer(method, args);
            };
            return answer != null ? answer : emptyAnswer(method.getReturnType());
        };
        return (T) Proxy.newProxyInstance(TsTestSupport.class.getClassLoader(), new Class<?>[]
        {
                aType
        }, handler);
    }


    private static @Nullable Object emptyAnswer(Class<?> aType)
    {
        if (aType == boolean.class)
        {
            return false;
        }
        if (aType == int.class)
        {
            return 0;
        }
        if (aType == long.class)
        {
            return 0L;
        }
        if (aType == Optional.class)
        {
            return Optional.empty();
        }
        if (aType == Stream.class)
        {
            return Stream.empty();
        }
        if (aType == List.class)
        {
            return List.of();
        }
        return null;
    }
}
