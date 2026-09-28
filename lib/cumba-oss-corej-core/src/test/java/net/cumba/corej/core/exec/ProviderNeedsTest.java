package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.convert.BindingRouting;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.RegistryTestSeam;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * The provider capability's ONE reader ({@code PLAN-binding-expressions} §5.2, M2) on the arms wave
 * 0 ships no function for — DEFINE, and DICTIONARY with its type argument (W1's D-W1-3 reuses
 * exactly this) — plus the routing predicate every reader shares and the operation-side
 * {@code $}-read census ({@link OperationExecutor#priorReferences}, I5).
 */
class ProviderNeedsTest
{

    private static FunctionDescriptor probe(String name, ProviderNeed need)
    {
        return new FunctionDescriptor(name,
                List.of(Parameter.required("x", ExprType.Unknown.UNKNOWN),
                        Parameter.optional("external_dictionary_type", Primitive.STRING)),
                FunctionKind.VALUE, (run, args) -> ConstVector.of("v")).withProvider(need);
    }


    private static ProviderNeeds needsOf(String expression)
    {
        return ProviderNeeds.ofExpr(CheckExpressionParser.parse(expression));
    }


    @Test
    void aDictionaryCallContributesItsStaticTypeInEitherSpelling()
    {
        try (var _ = RegistryTestSeam.register(
                probe("__w0_dict__", ProviderNeed.dictionary("external_dictionary_type"))))
        {
            ProviderNeeds keyword = needsOf(
                    "__w0_dict__(AETERM, external_dictionary_type=\"MedDRA\") == \"x\"");
            assertTrue(keyword.dictionary());
            assertFalse(keyword.library() || keyword.define());
            assertEquals(List.of("MedDRA"), List.copyOf(keyword.dictionaryTypes()));

            ProviderNeeds positional = needsOf("__w0_dict__(AETERM, \"WHODD\") == \"x\"");
            assertEquals(List.of("WHODD"), List.copyOf(positional.dictionaryTypes()));

            // A typeless call (no argument, or a non-literal one) still needs a dictionary; it
            // just names no type — the R8 load gate's business, not this reader's.
            ProviderNeeds typeless = needsOf("__w0_dict__(AETERM) == \"x\" or"
                    + " not (__w0_dict__(AETERM, external_dictionary_type=AEDECOD) == \"y\")");
            assertTrue(typeless.dictionary());
            assertTrue(typeless.dictionaryTypes().isEmpty(), typeless.toString());

            // An argument list the descriptor cannot bind names no type either.
            ProviderNeeds unbindable = needsOf("__w0_dict__(AETERM, \"A\", \"B\") == \"x\"");
            assertTrue(unbindable.dictionary());
            assertTrue(unbindable.dictionaryTypes().isEmpty());
        }
    }


    @Test
    void theUnionKeepsFirstSeenTypeOrderAndEveryKind()
    {
        try (var _ = RegistryTestSeam.register(
                probe("__w0_dict2__", ProviderNeed.dictionary("external_dictionary_type")));
                var _ = RegistryTestSeam.register(probe("__w0_def__", ProviderNeed.DEFINE)))
        {
            ProviderNeeds needs = needsOf(
                    "__w0_dict2__(X, \"B\") == \"1\" and __w0_def__(X) == \"2\""
                            + " and __w0_dict2__(Y, \"A\") == \"3\" and __w0_dict2__(Z, \"B\") == \"4\"");
            assertTrue(needs.define() && needs.dictionary());
            assertFalse(needs.library());
            assertEquals(List.of("B", "A"), List.copyOf(needs.dictionaryTypes()));
            assertFalse(needs.isEmpty());
        }
    }


    @Test
    void aCallNeedingNothingAndAnUnknownNameNeedNothing()
    {
        assertTrue(needsOf("upper(AETERM) == \"X\"").isEmpty());
        assertTrue(needsOf("__w0_no_such_function__(AETERM) == \"X\"").isEmpty());
        assertTrue(ProviderNeeds.NONE.union(ProviderNeeds.NONE).isEmpty());
    }


    @Test
    void aMalformedInlineOperationCallNeedsNothingItTheCompilerRejectsIt()
    {
        // Review round 1, T3: a LIBRARY-dependent operation, so "needs nothing" is a real answer —
        // the well-formed call needs the Library (the positive control), and the malformed one
        // (an unknown keyword the operation parser refuses) reports no need rather than guessing
        // one; the compiler raises the real error.
        assertTrue(needsOf("not domain_is_custom()").library(), "the positive control");
        assertTrue(needsOf("not domain_is_custom(bogus=1)").isEmpty());
    }


    @Test
    void onlyADictionaryNeedNamesATypeParameter()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderNeed(ProviderNeed.Kind.LIBRARY, "t"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderNeed(ProviderNeed.Kind.DICTIONARY, null));
    }


    @Test
    void routingKeepsASingleOperationCallAndUnparseableTextOnTheOperationPath()
    {
        assertTrue(BindingRouting.routesToOperation("record_count()"));
        assertTrue(BindingRouting.routesToOperation("((("), "unparseable stays an operation");
        assertTrue(BindingRouting.routesToOperation(null));
        assertTrue(BindingRouting.routesToOperation("  "));
        assertFalse(BindingRouting.routesToOperation("upper(AETERM)"));
        assertFalse(BindingRouting.routesToOperation("record_count() + 0"));
        assertNull(BindingRouting.tryParse("((("));
        assertNull(BindingRouting.tryParse(null));
    }


    @Test
    void priorReferencesReadEveryDollarNameTheExecutorConsults()
    {
        Operation op = new Operation();
        op.setGroup(List.of("USUBJID", "$g"));
        op.setName("$n");
        op.setSubtract("$s");
        op.setNameExpr(CheckExpressionParser.parse("concat($a, \"x\") == \"y\" and not ($c > 1"
                + " or $d < 2) and upper($e) in [\"A\", \"B\"]"));
        assertEquals(List.of("$g", "$n", "$s", "$a", "$c", "$d", "$e"),
                List.copyOf(OperationExecutor.priorReferences(op)));
        assertTrue(OperationExecutor.priorReferences(new Operation()).isEmpty());
    }

}
