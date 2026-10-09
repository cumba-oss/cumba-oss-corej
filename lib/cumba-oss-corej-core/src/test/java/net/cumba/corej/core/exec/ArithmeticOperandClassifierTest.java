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
 * Runbook W2 fold (R5 PC-2): an arithmetic binary in a Check operand position reads its operands'
 * columns — {@code RuleClassifier.operandText} reads the binary's left operand, else its right, as
 * RC-SUBST reads a substitution's left side. Before, {@code A + B > 3} left the classifier no
 * column: the Check read signal-free, no Sensitivity was derived and the runner collapsed the
 * per-row findings to one. Corpus census: the arithmetic operands of both corpora (the ADaM change
 * rules, {@code CHG != (AVAL - BASE)} and their twins) all sit beside a column-reading conjunct, so
 * no Check is signal-free through one — 0 carriers.
 */
class ArithmeticOperandClassifierTest
{

    /** A + B: 2, 6, 5 — rows 2 and 3 exceed 3. */
    private static IDataTable xx()
    {
        return RealTableFixture.of("XX").str("USUBJID", "S1", "S2", "S3").dbl("A", 1.0, 5.0, 2.0)
                .dbl("B", 1.0, 1.0, 3.0).build();
    }


    private static Rule load(String check)
    {
        String json = "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"},\"Check\":{\"expression\":\""
                + check.replace("\"", "\\\"") + "\"},\"Outcome\":{\"Message\":\"m\"}}}}";
        try
        {
            Rule r = RulePackageLoader.loadFromString(json).getRules().get("R");
            assertNotNull(r);
            assertNull(r.getLoadError(), r.getLoadError());
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
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
    void anArithmeticOperandIsReadPerRow()
    {
        for (String check : List.of("A + B > 3", "3 < A + B", "(A + B) * 2 > 6",
                "B * (A - 0) + B > 3"))
        {
            Rule rule = load(check);
            RuleClassifier.Derived<Sensitivity> d = RuleClassifier.deriveSensitivity(rule);
            assertEquals(Sensitivity.RECORD, d.value(), check + ": " + d.rationale());
            assertEquals(List.of(2L, 3L), fired(rule), check + ": one finding per row");
        }
    }


    @Test
    void anOperandAlreadyNamedKeepsItsReading()
    {
        // the ADaM change-rule shape: the left operand names the column, the arithmetic right
        // side adds the columns it reads; the derivation is the column's, as before
        Rule rule = load("A != (B - 1)");
        RuleClassifier.Derived<Sensitivity> d = RuleClassifier.deriveSensitivity(rule);
        assertEquals(Sensitivity.RECORD, d.value(), d.rationale());
        assertEquals(List.of(1L, 2L), fired(rule));
    }
}
