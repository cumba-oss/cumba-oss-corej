package net.cumba.corej.define.conformance.ct;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The CDISC QRS class of a CT codelist and its role inside the instrument, both read from the NCI
 * preferred term — {@code "CDISC Clinical Classification HAMD 17 Test Code Terminology"} → class
 * {@code Clinical Classification}, role {@code Test Code}. The class is a published-name
 * convention, not a structured field of CT: a term outside the three {@code "CDISC <class> …"}
 * prefixes (e.g. {@code "CDISC SDTM Sex Terminology"}, or {@code "CDISC Brief Questionnaire of
 * Smoking Urges …"}) is in no QRS class; {@code role} is empty when the term ends in none of the
 * five role suffixes (measured 2026-10-12: every Clinical Classification term in all 43
 * {@code sdtmct} packages of the store ends in one of them).
 *
 * <p>
 * Why it exists ({@code PLAN-stopped-rule-engine-fixes} SR2 / SR3 / T8): since SDTMIG 3.3 the RS
 * dataset also holds clinical classifications, each with its own instrument codelist, while the
 * Library assigns {@code RS.RSTESTCD} / {@code RSTEST} the oncology lists only. A define rule can
 * exempt that pair through an {@code exempt:} block naming class and role — the role because the
 * class alone would admit a Test Code variable bound to the instrument's Test Name list.
 * </p>
 */
public record CodelistClass(String qrsClass, Optional<String> role)
{

    /** The QRS classes, read from the preferred term's {@code "CDISC <class> "} prefix. */
    private static final List<String> QRS_CLASSES = List.of("Clinical Classification",
            "Questionnaire", "Functional Test");

    /**
     * The role suffixes, each followed by {@code " Terminology"}; matched in any case (published
     * Questionnaire terms spell some in lower case, {@code "… Test code Terminology"}).
     */
    private static final List<String> ROLES = List.of("Test Code", "Test Name", "Original Response",
            "Character Response", "Category");

    private static final String TERMINOLOGY = " Terminology";

    /** An absent role is {@link Optional#empty()}, never {@code null}. */
    public CodelistClass
    {
        Objects.requireNonNull(role, "role");
    }


    /**
     * The class and role of a codelist with this NCI preferred term, or empty when the term is
     * absent or starts with none of the QRS class prefixes.
     */
    public static Optional<CodelistClass> of(@Nullable String aPreferredTerm)
    {
        if (aPreferredTerm == null)
        {
            return Optional.empty();
        }
        for (String qrsClass : QRS_CLASSES)
        {
            if (aPreferredTerm.startsWith("CDISC " + qrsClass + " "))
            {
                return Optional.of(new CodelistClass(qrsClass, roleOf(aPreferredTerm)));
            }
        }
        return Optional.empty();
    }


    private static Optional<String> roleOf(String aPreferredTerm)
    {
        for (String role : ROLES)
        {
            String suffix = " " + role + TERMINOLOGY;
            int from = aPreferredTerm.length() - suffix.length();
            if (from >= 0 && aPreferredTerm.regionMatches(true, from, suffix, 0, suffix.length()))
            {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
