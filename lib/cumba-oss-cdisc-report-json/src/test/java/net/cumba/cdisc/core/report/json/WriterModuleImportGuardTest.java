package net.cumba.cdisc.core.report.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>R7 — the import guard, and the reason R5 is executable rather than merely intended.</b>
 *
 * <p>
 * The report SPI lives <em>inside</em> the engine (owner ruling, 2026-08-12), so the build cannot
 * prove that a writer module touches only SPI surface: this module compiles against the whole of
 * {@code cumba-oss-cdisc-core} and javac will not object if a writer reaches into {@code exec/} or
 * {@code model/}. The boundary is therefore a convention — and this project's own record is that an
 * unenforced convention rots (the "a guard must sit in the top-level {@code all}" rule stood five
 * days with nothing checking it, and wave-35 lane D then found five shipped violations).
 * </p>
 *
 * <p>
 * So: every {@code import} in this module's production sources must name the JDK, the report SPI,
 * {@code Property}/{@code PropertyType}, JSpecify, or this format's own library (Jackson). Anything
 * else means the claim <em>"a new writer needs only the SPI"</em> has quietly stopped being true.
 * </p>
 */
class WriterModuleImportGuardTest
{

    /**
     * Import prefixes a report-writer module may use. Deliberately short: lengthening it is the
     * decision this guard exists to force into the open.
     */
    private static final List<String> ALLOWED = List.of(
            // the JDK
            "java.",
            // the report SPI plus the neutral section model, all in the engine's report package
            "net.cumba.cdisc.core.report.ReportFormat", "net.cumba.cdisc.core.report.ReportManager",
            "net.cumba.cdisc.core.report.ReportSections",
            "net.cumba.cdisc.core.report.ReportWriter",
            "net.cumba.cdisc.core.report.ReportWriterSupplier",
            "net.cumba.cdisc.core.report.ServiceReportManager",
            // the shared, unmodified property model
            "net.cumba.datatable.io.Property", "net.cumba.datatable.io.PropertyType",
            // nullability annotations
            "org.jspecify.",
            // this module's own format library
            "com.fasterxml.jackson.");

    @Test
    void productionSourcesImportOnlyTheSpiAndTheFormatLibrary() throws IOException
    {
        Path sources = sourceRoot();
        assertTrue(Files.isDirectory(sources), "source root not found: " + sources);

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(sources))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList())
            {
                scanned++;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                {
                    String trimmed = line.strip();
                    if (!trimmed.startsWith("import "))
                    {
                        continue;
                    }
                    String imported = trimmed.substring("import ".length()).replace("static ", "")
                            .replace(";", "").strip();
                    if (ALLOWED.stream().noneMatch(imported::startsWith))
                    {
                        violations.add(file.getFileName() + ": " + imported);
                    }
                }
            }
        }
        // Anti-vacuity: a guard that scanned nothing is a guard that proves nothing.
        assertTrue(scanned >= 3,
                "expected to scan this module's writer + two suppliers, saw " + scanned);
        assertEquals(List.of(), violations,
                "a report-writer module may import only the SPI, Property, JSpecify, the JDK and "
                        + "its own format library — see R7");
    }


    /**
     * This module's {@code src/main/java}. Surefire runs with the working directory set to
     * {@code target/test-cwd} (parent pom), so the module base has to come from the {@code basedir}
     * system property Surefire provides; the walk-up is a fallback for an IDE runner.
     */
    static Path sourceRoot()
    {
        String basedir = System.getProperty("basedir");
        if (basedir != null && !basedir.isBlank())
        {
            return Path.of(basedir, "src", "main", "java");
        }
        Path cwd = Path.of("").toAbsolutePath();
        while (cwd != null && !Files.isDirectory(cwd.resolve("src/main/java")))
        {
            cwd = cwd.getParent();
        }
        return cwd == null ? Path.of("src/main/java") : cwd.resolve("src/main/java");
    }
}
