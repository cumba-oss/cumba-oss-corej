package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Operation
{

    /** {@link #missingValues}: a missing candidate is not a candidate. The default. */
    public static final String MISSING_VALUES_SKIP = "skip";

    /** {@link #missingValues}: a missing candidate makes the extreme undeterminable. */
    public static final String MISSING_VALUES_INDETERMINATE = "indeterminate";

    private @Nullable String id;

    private @Nullable String operator;

    /**
     * Function-call authoring form (Form B). When set, the operation is written as a single
     * function-call string whose name is the {@code operator} and whose arguments are the operation
     * parameters, e.g. {@code "variable_count(--LNKGRP)"} or
     * {@code "record_count(filter=filter(TSPARMCD=\"INDIC\"))"}. The loader
     * ({@code RulePackageLoader.normalizeOperations}) rewrites such an operation to the equivalent
     * field form via
     * {@link net.cumba.corej.core.expr.convert.OperationExpressionParser#normalize(Operation)} at
     * load time, so {@link net.cumba.corej.core.exec.OperationExecutor} only ever sees field-form
     * operations. Mutually exclusive with the individual field accessors below; not consumed at
     * runtime.
     */
    private @Nullable String expression;

    private @Nullable String name;

    /**
     * Composite (multi-column) target for the {@code distinct} operation (T3): the ordered list of
     * columns whose per-row tuple is collected into the reference tuple set. When set, the
     * {@code name} field is ignored and {@link net.cumba.corej.core.exec.OperationExecutor} builds
     * a {@code Set<List<String>>} (one {@code List<String>} per distinct row-tuple in the reference
     * dataset) rather than a single-column {@code List<String>}. Authored as
     * {@code names: [VISIT, VISITNUM]} and printed as the first positional list argument of the
     * inline form {@code distinct([VISIT, VISITNUM], domain="TV")}; the composite membership
     * left-hand side is the native {@code tuple(VISIT, VISITNUM)} value function. JSON key
     * {@code "names"}.
     */
    private @Nullable List<String> names;

    /**
     * Subtrahend reference for the {@code minus} (set-difference) operation: a {@code $}-ref to a
     * prior operation result whose elements are removed from this operation's {@code name} (the
     * minuend). Mirrors Python's {@code operations/minus.py} {@code params.subtract}. Only consumed
     * by {@link net.cumba.corej.core.model.OperationType#MINUS}; {@code null} for every other
     * operation. JSON key {@code "subtract"}.
     */
    private @Nullable String subtract;

    /**
     * EC-7 — literal value-list minuend for the {@code minus} (set-difference) operation. When set,
     * {@link net.cumba.corej.core.exec.OperationExecutor#evalMinus} uses this literal list as the
     * minuend instead of resolving the {@code name} field as a {@code $}-ref to a prior operation
     * result — so a rule can subtract a {@code $}-ref subtrahend from a fixed authored list (e.g.
     * {@code minus(value=["AGEU", "SDESIGN"], subtract=$present)} reports which expected members
     * are absent). Mirrors the Python reference engine's {@code operations/minus.py}
     * {@code params.value}. Only consumed by
     * {@link net.cumba.corej.core.model.OperationType#MINUS}; a positional list is already claimed
     * by the T3 composite {@code names} form, so this is authored as the {@code value=[...]} kwarg.
     * JSON key {@code "value"}.
     */
    private @Nullable List<String> value;

    private @Nullable String domain;

    /**
     * Reference-date column for the {@code dy} (study-day) operation (T6): the DM column, keyed by
     * {@code USUBJID}, against which each {@code --DTC} date is recomputed into a study day. When
     * {@code null} the study day is computed against the SDTM-default {@code RFSTDTC} — so all
     * pre-existing {@code dy} rules stay byte-identical. Set to a non-default column such as
     * {@code "RFXSTDTC"} (first EX date) or {@code "RFCSTDTC"} to recompute {@code --XDY}/{@code
     * --CHDY} style values, or to any per-subject DM reference date. Mirrors Python's
     * {@code operations/day_data_validator.py} {@code params.reference}. Consumed only by
     * {@link net.cumba.corej.core.model.OperationType#DY}; printed as the {@code reference="…"}
     * kwarg of the inline {@code dy(--DTC, reference="RFXSTDTC")} form (omitted when default). JSON
     * key {@code "reference"}.
     */
    private @Nullable String reference;

    /**
     * Delimiter for the {@code split_by} operation (T9): the literal string on which the
     * {@code name} column's per-row value is split into a token list (mirrors the Python reference
     * engine's {@code operations/split_by.py} {@code params.delimiter}). coreJ has no
     * {@code SPLIT_BY} {@link OperationType} — a broadcast operation cannot produce a per-row list
     * — so the converter ({@link net.cumba.corej.core.expr.convert.SplitByInliner}) lowers a
     * {@code split_by} operation to the per-row native value function {@code split_by(<col>,
     * "<delimiter>")}; this field is the delimiter it reads. JSON key {@code "delimiter"}.
     */
    private @Nullable String delimiter;

    private @Nullable List<String> group;

    /**
     * Whether a row whose {@link #group} key carries a missing value stays in its group (folded
     * under the blank key) or is dropped along with its whole group.
     *
     * <p>
     * ⚠⚠ <b>This parameter exists because {@code group:} means two different things today.</b> The
     * five grouped operators that key through {@code IndexHelper.groupByPresent} — {@code max_date}
     * / {@code min_date}, {@code max}, {@code distinct}, {@code record_count},
     * {@code has_mixed_emptiness_within_group} — <b>fold</b> a blank key and keep the group, while
     * {@code is_last_in_group} partitions through {@code GroupSemantics.partition} and
     * <b>discards</b> it. Nothing in the YAML told the author which they would get. Declaring
     * {@code keep_missings} now settles it explicitly on either operator.
     * </p>
     *
     * <p>
     * {@code null} — the shipped state of every rule — means "engine default", and the defaults are
     * still the asymmetric pair above so that this phase moves no findings. Resolving the asymmetry
     * by changing a default is a separate, deliberately-isolated step.
     * </p>
     *
     * <p>
     * ⚠ Not {@link #missingValues}, which governs a different axis — how a missing <em>input</em>
     * affects an <em>operation's result</em>, rather than whether a row <em>participates in a
     * group</em>. JSON key {@code "keep_missings"}.
     * </p>
     */
    @JsonProperty("keep_missings")
    private @Nullable Boolean keepMissings;

    /**
     * E3 — the day offset added to the days-between result of the {@code date_diff_days} operation.
     * Either an integer literal (default {@code 0} when {@code null}/blank) or the name of a
     * per-record integer column whose value is added per row (e.g. {@code RPRFDY}). Consumed only
     * by {@link OperationType#DATE_DIFF_DAYS}. Mirrors the Python reference engine's
     * {@code OperationParams.offset}. JSON key {@code "offset"}.
     */
    private @Nullable String offset;

    /**
     * E3 — the grouped-reference extreme selector for the {@code date_diff_days} operation, Mode 2.
     * Selects whether the per-group subtrahend date is the earliest ({@code "min"}, the default
     * when {@code null}/blank) or the latest ({@code "max"}) value of the {@code reference} column
     * within the {@code group} key, sourced from the foreign {@code domain} dataset. Only
     * {@code "max"} changes behaviour; every other value (including {@code null}) is treated as
     * {@code "min"} so all pre-existing Mode 2 rules stay byte-identical. Ignored in Mode 1.
     * Consumed only by {@link OperationType#DATE_DIFF_DAYS}. Mirrors the Python reference engine's
     * {@code OperationParams.reference_extreme}. JSON key {@code "reference_extreme"}.
     */
    @JsonProperty("reference_extreme")
    private @Nullable String referenceExtreme;

    /**
     * EC-51 Half B — what a <b>missing</b> candidate means to a date extreme. Two values, and no
     * others:
     * <ul>
     * <li>{@link #MISSING_VALUES_SKIP} ({@code "skip"}) — the default when {@code null}, and
     * today's behaviour: a missing cell is not a candidate, so the extreme is taken over the
     * populated ones;</li>
     * <li>{@link #MISSING_VALUES_INDETERMINATE} ({@code "indeterminate"}) — a missing cell makes
     * the extreme <em>undeterminable</em>: the operation yields no value for that group, and the
     * dependent negative check therefore reports instead of silently computing the second-best
     * date.</li>
     * </ul>
     *
     * <p>
     * <b>"missing" is "empty".</b> The two words are synonyms in this codebase and in the clinical
     * taxonomy — char {@code ""}, numeric {@code MissingValue}, {@code null} mapped onto both. The
     * distinctions that <em>do</em> exist are missing vs <b>partial</b> ({@code 2012-06}) vs
     * <b>wrong</b> ({@code 2012-13-45}, {@code UNK}), and this field governs only the first: a
     * partial or wrong value is <em>present</em>, and EC-46 already makes it win its group and
     * leave the extreme undetermined regardless of what is declared here.
     * </p>
     *
     * <p>
     * <b>Consumed only by</b> {@link OperationType#MIN_DATE}, {@link OperationType#MAX_DATE} and
     * {@link OperationType#DATE_DIFF_DAYS} — the last only in <b>Mode 2</b>, whose grouped
     * subtrahend is the extreme taken over the foreign {@code domain} within the {@code group} key.
     * Declaring it on any other operator, or on a {@code date_diff_days} without {@code domain} and
     * a non-empty {@code group}, is a load error rather than a silent no-op — see
     * {@link net.cumba.corej.core.expr.convert.OperationExpressionParser#validateMissingValues}.
     * The generic {@code max} is deliberately outside that set: its string fallback also serves
     * Char <em>category</em> columns ({@code ANRIND}, {@code ATOXGR}) where a blank is "not
     * assessed", not "undeterminable", and a rule that wants date semantics authors
     * {@code max_date}. {@code row_max}/{@code row_min} are outside it because a blank
     * {@code TRxxEDT} is how "period not used" is encoded.
     * </p>
     *
     * <p>
     * String rather than boolean: {@link Operation} has no typed-enum field, and a boolean cannot
     * grow a third disposition. Validated strictly at load — unlike {@link #referenceExtreme}'s
     * lenient read, a typo here is an error rather than a silent {@code "skip"}. JSON key
     * {@code "missing_values"} (plural: the plural is the object of the policy — "missing values:
     * skip them" — not a statement about the field's type).
     * </p>
     */
    @JsonProperty("missing_values")
    private @Nullable String missingValues;

    /**
     * EC-18 / P5c — Mode 3 (foreign minuend) for the {@code date_diff_days} operation. When set,
     * the <em>minuend</em> date is read from the record of this foreign domain (e.g. {@code "PM"})
     * that matches the evaluation row on {@link #minuendMatch} (the {@code --SPID} mass linkage),
     * instead of from the {@code name} column of the evaluation record (Mode 1/2). The
     * <em>subtrahend</em> side is unchanged — it still uses the Mode 1 same-record
     * {@code reference} or the Mode 2 grouped extreme sourced from {@code domain}/{@code group}. In
     * Mode 3 the {@code name} field is the minuend date <em>column in the foreign
     * {@code minuend_domain}</em> (e.g. {@code PMDTC}). {@code null} for every non-Mode-3 rule, so
     * existing Mode 1/2 rules stay byte-identical. Mirrors the Python reference engine's
     * {@code OperationParams.minuend_domain}. Consumed only by
     * {@link OperationType#DATE_DIFF_DAYS}. JSON key {@code "minuend_domain"}.
     */
    @JsonProperty("minuend_domain")
    private @Nullable String minuendDomain;

    /**
     * EC-18 / P5c — the per-record match key(s) used by {@code date_diff_days} Mode 3 to link each
     * evaluation record to a record of {@link #minuendDomain}. Each entry is resolved <b>per
     * side</b> via the {@code --} wildcard: a {@code --}-prefixed key resolves to the evaluation
     * domain's prefix on the left (e.g. {@code "--SPID"} → {@code TFSPID} on a TF row) and to the
     * {@code minuend_domain} prefix on the right (→ {@code PMSPID} on the matched PM record); a
     * bare key (e.g. {@code "USUBJID"}) is same-named on both sides. This is the sided
     * {@code --SPID} linkage of SENDIG §6.3.15.1 Assumption 5, expressed at the operation level
     * (coreJ operations cannot read a rule-level {@code Match_Datasets} block). When {@code null}
     * the operation falls back to {@link #group} as the (same-named) match key. Consumed only by
     * {@link OperationType#DATE_DIFF_DAYS} in Mode 3. The {@code --} tokens are deliberately left
     * unresolved by {@code resolvePrefixes} so the two sides can be derived at evaluation time.
     * JSON key {@code "minuend_match"}.
     */
    @JsonProperty("minuend_match")
    private @Nullable List<String> minuendMatch;

    /**
     * E4 — the ordering column for the {@code is_last_in_group} operation: within each
     * {@code group} partition the rows are sorted by this column (the shared
     * {@link net.cumba.corej.core.exec.GroupSemantics#sortByOrderColumn} string ordering) and the
     * maximum-ordering row is flagged as the last record (e.g. {@code SESEQ}). Consumed only by
     * {@link OperationType#IS_LAST_IN_GROUP}. Mirrors the Python reference engine's
     * {@code OperationParams.ordering}. JSON key {@code "ordering"}.
     */
    private @Nullable String ordering;

    /**
     * Row-filter predicate for the aggregating operations ({@code record_count}, {@code distinct},
     * {@code min}/{@code max}, {@code min_date}/{@code max_date}, …): a column ⇒ expected-value map
     * where a row qualifies only if <em>every</em> entry matches. Each value is either
     * <ul>
     * <li>a scalar (deserialized as {@link String}) — the cell must equal it (or, when the string
     * ends with {@code "&"} / {@code "%"}, prefix-match it as a trailing wildcard); or</li>
     * <li>a JSON array (deserialized as {@link List}) — <em>list membership</em>: the cell (as a
     * string) must be one of the listed terms, e.g. {@code {DSDECOD: ["INFORMED CONSENT OBTAINED",
     * "RANDOMIZED"]}}.</li>
     * </ul>
     * A missing filter column never matches (yields an empty subset). The value type is
     * {@link Object} so Jackson can deserialize both a scalar and an array; consumers must branch
     * on {@code instanceof List}. Mirrors the Python reference engine's
     * {@code OperationParams.filter} and its {@code _filter_data} ({@code ==} vs
     * {@code .isin(...)}). JSON key {@code "filter"}.
     */
    private @Nullable Map<String, Object> filter;

    private @Nullable List<String> codelists;

    private @Nullable String level;

    private @Nullable String returntype;

    @JsonProperty("key_name")
    private @Nullable String keyName;

    @JsonProperty("key_value")
    private @Nullable String keyValue;

    /**
     * EC-85 — selects the SDTM general-observation-class table the model walk resolves, instead of
     * the class the dataset's own domain resolves to. Consumed by
     * {@code get_model_filtered_variables} only; {@code null} (the default) keeps the own-class
     * walk. The value is the CDISC class name, normalised by
     * {@link net.cumba.corej.core.metadata.SdtmObservationClasses#normalise} (e.g.
     * {@code "EVENTS"}, {@code "INTERVENTIONS"}, {@code "FINDINGS"}, {@code "FINDINGS ABOUT"}). The
     * dataset's own domain still drives the {@code --} substitution, so an EVENTS walk asked from
     * {@code BW} yields {@code BWTERM}. JSON key {@code "model_class"}.
     */
    @JsonProperty("model_class")
    private @Nullable String modelClass;

    @JsonProperty("ct_attribute")
    private @Nullable String ctAttribute;

    private @Nullable String version;

    @JsonProperty("ct_package_types")
    private @Nullable List<String> ctPackageTypes;

    private @Nullable String regex;

    /**
     * Optional regex used by {@code variable_count} to count columns whose names match the pattern
     * instead of looking up a single literal column. When set the {@code name} field is ignored.
     * Anchored matches: the regex is compiled with {@code Pattern.matches()}-style semantics so
     * {@code "^.+FL$"} and {@code ".+FL$"} behave identically.
     */
    @JsonProperty("name_pattern")
    private @Nullable String namePattern;

    @JsonProperty("value_is_reference")
    private @Nullable Boolean valueIsReference;

    /**
     * E7 — the minimum declared length a <em>non-terminal</em> member of a numbered column series
     * ({@code column_series_metadata}) must carry before the next member may be used (e.g. a
     * {@code COVAL} comment column must reach 200 chars before {@code COVAL1} is populated). When
     * set, a non-terminal series member whose declared length is below this threshold makes the
     * series "incomplete" (the check fires). When {@code null} only the numeric-suffix gap is
     * checked. Consumed only by {@link OperationType#COLUMN_SERIES_METADATA}. JSON key
     * {@code "min_length"}.
     */
    @JsonProperty("min_length")
    private @Nullable Integer minLength;

    /**
     * T1 — the external medical-dictionary type (e.g. {@code "meddra"}, {@code "whodrug"},
     * {@code "unii"}, {@code "neoplasm"}) a dictionary operation validates against. Consumed by the
     * {@code dictionary_available} / {@code valid_external_dictionary_*} operations; mirrors the
     * Python reference engine's {@code OperationParams.external_dictionary_type}. Since
     * {@code Fix #268} it is also the key {@code RuleRunner}'s eager no-dictionary SKIP reads: a
     * declared dictionary operation whose type is not loaded — or which names no type at all —
     * SKIPs the rule instead of resolving to {@code null}. JSON key
     * {@code "external_dictionary_type"}.
     */
    @JsonProperty("external_dictionary_type")
    private @Nullable String externalDictionaryType;

    /**
     * T1 — the dictionary level / term type a membership or case check validates against (e.g.
     * MedDRA {@code "PT"} / {@code "SOC"}). Mirrors the Python {@code
     * OperationParams.dictionary_term_type}. JSON key {@code "dictionary_term_type"}.
     */
    @JsonProperty("dictionary_term_type")
    private @Nullable String dictionaryTermType;

    /**
     * T1 — whether an external-dictionary operation ({@code valid_external_dictionary_value} /
     * {@code _code} / {@code _code_term_pair} / {@code _hierarchy}, {@code dictionary_has_decode})
     * compares case-sensitively. D-TA-3 / Fix #266: {@code null} (flag omitted) or {@code true} ⇒
     * case-SENSITIVE — the membership operations validate the term against the dictionary's
     * <em>preferred case</em> (the case-conformance rules, e.g. FDA SD0008C, author {@code true}
     * explicitly), the pair/hierarchy/decode operations compare the as-authored dictionary entries
     * verbatim; {@code false} ⇒ case-folded comparison (insensitive intent must be visible in the
     * rule, e.g. FDA SD0008). Mirrors the Python reference engine's
     * {@code OperationParams.case_sensitive}. JSON key {@code "case_sensitive"}.
     */
    @JsonProperty("case_sensitive")
    private @Nullable Boolean caseSensitive;

    /**
     * T1 — the companion term column for a code&harr;decode pairing operation
     * ({@code valid_external_dictionary_code_term_pair}): the {@code name} column carries the code,
     * this column carries the decode/term. Mirrors the Python {@code
     * OperationParams.external_dictionary_term_variable}. JSON key
     * {@code "external_dictionary_term_variable"}.
     */
    @JsonProperty("external_dictionary_term_variable")
    private @Nullable String externalDictionaryTermVariable;

    /**
     * T1 — the parent (ancestor) column for a hierarchy-path operation
     * ({@code valid_external_dictionary_hierarchy}): the {@code name} column carries the child
     * term, this column carries the candidate ancestor whose hierarchy path the child must lie on.
     * Mirrors the Python {@code OperationParams.dictionary_parent}. JSON key
     * {@code "dictionary_parent"}.
     */
    @JsonProperty("dictionary_parent")
    private @Nullable String dictionaryParent;

    /**
     * EC-23 — opt-in row qualifier for the {@code has_mixed_emptiness_within_group} operation
     * (Java-only). When set, the per-group emptiness tally in
     * {@link net.cumba.corej.core.exec.OperationExecutor#evalHasMixedEmptinessWithinGroup} skips
     * any group row where <em>none</em> of the listed columns is populated (populated = non-missing
     * AND non-blank after {@code strip()}); only the surviving rows contribute to the populated /
     * unpopulated determination. When {@code null} the scan considers every group row (the original
     * behaviour, byte-identical). This scopes the mixedness determination to the source-relevant
     * rows (e.g. AD0735's "rows where BASE or BASEC are populated") without a first-row-gated
     * Check. Consumed only by {@link OperationType#HAS_MIXED_EMPTINESS_WITHIN_GROUP}. JSON key
     * {@code "qualifying_any_populated"}.
     */
    @JsonProperty("qualifying_any_populated")
    private @Nullable List<String> qualifyingAnyPopulated;

    /**
     * Pre-resolution value of {@link #name}, stashed by {@code RuleRunner.resolveOperationPrefix}
     * when it rewrites {@code --} templates to a concrete domain prefix. Study-wide Operations
     * (e.g., {@code variable_count}) read this field to re-resolve the template per-dataset;
     * operations that keep single-domain semantics can ignore it. Not part of the JSON rule
     * contract — populated only at runtime.
     */
    @JsonIgnore
    private @Nullable String originalName;

    @JsonIgnore
    public @Nullable OperationType getOperationType()
    {
        return OperationType.fromJson(operator);
    }

}
