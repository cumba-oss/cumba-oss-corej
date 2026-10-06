package net.cumba.corej.core.model;

import java.util.List;

/**
 * The three lists a surface that shows a rule's {@code Output_Variables} needs, in one place
 * ({@code PLAN-rule-catalogue-effective-output-variables} P1; owner rulings D1 (b) / D3,
 * 2026-10-05): the <b>effective</b> report columns, the subset the author <b>wrote</b>, and the
 * names the author <b>excluded</b> with {@code !X}.
 *
 * <p>
 * ⛔ The engine owns {@code includes} / {@code exclusions} ({@link OutputVariableToken}) and the
 * derivation ({@code OutputVariableDeriver}); a consumer — the REST overlay, the authoring API, the
 * editor — reads this record and never re-derives "authored subset" itself.
 * </p>
 *
 * @param effective
 *            the report columns in order: {@link Rule#effectiveOutputVariablesOrAuthored()}
 * @param authored
 *            the include entries the author wrote (exclusion tokens removed), in authored order — ⚠
 *            not necessarily a subset of {@code effective}: a rule that writes {@code X} and
 *            {@code !X} fails to load, and its {@code X} stays here while the effective list
 *            applies the exclusion (pack-2 review 15b L1)
 * @param excluded
 *            the names the author excluded with {@code !X}, in authored order
 * @param derived
 *            whether a derivation ran: {@code false} for a rule that failed to load, whose
 *            effective list is then the authored one with {@code !X} applied
 */
public record OutputVariableListing(List<String> effective, List<String> authored,
        List<String> excluded, boolean derived)
{

    public OutputVariableListing
    {
        effective = List.copyOf(effective);
        authored = List.copyOf(authored);
        excluded = List.copyOf(excluded);
    }


    /**
     * The listing of a rule.
     *
     * @param aRule
     *            a loaded rule (a hand-built rule derives on first read)
     * @return the listing
     */
    public static OutputVariableListing of(Rule aRule)
    {
        List<String> entries = aRule.getOutcome() == null ? null
                : aRule.getOutcome().getOutputVariables();
        // exclusions(): every !X name, deduplicated, in first-appearance order, a bare "!"
        // contributing nothing — exactly the list this method used to rebuild entry by entry
        // (pack-2 review 15b: the rebuild's contains() test was the same set again)
        return new OutputVariableListing(aRule.effectiveOutputVariablesOrAuthored(),
                OutputVariableToken.includes(entries),
                List.copyOf(OutputVariableToken.exclusions(entries)), aRule.getLoadError() == null);
    }


    /**
     * Whether the author wrote this exact name.
     *
     * @param aName
     *            a variable name, compared exactly (P3)
     * @return true when it is in {@link #authored()}
     */
    public boolean isAuthored(String aName)
    {
        return authored.contains(aName);
    }
}
