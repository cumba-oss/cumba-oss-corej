package net.cumba.cdisc.define.conformance.eval;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.cumba.cdisc.define.conformance.ct.CtProvider;
import net.cumba.cdisc.define.conformance.library.LibraryProvider;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import org.jspecify.annotations.Nullable;

/**
 * Everything one validation run's rule evaluations share: the normalised document tree, the
 * memoised whole-document node scan, the OID index, the detected Define-XML version, and the
 * optional external inputs (plan §3.6).
 */
public final class DocumentContext
{

    /** Synthetic scope name for document-level rules ({@code Element: "Document"}). */
    public static final String DOCUMENT_SCOPE = "Document";

    private final ElementNode root;

    private final ElementNode documentNode;

    private final List<ElementNode> allNodes;

    private final OidResolver oidResolver;

    private final String defineVersion;

    @Nullable
    private final CtProvider ctProvider;

    @Nullable
    private final Path submissionFolder;

    private final List<String> stylesheetHrefs;

    @Nullable
    private final LibraryProvider libraryProvider;

    /**
     * @param aRoot
     *            the tree root (the {@code ODM} element)
     * @param aDefineVersion
     *            the detected Define-XML version, {@code "2.0"} or {@code "2.1"}
     * @param aCtProvider
     *            optional CT lookup; {@code null} ⇒ {@code Requires: ct} rules SKIP
     * @param aSubmissionFolder
     *            optional submission folder; {@code null} ⇒ {@code Requires: folder} rules SKIP
     */
    public DocumentContext(ElementNode aRoot, String aDefineVersion,
            @Nullable CtProvider aCtProvider, @Nullable Path aSubmissionFolder)
    {
        this(aRoot, aDefineVersion, aCtProvider, aSubmissionFolder, List.of(), null);
    }


    /**
     * As {@link #DocumentContext(ElementNode, String, CtProvider, Path)}, additionally carrying the
     * {@code href} values of the document's {@code <?xml-stylesheet?>} processing instructions —
     * prolog content the {@link ElementNode} tree cannot represent, extracted from the DOM via
     * {@link net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder#stylesheetHrefs} (the
     * {@code stylesheet_file_exists} kind's input, PMDA DD0085).
     */
    public DocumentContext(ElementNode aRoot, String aDefineVersion,
            @Nullable CtProvider aCtProvider, @Nullable Path aSubmissionFolder,
            List<String> aStylesheetHrefs)
    {
        this(aRoot, aDefineVersion, aCtProvider, aSubmissionFolder, aStylesheetHrefs, null);
    }


    /**
     * The full-input constructor, additionally carrying the optional {@link LibraryProvider};
     * {@code null} ⇒ {@code Requires: library} rules SKIP ({@code SKIPPED_MISSING_LIBRARY}).
     */
    public DocumentContext(ElementNode aRoot, String aDefineVersion,
            @Nullable CtProvider aCtProvider, @Nullable Path aSubmissionFolder,
            List<String> aStylesheetHrefs, @Nullable LibraryProvider aLibraryProvider)
    {
        root = aRoot;
        // A synthetic node above the root so document-level rules (e.g. "Element ODM must be
        // provided") have a scope to anchor an exists-check on.
        documentNode = SyntheticNodes.document(aRoot);
        allNodes = List.copyOf(documentNode.selfAndDescendants());
        oidResolver = new OidResolver(allNodes);
        defineVersion = aDefineVersion;
        ctProvider = aCtProvider;
        submissionFolder = aSubmissionFolder;
        stylesheetHrefs = List.copyOf(aStylesheetHrefs);
        libraryProvider = aLibraryProvider;
    }


    public ElementNode root()
    {
        return root;
    }


    /** The synthetic document node (localName {@code "Document"}) whose only child is the root. */
    public ElementNode documentNode()
    {
        return documentNode;
    }


    /** Every node in the document (including the synthetic document node), depth-first. */
    public List<ElementNode> allNodes()
    {
        return allNodes;
    }


    public OidResolver oidResolver()
    {
        return oidResolver;
    }


    /** The detected Define-XML version, {@code "2.0"} or {@code "2.1"}. */
    public String defineVersion()
    {
        return defineVersion;
    }


    public Optional<CtProvider> ctProvider()
    {
        return Optional.ofNullable(ctProvider);
    }


    public Optional<Path> submissionFolder()
    {
        return Optional.ofNullable(submissionFolder);
    }


    /** The {@code href} values of the document's {@code <?xml-stylesheet?>} prolog PIs. */
    public List<String> stylesheetHrefs()
    {
        return stylesheetHrefs;
    }


    public Optional<LibraryProvider> libraryProvider()
    {
        return Optional.ofNullable(libraryProvider);
    }

}
