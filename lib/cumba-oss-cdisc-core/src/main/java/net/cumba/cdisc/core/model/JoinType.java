package net.cumba.cdisc.core.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The join types a {@code Match_Datasets} entry may declare — the closed vocabulary behind
 * {@link MatchDataset#getJoinType()} ({@code Fix #236}).
 *
 * <h2>Why an enum exists but the field stays a {@code String}</h2> {@code Join_Type} is authored as
 * free text and, until {@code Fix #236}, <b>nothing validated it</b>: the engine's only value
 * comparison is a <em>negation</em> — {@code !"inner".equalsIgnoreCase(getJoinType())} in
 * {@code KeyMatchRowExpander} — so every unrecognised value (a typo, a case error, a value borrowed
 * from another vocabulary) was silently executed as a <b>left</b> join. {@code Join_Type: "iner"}
 * became {@code left} and reported nothing.
 *
 * <p>
 * This enum is the vocabulary; {@code RulePackageLoader.validateEnumFields} is the gate that makes
 * a value outside it a {@code loadError}. The model field itself is deliberately <b>not</b>
 * re-typed to this enum: {@code null} is a load-bearing state (see below) and the raw authored text
 * has to survive into the error message.
 * </p>
 *
 * <h2>⚠⚠ {@code null} is legal everywhere and must never be rejected</h2> {@code null} means
 * <i>"not authored"</i>, which is the normal state of a generated rule:
 * {@code RulePackageLoader.normalizeJoinTypes} stamps {@code inner} onto a null/blank value at
 * load, but {@code RuleGenerator} <b>never calls it</b>, so the whole
 * {@code CDISC-AD0591-<domain>-<var>} family keeps a null {@code Join_Type} — and that null is
 * exactly what keeps {@code RuleCohortGrouper}'s equality-cohort path reachable ({@code Fix #233} /
 * EC-74). Validation therefore judges <b>the string when present</b>, never its absence.
 *
 * <h2>Case sensitivity mirrors the engine, and trimming deliberately does not</h2>
 * {@link #fromJson} compares with {@code equalsIgnoreCase} and does <b>no trimming</b>, so it
 * accepts exactly the set of strings the engine already interprets as that join type.
 * {@code " inner "} is <em>not</em> accepted, because {@code KeyMatchRowExpander} would run it as
 * {@code left} — treating it as valid would re-create the silent divergence this enum exists to
 * close. A blank value is absence, not a value, and is normalised to {@code inner} by the loader.
 */
@RequiredArgsConstructor
@Getter
public enum JoinType
{

    /**
     * Drop a primary row that has no matching child row. The <b>effective corpus default</b>:
     * {@code RulePackageLoader.normalizeJoinTypes} stamps it onto every entry that omits
     * {@code Join_Type}.
     */
    INNER("inner"),

    /**
     * Keep a primary row that has no matching child row, binding it to a {@code null} child so
     * dotted references resolve to {@code null} and absence/empty checks fire. The only value the
     * shipped corpus authors — 85 {@code Match_Datasets} entries across 18 distinct rules
     * (2026-08-17; was 158 across 38 before {@code D-TA-2} removed the over-firing ones) — and the
     * defensive fallback {@code KeyMatchRowExpander} applies to a loader-bypassing rule.
     */
    LEFT("left");

    /** The value as authored in {@code Join_Type}. */
    private final String jsonValue;

    /**
     * The join type this authored string denotes, or {@code null} when it denotes none.
     *
     * <p>
     * ⚠ A {@code null} return is <b>ambiguous by design</b> and callers must not read it as
     * "invalid": {@code null} in, {@code null} out. Absence is tested separately — see
     * {@link #isAbsent(String)} — because absent is legal and unrecognised is not.
     * </p>
     *
     * @param value
     *            the raw authored value, may be {@code null}
     * @return the matching constant, or {@code null} for {@code null}, blank or unrecognised input
     */
    public static @Nullable JoinType fromJson(@Nullable String value)
    {
        if (value == null)
        {
            return null;
        }
        for (JoinType candidate : values())
        {
            // No trim: see the class javadoc — the accepted set must equal the set the engine
            // already interprets, or validation would bless a value that executes as something
            // else.
            if (candidate.jsonValue.equalsIgnoreCase(value))
            {
                return candidate;
            }
        }
        return null;
    }


    /**
     * Whether this authored value declares no join type at all — {@code null} or blank, the two
     * states {@code RulePackageLoader.normalizeJoinTypes} replaces with {@link #INNER}.
     *
     * @param value
     *            the raw authored value, may be {@code null}
     * @return {@code true} when nothing was authored
     */
    public static boolean isAbsent(@Nullable String value)
    {
        return value == null || value.isBlank();
    }

}
