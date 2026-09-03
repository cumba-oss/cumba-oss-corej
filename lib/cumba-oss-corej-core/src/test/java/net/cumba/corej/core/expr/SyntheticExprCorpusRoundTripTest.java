package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * <b>G0c</b> of {@code plans/done/PLAN-rules-src-expression-flip.md} §4 — the systematic synthetic
 * {@code Expr} corpus. The corpus-driven gates ({@code ExpressionTextIdempotenceTest} in the rules
 * module, P4a/P4b) prove idempotence only for the shapes the <em>authored corpus happens to
 * contain</em>; this test constructs every node kind, operator and literal kind directly and
 * asserts <b>both</b> round-trip directions:
 *
 * <ul>
 * <li><b>text direction</b> — {@code print(parse(t)) == t}: the canonical text is a fixed point, so
 * a regeneration never rewrites it;</li>
 * <li><b>AST direction</b> — {@code parse(print(x)) == x}: printing loses no structure. This
 * direction had never been tested node-kind-systematically before (the 9-rule singleton-composite
 * incident, {@code degenerate-single-child-checks.txt}, is exactly an AST-direction breach the
 * corpus gates could only see as text churn).</li>
 * </ul>
 *
 * <p>
 * Placement: this module (beside {@link RoundTripTest}), not the rules module — the corpus is
 * synthetic, needs no {@code rules-src}, and pins main-scope classes owned here
 * ({@link CheckExpressionParser}, {@link ExpressionPrinter}, {@link Expr},
 * {@link OperandClassifier}), so a regression reds the owning module with the smallest build.
 * </p>
 *
 * <p>
 * <b>Self-verifying coverage:</b> {@link #theCorpusCoversEveryKindSystematically()} walks the
 * corpus and asserts the union of covered node records (via
 * {@code Expr.class.getPermittedSubclasses()}), {@link Expr.BinOp}s, {@link Expr.LitKind}s and
 * {@link OperandKind}s equals the full sets — adding an enum constant or a sealed permit reds this
 * test until the corpus covers it. ⚠ {@code BinOp.ADD} has <b>zero</b> corpus instances (measured
 * 2026-08-13: SUB 12 · MUL 6 · DIV 18 · ADD 0), so this synthetic corpus is its only round-trip
 * coverage anywhere.
 * </p>
 *
 * <p>
 * <b>Known non-round-trippable shapes are pinned, not hidden</b> — see
 * {@link #singletonCompositesCollapseByDesign()} and the {@code *Boundary*} tests. Pinning the
 * current behaviour keeps a silent drift visible; it does not endorse the shape.
 * </p>
 */
class SyntheticExprCorpusRoundTripTest
{

    /** One corpus entry: the AST, and the canonical text the printer must emit for it. */
    private record Case(String label, Expr ast, String text)
    {
    }

    // ---- AST construction shorthand ---------------------------------------

    private static Expr.Ref col(String aName)
    {
        return new Expr.Ref(aName, OperandKind.COLUMN);
    }


    private static Expr.Ref ref(String aName, OperandKind aKind)
    {
        return new Expr.Ref(aName, aKind);
    }


    private static Expr.Lit str(String aValue)
    {
        return new Expr.Lit(Expr.LitKind.STRING, aValue);
    }


    private static Expr.Lit num(double aValue)
    {
        return new Expr.Lit(Expr.LitKind.NUMBER, aValue);
    }


    private static Expr.Lit bool(boolean aValue)
    {
        return new Expr.Lit(Expr.LitKind.BOOL, aValue);
    }


    private static Expr.Lit regex(String aPattern)
    {
        return new Expr.Lit(Expr.LitKind.REGEX, aPattern);
    }


    private static Expr.Lit list(Expr... aItems)
    {
        return new Expr.Lit(Expr.LitKind.LIST, List.of(aItems));
    }


    private static Expr.Call call(String aName, Expr... aArgs)
    {
        return new Expr.Call(aName, List.of(aArgs), Map.of());
    }


    private static Expr.Binary bin(Expr.BinOp aOp, Expr aLeft, Expr aRight)
    {
        return new Expr.Binary(aOp, aLeft, aRight);
    }

    // ---- the corpus --------------------------------------------------------

    /**
     * The systematic corpus. Every entry must satisfy all three of: {@code print(ast) == text},
     * {@code parse(text) == ast} (AST direction), {@code print(parse(text)) == text} (text
     * direction). Grouped: the 14 {@link Expr.BinOp}s, the 5 {@link Expr.LitKind}s, the 5
     * {@link OperandKind}s, the {@link Expr.Call} arg/kwarg shapes, and the composite / precedence
     * structures.
     */
    private static final List<Case> CORPUS = List.of(
            // -- the 14 BinOps ------------------------------------------------
            new Case("EQ, string literal", bin(Expr.BinOp.EQ, col("AESEV"), str("MILD")),
                    "AESEV == \"MILD\""),
            new Case("NEQ, dotted reference value",
                    bin(Expr.BinOp.NEQ, col("DSSTDTC"), ref("DM.DTHDTC", OperandKind.DOTTED_REF)),
                    "DSSTDTC != DM.DTHDTC"),
            new Case("LT, integral number", bin(Expr.BinOp.LT, col("AVAL"), num(0)), "AVAL < 0"),
            new Case("GT, decimal number", bin(Expr.BinOp.GT, col("AVAL"), num(2.5)), "AVAL > 2.5"),
            new Case("LE, negative number", bin(Expr.BinOp.LE, col("AVISITN"), num(-1)),
                    "AVISITN <= -1"),
            new Case("GE, operation reference",
                    bin(Expr.BinOp.GE, col("VISITNUM"),
                            ref("$tv_visitnum", OperandKind.OPERATION_REF)),
                    "VISITNUM >= $tv_visitnum"),
            new Case("MATCH, regex with escaped backslash",
                    bin(Expr.BinOp.MATCH, col("AESTDTC"), regex("^\\d{4}$")),
                    "AESTDTC =~ /^\\\\d{4}$/"),
            new Case("NMATCH, plain regex",
                    bin(Expr.BinOp.NMATCH, col("DOMAIN"), regex("^(AP|SUPP)")),
                    "DOMAIN !~ /^(AP|SUPP)/"),
            new Case("IN, string list",
                    bin(Expr.BinOp.IN, col("AESEV"), list(str("MILD"), str("MODERATE"))),
                    "AESEV in [\"MILD\", \"MODERATE\"]"),
            new Case("NOT_IN, singleton list",
                    bin(Expr.BinOp.NOT_IN, col("EPOCH"), list(str("SCREENING"))),
                    "EPOCH not in [\"SCREENING\"]"),
            // ⚠ ADD has 0 instances in the authored corpus — this synthetic case is its only
            // round-trip coverage (plan §4).
            new Case("ADD, as a comparison operand",
                    bin(Expr.BinOp.EQ, col("AVAL"), bin(Expr.BinOp.ADD, col("BASE"), num(1))),
                    "AVAL == (BASE + 1)"),
            new Case("SUB, as a comparison operand",
                    bin(Expr.BinOp.GE, col("ADURN"),
                            bin(Expr.BinOp.SUB, col("AENDY"), col("ASTDY"))),
                    "ADURN >= (AENDY - ASTDY)"),
            new Case("MUL",
                    bin(Expr.BinOp.EQ, col("AVAL"), bin(Expr.BinOp.MUL, col("BASE"), num(2))),
                    "AVAL == (BASE * 2)"),
            new Case("DIV",
                    bin(Expr.BinOp.LT, col("PCTCHG"), bin(Expr.BinOp.DIV, col("CHG"), col("BASE"))),
                    "PCTCHG < (CHG / BASE)"),
            new Case("nested arithmetic keeps structure through parens",
                    bin(Expr.BinOp.EQ, col("AVAL"),
                            bin(Expr.BinOp.ADD, bin(Expr.BinOp.MUL, col("BASE"), num(2)), num(1))),
                    "AVAL == ((BASE * 2) + 1)"),

            // -- LitKinds not already covered ----------------------------------
            new Case("BOOL literal as comparison value",
                    bin(Expr.BinOp.EQ, col("QVAL"), bool(true)), "QVAL == true"),
            new Case("BOOL literal standing alone", bool(false), "false"),
            new Case("STRING literal with quote and backslash escapes",
                    bin(Expr.BinOp.EQ, col("AETERM"), str("say \"hi\" \\ back")),
                    "AETERM == \"say \\\"hi\\\" \\\\ back\""),
            new Case("empty LIST literal", bin(Expr.BinOp.IN, col("AESEV"), list()), "AESEV in []"),
            new Case("LIST of numbers incl. negative",
                    bin(Expr.BinOp.NOT_IN, col("AVISITN"), list(num(-1), num(2.5))),
                    "AVISITN not in [-1, 2.5]"),
            new Case("REGEX with escaped forward slash",
                    bin(Expr.BinOp.MATCH, col("TSVAL"), regex("^\\d+/\\d+$")),
                    "TSVAL =~ /^\\\\d+\\/\\\\d+$/"),

            // -- OperandKinds not already covered ------------------------------
            new Case("WILDCARD_COLUMN, -- prefix",
                    call("var_exists", ref("--STDTC", OperandKind.WILDCARD_COLUMN)),
                    "var_exists(--STDTC)"),
            new Case("WILDCARD_COLUMN, * wildcard",
                    call("non_empty", ref("*DT", OperandKind.WILDCARD_COLUMN)), "non_empty(*DT)"),
            new Case("WILDCARD_COLUMN, ADaM capture letter",
                    bin(Expr.BinOp.EQ, ref("AyIND", OperandKind.WILDCARD_COLUMN), str("Y")),
                    "AyIND == \"Y\""),
            new Case("BUILTIN reference",
                    bin(Expr.BinOp.EQ, ref("variable_name", OperandKind.BUILTIN), str("AETERM")),
                    "variable_name == \"AETERM\""),
            new Case("backtick-quoted non-identifier COLUMN",
                    bin(Expr.BinOp.EQ, col("PROTOCOL MILESTONE"), str("X")),
                    "`PROTOCOL MILESTONE` == \"X\""),

            // -- Call shapes ---------------------------------------------------
            new Case("zero-argument call", call("today"), "today()"),
            new Case("multi-argument call", call("date_diff", col("AESTDTC"), col("AEENDTC")),
                    "date_diff(AESTDTC, AEENDTC)"),
            new Case("kwargs-only call",
                    new Expr.Call("unique_count", List.of(), Map.of("within", col("USUBJID"))),
                    "unique_count(within=USUBJID)"),
            new Case("args AND kwargs; kwargs print sorted by key",
                    new Expr.Call("present_on_multiple_rows_within", List.of(col("AESEQ")),
                            Map.of("within", col("USUBJID"), "min_count", num(2))),
                    "present_on_multiple_rows_within(AESEQ, min_count=2, within=USUBJID)"),
            new Case("nested calls on both comparison sides",
                    bin(Expr.BinOp.EQ, call("str", call("date", col("AESTDTC"))),
                            call("str", ref("DM.RFSTDTC", OperandKind.DOTTED_REF))),
                    "str(date(AESTDTC)) == str(DM.RFSTDTC)"),
            new Case("arithmetic as a call argument",
                    bin(Expr.BinOp.LE, call("abs", bin(Expr.BinOp.SUB, col("ASTDY"), col("AENDY"))),
                            num(1)),
                    "abs((ASTDY - AENDY)) <= 1"),

            // -- composites & precedence ---------------------------------------
            new Case("And of two parts",
                    new Expr.And(List.of(call("var_exists", col("AESEV")),
                            bin(Expr.BinOp.EQ, col("AESEV"), str("MILD")))),
                    "var_exists(AESEV) and AESEV == \"MILD\""),
            new Case("And of three parts",
                    new Expr.And(List.of(call("non_empty", col("AETERM")),
                            call("non_empty", col("AESEV")), call("non_empty", col("AESER")))),
                    "non_empty(AETERM) and non_empty(AESEV) and non_empty(AESER)"),
            new Case("Or of two parts",
                    new Expr.Or(List.of(call("empty", col("AEENDTC")),
                            bin(Expr.BinOp.EQ, col("AEENRF"), str("ONGOING")))),
                    "empty(AEENDTC) or AEENRF == \"ONGOING\""),
            new Case("Not of a call", new Expr.Not(call("empty", col("AETERM"))),
                    "not empty(AETERM)"),
            new Case("Not of a comparison binds without parens",
                    new Expr.Not(bin(Expr.BinOp.EQ, col("AESEV"), str("MILD"))),
                    "not AESEV == \"MILD\""),
            new Case("double negation", new Expr.Not(new Expr.Not(call("empty", col("AETERM")))),
                    "not not empty(AETERM)"),
            new Case("Or of Ands needs no parens (and binds tighter)",
                    new Expr.Or(List.of(
                            new Expr.And(List.of(bin(Expr.BinOp.EQ, col("AESER"), str("Y")),
                                    bin(Expr.BinOp.EQ, col("AESDTH"), str("Y")))),
                            bin(Expr.BinOp.EQ, col("AESLIFE"), str("Y")))),
                    "AESER == \"Y\" and AESDTH == \"Y\" or AESLIFE == \"Y\""),
            new Case("And of Ors is parenthesised",
                    new Expr.And(List.of(
                            new Expr.Or(List.of(bin(Expr.BinOp.EQ, col("AESER"), str("Y")),
                                    bin(Expr.BinOp.EQ, col("AESDTH"), str("Y")))),
                            bin(Expr.BinOp.EQ, col("AEOUT"), str("FATAL")))),
                    "(AESER == \"Y\" or AESDTH == \"Y\") and AEOUT == \"FATAL\""),
            new Case("Not of a composite is parenthesised",
                    new Expr.Not(new Expr.Or(
                            List.of(call("empty", col("AETERM")), call("empty", col("AESEV"))))),
                    "not (empty(AETERM) or empty(AESEV))"),
            new Case("mixed nesting: parenthesised Or and negated And under one And",
                    new Expr.And(List.of(
                            new Expr.Or(List.of(call("empty", col("MHSTDTC")),
                                    call("empty", col("MHENDTC")))),
                            new Expr.Not(new Expr.And(List.of(call("var_exists", col("MHENRF")),
                                    call("var_exists", col("MHENRTPT"))))))),
                    "(empty(MHSTDTC) or empty(MHENDTC)) and not (var_exists(MHENRF)"
                            + " and var_exists(MHENRTPT))"));

    // ---- the two directions, per corpus entry ------------------------------

    private static String reprint(String aText)
    {
        return ExpressionPrinter.print(CheckExpressionParser.parse(aText));
    }


    /** Text direction: the canonical text is a fixed point of {@code print ∘ parse}. */
    private static boolean textFixedPoint(String aText)
    {
        return aText.equals(reprint(aText));
    }


    /** AST direction: {@code parse(print(x))}. */
    private static Expr reparse(Expr aAst)
    {
        return CheckExpressionParser.parse(ExpressionPrinter.print(aAst));
    }


    @Test
    void everyCorpusCasePrintsItsCanonicalText()
    {
        List<String> wrong = new ArrayList<>();
        for (Case c : CORPUS)
        {
            String printed = ExpressionPrinter.print(c.ast());
            if (!c.text().equals(printed))
            {
                wrong.add(c.label() + ": expected <" + c.text() + "> printed <" + printed + ">");
            }
        }
        assertEquals(List.of(), wrong, "printer disagrees with the pinned canonical text");
    }


    @Test
    void astDirectionParseAfterPrintReproducesEveryTree()
    {
        List<String> breaches = new ArrayList<>();
        for (Case c : CORPUS)
        {
            Expr back = reparse(c.ast());
            if (!c.ast().equals(back))
            {
                breaches.add(c.label() + ": <" + c.ast() + "> reparsed to <" + back + ">");
            }
        }
        assertEquals(List.of(), breaches, "parse(print(x)) != x — printing loses structure");
    }


    @Test
    void textDirectionEveryCanonicalTextIsAFixedPoint()
    {
        List<String> unstable = new ArrayList<>();
        for (Case c : CORPUS)
        {
            if (!textFixedPoint(c.text()))
            {
                unstable.add(
                        c.label() + ": <" + c.text() + "> reprinted <" + reprint(c.text()) + ">");
            }
        }
        assertEquals(List.of(), unstable, "print(parse(t)) != t — a regeneration would rewrite");
    }

    // ---- self-verifying coverage -------------------------------------------


    private static void walk(Expr aNode, Set<Class<?>> aNodeKinds, EnumSet<Expr.BinOp> aOps,
            EnumSet<Expr.LitKind> aLits, EnumSet<OperandKind> aOperands, boolean[] aCallShapes)
    {
        aNodeKinds.add(aNode.getClass());
        switch (aNode)
        {
        case Expr.Lit lit ->
        {
            aLits.add(lit.kind());
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                for (Expr item : items)
                {
                    walk(item, aNodeKinds, aOps, aLits, aOperands, aCallShapes);
                }
            }
        }
        case Expr.Ref ref -> aOperands.add(ref.kind());
        case Expr.Call c ->
        {
            aCallShapes[0] |= !c.args().isEmpty();
            aCallShapes[1] |= !c.kwargs().isEmpty();
            for (Expr arg : c.args())
            {
                walk(arg, aNodeKinds, aOps, aLits, aOperands, aCallShapes);
            }
            for (Expr kw : c.kwargs().values())
            {
                walk(kw, aNodeKinds, aOps, aLits, aOperands, aCallShapes);
            }
        }
        case Expr.Binary b ->
        {
            aOps.add(b.op());
            walk(b.left(), aNodeKinds, aOps, aLits, aOperands, aCallShapes);
            walk(b.right(), aNodeKinds, aOps, aLits, aOperands, aCallShapes);
        }
        case Expr.And and -> and.parts()
                .forEach(p -> walk(p, aNodeKinds, aOps, aLits, aOperands, aCallShapes));
        case Expr.Or or -> or.parts()
                .forEach(p -> walk(p, aNodeKinds, aOps, aLits, aOperands, aCallShapes));
        case Expr.Not not -> walk(not.inner(), aNodeKinds, aOps, aLits, aOperands, aCallShapes);
        }
    }


    /**
     * The corpus covers the complete inventory — derived from the sealed permits and the enums
     * themselves, so growing any of them reds this test until the corpus grows with it.
     */
    @Test
    void theCorpusCoversEveryKindSystematically()
    {
        Set<Class<?>> nodeKinds = new LinkedHashSet<>();
        EnumSet<Expr.BinOp> ops = EnumSet.noneOf(Expr.BinOp.class);
        EnumSet<Expr.LitKind> lits = EnumSet.noneOf(Expr.LitKind.class);
        EnumSet<OperandKind> operands = EnumSet.noneOf(OperandKind.class);
        boolean[] callShapes = new boolean[2];
        for (Case c : CORPUS)
        {
            walk(c.ast(), nodeKinds, ops, lits, operands, callShapes);
        }
        assertEquals(Set.of(Expr.class.getPermittedSubclasses()), nodeKinds,
                "every sealed Expr node record must appear in the corpus");
        assertEquals(EnumSet.allOf(Expr.BinOp.class), ops,
                "every BinOp must appear (ADD has no authored-corpus instance — only this)");
        assertEquals(EnumSet.allOf(Expr.LitKind.class), lits, "every LitKind must appear");
        assertEquals(EnumSet.allOf(OperandKind.class), operands, "every OperandKind must appear");
        assertTrue(callShapes[0], "a Call with positional args must appear");
        assertTrue(callShapes[1], "a Call with kwargs must appear");
    }

    // ---- structural cases the 9-rule incident proves matter -----------------


    /**
     * ⚠ <b>Singleton composites do NOT round-trip in the AST direction — pinned, by design.</b>
     * {@code And([x])} prints as {@code x} (a single part joins without an operator) and the parser
     * can never rebuild a one-child composite, so {@code parse(print(.))} collapses it to the
     * child. This is the exact mechanism of the 9 {@code degenerate-single-child-checks.txt} rules:
     * a singleton {@code all:} wrapping an {@code any:} prints as {@code (a or b)}, which is not
     * even a text fixed point. The migration writer must therefore normalise via
     * {@code print(parse(print(.)))} (plan §5 step 2).
     */
    @Test
    void singletonCompositesCollapseByDesign()
    {
        Expr child = call("var_exists", col("AETERM"));
        Expr singletonAnd = new Expr.And(List.of(child));
        Expr singletonOr = new Expr.Or(List.of(child));

        assertEquals("var_exists(AETERM)", ExpressionPrinter.print(singletonAnd));
        assertEquals(child, reparse(singletonAnd), "the singleton And collapses to its child");
        assertNotEquals(singletonAnd, reparse(singletonAnd),
                "if this ever HOLDS the parser learned to rebuild singletons — re-derive P4");
        assertEquals(child, reparse(singletonOr), "the singleton Or collapses to its child");

        // The 9-rule shape itself: And([Or([a, b])]) prints "(a or b)" — not even text-stable.
        Expr nineRuleShape = new Expr.And(List.of(new Expr.Or(
                List.of(call("empty", col("AESTDTC")), call("empty", col("AEENDTC"))))));
        String raw = ExpressionPrinter.print(nineRuleShape);
        assertEquals("(empty(AESTDTC) or empty(AEENDTC))", raw);
        assertFalse(textFixedPoint(raw), "the degenerate raw text must NOT be a fixed point");
        String once = reprint(raw);
        assertEquals("empty(AESTDTC) or empty(AEENDTC)", once);
        assertTrue(textFixedPoint(once), "one normalising cycle reaches the fixed point");
    }


    /** Redundant parentheses normalise in exactly one {@code print ∘ parse} cycle. */
    @Test
    void redundantParenthesesNormaliseInOneCycle()
    {
        String noisy = "((not empty(AESEV)) and (AESEV != \"Y\"))";
        assertFalse(textFixedPoint(noisy), "the noisy spelling must not be a fixed point");
        String once = reprint(noisy);
        assertEquals("not empty(AESEV) and AESEV != \"Y\"", once);
        assertTrue(textFixedPoint(once), "the normalised spelling must be a fixed point");
    }

    // ---- neuter-and-watch: the harness can see a breach ----------------------


    /**
     * Positive controls proving the two comparisons are not vacuous. (a) The text-direction helper
     * flags a non-canonical spelling; (b) the AST-direction equality sees a difference buried in
     * the deepest literal of a kwargs map — i.e. record equality is deep, so a breach anywhere in a
     * tree fails the corpus loop; (c) a known AST-direction breach (the singleton composite) is
     * reported as unequal by the very comparison the corpus tests use.
     */
    @Test
    void theHarnessSeesABreachInBothDirections()
    {
        // (a) text direction — same helper the corpus loop uses.
        assertFalse(textFixedPoint("((var_exists(AETERM)))"),
                "the text control must be seen as unstable, else the text gate is vacuous");

        // (b) AST direction — a single deep literal differs (2 vs 3) inside Call kwargs.
        Expr deep2 = new Expr.And(List.of(call("var_exists", col("AESEQ")),
                new Expr.Call("present_on_multiple_rows_within", List.of(col("AESEQ")),
                        Map.of("within", col("USUBJID"), "min_count", num(2)))));
        Expr deep3 = new Expr.And(List.of(call("var_exists", col("AESEQ")),
                new Expr.Call("present_on_multiple_rows_within", List.of(col("AESEQ")),
                        Map.of("within", col("USUBJID"), "min_count", num(3)))));
        assertNotEquals(deep2, deep3,
                "a deep single-literal difference must be visible to Expr equality");
        assertEquals(deep2, reparse(deep2), "and the unmutated tree still round-trips");

        // (c) a genuine breach flows through the corpus comparison as inequality.
        Expr singleton = new Expr.And(List.of(call("var_exists", col("AETERM"))));
        assertNotEquals(singleton, reparse(singleton),
                "the corpus comparison must report the known singleton breach");
    }

    // ---- documented boundaries: shapes that CANNOT round-trip ---------------


    /**
     * ⚠ <b>Boundary pin, not an endorsement:</b> a <em>comparison</em> or a boolean composite
     * placed in an operand position (a {@link Expr.Binary} operand, a {@link Expr.Call} argument, a
     * list item) prints — parenthesised by {@code ExpressionPrinter.value} — but the printed text
     * does <b>not</b> parse back: operand-level {@code (...)} admits only arithmetic
     * ({@code CheckExpressionParser.parseAtom} calls {@code parseSum}, not {@code parseOr}). These
     * trees are constructible in the IR yet outside the printable/parseable language, so
     * {@code parse(print(x)) == x} CANNOT hold for them. If one of these ever starts parsing, the
     * grammar grew — re-derive the plan §4 inventory before relying on it.
     */
    @Test
    void booleanNodesInOperandPositionsPrintButDoNotParseBack()
    {
        // Each pin asserts BOTH halves separately: the tree PRINTS (outside the lambda, so a
        // printer throw cannot masquerade as the parse rejection), and the printed text is then
        // rejected by the parser (review finding, wave 41 lane C).

        // A comparison as a Binary operand.
        Expr cmpAsOperand = bin(Expr.BinOp.EQ, bin(Expr.BinOp.EQ, col("AESTDY"), num(1)),
                bool(true));
        String cmpText = ExpressionPrinter.print(cmpAsOperand);
        assertEquals("(AESTDY == 1) == true", cmpText);
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(cmpText));

        // A composite as a Call argument.
        Expr andAsArg = call("f",
                new Expr.And(List.of(call("empty", col("AETERM")), call("empty", col("AESEV")))));
        String andText = ExpressionPrinter.print(andAsArg);
        assertEquals("f((empty(AETERM) and empty(AESEV)))", andText);
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(andText));

        // A Not as a Binary operand.
        Expr notAsOperand = bin(Expr.BinOp.EQ, new Expr.Not(call("empty", col("AETERM"))),
                bool(true));
        String notText = ExpressionPrinter.print(notAsOperand);
        assertEquals("(not empty(AETERM)) == true", notText);
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(notText));

        // A composite as a list item.
        Expr orInList = bin(Expr.BinOp.IN, col("AESEV"), new Expr.Lit(Expr.LitKind.LIST, List.of(
                new Expr.Or(List.of(call("empty", col("AETERM")), call("empty", col("AESEV")))))));
        String listText = ExpressionPrinter.print(orInList);
        assertEquals("AESEV in [(empty(AETERM) or empty(AESEV))]", listText);
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(listText));
    }


    /**
     * ⚠ <b>Boundary pin (found by this corpus, 2026-08-13):</b> arithmetic as the <em>left</em>
     * operand of a comparison prints — {@code ExpressionPrinter.value} parenthesises it — but the
     * printed text does not parse back at boolean position: {@code parsePrimary} commits to the
     * boolean-group branch on a leading {@code (}, parses the arithmetic as a complete expression
     * and then rejects the trailing comparison operator. The same tree in <em>value</em> position
     * (a comparison's right side, a call argument) round-trips fine — see the SUB / DIV / ADD
     * corpus cases. No authored rule produces the left-side shape ({@code CheckToExpr} always
     * raises the leaf's name reference on the left), so this is a language boundary, not a corpus
     * defect.
     */
    @Test
    void parenthesisedArithmeticOnTheComparisonLeftDoesNotParseBack()
    {
        Expr leftArithmetic = bin(Expr.BinOp.GE, bin(Expr.BinOp.SUB, col("AENDY"), col("ASTDY")),
                num(0));
        String text = ExpressionPrinter.print(leftArithmetic);
        assertEquals("(AENDY - ASTDY) >= 0", text);
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(text));
    }


    /**
     * ⚠ <b>Boundary pin:</b> an empty composite prints as the empty string, which the parser
     * rejects outright — {@code And([])} / {@code Or([])} have no textual form at all.
     */
    @Test
    void emptyCompositesHaveNoTextualForm()
    {
        String andText = ExpressionPrinter.print(new Expr.And(List.of()));
        String orText = ExpressionPrinter.print(new Expr.Or(List.of()));
        assertEquals("", andText);
        assertEquals("", orText);
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(andText));
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse(orText));
    }


    /**
     * ⚠ <b>Boundary pin:</b> a {@link Expr.Ref} whose stored {@link OperandKind} disagrees with
     * what {@link OperandClassifier} derives from its name cannot round-trip — the kind is not
     * printed (only the name is), so the parser always re-derives it from the text. Inherent to the
     * design: the kind is a classification of the name, not independent state.
     */
    @Test
    void aRefWhoseKindContradictsItsNameCannotRoundTrip()
    {
        Expr lying = call("var_exists", ref("AESEV", OperandKind.BUILTIN));
        Expr back = reparse(lying);
        assertEquals(call("var_exists", ref("AESEV", OperandKind.COLUMN)), back,
                "the parser re-classifies the name from its text");
        assertNotEquals(lying, back);
    }
}
