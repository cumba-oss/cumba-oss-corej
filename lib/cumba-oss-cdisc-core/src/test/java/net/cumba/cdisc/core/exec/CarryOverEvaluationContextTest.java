package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider.PublishedVariable;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.ast.Expr.BinOp;
import org.junit.jupiter.api.Test;

/**
 * <b>Plan 2, R11 / Phase 6</b> — the two pieces that put the carry-over operands where the
 * <em>verdict</em> can see them.
 *
 * <p>
 * ⛔ The defect these pin: {@code putCarryOverCandidates} was called only from
 * {@code buildVariableMetadata}, which runs <b>after</b> a check has already fired (it builds the
 * finding). The operands were therefore constructed too late to influence anything, and
 * {@code DRAFT-900044} evaluated {@code library_variable_label_values} as absent on every column.
 * The per-column loop now populates them into the evaluation context before
 * {@code evaluateBroadcast}, gated on {@link RuleRunner#referencesCarryOverOperand} so no other
 * metadata rule pays for a library round-trip per column.
 * </p>
 */
class CarryOverEvaluationContextTest
{

    private static final String LABEL_VALUES = "library_variable_label_values";

    private static final String TYPE_VALUES = "library_variable_data_type_values";

    private static Expr operand()
    {
        return new Expr.Ref(LABEL_VALUES, OperandKind.BUILTIN);
    }


    private static Expr other()
    {
        return new Expr.Ref("variable_label", OperandKind.BUILTIN);
    }


    /**
     * A provider that publishes {@code occurrences} under any name, and answers
     * {@code getVariableMetadata} with {@code own} — the run's OWN definition of the variable.
     */
    private static MetadataProvider provider(Map<String, String> own,
            PublishedVariable... occurrences)
    {
        MetadataProvider p = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito
                .when(p.getPublishedVariablesByName(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(occurrences));
        org.mockito.Mockito.when(p.getVariableMetadata(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(own);
        return p;
    }


    /**
     * ⭐ The carry-over case: the run's own library has no definition for {@code RFSTDTC} on
     * {@code ADSL}, so the name-keyed companion lookup runs and publishes the operands into the map
     * the verdict reads.
     */
    @Test
    void undefinedVariablePublishesTheCandidatesIntoTheEvaluationMap()
    {
        Map<String, Object> perColVars = new LinkedHashMap<>();

        RuleRunner.putCarryOverIfUndefined(perColVars,
                provider(Map.of(),
                        new PublishedVariable("DM", "Subject Reference Start Date/Time", "Char")),
                null, "ADSL", "RFSTDTC");

        // Labels are published case-folded (R-4); types are not.
        assertEquals(List.of("SUBJECT REFERENCE START DATE/TIME"), perColVars.get(LABEL_VALUES));
        assertEquals(List.of("Char"), perColVars.get(TYPE_VALUES));
    }


    /**
     * ⛔⛔ <b>The discriminator, and the reason the gate is not an optimisation.</b> A variable the
     * run's OWN standard defines is not carried over — its own definition governs, and the
     * name-keyed companion lane must not run at all. Without this gate every ADaM-native variable
     * that happens to share an SDTM name would be judged against the SDTM label.
     */
    @Test
    void aVariableTheOwnLibraryDefinesIsNotACarryOver()
    {
        Map<String, Object> perColVars = new LinkedHashMap<>();

        RuleRunner.putCarryOverIfUndefined(perColVars,
                provider(Map.of("label", "Reference Start Date", "simpleDatatype", "Char"),
                        new PublishedVariable("DM", "Subject Reference Start Date/Time", "Char")),
                null, "ADSL", "RFSTDTC");

        assertTrue(perColVars.isEmpty(),
                "the companion lane must not be consulted for a variable ADaM itself defines");
    }


    /** A name published nowhere emits no operand, so the rule takes its not-applicable row. */
    @Test
    void aNamePublishedNowhereLeavesTheEvaluationMapUntouched()
    {
        Map<String, Object> perColVars = new LinkedHashMap<>();

        RuleRunner.putCarryOverIfUndefined(perColVars, provider(Map.of()), null, "ADSL", "PARAMCD");

        assertTrue(perColVars.isEmpty());
    }


    /** The bare operand is recognised on its own. */
    @Test
    void aBareOperandIsRecognised()
    {
        assertTrue(RuleRunner.referencesCarryOverOperand(operand()));
        assertTrue(RuleRunner
                .referencesCarryOverOperand(new Expr.Ref(TYPE_VALUES, OperandKind.BUILTIN)));
    }


    /**
     * ⭐ The shape {@code DRAFT-900044} actually authors — {@code not empty(operand) and … and trim(
     * var_label("DATA")) not in operand} — reaches the operand through And, Not, Call and Binary. A
     * walker missing any one of those arms leaves the gate false and the rule silent again.
     */
    @Test
    void theOperandIsFoundThroughEveryNestingTheRuleUses()
    {
        assertTrue(RuleRunner.referencesCarryOverOperand(new Expr.And(List
                .of(new Expr.Not(new Expr.Call("empty", List.of(operand()), Map.of())), other()))),
                "And + Not + Call args");
        assertTrue(RuleRunner.referencesCarryOverOperand(
                new Expr.Binary(BinOp.NOT_IN, other(), operand())), "Binary right");
        assertTrue(RuleRunner.referencesCarryOverOperand(
                new Expr.Binary(BinOp.NOT_IN, operand(), other())), "Binary left");
        assertTrue(RuleRunner.referencesCarryOverOperand(new Expr.Or(List.of(other(), operand()))),
                "Or");
        assertTrue(RuleRunner.referencesCarryOverOperand(
                new Expr.Call("f", List.of(), Map.of("k", operand()))), "Call kwargs");
        assertTrue(
                RuleRunner.referencesCarryOverOperand(
                        new Expr.Lit(Expr.LitKind.LIST, List.of(other(), operand()))),
                "list literal member");
    }


    /**
     * ⚑ The negative half of the gate: an expression that never names a carry-over operand must not
     * pay for a per-column library round-trip. A walker that always answered true would leave this
     * silent, so it is pinned separately from the positive cases.
     */
    @Test
    void anExpressionThatNamesNoCarryOverOperandIsNotGated()
    {
        assertFalse(RuleRunner.referencesCarryOverOperand(other()));
        assertFalse(
                RuleRunner.referencesCarryOverOperand(new Expr.And(List.of(
                        new Expr.Not(new Expr.Call("empty", List.of(other()), Map.of())),
                        new Expr.Binary(BinOp.EQ, other(), new Expr.Lit(Expr.LitKind.STRING, "x")),
                        new Expr.Or(List.of(other(),
                                new Expr.Call("g", List.of(), Map.of("k", other()))))))),
                "a whole tree of non-carry-over references");
        assertFalse(
                RuleRunner.referencesCarryOverOperand(
                        new Expr.Lit(Expr.LitKind.STRING, LABEL_VALUES)),
                "a string LITERAL that spells the operand is not a reference to it");
    }
}
