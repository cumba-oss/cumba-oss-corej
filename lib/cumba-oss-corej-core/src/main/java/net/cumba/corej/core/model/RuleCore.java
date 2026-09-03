package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class RuleCore
{

    @JsonProperty("Id")
    private @Nullable String id;

    @JsonProperty("Status")
    private @Nullable String status;

    @JsonProperty("Version")
    private @Nullable String version;

}
