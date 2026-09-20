package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.corej.core.model.OperationType;
import org.junit.jupiter.api.Test;

/**
 * ⭐ Phase 7 (D119h / D91g's descriptor↔dispatch half): the drift gate holding
 * {@code CompilerDispatchedCalls}' declarations and {@code ExprCompiler}'s dispatch together, in
 * <b>both</b> directions — the two-authorities-drifting-apart defect the retired
 * {@code HARDCODED_BOOLEAN_CALLS} mirror ("must mirror compileBoolCall") invited.
 *
 * <ul>
 * <li><b>Declared ⇒ dispatched</b>: every {@code fn == null} BOOLEAN descriptor must compile
 * through a real cascade arm. Probed <em>behaviorally</em> — one representative corpus spelling per
 * name — because D91g forbids deriving the dispatch set by scanning {@code ExprCompiler}'s source
 * (a source scanner can silently stop matching). A declared name no arm compiles lands in the
 * registry tail's {@link ExprCompiler#DISPATCH_DRIFT_SENTINEL}, which this gate rejects by
 * message.</li>
 * <li><b>Dispatched ⇒ declared</b>: closed by construction — {@code compileBoolCall}'s pre-cascade
 * guard throws {@code no native function} for any name without a descriptor before any arm can run.
 * The negative control below <em>proves the guard bites</em> by unregistering a real descriptor and
 * watching its arm become unreachable.</li>
 * </ul>
 *
 * <p>
 * ⚠ <b>Non-vacuity is asserted explicitly</b> (the phase-7 lesson, D122b — five silent vacuities,
 * none caught by an assertion): the descriptor counts are pinned (26 + 2 + 13, total parameter
 * count 84), the probe map's key set must equal the declared name sets (a new descriptor without a
 * dispatch-proving probe fails the gate), and the registry-derived sets must be non-empty and
 * exactly equal to the declarations — so losing the SPI registration line, or emptying either side,
 * reds instead of passing over nothing.
 * </p>
 */
class CompilerDispatchDriftGateTest
{

    /** One representative corpus spelling per compileBoolCall-dispatched boolean call. */
    private static final Map<String, String> BOOL_CALL_PROBES = boolCallProbes();

    /** The two Q1 group operators are dispatched only under {@code not} (compileNot's arms). */
    private static final Map<String, String> NEGATION_PROBES = Map.of(
            "has_next_corresponding_record",
            "not has_next_corresponding_record(AESEQ, AEDECOD, within=USUBJID, ordering=AESEQ)",
            "is_sorted_by", "not is_sorted_by(TSSEQ, by=[asc(\"TSGRPID\")], within=USUBJID)");

    private static Map<String, String> boolCallProbes()
    {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("ds_exists", "ds_exists(DM)");
        probes.put("ds_not_exists", "ds_not_exists(DM)");
        probes.put("var_exists", "var_exists(AEDECOD)");
        probes.put("var_not_exists", "var_not_exists(AEDECOD)");
        probes.put("var_is_null", "var_is_null(AEDECOD)");
        probes.put("does_not_equal_string_part",
                "does_not_equal_string_part(NAME, VALUE, regex=\"^AB\")");
        probes.put("has_not_equal_length", "has_not_equal_length(USUBJID, 5)");
        probes.put("has_equal_length", "has_equal_length(USUBJID, 5)");
        probes.put("has_multiple_values_for", "has_multiple_values_for(AEREL, AERELN)");
        probes.put("present_on_multiple_rows_within",
                "present_on_multiple_rows_within(AESEQ, within=USUBJID)");
        probes.put("empty_within_except_last_row",
                "empty_within_except_last_row(TSVAL, TSPARMCD, ordering=TSSEQ)");
        probes.put("is_not_unique_relationship", "is_not_unique_relationship(AEDECOD, USUBJID)");
        probes.put("is_unique_relationship", "is_unique_relationship(AEDECOD, keys=[USUBJID])");
        probes.put("is_not_unique_set", "is_not_unique_set([USUBJID, DOMAIN])");
        probes.put("is_unique_set", "is_unique_set([USUBJID, DOMAIN])");
        probes.put("is_not_unique_value", "is_not_unique_value(USUBJID)");
        probes.put("is_unique_value", "is_unique_value(USUBJID)");
        probes.put("is_inconsistent_across_dataset",
                "is_inconsistent_across_dataset(STRESU, keys=[TESTCD])");
        probes.put("inconsistent_enumerated_columns", "inconsistent_enumerated_columns(TSVAL)");
        probes.put("not_contains_all", "not_contains_all($source_list, $required_list)");
        probes.put("contains_all", "contains_all($source_list, $required_list)");
        probes.put("has_same_values", "has_same_values(MHCAT)");
        probes.put("shares_no_elements_with",
                "shares_no_elements_with($source_list, $required_list)");
        probes.put("shares_elements_with", "shares_elements_with($source_list, $required_list)");
        probes.put("is_not_ordered_subset_of",
                "is_not_ordered_subset_of($source_list, $required_list)");
        probes.put("is_ordered_subset_of", "is_ordered_subset_of($source_list, $required_list)");
        return probes;
    }

    // ------------------------------------------------------------------
    // Non-vacuity pins — an edit that empties either authority reds here.
    // ------------------------------------------------------------------


    @Test
    void declaredCountsArePinned()
    {
        assertEquals(26, CompilerDispatchedCalls.booleanCallNames().size(),
                "compileBoolCall-dispatched boolean calls");
        assertEquals(2, CompilerDispatchedCalls.negationDispatchedBooleanCallNames().size(),
                "negation-dispatched boolean calls (compileNot's Q1 arms)");
        assertEquals(13, CompilerDispatchedCalls.valueCallNames().size(),
                "compiler-dispatched value calls (vlm_* family, max_value_length, the decode"
                        + " accessors)");
        int parameters = new CompilerDispatchedCalls().functions().stream()
                .mapToInt(d -> d.parameters().size()).sum();
        assertEquals(84, parameters, "declared parameter surfaces across the 41 descriptors —"
                + " update deliberately when a signature legitimately changes");
    }


    @Test
    void registryServesExactlyTheDeclaredCompilerDispatchedDescriptors()
    {
        Set<String> boolFnNull = new TreeSet<>();
        Set<String> valueFnNull = new TreeSet<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            if (d.fn() == null)
            {
                (d.kind() == FunctionKind.BOOLEAN ? boolFnNull : valueFnNull).add(d.name());
            }
        }
        Set<String> declaredBool = new TreeSet<>(CompilerDispatchedCalls.booleanCallNames());
        declaredBool.addAll(CompilerDispatchedCalls.negationDispatchedBooleanCallNames());
        // Equality, not containment: if the SPI line is lost the registry side is EMPTY and this
        // fails; if another provider starts contributing fn-null descriptors, that new dispatch
        // obligation must land in this gate.
        assertEquals(declaredBool, boolFnNull,
                "fn == null BOOLEAN descriptors in the registry == the declared boolean calls");
        assertEquals(new TreeSet<>(CompilerDispatchedCalls.valueCallNames()), valueFnNull,
                "fn == null VALUE descriptors in the registry == the declared value calls");
    }


    @Test
    void compilerDispatchedNamesCollideWithNoOperation()
    {
        // Protects RecordCountSingleDescriptorTest's namespace pin: the registry/operation
        // overlap must stay exactly {record_count, dictionary_available}, so none of the 41 may
        // be an OperationType json name.
        List<String> all = new ArrayList<>();
        all.addAll(CompilerDispatchedCalls.booleanCallNames());
        all.addAll(CompilerDispatchedCalls.negationDispatchedBooleanCallNames());
        all.addAll(CompilerDispatchedCalls.valueCallNames());
        for (String name : all)
        {
            assertNull(OperationType.fromJson(name),
                    "'" + name + "' collides with an operation json name");
        }
    }

    // ------------------------------------------------------------------
    // Declared ⇒ dispatched, per name (behavioral — no source scanning, D91g).
    // ------------------------------------------------------------------


    @Test
    void everyDeclaredBooleanCallIsCompiledByADispatchArm()
    {
        assertEquals(CompilerDispatchedCalls.booleanCallNames(), BOOL_CALL_PROBES.keySet(),
                "every compileBoolCall-dispatched declaration needs a probe proving its arm");
        assertEquals(CompilerDispatchedCalls.negationDispatchedBooleanCallNames(),
                NEGATION_PROBES.keySet(),
                "every negation-dispatched declaration needs a probe proving its arm");
        List<String> drifted = new ArrayList<>();
        Map<String, String> probes = new LinkedHashMap<>(BOOL_CALL_PROBES);
        probes.putAll(NEGATION_PROBES);
        for (Map.Entry<String, String> probe : probes.entrySet())
        {
            try
            {
                assertNotNull(ExprCompiler.compile(CheckExpressionParser.parse(probe.getValue())));
            }
            catch (RuntimeException e)
            {
                // A semantic refusal from inside the arm still proves dispatch; only the two
                // never-dispatched shapes are drift.
                String message = String.valueOf(e.getMessage());
                if (message.contains(ExprCompiler.DISPATCH_DRIFT_SENTINEL)
                        || message.contains("no native function"))
                {
                    drifted.add(probe.getKey() + " -> " + message);
                }
            }
        }
        assertTrue(drifted.isEmpty(), "declared boolean calls without a compiled dispatch arm:\n  "
                + String.join("\n  ", drifted));
    }


    @Test
    void everyDeclaredValueCallIsCompiledByADedicatedOperandPlan()
    {
        // The value calls are dispatched by operandPlan's dedicated arms; the registry tail
        // (valueCallPlan) refuses their fn == null descriptors loudly. Probe through a value
        // comparison, whose left side compiles via operandPlan.
        List<String> drifted = new ArrayList<>();
        for (String name : CompilerDispatchedCalls.valueCallNames())
        {
            try
            {
                assertNotNull(
                        ExprCompiler.compile(CheckExpressionParser.parse(name + "(AEDECOD) == 1")));
            }
            catch (RuntimeException e)
            {
                String message = String.valueOf(e.getMessage());
                if (message.contains("no native function")
                        || message.contains("no dedicated operand plan"))
                {
                    drifted.add(name + " -> " + message);
                }
            }
        }
        assertTrue(drifted.isEmpty(), "declared value calls without a dedicated operand plan:\n  "
                + String.join("\n  ", drifted));
    }

    // ------------------------------------------------------------------
    // Negative controls — the gate proves its own teeth on every run.
    // ------------------------------------------------------------------


    @Test
    void aDescriptorWithoutADispatchArmHitsTheSentinel()
    {
        // Direction "declared ⇒ dispatched": register a boolean call NO cascade arm knows.
        String probe = "drift_probe_boolean_call";
        FunctionRegistry.register(new FunctionDescriptor(probe,
                List.of(Parameter.required("name", Unknown.UNKNOWN)), FunctionKind.BOOLEAN, null));
        try
        {
            ExpressionException ex = assertThrows(ExpressionException.class,
                    () -> ExprCompiler.compile(CheckExpressionParser.parse(probe + "(AEDECOD)")));
            assertTrue(ex.getMessage().contains(ExprCompiler.DISPATCH_DRIFT_SENTINEL),
                    "expected the dispatch-drift sentinel, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(probe),
                    "the sentinel must name the drifted call: " + ex.getMessage());
        }
        finally
        {
            FunctionRegistry.unregister(probe);
        }
    }


    @Test
    void aDispatchArmWithoutADescriptorIsUnreachable()
    {
        // Direction "dispatched ⇒ declared": with ds_exists' descriptor gone, its cascade arm —
        // still present and unchanged — must be unreachable (the pre-cascade registry guard).
        FunctionDescriptor saved = FunctionRegistry.descriptor("ds_exists");
        assertNotNull(saved, "ds_exists must be declared");
        FunctionRegistry.unregister("ds_exists");
        try
        {
            ExpressionException ex = assertThrows(ExpressionException.class,
                    () -> ExprCompiler.compile(CheckExpressionParser.parse("ds_exists(DM)")));
            assertTrue(ex.getMessage().contains("no native function 'ds_exists'"),
                    "expected the pre-cascade guard to name the undeclared call, got: "
                            + ex.getMessage());
        }
        finally
        {
            FunctionRegistry.register(saved);
        }
    }
}
