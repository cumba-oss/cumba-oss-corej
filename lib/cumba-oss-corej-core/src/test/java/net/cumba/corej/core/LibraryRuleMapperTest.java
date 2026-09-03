package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.cumba.cdisc.library.api.model.rules.RuleAuthority;
import net.cumba.cdisc.library.api.model.rules.RuleCitation;
import net.cumba.cdisc.library.api.model.rules.RuleCondition;
import net.cumba.cdisc.library.api.model.rules.RuleMap;
import net.cumba.cdisc.library.api.model.rules.RuleMatchDataset;
import net.cumba.cdisc.library.api.model.rules.RuleOperation;
import net.cumba.cdisc.library.api.model.rules.RuleOutcome;
import net.cumba.cdisc.library.api.model.rules.RuleReference;
import net.cumba.cdisc.library.api.model.rules.RuleScope;
import net.cumba.cdisc.library.api.model.rules.RuleScopeFilter;
import net.cumba.cdisc.library.api.model.rules.RuleStandard;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LibraryRuleMapperTest
{

    // ---- mapRulePackage ----

    @Test
    void mapRulePackage_null_returnsNull()
    {
        assertNull(LibraryRuleMapper.mapRulePackage(null));
    }


    @Test
    void mapRulePackage_emptyRules()
    {
        var src = mock(net.cumba.cdisc.library.api.model.rules.RulePackage.class);
        RuleMap ruleMap = mock(RuleMap.class);
        when(ruleMap.keys()).thenReturn(Set.of());
        when(src.rules()).thenReturn(Optional.of(ruleMap));

        RulePackage result = LibraryRuleMapper.mapRulePackage(src);
        assertNotNull(result);
        assertTrue(result.getRules().isEmpty());
    }


    @Test
    void mapRulePackage_withOneRule()
    {
        var srcRule = mockMinimalRule("RULE-001", "Test rule");

        RuleMap ruleMap = mock(RuleMap.class);
        when(ruleMap.keys()).thenReturn(Set.of("uuid-1"));
        when(ruleMap.get("uuid-1")).thenReturn(Optional.of(srcRule));

        var src = mock(net.cumba.cdisc.library.api.model.rules.RulePackage.class);
        when(src.rules()).thenReturn(Optional.of(ruleMap));

        RulePackage result = LibraryRuleMapper.mapRulePackage(src);
        assertNotNull(result);
        assertEquals(1, result.getRules().size());
        assertTrue(result.getRules().containsKey("uuid-1"));
        assertEquals("RULE-001", result.getRules().get("uuid-1").getId());
    }


    /**
     * {@code Fix #159} — a library-sourced rule declaring {@code Executability: "Not Executable"}
     * is parked, exactly as a file-loaded one is.
     *
     * <p>
     * ⚠ This path is the reason {@code RulePackageLoader.removeParkedRules} is called explicitly
     * from {@link LibraryRuleMapper#mapRulePackage}: this mapper never runs
     * {@code RulePackageLoader.finishLoad}, so the skip would otherwise never fire here and the
     * upstream CDISC-Library corpus — which this project does not control — could hand the engine a
     * rule that declares itself not executable and then runs.
     * </p>
     *
     * <p>
     * ⚠ The executable twin is asserted <b>present in the same package</b>. Without it, an empty
     * result would be a statement about the mock rather than about the parking.
     * </p>
     */
    @Test
    void mapRulePackage_parksANotExecutableRule_andKeepsTheRest()
    {
        var parked = mockMinimalRule("RULE-PARKED", "parked");
        lenient().when(parked.executability()).thenReturn(Optional.of("Not Executable"));
        var kept = mockMinimalRule("RULE-KEPT", "kept");
        lenient().when(kept.executability()).thenReturn(Optional.of("Fully Executable"));

        RuleMap ruleMap = mock(RuleMap.class);
        when(ruleMap.keys()).thenReturn(new java.util.LinkedHashSet<>(List.of("uuid-p", "uuid-k")));
        when(ruleMap.get("uuid-p")).thenReturn(Optional.of(parked));
        when(ruleMap.get("uuid-k")).thenReturn(Optional.of(kept));

        var src = mock(net.cumba.cdisc.library.api.model.rules.RulePackage.class);
        when(src.rules()).thenReturn(Optional.of(ruleMap));

        RulePackage result = LibraryRuleMapper.mapRulePackage(src);

        assertNotNull(result);
        assertTrue(result.getRules().containsKey("uuid-k"),
                () -> "the executable twin must survive: " + result.getRules().keySet());
        assertFalse(result.getRules().containsKey("uuid-p"),
                () -> "the parked rule must be dropped: " + result.getRules().keySet());
        assertEquals(1, result.getRules().size());
    }

    // ---- mapRule ----


    @Test
    void mapRule_null_returnsNull()
    {
        assertNull(LibraryRuleMapper.mapRule(null));
    }


    @Test
    void mapRule_minimalFields()
    {
        var src = mockMinimalRule("RULE-002", "Minimal");
        Rule result = LibraryRuleMapper.mapRule(src);
        assertEquals("RULE-002", result.getId());
        assertEquals("Minimal", result.getDescription());
        assertNull(result.getScope());
        assertNull(result.getCheck());
        assertNull(result.getOutcome());
        assertTrue(result.getOperations().isEmpty());
        assertTrue(result.getMatchDatasets().isEmpty());
    }


    @Test
    void mapRule_allFields()
    {
        var src = mockMinimalRule("RULE-003", "Full");

        // Core
        var core = mock(net.cumba.cdisc.library.api.model.rules.RuleCore.class);
        when(core.id()).thenReturn(Optional.of("CORE-000351"));
        when(core.status()).thenReturn(Optional.of("Published"));
        when(core.version()).thenReturn(Optional.of("1"));
        when(src.core()).thenReturn(Optional.of(core));

        // Rule type & enums
        when(src.sensitivity()).thenReturn(Optional.of("Record"));
        when(src.executability()).thenReturn(Optional.of("Fully Executable"));

        // Scope
        RuleScope scope = mockScope();
        when(src.scope()).thenReturn(Optional.of(scope));

        // Check
        RuleCondition check = mockLeafCondition("AGE", "greater_than", "18");
        when(src.check()).thenReturn(Optional.of(check));

        // Outcome
        RuleOutcome outcome = mock(RuleOutcome.class);
        when(outcome.message()).thenReturn(Optional.of("AGE must be > 18"));
        when(outcome.outputVariables()).thenReturn(List.of("AGE"));
        when(src.outcome()).thenReturn(Optional.of(outcome));

        // Operations
        RuleOperation op = mock(RuleOperation.class);
        when(op.id()).thenReturn(Optional.of("$op1"));
        when(op.operator()).thenReturn(Optional.of("variable_count"));
        when(op.name()).thenReturn(Optional.of("AGE"));
        when(op.domain()).thenReturn(Optional.empty());
        when(op.group()).thenReturn(List.of());
        when(op.filter()).thenReturn(Optional.empty());
        when(op.codelists()).thenReturn(List.of());
        when(op.level()).thenReturn(Optional.empty());
        when(op.returntype()).thenReturn(Optional.empty());
        when(op.keyName()).thenReturn(Optional.empty());
        when(op.keyValue()).thenReturn(Optional.empty());
        when(op.ctAttribute()).thenReturn(Optional.empty());
        when(op.version()).thenReturn(Optional.empty());
        when(op.ctPackageTypes()).thenReturn(List.of());
        when(op.regex()).thenReturn(Optional.empty());
        when(op.valueIsReference()).thenReturn(Optional.empty());
        when(src.operations()).thenReturn(List.of(op));

        // Match datasets
        RuleMatchDataset md = mock(RuleMatchDataset.class);
        when(md.name()).thenReturn(Optional.of("SUPPAE"));
        when(md.keys()).thenReturn(List.of("USUBJID"));
        when(md.wildcard()).thenReturn(Optional.empty());
        when(md.child()).thenReturn(Optional.of(true));
        when(md.joinType()).thenReturn(Optional.of("inner"));
        when(src.matchDatasets()).thenReturn(List.of(md));

        // Grouping variables
        when(src.groupingVariables()).thenReturn(List.of("USUBJID"));

        Rule result = LibraryRuleMapper.mapRule(src);

        assertEquals("RULE-003", result.getId());
        assertNotNull(result.getCore());
        assertEquals("CORE-000351", result.getCore().getId());
        assertEquals("Published", result.getCore().getStatus());
        assertNotNull(result.getScope());
        assertNotNull(result.getCheck());
        assertNotNull(result.getOutcome());
        assertEquals("AGE must be > 18", result.getOutcome().getMessage());
        assertEquals(List.of("AGE"), result.getOutcome().getOutputVariables());
        assertEquals(1, result.getOperations().size());
        assertEquals("$op1", result.getOperations().getFirst().getId());
        assertEquals(1, result.getMatchDatasets().size());
        assertEquals("SUPPAE", result.getMatchDatasets().getFirst().getName());
        assertTrue(result.getMatchDatasets().getFirst().getChild());
        assertEquals(List.of("USUBJID"), result.getGroupingVariables());
    }

    // ---- CheckCondition mapping ----


    @Test
    void mapCondition_leaf()
    {
        RuleCondition src = mockLeafCondition("SEX", "equal_to", "M");
        var srcRule = mockMinimalRule("R1", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionLeaf leaf = assertInstanceOf(CheckConditionLeaf.class, result.getCheck());
        assertEquals("SEX", leaf.getName());
        assertEquals("equal_to", leaf.getOperator());
        assertEquals("M", leaf.getValue().asText());
    }


    @Test
    void mapCondition_all()
    {
        RuleCondition child1 = mockLeafCondition("A", "var_exists", null);
        RuleCondition child2 = mockLeafCondition("B", "var_exists", null);

        RuleCondition src = mock(RuleCondition.class);
        when(src.all()).thenReturn(List.of(child1, child2));
        // any/not are not checked when all() is non-empty
        lenient().when(src.any()).thenReturn(List.of());
        lenient().when(src.not()).thenReturn(Optional.empty());

        var srcRule = mockMinimalRule("R2", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, result.getCheck());
        assertEquals(2, all.getConditions().size());
    }


    @Test
    void mapCondition_any()
    {
        RuleCondition child1 = mockLeafCondition("A", "empty", null);

        RuleCondition src = mock(RuleCondition.class);
        when(src.all()).thenReturn(List.of());
        when(src.any()).thenReturn(List.of(child1));
        // not is not checked when any() is non-empty
        lenient().when(src.not()).thenReturn(Optional.empty());

        var srcRule = mockMinimalRule("R3", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionAny any = assertInstanceOf(CheckConditionAny.class, result.getCheck());
        assertEquals(1, any.getConditions().size());
    }


    @Test
    void mapCondition_not()
    {
        RuleCondition inner = mockLeafCondition("A", "empty", null);

        RuleCondition src = mock(RuleCondition.class);
        when(src.all()).thenReturn(List.of());
        when(src.any()).thenReturn(List.of());
        when(src.not()).thenReturn(Optional.of(inner));

        var srcRule = mockMinimalRule("R4", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionNot not = assertInstanceOf(CheckConditionNot.class, result.getCheck());
        assertInstanceOf(CheckConditionLeaf.class, not.getCondition());
    }

    // ---- mapLeafValue: various types ----


    @Test
    void mapLeafValue_number_integer()
    {
        RuleCondition src = mockLeafConditionWithNumber("COL", "greater_than", 42);
        var srcRule = mockMinimalRule("R5", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionLeaf leaf = (CheckConditionLeaf) result.getCheck();
        assertEquals(42, leaf.getValue().asLong());
        assertTrue(leaf.getValue().isIntegralNumber() || leaf.getValue().isLong());
    }


    @Test
    void mapLeafValue_number_double()
    {
        RuleCondition src = mockLeafConditionWithNumber("COL", "greater_than", 3.14);
        var srcRule = mockMinimalRule("R6", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionLeaf leaf = (CheckConditionLeaf) result.getCheck();
        assertEquals(3.14, leaf.getValue().asDouble(), 0.001);
    }


    @Test
    void mapLeafValue_boolean()
    {
        RuleCondition src = mockLeafConditionWithBoolean("COL", "equal_to", true);
        var srcRule = mockMinimalRule("R7", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionLeaf leaf = (CheckConditionLeaf) result.getCheck();
        assertTrue(leaf.getValue().asBoolean());
    }


    @Test
    void mapLeafValue_array()
    {
        RuleCondition src = mockLeafConditionWithArray("COL", "is_contained_by",
                List.of("M", "F", "U"));
        var srcRule = mockMinimalRule("R8", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionLeaf leaf = (CheckConditionLeaf) result.getCheck();
        assertTrue(leaf.getValue().isArray());
        assertEquals(3, leaf.getValue().size());
        assertEquals("M", leaf.getValue().get(0).asText());
    }


    @Test
    void mapLeafValue_null()
    {
        RuleCondition src = mockLeafCondition("COL", "var_exists", null);
        var srcRule = mockMinimalRule("R9", "D");
        when(srcRule.check()).thenReturn(Optional.of(src));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        CheckConditionLeaf leaf = (CheckConditionLeaf) result.getCheck();
        assertNull(leaf.getValue());
    }

    // ---- Authority/Scope deep mapping ----


    @Test
    void mapAuthority_withCitations()
    {
        RuleCitation citation = mock(RuleCitation.class);
        when(citation.citedGuidance()).thenReturn(Optional.of("Some guidance"));
        when(citation.document()).thenReturn(Optional.of("SDTMIG"));
        when(citation.item()).thenReturn(Optional.of("3.1.1"));
        when(citation.section()).thenReturn(Optional.of("Variables"));

        RuleReference ref = mock(RuleReference.class);
        when(ref.origin()).thenReturn(Optional.of("SDTM and SDTMIG Conformance Rules"));
        when(ref.version()).thenReturn(Optional.of("2.0"));
        when(ref.ruleIdentifier()).thenReturn(Optional.empty());
        when(ref.citations()).thenReturn(List.of(citation));

        RuleStandard std = mock(RuleStandard.class);
        when(std.name()).thenReturn(Optional.of("SDTMIG"));
        when(std.version()).thenReturn(Optional.of("3.4"));
        when(std.substandard()).thenReturn(Optional.empty());
        when(std.references()).thenReturn(List.of(ref));

        RuleAuthority auth = mock(RuleAuthority.class);
        when(auth.organization()).thenReturn(Optional.of("CDISC"));
        when(auth.standards()).thenReturn(List.of(std));

        var srcRule = mockMinimalRule("R10", "D");
        when(srcRule.authorities()).thenReturn(List.of(auth));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        assertFalse(result.getAuthorities().isEmpty());
        assertEquals("CDISC", result.getAuthorities().getFirst().getOrganization());
        assertEquals("SDTMIG",
                result.getAuthorities().getFirst().getStandards().getFirst().getName());
        assertEquals("Some guidance", result.getAuthorities().getFirst().getStandards().getFirst()
                .getReferences().getFirst().getCitations().getFirst().getCitedGuidance());
    }


    @Test
    void mapScope_withDomainsAndClasses()
    {
        var srcRule = mockMinimalRule("R11", "D");
        RuleScope scope = mockScope();
        when(srcRule.scope()).thenReturn(Optional.of(scope));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        assertNotNull(result.getScope());
        assertEquals(List.of("AE", "MH"), result.getScope().getDomains().getInclude());
        assertEquals(List.of("EVENTS"), result.getScope().getClasses().getInclude());
    }

    // ---- Helpers ----


    private static net.cumba.cdisc.library.api.model.rules.Rule mockMinimalRule(String id,
            String description)
    {
        var src = mock(net.cumba.cdisc.library.api.model.rules.Rule.class);
        lenient().when(src.id()).thenReturn(Optional.of(id));
        lenient().when(src.description()).thenReturn(Optional.of(description));
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


    private static RuleCondition mockLeafCondition(String name, String operator, String value)
    {
        RuleCondition cond = mock(RuleCondition.class);
        lenient().when(cond.all()).thenReturn(List.of());
        lenient().when(cond.any()).thenReturn(List.of());
        lenient().when(cond.not()).thenReturn(Optional.empty());
        lenient().when(cond.name()).thenReturn(Optional.ofNullable(name));
        lenient().when(cond.operator()).thenReturn(Optional.ofNullable(operator));
        lenient().when(cond.valueIsLiteral()).thenReturn(Optional.empty());
        lenient().when(cond.valueIsReference()).thenReturn(Optional.empty());
        lenient().when(cond.typeInsensitive()).thenReturn(Optional.empty());
        lenient().when(cond.negative()).thenReturn(Optional.empty());
        lenient().when(cond.regex()).thenReturn(Optional.empty());
        lenient().when(cond.prefixInt()).thenReturn(OptionalInt.empty());
        lenient().when(cond.suffixInt()).thenReturn(OptionalInt.empty());
        lenient().when(cond.within()).thenReturn(Optional.empty());
        lenient().when(cond.ordering()).thenReturn(Optional.empty());
        // Value
        lenient().when(cond.valueNumber()).thenReturn(Optional.empty());
        lenient().when(cond.isBoolean("value")).thenReturn(false);
        lenient().when(cond.isArray("value")).thenReturn(false);
        lenient().when(cond.valueString()).thenReturn(Optional.ofNullable(value));
        lenient().when(cond.valueList()).thenReturn(List.of());
        return cond;
    }


    private static RuleCondition mockLeafConditionWithNumber(String name, String operator,
            Number number)
    {
        RuleCondition cond = mockLeafCondition(name, operator, null);
        lenient().when(cond.valueNumber()).thenReturn(Optional.of(number));
        lenient().when(cond.valueString()).thenReturn(Optional.empty());
        return cond;
    }


    private static RuleCondition mockLeafConditionWithBoolean(String name, String operator,
            boolean value)
    {
        RuleCondition cond = mockLeafCondition(name, operator, null);
        lenient().when(cond.isBoolean("value")).thenReturn(true);
        lenient().when(cond.getBoolean("value")).thenReturn(Optional.of(value));
        lenient().when(cond.valueString()).thenReturn(Optional.empty());
        return cond;
    }


    private static RuleCondition mockLeafConditionWithArray(String name, String operator,
            List<String> values)
    {
        RuleCondition cond = mockLeafCondition(name, operator, null);
        lenient().when(cond.isArray("value")).thenReturn(true);
        lenient().when(cond.valueList()).thenReturn(values);
        lenient().when(cond.valueString()).thenReturn(Optional.empty());
        return cond;
    }


    private static RuleScope mockScope()
    {
        RuleScopeFilter domains = mock(RuleScopeFilter.class);
        when(domains.include()).thenReturn(List.of("AE", "MH"));
        when(domains.exclude()).thenReturn(List.of());
        when(domains.includeSplitDatasets()).thenReturn(Optional.empty());

        RuleScopeFilter classes = mock(RuleScopeFilter.class);
        when(classes.include()).thenReturn(List.of("EVENTS"));
        when(classes.exclude()).thenReturn(List.of());

        RuleScope scope = mock(RuleScope.class);
        when(scope.domains()).thenReturn(Optional.of(domains));
        when(scope.classes()).thenReturn(Optional.of(classes));
        when(scope.useCase()).thenReturn(Optional.empty());
        return scope;
    }

}
