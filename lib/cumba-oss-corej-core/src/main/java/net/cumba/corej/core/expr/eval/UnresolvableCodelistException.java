package net.cumba.corej.core.expr.eval;

/**
 * A {@code var_codelist_extensible("LIBRARY")} read hit a variable whose bound codelist cannot be
 * resolved in the loaded controlled terminology (F-corej-ct-02, {@code
 * plans/PLAN-define-driven-ct-selection.md} P1).
 *
 * <p>
 * The unresolvable case must be <em>distinguishable</em>: every shipped consumer gates on
 * {@code var_codelist_extensible("LIBRARY") == false}, so both of the old miss behaviours — a
 * defaulted {@code true} and a silent absence — made the guard false and the rule quietly not fire.
 * Wrong or missing CT then looked exactly like a clean run (the {@code silent-disarming-guards}
 * class). Throwing here lets {@code RuleRunner}'s terminal catch convert the read into a rule-level
 * {@code SKIPPED} with a stated reason, mirroring the Python engine's
 * {@code MissingDataError → SKIPPED} disposition for {@code codelist_extensible}.
 * </p>
 *
 * <p>
 * ⚠ Thrown only for a variable that <b>has</b> a bound codelist ({@code codelist} present in its
 * LIBRARY-level metadata) whose {@code codelist_extensible} did not resolve. A variable with no
 * codelist at all stays absent → the rule's guard no-fires, which is correct (D4).
 * </p>
 */
public class UnresolvableCodelistException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public UnresolvableCodelistException(String aDomain, String aVariable, String aCodelist)
    {
        super("the codelist '" + aCodelist + "' bound to " + aDomain + "." + aVariable
                + " cannot be resolved in the loaded controlled terminology — the CT package"
                + " selection may not match the terminology this study uses");
    }
}
