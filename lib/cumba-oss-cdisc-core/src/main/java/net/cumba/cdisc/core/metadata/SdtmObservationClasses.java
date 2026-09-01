package net.cumba.cdisc.core.metadata;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * EC-85 — the one vocabulary of SDTM model class names shared by the rule loader and the model
 * resolver, so that a {@code model_class} value an author may write and a class name
 * {@link MetadataLibraryProvider} can walk cannot drift apart.
 *
 * <p>
 * {@link #normalise} mirrors the Python reference engine's
 * {@code convert_library_class_name_to_ct_class}: the two {@code special-purpose} spellings map to
 * their CT aliases, everything else is upper-cased. {@link #isDetectable} names the four general
 * observation classes whose model walk merges the GENERAL OBSERVATIONS identifiers and timing
 * variables (FINDINGS ABOUT additionally splices FINDINGS around {@code --TEST}).
 * </p>
 */
public final class SdtmObservationClasses
{

    public static final String FINDINGS = "FINDINGS";

    public static final String FINDINGS_ABOUT = "FINDINGS ABOUT";

    public static final String EVENTS = "EVENTS";

    public static final String INTERVENTIONS = "INTERVENTIONS";

    public static final String GENERAL_OBSERVATIONS = "GENERAL OBSERVATIONS";

    public static final String ASSOCIATED_PERSONS = "ASSOCIATED PERSONS";

    /** The four detectable general-observation classes, in the order the model lists them. */
    public static final List<String> DETECTABLE = List.of(INTERVENTIONS, EVENTS, FINDINGS,
            FINDINGS_ABOUT);

    /**
     * The normalised class names a {@code model_class} declaration may carry: the four detectable
     * classes plus the non-detectable classes of an SDTM model product that are stable under
     * {@link #normalise} (every member is its own normalisation, so load-time validation and the
     * resolver's in-walk normalisation agree). Validated at rule load against the <em>spelling</em>
     * only — the loader has no library, so whether the loaded product actually carries the class is
     * a runtime question answered by the resolver (an unserved class SKIPs). Deliberately NOT a
     * complete list of the names a product can list ({@code Identifiers}, {@code Timing} and
     * {@code Associated Persons - Identifiers} are role buckets, not class tables, and are rejected
     * loudly).
     */
    public static final Set<String> MODEL_CLASS_NAMES = Set.of(INTERVENTIONS, EVENTS, FINDINGS,
            FINDINGS_ABOUT, GENERAL_OBSERVATIONS, ASSOCIATED_PERSONS, "SPECIAL PURPOSE",
            "TRIAL DESIGN", "RELATIONSHIP", "STUDY REFERENCE");

    private SdtmObservationClasses()
    {
    }


    /**
     * Normalises a class name the way the resolver compares them: {@code special-purpose} →
     * {@code SPECIAL PURPOSE}, {@code special-purpose datasets} → {@code SPECIAL-PURPOSE},
     * otherwise upper-case. {@code null} stays {@code null}.
     */
    public static @Nullable String normalise(@Nullable String aClassName)
    {
        if (aClassName == null)
        {
            return null;
        }
        String lower = aClassName.toLowerCase(Locale.ROOT);
        if ("special-purpose".equals(lower))
        {
            return "SPECIAL PURPOSE";
        }
        if ("special-purpose datasets".equals(lower))
        {
            return "SPECIAL-PURPOSE";
        }
        return aClassName.toUpperCase(Locale.ROOT);
    }


    /** True when the given <em>normalised</em> name is one of the four detectable classes. */
    public static boolean isDetectable(@Nullable String aNormalisedClassName)
    {
        return aNormalisedClassName != null && DETECTABLE.contains(aNormalisedClassName);
    }
}
