package net.cumba.corej.core.run;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import net.cumba.corej.core.metadata.store.Presence;

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
     * The escape hatch the DEFINE-source abort names: the phrase every surface can recognise to
     * append its own spelling of that field (the CLI's {@code -ct}, PLAN-define-ct-evaluation
     * review round 1 M1).
     */
    public static final String CT_FIELD_HATCH = "fill the CT Packages field explicitly";

    private CtPresence()
    {
    }


    /**
     * Every CT package id the run named must be {@link Presence#PRESENT}.
     *
     * @param aPresence
     *            the store's presence answer
     * @param aStoreFile
     *            the store, for the message
     * @param aNamedCtIds
     *            the ids in play, after D1 resolution
     * @param aSource
     *            where they came from — the DEFINE message names the escape hatch
     * @throws StudyValidationException
     *             on the first id that is not present, naming it
     */
    static void requireNamedPresent(Function<String, Presence> aPresence, Path aStoreFile,
            List<String> aNamedCtIds, CtSelection.Source aSource)
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
            if (aSource == CtSelection.Source.DEFINE)
            {
                throw new StudyValidationException("The Define-XML declares "
                        + "controlled-terminology package '" + id + "' (def:Standards), which "
                        + problem + ". Seed the store with it, or " + CT_FIELD_HATCH
                        + " - an explicit selection takes the define's declaration out of play.");
            }
            throw new StudyValidationException("Controlled-terminology package '" + id
                    + "' was requested, but it " + problem + ".");
        }
    }
}
