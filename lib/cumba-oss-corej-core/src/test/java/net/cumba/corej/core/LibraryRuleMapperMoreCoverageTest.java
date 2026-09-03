package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.cumba.cdisc.library.api.model.rules.RuleAuthority;
import net.cumba.cdisc.library.api.model.rules.RuleCondition;
import net.cumba.cdisc.library.api.model.rules.RuleIdentifier;
import net.cumba.cdisc.library.api.model.rules.RuleOperation;
import net.cumba.cdisc.library.api.model.rules.RuleReference;
import net.cumba.cdisc.library.api.model.rules.RuleStandard;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.Reference;
import net.cumba.corej.core.model.Rule;
import net.cumba.web.api.ApiResource;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Additional coverage for {@link LibraryRuleMapper} targeting the {@code mapRuleIdentifier}-like
 * private mappers exercised through the public {@link LibraryRuleMapper#mapRule} entry point.
 * Complements {@link LibraryRuleMapperTest} for the {@code mapRuleIdentifier} (Reference) and
 * {@code mapFilter} (Operation) paths.
 */
@ExtendWith(MockitoExtension.class)
class LibraryRuleMapperMoreCoverageTest
{

    @Test
    void mapReference_ruleIdentifier_populated()
    {
        RuleIdentifier rid = mock(RuleIdentifier.class);
        when(rid.id()).thenReturn(Optional.of("CG0151"));
        when(rid.version()).thenReturn(Optional.of("1"));

        RuleReference ref = mock(RuleReference.class);
        when(ref.origin()).thenReturn(Optional.of("SDTM Conformance Rules"));
        when(ref.version()).thenReturn(Optional.of("2.0"));
        when(ref.ruleIdentifier()).thenReturn(Optional.of(rid));
        when(ref.citations()).thenReturn(List.of());

        RuleStandard std = mock(RuleStandard.class);
        when(std.name()).thenReturn(Optional.of("SDTMIG"));
        when(std.version()).thenReturn(Optional.of("3.4"));
        when(std.substandard()).thenReturn(Optional.empty());
        when(std.references()).thenReturn(List.of(ref));

        RuleAuthority auth = mock(RuleAuthority.class);
        when(auth.organization()).thenReturn(Optional.of("CDISC"));
        when(auth.standards()).thenReturn(List.of(std));

        var srcRule = mockMinimalRule("R-id-1", "Test description");
        when(srcRule.authorities()).thenReturn(List.of(auth));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        Authority a = result.getAuthorities().getFirst();
        Reference r = a.getStandards().getFirst().getReferences().getFirst();
        net.cumba.corej.core.model.RuleIdentifier target = r.getRuleIdentifier();
        assertNotNull(target);
        assertEquals("CG0151", target.getId());
        assertEquals("1", target.getVersion());
    }


    @Test
    void mapReference_ruleIdentifier_emptyIdAndVersion()
    {
        RuleIdentifier rid = mock(RuleIdentifier.class);
        when(rid.id()).thenReturn(Optional.empty());
        when(rid.version()).thenReturn(Optional.empty());

        RuleReference ref = mock(RuleReference.class);
        when(ref.origin()).thenReturn(Optional.empty());
        when(ref.version()).thenReturn(Optional.empty());
        when(ref.ruleIdentifier()).thenReturn(Optional.of(rid));
        when(ref.citations()).thenReturn(List.of());

        RuleStandard std = mock(RuleStandard.class);
        when(std.name()).thenReturn(Optional.empty());
        when(std.version()).thenReturn(Optional.empty());
        when(std.substandard()).thenReturn(Optional.empty());
        when(std.references()).thenReturn(List.of(ref));

        RuleAuthority auth = mock(RuleAuthority.class);
        when(auth.organization()).thenReturn(Optional.empty());
        when(auth.standards()).thenReturn(List.of(std));

        var srcRule = mockMinimalRule("R-id-2", "X");
        when(srcRule.authorities()).thenReturn(List.of(auth));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        Reference r = result.getAuthorities().getFirst().getStandards().getFirst().getReferences()
                .getFirst();
        net.cumba.corej.core.model.RuleIdentifier target = r.getRuleIdentifier();
        assertNotNull(target);
        assertNull(target.getId());
        assertNull(target.getVersion());
    }


    @Test
    void mapOperation_filter_populatedFromApiResource()
    {
        Map<String, Object> filterMap = new LinkedHashMap<>();
        filterMap.put("QNAM", "RACE");
        filterMap.put("RDOMAIN", "AE");
        ApiResource filter = MapResource.of(filterMap);

        RuleOperation op = mock(RuleOperation.class);
        when(op.id()).thenReturn(Optional.of("$op1"));
        when(op.operator()).thenReturn(Optional.of("record_count"));
        when(op.name()).thenReturn(Optional.empty());
        when(op.domain()).thenReturn(Optional.empty());
        when(op.group()).thenReturn(List.of());
        when(op.filter()).thenReturn(Optional.of(filter));
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

        var srcRule = mockMinimalRule("R-filter-1", "X");
        when(srcRule.operations()).thenReturn(List.of(op));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        assertEquals(1, result.getOperations().size());
        Map<String, Object> mappedFilter = result.getOperations().getFirst().getFilter();
        assertNotNull(mappedFilter);
        assertEquals("RACE", mappedFilter.get("QNAM"));
        assertEquals("AE", mappedFilter.get("RDOMAIN"));
    }


    @Test
    void mapOperation_filter_absent_nullFilter()
    {
        RuleOperation op = mock(RuleOperation.class);
        when(op.id()).thenReturn(Optional.of("$op2"));
        when(op.operator()).thenReturn(Optional.of("record_count"));
        when(op.name()).thenReturn(Optional.empty());
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

        var srcRule = mockMinimalRule("R-filter-2", "X");
        when(srcRule.operations()).thenReturn(List.of(op));

        Rule result = LibraryRuleMapper.mapRule(srcRule);
        assertNull(result.getOperations().getFirst().getFilter());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


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


    // Suppress unused-import warning when these aren't directly referenced.
    @SuppressWarnings("unused")
    private static void unused(RuleCondition c, OptionalInt o)
    {
        // no-op
    }
}
