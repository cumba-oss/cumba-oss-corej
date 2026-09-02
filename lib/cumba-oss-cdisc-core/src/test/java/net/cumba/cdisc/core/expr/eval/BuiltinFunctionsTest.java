package net.cumba.cdisc.core.expr.eval;

import static net.cumba.cdisc.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 2 — verifies the built-in {@link net.cumba.cdisc.core.expr.eval.spi.BuiltinFunctions}
 * resolve through the {@link FunctionRegistry} and compute correctly, including the
 * calendar-validating date family that intentionally diverges from the legacy structural checks.
 */
@ExtendWith(MockitoExtension.class)
class BuiltinFunctionsTest
{

    private static Vector value(String name, int rowCount, Vector... args)
    {
        Object out = FunctionRegistry.resolve(name, args.length).apply(EvalRun.ofRowCount(rowCount),
                List.of(args));
        return (Vector) out;
    }


    private static BitSet bool(String name, int rowCount, Vector... args)
    {
        Object out = FunctionRegistry.resolve(name, args.length).apply(EvalRun.ofRowCount(rowCount),
                List.of(args));
        return (BitSet) out;
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /** Resolves a function with a context-bound run so the function can read the table. */
    private static Vector valueOn(String name, IDataTable t, int rowCount, Vector... args)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        Object out = FunctionRegistry.resolve(name, args.length)
                .apply(new EvalRun(ctx, 0, rowCount), List.of(args));
        return (Vector) out;
    }


    @Test
    void lowerUpper()
    {
        // upper("")="" and upper(«missing»)="": a genuine missing folds to "" (function-examples.md
        // "Case & whitespace"). Row 2 is "", row 3 is a genuine null.
        IDataTable t = MockTable.of().col("X", "AbC", "xyz", "", (String) null).build();
        Vector lo = value("lower", 4, col(t, "X"));
        assertEquals("abc", lo.asString(0));
        // The result VALUE is the literal "" (asString == ""); note Vector.isMissing is true for
        // any "" cell by the F3 convention, so we assert the value, not the missing flag.
        assertEquals("", lo.asString(2)); // "" -> "" (literal, no longer collapsed to a value)
        assertEquals("", lo.asString(3)); // «missing» -> ""
        Vector up = value("upcase", 4, col(t, "X")); // alias
        assertEquals("XYZ", up.asString(1));
        assertEquals("", up.asString(2)); // upper("") -> ""
        assertEquals("", up.asString(3)); // upper(«missing») -> ""
    }


    @Test
    void caseFoldOverACollectionFoldsElementWiseAndStaysACollection()
    {
        // EC-28(a) / D7 (Fix #131). The case-insensitive contains twins lower to
        // `contains(upper(ref), upper(lit))` (CheckToExpr:276-279), so if upper() flattened a
        // collection to its toString() the membership branch in Primitives could never see it and
        // `contains_case_insensitive` over a $-list would silently stay a substring probe on the
        // rendered list — the exact defect EC-28 fixes for the case-SENSITIVE pair.
        Vector sets = new ComputedVector(2, net.cumba.datatable.values.DataValueType.STRING,
                row -> row == 0 ? List.of("yes", "n") : List.of("Y"));

        Vector up = value("upper", 2, sets);

        assertEquals(List.of("YES", "N"), up.resolvedObject(0));
        assertEquals(List.of("Y"), up.resolvedObject(1));

        // And the whole point: the folded collection reaches exact membership, so "Y" does NOT
        // match the element "YES" the way a substring probe on "[YES, N]" would have.
        assertEquals(bits(1), Primitives.contains(up, "Y", 2, false));
        assertEquals(bits(0), Primitives.contains(up, "Y", 2, true)); // does_not_contain
    }


    @Test
    void caseFoldOverACollectionRendersNullElementsAsEmpty()
    {
        Vector sets = new ComputedVector(1, net.cumba.datatable.values.DataValueType.STRING,
                _ -> java.util.Arrays.asList("y", null));

        Vector lo = value("lower", 1, sets);

        assertEquals(java.util.Arrays.asList("y", ""), lo.resolvedObject(0));
    }


    @Test
    void len()
    {
        // len("")=0, len(«missing»)=0 (function-examples.md "Length — len / length").
        IDataTable t = MockTable.of().col("X", "abcd", "", "z", (String) null).build();
        Vector l = value("length", 4, col(t, "X")); // alias of len
        assertEquals(4.0, l.asDouble(0));
        assertFalse(l.isMissing(1));
        assertEquals(0.0, l.asDouble(1)); // "" -> length 0
        assertEquals(1.0, l.asDouble(2));
        assertFalse(l.isMissing(3));
        assertEquals(0.0, l.asDouble(3)); // «missing» -> length 0
    }


    @Test
    void presence()
    {
        IDataTable t = MockTable.of().col("X", "v", "", (String) null).build();
        assertEquals(bits(1, 2), bool("empty", 3, col(t, "X")));
        assertEquals(bits(0), bool("non_empty", 3, col(t, "X")));
        assertEquals(bits(1, 2), bool("is_missing", 3, col(t, "X"))); // alias
    }


    @Test
    void substringFamily()
    {
        IDataTable t = MockTable.of().col("X", "HELLO", "WORLD", "").build();
        assertEquals(bits(0), bool("contains", 3, col(t, "X"), ConstVector.of("ELL")));
        // Empty-string literal fix (A.2): does_not_contain "ELL" now fires on the "" row (2) too.
        assertEquals(bits(1, 2), bool("does_not_contain", 3, col(t, "X"), ConstVector.of("ELL")));
        assertEquals(bits(0), bool("starts_with", 3, col(t, "X"), ConstVector.of("HE")));
        assertEquals(bits(0), bool("ends_with", 3, col(t, "X"), ConstVector.of("LO")));
    }


    @Test
    void substringFamilyColumnNeedle()
    {
        // Phase 1 — the substring predicates accept a per-row column needle (arg1), firing per row
        // where the LHS contains/starts-with/ends-with the row's own NEEDLE value.
        IDataTable t = MockTable.of().col("X", "HELLO", "WORLD", "ABCDE")
                .col("NEEDLE", "ELL", "XYZ", "ABC").build();
        // contains: row 0 "HELLO".contains("ELL") = true; row 1 "WORLD".contains("XYZ") = false;
        // row 2 "ABCDE".contains("ABC") = true.
        assertEquals(bits(0, 2), bool("contains", 3, col(t, "X"), col(t, "NEEDLE")));
        // does_not_contain is the polarity inverse of contains (decision #1 / #5).
        assertEquals(bits(1), bool("does_not_contain", 3, col(t, "X"), col(t, "NEEDLE")));
        // starts_with: row 2 "ABCDE".startsWith("ABC") = true; the others false.
        assertEquals(bits(2), bool("starts_with", 3, col(t, "X"), col(t, "NEEDLE")));
        // ends_with: none of the cells end with their row's needle.
        assertEquals(bits(), bool("ends_with", 3, col(t, "X"), col(t, "NEEDLE")));
    }


    @Test
    void substringFamilyMissingNeedleFoldsToEmpty()
    {
        // Decision #1 — a missing/null needle cell folds to "", so s.contains("")/startsWith("")/
        // endsWith("") fire on every row, while does_not_contain "" never fires.
        IDataTable t = MockTable.of().col("X", "HELLO", "WORLD")
                .col("NEEDLE", (String) null, (String) null).build();
        assertEquals(bits(0, 1), bool("contains", 2, col(t, "X"), col(t, "NEEDLE")));
        assertEquals(bits(0, 1), bool("starts_with", 2, col(t, "X"), col(t, "NEEDLE")));
        assertEquals(bits(0, 1), bool("ends_with", 2, col(t, "X"), col(t, "NEEDLE")));
        assertEquals(bits(), bool("does_not_contain", 2, col(t, "X"), col(t, "NEEDLE")));
    }


    @Test
    void substringFamilyEmptyLiteralMatchesMissingNeedle()
    {
        // An explicit "" literal needle behaves exactly like a missing needle cell (decision #1).
        IDataTable t = MockTable.of().col("X", "HELLO", "WORLD").build();
        assertEquals(bits(0, 1), bool("contains", 2, col(t, "X"), ConstVector.of("")));
        assertEquals(bits(0, 1), bool("starts_with", 2, col(t, "X"), ConstVector.of("")));
        assertEquals(bits(0, 1), bool("ends_with", 2, col(t, "X"), ConstVector.of("")));
        assertEquals(bits(), bool("does_not_contain", 2, col(t, "X"), ConstVector.of("")));
    }


    @Test
    void equalsIgnoreCaseFn()
    {
        IDataTable t = MockTable.of().col("X", "abc", "ABC", "xyz").build();
        assertEquals(bits(0, 1), bool("equalsIgnoreCase", 3, col(t, "X"), ConstVector.of("ABC")));
    }


    @Test
    void affixMatches()
    {
        IDataTable t = MockTable.of().col("X", "AB12", "9999", "").build();
        // anchored full-match over whole operand (no affix length in expression surface)
        assertEquals(bits(0),
                bool("prefix_matches", 3, col(t, "X"), ConstVector.of("[A-Z]{2}\\d{2}")));
        assertEquals(bits(1), bool("suffix_matches", 3, col(t, "X"), ConstVector.of("\\d{4}")));
    }


    @Test
    void affixMatchesLengthBounded()
    {
        // prefix_matches(x, /re/, n): anchored match of the FIRST n characters (legacy
        // prefix_matches_regex with a prefix length). "FAKE" → prefix 2 = "FA" matches (AP|FA);
        // "AP01" → "AP" matches; "XFA1" → "XF" does not. A value shorter than n uses the whole
        // string ("F" vs (AP|FA) → no match). Missing never fires (negate=false).
        IDataTable t = MockTable.of().col("X", "FAKE", "AP01", "XFA1", "F", "").build();
        assertEquals(bits(0, 1), bool("prefix_matches", 5, col(t, "X"), ConstVector.of("(AP|FA)"),
                ConstVector.of(2.0)));
        // suffix_matches(x, /re/, n): anchored match of the LAST n characters. "IDSEQ" → "SEQ"
        // matches; "IDSEX" → "SEX" does not; "EQ" (shorter than 3) → whole string, no match.
        IDataTable s = MockTable.of().col("Y", "IDSEQ", "IDSEX", "EQ", "").build();
        assertEquals(bits(0),
                bool("suffix_matches", 4, col(s, "Y"), ConstVector.of("SEQ"), ConstVector.of(3.0)));
    }


    @Test
    void affixMatchesPerRowLength()
    {
        // Phase 2b: the arity-3 affix length is read PER ROW (was row-0 only). A per-row column
        // window must produce a different prefix per row. "ABCD" → prefix 1 = "A" matches /A+/;
        // "ABCD" with prefix 4 = "ABCD" does NOT match /A+/ (has BCD); a numeric column carries
        // the per-row length 1, 4, 1 → rows 0 and 2 fire, row 1 does not.
        IDataTable t = MockTable.of().col("X", "ABCD", "ABCD", "ABCD").colLong("N", 1L, 4L, 1L)
                .build();
        assertEquals(bits(0, 2),
                bool("prefix_matches", 3, col(t, "X"), ConstVector.of("A+"), col(t, "N")));
    }


    @Test
    void affixMatchesLengthOperandShapesParity()
    {
        // All four length operand shapes are parity with the numeric literal 2.0: a numeric column,
        // a CHAR column whose cells parse to "2", a "2" string literal, and the numeric literal.
        IDataTable t = MockTable.of().col("X", "FAKE", "AP01", "XFA1", "F", "").build();
        BitSet expected = bits(0, 1); // prefix 2: "FA"/"AP" match (AP|FA); "XF" no; "F"/"" whole
        Vector x = col(t, "X");
        String re = "(AP|FA)";
        // (a) numeric literal 2.0 — the reference
        assertEquals(expected,
                bool("prefix_matches", 5, x, ConstVector.of(re), ConstVector.of(2.0)),
                "numeric literal length 2");
        // (b) numeric column holding 2 on every row
        IDataTable n = MockTable.of().col("X", "FAKE", "AP01", "XFA1", "F", "")
                .colLong("LEN", 2L, 2L, 2L, 2L, 2L).build();
        assertEquals(expected,
                bool("prefix_matches", 5, col(n, "X"), ConstVector.of(re), col(n, "LEN")),
                "per-row numeric column length 2");
        // (c) CHAR column holding the text "2" — parses via asDouble
        IDataTable c = MockTable.of().col("X", "FAKE", "AP01", "XFA1", "F", "")
                .col("LEN", "2", "2", "2", "2", "2").build();
        assertEquals(expected,
                bool("prefix_matches", 5, col(c, "X"), ConstVector.of(re), col(c, "LEN")),
                "char column holding \"2\"");
        // (d) "2" string literal
        assertEquals(expected,
                bool("prefix_matches", 5, x, ConstVector.of(re), ConstVector.of("2")),
                "string literal \"2\"");
    }


    @Test
    void affixMatchesNonIntegralLengthFallsBackToWholeString()
    {
        // A null / non-integral / infinite length folds to null ⇒ the WHOLE string is matched
        // (same edge semantics as the Integer-arg overload). "FA" anchored against (AP|FA) matches;
        // "FAKE" whole-string does NOT. So with a missing length only row 0 ("FA") fires.
        IDataTable t = MockTable.of().col("X", "FA", "FAKE").build();
        // missing length (empty char cells)
        assertEquals(bits(0),
                bool("prefix_matches", 2, col(t, "X"), ConstVector.of("(AP|FA)"),
                        col(MockTable.of().col("L", "", "").build(), "L")),
                "missing length ⇒ whole string");
        // non-integral length 2.5 ⇒ null ⇒ whole string
        assertEquals(bits(0), bool("prefix_matches", 2, col(t, "X"), ConstVector.of("(AP|FA)"),
                ConstVector.of(2.5)), "non-integral length ⇒ whole string");
    }


    @Test
    void affixValueFamily()
    {
        // prefix(x, n) / suffix(x, n): first/last n characters; shorter-than-n → WHOLE string
        // (legacy extractPrefix/extractSuffix). prefix("",n)="" and prefix(«missing»,n)="": a
        // genuine missing folds to "" (function-examples.md "Affix extraction"). Row 2 is "",
        // row 3 is a genuine null.
        IDataTable t = MockTable.of().col("X", "FAKE", "F", "", (String) null).build();
        Vector p = value("prefix", 4, col(t, "X"), ConstVector.of(2.0));
        assertEquals("FA", p.asString(0)); // prefix("ABCD"-like,2) -> "FA"
        assertEquals("F", p.asString(1), "shorter than n → whole string");
        // result VALUE is the literal "" (asString == ""); isMissing is true for any "" cell.
        assertEquals("", p.asString(2)); // prefix("",2) -> ""
        assertEquals("", p.asString(3)); // prefix(«missing»,2) -> ""
        Vector s = value("suffix", 4, col(t, "X"), ConstVector.of(2.0));
        assertEquals("KE", s.asString(0));
        assertEquals("F", s.asString(1));
        assertEquals("", s.asString(2)); // suffix("",2) -> ""
        assertEquals("", s.asString(3)); // suffix(«missing»,2) -> ""
        // a non-integral / non-positive n yields the whole string (legacy null-length contract)
        Vector whole = value("prefix", 4, col(t, "X"), ConstVector.of(0.0));
        assertEquals("FAKE", whole.asString(0));
    }


    @Test
    void integerFamily()
    {
        IDataTable t = MockTable.of().col("X", "5", "5.5", "abc").build();
        assertEquals(bits(0), bool("is_integer", 3, col(t, "X")));
        assertEquals(bits(1, 2), bool("is_not_integer", 3, col(t, "X")));
    }


    @Test
    void charFirstCodePoint()
    {
        // char(x): the Unicode code point of the first character; "" / missing ⇒ missing.
        // Row 0 "A" → 65; row 1 " abc" → 32 (leading space, the <= 32 boundary); row 2 "\tX" → 9
        // (a leading control char, also <= 32); row 3 "éxy" → 233 (multi-byte first char, U+00E9).
        IDataTable t = MockTable.of().col("X", "A", " abc", "\tX", "éxy", "", (String) null)
                .build();
        Vector c = value("char", 6, col(t, "X"));
        assertEquals(65.0, c.asDouble(0));
        assertEquals(32.0, c.asDouble(1)); // leading space (boundary used by char(value()) <= 32)
        assertEquals(9.0, c.asDouble(2)); // leading tab — a control char <= 32
        assertEquals(233.0, c.asDouble(3)); // first char of a multi-byte string
        assertTrue(c.isMissing(4), "char(\"\") -> missing");
        assertTrue(c.isMissing(5), "char(«missing») -> missing");
    }


    @Test
    void isNumericBattery()
    {
        // Full grammar battery: accept 0/-3/3.5/007/.5; reject 1./1e5/+1/" 1 "/""/abc
        // (only a leading '-' is allowed, matching the legacy `-?` regexes).
        IDataTable t = MockTable.of()
                .col("X", "0", "-3", "3.5", "007", ".5", "1.", "1e5", "+1", " 1 ", "", "abc")
                .build();
        assertEquals(bits(0, 1, 2, 3, 4), bool("is_numeric", 11, col(t, "X")));
    }


    @Test
    void validTestcdAndName()
    {
        IDataTable t = MockTable.of()
                .col("X", "AB", "ab", "_X9", "3AB", "ABCDEFGH", "ABCDEFGHI", "A-B", "").build();
        // is_valid_testcd (mixed case): accept "AB", "ab", "_X9", 8-char; reject leading-digit
        // "3AB", 9-char, "A-B", "".
        assertEquals(bits(0, 1, 2, 4), bool("is_valid_testcd", 8, col(t, "X")));
        // is_valid_name (uppercase only): "ab" now rejected.
        assertEquals(bits(0, 2, 4), bool("is_valid_name", 8, col(t, "X")));
    }


    @Test
    void hasAlphaHasDigit()
    {
        IDataTable t = MockTable.of().col("X", "Grade2", "abc", "123", "!?", "").build();
        assertEquals(bits(0, 1), bool("has_alpha", 5, col(t, "X")));
        assertEquals(bits(0, 2), bool("has_digit", 5, col(t, "X")));
    }


    @Test
    void durationFamily()
    {
        IDataTable t = MockTable.of().col("X", "P1Y", "XYZ", "").build();
        // Empty-string literal fix (A.4): invalid_duration now fires on the "" row (2).
        assertEquals(bits(1, 2), bool("invalid_duration", 3, col(t, "X")));
        // is_valid_duration("")=false (function-examples.md "Boolean functions without an operator
        // alias"): "" is structurally invalid, so the "" row (2) does not fire.
        assertEquals(bits(0), bool("is_valid_duration", 3, col(t, "X")));

        // EC-20/EC-22: the arity-1 invalid_duration builtin defaults negative=true (accept the
        // signed grammar), matching the Python reference engine and the aligned legacy operator.
        // A bare signed duration (-P1D) is valid; only a malformed one (--P1Y) fires.
        // is_valid_duration keeps its own negative=false grammar (Java-only helper, deliberately
        // untouched), so it rejects both signed rows.
        IDataTable s = MockTable.of().col("X", "-P1D", "--P1Y", "P2M").build();
        assertEquals(bits(1), bool("invalid_duration", 3, col(s, "X")));
        assertEquals(bits(2), bool("is_valid_duration", 3, col(s, "X")));
    }


    @Test
    void dateFamilyCalendarValidates()
    {
        IDataTable t = MockTable.of().col("X", "2024-01-01", "2024-01", "2024-13").build();
        // complete: only the full valid date
        assertEquals(bits(0), bool("is_complete_date", 3, col(t, "X")));
        // partial (calendar-valid but missing components): "2024-01"; "2024-13" is invalid month
        assertEquals(bits(1), bool("is_partial_date", 3, col(t, "X")));
        // umbrella valid: rows 0 and 1; "2024-13" rejected by calendar validation
        assertEquals(bits(0, 1), bool("is_valid_date", 3, col(t, "X")));
        // invalid: the calendar-impossible month
        assertEquals(bits(2), bool("invalid_date", 3, col(t, "X")));
        // is_incomplete_date is an alias of is_partial_date
        assertEquals(bits(1), bool("is_incomplete_date", 3, col(t, "X")));

        // is_valid_date("")=false and is_valid_date(«missing»)=false (function-examples.md "Boolean
        // functions without an operator alias"): "" is structurally invalid, neither row fires.
        IDataTable e = MockTable.of().col("X", "", (String) null).build();
        assertEquals(new BitSet(), bool("is_valid_date", 2, col(e, "X")));
    }


    @Test
    void dateFamilyRejectsImpossibleDay()
    {
        IDataTable t = MockTable.of().col("X", "2023-02-29", "2024-02-29").build();
        // 2023 not leap -> invalid; 2024 leap -> valid complete
        assertEquals(bits(0), bool("invalid_date", 2, col(t, "X")));
        assertEquals(bits(1), bool("is_complete_date", 2, col(t, "X")));
        assertFalse(bool("is_valid_date", 2, col(t, "X")).get(0));
    }


    /**
     * Fix #157 — {@code is_complete_date_part} judges ONLY the leading {@code YYYY-MM-DD}. Rows are
     * the plan's confirmed truth table (PLAN-incomplete-date-rule-review.md §3b/§8.4): the value a
     * blank, malformed-structural, malformed-calendar, two truncations, a complete date, a complete
     * date with a TRUNCATED TIME, and a full datetime.
     */
    @Test
    void completeDatePartJudgesOnlyTheDatePortion()
    {
        IDataTable t = MockTable.of().col("X", "", "banana", "2020-13-45", "2020", "2020-01",
                "2020-01-15", "2020-01-15T10", "2020-01-15T10:30:00").build();
        // Rows 5/6/7 have a complete date portion. Row 6 ("2020-01-15T10", length 13) is the whole
        // point: is_complete_date REJECTS it and is_incomplete_date FIRES on it.
        assertEquals(bits(5, 6, 7), bool("is_complete_date_part", 8, col(t, "X")));
        assertEquals(bits(0, 1, 2, 3, 4), bool("is_not_complete_date_part", 8, col(t, "X")));
        // The two established predicates, on the SAME column — this is the divergence being fixed.
        assertEquals(bits(5, 7), bool("is_complete_date", 8, col(t, "X")));
        assertEquals(bits(3, 4, 6), bool("is_incomplete_date", 8, col(t, "X")));
    }


    /**
     * Fix #157 — a genuine missing folds to {@code ""} exactly as {@code is_integer} does, so the
     * positive form stays false and the NEGATIVE form fires on a blank. That is what makes
     * {@code is_not_complete_date_part} a negative leaf needing an absent-column guard.
     */
    @Test
    void completeDatePartFoldsMissingToEmpty()
    {
        IDataTable t = MockTable.of().col("X", "2020-01-15", "", (String) null).build();
        assertEquals(bits(0), bool("is_complete_date_part", 3, col(t, "X")));
        assertEquals(bits(1, 2), bool("is_not_complete_date_part", 3, col(t, "X")));
    }


    /**
     * Fix #157 — the pair is EXACTLY complementary (unlike {@code is_complete_date} /
     * {@code is_incomplete_date}, where a malformed value is neither), and the positive form is
     * bit-identical to the composed {@code is_complete_date(prefix(X, 10))} it replaces.
     */
    @Test
    void completeDatePartIsComplementaryAndEqualsComposedPrefixForm()
    {
        IDataTable t = MockTable.of()
                .col("X", "", "banana", "2020-13-45", "2020", "2020-01", "2020-01-15",
                        "2020-01-15T10", "2020-01-15T10:30:00", "2020-02-30", "2020-01-15Tbanana")
                .build();
        BitSet positive = bool("is_complete_date_part", 10, col(t, "X"));
        BitSet negative = bool("is_not_complete_date_part", 10, col(t, "X"));
        BitSet union = (BitSet) positive.clone();
        union.or(negative);
        BitSet intersection = (BitSet) positive.clone();
        intersection.and(negative);
        assertEquals(bits(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), union, "the pair must cover every row");
        assertEquals(new BitSet(), intersection, "the pair must not overlap on any row");

        // The composed form the corpus would otherwise have to author.
        Vector ten = col(MockTable.of()
                .colLong("N", 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L).build(), "N");
        Vector prefixed = value("prefix", 10, col(t, "X"), ten);
        assertEquals(bool("is_complete_date", 10, prefixed), positive,
                "is_complete_date_part(X) must equal is_complete_date(prefix(X, 10))");
        // ... and it is NOT is_complete_date(X): row 6's truncated time is the whole difference.
        assertEquals(bits(5, 7), bool("is_complete_date", 10, col(t, "X")));
    }


    @Test
    void colrefTwoHopHappyPath()
    {
        // IDVAR names the column ("AESEQ"); colref reads AESEQ on the same row.
        IDataTable t = MockTable.of().col("IDVAR", "AESEQ").col("AESEQ", "1").build();
        Vector r = valueOn("colref", t, 1, col(t, "IDVAR"));
        assertEquals("1", r.asString(0));
    }


    @Test
    void colrefPerRowVaryingFirstHop()
    {
        // Each row's first hop names a different column; colref dereferences per row.
        IDataTable t = MockTable.of().col("IDVAR", "AESEQ", "CMSEQ").col("AESEQ", "1", "x")
                .col("CMSEQ", "y", "99").build();
        Vector r = valueOn("colref", t, 2, col(t, "IDVAR"));
        assertEquals("1", r.asString(0)); // IDVAR[0]=AESEQ -> AESEQ[0]=1
        assertEquals("99", r.asString(1)); // IDVAR[1]=CMSEQ -> CMSEQ[1]=99
    }


    @Test
    void colrefNamedColumnAbsentIsMissing()
    {
        // First hop names a column that is not present (e.g. parent col not pre-merged) -> null.
        IDataTable t = MockTable.of().col("IDVAR", "NOPE").build();
        Vector r = valueOn("colref", t, 1, col(t, "IDVAR"));
        assertTrue(r.isMissing(0));
    }


    @Test
    void colrefMissingFirstHopIsMissing()
    {
        // A missing/empty first hop yields a missing result (mirrors ValueResolver).
        IDataTable t = MockTable.of().col("IDVAR", (String) null).col("AESEQ", "1").build();
        Vector r = valueOn("colref", t, 1, col(t, "IDVAR"));
        assertTrue(r.isMissing(0));
    }


    @Test
    void absRoundFloorCeil()
    {
        IDataTable t = MockTable.of().col("X", "-3.5", "2.5", "", "abc").build();
        Vector a = value("abs", 4, col(t, "X"));
        assertEquals(3.5, a.asDouble(0));
        assertTrue(a.isMissing(2), "missing in -> missing out");
        assertTrue(a.isMissing(3), "non-numeric -> missing");

        // round is half-up toward +inf (Math.round): 2.5 -> 3, -3.5 -> -3.
        assertEquals(3.0, value("round", 4, col(t, "X")).asDouble(1));
        assertEquals(-3.0, value("round", 4, col(t, "X")).asDouble(0));
        // floor / ceil
        assertEquals(-4.0, value("floor", 4, col(t, "X")).asDouble(0));
        assertEquals(3.0, value("ceil", 4, col(t, "X")).asDouble(1));
    }


    @Test
    void trimConcatCoalesce()
    {
        // trim(" x ")="x" and trim("")="": a genuine missing folds to "" (function-examples.md
        // "Case & whitespace"). Row 1 of A is "", row 3 is a genuine null.
        IDataTable t = MockTable.of().col("A", " x ", "", "x", (String) null)
                .col("B", "bar", "baz", "q", "w").build();
        Vector tr = value("trim", 4, col(t, "A"));
        assertEquals("x", tr.asString(0)); // " x " -> "x"
        // result VALUE is the literal "" (asString == ""); isMissing is true for any "" cell.
        assertEquals("", tr.asString(1)); // trim("") -> ""
        assertEquals("", tr.asString(3)); // trim(«missing») -> ""

        // concat: a missing operand contributes "" so the result is never missing.
        Vector cc = value("concat", 4, col(t, "A"), col(t, "B"));
        assertEquals(" x bar", cc.asString(0));
        assertEquals("baz", cc.asString(1)); // A "" treated as not-present -> ""

        // coalesce: first non-missing; A present at 0/2, "" (not-present) at 1 -> B.
        Vector co = value("coalesce", 4, col(t, "A"), col(t, "B"));
        assertEquals(" x ", co.asString(0));
        assertEquals("baz", co.asString(1));
    }


    @Test
    void concatCoalesceArity3()
    {
        IDataTable t = MockTable.of().col("A", "x", "", "").col("B", "y", "q", "")
                .col("C", "z", "r", "").build();
        // concat/3: missing operands contribute "".
        Vector cc = value("concat", 3, col(t, "A"), col(t, "B"), col(t, "C"));
        assertEquals("xyz", cc.asString(0));
        assertEquals("qr", cc.asString(1)); // A missing -> ""
        assertEquals("", cc.asString(2)); // all missing -> "" (never missing)

        // coalesce/3: first non-missing operand.
        Vector co = value("coalesce", 3, col(t, "A"), col(t, "B"), col(t, "C"));
        assertEquals("x", co.asString(0));
        assertEquals("q", co.asString(1)); // A missing -> B
        assertTrue(co.isMissing(2), "all missing -> missing");
    }


    @Test
    void substringTwoArg()
    {
        IDataTable t = MockTable.of().col("X", "ABCDE", "", "AB").build();
        // 1-based start: start=2 -> "BCDE".
        Vector s2 = value("substring", 3, col(t, "X"), ConstVector.of(2.0));
        assertEquals("BCDE", s2.asString(0));
        // substring("", 2): the "" input is judged literally (no longer pre-treated as missing),
        // but start=2 is past the end of a length-0 string, so the bounds rule yields MISSING
        // (function-examples.md "Substring"; the doc's "" row is corrected separately).
        assertTrue(s2.isMissing(1), "substring(\"\",2) -> missing (start past end of length-0)");
        // start beyond length -> missing.
        assertTrue(value("substring", 3, col(t, "X"), ConstVector.of(5.0)).isMissing(2));
        // start == length (1-based) -> last char.
        assertEquals("B", value("substring", 3, col(t, "X"), ConstVector.of(2.0)).asString(2));
        // start < 1 -> missing.
        assertTrue(value("substring", 3, col(t, "X"), ConstVector.of(0.0)).isMissing(0));
        // non-integral start -> missing.
        assertTrue(value("substring", 3, col(t, "X"), ConstVector.of(2.5)).isMissing(0));
    }


    @Test
    void substringThreeArg()
    {
        IDataTable t = MockTable.of().col("X", "ABCDE").build();
        // start=2, length=3 -> "BCD".
        assertEquals("BCD",
                value("substring", 1, col(t, "X"), ConstVector.of(2.0), ConstVector.of(3.0))
                        .asString(0));
        // length past end is clamped.
        assertEquals("CDE",
                value("substring", 1, col(t, "X"), ConstVector.of(3.0), ConstVector.of(99.0))
                        .asString(0));
        // length <= 0 -> empty string.
        assertEquals("",
                value("substring", 1, col(t, "X"), ConstVector.of(2.0), ConstVector.of(0.0))
                        .asString(0));
        // non-integral length -> missing.
        assertTrue(value("substring", 1, col(t, "X"), ConstVector.of(2.0), ConstVector.of(1.5))
                .isMissing(0));

        // substring("", 1, 1) and substring(«missing», 1, 1): the input is judged literally (no
        // longer pre-treated as missing), but start=1 is already past the end of a length-0 string
        // (from index 0 >= length 0), so the bounds rule yields MISSING — the actual observed
        // behaviour. (The function-examples.md "Substring" "" row is corrected separately.)
        IDataTable e = MockTable.of().col("X", "", (String) null).build();
        assertTrue(value("substring", 2, col(e, "X"), ConstVector.of(1.0), ConstVector.of(1.0))
                .isMissing(0), "substring(\"\",1,1) -> missing");
        assertTrue(value("substring", 2, col(e, "X"), ConstVector.of(1.0), ConstVector.of(1.0))
                .isMissing(1), "substring(«missing»,1,1) -> missing");
    }


    @Test
    void dateComponents()
    {
        IDataTable t = MockTable.of()
                .col("X", "2024", "2024-03", "2024-03-15", "2024-03-15T08:30", "", "junk").build();
        Vector y = value("year", 6, col(t, "X"));
        assertEquals(2024.0, y.asDouble(0));
        assertEquals(2024.0, y.asDouble(3), "T time part ignored");
        assertTrue(y.isMissing(4), "missing -> missing");
        assertTrue(y.isMissing(5), "unparseable -> missing");

        Vector m = value("month", 6, col(t, "X"));
        assertTrue(m.isMissing(0), "year-only has no month");
        assertEquals(3.0, m.asDouble(1));
        assertEquals(3.0, m.asDouble(2));

        Vector d = value("day", 6, col(t, "X"));
        assertTrue(d.isMissing(1), "month-precision has no day");
        assertEquals(15.0, d.asDouble(2));
        assertEquals(15.0, d.asDouble(3));
    }


    @Test
    void imatchesCaseInsensitiveSearch()
    {
        IDataTable t = MockTable.of().col("X", "Hello", "WORLD", "", "abc").build();
        // unanchored, case-insensitive: "ell" matches "Hello"; "or" matches "WORLD".
        assertEquals(bits(0), bool("imatches", 4, col(t, "X"), ConstVector.of("ell")));
        assertEquals(bits(1), bool("imatches", 4, col(t, "X"), ConstVector.of("or")));
        // missing -> no fire.
        assertFalse(bool("imatches", 4, col(t, "X"), ConstVector.of("z")).get(2));
    }


    @Test
    void betweenInclusiveNumeric()
    {
        IDataTable t = MockTable.of().col("X", "5", "20", "30", "", "abc").build();
        // 10 <= X <= 30 : row1 (20) and row2 (30, inclusive) fire; 5 below, missing/non-numeric
        // skip.
        assertEquals(bits(1, 2),
                bool("between", 5, col(t, "X"), ConstVector.of(10.0), ConstVector.of(30.0)));
    }


    /** varname() reads the per-column "current variable" cursor (variables["variable_name"]). */
    @Test
    void varnameReadsCursor()
    {
        IDataTable t = MockTable.of().col("AESEQ", "1", "2").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t)
                .variables(java.util.Map.of("variable_name", "AESEQ")).build();
        Vector v = (Vector) FunctionRegistry.resolve("varname", 0).apply(new EvalRun(ctx, 0, 2),
                List.of());
        assertEquals("AESEQ", v.asString(0));
        assertEquals("AESEQ", v.asString(1), "broadcast constant across rows");
    }


    /** varname() with no cursor bound broadcasts missing. */
    @Test
    void varnameMissingCursorIsMissing()
    {
        IDataTable t = MockTable.of().col("AESEQ", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        Vector v = (Vector) FunctionRegistry.resolve("varname", 0).apply(new EvalRun(ctx, 0, 1),
                List.of());
        assertTrue(v.isMissing(0), "no cursor ⇒ missing varname");
    }


    /** value() reads the per-row cells of the column named by the cursor. */
    @Test
    void valueReadsCurrentVariableColumn()
    {
        IDataTable t = MockTable.of().col("TRTPFL", "Y", "X").col("OTHER", "a", "b").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t)
                .variables(java.util.Map.of("variable_name", "TRTPFL")).build();
        Vector v = (Vector) FunctionRegistry.resolve("value", 0).apply(new EvalRun(ctx, 0, 2),
                List.of());
        assertEquals("Y", v.asString(0));
        assertEquals("X", v.asString(1));
    }


    /** value() with a missing cursor or an absent column broadcasts missing (no row fires). */
    @Test
    void valueMissingCursorOrAbsentColumnIsMissing()
    {
        IDataTable t = MockTable.of().col("TRTPFL", "Y").build();
        EvaluationContext noCursor = EvaluationContext.builder().table(t).build();
        Vector v1 = (Vector) FunctionRegistry.resolve("value", 0).apply(new EvalRun(noCursor, 0, 1),
                List.of());
        assertTrue(v1.isMissing(0), "no cursor ⇒ missing value");

        EvaluationContext badCol = EvaluationContext.builder().table(t)
                .variables(java.util.Map.of("variable_name", "NOSUCH")).build();
        Vector v2 = (Vector) FunctionRegistry.resolve("value", 0).apply(new EvalRun(badCol, 0, 1),
                List.of());
        assertTrue(v2.isMissing(0), "absent column ⇒ missing value");
    }

}
