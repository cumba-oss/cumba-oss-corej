package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-output-variable-null-key-provenance} P3 review MED-1, decision (a) under the owner's
 * OPEN-T (c) ruling: a <b>reported</b> {@code $} binding with no type source is refused at
 * <b>load</b> — a rule-text decision — instead of ERRORing the rule at run time only on the data
 * whose reader did not publish a value ({@code extract_metadata("Compression")} is a value on a
 * sas7bdat file and nothing on XPT / CSV / DSJ).
 *
 * <p>
 * The gate ({@code RulePackageLoader.validateReportedBindingTypes}) refuses a binding that is in
 * the rule's <em>effective</em> Output_Variables (authored or derived, after the {@code !$x}
 * subtraction), whose stage-A type is unknown, and whose root is not column-typed
 * ({@code StageAChecker.columnTypedBindings}). A binding that is not reported, or that a
 * {@code !$x} excludes, is never refused; and a failed binding-type walk fails the load of a rule
 * that reports a binding, instead of emptying the type map and deferring to run time.
 * </p>
 */
class ReportedBindingTypeSourceGateTest
{

    private static final String MIS = MissingValue.MIS.toString();

    private static Rule loadRaw(String body) throws Exception
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}").getRules()
                .get("R1");
        assertNotNull(rule);
        return rule;
    }


    private static Rule load(String body) throws Exception
    {
        Rule rule = loadRaw(body);
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static String compression(String outputVariables)
    {
        return """
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$c","expression":"extract_metadata(\\"Compression\\")"}],
                 "Check":{"expression": "AETERM != \\"zz\\" and empty($c)"},
                 "Outcome":{"Message":"m","Output_Variables":%s}}""".formatted(outputVariables);
    }


    @Test
    void aReportedBindingOverAnUnpinnedMetadataKeyIsALoadError() throws Exception
    {
        // The review's reach: "Compression" is not one of the six typed extract_metadata keys,
        // so the binding is untyped at load; reporting it is refused, naming the binding.
        Rule rule = loadRaw(compression("[\"AETERM\",\"$c\"]"));

        String error = rule.getLoadError();
        assertNotNull(error, "a reported untyped binding must not load");
        assertTrue(error.contains("$c") && error.contains("no type source"), error);
        assertTrue(error.contains("extract_metadata(\"Compression\")"),
                "the error names the expression: " + error);
        assertTrue(error.contains("remove $c from Output_Variables"),
                "an AUTHORED entry is removed, not excluded (!$c names nothing derived): " + error);
        assertTrue(error.contains("dataset_size"), "the typed keys are named: " + error);
    }


    @Test
    void theSameBindingNotReportedLoadsAndRuns() throws Exception
    {
        Rule rule = load(compression("[\"AETERM\"]"));
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae);

        assertEquals(1, result.getViolationCount(), result.getStatusMessage());
        assertFalse(result.getViolations().get(0).getValues().containsKey("$c"));
    }


    @Test
    void anExclusionOfTheBulkBindingIsTheExclusionGatesOwnError() throws Exception
    {
        // extract_metadata is a bulk-result function: its binding is never derived, only reported
        // when authored — so `!$c` names nothing the rule derives (E-3.1), and "not reported" is
        // the excluded form for it. The load error is E-3.1's, not this gate's.
        Rule rule = loadRaw(compression("[\"AETERM\",\"!$c\"]"));

        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("names nothing the rule derives"),
                rule.getLoadError());
        assertFalse(rule.getLoadError().contains("no type source"), rule.getLoadError());
    }


    private static String derivedFold(String outputVariables)
    {
        return """
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$m","expression":"extract_metadata(\\"Compression\\")"},
                             {"name":"$v","expression":"upper($m)"}],
                 "Check":{"expression": "AETERM != \\"zz\\" and empty($v)"},
                 "Outcome":{"Message":"m","Output_Variables":%s}}""".formatted(outputVariables);
    }


    @Test
    void aDerivedUntypedBindingIsRefusedAndItsExclusionLoads() throws Exception
    {
        // $v is DERIVED (a case fold is not a bulk result) and untyped: a fold carries its
        // argument's missing through unchanged, so it has no more type source than $m.
        Rule refused = loadRaw(derivedFold("[\"AETERM\"]"));
        assertNotNull(refused.getLoadError(), "a derived untyped binding is reported, so refused");
        assertTrue(
                refused.getLoadError().contains("$v")
                        && refused.getLoadError().contains("exclude it with !$v"),
                refused.getLoadError());

        Rule excluded = load(derivedFold("[\"AETERM\",\"!$v\"]"));
        assertFalse(excluded.effectiveOutputVariablesOrAuthored().contains("$v"),
                "precondition: excluded " + excluded.effectiveOutputVariablesOrAuthored());
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(excluded, ae);
        assertEquals(1, result.getViolationCount(), result.getStatusMessage());
    }


    @Test
    void columnTypedRootsAreNotRefused() throws Exception
    {
        // max / read_value / row_max / coalesce / colref / value() type their result by the
        // column they read (a stage-B fact the evaluation carries); a case fold over one and a
        // $-reference to one inherit that, as does a plain column reference.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Bindings":[{"name":"$mx","expression":"max(AESEQ, group=[USUBJID])"},
                                     {"name":"$up","expression":"upper($mx)"},
                                     {"name":"$al","expression":"$mx"},
                                     {"name":"$rv","expression":"read_value(DMVAL, domain=DM, mode=\\"FIRST\\")"},
                                     {"name":"$col","expression":"AETERM"}],
                         "Check":{"expression": "AETERM != \\"zz\\" and not empty($mx) and not empty($up) and not empty($al) and not empty($rv) and not empty($col)"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","$mx","$up","$al","$rv","$col"]}}""");

        assertEquals(ExprType.Unknown.UNKNOWN, rule.staticBindingTypes().get("$mx"),
                "precondition: max is untyped at load");
        assertEquals(Set.of("$mx", "$up", "$al", "$rv", "$col"),
                StageAChecker.columnTypedBindings(rule.bindingOrder()));
    }


    @Test
    void theColumnTypedFunctionsAreExactlyTheUnpinnedNonKeyedValueFunctions()
    {
        // Ties the gate's roster to P3's ratchet (FindingValuesTypedDefaultTest
        // .everyValueFunctionWhoseResultItsSemanticsPinHasAResultType): the unpinned VALUE
        // functions minus the two keyed ones, plus colref and value(), which stage A types in
        // their own branches.
        assertEquals(Set.of("read_value", "max", "row_max", "coalesce", "colref", "value"),
                StageAChecker.COLUMN_TYPED_FUNCTIONS);
    }


    @Test
    void aFailedBindingTypeWalkFailsTheLoadOfARuleThatReportsABinding() throws Exception
    {
        // A typed key loads clean; then the binding is replaced by a shape the stage-A walk
        // cannot type (a STRING literal whose value is not a String — the static-key read
        // casts). Before: bindingTypes swallowed the failure as an empty map and the rule loaded,
        // deferring to a run-time ERROR. Now the load names the failure.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$s","expression":"extract_metadata(\\"dataset_size\\")"}],
                 "Check":{"expression": "AETERM != \\"zz\\" and empty($s)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$s"]}}""");
        Expr broken = new Expr.Call("extract_metadata",
                List.of(new Expr.Lit(Expr.LitKind.STRING, 42)), Map.of());
        rule.setCompiledBindings(List.of(new CompiledBinding("$s", broken, List.of(), null)));
        assertThrows(RuntimeException.class, () -> StageAChecker.bindingTypesOrThrow(rule),
                "precondition: the walk fails on this shape");

        RulePackageLoader.deriveOutputVariables(rule);

        assertNotNull(rule.getLoadError(), "a failed walk must fail the load");
        assertTrue(rule.getLoadError().contains("could not be derived"), rule.getLoadError());
    }


    @Test
    void aColumnTypedBindingReportsItsCarriersDefaultForEveryNonCharColumnType()
    {
        // The marginal reaches of the review: read_value / max over a column whose loaded type
        // is not a char or a plain number (COMPLEX in the internal datatable, OTHER from parquet
        // / RDS, MISSING from a SQL NULL column) answer a missing whose carrier type is that
        // column's. The carrier decides: a non-char column reports MIS — never an ERROR.
        CompiledBinding read = new CompiledBinding("$r",
                net.cumba.corej.core.expr.CheckExpressionParser
                        .parse("read_value(X, domain=DM, mode=\"FIRST\")"),
                List.of(), null);
        BindingValue columnTyped = new BindingValue(read, ExprType.Unknown.UNKNOWN, true);

        assertEquals("", columnTyped.reportedDefault(DataValueType.STRING));
        assertEquals(MissingValue.MIS, columnTyped.reportedDefault(DataValueType.OTHER));
        assertEquals(MissingValue.MIS, columnTyped.reportedDefault(DataValueType.MISSING));
        for (DataValueType type : DataValueType.values())
        {
            if (type != DataValueType.STRING)
            {
                assertEquals(MissingValue.MIS, columnTyped.reportedDefault(type), type.name());
            }
        }
    }


    @Test
    void aReadValueOverAnOtherTypedColumnReportsMisThroughTheRunner() throws Exception
    {
        // End to end: the runner hands the column-typed flag to the BindingValue (RuleRunner).
        // A read_value over a column whose loaded type is OTHER (parquet / RDS) and whose cell is
        // missing answers a missing carried as OTHER: the finding reports MIS, where P3 ERRORed
        // the rule.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                         "Bindings":[{"name":"$r","expression":"read_value(X, domain=DM, mode=\\"FIRST\\")"}],
                         "Check":{"expression": "record_count() == 0"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","$r"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM").build();
        IDataTable base = MockTable.of().name("DM").colDouble("X", (Double) null).build();
        net.cumba.datatable.DataTableMeta otherTyped = net.cumba.datatable.DataTableMeta.builder()
                .name("DM").rowCount(1).totalRowCount(1)
                .columns(new net.cumba.datatable.DataTableColumnMeta[]
                {
                        net.cumba.datatable.DataTableColumnMeta.builder().index(0).name("X")
                                .type(DataValueType.OTHER).build()
                }).build();
        IDataTable dm = (IDataTable) java.lang.reflect.Proxy
                .newProxyInstance(IDataTable.class.getClassLoader(), new Class<?>[]
                {
                        IDataTable.class
                }, (proxy, method, args) ->
                {
                    if ("getMetaData".equals(method.getName()))
                    {
                        return otherTyped;
                    }
                    try
                    {
                        return method.invoke(base, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException ex)
                    {
                        throw ex.getCause();
                    }
                });

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae,
                name -> "DM".equals(name) ? dm : null);

        assertEquals(1, result.getViolationCount(),
                result.getStatus() + " " + result.getStatusMessage());
        assertEquals(MIS, result.getViolations().get(0).getValues().get("$r"),
                "a non-char column's missing: MIS — " + result.getViolations().get(0).getValues());
    }


    @Test
    void aComplexColumnReadReportsTheMissingMarker()
    {
        // COMPLEX exists only in the internal datatable (RDS complex vectors); the OSS twin has
        // no such type, so this case is assumed, and named, rather than compiled against.
        DataValueType complex = java.util.Arrays.stream(DataValueType.values())
                .filter(t -> "COMPLEX".equals(t.name())).findFirst().orElse(null);
        Assumptions.assumeTrue(complex != null, "this datatable has no COMPLEX type");
        CompiledBinding max = new CompiledBinding("$mx",
                net.cumba.corej.core.expr.CheckExpressionParser.parse("max(Z, group=[USUBJID])"),
                List.of(), null);

        assertEquals(MissingValue.MIS,
                new BindingValue(max, ExprType.Unknown.UNKNOWN, true).reportedDefault(complex));
    }


    @Test
    void theRunTimeThrowIsADefensiveAssertionTheLoadGateMakesUnreachable()
    {
        // A binding that is neither typed nor column-typed is what the load gate refuses; only a
        // hand-built value can still reach the throw, and its message says so.
        CompiledBinding binding = new CompiledBinding("$u",
                new Expr.Call("extract_metadata",
                        List.of(new Expr.Lit(Expr.LitKind.STRING, "Compression")), Map.of()),
                List.of(), null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new BindingValue(binding, ExprType.Unknown.UNKNOWN, false)
                        .reportedDefault(DataValueType.MISSING));
        assertTrue(ex.getMessage().contains("$u") && ex.getMessage().contains("unreachable"),
                ex.getMessage());
    }


    @Test
    void anUnreportedUntypedBindingWithAFailedWalkStillLoads() throws Exception
    {
        // The walk's result is consumed only by a reported binding's default: a rule reporting
        // no binding keeps stage A's "a checker defect never parks a rule".
        Rule rule = load(compression("[\"AETERM\"]"));
        Expr broken = new Expr.Call("extract_metadata",
                List.of(new Expr.Lit(Expr.LitKind.STRING, 42)), Map.of());
        rule.setCompiledBindings(List.of(new CompiledBinding("$c", broken, List.of(), null)));

        RulePackageLoader.deriveOutputVariables(rule);

        assertNull(rule.getLoadError(), rule.getLoadError());
    }


    @Test
    void aBulkBindingReadOnlyInTheCheckOfARuleWithNoOutputVariablesRuns() throws Exception
    {
        // Confirmation look MED-1 (b): with no Output_Variables the effective list is empty (the
        // bulk-result $c is never derived), so the run time projects the Fix #15 inference. That
        // inference added $c — a binding the load gate never judged — and the rule ERRORed on a
        // reader that does not publish "Compression" (IllegalStateException from
        // BindingValue.reportedDefault). The inference now drops every binding the derivation
        // does not derive: the rule runs, and its finding does not carry $c.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$c","expression":"extract_metadata(\\"Compression\\")"}],
                 "Check":{"expression": "empty($c)"},
                 "Outcome":{"Message":"m"}}""");
        assertEquals(List.of(), rule.effectiveOutputVariablesOrAuthored(),
                "precondition: nothing derived, so the inference projects");
        assertEquals(Set.of("$c"), OutputVariableDeriver.underivedBindingIds(rule));
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae);

        assertEquals(1, result.getViolationCount(),
                result.getStatus() + " " + result.getStatusMessage());
        assertFalse(result.getViolations().get(0).getValues().containsKey("$c"),
                "an underived binding is never projected: "
                        + result.getViolations().get(0).getValues());
    }


    @Test
    void aBindingReadOnlyUnderNegatedExistenceIsNotInferredEither() throws Exception
    {
        // The second divergence the check of MED-1 found: D3 does not derive a name the Check
        // reads only under negated existence, and the inference took a `not var_exists(...)`
        // subject all the same. $v (a fold over an unpinned key) is untyped and not bulk.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$m","expression":"extract_metadata(\\"Compression\\")"},
                             {"name":"$v","expression":"upper($m)"}],
                 "Check":{"expression": "not var_exists($v)"},
                 "Outcome":{"Message":"m"}}""");
        assertEquals(List.of(), rule.effectiveOutputVariablesOrAuthored(),
                "precondition: $m is bulk, $v is D3-suppressed");
        assertEquals(Set.of("$m", "$v"), OutputVariableDeriver.underivedBindingIds(rule));
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae);

        assertEquals(1, result.getViolationCount(),
                result.getStatus() + " " + result.getStatusMessage());
        assertFalse(result.getViolations().get(0).getValues().containsKey("$v"),
                result.getViolations().get(0).getValues().toString());
    }


    @Test
    void aCoalesceOfTwoUnpinnedKeysIsNotColumnTyped() throws Exception
    {
        // Confirmation look LOW-1: coalesce answers whichever argument has a value, so it has a
        // type source only when an argument does. Two unpinned extract_metadata keys read no
        // column: reported, the binding is a load error.
        Rule refused = loadRaw(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Bindings":[{"name":"$x","expression":"coalesce(extract_metadata(\\"Compression\\"), extract_metadata(\\"Encoding\\"))"}],
                         "Check":{"expression": "AETERM != \\"zz\\" and empty($x)"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","$x"]}}""");

        assertNotNull(refused.getLoadError(), "a coalesce of untyped arguments has no type source");
        assertTrue(
                refused.getLoadError().contains("$x")
                        && refused.getLoadError().contains("no type source"),
                refused.getLoadError());
    }


    @Test
    void coalesceAndMaxQualifyThroughATypeSourceArgument() throws Exception
    {
        // The positive half of LOW-1: one column argument makes coalesce column-typed; max is
        // column-typed through its target — a column, or a call whose result type ElementTable
        // pins (max(record_count(...)), CDISC-SEND-0305 / -0306) — and not through an unpinned
        // key. A scalar literal is a type source too (a present one is always the answer).
        // read_value takes a bare column by contract (ReadValue.spec), so its root alone still
        // qualifies.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Bindings":[{"name":"$co","expression":"coalesce(extract_metadata(\\"Compression\\"), AETERM)"},
                                     {"name":"$cc","expression":"coalesce(extract_metadata(\\"Compression\\"), extract_metadata(\\"Encoding\\"))"},
                                     {"name":"$mr","expression":"max(record_count(domain=\\"EX\\", group=[USUBJID]), domain=\\"DM\\", group=[USUBJID])"},
                                     {"name":"$mc","expression":"max(AESEQ, group=[USUBJID])"},
                                     {"name":"$mk","expression":"max(extract_metadata(\\"Compression\\"), group=[USUBJID])"},
                                     {"name":"$cl","expression":"coalesce(extract_metadata(\\"Compression\\"), \\"NONE\\")"}],
                         "Check":{"expression": "AETERM != \\"zz\\" and not empty($co) and not empty($cc) and $mr > 0 and not empty($mc) and not empty($mk) and not empty($cl)"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","$co","$mr","$mc","$cl","!$cc","!$mk"]}}""");

        assertEquals(Set.of("$co", "$mr", "$mc", "$cl"),
                StageAChecker.columnTypedBindings(rule.bindingOrder()));
    }


    @Test
    void theLoadGatesFoldMessageUnwrapsEveryCaseFold() throws Exception
    {
        // Confirmation look LOW-2: the message's fold unwrap reads StageAChecker.CASE_FOLDS, so a
        // lowcase over an unpinned key still names extract_metadata's typed keys.
        Rule refused = loadRaw(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Bindings":[{"name":"$l","expression":"lowcase(extract_metadata(\\"Compression\\"))"}],
                         "Check":{"expression": "AETERM != \\"zz\\" and empty($l)"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","$l"]}}""");

        assertNotNull(refused.getLoadError());
        assertTrue(refused.getLoadError().contains("extract_metadata's typed keys"),
                refused.getLoadError());
        assertEquals(Set.of("upper", "upcase", "lower", "lowcase"), StageAChecker.CASE_FOLDS);
    }


    @Test
    void aReportedTypedKeyStillReportsItsDefault() throws Exception
    {
        // Control: a pinned key is typed, loads, and reports its typed default (P3).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$size","expression":"extract_metadata(\\"dataset_size\\")"}],
                 "Check":{"expression": "AETERM != \\"zz\\" and empty($size)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$size"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae);

        assertEquals(1, result.getViolationCount(), result.getStatusMessage());
        assertEquals(MIS, result.getViolations().get(0).getValues().get("$size"));
    }
}
