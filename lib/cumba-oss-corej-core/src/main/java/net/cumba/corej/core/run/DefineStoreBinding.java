package net.cumba.corej.core.run;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.CustomLog;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.corej.core.gen.CtStandardRef;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreCtProvider;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreLibraryProvider;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.define.conformance.ct.CtProvider;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import org.jspecify.annotations.Nullable;

/**
 * What a Define-XML conformance run binds from the unified metadata store, and why
 * (PLAN-define-ct-evaluation §4.2): the CT (owner ruling D1 through {@link CtSelection}; D3 for a
 * named package the store lacks) and the IG library (§4.4). One store open serves both halves, on
 * every surface — the CLI's {@code -vx} and the data browser's define check resolve their binding
 * here and differ only in where the user's CT list comes from ({@code -ct} / the "CT Packages"
 * field).
 *
 * <p>
 * <b>The CT half.</b> {@link CtSelection#resolve}: a non-empty user list wins outright; else the
 * define's {@code def:Standards} declaration (D-8: parsed with the dataset run's own parser, a
 * parse failure meaning "declares nothing", with a WARNING); else nothing is named and the CT rules
 * SKIP, visibly, on the basis line (T1-4 a). A named package the store does not hold ABORTS the run
 * with the same {@link StudyValidationException} and the same wording the dataset run uses (T1-2 a,
 * {@link CtPresence}) — the escape hatch is the CT field on every surface. The bound packages are
 * merged in D-2 order: every {@code define-xmlct} package first (the explicit-c-code rules name
 * Define-XML CT codelists), then the other roots in first-appearance order, newest version first
 * within a root; first wins per codelist.
 * </p>
 *
 * <p>
 * <b>The library half</b> binds whenever the store opens, whatever the CT selection says — a 2.0
 * define with no CT still gets its library rules (T1-7 a).
 * </p>
 *
 * <p>
 * <b>What does NOT abort:</b> no store configured, or a store that does not open — that is the R2
 * degraded run the dataset run applies too, and both halves stay unbound with the reason on their
 * basis lines. The ONE exception is an old-format store, which aborts on every surface (T1-10 a)
 * with the store's neutral message; each surface appends its own remedy.
 * </p>
 *
 * <p>
 * Hold the binding in try-with-resources for the length of the engine run: {@link MetadataStore} is
 * fully in memory today and {@link #close()} releases nothing, but a later lazy format stays
 * correct without an API change.
 * </p>
 */
@CustomLog
public final class DefineStoreBinding implements AutoCloseable
{

    private static final String DEFINE_XML_ROOT = "define-xml";

    private final CtSelection selection;

    private final @Nullable StoreCtProvider ctProvider;

    private final @Nullable String ctUnboundReason;

    private final @Nullable StoreLibraryProvider libraryProvider;

    private final @Nullable String libraryUnboundReason;

    private final @Nullable MetadataStore store;

    private final @Nullable Path storeFile;

    private DefineStoreBinding(CtSelection aSelection, @Nullable StoreCtProvider aCtProvider,
            @Nullable String aCtUnboundReason, @Nullable StoreLibraryProvider aLibraryProvider,
            @Nullable String aLibraryUnboundReason, @Nullable MetadataStore aStore,
            @Nullable Path aStoreFile)
    {
        selection = aSelection;
        ctProvider = aCtProvider;
        ctUnboundReason = aCtUnboundReason;
        libraryProvider = aLibraryProvider;
        libraryUnboundReason = aLibraryUnboundReason;
        store = aStore;
        storeFile = aStoreFile;
    }


    /**
     * Resolves the binding for one define run.
     *
     * @param aDefineXml
     *            the document under validation (read for its {@code def:Standards})
     * @param aUserIds
     *            the surface's explicit CT list ({@code -ct}, the CT Packages field); empty means
     *            "the define's declaration"
     * @param aStoreFile
     *            the resolved store, or {@code null} when none is configured
     * @return the binding — hold it in try-with-resources for the run
     * @throws StudyValidationException
     *             when a named CT package is not held by the store or is malformed (D3, T1-2 a), or
     *             when the store is of another format (T1-10 a); the message is the store's neutral
     *             one and the typed {@link StoreFormatException} travels as its cause
     */
    public static DefineStoreBinding resolve(Path aDefineXml, List<String> aUserIds,
            @Nullable Path aStoreFile)
    {
        CtSelection selection = CtSelection.resolve(aUserIds, declaredCt(aDefineXml));
        if (aStoreFile == null)
        {
            String reason = "no metadata store is configured";
            return new DefineStoreBinding(selection, null, reason, null, reason, null, null);
        }
        MetadataStore opened;
        try
        {
            opened = MetadataStore.open(aStoreFile);
        }
        catch (StoreFormatException e)
        {
            // T1-10 (a): the one open failure that is never R2 — abort, neutrally worded.
            throw new StudyValidationException(e.describe(), e);
        }
        catch (IOException e)
        {
            // R2, as the dataset run (StudyValidationService.tryStoreProvider): a store that does
            // not open degrades the run, and the reason is on both basis lines.
            String reason = "the metadata store at " + aStoreFile + " cannot be opened ("
                    + e.getMessage() + ")";
            LOGGER.log(System.Logger.Level.WARNING,
                    "Metadata store {0} cannot be opened for the Define-XML run ({1}); the CT"
                            + " and library rules will SKIP.",
                    aStoreFile, e.getMessage());
            return new DefineStoreBinding(selection, null, reason, null, reason, null, null);
        }
        StoreLibraryProvider library = StoreLibraryProvider.over(opened);
        if (selection.source() == CtSelection.Source.NONE)
        {
            return new DefineStoreBinding(selection, null,
                    "no CT package is named (the define declares none in def:Standards, and none"
                            + " was given explicitly)",
                    library, null, opened, aStoreFile);
        }
        // T1-2 (a), RULED: D3 as the dataset run applies it — a named id that is not PRESENT (or
        // is MALFORMED) ABORTS, naming it, through the same helper and the same two messages as
        // StudyValidationService.requireNamedCtPackagesPresent.
        try
        {
            CtPresence.requireNamedPresent(opened::presence, aStoreFile, selection.packageIds(),
                    selection.source());
        }
        catch (StudyValidationException e)
        {
            opened.close();
            throw e;
        }
        return new DefineStoreBinding(selection,
                StoreCtProvider.of(opened, precedenceOrder(selection.packageIds())), null, library,
                null, opened, aStoreFile);
    }


    /** The run's CT selection (D1). */
    public CtSelection selection()
    {
        return selection;
    }


    /** The bound CT, or empty when the CT rules skip ({@link #basis()} says why). */
    public Optional<CtProvider> ctProvider()
    {
        return Optional.ofNullable(ctProvider);
    }


    /** The bound library, or empty when the library rules skip ({@link #libraryBasis()}). */
    public Optional<LibraryProvider> libraryProvider()
    {
        return Optional.ofNullable(libraryProvider);
    }


    /** One line for a report or stderr: the bound CT ids and their source, or why none. */
    public String basis()
    {
        if (ctProvider == null)
        {
            return "CT basis: none - " + ctUnboundReason
                    + "; every Requires: ct rule is skipped (SKIPPED_MISSING_CT).";
        }
        return "CT basis: " + String.join(", ", ctProvider.packageIds()) + " ("
                + ctProvider.codelistCount() + " codelists; source: " + describe(selection.source())
                + ") from " + storeFile
                + "; a CT rule this selection cannot serve stays skipped (SKIPPED_MISSING_CT).";
    }


    /**
     * The library half: the store and its provenance, plus any product the run asked for that the
     * store does not hold (D-14) — so call it AFTER the engine ran.
     */
    public String libraryBasis()
    {
        if (libraryProvider == null || store == null)
        {
            return "Library basis: none - " + libraryUnboundReason
                    + "; every Requires: library rule is skipped (SKIPPED_MISSING_LIBRARY).";
        }
        StringBuilder line = new StringBuilder("Library basis: store ").append(storeFile)
                .append(" (seeded ").append(provenance(store)).append(")");
        List<String> missed = libraryProvider.missedProducts();
        if (!missed.isEmpty())
        {
            line.append("; products not held: ").append(String.join(", ", missed))
                    .append(" - the library rules answered empty for them, so this report"
                            + " under-reports");
        }
        return line.append('.').toString();
    }


    @Override
    public void close()
    {
        if (store != null)
        {
            store.close();
        }
    }


    /**
     * D-2: {@code define-xmlct} packages first, then the other roots in first-appearance order,
     * newest version first within a root. Package-private for its test.
     */
    static List<String> precedenceOrder(List<String> aIds)
    {
        Map<String, List<String>> byRoot = new LinkedHashMap<>();
        for (String id : aIds)
        {
            int at = id.indexOf("ct-");
            String root = at > 0 ? id.substring(0, at) : id;
            byRoot.computeIfAbsent(root, r -> new ArrayList<>()).add(id);
        }
        List<String> ordered = new ArrayList<>(aIds.size());
        List<String> defineXml = byRoot.remove(DEFINE_XML_ROOT);
        if (defineXml != null)
        {
            ordered.addAll(newestFirst(defineXml));
        }
        for (List<String> root : byRoot.values())
        {
            ordered.addAll(newestFirst(root));
        }
        return List.copyOf(ordered);
    }


    /** Ids end in an ISO date, so reverse lexical order is newest first. */
    private static List<String> newestFirst(List<String> aIds)
    {
        List<String> sorted = new ArrayList<>(aIds);
        sorted.sort(java.util.Comparator.reverseOrder());
        return sorted;
    }


    /**
     * D-8: the define's declared CT, read with the parser the dataset run already uses. A parse
     * failure means "declares nothing" — the conformance engine reports an unparseable document
     * itself, so CT binding must never be what throws on a broken file.
     */
    private static List<CtStandardRef> declaredCt(Path aDefineXml)
    {
        try
        {
            return new OdmDefineXMLProvider(
                    new DefineXmlParser().parse(new File(aDefineXml.toString())))
                            .declaredCtPackages();
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Define-XML {0} could not be parsed for its def:Standards ({1}); it is treated"
                            + " as declaring no CT package.",
                    aDefineXml, e.getMessage());
            return List.of();
        }
    }


    private static String describe(CtSelection.Source aSource)
    {
        return switch (aSource)
        {
        case USER -> "explicit selection";
        case DEFINE -> "the define's def:Standards declaration";
        case NONE -> "none";
        };
    }


    private static String provenance(MetadataStore aStore)
    {
        List<String> parts = new ArrayList<>();
        for (StoreProvenance p : aStore.manifest().provenance())
        {
            parts.add((p.source() + " " + p.ref() + " " + p.fetchedAt()).trim());
        }
        return parts.isEmpty() ? "unknown provenance" : String.join("; ", parts);
    }
}
