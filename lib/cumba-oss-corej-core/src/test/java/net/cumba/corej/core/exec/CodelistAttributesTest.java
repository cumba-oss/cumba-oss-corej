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
