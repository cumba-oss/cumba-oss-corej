package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RuleGenerator.applyTemplatePostFilters} — the gate that decides <b>which</b> wildcard
 * expansions survive to be executed, and the only place a wildcard expansion is announced to the
 * {@link RuleGenerationReport}.
 *
 * <p>
 * The generator is built with {@link RuleCategory#corpusDeliveryOnly()}, i.e. the exact category
 * set the single production construction site passes, so nothing here depends on a generator that
 * is dead in production.
 * </p>
 *
 * <p>
 * Both filters fail silently in opposite directions. Too permissive and the run executes a
 * duplicate of a rule it already runs (the SDTM {@code --} prefix half) or one the Library already
 * covers; too aggressive and an authored rule quietly stops being checked on the very columns it
 * names. The assertions are therefore always the exact surviving id list, never a count.
 * </p>
 */
class WildcardExpansionPostFilterTest
{

    private static CheckConditionLeaf leaf(String name)
    {
        return CheckConditionLeaf.builder().name(name).operator("non_empty").build();
    }


    private static Rule template(String coreId, CheckCondition check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setDescription("post-filter fixture");
        return rule;
    }


    private static StubMetadataProvider library(String domain, String... variableNames)
    {
        StubMetadataProvider provider = new StubMetadataProvider();
        for (String name : variableNames)
        {
            provider.variable(domain, Map.of("name", name));
        }
        return provider;
    }


    private static GeneratedRulePackage generate(StubMetadataProvider provider, String domain,
            Rule template, IDataTable table)
    {
        RuleGenerator generator = new RuleGenerator(provider, null, null, null,
                RuleCategory.corpusDeliveryOnly());
        generator.setDomainName(domain);
        generator.setStaticRules(List.of(template));
        return generator.generate(table);
    }


    /** The expansion ids belonging to {@code templateId}, in generation order. */
    private static List<String> expansionIds(GeneratedRulePackage pkg, String templateId)
    {
        return pkg.getRules().stream().map(Rule::effectiveId)
                .filter(id -> id != null && id.startsWith(templateId + "-")).toList();
    }


    /** What the report says was generated as a wildcard expansion: (id, variable) pairs. */
    private static List<String> reportedExpansions(GeneratedRulePackage pkg)
    {
        return pkg.getReport().getGeneratedRules().stream()
                .filter(info -> info.category() == RuleCategory.WILDCARD_EXPANSION)
                .map(info -> info.ruleId() + "/" + info.variable()).toList();
    }


    /**
     * {@code skipIfLibraryDefined} drops exactly the expansions whose target column the Library
     * already defines — the rule is redundant there, because a Library-defined variable is already
     * covered by the metadata-driven checks.
     */
    @Test
    @DisplayName("skipIfLibraryDefined drops only the Library-defined columns")
    void skipIfLibraryDefinedDropsOnlyTheLibraryDefinedColumns()
    {
        IDataTable table = MockTable.of().name("ADAE").col("TRT01P", "A").col("TRT02P", "B")
                .build();
        Rule tpl = template("WCP-1", new CheckConditionAll(List.of(leaf("TRTxxP"))));
        tpl.setSkipIfLibraryDefined(Boolean.TRUE);

        GeneratedRulePackage pkg = generate(library("ADAE", "TRT01P"), "ADAE", tpl, table);

        assertEquals(List.of("WCP-1-TRT02P"), expansionIds(pkg, "WCP-1"),
                "TRT01P is Library-defined and must drop; TRT02P is not and must survive");
        assertEquals(List.of("WCP-1-TRT02P/TRT02P"), reportedExpansions(pkg),
                "the report must announce exactly the surviving expansion — a run whose report "
                        + "omits a rule it executed cannot be audited");
    }


    /**
     * The negative control for the same branch: with the flag absent the Library's own definition
     * of {@code TRT01P} is irrelevant and BOTH expansions run. Without this case a filter that
     * fires unconditionally would look correct.
     */
    @Test
    @DisplayName("without the flag, a Library-defined column is NOT dropped")
    void withoutTheFlagLibraryDefinedColumnsSurvive()
    {
        IDataTable table = MockTable.of().name("ADAE").col("TRT01P", "A").col("TRT02P", "B")
                .build();
        Rule tpl = template("WCP-2", new CheckConditionAll(List.of(leaf("TRTxxP"))));

        GeneratedRulePackage pkg = generate(library("ADAE", "TRT01P"), "ADAE", tpl, table);

        assertEquals(List.of("WCP-2-TRT01P", "WCP-2-TRT02P"), expansionIds(pkg, "WCP-2"),
                "the filter is opt-in; firing it unasked silently stops checking TRT01P");
        assertEquals(List.of("WCP-2-TRT01P/TRT01P", "WCP-2-TRT02P/TRT02P"),
                reportedExpansions(pkg));
    }


    /**
     * On a two-letter SDTM domain, an expansion whose target column is the domain's own
     * {@code <DOMAIN><suffix>} column duplicates what the {@code --}-prefix expansion of the same
     * suffix already produces, so it is dropped. Every other column keeps its expansion — the
     * filter is about the domain's own prefix, not about the suffix in general.
     */
    @Test
    @DisplayName("on an SDTM domain the domain's own <DOMAIN><suffix> column is not re-expanded")
    void sdtmDomainOwnPrefixedColumnIsDropped()
    {
        IDataTable table = MockTable.of().name("AE").col("AEFL", "Y").col("SUBJFL", "N").build();
        Rule tpl = template("WCP-3", new CheckConditionAll(List.of(leaf("*FL"))));

        GeneratedRulePackage pkg = generate(library("AE"), "AE", tpl, table);

        assertEquals(List.of("WCP-3-SUBJFL"), expansionIds(pkg, "WCP-3"),
                "AEFL is 'AE' + the template's own suffix 'FL' — the --FL rule already covers it; "
                        + "SUBJFL is not and must survive");
        assertEquals(List.of("WCP-3-SUBJFL/SUBJFL"), reportedExpansions(pkg));
    }


    /**
     * The boundary of that same filter. A concrete column equal to the domain itself has an
     * <em>empty</em> effective suffix, and the bare-{@code *} template's suffix is empty too — so a
     * filter written with {@code >=} instead of {@code >} would find them "equal" and drop the only
     * expansion the template produces. The rule must survive: {@code AE} is a real column and the
     * template names it.
     */
    @Test
    @DisplayName("a column equal to the domain itself is NOT swallowed by the prefix filter")
    void aColumnEqualToTheDomainSurvivesThePrefixFilter()
    {
        IDataTable table = MockTable.of().name("AE").col("AE", "x").col("AEN", "1").build();
        // A bare '*' anchored by a sibling '*N': the primary resolves to AE, whose effective
        // suffix after stripping the domain is "" — exactly the template's own empty suffix.
        Rule tpl = template("WCP-4", new CheckConditionAll(List.of(leaf("*"), leaf("*N"))));

        GeneratedRulePackage pkg = generate(library("AE"), "AE", tpl, table);

        assertEquals(List.of("WCP-4-AE"), expansionIds(pkg, "WCP-4"),
                "the column IS the domain, so nothing is left after the prefix — an off-by-one "
                        + "boundary here silently deletes the rule's only expansion");
        assertEquals(List.of("WCP-4-AE/AE"), reportedExpansions(pkg));
    }

}
