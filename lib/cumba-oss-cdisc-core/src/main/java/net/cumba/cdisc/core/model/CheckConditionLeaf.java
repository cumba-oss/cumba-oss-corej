package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jspecify.annotations.Nullable;

// All leaf fields are optional on the wire; @Builder's generated all-args constructor trips
// NullAway's init check (NullAway#917). @Nullable fields carry the real contract.
@SuppressWarnings("NullAway.Init")
@Value
@Builder
@Jacksonized
// Override the interface-level @JsonSerialize so Jackson uses its default
// POJO serialiser for leaves (the interface serialiser only handles composites).
@JsonSerialize(using = JsonSerializer.None.class)
public class CheckConditionLeaf implements CheckCondition
{

    @Nullable
    String name;

    /**
     * Composite (multi-column) target for tuple membership (T3): the ordered list of columns whose
     * per-row tuple is tested against a reference tuple set, e.g.
     * {@code names: ["VISIT", "VISITNUM"]} with {@code operator: is_not_contained_by} and
     * {@code value: $tv_visit_keys}. Mutually exclusive with {@link #name}: when present,
     * {@link net.cumba.cdisc.core.expr.CheckToExpr} raises the leaf to
     * {@code tuple(VISIT, VISITNUM) [not] in <value>}. Only the composite membership operators
     * ({@code is_contained_by}/{@code is_not_contained_by}) consume it. JSON key {@code "names"}.
     */
    @Nullable
    List<String> names;

    @Nullable
    String operator;

    @Nullable
    JsonNode value;

    @JsonProperty("value_is_literal")
    @Nullable
    Boolean valueIsLiteral;

    @JsonProperty("value_is_reference")
    @Nullable
    Boolean valueIsReference;

    @JsonProperty("type_insensitive")
    @Nullable
    Boolean typeInsensitive;

    @Nullable
    Boolean negative;

    @Nullable
    String regex;

    /**
     * Opt-in emptiness switch for the consistency operators (Fix #121, Java-only; default
     * {@code false}). When {@code true}, {@link CheckOperator#HAS_MULTIPLE_VALUES_FOR} and
     * {@link CheckOperator#IS_INCONSISTENT_ACROSS_DATASET} treat a blank cell as a real
     * participating value instead of excluding it (the D.13 / D.2 emptiness-exceptions in
     * {@code operator-examples.md}), so "one side populated, the other blank" counts as a
     * conflicting value. Consumed only by those two operators; {@code CheckToExpr} rejects it on
     * any other operator. No Python counterpart — a rule carrying this field is Java-only.
     */
    @JsonProperty("include_empty")
    @Nullable
    Boolean includeEmpty;

    /**
     * Whether a row whose grouping key carries a missing value stays in its group (folded under the
     * blank key) or is dropped along with its whole group. Consumed by the group-aware operators —
     * those keyed by {@link #within} ({@code has_multiple_values_for},
     * {@code present_on_multiple_rows_within}, {@code does_not_have_next_corresponding_record},
     * {@code empty_within_except_last_row}, {@code target_is_not_sorted_by}) and those keyed by an
     * array {@link #value} ({@code is_not_unique_set}, {@code is_inconsistent_across_dataset}).
     * {@code CheckToExpr} rejects it on any other operator, so it can never sit silently dead.
     *
     * <p>
     * {@code null} — the shipped state of every rule — means "engine default", which differs per
     * operator family today: the {@code within:}-keyed operators and
     * {@code is_inconsistent_across_dataset} <b>discard</b> a missing key, while
     * {@code is_not_unique_set} and {@code target_is_not_sorted_by} <b>fold</b> it. The defaults
     * are unchanged here.
     * </p>
     *
     * <p>
     * ⚠ Not {@link #includeEmpty}, which governs the orthogonal <em>value-participation</em> axis
     * (does a blank <em>target</em> count as a value) rather than <em>group membership</em> (is a
     * row with a blank <em>key</em> in a group at all). A rule may legitimately declare both.
     * </p>
     */
    @JsonProperty("keep_missings")
    @Nullable
    Boolean keepMissings;

    /**
     * EC-87 — the relation a neighbouring cell pair must stand in for
     * {@code does_not_have_next_corresponding_record} to consider a corresponding record present.
     * One of {@code "=="} (the default, and identical to omitting the field), {@code "<="} or
     * {@code ">="} ({@link NextRecordRelation}). The relation is applied <b>in disjunction with</b>
     * the shipped {@code KeyPart}-identity rule, so it can only widen what corresponds — never
     * narrow it, and never change how blanks or missings compare ({@code W38-A1} / {@code Fix #249}
     * stand). Consumed only by that operator and its positive twin; {@code CheckToExpr} and
     * {@code RulePackageLoader} both reject it anywhere else, so it can never sit silently dead. No
     * Python counterpart — a rule carrying this field is Java-only.
     */
    @Nullable
    String relation;

    @Nullable
    Integer prefix;

    @Nullable
    Integer suffix;

    /**
     * Partition columns for group-aware operators (e.g.
     * {@link CheckOperator#HAS_MULTIPLE_VALUES_FOR}, {@code present_on_multiple_rows_within}).
     * Polymorphic on the wire — accepts either a single column name as a string ({@code "PARAMCD"})
     * or a list of column names ({@code ["USUBJID", "SPDEVID", "PARAMCD"]}). Stored as a
     * {@link JsonNode} to preserve the raw shape; consumers should call {@link #getWithinColumns()}
     * to obtain a normalised {@link List List&lt;String&gt;} regardless of the wire form.
     * <p>
     * Fix #25 introduced the multi-column form for {@code has_multiple_values_for}. Existing
     * single-column callers (e.g. {@code present_on_multiple_rows_within}) still resolve via
     * {@code getWithinColumns().get(0)} when the list is exactly one element; multi-column input to
     * operators that don't support it is treated as if {@code within} were absent (ungrouped),
     * matching the prior behaviour for unrecognised values.
     */
    @Nullable
    JsonNode within;

    @Nullable
    String ordering;

    @JsonIgnore
    public @Nullable CheckOperator getCheckOperator()
    {
        return CheckOperator.fromJson(operator);
    }


    /**
     * Returns {@code within} normalised to a list of column names. Empty list when {@code within}
     * is null/empty/non-textual content; one-element list for a single string; multi-element list
     * for an array of strings. Non-string array entries are skipped silently — malformed
     * {@code within} values produce a partial list rather than throwing, matching the engine's
     * tolerant deserialisation philosophy elsewhere.
     */
    @JsonIgnore
    public List<String> getWithinColumns()
    {
        if (within == null || within.isNull())
        {
            return List.of();
        }
        if (within.isTextual())
        {
            String t = within.asText();
            return (t == null || t.isEmpty()) ? List.of() : List.of(t);
        }
        if (within.isArray())
        {
            List<String> out = new ArrayList<>();
            for (JsonNode n : within)
            {
                if (n != null && n.isTextual())
                {
                    String t = n.asText();
                    if (t != null && !t.isEmpty())
                    {
                        out.add(t);
                    }
                }
            }
            return out;
        }
        return List.of();
    }


    /**
     * Returns {@code within} normalised to a list of key <i>components</i> (EC-24). Each top-level
     * {@code within} entry is one key component; a component is either a single column (a plain
     * string entry, or a top-level array element — today's composite-key form) or a
     * <b>coalesce-group</b> (a nested array of columns → the component's value is the first
     * <i>populated</i> column). The normalised shape is {@code List<List<String>>}:
     * <ul>
     * <li>{@code within: "USUBJID"} → {@code [[USUBJID]]}</li>
     * <li>{@code within: [USUBJID, PARAMCD]} → {@code [[USUBJID], [PARAMCD]]} (composite, identical
     * to the pre-EC-24 flat form via {@link #getWithinColumns()})</li>
     * <li>{@code within: [[USUBJID, POOLID]]} → {@code [[USUBJID, POOLID]]} (one coalesce
     * component)</li>
     * <li>{@code within: [[USUBJID, POOLID], PARAMCD]} →
     * {@code [[USUBJID, POOLID], [PARAMCD]]}</li>
     * </ul>
     * When every component is a singleton this is exactly the pre-EC-24 flat key list wrapped
     * one-per-list, so consumers that delegate to the index-based partition behave identically.
     * Empty / non-textual entries are skipped silently (the tolerant contract of
     * {@link #getWithinColumns()}); an empty coalesce-group contributes no component.
     */
    @JsonIgnore
    public List<List<String>> getWithinComponents()
    {
        if (within == null || within.isNull())
        {
            return List.of();
        }
        if (within.isTextual())
        {
            String t = within.asText();
            return (t == null || t.isEmpty()) ? List.of() : List.of(List.of(t));
        }
        if (within.isArray())
        {
            List<List<String>> out = new ArrayList<>();
            for (JsonNode n : within)
            {
                if (n == null)
                {
                    continue;
                }
                if (n.isTextual())
                {
                    String t = n.asText();
                    if (t != null && !t.isEmpty())
                    {
                        out.add(List.of(t));
                    }
                }
                else if (n.isArray())
                {
                    List<String> comp = new ArrayList<>();
                    for (JsonNode e : n)
                    {
                        if (e != null && e.isTextual())
                        {
                            String t = e.asText();
                            if (t != null && !t.isEmpty())
                            {
                                comp.add(t);
                            }
                        }
                    }
                    if (!comp.isEmpty())
                    {
                        out.add(comp);
                    }
                }
            }
            return out;
        }
        return List.of();
    }

}
