package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} phase 2 — the stage-A half: the result types of the new and
 * registered functions (§2.3's type table, {@code colref}'s only in its checker arm), owner Q7 as
 * {@code PARAMETER_TYPE} (§3.1; armed since {@code PLAN-stage-a-parameter-type-arming}), the
 * undeclared qualifier of a literal {@code colref} name as the observe-only
 * {@code DOTTED_REF_UNDECLARED} (§3.1 H2, uniform with the authored {@code DS.X}), and the Q14
 * call-site recording of {@link TypeExpectations}.
 */
class DynamicColumnStageATest
{

    private static StageAReport check(Rule rule, String expression)
    {
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, CheckExpressionParser.parse(expression));
        return StageAChecker.check(rule, levels);
    }


    /** The type of the LEFT operand of a comparison {@code expression}. */
    private static ExprType leftType(String expression)
    {
        return check(new Rule(), expression).typedLevels().get(Severity.ERROR).children().get(0)
                .type();
    }


    private static List<StageAErrorKind> kinds(StageAReport report)
    {
        return report.findings().stream().map(StageAFinding::kind).distinct().toList();
    }


    @Test
    void resultTypesArePinned()
    {
        assertEquals(Primitive.COLUMN_REFERENCE, leftType("colref(\"AESEQ\") == 1"));
        assertEquals(Level.RECORD, check(new Rule(), "colref(IDVAR) == 1").typedLevels()
                .get(Severity.ERROR).children().get(0).level());
        assertEquals(new ListOf(Unknown.UNKNOWN), leftType("colref([\"A\", \"B\"]) == 1"),
                "the list form's elements are cells, never names: list<unknown>");
        assertEquals(Primitive.STRING, leftType("str(AESEQ) == \"1\""));
        assertEquals(Primitive.STRING, leftType("printf(\"%02d\", AESEQ) == \"01\""));
        assertEquals(Primitive.STRING, leftType("lpad(AETERM, 2, \"0\") == \"01\""));
        assertEquals(Primitive.NUMBER, leftType("num(AETERM) == 1"));
    }


    /** Q7: a statically non-string argument is PARAMETER_TYPE — armed, so it parks the rule. */
    @Test
    void aNonStringColrefArgumentIsAnArmedParameterType()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check(new Rule(), "colref(3) == \"x\"")));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check(new Rule(), "colref(num(AETERM)) == \"x\"")));
        assertEquals(List.of(), kinds(check(new Rule(), "colref(IDVAR) == \"x\"")));
        assertEquals(List.of(), kinds(check(new Rule(), "colref(concat(\"A\", \"B\")) == \"x\"")));
        assertTrue(StageAErrorKind.PARAMETER_TYPE.armed(),
                "PLAN-stage-a-parameter-type-arming armed PARAMETER_TYPE (2026-10-01): a"
                        + " non-string colref argument is a load error");
    }


    /** §3.1 H2: an undeclared qualifier is filed exactly as the authored DS.X is — observe-only. */
    @Test
    void anUndeclaredLiteralQualifierIsTheObserveOnlyUndeclaredKind()
    {
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED),
                kinds(check(new Rule(), "colref(\"DM.AGE\") > 30")));
        assertEquals(kinds(check(new Rule(), "DM.AGE > 30")),
                kinds(check(new Rule(), "colref(\"DM.AGE\") > 30")), "uniform with DS.X");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED),
                kinds(check(new Rule(), "colref(concat(\"DM.AG\", \"E\")) > 30")),
                "a literal prefix carries the qualifier too");
        assertEquals(List.of(), kinds(check(new Rule(), "colref(concat(DS, \".AGE\")) > 30")),
                "a computed qualifier cannot be judged at load");
        Rule declared = new Rule();
        MatchDataset dm = new MatchDataset();
        dm.setName("DM");
        dm.setKeys(List.of("USUBJID"));
        declared.setMatchDatasets(List.of(dm));
        assertEquals(List.of(), kinds(check(declared, "colref(\"DM.AGE\") > 30")));
        assertFalse(StageAErrorKind.DOTTED_REF_UNDECLARED.armed());
    }


    private static Set<Expr.Call> sites(String expression)
    {
        return TypeExpectations.of(List.of(CheckExpressionParser.parse(expression)))
                .numericDynamicSites();
    }


    private static Expr.Call call(String source)
    {
        return (Expr.Call) CheckExpressionParser.parse(source);
    }


    /** Q14 step 1: a colref call site records its position's kind under the call itself. */
    @Test
    void aColrefCallSiteRecordsItsPositionsKind()
    {
        Expr.Call x = call("colref(\"X\")");
        assertEquals(Set.of(x), sites("colref(\"X\") > 3"));
        assertEquals(Set.of(x), sites("colref(\"X\") == 3"));
        assertEquals(Set.of(x), sites("abs(colref(\"X\")) == 3"));
        assertEquals(Set.of(x), sites("colref(\"X\") in [1, 2]"));
        assertEquals(Set.of(x), sites("colref(\"X\") == \"Y\" or colref(\"X\") < 0"),
                "two textually identical calls union their kinds, as one authored name does");
        assertEquals(Set.of(), sites("colref(\"X\") == \"Y\""));
        assertEquals(Set.of(), sites("colref(\"X\") != Y"), "column-vs-column records no kind");
        assertEquals(Set.of(), sites("str(IDVARVAL) != str(colref(IDVAR))"),
                "the corpus's three colref(IDVAR) rules: a string context");
        TypeExpectations te = TypeExpectations
                .of(List.of(CheckExpressionParser.parse("colref(\"X\") > 3")));
        assertTrue(te.valueReadColumns().isEmpty() && te.equalityPairs().isEmpty(),
                "a dynamic site joins neither stage-B probe set");
    }
    // ------------------------------------------------------------------ phase 3


    @Test
    void findVarsIsAListOfNamesAtTheLevelOfItsEntry()
    {
        assertEquals(new ListOf(Primitive.STRING), leftType("find_vars(\"TRTxxP\") == 1"));
        assertEquals(Level.DATASET, check(new Rule(), "count(find_vars(\"TRTxxP\")) == 0")
                .typedLevels().get(Severity.ERROR).level(), "a literal entry: dataset level");
        assertEquals(Level.RECORD, check(new Rule(), "count(find_vars(NAMECOL)) == 0").typedLevels()
                .get(Severity.ERROR).level(), "a computed entry: record level");
        assertEquals(new ListOf(Unknown.UNKNOWN),
                leftType("colref(find_vars(\"ADSL.TRTxxA\")) == 1"));
    }


    @Test
    void anUnknownFindVarsQualifierIsTheObserveOnlyUndeclaredKind()
    {
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED),
                kinds(check(new Rule(), "count(find_vars(\"DM.TRTxxP\")) == 0")));
        assertEquals(List.of(), kinds(check(new Rule(), "count(find_vars(\"/DM.X/\")) == 0")),
                "a whole-entry regex is never split (it is the compiler's load error instead)");
    }


    /** §2.5: a computed var_exists is a RECORD-level presence probe that still READS its inner. */
    @Test
    void aComputedVarExistsIsRecordLevelAndReadsItsArgument()
    {
        String expr = "var_exists(concat(\"ADSL.TRT\", printf(\"%02d\", APERIOD), \"P\"))";
        assertEquals(Level.RECORD,
                check(new Rule(), expr).typedLevels().get(Severity.ERROR).level());
        assertEquals(List.of(), kinds(check(new Rule(), expr)));
        assertTrue(
                TypeExpectations.of(List.of(CheckExpressionParser.parse(expr))).valueReadColumns()
                        .contains("APERIOD"),
                "the driver inside a computed presence probe is a value read (absent-column fold)");
    }

    // ------------------------------------------------------------------ $-bindings (respelling
    // §6.2)

    /** The S2 / S3 shapes' computed names. */
    private static final String TRT_P = "concat(\"ADSL.TRT\", printf(\"%02d\", APERIOD), \"P\")";

    private static final String P_S = "concat(\"ADSL.P\", printf(\"%02d\", APERIOD), \"S\")";

    /**
     * A rule joining ADSL ({@code child} its {@code Child:} flag, {@code null} for no
     * {@code Match_Datasets} at all) whose compiled bindings are {@code nameAndSource} pairs, each
     * preceded by the ones before it.
     */
    private static Rule adslRule(Boolean child, String... nameAndSource)
    {
        Rule rule = new Rule();
        if (child != null)
        {
            MatchDataset adsl = new MatchDataset();
            adsl.setName("ADSL");
            adsl.setKeys(List.of("USUBJID"));
            adsl.setChild(child);
            rule.setMatchDatasets(List.of(adsl));
        }
        List<CompiledBinding> bindings = new java.util.ArrayList<>();
        List<String> before = new java.util.ArrayList<>();
        for (int i = 0; i < nameAndSource.length; i += 2)
        {
            bindings.add(new CompiledBinding(nameAndSource[i],
                    CheckExpressionParser.parse(nameAndSource[i + 1]), List.copyOf(before), null));
            before.add(nameAndSource[i]);
        }
        rule.setCompiledBindings(bindings.isEmpty() ? null : bindings);
        return rule;
    }


    /**
     * LOW-1 ({@code PLAN-dynamic-column-corpus-respelling} §6.2): a {@code $}-binding handed to
     * {@code colref} / {@code find_vars} is followed into its expression, so a bound qualified name
     * is judged exactly as the same name written inline — the armed Child arm and the observe-only
     * undeclared arm alike.
     */
    @Test
    void aBoundQualifiedNameIsJudgedAsTheInlineOne()
    {
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(adslRule(Boolean.TRUE), "count(find_vars(" + P_S + ")) == 0")),
                "the inline spelling (the reference)");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(adslRule(Boolean.TRUE, "$p", P_S), "count(find_vars($p)) == 0")),
                "bound: find_vars($p)");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(
                        adslRule(Boolean.TRUE, "$a",
                                "concat(\"ADSL.AP\", printf(\"%02d\", APERIOD), \"SDT\")"),
                        "APERSDT != colref($a)")),
                "bound: colref($a)");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(adslRule(Boolean.TRUE, "$p", P_S, "$q", "$p"),
                        "count(find_vars($q)) == 0")),
                "a binding of a binding is followed to the expression");
        assertEquals(List.of(),
                kinds(check(adslRule(Boolean.FALSE, "$p", P_S), "count(find_vars($p)) == 0")),
                "an ordinary ADSL entry: declared, clean");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED),
                kinds(check(adslRule(null, "$p", P_S), "count(find_vars($p)) == 0")),
                "no Match_Datasets: the observe-only undeclared kind, as inline");
        assertEquals(kinds(check(adslRule(null), "count(find_vars(" + P_S + ")) == 0")),
                kinds(check(adslRule(null, "$p", P_S), "count(find_vars($p)) == 0")));
    }


    /**
     * A presence probe over a {@code $}-binding is the inline computed probe: record level when the
     * binding is, no qualifier judgement (the exists family reads the named dataset's inventory
     * with no join, inline or bound), and a statically non-string name is PARAMETER_TYPE.
     */
    @Test
    void aBoundPresenceProbeIsTypedAsTheInlineOne()
    {
        Rule rule = adslRule(Boolean.TRUE, "$n", TRT_P);
        assertEquals(Level.RECORD,
                check(rule, "var_exists($n)").typedLevels().get(Severity.ERROR).level(),
                "the binding's record level, not the literal probe's dataset level");
        assertEquals(Level.RECORD,
                check(rule, "var_not_exists($n)").typedLevels().get(Severity.ERROR).level());
        assertEquals(kinds(check(rule, "var_exists(" + TRT_P + ")")),
                kinds(check(rule, "var_exists($n)")));
        assertEquals(List.of(), kinds(check(rule, "not empty($n) and not var_exists($n)")));
        Rule numeric = adslRule(Boolean.FALSE, "$k", "num(APERIOD) + 1");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check(numeric, "var_exists($k)")));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check(numeric, "var_not_exists(num(APERIOD) + 1)")), "inline too");
        assertEquals(List.of(), kinds(check(numeric, "var_exists(APERIOD)")),
                "a bare reference stays the literal-name probe");
    }
}
