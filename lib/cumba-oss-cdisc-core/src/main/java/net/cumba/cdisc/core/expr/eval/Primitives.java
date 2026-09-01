package net.cumba.cdisc.core.expr.eval;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.cumba.cdisc.core.exec.ScalarSemantics;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Vectorized, per-row scalar predicates producing violation {@link BitSet}s over a row range
 * {@code [0, rowCount)}. Each method implements one operator family <i>exactly</i> by delegating
 * the scalar semantics to {@link ScalarSemantics} — the same code the legacy engine now runs.
 * Parity between the native evaluator and the legacy engine is therefore by construction for the
 * per-row scalar operators (the vector-layer suites are the proof).
 *
 * <p>
 * LHS operands are read via {@link Vector#dataValue(int)} (typed cell); RHS operands via
 * {@link Vector#resolvedObject(int)} (resolved {@code Object}), matching the legacy
 * name-position/value-position split. Every LHS row loop runs through {@link #scan}, the native A
 * plain vector tests its scalar cell, while an unqualified foreign reference
 * ({@link JoinedCandidatesVector}) votes with ANY-MATCH over all of the row's joined candidate
 * values — the legacy {@code forEachJoinedValue} contract (B2,
 * {@code plans/PLAN-native-engine-residuals.md}). These primitives evaluate the full range;
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
     * the row loop. Candidate-aware: when {@code v} is a {@link JoinedCandidatesVector} (an
     * unqualified foreign reference carried by a {@code Match_Datasets} join), each row votes with
     * <b>ANY-MATCH</b> over all of its joined candidate values: a row with matches fires when ANY
     * candidate satisfies the test; a row of the live lookup with NO matches votes once with a
     * missing-value probe (so {@code empty}, {@code not_equal_to}-vs-concrete etc. still get a
     * vote); and when no lookup matched anywhere the row casts no vote at all (the legacy
     * empty-BitSet contract).
     */
    static BitSet scan(Vector v, int rowCount, RowTest test)
    {
        BitSet result = new BitSet(rowCount);
        if (v instanceof JoinedCandidatesVector jc)
        {
            for (int r = 0; r < rowCount; r++)
            {
                List<String> candidates = jc.candidates(r);
                if (candidates == null)
                {
                    continue; // no live lookup — no vote (legacy: empty BitSet)
                }
                if (candidates.isEmpty())
                {
                    if (test.test(DataValues.of(null), r))
                    {
                        result.set(r);
                    }
                    continue;
                }
                for (String value : candidates)
                {
                    if (test.test(DataValues.of(value), r))
                    {
                        result.set(r);
                        break;
                    }
                }
            }
            return result;
        }
        for (int r = 0; r < rowCount; r++)
        {
            if (test.test(v.dataValue(r), r))
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
     * The comparison is <em>literal</em>: a missing cell or null target folds to {@code ""}, so
     * {@code equal_to} with both operands empty is a match (fires). Numeric mode (Phase 8) is
     * entered — via the shared {@link ScalarSemantics#equalsNumericAware} — when the comparison is
     * neither case- nor type-insensitive and either {@code forceNumeric} (the {@code num()}
     * marker), the LHS cell is numeric-typed, or the resolved target is a {@link Number}; both
     * sides are then parsed and compared numerically, falling back to the literal fold when either
     * side does not parse.
     */
    public static BitSet equality(Vector lhs, Vector rhs, int rowCount, boolean negate,
            boolean caseInsensitive, boolean typeInsensitive, boolean forceNumeric)
    {
        return scan(lhs, rowCount, (dv, r) ->
        {
            Object rawTarget = rhs.resolvedObject(r);
            boolean dvMissing = ScalarSemantics.isMissing(dv);
            boolean equal = ScalarSemantics.equalsNumericAware(dv, rawTarget, dvMissing,
                    caseInsensitive, typeInsensitive, forceNumeric);
            return negate != equal;
        });
    }

    // -------------------------------------------------------------------------
    // Numeric comparison (less_than / greater_than / *_or_equal_to)
    // -------------------------------------------------------------------------


    /**
     * {@code direction > 0} for greater-than, {@code < 0} for less-than. Missing/NaN LHS or
     * non-numeric RHS ⇒ no violation.
     */
    public static BitSet comparison(Vector lhs, Vector rhs, int rowCount, int direction,
            boolean orEqual)
    {
        return scan(lhs, rowCount, (dv, r) ->
        {
            Double dvVal = ScalarSemantics.comparisonLhsAsDouble(dv);
            if (dvVal == null)
            {
                return false;
            }
            Double targetVal = ScalarSemantics.comparisonTargetAsDouble(rhs.resolvedObject(r));
            if (targetVal == null)
            {
                return false;
            }
            int cmp = Double.compare(dvVal, targetVal);
            if (direction > 0)
            {
                return orEqual ? cmp >= 0 : cmp > 0;
            }
            return orEqual ? cmp <= 0 : cmp < 0;
        });
    }

    // -------------------------------------------------------------------------
    // Polymorphic date comparison (date_* family)
    // -------------------------------------------------------------------------


    /**
     * Dispatches on the per-cell type: numeric↔numeric uses the {@code 1e-9} epsilon; string↔string
     * uses {@link IsoDateComparison}'s hull semantics (Q16); a numeric/ISO mix fires for every
     * direction. Missing on either side ⇒ {@code negate}.
     *
     * <p>
     * ⚠ The two missing short-circuits are the <b>absent-column contract</b> and are deliberately
     * left alone by Q16: a blank <em>left</em> operand still yields {@code negate}, so a
     * {@code date_not_equal_to} still fires on it. Q16 changes only what happens once a value
     * actually reaches the comparison — including a right operand that resolves to {@code ""}
     * rather than to {@code null}, which is the shape a merged/joined column already produces.
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
        // call and may throw), exactly as the pre-extraction lambda ordered it.
        return scan(lhs, rowCount, (dv, r) -> ScalarSemantics.isMissing(dv) ? negate
                : compareCells(dv, rhs.resolvedObject(r), direction, orEqual, negate, true));
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
     * @param aLhs
     *            the left cell
     * @param aRhs
     *            the resolved right operand — the shape {@link Vector#resolvedObject(int)} yields:
     *            a {@link String} (the cell's string form, {@code ""} for a blank character cell),
     *            a {@link Number}, or {@code null} for a missing cell
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
            Object target = rhs.resolvedObject(r);
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
     * Unanchored {@code find()}. Empty-string literal fix: a missing cell folds to {@code ""} and
     * the pattern is evaluated against it (so e.g. {@code matches_regex "^$"} fires on a blank),
     * rather than short-circuiting to {@code negate}.
     */
    public static BitSet regexFind(Vector v, Pattern pattern, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            return negate != pattern.matcher(s).find();
        });
    }

    // -------------------------------------------------------------------------
    // Affix regex (prefix_/suffix_matches_regex — anchored .matches() on a substring)
    // -------------------------------------------------------------------------


    /**
     * Anchored {@code matches()}. Empty-string literal fix: a missing cell folds to {@code ""} and
     * the extracted affix (also {@code ""}) is evaluated against the pattern, rather than
     * short-circuiting to {@code negate}.
     */
    public static BitSet affixRegex(Vector v, Pattern pattern, int rowCount, boolean isPrefix,
            @Nullable Integer affixLen, boolean negate)
    {
        return scan(v, rowCount, (dv, _) ->
        {
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
     */
    public static BitSet affixRegex(Vector v, Pattern pattern, int rowCount, boolean isPrefix,
            Vector affixLen, boolean negate)
    {
        return scan(v, rowCount, (dv, r) ->
        {
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            Integer n = integral(affixLen, r);
            String sub = isPrefix ? extractPrefix(s, n) : extractSuffix(s, n);
            return negate != pattern.matcher(sub).matches();
        });
    }


    /**
     * The integral value of {@code v} at {@code row}, or {@code null} when missing, non-numeric, or
     * not an exact (finite) integer. Mirrors {@code BuiltinFunctions.integral}.
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

    /** Mirrors {@code evalContains}/{@code evalDoesNotContain}; missing ⇒ no violation. */
    public static BitSet contains(Vector v, String target, int rowCount, boolean negate)
    {
        return substring(v, target, rowCount, SubstringMode.CONTAINS, negate);
    }


    /** Mirrors {@code evalStartsWith}; missing ⇒ no violation. */
    public static BitSet startsWith(Vector v, String target, int rowCount)
    {
        return substring(v, target, rowCount, SubstringMode.STARTS_WITH, false);
    }


    /** Mirrors {@code evalEndsWith}; missing ⇒ no violation. */
    public static BitSet endsWith(Vector v, String target, int rowCount)
    {
        return substring(v, target, rowCount, SubstringMode.ENDS_WITH, false);
    }


    /**
     * Per-row needle from {@code targets.resolvedObject(row)}. A null/missing target folds to "" —
     * the same empty-string fold the LHS uses — so {@code contains(X, MISSINGCOL)} matches
     * {@code s.contains("") = true} and fires on every row (decision #1).
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
     * Canonical string form of a resolved needle in a STRING position. A {@code null} folds to
     * {@code ""} (decision #1); a {@link Number} (a numeric-literal needle, D5) renders via the
     * canonical {@code numberText} converter so {@code 100.0} probes {@code "100"} rather than
     * {@code "100.0"}; anything else (the normal String case) uses {@code toString()} verbatim.
     */
    private static String canonicalNeedle(@Nullable Object raw)
    {
        if (raw == null)
        {
            return "";
        }
        if (raw instanceof Number n)
        {
            return ExprCompiler.canonicalNumberText(n);
        }
        return raw.toString();
    }


    private static BitSet substring(Vector v, Vector targets, int rowCount, SubstringMode mode,
            boolean negate)
    {
        return scan(v, rowCount, (dv, r) ->
        {
            // Empty-string fold (decision #1): a null/missing needle folds to "" just like the LHS,
            // so contains(X, MISSINGCOL) fires on every row.
            Object raw = targets.resolvedObject(r);
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
        return v.resolvedObject(row) instanceof Collection<?> col ? col : null;
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
            // EC-28(a): collection-valued LHS ⇒ exact membership (see membershipOperand).
            Collection<?> asCollection = membershipOperand(v, r, mode);
            if (asCollection != null)
            {
                return negate != containsElement(asCollection, needle);
            }
            // Empty-string literal fix: a missing cell folds to "" and is evaluated literally
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
     * Mirrors {@code evalIsContainedBy}/{@code evalIsNotContainedBy}; missing ⇒ no violation. For
     * the case-insensitive variant, {@code set} must already hold upper-cased
     * ({@link java.util.Locale#ROOT}) entries and {@code caseInsensitive} must be {@code true}.
     */
    public static BitSet membership(Vector v, Set<String> set, int rowCount, boolean negate,
            boolean caseInsensitive)
    {
        return scan(v, rowCount, (dv, _) ->
        {
            // Empty-string literal fix: a missing cell folds to "" and is probed literally, so
            // is_not_contained_by ["Y","N"] fires on a blank while an opt-out list ["","Y","N"]
            // suppresses it.
            String s = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            String probe = caseInsensitive ? s.toUpperCase(java.util.Locale.ROOT) : s;
            return negate != set.contains(probe);
        });
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
            if (negate != anyInSet(v.resolvedObject(r), set, caseInsensitive))
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
            if (!(tokens.resolvedObject(r) instanceof List<?> list))
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
        return scan(v, rowCount, (dv, _) -> ScalarSemantics.isIntegerString(
                ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString()) != negate);
    }

    // -------------------------------------------------------------------------
    // Numeric (is_numeric)
    // -------------------------------------------------------------------------


    /**
     * {@code is_numeric(x)} — fires where the cell's string form is a finite decimal number under a
     * hand-rolled character scan (no regex, no {@code Double.parseDouble}). A missing cell folds to
     * {@code ""}, which is not numeric, so {@code is_numeric} stays {@code false} on a blank and
     * the {@code negate} (i.e. {@code not is_numeric}) form fires on it.
     *
     * <p>
     * The accepted grammar is an optional leading {@code -} followed by <b>either</b> one-or-more
     * digits with an optional {@code .}digits fraction (e.g. {@code 0}, {@code -3}, {@code 3.5},
     * {@code 007}) <b>or</b> a leading-dot fraction {@code .}digits (e.g. {@code .5}). A leading
     * {@code +} ({@code +5}) is <b>rejected</b> — the legacy regexes this replaced are all
     * {@code -?} — as are a lone {@code .}, a trailing dot ({@code 1.}), scientific notation
     * ({@code 1e5}), surrounding whitespace ({@code " 1 "}), and the empty string.
     */
    public static BitSet isNumeric(Vector v, int rowCount, boolean negate)
    {
        return scan(v, rowCount, (dv, _) -> isNumericString(
                ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString()) != negate);
    }


    /**
     * Hand-rolled finite-decimal scan backing {@link #isNumeric}: optional leading {@code -} (a
     * leading {@code +} is <b>not</b> accepted — see the inline comment), then either
     * {@code digits[.digits]} or {@code .digits}. Rejects the empty string, a lone / trailing dot,
     * exponents, and any surrounding whitespace or stray character.
     */
    private static boolean isNumericString(String s)
    {
        int n = s.length();
        int i = 0;
        // Only a leading '-' is allowed (the legacy regexes are all `-?`); a leading '+' is
        // rejected, matching the source patterns and avoiding a Double.parseDouble-style widening.
        if (i < n && s.charAt(i) == '-')
        {
            i++;
        }
        int intDigits = 0;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9')
        {
            i++;
            intDigits++;
        }
        int fracDigits = 0;
        if (i < n && s.charAt(i) == '.')
        {
            i++;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9')
            {
                i++;
                fracDigits++;
            }
            // A dot must be followed by at least one fractional digit (rejects "1." and a lone
            // ".").
            if (fracDigits == 0)
            {
                return false;
            }
        }
        // The whole string must be consumed and carry at least one digit overall.
        return i == n && (intDigits > 0 || fracDigits > 0);
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
