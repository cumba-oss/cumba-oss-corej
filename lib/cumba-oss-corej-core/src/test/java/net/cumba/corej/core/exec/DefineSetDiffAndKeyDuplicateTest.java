package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T2-residual — the define-set-diff and define-key-duplicate operations exercised end-to-end.
 *
 * <ul>
 * <li>FDA-SD0054 ({@code define_variable_names} diffed against the data column set via
 * {@code not_contains_all}) fires when the Define-XML declares a variable absent from the submitted
 * dataset, and does not when every declared variable is present.</li>
 * <li>PMDA-SD1152 ({@code define_key_variables} as an {@code is_not_unique_set} {@code $}-ref key)
 * fires when records repeat over the Define key set, and does not when they are unique.</li>
 * <li>Both rules are <b>SKIPPED</b> — never PASS/FAIL — when no Define-XML provider is supplied
 * (input-availability gate).</li>
 * </ul>
 */
class DefineSetDiffAndKeyDuplicateTest
{

    private static MetadataProvider dmDefine;

    private static MetadataProvider lbDefine;

    private static Rule sd0054;

    private static Rule sd1152;

    @BeforeAll
    static void load() throws IOException
    {
        dmDefine = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-itemmeta-e2e.xml")));
        lbDefine = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-keys-e2e.xml")));

        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-4.json"));
        sd0054 = find(pkg, "FDA-SD0054");
        sd1152 = find(pkg, "PMDA-SD1152");
    }


    private static ODM parse(String resource) throws IOException
    {
        try (InputStream in = DefineSetDiffAndKeyDuplicateTest.class.getResourceAsStream(resource))
        {
            return new DefineXmlParser().parse(in);
        }
    }


    private static Rule find(RulePackage pkg, String id)
    {
        return pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && id.equals(r.getCore().getId())).findFirst()
                .orElseThrow(() -> new AssertionError(id + " not in package"));
    }


    /** DM define declares AGE + SEX; data has only AGE -> SD0054 fires (SEX missing). */
    @Test
    void sd0054_firesWhenDefineVariableAbsentFromData()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = RuleRunner.execute(sd0054, dm, _ -> null, "DM", null, null,
                dmDefine);
        assertTrue(r.hasViolations(), "SEX is declared in the Define but absent from the data");
    }


    /** Data carries every Define-declared variable (AGE, SEX) -> SD0054 does not fire. */
    @Test
    void sd0054_noFireWhenAllDefineVariablesPresent()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").build();

        RuleExecutionResult r = RuleRunner.execute(sd0054, dm, _ -> null, "DM", null, null,
                dmDefine);
        assertFalse(r.hasViolations(), "AGE and SEX are both present in the data");
    }


    /** No define provider -> SD0054 is SKIPPED (never PASS/FAIL). */
    @Test
    void sd0054_skippedWhenNoDefine()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = RuleRunner.execute(sd0054, dm, _ -> null, "DM", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
        assertFalse(r.hasViolations(), "SKIPPED rule reports no violations");
    }


    /** Two rows share the Define key set (USUBJID, LBTESTCD) -> SD1152 fires. */
    @Test
    void sd1152_firesOnDuplicateOverDefineKeys()
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1", "S1", "S1")
                .col("USUBJID", "S1-001", "S1-001", "S1-001").col("LBTESTCD", "ALB", "ALB", "GLUC")
                .col("LBORRES", "40", "41", "90").build();

        RuleExecutionResult r = RuleRunner.execute(sd1152, lb, _ -> null, "LB", null, null,
                lbDefine);
        assertTrue(r.hasViolations(),
                "rows 1-2 repeat the Define key set (USUBJID S1-001, LBTESTCD ALB)");
    }


    /** Every record unique over the Define key set -> SD1152 does not fire. */
    @Test
    void sd1152_noFireWhenUniqueOverDefineKeys()
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1", "S1", "S1")
                .col("USUBJID", "S1-001", "S1-001", "S1-002").col("LBTESTCD", "ALB", "GLUC", "ALB")
                .col("LBORRES", "40", "90", "42").build();

        RuleExecutionResult r = RuleRunner.execute(sd1152, lb, _ -> null, "LB", null, null,
                lbDefine);
        assertFalse(r.hasViolations(), "every (USUBJID, LBTESTCD) combination is distinct");
    }


    /**
     * M4: Define present but this dataset declares ZERO key variables. The empty key set would
     * collapse is_not_unique_set to the constant STUDYID anchor and flag every record as a
     * duplicate; the operation instead signals library-not-available so the rule SKIPS.
     */
    @Test
    void sd1152_skippedWhenDefineDeclaresNoKeys()
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1", "S1")
                .col("USUBJID", "S1-001", "S1-001").col("LBTESTCD", "ALB", "ALB")
                .col("LBORRES", "40", "41").build();

        // StubMetadataProvider.getKeyVariables returns an empty list for every domain.
        MetadataProvider emptyKeys = new StubMetadataProvider();
        RuleExecutionResult r = RuleRunner.execute(sd1152, lb, _ -> null, "LB", null, null,
                emptyKeys);
        assertTrue(r.isSkipped(),
                "empty Define key set -> rule SKIPPED, not every-record-duplicate");
        assertFalse(r.hasViolations(), "SKIPPED rule reports no violations");
    }


    /** No define provider -> SD1152 is SKIPPED. */
    @Test
    void sd1152_skippedWhenNoDefine()
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1", "S1")
                .col("USUBJID", "S1-001", "S1-001").col("LBTESTCD", "ALB", "ALB").build();

        RuleExecutionResult r = RuleRunner.execute(sd1152, lb, _ -> null, "LB", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
        assertFalse(r.hasViolations(), "SKIPPED rule reports no violations");
    }
}
