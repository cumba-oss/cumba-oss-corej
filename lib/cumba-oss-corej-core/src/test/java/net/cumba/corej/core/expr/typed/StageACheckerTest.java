package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * The stage-A checker (spec §2, phase 2): the typed AST it builds, the checks it arms, and the
 * phase-2 constraint that armed checks park through the existing {@code loadError} channel while
 * observe-only findings never do.
 */
class StageACheckerTest
{

    private static StageAReport check(String expression)
    {
        return check(new Rule(), expression);
    }


    private static StageAReport check(Rule rule, String expression)
    {
        return StageAChecker.check(rule, levels(CheckExpressionParser.parse(expression)));
    }


    private static SequencedMap<Severity, Expr> levels(Expr expr)
    {
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, expr);
        return levels;
    }


    private static List<StageAErrorKind> kinds(StageAReport report)
    {
        return report.findings().stream().map(StageAFinding::kind).distinct().toList();
    }


    private static TypedExpr root(StageAReport report)
    {
        return report.typedLevels().get(Severity.ERROR);
    }

    // ------------------------------------------------------------------
    // Typed AST shape
    // ------------------------------------------------------------------


    @Test
    void aCleanRowRuleTypesCleanAtRecordLevel()
    {
        StageAReport report = check("empty(AEOUT) and AESEQ > 5");
        assertEquals(List.of(), report.findings());
        TypedExpr root = root(report);
        assertEquals(Primitive.BOOLEAN, root.type());
        assertEquals(Level.RECORD, root.level());
        assertEquals(2, root.children().size());
    }


    @Test
    void literalsAreStudyLevelConstants()
    {
        TypedExpr root = root(check("\"A\" == \"B\""));
        assertEquals(Level.STUDY, root.level());
        assertEquals(Primitive.STRING, root.children().get(0).type());
    }


    @Test
    void theCursorCellsAreTheProductCells()
    {
        assertEquals(Level.VARIABLE_VALUE, root(check("value() == \"X\"")).level());
        assertEquals(Level.VARIABLE_METADATA, root(check("var_label(\"DATA\") == \"L\"")).level());
        // an explicit variable name makes the accessor a dataset fact (DomainScan's rule)
        assertEquals(Level.DATASET, root(check("var_label(\"AESEV\", \"DATA\") == \"L\"")).level());
        assertEquals(Level.DATASET, root(check("ds_exists(\"EX\")")).level());
        assertEquals(Level.DATASET, root(check("record_count() > 100")).level());
    }


    @Test
    void aGroupedInlineOperationCarriesGroupGranularity()
    {
        TypedExpr root = root(check("max(AESEQ, group=[USUBJID]) == AESEQ"));
        // group(K) ⊔ record = record (the comparison is per row)
        assertEquals(Level.RECORD, root.level());
        TypedExpr grouped = root.children().get(0);
        assertEquals(new Granularity.Group(java.util.Set.of("USUBJID")), grouped.granularity());
        assertEquals(Cursor.ABSENT, grouped.cursor());
    }


    @Test
    void aGroupedBindingReferenceCarriesGroupGranularity()
    {
        Rule rule = new Rule();
        Operation op = new Operation();
        op.setId("$m");
        op.setOperator("max");
        op.setName("AESEQ");
        op.setGroup(List.of("USUBJID"));
        rule.setOperations(List.of(op));
        TypedExpr root = root(check(rule, "$m == AESEQ"));
        assertEquals(new Granularity.Group(java.util.Set.of("USUBJID")),
                root.children().get(0).granularity());
    }


    @Test
    void modeTagsAreErasedIdentities()
    {
        // D91f (i), narrowed by phase 3b: only str() is still an erased passthrough (live until
        // phase 7, D97c); date() is a real conversion now — see the temporal-surface tests.
        StageAReport report = check("str(AESTDTC) == str(AEENDTC)");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.COLUMN_REFERENCE, root(report).children().get(0).type());
    }


    @Test
    void numIsANumericConversion()
    {
        StageAReport report = check("num(AESEQ) > 5");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.NUMBER, root(report).children().get(0).type());
    }

    // ------------------------------------------------------------------
    // Armed checks
    // ------------------------------------------------------------------


    @Test
    void aHeterogeneousListLiteralIsAStageAError()
    {
        StageAReport report = check("AESEV in [1, \"a\"]");
        assertEquals(List.of(StageAErrorKind.HETEROGENEOUS_LIST), kinds(report));
        assertTrue(StageAErrorKind.HETEROGENEOUS_LIST.armed());
    }


    @Test
    void homogeneousListsOfEitherDualityAreClean()
    {
        // §1.2: same brackets, opposite element types — both legal.
        assertEquals(List.of(), check("is_unique_set([USUBJID, AESEQ])").findings());
        assertEquals(List.of(), check("AESEV in [\"MILD\", \"SEVERE\"]").findings());
        assertEquals(List.of(), check("AESEQ in [1, 2, 3]").findings());
    }


    @Test
    void anIllegalAttributeLevelPairIsAStageAError()
    {
        // var_role has no DATA cell (the 78-cell table, 50 legal).
        assertEquals(List.of(StageAErrorKind.METADATA_LEVEL_ILLEGAL),
                kinds(check("var_role(\"DATA\") == \"IDENTIFIER\"")));
        // unknown level spelling
        assertEquals(List.of(StageAErrorKind.METADATA_LEVEL_ILLEGAL),
                kinds(check("var_label(\"BOGUS\") == \"L\"")));
        // non-literal level
        assertEquals(List.of(StageAErrorKind.METADATA_LEVEL_ILLEGAL),
                kinds(check("var_label(upper(AESEV)) == \"L\"")));
        assertTrue(StageAErrorKind.METADATA_LEVEL_ILLEGAL.armed());
    }


    @Test
    void aComputedAccessorNameIsAStageAError()
    {
        // §1.2: every name position outside the three dynamic forms needs a static name — the
        // typed mirror of ExprCompiler:4583.
        assertEquals(List.of(StageAErrorKind.NON_STATIC_NAME),
                kinds(check("var_label(upper(AESEV), \"DATA\") == \"L\"")));
        // the cursor forms are fine
        assertEquals(List.of(), check("var_label(varname(), \"DATA\") == \"L\"").findings());
        assertEquals(List.of(), check("var_label(variable_name, \"DATA\") == \"L\"").findings());
    }


    @Test
    void aWrongArityIsAStageAError()
    {
        // substring is registered at arities 2 and 3 only.
        assertEquals(List.of(StageAErrorKind.ARITY), kinds(check("substring(AEOUT) == \"X\"")));
        assertEquals(List.of(), check("substring(AEOUT, 1, 4) == \"X\"").findings());
    }


    @Test
    void theExcludedGroupCursorCellIsAStageAError()
    {
        // A grouped aggregate joined with a variable-cursor read lands on group × cursor (D67).
        StageAReport report = check(
                "max(AESEQ, group=[USUBJID]) == 1 and var_label(\"DATA\") == \"L\"");
        assertEquals(List.of(StageAErrorKind.LEVEL_EXCLUDED_GROUP_CURSOR), kinds(report));
        assertTrue(StageAErrorKind.LEVEL_EXCLUDED_GROUP_CURSOR.armed());
    }


    @Test
    void aForwardOrSelfBindingReferenceIsAStageAError()
    {
        Rule rule = new Rule();
        Operation first = new Operation();
        first.setId("$a");
        first.setExpression("$b");
        Operation second = new Operation();
        second.setId("$b");
        second.setOperator("min");
        second.setName("AESEQ");
        rule.setOperations(List.of(first, second));
        assertEquals(List.of(StageAErrorKind.FORWARD_OR_CYCLIC_BINDING),
                kinds(check(rule, "$a == 1")));

        Rule selfRule = new Rule();
        Operation self = new Operation();
        self.setId("$a");
        self.setExpression("$a");
        selfRule.setOperations(List.of(self));
        StageAReport report = check(selfRule, "$a == 1");
        assertEquals(List.of(StageAErrorKind.FORWARD_OR_CYCLIC_BINDING), kinds(report));
        assertTrue(report.findings().get(0).message().contains("itself"));
    }


    @Test
    void backwardBindingReferencesAreClean()
    {
        Rule rule = new Rule();
        Operation first = new Operation();
        first.setId("$a");
        first.setOperator("max");
        first.setName("AESEQ");
        Operation second = new Operation();
        second.setId("$b");
        second.setExpression("$a");
        rule.setOperations(List.of(first, second));
        assertEquals(List.of(), check(rule, "$b == 1").findings());
    }


    /**
     * Phase 6b — D110g(iii): two {@code Match_Datasets} entries under one {@code Name} are the
     * ambiguity load-error the 2026-08 plan specified; the join lookup is name-keyed last-wins, so
     * the shape used to silently read only the surviving entry.
     */
    @Test
    void duplicateMatchDatasetNameIsAStageAError()
    {
        Rule rule = new Rule();
        MatchDataset first = new MatchDataset();
        first.setName("AE");
        first.setKeys(List.of("USUBJID"));
        first.setJoinType("left");
        MatchDataset second = new MatchDataset();
        second.setName("AE");
        second.setKeys(List.of("USUBJID", "AESEQ"));
        second.setJoinType("left");
        rule.setMatchDatasets(List.of(first, second));
        StageAReport report = check(rule, "AE.AESER == \"Y\"");
        assertTrue(kinds(report).contains(StageAErrorKind.DUPLICATE_MATCH_DATASET_NAME),
                report.findings().toString());
        assertTrue(report.findings().stream().map(Object::toString)
                .anyMatch(s -> s.contains("share the Name 'AE'")), report.findings().toString());

        // distinct names stay clean
        second.setName("DM");
        assertEquals(List.of(), check(rule, "AE.AESER == \"Y\"").findings().stream()
                .filter(f -> f.kind() == StageAErrorKind.DUPLICATE_MATCH_DATASET_NAME).toList());
    }


    private static Rule ruleJoining(String name,
            @SuppressWarnings("SameParameterValue") String joinType, String... keys)
    {
        Rule rule = new Rule();
        MatchDataset match = new MatchDataset();
        match.setName(name);
        if (keys.length > 0)
        {
            match.setKeys(List.of(keys));
        }
        match.setJoinType(joinType);
        rule.setMatchDatasets(List.of(match));
        return rule;
    }


    @Test
    void matchedFlagUnderAnInnerJoinIsAStageAError()
    {
        // no Join_Type ⇒ inner, the default (D88e: 242 of 266 entries). Since 5b-J the parser
        // spells the flag itself.
        Rule rule = ruleJoining("AE", null, "USUBJID");
        StageAReport report = check(rule, "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INNER_JOIN), kinds(report));
        assertTrue(report.findings().get(0).toString().contains("AE._matched_"));
    }


    @Test
    void matchedFlagUnderAnExplicitInnerJoinIsAStageAError()
    {
        StageAReport report = check(ruleJoining("AE", "inner", "USUBJID"), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INNER_JOIN), kinds(report));
    }


    @Test
    void matchedFlagUnderALeftJoinIsClean()
    {
        StageAReport report = check(ruleJoining("AE", "left", "USUBJID"),
                "not AE._matched_ and DTHFL != \"Y\"");
        assertEquals(List.of(), report.findings());
        // and it types as the one statically-boolean column, at record level
        assertEquals(Primitive.BOOLEAN, root(report).children().get(0).children().get(0).type());
    }


    @Test
    void matchedFlagOnAnotherEntryDoesNotTripTheInnerOne()
    {
        // Two entries, only DM is inner — the flag names AE (left), so no error (the pre-5b-J
        // approximation would have fired on ANY inner entry).
        Rule rule = new Rule();
        MatchDataset ae = new MatchDataset();
        ae.setName("AE");
        ae.setKeys(List.of("USUBJID"));
        ae.setJoinType("left");
        MatchDataset dm = new MatchDataset();
        dm.setName("DM");
        dm.setKeys(List.of("USUBJID"));
        rule.setMatchDatasets(List.of(ae, dm));
        assertEquals(List.of(), check(rule, "not AE._matched_").findings());
    }


    @Test
    void matchedFlagWithNoDefiningEntryIsInvalid()
    {
        StageAReport report = check(ruleJoining("DM", "left", "USUBJID"), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INVALID), kinds(report));
        assertTrue(report.armedFindings().size() == 1);
    }


    @Test
    void matchedFlagWithNoEntriesAtAllIsInvalid()
    {
        StageAReport report = check(new Rule(), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INVALID), kinds(report));
    }


    @Test
    void matchedFlagOnAKeylessEntryIsInvalid()
    {
        StageAReport report = check(ruleJoining("AE", "left"), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INVALID), kinds(report));
    }


    @Test
    void matchedFlagOnAChildEntryIsInvalid()
    {
        Rule rule = ruleJoining("AE", "left", "USUBJID");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        StageAReport report = check(rule, "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INVALID), kinds(report));
    }


    @Test
    void unboundFlagIsDeferredWhileATemplateEntryRemains()
    {
        // SUPP-- may specialise to SUPPAE; the load-time pass must not park on a guess — the
        // specialised per-dataset pass re-checks with concrete names.
        StageAReport report = check(ruleJoining("SUPP--", "left", "USUBJID"),
                "not SUPPAE._matched_");
        assertEquals(List.of(), report.findings());
    }

    // ------------------------------------------------------------------
    // DOTTED_REF_UNDECLARED (PLAN-null-free-value-channel §9c) — the value-read sibling of
    // MATCHED_FLAG_INVALID's dangling-qualifier arm.
    //
    // ⛔⛔ This kind has a ZERO population over the shipped corpus (3 940 check files, 192
    // (rule, qualifier) pairs, every one declared), so a green corpus proves NOTHING about it.
    // These four cases ARE the instrument: the negative control below is the only evidence the
    // guard fires at all, and it was verified to RED with the guard's `find` call removed.
    // ------------------------------------------------------------------


    @Test
    void aDottedRefWhoseQualifierIsNotDeclaredIsAStageAError()
    {
        // NEGATIVE CONTROL. `DM.AGE > 30` with Match_Datasets: [{Name: AE}] — no join is ever
        // built for DM, so the operand reads its type default on every row and the check can
        // never fire. Silently a PASS today.
        StageAReport report = check(ruleJoining("AE", "left", "USUBJID"), "DM.AGE > 30");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED), kinds(report));
        // ⛔ Observe-only ON PURPOSE — three of this repo's own fixtures still carry the shape, so
        // the kind fails the arming discipline's zero-population test. This assertion is the pin
        // that keeps that deliberate, not forgotten: flipping the kind to armed reds HERE as well
        // as in the three fixtures, so the flip cannot happen without reading the reason.
        assertEquals(List.of(), report.armedFindings());
        String message = report.findings().get(0).toString();
        assertTrue(message.contains("DM.AGE"), message);
        assertTrue(message.contains("no Match_Datasets entry named DM"), message);
    }


    @Test
    void aDottedRefWithNoMatchDatasetsAtAllIsAStageAError()
    {
        // The same error in its commonest shape — the author forgot the Match_Datasets block
        // entirely. Reaches the check only because checkDottedRefs runs BEFORE
        // checkMatchDatasets' empty-list early return.
        StageAReport report = check(new Rule(), "DM.AGE > 30");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED), kinds(report));
    }


    @Test
    void aDeclaredDottedRefIsClean()
    {
        // POSITIVE CONTROL — the 192 shipped (rule, qualifier) pairs all have this shape. The
        // absent-from-the-RUN case is a different cause entirely and must stay silent here: it is
        // legitimate and takes the rule-expected default value.
        StageAReport report = check(ruleJoining("DM", "left", "USUBJID"), "DM.AGE > 30");
        assertEquals(List.of(), report.findings());
    }


    @Test
    void anUndeclaredDottedRefIsDeferredWhileATemplateEntryRemains()
    {
        // Same deferral as the _matched_ flag's, and by the SAME predicate
        // (anyTemplateEntryName): SUPP-- may specialise to SUPPDM, so the load-time pass must not
        // park on a guess.
        StageAReport report = check(ruleJoining("SUPP--", "left", "USUBJID"), "SUPPDM.QVAL > 30");
        assertEquals(List.of(), report.findings());
    }


    @Test
    void aTemplatedDottedNameNeverReachesTheUndeclaredCheck()
    {
        // MEASURED, not reasoned: OperandClassifier tests isWildcard FIRST, so `ADSL.PH${*}SDT`
        // classifies as WILDCARD_COLUMN and is not a DOTTED_REF at all. ADSL is undeclared here
        // and the guard must still stay silent — which is why the guard needs no exclusion of its
        // own for templated names.
        Rule rule = ruleJoining("AE", "left", "USUBJID");
        StageAReport report = check(rule, "PHSDTM not in ADSL.PH${*}SDT");
        assertTrue(!kinds(report).contains(StageAErrorKind.DOTTED_REF_UNDECLARED),
                String.valueOf(report.findings()));
    }


    @Test
    void anUndeclaredDottedRefInsideAPresenceCallIsClean()
    {
        // ⛔ NOT an authoring error: var_exists(DM.ARM) is a pure metadata question against DM's
        // own inventory — no join is built and none is needed. Three tests in this repo pin that
        // shape (StudyRuleClassifierTest ×2, AbsentDatasetSkipTest's split-domain F2 case), and
        // they are what turned this from a reasoned exclusion into a measured one.
        StageAReport report = check(ruleJoining("AE", "left", "USUBJID"),
                "var_exists(DM.ARM) and empty(X)");
        assertEquals(List.of(), report.findings());
    }


    @Test
    void anUndeclaredDottedRefInABindingExpressionIsAlsoCaught()
    {
        // Bindings carry 0 dotted refs in the shipped corpus, so this arm too is instrument-only.
        Rule rule = ruleJoining("AE", "left", "USUBJID");
        Operation op = new Operation();
        op.setId("$x");
        op.setOperator("max");
        op.setExpression("DM.AGE");
        rule.setOperations(List.of(op));
        StageAReport report = check(rule, "AGE > 0");
        assertTrue(kinds(report).contains(StageAErrorKind.DOTTED_REF_UNDECLARED),
                String.valueOf(report.findings()));
    }


    private static Rule ruleWithFilter(String filter)
    {
        Rule rule = ruleJoining("AE", "left", "USUBJID");
        rule.getMatchDatasets().get(0).setFilter(filter);
        return rule;
    }


    @Test
    void aPlainColumnFilterIsClean()
    {
        StageAReport report = check(ruleWithFilter("AEOUT == \"FATAL\" and non_empty(AESEQ)"),
                "not AE._matched_");
        assertEquals(List.of(), report.findings());
    }


    @Test
    void aDottedFilterReferenceIsALeftSideError()
    {
        StageAReport report = check(ruleWithFilter("AEOUT == DM.ARM"), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.FILTER_LEFT_REFERENCE), kinds(report));
    }


    @Test
    void anOperationRefInAFilterIsALeftSideError()
    {
        StageAReport report = check(ruleWithFilter("AEOUT in $fatal_terms"), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.FILTER_LEFT_REFERENCE), kinds(report));
    }


    @Test
    void aWildcardInAFilterIsInvalid()
    {
        StageAReport report = check(ruleWithFilter("--OUT == \"FATAL\""), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.FILTER_INVALID), kinds(report));
    }


    @Test
    void anUnparseableFilterIsInvalid()
    {
        StageAReport report = check(ruleWithFilter("AEOUT == "), "not AE._matched_");
        assertEquals(List.of(StageAErrorKind.FILTER_INVALID), kinds(report));
        assertTrue(report.findings().get(0).toString().contains("does not parse"));
    }


    @Test
    void aFilterOnAKeylessEntryIsInvalid()
    {
        Rule rule = ruleJoining("AE", "left");
        rule.getMatchDatasets().get(0).setFilter("AEOUT == \"FATAL\"");
        // the flagless Check keeps the keyless entry itself legal — only the filter is checked
        // (DTHFL, not AETERM: an AE-prefixed bare ref would trip the D62 observe heuristic)
        StageAReport report = check(rule, "empty(DTHFL)");
        assertEquals(List.of(StageAErrorKind.FILTER_INVALID), kinds(report));
    }


    @Test
    void aFilterOnAChildEntryIsInvalid()
    {
        Rule rule = ruleJoining("AE", "left", "USUBJID");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        rule.getMatchDatasets().get(0).setFilter("AEOUT == \"FATAL\"");
        StageAReport report = check(rule, "empty(DTHFL)");
        assertEquals(List.of(StageAErrorKind.FILTER_INVALID), kinds(report));
    }


    @Test
    void matchedFlagInValuePositionIsInvalid()
    {
        StageAReport report = check(ruleJoining("AE", "left", "USUBJID"), "AE._matched_ == \"Y\"");
        assertTrue(kinds(report).contains(StageAErrorKind.MATCHED_FLAG_INVALID));
        assertTrue(report.findings().stream()
                .anyMatch(f -> f.toString().contains("boolean condition")));
    }

    // ------------------------------------------------------------------
    // Observe-only checks
    // ------------------------------------------------------------------


    @Test
    void knownTypeConflictsAreObservedNotParked()
    {
        StageAReport report = check("len(AEOUT) == \"5\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(report));
        assertTrue(report.armedFindings().isEmpty());
        assertEquals(report.findings(), report.observedFindings());
    }


    @Test
    void nonBooleanLogicalOperandsAreObserved()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("upper(AEOUT) and empty(AESEV)")));
        // a Check whose root is a known non-boolean
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("upper(AEOUT)")));
    }


    @Test
    void arithmeticOverAKnownStringIsObserved()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("upper(AEOUT) + 1 > 2")));
        assertEquals(List.of(), check("AESEQ + 1 > 2").findings());
    }


    @Test
    void aComputedRegexIsObserved()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("AEOUT =~ upper(AESEV)")));
        assertEquals(List.of(), check("AEOUT =~ \"^[A-Z]+$\"").findings());
    }


    @Test
    void aScalarMembershipRightHandSideIsObserved()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("AESEV in 5")));
        // D81d: a collection LEFT operand is a legal collection shape (10 corpus rules)
        assertEquals(List.of(), check("[AESTDTC, AEENDTC] in $set").findings());
    }


    @Test
    void anUnqualifiedMergedColumnIsObservedNotParked()
    {
        // D62 / D62a: the three SUPPAE rules read AESMIE bare and work today — observe only.
        Rule rule = new Rule();
        MatchDataset match = new MatchDataset();
        match.setName("AE");
        rule.setMatchDatasets(List.of(match));
        StageAReport bare = check(rule, "empty(AESMIE)");
        assertEquals(List.of(StageAErrorKind.MERGED_COLUMN_UNQUALIFIED), kinds(bare));
        assertTrue(bare.armedFindings().isEmpty());
        // a qualified reference is what D62 asks for
        assertEquals(List.of(), check(rule, "empty(AE.AESMIE)").findings());
    }


    @Test
    void anUnknownElementIsObservedNotParked()
    {
        StageAReport report = check("frobnicate(AEOUT)");
        assertEquals(List.of(StageAErrorKind.UNKNOWN_ELEMENT), kinds(report));
        assertTrue(report.armedFindings().isEmpty());
    }


    @Test
    void theNativeDispatchOnlyCallsAreKnown()
    {
        // ExprCompiler dispatches these by name outside the registry; they must not read as
        // unknown elements (12 corpus rules use them).
        assertEquals(List.of(),
                check("not has_next_corresponding_record(SJENDTC, SJSTDTC, "
                        + "keep_missings=false, ordering=SJSEQ, relation=\"<=\", within=USUBJID)")
                                .findings());
        assertEquals(List.of(),
                check("is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=USUBJID)").findings());
    }

    // ------------------------------------------------------------------
    // The temporal surface (phase 3b — SPEC §5, D20/D22/D27/D55)
    // ------------------------------------------------------------------


    @Test
    void dateAndTimeAreRealConversions()
    {
        StageAReport report = check("date(AESTDTC) == date(AEENDTC)");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.DATE, root(report).children().get(0).type());
        assertEquals(Primitive.DATE, root(report).children().get(1).type());
        StageAReport time = check("time(AESTTIM) == time(AEENTIM)");
        assertEquals(List.of(), time.findings());
        assertEquals(Primitive.TIME, root(time).children().get(0).type());
    }


    @Test
    void partAccessorsTakeADateAndCarryTheirBase()
    {
        // D20: date_part/time_part are ordinary functions over a date. The corpus spelling
        // date_part(COLUMN) stays clean (a column is stage B's to type).
        StageAReport report = check("date_part(ADTM) != date(ADT)");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.DATE, root(report).children().get(0).type());
        StageAReport time = check("time_part(date(ADTM)) != time(ATM)");
        assertEquals(List.of(), time.findings());
        assertEquals(Primitive.TIME, root(time).children().get(0).type());
        // A known non-date argument is observed (PARAMETER_TYPE, observe-only).
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("date_part(5) == date(ADT)")));
    }


    @Test
    void hullBoundsOverloadOnTheTemporalBase()
    {
        // D22: earliest_possible/latest_possible follow their argument's base; date by default.
        StageAReport date = check("date(earliest_possible(AESTDTC)) <= latest_possible(AEENDTC)");
        assertEquals(List.of(), date.findings());
        assertEquals(Primitive.DATE, root(date).children().get(1).type());
        StageAReport time = check("earliest_possible(time(AESTTIM)) <= time(AEENTIM)");
        assertEquals(List.of(), time.findings());
        assertEquals(Primitive.TIME, root(time).children().get(0).type());
    }


    @Test
    void dateOverANumberIsObservedAsTheD55Shape()
    {
        // D55's static shadow: the armed half is the stage-B bind gate; here the shape is
        // observed with the date_from_sas_* rewrite named.
        StageAReport report = check("date(5) == date(AEENDTC)");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(report));
        assertTrue(report.findings().get(0).toString().contains("date_from_sas_days"));
    }


    @Test
    void mixedDateStringComparisonIsObservedAndNoLongerVacuous()
    {
        // §5.2: BOTH sides must be date — a date against a statically-known string is the mixed
        // pair. ⚠ Observe-only in 3b: the rulespec parity harness carries exactly one such
        // spelling (EMPTYSTR-date-not-equal-empty, date(X) != "") expecting EXECUTED, so the
        // arming discipline (zero newly parked over the shipped gates) keeps this from parking
        // until 3c respells that spec.
        Rule rule = new Rule();
        StageAReport report = StageAChecker.runAndApply(rule,
                levels(CheckExpressionParser.parse("date(AESTDTC) == \"2012-06-15\"")));
        assertNull(rule.getLoadError(), "observe-only: must not park");
        assertEquals(List.of(StageAErrorKind.MIXED_DATE_STRING_COMPARISON), kinds(report));
        assertTrue(report.findings().get(0).toString().contains("date/time and string"));
        // The corpus' transitional one-sided spelling is not even an observation: a bare column
        // dereferences to unknown, not string.
        assertEquals(List.of(), check("date(AESTDTC) != DTHDTC").findings());
    }


    @Test
    void minDateAndMaxDateBindingsTypeAsDate()
    {
        // Review 0b / coordinator 2026-09-16: the two date-valued operations type as date, so a
        // binding reference meets the §5.2 operator as a date — Review 0's "no edit" sites
        // ($min_ds_dsstdtc against date(DSSTDTC)) stay legal…
        Rule rule = new Rule();
        Operation op = new Operation();
        op.setId("$min_ds_dsstdtc");
        op.setOperator("min_date");
        op.setName("DSSTDTC");
        op.setGroup(List.of("USUBJID"));
        rule.setOperations(List.of(op));
        StageAReport report = check(rule, "date(DSSTDTC) == $min_ds_dsstdtc");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.DATE, root(report).children().get(1).type());
        // …and a string literal against the same binding is the armed §5.2 mixed pair.
        StageAReport mixed = check(rule, "$min_ds_dsstdtc == \"2012-06-15\"");
        assertEquals(List.of(StageAErrorKind.MIXED_DATE_STRING_COMPARISON), kinds(mixed));
    }


    @Test
    void theFourPredicatesAreBooleanWithTypedOperands()
    {
        StageAReport report = check("date_contains(RFSTDTC, AESTDTC) and date_overlaps(A, B)"
                + " and time_contains(T1, T2) and time_overlaps(T1, T2)");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.BOOLEAN, root(report).children().get(0).type());
        // D27a/§1.2: a statically-known string operand needs an explicit conversion.
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("date_contains(RFSTDTC, \"2012-06-15\")")));
        // And the conversions satisfy the parameter, cross-checking the typed flow end to end.
        assertEquals(List.of(),
                check("date_contains(date(RFSTDTC), date(\"2012-06-15\"))").findings());
    }

    // ------------------------------------------------------------------
    // runAndApply — the park path
    // ------------------------------------------------------------------


    @Test
    void armedFindingsParkThroughLoadError()
    {
        Rule rule = new Rule();
        StageAReport report = StageAChecker.runAndApply(rule,
                levels(CheckExpressionParser.parse("AESEV in [1, \"a\"]")));
        assertEquals(1, report.armedFindings().size());
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().startsWith("stage A: "));
    }


    @Test
    void observedFindingsDoNotPark()
    {
        Rule rule = new Rule();
        StageAChecker.runAndApply(rule, levels(CheckExpressionParser.parse("len(AEOUT) == \"5\"")));
        assertNull(rule.getLoadError());
    }


    @Test
    void anEarlierLoadErrorIsAppendedToNotClobbered()
    {
        Rule rule = new Rule();
        rule.setLoadError("earlier cause");
        StageAChecker.runAndApply(rule, levels(CheckExpressionParser.parse("AESEV in [1, \"a\"]")));
        assertTrue(rule.getLoadError().startsWith("earlier cause; stage A: "));
    }

    // ------------------------------------------------------------------
    // Loader integration — spec §9's stage-A row end to end
    // ------------------------------------------------------------------


    private static Rule load(String expression) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString(
                "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":\""
                        + expression.replace("\"", "\\\"") + "\"}}}}");
        return pkg.getRules().get("X-1");
    }


    @Test
    void theLoaderParksAnArmedStageAFinding() throws Exception
    {
        Rule rule = load("AESEV in [1, \"a\"]");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("stage A"));
        assertNull(rule.getCheckExpr());
    }


    @Test
    void theLoaderKeepsACleanRuleUnparked() throws Exception
    {
        Rule rule = load("empty(AEOUT) and AESEQ > 5");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr());
    }


    @Test
    void theObserverSeesEveryCheckedRule()
    {
        List<String> seen = new java.util.ArrayList<>();
        StageAChecker.setObserver((rule, report) -> seen.add(String.valueOf(report.findings())));
        try
        {
            check("empty(AEOUT)");
        }
        finally
        {
            StageAChecker.setObserver(null);
        }
        // check() does not notify — only runAndApply does; prove the null-reset path too.
        assertTrue(seen.isEmpty());
        StageAChecker.runAndApply(new Rule(), levels(CheckExpressionParser.parse("empty(AEOUT)")));
    }

}
