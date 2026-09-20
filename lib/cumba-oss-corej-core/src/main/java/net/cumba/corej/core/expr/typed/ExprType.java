package net.cumba.corej.core.expr.typed;

/**
 * The static type of an expression node — the base-type table of the typed-expression specification
 * ({@code plans/SPEC-typed-expression-engine.md} §1.1, D91a).
 *
 * <p>
 * {@code missing} is deliberately <b>not</b> a type: it is a bottom element inhabiting every type
 * (D11/D12), so it never appears in a static type. {@link Unknown#UNKNOWN} is different — it is the
 * stage-A checker's <em>"not statically known"</em>, used for values whose type only stage B (the
 * per-dataset bind) can decide, such as a dereferenced column. Unknown is compatible with
 * everything: stage A reports only known-vs-known conflicts (D10 — per-dataset typing is stage B's
 * job).
 * </p>
 */
public sealed interface ExprType
        permits
        ExprType.Primitive,
        ExprType.ListOf,
        ExprType.SetOf,
        ExprType.Unknown
{

    /** The scalar base types of spec §1.1, plus the two parameter-only types. */
    enum Primitive implements ExprType
    {

        STRING,
        NUMBER,
        BOOLEAN,
        /** ISO-8601 calendar value, partial precision retained (§1.1; not modellable as string). */
        DATE,
        /** Time-of-day, partial precision retained (§1.1). */
        TIME,
        /** Literal-only, never computed, never stored in a column (§1.1). */
        REGEX,
        /**
         * ⭐ A name is not a string (§1.1) — {@code varname()}, a bare column operand, a quoted
         * name. Dereferences implicitly in value position to {@link Unknown#UNKNOWN}, because the
         * cell type is a stage-B fact (§1.2, D10).
         */
        COLUMN_REFERENCE,
        /** The closed parameter-only enum {@code {DATA, DEFINE, LIBRARY}} (§1.1). */
        METADATA_LEVEL;

        @Override
        public String describe()
        {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }


    /** {@code list<T>} — ordered, with duplicates (§1.5). */
    record ListOf(ExprType element) implements ExprType
    {

        @Override
        public String describe()
        {
            return "list<" + element.describe() + ">";
        }
    }


    /** {@code set<T>} — unordered, no duplicates, order not observable (§1.5). */
    record SetOf(ExprType element) implements ExprType
    {

        @Override
        public String describe()
        {
            return "set<" + element.describe() + ">";
        }
    }


    /**
     * Not statically known at stage A — a dereferenced column, a {@code $}-operation result, an
     * element the table does not yet specify. Compatible with every type by definition.
     */
    enum Unknown implements ExprType
    {

        UNKNOWN;

        @Override
        public String describe()
        {
            return "unknown";
        }
    }

    /** A short human-readable spelling for error messages. */
    String describe();


    /**
     * The type this node contributes in <b>value</b> position: a {@link Primitive#COLUMN_REFERENCE}
     * dereferences implicitly to {@link Unknown#UNKNOWN} (spec §1.2 — the cell type is stage B's),
     * every other type is itself.
     */
    default ExprType dereference()
    {
        return this == Primitive.COLUMN_REFERENCE ? Unknown.UNKNOWN : this;
    }


    /** Whether this is a {@code list<T>} or {@code set<T>}. */
    default boolean isCollection()
    {
        return this instanceof ListOf || this instanceof SetOf;
    }


    /**
     * Stage-A compatibility of two types, after any dereference the caller intends: unknown is
     * compatible with everything; collections are compatible when their element types are; two
     * known scalars are compatible only when equal. This is deliberately a <em>known-vs-known</em>
     * conflict test — stage A must never reject what only stage B can decide (D10).
     */
    static boolean compatible(ExprType a, ExprType b)
    {
        if (a == Unknown.UNKNOWN || b == Unknown.UNKNOWN)
        {
            return true;
        }
        if (a instanceof ListOf la)
        {
            return b.isCollection() && compatible(la.element(), elementOf(b));
        }
        if (a instanceof SetOf sa)
        {
            return b.isCollection() && compatible(sa.element(), elementOf(b));
        }
        if (b.isCollection())
        {
            return false;
        }
        return a == b;
    }


    /** The element type of a collection type; {@link Unknown#UNKNOWN} for anything else. */
    static ExprType elementOf(ExprType t)
    {
        return switch (t)
        {
        case ListOf l -> l.element();
        case SetOf s -> s.element();
        default -> Unknown.UNKNOWN;
        };
    }

}
