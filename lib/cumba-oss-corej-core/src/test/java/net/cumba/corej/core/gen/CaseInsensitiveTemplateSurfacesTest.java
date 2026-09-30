package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.DatasetRuleResolver;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.exec.StubMetadataProvider;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The template-expansion surfaces that still compared a column name case-SENSITIVELY after the
 * 2026-09-28 ruling (owner: <i>"Case-insensitive everywhere"</i>,
 * {@code PLAN-case-insensitive-templates}, register {@code CIT §1}), found by review round 1: the
 * {@code wildcardPairCatalogue} whitelist ({@code PMDA-AD1012A}), the post-expansion filters of
 * {@code DatasetRuleResolver}, and the classification of a bound name that is a dotted reference
 * ({@code TokenExpander}'s {@code ADSL.&VAR&}, {@code CDISC-AD0591}). Every test runs on
 * <b>lowercase-named</b> columns and fails on the comparison it guards.
 *
 * <p>
 * Mockito-free: {@link SyntheticDataTable} and {@link StubMetadataProvider} are real objects.
 * </p>
 */
class CaseInsensitiveTemplateSurfacesTest
{

    private static IDataTable table(String name, String... columns)
    {
        return new SyntheticDataTable(name, List.of(columns), new String[]
        {
                "x"
        }, 1);
    }


    private static CheckConditionExpression expr(String source)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(source), source);
    }


    private static Rule rule(String coreId, CheckCondition check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setDescription("case-insensitive surface fixture");
        return rule;
    }


    private static List<String> ids(List<Rule> rules)
    {
        return rules.stream().map(Rule::effectiveId).toList();
    }

    // -- wildcardPairCatalogue (PMDA-AD1012A)
    // ------------------------------------------------------


    /** The {@code PMDA-AD1012A} shape: bare {@code *} anchored by a catalogued {@code *N}. */
    private static Rule pairCatalogueTemplate()
    {
        Rule template = rule("PMDA-AD1012A", new CheckConditionAny(List.of(new CheckConditionAll(
                List.of(expr("var_not_exists(\"*\")"), expr("var_exists(\"*N\")"))))));
        template.setWildcardPairCatalogue(true);
        return template;
    }


    /**
     * A lowercase {@code trtpn} is the catalogued secondary {@code TRTPN}: alone it seeds the same
     * one expansion {@code TRTPN} does. Looked up case-sensitively it was not catalogued and the
     * rule expanded to nothing.
     */
    @Test
    void pairCatalogueAcceptsALowercaseSecondary()
    {
        DataTableMeta upper = table("ADAE", "TRTPN").getMetaData();
        DataTableMeta lower = table("ADAE", "trtpn").getMetaData();

        List<Rule> fromUpper = WildcardExpander.expand(pairCatalogueTemplate(), upper);
        List<Rule> fromLower = WildcardExpander.expand(pairCatalogueTemplate(), lower);

        assertEquals(List.of("PMDA-AD1012A-TRTP"), ids(fromUpper), "the uppercase baseline");
        assertEquals(1, fromLower.size(), "trtpn seeds exactly as TRTPN does: " + ids(fromLower));
        assertEquals("PMDA-AD1012A-TRTP", ids(fromLower).get(0).toUpperCase(java.util.Locale.ROOT));
    }


    /** Negative control: an uncatalogued lowercase secondary is still dropped. */
    @Test
    void pairCatalogueStillDropsAnUncataloguedLowercaseSecondary()
    {
        assertEquals(List.of(), WildcardExpander.expand(pairCatalogueTemplate(),
                table("ADAE", "foon").getMetaData()));
    }

    // -- DatasetRuleResolver.applyTemplatePostFilters --------------------------------------------


    private static List<String> survivingExpansions(StubMetadataProvider provider, String domain,
            Rule template, IDataTable table)
    {
        DatasetRuleResolver generator = new DatasetRuleResolver(provider);
        generator.setDomainName(domain);
        generator.setStaticRules(List.of(template));
        return generator.generate(table).getRules().stream().map(Rule::effectiveId)
                .filter(id -> id != null && id.startsWith(template.effectiveId() + "-")).toList();
    }


    /**
     * {@code skipIfLibraryDefined}: the Library's {@code TRT01P} is the dataset's {@code trt01p},
     * so that expansion drops and only {@code trt02p} survives. Compared case-sensitively both
     * survived.
     */
    @Test
    void skipIfLibraryDefinedDropsALowercaseLibraryDefinedColumn()
    {
        Rule template = rule("WCP-L1", new CheckConditionAll(List.of(expr("not empty(TRTxxP)"))));
        template.setSkipIfLibraryDefined(Boolean.TRUE);
        StubMetadataProvider library = new StubMetadataProvider().variable("ADAE",
                Map.of("name", "TRT01P"));

        assertEquals(List.of("WCP-L1-trt02p"),
                survivingExpansions(library, "ADAE", template, table("ADAE", "trt01p", "trt02p")));
    }


    /**
     * On an SDTM domain the domain's own {@code <DOMAIN><suffix>} column is not re-expanded: a
     * lowercase {@code aefl} is {@code AE} + {@code FL} as {@code AEFL} is, so only {@code subjfl}
     * survives. Compared case-sensitively {@code aefl} survived as a duplicate of the {@code --FL}
     * rule.
     */
    @Test
    void sdtmOwnPrefixFilterRecognisesALowercaseDomainColumn()
    {
        Rule template = rule("WCP-L2", new CheckConditionAll(List.of(expr("not empty(*FL)"))));

        assertEquals(List.of("WCP-L2-subjfl"), survivingExpansions(new StubMetadataProvider(), "AE",
                template, table("AE", "aefl", "subjfl")));
    }

    // -- classifyConcrete: a bound name that is a dotted reference -------------------------------


    private static void collectRefs(Expr e, List<Expr.Ref> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectRefs(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectRefs(p, out));
        case Expr.Not n -> collectRefs(n.inner(), out);
        case Expr.Binary b ->
        {
            collectRefs(b.left(), out);
            collectRefs(b.right(), out);
        }
        case Expr.Call c -> c.args().forEach(a -> collectRefs(a, out));
        case Expr.Ref r -> out.add(r);
        case Expr.Lit _ ->
        {
            // a literal is no reference
        }
        }
    }


    private static List<Expr.Ref> refsOf(Rule rule)
    {
        List<Expr.Ref> out = new ArrayList<>();
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, rule.getCheck());
        for (CheckCondition c : all.getConditions())
        {
            collectRefs(assertInstanceOf(CheckConditionExpression.class, c).expr(), out);
        }
        return out;
    }


    private static @Nullable OperandKind kindOf(List<Expr.Ref> refs, String name)
    {
        return refs.stream().filter(r -> r.name().equals(name)).map(Expr.Ref::kind).findFirst()
                .orElse(null);
    }


    /** The {@code CDISC-AD0591} template: every variable ADAE shares with ADSL. */
    private static Rule ad0591Template()
    {
        Rule template = rule("CDISC-AD0591", new CheckConditionAll(List.of(expr("not empty(&VAR&)"),
                expr("not empty(ADSL.&VAR&)"), expr("&VAR& != ADSL.&VAR&"))));
        MatchDataset adsl = new MatchDataset();
        adsl.setName("ADSL");
        adsl.setKeys(List.of("STUDYID", "USUBJID"));
        adsl.setJoinType("left");
        template.setMatchDatasets(List.of(adsl));
        ExpansionDirective shared = new ExpansionDirective();
        shared.setToken("&VAR&");
        shared.setOverJson(ExpansionSource.SHARED_VARIABLES.getJsonValue());
        shared.setWith("ADSL");
        template.setExpansion(List.of(shared));
        return template;
    }


    private static List<Rule> expandAd0591(IDataTable adae, IDataTable adsl)
    {
        DatasetResolver.WithInventory inventory = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return "ADSL".equals(name) ? adsl : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("ADSL");
            }
        };
        TokenExpander.Context context = new TokenExpander.Context(
                ScopeVariableSource.of(inventory, adae), new StubMetadataProvider(), "ADAE");
        return assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                TokenExpander.tryExpand(ad0591Template(), adae.getMetaData(), context)).rules();
    }


    /**
     * The declared-token reach of {@code classifyConcrete}: {@code ADSL.&VAR&} bound to the
     * lowercase {@code trt01p} is the dotted reference {@code ADSL.trt01p} — the kind the stage-A
     * dotted-reference checks and the absent-dataset skip key on — exactly as the uppercase binding
     * {@code ADSL.TRT01P} is. It used to fall through to {@code COLUMN}. The bare {@code &VAR&}
     * binding stays a {@code COLUMN} in either case.
     */
    @Test
    void aLowercaseDottedTokenBindingIsADottedReference()
    {
        List<Rule> lower = expandAd0591(table("ADAE", "STUDYID", "USUBJID", "trt01p"),
                table("ADSL", "STUDYID", "USUBJID", "TRT01P"));
        List<Rule> upper = expandAd0591(table("ADAE", "STUDYID", "USUBJID", "TRT01P"),
                table("ADSL", "STUDYID", "USUBJID", "TRT01P"));

        assertEquals(1, lower.size(), ids(lower).toString());
        List<Expr.Ref> lowerRefs = refsOf(lower.get(0));
        assertEquals(OperandKind.DOTTED_REF, kindOf(lowerRefs, "ADSL.trt01p"),
                lowerRefs.toString());
        assertEquals(OperandKind.COLUMN, kindOf(lowerRefs, "trt01p"), lowerRefs.toString());

        List<Expr.Ref> upperRefs = refsOf(upper.get(0));
        assertEquals(OperandKind.DOTTED_REF, kindOf(upperRefs, "ADSL.TRT01P"),
                "the uppercase baseline");
        assertEquals(OperandKind.COLUMN, kindOf(upperRefs, "TRT01P"), "the uppercase baseline");
    }
}
