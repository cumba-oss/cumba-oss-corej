package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.gen.GeneratedRulePackage;
import net.cumba.cdisc.core.gen.RuleCategory;
import net.cumba.cdisc.core.gen.RuleGenerator;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The acceptance of {@code plans/PLAN-retire-engine-generated-rules.md} phase 2, asserted against
 * the <b>shipped</b> wiring: <i>no rule may fire unless it is in a package the user selected.</i>
 *
 * <p>
 * The engine used to mint rules in Java at run time — {@code GEN-*} identities carrying no
 * {@code Standards} block, therefore belonging to no package, therefore selected by nobody — and
 * merge them into the executed set on every dataset of every run. {@code LibraryValidator} is the
 * one production construction site of {@link RuleGenerator}, and since Fix #366 it constructs it
 * with {@link RuleCategory#corpusDeliveryOnly()}: the two categories that deliver the selected
 * packages' own rules, and nothing else.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why the control below is not optional.</b> A test that builds its own restricted
 * {@code RuleGenerator} and finds no {@code GEN-} id proves the mechanism works and never that
 * production carries it — the shape that let {@code KDICT-F1} hide behind a green gate. So this
 * class drives {@link LibraryValidator#validate()} (no {@code EnumSet} anywhere in the subject) and
 * separately proves, with an {@code allOf} generator over the <em>same</em> fixture, that the
 * fixture really is one on which the retired generators would fire. Without that second half a
 * fixture that simply gives the generators nothing to do would pass identically.
 * </p>
 */
class LibraryValidatorNoUnselectedRulesTest
{

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
     * DM with one column the Library does not define ({@code SPONSORX}) — the trigger for the
     * {@code DISALLOWED_VARIABLE} generator, which minted {@code GEN-DISALLOW-DM}.
     */
    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1").col("SPONSORX", "x").build();
    }


    private static RulePackage selectedPackage()
    {
        Rule rule = new Rule();
        rule.setId("uuid-" + SELECTED_ID);
        RuleCore core = new RuleCore();
        core.setId(SELECTED_ID);
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("empty").build());
        Outcome outcome = new Outcome();
        outcome.setMessage("STUDYID must not be empty");
        rule.setOutcome(outcome);

        RulePackage pkg = new RulePackage();
        Map<String, Rule> rules = new HashMap<>();
        rules.put(SELECTED_ID, rule);
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
     * The control. Same provider, same table — but a generator with every category enabled, which
     * is what {@code LibraryValidator} used to build. It mints {@code GEN-DISALLOW-DM}, an identity
     * carrying no {@code Standards} block and belonging to no package. If this ever stops firing,
     * the assertion above has gone vacuous and must be re-aimed, not deleted.
     */
    @Test
    void theFixtureReallyWouldProduceAnUnselectedRuleUnderTheOldWiring()
    {
        MetadataProvider provider = provider();
        RuleGenerator allCategories = new RuleGenerator(provider, null, null, provider.getVersion(),
                EnumSet.allOf(RuleCategory.class));
        allCategories.setStaticRules(List.of(selectedPackage().getRules().get(SELECTED_ID)));
        allCategories.setDomainName("DM");
        allCategories.setClassName("Special-Purpose");

        GeneratedRulePackage pkg = allCategories.generate(dmTable());

        List<String> ids = pkg.getRules().stream()
                .map(r -> r.getCore() != null ? r.getCore().getId() : null).toList();
        assertTrue(ids.contains("GEN-DISALLOW-DM"),
                () -> "the control no longer mints an unselected rule; ids were " + ids);
    }
}
