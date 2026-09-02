package net.cumba.cdisc.define.conformance.ct;

import java.util.Map;

/**
 * One CDISC Controlled Terminology codelist as the CT-backed checks need it (plan §3.6).
 *
 * @param cCode
 *            the codelist's NCI c-code (e.g. {@code C66731})
 * @param extensible
 *            whether the codelist is extensible
 * @param termsBySubmissionValue
 *            submission value → the term's NCI c-code
 */
public record CtCodelist(String cCode, boolean extensible,
        Map<String, String> termsBySubmissionValue)
{

    public CtCodelist
    {
        termsBySubmissionValue = Map.copyOf(termsBySubmissionValue);
    }

}
