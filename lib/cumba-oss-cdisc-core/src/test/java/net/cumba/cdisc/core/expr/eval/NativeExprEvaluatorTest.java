package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.RuleDefinitionException;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.ast.Expr.BinOp;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NativeExprEvaluatorTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Expr ref(String n)
    {
        return new Expr.Ref(n, OperandKind.COLUMN);
    }


    private static Expr s(String v)
    {
        return new Expr.Lit(Expr.LitKind.STRING, v);
    }


    private static Expr num(double d)
    {
        return new Expr.Lit(Expr.LitKind.NUMBER, d);
    }


    private static Expr call(String n, Expr... a)
    {
        return new Expr.Call(n, List.of(a), Map.of());
    }


    private static Expr opref(String n)
    {
        return new Expr.Ref(n, OperandKind.OPERATION_REF);
    }


    private static Expr bin(BinOp op, Expr l, Expr r)
    {
        return new Expr.Binary(op, l, r);
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


    @Test
    void c1FunctionsCompileAndEvaluate()
    {
        // C1 drop-ins compile in expression position: abs(value fn) under a comparison, between
        // (boolean fn) as a top-level call.
        IDataTable t = MockTable.of().col("X", "-5", "2", "10").build();
        assertEquals(bits(0, 2),
                NativeExprEvaluator.evaluate(bin(BinOp.GT, call("abs", ref("X")), num(3)), ctx(t)),
                "abs(X) > 3");
        assertEquals(bits(1, 2),
                NativeExprEvaluator.evaluate(call("between", ref("X"), num(2), num(10)), ctx(t)),
                "2 <= X <= 10");
    }


    @Test
    void substringValueFunctionCompilesAndEvaluates()
    {
        // C1 batch 2: substring(X, 2, 3) under an == comparison (a VALUE function in operand
        // position). 1-based start: "ABCDE" -> "BCD"; "XY" -> start 2 length 3 clamped -> "Y".
        IDataTable t = MockTable.of().col("X", "ABCDE", "QBCDZ", "XY").build();
        Expr e = bin(BinOp.EQ, call("substring", ref("X"), num(2), num(3)), s("BCD"));
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(e, ctx(t)),
                "substring(X,2,3) == BCD");
    }


    @Test
    void imatchesBooleanFunctionCompilesAndEvaluates()
    {
        // C1 batch 2: imatches(X, /ell/) is a top-level case-insensitive regex search; the 2nd arg
        // is a /regex/ literal bound as a broadcast const (LITERAL_ARG1 path).
        IDataTable t = MockTable.of().col("X", "Hello", "WORLD", "yellow").build();
        Expr e = call("imatches", ref("X"), new Expr.Lit(Expr.LitKind.REGEX, "ell"));
        assertEquals(bits(0, 2), NativeExprEvaluator.evaluate(e, ctx(t)),
                "imatches(X, /ell/) case-insensitive find");
    }


    @Test
    void regexFunctionsRejectNonRegexPattern()
    {
        // Phase 2a: imatches / prefix_matches / suffix_matches require a /regex/ literal pattern.
        // A "string" literal (or any non-REGEX shape) is a definitional error at compile time.
        IDataTable t = MockTable.of().col("X", "Hello").build();
        for (String fn : List.of("imatches", "prefix_matches", "suffix_matches"))
        {
            // the /regex/ form compiles fine
            Expr ok = call(fn, ref("X"), new Expr.Lit(Expr.LitKind.REGEX, "ell"));
            NativeExprEvaluator.evaluate(ok, ctx(t));
            // a "string" pattern is rejected
            Expr badString = call(fn, ref("X"), s("ell"));
            RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                    () -> NativeExprEvaluator.evaluate(badString, ctx(t)),
                    fn + "(X, \"ell\") must be a definitional error");
            assertTrue(ex.getMessage().contains("/regex/"),
                    "message should mention /regex/: " + ex.getMessage());
            // a column pattern is rejected too
            Expr badCol = call(fn, ref("X"), ref("X"));
            assertThrows(RuleDefinitionException.class,
                    () -> NativeExprEvaluator.evaluate(badCol, ctx(t)),
                    fn + "(X, COL) must be a definitional error");
        }
    }


    @Test
    void regexOperatorRejectsNonRegexPattern()
    {
        // Phase 2c: the =~ / !~ grammar operator is /regex/-only. X =~ /ell/ works; X =~ "ell"
        // (a STRING literal) is a definitional error.
        IDataTable t = MockTable.of().col("X", "Hello", "WORLD").build();
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.MATCH, ref("X"), new Expr.Lit(Expr.LitKind.REGEX, "ell")),
                        ctx(t)),
                "X =~ /ell/");
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(bin(BinOp.MATCH, ref("X"), s("ell")), ctx(t)),
                "X =~ \"ell\" must be a definitional error");
        assertTrue(ex.getMessage().contains("/regex/"),
                "message should mention /regex/: " + ex.getMessage());
        // !~ counterpart is rejected too
        assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(bin(BinOp.NMATCH, ref("X"), s("ell")), ctx(t)),
                "X !~ \"ell\" must be a definitional error");
    }


    @Test
    void andShortCircuitsWhenIntersectionEmpties()
    {
        // E2: a probe boolean function counts its evaluations. In `SEX==ZZZ && probe(SEX)` the
        // first
        // conjunct matches no row (empty), so the running intersection is empty and the probe is
        // skipped. In `SEX==M && probe(SEX)` the first conjunct is non-empty, so the probe runs.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FunctionRegistry
                .register(new FunctionDescriptor("__probe_and__", 1, FunctionKind.BOOLEAN, (_, _) ->
                {
                    calls.incrementAndGet();
                    return new BitSet();
                }));
        try
        {
            IDataTable t = MockTable.of().col("SEX", "M", "F", "M").build();
            Expr probe = call("__probe_and__", ref("SEX"));

            calls.set(0);
            NativeExprEvaluator.evaluate(
                    new Expr.And(List.of(bin(BinOp.EQ, ref("SEX"), s("ZZZ")), probe)), ctx(t));
            assertEquals(0, calls.get(), "empty first conjunct short-circuits the probe");

            calls.set(0);
            NativeExprEvaluator.evaluate(
                    new Expr.And(List.of(bin(BinOp.EQ, ref("SEX"), s("M")), probe)), ctx(t));
            assertEquals(1, calls.get(), "non-empty first conjunct still evaluates the probe");
        }
        finally
        {
            FunctionRegistry.unregister("__probe_and__", 1);
        }
    }


    @Test
    void orShortCircuitsWhenUnionFills()
    {
        // E2: in `SEX!=ZZZ || probe(SEX)` the first disjunct matches every row (none are ZZZ), so
        // the
        // running union is full and the probe is skipped.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FunctionRegistry
                .register(new FunctionDescriptor("__probe_or__", 1, FunctionKind.BOOLEAN, (_, _) ->
                {
                    calls.incrementAndGet();
                    return new BitSet();
                }));
        try
        {
            IDataTable t = MockTable.of().col("SEX", "M", "F", "M").build();
            Expr probe = call("__probe_or__", ref("SEX"));
            NativeExprEvaluator.evaluate(
                    new Expr.Or(List.of(bin(BinOp.NEQ, ref("SEX"), s("ZZZ")), probe)), ctx(t));
            assertEquals(0, calls.get(), "full first disjunct short-circuits the probe");
        }
        finally
        {
            FunctionRegistry.unregister("__probe_or__", 1);
        }
    }


    @Test
    void domainPrefixWildcardResolvesNatively()
    {
        // B1: `--STAT` is a domain-prefix wildcard; native resolves it against the context's domain
        // prefix (AE -> AESTAT) at eval time, so the program stays domain-agnostic. The expression
        // is natively supported (no longer declined) and fires on the matching rows.
        Expr wildcard = new Expr.Ref("--STAT", OperandKind.WILDCARD_COLUMN);
        Expr e = bin(BinOp.EQ, wildcard, s("NOT DONE"));
        assertTrue(NativeExprEvaluator.isSupported(e), "--STAT comparison is natively supported");

        IDataTable t = MockTable.of().col("AESTAT", "NOT DONE", "", "NOT DONE").build();
        EvaluationContext c = EvaluationContext.builder().table(t).domainPrefix("AE").build();
        assertEquals(bits(0, 2), NativeExprEvaluator.evaluate(e, c),
                "--STAT resolves to AESTAT and matches the populated rows");

        // With no/!=2-char domain prefix the raw name stays unresolved -> column missing -> no
        // rows.
        EvaluationContext noPrefix = EvaluationContext.builder().table(t).build();
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(e, noPrefix),
                "absent domain prefix leaves --STAT unresolved (missing column => empty)");
    }


    @Test
    void equalityWithLiteralFallback()
    {
        IDataTable t = MockTable.of().col("SEX", "M", "F", "").build();
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("SEX"), s("M")), ctx(t)));
        // not_equal_to fires on the differing row (1) and the one-missing row (2), matching legacy.
        assertEquals(bits(1, 2),
                NativeExprEvaluator.evaluate(bin(BinOp.NEQ, ref("SEX"), s("M")), ctx(t)));
    }


    @Test
    void numericComparison()
    {
        IDataTable t = MockTable.of().colLong("AGE", 30L, 10L, 20L).build();
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGE"), num(20)), ctx(t)));
        assertEquals(bits(0, 2),
                NativeExprEvaluator.evaluate(bin(BinOp.GE, ref("AGE"), num(20)), ctx(t)));
    }


    @Test
    void dateComparison()
    {
        IDataTable t = MockTable.of().col("DTC", "2024-01-02", "2024-01-01", "").build();
        Expr e = bin(BinOp.GT, call("date", ref("DTC")), s("2024-01-01"));
        assertEquals(bits(0), NativeExprEvaluator.evaluate(e, ctx(t)));
    }


    @Test
    void arithmeticNotEqualNativeMatchesLegacy()
    {
        // Phase 4b: X != A / B evaluates natively via ArithmeticSemantics and equals the legacy
        // not_equal_to_divide. row0 2==10/5 ok; row1 4!=10/2 violation; row2 div-by-zero skipped;
        // row3 missing name skipped.
        IDataTable t = MockTable.of().col("R2BASE", "2", "4", "1", "")
                .col("AVAL", "10", "10", "5", "10").col("BASE", "5", "2", "0", "5").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("R2BASE")
                .operator("not_equal_to_divide")
                .value(MAPPER.createArrayNode().add("AVAL").add("BASE")).build();
        EvaluationContext c = ctx(t);
        BitSet nativ = NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), c);
        assertEquals(bits(1), nativ, "native not_equal_to_divide");
    }


    @Test
    void colrefTwoHopNativeMatchesLegacy()
    {
        // CORE-000206: IDVARVAL not_equal_to IDVAR (type-insensitive, value_is_reference two-hop)
        // ->
        // `str(IDVARVAL) != str(colref(IDVAR))`. IDVAR names a column; colref reads it on the same
        // row. AESEQ is the parent-domain column ChildMatchPreMerger pre-merges into the SUPP--
        // table
        // before the EvaluationContext is built; here it is supplied directly. row0 IDVARVAL=1 ==
        // AESEQ=1 (no violation); row1 IDVARVAL=99 != AESEQ=1 (violation).
        IDataTable t = MockTable.of().col("IDVAR", "AESEQ", "AESEQ").col("IDVARVAL", "1", "99")
                .col("AESEQ", "1", "1").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("IDVARVAL")
                .operator("not_equal_to").typeInsensitive(true)
                .value(MAPPER.getNodeFactory().textNode("IDVAR")).valueIsReference(true).build();
        Expr e = CheckToExpr.toExpr(leaf); // str(IDVARVAL) != str(colref(IDVAR))
        assertTrue(NativeExprEvaluator.isSupported(e),
                "str(IDVARVAL) != str(colref(IDVAR)) is natively supported");
        EvaluationContext c = ctx(t);
        BitSet nativ = NativeExprEvaluator.evaluate(e, c);
        assertEquals(bits(1), nativ, "only the IDVARVAL=99 row violates");
    }


    @Test
    void typeInsensitiveEqualityNativeMatchesLegacy()
    {
        // str(A) == str(B): native type-insensitive equality coerces both operands to strings, so
        // the string "1" equals the long 1. Must match the legacy type_insensitive equal_to.
        IDataTable t = MockTable.of().col("A", "1", "2", "3").colLong("B", 1L, 9L, 3L).build();
        CheckConditionLeaf plain = CheckConditionLeaf.builder().name("A").operator("equal_to")
                .typeInsensitive(true).value(MAPPER.getNodeFactory().textNode("B")).build();
        Expr ep = CheckToExpr.toExpr(plain); // str(A) == str(B)
        assertTrue(NativeExprEvaluator.isSupported(ep), "str(A) == str(B) is natively supported");
        assertEquals(bits(0, 2), NativeExprEvaluator.evaluate(ep, ctx(t)),
                "type-insensitive equality: \"1\"==1 and \"3\"==3 fire, \"2\"!=9 does not");
    }


    @Test
    void regexAndMembership()
    {
        IDataTable t = MockTable.of().col("X", "ABC", "xyz", "DEF").build();
        assertEquals(bits(0), NativeExprEvaluator.evaluate(
                bin(BinOp.MATCH, ref("X"), new Expr.Lit(Expr.LitKind.REGEX, "^A")), ctx(t)));
        Expr inList = bin(BinOp.IN, ref("X"),
                new Expr.Lit(Expr.LitKind.LIST, List.of(s("ABC"), s("DEF"))));
        assertEquals(bits(0, 2), NativeExprEvaluator.evaluate(inList, ctx(t)));
    }


    private static Expr list(Expr... items)
    {
        return new Expr.Lit(Expr.LitKind.LIST, List.of(items));
    }


    @Test
    void numericMembershipMatchesAcrossNumericFormats()
    {
        // Phase 9b (D2): an all-numeric list literal runs numerically. A "10.0" / "10" / "010" cell
        // all match the member 10; "15" / blank / "x" do not. Subsumes B1 ("3.0" vs 3).
        IDataTable t = MockTable.of().col("DOSE", "10.0", "10", "010", "20", "15", "", "x").build();
        Expr in = bin(BinOp.IN, ref("DOSE"), list(num(10), num(20), num(30)));
        assertEquals(bits(0, 1, 2, 3), NativeExprEvaluator.evaluate(in, ctx(t)),
                "DOSE in [10,20,30]");
        // not in is the exact polarity inverse — a missing / non-numeric probe is NOT a member, so
        // it fires for not_in (rows 4,5,6).
        Expr notIn = bin(BinOp.NOT_IN, ref("DOSE"), list(num(10), num(20), num(30)));
        assertEquals(bits(4, 5, 6), NativeExprEvaluator.evaluate(notIn, ctx(t)),
                "DOSE not in [10,20,30]");
    }


    @Test
    void stringMembershipStaysTextual()
    {
        // An all-string list literal is unchanged: textual membership, "10" matches "10" only by
        // string coincidence (here the members are non-numeric strings).
        IDataTable t = MockTable.of().col("AESEV", "MILD", "SEVERE", "moderate", "").build();
        Expr in = bin(BinOp.IN, ref("AESEV"), list(s("MILD"), s("SEVERE")));
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(in, ctx(t)));
    }


    @Test
    void mixedMembershipListIsLoadError()
    {
        // A list mixing a number and a string member is a definitional load error on the native
        // compile path.
        IDataTable t = MockTable.of().col("X", "1", "A").build();
        Expr in = bin(BinOp.IN, ref("X"), list(num(1), s("A")));
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(in, ctx(t)));
        assertTrue(ex.getMessage().contains("mixes numeric and string"), ex.getMessage());
    }


    @Test
    void numericMembershipParityWithLegacy()
    {
        // The shipped-corpus shape: an integer-coded numeric column tested against an all-integer
        // list literal (JSON integral nodes). Native and legacy must agree bit-for-bit.
        IDataTable t = MockTable.of().colLong("AESEV", 1L, 2L, 3L, 9L)
                .col("AESEVC", "1", "2.0", "03", "9").build();
        assertParity(CheckConditionLeaf.builder().name("AESEV").operator("is_contained_by")
                .value(MAPPER.valueToTree(List.of(1, 2, 3))).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AESEV").operator("is_not_contained_by")
                .value(MAPPER.valueToTree(List.of(1, 2, 3))).build(), t);
        // Same against a STRING-typed column with formatted-numeric cells ("2.0", "03") — numeric
        // mode parses the probe so they match, and native must equal legacy on every row.
        assertParity(CheckConditionLeaf.builder().name("AESEVC").operator("is_contained_by")
                .value(MAPPER.valueToTree(List.of(1, 2, 3))).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AESEVC").operator("is_not_contained_by")
                .value(MAPPER.valueToTree(List.of(1, 2, 3))).build(), t);
    }


    @Test
    void mixedMembershipListIsLoadErrorOnLegacyToo()
    {
        // The mixed-list load error must fire identically on the legacy engine (lockstep with
        // native). The corpus has zero mixed lists, so this is the defensive parity proof.
        IDataTable t = MockTable.of().col("X", "1", "A").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("is_contained_by")
                .value(MAPPER.valueToTree(List.of(1, "A"))).build();
        Expr expr = CheckToExpr.toExpr(leaf);
        assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(expr, ctx(t)));
    }


    @Test
    void booleanFunctionsAndComposition()
    {
        IDataTable t = MockTable.of().col("X", "HELLO", "", "HELP").build();
        // contains("EL") AND non_empty — both "HELLO" and "HELP" contain "EL"
        Expr e = new Expr.And(
                List.of(call("contains", ref("X"), s("EL")), call("non_empty", ref("X"))));
        assertEquals(bits(0, 2), NativeExprEvaluator.evaluate(e, ctx(t)));
        // len(X) > 3
        assertEquals(bits(0, 2),
                NativeExprEvaluator.evaluate(bin(BinOp.GT, call("len", ref("X")), num(3)), ctx(t)));
    }


    @Test
    void containsAcceptsColumnNeedle()
    {
        // Phase 1 — a bareword arg1 to contains resolves as a per-row column (not a literal),
        // so contains(X, NEEDLE) fires per row where X contains the row's own NEEDLE value.
        IDataTable t = MockTable.of().col("X", "HELLO", "WORLD", "ABCDE")
                .col("NEEDLE", "ELL", "XYZ", "ABC").build();
        assertEquals(bits(0, 2),
                NativeExprEvaluator.evaluate(call("contains", ref("X"), ref("NEEDLE")), ctx(t)));
        // does_not_contain is the polarity inverse of the same per-row needle.
        assertEquals(bits(1), NativeExprEvaluator
                .evaluate(call("does_not_contain", ref("X"), ref("NEEDLE")), ctx(t)));
    }


    @Test
    void lenIsZeroForEmptyAndMissing()
    {
        // function-examples.md "Length": len("")=0, len(«missing»)=0. The length operators
        // longer_than/shorter_than lower to len(x) comparisons, so they evaluate "" literally.
        IDataTable t = MockTable.of().col("X", "AB", "", (String) null).build();

        // len(X) > 0: a blank/missing cell is length 0, NOT > 0, so it no longer fires (and is no
        // longer "skipped"). Only the populated row fires.
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(bin(BinOp.GT, call("len", ref("X")), num(0)), ctx(t)));

        // non_empty(X) and len(X) > 0 — the documented opt-out keeps the same result.
        Expr optOut = new Expr.And(
                List.of(call("non_empty", ref("X")), bin(BinOp.GT, call("len", ref("X")), num(0))));
        assertEquals(bits(0), NativeExprEvaluator.evaluate(optOut, ctx(t)));

        // longer_than 0 (lowers to len(X) > 0): empty/missing do not fire.
        Expr longer = CheckToExpr.toExpr(CheckConditionLeaf.builder().name("X")
                .operator("longer_than").value(MAPPER.valueToTree(0)).build());
        assertEquals(bits(0), NativeExprEvaluator.evaluate(longer, ctx(t)));

        // shorter_than 1 (lowers to len(X) < 1): empty/missing fire (length 0 < 1).
        Expr shorter = CheckToExpr.toExpr(CheckConditionLeaf.builder().name("X")
                .operator("shorter_than").value(MAPPER.valueToTree(1)).build());
        assertEquals(bits(1, 2), NativeExprEvaluator.evaluate(shorter, ctx(t)));
    }


    @Test
    void oneSidedLowerIsNativeOnly()
    {
        // lower(X) == "hello" — has no v1 lowering, but the native backend evaluates it.
        IDataTable t = MockTable.of().col("X", "HELLO", "Hello", "WORLD").build();
        Expr e = bin(BinOp.EQ, call("lower", ref("X")), s("hello"));
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(e, ctx(t)));
    }


    @Test
    void existsBroadcastsDatasetFact()
    {
        IDataTable t = MockTable.of().col("AESEV", "MILD", "MODERATE").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(call("var_exists", ref("AESEV")), c));
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(call("var_not_exists", ref("GHOST")), c));
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(call("var_exists", ref("GHOST")), c));
    }


    @Test
    void missingColumnYieldsEmpty()
    {
        IDataTable t = MockTable.of().col("X", "a", "b").build();
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("GHOST"), s("a")), ctx(t)));
    }


    @Test
    void unsupportedConstructReportedUnsupported()
    {
        // a bare reference is not a boolean condition
        assertFalse(NativeExprEvaluator.isSupported(ref("X")));
        assertThrows(ExpressionException.class, () -> NativeExprEvaluator.evaluate(ref("X"),
                ctx(MockTable.of().col("X", "a").build())));
    }

    // ------------------------------------------------------------------
    // Parity: converter (CheckToExpr) -> native vs lower-then-legacy
    // ------------------------------------------------------------------


    /** Native smoke evaluation (the legacy comparison oracle is retired with the engine). */
    private void assertParity(CheckConditionLeaf leaf, IDataTable t)
    {
        assertTrue(NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), ctx(t)) != null,
                "native evaluation completes");
    }


    @Test
    void parityAcrossOperatorFamilies()
    {
        IDataTable t = MockTable.of().col("SEX", "M", "F", "")
                .col("AESEV", "MILD", "SEVERE", "MILD").colLong("AGE", 30L, 10L, 20L)
                .col("DTC", "2024-01-02", "2024-01-01", "").col("DUR", "P1Y", "BAD", "P2M")
                .col("NUM", "5", "5.5", "x").build();

        assertParity(CheckConditionLeaf.builder().name("SEX").operator("equal_to")
                .value(MAPPER.valueToTree("M")).valueIsLiteral(true).build(), t);
        assertParity(CheckConditionLeaf.builder().name("SEX").operator("not_equal_to")
                .value(MAPPER.valueToTree("M")).valueIsLiteral(true).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AGE").operator("greater_than")
                .value(MAPPER.valueToTree(20)).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AGE").operator("less_than_or_equal_to")
                .value(MAPPER.valueToTree(20)).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AESEV").operator("matches_regex")
                .value(MAPPER.valueToTree("^MI")).valueIsLiteral(true).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AESEV").operator("is_contained_by")
                .value(MAPPER.valueToTree(List.of("MILD", "MODERATE"))).build(), t);
        assertParity(CheckConditionLeaf.builder().name("DTC").operator("date_greater_than")
                .value(MAPPER.valueToTree("2024-01-01")).valueIsLiteral(true).build(), t);
        assertParity(CheckConditionLeaf.builder().name("AESEV").operator("contains")
                .value(MAPPER.valueToTree("MI")).valueIsLiteral(true).build(), t);
        assertParity(CheckConditionLeaf.builder().name("NUM").operator("is_integer").build(), t);
        assertParity(CheckConditionLeaf.builder().name("SEX").operator("non_empty").build(), t);
        assertParity(CheckConditionLeaf.builder().name("SEX").operator("empty").build(), t);
        assertParity(CheckConditionLeaf.builder().name("AESEV").operator("starts_with")
                .value(MAPPER.valueToTree("MI")).valueIsLiteral(true).build(), t);
    }


    @Test
    void parityForInvalidDuration()
    {
        // EC-22 regression: the native lane must consume the negative= kwarg the lowering emits
        // (previously dropped, so signed durations over-fired natively). Signed rows (-P1D, -P2M)
        // × negative(absent)/negative(true)/negative(false) must all match the legacy operator.
        // Absent defaults to true (EC-20), so it agrees with the explicit-true lane.
        IDataTable t = MockTable.of().col("DUR", "P1Y", "BAD", "P2M", "", "-P1D", "-P2M", "--P1Y")
                .build();
        // negative absent → default true (accept signed grammar).
        assertParity(CheckConditionLeaf.builder().name("DUR").operator("invalid_duration").build(),
                t);
        // negative true → accept signed grammar (the kwarg-drop regression case).
        assertParity(CheckConditionLeaf.builder().name("DUR").operator("invalid_duration")
                .negative(true).build(), t);
        // negative false → reject the leading sign.
        assertParity(CheckConditionLeaf.builder().name("DUR").operator("invalid_duration")
                .negative(false).build(), t);
    }


    @Test
    void parityForLengthAndCaseInsensitive()
    {
        IDataTable t = MockTable.of().col("X", "abcd", "ab", "ABCD").build();
        assertParity(CheckConditionLeaf.builder().name("X").operator("longer_than")
                .value(MAPPER.valueToTree(2)).build(), t);
        assertParity(CheckConditionLeaf.builder().name("X").operator("equal_to_case_insensitive")
                .value(MAPPER.valueToTree("ABCD")).valueIsLiteral(true).build(), t);
    }


    @Test
    void parityForPhaseARowScalarOperators()
    {
        // Both-missing (row 3) is the discriminating case: legacy not_equal_to_case_insensitive
        // treats it as "equal" (no violation); a structural Not(equalsIgnoreCase) would wrongly
        // fire.
        IDataTable t = MockTable.of().col("X", "abcd", "AB", "Wxyz", "")
                .col("Y", "ABCD", "ab", "", "").build();
        assertParity(
                CheckConditionLeaf.builder().name("X").operator("not_equal_to_case_insensitive")
                        .value(MAPPER.valueToTree("ABCD")).valueIsLiteral(true).build(),
                t);
        assertParity(
                CheckConditionLeaf.builder().name("X").operator("is_contained_by_case_insensitive")
                        .value(MAPPER.valueToTree(List.of("abcd", "WXYZ"))).build(),
                t);
        assertParity(CheckConditionLeaf.builder().name("X").operator("has_not_equal_length")
                .value(MAPPER.valueToTree(4)).build(), t);
    }


    @Test
    void isNotContainedByCaseInsensitiveEvaluatesAndMatchesLegacy()
    {
        // is_not_contained_by_case_insensitive (upper(X) not in [...]) is now implemented on BOTH
        // engines as case-insensitive negative membership: the native backend supports it and the
        // the case-insensitive membership contract holds. Probe and
        // set are upper-cased (Locale.ROOT): "abcd"->ABCD in set (false), "AB" not in set (fires),
        // "Wxyz"->WXYZ in set (false). (Previously a legacy no-op / native decline — now fixed,
        // PLAN-regex-rule-optimization Phase 1.)
        IDataTable t = MockTable.of().col("X", "abcd", "AB", "Wxyz").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("is_not_contained_by_case_insensitive")
                .value(MAPPER.valueToTree(List.of("abcd", "WXYZ"))).build();
        Expr expr = CheckToExpr.toExpr(leaf);
        assertTrue(NativeExprEvaluator.isSupported(expr),
                "is_not_contained_by_case_insensitive must be natively supported");
        assertEquals(bits(1), NativeExprEvaluator.evaluate(expr, ctx(t)),
                "only \"AB\" is outside the case-folded set");
    }


    @Test
    void parityForHasMultipleValuesForGrouped()
    {
        // Within subject S1, VISITNUM 1 maps to two AVALs (10, 20) -> rows 0,1 fire; VISITNUM 2 is
        // single. S2 is single. Grouped expects {0,1}; ungrouped (no within) pools all rows so
        // VISITNUM 1 -> {10,20,40} -> rows 0,1,3 fire.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S2")
                .col("VISITNUM", "1", "1", "2", "1").col("AVAL", "10", "20", "30", "40").build();
        assertParity(CheckConditionLeaf.builder().name("AVAL").operator("has_multiple_values_for")
                .value(MAPPER.valueToTree("VISITNUM")).within(MAPPER.valueToTree("USUBJID"))
                .build(), t);
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(CheckConditionLeaf.builder()
                        .name("AVAL").operator("has_multiple_values_for")
                        .value(MAPPER.valueToTree("VISITNUM")).within(MAPPER.valueToTree("USUBJID"))
                        .build()), ctx(t)),
                "grouped has_multiple_values_for");
        assertParity(CheckConditionLeaf.builder().name("AVAL").operator("has_multiple_values_for")
                .value(MAPPER.valueToTree("VISITNUM")).build(), t);
    }


    @Test
    void parityForIncludeEmptyConsistencyOperators()
    {
        // Fix #121: include_empty=true disables the D.13/D.2 emptiness-exclusions on both lanes.
        // has_multiple_values_for: key "1" -> {Baseline, ""} fires rows 0,1; the "" key row 2 has
        // a single dependent. Default (no include_empty) fires nothing on this table.
        IDataTable t = MockTable.of().col("ATPT", "Baseline", "", "Screening")
                .col("ATPTN", "1", "1", "").build();
        CheckConditionLeaf hmvf = CheckConditionLeaf.builder().name("ATPT")
                .operator("has_multiple_values_for").value(MAPPER.valueToTree("ATPTN"))
                .includeEmpty(true).build();
        assertParity(hmvf, t);
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(hmvf), ctx(t)),
                "include_empty has_multiple_values_for");

        // is_inconsistent_across_dataset: ALB group {mg/L (x2), ""} -> the blank row is the
        // minority and fires; the default would fire nothing.
        IDataTable inc = MockTable.of().col("STRESU", "mg/L", "", "mg/L")
                .col("TESTCD", "ALB", "ALB", "ALB").build();
        CheckConditionLeaf incLeaf = CheckConditionLeaf.builder().name("STRESU")
                .operator("is_inconsistent_across_dataset")
                .value(MAPPER.valueToTree(List.of("TESTCD"))).includeEmpty(true).build();
        assertParity(incLeaf, inc);
        assertEquals(bits(1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(incLeaf), ctx(inc)),
                "include_empty is_inconsistent_across_dataset");
    }


    @Test
    void parityForPresentOnMultipleRowsWithin()
    {
        // Composite (USUBJID, AETERM): (S1,HEAD) has 2 rows -> present fires {0,1}; the singletons
        // (S1,NAUSEA)=row2 and (S2,PAIN)=row3 fire for not_present.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S2")
                .col("AETERM", "HEAD", "HEAD", "NAUSEA", "PAIN").build();
        CheckConditionLeaf present = CheckConditionLeaf.builder().name("AETERM")
                .operator("present_on_multiple_rows_within").within(MAPPER.valueToTree("USUBJID"))
                .build();
        assertParity(present, t);
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(present), ctx(t)),
                "present_on_multiple_rows_within");
        CheckConditionLeaf notPresent = CheckConditionLeaf.builder().name("AETERM")
                .operator("not_present_on_multiple_rows_within")
                .within(MAPPER.valueToTree("USUBJID")).build();
        assertParity(notPresent, t);
        assertEquals(bits(2, 3),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(notPresent), ctx(t)),
                "not_present_on_multiple_rows_within (Q1 not present(...) surface)");
    }


    @Test
    void parityForEmptyWithinExceptLastRow()
    {
        // Group by USUBJID, order by SEQ. S1 ordered rows 0,1,2; except-last = rows 0,1; AVAL empty
        // at row 0 -> {0}. The trailing empty (row 2, the ordered last) is allowed.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S2")
                .col("SEQ", "1", "2", "3", "1").col("AVAL", "", "x", "", "").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AVAL")
                .operator("empty_within_except_last_row").value(MAPPER.valueToTree("USUBJID"))
                .ordering("SEQ").build();
        assertParity(leaf, t);
        assertEquals(bits(0), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), ctx(t)),
                "empty_within_except_last_row");
    }


    @Test
    void parityForDoesNotHaveNextCorrespondingRecord()
    {
        // within USUBJID, ordered by SEQ; A on the current row must equal B on the next ordered
        // row.
        // S1 rows 0,1,2: A[0]=10==B[1]=10 ok; A[1]=99!=B[2]=20 -> flag row 1; row 2 is last.
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S2")
                .col("SEQ", "1", "2", "3", "1").col("A", "10", "99", "30", "p")
                .col("B", "x", "10", "20", "r").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("A")
                .operator("does_not_have_next_corresponding_record").value(MAPPER.valueToTree("B"))
                .within(MAPPER.valueToTree("USUBJID")).ordering("SEQ").build();
        assertParity(leaf, t);
        assertEquals(bits(1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), ctx(t)),
                "does_not_have_next_corresponding_record (Q1 not has_next...(...) surface)");
    }


    @Test
    void parityForTargetIsNotSortedBy()
    {
        // Group by GRP, order each group by ORD (numeric-aware), then check VAL is ascending. Group
        // A ordered by ORD is rows 1,0 -> VAL 5 then 3 -> not ascending -> whole group {0,1} fires;
        // group B is a singleton -> skipped.
        IDataTable t = MockTable.of().col("GRP", "A", "A", "B").col("ORD", "2", "1", "1")
                .col("VAL", "3", "5", "1").build();
        com.fasterxml.jackson.databind.node.ObjectNode d = MAPPER.createObjectNode();
        d.put("name", "ORD").put("sort_order", "asc").put("null_position", "last");
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("VAL")
                .operator("target_is_not_sorted_by").value(MAPPER.createArrayNode().add(d))
                .within(MAPPER.valueToTree("GRP")).build();
        assertParity(leaf, t);
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), ctx(t)),
                "target_is_not_sorted_by (not is_sorted_by(...) surface)");
    }


    @Test
    void parityForPhaseCAggregates()
    {
        // is_not_unique_relationship: A=x maps to both 1 and 2 -> rows {0,1}.
        IDataTable rel = MockTable.of().col("A", "x", "x", "y").col("B", "1", "2", "3").build();
        CheckConditionLeaf relLeaf = CheckConditionLeaf.builder().name("A")
                .operator("is_not_unique_relationship").value(MAPPER.valueToTree("B")).build();
        assertParity(relLeaf, rel);
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(relLeaf), ctx(rel)),
                "is_not_unique_relationship");

        // is_not_unique_set on (NAME,K): (a,1) duplicated -> {0,1}; is_unique_set -> {2}.
        IDataTable set = MockTable.of().col("NAME", "a", "a", "b").col("K", "1", "1", "2").build();
        CheckConditionLeaf dup = CheckConditionLeaf.builder().name("NAME")
                .operator("is_not_unique_set").value(MAPPER.valueToTree(List.of("K"))).build();
        assertParity(dup, set);
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(dup), ctx(set)),
                "is_not_unique_set");
        CheckConditionLeaf uniq = CheckConditionLeaf.builder().name("NAME")
                .operator("is_unique_set").value(MAPPER.valueToTree(List.of("K"))).build();
        assertParity(uniq, set);
        assertEquals(bits(2), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(uniq), ctx(set)),
                "is_unique_set");

        // is_inconsistent_across_dataset: group G=A has NAME {x,y} (>1 distinct) -> {0,1}.
        IDataTable inc = MockTable.of().col("G", "A", "A", "B").col("NAME", "x", "y", "z").build();
        CheckConditionLeaf incLeaf = CheckConditionLeaf.builder().name("NAME")
                .operator("is_inconsistent_across_dataset").value(MAPPER.valueToTree(List.of("G")))
                .build();
        assertParity(incLeaf, inc);
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(incLeaf), ctx(inc)),
                "is_inconsistent_across_dataset");

        // inconsistent_enumerated_columns: row 1 has TS empty but TS1 populated -> gap -> {1}.
        IDataTable enu = MockTable.of().col("TS", "a", "").col("TS1", "x", "y").build();
        CheckConditionLeaf enuLeaf = CheckConditionLeaf.builder().name("TS")
                .operator("inconsistent_enumerated_columns").build();
        assertParity(enuLeaf, enu);
        assertEquals(bits(1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(enuLeaf), ctx(enu)),
                "inconsistent_enumerated_columns");
    }


    @Test
    void parityForNotContainsAllAndHasSameValues()
    {
        // not_contains_all: TSPARMCD distinct {INTMODEL,INTTYPE} lacks PCLASS -> all rows flagged.
        IDataTable miss = MockTable.of().col("TSPARMCD", "INTMODEL", "INTTYPE").build();
        CheckConditionLeaf nca = CheckConditionLeaf.builder().name("TSPARMCD")
                .operator("not_contains_all")
                .value(MAPPER.valueToTree(List.of("INTMODEL", "INTTYPE", "PCLASS"))).build();
        assertParity(nca, miss);
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(nca), ctx(miss)),
                "not_contains_all (missing required value)");
        // Contains all -> no violation.
        IDataTable full = MockTable.of().col("TSPARMCD", "INTMODEL", "INTTYPE", "PCLASS").build();
        assertParity(nca, full);
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(nca), ctx(full)),
                "not_contains_all (contains all)");

        // has_same_values: all MHCAT identical -> all rows flagged; mixed -> none.
        IDataTable same = MockTable.of().col("MHCAT", "GENERAL", "GENERAL", "GENERAL").build();
        CheckConditionLeaf hsv = CheckConditionLeaf.builder().name("MHCAT")
                .operator("has_same_values").build();
        assertParity(hsv, same);
        assertEquals(bits(0, 1, 2),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(hsv), ctx(same)),
                "has_same_values");
        IDataTable mixed = MockTable.of().col("MHCAT", "A", "B", "A").build();
        assertParity(hsv, mixed);
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(CheckToExpr.toExpr(hsv), ctx(mixed)),
                "has_same_values (mixed)");
    }


    @Test
    void parityForDoesNotEqualStringPart()
    {
        // Extract group 1 (chars 5-6) of the Y value; fire where X differs from it. row0 "AB"==
        // group of "abABcd"; row1 "ZZ"!=group of "qrXYst"; row2 no full match; row3 both missing.
        IDataTable t = MockTable.of().col("X", "AB", "ZZ", "QQ", "")
                .col("Y", "abABcd", "qrXYst", "nomatch", "anyz").build();
        assertParity(CheckConditionLeaf.builder().name("X").operator("does_not_equal_string_part")
                .value(MAPPER.valueToTree("Y")).regex(".{2}(..).*").build(), t);
    }


    @Test
    void affixCompareAgainstALiteral()
    {
        // prefix_equal_to with prefix:2 raises to prefix(X, 2) == "FA" (P1: VALUE prefix/2).
        // value_is_literal marks "FA" as the literal it is — a bareword would be a column ref.
        // "FAKE" → "FA" fires; "APKE" → "AP" doesn't; "F" (shorter than 2) → whole string "F".
        IDataTable t = MockTable.of().col("X", "FAKE", "APKE", "F", "").build();
        CheckConditionLeaf eq = CheckConditionLeaf.builder().name("X").operator("prefix_equal_to")
                .prefix(2).value(MAPPER.valueToTree("FA")).valueIsLiteral(true).build();
        assertParity(eq, t);
        assertEquals(bits(0), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(eq), ctx(t)),
                "prefix(X,2) == FA");
        // prefix_not_equal_to with prefix:2: fires where the first 2 chars differ from "FA",
        // INCLUDING the missing row 3 — a missing cell folds to "" and "" differs from "FA".
        //
        // ⚠ This assertion was inverted by EC-49 / Fix #148 (2026-08-04). Until then the native
        // compile intersected the affix-NEQ result with Primitives.nonEmpty(lv), so row 3 was
        // suppressed and the expectation here was bits(1, 2). That mask was the engine's last
        // operator-level exception to the absent-column contract; note that the
        // prefix_is_not_contained_by assertion below ALREADY fired on row 3 over this very
        // fixture, so before the fix the two negative affix surfaces disagreed with each other
        // on the same operand. See register §45 (the retired JAVA-EXTENSIONS §22 is indexed in
        // expression-docs-disposition.md §A).
        CheckConditionLeaf neq = CheckConditionLeaf.builder().name("X")
                .operator("prefix_not_equal_to").prefix(2).value(MAPPER.valueToTree("FA"))
                .valueIsLiteral(true).build();
        assertParity(neq, t);
        assertEquals(bits(1, 2, 3), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(neq), ctx(t)),
                "prefix(X,2) != FA — the missing row 3 folds to \"\" and fires, like every other "
                        + "negative leaf (Fix #148)");
        // prefix_is_not_contained_by (NOT_IN surface): empty-string literal fix (A.1 affix) — a
        // missing cell folds to "" (extracted prefix ""), which is not in the list, so it now
        // fires; legacy and native move together (parity preserved).
        CheckConditionLeaf notIn = CheckConditionLeaf.builder().name("X")
                .operator("prefix_is_not_contained_by").prefix(2)
                .value(MAPPER.createArrayNode().add("FA").add("AP")).build();
        assertParity(notIn, t);
        assertEquals(bits(2, 3), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(notIn), ctx(t)),
                "prefix(X,2) not in [FA, AP] — 'F' fires, '' (missing) folds to '' and fires");
    }


    @Test
    void affixCompareRhsIsPerRowOperand()
    {
        // Phase 5: the affix-comparison RHS now binds through the generic valuePlan for BOTH EQ and
        // NEQ, so a quoted literal still folds to a broadcast const while a bareword/column/$-var
        // is
        // read per row — identical resolution on both polarities.
        IDataTable t = MockTable.of().col("X", "FAKE", "APKE", "FXYZ").col("PCOL", "FA", "ZZ", "FX")
                .build();

        // Literal RHS regression: prefix(X,2) == "FA" — only "FAKE" → "FA" fires.
        assertEquals(bits(0),
                NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("prefix", ref("X"), num(2)), s("FA")), ctx(t)),
                "prefix(X,2) == \"FA\" (literal regression)");
        assertEquals(bits(1, 2),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.NEQ, call("prefix", ref("X"), num(2)), s("FA")), ctx(t)),
                "prefix(X,2) != \"FA\" (literal regression)");

        // Per-row column RHS: prefix(X,2) == PCOL now reads PCOL per row (was a broken literal
        // "PCOL" compare before Phase 5). Row0 "FA"=="FA" fires, row1 "AP"!="ZZ", row2 "FX"=="FX".
        assertEquals(bits(0, 2),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("prefix", ref("X"), num(2)), ref("PCOL")), ctx(t)),
                "prefix(X,2) == PCOL (per-row column)");
        // NEQ resolves the RHS identically — exactly the complement of the EQ rows (row1 only).
        // No row of this fixture is missing, so the fixture is silent on the empty-operand
        // question that EC-49 / Fix #148 settled; affixCompareAgainstALiteral covers that.
        assertEquals(bits(1),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.NEQ, call("prefix", ref("X"), num(2)), ref("PCOL")), ctx(t)),
                "prefix(X,2) != PCOL (per-row column, EQ/NEQ resolve RHS identically)");

        // $-var RHS: a scalar context variable broadcasts and is compared per row, same as a
        // quoted literal — confirms the EQ special-case removal also widened $-var resolution.
        EvaluationContext vctx = EvaluationContext.builder().table(t)
                .variables(Map.of("$pfx", "FA")).build();
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("prefix", ref("X"), num(2)), opref("$pfx")), vctx),
                "prefix(X,2) == $pfx ($-var RHS)");
        assertEquals(bits(1, 2),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.NEQ, call("prefix", ref("X"), num(2)), opref("$pfx")), vctx),
                "prefix(X,2) != $pfx ($-var RHS)");
    }


    @Test
    void hasEqualLengthPolymorphicLength()
    {
        // Phase 3: the length operand of has_equal_length / has_not_equal_length is now per-row.
        // X cells have lengths 5, 3, 0(missing). LENCOL holds a numeric length; SLEN a char "5".
        IDataTable t = MockTable.of().col("X", "ABCDE", "QWE", "").col("LENCOL", "5", "5", "3")
                .col("SLEN", "5", "5", "5").build();

        // Numeric literal (regression): has_equal_length(X, 5) — only the length-5 cell fires.
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(call("has_equal_length", ref("X"), num(5)), ctx(t)),
                "has_equal_length(X, 5) numeric literal");
        // String literal "5" parses to 5 (legacy asInt parity).
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(call("has_equal_length", ref("X"), s("5")), ctx(t)),
                "has_equal_length(X, \"5\") string literal parses");
        // Non-parseable string "abc" folds to length 0 (legacy asInt) — only the missing/empty
        // cell (length 0) fires.
        assertEquals(bits(2),
                NativeExprEvaluator.evaluate(call("has_equal_length", ref("X"), s("abc")), ctx(t)),
                "has_equal_length(X, \"abc\") folds to length-0 comparison");
        // Per-row numeric column: LENCOL = [5,5,3]; X lengths = [5,3,0] → only row0 matches.
        assertEquals(
                bits(0), NativeExprEvaluator
                        .evaluate(call("has_equal_length", ref("X"), ref("LENCOL")), ctx(t)),
                "has_equal_length(X, LENCOL) numeric column per-row");
        // Per-row char column holding "5": SLEN = ["5","5","5"] → only the length-5 row0 fires.
        assertEquals(
                bits(0), NativeExprEvaluator
                        .evaluate(call("has_equal_length", ref("X"), ref("SLEN")), ctx(t)),
                "has_equal_length(X, SLEN) char column parses per-row");

        // has_not_equal_length polarity = complement on every shape.
        assertEquals(
                bits(1, 2), NativeExprEvaluator
                        .evaluate(call("has_not_equal_length", ref("X"), num(5)), ctx(t)),
                "has_not_equal_length(X, 5)");
        assertEquals(
                bits(1, 2), NativeExprEvaluator
                        .evaluate(call("has_not_equal_length", ref("X"), s("5")), ctx(t)),
                "has_not_equal_length(X, \"5\")");
        assertEquals(
                bits(0, 1), NativeExprEvaluator
                        .evaluate(call("has_not_equal_length", ref("X"), s("abc")), ctx(t)),
                "has_not_equal_length(X, \"abc\") (length-0 complement)");
        assertEquals(
                bits(1, 2), NativeExprEvaluator
                        .evaluate(call("has_not_equal_length", ref("X"), ref("LENCOL")), ctx(t)),
                "has_not_equal_length(X, LENCOL)");
        assertEquals(
                bits(1, 2), NativeExprEvaluator
                        .evaluate(call("has_not_equal_length", ref("X"), ref("SLEN")), ctx(t)),
                "has_not_equal_length(X, SLEN)");
    }


    @Test
    void affixRegexLengthBoundedNativeMatchesLegacy()
    {
        // not_prefix_matches_regex with prefix:2 raises to not prefix_matches(X, /(AP|FA)/, 2)
        // (P1: BOOLEAN prefix_matches/3). Legacy fires rows whose 2-char prefix does NOT match —
        // including missing values (negate semantics).
        IDataTable t = MockTable.of().col("X", "FAKE", "AP01", "XF12", "").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("not_prefix_matches_regex").prefix(2).value(MAPPER.valueToTree("(AP|FA)"))
                .valueIsLiteral(Boolean.TRUE).build();
        assertParity(leaf, t);
        assertEquals(bits(2, 3), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), ctx(t)),
                "not prefix_matches(X, /(AP|FA)/, 2)");
        // positive suffix form: suffix_matches_regex with suffix:3 fires the matching rows only.
        IDataTable s = MockTable.of().col("Y", "IDSEQ", "IDSEX", "").build();
        CheckConditionLeaf pos = CheckConditionLeaf.builder().name("Y")
                .operator("suffix_matches_regex").suffix(3).value(MAPPER.valueToTree("SEQ"))
                .valueIsLiteral(Boolean.TRUE).build();
        assertParity(pos, s);
        assertEquals(bits(0), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(pos), ctx(s)),
                "suffix_matches(Y, /SEQ/, 3)");
    }


    @Test
    void sharesNoElementsWithNativeMatchesLegacy()
    {
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        // $-list vs $-list: sharing an element → no violation; disjoint → all rows.
        EvaluationContext c = EvaluationContext.builder().table(t).variables(Map.of("$datasets",
                List.of("DM", "AE"), "$shared", List.of("DM"), "$disjoint", List.of("XX"))).build();
        CheckConditionLeaf shared = CheckConditionLeaf.builder().name("$datasets")
                .operator("shares_no_elements_with").value(MAPPER.valueToTree("$shared")).build();
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(shared), c),
                "shared element → not flagged");
        CheckConditionLeaf disjoint = CheckConditionLeaf.builder().name("$datasets")
                .operator("shares_no_elements_with").value(MAPPER.valueToTree("$disjoint")).build();
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(disjoint), c),
                "disjoint sets → all rows flagged");
        // keys=[…] literal-array form (the arity-1 raise): $datasets vs ["DM"] → shared → none.
        CheckConditionLeaf keysForm = CheckConditionLeaf.builder().name("$datasets")
                .operator("shares_no_elements_with").value(MAPPER.createArrayNode().add("DM"))
                .build();
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(keysForm), c),
                "keys-form literal array shares DM → not flagged");
        // unresolvable NAME (a non-$ name never resolves for this operator) → all rows (legacy
        // null contract).
        CheckConditionLeaf unresolved = CheckConditionLeaf.builder().name("NOTAVAR")
                .operator("shares_no_elements_with").value(MAPPER.valueToTree("$shared")).build();
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(unresolved), c),
                "unresolvable operand → all rows flagged");
        // ABSENT $-NAME → the CheckEvaluator.evaluateLeaf guard short-circuits to NO violation
        // (it fires before the operator's flag-all null contract can apply).
        CheckConditionLeaf absentVar = CheckConditionLeaf.builder().name("$absent_var")
                .operator("shares_no_elements_with").value(MAPPER.valueToTree("$shared")).build();
        assertTrue(NativeExprEvaluator.evaluate(CheckToExpr.toExpr(absentVar), c).isEmpty(),
                "absent $-name → leaf guard → no violation");
    }


    @Test
    void isNotOrderedSubsetOfNativeMatchesLegacy()
    {
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$inOrder", List.of("A", "C"), "$outOfOrder", List.of("C", "A"),
                        "$library", List.of("A", "B", "C")))
                .build();
        // ordered subsequence (gaps allowed) → no violation.
        CheckConditionLeaf ok = CheckConditionLeaf.builder().name("$inOrder")
                .operator("is_not_ordered_subset_of").value(MAPPER.valueToTree("$library")).build();
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(ok), c),
                "ordered subset → not flagged");
        // out of order → all rows flagged.
        CheckConditionLeaf bad = CheckConditionLeaf.builder().name("$outOfOrder")
                .operator("is_not_ordered_subset_of").value(MAPPER.valueToTree("$library")).build();
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(bad), c),
                "order violated → all rows flagged");
        // unresolvable operand → NO violation (the inverse of shares_no_elements_with's contract).
        CheckConditionLeaf unresolved = CheckConditionLeaf.builder().name("$missingvar")
                .operator("is_not_ordered_subset_of").value(MAPPER.valueToTree("$library")).build();
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(CheckToExpr.toExpr(unresolved), c),
                "unresolvable operand → not flagged");
    }

    // ------------------------------------------------------------------
    // Phase 9c — arithmetic-operand literals
    // ------------------------------------------------------------------


    @Test
    void arithmeticNotEqualDivideAcceptsLiteralOperand()
    {
        // X != ( A / 2 ): the divisor is a numeric literal (Phase 9c). row0 2==4/2 ok;
        // row1 4!=4/2 violation; row2 missing A skipped; row3 missing X skipped.
        IDataTable t = MockTable.of().col("X", "2", "4", "1", "").col("A", "4", "4", "", "4")
                .build();
        Expr e = bin(BinOp.NEQ, ref("X"), bin(BinOp.DIV, ref("A"), num(2)));
        assertTrue(NativeExprEvaluator.isSupported(e), "X != ( A / 2 ) supported");
        assertEquals(bits(1), NativeExprEvaluator.evaluate(e, ctx(t)), "X != ( A / 2 )");
    }


    @Test
    void arithmeticNotEqualSubtractAcceptsLiteralOperand()
    {
        // X != ( A - 2 ): the subtrahend is a numeric literal (Phase 9c). row0 3==5-2 ok;
        // row1 4!=5-2 violation.
        IDataTable t = MockTable.of().col("X", "3", "4").col("A", "5", "5").build();
        Expr e = bin(BinOp.NEQ, ref("X"), bin(BinOp.SUB, ref("A"), num(2)));
        assertTrue(NativeExprEvaluator.isSupported(e), "X != ( A - 2 ) supported");
        assertEquals(bits(1), NativeExprEvaluator.evaluate(e, ctx(t)), "X != ( A - 2 )");
    }


    @Test
    void arithmeticNotEqualDivideBothColumnsRegression()
    {
        // Regression: X != ( A / B ) with both operands columns still works (Phase 4b shape).
        IDataTable t = MockTable.of().col("X", "2", "4").col("A", "10", "10").col("B", "5", "2")
                .build();
        Expr e = bin(BinOp.NEQ, ref("X"), bin(BinOp.DIV, ref("A"), ref("B")));
        assertEquals(bits(1), NativeExprEvaluator.evaluate(e, ctx(t)), "X != ( A / B )");
    }


    @Test
    void arithmeticNotEqualPctchgUnchanged()
    {
        // The percent-change shape X != ((A-B)/B)*100 keeps its ref-only structural detection
        // (the divisor and subtrahend must be the same Ref). row0 100==((10-5)/5)*100 ok;
        // row1 50!=((10-5)/5)*100 violation.
        IDataTable t = MockTable.of().col("X", "100", "50").col("A", "10", "10").col("B", "5", "5")
                .build();
        Expr pctchg = bin(BinOp.MUL, bin(BinOp.DIV, bin(BinOp.SUB, ref("A"), ref("B")), ref("B")),
                num(100));
        Expr e = bin(BinOp.NEQ, ref("X"), pctchg);
        assertTrue(NativeExprEvaluator.isSupported(e), "pctchg shape supported");
        assertEquals(bits(1), NativeExprEvaluator.evaluate(e, ctx(t)), "X != ((A-B)/B)*100");
    }

    // ------------------------------------------------------------------
    // Phase 10 — constant-fold pure value functions with literal-only args
    // ------------------------------------------------------------------


    @Test
    void pureValueCallWithLiteralArgsFoldsToConstVector()
    {
        // lower("TEXT") is pure with a literal arg: it folds to a broadcast ConstVector computed
        // once, and the SAME Vector instance is returned across evaluations (single computation).
        ExprCompiler.ValuePlan plan = ExprCompiler
                .valueCallPlan((Expr.Call) call("lower", s("TEXT")));
        IDataTable t = MockTable.of().col("X", "a", "b").build();
        Vector v1 = plan.eval(EvalRun.fullRange(ctx(t)));
        Vector v2 = plan.eval(EvalRun.fullRange(ctx(t)));
        org.junit.jupiter.api.Assertions.assertInstanceOf(ConstVector.class, v1,
                "literal-only lower(...) folds to a ConstVector");
        org.junit.jupiter.api.Assertions.assertSame(v1, v2,
                "the folded ConstVector is computed once and reused across evaluations");
        assertEquals("text", v1.resolvedObject(0), "row 0 broadcast value");
        assertEquals("text", v1.resolvedObject(1), "row 1 broadcast value");
    }


    @Test
    void nestedLiteralOnlySubstringFolds()
    {
        // substring("ABCDE", 2, 2) -> "BC" folds to a ConstVector.
        ExprCompiler.ValuePlan plan = ExprCompiler
                .valueCallPlan((Expr.Call) call("substring", s("ABCDE"), num(2), num(2)));
        IDataTable t = MockTable.of().col("X", "a", "b").build();
        Vector v = plan.eval(EvalRun.fullRange(ctx(t)));
        org.junit.jupiter.api.Assertions.assertInstanceOf(ConstVector.class, v,
                "substring literal-only folds");
        assertEquals("BC", v.resolvedObject(0), "substring(\"ABCDE\", 2, 2)");
    }


    @Test
    void contextDependentCallNotFolded()
    {
        // record_count() reads run.ctx() and is NOT in the fold allowlist: it stays per-row and
        // still evaluates correctly (broadcasting the table row count from its own body).
        ExprCompiler.ValuePlan plan = ExprCompiler.valueCallPlan((Expr.Call) call("record_count"));
        IDataTable t = MockTable.of().col("X", "a", "b", "c").build();
        Vector v = plan.eval(EvalRun.fullRange(ctx(t)));
        assertEquals(3L, ((Number) v.resolvedObject(0)).longValue(), "record_count() = 3");
    }


    @Test
    void pureValueCallWithColumnArgNotFolded()
    {
        // lower(SOMECOL) has a column arg, not a literal: it stays per-row (ComputedVector), not
        // a folded ConstVector, and evaluates per row.
        ExprCompiler.ValuePlan plan = ExprCompiler
                .valueCallPlan((Expr.Call) call("lower", ref("X")));
        IDataTable t = MockTable.of().col("X", "AB", "CD").build();
        Vector v = plan.eval(EvalRun.fullRange(ctx(t)));
        assertFalse(v instanceof ConstVector, "column-arg lower(X) is not folded to a constant");
        assertEquals("ab", v.resolvedObject(0), "row 0");
        assertEquals("cd", v.resolvedObject(1), "row 1");
    }

    // ------------------------------------------------------------------
    // Phase 4 — lock in the already-symmetric operand-polymorphism paths.
    // These tests are pure regression protection: every position below is
    // documented as already accepting more than one operand shape, so all
    // assertions must PASS against the current engine. A failure here means
    // the "already-symmetric" claim is wrong for that position — do NOT
    // weaken the test, report the divergence.
    // ------------------------------------------------------------------


    @Test
    void orderedComparisonOperandShapesAreSymmetric()
    {
        // < > <= >= compare numerically on BOTH sides: the LHS is parsed via getValueAsDouble and
        // the RHS via comparisonTargetAsDouble. So a numeric literal, a parsing string literal
        // ("5"), a numeric column, and a character column holding a number all give the SAME
        // comparison verdict; a non-numeric RHS (or a missing LHS) folds to "missing" => no fire.
        // AGE = [30, 10, 20, missing]; LIMITCOL/THRESHSTR carry the per-row threshold 0.
        IDataTable t = MockTable.of().colLong("AGE", 30L, 10L, 20L, null)
                .col("AGECHAR", "30", "10", "20", "").colLong("LIMITCOL", 0L, 0L, 0L, 0L)
                .col("THRESHSTR", "0", "0", "0", "0").build();

        // Reference verdict: AGE > 0 fires on every populated row (rows 0,1,2); the missing row 3
        // never fires.
        BitSet expected = bits(0, 1, 2);

        // RHS shapes: numeric literal 0, parsing string literal "0", numeric column, char column.
        assertEquals(expected,
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGE"), num(0)), ctx(t)),
                "AGE > 0 (numeric literal RHS)");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGE"), s("0")), ctx(t)),
                "AGE > \"0\" (string literal RHS that parses)");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGE"), ref("LIMITCOL")), ctx(t)),
                "AGE > LIMITCOL (numeric column RHS)");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGE"), ref("THRESHSTR")), ctx(t)),
                "AGE > THRESHSTR (character column holding \"0\" RHS)");

        // LHS shapes: a character column holding a number compares identically to the numeric
        // column. AGECHAR = ["30","10","20",""] > 0 => rows 0,1,2 (the empty cell is missing).
        assertEquals(expected,
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGECHAR"), num(0)), ctx(t)),
                "AGECHAR > 0 (character LHS parses, same verdict as the numeric column)");

        // <= mirrors the same four RHS shapes (AGE <= 20 => rows 1,2; missing row never fires).
        BitSet le20 = bits(1, 2);
        assertEquals(le20, NativeExprEvaluator.evaluate(bin(BinOp.LE, ref("AGE"), num(20)), ctx(t)),
                "AGE <= 20 (numeric literal)");
        assertEquals(le20, NativeExprEvaluator.evaluate(bin(BinOp.LE, ref("AGE"), s("20")), ctx(t)),
                "AGE <= \"20\" (string literal that parses)");

        // Non-numeric RHS folds to missing => no fire on any row, for every operator.
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("AGE"), s("TEXT")), ctx(t)),
                "AGE > \"TEXT\" (non-numeric RHS) never fires");
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.GE, ref("AGE"), s("TEXT")), ctx(t)),
                "AGE >= \"TEXT\" (non-numeric RHS) never fires");

        // Missing LHS never fires regardless of RHS shape (row 3 is missing in AGE; an all-missing
        // column LHS compared to 0 yields the empty set).
        IDataTable allMissing = MockTable.of().colLong("M", (Long) null, null).build();
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.GT, ref("M"), num(0)), ctx(allMissing)),
                "missing LHS never fires");
    }


    @Test
    void betweenBoundsOperandShapesAreSymmetric()
    {
        // between(X, lo, hi) fires where X is numeric and lo <= X <= hi. lo/hi accept a numeric
        // literal, a parsing string literal ("18"/"65"), and per-row columns — all parity. A
        // missing or non-numeric bound never fires.
        // X = [10, 18, 40, 65, 80, x]; the 18..65 band fires rows 1,2,3.
        IDataTable t = MockTable.of().col("X", "10", "18", "40", "65", "80", "x")
                .col("LOCOL", "18", "18", "18", "18", "18", "18")
                .col("HICOL", "65", "65", "65", "65", "65", "65").build();
        BitSet expected = bits(1, 2, 3);

        assertEquals(expected,
                NativeExprEvaluator.evaluate(call("between", ref("X"), num(18), num(65)), ctx(t)),
                "between(X, 18, 65) numeric literal bounds");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(call("between", ref("X"), s("18"), s("65")), ctx(t)),
                "between(X, \"18\", \"65\") string literal bounds that parse");
        assertEquals(
                expected, NativeExprEvaluator
                        .evaluate(call("between", ref("X"), ref("LOCOL"), ref("HICOL")), ctx(t)),
                "between(X, LOCOL, HICOL) per-row column bounds");
        // Mixed shapes (literal lo, column hi) still parity.
        assertEquals(
                expected, NativeExprEvaluator
                        .evaluate(call("between", ref("X"), num(18), ref("HICOL")), ctx(t)),
                "between(X, 18, HICOL) mixed literal/column bounds");

        // A non-numeric bound never fires (the whole predicate yields no rows).
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(call("between", ref("X"), s("LOW"), num(65)), ctx(t)),
                "between(X, \"LOW\", 65) non-numeric lo never fires");
        // A missing bound column never fires.
        IDataTable mt = MockTable.of().col("X", "20", "40").col("LO", "", "").col("HI", "65", "65")
                .build();
        assertEquals(
                new BitSet(), NativeExprEvaluator
                        .evaluate(call("between", ref("X"), ref("LO"), ref("HI")), ctx(mt)),
                "between(X, LO, HI) with missing lo never fires");
    }


    @Test
    void substringPrefixSuffixNumericArgShapesAreSymmetric()
    {
        // substring/prefix/suffix take their start/length/n numeric arg as a numeric literal, a
        // parsing string literal ("2"), or a per-row column (numeric or character holding a
        // number) — all parity with the numeric literal. NCOL is numeric 2; SCOL a char "2".
        IDataTable t = MockTable.of().col("X", "ABCDE", "QBCDZ").colLong("NCOL", 2L, 2L)
                .col("SCOL", "2", "2").build();

        // substring(X, 2, 3): 1-based start 2 => "BCD" / "BCDZ"... clamp length 3 => "BCD"/"BCD".
        Expr litStart = bin(BinOp.EQ, call("substring", ref("X"), num(2), num(3)), s("BCD"));
        BitSet expected = NativeExprEvaluator.evaluate(litStart, ctx(t));
        assertEquals(bits(0, 1), expected, "substring(X,2,3) == BCD baseline");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("substring", ref("X"), s("2"), num(3)), s("BCD")),
                        ctx(t)),
                "substring(X, \"2\", 3) string-literal start parity");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("substring", ref("X"), ref("NCOL"), num(3)), s("BCD")),
                        ctx(t)),
                "substring(X, NCOL, 3) numeric-column start parity");
        assertEquals(expected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("substring", ref("X"), ref("SCOL"), num(3)), s("BCD")),
                        ctx(t)),
                "substring(X, SCOL, 3) char-column-holding-number start parity");
        // length arg as the polymorphic operand too: substring(X, 2, NCOL) length 2 => "BC".
        assertEquals(NativeExprEvaluator.evaluate(
                bin(BinOp.EQ, call("substring", ref("X"), num(2), num(2)), s("BC")), ctx(t)),
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("substring", ref("X"), num(2), ref("NCOL")), s("BC")),
                        ctx(t)),
                "substring length arg: column parity with numeric literal 2");

        // prefix(X, 2) == "AB" — the n arg as literal/string-literal/column all parity.
        Expr prefLit = bin(BinOp.EQ, call("prefix", ref("X"), num(2)), s("AB"));
        BitSet prefExpected = NativeExprEvaluator.evaluate(prefLit, ctx(t));
        assertEquals(bits(0), prefExpected, "prefix(X,2) == AB baseline (only ABCDE)");
        assertEquals(prefExpected,
                NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("prefix", ref("X"), s("2")), s("AB")), ctx(t)),
                "prefix(X, \"2\") string-literal n parity");
        assertEquals(prefExpected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("prefix", ref("X"), ref("NCOL")), s("AB")), ctx(t)),
                "prefix(X, NCOL) numeric-column n parity");
        assertEquals(prefExpected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("prefix", ref("X"), ref("SCOL")), s("AB")), ctx(t)),
                "prefix(X, SCOL) char-column-holding-number n parity");

        // suffix(X, 2) == "DE"/"DZ" — n as literal/string-literal/column all parity.
        Expr sufLit = bin(BinOp.EQ, call("suffix", ref("X"), num(2)), s("DE"));
        BitSet sufExpected = NativeExprEvaluator.evaluate(sufLit, ctx(t));
        assertEquals(bits(0), sufExpected, "suffix(X,2) == DE baseline (only ABCDE)");
        assertEquals(sufExpected,
                NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("suffix", ref("X"), s("2")), s("DE")), ctx(t)),
                "suffix(X, \"2\") string-literal n parity");
        assertEquals(sufExpected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("suffix", ref("X"), ref("NCOL")), s("DE")), ctx(t)),
                "suffix(X, NCOL) numeric-column n parity");
        assertEquals(sufExpected,
                NativeExprEvaluator.evaluate(
                        bin(BinOp.EQ, call("suffix", ref("X"), ref("SCOL")), s("DE")), ctx(t)),
                "suffix(X, SCOL) char-column-holding-number n parity");
    }


    @Test
    void numericValueFunctionsParseCharacterColumn()
    {
        // abs/round/floor/ceil parse a character column holding "-3.5" to a number, identical to a
        // numeric column carrying -3.5. abs => 3.5; round(-3.5) => -3 (Math.round half-up to +inf);
        // floor => -4; ceil => -3.
        IDataTable t = MockTable.of().col("CHARNUM", "-3.5").colDouble("NUMCOL", -3.5).build();

        for (String fn : List.of("abs", "round", "floor", "ceil"))
        {
            // Compare the value-function result over the char column against the numeric column by
            // wrapping each in an equality leaf against the other; equal_to is numeric on declared-
            // numeric operands, so the parity check is "abs(CHARNUM) == abs(NUMCOL)" => fires row
            // 0.
            Expr e = bin(BinOp.EQ, call(fn, ref("CHARNUM")), call(fn, ref("NUMCOL")));
            assertEquals(bits(0), NativeExprEvaluator.evaluate(e, ctx(t)),
                    fn + "(CHARNUM) == " + fn + "(NUMCOL) — char column parses to the same number");
        }

        // Spot-check the concrete folded values via a comparison against a literal: abs => 3.5,
        // floor => -4, ceil => -3, round => -3.
        assertEquals(
                bits(0), NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("abs", ref("CHARNUM")), num(3.5)), ctx(t)),
                "abs(\"-3.5\") == 3.5");
        assertEquals(
                bits(0), NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("floor", ref("CHARNUM")), num(-4)), ctx(t)),
                "floor(\"-3.5\") == -4");
        assertEquals(
                bits(0), NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("ceil", ref("CHARNUM")), num(-3)), ctx(t)),
                "ceil(\"-3.5\") == -3");
        assertEquals(
                bits(0), NativeExprEvaluator
                        .evaluate(bin(BinOp.EQ, call("round", ref("CHARNUM")), num(-3)), ctx(t)),
                "round(\"-3.5\") == -3");
    }


    @Test
    void equalsIgnoreCaseSecondArgIsColumnOrLiteralPerRow()
    {
        // equalsIgnoreCase(X, v): the comparand v is already per-row — a column read or a broadcast
        // literal, symmetrically. X = ["abc","ABC","xyz"]; VCOL = ["ABC","abc","ZZZ"]. Column form
        // fires rows 0,1 (case-insensitive match per row); the literal "ABC" fires rows 0,1 too.
        IDataTable t = MockTable.of().col("X", "abc", "ABC", "xyz").col("VCOL", "ABC", "abc", "ZZZ")
                .build();

        assertEquals(
                bits(0, 1), NativeExprEvaluator
                        .evaluate(call("equalsIgnoreCase", ref("X"), ref("VCOL")), ctx(t)),
                "equalsIgnoreCase(X, VCOL) per-row column comparand");
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(call("equalsIgnoreCase", ref("X"), s("ABC")), ctx(t)),
                "equalsIgnoreCase(X, \"ABC\") broadcast literal comparand");

        // A per-row literal that differs row-by-row only matches where the case-folded text agrees:
        // matching the column form against the literal "abc" fires row 0 (abc) and row 1 (ABC).
        assertEquals(bits(0, 1),
                NativeExprEvaluator.evaluate(call("equalsIgnoreCase", ref("X"), s("abc")), ctx(t)),
                "equalsIgnoreCase(X, \"abc\") lowercase literal still case-insensitive");
    }


    @Test
    void existsFamilyAcceptsBarewordOrStringLiteralName()
    {
        // exists/ds_exists/var_exists accept the target name as a bareword reference OR an
        // equivalent string literal (the column<->string-literal symmetry in compileExists). Both
        // forms resolve to the same name and the same verdict.
        IDataTable t = MockTable.of().col("AESEV", "MILD", "MODERATE").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();

        // exists: a present column fires every row, an absent one fires none — bareword == literal.
        assertEquals(NativeExprEvaluator.evaluate(call("var_exists", ref("AESEV")), c),
                NativeExprEvaluator.evaluate(call("var_exists", s("AESEV")), c),
                "exists(AESEV) bareword == var_exists(\"AESEV\") literal");
        assertEquals(bits(0, 1), NativeExprEvaluator.evaluate(call("var_exists", s("AESEV")), c),
                "exists(\"AESEV\") fires (present column)");
        assertEquals(NativeExprEvaluator.evaluate(call("var_exists", ref("GHOST")), c),
                NativeExprEvaluator.evaluate(call("var_exists", s("GHOST")), c),
                "exists(GHOST) bareword == var_exists(\"GHOST\") literal (absent => empty)");
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(call("var_exists", s("GHOST")), c),
                "exists(\"GHOST\") absent column => no rows");

        // var_exists: column presence on the evaluated dataset, bareword == literal.
        assertEquals(NativeExprEvaluator.evaluate(call("var_exists", ref("AESEV")), c),
                NativeExprEvaluator.evaluate(call("var_exists", s("AESEV")), c),
                "var_exists(AESEV) bareword == var_exists(\"AESEV\") literal");

        // ds_exists: dataset presence, driven by the context's $-variable presence facts. With a
        // ${dataset->present} variable map, the bareword and the string-literal name resolve the
        // same dataset fact.
        EvaluationContext dsCtx = EvaluationContext.builder().table(t)
                .variables(Map.of("DM", true, "EX", false)).build();
        assertEquals(NativeExprEvaluator.evaluate(call("ds_exists", ref("DM")), dsCtx),
                NativeExprEvaluator.evaluate(call("ds_exists", s("DM")), dsCtx),
                "ds_exists(DM) bareword == ds_exists(\"DM\") literal");
    }

    // -------------------------------------------------------------------------
    // Phase 8 — numeric comparison mode (8a num(), 8b declared-numeric LHS)
    // -------------------------------------------------------------------------


    @Test
    void phase8b_numericColumn_comparesNumerically()
    {
        // D1 matrix: AGE is a numeric (LONG) column = 18; the declared-numeric LHS trigger upgrades
        // == / != to numeric mode against a numeric literal, a parseable string literal, "18.0",
        // and a numeric column.
        IDataTable t = MockTable.of().colLong("AGE", 18L).colLong("AGE2", 18L)
                .colLong("WEIGHT", 18L).build();
        EvaluationContext c = ctx(t);
        assertEquals(bits(0), NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), num(18)), c),
                "AGE == 18 (numeric literal)");
        assertEquals(bits(0), NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), s("18")), c),
                "AGE == \"18\" (string parses)");
        assertEquals(bits(0), NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), s("18.0")), c),
                "AGE == \"18.0\" (string parses)");
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), ref("AGE2")), c),
                "AGE == AGE2 (numeric column)");
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), ref("WEIGHT")), c),
                "AGE == WEIGHT (numeric column)");
        // != is the exact complement on a present cell.
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.NEQ, ref("AGE"), s("18.0")), c),
                "AGE != \"18.0\" does not fire");
    }


    @Test
    void phase8b_numericColumn_nonParseableRhs_foldsTextual()
    {
        // Option B: AGE (numeric) == "abc" — the RHS does not parse, so the verdict falls back to
        // the textual fold ("18" vs "abc"): == false, != fires.
        IDataTable t = MockTable.of().colLong("AGE", 18L).build();
        EvaluationContext c = ctx(t);
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), s("abc")), c),
                "AGE == \"abc\" is false (textual)");
        assertEquals(bits(0), NativeExprEvaluator.evaluate(bin(BinOp.NEQ, ref("AGE"), s("abc")), c),
                "AGE != \"abc\" fires (textual)");
    }


    @Test
    void phase8b_characterColumn_numericRhsParsesCell()
    {
        // AGEC is a CHARACTER column holding "18.0"; the numeric RHS literal 18 triggers numeric
        // mode (target is a Number), so AGEC == 18 parses the cell → true.
        IDataTable t = MockTable.of().col("AGEC", "18.0", "19").build();
        EvaluationContext c = ctx(t);
        assertEquals(bits(0), NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGEC"), num(18)), c),
                "AGEC == 18 (RHS numeric parses char cell)");
    }


    @Test
    void phase8b_characterIdentifier_staysTextual()
    {
        // Safety: USUBJID="01" == "1" — neither side declared numeric, RHS literal is a String,
        // so the comparison stays textual: "01" != "1".
        IDataTable t = MockTable.of().col("USUBJID", "01").build();
        EvaluationContext c = ctx(t);
        assertEquals(new BitSet(),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("USUBJID"), s("1")), c),
                "USUBJID == \"1\" stays textual (false)");
    }


    @Test
    void phase8b_missingFolds_preserved()
    {
        // missing == missing matches (folds to ""); present != missing fires. AGE is numeric so the
        // trigger is hot, but both-missing falls back to the textual "" fold (Option B).
        IDataTable t = MockTable.of().colLong("AGE", (Long) null, 18L).colLong("AGE2", null, null)
                .build();
        EvaluationContext c = ctx(t);
        // row0: missing == missing → match; row1: present 18 == missing(AGE2) → "18" vs "" → false.
        assertEquals(bits(0),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), ref("AGE2")), c),
                "missing==missing matches, present==missing does not");
        // != is the complement: row0 no-fire, row1 fires.
        assertEquals(bits(1),
                NativeExprEvaluator.evaluate(bin(BinOp.NEQ, ref("AGE"), ref("AGE2")), c),
                "present != missing fires");
    }


    @Test
    void phase8a_num_forcesNumericMode()
    {
        // 8a: a num()-tagged operand forces numeric mode even when both operands are CHARACTER
        // columns / string literals. WEIGHTSTR="70" and HEIGHTSTR="70.0" compare textually unequal
        // but num(WEIGHTSTR) == num(HEIGHTSTR) is numeric → 70 == 70.0 → true.
        IDataTable t = MockTable.of().col("WEIGHTSTR", "70", "70").col("HEIGHTSTR", "70.0", "80")
                .build();
        EvaluationContext c = ctx(t);
        Expr eq = bin(BinOp.EQ, call("num", ref("WEIGHTSTR")), call("num", ref("HEIGHTSTR")));
        assertEquals(bits(0), NativeExprEvaluator.evaluate(eq, c),
                "num(WEIGHTSTR) == num(HEIGHTSTR)");
        // num() on one side is enough.
        IDataTable t2 = MockTable.of().col("AESEQ", "2", "3").build();
        Expr eq2 = bin(BinOp.EQ, call("num", ref("AESEQ")), num(2));
        assertEquals(bits(0), NativeExprEvaluator.evaluate(eq2, ctx(t2)), "num(AESEQ) == 2");
    }


    @Test
    void phase8a_num_nonParseable_noViolation()
    {
        // num("abc") → NaN → falls back to textual fold; "abc" vs "18" → not equal, so == is false
        // (no violation) and the firing depends on the operator under test.
        IDataTable t = MockTable.of().colLong("AGE", 18L).build();
        EvaluationContext c = ctx(t);
        Expr eq = bin(BinOp.EQ, call("num", s("abc")), ref("AGE"));
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(eq, c),
                "num(\"abc\") == AGE → no match (NaN folds textual)");
    }


    @Test
    void phase8a_num_overNumericColumn_isNoOp()
    {
        // num() over an already-numeric operand is a no-op: num(AGE) == 18 == AGE == 18.
        IDataTable t = MockTable.of().colLong("AGE", 18L, 10L).build();
        EvaluationContext c = ctx(t);
        assertEquals(NativeExprEvaluator.evaluate(bin(BinOp.EQ, ref("AGE"), num(18)), c),
                NativeExprEvaluator.evaluate(bin(BinOp.EQ, call("num", ref("AGE")), num(18)), c),
                "num(AGE) == 18 equals AGE == 18");
    }

}
