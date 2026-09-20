package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link RuleClassifier} — the {@code Sensitivity} rules (plan &sect;4.4), the
 * dataset-anchor accessor (&sect;3.9) and the shape-B execution pins. The {@code Rule_Type} cascade
 * it once covered was retired by {@code PLAN-leaf-scope-domain-inference.md} phase 7.
 *
 * <p>
 * Rules are built by binding JSON through the production mapper rather than by hand-constructing
 * model objects, so the tests exercise the same {@code Check} binding the loader uses.
 * </p>
 */
class RuleClassifierTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Rule rule(String json)
    {
        try
        {
            Rule bound = MAPPER.readValue(json, Rule.class);
            // An external binder must materialise the Bindings itself (7b) — see
            // RulePackageLoader.normalizeOperations' javadoc.
            RulePackageLoader.normalizeOperations(bound);
            return bound;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + json, e);
        }
    }


    private static Sensitivity sensitivity(String json)
    {
        return RuleClassifier.deriveSensitivity(rule(json)).value();
    }


    /** A Check with a single condition, in the operator's expression spelling. */
    private static String leaf(String name, String operator)
    {
        String expression = switch (operator)
        {
        case "ds_not_exists" -> "ds_not_exists(\\\"" + name + "\\\")";
        case "var_not_exists" -> "var_not_exists(\\\"" + name + "\\\")";
        case "var_is_null" -> "var_is_null(" + name + ")";
        case "non_empty" -> "not empty(" + name + ")";
        default -> throw new IllegalArgumentException(operator);
        };
        return "{\"Check\":{\"all\":[{\"expression\":\"" + expression + "\"}]}}";
    }

    /**
     * Shape-B execution pins (EC-77): a top-level {@code ds_exists} guard beside genuine data
     * leaves retains its native expression, infers the {@code {ROW}} domain and executes row-level
     * — nothing type-shaped decides it any more.
     */
    @Nested
    class ShapeBExecution
    {

        @Test
        void aShapeBRuleRetainsItsCheckExprAndExecutes()
        {
            Rule r = executable(rule(shapeB()));
            r.setSensitivity(Sensitivity.RECORD);
            RulePackageLoader.installNativeExpr(r);
            assertNotNull(r.getCheckExpr(), "ds_exists compiles context-independently"
                    + " (ExprCompiler.ExistsMode.DATASET), so the expr is retained");

            RuleExecutionResult result = RuleRunner.execute(r, sv(), tvResolver(), null, null,
                    null);
            assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                    () -> "shape B must execute, got: " + result.getStatusMessage());
            assertEquals(1, result.getViolations().size(),
                    "ds_exists(TV) holds and row 1 has an empty VISIT");
            assertEquals(1, result.getViolations().get(0).getRow());
        }


        @Test
        void theInferredDomainIsRowAndRoutesShapeBRowLevel()
        {
            // The inverted control of the pre-leaf-scope days: forcing Domain Presence Check onto
            // the identical rule made RulePackageLoader.retainsExpr decline the row-level expr
            // and execution hard-ERROR ("no native expression form") — the defect EC-77 closed.
            // Since PLAN-leaf-scope-domain-inference.md no type exists: the inferred {ROW} domain
            // routes the rule row-level.
            Rule r = executable(rule(shapeB()));
            r.setSensitivity(Sensitivity.RECORD);
            RulePackageLoader.installNativeExpr(r);
            assertNotNull(r.getCheckExpr(), "retention no longer consults the type");
            assertEquals(net.cumba.corej.core.expr.eval.Domain.ROW, r.getEvaluationDomain());

            RuleExecutionResult result = RuleRunner.execute(r, sv(), tvResolver(), null, null,
                    null);
            assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                    () -> "shape B must execute whatever the type says: "
                            + result.getStatusMessage());
            assertEquals(1, result.getViolations().size());
            assertEquals(1, result.getViolations().get(0).getRow());
        }


        /** Shape B: a top-level {@code ds_exists} guard conjunct plus real data-column leaves. */
        private static String shapeB()
        {
            return "{\"Check\":{\"all\":[{\"expression\": \"ds_exists(\\\"TV\\\")\"},"
                    + "{\"expression\": \"empty(VISIT)\"}]}}";
        }


        /** The dataset under evaluation: SV with one empty VISIT at row 1. */
        private static IDataTable sv()
        {
            return MockTable.of().col("VISIT", "WEEK 1", "", "WEEK 2").build();
        }


        /** A submission where TV is present, so the guard holds. */
        private static DatasetResolver tvResolver()
        {
            IDataTable tv = MockTable.of().col("VISIT", "WEEK 1", "WEEK 2").build();
            return name -> "TV".equals(name) ? tv : null;
        }


        /** Adds the identity and outcome fields execution requires. */
        private static Rule executable(Rule r)
        {
            RuleCore core = new RuleCore();
            core.setId("CORE-EC77-B");
            r.setCore(core);
            Outcome outcome = new Outcome();
            outcome.setMessage("shape-B guard test");
            r.setOutcome(outcome);
            return r;
        }

    }


    /**
     * Inlined operation calls: {@code RuleClassifier.deriveSensitivity} reads a bare call by the
     * same operation-expression parser as a declared operation, honouring {@code group=} and
     * {@code domain=} keyword arguments.
     */
    @Nested
    class InlinedOperationCalls
    {

        @Test
        void aGroupKwargMakesTheUsageRecordScoped()
        {
            assertEquals(Sensitivity.RECORD, sensitivity("{\"Check\":{\"expression\":"
                    + "\"max_date(DSSTDTC, domain=\\\"DS\\\", group=[USUBJID]) > date(RFSTDTC)\"}}"));
        }


        @Test
        void aSignalFreeCheckDerivesNothingInsteadOfGuessing()
        {
            // No data column and an empty operation-usage view (plan PLAN-classifier-redesign
            // §2.2): an absent signal is not evidence of a record-data rule — the derivation
            // must refuse rather than answer confidently from nothing.
            Rule r = rule("{\"Check\":{\"expression\":\"1 == 1\"}}");
            RuleClassifier.Derived<Sensitivity> s = RuleClassifier.deriveSensitivity(r);
            assertNull(s.value());
            assertEquals(RuleClassifier.Confidence.NONE, s.confidence());
        }


        @Test
        void aPlainColumnsOnlyCheckIsRecord()
        {
            // Positive evidence, not absence of evidence (Q2): with operation calls visible as
            // usages, a plain column genuinely demands the contents frame. Record stays LIKELY
            // rather than CERTAIN: a per-record leaf is a necessary, not a sufficient, sign.
            RuleClassifier.Derived<Sensitivity> s = RuleClassifier.deriveSensitivity(
                    rule("{\"Check\":{\"all\":[{\"expression\": \"AESEV == \\\"MILD\\\"\"}]}}"));
            assertEquals(Sensitivity.RECORD, s.value());
            assertEquals(RuleClassifier.Confidence.LIKELY, s.confidence());
        }


        @Test
        void aStudyLevelOperationCallDoesNotCountAsReadingTheDataset()
        {
            // The call-form twin of studyLevelOperationsDoNotCountAsReadingTheDataset: an inline
            // dataset_names() interrogates the study inventory, so nothing reads the dataset
            // under evaluation and the finding has no dataset to attach to.
            assertEquals(Sensitivity.STUDY, sensitivity("{\"Check\":{\"expression\":"
                    + "\"not_contains_all(dataset_names(), dataset_names())\"}}"));
        }

    }


    @Nested
    class SensitivityRules
    {

        @Test
        void groupingVariablesMakeItGroup()
        {
            assertEquals(Sensitivity.GROUP,
                    sensitivity("{\"Grouping_Variables\":[\"USUBJID\"],\"Check\":{\"all\":[{"
                            + "\"expression\":\"not empty(AVAL)\"}]}}"));
        }


        @Test
        void aMissingDatasetWithNoAnchorIsStudy()
        {
            assertEquals(Sensitivity.STUDY, sensitivity(leaf("DM", "ds_not_exists")));
        }


        @Test
        void anEntailedPositiveAnchorMakesItDatasetNotStudy()
        {
            assertEquals(Sensitivity.DATASET,
                    sensitivity("{\"Check\":{\"all\":[{\"expression\": \"ds_exists(\\\"MS\\\")\"},"
                            + "{\"expression\": \"ds_not_exists(\\\"MB\\\")\"}]}}"));
        }


        @Test
        void aSingleBranchAnyStillEntailsItsAnchor()
        {
            assertEquals(Sensitivity.DATASET, sensitivity(
                    "{\"Check\":{\"any\":[{\"expression\": \"ds_exists(\\\"TT\\\")\"}]}}"));
        }


        @Test
        void aMissingVariableIsNotStudyBecauseItAttachesToTheDataset()
        {
            // §3.3 refinement: only a missing DATASET has nowhere to attach; a missing variable
            // attaches to the dataset under evaluation, which by definition exists.
            assertEquals(Sensitivity.DATASET, sensitivity(leaf("STUDYID", "var_not_exists")));
        }


        @Test
        void presenceOnlyChecksAreDatasetLevel()
        {
            assertEquals(Sensitivity.DATASET,
                    sensitivity(
                            "{\"Check\":{\"all\":[{\"expression\": \"var_exists(\\\"TRTPGy\\\")\"},"
                                    + "{\"expression\": \"var_not_exists(\\\"TRTPGyN\\\")\"}]}}"));
        }


        @Test
        void aPerRecordLeafMakesItRecord()
        {
            assertEquals(Sensitivity.RECORD, sensitivity(
                    "{\"Check\":{\"all\":[{\"expression\": \"AESEV not in [\\\"MILD\\\"]\"}]}}"));
        }


        @Test
        void aDatasetConstantColumnStaysDatasetLevel()
        {
            // §3.4: DOMAIN is constant within a dataset, so a regex on it is one verdict per
            // dataset — and the regex operand must not be misread as a column reference.
            assertEquals(Sensitivity.DATASET, sensitivity(
                    "{\"Check\":{\"all\":[{\"expression\": \"DOMAIN =~ /^[^A-Z]/\"}]}}"));
        }


        @Test
        void aRecordScopedOperationMakesItRecord()
        {
            // `is_last_in_group` returns a GroupedResult — grounded from OperationExecutor.
            assertEquals(Sensitivity.RECORD, sensitivity(
                    "{\"Bindings\":[{\"name\": \"$last\", \"expression\": \"is_last_in_group()\"}],\"Check\":{\"all\":[{\"expression\": \"$last == true\"}]}}"));
        }


        @Test
        void aGroupedOperationIsRecordScopedWhateverItsOperator()
        {
            assertEquals(Sensitivity.RECORD, sensitivity(
                    "{\"Bindings\":[{\"name\": \"$n\", \"expression\": \"record_count(group=[USUBJID])\"}],\"Check\":{\"all\":[{\"expression\": \"$n > 1\"}]}}"));
        }


        @Test
        void studyLevelOperationsDoNotCountAsReadingTheDataset()
        {
            assertEquals(Sensitivity.STUDY, sensitivity(
                    "{\"Bindings\":[{\"name\": \"$ds\", \"expression\": \"dataset_names()\"}],"
                            + "\"Check\":{\"all\":[{\"expression\":"
                            + "\"not contains_all($ds, $ds)\"}]}}"));
        }


        /**
         * The {@code FDA-SD9714} / {@code PMDA-SD9714} regression.
         *
         * <p>
         * &#9940; Both leaves are dataset-wide facts, so the conjunction is one verdict per
         * dataset. Deriving {@code Record} here means the rule emits <b>one finding per record</b>
         * on every Findings dataset that omits {@code --ORRESU} and carries a non-empty
         * {@code --ORRES} — and the rule was minted (2026-08-27, {@code Fix #330}) precisely to
         * <em>replace</em> such a flood with a single "the units variable is missing" finding.
         * </p>
         *
         * <p>
         * Measured {@code Record}/{@code LIKELY} before {@code var_is_null} was registered in
         * {@code BroadcastFold.BROADCAST_COLUMN_PREDICATES}, with the rationale
         * <i>"var_is_null(--ORRES) — --ORRES is a per-record column"</i>: the compiled plan paints
         * one boolean over every row, but no vocabulary told the classifier so.
         * </p>
         */
        @Test
        void aBroadcastColumnPredicateBesideAPresenceLeafIsDatasetNotAPerRecordFlood()
        {
            RuleClassifier.Derived<Sensitivity> s = RuleClassifier.deriveSensitivity(
                    rule("{\"Check\":{\"expression\":\"not var_is_null(--ORRES) and not"
                            + " var_exists(\\\"--ORRESU\\\")\"}}"));
            assertEquals(Sensitivity.DATASET, s.value(),
                    () -> "Record here is one finding per record: " + s.rationale());
            assertEquals(RuleClassifier.Confidence.CERTAIN, s.confidence());
            assertEquals(Sensitivity.DATASET, sensitivity(leaf("AEORRES", "var_is_null")));
        }


        /**
         * The three cursor-form {@code var_is_null} rules ({@code FDA-SD1078}, {@code PMDA-SD1078},
         * {@code FDA-SD1149}) must <b>not</b> move: one finding per variable is exactly right for
         * <i>"every Permissible variable that is entirely null"</i>.
         *
         * <p>
         * {@code nonDatasetReason} keys on the operator name alone, so the new short-circuit
         * silences the {@code var_is_null} leaf for these rules too — but
         * {@link RuleClassifier#deriveSensitivity} scans <em>every</em> leaf, and the sibling
         * {@code varname() in $…} leaf lowers to {@code is_contained_by(variable_name)}, which
         * classifies {@code VARIABLE_META} and yields a reason on its own. That leaf is first in
         * document order, so even the rationale string is unchanged — pinned verbatim below against
         * the value measured before the change.
         * </p>
         */
        @Test
        void theCursorFormVarIsNullRulesKeepTheirRecordDerivationAndRationale()
        {
            RuleClassifier.Derived<Sensitivity> s = RuleClassifier
                    .deriveSensitivity(rule("{\"Bindings\":[{\"name\":\"$permissible_variables\","
                            + "\"expression\":\"get_dataset_filtered_variables(key_name=\\\"core\\\","
                            + " key_value=\\\"Perm\\\")\"}],\"Check\":{\"expression\":\"varname() in"
                            + " $permissible_variables and var_is_null(varname())\"}}"));
            assertEquals(Sensitivity.RECORD, s.value());
            assertEquals(RuleClassifier.Confidence.LIKELY, s.confidence());
            assertEquals(
                    "is_contained_by(variable_name) — variable_name is variable-level metadata,"
                            + " evaluated once per variable rather than yielding a single dataset verdict",
                    s.rationale());
        }

    }


    @Nested
    class Anchors
    {

        @Test
        void reportsTheNamedAnchorDatasets()
        {
            Rule r = rule("{\"Check\":{\"all\":[{\"expression\": \"ds_exists(\\\"TA\\\")\"},"
                    + "{\"expression\": \"ds_not_exists(\\\"EX\\\")\"}]}}");
            assertEquals(List.of("TA"), RuleClassifier.datasetAnchors(r));
        }


        @Test
        void onlyTheExplicitDatasetPresenceSpellingAnchors()
        {
            // The retired generic `exists` (rejected at load) anchors nothing; the column form
            // anchors nothing; only ds_exists names a dataset.
            Rule generic = rule("{\"Check\":{\"all\":[{\"expression\": \"exists(TA)\"}]}}");
            assertTrue(RuleClassifier.datasetAnchors(generic).isEmpty());
            Rule column = rule(
                    "{\"Check\":{\"all\":[{\"expression\": \"var_exists(\\\"TA\\\")\"}]}}");
            assertTrue(RuleClassifier.datasetAnchors(column).isEmpty());
            Rule dataset = rule(
                    "{\"Check\":{\"all\":[{\"expression\": \"ds_exists(\\\"TA\\\")\"}]}}");
            assertEquals(List.of("TA"), RuleClassifier.datasetAnchors(dataset));
        }


        @Test
        void aNegatedAnchorDoesNotCount()
        {
            Rule r = rule(
                    "{\"Check\":{\"all\":[{\"not\":{\"expression\": \"ds_exists(\\\"TA\\\")\"}}]}}");
            assertTrue(RuleClassifier.datasetAnchors(r).isEmpty());
        }


        @Test
        void anUnentailedAnchorUnderAMultiBranchAnyDoesNotCount()
        {
            Rule r = rule("{\"Check\":{\"any\":[{\"expression\": \"ds_exists(\\\"TA\\\")\"},"
                    + "{\"expression\": \"ds_exists(\\\"TE\\\")\"}]}}");
            assertTrue(RuleClassifier.datasetAnchors(r).isEmpty());
        }

    }


    @Nested
    class Degenerate
    {

        @Test
        void anAbsentCheckDerivesNothing()
        {
            RuleClassifier.Derived<Sensitivity> s = RuleClassifier.deriveSensitivity(rule("{}"));
            assertNull(s.value());
            assertEquals(RuleClassifier.Confidence.NONE, s.confidence());
            assertFalse(s.rationale().isEmpty());
        }


        @Test
        void everyDerivationCarriesARationale()
        {
            Rule r = rule(leaf("AESEQ", "non_empty"));
            assertFalse(RuleClassifier.deriveSensitivity(r).rationale().isBlank());
        }


        @Test
        void aLiteralValueIsNeverReadAsAnOperand()
        {
            // `equal_to` with value_is_literal must not contribute "variable_label" as a
            // metadata operand — the rule stays a per-record column check.
            assertEquals(Sensitivity.RECORD, sensitivity(
                    "{\"Check\":{\"all\":[{\"expression\": \"AEACN == \\\"variable_label\\\"\"}]}}"));
        }


        @Test
        void aColumnValuedOperatorContributesItsValueAsAnOperand()
        {
            // is_not_unique_set's value names key COLUMNS, so the rule reads per-record data.
            assertEquals(Sensitivity.RECORD, sensitivity("{\"Check\":{\"all\":[{\"expression\":"
                    + "\"not is_unique_set([DSCAT, USUBJID, EPOCH])\"}]}}"));
        }


        /**
         * Owner requirement #1 (2026-08-23): {@code is_unique_set([A, B, …])} carries its key tuple
         * as ONE list operand, and {@code atom(Expr)} reads operands off the positional slots —
         * without a LIST arm every flattened rule would classify with NO operands. The
         * classification must be identical between the old and the new spelling for every §2.3
         * shape (verdict AND rationale).
         */
        @Test
        void theSingleListSpellingClassifiesExactlyAsTheOldSpelling()
        {
            String[][] pairs =
            {
                    {
                            "not is_unique_set(USUBJID, keys=[DOMAIN])",
                            "not is_unique_set([USUBJID, DOMAIN])"
                    },
                    {
                            "not is_unique_set(IETESTCD, DOMAIN)",
                            "not is_unique_set([IETESTCD, DOMAIN])"
                    },
                    {
                            "not is_unique_set(ETCD)", "not is_unique_set([ETCD])"
                    },
                    {
                            "not is_unique_set(USUBJID, keys=[--TESTCD, $natural_key])",
                            "not is_unique_set([USUBJID, --TESTCD, $natural_key])"
                    },
                    {
                            "not is_unique_set(STUDYID, keys=[$define_key_variables])",
                            "not is_unique_set([STUDYID, $define_key_variables])"
                    },
                    {
                            "var_exists(TAETORD) and not is_unique_set(TAETORD, keys=[ARMCD])",
                            "var_exists(TAETORD) and not is_unique_set([TAETORD, ARMCD])"
                    },
            };
            for (String[] pair : pairs)
            {
                // The OLD shape, held as a native expression so the classifier reads the authored
                // positional/keys= operands (the pre-2026-08-23 atom arm) — the deserializer
                // would otherwise lower it to a leaf and re-raise it in the NEW shape.
                RuleClassifier.Derived<Sensitivity> before = RuleClassifier
                        .deriveSensitivity(nativeRule(pair[0]));
                // The NEW shape on the production path (deserialize → leaf → CheckToExpr raise,
                // which is where every shipped rule now arrives as a single LIST operand)…
                RuleClassifier.Derived<Sensitivity> after = RuleClassifier
                        .deriveSensitivity(boundRule(pair[1]));
                // …and held native, so the LIST arm is exercised on the authored Expr too.
                RuleClassifier.Derived<Sensitivity> afterNative = RuleClassifier
                        .deriveSensitivity(nativeRule(pair[1]));
                assertNotNull(before.value(), pair[0] + " must classify to a verdict");
                for (RuleClassifier.Derived<Sensitivity> d : List.of(after, afterNative))
                {
                    assertEquals(before.value(), d.value(), pair[1]);
                    assertEquals(before.confidence(), d.confidence(), pair[1]);
                    assertEquals(before.rationale(), d.rationale(), pair[1]);
                }
                assertEquals(RuleClassifier.datasetAnchors(nativeRule(pair[0])),
                        RuleClassifier.datasetAnchors(boundRule(pair[1])), pair[1]);
            }
        }


        /** The rule bound through the production mapper (lowered to a leaf where possible). */
        private static Rule boundRule(String expression)
        {
            return rule(
                    "{\"Check\":{\"expression\":\"" + expression.replace("\"", "\\\"") + "\"}}");
        }


        /** The rule holding the parsed expression natively — the classifier reads it as is. */
        private static Rule nativeRule(String expression)
        {
            Rule r = new Rule();
            r.setCheck(new net.cumba.corej.core.model.CheckConditionExpression(
                    net.cumba.corej.core.expr.CheckExpressionParser.parse(expression), expression));
            return r;
        }

    }

}
