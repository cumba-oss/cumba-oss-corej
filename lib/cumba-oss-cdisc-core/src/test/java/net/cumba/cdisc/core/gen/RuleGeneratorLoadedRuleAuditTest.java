package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Generation-time scope-skip audit for rules supplied the way <em>production</em> supplies them —
 * loaded through {@link RulePackageLoader}, hence carrying no raw {@code id}.
 *
 * <p>
 * {@code RuleGenerator} records a skipped source rule only for rules present in its
 * {@code staticRuleSourceIds} audit set. That set used to be filled from {@code Rule#getId()},
 * which is {@code null} for every rule loaded from a shipped package, so the set was always empty
 * and no corpus rule ever produced a scope-skip audit row. The existing {@code RuleGeneratorTest}
 * cases miss this because their fixtures assign an explicit {@code id}.
 * </p>
 */
class RuleGeneratorLoadedRuleAuditTest
{

    /**
     * A domain-scoped rule as a shipped package expresses it: {@code Core.Id} only, no {@code id}.
     */
    private static Rule loadedAeScopedRule() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "TEST-LOADED-SCOPE": {
                      "Core": { "Id": "TEST-LOADED-SCOPE", "Status": "Published" },
                      "Sensitivity": "Record",
                      "Description": "AE-only rule",
                      "Scope": { "Domains": { "Include": ["AE"] } },
                      "Check": { "expression": "empty(STUDYID)" },
                      "Outcome": { "Message": "scope audit fixture" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return pkg.getRules().get("TEST-LOADED-SCOPE");
    }


    private static IDataTable table(String name)
    {
        return MockTable.of().name(name).col("STUDYID", "S1").col("USUBJID", "U1").build();
    }


    /** A library knowing AE and EX, so either can be the dataset under generation. */
    private static MetadataProvider provider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("audit")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .table(TestMetadataFixtures.table("EX").label("Exposure").className("Interventions")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static GeneratedRulePackage generateFor(Rule rule, String domain)
    {
        RuleGenerator gen = new RuleGenerator(provider(), null);
        gen.setStaticRules(List.of(rule));
        gen.setDomainName(domain);
        return gen.generate(table(domain));
    }


    /**
     * The regression: a loaded, domain-scoped rule must be audited as SKIPPED on a non-matching
     * dataset. Before the repair the audit set was empty and this list came back empty.
     */
    @Test
    void loadedDomainScopedRuleIsAuditedAsSkippedOnANonMatchingDataset() throws Exception
    {
        Rule rule = loadedAeScopedRule();
        assertNull(rule.getId(), "precondition: a loaded rule carries no raw id");

        GeneratedRulePackage pkg = generateFor(rule, "EX");

        List<SkippedSourceRule> skipped = pkg.getSkippedSourceRules().stream()
                .filter(s -> "TEST-LOADED-SCOPE".equals(s.rule().effectiveId())).toList();
        assertEquals(1, skipped.size(), "the scope skip is recorded for a loader-supplied rule");
        assertTrue(skipped.getFirst().reason().contains("EX"),
                "the reason names the rejecting domain: " + skipped.getFirst().reason());
    }


    /** On a matching dataset the rule is admitted and produces no audit row. */
    @Test
    void loadedDomainScopedRuleIsAdmittedOnItsOwnDataset() throws Exception
    {
        GeneratedRulePackage pkg = generateFor(loadedAeScopedRule(), "AE");

        assertTrue(
                pkg.getSkippedSourceRules().stream()
                        .noneMatch(s -> "TEST-LOADED-SCOPE".equals(s.rule().effectiveId())),
                "no skip row on the dataset the rule is scoped to");
        assertTrue(
                pkg.getRules().stream().anyMatch(r -> "TEST-LOADED-SCOPE".equals(r.effectiveId())),
                "the rule is admitted for execution on AE");
    }


    /**
     * SDTM {@code --}-prefix expansions keep the base rule's {@code Core.Id} verbatim, so several
     * datasets can produce audit entries carrying the <em>same</em> id. The audit set is built per
     * dataset by a fresh generator, so those siblings never share a set and cannot mask each other:
     * each non-matching dataset yields its own single skip row.
     */
    @Test
    void prefixExpansionSiblingsDoNotCollideAcrossDatasets() throws Exception
    {
        Rule ruleForEx = loadedAeScopedRule();
        Rule ruleForLb = loadedAeScopedRule();
        assertEquals(ruleForEx.effectiveId(), ruleForLb.effectiveId(),
                "precondition: siblings share one bare Core.Id");

        GeneratedRulePackage onEx = generateFor(ruleForEx, "EX");
        GeneratedRulePackage onLb = generateFor(ruleForLb, "EX");

        assertEquals(1,
                onEx.getSkippedSourceRules().stream()
                        .filter(x -> "TEST-LOADED-SCOPE".equals(x.rule().effectiveId())).count(),
                "one audit row on the first dataset");
        assertEquals(1,
                onLb.getSkippedSourceRules().stream()
                        .filter(x -> "TEST-LOADED-SCOPE".equals(x.rule().effectiveId())).count(),
                "one audit row on the second dataset — not swallowed by the shared id");
    }


    /**
     * Built-in template rules live in their own {@code GEN-*} {@code Core.Id} namespace and must
     * stay out of the audit — they are infrastructural, not user-supplied. Keying the audit set on
     * {@code Core.Id} could in principle have pulled them in; this pins that it does not.
     */
    @Test
    void builtInTemplatesAreNeverAudited() throws Exception
    {
        GeneratedRulePackage pkg = generateFor(loadedAeScopedRule(), "EX");

        assertTrue(
                pkg.getSkippedSourceRules().stream()
                        .noneMatch(s -> String.valueOf(s.rule().effectiveId()).startsWith("GEN-")),
                "no built-in template appears in the scope-skip audit");
    }
}
