package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * ADaM subclass scope filter for rules ({@code Scope.Subclasses}). When present, restricts the rule
 * by the dataset's detected data-structure subclass (see
 * {@link net.cumba.cdisc.core.metadata.AdamSubclassDetector}). The token vocabulary is the
 * Define-XML 2.1 {@code ItemGroupSubClass} enumeration — {@code ADVERSE EVENT},
 * {@code MEDICAL DEVICE TIME-TO-EVENT}, {@code NON-COMPARTMENTAL ANALYSIS},
 * {@code POPULATION PHARMACOKINETIC ANALYSIS}, {@code TIME-TO-EVENT} — plus the {@code ALL}
 * sentinel in {@code Include}.
 *
 * <h2>Null-detection semantics (decided 2026-07-26)</h2>
 * <ul>
 * <li>{@code Include} requires a positively detected subclass that is in the list — a dataset with
 * no detectable subclass (the normal case for a plain BDS/OCCDS/ADSL dataset) is skipped;</li>
 * <li>{@code Exclude} rejects only on a positive match — a dataset with no detectable subclass
 * passes an Exclude-only scope.</li>
 * </ul>
 *
 * <p>
 * Unlike {@code Data_Structures} this field has no Python-engine runtime counterpart upstream (the
 * CORE rule schema defines it, nothing consumes it); the house parity fork carries a twin gate so
 * both lanes agree.
 * </p>
 */
@Data
@NoArgsConstructor
public class SubclassScope
{

    @JsonProperty("Include")
    private @Nullable List<String> include;

    @JsonProperty("Exclude")
    private @Nullable List<String> exclude;

}
