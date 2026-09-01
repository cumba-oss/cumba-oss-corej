package net.cumba.cdisc.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link GitRefDiscovery} against real and degenerate smart-HTTP advertisements. No network.
 */
class GitRefDiscoveryTest
{

    /**
     * A verbatim capture of GitHub's response for {@code cdisc-org/cdisc-rules-engine}, NUL
     * separators included — the exact bytes the production path parses.
     */
    private static final String GITHUB_ADVERTISEMENT = "001e# service=git-upload-pack\n" + "0000015"
            + "8941161a4c7e1dd76ece0c0590d28dabcc11e5f2 HEAD\0multi_ack thin-pack "
            + "side-band side-band-64k ofs-delta shallow deepen-since deepen-not deepen-relative "
            + "no-progress include-tag multi_ack_detailed allow-tip-sha1-in-want "
            + "allow-reachable-sha1-in-want no-done symref=HEAD:refs/heads/main filter "
            + "object-format=sha1 agent=git/github-cda1d7094a30-Linux\n";

    private static byte[] bytes(String aText)
    {
        return aText.getBytes(StandardCharsets.UTF_8);
    }


    @Test
    void readsTheDefaultBranchFromGitHubsAdvertisement()
    {
        assertEquals("main",
                GitRefDiscovery.defaultBranch(bytes(GITHUB_ADVERTISEMENT)).orElse(null));
    }


    /**
     * The object id sits behind the pkt-line length prefix, which is itself hex — so the parse must
     * not anchor to the line start.
     */
    @Test
    void readsTheHeadShaDespiteTheHexLengthPrefix()
    {
        assertEquals("8941161a4c7e1dd76ece0c0590d28dabcc11e5f2",
                GitRefDiscovery.headSha(bytes(GITHUB_ADVERTISEMENT)).orElse(null));
    }


    @Test
    void handlesANonDefaultBranchName()
    {
        String ad = "0000013abc\0symref=HEAD:refs/heads/release/2.x more";

        assertEquals("release/2.x", GitRefDiscovery.defaultBranch(bytes(ad)).orElse(null));
    }


    /** Some servers omit the symref capability; the caller then falls back to the literal HEAD. */
    @Test
    void missingSymrefYieldsEmpty()
    {
        String ad = "001e# service=git-upload-pack\n0000015"
                + "8941161a4c7e1dd76ece0c0590d28dabcc11e5f2 HEAD\0multi_ack thin-pack\n";

        assertTrue(GitRefDiscovery.defaultBranch(bytes(ad)).isEmpty());
        assertEquals("8941161a4c7e1dd76ece0c0590d28dabcc11e5f2",
                GitRefDiscovery.headSha(bytes(ad)).orElse(null));
    }


    @Test
    void truncatedOrEmptyInputYieldsEmpty()
    {
        assertTrue(GitRefDiscovery.defaultBranch(new byte[0]).isEmpty());
        assertTrue(GitRefDiscovery.headSha(new byte[0]).isEmpty());
        assertTrue(GitRefDiscovery.defaultBranch(bytes("001e# service=git-up")).isEmpty());
        assertTrue(GitRefDiscovery.headSha(bytes("0000015 not-a-sha HEAD")).isEmpty());
    }


    @Test
    void garbageInputDoesNotThrow()
    {
        assertTrue(GitRefDiscovery.defaultBranch(bytes("\0\0\0\0")).isEmpty());
        assertTrue(GitRefDiscovery.headSha(bytes("HEAD HEAD HEAD")).isEmpty());
    }
}
