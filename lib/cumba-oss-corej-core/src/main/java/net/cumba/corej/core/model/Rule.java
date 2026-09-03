package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Rule
{

    private @Nullable String id;

    @JsonProperty("Core")
    private @Nullable RuleCore core;

    @JsonProperty("Description")
    private @Nullable String description;

    /**
     * {@code Rule_Type} is <b>gone</b> ({@code PLAN-leaf-scope-domain-inference.md}, owner ruling 6
     * / Q3): the engine routes on the inferred {@link #evaluationDomain}, and the one non-derivable
     * bit the taxonomy carried is the explicit {@link #variableUniverse}. This binding survives
     * <em>solely to reject the key</em> — Jackson would otherwise silently ignore it — by recording
     * it here for {@link net.cumba.corej.core.RulePackageLoader} to turn into a load error carrying
     * the migration guidance. Never serialised.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String rejectedRuleType;

    /**
     * Typed {@code Sensitivity}; {@code null} when the JSON field is absent <em>or</em> carried an
     * unrecognized string. JSON binding goes through the {@code @JsonSetter}/{@code @JsonGetter}
     * pair below; the raw string is kept in {@link #rawSensitivity} so
     * {@link net.cumba.corej.core.RulePackageLoader} can fail loud on present-but-invalid values
     * while an absent field keeps the {@code null} semantics. Both fields carry {@code @JsonIgnore}
     * — without it Jackson would auto-detect the Lombok accessors and emit duplicate properties
     * alongside the title-case key. The Lombok-generated typed accessors stay for programmatic use.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Sensitivity sensitivity;

    /** Raw JSON {@code Sensitivity} string, kept verbatim for load-time validation / round-trip. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String rawSensitivity;

    /** Typed {@code Executability}; see {@link #sensitivity} for the raw/typed binding contract. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Executability executability;

    /**
     * Raw JSON {@code Executability} string, kept verbatim for load-time validation / round-trip.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String rawExecutability;

    /**
     * Typed {@code Severity}; see {@link #sensitivity} for the raw/typed binding contract.
     *
     * <p>
     * &#9873;&#9873; <b>{@code null} means "absent", which means {@link Severity#ERROR}</b> — read
     * it through {@link #effectiveSeverity()}, never by testing this field for {@code null} to mean
     * anything else. The field is <b>omitted whenever it equals the default</b> (Plan C ruling 5),
     * so ~3 473 of the 3 804 shipped rules carry no {@code Severity} key at all and
     * {@code Severity: "Error"} is not a legal shipped spelling — {@code RuleCanonicalizer} strips
     * it. That mirrors {@code ConformanceRule.effectiveSeverity()} in
     * {@code cumba-oss-corej-define-conformance}, which has shipped the same absent-means-ERROR
     * convention on a sibling corpus.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Severity severity;

    /** Raw JSON {@code Severity} string, kept verbatim for load-time validation / round-trip. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String rawSeverity;

    /**
     * Typed {@code Variable_Universe} ({@code PLAN-leaf-scope-domain-inference.md} §3.7): which
     * variables the VAR cursor iterates. {@code null} (absent) means {@link VariableUniverse#DATA};
     * see {@link #sensitivity} for the raw/typed binding contract.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable VariableUniverse variableUniverse;

    /** Raw JSON {@code Variable_Universe} string, kept verbatim for load-time validation. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String rawVariableUniverse;

    /**
     * Typed programmatic setter (review F5). Hand-written (winning over the Lombok
     * {@code @Data}-generated setter) so the raw JSON string stays in sync: a non-null enum
     * rewrites the raw field to its canonical JSON value, {@code null} clears it. Without the sync,
     * loading a rule with an invalid raw (e.g. {@code "Recodr"}) and later calling
     * {@code setSensitivity(null)} would leave {@link #getSensitivityJson()} resurfacing the stale
     * raw string on serialization.
     *
     * @param sensitivity
     *            the typed value, or {@code null} to clear the field
     */
    public void setSensitivity(@Nullable Sensitivity sensitivity)
    {
        this.sensitivity = sensitivity;
        this.rawSensitivity = sensitivity != null ? sensitivity.getJsonValue() : null;
    }


    /** Typed programmatic setter; see {@link #setSensitivity} for the raw-sync contract (F5). */
    public void setExecutability(@Nullable Executability executability)
    {
        this.executability = executability;
        this.rawExecutability = executability != null ? executability.getJsonValue() : null;
    }


    /** Typed programmatic setter; see {@link #setSensitivity} for the raw-sync contract (F5). */
    public void setSeverity(@Nullable Severity severity)
    {
        this.severity = severity;
        this.rawSeverity = severity != null ? severity.getJsonValue() : null;
    }


    /**
     * The rule's effective severity: the authored value, or {@link Severity#ERROR} when the field
     * is absent.
     *
     * <p>
     * This is the <b>only</b> correct way to read a rule's severity. An absent {@code Severity} is
     * not "unknown" — it is {@code ERROR}, stated once here so no caller has to remember it.
     * </p>
     *
     * @return the authored severity, or {@link Severity#ERROR} when none was authored
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Severity effectiveSeverity()
    {
        return severity != null ? severity : Severity.ERROR;
    }


    /**
     * The rule's declared check levels, <b>never {@code null}</b> — the single documented reader of
     * {@link #checkLevels} (Plan C &#167;3.3).
     *
     * <p>
     * Precedence: the authored level map when there is one; otherwise the synthesised one-level map
     * <code>{effectiveSeverity(): (check, null)}</code>, which is what a plain {@code Check:}
     * <em>means</em> — "one level, at the rule's {@code Severity}". A rule with no {@code Check:}
     * at all yields an empty map.
     * </p>
     *
     * <p>
     * &#9888;&#9888; <b>Every walker that validates or collects over a rule's Check must read this,
     * not {@code getCheck()}.</b> {@code getCheck()} is the strictest level alone; a gate that
     * reads it sees nothing of a weaker level, so a malformed operand, an undeclared provider
     * dependency or an unresolved wildcard sitting in an {@code INFO} level would load clean and
     * misbehave at runtime. Structural readers that genuinely want the rule's strongest statement
     * (the cohort key, the {@code --}-expansion seam) keep reading {@code getCheck()} on purpose.
     * </p>
     *
     * @return the declared levels, strictest first; empty when the rule declares no Check
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public SequencedMap<Severity, LevelCheck> effectiveCheckLevels()
    {
        if (checkLevels != null)
        {
            return checkLevels;
        }
        if (check == null)
        {
            return new LinkedHashMap<>(0);
        }
        return LevelCheck.single(effectiveSeverity(), check);
    }


    /**
     * Every declared level's condition, strictest first — the walk surface for a gate or a
     * collector that must see the <b>whole</b> rule rather than only its strongest statement.
     *
     * <p>
     * Shorthand for {@code effectiveCheckLevels().values()} mapped to conditions; a one-element
     * list holding exactly {@code getCheck()} for every rule that authors a plain {@code Check:},
     * so widening a walker to it cannot change what that walker does on the shipped corpus.
     * </p>
     *
     * @return the conditions of every declared level; empty when the rule declares no Check
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<CheckCondition> checkConditions()
    {
        if (checkLevels == null)
        {
            return check == null ? List.of() : List.of(check);
        }
        List<CheckCondition> out = new java.util.ArrayList<>(checkLevels.size());
        for (LevelCheck level : checkLevels.values())
        {
            out.add(level.condition());
        }
        return out;
    }


    /**
     * Sets the strictest level's condition, keeping the {@link #checkLevels} invariant.
     *
     * <p>
     * Hand-written (winning over the Lombok {@code @Data} setter) because {@link #check}
     * <em>is</em> the first entry of {@link #checkLevels} when a level map is present: the loader's
     * inlining seams ({@code inlineVariableExistsOps}, {@code inlineSplitByOps}) and the generators
     * rewrite the Check through this setter, and a level map left holding the pre-rewrite condition
     * would evaluate the un-inlined tree at its own level.
     * </p>
     *
     * @param check
     *            the strictest level's condition, or {@code null} to clear the whole Check
     */
    public void setCheck(@Nullable CheckCondition check)
    {
        this.check = check;
        if (check == null)
        {
            // No Check at all — a level map would have nothing to be the strictest entry of.
            this.checkLevels = null;
            return;
        }
        if (checkLevels != null && !checkLevels.isEmpty())
        {
            // Rebuild rather than mutate in place: setCheckLevels stores the map wrapped
            // unmodifiable, so a put() here would throw UnsupportedOperationException on every
            // setCheck-after-setCheckLevels call.
            Map.Entry<Severity, LevelCheck> strictest = checkLevels.firstEntry();
            LinkedHashMap<Severity, LevelCheck> rebuilt = new LinkedHashMap<>(checkLevels);
            rebuilt.put(strictest.getKey(), new LevelCheck(check, strictest.getValue().message()));
            this.checkLevels = java.util.Collections.unmodifiableSequencedMap(rebuilt);
        }
    }


    /**
     * Sets the level map, keeping the {@link #check} invariant.
     *
     * <p>
     * Hand-written for the same reason {@link #setCheck} is, from the other side: the three
     * {@code new Rule()} clone sites in {@code net.cumba.corej.core.gen} rewrite a template's Check
     * <em>and</em> its levels, and the two must not disagree about the strictest condition.
     * </p>
     *
     * @param levels
     *            the level map, strictest first, or {@code null} for a plain single-level Check
     */
    public void setCheckLevels(@Nullable SequencedMap<Severity, LevelCheck> levels)
    {
        // LevelCheck.byLadder both re-orders strictest-first (§3.4 step 3 — the deserialiser
        // already sorts, but a map handed in programmatically may arrive in any order, and an
        // unsorted map would let the WEAKEST rung claim first with nothing noticing) and takes the
        // defensive copy: the map is handed in by the loader, the Jackson binding and the three
        // gen/ clone sites, and is then read on the per-rule-execution path. Copying here — a
        // cold, load-time path — lets effectiveCheckLevels() share the map with no allocation.
        this.checkLevels = levels == null ? null
                : java.util.Collections.unmodifiableSequencedMap(LevelCheck.byLadder(levels));
        if (this.checkLevels != null && !this.checkLevels.isEmpty())
        {
            // Read the strictest entry off the LADDER-ORDERED map, never off the caller's map,
            // whose first entry may be any rung.
            this.check = this.checkLevels.firstEntry().getValue().condition();
        }
    }


    /**
     * Jackson binding for {@code Check}: dispatches &#167;3.3's grammar (plain condition / level
     * map / grammar violation) and lands each in its own field.
     *
     * <p>
     * &#9873; Like {@link #setSeverityJson}, a malformed value is <b>kept, not thrown</b>: the
     * violation goes to {@link #rawCheckLevels} so {@code RulePackageLoader.validateEnumFields} can
     * report it as a per-rule load error naming the rule, instead of aborting the package load with
     * a Jackson exception that names none.
     * </p>
     *
     * @param binding
     *            the bound {@code Check:} value
     */
    @JsonSetter("Check")
    public void setCheckJson(@Nullable RuleCheck binding)
    {
        if (binding == null)
        {
            this.check = null;
            this.checkLevels = null;
            this.rawCheckLevels = null;
            return;
        }
        this.rawCheckLevels = binding.grammarError();
        if (binding.levels() != null)
        {
            setCheckLevels(binding.levels());
        }
        else
        {
            this.checkLevels = null;
            this.check = binding.single();
        }
    }


    /**
     * Jackson serialization of {@code Check}: the level map when the rule declares one, else the
     * plain condition — which is what every shipped rule emits, byte-identically to the binding
     * this pair replaced.
     *
     * @return the {@code Check} payload, or {@code null} when the rule declares none
     */
    @JsonGetter("Check")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = RuleCheckSerializer.class)
    public @Nullable Object getCheckJson()
    {
        return checkLevels != null ? checkLevels : check;
    }


    /** Typed programmatic setter; see {@link #setSensitivity} for the raw-sync contract (F5). */
    public void setVariableUniverse(@Nullable VariableUniverse variableUniverse)
    {
        this.variableUniverse = variableUniverse;
        this.rawVariableUniverse = variableUniverse != null ? variableUniverse.getJsonValue()
                : null;
    }


    /**
     * The Output_Variables list every execution-side consumer reads (EC-37): the derived
     * {@link #effectiveOutputVariables} when the load-time derivation ran, else the authored
     * {@code Outcome.Output_Variables} (empty when absent) — in that fallback with every {@code !X}
     * exclusion token stripped <em>and</em> applied ({@link OutputVariableToken#applyExclusions}),
     * so the {@code -Dcorej.autoOutputVariables=false} kill switch never leaks a raw token or an
     * excluded name into a finding. Never {@code null}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<String> effectiveOutputVariablesOrAuthored()
    {
        if (effectiveOutputVariables != null)
        {
            return effectiveOutputVariables;
        }
        return OutputVariableToken
                .applyExclusions(outcome != null ? outcome.getOutputVariables() : null);
    }


    /**
     * The names the author excluded from this rule's findings with {@code !X} entries
     * ({@link OutputVariableToken}): the set {@code RulePackageLoader#deriveOutputVariables} stored
     * next to {@link #effectiveOutputVariables}, else — when the derivation did not run — the same
     * set read straight off the authored list. Runtime fallbacks that infer output columns outside
     * the derivation ({@code RuleRunner}'s Fix #15 Check-leaf inference, the builders' "no
     * Output_Variables" defaults) filter by it, so an excluded variable is absent on every
     * projection path. Never {@code null}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Set<String> excludedOutputVariablesOrAuthored()
    {
        if (excludedOutputVariables != null)
        {
            return excludedOutputVariables;
        }
        return OutputVariableToken
                .exclusions(outcome != null ? outcome.getOutputVariables() : null);
    }


    /**
     * The rule-level grouping key every execution-side consumer reads: {@code Grouping.Variables}
     * when the {@code Grouping:} block is present, else the flat {@code Grouping_Variables:}.
     * {@code null} when the rule declares neither — which is the "not a grouped rule" signal, so
     * callers must keep distinguishing {@code null} from an empty list exactly as they did before
     * the block existed.
     *
     * <p>
     * ⚠ Read this, not {@code getGroupingVariables()}. The Lombok getter returns only the flat
     * field and is now a partial view of the rule; it is retained because the generators and the
     * Python-facing legacy converter deliberately work in the flat shape.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public @Nullable List<String> effectiveGroupingVariables()
    {
        if (grouping != null && grouping.getVariables() != null)
        {
            return grouping.getVariables();
        }
        return groupingVariables;
    }


    /**
     * The rule's variable requirements — {@code Requirements.Variables}.
     *
     * <p>
     * This was the dual-read shim that carried the corpus across the {@code Scope.Variables} →
     * {@code Requirements.Variables} migration ({@code plans/PLAN-scope-requirements-split.md}
     * phase 1). Phase 5 dropped the legacy binding, so there is exactly one spelling left and this
     * is now a plain accessor. It is kept — rather than inlined at its ~20 call sites — for the
     * same reason {@link #effectiveGroupingVariables()} is: it is the single documented reader, and
     * a future second spelling resolves here or nowhere.
     * </p>
     *
     * <p>
     * ⛔ {@code getScope().getVariables()} no longer exists: {@link Scope} has no {@code Variables}
     * property, and a surviving {@code Scope: {Variables: …}} in a corpus rule is a load error
     * (gate R1), not a silently-unscoped rule.
     * </p>
     *
     * @return the effective variable requirement, or {@code null} when the rule declares none
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public @Nullable VariableRequirement effectiveVariableRequirement()
    {
        return requirements == null ? null : requirements.getVariables();
    }


    /**
     * The authored rule-level {@code keep_missings}, or {@code null} when the rule is silent (every
     * shipped rule today). Only the {@code Grouping:} block can carry it — the flat
     * {@code Grouping_Variables:} form has nowhere to put it, which is the point of the
     * restructuring.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public @Nullable Boolean groupingKeepMissings()
    {
        return grouping != null ? grouping.getKeepMissings() : null;
    }


    /**
     * Jackson binding for the retired {@code Rule_Type} key: records the offending value so the
     * loader rejects the rule with the migration guidance (owner ruling 6). There is no getter —
     * the key is never written back.
     */
    @JsonSetter("Rule_Type")
    public void setRuleTypeJson(@Nullable String raw)
    {
        // Key presence is what is rejected: an explicit JSON null reaches this setter too, and
        // "Rule_Type": null is still a rule carrying the retired field.
        this.rejectedRuleType = raw != null ? raw : "null";
    }


    /** Jackson binding for {@code Sensitivity}: stores the raw string and the parsed enum. */
    @JsonSetter("Sensitivity")
    public void setSensitivityJson(@Nullable String raw)
    {
        this.rawSensitivity = raw;
        this.sensitivity = Sensitivity.fromJson(raw);
    }


    /**
     * Jackson serialization of {@code Sensitivity}: the canonical enum value when valid, otherwise
     * the raw string verbatim.
     *
     * @return the JSON value for {@code Sensitivity}, or {@code null} when absent
     */
    @JsonGetter("Sensitivity")
    public @Nullable String getSensitivityJson()
    {
        return sensitivity != null ? sensitivity.getJsonValue() : rawSensitivity;
    }


    /**
     * Jackson binding for {@code Severity}: stores the raw string and the leniently-parsed enum.
     *
     * <p>
     * &#9873; It parses through {@link Severity#parseOrNull}, <b>not</b> the strict
     * {@link Severity#fromJson}, precisely so an invalid authored value survives to
     * {@code RulePackageLoader.validateEnumFields} and is reported as a per-rule load error naming
     * the rule — rather than aborting deserialisation with a Jackson exception that names none. The
     * strict door stays strict for <em>report</em> deserialisation, where nothing revalidates.
     * </p>
     *
     * @param raw
     *            the authored spelling, kept verbatim
     */
    @JsonSetter("Severity")
    public void setSeverityJson(@Nullable String raw)
    {
        this.rawSeverity = raw;
        this.severity = Severity.parseOrNull(raw);
    }


    /**
     * Jackson serialization of {@code Severity}: the canonical enum value when valid, otherwise the
     * raw string verbatim.
     *
     * @return the JSON value for {@code Severity}, or {@code null} when absent
     */
    @JsonGetter("Severity")
    public @Nullable String getSeverityJson()
    {
        return severity != null ? severity.getJsonValue() : rawSeverity;
    }


    /** Jackson binding for {@code Executability}: stores the raw string and the parsed enum. */
    @JsonSetter("Executability")
    public void setExecutabilityJson(@Nullable String raw)
    {
        this.rawExecutability = raw;
        this.executability = Executability.fromJson(raw);
    }


    /**
     * Jackson serialization of {@code Executability}: the canonical enum value when valid,
     * otherwise the raw string verbatim.
     *
     * @return the JSON value for {@code Executability}, or {@code null} when absent
     */
    @JsonGetter("Executability")
    public @Nullable String getExecutabilityJson()
    {
        return executability != null ? executability.getJsonValue() : rawExecutability;
    }


    /** Jackson binding for {@code Variable_Universe}: stores the raw string and the parsed enum. */
    @JsonSetter("Variable_Universe")
    public void setVariableUniverseJson(@Nullable String raw)
    {
        this.rawVariableUniverse = raw;
        this.variableUniverse = VariableUniverse.fromJson(raw);
    }


    /**
     * Serialises the typed value when set, else the raw string (so an invalid value round-trips).
     */
    @JsonGetter("Variable_Universe")
    public @Nullable String getVariableUniverseJson()
    {
        return variableUniverse != null ? variableUniverse.getJsonValue() : rawVariableUniverse;
    }

    /**
     * Project-specific enrichment recording, for a rule that is not directly executable, what the
     * engine must do to run it (wildcard expansion / template generation) or why it cannot run. See
     * {@link ExecutabilityHint}. Absent on directly-executable rules.
     */
    @JsonProperty("ExecutabilityHint")
    private @Nullable ExecutabilityHint executabilityHint;

    @JsonProperty("Authorities")
    private @Nullable List<Authority> authorities;

    @JsonProperty("Scope")
    private @Nullable Scope scope;

    /**
     * What the rule needs in order to answer at all, as opposed to which datasets it is about
     * ({@link Requirements}; {@code plans/PLAN-scope-requirements-split.md}).
     */
    @JsonProperty("Requirements")
    private @Nullable Requirements requirements;

    /**
     * The <b>strictest declared level's</b> condition — and, for every rule that authors a plain
     * {@code Check:}, simply that condition.
     *
     * <p>
     * &#9873;&#9873; <b>The meaning of this field did not change when level-keyed Checks landed
     * (Plan C &#167;3.3).</b> A rule with no level map declares exactly one level, at
     * {@link #effectiveSeverity()}, and this holds its condition; a rule with a level map holds the
     * <em>first</em> (strictest) level's condition here, which the load gate pins to equal the
     * rule's {@code Severity}. That is why the ~30 structural {@code getCheck()} readers stayed
     * correct and untouched: they all want "the rule's Check" in the sense of its strongest
     * statement. A reader that must see <em>every</em> level — a validation walker, the
     * output-variable derivation, the provider-requirement scan — reads
     * {@link #effectiveCheckLevels()} instead.
     * </p>
     *
     * <p>
     * The JSON binding goes through the {@code @JsonSetter}/{@code @JsonGetter} pair below (the
     * same shape {@code Sensitivity} and {@code Severity} use), because the {@code Check:} key can
     * bind to either this field or {@link #checkLevels} and a Jackson field binding cannot
     * dispatch.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable CheckCondition check;

    /**
     * The rule's level-keyed {@code Check} (Plan C &#167;3.3), ordered <b>strictest first</b>
     * ({@code REJECT} &rarr; {@code ERROR} &rarr; {@code WARNING} &rarr; {@code INFO}), or
     * {@code null} when the rule authored a plain {@code Check:} — which is every shipped rule.
     *
     * <p>
     * &#9873; <b>Read {@link #effectiveCheckLevels()}, not this field.</b> {@code null} here does
     * not mean "no levels": it means "one level, at {@link #effectiveSeverity()}, whose condition
     * is {@link #check}", and the accessor states that once so no caller has to.
     * </p>
     *
     * <p>
     * The invariant {@code checkLevels.firstEntry().getValue().condition() == check} is maintained
     * by {@link #setCheck} and {@link #setCheckLevels}; the load gate additionally pins
     * {@code checkLevels.firstKey() == effectiveSeverity()}.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable SequencedMap<Severity, LevelCheck> checkLevels;

    /**
     * A &#167;3.3 grammar violation in the authored {@code Check:} — a mixed level map, an unknown
     * level name — kept verbatim for load-time validation, exactly as {@link #rawSeverity} is.
     *
     * <p>
     * A Jackson deserialiser cannot name the offending rule, so the violation rides here until
     * {@code RulePackageLoader.validateEnumFields} turns it into a per-rule {@link #loadError} that
     * does. When it is non-null, {@link #check} is {@code null}: nothing binds, so nothing
     * evaluates.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String rawCheckLevels;

    @JsonProperty("Outcome")
    private @Nullable Outcome outcome;

    @JsonProperty("Operations")
    private @Nullable List<Operation> operations;

    @JsonProperty("Match_Datasets")
    private @Nullable List<MatchDataset> matchDatasets;

    /**
     * The <b>flat, legacy</b> rule-level grouping key. Still accepted, and still the only shape the
     * Python-facing {@code rules-legacy/} view carries. Prefer {@link #grouping} for new rules and
     * read the resolved value through {@link #effectiveGroupingVariables()}, never this field
     * directly.
     */
    @JsonProperty("Grouping_Variables")
    private @Nullable List<String> groupingVariables;

    /**
     * The rule-level {@code Grouping:} block — the grouping key plus its {@code keep_missings}
     * disposition. Mutually exclusive with the flat {@link #groupingVariables} (declaring both is a
     * load error); {@link #effectiveGroupingVariables()} resolves whichever is present.
     */
    @JsonProperty("Grouping")
    private @Nullable GroupingSpec grouping;

    /**
     * Fix #13: study-level precondition. When non-null, the engine evaluates this Check tree
     * against the dataset-level context before the main Check. If the precondition is false, the
     * rule is skipped (returns {@link net.cumba.corej.core.exec.RuleExecutionStatus#SKIPPED}) and
     * the main Check is never evaluated.
     * <p>
     * Engine extension beyond the upstream rule format — specified in
     * {@code the CORE rules specification}. When absent (all currently-shipped rules), behaviour is
     * unchanged.
     * </p>
     */
    @JsonProperty("Precondition")
    private @Nullable CheckCondition precondition;

    /**
     * The rule's {@code Expansion:} block — declared-token template expansion (see
     * {@link ExpansionDirective}). When non-empty the rule is a <b>template</b>: it never executes
     * itself; {@code net.cumba.corej.core.gen.WildcardExpander#tryExpand} replaces it with one
     * concrete rule per token binding, or skips it with a stated reason when the binding source
     * cannot be read.
     * <p>
     * Distinct from the engine-owned {@code xx}/{@code y}/{@code zz}/{@code w} markers, which need
     * no declaration; the two mechanisms are mutually exclusive on one rule (enforced at load).
     * </p>
     * <p>
     * Engine extension beyond the upstream rule format; specified in
     * {@code the CORE rules specification}, Engine Fields &#167; {@code Expansion}.
     * </p>
     */
    @JsonProperty("Expansion")
    private @Nullable List<ExpansionDirective> expansion;

    /**
     * When {@code true}, expanded rules are skipped for variables that have CDISC Library
     * definitions (since Library-driven checks are more precise).
     */
    @JsonProperty("skipIfLibraryDefined")
    private @Nullable Boolean skipIfLibraryDefined;

    /**
     * Fix #24: numeric range filters applied to wildcard capture groups during expansion. Map key
     * is the capture-group token ({@code xx}, {@code zz}, {@code y}, {@code w}); value is a
     * {@link WildcardFilter} carrying inclusive {@code min} / {@code max} bounds.
     * <p>
     * The {@code WildcardExpander} drops candidate tuples whose captured group values fall outside
     * any configured filter. Group keys that don't appear in any leaf wildcard raise a load-time
     * error via {@link #loadError}.
     * </p>
     * <p>
     * Engine extension beyond the upstream rule format; specified in
     * {@code the CORE rules specification}, Engine Fields &#167; {@code wildcards}. Used by
     * CDISC-AD0078 / CDISC-AD0079 ({@code "xx > 01"}).
     * </p>
     */
    @JsonProperty("wildcards")
    private @Nullable Map<String, WildcardFilter> wildcards;

    /**
     * Fix #84 (Group B / B4): root-name exclusion list for empty-suffix wildcard pairing. Each
     * entry is a variable-name pattern (literal such as {@code TRTPN}, or wildcard such as
     * {@code *DTC} / {@code *FN}) that must NOT be treated as a pairing secondary during expansion
     * of a bare- {@code *} / {@code *N} / {@code *C} template. Applied by {@code WildcardExpander}
     * against the anchored ({@code *N} / {@code *C}) secondary columns: any secondary column
     * matching an exclusion is dropped, so its pair never seeds a tuple. Populated from the PMDA
     * ADaM sheet's verbatim "Exceptions:" lists (e.g. AD0376 / AD1011).
     * <p>
     * Engine extension beyond the upstream rule format; specified in
     * {@code the CORE rules specification}, Engine Fields &#167; {@code wildcardExclude}.
     * </p>
     */
    @JsonProperty("wildcardExclude")
    private @Nullable List<String> wildcardExclude;

    /**
     * Fix #84 (Group B / B4): when {@code true}, an empty-suffix wildcard pairing template (bare
     * {@code *} primary co-anchored by a {@code *N} / {@code *C} secondary) only emits expansions
     * for secondary columns that appear in the curated CDISC-standard pair catalogue
     * ({@link net.cumba.corej.core.gen.WildcardPairCatalogue}). This implements the AD1012A
     * requirement to look "explicitly at variable pairs defined in the CDISC standard documents".
     * <p>
     * Engine extension beyond the upstream rule format; specified in
     * {@code the CORE rules specification}, Engine Fields &#167; {@code wildcardPairCatalogue}.
     * </p>
     */
    @JsonProperty("wildcardPairCatalogue")
    private @Nullable Boolean wildcardPairCatalogue;

    /**
     * Every JSON key that bound to no modelled property, in encounter order.
     *
     * <p>
     * The loader's mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so an unknown key
     * would otherwise vanish without trace. That is still the behaviour for genuinely unknown keys
     * — this set only <em>records</em> them, it does not reject anything. What rejects is
     * {@code RulePackageLoader.validateRetiredUnderscoreKeys}, which turns a <em>retired</em>
     * spelling (the {@code _}-prefixed field names this engine used before
     * {@code PLAN-underscore-field-retirement.md}) into a per-rule {@link #loadError} naming the
     * replacement — the alternative being a stale {@code _wildcards:} silently dropped and the rule
     * expanding unfiltered.
     * </p>
     *
     * <p>
     * Populated only at parse time, never serialised, and excluded from {@code equals} /
     * {@code hashCode} / {@code toString} so two rules that differ only in discarded keys still
     * compare equal (round-trip and fixture-comparison tests rely on that).
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private final SequencedSet<String> unknownKeys = new LinkedHashSet<>();

    /**
     * Jackson's catch-all for unbound JSON keys; records the key name and drops the value.
     *
     * @param name
     *            the unbound JSON key
     * @param value
     *            its value — deliberately unread; only the key's presence is diagnostic
     */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    void recordUnknownKey(String name, @Nullable Object value)
    {
        unknownKeys.add(name);
    }


    /**
     * The JSON keys of this rule that bound to no modelled property, in encounter order.
     *
     * @return an unmodifiable view of the collected unknown keys (empty when the rule was not
     *         parsed from JSON, or carried none)
     */
    public SequencedSet<String> getUnknownKeys()
    {
        return java.util.Collections.unmodifiableSequencedSet(unknownKeys);
    }

    /**
     * Fix #37: transient flag set by {@link net.cumba.corej.core.RulePackageLoader} when the rule's
     * Check tree contains malformed operand-substitution syntax (Fix #37 {@code ${VAR[:fmt]}} /
     * {@code ${*}}) or an off-diagonal operator combination.
     *
     * <p>
     * Populated only at runtime by the loader; never serialised. When non-null,
     * {@link net.cumba.corej.core.exec.RuleRunner#execute} returns a single sentinel
     * {@link net.cumba.corej.core.exec.Violation} carrying the message instead of evaluating the
     * rule, so invalid rules surface loudly without blocking the rest of the package.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String loadError;

    /**
     * A non-fatal load finding ({@code PLAN-derive-rule-type-sensitivity} open question 2, resolved
     * 2026-07-30): the frame-compatibility gate reports here instead of failing the load. The rule
     * still executes — Java evaluates cross-frame Checks correctly (its frame is always the data
     * table with metadata accessors); the hazard the gate describes only materialises if the same
     * rule is fed to the Python reference engine. A hard {@link #loadError} would reject
     * externally-supplied ({@code --rules-dir}) rules that run fine here, so the gate warns; the
     * shipped corpus is held to zero warnings by a committed corpus test instead.
     *
     * <p>
     * Populated only at runtime by the loader; never serialised. Also logged at {@code WARNING}.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String loadWarning;

    /**
     * Loader gate <b>R7</b>'s channel: a {@code Match_Datasets[].Name} this rule joins against but
     * does not declare in {@code Requirements.Datasets}
     * ({@code plans/PLAN-scope-requirements-split.md} &#167;4.4, owner ruling Q5). Advisory only —
     * a missing secondary stays a DEBUG no-op at runtime and no promotion lane is scheduled.
     *
     * <p>
     * ⚠⚠ <b>Deliberately not {@link #loadWarning}.</b> R7 is loud by design — 251 authored rules
     * carry {@code Match_Datasets} and none declares {@code Requirements.Datasets} yet — while
     * {@code CrossCorpusDerivationTest} holds the shipped corpus to <em>zero</em>
     * {@code loadWarning}s. Routing R7 there would have forced that assertion to be weakened to
     * accommodate a warning it was built to catch; the plan's own instruction is the opposite ("R7
     * needs its own warning channel rather than a weakened assertion"). So the gap gets a channel
     * of its own: visible, countable, and unable to drown the channel that means "something is
     * wrong with this rule".
     * </p>
     *
     * <p>
     * Populated only at runtime by the loader; never serialised. Logged at {@code DEBUG} — at
     * {@code WARNING} it would emit hundreds of lines on every corpus load.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String requirementsGapWarning;

    /**
     * The availability-gate terms {@link net.cumba.corej.core.RulePackageLoader} injected into this
     * rule's {@code Precondition} at load, or {@code null} when none were needed
     * (PLAN-classifier-redesign Phase-0 filed hazard): a hand-authored native rule that inlines a
     * library/define/dictionary-dependent operation call <em>without</em> the
     * {@code library_available() and available(<op>)} gate would silently PASS where the legacy
     * contract demands SKIPPED, because the ungated call broadcasts {@code null} and no row fires.
     * The loader restores the contract by injecting the same gate shape {@code OperationInliner}
     * bakes into the shipped corpus — which therefore never needs the injection (held to zero by a
     * committed corpus test).
     *
     * <p>
     * Populated only at runtime by the loader; never serialised. Also logged at {@code INFO}.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable String injectedPreconditionGates;

    /**
     * Why {@link #sensitivity} holds a <em>derived</em> value rather than an authored one
     * ({@code PLAN-derive-rule-type-sensitivity} phase 6), keyed by field name.
     *
     * <p>
     * Populated only at runtime by {@link net.cumba.corej.core.RulePackageLoader} when the source
     * omitted the field; never serialised. Reports and the rule editor read it to label the value
     * "derived" and explain the basis, so an author can see what the engine concluded instead of
     * having to guess why a rule routed the way it did. Absent from the map ⇒ the value was
     * authored explicitly and wins over any derivation.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Map<String, String> derivationRationale;

    /**
     * The effective Output_Variables — the authored {@code Outcome.Output_Variables} plus every
     * entry {@link net.cumba.corej.core.exec.OutputVariableDeriver} could derive from the Check,
     * the Operations and the rule type (EC-37, {@code PLAN-auto-output-variables}). Populated only
     * at runtime by {@code RulePackageLoader#deriveOutputVariables}; never serialised, so
     * {@code /api/rules/full}, the XLSX export and every offline tool keep showing exactly what the
     * author wrote. {@code null} when the derivation is disabled
     * ({@code -Dcorej.autoOutputVariables=false}), so consumers fall back to the authored list via
     * {@link #effectiveOutputVariablesOrAuthored()}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable List<String> effectiveOutputVariables;

    /**
     * The names the authored {@code Outcome.Output_Variables} excludes with {@code !X} entries (E-2
     * of {@code PLAN-authoring-grammar-unique-set-and-output-exclusion}), computed once by
     * {@code OutputVariableDeriver#excludedOf} and installed next to
     * {@link #effectiveOutputVariables}. Runtime-only, never serialised; {@code null} when the
     * derivation did not run — read through {@link #excludedOutputVariablesOrAuthored()}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Set<String> excludedOutputVariables;

    /**
     * Native-evaluator backing expression, reconstructed by
     * {@link net.cumba.corej.core.RulePackageLoader} from the rule's {@link #check} via
     * {@code CheckToExpr} when (and only when) the whole Check is fully-expression and the rule is
     * a Record-Data rule. Populated only at runtime; never serialised. When non-null and the
     * {@code nativeEval} flag is on, {@link net.cumba.corej.core.exec.RuleRunner} evaluates this
     * {@code Expr} directly via {@code NativeExprEvaluator} at the Record-Data row-level site
     * instead of running the lowered {@link #check} through the legacy engine. {@code null} keeps
     * the rule entirely on the legacy path.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private net.cumba.corej.core.expr.ast.@Nullable Expr checkExpr;

    /**
     * Whether {@link #checkExpr} is a fold-equivalent dataset-broadcast verdict (P3a of
     * {@code plans/done/PLAN-native-engine-full-coverage.md}): exists/not_exists facts and
     * {@code $}-operation comparisons only — the exact class the legacy
     * {@code partialEvaluateDataset} folds to a constant (one dataset-level violation at row 0).
     * Set by the loader alongside {@code checkExpr}; when {@code true} (and native is on)
     * {@code RuleRunner.executeUnified} evaluates the expression ONCE via
     * {@code NativeExprEvaluator.evaluateBroadcast} instead of the legacy fold. Runtime-only; never
     * serialised.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean broadcastCheckExpr;

    /**
     * The native form of <b>every</b> declared check level, keyed by level (Plan C &#167;3.3 step
     * 2) — {@code null} for a single-level rule, whose one compiled form is {@link #checkExpr}.
     *
     * <p>
     * The strictest level's entry is the very same {@code Expr} instance as {@link #checkExpr}, so
     * the single-level execution path is unchanged and a reference comparison against
     * {@code checkExpr} still identifies "the rule's own, unsuppressed expression".
     * </p>
     *
     * <p>
     * Populated only at runtime by {@code RulePackageLoader#installNativeExpr}; never serialised.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Map<Severity, net.cumba.corej.core.expr.ast.Expr> checkLevelExprs;

    /**
     * Which of {@link #checkLevelExprs}' levels are fold-equivalent dataset-broadcast verdicts —
     * the per-level companion of {@link #broadcastCheckExpr}, which stays the strictest level's
     * flag. {@code null} for a single-level rule. Runtime-only; never serialised.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private @Nullable Set<Severity> broadcastCheckLevels;

    /**
     * The rule's <b>evaluation domain</b> — the join of the cursor demands of {@link #checkExpr}'s
     * leaves ({@code PLAN-leaf-scope-domain-inference.md} §3.2), computed by {@code DomainScan} at
     * {@code installNativeExpr} time alongside {@code checkExpr}. A memoised result of the
     * inference, never an input to it and never serialised: {@code RuleRunner} dispatches on it
     * ({@code {}} broadcast, {@code {VAR}} per variable, {@code {ROW}} per row, {@code {VAR,ROW}}
     * per variable × row) and {@code ValidationReportBuilder} projects the {@code FindingScope}
     * from it. {@code null} exactly when {@link #checkExpr} is {@code null}.
     *
     * <p>
     * &#9873; On a multi-level rule this is the <b>join</b> of the levels' domains (Plan C
     * &#167;3.3 step 2), not the strictest level's own: the finding unit a level claims must be the
     * same shape at every level, or first-claim would be comparing rows against cells. For the
     * single-level corpus the join is the one level's domain, i.e. exactly today's value.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private net.cumba.corej.core.expr.eval.@Nullable Domain evaluationDomain;

    /**
     * Native form of {@link #precondition} (P6b of
     * {@code plans/done/PLAN-native-engine-full-coverage.md}), raised by the loader when the
     * precondition is a fold-equivalent broadcast verdict (exists/not_exists facts and
     * {@code $}-operation comparisons — the exact class the legacy {@code partialEvaluateDataset}
     * fold can decide). When non-null and native is on, {@code RuleRunner} makes the skip-on-false
     * decision via {@code NativeExprEvaluator.evaluateBroadcast}; {@code null} mirrors the legacy
     * "not fully resolvable ⇒ continue" behaviour. Runtime-only; never serialised.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private net.cumba.corej.core.expr.ast.@Nullable Expr preconditionExpr;

    /**
     * The stable identity of this rule for cross-dataset grouping, audit sets and report keys: the
     * {@code Core.Id} when present, else the JSON {@code id}.
     *
     * <p>
     * File-loaded rules carry <em>no</em> {@code id} — the rule-package map key <em>is</em> the
     * {@code Core.Id} — while generated ({@link net.cumba.corej.core.gen.RuleGenerator}) and
     * CDISC-Library-sourced rules carry a synthetic {@code id} and may carry no {@code Core}.
     * Neither field alone identifies every rule, so callers that need "which rule is this, across
     * datasets" must use this method and never {@code getId()} directly.
     * </p>
     *
     * @return the rule's stable identity, or {@code null} when it carries neither a {@code Core.Id}
     *         nor an {@code id}
     */
    public @Nullable String effectiveId()
    {
        RuleCore c = getCore();
        String coreId = c != null ? c.getId() : null;
        return coreId != null ? coreId : getId();
    }

}
