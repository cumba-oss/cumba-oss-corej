package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link LibraryValidator} wiring that collects the bare CORE ids of SDTM {@code
 * --}-prefix expansions (surfaced via {@link LibraryValidator#getSdtmPrefixExpandedIds()} and used
 * by the report writer to bundle their per-domain rows). Kept in its own class to stay clear of the
 * larger {@code LibraryValidatorTest}.
 */
class LibraryValidatorSdtmExpansionTest
{

    private static MetadataProvider providerWithDm()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .column(TestMetadataFixtures.column("DMDTC", 1, DataValueType.STRING)
                                .label("Date/Time of Collection").core("Perm").role("Timing")
                                .build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1", "STUDY1")
                .col("DMDTC", "2024-01-01", "").build();
    }


    /** A static rule whose Check carries a {@code --} prefix, so it is SDTM-prefix expanded. */
    private static RulePackage dashRulePackage(String coreId)
    {
        Rule rule = new Rule();
        rule.setId("uuid-" + coreId);
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("--DTC").operator("empty").build());
        Outcome outcome = new Outcome();
        outcome.setMessage("--DTC must not be empty");
        rule.setOutcome(outcome);

        RulePackage pkg = new RulePackage();
        Map<String, Rule> rules = new HashMap<>();
        rules.put(coreId, rule);
        pkg.setRules(rules);
        return pkg;
    }


    @Test
    void validateCollectsBareCoreIdOfSdtmPrefixExpansion()
    {
        LibraryValidator validator = LibraryValidator.builder().provider(providerWithDm())
                .rules(dashRulePackage("CORE-000001")).targetDataset("DM", "dm.xpt", dmTable())
                .build();

        ValidationReport report = validator.validate();
        assertNotNull(report);

        // The `--DTC` rule was expanded for DM, and its expansion keeps the bare base CORE id —
        // no GEN-EXP- prefix, no domain code. That id is what the report writer bundles on.
        assertTrue(validator.getSdtmPrefixExpandedIds().contains("CORE-000001"),
                "the SDTM `--` expansion's bare CORE id must be collected for bundling");
    }


    @Test
    void getSdtmPrefixExpandedIdsEmptyWhenNoDashRule()
    {
        // A plain rule without `--` produces no SDTM-prefix expansion, so nothing is collected.
        Rule plain = new Rule();
        plain.setId("uuid-CORE-000002");
        RuleCore core = new RuleCore();
        core.setId("CORE-000002");
        plain.setCore(core);
        plain.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        RulePackage pkg = new RulePackage();
        Map<String, Rule> rules = new HashMap<>();
        rules.put("CORE-000002", plain);
        pkg.setRules(rules);

        LibraryValidator validator = LibraryValidator.builder().provider(providerWithDm())
                .rules(pkg).targetDataset("DM", "dm.xpt", dmTable()).build();
        validator.validate();

        assertFalse(validator.getSdtmPrefixExpandedIds().contains("CORE-000002"));
        assertEquals(0, validator.getSdtmPrefixExpandedIds().stream()
                .filter(id -> id.startsWith("CORE-000002")).count());
    }
}
