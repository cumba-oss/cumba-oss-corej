package net.cumba.corej.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.cumba.corej.define.conformance.report.Category;
import org.jspecify.annotations.Nullable;

/**
 * The {@code packages.json} index beside the generated Define-XML rule packages: it maps each
 * shipped file to the {@code (family, version)} pair it holds.
 *
 * <p>
 * <b>The family set is open and comes from this file</b> (PLAN-define-families-from-packages, owner
 * 2026-10-12: the valid values are defined by the available packages, as on the data side). A
 * family is a plain string here; {@link #families()} is the list of valid values a run may name,
 * {@link #canonicalFamily} resolves a caller's spelling to the manifest's own, case-insensitively.
 * Adding a family (an FDA Define-XML sheet, a DRAFT family) is a corpus change and nothing else: no
 * enum, no code edit in any client.
 * </p>
 *
 * <p>
 * Deliberately <b>not</b> {@code net.cumba.corej.core.RulePackageManifest}. That class lives in
 * {@code corej-core}, a module this one does not depend on, so reusing it would add a lib&rarr;lib
 * edge for nothing; and its {@code Entry} carries a {@code List<StandardRef> declared} describing
 * CDISC Library products, which has no meaning for Define-XML. The two manifests share a shape and
 * nothing else — including the lenient reader: an unknown property is ignored, so a newer corpus
 * with an extra manifest field still loads in this engine (the rule and package readers stay
 * strict, because a misspelt rule field must remain a load error).
 * </p>
 *
 * <p>
 * There is no {@code standard} field: for this corpus the standard is always Define-XML, so the
 * package axis is {@code (family, version)} alone — {@code rules-define-cdisc-2-1.json},
 * {@code rules-define-pmda-2-0.json}, and so on.
 * </p>
 */
public record DefineRulePackageManifest(@JsonProperty("generatedFrom") String generatedFrom,
        @JsonProperty("packages") List<Entry> packages)
{

    /** The conventional file name, beside the packages it indexes. */
    public static final String FILE_NAME = "packages.json";

    /**
     * Lenient, as the data side's manifest reader: a field this engine does not know is ignored
     * rather than refused, so an older engine keeps reading a newer corpus.
     */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DefineRulePackageManifest
    {
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    /**
     * One shipped package: its file name and the {@code (family, version)} it holds.
     *
     * @param file
     *            the package file name, e.g. {@code rules-define-pmda-2-1.json}
     * @param family
     *            the rule family the package holds — a free token the corpus defines, e.g.
     *            {@code CDISC} / {@code PMDA}; {@link #load} refuses an entry without one
     * @param version
     *            the Define-XML version in display form, e.g. {@code 2.1}
     * @param ruleCount
     *            how many rules the package carries — a cheap corruption check
     */
    public record Entry(@JsonProperty("file") String file,
            @JsonProperty("family") @Nullable String family,
            @JsonProperty("version") String version, @JsonProperty("ruleCount") int ruleCount)
    {
    }

    /**
     * The conventional package file name for one {@code (family, version)}: the family lower-cased
     * ({@link Locale#ROOT}), the version's dots become dashes, mirroring the CDISC corpus' own file
     * naming.
     */
    public static String packageFileName(String aFamily, String aVersion)
    {
        return "rules-define-" + aFamily.toLowerCase(Locale.ROOT) + "-" + aVersion.replace('.', '-')
                + ".json";
    }


    /**
     * Reads {@code packages.json} from a directory.
     *
     * <p>
     * Three shapes are refused at load, each a {@link DefineRuleSelectionException} naming the
     * file, because with an open family set nothing else would catch them: an entry without a
     * family (it could never be selected and would reach every comparison as {@code null}); a
     * family spelled like an engine category ({@code XSD} / {@code SCHEMA} / {@code SPECIFICATION},
     * ignoring case — its findings would merge into that category); and two entries whose families
     * differ only in case (one spelling per family, so {@link #canonicalFamily} has one answer).
     * </p>
     */
    public static DefineRulePackageManifest load(Path aDirectory) throws IOException
    {
        Path file = aDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file))
        {
            throw new IOException("no " + FILE_NAME + " in " + aDirectory.toAbsolutePath()
                    + " — the directory does not hold a generated Define-XML rule corpus");
        }
        DefineRulePackageManifest manifest = READER.readValue(
                Files.readString(file, StandardCharsets.UTF_8), DefineRulePackageManifest.class);
        manifest.validateFamilies(file);
        return manifest;
    }


    private void validateFamilies(Path aFile)
    {
        List<String> seen = new ArrayList<>();
        for (Entry entry : packages)
        {
            String family = entry.family();
            // DF review L5: an entry without a file or a version can never be selected or
            // resolved — it would reach find / versionsFor as null and the package load as "no
            // such file" — so it is refused here like an entry without a family.
            if (entry.file() == null || entry.file().isBlank())
            {
                throw new DefineRuleSelectionException("invalid " + aFile + ": an entry (family "
                        + family + ", version " + entry.version() + ") names no file");
            }
            if (entry.version() == null || entry.version().isBlank())
            {
                throw new DefineRuleSelectionException("invalid " + aFile + ": the entry for "
                        + entry.file() + " names no version");
            }
            if (family == null || family.isBlank())
            {
                throw new DefineRuleSelectionException("invalid " + aFile + ": the entry for "
                        + entry.file() + " names no family");
            }
            // A finding's category is its family when the rule carries no Source_Type, so a
            // family spelled like an engine category (XSD / SCHEMA / SPECIFICATION) would merge
            // its findings into that category. Compared normalised, as Category compares.
            if (Category.isEngineCategoryName(family))
            {
                throw new DefineRuleSelectionException("invalid " + aFile + ": family \"" + family
                        + "\" (entry " + entry.file() + ") is spelled like an engine finding"
                        + " category (" + Category.XSD + ", " + Category.SCHEMA + ", "
                        + Category.SPECIFICATION
                        + "); its findings would merge into that category");
            }
            for (String other : seen)
            {
                if (!other.equals(family) && other.equalsIgnoreCase(family))
                {
                    throw new DefineRuleSelectionException(
                            "invalid " + aFile + ": families \"" + other + "\" and \"" + family
                                    + "\" differ only in case — one spelling per family");
                }
            }
            seen.add(family);
        }
    }


    /**
     * The distinct families this manifest publishes, in manifest order and in the manifest's own
     * spelling — the valid values of a run's family selection.
     */
    public List<String> families()
    {
        List<String> out = new ArrayList<>();
        for (Entry entry : packages)
        {
            String family = entry.family();
            if (family != null && !out.contains(family))
            {
                out.add(family);
            }
        }
        return List.copyOf(out);
    }


    /**
     * The manifest's own spelling of a family a caller named, matched trimmed and ignoring case;
     * empty for {@code null}, blank, or a name no published family matches.
     */
    public Optional<String> canonicalFamily(@Nullable String aName)
    {
        if (aName == null || aName.isBlank())
        {
            return Optional.empty();
        }
        String wanted = aName.trim();
        return families().stream().filter(f -> f.equalsIgnoreCase(wanted)).findFirst();
    }


    /**
     * The single entry for one {@code (family, version)}, or empty when none is published. Both are
     * compared ignoring case; an entry without a family never matches.
     */
    public Optional<Entry> find(String aFamily, String aVersion)
    {
        return packages.stream().filter(e -> sameFamily(e, aFamily))
                .filter(e -> e.version().equalsIgnoreCase(aVersion)).findFirst();
    }


    /** Every version published for one family (compared ignoring case), in manifest order. */
    public List<String> versionsFor(String aFamily)
    {
        return packages.stream().filter(e -> sameFamily(e, aFamily)).map(Entry::version).toList();
    }


    private static boolean sameFamily(Entry aEntry, String aFamily)
    {
        String family = aEntry.family();
        return family != null && family.equalsIgnoreCase(aFamily);
    }

}
