package net.cumba.cdisc.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fix #117/#118: (de)serialization contract of the new {@code Scope.Data_Structures} /
 * {@code Scope.Subclasses} fields — canonical house spelling on write, upstream-CORE alias accepted
 * on read, and lossless round-trip through the Rule model (the property the rules-legacy→rules
 * regeneration relies on).
 */
class ScopeStructureSerializationTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void canonicalSpelling_roundTrips() throws Exception
    {
        Scope scope = new Scope();
        DataStructureScope ds = new DataStructureScope();
        ds.setInclude(List.of("BASIC DATA STRUCTURE"));
        ds.setExclude(List.of("ADAM OTHER"));
        scope.setDataStructures(ds);
        SubclassScope sc = new SubclassScope();
        sc.setInclude(List.of("TIME-TO-EVENT"));
        scope.setSubclasses(sc);

        JsonNode node = JSON.valueToTree(scope);
        // Canonical house spelling on write — never the upstream alias.
        assertTrue(node.has("Data_Structures"));
        assertFalse(node.has("Data Structures"));
        assertTrue(node.has("Subclasses"));
        assertEquals("BASIC DATA STRUCTURE",
                node.get("Data_Structures").get("Include").get(0).asText());

        Scope back = JSON.treeToValue(node, Scope.class);
        assertNotNull(back.getDataStructures());
        assertEquals(List.of("BASIC DATA STRUCTURE"), back.getDataStructures().getInclude());
        assertEquals(List.of("ADAM OTHER"), back.getDataStructures().getExclude());
        assertNotNull(back.getSubclasses());
        assertEquals(List.of("TIME-TO-EVENT"), back.getSubclasses().getInclude());
    }


    @Test
    void upstreamSpaceSpelling_acceptedOnRead() throws Exception
    {
        // Upstream CORE YAML authors the field as "Data Structures" (with a space); the Python
        // engine normalizes spaces to underscores — the Java model accepts it via @JsonAlias.
        String yaml = """
                Classes:
                  Include:
                  - "BASIC DATA STRUCTURE"
                "Data Structures":
                  Include:
                  - "BASIC DATA STRUCTURE"
                Subclasses:
                  Include:
                  - "TIME-TO-EVENT"
                """;
        Scope scope = YAML.readValue(yaml, Scope.class);
        assertNotNull(scope.getDataStructures());
        assertEquals(List.of("BASIC DATA STRUCTURE"), scope.getDataStructures().getInclude());
        assertNotNull(scope.getSubclasses());
        assertEquals(List.of("TIME-TO-EVENT"), scope.getSubclasses().getInclude());
    }

}
