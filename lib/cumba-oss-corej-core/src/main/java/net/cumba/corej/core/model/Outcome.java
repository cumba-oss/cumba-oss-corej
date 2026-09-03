package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Outcome
{

    @JsonProperty("Message")
    private @Nullable String message;

    @JsonProperty("Output_Variables")
    private @Nullable List<String> outputVariables;

}
