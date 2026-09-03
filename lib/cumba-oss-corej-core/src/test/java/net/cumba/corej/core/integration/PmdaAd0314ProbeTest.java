package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Probe for the finalized PMDA-AD0314 (Original/Prior MedDRA Coding *ORGy index legality). After
 * the OriginalTextBackfill supplied the concrete variable family from the PMDA v6.0 sheet
 * (DECDORGy/BDSYORGy/HLGTORGy/HLTORGy/LLTORGy/LLTNORGy, y in [1-9]), the rule is a Variable
 * Metadata Check that fires on a family variable whose single-digit index is 0 (the only single
 * digit not in [1-9]) — mirroring the shipping PMDA-AD0299 SMQzz index-legality mechanism.
 */
class PmdaAd0314ProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule load() throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/PMDA/PMDA-AD0314.yaml");
        Rule rule = MAPPER.readValue(Files.readString(file), Rule.class);
        // rules-src no longer carries Rule_Type / Sensitivity — the loader
        // derives them, so a hand-bound rule must be completed the same way.
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        RulePackageLoader.deriveOmittedFields(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null);
        return result.getViolationCount();
    }


    @Test
    void convertsToNativeExpression() throws Exception
    {
        assertNotNull(load().getCheckExpr(), "PMDA-AD0314 must raise to a native expression");
    }


    @Test
    void firesOnZeroIndex_notOnValidOrNonFamily() throws Exception
    {
        Rule rule = load();
        // AEDECOD is the scope fixture, not a check input: the rule is gated by
        // Scope.Data_Structures + Scope.Subclasses, both derived from the dataset's columns
        // (AEDECOD ends with an OCCDS suffix and marks the ADVERSE EVENT subclass). It is not
        // in the *ORGy family regex, so it never fires the rule itself.
        // A family variable with the illegal 0 index makes the (Dataset-sensitivity) rule fire.
        IDataTable decd0 = MockTable.of().col("USUBJID", "S1").col("DECDORG0", "x")
                .col("AEDECOD", "Headache").name("ADAE").build();
        assertEquals(1, violations(rule, decd0), "DECDORG0 (index 0) must fire");

        // LLTNORG root is distinct from LLTORG and must match on its own.
        IDataTable lltn0 = MockTable.of().col("USUBJID", "S1").col("LLTNORG0", "1")
                .col("AEDECOD", "Headache").name("ADAE").build();
        assertEquals(1, violations(rule, lltn0), "LLTNORG0 (index 0) must fire");

        // Valid 1-9 indices + a non-family MedDRA-ish column → no violation.
        IDataTable good = MockTable.of().col("USUBJID", "S1").col("DECDORG1", "x")
                .col("BDSYORG9", "y").col("AEDECOD", "z").name("ADAE").build();
        assertEquals(0, violations(rule, good),
                "valid 1-9 indices and non-family columns must not fire");
    }
}
