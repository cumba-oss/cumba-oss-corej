package net.cumba.corej.core.run;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.jspecify.annotations.Nullable;

/**
 * A small {@link IDataTableManager} fake for the {@link StudyValidationService} tests (no Mockito):
 * every library it opens is a single member {@code DM} backed by one table. It also counts the
 * library references the service takes through {@code getLibraryRef(URI, …)} against the ones it
 * gives back through {@code releaseLibraryRef}, for the day the service releases them (runbook W1b
 * LR left the pack: the OSS datatable manager has no release call, so the edit cannot be a
 * byte-identical twin). Runbook W1b DP1a uses it.
 *
 * <p>
 * Built as a dynamic proxy so the interface's many unrelated methods need no stubs: an unhandled
 * method answers its type's empty value ({@code null}, {@code false}, {@code 0}, an empty stream /
 * list / optional).
 * </p>
 */
final class CountingStudyManager implements InvocationHandler
{

    final List<Object> acquired = new ArrayList<>();

    final List<Object> released = new ArrayList<>();

    /** Contract violations the manager would report: a foreign ref or a repeated release. */
    final List<String> violations = new ArrayList<>();

    private final IDataTable dm;

    private final @Nullable IMetadataLibrary metadata;

    private final ILibraryMemberRef member;

    private CountingStudyManager(IDataTable aDm, @Nullable IMetadataLibrary aMetadata)
    {
        dm = aDm;
        metadata = aMetadata;
        member = (ILibraryMemberRef) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]
                {
                        ILibraryMemberRef.class
                }, (proxy, method, args) -> switch (method.getName())
                {
                case "getName" -> "DM";
                case "getUri" -> "file:///study/dm.csv";
                case "toString" -> "member DM";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> emptyValue(method.getReturnType());
                });
    }


    /** The manager, with the counting handler reachable through {@link #handler}. */
    static IDataTableManager create(IDataTable aDm, @Nullable IMetadataLibrary aMetadata)
    {
        CountingStudyManager handler = new CountingStudyManager(aDm, aMetadata);
        return (IDataTableManager) Proxy
                .newProxyInstance(CountingStudyManager.class.getClassLoader(), new Class<?>[]
                {
                        IDataTableManager.class
                }, handler);
    }


    static CountingStudyManager handler(IDataTableManager aManager)
    {
        return (CountingStudyManager) Proxy.getInvocationHandler(aManager);
    }


    @Override
    public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args)
    {
        Object[] a = args == null ? new Object[0] : args;
        return switch (method.getName())
        {
        case "getLibraryRef" -> a.length > 0 && a[0] instanceof URI uri ? open(uri)
                : acquired.isEmpty() ? null : acquired.get(0); // a borrowed view of a member
        case "releaseLibraryRef" -> release(a[0]);
        case "getLibraryMembers" -> Stream.of(member);
        case "getDataTableRef" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]
            {
                    IDataTableRef.class
            }, (p, m, x) -> switch (m.getName())
            {
            case "toString" -> "ref DM";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == x[0];
            default -> emptyValue(m.getReturnType());
            });
        case "getDataTable" -> dm;
        case "getMetadataLibrary" -> metadata;
        case "toString" -> "CountingStudyManager";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == a[0];
        default -> emptyValue(method.getReturnType());
        };
    }


    private IDataTableLibraryRef open(URI uri)
    {
        IDataTableLibraryRef ref = (IDataTableLibraryRef) Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[]
                {
                        IDataTableLibraryRef.class
                }, (p, m, x) -> switch (m.getName())
                {
                case "getUri" -> uri.toString();
                case "toString" -> "library " + uri;
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == x[0];
                default -> emptyValue(m.getReturnType());
                });
        acquired.add(ref);
        return ref;
    }


    private @Nullable Object release(Object ref)
    {
        if (!contains(acquired, ref))
        {
            violations.add("release of a ref this manager never handed out: " + ref);
        }
        else if (contains(released, ref))
        {
            violations.add("repeated release of " + ref);
        }
        released.add(ref);
        return null;
    }


    private static boolean contains(List<Object> refs, Object ref)
    {
        for (Object r : refs)
        {
            if (r == ref)
            {
                return true;
            }
        }
        return false;
    }


    private static @Nullable Object emptyValue(Class<?> type)
    {
        if (type == boolean.class)
        {
            return false;
        }
        if (type == int.class)
        {
            return 0;
        }
        if (type == long.class)
        {
            return 0L;
        }
        if (type == double.class)
        {
            return 0.0d;
        }
        if (type == Stream.class)
        {
            return Stream.empty();
        }
        if (type == List.class)
        {
            return List.of();
        }
        if (type == Optional.class)
        {
            return Optional.empty();
        }
        return null;
    }
}
