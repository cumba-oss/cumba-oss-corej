package net.cumba.corej.define.conformance.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.cumba.corej.define.conformance.ct.CtProvider;
import net.cumba.corej.define.conformance.library.LibraryProvider;
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
 *
 * <p>
 * <b>Families are strings, resolved against the corpus</b> (PLAN-define-families-from-packages):
 * {@link #families()} names the rule families to run, in whatever spelling the caller used; the
 * engine resolves each against the loaded {@code packages.json}, ignoring case, and refuses one the
 * manifest does not publish. {@link #everyPublishedFamily()} is the explicit alternative — run
 * every family the corpus publishes for the document's version (a family without a package at that
 * version is skipped and logged, DF3). An empty family list without that mode is a refusal: there
 * is deliberately no default. Both at once is ambiguous and refused at construction.
 * </p>
 */
public record DefineConformanceInput(Path defineXml, @Nullable CtProvider ctProvider,
        @Nullable Path submissionFolder, boolean useDefaultSubmissionFolder,
        @Nullable String versionOverride, @Nullable LibraryProvider libraryProvider,
        @Nullable Path rulesDir, List<String> families, boolean everyPublishedFamily,
        List<Path> rulesFiles)
{

    public DefineConformanceInput
    {
        Objects.requireNonNull(defineXml, "defineXml");
        families = families == null ? List.of() : List.copyOf(families);
        rulesFiles = rulesFiles == null ? List.of() : List.copyOf(rulesFiles);
        if (everyPublishedFamily && !families.isEmpty())
        {
            throw new IllegalStateException("ambiguous Define-XML family selection: " + families
                    + " named AND every published family requested — choose one");
        }
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

        private List<String> families = List.of();

        private boolean everyPublishedFamily;

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
         * Which rule families to validate against, by name — the values the corpus's
         * {@code packages.json} publishes (the shipped corpus: {@code CDISC}, {@code PMDA}),
         * matched ignoring case. <b>Mandatory</b> for an engine that resolves its own rules unless
         * {@link #everyPublishedFamily()} is chosen, and deliberately so: before 2026-09-01 an
         * unspecified selection silently meant "all rules", which is a decision no caller had
         * actually made. Several may be named — their ids are disjoint, so CDISC and PMDA load
         * together cleanly.
         */
        public Builder families(List<String> aFamilies)
        {
            // Distinct, ignoring case, first spelling kept: naming a family twice would select its
            // package twice, and the duplicate-id check would then report "duplicate Rule_Id" — a
            // corpus-corruption message for what is only a caller slip. The CLI already dedupes;
            // the Java API must too.
            List<String> distinct = new ArrayList<>();
            if (aFamilies != null)
            {
                for (String family : aFamilies)
                {
                    if (distinct.stream().noneMatch(f -> f.equalsIgnoreCase(family)))
                    {
                        distinct.add(family);
                    }
                }
            }
            families = List.copyOf(distinct);
            return this;
        }


        /**
         * Run every family the corpus publishes for the document's Define-XML version (DF3): a
         * family with no package at that version is skipped and logged, never refused; a version no
         * family publishes is refused. This is the data browser's blank-selection mode. It is an
         * explicit mode, not the meaning of an empty {@link #families} list — that stays a refusal.
         */
        public Builder everyPublishedFamily()
        {
            everyPublishedFamily = true;
            return this;
        }


        /** Additive rule files layered on top of the selected packages (site rules). */
        public Builder rulesFiles(List<Path> aRulesFiles)
        {
            rulesFiles = aRulesFiles == null ? List.of() : List.copyOf(aRulesFiles);
            return this;
        }


        /**
         * Builds the input.
         *
         * @throws IllegalStateException
         *             if families were named and every published family was requested too
         */
        public DefineConformanceInput build()
        {
            return new DefineConformanceInput(defineXml, ctProvider, submissionFolder,
                    useDefaultSubmissionFolder, versionOverride, libraryProvider, rulesDir,
                    families, everyPublishedFamily, rulesFiles);
        }

    }

}
