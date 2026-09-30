package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
 * {@code read_value(X, domain=D, filter=(…), mode="…")} through the loader and {@link RuleRunner}
 * (runbook W2a, {@code PLAN-operation-replacements} §2.2; owner D12 / D13 / D10): the four modes,
 * the three "no value" answers, both {@code domain=} spellings, the ONLY-ambiguity ERROR and the
 * load errors the strict readers owe.
 */
class ReadValueTest
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
        String error = loadError(binding);
        assertTrue(error.contains(fragment), () -> "load error for `" + binding
                + "` does not name `" + fragment + "`: " + error);
    }


    private static String loadError(String binding)
    {
        Rule rule = load(binding, "$v == \\\"x\\\"");
        assertNotNull(rule.getLoadError(), "expected a load error for " + binding);
        return rule.getLoadError();
    }


    /** DM, two subjects — the dataset under evaluation. */
    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM", "DM").str("USUBJID", "S1", "S2")
                .str("SPECIES", "", "").build();
    }


    /** TS with two SPECIES rows (RAT first, DOG second), numeric TSSEQ. */
    private static IDataTable ts()
    {
        return RealTableFixture.of("TS").str("DOMAIN", "TS", "TS", "TS").lng("TSSEQ", 1L, 2L, 3L)
                .str("TSPARMCD", "TITLE", "SPECIES", "SPECIES")
                .str("TSVAL", "Study X", "RAT", "DOG").build();
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


    private static RuleExecutionResult run(String binding, String check, IDataTable... others)
    {
        // Row-based on purpose (USUBJID): a Check reading only the binding names no column of the
        // primary — read_value's arguments are the other dataset's (RuleClassifier, W2a) — so it
        // derives no Sensitivity and RuleRunner reports the dataset-level fact once. The per-row
        // counts below pin the BROADCAST, which needs a row-based Check to be visible.
        IDataTable dm = dm();
        return RuleRunnerCalls.execute(loaded(binding, "not empty(USUBJID) and (" + check + ")"),
                dm, resolver(concat(dm, others)));
    }


    private static IDataTable[] concat(IDataTable first, IDataTable[] rest)
    {
        IDataTable[] all = new IDataTable[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private static final String SPECIES_FILTER = "filter=(TSPARMCD == \\\"SPECIES\\\")";

    @Test
    void firstTakesTheFirstQualifyingRowInDatasetOrder()
    {
        RuleExecutionResult result = run(
                "read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                "$v == \\\"RAT\\\"", ts());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(2, result.getViolations().size(), "the value is broadcast to every DM row");
        assertEquals("RAT", result.getViolations().get(0).getValues().get("$v"),
                "the finding reports the read value");
    }


    @Test
    void aBindingOnlyCheckOverReadValueIsADatasetLevelFact()
    {
        // The classifier (RuleClassifier, W2a): a `domain=` registry call names no column of the
        // primary — TSVAL / TSPARMCD are TS's — so a Check reading only the binding derives no
        // Record sensitivity and the fact is reported ONCE, as CDISC-SEND-0105's `Dataset {}`
        // census row requires. Before the arm existed TSVAL derived Record and this reported 2.
        IDataTable dm = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(
                loaded("read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                        "$v == \\\"RAT\\\""),
                dm, resolver(concat(dm, new IDataTable[]
                {
                        ts()
                })));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "one dataset-level finding, not one per row");
    }


    @Test
    void bothDomainSpellingsNameTheSameDataset()
    {
        RuleExecutionResult quoted = run(
                "read_value(TSVAL, domain=\\\"TS\\\", " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                "$v == \\\"RAT\\\"", ts());
        assertEquals(2, quoted.getViolations().size(), quoted.getStatusMessage());
    }


    @Test
    void minAndMaxRankByTheEngineSOrdering()
    {
        assertEquals(2,
                run("read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"MIN\\\")",
                        "$v == \\\"DOG\\\"", ts()).getViolations().size(),
                "text ranks as text: DOG < RAT");
        assertEquals(2,
                run("read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"MAX\\\")",
                        "$v == \\\"RAT\\\"", ts()).getViolations().size());
        assertEquals(2,
                run("read_value(TSSEQ, domain=TS, " + SPECIES_FILTER + ", mode=\\\"MAX\\\")",
                        "$v == 3", ts()).getViolations().size(),
                "a numeric column ranks numerically");
        assertEquals(2, run("read_value(TSSEQ, domain=TS, mode=\\\"MIN\\\")", "$v == 1", ts())
                .getViolations().size(), "no filter: every row of the dataset qualifies");
    }


    @Test
    void onlyAnswersASingleMatchAndErrorsOnASecond()
    {
        RuleExecutionResult one = run(
                "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \\\"TITLE\\\"), mode=\\\"ONLY\\\")",
                "$v == \\\"Study X\\\"", ts());
        assertEquals(RuleExecutionStatus.EXECUTED, one.getStatus(), one.getStatusMessage());
        assertEquals(2, one.getViolations().size());

        RuleExecutionResult two = run(
                "read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"ONLY\\\")",
                "$v == \\\"RAT\\\"", ts());
        assertEquals(RuleExecutionStatus.ERROR, two.getStatus(),
                "two SPECIES rows under ONLY is the rule's ERROR (D13)");
        String message = String.valueOf(two.getStatusMessage());
        assertTrue(
                message.startsWith(
                        "read_value(TSVAL, domain=TS, mode=\"ONLY\") matched 2 rows of TS"),
                message);
        assertTrue(message.endsWith(" — ONLY asserts exactly one qualifying row"), message);
        assertEquals(1, two.getViolations().size(), "the __error__ sentinel");
        assertEquals(message, two.getViolations().get(0).getValues().get("__error__"));
    }


    @Test
    void noQualifyingRowAnswersTheColumnsTypeDefault()
    {
        // char -> "" (empty), numeric -> MissingValue.MIS (empty as well); never an error.
        RuleExecutionResult text = run(
                "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \\\"STRAIN\\\"), mode=\\\"FIRST\\\")",
                "empty($v)", ts());
        assertEquals(RuleExecutionStatus.EXECUTED, text.getStatus(), text.getStatusMessage());
        assertEquals(2, text.getViolations().size());
        assertEquals("", text.getViolations().get(0).getValues().get("$v"),
                "a char column's default is the present blank");
        RuleExecutionResult number = run(
                "read_value(TSSEQ, domain=TS, filter=(TSPARMCD == \\\"STRAIN\\\"), mode=\\\"MAX\\\")",
                "empty($v)", ts());
        assertEquals(2, number.getViolations().size(), number.getStatusMessage());
    }


    @Test
    void anAbsentDatasetOrColumnAnswersTheAbsentJoinedColumnDefault()
    {
        RuleExecutionResult noTs = run(
                "read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                "empty($v)");
        assertEquals(RuleExecutionStatus.EXECUTED, noTs.getStatus(),
                "an absent dataset folds, it does not skip: " + noTs.getStatusMessage());
        assertEquals(2, noTs.getViolations().size());
        RuleExecutionResult noColumn = run(
                "read_value(TSVALX, domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                "empty($v)", ts());
        assertEquals(2, noColumn.getViolations().size(), noColumn.getStatusMessage());
    }


    @Test
    void aMissingCellIsHandedThroughAsThatMissing()
    {
        IDataTable ts = RealTableFixture.of("TS").str("DOMAIN", "TS").lng("TSSEQ", 1L)
                .str("TSPARMCD", "SPECIES").str("TSVAL", (String) null).build();
        RuleExecutionResult result = run(
                "read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                "empty($v)", ts);
        assertEquals(2, result.getViolations().size(), result.getStatusMessage());
    }


    @Test
    void theStrictReadersRefuseEveryShapeThePlanCannotMean()
    {
        String tail = ", domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")";
        expectError("read_value(\\\"TSVAL\\\"" + tail, "column reference");
        expectError("read_value(TS.TSVAL" + tail, "not dotted");
        expectError("read_value(--VAL" + tail, "--");
        // A $-reference is refused before the reader sees it: the loader's dangling-reference gate
        // (no binding defines $x) — loud either way; the reader's own arm covers a defined one.
        expectError("read_value($x" + tail, "$x");
        expectError("read_value(TSVAL, TSPARMCD" + tail, "exactly one positional");
        expectError("read_value(TSVAL, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                "requires domain=");
        expectError("read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ")", "requires mode=");
        expectError("read_value(TSVAL, domain=TS, mode=\\\"SECOND\\\")", "\"SECOND\"");
        expectError("read_value(TSVAL, domain=TS, mode=FIRST)", "string literal");
        expectError("read_value(TSVAL, domain=TS.X, mode=\\\"FIRST\\\")", "dataset reference");
        expectError("read_value(TSVAL, domain=TS, key_value=\\\"SPECIES\\\", mode=\\\"FIRST\\\")",
                "no parameter 'key_value'");
        // $x inside the filter: the loader's dangling-reference gate again (loud, same as above).
        expectError("read_value(TSVAL, domain=TS, filter=(TSPARMCD == $x), mode=\\\"FIRST\\\")",
                "$x");
        expectError(
                "read_value(TSVAL, domain=TS, filter=(DM.SPECIES == \\\"RAT\\\"), mode=\\\"FIRST\\\")",
                "filter= reads the columns of domain only");
        expectError(
                "read_value(TSVAL, domain=TS, filter=(--PARMCD == \\\"SPECIES\\\"), mode=\\\"FIRST\\\")",
                "filter= reads the columns of domain only");
        // And the well-formed spelling loads: the readers refuse shapes, not the function.
        assertNull(
                loaded("read_value(TSVAL, domain=TS, " + SPECIES_FILTER + ", mode=\\\"FIRST\\\")",
                        "$v == \\\"RAT\\\"").getLoadError());
    }


    @Test
    void theSpecRecordsWhatWasAuthored()
    {
        ReadValue.Spec spec = ReadValue
                .spec((net.cumba.corej.core.expr.ast.Expr.Call) CheckExpressionParser.parse(
                        "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \"SPECIES\"), mode=\"MAX\")"));
        assertEquals("TSVAL", spec.column());
        assertEquals("TS", spec.dataset());
        assertEquals(ReadValue.Mode.MAX, spec.mode());
        assertNotNull(spec.filter());
        assertEquals(List.of("FIRST", "ONLY", "MIN", "MAX"),
                java.util.Arrays.stream(ReadValue.Mode.values()).map(Enum::name).toList());
    }

    // -----------------------------------------------------------------------
    // combined review of runbook W2–W8
    // -----------------------------------------------------------------------


    private static Vector vector(String call, EvaluationContext ctx)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        return v;
    }


    @Test
    void everyUngroupedAnswerIsOneConstant()
    {
        // XCUT PERF 2: the three ungrouped answers — the selected cell (or the no-match type
        // default), an absent D, an absent X — are one dataset-level value, so they are ONE
        // ConstVector, the cheap path through BindingValue's dataset-level hand-over. RED before
        // the fix: ComputedVector.typed on all three.
        EvaluationContext study = EvaluationContext.builder().table(dm())
                .datasetResolver(resolver(dm(), ts())).build();
        Vector selected = vector(
                "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \"SPECIES\"), mode=\"FIRST\")",
                study);
        assertInstanceOf(ConstVector.class, selected, "the selected cell");
        assertEquals("RAT", selected.value(1).resolved());
        Vector numeric = vector("read_value(TSSEQ, domain=TS, mode=\"MAX\")", study);
        assertInstanceOf(ConstVector.class, numeric);
        assertEquals(3L, ((ConstVector) numeric).value(),
                "a numeric cell hands over its number, as the computed vector's hand-over did");
        assertInstanceOf(ConstVector.class,
                vector("read_value(TSVALX, domain=TS, mode=\"FIRST\")", study), "an absent X");
        EvaluationContext noTs = EvaluationContext.builder().table(dm()).build();
        Vector absent = vector("read_value(TSVAL, domain=TS, mode=\"FIRST\")", noTs);
        assertInstanceOf(ConstVector.class, absent, "an absent D");
        assertEquals("", absent.value(0).resolved());
    }


    @Test
    void theFilterReadsTheColumnsOfDomainNeverItsSuppQualifiers()
    {
        // W2 M3: the declared SUPP merge serves the PRIMARY table only (PLAN-operation-replacements
        // §2.3 / §7). DSXFL is a qualifier in SUPPDS, not a column of DS: in filter= it is an
        // absent column of DS ("" — D76), so nothing qualifies and X reads its type default. RED
        // before the fix: the D-scoped filter context inherited suppMerge=true and pivoted
        // SUPPDS, so S1's row qualified and both DM rows read "A".
        IDataTable ds = RealTableFixture.of("DS").str("DOMAIN", "DS", "DS")
                .str("USUBJID", "S1", "S2").str("DSSEQ", "1", "1").str("DSTERM", "A", "B").build();
        IDataTable suppds = RealTableFixture.of("SUPPDS").str("RDOMAIN", "DS").str("USUBJID", "S1")
                .str("IDVAR", "DSSEQ").str("IDVARVAL", "1").str("QNAM", "DSXFL").str("QVAL", "Y")
                .build();
        String call = "read_value(DSTERM, domain=DS, filter=(DSXFL == \\\"Y\\\"),"
                + " mode=\\\"FIRST\\\")";
        RuleExecutionResult result = run(call, "$v == \\\"A\\\"", ds, suppds);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(0, result.getViolations().size(),
                "no DS row carries a DSXFL column, so no row qualifies");
        // control: the same filter over a real DS column does qualify S1's row
        assertEquals(2,
                run("read_value(DSTERM, domain=DS, filter=(DSSEQ == \\\"1\\\"),"
                        + " mode=\\\"FIRST\\\")", "$v == \\\"A\\\"", ds, suppds).getViolations()
                                .size());
    }


    @Test
    void aDoubleDashInTheDomainLiteralResolvesAgainstTheEvaluatedDataset()
    {
        // F-W8-2: `domain="SUPP--"` on DM names SUPPDM (DatasetIdentity.resolveWildcard, Fix #33),
        // as crossDatasetVariableMetadata resolves it. RED before the fix: SUPP-- was looked up
        // verbatim and answered the absent-dataset "".
        IDataTable suppdm = RealTableFixture.of("SUPPDM").str("RDOMAIN", "DM").str("USUBJID", "S1")
                .str("QNAM", "RACEOTH").str("QVAL", "MIXED").build();
        assertEquals(2, run("read_value(QVAL, domain=\\\"SUPP--\\\", mode=\\\"FIRST\\\")",
                "$v == \\\"MIXED\\\"", suppdm).getViolations().size());
    }


    @Test
    void aNumericUngroupedReadValueMatchesAsItsCellTextInAMembership()
    {
        // Round 2 L3: the ungrouped answer became ONE ConstVector (XCUT PERF 2) whose payload is
        // the cell's number, and the membership reader folded that payload — Double 42.0 as
        // "42.0" — where the per-row vector it replaced folded the cell's text "42". RED before
        // the fix: AGETXT "42" was no longer a member.
        IDataTable dm = RealTableFixture.of("DM").str("DOMAIN", "DM", "DM")
                .str("USUBJID", "S1", "S2").str("AGETXT", "42", "7").build();
        IDataTable ts = RealTableFixture.of("TS").str("DOMAIN", "TS").str("TSPARMCD", "AGE")
                .dbl("TSNUM", 42.0).build();
        RuleExecutionResult result = RuleRunnerCalls
                .execute(loaded("read_value(TSNUM, domain=TS, mode=\\\"FIRST\\\")",
                        "not empty(USUBJID) and AGETXT in $v"), dm, resolver(dm, ts));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "S1's AGETXT \"42\" is the read value's text — " + result.getViolations());
    }
}
