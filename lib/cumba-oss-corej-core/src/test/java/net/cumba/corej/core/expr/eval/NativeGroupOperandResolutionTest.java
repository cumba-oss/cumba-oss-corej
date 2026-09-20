package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P2 of {@code plans/done/PLAN-native-engine-full-coverage.md} — group-operator operands beyond
 * plain columns: {@code --}-prefix domain wildcards (resolved per run against
 * {@code ctx.getDomainPrefix()}, inside the compiled closure, so the per-{@code Expr} program cache
 * stays dataset-agnostic) and {@code $}-operation lists for {@code not_contains_all}.
 *
 * <p>
 * The legacy reference for the {@code --} cases applies {@code
 * CheckConditionTransformer.resolvePrefixes} first — exactly what {@code RuleRunner} phase 2c does
 * before legacy evaluation — then evaluates via {@code CheckEvaluator}; the native side evaluates
 * the RAW (unresolved) raised expression against a context carrying the domain prefix.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class NativeGroupOperandResolutionTest
{

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    private static EvaluationContext ctx(IDataTable t, String domainPrefix)
    {
        return EvaluationContext.builder().table(t).domainPrefix(domainPrefix).build();
    }


    /**
     * Specialises the {@code --} expression for {@code prefix} (D77 — the bind-time stage the
     * production pipeline runs) and evaluates it natively; callers assert the explicit expected
     * bits (the legacy comparison oracle retired with the engine).
     */
    private static BitSet assertPrefixedParity(String source, IDataTable t, String prefix)
    {
        Expr specialised = net.cumba.corej.core.exec.ExprPrefixResolver.resolve(px(source), prefix,
                prefix);
        return NativeExprEvaluator.evaluate(specialised, ctx(t, prefix));
    }


    private static Expr px(String source)
    {
        return net.cumba.corej.core.expr.CheckExpressionParser.parse(source);
    }


    @Test
    void notUniqueRelationshipWithDomainPrefix()
    {
        // The FDA-SD9731 shape (`not is_unique_relationship(--LLT, --LLTCD)`) — a `--`-prefixed
        // operand pair on this operator; the synthetic instance below uses --TEST / --TESTCD,
        // whose shipped carrier was retired with the CORE family. AETEST "x" maps to both
        // AETESTCD 1 and 2 -> rows {0,1}.
        IDataTable t = MockTable.of().col("AETEST", "x", "x", "y").col("AETESTCD", "1", "2", "3")
                .build();
        assertEquals(bits(0, 1),
                assertPrefixedParity("not is_unique_relationship(--TEST, --TESTCD)", t, "AE"));
    }


    @Test
    void uniqueSetWithDomainPrefixedNameAndKeys()
    {
        // --SEQ is_not_unique_set [USUBJID]: (1, S1) duplicated -> rows {0,1}.
        IDataTable t = MockTable.of().col("AESEQ", "1", "1", "2").col("USUBJID", "S1", "S1", "S1")
                .build();
        assertEquals(bits(0, 1),
                assertPrefixedParity("not is_unique_set([--SEQ, USUBJID])", t, "AE"));
    }


    @Test
    void inconsistentAcrossDatasetWithDomainPrefixedKeys()
    {
        // CDISC-CG0573 shape (its shipped keys add VISITNUM / --TPTREF; reduced to one key
        // here): --TPT is_inconsistent_across_dataset [--TPTNUM]: TPTNUM 1 carries
        // two distinct TPT values -> all 1-keyed rows {0,1}.
        IDataTable t = MockTable.of().col("AETPT", "A", "B", "C").col("AETPTNUM", "1", "1", "2")
                .build();
        assertEquals(bits(0, 1), assertPrefixedParity(
                "is_inconsistent_across_dataset(--TPT, keys=[--TPTNUM])", t, "AE"));
    }


    @Test
    void targetIsNotSortedByWithDomainPrefixedDescriptors()
    {
        // CDISC-CG0662 shape: --SEQ target_is_not_sorted_by [{name: --STDTC, asc}] within USUBJID.
        // Within S1, SEQ ordered by STDTC is 2,1 -> not ascending -> both rows fire.
        IDataTable t = MockTable.of().col("AESEQ", "2", "1", "1")
                .col("AESTDTC", "2024-01-01", "2024-02-01", "2024-01-01")
                .col("USUBJID", "S1", "S1", "S2").build();
        assertPrefixedParity("not is_sorted_by(--SEQ, by=[asc(\"--STDTC\")], within=USUBJID)", t,
                "AE");
    }


    @Test
    void oneTemplateServesEveryDomainThroughSpecialisation()
    {
        // D77: the SAME raised template specialises per domain — one authored rule, one concrete
        // program per domain — and the raw template reaching the evaluator is an error (D77b),
        // never an in-closure substitution (the pre-D77 contract this test used to pin).
        Expr raw = px("not is_unique_relationship(--TEST, --TESTCD)");
        IDataTable ae = MockTable.of().col("AETEST", "x", "x").col("AETESTCD", "1", "2").build();
        IDataTable lb = MockTable.of().col("LBTEST", "x", "y").col("LBTESTCD", "1", "1").build();
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(
                        net.cumba.corej.core.exec.ExprPrefixResolver.resolve(raw, "AE", "AE"),
                        ctx(ae, "AE")),
                "AE: x maps to 1 and 2");
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(
                        net.cumba.corej.core.exec.ExprPrefixResolver.resolve(raw, "LB", "LB"),
                        ctx(lb, "LB")),
                "LB: 1 maps back to x and y (reverse direction)");
        org.junit.jupiter.api.Assertions.assertThrows(
                net.cumba.corej.core.expr.ExpressionException.class,
                () -> NativeExprEvaluator.evaluate(raw, ctx(ae, "AE")),
                "the raw template must not evaluate (D77b)");
    }


    @Test
    void notContainsAllOperationListsMatchLegacy()
    {
        // FDA-SD0056 shape, `not contains_all($dataset_variables, $required_variables)` (both
        // $-operation lists; broadcast verdict).
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$dataset_variables", List.of("STUDYID", "USUBJID"),
                        "$required_all_present", List.of("STUDYID"), "$required_missing",
                        List.of("STUDYID", "TSPARMCD")))
                .build();
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(
                        px("not contains_all($dataset_variables, $required_all_present)"), c),
                "all required present → no violation");
        assertEquals(bits(0, 1),
                NativeExprEvaluator
                        .evaluate(px("not contains_all($dataset_variables, $required_missing)"), c),
                "a required value missing → all rows flagged");
        // ABSENT $-source name → the CheckEvaluator.evaluateLeaf guard short-circuits to NO
        // violation before the operator runs (NOT the empty-set→flag-all operator contract).
        assertTrue(NativeExprEvaluator
                .evaluate(px("not contains_all($no_such_var, $required_all_present)"), c).isEmpty(),
                "absent $-source name → leaf guard → no violation");
        // PRESENT but non-collection $-source → EMPTY distinct set (legacy
        // collectDistinctSourceValues contract) → any non-empty requirement flags all rows.
        EvaluationContext scalarCtx = EvaluationContext.builder().table(t).variables(Map
                .of("$scalar_source", "JUST-A-STRING", "$required_all_present", List.of("STUDYID")))
                .build();
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(
                        px("not contains_all($scalar_source, $required_all_present)"), scalarCtx),
                "non-collection $-source → empty set → flagged");
        // absent $-required → empty requirement → trivially contained → no violation
        assertTrue(NativeExprEvaluator
                .evaluate(px("not contains_all($dataset_variables, $no_such_var)"), c).isEmpty(),
                "absent $-required → no violation");
    }

}
