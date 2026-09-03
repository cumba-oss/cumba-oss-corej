package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for model POJOs: Rule, RuleCore, Scope, Authority, Outcome, etc.
 */
class RuleModelTest
{

    @Test
    void testRule_allFields()
    {
        Rule rule = new Rule();
        rule.setId("uuid");
        RuleCore core = new RuleCore();
        core.setId("CORE-001");
        core.setStatus("Published");
        core.setVersion("1");
        rule.setCore(core);
        rule.setDescription("Test rule");
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setExecutability(Executability.FULLY_EXECUTABLE);

        assertEquals("uuid", rule.getId());
        assertEquals("CORE-001", rule.getCore().getId());
        assertEquals("Published", rule.getCore().getStatus());
        assertEquals("1", rule.getCore().getVersion());
        assertEquals("Test rule", rule.getDescription());
        assertEquals(Sensitivity.RECORD, rule.getSensitivity());
        assertEquals(Executability.FULLY_EXECUTABLE, rule.getExecutability());
    }


    @Test
    void testOutcome()
    {
        Outcome outcome = new Outcome();
        outcome.setMessage("Error found");
        outcome.setOutputVariables(List.of("A", "B"));

        assertEquals("Error found", outcome.getMessage());
        assertEquals(List.of("A", "B"), outcome.getOutputVariables());
    }


    @Test
    void testScope()
    {
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of("DM", "AE"));
        ds.setExclude(List.of("SUPPQUAL"));
        ds.setIncludeSplitDatasets(true);
        scope.setDomains(ds);

        ClassScope cs = new ClassScope();
        cs.setInclude(List.of("EVENTS"));
        scope.setClasses(cs);
        scope.setUseCase("Submission");

        assertEquals(List.of("DM", "AE"), scope.getDomains().getInclude());
        assertEquals(List.of("SUPPQUAL"), scope.getDomains().getExclude());
        assertTrue(scope.getDomains().getIncludeSplitDatasets());
        assertEquals(List.of("EVENTS"), scope.getClasses().getInclude());
        assertEquals("Submission", scope.getUseCase());
    }


    @Test
    void testAuthority()
    {
        Authority auth = new Authority();
        auth.setOrganization("CDISC");

        AuthorityStandard std = new AuthorityStandard();
        std.setName("SDTMIG");
        std.setVersion("3.4");
        std.setSubstandard("sub");

        Reference ref = new Reference();
        ref.setOrigin("SDTM");
        ref.setVersion("2.0");

        RuleIdentifier rid = new RuleIdentifier();
        rid.setId("CG0001");
        rid.setVersion("1");
        ref.setRuleIdentifier(rid);

        Citation cit = new Citation();
        cit.setCitedGuidance("guidance text");
        cit.setDocument("doc");
        cit.setItem("item");
        cit.setSection("section");
        ref.setCitations(List.of(cit));

        std.setReferences(List.of(ref));
        auth.setStandards(List.of(std));

        assertEquals("CDISC", auth.getOrganization());
        assertEquals("SDTMIG", auth.getStandards().get(0).getName());
        assertEquals("CG0001",
                auth.getStandards().get(0).getReferences().get(0).getRuleIdentifier().getId());
        assertEquals("guidance text", auth.getStandards().get(0).getReferences().get(0)
                .getCitations().get(0).getCitedGuidance());
    }


    @Test
    void testOperation()
    {
        Operation op = new Operation();
        op.setId("$var");
        op.setOperator("distinct");
        op.setName("USUBJID");
        op.setDomain("DM");
        op.setGroup(List.of("STUDYID"));
        op.setRegex("^[A-Z]");
        op.setKeyName("key");
        op.setKeyValue("val");
        op.setCtAttribute("attr");
        op.setVersion("1.0");
        op.setCtPackageTypes(List.of("sdtm"));
        op.setReturntype("string");
        op.setLevel("top");
        op.setCodelists(List.of("CL1"));
        op.setValueIsReference(true);

        assertEquals("$var", op.getId());
        assertEquals(OperationType.DISTINCT, op.getOperationType());
        assertEquals("USUBJID", op.getName());
        assertEquals("DM", op.getDomain());
        assertEquals(List.of("STUDYID"), op.getGroup());
        assertEquals("^[A-Z]", op.getRegex());
        assertTrue(op.getValueIsReference());
    }


    @Test
    void testOperation_unknownOperator()
    {
        Operation op = new Operation();
        op.setOperator("future_operator");
        assertNull(op.getOperationType());
    }


    @Test
    void testMatchDataset()
    {
        MatchDataset md = new MatchDataset();
        md.setName("DS");
        md.setKeys(List.of("USUBJID", "STUDYID"));
        md.setWildcard("--");
        md.setChild(true);
        md.setJoinType("inner");

        assertEquals("DS", md.getName());
        assertEquals(2, md.getKeys().size());
        assertEquals("--", md.getWildcard());
        assertTrue(md.getChild());
        assertEquals("inner", md.getJoinType());
        // Programmatic same-named keys are not sided.
        assertFalse(md.hasSidedKeys());
        assertEquals(md.getKeys(), md.getRightKeys());
    }


    @Test
    void testMatchDataset_sameNamedKeysFromJson() throws Exception
    {
        // Historical bare-string shape: getKeys()==getRightKeys(), not sided.
        MatchDataset md = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"Name\":\"EX\",\"Keys\":[\"USUBJID\",\"STUDYID\"]}", MatchDataset.class);
        assertFalse(md.hasSidedKeys());
        assertEquals(List.of("USUBJID", "STUDYID"), md.getKeys());
        assertEquals(List.of("USUBJID", "STUDYID"), md.getRightKeys());
    }


    @Test
    void testMatchDataset_sidedKeysFromJson() throws Exception
    {
        // EC-18 / P5c: a {left, right} entry declares a differently-named key per side; a bare
        // string stays same-named. getKeys() = left names, getRightKeys() = right names.
        MatchDataset md = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"Name\":\"PM\",\"Keys\":[\"USUBJID\",{\"left\":\"TFSPID\",\"right\":\"PMSPID\"}]}",
                MatchDataset.class);
        assertTrue(md.hasSidedKeys());
        assertEquals(List.of("USUBJID", "TFSPID"), md.getKeys());
        assertEquals(List.of("USUBJID", "PMSPID"), md.getRightKeys());
    }
}
