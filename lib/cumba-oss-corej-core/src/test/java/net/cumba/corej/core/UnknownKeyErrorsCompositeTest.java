package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The public composite {@link RulePackageLoader#unknownKeyErrors(Rule)} — the one entry point a
 * reader that never runs {@code finishLoad} (the corpus repos' rulespec {@code RuleScaffold}) uses
 * — agrees with the loader's own per-rule arms and composes <b>only</b> the unknown-key arms
 * (review E10 of {@code PLAN-rule-unknown-keys-gate}): R1, R2's unknown-key arm, the
 * {@code Match_Datasets} gate, the parked {@code Check} / {@code Precondition} grammar errors, the
 * retired and threshold spellings, and the walker — never R3 / R4 or any other gate.
 */
class UnknownKeyErrorsCompositeTest
{

    private static Rule load(String members) throws IOException
    {
        Rule rule = RulePackageLoader
                .loadFromString(KeyedJoinFixtures.declared("{\"rules\":{\"x\":{" + members + "}}}"))
                .getRules().get("x");
        assertNotNull(rule);
        return rule;
    }


    private static String body(String extra)
    {
        return "\"Core\":{\"Id\":\"T-COMP\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}"
                + (extra.isEmpty() ? "" : "," + extra);
    }


    @Test
    void theCompositeIsExactlyTheLoadersUnknownKeyMessages() throws IOException
    {
        // One carrier per arm, on one rule: the composite must yield every message the loader
        // put on loadError for these keys, and nothing the loader did not.
        Rule rule = load("\"Core\":{\"Id\":\"T-COMP\",\"Versoin\":1},\"Sensitivity\":\"Record\","
                + "\"_wildcards\":{},\"Severity_Threshold\":\"Error\",\"Outcom\":1,"
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]},\"Variables\":{\"Include\":[\"A\"]},"
                + "\"domians\":{}}," + "\"Requirements\":{\"Variables\":{\"Al\":[\"AESEQ\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_type\":\"left\"}],"
                + "\"Precondition\":{\"expression\":\"library_available()\",\"P\":1},"
                + "\"Outcome\":{\"Mesage\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\",\"Mesage\":\"m\"}");
        List<String> composite = RulePackageLoader.unknownKeyErrors(rule);
        String loadError = rule.getLoadError();
        assertNotNull(loadError);
        assertTrue(composite.size() >= 10, "arms reached: " + composite);
        for (String message : composite)
        {
            assertTrue(loadError.contains(message),
                    "the composite reports a message the loader did not: " + message);
        }
        // every unknown-key message of the loader is in the composite (the loader's other gates
        // may add more — here none applies, so the sets are equal)
        List<String> loaderMessages = List.of(loadError.split("; (?=\\[T-COMP\\])", -1));
        assertEquals(loaderMessages.size(), composite.size(),
                "loader: " + loaderMessages + "\ncomposite: " + composite);
        for (String key : List.of("'Versoin'", "'_wildcards'", "'Severity_Threshold'", "'Outcom'",
                "'Scope.Variables'", "'domians'", "'Al'", "'Join_type'", "'P'", "'Mesage'"))
        {
            assertTrue(composite.stream().anyMatch(m -> m.contains(key)), key + " in " + composite);
        }
    }


    @Test
    void theCompositeExcludesR3AndR4() throws IOException
    {
        // R3 (an empty requirement entry) and R4 (a one-entry Any group) are shape gates the 17
        // inline rulespec specs were never run against (review M2): the loader reports them, the
        // composite must not.
        Rule rule = load(
                body("\"Requirements\":{\"Variables\":{\"All\":[\"\"],\"Any\":[[\"AETERM\"]]}}"));
        String loadError = rule.getLoadError();
        assertNotNull(loadError, "R3 / R4 fire in the loader: " + loadError);
        assertEquals(List.of(), RulePackageLoader.unknownKeyErrors(rule),
                "the composite composes only the unknown-key arms");
    }


    @Test
    void aCleanRuleYieldsNothing() throws IOException
    {
        Rule rule = load(body(""));
        assertEquals(List.of(), RulePackageLoader.unknownKeyErrors(rule));
    }
}
