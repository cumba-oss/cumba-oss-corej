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
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} phase 2 — the stage-A half: the result types of the new and
 * registered functions (§2.3's type table, {@code colref}'s only in its checker arm), owner Q7 as
 * the observe-only {@code PARAMETER_TYPE} (§3.1), the undeclared qualifier of a literal
 * {@code colref} name as the observe-only {@code DOTTED_REF_UNDECLARED} (§3.1 H2, uniform with the
 * authored {@code DS.X}), and the Q14 call-site recording of {@link TypeExpectations}.
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


    /** Q7: a statically non-string argument is PARAMETER_TYPE — observe-only until armed. */
    @Test
    void aNonStringColrefArgumentIsAnObserveOnlyParameterType()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check(new Rule(), "colref(3) == \"x\"")));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check(new Rule(), "colref(num(AETERM)) == \"x\"")));
        assertEquals(List.of(), kinds(check(new Rule(), "colref(IDVAR) == \"x\"")));
        assertEquals(List.of(), kinds(check(new Rule(), "colref(concat(\"A\", \"B\")) == \"x\"")));
        assertFalse(StageAErrorKind.PARAMETER_TYPE.armed(),
                "PLAN-stage-a-parameter-type-arming arms PARAMETER_TYPE; until it lands (same"
                        + " release) the finding parks nothing — flip this assertion there");
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
}
