package net.cumba.corej.ruletest.cdt.ruletest;

import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.model.Rule;

/**
 * Sees every engine execution a {@link ScenarioRunner} performs — one per concrete rule, so a
 * wildcard expansion reports each expansion. The corpus factory passes its finding-map null guard
 * ({@code FindingValuesNullGuard}, which stays in the corpus test tree with its reach counters); an
 * editor passes none.
 */
@FunctionalInterface
public interface ExecutionObserver
{

    /**
     * Called after each execution, before the result is folded into the outcome.
     *
     * @param aContext
     *            {@code "scenario <domain> / <dataset>"}, the text the corpus guard names
     * @param aRule
     *            the executed rule (a concrete expansion, when the scenario's rule expanded)
     * @param aResult
     *            the engine's result
     */
    void afterExecution(String aContext, Rule aRule, RuleExecutionResult aResult);
}
