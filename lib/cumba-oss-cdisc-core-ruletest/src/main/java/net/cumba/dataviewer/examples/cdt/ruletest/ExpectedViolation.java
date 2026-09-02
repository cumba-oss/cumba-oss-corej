package net.cumba.dataviewer.examples.cdt.ruletest;

import java.util.Map;
import lombok.Builder;
import lombok.Value;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * One expected violation <em>location</em>, parsed from a single {@code #expectViolationAt}
 * directive line. A scenario may carry several of these; together with an optional
 * {@code #expectViolationCount} they pin <em>where</em> a rule fires, not merely whether it fires
 * (see {@code RuleTestScenario.getExpectedViolations()}).
 *
 * <p>
 * Addressing is deliberately twofold so one directive shape covers every rule class:
 * </p>
 * <ul>
 * <li>{@link #row} — a 1-based data-row number (from {@code row=N}); matched against
 * {@code Violation.getRowNumber()}. Natural for record-level (value-based) rules.</li>
 * <li>{@link #constraints} — any other {@code COL=value} pins; matched against the union of the
 * violation's projected output variables and the primary table's column {@code COL} at the fired
 * row. Output variables are consulted first, so per-domain rules (variable-metadata, dataset
 * presence, …) are pinned by their projected keys (e.g. {@code variable_name=AEFOO}) rather than by
 * an unreliable row index.</li>
 * </ul>
 *
 * <p>
 * At least one of {@link #row} / a non-empty {@link #constraints} is always present (the parser
 * rejects an empty {@code #expectViolationAt}). Comparison is order-independent and
 * value-normalised (numeric {@code 1.0} matches {@code 1}); see {@code ViolationLocationCheck}.
 * </p>
 */
@Value
@Builder
public class ExpectedViolation
{

    /** 1-based row number from {@code row=N}, or {@code null} when only value pins were given. */
    @Nullable
    Integer row;

    /**
     * {@code COL=value} constraints in file order, excluding the reserved {@code row=} token.
     * Possibly empty (when only {@code row=} was given). Insertion-ordered.
     */
    Map<String, String> constraints;

    /**
     * Expected severity from the reserved {@code severity=} token, or {@code null} when the
     * scenario does not pin one.
     *
     * <p>
     * ⛔⛔ {@code severity} is <b>RESERVED</b> in {@code parseExpectAt}, exactly as {@code row} is.
     * Before Plan C phase 2 it was not, so {@code #expectViolationAt severity=ERROR} silently
     * declared a constraint on a <em>column named {@code severity}</em> — which matches nothing on
     * most fixtures and, on any fixture that happens to have such a column, matches the wrong
     * thing. That is the quiet bug this field exists to make impossible.
     * </p>
     */
    @Nullable
    Severity severity;
}
