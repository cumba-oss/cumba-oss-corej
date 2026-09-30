package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code max} / {@code max_date} / {@code min_date} — and {@code read_value}'s {@code group=} —
 * through the loader and {@link RuleRunner} (runbook W5, {@code PLAN-grouped-aggregate-functions}
 * §2.2 / §2.3 / §4.2): the grouped cross-dataset broadcast, the type-ranked {@code max}, the EC-46
 * / EC-51 date extremes, both missing-key policies, the missing answers, the key-type ERROR, the
 * expression target, and the strict reader's load errors.
 */
class GroupedAggregateFunctionsTest
{

    private static Rule load(String binding, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":{"
                    + "\"Core\":{\"Id\":\"X-1\"},"
                    + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"" + binding + "\"}],"
                    + "\"Check\":{\"expression\":\"" + check + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\",\"$v\"]}}}}");
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
        Rule rule = load(binding, "empty($v)");
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


    /** Runs a row-based Check (the {@code USUBJID} guard makes the per-row broadcast visible). */
    private static RuleExecutionResult run(String binding, String check, IDataTable primary,
            IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = primary;
        System.arraycopy(others, 0, all, 1, others.length);
        return RuleRunnerCalls.execute(loaded(binding, "not empty(USUBJID) and (" + check + ")"),
                primary, resolver(all));
    }


    private static int fires(String binding, String check, IDataTable primary, IDataTable... others)
    {
        RuleExecutionResult result = run(binding, check, primary, others);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------


    /** ADLB: two subjects, one parameter, a baseline-flagged row each plus post-baseline rows. */
    private static IDataTable adlb()
    {
        return RealTableFixture.of("ADLB").str("USUBJID", "S1", "S1", "S1", "S2", "S2")
                .str("PARAMCD", "ALT", "ALT", "ALT", "ALT", "ALT")
                .str("ABLFL", "Y", "", "", "Y", "").dbl("AVAL", 5.0, 9.0, 2.0, 7.0, 1.0)
                .str("ATOXGR", "9", "10", "2", "3", "1").lng("AVISITN", 0L, 1L, 2L, 0L, 1L).build();
    }


    /** DM: three subjects — S3 has no DS rows. */
    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM", "DM", "DM")
                .str("USUBJID", "S1", "S2", "S3").build();
    }


    /** DS: S1 twice, S2 once, S4 (no DM row) once. */
    private static IDataTable ds()
    {
        return RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS", "DS")
                .str("USUBJID", "S1", "S1", "S2", "S4")
                .str("DSSTDTC", "2024-01-14", "2024-07-02", "2024-03-01", "2024-12-31")
                .lng("DSSTDY", 14L, 183L, 60L, 365L)
                .str("DSDECOD", "RANDOMIZED", "COMPLETED", "COMPLETED", "COMPLETED").build();
    }

    private static final String BASELINE = "filter=(ABLFL == \\\"Y\\\")";

    // -----------------------------------------------------------------------
    // max
    // -----------------------------------------------------------------------

    @Test
    void maxRanksANumericTargetNumericallyPerGroupAndHonoursTheFilter()
    {
        // S1's baseline AVAL is 5 (the post-baseline 9 is filtered out); S2's is 7.
        assertEquals(3,
                fires("max(AVAL, group=[USUBJID, PARAMCD], " + BASELINE + ")", "$v == 5", adlb()),
                "every S1 row reads its group's baseline maximum");
        assertEquals(2,
                fires("max(AVAL, group=[USUBJID, PARAMCD], " + BASELINE + ")", "$v == 7", adlb()));
        assertEquals(3, fires("max(AVAL, group=[USUBJID, PARAMCD])", "$v == 9", adlb()),
                "without the filter the group's maximum is the post-baseline 9");
    }


    @Test
    void maxAnswersTheWinningCellWithItsType()
    {
        RuleExecutionResult result = run("max(AVAL, group=[USUBJID, PARAMCD], " + BASELINE + ")",
                "$v == 5", adlb());
        assertEquals("5", result.getViolations().get(0).getValues().get("$v"),
                "a DOUBLE cell renders as the cell does, not as a boxed 5.0 text");
        RuleExecutionResult longs = run("max(AVISITN, group=[USUBJID, PARAMCD])", "$v == 2",
                adlb());
        assertEquals(3, longs.getViolations().size(), longs.getStatusMessage());
        assertEquals("2", longs.getViolations().get(0).getValues().get("$v"));
    }


    @Test
    void maxRanksACharacterTargetAsText()
    {
        // S1: "9", "10", "2" — as text "9" wins ("10" < "9"); numerically 10 would.
        assertEquals(3, fires("max(ATOXGR, group=[USUBJID, PARAMCD])", "$v == \\\"9\\\"", adlb()),
                "D-W1-2a: a string is a string");
        assertEquals(0, fires("max(ATOXGR, group=[USUBJID, PARAMCD])", "$v == \\\"10\\\"", adlb()));
        // The consumer comparison is text against text: "3.0" is not "3".
        IDataTable decimal = RealTableFixture.of("ADLB").str("USUBJID", "S1", "S1")
                .str("PARAMCD", "ALT", "ALT").str("ABLFL", "Y", "").str("ATOXGR", "3", "1")
                .str("BTOXGR", "3.0", "3.0").build();
        assertEquals(2, fires("max(ATOXGR, group=[USUBJID, PARAMCD], " + BASELINE + ")",
                "BTOXGR != $v", decimal));
    }


    @Test
    void aNumericOrderOverACharacterTargetIsTheAuthorsNumConversion()
    {
        // D14 / D119c: the target is any VALUE expression over the target table; num() is how a
        // rule asks for numeric order of a Char grade.
        assertEquals(3, fires("max(num(ATOXGR), group=[USUBJID, PARAMCD])", "$v == 10", adlb()));
        assertEquals(2, fires("max(num(ATOXGR), group=[USUBJID, PARAMCD])", "$v == 3", adlb()));
    }


    @Test
    void aMixedCharacterGroupKeepsRankingAsText()
    {
        // The retired evaluator's global numeric switch would have dropped a group holding only
        // non-parsable text once any other group parsed; every group ranks as text now.
        IDataTable mixed = RealTableFixture.of("ADLB").str("USUBJID", "S1", "S1", "S2", "S2")
                .str("PARAMCD", "ALT", "ALT", "ALT", "ALT")
                .str("ANRIND", "HIGH", "NORMAL", "LOW", "HIGH").build();
        assertEquals(2,
                fires("max(ANRIND, group=[USUBJID, PARAMCD])", "$v == \\\"NORMAL\\\"", mixed));
        assertEquals(2, fires("max(ANRIND, group=[USUBJID, PARAMCD])", "$v == \\\"LOW\\\"", mixed));
    }


    @Test
    void aGroupWithNoCandidateAnswersAMissing()
    {
        // S1: blank-only cells (present, no candidate) => the computed missing; S2: every cell
        // missing => the carried missing; both are `empty`.
        IDataTable table = RealTableFixture.of("ADLB").str("USUBJID", "S1", "S1", "S2", "S2")
                .str("PARAMCD", "ALT", "ALT", "ALT", "ALT").str("ATOXGR", "", " ", null, null)
                .lng("AVAL", 1L, 2L, null, null).build();
        assertEquals(4, fires("max(ATOXGR, group=[USUBJID, PARAMCD])", "empty($v)", table));
        assertEquals(2, fires("max(AVAL, group=[USUBJID, PARAMCD])", "empty($v)", table),
                "S2's all-missing AVAL group is missing; S1's is 2");
        assertEquals(2, fires("max(AVAL, group=[USUBJID, PARAMCD])", "$v == 2", table));
        assertEquals(0, fires("max(AVAL, group=[USUBJID, PARAMCD], filter=(PARAMCD == \\\"X\\\"))",
                "$v == 2", table), "a filter that keeps no row leaves no candidate");
    }


    @Test
    void theShippedDefaultKeepsABlankKeyGroupAndKeepMissingsFalseDropsIt()
    {
        IDataTable table = RealTableFixture.of("ADLB").str("USUBJID", "S1", "", "")
                .str("PARAMCD", "ALT", "ALT", "ALT").lng("AVAL", 1L, 5L, 6L).build();
        // The blank USUBJID rows form their own group under the "" key and read its maximum.
        Rule keep = loaded("max(AVAL, group=[USUBJID, PARAMCD])", "$v == 6");
        assertEquals(2, RuleRunnerCalls.execute(keep, table).getViolations().size());
        Rule drop = loaded("max(AVAL, group=[USUBJID, PARAMCD], keep_missings=false)", "$v == 6");
        assertEquals(0, RuleRunnerCalls.execute(drop, table).getViolations().size(),
                "keep_missings=false drops the block whose key is blank");
        Rule dropEmpty = loaded("max(AVAL, group=[USUBJID, PARAMCD], keep_missings=false)",
                "empty($v)");
        assertEquals(2, RuleRunnerCalls.execute(dropEmpty, table).getViolations().size(),
                "its rows find no group and read the missing answer");
    }

    // -----------------------------------------------------------------------
    // the cross-dataset broadcast
    // -----------------------------------------------------------------------


    @Test
    void aForeignGroupedExtremeIsBroadcastToThePrimaryByKey()
    {
        // S1's latest DS date is 2024-07-02, S2's 2024-03-01; S3 has no DS group (missing); DS's
        // S4 has no DM row (unused, never an error).
        assertEquals(1, fires("max_date(DSSTDTC, domain=\\\"DS\\\", group=[USUBJID])",
                "$v == \\\"2024-07-02\\\"", dm(), ds()));
        assertEquals(1, fires("max_date(DSSTDTC, domain=DS, group=[USUBJID])",
                "$v == \\\"2024-03-01\\\"", dm(), ds()), "D10: the bare spelling too");
        assertEquals(1,
                fires("max_date(DSSTDTC, domain=DS, group=[USUBJID])", "empty($v)", dm(), ds()),
                "a primary key with no foreign group reads the missing answer");
        assertEquals(1, fires("max(DSSTDY, domain=DS, group=[USUBJID])", "$v == 183", dm(), ds()),
                "max over a foreign numeric column");
        RuleExecutionResult reported = run("max_date(DSSTDTC, domain=DS, group=[USUBJID])",
                "$v == \\\"2024-07-02\\\"", dm(), ds());
        assertEquals("2024-07-02", reported.getViolations().get(0).getValues().get("$v"));
    }


    @Test
    void aForeignFilterReadsTheForeignColumns()
    {
        assertEquals(1, fires(
                "min_date(DSSTDTC, domain=DS, group=[USUBJID], filter=(DSDECOD == \\\"COMPLETED\\\"))",
                "$v == \\\"2024-07-02\\\"", dm(), ds()),
                "S1's earliest COMPLETED date (its RANDOMIZED row is filtered out)");
    }


    @Test
    void aCharNumKeyPairBetweenTheTwoTablesErrorsTheRule()
    {
        IDataTable numericKeyDm = RealTableFixture.of("DM").str("DOMAIN", "DM", "DM")
                .lng("USUBJID", 1L, 2L).build();
        RuleExecutionResult result = run("max_date(DSSTDTC, domain=DS, group=[USUBJID])",
                "empty($v)", numericKeyDm, ds());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "GKI Q2: a Char/Num key pair never meets silently");
        assertTrue(String.valueOf(result.getStatusMessage()).contains("USUBJID"),
                result.getStatusMessage());
    }


    @Test
    void anAbsentDatasetOrTargetColumnAnswersTheMissingOnEveryRow()
    {
        assertEquals(3, fires("max_date(DSSTDTC, domain=DS, group=[USUBJID])", "empty($v)", dm()),
                "no DS in the study");
        assertEquals(3,
                fires("max_date(DSSTDTCX, domain=DS, group=[USUBJID])", "empty($v)", dm(), ds()),
                "DS carries no such column");
    }


    @Test
    void anAbsentGroupColumnPartitionsNothing()
    {
        // EC-44 over names: DS has no SITEID, so the grouping falls to USUBJID alone, and a
        // primary row keys on USUBJID alone too (the absent column is "" on both sides).
        assertEquals(1, fires("max_date(DSSTDTC, domain=DS, group=[USUBJID, SITEID])",
                "$v == \\\"2024-07-02\\\"", dm(), ds()));
    }

    // -----------------------------------------------------------------------
    // max_date / min_date — EC-46 / EC-51
    // -----------------------------------------------------------------------


    @Test
    void aDateExtremeIsDeterminateOnlyWhenTheWinnerReachesEveryRivalsHull()
    {
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS", "DS")
                .str("USUBJID", "S1", "S1", "S2", "S2")
                .str("DSSTDTC", "2012-06", "2012-06-15", "2012-06", "2012-06-30").build();
        // S1: 2012-06 could be the 30th, later than 2012-06-15 => indeterminate; S2: the partial
        // cannot end after its own month => 2012-06-30 is the maximum.
        assertEquals(1, fires("max_date(DSSTDTC, domain=DS, group=[USUBJID])",
                "$v == \\\"2012-06-30\\\"", dm(), ds));
        assertEquals(2,
                fires("max_date(DSSTDTC, domain=DS, group=[USUBJID])", "empty($v)", dm(), ds),
                "S1 (indeterminate) and S3 (no group)");
        // min: the partial's earliest completion is 2012-06-01, earlier than every complete date
        // of S1 => indeterminate; S2 likewise.
        assertEquals(3,
                fires("min_date(DSSTDTC, domain=DS, group=[USUBJID])", "empty($v)", dm(), ds));
    }


    @Test
    void missingValuesIndeterminateMakesAMissingCandidateUndeterminable()
    {
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS")
                .str("USUBJID", "S1", "S1", "S2").str("DSSTDTC", "", "2012-06-15", "2012-06-30")
                .build();
        assertEquals(
                1, fires("max_date(DSSTDTC, domain=DS, group=[USUBJID])",
                        "$v == \\\"2012-06-15\\\"", dm(), ds),
                "skip (the default): a blank is no candidate");
        assertEquals(1,
                fires("max_date(DSSTDTC, domain=DS, group=[USUBJID], missing_values=\\\"skip\\\")",
                        "$v == \\\"2012-06-15\\\"", dm(), ds));
        // (an `indeterminate` extreme may only be consumed by a negative-polarity leaf — the
        // EC-51 OQ3 load gate, alive on the function surface — so the pins read `empty`.)
        String indeterminate = "max_date(DSSTDTC, domain=DS, group=[USUBJID], missing_values=\\\"indeterminate\\\")";
        assertEquals(2, fires(indeterminate, "empty($v)", dm(), ds),
                "EC-51 Half B: S1's blank makes its extreme undeterminable (S3 has no group)");
        assertEquals(1, fires(indeterminate, "not empty($v)", dm(), ds),
                "S2, with no missing candidate, still answers");
        Rule positive = load(indeterminate, "$v == \\\"2012-06-30\\\"");
        assertNotNull(positive.getLoadError(), "the OQ3 polarity gate reads the function's call");
        assertTrue(positive.getLoadError().contains("positive-polarity"), positive.getLoadError());
    }


    @Test
    void aFilteredOutRowIsNotAMissingCandidate()
    {
        // EC-51: the disposition applies to the rows the function reads — a blank on a row the
        // filter drops never makes the extreme undeterminable.
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS")
                .str("USUBJID", "S1", "S1").str("DSDECOD", "RANDOMIZED", "OTHER")
                .str("DSSTDTC", "2024-06-01", "").build();
        assertEquals(1, fires(
                "min_date(DSSTDTC, domain=DS, group=[USUBJID], missing_values=\\\"indeterminate\\\", filter=(DSDECOD == \\\"RANDOMIZED\\\"))",
                "not empty($v)", dm(), ds), "S1 answers 2024-06-01: the blank row is filtered out");
        assertEquals(3, fires(
                "min_date(DSSTDTC, domain=DS, group=[USUBJID], missing_values=\\\"indeterminate\\\")",
                "empty($v)", dm(), ds),
                "unfiltered, the blank row is a candidate and S1's group is undeterminable");
    }


    @Test
    void theDispositionAndThePrefixSurviveThePerDatasetSpecialisation()
    {
        // A `--`-prefixed target (the common corpus shape) resolves against the run's domain and
        // keeps its missing_values= declaration — the retired resolvePrefixes copied the field by
        // hand; the function reads it from the specialised call.
        Rule rule = loaded(
                "max_date(--STDTC, group=[USUBJID], missing_values=\\\"indeterminate\\\")",
                "empty($v)");
        IDataTable ex = RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2")
                .str("EXSTDTC", "", "2024-02-01", "2024-03-01").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ex, _ -> null, "EX");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(2, result.getViolations().size(),
                "S1's blank makes its extreme undeterminable; S2 answers");
        // and a `--` inside the filter resolves the same way (EC-28(b)'s claim, on the function)
        Rule filtered = loaded("max_date(EXSTDTC, group=[USUBJID], filter=(--TRT == \\\"A\\\"))",
                "$v == \\\"2024-02-01\\\"");
        IDataTable exTrt = RealTableFixture.of("EX").str("USUBJID", "S1", "S1")
                .str("EXTRT", "A", "B").str("EXSTDTC", "2024-02-01", "2024-03-01").build();
        assertEquals(2,
                RuleRunnerCalls.execute(filtered, exTrt, _ -> null, "EX").getViolations().size());
    }


    @Test
    void theTargetIsTheOnePositionalArgument()
    {
        // Combined review of runbook W2–W8, W5/W6 M1: the binder would bind a second positional to
        // domain= (D9), but every load-time reader (the absent-dataset skip, the classifiers, the
        // stage-A typer) reads the keywords only — so the positional spelling is refused and the
        // message names the keyword one. RED before the fix: `max(AVAL, ADLB, …)` loaded clean.
        expectError("max(num(AVAL), num(AVISITN), group=[USUBJID])",
                "takes one positional argument");
        expectError("max(AVAL, DS, (ABLFL == \\\"Y\\\"), [USUBJID], true, 1)",
                "takes one positional argument");
        expectError("max(AVAL, ADLB, (ABLFL == \\\"Y\\\"), [USUBJID], true)", "domain=, filter=");
        expectError("max_date(DSSTDTC, DS, group=[USUBJID])", "write the others as keywords");
        expectError("min_date(DSSTDTC, DS, group=[USUBJID])", "missing_values=");
        // the keyword spelling of the same call loads (the control that the refusal is the shape)
        assertNull(load("max(AVAL, domain=ADLB, filter=(ABLFL == \\\"Y\\\"), group=[USUBJID],"
                + " keep_missings=true)", "empty($v)").getLoadError());
    }


    @Test
    void aPositionalDomainNoLongerRunsOverAnAbsentDatasetSilently()
    {
        // W5/W6 M1's failing input: on DM with no DS the positional spelling bound DS as domain=
        // while the absent-dataset skip read only the keyword, so the rule EXECUTED and passed.
        // It is a load error now — the rule reports, it never silently passes.
        Rule rule = load("max_date(DSSTDTC, DS, group=[USUBJID])", "empty($v)");
        assertNotNull(rule.getLoadError(),
                "RED before the fix: the positional domain loaded and ran over no DS");
        assertTrue(rule.getLoadError().contains("domain="), rule.getLoadError());
    }


    @Test
    void anAllMissingDateGroupIsMissingUnderBothDispositions()
    {
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS")
                .str("USUBJID", "S1", "S1").str("DSSTDTC", null, null).build();
        assertEquals(3,
                fires("min_date(DSSTDTC, domain=DS, group=[USUBJID])", "empty($v)", dm(), ds));
        assertEquals(3, fires(
                "min_date(DSSTDTC, domain=DS, group=[USUBJID], missing_values=\\\"indeterminate\\\")",
                "empty($v)", dm(), ds));
    }


    @Test
    void aDateExtremeOverThePrimaryGroupsThePrimary()
    {
        IDataTable ex = RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2")
                .str("EXSTDTC", "2024-01-01", "2024-02-01", "2024-03-01").build();
        assertEquals(2,
                fires("min_date(EXSTDTC, group=[USUBJID])", "$v == \\\"2024-01-01\\\"", ex));
        assertEquals(2,
                fires("max_date(EXSTDTC, group=[USUBJID])", "$v == \\\"2024-02-01\\\"", ex));
    }

    // -----------------------------------------------------------------------
    // read_value's group= (D14)
    // -----------------------------------------------------------------------


    @Test
    void readValueGroupedSelectsWithinEachGroup()
    {
        String tail = ", domain=DS, group=[USUBJID], mode=\\\"";
        assertEquals(1, fires("read_value(DSSTDTC" + tail + "FIRST\\\")",
                "$v == \\\"2024-01-14\\\"", dm(), ds()), "S1's first DS row in dataset order");
        assertEquals(1, fires("read_value(DSSTDTC" + tail + "MAX\\\")", "$v == \\\"2024-07-02\\\"",
                dm(), ds()));
        assertEquals(1, fires("read_value(DSSTDY" + tail + "MIN\\\")", "$v == 14", dm(), ds()));
        assertEquals(1,
                fires("read_value(DSSTDTC" + tail + "FIRST\\\")", "$v == \\\"\\\"", dm(), ds()),
                "D13: a primary row with no group answers the char type default");
        assertEquals(1,
                fires("read_value(DSSTDTC" + tail
                        + "FIRST\\\", filter=(DSDECOD == \\\"COMPLETED\\\"))",
                        "$v == \\\"2024-07-02\\\"", dm(), ds()));
    }


    @Test
    void readValueGroupedAGroupTheFilterEmptiesAnswersTheTypeDefault()
    {
        // Combined review of runbook W2–W8, W2 M1 = W5/W6 M2: D13 — "no qualifying row ⇒ X's type
        // default in D" (PLAN-operation-replacements §2.2, D-W5-9 per group). DS: S1 RANDOMIZED
        // (+ COMPLETED), S2 COMPLETED only, S3 no row. Filtered to RANDOMIZED, S2's group keeps no
        // row: it must answer the char default "" exactly as S3 (no group) does. RED before the
        // fix: S2 answered the computed MIS, which is not == "" (D13 strictness), so only S3
        // fired.
        String call = "read_value(DSSTDTC, domain=DS, filter=(DSDECOD == \\\"RANDOMIZED\\\"),"
                + " mode=\\\"FIRST\\\", group=[USUBJID])";
        RuleExecutionResult result = run(call, "$v == \\\"\\\"", dm(), ds());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of(2L, 3L),
                result.getViolations().stream().map(Violation::getRowNumber).toList(),
                "S2 (filtered out) and S3 (no group) both read the char type default");
        assertEquals(1, fires(call, "$v == \\\"2024-01-14\\\"", dm(), ds()),
                "S1 reads its RANDOMIZED date");
        // a numeric X answers its own type default (MissingValue.MIS), for both kinds of "no row"
        String numeric = "read_value(DSSTDY, domain=DS, filter=(DSDECOD == \\\"RANDOMIZED\\\"),"
                + " mode=\\\"FIRST\\\", group=[USUBJID])";
        assertEquals(2, fires(numeric, "empty($v)", dm(), ds()));
    }


    @Test
    void readValueGroupedOnlyReportsTheGroupsTrueCardinality()
    {
        // W2 L1: the grouped ONLY error named "2 rows" at the first duplicate whatever the group
        // held; the ungrouped read reports the true count. S1 has THREE DS rows here. RED before
        // the fix: the message said "matched 2 rows".
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS", "DS")
                .str("USUBJID", "S1", "S1", "S1", "S2")
                .str("DSSTDTC", "2024-01-01", "2024-01-02", "2024-01-03", "2024-02-01").build();
        RuleExecutionResult result = run(
                "read_value(DSSTDTC, domain=DS, group=[USUBJID], mode=\\\"ONLY\\\")", "empty($v)",
                dm(), ds);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        String message = String.valueOf(result.getStatusMessage());
        assertTrue(message.contains("matched 3 rows"), message);
    }


    @Test
    void readValueOnlyAssertsOneRowPerGroup()
    {
        String tail = ", domain=DS, group=[USUBJID], mode=\\\"ONLY\\\"";
        RuleExecutionResult two = run("read_value(DSSTDTC" + tail + ")", "empty($v)", dm(), ds());
        assertEquals(RuleExecutionStatus.ERROR, two.getStatus(),
                "S1 has two DS rows: ONLY asserts one per group");
        assertEquals(1,
                fires("read_value(DSSTDTC" + tail + ", filter=(DSDECOD == \\\"RANDOMIZED\\\"))",
                        "$v == \\\"2024-01-14\\\"", dm(), ds()));
    }

    // -----------------------------------------------------------------------
    // the strict reader
    // -----------------------------------------------------------------------


    @Test
    void theStrictReaderRefusesEveryShapeThePlanCannotMean()
    {
        expectError("max(\\\"AVAL\\\", group=[USUBJID])", "a quoted name is a string");
        expectError("max(AVAL)", "requires argument 'group'");
        expectError("max(AVAL, group=[])", "at least one column");
        expectError("max(AVAL, group=[\\\"USUBJID\\\"])", "a quoted name is a string");
        expectError("max(AVAL, group=[$x])", "plain column reference");
        expectError("max(DS.AVAL, group=[USUBJID])", "not dotted");
        expectError("max(AVAL, group=[USUBJID], filter=filter(ABLFL=\\\"Y\\\"))",
                "written filter=(COLUMN == ");
        expectError("max(AVAL, group=[USUBJID], filter=(DM.ARM == \\\"A\\\"))", "not dotted");
        expectError("max(AVAL, group=[USUBJID], keep_missings=\\\"no\\\")", "boolean literal");
        expectError("max(AVAL, group=[USUBJID], missing_values=\\\"skip\\\")", "missing_values");
        expectError("max_date(DSSTDTC, group=[USUBJID], missing_values=\\\"indeterminant\\\")",
                "got `indeterminant`");
        expectError("max_date(DSSTDTC, group=[USUBJID], missing_values=1)", "string literal");
        expectError("max(AVAL, domain=1, group=[USUBJID])", "dataset reference");
        expectError("max(--DTC, domain=DS, group=[USUBJID])", "does not resolve `--`");
        expectError("max(AVAL, domain=DS, group=[--SEQ])", "does not resolve `--`");
        expectError("read_value(DSSTDTC, domain=DS, mode=\\\"FIRST\\\", keep_missings=true)",
                "requires a non-empty `group`");
        expectError("read_value(DSSTDTC, domain=DS, mode=\\\"FIRST\\\", group=[])",
                "at least one column");
        // control: the well-formed spellings the corpus authors load clean
        assertNull(load("max(AVAL, group=[USUBJID, PARAMCD], " + BASELINE + ")", "empty($v)")
                .getLoadError());
        assertNull(load(
                "max_date(DSSTDTC, domain=\\\"DS\\\", missing_values=\\\"indeterminate\\\", group=[USUBJID])",
                "empty($v)").getLoadError());
    }


    @Test
    void aTemplateWildcardTargetLoadsAsTheNameItWillBecome()
    {
        // The five ADaM AyIND rules (CDISC-AD0353/0354/0702/0703/0790): a template wildcard is a
        // WILDCARD_COLUMN reference that WildcardExpander substitutes per expansion (AyIND ->
        // A1IND) before the expansion is compiled; the template itself must LOAD (a load error
        // here left the five rules' Sensitivity absent from the routing census, measured in
        // phase 2). Only a `--` domain prefix under domain= is refused.
        assertNull(load("max(AyIND, group=[USUBJID, PARAMCD], " + BASELINE + ")",
                "not empty(ByIND) and ByIND != $v").getLoadError());
        assertNull(load("max(AyIND, domain=ADLB, group=[USUBJID])", "empty($v)").getLoadError(),
                "a template wildcard is not a domain prefix");
    }


    @Test
    void aBareWildcardTargetIsAcceptedOnThePrimaryAndResolvedPerDataset()
    {
        // The per-dataset specialisation resolves `--` before the call is compiled (D77); the
        // reader accepts the raw shape at load so the template loads.
        Rule rule = loaded("max_date(--STDTC, group=[USUBJID])", "$v == \\\"2024-02-01\\\"");
        IDataTable ex = RealTableFixture.of("EX").str("USUBJID", "S1", "S1")
                .str("EXSTDTC", "2024-01-01", "2024-02-01").build();
        assertEquals(2, RuleRunnerCalls.execute(rule, ex, _ -> null, "EX").getViolations().size());
    }


    @Test
    void theGroupedAggregateIsARowLevelValueOfThePrimary()
    {
        // A Check reading only the binding is still evaluated per row (DomainScan: ROW), so a
        // dataset whose subjects differ reports each subject's own answer — here two of the three
        // DM rows.
        RuleExecutionResult result = RuleRunnerCalls.execute(
                loaded("max_date(DSSTDTC, domain=DS, group=[USUBJID])", "not empty($v)"), dm(),
                resolver(dm(), ds()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(2, result.getViolations().size());
        assertEquals(List.of(1L, 2L),
                result.getViolations().stream().map(Violation::getRowNumber).toList());
    }

    // -----------------------------------------------------------------------
    // combined review of runbook W2–W8 — the memo, the constant answer, `--` in domain=
    // -----------------------------------------------------------------------


    /** Evaluates the call directly over {@code ctx}'s table and answers the vector. */
    private static Vector vector(String call, EvaluationContext ctx)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        return v;
    }


    @Test
    void theGroupingIsFormedOncePerExecution()
    {
        // W5/W6 M4 (W5 §4.2's "one grouping per execution"): two reads of one call in one
        // execution resolve the target table once. The guard a computeIfAbsent -> get() mutant of
        // GroupedAggregate.broadcast's memo must red (it would resolve twice).
        AtomicInteger resolves = new AtomicInteger();
        IDataTable ds = ds();
        DatasetResolver counting = name ->
        {
            resolves.incrementAndGet();
            return "DS".equalsIgnoreCase(name) ? ds : null;
        };
        EvaluationContext ctx = EvaluationContext.builder().table(dm()).datasetResolver(counting)
                .build();
        vector("max(DSSTDY, domain=DS, group=[USUBJID])", ctx);
        vector("max(DSSTDY, domain=DS, group=[USUBJID])", ctx);
        assertEquals(1, resolves.get(), "one grouping per execution");
        vector("max_date(DSSTDTC, domain=DS, group=[USUBJID])", ctx);
        assertEquals(2, resolves.get(), "another call is another grouping");
    }


    @Test
    void aGroupingWithNoBlockIsOneConstantAnswer()
    {
        // XCUT PERF 2: an absent dataset / target column / a grouping without a block answers the
        // no-group default on every row — as ONE constant, not a per-row carrier (the cheap path
        // through BindingValue's dataset-level hand-over). RED before the fix: a ComputedVector.
        EvaluationContext noDs = EvaluationContext.builder().table(dm()).build();
        Vector absent = vector("max_date(DSSTDTC, domain=DS, group=[USUBJID])", noDs);
        assertInstanceOf(ConstVector.class, absent);
        assertTrue(absent.value(0).cell().isMissingOrInvalid(), "the extremes' no-group missing");
        EvaluationContext withDs = EvaluationContext.builder().table(dm())
                .datasetResolver(resolver(dm(), ds())).build();
        assertInstanceOf(ConstVector.class,
                vector("max_date(DSSTDTCX, domain=DS, group=[USUBJID])", withDs),
                "an absent target column");
        Vector readValue = vector("read_value(DSSTDTC, domain=DS, mode=\"FIRST\", group=[USUBJID])",
                noDs);
        assertInstanceOf(ConstVector.class, readValue, "read_value's no-group default");
        assertEquals("", readValue.value(2).resolved(), "the char type default, on every row");
    }


    @Test
    void aGroupingOverThePrimaryAnswersEachRowFromItsOwnBlock()
    {
        // XCUT PERF 3: over the evaluated dataset itself the answers are written by row; the
        // verdicts are the key-derived ones (every row reads its own block, a dropped blank key
        // reads the no-group missing).
        IDataTable table = RealTableFixture.of("ADLB").str("USUBJID", "S1", "", "S1", "")
                .str("PARAMCD", "ALT", "ALT", "ALT", "ALT").lng("AVAL", 1L, 5L, 3L, 6L).build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Vector keep = vector("max(AVAL, group=[USUBJID, PARAMCD])", ctx);
        assertEquals(List.of(3L, 6L, 3L, 6L),
                List.of(keep.value(0).cell().getValue(), keep.value(1).cell().getValue(),
                        keep.value(2).cell().getValue(), keep.value(3).cell().getValue()));
        Vector drop = vector("max(AVAL, group=[USUBJID, PARAMCD], keep_missings=false)", ctx);
        assertEquals(3L, drop.value(0).cell().getValue());
        assertTrue(drop.value(1).cell().isMissingOrInvalid(), "the dropped blank key's row");
    }


    @Test
    void aDoubleDashInTheDomainLiteralResolvesAgainstTheEvaluatedDataset()
    {
        // F-W8-2: `domain="SUPP--"` on AE is SUPPAE, as Match_Datasets names and
        // crossDatasetVariableMetadata resolve it (DatasetIdentity.resolveWildcard, Fix #33).
        // RED before the fix: the literal SUPP-- was looked up verbatim, found nothing, and every
        // row read the absent-dataset missing.
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "S1", "S2").build();
        IDataTable suppae = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE", "AE")
                .str("USUBJID", "S1", "S1").str("QNAM", "AESOSP", "AETRTEM")
                .str("QVAL", "2024-01-01", "2024-03-05").build();
        assertEquals(1,
                RuleRunnerCalls
                        .execute(loaded("max_date(QVAL, domain=\\\"SUPP--\\\", group=[USUBJID])",
                                "$v == \\\"2024-03-05\\\""), ae, resolver(ae, suppae))
                        .getViolations().size(),
                "S1's latest SUPPAE date, read through the resolved SUPP--");
    }
}
