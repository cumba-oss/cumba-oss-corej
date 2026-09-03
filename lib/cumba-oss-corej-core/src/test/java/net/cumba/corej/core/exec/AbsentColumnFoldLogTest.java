package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EC-43 — the absent-column fold is <b>visible</b>, and its visibility is <b>bounded</b>.
 *
 * <p>
 * Absence is legitimate (the operator computes its own polarity over the all-missing column), so
 * the engine says so at INFO rather than warning. The bound is the point of this test: <b>one
 * aggregated line per (rule, dataset)</b> — not one per leaf, per evaluation or per row. That is
 * why the record lives on the {@link EvaluationContext} and is drained by
 * {@code RuleRunner.logAbsentColumnFolds}: {@code NativeExprEvaluator} caches one compiled program
 * per {@code Expr} in a <em>static</em> map shared across datasets and threads, so a latch in the
 * plan's closure would log the first dataset only and race.
 * </p>
 */
class AbsentColumnFoldLogTest
{

    /** TS with TSPARMCD populated over 4 rows; TSVAL and TSVALNF absent. */
    private static IDataTable ts()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C", "D").build();
    }


    /**
     * A negative leaf that <b>fires over the fold and carries no injected guard</b> — the fixture
     * requirement of this whole test: if the leaf were guarded, {@code var_exists} would
     * short-circuit it, the fold would never run, and every assertion here would pass vacuously.
     *
     * <p>
     * ⚠ This used to be {@code not_equal_to} against a {@code value_is_literal: true} string, which
     * was unguarded only because {@code NonEmptyGuardInliner} (deleted 2026-08-26) left
     * column-vs-literal negatives alone. Triage finding S5 closed that gap, so the shape now
     * suppresses instead of folding. {@code is_not_integer} lowers to a <em>structural</em>
     * negation ({@code not is_integer(X)}), for which the injector contributes no columns — the
     * documented unguarded-negative family (32 {@code is_integer} leaves ship this way) — so the
     * fold stays observable. {@link #guardedLeavesNeverReachTheFold} pins that the distinction is
     * real.
     * </p>
     */
    private static CheckConditionLeaf foldingNegative(String column)
    {
        return CheckConditionLeaf.builder().name(column).operator("is_not_integer").build();
    }


    /**
     * A second, structurally distinct unguarded negative — {@code does_not_contain} lowers to
     * {@code not contains(X, "…")}, another {@code Not(Call)} the injector contributes no columns
     * for. Two <em>different</em> leaves, so nothing can collapse them into one and make "both
     * leaves fired" a statement about a single leaf.
     */
    private static CheckConditionLeaf doesNotContain(String column, String value)
    {
        return CheckConditionLeaf.builder().name(column).operator("does_not_contain")
                .value(new TextNode(value)).build();
    }


    private static CheckConditionLeaf neq(String column, String value)
    {
        return CheckConditionLeaf.builder().name(column).operator("not_equal_to")
                .value(new TextNode(value)).valueIsLiteral(true).build();
    }


    private static Rule rule(String id, net.cumba.corej.core.model.CheckCondition check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of());
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    /** Runs {@code body} with an INFO-level handler attached to {@link RuleRunner}'s logger. */
    private static List<String> capture(Runnable body)
    {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger juli = Logger.getLogger(RuleRunner.class.getName());
        Level previous = juli.getLevel();
        juli.addHandler(handler);
        juli.setLevel(Level.ALL);
        try
        {
            body.run();
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
        return handler.formatted().stream().filter(l -> l.contains("EC-43")).toList();
    }


    @Test
    @DisplayName("one INFO line per (rule, dataset) — not per row, not per leaf")
    void oneLinePerRuleAndDataset()
    {
        // Four rows and TWO leaves over the same absent column: a per-row or per-leaf log would
        // emit 4 or 8 lines. Both leaves fire, so the fold really did run for each of them.
        Rule twoLeaves = rule("CORE-LOG-1", new CheckConditionAny(
                List.of(foldingNegative("TSVAL"), doesNotContain("TSVAL", "X"))));

        List<String> lines = capture(() -> RuleRunner.execute(twoLeaves, ts()));

        assertEquals(1, lines.size(), () -> "expected exactly one aggregated line, got " + lines);
        assertTrue(lines.getFirst().contains("CORE-LOG-1"), lines::toString);
        assertTrue(lines.getFirst().contains("TSVAL"), lines::toString);
        assertTrue(lines.getFirst().contains("TS"), () -> "must name the dataset: " + lines);
    }


    @Test
    @DisplayName("the line aggregates every folded column, sorted, and re-arms per dataset")
    void aggregatesColumnsAndRepeatsPerDataset()
    {
        // `all`, not `any`: an OR short-circuits once the first disjunct fires every row, so the
        // second column would never be reached — which is itself the fold staying lazy.
        Rule twoColumns = rule("CORE-LOG-2", new CheckConditionAll(
                List.of(foldingNegative("TSVALNF"), foldingNegative("TSVAL"))));

        List<String> lines = capture(() ->
        {
            RuleRunner.execute(twoColumns, ts());
            RuleRunner.execute(twoColumns, ts());
        });

        assertEquals(2, lines.size(),
                () -> "one line per (rule, dataset) means a second dataset logs again: " + lines);
        assertTrue(lines.getFirst().contains("[TSVAL, TSVALNF]"),
                () -> "columns are aggregated into one sorted list: " + lines);
    }


    @Test
    @DisplayName("no line when nothing folded — a present column is not an event")
    void silentWhenNothingFolds()
    {
        Rule present = rule("CORE-LOG-3", neq("TSPARMCD", "X"));
        assertEquals(List.of(), capture(() -> RuleRunner.execute(present, ts())));
    }


    /**
     * The counterpart that keeps {@link #foldingNegative}'s choice honest (triage finding S5): a
     * column-vs-<b>literal</b> negative over the same absent column now carries an injected
     * {@code var_exists} guard, so it short-circuits <em>before</em> the fold and logs nothing.
     *
     * <p>
     * Without this, "the fixture must use an unguarded leaf" would be an unverified claim in a
     * comment: swap {@code foldingNegative} back to {@code neq} and the two tests above would go
     * silently vacuous (no fold, no line, {@code assertEquals(1, lines.size())} red — but for the
     * wrong reason). This asserts the difference directly.
     * </p>
     */
    @Test
    @DisplayName("a guarded column-vs-literal negative short-circuits before the fold — no line")
    void guardedLeavesNeverReachTheFold()
    {
        // The guard is AUTHORED here. It used to be injected by NonEmptyGuardInliner, which was
        // deleted 2026-08-26 — but this test's subject is the ENGINE's behaviour (a guarded leaf
        // short-circuits before the fold), not who supplied the guard. Authoring it keeps the
        // assertion and removes the dependency on a pass that no longer exists.
        Rule guarded = rule("CORE-LOG-4",
                new CheckConditionAll(List.of(
                        CheckConditionLeaf.builder().name("TSVAL").operator("var_exists").build(),
                        neq("TSVAL", "X"))));
        assertTrue(String.valueOf(guarded.getCheckExpr()).contains("var_exists"),
                () -> "precondition: the guard must be present — " + guarded.getCheckExpr());
        assertEquals(List.of(), capture(() -> RuleRunner.execute(guarded, ts())),
                "the guard short-circuits the leaf, so no column is ever folded");
    }

    /**
     * Collects the {@link LogRecord}s emitted by {@link RuleRunner}'s class logger. Lombok's
     * {@code @CustomLog} yields a {@link System.Logger}, which the JDK routes through
     * {@code java.util.logging}.
     */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        /** The captured records with their {@code {0}} placeholders substituted. */
        List<String> formatted()
        {
            return records.stream()
                    .map(r -> MessageFormat.format(r.getMessage(), r.getParameters())).toList();
        }


        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // no-op
        }


        @Override
        public void close()
        {
            // no-op
        }
    }
}
