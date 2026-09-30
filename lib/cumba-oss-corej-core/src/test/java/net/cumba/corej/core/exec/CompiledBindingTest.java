package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Wave 0 of {@code RUNBOOK-operations-to-functions} ({@code PLAN-binding-expressions}): a
 * {@code Bindings:} entry may hold ANY expression, not only a single operation call. These tests
 * pin the loader's parse (R1; since runbook W8 every binding is compiled — the operation path and
 * the hand-over contract an operation read a compiled binding through went with the carrier), the
 * ordered binding view (§5.0), the dangling / order gates (R6, R25), the lazy per-context storage
 * (R10, D-W0-3) and the membership arms (I4). Probe functions are planted through the registry's
 * test seam ({@link RegistryTestSeam}) — never through the corpus.
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
        return rule.bindingOrder().stream().map(CompiledBinding::name).toList();
    }


    /** A dataset-level list probe: every row the same three-member list. */
    private static FunctionDescriptor constList(String name)
    {
        return new FunctionDescriptor(name, List.of(), FunctionKind.VALUE,
                (run, args) -> ConstVector.of(List.of("A", "B", "C")));
    }

    // ------------------------------------------------------------------ R1 routing + §5.0 order


    @Test
    void everyBindingCompilesInAuthoredOrder() throws Exception
    {
        Rule rule = loadClean("$u == \"HEADACHE\"", "$n", "distinct(AEDECOD)", "$u",
                "upper(AETERM)", "$d", "distinct(AETERM)", "$m", "record_count() + 0");
        // Runbook W7 retired the last OperationType constant (distinct) and W8 the operation
        // path itself: every binding compiles, in authored order.
        assertEquals(List.of("$n", "$u", "$d", "$m"),
                rule.getCompiledBindings().stream().map(CompiledBinding::name).toList(),
                "every expression is compiled — the former operation calls included");
        assertEquals(List.of("$n", "$u", "$d", "$m"), names(rule),
                "the ordered view is the authored order");
        assertEquals(List.of("$n"), rule.compiledBinding("$u").predecessors());
    }


    /**
     * D-W8-4 ({@code PLAN-retire-operation-surface}): an expression that does not parse is the load
     * error "invalid binding expression" naming the text — the one behaviour the operation carrier
     * still had (its load error said "invalid operation expression").
     */
    @Test
    void anUnparseableBindingExpressionIsALoadErrorNamingIt() throws Exception
    {
        Rule rule = load("$x == \"A\"", List.of(), "$x", "upper(AETERM");
        assertNotNull(rule.getLoadError(), "an unparseable binding never loads");
        assertTrue(rule.getLoadError().contains("invalid binding expression `upper(AETERM`"),
                rule.getLoadError());
        assertFalse(rule.getLoadError().contains("invalid operation expression"),
                rule.getLoadError());
    }


    @Test
    void aDuplicateBindingNameIsALoadErrorWhateverTheKinds() throws Exception
    {
        for (String[] pair : new String[][]
        {
                {
                        "distinct(AETERM)", "distinct(AETERM)"
                },
                {
                        "distinct(AETERM)", "upper(AETERM)"
                },
                {
                        "upper(AETERM)", "lower(AETERM)"
                }
        })
        {
            Rule rule = load("$x == \"A\"", List.of(), "$x", pair[0], "$x", pair[1]);
            assertNotNull(rule.getLoadError(), pair[0] + " + " + pair[1]);
            assertTrue(rule.getLoadError().contains("`$x` is declared twice"), rule.getLoadError());
            // Review round 1, L4: the name IS authored — no second, false "dangling" diagnosis.
            assertFalse(rule.getLoadError().contains("which no binding defines"),
                    rule.getLoadError());
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
        assertTrue(rule.getLoadError().contains("which no binding defines"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Bindings"),
                "names the surface it was found on: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------ R25 order across kinds


    @Test
    void aForwardReferenceAcrossBindingKindsIsAStageAError() throws Exception
    {
        Rule readsALaterDistinct = load("$a > 0", List.of(), "$a", "size($b) + 1", "$b",
                "distinct(AETERM)");
        assertNotNull(readsALaterDistinct.getLoadError());
        assertTrue(readsALaterDistinct.getLoadError().contains("declared later"),
                readsALaterDistinct.getLoadError());
        try (var _ = RegistryTestSeam.register(constList("__w0_order_list__")))
        {
            Rule minusReadsALaterList = load("X in $m", List.of(), "$m", "minus($l, subtract=$s)",
                    "$s", "distinct(X)", "$l", "__w0_order_list__()");
            assertNotNull(minusReadsALaterList.getLoadError());
            assertTrue(minusReadsALaterList.getLoadError().contains("declared later"),
                    minusReadsALaterList.getLoadError());
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

    // (theTwoRoutesForRecordCountAgree — the D91e pin that `$n: record_count()` on the operation
    // path and `$m: record_count() + 0` on the registry's bare-form fast path agree — retired with
    // runbook W6: record_count is ONE compiled registry function, and its bare call IS its
    // unfiltered ungrouped branch, pinned by RecordCountFunctionTest.)


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
    void aListReaderCannotReadAPerRowCompiledBinding() throws Exception
    {
        Rule rule = load("X in $m", List.of(), "$u", "upper(AETERM)", "$s", "distinct(Y)", "$m",
                "minus($u, subtract=$s)");
        // (minus is a registry function since wave 4; the same load-time finding — stage A's
        // OPERATION_READS_CURSOR_BINDING — keeps the retired operation's runtime refusal.)
        assertNotNull(rule.getLoadError(), "§5.0 row 3: a per-row value has no row to pick");
        assertTrue(rule.getLoadError().contains("cannot read the per-row or per-variable binding"),
                rule.getLoadError());
    }


    /**
     * {@code resolveVariable} unwraps a per-row binding to its {@link Vector}. (Until runbook W8
     * this test also pinned {@code BindingValue.forOperation}'s refusal to hand an operation a
     * per-row vector; the helper went with the carrier.)
     */
    @Test
    void resolveVariableHandsAPerRowBindingOverAsItsVector() throws Exception
    {
        Rule rule = loadClean("$u == \"A\"", "$u", "upper(AETERM)");
        BindingValue value = new BindingValue(rule.compiledBinding("$u"));
        EvaluationContext ctx = EvaluationContext.builder().table(ae())
                .variables(Map.of("$u", value)).build();
        Object handedOver = ctx.resolveVariable("$u");
        assertInstanceOf(Vector.class, handedOver,
                "resolveVariable hands a per-row binding over as its Vector");
    }


    @Test
    void anInlineMinusReadsItsBindingOperandsForcedNotAsLazyWrappers() throws Exception
    {
        // I5 (ExprCompiler.forcedPriors, until runbook W8): before wave 0 an inline operation with
        // no $-group got the live variables map, so an inline minus read its operands as raw
        // LazyValue wrappers (normalizeToList's scalar arm → one `LazyValue.toString()` element).
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


    /**
     * Review round 1, M1: the per-variable loops give every column a fresh variables map; a
     * dataset-level binding must not be recomputed per column (nor per synthetic broadcast row).
     */
    @Test
    void aDatasetLevelBindingIsComputedOnceAcrossThePerVariableLoop() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        FunctionDescriptor counted = new FunctionDescriptor("__w0_counted__", List.of(),
                FunctionKind.VALUE, (run, args) ->
                {
                    calls.incrementAndGet();
                    return ConstVector.of(1L);
                }).aggregating();
        try (var _ = RegistryTestSeam.register(counted))
        {
            Rule rule = loadClean("varname() == \"X\" and $c == 1", "$c", "__w0_counted__()");
            RuleExecutionResult result = run(rule, ae());
            assertEquals(1, result.getViolations().size(), "the one column named X fires");
            assertEquals(1, calls.get(), "computed once for the five columns, not once per column");
        }
    }


    /**
     * Combined review of runbook W2–W8, confirmation look L-c: a binding that reads the ROW cursor
     * but not the VAR cursor answers the same Vector for every column, yet its memo was keyed on
     * the per-column variables map, so the {VAR,ROW} loop evaluated it once per column.
     */
    @Test
    void aRowOnlyBindingIsComputedOnceAcrossTheVariableRowLoop() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        FunctionDescriptor counter = new FunctionDescriptor("__cfu_row_counter__",
                List.of(Parameter.required("x", ExprType.Unknown.UNKNOWN)), FunctionKind.VALUE,
                (run, args) ->
                {
                    calls.incrementAndGet();
                    return args.get(0);
                });
        try (var _ = RegistryTestSeam.register(counter))
        {
            Rule rule = loadClean("varname() != \"AETERM\" and $c == \"Nausea\"",
                    List.of("variable_name", "$c"), "$c", "__cfu_row_counter__(AETERM)");
            assertEquals(Domain.ROW, rule.compiledBinding("$c").domain(), "a ROW-only binding");
            IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "S1", "S2")
                    .str("AETERM", "headache", "Nausea", "HEADACHE").str("X", "A", "B", "C")
                    .str("Y", "A", "Z", "Q").build();
            RuleExecutionResult result = run(rule, ae);
            assertEquals(3, result.getViolations().size(),
                    "USUBJID, X and Y on the Nausea row — " + result.getViolations());
            assertEquals("Nausea", result.getViolations().get(0).getValues().get("$c"));
            assertEquals(1, calls.get(), "computed once for the four columns, not once per column");
        }
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


    private static IDataTable seqs()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S2", "S3")
                .colLong("AESEQ", 1L, 2L, 3L).col("CSEQ", "1", "2", "3").col("X", "A", "B", "C")
                .build();
    }


    /** Status, message and fired rows of one execution — what "the same verdict" means. */
    private static String verdict(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        return result.getStatus() + " | " + result.getStatusMessage() + " | " + firedRows(result);
    }


    /**
     * Review round 1, M2: a list-literal binding is membership against the literal ITSELF — the
     * numeric member classification, the canonical member text and the column-type gates of the
     * inline list. Before the fix a numeric member became the text "1.0", so a numeric list against
     * a Char column fired on EVERY row where the inline list raises its column-type error (probes
     * E1d / E1e), and a list of digit strings against a Num column ran textually where the inline
     * list errors (probes F4 / F5).
     */
    @Test
    void aListLiteralBindingIsMembershipAgainstTheLiteralItself() throws Exception
    {
        IDataTable t = seqs();
        for (String[] shape : new String[][]
        {
                {
                        "AESEQ not in", "[1, 2]"
                },
                {
                        "CSEQ not in", "[1, 2]"
                },
                {
                        "CSEQ in", "[1, 2]"
                },
                {
                        "AESEQ not in", "[\"1\", \"2\"]"
                },
                {
                        "CSEQ not in", "[\"1\", \"2\"]"
                },
                {
                        "upper(X) in", "[\"a\"]"
                }
        })
        {
            String inline = verdict(loadClean(shape[0] + " " + shape[1], "$u", "upper(X)"), t);
            assertEquals(inline, verdict(loadClean(shape[0] + " $l", "$l", shape[1]), t),
                    shape[0] + " " + shape[1]);
            // …and through a binding that merely renames it.
            assertEquals(inline,
                    verdict(loadClean(shape[0] + " $k", "$l", shape[1], "$k", "$l"), t),
                    "alias: " + shape[0] + " " + shape[1]);
        }
        assertTrue(verdict(loadClean("CSEQ not in $l", "$l", "[1, 2]"), t)
                .startsWith("ERROR | column-type mismatch"), "E1d: the inline list's gate");
        assertTrue(verdict(loadClean("AESEQ not in $l", "$l", "[\"1\", \"2\"]"), t)
                .startsWith("ERROR | column-type mismatch"), "F4: the inline list's gate");
        assertEquals("EXECUTED | null | [2]",
                verdict(loadClean("AESEQ not in $l", "$l", "[1, 2]"), t));
    }


    /**
     * Review round 2, LOW-3: a scalar comparison against a list-valued COMPILED binding is a load
     * error — the inline spelling `X != ["A"]` never compiles, so `X != $l` may not quietly flag
     * every row (nor `==` none). An OPERATION-produced list keeps its behaviour (probe F1): the
     * shipped corpus compares against operation lists and wave 0 may not move those verdicts.
     */
    @Test
    void aScalarComparisonAgainstAListValuedCompiledBindingIsALoadError() throws Exception
    {
        for (String check : List.of("X != $l", "X == $l", "$l == X", "X < $l"))
        {
            Rule rule = load(check, List.of(), "$l", "[\"A\"]");
            assertNotNull(rule.getLoadError(), check);
            assertTrue(rule.getLoadError().contains("COMPARISON_WITH_LIST_BINDING"),
                    rule.getLoadError());
        }
        assertNotNull(
                load("TSVALCD != $l", List.of(), "$l",
                        "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")")
                                .getLoadError(),
                "a list-valued FUNCTION binding is known statically too");
        // Runbook W7: distinct is a list-valued registry function, so probe F1's exemption (an
        // OPERATION-produced list kept `X != $d` firing on every row) ended with the last
        // operation — the same load error now, and no shipped rule compares a distinct binding
        // with a scalar operator (measured: every consumer is in / not in / contains /
        // contains_all).
        Rule distinctList = load("X != $d", List.of(), "$d", "distinct(Y)");
        assertNotNull(distinctList.getLoadError());
        assertTrue(distinctList.getLoadError().contains("COMPARISON_WITH_LIST_BINDING"),
                distinctList.getLoadError());
        loadClean("X not in $l", "$l", "[\"A\"]");
        loadClean("X != $u", "$u", "upper(X)");
    }


    /** Review round 1, M2 (probe F10): a numeric list reaches an operation as its authored text. */
    @Test
    void aNumericListLiteralBindingReachesAnOperationAsItsCanonicalText() throws Exception
    {
        Rule rule = loadClean("CSEQ not in $m", List.of("$m", "$a"), "$a", "[1, 2, 3]", "$b", "[2]",
                "$m", "minus($a, subtract=$b)");
        RuleExecutionResult result = run(rule, seqs());
        assertEquals(Set.of(1L), firedRows(result),
                "[1, 3] leaves only CSEQ \"2\"; [1.0, 3.0] fired all");
        assertEquals("[1, 3]", result.getViolations().get(0).getValues().get("$m"));
        assertEquals("[1, 2, 3]", result.getViolations().get(0).getValues().get("$a"));
    }


    @Test
    void aListLiteralBindingMixingNumbersAndStringsIsTheInlineListsLoadError() throws Exception
    {
        Rule rule = load("X in $l", List.of(), "$l", "[1, \"A\"]");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("mixes numeric and string members"),
                rule.getLoadError());
    }


    /**
     * Review round 1, L1: §5.0 row 3 holds for an INLINE operation too — in the Check or nested in
     * a compiled binding, reading a per-row binding is a load error, never the run-time backstop.
     */
    @Test
    void anInlineOperationCannotReadAPerRowCompiledBinding() throws Exception
    {
        Rule inCheck = load("not empty(minus($a, subtract=$p))", List.of(), "$a", "[\"A\", \"B\"]",
                "$p", "upper(X)");
        assertNotNull(inCheck.getLoadError(), "probe E2a");
        assertTrue(
                inCheck.getLoadError().contains("OPERATION_READS_CURSOR_BINDING") && inCheck
                        .getLoadError().contains("the list function minus(…) in the Check"),
                inCheck.getLoadError());
        Rule inBinding = load("$e == true", List.of(), "$a", "[\"A\"]", "$p", "upper(X)", "$e",
                "empty(minus($a, subtract=$p))");
        assertNotNull(inBinding.getLoadError());
        assertTrue(inBinding.getLoadError().contains("in the binding $e"),
                inBinding.getLoadError());
        // The negative control: the same inline minus over two dataset-level lists loads clean.
        Rule clean = loadClean("X not in minus($a, subtract=$b)", "$a", "[\"A\", \"B\"]", "$b",
                "[\"B\"]");
        assertEquals(Set.of(1L, 2L), firedRows(run(clean, seqs())), "probe E2b: [A,B] minus [B]");
    }


    /**
     * Review round 1, T2: a library-dependent call (an OperationType until wave 4b) nested in a
     * compiled binding is SKIPPED by the provider GATE ("no Library access"), not by the
     * unusable-answer layer.
     */
    @Test
    void aNestedLibraryOperationWithNoLibraryIsSkippedByTheProviderGate() throws Exception
    {
        Rule rule = loadClean("$standard == true and empty(AETERM)", "$standard",
                "domain_is_custom() == false");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae());
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertEquals("Rule skipped — no Library access", result.getStatusMessage());
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
        assertEquals(2, folds.get(), "one fold per run of one list instance, not one per row");
        // Review round 2, LOW-2: ONE slot, never a map — a list instance that returns after
        // another is folded again rather than kept for the whole loop (split_by makes every row's
        // list distinct, and a per-instance map would hold N sets beside the vector's N lists).
        folds.set(0);
        Vector alternating = new ComputedVector(4, DataValueType.STRING,
                row -> row % 2 == 0 ? shared : other);
        net.cumba.corej.core.expr.eval.ExprCompilerTestAccess.boundMembership(probe, alternating, 4,
                false, false, false);
        assertEquals(4, folds.get(), "an alternating pair refolds per row: memory stays O(1)");
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


    /**
     * Combined review of runbook W2–W8, XCUT H1: a per-row function WITHOUT a column operand
     * (row_max selects its columns by a static regex) used to be classified dataset-level, and
     * every dataset-level reader — the broadcast fold's synthetic row, the hand-over, the report —
     * then read ROW 0 for the whole table (CDISC-/PMDA-AD0084 decided from row 0, silently). A
     * dataset-level binding whose rows disagree now fails loud, naming the binding.
     */
    @Test
    void aDatasetLevelBindingWhoseRowsDisagreeFailsLoudInsteadOfReadingRowZero() throws Exception
    {
        try (var _ = RegistryTestSeam.register(rowReaderWithoutColumnOperand()))
        {
            Rule rule = loadClean("$v == \"B\"", "$v", "__cfx_row_reader__()");
            // P7 decision 2 (no fallback): a native evaluation error PROPAGATES; the upstream
            // contract (LibraryValidator / StudyValidationService) turns it into the rule's
            // ERROR result. Pre-fix: EXECUTED with 0 findings, row 0's "A" compared for every row.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> RuleRunnerCalls.execute(rule, ae()),
                    "row 0 reads A, row 1 reads B: not one value for the table");
            assertTrue(refused.getMessage().contains("$v"),
                    "the message names the binding: " + refused.getMessage());
        }
    }


    /**
     * The same function declared a row reader ({@code FunctionDescriptor.readingRows}) is a per-row
     * binding: the Check reads every row's own value.
     */
    @Test
    void aDeclaredRowReaderIsAPerRowBinding() throws Exception
    {
        try (var _ = RegistryTestSeam.register(rowReaderWithoutColumnOperand().readingRows()))
        {
            Rule rule = loadClean("$v == \"B\"", "$v", "__cfx_row_reader__()");
            assertEquals(Set.of(1L), firedRows(run(rule, ae())), "X = A, B, C: row 1 only");
        }
    }


    /** A per-row probe with NO column operand: row r answers column X of row r (like row_max). */
    private static FunctionDescriptor rowReaderWithoutColumnOperand()
    {
        return new FunctionDescriptor("__cfx_row_reader__", List.of(), FunctionKind.VALUE,
                (run, args) ->
                {
                    IDataTable t = run.ctx().getTable();
                    int x = t.getMetaData().getColumnIndex("X");
                    return new ComputedVector(run.rowCount(), DataValueType.STRING,
                            row -> t.getDataValue(row, x).getValueAsString());
                });
    }
}
