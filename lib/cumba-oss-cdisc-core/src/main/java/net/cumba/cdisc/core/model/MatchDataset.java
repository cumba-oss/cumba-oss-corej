package net.cumba.cdisc.core.model;

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
     * Left/primary-side join key names. A bare-string entry contributes its own name; a sided
     * {@code {left, right}} entry contributes its {@code left} name. Returns {@code null} when no
     * keys are declared, matching the historical field accessor so existing callers
     * ({@code RelrecRowExpander}, {@code RuleCohortGrouper}, {@code KeyMatchRowExpander},
     * {@code ChildMatchPreMerger}, {@code RuleRunner.buildJoinedDatasets}) stay byte-identical for
     * the bare-string shape.
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
