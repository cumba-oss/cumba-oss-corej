package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Bounded collector for a single rule execution's {@link Violation}s. It materialises at most
 * {@code cap} findings while always counting the <em>true</em> total, so a high-cardinality rule
 * cannot exhaust the heap yet the report can still state how many violations actually occurred.
 *
 * <p>
 * One sink is created per rule execution (per dataset) and threaded across every emit site of that
 * rule — including the per-variable loop of Variable-Metadata rules — so the cap is per (rule,
 * dataset), not per variable. The canonical per-{@link java.util.BitSet} loop is:
 * </p>
 *
 * <pre>
 * int card = bits.cardinality();
 * int r = bits.nextSetBit(0);
 * int taken = 0;
 * while (r &gt;= 0 &amp;&amp; sink.wantsMore())
 * {
 *     sink.store(buildViolation(r));
 *     taken++;
 *     r = bits.nextSetBit(r + 1);
 * }
 * sink.recordSkipped(card - taken); // count the unmaterialised remainder
 * </pre>
 *
 * <p>
 * <b>De-duplication on an expanded table</b> ({@code PLAN-merged-evaluation-table} §3.8, MET #22,
 * P-4, P-18): a record-level site on a table whose rows repeat a primary record asks {@link #claim}
 * before it builds a finding, with the finding's <em>identity</em> — read from the cells at the
 * emit site, never from the rendered strings (a missing and a literal {@code "."} render alike). A
 * finding equal to one already stored is not stored and not counted: two expanded rows that report
 * the same thing are <b>one</b> finding, the first occurrence kept. {@link #wantsMore()} then
 * counts <b>distinct</b> findings. Nothing is claimed on a table whose rows are distinct records,
 * so there the sink is exactly what it always was. The identities kept are those of the stored
 * findings only (each one is claimed just before it is stored).
 * </p>
 *
 * <p>
 * ⚠ <b>Past the cap the total is an upper bound</b> on an expanded table (P-6): the rows the loop
 * no longer visits are counted by {@link #recordSkipped} as they are, duplicates included —
 * computing every identity past the cap would defeat the cap. Below the cap it is exact.
 * </p>
 *
 * <p>
 * Not thread-safe: a sink belongs to one rule execution running on one thread.
 * </p>
 */
public final class ViolationSink
{

    private final int cap;

    private final List<Violation> stored;

    private long total;

    /** The identities claimed so far — {@code null} until the first {@link #claim}. */
    private @Nullable Set<Object> claimed;

    /**
     * @param cap
     *            maximum number of {@link Violation}s to materialise; {@link Integer#MAX_VALUE} (or
     *            any value larger than the dataset) means effectively unlimited.
     */
    public ViolationSink(int cap)
    {
        this.cap = Math.max(0, cap);
        this.stored = new ArrayList<>(Math.min(this.cap, 64));
    }


    /** {@code true} while the materialised list still has room (size &lt; cap). */
    public boolean wantsMore()
    {
        return stored.size() < cap;
    }


    /**
     * Materialises one finding and counts it. The caller must guard with {@link #wantsMore()}; this
     * keeps the (potentially expensive) {@link Violation} construction off the hot path once the
     * cap is reached.
     */
    public void store(Violation v)
    {
        stored.add(v);
        total++;
    }


    /**
     * Claims a finding's identity before the caller builds and {@linkplain #store stores} it:
     * answers {@code true} when no finding with an equal identity was stored yet — the caller then
     * stores it — and {@code false} for a duplicate, which the caller drops uncounted (§3.8: the
     * first occurrence is kept). Only the record-level sites of a table whose rows repeat a primary
     * record call it.
     *
     * @param identity
     *            the finding's identity — equal exactly when the two findings are one (P-4: by
     *            value identity, P-18: read from the cells)
     * @return whether the finding is new
     */
    public boolean claim(Object identity)
    {
        Set<Object> seen = claimed;
        if (seen == null)
        {
            seen = new HashSet<>();
            claimed = seen;
        }
        return seen.add(identity);
    }


    /**
     * Counts one finding, materialising it only if there is still room. Convenience for low-volume
     * emit sites (dataset-level / per-variable) where constructing the {@link Violation} is cheap.
     * High-cardinality per-row loops should instead guard with {@link #wantsMore()} +
     * {@link #store} and finish with {@link #recordSkipped} so no {@link Violation} is built past
     * the cap.
     */
    public void add(Violation v)
    {
        if (wantsMore())
        {
            stored.add(v);
        }
        total++;
    }


    /**
     * Accounts for {@code n} findings that were counted but deliberately not materialised (the
     * fast-forward path once {@link #wantsMore()} is false). Pair with a BitSet's
     * {@code cardinality()} to record the true total without building the remaining objects.
     */
    public void recordSkipped(long n)
    {
        if (n > 0)
        {
            total += n;
        }
    }


    /**
     * The materialised findings (size &le; cap) as an immutable list. Copied defensively — the list
     * is bounded by the cap, so the copy is cheap and keeps the sink's internals encapsulated.
     */
    public List<Violation> stored()
    {
        return List.copyOf(stored);
    }


    /** The true number of violations, which may exceed {@link #stored()} size. */
    public long total()
    {
        return total;
    }
}
