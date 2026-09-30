package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Direct dispatch coverage for the {@code define_dataset_names} operation (T2-residual). Mirrors
 * {@code define_variable_names}, but returns the whole-study set of {@code ItemGroupDef} names the
 * Define-XML declares (not domain-scoped). Define-dependent: {@code null} (unresolvable ⇒ rule
 * SKIP) when no Define provider is supplied.
 */
class DefineDatasetNamesTest
{

    private static ODM parse(String resource) throws IOException
    {
        try (InputStream in = DefineDatasetNamesTest.class.getResourceAsStream(resource))
        {
            return new DefineXmlParser().parse(in);
        }
    }


    /** {@code define_dataset_names()} — a registry function since wave 4 — over {@code table}. */
    private static Object run(IDataTable table, @Nullable MetadataProvider define)
    {
        return DefineLists.defineDatasetNames(
                net.cumba.corej.core.expr.eval.EvalRun.fullRange(
                        EvaluationContext.builder().table(table).defineProvider(define).build()),
                List.of()).value(0).resolved();
    }


    /** define-itemmeta-e2e.xml declares a single ItemGroupDef named DM. */
    @Test
    void returnsItemGroupDefNamesFromDefine() throws IOException
    {
        MetadataProvider define = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-itemmeta-e2e.xml")), null);
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();

        assertEquals(List.of("DM"), run(table, define));
    }


    /** define-keys-e2e.xml declares a single ItemGroupDef named LB. */
    @Test
    void returnsItemGroupDefNamesFromLbDefine() throws IOException
    {
        MetadataProvider define = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-keys-e2e.xml")), null);
        IDataTable table = MockTable.of().name("LB").col("LBORRES", "40").build();

        assertEquals(List.of("LB"), run(table, define));
    }


    /** No Define provider ⇒ null (unresolvable), so the caller SKIPs the rule (never PASS/FAIL). */
    @Test
    void unusableWhenNoDefineProvider()
    {
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();
        // The signal RuleRunner turns into SKIPPED (the second layer under its no-provider arm).
        org.junit.jupiter.api.Assertions.assertThrows(
                net.cumba.corej.core.expr.eval.UnusableProviderAnswerException.class,
                () -> run(table, null));
    }
}
