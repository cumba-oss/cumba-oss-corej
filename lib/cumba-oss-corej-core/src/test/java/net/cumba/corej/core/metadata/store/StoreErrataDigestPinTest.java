package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Review F3 of PLAN-store-library-content-errata: the errata register decides what the seeder
 * writes, so a register change leaves every existing store uncorrected unless the user re-seeds.
 * The seed records the register's sha256 in the manifest provenance and a re-seed never carries a
 * baseline product corrected under another digest; this test is the deliberate stop for the other
 * half — forcing a re-seed — by pinning the register's sha256 NEXT TO the format version.
 *
 * <p>
 * When it fails, decide: a register change that users must not miss (a corrected value rules read)
 * bumps {@link StoreFormat#FORMAT_VERSION}, so every older store is refused and re-seeded; a change
 * that can wait for the next seed (or a format that is not released yet) only re-pins the digest.
 * Either way, update both literals here together.
 * </p>
 *
 * <p>
 * Pinned 2026-10-03 (review round): format 5 is not released, so the register's review changes —
 * the {@code sources} scoping and the three missed {@code #283.36} sites per SDTM 2.x model — keep
 * format 5 and re-pin the digest.
 * </p>
 */
class StoreErrataDigestPinTest
{

    private static final String REGISTER = "/metadata/store/store-errata.json";

    private static final int PINNED_FORMAT = 5;

    private static final String PINNED_SHA256 = "5cb74dd5bbcb4138a0da588459f0e3985ca66ec8aa74132fd243a904b5e378d6";

    @Test
    void theRegisterDigestIsPinnedNextToTheFormatVersion()
        throws IOException, NoSuchAlgorithmException
    {
        byte[] bytes;
        try (InputStream in = StoreErrataDigestPinTest.class.getResourceAsStream(REGISTER))
        {
            assertNotNull(in, REGISTER);
            bytes = in.readAllBytes();
        }
        String actual = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(PINNED_FORMAT, StoreFormat.FORMAT_VERSION,
                "the format moved: re-pin the register digest for the new format deliberately");
        assertEquals(PINNED_SHA256, actual,
                "a register change must bump FORMAT_VERSION or update this pin deliberately");
    }
}
