package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionLeaf;
import org.junit.jupiter.api.Test;

/**
 * Round-trip property test for the inverse path: a supported leaf raised to
 * {@link net.cumba.corej.core.expr.ast.Expr} via {@link CheckToExpr}, printed by
 * {@link ExpressionPrinter}, re-parsed by {@link CheckExpressionParser}, and lowered by
 * {@link ExprLowering} reproduces the original leaf. Leaves are built exactly as
 * {@code ExprLowering} produces them.
 */
class RoundTripTest
{

    private static final JsonNodeFactory N = JsonNodeFactory.instance;

    private static CheckCondition roundTrip(CheckCondition cc)
    {
        return ExprLowering.toCheckCondition(
                CheckExpressionParser.parse(ExpressionPrinter.print(CheckToExpr.toExpr(cc))));
    }


    /**
     * Like {@link #assertLeafRoundTrips} but asserts the round-tripped value equals
     * {@code expectedValue} rather than the original — for an operator (case-insensitive
     * membership) whose converter rewrite folds the value (here, to upper-case) so the round-trip
     * is exact behaviourally but not byte-for-byte on the value.
     */
    private static void assertLeafRoundTripsToValue(CheckConditionLeaf orig,
            com.fasterxml.jackson.databind.JsonNode expectedValue)
    {
        CheckConditionLeaf rt = assertInstanceOf(CheckConditionLeaf.class, roundTrip(orig));
        assertEquals(orig.getName(), rt.getName(), "name");
        assertEquals(orig.getOperator(), rt.getOperator(), "operator");
        assertEquals(expectedValue, rt.getValue(), "value");
    }


    /**
     * Asserts an affix-regex leaf round-trips to the same operator/name/affix-length but with the
     * pattern anchored to {@code expectedPattern} (Agreed change #2 anchors {@code ^…$} on emit and
     * stores it back verbatim on lowering).
     */
    private static void assertLeafRoundTripsToAffixRegex(CheckConditionLeaf orig,
            String expectedPattern)
    {
        CheckConditionLeaf rt = assertInstanceOf(CheckConditionLeaf.class, roundTrip(orig));
        assertEquals(orig.getName(), rt.getName(), "name");
        assertEquals(orig.getOperator(), rt.getOperator(), "operator");
        assertEquals(N.textNode(expectedPattern), rt.getValue(), "value");
        assertEquals(orig.getPrefix(), rt.getPrefix(), "prefix");
        assertEquals(orig.getSuffix(), rt.getSuffix(), "suffix");
    }


    private static void assertLeafRoundTrips(CheckConditionLeaf orig)
    {
        CheckConditionLeaf rt = assertInstanceOf(CheckConditionLeaf.class, roundTrip(orig));
        assertEquals(orig.getName(), rt.getName(), "name");
        assertEquals(orig.getOperator(), rt.getOperator(), "operator");
        assertEquals(orig.getValue(), rt.getValue(), "value");
        assertEquals(orig.getValueIsLiteral(), rt.getValueIsLiteral(), "valueIsLiteral");
        assertEquals(orig.getValueIsReference(), rt.getValueIsReference(), "valueIsReference");
        assertEquals(orig.getPrefix(), rt.getPrefix(), "prefix");
        assertEquals(orig.getSuffix(), rt.getSuffix(), "suffix");
        assertEquals(orig.getNegative(), rt.getNegative(), "negative");
        assertEquals(orig.getTypeInsensitive(), rt.getTypeInsensitive(), "typeInsensitive");
        assertEquals(orig.getWithin(), rt.getWithin(), "within");
        assertEquals(orig.getOrdering(), rt.getOrdering(), "ordering");
        assertEquals(orig.getRegex(), rt.getRegex(), "regex");
        assertEquals(orig.getIncludeEmpty(), rt.getIncludeEmpty(), "includeEmpty");
    }


    @Test
    void equalityLiteral()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DTHFL").operator("equal_to")
                .value(N.textNode("Y")).valueIsLiteral(true).build());
    }


    @Test
    void inequalityReference()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DSSTDTC").operator("not_equal_to")
                .value(N.textNode("DM.DTHDTC")).build());
    }


    @Test
    void notEqualToTwoHopReference()
    {
        // CORE-000206: type-insensitive not_equal_to with value_is_reference (two-hop). Emits
        // `str(IDVARVAL) != str(colref(IDVAR))`; must round-trip with valueIsReference preserved.
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("IDVARVAL").operator("not_equal_to")
                .typeInsensitive(true).value(N.textNode("IDVAR")).valueIsReference(true).build());
    }


    @Test
    void notEqualToTwoHopReferencePlain()
    {
        // The plain (non type-insensitive) comparison path: emits `IDVARVAL != colref(IDVAR)`.
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("IDVARVAL").operator("not_equal_to")
                .value(N.textNode("IDVAR")).valueIsReference(true).build());
    }


    @Test
    void dateComparison()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("MHSTDTC")
                .operator("date_greater_than_or_equal_to").value(N.textNode("DM.RFSTDTC")).build());
    }


    @Test
    void lengthComparison()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("variable_label")
                .operator("longer_than").value(N.numberNode(40L)).valueIsLiteral(true).build());
    }


    @Test
    void lengthComparisonOrEqual()
    {
        // shorter_than_or_equal_to / longer_than_or_equal_to -> len(X) <= n / len(X) >= n.
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("dataset_name")
                .operator("shorter_than_or_equal_to").value(N.numberNode(4L)).valueIsLiteral(true)
                .build());
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("dataset_name")
                .operator("longer_than_or_equal_to").value(N.numberNode(6L)).valueIsLiteral(true)
                .build());
    }


    @Test
    void regexMatch()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("dataset_name").operator("matches_regex")
                        .value(N.textNode("^AD.*$")).valueIsLiteral(true).build());
    }


    @Test
    void membershipLiteralList()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DTHFL").operator("is_contained_by")
                .value(N.arrayNode().add("Y").add("")).valueIsLiteral(true).build());
    }


    @Test
    void membershipOperationReference()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("VISITNUM")
                .operator("is_not_contained_by").value(N.textNode("$tv_visitnum")).build());
    }


    @Test
    void presencePredicate()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("AEOCCUR").operator("var_exists").build());
    }


    @Test
    void substringPredicate()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("PARAM").operator("contains")
                .value(N.textNode("mmHg")).valueIsLiteral(true).build());
    }


    @Test
    void change1PositiveAlreadyExists()
    {
        // Task EF: non_empty / is_not_integer convert to `not empty(X)` / `not is_integer(X)` and
        // must lower back to the original negative operator-leaf (does_not_contain is covered by
        // substringPredicateNonBarewordLiteral; is_not_unique_set by the corpus round-trip).
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("AESTDTC").operator("non_empty").build());
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("AGE").operator("is_not_integer").build());
        // Fix #157: the same shape for the date-portion pair — `not is_complete_date_part(X)` must
        // lower back to the is_not_complete_date_part leaf, never to a structural CheckConditionNot
        // (which would flip the unflagged rows into violations).
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("AESTDTC")
                .operator("is_not_complete_date_part").build());
    }


    @Test
    void completeDatePartPositiveLeafRoundTrips()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("AESTDTC")
                .operator("is_complete_date_part").build());
    }


    @Test
    void substringPredicateNonBarewordLiteral()
    {
        // Phase 1: non-bareword substrings ("...", "{", whitespace) emit as quoted literals.
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("library_variable_label")
                .operator("does_not_contain").value(N.textNode("...")).valueIsLiteral(true)
                .build());
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DSTERM").operator("contains")
                .value(N.textNode("INFORMED CONSENT")).valueIsLiteral(true).build());
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("--DTC").operator("contains")
                .value(N.textNode(" ")).valueIsLiteral(true).build());
    }

    // ---- Phase 2: prefix/suffix/type_insensitive/negative modifier fields ----------------------


    @Test
    void prefixEqualToLiteral()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("TSVCDREF").operator("prefix_equal_to")
                        .value(N.textNode("CDISC")).valueIsLiteral(true).prefix(5).build());
    }


    @Test
    void prefixNotEqualToReference()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("dataset_name")
                .operator("prefix_not_equal_to").value(N.textNode("DOMAIN")).prefix(2).build());
    }


    @Test
    void prefixIsNotContainedByOperationRef()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("dataset_name")
                .operator("prefix_is_not_contained_by").value(N.textNode("$list_dataset_names"))
                .prefix(2).build());
    }


    @Test
    void prefixMatchesRegexWithLength()
    {
        // Agreed change #2: the affix-regex operators emit an anchored `prefix(X, n) =~ /^re$/`, so
        // the pattern round-trips with the `^…$` anchors added (the affix operator is anchored, =~
        // is not). Behaviourally exact; the corpus guard accepts it via Expr-IR equivalence.
        assertLeafRoundTripsToAffixRegex(
                CheckConditionLeaf.builder().name("DOMAIN").operator("prefix_matches_regex")
                        .value(N.textNode("(AP|ap)")).valueIsLiteral(true).prefix(2).build(),
                "^(AP|ap)$");
    }


    @Test
    void notPrefixMatchesRegexWithLength()
    {
        assertLeafRoundTripsToAffixRegex(
                CheckConditionLeaf.builder().name("DOMAIN").operator("not_prefix_matches_regex")
                        .value(N.textNode("(AP|ap)")).valueIsLiteral(true).prefix(2).build(),
                "^(AP|ap)$");
    }


    @Test
    void suffixIsNotContainedByOperationRef()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("dataset_name")
                .operator("suffix_is_not_contained_by").value(N.textNode("$list_dataset_names"))
                .suffix(2).build());
    }


    @Test
    void suffixMatchesRegexWithLength()
    {
        assertLeafRoundTripsToAffixRegex(
                CheckConditionLeaf.builder().name("IDVAR").operator("suffix_matches_regex")
                        .value(N.textNode("SEQ")).valueIsLiteral(true).suffix(3).build(),
                "^SEQ$");
    }


    @Test
    void notSuffixMatchesRegexWithLength()
    {
        assertLeafRoundTripsToAffixRegex(
                CheckConditionLeaf.builder().name("QNAM").operator("not_suffix_matches_regex")
                        .value(N.textNode("(OTH)")).valueIsLiteral(true).suffix(3).build(),
                "^(OTH)$");
    }


    @Test
    void typeInsensitiveNotEqualReference()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("IDVARVAL").operator("not_equal_to")
                .value(N.textNode("IDVAR")).typeInsensitive(true).build());
    }


    @Test
    void invalidDurationNegative()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("TSVAL").operator("invalid_duration")
                .negative(true).build());
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("TSVAL").operator("invalid_duration")
                .negative(false).build());
    }

    // ---- Phase 3: operand surface (substitution + quoted whitespace) ---------------------------


    @Test
    void substitutionNameOperand()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("ADSL.AP${APERIOD:%02d}SDT")
                .operator("var_not_exists").build());
    }


    @Test
    void substitutionValueOperand()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("APERSDT").operator("not_equal_to")
                .value(N.textNode("ADSL.AP${APERIOD:%02d}SDT")).build());
    }


    @Test
    void wildcardSubstitutionValueOperand()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("TRTA")
                .operator("is_not_contained_by").value(N.textNode("ADSL.TRT${*}A")).build());
    }


    @Test
    void quotedWhitespaceReferenceValue()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DSCAT").operator("equal_to")
                .value(N.textNode("PROTOCOL MILESTONE")).build());
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("TSVCDREF").operator("not_equal_to")
                .value(N.textNode("ISO 3166-1 alpha-3")).build());
    }


    @Test
    void caseInsensitiveReference()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("--CAT")
                .operator("equal_to_case_insensitive").value(N.textNode("--DECOD")).build());
    }


    @Test
    void compositeAllRoundTrips()
    {
        CheckConditionLeaf a = CheckConditionLeaf.builder().name("IECAT").operator("equal_to")
                .value(N.textNode("INCLUSION")).valueIsLiteral(true).build();
        CheckConditionLeaf b = CheckConditionLeaf.builder().name("IEORRES").operator("not_equal_to")
                .value(N.textNode("N")).valueIsLiteral(true).build();
        CheckCondition rt = roundTrip(new CheckConditionAll(List.of(a, b)));
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, rt);
        assertEquals(2, all.getConditions().size());
    }


    /**
     * Principle #5: the canonical printed boolean form is all-keyword ({@code and}/{@code or}), and
     * both the keyword and symbolic spellings still parse to the same {@link CheckCondition} tree
     * (the lexer maps {@code &&}/{@code ||} to the same {@code AND}/{@code OR} tokens as
     * {@code and}/{@code or}).
     */
    @Test
    void canonicalBooleanPrintUsesKeywords()
    {
        CheckConditionLeaf a = CheckConditionLeaf.builder().name("A").operator("equal_to")
                .value(N.textNode("1")).valueIsLiteral(true).build();
        CheckConditionLeaf b = CheckConditionLeaf.builder().name("B").operator("equal_to")
                .value(N.textNode("2")).valueIsLiteral(true).build();

        assertEquals("A == \"1\" and B == \"2\"",
                ExpressionPrinter.print(CheckToExpr.toExpr(new CheckConditionAll(List.of(a, b)))),
                "canonical conjunction print");
        assertEquals("A == \"1\" or B == \"2\"",
                ExpressionPrinter.print(CheckToExpr.toExpr(new CheckConditionAny(List.of(a, b)))),
                "canonical disjunction print");

        assertEquals(CheckExpressionParser.parse("A == \"1\" && B == \"2\""),
                CheckExpressionParser.parse("A == \"1\" and B == \"2\""),
                "&& and 'and' parse identically");
        assertEquals(CheckExpressionParser.parse("A == \"1\" || B == \"2\""),
                CheckExpressionParser.parse("A == \"1\" or B == \"2\""),
                "|| and 'or' parse identically");
    }


    @Test
    void notEqualToCaseInsensitiveWhitespace()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("MBSTRESC")
                .operator("not_equal_to_case_insensitive").value(N.textNode("NO GROWTH")).build());
    }


    @Test
    void caseInsensitiveMembershipAllUpper()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("ACTARMCD")
                .operator("is_not_contained_by_case_insensitive")
                .value(N.arrayNode().add("SCRNFAIL").add("NOTASSGN").add("NOTTRT")).build());
    }


    @Test
    void caseInsensitiveMembershipMixedCase()
    {
        // Agreed change #3: the converter pre-uppercases the membership terms (the operator
        // compares
        // them case-insensitively at runtime — both the cell and the value set are upcased with
        // Locale.ROOT), so a mixed-case input round-trips to its upper-cased form. This is exact
        // behaviourally; the corpus round-trip comparator compares these operators' values
        // case-insensitively.
        assertLeafRoundTripsToValue(
                CheckConditionLeaf.builder().name("ARM")
                        .operator("is_not_contained_by_case_insensitive")
                        .value(N.arrayNode().add("Screen Failure").add("Not Assigned")).build(),
                N.arrayNode().add("SCREEN FAILURE").add("NOT ASSIGNED"));
        assertLeafRoundTripsToValue(
                CheckConditionLeaf.builder().name("ARM")
                        .operator("is_contained_by_case_insensitive")
                        .value(N.arrayNode().add("Screen Failure").add("Not Treated")).build(),
                N.arrayNode().add("SCREEN FAILURE").add("NOT TREATED"));
    }


    @Test
    void targetIsNotSortedBy()
    {
        var desc = N.objectNode();
        desc.put("name", "--STDTC");
        desc.put("null_position", "last");
        desc.put("sort_order", "asc");
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("--SEQ").operator("target_is_not_sorted_by")
                        .value(N.arrayNode().add(desc)).within(N.textNode("USUBJID")).build());
    }


    @Test
    void regexWithBackslashClasses()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("--DTC").operator("matches_regex")
                .value(N.textNode("^\\d{4}-\\d{2}-\\d{2}$")).valueIsLiteral(true).build());
    }


    @Test
    void regexWithEscapedSlash()
    {
        // Phase 4a: '\/' in the pattern now round-trips via the \\-escape in the /regex/ surface.
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("variable_label").operator("not_matches_regex")
                        .value(N.textNode("(a|and\\/or|b)")).valueIsLiteral(true).build());
    }

    // ---- Phase 5: arithmetic comparison operators ---------------------------------------------


    @Test
    void notEqualToDivide()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("R2BASE").operator("not_equal_to_divide")
                        .value(N.arrayNode().add("AVAL").add("BASE")).build());
    }


    @Test
    void notEqualToSubtract()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("CHG").operator("not_equal_to_subtract")
                        .value(N.arrayNode().add("AVAL").add("BASE")).build());
    }


    @Test
    void notEqualToPctChange()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("PCHG").operator("not_equal_to_pctchg")
                        .value(N.arrayNode().add("AVAL").add("BASE")).build());
    }


    @Test
    void arithmeticPrintsInfix()
    {
        // Confirm the canonical printed form is infix arithmetic.
        CheckConditionLeaf divide = CheckConditionLeaf.builder().name("R2BASE")
                .operator("not_equal_to_divide").value(N.arrayNode().add("AVAL").add("BASE"))
                .build();
        assertEquals("R2BASE != (AVAL / BASE)", ExpressionPrinter.print(CheckToExpr.toExpr(divide)),
                "divide infix print");
        CheckConditionLeaf pct = CheckConditionLeaf.builder().name("PCHG")
                .operator("not_equal_to_pctchg").value(N.arrayNode().add("AVAL").add("BASE"))
                .build();
        assertEquals("PCHG != (((AVAL - BASE) / BASE) * 100)",
                ExpressionPrinter.print(CheckToExpr.toExpr(pct)), "pctchg infix print");
    }


    @Test
    void unsupportedOperatorThrows()
    {
        CheckConditionLeaf l = CheckConditionLeaf.builder().name("USUBJID")
                .operator("a_totally_unmapped_operator").build();
        assertThrows(ExpressionException.class, () -> CheckToExpr.toExpr(l));
    }


    @Test
    void leafWithUnsupportedFieldThrows()
    {
        // 'within' on an operator that does not support it (equal_to) has no surface.
        CheckConditionLeaf l = CheckConditionLeaf.builder().name("USUBJID").operator("equal_to")
                .value(N.textNode("Y")).valueIsLiteral(true).within(N.textNode("DOMAIN")).build();
        assertThrows(ExpressionException.class, () -> CheckToExpr.toExpr(l));
    }

    // ---- Phase 4a: non-row-level function operators --------------------------------------------


    @Test
    void hasMultipleValuesForNoWithin()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("ACYCLEC")
                .operator("has_multiple_values_for").value(N.textNode("ACYCLE")).build());
    }


    @Test
    void hasMultipleValuesForIncludeEmpty()
    {
        // Fix #121: the include_empty switch round-trips as a boolean kwarg.
        CheckConditionLeaf l = CheckConditionLeaf.builder().name("ANLzzFN")
                .operator("has_multiple_values_for").value(N.textNode("ANLzzFL")).includeEmpty(true)
                .build();
        assertLeafRoundTrips(l);
        assertEquals("has_multiple_values_for(ANLzzFN, ANLzzFL, include_empty=true)",
                ExpressionPrinter.print(CheckToExpr.toExpr(l)), "include_empty kwarg print");
    }


    @Test
    void isInconsistentAcrossDatasetIncludeEmpty()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("TRTAGyN")
                .operator("is_inconsistent_across_dataset").value(N.arrayNode().add("TRTAGy"))
                .includeEmpty(true).build());
    }


    @Test
    void includeEmptyOnUnsupportedOperatorThrows()
    {
        // Only the two consistency operators consume include_empty; on any other function
        // operator the field would be dead weight, so CheckToExpr fails loudly.
        CheckConditionLeaf functionOp = CheckConditionLeaf.builder().name("NAME")
                .operator("is_unique_set").value(N.arrayNode().add("K")).includeEmpty(true).build();
        assertThrows(ExpressionException.class, () -> CheckToExpr.toExpr(functionOp));
        // ...and on a row-level operator it is rejected by the group-field guard.
        CheckConditionLeaf rowOp = CheckConditionLeaf.builder().name("DTHFL").operator("equal_to")
                .value(N.textNode("Y")).valueIsLiteral(true).includeEmpty(true).build();
        assertThrows(ExpressionException.class, () -> CheckToExpr.toExpr(rowOp));
    }


    @Test
    void hasMultipleValuesForWithinString()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("AVISITN").operator("has_multiple_values_for")
                        .value(N.textNode("AVISIT")).within(N.textNode("PARAMCD")).build());
    }


    @Test
    void hasMultipleValuesForWithinArray()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("BASE")
                .operator("has_multiple_values_for").value(N.textNode("BASEC"))
                .within(N.arrayNode().add("USUBJID").add("PARAMCD")).build());
    }


    @Test
    void isNotUniqueSetVariants()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("USUBJID").operator("is_not_unique_set").build());
        // Plan A (2026-08-23) §3.10 item 4: a scalar TEXT value — the pre-flattening
        // two-positional spelling f(A, B) — raises to f([A, B]) and lowers back to a ONE-element
        // ARRAY value. The leaf wire form moves for exactly that shape (17 corpus sites; their
        // L1 fingerprint is re-based deliberately in Phase 2); the verdict does not.
        assertLeafRoundTripsToValue(CheckConditionLeaf.builder().name("USUBJID")
                .operator("is_not_unique_set").value(N.textNode("DOMAIN")).build(),
                N.arrayNode().add("DOMAIN"));
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("DSCAT").operator("is_not_unique_set")
                        .value(N.arrayNode().add("USUBJID").add("EPOCH")).build());
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("--REPNUM")
                .operator("is_not_unique_set")
                .value(N.arrayNode().add("USUBJID").add("--TESTCD").add("$TIMING_VARIABLES"))
                .regex("^\\d{4}-\\d{2}-\\d{2}").build());
    }


    /**
     * The text round trip of the canonical single-list spelling (owner requirement #1, 2026-08-23):
     * {@code expr → leaf → expr} is byte-stable for every §2.3 call-site shape's NEW spelling, and
     * the leaf carries {@code name = members[0]}, {@code value = members[1..]} (D-2 — the split
     * {@code RuleFingerprint.guardSensitiveNames} and {@code SilencingGuardScan} read).
     */
    @Test
    void uniqueSetListOperandRoundTripsByteStable()
    {
        // The five §2.3 shapes, flattened: keys=, two-positional, degenerate one-member,
        // keys= + regex=, and the $-ref member (plus the repeated-member CDISC-AD0688 shape, kept
        // verbatim — no dedup).
        for (String text : List.of("not is_unique_set([USUBJID, DOMAIN])",
                "not is_unique_set([IETESTCD, DOMAIN])", "not is_unique_set([ETCD])",
                "not is_unique_set([--REPNUM, USUBJID, --TESTCD, $TIMING_VARIABLES],"
                        + " regex=\"^\\\\d{4}-\\\\d{2}\")",
                "not is_unique_set([USUBJID, --TESTCD, $natural_key])",
                "not is_unique_set([USUBJID, USUBJID, SPDEVID])",
                "is_unique_set([TSSEQ, TSPARMCD])",
                "not is_unique_set([USUBJID, DSCAT], keep_missings=false)"))
        {
            CheckCondition lowered = ExprLowering
                    .toCheckCondition(CheckExpressionParser.parse(text));
            assertEquals(text, ExpressionPrinter.print(CheckToExpr.toExpr(lowered)),
                    "byte-stable text round trip");
        }
        // The negative twin's canonical text is the house form `not is_unique_set([…])`
        // (ruling 1) — its own spelling is accepted and re-raised to that form.
        assertEquals("not is_unique_set([TSSEQ, TSPARMCD])",
                ExpressionPrinter.print(CheckToExpr.toExpr(ExprLowering.toCheckCondition(
                        CheckExpressionParser.parse("is_not_unique_set([TSSEQ, TSPARMCD])")))));
        CheckConditionLeaf leaf = assertInstanceOf(CheckConditionLeaf.class, ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("not is_unique_set([A, B, C])")));
        assertEquals("is_not_unique_set", leaf.getOperator());
        assertEquals("A", leaf.getName(), "D-2: member 0 is the leaf name");
        assertEquals(N.arrayNode().add("B").add("C"), leaf.getValue(),
                "D-2: members 1.. are the array value");
        CheckConditionLeaf one = assertInstanceOf(CheckConditionLeaf.class,
                ExprLowering.toCheckCondition(CheckExpressionParser.parse("is_unique_set([A])")));
        assertEquals("A", one.getName());
        assertEquals(null, one.getValue(), "a one-member list carries no value");
        // A numeric leaf value has no member form — the raise declines loudly.
        assertThrows(ExpressionException.class, () -> CheckToExpr.toExpr(CheckConditionLeaf
                .builder().name("A").operator("is_not_unique_set").value(N.numberNode(5)).build()));
        // A positional list is NOT a legal first operand on any other function operator — the
        // lowering declines (the deserializer then keeps the Check native).
        assertThrows(ExpressionException.class, () -> ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("has_multiple_values_for([A, B])")));
        // f([]) and a list operand mixed with keys= have no leaf.
        assertThrows(ExpressionException.class, () -> ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("not is_unique_set([])")));
        assertThrows(ExpressionException.class, () -> ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("not is_unique_set([A], keys=[B])")));
    }


    /**
     * Plan A Phase 2 (ruling 2, no deprecation window): the retired {@code f(A, keys=[…])} /
     * {@code f(A, B)} / {@code f(A)} spellings no longer lower for the pair — the refusal is what
     * keeps such a Check native so {@code RulePackageLoader.validateInlineUniqueSetShape} can turn
     * it into the load error. The single-list form lowers to the D-2 leaf and re-raises to itself.
     */
    @Test
    void uniqueSetRetiredSpellingsAreRefusedByLowering()
    {
        for (String retired : List.of("not is_unique_set(A, keys=[B, C])",
                "not is_unique_set(A, B)", "not is_unique_set(A)", "is_not_unique_set(A, keys=[B])",
                "not is_unique_set(A, keys=[B], regex=\"^x\")"))
        {
            ExpressionException e = assertThrows(ExpressionException.class,
                    () -> ExprLowering.toCheckCondition(CheckExpressionParser.parse(retired)),
                    retired);
            assertTrue(e.getMessage().contains("one list operand")
                    || e.getMessage().contains("must be a list literal"), e.getMessage());
        }
        CheckCondition newForm = ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("not is_unique_set([A, B, C])"));
        assertEquals("not is_unique_set([A, B, C])",
                ExpressionPrinter.print(CheckToExpr.toExpr(newForm)));
        // The sibling's keys= is untouched by the refusal.
        assertNotNull(ExprLowering.toCheckCondition(
                CheckExpressionParser.parse("not is_inconsistent_across_dataset(A, keys=[B])")));
    }


    @Test
    void isNotUniqueValueNoValue()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("TXSEQ").operator("is_not_unique_value").build());
    }


    @Test
    void hasNotEqualLengthNumber()
    {
        // change #5: has_not_equal_length(X, n) -> len(X) != n. Like longer_than/shorter_than the
        // length literal lowers back with value_is_literal set, so the fixture carries that flag.
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("DOMAIN").operator("has_not_equal_length")
                        .value(N.numberNode(4L)).valueIsLiteral(true).build());
    }


    @Test
    void hasEqualLengthNumber()
    {
        // change #5: has_equal_length(X, n) -> len(X) == n.
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DOMAIN")
                .operator("has_equal_length").value(N.numberNode(4L)).valueIsLiteral(true).build());
    }


    @Test
    void isInconsistentAcrossDatasetArray()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("--TPT")
                .operator("is_inconsistent_across_dataset")
                .value(N.arrayNode().add("--TPTNUM").add("VISITNUM").add("--TPTREF")).build());
    }


    @Test
    void sharesNoElementsWithArray()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("$datasets")
                .operator("shares_no_elements_with").value(N.arrayNode().add("EX")).build());
    }


    @Test
    void presentOnMultipleRowsWithin()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("DSDECOD")
                .operator("present_on_multiple_rows_within").within(N.textNode("USUBJID")).build());
    }


    @Test
    void emptyWithinExceptLastRow()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("SEENDTC")
                .operator("empty_within_except_last_row").value(N.textNode("USUBJID"))
                .ordering("SESTDTC").build());
    }


    @Test
    void doesNotHaveNextCorrespondingRecord()
    {
        assertLeafRoundTrips(CheckConditionLeaf.builder().name("SEENDTC")
                .operator("does_not_have_next_corresponding_record").value(N.textNode("SESTDTC"))
                .ordering("SESEQ").within(N.textNode("USUBJID")).build());
    }


    @Test
    void doesNotEqualStringPartRegex()
    {
        assertLeafRoundTrips(
                CheckConditionLeaf.builder().name("RDOMAIN").operator("does_not_equal_string_part")
                        .value(N.textNode("$dataset_name")).regex(".{4}(..).*").build());
    }

}
