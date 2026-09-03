package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One CDISC Library standard a rule package declares it runs against (R6): the library product
 * {@code id} plus the {@link Role} it plays for that package.
 *
 * <p>
 * <b>The {@code id} is a library product token, NOT a package short name.</b> The two are different
 * vocabularies and neither is derivable from the other — measured 2026-08-28 against
 * {@code ProductKeyResolver}: the package {@code cdisc-sendig-dart-1-1} declares the product
 * {@code sendig/dart-1-1}, and the flat filename-shaped spelling {@code sendig-dart-1-1} resolves
 * to <em>nothing</em>. Declare the slash form (or any unique suffix of a real cache key, e.g.
 * {@code dart-1-1}); it is resolved by {@code ProductKeyResolver} against the configured
 * {@code MetadataProductCatalogue}, never by parsing.
 * </p>
 *
 * <p>
 * ⚑ <b>A role carries no capability list, deliberately.</b> The data declares WHICH products; the
 * engine decides WHAT IT DOES with them. A {@code provides} list would let a package edit silently
 * disable a conformance check. It stays forward-compatible: {@code provides} can be added later,
 * with "absent" meaning "all uses".
 * </p>
 *
 * @param id
 *            the library product token, e.g. {@code adam/adamig-1-3} or {@code sdtmig/3-4}
 * @param role
 *            what the product is to this package
 */
public record StandardRef(@JsonProperty("id") String id, @JsonProperty("role") Role role)
{

    /** Null-safe canonicalisation: a blank id stays blank, an absent role defaults to primary. */
    public StandardRef
    {
        id = id == null ? "" : id.trim();
        role = role == null ? Role.PRIMARY : role;
    }

    /** The part a declared standard plays for its package. */
    public enum Role
    {

        /**
         * The standard the package's rules are written against. Auto-added to the effective
         * {@code --metadata-products} list, last (R7).
         */
        PRIMARY,

        /**
         * A supporting standard the package's rules may consult but are not written against — e.g.
         * the SDTMIG an ADaM package compares carried-over variables to.
         */
        COMPANION;

        /** Serialises as the lower-case wire form ({@code primary} / {@code companion}). */
        @JsonValue
        public String wireName()
        {
            return name().toLowerCase(Locale.ROOT);
        }


        /**
         * Parses the wire form, case-insensitively.
         *
         * @param aValue
         *            the JSON value
         * @return the matching role
         * @throws IllegalArgumentException
         *             when the value names no role — an unknown role is a package-authoring error
         *             and must not be silently dropped
         */
        @JsonCreator
        public static Role fromWire(@Nullable String aValue)
        {
            String v = aValue == null ? "" : aValue.trim().toLowerCase(Locale.ROOT);
            for (Role r : values())
            {
                if (r.wireName().equals(v))
                {
                    return r;
                }
            }
            throw new IllegalArgumentException(
                    "unknown standard role '" + aValue + "'; expected primary or companion");
        }
    }
}
