package net.cumba.corej.core.model;

import org.jspecify.annotations.Nullable;

/**
 * One of a rule's {@code Bindings:} entries, in <b>authored order</b>, as either of the two kinds a
 * binding can have since wave 0 of {@code plans/RUNBOOK-operations-to-functions.md}
 * ({@code PLAN-binding-expressions} §5.0):
 * <ul>
 * <li>{@link OfOperation} — the binding's expression is a single top-level {@link OperationType}
 * call, so it keeps today's path ({@link Operation} → {@code OperationExecutor}, ruling
 * D-W0-1);</li>
 * <li>{@link CompiledBinding} — any other well-typed expression, compiled like the {@code Check}
 * (SPEC §3.1: <i>"every parameter accepts ANY expression of the right type"</i>).</li>
 * </ul>
 *
 * <p>
 * ⛔ <b>This is the view for every reader that resolves a {@code $}-name, orders bindings or walks
 * them</b> — {@link Rule#bindingOrder()}. A reader that iterates {@code Rule.getOperations()} alone
 * silently misses every compiled binding, which is the failure §5.0 exists to prevent; the sealed
 * switch here is what makes a new reader handle both kinds, and the engine's
 * {@code BindingReaderCensusTest} pins every reader of the two underlying lists.
 * </p>
 */
public sealed interface BoundBinding permits BoundBinding.OfOperation, CompiledBinding
{

    /**
     * The {@code $}-name this binding defines, or {@code null} for an operation authored without a
     * name (only a programmatic rule; the loader rejects a nameless compiled binding).
     *
     * @return the binding name
     */
    @Nullable
    String name();

    /**
     * An operation binding: the executor-internal bound-argument record of a single top-level
     * {@link OperationType} call.
     *
     * @param operation
     *            the operation record, as {@code Rule.getOperations()} holds it
     */
    record OfOperation(Operation operation) implements BoundBinding
    {

        @Override
        public @Nullable String name()
        {
            return operation.getId();
        }
    }

}
