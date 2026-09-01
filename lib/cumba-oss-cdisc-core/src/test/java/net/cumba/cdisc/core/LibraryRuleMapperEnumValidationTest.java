package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.VariableUniverse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 2 of {@code plans/PLAN-extend-expression-engine.md} — {@link LibraryRuleMapper} applies the
 * same present-but-unrecognized enum validation as the loader path, so CDISC-Library-sourced rules
 * carrying an invalid {@code Rule_Type} / {@code Sensitivity} / {@code Executability} fail
 * identically ({@code Rule.loadError} → ERROR sentinel).
 */
@ExtendWith(MockitoExtension.class)
class LibraryRuleMapperEnumValidationTest
{

    private static net.cumba.cdisc.library.api.model.rules.Rule mockMinimalRule(String id)
    {
        var src = mock(net.cumba.cdisc.library.api.model.rules.Rule.class);
        lenient().when(src.id()).thenReturn(Optional.of(id));
        lenient().when(src.description()).thenReturn(Optional.empty());
        lenient().when(src.core()).thenReturn(Optional.empty());
        lenient().when(src.ruleType()).thenReturn(Optional.empty());
        lenient().when(src.sensitivity()).thenReturn(Optional.empty());
        lenient().when(src.executability()).thenReturn(Optional.empty());
        lenient().when(src.authorities()).thenReturn(List.of());
        lenient().when(src.scope()).thenReturn(Optional.empty());
        lenient().when(src.check()).thenReturn(Optional.empty());
        lenient().when(src.outcome()).thenReturn(Optional.empty());
        lenient().when(src.operations()).thenReturn(List.of());
        lenient().when(src.matchDatasets()).thenReturn(List.of());
        lenient().when(src.groupingVariables()).thenReturn(List.of());
        return src;
    }


    @Test
    void ruleType_isTranslatedNotRejected_theDefineItemTypeBecomesTheDefineUniverse()
    {
        // The Library payload carries Rule_Type on every rule, so the mapper translates the one
        // non-inferable bit and drops the rest instead of rejecting (see LibraryRuleMapper).
        var src = mockMinimalRule("LIB-001");
        when(src.ruleType()).thenReturn(Optional.of(LibraryRuleMapper.DEFINE_ITEM_RULE_TYPE));
        Rule mapped = LibraryRuleMapper.mapRule(src);
        assertNull(mapped.getLoadError());
        assertNull(mapped.getRejectedRuleType(), "the retired field is never recorded");
        assertEquals(VariableUniverse.DEFINE, mapped.getVariableUniverse());

        var other = mockMinimalRule("LIB-002");
        when(other.ruleType()).thenReturn(Optional.of("Record Data"));
        Rule plain = LibraryRuleMapper.mapRule(other);
        assertNull(plain.getLoadError());
        assertNull(plain.getRejectedRuleType());
        assertNull(plain.getVariableUniverse(), "every other type is dropped: absent means Data");

        var bogus = mockMinimalRule("LIB-003");
        when(bogus.ruleType()).thenReturn(Optional.of("Recordata"));
        assertNull(LibraryRuleMapper.mapRule(bogus).getLoadError(),
                "an unrecognised API value is dropped too — nothing reads it any more");
    }


    @Test
    void invalidSensitivityAndExecutability_accumulateInOneLoadError()
    {
        var src = mockMinimalRule("LIB-002");
        when(src.sensitivity()).thenReturn(Optional.of("Variable"));
        when(src.executability()).thenReturn(Optional.of("Sometimes Executable"));

        Rule mapped = LibraryRuleMapper.mapRule(src);
        assertEquals("[LIB-002] Invalid Sensitivity 'Variable' — expected one of: "
                + "Record, Dataset, Group, Study; "
                + "[LIB-002] Invalid Executability 'Sometimes Executable' — expected one of: "
                + "Fully Executable, Partially Executable, "
                + "Partially Executable - Possible Overreporting, "
                + "Partially Executable - Possible Underreporting, Not Executable",
                mapped.getLoadError());
        assertEquals("Variable", mapped.getRawSensitivity());
        assertEquals("Sometimes Executable", mapped.getRawExecutability());
    }


    @Test
    void invalidEnum_prefersCoreIdInMessage()
    {
        var src = mockMinimalRule("uuid-1");
        var core = mock(net.cumba.cdisc.library.api.model.rules.RuleCore.class);
        when(core.id()).thenReturn(Optional.of("CORE-XYZ"));
        lenient().when(core.status()).thenReturn(Optional.empty());
        lenient().when(core.version()).thenReturn(Optional.empty());
        when(src.core()).thenReturn(Optional.of(core));
        when(src.sensitivity()).thenReturn(Optional.of("Variable"));

        Rule mapped = LibraryRuleMapper.mapRule(src);
        assertNotNull(mapped.getLoadError());
        assertTrue(mapped.getLoadError().startsWith("[CORE-XYZ]"),
                "Core.Id preferred: " + mapped.getLoadError());
    }


    @Test
    void validValues_mapWithoutLoadError()
    {
        var src = mockMinimalRule("LIB-003");
        when(src.sensitivity()).thenReturn(Optional.of("Record"));
        when(src.executability()).thenReturn(Optional.of("Fully Executable"));

        Rule mapped = LibraryRuleMapper.mapRule(src);
        assertNull(mapped.getLoadError());
        assertEquals(Sensitivity.RECORD, mapped.getSensitivity());
        assertEquals(Executability.FULLY_EXECUTABLE, mapped.getExecutability());
    }


    /**
     * {@code PLAN-dangling-operation-reference-load-check} — the mapper runs the dangling-operand
     * gate too, so a library-sourced rule that would silently check nothing fails identically to a
     * file-loaded one.
     */
    @Test
    void danglingOperandReference_tagsLoadErrorOnMappedRule()
    {
        var src = mockMinimalRule("LIB-005");
        // Mockito's default answer already yields Optional.empty() / List.of() for the rest of the
        // leaf's accessors, so only the two that matter are stubbed.
        var leaf = mock(net.cumba.cdisc.library.api.model.rules.RuleCondition.class);
        lenient().when(leaf.name()).thenReturn(Optional.of("$never_defined"));
        lenient().when(leaf.operator()).thenReturn(Optional.of("non_empty"));
        when(src.check()).thenReturn(Optional.of(leaf));

        Rule mapped = LibraryRuleMapper.mapRule(src);
        assertNotNull(mapped.getLoadError());
        assertTrue(mapped.getLoadError().contains("$never_defined"), mapped.getLoadError());
        assertTrue(mapped.getLoadError().contains("which no Operations entry defines"),
                mapped.getLoadError());
    }


    @Test
    void absentValues_mapWithoutLoadError()
    {
        Rule mapped = LibraryRuleMapper.mapRule(mockMinimalRule("LIB-004"));
        assertNull(mapped.getLoadError());
        assertNull(mapped.getRejectedRuleType());
        assertNull(mapped.getSensitivity());
        assertNull(mapped.getRawSensitivity());
        assertNull(mapped.getExecutability());
        assertNull(mapped.getRawExecutability());
    }
}
