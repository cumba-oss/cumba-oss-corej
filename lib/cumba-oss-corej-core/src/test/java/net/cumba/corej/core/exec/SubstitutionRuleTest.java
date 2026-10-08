package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The substitution operator {@code X ~ /pattern/replacement/} seen from a rule
 * ({@code PLAN-engine-gap-string-extract} §3, runbook W1b SX): a rule using it loads clean and
 * fires on the extracted value; a missing subject keeps its identity through the operator (D85c);
 * over a list the result keeps every element's position; and the load errors — a group reference
 * the pattern does not define, a right side that is not a substitution literal, a regex literal
 * without the replacement part.
 */
class SubstitutionRuleTest
{

    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S1", "S2")
                .str("X", "AESTDTC", null, "AETERM").str("L", "AESTDTC,AETERM", "y", null).build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-SUBST\"},\"Sensitivity\":\"Record\"");
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
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputs.size(); i++)
        {
            b.append(i == 0 ? "" : ",").append('"').append(esc(outputs.get(i))).append('"');
        }
        return b.append("]}}").toString();
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            Rule r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static RuleExecutionResult run(String ruleJson)
    {
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = ae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary), primary.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> ruleJson + " -> " + result.getStatusMessage());
        return result;
    }


    private static List<Long> fired(String check, String... bindings)
    {
        RuleExecutionResult result = run(
                json("not empty(USUBJID) and (" + check + ")", List.of("USUBJID"), bindings));
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(json(check, List.of("USUBJID"), bindings)).getLoadError());
    }


    /**
     * The message of a check that does not even parse: the loader throws (the rule file is
     * unreadable), it does not record a load error on the rule.
     */
    private static String parseFailure(String check)
    {
        try
        {
            RulePackageLoader.loadFromString(KeyedJoinFixtures
                    .declared("{\"rules\":{\"R\":" + json(check, List.of("USUBJID")) + "}}"));
        }
        catch (java.io.IOException e)
        {
            return String.valueOf(e.getMessage());
        }
        throw new AssertionError("expected a parse failure: " + check);
    }


    /** {@code $v} bound to {@code binding}, reported on every row: its rendering per row. */
    private static List<String> rendered(String binding)
    {
        RuleExecutionResult result = run(json("not empty(USUBJID)", List.of("$v"), "$v", binding));
        List<String> out = new ArrayList<>();
        result.getViolations().forEach(v -> out.add(v.getValues().get("$v")));
        return out;
    }


    @Test
    void aRuleExtractsWithTheOperatorAndFiresOnTheResult()
    {
        assertEquals(List.of(1L), fired("X ~ /^(.*)DTC$/$1/ == \"AEST\""));
        assertEquals(List.of(1L), fired("$stem == \"AEST\"", "$stem", "X ~ /^(.*)DTC$/$1/"),
                "through a binding");
        // row 2 (missing X) and row 3 (no match) are both missing: neither fires a comparison,
        // both are empty
        assertEquals(List.of(2L, 3L), fired("empty(X ~ /^(.*)DTC$/$1/)"));
        assertEquals(List.of(3L), fired("coalesce(X ~ /^(.*)DTC$/$1/, X) == \"AETERM\""),
                "replace-or-keep: the no-match row keeps its input");
    }


    @Test
    void aMissingSubjectKeepsItsIdentity()
    {
        List<String> substituted = rendered("X ~ /^(.*)DTC$/$1/");
        List<String> plain = rendered("X");
        assertEquals("AEST", substituted.get(0));
        assertEquals(plain.get(1), substituted.get(1),
                "the missing row renders as the input's own missing, unchanged");
    }


    @Test
    void aListSubjectKeepsEveryElementsPosition()
    {
        List<String> folded = rendered("split_by(L, \",\") ~ /^(.*)DTC$/$1/");
        List<String> unfolded = rendered("split_by(L, \",\")");
        assertEquals("[AEST, .]", folded.get(0),
                "the no-match element is missing at its position, the list keeps its length");
        assertEquals("[.]", folded.get(1), "a one-element row: no match");
        assertEquals(unfolded.get(2), folded.get(2), "a missing row is that missing, unchanged");
        assertEquals(List.of(1L), fired("\"AEST\" in split_by(L, \",\") ~ /^(.*)DTC$/$1/"));
    }


    @Test
    void theLoadErrors()
    {
        String badGroup = loadError("X ~ /^(.*)DTC$/$2/ == \"AEST\"");
        assertTrue(badGroup.contains("$2"), badGroup);
        String badName = loadError("X ~ /^(?<stem>.*)DTC$/${root}/ == \"AEST\"");
        assertTrue(badName.contains("${root}"), badName);
        String badPattern = loadError("X ~ /^(.*DTC$/$1/ == \"AEST\"");
        assertTrue(badPattern.contains("not a valid regular expression"), badPattern);
        String notALiteral = parseFailure("X ~ \"DTC\" == \"AEST\"");
        assertTrue(notALiteral.contains("/pattern/replacement/"), notALiteral);
        String noReplacement = parseFailure("X ~ /DTC/ == \"AEST\"");
        assertTrue(noReplacement.contains("Unterminated substitution literal"), noReplacement);
        String numeric = loadError("num(X) ~ /1/x/ == \"x\"");
        assertTrue(numeric.contains("the left operand of ~ must be a string"), numeric);
        String bare = loadError("X ~ /a/b/");
        assertTrue(bare.contains("boolean"), bare);
    }
}
