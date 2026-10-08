package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * Stage A over the substitution operator {@code X ~ /pattern/replacement/}
 * ({@code PLAN-engine-gap-string-extract} §3 / N4, runbook W1b SX, coordinator decision D12): the
 * result is a STRING at the subject's level; a numeric subject is the {@code =~} error; a list
 * subject is typed {@code list<string>} (element-wise, as a lifted call); the pattern is compiled
 * at load and every {@code $n} / {@code ${name}} of the replacement must name a group the pattern
 * defines — each refusal the armed {@link StageAErrorKind#PARAMETER_TYPE} finding, a load error.
 */
class SubstitutionStageATest
{

    private static StageAReport check(String expression)
    {
        return check(new Rule(), expression);
    }


    private static StageAReport check(Rule rule, String expression)
    {
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, CheckExpressionParser.parse(expression));
        return StageAChecker.check(rule, levels);
    }


    private static List<StageAErrorKind> kinds(StageAReport report)
    {
        return report.findings().stream().map(StageAFinding::kind).distinct().toList();
    }


    private static String messages(StageAReport report)
    {
        return report.findings().toString();
    }


    /** The typed left child of the root comparison. */
    private static TypedExpr subject(StageAReport report)
    {
        return report.typedLevels().get(Severity.ERROR).children().get(0);
    }


    private static Rule withBindings(String... nameAndSource)
    {
        Rule rule = new Rule();
        List<CompiledBinding> bound = new ArrayList<>();
        for (int i = 0; i < nameAndSource.length; i += 2)
        {
            bound.add(new CompiledBinding(nameAndSource[i],
                    CheckExpressionParser.parse(nameAndSource[i + 1]), List.of(),
                    net.cumba.corej.core.expr.eval.Domain.DATASET));
        }
        rule.setCompiledBindings(bound);
        return rule;
    }


    @Test
    void aSubstitutionOverAColumnIsAStringAtRecordLevel()
    {
        StageAReport report = check("AESTDTC ~ /(?i)^(.*)DTC$/$1/ == \"AEST\"");
        assertEquals(List.of(), report.findings(), messages(report));
        TypedExpr subject = subject(report);
        assertEquals("string", subject.type().describe());
        assertEquals(Level.RECORD, subject.level());
        // the literal is the right child, typed and never a finding of its own
        assertEquals(2, subject.children().size());
        assertEquals(Expr.LitKind.SUBSTITUTION,
                ((Expr.Lit) subject.children().get(1).node()).kind());
    }


    @Test
    void aNumericSubjectIsTheRegexMatchError()
    {
        StageAReport report = check("num(AESEQ) ~ /1/x/ == \"x\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(report));
        assertTrue(messages(report).contains("the left operand of ~ must be a string"),
                messages(report));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("empty((AESEQ + 1) ~ /1/x/)")), "an arithmetic subject");
    }


    @Test
    void aListSubjectIsAListOfStringsAndAnUnknownStaysUnknown()
    {
        StageAReport values = check("\"A\" in values([A, B]) ~ /a/A/");
        assertEquals(List.of(), values.findings(), messages(values));
        assertEquals("list<string>",
                values.typedLevels().get(Severity.ERROR).children().get(1).type().describe());
        StageAReport perRow = check("\"A\" in split_by(AETERM, \",\") ~ /a/A/");
        assertEquals(List.of(), perRow.findings(), messages(perRow));
        assertEquals("list<string>",
                perRow.typedLevels().get(Severity.ERROR).children().get(1).type().describe());
        Rule rule = withBindings("$l", "split_by(AETERM, \",\")", "$n", "[1, 2]");
        StageAReport bound = check(rule, "\"A\" in $l ~ /a/A/");
        assertEquals(List.of(), bound.findings(), messages(bound));
        assertEquals("list<string>",
                bound.typedLevels().get(Severity.ERROR).children().get(1).type().describe());
        StageAReport numbers = check(rule, "\"A\" in $n ~ /a/A/");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(numbers),
                "a list of numbers is not a string subject either");
        StageAReport unknown = check("$u ~ /a/A/ == \"A\"");
        assertEquals(List.of(), unknown.findings(), messages(unknown));
        assertEquals("unknown", subject(unknown).type().describe(),
                "a binding of unknown static type: list or scalar is decided at evaluation");
    }


    @Test
    void anInvalidPatternIsALoadError()
    {
        StageAReport report = check("AETERM ~ /(abc/x/ == \"x\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(report));
        assertTrue(messages(report).contains("not a valid regular expression"), messages(report));
    }


    @Test
    void aGroupReferenceThePatternDoesNotDefineIsALoadError()
    {
        StageAReport numbered = check("AETERM ~ /^(.*)DTC$/$2/ == \"x\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(numbered));
        assertTrue(messages(numbered).contains("$2"), messages(numbered));
        StageAReport named = check("AETERM ~ /^(?<stem>.*)DTC$/${root}/ == \"x\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(named));
        assertTrue(messages(named).contains("${root}"), messages(named));
        StageAReport noGroups = check("AETERM ~ /DTC/$1/ == \"x\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(noGroups));
        // a bare `$` and a dangling backslash are the replacement syntax errors Matcher would
        // throw per row — refused at load instead
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("AETERM ~ /a/$/ == \"x\"")), "a bare $");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("AETERM ~ /a/x\\\\/ == \"x\"")), "a dangling backslash");
    }


    @Test
    void everyGroupReferenceThePatternDefinesIsAccepted()
    {
        for (String ok : List.of("AETERM ~ /^(.*)DTC$/$1/ == \"x\"",
                "AETERM ~ /^(.*)DTC$/$0$1/ == \"x\"",
                "AETERM ~ /^(?<stem>.*)DTC$/${stem}/ == \"x\"",
                "AETERM ~ /^(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)$/$11$1/ == \"x\"",
                "AETERM ~ /a/\\$1/ == \"$1\"", "AETERM ~ /a/\\\\$1/ == \"x\"", "AETERM ~ /a//"))
        {
            StageAReport report = check(ok.endsWith("//") ? "empty(" + ok + ")" : ok);
            assertEquals(List.of(), report.findings(), ok + " -> " + messages(report));
        }
    }


    @Test
    void anEqualitySideBesideASubstitutionExpectsCharacterAsTheCompilerGatesIt()
    {
        // R5 B-L3: ExprCompiler.staticKind reads a substitution side as CHARACTER (its result is
        // a STRING), so the column-type gate expects text of the other side; the stage-A mirror
        // (TypeExpectations.staticKind) answers the same, so an absent AETERM reads as the
        // character default the gate would have demanded
        TypeExpectations expectations = TypeExpectations
                .of(List.of(CheckExpressionParser.parse("AETERM == X ~ /^(.*)DTC$/$1/")));
        assertEquals(java.util.Set.of(TypeExpectations.Expectation.CHARACTER),
                expectations.expectationsOf("AETERM"));
        TypeExpectations left = TypeExpectations
                .of(List.of(CheckExpressionParser.parse("X ~ /^(.*)DTC$/$1/ != AETERM")));
        assertEquals(java.util.Set.of(TypeExpectations.Expectation.CHARACTER),
                left.expectationsOf("AETERM"));
    }
}
