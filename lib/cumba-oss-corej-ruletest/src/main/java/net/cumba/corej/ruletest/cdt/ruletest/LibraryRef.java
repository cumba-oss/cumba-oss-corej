package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import org.jspecify.annotations.Nullable;

/**
 * Declarative reference to a <em>real</em> CDISC Library, parsed from a {@code #library-ref}
 * directive. Carries only the selection inputs that actually reach the Library — the API key, cache
 * directory and base URL are intentionally absent and come from the environment
 * ({@code CDISC_API_KEY} / {@code CDISC_API_CACHE} / {@code CDISC_API_URL}).
 *
 * <p>
 * This object performs no I/O; {@link ScenarioLibraryResolver} turns it into a
 * {@link net.cumba.corej.core.exec.MetadataProvider} (or signals that the Library is unavailable).
 * </p>
 *
 * <p>
 * Only {@code standard}, {@code version} and {@code ctPackages} are passed to
 * {@code CdiscLibraryProviderBuilder}. {@code useCase} and {@code defineVersion} are deliberately
 * not modelled: neither reaches the Library ({@code useCase} filters rules; {@code defineVersion}
 * only labels the report).
 * </p>
 */
@Value
@Builder(toBuilder = true)
public class LibraryRef
{

    /** Standard identifier, e.g. {@code "sdtmig"}, {@code "adamig"}. Required. */
    String standard;

    /** Standard version, e.g. {@code "3-4"}, {@code "1-3"}. Required. */
    String version;

    /** Candidate CT-package ids, e.g. {@code "sdtmct-2024-09-27"}. May be empty. */
    @Singular
    List<String> ctPackages;

    /**
     * Optional substandard selector ({@code sdtm|send|adam|cdash}). Retained for forward-compat
     * only — it is <em>not</em> a CDISC-Library input ({@code CdiscLibraryProviderBuilder} ignores
     * it) and, because the scenario picks its rule by {@code coreId}, currently has no effect.
     */
    @Nullable
    String substandard;
}
