package net.cumba.cdisc.core.report.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportSections;
import org.junit.jupiter.api.Test;

/**
 * <b>R2 — byte-identity across the module split.</b>
 *
 * <p>
 * The two golden files were produced by the <em>pre-split</em> engine
 * ({@code net.cumba.cdisc.core.report.JsonReportWriter} at commit {@code 6687442df}) from
 * {@code report-sections-fixture.json}, and are compared here byte for byte against what the
 * extracted writer produces from the same fixture. The v1 schema is frozen ({@code W32-F1}), so a
 * diff here is a schema break, not a formatting nit — regenerate the goldens only with an explicit
 * owner decision to change the published shape.
 * </p>
 *
 * <p>
 * ⚠ The fixture is driven through {@link ReportSections#fromExportDocument} rather than through the
 * assembler on purpose: it makes the input a fixed literal with no timestamp, no runtime and no
 * environment in it, so the comparison is a real byte comparison rather than a comparison of two
 * things that happened to be generated in the same second.
 * </p>
 */
class JsonReportGoldenTest
{

    static ReportSections fixture() throws IOException
    {
        try (InputStream in = JsonReportGoldenTest.class
                .getResourceAsStream("/report/report-sections-fixture.json"))
        {
            assertNotNull(in, "the fixture resource must be on the test classpath");
            Map<String, Object> document = new ObjectMapper().readValue(in,
                    new TypeReference<Map<String, Object>>()
                    {
                    });
            return ReportSections.fromExportDocument(document);
        }
    }


    private static String golden(String name) throws IOException
    {
        try (InputStream in = JsonReportGoldenTest.class.getResourceAsStream("/report/" + name))
        {
            assertNotNull(in, "missing golden resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    private static String write(boolean combined) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonReportWriter(combined).write(fixture(), out);
        return out.toString(StandardCharsets.UTF_8);
    }


    @Test
    void v1IsByteIdenticalToThePreSplitEngine() throws IOException
    {
        assertEquals(golden("golden-v1.json"), write(false));
    }


    @Test
    void v2IsByteIdenticalToThePreSplitEngine() throws IOException
    {
        assertEquals(golden("golden-v2.json"), write(true));
    }


    @Test
    void theTwoDocumentsDifferOnlyWhereTheSchemasDo() throws IOException
    {
        // Guards the golden pair against the failure mode of both files being regenerated from the
        // same code path by mistake: v2 leads with the discriminator and carries Findings; v1 has
        // neither and carries Issue_Details instead.
        String v1 = write(false);
        String v2 = write(true);
        assertEquals(true, v2.startsWith("{\"Report_Version\":\"2.0\","), v2.substring(0, 40));
        assertEquals(false, v1.contains("Report_Version"));
        assertEquals(true, v1.contains("\"Issue_Details\""));
        assertEquals(false, v1.contains("\"Findings\""));
        assertEquals(true, v2.contains("\"Findings\""));
        assertEquals(false, v2.contains("\"Issue_Details\""));
    }
}
