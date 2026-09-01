package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Native-compiler tests for the T9 delimiter-split-then-per-token membership capability: the
 * {@code split_by(<col>, "<delim>")} value function producing a per-row token list, consumed by the
 * per-row {@code not_contains_all} token verdict ({@code $codelist not_contains_all
 * split_by(--VAR, "/")}). The row fires when ANY delimited token is not a member of the codelist
 * set — a single valid token passes, one invalid token fires — mirroring the Python reference
 * engine's {@code not_contains_all} over two columns of iterables (SEND56/57/282). Also checks
 * delimiter handling (literal, not regex; trailing empties kept, pandas-parity) and that a
 * plain-column split compiles natively (NativeCoverageTest contract).
 */
class NativeSplitByTest
{

    private static final Set<String> SPEC = Set.of("LIVER", "KIDNEY", "SPLEEN");

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext ctx(IDataTable t, Map<String, Object> variables)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).variables(variables)
                .build();
    }


    private static BitSet evalSpec(String specValue, String expr)
    {
        IDataTable t = MockTable.of().col("SPEC", specValue).build();
        return NativeExprEvaluator.evaluate(parse(expr), ctx(t, Map.of("$spec_terms", SPEC)));
    }

    // ---- per-token membership verdict --------------------------------------


    @Test
    void everyTokenValidDoesNotFire()
    {
        assertTrue(evalSpec("LIVER/KIDNEY", "not contains_all($spec_terms, split_by(SPEC, \"/\"))")
                .isEmpty(), "all slash tokens are valid SPEC values → no violation");
    }


    @Test
    void singleValidValueDoesNotFire()
    {
        assertTrue(
                evalSpec("LIVER", "not contains_all($spec_terms, split_by(SPEC, \"/\"))").isEmpty(),
                "a single valid value (no delimiter) → one token, valid → no violation");
    }


    @Test
    void anyInvalidTokenFires()
    {
        assertFalse(evalSpec("LIVER/FOO", "not contains_all($spec_terms, split_by(SPEC, \"/\"))")
                .isEmpty(), "one invalid slash token → violation (not just all-invalid)");
    }


    @Test
    void singleInvalidValueFires()
    {
        assertFalse(
                evalSpec("BONE", "not contains_all($spec_terms, split_by(SPEC, \"/\"))").isEmpty(),
                "a single invalid value → violation");
    }

    // ---- delimiter handling ------------------------------------------------


    @Test
    void delimiterIsLiteralNotRegex()
    {
        // A "/" split must not fire on a ";"-joined value's slash-free content, and vice-versa: the
        // delimiter is matched literally.
        assertTrue(
                evalSpec("LIVER;KIDNEY", "not contains_all($spec_terms, split_by(SPEC, \";\"))")
                        .isEmpty(),
                "semicolon delimiter splits the semicolon-joined value into valid tokens");
        assertFalse(
                evalSpec("LIVER;KIDNEY", "not contains_all($spec_terms, split_by(SPEC, \"/\"))")
                        .isEmpty(),
                "splitting the semicolon-joined value on \"/\" yields one invalid token → fires");
    }


    @Test
    void trailingEmptyTokenKeptAndFires()
    {
        // pandas Series.str.split keeps a trailing empty token ("LIVER/" -> ["LIVER", ""]); the
        // empty token is not a codelist member, so the row fires — the native split matches.
        assertFalse(
                evalSpec("LIVER/", "not contains_all($spec_terms, split_by(SPEC, \"/\"))")
                        .isEmpty(),
                "trailing empty token is kept and is not a member → violation (pandas parity)");
    }


    @Test
    void authoredRuleShapeGuardsBlanks()
    {
        // The authored SEND56/282 shape: non_empty guard AND the token check. A blank cell is
        // guarded out (no false positive), a populated invalid combo fires.
        String shape = "not empty(SPEC) and not contains_all($spec_terms, split_by(SPEC, \"/\"))";
        assertTrue(evalSpec("", shape).isEmpty(), "blank SPEC is guarded → no violation");
        assertTrue(evalSpec("LIVER/KIDNEY", shape).isEmpty(),
                "populated, all valid → no violation");
        assertFalse(evalSpec("LIVER/FOO", shape).isEmpty(), "populated, invalid token → violation");
    }

    // ---- per-row (not broadcast) -------------------------------------------


    @Test
    void verdictIsPerRow()
    {
        // Two rows: row 0 all-valid, row 1 has an invalid token. Only row 1 fires (per-row, not a
        // dataset-level broadcast) — the CoreIssue890 semantics.
        IDataTable t = MockTable.of().col("SPEC", "LIVER/KIDNEY", "LIVER/FOO").build();
        BitSet fired = NativeExprEvaluator.evaluate(
                parse("not contains_all($spec_terms, split_by(SPEC, \"/\"))"),
                ctx(t, Map.of("$spec_terms", SPEC)));
        BitSet expected = new BitSet();
        expected.set(1);
        assertEquals(expected, fired, "only the row with an invalid token fires");
    }

    // ---- native support ----------------------------------------------------


    @Test
    void plainColumnSplitCompilesNatively()
    {
        assertTrue(
                NativeExprEvaluator
                        .isSupported(parse("not contains_all($spec_terms, split_by(SPEC, \"/\"))")),
                "a plain-column split-then-membership compiles natively (NativeCoverageTest)");
    }


    @Test
    void splitByProducesTokenList()
    {
        // Direct check that split_by yields a per-row List consumed element-wise: an empty
        // delimiter yields the single-element list [value] (no split), so a valid whole value
        // passes and an invalid one fires.
        assertTrue(
                evalSpec("LIVER", "not contains_all($spec_terms, split_by(SPEC, \"\"))").isEmpty(),
                "empty delimiter → single token = whole value; valid → no violation");
        assertFalse(
                evalSpec("LIVER/KIDNEY", "not contains_all($spec_terms, split_by(SPEC, \"\"))")
                        .isEmpty(),
                "empty delimiter → the whole slash-joined value is one non-member token → fires");
    }
}
