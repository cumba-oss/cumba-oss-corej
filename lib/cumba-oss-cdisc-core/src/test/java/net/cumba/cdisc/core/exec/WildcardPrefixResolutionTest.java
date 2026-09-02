package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EC-36 end-to-end: {@code --} in a <em>variable name</em> resolves through Python's
 * {@code wildcard_replacement}, not the CDISC domain code.
 *
 * <p>
 * Before EC-36 an AP dataset resolved {@code --TERM} to {@code APMHTERM} — a column that cannot
 * exist, because an AP dataset carries parent-prefixed variables — so the rule was skipped by the
 * {@code Scope.Variables} gate and reported nothing. A SUPP dataset was worse: the engine disagreed
 * with itself, {@code resolvePrefixes} yielding {@code AEQNAM} and {@code resolveTemplate}
 * {@code SUPPAEQNAM}, where the real column is {@code QNAM}.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class WildcardPrefixResolutionTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) YAMLMapper.builder().build()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule rule(String yaml) throws Exception
    {
        Rule r = MAPPER.readValue(yaml, Rule.class);
        // The legacy evaluator was retired (PLAN-legacy-engine-removal): a rule reaches the row
        // engine only with a compiled native expression, which the package loader normally
        // supplies. Hand-built test rules must compile it themselves.
        r.setCheckExpr(CheckToExpr.toExpr(r.getCheck()));
        return r;
    }


    /** A record-level rule flagging rows whose {@code --TERM} equals "BAD". */
    private static Rule wildcardTermRule() throws Exception
    {
        return rule("""
                Core: {Id: "EC36-TERM"}
                Description: "flag --TERM = BAD"
                Sensitivity: "Record"
                Scope:
                  Domains: {Include: ["ALL"]}
                Requirements:
                  Variables: {All: ["--TERM"]}
                Check:
                  all:
                  - {name: "--TERM", operator: "equal_to", value: "BAD", value_is_literal: true}
                Outcome:
                  Message: "bad term"
                  Output_Variables: ["--TERM"]
                """);
    }

    // -----------------------------------------------------------------------
    // AP — the defect
    // -----------------------------------------------------------------------


    @Test
    void apDataset_resolvesWildcardToParentPrefixedColumn() throws Exception
    {
        // APMH carries MHTERM. Pre-EC-36 this resolved to APMHTERM, the Scope.Variables gate found
        // no such column, and the rule was SKIPPED — a real finding silently lost.
        IDataTable apmh = MockTable.of().col("DOMAIN", "APMH", "APMH").col("APID", "A1", "A2")
                .col("MHTERM", "BAD", "GOOD").name("APMH").build();

        RuleExecutionResult r = RuleRunner.execute(wildcardTermRule(), apmh, _ -> null, "APMH",
                null, null);

        assertFalse(r.isSkipped(), "rule must no longer be skipped on an AP dataset");
        assertEquals(1, r.getViolations().size(), "the BAD row must be flagged via MHTERM");
        assertEquals("BAD", r.getViolations().get(0).getValues().get("MHTERM"));
    }


    @Test
    void apDatasetWithoutApid_keepsDomainCodePrefix() throws Exception
    {
        // No APID => Python is_ap is false => wildcard_replacement is the DOMAIN value, so --TERM
        // is APMHTERM and this dataset genuinely has no such column: still skipped, as before.
        IDataTable apmh = MockTable.of().col("DOMAIN", "APMH").col("MHTERM", "BAD").name("APMH")
                .build();

        RuleExecutionResult r = RuleRunner.execute(wildcardTermRule(), apmh, _ -> null, "APMH",
                null, null);

        assertTrue(r.isSkipped(), "without APID the AP suffix does not apply");
    }

    // -----------------------------------------------------------------------
    // SUPP — Java used to disagree with itself
    // -----------------------------------------------------------------------


    @Test
    void suppDataset_resolvesWildcardToBareColumnName() throws Exception
    {
        // --QNAM must become QNAM, the column that actually exists.
        Rule qnamRule = rule("""
                Core: {Id: "EC36-QNAM"}
                Description: "flag --QNAM = BAD"
                Sensitivity: "Record"
                Scope:
                  Domains: {Include: ["ALL"]}
                Requirements:
                  Variables: {All: ["--QNAM"]}
                Check:
                  all:
                  - {name: "--QNAM", operator: "equal_to", value: "BAD", value_is_literal: true}
                Outcome:
                  Message: "bad qnam"
                  Output_Variables: ["--QNAM"]
                """);
        IDataTable suppae = MockTable.of().col("RDOMAIN", "AE", "AE").col("QNAM", "BAD", "OK")
                .name("SUPPAE").build();

        RuleExecutionResult r = RuleRunner.execute(qnamRule, suppae, _ -> null, "SUPPAE", null,
                null);

        assertFalse(r.isSkipped(), "SUPP rule must not be skipped");
        assertEquals(1, r.getViolations().size());
        assertEquals("BAD", r.getViolations().get(0).getValues().get("QNAM"));
    }


    @Test
    void suppDataset_operationDomainKeepsFullCodeWhileVariableGoesBare() throws Exception
    {
        // The two jobs in one pass: `domain: "SUPP--"` is a DATASET-NAME wildcard and must stay
        // SUPPLB (Fix #59/#33), while `--QNAM` is a VARIABLE name and must become QNAM.
        net.cumba.cdisc.core.model.Operation op = new net.cumba.cdisc.core.model.Operation();
        op.setId("$x");
        op.setOperator("distinct");
        op.setDomain("SUPP--");
        op.setName("--QNAM");

        net.cumba.cdisc.core.model.Operation resolved = OperationExecutor.resolvePrefixes(op,
                "SUPPLB", "");

        assertEquals("SUPPLB", resolved.getDomain(), "dataset-name wildcard keeps the domain code");
        assertEquals("QNAM", resolved.getName(), "variable wildcard uses the empty SUPP prefix");
    }


    @Test
    void ordinaryDomain_bothPrefixesAgree() throws Exception
    {
        // Regression guard: for every ordinary domain the split must be a no-op.
        IDataTable ae = MockTable.of().col("DOMAIN", "AE", "AE").col("AETERM", "BAD", "OK")
                .name("AE").build();

        RuleExecutionResult r = RuleRunner.execute(wildcardTermRule(), ae, _ -> null, "AE", null,
                null);

        assertFalse(r.isSkipped());
        assertEquals(1, r.getViolations().size());
        assertEquals("BAD", r.getViolations().get(0).getValues().get("AETERM"));
    }

    // -----------------------------------------------------------------------
    // Non-AP / non-SUPP datasets resolve on the caller's domain code, unchanged
    // (D2' — the unresolvable-prefix skip — was withdrawn; see the plan's 11.2)
    // -----------------------------------------------------------------------


    @Test
    void nonApNonSuppDataset_resolvesExactlyAsBeforeEc36() throws Exception
    {
        // RELREC is neither AP nor SUPP, so `--TERM` resolves against the caller's domain code —
        // RELRECTERM, identical to pre-EC-36. The rule is skipped by the ordinary
        // Requirements.Variables gate, with its ordinary reason. (An earlier revision returned
        // "unresolvable" here and skipped with a bespoke message; that gate was withdrawn — see
        // plan section 10.4.)
        IDataTable relrec = MockTable.of().col("RDOMAIN", "AE").col("RELID", "1").name("RELREC")
                .build();

        RuleExecutionResult r = RuleRunner.execute(wildcardTermRule(), relrec, _ -> null, "RELREC",
                null, null);

        assertTrue(r.isSkipped());
        assertTrue(r.getStatusMessage().contains("resolved RELRECTERM"),
                "must resolve against the caller domain code, was: " + r.getStatusMessage());
    }


    @Test
    void corruptDomainCell_doesNotChangeResolution() throws Exception
    {
        // Regression guard for the defect that sank the first attempt. Row-0 DOMAIN says "GRP1"
        // while the caller supplies "AE" (SdtmAllRuleTest.CORE_000544_invalid's exact shape).
        // Re-deriving from the cell gave GRP1TERM and the rule silently no-fired.
        IDataTable ae = MockTable.of().col("DOMAIN", "GRP1", "GRP1").col("AETERM", "BAD", "OK")
                .name("AE").build();

        RuleExecutionResult r = RuleRunner.execute(wildcardTermRule(), ae, _ -> null, "AE", null,
                null);

        assertFalse(r.isSkipped(), "a corrupt DOMAIN cell must not suppress the rule");
        assertEquals(1, r.getViolations().size());
        assertEquals("BAD", r.getViolations().get(0).getValues().get("AETERM"));
    }


    @Test
    void unresolvableDataset_runsNormallyWhenRuleUsesNoWildcard() throws Exception
    {
        // A rule naming concrete columns is unaffected by anything EC-36 changed.
        Rule concrete = rule("""
                Core: {Id: "EC36-CONCRETE"}
                Description: "flag RELID = 1"
                Sensitivity: "Record"
                Scope:
                  Domains: {Include: ["ALL"]}
                Check:
                  all:
                  - {name: "RELID", operator: "equal_to", value: "1", value_is_literal: true}
                Outcome:
                  Message: "relid"
                  Output_Variables: ["RELID"]
                """);
        IDataTable relrec = MockTable.of().col("RDOMAIN", "AE").col("RELID", "1").name("RELREC")
                .build();

        RuleExecutionResult r = RuleRunner.execute(concrete, relrec, _ -> null, "RELREC", null,
                null);

        assertFalse(r.isSkipped(), "a rule without -- must not be skipped");
        assertEquals(1, r.getViolations().size());
    }


    @Test
    void zeroRowDatasetNamedAsDomain_stillResolvesFromItsName() throws Exception
    {
        // A metadata-only dataset still gets the caller's domain code, so --TERM -> AETERM.
        IDataTable emptyAe = MockTable.of().col("DOMAIN").col("AETERM").name("AE").build();

        RuleExecutionResult r = RuleRunner.execute(wildcardTermRule(), emptyAe, _ -> null, "AE",
                null, null);

        assertFalse(r.isSkipped(), "a zero-row dataset named AE resolves --TERM to AETERM");
        assertEquals(0, r.getViolations().size());
    }


    @Test
    void dotQualifiedDatasetName_keepsTheFullDomainCode()
    {
        // `SUPP--.QVAL` names a DATASET, not a column. On an APMH primary the supplemental dataset
        // is SUPPAPMH; substituting the variable prefix would give SUPPMH — a DIFFERENT dataset
        // that can genuinely exist in a study carrying both MH and APMH.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--TERM").operator("equal_to")
                .value(new com.fasterxml.jackson.databind.node.TextNode("SUPP--.QVAL")).build();
        CheckCondition resolved = CheckConditionTransformer.resolvePrefixes(
                new CheckConditionAll(java.util.List.of(leaf)), "MH", "APMH", null);

        CheckConditionLeaf out = (CheckConditionLeaf) ((CheckConditionAll) resolved).getConditions()
                .get(0);
        assertEquals("MHTERM", out.getName(), "the leaf NAME is a column -> variable prefix");
        assertEquals("SUPPAPMH.QVAL", out.getValue().asText(),
                "the dataset half keeps the full domain code");
    }


    @Test
    void dotQualifiedColumnName_takesTheVariablePrefix()
    {
        // Mirror image: the wildcard sits AFTER the dot, so it names a column on another dataset.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("USUBJID").operator("equal_to")
                .value(new com.fasterxml.jackson.databind.node.TextNode("DM.--SEQ")).build();
        CheckCondition resolved = CheckConditionTransformer.resolvePrefixes(
                new CheckConditionAll(java.util.List.of(leaf)), "MH", "APMH", null);

        CheckConditionLeaf out = (CheckConditionLeaf) ((CheckConditionAll) resolved).getConditions()
                .get(0);
        assertEquals("DM.MHSEQ", out.getValue().asText());
    }


    @Test
    void dictionaryParentIsResolvedLikeAnyOtherVariableName()
    {
        // CDISC-CG0460/CG0461 ship `dictionary_parent: "--SOC"`. It was copied verbatim, so the
        // hierarchy operation looked up a column literally named "--SOC" and both rules were dead.
        net.cumba.cdisc.core.model.Operation op = new net.cumba.cdisc.core.model.Operation();
        op.setId("$x");
        op.setOperator("valid_external_dictionary_hierarchy");
        op.setName("--HLT");
        op.setDictionaryParent("--SOC");

        net.cumba.cdisc.core.model.Operation resolved = OperationExecutor.resolvePrefixes(op, "AE",
                "AE");

        assertEquals("AEHLT", resolved.getName());
        assertEquals("AESOC", resolved.getDictionaryParent());
    }


    @Test
    void dictionaryParentAloneTriggersResolution()
    {
        // needsResolve must see it, or an op whose ONLY wildcard is dictionary_parent is returned
        // untouched.
        net.cumba.cdisc.core.model.Operation op = new net.cumba.cdisc.core.model.Operation();
        op.setId("$x");
        op.setOperator("valid_external_dictionary_hierarchy");
        op.setName("AEHLT");
        op.setDictionaryParent("--SOC");

        assertEquals("AESOC",
                OperationExecutor.resolvePrefixes(op, "AE", "AE").getDictionaryParent());
    }

    // -----------------------------------------------------------------------
    // Dot-qualified references: each half resolves on its own prefix
    // -----------------------------------------------------------------------


    private static String resolveValue(String value, String varPrefix, String domainCode)
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("equal_to")
                .value(new com.fasterxml.jackson.databind.node.TextNode(value)).build();
        CheckCondition out = CheckConditionTransformer.resolvePrefixes(
                new CheckConditionAll(java.util.List.of(leaf)), varPrefix, domainCode, null);
        return ((CheckConditionLeaf) ((CheckConditionAll) out).getConditions().get(0)).getValue()
                .asText();
    }


    @Test
    void dotQualified_bothHalvesWildcarded_resolveIndependently()
    {
        // The defect the split-and-resolve rewrite fixes: picking ONE prefix via indexOf and then
        // String.replace-ing every occurrence gave SUPPAPMH.APMHQVAL — the dataset prefix leaked
        // into the column half.
        assertEquals("SUPPAPMH.MHQVAL", resolveValue("SUPP--.--QVAL", "MH", "APMH"));
        assertEquals("SUPPSUPPAE.QNAM", resolveValue("SUPP--.--QNAM", "", "SUPPAE"));
    }


    @Test
    void dotQualified_datasetHalfResolvesEvenWhenColumnHalfIsDoubleWildcard()
    {
        // Fix #5 preserves the column-side `**` for per-row RELREC resolution, but the dataset half
        // must still resolve — previously the whole value was returned untouched.
        assertEquals("SUPPAPMH.**DECOD", resolveValue("SUPP--.**DECOD", "MH", "APMH"));
    }


    @Test
    void dotQualified_bareDatasetWildcardUsesTheDomainCode()
    {
        // "--.X" is a dataset position; it used to be caught by the startsWith branch and given the
        // VARIABLE prefix.
        assertEquals("APMH.SEQ", resolveValue("--.SEQ", "MH", "APMH"));
    }


    @Test
    void relrecDoubleWildcard_stillPreservedUntouched()
    {
        // Regression guard for Fix #5 proper.
        assertEquals("RELREC.**DECOD", resolveValue("RELREC.**DECOD", "MH", "APMH"));
    }


    @Test
    void arrayValueResolvesIdenticallyToTheSameStringAsAScalar()
    {
        // The array branch had its own narrower copy, so 3 of 5 shapes resolved differently
        // depending on whether the string sat in `value` or inside `value[]`.
        com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                .createArrayNode();
        arr.add("SUPP--.QVAL");
        arr.add("DM.--SEQ");
        arr.add("--SEQ");
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("is_contained_by")
                .value(arr).build();

        CheckCondition out = CheckConditionTransformer.resolvePrefixes(
                new CheckConditionAll(java.util.List.of(leaf)), "MH", "APMH", null);
        com.fasterxml.jackson.databind.JsonNode v = ((CheckConditionLeaf) ((CheckConditionAll) out)
                .getConditions().get(0)).getValue();

        assertEquals("SUPPAPMH.QVAL", v.get(0).asText());
        assertEquals("DM.MHSEQ", v.get(1).asText());
        assertEquals("MHSEQ", v.get(2).asText());
    }


    @Test
    void singleArgResolvePrefixesStillAppliesFix33Stripping()
    {
        // The 1-arg overload delegated (prefix, prefix), which silently disabled Fix #33's
        // SUPP/SQAP parent-stripping for variable fields: name became SUPPAEQNAM, not AEQNAM.
        net.cumba.cdisc.core.model.Operation op = new net.cumba.cdisc.core.model.Operation();
        op.setId("$x");
        op.setOperator("distinct");
        op.setName("--QNAM");
        op.setDomain("SUPP--");

        net.cumba.cdisc.core.model.Operation resolved = OperationExecutor.resolvePrefixes(op,
                "SUPPAE");

        assertEquals("AEQNAM", resolved.getName(), "Fix #33 parent-stripping must still apply");
        assertEquals("SUPPAE", resolved.getDomain());
    }


    @Test
    void qualifyingAnyPopulatedAloneTriggersResolution()
    {
        // Twin of dictionaryParentAloneTriggersResolution: needsResolve must see this field too.
        net.cumba.cdisc.core.model.Operation op = new net.cumba.cdisc.core.model.Operation();
        op.setId("$x");
        op.setOperator("distinct");
        op.setName("AESTRESC");
        op.setQualifyingAnyPopulated(java.util.List.of("--ORRES"));

        assertEquals(java.util.List.of("AEORRES"),
                OperationExecutor.resolvePrefixes(op, "AE", "AE").getQualifyingAnyPopulated());
    }

    // -----------------------------------------------------------------------
    // Grouping_Variables must resolve identically to the Check
    // -----------------------------------------------------------------------


    @Test
    void groupingVariablesResolveOnTheVariablePrefix_apDataset() throws Exception
    {
        // Reproduced defect: resolveGroupingPrefixes kept a `length() == 2` gate fed from the
        // domain code, so on an AP dataset (4-char code) --CAT stayed raw, the grouping index
        // collapsed to ONE group and the rule under-reported by half — while the Check resolved
        // --TERM correctly. That is the split-brain the plan forbids.
        Rule grouped = rule("""
                Core: {Id: "EC36-GROUP"}
                Description: "flag --TERM = BAD per --CAT group"
                Sensitivity: "Group"
                Grouping_Variables: ["--CAT"]
                Scope:
                  Domains: {Include: ["ALL"]}
                Check:
                  all:
                  - {name: "--TERM", operator: "equal_to", value: "BAD", value_is_literal: true}
                Outcome:
                  Message: "bad term"
                  Output_Variables: ["--CAT", "--TERM"]
                """);
        IDataTable apmh = MockTable.of().col("DOMAIN", "APMH", "APMH", "APMH", "APMH")
                .col("APID", "A1", "A1", "A2", "A2").col("MHCAT", "C1", "C1", "C2", "C2")
                .col("MHTERM", "BAD", "BAD", "BAD", "BAD").name("APMH").build();

        RuleExecutionResult r = RuleRunner.execute(grouped, apmh, _ -> null, "APMH", null, null);

        assertFalse(r.isSkipped());
        assertEquals(2, r.getViolations().size(),
                "one violation per --CAT group; a collapsed index would report 1");
    }


    @Test
    void groupingVariablesResolveOnTheVariablePrefix_suppDataset() throws Exception
    {
        // SUPP twin: the empty prefix must reach the grouping index too, so --NAM becomes NAM.
        Rule grouped = rule("""
                Core: {Id: "EC36-GROUP-SUPP"}
                Description: "flag --VAL = BAD per --NAM group"
                Sensitivity: "Group"
                Grouping_Variables: ["--NAM"]
                Scope:
                  Domains: {Include: ["ALL"]}
                Check:
                  all:
                  - {name: "--VAL", operator: "equal_to", value: "BAD", value_is_literal: true}
                Outcome:
                  Message: "bad qval"
                  Output_Variables: ["--NAM", "--VAL"]
                """);
        IDataTable suppae = MockTable.of().col("RDOMAIN", "AE", "AE", "AE", "AE")
                .col("NAM", "N1", "N1", "N2", "N2").col("VAL", "BAD", "BAD", "BAD", "BAD")
                .name("SUPPAE").build();

        RuleExecutionResult r = RuleRunner.execute(grouped, suppae, _ -> null, "SUPPAE", null,
                null);

        assertFalse(r.isSkipped());
        assertEquals(2, r.getViolations().size(), "one violation per NAM group");
    }
}
