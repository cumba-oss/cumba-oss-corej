package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Citation
{

    @JsonProperty("Cited_Guidance")
    private @Nullable String citedGuidance;

    @JsonProperty("Document")
    private @Nullable String document;

    @JsonProperty("Item")
    private @Nullable String item;

    @JsonProperty("Section")
    private @Nullable String section;

}
