package net.cumba.corej.core.exec;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableUniverse;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of PLAN-coreJ-cdisc-provider: the three-level metadata model wired into rule evaluation.
 *
 * <p>
 * The carrier is a hand-written define-item rule ({@link #DEFINE_ITEM_RULE}): it iterates the
 * Define-XML ItemDefs ({@code Variable_Universe: Define}) and compares the define role with the
 * library role ({@code var_role("DEFINE") != var_role("LIBRARY")}). It is the successor shape of
 * the retired {@code DEFINE_ITEM_METADATA_CHECK_AGAINST_LIBRARY_METADATA} rule type, which before
 * the gate was generalised received <em>neither</em> operand (the legacy gate only fired for
 * {@code VARIABLE_METADATA_CHECK_AGAINST_LIBRARY_METADATA}), so it could never fire. These tests
 * prove the loader derives that shape — one verdict per variable, needing both the define and the
 * library provider — and that it executes against the independent define and library providers,
 * SKIPPING when either is absent.
 * </p>
 */
class RuleRunnerDefineLevelTest
{

    private static final String DEFINE_ITEM_RULE = """
            {"rules":{"D1":{"Core":{"Id":"T-DEFINE-ROLE"},"Variable_Universe":"Define",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Check":{"expression":
               "not empty(var_name(\\"DEFINE\\")) and var_role(\\"DEFINE\\") != var_role(\\"LIBRARY\\")"},
             "Outcome":{"Message":"m","Output_Variables":
               ["define_variable_name","define_variable_role","library_variable_role"]}}}}""";

    private static Rule defineItemRule;

    @BeforeAll
    static void load() throws IOException
    {
        defineItemRule = RulePackageLoader.loadFromString(DEFINE_ITEM_RULE).getRules().get("D1");
        assertNull(defineItemRule.getLoadError(),
                "the hand-written rule must load: " + defineItemRule.getLoadError());
    }


    /**
     * A provider that reports {@code role} for DM.AGE (used as either the library or define level).
     */
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


    private static RuleExecutionResult run(
            @org.jspecify.annotations.Nullable MetadataProvider library,
            @org.jspecify.annotations.Nullable MetadataProvider define)
    {
        return RuleRunnerCalls.execute(defineItemRule, dmTable(), _ -> null, "DM", library, null,
                define);
    }


    /**
     * The derived define-item shape: the authored universe iterates the ItemDefs, the loader infers
     * a per-variable evaluation domain (no row cursor), and the rule needs both the define and the
     * library provider — the three facts the retired {@code Rule_Type} used to state.
     */
    @Test
    void inlineRuleDerivesTheDefineItemShape()
    {
        assertEquals(VariableUniverse.DEFINE, defineItemRule.getVariableUniverse());
        assertEquals(Domain.VARIABLE, defineItemRule.getEvaluationDomain(),
                "one verdict per define variable, no row cursor");
        assertEquals(new ProviderRequirements(true, true, false),
                ProviderRequirements.of(defineItemRule),
                "the rule reads the define AND the library level");
    }


    @Test
    void defineRoleDiffersFromLibrary_violation()
    {
        assertTrue(run(providerWithRole("Record Qualifier"), providerWithRole("Identifier"))
                .hasViolations(), "define role != library role -> violation");
    }


    @Test
    void defineRoleMatchesLibrary_noViolation()
    {
        assertFalse(
                run(providerWithRole("Identifier"), providerWithRole("Identifier")).hasViolations(),
                "define role == library role -> no violation");
    }


    @Test
    void noDefineProvider_skipped()
    {
        // The rule reads var_role("DEFINE"); with no Define-XML it must SKIP, not pass.
        RuleExecutionResult r = run(providerWithRole("Identifier"), null);
        assertTrue(r.isSkipped(), "no define provider -> SKIPPED");
        assertFalse(r.hasViolations());
    }


    @Test
    void noLibraryProvider_skipped()
    {
        // The rule reads var_role("LIBRARY") on the value side; no Library -> SKIP.
        RuleExecutionResult r = run(null, providerWithRole("Identifier"));
        assertTrue(r.isSkipped(), "no library provider -> SKIPPED");
    }
}
