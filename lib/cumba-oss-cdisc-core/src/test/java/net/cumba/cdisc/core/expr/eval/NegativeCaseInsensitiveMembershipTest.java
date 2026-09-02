package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.ast.Expr.BinOp;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins the Phase-1 engine change of {@code PLAN-regex-rule-optimization}: the
 * {@code ExprCompiler.compileMembership} guard that declined {@code upper(X) not in […]} (the
 * lowered {@code is_not_contained_by_case_insensitive} surface) is removed, so the negated
 * case-insensitive membership now evaluates natively via
 * {@code Primitives.membership(…, negate=true, caseInsensitive=true)}. The previously unimplemented
 * legacy operator was a silent no-op; the native path is the corrected behaviour.
 */
class NegativeCaseInsensitiveMembershipTest
{

    private static Expr ref(String n)
    {
        return new Expr.Ref(n, OperandKind.COLUMN);
    }


    private static Expr strList(String... values)
    {
        Expr[] lits = new Expr[values.length];
        for (int i = 0; i < values.length; i++)
        {
            lits[i] = new Expr.Lit(Expr.LitKind.STRING, values[i]);
        }
        return new Expr.Lit(Expr.LitKind.LIST, List.of(lits));
    }


    private static Expr upper(Expr inner)
    {
        return new Expr.Call("upper", List.of(inner), Map.of());
    }


    private static EvaluationContext ctx(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /**
     * {@code upper(X) not in [list]} fires every row whose (case-folded) value is NOT in the list.
     * Members are stored case-insensitively, so "unk" (folds to "UNK") matches the list member
     * "UNK" and does NOT fire, while "garbage" is absent and fires.
     */
    @Test
    void negatedCaseInsensitiveMembershipFiresAbsentRows()
    {
        IDataTable t = MockTable.of().col("X", "UNK", "unk", "garbage", "Unknown").build();
        Expr e = new Expr.Binary(BinOp.NOT_IN, upper(ref("X")), strList("UNK", "Unknown", "Other"));
        // row 0 "UNK" -> in list (no fire); row 1 "unk" -> folds to UNK, in list (no fire);
        // row 2 "garbage" -> absent (fire); row 3 "Unknown" -> folds to UNKNOWN, in list (no fire).
        assertEquals(bits(2), NativeExprEvaluator.evaluate(e, ctx(t)),
                "negated CI membership fires only the row whose value is not in the list");
    }


    /**
     * The positive {@code upper(X) in [list]} surface stays intact (the complement of the negated
     * form on present/absent rows): only rows whose folded value IS in the list fire.
     */
    @Test
    void positiveCaseInsensitiveMembershipUnchanged()
    {
        IDataTable t = MockTable.of().col("X", "UNK", "unk", "garbage", "Unknown").build();
        Expr e = new Expr.Binary(BinOp.IN, upper(ref("X")), strList("UNK", "Unknown", "Other"));
        assertEquals(bits(0, 1, 3), NativeExprEvaluator.evaluate(e, ctx(t)),
                "positive CI membership fires only the rows whose value is in the list");
    }


    /**
     * At the bare membership level a missing cell folds to {@code ""}, which is not in the list, so
     * the negated form fires it (the "empty-string literal" contract in
     * {@code Primitives.membership}). In the real CORE-000041 rule the row is excluded by the
     * separate {@code not empty(TSVAL)} AND-clause, not by this leaf — so this leaf must fire the
     * blank in isolation, mirroring the legacy operator's per-leaf behaviour.
     */
    @Test
    void missingCellFiresAtLeafLevel()
    {
        IDataTable t = MockTable.of().col("X", "garbage", "", "UNK").build();
        Expr e = new Expr.Binary(BinOp.NOT_IN, upper(ref("X")), strList("UNK", "Unknown"));
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(e, ctx(t)),
                "a blank cell folds to \"\" (absent from the list) and fires negated membership");
    }
}
