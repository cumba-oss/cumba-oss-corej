package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import net.cumba.corej.core.model.Sensitivity;
import org.junit.jupiter.api.Test;

/**
 * SPEC §8 / D39b-REV — the effective granularity of one (rule, dataset): {@code Sensitivity} is the
 * declared <b>maximum</b>, the derived level may coarsen it to Dataset, Study or Group (D39, D39d),
 * and a derived level <em>finer</em> than the declaration keeps the declaration (D28b's emission
 * collapse).
 */
class EffectiveGranularityTest
{

    private static Level group(String... keys)
    {
        return new Level(new Granularity.Group(Set.of(keys)), Cursor.ABSENT);
    }


    private static EffectiveGranularity of(Sensitivity declared, Level derived)
    {
        return EffectiveGranularity.of(declared, Set.of(), derived);
    }


    @Test
    void equalDeclaredAndDerivedIsNotCoarsened()
    {
        EffectiveGranularity eff = of(Sensitivity.RECORD, Level.RECORD);
        assertEquals(Granularity.Simple.RECORD, eff.granularity());
        assertFalse(eff.coarsened());
    }


    @Test
    void anAllDatasetDerivationCoarsensARecordRule()
    {
        // D39/D39a: every operand dataset-level (e.g. every read column absent) folds a Record
        // rule to one Dataset finding.
        EffectiveGranularity eff = of(Sensitivity.RECORD, Level.DATASET);
        assertEquals(Granularity.Simple.DATASET, eff.granularity());
        assertTrue(eff.coarsened());
    }


    @Test
    void aStudyDerivationCoarsensARecordRule()
    {
        EffectiveGranularity eff = of(Sensitivity.RECORD, Level.STUDY);
        assertEquals(Granularity.Simple.STUDY, eff.granularity());
        assertTrue(eff.coarsened());
    }


    @Test
    void aGroupDerivationCoarsensARecordRuleToTheDerivedKey()
    {
        // D39d: the fold can land on group even when the rule was not authored with Grouping —
        // the key is derived, never invented (D39e).
        EffectiveGranularity eff = of(Sensitivity.RECORD, group("USUBJID", "PARAMCD"));
        assertEquals(new Granularity.Group(Set.of("USUBJID", "PARAMCD")), eff.granularity());
        assertTrue(eff.coarsened());
    }


    @Test
    void aRecordDerivationUnderADatasetRuleKeepsTheDeclaredMaximum()
    {
        // D28b: the rule evaluates per row; the declaration caps EMISSION at dataset.
        EffectiveGranularity eff = of(Sensitivity.DATASET, Level.RECORD);
        assertEquals(Granularity.Simple.DATASET, eff.granularity());
        assertFalse(eff.coarsened());
    }


    @Test
    void aGroupDerivationUnderADatasetRuleKeepsTheDeclaredMaximum()
    {
        // group is FINER than dataset on the granularity chain; the declared maximum wins.
        EffectiveGranularity eff = of(Sensitivity.DATASET, group("USUBJID"));
        assertEquals(Granularity.Simple.DATASET, eff.granularity());
        assertFalse(eff.coarsened());
    }


    @Test
    void aRecordDerivationUnderAGroupRuleKeepsTheDeclaredKeys()
    {
        EffectiveGranularity eff = EffectiveGranularity.of(Sensitivity.GROUP,
                new LinkedHashSet<>(Set.of("USUBJID")), Level.RECORD);
        assertEquals(new Granularity.Group(Set.of("USUBJID")), eff.granularity());
        assertFalse(eff.coarsened());
    }


    @Test
    void aDatasetDerivationCoarsensAGroupRule()
    {
        EffectiveGranularity eff = EffectiveGranularity.of(Sensitivity.GROUP, Set.of("USUBJID"),
                Level.DATASET);
        assertEquals(Granularity.Simple.DATASET, eff.granularity());
        assertTrue(eff.coarsened());
    }


    @Test
    void aGroupDerivationUnderAGroupRuleIsTheDerivedGroup()
    {
        EffectiveGranularity eff = EffectiveGranularity.of(Sensitivity.GROUP, Set.of("USUBJID"),
                group("USUBJID"));
        assertEquals(new Granularity.Group(Set.of("USUBJID")), eff.granularity());
        assertFalse(eff.coarsened());
    }


    @Test
    void aKeylessGroupDeclarationDegradesToDatasetDefensively()
    {
        // D28a makes this unreachable on a loaded rule; the derivation must not throw from an
        // observation path regardless.
        EffectiveGranularity eff = EffectiveGranularity.of(Sensitivity.GROUP, Set.of(),
                Level.RECORD);
        assertEquals(Granularity.Simple.DATASET, eff.granularity());
        assertFalse(eff.coarsened());
    }


    @Test
    void theEffectiveValueCarriesTheDerivedCursor()
    {
        // D106g (phase 5b): the per-variable rules (CG0310/0311/0359 shape) derive dataset ×
        // cursor — the granularity half coarsens while the multiplicity rides the cursor, so the
        // effective value must carry the FULL level or it under-describes effective emission.
        EffectiveGranularity eff = of(Sensitivity.RECORD,
                new Level(Granularity.Simple.DATASET, Cursor.PRESENT));
        assertEquals(Granularity.Simple.DATASET, eff.granularity());
        assertEquals(Cursor.PRESENT, eff.cursor());
        assertTrue(eff.coarsened());

        // ...and the routine collapse case keeps the cursor it derived (ABSENT).
        assertEquals(Cursor.ABSENT, of(Sensitivity.DATASET, Level.RECORD).cursor());
    }


    @Test
    void aStudyRuleIsAlwaysStudy()
    {
        EffectiveGranularity eff = of(Sensitivity.STUDY, Level.RECORD);
        assertEquals(Granularity.Simple.STUDY, eff.granularity());
        assertFalse(eff.coarsened());
    }

}
