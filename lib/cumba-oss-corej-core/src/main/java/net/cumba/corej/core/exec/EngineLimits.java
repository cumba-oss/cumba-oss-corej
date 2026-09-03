package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * Resolves engine-wide execution limits from configuration. Currently the per-rule findings cap:
 * the maximum number of {@link Violation}s a single rule execution (per dataset) materialises into
 * its {@link RuleExecutionResult}. The cap bounds heap use on high-cardinality rules (e.g.
 * CORE-000867 — "text variable contains leading spaces" — flagging every char column of a large
 * {@code SUPPLB}); without it the unbounded {@code List<Violation>} can exhaust the JVM heap.
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
