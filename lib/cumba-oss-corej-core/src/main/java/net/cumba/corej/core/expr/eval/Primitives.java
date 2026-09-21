package net.cumba.corej.core.expr.eval;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * Vectorized, per-row scalar predicates producing violation {@link BitSet}s over a row range
 * {@code [0, rowCount)}. Each method implements one operator family <i>exactly</i> by delegating
 * the scalar semantics to {@link ScalarSemantics} — the same code the legacy engine now runs.
 * Parity between the native evaluator and the legacy engine is therefore by construction for the
 * per-row scalar operators (the vector-layer suites are the proof).
 *
 * <p>
 * Both operands resolve through {@link Vector#value(int)} to one {@link TypedValue}. The scalar
 * numeric/equality comparisons (phase 3d) read the carrier's typed channels directly — the LHS its
 * typed cell ({@link TypedValue#cell()}), the RHS via {@link #targetAsDouble} /
 * {@link #targetString}, which consume {@link TypedValue#sourceCell()} for a cell-backed operand
 * (exact value, never a text round-trip — D84) and the resolved payload only for literals/computed
 * values. The temporal families deliberately keep the {@link TypedValue#resolved()} string read
 * (3b: a temporal value's runtime carrier is its ISO-8601 string). Every LHS row loop runs through
 * {@link #scan}, which tests each row's scalar cell. ⚠ Until 2026-09-21 an unqualified foreign
 * reference ({@code JoinedCandidatesVector}, REMOVED 2026-09-21) voted with ANY-MATCH over the
 * row's joined candidate values — the legacy {@code forEachJoinedValue} contract (B2,
 * {@code plans/done/PLAN-native-engine-residuals.md}). These primitives evaluate the full range;
 * candidate-mask short-circuiting and chunked ranges are layered on by the evaluator (Phase 3), not
 * here.
 * </p>
 */
public final class Primitives
{

    private Primitives()
    {
    }

    /** Per-row test: does the (typed) value at {@code row} fire? */
    @FunctionalInterface
    interface RowTest
    {

        boolean test(IDataValue dv, int row);
    }

    /**
     * Runs {@code test} per row over {@code v} and collects the firing rows — the native sibling of
     * the row loop. ⚠ It was candidate-aware until 2026-09-21: when {@code v} was a
     * {@code JoinedCandidatesVector} (an unqualified foreign reference carried by a
     * {@code Match_Datasets} join), each row votes with <b>ANY-MATCH</b> over all of its joined
     * candidate values: a row with matches fires when ANY candidate satisfies the test; a row of
     * the live lookup with NO matches votes once with a missing-value probe (so {@code empty},
     * {@code not_equal_to}-vs-concrete etc. still get a vote); and when no lookup matched anywhere
     * the row casts no vote at all (the legacy empty-BitSet contract).
     */
    static BitSet scan(Vector v, int rowCount, RowTest test)
    {
        BitSet result = new BitSet(rowCount);
        // ⭐⭐ REMOVED 2026-09-21 by PLAN-unqualified-name-primary-only's closure sweep. Its only
        // producer was ExprCompiler.joinedColumnVector, which resolved an UNQUALIFIED name out of a
        // Match_Datasets join -- the behaviour the owner's uniformity ruling abolished. With that
        // gone
        // nothing can construct a JoinedCandidatesVector, so this branch was unreachable.
        // ⚠ Found by review round 1, not by the compiler: unreachable code behind an `instanceof`
        // pattern is not a warning, and the class stayed alive only because its own mapped() called
        // its constructor.
        for (int r = 0; r < rowCount; r++)
        {
            if (test.test(v.value(r).cell(), r))
            {
                result.set(r);
            }
        }
        return result;
    }


    /**
     * Generic per-row string predicate: tests {@code test} against the string form of every
     * non-missing cell, setting a violation bit where it holds. Missing rows never fire. Used by
     * the SPI date/duration predicates whose row logic is "non-missing AND some test of the value".
     */
    public static BitSet stringPredicate(Vector v, int rowCount,
            java.util.function.Predicate<String> test)
    {
        return scan(v, rowCount,
                (dv, _) -> !ScalarSemantics.isMissing(dv) && test.test(dv.getValueAsString()));
    }

    // -------------------------------------------------------------------------
    // Equality (equal_to / not_equal_to / *_case_insensitive)
    // -------------------------------------------------------------------------


    /**
     * {@code caseInsensitive} folds case (and forces the type-insensitive string path);
     * {@code typeInsensitive} forces a string compare without case folding.
     *
     * <p>
     * The comparison is <em>literal</em>: a missing cell or missing target folds to {@code ""}, so
     * {@code equal_to} with both operands empty is a match (fires). Numeric mode (Phase 8) is
     * entered when the comparison is neither case- nor type-insensitive and either
     * {@code forceNumeric} (the {@code num()} marker), the LHS cell is numeric-typed, or the target
     * is a resolved {@link Number} payload; both sides are then read/parsed and compared
     * numerically under the Step C tolerance, falling back to the literal fold when either side
     * does not parse.
     * </p>
     *
     * <p>
     * ⭐ Phase 3d: the target is consumed from the typed carrier ({@link #targetAsDouble} /
     * {@link #targetString}) instead of the retired {@code TypedValue.comparisonOperand()}
     * derivation. ⚑ The decision tree used to be pinned branch-for-branch against
     * {@code ScalarSemantics.equalsNumericAware} by {@code TypedComparisonChannelParityTest}; both
     * went with D121 (terminal review, 2026-09-17) — {@code equalsNumericAware} was the legacy
     * operator engine's anchor, had no production caller after phase 7, and the test pinned this
     * method to it. This method is now the single equality anchor.
     * </p>
     */
    public static BitSet equality(Vector lhs, Vector rhs, int rowCount, boolean negate,
            boolean caseInsensitive, boolean typeInsensitive, boolean forceNumeric)
    {
        return scan(lhs, rowCount, (dv, r) ->
        {
            TypedValue target = rhs.value(r);
            boolean dvMissing = ScalarSemantics.isMissing(dv);
            return negate != equalsTypedAware(dv, dvMissing, target, caseInsensitive,
                    typeInsensitive, forceNumeric);
        });
    }


    /**
     * The per-cell equality verdict, consuming the carrier's typed channels (phase 3d). Verdict
     * tree: numeric mode when neither case- nor type-insensitive, the LHS is present and either
     * {@code forceNumeric}, the LHS cell is numeric-typed, or the target is a resolved
     * {@link Number}; both sides numeric ⇒ Step C tolerant equality; otherwise the literal textual
     * fold (missing folds to {@code ""}).
     *
     * <p>
     * ⭐⭐ <b>D34 #5-2's EQUALITY half, and with it D12 and D13's equality site</b> (terminal review
     * M2/M3; landed once {@code ExprCompiler.nameRefPlan} applied D76 — D131a). A genuine
     * {@link MissingValue} on either side settles the verdict through {@link #equalsWithMissing} —
     * the SAME {@link #compareWithMissing} the four order operators read — so the trichotomy is
     * structural rather than asserted: {@code MIS_A == MIS_B} is now <b>false</b> where
     * {@code MIS_A < MIS_B} is true, instead of both being true. Three consequences, all ruled
     * rather than incidental: a missing equals no present value, {@code ""} included (<b>D12</b>:
     * {@code VAR == ""} is true for an empty string and false for a {@code MissingValue}); two
     * missings are equal iff they are the same missing (#5-1); and because <b>D81</b> makes
     * {@code in} a disjunction of {@code ==}, {@link #isMember} takes the identical arm
     * (<b>D13</b>'s membership limb) instead of a second rule written twice.
     * </p>
     *
     * <p>
     * ⚠ <b>Only a genuine {@code MissingValue} takes it — a blank character cell does not</b> (D34
     * #1/#3, the {@link #equalsWithMissing} boundary). That is what keeps the EC-43
     * absent-equals-blank contract intact: an absent char column is all-{@code ""} (D76), a present
     * value, so {@code ABSENT == ""} stays true and {@code ABSENT != ABSENT} stays false. Before
     * D131a the NAME position minted {@code ALL_MISSING} for exactly that column while the VALUE
     * position applied D76, so this arm would have made the two sides of one comparison disagree —
     * which is why it was blocked on H1 and on nothing in this method.
     *
     * <p>
     * ⚠⚠ <b>The M3 survey measured this arm's population too narrowly, and the real one is written
     * down here</b> (R2 / L1-3b). It reported D12 as <em>"{@code == ""} / {@code != ""}: 0
     * sites"</em> — i.e. it counted only the {@code ""}-LITERAL shape. The arm moves three row
     * shapes, not one: a {@code ""} literal against a {@code MissingValue} (the counted shape), two
     * {@code MissingValue}s of different identity, and — the one the survey did not cover — a
     * <b>blank column compared for inequality against a genuine missing</b>, which was silent
     * before and now FIRES. Re-walked afterwards over every {@code $}-ref equality site in
     * {@code rules-src}: each either guards with {@code not empty(...)} or has a numeric column on
     * the blank side, so this is a <b>measurement gap, not a live defect</b> — but the next reader
     * must inherit the wider population, not the narrower one.
     * </p>
     */
    private static boolean equalsTypedAware(IDataValue dv, boolean dvMissing, TypedValue target,
            boolean caseInsensitive, boolean typeInsensitive, boolean forceNumeric)
    {
        MissingValue dvIdentity = TypedValue.missingIdentityOf(dv);
        MissingValue targetIdentity = target.missing();
        if (dvIdentity != null || targetIdentity != null)
        {
            return equalsWithMissing(dvIdentity, targetIdentity);
        }
        if (!caseInsensitive && !typeInsensitive && !dvMissing
                && (forceNumeric || isNumericTypedCell(dv) || isNumberPayload(target)))
        {
            double lhsD = dv.getValueAsDouble();
            Double rhsD = targetAsDouble(target);
            if (!Double.isNaN(lhsD) && rhsD != null)
            {
                return ScalarSemantics.numericEquals(lhsD, rhsD);
            }
        }
        String a = dvMissing ? "" : dv.getValueAsString();
        String b = targetString(target);
        return caseInsensitive ? a.equalsIgnoreCase(b) : a.equals(b);
    }


    /**
     * Whether the cell's declared type is numeric ({@code LONG}/{@code DOUBLE}) — the numeric-mode
     * trigger, distinct from {@link #isNumericCell}, which asks whether the cell's <em>content</em>
     * is a finite number.
     */
    private static boolean isNumericTypedCell(IDataValue dv)
    {
        var t = dv.getType();
        return t == net.cumba.datatable.values.DataValueType.LONG
                || t == net.cumba.datatable.values.DataValueType.DOUBLE;
    }


    /** Whether the target is a present resolved {@link Number} payload (a numeric literal). */
    private static boolean isNumberPayload(TypedValue target)
    {
        return !target.isMissing() && target.sourceCell() == null
                && target.resolved() instanceof Number;
    }


    /**
     * The comparison target as a {@code Double}, read from the typed carrier (phase 3d): a missing
     * value ⇒ {@code null}; a cell-backed value reads its {@link IDataValue#getValueAsDouble()}
     * <em>exactly</em> — never through the {@code getAsDoubleCleaned} text round-trip (D84) —
     * parsing the string form only when the raw read is the carrier {@code NaN} (a character cell);
     * a resolved payload converts directly ({@link Number}) or parses its text.
     */
    static @Nullable Double targetAsDouble(TypedValue target)
    {
        if (target.isMissing())
        {
            return null;
        }
        IDataValue cell = target.sourceCell();
        if (cell != null)
        {
            if (cell.isMissingOrInvalid())
            {
                return null;
            }
            double d = cell.getValueAsDouble();
            if (!Double.isNaN(d))
            {
                return d;
            }
            return parseOrNull(cell.getValueAsString());
        }
        Object payload = target.resolved();
        if (payload instanceof Number n)
        {
            return n.doubleValue();
        }
        return payload == null ? null : parseOrNull(payload.toString());
    }


    /**
     * The comparison target's literal fold (phase 3d): {@code ""} for a missing value, the cell's
     * string form for a cell-backed value, {@code toString()} of a resolved payload.
     */
    static String targetString(TypedValue target)
    {
        if (target.isMissing())
        {
            return "";
        }
        IDataValue cell = target.sourceCell();
        if (cell != null)
        {
            return cell.getValueAsString();
        }
        Object payload = target.resolved();
        return payload == null ? "" : payload.toString();
    }


    /** {@code Double.parseDouble}, or {@code null} when the text is not a number. */
    private static @Nullable Double parseOrNull(String text)
    {
        try
        {
            return Double.parseDouble(text);
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Numeric comparison (less_than / greater_than / *_or_equal_to)
    // -------------------------------------------------------------------------


    /**
     * ⭐ <b>D34 #5 — the order of a {@link MissingValue} against everything else, and what makes an
     * order comparison TOTAL</b> (phase 6c, D117). Every {@code MissingValue} sorts <b>below</b>
     * every non-missing value (#5); two of them sort by their {@linkplain MissingValue#getValue()
     * value byte} (#5-1); therefore a comparison in which either operand is a {@code MissingValue}
     * answers <b>true or false, never "no violation by default"</b> (#5-2).
     *
     * <p>
     * ⚠⚠ <b>Only a genuine {@code MissingValue} is low — a blank character cell is not.</b> That is
     * D96c's boundary, and it is the whole reason this method asks
     * {@link TypedValue#missingIdentityOf(IDataValue)} rather than
     * {@link ScalarSemantics#isMissing(IDataValue)}: the latter is the missing-<em>or</em>-empty
     * fold, and under D34 #1/#3 an empty string is a present value that happens to be positionally
     * unreadable. A present operand that simply will not read as a number keeps the shipped "no
     * violation" answer, unchanged.
     * </p>
     *
     * @param direction
     *            {@code > 0} for greater-than, {@code < 0} for less-than
     * @param orEqual
     *            whether equality satisfies the comparison
     * @return the firing rows
     */
    public static BitSet comparison(Vector lhs, Vector rhs, int rowCount, int direction,
            boolean orEqual)
    {
        return scan(lhs, rowCount, (dv, r) ->
        {
            TypedValue target = rhs.value(r);
            MissingValue lhsMissing = TypedValue.missingIdentityOf(dv);
            MissingValue rhsMissing = target.missing();
            if (lhsMissing != null || rhsMissing != null)
            {
                return orderWithMissing(lhsMissing, rhsMissing, direction, orEqual);
            }
            Double dvVal = ScalarSemantics.comparisonLhsAsDouble(dv);
            if (dvVal == null)
            {
                return false;
            }
            Double targetVal = targetAsDouble(target);
            if (targetVal == null)
            {
                return false;
            }
            // Step C: the four order operators carry the same tolerance as equality, or a pair
            // could be both "equal" and "less than" at once.
            return ScalarSemantics.compareNumericTolerant(dvVal, targetVal, direction, orEqual);
        });
    }


    /**
     * The verdict of one of the four <b>order</b> operators when at least one operand is a genuine
     * {@link MissingValue} (D34 #5/#5-1/#5-2, phase 6c). Shared by the plain, {@code date} and
     * {@code time} order families so they cannot drift — the one place the total order is written
     * down.
     *
     * <p>
     * ⛔ <b>Deliberately NOT reached by {@link #compareCells}' other consumer</b>, the
     * {@code relation=} neighbour relation of {@code has_next_corresponding_record}. EC-87's D-1
     * rules that a relation may only <em>widen</em> what "corresponds", and its D-3 that a missing
     * or blank cell keeps exactly its identity disposition <em>because</em> the comparison arm
     * answers {@code false} on it. Making that arm total would widen correspondence and suppress
     * findings — the opposite direction from D34 #5, and a population no review classified.
     * </p>
     *
     * @param lhs
     *            the left operand's missing identity, or {@code null} when it is present
     * @param rhs
     *            the right operand's missing identity, or {@code null} when it is present
     * @param direction
     *            {@code > 0} for greater-than, {@code < 0} for less-than
     * @param orEqual
     *            whether equality satisfies the comparison
     * @return the verdict — total, by construction
     */
    static boolean orderWithMissing(@Nullable MissingValue lhs, @Nullable MissingValue rhs,
            int direction, boolean orEqual)
    {
        int cmp = compareWithMissing(lhs, rhs);
        if (cmp == 0)
        {
            return orEqual;
        }
        return direction < 0 ? cmp < 0 : cmp > 0;
    }


    /**
     * ⭐ <b>The total order itself, written down ONCE</b> (D34 #5/#5-1) — the single place the
     * engine decides where a {@link MissingValue} sits relative to another operand. Every consumer
     * of the missing boundary derives from this: {@link #orderWithMissing} for the four order
     * operators, {@link #equalsWithMissing} for {@code ==}/{@code !=}, membership and the length
     * operators. Trichotomy is therefore structural rather than asserted — the order and equality
     * families cannot answer contradictory things about one pair, which is exactly what they did
     * between phase 6c and this fix ({@code X == Y} true while {@code X < Y} was also true).
     *
     * @param lhs
     *            the left operand's missing identity, or {@code null} when it is present
     * @param rhs
     *            the right operand's missing identity, or {@code null} when it is present
     * @return negative / zero / positive, as {@link Integer#compare}
     */
    private static int compareWithMissing(@Nullable MissingValue lhs, @Nullable MissingValue rhs)
    {
        if (lhs != null && rhs != null)
        {
            // #5-1 — two missings sort by their value byte, and are equal iff they are the same
            // missing.
            return Integer.compare(lhs.getValue(), rhs.getValue());
        }
        // #5 — every missing is below every non-missing value.
        return lhs != null ? -1 : 1;
    }


    /**
     * ⭐ <b>D34 #5-2's EQUALITY half, and D12/D13's first site</b>: the verdict of {@code ==} when
     * at least one operand is a genuine {@link MissingValue}. Two missings are equal <b>iff they
     * are the same missing</b>; a missing equals <b>no</b> present value — the empty string
     * included (D12), in a comparison or a membership list (D13).
     *
     * <p>
     * ⚠⚠ <b>Only a genuine {@code MissingValue} reaches here — a blank character cell does not.</b>
     * Same boundary as {@link #orderWithMissing} (D96c, D120f): under D34 #1/#3 an empty string is
     * a present value, so {@code BLANK == ""} stays <b>true</b> and only a carried or computed
     * {@code MissingValue} takes this arm. That is what makes D12's *"`VAR == ""` is true for an
     * empty string and false for a `MissingValue`"* one rule rather than two.
     * </p>
     *
     * @param lhs
     *            the left operand's missing identity, or {@code null} when it is present
     * @param rhs
     *            the right operand's missing identity, or {@code null} when it is present
     * @return whether the two operands are equal under the total order
     */
    static boolean equalsWithMissing(@Nullable MissingValue lhs, @Nullable MissingValue rhs)
    {
        return compareWithMissing(lhs, rhs) == 0;
    }


    /**
     * The verdict of one <b>polymorphic temporal</b> operator ({@code date_*} / {@code time}) when
     * at least one operand is a genuine {@link MissingValue}, for all six operators at once:
     * {@code direction == 0} is the equality family ({@link #equalsWithMissing}, inverted by
     * {@code negate} for {@code date_not_equal_to}), everything else the order family
     * ({@link #orderWithMissing}).
     *
     * <p>
     * ⚠ The equality arm is the M2 half phase 6c did not carry over. Only ONE row shape moves: two
     * operands that are the <b>same</b> missing now compare <b>equal</b>, where the pre-fix path
     * answered {@code negate} (i.e. "not equal") for every missing pair. Missing-vs-present and two
     * <em>different</em> missings answered "not equal" before and still do — so the fix is the
     * trichotomy, not a widening.
     * </p>
     *
     * @param lhs
     *            the left operand's missing identity, or {@code null} when it is present
     * @param rhs
     *            the right operand's missing identity, or {@code null} when it is present
     * @param direction
     *            0 = equality, {@code > 0} greater, {@code < 0} less
     * @param orEqual
     *            whether equality satisfies a directional comparison
     * @param negate
     *            whether the equality verdict is inverted ({@code date_not_equal_to})
     * @return the verdict — total, by construction
     */
    private static boolean missingVerdict(@Nullable MissingValue lhs, @Nullable MissingValue rhs,
            int direction, boolean orEqual, boolean negate)
    {
        return direction == 0 ? negate != equalsWithMissing(lhs, rhs)
                : orderWithMissing(lhs, rhs, direction, orEqual);
    }

    // -------------------------------------------------------------------------
    // Polymorphic date comparison (date_* family)
    // -------------------------------------------------------------------------


    /**
     * Dispatches on the per-cell type: numeric↔numeric uses the {@code 1e-9} epsilon; string↔string
     * uses {@link IsoDateComparison}'s hull semantics (Q16); a numeric/ISO mix fires for every
     * direction. Missing on either side ⇒ {@code negate} — <b>except</b> under the four order
     * operators, where a genuine {@link MissingValue} takes {@link #orderWithMissing} instead.
     *
     * <p>
     * ⚠ The two missing short-circuits are the <b>absent-column contract</b> and are deliberately
     * left alone by Q16: a blank <em>left</em> operand still yields {@code negate}, so a
     * {@code date_not_equal_to} still fires on it. Q16 changes only what happens once a value
     * actually reaches the comparison — including a right operand that resolves to {@code ""}
     * rather than to {@code null}, which is the shape a merged/joined column already produces.
     * </p>
     *
     * <p>
     * ⭐ <b>Phase 6c (D117) carves exactly one case out of them.</b> Under {@code <}/{@code <=}/
     * {@code >}/{@code >=} a genuine {@code MissingValue} on either side is <em>low</em> (D34 #5)
     * and the verdict is total (#5-2). A blank or masked character cell is <b>not</b> a
     * {@code MissingValue} (D34 #1/#3, D26) — its hull is unbounded, so it keeps SPEC §5.2(4)'s
     * "false for all six" and reaches none of this. That boundary is D96c, and it is why 41 of
     * Review 1b's 60 date-ordering sites needed no guard while 19 did.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>H1b (owner, 2026-09-17) narrows that last sentence for ONE operator.</b> §5.2(4)'s
     * "false for all six" is the verdict of the six <b>predicates</b>; {@code date_not_equal_to}
     * arrives as {@code direction == 0, negate == true}, and an unbounded hull now answers
     * {@code negate} in {@link IsoDateComparison#fires} — so it <b>fires</b>. That is exactly what
     * the LEFT-hand short-circuit two lines below has always done for a blank left cell, and the
     * ruling is that the two positions must agree. The four order operators and {@code ==} are
     * unchanged, so the 41 no-guard sites of Review 1b keep their verdict unless they use
     * {@code date_not_equal_to}.
     * </p>
     *
     * @param direction
     *            0 = equality, 1 = greater, -1 = less
     */
    public static BitSet dateComparison(Vector lhs, Vector rhs, int rowCount, int direction,
            boolean orEqual, boolean negate)
    {
        // The missing-LHS short-circuit stays HERE as well as inside compareCells: the right
        // operand must not be resolved for a blank left cell (a ComputedVector recomputes on every
        // call and may throw), exactly as the pre-extraction lambda ordered it. ⚠ Phase 6c widens
        // that by exactly one row shape — a left cell that is a GENUINE MissingValue under an
        // ORDER operator, where D34 #5's total order needs the right operand to answer at all.
        return scan(lhs, rowCount, (dv, r) ->
        {
            MissingValue lhsMissing = TypedValue.missingIdentityOf(dv);
            if (lhsMissing == null && ScalarSemantics.isMissing(dv))
            {
                return negate;
            }
            TypedValue target = rhs.value(r);
            MissingValue rhsMissing = target.missing();
            if (lhsMissing != null || rhsMissing != null)
            {
                return missingVerdict(lhsMissing, rhsMissing, direction, orEqual, negate);
            }
            return compareCells(dv, target.resolved(), direction, orEqual, negate, true);
        });
    }


    /**
     * EC-87 — the per-pair verdict of a {@code date_*}-family comparison, extracted verbatim from
     * {@link #dateComparison}'s scan lambda so the neighbouring-record relation
     * ({@code GroupSemantics.NeighbourRelation}, the {@code relation=} kwarg of
     * {@code has_next_corresponding_record}) and the row-level comparison cannot drift apart.
     * Numeric-first ({@link ScalarSemantics#matchNumeric}), then ISO via
     * {@link IsoDateComparison#fires}; a missing left cell or a {@code null} right operand answers
     * {@code negate}; a mixed numeric/ISO shape is a violation regardless of direction.
     *
     * <p>
     * ⛔ <b>Its {@code negate}-on-missing contract is unchanged by phase 6c, on purpose.</b> The
     * order family now settles a genuine {@code MissingValue} in {@link #dateComparison} before
     * reaching here, so what is left of this path's missing answer serves the equality family and
     * the {@code relation=} neighbour relation — where EC-87's D-1/D-3 want exactly the
     * {@code false} it gives (see {@link #orderWithMissing}).
     * </p>
     *
     * <p>
     * ⚠ <b>H1b did NOT change this method</b>, deliberately. The left short-circuit above is the
     * behaviour the owner ruled <i>correct</i> (<i>"I would state this is correct when it
     * fires"</i>); what was wrong was that an unpositionable RIGHT operand — which arrives as
     * {@code ""} rather than as {@code null} and therefore misses the {@code aRhs == null} arm —
     * answered differently. The fix is one line in {@link IsoDateComparison#fires}, where a hull is
     * actually known to be unbounded. ⚑ The EC-87 {@code relation=} consumer is <b>untouched by
     * it</b>, checked rather than assumed: {@code ExprCompiler} passes {@code negate == false}
     * there, and {@code negate} is what the unbounded branch now returns — so an unpositionable
     * neighbour still answers {@code false} ("does not correspond by relation"), exactly as before.
     * </p>
     *
     * @param aLhs
     *            the left cell
     * @param aRhs
     *            the resolved right operand — the shape {@link TypedValue#resolved()} yields: a
     *            {@link String} (the cell's string form, {@code ""} for a blank character cell), a
     *            {@link Number}, or {@code null} for a missing cell
     * @param aDirection
     *            0 = equality, 1 = greater, -1 = less
     * @param aOrEqual
     *            whether equality satisfies a directional comparison
     * @param aNegate
     *            whether the verdict is inverted ({@code date_not_equal_to})
     * @param aMixedVerdict
     *            the answer for a malformed mixed numeric/ISO pair. The {@code date_*} operators
     *            pass {@code true} ("the row is a finding regardless of direction"); the
     *            neighbouring-record relation, whose {@code true} means "corresponds — do NOT
     *            fire", passes {@code false} so a malformed pair is still reported (review of
     *            EC-87, finding 1: reading the Check-operator verdict backwards would have turned
     *            the always-report fallback into an always-suppress one)
     * @return {@code true} when the predicate holds for the pair
     */
    public static boolean compareCells(IDataValue aLhs, @Nullable Object aRhs, int aDirection,
            boolean aOrEqual, boolean aNegate, boolean aMixedVerdict)
    {
        if (ScalarSemantics.isMissing(aLhs))
        {
            return aNegate;
        }
        if (aRhs == null)
        {
            return aNegate;
        }
        Double lhsD = ScalarSemantics.tryNumericLhs(aLhs);
        Double rhsD = ScalarSemantics.tryNumericRhs(aRhs);
        if (lhsD != null && rhsD != null)
        {
            return aNegate != ScalarSemantics.matchNumeric(lhsD, rhsD, aDirection, aOrEqual);
        }
        if (lhsD == null && (rhsD == null || isIsoText(aRhs)))
        {
            return IsoDateComparison.fires(aLhs.getValueAsString(), aRhs.toString(), aDirection,
                    aOrEqual, aNegate);
        }
        // Mixed numeric/ISO — the data shape is malformed; the caller says what that means
        // (a violation regardless of operator direction for the date_* family).
        return aMixedVerdict;
    }


    /**
     * The {@code time}-type operator (SPEC §5.2 applied to time-of-day, phase 3b): dispatches
     * exactly like {@link #dateComparison} — numeric↔numeric compares numerically (a SAS
     * seconds-of-day pair) under {@code DATE_EPSILON}; string↔string goes to
     * {@link IsoTimeComparison}'s hull semantics; a mixed numeric/ISO pair fires for every
     * direction; missing on either side ⇒ {@code negate}, save for the phase-6c order carve-out
     * ({@link #orderWithMissing}, D34 #5). New surface with zero corpus users until phase 3c — the
     * {@code time()} conversion is how a comparison reaches it.
     *
     * @param direction
     *            0 = equality, 1 = greater, -1 = less
     */
    public static BitSet timeComparison(Vector lhs, Vector rhs, int rowCount, int direction,
            boolean orEqual, boolean negate)
    {
        return scan(lhs, rowCount, (dv, r) ->
        {
            MissingValue lhsMissing = TypedValue.missingIdentityOf(dv);
            if (lhsMissing == null && ScalarSemantics.isMissing(dv))
            {
                return negate;
            }
            TypedValue target = rhs.value(r);
            MissingValue rhsMissing = target.missing();
            if (lhsMissing != null || rhsMissing != null)
            {
                return missingVerdict(lhsMissing, rhsMissing, direction, orEqual, negate);
            }
            return compareTimeCells(dv, target.resolved(), direction, orEqual, negate);
        });
    }


    /**
     * The per-pair verdict of a {@code time}-operator comparison — {@link #compareCells}' contract
     * mirrored onto the time type (same missing short-circuits, same mixed-shape fallthrough).
     */
    private static boolean compareTimeCells(IDataValue aLhs, @Nullable Object aRhs, int aDirection,
            boolean aOrEqual, boolean aNegate)
    {
        if (ScalarSemantics.isMissing(aLhs))
        {
            return aNegate;
        }
        if (aRhs == null)
        {
            return aNegate;
        }
        Double lhsD = ScalarSemantics.tryNumericLhs(aLhs);
        Double rhsD = ScalarSemantics.tryNumericRhs(aRhs);
        if (lhsD != null && rhsD != null)
        {
            return aNegate != ScalarSemantics.matchNumeric(lhsD, rhsD, aDirection, aOrEqual);
        }
        if (lhsD == null && (rhsD == null || isTimeText(aRhs)))
        {
            return IsoTimeComparison.fires(aLhs.getValueAsString(), aRhs.toString(), aDirection,
                    aOrEqual, aNegate);
        }
        // Mixed numeric/ISO — the data shape is malformed; a violation regardless of direction,
        // exactly as the date operator rules it.
        return true;
    }


    /**
     * The time twin of {@link #isIsoText}: an hour-only comparand ({@code "10"}) parses as a
     * number, and without this reroute the mixed-shape branch would fire every operator on every
     * row. A string that positions as a time-of-day takes the hull reading instead.
     */
    private static boolean isTimeText(Object target)
    {
        return target instanceof String s && IsoTimeBounds.lower(s) != null;
    }


    /**
     * Q16 — whether a right operand that {@link ScalarSemantics#tryNumericRhs} parsed as a number
     * is in fact a calendar-valid ISO date, in which case the ISO reading wins.
     *
     * <p>
     * ⚠⚠ Without this, a <b>year-precision</b> comparand is hijacked by the numeric branch:
     * {@code Double.parseDouble("2026")} succeeds, the character left operand stays non-numeric,
     * and the "mixed shape ⇒ violation" fallthrough fires <b>every operator on every row</b> —
     * measured: {@code 2026-01-17} against {@code 2026} answered TRUE for all six, including
     * {@code ==} and {@code !=} simultaneously. A year is a legal partial date and must reach the
     * hull rule instead.
     * </p>
     * <p>
     * The test is deliberately narrow — only a string that is calendar-valid ISO at some precision
     * is re-routed, so a bare {@code 20260117} or a genuine SAS numeric day count still takes the
     * mixed-shape branch exactly as before.
     * </p>
     */
    private static boolean isIsoText(Object target)
    {
        return target instanceof String s && CalendarDates.isValidDate(s);
    }

    // -------------------------------------------------------------------------
    // Cross-precision part comparison (date_part_* / time_part_*)
    // -------------------------------------------------------------------------


    /** Compares only the date or time part of an ISO value. */
    public static BitSet datePartComparison(Vector lhs, Vector rhs, int rowCount,
            boolean isTimePart, boolean negate)
    {
        return scan(lhs, rowCount, (dv, r) ->
        {
            if (ScalarSemantics.isMissing(dv))
            {
                return negate;
            }
            Object target = rhs.value(r).resolved();
            if (target == null)
            {
                return negate;
            }
            Double lhsD = ScalarSemantics.tryNumericLhs(dv);
            Double rhsD = ScalarSemantics.tryNumericRhs(target);
            int code = datePartCode(dv, target, lhsD, rhsD, isTimePart, negate);
            // code: FIRE -> violation; UNDEFINED (e.g. *DTM date-only for a time-part query) ⇒
            // treat as missing, so a negated operator still fires.
            return code == FIRE || (code == UNDEFINED && negate);
        });
    }

    private static final int NO_FIRE = 0;

    private static final int FIRE = 1;

    private static final int UNDEFINED = -1;

    private static int code(boolean fires)
    {
        return fires ? FIRE : NO_FIRE;
    }


    private static int datePartCode(IDataValue dv, Object target, @Nullable Double lhsD,
            @Nullable Double rhsD, boolean isTimePart, boolean negate)
    {
        if (lhsD != null && rhsD != null)
        {
            boolean match;
            if (isTimePart)
            {
                double timePart = ((lhsD % 86_400.0) + 86_400.0) % 86_400.0;
                match = Math.abs(timePart - rhsD) < ScalarSemantics.DATE_EPSILON;
            }
            else
            {
                match = Math.floor(lhsD / 86_400.0) == Math.floor(rhsD);
            }
            return code(negate != match);
        }
        if (lhsD == null && rhsD == null)
        {
            return datePartIsoCode(dv.getValueAsString(), target.toString(), isTimePart, negate);
        }
        // Mixed — fire every direction.
        return FIRE;
    }


    private static int datePartIsoCode(String a, String b, boolean isTimePart, boolean negate)
    {
        // Timezone-correct parts (Phase 5): a trailing offset is applied instant-preserving
        // and the value rendered in UTC *before* the 'T' split — a +02:00 value's date part
        // may shift a day, and a bare time part no longer keeps the offset glued on (the
        // pre-existing "13:30:00+02:00" != "13:30:00" gap).
        String aUtc = ScalarSemantics.normalizeToUtc(a);
        String bUtc = ScalarSemantics.normalizeToUtc(b);
        String aPart;
        String bPart;
        if (isTimePart)
        {
            int aT = aUtc.indexOf('T');
            if (aT < 0)
            {
                return UNDEFINED;
            }
            aPart = aUtc.substring(aT + 1);
            int bT = bUtc.indexOf('T');
            if (bT >= 0)
            {
                bPart = bUtc.substring(bT + 1);
            }
            else if (bUtc.startsWith("T"))
            {
                bPart = bUtc.substring(1);
            }
            else
            {
                bPart = bUtc;
            }
        }
        else
        {
            int aT = aUtc.indexOf('T');
            aPart = aT >= 0 ? aUtc.substring(0, aT) : aUtc;
            int bT = bUtc.indexOf('T');
            bPart = bT >= 0 ? bUtc.substring(0, bT) : bUtc;
            if (aPart.isEmpty() || bPart.isEmpty())
            {
                return UNDEFINED;
            }
        }
        return code(negate != aPart.equals(bPart));
    }

    // -------------------------------------------------------------------------
    // Presence (empty / non_empty)
    // -------------------------------------------------------------------------


    /** Missing (null/invalid/empty) ⇒ violation. */
    public static BitSet empty(Vector v, int rowCount)
    {
        return scan(v, rowCount, (dv, _) -> isEmptyValue(dv));
    }


    /** Present, non-empty ⇒ violation. */
    public static BitSet nonEmpty(Vector v, int rowCount)
    {
        return scan(v, rowCount, (dv, _) -> !isEmptyValue(dv));
    }


    /**
     * Emptiness for {@code empty()}/{@code non_empty()}: a collection-valued operand (a list
     * metadata accessor such as {@code var_codelist_extended_values}, or any {@code $}-list/set
     * operation result) is empty iff it has no elements — cardinality, not the string form —
     * matching the Python engine's collection-aware {@code check_empty} (set/list/dict). This
     * resolved the NRI-008 review's F2 divergence, where the Java gate tested the never-empty
     * {@code toString()}. Scalar cells keep the {@link ScalarSemantics#isMissing} contract. Reads
     * the raw object off the already materialised {@code dv} (no second vector read).
     */
    private static boolean isEmptyValue(IDataValue dv)
    {
        if (dv != null && dv.getValue() instanceof Collection<?> collection)
        {
            return collection.isEmpty();
        }
        return ScalarSemantics.isMissing(dv);
    }

    // -------------------------------------------------------------------------
    // Regex (matches_regex / not_matches_regex — unanchored .find())
    // -------------------------------------------------------------------------


    /**
     * Unanchored {@code find()}. A <b>blank</b> cell folds to {@code ""} and the pattern is
     * evaluated against it, so {@code =~ /^$/} fires on a blank (D34 #1 — an empty string is an
     * empty string).
     *
     * <p>
     * ⭐ <b>D13 / SPEC §4(4), the regex limb</b>: a genuine {@link MissingValue} is <b>not a
     * string</b> and matches <b>no</b> pattern, {@code /^$/} included — so it answers
     * {@code negate} outright instead of being probed as {@code ""}. Same D96c boundary as
     * {@link #orderWithMissing} and {@link #equalsWithMissing}: blank is present, missing is not. ⚑
     * Free on the shipped corpus — of <b>237</b> {@code =~}/{@code !~} sites exactly <b>one</b>
     * carries a pattern that matches the empty string ({@code CDISC-CG0112},
     * {@code /^\d*\.?\d*$/}), and it is already guarded by {@code not empty(--DOSTXT)}. <b>The
     * method, so the figure can be re-derived</b> — run from the rules repository:
     * {@code grep -roh '=~\|!~' rules-src/checks --include=*.yaml | wc -l}. It counts occurrences
     * of the operator token, not lines and not files (153 files carry at least one). ⚑ Re-measured
     * 2026-09-19 for the CORE-family retirement: <b>254</b> before it (17 of them under
     * {@code rules-src/checks/CORE}) and <b>237</b> after — exactly the pre-retirement non-CORE
     * total, so no surviving site moved. ⚠ The figure standing here before was <b>253</b> and did
     * <em>not</em> reproduce: the method above measures 254 on the pre-deletion tree, so the
     * paragraph understated its own point by one. The M3 survey and its commit message both said
     * 247.
     * </p>
     *
     * <p>
     * ⚠ A parenthetical "115 distinct patterns, exactly one of them empty-matching" stood here and
     * was <b>removed</b> in review round 3: three independent re-derivations of it produced 107,
     * 115 and 132, because "distinct pattern" has no settled spelling (de-escaped or not? per
     * operator or pooled? per file or per corpus?). The <b>site</b> count above reproduces under a
     * stated method, and it is the one the conclusion rests on — but only under that method: it was
     * recorded here as 253 without one and came back 254. A number that cannot be re-derived does
     * not belong in a javadoc that exists to correct a number, and neither does one whose method is
     * not written down beside it.
     * </p>
     */
    public static BitSet regexFind(Vector v, Pattern pattern, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            if (TypedValue.missingIdentityOf(dv) != null)
            {
                return negate; // D13, regex limb — see this method's javadoc, above
            }
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            return negate != pattern.matcher(s).find();
        });
    }

    // -------------------------------------------------------------------------
    // Affix regex (prefix_/suffix_matches_regex — anchored .matches() on a substring)
    // -------------------------------------------------------------------------


    /**
     * Anchored {@code matches()} over an extracted prefix / suffix.
     *
     * <p>
     * ⭐ <b>D13 / SPEC §4(4), the regex limb — identical to {@link #regexFind}</b>: a genuine
     * {@link MissingValue} is not a string and matches <b>no</b> pattern, so it answers
     * {@code negate} outright; the affix is never cut and the pattern is never applied. ⚠ This
     * javadoc used to state the OPPOSITE (the retired "a missing cell folds to {@code ""} and the
     * extracted affix is evaluated against the pattern" behaviour) directly above the limb that
     * implements the ruling, which is how a maintainer asking whether
     * {@code suffix(IDVAR, 3) !~ /^SEQ$/} ({@code FDA}/{@code PMDA-SD1026}, {@code CDISC-CG0419})
     * fires on a marker-missing {@code IDVAR} would have read the contract and got the wrong
     * answer. Corrected R2 / L1-2.
     * </p>
     *
     * <p>
     * ⚑ A <em>blank</em> cell is not missing (D96c): it still folds to {@code ""}, the affix of
     * {@code ""} is {@code ""}, and the pattern is applied to it.
     * </p>
     */
    public static BitSet affixRegex(Vector v, Pattern pattern, int rowCount, boolean isPrefix,
            @Nullable Integer affixLen, boolean negate)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            if (TypedValue.missingIdentityOf(dv) != null)
            {
                return negate; // D13, regex limb — see regexFind
            }
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            String sub = isPrefix ? extractPrefix(s, affixLen) : extractSuffix(s, affixLen);
            return negate != pattern.matcher(sub).matches();
        });
    }


    /**
     * Per-row affix-length overload of {@link #affixRegex}: the length is read from
     * {@code affixLen} at each row via the shared exact-integer {@code integral} (a numeric column,
     * a char column parsing to an int, or a string/number literal). A missing / non-integral /
     * infinite length folds to {@code null}, i.e. the whole string (same edge semantics as the
     * {@link Integer}-arg overload).
     *
     * <p>
     * ⭐ The <b>D13 regex limb applies here too</b>, on the same terms as the {@link Integer}-arg
     * overload: a genuine {@link MissingValue} in {@code v} answers {@code negate} before any cut
     * or match, whatever the per-row length resolves to. Spelled out because this overload's
     * javadoc never mentioned the limb (R2 / L1-2).
     * </p>
     */
    public static BitSet affixRegex(Vector v, Pattern pattern, int rowCount, boolean isPrefix,
            Vector affixLen, boolean negate)
    {
        // Hoisted out of integral(): the gate is a pure function of the VECTOR, so calling it
        // per row asked the same question rowCount times to get the same answer.
        ColumnTypeGate.requireNumericRead(affixLen, "an integer-length operand");
        return scan(v, rowCount, (dv, r) ->
        {
            if (TypedValue.missingIdentityOf(dv) != null)
            {
                return negate; // D13, regex limb — see regexFind
            }
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            Integer n = integral(affixLen, r);
            String sub = isPrefix ? extractPrefix(s, n) : extractSuffix(s, n);
            return negate != pattern.matcher(sub).matches();
        });
    }


    /**
     * The {@code num(X)} conversion (R2/R10, PLAN-column-type-conformance §10): reads {@code v} as
     * a number, per cell, publishing {@link net.cumba.datatable.values.DataValueType#DOUBLE} — the
     * type the column-type gate interrogates, which is how an explicit {@code num()} satisfies the
     * numeric direction. Per-cell semantics are exactly
     * {@link ScalarSemantics#comparisonLhsAsDouble}: a missing cell, or one whose content does not
     * parse, yields <b>missing</b> — never an error (§4b F1: a mixed-content column such as
     * {@code --STRESC} legitimately holds free text on some rows, and {@code num()} must tolerate
     * exactly those rows).
     */
    public static Vector numConversion(Vector v, int rowCount)
    {
        return new ComputedVector(rowCount, net.cumba.datatable.values.DataValueType.DOUBLE,
                row -> ScalarSemantics.comparisonLhsAsDouble(v.value(row).cell()));
    }


    /**
     * The integral value of {@code v} at {@code row}, or {@code null} when missing, non-numeric, or
     * not an exact (finite) integer. Mirrors {@code BuiltinFunctions.integral}. A resolved
     * {@code Char} column here is a numeric read of character data and errors through
     * {@link ColumnTypeGate#requireNumericRead} (R3/R4) — a literal, a computed value or a
     * {@code num(...)} conversion pass.
     */
    private static @Nullable Integer integral(Vector v, int row)
    {
        if (v.isMissing(row))
        {
            return null;
        }
        double d = v.asDouble(row);
        if (Double.isNaN(d) || Double.isInfinite(d) || Double.compare(d, Math.rint(d)) != 0)
        {
            return null;
        }
        return (int) d;
    }


    /**
     * The first {@code prefixLen} characters of {@code value}; the whole string when the length is
     * {@code null}, non-positive, or longer than the value (mirrors the whole string).
     */
    public static String extractPrefix(String value, @Nullable Integer prefixLen)
    {
        if (prefixLen == null || prefixLen <= 0 || value.length() < prefixLen)
        {
            return value;
        }
        return value.substring(0, prefixLen);
    }


    /**
     * The last {@code suffixLen} characters of {@code value}; the whole string when the length is
     * {@code null}, non-positive, or longer than the value (mirrors the whole string).
     */
    public static String extractSuffix(String value, @Nullable Integer suffixLen)
    {
        if (suffixLen == null || suffixLen <= 0 || value.length() < suffixLen)
        {
            return value;
        }
        return value.substring(value.length() - suffixLen);
    }

    // -------------------------------------------------------------------------
    // Substring containment (contains / does_not_contain / starts_with / ends_with)
    // -------------------------------------------------------------------------

    private enum SubstringMode
    {
        CONTAINS, STARTS_WITH, ENDS_WITH
    }

    /**
     * Mirrors {@code evalContains}/{@code evalDoesNotContain}. A genuinely-missing <em>subject</em>
     * answers {@code negate} (R2-20 / D13 — see
     * {@link #substring(Vector, Vector, int, SubstringMode, boolean)}); a blank cell is a present
     * {@code ""} and is probed literally.
     *
     * <p>
     * ⚑ No main caller: the {@code String}-needle overloads of this family are reached only from
     * the tests, because {@code BuiltinFunctions} binds {@code contains} / {@code starts_with} /
     * {@code ends_with} to the per-row {@link Vector}-needle overloads for every arity. They are
     * kept as the single-needle expression of the same contract and pinned as such.
     * </p>
     */
    public static BitSet contains(Vector v, String target, int rowCount, boolean negate)
    {
        return substring(v, target, rowCount, SubstringMode.CONTAINS, negate);
    }


    /** Mirrors {@code evalStartsWith}; missing subject ⇒ {@code negate} (see {@link #contains}). */
    public static BitSet startsWith(Vector v, String target, int rowCount)
    {
        return substring(v, target, rowCount, SubstringMode.STARTS_WITH, false);
    }


    /** Mirrors {@code evalEndsWith}; missing subject ⇒ {@code negate} (see {@link #contains}). */
    public static BitSet endsWith(Vector v, String target, int rowCount)
    {
        return substring(v, target, rowCount, SubstringMode.ENDS_WITH, false);
    }


    /**
     * Per-row needle from {@code targets.value(row)}.
     *
     * <p>
     * ⛔ <b>The retired "decision #1" fold</b>: a null/missing needle used to fold to {@code ""}, so
     * {@code s.contains("")} held and {@code contains(X, MISSINGCOL)} fired on <b>every</b> row.
     * R2-20 replaced it — a genuinely-missing needle now answers {@code negate} outright. See
     * {@link #substring(Vector, Vector, int, SubstringMode, boolean)} for the ruling and its
     * consequences.
     * </p>
     */
    public static BitSet contains(Vector v, Vector targets, int rowCount, boolean negate)
    {
        return substring(v, targets, rowCount, SubstringMode.CONTAINS, negate);
    }


    /** Per-row needle variant of {@link #startsWith(Vector, String, int)}. */
    public static BitSet startsWith(Vector v, Vector targets, int rowCount)
    {
        return substring(v, targets, rowCount, SubstringMode.STARTS_WITH, false);
    }


    /** Per-row needle variant of {@link #endsWith(Vector, String, int)}. */
    public static BitSet endsWith(Vector v, Vector targets, int rowCount)
    {
        return substring(v, targets, rowCount, SubstringMode.ENDS_WITH, false);
    }


    /**
     * Canonical string form of a resolved needle in a STRING position. A {@link Number} (a
     * numeric-literal needle, D5) renders via the canonical {@code numberText} converter so
     * {@code 100.0} probes {@code "100"} rather than {@code "100.0"}; anything else (the normal
     * String case) uses {@code toString()} verbatim.
     *
     * <p>
     * ⚑ It no longer takes a {@code null}: {@link TypedValue#resolved()} answers {@code null}
     * <b>iff</b> the carrier is a genuine missing, and R2-20's arm now settles that row before the
     * needle is ever rendered. The {@code null} branch that used to stand here — the
     * {@code ""}-fold of "decision #1" — was therefore not merely retired but unreachable.
     * </p>
     */
    private static String canonicalNeedle(Object raw)
    {
        if (raw instanceof Number n)
        {
            return ExprCompiler.canonicalNumberText(n);
        }
        return raw.toString();
    }


    /**
     * ⭐⭐ <b>R2-20 / D13, the substring limb</b> (owner, 2026-09-17): <i>"all three return false
     * once one (or more) parameter is missing."</i> {@code contains} / {@code starts_with} /
     * {@code ends_with} are the fifth, sixth and seventh sites of D13's principle — <b>a
     * {@link MissingValue} is not a string</b> — joining {@link #regexFind}, {@link #affixRegex},
     * {@link #isMember} and the {@code len()} builtin ({@code BuiltinFunctions}, D13's len limb). A
     * genuine missing in <b>either</b> operand answers {@code negate} outright: the predicate is
     * <b>false</b>, and {@code negate} flips it exactly as the regex limb does.
     *
     * <p>
     * ⚠⚠ <b>The boundary is {@link TypedValue#missingIdentityOf} / {@link TypedValue#missing()},
     * never {@link ScalarSemantics#isMissing}</b> — that is D96c, and it is load-bearing. A
     * blank/empty character cell is <b>not</b> missing: it is the empty string (D34 #1/#3), and
     * {@code "".contains("")} is legitimately <b>true</b>. The {@code isMissing} fold below is the
     * <em>blank</em> fold and survives untouched; this arm sits in front of it and keys on the
     * genuine identity only. Widening it to {@code isMissing} reds the blank controls in
     * {@code SubstringMissingLimbTest} — <b>both</b> overloads' subject halves and the needle half,
     * each measured as a sabotage. ⚠ The vector-needle subject row had to be ADDED for that: with
     * only the literal-needle rows present the widening was GREEN, which is a failed experiment,
     * not a passing one.
     * </p>
     *
     * <p>
     * ⚠ <b>The consequence, stated so nobody rediscovers it</b>: {@code not contains(X, MISSING)}
     * now fires on <b>every</b> row, where before the ruling the <em>un</em>-negated
     * {@code contains(X, MISSING)} did (the retired "decision #1" {@code ""}-fold made
     * {@code s.contains("")} hold everywhere). The owner accepted this: the gain is consistency
     * with D13's other four sites, not removal of the fire-on-every-row shape, and
     * {@code not empty(...)} remains the author's guard either way. Of 148 substring sites in
     * {@code rules-src/checks}, <b>54</b> are negated — but the change is free on the corpus
     * (below), so none of them moves.
     * </p>
     *
     * <p>
     * ⚑ <b>Corpus exposure: zero, re-derived 2026-09-17.</b> Of those 148 sites <b>0</b> carry an
     * empty-string needle and <b>0</b> carry a needle that can be missing — the 20 whose needle is
     * not a bare literal are all {@code upper("<literal>")}. ⚠ So "0 non-literal needles" as
     * originally filed is wrong as written and right in its conclusion: what matters is that no
     * needle reads a column or a {@code $}-operation. Note also that a missing <em>subject</em>
     * moves only when the needle is {@code ""}: for a non-empty needle the old {@code ""}-fold
     * already answered {@code negate} by a different route ({@code "".contains("ABC")} is false).
     * </p>
     *
     * <p>
     * ⛔ <b>(a) The collection branch takes the same rule</b>: a genuinely-missing needle answers
     * {@code negate} before {@link #membershipOperand} is consulted, so the {@code $}-operation
     * membership arm cannot disagree with the scalar one. <b>(b) A missing ELEMENT inside the
     * collection is a different position and is deliberately OUT OF SCOPE</b> (owner): it simply
     * does not match the needle — {@link #containsElement} still folds it to {@code ""} — and it
     * does not make the whole call false.
     * </p>
     */
    private static BitSet substring(Vector v, Vector targets, int rowCount, SubstringMode mode,
            boolean negate)
    {
        return scan(v, rowCount, (dv, r) ->
        {
            TypedValue needleValue = targets.value(r);
            if (TypedValue.missingIdentityOf(dv) != null || needleValue.missing() != null)
            {
                // R2-20 / D13, the substring limb — see this method's javadoc, above. Both
                // operands are tested on the GENUINE-missing boundary (D96c); a blank cell is a
                // present "" and falls through to the literal probe below.
                return negate;
            }
            // Non-null by TypedValue.resolved()'s contract: it answers null iff the carrier is
            // missing, and that row returned above.
            Object raw = java.util.Objects.requireNonNull(needleValue.resolved());
            // D5: a numeric-literal needle resolves to a boxed Number; render it canonically so
            // contains(CODE, 100) probes "100" (not "100.0"). A String needle (column / quoted
            // literal) is unchanged — canonicalNeedle returns it verbatim.
            String needle = canonicalNeedle(raw);
            Collection<?> asCollection = membershipOperand(v, r, mode);
            if (asCollection != null)
            {
                return negate != containsElement(asCollection, needle);
            }
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            boolean hit = switch (mode)
            {
            case CONTAINS -> s.contains(needle);
            case STARTS_WITH -> s.startsWith(needle);
            case ENDS_WITH -> s.endsWith(needle);
            };
            return negate != hit;
        });
    }


    /**
     * EC-28(a) / Fix #131 — the COLLECTION operand of a {@code contains} probe, or {@code null}.
     * membership, not a substring probe.
     *
     * <p>
     * This mirrors the Python reference engine, whose {@code contains} routes through
     * {@code is_in(needle, cell)} = Python's polymorphic {@code in}
     * ({@code check_operators/helpers.py:275-283}): against a {@code str} cell that is a substring
     * test, against a {@code list}/{@code set} cell it is membership. Java previously rendered the
     * collection with {@code getValueAsString()} and probed the resulting {@code "[Y, N]"} text, so
     * {@code does_not_contain "Y"} was silenced by any embedding element ({@code "YES"}) —
     * under-reporting, plus a silent parity divergence.
     * </p>
     *
     * <p>
     * Deliberately CONTAINS-only: {@code starts_with} / {@code ends_with} have no list branch in
     * the Python operator either, so they keep the rendered-string behaviour. A collection reaches
     * this path from a {@code $}-operation reference materialised by
     * {@code ExprCompiler.variableVector}; case folding, when the case-insensitive surface is in
     * play, has already been applied element-wise by {@code BuiltinFunctions.caseFold}, so a plain
     * {@code equals} against the (likewise folded) needle is the whole comparison.
     * </p>
     *
     * @return the collection to test membership against, or {@code null} when this row's operand is
     *         not a collection (or the mode is not {@code CONTAINS}) — the caller then runs the
     *         ordinary substring logic.
     */
    private static @Nullable Collection<?> membershipOperand(Vector v, int row, SubstringMode mode)
    {
        if (mode != SubstringMode.CONTAINS)
        {
            return null;
        }
        return v.value(row).resolved() instanceof Collection<?> col ? col : null;
    }


    /**
     * Exact membership of {@code needle} in {@code col}. A {@code null} element contributes the
     * empty string — the same fold the scalar path applies to a missing cell, and what
     * {@code ExprCompiler.groupedMembership} does on the mirrored ({@code LHS ∈ $list}) direction.
     */
    private static boolean containsElement(Collection<?> col, String needle)
    {
        for (Object item : col)
        {
            if (needle.equals(item != null ? item.toString() : ""))
            {
                return true;
            }
        }
        return false;
    }


    private static BitSet substring(Vector v, String target, int rowCount, SubstringMode mode,
            boolean negate)
    {
        String needle = target != null ? target : "";
        return scan(v, rowCount, (dv, r) ->
        {
            if (TypedValue.missingIdentityOf(dv) != null)
            {
                // R2-20 / D13, the substring limb — see the Vector-needle overload's javadoc. A
                // literal needle is a present string (even ""), so only the SUBJECT can be missing
                // here, and a genuine missing is not a string: the predicate is false.
                return negate;
            }
            // EC-28(a): collection-valued LHS ⇒ exact membership (see membershipOperand).
            Collection<?> asCollection = membershipOperand(v, r, mode);
            if (asCollection != null)
            {
                return negate != containsElement(asCollection, needle);
            }
            // Blank fold (D96c): a BLANK cell is a present "" and is evaluated literally
            // (e.g. does_not_contain "X" fires on a blank); the suppress short-circuit is gone.
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            boolean hit = switch (mode)
            {
            case CONTAINS -> s.contains(needle);
            case STARTS_WITH -> s.startsWith(needle);
            case ENDS_WITH -> s.endsWith(needle);
            };
            return negate != hit;
        });
    }

    // -------------------------------------------------------------------------
    // Length comparison (longer_than / shorter_than)
    // -------------------------------------------------------------------------


    /**
     * Mirrors {@code evalHasEqualLength}/{@code evalHasNotEqualLength}: fires where the cell's
     * string length equals (or, when {@code negate}, does not equal) {@code length}. A missing cell
     * folds to {@code ""} (length 0), so {@code len("")=0} (operator-examples.md A.5).
     */
    public static BitSet lengthEquality(Vector v, int length, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            int len = ScalarSemantics.isMissing(dv) ? 0 : dv.getValueAsString().length();
            return negate != (len == length);
        });
    }


    /**
     * Per-row length variant of {@link #lengthEquality(Vector, int, int, boolean)}: reads the
     * target length for each row from {@code lengthVec} via the shared exact-integer
     * {@code integral}. A missing / non-integral length folds to {@code 0} (legacy {@code asInt}
     * parity, decision #5), so a blank length cell compares against length 0. A missing name cell
     * folds to {@code ""} (length 0), consistent with the literal overload.
     */
    public static BitSet lengthEquality(Vector v, Vector lengthVec, int rowCount, boolean negate)
    {
        // Hoisted out of integral() — see the note in the affix scan above.
        ColumnTypeGate.requireNumericRead(lengthVec, "an integer-length operand");
        return scan(v, rowCount, (dv, r) ->
        {
            Integer length = integral(lengthVec, r); // exact int; missing/non-integral ⇒ null
            int target = length == null ? 0 : length; // fold to 0 (legacy asInt)
            int len = ScalarSemantics.isMissing(dv) ? 0 : dv.getValueAsString().length();
            return negate != (len == target);
        });
    }


    /** Mirrors {@code evalLongerThan}/{@code evalShorterThan}; missing/empty ⇒ length 0. */
    public static BitSet lengthCompare(Vector v, int length, int rowCount, int direction)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            // "" / missing fold to length 0 (operator-examples.md A.5), consistent with the live
            // len(x) comparison path; no native caller routes here today but the mirror stays
            // right.
            int len = ScalarSemantics.isMissing(dv) ? 0 : dv.getValueAsString().length();
            return direction > 0 ? len > length : len < length;
        });
    }

    // -------------------------------------------------------------------------
    // Membership (is_contained_by / is_not_contained_by [/ case-insensitive])
    // -------------------------------------------------------------------------


    /**
     * ⭐ Phase 6b — <b>D81: {@code in} IS a disjunction of {@code ==}</b>, so this probe routes
     * through {@link #isMember}, the same per-member decision tree the equality operator runs
     * ({@link #equalsTypedAware}'s two arms, vectorised over the member set). Everything ruled for
     * {@code ==} reaches membership by construction: the Step C tolerance (D64, via the numeric arm
     * — D81c's linear scan, since tolerant equality is not hashable), the missing-fold semantics,
     * numeric-vs-character handling. {@code Primitives} thereby loses its second comparison path
     * (D81a/D81e): the old probe was a bare {@code set.contains(getValueAsString())} that never
     * entered numeric mode, so a numeric-typed cell could disagree with {@code ==} against the very
     * same member.
     *
     * <p>
     * For the case-insensitive variant ({@code upper(X) in […]} — D81d(ii) resolved as a
     * case-insensitive {@code ==}), {@code set} must already hold upper-cased
     * ({@link java.util.Locale#ROOT}) entries and {@code caseInsensitive} must be {@code true};
     * numbers have no case, so the numeric arm never runs there.
     * </p>
     *
     * <p>
     * ⛔ The two shapes D81d EXCLUDES from this desugaring stay their own operations:
     * <b>collection-LHS membership</b> — {@link #listMembership} (the accessor-list LHS,
     * {@code CDISC-CG0001}) and {@code ExprCompiler.compileTupleMembership} (the composite-key LHS,
     * {@code FDA/PMDA-SD0065}, {@code FDA/PMDA-SD1023}, {@code FDA-SE2259}, {@code PMDA-SD1143},
     * {@code CDISC-SEND-0224/-0333}) — a well-typed operation over a {@code list<T>} left operand,
     * not sugar for {@code ==}.
     * </p>
     */
    public static BitSet membership(Vector v, Set<String> set, int rowCount, boolean negate,
            boolean caseInsensitive)
    {
        return scan(v, rowCount, (dv, _) -> negate != isMember(dv, set, caseInsensitive));
    }


    /**
     * D81's per-cell membership verdict: {@code ∃ member: cell == member}, with {@code ==}'s own
     * decision tree ({@link #equalsTypedAware}) applied per member.
     *
     * <ul>
     * <li>⭐ <b>D13's membership limb</b>: a genuine {@link MissingValue} is a member of NO list,
     * {@code ""} included. It is the same arm {@link #equalsTypedAware} takes, not a second rule —
     * D81 makes {@code in} a disjunction of {@code ==}, so the two cannot be implemented apart
     * without breaking the invariant {@code InLowersIntoEqualityTest} pins. ⚑ <b>Vacuous on the
     * authored corpus, and implemented anyway</b> because SPEC §4.4 requires it: no authored list
     * carries {@code ""} (D81b, re-measured 2026-09-17), so the only member a missing could ever
     * have matched is one nothing spells. A blank character cell is untouched — it is a present
     * {@code ""} (D34 #1) and still probes literally, which is what keeps the opt-out shape
     * described below true.</li>
     * <li><b>Textual arm</b> (the literal-fold branch): a missing cell folds to {@code ""} and is
     * probed literally — so {@code is_not_contained_by ["Y","N"]} fires on a blank while an opt-out
     * list {@code ["","Y","N"]} suppresses it (D81b: measured, no authored list carries
     * {@code ""}). Realised as the exact {@code set.contains} probe, which for a textual comparison
     * is the member-wise {@code equals} disjunction by identity.</li>
     * <li><b>Numeric arm</b> (the numeric-mode branch): a non-missing, numeric-typed cell also
     * matches any member whose text parses to a number equal under the Step C tolerance
     * ({@link ScalarSemantics#numericEquals}) — exactly when {@code cell == "member"} would. A
     * linear scan by necessity (D81c: {@code a ≈ b} does not imply {@code hash(a) == hash(b)}); the
     * textual hit above stays the O(1) fast path.</li>
     * </ul>
     *
     * <p>
     * The two arms agree with {@link #equalsTypedAware} in either order: a textual hit under
     * applicable numeric mode implies the numeric match (the same text parses to the same number),
     * pinned member-by-member against {@link #equality} by {@code InLowersIntoEqualityTest}.
     * </p>
     */
    public static boolean isMember(IDataValue dv, Set<String> set, boolean caseInsensitive)
    {
        if (TypedValue.missingIdentityOf(dv) != null)
        {
            // D13/D81 — a genuine MissingValue is a member of no list, "" included. Same arm as
            // equalsTypedAware's, which is what "in IS a disjunction of ==" requires.
            return false;
        }
        boolean missing = ScalarSemantics.isMissing(dv);
        String s = missing ? "" : dv.getValueAsString();
        String probe = caseInsensitive ? s.toUpperCase(java.util.Locale.ROOT) : s;
        if (set.contains(probe))
        {
            return true;
        }
        if (!caseInsensitive && !missing && isNumericTypedCell(dv))
        {
            double lhs = dv.getValueAsDouble();
            if (!Double.isNaN(lhs))
            {
                for (String member : set)
                {
                    Double d = parseOrNull(member);
                    if (d != null && ScalarSemantics.numericEquals(lhs, d))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Numeric-membership variant (Phase 9b, decision D2) for an <b>all-numeric list literal</b>:
     * the probe is parsed to a number and tested against the numeric {@code members} via the shared
     * {@link ScalarSemantics#isNumericMember} parity anchor (the legacy the numeric membership
     * branch runs the identical helper). A missing / empty / non-numeric probe is never a member,
     * so {@code not in} fires on it — exactly as {@link #membership} fires on a blank not present
     * in the set. There is no case-insensitive numeric surface (numbers have no case), so this
     * never folds case.
     */
    public static BitSet numericMembership(Vector v, Set<Double> members, int rowCount,
            boolean negate)
    {
        return scan(v, rowCount, (dv, _) -> negate != ScalarSemantics.isNumericMember(dv, members));
    }


    /**
     * List-LHS membership — mirrors the Python reference engine's
     * {@code is_contained_by}/{@code is_not_contained_by} {@code is_column_of_iterables(target)}
     * branch ({@code any(is_in(item, comparator) for item in target_val)}). When the left operand
     * is a list value (e.g. a define codelist's coded codes, materialised as a {@code List<String>}
     * by the {@code var_codelist_coded_codes} accessor), the row's {@code anyInSet} verdict is
     * whether ANY element is contained in {@code set}; {@code is_contained_by} fires on it,
     * {@code is_not_contained_by} ({@code negate}) on its negation. An empty or absent list
     * contains nothing — so {@code is_not_contained_by} fires (Python: {@code any([])} is
     * {@code False}). {@code set} must already be upper-cased when {@code caseInsensitive} is
     * {@code true}.
     */
    public static BitSet listMembership(Vector v, Set<String> set, int rowCount, boolean negate,
            boolean caseInsensitive)
    {
        BitSet result = new BitSet(rowCount);
        for (int r = 0; r < rowCount; r++)
        {
            if (negate != anyInSet(v.value(r).resolved(), set, caseInsensitive))
            {
                result.set(r);
            }
        }
        return result;
    }


    /**
     * Per-row {@code not_contains_all(allowed, tokens)} verdict — the token-list form used by the
     * T9 delimiter-split membership rules
     * ({@code $codelist not_contains_all split_by(--VAR, "…")}). For each row whose {@code tokens}
     * cell is a {@code List} (e.g. produced by the native {@code split_by} value function), the row
     * fires when <b>any</b> token is <b>not</b> a member of {@code allowed} — i.e. the allowed set
     * does not contain every token. This mirrors the Python reference engine's
     * {@code not_contains_all} on two columns of iterables
     * ({@code ~all(is_in(item, allowed) for item in tokens)}, proven per-row by CoreIssue890): a
     * single valid token passes, one invalid token fires. A null / non-list / empty-list cell never
     * fires ({@code all([])} is {@code True} ⇒ contained ⇒ no violation). Matching is
     * case-sensitive (CT submission values are exact-case), and {@code null} tokens fold to
     * {@code ""}.
     */
    public static BitSet notContainsAllTokens(Vector tokens, Set<String> allowed, int rowCount)
    {
        BitSet result = new BitSet(rowCount);
        for (int r = 0; r < rowCount; r++)
        {
            if (!(tokens.value(r).resolved() instanceof List<?> list))
            {
                continue;
            }
            for (Object item : list)
            {
                if (!allowed.contains(item == null ? "" : item.toString()))
                {
                    result.set(r);
                    break;
                }
            }
        }
        return result;
    }


    private static boolean anyInSet(@Nullable Object value, Set<String> set,
            boolean caseInsensitive)
    {
        if (!(value instanceof List<?> list))
        {
            return false;
        }
        for (Object item : list)
        {
            if (item == null)
            {
                continue;
            }
            String s = caseInsensitive ? item.toString().toUpperCase(java.util.Locale.ROOT)
                    : item.toString();
            if (set.contains(s))
            {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Integer (is_integer / is_not_integer)
    // -------------------------------------------------------------------------


    /**
     * Mirrors {@code evalIsInteger}/{@code evalIsNotInteger}. Empty-string literal fix: a missing
     * cell folds to {@code ""}, which is not an integer, so {@code is_integer} stays {@code false}
     * and {@code is_not_integer} fires on a blank.
     */
    public static BitSet isInteger(Vector v, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) -> isIntegerCell(dv) != negate);
    }


    /**
     * Whether {@code dv} holds a finite whole number, <b>without</b> routing a numeric cell through
     * its string form.
     * <p>
     * ⚠ The obvious spelling — {@code isIntegerString(dv.getValueAsString())} — makes a numeric
     * cell take a full round trip: {@code DataValueDouble.getValueAsString()} allocates a
     * {@code String} and performs the whole-number test to decide its own format, then
     * {@code Double.parseDouble} parses it straight back, and the whole-number test runs a second
     * time. Once per row, per rule, across a corpus where 26 rules call {@code is_integer}.
     * </p>
     * <p>
     * ⭐ It is also arriving at the right answer through a wrong intermediate: that round trip
     * formats via {@code String.valueOf((long) cleaned)}, which <b>saturates</b>, so {@code 1e20}
     * becomes {@code "9223372036854775807"}. The verdict survives only because every double ≥
     * 2<sup>53</sup> is necessarily whole. Reading the double directly has no such hazard.
     * </p>
     * <p>
     * Behaviour is unchanged for every cell shape. A character cell still takes the string path —
     * {@code DataValueString.getValueAsDouble()} is a hard {@code NaN} by contract — and so does a
     * numeric cell holding {@code NaN}, which the string path rejects exactly as before.
     * </p>
     *
     * @param dv
     *            the cell.
     * @return {@code true} when the cell is a finite whole number.
     */
    private static boolean isIntegerCell(IDataValue dv)
    {
        if (ScalarSemantics.isMissing(dv))
        {
            return false;
        }
        double d = dv.getValueAsDouble();
        if (!Double.isNaN(d))
        {
            // Double.compare, not `==`: SpotBugs' FE_FLOATING_POINT_EQUALITY fires on the latter,
            // and integral() below already uses this idiom. Equivalent here — floor preserves the
            // sign of zero, so the two spellings cannot disagree once NaN is excluded above.
            return !Double.isInfinite(d) && Double.compare(d, Math.floor(d)) == 0;
        }
        return ScalarSemantics.isIntegerString(dv.getValueAsString());
    }

    // -------------------------------------------------------------------------
    // Numeric (is_numeric)
    // -------------------------------------------------------------------------


    /**
     * {@code is_numeric(x)} — fires where the cell holds a finite numeric value: a numeric-typed
     * cell always does, and a character cell does when its text parses as one. A missing cell folds
     * to {@code ""}, which is not numeric, so {@code is_numeric} stays {@code false} on a blank and
     * the {@code negate} (i.e. {@code not is_numeric}) form fires on it.
     *
     * <p>
     * ⚠⚠ <b>This javadoc described the pre-{@code ef60470} grammar until 2026-09-14</b>, three
     * lines above the {@link #isNumericCell} that had already replaced it — it still said a leading
     * {@code +}, a trailing dot ({@code 1.}) and scientific notation ({@code 1e5}) were
     * <em>rejected</em>, when all three are accepted. It is the javadoc a caller reads, and reading
     * it is enough to conclude the predicate is still narrow and to mis-diagnose a corpus scenario
     * on that basis. The accepted set is defined by {@link #isNumericCell} and stated there; do not
     * restate it here, where it can drift again.
     * </p>
     */
    public static BitSet isNumeric(Vector v, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) -> isNumericCell(dv) != negate);
    }


    /**
     * Whether {@code dv} holds a numeric value — the cell already being a number, or its text
     * parsing as one.
     * <p>
     * ⭐⭐ Owner ruling, 2026-09-14: <i>"for all numeric column types we do not need any check, as
     * the cell is numeric already. For char columns … even string values with exponents are numeric
     * values, so is_numeric should return true for all values that can be parsed to either Double
     * or long."</i>
     * </p>
     * <p>
     * ⛔ This <b>fixes a defect</b>, it is not only a fast path. The previous implementation asked a
     * hand-rolled scan that deliberately rejected exponents — but a numeric cell's text comes from
     * {@code String.valueOf(double)}, which <em>produces</em> exponents outside roughly
     * [10<sup>-3</sup>, 10<sup>7</sup>). So {@code is_numeric(NUMCOL)} answered <b>false</b> for a
     * genuinely numeric cell holding {@code 0.00000000012} (rendered {@code "1.2E-10"}) — the same
     * string-rendering-leaks-into-a-verdict shape that ruling R9 was made about, reached from the
     * other side. Lab results and {@code --STRESN} routinely sit in that range.
     * </p>
     * <p>
     * A non-finite value is not a numeric <em>value</em>: an infinity, and a cell whose text is
     * {@code "NaN"} or {@code "Infinity"}, answer {@code false} — consistent with
     * {@link #isIntegerCell}. A missing cell answers {@code false}, so {@code not is_numeric} still
     * fires on a blank.
     * </p>
     * ⚠ Widened deliberately, and the widening is real: {@code +5}, {@code 1e5}, {@code 1.} and
     * {@code .5} are now numeric where the old scan rejected them. ⚠⚠ It also inherits three things
     * {@code Double.parseDouble} accepts that nobody chose — surrounding whitespace
     * ({@code " 5 "}), a Java float/double suffix ({@code 5f}, {@code 5d}) and a hex-float literal
     * ({@code 0x1p3}). {@link ScalarSemantics#isIntegerString} has always accepted them too, so the
     * two predicates are now at least <em>consistent</em>; if those are to be rejected, they must
     * be rejected in both.
     *
     * @param dv
     *            the cell.
     * @return {@code true} when the cell holds a finite numeric value.
     */
    private static boolean isNumericCell(IDataValue dv)
    {
        if (ScalarSemantics.isMissing(dv))
        {
            return false;
        }
        double d = dv.getValueAsDouble();
        if (!Double.isNaN(d))
        {
            // A numeric-typed cell: already a number, no text involved.
            return !Double.isInfinite(d);
        }
        // A character cell — DataValueString.getValueAsDouble() is a hard NaN by contract (R1) —
        // or a numeric cell holding NaN, which the parse below rejects exactly as before.
        return isParsableNumber(dv.getValueAsString());
    }


    /**
     * Whether {@code s} parses as a finite number, by the ruling above.
     *
     * @param s
     *            the text form.
     * @return {@code true} when it parses to a finite {@code double}.
     */
    private static boolean isParsableNumber(String s)
    {
        if (s == null || s.isEmpty())
        {
            return false;
        }
        try
        {
            double d = Double.parseDouble(s);
            return !Double.isNaN(d) && !Double.isInfinite(d);
        }
        catch (NumberFormatException _)
        {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Valid test code / variable name (is_valid_testcd / is_valid_name)
    // -------------------------------------------------------------------------


    /**
     * {@code is_valid_testcd(x)} — fires where the cell's string form is a valid findings-domain
     * <b>test code</b>: a hand-rolled scan (no regex) of the legacy
     * {@code ^[a-zA-Z_][a-zA-Z0-9_]{0,7}$} charset — first char {@code [A-Za-z_]}, every remaining
     * char {@code [A-Za-z0-9_]}, total length 1..8 (mixed case allowed). A missing cell folds to
     * {@code ""} (length 0), which is not a valid test code, so the predicate does not fire on a
     * blank and the {@code not is_valid_testcd} form fires on it — matching the legacy
     * {@code not_matches_regex} on an empty cell.
     */
    public static BitSet isValidTestcd(Vector v, int rowCount)
    {
        return stringPredicate(v, rowCount, s -> isValidIdentifier(s, false));
    }


    /**
     * {@code is_valid_name(x)} — fires where the cell's string form is a valid SAS/CDISC
     * <b>variable (column) name</b>: a hand-rolled scan (no regex) of the legacy
     * {@code ^[A-Z_][A-Z0-9_]{0,7}$} charset — first char {@code [A-Z_]}, every remaining char
     * {@code [A-Z0-9_]}, total length 1..8 (<b>uppercase only</b>). Missing/empty behaviour mirrors
     * {@link #isValidTestcd}.
     */
    public static BitSet isValidName(Vector v, int rowCount)
    {
        return stringPredicate(v, rowCount, s -> isValidIdentifier(s, true));
    }


    /**
     * Hand-rolled identifier scan backing {@link #isValidTestcd}/{@link #isValidName}. When
     * {@code upperOnly} is {@code false} the charset is {@code [A-Za-z_]} (first) / {@code
     * [A-Za-z0-9_]} (rest); when {@code true} it is the uppercase-only {@code [A-Z_]} / {@code
     * [A-Z0-9_]}. Length must be 1..8 inclusive.
     */
    private static boolean isValidIdentifier(String s, boolean upperOnly)
    {
        int n = s.length();
        if (n < 1 || n > 8)
        {
            return false;
        }
        for (int i = 0; i < n; i++)
        {
            char c = s.charAt(i);
            boolean upper = c >= 'A' && c <= 'Z';
            boolean lower = !upperOnly && c >= 'a' && c <= 'z';
            boolean digit = i > 0 && c >= '0' && c <= '9';
            boolean underscore = c == '_';
            if (!(upper || lower || digit || underscore))
            {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Has-letter / has-digit (has_alpha / has_digit)
    // -------------------------------------------------------------------------


    /**
     * {@code has_alpha(x)} — fires where the cell contains at least one ASCII letter
     * {@code [A-Za-z]}. Mirrors the legacy {@code matches_regex ".*[a-zA-Z].*"} (an unanchored find
     * for a letter). A missing cell folds to {@code ""}, which has no letter, so it does not fire.
     */
    public static BitSet hasAlpha(Vector v, int rowCount)
    {
        return stringPredicate(v, rowCount, Primitives::containsAlpha);
    }


    /**
     * {@code has_digit(x)} — fires where the cell contains at least one ASCII digit {@code [0-9]}.
     * Mirrors the legacy {@code matches_regex ".*[0-9].*"}. Missing/empty does not fire.
     */
    public static BitSet hasDigit(Vector v, int rowCount)
    {
        return stringPredicate(v, rowCount, Primitives::containsDigit);
    }


    private static boolean containsAlpha(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
            {
                return true;
            }
        }
        return false;
    }


    private static boolean containsDigit(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9')
            {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Duration (invalid_duration)
    // -------------------------------------------------------------------------


    /**
     * Mirrors {@code evalInvalidDuration}. Empty-string literal fix: a missing cell folds to
     * {@code ""}, which is not a valid duration, so {@code invalid_duration} fires on a blank.
     */
    public static BitSet invalidDuration(Vector v, int rowCount, boolean allowNegative)
    {
        return scan(v, rowCount, (dv, _) -> ScalarSemantics.isInvalidDuration(
                ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString(), allowNegative));
    }

    // -------------------------------------------------------------------------
    // Structural ISO date predicates (legacy: is_complete_date / is_incomplete_date / invalid_date)
    // -------------------------------------------------------------------------


    /** Mirrors {@code evalIsCompleteDate} (structural, no calendar validation). */
    public static BitSet isCompleteDateStructural(Vector v, int rowCount)
    {
        return scan(v, rowCount, (dv, _) -> !ScalarSemantics.isMissing(dv)
                && ScalarSemantics.isCompleteDate(dv.getValueAsString()));
    }


    /** Mirrors {@code evalIsIncompleteDate} (structural): partial but not complete. */
    public static BitSet isIncompleteDateStructural(Vector v, int rowCount)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            if (ScalarSemantics.isMissing(dv))
            {
                return false;
            }
            String s = dv.getValueAsString();
            return ScalarSemantics.isPartialDate(s) && !ScalarSemantics.isCompleteDate(s);
        });
    }


    /**
     * Mirrors {@code evalInvalidDate} (structural): not a valid partial-date prefix. Empty-string
     * literal fix: a missing cell folds to {@code ""}, which is not a partial date, so
     * {@code invalid_date} fires on a blank.
     */
    public static BitSet invalidDateStructural(Vector v, int rowCount)
    {
        return scan(v, rowCount, (dv, _) -> !ScalarSemantics
                .isPartialDate(ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString()));
    }


    /**
     * {@code invalid_date} as the native engine registers it: calendar-validating (a
     * calendar-impossible value such as {@code 2023-02-29} is invalid — see
     * {@code BuiltinFunctionsTest.dateFamilyRejectsImpossibleDay}) AND firing on a missing/blank
     * cell, since a blank is not a date at all (so an empty value is not silently hidden — it is
     * reported as invalid, matching the legacy operator's blank handling). Differs from the
     * structural {@link #invalidDateStructural} only on calendar-impossible-but-structural inputs;
     * both fire on a blank.
     */
    public static BitSet invalidDateCalendar(Vector v, int rowCount)
    {
        return scan(v, rowCount, (dv, _) -> ScalarSemantics.isMissing(dv)
                || !CalendarDates.isValidDate(dv.getValueAsString()));
    }


    /**
     * {@code is_complete_date_part} ({@code negate=false}) / {@code is_not_complete_date_part}
     * ({@code negate=true}) — Fix #157. Empty-string literal fix, exactly as
     * {@link #isInteger(Vector, int, boolean)}: a missing cell folds to {@code ""}, whose date
     * portion is not complete, so the positive form stays {@code false} on a blank and the negative
     * form fires on it. Under the EC-43 contract an absent column is all-missing, so the negative
     * form fires on every row of one — it is a negative leaf and needs a guard exactly like
     * {@code is_not_integer}.
     *
     * @see CalendarDates#isCompleteDatePart(String)
     */
    public static BitSet isCompleteDatePart(Vector v, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) -> CalendarDates.isCompleteDatePart(
                ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString()) != negate);
    }

}
