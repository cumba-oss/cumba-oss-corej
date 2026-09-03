package net.cumba.corej.define.conformance.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.cumba.corej.define.conformance.ct.CtProvider;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.corej.define.conformance.rule.RuleSet;
import org.jspecify.annotations.Nullable;

/**
 * Inputs for one {@link DefineConformanceEngine} run (plan §3.6). The define.xml path is required;
 * everything else is optional and gates the corresponding rule families:
 *
 * <ul>
 * <li>{@code ctProvider} absent ⇒ {@code Requires: ct} rules SKIP
 * ({@code SKIPPED_MISSING_CT}).</li>
 * <li>submission folder absent ⇒ {@code Requires: folder} rules SKIP
 * ({@code SKIPPED_MISSING_FOLDER}). The effective folder is the explicit
 * {@link #submissionFolder()} if set, else the define's parent when
 * {@link #useDefaultSubmissionFolder()} is on (the default), else none — see
 * {@link #resolvedSubmissionFolder()}.</li>
 * <li>{@code libraryProvider} absent ⇒ {@code Requires: library} rules SKIP
 * ({@code SKIPPED_MISSING_LIBRARY}).</li>
 * <li>{@code versionOverride} ({@code "2.0"}/{@code "2.1"}) forces the applicable-version gate;
 * absent ⇒ the pre-pass detected version is used.</li>
 * </ul>
 */
public record DefineConformanceInput(Path defineXml, @Nullable CtProvider ctProvider,
        @Nullable Path submissionFolder, boolean useDefaultSubmissionFolder,
        @Nullable String versionOverride, @Nullable LibraryProvider libraryProvider,
        @Nullable Path rulesDir, List<RuleSet> families, List<Path> rulesFiles)
{

    public DefineConformanceInput
    {
        Objects.requireNonNull(defineXml, "defineXml");
        families = families == null ? List.of() : List.copyOf(families);
        rulesFiles = rulesFiles == null ? List.of() : List.copyOf(rulesFiles);
    }


    /** A builder over a required define.xml path; submission-folder defaulting is on. */
    public static Builder builder(Path aDefineXml)
    {
        return new Builder(aDefineXml);
    }


    /**
     * The submission folder the folder-gated rules should see: the explicit folder when set, else
     * the define's parent directory when defaulting is on, else empty (folder rules SKIP).
     */
    public Optional<Path> resolvedSubmissionFolder()
    {
        if (submissionFolder != null)
        {
            return Optional.of(submissionFolder);
        }
        if (useDefaultSubmissionFolder)
        {
            return Optional.ofNullable(defineXml.getParent());
        }
        return Optional.empty();
    }

    /** Mutable builder for {@link DefineConformanceInput}. */
    public static final class Builder
    {

        private final Path defineXml;

        private @Nullable CtProvider ctProvider;

        private @Nullable Path submissionFolder;

        private boolean useDefaultSubmissionFolder = true;

        private @Nullable String versionOverride;

        private @Nullable LibraryProvider libraryProvider;

        private @Nullable Path rulesDir;

        private List<RuleSet> families = List.of();

        private List<Path> rulesFiles = List.of();

        private Builder(Path aDefineXml)
        {
            defineXml = Objects.requireNonNull(aDefineXml, "defineXml");
        }


        public Builder ctProvider(@Nullable CtProvider aCtProvider)
        {
            ctProvider = aCtProvider;
            return this;
        }


        public Builder libraryProvider(@Nullable LibraryProvider aLibraryProvider)
        {
            libraryProvider = aLibraryProvider;
            return this;
        }


        public Builder submissionFolder(@Nullable Path aSubmissionFolder)
        {
            submissionFolder = aSubmissionFolder;
            return this;
        }


        public Builder useDefaultSubmissionFolder(boolean aUseDefault)
        {
            useDefaultSubmissionFolder = aUseDefault;
            return this;
        }


        public Builder versionOverride(@Nullable String aVersionOverride)
        {
            versionOverride = aVersionOverride;
            return this;
        }


        /**
         * Where the generated rule packages live. {@code null} defers to {@code RuleRepository}'s
         * directory precedence (arg &gt; env &gt; sysprop &gt; conventional). Ignored when the
         * engine was built over an explicit rule list.
         */
        public Builder rulesDir(@Nullable Path aRulesDir)
        {
            rulesDir = aRulesDir;
            return this;
        }


        /**
         * Which rule sheets to validate against. <b>Mandatory</b> for an engine that resolves its
         * own rules, and deliberately so: before 2026-09-01 an unspecified selection silently meant
         * "all 409 rules", which is a decision no caller had actually made. Several may be named —
         * their ids are disjoint, so CDISC and PMDA load together cleanly.
         */
        public Builder families(List<RuleSet> aFamilies)
        {
            // Distinct: naming a family twice would select its package twice, and loadPackages
            // would then report "duplicate Rule_Id" — a corpus-corruption message for what is
            // only a caller slip. The CLI already dedupes; the Java API must too.
            families = aFamilies == null ? List.of()
                    : List.copyOf(new java.util.LinkedHashSet<>(aFamilies));
            return this;
        }


        /** Additive rule files layered on top of the selected packages (site rules). */
        public Builder rulesFiles(List<Path> aRulesFiles)
        {
            rulesFiles = aRulesFiles == null ? List.of() : List.copyOf(aRulesFiles);
            return this;
        }


        public DefineConformanceInput build()
        {
            return new DefineConformanceInput(defineXml, ctProvider, submissionFolder,
                    useDefaultSubmissionFolder, versionOverride, libraryProvider, rulesDir,
                    families, rulesFiles);
        }

    }

}
