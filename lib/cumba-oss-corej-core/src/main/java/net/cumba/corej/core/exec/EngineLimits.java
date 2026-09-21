package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * Resolves engine-wide execution limits from configuration.
 *
 * <p>
 * ⚠⚠ There are <b>two</b>, and their defaults differ on purpose. {@link #maxErrorsPerRule()}
 * truncates a list of findings that is <em>still reported</em>, so the run says so, and it defaults
 * to {@link #DEFAULT_MAX_ERRORS_PER_RULE}. {@link #maxExpansionsPerRule()} removes an
 * <em>execution</em> — a rule that never ran yields no finding and no absence signal — so it
 * defaults to <b>unlimited</b> (owner ruling, 2026-09-21; see that method). The {@code <= 0} and
 * property-beats-env conventions below apply to both; the default does not.
 * </p>
 *
 * <p>
 * The findings cap: the maximum number of {@link Violation}s a single rule execution (per dataset)
 * materialises into its {@link RuleExecutionResult}. The cap bounds heap use on high-cardinality
 * rules — a variable-level check with an {@code ALL}-domain scope flags one violation per char
 * column of every dataset, so a large {@code SUPPLB} alone can produce tens of thousands; without
 * the cap the unbounded {@code List<Violation>} can exhaust the JVM heap.
 *
 * <p>
 * The cap is resolved (highest precedence first):
 * </p>
 * <ol>
 * <li>system property {@code corej.maxErrorsPerRule}</li>
 * <li>environment variable {@code MAX_ERRORS_PER_RULE}</li>
 * <li>the default {@link #DEFAULT_MAX_ERRORS_PER_RULE}</li>
 * </ol>
 *
 * <p>
 * A value {@code <= 0} (or unparseable) means <em>unlimited</em>, represented as
 * {@link Integer#MAX_VALUE}. Resolution reads the property/env on every call (no static cache) so
 * the system property stays a live knob in a long-running service; the cost is negligible because
 * it is read at most once per rule execution.
 * </p>
 */
public final class EngineLimits
{

    /** Per-rule findings cap applied when neither the system property nor the env var overrides. */
    public static final int DEFAULT_MAX_ERRORS_PER_RULE = 1000;

    /**
     * The run's default <b>severity threshold</b> (Plan C &#167;3.4, ruling 4) — the weakest rung a
     * run evaluates when the caller names none.
     *
     * <p>
     * <b>{@code WARNING}</b>, so a default run evaluates {@code REJECT} + {@code ERROR} +
     * {@code WARNING} and excludes {@code INFO}. {@code REJECT} rides above {@code ERROR} and
     * cannot be excluded by any threshold at or below it, so "ERROR+WARNING" and
     * "REJECT+ERROR+WARNING" name the same set; the rung is the unambiguous spelling and is what
     * the engine stores. {@code INFO} stays out because it is the "a reviewer should look at this"
     * rung — turning it on corpus-wide by default would be a finding-mover disguised as a default.
     * </p>
     */
    public static final net.cumba.datatable.report.Severity DEFAULT_SEVERITY_THRESHOLD = net.cumba.datatable.report.Severity.WARNING;

    static final String PROP = "corej.maxErrorsPerRule";

    static final String ENV = "MAX_ERRORS_PER_RULE";

    static final String EXPANSIONS_PROP = "corej.maxExpansionsPerRule";

    static final String EXPANSIONS_ENV = "MAX_EXPANSIONS_PER_RULE";

    private EngineLimits()
    {
    }


    /**
     * The globally-configured per-rule findings cap. {@link Integer#MAX_VALUE} means unlimited.
     */
    public static int maxErrorsPerRule()
    {
        Integer v = parse(System.getProperty(PROP));
        if (v == null)
        {
            v = parse(System.getenv(ENV));
        }
        int cap = v != null ? v : DEFAULT_MAX_ERRORS_PER_RULE;
        return cap <= 0 ? Integer.MAX_VALUE : cap;
    }


    /**
     * The per-rule <b>expansion</b> cap: the maximum number of concrete rules one template may mint
     * for one dataset. {@link Integer#MAX_VALUE} means unlimited, and unlimited is the
     * <b>default</b>.
     *
     * <p>
     * ⛔⛔ <b>The default differs from {@link #maxErrorsPerRule()} on purpose, and the reason is the
     * whole point of this knob.</b> Owner ruling, 2026-09-21: <i>"Agree to a cap, but off by
     * default. At default we want to see every finding. Limits to finding counts are different as
     * they show up as findings anyway, but a cap on minted rules means the rule is not executed at
     * all and no finding pops up."</i>
     * </p>
     *
     * <p>
     * A findings cap truncates a list that is <em>still reported</em> — the run says so. A cap on
     * minted rules removes an execution, so it produces no finding <b>and no absence signal</b>.
     * The two are not the same kind of limit and must not inherit the same default. ⛔ Never give
     * this one a finite default "for safety": that is precisely the silent coverage loss the ruling
     * names.
     * </p>
     *
     * @return the configured cap, or {@link Integer#MAX_VALUE} when unset or non-positive
     */
    public static int maxExpansionsPerRule()
    {
        Integer v = parse(System.getProperty(EXPANSIONS_PROP));
        if (v == null)
        {
            v = parse(System.getenv(EXPANSIONS_ENV));
        }
        return v == null || v <= 0 ? Integer.MAX_VALUE : v;
    }


    /**
     * Resolves the effective cap for a run: an explicit per-run {@code override} wins over the
     * global configuration; {@code null} falls back to {@link #maxErrorsPerRule()}. A non-null
     * {@code override <= 0} means unlimited.
     */
    public static int resolve(@Nullable Integer override)
    {
        if (override != null)
        {
            return override <= 0 ? Integer.MAX_VALUE : override;
        }
        return maxErrorsPerRule();
    }


    /**
     * Resolves the effective severity threshold for a run: an explicit per-run {@code override}
     * wins, {@code null} falls back to {@link #DEFAULT_SEVERITY_THRESHOLD}.
     *
     * <p>
     * Deliberately <b>not</b> configurable by system property or environment variable, unlike the
     * findings cap: the cap bounds resource use and is an operational knob, while the threshold
     * decides <em>which findings exist</em> and must be stated by the caller who will read the
     * report. An ambient environment variable that silently removed a rung from every run in a
     * container would be exactly the kind of invisible finding-mover this plan exists to avoid.
     * </p>
     *
     * @param override
     *            the run's declared threshold, or {@code null}
     * @return the threshold to evaluate at, never {@code null}
     */
    public static net.cumba.datatable.report.Severity resolveSeverityThreshold(
            net.cumba.datatable.report.@Nullable Severity override)
    {
        return override != null ? override : DEFAULT_SEVERITY_THRESHOLD;
    }


    private static @Nullable Integer parse(@Nullable String s)
    {
        if (s == null || s.isBlank())
        {
            return null;
        }
        try
        {
            return Integer.valueOf(s.trim());
        }
        catch (NumberFormatException _)
        {
            return null; // fall through to the next source / default
        }
    }

}
