package net.cumba.cdisc.define.conformance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.cumba.cdisc.define.conformance.report.Category;
import net.cumba.cdisc.define.conformance.report.ConformanceFinding;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.report.RuleExecution;
import net.cumba.cdisc.define.conformance.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonReportWriterTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DefineConformanceReport sampleReport()
    {
        ConformanceFinding schemaFinding = ConformanceFinding.builder().ruleId("DEFINE-XML-0004")
                .element("ItemDef").xpath("/ODM/Study/MetaDataVersion/ItemDef[@OID='IT.AE']")
                .message("missing description").category(Category.SCHEMA).severity(Severity.ERROR)
                .build();
        ConformanceFinding xsdFinding = ConformanceFinding.builder().ruleId("PMDA-DD0004")
                .message("attribute not allowed").line(26).column(12).category(Category.XSD)
                .severity(Severity.WARNING).build();
        return new DefineConformanceReport("/tmp/define.xml", "2.1",
                Instant.parse("2026-07-03T12:00:00Z"), List.of(schemaFinding, xsdFinding),
                List.of(new RuleExecution("DEFINE-XML-0004", ExecutionStatus.EXECUTED, 1),
                        new RuleExecution("DEFINE-XML-0065", ExecutionStatus.SKIPPED_MISSING_CT,
                                0)));
    }


    @Test
    void writesStableTopLevelStructure(@TempDir Path dir) throws IOException
    {
        Path out = dir.resolve("report.json");
        JsonReportWriter.write(sampleReport(), out);
        assertTrue(Files.exists(out));

        JsonNode root = MAPPER.readTree(out.toFile());
        assertEquals("/tmp/define.xml", root.get("defineXml").asText());
        assertEquals("2.1", root.get("defineVersion").asText());
        assertEquals("2026-07-03T12:00:00Z", root.get("generatedAt").asText());

        JsonNode summary = root.get("summary");
        assertEquals(2, summary.get("totalFindings").asInt());
        assertEquals(1, summary.get("findingsByCategory").get("SCHEMA").get("ERROR").asInt());
        assertEquals(1, summary.get("findingsByCategory").get("XSD").get("WARNING").asInt());
        assertEquals(1, summary.get("executionsByStatus").get("EXECUTED").asInt());
        assertEquals(1, summary.get("executionsByStatus").get("SKIPPED_MISSING_CT").asInt());

        assertEquals(2, root.get("findings").size());
        assertEquals(2, root.get("ruleExecutions").size());
    }


    @Test
    void omitsNullOptionalFieldsAndKeepsPresentOnes()
    {
        JsonNode root;
        try
        {
            root = MAPPER.readTree(JsonReportWriter.toJson(sampleReport()));
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }

        JsonNode schema = root.get("findings").get(0);
        assertTrue(schema.has("element"));
        assertTrue(schema.has("xpath"));
        assertFalse(schema.has("line"), "SCHEMA finding has no line");
        assertFalse(schema.has("column"));

        JsonNode xsd = root.get("findings").get(1);
        assertFalse(xsd.has("element"), "XSD finding has no element");
        assertFalse(xsd.has("xpath"));
        assertEquals(26, xsd.get("line").asInt());
        assertEquals(12, xsd.get("column").asInt());
    }


    @Test
    void ruleExecutionRowsCarryStatusAndCount()
    {
        JsonNode root;
        try
        {
            root = MAPPER.readTree(JsonReportWriter.toJson(sampleReport()));
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
        JsonNode first = root.get("ruleExecutions").get(0);
        assertEquals("DEFINE-XML-0004", first.get("ruleId").asText());
        assertEquals("EXECUTED", first.get("status").asText());
        assertEquals(1, first.get("findingCount").asInt());
    }

}
