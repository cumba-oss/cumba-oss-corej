package net.cumba.corej.core.expr.typed;

/**
 * The stage-B check kinds of the typed-expression specification's error model
 * ({@code plans/SPEC-typed-expression-engine.md} §2 / §9, stage-B rows) — decided once per (rule ×
 * dataset), knowing the dataset's columns and their types (D10).
 *
 * <p>
 * ⛔ <b>Arming discipline (plan §7, phase 4):</b> an {@link #armed()} kind files a <b>bind error</b>
 * — the (rule, dataset) execution reports {@code ERROR}, per binding (D41) — so a kind may only be
 * armed after being measured against the shipped corpus <b>and</b> the {@code rulespec/} parity
 * harness with <b>zero newly errored rules</b> (D102c: "zero in the corpus" is not "zero in the
 * build"). Every flag below carries its measurement. An unarmed kind is still checked and reported
 * ({@code observe} mode), it just cannot error.
 * </p>
 */
public enum StageBErrorKind
{

    /**
     * A {@code --} wildcard that survived specialisation — D77b's output contract (<i>"no
     * {@code --} survives specialisation, anywhere"</i>), asserted at bind time per (rule ×
     * dataset). A finding here is a <b>specialiser defect, not an authoring one</b> (spec §9): the
     * eval-time assertion ({@code ExprCompiler.resolveDomainPrefix}, D93a) remains the backstop
     * behind it. Checked only when specialisation was applicable — a degraded / synthetic context
     * with no domain code resolves nothing on purpose, so the contract does not hold there. The
     * D92a/D92b/D93b carve-outs (the {@code variable_count} family's name operand,
     * {@code name_pattern=} contents, {@code Child: true} match names) are exempted in the walk.
     * Armed: measured 0 over the shipped corpus runs (pinned and HEAD) and the rules-repo gate
     * (2026-09-16).
     */
    UNRESOLVED_WILDCARD(true),

    /**
     * An unresolvable column reference inside a {@code Match_Datasets} {@code Filter} that is
     * <b>not</b> declared in {@code Requirements.Variables} — a bind error per D89/D89b
     * (<i>"declare ⇒ skip; don't declare ⇒ error — never silent"</i>; the declared case is a
     * {@link StageBReport#skips() skip}, not a finding).
     *
     * <p>
     * ⚑ <b>Future-armed on purpose:</b> {@code MatchDataset} carries no {@code Filter} field yet —
     * it lands in phase 5b-J — so the production population is structurally <b>zero</b> and arming
     * changes nothing today. The detection logic is live and tested through
     * {@code StageBChecker.checkFilterBinding}, which 5b-J wires to the parsed filter and the
     * right-side dataset's column inventory.
     * </p>
     */
    FILTER_UNRESOLVABLE(true),

    /**
     * A {@code <DATASET>._matched_} flag whose joined dataset this run cannot resolve, with the
     * dataset neither declared in {@code Requirements} (which skips — D89a) nor suppressed by
     * {@code AbsentDatasetSkip} (which folds the reader and reports the absence once, via the
     * presence rule). D89b's doctrine applied to the flag's own binding — the join itself: letting
     * the flag evaluate over a missing join would either flood ({@code not} over an all-false
     * verdict — the 2026-08 plan's Q1) or stay silent, and both are ruled out.
     *
     * <p>
     * Armed in phase 5b-J with a structurally-zero production population: no corpus or rulespec
     * rule spells {@code _matched_} yet (D88b measured 0 over the 3&#8239;940 authored checks),
     * re-measured over both populations with the parser able to spell it.
     * </p>
     */
    MATCHED_FLAG_UNRESOLVABLE(true),

    /**
     * A column read against its declared type without a conversion — D15's column-type mismatch,
     * detected per binding and named per binding (D41).
     *
     * <p>
     * ⭐ <b>Observe-only here BY DESIGN, not by caution: the armed half of this kind IS the shipped
     * {@code ColumnTypeGate}</b> (D15 — this engine absorbs that gate, it does not grow a second
     * one beside it). The gate raises {@code ColumnTypeMismatchException} from the evaluation plans
     * and {@code RuleRunner}'s single catch site (D74a) turns it into the per-(rule, dataset)
     * {@code ERROR} — that path stays the one source of truth for erroring. What the gate
     * structurally cannot do is <b>per-binding accumulation</b>: it throws on the first mismatch,
     * so a cursor rule that errors for variable C says nothing about A and B (D41b). This kind is
     * that reporting gap: the checker names <b>every</b> mismatching binding, and phase 5b surfaces
     * the accumulated messages in the report. Arming it would double-gate — and the static walk can
     * over-approximate lazily-evaluated plans, so its verdict may not replace the gate's.
     * </p>
     */
    COLUMN_TYPE_MISMATCH(false),

    /**
     * A {@code date()}/{@code time()} conversion over a column the dataset declares numeric — D55's
     * {@code date(NUM)} shape, stage-B classified. ⚠ <b>Observe-only on purpose, exactly as phase
     * 3b left it</b>: the prescribed rewrite ({@code date_from_sas_days} /
     * {@code date_from_sas_datetime}) exists nowhere yet, so an armed error would name an
     * unwritable fix. The evaluation-side twin is {@code ColumnTypeGate.observeIsoConversionRead}
     * (pinned by its own test); when D55 arms — a 3c-or-later decision — exactly one of the two
     * homes keeps it, and the arming conditions recorded on the gate hook apply.
     */
    DATE_CONVERSION_OVER_NUMERIC(false),

    /**
     * An absent column and the type it defaults to under this rule (D34 #3/#4 + D76: the rule's own
     * expectation decides — numeric-expected ⇒ {@code number} ⇒ {@code MissingValue.MIS}, otherwise
     * {@code string} ⇒ {@code ""}). <b>Never an error</b> — spec §9 has no absent-column row, and
     * D34 #3/#4 say the rule evaluates normally; this kind is the <em>informational record</em> of
     * which binding folded and to what, per (rule, dataset).
     */
    ABSENT_COLUMN(false),

    /**
     * The checker itself failed on this (rule, dataset). Never armed: a checker defect must not
     * error an execution (that would be an evaluation change caused by the instrument — phase 2's
     * headline constraint, unchanged here).
     */
    CHECKER_FAILURE(false);

    private final boolean armed;

    StageBErrorKind(boolean armed)
    {
        this.armed = armed;
    }


    /** Whether a finding of this kind files a bind error for the (rule, dataset). */
    public boolean armed()
    {
        return armed;
    }

}
