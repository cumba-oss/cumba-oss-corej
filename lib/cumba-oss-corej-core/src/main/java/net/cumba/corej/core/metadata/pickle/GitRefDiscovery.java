package net.cumba.corej.core.metadata.pickle;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the default branch out of a git <em>smart HTTP</em> advertisement.
 *
 * <p>
 * {@code GET <repo>/info/refs?service=git-upload-pack} is served unauthenticated by every forge
 * that speaks git over HTTP — GitHub, Gitea, GitLab — and its capability list carries
 * {@code symref=HEAD:refs/heads/<branch>}. That makes it a portable way to discover the default
 * branch without a forge-specific JSON API, an API token, or a rate limit. The advertisement also
 * carries HEAD's commit SHA, which is useful provenance for a seed report.
 * </p>
 *
 * <p>
 * The payload is pkt-line framed: each line is prefixed with its length as four hex digits, and
 * {@code 0000} is a flush packet. Only the first non-flush line matters here, so the parsing stays
 * deliberately small — this is not a general git client.
 * </p>
 */
final class GitRefDiscovery
{

    /** {@code symref=HEAD:refs/heads/main} → {@code main}. */
    // NUL separators are mapped to spaces by decode(), so \s alone terminates the branch name.
    private static final Pattern HEAD_SYMREF = Pattern
            .compile("symref=HEAD:refs/heads/(?<branch>\\S+)");

    /**
     * The 40-hex object id introducing the {@code HEAD} ref line.
     *
     * <p>
     * Deliberately unanchored: the id is preceded on the same line by the pkt-line length prefix
     * (and possibly a {@code 0000} flush), which are themselves hex digits — so anchoring to the
     * line start would never match. Scanning left to right, the first position where 40 hex digits
     * are followed by {@code " HEAD"} is the id itself.
     * </p>
     */
    private static final Pattern HEAD_SHA = Pattern.compile("(?<sha>[0-9a-f]{40}) HEAD(?=\\s|$)");

    private GitRefDiscovery()
    {
    }


    /**
     * The default branch advertised for {@code HEAD}.
     *
     * @param aAdvertisement
     *            the raw {@code info/refs} body.
     * @return the branch name, or empty when the advertisement carries no {@code symref} capability
     *         (some servers omit it) — callers should then fall back to {@code HEAD}.
     */
    static Optional<String> defaultBranch(byte[] aAdvertisement)
    {
        Matcher m = HEAD_SYMREF.matcher(decode(aAdvertisement));
        return m.find() ? Optional.of(m.group("branch")) : Optional.empty();
    }


    /**
     * The commit id {@code HEAD} currently points at.
     *
     * @param aAdvertisement
     *            the raw {@code info/refs} body.
     * @return the 40-character object id, or empty when absent.
     */
    static Optional<String> headSha(byte[] aAdvertisement)
    {
        Matcher m = HEAD_SHA.matcher(decode(aAdvertisement));
        return m.find() ? Optional.of(m.group("sha")) : Optional.empty();
    }


    /**
     * Decodes the advertisement, replacing pkt-line NUL separators with spaces so a single regex
     * pass can see the capability list. Length prefixes are left in place — they never collide with
     * the patterns above.
     */
    private static String decode(byte[] aBytes)
    {
        return new String(aBytes, StandardCharsets.UTF_8).replace('\0', ' ');
    }
}
