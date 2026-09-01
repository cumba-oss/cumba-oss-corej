package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

class RulePackageLoaderEdgeCaseTest
{

    @Test
    void testLoadFromString_emptyRules() throws IOException
    {
        String json = """
                {
                  "rules": {}
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        assertNotNull(pkg);
        assertNotNull(pkg.getRules());
        assertTrue(pkg.getRules().isEmpty());
    }


    @Test
    void testLoadFromString_unknownPropertiesIgnored() throws IOException
    {
        String json = """
                {
                  "rules": {
                    "test-uuid": {
                      "id": "test-uuid",
                      "Core": {"Id": "CORE-TEST", "Status": "Draft", "Version": "1"},
                      "Sensitivity": "Record",
                      "unknown_field": "should be ignored",
                      "Check": {"name": "X", "operator": "var_exists"}
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        assertNotNull(pkg.getRules().get("test-uuid"));
        assertEquals("CORE-TEST", pkg.getRules().get("test-uuid").getCore().getId());
    }


    @Test
    void testLoadFromInputStream() throws IOException
    {
        String json = """
                {
                  "rules": {
                    "uuid1": {
                      "id": "uuid1",
                      "Core": {"Id": "CORE-001"},
                      "Check": {"name": "A", "operator": "var_exists"},
                      "Sensitivity": "Record"
                    }
                  }
                }
                """;
        ByteArrayInputStream bis = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        RulePackage pkg = RulePackageLoader.load(bis);
        assertNotNull(pkg);
        assertEquals(1, pkg.getRules().size());
    }


    @Test
    void testLoadFromString_invalidJson_throws()
    {
        assertThrows(IOException.class, () -> RulePackageLoader.loadFromString("{invalid}"));
    }


    @Test
    void testLoadFromString_nullRules() throws IOException
    {
        String json = """
                {
                  "rules": null
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        assertNotNull(pkg);
        assertNull(pkg.getRules());
    }


    @Test
    void toJsonRoundTripsTitleCaseKeys() throws IOException
    {
        String json = """
                {
                  "rules": {
                    "R1": {
                      "Core": {"Id": "CG0001"},
                      "Description": "desc",
                      "Check": {"name": "A", "operator": "var_exists"},
                      "Sensitivity": "Record"
                    }
                  }
                }
                """;
        net.cumba.cdisc.core.model.Rule rule = RulePackageLoader.loadFromString(json).getRules()
                .get("R1");

        String out = RulePackageLoader.toJson(rule);
        // Title-case @JsonProperty keys round-trip in the emitted JSON.
        assertTrue(out.contains("\"Core\""));
        assertTrue(out.contains("\"Description\""));
        assertTrue(out.contains("CG0001"));
        assertTrue(out.contains("desc"));

        // Re-loading the emitted rule object yields the same Core id.
        RulePackage reparsed = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + out + "}}");
        assertEquals("CG0001", reparsed.getRules().get("R1").getCore().getId());
    }

}
