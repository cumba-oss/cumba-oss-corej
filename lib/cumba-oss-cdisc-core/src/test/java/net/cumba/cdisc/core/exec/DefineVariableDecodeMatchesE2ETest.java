package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider;
import net.cumba.cdisc.core.metadata.OdmDefineXMLProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@code define_variable_decode_matches} (Fix #123, {@code DRAFT-900025}): the
 * record's decode value must equal the {@code Decode} the variable's Define-XML ItemDef codelist
 * gives for the record's coded value.
 *
 * <p>
 * Driven against {@code /define/define-vardecode-e2e.xml}, which exercises every path of the
 * "suffix proposes, metadata confirms" resolver: {@code PARAMCD}/{@code PARAM} (a {@code CD} suffix
 * confirmed by the partner's own codelist), {@code AVISITN}/{@code AVISIT} (an {@code N} suffix
 * accepted on the naming convention because the partner binds no codelist to judge by),
 * {@code ETCD}/{@code ELEMENT} (no shared stem — reached only by the unique-match fallback),
 * {@code SHIFTN}/{@code SHIFT} (a suffix candidate whose content disagrees, so the pair is
 * rejected) and {@code AGE} (no decode-carrying codelist at all).
 * </p>
 */
class DefineVariableDecodeMatchesE2ETest
{

    private static MetadataProvider define;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineVariableDecodeMatchesE2ETest.class
                .getResourceAsStream("/define/define-vardecode-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm));
    }


    private static Rule draft900025() throws IOException
    {
        String checkJson = "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"define_variable_decode_matches\",\"operator\":\"equal_to\","
                + "\"value\":false,\"value_is_literal\":true}]}";
        String json = "{\"Core\":{\"Id\":\"DRAFT-900025\"}," + ""
                + "\"Sensitivity\":\"Record\",\"Check\":" + checkJson + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\",\"variable_value\"]}}";
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"DRAFT-900025\":" + json + "}}");
        Rule r = pkg.getRules().get("DRAFT-900025");
        assertNull(r.getLoadError(), "rule must load without error");
        assertNotNull(r.getCheckExpr(), "decode-pairing rule must compile to a native checkExpr");
        return r;
    }


    private static RuleExecutionResult run(IDataTable xx, MetadataProvider def) throws IOException
    {
        return RuleRunner.execute(draft900025(), xx, _ -> null, "XX", null, null, def, null);
    }


    @Test
    void cdSuffixPartnerConfirmedByItsOwnCodelist() throws IOException
    {
        // PARAMCD -> PARAM (drop "CD"); PARAM's coded values {Albumin,Bilirubin} equal PARAMCD's
        // decodes, so the pair is confirmed. r0 matches, r1 does not.
        IDataTable xx = MockTable.of().name("XX").col("PARAMCD", "ALB", "BILI")
                .col("PARAM", "Albumin", "Albumin").build();
        assertEquals(1, run(xx, define).getViolationCount(),
                "only the mismatched decode fires (BILI should decode to Bilirubin)");
    }


    @Test
    void nSuffixPartnerAcceptedWhenMetadataCannotJudge() throws IOException
    {
        // AVISITN -> AVISIT (drop "N"). AVISIT binds no codelist, so the metadata has nothing to
        // say (UNKNOWN) and the CDISC-conventional name carries the pairing on its own.
        // Confirmation deliberately does NOT look at the record values: doing so would reject the
        // very mismatch this rule exists to report.
        IDataTable xx = MockTable.of().name("XX").col("AVISITN", "0", "8")
                .col("AVISIT", "Baseline", "Baseline").build();
        assertEquals(1, run(xx, define).getViolationCount(),
                "AVISITN=8 should decode to 'Week 8', not 'Baseline'");
    }


    @Test
    void allRecordsMatchingFiresNothing() throws IOException
    {
        IDataTable xx = MockTable.of().name("XX").col("PARAMCD", "ALB", "BILI")
                .col("PARAM", "Albumin", "Bilirubin").build();
        assertEquals(0, run(xx, define).getViolationCount(), "a fully consistent pair is quiet");
    }


    @Test
    void nonSuffixPairReachedByTheUniqueMatchFallback() throws IOException
    {
        // ETCD strips to "ET", which is not a column, so the suffix step fails. ELEMENT is the
        // only column whose coded values equal ETCD's decodes -> the fallback resolves it.
        IDataTable xx = MockTable.of().name("XX").col("ETCD", "SCRN", "PBO")
                .col("ELEMENT", "Screen", "Screen").build();
        assertEquals(1, run(xx, define).getViolationCount(),
                "PBO should decode to 'Placebo' — found only via the non-suffix fallback");
    }


    @Test
    void suffixCandidateWhoseContentDisagreesIsNotPaired() throws IOException
    {
        // SHIFTN -> SHIFT exists by name, but SHIFT's codelist ({SOMETHING ELSE}) does not match
        // SHIFTN's decodes ({Normal to High}), so the pair is rejected and nothing is evaluated.
        // Without the confirmation gate this would fire on every row.
        IDataTable xx = MockTable.of().name("XX").col("SHIFTN", "1", "1")
                .col("SHIFT", "SOMETHING ELSE", "SOMETHING ELSE").build();
        assertEquals(0, run(xx, define).getViolationCount(),
                "an unconfirmed name match must never be treated as a code/decode pair");
    }


    @Test
    void variableWithoutDecodeCarryingCodelistIsNeverEvaluated() throws IOException
    {
        // AGE binds no codelist -> codelist_code_decode is "{}" -> no decision for any row.
        IDataTable xx = MockTable.of().name("XX").col("AGE", "40", "99").build();
        assertEquals(0, run(xx, define).getViolationCount(),
                "no decode-carrying codelist -> the accessor never decides");
    }


    @Test
    void partnerColumnAbsentFromTheDatasetIsNotEvaluated() throws IOException
    {
        // PARAMCD present but PARAM absent: the suffix candidate does not exist as a column and no
        // other column confirms, so there is no decision.
        IDataTable xx = MockTable.of().name("XX").col("PARAMCD", "ALB", "BILI").build();
        assertEquals(0, run(xx, define).getViolationCount(),
                "absent decode column -> no decision, no fire");
    }


    @Test
    void codeValueOutsideTheCodelistIsNotThisRulesConcern() throws IOException
    {
        // "ZZZ" is not a term of CL.PARAMCD -> no expected decode -> no decision (SD0037's job).
        IDataTable xx = MockTable.of().name("XX").col("PARAMCD", "ZZZ")
                .col("PARAM", "Albumin", "Bilirubin").build();
        assertEquals(0, run(xx, define).getViolationCount(),
                "an out-of-codelist coded value is SD0037's finding, not a decode mismatch");
    }


    @Test
    void emptyCodeValueIsNotEvaluated() throws IOException
    {
        IDataTable xx = MockTable.of().name("XX").col("PARAMCD", "", "ALB")
                .col("PARAM", "Albumin", "Albumin").build();
        assertEquals(0, run(xx, define).getViolationCount(),
                "a blank coded value yields no decision (and the non_empty guard drops the row)");
    }


    @Test
    void skippedWhenNoDefine() throws IOException
    {
        IDataTable xx = MockTable.of().name("XX").col("PARAMCD", "BILI").col("PARAM", "Albumin")
                .build();
        RuleExecutionResult r = run(xx, null);
        assertTrue(r.isSkipped(), "no Define-XML -> the DEFINE metadata gate SKIPs the rule");
        assertFalse(r.hasViolations());
    }

}
