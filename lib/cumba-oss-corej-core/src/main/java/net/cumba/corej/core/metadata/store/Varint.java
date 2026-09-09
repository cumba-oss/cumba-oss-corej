package net.cumba.corej.core.metadata.store;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The store's binary primitives: LEB128 unsigned varints plus the nullable string / string-list
 * codings built on them. Used by {@code ct/terms.bin} (varint-delimited term rows) and
 * {@code ct/codelists.bin} (varint-delta term-id lists).
 *
 * <p>
 * Null coding: a string is {@code varint 0} for {@code null}, else {@code varint(utf8Length + 1)}
 * followed by the UTF-8 bytes; a string list is {@code varint 0} for {@code null}, else
 * {@code varint(size + 1)} followed by its strings. {@code null} and empty are therefore distinct,
 * matching "store what the source publishes".
 * </p>
 */
final class Varint
{

    private Varint()
    {
    }


    /** Writes {@code aValue} as an unsigned LEB128 varint. */
    static void writeUnsigned(ByteArrayOutputStream aOut, long aValue)
    {
        if (aValue < 0)
        {
            throw new IllegalArgumentException("negative varint: " + aValue);
        }
        long remaining = aValue;
        while ((remaining & ~0x7FL) != 0)
        {
            aOut.write((int) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        aOut.write((int) remaining);
    }


    /** Reads one unsigned LEB128 varint; {@link EOFException} on a truncated stream. */
    static long readUnsigned(InputStream aIn) throws IOException
    {
        long value = 0;
        for (int shift = 0; shift <= 63; shift += 7)
        {
            int b = aIn.read();
            if (b < 0)
            {
                throw new EOFException("truncated varint");
            }
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
            {
                return value;
            }
        }
        throw new IOException("varint longer than 64 bits");
    }


    /** Reads a varint and narrows it to a non-negative int, or fails naming {@code aWhat}. */
    static int readCount(InputStream aIn, String aWhat) throws IOException
    {
        long value = readUnsigned(aIn);
        if (value > Integer.MAX_VALUE)
        {
            throw new IOException(aWhat + " out of range: " + value);
        }
        return (int) value;
    }


    /** Writes a nullable string (see class comment for the coding). */
    static void writeString(ByteArrayOutputStream aOut, @Nullable String aValue)
    {
        if (aValue == null)
        {
            aOut.write(0);
            return;
        }
        byte[] utf8 = aValue.getBytes(StandardCharsets.UTF_8);
        writeUnsigned(aOut, utf8.length + 1L);
        aOut.writeBytes(utf8);
    }


    /** Reads a nullable string (see class comment for the coding). */
    static @Nullable String readString(InputStream aIn) throws IOException
    {
        int coded = readCount(aIn, "string length");
        if (coded == 0)
        {
            return null;
        }
        byte[] utf8 = aIn.readNBytes(coded - 1);
        if (utf8.length != coded - 1)
        {
            throw new EOFException("truncated string");
        }
        return new String(utf8, StandardCharsets.UTF_8);
    }


    /** Writes a nullable string list (see class comment for the coding). */
    static void writeStringList(ByteArrayOutputStream aOut, @Nullable List<String> aValues)
    {
        if (aValues == null)
        {
            aOut.write(0);
            return;
        }
        writeUnsigned(aOut, aValues.size() + 1L);
        for (String value : aValues)
        {
            writeString(aOut, value);
        }
    }


    /** Reads a nullable string list (see class comment for the coding). */
    static @Nullable List<String> readStringList(InputStream aIn) throws IOException
    {
        int coded = readCount(aIn, "list size");
        if (coded == 0)
        {
            return null;
        }
        List<String> values = new ArrayList<>(coded - 1);
        for (int i = 0; i < coded - 1; i++)
        {
            values.add(readString(aIn));
        }
        return values;
    }
}
