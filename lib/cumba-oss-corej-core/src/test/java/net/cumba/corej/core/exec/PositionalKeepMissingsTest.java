package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Review round 2 L-12 of {@code PLAN-function-surface-wave1}: the pre-existing compiler-dispatched
 * grouped readers read {@code keep_missings} from the call's <b>keywords</b> only, while the
 * descriptor Stage A binds them through ({@code CompilerDispatchedCalls}) declares it as an
 * ordinary optional parameter — so a <b>positional</b> {@code keep_missings} bound cleanly and was
 * then silently ignored (the rule ran on its default missing-key disposition). Wave 1's two ported
 * callables read the bound slot instead (round 1 M1, {@code GroupedPredicatesTest}); these older
 * readers belong to W8's strict-binding sweep, so the minimal honest fix is a load error that names
 * the keyword spelling. The keyword spelling keeps loading.
 */
class PositionalKeepMissingsTest
{

    @Test
    void aPositionalKeepMissingsOnHasMultipleValuesForIsALoadError() throws Exception
    {
        // Measured per reader (round 2): has_multiple_values_for is the one older reader that
        // bound a positional keep_missings and ignored it (its `within` slot before it is
        // optional, so `(A, K, W, true)` bound cleanly and ran on the default disposition). Every
        // other reader was already loud — see theOtherReadersWereAlreadyLoud.
        String check = "has_multiple_values_for(A, K, W, true)";
        Rule rule = load(check);
        assertNotNull(rule.getLoadError(),
                check + " must not load: its keep_missings would be bound and silently ignored");
        // Runbook W8 (D-W8-7) widened the guard to `within`, which the same call also binds by
        // position, so one load error names both keyword spellings.
        assertTrue(
                rule.getLoadError()
                        .contains("on has_multiple_values_for are read by keyword"
                                + " only — write within=W, keep_missings=true"),
                rule.getLoadError());
    }


    @Test
    void theOtherReadersWereAlreadyLoud() throws Exception
    {
        // A positional keep_missings on each of these fails on the reader's OWN diagnostic, before
        // (and independently of) the L-12 guard: the unique-set readers take one list operand,
        // is_inconsistent_across_dataset reads every later positional as a key column, and the
        // rest reach the slot only by passing a REQUIRED keyword-read parameter (ordering /
        // within / by) by position, which they refuse. The negation-dispatched pair compiles
        // only under `not`.
        for (String check : List.of("is_unique_set([A, B], \\\"x\\\", true)",
                "is_not_unique_set([A, B], \\\"x\\\", false)",
                "is_inconsistent_across_dataset(A, K, [B], false, true)",
                "empty_within_except_last_row(A, G, O, true)",
                "present_on_multiple_rows_within(A, W, true)",
                "not has_next_corresponding_record(A, B, W, O, \\\"x\\\", true)",
                "not is_sorted_by(T, [B], W, true)"))
        {
            Rule rule = load(check);
            assertNotNull(rule.getLoadError(), check);
            assertFalse(rule.getLoadError().contains("read by keyword only"),
                    check + " was loud on its own: " + rule.getLoadError());
        }
    }


    @Test
    void theKeywordSpellingStillLoads() throws Exception
    {
        for (String check : List.of("has_multiple_values_for(A, K, within=W, keep_missings=true)",
                "is_unique_set([A, B], keep_missings=true)",
                "is_inconsistent_across_dataset(A, K, keep_missings=true)",
                "empty_within_except_last_row(A, G, ordering=O, keep_missings=true)",
                "present_on_multiple_rows_within(A, within=W, keep_missings=true)"))
        {
            Rule rule = load(check);
            assertNull(rule.getLoadError(), check + " → " + rule.getLoadError());
        }
    }


    private static Rule load(String check) throws Exception
    {
        // A compiled binding, as a corpus rule authors it: its compile failure is the rule's
        // load error (a Check that fails to compile is reported as a per-rule ERROR at run time).
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-KEEP\"},"
                + "\"Bindings\":[{\"name\":\"$p\",\"expression\":\"" + check + "\"}],"
                + "\"Check\":{\"expression\":\"$p == true\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule parses");
        return rule;
    }

}
