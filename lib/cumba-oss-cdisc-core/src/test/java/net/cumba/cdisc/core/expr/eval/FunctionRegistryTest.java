package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.cdisc.core.expr.ExpressionException;
import org.junit.jupiter.api.Test;

class FunctionRegistryTest
{

    @Test
    void serviceLoaderDiscoversBuiltins()
    {
        assertTrue(FunctionRegistry.isRegistered("lower", 1));
        assertTrue(FunctionRegistry.isRegistered("contains", 2));
        assertTrue(FunctionRegistry.isRegistered("is_valid_date", 1));
        assertNotNull(FunctionRegistry.resolve("non_empty", 1));
        assertEquals(FunctionKind.VALUE, FunctionRegistry.descriptor("len", 1).kind());
        assertEquals(FunctionKind.BOOLEAN, FunctionRegistry.descriptor("contains", 2).kind());
    }


    @Test
    void unknownNameOrArityThrows()
    {
        assertThrows(ExpressionException.class, () -> FunctionRegistry.resolve("no_such_fn", 1));
        // contains exists only at arity 2
        assertThrows(ExpressionException.class, () -> FunctionRegistry.resolve("contains", 1));
    }


    @Test
    void programmaticRegistrationAndOverloadByArity()
    {
        EvalFunction one = (_, _) -> new BitSet();
        EvalFunction two = (_, _) -> new BitSet();
        FunctionRegistry.register(new FunctionDescriptor("xtest", 1, FunctionKind.BOOLEAN, one));
        FunctionRegistry.register(new FunctionDescriptor("xtest", 2, FunctionKind.BOOLEAN, two));
        try
        {
            assertSame(one, FunctionRegistry.resolve("xtest", 1));
            assertSame(two, FunctionRegistry.resolve("xtest", 2));
            assertThrows(ExpressionException.class, () -> FunctionRegistry.resolve("xtest", 3));
        }
        finally
        {
            FunctionRegistry.unregister("xtest", 1);
            FunctionRegistry.unregister("xtest", 2);
        }
        assertFalse(FunctionRegistry.isRegistered("xtest", 1));
    }


    @Test
    void allSnapshotIsSortedAndNonEmpty()
    {
        List<FunctionDescriptor> all = FunctionRegistry.all();
        assertTrue(all.size() >= 20, "built-ins registered");
        for (int i = 1; i < all.size(); i++)
        {
            FunctionDescriptor prev = all.get(i - 1);
            FunctionDescriptor cur = all.get(i);
            int byName = prev.name().compareTo(cur.name());
            assertTrue(byName < 0 || (byName == 0 && prev.arity() <= cur.arity()),
                    "sorted by (name, arity)");
        }
    }

}
