package net.cumba.corej.define.conformance.rule;

/** Which source sheet a rule mirrors (plan §1/§2/§2.5). */
public enum RuleSet
{

    /** CDISC Define-XML v2.1 Conformance Rules (225 rules, ids {@code DEFINE-XML-####}). */
    CDISC,

    /**
     * PMDA Validation Rules v6.0, sheet "Define-XML Rules" (161 rules, ids
     * {@code PMDA-DD####}/{@code PMDA-OD####}).
     */
    PMDA;

}
