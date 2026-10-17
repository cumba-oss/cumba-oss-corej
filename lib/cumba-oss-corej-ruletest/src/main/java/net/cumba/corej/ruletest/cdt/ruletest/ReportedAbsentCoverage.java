package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.exec.AbsentDatasetSkip;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The {@code #reported-absent} coverage check — <b>one implementation</b> for the corpus factories
 * and the rule editor: every dataset a scenario lists must be one whose absence a run of the rule's
 * package <em>reports</em>.
 *
 * <p>
 * The runner hands the listed names to the engine as its {@code reportedDatasets}, but production
 * never takes that set as input: {@code LibraryValidator} derives it from the run's rules
 * ({@link AbsentDatasetSkip#reportedDatasets} over {@link ScopeMatcher#rulesInUseCase} — only an
 * unscoped rule whose whole Check is a bare {@code not ds_exists("D")} adds D). A name no presence
 * rule reports would make the scenario prove a SKIP that no run ever takes. The harness itself
 * cannot see the package — {@link ScenarioRunner} runs one rule — so the host derives the set with
 * {@link #reportedDatasets} over the package's rules (the package {@link ScenarioRuleSelector}
 * selects) and refuses with {@link #refusal}.
 * </p>
 */
public final class ReportedAbsentCoverage
{

    private ReportedAbsentCoverage()
    {
    }


    /**
     * The datasets whose absence a run of these rules reports, derived as {@code LibraryValidator}
     * derives it. A {@code .cdt} scenario cannot request a use case ({@link LibraryRef}), so the
     * use-case restriction keeps every rule; it is spelled as the validator spells it so the two
     * derivations cannot drift apart.
     *
     * @param aPackageRules
     *            the rules of the package the scenario's rule runs from
     * @return the upper-cased dataset names (never {@code null})
     */
    public static Set<String> reportedDatasets(Collection<Rule> aPackageRules)
    {
        return AbsentDatasetSkip
                .reportedDatasets(ScopeMatcher.rulesInUseCase(List.copyOf(aPackageRules), null));
    }


    /**
     * The package's name in a refusal, e.g. {@code the fda family's 'sendig-3-1-1' package}.
     *
     * @param aFamily
     *            the family key ({@code cdisc}, {@code fda}, …)
     * @param aPackageKey
     *            the {@code standard-version} key, or {@code null} when none was selected
     * @return the label
     */
    public static String packageLabel(String aFamily, @Nullable String aPackageKey)
    {
        return aPackageKey == null ? "the " + aFamily + " family (no package holds the rule)"
                : "the " + aFamily + " family's '" + aPackageKey + "' package";
    }


    /**
     * Why the scenario's {@code #reported-absent} claims a coverage the package does not provide,
     * or {@code null} when every listed name is covered (or none is listed).
     *
     * @param aScenario
     *            the parsed scenario
     * @param aReported
     *            the package's coverage, from {@link #reportedDatasets}
     * @param aPackageLabel
     *            the package, from {@link #packageLabel}
     * @return the refusal message, or {@code null}
     */
    public static @Nullable String refusal(RuleTestScenario aScenario, Set<String> aReported,
            String aPackageLabel)
    {
        List<String> uncovered = aScenario.getReportedAbsent().stream()
                .filter(name -> !aReported.contains(name)).toList();
        if (uncovered.isEmpty())
        {
            return null;
        }
        return "Scenario " + aScenario.getCoreId() + " (" + aScenario.getSource()
                + ") declares #reported-absent " + uncovered + ", but no rule of " + aPackageLabel
                + " reports that absence (an unscoped rule whose whole Check is"
                + " not ds_exists(\"<DS>\")); the package reports " + new TreeSet<>(aReported)
                + ". Production derives the set from the run's rules, so no run would silence the"
                + " dependant.";
    }
}
