package net.cumba.cdisc.define.conformance.report;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One emitted Define-XML conformance finding (plan §3.4). Not a reuse of the CORE engine's
 * {@code Violation} — that model is SDTM-row-shaped (row/USUBJID/SEQ), none of which apply to an
 * XML-tree location.
 */
@Value
@Builder
public class ConformanceFinding
{

    /**
     * {@code "DEFINE-XML-0065"} / {@code "PMDA-DD0024"}; {@code "DEFINE-XML-XSD"} for pre-pass
     * findings not attributable to a sheet rule.
     */
    String ruleId;

    /**
     * Target element bare local name; {@code null} for XSD-pass findings (they carry line/column
     * instead).
     */
    @Nullable
    String element;

    /** Target attribute local name, when the rule checks one. */
    @Nullable
    String attribute;

    /**
     * Resolved tree location, e.g.
     * {@code /ODM/Study/MetaDataVersion/ItemGroupDef[@OID='IG.AE']/ItemRef[2]}; {@code null} for
     * XSD-pass findings.
     */
    @Nullable
    String xpath;

    /** Source line (XSD pre-pass findings only). */
    @Nullable
    Integer line;

    /** Source column (XSD pre-pass findings only). */
    @Nullable
    Integer column;

    /** Rendered from the rule's Message template (or the SAX message for XSD findings). */
    String message;

    Category category;

    Severity severity;

}
