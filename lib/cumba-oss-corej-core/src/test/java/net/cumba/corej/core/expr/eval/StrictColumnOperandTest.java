package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Runbook W8 ({@code PLAN-retire-operation-surface} D-W8-6 / D-W8-7): R1 on the pre-existing
 * compiler-dispatched readers. Until W8 {@code ExprCompiler.groupOperandName} read a quoted
 * {@code "X"} as the column {@code X} — the one place on the function surface where a string
 * literal was still a name (runbook §7) — and the {@code is_sorted_by} sort-key descriptor
 * <em>required</em> the quoted form ({@code asc("X")}). A quoted name is a string, never a column
 * (R1, owner 2026-09-22: no compatibility arm), so both now fail to load naming the bare spelling.
 * And the optional {@code within} of {@code has_multiple_values_for} was bound positionally and
 * silently ignored (the same shape as L-12's positional {@code keep_missings}); it is a load error
 * naming the keyword.
 */
class StrictColumnOperandTest
{

    @Test
    void aQuotedSortKeyIsALoadErrorNamingTheBareSpelling() throws Exception
    {
        Rule rule = load("not is_sorted_by(SEQ, by=[asc(\\\"STDTC\\\")], within=USUBJID)");
        assertNotNull(rule.getLoadError(),
                "asc(\"STDTC\") must not load: a quoted name is a string");
        assertTrue(rule.getLoadError().contains("asc(STDTC)"), rule.getLoadError());
    }


    @Test
    void aQuotedGroupOperandIsALoadErrorNamingTheBareSpelling() throws Exception
    {
        for (String check : List.of("has_same_values(\\\"AESEV\\\")",
                "has_multiple_values_for(\\\"COHORTN\\\", COHORT)",
                "has_multiple_values_for(COHORTN, COHORT, within=[\\\"USUBJID\\\"])",
                "is_inconsistent_across_dataset(AESEV, keys=[\\\"USUBJID\\\"])",
                "present_on_multiple_rows_within(AETERM, within=\\\"USUBJID\\\")"))
        {
            Rule rule = load(check);
            assertNotNull(rule.getLoadError(), check + " must not load: a quoted name is a string");
            assertTrue(rule.getLoadError().contains("a quoted name is a string, never a column"),
                    check + " → " + rule.getLoadError());
        }
    }


    @Test
    void aPositionalWithinOnHasMultipleValuesForIsALoadError() throws Exception
    {
        // The second spelling is the combined review's W8 M1: a keyword keep_missings= used to
        // skip the positional check (it ran only when keep_missings= was absent), so the call
        // loaded and ran UNGROUPED.
        for (String check : List.of("has_multiple_values_for(COHORTN, COHORT, USUBJID)",
                "has_multiple_values_for(COHORTN, COHORT, USUBJID, keep_missings=true)"))
        {
            Rule rule = load(check);
            assertNotNull(rule.getLoadError(),
                    check + " must not load: its within would be bound and silently ignored");
            assertTrue(rule.getLoadError().contains(
                    "`within` on has_multiple_values_for is read by keyword only — write within=USUBJID"),
                    check + " → " + rule.getLoadError());
        }
    }


    @Test
    void aMultiColumnWithinOnIsSortedByIsALoadError() throws Exception
    {
        // Combined review of runbook W2–W8, round 2 (corpus M4): the plan reads ONE within
        // column, and a multi-element list used to compile to within=null — the call loaded and
        // ran UNGROUPED, chaining one subject's last row to the next subject's first.
        Rule rule = load("not is_sorted_by(SEQ, by=[asc(STDTC)], within=[STUDYID, USUBJID])");
        assertNotNull(rule.getLoadError(), "a two-column within= must not load");
        assertTrue(rule.getLoadError().contains("is_sorted_by's within= takes one column"),
                rule.getLoadError());
    }


    @Test
    void theBareSpellingsLoad() throws Exception
    {
        for (String check : List.of("not is_sorted_by(SEQ, by=[asc(STDTC)], within=USUBJID)",
                "not is_sorted_by(SEQ, by=[asc(STDTC)], within=[USUBJID])",
                "not is_sorted_by(--SEQ, by=[asc(--STDTC), desc(--ENDTC)], within=USUBJID)",
                "has_same_values(AESEV)", "has_multiple_values_for(COHORTN, COHORT)",
                "has_multiple_values_for(COHORTN, COHORT, within=USUBJID)",
                "has_multiple_values_for(COHORTN, COHORT, within=[USUBJID, [VISIT, VISITNUM]])",
                "is_inconsistent_across_dataset(AESEV, keys=[USUBJID])",
                "present_on_multiple_rows_within(AETERM, within=USUBJID)"))
        {
            Rule rule = load(check);
            assertNull(rule.getLoadError(), check + " → " + rule.getLoadError());
        }
    }


    private static Rule load(String check) throws Exception
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W8-STRICT\"},"
                + "\"Bindings\":[{\"name\":\"$p\",\"expression\":\"" + check + "\"}],"
                + "\"Check\":{\"expression\":\"$p == true\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule parses");
        return rule;
    }

}
