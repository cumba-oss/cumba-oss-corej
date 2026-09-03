package net.cumba.corej.core.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.core.model.StandardRef;
import org.jspecify.annotations.Nullable;

/**
 * What a run's selected rule packages say the run <em>is</em> — derived entirely from their
 * declared {@code primary} standards (Plan 2, R5/R7/R8), now that {@code --standard} /
 * {@code --version} / {@code --family} are gone.
 *
 * @param group
 *            the CDISC Library group the primaries share: {@code adam}, {@code sdtmig},
 *            {@code sendig}, {@code tig} or {@code cdashig}
 * @param standard
 *            the display standard for the report header, e.g. {@code sdtmig}, {@code adamig}
 * @param version
 *            the display version for the report header, e.g. {@code 3-4}, {@code dart-1-1}
 * @param key
 *            the first primary's resolved {@code standards/...} cache key
 */
public record RunStandard(String group, String standard, String version, String key)
{

    private static final String PREFIX = "standards/";

    /**
     * A trailing version segment: digits, then any number of {@code -digits} groups. Used to split
     * an ADaM product id ({@code adamig-1-3} → {@code adamig} + {@code 1-3}, {@code adam-occds-1-1}
     * → {@code adam-occds} + {@code 1-1}).
     *
     * <p>
     * ⚠ This split is applied ONLY to the {@code adam} group, where the id is a single dashed
     * token. It is deliberately not a general key parser: {@code ProductKeyResolver} refuses to
     * parse keys for good reason, and every other group already carries its version as its own path
     * segment.
     * </p>
     */
    private static final Pattern ADAM_ID = Pattern.compile("^(.*?)-(\\d+(?:-\\d+)*)$");

    /**
     * Derives the run standard from the selected packages' declared primaries.
     *
     * @param declared
     *            every standard the selected packages declare
     * @return the derived run standard, or {@code null} when no primary is declared — the caller
     *         then has nothing to route on and must fail or degrade explicitly
     * @throws StudyValidationException
     *             when the declared primaries span more than one library group (R8)
     */
    public static @Nullable RunStandard from(List<StandardRef> declared)
    {
        List<String> primaries = new ArrayList<>();
        for (StandardRef ref : declared)
        {
            if (ref.role() == StandardRef.Role.PRIMARY && !ref.id().isEmpty())
            {
                primaries.add(ref.id());
            }
        }
        if (primaries.isEmpty())
        {
            return null;
        }
        Set<String> groups = new LinkedHashSet<>();
        for (String id : primaries)
        {
            groups.add(groupOf(id));
        }
        if (groups.size() > 1)
        {
            // ⛔ R8 — "refined B": every primary must share one library group. sendig/3-1-1 +
            // sendig/dart-1-1 is fine (both `sendig`); adamig-1-3 + sdtmig-3-4 as two primaries is
            // refused, because routing, hasSdtmProduct() and the companion decorator are all
            // per-run — a mixed run would answer from the wrong provider on a shared dataset.
            throw new StudyValidationException("The selected rule packages declare primary "
                    + "standards from more than one CDISC Library group " + groups + " (from "
                    + primaries + "). A run resolves ONE group; select packages that "
                    + "share one, or run them separately.");
        }
        checkOneVersionPerProductLine(primaries);
        return of(primaries.get(0));
    }


    /**
     * <b>R8, refined by review finding R-3 / ruling V3</b> — refuses two primaries that name the
     * SAME product line at DIFFERENT versions.
     *
     * <p>
     * ⛔ The group check alone is not enough. {@code -rp cdisc-adamig-1-1,cdisc-adamig-1-3} shares
     * the group {@code adam}, so it passed — and {@code from()} then returned
     * {@code of(primaries.get(0))}, letting the ORDER THE PACKAGES WERE TYPED decide the library
     * product silently: the report header named ADaMIG 1-1 while every ADaMIG 1.3 rule executed,
     * and reversing the two tokens flipped it. Before Phase 4, {@code -s}/{@code -v} made that an
     * explicit user choice, so declaration order could never decide it.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>The key is the PRODUCT LINE, not {@code (group, standard)}.</b> Ruling V3 keeps
     * {@code sendig/3-1-1} + {@code sendig/dart-1-1} legal — they are different products — but
     * {@link #of} gives BOTH of them {@code standard = "sendig"}, so a {@code (group, standard)}
     * key would refuse exactly the case the ruling protects. The line therefore includes the
     * version's alphabetic prefix ({@code dart-1-1} ⇒ {@code dart}, {@code 3-1-1} ⇒ {@code ""}),
     * which separates a named product line from a bare version.
     * </p>
     *
     * <p>
     * ⚑ TIG legs are unaffected: {@code tig/1-0/adam} and {@code tig/1-0/cdash} both derive version
     * {@code 1-0}, so they share a line at the SAME version and are not a conflict.
     * </p>
     */
    private static void checkOneVersionPerProductLine(List<String> primaries)
    {
        Map<String, String> versionByLine = new LinkedHashMap<>();
        Map<String, String> idByLine = new LinkedHashMap<>();
        for (String id : primaries)
        {
            RunStandard rs = of(id);
            String line = rs.group() + "/" + rs.standard() + "/" + alphaPrefix(rs.version());
            String seen = versionByLine.putIfAbsent(line, rs.version());
            idByLine.putIfAbsent(line, id);
            if (seen != null && !seen.equals(rs.version()))
            {
                throw new StudyValidationException("The selected rule packages declare the same "
                        + "standard at two different versions — '" + idByLine.get(line) + "' and '"
                        + id + "'. A run resolves ONE version per standard, and which one won "
                        + "would otherwise depend on the order the packages were named. Select "
                        + "packages that agree on the version, or run them separately.");
            }
        }
    }


    /**
     * The leading alphabetic run of a version token — the product-line marker that separates
     * {@code dart-1-1} ({@code "dart"}) from a bare {@code 3-1-1} ({@code ""}).
     */
    private static String alphaPrefix(String version)
    {
        int i = 0;
        while (i < version.length() && Character.isLetter(version.charAt(i)))
        {
            i++;
        }
        return version.substring(0, i);
    }


    /**
     * Derives the run standard from a single primary product id or cache key.
     *
     * @param idOrKey
     *            e.g. {@code adam/adamig-1-3}, {@code standards/sdtmig/3-4}, {@code tig/1-0/adam}
     * @return the derived run standard
     */
    public static RunStandard of(String idOrKey)
    {
        String id = canonical(idOrKey);
        String group = groupOf(id);
        String rest = id.length() > group.length() ? id.substring(group.length() + 1) : "";
        return switch (group)
        {
        // A TIG key is standards/tig/<version>/<leg>; the version is the first segment.
        case "tig" -> new RunStandard(group, "tig",
                rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest, PREFIX + id);
        case "adam" -> adamOf(group, rest, id);
        // sdtmig / sendig / cdashig and anything else: the remainder IS the version
        // (sendig/dart-1-1 -> version "dart-1-1", which is how the library names it).
        default -> new RunStandard(group, group, rest, PREFIX + id);
        };
    }


    private static RunStandard adamOf(String group, String productId, String id)
    {
        Matcher m = ADAM_ID.matcher(productId);
        return m.matches() ? new RunStandard(group, m.group(1), m.group(2), PREFIX + id)
                : new RunStandard(group, group, productId, PREFIX + id);
    }


    /** The library group of a product id / key: its first segment after any {@code standards/}. */
    public static String groupOf(String idOrKey)
    {
        String id = canonical(idOrKey);
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(0, slash);
    }


    private static String canonical(String idOrKey)
    {
        String t = idOrKey == null ? "" : idOrKey.trim().toLowerCase(Locale.ROOT);
        return t.startsWith(PREFIX) ? t.substring(PREFIX.length()) : t;
    }
}
