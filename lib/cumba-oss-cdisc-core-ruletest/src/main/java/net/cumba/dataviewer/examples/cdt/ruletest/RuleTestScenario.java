package net.cumba.dataviewer.examples.cdt.ruletest;

import java.util.List;
import java.util.Locale;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;

/**
 * In-memory representation of an extended-CDT rule-test scenario file: the {@code #!RuleTest}
 * prelude plus one or more CDT dataset blocks.
 *
 * <p>
 * A scenario is fully self-contained — the {@link #resolver()} it yields sees exactly the datasets
 * declared in the file and nothing else (no fall-through to a shared study).
 * </p>
 */
@Value
@Builder(toBuilder = true)
public class RuleTestScenario
{

    /** Rule identifier from {@code #test}, e.g. {@code "CORE-000012"}. */
    String coreId;

    /** Expected verdict: {@link Verdict#VIOLATION} or {@link Verdict#NO_VIOLATION}. */
    Verdict expect;

    /**
     * Domain prefix, e.g. {@code "AE"}. Must match the name of exactly one dataset in
     * {@link #getDatasets()} (case-insensitive).
     */
    String domain;

    /** Human-readable description from {@code #note}, or {@code null}. */
    @Nullable
    String note;

    /** Declared datasets in file order. Contains at least one entry. */
    @Singular
    List<OverlayDataTable> datasets;

    /**
     * Total number of violations the rule is expected to emit, from a {@code #expectViolationCount}
     * directive, or {@code null} when the directive is absent (verdict-only scenario). When both
     * this and {@link #expectedViolations} are present the parser enforces that they agree.
     */
    @Nullable
    Integer expectViolationCount;

    /**
     * One entry per {@code #expectViolationAt} directive line, in file order; possibly empty. Each
     * pins one location the rule must fire on. When non-empty the match is <em>exact</em>: the set
     * of fired locations must equal this set (no missing, no extra).
     */
    @Singular
    List<ExpectedViolation> expectedViolations;

    /** Source label used for error messages (filename / resource path). */
    String source;

    /**
     * Inline Library provider declared via one or more {@code #library} directives, or {@code null}
     * if the scenario doesn't use Library-dependent operations.
     */
    @Nullable
    MapBackedLibraryMetadataProvider library;

    /**
     * Declarative real-Library reference from a {@code #library-ref} directive, or {@code null}
     * when the scenario uses inline metadata / no Library at all. Mutually exclusive with
     * {@link #library} (the parser rejects scenarios that declare both).
     */
    @Nullable
    LibraryRef libraryRef;

    /**
     * Inline Define-XML provider declared via one or more {@code #define} / {@code #define-include}
     * directives, or {@code null} if the scenario doesn't compare against define metadata. Same
     * grammar and backing double as {@link #library}, serving the {@code define_*} operand level;
     * combines freely with any library channel so a scenario can assert each axis (data-library,
     * data-define, define-library) independently.
     */
    @Nullable
    MapBackedLibraryMetadataProvider define;

    /**
     * Relative path (resolved against the scenario file's directory) of a real Define-XML sidecar
     * declared via {@code #define-xml <file>}, or {@code null}. The scenario runner parses the
     * sidecar and wires <em>both</em> the define-metadata provider and the per-record VLM resolver
     * from it — the production Define-XML path in miniature, serving the {@code define_*} and
     * {@code define_vlm_*} accessors alike. Mutually exclusive with the synthetic {@code #define} /
     * {@code #define-include} channel (the parser rejects a blend).
     */
    @Nullable
    String defineXml;

    /**
     * Value of the {@code #dictionaries} directive, or {@code null} when the scenario does not use
     * external dictionaries. The only accepted value today is {@code "dummy"} — the checked-in
     * dummy dictionary bundle ({@code lib/corej-cdisc-core/dictionaries}); the scenario runner
     * resolves it to a {@code RuntimeDictionaryProvider} and hands it to the engine. Without the
     * directive no provider is supplied, so a dictionary-dependent rule SKIPs — for the declared
     * ({@code $}-ref) form every shipped rule uses, via {@code RuleRunner}'s eager dictionary arm
     * ({@code Fix #268}); for an inlined call, via its injected {@code dictionary_available} gate.
     */
    @Nullable
    String dictionaries;

    /**
     * The scenario's <b>run severity threshold</b> from a {@code #runLevel} directive, or
     * {@code null} when the scenario declares none — in which case it runs at the engine default,
     * {@code Warning} (Plan C &#167;3.4, ruling 4).
     *
     * <p>
     * The weakest check level the scenario evaluates: a rule's declared levels below it are not
     * evaluated at all, and a rule whose every declared level is below it reports {@code SKIPPED}
     * with a stated reason rather than passing silently. The {@code .cdt} face of the CLI's
     * {@code --severity-level} and the REST {@code CheckRunRequest.severityThreshold}.
     * </p>
     */
    net.cumba.datatable.report.@Nullable Severity runLevel;

    /**
     * The dataset whose name equals {@link #getDomain()} (case-insensitive).
     *
     * @return the primary table, or {@code null} if the domain does not match any declared dataset
     *         (the parser should already reject that case at load time).
     */
    public @Nullable OverlayDataTable primaryTable()
    {
        if (domain == null)
        {
            return null;
        }
        for (OverlayDataTable t : datasets)
        {
            String name = t.getMetaData().getName();
            if (name != null && name.equalsIgnoreCase(domain))
            {
                return t;
            }
        }
        return null;
    }


    /**
     * Resolver built from {@link #getDatasets()}, keyed by dataset name (uppercase). Unrelated
     * domain references resolve to {@code null}; the inventory reports exactly the declared dataset
     * names.
     */
    public DatasetResolver.WithInventory resolver()
    {
        return ScenarioResolver.of(datasets);
    }

    /**
     * The contract a scenario declares on its {@code #test} directive.
     * <p>
     * {@link #VIOLATION} and {@link #NO_VIOLATION} both assert that the rule <b>ran</b>;
     * {@link #SKIPPED} asserts that it deliberately did not. Without that third state a rule that
     * never executed reports zero violations and is indistinguishable from one that ran and found
     * nothing — so an {@code expect=noViolation} scenario passes whether or not it tests anything.
     * That is how a fixture rots: tighten a rule's scope, or strip the column its scope needs, and
     * the scenario keeps passing while exercising nothing.
     * </p>
     */
    public enum Verdict
    {

        VIOLATION,
        NO_VIOLATION,

        /**
         * The rule is expected <b>not to execute</b> on this scenario — the deliberate
         * absent-optional-variable test: a rule gated by {@code Requirements.Variables.All} on a
         * dataset that does not carry the variable must skip, and the scenario exists to prove it.
         * Previously such scenarios had to borrow {@code noViolation}, which asserted nothing.
         */
        SKIPPED;

        /**
         * Parse a verdict token as written on a {@code #test} directive. Case-insensitive. Accepts
         * {@code violation}, {@code noViolation}/{@code no_violation}, and {@code skipped}.
         */
        static @Nullable Verdict parse(@Nullable String aToken)
        {
            if (aToken == null)
            {
                return null;
            }
            String t = aToken.toLowerCase(Locale.ROOT);
            return switch (t)
            {
            case "violation" -> VIOLATION;
            case "noviolation", "no_violation" -> NO_VIOLATION;
            case "skipped" -> SKIPPED;
            default -> null;
            };
        }


        /** The token {@link #parse} accepts for this verdict — the round-trip inverse. */
        public String token()
        {
            return switch (this)
            {
            case VIOLATION -> "violation";
            case NO_VIOLATION -> "noViolation";
            case SKIPPED -> "skipped";
            };
        }
    }
}
