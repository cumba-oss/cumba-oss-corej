package net.cumba.corej.core.expr.typed;

/**
 * The stage-A check kinds of the typed-expression specification's error model
 * ({@code .claude/docs/specs/SPEC-typed-expression-engine.md} §2 / §9, stage-A rows).
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
     * different known type (D3 — "where the actual type is unsupported the rule errors"): a
     * parameter binding ({@code StageAChecker.checkParameterBinding}), a non-boolean Check or
     * Precondition root, a non-boolean logical operand, a comparison whose known operand types
     * disagree, a non-number in arithmetic, a computed regex, a scalar right operand of {@code in},
     * a non-string regex operand, the temporal conversions and predicates, the
     * {@code earliest_date} / {@code latest_date} DATE parameters and {@code colref}'s non-string
     * argument ({@code PLAN-dynamic-column-functions} Q7).
     *
     * <p>
     * ⭐ <b>Armed since {@code PLAN-stage-a-parameter-type-arming} (2026-10-01).</b> It could not
     * simply be armed: at phase 0 of that plan the shipped corpus carried <b>984</b> findings over
     * 194 rules and the rulespecs 98 — none a wrong rule, all four of them shapes the checker could
     * not type (a quoted dataset name at a {@code DATASET_REFERENCE} parameter, a case-fold over a
     * list, a {@code $}-list spliced into a column-reference list, a string literal at
     * {@code record_count}'s {@code regex=}). Phase 1 taught the checker those shapes; the plan's
     * probe then measured <b>zero</b> over both corpora (every package load), every rulespec and
     * the run-time expanded rules of the scenario suites ({@code WildcardExpander} / {@code
     * TokenExpander} expansions — the {@code --} specialisation copies a checked rule's
     * {@code checkExpr} and never re-enters this checker), and the Precondition root (phase 2). So
     * no shipped rule parks; what changes is a wrongly typed argument in a rule <em>outside</em>
     * the corpus, which now fails to load where it used to load and fail per row, or never.
     * </p>
     *
     * <p>
     * ⚠ The javadoc that stood here said <i>"measured 0 over the shipped corpus (2026-09-16)"</i>;
     * runbook W8 measured 192 rules / 850 findings on the same corpus a day later, and the figure
     * above is the plan's own re-measurement. A zero that is not re-derived is a claim.
     * </p>
     */
    PARAMETER_TYPE(true),

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
     * A name position that is not statically known and not one of §1.2's dynamic forms
     * ({@code ${VAR[:fmt]}}, {@code colref(<string>)} — {@code RELREC.**} retired with W5 of
     * {@code PLAN-merged-evaluation-table} —, {@code colref(<list<string>>)},
     * {@code var_exists(<string>)} — amended by {@code PLAN-dynamic-column-functions}, 2026-09-30)
     * — the typed mirror of {@code ExprCompiler:4583}. Armed: measured 0 newly parked (the engine
     * already parks these today via {@code RuleDefinitionException}). Phase 2 enforces it for the
     * metadata-accessor name argument only — §1.2's full name-position rule lands with the complete
     * element table.
     */
    NON_STATIC_NAME(true),

    /**
     * A binding referencing a later binding or itself (spec §3.2 — forward and cyclic references
     * are the same stage-A error; a cycle over the declaration order implies a forward edge).
     * Armed: measured 0 newly parked (the corpus has 0 forward or self references).
     */
    FORWARD_OR_CYCLIC_BINDING(true),

    /**
     * A reader that takes a {@code $}-binding as a <b>dataset-level list</b> — since runbook W8
     * only {@code minus($a, subtract=$b)}, in the Check or nested in a compiled binding — reading a
     * <b>compiled</b> binding whose derived level has a row or variable cursor
     * ({@code PLAN-binding-expressions} §5.0, the hand-over contract's third row): there is no row
     * to pick the value at, and the reader would otherwise read the per-row value as one bogus
     * string. (Until W8 an operation binding, declared or inline, was held to the same rule through
     * the fields it read; the kind keeps its name.) Armed: measured 0 newly parked — before wave 0
     * no compiled binding existed, and the one wave 0 adds ({@code CDISC-CG0288}'s
     * {@code $VALID_TERM_CODES}) is dataset-level and read by no list function.
     */
    OPERATION_READS_CURSOR_BINDING(true),

    /**
     * A scalar comparison ({@code == != < > <= >=}) against a <b>compiled</b> binding whose static
     * type is a list ({@code PLAN-binding-expressions} review round 2, LOW-3) — the same comparison
     * written against the inline list never compiles, so the binding spelling may not quietly run
     * ({@code !=} flagging every row, {@code ==} none). Operation-produced lists are out of scope
     * on purpose (the shipped corpus compares against them and their verdicts may not move). Armed:
     * measured 0 newly parked — the one shipped compiled binding ({@code CDISC-CG0288}) is read
     * only by {@code in} / {@code not in} / {@code empty}.
     */
    COMPARISON_WITH_LIST_BINDING(true),

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
     * stage A types as unknown — but the {@code rulespec} drift-guard specs have exactly
     * <b>one</b>: {@code EMPTYSTR-date-not-equal-empty} spells {@code date(AESTDTC) != ""} and
     * expects {@code EXECUTED}, so arming parks it and reds the rules gate. Under the arming
     * discipline above (zero newly parked over the shipped gates) the kind stays observe until
     * phase 3c either respells that spec ({@code date("")}) or moves its expectation to the stage-A
     * ERROR — a one-spec rules-repo edit, after which re-arming is measured-safe. The D71b
     * one-sided spellings stay loadable either way, and the column side is stage B's business
     * (D10).
     */
    MIXED_DATE_STRING_COMPARISON(false),

    /**
     * {@code _matched_} consumed under an inner join (D88e — inner drops unmatched rows, the flag
     * is constant-true, and with 242 of 266 corpus entries defaulting to inner this is the
     * <em>default</em> case, not an edge case). Armed: measured 0 newly parked — re-measured in
     * phase 5b-J with the parser able to spell the flag (before 5b-J the kind was vacuous by
     * construction), over the shipped corpus and the {@code rulespec/} harness. ⚠ That zero was
     * over an EMPTY population: no rule spelled {@code _matched_} then (D88b). Re-counted
     * 2026-10-15: 15 authored rules of the rule corpus ({@code rules-src/}, the same 15 in the
     * internal and the published corpus) and 3 {@code rulespec} specs spell it.
     */
    MATCHED_FLAG_INNER_JOIN(true),

    /**
     * A {@code <DATASET>._matched_} reference that cannot mean anything (phase 5b-J): the qualifier
     * names no {@code Match_Datasets} entry (a dangling join reference — the binding analogue of
     * {@link #FORWARD_OR_CYCLIC_BINDING}), the flag names a {@code Child: true} entry by its own
     * {@code --} template (the child dataset, not a parent — a parent domain's flag is served since
     * W4 of {@code PLAN-merged-evaluation-table}, P-14) or an entry that declares no keys (nothing
     * to match on), or the flag stands in value position (spec §3.3 / D88b: the flag is a boolean
     * <em>condition</em> — {@code not AE._matched_} — never a comparison operand).
     * Dataset-independent and decidable at load, so a stage-A error under D67's fail-loud
     * precedent, exactly like {@link #MATCHED_FLAG_INNER_JOIN}. Armed: measured 0 newly parked in
     * phase 5b-J, when no corpus or rulespec rule spelled {@code _matched_} (the namespace scan
     * found 0 over the 3&#8239;940 authored checks, D88b) — a zero over an EMPTY population.
     * Re-counted 2026-10-15: 15 authored rules of the rule corpus ({@code rules-src/}, the same 15
     * in the internal and the published corpus) and 3 {@code rulespec} specs spell it.
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
     * (it gates SKIP-vs-run; it does <b>not</b> reach the merged view's parts), and whether those
     * fixtures are authoring errors to repair or shapes to admit. ⛔ Do not arm this kind by editing
     * the fixtures until that is ruled — three fixtures pin run-time gating behaviour, and adding a
     * join to them changes what they measure.
     * </p>
     */
    DOTTED_REF_UNDECLARED(false),

    /**
     * A value read of the forward RELREC entry itself — {@code RELREC.<column>} or
     * {@code RELREC.**<suffix>} in a Check, a binding, {@code Output_Variables} or a {@code colref}
     * / {@code find_vars} literal ({@code PLAN-merged-evaluation-table} W5, §3.6.5, MET #17). The
     * RELREC entry carries no columns of its own in the one-table model: a related record's column
     * is read qualified with its domain ({@code PC.PCRFTDTC}), guarded by {@code <D>._matched_}
     * where the domain may be absent; the per-row alias the retired expander served has no single
     * answer on a cross-product row. Undeclared in the strict sense of
     * {@link #DOTTED_REF_UNDECLARED} (no join ever serves the name) but <b>armed</b>: the entry
     * exists, so the silent not-supplied default would pass as a verdict. Dataset-independent and
     * decidable at load, like {@link #MATCHED_FLAG_INVALID}. Measured 0 in both corpora after the
     * seven forward-RELREC rules were respelled in W5.
     */
    DOTTED_REF_RELREC_ENTRY(true),

    /**
     * Two {@code Match_Datasets} entries sharing one {@code Name} (phase 6b — D110g(iii), the
     * 2026-08 join-match-flag plan's ambiguity load-error, routed here by D88b as part of the
     * binding model): the merged view keys its parts' lookups by name
     * ({@code EvaluationContext.joins}), last-wins, so a duplicate-name rule silently reads only
     * the surviving entry — since 5b-J with the {@code _matched_} flag as one more reader of the
     * survivor. A binding declared twice is an authoring contradiction, not a resolvable
     * preference, so it fails loud at load (the {@code Operations:} analogue is the
     * duplicate-{@code id} shape). Armed: measured 0 duplicates across the authored corpus
     * (4&#8239;349 rule files, 2026-09-16), 0 newly parked over the shipped-corpus load and the
     * {@code rulespec/} harness.
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
     * pointer, not the key join, finds its parent), {@code RELREC} (row expansion), or an entry
     * with no {@code Keys} (no join is ever built, so the filter would be silently dead). Fail loud
     * at load rather than silently not applying a declared filter. Armed: measured 0 newly parked
     * in phase 5b-J (no {@code Filter} exists in either population yet).
     */
    FILTER_INVALID(true),

    /**
     * A bare (unqualified) reference that resolves onto a merged dataset's column (D62 — a merged
     * column is always qualified). ⚠ Observe-only in phase 2 <b>by ruling</b>: the {@code SUPPAE}
     * rules D62a named read {@code AESMIE} bare; arming before their booked migration would have
     * parked them. Measured 2026-09-16 as three, re-derived 2026-09-19 as <b>two</b> after the
     * CORE-family retirement ({@code CDISC-CG0043}, {@code FDA-SD1143}); both read
     * {@code AE.AESMIE} since {@code PLAN-merged-evaluation-table} W4 (MET #2), and enforcement is
     * the name resolution itself (a bare name is the primary's) plus the corpus lint
     * {@code UnqualifiedJoinedReadLintTest}. Phase-2 detection is a heuristic, not name resolution.
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
     * The naming lint of {@code PLAN-merged-evaluation-table} W3d (P-19 / MET #6): a dotted
     * operand, a {@code _matched_} flag, an {@code Output_Variables} entry or a {@code colref} /
     * {@code find_vars} literal whose qualifier names a {@code Match_Datasets} entry <b>ignoring
     * letter case only</b> ({@code j.S} against an entry {@code J}). The read resolves — a dataset
     * name is case-insensitive, upper case expected — so this is observe-only by ruling: it reports
     * the spelling, never parks the rule.
     */
    QUALIFIER_CASE(false),

    /**
     * The OD-6 gap lint of {@code PLAN-merged-evaluation-table} (T2 review, lane 3): a counting
     * operator ({@code GroupReadTable.REAL_ROW_OPERATORS} — a uniqueness / grouped operator or a
     * grouped aggregate) with an operand that is a {@code $} reference, a {@code ${…}} splice or a
     * {@code *} / {@code **} pattern, on a rule whose merged view can repeat a record's row (a
     * {@code Child: true} entry, a forward {@code RELREC} entry, or a keyed entry whose
     * {@code Cardinality} lets the partner repeat — {@code n:m} / {@code 1:n}, or none authored).
     * Such an operand is never judged primary-only, so the operator counts every merged row instead
     * of each primary record once (OD-6 is not served). Observe-only: it reports the shape, never
     * parks the rule and changes no verdict.
     */
    AGGREGATE_OVER_REPEATED_ROWS(false),

    /**
     * A misuse of the quantifier family over joined rows
     * ({@code PLAN-cg0218-transition-day-precision} P4 / P5): {@code any} / {@code all} /
     * {@code none} outside a Check level expression (a binding, the Precondition, a comparison
     * operand, a call argument) or with an argument other than {@code joined(<condition>)};
     * {@code joined(…)} anywhere but as a quantifier's direct argument; or {@code joined(…)} in a
     * rule that declares no keyed or RELREC {@code Match_Datasets} entry (it ranges over the rows
     * an entry adds; an entry declared {@code n:1} / {@code 1:1} is legal — the list then has one
     * element). Armed: nothing in either corpus spells the family before this plan, so no shipped
     * rule parks; the kind exists so a wrong position is a load error naming it, never a silently
     * per-row answer.
     */
    JOINED_QUANTIFIER(true),

    /**
     * A case-fold probe — {@code upper(…)} / {@code upcase(…)} / {@code lower(…)} /
     * {@code lowcase(…)} — compared by {@code in} / {@code not in} / {@code ==} / {@code !=} with a
     * string literal (or a list-literal member) that is not in the fold's case:
     * {@code upper(X) in ["mild"]} can never match, since the probe is upper-case text and the
     * member is matched as written ({@code SVY OQ6}, owner 2026-10-16: <i>"the load error
     * only"</i>; B5 S6). "Not in that case" is {@code !lit.equals(lit.toUpperCase(Locale.ROOT))}
     * for the upper folds and the lower-case twin for the lower folds — the locale {@code caseFold}
     * itself uses — so a literal with no cased character passes and {@code "ß"} (upper-cases to
     * {@code "SS"}) errors. Armed: the corpus census of 2026-10-16 found no literal compared to a
     * fold probe in the other case (both corpora), so no shipped rule parks; the kind exists
     * because until SVY OQ6 the surface folded the members too, and a rule written for that surface
     * would otherwise go silent.
     */
    FOLD_PROBE_LITERAL_CASE(true),

    /**
     * A computed operand where a set operator's shape is fixed (PACK I E2, PI-C3):
     * {@code contains_all} / {@code not contains_all} take a column, a {@code $}-binding or a
     * list-valued {@code var_*} accessor <em>with a second operand</em> as the source list;
     * {@code is_ordered_subset_of} / {@code is_not_ordered_subset_of} take a reference on both
     * sides. {@code contains_all(upper($x), $y)}, {@code not contains_all(split_by(X, ";"), $y)},
     * {@code is_ordered_subset_of($x, upper($y))} or an accessor source with {@code keys=} used to
     * load silently and ERROR at run — the compile refused them and the loader installed no
     * {@code checkExpr}. The predicate is the compiler's own operand reading
     * ({@code ExprCompiler.setSourceRefusal} runs the classification both compile methods build
     * their plans from, on the call's bound positional form — review fold H1 / M1), so the gate and
     * the compiler cannot drift and EVERY operand shape the compile refuses is this finding: a
     * quoted name as the source, a list literal or a number as the required operand, a
     * {@code keys=} that is not a list of values included. The message names the function as
     * written and, for a computed source, the {@code $}-binding route. Armed: the 2026-10-17 census
     * found no shipped rule of the shape in either corpus (precedent: the binding gate and the W8
     * Precondition gate — a refused form is a load error, never silent).
     */
    COMPUTED_SET_OPERAND(true),

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
