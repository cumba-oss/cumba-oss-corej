package net.cumba.corej.core.run;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import net.cumba.corej.core.gen.CtStandardRef;

/**
 * The run's resolved controlled-terminology selection — which CT package ids the run intends to
 * load, and where they came from (define-ct plan §4.2, owner ruling D1).
 *
 * <p>
 * Precedence: an explicit user selection wins outright; a blank field takes the Define-XML's
 * declared CT set; with neither, the run has no CT and every rule needing a CT package SKIPs (§4.4
 * row 1 — <em>nothing named</em> is the cache ruling's skip, never an abort).
 * </p>
 *
 * <p>
 * The {@link #source()} matters beyond bookkeeping: D3's abort for a named-but-unavailable package
 * applies to whatever set is <em>in play</em>, and with a populated user field the define's
 * declaration is not consulted, not resolved and not validated (§4.4 row 6 — the escape hatch), so
 * a {@link Source#USER} selection must never re-surface the declared ids.
 * </p>
 *
 * @param source
 *            where the selection came from
 * @param packageIds
 *            the CT package ids the run intends to load, in precedence order, duplicate-free; empty
 *            only for {@link Source#NONE}
 */
public record CtSelection(Source source, List<String> packageIds)
{

    /** Where a run's CT selection came from. */
    public enum Source
    {
        /** Nothing named: blank field and no declaration — the run has no CT. */
        NONE,

        /** The user's {@code CT Packages} field — the declaration is out of play entirely. */
        USER,

        /** The Define-XML's {@code def:Standards} declaration (blank field, 2.1 define). */
        DEFINE
    }

    public CtSelection
    {
        packageIds = List.copyOf(packageIds);
    }


    /**
     * Resolves the run's CT selection per D1: {@code aUserIds} non-empty → {@link Source#USER} with
     * exactly those ids; otherwise a non-empty {@code aDeclared} → {@link Source#DEFINE} with the
     * declared package ids in document order (duplicates dropped); otherwise {@link Source#NONE}.
     *
     * @param aUserIds
     *            the user's {@code CT Packages} field, possibly empty
     * @param aDeclared
     *            the Define-XML's declared CT packages, possibly empty
     * @return the resolved selection
     */
    public static CtSelection resolve(List<String> aUserIds, List<CtStandardRef> aDeclared)
    {
        if (!aUserIds.isEmpty())
        {
            return new CtSelection(Source.USER, aUserIds);
        }
        if (!aDeclared.isEmpty())
        {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (CtStandardRef ref : aDeclared)
            {
                ids.add(ref.packageId());
            }
            return new CtSelection(Source.DEFINE, new ArrayList<>(ids));
        }
        return new CtSelection(Source.NONE, List.of());
    }
}
