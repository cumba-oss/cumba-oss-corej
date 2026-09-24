package net.cumba.corej.core.report.xlsx;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for {@link XlsxReportGoldenTest}'s forked render: writes the golden fixture's
 * workbook to {@code args[0]}. The test launches it in a fresh JVM with
 * {@code -Dline.separator=\r\n}, because the XML serialisation caches the separator per JVM once
 * anything has been saved, so switching it in-process is unreliable (measured).
 */
public final class XlsxWindowsSeparatorRender
{

    private XlsxWindowsSeparatorRender()
    {
    }


    /**
     * Renders the fixture workbook and writes it to the given file.
     *
     * @param aArgs
     *            {@code aArgs[0]}: the file to write the rendered workbook to.
     * @throws Exception
     *             if rendering or writing fails; the forked JVM then exits non-zero.
     */
    public static void main(String[] aArgs) throws Exception
    {
        Files.write(Path.of(aArgs[0]), XlsxReportGoldenTest.render());
    }
}
