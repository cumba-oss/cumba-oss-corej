package net.cumba.corej.core.metadata.store.seed;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of one store seed run.
 *
 * <p>
 * The fetched/carried split is what makes the incremental acquisition of plan §5.2 observable: a
 * re-seed that fetched everything it could have carried is a defect this report makes visible, and
 * the tests assert on exactly these counters. The errata and normalisation members
 * (PLAN-store-library-content-errata) make the seeder's corrections observable the same way: what
 * the register applied, what it could not (stale, D4), and how many fields the text normalisation
 * changed.
 * </p>
 *
 * @param productsSeeded
 *            products (IG + model) projected fresh from the source
 * @param productsCarried
 *            products carried forward unchanged from the existing store because the source no
 *            longer offers their key
 * @param ctPackagesFetched
 *            CT packages acquired from the source this run
 * @param ctPackagesCarried
 *            CT packages carried forward from the existing store without touching the source — they
 *            are immutable once published, so presence is sufficient (plan §5.2)
 * @param ctPackagesMissed
 *            ids that are published (and therefore enumerated in the manifest) but that neither the
 *            source could deliver nor the existing store held — the store answers ABSENT for these,
 *            and a later run naming one aborts rather than degrades
 * @param warnings
 *            non-fatal problems: unreadable source entries, malformed ids, empty projections, and —
 *            loud on every surface — each label still ending in a footnote marker. A stale erratum
 *            is deliberately NOT a warning (D4, review F1): it is listed in {@link #errataStale()},
 *            named by {@link #summary()} and spelled out by {@link #details()}
 * @param sourceRef
 *            what was seeded from, as far as the source can say (a resolved archive ref, a base
 *            URL, a directory); {@code null} when it cannot
 * @param target
 *            the store file written
 * @param storeBytes
 *            the written store's size in bytes
 * @param normalisations
 *            how many product text fields {@link StoreTextNormaliser} changed, by kind
 * @param errataApplied
 *            the ids of the register entries applied to this run's source products
 * @param errataStale
 *            {@code <id>: <reason>} for every entry whose product this source holds but whose
 *            target is gone or no longer carries the expected value — reported, NOT applied (D4);
 *            retire the entry
 * @param errataUnreached
 *            {@code <id>: <reason>} for every entry whose product this source does not hold at all
 *            (a partial source, a dropped product)
 * @param errataNotApplicable
 *            {@code <id>: <reason>} for every entry scoped to another seed source (the register's
 *            {@code sources} member) — left alone by this run
 * @param footnoteLabels
 *            labels and prompts still ending in a footnote marker after the errata (owner rule
 *            2026-10-03); empty on a conforming seed
 */
public record StoreSeedReport(int productsSeeded, int productsCarried, int ctPackagesFetched,
        int ctPackagesCarried, List<String> ctPackagesMissed, List<String> warnings,
        @Nullable String sourceRef, Path target, long storeBytes, Normalisations normalisations,
        List<String> errataApplied, List<String> errataStale, List<String> errataUnreached,
        List<String> errataNotApplicable, List<String> footnoteLabels)
{

    /**
     * The text-normalisation counters (fields changed, not characters).
     *
     * @param literalNewlines
     *            long text fields whose literal {@code \n} became a line break
     * @param shortFieldNewlines
     *            short fields (label, prompt, usage restrictions, role description, name) whose
     *            line break became one space
     * @param htmlEntities
     *            fields whose HTML entities were decoded
     */
    public record Normalisations(int literalNewlines, int shortFieldNewlines, int htmlEntities)
    {

        /** No normalisation at all. */
        public static final Normalisations NONE = new Normalisations(0, 0, 0);

        /**
         * The total number of fields changed by any kind.
         *
         * @return the sum of the three counters
         */
        public int total()
        {
            return literalNewlines + shortFieldNewlines + htmlEntities;
        }
    }

    /** Defensively copies the lists. */
    public StoreSeedReport
    {
        ctPackagesMissed = List
                .copyOf(Objects.requireNonNull(ctPackagesMissed, "ctPackagesMissed"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(normalisations, "normalisations");
        errataApplied = List.copyOf(Objects.requireNonNull(errataApplied, "errataApplied"));
        errataStale = List.copyOf(Objects.requireNonNull(errataStale, "errataStale"));
        errataUnreached = List.copyOf(Objects.requireNonNull(errataUnreached, "errataUnreached"));
        errataNotApplicable = List
                .copyOf(Objects.requireNonNull(errataNotApplicable, "errataNotApplicable"));
        footnoteLabels = List.copyOf(Objects.requireNonNull(footnoteLabels, "footnoteLabels"));
    }


    /**
     * A one-line summary suitable for CLI output or a log line. Stale errata are named in it (their
     * ids), since they are not warnings and a surface that prints only the summary and the warnings
     * must still show them.
     *
     * @return the summary
     */
    public String summary()
    {
        return ("seeded %d products (%d carried), %d CT packages fetched, %d carried, %d missing,"
                + " %d errata applied (%d stale, %d unreached, %d not for this source), %d fields"
                + " normalised -> %s (%d bytes, %d warnings)%s%s").formatted(productsSeeded,
                        productsCarried, ctPackagesFetched, ctPackagesCarried,
                        ctPackagesMissed.size(), errataApplied.size(), errataStale.size(),
                        errataUnreached.size(), errataNotApplicable.size(), normalisations.total(),
                        target, storeBytes, warnings.size(),
                        sourceRef == null ? "" : " from " + sourceRef, staleIds());
    }


    /**
     * The report's per-entry detail, one line each: every stale erratum with its reason (reported,
     * NOT applied — retire it from the register) and every label still ending in a footnote marker.
     * Empty on a conforming seed.
     *
     * @return the detail lines
     */
    public List<String> details()
    {
        List<String> lines = new java.util.ArrayList<>();
        errataStale.forEach(s -> lines.add("errata entry stale, reported and NOT applied: " + s));
        footnoteLabels.forEach(f -> lines.add("label ends in a footnote marker: " + f));
        return List.copyOf(lines);
    }


    private String staleIds()
    {
        if (errataStale.isEmpty())
        {
            return "";
        }
        List<String> ids = errataStale.stream().map(s ->
        {
            int colon = s.indexOf(':');
            return colon < 0 ? s : s.substring(0, colon);
        }).toList();
        return "; stale errata, NOT applied: " + String.join(", ", ids);
    }
}
