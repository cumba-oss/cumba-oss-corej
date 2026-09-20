package net.cumba.corej.ruletest.cdt.ruletest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        if (wantCount != null && aEffectiveCount != wantCount.longValue())
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
        if (aExpected.getRow() != null
                && aExpected.getRow().longValue() != aObserved.getRowNumber())
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
        for (Map.Entry<String, String> c : aExpected.getConstraints().entrySet())
        {
            boolean ok;
            if (out.containsKey(c.getKey()))
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
            else
            {
                // Table cell: compare by the cell's runtime type so a Char "002" only matches the
                // string "002" (never numerically), while a Num 1.0 still matches "1".
                // ⛔ Relied on: a non-$ key absent from the payload resolves from the column of
                // that name. Pinned by
                // ViolationLocationCheckTest.nonDollarPin_absentFromPayload_stillFallsBackToTheTableCell.
                ok = valueMatchesCell(c.getValue(),
                        tableCell(aPrimary, aMeta, aObserved.getRow(), c.getKey()));
            }
            if (!ok)
            {
                return false;
            }
        }
        return true;
    }


    /** A constraint key naming an engine operation result rather than a dataset variable. */
    private static boolean isDollarPin(String aKey)
    {
        return aKey.startsWith("$");
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
            return canonDouble(f.doubleValue());
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
            double d = aRaw instanceof Float f ? f.doubleValue() : (Double) aRaw;
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
                if (pins.isEmpty())
                {
                    continue;
                }
            }
            else
            {
                continue;
            }
            ats.add(ExpectedViolation.builder().row(row).constraints(pins).severity(v.getLevel())
                    .build());
        }
        if (ats.size() != count)
        {
            // Could not enumerate every violation as an at-line — keep the count, drop the
            // locations.
            return new Expectations(count, List.of());
        }
        ats.sort(Comparator
                .comparingInt((ExpectedViolation e) -> e.getRow() == null ? -1 : e.getRow())
                .thenComparing(e -> new TreeMap<>(e.getConstraints()).toString()));
        return new Expectations(count, ats);
    }


    private static boolean notBlank(@Nullable String aValue)
    {
        return aValue != null && !aValue.isEmpty();
    }
}
