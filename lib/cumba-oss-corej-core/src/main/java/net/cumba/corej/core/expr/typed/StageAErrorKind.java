package net.cumba.corej.core.expr.typed;

/**
 * The stage-A check kinds of the typed-expression specification's error model
 * ({@code plans/SPEC-typed-expression-engine.md} §2 / §9, stage-A rows).
 *
 * <p>
 * ⛔ <b>Arming discipline (phase 2, plan §7):</b> an {@link #armed()} kind files a <b>load error</b>
 * — the rule parks and reports {@code ERROR} once — so a kind may only be armed after being
 * measured against the shipped corpus with <b>zero newly parked rules</b> (the DRAFT-1 lesson, spec
 * §11 H2: the unresolved-{@code --} error at stage A would have parked all 918 {@code --} rules).
 * Every armed flag below carries its measured count. An unarmed kind is still checked and reported
 * ({@code observe} mode), it just cannot park.
 * </p>
 */
public enum StageAErrorKind
{

    /**
     * Wrong number of arguments for an element with a known arity contract. Armed: measured 0 newly
     * parked over the shipped corpus (58 packages, 3 914 distinct rules, 2026-09-16).
     */
    ARITY(true),

    /**
     * A known operand type where the parameter (or the opposite comparison side) requires a
     * different known type (D3 — "where the actual type is unsupported the rule errors"). ⚠
     * Observe-only in phase 2, although measured 0 over the shipped corpus (2026-09-16): the
     * element table is still partial (D91d — 173 of 299 elements have no corpus user and are
     * specified, not ported), and expanded/specialised rules pass through this checker per dataset
     * where the load-time measurement cannot see them. Arming is a phase-3/4 decision.
     */
    PARAMETER_TYPE(false),

    /**
     * The excluded {@code group(K) × cursor} cell (D67). Armed: measured 0 newly parked (D30b
     * measured 0 corpus instances).
     */
    LEVEL_EXCLUDED_GROUP_CURSOR(true),

    /**
     * A list literal whose elements carry two different statically-known types (§1.5 — a literal
     * must be homogeneous; generalises {@code ExprCompiler}'s numeric-vs-string membership check).
     * Armed: measured 0 newly parked (2026-09-16).
     */
    HETEROGENEOUS_LIST(true),

    /**
     * A name position that is not statically known and not one of §1.2's three dynamic forms
     * ({@code ${VAR[:fmt]}}, {@code RELREC.**}, {@code colref(IDVAR)}) — the typed mirror of
     * {@code ExprCompiler:4583}. Armed: measured 0 newly parked (the engine already parks these
     * today via {@code RuleDefinitionException}). Phase 2 enforces it for the metadata-accessor
     * name argument only — §1.2's full name-position rule lands with the complete element table.
     */
    NON_STATIC_NAME(true),

    /**
     * A binding referencing a later binding or itself (spec §3.2 — forward and cyclic references
     * are the same stage-A error; a cycle over the declaration order implies a forward edge).
     * Armed: measured 0 newly parked (the corpus has 0 forward or self references).
     */
    FORWARD_OR_CYCLIC_BINDING(true),

    /**
     * An illegal {@code (attribute, metadata-level)} pair, or an unparseable metadata-level literal
     * (§1.1 — the 78-cell table with 50 legal). Armed: measured 0 newly parked (the engine already
     * parks these today).
     */
    METADATA_LEVEL_ILLEGAL(true),

    /**
     * A comparison whose one side is statically {@code date} or {@code time} and whose other is
     * statically {@code string} (§5.2 — a mixed pair is a type error, never a reinterpretation).
     * Since phase 3b <b>no longer vacuous</b>: {@code date()}/{@code time()},
     * {@code date_part}/{@code time_part}, the hull bounds and the {@code min_date}/
     * {@code max_date} bindings all produce static temporal types now.
     *
     * <p>
     * ⚠ <b>Observe-only in 3b — DISARMED by its own measurement, loudly, not silently.</b> Measured
     * 2026-09-16: the full shipped tree (58 packages, 14 937 rule loads) has <b>zero</b> findings —
     * every corpus comparison pairs its temporal side with a column or {@code $}-reference, which
     * stage A types as unknown — but the rulespec parity harness has exactly <b>one</b>:
     * {@code EMPTYSTR-date-not-equal-empty} spells {@code date(AESTDTC) != ""} and expects
     * {@code EXECUTED}, so arming parks it and reds the rules gate. Under the arming discipline
     * above (zero newly parked over the shipped gates) the kind stays observe until phase 3c either
     * respells that spec ({@code date("")}) or moves its expectation to the stage-A ERROR — a
     * one-spec rules-repo edit, after which re-arming is measured-safe. The D71b one-sided
     * spellings stay loadable either way, and the column side is stage B's business (D10).
     */
    MIXED_DATE_STRING_COMPARISON(false),

    /**
     * {@code _matched_} consumed under an inner join (D88e — inner drops unmatched rows, the flag
     * is constant-true, and with 242 of 266 corpus entries defaulting to inner this is the
     * <em>default</em> case, not an edge case). Armed: measured 0 newly parked — re-measured in
     * phase 5b-J with the parser able to spell the flag (before 5b-J the kind was vacuous by
     * construction), over the shipped corpus and the {@code rulespec/} harness.
     */
    MATCHED_FLAG_INNER_JOIN(true),

    /**
     * A {@code <DATASET>._matched_} reference that cannot mean anything (phase 5b-J): the qualifier
     * names no {@code Match_Datasets} entry (a dangling join reference — the binding analogue of
     * {@link #FORWARD_OR_CYCLIC_BINDING}), the named entry is {@code Child: true} (a pre-merge
     * carries no match flag) or declares no keys (nothing to match on), or the flag stands in value
     * position (spec §3.3 / D88b: the flag is a boolean <em>condition</em> —
     * {@code not AE._matched_} — never a comparison operand). Dataset-independent and decidable at
     * load, so a stage-A error under D67's fail-loud precedent, exactly like
     * {@link #MATCHED_FLAG_INNER_JOIN}. Armed: measured 0 newly parked in phase 5b-J (no corpus or
     * rulespec spelling of {@code _matched_} exists yet — the namespace scan found 0 over the
     * 3&#8239;940 authored checks, D88b).
     */
    MATCHED_FLAG_INVALID(true),

    /**
     * A plain dotted operand {@code <DATASET>.<COLUMN>} whose qualifier names no
     * {@code Match_Datasets} entry — the <b>value-read</b> sibling of
     * {@link #MATCHED_FLAG_INVALID}'s dangling-qualifier arm, and the authoring half of the
     * five-way {@code lookup == null} conflation §9c of
     * {@code plans/PLAN-null-free-value-channel.md} pulled apart. The other four causes are a
     * DECLARED join whose dataset is absent from the run; those are legitimate and take the
     * rule-expected default value (D72a-1's third not-supplied case,
     * {@code ExprCompiler.dottedNotSuppliedDefault}). An UNDECLARED qualifier cannot mean that: no
     * join is ever built for it, so the operand reads its type default on every row and the check
     * can never fire — a silent {@code PASS} for a rule that never ran. Dataset-independent and
     * decidable from the rule text alone, so a stage-A error under D67's fail-loud precedent,
     * exactly like {@link #MATCHED_FLAG_INVALID}. Deferred, like that kind, while any entry name is
     * still a template.
     * <p>
     * ⛔⛔ <b>Observe-only, and NOT because it is harmless — because its population is measurably NOT
     * zero.</b> The arming discipline at the top of this file says a kind may be armed only once
     * measured at zero newly parked rules, and this one fails that test <em>in this repo</em>: over
     * the shipped authored corpus it is clean (2026-09-18 — 3&#8239;940 check files, 187 carrying a
     * {@code DOTTED_REF}, <b>192</b> (rule, qualifier) pairs, every one a literal
     * {@code Match_Datasets} {@code Name}, <b>0</b> deferred by the template guard; also 0 in
     * {@code Bindings[].expression} and 0 in {@code Match_Datasets[].Filter}, the latter being
     * {@link #FILTER_LEFT_REFERENCE}'s ground anyway and deliberately not double-reported here; and
     * 0 over the rules repository's {@code rulespec}), but <b>three of this repo's own test
     * fixtures carry a value-position dotted reference with no {@code Match_Datasets} at all</b> —
     * {@code StudyRuleClassifierTest}'s {@code DM.DTHDTC != ""},
     * {@code RuleRunnerRequirementsDatasetsTest}'s {@code empty(EX.EXDOSE)} (whose dataset is
     * declared through {@code Requirements.Datasets}, which builds no join) and
     * {@code MissingValuesLoadValidationTest}'s {@code date(EXSTDTC) > DM.RFSTDTC}. Arming parks
     * all three.
     * </p>
     *
     * <p>
     * ⇒ Two questions must be answered before arming, and neither is this kind's to decide: whether
     * {@code Requirements.Datasets} is a second legal declaration channel for a dotted qualifier
     * (it gates SKIP-vs-run; it does <b>not</b> reach {@code RuleRunner.buildJoinedDatasets}), and
     * whether those fixtures are authoring errors to repair or shapes to admit. ⛔ Do not arm this
     * kind by editing the fixtures until that is ruled — three fixtures pin run-time gating
     * behaviour, and adding a join to them changes what they measure.
     * </p>
     */
    DOTTED_REF_UNDECLARED(false),

    /**
     * Two {@code Match_Datasets} entries sharing one {@code Name} (phase 6b — D110g(iii), the
     * 2026-08 join-match-flag plan's ambiguity load-error, routed here by D88b as part of the
     * binding model): {@code RuleRunner.buildJoinedDatasets} keys the join lookups by name,
     * last-wins, so a duplicate-name rule silently reads only the surviving entry — since 5b-J with
     * the {@code _matched_} flag as one more reader of the survivor. A binding declared twice is an
     * authoring contradiction, not a resolvable preference, so it fails loud at load (the
     * {@code Operations:} analogue is the duplicate-{@code id} shape). Armed: measured 0 duplicates
     * across the authored corpus (4&#8239;349 rule files, 2026-09-16), 0 newly parked over the
     * shipped-corpus load and the {@code rulespec/} harness.
     */
    DUPLICATE_MATCH_DATASET_NAME(true),

    /**
     * A {@code Match_Datasets} {@code Filter} referencing anything but the joined dataset's own
     * columns — a dotted reference, a {@code $}-operation result, or the {@code _matched_} flag
     * (spec §3.3 SPEC CHOICE, phase 5b-J): the filter is evaluated against the joined dataset's own
     * rows before the key index is built, so a left-side reference would be a correlated sub-join —
     * a different, much larger feature — and silently resolving it against the wrong side would
     * filter nothing or everything. Spec §9's dedicated stage-A row. Armed: measured 0 newly parked
     * in phase 5b-J (no corpus or rulespec entry carries a {@code Filter} yet — the field itself
     * lands with this phase).
     */
    FILTER_LEFT_REFERENCE(true),

    /**
     * A {@code Match_Datasets} {@code Filter} that cannot work (phase 5b-J): unparseable text, a
     * wildcard column reference (a filter is never specialised, so {@code --} could resolve
     * nothing), or a filter on an entry the engine cannot pre-filter — {@code Child: true} (the
     * pre-merger, not the key join, consumes it), {@code RELREC} (row expansion), or an entry with
     * no {@code Keys} (no join is ever built, so the filter would be silently dead). Fail loud at
     * load rather than silently not applying a declared filter. Armed: measured 0 newly parked in
     * phase 5b-J (no {@code Filter} exists in either population yet).
     */
    FILTER_INVALID(true),

    /**
     * A bare (unqualified) reference that resolves onto a merged dataset's column (D62 — a merged
     * column is always qualified). ⚠ Observe-only in phase 2 <b>by ruling</b>: the {@code SUPPAE}
     * rules D62a named read {@code AESMIE} bare and work today; arming before their booked
     * migration would park them. Measured 2026-09-16 as three, re-derived 2026-09-19 as <b>two</b>
     * after the CORE-family retirement: the prefix heuristic finds exactly those two
     * ({@code CDISC-CG0043}, {@code FDA-SD1143}) and no false positive. Phase-2 detection is a
     * heuristic, not name resolution.
     */
    MERGED_COLUMN_UNQUALIFIED(false),

    /**
     * A call name no classifier knows — not an accessor, operation, builtin, registry entry or
     * table element. Observe-only: the element table is phase 2's seed, not yet the full §1.6
     * surface. Measured 0 over the shipped corpus (2026-09-16) — every corpus-used element is
     * classified.
     */
    UNKNOWN_ELEMENT(false),

    /**
     * The checker itself failed on this rule. Never armed: a checker defect must not park a rule
     * (that would be an evaluation change caused by the instrument).
     */
    CHECKER_FAILURE(false);

    private final boolean armed;

    StageAErrorKind(boolean armed)
    {
        this.armed = armed;
    }


    /** Whether a finding of this kind files a load error (parks the rule). */
    public boolean armed()
    {
        return armed;
    }

}
