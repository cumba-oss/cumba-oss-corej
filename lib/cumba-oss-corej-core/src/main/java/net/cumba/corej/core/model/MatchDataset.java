package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class MatchDataset
{

    @JsonProperty("Name")
    private @Nullable String name;

    /**
     * Raw {@code Match_Datasets} join keys. Each entry is EITHER a bare string (a <b>same-named</b>
     * key — the column carries the identical name on the primary/left and joined/right sides; the
     * historical shape) OR a JSON object {@code {"left": <col>, "right": <col>}} declaring a
     * <b>differently-named</b> key on each side (the "sided" shape). The sided shape mirrors the
     * Python reference engine, which already accepts it via {@code get_sided_match_keys}
     * ({@code cdisc_rules_engine/utilities/utils.py}) consumed in {@code dataset_preprocessor.py};
     * this field is the Java catch-up (EC-18 / P5c). No shipped rule uses the sided shape yet.
     * <p>
     * Held as a raw {@link JsonNode} so both shapes round-trip faithfully. Consumers read the
     * normalised accessors: {@link #getKeys()} returns the left/primary-side names (byte-identical
     * to the historical {@code List<String>} accessor for the bare-string shape) and
     * {@link #getRightKeys()} returns the joined/right-side names. JSON key {@code "Keys"}.
     */
    @JsonProperty("Keys")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private @Nullable JsonNode keysNode;

    @JsonProperty("Wildcard")
    private @Nullable String wildcard;

    @JsonProperty("Child")
    private @Nullable Boolean child;

    @JsonProperty("Join_Type")
    private @Nullable String joinType;

    /**
     * Whether rows whose key is <b>blank</b> — {@code ""} or any
     * {@link net.cumba.datatable.values.MissingValue} — take part in this join
     * ({@code PLAN-join-key-missing-semantics}; register {@code JKM R4}).
     *
     * <p>
     * ⭐⭐ <b>{@code null} means KEEP.</b> Owner, 2026-09-21: <i>"From my point of view the DROP is
     * the bug we need to fix. There is no reason to remove a row from the merge because there is a
     * missing value in one of the keys. Especially if both tables have rows that would match up.
     * Therefore I rule KEEP is the default and DROP must explicitly be authored if needed."</i> ⇒
     * the join surface's default is the <b>opposite</b> of the Check-level {@code Grouping:}
     * default ({@link GroupingSpec#getKeepMissings()}, which stays <b>drop</b>) — deliberately,
     * because grouping <em>collapses</em> blank-keyed rows into one bucket while a join
     * <em>pairs</em> them, so the 2026-08 flood mechanism is not present in the same shape.
     * </p>
     *
     * <p>
     * ⛔ <b>This flag governs PARTICIPATION only, never IDENTITY</b> ({@code JKM R5}, owner: <i>"if
     * they are kept, they are kept as separate identities. a MIS will not join a record with an
     * empty string and a MIS_A will not join a record with a MIS or MIS_B."</i>). Identity is exact
     * with the flag on <b>or</b> off, and it is carried by
     * {@link net.cumba.corej.core.exec.GroupKeyPolicy.KeyPart} rather than by any rendered key — a
     * stringified key would make a participating {@code MIS} collide with a present {@code "."}.
     * </p>
     *
     * <p>
     * ⚠ Under an authored {@code false}, a key column that is <b>absent on one side</b> makes every
     * row on that side blank at that component, so the join matches nothing. That follows from the
     * author's own declaration ({@code JKM R7} gives the absent column its type default, and DROP
     * drops blanks) and is not a separate rule. JSON key {@code "keep_missings"} — the same
     * spelling the two grouping surfaces use.
     * </p>
     */
    @JsonProperty("keep_missings")
    private @Nullable Boolean keepMissings;

    /**
     * The effective participation policy: {@code true} unless the rule authored {@code false}.
     *
     * @return whether blank-keyed rows take part in this join ({@code JKM R4}: the default is KEEP)
     */
    @JsonIgnore
    public boolean keepMissingKeys()
    {
        return keepMissings == null || keepMissings;
    }

    /**
     * The pre-merge filter (phase 5b-J — spec §3.3 / D88c, {@code PLAN-join-match-flag.md} §8): a
     * {@code boolean}-typed expression evaluated against the joined dataset's <b>own</b> rows,
     * <b>before</b> the key index is built. Rows failing it can never become a join partner, so
     * {@code Filter: 'AEOUT == "FATAL"'} + {@code AE._matched_} asks <i>"does this subject have a
     * fatal AE?"</i> without depending on which AE row survives the join collapse.
     *
     * <p>
     * Right-side columns only (a left-side or {@code $}-reference is a stage-A load error — spec
     * §3.3's SPEC CHOICE: a correlated sub-join is a different feature); an unresolvable filter
     * column is a bind error unless declared in {@code Requirements.Variables}, which skips cleanly
     * (D89/D89a). JSON key {@code "Filter"}.
     * </p>
     */
    @JsonProperty("Filter")
    private @Nullable String filter;

    /**
     * Memoised parse of {@link #filter} — computed on first use, shared safely across threads
     * (parsing is deterministic, so a rare double parse is harmless). Never serialised; survives
     * {@code RuleSpecialiser}'s reflective shallow copy (the filter text is never specialised, so
     * the cached tree stays valid). Excluded from equals/hashCode/toString: it is derived state.
     */
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private transient net.cumba.corej.core.expr.ast.@Nullable Expr filterExprCache;

    /** Replaces the Lombok setter: a new filter text must drop the memoised parse. */
    public void setFilter(@Nullable String filter)
    {
        this.filter = filter;
        this.filterExprCache = null;
    }


    /**
     * The parsed {@link #filter} expression, or {@code null} when the entry declares none.
     *
     * @throws net.cumba.corej.core.expr.ExpressionException
     *             if the filter text does not parse — the loader's stage-A pass converts this into
     *             a load error ({@code FILTER_INVALID}), so a malformed filter parks the rule
     *             rather than silently filtering nothing or everything
     */
    @JsonIgnore
    public net.cumba.corej.core.expr.ast.@Nullable Expr filterExpr()
    {
        String text = filter;
        if (text == null || text.isBlank())
        {
            return null;
        }
        net.cumba.corej.core.expr.ast.Expr cached = filterExprCache;
        if (cached == null)
        {
            cached = net.cumba.corej.core.expr.CheckExpressionParser.parse(text);
            filterExprCache = cached;
        }
        return cached;
    }


    /**
     * Left/primary-side join key names. A bare-string entry contributes its own name; a sided
     * {@code {left, right}} entry contributes its {@code left} name. Returns {@code null} when no
     * keys are declared, matching the historical field accessor so existing callers
     * ({@code RelrecRowExpander}, {@code KeyMatchRowExpander}, {@code ChildMatchPreMerger},
     * {@code RuleRunner.buildJoinedDatasets}) stay byte-identical for the bare-string shape.
     */
    @JsonIgnore
    public @Nullable List<String> getKeys()
    {
        return sidedKeys("left");
    }


    /**
     * Joined/right-side join key names. A bare-string entry contributes its own name; a sided
     * {@code {left, right}} entry contributes its {@code right} name. Returns {@code null} when no
     * keys are declared. Equal to {@link #getKeys()} whenever every entry is a bare string.
     */
    @JsonIgnore
    public @Nullable List<String> getRightKeys()
    {
        return sidedKeys("right");
    }


    /** {@code true} when at least one key entry is a sided {@code {left, right}} object. */
    @JsonIgnore
    public boolean hasSidedKeys()
    {
        if (keysNode == null || !keysNode.isArray())
        {
            return false;
        }
        for (JsonNode n : keysNode)
        {
            if (n.isObject())
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Programmatic setter — stores a same-named key list (every element becomes a bare string
     * entry). Sided keys are only authored via JSON, so there is no {@code List}-based sided
     * setter.
     */
    public void setKeys(@Nullable List<String> keys)
    {
        if (keys == null)
        {
            this.keysNode = null;
            return;
        }
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (String k : keys)
        {
            arr.add(k);
        }
        this.keysNode = arr;
    }


    private @Nullable List<String> sidedKeys(String side)
    {
        if (keysNode == null || keysNode.isNull() || !keysNode.isArray())
        {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : keysNode)
        {
            if (n.isTextual())
            {
                out.add(n.asText());
            }
            else if (n.isObject())
            {
                JsonNode s = n.get(side);
                if (s != null && s.isTextual())
                {
                    out.add(s.asText());
                }
            }
        }
        return out;
    }

}
