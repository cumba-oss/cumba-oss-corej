package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import org.junit.jupiter.api.Test;

/**
 * Phase 6b (D16/D17/D19a) — the per-operation descriptor table and the two gates it feeds: the
 * {@code fromCall} keyword binding and the field-form {@code validateAgainstDescriptor} twin.
 */
class OperationDescriptorsTest
{

    private static Expr.Call parse(String expression)
    {
        return (Expr.Call) CheckExpressionParser.parse(expression);
    }


    @Test
    void everyOperationTypeHasExactlyOneDescriptorNamedByItsJsonValue()
    {
        for (OperationType type : OperationType.values())
        {
            FunctionDescriptor d = OperationDescriptors.of(type);
            assertNotNull(d, type.name());
            assertEquals(type.getJsonValue(), d.name());
            assertNotNull(OperationDescriptors.byName(type.getJsonValue()));
        }
        assertNull(OperationDescriptors.byName("no_such_operation"));
    }


    /**
     * The descriptor declarations and the shipped value-validators' operator allowlists are the
     * same sets — the validators keep their VALUE checks, the descriptors own parameter legality,
     * and this pin is what keeps the two authorities from drifting apart.
     */
    @Test
    void descriptorDeclarationsMatchTheValidatorAllowlists()
    {
        assertEquals(
                names("min_date", "max_date", "max", "distinct", "record_count",
                        "has_mixed_emptiness_within_group", "is_last_in_group"),
                declaring("keep_missings"), "validateKeepMissings' seven grouped operations");
        assertEquals(names("min_date", "max_date", "date_diff_days"), declaring("missing_values"),
                "validateMissingValues' three consumers");
        assertEquals(
                names("get_model_filtered_variables", "get_dataset_filtered_variables",
                        "ts_parameter_value"),
                declaring("key_name"), "validateKeyName's three readers");
        assertEquals(names("get_model_filtered_variables"), declaring("model_class"),
                "validateModelClass' single consumer");
    }


    private static Set<String> declaring(String parameter)
    {
        Set<String> out = new TreeSet<>();
        for (OperationType type : OperationType.values())
        {
            if (OperationDescriptors.of(type).parameter(parameter) != null)
            {
                out.add(type.getJsonValue());
            }
        }
        return out;
    }


    private static Set<String> names(String... n)
    {
        return new TreeSet<>(List.of(n));
    }


    @Test
    void unknownKeywordOnAnOperationErrorsNamingTheParameterList()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.fromCall(parse("record_count(delimiter=\"|\")"),
                        "$x"));
        assertTrue(ex.getMessage().contains("no parameter `delimiter`"), ex.getMessage());
        assertTrue(ex.getMessage().contains("filter"), "message names the parameters");
    }


    /**
     * The four validator-owned parameters keep their shipped, operator-naming messages: the generic
     * gate defers to {@code validateMissingValues} / {@code validateKeepMissings} /
     * {@code validateModelClass} / {@code validateKeyName}.
     */
    @Test
    void validatorOwnedParametersKeepTheirShippedMessages()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser
                        .fromCall(parse("record_count(missing_values=\"skip\")"), "$x"));
        assertTrue(ex.getMessage().contains("not supported by operation"), ex.getMessage());
    }


    /** D19a's second error, on the operation surface. */
    @Test
    void rebindingThePositionalTargetByNameIsItsOwnError()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser
                        .fromCall(parse("distinct(VISIT, name=\"VISITNUM\")"), "$x"));
        assertTrue(ex.getMessage().contains("already bound by the positional target"),
                ex.getMessage());
    }


    @Test
    void declaredKeywordsStillBind()
    {
        Operation op = OperationExpressionParser.fromCall(
                parse("record_count(group=[USUBJID], filter=filter(TSPARMCD=\"TRT\"))"), "$n");
        assertEquals(List.of("USUBJID"), op.getGroup());
        assertEquals(Map.of("TSPARMCD", "TRT"), op.getFilter());
    }


    /** The field-form twin: Jackson binds anything, the descriptor gate rejects it. */
    @Test
    void fieldFormOperationWithAnUndeclaredParameterErrors()
    {
        Operation op = new Operation();
        op.setOperator("record_count");
        op.setDelimiter("|");
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.validateAgainstDescriptor(op));
        assertTrue(ex.getMessage().contains("silently dropped"), ex.getMessage());

        Operation ok = new Operation();
        ok.setOperator("record_count");
        ok.setGroup(List.of("USUBJID"));
        OperationExpressionParser.validateAgainstDescriptor(ok); // no throw
        OperationExpressionParser.validateAgainstDescriptor(new Operation()); // operator-less:
                                                                              // loader's channel
    }

}
