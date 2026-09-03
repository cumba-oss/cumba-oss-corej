package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@code RulePackageLoader.validateInlineUniqueSetShape} — owner requirement #1 (2026-08-23,
 * {@code plans/PLAN-authoring-grammar-unique-set-and-output-exclusion.md} §3.5 D-3 / §3.9(e)): the
 * retired {@code is_(not_)unique_set(name, keys=[…])} / {@code f(A, B)} / {@code f(A)} spellings
 * and the authored empty list are LOAD errors carrying the migration text; the canonical single
 * list operand loads and compiles.
 *
 * <p>
 * ⚠⚠ Both arms are asserted — accepting and rejecting — because a one-armed validator silently
 * becomes the only arm. Reachability: the validator enters an {@code Expr} only for a
 * {@link CheckConditionExpression} ({@code validateInlineMissingValues}); since Plan A Phase 2
 * {@code ExprLowering.functionOperatorLeaf} REFUSES the retired shapes for the pair, so the
 * deserializer keeps such a Check native and the validator is armed for every plain Check — which
 * is what {@link #rejected} proves by loading the bare old spelling with no conjunct (the Phase-1
 * {@code length()} device is kept only as a second arm, so the native-authored path stays covered).
 * </p>
 */
class UniqueSetShapeLoadValidationTest
{

    private static final String KEEP_NATIVE = " and length(USUBJID) > 3";

    private static Rule loadCheck(String expression)
    {
        String json = "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":\""
                + expression.replace("\"", "\\\"") + "\"}}}}";
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString(json);
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + json, e);
        }
    }


    /**
     * Loads {@code expression} bare AND under the native-keeping conjunct; both must be LOAD errors
     * carrying the same text. The bare arm is the one the shipped corpus would take — it proves the
     * lowering refusal arms the validator (plan §3.10 item 2).
     */
    private static String rejected(String expression)
    {
        Rule bare = loadCheck(expression);
        assertTrue(bare.getCheck() instanceof CheckConditionExpression,
                "⚠⚠ the retired spelling LOWERED — ExprLowering no longer refuses it, the old"
                        + " grammar survives and the validator is unreachable: " + bare.getCheck());
        String error = bare.getLoadError();
        assertNotNull(error, "must be a LOAD error: " + expression);
        assertFalse(error.contains("unknown operation function"),
                "⚠⚠ MISROUTING: a Check operator was handed to the OPERATION parser: " + error);
        assertNull(bare.getCheckExpr(), "a rejected rule must not compile: " + expression);

        Rule conjunct = loadCheck(expression + KEEP_NATIVE);
        assertTrue(conjunct.getCheck() instanceof CheckConditionExpression);
        assertEquals(error, conjunct.getLoadError(), "same text on the native-authored arm");
        return error;
    }


    @Test
    void theRetiredSpellingsAreLoadErrorsWithTheMigrationText()
    {
        String keys = rejected("not is_unique_set(USUBJID, keys=[DOMAIN])");
        assertTrue(keys.contains("`is_unique_set` no longer takes keys="), keys);
        assertTrue(keys.contains("write is_unique_set([TARGET, KEY, …])"), keys);
        assertTrue(keys.contains("the first member has no special meaning"), keys);

        String twoPositional = rejected("not is_unique_set(IETESTCD, DOMAIN)");
        assertTrue(twoPositional.contains("takes exactly one list operand"), twoPositional);
        assertTrue(twoPositional.contains("write is_unique_set([TARGET, KEY, …])"), twoPositional);

        String bare = rejected("not is_unique_set(ETCD)");
        assertTrue(bare.contains("operand must be a list literal"), bare);

        String negativeTwin = rejected("is_not_unique_set(USUBJID, keys=[DOMAIN])");
        assertTrue(negativeTwin.contains("`is_not_unique_set` no longer takes keys="),
                negativeTwin);

        String mixed = rejected("not is_unique_set([USUBJID], keys=[DOMAIN])");
        assertTrue(mixed.contains("no longer takes keys="), mixed);
    }


    @Test
    void theAuthoredEmptyListIsALoadError()
    {
        String empty = rejected("not is_unique_set([])");
        assertTrue(empty.contains("`is_unique_set([])` has no members"), empty);
        assertTrue(rejected("is_not_unique_set([])").contains("has no members"));
    }


    @Test
    void theCanonicalSingleListOperandLoadsAndCompiles()
    {
        for (String ok : new String[]
        {
                "not is_unique_set([USUBJID, DOMAIN])", "not is_unique_set([ETCD])",
                "is_not_unique_set([USUBJID, --TESTCD])",
                "not is_unique_set([USUBJID, DSCAT], keep_missings=false)",
                "not is_unique_set([--REPNUM, USUBJID], regex=\"^\\\\d{4}\")"
        })
        {
            Rule nativeRule = loadCheck(ok + KEEP_NATIVE);
            assertTrue(nativeRule.getCheck() instanceof CheckConditionExpression, ok);
            assertNull(nativeRule.getLoadError(), ok + ": " + nativeRule.getLoadError());
            assertNotNull(nativeRule.getCheckExpr(), ok + ": the native expr must be installed");

            // And on the lowering path (no conjunct): a leaf, re-raised and compiled.
            Rule lowered = loadCheck(ok);
            assertNull(lowered.getLoadError(), ok + ": " + lowered.getLoadError());
            assertNotNull(lowered.getCheckExpr(), ok + ": the native expr must be installed");
        }
    }


    @Test
    void aDollarMemberIsStillSeenByTheDanglingReferenceCheck()
    {
        // D-2's point: the leaf keeps name/value, so collectOperandRefs reads a $-member of the
        // single-list spelling exactly as it read a keys= member — an undefined operation is a
        // load error, not a silently empty tuple member.
        Rule dangling = loadCheck("not is_unique_set([USUBJID, --TESTCD, $natural_key])");
        assertNotNull(dangling.getLoadError());
        assertTrue(dangling.getLoadError().contains("$natural_key"), dangling.getLoadError());
        Rule danglingFirst = loadCheck("not is_unique_set([$natural_key, USUBJID])");
        assertNotNull(danglingFirst.getLoadError(), "position 0 is a member like any other");
        assertTrue(danglingFirst.getLoadError().contains("$natural_key"),
                danglingFirst.getLoadError());
    }


    @Test
    void otherOperatorsAreNotTouched()
    {
        // The validator is scoped to the pair: keys= on its siblings, and a list first operand
        // on an unrelated function, are not its business.
        Rule sibling = loadCheck(
                "is_inconsistent_across_dataset(VISIT, keys=[VISITNUM])" + KEEP_NATIVE);
        assertNull(sibling.getLoadError(), String.valueOf(sibling.getLoadError()));
        Rule relationship = loadCheck(
                "not is_unique_relationship(USUBJID, keys=[SUBJID])" + KEEP_NATIVE);
        assertNull(relationship.getLoadError(), String.valueOf(relationship.getLoadError()));
    }
}
