package net.cumba.corej.core.exec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Opt-in, in-memory recorder of which evaluation backend — native expression evaluator vs the
 * legacy {@code CheckEvaluator} — actually ran each rule during a validation pass (Epic A2 of the
 * native-only expression-engine plan).
 *
 * <p>
 * Off by default and zero-overhead when no session is active: the dispatch sites
 * ({@link RuleRunner} row-level + metadata-native paths, {@link CohortRunner}) call
 * {@link #record(String, Backend)} on every evaluation, but it is a no-op unless a test/diagnostic
 * program has called {@link #enable()}. The intended consumer is a test program that flips
 * {@code nativeEval} on, runs a validation, and asserts the rules it expects to run natively
 * actually did (and did not silently fall back to legacy).
 * </p>
 *
 * <p>
 * Since the legacy {@code CheckEvaluator} retirement (PLAN-native-only-engine-retire-legacy Phase
 * 5) the only backend is {@link Backend#NATIVE}: the recorder remains as the corpus tests' sanity
 * hook that every executed rule produced a native verdict (an unexpectedly absent entry — a rule
 * that executed without recording — would surface a routing regression).
 * </p>
 *
 * <p>
 * Thread-safe: the active map is a {@link ConcurrentHashMap}, so cohort / parallel execution is
 * captured correctly.
 * </p>
 */
public final class NativeExecutionRecorder
{

    /** Which backend evaluated a rule's Check — native-only since the legacy retirement. */
    public enum Backend
    {
        /** The native expression evaluator ({@code NativeExprEvaluator}). */
        NATIVE
    }

    private static volatile @Nullable ConcurrentHashMap<String, Backend> active;

    private NativeExecutionRecorder()
    {
    }


    /** Starts (or restarts) a recording session, discarding any prior data. */
    public static void enable()
    {
        active = new ConcurrentHashMap<>();
    }


    /** {@code true} while a recording session is active. */
    public static boolean isEnabled()
    {
        return active != null;
    }


    /**
     * Ends the current session and returns an immutable snapshot of {@code ruleId -> backend}.
     * Empty when no session was active.
     */
    public static Map<String, Backend> disable()
    {
        ConcurrentHashMap<String, Backend> snapshot = active;
        active = null;
        return snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }


    /**
     * Records that {@code ruleId} evaluated on {@code backend}. No-op when no session is active or
     * {@code ruleId} is {@code null}. Idempotent.
     */
    public static void record(@Nullable String ruleId, Backend backend)
    {
        ConcurrentHashMap<String, Backend> a = active;
        if (a == null || ruleId == null)
        {
            return;
        }
        a.putIfAbsent(ruleId, backend);
    }

}
