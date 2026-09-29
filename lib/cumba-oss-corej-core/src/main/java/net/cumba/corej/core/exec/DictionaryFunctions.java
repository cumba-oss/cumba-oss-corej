package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.UngatedProviderReachException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The two per-record external-dictionary registry functions of wave 1
 * ({@code PLAN-function-surface-wave1} phase 3), ported from the retired
 * {@code VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR} / {@code _HIERARCHY} operations:
 * <ul>
 * <li>{@code valid_external_dictionary_code_term_pair(name, external_dictionary_term_variable,
 * external_dictionary_type=, case_sensitive=)} — {@code true} on a row whose code (the target
 * column) decodes to the term column's value in the named dictionary;</li>
 * <li>{@code valid_external_dictionary_hierarchy(name, dictionary_parent, external_dictionary_type=,
 * case_sensitive=)} — {@code true} on a row whose child term lies on the hierarchy path of the
 * parent column's value.</li>
 * </ul>
 * <p>
 * Both column parameters are column references, so the {@code --} in {@code --STRESC} /
 * {@code --RESCAT} / {@code --HLT} / {@code --SOC} is resolved by the shared typed-parameter path
 * and a quoted name is a string literal that fails to load (R1): EC-36's hand-written
 * {@code dictionary_parent} prefix arm is unrepresentable, not merely fixed. A blank code or term
 * (child or parent) is {@code true} — valid, no fire (H1); comparison is case-sensitive unless
 * {@code case_sensitive=false} (D-TA-3 / Fix #266).
 * </p>
 * <p>
 * ⭐ <b>Provider capability {@code ProviderNeed.dictionary("external_dictionary_type")}</b>
 * (D-W1-3): the run-level gates — the runner's no-provider SKIP, the injected
 * {@code dictionary_available(<type>)} Precondition for an inline call, the forecast and the
 * load-time typeless-dictionary guard — all read it through {@code ProviderNeeds}, so a rule whose
 * dictionary type is not loaded reports SKIPPED before any row is read. ⛔ If a function is ever
 * <b>reached</b> with no provider or with its type unavailable, it fails loudly (the rule reports
 * ERROR): it never answers the {@code PREDICATE} default {@code false}, which the corpus's
 * {@code $x == false} Checks would turn into a finding on every row. That arm is a tripwire for a
 * gate hole, never a SKIP path (D-W1-3 (vii)).
 * </p>
 */
public final class DictionaryFunctions
{

    /** The code↔term pairing function, as authored. */
    public static final String CODE_TERM_PAIR = "valid_external_dictionary_code_term_pair";

    /** The hierarchy-path function, as authored. */
    public static final String HIERARCHY = "valid_external_dictionary_hierarchy";

    /** The parameter naming the dictionary type on both functions. */
    public static final String TYPE_PARAMETER = "external_dictionary_type";

    private DictionaryFunctions()
    {
    }


    /**
     * {@code valid_external_dictionary_code_term_pair}: {@code args} are the bound code column,
     * term column, dictionary type and (optional, may be {@code null}) {@code case_sensitive}.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the rows whose code decodes to the term, or whose code or term is blank
     */
    public static BitSet codeTermPair(EvalRun run, List<Vector> args)
    {
        String type = typeOf(args.get(2));
        RuntimeDictionaryProvider provider = availableProvider(run, CODE_TERM_PAIR, type);
        boolean caseSensitive = caseSensitive(args.get(3));
        Vector code = args.get(0);
        Vector term = args.get(1);
        int rowCount = run.rowCount();
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            String codeText = text(code.value(row).cell());
            String termText = text(term.value(row).cell());
            // H1: a blank code OR blank term is a valid pair — completeness is another rule's
            // concern, and a failed lookup on "" would false-fire the `== false` consequent.
            if (codeText.isEmpty() || termText.isEmpty()
                    || provider.codeDecodePair(type, type, codeText, termText, caseSensitive))
            {
                out.set(row);
            }
        }
        return out;
    }


    /**
     * {@code valid_external_dictionary_hierarchy}: {@code args} are the bound child column, parent
     * column, dictionary type and (optional, may be {@code null}) {@code case_sensitive}.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the rows whose child lies on the parent's hierarchy path, or whose child or parent is
     *         blank
     */
    public static BitSet hierarchy(EvalRun run, List<Vector> args)
    {
        String type = typeOf(args.get(2));
        RuntimeDictionaryProvider provider = availableProvider(run, HIERARCHY, type);
        boolean caseSensitive = caseSensitive(args.get(3));
        Vector child = args.get(0);
        Vector parent = args.get(1);
        int rowCount = run.rowCount();
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            String childText = text(child.value(row).cell());
            String parentText = text(parent.value(row).cell());
            // H1: a blank child OR blank parent is on-path (no fire).
            if (childText.isEmpty() || parentText.isEmpty()
                    || provider.onHierarchyPath(type, childText, parentText, caseSensitive))
            {
                out.set(row);
            }
        }
        return out;
    }


    /**
     * D-W1-3 (vii), the loud arm: the provider the run carries, holding a dictionary of
     * {@code type}. Anything else means the function was reached past a gate that should have
     * SKIPPED the rule, and the rule reports ERROR rather than a silent verdict.
     */
    private static RuntimeDictionaryProvider availableProvider(EvalRun run, String function,
            String type)
    {
        RuntimeDictionaryProvider provider = run.ctx().getDictionaryProvider();
        if (provider == null)
        {
            throw new UngatedProviderReachException(function,
                    "with no external-dictionary provider (external_dictionary_type=\"" + type
                            + "\")");
        }
        if (!provider.isAvailable(type))
        {
            throw new UngatedProviderReachException(function,
                    "with external dictionary " + type + " " + provider.unavailabilityDetail(type));
        }
        return provider;
    }


    private static String typeOf(Vector typeArgument)
    {
        Object type = typeArgument.value(0).resolved();
        return type == null ? "" : type.toString();
    }


    private static boolean caseSensitive(@Nullable Vector flag)
    {
        // D-TA-3 / Fix #266: case-SENSITIVE unless the rule authors case_sensitive=false. The
        // compiler hands an absent optional parameter through as a null slot.
        return flag == null || !Boolean.FALSE.equals(flag.value(0).resolved());
    }


    private static String text(IDataValue cell)
    {
        return cell.isMissingOrInvalid() ? "" : cell.getValueAsString();
    }

}
