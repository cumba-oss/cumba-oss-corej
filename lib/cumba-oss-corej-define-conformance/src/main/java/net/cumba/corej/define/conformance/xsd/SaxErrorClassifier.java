package net.cumba.corej.define.conformance.xsd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * Maps XSD pre-pass problems onto PMDA Define-XML rule ids (plan §3.5 step 3, PMDA §2.5 cat. 1).
 * The JDK validator's {@code SAXParseException} message starts with the stable Xerces error key
 * (empirically verified, e.g. {@code "cvc-complex-type.4: Attribute 'Mandatory' must appear on
 * element 'ItemRef'."}), so classification pattern-matches on those key prefixes:
 *
 * <ul>
 * <li>fatal (well-formedness, no cvc key) → {@code PMDA-OD0001} (Reject)</li>
 * <li>{@code cvc-elt.1*} (undeclared root element) → {@code PMDA-OD0012} (Reject)</li>
 * <li>{@code cvc-complex-type.3.2*} (attribute not allowed) → {@code PMDA-DD0004} (Warning)</li>
 * <li>{@code cvc-complex-type.4*} (missing required attribute) → {@code PMDA-DD0003} (Reject)</li>
 * <li>{@code cvc-complex-type.2.4.b} (incomplete content) → {@code PMDA-DD0006} (Reject)</li>
 * <li>other {@code cvc-complex-type.2.4*} (invalid/mis-ordered content) → {@code PMDA-DD0007}
 * (Warning). {@code PMDA-DD0008} ("element in wrong position") is <b>not</b> emitted here: Xerces
 * reports unknown elements and mis-ordered elements through the identical
 * {@code cvc-complex-type.2.4.a} message — the sheet's DD0007 message is that Xerces text verbatim,
 * so DD0007 is the source-true id for both. (DD0008's ordering semantics are covered by the
 * CDISC-sheet global ordering rule, its sheet twin.)</li>
 * <li>{@code cvc-attribute.3}/{@code cvc-datatype-valid*} whose reported simple type contains
 * {@code integer} → {@code PMDA-OD0013}, contains {@code datetime} → {@code PMDA-OD0017} (both
 * Warning). Xerces reports one bad value twice (datatype + attribute view) at the same location;
 * the classifier deduplicates on (rule, line, column), preferring the {@code cvc-attribute.3}
 * message because it names the attribute and element.</li>
 * <li>any other <b>error</b> → {@code PMDA-DD0001}, the sheet's own "schema validation issue not
 * covered by one of the other rules" catch-all (Reject).</li>
 * <li>any unmatched parser <b>warning</b> → {@link #GENERIC_RULE_ID} with severity Warning: parser
 * warnings are not schema violations, so pinning the Reject-severity DD0001 on them would overstate
 * them. (Phase 7 may also remap the catch-all to {@link #GENERIC_RULE_ID} when running in
 * CDISC-only scope, per plan §3.5.)</li>
 * </ul>
 *
 * All findings carry {@code category = XSD} (plan §3.5 step 3), the sheet's severity, a message of
 * the form {@code "<sheet MESSAGE>: <SAX detail>"}, the SAX line/column, and no element/xpath.
 */
public final class SaxErrorClassifier
{

    /** Generic rule id for findings not attributable to a PMDA sheet row. */
    public static final String GENERIC_RULE_ID = "DEFINE-XML-XSD";

    /** Xerces quotes the offending simple type: {@code "… its type, 'integer'."}. */
    private static final Pattern ATTRIBUTE_TYPE = Pattern
            .compile("not valid with respect to its type, '([^']+)'");

    /**
     * Xerces quotes the offending simple type: {@code "… is not a valid value for 'dateTime'."}.
     */
    private static final Pattern DATATYPE_TYPE = Pattern
            .compile("is not a valid value for '([^']+)'");

    private SaxErrorClassifier()
    {
    }


    /**
     * Classifies every collected problem into one {@code ConformanceFinding}, deduplicating the
     * double-reported datatype violations on (rule id, line, column).
     */
    public static List<ConformanceFinding> toFindings(List<SaxProblem> aProblems)
    {
        Map<String, ConformanceFinding> byLocation = new LinkedHashMap<>();
        for (SaxProblem problem : aProblems)
        {
            ConformanceFinding finding = classify(problem);
            String key = finding.getRuleId() + "@" + problem.line() + ":" + problem.column();
            ConformanceFinding previous = byLocation.get(key);
            if (previous == null || problem.message().startsWith("cvc-attribute.3"))
            {
                byLocation.put(key, finding);
            }
        }
        return List.copyOf(byLocation.values());
    }


    /** Classifies a single problem (no deduplication). */
    static ConformanceFinding classify(SaxProblem aProblem)
    {
        String message = aProblem.message();
        if (aProblem.kind() == SaxProblem.Kind.FATAL)
        {
            return finding(aProblem, "PMDA-OD0001", Severity.REJECT, "XML is not well-formed");
        }
        if (message.startsWith("cvc-elt.1"))
        {
            return finding(aProblem, "PMDA-OD0012", Severity.REJECT, "Invalid root element");
        }
        if (message.startsWith("cvc-complex-type.3.2"))
        {
            return finding(aProblem, "PMDA-DD0004", Severity.WARNING,
                    "Attribute <attribute> is not allowed to appear in element <element>");
        }
        if (message.startsWith("cvc-complex-type.4"))
        {
            return finding(aProblem, "PMDA-DD0003", Severity.REJECT,
                    "Missing required <attribute> value for <object>");
        }
        if (message.startsWith("cvc-complex-type.2.4.b"))
        {
            return finding(aProblem, "PMDA-DD0006", Severity.REJECT,
                    "Missing required <element> value");
        }
        if (message.startsWith("cvc-complex-type.2.4"))
        {
            return finding(aProblem, "PMDA-DD0007", Severity.WARNING,
                    "Invalid content was found starting with element <element>");
        }
        String simpleType = reportedSimpleType(message);
        if (simpleType != null)
        {
            String lower = simpleType.toLowerCase(Locale.ROOT);
            if (lower.contains("integer"))
            {
                return finding(aProblem, "PMDA-OD0013", Severity.WARNING,
                        "Invalid integer value for <attribute>");
            }
            if (lower.contains("datetime"))
            {
                return finding(aProblem, "PMDA-OD0017", Severity.WARNING,
                        "Invalid datetime value for <attribute>");
            }
        }
        if (aProblem.kind() == SaxProblem.Kind.WARNING)
        {
            return finding(aProblem, GENERIC_RULE_ID, Severity.WARNING,
                    "XML schema validation warning");
        }
        return finding(aProblem, "PMDA-DD0001", Severity.REJECT,
                "XML schema validation issue within Define.xml");
    }


    /** The simple type Xerces quoted in a datatype-violation message, or {@code null}. */
    private static @Nullable String reportedSimpleType(String aMessage)
    {
        if (aMessage.startsWith("cvc-attribute.3"))
        {
            Matcher matcher = ATTRIBUTE_TYPE.matcher(aMessage);
            return matcher.find() ? matcher.group(1) : null;
        }
        if (aMessage.startsWith("cvc-datatype-valid"))
        {
            Matcher matcher = DATATYPE_TYPE.matcher(aMessage);
            return matcher.find() ? matcher.group(1) : null;
        }
        return null;
    }


    private static ConformanceFinding finding(SaxProblem aProblem, String aRuleId,
            Severity aSeverity, String aSheetMessage)
    {
        return ConformanceFinding.builder() //
                .ruleId(aRuleId) //
                .category(Category.XSD) //
                .severity(aSeverity) //
                .message(aSheetMessage + ": " + aProblem.message()) //
                .line(aProblem.line()) //
                .column(aProblem.column()) //
                .build();
    }

}
