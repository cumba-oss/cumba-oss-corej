package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.cumba.cdisc.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * {@link PresenceSpelling#canonical} — the writer-side invariant that replaced the assembler's
 * lower/raise dance (phase 1 of {@code PLAN-leaf-scope-domain-inference.md}): a negative presence
 * call is rewritten to {@code not <positive>}, and every other node is rebuilt unchanged.
 */
class PresenceSpellingTest
{

    private static String canon(String source)
    {
        return ExpressionPrinter
                .print(PresenceSpelling.canonical(CheckExpressionParser.parse(source)));
    }


    @Test
    void theTwoNegativeSpellingsBecomeNotOfThePositive()
    {
        assertEquals(canon("not ds_exists(\"TV\")"), canon("ds_not_exists(\"TV\")"));
        assertEquals(canon("not var_exists(\"AESEQ\")"), canon("var_not_exists(\"AESEQ\")"));
    }


    @Test
    void theRewriteReachesEveryNodeKind()
    {
        // and / or / not / binary / list literal / kwargs all carry the rewrite through.
        String in = "(ds_not_exists(\"TV\") and var_not_exists(\"AESEQ\")) or not"
                + " var_not_exists(\"VISIT\") or max_date(AESTDTC, domain=\"AE\") > date(RFSTDTC)"
                + " or AESEV in [\"MILD\", \"MODERATE\"]";
        String expected = "(not ds_exists(\"TV\") and not var_exists(\"AESEQ\")) or not not"
                + " var_exists(\"VISIT\") or max_date(AESTDTC, domain=\"AE\") > date(RFSTDTC)"
                + " or AESEV in [\"MILD\", \"MODERATE\"]";
        assertEquals(canon(expected), canon(in));
        assertEquals(ExpressionPrinter.print(CheckExpressionParser.parse(expected)), canon(in));
    }


    @Test
    void aTreeWithoutANegativeSpellingIsRebuiltIdentically()
    {
        String src = "ds_exists(\"TV\") and empty(AESEQ) and AESEV == \"MILD\" and $x in [1, 2]";
        assertEquals(ExpressionPrinter.print(CheckExpressionParser.parse(src)), canon(src));
        Expr ref = new Expr.Ref("AESEQ", OperandKind.COLUMN);
        assertSame(ref, PresenceSpelling.canonical(ref), "a leaf reference is returned as is");
    }
}
