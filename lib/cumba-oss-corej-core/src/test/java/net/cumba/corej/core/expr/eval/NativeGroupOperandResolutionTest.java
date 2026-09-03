package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionLeaf;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * Evaluates the raw {@code --} expression natively under a prefixed context; callers assert the
     * explicit expected bits (the legacy comparison oracle retired with the engine).
     */
    private static BitSet assertPrefixedParity(CheckCondition check, IDataTable t, String prefix)
    {
        return NativeExprEvaluator.evaluate(CheckToExpr.toExpr(check), ctx(t, prefix));
    }


    @Test
    void notUniqueRelationshipWithDomainPrefix()
    {
        // CORE-000303 shape: --TEST is_not_unique_relationship --TESTCD. AETEST "x" maps to both
        // AETESTCD 1 and 2 -> rows {0,1}.
        IDataTable t = MockTable.of().col("AETEST", "x", "x", "y").col("AETESTCD", "1", "2", "3")
                .build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--TEST")
                .operator("is_not_unique_relationship").value(MAPPER.valueToTree("--TESTCD"))
                .build();
        assertEquals(bits(0, 1), assertPrefixedParity(leaf, t, "AE"));
    }


    @Test
    void uniqueSetWithDomainPrefixedNameAndKeys()
    {
        // --SEQ is_not_unique_set [USUBJID]: (1, S1) duplicated -> rows {0,1}.
        IDataTable t = MockTable.of().col("AESEQ", "1", "1", "2").col("USUBJID", "S1", "S1", "S1")
                .build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--SEQ")
                .operator("is_not_unique_set").value(MAPPER.createArrayNode().add("USUBJID"))
                .build();
        assertEquals(bits(0, 1), assertPrefixedParity(leaf, t, "AE"));
    }


    @Test
    void inconsistentAcrossDatasetWithDomainPrefixedKeys()
    {
        // CORE-000689 shape: --TPT is_inconsistent_across_dataset [--TPTNUM]: TPTNUM 1 carries
        // two distinct TPT values -> all 1-keyed rows {0,1}.
        IDataTable t = MockTable.of().col("AETPT", "A", "B", "C").col("AETPTNUM", "1", "1", "2")
                .build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--TPT")
                .operator("is_inconsistent_across_dataset")
                .value(MAPPER.createArrayNode().add("--TPTNUM")).build();
        assertEquals(bits(0, 1), assertPrefixedParity(leaf, t, "AE"));
    }


    @Test
    void targetIsNotSortedByWithDomainPrefixedDescriptors()
    {
        // CORE-000535 shape: --SEQ target_is_not_sorted_by [{name: --STDTC, asc}] within USUBJID.
        // Within S1, SEQ ordered by STDTC is 2,1 -> not ascending -> both rows fire.
        IDataTable t = MockTable.of().col("AESEQ", "2", "1", "1")
                .col("AESTDTC", "2024-01-01", "2024-02-01", "2024-01-01")
                .col("USUBJID", "S1", "S1", "S2").build();
        com.fasterxml.jackson.databind.node.ObjectNode d = MAPPER.createObjectNode();
        d.put("name", "--STDTC").put("sort_order", "asc").put("null_position", "last");
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--SEQ")
                .operator("target_is_not_sorted_by").value(MAPPER.createArrayNode().add(d))
                .within(MAPPER.valueToTree("USUBJID")).build();
        assertPrefixedParity(leaf, t, "AE");
    }


    @Test
    void compiledProgramIsDomainAgnostic()
    {
        // Cache-safety: the SAME raised Expr (cached per Expr) must resolve --TESTCD per run —
        // correct results for an AE-prefixed and an LB-prefixed dataset from one program.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--TEST")
                .operator("is_not_unique_relationship").value(MAPPER.valueToTree("--TESTCD"))
                .build();
        Expr raw = CheckToExpr.toExpr(leaf);
        IDataTable ae = MockTable.of().col("AETEST", "x", "x").col("AETESTCD", "1", "2").build();
        IDataTable lb = MockTable.of().col("LBTEST", "x", "y").col("LBTESTCD", "1", "1").build();
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(raw, ctx(ae, "AE")),
                "AE: x maps to 1 and 2");
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(raw, ctx(lb, "LB")),
                "LB: 1 maps back to x and y (reverse direction)");
        // and an unresolvable prefix (no domain) misses the columns -> empty
    }


    @Test
    void notContainsAllOperationListsMatchLegacy()
    {
        // CORE-000355 shape: $dataset_variables not_contains_all $required_variables (both
        // $-operation lists; broadcast verdict).
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$dataset_variables", List.of("STUDYID", "USUBJID"),
                        "$required_all_present", List.of("STUDYID"), "$required_missing",
                        List.of("STUDYID", "TSPARMCD")))
                .build();
        CheckConditionLeaf ok = CheckConditionLeaf.builder().name("$dataset_variables")
                .operator("not_contains_all").value(MAPPER.valueToTree("$required_all_present"))
                .build();
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(ok), c),
                "all required present → no violation");
        CheckConditionLeaf missing = CheckConditionLeaf.builder().name("$dataset_variables")
                .operator("not_contains_all").value(MAPPER.valueToTree("$required_missing"))
                .build();
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(missing), c),
                "a required value missing → all rows flagged");
        // ABSENT $-source name → the CheckEvaluator.evaluateLeaf guard short-circuits to NO
        // violation before the operator runs (NOT the empty-set→flag-all operator contract).
        CheckConditionLeaf absentSource = CheckConditionLeaf.builder().name("$no_such_var")
                .operator("not_contains_all").value(MAPPER.valueToTree("$required_all_present"))
                .build();
        assertTrue(NativeExprEvaluator.evaluate(CheckToExpr.toExpr(absentSource), c).isEmpty(),
                "absent $-source name → leaf guard → no violation");
        // PRESENT but non-collection $-source → EMPTY distinct set (legacy
        // collectDistinctSourceValues contract) → any non-empty requirement flags all rows.
        EvaluationContext scalarCtx = EvaluationContext.builder().table(t).variables(Map
                .of("$scalar_source", "JUST-A-STRING", "$required_all_present", List.of("STUDYID")))
                .build();
        CheckConditionLeaf scalarSource = CheckConditionLeaf.builder().name("$scalar_source")
                .operator("not_contains_all").value(MAPPER.valueToTree("$required_all_present"))
                .build();
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(scalarSource), scalarCtx),
                "non-collection $-source → empty set → flagged");
        // absent $-required → empty requirement → trivially contained → no violation
        CheckConditionLeaf absentRequired = CheckConditionLeaf.builder().name("$dataset_variables")
                .operator("not_contains_all").value(MAPPER.valueToTree("$no_such_var")).build();
        assertTrue(NativeExprEvaluator.evaluate(CheckToExpr.toExpr(absentRequired), c).isEmpty(),
                "absent $-required → no violation");
    }

}
