package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.metadata.DefineMetadataListCodec;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 of {@code plans/done/PLAN-define-item-metadata-parity-929-1081.md}: end-to-end proof that
 * a <b>real define.xml</b> parsed to the ODM model ({@link DefineXmlParser}) feeds the engine's
 * direct-access define provider ({@link OdmDefineXMLProvider} &rarr;
 * {@link DefineXmlMetadataProvider}) with <b>no datatable {@code IMetadataLibrary}</b> in the
 * define path: roles, the codelist ccode and the coded codes are read straight from the parsed ODM.
 */
class DefineXmlDirectAccessE2ETest
{

    private static MetadataProvider define;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineXmlDirectAccessE2ETest.class
                .getResourceAsStream("/define/define-itemmeta-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm), null);
    }


    @Test
    void odmProvider_readsRolesAndCodelistFromRealDefineXml()
    {
        // Roles come straight from the ItemRef Role attribute of the parsed define.xml.
        assertEquals("Topic", define.getVariableMetadata("DM", "AGE").get("role"));
        assertEquals("Qualifier", define.getVariableMetadata("DM", "SEX").get("role"));

        // The codelist ccode (CodeList nci:ExtCodeID) and coded codes (CodeListItem Alias names)
        // are extracted directly from the ODM — the values CDISC-CG0001 needs, never surfaced via
        // the datatable model.
        Map<String, String> sex = define.getVariableMetadata("DM", "SEX");
        assertEquals("C66731", sex.get("ccode"));
        assertEquals(List.of("C20197", "C16576"),
                DefineMetadataListCodec.decode(sex.get("codelist_coded_codes")));
    }
}
