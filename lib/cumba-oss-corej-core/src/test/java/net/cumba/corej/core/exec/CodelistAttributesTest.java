package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code get_codelist_attributes} as a <b>registry function</b> — wave 0's list-valued exemplar
 * ({@code PLAN-binding-expressions} phase 3, {@code CDISC-CG0288}). Carries every assertion the
 * four operation-surface tests made of the retired {@code GET_CODELIST_ATTRIBUTES} arm
 * ({@code OperationExecutorTest}, {@code OperationExecutorLibraryOpsTest},
 * {@code OperationExecutorSurvivorPinsTest}'s {@code ctPackageId} pins,
 * {@code OperationExecutorMoreCoverageTest}'s library-dependence), plus the function's own
 * contract: an aggregate list with the LIBRARY provider capability that never answers an empty
 * list.
 */
class CodelistAttributesTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A provider that echoes the CT package id it was asked for (so a test asserts WHICH package
     * was derived), or answers nothing when {@code echo} is off.
     */
    private static MetadataProvider echoProvider(@Nullable String standard, boolean echo)
    {
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getStandard()).thenReturn(standard);
        lenient().when(p.getCodelistAttribute(anyString(), anyString())).thenAnswer(
                inv -> echo ? List.of(inv.getArgument(0) + "/" + inv.getArgument(1)) : List.of());
        return p;
    }


    /** A provider answering a fixed attribute list for every package (standard sdtmig). */
    private static MetadataProvider answering(List<String> answer)
    {
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getStandard()).thenReturn("sdtmig");
        lenient().when(p.getCodelistAttribute(anyString(), anyString())).thenReturn(answer);
        return p;
    }


    private static IDataTable ts(String target, String version)
    {
        return MockTable.of().name("TS").col("TSVCDREF", target).col("TSVCDVER", version)
                .col("TSVALCD", "C1").build();
    }


    /**
     * Evaluates the function over {@code table}, as the compiler would, with a Library provider.
     */
    private static Object call(IDataTable table, @Nullable MetadataProvider provider)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(table).libraryProvider(provider)
                .build();
        Expr call = CheckExpressionParser
                .parse("get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")");
        return Objects.requireNonNull(
                net.cumba.corej.core.expr.eval.ExprCompiler.evaluateValueExpression(call, ctx))
                .value(0).resolved();
    }


    private static Object packageAskedFor(String target, String version, @Nullable String std)
    {
        return call(ts(target, version), echoProvider(std, true));
    }

    // ------------------------------------------------------------------ the descriptor


    @Test
    void isARegisteredAggregateWithTheLibraryCapability()
    {
        FunctionDescriptor d = Objects
                .requireNonNull(FunctionRegistry.descriptor(CodelistAttributes.NAME));
        assertEquals(ProviderNeed.LIBRARY, d.provider(),
                "the library dependence the operation's isLibraryDependent declared");
        assertTrue(d.aggregate(), "one list for the dataset (SPEC §1.4)");
        assertNull(net.cumba.corej.core.model.OperationType.fromJson(CodelistAttributes.NAME),
                "the OperationType is gone (R1/R3) — no compatibility arm");
        assertTrue(ProviderNeeds
                .ofCall((Expr.Call) CheckExpressionParser.parse(
                        "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")"))
                .library());
    }

    // ------------------------------------------------------------------ ctPackageId pins


    @Test
    void derivesThePackageFromTheRowsTargetVersionAndTheStandard()
    {
        assertEquals(List.of("sdtmct-2024-09-27/Term CCODE"),
                packageAskedFor("CDISC", "2024-09-27", "sdtmig"), "SDTM standard ⇒ sdtmct");
        assertEquals(List.of("adamct-2024-09-27/Term CCODE"),
                packageAskedFor("CDISC", "2024-09-27", "adamig"), "ADaM standard ⇒ adamct");
        assertEquals(List.of("sendct-2024-09-27/Term CCODE"),
                packageAskedFor("CDISC", "2024-09-27", "sendig"), "SEND standard ⇒ sendct");
        assertEquals(List.of("sdtmct-2024-09-27/Term CCODE"),
                packageAskedFor("CDISC", "2024-09-27", null),
                "an unknown/absent standard falls back to sdtmct, it must not crash");
        assertEquals(List.of("adamct-2024-09-27/Term CCODE"),
                packageAskedFor("CDISC CT", "2024-09-27", "adamig"), "the 'CDISC CT' spelling");
        assertEquals(List.of("sdtmct-2024-09-27/Term CCODE"),
                packageAskedFor("  CDISC  ", "  2024-09-27  ", "sdtmig"), "operands are stripped");
        // Negative — a sponsor/external CT target is used verbatim, never mapped onto CDISC.
        assertEquals(List.of("MEDDRA-2024-09-27/Term CCODE"),
                packageAskedFor("MEDDRA", "2024-09-27", "sdtmig"),
                "a non-CDISC target names its own package");
    }


    @Test
    void aBlankVersionNamesNoPackageAndIsUnusableNeverAnEmptyList()
    {
        // No package was invented: a fabricated id such as "sdtmct-" would come back as a
        // one-element list instead of the unusable signal.
        UnusableProviderAnswerException unusable = assertThrows(
                UnusableProviderAnswerException.class,
                () -> packageAskedFor("CDISC", "   ", "sdtmig"));
        assertEquals(CodelistAttributes.NAME, unusable.function());
        assertEquals(ProviderNeed.Kind.LIBRARY, unusable.kind());
    }


    @Test
    void unionsTheAttributeAcrossEveryDistinctPackageInRowOrder()
    {
        IDataTable table = MockTable.of().name("TS").col("TSVCDREF", "CDISC", "CDISC", "CDISC")
                .col("TSVCDVER", "2024-03-29", "2024-09-27", "2024-03-29").build();
        assertEquals(List.of("sdtmct-2024-03-29/Term CCODE", "sdtmct-2024-09-27/Term CCODE"),
                call(table, echoProvider("sdtmig", true)));
    }


    @Test
    void returnsTheProvidersListAsOneBroadcastConstant()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(ts("CDISC", "2024-09-27"))
                .libraryProvider(answering(List.of("C1", "C2"))).build();
        Vector v = CodelistAttributes.evaluate(EvalRun.fullRange(ctx),
                List.of(ConstVector.of("CDISC"), ConstVector.of("2024-09-27"),
                        ConstVector.of("Term CCODE")));
        assertInstanceOf(ConstVector.class, v, "an aggregate is broadcast, option 2 of §3.1");
        assertEquals(List.of("C1", "C2"), ((ConstVector) v).value());
    }

    // ------------------------------------------------------------------ the capability


    @Test
    void noProviderAndADegradedLibraryAndAnEmptyAnswerAreAllUnusable()
    {
        assertThrows(UnusableProviderAnswerException.class,
                () -> call(ts("CDISC", "2024-09-27"), null));
        assertThrows(UnusableProviderAnswerException.class,
                () -> call(ts("CDISC", "2024-09-27"), echoProvider("sdtmig", false)),
                "a package the provider cannot serve answers nothing — SKIP, never []");
        MetadataProvider degraded = echoProvider("sdtmig", true);
        lenient().when(degraded.isLibraryUnavailable()).thenReturn(true);
        assertThrows(UnusableProviderAnswerException.class,
                () -> call(ts("CDISC", "2024-09-27"), degraded),
                "Fix #369: a Library that could not be consulted is not answerable");
        assertThrows(UnusableProviderAnswerException.class,
                () -> call(MockTable.of().name("TS").col("TSVCDREF").col("TSVCDVER").build(),
                        echoProvider("sdtmig", true)),
                "no row resolves a package");
    }

    // ------------------------------------------------------------------ in a rule (CG0288 shape)


    private static Rule cg0288Shape() throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "W0-CG0288-SHAPE"));
        List<Map<String, String>> bindings = new ArrayList<>();
        bindings.add(Map.of("name", "$VALID_TERM_CODES", "expression",
                "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")"));
        rule.put("Bindings", bindings);
        rule.put("Check", Map.of("expression", "prefix(TSVCDREF, 5) == \"CDISC\" and not"
                + " empty($VALID_TERM_CODES) and TSVALCD not in $VALID_TERM_CODES"));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables",
                List.of("TSVALCD", "TSVCDREF", "$VALID_TERM_CODES")));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        return Objects.requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
    }


    @Test
    void theCg0288ShapeFiresReportsTheListAndDerivesOnlyTheTarget() throws Exception
    {
        Rule rule = cg0288Shape();
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNull(rule.getOperations(), "the binding is compiled, not an operation");
        assertEquals(Domain.DATASET, rule.compiledBinding("$VALID_TERM_CODES").domain(),
                "an aggregate binding is dataset-level");
        assertEquals(List.of("TSVALCD", "TSVCDREF", "$VALID_TERM_CODES"),
                rule.getEffectiveOutputVariables(),
                "TSVCDVER is a parameter, not derived (R16) — the operation never derived it");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ts("CDISC", "2024-09-27"),
                _ -> null, null, answering(List.of("C9", "C8")));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "C1 is not in [C9, C8]");
        assertEquals("[C9, C8]", result.getViolations().get(0).getValues().get("$VALID_TERM_CODES"),
                "the report renders the list exactly as the operation's list rendered");
        RuleExecutionResult member = RuleRunnerCalls.execute(rule, ts("CDISC", "2024-09-27"),
                _ -> null, null, answering(List.of("C1")));
        assertTrue(member.getViolations().isEmpty(), "C1 is a valid code");
    }


    private static Rule rule(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "W0-CG0288-VARIANT"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        if (!declared.isEmpty())
        {
            rule.put("Bindings", declared);
        }
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", outputs));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        return Objects.requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
    }


    /**
     * Review round 1, L2 — decided SUPPORT: the list-valued function written INLINE as the
     * membership right-hand side answers exactly what the same call held by a binding answers (a
     * binding is only a name for its expression). Its SKIP half was already right (§0.2 a); with a
     * usable answer it threw "membership right-hand side must be a list literal …" (probe E6b).
     */
    @Test
    void theFunctionInlineAsAMembershipSetAnswersAsItsBindingDoes() throws Exception
    {
        String call = "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")";
        Rule inline = rule("TSVALCD not in " + call, List.of());
        assertNull(inline.getLoadError(), inline.getLoadError());
        Rule bound = rule("TSVALCD not in $V", List.of(), "$V", call);
        for (List<String> answer : List.of(List.of("C9"), List.of("C1")))
        {
            RuleExecutionResult viaInline = RuleRunnerCalls.execute(inline,
                    ts("CDISC", "2024-09-27"), _ -> null, null, answering(answer));
            RuleExecutionResult viaBinding = RuleRunnerCalls.execute(bound,
                    ts("CDISC", "2024-09-27"), _ -> null, null, answering(answer));
            assertEquals(RuleExecutionStatus.EXECUTED, viaInline.getStatus(),
                    viaInline.getStatusMessage());
            assertEquals(viaBinding.getViolations().size(), viaInline.getViolations().size(),
                    "answer " + answer);
        }
        assertEquals(1, RuleRunnerCalls.execute(inline, ts("CDISC", "2024-09-27"), _ -> null, null,
                answering(List.of("C9"))).getViolations().size(), "C1 is not in [C9]");
        // The SKIP half, unchanged: no provider, and a provider with nothing usable.
        assertEquals(RuleExecutionStatus.SKIPPED,
                RuleRunnerCalls.execute(inline, ts("CDISC", "2024-09-27")).getStatus());
        assertEquals(RuleExecutionStatus.SKIPPED, RuleRunnerCalls
                .execute(inline, ts("CDISC", "2099-01-01"), _ -> null, null, answering(List.of()))
                .getStatus());
    }


    /**
     * Review round 2, MED-1 / LOW-1: the INLINE spelling answers exactly what the binding spelling
     * answers on BOTH row orders — with the resolving row second as well as first. The loader's
     * injected gate {@code available(get_codelist_attributes(…))} is folded over one synthetic row;
     * before the fix the call answered for row 0 alone, so with a MedDRA row first the rule was
     * SKIPPED "precondition not met" and the provider was never asked. And it is computed ONCE per
     * execution: the gate and the Check share one provider round-trip.
     */
    @Test
    void theInlineCallAnswersAsItsBindingOnBothRowOrdersAndAsksOnce() throws Exception
    {
        String call = "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")";
        Rule inline = rule("prefix(TSVCDREF, 5) == \"CDISC\" and TSVALCD not in " + call,
                List.of());
        Rule bound = rule("prefix(TSVCDREF, 5) == \"CDISC\" and TSVALCD not in $V", List.of(), "$V",
                call);
        IDataTable medDraFirst = MockTable.of().name("TS").col("TSVCDREF", "MedDRA", "CDISC CT")
                .col("TSVCDVER", "", "2024-09-27").col("TSVALCD", "X", "C1").build();
        IDataTable cdiscFirst = MockTable.of().name("TS").col("TSVCDREF", "CDISC CT", "MedDRA")
                .col("TSVCDVER", "2024-09-27", "").col("TSVALCD", "C1", "X").build();
        for (IDataTable table : List.of(medDraFirst, cdiscFirst))
        {
            AtomicInteger askedInline = new AtomicInteger();
            RuleExecutionResult viaInline = RuleRunnerCalls.execute(inline, table, _ -> null, null,
                    counting("sdtmct-2024-09-27", List.of("C9"), askedInline));
            AtomicInteger askedBound = new AtomicInteger();
            RuleExecutionResult viaBinding = RuleRunnerCalls.execute(bound, table, _ -> null, null,
                    counting("sdtmct-2024-09-27", List.of("C9"), askedBound));
            assertEquals(RuleExecutionStatus.EXECUTED, viaInline.getStatus(),
                    viaInline.getStatusMessage());
            assertEquals(viaBinding.getStatus(), viaInline.getStatus());
            assertEquals(1, viaInline.getViolations().size(), "only the CDISC row fires");
            assertEquals(viaBinding.getViolations().get(0).getRow(),
                    viaInline.getViolations().get(0).getRow());
            assertEquals(1, askedInline.get(), "gate + Check share one provider round-trip");
            assertEquals(1, askedBound.get());
        }
    }


    /**
     * Review round 1, L3: the target argument derives the same Output_Variables in either spelling
     * — positional {@code (TSVCDREF, …)} and keyword {@code (name=TSVCDREF, …)}.
     */
    @Test
    void theTargetDerivesTheSameColumnsPositionallyAndByKeyword() throws Exception
    {
        Rule positional = rule("TSVALCD not in $V", List.of(), "$V",
                "get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute=\"Term CCODE\")");
        Rule keyword = rule("TSVALCD not in $V", List.of(), "$V",
                "get_codelist_attributes(name=TSVCDREF, version=TSVCDVER,"
                        + " ct_attribute=\"Term CCODE\")");
        assertNull(keyword.getLoadError(), keyword.getLoadError());
        assertEquals(List.of("TSVALCD", "TSVCDREF"), positional.getEffectiveOutputVariables());
        assertEquals(positional.getEffectiveOutputVariables(),
                keyword.getEffectiveOutputVariables());
    }


    /** A provider answering {@code answer} for {@code pkg} only, counting every question. */
    private static MetadataProvider counting(String pkg, List<String> answer, AtomicInteger asked)
    {
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getStandard()).thenReturn("sdtmig");
        lenient().when(p.getCodelistAttribute(anyString(), anyString())).thenAnswer(inv ->
        {
            asked.incrementAndGet();
            return pkg.equals(inv.getArgument(0)) ? answer : List.of();
        });
        return p;
    }


    /**
     * Review round 1, H1: ROW 0 resolves no CT package. The dataset-level leaf
     * {@code not empty($VALID_TERM_CODES)} is folded once over a single synthetic row
     * ({@code BroadcastFold}); the aggregate must still answer the union of EVERY row. Before the
     * fix the fold re-ran the function over row 0 alone, found no package and the rule ERRORed.
     */
    @Test
    void theAggregateAnswersEveryRowWhenRowZeroResolvesNoPackage() throws Exception
    {
        Rule rule = cg0288Shape();
        IDataTable table = MockTable.of().name("TS").col("TSVCDREF", "MedDRA", "CDISC CT")
                .col("TSVCDVER", "", "2024-09-27").col("TSVALCD", "X", "C1").build();
        AtomicInteger asked = new AtomicInteger();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, _ -> null, null,
                counting("sdtmct-2024-09-27", List.of("C9"), asked));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "only the CDISC row fires");
        assertEquals(1L, result.getViolations().get(0).getRow());
        assertEquals("[C9]", result.getViolations().get(0).getValues().get("$VALID_TERM_CODES"));
        assertEquals(1, asked.get(), "one package, asked once");
    }


    /**
     * Review round 1, M1: the dataset-level binding is computed ONCE per (rule × dataset) execution
     * — the eager SKIP arm, the dataset-level fold and the Check all read the one memo. Measured
     * before the fix: the provider was asked 2 + 1 + 2 = 5 times for two packages (the 25 000-code
     * union of CG0288 three times over).
     */
    @Test
    void theAggregateIsComputedOncePerExecution() throws Exception
    {
        IDataTable table = MockTable.of().name("TS").col("TSVCDREF", "CDISC CT", "MedDRA")
                .col("TSVCDVER", "2024-09-27", "26.0").col("TSVALCD", "C1", "X").build();
        AtomicInteger asked = new AtomicInteger();
        RuleExecutionResult result = RuleRunnerCalls.execute(cg0288Shape(), table, _ -> null, null,
                counting("sdtmct-2024-09-27", List.of("C9"), asked));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size());
        assertEquals(2, asked.get(), "two distinct packages, each asked exactly once");
    }


    @Test
    void theCg0288ShapeSkipsWithNoLibraryAndWithAnUnresolvedPackage() throws Exception
    {
        Rule rule = cg0288Shape();
        RuleExecutionResult none = RuleRunnerCalls.execute(rule, ts("CDISC", "2024-09-27"));
        assertEquals(RuleExecutionStatus.SKIPPED, none.getStatus());
        assertEquals("Rule skipped — no Library access", none.getStatusMessage());
        RuleExecutionResult unresolved = RuleRunnerCalls.execute(rule, ts("CDISC", "2099-01-01"),
                _ -> null, null, answering(List.of()));
        assertEquals(RuleExecutionStatus.SKIPPED, unresolved.getStatus(),
                "a package the store lacks answers nothing — SKIPPED, never a PASS");
        assertEquals("Rule skipped — library returned no data for get_codelist_attributes",
                unresolved.getStatusMessage());
        assertNotNull(unresolved.getStatusMessage());
    }

}
