package net.cumba.corej.ruletest.cdt.ruletest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.Violation;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Verifies the optional location expectations of a {@link RuleTestScenario}
 * ({@code #expectViolationCount} / {@code #expectViolationAt}) against the violations a rule
 * actually emitted. Mirrors the rulespec suite's set-diff: when {@code #expectViolationAt} lines
 * are present the fired locations must form an <em>exact set</em> match with the declared ones (no
 * missing, no extra).
 *
 * <p>
 * Main-scope on purpose so all three scenario factories (and {@link ScenarioCapture}) can use it.
 * It performs no assertions — it returns a {@link Result} and the caller (a test) decides. The same
 * class hosts {@link #toExpectations} so capture and back-fill emit directives the checker
 * round-trips.
 * </p>
 *
 * <h2>Value comparison</h2>
 * <p>
 * {@code Num} cells are stored as {@code Double} and numeric output variables stringify as
 * {@code "1.0"}, while a scenario author writes {@code AESEQ=1}. A table cell is matched <em>by its
 * runtime type</em> ({@link #valueMatchesCell}): a {@code Double} {@code 1.0} compares numerically
 * (so {@code 1} / {@code 1.0} both match), but a {@code Char} {@code "003"} requires exact string
 * equality and is never numeric-coerced — so subject ids like {@code "003"} only match
 * {@code "003"} (not {@code 3}). Output variables, already engine-stringified, are matched
 * exact-or-numeric ({@link #valueMatchesString}).
 * </p>
 *
 * <h2>&#9940; A {@code $} pin is PAYLOAD-ONLY</h2>
 * <p>
 * A constraint key starting with {@code $} names an <em>operation result</em> the engine projected
 * onto the violation, never a dataset variable. Such a key is therefore resolved <b>only</b> in
 * {@link Violation#getValues()} and <b>never</b> against a table column; when no observed violation
 * carries it, {@link #verify} fails with a message naming the key, rather than silently changing
 * channel.
 * </p>
 * <p>
 * This is not theoretical tidiness. The two lookups differ in <em>both</em> directions:
 * </p>
 * <ul>
 * <li>the payload lookup is a {@code Map} {@code containsKey} — <b>case-SENSITIVE</b>;</li>
 * <li>{@code DataTableMeta.getColumnIndex} is a linear name scan that is <b>case-INSENSITIVE by
 * default</b> ({@code columnNameCaseSensitive} defaults to {@code false}).</li>
 * </ul>
 * <p>
 * So before this rule a census cell transcribed in the wrong case ({@code $DATASET_SIZE} for
 * {@code $dataset_size}) missed the payload and hit the column — measured green by
 * {@code ViolationLocationCheckTest.dollarPin_misCasedKey_failsLoudly_ratherThanChangingChannel}
 * before the fix. And a {@code $}-named <em>column</em> is genuinely declarable in a {@code .cdt}
 * ({@code col $dataset_size type=Num} parses), so the fallthrough arm was reachable, not merely
 * reachable-by-regression: with the payload key gone the check read the row's column of that name
 * and <b>passed</b>.
 * </p>
 *
 * <h2>&#9940; A NAME-ONLY column is never read from the table</h2>
 * <p>
 * {@code PLAN-finding-values-record-level-only} (V1, V8 (b)): a non-record finding lists its raw
 * columns <em>by name without a value</em> ({@link Violation#getNamedColumns()}). The table
 * fallback above would have made that change invisible — a dataset verdict pinned {@code DOMAIN=AE}
 * kept passing against the row-0 cell after the engine stopped printing it (~185 corpus pins,
 * review H2). So a key the observed violation lists by name is <b>never</b> resolved against the
 * table: a {@code COL=value} pin on it fails, {@link #verify} names such pins up front
 * ({@link #verifyNameOnlyKeysAreNotValuePinned}), and the reserved spelling {@code nameOnly=COL}
 * ({@link ExpectedViolation#nameOnly}) is how a scenario asserts the state. {@link #toExpectations}
 * emits it, so the back-fill cannot erase it (the same self-deleting shape as the {@code $} and
 * {@code severity=} pins below).
 * </p>
 * <p>
 * And on such a finding the table is not read for <em>any</em> other column either (P1 review L-3):
 * a violation that lists a column by name is a non-record finding, its row (row 0 of a dataset
 * verdict, a {@code {ROW}} dataset rule's first firing row) is no record of it, so a
 * {@code COL=value} pin the finding does not carry is a non-match —
 * {@link #verifyNoTableFallbackOnNameOnlyFindings} names it. Only the row anchors ({@code USUBJID},
 * {@code --SEQ}) may still locate that row through the table.
 * </p>
 */
public final class ViolationLocationCheck
{

    private ViolationLocationCheck()
    {
    }

    /** Outcome of a location check: {@code pass=true} with empty detail, or a failure message. */
    public record Result(boolean pass, String detail)
    {
    }


    /** Directives derived from observed violations, for capture / back-fill emission. */
    public record Expectations(@Nullable Integer count, List<ExpectedViolation> ats)
    {

        public Expectations
        {
            // Defensive immutable copy: the record must not expose/store a mutable list.
            ats = ats == null ? List.of() : List.copyOf(ats);
        }
    }

    /**
     * Convenience overload reading violations / count / truncation from a
     * {@link RuleExecutionResult}.
     */
    public static Result verify(RuleTestScenario aScenario, RuleExecutionResult aResult,
            IDataTable aPrimary)
    {
        List<Violation> v = aResult.getViolations() != null ? aResult.getViolations() : List.of();
        return verify(aScenario, v, aResult.getViolationCount(), aResult.isTruncated(), aPrimary);
    }


    public static Result verify(RuleTestScenario aScenario, List<Violation> aObserved,
            long aEffectiveCount, boolean aTruncated, IDataTable aPrimary)
    {
        Integer wantCount = aScenario.getExpectViolationCount();
        List<ExpectedViolation> wantAt = aScenario.getExpectedViolations();
        boolean noAt = wantAt == null || wantAt.isEmpty();
        if (wantCount == null && noAt)
        {
            return new Result(true, "");
        }
        String prefix = aScenario.getCoreId() + ": ";
        if (wantCount != null && aEffectiveCount != wantCount)
        {
            return new Result(false, prefix + "expected " + wantCount
                    + " violation(s) but rule fired " + aEffectiveCount);
        }
        if (noAt)
        {
            return new Result(true, "");
        }
        if (aTruncated)
        {
            return new Result(false,
                    prefix + "findings cap truncated the violation list (showing "
                            + aObserved.size() + " of " + aEffectiveCount
                            + "); drop #expectViolationAt or shrink the fixture");
        }
        Result unbackedDollarPin = verifyDollarPinsAreCarried(prefix, wantAt, aObserved);
        if (unbackedDollarPin != null)
        {
            return unbackedDollarPin;
        }
        Result valuePinnedNameOnly = verifyNameOnlyKeysAreNotValuePinned(prefix, wantAt, aObserved);
        if (valuePinnedNameOnly != null)
        {
            return valuePinnedNameOnly;
        }
        Result unbackedNameOnlyPin = verifyNameOnlyPinsAreCarried(prefix, wantAt, aObserved);
        if (unbackedNameOnlyPin != null)
        {
            return unbackedNameOnlyPin;
        }
        Result tableFallbackOnNameOnly = verifyNoTableFallbackOnNameOnlyFindings(prefix, wantAt,
                aObserved);
        if (tableFallbackOnNameOnly != null)
        {
            return tableFallbackOnNameOnly;
        }
        DataTableMeta meta = aPrimary.getMetaData();
        if (aObserved.size() != wantAt.size())
        {
            return diffResult(prefix, wantAt, aObserved, aPrimary, meta);
        }
        boolean[] used = new boolean[aObserved.size()];
        if (!bijection(wantAt, aObserved, aPrimary, meta, used, 0))
        {
            return diffResult(prefix, wantAt, aObserved, aPrimary, meta);
        }
        return new Result(true, "");
    }


    /**
     * Backtracking perfect matching — each expected entry claims one distinct observed violation.
     */
    private static boolean bijection(List<ExpectedViolation> aExpected, List<Violation> aObserved,
            IDataTable aPrimary, DataTableMeta aMeta, boolean[] aUsed, int aIndex)
    {
        if (aIndex == aExpected.size())
        {
            return true;
        }
        for (int j = 0; j < aObserved.size(); j++)
        {
            if (!aUsed[j] && matches(aExpected.get(aIndex), aObserved.get(j), aPrimary, aMeta))
            {
                aUsed[j] = true;
                if (bijection(aExpected, aObserved, aPrimary, aMeta, aUsed, aIndex + 1))
                {
                    return true;
                }
                aUsed[j] = false;
            }
        }
        return false;
    }


    private static boolean matches(ExpectedViolation aExpected, Violation aObserved,
            IDataTable aPrimary, DataTableMeta aMeta)
    {
        if (aExpected.getRow() != null && aExpected.getRow() != aObserved.getRowNumber())
        {
            return false;
        }
        // Plan C phase 2 — the reserved severity= pin. Compared against the level the ENGINE
        // carried out on the violation, which is why the level rides on Violation and is not
        // invented in ValidationReportBuilder: this checker never sees a ValidationFinding.
        if (aExpected.getSeverity() != null && aExpected.getSeverity() != aObserved.getLevel())
        {
            return false;
        }
        Map<String, String> out = aObserved.getValues() != null ? aObserved.getValues() : Map.of();
        List<String> named = aObserved.getNamedColumns();
        for (String name : aExpected.getNameOnly())
        {
            if (!named.contains(name))
            {
                return false;
            }
        }
        for (Map.Entry<String, String> c : aExpected.getConstraints().entrySet())
        {
            boolean ok;
            if (named.contains(c.getKey()))
            {
                // ⛔ A name-only column has NO value on this finding: a value pin on it — the
                // empty spelling `COL=` included — is a non-match, and the table is never
                // consulted. Pin it `nameOnly=COL`.
                ok = false;
            }
            else if (out.containsKey(c.getKey()))
            {
                // Output variables first: per-domain rules (variable-metadata, …) are pinned by
                // their projected keys, whose Violation.row is a column index — never read the
                // table
                // for those. Output vars are already engine-stringified: exact, or numeric for a
                // numeric value.
                ok = valueMatchesString(c.getValue(), out.get(c.getKey()));
            }
            else if (isDollarPin(c.getKey()))
            {
                // ⛔ PAYLOAD-ONLY (see the class javadoc): a $ key names an operation result, so
                // an absent one is a NON-MATCH — never a table lookup. #verify's pre-check has
                // already turned "no observed violation carries it at all" into a named failure;
                // reaching here means some OTHER violation carries it, so failing this candidate
                // and letting the bijection try the rest is the correct, per-violation answer.
                ok = false;
            }
            else if (!named.isEmpty() && !isRowAnchor(c.getKey()))
            {
                // ⛔ P1 review L-3: a finding that lists columns by name is a NON-RECORD finding
                // (V1), so the table cell at its row is no fact about it — a value pin resolved
                // there passes on a fixture's row, not on the finding (CDISC-AD0661's
                // DOSEDURU=HOURS did). Only the row anchors (USUBJID, --SEQ) may still locate
                // the firing row of a Dataset {ROW} finding through the table.
                ok = false;
            }
            else
            {
                // Table cell: compare by the cell's runtime type so a Char "002" only matches the
                // string "002" (never numerically), while a Num 1.0 still matches "1".
                // ⛔ Relied on: a non-$ key absent from the payload resolves from the column of
                // that name. Pinned by
                // ViolationLocationCheckTest.nonDollarPin_absentFromPayload_stillFallsBackToTheTableCell.
                ok = valueMatchesCell(c.getValue(),
                        tableCell(aPrimary, aMeta, aObserved.getRow(), c.getKey()))
                        || matchesMissingMarker(c.getValue(), aPrimary, aMeta, aObserved.getRow(),
                                c.getKey());
            }
            if (!ok)
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Whether the table cell is a genuine missing whose marker ({@code "."}, {@code ".A"}, …) is
     * exactly {@code aWant}. Since {@code PLAN-member-set-identity-hardening} the engine reports a
     * missing identity cell ({@code USUBJID} / {@code SEQ}) as its marker, so a back-filled pin for
     * such a row carries the marker; {@link #tableCell} folds a missing to {@code null}, which
     * matches only an empty pin. Both spellings therefore match a missing cell — the empty one
     * every existing fixture uses, and the marker the engine now prints.
     */
    private static boolean matchesMissingMarker(String aWant, IDataTable aTable,
            DataTableMeta aMeta, long aRow, String aCol)
    {
        int idx = aMeta.getColumnIndex(aCol);
        if (idx < 0 || aRow < 0 || aRow >= aTable.getRowCount())
        {
            return false;
        }
        IDataValue dv = aTable.getDataValue(aRow, idx);
        if (dv == null)
        {
            return false;
        }
        net.cumba.datatable.values.MissingValue m = net.cumba.corej.core.expr.eval.TypedValue
                .missingIdentityOf(dv);
        return m != null && m.toString().equals(aWant);
    }


    /** A constraint key naming an engine operation result rather than a dataset variable. */
    private static boolean isDollarPin(String aKey)
    {
        return aKey.startsWith("$");
    }


    /**
     * A row-anchor key — {@code USUBJID} or a {@code --SEQ} column — which may locate a finding's
     * row through the table even on a finding that lists columns by name (P1 review L-3).
     */
    static boolean isRowAnchor(String aKey)
    {
        return "USUBJID".equalsIgnoreCase(aKey) || aKey.toUpperCase(Locale.ROOT).endsWith("SEQ");
    }


    /**
     * Fail loudly when a {@code $} pin is backed by <em>no</em> observed violation's payload.
     *
     * <p>
     * Without this the pin degraded into a lookup of a column of the same name — and on a fixture
     * that happens to have one, into a silent pass. It is the same self-disarming shape as the
     * unreserved {@code severity=} key: the weakened expectation keeps the fixture green, so the
     * loss is invisible. Returns {@code null} when every {@code $} pin is carried (or none is
     * declared).
     * </p>
     */
    private static @Nullable Result verifyDollarPinsAreCarried(String aPrefix,
            List<ExpectedViolation> aExpected, List<Violation> aObserved)
    {
        Set<String> pinned = new LinkedHashSet<>();
        for (ExpectedViolation e : aExpected)
        {
            for (String key : e.getConstraints().keySet())
            {
                if (isDollarPin(key))
                {
                    pinned.add(key);
                }
            }
        }
        if (pinned.isEmpty())
        {
            return null;
        }
        Set<String> carried = new TreeSet<>();
        for (Violation v : aObserved)
        {
            if (v.getValues() != null)
            {
                carried.addAll(v.getValues().keySet());
            }
        }
        List<String> absent = new ArrayList<>();
        for (String key : pinned)
        {
            if (!carried.contains(key))
            {
                absent.add(key);
            }
        }
        if (absent.isEmpty())
        {
            return null;
        }
        return new Result(false, aPrefix + "#expectViolationAt pins " + absent
                + " which NO observed violation carries — a $ key is payload-only and is never"
                + " resolved against a table column of that name. Either the engine stopped"
                + " projecting the operation, or the key is mis-spelled / mis-cased (the payload"
                + " lookup is case-sensitive). Keys actually projected: " + carried);
    }


    /**
     * Fail loudly, naming the pins, when a {@code COL=value} constraint names a column that
     * <em>every</em> observed violation lists by name without a value: the pin cannot match (a
     * name-only column has no value, and the table is never read for it), and the message says what
     * the fixture must say instead. The per-violation non-match in {@link #matches} stays the
     * authority; this names the cause instead of a bare set diff.
     */
    private static @Nullable Result verifyNameOnlyKeysAreNotValuePinned(String aPrefix,
            List<ExpectedViolation> aExpected, List<Violation> aObserved)
    {
        if (aObserved.isEmpty())
        {
            return null;
        }
        Set<String> namedOnEvery = null;
        for (Violation v : aObserved)
        {
            Set<String> named = new TreeSet<>(v.getNamedColumns());
            if (namedOnEvery == null)
            {
                namedOnEvery = named;
            }
            else
            {
                namedOnEvery.retainAll(named);
            }
        }
        if (namedOnEvery == null || namedOnEvery.isEmpty())
        {
            return null;
        }
        Set<String> offending = new TreeSet<>();
        for (ExpectedViolation e : aExpected)
        {
            for (String key : e.getConstraints().keySet())
            {
                if (namedOnEvery.contains(key))
                {
                    offending.add(key);
                }
            }
        }
        if (offending.isEmpty())
        {
            return null;
        }
        return new Result(false, aPrefix + "#expectViolationAt pins a VALUE for " + offending
                + ", which every observed violation lists by name without a value — a raw"
                + " column on a non-record finding prints no value"
                + " (PLAN-finding-values-record-level-only V1) and is never read from the table"
                + " cell; pin it as nameOnly=COL. Name-only on every violation: " + namedOnEvery);
    }


    /**
     * Fail loudly when a {@code nameOnly=COL} pin is backed by <em>no</em> observed violation's
     * name-only list — the engine prints the column's value again, or omits it (the column is
     * absent), or the name is mis-spelled.
     */
    private static @Nullable Result verifyNameOnlyPinsAreCarried(String aPrefix,
            List<ExpectedViolation> aExpected, List<Violation> aObserved)
    {
        Set<String> pinned = new LinkedHashSet<>();
        for (ExpectedViolation e : aExpected)
        {
            pinned.addAll(e.getNameOnly());
        }
        if (pinned.isEmpty())
        {
            return null;
        }
        Set<String> listed = new TreeSet<>();
        Set<String> valued = new TreeSet<>();
        for (Violation v : aObserved)
        {
            listed.addAll(v.getNamedColumns());
            if (v.getValues() != null)
            {
                valued.addAll(v.getValues().keySet());
            }
        }
        List<String> absent = new ArrayList<>();
        for (String key : pinned)
        {
            if (!listed.contains(key))
            {
                absent.add(key);
            }
        }
        if (absent.isEmpty())
        {
            return null;
        }
        return new Result(false, aPrefix + "#expectViolationAt pins " + absent
                + " as nameOnly=, which NO observed violation lists by name — the engine prints"
                + " a value for it (then pin COL=value), or omits it (an absent column is in"
                + " neither channel). Listed by name: " + listed + "; valued: " + valued);
    }


    /**
     * Fail loudly, naming the pins, when every observed violation lists columns by name (a
     * non-record finding, V1) and a {@code COL=value} pin names a key that no observed violation
     * carries a value for and that is no row anchor ({@link #isRowAnchor}): the only place such a
     * pin could match is the table cell at the finding's row, which is no fact about a non-record
     * finding (P1 review L-3). The per-violation non-match in {@link #matches} stays the authority;
     * this names the cause.
     */
    private static @Nullable Result verifyNoTableFallbackOnNameOnlyFindings(String aPrefix,
            List<ExpectedViolation> aExpected, List<Violation> aObserved)
    {
        if (aObserved.isEmpty())
        {
            return null;
        }
        Set<String> valued = new TreeSet<>();
        for (Violation v : aObserved)
        {
            if (v.getNamedColumns().isEmpty())
            {
                return null;
            }
            if (v.getValues() != null)
            {
                valued.addAll(v.getValues().keySet());
            }
        }
        Set<String> offending = new TreeSet<>();
        for (ExpectedViolation e : aExpected)
        {
            for (String key : e.getConstraints().keySet())
            {
                if (!valued.contains(key) && !isDollarPin(key) && !isRowAnchor(key))
                {
                    offending.add(key);
                }
            }
        }
        if (offending.isEmpty())
        {
            return null;
        }
        return new Result(false, aPrefix + "#expectViolationAt pins a VALUE for " + offending
                + ", which no observed violation carries — the pin could only match the table"
                + " cell at the finding's row, and every observed violation lists columns by name"
                + " (a non-record finding, PLAN-finding-values-record-level-only V1), whose row is"
                + " no record of it. Drop the pin (say it in #note), or pin nameOnly=COL if the"
                + " finding lists it. Valued: " + valued);
    }


    private static @Nullable Object tableCell(IDataTable aTable, DataTableMeta aMeta, long aRow,
            String aCol)
    {
        int idx = aMeta.getColumnIndex(aCol);
        if (idx < 0 || aRow < 0 || aRow >= aTable.getRowCount())
        {
            return null;
        }
        IDataValue dv = aTable.getDataValue(aRow, idx);
        return (dv == null || dv.isMissingOrInvalid()) ? null : dv.getValue();
    }

    // ---- Value canonicalisation ----------------------------------------------------


    /** Canonical string for an observed value, by runtime type; empty string and missing → null. */
    static @Nullable String canon(@Nullable Object aRaw)
    {
        if (aRaw == null)
        {
            return null;
        }
        if (aRaw instanceof Double d)
        {
            return canonDouble(d);
        }
        if (aRaw instanceof Float f)
        {
            return canonDouble(f);
        }
        String s = String.valueOf(aRaw);
        return s.isEmpty() ? null : s;
    }


    private static @Nullable String canonDouble(double aValue)
    {
        if (Double.isNaN(aValue) || Double.isInfinite(aValue))
        {
            return null;
        }
        double r = Math.round(aValue * 1.0e10) / 1.0e10; // parity-consistent rounding
        if (Double.compare(r, Math.rint(r)) == 0) // integral — compare ints, not floats
        {
            return Long.toString((long) r);
        }
        return BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }


    /**
     * Match a directive value against a table cell, <em>by the cell's runtime type</em>. A numeric
     * ({@code Double}/{@code Float}) cell compares numerically (so {@code 1} / {@code 1.0} match a
     * stored {@code 1.0}); any other cell (e.g. a {@code Char} id like {@code "002"}) requires
     * exact string equality, so an all-digit id is never coerced to a number. Empty directive value
     * and a missing cell both compare as {@code null}.
     */
    private static boolean valueMatchesCell(String aWant, @Nullable Object aRaw)
    {
        if (aRaw instanceof Double || aRaw instanceof Float)
        {
            // Guarded above to Double or Float, so the Number view is exact for both.
            double d = ((Number) aRaw).doubleValue();
            String have = canonDouble(d);
            Double dw = tryDouble(aWant);
            return dw != null && have != null && have.equals(canonDouble(dw));
        }
        return Objects.equals(emptyToNull(aWant), canon(aRaw));
    }


    /**
     * Match a directive value against an engine-stringified output variable: exact string equality,
     * or numeric equality when both parse as a number (so {@code 1} / {@code 1.0} match a numeric
     * output var rendered {@code "1.0"}). Empty directive value compares as {@code null}.
     */
    private static boolean valueMatchesString(String aWant, @Nullable String aHave)
    {
        String want = emptyToNull(aWant);
        String have = canon(aHave);
        if (Objects.equals(want, have))
        {
            return true;
        }
        if (want == null || have == null)
        {
            return false;
        }
        Double dw = tryDouble(want);
        Double dh = tryDouble(have);
        return dw != null && dh != null && Objects.equals(canonDouble(dw), canonDouble(dh));
    }


    private static @Nullable String emptyToNull(String aValue)
    {
        return aValue.isEmpty() ? null : aValue;
    }


    private static @Nullable Double tryDouble(String aValue)
    {
        try
        {
            return Double.valueOf(aValue);
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }

    // ---- Diff rendering ------------------------------------------------------------


    private static Result diffResult(String aPrefix, List<ExpectedViolation> aExpected,
            List<Violation> aObserved, IDataTable aPrimary, DataTableMeta aMeta)
    {
        // Greedy claim of observed by expected so the leftovers are the true diff.
        boolean[] used = new boolean[aObserved.size()];
        List<String> missing = new ArrayList<>();
        for (ExpectedViolation e : aExpected)
        {
            int hit = -1;
            for (int j = 0; j < aObserved.size(); j++)
            {
                if (!used[j] && matches(e, aObserved.get(j), aPrimary, aMeta))
                {
                    hit = j;
                    break;
                }
            }
            if (hit >= 0)
            {
                used[hit] = true;
            }
            else
            {
                missing.add(describeExpected(e));
            }
        }
        List<String> unexpected = new ArrayList<>();
        for (int j = 0; j < aObserved.size(); j++)
        {
            if (!used[j])
            {
                unexpected.add(describeObserved(aObserved.get(j)));
            }
        }
        return new Result(false, aPrefix + "violation locations differ — missing " + missing
                + ", unexpected " + unexpected);
    }


    private static String describeExpected(ExpectedViolation aExpected)
    {
        StringBuilder sb = new StringBuilder();
        if (aExpected.getRow() != null)
        {
            sb.append("row=").append(aExpected.getRow());
        }
        if (aExpected.getSeverity() != null)
        {
            sb.append(sb.length() == 0 ? "" : " ").append("severity=")
                    .append(aExpected.getSeverity());
        }
        if (!aExpected.getNameOnly().isEmpty())
        {
            sb.append(sb.length() == 0 ? "" : " ").append("nameOnly=")
                    .append(new TreeSet<>(aExpected.getNameOnly()));
        }
        if (!aExpected.getConstraints().isEmpty())
        {
            sb.append(new TreeMap<>(aExpected.getConstraints()));
        }
        return sb.length() == 0 ? "{}" : sb.toString();
    }


    private static String describeObserved(Violation aViolation)
    {
        Map<String, String> vars = new TreeMap<>();
        if (aViolation.getValues() != null)
        {
            for (Map.Entry<String, String> e : aViolation.getValues().entrySet())
            {
                vars.put(e.getKey(), canon(e.getValue()) == null ? "" : canon(e.getValue()));
            }
        }
        return "row=" + aViolation.getRowNumber()
                + (aViolation.getLevel() != null ? " severity=" + aViolation.getLevel() : "")
                + (aViolation.getNamedColumns().isEmpty() ? ""
                        : " nameOnly=" + new TreeSet<>(aViolation.getNamedColumns()))
                + " vars=" + vars;
    }

    // ---- Emission (capture / back-fill) --------------------------------------------


    /**
     * Derive the location directives for a set of observed violations.
     *
     * <ul>
     * <li>{@code count} is always the effective violation count.</li>
     * <li>a cap-truncated result yields count only (the checker refuses exact location match when
     * the materialised list is incomplete).</li>
     * <li>value-based (record-level) rule → one {@code row=} entry per violation, plus
     * {@code USUBJID} / {@code <DOMAIN>SEQ} identity pins when present, plus any {@code $}-prefixed
     * operation results the violation carries.</li>
     * <li>non-value-based (per-domain) rule → pin by the projected output variables (e.g.
     * {@code variable_name=…}), {@code $}-prefixed operation results included.</li>
     * <li>If the per-violation entries cannot enumerate the full count (some violation yielded no
     * pin), fall back to count-only so the emitted directives stay self-consistent (the parser
     * rejects a count that disagrees with the {@code #expectViolationAt} line count).</li>
     * <li>the reserved {@code severity=} pin is emitted from the level the engine <em>claimed</em>
     * the violation at, when it resolved one.</li>
     * </ul>
     *
     * <p>
     * &#9940;&#9940; <b>Why the severity is stamped here.</b> {@code ScenarioLocationBackfill}
     * <em>strips every existing</em> {@code #expectViolationAt} line and re-emits from these
     * expectations, so anything this method does not carry is <b>erased from the fixture</b>. Left
     * unstamped, one opt-in {@code -Dbackfill.locations=true} run would have silently deleted every
     * {@code severity=} pin in the corpus — and <b>nothing would have gone red</b>, because a
     * {@code null} expected severity means "not pinned" to {@link #matches}, so the weakened
     * fixtures keep passing. That is a pin that deletes itself. Pinned by
     * {@code ScenarioLocationBackfillTest.backfillRoundTripsTheSeverityPinAndTheRunLevel} and
     * {@code ScenarioLocationBackfillTest.backfillInventsNoSeverityForAnUnstampedViolation}.
     * </p>
     *
     * <p>
     * &#9873; A single-level rule's violations carry {@code null} here (the producing sites stamp
     * no level; the report builder falls back to the rule's effective severity), so nothing is
     * emitted for them and the ~8 800 single-level fixtures are byte-unaffected.
     * </p>
     *
     * <p>
     * &#9940;&#9940; <b>Why {@code $} pins are carried, on BOTH branches.</b> The identical
     * self-deleting shape, found once more: this method used to skip every {@code $}-prefixed key
     * ("verbose and order-unstable"), and the value-based branch never consulted the payload at all
     * &mdash; so one {@code -Dbackfill.locations=true} run erased all <b>23</b> {@code $} pins in
     * the corpus (16 of them on the value-based branch, i.e. carrying {@code row=}), and nothing
     * went red, because the erased pin degraded into a table lookup that a fixture with a
     * same-named column satisfied. Skipping them is not a cosmetic preference; it is a pin that
     * deletes itself. Pinned by {@code ScenarioLocationBackfillTest.backfillRoundTripsADollarPin}
     * and the two {@code toExpectations_*carriesDollarPins} tests.
     * </p>
     * <p>
     * &#9873; The trade-off is accepted, not overlooked. A {@code $} result that stringifies
     * order-unstably now yields a pin that <em>fails on the next run</em> &mdash; loud, and the
     * right direction; the alternative was a pin that vanished in silence. Verbosity is likewise
     * accepted: a captured operation dump is data the checker can verify, unlike the {@code row=}
     * pin that cannot recover it. The value-based branch deliberately carries <em>only</em>
     * {@code $} keys from the payload: every other output variable is recoverable from the table at
     * the pinned row, so omitting those loses nothing and keeps the record-level fixtures
     * byte-stable.
     * </p>
     * <p>
     * &#9888; A {@code $} result whose value is blank is still not emitted ({@link #notBlank})
     * &mdash; an empty pin would match vacuously, see {@link #valueMatchesCell}. No corpus scenario
     * pins an empty value today.
     * </p>
     * <p>
     * &#9733; The columns a violation lists <b>by name without a value</b>
     * ({@link Violation#getNamedColumns()}) are emitted as {@code nameOnly=COL} on BOTH branches
     * &mdash; the third pin that cannot be recovered from the table, and the third that the
     * back-fill would otherwise erase in silence.
     * </p>
     */
    public static Expectations toExpectations(List<Violation> aObserved, long aTotalCount,
            boolean aTruncated, boolean aValueBased, String aDomain)
    {
        int count = aTotalCount >= 0 ? (int) Math.min(aTotalCount, Integer.MAX_VALUE)
                : aObserved.size();
        if (aTruncated)
        {
            return new Expectations(count, List.of());
        }
        List<ExpectedViolation> ats = new ArrayList<>();
        for (Violation v : aObserved)
        {
            Map<String, String> pins = new LinkedHashMap<>();
            Integer row = null;
            if (aValueBased)
            {
                row = (int) v.getRowNumber();
                if (notBlank(v.getUsubjid()))
                {
                    pins.put("USUBJID", v.getUsubjid());
                }
                if (notBlank(v.getSeq()))
                {
                    pins.put(aDomain + "SEQ", v.getSeq());
                }
                // ⛔ Only a $ key, and on this branch too: row= / USUBJID / SEQ are all
                // table-derived, so an operation result is the one pin that cannot be recovered
                // from the fixture — carry it or the back-fill deletes it.
                if (v.getValues() != null)
                {
                    for (Map.Entry<String, String> e : v.getValues().entrySet())
                    {
                        if (isDollarPin(e.getKey()) && notBlank(e.getValue()))
                        {
                            pins.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
            else if (v.getValues() != null)
            {
                for (Map.Entry<String, String> e : v.getValues().entrySet())
                {
                    if (notBlank(e.getValue()))
                    {
                        pins.put(e.getKey(), e.getValue());
                    }
                }
                if (pins.isEmpty() && v.getNamedColumns().isEmpty())
                {
                    continue;
                }
            }
            else
            {
                continue;
            }
            ats.add(ExpectedViolation.builder().row(row).constraints(pins).severity(v.getLevel())
                    .nameOnly(v.getNamedColumns()).build());
        }
        if (ats.size() != count)
        {
            // Could not enumerate every violation as an at-line — keep the count, drop the
            // locations.
            return new Expectations(count, List.of());
        }
        ats.sort(Comparator
                .comparingInt((ExpectedViolation e) -> e.getRow() == null ? -1 : e.getRow())
                .thenComparing(e -> new TreeMap<>(e.getConstraints()).toString())
                .thenComparing(e -> e.getNameOnly().toString()));
        return new Expectations(count, ats);
    }


    private static boolean notBlank(@Nullable String aValue)
    {
        return aValue != null && !aValue.isEmpty();
    }
}
