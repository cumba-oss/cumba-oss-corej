package net.cumba.cdisc.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.cumba.cdisc.core.report.ReportSections;
import org.junit.jupiter.api.Test;

/**
 * <b>R2 — byte-identity across the module split, for the workbook.</b>
 *
 * <p>
 * The golden workbook was originally produced by the <em>pre-split</em> engine
 * ({@code net.cumba.cdisc.core.report.XlsxReportWriter} at commit {@code 6687442df}) from
 * {@code report-sections-fixture.json}.
 * </p>
 *
 * <p>
 * ⚠ <b>Regenerated once, deliberately, for {@code PLAN-dictionary-seeder} Phase 6a</b> (from the
 * same fixture, by this module's writer): the report template intentionally gained Conformance rows
 * 21-23 ({@code Dictionary Basis}, {@code Neoplasm Version}, {@code Library Metadata Basis}), so
 * the sheet and shared-strings parts could no longer match the pre-Phase-6a bytes. The fixture sets
 * none of the three, and the regenerated golden was verified cell-by-cell against the old one:
 * exactly four resolved-value differences, all of them the new rows' template defaults. (At the XML
 * level every sheet part changed, because the three added template strings shift the shared-string
 * indices of every runtime-added string by three — the resolved content is what was compared.) The
 * ratchet resumes from here.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Compared by per-entry content digest, never by file hash.</b> An XLSX is a zip, and a zip
 * carries a per-entry modification timestamp: two runs of <em>identical</em> code produce files
 * with different bytes and different SHA-256s. A file-hash comparison here would fail every time
 * and teach the next reader to disable it. What must be stable is the entry set and each entry's
 * decompressed content, which is what this asserts.
 * </p>
 */
class XlsxReportGoldenTest
{

    @Test
    void theWorkbookIsContentIdenticalToThePreSplitEngine() throws Exception
    {
        Map<String, String> golden = entryDigests(resource("/report/golden.xlsx"));
        Map<String, String> actual = entryDigests(render());

        assertEquals(golden.keySet(), actual.keySet(), "the zip entry set must not change");
        for (Map.Entry<String, String> e : golden.entrySet())
        {
            assertEquals(e.getValue(), actual.get(e.getKey()),
                    "workbook part changed after the writer moved module: " + e.getKey());
        }
        // Anti-vacuity: an empty or one-entry map would make the loop above prove nothing.
        assertEquals(true, golden.size() > 5,
                "a real workbook has many parts, saw " + golden.size());
    }


    private static byte[] render() throws IOException
    {
        Map<String, Object> document;
        try (InputStream in = resourceStream("/report/report-sections-fixture.json"))
        {
            document = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>()
            {
            });
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxReportWriter(10_000).write(ReportSections.fromExportDocument(document), out);
        return out.toByteArray();
    }


    private static Map<String, String> entryDigests(byte[] zip)
        throws IOException, NoSuchAlgorithmException
    {
        Map<String, String> digests = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)))
        {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null)
            {
                if (entry.isDirectory())
                {
                    continue;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(in.readAllBytes());
                digests.put(entry.getName(), HexFormat.of().formatHex(digest.digest()));
            }
        }
        return digests;
    }


    private static byte[] resource(String path) throws IOException
    {
        try (InputStream in = resourceStream(path))
        {
            return in.readAllBytes();
        }
    }


    private static InputStream resourceStream(String path) throws IOException
    {
        InputStream in = XlsxReportGoldenTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing test resource " + path);
        return in;
    }
}
