package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.cumba.cdisc.core.report.LibraryValidator;
import net.cumba.cdisc.core.run.StudyValidationParams.RuleSelectionMode;
import net.cumba.datatable.manager.IDataTableManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StudyValidationParamsTest
{

    private static StudyValidationParams.Builder base()
    {
        return StudyValidationParams.builder().manager(Mockito.mock(IDataTableManager.class));
    }


    @Test
    void requiresManager()
    {
        assertThrows(NullPointerException.class, () -> StudyValidationParams.builder().build());
    }


    @Test
    void rejectsRuleThreadsBelowOne()
    {
        assertThrows(IllegalArgumentException.class, () -> base().ruleThreads(0).build());
    }


    @Test
    void taskDecoratorDefaultsToIdentity()
    {
        StudyValidationParams p = base().build();
        assertSame(UnaryOperator.identity(), p.taskDecorator());
    }


    @Test
    void taskDecoratorIsStoredAndRejectsNull()
    {
        UnaryOperator<Runnable> deco = task -> task;
        StudyValidationParams p = base().taskDecorator(deco).build();
        assertSame(deco, p.taskDecorator());
        assertThrows(NullPointerException.class, () -> base().taskDecorator(null));
    }


    @Test
    void defaultsAreSane()
    {
        StudyValidationParams p = base().build();
        assertEquals(RuleSelectionMode.ALL, p.ruleSelectionMode());
        assertEquals(1, p.ruleThreads());
        assertTrue(p.referenceData().isEmpty());
        assertTrue(p.controlledTerminologyPackages().isEmpty());
        assertTrue(p.includeRules().isEmpty());
        assertTrue(p.excludeRules().isEmpty());
        assertTrue(p.rulesFiles().isEmpty());
        assertTrue(p.datasetFilter().isEmpty());
    }


    @Test
    void explicitMetadataProductsAreKeptInOrder()
    {
        StudyValidationParams p = base()
                .metadataProducts(
                        List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"))
                .build();
        assertEquals(List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"),
                p.metadataProducts());
    }


    /**
     * A {@code null} or empty argument clears whatever was already staged. ⚑ Plan 2 (R5) removed
     * {@code -s}/{@code -v} and with them the implied default, so "cleared" now means empty; the
     * selected packages' declared standards are appended later by
     * {@code StudyValidationService.effectiveMetadataProducts}.
     */
    @Test
    void nullOrEmptyMetadataProductsClearTheList()
    {
        assertEquals(List.of(), base().metadataProducts(List.of("standards/sdtmig/3-4"))
                .metadataProducts(null).build().metadataProducts());
        assertEquals(List.of(), base().metadataProducts(List.of("standards/sdtmig/3-4"))
                .metadataProducts(List.of()).build().metadataProducts());
    }


    @Test
    void includeRulesSwitchModeToFiltered()
    {
        StudyValidationParams p = base().includeRules(List.of("CORE-1")).build();
        assertEquals(RuleSelectionMode.FILTERED, p.ruleSelectionMode());
    }


    @Test
    void excludeRulesSwitchModeToFiltered()
    {
        StudyValidationParams p = base().excludeRules(List.of("CORE-2")).build();
        assertEquals(RuleSelectionMode.FILTERED, p.ruleSelectionMode());
    }


    @Test
    void explicitModeIsNotOverriddenByFilters()
    {
        StudyValidationParams p = base().ruleSelectionMode(RuleSelectionMode.NONE)
                .includeRules(List.of("CORE-1")).build();
        assertEquals(RuleSelectionMode.NONE, p.ruleSelectionMode());
    }


    @Test
    void nullModeFallsBackToAll()
    {
        StudyValidationParams p = base().ruleSelectionMode(null).build();
        assertEquals(RuleSelectionMode.ALL, p.ruleSelectionMode());
    }


    @Test
    void nullCollectionArgsClearLists()
    {
        StudyValidationParams p = base().referenceData(null).controlledTerminologyPackages(null)
                .includeRules(null).excludeRules(null).rulesFiles(null).datasetFilter(null).build();
        assertTrue(p.referenceData().isEmpty());
        assertTrue(p.controlledTerminologyPackages().isEmpty());
        assertTrue(p.includeRules().isEmpty());
        assertTrue(p.excludeRules().isEmpty());
        assertTrue(p.rulesFiles().isEmpty());
        assertTrue(p.datasetFilter().isEmpty());
    }


    @Test
    void accessorsReturnConfiguredValues()
    {
        IDataTableManager mgr = Mockito.mock(IDataTableManager.class);
        BooleanSupplier cancel = () -> false;
        LibraryValidator.RuntimeListener rl = _ ->
        {
            // no-op
        };
        ProgressListener pl = new ProgressListener()
        {
            // defaults
        };
        StudyValidationParams p = StudyValidationParams.builder().manager(mgr).dataLibrary("/d")
                .defineXmlPath("/dx").referenceData(List.of("/r"))
                .rulesPackages(List.of("adamig-1-3"))
                .metadataProducts(List.of("standards/adam/adamig-1-3", "standards/tig/1-0/adam"))
                .useCase("uc").controlledTerminologyPackages(List.of("adamct")).defineVersion("2-1")
                .rulesDir("/rules").rulesFiles(List.of("/f.json")).datasetFilter(Set.of("DM"))
                .ruleThreads(2).cacheDir("/cache").runtimeListener(rl).progressListener(pl)
                .cancellation(cancel).build();

        assertSame(mgr, p.manager());
        assertEquals("/d", p.dataLibrary());
        assertEquals("/dx", p.defineXmlPath());
        assertEquals(List.of("/r"), p.referenceData());
        assertEquals(List.of("adamig-1-3"), p.rulesPackages());
        assertEquals(List.of("standards/adam/adamig-1-3", "standards/tig/1-0/adam"),
                p.metadataProducts());
        assertEquals("uc", p.useCase());
        assertEquals(List.of("adamct"), p.controlledTerminologyPackages());
        assertEquals("2-1", p.defineVersion());
        assertEquals("/rules", p.rulesDir());
        assertEquals(List.of("/f.json"), p.rulesFiles());
        assertEquals(Set.of("DM"), p.datasetFilter());
        assertEquals(2, p.ruleThreads());
        assertEquals("/cache", p.cacheDir());
        assertSame(rl, p.runtimeListener());
        assertSame(pl, p.progressListener());
        assertSame(cancel, p.cancellation());
        assertFalse(p.cancellation().getAsBoolean());
    }
}
