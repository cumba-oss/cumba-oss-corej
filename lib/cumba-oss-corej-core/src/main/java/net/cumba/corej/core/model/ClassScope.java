package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class ClassScope
{

    @JsonProperty("Include")
    private @Nullable List<String> include;

    @JsonProperty("Exclude")
    private @Nullable List<String> exclude;

}
