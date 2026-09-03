package net.cumba.corej.core.model;

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
public enum Sensitivity
{

    RECORD("Record"), DATASET("Dataset"), GROUP("Group"), STUDY("Study");

    @JsonValue
    private final String jsonValue;

    private static final Map<String, Sensitivity> LOOKUP = Stream.of(values())
            .collect(Collectors.toMap(Sensitivity::getJsonValue, Function.identity()));

    @JsonCreator
    public static @Nullable Sensitivity fromJson(@Nullable String value)
    {
        return LOOKUP.get(value);
    }

}
