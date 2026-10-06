package net.cumba.corej.core.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import net.cumba.corej.core.gen.CtStandardRef;
import org.jspecify.annotations.Nullable;

/**
 * The run's resolved controlled-terminology selection — which CT package ids the run intends to
 * load, and where they came from (define-ct plan §4.2, owner ruling D1; the Trial Summary source,
 * PLAN-ct-packages-default-and-ts-source CTD-2, owner 2026-10-05).
 *
 * <p>
 * Precedence: an explicit user selection wins outright; a blank field takes the Define-XML's
 * declared CT set; failing that, the CDISC CT version(s) the study's Trial Summary states; with
 * none of them, the run has no CT and every rule needing a CT package SKIPs (§4.4 row 1 —
 * <em>nothing named</em> is the cache ruling's skip, never an abort).
 * </p>
 *
 * <p>
 * The {@link #source()} matters beyond bookkeeping: D3's abort for a named-but-unavailable package
 * applies to whatever set is <em>in play</em>, and with a populated user field the define's
 * declaration and the Trial Summary are not consulted, not resolved and not validated (§4.4 row 6 —
 * the escape hatch), so a {@link Source#USER} selection must never re-surface the declared or
 * TS-derived ids.
 * </p>
 *
 * @param source
 *            where the selection came from
 * @param packageIds
 *            the CT package ids the run intends to load, in precedence order, duplicate-free; empty
 *            only for {@link Source#NONE}
 * @param trialSummary
 *            what the Trial Summary read produced, non-null exactly when that read ran (fold F2):
 *            on a {@link Source#TS} selection, and on the {@link Source#DEFINE} /
 *            {@link Source#NONE} selection that followed a read yielding no package
 */
public record CtSelection(Source source, List<String> packageIds,
        @Nullable TrialSummaryEvidence trialSummary)
{

    /** Where a run's CT selection came from. */
    public enum Source
    {
        /**
         * Nothing named: blank field, no usable declaration, no TS-stated CT — the run has no CT.
         */
        NONE,

        /**
         * The user's {@code CT Packages} field — the declaration and TS are out of play entirely.
         */
        USER,

        /** The Define-XML's {@code def:Standards} declaration (blank field, 2.1 define). */
        DEFINE,

        /**
         * The study's Trial Summary (CTD-2): blank field, no define declaration for a root the run
         * consumes, and TS rows stating a CDISC CT version.
         */
        TS
    }


    /**
     * One TS-derived CT package, with the TS values the run note and the abort message cite.
     *
     * @param packageId
     *            the derived package id, e.g. {@code sdtmct-2023-12-15}
     * @param evidence
     *            the TS values it came from, e.g.
     *            {@code TSVCDREF "CDISC CT", TSVCDVER "2023-12-15"}
     */
    public record TsRef(String packageId, String evidence)
    {
    }


    /**
     * What a Trial Summary read produced.
     *
     * @param refs
     *            the CT packages the TS states, distinct, newest first
     * @param ignored
     *            the CDISC-CT values not taken because they are not one valid ISO date (CQ4 (a)),
     *            each rendered with the TS values it came from
     */
    public record TrialSummaryEvidence(List<TsRef> refs, List<String> ignored)
    {

        /** Nothing read: TS absent, without CDISC-CT rows, or unreadable (CQ6). */
        public static final TrialSummaryEvidence EMPTY = new TrialSummaryEvidence(List.of(),
                List.of());

        public TrialSummaryEvidence
        {
            refs = List.copyOf(refs);
            ignored = List.copyOf(ignored);
        }


        /**
         * The TS values a package id came from, or {@code null} when it is not one of
         * {@link #refs}.
         *
         * @param aPackageId
         *            a CT package id
         * @return the evidence, or {@code null}
         */
        public @Nullable String evidenceFor(String aPackageId)
        {
            for (TsRef ref : refs)
            {
                if (ref.packageId().equals(aPackageId))
                {
                    return ref.evidence();
                }
            }
            return null;
        }
    }

    public CtSelection
    {
        packageIds = List.copyOf(packageIds);
    }


    /**
     * Every non-TS construction site keeps its two-component call shape.
     *
     * @param source
     *            where the selection came from
     * @param packageIds
     *            the CT package ids the run intends to load
     */
    public CtSelection(Source source, List<String> packageIds)
    {
        this(source, packageIds, null);
    }


    /**
     * Resolves the run's CT selection per D1: {@code aUserIds} non-empty → {@link Source#USER} with
     * exactly those ids; otherwise a non-empty {@code aDeclared} → {@link Source#DEFINE} with the
     * declared package ids in document order (duplicates dropped); otherwise {@link Source#NONE}.
     * The Trial Summary is not consulted — this form serves the Define-XML conformance run, which
     * has no datasets.
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
            return new CtSelection(Source.DEFINE, declaredIds(aDeclared));
        }
        return new CtSelection(Source.NONE, List.of());
    }


    /**
     * Resolves a dataset run's CT selection (CTD-2, fold F1) in five steps:
     * <ol>
     * <li>a non-empty {@code aUserIds} → {@link Source#USER}, exactly those ids;</li>
     * <li>the define names at least one package of a root in {@code aConsumedRoots} →
     * {@link Source#DEFINE} with <b>all</b> its declared ids (document order, duplicates dropped),
     * exactly as the two-argument form;</li>
     * <li>{@code aTrialSummary} is non-null (the run's root reads TS) and the read yields at least
     * one package → {@link Source#TS}, exactly the TS-derived ids;</li>
     * <li>the define is non-empty but names only roots the run does not consume →
     * {@link Source#DEFINE}, unchanged from the two-argument form;</li>
     * <li>else → {@link Source#NONE}.</li>
     * </ol>
     * Define and TS ids are never mixed (CQ5 (a)), and the supplier is invoked <b>only</b> at step
     * 3 — with a user list or a consumed-root declaration in play the TS is not loaded, not parsed
     * and not validated, so a broken TS can never stop such a run.
     *
     * @param aUserIds
     *            the user's {@code CT Packages} field, possibly empty
     * @param aDeclared
     *            the Define-XML's declared CT packages, possibly empty
     * @param aConsumedRoots
     *            the CT roots the run consumes (e.g. {@code sdtmct}); an id belongs to a root when
     *            it starts with it, the same test the store provider names packages by
     * @param aTrialSummary
     *            the lazy Trial Summary read, or {@code null} when the run's root never reads TS
     *            (ADaM family, a run the store does not serve)
     * @return the resolved selection
     */
    public static CtSelection resolve(List<String> aUserIds, List<CtStandardRef> aDeclared,
            Collection<String> aConsumedRoots,
            @Nullable Supplier<TrialSummaryEvidence> aTrialSummary)
    {
        if (!aUserIds.isEmpty())
        {
            return new CtSelection(Source.USER, aUserIds);
        }
        List<String> declared = declaredIds(aDeclared);
        if (namesAConsumedRoot(declared, aConsumedRoots))
        {
            return new CtSelection(Source.DEFINE, declared);
        }
        TrialSummaryEvidence evidence = null;
        if (aTrialSummary != null)
        {
            evidence = aTrialSummary.get();
            if (!evidence.refs().isEmpty())
            {
                List<String> ids = new ArrayList<>(evidence.refs().size());
                for (TsRef ref : evidence.refs())
                {
                    ids.add(ref.packageId());
                }
                return new CtSelection(Source.TS, ids, evidence);
            }
        }
        if (!declared.isEmpty())
        {
            return new CtSelection(Source.DEFINE, declared, evidence);
        }
        return new CtSelection(Source.NONE, List.of(), evidence);
    }


    /** Whether any id belongs to one of the roots (the store provider's own prefix test). */
    static boolean namesAConsumedRoot(List<String> aIds, Collection<String> aConsumedRoots)
    {
        for (String id : aIds)
        {
            for (String root : aConsumedRoots)
            {
                if (id.startsWith(root))
                {
                    return true;
                }
            }
        }
        return false;
    }


    private static List<String> declaredIds(List<CtStandardRef> aDeclared)
    {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (CtStandardRef ref : aDeclared)
        {
            ids.add(ref.packageId());
        }
        return new ArrayList<>(ids);
    }
}
