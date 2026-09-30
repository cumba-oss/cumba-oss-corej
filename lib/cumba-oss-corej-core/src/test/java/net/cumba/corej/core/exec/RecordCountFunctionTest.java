package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
 * {@code record_count} through the loader and {@link RuleRunner} (runbook W6,
 * {@code PLAN-record-count-function} §2.2 / §2.3 / §4.2): the bare call, the filter shapes, the
 * dataset-level ungrouped answer, the grouped broadcast with {@code 0} for a row without a group,
 * both missing-key policies, {@code domain=}, the {@code $}-list splice (D-W6-7), the memo key
 * (D-W6-8), the D1 {@code regex=} matrix, and the strict reader's load errors.
 */
class RecordCountFunctionTest
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
        Rule rule = load(binding, "$v == 0");
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
        return runRaw(binding, "not empty(USUBJID) and (" + check + ")", primary, others);
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
        RuleExecutionResult result = run(binding, check, primary, others);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }


    /** Evaluates the call directly over {@code ctx}'s table and answers the value at each row. */
    private static List<Object> values(String call, EvaluationContext ctx)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            // the cell's own payload (a Long) — resolved() may render a numeric cell as text
            out.add(v.value(row).cell().getValue());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------


    /** DM: three subjects — S3 has no DS rows. */
    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM", "DM", "DM")
                .str("USUBJID", "S1", "S2", "S3").build();
    }


    /** DS: S1 twice, S2 once, S4 (no DM row) once; one blank DSSTDTC. */
    private static IDataTable ds()
    {
        return RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS", "DS")
                .str("USUBJID", "S1", "S1", "S2", "S4")
                .str("DSSTDTC", "2024-01-14", "", "2024-03-01", "2024-12-31")
                .str("DSDECOD", "RANDOMIZED", "COMPLETED", "COMPLETED", "COMPLETED").build();
    }

    // -----------------------------------------------------------------------
    // the ungrouped count
    // -----------------------------------------------------------------------


    @Test
    void theBareCallIsTheRowCountAndTheUnfilteredUngroupedBranch()
    {
        // P-Q2 / D-W6-1: the retired registry fast path and the retired operation's unfiltered
        // branch are one function — both read the primary's row count.
        assertEquals(3, fires("record_count()", "$v == 3", dm()));
        assertEquals(3, fires("record_count(filter=(DOMAIN == " + q("DM") + "))", "$v == 3", dm()));
        assertEquals(3, fires("record_count(domain=DM)", "$v == 3", dm()));
    }


    @Test
    void theFilterShapesOfTheRetiredMapForm()
    {
        // D-W6-3: each map arm and its boolean twin. Equality (3 COMPLETED), membership (1 of
        // the listed terms present), the trailing-% prefix (3 starting with COMP), the bare & =
        // any populated value (3 populated DSSTDTC; the blank never matches), two keys (and).
        String ds = ", domain=DS";
        assertEquals(3, fires("record_count(filter=(DSDECOD == " + q("COMPLETED") + ")" + ds + ")",
                "$v == 3", dm(), ds()));
        assertEquals(3, fires("record_count(filter=(DSDECOD in [" + q("RANDOMIZED") + ", "
                + q("OTHER") + "])" + ds + ")", "$v == 1", dm(), ds()));
        assertEquals(3,
                fires("record_count(filter=(starts_with(DSDECOD, " + q("COMP") + "))" + ds + ")",
                        "$v == 3", dm(), ds()));
        assertEquals(3, fires("record_count(filter=(not empty(DSSTDTC))" + ds + ")", "$v == 3",
                dm(), ds()));
        assertEquals(3, fires("record_count(filter=(DSDECOD == " + q("COMPLETED")
                + " and USUBJID == " + q("S1") + ")" + ds + ")", "$v == 1", dm(), ds()));
        // A blank cell never matches a value (D12: "" is not "COMPLETED"), and an absent filter
        // column matches nothing (the map form's "missing filter column never matches").
        assertEquals(3, fires("record_count(filter=(DSSTDTC == " + q("") + ")" + ds + ")",
                "$v == 1", dm(), ds()), "the blank matches the empty string only");
        assertEquals(3, fires("record_count(filter=(NOPE == " + q("x") + ")" + ds + ")", "$v == 0",
                dm(), ds()));
    }


    @Test
    void theUngroupedCountIsOneDatasetLevelValue()
    {
        // D-W6-2 / D-W6-9: a Check reading only the ungrouped count fires ONCE per dataset (the
        // filter's columns are parameters, not row reads of the primary), and the value is
        // reported as the count.
        RuleExecutionResult result = runRaw("record_count(filter=(DOMAIN == " + q("DM") + "))",
                "$v == 3", dm());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "one dataset-level finding, not one per row");
        assertEquals("3", result.getViolations().get(0).getValues().get("$v"));
        // On an EMPTY table the dataset-level count is still reported as 0 (a ConstVector hands
        // over its value whatever the row count).
        IDataTable empty = RealTableFixture.of("DM").str("DOMAIN").str("USUBJID").build();
        RuleExecutionResult none = runRaw("record_count()", "$v == 0", empty);
        assertEquals(RuleExecutionStatus.EXECUTED, none.getStatus(), none.getStatusMessage());
        assertEquals(1, none.getViolations().size());
        assertEquals("0", none.getViolations().get(0).getValues().get("$v"));
    }

    // -----------------------------------------------------------------------
    // the grouped count
    // -----------------------------------------------------------------------


    @Test
    void groupedCountsPerGroupBroadcastPerRowAndZeroWithoutAGroup()
    {
        // S1 has 2 DS rows, S2 has 1, S3 has none (0, never missing — D-W6-5); S4's DS rows have no
        // DM row and are simply not read.
        String call = "record_count(domain=DS, group=[USUBJID])";
        assertEquals(1, fires(call, "$v == 2", dm(), ds()));
        assertEquals(1, fires(call, "$v == 1", dm(), ds()));
        assertEquals(1, fires(call, "$v == 0", dm(), ds()), "S3 reads 0, so `== 0` fires");
        assertEquals(0, fires(call, "empty($v)", dm(), ds()), "a count is never missing");
        // with a filter: S1 has one COMPLETED row, S2 one, S3 none
        assertEquals(2,
                fires("record_count(domain=" + q("DS") + ", group=[USUBJID], filter=(DSDECOD == "
                        + q("COMPLETED") + "))", "$v == 1", dm(), ds()),
                "D10: the quoted domain too");
        // a group with no kept row still exists and answers 0 (S1 after a filter nobody meets)
        assertEquals(3, fires(
                "record_count(domain=DS, group=[USUBJID], filter=(DSDECOD == " + q("NOPE") + "))",
                "$v == 0", dm(), ds()));
    }


    @Test
    void anAbsentGroupColumnPartitionsNothingAndAnAbsentDatasetCountsZero()
    {
        // EC-44 over names: DS has no SITEID, so the grouping falls to USUBJID alone.
        assertEquals(1,
                fires("record_count(domain=DS, group=[USUBJID, SITEID])", "$v == 2", dm(), ds()));
        // Q17-a: no DS in the study — every row reads 0, grouped or not (and the Check still runs:
        // the absent-dataset SKIP is the loader's gate, not the function's).
        assertEquals(3, fires("record_count(domain=DS, group=[USUBJID])", "$v == 0", dm()));
        assertEquals(3, fires("record_count(domain=DS)", "$v == 0", dm()));
    }


    @Test
    void theShippedDefaultKeepsABlankKeyGroupAndKeepMissingsFalseDropsIt()
    {
        IDataTable dm = RealTableFixture.of("DM").str("DOMAIN", "DM", "DM").str("USUBJID", "S1", "")
                .str("SITE", "A", "B").build();
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS", "DS")
                .str("USUBJID", "S1", "", "").build();
        // The Check reads SITE (the USUBJID guard would hide the blank row).
        assertEquals(1,
                runRaw("record_count(domain=DS, group=[USUBJID])", "not empty(SITE) and $v == 2",
                        dm, ds).getViolations().size(),
                "KEEP (default): the blank key is a group of two");
        assertEquals(1,
                runRaw("record_count(domain=DS, group=[USUBJID], keep_missings=false)",
                        "not empty(SITE) and $v == 0", dm, ds).getViolations().size(),
                "DROP: the blank key forms no group, so the blank DM row reads 0");
    }


    @Test
    void aCharNumKeyPairBetweenTheTwoTablesErrorsTheRule()
    {
        IDataTable numericKeyDm = RealTableFixture.of("DM").str("DOMAIN", "DM", "DM")
                .lng("USUBJID", 1L, 2L).build();
        RuleExecutionResult result = run("record_count(domain=DS, group=[USUBJID])", "$v == 0",
                numericKeyDm, ds());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "GKI Q2: a Char/Num key pair never meets silently");
        assertTrue(String.valueOf(result.getStatusMessage()).contains("USUBJID"),
                result.getStatusMessage());
    }

    // -----------------------------------------------------------------------
    // the $-list splice (D-W6-7) and the memo key (D-W6-8)
    // -----------------------------------------------------------------------


    @Test
    void aDollarMemberSplicesTheListOfColumnNamesItHolds()
    {
        // The retired expandGroupRefs semantics: the list's elements are column names, spliced in
        // place; a one-element and a two-element list; a blank name partitions nothing (EC-44).
        IDataTable ds = ds();
        EvaluationContext one = EvaluationContext.builder().table(ds)
                .variables(Map.of("$K", List.of("DSDECOD"))).build();
        assertEquals(List.of(1L, 1L, 1L, 1L), values("record_count(group=[USUBJID, $K])", one),
                "[USUBJID, DSDECOD]: every DS row is its own group");
        EvaluationContext two = EvaluationContext.builder().table(ds)
                .variables(Map.of("$K", List.of("DSDECOD", "DOMAIN"))).build();
        assertEquals(List.of(1L, 1L, 1L, 1L), values("record_count(group=[USUBJID, $K])", two));
        EvaluationContext none = EvaluationContext.builder().table(ds)
                .variables(Map.of("$K", List.of())).build();
        assertEquals(List.of(2L, 2L, 1L, 1L), values("record_count(group=[USUBJID, $K])", none),
                "an empty list splices nothing: [USUBJID]");
        EvaluationContext blank = EvaluationContext.builder().table(ds)
                .variables(Map.of("$K", List.of("", "NOPE"))).build();
        assertEquals(List.of(2L, 2L, 1L, 1L), values("record_count(group=[USUBJID, $K])", blank),
                "a blank or absent name partitions nothing");
        EvaluationContext text = EvaluationContext.builder().table(ds)
                .variables(Map.of("$K", "DSDECOD")).build();
        assertEquals(List.of(1L, 1L, 1L, 1L), values("record_count(group=[USUBJID, $K])", text),
                "a String is one name");
        EvaluationContext only = EvaluationContext.builder().table(ds)
                .variables(Map.of("$K", List.of())).build();
        Vector v = ExprCompiler.evaluateValueExpression(
                CheckExpressionParser.parse("record_count(group=[$K])"), only);
        assertTrue(v instanceof ConstVector, "a splice that leaves no group column is ungrouped");
        assertEquals(4L, v.value(0).cell().getValue());
    }


    @Test
    void aDollarMemberThatHoldsNoListOfNamesErrorsTheRule()
    {
        // D-W6-7: loud, never the operation's silent null (which folded to "no value" and made a
        // `== 0` consumer read false).
        EvaluationContext scalar = EvaluationContext.builder().table(ds())
                .variables(Map.of("$K", 5L)).ruleId("X-1").build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> values("record_count(group=[USUBJID, $K])", scalar));
        assertTrue(ex.getMessage().contains("$K") && ex.getMessage().contains("Long"),
                ex.getMessage());
        EvaluationContext absent = EvaluationContext.builder().table(ds()).build();
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> values("record_count(group=[USUBJID, $K])", absent));
        assertTrue(none.getMessage().contains("resolves to nothing"), none.getMessage());
    }


    @Test
    void twoContextsOfOneExecutionWhoseDollarListsDifferDoNotShareAMemoEntry()
    {
        // D-W6-8: toBuilder carries the aggregate memo into a derived context; keyed on the call
        // text alone, the second evaluation would read the first's grouping. RED before the
        // expanded-names key: the derived context answered [1, 1, 1, 1] here.
        EvaluationContext first = EvaluationContext.builder().table(ds())
                .variables(Map.of("$K", List.of("DSDECOD"))).build();
        assertEquals(List.of(1L, 1L, 1L, 1L), values("record_count(group=[USUBJID, $K])", first));
        EvaluationContext derived = first.toBuilder().variables(Map.of("$K", List.of("DOMAIN")))
                .build();
        assertEquals(List.of(2L, 2L, 1L, 1L), values("record_count(group=[USUBJID, $K])", derived),
                "[USUBJID, DOMAIN] groups S1's two rows together");
        // and the memo still serves the same expansion twice (one grouping per execution)
        assertEquals(List.of(2L, 2L, 1L, 1L), values("record_count(group=[USUBJID, $K])", derived));
    }

    // -----------------------------------------------------------------------
    // regex= (D1, D-W6-6)
    // -----------------------------------------------------------------------


    /** VS: S1 three SYSBP records on one date (two of them with times), one on the next day. */
    private static IDataTable vs()
    {
        return RealTableFixture.of("VS").str("DOMAIN", "VS", "VS", "VS", "VS", "VS", "VS")
                .str("USUBJID", "S1", "S1", "S1", "S1", "S2", "S2")
                .str("VSDTC", "2014-01-01T08:00", "2014-01-01T09:00", "2014-01-01", "2014-01-02",
                        "UNK", "")
                .dbl("VISITNUM", 4.9999999999994, 4.9999999999994, 5.0, 5.0, 1.0, 1.0).build();
    }

    /** {@code regex="^\\d{4}-\\d{2}-\\d{2}"} as expression text (the direct API). */
    private static final String DATE = "regex=\"^\\\\d{4}-\\\\d{2}-\\\\d{2}\"";

    /** The same argument JSON-escaped for a rule text (the loader). */
    private static final String DATE_JSON = "regex=" + q("^\\\\\\\\d{4}-\\\\\\\\d{2}-\\\\\\\\d{2}");

    @Test
    void regexCollapsesAMatchingKeyColumnToItsFirstMatch()
    {
        // is_unique_set's contract: VSDTC's first value matches the date pattern, so every present
        // value groups by its date — the two timed records and the bare date are ONE group of
        // three; 2014-01-02 is a group of one. A present value that does not match (UNK) takes the
        // empty key, the bucket the blank cell occupies, so S2's two rows are one group of two.
        // Without the regex every distinct text is its own group.
        EvaluationContext ctx = EvaluationContext.builder().table(vs()).build();
        assertEquals(List.of(3L, 3L, 3L, 1L, 2L, 2L),
                values("record_count(group=[USUBJID, VSDTC], " + DATE + ")", ctx));
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L),
                values("record_count(group=[USUBJID, VSDTC])", ctx));
    }


    @Test
    void regexLeavesAColumnWhoseSampleDoesNotMatchVerbatim()
    {
        // VISITNUM's first value does not match the date pattern, so the column keeps its exact
        // numeric identity (the GKI noise pair 4.9999999999994 vs 5 stays two groups) — the
        // regex is a per-column decision taken on the first non-blank value.
        EvaluationContext ctx = EvaluationContext.builder().table(vs()).build();
        assertEquals(List.of(2L, 2L, 2L, 2L, 2L, 2L),
                values("record_count(group=[USUBJID, VISITNUM], " + DATE + ")", ctx));
        // both columns: the date collapse AND the exact visit number
        assertEquals(List.of(2L, 2L, 1L, 1L, 2L, 2L),
                values("record_count(group=[USUBJID, VSDTC, VISITNUM], " + DATE + ")", ctx));
    }


    @Test
    void regexKeysTheEvaluatedDatasetTheSameWayUnderDomain()
    {
        // The primary's rows derive their key through the same per-column patterns the counted
        // table decided, so a timed primary value finds the date group of the other dataset.
        IDataTable primary = RealTableFixture.of("PR").str("DOMAIN", "PR", "PR", "PR")
                .str("USUBJID", "S1", "S1", "S2")
                .str("VSDTC", "2014-01-01T23:59", "2014-01-02", "x").build();
        RuleExecutionResult result = runRaw(
                "record_count(domain=VS, group=[USUBJID, VSDTC], " + DATE_JSON + ")", "$v == 3",
                primary, vs());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "the timed S1 row reads the date group's 3");
        assertEquals(1,
                runRaw("record_count(domain=VS, group=[USUBJID, VSDTC], " + DATE_JSON + ")",
                        "$v == 2", primary, vs()).getViolations().size(),
                "S2's non-matching x takes the empty key and reads the UNK/blank group's 2");
    }


    @Test
    void regexMustBeAValidStringLiteral()
    {
        expectError("record_count(group=[USUBJID], regex=" + q("(") + ")",
                "not a valid regular expression");
        expectError("record_count(group=[USUBJID], regex=USUBJID)", "must be a string literal");
        // an empty pattern normalises nothing (is_unique_set's contract), and loads
        assertEquals(1, fires("record_count(domain=DS, group=[USUBJID], regex=" + q("") + ")",
                "$v == 2", dm(), ds()));
    }

    // -----------------------------------------------------------------------
    // the strict reader (load time)
    // -----------------------------------------------------------------------


    @Test
    void theRetiredTargetIsALoadErrorNamingTheSpelling()
    {
        // R6 RETIRE-both: the operation never read its target; the function declares none, and a
        // positional is refused rather than dropped ("never keep the drop").
        expectError("record_count(ABLFL, group=[USUBJID], filter=(ABLFL == " + q("Y") + "))",
                "takes no positional argument");
        expectError("record_count(" + q("ABLFL") + ")", "takes no positional argument");
        // the well-formed control: the same filter without the target loads
        assertNull(
                load("record_count(group=[USUBJID], filter=(ABLFL == " + q("Y") + "))", "$v == 0")
                        .getLoadError());
    }


    @Test
    void theRetiredMapFilterAndTheOtherMalformedShapesAreLoadErrors()
    {
        expectError("record_count(filter=filter(TSPARMCD=" + q("INDIC") + "))",
                "filter=(COLUMN == \"value\")");
        expectError("record_count(filter=TSPARMCD)", "boolean expression");
        expectError("record_count(domain=TS, filter=($x == 1))",
                "must be a plain column reference");
        expectError("record_count(domain=TS, group=[--TESTCD])", "does not resolve `--`");
        expectError("record_count(domain=TS, filter=(DM.USUBJID == " + q("S1") + "))",
                "written bare, not dotted");
        expectError("record_count(group=" + q("USUBJID") + ")",
                "takes a list of column references");
        expectError("record_count(group=[USUBJID], keep_missings=" + q("yes") + ")",
                "boolean literal");
        expectError("record_count(keep_missings=1)", "boolean literal");
        expectError("record_count(name=USUBJID)", "name");
        expectError("record_count(domain=1)", "dataset reference");
        // the well-formed control
        assertNull(load(
                "record_count(domain=TS, group=[TSPARMCD], keep_missings=false, filter=(TSVAL == "
                        + q("Y") + "))",
                "$v == 0").getLoadError());
    }


    @Test
    void anInlineCallInACheckMeetsTheReaderAtLoad()
    {
        Rule rule = load("record_count()",
                "record_count(filter=filter(TSPARMCD=" + q("INDIC") + ")) > 0");
        assertNotNull(rule.getLoadError(), "the inline Check surface is loud at load too");
        assertTrue(rule.getLoadError().contains("filter=(COLUMN == \"value\")"),
                rule.getLoadError());
        // the ungrouped inline form evaluates as a dataset fact: one finding per dataset
        RuleExecutionResult inline = RuleRunnerCalls.execute(
                loaded("record_count()", "record_count(filter=(DOMAIN == " + q("DM") + ")) == 3"),
                dm());
        assertEquals(RuleExecutionStatus.EXECUTED, inline.getStatus(), inline.getStatusMessage());
        assertEquals(1, inline.getViolations().size());
    }

    // -----------------------------------------------------------------------
    // combined review of runbook W2–W8
    // -----------------------------------------------------------------------


    @Test
    void theForeignFilterReadsTheColumnsOfDomainNeverItsSuppQualifiers()
    {
        // W2 M3: the declared SUPP merge serves the PRIMARY table only (PLAN-operation-replacements
        // §2.3 / §7). DSXFL is a SUPPDS qualifier, not a DS column: in filter= it is absent ("")
        // and keeps no row. RED before the fix: the DS-scoped filter context pivoted SUPPDS and
        // counted S1's row (every DM row read 1).
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS")
                .str("USUBJID", "S1", "S2").str("DSSEQ", "1", "1").build();
        IDataTable suppds = RealTableFixture.of("SUPPDS").str("RDOMAIN", "DS").str("USUBJID", "S1")
                .str("IDVAR", "DSSEQ").str("IDVARVAL", "1").str("QNAM", "DSXFL").str("QVAL", "Y")
                .build();
        assertEquals(3, fires("record_count(domain=DS, filter=(DSXFL == " + q("Y") + "))",
                "$v == 0", dm(), ds, suppds), "no DS row carries DSXFL");
        assertEquals(3,
                fires("record_count(domain=DS, group=[USUBJID], filter=(DSXFL == " + q("Y") + "))",
                        "$v == 0", dm(), ds, suppds),
                "grouped, the same");
    }


    @Test
    void aBlankKeyUnderKeepMissingsFalseFindsNoGroupUnderRegex()
    {
        // W5/W6 L1: under regex= a non-matching present value takes the empty key — the key a
        // blank cell would take — so with keep_missings=false (the blank block dropped) S2's blank
        // row read the UNK bucket's count. It finds no group now and counts 0. RED before the fix:
        // [3, 3, 3, 1, 1, 1].
        EvaluationContext ctx = EvaluationContext.builder().table(vs()).build();
        assertEquals(List.of(3L, 3L, 3L, 1L, 1L, 0L), values(
                "record_count(group=[USUBJID, VSDTC], keep_missings=false, " + DATE + ")", ctx));
        // control: the shipped KEEP default still folds the blank with the UNK bucket
        assertEquals(List.of(3L, 3L, 3L, 1L, 2L, 2L),
                values("record_count(group=[USUBJID, VSDTC], " + DATE + ")", ctx));
    }


    @Test
    void aSplicedDoubleDashNameUnderDomainErrorsTheRule()
    {
        // W5/W6 L6: a `--` spliced into group= under domain= resolves against the EVALUATED
        // dataset's prefix and is looked up in the other table, so it silently partitioned nothing.
        // It ERRORs the rule, as an authored `--` member is a load error. RED before the fix: the
        // call answered S1's whole-subject count.
        DatasetResolver study = resolver(dm(), ds());
        EvaluationContext spliced = EvaluationContext.builder().table(dm()).datasetResolver(study)
                .domainPrefix("DM").variables(Map.of("$K", List.of("--DECOD"))).ruleId("X-1")
                .build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> values("record_count(domain=DS, group=[USUBJID, $K])", spliced));
        assertTrue(ex.getMessage().contains("--DECOD") && ex.getMessage().contains("domain="),
                ex.getMessage());
        // controls: a splice without a `--` name runs under domain= (a name neither table carries
        // partitions nothing, EC-44), and `--` stays legal without domain=
        EvaluationContext named = EvaluationContext.builder().table(dm()).datasetResolver(study)
                .variables(Map.of("$K", List.of("NOPE"))).build();
        assertEquals(List.of(2L, 1L, 0L),
                values("record_count(domain=DS, group=[USUBJID, $K])", named));
        EvaluationContext primary = EvaluationContext.builder().table(ds()).domainPrefix("DS")
                .variables(Map.of("$K", List.of("--DECOD"))).build();
        assertEquals(List.of(1L, 1L, 1L, 1L), values("record_count(group=[USUBJID, $K])", primary));
    }


    @Test
    void theGroupingIsFormedOncePerExecution()
    {
        // W5/W6 M4: two reads of one grouped call in one execution resolve the counted table once
        // (the memo of GroupedAggregate.broadcast); a computeIfAbsent -> get() mutant resolves
        // twice.
        AtomicInteger resolves = new AtomicInteger();
        IDataTable ds = ds();
        DatasetResolver counting = name ->
        {
            resolves.incrementAndGet();
            return "DS".equalsIgnoreCase(name) ? ds : null;
        };
        EvaluationContext ctx = EvaluationContext.builder().table(dm()).datasetResolver(counting)
                .build();
        values("record_count(domain=DS, group=[USUBJID])", ctx);
        values("record_count(domain=DS, group=[USUBJID])", ctx);
        assertEquals(1, resolves.get(), "one grouping per execution");
    }


    @Test
    void aDoubleDashInTheDomainLiteralResolvesAgainstTheEvaluatedDataset()
    {
        // F-W8-2: `domain="SUPP--"` on AE counts SUPPAE (DatasetIdentity.resolveWildcard, Fix #33).
        // RED before the fix: SUPP-- was looked up verbatim and counted zero.
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "S1", "S2").build();
        IDataTable suppae = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE", "AE", "AE")
                .str("USUBJID", "S1", "S1", "S2").str("QNAM", "A", "B", "A").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae)
                .datasetResolver(resolver(ae, suppae)).build();
        assertEquals(List.of(3L, 3L), values("record_count(domain=\"SUPP--\")", ctx));
        assertEquals(List.of(2L, 1L),
                values("record_count(domain=\"SUPP--\", group=[USUBJID])", ctx));
    }


    @Test
    void anEmptyGroupIsALoadErrorAndAnAbsentGroupedDatasetIsOneConstant()
    {
        // W5/W6 L5: isGrouped's empty-list arm was dead because the reader refuses group=[] —
        // this is the pin that keeps the arm's deletion honest.
        expectError("record_count(group=[])", "at least one column");
        // XCUT PERF 2: a grouped count over an absent dataset is 0 on every row, as one constant.
        EvaluationContext noDs = EvaluationContext.builder().table(dm()).build();
        Vector v = ExprCompiler.evaluateValueExpression(
                CheckExpressionParser.parse("record_count(domain=DS, group=[USUBJID])"), noDs);
        assertTrue(v instanceof ConstVector, "RED before the fix: a ComputedVector");
        assertEquals(0L, v.value(2).cell().getValue());
    }
}
