package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import org.junit.jupiter.api.Test;

class FunctionRegistryTest
{

    @Test
    void serviceLoaderDiscoversBuiltins()
    {
        assertTrue(FunctionRegistry.isRegistered("lower"));
        assertTrue(FunctionRegistry.isRegistered("contains"));
        assertTrue(FunctionRegistry.isRegistered("is_valid_date"));
        assertNotNull(FunctionRegistry.resolve("non_empty"));
        assertEquals(FunctionKind.VALUE, FunctionRegistry.descriptor("len").kind());
        assertEquals(FunctionKind.BOOLEAN, FunctionRegistry.descriptor("contains").kind());
    }


    @Test
    void unknownNameThrows()
    {
        assertThrows(ExpressionException.class, () -> FunctionRegistry.resolve("no_such_fn"));
        assertNull(FunctionRegistry.descriptor("no_such_fn"));
    }


    /**
     * Phase 6b (D19a): one descriptor per name. What used to be an arity overload is an optional
     * parameter; the {@code (name, arity)} key and the per-arity registrations are gone.
     */
    @Test
    void oneDescriptorPerName()
    {
        FunctionDescriptor substring = FunctionRegistry.descriptor("substring");
        assertNotNull(substring);
        assertEquals(2, substring.minArity());
        assertEquals(3, substring.maxArity());
        // the arity probe honours the parameter bounds
        assertNotNull(FunctionRegistry.descriptorAccepting("substring", 2));
        assertNotNull(FunctionRegistry.descriptorAccepting("substring", 3));
        assertNull(FunctionRegistry.descriptorAccepting("substring", 4));
        assertNull(FunctionRegistry.descriptorAccepting("no_such_fn", 1));
    }


    @Test
    void programmaticRegistrationByName()
    {
        EvalFunction one = (_, _) -> new BitSet();
        FunctionRegistry.register(new FunctionDescriptor("xtest",
                List.of(Parameter.required("x", Unknown.UNKNOWN)), FunctionKind.BOOLEAN, one));
        try
        {
            assertSame(one, FunctionRegistry.resolve("xtest"));
        }
        finally
        {
            FunctionRegistry.unregister("xtest");
        }
        assertFalse(FunctionRegistry.isRegistered("xtest"));
    }


    @Test
    void allSnapshotIsSortedAndNonEmpty()
    {
        List<FunctionDescriptor> all = FunctionRegistry.all();
        assertTrue(all.size() >= 20, "built-ins registered");
        for (int i = 1; i < all.size(); i++)
        {
            assertTrue(all.get(i - 1).name().compareTo(all.get(i).name()) < 0,
                    "sorted by name, one descriptor per name");
        }
    }

}
