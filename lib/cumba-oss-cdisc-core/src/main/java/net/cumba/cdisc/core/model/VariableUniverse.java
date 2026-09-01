package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The rule-level {@code Variable_Universe} field ({@code PLAN-leaf-scope-domain-inference.md} §3.7,
 * owner ruling 4 of 2026-08-13): <b>which set of variables the VAR cursor iterates</b> for a rule
 * whose evaluation domain carries that cursor. One VAR cursor exists per rule, so rule level is the
 * honest granularity — a sibling of {@code Sensitivity} / {@code Grouping}, which configure the
 * other cursor dimensions the same way — and deliberately <em>not</em> inside {@code Scope}, whose
 * {@code Variables} block selects <em>within</em> a universe.
 *
 * <p>
 * The universe decides <b>iteration only</b>: {@code Scope.Variables} still filters within it,
 * {@code varname()} binds the cursor name, and reads on either side degrade to the standard absent
 * semantics — {@code var_exists(varname())} is the discriminator (under {@link #DEFINE} a cursor
 * variable may be absent from the data; under {@link #DATA} a {@code define_variable_*} read may
 * have no ItemDef). It is <b>never derived</b> from operand classes: absent means {@link #DATA},
 * full stop. The single piece of genuinely non-derivable information the deleted {@code Rule_Type}
 * taxonomy carried ({@code Define Item Metadata Check against Library
 * Metadata} = "iterate the Define-XML ItemDefs") lives here and nowhere else.
 * </p>
 *
 * <p>
 * <b>Considered and deferred:</b> a {@code Union} value (data columns ∪ ItemDefs with existence
 * guards, for both-direction "declared-but-missing / present-but-undeclared" checks in one rule).
 * No shipped rule needs it and it is the only variant that changes finding sets by construction; it
 * is recorded here rather than reserved.
 * </p>
 */
@RequiredArgsConstructor
@Getter
public enum VariableUniverse
{

    /** The dataset's columns, in column order — the default, and every non-Define shape. */
    DATA("Data"),

    /**
     * The Define-XML ItemDefs of the domain, in ItemDef order: a define-declared variable absent
     * from the data is still checked, a data column absent from the define is not. No Define-XML
     * provider ⇒ the rule is SKIPPED (the existing {@code MetadataLevel.DEFINE} gate).
     */
    DEFINE("Define");

    @JsonValue
    private final String jsonValue;

    private static final Map<String, VariableUniverse> LOOKUP = Stream.of(values())
            .collect(Collectors.toMap(VariableUniverse::getJsonValue, Function.identity()));

    @JsonCreator
    public static @Nullable VariableUniverse fromJson(@Nullable String value)
    {
        return LOOKUP.get(value);
    }
}
