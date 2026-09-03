package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One regulatory authority's view of a rule: who publishes it, and under which id(s).
 *
 * <h2>⚠ TWO SHAPES, BOTH LIVE</h2>
 *
 * <p>
 * {@link #standards} is the full nested tree — {@code Standards[] → References[] →
 * Rule_Identifier.Id}, with the citation text and per-reference versions beside it. It is what
 * {@code rules-src/} authors, what {@code LibraryRuleMapper} writes on the CDISC-Library ingestion
 * path, and what the rule editor round-trips.
 * </p>
 *
 * <p>
 * {@link #ruleIds} is the flat released form. The generator collapses the tree to
 * {@code {Organization, Rule_Ids}} when it writes a shipped package ({@code ReleaseShapeTrimmer},
 * {@code plans/PLAN-rules-corpus-build-integration.md} §10): it keeps exactly what the report reads
 * and drops 60.8 MiB of citation text that no {@code src/main} consumer touches. <b>Nothing is lost
 * from the authored source</b> — the tree stays in {@code rules-src/}, in git, and in the authoring
 * tools.
 * </p>
 *
 * <p>
 * ⛔ A reader of this class must therefore handle <b>both</b>, and must not assume that a null
 * {@link #standards} means "no ids". That assumption is exactly what
 * {@code ReportAssembler.collectRuleIdsFromAuthority} used to make, and it would have blanked the
 * {@code cdisc_rule_id} and {@code fda_rule_id} report columns for every released rule with no
 * error at all: unknown keys are tolerated, so an unread {@code Rule_Ids} is silent.
 * {@code ReleasedRuleIdColumnsTest} is the guard.
 * </p>
 */
@Data
@NoArgsConstructor
public class Authority
{

    @JsonProperty("Organization")
    private @Nullable String organization;

    /** The full nested tree; absent in a released package. See the class javadoc. */
    @JsonProperty("Standards")
    private @Nullable List<AuthorityStandard> standards;

    /**
     * The published rule ids, flat and de-duplicated; absent in the authored source. See the class
     * javadoc.
     */
    @JsonProperty("Rule_Ids")
    private @Nullable List<String> ruleIds;

}
