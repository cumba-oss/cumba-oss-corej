package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.MatchDataset;
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


    /**
     * A compiled binding {@code name} over {@code source} — the one kind of binding since runbook
     * W8 (the declared operation records these tests used to build went with the carrier).
     */
    private static CompiledBinding binding(String name, String source)
    {
        return new CompiledBinding(name, CheckExpressionParser.parse(source), List.of(), null);
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
    void aGroupedInlineCallCarriesGroupGranularity()
    {
        TypedExpr root = root(check("max(AESEQ, group=[USUBJID]) == AESEQ"));
        // group(K) ⊔ record = record (the comparison is per row)
        assertEquals(Level.RECORD, root.level());
        TypedExpr grouped = root.children().get(0);
        assertEquals(new Granularity.Group(java.util.Set.of("USUBJID")),
                grouped.level().granularity());
        assertEquals(Cursor.ABSENT, grouped.level().cursor());
    }


    @Test
    void aGroupedBindingReferenceCarriesGroupGranularity()
    {
        // (max is a registry function since runbook W5: the grouped binding is a COMPILED one.)
        Rule rule = new Rule();
        rule.setCompiledBindings(List.of(new CompiledBinding("$m",
                CheckExpressionParser.parse("max(AESEQ, group=[USUBJID])"), List.of(), null)));
        TypedExpr root = root(check(rule, "$m == AESEQ"));
        assertEquals(new Granularity.Group(java.util.Set.of("USUBJID")),
                root.children().get(0).level().granularity());
    }


    @Test
    void strIsAStringConversion()
    {
        // D91f (i) retired by PLAN-dynamic-column-functions §2.1 (owner Q10, 2026-09-30): str()
        // is no longer an erased passthrough but a registered conversion typed string — the last
        // mode tag of this checker is gone (date() became a real conversion in phase 3b).
        StageAReport report = check("str(AESTDTC) == str(AEENDTC)");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.STRING, root(report).children().get(0).type());
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
        rule.setCompiledBindings(
                List.of(binding("$a", "$b"), binding("$b", "max(AESEQ, group=[USUBJID])")));
        assertEquals(List.of(StageAErrorKind.FORWARD_OR_CYCLIC_BINDING),
                kinds(check(rule, "$a == 1")));

        Rule selfRule = new Rule();
        selfRule.setCompiledBindings(List.of(binding("$a", "$a")));
        StageAReport report = check(selfRule, "$a == 1");
        assertEquals(List.of(StageAErrorKind.FORWARD_OR_CYCLIC_BINDING), kinds(report));
        assertTrue(report.findings().get(0).message().contains("itself"));
    }


    @Test
    void backwardBindingReferencesAreClean()
    {
        Rule rule = new Rule();
        rule.setCompiledBindings(
                List.of(binding("$a", "max(AESEQ, group=[USUBJID])"), binding("$b", "$a")));
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
        assertEquals(1, report.armedFindings().size());
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


    /**
     * ⭐ The Stage-A hole of {@code PLAN-hashed-join-arm-absent-columns} §2b, closed 2026-09-25: a
     * {@code Child: true} entry keeps its {@code SUPP--} template name through specialisation, so
     * an exact-name comparison found no entry for {@code SUPPAE._matched_} at either pass, deferred
     * it (the test above), and the flag then read a SUPPAE self-join at run time — every row true.
     * The qualifier now resolves as an instance of the template, and the Child arm refuses it.
     */
    @Test
    void matchedFlagOnAChildTemplateEntryIsInvalidDespiteTheTemplate()
    {
        Rule rule = ruleJoining("SUPP--", "left", "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        StageAReport report = check(rule, "not SUPPAE._matched_");
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INVALID), kinds(report),
                "deferred (no finding) is the pre-fix hole");
        String message = report.findings().get(0).toString();
        assertTrue(message.contains("SUPPAE._matched_"), message);
        assertTrue(message.contains("Child: true"), message);
    }


    @Test
    void aQualifierThatIsNoInstanceOfTheTemplateStaysDeferred()
    {
        // DM is not an instance of SUPP--, so the flag is still unbound and still deferred while
        // the template remains — the resolution widened only to the template's own instances.
        Rule rule = ruleJoining("SUPP--", "left", "USUBJID");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        assertEquals(List.of(), check(rule, "not DM._matched_").findings());
        assertTrue(StageAChecker.instantiatesTemplate("SUPP--", "SUPPAE"));
        assertTrue(StageAChecker.instantiatesTemplate("SQ--", "SQAP"));
        assertFalse(StageAChecker.instantiatesTemplate("SUPP--", "SUPP"),
                "the template needs a non-empty domain in place of --");
        assertFalse(StageAChecker.instantiatesTemplate("SUPP--", "DM"));
        assertFalse(StageAChecker.instantiatesTemplate("AE", "AE"),
                "a concrete name is not a template — the exact arm handles it");
        assertFalse(StageAChecker.instantiatesTemplate(null, "AE"));
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

    // ------------------------------------------------------------------
    // DOTTED_REF_CHILD_ENTRY (PLAN-hashed-join-arm-absent-columns §3 follow-up 1, owner
    // 2026-09-25) — the value-read sibling of MATCHED_FLAG_INVALID's Child arm. Armed, and like
    // that arm ZERO over the shipped corpus (none of the 5 Child rules reads its entry dotted), so
    // these cases are the whole instrument.
    // ------------------------------------------------------------------


    @Test
    void aDottedReadOfAChildEntryIsAnArmedStageAError()
    {
        Rule rule = ruleJoining("AE", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        StageAReport report = check(rule, "AE.AESMIE != \"Y\"");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY), kinds(report),
                "a Child entry builds no direct lookup, so AE.AESMIE has nothing to read; clean is"
                        + " the pre-ruling behaviour (the read took the not-supplied default)");
        assertEquals(1, report.armedFindings().size(), "refused at load, as the flag is");
        String message = report.findings().get(0).toString();
        assertTrue(message.contains("AE.AESMIE"), message);
        assertTrue(message.contains("read bare"), message);
    }


    @Test
    void aDottedReadOfAChildTemplateEntryIsRefusedDespiteTheTemplate()
    {
        // The same template resolution as the flag: SUPPAE.QVAL names the SUPP-- Child entry, and
        // the refusal is not deferred — the entry WAS resolved, so this is no guess.
        Rule rule = ruleJoining("SUPP--", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        StageAReport report = check(rule, "SUPPAE.QVAL == \"x\"");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY), kinds(report));
    }


    /**
     * Round 2 M1: {@code SUPP--.QVAL} is a {@code WILDCARD_COLUMN} (it contains {@code --}) whose
     * qualifier names the {@code SUPP--} Child entry <b>exactly</b>; {@code ExprPrefixResolver}
     * later rewrites it to {@code SUPPAE.QVAL}, a silent not-supplied default. One predicate for
     * every surface: it is judged in a Check and in a Binding alike.
     */
    @Test
    void aTemplateQualifiedReadOfAChildEntryIsRefused()
    {
        Rule rule = ruleJoining("SUPP--", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(rule, "SUPP--.QVAL == \"x\"")), "SUPP--.QVAL in a Check");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(rule, "SUPP--.**VAL == \"x\"")), "SUPP--.**VAL in a Check");
        rule.setCompiledBindings(List.of(binding("$q", "SUPP--.QVAL")));
        assertTrue(
                kinds(check(rule, "$q == \"x\"")).contains(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                "SUPP--.QVAL in a Binding");
        // control: the same reads against an ORDINARY SUPP-- entry are not refused
        rule.getMatchDatasets().get(0).setChild(Boolean.FALSE);
        assertFalse(
                kinds(check(rule, "$q == \"x\"")).contains(StageAErrorKind.DOTTED_REF_CHILD_ENTRY));
        rule.setCompiledBindings(null);
        assertFalse(kinds(check(rule, "SUPP--.QVAL == \"x\""))
                .contains(StageAErrorKind.DOTTED_REF_CHILD_ENTRY));
    }


    @Test
    void aDottedReadOfAnOrdinaryTemplateEntryStaysDeferred()
    {
        // Control for the two above: the same reads against the same names WITHOUT Child: true
        // are what they were — deferred while the template remains, clean when concrete.
        assertEquals(List.of(),
                check(ruleJoining("SUPP--", null, "USUBJID"), "SUPPAE.QVAL == \"x\"").findings());
        assertEquals(List.of(),
                check(ruleJoining("SUPP--", null, "USUBJID"), "DM.AGE > 30").findings(),
                "a qualifier that is no instance of the template is still deferred, as before");
        assertEquals(List.of(),
                check(ruleJoining("AE", null, "USUBJID"), "AE.AESMIE != \"Y\"").findings());
    }


    @Test
    void aDottedOutputVariableOfAChildEntryIsAnArmedStageAError()
    {
        // M2: CG0043 with Output_Variables: [AE.AESMIE] loaded clean and RuleRunner silently
        // dropped the column from every finding (no lookup, no value, no message).
        Rule rule = ruleJoining("AE", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setOutputVariables(List.of("QNAM", "AE.AESMIE"));
        rule.setOutcome(outcome);
        StageAReport report = check(rule, "QNAM == \"AESOSP\"");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY), kinds(report),
                "clean is the pre-fix silent omission");
        assertEquals(1, report.armedFindings().size());
        String message = report.findings().get(0).toString();
        assertTrue(message.contains("Output_Variables entry AE.AESMIE"), message);
    }


    @Test
    void dottedOutputVariableShapesOnAChildEntry()
    {
        Rule rule = ruleJoining("AE", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        rule.setOutcome(outcome);
        // a judgeable qualifier before a substituted or wildcard suffix is judged
        outcome.setOutputVariables(List.of("AE.${QNAM}"));
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(rule, "QNAM == \"AESOSP\"")), "AE.${QNAM}: the prefix is literal");
        outcome.setOutputVariables(List.of("AE.**TERM"));
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                kinds(check(rule, "QNAM == \"AESOSP\"")), "AE.**TERM: the prefix is literal");
        // a substituted QUALIFIER is bound at run time and is not judged here (documented on the
        // kind), and an excluded name is not judged either
        outcome.setOutputVariables(List.of("${IDVAR}.AESMIE"));
        assertEquals(List.of(), check(rule, "QNAM == \"AESOSP\"").findings());
        // L5: an excluded name is not judged — the exclusion REMOVES AE.AESMIE from the list the
        // arm reads, so the pair yields nothing (red with applyExclusions removed: control run)
        outcome.setOutputVariables(List.of("AE.AESMIE", "!AE.AESMIE"));
        assertEquals(List.of(), check(rule, "QNAM == \"AESOSP\"").findings(),
                "the exclusion must take AE.AESMIE out before the Child arm sees it");
        // control: the same output on the same entry WITHOUT Child: true is clean
        rule.getMatchDatasets().get(0).setChild(Boolean.FALSE);
        outcome.setOutputVariables(List.of("AE.AESMIE"));
        assertEquals(List.of(), check(rule, "QNAM == \"AESOSP\"").findings());
    }


    @Test
    void aBindingExpressionReadingAChildEntryDottedIsAnArmedStageAError()
    {
        // L5: the Bindings surface is walked by collectBindingDottedRefs, so a Child entry read
        // through a binding is the same refusal.
        Rule rule = ruleJoining("AE", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        rule.setCompiledBindings(List.of(binding("$smie", "AE.AESMIE")));
        StageAReport report = check(rule, "$smie != \"Y\"");
        assertTrue(kinds(report).contains(StageAErrorKind.DOTTED_REF_CHILD_ENTRY),
                "the binding's AE.AESMIE reads the Child entry: " + report.findings());
        // control: the same binding against an ordinary entry
        rule.getMatchDatasets().get(0).setChild(Boolean.FALSE);
        assertFalse(kinds(check(rule, "$smie != \"Y\""))
                .contains(StageAErrorKind.DOTTED_REF_CHILD_ENTRY));
    }


    @Test
    void theLoaderParksADottedReadOfAChildEntry() throws Exception
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString(KeyedJoinFixtures.declared("{\"rules\":{\"X-1\":{\"Core\":{\"Id\":"
                        + "\"X-1\"},\"Match_Datasets\":[{\"Name\":\"AE\",\"Child\":true,\"Keys\":"
                        + "[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}],\"Check\":{\"expression\":"
                        + "\"AE.AESMIE != \\\"Y\\\"\"}}}}"));
        Rule rule = pkg.getRules().get("X-1");
        assertNotNull(rule.getLoadError(), "the armed kind parks the rule");
        assertTrue(rule.getLoadError().contains("DOTTED_REF_CHILD_ENTRY"), rule.getLoadError());
        assertNull(rule.getCheckExpr());
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
    void aTemplatedDottedNameIsJudgedAsAQualifiedWildcard()
    {
        // MEASURED, not reasoned: OperandClassifier tests isWildcard FIRST, so `ADSL.PH${*}SDT`
        // classifies as WILDCARD_COLUMN and is not a DOTTED_REF at all. ⭐ Since QNU N27 the
        // undeclared arm judges a qualified wildcard with a CONCRETE qualifier too (it reads
        // through
        // that join exactly as ADSL.X does) — so ADSL, undeclared here, is reported, by the
        // wildcard arm's message rather than the DOTTED_REF one. Until N27 this pinned silence.
        Rule rule = ruleJoining("AE", "left", "USUBJID");
        StageAReport report = check(rule, "PHSDTM not in ADSL.PH${*}SDT");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED), kinds(report));
        assertTrue(report.findings().get(0).toString().contains("a qualified template reads"),
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
        rule.setCompiledBindings(List.of(binding("$x", "DM.AGE")));
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
    void aBindingRefInAFilterIsALeftSideError()
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
    // The type checks — PARAMETER_TYPE, armed since PLAN-stage-a-parameter-type-arming
    // ------------------------------------------------------------------


    @Test
    void knownTypeConflictsAreArmedAndPark()
    {
        // Armed 2026-10-01 after the plan's probe measured zero over both corpora, the rulespecs
        // and the run-time expanded rules; a known-vs-known conflict now parks the rule.
        assertTrue(StageAErrorKind.PARAMETER_TYPE.armed());
        StageAReport report = check("len(AEOUT) == \"5\"");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(report));
        assertEquals(report.findings(), report.armedFindings());
        assertTrue(report.observedFindings().isEmpty());
    }


    @Test
    void nonBooleanLogicalOperandsAreFindings()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("upper(AEOUT) and empty(AESEV)")));
        // a Check whose root is a known non-boolean
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("upper(AEOUT)")));
    }


    @Test
    void arithmeticOverAKnownStringIsAFinding()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("upper(AEOUT) + 1 > 2")));
        assertEquals(List.of(), check("AESEQ + 1 > 2").findings());
    }


    @Test
    void aComputedRegexIsAFinding()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("AEOUT =~ upper(AESEV)")));
        assertEquals(List.of(), check("AEOUT =~ \"^[A-Z]+$\"").findings());
    }


    @Test
    void aScalarMembershipRightHandSideIsAFinding()
    {
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("AESEV in 5")));
        // D81d: a collection LEFT operand is a legal collection shape (10 corpus rules)
        assertEquals(List.of(), check("[AESTDTC, AEENDTC] in $set").findings());
    }

    // ------------------------------------------------------------------
    // Observe-only checks
    // ------------------------------------------------------------------


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
        // A known non-date argument is the (armed) PARAMETER_TYPE finding.
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
    void dateOverANumberIsRefusedAsTheD55Shape()
    {
        // D55's static shadow: a statically NUMBER argument is refused at load with the
        // date_from_sas_* rewrite named (a numeric COLUMN is stage B's bind gate).
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


    /**
     * ⭐ <b>Review round 1, finding 3</b> ({@code plans/done/PLAN-membership-as-equality.md} phase
     * 1a). {@code StageAChecker.membership} now names the temporal/string mix the way
     * {@code comparison} does, instead of the generic disagreement — and the branch was shipped
     * with <b>no test at all</b>.
     *
     * <p>
     * ⚠⚠ Two things are asserted, not one. The REJECTION is not new — {@code compatible(DATE,
     * STRING)} was already false — so pinning only "this errors" would have passed either side of
     * the change. What is new is the <b>kind</b>: {@code MIXED_DATE_STRING_COMPARISON} in place of
     * {@code PARAMETER_TYPE}, which any consumer keying on the old kind silently stops seeing. Both
     * the kind and the actionable wording are pinned here.
     * </p>
     */
    @Test
    void membershipNamesTheTemporalStringMixTheWayComparisonDoes()
    {
        StageAReport report = check("date(AESTDTC) in [\"2012-06-15\"]");
        assertEquals(List.of(StageAErrorKind.MIXED_DATE_STRING_COMPARISON), kinds(report),
                "the KIND changed from PARAMETER_TYPE — that is the half a rejection test misses");
        assertTrue(report.findings().get(0).toString().contains("date/time and string"));
        assertTrue(report.findings().get(0).toString().contains("date(...)"),
                "the message must say how to fix it, which is why it replaced the generic one");
    }


    /**
     * The other side of the same branch: the authoring the message prescribes must LOAD. ⛔ A
     * type-error test without this arm cannot distinguish "rejects the mix" from "rejects
     * membership over a date".
     */
    @Test
    void aTemporalMembershipOverConvertedMembersIsClean()
    {
        assertEquals(List.of(), check("date(AESTDTC) in [date(\"2012-06-15\")]").findings());
        // ⭐ Review round 2, finding 4: the TIME spelling is the one the round-1 HIGH exists for,
        // and nothing checked that it even loads. evaluate() in TemporalMembershipTest bypasses
        // Stage A entirely, so this is the only place it is covered.
        assertEquals(List.of(), check("time(AESTTM) in [time(\"09:15\")]").findings());
    }


    @Test
    void minDateAndMaxDateBindingsTypeAsDate()
    {
        // Review 0b / coordinator 2026-09-16: the two date-valued operations type as date, so a
        // binding reference meets the §5.2 operator as a date — Review 0's "no edit" sites
        // ($min_ds_dsstdtc against date(DSSTDTC)) stay legal…
        Rule rule = new Rule();
        rule.setCompiledBindings(List.of(new CompiledBinding("$min_ds_dsstdtc",
                CheckExpressionParser.parse("min_date(DSSTDTC, group=[USUBJID])"), List.of(),
                null)));
        StageAReport report = check(rule, "date(DSSTDTC) == $min_ds_dsstdtc");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.DATE, root(report).children().get(1).type());
        // …and a string literal against the same binding is the observe-only §5.2 mixed pair (the
        // kind is not armed; its javadoc says why).
        StageAReport mixed = check(rule, "$min_ds_dsstdtc == \"2012-06-15\"");
        assertEquals(List.of(StageAErrorKind.MIXED_DATE_STRING_COMPARISON), kinds(mixed));
    }


    @Test
    void earliestDateAndLatestDateTypeAsDateWithDateParameters()
    {
        // PLAN-scalar-date-extremes S3: the pair extremes are DATE-valued like min_date /
        // max_date (ElementTable), and their two parameters are DATE — a column meets them, a
        // DATE-typed binding meets them, a string literal is the PARAMETER_TYPE finding.
        Rule rule = new Rule();
        rule.setCompiledBindings(List.of(new CompiledBinding("$own",
                CheckExpressionParser.parse("min_date(EXSTDTC, group=[USUBJID])"), List.of(),
                null)));
        StageAReport report = check(rule, "date(RFXSTDTC) != earliest_date($own, EXSTDTC)");
        assertEquals(List.of(), report.findings());
        assertEquals(Primitive.DATE, root(report).children().get(1).type());
        assertEquals(Primitive.DATE,
                root(check("date(X) == latest_date(A, B)")).children().get(1).type());
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("empty(earliest_date(A, \"2012-06-15\"))")));
        assertEquals(List.of(), check("empty(earliest_date(A, date(\"2012-06-15\")))").findings());
        // and the DATE result meets a string literal as the observe-only §5.2 mixed pair
        assertEquals(List.of(StageAErrorKind.MIXED_DATE_STRING_COMPARISON),
                kinds(check("latest_date(A, B) == \"2012-06-15\"")));
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
        // UNKNOWN_ELEMENT is observe-only (PARAMETER_TYPE, which stood here, is armed since
        // PLAN-stage-a-parameter-type-arming)
        Rule rule = new Rule();
        StageAChecker.runAndApply(rule, levels(CheckExpressionParser.parse("frobnicate(AEOUT)")));
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
        RulePackage pkg = RulePackageLoader.loadFromString(KeyedJoinFixtures.declared(
                "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":\""
                        + expression.replace("\"", "\\\"") + "\"}}}}"));
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
        java.util.function.BiConsumer<Rule, StageAReport> previous = StageAChecker
                .setObserver((rule, report) -> seen.add(String.valueOf(report.findings())));
        try
        {
            check("empty(AEOUT)");
        }
        finally
        {
            StageAChecker.setObserver(previous);
        }
        // check() does not notify — only runAndApply does; prove the null-reset path too.
        assertTrue(seen.isEmpty());
        StageAChecker.runAndApply(new Rule(), levels(CheckExpressionParser.parse("empty(AEOUT)")));
    }


    /**
     * Combined review of runbook W2–W8, XCUT H1: a declared row reader
     * ({@code FunctionDescriptor.readingRows} — {@code row_max}, whose only operand is a static
     * pattern) is at least RECORD level, the same flag {@code DomainScan} reads.
     */
    @Test
    void aDeclaredRowReaderIsRecordLevelWithoutAColumnOperand()
    {
        StageAReport report = check("row_max(name_pattern=\"^TR..EDT$\") != \"\"");
        assertEquals(Level.RECORD, root(report).level());
        assertEquals(List.of(), kinds(report));
    }


    /**
     * Combined review of runbook W2–W8, XCUT L3: an ungrouped {@code read_value(…, domain=X)} reads
     * ANOTHER dataset and answers one value for the run — {@code DATASET}, as {@code DomainScan}
     * classifies it. Pre-fix Stage A joined its column arguments and typed it {@code RECORD}, so
     * the two calculi disagreed on the same call.
     */
    @Test
    void anUngroupedForeignReadValueIsDatasetLevelAsDomainScanSays()
    {
        String source = "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \"SPECIES\"),"
                + " mode=\"FIRST\") == \"RAT\"";
        assertEquals(net.cumba.corej.core.expr.eval.Domain.DATASET,
                net.cumba.corej.core.expr.eval.DomainScan.infer(CheckExpressionParser.parse(source),
                        net.cumba.corej.core.expr.eval.BindingDomains.NONE),
                "the DomainScan side of the agreement");
        assertEquals(Level.DATASET, root(check(source)).level(),
                "Stage A must agree: the foreign read is one value for the run, not per row");
        // The grouped arm keeps precedence: a grouped foreign read is per primary group, never
        // folded to one value for the run by the new arm.
        String grouped = "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \"SPECIES\"),"
                + " mode=\"FIRST\", group=[USUBJID]) == \"RAT\"";
        assertNotEquals(Level.DATASET, root(check(grouped)).level(),
                "a grouped read_value keeps its group(K) granularity");
    }

    // ------------------------------------------------------------------
    // PLAN-qualified-name-uniformity-review phase 3 — N17, N26, N27: the qualified surfaces the
    // undeclared / Child arms did not reach. Each test was RED before its arm.
    // ------------------------------------------------------------------


    private static Rule withOutputs(Rule rule, String... outputVariables)
    {
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setOutputVariables(List.of(outputVariables));
        rule.setOutcome(outcome);
        return rule;
    }


    /**
     * N17: an {@code Output_Variables} entry naming an undeclared dataset is omitted from every
     * finding at run time; the Check side files {@code DOTTED_REF_UNDECLARED} for the same
     * spelling, the output side filed nothing. Same observe-only kind now — never a load error.
     */
    @Test
    void anUndeclaredDottedOutputVariableIsAnObserveOnlyFinding()
    {
        StageAReport report = check(
                withOutputs(ruleJoining("AE", "left", "USUBJID"), "QNAM", "DM.ARM"),
                "QNAM == \"x\"");
        assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED), kinds(report));
        assertEquals(List.of(), report.armedFindings(), "observe-only, as on the Check side");
        String message = report.findings().get(0).toString();
        assertTrue(message.contains("Output_Variables entry DM.ARM"), message);
        // controls: declared; a ${…} / -- qualifier (resolved later); a case-only difference
        // (owner-pending N2); a template entry name (deferred); a per-VARIABLE rule, where the
        // runner reports the entry as its own identifier (Fix #18)
        assertEquals(List.of(),
                check(withOutputs(ruleJoining("AE", "left", "USUBJID"), "AE.AETERM"),
                        "QNAM == \"x\"").findings());
        assertEquals(List.of(), check(
                withOutputs(ruleJoining("AE", "left", "USUBJID"), "${IDVAR}.X", "SUPP--.QVAL"),
                "QNAM == \"x\"").findings());
        assertEquals(List.of(),
                check(withOutputs(ruleJoining("AE", "left", "USUBJID"), "ae.AETERM"),
                        "QNAM == \"x\"").findings());
        assertEquals(List.of(),
                check(withOutputs(ruleJoining("SUPP--", "left", "USUBJID"), "DM.ARM"),
                        "QNAM == \"x\"").findings());
        assertEquals(List.of(),
                check(withOutputs(new Rule(), "AE.AETRTEM"), "varname() == \"AETRTEM\"")
                        .findings());
    }


    /**
     * N26: a {@code colref} / {@code find_vars} literal qualifier naming a {@code Child: true}
     * entry reads the not-supplied default on every row — the authored {@code AE.AESMIE} is refused
     * at load for exactly that (armed), the dynamic spelling was only ever judged undeclared.
     */
    @Test
    void aColrefOrFindVarsNameOfAChildEntryIsAnArmedStageAError()
    {
        Rule rule = ruleJoining("AE", null, "USUBJID", "IDVAR", "IDVARVAL");
        rule.getMatchDatasets().get(0).setChild(Boolean.TRUE);
        for (String expression : List.of("colref(\"AE.AESMIE\") != \"Y\"",
                "count(find_vars(\"AE.AES*\")) > 0"))
        {
            StageAReport report = check(rule, expression);
            assertEquals(List.of(StageAErrorKind.DOTTED_REF_CHILD_ENTRY), kinds(report),
                    expression);
            assertEquals(1, report.armedFindings().size(), expression);
        }
        // control: the same names against the same entry without Child: true are clean
        rule.getMatchDatasets().get(0).setChild(Boolean.FALSE);
        assertEquals(List.of(), check(rule, "colref(\"AE.AESMIE\") != \"Y\"").findings());
    }


    /**
     * N27: a qualified TEMPLATE operand ({@code DM.${V}}, {@code ADSL.X${*}}, {@code AE.**TERM})
     * reads through the join of its concrete qualifier, so an undeclared one is judged by the
     * observe-only undeclared arm like {@code DM.X} — it was judged by the Child arm only.
     */
    @Test
    void anUndeclaredQualifiedTemplateOperandIsAnObserveOnlyFinding()
    {
        for (String expression : List.of("DM.**TERM != \"x\"", "X in DM.Q${*}V"))
        {
            StageAReport report = check(ruleJoining("AE", "left", "USUBJID"), expression);
            assertEquals(List.of(StageAErrorKind.DOTTED_REF_UNDECLARED), kinds(report), expression);
            assertEquals(List.of(), report.armedFindings(), expression);
        }
        // controls: declared, and a -- qualifier resolved later against a concrete entry
        assertEquals(List.of(),
                check(ruleJoining("DM", "left", "USUBJID"), "DM.**TERM != \"x\"").findings());
        // T2 r1 (engine 4): a qualifier whose CASE alone differs from an entry's is owner-pending
        // N2 and not judged — the exemption the Output_Variables arm (N17) already carries.
        assertEquals(List.of(),
                check(ruleJoining("dm", "left", "USUBJID"), "X in DM.Q${*}V").findings());
        assertEquals(List.of(),
                check(ruleJoining("SUPPAE", "left", "USUBJID"), "SUPP--.QVAL != \"x\"").findings());
    }

    // ------------------------------------------------------------------
    // PLAN-stage-a-parameter-type-arming §3.1 — the four shapes the checker could not type
    // ------------------------------------------------------------------


    /** A rule carrying the compiled bindings {@code name → source}, in that order. */
    private static Rule withBindings(String... nameAndSource)
    {
        Rule rule = new Rule();
        List<CompiledBinding> bindings = new java.util.ArrayList<>();
        for (int i = 0; i < nameAndSource.length; i += 2)
        {
            bindings.add(binding(nameAndSource[i], nameAndSource[i + 1]));
        }
        rule.setCompiledBindings(bindings);
        return rule;
    }


    @Test
    void g1AQuotedDatasetNameAtADatasetReferenceParameterIsNotAFinding()
    {
        // G1 (SPEC §1.1, D10): a dataset reference is a bare TS or a quoted "TS" in a dataset
        // position — exactly the two spellings GroupedAggregate.readDataset accepts. The quoted
        // form used to type as STRING against DATASET_REFERENCE (944 corpus findings).
        for (String expression : List.of("record_count(domain=\"DM\") > 0",
                "record_count(domain=DM) > 0",
                "empty(read_value(TSVAL, domain=\"TS\", mode=\"FIRST\"))",
                "empty(max(AESEQ, domain=\"AE\", group=[USUBJID]))",
                "empty(max_date(AESTDTC, domain=\"AE\", group=[USUBJID]))",
                "empty(min_date(AESTDTC, domain=\"AE\", group=[USUBJID]))",
                "AETERM in distinct(AETERM, domain=\"AE\")"))
        {
            assertEquals(List.of(), check(expression).findings(), expression);
        }
        // negatives: a computed string and a number at domain= are still findings
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("record_count(domain=upper(DM)) > 0")));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("record_count(domain=5) > 0")));
        assertTrue(check("record_count(domain=upper(DM)) > 0").findings().get(0).message()
                .contains("takes dataset-reference, not string"));
    }


    @Test
    void g2UpperAndLowerOverAListAreTypedAsAListOfStrings()
    {
        // G2 (owner 2026-09-28: "upper allows a list of strings as parameter and returns a
        // list"): the case-folds fold element-wise, so over a list-typed argument the result is
        // list<string>, never the scalar STRING the ElementTable row says — CDISC-CG0370's
        // `IDVAR not in $rdomain_variables_upper` used to fire "the right operand of 'in' must be
        // a list or set, not string".
        Rule rule = withBindings("$names", "natural_key_variables()", "$upper", "upper($names)",
                "$lower", "lower($names)", "$upcase", "upcase($names)", "$lowcase",
                "lowcase($names)");
        for (String expression : List.of("IDVAR not in $upper", "IDVAR in $lower",
                "IDVAR in $upcase", "IDVAR in $lowcase", "IDVAR in upper(natural_key_variables())",
                "IDVAR in lower(find_vars(\"--SEQ\"))"))
        {
            StageAReport report = check(rule, expression);
            assertEquals(List.of(), report.findings(), expression);
        }
        assertEquals(new ExprType.ListOf(Primitive.STRING),
                root(check(rule, "IDVAR not in $upper")).children().get(1).type());
        // the scalar form stays scalar: a known non-boolean root, a string in arithmetic
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check("upper(AEOUT)")));
        assertEquals(Primitive.STRING,
                root(check("upper(AEOUT) == \"X\"")).children().get(0).type());
        // and the list result is not silently a scalar comparison operand either (armed kind)
        assertEquals(List.of(StageAErrorKind.COMPARISON_WITH_LIST_BINDING),
                kinds(check(rule, "IDVAR == $upper")));
        // the zero-argument spelling is the ARITY finding, never a checker failure
        StageAReport empty = check("upper() == \"\"");
        assertTrue(kinds(empty).contains(StageAErrorKind.ARITY), String.valueOf(kinds(empty)));
        assertFalse(kinds(empty).contains(StageAErrorKind.CHECKER_FAILURE));
    }


    @Test
    void g3ASplicedListBindingIsAcceptedOnlyWhereTheReaderSplices()
    {
        // G3: a $ binding holding a LIST OF NAMES spliced into a column-reference list — accepted
        // exactly where the reader splices (RecordCount: allowSplice = true for group=, and
        // is_(not_)unique_set's members through GroupSplice); every other element is still
        // typed, and every other reader (GroupedAggregate, ReadValue, Distinct, keys=) refuses.
        Rule rule = withBindings("$nk", "natural_key_variables()", "$fv", "find_vars(\"J.G\")",
                "$cl", "colref([\"A\", \"B\"])", "$scalar", "upper(AETERM)", "$one", "\"S\"",
                "$num", "len(AETERM)");
        // J is a declared join, so the qualified find_vars entry is not an undeclared qualifier
        rule.setMatchDatasets(ruleJoining("J", "left", "USUBJID").getMatchDatasets());
        for (String expression : List.of("is_unique_set([USUBJID, --TESTCD, $nk])",
                "not is_not_unique_set([USUBJID, $nk])",
                "record_count(group=[USUBJID, --TESTCD, $nk]) > 1", "record_count(group=$nk) > 1",
                // find_vars holding QUALIFIED names: the shape passes Stage A; the splice is the
                // rule's ERROR at run time by design (GroupSplice, GroupingUniformityTest)
                "record_count(group=[USUBJID, $fv]) > 1",
                // a string binding is ONE name (GroupSplice, N29)
                "is_unique_set([USUBJID, $one])", "record_count(group=[$one]) > 1",
                // review r1 M1: a splice-shaped $ element contributes its ELEMENT type to the
                // list's homogeneity — one name beside a list of names is not a mixed list
                "is_unique_set([USUBJID, $one, $nk])",
                "record_count(group=[USUBJID, $one, $nk]) > 1"))
        {
            assertEquals(List.of(), check(rule, expression).findings(), expression);
        }
        // negatives
        for (String expression : List.of("empty(max(AESEQ, group=[USUBJID, $nk]))",
                "empty(max(AESEQ, group=$nk))", "empty(max_date(AESTDTC, group=[USUBJID, $nk]))",
                "empty(read_value(TSVAL, domain=TS, mode=\"FIRST\", group=[$nk]))",
                "AETERM in distinct(AETERM, group=[USUBJID, $nk])", "is_unique_set([\"USUBJID\"])",
                "is_unique_set([USUBJID, $num])", "empty(max(AESEQ, group=[USUBJID, $one]))",
                // review r1 L7: a PER-ROW binding can never be spliced (GroupSplice ERRORs on the
                // hand-over Vector) — stage A's derived level is certain for these, so they are
                // refused at load: colref(list) reads per row, upper(AETERM) is per row
                "is_unique_set([USUBJID, $cl])", "is_unique_set([USUBJID, $scalar])",
                "record_count(group=$cl) > 1", "record_count(group=[USUBJID, $scalar]) > 1",
                "record_count(group=[USUBJID, $num]) > 1", "record_count(group=$num) > 1",
                "is_unique_relationship(AETERM, keys=[USUBJID, $nk])"))
        {
            assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(check(rule, expression)),
                    expression);
        }
        String message = check(rule, "empty(max(AESEQ, group=[USUBJID, $nk]))").findings().get(0)
                .message();
        assertTrue(message.contains("'group' of 'max'") && message.contains("$nk"), message);
        String perRow = check(rule, "is_unique_set([USUBJID, $cl])").findings().get(0).message();
        assertTrue(perRow.contains("per-row") && perRow.contains("$cl"), perRow);
    }


    @Test
    void g4AStringLiteralAtRecordCountsRegexParameterTypesAsRegex()
    {
        // G4 (C1): record_count's regex= is declared REGEX and RecordCount.readRegex accepts a
        // STRING or REGEX literal — CDISC-CG0562's regex="^\d{4}-…" is the one corpus site. Only
        // that parameter: the affix / imatches patterns compile from a /…/ literal alone.
        assertEquals(List.of(),
                check("record_count(group=[USUBJID], regex=\"^\\\\d{4}\") > 1").findings());
        assertEquals(List.of(), check("record_count(group=[USUBJID], regex=/^x/) > 1").findings());
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("record_count(group=[USUBJID], regex=5) > 1")));
        // (a column at regex= dereferences to unknown — stage A judges known-vs-known only;
        // RecordCount.readRegex refuses it at compile time)
        assertEquals(List.of(),
                check("record_count(group=[USUBJID], regex=AETERM) > 1").findings());
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("imatches(AETERM, \"a\")")));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                kinds(check("prefix_matches(AETERM, \"a\")")));
        assertEquals(List.of(), check("imatches(AETERM, /a/)").findings());
    }

    // ------------------------------------------------------------------
    // PLAN-stage-a-parameter-type-arming §3.2 — the Precondition as a root (Q2 / C2)
    // ------------------------------------------------------------------


    private static Expr pre(String expression)
    {
        return CheckExpressionParser.parse(expression);
    }


    private static net.cumba.corej.core.model.CheckCondition condition(String expression)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(pre(expression), expression);
    }


    @Test
    void theEngineWrittenGateShapesPassTheCheckerClean()
    {
        // the three availability-gate calls injectInlineOperationGates and the inliners write
        StageAReport report = StageAChecker.check(new Rule(), levels(pre("empty(AETERM)")),
                pre("library_available() and dictionary_available(\"meddra\")"
                        + " and available(var_exists(\"AESEV\"))"));
        assertEquals(List.of(), report.findings());
        assertNotNull(report.typedPrecondition());
        assertEquals(Primitive.BOOLEAN, report.typedPrecondition().type());
        // and no Precondition is no typed Precondition
        assertNull(check("empty(AETERM)").typedPrecondition());
    }


    @Test
    void aPreconditionIsHeldToTheArmedKindsLikeACheckLevel()
    {
        // a wrong arity in the Precondition is the armed ARITY finding, parked through loadError
        Rule rule = new Rule();
        StageAReport report = StageAChecker.runAndApply(rule, levels(pre("empty(AETERM)")),
                pre("library_available(1)"));
        assertEquals(List.of(StageAErrorKind.ARITY), kinds(report));
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().startsWith("stage A: ARITY"), rule.getLoadError());
        // a heterogeneous list in the Precondition parks too
        assertEquals(List.of(StageAErrorKind.HETEROGENEOUS_LIST),
                kinds(check2("empty(AETERM)", "AESEV in [1, \"a\"]")));
        // a forward binding reference seen from the Precondition
        Rule bound = withBindings("$later", "upper(AETERM)");
        assertEquals(List.of(), StageAChecker
                .check(bound, levels(pre("empty(AETERM)")), pre("not empty($later)")).findings());
    }


    @Test
    void aPreconditionIsHeldToTheTypeChecksLikeACheckLevel()
    {
        // a wrong parameter type and a non-boolean root are PARAMETER_TYPE findings — armed or
        // observed exactly as the kind is at the time (phase 3 of the plan arms it)
        StageAReport typeError = check2("empty(AETERM)", "dictionary_available(5)");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(typeError));
        assertFalse(typeError.armedFindings().isEmpty(), "armed since phase 3");
        StageAReport nonBoolean = check2("empty(AETERM)", "upper(AETERM)");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(nonBoolean));
        assertTrue(nonBoolean.findings().get(0).message().contains(
                "the Precondition root must be boolean"), nonBoolean.findings().toString());
        // the Check root keeps its own wording
        assertTrue(check("upper(AETERM)").findings().get(0).message()
                .contains("the Check root must be boolean"));
    }


    @Test
    void aPreconditionTakesPartInTheMatchDatasetsChecks()
    {
        // a _matched_ flag under an inner join, read only from the Precondition
        Rule rule = ruleJoining("AE", "inner", "USUBJID");
        StageAReport report = StageAChecker.check(rule, levels(pre("empty(X)")),
                pre("not AE._matched_"));
        assertEquals(List.of(StageAErrorKind.MATCHED_FLAG_INNER_JOIN), kinds(report));
    }


    @Test
    void thePreconditionOnlyEntryParksAnArmedFindingAndIsSkippedOnAParkedRule()
    {
        // the entry installEngineInternalPrecondition uses on an already-loaded rule
        Rule rule = new Rule();
        List<String> seen = new java.util.ArrayList<>();
        java.util.function.BiConsumer<Rule, StageAReport> previous = StageAChecker
                .setObserver((r, report) -> seen.add("observed"));
        try
        {
            StageAReport report = StageAChecker.runAndApplyPrecondition(rule,
                    pre("library_available(1)"));
            assertEquals(List.of(StageAErrorKind.ARITY), kinds(report));
            assertNull(report.typedLevels().get(Severity.ERROR));
            assertNotNull(report.typedPrecondition());
        }
        finally
        {
            StageAChecker.setObserver(previous);
        }
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().startsWith("stage A: ARITY"), rule.getLoadError());
        assertEquals(List.of(), seen, "the observer is not fired a second time for the rule");
        // the bindings' own findings are not re-reported: a binding with a type conflict loads
        // observed (or parked) at load, and the entry re-walks it for its TYPE only
        Rule bound = withBindings("$l", "len(AEOUT) == \"5\"");
        StageAReport clean = StageAChecker.runAndApplyPrecondition(bound,
                pre("library_available()"));
        assertEquals(List.of(), clean.findings());
    }


    @Test
    void theLoaderChecksAnEngineInternalPrecondition() throws Exception
    {
        // through RulePackageLoader.installEngineInternalPrecondition — the documented engine
        // seam for the tier
        Rule rule = load("empty(AEOUT) and AESEQ > 5");
        assertNull(rule.getLoadError());
        RulePackageLoader.installEngineInternalPrecondition(rule,
                condition("library_available(1)"));
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("stage A: ARITY"), rule.getLoadError());
        assertNull(rule.getPreconditionExpr(), "a parked Precondition is not raised");
        // a clean gate still raises to the native broadcast form
        Rule clean = load("empty(AEOUT) and AESEQ > 5");
        RulePackageLoader.installEngineInternalPrecondition(clean,
                condition("library_available()"));
        assertNull(clean.getLoadError());
        assertNotNull(clean.getPreconditionExpr());
        // and a parked rule is left alone
        Rule parked = load("empty(AEOUT)");
        parked.setLoadError("earlier cause");
        RulePackageLoader.installEngineInternalPrecondition(parked,
                condition("library_available(1)"));
        assertEquals("earlier cause", parked.getLoadError());
    }


    private static StageAReport check2(String check, String precondition)
    {
        return StageAChecker.check(new Rule(), levels(pre(check)), pre(precondition));
    }

    // ------------------------------------------------------------------
    // Review round 1 fixes (T2-fix1)
    // ------------------------------------------------------------------


    @Test
    void aCaseFoldOverAnUnknownBindingIsUnknown()
    {
        // sem L1: upper($u) over a binding of unknown static type is unknown, not a scalar
        // string — `X in upper($u)` must not be refused as a scalar right operand
        Rule rule = withBindings("$u", "read_value(TSVAL, domain=TS, mode=\"FIRST\")");
        assertEquals(List.of(), check(rule, "IDVAR in upper($u)").findings());
        assertEquals(List.of(), check(rule, "IDVAR in lower($u)").findings());
        // a column keeps the string row: upper(AEOUT) is a string value
        assertEquals(Primitive.STRING,
                root(check("upper(AEOUT) == \"X\"")).children().get(0).type());
    }


    @Test
    void aKnownNonReferenceAtACompilerDispatchedColumnParameterIsRefused()
    {
        // sem L6: the compiler-dispatched grouped callables read their column parameters with a
        // STRICT reader (groupOperandName) — a computed value is never a column there, so stage
        // A refuses the known non-reference (the registry-evaluated functions, which read value
        // vectors, keep accepting a computed scalar: tuple(upper(ARMCD), ARM)).
        for (String expression : List.of(
                "has_mixed_emptiness_within_group(upper(AETERM), group=[USUBJID])",
                "is_last_in_group(ordering=upper(AESEQ), group=[USUBJID])"))
        {
            StageAReport report = check(expression);
            assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(report), expression);
            assertTrue(report.findings().get(0).message().contains(
                    "takes a column reference, not string"), report.findings().toString());
        }
        assertEquals(List.of(),
                check("has_mixed_emptiness_within_group(AETERM, group=[USUBJID])").findings());
        assertEquals(List.of(), check("tuple(upper(ARMCD), ARM) not in $set").findings());
    }


    @Test
    void aParameterTypeFindingLocatesItsRootAndOperand()
    {
        // tests M2 / sem L3: the message names the root (Check level / Precondition / binding)
        // and the operand text, so a custom-package author can find the site
        String inCheck = check("len(AEOUT) == \"5\"").findings().get(0).message();
        assertTrue(inCheck.startsWith("Check (ERROR): "), inCheck);
        assertTrue(inCheck.contains("len(AEOUT) == \"5\""), inCheck);
        String inPrecondition = check2("empty(AETERM)", "len(AEOUT) + \"a\" > 1").findings().get(0)
                .message();
        assertTrue(inPrecondition.startsWith("Precondition: "), inPrecondition);
        assertTrue(inPrecondition.contains("len(AEOUT) + \"a\""), inPrecondition);
        Rule rule = withBindings("$b", "len(AEOUT) + \"a\"");
        String inBinding = check(rule, "$b > 1").findings().get(0).message();
        assertTrue(inBinding.startsWith("binding $b: "), inBinding);
        assertTrue(inBinding.contains("must be a number, not string"), inBinding);
        // and the list-function read of a Precondition is labelled as such (sem L3)
        Rule cursor = withBindings("$p", "upper(AETERM)");
        StageAReport reads = StageAChecker.check(cursor, levels(pre("empty(AETERM)")),
                pre("not empty(minus($p, subtract=$p))"));
        assertEquals(List.of(StageAErrorKind.OPERATION_READS_CURSOR_BINDING), kinds(reads));
        assertTrue(reads.findings().get(0).message().contains("in the Precondition"),
                reads.findings().toString());
    }


    @Test
    void theLoaderFilesALocatingMessageForABindingAndAPrecondition() throws Exception
    {
        // tests M2, loader level: the finding is user-visible in the rule's ERROR message
        RulePackage pkg = RulePackageLoader.loadFromString(
                KeyedJoinFixtures.declared("{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},"
                        + "\"Bindings\":[{\"name\":\"$b\",\"expression\":\"len(AEOUT) + \\\"a\\\"\"}],"
                        + "\"Check\":{\"expression\":\"$b > 1\"}}}}"));
        Rule rule = pkg.getRules().get("X-1");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("stage A: PARAMETER_TYPE: binding $b: ")
                && rule.getLoadError().contains("len(AEOUT) + \"a\""), rule.getLoadError());
        Rule gated = load("empty(AEOUT)");
        RulePackageLoader.installEngineInternalPrecondition(gated,
                condition("len(AEOUT) == \"5\""));
        assertNotNull(gated.getLoadError());
        assertTrue(
                gated.getLoadError().contains("stage A: PARAMETER_TYPE: Precondition: ")
                        && gated.getLoadError().contains("len(AEOUT) == \"5\""),
                gated.getLoadError());
    }


    @Test
    void aBindingAsAListLiteralElementOutsideASpliceSlotIsAnArmedFinding()
    {
        // review r2 F1: outside record_count's group= and is_(not_)unique_set's members the
        // compiler reads a $ element of a list literal as its TEXT ("$codes"), a silent wrong
        // verdict — so it is an armed load finding that says why and names the fix
        Rule rule = withBindings("$codes", "natural_key_variables()", "$a", "\"A\"", "$b", "\"B\"",
                "$nk", "natural_key_variables()");
        for (String expression : List.of("AETERM in [\"A\", $codes]", "[$a, $b] in $set",
                "AETERM in [$a]"))
        {
            StageAReport report = check(rule, expression);
            assertEquals(List.of(StageAErrorKind.HETEROGENEOUS_LIST), kinds(report), expression);
            assertEquals(1, report.armedFindings().size(), expression);
            String message = report.findings().get(0).message();
            assertTrue(message.contains("never a list element") && message.contains("\"$")
                    && message.contains("bind the whole list"), message);
        }
        // the splice slots are the only legal homes, and a non-splicing column-reference list
        // files its own finding exactly once (no double report)
        assertEquals(List.of(), check(rule, "is_unique_set([USUBJID, $nk])").findings());
        assertEquals(List.of(), check(rule, "record_count(group=[USUBJID, $nk]) > 1").findings());
        StageAReport once = check(rule, "empty(max(AESEQ, group=[USUBJID, $nk]))");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(once));
        assertEquals(1, once.findings().size(), once.findings().toString());
        // the binding alone as the right operand is the fix
        assertEquals(List.of(), check(rule, "AETERM in $codes").findings());
    }


    @Test
    void aVariableCursorBindingAtDatasetGranularitySplicesAndAGroupedOneDoesNot()
    {
        // review r2 F2: BindingValue.handOver gives a per-row VECTOR only for a row-cursor
        // domain; a {VAR}-cursor binding at dataset granularity hands over its first value, which
        // GroupSplice accepts (a String is one name) — so isPerRow is granularity only
        Rule rule = withBindings("$end", "concat(substring(varname(), 1, 4), \"ENDTC\")", "$d",
                "distinct(AETERM, group=[USUBJID])");
        assertEquals(List.of(), check(rule, "is_unique_set([USUBJID, $end])").findings());
        StageAReport grouped = check(rule, "is_unique_set([USUBJID, $d])");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE), kinds(grouped));
        assertTrue(grouped.findings().get(0).message().contains("per-row binding $d"),
                grouped.findings().toString());
    }


    @Test
    void thePreconditionOnlyEntryDoesNotRefileABindingLiteralFinding()
    {
        // review r3 LOW: the precondition-only entry scans the bindings for their types only and
        // clears their findings — the binding-literal ledger (r2 F1) must be cleared with them,
        // or a parked rule's load error carries the same finding twice
        Rule rule = withBindings("$codes", "natural_key_variables()", "$x", "[\"A\", $codes]");
        StageAChecker.runAndApply(rule, levels(pre("empty(AETERM)")), null);
        assertNotNull(rule.getLoadError());
        String marker = "holds the binding $codes";
        assertEquals(1, occurrences(rule.getLoadError(), marker), rule.getLoadError());
        StageAReport report = StageAChecker.runAndApplyPrecondition(rule,
                pre("library_available()"));
        assertEquals(List.of(), report.findings(),
                "the binding's literal is not the Precondition's");
        assertEquals(1, occurrences(rule.getLoadError(), marker), rule.getLoadError());
    }


    private static int occurrences(String text, String marker)
    {
        int count = 0;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + 1))
        {
            count++;
        }
        return count;
    }


    @Test
    void setObserverReturnsThePreviousObserverSoACallerCanRestoreIt()
    {
        // tests 5: a test that installs an observer restores what was there, never null
        java.util.function.BiConsumer<Rule, StageAReport> mine = (r, report) ->
        {
        };
        java.util.function.BiConsumer<Rule, StageAReport> previous = StageAChecker
                .setObserver(mine);
        try
        {
            assertSame(mine, StageAChecker.setObserver(previous));
        }
        finally
        {
            StageAChecker.setObserver(previous);
        }
    }


    @Test
    void aThrowingBindingWalkIsACheckerFailureNotAThrow()
    {
        // C4: the constructor used to scan the bindings OUTSIDE check()'s try, so a checker
        // defect in a binding walk escaped the "never throws" contract of check / runAndApply.
        // A LIST literal whose value is not a list: listLiteral's cast throws inside the walk —
        // the one malformed node a parser never produces, so a throw is the only way to reach it.
        Rule rule = new Rule();
        rule.setCompiledBindings(List.of(new CompiledBinding("$boom",
                new Expr.Lit(Expr.LitKind.LIST, "not a list"), List.of(), null)));
        StageAReport report = StageAChecker.check(rule,
                levels(CheckExpressionParser.parse("empty(AETERM)")));
        assertEquals(List.of(StageAErrorKind.CHECKER_FAILURE), kinds(report));
        assertTrue(report.armedFindings().isEmpty());
        // runAndApply never parks on it either
        StageAChecker.runAndApply(rule, levels(CheckExpressionParser.parse("empty(AETERM)")));
        assertNull(rule.getLoadError());
    }
}
