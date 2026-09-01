package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.CompanionDomainsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #218} — the <b>run-level</b> half of
 * {@code plans/PLAN-cross-standard-absence-skip.md}: {@link StudyValidationService} decides which
 * dataset names belong to a CDISC standard this run does not validate.
 *
 * <p>
 * ⚑ The decisive property is that the fact is derived from the {@link CompanionDomainsProvider}
 * <em>wrap</em>, not from the accessor alone. {@code maybeWrapCompanion} installs that decorator
 * <b>iff</b> the run is ADaM-family and a companion SDTM product resolved; on every other run the
 * same accessor answers with the run's <b>own</b> standard's datasets, which must never be treated
 * as foreign. Each test below pairs the ADaM case with exactly that control.
 * </p>
 */
class StudyValidationServiceCrossStandardTest
{

    /**
     * Minimal {@link MetadataProvider} double: only the accessor under test is meaningful.
     *
     * @param names
     *            what {@code getStandardDatasetNames()} answers, {@code null} for "not available"
     * @return the stub
     */
    private static MetadataProvider stub(@Nullable List<String> names)
    {
        MetadataProvider p = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito.when(p.getStandardDatasetNames()).thenReturn(names);
        return p;
    }


    @Test
    void anAdamRunSurfacesTheCompanionSdtmCatalogue()
    {
        MetadataProvider base = stub(null);
        MetadataProvider companion = stub(List.of("DM", "AE", "LB"));
        Set<String> cross = StudyValidationService
                .crossStandardDatasets(new CompanionDomainsProvider(base, companion));
        assertEquals(Set.of("DM", "AE", "LB"), cross);
    }


    @Test
    void aNonAdamRunHasNoCrossStandardDatasets()
    {
        // ⚠⚠ THE CONTROL THAT MATTERS. An SDTM run's provider is NOT wrapped, and its
        // getStandardDatasetNames() is its OWN domain list — treating that as cross-standard would
        // make every SDTM run SKIP its own optional domains. The instanceof test excludes it.
        MetadataProvider unwrapped = stub(List.of("DM", "AE", "LB", "SUPPDM"));
        assertTrue(StudyValidationService.crossStandardDatasets(unwrapped).isEmpty(),
                "an unwrapped provider's standard datasets are the run's OWN, never foreign");
    }


    @Test
    void anUnavailableCompanionDegradesToTheOldEngine()
    {
        // maybeWrapCompanion returns the base provider (with a WARNING) when no companion product
        // resolves, so the empty set here is the documented graceful degradation: identical to the
        // pre-Fix #218 engine rather than a wrong SKIP.
        assertTrue(StudyValidationService.crossStandardDatasets(null).isEmpty());
        assertTrue(StudyValidationService
                .crossStandardDatasets(new CompanionDomainsProvider(stub(null), stub(null)))
                .isEmpty());
        assertTrue(StudyValidationService
                .crossStandardDatasets(new CompanionDomainsProvider(stub(null), stub(List.of())))
                .isEmpty());
    }


    @Test
    void namesAreNormalisedAndBlanksDropped()
    {
        MetadataProvider companion = stub(
                java.util.Arrays.asList("dm", "  ae  ", "", "  ", null, "DM"));
        assertEquals(Set.of("DM", "AE"), StudyValidationService
                .crossStandardDatasets(new CompanionDomainsProvider(stub(null), companion)));
    }

}
