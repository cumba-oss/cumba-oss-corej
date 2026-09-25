package net.cumba.corej.ruletest.cdt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.impl.support.OverlayDataTable;

/**
 * Test-side classpath readers for {@code .cdt} fixtures — what the retired
 * {@code CdtLoader.loadAllResource} and {@code RuleTestCdt.loadResource} did
 * ({@code PLAN-retire-dead-multi-match-lookup} U8, 2026-09-25: neither had a caller outside this
 * module's own tests). Pure forwarders over the parse entry points; the sidecar resolver reads
 * sibling resources, as the retired loader did.
 */
public final class CdtTestResources
{

    private CdtTestResources()
    {
    }


    /** The resource's content, UTF-8; fails the test if it is absent. */
    public static String read(String aResourcePath) throws IOException
    {
        try (InputStream in = classLoader().getResourceAsStream(aResourcePath))
        {
            if (in == null)
            {
                throw new IOException("test resource not found on classpath: " + aResourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    /** Every dataset block of the resource, in file order. */
    public static List<OverlayDataTable> parseAllResource(String aResourcePath) throws IOException
    {
        return CdtLoader.parseAll(read(aResourcePath), aResourcePath);
    }


    /** The scenario, {@code #library-include} sidecars resolved as sibling resources. */
    public static RuleTestScenario loadScenario(String aResourcePath) throws IOException
    {
        ClassLoader cl = classLoader();
        int slash = aResourcePath.lastIndexOf('/');
        String parent = slash < 0 ? "" : aResourcePath.substring(0, slash);
        return RuleTestCdt.parse(read(aResourcePath), aResourcePath,
                rel -> cl.getResourceAsStream(parent.isEmpty() ? rel : parent + "/" + rel));
    }


    private static ClassLoader classLoader()
    {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx : CdtTestResources.class.getClassLoader();
    }
}
