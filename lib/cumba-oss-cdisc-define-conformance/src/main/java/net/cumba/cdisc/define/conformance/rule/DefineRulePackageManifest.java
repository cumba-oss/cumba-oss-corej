package net.cumba.cdisc.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code packages.json} index beside the generated Define-XML rule packages: it maps each
 * shipped file to the {@code (family, version)} pair it holds.
 *
 * <p>
 * Deliberately <b>not</b> {@code net.cumba.cdisc.core.RulePackageManifest}. That class lives in
 * {@code corej-cdisc-core}, a module this one does not depend on, so reusing it would add a
 * lib&rarr;lib edge for nothing; and its {@code Entry} carries a {@code List<StandardRef> declared}
 * describing CDISC Library products, which has no meaning for Define-XML. The two manifests share a
 * shape and nothing else.
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     *            which source sheet the rules mirror
     * @param version
     *            the Define-XML version in display form, e.g. {@code 2.1}
     * @param ruleCount
     *            how many rules the package carries — a cheap corruption check
     */
    public record Entry(@JsonProperty("file") String file, @JsonProperty("family") RuleSet family,
            @JsonProperty("version") String version, @JsonProperty("ruleCount") int ruleCount)
    {
    }

    /**
     * The conventional package file name for one {@code (family, version)}. The version's dots
     * become dashes, mirroring the CDISC corpus' own file naming.
     */
    public static String packageFileName(RuleSet aFamily, String aVersion)
    {
        return "rules-define-" + aFamily.name().toLowerCase(Locale.ROOT) + "-"
                + aVersion.replace('.', '-') + ".json";
    }


    /** Reads {@code packages.json} from a directory. */
    public static DefineRulePackageManifest load(Path aDirectory) throws IOException
    {
        Path file = aDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file))
        {
            throw new IOException("no " + FILE_NAME + " in " + aDirectory.toAbsolutePath()
                    + " — the directory does not hold a generated Define-XML rule corpus");
        }
        return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                DefineRulePackageManifest.class);
    }


    /** The single entry for one {@code (family, version)}, or empty when none is published. */
    public Optional<Entry> find(RuleSet aFamily, String aVersion)
    {
        return packages.stream()
                .filter(e -> e.family() == aFamily && e.version().equalsIgnoreCase(aVersion))
                .findFirst();
    }


    /** Every version published for one family, in manifest order. */
    public List<String> versionsFor(RuleSet aFamily)
    {
        return packages.stream().filter(e -> e.family() == aFamily).map(Entry::version).toList();
    }

}
