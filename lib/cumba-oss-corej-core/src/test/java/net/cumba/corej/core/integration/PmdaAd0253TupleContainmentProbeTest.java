package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.expr.eval.OperationKinds;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * <b>Phase 0 of {@code plans/done/PLAN-pmda-ad0253-rescope.md}</b> ({@code Fix #247}) &mdash; pins
 * the novel composition <em>before</em> the rule is authored.
 *
 * <p>
 * The re-authored {@code PMDA-AD0253} runs on ADAE, reads SDTM {@code AE} as reference data through
 * an {@code Operations[].domain} entry, and asserts set containment with {@code not_contains_all}
 * while {@code minus} supplies the missing keys. Every ingredient ships, but two assemblies have
 * <b>zero</b> shipped instances: a tuple {@code distinct} with no {@code domain:} (all 5 shipped
 * tuple users pin a domain) and {@code not_contains_all} between two <em>tuple</em> sets (the 17
 * shipped users compare flat string lists &mdash; {@code PMDA-AD0047} is the flat-list twin this
 * probe isolates the tuple element type against). Both sides stringify through the tuple's
 * {@code List} form, so the mechanism should hold &mdash; this probe proves it empirically through
 * {@code RuleRunner}, per the standing rule that probes must run through the runner rather than
 * poke internals.
 * </p>
 *
 * <p>
 * &#9940; Plan &sect;7 Phase 0: if tuple-vs-tuple containment does not compare as expected here,
 * the lane stops and reports &mdash; the rule is not reshaped to compensate.
 * </p>
 */
class PmdaAd0253TupleContainmentProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The exact &sect;0 shape (minus the untouched {@code Source}/{@code Standards} blocks), under
     * a probe id so the pin cannot be satisfied by the shipped corpus.
     */
    private static final String RULE_YAML = """
            Core:
              Id: "PMDA-AD0253-PROBE"
            Description: "Phase-0 probe of the PMDA-AD0253 re-authoring: ADAE must contain a
              record for every (STUDYID, USUBJID, AESEQ) combination present in SDTM AE."
            Scope:
              Data_Structures:
                Include:
                - "OCCURRENCE DATA STRUCTURE"
              Subclasses:
                Include:
                - "ADVERSE EVENT"
            Operations:
            - id: "$ae_keys"
              operator: "distinct"
              domain: "AE"
              names:
              - "STUDYID"
              - "USUBJID"
              - "AESEQ"
            - id: "$adae_keys"
              operator: "distinct"
              names:
              - "STUDYID"
              - "USUBJID"
              - "AESEQ"
            - id: "$untraceable_ae_keys"
              operator: "minus"
              name: "$ae_keys"
              subtract: "$adae_keys"
            Check:
              all:
              - name: "$adae_keys"
                operator: "not_contains_all"
                value: "$ae_keys"
            Outcome:
              Message: "One or more (STUDYID, USUBJID, AESEQ) combinations present in the SDTM AE
                dataset have no corresponding record in the ADaM ADAE dataset."
              Output_Variables:
              - "$untraceable_ae_keys"
            """;

    private static Rule loadRule() throws Exception
    {
        Rule rule = MAPPER.readValue(RULE_YAML, Rule.class);
        // rules-src does not author Sensitivity — the loader derives it, so a
        // hand-bound rule must be completed the same way.
        RulePackageLoader.deriveOmittedFields(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    /**
     * The primary ADAE table. Carries an {@code AETERM} column besides the key: it is what
     * {@code AdamDataStructureDetector} / {@code AdamSubclassDetector} key OCCDS + ADVERSE EVENT
     * on, so without it the scope gate skips the rule before the Check ever runs.
     */
    private static IDataTable adae(String[]... rows)
    {
        return table("ADAE", true, rows);
    }


    /** The SDTM AE reference table — reached only through the resolver, never scoped. */
    private static IDataTable ae(String[]... rows)
    {
        return table("AE", false, rows);
    }


    private static IDataTable table(String name, boolean withAeterm, String[]... rows)
    {
        String[] studyid = new String[rows.length];
        String[] usubjid = new String[rows.length];
        String[] aeseq = new String[rows.length];
        String[] aeterm = new String[rows.length];
        for (int i = 0; i < rows.length; i++)
        {
            studyid[i] = rows[i][0];
            usubjid[i] = rows[i][1];
            aeseq[i] = rows[i][2];
            aeterm[i] = "HEADACHE";
        }
        MockTable mock = MockTable.of().col("STUDYID", studyid).col("USUBJID", usubjid).col("AESEQ",
                aeseq);
        if (withAeterm)
        {
            mock = mock.col("AETERM", aeterm);
        }
        return mock.name(name).build();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable adae, IDataTable ae)
    {
        return RuleRunner.execute(rule, adae, name -> "AE".equals(name) ? ae : null);
    }

    // ------------------------------------------------------------------
    // The derivations — both load-bearing, neither authored (plan §0 / Phase 2)
    // ------------------------------------------------------------------


    @Test
    void theShapeInfersTheDatasetDomainAtDatasetSensitivity() throws Exception
    {
        Rule rule = loadRule();
        assertNotNull(rule.getCheckExpr(), "the shape must raise to a native expression");
        assertEquals(Domain.DATASET,
                DomainScan.infer(rule.getCheckExpr(), OperationKinds.forRule(rule)),
                "the sole leaf is a whole-column verdict, so no cursor survives: the dataset"
                        + " domain, one finding per dataset");
        assertEquals(Sensitivity.DATASET, rule.getSensitivity(),
                "the sole leaf is a BROADCAST operator, so nonDatasetReason must be null and the"
                        + " rule dataset-level — this is what makes the finding count exactly one");
    }

    // ------------------------------------------------------------------
    // The containment — tuple vs tuple, through RuleRunner
    // ------------------------------------------------------------------


    @Test
    void oneMissingAeKeyYieldsExactlyOneFindingNamingOnlyThatKey() throws Exception
    {
        Rule rule = loadRule();
        IDataTable ae = ae(new String[]
        {
                "S1", "U1", "1"
        }, new String[]
        {
                "S1", "U1", "2"
        }, new String[]
        {
                "S1", "U2", "1"
        });
        IDataTable adae = adae(new String[]
        {
                "S1", "U1", "1"
        }, new String[]
        {
                "S1", "U1", "2"
        });

        RuleExecutionResult result = run(rule, adae, ae);
        assertEquals(1, result.getViolationCount(),
                "tuple-vs-tuple not_contains_all must fire exactly once at dataset level"
                        + " (status " + result.getStatus() + ", " + result.getStatusMessage()
                        + ")");
        String payload = result.getViolations().getFirst().getValues().get("$untraceable_ae_keys");
        assertNotNull(payload, "the minus result must ride the finding as $untraceable_ae_keys");
        assertTrue(payload.contains("S1, U2, 1"),
                "the missing (S1, U2, 1) key must be named in the payload; got: " + payload);
        assertFalse(payload.contains("U1"),
                "traceable keys must NOT appear in the payload — minus reports only the missing"
                        + " members; got: " + payload);
    }


    @Test
    void manyMissingAeKeysStillYieldExactlyOneFindingListingAllOfThem() throws Exception
    {
        Rule rule = loadRule();
        IDataTable ae = ae(new String[]
        {
                "S1", "U1", "1"
        }, new String[]
        {
                "S1", "U2", "1"
        }, new String[]
        {
                "S1", "U3", "1"
        }, new String[]
        {
                "S1", "U4", "1"
        });
        IDataTable adae = adae(new String[]
        {
                "S1", "U1", "1"
        });

        RuleExecutionResult result = run(rule, adae, ae);
        assertEquals(1, result.getViolationCount(),
                "the one-finding constraint must hold regardless of how many keys are missing");
        String payload = result.getViolations().getFirst().getValues().get("$untraceable_ae_keys");
        assertNotNull(payload);
        assertTrue(
                payload.contains("S1, U2, 1") && payload.contains("S1, U3, 1")
                        && payload.contains("S1, U4, 1"),
                "every missing key must be listed inside the single finding; got: " + payload);
    }


    @Test
    void everyAeKeyPresentInAdaeYieldsNoFinding() throws Exception
    {
        Rule rule = loadRule();
        IDataTable ae = ae(new String[]
        {
                "S1", "U1", "1"
        }, new String[]
        {
                "S1", "U2", "1"
        });
        IDataTable adae = adae(new String[]
        {
                "S1", "U2", "1"
        }, new String[]
        {
                "S1", "U1", "1"
        });

        RuleExecutionResult result = run(rule, adae, ae);
        assertEquals(0, result.getViolationCount(),
                "order must not matter — set containment, not sequence comparison");
        assertFalse(result.isSkipped(),
                "the rule must execute, not skip: " + result.getStatusMessage());
        assertFalse(result.isError(),
                "the rule must execute cleanly: " + result.getStatusMessage());
    }

    // ------------------------------------------------------------------
    // The flood arm — the defect being fixed (plan §0: absence runs the SAFE way)
    // ------------------------------------------------------------------


    @Test
    void anAbsentAeDatasetYieldsNoFindingAndNoError() throws Exception
    {
        Rule rule = loadRule();
        IDataTable adae = adae(new String[]
        {
                "S1", "U1", "1"
        });

        // The resolver knows no AE at all — the study simply has none loaded.
        RuleExecutionResult result = RuleRunner.execute(rule, adae, _ -> null);
        assertEquals(0, result.getViolationCount(),
                "absent AE ⇒ $ae_keys is the operator's declared EmptyResult.SET ⇒"
                        + " containsAll([]) is true ⇒ the rule passes with no finding. This is"
                        + " the arm that pins the flood being fixed.");
        assertFalse(result.isError(), "absence must not surface as an execution error");
    }


    @Test
    void anEmptyAeDatasetYieldsNoFinding() throws Exception
    {
        Rule rule = loadRule();
        IDataTable adae = adae(new String[]
        {
                "S1", "U1", "1"
        });
        // ⚠ Not MockTable.withColumns — that helper materialises ONE all-blank row, and a blank
        // AE row is genuinely untraceable (the probe's blank-key arm pins exactly that), so the
        // control would fire. A zero-row table needs zero-length columns.
        IDataTable emptyAe = MockTable.of().col("STUDYID").col("USUBJID").col("AESEQ").name("AE")
                .build();

        RuleExecutionResult result = run(rule, adae, emptyAe);
        assertEquals(0, result.getViolationCount(),
                "AE present with zero rows ⇒ zero required keys ⇒ nothing can be untraceable"
                        + " (status " + result.getStatus() + ", " + result.getStatusMessage()
                        + ")");
    }

    // ------------------------------------------------------------------
    // The accepted behaviour change — plan §9.1, asserted so it is not a surprise
    // ------------------------------------------------------------------


    @Test
    void aBlankAeKeyComponentParticipatesAndAppearsInTheMissingList() throws Exception
    {
        Rule rule = loadRule();
        // The re-authoring deliberately drops the shipped rule's three non_empty guards
        // (keeping them would flip Sensitivity back to RECORD and lose the one-finding
        // constraint), so a malformed AE row with a blank USUBJID contributes its tuple —
        // arguably correct: a malformed AE row genuinely is untraceable.
        IDataTable ae = ae(new String[]
        {
                "S1", "U1", "1"
        }, new String[]
        {
                "S1", "", "1"
        });
        IDataTable adae = adae(new String[]
        {
                "S1", "U1", "1"
        });

        RuleExecutionResult result = run(rule, adae, ae);
        assertEquals(1, result.getViolationCount());
        String payload = result.getViolations().getFirst().getValues().get("$untraceable_ae_keys");
        assertNotNull(payload);
        assertTrue(payload.contains("S1, , 1"),
                "the blank-key tuple must appear in the missing list (plan §9.1's accepted"
                        + " behaviour change); got: " + payload);
    }
}
