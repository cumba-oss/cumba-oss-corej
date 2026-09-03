package net.cumba.corej.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 8 contract on {@link RawDictionaryFiles#reader}: undecodable bytes are still
 * <em>replaced</em> rather than aborting the conversion, but never <em>silently</em> — every
 * substituted character is tallied, per thread, for the installer to surface.
 */
class RawDictionaryFilesTest
{

    /** {@code 'A' 0xFF 'B' LF 0xC3 LF} — two undecodable sequences on two lines. */
    private static final int[] DIRTY =
    {
            0x41, 0xFF, 0x42, 0x0A, 0xC3, 0x0A
    };

    @Test
    void aCleanFileCountsNoReplacements(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, new int[]
        {
                0x63, 0x61, 0x66, 0xC3, 0xA9, 0x0A
        }); // "café"
        RawDictionaryFiles.resetReplacementCount();
        try (BufferedReader in = RawDictionaryFiles.reader(file))
        {
            assertEquals(4, in.readLine().length(), "0xC3 0xA9 is one well-formed character");
            assertNull(in.readLine());
        }
        assertEquals(0, RawDictionaryFiles.replacementCount());
    }


    @Test
    void everyUndecodableSequenceIsCounted(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, DIRTY);
        RawDictionaryFiles.resetReplacementCount();
        try (BufferedReader in = RawDictionaryFiles.reader(file))
        {
            assertEquals(3, in.readLine().length(), "0xFF became one replacement character");
            assertEquals(1, in.readLine().length(), "the dangling 0xC3 became another");
            assertNull(in.readLine());
        }
        assertEquals(2, RawDictionaryFiles.replacementCount());
    }


    @Test
    void theTallyAccumulatesAcrossReadersAndResetsOnDemand(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, DIRTY);
        RawDictionaryFiles.resetReplacementCount();
        for (int i = 0; i < 2; i++)
        {
            try (BufferedReader in = RawDictionaryFiles.reader(file))
            {
                assertEquals(2, in.lines().count());
            }
        }
        assertEquals(4, RawDictionaryFiles.replacementCount(),
                "one conversion reads several files into one tally");
        RawDictionaryFiles.resetReplacementCount();
        assertEquals(0, RawDictionaryFiles.replacementCount(),
                "the next conversion starts from zero");
    }

    // ------------------------------------------------------------------
    // The BOM skip: one leading U+FEFF is not data
    // ------------------------------------------------------------------


    /**
     * A2 — a UTF-8 BOM survives {@code trim()} and {@code isBlank()}, so left in the stream it
     * corrupts the first token every converter reads (a MED-RT version, an {@code llt.asc} code, a
     * {@code LOINC_NUM} header lookup). The reader skips exactly one leading BOM — and does NOT
     * count it as a replacement, so the Phase 8 warning stays quiet for a merely-BOM'd file.
     */
    @Test
    void aLeadingBomIsSkippedAndNotCountedAsAReplacement(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, new int[]
        {
                0xEF, 0xBB, 0xBF, 0x41, 0x42, 0x0A
        }); // BOM + "AB"
        RawDictionaryFiles.resetReplacementCount();
        try (BufferedReader in = RawDictionaryFiles.reader(file))
        {
            assertEquals("AB", in.readLine(), "the BOM is not part of the first line");
            assertNull(in.readLine());
        }
        assertEquals(0, RawDictionaryFiles.replacementCount(),
                "a BOM is a deliberate encoding signature, not a decoding accident");
    }


    /** Only the FIRST character is a byte-order mark; a later U+FEFF is (weird) data. */
    @Test
    void onlyOneLeadingBomIsSkipped(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, new int[]
        {
                0xEF, 0xBB, 0xBF, 0xEF, 0xBB, 0xBF, 0x41, 0x0A
        });
        try (BufferedReader in = RawDictionaryFiles.reader(file))
        {
            assertEquals("\uFEFF" + "A", in.readLine(),
                    "the second U+FEFF is content — rewriting it would silently alter a term");
        }
    }


    /** A file that does not start with a BOM is read from byte zero, untouched. */
    @Test
    void aBomlessFileIsNotShifted(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, new int[]
        {
                0x41, 0x42, 0x0A
        });
        try (BufferedReader in = RawDictionaryFiles.reader(file))
        {
            assertEquals("AB", in.readLine());
        }
    }

    // ------------------------------------------------------------------
    // The dropped-term tally (A8): refusals are counted, never silent
    // ------------------------------------------------------------------


    @Test
    void theDroppedTermTallyAccumulatesAndResetsLikeTheReplacementOne()
    {
        RawDictionaryFiles.resetDroppedTermCount();
        assertEquals(0, RawDictionaryFiles.droppedTermCount());
        RawDictionaryFiles.countDroppedTerm();
        RawDictionaryFiles.countDroppedTerm();
        assertEquals(2, RawDictionaryFiles.droppedTermCount(),
                "one conversion tallies every refusal");
        RawDictionaryFiles.resetDroppedTermCount();
        assertEquals(0, RawDictionaryFiles.droppedTermCount(),
                "the next conversion starts from zero");
    }


    private static Path write(Path aDir, int[] aBytes) throws IOException
    {
        byte[] bytes = new byte[aBytes.length];
        for (int i = 0; i < aBytes.length; i++)
        {
            bytes[i] = (byte) aBytes[i];
        }
        Path file = aDir.resolve("raw.txt");
        Files.write(file, bytes);
        return file;
    }

}
