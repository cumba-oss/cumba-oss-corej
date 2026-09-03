package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of PLAN-coreJ-cdisc-provider: the {@code define} level. A
 * {@link MetadataLibraryProvider#forDefine(IMetadataLibrary)} provider returns exactly the
 * Define-XML's declared metadata with no CDISC Library product fallback, and the
 * {@link EvaluationContext} carries it in a dedicated {@code defineProvider} slot.
 */
class MetadataProviderForDefineTest
{

    private static IMetadataLibrary defineLib()
    {
        return lib("define").table(table("DM")
                .column(column("USUBJID", 0, DataValueType.STRING)
                        .label("Unique Subject Identifier").core("Req").role("Identifier")
                        .length(20).build())
                .column(column("AGE", 1, DataValueType.LONG).label("Age").core("Exp").build())
                .build()).build();
    }


    @Test
    void forDefine_echoesDefineColumnAttributes()
    {
        MetadataProvider define = MetadataLibraryProvider.forDefine(defineLib());

        Map<String, String> usubjid = define.getVariableMetadata("DM", "USUBJID");
        assertEquals("Unique Subject Identifier", usubjid.get("label"));
        assertEquals("Req", usubjid.get("core"));
        assertEquals("Identifier", usubjid.get("role"));
        assertEquals("Char", usubjid.get("simpleDatatype"));
        assertEquals("20", usubjid.get("length"));
        // AGE has no declared length (0) -> length is omitted, not "0".
        assertNull(define.getVariableMetadata("DM", "AGE").get("length"));

        // core==Req drives the required set; core==Exp adds to expected only.
        assertEquals(java.util.List.of("USUBJID"), define.getRequiredVariables("DM"));
        assertTrue(
                define.getExpectedVariables("DM").containsAll(java.util.List.of("USUBJID", "AGE")));
    }


    @Test
    void forDefine_hasNoLibraryProductFallback()
    {
        MetadataProvider define = MetadataLibraryProvider.forDefine(defineLib());
        // No product → class-hierarchy queries yield nothing (study-only provider).
        assertNull(define.getStandardModelVariables(null, null));
        // A domain the define does not declare returns empty, not an IG default.
        assertTrue(define.getVariableMetadata("LB", "LBORRES").isEmpty());
    }


    @Test
    void evaluationContext_carriesDefineProvider()
    {
        MetadataProvider define = MetadataLibraryProvider.forDefine(defineLib());
        EvaluationContext ctx = EvaluationContext.builder().defineProvider(define).build();
        assertSame(define, ctx.getDefineProvider());
        // Default is null when not set.
        assertNull(EvaluationContext.builder().build().getDefineProvider());
    }
}
