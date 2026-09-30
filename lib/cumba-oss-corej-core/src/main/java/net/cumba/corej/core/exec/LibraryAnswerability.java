package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * Whether a run's CDISC Library may be asked a library-dependent question at all (Fix #369): the
 * process-wide {@code corej.degradedDefineFallback} preference and the answerability test the
 * registry functions, the runner's SKIP arms, the {@code library_available()} gate and the study
 * validation service share.
 *
 * <p>
 * Runbook W8 ({@code PLAN-retire-operation-surface}): moved verbatim from the retired operation
 * executor, where the library-dependent operations were the first callers; no operation has read a
 * provider since wave 4b, and every caller left is a registry function or the runner.
 * </p>
 */
public final class LibraryAnswerability
{

    private LibraryAnswerability()
    {
    }

    /**
     * Fix #369 — system property enabling the Define-XML substitution on a degraded run. Opt-in,
     * <b>default off</b>; see {@link #defineFallbackPreference()}.
     */
    public static final String DEGRADED_DEFINE_FALLBACK_PROPERTY = "corej.degradedDefineFallback";

    /**
     * Fix #369 — the process-wide {@code corej.degradedDefineFallback} preference: whether a run
     * whose CDISC Library could not be consulted may answer library-citing rules from the study's
     * Define-XML instead.
     *
     * <p>
     * <b>Opt-in, default {@code false}</b>, and deliberately so. Owner ruling, 2026-08-27:
     * <em>"these rules state they check against the library, which they do not do if the source is
     * not the library. Therefore the correct result is always a SKIPPED (library not
     * available)."</em> A Define-XML {@code ItemRef/@Mandatory} is the <em>sponsor's</em>
     * declaration, not the <em>standard's</em> Required list; answering from it is a different
     * question wearing the rule's message. A sponsor who knowingly wants their own declarations
     * used as the basis may ask for it — but it must be their decision, not an accident of an
     * expired subscription key.
     * </p>
     *
     * <p>
     * ⚑ Note the asymmetry with {@code corej.defineFirst}, which is opt-<em>out</em>: this one is
     * plain {@link Boolean#getBoolean}, so anything but an explicit {@code true} leaves the
     * substitution off.
     * </p>
     *
     * @return {@code true} only when {@code -Dcorej.degradedDefineFallback=true} was given
     */
    public static boolean defineFallbackPreference()
    {
        return Boolean.getBoolean(DEGRADED_DEFINE_FALLBACK_PROPERTY);
    }


    /**
     * Fix #369 — whether {@code provider} may be asked a library-dependent question at all.
     *
     * <p>
     * {@code false} for an absent provider, and for a <b>degraded</b> one
     * ({@link MetadataProvider#isLibraryUnavailable()}) unless the caller opted into the Define-XML
     * substitution <em>and</em> the fallback really is a Define-XML library. That last test is
     * exact rather than heuristic: {@link MetadataProvider#getDefineVersion()} reads
     * {@code IMetadataLibrary.META_KEY_DEFINE_VERSION}, and {@code DefineMetadataLibrary} is the
     * only implementation in the repository that publishes it — so no per-answer provenance
     * plumbing is needed to know that a non-{@code null} version means "the fallback is a define".
     * </p>
     *
     * <p>
     * ⚠ Whether a fallback tier <em>happens</em> to hold something is deliberately <b>not</b> part
     * of this test on the default path. The question is not "could anything answer?" but "was the
     * rule's own claim met?", and it was not.
     * </p>
     */
    public static boolean libraryAnswerable(@Nullable MetadataProvider provider)
    {
        if (provider == null)
        {
            return false;
        }
        return !provider.isLibraryUnavailable()
                || (defineFallbackPreference() && provider.getDefineVersion() != null);
    }

}
