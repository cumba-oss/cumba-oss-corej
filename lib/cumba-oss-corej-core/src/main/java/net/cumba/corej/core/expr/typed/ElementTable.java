package net.cumba.corej.core.expr.typed;

import java.util.Map;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;

/**
 * The phase-2 seed of the per-element type table
 * ({@code .claude/docs/specs/SPEC-typed-expression-engine.md} §1.6 — normative source
 * {@code plans/findings/SCAN6-element-map.tsv}, 318 elements).
 *
 * <p>
 * ⚠ Deliberately partial: phase 2 declares <b>result types</b> for the value functions whose
 * semantics are unambiguous, and leaves everything else {@link Unknown#UNKNOWN} — an unknown result
 * suppresses downstream known-vs-known checks, so partiality can only make the checker
 * <em>quieter</em>, never wrong. Boolean-ness is not declared here at all: it comes from the
 * {@code FunctionRegistry} descriptor's {@code FunctionKind} and the hardcoded boolean-call set, so
 * it cannot drift from the evaluator. Full per-element parameter typing is phases 3–6b, per the
 * §1.6 mapping rule (the TSV's "accepted types today" is what the engine tolerates positionally,
 * not what semantics require — D91f's 45 positional artifacts are dropped, not ported).
 * </p>
 */
public final class ElementTable
{

    /** Result types of the value-function surface, where semantics pin them (spec §1.6). */
    private static final Map<String, ExprType> RESULT_TYPES = Map.ofEntries(
            // numeric results
            Map.entry("abs", Primitive.NUMBER), Map.entry("ceil", Primitive.NUMBER),
            Map.entry("floor", Primitive.NUMBER), Map.entry("round", Primitive.NUMBER),
            Map.entry("len", Primitive.NUMBER), Map.entry("length", Primitive.NUMBER),
            Map.entry("size", Primitive.NUMBER), Map.entry("num", Primitive.NUMBER),
            Map.entry("day", Primitive.NUMBER), Map.entry("month", Primitive.NUMBER),
            Map.entry("year", Primitive.NUMBER), Map.entry("record_count", Primitive.NUMBER),
            Map.entry("max_value_length", Primitive.NUMBER),
            // char(x) is the Unicode CODE POINT of x's first character (a LONG), not a string —
            // the phase-2 corpus measurement caught this: CDISC-AD0144 compares
            // char(PARAMCD) against char("A") / char("Z").
            // ⚑ Not the census's answer, and deliberately so: this comment used to name
            // `CORE-000867`, which plans/findings/CENSUS-core-rule-disposition.tsv files
            // `X-no-twin` with no proposed replacement — and whose own text is a leading-space
            // check, never this comparison, so the old citation was wrong before the CORE family
            // was retired. CDISC-AD0144 is cited because its shipped Check
            // (`not empty(PARAMCD) and not between(char(PARAMCD), char("A"), char("Z"))`) is
            // exactly the shape measured.
            Map.entry("char", Primitive.NUMBER),
            // string results
            Map.entry("upper", Primitive.STRING), Map.entry("upcase", Primitive.STRING),
            Map.entry("lower", Primitive.STRING), Map.entry("lowcase", Primitive.STRING),
            Map.entry("trim", Primitive.STRING), Map.entry("normalize_space", Primitive.STRING),
            Map.entry("concat", Primitive.STRING), Map.entry("substring", Primitive.STRING),
            Map.entry("prefix", Primitive.STRING), Map.entry("suffix", Primitive.STRING),
            // PLAN-dynamic-column-functions §2.1/§2.2/§2.3: the registered text conversion and
            // the two formatting functions. ⚠ colref has NO row here — its scalar
            // (column-reference) and list (list<unknown>) results are decided by its argument, in
            // StageAChecker's colref arm, which a name-keyed row cannot do.
            Map.entry("str", Primitive.STRING), Map.entry("printf", Primitive.STRING),
            Map.entry("lpad", Primitive.STRING),
            // find_vars (§2.4): a list of column NAMES — only colref dereferences them.
            Map.entry("find_vars", new ListOf(Primitive.STRING)),
            // temporal results (phase 3b). ⭐ min_date/max_date are the corpus' only two
            // date-valued operations (an operation type declared an empty result but never a result
            // type — Review 0b's finding); typing them date is what makes an untagged
            // `RFXSTDTC != $max_…` site visible to the §5.2 mixed check once stage B knows the
            // column, and is what lets Review 0's four "no edit" sites stay legal. ⚠ row_max is
            // date-SHAPED but string-typed by ruling — do not add it here (a registry function
            // since W3 answering the winning cell, typed by the columns it reads; row_min went in
            // W1,
            // ts_parameter_value became read_value in W2a, typed by the column it reads).
            // ⭐ earliest_date / latest_date (PLAN-scalar-date-extremes, S3) type DATE for the same
            // reason: they answer what min_date / max_date answer over a two-cell block, and an
            // untagged `RFXSTDTC != earliest_date(…)` must meet the §5.2 mixed check as a date.
            Map.entry("min_date", Primitive.DATE), Map.entry("max_date", Primitive.DATE),
            Map.entry("earliest_date", Primitive.DATE), Map.entry("latest_date", Primitive.DATE),
            Map.entry("date_diff_days", Primitive.NUMBER),
            // collection results
            Map.entry("split_by", new ListOf(Primitive.STRING)),
            // wave 0's list-valued exemplar (PLAN-binding-expressions): one list of CT attribute
            // values — a set by content, a list by carrier (§1.5).
            Map.entry("get_codelist_attributes", new ListOf(Primitive.STRING)),
            // wave 4's dataset-level list functions (PLAN-list-functions D-W4-11): one list of
            // names / terms / dates — a set by content, a list by carrier; get_parent_model_column_
            // order's is per row but list-typed all the same.
            Map.entry("required_variables", new ListOf(Primitive.STRING)),
            Map.entry("expected_variables", new ListOf(Primitive.STRING)),
            Map.entry("get_column_order_from_library", new ListOf(Primitive.STRING)),
            Map.entry("get_model_column_order", new ListOf(Primitive.STRING)),
            Map.entry("variable_names", new ListOf(Primitive.STRING)),
            Map.entry("standard_domains", new ListOf(Primitive.STRING)),
            Map.entry("get_dataset_filtered_variables", new ListOf(Primitive.STRING)),
            Map.entry("natural_key_variables", new ListOf(Primitive.STRING)),
            Map.entry("get_model_filtered_variables", new ListOf(Primitive.STRING)),
            Map.entry("valid_codelist_dates", new ListOf(Primitive.STRING)),
            Map.entry("codelist_terms", new ListOf(Primitive.STRING)),
            Map.entry("get_parent_model_column_order", new ListOf(Primitive.STRING)),
            Map.entry("define_variable_names", new ListOf(Primitive.STRING)),
            Map.entry("define_dataset_names", new ListOf(Primitive.STRING)),
            Map.entry("define_key_variables", new ListOf(Primitive.STRING)),
            Map.entry("get_column_order_from_dataset", new ListOf(Primitive.STRING)),
            Map.entry("dataset_names", new ListOf(Primitive.STRING)),
            Map.entry("study_domains", new ListOf(Primitive.STRING)),
            Map.entry("split_sibling_length_mismatch", new ListOf(Primitive.STRING)),
            Map.entry("duplicate_label_variables", new ListOf(Primitive.STRING)),
            Map.entry("minus", new ListOf(Primitive.STRING)),
            // runbook W7 (PLAN-distinct-function): distinct answers ONE list — of the column's
            // distinct values, or of the distinct row tuples over a list of columns — a set by
            // content, a list by carrier, whose element type is the target's (Unknown);
            // referenced_dataset_variables a per-row list of variable names.
            Map.entry("distinct", new ListOf(Unknown.UNKNOWN)),
            Map.entry("referenced_dataset_variables", new ListOf(Primitive.STRING)),
            // §1.5: tuple(…) is sugar for a list<column-reference> composite key
            Map.entry("tuple", new ListOf(Primitive.COLUMN_REFERENCE)));

    private ElementTable()
    {
    }


    /**
     * The declared result type of a value function or operation, or {@link Unknown#UNKNOWN} when
     * the table does not pin it (e.g. {@code coalesce}, {@code value}). The temporal conversions
     * and accessors ({@code date}, {@code time}, {@code date_part}, {@code time_part},
     * {@code earliest_possible}, {@code latest_possible}) are typed by the checker's own temporal
     * branch instead, because two of them overload their result base on the argument.
     */
    public static ExprType resultType(String name)
    {
        return RESULT_TYPES.getOrDefault(name, Unknown.UNKNOWN);
    }

}
