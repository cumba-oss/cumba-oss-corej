package net.cumba.corej.core.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * EC-45 — the value an {@link OperationType} publishes when it has <em>no answer</em> for a row: no
 * group matched the row's key, no record satisfied the operation's filter, the referenced library
 * holds nothing for the looked-up name.
 *
 * <p>
 * <b>Every operation declares one. There is no second policy.</b> The declaration is a property of
 * the operator's <em>codomain</em>, decided once here, rather than of whichever
 * {@link net.cumba.corej.core.exec.GroupedResult} constructor the neighbouring evaluator happened
 * to copy — copying is how the pre-EC-45 drift arose, where the same {@code distinct} operator
 * answered {@code List.of()} ungrouped and {@code null} grouped. Because the constructor takes this
 * classification as a mandatory argument, a new operator cannot be added without choosing one.
 * </p>
 *
 * <p>
 * The check then computes honestly over the declared value and its leaf fires, or does not, on its
 * own terms. <b>Applicability is scope's job, never the algebra's</b> — a rule that should not have
 * run is SKIPped by {@code Scope.Variables} (including the qualified {@code DATASET.VARIABLE}
 * form), not silenced inside a leaf. A declaration is therefore never "do not fire"; it is always a
 * value.
 * </p>
 *
 * <p>
 * For operations that build a {@link net.cumba.corej.core.exec.GroupedResult} the declaration is
 * operative: it becomes {@code GroupedResult.missingKeyDefault} and is what a comparison operand
 * and the rendered {@code $var} both read. For the dataset-level operations that broadcast a single
 * value it is documentation of the same contract at the same altitude — the codomain a future
 * grouped variant would have to honour.
 * </p>
 */
public enum EmptyResult
{

    /**
     * Count codomain — nothing matched is <b>zero</b>, a real answer. {@code record_count} over a
     * group with no records genuinely counted zero records, so {@code $count <= 1} must fire rather
     * than be skipped.
     */
    COUNT(0L),

    /**
     * Set / list codomain — nothing matched is <b>the empty set</b>. {@code X not in} the empty set
     * is vacuously true and fires; {@code X in} the empty set is vacuously false. This is the
     * closed-world reading, and the one the ungrouped {@code distinct} has always used.
     */
    SET(List.of()),

    /**
     * Boolean-predicate codomain — nothing matched is <b>{@code false}</b>. The predicate was
     * evaluated and did not hold; it is not unknown.
     */
    PREDICATE(false),

    /**
     * Closed-world scalar lookup — nothing held for the looked-up name is the <b>empty string</b>,
     * the same vacuous-truth reading {@link #SET} takes. An unknown {@code RDOMAIN} means "the
     * library holds no class for it", not "the class is undefined", so
     * {@code $class not_equal_to "EVENTS"} fires.
     */
    EMPTY_TEXT(""),

    /**
     * Scalar / extremum / derived-value codomain — the calculation was <b>not possible</b>, so
     * there is no value. Today's {@code null}, which the comparison folds to {@code ""} and the
     * check fires over: a populated derived variable whose inputs cannot support it is
     * unverifiable, and that is worth reporting.
     */
    MISSING(null);

    /**
     * Every declared value is deeply immutable — a boxed {@code Long}, {@link List#of()}, a boxed
     * {@code Boolean}, an interned {@link String} or {@code null} — so the enum is immutable in
     * fact. The declaration is typed {@code Object} because the classification spans four codomains
     * and its consumer, {@link net.cumba.corej.core.exec.GroupedResult#missingKeyDefault()}, is
     * likewise {@code Object}-typed.
     */
    @SuppressWarnings("ImmutableEnumChecker")
    private final @Nullable Object value;

    EmptyResult(@Nullable Object aValue)
    {
        this.value = aValue;
    }


    /**
     * The declared value itself — {@code 0L}, {@code List.of()}, {@code false}, {@code ""} or
     * {@code null}. Passed straight to
     * {@link net.cumba.corej.core.exec.GroupedResult#GroupedResult(List, java.util.Map, Object)} as
     * the absent-key default.
     */
    public @Nullable Object value()
    {
        return value;
    }

}
