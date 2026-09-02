package net.cumba.cdisc.core.metadata.dictionary;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Converts one vendor's raw distribution into a house-format dictionary document.
 *
 * <p>
 * One implementation per dictionary type. Each is responsible for the whole of its vendor's format:
 * which files to read, which columns, and — the part that carries the risk — emitting every
 * {@code levels} value as the vendor's own string <b>verbatim</b>. 79 of the 98 dictionary rules
 * compare case-sensitively, so a converter that normalises case makes its rules fire on every row.
 * </p>
 */
public interface DictionaryConverter
{

    /** The house-format dictionary type this converter produces, lower-cased. */
    String type();


    /**
     * Reads the raw distribution at {@code aRawDir} and builds the house-format document.
     *
     * @throws IOException
     *             when the distribution is absent, unreadable, or not the expected layout
     */
    ObjectNode convert(Path aRawDir) throws IOException;


    /**
     * The vendor release identifier read out of the distribution itself — MedDRA's
     * {@code meddra_release.asc}, MED-RT's {@code //namespace/version}, the date token in a UNII
     * file name. Never parsed or ordered; recorded verbatim and compared only for equality.
     */
    String versionOf(Path aRawDir) throws IOException;

}
