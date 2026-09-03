package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class AuthorityStandard
{

    @JsonProperty("Name")
    private @Nullable String name;

    @JsonProperty("Version")
    private @Nullable String version;

    @JsonProperty("Substandard")
    private @Nullable String substandard;

    @JsonProperty("References")
    private @Nullable List<Reference> references;

}
