package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.DefinePresence;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.ProviderNeeds;
import net.cumba.corej.core.exec.StudyRuleClassifier;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-engine-gap-define-presence} §4 (runbook W1b DP1a, owner-ruled 2026-10-07):
 * {@code define_available()} is true when a Define-XML was <b>supplied</b> to the run — also one
 * that failed to parse (Q2: the parse failure is DP1b's study-level ERROR, not an absence) — and
 * false otherwise; authorable (Q4), study level, carrying no provider need so the rule that reports
 * the absence is never SKIPPED for it.
 */
class DefineAvailableTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext ctx(DefinePresence presence)
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2").build();
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("DM")
                .definePresence(presence).build();
    }


    @Test
    void itAnswersThePresenceFactNotTheProvider()
    {
        BitSet absent = NativeExprEvaluator.evaluate(parse("define_available()"),
                ctx(DefinePresence.ABSENT));
        assertEquals(0, absent.cardinality(), "no define supplied -> false on every row");
        BitSet parsed = NativeExprEvaluator.evaluate(parse("define_available()"),
                ctx(DefinePresence.PARSED));
        assertEquals(2, parsed.cardinality());
        // Q2: a supplied define that failed to parse WAS supplied — the presence rule must not
        // report it as absent (DP1b reports the parse failure)
        BitSet broken = NativeExprEvaluator.evaluate(parse("define_available()"),
                ctx(DefinePresence.UNPARSEABLE));
        assertEquals(2, broken.cardinality());
        BitSet negated = NativeExprEvaluator.evaluate(parse("not define_available()"),
                ctx(DefinePresence.ABSENT));
        assertEquals(2, negated.cardinality(), "the presence rule's own spelling");
        assertEquals(DefinePresence.ABSENT,
                EvaluationContext.builder().table(MockTable.of().col("A", "x").build())
                        .datasetResolver(_ -> null).build().getDefinePresence(),
                "a context built without the fact reads ABSENT");
    }


    @Test
    void itDeclaresNoProviderNeedAndIsAStudyLevelFact()
    {
        FunctionDescriptor d = FunctionRegistry.descriptor("define_available");
        assertNotNull(d);
        assertEquals(List.of(), d.parameters(), "arity 0");
        assertEquals(FunctionKind.BOOLEAN, d.kind());
        assertNull(d.provider(),
                "no provider need: the rule is never SKIPPED for the absence it" + " tests");
        ProviderNeeds needs = ProviderNeeds.ofExpr(parse("not define_available()"));
        assertFalse(needs.define(), "a rule reading only define_available() needs no define");
        assertFalse(needs.library());
        assertTrue(StudyRuleClassifier.isStudyLevelFunction("define_available"),
                "one finding per study, like dataset_names()");
        Expr.Call call = (Expr.Call) parse("define_available()");
        assertTrue(BroadcastFold.isLibraryGateCall(call),
                "a run constant: folded like library_available()");
        assertFalse(
                BroadcastFold.isLibraryGateCall(
                        new Expr.Call("define_available", List.of(parse("X")), java.util.Map.of())),
                "only the nullary spelling is the gate");
    }


    @Test
    void theSurfaceListsIt()
    {
        String surface = net.cumba.corej.core.RuleLanguage.surface();
        assertTrue(surface.contains("\ndefine_available() kind=boolean\n"), surface);
    }
}
