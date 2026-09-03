package net.cumba.corej.core.exec;

import java.util.Objects;

import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;

/**
 * The grouping-key policy: what a <b>missing value in a grouping key column that exists</b> means.
 *
 * <p>
 * Before this type the engine answered that question in eight independent places, three of which
 * kept the row under a {@code ""} key while five discarded the whole group, and it implemented "is
 * this key component missing?" <b>six</b> separate times — twice inside a single method
 * ({@link GroupSemantics} {@code componentKeyValue}'s singleton and coalesce branches). Every
 * grouping path now routes its decision through one object and one predicate,
 * {@link #isBlankKeyComponent(IDataValue)}.
 * </p>
 *
 * <p>
 * <b>Two orthogonal axes.</b> They are deliberately separate because conflating them is exactly
 * what produced the divergence:
 * </p>
 * <ol>
 * <li>{@link #keepMissings()} — <b>the authored axis</b>. Does a blank key component keep its row
 * in a group (under the blank's own {@link KeyPart} identity), or drop the row/group entirely? This
 * is the axis the rule declares as {@code keep_missings}. A missing value is a valid value of a
 * variable, so the direction of travel is {@code true}; the per-site defaults preserve today's
 * behaviour until the default is flipped.</li>
 * <li>{@link #blankness()} — <b>an internal, per-site descriptor and NOT authorable</b>. Which
 * cells count as blank at all. ⭐ <b>Two</b> notions exist since {@code W32-E3} retired
 * {@code MISSING_ONLY} (2026-08-12); each is load-bearing for at least one operator, so they are
 * named here rather than silently collapsed.</li>
 * </ol>
 *
 * <p>
 * ⚠ Do not promote {@link Blankness} to an authoring parameter. {@code keep_missings} is a boolean
 * on purpose (an enum would imply a third disposition exists); {@code Blankness} describes what the
 * <em>operator family</em> already means by "blank" and is fixed per call site.
 * </p>
 *
 * <p>
 * ⚠⚠ {@code IndexHelper.buildGroupKey} and {@code IndexHelper.isBlockKeyMissing} both consult this
 * policy but remain <b>two functions</b>, and must stay that way. {@code buildGroupKey} builds the
 * <em>reporting key</em> of a group that has already been formed; {@code isBlockKeyMissing} decides
 * whether the group is formed at all. They answer different questions and merely happen to read the
 * same cells. Unifying them would be a bug, not a tidy-up.
 * </p>
 *
 * @param keepMissings
 *            {@code true} to keep a row whose key component is blank (the blank is a real key
 *            value)
 * @param blankness
 *            which cells count as blank for this call site
 */
public record GroupKeyPolicy(boolean keepMissings, Blankness blankness)
{

    /**
     * Which cells count as a blank key component. Fixed per call site by the operator family — see
     * the class comment's warning against making this authorable.
     */
    public enum Blankness
    {

        /**
         * A genuine missing marker <b>or</b> {@code ""} is blank ({@code ScalarSemantics.isMissing}
         * — character variables cannot be null in SAS, so a blank cell arrives as {@code ""}).
         *
         * <p>
         * ⭐ <b>Since {@code W32-E3} (owner ruling 2026-08-12) this is the notion for <em>every</em>
         * grouping key.</b> The former {@code MISSING_ONLY} — <i>"only a genuine marker is blank;
         * {@code ""} is a real key value"</i> — is <b>retired</b>: it made one
         * {@code keep_missings} declaration mean different things depending on how a blank happened
         * to be stored, which the author cannot see. Owner: <i>"a {@code MissingValue} is a valid
         * value to be handled — this is why we introduced {@code keep_missings}. It was not meant
         * to differentiate between blank char variables and missing numerics."</i>
         * </p>
         *
         * <p>
         * ⚑ This also aligns the grouping path with the standing <b>{@code missing ≡ empty}</b>
         * policy of 2026-08-04, which it had contradicted ever since.
         * </p>
         */
        MISSING_OR_EMPTY,

        /**
         * As {@link #MISSING_OR_EMPTY}, and a whitespace-only value is blank too. The EC-24
         * coalesce-component notion: a pooled record carries a blank {@code USUBJID} and identifies
         * by {@code POOLID}, so {@code within: [[USUBJID, POOLID]]} must fall through a blank —
         * even a space-filled — {@code USUBJID}.
         */
        MISSING_OR_WHITESPACE
    }


    /**
     * One <b>grouping-key component</b> as a typed identity — the {@code W38-A1} composite key
     * (owner, 2026-08-13: <i>"lets go with the composite key, it can't collide by
     * construction"</i>; Fix #249 / EC-75).
     *
     * <p>
     * Equality of {@code KeyPart}s <em>is</em> the grouping-identity relation the owner ruled:
     * </p>
     * <ol>
     * <li>for <em>filtering</em> ({@code keep_missings}), {@code ""} and a {@link MissingValue} are
     * both blank — the {@link GroupKeyPolicy#isBlankKeyComponent} bucket is unchanged;</li>
     * <li>wherever blanks are <em>kept</em>, they form <b>separate</b> groups — {@link Empty}
     * {@code ≠} {@link Missing};</li>
     * <li>different {@code MissingValue}s do not fold together — {@code Missing(MIS) ≠
     * Missing(MIS_UNKNOWN)};</li>
     * <li>a {@code MissingValue} is <b>never</b> equal to any String value — in particular not to a
     * literal {@code "."} ({@code Missing(MIS) ≠ Present(".")}), which a
     * {@code getValueAsString()}-based sentinel encoding would have got wrong.</li>
     * </ol>
     *
     * <p>
     * ⭐ <b>Why a sealed type and not a sentinel string.</b> A sentinel is <em>unlikely</em> to
     * collide with a real value; a record <b>cannot</b> — for every possible input. Part 4 says
     * <i>"never"</i>, and only the composite delivers "never".
     * </p>
     *
     * <p>
     * ⭐⭐ <b>And it deletes the emptiness-inference bug class.</b> The D.2 / D.13 emptiness
     * exceptions used to infer blankness by re-reading the <em>rendered</em> key
     * ({@code strip().isEmpty()} on the folded string) — which is exactly why a naive
     * distinct-as-strings encoding silently turned every missing into a participating value
     * (measured: +9 528 findings, 8 rules). With {@code KeyPart} the question is a <b>type
     * test</b>: {@code part instanceof Present}. Blankness can no longer be mis-read from how a
     * value happens to render.
     * </p>
     *
     * <p>
     * ⚠⚠ {@link #reportingForm()} is <b>presentation only</b>, derived from the {@code KeyPart} —
     * it must <b>never</b> be re-parsed to recover identity. Identity lives in the type.
     * </p>
     */
    public sealed interface KeyPart
    {

        /** A real value — <b>including</b> {@code "."} and whitespace-only strings. */
        record Present(String value) implements KeyPart
        {

            public Present
            {
                // "" is Empty's identity, never Present's — two spellings of one identity would
                // re-open the collision class this type exists to close. Loud beats silent.
                if (value.isEmpty())
                {
                    throw new IllegalArgumentException(
                            "an empty string is KeyPart.EMPTY, not a Present value");
                }
            }


            @Override
            public String reportingForm()
            {
                return value;
            }
        }


        /**
         * The literal {@code ""} (and, under a whitespace-aware notion, a whitespace-only cell).
         */
        record Empty() implements KeyPart
        {

            @Override
            public String reportingForm()
            {
                return "";
            }
        }


        /** A genuine missing marker — {@code MIS} / {@code MIS_UNKNOWN} / {@code MIS_ERROR}. */
        record Missing(MissingValue marker) implements KeyPart
        {

            @Override
            public String reportingForm()
            {
                return "\u0001" + marker.name();
            }
        }

        // ⚠ These constants instantiate KeyPart's own subclasses, so KeyPart must declare NO
        // default methods: with one, a subclass's initialization would trigger the interface's
        // (JLS 12.4.1) while the interface's initializer needs the subclass — the Error Prone
        // ClassInitializationDeadlock cycle. reportingForm() is therefore implemented per record.

        /** Interned {@link Empty} — only {@link Present} allocates on the keying path (§6.4). */
        KeyPart EMPTY = new Empty();

        /** Interned {@link Missing Missing(MIS)}. */
        KeyPart MISSING_MIS = new Missing(MissingValue.MIS);

        /** Interned {@link Missing Missing(MIS_UNKNOWN)}. */
        KeyPart MISSING_UNKNOWN = new Missing(MissingValue.MIS_UNKNOWN);

        /** Interned {@link Missing Missing(MIS_ERROR)}. */
        KeyPart MISSING_ERROR = new Missing(MissingValue.MIS_ERROR);

        /**
         * The interned {@link Missing} constant for {@code marker} — never allocates.
         *
         * @param marker
         *            the missing-value marker
         * @return the interned {@code Missing} part
         */
        static KeyPart missing(MissingValue marker)
        {
            return switch (marker)
            {
            case MIS -> MISSING_MIS;
            case MIS_UNKNOWN -> MISSING_UNKNOWN;
            case MIS_ERROR -> MISSING_ERROR;
            };
        }


        /**
         * The <b>reporting-key</b> rendering of this component — what
         * {@code IndexHelper.buildGroupKey} and {@code GroupedResult.buildKey} join into the
         * per-group lookup/report key, so the reporting key distinguishes exactly what the grouping
         * distinguishes: {@link Present} renders its value, {@link Empty} renders {@code ""}, and
         * {@link Missing} renders {@code "\\u0001" + marker.name()} — an SOH-prefixed token that no
         * clinical string value can equal (control characters cannot occur in cell text, the same
         * argument the {@code NUL} key separator already rests on).
         *
         * <p>
         * ⚠⚠ <b>Presentation only — never re-parse this to recover identity.</b> Consumers compare
         * whole rendered keys for equality (or against authored real-string values, which the
         * {@code Missing} token can never equal — ruling part 4); nothing may split or interpret
         * one.
         * </p>
         *
         * @return the rendered component
         */
        String reportingForm();
    }

    /**
     * Keep a blank key component as a real key. The index-block reporting key
     * ({@code IndexHelper.buildGroupKey}) — the group is still formed, and since {@code W38-A1}
     * (Fix #249) the key renders the component's {@link KeyPart} identity: {@code ""} for
     * {@link KeyPart.Empty}, the marker token for {@link KeyPart.Missing}
     * ({@link KeyPart#reportingForm()}), so two groups the grouping distinguishes are never
     * reported under one key.
     *
     * <p>
     * ⭐ {@code W32-E3}: the blankness notion moved {@code MISSING_ONLY → MISSING_OR_EMPTY} — a
     * no-op at these call sites at the time, kept so the encoding change ({@code W38-A1}, now
     * <b>implemented</b>) did not land on an inconsistent base.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>This is now value-identical to {@link #FOLD_BLANK_KEYS}</b> — both are
     * {@code (true, MISSING_OR_EMPTY)} — and {@code GroupKeyPolicy} is a {@code record}, so they
     * are {@code equals()}. Nothing compares policies by value today (verified: no {@code ==} or
     * {@code equals} against a constant anywhere in {@code lib/}), and the two names are kept apart
     * because they document <em>different call-site intent</em>: this one is the <b>reporting</b>
     * key, {@code FOLD_BLANK_KEYS} the <b>per-row</b> key. ⛔ <b>Do not "deduplicate" them</b> — and
     * do not start switching on policy identity, which would silently conflate them.
     * </p>
     */
    public static final GroupKeyPolicy KEEP_MISSING_KEYS = new GroupKeyPolicy(true,
            Blankness.MISSING_OR_EMPTY);

    /**
     * Discard the group when a key component is blank. {@code GroupSemantics.partition} /
     * {@code IndexHelper.isBlockKeyMissing} and the singleton branch of a coalesced key.
     *
     * <p>
     * ⭐⭐ <b>{@code W32-E3} (owner, 2026-08-12): "blank" here now means a genuine missing marker
     * <em>or</em> {@code ""}.</b> Previously only the marker dropped the group and a {@code ""} key
     * formed a real one. ⚑ <b>These are the call sites where the ruling actually changes
     * behaviour</b> — a blank-keyed group that used to be checked is now discarded, so rules keyed
     * on an unpopulated identifier go quieter. That is the intent: <em>the key IS the code whose
     * decode is being checked; a blank key means there is no identity.</em>
     * </p>
     *
     * <p>
     * ⚠ The javadoc this replaced cited "the EC-26 / Fix #122 parity contract" as the warrant for
     * the old notion. That citation was void: the ledger records {@code Fix #122} as <em>Python
     * fork only; Java unchanged</em> — it moved the <b>fork</b> to match coreJ, so coreJ's
     * behaviour was never derived from parity and the label was retroactive. ⇒ nothing was owed to
     * parity here, which is part of why the ruling was free to move it.
     * </p>
     *
     * <p>
     * ⛔ Still <b>not</b> {@link #COALESCE_COMPONENT}: that one also treats whitespace-only as
     * blank, and collapsing the two would change {@code FDA-SE2279}.
     * </p>
     */
    public static final GroupKeyPolicy DROP_MISSING_KEYS = new GroupKeyPolicy(false,
            Blankness.MISSING_OR_EMPTY);

    /**
     * Keep a blank key component as a real key on the per-row key builders
     * ({@code GroupSemantics.keyPart} / {@code foldedKey} and the {@code target_is_not_sorted_by}
     * partition).
     *
     * <p>
     * ⚠ <b>The name's "fold" is historical.</b> Until {@code W38-A1} (Fix #249) these sites folded
     * every blank to the one string {@code ""}; the key builders now type each component as a
     * {@link KeyPart}, so blanks are still <em>kept</em> but are distinct identities —
     * {@link KeyPart.Empty} and each {@link KeyPart.Missing} marker form separate groups (ruling
     * parts 2–3), and no {@code Missing} ever equals a real value (part 4). The name is kept
     * because it still marks the call-site intent that differs from {@link #KEEP_MISSING_KEYS}:
     * per-row keys here, index-block reporting keys there.
     * </p>
     */
    public static final GroupKeyPolicy FOLD_BLANK_KEYS = new GroupKeyPolicy(true,
            Blankness.MISSING_OR_EMPTY);

    /**
     * The EC-24 coalesce-component policy: a component whose every column is unpopulated (missing,
     * {@code ""}, or whitespace-only) is missing and the row drops. Reachable by exactly one
     * shipped rule, {@code FDA-SE2279} ({@code within: [[USUBJID, POOLID]]}).
     *
     * <p>
     * ⚠⚠ This is <b>not</b> {@link #DROP_MISSING_KEYS}. Collapsing the two would silently change
     * {@code FDA-SE2279}: the difference is now <b>whitespace only</b> — a space-filled
     * {@code USUBJID} must fall through to {@code POOLID} here, and under {@code MISSING_OR_EMPTY}
     * it would not. ⚑ {@code W32-E3} narrowed the gap between these two policies (both now drop a
     * plain {@code ""}), which makes it <em>easier</em> to collapse them by mistake — the remaining
     * difference is small and load-bearing.
     * </p>
     */
    public static final GroupKeyPolicy COALESCE_COMPONENT = new GroupKeyPolicy(false,
            Blankness.MISSING_OR_WHITESPACE);

    /**
     * <b>The single missing-key predicate.</b> Every grouping path in the engine asks this one
     * question of this one method; the answer depends only on {@link #blankness()}, never on
     * {@link #keepMissings()} (what to <em>do</em> about a blank is the caller's decision, and
     * keeping the two apart is what lets one predicate serve both the fold and the discard).
     *
     * @param dv
     *            the key column's cell at the row being keyed; a {@code null} is blank under every
     *            notion
     * @return {@code true} when this cell does not contribute a known key value
     */
    public boolean isBlankKeyComponent(@org.jspecify.annotations.Nullable IDataValue dv)
    {
        if (dv == null)
        {
            return true;
        }
        return switch (blankness)
        {
        case MISSING_OR_EMPTY -> ScalarSemantics.isMissing(dv);
        case MISSING_OR_WHITESPACE -> ScalarSemantics.isMissing(dv)
                || dv.getValueAsString().strip().isEmpty();
        };
    }


    /**
     * <b>The single key-component classification</b> ({@code W38-A1} / Fix #249): the cell's
     * grouping identity as a {@link KeyPart}. A cell this policy's
     * {@link #isBlankKeyComponent(IDataValue)} calls non-blank is {@link KeyPart.Present
     * Present(getValueAsString())}; a blank cell keeps its own identity instead of folding to
     * {@code ""} — a genuine missing marker maps to its interned {@link KeyPart.Missing},
     * everything else blank (a literal {@code ""}, or a whitespace-only value under
     * {@link Blankness#MISSING_OR_WHITESPACE}) is {@link KeyPart#EMPTY}.
     *
     * <p>
     * The disposition still belongs to the caller: this classifies, {@link #keepMissings()} says
     * what to do with a blank — exactly the split {@link #isBlankKeyComponent(IDataValue)} already
     * keeps.
     * </p>
     *
     * @param dv
     *            the key column's cell at the row being keyed; {@code null} — a cell no loader
     *            hands out, every notion calls it blank — classifies as
     *            {@link KeyPart#MISSING_UNKNOWN}
     * @return the cell's grouping identity
     */
    public KeyPart keyPart(@org.jspecify.annotations.Nullable IDataValue dv)
    {
        if (dv == null)
        {
            return KeyPart.MISSING_UNKNOWN;
        }
        if (!isBlankKeyComponent(dv))
        {
            return new KeyPart.Present(dv.getValueAsString());
        }
        if (dv.getValue() instanceof MissingValue m)
        {
            return KeyPart.missing(m);
        }
        if (dv.isMissingOrInvalid())
        {
            // A numeric cell that is missing without carrying a MissingValue object — a NaN
            // double. Decode the NaN payload; a payload-less NaN is the generic numeric missing,
            // i.e. MIS.
            return KeyPart.missing(Objects.requireNonNull(
                    MissingValue.forValue(dv.getValueAsDouble(), MissingValue.MIS)));
        }
        return KeyPart.EMPTY;
    }


    /**
     * This policy with {@link #keepMissings()} overridden, or {@code this} when the disposition is
     * already the one asked for. The {@link Blankness} notion is preserved: an authored
     * {@code keep_missings} chooses the <em>disposition</em> of a blank, never what counts as
     * blank.
     *
     * @param keep
     *            the wanted disposition
     * @return a policy with the same blankness notion and the given disposition
     */
    public GroupKeyPolicy withKeepMissings(boolean keep)
    {
        return keep == keepMissings ? this : new GroupKeyPolicy(keep, blankness);
    }


    /**
     * This policy with {@link #keepMissings()} overridden by an authored declaration, or unchanged
     * when the rule declared nothing. The plumbing shape for the {@code keep_missings} authoring
     * surfaces: each call site starts from its own shipped default and lets a declaration win.
     *
     * @param declared
     *            the authored {@code keep_missings}, or {@code null} when the rule is silent
     * @return the effective policy
     */
    public GroupKeyPolicy withDeclared(@org.jspecify.annotations.Nullable Boolean declared)
    {
        return declared == null ? this : withKeepMissings(declared);
    }

}
