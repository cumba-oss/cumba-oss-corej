package net.cumba.corej.define.conformance.xsd;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.DefineXmlConverter;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * The complete pre-pass over a raw define.xml (plan §3.5), in one call: raw-document checks
 * ({@link RawDocumentChecks}), version detection
 * ({@link DefineXmlConverter#detectVersion(Document)}), XSD validation against the version-matching
 * vendored schema ({@link XsdValidator}), and SAX→PMDA-id classification
 * ({@link SaxErrorClassifier}). Phase 7's {@code DefineConformanceEngine} consumes the
 * {@link Result} and then <b>always still runs</b> the rule engine afterwards (best-effort, plan
 * §3.5 step 4) — the pre-pass gates nothing.
 *
 * <p>
 * Version gate (plan §3.5 step 5): {@code V2_1} and {@code V2_0} documents are validated against
 * their own schema package. When the version cannot be detected the 2.1 schema (the newest) is
 * used. Explicitly detected {@code V1_0} documents are outside the validator's scope (plan §2.5:
 * the PMDA sheet's applicability columns cover 2.0/2.1 only; no 1.0 schema is vendored) — for them
 * only the raw XML-declaration check runs, and the caller sees the detected version in the
 * {@link Result} and can report the scope decision.
 * </p>
 */
public final class DefinePrePass
{

    private DefinePrePass()
    {
    }

    /**
     * Pre-pass outcome: the detected Define-XML version ({@code null} when detection failed — for
     * example on a document that is not well-formed) and every pre-pass finding, in emission order
     * (raw checks first, then schema findings in document order).
     */
    public record Result(DefineXmlConverter.@Nullable Version version,
            List<ConformanceFinding> findings)
    {

        public Result
        {
            findings = List.copyOf(findings);
        }

    }

    /** Runs the pre-pass on a define.xml file. */
    public static Result run(Path aDefineXml) throws IOException
    {
        return run(Files.readAllBytes(aDefineXml));
    }


    /** Runs the pre-pass on an in-memory define.xml document. */
    public static Result run(byte[] aDocumentBytes)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        RawDocumentChecks.xmlDeclaration(aDocumentBytes).ifPresent(findings::add);
        RawDocumentChecks.xmlEncoding(aDocumentBytes).ifPresent(findings::add);

        Document document = parseQuietly(aDocumentBytes);
        DefineXmlConverter.Version version = document == null ? null
                : DefineXmlConverter.detectVersion(document);
        if (version == DefineXmlConverter.Version.V1_0)
        {
            return new Result(version, findings);
        }

        DefineXmlConverter.Version effective = version == null ? DefineXmlConverter.Version.V2_1
                : version;
        if (document != null)
        {
            findings.addAll(RawDocumentChecks.namespaceDeclarations(document, effective));
        }
        findings.addAll(
                SaxErrorClassifier.toFindings(XsdValidator.validate(aDocumentBytes, effective)));
        return new Result(version, findings);
    }


    /**
     * DOM parse for version detection and the namespace check; a document that cannot be parsed
     * (not well-formed, DOCTYPE, …) yields {@code null} — the XSD validation pass still runs on the
     * raw bytes and reports the same problem as a classified fatal.
     */
    private static @Nullable Document parseQuietly(byte[] aDocumentBytes)
    {
        try
        {
            return DefineDomIo.parse(new ByteArrayInputStream(aDocumentBytes));
        }
        catch (IOException | ParserConfigurationException | SAXException _)
        {
            return null;
        }
    }

}
