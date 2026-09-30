package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code distinct} through the loader and {@link RuleRunner} (runbook W7,
 * {@code PLAN-distinct-function} §2.2 / §2.3 / §4.2): the value set (D-W7-2 — a missing or blank
 * cell is not a member, a numeric cell its canonical text, an expression target), the tuple set
 * (D-W7-3 — {@code MissingMember} identity, the {@code IDVARVAL} join token, an absent column's
 * default, the inline right-hand side), the filter shapes, {@code domain=} both spellings and the
 * empty answers, the grouped per-row lists (no group ⇒ empty, both {@code keep_missings}, the
 * key-type ERROR, {@code contains} over a per-row list), the memo, and the strict reader's load
 * errors (a list target with {@code group=}, {@code keep_missings} without one, R1).
 */
class DistinctFunctionTest
{

    /** A JSON-escaped double-quoted string for a rule text: {@code q("Y")} spells {@code "Y"}. */
    private static String q(String s)
    {
        return "\\\"" + s + "\\\"";
    }


    private static Rule load(String binding, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":{"
                    + "\"Core\":{\"Id\":\"X-1\"},"
                    + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"" + binding + "\"}],"
                    + "\"Check\":{\"expression\":\"" + check + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + binding, e);
        }
    }


    private static Rule loaded(String binding, String check)
    {
        Rule rule = load(binding, check);
        assertNull(rule.getLoadError(), "expected the binding to load: " + rule.getLoadError());
        return rule;
    }


    private static void expectError(String binding, String fragment)
    {
        Rule rule = load(binding, "USUBJID not in $v");
        assertNotNull(rule.getLoadError(), "expected a load error for " + binding);
        String error = rule.getLoadError();
        assertTrue(error.contains(fragment), () -> "load error for `" + binding
                + "` does not name `" + fragment + "`: " + error);
    }


    private static DatasetResolver resolver(IDataTable... tables)
    {
        return name ->
        {
            for (IDataTable t : tables)
            {
                if (t.getMetaData().getName().equalsIgnoreCase(name))
                {
                    return t;
                }
            }
            return null;
        };
    }


    private static RuleExecutionResult runRaw(String binding, String check, IDataTable primary,
            IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = primary;
        System.arraycopy(others, 0, all, 1, others.length);
        return RuleRunnerCalls.execute(loaded(binding, check), primary, resolver(all));
    }


    private static int fires(String binding, String check, IDataTable primary, IDataTable... others)
    {
        RuleExecutionResult result = runRaw(binding, check, primary, others);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }


    /** Evaluates the call directly over {@code ctx}'s table and answers the vector. */
    private static Vector vector(String call, EvaluationContext ctx)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        return v;
    }


    /** The dataset-level list of an ungrouped call. */
    private static List<?> list(String call, EvaluationContext ctx)
    {
        Vector v = vector(call, ctx);
        assertInstanceOf(ConstVector.class, v, "an ungrouped distinct is one dataset-level list");
        return (List<?>) v.value(0).resolved();
    }


    /** The per-row lists of a grouped call. */
    private static List<List<?>> rows(String call, EvaluationContext ctx)
    {
        Vector v = vector(call, ctx);
        List<List<?>> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add((List<?>) v.value(row).resolved());
        }
        return out;
    }


    private static Primitives.MissingMember mis()
    {
        return new Primitives.MissingMember(MissingValue.MIS);
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------


    /** DM: three subjects — S3 has no DS row. */
    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM", "DM", "DM")
                .str("USUBJID", "S1", "S2", "S3").str("ARM", "Placebo", "Drug A", "Drug A").build();
    }


    /** DS: S1 twice, S2 once, S4 (no DM row) once; one blank DSSTDTC; one DEATH. */
    private static IDataTable ds()
    {
        return RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS", "DS")
                .str("USUBJID", "S1", "S1", "S2", "S4")
                .str("DSSTDTC", "2024-01-14", "", "2024-03-01", "2024-12-31")
                .str("DSDECOD", "RANDOMIZED", "DEATH", "COMPLETED", "COMPLETED").build();
    }


    /** SV: S1 visits 1 and 2, S2 visit 1 (numeric VISITNUM). */
    private static IDataTable sv()
    {
        return RealTableFixture.of("SV").str("DOMAIN", "SV", "SV", "SV")
                .str("USUBJID", "S1", "S1", "S2").dbl("VISITNUM", 1.0, 2.0, 1.0).build();
    }


    /** LB: S1 at visit 2, S2 at visits 2 and 3, S3 at visit 1. */
    private static IDataTable lb()
    {
        return RealTableFixture.of("LB").str("DOMAIN", "LB", "LB", "LB", "LB")
                .str("USUBJID", "S1", "S2", "S2", "S3").dbl("VISITNUM", 2.0, 2.0, 3.0, 1.0)
                .str("LBBLFL", "Y", "", "", "").build();
    }

    // -----------------------------------------------------------------------
    // the value set
    // -----------------------------------------------------------------------


    @Test
    void theValueSetIsTheDistinctPresentValuesInRowOrder()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(ds()).build();
        assertEquals(List.of("S1", "S2", "S4"), list("distinct(USUBJID)", ctx));
        assertEquals(List.of("RANDOMIZED", "DEATH", "COMPLETED"), list("distinct(DSDECOD)", ctx));
        // the membership consumers: S3 has no DS row
        assertEquals(1, fires("distinct(USUBJID, domain=DS)", "USUBJID not in $v", dm(), ds()));
        assertEquals(2,
                fires("distinct(USUBJID, domain=" + q("DS") + ")", "USUBJID in $v", dm(), ds()),
                "D10: the quoted domain too");
    }


    @Test
    void aMissingOrBlankCellIsNotAMember()
    {
        // D-W7-2: the retired evaluator's contract, carried — a missing cell and "" are skipped
        // on the set side; on the probe side a missing is a member of nothing (D13) and "" is a
        // present value that is not in the set.
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS", "DS")
                .str("USUBJID", "S1", "", null, "S1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ds).build();
        assertEquals(List.of("S1"), list("distinct(USUBJID)", ctx));
        IDataTable dm = RealTableFixture.of("DM").str("DOMAIN", "DM", "DM", "DM")
                .str("USUBJID", "S1", "", null).str("SITE", "A", "B", "C").build();
        assertEquals(2, fires("distinct(USUBJID, domain=DS)",
                "not empty(SITE) and USUBJID not in $v", dm, ds),
                "the blank and the missing probe are not in {S1}");
        assertEquals(1,
                fires("distinct(USUBJID, domain=DS)", "not empty(SITE) and USUBJID in $v", dm, ds));
    }


    @Test
    void aNumericColumnContributesItsCanonicalText()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(sv()).build();
        assertEquals(List.of("1", "2"), list("distinct(VISITNUM)", ctx),
                "an integral double renders without the .0, as the retired evaluator's text did");
        // the probe side compares as == does (D81): a numeric LB cell meets the text member
        assertEquals(1, fires("distinct(VISITNUM, domain=SV)", "VISITNUM not in $v", lb(), sv()),
                "only visit 3 (S2) is absent from SV's study-wide {1, 2}");
    }


    @Test
    void theFilterShapesOfTheRetiredMapForm()
    {
        // D-W7-4: equality, membership, two keys; a blank never matches a value; an absent
        // filter column matches nothing (the map form's contract).
        assertEquals(2,
                fires("distinct(USUBJID, domain=DS, filter=(DSDECOD == " + q("DEATH") + "))",
                        "USUBJID not in $v", dm(), ds()),
                "only S1 died");
        assertEquals(1, fires("distinct(USUBJID, domain=DS, filter=(DSDECOD in [" + q("DEATH")
                + ", " + q("COMPLETED") + "]))", "USUBJID not in $v", dm(), ds()));
        assertEquals(2, fires("distinct(USUBJID, domain=DS, filter=(DSDECOD == " + q("COMPLETED")
                + " and not empty(DSSTDTC)))", "USUBJID not in $v", dm(), ds()));
        assertEquals(3, fires("distinct(USUBJID, domain=DS, filter=(NOPE == " + q("x") + "))",
                "USUBJID not in $v", dm(), ds()), "an absent filter column keeps no row");
    }


    @Test
    void anAbsentDatasetOrColumnAnswersTheEmptyList()
    {
        // Q17-a (the retired EmptyResult.SET): `not in` fires everywhere, `in` nowhere — and the
        // Check still
        // runs (the absent-dataset SKIP is the loader's gate, not the function's).
        assertEquals(3, fires("distinct(USUBJID, domain=DS)", "USUBJID not in $v", dm()));
        assertEquals(0, fires("distinct(USUBJID, domain=DS)", "USUBJID in $v", dm()));
        assertEquals(3, fires("distinct(NOPE, domain=DS)", "USUBJID not in $v", dm(), ds()));
        EvaluationContext ctx = EvaluationContext.builder().table(dm()).build();
        assertEquals(List.of(), list("distinct(NOPE)", ctx));
    }


    @Test
    void theUngroupedSetIsOneDatasetLevelValue()
    {
        // D-W7-12: a Check reading only the set fires ONCE per dataset — the target and the
        // filter are parameters of the target table, not row reads of the primary.
        RuleExecutionResult result = runRaw("distinct(USUBJID, domain=DS)",
                "not contains($v, " + q("S3") + ")", dm(), ds());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "one dataset-level finding, not one per row");
        assertEquals(0, runRaw("distinct(USUBJID, domain=DS)", "not contains($v, " + q("S1") + ")",
                dm(), ds()).getViolations().size());
    }


    @Test
    void anExpressionTargetIsEvaluatedOverTheTargetTable()
    {
        // D-W7-10 (D14 / D119c): the value set of an expression, computed on the target table.
        EvaluationContext ctx = EvaluationContext.builder().table(dm()).build();
        assertEquals(List.of("PLACEBO", "DRUG A"), list("distinct(upper(ARM))", ctx));
        IDataTable dx = RealTableFixture.of("DX").str("DOMAIN", "DX").str("USUBJID", "S9")
                .str("ARM", "Drug B").build();
        assertEquals(3, fires("distinct(upper(ARM), domain=DX)", "upper(ARM) not in $v", dm(), dx),
                "PLACEBO and DRUG A (three rows) are not in the other dataset's {DRUG B}; the"
                        + " probe is the same expression on the primary");
    }

    // -----------------------------------------------------------------------
    // the tuple set
    // -----------------------------------------------------------------------


    /** PC: three pairs, the second with a MISSING nominal day (a bare NaN is the plain missing). */
    private static IDataTable pc()
    {
        return RealTableFixture.of("PC").str("DOMAIN", "PC", "PC", "PC", "PC")
                .dbl("PCNOMDY", 1.0, Double.NaN, 7.0, 1.0)
                .str("PCTPTREF", "Day 1", "Day 0", "Day 7", "Day 1").build();
    }


    @Test
    void theTupleSetKeepsAMissingComponentsIdentity()
    {
        // D-W7-3: one unmodifiable list of components per distinct kept row; a missing cell is
        // its MissingMember — never "", never ".", never 0.
        EvaluationContext ctx = EvaluationContext.builder().table(pc()).build();
        assertEquals(List.of(List.of("1", "Day 1"), List.of(mis(), "Day 0"), List.of("7", "Day 7")),
                list("distinct([PCNOMDY, PCTPTREF])", ctx));
        IDataTable pp = RealTableFixture.of("PP").str("DOMAIN", "PP", "PP", "PP", "PP")
                .str("USUBJID", "A", "B", "C", "D").dbl("PPNOMDY", 1.0, 0.0, Double.NaN, 7.0)
                .str("PPTPTREF", "Day 1", "Day 0", "Day 0", "Day 7").build();
        RuleExecutionResult result = runRaw("distinct([PCNOMDY, PCTPTREF], domain=PC)",
                "tuple(PPNOMDY, PPTPTREF) not in $v", pp, pc());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "(0, Day 0) is not (missing, Day 0); (missing, Day 0) IS — D34 #5-2");
        assertEquals("B", result.getViolations().get(0).getValues().get("USUBJID"));
    }


    @Test
    void anAbsentTupleColumnIsItsTypeDefaultOnBothSides()
    {
        // NVE: an absent reference column is the constant "" (a character expectation), the
        // same constant the tuple(…) probe folds an absent primary column to — so the two agree.
        EvaluationContext ctx = EvaluationContext.builder().table(ds()).build();
        assertEquals(List.of(List.of("S1", ""), List.of("S2", ""), List.of("S4", "")),
                list("distinct([USUBJID, NOPE])", ctx));
        assertEquals(1, fires("distinct([USUBJID, NOPE], domain=DS)",
                "tuple(USUBJID, NOPE) not in $v", dm(), ds()), "only S3 has no DS pair");
    }


    @Test
    void anIdvarvalComponentIsTheJoinToken()
    {
        // Review H1 (2026-08-19), carried verbatim: SAS padding, a zero-padded integer, a float
        // rendering and an explicit sign all canonicalise to the token "1"; a non-numeric token is
        // only stripped, so Char keys keep their identity.
        IDataTable suppae = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE", "AE", "AE", "AE")
                .str("USUBJID", "001", "001", "002", "002").str("IDVARVAL", " 1", "01", "1.0", "+1")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(suppae).build();
        assertEquals(List.of(List.of("001", "1"), List.of("002", "1")),
                list("distinct([USUBJID, IDVARVAL])", ctx));
        IDataTable chars = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE", "AE")
                .str("USUBJID", "001", "001").str("IDVARVAL", " H-14 ", "H-15").build();
        assertEquals(List.of(List.of("001", "H-14"), List.of("001", "H-15")), list(
                "distinct([USUBJID, IDVARVAL])", EvaluationContext.builder().table(chars).build()));
        // end to end: the probe side's numeric AESEQ renders "1" and meets the token
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE", "AE")
                .str("USUBJID", "001", "002", "003").dbl("AESEQ", 1.0, 1.0, 1.0).build();
        assertEquals(1,
                fires("distinct([USUBJID, IDVARVAL], domain=SUPPAE, filter=(RDOMAIN == " + q("AE")
                        + "))", "tuple(USUBJID, AESEQ) not in $v", ae, suppae),
                "003 has no SUPPAE key; 001 / 002 meet their padded tokens");
    }


    @Test
    void anInlineTupleSetOnTheRightHandSideReadsTheSameList()
    {
        // A list-valued registry call written inline as the membership right-hand side answers
        // exactly as the $-binding holding the same call (the retired inline-operation route).
        Rule rule = loaded("distinct(USUBJID)",
                "tuple(USUBJID, VISITNUM) not in distinct([USUBJID, VISITNUM], domain=SV)");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, lb(), resolver(lb(), sv()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(3, result.getViolations().size(),
                "S1/2 is in SV; S2/2, S2/3 and S3/1 are not");
        assertEquals(3, fires("distinct([USUBJID, VISITNUM], domain=SV)",
                "tuple(USUBJID, VISITNUM) not in $v", lb(), sv()));
    }

    // -----------------------------------------------------------------------
    // grouped
    // -----------------------------------------------------------------------


    @Test
    void groupedAnswersEachRowItsGroupsListAndTheEmptyListWithoutAGroup()
    {
        // S1 reads SV's {1, 2}; S2 reads {1}; S3 has no SV group and reads the empty list (never
        // missing, D-W7-8), so `not in` fires on it.
        String call = "distinct(VISITNUM, domain=SV, group=[USUBJID])";
        assertEquals(3, fires(call, "VISITNUM not in $v", lb(), sv()),
                "S2/2, S2/3 and S3/1 are not in their subject's SV visits; S1/2 is");
        assertEquals(1, fires(call, "VISITNUM in $v", lb(), sv()));
        assertEquals(1, fires(call, "empty($v)", lb(), sv()),
                "a list is never missing: `empty` holds on S3's EMPTY list only");
        EvaluationContext ctx = EvaluationContext.builder().table(sv()).build();
        assertEquals(List.of(List.of("1", "2"), List.of("1", "2"), List.of("1")),
                rows("distinct(VISITNUM, group=[USUBJID])", ctx));
        // with a filter: S1 keeps visit 2 only
        assertEquals(List.of(List.of("2"), List.of("2"), List.of()),
                rows("distinct(VISITNUM, group=[USUBJID], filter=(VISITNUM > 1))", ctx));
    }


    @Test
    void containsProbesAPerRowListForAMember()
    {
        // The FDA-SD0006 shape: `not contains($blfl, "Y")` — subjects without a baseline flag
        // fire on every row (S2 twice, S3 once); a `--` target resolves on the primary.
        // (the runner's per-dataset specialisation resolves `--` against the domain prefix,
        // so the prefix is handed to it as the corpus run hands it)
        RuleExecutionResult dashes = RuleRunnerCalls.execute(
                loaded("distinct(--BLFL, group=[USUBJID])", "not contains($v, " + q("Y") + ")"),
                lb(), resolver(lb()), "LB", null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, dashes.getStatus(), dashes.getStatusMessage());
        assertEquals(3, dashes.getViolations().size());
        assertEquals(1,
                fires("distinct(LBBLFL, group=[USUBJID])", "contains($v, " + q("Y") + ")", lb()));
    }


    @Test
    void theShippedDefaultKeepsABlankKeyGroupAndKeepMissingsFalseDropsIt()
    {
        IDataTable lb = RealTableFixture.of("LB").str("DOMAIN", "LB", "LB").str("USUBJID", "S1", "")
                .dbl("VISITNUM", 1.0, 9.0).str("SITE", "A", "B").build();
        IDataTable sv = RealTableFixture.of("SV").str("DOMAIN", "SV", "SV", "SV")
                .str("USUBJID", "S1", "", "").dbl("VISITNUM", 1.0, 9.0, 8.0).build();
        // The Check reads SITE (a USUBJID guard would hide the blank row).
        assertEquals(0,
                runRaw("distinct(VISITNUM, domain=SV, group=[USUBJID])",
                        "not empty(SITE) and VISITNUM not in $v", lb, sv).getViolations().size(),
                "KEEP (default): the blank key is a group holding {9, 8}");
        assertEquals(1,
                runRaw("distinct(VISITNUM, domain=SV, group=[USUBJID], keep_missings=false)",
                        "not empty(SITE) and VISITNUM not in $v", lb, sv).getViolations().size(),
                "DROP: the blank key forms no group, so the blank LB row reads the empty list");
    }


    @Test
    void aCharNumKeyPairBetweenTheTwoTablesErrorsTheRule()
    {
        IDataTable numericKeyLb = RealTableFixture.of("LB").str("DOMAIN", "LB", "LB")
                .lng("USUBJID", 1L, 2L).dbl("VISITNUM", 1.0, 1.0).build();
        RuleExecutionResult result = runRaw("distinct(VISITNUM, domain=SV, group=[USUBJID])",
                "VISITNUM not in $v", numericKeyLb, sv());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "GKI Q2: a Char/Num key pair never meets silently");
        assertTrue(String.valueOf(result.getStatusMessage()).contains("USUBJID"),
                result.getStatusMessage());
    }


    @Test
    void anAbsentGroupColumnPartitionsNothing()
    {
        // EC-44 over names: SV has no SITEID, so the grouping falls to USUBJID alone.
        assertEquals(3, fires("distinct(VISITNUM, domain=SV, group=[USUBJID, SITEID])",
                "VISITNUM not in $v", lb(), sv()));
    }


    @Test
    void theTargetTableIsScannedOnceForAnExecution()
    {
        // The aggregate memo: two reads of one binding in one execution resolve the target table
        // once (grouped and ungrouped alike).
        AtomicInteger resolves = new AtomicInteger();
        IDataTable sv = sv();
        DatasetResolver counting = name ->
        {
            resolves.incrementAndGet();
            return "SV".equalsIgnoreCase(name) ? sv : null;
        };
        EvaluationContext ctx = EvaluationContext.builder().table(lb()).datasetResolver(counting)
                .build();
        rows("distinct(VISITNUM, domain=SV, group=[USUBJID])", ctx);
        rows("distinct(VISITNUM, domain=SV, group=[USUBJID])", ctx);
        assertEquals(1, resolves.get(), "one grouping per execution");
        list("distinct(VISITNUM, domain=SV)", ctx);
        list("distinct(VISITNUM, domain=SV)", ctx);
        assertEquals(2, resolves.get(), "one scan per execution for the ungrouped set too");
    }

    // -----------------------------------------------------------------------
    // the strict reader (load time)
    // -----------------------------------------------------------------------


    @Test
    void aListTargetWithAGroupIsALoadError()
    {
        // P-Q8's first silent drop, closed: the operation ignored the group. RED before the port
        // (the binding loaded and the tuple dispatch ran first).
        expectError("distinct([ARMCD, ARM], domain=TA, group=[USUBJID])", "takes no group=");
        // the well-formed controls
        assertNull(load("distinct([ARMCD, ARM], domain=TA)", "tuple(ARMCD, ARM) not in $v")
                .getLoadError());
        assertNull(load("distinct(ARMCD, domain=TA, group=[USUBJID])", "ARMCD not in $v")
                .getLoadError());
    }


    @Test
    void keepMissingsWithoutAGroupIsALoadError()
    {
        // The retired parser's rule, carried (D-W7-9): with no grouping key it would have no
        // effect.
        expectError("distinct(ARMCD, domain=TA, keep_missings=true)", "requires a non-empty");
        expectError("distinct(ARMCD, group=[USUBJID], keep_missings=" + q("yes") + ")",
                "boolean literal");
        // the well-formed control: a boolean literal beside a group loads
        assertNull(load("distinct(ARMCD, domain=TA, group=[USUBJID], keep_missings=true)",
                "ARMCD not in $v").getLoadError());
    }


    @Test
    void theRetiredSpellingsAndTheMalformedShapesAreLoadErrors()
    {
        // R1: a quoted name is a string, never a column — in both target shapes
        expectError("distinct(" + q("ARMCD") + ", domain=TA)", "takes a column reference");
        expectError("distinct([ARMCD, " + q("ARM") + "], domain=TA)", "takes a column reference");
        expectError("distinct([], domain=TA)", "names no column");
        expectError("distinct(domain=TA)", "requires argument 'name'");
        expectError("distinct(ARMCD, ARM, domain=TA)", "distinct");
        // the retired value_is_reference is no parameter (its shape is
        // referenced_dataset_variables)
        expectError("distinct(IDVAR, value_is_reference=true)", "value_is_reference");
        expectError("distinct(ARMCD, domain=TA, filter=filter(ARM=" + q("x") + "))",
                "filter=(COLUMN == \"value\")");
        expectError("distinct(ARMCD, domain=TA, filter=ARM)", "boolean expression");
        expectError("distinct(--BLFL, domain=TA)", "does not resolve `--`");
        expectError("distinct(DM.USUBJID)", "written bare, not dotted");
        expectError("distinct($x)", "must be a plain column reference");
        expectError("distinct(ARMCD, group=" + q("USUBJID") + ")",
                "takes a list of column references");
        expectError("distinct(ARMCD, domain=1)", "dataset reference");
        // the well-formed control
        assertNull(
                load("distinct(ARMCD, domain=" + q("TA") + ", group=[USUBJID], keep_missings=false,"
                        + " filter=(ARM == " + q("Y") + "))", "ARMCD not in $v").getLoadError());
    }


    @Test
    void anInlineCallInACheckMeetsTheReaderAtLoad()
    {
        Rule rule = load("distinct(USUBJID)",
                "USUBJID not in distinct(USUBJID, domain=DS, filter=filter(DSDECOD=" + q("DEATH")
                        + "))");
        assertNotNull(rule.getLoadError(), "the inline Check surface is loud at load too");
        assertTrue(rule.getLoadError().contains("filter=(COLUMN == \"value\")"),
                rule.getLoadError());
        // the inline value set evaluates as the binding does
        RuleExecutionResult inline = RuleRunnerCalls.execute(
                loaded("distinct(USUBJID)", "USUBJID not in distinct(USUBJID, domain=DS)"), dm(),
                resolver(dm(), ds()));
        assertEquals(RuleExecutionStatus.EXECUTED, inline.getStatus(), inline.getStatusMessage());
        assertEquals(1, inline.getViolations().size());
    }


    @Test
    void theRuleLoadsWithTheBindingReadThroughAVariablesMap()
    {
        // the direct API with a $-binding in the variables map: a foreign scan on the resolver
        EvaluationContext ctx = EvaluationContext.builder().table(dm())
                .datasetResolver(resolver(dm(), ds())).variables(Map.of()).build();
        assertEquals(List.of("S1", "S2", "S4"), list("distinct(USUBJID, domain=DS)", ctx));
    }

    // -----------------------------------------------------------------------
    // combined review of runbook W2–W8
    // -----------------------------------------------------------------------


    @Test
    void aGroupedSetWithNoGroupIsOneConstantEmptyList()
    {
        // XCUT PERF 2: an absent dataset answers the empty list on every row — as ONE constant.
        // RED before the fix: a ComputedVector.
        EvaluationContext ctx = EvaluationContext.builder().table(lb()).build();
        Vector v = vector("distinct(VISITNUM, domain=SV, group=[USUBJID])", ctx);
        assertInstanceOf(ConstVector.class, v);
        assertEquals(List.of(), v.value(3).resolved());
    }


    @Test
    void aDoubleDashInTheDomainLiteralResolvesAgainstTheEvaluatedDataset()
    {
        // F-W8-2: `domain="SUPP--"` on AE is SUPPAE (DatasetIdentity.resolveWildcard, Fix #33),
        // as crossDatasetVariableMetadata resolves it. RED before the fix: SUPP-- was looked up
        // verbatim and answered the empty set.
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE").str("USUBJID", "S1").build();
        IDataTable suppae = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE", "AE")
                .str("USUBJID", "S1", "S1").str("QNAM", "AESOSP", "AETRTEM").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae)
                .datasetResolver(resolver(ae, suppae)).build();
        assertEquals(List.of("AESOSP", "AETRTEM"), list("distinct(QNAM, domain=\"SUPP--\")", ctx));
    }


    @Test
    void aPositionalDomainIsALoadErrorNamingTheKeyword()
    {
        // W5W6 M1's shape on distinct: `distinct(USUBJID, DS)` bound DS as domain= (D9) while the
        // load-time readers read the keyword only. RED before the fix: it loaded.
        expectError("distinct(USUBJID, DS)", "write the others as keywords");
        assertNull(load("distinct(USUBJID, domain=DS)", "USUBJID not in $v").getLoadError());
    }
}
