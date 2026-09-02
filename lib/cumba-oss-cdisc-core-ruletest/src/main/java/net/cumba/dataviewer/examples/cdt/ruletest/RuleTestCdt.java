package net.cumba.dataviewer.examples.cdt.ruletest;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.dataviewer.examples.cdt.CdtLoader;
import net.cumba.dataviewer.examples.cdt.ruletest.RuleTestScenario.Verdict;
import org.jspecify.annotations.Nullable;

/**
 * Parser, loader, and writer for the extended-CDT "rule test scenario" format. A scenario file is a
 * plain {@code .cdt} file prefixed with a magic {@code #!RuleTest} shebang followed by one or more
 * directive lines ({@code #test ...}, {@code #note ...}). The dataset blocks that follow are handed
 * off verbatim to the CDT parser.
 *
 * <p>
 * This class lives inside the examples module and does not modify the CDT provider: plain
 * {@code CdtParser} can still read every scenario file as a normal multi-dataset CDT (the shebang
 * and directives survive as CDT comments).
 * </p>
 */
public final class RuleTestCdt
{

    /** The magic discriminator that must appear as the first non-empty line. */
    public static final String SHEBANG = "#!RuleTest";

    private RuleTestCdt()
    {
    }

    // ---- Entry points --------------------------------------------------------------


    /**
     * Returns {@code true} iff {@code aContent} starts with the {@code #!RuleTest} shebang. Leading
     * UTF-8 BOM and leading blank lines are tolerated (matches §2.3 of the format spec).
     */
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    public static boolean hasShebang(String aContent)
    {
        if (aContent == null)
        {
            return false;
        }
        String c = stripLeadingBom(aContent);
        for (String line : c.split("\\R", -1))
        {
            String t = line.trim();
            if (t.isEmpty())
            {
                continue;
            }
            return SHEBANG.equals(t);
        }
        return false;
    }

    /**
     * Resolves a {@code #library-include} sidecar path (relative to the scenario's location) to an
     * input stream. Supplied by {@link #load(Path)} / {@link #loadResource(String)}. A {@code null}
     * resolver (the plain {@link #parse(String, String)} entry point) makes
     * {@code #library-include} a hard error, since there is no base location to resolve against.
     */
    @FunctionalInterface
    public interface IncludeResolver
    {

        /** Open the named sidecar, or return {@code null} if it does not exist. */
        @Nullable
        InputStream open(String aRelativePath) throws IOException;
    }

    /**
     * Parse a scenario from its full file content. Strict entry point — throws if the shebang is
     * missing or any directive is invalid. {@code #library-include} directives are rejected (no
     * base location); use {@link #parse(String, String, IncludeResolver)} or {@link #load(Path)} to
     * support sidecars.
     */
    public static RuleTestScenario parse(String aContent, String aSource)
    {
        return parse(aContent, aSource, null);
    }


    /**
     * Parse a scenario, resolving any {@code #library-include} sidecar through {@code aResolver}.
     * Strict entry point.
     */
    public static RuleTestScenario parse(String aContent, String aSource,
            @Nullable IncludeResolver aResolver)
    {
        if (aContent == null)
        {
            throw error(aSource, 0, "empty content");
        }
        String[] lines = stripLeadingBom(aContent).split("\\R", -1);

        int shebangIdx = firstNonEmpty(lines);
        if (shebangIdx < 0 || !SHEBANG.equals(lines[shebangIdx].trim()))
        {
            throw error(aSource, 0,
                    "not a RuleTest file: missing '" + SHEBANG + "' shebang on line 1");
        }

        // Collect directive lines up to the first non-comment, non-blank line.
        List<Directive> directives = new ArrayList<>();
        int datasetRegionStart = lines.length;
        for (int i = shebangIdx + 1; i < lines.length; i++)
        {
            String t = lines[i].trim();
            if (t.isEmpty())
            {
                continue;
            }
            if (t.charAt(0) != '#')
            {
                datasetRegionStart = i;
                break;
            }
            // A '#'-prefixed line whose first non-'#' character is whitespace (or which
            // is just "#" alone) is a plain comment: skip but do not end the directive
            // block. Directives have the keyword immediately after '#'
            // (e.g. "#test ..."); comments have a space (e.g. "# note about ...").
            String afterHash = t.substring(1);
            if (afterHash.isEmpty() || Character.isWhitespace(afterHash.charAt(0)))
            {
                continue;
            }
            directives.add(new Directive(i, afterHash));
        }

        // Parse directives.
        String coreId = null;
        Verdict expect = null;
        String domain = null;
        String note = null;
        boolean sawTest = false;
        boolean sawNote = false;
        // Library directives are buffered and applied after the loop so sidecars (#library-include)
        // can be merged first and inline #library directives override them per key, independent of
        // line order (Decision D2).
        List<LibLine> inlineLibrary = new ArrayList<>();
        List<IncludeRef> includes = new ArrayList<>();
        // #library-ref directives are buffered like #library and merged after the loop. They are
        // mutually exclusive with inline #library / #library-include (a scenario tests either the
        // synthetic in-memory provider or the real CDISC Library, never a blend).
        List<LibLine> libraryRefLines = new ArrayList<>();
        // #define / #define-include: the Define-XML level, same grammar and merge order as the
        // library channel (sidecars first, inline overrides per key). Combines freely with any
        // library channel — the define and library axes are independent.
        List<LibLine> inlineDefine = new ArrayList<>();
        List<IncludeRef> defineIncludes = new ArrayList<>();
        // #expectViolationCount / #expectViolationAt are buffered and validated after the loop,
        // where the verdict (testExpect) is known — the verdict-consistency checks need it and the
        // directives may appear in any order relative to #test.
        List<LibLine> expectAtLines = new ArrayList<>();
        Integer expectCount = null;
        int expectCountLine = -1;
        // #dictionaries: opt-in external-dictionary bundle for the scenario run.
        String dictionaries = null;
        // #define-xml: a real Define-XML sidecar serving define_* AND define_vlm_* accessors.
        String defineXml = null;
        int defineXmlLine = -1;
        Severity runLevel = null;

        for (Directive d : directives)
        {
            List<String> tokens = tokenize(d.body, aSource, d.lineIdx);
            if (tokens.isEmpty())
            {
                throw error(aSource, d.lineIdx, "empty directive");
            }
            String keyword = tokens.get(0);
            switch (keyword)
            {
            case "test" ->
            {
                if (sawTest)
                {
                    throw error(aSource, d.lineIdx, "duplicate #test directive");
                }
                sawTest = true;
                TestPayload tp = parseTestPayload(tokens, aSource, d.lineIdx);
                coreId = tp.coreId;
                expect = tp.expect;
                domain = tp.domain;
            }
            case "note" ->
            {
                if (sawNote)
                {
                    throw error(aSource, d.lineIdx, "duplicate #note directive");
                }
                sawNote = true;
                note = parseNotePayload(tokens, aSource, d.lineIdx);
            }
            case "library" -> inlineLibrary.add(new LibLine(tokens, d.lineIdx));
            case "library-include" -> includes.add(
                    new IncludeRef(parseIncludePayload(tokens, aSource, d.lineIdx), d.lineIdx));
            case "library-ref" -> libraryRefLines.add(new LibLine(tokens, d.lineIdx));
            case "define" -> inlineDefine.add(new LibLine(tokens, d.lineIdx));
            case "define-include" -> defineIncludes.add(
                    new IncludeRef(parseIncludePayload(tokens, aSource, d.lineIdx), d.lineIdx));
            case "expectViolationCount" ->
            {
                if (expectCount != null)
                {
                    throw error(aSource, d.lineIdx, "duplicate #expectViolationCount directive");
                }
                expectCount = parseExpectCount(tokens, aSource, d.lineIdx);
                expectCountLine = d.lineIdx;
            }
            case "expectViolationAt" -> expectAtLines.add(new LibLine(tokens, d.lineIdx));
            case "dictionaries" ->
            {
                if (dictionaries != null)
                {
                    throw error(aSource, d.lineIdx, "duplicate #dictionaries directive");
                }
                dictionaries = parseDictionariesPayload(tokens, aSource, d.lineIdx);
            }
            case "runLevel" ->
            {
                if (runLevel != null)
                {
                    throw error(aSource, d.lineIdx, "duplicate #runLevel directive");
                }
                runLevel = parseRunLevelPayload(tokens, aSource, d.lineIdx);
            }
            case "define-xml" ->
            {
                if (defineXml != null)
                {
                    throw error(aSource, d.lineIdx, "duplicate #define-xml directive");
                }
                defineXml = parseDefineXmlPayload(tokens, aSource, d.lineIdx);
                defineXmlLine = d.lineIdx;
            }
            case "setup", "teardown" -> throw error(aSource, d.lineIdx, "directive '#" + keyword
                    + "' is reserved for a future version and not implemented in v1");
            default -> throw error(aSource, d.lineIdx, "unknown directive: #" + keyword
                    + " (expected #test, #note, #library, #library-include, #library-ref, "
                    + "#define, #define-include, #define-xml, #dictionaries, or " + "#runLevel)");
            }
        }
        if (!sawTest)
        {
            throw error(aSource, shebangIdx, "missing required #test directive");
        }
        // sawTest implies the #test case ran and assigned all three from a non-null TestPayload
        // (see parseTestPayload, which throws if any are absent); NullAway can't track that
        // flag-to-assignment invariant, so capture the non-null values explicitly here.
        String testCoreId = Objects.requireNonNull(coreId, "coreId set by #test");
        Verdict testExpect = Objects.requireNonNull(expect, "expect set by #test");
        String testDomain = Objects.requireNonNull(domain, "domain set by #test");

        // Slice the body: replace the prelude with blank lines so CdtParseException line
        // numbers still match the original file.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < datasetRegionStart; i++)
        {
            body.append('\n');
        }
        for (int i = datasetRegionStart; i < lines.length; i++)
        {
            body.append(lines[i]);
            if (i < lines.length - 1)
            {
                body.append('\n');
            }
        }

        List<OverlayDataTable> datasets = CdtLoader.parseAll(body.toString(), aSource);

        // Repeated dataset names would collide in the resolver — reject.
        Set<String> seen = new HashSet<>();
        for (OverlayDataTable t : datasets)
        {
            String n = t.getMetaData().getName();
            if (n == null || n.isBlank())
            {
                throw error(aSource, shebangIdx, "dataset is missing a name");
            }
            if (!seen.add(n.toUpperCase(Locale.ROOT)))
            {
                throw error(aSource, shebangIdx, "duplicate dataset name: " + n);
            }
        }

        // Domain must match at least one dataset (case-insensitive per §2.4).
        boolean domainMatches = false;
        for (OverlayDataTable t : datasets)
        {
            if (testDomain.equalsIgnoreCase(t.getMetaData().getName()))
            {
                domainMatches = true;
                break;
            }
        }
        if (!domainMatches)
        {
            throw error(aSource, shebangIdx,
                    "#test domain=" + testDomain + " does not match any dataset in the file");
        }

        LibraryRef libraryRef = buildLibraryRef(libraryRefLines, aSource);

        // D2: a scenario tests either the inline synthetic provider or the real CDISC Library,
        // never a blend. Reject any mix of #library-ref with #library / #library-include.
        if (libraryRef != null && (!inlineLibrary.isEmpty() || !includes.isEmpty()))
        {
            throw error(aSource, libraryRefLines.get(0).lineIdx(),
                    "#library-ref cannot be combined with #library / #library-include");
        }

        MapBackedLibraryMetadataProvider library = buildLibrary(includes, inlineLibrary, aResolver,
                aSource, "#library");
        MapBackedLibraryMetadataProvider define = buildLibrary(defineIncludes, inlineDefine,
                aResolver, aSource, "#define");

        // A scenario tests either the real parsed Define-XML sidecar or the synthetic map-backed
        // define double, never a blend (both would compete for the define-provider slot).
        if (defineXml != null && define != null)
        {
            throw error(aSource, defineXmlLine,
                    "#define-xml cannot be combined with #define / #define-include");
        }

        // Build + validate the location expectations now that the verdict (testExpect) is known.
        List<ExpectedViolation> expectedViolations = new ArrayList<>();
        for (LibLine line : expectAtLines)
        {
            expectedViolations.add(parseExpectAt(line.tokens(), aSource, line.lineIdx()));
        }
        boolean wantsLocation = !expectedViolations.isEmpty()
                || (expectCount != null && expectCount > 0);
        if (wantsLocation && testExpect != Verdict.VIOLATION)
        {
            throw error(aSource, shebangIdx,
                    "#expectViolationAt / #expectViolationCount>0 require expect=violation");
        }
        if (expectCount != null && expectCount == 0)
        {
            if (!expectedViolations.isEmpty())
            {
                throw error(aSource, expectCountLine,
                        "#expectViolationCount 0 forbids #expectViolationAt");
            }
            if (testExpect != Verdict.NO_VIOLATION)
            {
                throw error(aSource, expectCountLine,
                        "#expectViolationCount 0 requires expect=noViolation");
            }
        }
        if (expectCount != null && !expectedViolations.isEmpty()
                && expectCount != expectedViolations.size())
        {
            throw error(aSource, expectCountLine,
                    "#expectViolationCount " + expectCount + " disagrees with "
                            + expectedViolations.size() + " #expectViolationAt line(s)");
        }

        return RuleTestScenario.builder().coreId(testCoreId).expect(testExpect).domain(testDomain)
                .note(note).datasets(datasets).source(aSource).library(library)
                .libraryRef(libraryRef).define(define).defineXml(defineXml)
                .dictionaries(dictionaries).runLevel(runLevel).expectViolationCount(expectCount)
                .expectedViolations(expectedViolations).build();
    }


    /**
     * Merge sidecars then inline {@code #library} directives into a single provider. Sidecars are
     * applied first (in appearance order) and inline directives override them per key (Decision
     * D2). Returns {@code null} when the scenario declares no library metadata at all.
     */
    private static @Nullable MapBackedLibraryMetadataProvider buildLibrary(
            List<IncludeRef> aIncludes, List<LibLine> aInline, @Nullable IncludeResolver aResolver,
            String aSource, String aLabel)
    {
        if (aIncludes.isEmpty() && aInline.isEmpty())
        {
            return null;
        }
        MapBackedLibraryMetadataProvider.Builder builder = MapBackedLibraryMetadataProvider
                .builder();
        for (IncludeRef inc : aIncludes)
        {
            if (aResolver == null)
            {
                throw error(aSource, inc.lineIdx(),
                        aLabel + "-include requires loading from a file or classpath resource");
            }
            try (InputStream in = aResolver.open(inc.path()))
            {
                if (in == null)
                {
                    throw error(aSource, inc.lineIdx(),
                            aLabel + "-include: sidecar not found: " + inc.path());
                }
                LibraryYaml.merge(builder, in, aSource + " -> " + inc.path());
            }
            catch (IOException e)
            {
                throw new RuleTestCdtException(
                        aSource + ":" + (inc.lineIdx() + 1) + ": " + aLabel
                                + "-include: cannot read '" + inc.path() + "': " + e.getMessage(),
                        e);
            }
        }
        for (LibLine line : aInline)
        {
            parseLibraryPayload(builder, line.tokens(), aSource, line.lineIdx(), aLabel);
        }
        return builder.build();
    }


    /**
     * Build a {@link LibraryRef} from one or more buffered {@code #library-ref} directive lines.
     * Scalar keys ({@code standard}, {@code version}, {@code substandard}) overwrite across lines;
     * {@code ct=} accumulates. {@code standard} and {@code version} are required. Returns
     * {@code null} when no {@code #library-ref} line was seen.
     *
     * <pre>{@code
     * #library-ref standard=adamig version=1-3
     * #library-ref standard=sdtmig version=3-4 ct=sdtmct-2024-09-27 substandard=sdtm
     * }</pre>
     */
    private static @Nullable LibraryRef buildLibraryRef(List<LibLine> aLines, String aSource)
    {
        if (aLines.isEmpty())
        {
            return null;
        }
        var builder = LibraryRef.builder();
        String standard = null;
        String version = null;
        String substandard = null;
        for (LibLine line : aLines)
        {
            List<String> tokens = line.tokens();
            for (int i = 1; i < tokens.size(); i++)
            {
                String tok = tokens.get(i);
                int eq = tok.indexOf('=');
                if (eq < 0)
                {
                    throw error(aSource, line.lineIdx(),
                            "#library-ref: expected key=value, got '" + tok + "'");
                }
                String k = tok.substring(0, eq);
                String v = tok.substring(eq + 1);
                switch (k)
                {
                case "standard" -> standard = v;
                case "version" -> version = v;
                case "ct" -> builder.ctPackage(v);
                case "substandard" -> substandard = v;
                default -> throw error(aSource, line.lineIdx(), "#library-ref: unknown key '" + k
                        + "' (expected standard, version, ct, substandard)");
                }
            }
        }
        int firstLine = aLines.get(0).lineIdx();
        if (standard == null || standard.isBlank())
        {
            throw error(aSource, firstLine, "#library-ref: missing required standard=");
        }
        if (version == null || version.isBlank())
        {
            throw error(aSource, firstLine, "#library-ref: missing required version=");
        }
        return builder.standard(standard).version(version).substandard(substandard).build();
    }


    private static String parseIncludePayload(List<String> aTokens, String aSource, int aLineIdx)
    {
        if (aTokens.size() != 2)
        {
            throw error(aSource, aLineIdx, "#" + aTokens.get(0) + ": expected exactly one path "
                    + "(got " + (aTokens.size() - 1) + ")");
        }
        return aTokens.get(1);
    }


    /**
     * Load a scenario from a file path. Strict entry point. {@code #library-include} sidecars are
     * resolved relative to {@code aPath}'s parent directory.
     */
    public static RuleTestScenario load(Path aPath) throws IOException
    {
        String content = Files.readString(aPath, StandardCharsets.UTF_8);
        Path base = aPath.toAbsolutePath().getParent();
        IncludeResolver resolver = rel -> Files
                .newInputStream(base == null ? Path.of(rel) : base.resolve(rel));
        return parse(content, aPath.toString(), resolver);
    }


    /**
     * Load a scenario from a classpath resource. Strict entry point. {@code #library-include}
     * sidecars are resolved as sibling resources of {@code aResourcePath}.
     */
    public static RuleTestScenario loadResource(String aResourcePath) throws IOException
    {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        ClassLoader cl = ctx != null ? ctx : RuleTestCdt.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(aResourcePath))
        {
            if (in == null)
            {
                throw new IOException(
                        "RuleTest scenario resource not found on classpath: " + aResourcePath);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String parent = parentResourcePath(aResourcePath);
            IncludeResolver resolver = rel -> cl
                    .getResourceAsStream(parent.isEmpty() ? rel : parent + "/" + rel);
            return parse(content, aResourcePath, resolver);
        }
    }


    private static String parentResourcePath(String aResourcePath)
    {
        int slash = aResourcePath.lastIndexOf('/');
        return slash < 0 ? "" : aResourcePath.substring(0, slash);
    }


    /**
     * Write the scenario to {@code aPath}, overwriting any existing content.
     */
    public static void write(RuleTestScenario aScenario, Path aPath) throws IOException
    {
        try (Writer w = Files.newBufferedWriter(aPath, StandardCharsets.UTF_8, WRITE, CREATE,
                TRUNCATE_EXISTING))
        {
            writeTo(aScenario, w);
        }
    }


    /**
     * Serialise the scenario to a string in extended-CDT format.
     */
    public static String toString(RuleTestScenario aScenario)
    {
        StringWriter sw = new StringWriter();
        try
        {
            writeTo(aScenario, sw);
        }
        catch (IOException e)
        {
            // StringWriter never throws.
            throw new IllegalStateException(e);
        }
        return sw.toString();
    }

    // ---- Writer internals ----------------------------------------------------------


    private static void writeTo(RuleTestScenario aScenario, Writer aOut) throws IOException
    {
        aOut.write(SHEBANG);
        aOut.write('\n');

        aOut.write("#test ");
        aOut.write(aScenario.getCoreId());
        aOut.write(" expect=");
        aOut.write(aScenario.getExpect().token());
        aOut.write(" domain=");
        aOut.write(quoteIfNeeded(aScenario.getDomain()));
        aOut.write('\n');

        String note = aScenario.getNote();
        if (note != null && !note.isBlank())
        {
            aOut.write("#note ");
            aOut.write(quoteIfNeeded(note));
            aOut.write('\n');
        }

        Integer count = aScenario.getExpectViolationCount();
        if (count != null)
        {
            aOut.write("#expectViolationCount ");
            aOut.write(Integer.toString(count));
            aOut.write('\n');
        }
        for (ExpectedViolation ev : sortExpectations(aScenario.getExpectedViolations()))
        {
            aOut.write("#expectViolationAt");
            if (ev.getRow() != null)
            {
                aOut.write(" row=");
                aOut.write(Integer.toString(ev.getRow()));
            }
            // Plan C: `severity=` is a RESERVED key, not a column constraint, so it has to be
            // emitted explicitly — the constraint loop below cannot carry it. Without this the
            // writer silently drops a pin the parser accepted, and any round-trip through
            // ScenarioTrimmer would lose it.
            if (ev.getSeverity() != null)
            {
                aOut.write(" severity=");
                aOut.write(ev.getSeverity().getJsonValue());
            }
            // TreeMap for a stable key order so back-filled / regenerated files diff cleanly.
            for (var e : new java.util.TreeMap<>(ev.getConstraints()).entrySet())
            {
                aOut.write(' ');
                aOut.write(quoteIfNeeded(e.getKey()));
                aOut.write('=');
                aOut.write(quoteIfNeeded(e.getValue()));
            }
            aOut.write('\n');
        }

        // #library-ref and inline #library are mutually exclusive (enforced at parse time), so emit
        // whichever the scenario carries.
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref != null)
        {
            writeLibraryRef(aOut, ref);
        }
        else
        {
            MapBackedLibraryMetadataProvider lib = aScenario.getLibrary();
            if (lib != null)
            {
                writeLibrary(aOut, lib);
            }
        }

        String defineXml = aScenario.getDefineXml();
        if (defineXml != null)
        {
            aOut.write("#define-xml ");
            aOut.write(quoteIfNeeded(defineXml));
            aOut.write('\n');
        }

        String dictionaries = aScenario.getDictionaries();
        if (dictionaries != null)
        {
            aOut.write("#dictionaries ");
            aOut.write(dictionaries);
            aOut.write('\n');
        }

        Severity runLevel = aScenario.getRunLevel();
        if (runLevel != null)
        {
            aOut.write("#runLevel ");
            aOut.write(runLevel.getJsonValue());
            aOut.write('\n');
        }

        boolean first = true;
        for (IDataTable t : aScenario.getDatasets())
        {
            if (!first)
            {
                aOut.write('\n');
            }
            first = false;
            net.cumba.datatable.provider.cdt.CdtWriter.writeDataset(t, null, null, null, aOut);
        }
    }


    /**
     * Order expectations deterministically — by row (rowless entries first), then by the
     * canonicalised constraint map — so back-filled / regenerated files produce stable diffs.
     */
    private static List<ExpectedViolation> sortExpectations(List<ExpectedViolation> aList)
    {
        List<ExpectedViolation> sorted = new ArrayList<>(aList);
        sorted.sort(java.util.Comparator
                .comparingInt((ExpectedViolation e) -> e.getRow() == null ? -1 : e.getRow())
                .thenComparing(e -> new java.util.TreeMap<>(e.getConstraints()).toString())
                .thenComparing(e -> e.getSeverity() == null ? "" : e.getSeverity().name()));
        return sorted;
    }


    /**
     * Emit a {@link LibraryRef} as a single {@code #library-ref} directive line. Mirrors the shape
     * accepted by {@link #buildLibraryRef(List, String)} so the scenario round-trips.
     */
    private static void writeLibraryRef(Writer aOut, LibraryRef aRef) throws IOException
    {
        aOut.write("#library-ref standard=");
        aOut.write(quoteIfNeeded(aRef.getStandard()));
        aOut.write(" version=");
        aOut.write(quoteIfNeeded(aRef.getVersion()));
        for (String ct : aRef.getCtPackages())
        {
            aOut.write(" ct=");
            aOut.write(quoteIfNeeded(ct));
        }
        writeOptional(aOut, "substandard", aRef.getSubstandard());
        aOut.write('\n');
    }


    /** Emit a {@code key=value} pair when the value is non-null; a no-op otherwise. */
    private static void writeOptional(Writer aOut, String aKey, @Nullable String aValue)
        throws IOException
    {
        if (aValue != null)
        {
            aOut.write(' ');
            aOut.write(aKey);
            aOut.write('=');
            aOut.write(quoteIfNeeded(aValue));
        }
    }


    /**
     * Emit the {@link MapBackedLibraryMetadataProvider} state as a series of
     * {@code #library <kind>} directive lines. Mirrors the shape accepted by
     * {@link #parseLibraryPayload(MapBackedLibraryMetadataProvider.Builder, List, String, int, String)}.
     */
    private static void writeLibrary(Writer aOut, MapBackedLibraryMetadataProvider aLib)
        throws IOException
    {
        // Always emit the scalar standard/version line so an otherwise-empty library (used
        // to signal "run rule with empty library metadata") still round-trips as non-null.
        aOut.write("#library");
        String standard = aLib.getStandard();
        if (standard != null)
        {
            aOut.write(" standard=");
            aOut.write(quoteIfNeeded(standard));
        }
        String version = aLib.getVersion();
        if (version != null)
        {
            aOut.write(" version=");
            aOut.write(quoteIfNeeded(version));
        }
        aOut.write('\n');
        writeStringListMap(aOut, "required-variables", aLib.getRequiredVariablesMap());
        writeStringListMap(aOut, "expected-variables", aLib.getExpectedVariablesMap());
        writeStringListMap(aOut, "column-order", aLib.getColumnOrderMap());
        writeStringListMap(aOut, "model-column-order", aLib.getModelColumnOrderMap());
        if (!aLib.getCustomDomainsSet().isEmpty())
        {
            aOut.write("#library custom-domain");
            for (String d : aLib.getCustomDomainsSet())
            {
                aOut.write(' ');
                aOut.write(quoteIfNeeded(d));
            }
            aOut.write('\n');
        }
        writeStringListMap(aOut, "codelist-terms", aLib.getCodelistTermsMap());
        for (var e : new java.util.TreeMap<>(aLib.getCodelistExtensibleMap()).entrySet())
        {
            aOut.write("#library codelist-extensible ");
            aOut.write(quoteIfNeeded(e.getKey()));
            aOut.write(' ');
            aOut.write(Boolean.TRUE.equals(e.getValue()) ? "true" : "false");
            aOut.write('\n');
        }
        for (var e : new java.util.TreeMap<>(aLib.getCodelistTermMappingsMap()).entrySet())
        {
            aOut.write("#library codelist-term-mappings ");
            aOut.write(quoteIfNeeded(e.getKey()));
            writeKeyValues(aOut, e.getValue());
            aOut.write('\n');
        }
        writeVarListMap(aOut, "domain-variables", aLib.getDomainVariablesMap());
        writeVarListMap(aOut, "model-variables", aLib.getModelVariablesMap());
        writeVarListMap(aOut, "model-class-variables", aLib.getModelClassVariablesMap());
        for (var de : new java.util.TreeMap<>(aLib.getVariableMetadataMap()).entrySet())
        {
            for (var ve : new java.util.TreeMap<>(de.getValue()).entrySet())
            {
                aOut.write("#library variable-metadata ");
                aOut.write(quoteIfNeeded(de.getKey()));
                aOut.write(' ');
                aOut.write(quoteIfNeeded(ve.getKey()));
                writeKeyValues(aOut, ve.getValue());
                aOut.write('\n');
            }
        }
        for (var de : new java.util.TreeMap<>(aLib.getCodelistCodesMap()).entrySet())
        {
            for (var ve : new java.util.TreeMap<>(de.getValue()).entrySet())
            {
                aOut.write("#library codelist-codes ");
                aOut.write(quoteIfNeeded(de.getKey()));
                aOut.write(' ');
                aOut.write(quoteIfNeeded(ve.getKey()));
                writeKeyValues(aOut, ve.getValue());
                aOut.write('\n');
            }
        }
        for (var e : new java.util.TreeMap<>(aLib.getDatasetMetadataMap()).entrySet())
        {
            java.util.Map<String, String> md = e.getValue();
            String cls = md.get("className");
            if (cls != null && md.size() == 1)
            {
                aOut.write("#library dataset-class ");
                aOut.write(quoteIfNeeded(e.getKey()));
                aOut.write(' ');
                aOut.write(quoteIfNeeded(cls));
                aOut.write('\n');
            }
            else if (!md.isEmpty())
            {
                aOut.write("#library dataset-metadata ");
                aOut.write(quoteIfNeeded(e.getKey()));
                writeKeyValues(aOut, md);
                aOut.write('\n');
            }
        }
        if (!aLib.getPublishedCtPackagesList().isEmpty())
        {
            aOut.write("#library published-ct-packages");
            for (String p : aLib.getPublishedCtPackagesList())
            {
                aOut.write(' ');
                aOut.write(quoteIfNeeded(p));
            }
            aOut.write('\n');
        }
        // Fix #147. Written whenever the list is NON-NULL, including when it is EMPTY: for
        // `known_domain_only` expansion, "declared as empty" (the library attests no domains) and
        // "not declared" (undecidable, skip with a reason) are different states, and a round-trip
        // that collapsed the first into the second would silently change a scenario's verdict.
        List<String> standardDomains = aLib.getStandardDatasetNames();
        if (standardDomains != null)
        {
            aOut.write("#library standard-domains");
            for (String d : standardDomains)
            {
                aOut.write(' ');
                aOut.write(quoteIfNeeded(d));
            }
            aOut.write('\n');
        }
    }


    private static void writeStringListMap(Writer aOut, String aKind,
            java.util.Map<String, List<String>> aMap)
        throws IOException
    {
        for (var e : aMap.entrySet())
        {
            aOut.write("#library ");
            aOut.write(aKind);
            aOut.write(' ');
            aOut.write(quoteIfNeeded(e.getKey()));
            for (String v : e.getValue())
            {
                aOut.write(' ');
                aOut.write(quoteIfNeeded(v));
            }
            aOut.write('\n');
        }
    }


    /**
     * Emit a {@code key=value} map as space-prefixed tokens, entries sorted by key for a stable
     * round-trip. Keys are simple identifiers (written verbatim); values are quoted when needed.
     */
    private static void writeKeyValues(Writer aOut, java.util.Map<String, String> aMap)
        throws IOException
    {
        for (var kv : new java.util.TreeMap<>(aMap).entrySet())
        {
            aOut.write(' ');
            // Quote the key too so a key with whitespace/quotes survives the round-trip. Keys
            // containing '=' cannot round-trip (parseKeyValues splits on the first '='); the YAML
            // loader rejects those, and inline directives can never produce one.
            aOut.write(quoteIfNeeded(kv.getKey()));
            aOut.write('=');
            aOut.write(quoteIfNeeded(kv.getValue()));
        }
    }


    private static void writeVarListMap(Writer aOut, String aKind,
            java.util.Map<String, List<java.util.Map<String, String>>> aMap)
        throws IOException
    {
        for (var e : aMap.entrySet())
        {
            aOut.write("#library ");
            aOut.write(aKind);
            aOut.write(' ');
            aOut.write(quoteIfNeeded(e.getKey()));
            for (java.util.Map<String, String> v : e.getValue())
            {
                aOut.write(' ');
                String name = v.getOrDefault("name", "");
                String role = v.getOrDefault("role", "");
                aOut.write(quoteIfNeeded(name + ":" + role));
            }
            aOut.write('\n');
        }
    }


    /**
     * Same semantics as {@code CdtWriter.quoteIfNeeded}, duplicated here so the writer stays
     * decoupled from the CDT provider. Any value containing whitespace, {@code =}, {@code "},
     * {@code '}, or that is empty, is wrapped in double quotes and backslash-escaped.
     */
    private static String quoteIfNeeded(String aValue)
    {
        if (aValue.isEmpty())
        {
            return "\"\"";
        }
        boolean needQuote = false;
        for (int i = 0; i < aValue.length(); i++)
        {
            char ch = aValue.charAt(i);
            if (Character.isWhitespace(ch) || ch == '=' || ch == '"' || ch == '\'')
            {
                needQuote = true;
                break;
            }
        }
        if (!needQuote)
        {
            return aValue;
        }
        String esc = aValue.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }

    // ---- Directive parsing ---------------------------------------------------------


    private static TestPayload parseTestPayload(List<String> aTokens, String aSource, int aLineIdx)
    {
        String coreId = null;
        Verdict expect = null;
        String domain = null;
        for (int i = 1; i < aTokens.size(); i++)
        {
            String tok = aTokens.get(i);
            int eq = tok.indexOf('=');
            if (eq < 0)
            {
                if (coreId != null)
                {
                    throw error(aSource, aLineIdx,
                            "#test: unexpected positional token after coreId: " + tok);
                }
                coreId = tok;
                continue;
            }
            String key = tok.substring(0, eq);
            String value = tok.substring(eq + 1);
            switch (key)
            {
            case "expect" ->
            {
                expect = Verdict.parse(value);
                if (expect == null)
                {
                    throw error(aSource, aLineIdx, "#test: invalid expect=" + value
                            + " (expected 'violation', 'noViolation' or 'skipped')");
                }
            }
            case "domain" ->
            {
                if (value.isEmpty())
                {
                    throw error(aSource, aLineIdx, "#test: empty domain=");
                }
                domain = value;
            }
            default -> throw error(aSource, aLineIdx,
                    "#test: unknown key '" + key + "' (expected 'expect' or 'domain')");
            }
        }
        if (coreId == null)
        {
            throw error(aSource, aLineIdx, "#test: missing coreId (first positional token)");
        }
        if (expect == null)
        {
            throw error(aSource, aLineIdx, "#test: missing required expect=");
        }
        if (domain == null)
        {
            throw error(aSource, aLineIdx, "#test: missing required domain=");
        }
        return new TestPayload(coreId, expect, domain);
    }


    /** Parse a {@code #expectViolationCount <N>} payload into a non-negative integer. */
    private static int parseExpectCount(List<String> aTokens, String aSource, int aLineIdx)
    {
        if (aTokens.size() != 2)
        {
            throw error(aSource, aLineIdx, "#expectViolationCount: expected a single integer");
        }
        try
        {
            int n = Integer.parseInt(aTokens.get(1));
            if (n < 0)
            {
                throw new NumberFormatException();
            }
            return n;
        }
        catch (NumberFormatException _)
        {
            throw error(aSource, aLineIdx,
                    "#expectViolationCount: not a non-negative integer: " + aTokens.get(1));
        }
    }


    /**
     * Parse a {@code #expectViolationAt [row=N] [severity=LEVEL] [COL=value ...]} directive into
     * one {@link ExpectedViolation}.
     *
     * <p>
     * <b>Two</b> reserved keys, both lowercase: {@code row} becomes
     * {@link ExpectedViolation#getRow()} and {@code severity} becomes
     * {@link ExpectedViolation#getSeverity()}. Every other {@code key=value} token is a column
     * constraint.
     * </p>
     *
     * <p>
     * ⛔⛔ {@code severity} <b>must</b> be reserved here. Left unreserved it falls through to
     * {@code constraints.put}, and {@code #expectViolationAt severity=ERROR} then silently declares
     * a constraint on a column named {@code severity} — matching nothing on most fixtures, and
     * matching the wrong thing on any fixture that has such a column. A test that only asserts the
     * parsed level would pass while the key <em>also</em> sat in {@code constraints}, so
     * {@code RuleTestCdtSeverityKeyTest} asserts {@code constraints.isEmpty()} explicitly.
     * </p>
     */
    private static ExpectedViolation parseExpectAt(List<String> aTokens, String aSource,
            int aLineIdx)
    {
        Integer row = null;
        Severity severity = null;
        java.util.Map<String, String> constraints = new java.util.LinkedHashMap<>();
        for (int i = 1; i < aTokens.size(); i++)
        {
            String tok = aTokens.get(i);
            int eq = tok.indexOf('=');
            if (eq < 0)
            {
                throw error(aSource, aLineIdx,
                        "#expectViolationAt: expected key=value, got '" + tok + "'");
            }
            String key = tok.substring(0, eq);
            String value = tok.substring(eq + 1);
            if (key.equals("row"))
            {
                if (row != null)
                {
                    throw error(aSource, aLineIdx, "#expectViolationAt: duplicate row=");
                }
                row = parseRow(value, aSource, aLineIdx);
            }
            else if (key.equals("severity"))
            {
                if (severity != null)
                {
                    throw error(aSource, aLineIdx, "#expectViolationAt: duplicate severity=");
                }
                severity = parseSeverity(value, aSource, aLineIdx);
            }
            else
            {
                constraints.put(key, value);
            }
        }
        // ⚑ severity= counts as a pin. Without this arm, reserving the key would turn the
        // previously-working spelling `#expectViolationAt severity=ERROR` into a parse error.
        if (row == null && severity == null && constraints.isEmpty())
        {
            throw error(aSource, aLineIdx,
                    "#expectViolationAt: needs at least one of row=, severity= or COL=value");
        }
        return ExpectedViolation.builder().row(row).constraints(constraints).severity(severity)
                .build();
    }


    /**
     * Parse a {@code severity=} value as one of the four authorable levels, case-insensitively.
     *
     * <p>
     * ⚠ Deliberately narrower than the report enum, which also carries {@code NOTICE} — a
     * report-only kind no rule authors and no scenario may pin.
     * </p>
     */
    private static Severity parseSeverity(String aValue, String aSource, int aLineIdx)
    {
        Severity s = Severity.parseOrNull(aValue);
        if (s == null || s == Severity.NOTICE)
        {
            throw error(aSource, aLineIdx, "#expectViolationAt: unknown severity '" + aValue
                    + "' (expected REJECT, ERROR, WARNING or INFO)");
        }
        return s;
    }


    /** Parse a {@code row=} value as a strict 1-based positive integer. */
    private static int parseRow(String aValue, String aSource, int aLineIdx)
    {
        try
        {
            int n = Integer.parseInt(aValue);
            if (n < 1)
            {
                throw new NumberFormatException();
            }
            return n;
        }
        catch (NumberFormatException _)
        {
            throw error(aSource, aLineIdx,
                    "#expectViolationAt: row= must be a 1-based integer, got '" + aValue + "'");
        }
    }


    /**
     * Parse a {@code #library <kind> ...} directive and fold it into the shared builder. Supported
     * kinds are the keyword forms below.
     *
     * <pre>{@code
     * #library standard=sdtmig version=3-4
     * #library required-variables    DOMAIN VAR VAR ...
     * #library required-variables-for-structure "STRUCTURE TOKEN" VAR VAR ...
     * #library expected-variables    DOMAIN VAR VAR ...
     * #library column-order          DOMAIN VAR VAR ...
     * #library model-column-order    DOMAIN VAR VAR ...
     * #library custom-domain         DOMAIN [DOMAIN ...]
     * #library codelist-terms        CODELIST TERM TERM ...
     * #library published-ct-packages PKG [PKG ...]
     * #library standard-domains      DOMAIN [DOMAIN ...]
     * #library dataset-class         DOMAIN CLASSNAME
     * #library domain-variables      DOMAIN NAME:ROLE NAME:ROLE ...
     * #library model-variables       DOMAIN NAME:ROLE NAME:ROLE ...
     * #library model-class-variables CLASS NAME:ROLE NAME:ROLE ...
     * #library codelist-codes        DOMAIN VARIABLE TERM=Cxxxxxx TERM=Cxxxxxx ...
     * }</pre>
     */
    private static void parseLibraryPayload(MapBackedLibraryMetadataProvider.Builder aBuilder,
            List<String> aTokens, String aSource, int aLineIdx, String aLabel)
    {
        if (aTokens.size() < 2)
        {
            throw error(aSource, aLineIdx, aLabel + ": missing kind (e.g. 'standard=')");
        }
        // A single-line form allowing multiple scalar key=value pairs:
        // #library standard=sdtmig version=3-4
        String second = aTokens.get(1);
        if (second.indexOf('=') >= 0)
        {
            for (int i = 1; i < aTokens.size(); i++)
            {
                String tok = aTokens.get(i);
                int eq = tok.indexOf('=');
                if (eq < 0)
                {
                    throw error(aSource, aLineIdx,
                            aLabel + ": expected key=value, got '" + tok + "'");
                }
                String k = tok.substring(0, eq);
                String v = tok.substring(eq + 1);
                switch (k)
                {
                case "standard" -> aBuilder.standard(v);
                case "version" -> aBuilder.version(v);
                default -> throw error(aSource, aLineIdx, aLabel + ": unknown scalar key '" + k
                        + "' (expected 'standard' or 'version')");
                }
            }
            return;
        }

        String kind = second;
        List<String> rest = aTokens.subList(2, aTokens.size());
        switch (kind)
        {
        case "required-variables" -> applyDomainVars(aBuilder::requiredVariables, rest, aSource,
                aLineIdx, kind);
        case "expected-variables" -> applyDomainVars(aBuilder::expectedVariables, rest, aSource,
                aLineIdx, kind);
        // Fix #368: keyed by ADaM DATA STRUCTURE. The token is multi-word, so it is quoted:
        // #library required-variables-for-structure "BASIC DATA STRUCTURE" PARAM PARAMCD
        case "required-variables-for-structure" -> applyDomainVars(
                aBuilder::requiredVariablesForStructure, rest, aSource, aLineIdx, kind);
        case "column-order" -> applyDomainVars(aBuilder::columnOrder, rest, aSource, aLineIdx,
                kind);
        case "model-column-order" -> applyDomainVars(aBuilder::modelColumnOrder, rest, aSource,
                aLineIdx, kind);
        case "custom-domain" ->
        {
            if (rest.isEmpty())
            {
                throw error(aSource, aLineIdx, aLabel + " custom-domain: missing domain");
            }
            for (String d : rest)
            {
                aBuilder.customDomain(d);
            }
        }
        case "codelist-terms" -> applyDomainVars(aBuilder::codelistTerms, rest, aSource, aLineIdx,
                kind);
        case "published-ct-packages" -> aBuilder.publishedCtPackages(rest.toArray(new String[0]));
        // Fix #147: the canonical dataset names an `Expansion: known_domain_only` filter reads.
        // Declaring the kind at all — even with no names — makes the filter decidable.
        case "standard-domains" -> aBuilder.standardDatasetNames(rest.toArray(new String[0]));
        case "dataset-class" ->
        {
            if (rest.size() != 2)
            {
                throw error(aSource, aLineIdx,
                        aLabel + " dataset-class: expected DOMAIN CLASSNAME");
            }
            aBuilder.datasetClass(rest.get(0), rest.get(1));
        }
        case "domain-variables" -> applyVarList(aBuilder::domainVariables, rest, aSource, aLineIdx,
                kind);
        case "model-variables" -> applyVarList(aBuilder::modelVariables, rest, aSource, aLineIdx,
                kind);
        // EC-85: CLASS-keyed where model-variables is DOMAIN-keyed; same NAME:ROLE payload,
        // `--` left unsubstituted for the executor's fallback path to resolve.
        case "model-class-variables" -> applyVarList(aBuilder::modelClassVariables, rest, aSource,
                aLineIdx, kind);
        case "codelist-extensible" ->
        {
            if (rest.size() != 2)
            {
                throw error(aSource, aLineIdx,
                        aLabel + " codelist-extensible: expected CODELIST true|false");
            }
            aBuilder.codelistExtensible(rest.get(0),
                    parseBool(rest.get(1), aSource, aLineIdx, aLabel));
        }
        case "codelist-term-mappings" ->
        {
            if (rest.isEmpty())
            {
                throw error(aSource, aLineIdx,
                        aLabel + " codelist-term-mappings: missing codelist");
            }
            aBuilder.codelistTermMappings(rest.get(0),
                    parseKeyValues(rest.subList(1, rest.size()), aSource, aLineIdx, kind));
        }
        case "variable-metadata" ->
        {
            if (rest.size() < 2)
            {
                throw error(aSource, aLineIdx,
                        aLabel + " variable-metadata: expected DOMAIN VAR key=value ...");
            }
            aBuilder.variableMetadata(rest.get(0), rest.get(1),
                    parseKeyValues(rest.subList(2, rest.size()), aSource, aLineIdx, kind));
        }
        // CT2003: the (domain, variable) submission-value -> NCI concept-id map behind
        // library_variable_code_pair_matches. Same DOMAIN VAR key=value shape as
        // variable-metadata; declaring it twice for one (domain, variable) replaces the map.
        case "codelist-codes" ->
        {
            if (rest.size() < 2)
            {
                throw error(aSource, aLineIdx,
                        aLabel + " codelist-codes: expected DOMAIN VAR TERM=CODE ...");
            }
            aBuilder.codelistCodes(rest.get(0), rest.get(1),
                    parseKeyValues(rest.subList(2, rest.size()), aSource, aLineIdx, kind));
        }
        case "dataset-metadata" ->
        {
            if (rest.isEmpty())
            {
                throw error(aSource, aLineIdx, aLabel + " dataset-metadata: missing domain");
            }
            aBuilder.datasetMetadata(rest.get(0),
                    parseKeyValues(rest.subList(1, rest.size()), aSource, aLineIdx, kind));
        }
        default -> throw error(aSource, aLineIdx, aLabel + ": unknown kind '" + kind + "'");
        }
    }


    /**
     * Parse a strict {@code true}/{@code false} token (case-insensitive). Any other value is a
     * directive error.
     */
    private static boolean parseBool(String aTok, String aSource, int aLineIdx, String aLabel)
    {
        return switch (aTok.toLowerCase(Locale.ROOT))
        {
        case "true" -> true;
        case "false" -> false;
        default -> throw error(aSource, aLineIdx,
                aLabel + ": expected true|false, got '" + aTok + "'");
        };
    }


    /**
     * Parse a list of {@code key=value} tokens into an insertion-ordered map. Each token must
     * contain at least one {@code '='}; the split is on the <em>first</em> {@code '='} so values
     * may themselves contain {@code '='}. A token without {@code '='} is a directive error.
     */
    private static java.util.Map<String, String> parseKeyValues(List<String> aToks, String aSource,
            int aLineIdx, String aKind)
    {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String tok : aToks)
        {
            int eq = tok.indexOf('=');
            if (eq < 0)
            {
                throw error(aSource, aLineIdx,
                        "#library " + aKind + ": expected key=value, got '" + tok + "'");
            }
            m.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return m;
    }

    @FunctionalInterface
    private interface DomainVarsFn
    {

        void apply(String aDomain, String... aVars);
    }

    private static void applyDomainVars(DomainVarsFn aFn, List<String> aRest, String aSource,
            int aLineIdx, String aKind)
    {
        if (aRest.isEmpty())
        {
            throw error(aSource, aLineIdx, "#library " + aKind + ": missing domain");
        }
        String domain = aRest.get(0);
        String[] vars = aRest.subList(1, aRest.size()).toArray(new String[0]);
        aFn.apply(domain, vars);
    }

    @FunctionalInterface
    private interface VarListFn
    {

        void apply(String aDomain, List<java.util.Map<String, String>> aVars);
    }

    private static void applyVarList(VarListFn aFn, List<String> aRest, String aSource,
            int aLineIdx, String aKind)
    {
        if (aRest.isEmpty())
        {
            throw error(aSource, aLineIdx, "#library " + aKind + ": missing "
                    + (aKind.startsWith("model-class") ? "class" : "domain"));
        }
        String domain = aRest.get(0);
        List<java.util.Map<String, String>> vars = new ArrayList<>();
        for (int i = 1; i < aRest.size(); i++)
        {
            String tok = aRest.get(i);
            int colon = tok.indexOf(':');
            if (colon < 0)
            {
                throw error(aSource, aLineIdx,
                        "#library " + aKind + ": expected NAME:ROLE, got '" + tok + "'");
            }
            vars.add(MapBackedLibraryMetadataProvider.var(tok.substring(0, colon),
                    tok.substring(colon + 1)));
        }
        aFn.apply(domain, vars);
    }


    /**
     * Parse the {@code #define-xml} payload: exactly one value token — the sidecar file name,
     * resolved by the runner against the scenario file's directory.
     */
    private static String parseDefineXmlPayload(List<String> aTokens, String aSource, int aLineIdx)
    {
        if (aTokens.size() != 2)
        {
            throw error(aSource, aLineIdx,
                    "#define-xml expects exactly one file name, e.g. '#define-xml my-define.xml'");
        }
        return aTokens.get(1);
    }


    /**
     * Parse the {@code #dictionaries} payload: exactly one value token naming the dictionary
     * bundle. Only {@code dummy} (the checked-in dummy dictionaries) is supported.
     */
    private static String parseDictionariesPayload(List<String> aTokens, String aSource,
            int aLineIdx)
    {
        if (aTokens.size() != 2)
        {
            throw error(aSource, aLineIdx,
                    "#dictionaries expects exactly one value, e.g. '#dictionaries dummy'");
        }
        String value = aTokens.get(1);
        if (!"dummy".equals(value))
        {
            throw error(aSource, aLineIdx,
                    "unsupported #dictionaries value: " + value + " (only 'dummy' is supported)");
        }
        return value;
    }


    /**
     * Parse a {@code #runLevel <LEVEL>} directive — the scenario's <b>run severity threshold</b>
     * (Plan C &#167;3.4, ruling 4): the weakest check level this scenario evaluates.
     *
     * <p>
     * The {@code .cdt} face of the CLI's {@code --severity-level} and the REST
     * {@code CheckRunRequest.severityThreshold}; all three set the same value. A scenario that
     * declares none runs at the engine default, {@code Warning} — so every baseline in this suite
     * is a default-threshold baseline, and a scenario that pins a non-default one has to say so.
     * </p>
     *
     * @param aTokens
     *            the directive's tokens, {@code ["runLevel", "<LEVEL>"]}
     * @param aSource
     *            source label for error messages
     * @param aLineIdx
     *            0-based line index for error messages
     * @return the parsed threshold
     */
    private static Severity parseRunLevelPayload(List<String> aTokens, String aSource, int aLineIdx)
    {
        if (aTokens.size() != 2)
        {
            throw error(aSource, aLineIdx,
                    "#runLevel expects exactly one value, e.g. '#runLevel Error'");
        }
        Severity level = Severity.parseOrNull(aTokens.get(1));
        if (level == null || level == Severity.NOTICE)
        {
            throw error(aSource, aLineIdx, "#runLevel: unknown level '" + aTokens.get(1)
                    + "' (expected Reject, Error, Warning or Info)");
        }
        return level;
    }


    private static String parseNotePayload(List<String> aTokens, String aSource, int aLineIdx)
    {
        if (aTokens.size() < 2)
        {
            throw error(aSource, aLineIdx, "#note: missing text");
        }
        // Allow unquoted multi-word notes: join remaining tokens with single space. Quoted
        // notes come through as a single token unchanged, so round-trip via the writer
        // (which always quotes values containing whitespace) preserves the exact text.
        if (aTokens.size() == 2)
        {
            return aTokens.get(1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < aTokens.size(); i++)
        {
            if (i > 1)
            {
                sb.append(' ');
            }
            sb.append(aTokens.get(i));
        }
        return sb.toString();
    }

    // ---- Lexer ---------------------------------------------------------------------


    /**
     * Whitespace-separated tokenizer with double-quote grouping and backslash escapes inside
     * quotes. Mirrors the tokenizer in {@code CdtParser} so directive values use the same quoting
     * rules as CDT dataset/col lines.
     */
    private static List<String> tokenize(String aLine, String aSource, int aLineIdx)
    {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean sawAnything = false;

        for (int i = 0; i < aLine.length(); i++)
        {
            char c = aLine.charAt(i);
            if (c == '"')
            {
                inQuotes = !inQuotes;
                sawAnything = true;
                continue;
            }
            if (inQuotes)
            {
                if (c == '\\' && i + 1 < aLine.length())
                {
                    cur.append(aLine.charAt(++i));
                    sawAnything = true;
                    continue;
                }
                cur.append(c);
                sawAnything = true;
                continue;
            }
            if (Character.isWhitespace(c))
            {
                if (sawAnything)
                {
                    tokens.add(stripTrailing(cur.toString()));
                    cur.setLength(0);
                    sawAnything = false;
                }
                continue;
            }
            cur.append(c);
            sawAnything = true;
        }
        if (inQuotes)
        {
            throw error(aSource, aLineIdx, "unterminated quoted string");
        }
        if (sawAnything)
        {
            tokens.add(stripTrailing(cur.toString()));
        }
        return tokens;
    }


    private static String stripTrailing(String aValue)
    {
        int end = aValue.length();
        while (end > 0 && Character.isWhitespace(aValue.charAt(end - 1)))
        {
            end--;
        }
        return end == aValue.length() ? aValue : aValue.substring(0, end);
    }

    // ---- Misc helpers --------------------------------------------------------------


    private static int firstNonEmpty(String[] aLines)
    {
        for (int i = 0; i < aLines.length; i++)
        {
            if (!aLines[i].trim().isEmpty())
            {
                return i;
            }
        }
        return -1;
    }


    private static String stripLeadingBom(String aContent)
    {
        if (!aContent.isEmpty() && aContent.charAt(0) == '\uFEFF')
        {
            return aContent.substring(1);
        }
        return aContent;
    }


    private static RuleTestCdtException error(String aSource, int aLineIdx, String aMessage)
    {
        return new RuleTestCdtException(aSource + ":" + (aLineIdx + 1) + ": " + aMessage);
    }

    // ---- Small carriers ------------------------------------------------------------

    private record Directive(int lineIdx, String body)
    {
    }


    private record TestPayload(String coreId, Verdict expect, String domain)
    {
    }


    /**
     * A buffered inline {@code #library} directive (tokens + source line) applied after parsing.
     */
    private record LibLine(List<String> tokens, int lineIdx)
    {
    }


    /** A buffered {@code #library-include} sidecar reference (path + source line). */
    private record IncludeRef(String path, int lineIdx)
    {
    }
}
