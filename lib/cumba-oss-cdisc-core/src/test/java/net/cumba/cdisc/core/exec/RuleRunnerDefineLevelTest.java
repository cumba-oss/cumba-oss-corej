package net.cumba.cdisc.core.exec;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
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
 * CORE-001081 ("variable role does not match the IG role") is a
 * {@code DEFINE_ITEM_METADATA_CHECK_AGAINST_LIBRARY_METADATA} rule whose check is
 * {@code define_variable_role != library_variable_role}. Before the gate was generalised this rule
 * type received <em>neither</em> operand (the legacy gate only fired for
 * {@code VARIABLE_METADATA_CHECK_AGAINST_LIBRARY_METADATA}), so it could never fire. These tests
 * prove it now executes against the independent define and library providers.
 * </p>
 */
class RuleRunnerDefineLevelTest
{

    private static final Path RULES = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/fixtures/rules/packages/rules-sdtmig-3-4.json");

    private static Rule core1081;

    @BeforeAll
    static void load() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadCombined(RULES);
        core1081 = pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CORE-001081".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CORE-001081 not in package"));
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
        return RuleRunner.execute(core1081, dmTable(), _ -> null, "DM", library, null, define);
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
        // CORE-001081 references define_variable_role; with no Define-XML it must SKIP, not pass.
        RuleExecutionResult r = run(providerWithRole("Identifier"), null);
        assertTrue(r.isSkipped(), "no define provider -> SKIPPED");
        assertFalse(r.hasViolations());
    }


    @Test
    void noLibraryProvider_skipped()
    {
        // CORE-001081 references library_variable_role on the value side; no Library -> SKIP.
        RuleExecutionResult r = run(null, providerWithRole("Identifier"));
        assertTrue(r.isSkipped(), "no library provider -> SKIPPED");
    }
}
