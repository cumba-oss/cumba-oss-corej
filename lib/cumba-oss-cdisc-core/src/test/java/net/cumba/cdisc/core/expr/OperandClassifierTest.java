package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperandClassifierTest
{

    private static OperandKind kind(String t)
    {
        return OperandClassifier.classify(t, -1);
    }


    @Test
    void plainColumn()
    {
        assertSame(OperandKind.COLUMN, kind("DTHFL"));
        assertSame(OperandKind.COLUMN, kind("USUBJID"));
        assertSame(OperandKind.COLUMN, kind("ARMCD"));
    }


    @Test
    void wildcardColumns()
    {
        assertSame(OperandKind.WILDCARD_COLUMN, kind("--STDTC"));
        assertSame(OperandKind.WILDCARD_COLUMN, kind("*DT"));
        assertSame(OperandKind.WILDCARD_COLUMN, kind("RELREC.**TERM"));
    }


    @Test
    void adamCaptureLetterIsWildcardNotBuiltin()
    {
        // Contains a lowercase capture letter but must be a wildcard column, not a built-in.
        assertSame(OperandKind.WILDCARD_COLUMN, kind("AyIND"));
        assertSame(OperandKind.WILDCARD_COLUMN, kind("ANLzzFN"));
        assertSame(OperandKind.WILDCARD_COLUMN, kind("TRTxxP"));
    }


    @Test
    void operationReference()
    {
        assertSame(OperandKind.OPERATION_REF, kind("$tv_visitnum"));
        assertSame(OperandKind.OPERATION_REF, kind("$ae_aeout"));
    }


    @Test
    void dottedReference()
    {
        assertSame(OperandKind.DOTTED_REF, kind("DM.DTHDTC"));
        assertSame(OperandKind.DOTTED_REF, kind("SE.SEENDTC"));
    }


    @Test
    void builtinReference()
    {
        assertSame(OperandKind.BUILTIN, kind("variable_name"));
        assertSame(OperandKind.BUILTIN, kind("variable_data_type"));
        assertSame(OperandKind.BUILTIN, kind("library_variable_role"));
        assertSame(OperandKind.BUILTIN, kind("dataset_name"));
    }


    @Test
    void unknownBuiltinShapeThrows()
    {
        // Lowercase-leading, not registered.
        assertThrows(ExpressionException.class, () -> kind("foo"));
        // Underscore-containing, not registered.
        assertThrows(ExpressionException.class, () -> kind("not_a_real_builtin"));
    }


    @Test
    void emptyOrNullThrows()
    {
        assertThrows(ExpressionException.class, () -> kind(""));
        assertThrows(ExpressionException.class, () -> kind(null));
    }


    @Test
    void exceptionCarriesPosition()
    {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> OperandClassifier.classify("mystery_token", 7));
        assertEquals(7, ex.getPosition());
        assertTrue(ex.getMessage().contains("mystery_token"));
    }

}
