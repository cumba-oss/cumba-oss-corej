package net.cumba.corej.core.expr.eval;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The <b>provider capability</b> of a registry function ({@code PLAN-binding-expressions} §5.2,
 * pre-go review M2): which run-level provider the function needs before it can answer.
 *
 * <p>
 * Before wave 0 every provider gate keyed on the operation type — the no-provider SKIP, the eager
 * "answered but unusable" SKIP, the run forecast ({@code ProviderRequirements}) and the injected
 * inline gate — so a <em>registry function</em> could not be library-dependent at all: a ported
 * {@code get_codelist_attributes} with no library would have answered {@code []} and the rule would
 * have PASSED instead of SKIPPING. The capability is declared on the descriptor
 * ({@link FunctionDescriptor#provider()}) and read by exactly one helper,
 * {@code net.cumba.corej.core.exec.ProviderNeeds}, which (until W8) also read the operation type
 * predicates — so the gates see both keys and a call in a binding and the same call inline in the
 * Check are gated alike.
 * </p>
 *
 * <p>
 * ⭐ <b>One mechanism for every wave.</b> Wave 0 declares {@link Kind#LIBRARY} (on
 * {@code get_codelist_attributes}); wave 1 adds {@link Kind#DICTIONARY} with its
 * {@link #typeParameter} — the name of the parameter whose static string literal names the
 * dictionary type ({@code external_dictionary_type}) — without a second mechanism, and a later wave
 * {@link Kind#DEFINE}.
 * </p>
 *
 * @param kind
 *            the provider the function needs
 * @param typeParameter
 *            for {@link Kind#DICTIONARY}: the parameter whose static string literal names the
 *            dictionary type; {@code null} otherwise
 */
public record ProviderNeed(Kind kind, @Nullable String typeParameter)
{

    /** The run-level providers a function can depend on. */
    public enum Kind
    {
        /** The CDISC Library metadata provider. */
        LIBRARY,

        /** The sponsor Define-XML overlay. */
        DEFINE,

        /** An external dictionary, of the type named by {@link ProviderNeed#typeParameter()}. */
        DICTIONARY
    }

    /** A function that needs the CDISC Library. */
    public static final ProviderNeed LIBRARY = new ProviderNeed(Kind.LIBRARY, null);

    /** A function that needs the sponsor Define-XML. */
    public static final ProviderNeed DEFINE = new ProviderNeed(Kind.DEFINE, null);

    /**
     * Validates the pairing: exactly {@link Kind#DICTIONARY} names its type parameter.
     */
    public ProviderNeed
    {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.DICTIONARY) != (typeParameter != null))
        {
            throw new IllegalArgumentException(
                    "only a DICTIONARY need names a type parameter, and it must: " + kind + " / "
                            + typeParameter);
        }
    }


    /**
     * A function that needs an external dictionary whose type is the static string literal bound to
     * {@code aTypeParameter} (wave 1's {@code external_dictionary_type}).
     *
     * @param aTypeParameter
     *            the parameter naming the dictionary type
     * @return the need
     */
    public static ProviderNeed dictionary(String aTypeParameter)
    {
        return new ProviderNeed(Kind.DICTIONARY, Objects.requireNonNull(aTypeParameter));
    }

}
