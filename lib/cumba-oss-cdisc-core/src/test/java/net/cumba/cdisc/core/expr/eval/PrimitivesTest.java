package net.cumba.cdisc.core.expr.eval;

import static net.cumba.cdisc.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.Set;
import java.util.regex.Pattern;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 1 — verifies the vectorized primitive library against hand-built columns. The legacy
 * {@code OperatorRegistry*Test} suites already prove the shared {@code ScalarSemantics} math; these
 * tests prove the {@link Primitives} vectorization (row selection, missing handling, negation)
 * reproduces each operator family.
 */
@ExtendWith(MockitoExtension.class)
class PrimitivesTest
{

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    @Test
    void equality_stringLiteral()
    {
        // operator-examples.md B.1: against a populated literal, "" is evaluated literally — it
        // does not equal "M" (equal_to no-fire) and not_equal_to fires. Unchanged from before.
        IDataTable t = MockTable.of().col("X", "M", "F", "").build();
        ColumnVector x = col(t, "X");
        ConstVector m = ConstVector.of("M");
        assertEquals(bits(0), Primitives.equality(x, m, 3, false, false, false, false));
        // not_equal_to: row1 differs; row2 "" != "M" -> fires.
        assertEquals(bits(1, 2), Primitives.equality(x, m, 3, true, false, false, false));
    }


    @Test
    void equality_twoVariables_bothEmptyMatches()
    {
        // operator-examples.md B.2: between two variables the both-empty corner now matches
        // (equal_to fires); not_equal_to is unchanged in every row. A genuine missing folds to "".
        IDataTable ta = MockTable.of().col("A", "Y", "Y", "", (String) null).build();
        IDataTable tb = MockTable.of().col("B", "Y", "X", "", (String) null).build();
        ColumnVector a = col(ta, "A");
        ColumnVector b = col(tb, "B");
        // equal_to: row0 "Y"=="Y"; row2 ""==""; row3 missing==missing (folds to "") -> all match.
        assertEquals(bits(0, 2, 3), Primitives.equality(a, b, 4, false, false, false, false));
        // not_equal_to: only row1 "Y" != "X" fires — unchanged from today.
        assertEquals(bits(1), Primitives.equality(a, b, 4, true, false, false, false));
    }


    @Test
    void equality_caseInsensitive_bothEmptyMatches()
    {
        // operator-examples.md B.3: case-insensitive equality between two variables; the both-empty
        // corner matches (equal_to_ci fires), not_equal_to_ci unchanged.
        IDataTable ta = MockTable.of().col("A", "Y", "Y", "", (String) null).build();
        IDataTable tb = MockTable.of().col("B", "y", "X", "", (String) null).build();
        ColumnVector a = col(ta, "A");
        ColumnVector b = col(tb, "B");
        // row0 "Y"~="y"; row2 ""==""; row3 missing/missing fold to "" -> match.
        assertEquals(bits(0, 2, 3), Primitives.equality(a, b, 4, false, true, false, false));
        assertEquals(bits(1), Primitives.equality(a, b, 4, true, true, false, false));
    }


    @Test
    void equality_numericTarget()
    {
        IDataTable t = MockTable.of().colLong("AGE", 30L, 10L, null).build();
        ColumnVector age = col(t, "AGE");
        assertEquals(bits(0),
                Primitives.equality(age, ConstVector.of(30L), 3, false, false, false, false));
    }


    @Test
    void equality_caseInsensitive()
    {
        IDataTable t = MockTable.of().col("X", "abc", "ABC", "xyz").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0, 1),
                Primitives.equality(x, ConstVector.of("ABC"), 3, false, true, false, false));
    }


    @Test
    void equality_numericTypedLhs_vsStringTarget()
    {
        // Phase 8b: a numeric (LONG) column LHS compared to a STRING target parses both sides and
        // compares numerically — AGE=18 == "18.0" is true (textual would be "18" != "18.0").
        IDataTable t = MockTable.of().colLong("AGE", 18L, 10L, null).build();
        ColumnVector age = col(t, "AGE");
        assertEquals(bits(0),
                Primitives.equality(age, ConstVector.of("18.0"), 3, false, false, false, false));
        // Non-parseable RHS falls back to the textual fold: "18" vs "abc" → no row matches.
        assertEquals(new BitSet(),
                Primitives.equality(age, ConstVector.of("abc"), 3, false, false, false, false));
    }


    @Test
    void equality_forceNumeric_stringColumns()
    {
        // Phase 8a: forceNumeric upgrades two STRING columns to numeric mode (the num() path).
        IDataTable t = MockTable.of().col("A", "70", "70").col("B", "70.0", "80").build();
        ColumnVector a = col(t, "A");
        ColumnVector b = col(t, "B");
        // forceNumeric off → textual: "70" != "70.0", "70" != "80" → no match.
        assertEquals(new BitSet(), Primitives.equality(a, b, 2, false, false, false, false));
        // forceNumeric on → numeric: 70 == 70.0 (row0), 70 != 80 (row1).
        assertEquals(bits(0), Primitives.equality(a, b, 2, false, false, false, true));
    }


    @Test
    void comparison_numeric()
    {
        IDataTable t = MockTable.of().colLong("AGE", 30L, 10L, 20L).build();
        ColumnVector age = col(t, "AGE");
        ConstVector twenty = ConstVector.of(20L);
        assertEquals(bits(0), Primitives.comparison(age, twenty, 3, 1, false)); // > 20
        assertEquals(bits(0, 2), Primitives.comparison(age, twenty, 3, 1, true)); // >= 20
        assertEquals(bits(1), Primitives.comparison(age, twenty, 3, -1, false)); // < 20
    }


    @Test
    void comparison_missingAndNonNumericSkipped()
    {
        IDataTable t = MockTable.of().col("X", "5", "abc", "").build();
        ColumnVector x = col(t, "X");
        // string "5" parses; "abc" -> NaN skip; "" missing skip
        assertEquals(bits(0), Primitives.comparison(x, ConstVector.of(3L), 3, 1, false));
    }


    @Test
    void dateComparison_isoStrings()
    {
        IDataTable t = MockTable.of().col("DTC", "2024-01-02", "2024-01-01", "").build();
        ColumnVector dtc = col(t, "DTC");
        ConstVector ref = ConstVector.of("2024-01-01");
        // date_greater_than
        assertEquals(bits(0), Primitives.dateComparison(dtc, ref, 3, 1, false, false));
        // date_equal_to fires on equal row (mirrors legacy: negate=false -> returns match)
        assertEquals(bits(1), Primitives.dateComparison(dtc, ref, 3, 0, true, false));
    }


    @Test
    void dateComparison_numericEpsilon()
    {
        IDataTable t = MockTable.of().colDouble("DTM", 100.0, 100.000000001).build();
        ColumnVector dtm = col(t, "DTM");
        // within 1e-9? diff == 1e-9 which is NOT < epsilon -> row1 not equal
        assertEquals(bits(0),
                Primitives.dateComparison(dtm, ConstVector.of(100.0), 2, 0, true, false));
    }


    @Test
    void dateComparison_mixedTypesAlwaysFire()
    {
        IDataTable t = MockTable.of().colDouble("DTM", 100.0).build();
        ColumnVector dtm = col(t, "DTM");
        // numeric LHS vs non-parseable ISO RHS -> mixed -> fires
        assertEquals(bits(0),
                Primitives.dateComparison(dtm, ConstVector.of("2024-01-01"), 1, 1, false, false));
    }


    @Test
    void dateComparison_missingIsNegate()
    {
        IDataTable t = MockTable.of().col("DTC", "").build();
        ColumnVector dtc = col(t, "DTC");
        // not-equal (negate=true) fires on missing
        assertEquals(bits(0),
                Primitives.dateComparison(dtc, ConstVector.of("2024-01-01"), 1, 0, true, true));
        assertEquals(bits(),
                Primitives.dateComparison(dtc, ConstVector.of("2024-01-01"), 1, 0, true, false));
    }


    @Test
    void datePartComparison_numericAndIso()
    {
        IDataTable num = MockTable.of().colDouble("DTM", 86_400.0).build();
        assertEquals(bits(0), Primitives.datePartComparison(col(num, "DTM"), ConstVector.of(1.0), 1,
                false, false));

        IDataTable iso = MockTable.of().col("DTM", "2024-01-01T12:30").build();
        assertEquals(bits(0), Primitives.datePartComparison(col(iso, "DTM"),
                ConstVector.of("12:30"), 1, true, false));
    }


    @Test
    void datePartComparison_undefinedPartTreatedMissing()
    {
        IDataTable t = MockTable.of().col("DTM", "2024-01-01").build();
        // time part of a date-only value is undefined -> not a violation (negate=false)
        assertEquals(bits(), Primitives.datePartComparison(col(t, "DTM"), ConstVector.of("12:30"),
                1, true, false));
    }


    @Test
    void emptyAndNonEmpty()
    {
        IDataTable t = MockTable.of().col("X", "x", "", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(1, 2), Primitives.empty(x, 3));
        assertEquals(bits(0), Primitives.nonEmpty(x, 3));
    }


    @Test
    void regexFind_unanchored()
    {
        IDataTable t = MockTable.of().col("X", "ABC", "xyz", "").build();
        ColumnVector x = col(t, "X");
        Pattern p = Pattern.compile("^A");
        assertEquals(bits(0), Primitives.regexFind(x, p, 3, false));
        // not_matches: row1 no match -> fires; row2 "" -> "^A" still no match -> fires
        assertEquals(bits(1, 2), Primitives.regexFind(x, p, 3, true));
    }


    @Test
    void regexFind_emptyStringEvaluatedLiterally()
    {
        // Empty-string literal fix (A.3): "^$" matches "" and a missing cell (folds to ""), but
        // not the populated row. Previously the empty/missing rows returned the negate flag.
        IDataTable t = MockTable.of().col("X", "Y", "", (String) null).build();
        ColumnVector x = col(t, "X");
        Pattern empty = Pattern.compile("^$");
        assertEquals(bits(1, 2), Primitives.regexFind(x, empty, 3, false));
        assertEquals(bits(0), Primitives.regexFind(x, empty, 3, true));
        // "^[YN]$" is unchanged: "" matches neither way.
        Pattern yn = Pattern.compile("^[YN]$");
        assertEquals(bits(0), Primitives.regexFind(x, yn, 3, false));
        assertEquals(bits(1, 2), Primitives.regexFind(x, yn, 3, true));
    }


    @Test
    void affixRegex_anchored()
    {
        IDataTable t = MockTable.of().col("X", "ABCDEF").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0),
                Primitives.affixRegex(x, Pattern.compile("[A-Z]{3}"), 1, true, 3, false));
        assertEquals(bits(0), Primitives.affixRegex(x, Pattern.compile("EF"), 1, false, 2, false));
        // affix shorter than required length -> whole string used; "ABCDEF" != "EF"
        assertEquals(bits(), Primitives.affixRegex(x, Pattern.compile("EF"), 1, false, 99, false));
    }


    @Test
    void affixRegex_emptyStringEvaluatedLiterally()
    {
        // Empty-string literal fix (A.3): the extracted affix of "" / missing is "", evaluated
        // against the anchored pattern. "^$" matches the empty affix; "[A-Z]{3}" does not.
        IDataTable t = MockTable.of().col("X", "ABC", "", (String) null).build();
        ColumnVector x = col(t, "X");
        Pattern empty = Pattern.compile("^$");
        assertEquals(bits(1, 2), Primitives.affixRegex(x, empty, 3, true, 3, false));
        Pattern three = Pattern.compile("[A-Z]{3}");
        assertEquals(bits(0), Primitives.affixRegex(x, three, 3, true, 3, false));
        // not_… (negate) over the empty affix: "^$" fires only on the populated row.
        assertEquals(bits(0), Primitives.affixRegex(x, empty, 3, true, 3, true));
    }

    // -----------------------------------------------------------------------
    // EC-28(a) / Fix #131 — contains over a collection operand is exact membership
    // -----------------------------------------------------------------------


    @Test
    void containsOverACollectionIsExactMembershipNotSubstring()
    {
        // The FDA-SD0006 / PMDA-SD0006 / FDA-SE2319 shape: $blfl is a grouped `distinct` of
        // --BLFL, and the leaf is `$blfl does_not_contain "Y"`. Java used to render the set with
        // getValueAsString() and probe the text, so a set containing "YES" contained "Y" as a
        // SUBSTRING and the rule stayed silent — exactly the non-conformant data it exists to
        // catch. Python routes list cells through `needle in cell` = membership.
        ComputedVector sets = new ComputedVector(3, net.cumba.datatable.values.DataValueType.STRING,
                row -> switch (row)
                {
                case 0 -> java.util.List.of("Y", "N"); // contains "Y" -> no fire on the negation
                case 1 -> java.util.List.of("YES"); // substring would match; membership must not
                default -> java.util.List.of("N");
                });
        // contains: only the set that genuinely holds "Y".
        assertEquals(bits(0), Primitives.contains(sets, "Y", 3, false));
        // does_not_contain: "YES" and "N" both lack the exact element "Y", so both fire.
        assertEquals(bits(1, 2), Primitives.contains(sets, "Y", 3, true));
    }


    @Test
    void containsOverAPlainStringColumnStaysSubstring()
    {
        // The other 42 contains-family leaves in the corpus have a plain column LHS. Python's
        // is_in degenerates to `str in str` there, i.e. substring — identical to Java. The
        // membership branch must not touch them.
        IDataTable t = MockTable.of().col("X", "SUDDEN DEATH", "RECOVERED").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0), Primitives.contains(x, "DEATH", 2, false));
    }


    @Test
    void collectionMembershipAppliesToContainsOnly()
    {
        // starts_with / ends_with have no list branch in the Python operator either, so a
        // collection operand keeps the rendered-string behaviour there.
        ComputedVector sets = new ComputedVector(1, net.cumba.datatable.values.DataValueType.STRING,
                _ -> java.util.List.of("YES"));
        // The rendered form of List.of("YES") starts with "[" — proving the substring path ran.
        assertEquals(bits(0), Primitives.startsWith(sets, "[", 1));
    }


    @Test
    void collectionMembershipFoldsNullElementsToEmptyString()
    {
        ComputedVector sets = new ComputedVector(1, net.cumba.datatable.values.DataValueType.STRING,
                _ -> java.util.Arrays.asList("N", null));
        assertEquals(bits(0), Primitives.contains(sets, "", 1, false));
    }


    @Test
    void substringOps()
    {
        IDataTable t = MockTable.of().col("X", "HELLO", "WORLD", "").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0), Primitives.contains(x, "ELL", 3, false));
        // Empty-string literal fix (A.2): does_not_contain "ELL" now fires on "" (row 2) too.
        assertEquals(bits(1, 2), Primitives.contains(x, "ELL", 3, true)); // does_not_contain
        assertEquals(bits(0), Primitives.startsWith(x, "HE", 3));
        assertEquals(bits(0), Primitives.endsWith(x, "LO", 3));
    }


    @Test
    void substringOps_emptyStringEvaluatedLiterally()
    {
        // Empty-string literal fix (A.2): "" / missing fold to "" and are evaluated literally —
        // contains/starts_with/ends_with against a non-empty needle stay false, does_not_contain
        // fires.
        IDataTable t = MockTable.of().col("X", "XY", "", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0), Primitives.contains(x, "X", 3, false));
        assertEquals(bits(1, 2), Primitives.contains(x, "X", 3, true)); // does_not_contain "X"
        assertEquals(bits(0), Primitives.startsWith(x, "X", 3));
        assertEquals(bits(), Primitives.endsWith(x, "X", 3));
    }


    @Test
    void substringOps_numericLiteralNeedleRendersCanonically()
    {
        // D5: a numeric-literal needle resolves to a boxed Double. It must be stringified
        // canonically ("100", not "100.0"), so contains(CODE, 100) matches a cell containing
        // "100" and never spuriously matches via "100.0".
        IDataTable t = MockTable.of().col("CODE", "X100Y", "100ABC", "X100.0Y", "Z").build();
        ColumnVector code = col(t, "CODE");
        ConstVector hundred = ConstVector.of(100.0);
        // contains(CODE, 100): rows 0,1,2 all contain "100" (row2 "X100.0Y" contains "100" too).
        assertEquals(bits(0, 1, 2), Primitives.contains(code, hundred, 4, false));
        // starts_with(CODE, 100): only "100ABC" starts with "100".
        assertEquals(bits(1), Primitives.startsWith(code, hundred, 4));
        // ends_with(CODE, 100): none ends with "100" (would only fire if the needle were "100").
        assertEquals(bits(), Primitives.endsWith(code, hundred, 4));

        // Proof the canonical render matters: a needle of 100.0 must NOT match a cell that contains
        // only the literal text "100.0" but not "100" — there is no such cell that lacks "100", so
        // build one explicitly: "AB100.0CD" contains "100" (fires), "AB10.0CD" does not.
        IDataTable t2 = MockTable.of().col("CODE", "AB10.0CD").build();
        assertEquals(bits(), Primitives.contains(col(t2, "CODE"), hundred, 1, false));
    }


    @Test
    void lengthCompare()
    {
        IDataTable t = MockTable.of().col("X", "abcd", "ab", "").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0), Primitives.lengthCompare(x, 3, 3, 1)); // longer_than 3
        // shorter_than 3: "ab" (len 2) and "" (folds to length 0, A.5) both < 3
        assertEquals(bits(1, 2), Primitives.lengthCompare(x, 3, 3, -1));
    }


    @Test
    void lengthEquality_emptyAndMissingAreLengthZero()
    {
        // operator-examples.md A.5: "" and «missing» fold to length 0.
        // Row 0 "AB" (len 2), row 1 "" (len 0), row 2 null (len 0).
        IDataTable t = MockTable.of().col("X", "AB", "", (String) null).build();
        ColumnVector x = col(t, "X");
        // has_equal_length 0: the length-0 rows fire.
        assertEquals(bits(1, 2), Primitives.lengthEquality(x, 0, 3, false));
        // has_not_equal_length 5: all rows differ from 5.
        assertEquals(bits(0, 1, 2), Primitives.lengthEquality(x, 5, 3, true));
    }


    @Test
    void membership()
    {
        IDataTable t = MockTable.of().col("X", "A", "B", "C").build();
        ColumnVector x = col(t, "X");
        Set<String> set = Set.of("A", "C");
        assertEquals(bits(0, 2), Primitives.membership(x, set, 3, false, false));
        assertEquals(bits(1), Primitives.membership(x, set, 3, true, false));

        IDataTable ci = MockTable.of().col("X", "a", "b").build();
        assertEquals(bits(0), Primitives.membership(col(ci, "X"), Set.of("A"), 2, false, true));
    }


    @Test
    void membership_emptyStringEvaluatedLiterally()
    {
        // Empty-string literal fix (A.1): "" / missing fold to "" and are probed literally against
        // the list. is_not_contained_by ["Y","N"] fires on "X", "" and missing.
        IDataTable t = MockTable.of().col("X", "Y", "X", "", (String) null).build();
        ColumnVector x = col(t, "X");
        Set<String> yn = Set.of("Y", "N");
        assertEquals(bits(0), Primitives.membership(x, yn, 4, false, false)); // is_contained_by
        assertEquals(bits(1, 2, 3), Primitives.membership(x, yn, 4, true, false)); // is_not_...
        // Explicit opt-out list ["","Y","N"] permits "" / missing, so they are contained.
        Set<String> optOut = Set.of("", "Y", "N");
        assertEquals(bits(0, 2, 3), Primitives.membership(x, optOut, 4, false, false));
        assertEquals(bits(1), Primitives.membership(x, optOut, 4, true, false));
    }


    @Test
    void isInteger()
    {
        // operator-examples.md J.6: "" / missing are judged literally — not an integer — so
        // is_integer stays false on the empty row and is_not_integer now fires on it.
        IDataTable t = MockTable.of().col("X", "5", "5.5", "abc", "", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0), Primitives.isInteger(x, 5, false));
        // is_not_integer fires on "5.5", "abc", "" (row 3) and the genuine missing (row 4).
        assertEquals(bits(1, 2, 3, 4), Primitives.isInteger(x, 5, true));
    }


    @Test
    void isNumeric()
    {
        // Hand-rolled finite-decimal scan: accept 0/-3/3.5/007/.5; reject 1./1e5/+1/" 1 "/""/abc
        // and a genuine missing (only a leading '-' is allowed, matching the legacy `-?` regexes).
        // The negate (not is_numeric) form is the exact complement, and a missing/"" cell folds to
        // non-numeric so not is_numeric fires on it.
        IDataTable t = MockTable.of().col("X", "0", "-3", "3.5", "007", ".5", "1.", "1e5", "+1",
                " 1 ", "", "abc", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0, 1, 2, 3, 4), Primitives.isNumeric(x, 12, false));
        // not is_numeric fires on the rejects 5,6,7,8,9,10 and the genuine missing 11.
        assertEquals(bits(5, 6, 7, 8, 9, 10, 11), Primitives.isNumeric(x, 12, true));
    }


    @Test
    void isNumericDotEdgeCases()
    {
        // A lone "." and the sign-only "-"/"+" are not numbers; a leading "+" is rejected
        // (minus only), so "+.5" is NOT numeric but "-.5" and "0.0" are.
        IDataTable t = MockTable.of().col("X", ".", "-", "+", "+.5", "-.5", "0.0").build();
        assertEquals(bits(4, 5), Primitives.isNumeric(col(t, "X"), 6, false));
    }


    @Test
    void isValidTestcd()
    {
        // Mixed-case test code: first char [A-Za-z_], rest [A-Za-z0-9_], length 1..8. Accept "AB",
        // "ab" (mixed case), "_X9", "A2", an 8-char value, and "X" (length 1). Reject "3AB"
        // (leading digit), the 9-char value (too long), "A-B" ('-'), "" (length 0), and a genuine
        // missing.
        IDataTable t = MockTable.of().col("X", "AB", "ab", "_X9", "A2", "ABCDEFGH", "X", "3AB",
                "ABCDEFGHI", "A-B", "", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0, 1, 2, 3, 4, 5), Primitives.isValidTestcd(x, 11));
    }


    @Test
    void isValidName()
    {
        // Uppercase variable name: first char [A-Z_], rest [A-Z0-9_], length 1..8. Same battery as
        // isValidTestcd — but "ab" (lowercase) is now REJECTED, and a lowercase letter anywhere is
        // rejected ("Ab").
        IDataTable t = MockTable.of().col("X", "AB", "ab", "Ab", "_X9", "A2", "ABCDEFGH", "X",
                "3AB", "ABCDEFGHI", "A-B", "", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0, 3, 4, 5, 6), Primitives.isValidName(x, 12));
    }


    @Test
    void hasAlphaHasDigit()
    {
        // has_alpha / has_digit: unanchored "contains a letter / digit". "Grade2" has both; "abc"
        // only a letter; "123" only a digit; "!?" neither; "" / missing fire neither.
        IDataTable t = MockTable.of().col("X", "Grade2", "abc", "123", "!?", "", (String) null)
                .build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0, 1), Primitives.hasAlpha(x, 6));
        assertEquals(bits(0, 2), Primitives.hasDigit(x, 6));
    }


    @Test
    void invalidDuration()
    {
        // Empty-string literal fix (A.4): "" (row 4) is not a valid duration, so it fires too.
        IDataTable t = MockTable.of().col("X", "P1Y", "P", "PT24H", "XYZ", "").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(1, 3, 4), Primitives.invalidDuration(x, 5, false));
    }


    @Test
    void invalidDuration_emptyAndMissingFire()
    {
        // Empty-string literal fix (A.4): "" and a genuine missing both fold to "" -> invalid.
        IDataTable t = MockTable.of().col("X", "P1Y", "", (String) null).build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(1, 2), Primitives.invalidDuration(x, 3, false));
    }


    @Test
    void structuralDatePredicates()
    {
        IDataTable t = MockTable.of().col("X", "2024-01-01", "2024-01", "").build();
        ColumnVector x = col(t, "X");
        assertEquals(bits(0), Primitives.isCompleteDateStructural(x, 3));
        assertEquals(bits(1), Primitives.isIncompleteDateStructural(x, 3));

        // Empty-string literal fix (A.4): "" (row 2) is not a partial date -> invalid_date fires.
        IDataTable t2 = MockTable.of().col("X", "2024-01-01", "GARBAGE", "").build();
        assertEquals(bits(1, 2), Primitives.invalidDateStructural(col(t2, "X"), 3));
    }


    @Test
    void invalidDateStructural_emptyAndMissingFire()
    {
        // Empty-string literal fix (A.4): "" and a genuine missing both fold to "" -> invalid_date.
        IDataTable t = MockTable.of().col("X", "2024-01-01", "", (String) null).build();
        assertEquals(bits(1, 2), Primitives.invalidDateStructural(col(t, "X"), 3));
    }


    /**
     * EC-87 — {@link Primitives#compareCells} is an <em>extraction</em> of
     * {@link Primitives#dateComparison}'s per-row lambda, not a copy: over a pair corpus carrying
     * every cell shape (complete / partial / year ISO, blank, missing, numeric, mixed) and every
     * direction × or-equal × negate combination, the per-cell verdict must equal the vectorised
     * verdict bit for bit — with the right operand handed over in exactly the shape the
     * neighbouring-record relation uses ({@code isMissingOrInvalid ? null : getValueAsString}),
     * which is the {@code ColumnVector.resolvedObject} shape.
     */
    @Test
    void compareCells_isTheExtractedDateComparisonLambda()
    {
        IDataTable t = MockTable.of()
                .col("A", "2020-01-15", "2020-01-14", "2020-01", "2019", "", (String) null,
                        "2020-01-15", "x", "2020-01-15")
                .col("B", "2020-01-15", "2020-01-15", "2020-01", "2020-01-15", "2020-01-15", "",
                        (String) null, "2020-01-15", "12")
                .build();
        IDataTable n = MockTable.of().colDouble("L", 5.0, 5.0, null, 5.0)
                .colDouble("R", 7.0, 5.0, 5.0, null).build();
        int[][] cases =
        {
                // direction, orEqual, negate
                {
                        0, 1, 0
                },
                {
                        0, 1, 1
                },
                {
                        1, 0, 0
                },
                {
                        1, 1, 0
                },
                {
                        -1, 0, 0
                },
                {
                        -1, 1, 0
                },
                {
                        -1, 1, 1
                }
        };
        for (int[] c : cases)
        {
            boolean orEqual = c[1] == 1;
            boolean negate = c[2] == 1;
            differential(t, "A", "B", 9, c[0], orEqual, negate);
            differential(n, "L", "R", 4, c[0], orEqual, negate);
        }
    }


    private static void differential(IDataTable t, String lhs, String rhs, int rows, int direction,
            boolean orEqual, boolean negate)
    {
        BitSet vectorised = Primitives.dateComparison(col(t, lhs), col(t, rhs), rows, direction,
                orEqual, negate);
        int l = t.getMetaData().getColumnIndex(lhs);
        int r = t.getMetaData().getColumnIndex(rhs);
        for (int row = 0; row < rows; row++)
        {
            net.cumba.datatable.values.IDataValue left = t.getColumn(l).getDataValue(row);
            net.cumba.datatable.values.IDataValue right = t.getColumn(r).getDataValue(row);
            boolean perCell = Primitives.compareCells(left,
                    right.isMissingOrInvalid() ? null : right.getValueAsString(), direction,
                    orEqual, negate, true);
            assertEquals(vectorised.get(row), perCell, "row " + row + " of " + lhs + "/" + rhs
                    + " direction=" + direction + " orEqual=" + orEqual + " negate=" + negate);
        }
    }
}
