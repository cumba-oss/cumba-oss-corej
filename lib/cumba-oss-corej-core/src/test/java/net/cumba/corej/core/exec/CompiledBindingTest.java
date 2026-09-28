package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.RegistryTestSeam;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.BoundBinding;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Wave 0 of {@code RUNBOOK-operations-to-functions} ({@code PLAN-binding-expressions}): a
 * {@code Bindings:} entry may hold ANY expression, not only a single operation call. These tests
 * pin the loader's routing (R1), the ordered binding view (§5.0), the dangling / order gates over
 * both kinds (R6, R25), the lazy per-context storage (R10, D-W0-3), the hand-over contract an
 * operation reads a compiled binding through (§5.0 / I5) and the membership arms (I4). Probe
 * functions are planted through the registry's test seam ({@link RegistryTestSeam}) — never through
 * the corpus.
 */
class CompiledBindingTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Loads a one-rule package: {@code check} as the Check, {@code outputs} as the authored
     * Output_Variables, {@code bindings} as (name, expression) pairs in authored order.
     */
    private static Rule load(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "W0-TEST"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            Map<String, String> binding = new LinkedHashMap<>();
            if (bindings[i] != null)
            {
                binding.put("name", bindings[i]);
            }
            binding.put("expression", bindings[i + 1]);
            declared.add(binding);
        }
        rule.put("Bindings", declared);
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", outputs));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule loads");
        return loaded;
    }


    private static Rule loadClean(String check, String... bindings) throws Exception
    {
        return loadClean(check, List.of(), bindings);
    }


    private static Rule loadClean(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Rule rule = load(check, outputs, bindings);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AETERM", "headache", "Nausea", "HEADACHE").col("AESER", "Y", "N", "Y")
                .col("X", "A", "B", "C").col("Y", "A", "Z", "Q").build();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result;
    }


    private static Set<Long> firedRows(RuleExecutionResult result)
    {
        Set<Long> rows = new TreeSet<>();
        result.getViolations().forEach(v -> rows.add(v.getRow()));
        return rows;
    }


    private static List<String> names(Rule rule)
    {
        return rule.bindingOrder().stream().map(BoundBinding::name).toList();
    }


    /** A dataset-level list probe: every row the same three-member list. */
    private static FunctionDescriptor constList(String name)
    {
        return new FunctionDescriptor(name, List.of(), FunctionKind.VALUE,
                (run, args) -> ConstVector.of(List.of("A", "B", "C")));
    }

    // ------------------------------------------------------------------ R1 routing + §5.0 order


    @Test
    void aSingleTopLevelOperationCallKeepsTheOperationPathAndEverythingElseCompiles()
        throws Exception
    {
        Rule rule = loadClean("$u == \"HEADACHE\"", "$n", "record_count()", "$u", "upper(AETERM)",
                "$d", "distinct(AETERM)", "$m", "record_count() + 0");
        assertEquals(List.of("$n", "$d"),
                rule.getOperations().stream().map(Operation::getId).toList(),
                "a single top-level OperationType call keeps the operation path (D-W0-1)");
        assertEquals(List.of("$u", "$m"),
                rule.getCompiledBindings().stream().map(CompiledBinding::name).toList(),
                "every other expression is compiled — a nested operation call included");
        assertEquals(List.of("$n", "$u", "$d", "$m"), names(rule),
                "the ordered view interleaves both kinds in authored order");
        assertEquals(List.of("$n"), rule.compiledBinding("$u").predecessors());
    }


    @Test
    void theOrderedViewSurvivesADroppedOperationBinding() throws Exception
    {
        Rule rule = loadClean("$v == 1", "$a", "record_count()", "$u", "upper(AETERM)", "$b",
                "record_count()", "$v", "$b + 0");
        // An inliner drops operation bindings after load; the compiled ones must keep their place
        // relative to the survivors (an absolute index would shift $u past $b).
        rule.setOperations(List.of(rule.getOperations().get(1)));
        assertEquals(List.of("$u", "$b", "$v"), names(rule));
    }


    @Test
    void aDuplicateBindingNameIsALoadErrorWhateverTheKinds() throws Exception
    {
        for (String[] pair : new String[][]
        {
                {
                        "record_count()", "record_count()"
                },
                {
                        "record_count()", "upper(AETERM)"
                },
                {
                        "upper(AETERM)", "lower(AETERM)"
                }
        })
        {
            Rule rule = load("$x == \"A\"", List.of(), "$x", pair[0], "$x", pair[1]);
            assertNotNull(rule.getLoadError(), pair[0] + " + " + pair[1]);
            assertTrue(rule.getLoadError().contains("`$x` is declared twice"), rule.getLoadError());
        }
    }


    @Test
    void aCompiledBindingWithoutANameIsALoadError() throws Exception
    {
        Rule rule = load("AETERM == \"A\"", List.of(), null, "upper(AETERM)");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("declares no `name:`"), rule.getLoadError());
    }

    // ------------------------------------------------------------------ R6 dangling gate


    @Test
    void aCompiledBindingDefinesItsNameForTheDanglingReferenceGate() throws Exception
    {
        Rule rule = loadClean("$u == \"HEADACHE\"", "$u", "upper(AETERM)");
        assertNotNull(rule.getCheckExpr(), "the Check compiled");
    }


    @Test
    void aCompiledBindingReadingAnUndefinedNameIsDangling() throws Exception
    {
        Rule rule = load("$a > 0", List.of(), "$a", "$y + 1");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$y"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("which no Operations entry defines"),
                rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Bindings"),
                "names the surface it was found on: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------ R25 order across kinds


    @Test
    void aForwardReferenceAcrossBindingKindsIsAStageAError() throws Exception
    {
        Rule compiledReadsLaterOperation = load("$a > 0", List.of(), "$a", "$b + 1", "$b",
                "record_count()");
        assertNotNull(compiledReadsLaterOperation.getLoadError());
        assertTrue(compiledReadsLaterOperation.getLoadError().contains("declared later"),
                compiledReadsLaterOperation.getLoadError());
        try (var _ = RegistryTestSeam.register(constList("__w0_order_list__")))
        {
            Rule operationReadsLaterCompiled = load("X in $m", List.of(), "$m",
                    "minus($l, subtract=$s)", "$s", "distinct(X)", "$l", "__w0_order_list__()");
            assertNotNull(operationReadsLaterCompiled.getLoadError());
            assertTrue(operationReadsLaterCompiled.getLoadError().contains("declared later"),
                    operationReadsLaterCompiled.getLoadError());
        }
    }

    // ------------------------------------------------------------------ evaluation


    @Test
    void aValueBindingAnswersExactlyWhatTheInlineExpressionAnswers() throws Exception
    {
        Rule bound = loadClean("$u == \"HEADACHE\"", "$u", "upper(AETERM)");
        Rule inline = loadClean("upper(AETERM) == \"HEADACHE\"");
        assertEquals(Set.of(0L, 2L), firedRows(run(bound, ae())));
        assertEquals(firedRows(run(inline, ae())), firedRows(run(bound, ae())));
    }


    @Test
    void aConditionBindingIsAPerRowBooleanComparableToTrueAndFalse() throws Exception
    {
        assertEquals(Set.of(0L, 2L),
                firedRows(run(loadClean("$s == true", "$s", "AESER == \"Y\""), ae())));
        assertEquals(Set.of(1L),
                firedRows(run(loadClean("$s == false", "$s", "AESER == \"Y\""), ae())));
        assertEquals(Set.of(1L),
                firedRows(run(loadClean("$s == true", "$s", "not (AESER == \"Y\")"), ae())),
                "a logical connective is a condition too");
    }


    @Test
    void aCompilerDispatchedBooleanCallCompilesAsABinding() throws Exception
    {
        // W1's pre-go review L9: a fn == null (compiler-dispatched) BOOLEAN call must compile on
        // the binding path and be read per row by the Check.
        // `$dup == true` over a condition binding means the condition itself (the compiler's
        // unified boolean surface), so it is compared with the bare inline call.
        Rule bound = loadClean("$dup == true", "$dup", "is_not_unique_set([USUBJID])");
        Rule inline = loadClean("is_not_unique_set([USUBJID])");
        Set<Long> inlineRows = firedRows(run(inline, ae()));
        assertEquals(Set.of(0L, 1L), inlineRows, "S1 is duplicated on rows 0 and 1");
        assertEquals(inlineRows, firedRows(run(bound, ae())));
    }


    @Test
    void aCompiledBindingReadsAnEarlierOperationBindingAndAnotherCompiledOne() throws Exception
    {
        Rule rule = loadClean("$m == 5", "$n", "record_count()", "$k", "$n + 1", "$m", "$k + 1");
        RuleExecutionResult result = run(rule, ae());
        assertFalse(result.getViolations().isEmpty(), "3 rows + 1 + 1 == 5");
        Rule miss = loadClean("$m == 6", "$n", "record_count()", "$k", "$n + 1", "$m", "$k + 1");
        assertTrue(run(miss, ae()).getViolations().isEmpty());
    }


    @Test
    void theTwoRoutesForRecordCountAgree() throws Exception
    {
        // D91e pin: `$n: record_count()` is a single top-level OperationType call (operation path)
        // and `$m: record_count() + 0` compiles through the registry's bare-form fast path — the
        // two routes wave 0 creates for one name must agree on the same table.
        Rule rule = loadClean("$n == $m", "$n", "record_count()", "$m", "record_count() + 0");
        assertNotNull(rule.getOperations());
        assertNotNull(rule.compiledBinding("$m"));
        assertFalse(run(rule, ae()).getViolations().isEmpty(), "3 == 3");
        Rule differ = loadClean("$n != $m", "$n", "record_count()", "$m", "record_count() + 0");
        assertTrue(run(differ, ae()).getViolations().isEmpty());
    }


    @Test
    void aNeverReadCompiledBindingNeverRunsAndAReadOneRunsOncePerContext() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        FunctionDescriptor counter = new FunctionDescriptor("__w0_counter__",
                List.of(Parameter.required("x", ExprType.Unknown.UNKNOWN)), FunctionKind.VALUE,
                (run, args) ->
                {
                    calls.incrementAndGet();
                    return args.get(0);
                });
        try (var _ = RegistryTestSeam.register(counter))
        {
            // "Read" includes the report: D4a derives every binding id as an output variable
            // (exactly as for an operation), so the unread binding is excluded from the report.
            Rule unread = loadClean("AETERM == \"Nausea\"", List.of("AETERM", "!$c"), "$c",
                    "__w0_counter__(AETERM)");
            assertEquals(Set.of(1L), firedRows(run(unread, ae())));
            assertEquals(0, calls.get(), "D-W0-3: a never-read binding never runs");
            Rule twice = loadClean("$c == \"Nausea\" or $c == \"HEADACHE\"", "$c",
                    "__w0_counter__(AETERM)");
            assertEquals(Set.of(1L, 2L), firedRows(run(twice, ae())));
            assertEquals(1, calls.get(),
                    "two reads in the Check and the report of two findings evaluate it once");
        }
    }

    // ------------------------------------------------------------------ §5.0 / I5 hand-over


    @Test
    void anOperationReadsACompiledDatasetLevelListAsTheRawList() throws Exception
    {
        try (var _ = RegistryTestSeam.register(constList("__w0_list__")))
        {
            // minus(name=$l, subtract=$s): the minuend is a compiled dataset-level list binding.
            // Through the hand-over it is the raw [A, B, C]; bypassed, the executor would read
            // the holder / a Vector as ONE bogus string element and `X in $m` would match no row.
            Rule rule = loadClean("X in $m", List.of("$m"), "$l", "__w0_list__()", "$s",
                    "distinct(Y)", "$m", "minus($l, subtract=$s)");
            RuleExecutionResult result = run(rule, ae());
            assertEquals(Set.of(1L, 2L), firedRows(result), "[A,B,C] minus [A,Z,Q] = [B,C]");
            assertEquals("[B, C]", result.getViolations().get(0).getValues().get("$m"));
        }
    }


    @Test
    void anOperationCannotReadAPerRowCompiledBinding() throws Exception
    {
        Rule rule = load("X in $m", List.of(), "$u", "upper(AETERM)", "$s", "distinct(Y)", "$m",
                "minus($u, subtract=$s)");
        assertNotNull(rule.getLoadError(), "§5.0 row 3: a per-row value has no row to pick");
        assertTrue(rule.getLoadError().contains("cannot read the per-row or per-variable binding"),
                rule.getLoadError());
    }


    @Test
    void theHandOverHelperThrowsRatherThanHandingAnOperationAPerRowVector() throws Exception
    {
        Rule rule = loadClean("$u == \"A\"", "$u", "upper(AETERM)");
        BindingValue value = new BindingValue(rule.compiledBinding("$u"));
        EvaluationContext ctx = EvaluationContext.builder().table(ae())
                .variables(Map.of("$u", value)).build();
        IllegalStateException backstop = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> BindingValue.forOperation(value, () -> ctx));
        assertTrue(backstop.getMessage().contains("$u"), backstop.getMessage());
        Object handedOver = ctx.resolveVariable("$u");
        assertInstanceOf(Vector.class, handedOver,
                "resolveVariable hands a per-row binding over as its Vector");
    }


    @Test
    void anInlineMinusReadsItsBindingOperandsForcedNotAsLazyWrappers() throws Exception
    {
        // I5 (ExprCompiler.forcedPriors): before wave 0 an inline operation with no $-group got
        // the live variables map, so an inline minus read its operands as raw LazyValue wrappers
        // (normalizeToList's scalar arm → one `LazyValue.toString()` element).
        Rule rule = loadClean("X in minus($l, subtract=$s)", "$l", "distinct(X)", "$s",
                "distinct(Y)");
        assertEquals(Set.of(1L, 2L), firedRows(run(rule, ae())), "[A,B,C] minus [A,Z,Q]");
    }

    // ------------------------------------------------------------------ report


    @Test
    void aPerRowCompiledBindingReportsItsValueAtTheFindingRow() throws Exception
    {
        Rule rule = loadClean("$u == \"HEADACHE\"", List.of("$u", "AETERM"), "$u", "upper(AETERM)");
        RuleExecutionResult result = run(rule, ae());
        assertEquals(2, result.getViolations().size());
        for (Violation v : result.getViolations())
        {
            assertEquals("HEADACHE", v.getValues().get("$u"));
        }
        assertEquals("headache", result.getViolations().get(0).getValues().get("AETERM"));
    }

    // ------------------------------------------------------------------ level (R9 / R24)


    @Test
    void aBindingsDerivedDomainIsItsExpressionsDomain() throws Exception
    {
        Rule rule = loadClean("$u == \"A\" and $m > 0", "$u", "upper(AETERM)", "$m",
                "record_count() + 0");
        assertEquals(Domain.ROW, rule.compiledBinding("$u").domain());
        assertEquals(Domain.DATASET, rule.compiledBinding("$m").domain());
        assertTrue(rule.compiledBinding("$u").needsCursor());
        assertFalse(rule.compiledBinding("$m").needsCursor());
        assertEquals(Domain.ROW, rule.getEvaluationDomain());
    }


    @Test
    void aDatasetLevelBindingIsHandedOverAsItsRawValue() throws Exception
    {
        Rule rule = loadClean("$m > 0", "$m", "record_count() + 0");
        EvaluationContext ctx = EvaluationContext.builder().table(ae())
                .variables(Map.of("$m", new BindingValue(rule.compiledBinding("$m")))).build();
        Object handedOver = ctx.resolveVariable("$m");
        assertFalse(handedOver instanceof Vector, "a dataset-level value, not a Vector");
        assertEquals(3.0, ((Number) handedOver).doubleValue(), 0.0);
    }

    // ------------------------------------------------------------------ I4 membership


    @Test
    void aDatasetLevelListBindingIsAMembershipSet() throws Exception
    {
        try (var _ = RegistryTestSeam.register(constList("__w0_set__")))
        {
            Rule rule = loadClean("Y not in $l", "$l", "__w0_set__()");
            assertEquals(Set.of(1L, 2L), firedRows(run(rule, ae())), "Z and Q are not in [A,B,C]");
        }
    }


    @Test
    void aListLiteralBindingIsADatasetLevelListAndAMembershipSet() throws Exception
    {
        Rule rule = loadClean("Y not in $l", "$l", "[\"A\", \"B\", \"C\"]");
        assertEquals(Domain.DATASET, rule.compiledBinding("$l").domain());
        RuleExecutionResult result = run(rule, ae());
        assertEquals(Set.of(1L, 2L), firedRows(result), "Z and Q are not in [A,B,C]");
        // R15: a list-valued binding is bulk — reported only when authored.
        assertFalse(result.getViolations().get(0).getValues().containsKey("$l"),
                result.getViolations().get(0).getValues().toString());
    }


    @Test
    void anOperationReadsAListLiteralBindingAsTheRawList() throws Exception
    {
        Rule rule = loadClean("X in $m", List.of("$m", "$l"), "$l", "[\"A\", \"B\", \"C\"]", "$s",
                "distinct(Y)", "$m", "minus($l, subtract=$s)");
        RuleExecutionResult result = run(rule, ae());
        assertEquals(Set.of(1L, 2L), firedRows(result), "[A,B,C] minus [A,Z,Q] = [B,C]");
        assertEquals("[B, C]", result.getViolations().get(0).getValues().get("$m"));
        assertEquals("[A, B, C]", result.getViolations().get(0).getValues().get("$l"),
                "an authored list binding is reported whole");
    }


    @Test
    void aListLiteralBindingHoldsPlainLiteralsOnly() throws Exception
    {
        Rule rule = load("Y in $l", List.of(), "$l", "[AETERM, \"A\"]");
        assertNotNull(rule.getLoadError(), "a column in a list binding has no dataset-level value");
        assertTrue(rule.getLoadError().contains("plain literals only"), rule.getLoadError());
    }


    @Test
    void aPerRowListBindingIsAPerRowMembershipSet() throws Exception
    {
        FunctionDescriptor rowList = new FunctionDescriptor("__w0_row_list__",
                List.of(Parameter.required("x", ExprType.Unknown.UNKNOWN)), FunctionKind.VALUE,
                (run, args) ->
                {
                    Vector x = args.get(0);
                    return new ComputedVector(run.rowCount(), DataValueType.STRING,
                            row -> List.of(x.value(row).cell().getValueAsString(), "Z"));
                });
        try (var _ = RegistryTestSeam.register(rowList))
        {
            // Row r's set is [X[r], "Z"]: row 0 (Y=A ∈ [A,Z]) and row 1 (Y=Z ∈ [B,Z]) are
            // members; row 2 (Y=Q ∉ [C,Z]) is not.
            Rule in = loadClean("Y in $p", "$p", "__w0_row_list__(X)");
            assertEquals(Set.of(0L, 1L), firedRows(run(in, ae())));
            Rule notIn = loadClean("Y not in $p", "$p", "__w0_row_list__(X)");
            assertEquals(Set.of(2L), firedRows(run(notIn, ae())));
        }
    }


    @Test
    void aPerRowMembershipSetIsFoldedOncePerDistinctListInstance()
    {
        AtomicInteger folds = new AtomicInteger();
        List<Object> shared = counting(List.of("A", "B"), folds);
        List<Object> other = counting(List.of("Q"), folds);
        Vector probe = new ComputedVector(4, DataValueType.STRING,
                row -> List.of("A", "Z", "B", "Q").get(row));
        Vector perRow = new ComputedVector(4, DataValueType.STRING,
                row -> row == 3 ? other : shared);
        BitSet members = net.cumba.corej.core.expr.eval.ExprCompilerTestAccess
                .boundMembership(probe, perRow, 4, false, false, false);
        assertEquals(BitSet.valueOf(new long[]
        {
                0b1101
        }), members, "A∈[A,B], Z∉[A,B], B∈[A,B], Q∈[Q]");
        assertEquals(2, folds.get(), "one fold per distinct list instance, not one per row");
    }


    /** A list whose every full iteration is counted — the fold of a member set iterates it once. */
    private static List<Object> counting(List<Object> delegate, AtomicInteger folds)
    {
        return new AbstractList<>()
        {

            @Override
            public Object get(int index)
            {
                return delegate.get(index);
            }


            @Override
            public int size()
            {
                return delegate.size();
            }


            @Override
            public java.util.Iterator<Object> iterator()
            {
                folds.incrementAndGet();
                return delegate.iterator();
            }
        };
    }

}
