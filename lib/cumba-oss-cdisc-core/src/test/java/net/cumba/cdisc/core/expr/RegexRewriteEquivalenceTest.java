package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.eval.NativeExprEvaluator;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Behavioural-equivalence gate for the Phase-3 regex-rule optimisation
 * (PLAN-regex-rule-optimization). For each recognition key the legacy {@code =~}/{@code !~} regex
 * form and the rewritten scalar form are evaluated over the <em>same</em> {@link MockTable}, and
 * their violating-row {@link BitSet}s are asserted equal — except for the explicitly documented
 * widenings (each of which also has a {@code known_divergences} entry in
 * {@code documentation/parity-diff-baseline.json}).
 *
 * <p>
 * The parity harness builds rules from spec YAML and the corpus test only checks converter
 * self-consistency, so neither proves an optimised rule fires identically to its regex original on
 * data — this test is that proof.
 * </p>
 */
class RegexRewriteEquivalenceTest
{

    /** Evaluates a check-expression string over a table, returning the violating-row bit-set. */
    private static BitSet eval(String expr, IDataTable t)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    @Test
    void isNumericMatchesStrictRegex()
    {
        IDataTable t = MockTable.of()
                .col("X", "0", "007", ".5", "1.", "1e5", "+1", " 1 ", "-3.5", "", "abc", "3", "3.0")
                .build();
        BitSet regex = eval("X =~ /^-?(0|[1-9]\\d*)(\\.\\d+)?$/", t);
        BitSet rewrite = eval("is_numeric(X)", t);
        // DOCUMENTED widenings (is_numeric is a superset): "007" (row 1) and ".5" (row 2) now count
        // as numeric where the strict regex did not match them.
        regex.set(1);
        regex.set(2);
        assertEquals(regex, rewrite,
                "is_numeric vs strict numeric regex (with documented widening)");
    }


    @Test
    void notIsNumericMatchesLborresRegex()
    {
        // LBORRES laxer numeric regex (CORE-000289/290/298/299): /^-?(\d+(\.\d+)?$)|(\.\d+$)/.
        // is_numeric is a superset, so it accepts leading zeros ("007") where the regex still
        // fired (the LBORRES regex requires no leading zero before a multi-digit integer? — it
        // tolerates leading zeros, but rejects a bare lone-dot fractional differently). Assert the
        // documented widening for "007" explicitly.
        IDataTable t = MockTable.of().col("X", "0", "007", ".5", "-3.5", "abc", "3.0", "").build();
        BitSet regex = eval("X !~ /^-?(\\d+(\\.\\d+)?$)|(\\.\\d+$)/", t);
        BitSet rewrite = eval("not is_numeric(X)", t);
        // Documented widening: "007" (row 1) is numeric under is_numeric so it no longer fires the
        // not_matches form; align the regex baseline to the widened semantics.
        regex.clear(1);
        assertEquals(regex, rewrite,
                "not is_numeric vs LBORRES numeric regex (documented widening)");
    }


    @Test
    void leadingSpaceMatchesRegex()
    {
        // Battery: leading space, leading tab, leading control char (cp 1), normal value, blank,
        // null.
        IDataTable t = MockTable.of().col("X", " hi", "\thi", "hi", "hi", "", null).build();
        BitSet regex = eval("X =~ /^\\s/", t);
        BitSet rewrite = eval("not empty(X) and char(X) <= 32", t);
        // DOCUMENTED breadth divergence: char(X) <= 32 flags the leading ASCII control char (cp 1,
        // row 2) which \s does not match.
        regex.set(2);
        assertEquals(regex, rewrite,
                "leading-space vs char(X) <= 32 (documented control-char breadth)");
    }


    @Test
    void lengthBoundMatchesRegex()
    {
        IDataTable t = MockTable.of()
                .col("X", "12345678901234567890", "123456789012345678901", "short", "").build();
        assertEquals(eval("X =~ /^(.){21,}$/", t), eval("len(X) > 20", t),
                "len(X) > 20 vs ^(.){21,}$");
        IDataTable t2 = MockTable.of().col("X", "12345678", "123456789", "ab", "").build();
        assertEquals(eval("X =~ /^(.){9,}$/", t2), eval("len(X) > 8", t2),
                "len(X) > 8 vs ^(.){9,}$");
    }


    @Test
    void strictIntegerRewriteIsNotEquivalentSoRulesKeepRegex()
    {
        // REVIEW REVERT (FIX #1): the strict-integer regexes (CDISC-AD0169,
        // CORE-000338/340/534/587)
        // were reverted to regex because `is_integer` = ScalarSemantics.isIntegerString is
        // Double.parseDouble-backed and LENIENT — it accepts forms the strict regexes reject
        // (`5.0`, `1e5`, `+5`, ` 5 `, leading zeros), which would cause FALSE NEGATIVES. This test
        // pins the divergence on `5.0`: the regex `^\d+$` does NOT match "5.0" (so the not_matches
        // form FIRES), but the integer rewrite does NOT fire — proving the two are not equivalent
        // and the revert is correct.
        IDataTable t = MockTable.of().col("X", "5.0", "1e5", " 5 ").build();
        BitSet regex = eval("X !~ /^\\d+$/", t);
        BitSet rewrite = eval("not (is_integer(X) and X >= 0)", t);
        // The strict regex fires on every row (none is a bare run of digits).
        BitSet allFire = new BitSet();
        allFire.set(0, 3);
        assertEquals(allFire, regex,
                "strict ^\\d+$ fires on 5.0 / 1e5 / ' 5 ' (none is bare digits)");
        // The lenient is_integer rewrite does NOT fire on "5.0" (is_integer treats it as integer),
        // so the two diverge — which is exactly why these rules keep the regex.
        org.junit.jupiter.api.Assertions.assertFalse(rewrite.get(0),
                "is_integer(\"5.0\") is true (parseDouble-lenient) so the rewrite would NOT fire — "
                        + "a false negative vs the strict regex; the rule correctly keeps the regex");
        org.junit.jupiter.api.Assertions.assertNotEquals(regex, rewrite,
                "strict-integer regex and is_integer rewrite are NOT equivalent (review revert)");
    }


    @Test
    void durationMatchesRegex()
    {
        String re = "^P(?=\\d+[YMWD])(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?"
                + "(T(?=\\d+[HMS])(\\d+H)?(\\d+M)?(\\d+S)?)?$";
        IDataTable t = MockTable.of().col("X", "P1Y", "P2M3D", "PT1H", "P1YT2H", "garbage", "P", "")
                .build();
        BitSet regex = eval("X !~ /" + re + "/", t);
        BitSet rewrite = eval("invalid_duration(X)", t);
        // DOCUMENTED divergence (CORE-000779): the legacy regex's leading lookahead
        // (?=\d+[YMWD]) requires a date component immediately after P, so it rejects a time-only
        // ISO-8601 duration such as "PT1H" (row 2) — firing the not_matches form. invalid_duration
        // correctly accepts "PT1H" as a valid duration, so it does not fire. Align the baseline.
        regex.clear(2);
        assertEquals(regex, rewrite,
                "invalid_duration vs ISO-8601 duration regex (documented time-only divergence)");
    }


    @Test
    void core000779TimeOnlyDurationsAreIntendedDivergence()
    {
        // CORE-000779 keeps invalid_duration(TDSTOFF), an INTENDED, MORE-CORRECT divergence: the
        // legacy lookahead regex wrongly REJECTED valid ISO-8601 time-only / multi-component /
        // fractional durations (the leading lookahead requires a date component, and the time
        // lookahead `(?=\d+[HMS])` does not admit a fractional second). invalid_duration accepts
        // them as valid, so it does NOT fire on them. Assert the NEW behaviour explicitly.
        IDataTable t = MockTable.of().col("X", "PT1H", "PT1M30S", "PT1.5S", "garbage", "").build();
        BitSet rewrite = eval("invalid_duration(X)", t);
        // PT1H, PT1M30S, PT1.5S are all valid ISO-8601 durations -> NO fire (rows 0-2).
        // "garbage" and "" are invalid -> fire (rows 3,4).
        BitSet expected = new BitSet();
        expected.set(3);
        expected.set(4);
        assertEquals(expected, rewrite,
                "CORE-000779 invalid_duration — intended, more-correct divergence: time-only / "
                        + "multi-component / fractional durations the legacy regex wrongly rejected");
    }


    @Test
    void core000335RandqtRangeIsIntendedDivergence()
    {
        // CORE-000335 (RANDQT quotient) — the curated rewrite is an INTENDED, MORE-CORRECT
        // divergence from the buggy legacy regex pair `^(0.[0-9]+?)$` / `^[1]$` (unescaped `.`:
        // it matched "0a5"/"0X5" and excluded "1.0") — pinned here so the divergence is
        // asserted, not silent.
        //
        // Since the D-TA-7c twin-alignment ruling (2026-08-17) the curated shape is the
        // converged (0,1] range shared with CDISC-CG0280 / FDA-SD1269 / PMDA-SD1269:
        // null-exempt, numeric-format guard, numeric ordering comparison — and TSVAL = 0
        // FIRES (a quotient of exactly 0 is not a valid randomization quotient; the earlier
        // between(X, 0, 1) rewrite admitted it).
        IDataTable t = MockTable.of().col("X", "0", "1", "1.0", "0.5", "0a5", "0X5", "2", "abc", "")
                .build();
        BitSet rewrite = eval("not empty(X) and (X <= 0 or X > 1 or X !~"
                + " /^[+-]?([0-9]+(\\.[0-9]+)?|\\.[0-9]+)$/)", t);
        // INTENDED behaviour of the converged (0,1] shape, asserted explicitly:
        // 1, 1.0, 0.5 are valid quotients in (0,1] -> NO fire (rows 1-3).
        // 0 is outside (0,1] -> fire (row 0). 0a5, 0X5 are not numbers -> fire (rows 4,5).
        // 2 is out of range -> fire (row 6). abc is not numeric -> fire (row 7).
        // "" is exempt (null-exempt guard) -> NO fire (row 8).
        BitSet expected = new BitSet();
        expected.set(0);
        expected.set(4);
        expected.set(5);
        expected.set(6);
        expected.set(7);
        assertEquals(expected, rewrite,
                "CORE-000335 RANDQT range rewrite — the converged D-TA-7c (0,1] shape "
                        + "(0 fires; blank exempt; 0a5/0X5 fire as non-numeric)");
    }


    @Test
    void slashContainsMatchesRegex()
    {
        IDataTable t = MockTable.of().col("X", "2020/01/01", "2020-01-01", "a/b", "", null).build();
        assertEquals(eval("X =~ /\\//", t), eval("contains(X, \"/\")", t),
                "contains(X, \"/\") vs /\\//");
    }


    @Test
    void amPmMatchesRegex()
    {
        IDataTable t = MockTable.of().col("X", "10:00 AM", "10:00 pm", "noon", "AMOUNT", "", null)
                .build();
        // Note: (?i)(AM|PM) is an unanchored find, so "AMOUNT" (contains "AM") matches both forms.
        assertEquals(eval("X =~ /(?i)(AM|PM)/", t),
                eval("contains(upper(X), \"AM\") or contains(upper(X), \"PM\")", t),
                "contains(upper(X), AM/PM) vs (?i)(AM|PM)");
    }


    @Test
    void validTestcdMatchesRegex()
    {
        // not_matches /^[a-zA-Z_][a-zA-Z0-9_]{0,7}$/ == not is_valid_testcd(X). Battery: mixed-case
        // valid, valid 8-char, leading-digit (invalid), 9-char (too long), special char (invalid),
        // underscore-led valid, blank, null. is_valid_testcd is exact — no documented divergence.
        IDataTable t = MockTable.of()
                .col("X", "AB", "ab12", "ABCDEFGH", "3AB", "ABCDEFGHI", "A-B", "_X9", "", null)
                .build();
        assertEquals(eval("X !~ /^[a-zA-Z_][a-zA-Z0-9_]{0,7}$/", t),
                eval("not is_valid_testcd(X)", t),
                "not is_valid_testcd(X) vs ^[a-zA-Z_][a-zA-Z0-9_]{0,7}$");
    }


    @Test
    void validNameMatchesRegex()
    {
        // not_matches /^[A-Z_][A-Z0-9_]{0,7}$/ == not is_valid_name(X). Same battery; the lowercase
        // "ab12" and "Ab" are now invalid (uppercase only). Exact — no documented divergence.
        IDataTable t = MockTable.of()
                .col("X", "AB", "ab12", "Ab", "ABCDEFGH", "3AB", "ABCDEFGHI", "_X9", "", null)
                .build();
        assertEquals(eval("X !~ /^[A-Z_][A-Z0-9_]{0,7}$/", t), eval("not is_valid_name(X)", t),
                "not is_valid_name(X) vs ^[A-Z_][A-Z0-9_]{0,7}$");
    }


    @Test
    void hasAlphaHasDigitMatchRegex()
    {
        // matches /.*[a-zA-Z].*/ == has_alpha(X); matches /.*[0-9].*/ == has_digit(X)
        // (CORE-000169).
        IDataTable t = MockTable.of().col("X", "Grade2", "abc", "123", "!?", "", null).build();
        assertEquals(eval("X =~ /.*[a-zA-Z].*/", t), eval("has_alpha(X)", t),
                "has_alpha(X) vs .*[a-zA-Z].*");
        assertEquals(eval("X =~ /.*[0-9].*/", t), eval("has_digit(X)", t),
                "has_digit(X) vs .*[0-9].*");
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 5 — broadcast (per-variable / per-dataset) readability rewrites. These operate on a
    // string operand (varname() / ds_name("DATA")), so a plain column X faithfully exercises the
    // string semantics: the operand mapping is identical on both sides of each equivalence.
    // ---------------------------------------------------------------------------------------------


    @Test
    void endsWithMatchesSuffixRegex()
    {
        // /.+FL$/ vs ends_with(X, "FL") over a battery of variable names. The /.+/ fill requires at
        // least one char before the suffix, but no real variable name is exactly "FL" alone (and
        // ends_with("FL","FL") would be the only divergence — excluded from the battery).
        IDataTable t = MockTable.of()
                .col("X", "SAFFL", "ANL01FL", "AGE", "FLAG", "TRTEMFL", "DTHFL", "", null).build();
        assertEquals(eval("X =~ /.+FL$/", t), eval("ends_with(X, \"FL\")", t),
                "ends_with(X, \"FL\") vs /.+FL$/");
        // A longer suffix (the SDTM/ADaM date-suffix family).
        IDataTable t2 = MockTable.of()
                .col("X", "ADT", "ASTDT", "TRTSDT", "ADTM", "DTHDT", "AGE", "", null).build();
        assertEquals(eval("X =~ /.+DT$/", t2), eval("ends_with(X, \"DT\")", t2),
                "ends_with(X, \"DT\") vs /.+DT$/");
    }


    @Test
    void bareSuffixIsIntendedDivergence()
    {
        // The broadcast `.+SUF$` -> ends_with(varname(), "SUF") rewrite has one INTENDED, inert
        // divergence: a variable named EXACTLY the suffix ("FL") matches ends_with("FL","FL") but
        // NOT the regex `^.+FL$` (the `.+` fill needs at least one char before the suffix). This
        // is real but inert — no shipped ADaM variable is a bare suffix. Pinned here explicitly.
        IDataTable t = MockTable.of().col("X", "FL").build();
        BitSet regex = eval("X =~ /.+FL$/", t);
        BitSet rewrite = eval("ends_with(X, \"FL\")", t);
        assertEquals(0, regex.cardinality(), "regex `^.+FL$` does NOT match a bare \"FL\"");
        assertEquals(1, rewrite.cardinality(),
                "ends_with(\"FL\", \"FL\") DOES match — intended (inert) divergence");
    }


    @Test
    void notEndsWithMatchesSuffixRegex()
    {
        // The negated sense: /.*DTM$/ vs ends_with — the CDISC-AD0042/0716 excluded-suffix clauses
        // are spelled `not ends_with(varname(), "DTM")`.
        IDataTable t = MockTable.of()
                .col("X", "ADTM", "TRTSDTM", "ADT", "ASTM", "ELTM", "AGE", "", null).build();
        assertEquals(eval("X !~ /.*DTM$/", t), eval("not ends_with(X, \"DTM\")", t),
                "not ends_with(X, \"DTM\") vs /.*DTM$/");
        assertEquals(eval("X !~ /.*ELTM$/", t), eval("not ends_with(X, \"ELTM\")", t),
                "not ends_with(X, \"ELTM\") vs /.*ELTM$/");
    }


    @Test
    void startsWithMatchesAdPrefixRegex()
    {
        // CDISC-AD0496/0497: ds_name ^AD vs starts_with(X, "AD") — both senses.
        IDataTable t = MockTable.of()
                .col("X", "ADSL", "ADAE", "DM", "BADGE", "AD", "ADVS", "", null).build();
        assertEquals(eval("X =~ /^AD/", t), eval("starts_with(X, \"AD\")", t),
                "starts_with(X, \"AD\") vs /^AD/");
        assertEquals(eval("X !~ /^AD/", t), eval("not starts_with(X, \"AD\")", t),
                "not starts_with(X, \"AD\") vs /^AD/");
    }


    @Test
    void prefixNotInMatchesAffixRegex()
    {
        // CORE-000539: prefix(X,2) !~ /^(AP|FA)$/ vs prefix(X,2) not in ["AP","FA"]. The anchored
        // affix regex matches exactly the 2-char prefix against "AP"/"FA", so negated membership is
        // equivalent.
        // ⚠ Both forms are case-SENSITIVE, so a lower-case-only column makes both sides all-true
        // and the equality assertion vacuous (it held for the wrong reason until the TA review
        // fix). The upper-case AP*/FA* rows below are what makes this arm discriminate; the
        // cardinality assertion keeps it that way.
        IDataTable t = MockTable.of().col("X", "apdm", "fadm", "lbabc", "vsxy", "ap", "fa", "",
                "APDM", "FADM", "AP", "FAXY").build();
        BitSet notIn = eval("prefix(X, 2) not in [\"AP\", \"FA\"]", t);
        assertEquals(eval("prefix(X, 2) !~ /^(AP|FA)$/", t), notIn,
                "prefix(X,2) not in [AP,FA] vs prefix(X,2) !~ /^(AP|FA)$/");
        assertEquals(7, notIn.cardinality(),
                "the four upper-case AP*/FA* rows must be excluded and the seven others kept —"
                        + " an all-true (or all-false) result means the arm stopped discriminating");
    }
}
