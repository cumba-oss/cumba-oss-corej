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

@RequiredArgsConstructor
@Getter
public enum Executability
{

    FULLY_EXECUTABLE("Fully Executable", "fully executable"),
    PARTIALLY_EXECUTABLE("Partially Executable", "partially executable"),
    PARTIALLY_EXECUTABLE_POSSIBLE_OVERREPORTING("Partially Executable - Possible Overreporting", "partially executable - possible overreporting"),
    PARTIALLY_EXECUTABLE_POSSIBLE_UNDERREPORTING("Partially Executable - Possible Underreporting", "partially executable - possible underreporting"),
    NOT_EXECUTABLE("Not Executable", "not executable");

    @JsonValue
    private final String jsonValue;

    /**
     * Lower-cased form emitted by the Python CORE engine's JSON report
     * ({@code Issue_Details[].executability}). Kept distinct from {@link #jsonValue} so the
     * rule-package parser keeps round-tripping the title-case form in the upstream rule JSON
     * without coupling it to the report-emission casing.
     */
    private final String pythonValue;

    private static final Map<String, Executability> LOOKUP = Stream.of(values())
            .collect(Collectors.toMap(Executability::getJsonValue, Function.identity()));

    @JsonCreator
    public static @Nullable Executability fromJson(@Nullable String value)
    {
        return LOOKUP.get(value);
    }

}
