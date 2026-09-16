package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The store's LEB128 primitives, checked against the <b>encoding</b> rather than against each
 * other.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>
 * {@link Varint} had <b>no unit test at all</b>. Measured 2026-09-14: its mutants' only killer was
 * {@code StoreSeederRealDataConformanceTest}, an integration test that unpickles ~420 MB twice and
 * costs the module's mutation gate about 23 minutes on its own, because pitest re-runs it once per
 * mutant it covers. A 64-line binary primitive was being verified by the most expensive test in the
 * repository, incidentally.
 * </p>
 *
 * <h2>⭐⭐ Write and read are checked SEPARATELY, both against the spec</h2>
 *
 * <p>
 * The obvious test here is a round trip — write a value, read it back, assert equality. That is
 * exactly the shape this campaign's second-largest finding warns about: <i>"our reader agrees with
 * our writer" proves nothing</i>. A pair of symmetric defects (a wrong shift on both sides, a
 * continuation bit set and cleared consistently) round-trips perfectly and ships a file no other
 * LEB128 decoder can read.
 * </p>
 * <p>
 * So the encoder is asserted against <b>literal byte sequences derived from the LEB128
 * definition</b> — 7 data bits per byte, little-endian groups, high bit set on every byte but the
 * last — and the decoder is fed those same hand-written bytes and asked for the value. Neither
 * direction is allowed to be its own oracle. The round-trip test at the end is a convenience, not
 * the evidence.
 * </p>
 */
class VarintTest
{

    // ------------------------------------------------------------------
    // writeUnsigned — asserted against the encoding, byte for byte
    // ------------------------------------------------------------------

    /**
     * ⚠ The single-byte cases never enter the {@code while} loop, so on their own they leave the
     * whole continuation-byte path — the encoder's only interesting part — untouched. They are here
     * to pin the boundary at 127/128, not to cover the loop; the multi-byte test below does that.
     */
    @Test
    void singleByteValuesEncodeAsThemselves()
    {
        assertArrayEquals(bytes(0x00), encode(0));
        assertArrayEquals(bytes(0x01), encode(1));
        // 127 is the largest value that fits in seven bits, so it is the last single-byte one.
        assertArrayEquals(bytes(0x7F), encode(127));
    }


    /**
     * ⭐ The load-bearing test: every byte but the last carries the high continuation bit, and the
     * groups are little-endian.
     *
     * <p>
     * Each expectation is derived from the LEB128 definition, not from running the encoder. 128 is
     * {@code 0b1_0000000}: low group {@code 0000000} with the continuation bit set → {@code 0x80},
     * then {@code 1} → {@code 0x01}. 300 is {@code 0b10_0101100}: low group {@code 0101100} = 0x2C,
     * continuation set → {@code 0xAC}, then {@code 2} → {@code 0x02}.
     * </p>
     */
    @Test
    void multiByteValuesCarryTheContinuationBitOnEveryByteButTheLast()
    {
        assertArrayEquals(bytes(0x80, 0x01), encode(128));
        assertArrayEquals(bytes(0xAC, 0x02), encode(300));
        // 16383 = 2^14-1: both groups full, only the second is terminal.
        assertArrayEquals(bytes(0xFF, 0x7F), encode(16383));
        // 16384 = 2^14: two empty groups then a 1 — the case that catches a shift of the wrong
        // width, because a 6- or 8-bit group produces a different byte count here.
        assertArrayEquals(bytes(0x80, 0x80, 0x01), encode(16384));
    }


    /**
     * The widest legal value: 63 set bits are exactly nine 7-bit groups, so this is the longest
     * encoding the reader is allowed to accept, and the last byte is terminal.
     */
    @Test
    void longMaxValueEncodesAsNineBytes()
    {
        byte[] encoded = encode(Long.MAX_VALUE);
        assertEquals(9, encoded.length, "63 bits / 7 bits per byte = 9 bytes");
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F), encoded);
    }


    @Test
    void aNegativeValueIsRejectedRatherThanEncodedAsHuge()
    {
        // Unsigned LEB128 has no representation for a negative number; sign-extending it would
        // silently write ten bytes the reader then rejects as "longer than 64 bits".
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Varint.writeUnsigned(new ByteArrayOutputStream(), -1L));
        assertEquals("negative varint: -1", e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Varint.writeUnsigned(new ByteArrayOutputStream(), Long.MIN_VALUE));
    }

    // ------------------------------------------------------------------
    // readUnsigned — fed hand-written bytes, never the encoder's output
    // ------------------------------------------------------------------


    @Test
    void theDecoderReadsSpecBytesBackToTheirValues() throws IOException
    {
        assertEquals(0L, decode(0x00));
        assertEquals(127L, decode(0x7F));
        assertEquals(128L, decode(0x80, 0x01));
        assertEquals(300L, decode(0xAC, 0x02));
        assertEquals(16383L, decode(0xFF, 0x7F));
        assertEquals(16384L, decode(0x80, 0x80, 0x01));
        assertEquals(Long.MAX_VALUE, decode(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F));
    }


    /**
     * ⚠ Non-canonical but legal: a value padded with redundant continuation groups must decode to
     * the same number. This is what pins the shift as <em>additive per group</em> rather than
     * merely "some shift" — a decoder that mis-weights a group gets 1 right here and 129 wrong.
     */
    @Test
    void redundantlyPaddedEncodingsStillDecode() throws IOException
    {
        assertEquals(1L, decode(0x81, 0x00));
        assertEquals(129L, decode(0x81, 0x81, 0x00));
    }


    @Test
    void theDecoderStopsAtTheTerminalByteAndLeavesTheRest() throws IOException
    {
        // Two varints back to back: the first read must consume exactly its own bytes, or every
        // varint-delimited row in ct/terms.bin drifts.
        InputStream in = new ByteArrayInputStream(bytes(0xAC, 0x02, 0x7F));
        assertEquals(300L, Varint.readUnsigned(in));
        assertEquals(127L, Varint.readUnsigned(in));
        assertEquals(-1, in.read(), "both varints consumed, nothing left over");
    }


    @Test
    void aTruncatedVarintIsAnEofRatherThanAWrongNumber() throws IOException
    {
        // 0x80 promises a following group and the stream ends: answering 0 here would be a silently
        // wrong value in the middle of a binary store.
        assertThrows(EOFException.class,
                () -> Varint.readUnsigned(new ByteArrayInputStream(bytes(0x80))));
        assertThrows(EOFException.class,
                () -> Varint.readUnsigned(new ByteArrayInputStream(new byte[0])));
    }


    @Test
    void aVarintLongerThanSixtyFourBitsIsRefused()
    {
        // Ten continuation bytes: past the 64-bit budget, so the loop must run out rather than
        // wrap silently.
        byte[] tooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80);
        IOException e = assertThrows(IOException.class,
                () -> Varint.readUnsigned(new ByteArrayInputStream(tooLong)));
        assertEquals("varint longer than 64 bits", e.getMessage());
    }


    /**
     * ⭐⭐ The regression test for a silent-corruption defect found 2026-09-14 while writing this
     * class, by a {@code ConditionalsBoundary} mutant that survived everything else.
     *
     * <p>
     * The loop used to allow a <b>tenth</b> 7-bit group (shift 63), which is the sign bit. Nine
     * groups cover bits 0..62 — every non-negative {@code long} — so a tenth can only ever arrive
     * from a corrupt or crafted stream, and it was being honoured: ten bytes of {@code 0x80}… with
     * a terminal {@code 0x01} decoded to {@code Long.MIN_VALUE}.
     * </p>
     * <p>
     * ⚠ The damage was downstream, which is why nothing caught it. A negative is <b>not</b> greater
     * than {@code Integer.MAX_VALUE}, so it sailed through {@link Varint#readCount}'s range guard,
     * narrowed to int {@code 0} — and {@code 0} is the coding for <b>null</b>. A corrupt
     * {@code ct/terms.bin} produced a null field instead of an error, silently. Measured before the
     * fix, not argued.
     * </p>
     */
    @Test
    void aTenthGroupCannotSmuggleInTheSignBit() throws IOException
    {
        // Nine continuation groups then a terminal 0x01 — the tenth group is bit 63.
        byte[] crafted = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
        IOException e = assertThrows(IOException.class,
                () -> Varint.readUnsigned(new ByteArrayInputStream(crafted)),
                "a tenth group is refused, not decoded into a negative 'unsigned' value");
        assertEquals("varint longer than 64 bits", e.getMessage());

        // ⚠ And the property that actually protected the caller: nine groups still decode, so the
        // fix cannot have been made by simply narrowing the legal range.
        assertEquals(Long.MAX_VALUE, decode(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F));
    }

    // ------------------------------------------------------------------
    // readCount — the narrowing guard
    // ------------------------------------------------------------------


    @Test
    void readCountNarrowsAndNamesWhatOverflowed() throws IOException
    {
        assertEquals(300, Varint.readCount(new ByteArrayInputStream(bytes(0xAC, 0x02)), "x"));
        assertEquals(Integer.MAX_VALUE,
                Varint.readCount(new ByteArrayInputStream(encode(Integer.MAX_VALUE)), "x"),
                "the largest value that still fits an int");

        ByteArrayInputStream tooBig = new ByteArrayInputStream(encode(Integer.MAX_VALUE + 1L));
        IOException e = assertThrows(IOException.class,
                () -> Varint.readCount(tooBig, "term count"));
        assertEquals("term count out of range: 2147483648", e.getMessage(),
                "the message names WHICH count overflowed — the store has several");
    }

    // ------------------------------------------------------------------
    // The nullable string / list codings built on the varint
    // ------------------------------------------------------------------


    /** ⭐ null and empty are deliberately distinct — "store what the source publishes". */
    @Test
    void nullAndEmptyAreDifferentOnTheWire()
    {
        ByteArrayOutputStream nul = new ByteArrayOutputStream();
        Varint.writeString(nul, null);
        assertArrayEquals(bytes(0x00), nul.toByteArray());

        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        Varint.writeString(empty, "");
        assertArrayEquals(bytes(0x01), empty.toByteArray(), "length+1, then zero bytes");
    }


    @Test
    void stringsAreLengthPrefixedInUtf8Bytes() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // "é" is two UTF-8 bytes: the prefix must count BYTES, not chars.
        Varint.writeString(out, "é");
        assertArrayEquals(bytes(0x03, 0xC3, 0xA9), out.toByteArray());
        assertEquals("é", Varint.readString(new ByteArrayInputStream(out.toByteArray())));
    }


    @Test
    void aTruncatedStringIsAnEofRatherThanAShortString()
    {
        // Claims four payload bytes, supplies one.
        assertThrows(EOFException.class,
                () -> Varint.readString(new ByteArrayInputStream(bytes(0x05, 0x41))));
    }


    @Test
    void stringListsRoundTripIncludingNullAndNullMembers() throws IOException
    {
        assertNull(Varint.readStringList(new ByteArrayInputStream(bytes(0x00))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> values = java.util.Arrays.asList("A", null, "");
        Varint.writeStringList(out, values);
        assertEquals(values, Varint.readStringList(new ByteArrayInputStream(out.toByteArray())));

        ByteArrayOutputStream emptyList = new ByteArrayOutputStream();
        Varint.writeStringList(emptyList, List.of());
        assertArrayEquals(bytes(0x01), emptyList.toByteArray());
        assertEquals(List.of(), Varint.readStringList(new ByteArrayInputStream(bytes(0x01))));
    }

    // ------------------------------------------------------------------
    // Convenience, not evidence — see the class javadoc
    // ------------------------------------------------------------------


    @Test
    void everyBoundaryValueRoundTrips() throws IOException
    {
        for (long v : new long[]
        {
                0, 1, 126, 127, 128, 129, 255, 256, 16383, 16384, 1L << 31, (1L << 56) - 1,
                Long.MAX_VALUE
        })
        {
            assertEquals(v, decode(encode(v)), "round trip of " + v);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private static byte[] encode(long aValue)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Varint.writeUnsigned(out, aValue);
        return out.toByteArray();
    }


    private static long decode(int... aBytes) throws IOException
    {
        return Varint.readUnsigned(new ByteArrayInputStream(bytes(aBytes)));
    }


    private static long decode(byte[] aBytes) throws IOException
    {
        return Varint.readUnsigned(new ByteArrayInputStream(aBytes));
    }


    /** Literal byte sequences, written as the unsigned values the encoding is defined in. */
    private static byte[] bytes(int... aValues)
    {
        byte[] out = new byte[aValues.length];
        for (int i = 0; i < aValues.length; i++)
        {
            out[i] = (byte) aValues[i];
        }
        return out;
    }
}
