package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Reference
{

    @JsonProperty("Origin")
    private @Nullable String origin;

    @JsonProperty("Version")
    private @Nullable String version;

    @JsonProperty("Rule_Identifier")
    private @Nullable RuleIdentifier ruleIdentifier;

    @JsonProperty("Citations")
    private @Nullable List<Citation> citations;

}
