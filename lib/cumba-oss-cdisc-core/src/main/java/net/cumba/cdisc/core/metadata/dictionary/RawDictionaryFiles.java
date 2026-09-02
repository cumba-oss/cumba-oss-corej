package net.cumba.cdisc.core.metadata.dictionary;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The little that every vendor distribution has in common: finding a file whose name carries a
 * release token, and reading a delimited text file that is not guaranteed to be clean UTF-8.
 */
final class RawDictionaryFiles
{

    /** What {@link CodingErrorAction#REPLACE} substitutes for an undecodable byte sequence. */
    private static final char REPLACEMENT = '\uFFFD';

    /**
     * The Unicode byte-order mark. Several real vendor files (the MED-RT release notes,
     * {@code MEDRT.txt}, MedDRA {@code .asc} exports, {@code Loinc.csv} re-saved on Windows) begin
     * with one; it survives {@code trim()} (which strips only up to {@code U+0020}) and
     * {@code isBlank()}, so left in place it silently corrupts whatever reads the first line \u2014
     * a version string, the first row's code, or a header-column lookup.
     */
    private static final char BOM = '\uFEFF';

    /**
     * Replacement characters seen by every reader opened via {@link #reader} on the current thread
     * since the last {@link #resetReplacementCount()}. Thread-confined rather than global because
     * an install converts one dictionary at a time on the calling thread, and a concurrent install
     * on another thread must not inflate this one's count.
     */
    private static final ThreadLocal<AtomicLong> REPLACEMENTS = ThreadLocal
            .withInitial(AtomicLong::new);

    /**
     * Term spellings a converter refused because of a case collision, tallied on the converting
     * thread since the last {@link #resetDroppedTermCount()} — the same thread-confined design as
     * {@link #REPLACEMENTS}, for the same reason.
     */
    private static final ThreadLocal<AtomicLong> DROPPED = ThreadLocal.withInitial(AtomicLong::new);

    private RawDictionaryFiles()
    {
    }


    /**
     * Opens a text file for reading.
     *
     * <p>
     * Vendor distributions are not uniformly clean UTF-8, and an undecodable byte anywhere in a 171
     * 912-row file would otherwise abort the whole conversion. A replacement character in one
     * concept name costs that one term; a {@code MalformedInputException} costs the dictionary.
     * </p>
     *
     * <p>
     * ⚠ The replacement must never be <em>silent</em>: a substituted character produces a term that
     * passes the case contract yet matches no real data. Every {@code U+FFFD} delivered by a reader
     * this method opened is therefore tallied on the opening thread, and the installer surfaces the
     * count as an {@link InstallReport} warning. A literal {@code U+FFFD} already present in the
     * raw bytes counts too — it is indistinguishable after decoding, and is itself the residue of
     * an earlier encoding accident, so warning on it is just as warranted.
     * </p>
     *
     * <p>
     * A single leading {@code U+FEFF} (a UTF-8 byte-order mark) is skipped, so every converter
     * inherits the fix: without it the BOM lands in the first token the converter reads — a MED-RT
     * version becomes an untypeable directory name, the first {@code llt.asc} code drops out of
     * every join, a {@code LOINC_NUM} header lookup fails while blaming the column. The BOM is a
     * deliberate encoding signature, not a decoding accident, so it is <em>not</em> tallied as a
     * replacement.
     * </p>
     */
    static BufferedReader reader(Path aFile) throws IOException
    {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        Reader raw = new InputStreamReader(Files.newInputStream(aFile), decoder);
        BufferedReader in = new BufferedReader(
                new ReplacementCountingReader(raw, REPLACEMENTS.get()));
        in.mark(1);
        if (in.read() != BOM)
        {
            in.reset();
        }
        return in;
    }


    /** Starts a fresh replacement tally for the current thread — one per conversion. */
    static void resetReplacementCount()
    {
        REPLACEMENTS.get().set(0);
    }


    /**
     * Replacement characters delivered on this thread since the last
     * {@link #resetReplacementCount()}.
     */
    static long replacementCount()
    {
        return REPLACEMENTS.get().get();
    }


    /**
     * Records that a converter refused to publish a term spelling because its case collides with an
     * already-published one. The refusal itself is correct — rewriting either spelling would assert
     * a form the vendor never wrote — but it must never be <em>silent</em>: a concept whose only
     * synonym is refused ends up code-only (in {@code levels.*CD} but in neither the name level nor
     * {@code pairs}), so data reporting that spelling is flagged even though the vendor publishes
     * it. The installer surfaces the tally as an {@link InstallReport} warning, exactly as the
     * replacement-character count is.
     */
    static void countDroppedTerm()
    {
        DROPPED.get().incrementAndGet();
    }


    /** Starts a fresh dropped-term tally for the current thread — one per conversion. */
    static void resetDroppedTermCount()
    {
        DROPPED.get().set(0);
    }


    /**
     * Term spellings refused on this thread since the last {@link #resetDroppedTermCount()}.
     */
    static long droppedTermCount()
    {
        return DROPPED.get().get();
    }

    /**
     * Counts every {@code U+FFFD} that passes through. Only {@code read(char[], int, int)} is
     * overridden; that is sufficient because {@link #reader} always wraps this in a
     * {@link BufferedReader}, whose every read — single-char, {@code readLine}, {@code skip},
     * {@code lines} — fills its buffer through {@code read(char[], int, int)} and never calls the
     * single-char {@code read()} on the underlying reader.
     */
    private static final class ReplacementCountingReader extends FilterReader
    {

        private final AtomicLong tally;

        ReplacementCountingReader(Reader aIn, AtomicLong aTally)
        {
            super(aIn);
            tally = aTally;
        }


        @Override
        public int read(char[] aBuffer, int aOffset, int aLength) throws IOException
        {
            int n = super.read(aBuffer, aOffset, aLength);
            for (int i = 0; i < n; i++)
            {
                if (aBuffer[aOffset + i] == REPLACEMENT)
                {
                    tally.incrementAndGet();
                }
            }
            return n;
        }
    }

    /**
     * The first file in {@code aDir} matching {@code aGlob} in name order, or {@code null} when
     * there is none. Ordered so that a directory holding two releases converts reproducibly rather
     * than by directory-iteration accident.
     */
    static @Nullable Path firstMatching(Path aDir, String aGlob) throws IOException
    {
        if (!Files.isDirectory(aDir))
        {
            return null;
        }
        List<Path> hits = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aDir, aGlob))
        {
            stream.forEach(hits::add);
        }
        return hits.stream().sorted().findFirst().orElse(null);
    }


    /**
     * The single file in {@code aDir} matching {@code aGlob}, {@code null} when there is none — and
     * an {@link IOException} when there are several. For a converter that derives its data and its
     * version from files matched by <em>different</em> globs, "first in name order" is not good
     * enough: two releases unpacked into one directory would pair one release's data with the
     * other's version stamp, silently. Refusing forces the operator to keep one release per raw
     * directory.
     */
    static @Nullable Path soleMatching(Path aDir, String aGlob) throws IOException
    {
        if (!Files.isDirectory(aDir))
        {
            return null;
        }
        List<Path> hits = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aDir, aGlob))
        {
            stream.forEach(hits::add);
        }
        if (hits.size() > 1)
        {
            throw new IOException("several files in " + aDir + " match " + aGlob + ": "
                    + hits.stream().map(RawDictionaryFiles::fileNameOf).sorted().toList()
                    + " — two releases in one directory cannot be converted deterministically; "
                    + "keep exactly one");
        }
        return hits.isEmpty() ? null : hits.get(0);
    }


    /** Splits one line of a tab-separated file, keeping trailing empty cells. */
    static String[] cells(String aLine)
    {
        return aLine.split("\t", -1);
    }


    /**
     * The path's file name as a string, or {@code ""} for a path that has none (a filesystem root).
     * {@link Path#getFileName()} is nullable and the converters only ever hand real files here, but
     * a null-safe spelling keeps that reasoning local instead of at every call site (SpotBugs
     * {@code NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE}).
     */
    static String fileNameOf(Path aPath)
    {
        return Objects.toString(aPath.getFileName(), "");
    }


    /** Copies a map into a JSON object, preserving insertion order. */
    static void putAll(ObjectNode aTarget, Map<String, String> aEntries)
    {
        aEntries.forEach(aTarget::put);
    }

}
