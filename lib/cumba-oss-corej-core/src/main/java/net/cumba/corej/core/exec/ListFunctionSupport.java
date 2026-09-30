package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ The shared shape of the dataset-level <b>list</b> functions (runbook wave 4,
 * {@code PLAN-list-functions} §2.2, D-W4-1): the mechanics every ported list callable needs and
 * none owns — the provider conditions, the broadcast, the static-argument reads. Each callable
 * keeps its <b>own</b> empty / unusable arm in its own class ({@link LibraryLists},
 * {@link DefineLists}, {@link InventoryLists}, {@link Minus}, {@link ParentModelColumnOrder}); this
 * class deliberately carries no per-callable semantics, so that it cannot flatten them (skeleton
 * hazard 1). Wave 7's {@code distinct} inherits the same mechanics.
 *
 * <p>
 * A list answer is born here, at {@link #broadcast}, through {@code ConstVector.of} — the
 * {@link ListValueGuard} site of register {@code NNL §1} — so no consumer ever meets a {@code null}
 * element. It is <b>one</b> {@code List} broadcast to every row (W0's list shape, option 2), which
 * is why every function using it is declared an aggregate
 * ({@code FunctionDescriptor.aggregating()}, D-W4-10).
 * </p>
 */
public final class ListFunctionSupport
{

    private static final System.Logger LOGGER = System
            .getLogger(ListFunctionSupport.class.getName());

    /** The dataset-level list functions ported by wave 4, by their authored names. */
    public static final Set<String> FUNCTION_NAMES = Set.of(LibraryLists.REQUIRED_VARIABLES,
            LibraryLists.EXPECTED_VARIABLES, LibraryLists.GET_COLUMN_ORDER_FROM_LIBRARY,
            LibraryLists.GET_MODEL_COLUMN_ORDER, LibraryLists.VARIABLE_NAMES,
            LibraryLists.STANDARD_DOMAINS, LibraryLists.GET_DATASET_FILTERED_VARIABLES,
            LibraryLists.NATURAL_KEY_VARIABLES, LibraryLists.GET_MODEL_FILTERED_VARIABLES,
            LibraryLists.VALID_CODELIST_DATES, LibraryLists.CODELIST_TERMS,
            ParentModelColumnOrder.NAME, DefineLists.DEFINE_VARIABLE_NAMES,
            DefineLists.DEFINE_DATASET_NAMES, DefineLists.DEFINE_KEY_VARIABLES,
            InventoryLists.GET_COLUMN_ORDER_FROM_DATASET, InventoryLists.DATASET_NAMES,
            InventoryLists.STUDY_DOMAINS, InventoryLists.SPLIT_SIBLING_LENGTH_MISMATCH,
            InventoryLists.DUPLICATE_LABEL_VARIABLES, Minus.NAME);

    private ListFunctionSupport()
    {
    }


    /**
     * The run's CDISC Library provider, or the unusable signal — conditions 1 and 2 of the retired
     * the retired executor's {@code evalLibrary} / {@code degradedSkip} (Fix #369): no provider, or
     * a provider whose Library could not be consulted and is not answerable from a Define-XML
     * fallback. Condition 3 (opted in, define present, the arm got nothing) is per callable:
     * {@link #degradedAnswer}.
     *
     * @param run
     *            the evaluation run
     * @param function
     *            the calling function, named in the signal
     * @return the provider
     * @throws UnusableProviderAnswerException
     *             when the rule must SKIP
     */
    static MetadataProvider library(EvalRun run, String function)
    {
        MetadataProvider provider = run.ctx().getLibraryProvider();
        if (provider == null)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] Library provider not available for {1} — rule will be skipped",
                    ruleId(run), function);
            throw unusable(function, ProviderNeed.Kind.LIBRARY, "no CDISC Library provider");
        }
        if (provider.isLibraryUnavailable() && !LibraryAnswerability.libraryAnswerable(provider))
        {
            LOGGER.log(System.Logger.Level.INFO,
                    LibraryAnswerability.defineFallbackPreference()
                            ? "[{0}] {1}: define fallback requested but the study metadata is not"
                                    + " Define-XML backed — rule will be skipped"
                            : "[{0}] {1}: the CDISC Library could not be consulted — rule will be"
                                    + " skipped rather than answered from a non-library source",
                    ruleId(run), function);
            throw unusable(function, ProviderNeed.Kind.LIBRARY,
                    "the CDISC Library could not be consulted");
        }
        return provider;
    }


    /**
     * Condition 3 of Fix #369 for the arms whose <em>empty</em> answer is a legitimate pass on a
     * working Library: under the degraded Define-XML opt-in an empty answer means "the define could
     * not answer this arm" and must SKIP (the retired {@code degradedAnswerOrSkip}).
     *
     * @param provider
     *            the provider that answered
     * @param run
     *            the evaluation run
     * @param function
     *            the calling function
     * @param answer
     *            the arm's answer
     * @return {@code answer}, unchanged
     * @throws UnusableProviderAnswerException
     *             when the provider is degraded and the answer is empty
     */
    static List<String> degradedAnswer(MetadataProvider provider, EvalRun run, String function,
            List<String> answer)
    {
        if (provider.isLibraryUnavailable() && answer.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1}: define fallback engaged but the Define-XML could not answer this"
                            + " function — rule will be skipped",
                    ruleId(run), function);
            throw unusable(function, ProviderNeed.Kind.LIBRARY,
                    "the Define-XML fallback could not answer");
        }
        return answer;
    }


    /**
     * The run's sponsor Define-XML provider, or the unusable signal. {@code RuleRunner}'s
     * no-provider arm SKIPs the rule before any binding is forced (the descriptor's {@code DEFINE}
     * capability); this is the second layer, as W0's exemplar carries it.
     *
     * @param run
     *            the evaluation run
     * @param function
     *            the calling function
     * @return the provider
     * @throws UnusableProviderAnswerException
     *             when no Define-XML is supplied
     */
    static MetadataProvider define(EvalRun run, String function)
    {
        MetadataProvider provider = run.ctx().getDefineProvider();
        if (provider == null)
        {
            throw unusable(function, ProviderNeed.Kind.DEFINE, "no Define-XML provider");
        }
        return provider;
    }


    /**
     * The run's dataset inventory. ⛔ A resolver that cannot enumerate the study is a wiring defect,
     * not a data condition, so it is <b>loud</b> (D-W4-5, D-W1-3 (vii)): the retired operations
     * answered {@code []} here, silently — the FDA-SD1078 shape.
     *
     * @param run
     *            the evaluation run
     * @param function
     *            the calling function
     * @return the inventory
     * @throws IllegalStateException
     *             when the run's resolver is not a {@link DatasetResolver.WithInventory}
     */
    static DatasetResolver.WithInventory inventory(EvalRun run, String function)
    {
        DatasetResolver resolver = run.ctx().getDatasetResolver();
        if (resolver instanceof DatasetResolver.WithInventory inventory)
        {
            return inventory;
        }
        throw new IllegalStateException(function + " needs a dataset inventory"
                + " (DatasetResolver.WithInventory) to enumerate the study, but the run's resolver"
                + " cannot — it answered nothing rather than an empty list");
    }


    /**
     * One list, broadcast to every row (W0's list shape, option 2), guarded against a {@code null}
     * element (NNL §1) and unmodifiable. Not {@code List.copyOf}: it throws a bare
     * {@code NullPointerException} naming nothing, before the guard that names the function.
     *
     * @param function
     *            the producing function, named by the guard
     * @param values
     *            the list
     * @return the broadcast vector
     */
    static Vector broadcast(String function, Collection<?> values)
    {
        return ConstVector.of(Collections.unmodifiableList(new ArrayList<>(values)),
                () -> function + "()");
    }


    /**
     * A static list argument — a list literal of string literals, which the compile seam
     * ({@code ExprCompiler.rejectNonLiteralListArguments}) guarantees for the parameters that read
     * one — as its texts. An absent optional argument is the empty list.
     *
     * @param arg
     *            the bound argument, or {@code null} when absent
     * @return the texts
     */
    static List<String> staticStrings(@Nullable Vector arg)
    {
        if (arg == null)
        {
            return List.of();
        }
        Object value = arg.value(0).resolved();
        if (value instanceof Collection<?> items)
        {
            List<String> out = new ArrayList<>(items.size());
            for (Object item : ListValueGuard.elements(items))
            {
                out.add(item.toString());
            }
            return out;
        }
        return value == null ? List.of() : List.of(value.toString());
    }


    /**
     * A static string argument (a string literal, guaranteed by the compile seam) as its text, or
     * {@code null} when the optional argument is absent.
     *
     * @param arg
     *            the bound argument, or {@code null}
     * @return the text, or {@code null}
     */
    static @Nullable String staticString(@Nullable Vector arg)
    {
        if (arg == null)
        {
            return null;
        }
        Object value = arg.value(0).resolved();
        return value == null ? null : value.toString();
    }


    /**
     * The signal {@code RuleRunner} turns into {@code SKIPPED}, naming the function and the
     * provider kind.
     *
     * @param function
     *            the function
     * @param kind
     *            the provider kind
     * @param detail
     *            what was unusable
     * @return the exception to throw
     */
    static UnusableProviderAnswerException unusable(String function, ProviderNeed.Kind kind,
            String detail)
    {
        return new UnusableProviderAnswerException(function, kind, detail);
    }


    /** The rule id for a log line, {@code ?} when the run carries none. */
    static String ruleId(EvalRun run)
    {
        String id = run.ctx().getRuleId();
        return id != null ? id : "?";
    }

}
