package net.cumba.corej.core.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.SequencedMap;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The bound form of a rule's {@code Check:} key — <b>at most one</b> of the three states of Plan C
 * &#167;3.3's grammar is populated: a well-formed binding carries either the plain condition or the
 * level map (never both), a grammar violation carries only {@link #grammarError}, and an absent or
 * explicitly-{@code null} {@code Check:} leaves all three components {@code null}.
 *
 * <p>
 * A {@code Check:} whose keys are <em>all</em> ladder level names is a <b>level map</b>; a
 * {@code Check:} with <em>no</em> level-name key is a plain {@link CheckCondition}, exactly as
 * before this plan; a <b>mixed</b> map ({@code {ERROR: …, expression: …}}) and an <b>unknown</b>
 * level name are load errors.
 * </p>
 *
 * <p>
 * &#9873; <b>Why a grammar violation is carried rather than thrown.</b> A Jackson deserialiser
 * cannot name the offending rule — the rule id lives one level up, in the map key of
 * {@code RulePackage.rules} — so throwing here aborts the whole package load with a message that
 * names nothing. This mirrors the {@code rawSeverity} precedent exactly: keep the offending form,
 * let {@code RulePackageLoader.validateEnumFields} turn it into a per-rule {@code loadError} that
 * names the rule, and let every other rule in the package keep loading.
 * </p>
 *
 * @param single
 *            the plain condition when the {@code Check:} declared no level, else {@code null}
 * @param levels
 *            the level map, ordered strictest-first, when the {@code Check:} declared levels, else
 *            {@code null}
 * @param grammarError
 *            the &#167;3.3 grammar violation, or {@code null} when the {@code Check:} is well
 *            formed. Non-null implies both other components are {@code null}.
 */
@JsonDeserialize(using = RuleCheckDeserializer.class)
public record RuleCheck(@Nullable CheckCondition single,
        @Nullable SequencedMap<Severity, LevelCheck> levels, @Nullable String grammarError)
{

    /**
     * Defensive copy of {@link #levels} — the deserialiser builds it mutably, and this record is
     * then read on the execution path. Copying once here keeps the accessor allocation-free.
     */
    public RuleCheck
    {
        levels = levels == null ? null
                : java.util.Collections
                        .unmodifiableSequencedMap(new java.util.LinkedHashMap<>(levels));
    }


    /** A well-formed plain {@code Check:} — one level, at the rule's {@code Severity}. */
    static RuleCheck plain(@Nullable CheckCondition condition)
    {
        return new RuleCheck(condition, null, null);
    }


    /** A well-formed level-keyed {@code Check:}. */
    static RuleCheck levelled(SequencedMap<Severity, LevelCheck> levels)
    {
        return new RuleCheck(null, levels, null);
    }


    /** A {@code Check:} that violates &#167;3.3's grammar; {@code message} names how. */
    static RuleCheck invalid(String message)
    {
        return new RuleCheck(null, null, message);
    }

}
