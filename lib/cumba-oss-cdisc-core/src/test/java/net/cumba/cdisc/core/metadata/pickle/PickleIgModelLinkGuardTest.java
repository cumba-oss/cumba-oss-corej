package net.cumba.cdisc.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan 2, Phase 3b — every cached SDTM-family IG must still resolve its SDTM <b>Model</b>.
 *
 * <p>
 * ⛔ <b>Why this guard exists: losing the link degrades SILENTLY.</b> The Model is selected from the
 * IG's own published {@code _links.model.href} (R13 — CDISC publishes this relationship, so it is
 * read where CDISC put it rather than copied into our data). If a future seeding pass ever dropped
 * {@code _links} while trimming the cache, {@code getLink("model")} returns empty ⇒
 * {@code sdtmModelFor} returns empty ⇒ {@code MetadataLibraryProvider} <em>accepts a null model
 * product by design</em> ({@code Fix #61}'s tier C fallback). The result is a green build with
 * quietly thinner variable lists — no GENERAL OBSERVATIONS merge in {@code buildResolvedSdtm} step
 * 3, and {@code SUPPQUAL}/{@code SQ*} falling back to the hard-coded list. No error anywhere.
 * </p>
 *
 * <p>
 * ⚑ <b>Measured 2026-08-28/29, and the numbers are why the exclusions below are explicit:</b> of 34
 * cached products, <b>every</b> SDTM-family IG resolves (20/20, and 0 href-present-but-key-absent).
 * Each IG points at a <em>different</em> Model — SDTMIG 3.2→{@code 1-4}, 3.3→{@code 1-7},
 * 3.4→{@code 2-0}, TIG→{@code 2-1} — so this could never have been a house constant.
 * </p>
 *
 * <p>
 * ⚠ <b>It SKIPs when the cache is absent, never fails.</b> The pickle cache is an unversioned,
 * untracked input: no CI box and no fresh clone has one.
 * </p>
 */
class PickleIgModelLinkGuardTest
{

    /**
     * Products deliberately exempt, each with its reason — an unexplained exclusion reads as a bug
     * to the next person.
     */
    private static final Map<String, String> EXCLUDED = Map.of("standards/cdashig/1-1-1",
            "publishes NO _links.model.href at all (measured: the only product of 34 that does "
                    + "not); it is not SDTM-family, so no SDTM lane depends on it");

    @Test
    void everySdtmFamilyIgResolvesItsModel()
    {
        Optional<PickleCache> maybe = PickleCache.openIfConfigured();
        assumeTrue(maybe.isPresent() && maybe.get().isAvailable(),
                "no pickle cache configured — this guard is opt-in, like FindingsSnapshotDriftTest");
        PickleCache cache = maybe.get();

        java.util.Set<String> modelKeys = cache.modelKeys();
        assumeTrue(!modelKeys.isEmpty(),
                "pickle cache has no standards_models.pkl to check against");

        List<String> noLink = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int checked = 0;
        for (String key : cache.standardKeys())
        {
            if (!isSdtmFamilyIg(key) || EXCLUDED.containsKey(key))
            {
                continue;
            }
            checked++;
            Optional<String> derived = modelKeyOf(cache, key);
            if (derived.isEmpty())
            {
                noLink.add(key);
            }
            else if (!modelKeys.contains(derived.get()))
            {
                unresolved.add(key + " -> " + derived.get());
            }
        }

        assumeTrue(checked > 0, "no SDTM-family IG products in this cache");
        assertTrue(noLink.isEmpty(),
                "these SDTM-family IGs publish no _links.model.href, so sdtmModelFor would return "
                        + "empty and the run would degrade SILENTLY to thinner variable lists: "
                        + noLink);
        assertTrue(unresolved.isEmpty(),
                "these SDTM-family IGs name a Model that is not in standards_models.pkl — the "
                        + "cache is internally inconsistent and the model would be missing at run "
                        + "time: " + unresolved);
    }


    /**
     * ADaM is excluded from the SDTM-family sweep <b>by construction, and that is correct</b>:
     * {@code models/adam/2-1} is a header-only product — measured 0 variables and no
     * {@code classes}/{@code datasets} keys, against 379 distinct for {@code models/sdtm/2-0}.
     * {@code adamModelColumnOrder}/{@code adamModelVariables} read the IG structure's own
     * {@code analysisVariableSets}; only {@code sdtmModelFor} consumes a Model product. Nothing is
     * being missed — this test pins that the exclusion is deliberate, not an oversight.
     */
    @Test
    void adamIsNotPartOfTheSdtmFamilySweep()
    {
        assertTrue(!isSdtmFamilyIg("standards/adam/adamig-1-3"),
                "ADaM IGs must not enter the SDTM-family model sweep: their Model product is "
                        + "header-only (0 variables) by design, so requiring one would fail on "
                        + "correct data");
    }


    /**
     * SDTM-family IG keys: {@code standards/sdtmig/…}, {@code standards/sendig/…}, TIG sdtm/send
     * legs.
     */
    private static boolean isSdtmFamilyIg(String key)
    {
        return key.startsWith("standards/sdtmig/") || key.startsWith("standards/sendig/")
                || key.endsWith("/sdtm") || key.endsWith("/send");
    }


    /**
     * Mirrors {@code PickleProductSource.modelKeyFromIg} — including the <b>prefix strip</b>
     * ({@code /mdr/adam/adam-2-1} → version {@code adam-2-1} → {@code 2-1}) that a naive reading of
     * the href would miss.
     */
    private static Optional<String> modelKeyOf(PickleCache cache, String key)
    {
        Object links = cache.get(key).map(m -> m.get("_links")).orElse(null);
        if (!(links instanceof Map<?, ?> linkMap))
        {
            return Optional.empty();
        }
        if (!(linkMap.get("model") instanceof Map<?, ?> model))
        {
            return Optional.empty();
        }
        if (!(model.get("href") instanceof String href))
        {
            return Optional.empty();
        }
        String[] parts = href.split("/", -1);
        if (parts.length < 4)
        {
            return Optional.empty();
        }
        String standard = parts[2];
        String version = parts[3];
        if (version.startsWith(standard + "-"))
        {
            version = version.substring(standard.length() + 1);
        }
        return Optional.of("models/" + standard + "/" + version.replace('.', '-'));
    }
}
