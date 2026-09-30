package net.cumba.corej.core.exec;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;

/**
 * The registry function {@code referenced_domain_class(name)} — the CDISC-Library observation class
 * of the domain each record names, ported in wave 3 of {@code RUNBOOK-operations-to-functions}
 * ({@code PLAN-per-row-functions}) from the retired {@code REFERENCED_DOMAIN_CLASS} operation.
 * Backs {@code FDA-SD0095} / {@code PMDA-SD0095} and their {@code -A} splits (a SUPPQUAL
 * {@code RDOMAIN} must reference a general-observation-class domain).
 *
 * <p>
 * Per row it answers the class of the domain in the {@code name} column
 * ({@code MetadataProvider.getDatasetClass}), upper-cased so a Check compares against the canonical
 * tokens ({@code EVENTS}, {@code FINDINGS ABOUT}, …) whatever the provider tier's case, or
 * {@code ""} when the Library cannot classify the domain. A <b>missing</b> cell answers that cell,
 * identity kept (the runbook's missing hand-through) — the retired operation skipped the row and
 * the row read its grouped default {@code ""}; both fold alike in the corpus's
 * {@code $x not in [...] and RDOMAIN != "DM"} Checks ({@code FDA-SD0095-invalid_blank_rdomain}).
 * {@code name} is <b>required</b>: the operation's {@code RDOMAIN} default was an implicit read no
 * site relied on. An absent column is the absent-column constant (D4) — unreachable in the corpus,
 * where all four rules require {@code RDOMAIN} and SKIP on it first.
 * </p>
 *
 * <p>
 * ⭐ <b>Provider capability {@link ProviderNeed#LIBRARY}</b> — the runner's no-provider SKIP, the
 * forecast and the injected inline gate see it. The function never answers from a source the rule
 * does not claim: with no provider, with a Library that could not be consulted (Fix #369's
 * conditions 1 and 2) or with a degraded Library whose Define-XML fallback classified none of the
 * referenced domains (condition 3) it raises {@link UnusableProviderAnswerException}, which
 * {@code RuleRunner} turns into {@code SKIPPED} — the retired operation's library-not-available
 * SKIP, unchanged ({@code CodelistAttributes}' wave-0 precedent).
 * </p>
 */
public final class ReferencedDomainClass
{

    /** The function name as authored. */
    public static final String NAME = "referenced_domain_class";

    private ReferencedDomainClass()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} holds the bound {@code name} vector.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the per-row class
     * @throws UnusableProviderAnswerException
     *             when the Library cannot answer
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = run.ctx().getLibraryProvider();
        if (provider == null)
        {
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "no CDISC Library provider");
        }
        if (provider.isLibraryUnavailable() && !LibraryAnswerability.libraryAnswerable(provider))
        {
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "the CDISC Library could not be consulted");
        }
        Vector domain = args.get(0);
        int rowCount = run.rowCount();
        // One Library question per distinct domain, not per row — the retired operation's
        // computeIfAbsent, kept: a study's SUPP datasets name a handful of domains over many rows.
        Map<String, String> classes = new HashMap<>();
        for (int row = 0; row < rowCount; row++)
        {
            IDataValue cell = domain.value(row).cell();
            if (!cell.isMissingOrInvalid())
            {
                classes.computeIfAbsent(cell.getValueAsString(), dom ->
                {
                    String c = provider.getDatasetClass(dom, dom);
                    return c != null ? c.toUpperCase(Locale.ROOT) : "";
                });
            }
        }
        if (provider.isLibraryUnavailable() && classes.values().stream().allMatch(String::isBlank))
        {
            // Fix #369 condition 3: the define fallback is engaged but classified nothing.
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "the Define-XML fallback could not classify any referenced domain");
        }
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            IDataValue cell = domain.value(row).cell();
            return cell.isMissingOrInvalid() ? cell
                    : java.util.Objects.requireNonNullElse(classes.get(cell.getValueAsString()),
                            "");
        });
    }

}
