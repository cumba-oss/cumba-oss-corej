package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.eval.spi.BuiltinFunctions;
import net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls;
import net.cumba.datatable.io.GenericServiceFactory;
import org.junit.jupiter.api.Test;

/**
 * R2-8 — the gate behind {@link BroadcastFold#isDatasetFactBoolCall}'s prose invariant.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>
 * {@code isDatasetFactBoolCall} used to consult a hand-written roster of admissible operator names.
 * L1 replaced that with a <em>descriptor</em> test — any registered {@code BOOLEAN} function with a
 * non-null {@link FunctionDescriptor#fn()}, over broadcast-constant arguments, folds. That was the
 * right move: {@code RulePackageLoader.isBroadcastVerdictExpr} already asked the question without a
 * roster, so the load-time flag and the runtime fold <b>disagreed for every non-roster BOOL
 * call</b>, and the roster itself was arbitrary ({@code empty} was in it, its own registered alias
 * {@code is_missing} was not). But it also flipped the default from fail-safe to <b>fail-open</b>:
 * an unrecognised BOOLEAN registration is now admitted where the roster left it
 * {@link BroadcastFold.Verdict#UNKNOWN}.
 * </p>
 *
 * <p>
 * The invariant that makes fail-open safe — <em>every registered BOOLEAN function with an
 * implementation is <b>row-independent given broadcast-constant arguments</b></em> — was stated in
 * prose only. A BOOLEAN {@link FunctionProvider} added to the classpath tomorrow whose {@code fn()}
 * reads the table <em>per row</em> would be silently broadcast-flagged, turning N row findings into
 * one dataset finding with nothing red.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Row-independence, not context-freedom</b> (round 3). The invariant used to be spelled
 * "reads neither the table nor the evaluation context", and two shipped builtins already violate
 * that spelling while being correctly folded: {@code library_available} and
 * {@code dictionary_available} carry an {@code fn()} and read
 * {@code run.ctx().getLibraryProvider()} / {@code getDictionaryProvider()}. A provider is a
 * property of the RUN, not of the row, so their verdict is the same for every row and folding it is
 * right. Both pass the assertions below, and must keep passing.
 * </p>
 *
 * <h2>⚠⚠ What is asserted is PROVENANCE, not row-independence</h2>
 *
 * <p>
 * Read this before citing the test as a purity gate: <b>no assertion below inspects an
 * {@code fn()}.</b> All three arms are about where a descriptor came from. A new BOOLEAN added to
 * {@code BuiltinFunctions} whose implementation reads per-row table state passes every one of them
 * untouched — the only thing that reds is {@code BuiltinFunctionsRegistrationTest}'s name/arity
 * roster, which the author updates in the same edit by construction. What this test buys is that an
 * implemented BOOLEAN cannot reach the fold from anywhere <em>except</em> a door a reviewer is
 * standing at; the purity judgement itself is the reviewer's and has no mechanism behind it.
 * {@code BroadcastFold.isDatasetFactBoolCall}'s javadoc says the same, deliberately.
 * </p>
 *
 * <h2>What is asserted, and why it needs no hand-maintained list</h2>
 *
 * <ol>
 * <li><b>Every implemented BOOLEAN function on <em>this module's test classpath</em> comes from
 * {@link BuiltinFunctions}.</b> Derived on both sides — the SPI is enumerated exactly the way
 * {@link FunctionRegistry} enumerates it — so a new provider contributing an implemented BOOLEAN
 * reds here, and the reviewer is asked the row-independence question before it can broadcast.
 * {@code BuiltinFunctions}' own roster is pinned name-for-name by
 * {@code BuiltinFunctionsRegistrationTest}, so adding one there is already a reviewed edit.</li>
 * <li><b>{@link CompilerDispatchedCalls} contributes no implemented BOOLEAN.</b> That provider
 * registers the group / presence operators for their <em>signatures</em> only, and every one of
 * them reads the table or context — {@code fn() == null} is the exclusion
 * {@code isDatasetFactBoolCall}'s javadoc relies on, and this is the assertion that it still holds.
 * </li>
 * <li><b>Non-vacuity.</b> The admissible set is large and non-empty, so a provider load that
 * silently returned nothing could not pass 1 and 2 by finding nothing at all.</li>
 * </ol>
 *
 * <p>
 * ⚑ <b>The other thing it cannot gate</b>: this test runs in this module, so the classpath it
 * enumerates is that module's. A {@link FunctionProvider} shipped by a <em>downstream</em> module
 * (the define-conformance module, or an embedder's jar) contributing an implemented BOOLEAN would
 * be picked up by {@link FunctionRegistry} at runtime and admitted by
 * {@code isDatasetFactBoolCall}, with this assertion green one module upstream. No such provider
 * exists anywhere in the stack today (verified round 3).
 * </p>
 *
 * <p>
 * ⚑ <b>What this cannot gate, deliberately</b>: an embedder calling
 * {@link FunctionRegistry#register(FunctionDescriptor)} at runtime. No test can see a registration
 * that exists only in someone else's process. Accepted because the residual hazard is
 * <b>multiplicity, not value</b> — a leaf that folds {@code TRUE} yields one dataset-level finding
 * where the row path yielded N, and the {@code FALSE} direction is observationally identical.
 * </p>
 */
class BroadcastBoolFunctionInvariantTest
{

    /**
     * Enumerates {@link FunctionProvider}s through the project SPI, exactly as
     * {@code FunctionRegistry.ProviderFactory} does — reading the registry itself would also see
     * programmatic registrations left behind by other tests in the same JVM.
     */
    private static final class Providers extends GenericServiceFactory<FunctionProvider, Object>
    {

        private Providers()
        {
            super(FunctionProvider.class);
        }


        @Override
        public List<FunctionProvider> getSuppliers()
        {
            return super.getSuppliers();
        }
    }

    /** The names a provider contributes that {@code isDatasetFactBoolCall} would admit. */
    private static Set<String> admissible(FunctionProvider provider)
    {
        Set<String> out = new TreeSet<>();
        for (FunctionDescriptor d : provider.functions())
        {
            if (d.kind() == FunctionKind.BOOLEAN && d.fn() != null)
            {
                out.add(d.name());
            }
        }
        return out;
    }


    @Test
    void everyBroadcastAdmissibleBooleanComesFromTheBuiltinProvider()
    {
        Set<String> all = new TreeSet<>();
        List<FunctionProvider> providers = new Providers().getSuppliers();
        assertTrue(providers.size() >= 2,
                "the SPI must still discover the providers — found " + providers.size());
        providers.forEach(p -> all.addAll(admissible(p)));
        assertEquals(admissible(new BuiltinFunctions()), all,
                "a provider other than BuiltinFunctions contributes an implemented BOOLEAN"
                        + " function. isDatasetFactBoolCall will broadcast-flag it over"
                        + " dataset-fact arguments: confirm its verdict is ROW-INDEPENDENT given"
                        + " broadcast-constant arguments before widening this assertion. (Reading"
                        + " the EvaluationContext is fine and two shipped builtins do it — a"
                        + " provider is a property of the run. Reading the TABLE per row is not.)");
    }


    @Test
    void compilerDispatchedBooleansCarryNoImplementation()
    {
        assertEquals(Set.of(), admissible(new CompilerDispatchedCalls()),
                "the compiler-dispatched boolean calls are registered for their SIGNATURES only;"
                        + " every one of them reads the table or context, so giving one an fn()"
                        + " would silently make e.g. contains_all($a, $b) fold to one dataset-level"
                        + " verdict");
    }


    @Test
    void theAdmissibleSetIsNotVacuous()
    {
        Set<String> builtin = admissible(new BuiltinFunctions());
        assertTrue(builtin.size() > 30,
                "only " + builtin.size() + " implemented BOOLEAN builtins — a provider that"
                        + " silently returned nothing would pass the two assertions above"
                        + " vacuously");
        assertTrue(builtin.contains("empty") && builtin.contains("is_missing"),
                "the two spellings of one EvalFunction that the retired roster disagreed about"
                        + " must both be here: " + builtin);
    }
}
