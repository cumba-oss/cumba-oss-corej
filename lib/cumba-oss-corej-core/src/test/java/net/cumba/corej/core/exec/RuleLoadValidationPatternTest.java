package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 (PLAN-extend-expression-engine) — load-time validation of glob/regex pattern entries in
 * {@code Scope.Domains} and {@code Requirements.Variables}: an invalid {@code /…/} regex tags the
 * rule with a load error instead of blowing up scope matching at generation time. Glob translation
 * quotes every literal run and cannot fail, so only the {@code /…/} form is rejectable.
 *
 * <p>
 * ⚠ The variable half moved with the field: {@code Scope.Variables} retired to
 * {@code Requirements.Variables} ({@code Include} → {@code All}, {@code Exclude} → {@code None},
 * {@code plans/PLAN-scope-requirements-split.md} phase 5), and authoring the old spelling is now a
 * load error in its own right (gate R1, {@code RequirementsLoadGateTest}) rather than a rule whose
 * pattern entries get validated.
 * </p>
 *
 * <p>
 * Each test loads a minimal rule package via {@link RulePackageLoader#loadFromString} and inspects
 * {@link Rule#getLoadError()}. Mirrors the fixture style of {@code RuleLoadValidationTest} /
 * {@code RuleLoadValidationEmptyIncludeTest}.
 * </p>
 */
class RuleLoadValidationPatternTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule onlyRule(RulePackage pkg)
    {
        return pkg.getRules().values().iterator().next();
    }


    /** Loads a minimal rule carrying the given top-level block(s) verbatim. */
    private static Rule loadWithBlocks(String coreId, String blocksJson) throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "%s"},
                  %s,
                  "Check": {
                    "name": "AESTDY",
                    "operator": "non_empty"
                  }
                }
                """.formatted(coreId, blocksJson);
        return onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
    }


    private static Rule loadWithScope(String coreId, String scopeJson) throws IOException
    {
        return loadWithBlocks(coreId, "\"Scope\": " + scopeJson);
    }


    /** The variable half's successor host — {@code Requirements.Variables}, not {@code Scope}. */
    private static Rule loadWithVariableRequirement(String coreId, String variablesJson)
        throws IOException
    {
        return loadWithBlocks(coreId, "\"Requirements\": {\"Variables\": " + variablesJson + "}");
    }


    @Test
    void invalidRegexInDomainsInclude_tagsLoadError() throws IOException
    {
        Rule rule = loadWithScope("TEST-P4-A", """
                {"Domains": {"Include": ["/[/"]}}""");
        assertNotNull(rule.getLoadError(), "invalid regex include entry should tag loadError");
        assertTrue(rule.getLoadError().contains("[TEST-P4-A]"),
                "loadError mentions the Core ID: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Scope.Domains.Include entry '/[/'"),
                "loadError names list and entry: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("is not a valid pattern"),
                "loadError carries the pattern wording: " + rule.getLoadError());
    }


    @Test
    void invalidRegexInDomainsExclude_tagsLoadError() throws IOException
    {
        Rule rule = loadWithScope("TEST-P4-B", """
                {"Domains": {"Exclude": ["/(/"]}}""");
        assertNotNull(rule.getLoadError(), "invalid regex exclude entry should tag loadError");
        assertTrue(rule.getLoadError().contains("Scope.Domains.Exclude entry '/(/'"),
                "loadError names list and entry: " + rule.getLoadError());
    }


    @Test
    void invalidRegexInVariablesAll_tagsLoadError() throws IOException
    {
        Rule rule = loadWithVariableRequirement("TEST-P4-C", """
                {"All": ["/[/"]}""");
        assertNotNull(rule.getLoadError(), "invalid regex variables entry should tag loadError");
        assertTrue(rule.getLoadError().contains("Requirements.Variables.All entry '/[/'"),
                "loadError names list and entry: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("is not a valid pattern"),
                "loadError carries the pattern wording: " + rule.getLoadError());
    }


    @Test
    void invalidRegexInVariablesNone_tagsLoadError() throws IOException
    {
        Rule rule = loadWithVariableRequirement("TEST-P4-D", """
                {"None": ["/*+/"]}""");
        assertNotNull(rule.getLoadError(), "invalid regex variables entry should tag loadError");
        assertTrue(rule.getLoadError().contains("Requirements.Variables.None entry '/*+/'"),
                "loadError names list and entry: " + rule.getLoadError());
    }


    @Test
    void validGlobsAndRegex_noLoadError() throws IOException
    {
        Rule rule = loadWithBlocks("TEST-P4-E", """
                "Scope": {
                  "Domains": {"Include": ["/^LB(HE|CH)?$/", "LB*", "SUPP--", "ALL"]}
                },
                "Requirements": {
                  "Variables": {"All": ["*DY", "--SEQ", "AESTD?"], "None": ["/^Q.*/"]}
                }""");
        assertNull(rule.getLoadError(),
                "valid patterns should leave loadError null but was: " + rule.getLoadError());
    }


    @Test
    void literalSlashEntries_stayLiteralAndLoadClean() throws IOException
    {
        // "/" and "//" are below the length-3 threshold for the /…/ form: literal, no error.
        Rule rule = loadWithScope("TEST-P4-F", """
                {"Domains": {"Include": ["/", "//"]}}""");
        assertNull(rule.getLoadError(),
                "short slash literals should not be treated as regex: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------
    // Fix #124 — qualified DATASET.VARIABLE entries in Requirements.Variables
    // ------------------------------------------------------------------


    @Test
    void qualifiedVariableEntries_loadClean() throws IOException
    {
        Rule rule = loadWithVariableRequirement("TEST-124-A", """
                {"All": ["DM.ARM", "ADSL.TRTxxPN", "SUPP--.QVAL", "DM.*DTC"],\
                 "None": ["DM./^Q.*$/"]}""");
        assertNull(rule.getLoadError(),
                "qualified entries should load clean but was: " + rule.getLoadError());
    }


    @Test
    void invalidRegexInQualifiedVariableHalf_tagsLoadError() throws IOException
    {
        Rule rule = loadWithVariableRequirement("TEST-124-B", """
                {"All": ["DM./[/"]}""");
        assertNotNull(rule.getLoadError(), "invalid regex in the variable half should tag");
        assertTrue(rule.getLoadError().contains("Requirements.Variables.All entry 'DM./[/'"),
                "loadError names the raw entry: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("is not a valid pattern"),
                "loadError carries the pattern wording: " + rule.getLoadError());
    }


    @Test
    void leadingOrTrailingDot_tagsLoadError() throws IOException
    {
        Rule leading = loadWithVariableRequirement("TEST-124-C", """
                {"All": [".ARM"]}""");
        assertNotNull(leading.getLoadError(), "leading dot should tag");
        assertTrue(leading.getLoadError().contains("must not start or end with '.'"),
                "loadError explains the shape: " + leading.getLoadError());

        Rule trailing = loadWithVariableRequirement("TEST-124-D", """
                {"None": ["DM."]}""");
        assertNotNull(trailing.getLoadError(), "trailing dot should tag");
        assertTrue(trailing.getLoadError().contains("Requirements.Variables.None entry 'DM.'"),
                "loadError names list and entry: " + trailing.getLoadError());
    }


    @Test
    void secondDotInVariableHalf_tagsLoadError() throws IOException
    {
        Rule rule = loadWithVariableRequirement("TEST-124-E", """
                {"All": ["DM.SUPP.QVAL"]}""");
        assertNotNull(rule.getLoadError(), "a second dot should tag");
        assertTrue(rule.getLoadError().contains("carries more than one '.'"),
                "loadError explains the shape: " + rule.getLoadError());
    }


    @Test
    void domainPrefixInQualifiedVariableHalf_tagsLoadError() throws IOException
    {
        Rule rule = loadWithVariableRequirement("TEST-124-F", """
                {"All": ["AE.--SEQ"]}""");
        assertNotNull(rule.getLoadError(), "'--' in the variable half should tag");
        assertTrue(rule.getLoadError().contains("no unambiguous resolution domain"),
                "loadError explains why: " + rule.getLoadError());
    }


    @Test
    void unqualifiedDomainPrefixEntryStillLoadsClean() throws IOException
    {
        // The '--' rejection is scoped to the QUALIFIED shape; the 199 migrated rules that carry
        // a bare `--OCCUR` style entry must keep loading.
        Rule rule = loadWithVariableRequirement("TEST-124-G", """
                {"All": ["--OCCUR", "--PRESP"]}""");
        assertNull(rule.getLoadError(),
                "unqualified '--' entries must stay valid: " + rule.getLoadError());
    }


    @Test
    void wholeEntryRegexWithDotsIsNotSplit() throws IOException
    {
        // The carve-out: a regex full of dots must not be read as a qualified entry (which would
        // reject it for carrying more than one '.').
        Rule rule = loadWithVariableRequirement("TEST-124-H", """
                {"All": ["/^DM\\\\..*DTC$/"]}""");
        assertNull(rule.getLoadError(), "a /…/ regex must not be split: " + rule.getLoadError());
    }
}
