package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-binding record of one variable-cursor execution (phase 6 of
 * {@code plans/PLAN-typed-expression-engine.md}, D8/D41/D41b): how many bindings the cursor loop
 * iterated, and one {@link BindingOutcome} per <b>noteworthy</b> binding — a binding that fired
 * violations or (once the D104b ruling arms it) errored or skipped. Silent bindings contribute to
 * {@link #iterated()} only, which is what keeps the carrier sparse: D41's 200-variable rule with
 * one noteworthy variable carries one entry that names it, not 200.
 *
 * <p>
 * Per D41b the ledger rides <b>inside</b> the one {@code RuleExecutionResult} per (rule, dataset) —
 * diagnostics keyed by binding alongside the violations — never as one result per binding, which
 * would change the report's shape. It is attached only by the two variable-cursor scopes; on the
 * broadcast and row paths the existing per-(rule, dataset) result is itself the binding carrier
 * (D41b: <i>"a wildcard binding is one per dataset, so per-binding is per-dataset there"</i>). JSON
 * report <b>v1 never carries it</b> (D57 — frozen); a report rendering belongs to v2 once a
 * per-binding kind is armed.
 * </p>
 *
 * @param iterated
 *            the bindings the cursor loop visited (a DATASET-sensitivity collapse stops the loop at
 *            its first firing binding, so this counts visited, not existing, bindings)
 * @param outcomes
 *            the noteworthy bindings, in iteration order
 */
public record BindingLedger(int iterated, List<BindingOutcome> outcomes)
{

    public BindingLedger
    {
        outcomes = List.copyOf(outcomes);
    }

    /** Mutable accumulator for the binding loops. */
    public static final class Builder
    {

        private int iterated;

        private final List<BindingOutcome> outcomes = new ArrayList<>();

        /** Records that the loop visited one more binding. */
        public void visited()
        {
            iterated++;
        }


        /** Records a noteworthy binding. */
        public void note(BindingOutcome outcome)
        {
            outcomes.add(outcome);
        }


        public BindingLedger build()
        {
            return new BindingLedger(iterated, outcomes);
        }
    }
}
