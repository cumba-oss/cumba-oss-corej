package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dictionary-seeder} Phase 6a, D13 item 3 (owner-ruled) — a dictionary-dependent
 * operation ({@code valid_external_dictionary_*} / {@code dictionary_has_decode}) that names no
 * {@code external_dictionary_type} is a <b>load error</b>, not a SKIP: no install can ever satisfy
 * it, so it is an authoring defect on the same {@code loadError} channel as a dangling {@code $}
 * ({@link DanglingOperationReferenceLoadTest}), and the message must send the author to the rule —
 * never the operator to an installer.
 *
 * <p>
 * The whole shipped corpus is unaffected: all 98 {@code rules-src} dictionary rules (417 generated
 * package operations) name a type. This guard exists for site/custom rules — which is why the
 * negative controls below are the tests that carry the weight.
 * </p>
 */
class TypelessDictionaryOperationLoadTest
{

    private static final String DEFECTIVE = "declares no external_dictionary_type";

    private static Rule load(String ruleJson) throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"rule-1\":" + ruleJson + "}}");
        return pkg.getRules().values().iterator().next();
    }


    private static IDataTable table()
    {
        return MockTable.of().col("USUBJID", "S1").col("AEDECOD", "Headache").name("AE").build();
    }


    @Test
    void aDeclaredTypelessDictionaryOperationIsALoadError_andExecutesAsError() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-TDO-1"},
                  "Executability": "Fully Executable",
                  "Operations": [{"id": "$terms", "operator": "valid_external_dictionary_value",
                                  "name": "AEDECOD", "dictionary_term_type": "PT"}],
                  "Check": {"all": [{"name": "$terms", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError(), "a typeless dictionary operation must fail the load");
        assertTrue(rule.getLoadError().contains(DEFECTIVE), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$terms"), rule.getLoadError());
        // D13: the message blames the RULE. It must never send the operator to an installer.
        assertFalse(rule.getLoadError().contains("is not installed"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("installing dictionaries cannot help"),
                rule.getLoadError());

        RuleExecutionResult result = RuleRunner.execute(rule, table());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "ERROR through the loadError sentinel — not a SKIP, and never a silent pass");
        assertEquals(rule.getLoadError(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "exactly one sentinel violation");
    }


    /** The expression (Form B) declared shape is judged after normalisation, identically. */
    @Test
    void anExpressionFormTypelessOperationIsCaughtTheSameWay() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-TDO-2"},
                  "Executability": "Fully Executable",
                  "Operations": [{"id": "$terms", "expression":
                      "valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\"PT\\")"}],
                  "Check": {"all": [{"name": "$terms", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains(DEFECTIVE), rule.getLoadError());
    }


    /**
     * ⛔ The hole this guard closes: {@code injectInlineOperationGates} only emits the
     * {@code dictionary_available(type)} precondition for an inline dictionary operation whose type
     * is <b>non-null</b>, and the eager SKIP arm in {@code RuleRunner} only walks <b>declared</b>
     * operations — so a typeless <b>inline</b> dictionary operation used to load cleanly, get no
     * gate at all, and evaluate with no provider: every executor arm answered null, the null
     * broadcast, and the rule false-passed silently. Now it never loads.
     */
    @Test
    void anInlineTypelessDictionaryOperationIsALoadError_closingTheUngatedHole() throws IOException
    {
        Rule rule = load(
                """
                        {
                          "Core": {"Id": "TEST-TDO-3"},
                          "Executability": "Fully Executable",
                          "Check": {"expression":
                              "valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\"PT\\") == false"}
                        }
                        """);
        assertNull(rule.getInjectedPreconditionGates(),
                "the injector still gates only TYPED inline calls — without the load guard this "
                        + "rule would run wholly ungated");
        assertNotNull(rule.getLoadError(),
                "the load guard must catch what the gate injector cannot");
        assertTrue(rule.getLoadError().contains(DEFECTIVE), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("inline operation"), rule.getLoadError());

        RuleExecutionResult result = RuleRunner.execute(rule, table());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
    }


    /** Positive control — a typed operation on both surfaces loads clean and keeps its gate. */
    @Test
    void typedOperationsOnBothSurfacesLoadClean() throws IOException
    {
        Rule declared = load(
                """
                        {
                          "Core": {"Id": "TEST-TDO-4"},
                          "Executability": "Fully Executable",
                          "Operations": [{"id": "$terms", "expression":
                              "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\"meddra\\", dictionary_term_type=\\"PT\\")"}],
                          "Check": {"all": [{"name": "$terms", "operator": "non_empty"}]}
                        }
                        """);
        assertNull(declared.getLoadError(), declared.getLoadError());

        Rule inline = load(
                """
                        {
                          "Core": {"Id": "TEST-TDO-5"},
                          "Executability": "Fully Executable",
                          "Check": {"expression":
                              "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\"meddra\\", dictionary_term_type=\\"PT\\") == false"}
                        }
                        """);
        assertNull(inline.getLoadError(), inline.getLoadError());
        assertNotNull(inline.getInjectedPreconditionGates(),
                "a TYPED inline dictionary call still gets its dictionary_available gate");
    }


    /**
     * {@code dictionary_available} is excluded, mirroring the eager SKIP arm: it IS the gate, its
     * executor arm is total ({@code isAvailable(null)} is plain {@code false}, never a silent
     * null), and the loader's own injected gates are calls of it.
     */
    @Test
    void aTypelessDictionaryAvailableGateIsNotThisGuardsFinding() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-TDO-6"},
                  "Executability": "Fully Executable",
                  "Operations": [{"id": "$gate", "operator": "dictionary_available"}],
                  "Check": {"all": [{"name": "$gate", "operator": "non_empty"}]}
                }
                """);
        assertTrue(rule.getLoadError() == null || !rule.getLoadError().contains(DEFECTIVE),
                "dictionary_available answers totally (false), so it is not unanswerable: "
                        + rule.getLoadError());
    }
}
