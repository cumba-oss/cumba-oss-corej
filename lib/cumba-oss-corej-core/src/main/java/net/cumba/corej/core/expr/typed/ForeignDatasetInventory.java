package net.cumba.corej.core.expr.typed;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Stage B's window onto the run's <b>foreign</b> datasets (phase 5b-J — D104d): the seam widening
 * that lets the per-(rule × dataset) checker decide the spec §9 rows that need more than the
 * primary table — a {@code Match_Datasets} {@code Filter}'s column references (D89) and the
 * {@code _matched_} flag's joined dataset.
 *
 * <p>
 * Deliberately a one-method functional interface rather than the {@code exec} package's
 * {@code DatasetResolver}: stage B needs exactly an <em>inventory</em> ("which columns does this
 * dataset carry, if it resolves at all"), and keeping the seam this narrow keeps {@code expr.typed}
 * free of the execution machinery and the checker trivially testable with a map-backed lambda. The
 * production implementation ({@code RuleRunner.stageBGate}) answers through the same split-domain
 * resolution the join build itself uses, so stage B and the join can never hold different ideas of
 * "resolvable".
 * </p>
 */
@FunctionalInterface
public interface ForeignDatasetInventory
{

    /**
     * The named dataset's column inventory.
     *
     * @param datasetName
     *            the foreign dataset's name, as the {@code Match_Datasets} entry (or the flag's
     *            qualifier) spells it after specialisation
     * @return the column names, or {@code null} when the dataset cannot be resolved in this run
     *         (absent, or an un-unionable split domain). An <b>empty</b> set means "resolves,
     *         carries no columns" — a different fact from {@code null}.
     */
    @Nullable
    Set<String> columnsOf(String datasetName);

}
