package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⛔⛔ Plan C phase 4, D2b — <b>the {@code new Rule()} clone sites carry the level map.</b>
 *
 * <p>
 * Each of {@code WildcardExpander#expandRule}, {@code TokenExpander} and {@code RuleGenerator}'s
 * {@code --}-prefix expansion rebuilds a rule <em>field by field</em> from a fresh
 * {@code new Rule()}. A top-level field the rebuild does not name is <b>silently dropped</b> from
 * every expanded child, and the drop is invisible to the loader, to both schemas and to the writer,
 * because the <em>template</em> still carries it. Plan C phase 3 lost <b>944 finding rows</b> to
 * exactly that on {@code Severity}; this test exists so the level map cannot repeat it.
 * </p>
 *
 * <p>
 * ⚑ The assertions are deliberately stronger than "the map is non-null": they check that each
 * level's condition was <em>rewritten</em>, because a copied-but-unrewritten map would ship the
 * template's wildcard tokens as literal column names — a rule that checks nothing, silently.
 * </p>
 */
class CheckLevelCloneSiteTest
{

    private static CheckCondition expr(String aText)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(aText), aText);
    }


    private static String printed(CheckCondition aCondition)
    {
        return ExpressionPrinter.print(((CheckConditionExpression) aCondition).expr());
    }


    private static SequencedMap<Severity, LevelCheck> levels(String aStrict, String aWeak)
    {
        SequencedMap<Severity, LevelCheck> out = new LinkedHashMap<>();
        out.put(Severity.WARNING, new LevelCheck(expr(aStrict), "definitely wrong"));
        out.put(Severity.INFO, new LevelCheck(expr(aWeak), null));
        return out;
    }


    private static Rule template(String aId, String aStrict, String aWeak)
    {
        Rule r = new Rule();
        r.setId(UUID.randomUUID().toString());
        RuleCore core = new RuleCore();
        core.setId(aId);
        core.setStatus("Published");
        core.setVersion("1");
        r.setCore(core);
        r.setDescription("clone-site fixture");
        r.setSensitivity(Sensitivity.RECORD);
        // ⛔ Authored Warning ON PURPOSE (M1): effectiveSeverity() defaults to Error, so a
        // fixture left at the default would make the severity assertions below vacuously green
        // even with every clone site's setSeverity line deleted — the exact shape of the 944-row
        // phase-3 regression this test exists to pin.
        r.setSeverity(Severity.WARNING);
        r.setCheck(expr(aStrict));
        r.setCheckLevels(levels(aStrict, aWeak));
        Outcome o = new Outcome();
        o.setMessage("m");
        r.setOutcome(o);
        return r;
    }


    private static void assertLevelsRewritten(Rule aExpanded, String aStrict, String aWeak)
    {
        SequencedMap<Severity, LevelCheck> got = aExpanded.getCheckLevels();
        assertNotNull(got, "the level map must ride onto the expanded child, not be dropped");
        assertEquals(List.of(Severity.WARNING, Severity.INFO), List.copyOf(got.keySet()));
        assertEquals(aStrict, printed(got.get(Severity.WARNING).condition()),
                "the strictest level is rewritten with the expansion's own substitution");
        assertEquals(aWeak, printed(got.get(Severity.INFO).condition()),
                "⛔ and so is the WEAKER level — a copied-but-unrewritten level ships the "
                        + "template's tokens as literal column names");
        assertEquals("definitely wrong", got.get(Severity.WARNING).message(),
                "each level's own Message survives the expansion");
        assertEquals(printed(got.get(Severity.WARNING).condition()), printed(aExpanded.getCheck()),
                "getCheck() stays the strictest level's condition");
        assertEquals(Severity.WARNING, aExpanded.effectiveSeverity(),
                "⛔⛔ Severity must ride onto the clone (M1) — the fixture is authored Warning so "
                        + "this cannot pass off the Error default; deleting a clone site's "
                        + "setSeverity line must turn exactly this assertion red (the 944-row "
                        + "phase-3 regression)");
    }


    @Test
    @DisplayName("WildcardExpander — every level is substituted, not just the strictest")
    void wildcardExpanderCarriesTheLevelMap()
    {
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT01P", "STUDYID");
        Rule tpl = template("TEST-WC", "var_exists(TRTxxPN)", "var_exists(TRTxxP)");

        List<Rule> expanded = WildcardExpander.expand(tpl, table.getMetaData());

        assertEquals(1, expanded.size());
        assertLevelsRewritten(expanded.getFirst(), "var_exists(TRT01PN)", "var_exists(TRT01P)");
    }


    @Test
    @DisplayName("WildcardExpander — a marker in a WEAKER level alone still makes the rule a template")
    void aMarkerInAWeakerLevelIsDetected()
    {
        IDataTable table = MockTable.withColumns("TRT01P", "STUDYID");
        // The strictest level carries no marker at all; only INFO does.
        Rule tpl = template("TEST-WC2", "var_exists(STUDYID)", "var_exists(TRTxxP)");

        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(tpl,
                table.getMetaData());

        assertFalse(result instanceof WildcardExpander.ExpansionResult.NotApplicable,
                "detection reads every level; otherwise the INFO level ships 'TRTxxP' verbatim");
        assertTrue(result instanceof WildcardExpander.ExpansionResult.Expanded, result.toString());
        List<Rule> rules = ((WildcardExpander.ExpansionResult.Expanded) result).rules();
        assertEquals(1, rules.size());
        assertLevelsRewritten(rules.getFirst(), "var_exists(STUDYID)", "var_exists(TRT01P)");
    }


    @Test
    @DisplayName("TokenExpander — every level is substituted")
    void tokenExpanderCarriesTheLevelMap()
    {
        IDataTable adae = MockTable.of().name("ADAE").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "50").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S").col("USUBJID", "U")
                .col("AGE", "51").build();

        Rule tpl = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-TK");
        tpl.setCore(core);
        // Authored Warning for the same non-vacuousness reason as template() (M1).
        tpl.setSeverity(Severity.WARNING);
        tpl.setCheck(new CheckConditionAll(List.of(leaf("&VAR", "non_empty"))));
        SequencedMap<Severity, LevelCheck> tokenLevels = new LinkedHashMap<>();
        tokenLevels.put(Severity.WARNING, new LevelCheck(
                new CheckConditionAll(List.of(leaf("&VAR", "non_empty"))), "definitely wrong"));
        tokenLevels.put(Severity.INFO,
                new LevelCheck(new CheckConditionAll(List.of(leaf("&VAR", "empty"))), null));
        tpl.setCheckLevels(tokenLevels);
        Outcome o = new Outcome();
        o.setMessage("m");
        tpl.setOutcome(o);
        ExpansionDirective d = new ExpansionDirective();
        d.setToken("&VAR");
        d.setOver(ExpansionSource.SHARED_VARIABLES);
        d.setWith("ADSL");
        tpl.setExpansion(List.of(d));

        ScopeVariableSource source = ScopeVariableSource.of(inventory(Map.of("ADSL", adsl)), adae);
        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(tpl, adae.getMetaData(),
                new TokenExpander.Context(source, null, "ADAE"));

        List<Rule> rules = assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class, result)
                .rules();
        // One concrete rule per column ADAE and ADSL share.
        assertEquals(List.of("TEST-TK-STUDYID", "TEST-TK-USUBJID", "TEST-TK-AGE"),
                rules.stream().map(Rule::effectiveId).toList());
        for (Rule concrete : rules)
        {
            String column = java.util.Objects.requireNonNull(concrete.effectiveId())
                    .substring("TEST-TK-".length());
            SequencedMap<Severity, LevelCheck> got = concrete.getCheckLevels();
            assertNotNull(got, "the level map must ride onto the expanded child, not be dropped");
            assertEquals(List.of(Severity.WARNING, Severity.INFO), List.copyOf(got.keySet()));
            assertEquals("not empty(" + column + ")",
                    rendered(got.get(Severity.WARNING).condition()));
            assertEquals("empty(" + column + ")", rendered(got.get(Severity.INFO).condition()),
                    "⛔ the WEAKER level is substituted too, or it ships the token as a column "
                            + "name");
            assertEquals("definitely wrong", got.get(Severity.WARNING).message());
            assertNull(got.get(Severity.INFO).message());
            assertEquals(Severity.WARNING, concrete.effectiveSeverity(),
                    "⛔⛔ Severity must ride onto the clone (M1) — authored Warning so the Error "
                            + "default cannot satisfy this; deleting TokenExpander's setSeverity "
                            + "line must turn this red");
        }
    }


    @Test
    @DisplayName("RuleGenerator's --prefix expansion — every level is resolved (the phase-3 site)")
    void sdtmPrefixExpansionCarriesTheLevelMap()
    {
        IDataTable ae = MockTable.of().name("AE").col("AEDTC", "2020-01-01").col("AESTDTC", "")
                .build();

        Rule tpl = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-PFX");
        tpl.setCore(core);
        tpl.setDescription("prefix fixture");
        tpl.setSensitivity(Sensitivity.RECORD);
        // Authored Warning for the same non-vacuousness reason as template() (M1).
        tpl.setSeverity(Severity.WARNING);
        tpl.setCheck(new CheckConditionAll(List.of(leaf("--DTC", "non_empty"))));
        SequencedMap<Severity, LevelCheck> prefixLevels = new LinkedHashMap<>();
        prefixLevels.put(Severity.WARNING, new LevelCheck(
                new CheckConditionAll(List.of(leaf("--DTC", "non_empty"))), "definitely wrong"));
        prefixLevels.put(Severity.INFO,
                new LevelCheck(new CheckConditionAll(List.of(leaf("--STDTC", "empty"))), null));
        tpl.setCheckLevels(prefixLevels);
        Outcome o = new Outcome();
        o.setMessage("m");
        tpl.setOutcome(o);

        List<Rule> out = new java.util.ArrayList<>();
        new RuleGenerator(null, null).expandSdtmPrefixRules(ae.getMetaData(), "AE", List.of(tpl),
                out, new RuleGenerationReport("AE", null, null, null));

        assertEquals(1, out.size(), out.toString());
        SequencedMap<Severity, LevelCheck> got = out.getFirst().getCheckLevels();
        assertNotNull(got,
                "⛔ THIS is the site that cost phase 3 944 finding rows on Severity — buildRule "
                        + "starts from a fresh new Rule(), so an unnamed field is silently lost");
        assertEquals(List.of(Severity.WARNING, Severity.INFO), List.copyOf(got.keySet()));
        assertEquals("not empty(AEDTC)", rendered(got.get(Severity.WARNING).condition()));
        assertEquals("empty(AESTDTC)", rendered(got.get(Severity.INFO).condition()),
                "the weaker level's `--` is resolved with the same prefix");
        assertEquals("definitely wrong", got.get(Severity.WARNING).message());
        assertEquals(rendered(got.get(Severity.WARNING).condition()),
                rendered(java.util.Objects.requireNonNull(out.getFirst().getCheck())),
                "getCheck() stays the strictest level's condition");
        assertEquals(Severity.WARNING, out.getFirst().effectiveSeverity(),
                "⛔⛔ Severity must ride onto the clone (M1) — THIS is the site that cost phase 3 "
                        + "944 finding rows; authored Warning so the Error default cannot satisfy "
                        + "it, and deleting RuleGenerator's setSeverity line must turn this red");
    }


    private static CheckConditionLeaf leaf(String aName, String aOperator)
    {
        return CheckConditionLeaf.builder().name(aName).operator(aOperator).build();
    }


    private static String rendered(CheckCondition aCondition)
    {
        return ExpressionPrinter.print(net.cumba.corej.core.expr.CheckToExpr.toExpr(aCondition));
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> aByName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String aName)
            {
                return aName == null ? null : aByName.get(aName);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return aByName.keySet();
            }
        };
    }

}
