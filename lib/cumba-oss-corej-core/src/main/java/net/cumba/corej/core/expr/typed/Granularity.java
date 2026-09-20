package net.cumba.corej.core.expr.typed;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The granularity half of the level product ({@code plans/SPEC-typed-expression-engine.md} §1.3):
 * {@code study < dataset < group(K) < record}. {@link Group} carries its key columns with
 * <b>set</b> semantics (D28d — a different key <em>order</em> does not split a grouping).
 *
 * <p>
 * The join (D5 — the finest of the operands) follows the chain, with one ruled exception: two
 * {@link Group}s over <em>different</em> key sets are incomparable, and their join is
 * {@link Simple#RECORD} (D39e — "if two group operands carried different keys they would be
 * incomparable … the finest level would be record").
 * </p>
 */
public sealed interface Granularity permits Granularity.Simple, Granularity.Group
{

    /** The three key-free granularities. */
    enum Simple implements Granularity
    {

        STUDY(0), DATASET(1), RECORD(3);

        private final int rank;

        Simple(int rank)
        {
            this.rank = rank;
        }


        @Override
        public int rank()
        {
            return rank;
        }


        @Override
        public String describe()
        {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }


    /** {@code group(K)} — one evaluation per distinct key tuple over the key column set. */
    record Group(Set<String> keys) implements Granularity
    {

        public Group
        {
            if (keys.isEmpty())
            {
                throw new IllegalArgumentException("group granularity requires at least one key");
            }
            keys = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        }


        @Override
        public int rank()
        {
            return 2;
        }


        @Override
        public String describe()
        {
            return "group(" + String.join(", ", keys) + ")";
        }
    }

    /** Position in the {@code study < dataset < group < record} chain. */
    int rank();


    /** A short human-readable spelling for error messages. */
    String describe();


    /**
     * The finest of the two granularities (D5). Two {@link Group}s with different key sets join to
     * {@link Simple#RECORD} (D39e); with equal key sets the group survives.
     */
    static Granularity join(Granularity a, Granularity b)
    {
        if (a.equals(b))
        {
            return a;
        }
        if (a instanceof Group && b instanceof Group)
        {
            return Simple.RECORD;
        }
        return a.rank() >= b.rank() ? a : b;
    }

}
