package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.MetadataNormalizer.Normalization;
import org.junit.jupiter.api.Test;

/** Phase 1 — the metadata-accessor model: levels, the attribute support matrix, normalization. */
class MetadataModelTest
{

    // -- MetadataLevel ---------------------------------------------------------

    @Test
    void levelParsesCaseInsensitivelyWithWhitespace()
    {
        assertSame(MetadataLevel.DATA, MetadataLevel.parse("DATA"));
        assertSame(MetadataLevel.DEFINE, MetadataLevel.parse("define"));
        assertSame(MetadataLevel.LIBRARY, MetadataLevel.parse("  Library "));
    }


    @Test
    void levelRejectsNullAndUnknown()
    {
        assertThrows(RuleDefinitionException.class, () -> MetadataLevel.parse(null));
        assertThrows(RuleDefinitionException.class, () -> MetadataLevel.parse("FOO"));
        assertThrows(RuleDefinitionException.class, () -> MetadataLevel.parse(""));
    }

    // -- MetadataAttribute support matrix --------------------------------------


    @Test
    void fromFunctionResolvesKnownNames()
    {
        assertSame(MetadataAttribute.VAR_LABEL, MetadataAttribute.fromFunction("var_label"));
        assertSame(MetadataAttribute.VAR_MANDATORY,
                MetadataAttribute.fromFunction("var_mandatory"));
        assertSame(MetadataAttribute.DS_CLASS, MetadataAttribute.fromFunction("ds_class"));
        assertNull(MetadataAttribute.fromFunction("not_a_metadata_function"));
        assertNull(MetadataAttribute.fromFunction("lower"));
    }


    @Test
    void supportMatrixMatchesTheSpec()
    {
        // role / core / codelist have no DATA cell; mandatory is DEFINE-only; format has no
        // LIBRARY.
        assertFalse(MetadataAttribute.VAR_ROLE.supports(MetadataLevel.DATA));
        assertTrue(MetadataAttribute.VAR_ROLE.supports(MetadataLevel.DEFINE));
        assertTrue(MetadataAttribute.VAR_ROLE.supports(MetadataLevel.LIBRARY));

        assertFalse(MetadataAttribute.VAR_CORE.supports(MetadataLevel.DATA));
        assertTrue(MetadataAttribute.VAR_CORE.supports(MetadataLevel.DEFINE));

        assertFalse(MetadataAttribute.VAR_FORMAT.supports(MetadataLevel.LIBRARY));
        assertTrue(MetadataAttribute.VAR_FORMAT.supports(MetadataLevel.DATA));
        assertTrue(MetadataAttribute.VAR_FORMAT.supports(MetadataLevel.DEFINE));

        assertTrue(MetadataAttribute.VAR_MANDATORY.supports(MetadataLevel.DEFINE));
        assertFalse(MetadataAttribute.VAR_MANDATORY.supports(MetadataLevel.DATA));
        assertFalse(MetadataAttribute.VAR_MANDATORY.supports(MetadataLevel.LIBRARY));

        assertTrue(MetadataAttribute.VAR_NAME.supports(MetadataLevel.DATA));
        assertTrue(MetadataAttribute.VAR_ORDINAL.supports(MetadataLevel.DATA));

        assertFalse(MetadataAttribute.DS_STRUCTURE.supports(MetadataLevel.DATA));
        assertTrue(MetadataAttribute.DS_STRUCTURE.supports(MetadataLevel.DEFINE));
        assertEquals(MetadataAttribute.Scope.DATASET, MetadataAttribute.DS_NAME.scope());
        assertEquals(MetadataAttribute.Scope.VARIABLE, MetadataAttribute.VAR_NAME.scope());
        assertEquals("simpleDatatype", MetadataAttribute.VAR_TYPE.providerKey());
    }

    // -- normalization ---------------------------------------------------------


    @Test
    void typeNormalizesToCharOrNum()
    {
        assertEquals("Char", MetadataAttribute.VAR_TYPE.normalize("text"));
        assertEquals("Char", MetadataAttribute.VAR_TYPE.normalize("Char"));
        assertEquals("Char", MetadataAttribute.VAR_TYPE.normalize("datetime"));
        assertEquals("Char", MetadataAttribute.VAR_TYPE.normalize("partialDate"));
        assertEquals("Num", MetadataAttribute.VAR_TYPE.normalize("integer"));
        assertEquals("Num", MetadataAttribute.VAR_TYPE.normalize("float"));
        assertEquals("Num", MetadataAttribute.VAR_TYPE.normalize("Num"));
        // unknown vocabulary is surfaced, not silently mapped
        assertEquals("weird", MetadataAttribute.VAR_TYPE.normalize("weird"));
    }


    @Test
    void coreNormalizes()
    {
        assertEquals("Req", MetadataAttribute.VAR_CORE.normalize("required"));
        assertEquals("Exp", MetadataAttribute.VAR_CORE.normalize("EXP"));
        assertEquals("Perm", MetadataAttribute.VAR_CORE.normalize("Permissible"));
        assertEquals("xyz", MetadataAttribute.VAR_CORE.normalize("xyz"));
    }


    @Test
    void mandatoryNormalizes()
    {
        assertEquals("Yes", MetadataAttribute.VAR_MANDATORY.normalize("yes"));
        assertEquals("Yes", MetadataAttribute.VAR_MANDATORY.normalize("Y"));
        assertEquals("Yes", MetadataAttribute.VAR_MANDATORY.normalize("true"));
        assertEquals("No", MetadataAttribute.VAR_MANDATORY.normalize("N"));
        assertEquals("No", MetadataAttribute.VAR_MANDATORY.normalize("false"));
        assertEquals("maybe", MetadataAttribute.VAR_MANDATORY.normalize("maybe"));
    }


    @Test
    void roleTitleCases()
    {
        assertEquals("Identifier", MetadataAttribute.VAR_ROLE.normalize("identifier"));
        assertEquals("Record Qualifier",
                MetadataAttribute.VAR_ROLE.normalize("RECORD   qualifier"));
        assertEquals("Timing", MetadataAttribute.VAR_ROLE.normalize("  Timing  "));
    }


    @Test
    void rawAndNumericTrim()
    {
        assertEquals("Subject Age", MetadataAttribute.VAR_LABEL.normalize("  Subject Age  "));
        assertEquals("8", MetadataAttribute.VAR_LENGTH.normalize(" 8 "));
        assertEquals("2", MetadataAttribute.VAR_ORDINAL.normalize("2"));
    }


    @Test
    void emptyAndNullAreMissing()
    {
        assertNull(MetadataAttribute.VAR_LABEL.normalize(null));
        assertNull(MetadataAttribute.VAR_LABEL.normalize("   "));
        assertNull(MetadataAttribute.VAR_TYPE.normalize(""));
        assertNull(MetadataNormalizer.normalize(Normalization.RAW, null));
    }

}
