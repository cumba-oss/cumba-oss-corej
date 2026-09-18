package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * The loader-injected availability gate for hand-authored native rules
 * ({@code PLAN-classifier-redesign} Phase-0 filed hazard, implemented 2026-07-30): a Check that
 * inlines a library/define/dictionary-dependent operation call without the
 * {@code library_available() and available(<op>)} Precondition silently PASSes (null broadcast, no
 * row fires) where the legacy declaration-keyed path reports SKIPPED. These tests pin the injection
 * shapes against {@code OperationInliner}'s corpus-baked gates, the emptiness exemption,
 * term-presence idempotence, and the unraisable-Precondition bail-out.
 */
class InjectInlineOperationGatesTest
{

    /** Loads a one-rule package through the production loader and returns the rule. */
    private static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    private static String preconditionText(Rule rule)
    {
        return rule.getPrecondition() instanceof CheckConditionExpression expression
                ? expression.source()
                : String.valueOf(rule.getPrecondition());
    }


    @Test
    void anUngatedLibraryCallGetsTheInlinerGateShape()
    {
        // The CDISC-SEND-0016 shape hand-authored WITHOUT the gate the inliner would have baked.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"domain_is_custom() == false\"}}");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getInjectedPreconditionGates());
        assertEquals("library_available() and available(domain_is_custom())",
                preconditionText(rule));
        assertNotNull(rule.getPreconditionExpr(),
                "the injected gate must raise to a broadcast preconditionExpr, or the rule"
                        + " cannot skip natively");
    }


    @Test
    void theShippedGatedFormIsLeftUntouched()
    {
        // Exactly what the inliner ships (CDISC-SEND-0016): no injection, no duplicate terms.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"domain_is_custom() == false\"},"
                + "\"Precondition\":{\"expression\":"
                + "\"library_available() and available(domain_is_custom())\"}}");
        assertNull(rule.getInjectedPreconditionGates());
        assertEquals("library_available() and available(domain_is_custom())",
                preconditionText(rule));
    }


    @Test
    void anEmptinessOnlyCheckGetsNoAvailableTerm()
    {
        // The inliner's exemption: available(<op>) would make an emptiness-testing rule
        // unreachable; library_available() alone still restores the provider-absent SKIP.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"empty(dataset_class_from_library())\"}}");
        assertEquals("library_available()", preconditionText(rule));
        assertEquals("library_available()", rule.getInjectedPreconditionGates());
    }


    @Test
    void aDictionaryCallGetsItsTypedGate()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\\"PT\\\","
                + " external_dictionary_type=\\\"meddra\\\") == false\"}}");
        assertNotNull(rule.getInjectedPreconditionGates());
        assertEquals("dictionary_available(\"meddra\")", preconditionText(rule));
    }


    @Test
    void aNonDependentCallNeedsNoGate()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"record_count() == 0\"}}");
        assertNull(rule.getInjectedPreconditionGates());
        assertNull(rule.getPrecondition());
    }


    /**
     * ⚠⚠ <b>This arm is UNREACHABLE from the loader since gate R8, and is labelled rather than
     * deleted.</b> Owner ruling Q3 ({@code plans/done/PLAN-scope-requirements-split.md} &#167;4.2)
     * made an authored {@code Precondition} a load error unless it is an availability gate, so no
     * cleanly-loading rule can arrive at {@code injectInlineOperationGates} carrying a
     * <em>foreign</em> precondition — the composition branch cannot fire through {@code finishLoad}
     * any more, for any provenance.
     *
     * <p>
     * The branch is kept because the tier is not: {@code Precondition} is still written by four
     * engine writers and evaluated at {@code RuleRunner} phase 2e, so a rule can still acquire a
     * foreign term through {@link RulePackageLoader#installEngineInternalPrecondition} and then be
     * re-gated. This test therefore drives the pass <b>directly</b> instead of through the loader,
     * which is an honest statement of what it now proves: the composition works; no shipped rule
     * exercises it. Deleting the test while keeping the branch would leave green tests over code
     * nothing reaches — the outcome to avoid — and deleting the branch is a semantic change ruling
     * Q3 did not authorise.
     * </p>
     */
    @Test
    void anExistingForeignPreconditionIsAndComposed() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"domain_is_custom() == false\"}}");
        assertNotNull(rule.getInjectedPreconditionGates(),
                "control: the inlined library call is gated on the normal load path");
        RulePackageLoader.installEngineInternalPrecondition(rule,
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        "{\"expression\":\"var_exists(\\\"DOMAIN\\\")\"}",
                        net.cumba.corej.core.model.CheckCondition.class));
        rule.setInjectedPreconditionGates(null);
        RulePackageLoader.injectInlineOperationGates(rule);
        assertNotNull(rule.getInjectedPreconditionGates());
        String text = preconditionText(rule);
        assertTrue(text.contains("library_available()")
                && text.contains("available(domain_is_custom())")
                && text.contains("var_exists(\"DOMAIN\")"), text);
    }


    @Test
    void aKwargNestedEmptinessOnlyCallGetsTheExemptionToo()
    {
        // F-corej-L2-04: collectGateTerms walks args AND kwargs, but walkEmptinessUse walked args
        // only, so an operation call reached only through a kwarg could never earn the emptiness
        // exemption: the gate collector found it, the suppression walker did not, and
        // available(<op>) was injected -- which per the exemption's own javadoc makes an
        // emptiness-testing rule unreachable. The two walkers must see the same tree.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"}," + "\"Check\":{\"expression\":"
                + "\"wrapper_fn(k = empty(dataset_class_from_library()))\"}}");
        assertNull(rule.getLoadError());
        assertEquals("library_available()", preconditionText(rule),
                "the emptiness exemption must apply through a kwarg exactly as through an arg");
        assertEquals("library_available()", rule.getInjectedPreconditionGates());
    }


    @Test
    void anUnraisableWeakerLevelNeitherDiscardsStricterGatesNorStaysSilent()
    {
        // F-corej-L2-05 (multi-level half): terms are collected "from every declared level"
        // (Plan C 3.3), so an unraisable WARNING level must not throw away the gate the ERROR
        // level already needs -- and, like the unraisable-Precondition twin below, it must say
        // what it could not do instead of silently skipping.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"}," + "\"Check\":{"
                + "\"ERROR\":{\"expression\":\"domain_is_custom() == false\"},"
                + "\"WARNING\":{\"name\":\"AETERM\",\"operator\":\"has_no_expression_surface_op\",\"value\":\"x\"}}}");
        assertNull(rule.getLoadError());
        assertEquals("library_available() and available(domain_is_custom())",
                preconditionText(rule),
                "the raisable ERROR level's gate must survive the unraisable WARNING level");
        assertNotNull(rule.getInjectedPreconditionGates());
        assertNotNull(rule.getLoadWarning(),
                "an unraisable level must warn like the unraisable-Precondition twin");
        assertTrue(rule.getLoadWarning().contains("cannot be raised"), rule.getLoadWarning());
    }


    @Test
    void anUnraisableOnlyLevelWarnsInsteadOfSilentlySkipping()
    {
        // F-corej-L2-05 (single-level half): the silent bare `return` also covered the case where
        // the ONLY declared level cannot be raised. Nothing can be gated then, but the exit must
        // say so rather than leave the rule indistinguishable from one needing no gate.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"}," + "\"Check\":{\"name\":\"AETERM\","
                + "\"operator\":\"has_no_expression_surface_op\",\"value\":\"x\"}}");
        assertNull(rule.getLoadError());
        assertNull(rule.getInjectedPreconditionGates(), "nothing raisable, nothing to inject");
        assertNotNull(rule.getLoadWarning(),
                "an unraisable level must warn like the unraisable-Precondition twin");
    }


    @Test
    void declaredOperationsStayWithTheEagerGatesNotThisOne()
    {
        // A $-ref goes through RuleRunner's declaration-keyed SKIP gates — no injection here.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Operations\":[{\"id\":\"$codes\",\"operator\":\"codelist_terms\","
                + "\"codelists\":[\"SDOMAIN\"],\"level\":\"term\",\"returntype\":\"value\"}],"
                + "\"Check\":{\"expression\":\"DOMAIN not in $codes\"}}");
        assertNull(rule.getInjectedPreconditionGates());
        assertNull(rule.getPrecondition());
    }
}
