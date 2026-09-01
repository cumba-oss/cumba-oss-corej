package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Authority
{

    @JsonProperty("Organization")
    private @Nullable String organization;

    @JsonProperty("Standards")
    private @Nullable List<AuthorityStandard> standards;

}
