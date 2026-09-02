package net.cumba.cdisc.define.conformance.xsd;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import net.cumba.cdisc.define.DefineXmlConverter;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * XSD pre-pass (plan §3.5 steps 1–2): validates a raw define.xml byte stream against the vendored
 * CDISC Define-XML 2.1 or 2.0 schema package under {@code src/main/resources/xsd/}, selected by the
 * caller's detected version. The input is the <b>raw</b> document (bytes/stream, not a DOM) so that
 * {@link SAXParseException} line/column numbers refer to the file the user submitted.
 *
 * <p>
 * Security mirrors {@code DefineDomIo.parse}: {@code FEATURE_SECURE_PROCESSING} plus empty
 * {@code ACCESS_EXTERNAL_DTD}/{@code ACCESS_EXTERNAL_SCHEMA}, so nothing is ever fetched from the
 * file system or network. The schema's {@code xs:include}/{@code xs:import}/{@code xs:redefine}
 * chain (all relative paths — verified against the vendored tree) is resolved exclusively from
 * classpath resources through an {@link LSResourceResolver} keyed off a {@code classpath:} pseudo
 * base URI.
 * </p>
 *
 * <p>
 * All problems are collected through an {@link ErrorHandler} — never fail-fast. A fatal
 * (well-formedness) error aborts the underlying parse by design; everything reported up to that
 * point, including the fatal itself, is returned.
 * </p>
 *
 * <p>
 * Note: the vendored Define entry schemas do not include the ARM 1.0 extension entry point
 * ({@code arm1-0-0.xsd} is a standalone entry); documents using {@code arm:} content will produce
 * schema findings. The PMDA Define-XML sheet has no ARM rows, so this matches the rule scope.
 * </p>
 */
public final class XsdValidator
{

    private static final String CLASSPATH_SCHEME = "classpath";

    private static final ConcurrentMap<DefineXmlConverter.Version, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private XsdValidator()
    {
    }


    /** Convenience overload over {@link #validate(InputStream, DefineXmlConverter.Version)}. */
    public static List<SaxProblem> validate(byte[] aDocumentBytes,
            DefineXmlConverter.Version aVersion)
    {
        try
        {
            return validate(new ByteArrayInputStream(aDocumentBytes), aVersion);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("in-memory validation cannot fail on I/O", e);
        }
    }


    /**
     * Validates the raw document stream against the vendored schema package for {@code aVersion}
     * ({@code V2_0} or {@code V2_1} — there is no vendored 1.0 schema) and returns every problem
     * the parser reported, in document order.
     *
     * @throws IllegalArgumentException
     *             if {@code aVersion} is {@code V1_0}
     * @throws IOException
     *             if the stream cannot be read
     */
    @SuppressWarnings("PMD.EmptyCatchBlock") // fatal already collected via the ErrorHandler
    public static List<SaxProblem> validate(InputStream aDocument,
            DefineXmlConverter.Version aVersion)
        throws IOException
    {
        List<SaxProblem> problems = new ArrayList<>();
        Validator validator = schemaFor(aVersion).newValidator();
        try
        {
            validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        }
        catch (SAXNotRecognizedException | SAXNotSupportedException e)
        {
            throw new IllegalStateException("cannot harden schema validator", e);
        }
        validator.setErrorHandler(new CollectingErrorHandler(problems));
        try
        {
            validator.validate(new StreamSource(aDocument));
        }
        catch (SAXException _)
        {
            // A fatal (well-formedness) error: Xerces reports it to the ErrorHandler — where it
            // has already been collected — and then rethrows to abort the parse. Collect-what-
            // you-have semantics: return everything reported so far.
        }
        return problems;
    }


    /** The cached, thread-safe {@link Schema} for a supported Define-XML version. */
    static Schema schemaFor(DefineXmlConverter.Version aVersion)
    {
        if (aVersion == DefineXmlConverter.Version.V1_0)
        {
            throw new IllegalArgumentException("no vendored schema for Define-XML 1.0");
        }
        return SCHEMAS.computeIfAbsent(aVersion, XsdValidator::loadSchema);
    }


    private static Schema loadSchema(DefineXmlConverter.Version aVersion)
    {
        String entry = aVersion == DefineXmlConverter.Version.V2_0
                ? "/xsd/define-2-0/cdisc-define-2.0/define2-0-0.xsd"
                : "/xsd/define-2-1/cdisc-define-2.1/define2-1-0.xsd";
        try (InputStream in = XsdValidator.class.getResourceAsStream(entry))
        {
            if (in == null)
            {
                throw new IllegalStateException("vendored schema missing from classpath: " + entry);
            }
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver(new ClasspathResourceResolver());
            return factory.newSchema(new StreamSource(in, CLASSPATH_SCHEME + ":" + entry));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read vendored schema " + entry, e);
        }
        catch (SAXException e)
        {
            throw new IllegalStateException("cannot compile vendored schema " + entry, e);
        }
    }

    /** Collects every reported problem; never throws, so validation is never fail-fast. */
    private record CollectingErrorHandler(List<SaxProblem> problems) implements ErrorHandler
    {

        @Override
        public void warning(SAXParseException aException)
        {
            problems.add(SaxProblem.of(SaxProblem.Kind.WARNING, aException));
        }


        @Override
        public void error(SAXParseException aException)
        {
            problems.add(SaxProblem.of(SaxProblem.Kind.ERROR, aException));
        }


        @Override
        public void fatalError(SAXParseException aException)
        {
            problems.add(SaxProblem.of(SaxProblem.Kind.FATAL, aException));
        }

    }


    /**
     * Resolves the schema package's relative {@code schemaLocation} references against the
     * including schema's {@code classpath:} base URI and serves them from classpath resources only.
     * Anything outside the vendored {@code /xsd/} tree resolves to {@code null}, which — with
     * {@code ACCESS_EXTERNAL_SCHEMA} empty — the parser turns into a hard error rather than an
     * external fetch.
     */
    private static final class ClasspathResourceResolver implements LSResourceResolver
    {

        @Override
        public @Nullable LSInput resolveResource(@Nullable String aType,
                @Nullable String aNamespaceUri, @Nullable String aPublicId,
                @Nullable String aSystemId, @Nullable String aBaseUri)
        {
            if (aSystemId == null || aBaseUri == null)
            {
                return null;
            }
            URI resolved = URI.create(aBaseUri).resolve(aSystemId);
            String path = resolved.getPath();
            if (!CLASSPATH_SCHEME.equals(resolved.getScheme()) || path == null
                    || !path.startsWith("/xsd/"))
            {
                return null;
            }
            try (InputStream in = XsdValidator.class.getResourceAsStream(path))
            {
                if (in == null)
                {
                    return null;
                }
                return new BytesLsInput(aPublicId, resolved.toString(), in.readAllBytes());
            }
            catch (IOException e)
            {
                throw new UncheckedIOException("cannot read vendored schema resource " + path, e);
            }
        }

    }


    /** Minimal byte-array {@link LSInput}; the setters are no-ops (the parser never calls them). */
    private static final class BytesLsInput implements LSInput
    {

        private final @Nullable String publicId;

        private final String systemId;

        private final byte[] bytes;

        BytesLsInput(@Nullable String aPublicId, String aSystemId, byte[] aBytes)
        {
            publicId = aPublicId;
            systemId = aSystemId;
            bytes = aBytes;
        }


        @Override
        public InputStream getByteStream()
        {
            return new ByteArrayInputStream(bytes);
        }


        @Override
        public @Nullable String getPublicId()
        {
            return publicId;
        }


        @Override
        public String getSystemId()
        {
            return systemId;
        }


        @Override
        public @Nullable Reader getCharacterStream()
        {
            return null;
        }


        @Override
        public @Nullable String getStringData()
        {
            return null;
        }


        @Override
        public @Nullable String getBaseURI()
        {
            return null;
        }


        @Override
        public @Nullable String getEncoding()
        {
            return null;
        }


        @Override
        public boolean getCertifiedText()
        {
            return false;
        }


        @Override
        public void setByteStream(@Nullable InputStream aByteStream)
        {
            // immutable input
        }


        @Override
        public void setCharacterStream(@Nullable Reader aCharacterStream)
        {
            // immutable input
        }


        @Override
        public void setStringData(@Nullable String aStringData)
        {
            // immutable input
        }


        @Override
        public void setSystemId(@Nullable String aSystemId)
        {
            // immutable input
        }


        @Override
        public void setPublicId(@Nullable String aPublicId)
        {
            // immutable input
        }


        @Override
        public void setBaseURI(@Nullable String aBaseUri)
        {
            // immutable input
        }


        @Override
        public void setEncoding(@Nullable String aEncoding)
        {
            // immutable input
        }


        @Override
        public void setCertifiedText(boolean aCertifiedText)
        {
            // immutable input
        }

    }

}
