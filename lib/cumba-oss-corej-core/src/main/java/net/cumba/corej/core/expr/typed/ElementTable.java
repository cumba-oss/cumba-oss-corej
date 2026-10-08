package net.cumba.corej.core.expr.typed;

import java.util.Map;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import org.jspecify.annotations.Nullable;

/**
 * The phase-2 seed of the per-element type table
 * ({@code .claude/docs/specs/SPEC-typed-expression-engine.md} §1.6 — normative source
 * {@code plans/findings/SCAN6-element-map.tsv}, 318 elements).
 *
 * <p>
 * ⚠ Deliberately partial: phase 2 declares <b>result types</b> for the value functions whose
 * semantics are unambiguous, and leaves everything else {@link Unknown#UNKNOWN}. Inside an
 * expression an unknown result suppresses downstream known-vs-known checks, so there partiality
 * only makes the checker <em>quieter</em>. ⚠ <b>Not so for a reported binding</b> (since
 * {@code PLAN-output-variable-null-key-provenance} P3 review MED-1): a {@code $} binding in a
 * rule's effective Output_Variables whose type this table leaves unknown, and whose value is not
 * the type of a column it reads ({@code StageAChecker.columnTypedBindings}), has no type source for
 * the finding's default, and the rule is a <b>load error</b>
 * ({@code RulePackageLoader.validateReportedBindingTypes}). So a missing row is no longer only
 * quieter: it refuses every rule that reports such a binding. Boolean <em>predicates</em> are not
 * declared here: their boolean-ness comes from the {@code FunctionRegistry} descriptor's
 * {@code FunctionKind} and the hardcoded boolean-call set, so it cannot drift from the evaluator. A
 * <b>value</b> function whose value is a boolean ({@code domain_is_custom}, the {@code _matches}
 * accessors) is declared here like any other value function. Full per-element parameter typing is
 * phases 3–6b, per the §1.6 mapping rule (the TSV's "accepted types today" is what the engine
 * tolerates positionally, not what semantics require — D91f's 45 positional artifacts are dropped,
 * not ported).
 * </p>
 *
 * <p>
 * ⭐ <b>The result type is also a finding's type source</b> ({@code PLAN-output-variable-null-key-
 * provenance} P3, owner ruling OPEN-T (c), 2026-10-05: <i>"#3: yes"</i>). A {@code $} binding that
 * answers no value reports its typed default — {@code ""} for a string, {@code MIS} otherwise — and
 * when the evaluation's carrier lost the type ({@code ConstVector.of(null)}: a library or
 * Define-XML lookup that answered nothing) the binding's stage-A type, which is this table's row,
 * decides ({@code BindingValue.reportedDefault}). So every value function whose result type its
 * semantics pin carries a row here, including the ones that answer a <em>keyed</em> attribute
 * ({@link #resultType(String, String)}). The functions still without a row are the ones whose
 * result is the type of the <b>column they read</b> — {@code read_value}, {@code max},
 * {@code row_max}, {@code coalesce}, {@code colref}, {@code value()} — a stage-B fact; their
 * evaluation always carries the column's declared type, which is the finding's type source
 * ({@code ""} for a char column, {@code MIS} for any other). Every other binding stage A leaves
 * unknown is refused at load when it is reported (MED-1) — never a data-dependent run-time ERROR.
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
            // the duration family (runbook W1b DO): the hull between two instants; the marker
            // duration(x) and the bounds over it are typed by the checker's temporal branch
            Map.entry("duration_between", Primitive.DURATION),
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
            Map.entry("get_permitted_column_order", new ListOf(Primitive.STRING)),
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
            // PLAN-union-function (runbook W1a U1): union answers one list whose element type is
            // its operands' (joined by StageAChecker; this name-keyed row routes the inline
            // `X in union(…)` membership)
            Map.entry("union", new ListOf(Unknown.UNKNOWN)),
            // PLAN-elementwise-list-functions §3.2 (runbook W1a E1): values answers a per-row LIST
            // of the named columns' cells — unlike colref it is a list BY NAME, so this name-keyed
            // row is correct and routes the inline `X in values([…])` membership
            // (ExprCompiler.isListValuedFunctionCall → boundMembership) with no new arm; the
            // element type is the columns' (Unknown)
            Map.entry("values", new ListOf(Unknown.UNKNOWN)),
            // runbook W7 (PLAN-distinct-function): distinct answers ONE list — of the column's
            // distinct values, or of the distinct row tuples over a list of columns — a set by
            // content, a list by carrier, whose element type is the target's (Unknown);
            // referenced_dataset_variables a per-row list of variable names.
            Map.entry("distinct", new ListOf(Unknown.UNKNOWN)),
            // runbook W1b RO (PLAN-engine-gap-row-ordinal): sequence answers ONE list of the
            // column's values in sort-key order (duplicates kept), collapse_runs the list with
            // adjacent duplicates collapsed — element type the target's / the input's (Unknown)
            Map.entry("sequence", new ListOf(Unknown.UNKNOWN)),
            Map.entry("collapse_runs", new ListOf(Unknown.UNKNOWN)),
            // PLAN-cg0218-transition-day-precision P1: joined(<condition>) is the list<boolean>
            // the quantifiers any / all / none fold (StageAChecker pins the same type at the call)
            Map.entry("joined", new ListOf(Primitive.BOOLEAN)),
            Map.entry("referenced_dataset_variables", new ListOf(Primitive.STRING)),
            // §1.5: tuple(…) is sugar for a list<column-reference> composite key
            Map.entry("tuple", new ListOf(Primitive.COLUMN_REFERENCE)),
            // PLAN-output-variable-null-key-provenance P3 (OPEN-T (c), owner 2026-10-05): the
            // value functions that had no row although their semantics pin the result —
            // each typed from its implementation's answer.
            // count(x) is size(x) (BuiltinFunctions registers one function under both names);
            // dy answers the SDTM study day, a LONG (StudyDay); variable_count a column count.
            Map.entry("count", Primitive.NUMBER), Map.entry("dy", Primitive.NUMBER),
            Map.entry("variable_count", Primitive.NUMBER),
            // a class name is a string (ScalarMetadataFunctions, ReferencedDomainClass)
            Map.entry("dataset_class_from_library", Primitive.STRING),
            Map.entry("referenced_domain_class", Primitive.STRING),
            // boolean-valued VALUE functions (a Boolean broadcast / carrier, not a predicate)
            Map.entry("domain_is_custom", Primitive.BOOLEAN),
            Map.entry("column_series_metadata", Primitive.BOOLEAN),
            Map.entry("library_variable_code_pair_matches", Primitive.BOOLEAN),
            Map.entry("define_variable_decode_matches", Primitive.BOOLEAN),
            // the per-record Define-XML VLM accessors: exactly the carrier types
            // ExprCompiler.compileVlmAccessor declares (LONG / BOOLEAN / STRING), the two codelist
            // accessors a list of strings (VlmResolver.VlmMatch.codedValues / codedCodes)
            Map.entry("vlm_length", Primitive.NUMBER),
            Map.entry("vlm_value_length", Primitive.NUMBER),
            Map.entry("vlm_data_type", Primitive.STRING),
            Map.entry("vlm_mandatory", Primitive.STRING),
            Map.entry("vlm_type_conforms", Primitive.BOOLEAN),
            Map.entry("vlm_codelist_extensible", Primitive.BOOLEAN),
            Map.entry("vlm_has_codelist", Primitive.BOOLEAN),
            Map.entry("vlm_decode_matches", Primitive.BOOLEAN),
            Map.entry("vlm_codelist_coded_values", new ListOf(Primitive.STRING)),
            Map.entry("vlm_codelist_coded_codes", new ListOf(Primitive.STRING)),
            // runbook W1b DV: the value-level method / comment presence, the origin type, and
            // the per-record codelist decode (ExprCompiler.compileCodelistDecode)
            Map.entry("vlm_has_method", Primitive.BOOLEAN),
            Map.entry("vlm_has_comment", Primitive.BOOLEAN),
            Map.entry("vlm_origin_type", Primitive.STRING),
            Map.entry("codelist_decode", Primitive.STRING));

    /**
     * Result types of the value functions whose answer is the attribute their static key literal
     * names (P3, OPEN-T (c)): {@code extract_metadata(name)} — the keys its implementation
     * documents ({@code ScalarMetadataFunctions.extractMetadata}; {@code dataset_size} is the byte
     * count, a {@code Long}; {@code encoding} and {@code encoding_source} name the decode charset)
     * and {@code file_format} ({@code DataTableMetaSupport}); any other key is whatever the
     * dataset's reader publishes, so it stays unknown — the raw, case-sensitive provider spellings
     * of the charset keys included — and {@code cross_dataset_variable_metadata(name, domain=)},
     * per variable, one of the four attributes its loader admits
     * ({@code ScalarMetadataFunctions.VARIABLE_METADATA_FIELDS}).
     */
    private static final Map<String, Map<String, ExprType>> KEYED_RESULT_TYPES = Map.of(
            "extract_metadata",
            Map.of("dataset_name", Primitive.STRING, "dataset_label", Primitive.STRING,
                    "dataset_location", Primitive.STRING, "filename", Primitive.STRING,
                    "file_format", Primitive.STRING, "dataset_size", Primitive.NUMBER, "encoding",
                    Primitive.STRING, "encoding_source", Primitive.STRING),
            "cross_dataset_variable_metadata", Map.of("label", Primitive.STRING, "data_type",
                    Primitive.STRING, "format", Primitive.STRING, "length", Primitive.NUMBER));

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


    /**
     * The declared result type of a value function whose answer is the attribute a static key
     * literal names ({@link #KEYED_RESULT_TYPES}: {@code extract_metadata},
     * {@code cross_dataset_variable_metadata}), or {@link #resultType(String)} for every other
     * function.
     *
     * @param name
     *            the function name
     * @param key
     *            the static key literal ({@code name} argument), or {@code null} when the call
     *            carries none
     * @return the result type; {@link Unknown#UNKNOWN} for a key the table does not pin
     */
    public static ExprType resultType(String name, @Nullable String key)
    {
        Map<String, ExprType> keyed = KEYED_RESULT_TYPES.get(name);
        if (keyed == null)
        {
            return resultType(name);
        }
        return key == null ? Unknown.UNKNOWN : keyed.getOrDefault(key, Unknown.UNKNOWN);
    }


    /**
     * The whole keyed table ({@link #KEYED_RESULT_TYPES}), read-only — for the language surface
     * ({@code RuleLanguage}, {@code PLAN-engine-language-runbook} §6.0 G).
     *
     * @return function → (key → result type)
     */
    public static Map<String, Map<String, ExprType>> keyedResultTypes()
    {
        return KEYED_RESULT_TYPES;
    }


    /**
     * The key literals {@link #resultType(String, String)} pins for a keyed value function — what
     * the load gate's message offers as the typed alternative to a reported binding over an
     * unpinned key ({@code RulePackageLoader.validateReportedBindingTypes}).
     *
     * @param name
     *            the function name
     * @return the pinned keys, sorted; empty for a function that is not keyed
     */
    public static java.util.SortedSet<String> typedKeys(String name)
    {
        Map<String, ExprType> keyed = KEYED_RESULT_TYPES.get(name);
        return keyed == null ? java.util.Collections.emptySortedSet()
                : java.util.Collections
                        .unmodifiableSortedSet(new java.util.TreeSet<>(keyed.keySet()));
    }

}
