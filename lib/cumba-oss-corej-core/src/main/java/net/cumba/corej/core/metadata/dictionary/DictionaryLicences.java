package net.cumba.corej.core.metadata.dictionary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The licence and terms-of-use notices the installer writes beside each dictionary it installs —
 * classpath resources under {@code dictionary-licences/}, one per dictionary type.
 *
 * <p>
 * These texts went through the compliance analysis in {@code PLAN-dictionary-seeder} §4.1–§4.5;
 * follow that record rather than editing claims here. The two constraints that matter most:
 * </p>
 *
 * <ul>
 * <li><b>No Creative Commons licence is asserted for MED-RT or the neoplasm subset.</b> The NCI CC
 * BY 4.0 statement names only "The NCI Thesaurus™"; MED-RT is a VA/VHA product with no published
 * terms, and CDISC CT is published free of charge with no terms attached. The notices record
 * absence of restriction as the basis — asserting a grant that does not exist would be worse than
 * shipping nothing.</li>
 * <li><b>LOINC's required notice ships verbatim as its own file</b>
 * ({@value #LOINC_SHORT_LICENCE_FILE}), byte-identical to the text the LOINC licence prescribes, ®
 * symbols included.</li>
 * </ul>
 */
public final class DictionaryLicences
{

    /** The file name of LOINC's verbatim required attribution notice. */
    public static final String LOINC_SHORT_LICENCE_FILE = "LOINC_short_license.txt";

    private static final String RESOURCE_DIR = "/dictionary-licences/";

    private DictionaryLicences()
    {
    }


    /**
     * The notice to write beside an installed dictionary of the given type, or {@code null} when
     * none is packaged for it.
     *
     * @param aType
     *            the house dictionary type, e.g. {@code medrt}.
     * @return the notice text, UTF-8 decoded.
     */
    public static @Nullable String noticeFor(String aType)
    {
        return read(aType.toLowerCase(Locale.ROOT) + ".txt");
    }


    /**
     * Additional licence documents that must ship beside the type's data as their own files, keyed
     * by file name — for LOINC, the verbatim required notice. Empty for every other type.
     *
     * @param aType
     *            the house dictionary type.
     * @return file name to content, possibly empty.
     */
    public static Map<String, String> extraFilesFor(String aType)
    {
        if ("loinc".equalsIgnoreCase(aType))
        {
            String text = read(LOINC_SHORT_LICENCE_FILE);
            if (text != null)
            {
                return Map.of(LOINC_SHORT_LICENCE_FILE, text);
            }
        }
        return Map.of();
    }


    private static @Nullable String read(String aName)
    {
        if (!aName.matches("[A-Za-z0-9._-]+"))
        {
            return null;
        }
        try (InputStream in = DictionaryLicences.class.getResourceAsStream(RESOURCE_DIR + aName))
        {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
