package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider.PublishedVariable;
import org.junit.jupiter.api.Test;

/**
 * <b>Plan 2, R11 / Phase 5</b> — the SDTM carry-over candidate operands
 * ({@code library_variable_label_values}, {@code library_variable_data_type_values}).
 *
 * <p>
 * These pin the two decisions Phase 0 measured and the plan then depended on: a <b>candidate is a
 * distinct {@code (label, simpleDatatype)} pair</b>, and <b>whitespace is normalised</b> before
 * anything is compared.
 * </p>
 */
class CarryOverCandidatesTest
{

    /** A provider that publishes exactly the occurrences a test names. */
    private static MetadataProvider publishing(PublishedVariable... occurrences)
    {
        MetadataProvider provider = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito
                .when(provider
                        .getPublishedVariablesByName(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(occurrences));
        return provider;
    }


    private static Map<String, Object> candidatesFor(PublishedVariable... occurrences)
    {
        Map<String, Object> varMeta = new LinkedHashMap<>();
        RuleRunner.putCarryOverCandidates(varMeta, publishing(occurrences), "AETERM");
        return varMeta;
    }


    /**
     * ⛔⛔ <b>The decision the whole lane rests on.</b> {@code USUBJID} is published in dozens of
     * SDTMIG 3.4 domains with ONE label. Counted per occurrence that is dozens of candidates and
     * the rule's INFO lane fires on every ADaM dataset; deduped on the {@code (label, type)} pair
     * it is ONE, and the rule is silent.
     *
     * <p>
     * ⚠ Review finding R-20: this javadoc used to quote exact figures (55 domains, 24 multi-name
     * counts, 2 per-pair). They were measured against {@code variables_metadata.pkl} while the
     * shipped path goes through {@code getVariableMetadata}'s Algorithm-B fallback — a DIFFERENT
     * population — and were wrong (72 / 56 / 4). The decision is unaffected; the numbers are gone
     * rather than patched, because a figure is only worth quoting if it was measured through the
     * accessor the code actually calls.
     * </p>
     */
    @Test
    void manyDomainsOneLabelIsOneCandidate()
    {
        Map<String, Object> meta = candidatesFor(
                new PublishedVariable("DM", "Unique Subject Identifier", "Char"),
                new PublishedVariable("AE", "Unique Subject Identifier", "Char"),
                new PublishedVariable("LB", "Unique Subject Identifier", "Char"));

        // ⚑ Labels are published CASE-FOLDED (review finding R-4): CDISC's own SDTMIG 3.4 has
        // case-only label variants, so folding is what makes candidate identity correct. Types are
        // NOT folded — `Char`/`Num` are canonical tokens with no variants.
        assertEquals(List.of("UNIQUE SUBJECT IDENTIFIER"),
                meta.get("library_variable_label_values"));
        assertEquals(List.of("Char"), meta.get("library_variable_data_type_values"));
    }


    /** Genuinely different labels DO produce several candidates — the INFO lane's real subject. */
    @Test
    void genuinelyDifferentLabelsAreSeveralCandidates()
    {
        Map<String, Object> meta = candidatesFor(
                new PublishedVariable("IS", "Non-host Organism ID", "Char"),
                new PublishedVariable("GF", "Non-Host Organism Identifier", "Char"),
                new PublishedVariable("OI", "Non-host Organism Identifier", "Char"));

        // ⭐ TWO, not three — and that IS the R-4 fix. NHOID publishes "Non-Host Organism
        // Identifier" and "Non-host Organism Identifier", which differ ONLY in case; before case
        // folding they counted as two distinct candidates from one logical label, and an ADaM copy
        // spelled either way took the ERROR lane against CDISC's own metadata.
        assertEquals(2, ((List<?>) meta.get("library_variable_label_values")).size(),
                "the two case-only variants collapse; the genuinely different label remains");
    }


    /**
     * ⛔ <b>G6-b — the whitespace case.</b> {@code CMTRT} is the ONE real mismatch in the entire
     * ADaM↔SDTM collision set (55 of 56 agree exactly), and it differs only by a trailing space.
     * Without normalisation CDISC's own published metadata raises a false ERROR.
     */
    @Test
    void trailingWhitespaceIsNormalisedSoCmtrtIsOneCandidate()
    {
        Map<String, Object> meta = candidatesFor(
                new PublishedVariable("CM", "Reported Name of Drug, Med, or Therapy ", "Char"),
                new PublishedVariable("AE", "Reported Name of Drug, Med, or Therapy", "Char"));

        assertEquals(List.of("REPORTED NAME OF DRUG, MED, OR THERAPY"),
                meta.get("library_variable_label_values"),
                "a trailing space must not make CDISC's own metadata disagree with itself");
    }


    /** Internal whitespace runs collapse too, so a double space is not a second candidate. */
    @Test
    void internalWhitespaceRunsCollapse()
    {
        Map<String, Object> meta = candidatesFor(
                new PublishedVariable("AE", "Reported  Term", "Char"),
                new PublishedVariable("CM", "Reported Term", "Char"));

        assertEquals(1, ((List<?>) meta.get("library_variable_label_values")).size());
    }


    /** Same label, different type ⇒ two candidates: the pair is the key, not the label alone. */
    @Test
    void thePairIsTheKeyNotTheLabelAlone()
    {
        Map<String, Object> meta = candidatesFor(new PublishedVariable("AE", "Sequence", "Num"),
                new PublishedVariable("CM", "Sequence", "Char"));

        assertEquals(List.of("SEQUENCE"), meta.get("library_variable_label_values"),
                "one distinct label...");
        assertEquals(List.of("Num", "Char"), meta.get("library_variable_data_type_values"),
                "...but two distinct types, because the candidates differ as PAIRS");
    }


    /**
     * ⛔⛔ <b>The not-applicable row.</b> An ADaM-native name is published nowhere in SDTM, so NO
     * operand is emitted at all and the rule cannot fire. Measured: {@code PARAMCD}, {@code AVAL},
     * {@code TRTP}, {@code AVISIT} &amp;c all have zero candidates, and only 10 of 332
     * {@code adamig-1-3} variables have any. Emitting an empty list instead would let a rule
     * compare against "no candidates" and report a violation.
     */
    @Test
    void aNameThatIsPublishedNowhereEmitsNoOperandAtAll()
    {
        Map<String, Object> meta = candidatesFor();

        assertFalse(meta.containsKey("library_variable_label_values"),
                "zero candidates must publish NO operand, not an empty one");
        assertFalse(meta.containsKey("library_variable_data_type_values"));
        assertTrue(meta.isEmpty());
    }


    /** A null label or type is skipped rather than becoming an empty-string candidate. */
    @Test
    void nullAttributesDoNotBecomeEmptyCandidates()
    {
        Map<String, Object> meta = candidatesFor(new PublishedVariable("AE", null, "Char"));

        assertFalse(meta.containsKey("library_variable_label_values"),
                "a null label is an ABSENCE, not a candidate whose value is empty");
        assertEquals(List.of("Char"), meta.get("library_variable_data_type_values"));
    }


    /**
     * ⛔ The wrapper must answer from the <b>companion</b>, not the base. The base is the ADaM
     * library, which publishes nothing under an SDTM domain — forwarding to it would return empty
     * for every name and silently turn the whole lane into the not-applicable row. The delegation
     * guard proves the method is declared and forwards its argument; only this proves it forwards
     * to the RIGHT provider.
     */
    @Test
    void theCompanionWrapperAnswersFromTheCompanionNotTheBase()
    {
        MetadataProvider base = publishing(new PublishedVariable("ADAE", "wrong-source", "Char"));
        MetadataProvider companion = publishing(
                new PublishedVariable("AE", "Reported Term of the Adverse Event", "Char"));

        MetadataProvider wrapped = new net.cumba.cdisc.core.metadata.CompanionDomainsProvider(base,
                companion);

        assertEquals(
                List.of(new PublishedVariable("AE", "Reported Term of the Adverse Event", "Char")),
                wrapped.getPublishedVariablesByName("AETERM"),
                "the carry-over lookup must come from the companion SDTM product");
    }
}
