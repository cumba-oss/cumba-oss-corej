package net.cumba.corej.define.conformance.xsd;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.cdisc.define.DefineDomUtil;
import net.cumba.cdisc.define.DefineXmlConverter;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;

/**
 * The PMDA document-level checks that ride along with the XSD pre-pass (plan §3.5 step 6), before
 * bean parsing:
 *
 * <ul>
 * <li>{@code PMDA-OD0010} (Reject) — "Define.xml must start with an XML declaration". A raw-bytes
 * check on the first non-BOM characters: an XSD-valid document may legally omit the declaration, so
 * neither the schema pass nor the DOM can see this.</li>
 * <li>{@code PMDA-OD0011} (Warning) — the declared {@code encoding} must be UTF-8, UTF-16, or
 * ISO-8859-1. Read from the raw prolog because the parsed DOM does not retain it.</li>
 * <li>{@code PMDA-DD0002} (Reject) — required namespace declarations, via
 * {@link DefineDomUtil#hasNamespace}: the ODM namespace {@code http://www.cdisc.org/ns/odm/v1.3}
 * and the {@code def} namespace ({@code …/def/v2.1} or {@code …/def/v2.0} per detected version).
 * The sheet's DESCRIPTION also names {@code xsi} and {@code xlink}, but marks both conditional
 * ("only required when a local schema / external documents are provided"); an xlink-using document
 * with the namespace undeclared is not namespace-well-formed and already fails as
 * {@code PMDA-OD0001}, so only the two unconditional namespaces are checked here.</li>
 * </ul>
 *
 * <p>
 * Category choice: these findings carry {@link Category#PMDA}, not {@code XSD} — they are
 * PMDA-sheet rules ({@code DD0002}/{@code OD0010}) implemented as raw/DOM checks, whereas
 * {@code Category.XSD} marks findings produced by schema validation itself (plan §3.5 step 3).
 * Source-true: the category mirrors the rule's sheet, the pre-pass is just the transport.
 * </p>
 */
public final class RawDocumentChecks
{

    private static final String ODM_NS_13 = "http://www.cdisc.org/ns/odm/v1.3";

    private static final String DEF_NS_20 = "http://www.cdisc.org/ns/def/v2.0";

    private static final String DEF_NS_21 = "http://www.cdisc.org/ns/def/v2.1";

    /** UTF-8 byte-order mark. */
    private static final byte[] BOM_UTF8 =
    {
            (byte) 0xEF, (byte) 0xBB, (byte) 0xBF
    };

    private RawDocumentChecks()
    {
    }


    /**
     * {@code PMDA-OD0010}: empty when the file starts (after an optional BOM) with an XML
     * declaration. UTF-16 documents (with or without BOM) are decoded accordingly before the
     * {@code "<?xml"} comparison.
     */
    public static Optional<ConformanceFinding> xmlDeclaration(byte[] aDocumentBytes)
    {
        if (startsWithXmlDeclaration(aDocumentBytes))
        {
            return Optional.empty();
        }
        return Optional.of(ConformanceFinding.builder() //
                .ruleId("PMDA-OD0010") //
                .category(Category.PMDA) //
                .severity(Severity.REJECT) //
                .message("Missing XML declaration: Define.xml must start with an XML declaration.") //
                .build());
    }


    /**
     * {@code PMDA-OD0011} (Warning): the XML declaration's {@code encoding} pseudo-attribute, when
     * present, must name one of UTF-8, UTF-16, or ISO-8859-1 (case-insensitive). A document with no
     * declaration (or no {@code encoding=} in it) is not flagged here — the absent declaration is
     * {@code OD0010}'s finding, and an absent {@code encoding} defaults to UTF-8, which is legal.
     * Read from the raw prolog bytes because the parsed DOM does not retain the declared encoding.
     */
    public static Optional<ConformanceFinding> xmlEncoding(byte[] aDocumentBytes)
    {
        String encoding = declaredEncoding(aDocumentBytes);
        if (encoding == null)
        {
            return Optional.empty();
        }
        String normalised = encoding.toUpperCase(Locale.ROOT).replace("_", "-");
        if (normalised.equals("UTF-8") || normalised.equals("UTF-16")
                || normalised.equals("ISO-8859-1"))
        {
            return Optional.empty();
        }
        return Optional.of(ConformanceFinding.builder() //
                .ruleId("PMDA-OD0011") //
                .category(Category.PMDA) //
                .severity(Severity.WARNING) //
                .message("Invalid XML encoding [" + encoding + "]: Define.xml must use UTF-8, "
                        + "UTF-16, or ISO-8859-1 encoding.") //
                .build());
    }


    /**
     * The {@code encoding="…"} value declared in the XML prolog, or {@code null} when there is no
     * declaration or it carries no {@code encoding} pseudo-attribute. Decodes the prolog with the
     * same UTF-8/UTF-16 detection as {@link #startsWithXmlDeclaration}.
     */
    static @Nullable String declaredEncoding(byte[] aBytes)
    {
        String prolog = decodeProlog(aBytes);
        if (prolog == null || !prolog.startsWith("<?xml"))
        {
            return null;
        }
        int end = prolog.indexOf("?>");
        String declaration = end < 0 ? prolog : prolog.substring(0, end);
        Matcher matcher = ENCODING_PATTERN.matcher(declaration);
        return matcher.find() ? matcher.group(1) : null;
    }


    private static @Nullable String decodeProlog(byte[] aBytes)
    {
        int length = Math.min(aBytes.length, 200);
        for (Charset charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16BE,
                StandardCharsets.UTF_16LE))
        {
            String head = new String(aBytes, 0, length, charset);
            if (head.startsWith("\uFEFF"))
            {
                head = head.substring(1);
            }
            if (head.startsWith("<?xml"))
            {
                return head;
            }
        }
        return null;
    }

    private static final Pattern ENCODING_PATTERN = Pattern
            .compile("encoding\\s*=\\s*[\"']([^\"']*)[\"']");

    /**
     * {@code PMDA-DD0002}: one finding per missing required namespace declaration (ODM v1.3 and the
     * version-matching {@code def} namespace).
     *
     * @param aVersion
     *            the detected Define-XML version, {@code V2_0} or {@code V2_1} (1.0 is outside this
     *            validator's scope, plan §2.5)
     */
    public static List<ConformanceFinding> namespaceDeclarations(Document aDocument,
            DefineXmlConverter.Version aVersion)
    {
        if (aVersion == DefineXmlConverter.Version.V1_0)
        {
            throw new IllegalArgumentException("Define-XML 1.0 is outside the validator's scope");
        }
        List<ConformanceFinding> findings = new ArrayList<>();
        if (!DefineDomUtil.hasNamespace(aDocument, ODM_NS_13))
        {
            findings.add(missingNamespace("CDISC ODM", "xmlns=\"" + ODM_NS_13 + "\""));
        }
        String defNs = aVersion == DefineXmlConverter.Version.V2_0 ? DEF_NS_20 : DEF_NS_21;
        if (!DefineDomUtil.hasNamespace(aDocument, defNs))
        {
            findings.add(missingNamespace("CDISC Define", "xmlns:def=\"" + defNs + "\""));
        }
        return List.copyOf(findings);
    }


    private static ConformanceFinding missingNamespace(String aName, String aDeclaration)
    {
        return ConformanceFinding.builder() //
                .ruleId("PMDA-DD0002") //
                .category(Category.PMDA) //
                .severity(Severity.REJECT) //
                .element("ODM") //
                .message("Missing or invalid " + aName + " namespace reference: Define.xml must "
                        + "declare " + aDeclaration + ".") //
                .build();
    }


    /** Whether the first non-BOM characters of the file are {@code "<?xml"}. */
    static boolean startsWithXmlDeclaration(byte[] aBytes)
    {
        // UTF-8 (with or without BOM) and every other ASCII-compatible encoding.
        int offset = startsWith(aBytes, BOM_UTF8, 0) ? BOM_UTF8.length : 0;
        byte[] decl = "<?xml".getBytes(StandardCharsets.US_ASCII);
        if (startsWith(aBytes, decl, offset))
        {
            return true;
        }
        // UTF-16, big- or little-endian, with or without BOM. new String(...) strips a leading
        // BOM character itself when present.
        for (Charset charset : List.of(StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE))
        {
            int length = Math.min(aBytes.length, 12);
            String head = new String(aBytes, 0, length, charset);
            if (head.startsWith("\uFEFF"))
            {
                head = head.substring(1);
            }
            if (head.startsWith("<?xml"))
            {
                return true;
            }
        }
        return false;
    }


    private static boolean startsWith(byte[] aBytes, byte[] aPrefix, int aOffset)
    {
        if (aBytes.length < aOffset + aPrefix.length)
        {
            return false;
        }
        for (int i = 0; i < aPrefix.length; i++)
        {
            if (aBytes[aOffset + i] != aPrefix[i])
            {
                return false;
            }
        }
        return true;
    }

}
