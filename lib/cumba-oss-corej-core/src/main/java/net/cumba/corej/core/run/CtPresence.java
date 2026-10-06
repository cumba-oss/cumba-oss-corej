package net.cumba.corej.core.run;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.cumba.corej.core.metadata.store.Presence;
import org.jspecify.annotations.Nullable;

/**
 * §4.4 (owner ruling D3, define-ct P5) — <b>abort when something named is missing or unusable; skip
 * when nothing is named at all.</b> The one loop and the two messages both runs use
 * (PLAN-define-ct-evaluation T1-2 a): the dataset run through
 * {@code StudyValidationService.requireNamedCtPackagesPresent}, the Define-XML conformance run
 * through {@code DefineStoreBinding}. Called only once the store is actually serving the run — a
 * store that is missing or does not open is the R2 degraded run, never a D3 abort.
 */
public final class CtPresence
{

    /**
     * The escape hatch the DEFINE- and TS-source aborts name: the phrase every surface can
     * recognise to append its own spelling of that field (the CLI's {@code -ct},
     * PLAN-define-ct-evaluation review round 1 M1).
     */
    public static final String CT_FIELD_HATCH = "fill the CT Packages field explicitly";

    /**
     * The phrase of an abort for a well-formed CT package id that no CDISC CT release carries (a
     * mistyped date, 5e review LOW-2): seeding cannot help, so a surface that appends a seeding
     * remedy to a {@link #CT_FIELD_HATCH} abort recognises this phrase and leaves it out (5e last
     * LOW).
     */
    public static final String UNKNOWN_RELEASE = "no CDISC CT release of that date is known to the store";

    private CtPresence()
    {
    }


    /**
     * Every CT package id the run named must be {@link Presence#PRESENT}.
     *
     * @param aPresence
     *            the store's presence answer
     * @param aPublished
     *            the store's published CT enumeration ({@code PUBLISHED_CT_PACKAGES}): an absent
     *            DEFINE- or TS-named id it lacks is no CDISC release at all (a typo), so the
     *            message does not advise seeding it (5e review LOW-2); empty means "unknown"
     * @param aStoreFile
     *            the store, for the message
     * @param aNamedCtIds
     *            the ids in play, after D1 resolution
     * @param aSource
     *            where they came from — the DEFINE message names the escape hatch
     * @throws StudyValidationException
     *             on the first id that is not present, naming it
     */
    static void requireNamedPresent(Function<String, Presence> aPresence,
            Collection<String> aPublished, Path aStoreFile, List<String> aNamedCtIds,
            CtSelection.Source aSource)
    {
        requireNamedPresent(aPresence, aPublished, aStoreFile, aNamedCtIds, aSource, null);
    }


    /**
     * As {@link #requireNamedPresent(Function, Collection, Path, List, CtSelection.Source)}, with
     * the Trial Summary evidence a {@link CtSelection.Source#TS} message cites per package
     * (PLAN-ct-packages-default-and-ts-source CTD-2: a TS-derived package the store does not hold
     * is treated like a define-declared one — abort, naming it and its TS row, with the same escape
     * hatch).
     *
     * @param aPresence
     *            the store's presence answer
     * @param aPublished
     *            the store's published CT enumeration ({@code PUBLISHED_CT_PACKAGES}): an absent
     *            DEFINE- or TS-named id it lacks is no CDISC release at all (a typo), so the
     *            message does not advise seeding it (5e review LOW-2); empty means "unknown"
     * @param aStoreFile
     *            the store, for the message
     * @param aNamedCtIds
     *            the ids in play, after resolution
     * @param aSource
     *            where they came from — the DEFINE and TS messages name the escape hatch
     * @param aTrialSummary
     *            the Trial Summary read behind a TS selection, or {@code null}
     * @throws StudyValidationException
     *             on the first id that is not present, naming it
     */
    static void requireNamedPresent(Function<String, Presence> aPresence,
            Collection<String> aPublished, Path aStoreFile, List<String> aNamedCtIds,
            CtSelection.Source aSource, CtSelection.@Nullable TrialSummaryEvidence aTrialSummary)
    {
        for (String id : aNamedCtIds)
        {
            Presence presence = aPresence.apply(id);
            if (presence == Presence.PRESENT)
            {
                continue;
            }
            String problem = presence == Presence.MALFORMED
                    ? "is not a valid CT package id (expected <family>ct-<yyyy-mm-dd>)"
                    : "is not held by the metadata store at " + aStoreFile;
            // LOW-2: a well-formed id the published enumeration lacks is no CDISC CT release at
            // all (a mistyped date) — seeding cannot help. An empty enumeration says nothing.
            String remedy = presence == Presence.ABSENT && !aPublished.isEmpty()
                    && !aPublished.contains(id) ? "; " + UNKNOWN_RELEASE + ". Correct the date, or "
                            : ". Seed the store with it, or ";
            if (aSource == CtSelection.Source.DEFINE)
            {
                throw new StudyValidationException("The Define-XML declares "
                        + "controlled-terminology package '" + id + "' (def:Standards), which "
                        + problem + remedy + CT_FIELD_HATCH
                        + " - an explicit selection takes the define's declaration out of play.");
            }
            if (aSource == CtSelection.Source.TS)
            {
                String evidence = aTrialSummary != null ? aTrialSummary.evidenceFor(id) : null;
                throw new StudyValidationException(
                        "The Trial Summary (TS) states " + "controlled-terminology package '" + id
                                + "'" + (evidence != null ? " (" + evidence + ")" : "") + ", which "
                                + problem + remedy + CT_FIELD_HATCH
                                + " - an explicit selection takes the Trial Summary out of play.");
            }
            throw new StudyValidationException("Controlled-terminology package '" + id
                    + "' was requested, but it " + problem + ".");
        }
    }
}
