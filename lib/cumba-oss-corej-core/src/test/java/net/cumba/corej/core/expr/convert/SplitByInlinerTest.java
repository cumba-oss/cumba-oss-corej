package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.OperandClassifier;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link SplitByInliner} — the shared mapping that lowers a {@code split_by}
 * operation into the per-row {@code split_by(<col>, "<delim>")} value function and rewrites every
 * {@code $}-id reference to it.
 */
class SplitByInlinerTest
{

    private static Operation splitBy(String id, String name, String delim)
    {
        Operation op = new Operation();
        op.setOperator("split_by");
        op.setId(id);
        op.setName(name);
        op.setDelimiter(delim);
        return op;
    }


    private static Expr.Ref ref(String name)
    {
        return new Expr.Ref(name, OperandClassifier.classify(name, -1));
    }


    @Test
    void candidateCalls_buildsCallPerCompleteSplitByOp()
    {
        Map<String, Expr> cands = SplitByInliner
                .candidateCalls(List.of(splitBy("$tok", "--SPEC", "/")));
        assertEquals(1, cands.size());
        Expr call = cands.get("$tok");
        assertInstanceOf(Expr.Call.class, call);
        Expr.Call c = (Expr.Call) call;
        assertEquals("split_by", c.name());
        assertEquals(2, c.args().size());
        assertInstanceOf(Expr.Ref.class, c.args().get(0));
        assertEquals("--SPEC", ((Expr.Ref) c.args().get(0)).name());
        assertEquals("/", ((Expr.Lit) c.args().get(1)).value());
    }


    @Test
    void candidateCalls_skipsNullOpsAndIncompleteOrNonSplitOps()
    {
        assertTrue(SplitByInliner.candidateCalls(null).isEmpty(), "null op list ⇒ empty");
        Operation noDelim = splitBy("$a", "--SPEC", null);
        Operation noName = splitBy("$b", null, "/");
        Operation noId = splitBy(null, "--SPEC", "/");
        Operation notSplit = splitBy("$c", "--SPEC", "/");
        notSplit.setOperator("codelist_terms");
        assertTrue(
                SplitByInliner.candidateCalls(List.of(noDelim, noName, noId, notSplit)).isEmpty(),
                "operations missing id/name/delimiter or of another operator are skipped");
    }


    @Test
    void referenced_keepsOnlyReferencedIdsAndDropsDeadOnes()
    {
        Map<String, Expr> cands = SplitByInliner.candidateCalls(
                List.of(splitBy("$tok", "--SPEC", "/"), splitBy("$dead", "X", ",")));
        // An expression that references only $tok.
        Expr expr = new Expr.Call("not_contains_all", List.of(ref("$terms"), ref("$tok")),
                Map.of());
        Map<String, Expr> refd = SplitByInliner.referenced(List.of(expr), cands);
        assertEquals(Map.of("$tok", cands.get("$tok")).keySet(), refd.keySet(),
                "$dead is never referenced ⇒ left out");
    }


    @Test
    void referenced_emptyCandidatesShortCircuits()
    {
        assertTrue(SplitByInliner.referenced(List.of(ref("$tok")), Map.of()).isEmpty());
    }


    @Test
    void rewrite_replacesRefsAcrossEveryNodeTypeAndLeavesLitsAlone()
    {
        Map<String, Expr> cands = SplitByInliner
                .candidateCalls(List.of(splitBy("$tok", "--SPEC", "/")));
        Expr call = cands.get("$tok");

        // A tree exercising Binary, And, Or, Not, Call (args + kwargs) and a bare Lit default arm.
        Expr lit = new Expr.Lit(Expr.LitKind.STRING, "keep");
        Expr binary = new Expr.Binary(Expr.BinOp.EQ, ref("$tok"), lit);
        Expr not = new Expr.Not(ref("$tok"));
        Expr callWithKwargs = new Expr.Call("f", List.of(ref("$tok")), Map.of("k", ref("$tok")));
        Expr or = new Expr.Or(List.of(ref("$tok"), lit));
        Expr tree = new Expr.And(List.of(binary, not, callWithKwargs, or));

        Expr.And out = (Expr.And) SplitByInliner.rewrite(tree, cands);
        Expr.Binary outBin = (Expr.Binary) out.parts().get(0);
        assertSame(call, outBin.left(), "Binary left $tok ⇒ split_by call");
        assertSame(lit, outBin.right(), "Lit untouched");
        assertSame(call, ((Expr.Not) out.parts().get(1)).inner(), "Not inner rewritten");
        Expr.Call outCall = (Expr.Call) out.parts().get(2);
        assertSame(call, outCall.args().get(0), "Call arg rewritten");
        assertSame(call, outCall.kwargs().get("k"), "Call kwarg rewritten");
        Expr.Or outOr = (Expr.Or) out.parts().get(3);
        assertSame(call, outOr.parts().get(0), "Or part rewritten");
        assertSame(lit, outOr.parts().get(1), "Or Lit untouched");
    }


    @Test
    void rewrite_emptyMapReturnsExpressionUnchanged()
    {
        Expr r = ref("$tok");
        assertSame(r, SplitByInliner.rewrite(r, Map.of()), "empty call map ⇒ identity");
    }
}
