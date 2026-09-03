package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * §3 non-goal 1 of {@code plans/PLAN-metadata-product-selection.md} (ruling 7) — <b>Phase 3 must
 * not narrow scope.</b>
 *
 * <h2>The hazard being fenced off</h2>
 *
 * <p>
 * Phase 3 makes the published {@code subClass} decide which data structure's variables answer for a
 * dataset. The obvious-looking next step — letting the subclass decide what the dataset <em>is</em>
 * — is forbidden: were {@code ADAE} to detect as {@code [AE]} instead of
 * {@code [OCCURRENCE DATA STRUCTURE]}, every rule scoped
 * {@code Data_Structures.Include:[OCCURRENCE DATA STRUCTURE]} would silently stop reaching it, and
 * 56 {@code ADVERSE EVENT}-shaped datasets' worth of coverage would evaporate with no failure
 * anywhere. An AE dataset <b>is</b> an OCCDS dataset.
 * </p>
 *
 * <p>
 * ⛔ {@code AdamDataStructureDetector}, {@code AdamSubclassDetector} and {@code ScopeMatcher} are
 * untouched by Phase 3. The structure set and the subclass set are computed independently of any
 * variable-resolution concern — this test pins that they still are.
 * </p>
 */
class AdamSubclassScopeInvarianceTest
{

    private static IDataTable adae()
    {
        return MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1").col("AESEQ", "1")
                .col("AEDECOD", "HEADACHE").name("ADAE").build();
    }


    private static Rule scopedTo(String... structures)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DataStructureScope ds = new DataStructureScope();
        ds.setInclude(List.of(structures));
        scope.setDataStructures(ds);
        rule.setScope(scope);
        return rule;
    }


    @Test
    void anAdaeDatasetStillDetectsTheOccurrenceStructure_notItsSubclass()
    {
        List<String> structures = AdamStructureContext.detectAll(adae().getMetaData(), null, null);

        assertEquals(List.of(AdamDataStructureDetector.OCCDS), structures,
                "the structure set is a STRUCTURE set; a subclass may never appear in it");
        assertTrue(AdamDataStructureDetector.STRUCTURE_TOKENS.containsAll(structures));
    }


    @Test
    void aRuleScopedToTheOccurrenceStructureStillReachesAdae()
    {
        List<String> structures = AdamStructureContext.detectAll(adae().getMetaData(), null, null);

        assertNull(
                ScopeMatcher.describeDataStructureMismatch(
                        scopedTo(AdamDataStructureDetector.OCCDS), structures),
                "an AE dataset is an OCCDS dataset — the gate must still admit it");
    }


    @Test
    void theSubclassIsDetectedAlongsideTheStructure_neitherReplacesTheOther()
    {
        var meta = adae().getMetaData();
        List<String> structures = AdamStructureContext.detectAll(meta, null, null);
        List<String> subclasses = AdamStructureContext.detectSubclasses(meta, null, null,
                structures);

        assertEquals(List.of(AdamSubclassDetector.ADVERSE_EVENT), subclasses);
        // ⚠ And asking for the subclasses must not have perturbed the structure set: the two
        // derivations are independent, which is what makes "the subclass governs the variable
        // list" safe to add at all.
        assertEquals(List.of(AdamDataStructureDetector.OCCDS),
                AdamStructureContext.detectAll(meta, null, null));
    }


    @Test
    void detectSubclassesAgreesWithTheDetectorTheScopeGateUses()
    {
        // The gate (RuleRunner) and the operation (OperationExecutor) now share ONE derivation.
        // If this ever diverges, a rule admitted as ADVERSE EVENT would resolve its variable list
        // from some other structure and nothing would say so.
        var meta = adae().getMetaData();
        List<String> structures = AdamStructureContext.detectAll(meta, null, null);

        assertEquals(
                AdamSubclassDetector.resolve(meta.getName(), structures,
                        AdamStructureContext.columnNamesOf(meta), List.of(),
                        AdamDataStructureDetector.defineFirstPreference()),
                AdamStructureContext.detectSubclasses(meta, null, null, structures));
    }


    @Test
    void anOccurrenceDatasetWithNoAeSignalHasNoSubclassButKeepsItsStructure()
    {
        IDataTable adcm = MockTable.of().col("STUDYID", "P1").col("CMTRT", "ASPIRIN").name("ADCM")
                .build();
        List<String> structures = AdamStructureContext.detectAll(adcm.getMetaData(), null, null);

        assertEquals(List.of(AdamDataStructureDetector.OCCDS), structures);
        assertEquals(List.of(),
                AdamStructureContext.detectSubclasses(adcm.getMetaData(), null, null, structures));
        assertNull(ScopeMatcher.describeDataStructureMismatch(
                scopedTo(AdamDataStructureDetector.OCCDS), structures));
    }
}
