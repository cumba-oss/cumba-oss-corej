package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.DefineMetadataListCodec;
import net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider;
import net.cumba.cdisc.core.metadata.OdmDefineXMLProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 of {@code plans/PLAN-define-item-metadata-parity-929-1081.md}: end-to-end proof that a
 * <b>real define.xml</b> parsed to the ODM model ({@link DefineXmlParser}) feeds the engine's
 * direct-access define provider ({@link OdmDefineXMLProvider} &rarr;
 * {@link DefineXmlMetadataProvider}) with <b>no datatable {@code IMetadataLibrary}</b> in the
 * define path. CORE-001081 then iterates the define ItemDefs and fires where the define role
 * differs from the library role.
 */
class DefineXmlDirectAccessE2ETest
{

    private static MetadataProvider define;

    private static Rule core1081;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineXmlDirectAccessE2ETest.class
                .getResourceAsStream("/define/define-itemmeta-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm));

        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-4.json"));
        core1081 = pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CORE-001081".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CORE-001081 not in package"));
    }


    @Test
    void odmProvider_readsRolesAndCodelistFromRealDefineXml()
    {
        // Roles come straight from the ItemRef Role attribute of the parsed define.xml.
        assertEquals("Topic", define.getVariableMetadata("DM", "AGE").get("role"));
        assertEquals("Qualifier", define.getVariableMetadata("DM", "SEX").get("role"));

        // The codelist ccode (CodeList nci:ExtCodeID) and coded codes (CodeListItem Alias names)
        // are extracted directly from the ODM — the values CORE-000929 needs, never surfaced via
        // the datatable model.
        Map<String, String> sex = define.getVariableMetadata("DM", "SEX");
        assertEquals("C66731", sex.get("ccode"));
        assertEquals(List.of("C20197", "C16576"),
                DefineMetadataListCodec.decode(sex.get("codelist_coded_codes")));
    }


    @Test
    void core1081_firesWhereDefineRoleDiffersFromLibraryRole()
    {
        // Library (IG) roles: AGE=Identifier (differs from define Topic -> fires),
        // SEX=Qualifier (matches define Qualifier -> no fire).
        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", Map.of("name", "AGE", "role", "Identifier"))
                .variable("DM", Map.of("name", "SEX", "role", "Qualifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").build();

        RuleExecutionResult r = RuleRunner.execute(core1081, dm, _ -> null, "DM", library, null,
                define);

        assertTrue(r.hasViolations(), "AGE define-role (Topic) != library-role (Identifier)");
        assertEquals("AGE", r.getViolations().get(0).getValues().get("define_variable_name"));
    }


    @Test
    void core1081_noViolationWhenAllRolesMatch()
    {
        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", Map.of("name", "AGE", "role", "Topic"))
                .variable("DM", Map.of("name", "SEX", "role", "Qualifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").build();

        RuleExecutionResult r = RuleRunner.execute(core1081, dm, _ -> null, "DM", library, null,
                define);
        assertFalse(r.hasViolations(), "define roles match library roles -> no finding");
    }
}
