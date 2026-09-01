package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class RuleIdentifier
{

    @JsonProperty("Id")
    private @Nullable String id;

    @JsonProperty("Version")
    private @Nullable String version;

}
