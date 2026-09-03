package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupKeyPolicy;
import net.cumba.corej.core.exec.GroupSemantics;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code ExprCompiler.compileUniqueSet} / {@code uniqueSetMembers} under owner requirement #1
 * (2026-08-23): the single-list spelling {@code is_unique_set([A, B, …])} compiles bit-for-bit to
 * the {@code GroupSemantics.uniqueSetViolations} primitive over the same member tuple (which is
 * what the pre-flattening {@code is_unique_set(A, keys=[B, …])} compiled to); a {@code $}-ref is
 * legal in position 0 (D-4's unlocked capability) and splices; {@code f([])} declines; the
 * {@code is_(not_)unique_value} adapter is untouched; and — since Plan A Phase 2 (ruling 2) — the
 * retired spellings are refused by the compiler (the authored surface is the load error; this is
 * the backstop).
 */
@ExtendWith(MockitoExtension.class)
class UniqueSetListOperandCompileTest
{

    /** Rows 0/1 share (USUBJID, DSSCAT, EPOCH); row 2 differs on EPOCH; row 3 on USUBJID. */
    private static IDataTable ds()
    {
        return MockTable.of().name("DS").col("USUBJID", "S1", "S1", "S1", "S2")
                .col("DSSCAT", "X", "X", "X", "X").col("EPOCH", "E1", "E1", "E2", "E1")
                .col("DSDTC", "2024-01-01T10", "2024-01-01T11", "2024-01-02", "2024-01-01").build();
    }


    private static BitSet eval(String expression, IDataTable t)
    {
        return eval(expression, t, Map.of());
    }


    private static BitSet eval(String expression, IDataTable t, Map<String, Object> vars)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(t).variables(vars).build());
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    private static BitSet primitive(IDataTable t, List<String> members, String regex,
            boolean flagDuplicates, GroupKeyPolicy policy)
    {
        return GroupSemantics.uniqueSetViolations(t, 4, members, regex, flagDuplicates, policy);
    }


    @Test
    void theListSpellingIsBitIdenticalToThePrimitiveOverTheSameTuple()
    {
        IDataTable t = ds();
        String date = "^\\d{4}-\\d{2}-\\d{2}";
        GroupKeyPolicy fold = GroupKeyPolicy.FOLD_BLANK_KEYS;
        assertEquals(bits(0, 1), eval("not is_unique_set([EPOCH, USUBJID, DSSCAT])", t));
        // {expression, expected-from-the-primitive} — the member tuple is exactly the list.
        record Case(String expression, BitSet expected)
        {
        }
        for (Case c : List.of(
                new Case("not is_unique_set([EPOCH, USUBJID, DSSCAT])",
                        primitive(t, List.of("EPOCH", "USUBJID", "DSSCAT"), null, true, fold)),
                new Case("not is_unique_set([EPOCH, USUBJID])",
                        primitive(t, List.of("EPOCH", "USUBJID"), null, true, fold)),
                new Case("not is_unique_set([USUBJID])",
                        primitive(t, List.of("USUBJID"), null, true, fold)),
                new Case("is_unique_set([EPOCH, USUBJID])",
                        primitive(t, List.of("EPOCH", "USUBJID"), null, false, fold)),
                new Case("is_not_unique_set([EPOCH, USUBJID])",
                        primitive(t, List.of("EPOCH", "USUBJID"), null, true, fold)),
                new Case("not is_unique_set([DSDTC, USUBJID], regex=\"" + date + "\")",
                        primitive(t, List.of("DSDTC", "USUBJID"), date, true, fold)),
                new Case("not is_unique_set([EPOCH, USUBJID], keep_missings=false)",
                        primitive(t, List.of("EPOCH", "USUBJID"), null, true,
                                fold.withKeepMissings(false))),
                // an absent member (ABSENT) anywhere drops and regroups
                new Case("not is_unique_set([ABSENT, USUBJID, DSSCAT])",
                        primitive(t, List.of("USUBJID", "DSSCAT"), null, true, fold)),
                new Case("not is_unique_set([EPOCH, ABSENT, USUBJID])",
                        primitive(t, List.of("EPOCH", "USUBJID"), null, true, fold))))
        {
            assertEquals(c.expected(), eval(c.expression(), t), c.expression());
        }
        // The regex case really normalises (rows 0/1 share the DATE of DSDTC and USUBJID).
        assertEquals(bits(0, 1),
                eval("not is_unique_set([DSDTC, USUBJID], regex=\"" + date + "\")", t));
        // The absent-member cases are real partitions, not empty verdicts.
        assertEquals(bits(0, 1, 2), eval("not is_unique_set([ABSENT, USUBJID, DSSCAT])", t));
    }


    @Test
    void aDollarRefInPositionZeroNowCompilesAndSplices()
    {
        IDataTable t = ds();
        Map<String, Object> vars = Map.of("$key", List.of("USUBJID", "DSSCAT"));
        // D-4's unlocked capability: the member list is one tuple, so a $-ref is legal FIRST.
        // groupOperandName refused it on the old first operand; the old spelling's keys=
        // position spliced it — both now give the (USUBJID, DSSCAT, EPOCH) verdict.
        assertEquals(bits(0, 1), eval("not is_unique_set([$key, EPOCH])", t, vars));
        assertEquals(eval("not is_unique_set([EPOCH, $key])", t, vars),
                eval("not is_unique_set([$key, EPOCH])", t, vars));
        // Unresolved in position 0: dropped, the check regroups on EPOCH alone (rows 0/1/3).
        assertEquals(bits(0, 1, 3), eval("not is_unique_set([$nothing, EPOCH])", t));
        // The $-ref alone, resolving to the whole tuple.
        assertEquals(bits(0, 1), eval("not is_unique_set([$key, EPOCH])", t, vars));
        assertEquals(bits(0, 1, 2), eval("not is_unique_set([$key])", t, vars));
    }


    @Test
    void theEmptyListAndMixedShapesDecline()
    {
        IDataTable t = ds();
        ExpressionException empty = assertThrows(ExpressionException.class,
                () -> eval("not is_unique_set([])", t));
        assertTrue(empty.getMessage().contains("has no members"), empty.getMessage());
        ExpressionException mixed = assertThrows(ExpressionException.class,
                () -> eval("not is_unique_set([EPOCH], keys=[USUBJID])", t));
        assertTrue(mixed.getMessage().contains("one list operand"), mixed.getMessage());
        assertThrows(ExpressionException.class, () -> eval("not is_unique_set()", t));
        // A member that is not a column/wildcard/literal/$-ref declines exactly as a key did.
        assertThrows(ExpressionException.class,
                () -> eval("not is_unique_set([length(EPOCH), USUBJID])", t));
    }


    @Test
    void theUniqueValueAdapterIsUntouched()
    {
        IDataTable t = ds();
        assertEquals(bits(0, 1, 2), eval("is_not_unique_value(USUBJID)", t));
        assertEquals(bits(3), eval("is_unique_value(USUBJID)", t));
        assertEquals(eval("is_not_unique_value(USUBJID)", t),
                eval("not is_unique_value(USUBJID)", t));
        // D-5: never flattened — a list is not a legal operand, nor is keys=.
        assertThrows(ExpressionException.class, () -> eval("is_not_unique_value([USUBJID])", t));
        assertThrows(ExpressionException.class,
                () -> eval("is_not_unique_value(USUBJID, keys=[EPOCH])", t));
    }


    @Test
    void theRetiredSpellingsAreRefusedByTheCompiler()
    {
        // Plan A Phase 2 (ruling 2): no deprecation window. The load-time RuleDefinitionException
        // is the authored surface (UniqueSetShapeLoadValidationTest); this is the backstop for an
        // Expr that reaches the compiler without passing through the loader.
        IDataTable t = ds();
        for (String retired : List.of("not is_unique_set(EPOCH, keys=[USUBJID, DSSCAT])",
                "not is_unique_set(EPOCH, USUBJID)", "not is_unique_set(EPOCH)",
                "is_not_unique_set(EPOCH, keys=[USUBJID])",
                "not is_unique_set(EPOCH, keys=[USUBJID], regex=\"^x\")"))
        {
            ExpressionException e = assertThrows(ExpressionException.class, () -> eval(retired, t),
                    retired);
            assertTrue(e.getMessage().contains("one list operand")
                    || e.getMessage().contains("must be a list literal"), e.getMessage());
        }
    }
}
