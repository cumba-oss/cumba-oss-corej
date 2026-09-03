package net.cumba.corej.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.UnaryOperator;

import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * One level of a level-keyed {@code Check} (Plan C &#167;3.3): the condition that level evaluates,
 * plus the optional {@code Message} that level reports.
 *
 * <p>
 * A rule's {@code Check:} is either a plain {@link CheckCondition} — one level, at the rule's
 * {@link Rule#effectiveSeverity()} — or a map from ladder level to this record. The map is ordered
 * <b>strictest first</b> ({@code REJECT} &rarr; {@code ERROR} &rarr; {@code WARNING} &rarr;
 * {@code INFO}); {@link Severity#NOTICE} is never authorable. Read a rule's levels through
 * {@link Rule#effectiveCheckLevels()}, which is never {@code null} and synthesises the single-level
 * map for the <b>3 795</b> of 3 804 authored rules that carry a plain {@code Check:}. (Re-measured
 * 2026-08-26: this read "~3 804", which was true only until Plan C phase 5b authored the first
 * <b>9</b> level maps.)
 * </p>
 *
 * <p>
 * &#9873; <b>{@code message} is never defaulted here.</b> A level that declares no {@code Message}
 * keeps {@code null} and the reader falls back to the rule's {@code Outcome.Message} at report time
 * (&#167;3.6, ruling 6) — copying the rule message into every level at load would put the same
 * string in the {@code rules/} package N times and make {@code Outcome.Message} unchangeable
 * without editing every level.
 * </p>
 *
 * @param condition
 *            the level's Check condition; never {@code null}
 * @param message
 *            the level's own {@code Message}, or {@code null} to fall back to
 *            {@code Outcome.Message}
 */
public record LevelCheck(CheckCondition condition, @Nullable String message)
{

    /**
     * A level map holding {@code condition} at {@code level} and nothing else — the shape
     * {@link Rule#effectiveCheckLevels()} synthesises for a rule that authored a plain
     * {@code Check:}.
     *
     * @param level
     *            the single declared level
     * @param condition
     *            that level's condition
     * @return a one-entry level map
     */
    public static SequencedMap<Severity, LevelCheck> single(Severity level,
            CheckCondition condition)
    {
        SequencedMap<Severity, LevelCheck> out = new LinkedHashMap<>(2);
        out.put(level, new LevelCheck(condition, null));
        return out;
    }


    /**
     * Re-orders {@code levels} strictest-first, i.e. into {@link Severity}'s own declaration order
     * ({@code REJECT} &gt; {@code ERROR} &gt; {@code WARNING} &gt; {@code INFO}), so evaluation
     * order is the ladder and never the authored file order (&#167;3.4 step 3).
     *
     * @param levels
     *            the parsed levels in file order
     * @return the same entries, strictest first
     */
    public static SequencedMap<Severity, LevelCheck> byLadder(Map<Severity, LevelCheck> levels)
    {
        SequencedMap<Severity, LevelCheck> out = new LinkedHashMap<>();
        for (Severity level : Severity.values())
        {
            LevelCheck entry = levels.get(level);
            if (entry != null)
            {
                out.put(level, entry);
            }
        }
        return out;
    }


    /**
     * Applies {@code fn} to every level's condition, preserving the ladder order and each level's
     * {@code Message}.
     *
     * <p>
     * &#9888;&#9888; This is what the three {@code new Rule()} <b>clone sites</b>
     * ({@code gen/RuleGenerator}, {@code gen/WildcardExpander}, {@code gen/TokenExpander}) call:
     * each of them rebuilds a rule field by field and rewrites the Check, so a level map that is
     * not rewritten alongside it would ship the template's unresolved names — and the drop is
     * invisible to the loader, both schemas and the writer, because the template still carries the
     * field. That exact omission cost Plan C phase 3 944 finding rows on {@code Severity}.
     * </p>
     *
     * @param levels
     *            the source level map, may be {@code null}
     * @param fn
     *            the condition rewrite
     * @return the rewritten map, or {@code null} when {@code levels} was {@code null}
     */
    public static @Nullable SequencedMap<Severity, LevelCheck> mapConditions(
            @Nullable SequencedMap<Severity, LevelCheck> levels, UnaryOperator<CheckCondition> fn)
    {
        if (levels == null)
        {
            return null;
        }
        SequencedMap<Severity, LevelCheck> out = new LinkedHashMap<>();
        for (Map.Entry<Severity, LevelCheck> e : levels.entrySet())
        {
            out.put(e.getKey(),
                    new LevelCheck(fn.apply(e.getValue().condition()), e.getValue().message()));
        }
        return out;
    }

}
