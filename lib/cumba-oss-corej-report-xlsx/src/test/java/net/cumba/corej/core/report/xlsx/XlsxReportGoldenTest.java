package net.cumba.corej.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.cumba.corej.core.report.ReportSections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>R2 — byte-identity across the module split, for the workbook.</b>
 *
 * <p>
 * The golden workbook was originally produced by the <em>pre-split</em> engine
 * ({@code net.cumba.corej.core.report.XlsxReportWriter} at commit {@code 6687442df}) from
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
 * ⚠ <b>Regenerated a second time, deliberately, for D65 (phase 5b of
 * {@code PLAN-typed-expression-engine.md})</b>: the {@code Rules Report} sheet gained the three
 * programmatically appended count-column headers ({@code Executed} / {@code Skipped} /
 * {@code Errored}). Verified cell-by-cell against the previous golden: exactly three resolved-value
 * differences, all of them the new header cells — the fixture's rows carry no counts, so every data
 * cell is unchanged. The ratchet resumes from here.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Compared by per-entry content digest, never by file hash.</b> An XLSX is a zip, and a zip
 * carries a per-entry modification timestamp: two runs of <em>identical</em> code produce files
 * with different bytes and different SHA-256s. A file-hash comparison here would fail every time
 * and teach the next reader to disable it. What must be stable is the entry set and each entry's
 * decompressed content, which is what this asserts.
 * </p>
 *
 * <p>
 * ⚠ <b>One byte is normalised before hashing, and only that one.</b> POI saves every XML part
 * through XmlBeans, which ends the {@code <?xml …?>} declaration with the JVM's line separator —
 * {@code \n} on Linux, where the golden was made, {@code \r\n} on Windows. Measured: on a Windows
 * separator 11 of the 17 parts differ, each only there, so this test was red on every Windows build
 * while the workbook was correct. A {@code \r\n} directly after the declaration is therefore read
 * as {@code \n}. Nothing else is touched: a line break inside a cell is content, and still compared
 * byte for byte.
 * </p>
 */
class XlsxReportGoldenTest
{

    @Test
    void theWorkbookIsContentIdenticalToThePreSplitEngine() throws Exception
    {
        assertContentIdenticalToGolden(render());
    }


    /**
     * A render on a Windows line separator matches the golden. The workbook is rendered in a FORKED
     * JVM started with {@code -Dline.separator=\r\n} ({@link XlsxWindowsSeparatorRender}): the XML
     * serialisation caches the separator per JVM once anything has saved a part, so switching it
     * in-process after another test's render is unreliable (measured). This is the real writer's
     * Windows output, not a rewrite of the golden, so a POI/XmlBeans change that moved a
     * {@code \r\n} anywhere else would turn it red here, on any host.
     */
    @Test
    void aRenderOnAWindowsLineSeparatorIsContentIdenticalToTheGolden(@TempDir Path aTmp)
        throws Exception
    {
        Path out = aTmp.resolve("windows.xlsx");
        Process fork = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dline.separator=\r\n", "-cp", System.getProperty("java.class.path"),
                XlsxWindowsSeparatorRender.class.getName(), out.toString())
                        .redirectErrorStream(true).start();
        String output = new String(fork.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(fork.waitFor(120, TimeUnit.SECONDS), "the forked render did not finish");
        assertEquals(0, fork.exitValue(), () -> "the forked render failed: " + output);

        byte[] rendered = Files.readAllBytes(out);
        // Anti-vacuity: the Windows separator must really have reached the output — measured, 11 of
        // the 17 parts carry an XML declaration.
        int crLfParts = crLfDeclarationCount(rendered);
        assertTrue(crLfParts >= 11,
                () -> "precondition: expected >= 11 parts with \\r\\n after the declaration, saw "
                        + crLfParts);
        assertContentIdenticalToGolden(rendered);
    }


    @Test
    void theNormalisationTouchesOnlyTheLineEndAfterTheDeclaration()
    {
        byte[] crlf = "<?xml version=\"1.0\"?>\r\n<a>x\r\ny</a>".getBytes(StandardCharsets.UTF_8);
        assertEquals("<?xml version=\"1.0\"?>\n<a>x\r\ny</a>",
                new String(declarationLineEndNormalised(crlf), StandardCharsets.UTF_8),
                "only the declaration's line end is normalised; a CRLF in content stays");
        byte[] lf = "<?xml version=\"1.0\"?>\n<a/>".getBytes(StandardCharsets.UTF_8);
        assertSame(lf, declarationLineEndNormalised(lf), "an LF part is returned as is");
        byte[] noDeclaration = "<a>\r\n</a>".getBytes(StandardCharsets.UTF_8);
        assertSame(noDeclaration, declarationLineEndNormalised(noDeclaration),
                "a part without a declaration is returned as is");
    }


    private static void assertContentIdenticalToGolden(byte[] aRendered) throws Exception
    {
        Map<String, String> golden = entryDigests(resource("/report/golden.xlsx"));
        Map<String, String> actual = entryDigests(aRendered);

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


    static byte[] render() throws IOException
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
                digest.update(declarationLineEndNormalised(in.readAllBytes()));
                digests.put(entry.getName(), HexFormat.of().formatHex(digest.digest()));
            }
        }
        return digests;
    }


    private static int crLfDeclarationCount(byte[] aZip) throws IOException
    {
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(aZip)))
        {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null)
            {
                String head = new String(in.readNBytes(80), StandardCharsets.UTF_8);
                if (!entry.isDirectory() && head.startsWith("<?xml") && head.contains("?>\r\n"))
                {
                    count++;
                }
            }
        }
        return count;
    }


    /**
     * Returns {@code aPart} with a {@code \r\n} that directly follows a leading {@code <?xml …?>}
     * declaration replaced by {@code \n}; every other byte, and any part without such a
     * declaration, is returned unchanged. See the class javadoc for why.
     */
    static byte[] declarationLineEndNormalised(byte[] aPart)
    {
        byte[] head = "<?xml".getBytes(StandardCharsets.US_ASCII);
        if (aPart.length < head.length
                || !Arrays.equals(aPart, 0, head.length, head, 0, head.length))
        {
            return aPart;
        }
        for (int i = head.length; i + 3 < aPart.length; i++)
        {
            if (aPart[i] == '?' && aPart[i + 1] == '>')
            {
                if (aPart[i + 2] != '\r' || aPart[i + 3] != '\n')
                {
                    return aPart;
                }
                byte[] out = new byte[aPart.length - 1];
                System.arraycopy(aPart, 0, out, 0, i + 2);
                System.arraycopy(aPart, i + 3, out, i + 2, aPart.length - i - 3);
                return out;
            }
        }
        return aPart;
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
