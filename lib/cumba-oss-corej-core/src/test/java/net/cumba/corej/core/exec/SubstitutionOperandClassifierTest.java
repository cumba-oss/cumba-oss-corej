package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 RC-SUBST (from the W1b fold): the substitution operator {@code X ~ /re/repl/} in a
 * Check operand position reads its left side's columns — {@code RuleClassifier.operandText} reads a
 * SUBST binary's left operand. Before, {@code not is_increasing($m ~ /x/y/)} left the classifier no
 * column: the Check read signal-free, no Sensitivity was derived and the runner collapsed the
 * per-row findings to one. 0 corpus carriers of {@code ~}.
 */
class SubstitutionOperandClassifierTest
{

    /** Rows 1 and 3 hold a decreasing list. */
    private static IDataTable xx()
    {
        return RealTableFixture.of("XX").str("USUBJID", "S1", "S2", "S3")
                .str("L", "3,2", "1,2", "5,4").build();
    }


    private static Rule load(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"}");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\"}}}}");
        try
        {
            Rule r = RulePackageLoader.loadFromString(b.toString()).getRules().get("R");
            assertNotNull(r);
            assertNull(r.getLoadError(), () -> check + " -> " + r.getLoadError());
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static List<Long> fired(Rule rule)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, xx());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    @Test
    void aSubstitutedListInACheckOperandIsReadPerRow()
    {
        for (Rule rule : List.of(
                load("not is_increasing($m ~ /^(.*)$/$1/)", "$m", "split_by(L, \",\")"),
                load("not is_increasing(split_by(L, \",\") ~ /^(.*)$/$1/)")))
        {
            RuleClassifier.Derived<Sensitivity> d = RuleClassifier.deriveSensitivity(rule);
            assertEquals(Sensitivity.RECORD, d.value(), rule.getCheck() + ": " + d.rationale());
            assertEquals(List.of(1L, 3L), fired(rule), "one finding per decreasing row");
        }
    }


    @Test
    void theBriefsSpellingClassifiesRecord()
    {
        // (a pattern that never matches answers a list of missings, which is_increasing reads as
        // true — no finding; the classification is what this pins)
        Rule rule = load("not is_increasing($m ~ /x/y/)", "$m", "split_by(L, \",\")");
        RuleClassifier.Derived<Sensitivity> d = RuleClassifier.deriveSensitivity(rule);
        assertEquals(Sensitivity.RECORD, d.value(), d.rationale());
    }


    @Test
    void aSubstitutedColumnInAComparisonIsReadPerRow()
    {
        Rule rule = load("L ~ /^([0-9]+),.*$/$1/ == \"3\"");
        RuleClassifier.Derived<Sensitivity> d = RuleClassifier.deriveSensitivity(rule);
        assertEquals(Sensitivity.RECORD, d.value(), d.rationale());
        assertEquals(List.of(1L), fired(rule));
    }
}
