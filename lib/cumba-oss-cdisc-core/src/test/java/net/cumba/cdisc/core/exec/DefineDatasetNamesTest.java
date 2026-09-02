package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider;
import net.cumba.cdisc.core.metadata.OdmDefineXMLProvider;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Direct dispatch coverage for the {@code define_dataset_names} operation (T2-residual). Mirrors
 * {@code define_variable_names}, but returns the whole-study set of {@code ItemGroupDef} names the
 * Define-XML declares (not domain-scoped). Define-dependent: {@code null} (unresolvable ⇒ rule
 * SKIP) when no Define provider is supplied.
 */
class DefineDatasetNamesTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static ODM parse(String resource) throws IOException
    {
        try (InputStream in = DefineDatasetNamesTest.class.getResourceAsStream(resource))
        {
            return new DefineXmlParser().parse(in);
        }
    }


    private static Operation makeOp()
    {
        Operation op = new Operation();
        op.setId("$define_dataset_names");
        op.setOperator("define_dataset_names");
        return op;
    }


    /** define-itemmeta-e2e.xml declares a single ItemGroupDef named DM. */
    @Test
    void returnsItemGroupDefNamesFromDefine() throws IOException
    {
        MetadataProvider define = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-itemmeta-e2e.xml")));
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();

        Object result = OperationExecutor.executeOne(makeOp(), table, NO_RESOLVER, null, Map.of(),
                null, null, define);
        assertEquals(List.of("DM"), result);
    }


    /** define-keys-e2e.xml declares a single ItemGroupDef named LB. */
    @Test
    void returnsItemGroupDefNamesFromLbDefine() throws IOException
    {
        MetadataProvider define = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-keys-e2e.xml")));
        IDataTable table = MockTable.of().name("LB").col("LBORRES", "40").build();

        Object result = OperationExecutor.executeOne(makeOp(), table, NO_RESOLVER, null, Map.of(),
                null, null, define);
        assertEquals(List.of("LB"), result);
    }


    /** No Define provider ⇒ null (unresolvable), so the caller SKIPs the rule (never PASS/FAIL). */
    @Test
    void nullWhenNoDefineProvider()
    {
        IDataTable table = MockTable.of().name("DM").col("AGE", "56").build();

        Object result = OperationExecutor.executeOne(makeOp(), table, NO_RESOLVER, null, Map.of(),
                null, null, null);
        assertNull(result);
    }
}
