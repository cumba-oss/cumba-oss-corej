package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The shipped acceptance of <i>"no rule may fire unless it is in a package the user
 * selected"</i>, asserted against the <b>real</b> {@link LibraryValidator} wiring — no
 * {@code EnumSet} anywhere in the subject.
 *
 * <p>
 * The engine used to mint rules in Java at run time — {@code GEN-*} identities carrying no
 * {@code Standards} block, therefore belonging to no package, therefore selected by nobody — and
 * merge them into the executed set on every dataset of every run. Fix #366 disabled that;
 * {@code plans/PLAN-remove-rule-generator.md} deleted it. <b>The property is now structural</b>: no
 * code path can mint a rule, so there is no longer anything for the engine to leak.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>What happened to the old non-vacuity control, and why this is not a weakening.</b> This
 * class used to carry a second test that built a {@code DatasetRuleResolver} with
 * {@code EnumSet.allOf(RuleCategory.class)} and asserted it still minted {@code GEN-DISALLOW-DM} —
 * proving the fixture was one on which a retired generator <em>would</em> have fired, so the
 * assertion below could not pass merely because the generators had nothing to do. That control's
 * subject is exactly what the deletion removed, so it could not be kept and could not be repaired.
 * Its javadoc forbade the lazy answer — <i>"must be re-aimed, not deleted"</i> — and it was
 * <b>re-aimed</b>, per ruling {@code R1}: the fixture now carries a <b>wildcard template</b> whose
 * expansion children are the {@code CORE-SELECTED-1-*} ids, and
 * {@link #theDeliveryPathReallyDeliversOnThisFixture()} pins that they actually appear. That keeps
 * the same guarantee where it still bites — the assertion below cannot go green on a fixture
 * through which nothing was delivered — and it now also guards the delivery path itself.
 * </p>
 */
class LibraryValidatorNoUnselectedRulesTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }

    /** The one rule a caller "selects". Everything else in the executed set is a defect. */
    private static final String SELECTED_ID = "CORE-SELECTED-1";

    private static MetadataProvider provider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    /**
     * DM carrying two columns a {@code TRTxxP} wildcard template expands over, so the selected
     * package reaches execution through the <b>delivery path</b> and not only as a pass-through. ⚑
     * {@code SPONSORX} is retained: it is a column the Library does not define, which is what the
     * retired {@code DISALLOWED_VARIABLE} generator keyed on. If a minting path is ever
     * reintroduced, this fixture still gives it something to mint — and
     * {@link #everyRuleThatRunsCameFromTheSelectedPackage()} would catch it.
     */
    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1").col("SPONSORX", "x")
                .col("TRT01P", "A").col("TRT02P", "B").build();
    }


    private static RulePackage selectedPackage()
    {
        Rule rule = new Rule();
        rule.setId("uuid-" + SELECTED_ID);
        RuleCore core = new RuleCore();
        core.setId(SELECTED_ID);
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(expr("empty(STUDYID)"));
        Outcome outcome = new Outcome();
        outcome.setMessage("STUDYID must not be empty");
        rule.setOutcome(outcome);

        // A wildcard template under the SAME selected core id. Its expansions are
        // CORE-SELECTED-1-TRT01P / -TRT02P, which is the `SELECTED_ID + "-"` arm of the assertion
        // below — the arm that would otherwise never be exercised.
        Rule template = new Rule();
        template.setId("uuid-" + SELECTED_ID + "-tpl");
        RuleCore tplCore = new RuleCore();
        tplCore.setId(SELECTED_ID);
        template.setCore(tplCore);
        template.setSensitivity(Sensitivity.RECORD);
        template.setCheck(expr("empty(TRTxxP)"));
        Outcome tplOutcome = new Outcome();
        tplOutcome.setMessage("TRTxxP must not be empty");
        template.setOutcome(tplOutcome);

        RulePackage pkg = new RulePackage();
        Map<String, Rule> rules = new HashMap<>();
        rules.put(SELECTED_ID, rule);
        rules.put(SELECTED_ID + "-tpl", template);
        pkg.setRules(rules);
        return pkg;
    }


    @Test
    void everyRuleThatRunsCameFromTheSelectedPackage()
    {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());

        LibraryValidator.builder().provider(provider()).rules(selectedPackage())
                .targetDataset("DM", "dm.xpt", dmTable())
                .runtimeListener(entry -> executed.add(String.valueOf(entry.coreId()))).build()
                .validate();

        assertFalse(executed.isEmpty(), "the listener saw nothing — the run did not happen");
        for (String id : executed)
        {
            assertTrue(id.equals(SELECTED_ID) || id.startsWith(SELECTED_ID + "-"),
                    () -> "a rule ran that no package selected: " + id + " (all: " + executed
                            + ")");
        }
    }


    /**
     * ⭐ The re-aimed non-vacuity control (ruling {@code R1}).
     *
     * <p>
     * {@link #everyRuleThatRunsCameFromTheSelectedPackage()} is a <em>universal</em> assertion over
     * the executed set, so it passes trivially on a fixture through which little was delivered.
     * This pins that the fixture genuinely exercises the delivery path: the {@code TRTxxP} template
     * in the selected package must reach execution as its expansion <b>children</b>, {@code
     * CORE-SELECTED-1-TRT01P} and {@code -TRT02P}. If wildcard expansion ever silently stops, this
     * reds — and the assertion above would otherwise have kept passing, more vacuously than before.
     * </p>
     */
    @Test
    void theDeliveryPathReallyDeliversOnThisFixture()
    {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());

        LibraryValidator.builder().provider(provider()).rules(selectedPackage())
                .targetDataset("DM", "dm.xpt", dmTable())
                .runtimeListener(entry -> executed.add(String.valueOf(entry.coreId()))).build()
                .validate();

        List<String> children = executed.stream().filter(id -> id.startsWith(SELECTED_ID + "-"))
                .sorted().toList();
        assertEquals(List.of(SELECTED_ID + "-TRT01P", SELECTED_ID + "-TRT02P"), children,
                () -> "the wildcard template was not delivered as expansion children; executed was "
                        + executed);
    }
}
