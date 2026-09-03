package net.cumba.corej.core.metadata.pickle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.cumba.corej.core.metadata.MetadataProductCatalogue;
import net.cumba.corej.core.metadata.MetadataProductKeys;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a user-supplied metadata-product token ({@code --metadata-products} / {@code -mp}) onto
 * a real {@code standards/...} cache key. A token may be given in full ({@code adam/adamig-1-3},
 * with or without the {@code standards/} prefix) or as any unique suffix of a key
 * ({@code adamig-1-3}); resolution is by suffix match against the configured
 * {@link MetadataProductCatalogue} (Phase 7b: the pickle cache's {@link PickleCache#standardKeys()}
 * unioned with the CDISC Library API's {@code /mdr/products} list — the token vocabulary is
 * identical in both, measured §7-1), never by parsing the token.
 *
 * <p>
 * ⚠ Deliberately NOT a family table and NOT a dash-split heuristic. Cache keys are non-uniform
 * ({@code standards/sdtmig/3-4} vs {@code standards/adam/adamig-1-3} vs
 * {@code standards/tig/1-0/sdtm}) and product ids are unsplittable ({@code adam-occds-1-1} — is
 * that {@code adam} + {@code occds-1-1} or {@code adam-occds} + {@code 1-1}?). A suffix match needs
 * neither, and an ambiguous or absent token becomes an ERROR rather than a plausible wrong answer.
 * </p>
 *
 * <p>
 * <b>No catalogue source available ⇒ no key set to match against.</b> With an empty key set, an
 * explicit token is accepted verbatim only in full-key form (it contains a {@code /}, e.g.
 * {@code adam/adamig-1-3}); a bare suffix cannot be resolved and is an error naming the reason.
 * Validation is never silently skipped — that would reintroduce the silent no-op declared products
 * exist to remove. (This is the residue of the pre-Phase-7 "no pickle cache ⇒ full-key tokens only"
 * rule: an API-backed deployment now resolves bare tokens too, and only a deployment with
 * <em>neither</em> source falls back to full-form keys.)
 * </p>
 */
public final class ProductKeyResolver
{

    /** Cache-key namespace prefix every standards product key carries. */
    private static final String STANDARDS_PREFIX = "standards/";

    /** Maximum number of candidate keys listed in a {@link Result.NotFound} message. */
    private static final int MAX_CANDIDATES = 10;

    private ProductKeyResolver()
    {
    }

    /** Outcome of resolving one token. */
    public sealed interface Result
    {

        /** The token resolved to exactly one cache key. */
        record Resolved(String cacheKey) implements Result
        {
        }


        /**
         * The token matched no key. {@code candidates} lists up to {@value #MAX_CANDIDATES} nearest
         * keys (or all keys when few), or is empty when no cache is available.
         */
        record NotFound(String token, List<String> candidates) implements Result
        {

            public NotFound
            {
                candidates = List.copyOf(candidates);
            }
        }


        /** The token is a suffix of several keys; the user must disambiguate. */
        record Ambiguous(String token, List<String> matches) implements Result
        {

            public Ambiguous
            {
                matches = List.copyOf(matches);
            }
        }
    }

    /**
     * Resolves one token against the cache's standard keys.
     *
     * @param token
     *            the user token (full key, {@code standards/}-prefixed key, or unique suffix;
     *            dotted versions like {@code adamig-1.3} are dash-normalised)
     * @param standardKeys
     *            the {@link PickleCache#standardKeys()} set; empty when no cache is configured
     * @return the resolution outcome (never {@code null})
     */
    public static Result resolve(String token, Set<String> standardKeys)
    {
        String normalised = normalise(token);
        if (normalised.isEmpty())
        {
            return new Result.NotFound(token, nearestCandidates("", standardKeys));
        }
        if (standardKeys.isEmpty())
        {
            // No cache to validate against. A full-form key is accepted verbatim; a bare suffix
            // has nothing to match and is refused with the reason (see class javadoc).
            if (normalised.indexOf('/') >= 0)
            {
                return new Result.Resolved(STANDARDS_PREFIX + normalised);
            }
            return new Result.NotFound(token, List.of());
        }
        List<String> matches = new ArrayList<>();
        for (String key : standardKeys)
        {
            if (key.equals(STANDARDS_PREFIX + normalised) || key.endsWith("/" + normalised))
            {
                matches.add(key);
            }
        }
        if (matches.size() == 1)
        {
            return new Result.Resolved(matches.get(0));
        }
        if (matches.isEmpty())
        {
            return new Result.NotFound(token, nearestCandidates(normalised, standardKeys));
        }
        return new Result.Ambiguous(token, matches);
    }


    /**
     * Resolves every token in order, or throws with <b>all</b> failures listed at once — a user
     * with three bad tokens sees three messages, not one per attempt.
     *
     * @param tokens
     *            the user tokens, in precedence order
     * @param standardKeys
     *            the {@link PickleCache#standardKeys()} set; empty when no cache is configured
     * @return the resolved cache keys, in the same order, duplicates removed (first wins)
     * @throws IllegalArgumentException
     *             when any token fails to resolve; the message lists every failure
     */
    public static List<String> resolveAll(List<String> tokens, Set<String> standardKeys)
    {
        return resolveAll(tokens, MetadataProductCatalogue.of(standardKeys, true, List.of()));
    }


    /**
     * As {@link #resolveAll(List, Set)}, resolving against a source-agnostic
     * {@link MetadataProductCatalogue} (Phase 7b) so failure messages can say <i>which</i> sources
     * were consulted — in particular why a TIG token fails in an API-only deployment (TIG is
     * pickle-only).
     *
     * @param tokens
     *            the user tokens, in precedence order
     * @param catalogue
     *            the configured product catalogue
     * @return the resolved cache keys, in the same order, duplicates removed (first wins)
     * @throws IllegalArgumentException
     *             when any token fails to resolve; the message lists every failure
     */
    public static List<String> resolveAll(List<String> tokens, MetadataProductCatalogue catalogue)
    {
        Set<String> standardKeys = catalogue.keys();
        Set<String> resolved = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (String token : tokens)
        {
            switch (resolve(token, standardKeys))
            {
            case Result.Resolved r -> resolved.add(r.cacheKey());
            case Result.NotFound nf -> failures.add(describeNotFound(nf, catalogue));
            case Result.Ambiguous a -> failures.add("'" + a.token() + "' is ambiguous — it matches "
                    + a.matches() + "; use a longer suffix or the full key");
            }
        }
        if (!failures.isEmpty())
        {
            throw new IllegalArgumentException(
                    "Cannot resolve --metadata-products: " + String.join("; ", failures));
        }
        return List.copyOf(resolved);
    }


    /**
     * Convenience over {@link #resolveAll(List, MetadataProductCatalogue)} that builds the
     * catalogue from the current configuration (Phase 7b): the pickle cache's {@code standards/...}
     * keys when one is configured ({@code aExplicitPickleDir} first, then
     * {@code CDISC_PICKLE_CACHE_DIR} / {@code cdisc.pickle.cache.dir}), <b>unioned</b> with the
     * CDISC Library API's {@code /mdr/products} list (served from {@code aExplicitApiCacheDir} /
     * {@code CDISC_API_CACHE} when cached there, the network otherwise). With no source available
     * at all only full-form tokens resolve — see the class javadoc.
     *
     * @param tokens
     *            the user tokens, in precedence order
     * @param aExplicitPickleDir
     *            an explicit pickle-cache directory ({@code --pickle-cache}); may be {@code null}
     * @param aExplicitApiCacheDir
     *            an explicit API-cache directory ({@code --cache}); may be {@code null}
     * @return the resolved cache keys, in order
     * @throws IllegalArgumentException
     *             when any token fails to resolve; the message lists every failure
     */
    public static List<String> resolveAllConfigured(List<String> tokens,
            @Nullable String aExplicitPickleDir, @Nullable String aExplicitApiCacheDir)
    {
        if (tokens.isEmpty())
        {
            return List.of();
        }
        return resolveAll(tokens,
                MetadataProductCatalogue.configured(aExplicitPickleDir, aExplicitApiCacheDir));
    }


    /** One failure message for a {@link Result.NotFound}, naming the reason and candidates. */
    private static String describeNotFound(Result.NotFound nf, MetadataProductCatalogue catalogue)
    {
        if (!catalogue.pickleConfigured() && MetadataProductKeys.isTig(normalise(nf.token())))
        {
            return "'" + nf.token() + "' names a TIG product, and TIG products exist only in the "
                    + "pickle metadata cache (the CDISC Library API does not serve them) — "
                    + "configure --pickle-cache / CDISC_PICKLE_CACHE_DIR";
        }
        if (catalogue.keys().isEmpty())
        {
            return "'" + nf.token() + "' cannot be resolved: no product catalogue is available to "
                    + "match against (no pickle metadata cache configured, CDISC Library product "
                    + "list unreachable) — pass the full key form instead (e.g. adam/adamig-1-3), "
                    + "or configure --pickle-cache / CDISC_PICKLE_CACHE_DIR";
        }
        return "'" + nf.token() + "' matches no known product key; nearest candidates: "
                + nf.candidates();
    }


    /**
     * Up to {@value #MAX_CANDIDATES} candidate keys for a miss, preferring keys sharing the token's
     * trailing characters, falling back to cache order.
     */
    private static List<String> nearestCandidates(String normalised, Set<String> standardKeys)
    {
        List<String> out = new ArrayList<>();
        // Pass 1: keys containing the token anywhere (a near-miss like a wrong version).
        String probe = normalised.length() > 3 ? normalised.substring(0, 4) : normalised;
        for (String key : standardKeys)
        {
            if (!probe.isEmpty() && key.contains(probe) && out.size() < MAX_CANDIDATES)
            {
                out.add(key);
            }
        }
        // Pass 2: fill with the remaining keys, cache order.
        for (String key : standardKeys)
        {
            if (out.size() >= MAX_CANDIDATES)
            {
                break;
            }
            if (!out.contains(key))
            {
                out.add(key);
            }
        }
        return out;
    }


    /**
     * Trim, lower-case, strip a leading {@code standards/}, and dash-normalise dotted version
     * digits ({@code 1.3} → {@code 1-3}), mirroring {@link PickleProductSource#standardsKey}.
     */
    private static String normalise(String token)
    {
        String t = token == null ? "" : token.trim().toLowerCase(Locale.ROOT).replace('.', '-');
        if (t.startsWith(STANDARDS_PREFIX))
        {
            t = t.substring(STANDARDS_PREFIX.length());
        }
        return t;
    }
}
