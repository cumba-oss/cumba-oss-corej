package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ Option A of {@code PLAN-hashed-join-arm-absent-columns} (owner, 2026-09-25): <b>a
 * {@code Child: true} entry disables the direct join</b>. It is joined only through its pointer —
 * {@code ChildMatchPreMerger} finds the parent row where {@code parent[row.IDVAR] == row.IDVARVAL}
 * and merges the parent's columns in bare — and {@code RuleRunner.buildJoinedDatasets} builds
 * <b>no</b> keyed {@link DatasetLookup} for it.
 *
 * <p>
 * The fixtures are the phase-1 probe's (findings §Appendix B): a SUPPAE with a record-level, a
 * subject-level ({@code IDVAR}/{@code IDVARVAL} both {@code ""}) and an orphan row; AE, CO and
 * RELREC beside it. The findings pinned below are the figures the probe <b>measured on the engine
 * before this change</b>, so a green here is the plan's <i>"a Child rule's findings are
 * unchanged"</i> claim, per rule and per primary — not a restatement of the new code.
 * </p>
 */
class ChildEntryDirectJoinTest
{

    private static final List<String> CHILD_KEYS = List.of("USUBJID", "IDVAR", "IDVARVAL");

    private static IDataTable suppae()
    {
        return RealTables.of("SUPPAE").str("STUDYID", "S", "S", "S")
                .str("RDOMAIN", "AE", "AE", "AE").str("USUBJID", "U1", "U1", "U2")
                .str("IDVAR", "AESEQ", "", "AESEQ").str("IDVARVAL", "1", "", "9")
                .str("QNAM", "AESOSP", "AESOSP", "AESOSP")
                .str("QVAL", "rec-level", "subj-level", "orphan").build();
    }


    private static IDataTable ae()
    {
        return RealTables.of("AE").str("STUDYID", "S", "S", "S").str("USUBJID", "U1", "U1", "U2")
                .lng("AESEQ", 1L, 2L, 1L).str("AESMIE", "N", "Y", "Y").build();
    }


    private static IDataTable co()
    {
        return RealTables.of("CO").str("STUDYID", "S", "S", "S").str("RDOMAIN", "AE", "", "AE")
                .str("USUBJID", "U1", "U1", "U2").str("IDVAR", "AESEQ", "", "AESEQ")
                .str("IDVARVAL", "1", "", "7").str("COVAL", "co-rec", "co-subj", "co-orphan")
                .build();
    }


    private static IDataTable relrec()
    {
        return RealTables.of("RELREC").str("STUDYID", "S", "S").str("RDOMAIN", "AE", "AE")
                .str("USUBJID", "U1", "U2").str("IDVAR", "AESEQ", "AESEQ").str("IDVARVAL", "1", "8")
                .str("RELID", "R1", "R2").build();
    }


    private static MatchDataset child(String name)
    {
        MatchDataset md = new MatchDataset();
        md.setName(name);
        md.setChild(true);
        md.setKeys(CHILD_KEYS);
        return md;
    }


    private static Rule rule(String id, List<MatchDataset> entries, String check,
            List<String> outputs)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("probe");
        outcome.setOutputVariables(outputs);
        rule.setOutcome(outcome);
        rule.setMatchDatasets(entries);
        rule.setCheck(new CheckConditionAll(
                List.of(new CheckConditionExpression(CheckExpressionParser.parse(check), check))));
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    /** {@code CDISC-CG0043} as shipped: one {@code AE} Child entry, a bare {@code AESMIE} read. */
    private static Rule cg0043()
    {
        return rule("CDISC-CG0043", List.of(child("AE")),
                "QNAM == \"AESOSP\" and var_exists(\"AESMIE\") and AESMIE != \"Y\"",
                List.of("QNAM", "AESMIE"));
    }


    /**
     * {@code CDISC-CG0371} as shipped: {@code SUPP--}, {@code CO}, {@code RELREC} Child entries.
     */
    private static Rule cg0371()
    {
        return rule("CDISC-CG0371", List.of(child("SUPP--"), child("CO"), child("RELREC")),
                "not empty(IDVAR) and not empty(IDVARVAL) and str(IDVARVAL) != str(colref(IDVAR))",
                List.of("RDOMAIN", "IDVAR", "IDVARVAL"));
    }


    private static List<String> fired(RuleExecutionResult r, String... columns)
    {
        List<String> out = new ArrayList<>();
        for (Violation v : r.getViolations())
        {
            StringBuilder sb = new StringBuilder("row").append(v.getRowNumber());
            for (String c : columns)
            {
                sb.append(' ').append(c).append('=').append(v.getValues().get(c));
            }
            out.add(sb.toString());
        }
        return out;
    }


    private static RuleExecutionResult execute(Rule rule, IDataTable primary)
    {
        return RuleRunnerCalls.execute(rule, primary,
                RealTables.inventoryOf(suppae(), ae(), co(), relrec()), null, null);
    }


    /**
     * ⭐ The change itself: a Child entry builds no direct lookup — and the control beside it, the
     * same entry without the flag, still does, so the empty map is the flag's doing and not an
     * unresolved dataset.
     */
    @Test
    void aChildEntryBuildsNoDirectLookup()
    {
        DatasetResolver res = RealTables.inventoryOf(suppae(), ae(), co(), relrec());
        Map<String, JoinLookup> joined = RuleRunner.buildJoinedDatasets(
                List.of(child("AE"), child("SUPP--"), child("CO"), child("RELREC")), suppae(), res,
                null, "R-TEST");
        assertEquals(Map.of(), joined,
                "option A: a Child: true entry is joined only through its pointer (the pre-merge)"
                        + " and builds no keyed lookup — any key here is the retired direct join");

        MatchDataset ordinary = child("AE");
        ordinary.setChild(false);
        Map<String, JoinLookup> control = RuleRunner.buildJoinedDatasets(List.of(ordinary),
                suppae(), res, null, "R-TEST");
        assertTrue(control.containsKey("AE"),
                "control: the SAME entry without Child: true still builds its lookup, so the"
                        + " empty map above is the flag's doing, not an unresolved AE");
    }


    /**
     * {@code CDISC-CG0043} on SUPPAE: the pre-merge still supplies {@code AESMIE} bare, so the
     * three findings the probe measured before the change (row 1 {@code N}; rows 2 and 3 with no
     * parent found, {@code AESMIE} empty) are the three findings now.
     */
    @Test
    void cg0043FindingsAreUnchangedOnSuppae()
    {
        RuleExecutionResult r = execute(cg0043(), suppae());
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertEquals(
                List.of("row1 QNAM=AESOSP AESMIE=N", "row2 QNAM=AESOSP AESMIE=",
                        "row3 QNAM=AESOSP AESMIE="),
                fired(r, "QNAM", "AESMIE"),
                "the phase-1 probe's as-shipped result; a fourth row or a missing AESMIE column"
                        + " would mean the pre-merge — the pointer join — stopped serving the rule");
    }


    /** {@code CDISC-CG0371} on each of its three primaries: the probe's one finding each. */
    @Test
    void cg0371FindingsAreUnchangedOnEveryPrimary()
    {
        RuleExecutionResult onSuppae = execute(cg0371(), suppae());
        assertEquals(RuleExecutionStatus.EXECUTED, onSuppae.getStatus(),
                onSuppae.getStatusMessage());
        assertEquals(List.of("row3 RDOMAIN=AE IDVAR=AESEQ IDVARVAL=9"),
                fired(onSuppae, "RDOMAIN", "IDVAR", "IDVARVAL"), "SUPPAE: only the orphan fires");

        RuleExecutionResult onCo = execute(cg0371(), co());
        assertEquals(RuleExecutionStatus.EXECUTED, onCo.getStatus(), onCo.getStatusMessage());
        assertEquals(List.of("row3 RDOMAIN=AE IDVAR=AESEQ IDVARVAL=7"),
                fired(onCo, "RDOMAIN", "IDVAR", "IDVARVAL"), "CO: only the orphan fires");

        RuleExecutionResult onRelrec = execute(cg0371(), relrec());
        assertEquals(RuleExecutionStatus.EXECUTED, onRelrec.getStatus(),
                onRelrec.getStatusMessage());
        assertEquals(List.of("row2 RDOMAIN=AE IDVAR=AESEQ IDVARVAL=8"),
                fired(onRelrec, "RDOMAIN", "IDVAR", "IDVARVAL"), "RELREC: only the orphan fires");
    }


    /**
     * ⭐ Follow-up 2, end to end: {@code SUPPAE._matched_} against a {@code SUPP--} Child entry used
     * to load clean (the entry's template name never equalled the qualifier) and then read a SUPPAE
     * self-join at run time — every row {@code true}. It is now the same load error every other
     * Child-entry flag is.
     */
    @Test
    void theSuppTemplateMatchedFlagHoleIsClosed()
    {
        RuleExecutionResult r = execute(rule("PROBE-suppae-matched",
                List.of(child("SUPP--"), child("CO")), "SUPPAE._matched_", List.of("USUBJID")),
                suppae());
        assertEquals(RuleExecutionStatus.ERROR, r.getStatus(),
                "the probe measured EXECUTED with every row matched through a self-join; after the"
                        + " fix the flag is refused at load: " + r.getStatusMessage());
        String message = String.valueOf(r.getStatusMessage());
        assertTrue(message.contains("MATCHED_FLAG_INVALID"), message);
        assertTrue(message.contains("SUPPAE._matched_"), message);
        assertTrue(message.contains("Child: true"), message);
    }


    /**
     * ⭐ Follow-up 1, end to end: a dotted read of a Child entry — the probe's authored
     * {@code CO.COVAL == "co-subj"}, which the retired lookup answered for the subject-level row —
     * is refused at load, not served by a join that no longer exists and not silently defaulted.
     */
    @Test
    void aDottedReadOfAChildEntryIsRefusedAtLoad()
    {
        RuleExecutionResult r = execute(
                rule("PROBE-co-dotted", List.of(child("SUPP--"), child("CO")),
                        "CO.COVAL == \"co-subj\"", List.of("USUBJID")),
                suppae());
        assertEquals(RuleExecutionStatus.ERROR, r.getStatus(),
                "the probe measured EXECUTED firing row 2 through the direct lookup; after option"
                        + " A the read is refused at load: " + r.getStatusMessage());
        String message = String.valueOf(r.getStatusMessage());
        assertTrue(message.contains("DOTTED_REF_CHILD_ENTRY"), message);
        assertTrue(message.contains("CO.COVAL"), message);
        assertFalse(r.getViolations().isEmpty(), "the ERROR carries its sentinel violation");
        assertNotNull(r.getViolations().get(0).getValues().get("__error__"));
    }


    /**
     * M2, end to end: {@code CDISC-CG0043} with {@code Output_Variables: [QNAM, AE.AESMIE]} used to
     * load clean and report findings WITHOUT the column — the direct lookup was gone, so the
     * violation builder skipped it silently. It is now the same load error as a dotted operand.
     */
    @Test
    void aDottedOutputVariableOfAChildEntryIsRefusedAtLoad()
    {
        RuleExecutionResult r = execute(rule("CDISC-CG0043-dotted-output", List.of(child("AE")),
                "QNAM == \"AESOSP\" and var_exists(\"AESMIE\") and AESMIE != \"Y\"",
                List.of("QNAM", "AE.AESMIE")), suppae());
        assertEquals(RuleExecutionStatus.ERROR, r.getStatus(), r.getStatusMessage());
        String message = String.valueOf(r.getStatusMessage());
        assertTrue(message.contains("DOTTED_REF_CHILD_ENTRY"), message);
        assertTrue(message.contains("Output_Variables entry AE.AESMIE"), message);
    }


    /**
     * A {@code DS.**X} operand is a {@code WILDCARD_COLUMN}, never a {@code DOTTED_REF}. In VALUE
     * position {@code ExprCompiler} reads it through a per-row dotted plan, which with no lookup
     * for the Child entry answers the not-supplied default on every row — SILENT, so stage A's
     * Child arm now judges a literal-qualified wildcard too (M2). Both shapes are refused at load;
     * the membership shape's run-time {@code SubstitutionException} is pinned as the backstop
     * below.
     */
    @Test
    void aQualifiedWildcardReadOfAChildEntryIsRefusedAtLoad()
    {
        RuleExecutionResult value = execute(rule("PROBE-ae-wildcard-value", List.of(child("AE")),
                "QNAM == \"AESOSP\" and AE.**SMIE != \"Y\"", List.of("QNAM")), suppae());
        assertEquals(RuleExecutionStatus.ERROR, value.getStatus(), value.getStatusMessage());
        assertTrue(String.valueOf(value.getStatusMessage()).contains("DOTTED_REF_CHILD_ENTRY"),
                value.getStatusMessage());
        RuleExecutionResult member = execute(rule("PROBE-ae-wildcard-member", List.of(child("AE")),
                "QNAM == \"AESOSP\" and \"Y\" in AE.**SMIE", List.of("QNAM")), suppae());
        assertEquals(RuleExecutionStatus.ERROR, member.getStatus(), member.getStatusMessage());
        assertTrue(String.valueOf(member.getStatusMessage()).contains("DOTTED_REF_CHILD_ENTRY"),
                member.getStatusMessage());
        RuleExecutionResult list = execute(rule("PROBE-ae-wildcard-list", List.of(child("AE")),
                "QNAM == \"AESOSP\" and \"Y\" in AE.AES${*}", List.of("QNAM")), suppae());
        assertEquals(RuleExecutionStatus.ERROR, list.getStatus(), list.getStatusMessage());
        assertTrue(String.valueOf(list.getStatusMessage()).contains("DOTTED_REF_CHILD_ENTRY"),
                list.getStatusMessage());
    }


    /**
     * The run-time picture for a package that bypasses the loader (the native expression set
     * directly, stage A never run), measured 2026-09-25 — the reason the load refusal above judges
     * a literal-qualified wildcard at all: a {@code **} read of the Child entry is SILENT in both
     * positions (value: the not-supplied default on every row, a flood; membership: an empty set,
     * no finding), and only the {@code ${*}} list-operand shape is loud, through
     * {@code ValueResolver}'s {@code SubstitutionException}. ⚠ Review round 1 had assumed the
     * {@code **} shape shared that loudness; it does not.
     */
    @Test
    void withoutStageAOnlyTheListWildcardIsLoud()
    {
        RuleExecutionResult value = execute(uncheckedRule("PROBE-ae-wildcard-value-raw",
                "QNAM == \"AESOSP\" and AE.**SMIE != \"Y\""), suppae());
        assertEquals(RuleExecutionStatus.EXECUTED, value.getStatus(), value.getStatusMessage());
        assertEquals(3, value.getViolations().size(),
                "the not-supplied default on every row: \"\" != \"Y\" fires all three SUPPAE"
                        + " rows — the silent flood only the load refusal prevents");

        RuleExecutionResult member = execute(uncheckedRule("PROBE-ae-wildcard-member-raw",
                "QNAM == \"AESOSP\" and \"Y\" in AE.**SMIE"), suppae());
        assertEquals(RuleExecutionStatus.EXECUTED, member.getStatus(), member.getStatusMessage());
        assertEquals(0, member.getViolations().size(),
                "membership over a ** read of the Child entry is an empty set on every row — a"
                        + " silent no-finding, not a SubstitutionException");

        Rule list = uncheckedRule("PROBE-ae-wildcard-list-raw",
                "QNAM == \"AESOSP\" and \"Y\" in AE.AES${*}");
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                OperandSubstitutor.SubstitutionException.class, () -> execute(list, suppae()),
                "the ${*} list operand is the one shape ValueResolver refuses without a lookup");
        assertTrue(String.valueOf(thrown.getMessage()).contains("foreign dataset `AE`"),
                thrown.getMessage());
    }


    /** A Child-entry rule with its native expression set directly, so stage A never runs. */
    private static Rule uncheckedRule(String id, String check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("probe");
        outcome.setOutputVariables(List.of("QNAM"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(child("AE")));
        rule.setCheck(new CheckConditionAll(
                List.of(new CheckConditionExpression(CheckExpressionParser.parse(check), check))));
        rule.setCheckExpr(CheckExpressionParser.parse(check));
        return rule;
    }
}
