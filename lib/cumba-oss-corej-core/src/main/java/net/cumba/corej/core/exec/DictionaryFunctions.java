package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.EvalFunction;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.UngatedProviderReachException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The per-record external-dictionary registry functions. Wave 1
 * ({@code PLAN-function-surface-wave1} phase 3) ported the pair and the hierarchy from the retired
 * {@code VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR} / {@code _HIERARCHY} operations; wave 3
 * ({@code PLAN-per-row-functions}) ported the membership pair and the decode-presence test from
 * {@code VALID_EXTERNAL_DICTIONARY_VALUE} / {@code _CODE} / {@code DICTIONARY_HAS_DECODE}:
 * <ul>
 * <li>{@code valid_external_dictionary_value(name, external_dictionary_type=, dictionary_term_type=,
 * case_sensitive=)} and {@code valid_external_dictionary_code(…)} — one implementation under two
 * names (runbook R6: both authored names are kept), {@code true} on a row whose value is a term (or
 * code) of the dictionary at the {@code dictionary_term_type} level, a blank being valid;</li>
 * <li>{@code dictionary_has_decode(name, external_dictionary_type=, case_sensitive=)} —
 * {@code true} on a row whose code has any decode in the dictionary, a blank having none;</li>
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

    /** The term-membership function, as authored. */
    public static final String VALUE = "valid_external_dictionary_value";

    /** The code-membership function, as authored — the same implementation as {@link #VALUE}. */
    public static final String CODE = "valid_external_dictionary_code";

    /** The decode-presence function, as authored. */
    public static final String HAS_DECODE = "dictionary_has_decode";

    /** The parameter naming the dictionary type on every dictionary function. */
    public static final String TYPE_PARAMETER = "external_dictionary_type";

    /**
     * The optional {@code case_sensitive} flag of every dictionary function — since W1a BS1 any
     * dataset-level boolean, bound or inline ({@code PLAN-binding-substitutability} C10).
     */
    public static final String CASE_SENSITIVE_PARAMETER = "case_sensitive";

    /** The parameter naming the dictionary level on the membership functions. */
    public static final String LEVEL_PARAMETER = "dictionary_term_type";

    /**
     * Runbook W2 DI1 ({@code PLAN-engine-gap-dict-unsupported} §4.1, §5.1 Q1/Q2): the MedDRA level
     * a column reads, by its name's suffix — longest suffix first, so {@code AEHLGTCD} is
     * {@code HLGTCD} and never {@code HLGT}. {@code --DECOD} is the PT. {@code --BODSYS} is
     * deliberately absent: it may carry a secondary SOC — a hierarchy call reading it is a load
     * error since runbook W4 DI2 ({@link #checkLevels}).
     */
    private static final List<Map.Entry<String, String>> HIERARCHY_LEVEL_SUFFIXES = List.of(
            Map.entry("HLGTCD", "HLGTCD"), Map.entry("HLTCD", "HLTCD"), Map.entry("LLTCD", "LLTCD"),
            Map.entry("SOCCD", "SOCCD"), Map.entry("PTCD", "PTCD"), Map.entry("HLGT", "HLGT"),
            Map.entry("HLT", "HLT"), Map.entry("LLT", "LLT"), Map.entry("SOC", "SOC"),
            Map.entry("DECOD", "PT"));

    /**
     * The function {@code valid_external_dictionary_hierarchy}'s descriptor publishes (runbook W2
     * fold, R5 PB-L3): the levels the call's two columns read (DI1, {@link #hierarchyLevelOf}) are
     * bound INTO it at load by {@link #atLevels} — the compiler dispatches through the descriptor's
     * function, never around it to a hard-wired method, so a function registered under the name is
     * the one called.
     */
    public interface LevelScoped extends EvalFunction
    {

        /**
         * This function with the call's levels bound.
         *
         * @param childLevel
         *            the level of the child column, or {@code null} (no level: refused loudly)
         * @param parentLevel
         *            the level of the parent column, or {@code null}
         * @return the function the compiled call applies
         */
        EvalFunction atLevels(@Nullable String childLevel, @Nullable String parentLevel);
    }


    /**
     * {@link #hierarchy(EvalRun, List, String, String)} with its levels bound — the registered
     * implementation ({@link #HIERARCHY_FUNCTION}, levels {@code null}: never applied as such, the
     * compiler binds the call's levels; applied with a {@code null} level it fails loudly).
     *
     * @param childLevel
     *            the level of the child column, or {@code null}
     * @param parentLevel
     *            the level of the parent column, or {@code null}
     */
    public record Hierarchy(@Nullable String childLevel,
            @Nullable String parentLevel) implements LevelScoped
    {

        @Override
        public Object apply(EvalRun run, List<Vector> args)
        {
            return hierarchy(run, args, childLevel, parentLevel);
        }


        @Override
        public EvalFunction atLevels(@Nullable String aChildLevel, @Nullable String aParentLevel)
        {
            return new Hierarchy(aChildLevel, aParentLevel);
        }
    }

    /** The registered {@link #HIERARCHY} function, no level bound. */
    public static final Hierarchy HIERARCHY_FUNCTION = new Hierarchy(null, null);

    private DictionaryFunctions()
    {
    }


    /**
     * Runbook W2 DI1: the hierarchy level a column reads, derived at load from its name — the
     * authored spelling ({@code --HLT}), the resolved one ({@code AEHLT}) or a qualified one
     * ({@code AE.AEHLT}) all name the same level. {@code null} for a name that maps to no level
     * (e.g. {@code --BODSYS}, a {@code $} binding): such a call is a load error since runbook W4
     * DI2 ({@link #checkLevels}).
     *
     * @param aColumnName
     *            the column name as written in the call, or {@code null} when the argument is not a
     *            column reference
     * @return the house-format level ({@code PT}, {@code PTCD}, {@code LLT}, …), or {@code null}
     */
    public static @Nullable String hierarchyLevelOf(@Nullable String aColumnName)
    {
        if (aColumnName == null || aColumnName.startsWith("$"))
        {
            return null;
        }
        String name = aColumnName.substring(aColumnName.lastIndexOf('.') + 1)
                .toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> suffix : HIERARCHY_LEVEL_SUFFIXES)
        {
            if (name.endsWith(suffix.getKey()))
            {
                return suffix.getValue();
            }
        }
        return null;
    }


    /**
     * {@link #hierarchyLevelOf} of a bound argument — the level a column-reference argument names,
     * {@code null} for anything else (a computed operand, a literal). The one judgement the
     * compiler ({@code ExprCompiler}'s level binding), the capability need ({@code ProviderNeeds})
     * and the load-time seam ({@link #checkLevels}) share.
     *
     * @param anArgument
     *            the bound argument, or {@code null}
     * @return the level, or {@code null}
     */
    public static @Nullable String hierarchyLevelOfArgument(@Nullable Expr anArgument)
    {
        return hierarchyLevelOf(anArgument instanceof Expr.Ref ref ? ref.name() : null);
    }


    /**
     * Runbook W4 DI2 F2 ({@code PLAN-engine-gap-dict-unsupported}, the W2 P-B candidate's form F2;
     * coordinator W4-D11): the load-time seam of {@code valid_external_dictionary_hierarchy} on the
     * MedDRA dictionary ({@code external_dictionary_type="meddra"}; every other type reads its
     * bare-name map at any column, as before W4 — W4 fold, R5 P3-L3) — the child and the parent
     * must each be a column whose name names a MedDRA level ({@link #hierarchyLevelOf}). Since the
     * bare-name map is no lookup any more, a column that names none ({@code --BODSYS}, which may
     * carry a secondary SOC), a {@code $} binding or a computed operand has no level to read the
     * dictionary at; it is the rule's load error, never a silent all-rows answer. A call that does
     * not bind, and a literal where a column belongs, are left to the generic seam and stage A,
     * which name their own cause. ⚑ Not checked here (backlog, O11): a name level paired with a
     * code level, and a parent not above its child — both are off-path on every row (forms F1 / F3,
     * 0 carriers).
     *
     * @param aCall
     *            the call, as written (name-form bindings inlined)
     * @throws ExpressionException
     *             if the child or the parent names no MedDRA level
     */
    public static void checkLevels(Expr.Call aCall)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(aCall.name());
        if (descriptor == null)
        {
            return;
        }
        List<@Nullable Expr> bound;
        try
        {
            bound = ArgumentBinder.bind(descriptor, aCall);
        }
        catch (ExpressionException _)
        {
            return;
        }
        // W4 fold (R5 P3-L3): MedDRA's levels only — a call on another dictionary type reads its
        // bare-name map at any column, as before W4 (a non-literal type is not judged here)
        Expr type = bound.size() > 2 ? bound.get(2) : null;
        if (!(type instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING
                || !RuntimeDictionaryProvider.levelScopedHierarchy(String.valueOf(lit.value())))
        {
            return;
        }
        for (int i = 0; i < 2 && i < bound.size(); i++)
        {
            Expr argument = bound.get(i);
            // a literal is the column-reference seam's (stage A / the generic seam): "a quoted name
            // is a string, never a column" is the more precise refusal
            if (argument != null && !(argument instanceof Expr.Lit)
                    && hierarchyLevelOfArgument(argument) == null)
            {
                throw new ExpressionException(HIERARCHY
                        + " reads the dictionary at the levels its two columns name \u2014 "
                        + ExpressionPrinter.print(argument)
                        + " names no MedDRA level (write a --DECOD/--PTCD, --HLT/--HLTCD,"
                        + " --HLGT/--HLGTCD or --SOC/--SOCCD column)");
            }
        }
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
        String type = typeOf(args.get(2), run.rowCount());
        RuntimeDictionaryProvider provider = availableProvider(run, CODE_TERM_PAIR, type);
        boolean caseSensitive = caseSensitive(args.get(3), run.rowCount());
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
        return hierarchy(run, args, null, null);
    }


    /**
     * {@code valid_external_dictionary_hierarchy} with the levels the two columns read (runbook W2
     * DI1, {@link #hierarchyLevelOf}), which the compiler derives from the call's column names at
     * load. The house file's level-scoped section answers at those levels. ⛔ Runbook W4 DI2: there
     * is no bare-name fallback — a column that maps to no level is a load error
     * ({@link #checkLevels}), and a file that cannot answer at the child's level (no section, or no
     * such level) SKIPs the rule before any row is read ({@code RuleRunner}'s capability arm); if
     * either is reached here anyway, the function fails loudly (the rule reports ERROR) instead of
     * answering "off-path" on every row.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @param childLevel
     *            the level of the child column, or {@code null}
     * @param parentLevel
     *            the level of the parent column, or {@code null}
     * @return the rows whose child lies on the parent's hierarchy path, or whose child or parent is
     *         blank
     */
    public static BitSet hierarchy(EvalRun run, List<Vector> args, @Nullable String childLevel,
            @Nullable String parentLevel)
    {
        String type = typeOf(args.get(2), run.rowCount());
        RuntimeDictionaryProvider provider = availableProvider(run, HIERARCHY, type);
        // W4 fold (R5 P3-L3): the level scoping is MedDRA's; every other type answers from its
        // bare-name map as before W4 (answersHierarchyAt is true for it at any level)
        if (RuntimeDictionaryProvider.levelScopedHierarchy(type)
                && (childLevel == null || parentLevel == null))
        {
            throw new UngatedProviderReachException(HIERARCHY,
                    "with a column that names no MedDRA level (a load error)");
        }
        if (!provider.answersHierarchyAt(type, childLevel))
        {
            throw new UngatedProviderReachException(HIERARCHY,
                    "with external dictionary " + type + " unable to answer at " + childLevel + ": "
                            + provider.hierarchyGapDetail(type, String.valueOf(childLevel)));
        }
        boolean caseSensitive = caseSensitive(args.get(3), run.rowCount());
        Vector child = args.get(0);
        Vector parent = args.get(1);
        int rowCount = run.rowCount();
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            String childText = text(child.value(row).cell());
            String parentText = text(parent.value(row).cell());
            // H1: a blank child OR blank parent is on-path (no fire).
            if (childText.isEmpty() || parentText.isEmpty() || provider.onHierarchyPath(type,
                    childLevel, childText, parentLevel, parentText, caseSensitive))
            {
                out.set(row);
            }
        }
        return out;
    }


    /**
     * {@code valid_external_dictionary_value} and {@code valid_external_dictionary_code} — one
     * implementation, told apart only by the level the rule names: {@code args} are the bound value
     * column, dictionary type, {@code dictionary_term_type} and (optional, may be {@code null})
     * {@code case_sensitive}. The retired {@code evalValidExternalDictionaryValue} verbatim, per
     * row: a blank (or missing) value is valid — completeness is another rule's concern, and a
     * failed lookup on {@code ""} would false-fire the {@code == false} consequent; otherwise the
     * value must match the dictionary's preferred case at that level ({@code caseMatches}, the
     * D-TA-3 / Fix #266 default) or, with {@code case_sensitive=false}, be a member in any case
     * ({@code isValidTerm}).
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @param function
     *            the name the call was authored under ({@link #VALUE} or {@link #CODE}), for the
     *            tripwire's message
     * @return the rows whose value is valid at the level, or blank
     */
    public static BitSet termMembership(EvalRun run, List<Vector> args, String function)
    {
        String type = typeOf(args.get(1), run.rowCount());
        RuntimeDictionaryProvider provider = availableProvider(run, function, type);
        String level = typeOf(args.get(2), run.rowCount());
        boolean caseSensitive = caseSensitive(args.get(3), run.rowCount());
        Vector value = args.get(0);
        int rowCount = run.rowCount();
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            String term = text(value.value(row).cell());
            if (term.isEmpty() || (caseSensitive ? provider.caseMatches(type, level, term)
                    : provider.isValidTerm(type, level, term)))
            {
                out.set(row);
            }
        }
        return out;
    }


    /**
     * {@code dictionary_has_decode}: {@code args} are the bound code column, dictionary type and
     * (optional, may be {@code null}) {@code case_sensitive}. {@code true} on a row whose code has
     * any decode in the dictionary (a {@code containsKey} over its {@code pairs} / {@code
     * attributes} registries, the registry defaulting to the type); a blank (or missing) code holds
     * no decode ({@code false}). The retired {@code evalDictionaryHasDecode} verbatim.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the rows whose code has a decode
     */
    public static BitSet hasDecode(EvalRun run, List<Vector> args)
    {
        String type = typeOf(args.get(1), run.rowCount());
        RuntimeDictionaryProvider provider = availableProvider(run, HAS_DECODE, type);
        boolean caseSensitive = caseSensitive(args.get(2), run.rowCount());
        Vector code = args.get(0);
        int rowCount = run.rowCount();
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            String codeText = text(code.value(row).cell());
            if (!codeText.isEmpty() && provider.hasDecode(type, type, codeText, caseSensitive))
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


    /**
     * The dictionary type, read through {@link Minus#rawOperand} (W1a fold, R5 review A-L3): a
     * constant's member value, else row 0 — never row 0 of a zero-row table.
     */
    private static String typeOf(Vector typeArgument, int rowCount)
    {
        Object type = Minus.rawOperand(typeArgument, rowCount);
        return type == null ? "" : type.toString();
    }


    private static boolean caseSensitive(@Nullable Vector flag, int rowCount)
    {
        // D-TA-3 / Fix #266: case-SENSITIVE unless the rule authors case_sensitive=false. The
        // compiler hands an absent optional parameter through as a null slot. Since W1a BS1 the
        // flag is any dataset-level boolean (PLAN-binding-substitutability C10, Q4): a missing
        // value is the default, and a computed value of another type — reachable only where
        // stage A does not type the call — is the rule's ERROR (D35 "written wrong"), never a
        // silent "case-sensitive".
        // The value is read through Minus.rawOperand (R5 review A-L3): a zero-row table reads it as
        // absent — the default — never as row 0.
        Object value = Minus.rawOperand(flag, rowCount);
        if (value == null || value instanceof Boolean)
        {
            return !Boolean.FALSE.equals(value);
        }
        throw new net.cumba.corej.core.expr.RuleDefinitionException(
                "argument '" + CASE_SENSITIVE_PARAMETER
                        + "' takes a boolean (true or false), not the value " + value);
    }


    private static String text(IDataValue cell)
    {
        return cell.isMissingOrInvalid() ? "" : cell.getValueAsString();
    }

}
