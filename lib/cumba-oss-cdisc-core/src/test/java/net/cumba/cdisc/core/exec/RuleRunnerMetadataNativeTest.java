package net.cumba.cdisc.core.exec;

import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.column;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.lib;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.CheckConditionConstant;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 2b — native evaluation of a metadata-check rule whose {@code checkExpr} uses the
 * {@code var_*} accessors: one finding per failing variable, and SKIPPED when a required provider
 * is absent (D7).
 */
class RuleRunnerMetadataNativeTest
{

    private static MetadataProvider providerWithRole(String role)
    {
        IMetadataLibrary l = lib("x").table(
                table("DM").column(column("AGE", 0, DataValueType.LONG).role(role).build()).build())
                .build();
        return MetadataLibraryProvider.forDefine(l);
    }


    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("AGE", "56").build();
    }


    /**
     * A metadata-check rule whose native checkExpr compares define vs library role per variable.
     */
    private static Rule roleRule()
    {
        Rule rule = new Rule();
        rule.setVariableUniverse(net.cumba.cdisc.core.model.VariableUniverse.DEFINE);
        rule.setCheck(CheckConditionConstant.FALSE); // dummy non-null legacy check (unused
                                                     // natively)
        rule.setCheckExpr(CheckExpressionParser.parse(
                "var_role(variable_name, \"DEFINE\") != var_role(variable_name, \"LIBRARY\")"));
        return rule;
    }


    private static RuleExecutionResult run(Rule rule, @Nullable MetadataProvider library,
            @Nullable MetadataProvider define)
    {
        return RuleRunner.execute(rule, dmTable(), _ -> null, "DM", library, null, define);
    }


    @Test
    void definesRoleDiffersFromLibrary_perVariableViolation()
    {
        RuleExecutionResult r = run(roleRule(), providerWithRole("Record Qualifier"),
                providerWithRole("Identifier"));
        assertTrue(r.hasViolations(), "define role != library role -> one finding for AGE");
    }


    @Test
    void defineRoleMatchesLibrary_noViolation()
    {
        RuleExecutionResult r = run(roleRule(), providerWithRole("Identifier"),
                providerWithRole("Identifier"));
        assertFalse(r.hasViolations(), "define role == library role -> no finding");
    }


    @Test
    void nativeAuthoredRuleFiresEvenWithFlagOff()
    {
        // A CheckConditionExpression (native-only authoring) has no legacy surface, so it must take
        // the metadata fast path regardless of the nativeEval opt-in (which defaults to false).
        String src = "var_role(variable_name, \"DEFINE\") != var_role(variable_name, \"LIBRARY\")";
        net.cumba.cdisc.core.expr.ast.Expr e = CheckExpressionParser.parse(src);
        Rule rule = new Rule();
        rule.setVariableUniverse(net.cumba.cdisc.core.model.VariableUniverse.DEFINE);
        rule.setCheck(new net.cumba.cdisc.core.model.CheckConditionExpression(e, src));
        rule.setCheckExpr(e);
        RuleExecutionResult r = RuleRunner.execute(rule, dmTable(), _ -> null, "DM",
                providerWithRole("Record Qualifier"), null, providerWithRole("Identifier"));
        assertTrue(r.hasViolations(), "native-authored metadata rule fires with nativeEval off");
    }


    @Test
    void absentProviderIsSkipped()
    {
        RuleExecutionResult r = run(roleRule(), null, providerWithRole("Identifier"));
        assertTrue(r.isSkipped(), "var_role(...,\"LIBRARY\") with no library provider -> SKIPPED");
        assertFalse(r.hasViolations());
    }
}
