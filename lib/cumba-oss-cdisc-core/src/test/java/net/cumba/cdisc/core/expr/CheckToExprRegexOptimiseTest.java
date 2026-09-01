package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.TextNode;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import org.junit.jupiter.api.Test;

/**
 * Unit gate for the Phase-3 regex-rule recognition in {@link CheckToExpr#toExpr}: each known
 * {@code (operator, pattern)} key must lower to the documented scalar predicate, and an
 * unrecognised regex must still lower to the {@code =~}/{@code !~} (MATCH/NMATCH) surface.
 */
class CheckToExprRegexOptimiseTest
{

    private static String lower(String op, String name, String pattern)
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name(name).operator(op)
                .value(new TextNode(pattern)).build();
        Expr e = CheckToExpr.toExpr(leaf);
        return ExpressionPrinter.print(e);
    }


    @Test
    void leadingSpaceLowersToCharCheck()
    {
        assertEquals("not empty(value()) and char(value()) <= 32",
                lower("matches_regex", "variable_value", "^\\s"));
    }


    @Test
    void lengthBoundsLowerToLen()
    {
        assertEquals("len(SETCD) > 8", lower("matches_regex", "SETCD", "^(.){9,}$"));
        assertEquals("len(ARMCD) > 20", lower("matches_regex", "ARMCD", "^(.){21,}$"));
    }


    @Test
    void numericRegexesLowerToIsNumeric()
    {
        assertEquals("is_numeric(X)", lower("matches_regex", "X", "^-?(0|[1-9]\\d*)(\\.\\d+)?$"));
        assertEquals("not is_numeric(X)",
                lower("not_matches_regex", "X", "^-?(0|[1-9]\\d*)(\\.\\d+)?$"));
        assertEquals("not is_numeric(X)",
                lower("not_matches_regex", "X", "^-?(\\d+(\\.\\d+)?$)|(\\.\\d+$)"));
    }


    @Test
    void looseNumericRegexStaysRegex()
    {
        // CORE-000094's loose `^\d*\.?\d*$` is NOT rewritten — is_numeric (sign-aware, differs on
        // `1.`/lone-dot) is not equivalent to the loose, sign-less regex (review revert FIX #2).
        // The printer doubles every backslash, so the regex literal reads `/^\\d*\\.?\\d*$/`.
        assertEquals("X =~ /^\\\\d*\\\\.?\\\\d*$/", lower("matches_regex", "X", "^\\d*\\.?\\d*$"));
    }


    @Test
    void integerRegexesStayRegex()
    {
        // The strict integer regexes are NOT rewritten — is_integer is parseDouble-backed and
        // lenient (accepts `5.0`/`1e5`/`+5`/leading zeros) which the strict regexes reject, so
        // these keep the regex to avoid false negatives (review revert FIX #1). The printer
        // doubles every backslash, so `\d` appears as `\\d` in the printed regex literal.
        assertEquals("X !~ /^\\\\d+$/", lower("not_matches_regex", "X", "^\\d+$"));
        assertEquals("X !~ /^[1-9]\\\\d*$/", lower("not_matches_regex", "X", "^[1-9]\\d*$"));
        assertEquals("X !~ /^(-?[1-9]\\\\d*|0)$/",
                lower("not_matches_regex", "X", "^(-?[1-9]\\d*|0)$"));
        assertEquals("X !~ /(-?[1-9]\\\\d*|0)$/",
                lower("not_matches_regex", "X", "(-?[1-9]\\d*|0)$"));
    }


    @Test
    void whitespaceTolerantIntegerRegexLowersToIsInteger()
    {
        // CORE-DRAFT-900007: the whitespace-tolerant integer pattern IS opted in to `not
        // is_integer` (the rule's intent is the lenient, trimming is_integer check; the `\s*`
        // fences let the Python-portable regex agree with is_integer on every clean/padded signed
        // integer — and stop the strict regex over-firing on the space-padded IDVARVAL SUPP
        // datasets carry). Both the `[0-9]` and `\d` spellings are recognised.
        assertEquals("not is_integer(IDVARVAL)",
                lower("not_matches_regex", "IDVARVAL", "^\\s*[+-]?[0-9]+\\s*$"));
        assertEquals("not is_integer(X)", lower("not_matches_regex", "X", "^\\s*[+-]?\\d+\\s*$"));
    }


    @Test
    void anchoredSuffixRegexLowersToSuffixEqual()
    {
        // CORE-DRAFT-900007: an anchored ends-with literal on a plain column lowers to the
        // efficient `suffix(X, n) == "..."` form (the same native shape suffix_equal_to produces).
        // Only the `.*<LIT>$` shape is recognised — Python's start-anchored str.match makes it
        // exactly "ends with LIT". An optional leading `^` is accepted (same semantics).
        assertEquals("suffix(IDVAR, 3) == \"SEQ\"", lower("matches_regex", "IDVAR", ".*SEQ$"));
        assertEquals("suffix(AETERM, 2) == \"AB\"", lower("matches_regex", "AETERM", "^.*AB$"));
        // `.+<LIT>$` (requires a leading char) and a bare `<LIT>$` (str.match ⇒ equals, not
        // ends-with) are NOT this exact shape and stay as the regex surface.
        assertEquals("X =~ /^.+FL$/", lower("matches_regex", "X", "^.+FL$"));
        assertEquals("X =~ /AB$/", lower("matches_regex", "X", "AB$"));
    }


    @Test
    void firstLetterLowersToBetweenChar()
    {
        assertEquals("not between(char(PARAMCD), char(\"A\"), char(\"Z\"))",
                lower("not_matches_regex", "PARAMCD", "^[A-Z]"));
    }


    @Test
    void firstLetterOnVariableNameStaysRegex()
    {
        // CDISC-AD0014's broadcast varname() form stays as regex (Phase 5 Cast).
        assertEquals("varname() !~ /^[A-Z]/",
                lower("not_matches_regex", "variable_name", "^[A-Z]"));
    }


    @Test
    void durationLowersToInvalidDuration()
    {
        String re = "^P(?=\\d+[YMWD])(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?"
                + "(T(?=\\d+[HMS])(\\d+H)?(\\d+M)?(\\d+S)?)?$";
        // The positive-only ISO duration regex pins negative=false (EC-20): the invalid_duration
        // absent-negative default is now true (accept signed), so the canonicalisation must state
        // negative=false to keep rejecting signed values (CORE-000779 / CG0376).
        assertEquals("invalid_duration(TDSTOFF, negative=false)",
                lower("not_matches_regex", "TDSTOFF", re));
    }


    @Test
    void amPmLowersToContainsUpper()
    {
        assertEquals("contains(upper(X), \"AM\") or contains(upper(X), \"PM\")",
                lower("matches_regex", "X", "(?i)(AM|PM)"));
    }


    @Test
    void slashLowersToContains()
    {
        assertEquals("contains(X, \"/\")", lower("matches_regex", "X", "/"));
    }


    @Test
    void testcdRegexLowersToIsValidTestcd()
    {
        // CORE-000220/000541/100005/100009: --TESTCD/IETESTCD/ETCD.
        assertEquals("not is_valid_testcd(--TESTCD)",
                lower("not_matches_regex", "--TESTCD", "^[a-zA-Z_][a-zA-Z0-9_]{0,7}$"));
        assertEquals("not is_valid_testcd(ETCD)",
                lower("not_matches_regex", "ETCD", "^[a-zA-Z_][a-zA-Z0-9_]{0,7}$"));
    }


    @Test
    void nameRegexLowersToIsValidName()
    {
        // CORE-000221/100007: QNAM.
        assertEquals("not is_valid_name(QNAM)",
                lower("not_matches_regex", "QNAM", "^[A-Z_][A-Z0-9_]{0,7}$"));
    }


    @Test
    void hasAlphaHasDigitLowerToFunctions()
    {
        // CORE-000169: LBTOXGR contains a letter AND a digit (each leaf recognised separately).
        assertEquals("has_alpha(LBTOXGR)", lower("matches_regex", "LBTOXGR", ".*[a-zA-Z].*"));
        assertEquals("has_digit(LBTOXGR)", lower("matches_regex", "LBTOXGR", ".*[0-9].*"));
    }


    @Test
    void unrecognisedRegexStaysMatch()
    {
        assertEquals("X =~ /^AD/", lower("matches_regex", "X", "^AD"));
        assertEquals("X !~ /^A/", lower("not_matches_regex", "X", "^A"));
    }


    @Test
    void varnameSuffixLowersToEndsWith()
    {
        // Phase 5 broadcast: pure-suffix varname() patterns -> ends_with(varname(), "SUF").
        assertEquals("ends_with(varname(), \"FL\")",
                lower("matches_regex", "variable_name", "^.+FL$"));
        assertEquals("ends_with(varname(), \"DT\")",
                lower("matches_regex", "variable_name", ".+DT$"));
        assertEquals("ends_with(varname(), \"TM\")",
                lower("matches_regex", "variable_name", "TM$"));
        assertEquals("ends_with(varname(), \"DTM\")",
                lower("matches_regex", "variable_name", ".+DTM$"));
        assertEquals("ends_with(varname(), \"SDTF\")",
                lower("matches_regex", "variable_name", ".+SDTF$"));
    }


    @Test
    void varnameSuffixNegatedLowersToNotEndsWith()
    {
        // The not_matches_regex sense (CDISC-AD0042/0716 excluded-suffix clauses).
        assertEquals("not ends_with(varname(), \"DTM\")",
                lower("not_matches_regex", "variable_name", ".*DTM$"));
        assertEquals("not ends_with(varname(), \"ELTM\")",
                lower("not_matches_regex", "variable_name", ".*ELTM$"));
    }


    @Test
    void varnameDigitIndexPatternsStayRegex()
    {
        // The \d{2} / \d index patterns (CDISC-AD0178/0212/0272/0313/0493/0647-0650) contain a
        // backslash, which is not [A-Z], so they are NOT pure-suffix and stay as regex.
        assertEquals("varname() =~ /^ANL\\\\d{2}FL$/",
                lower("matches_regex", "variable_name", "^ANL\\d{2}FL$"));
        assertEquals("varname() =~ /^TRTEM\\\\dFL$/",
                lower("matches_regex", "variable_name", "^TRTEM\\dFL$"));
        // The valid-uppercase-name charset patterns (CDISC-AD0015) also stay as regex.
        assertEquals("varname() !~ /^[A-Z_][A-Z0-9_]*$/",
                lower("not_matches_regex", "variable_name", "^[A-Z_][A-Z0-9_]*$"));
        assertEquals("varname() !~ /^[A-Z0-9_]+$/",
                lower("not_matches_regex", "variable_name", "^[A-Z0-9_]+$"));
    }


    @Test
    void datasetNameAdPrefixLowersToStartsWith()
    {
        // Phase 5 broadcast: dataset_name ^AD -> starts_with(ds_name("DATA"), "AD").
        assertEquals("starts_with(ds_name(\"DATA\"), \"AD\")",
                lower("matches_regex", "dataset_name", "^AD"));
        assertEquals("not starts_with(ds_name(\"DATA\"), \"AD\")",
                lower("not_matches_regex", "dataset_name", "^AD"));
    }


    @Test
    void suffixRewriteIsGatedOnVariableName()
    {
        // A plain data column with a pure-suffix shape is NOT rewritten (only varname() is) — the
        // broadcast readability rewrite is operand-gated.
        assertEquals("X =~ /^.+FL$/", lower("matches_regex", "X", "^.+FL$"));
    }
}
