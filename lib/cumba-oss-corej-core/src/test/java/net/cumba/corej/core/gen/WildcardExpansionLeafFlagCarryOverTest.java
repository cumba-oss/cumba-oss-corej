package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code WildcardExpander.substituteLeaf} rebuilds a check leaf field by field whenever an
 * expansion rewrites its name (or its value). Any field the rebuild forgets is dropped
 * <b>silently</b>: the expanded rule still loads, still runs and still reports, but it judges the
 * data by a different contract than the one its author wrote and a reviewer approved. Nothing
 * downstream can see the difference.
 *
 * <p>
 * Two fields were being lost. {@code include_empty} (Fix #121) decides whether blank cells take
 * part in the check at all, so losing it changes the verdict on exactly the rows a data manager
 * cares most about. {@code names} is the composite tuple-membership target (T3): a {@code names}
 * leaf carries a null {@code name}, so it reaches the rebuild only when its VALUE holds a wildcard
 * — and dropping it left the leaf with no target whatsoever.
 * </p>
 *
 * <p>
 * These pins are deliberately written against the rebuild's output rather than an end-to-end
 * verdict, because that is where the loss happened and where a future field addition will reoccur.
 * </p>
 */
class WildcardExpansionLeafFlagCarryOverTest
{

    /** One concrete treatment period, so a {@code TRTxxP} template expands exactly once. */
    private static DataTableMeta oneTreatmentPeriod()
    {
        IDataTable t = MockTable.of().name("ADSL").col("USUBJID", "S1").col("TRT01P", "").build();
        return t.getMetaData();
    }


    private static Rule templateWith(CheckConditionLeaf leaf)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-900900");
        rule.setCore(core);
        rule.setDescription("TRTxxP fixture");
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(leaf);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        return rule;
    }


    private static CheckConditionLeaf expandedLeafOf(CheckConditionLeaf leaf)
    {
        List<Rule> expanded = WildcardExpander.expand(templateWith(leaf), oneTreatmentPeriod());
        assertEquals(1, expanded.size(), "expected exactly one expansion: " + expanded);
        Rule concrete = expanded.get(0);
        // A load-errored expansion short-circuits derivation and would make the assertions below
        // vacuously green.
        assertNull(concrete.getLoadError(), concrete.getLoadError());
        return assertInstanceOf(CheckConditionLeaf.class, concrete.getCheck());
    }


    @Test
    void includeEmptySurvivesTheNameRewrite()
    {
        CheckConditionLeaf got = expandedLeafOf(CheckConditionLeaf.builder().name("TRTxxP")
                .operator("empty").includeEmpty(true).build());

        assertEquals("TRT01P", got.getName(), "the name really was rewritten");
        assertEquals(true, got.getIncludeEmpty(),
                "an authored include_empty must not be dropped by the rebuild");
    }


    @Test
    void includeEmptyFalseIsCarriedJustAsFaithfullyAsTrue()
    {
        // An explicit `false` is not the same as an absent value downstream, so the rebuild must
        // carry it rather than let it default.
        CheckConditionLeaf got = expandedLeafOf(CheckConditionLeaf.builder().name("TRTxxP")
                .operator("empty").includeEmpty(false).build());

        assertEquals(false, got.getIncludeEmpty());
    }


    @Test
    void anAbsentIncludeEmptyStaysAbsent()
    {
        // Guards the fix against over-reach: the rebuild must not invent a value.
        assertNull(expandedLeafOf(
                CheckConditionLeaf.builder().name("TRTxxP").operator("empty").build())
                        .getIncludeEmpty());
    }

    // ---- the same rebuild, on the `--` prefix path ----
    //
    // CheckConditionTransformer.transformLeaf is the SECOND (and last) place in the engine that
    // rebuilds a leaf from an existing leaf, and it carried the identical gap. It is the more
    // exposed of the two: SDTM_PREFIX_EXPANSION passes every scoped static rule through it.


    private static CheckConditionLeaf resolved(CheckConditionLeaf leaf)
    {
        return assertInstanceOf(CheckConditionLeaf.class,
                net.cumba.corej.core.exec.CheckConditionTransformer.resolvePrefixes(leaf, "AE"));
    }


    @Test
    void includeEmptySurvivesTheDashPrefixResolution()
    {
        CheckConditionLeaf got = resolved(CheckConditionLeaf.builder().name("--DTC")
                .operator("empty").includeEmpty(true).build());

        assertEquals("AEDTC", got.getName(), "the name really was resolved");
        assertEquals(true, got.getIncludeEmpty(),
                "an authored include_empty must survive `--` resolution too");
    }


    @Test
    void anUnchangedLeafIsReturnedAsIsByTheDashPrefixPath()
    {
        // Guards against over-reach: a leaf with no `--` is not rebuilt at all.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AEDTC").operator("empty")
                .includeEmpty(true).build();

        assertEquals(true, resolved(leaf).getIncludeEmpty());
    }


    @Test
    void theCompositeNamesTargetSurvivesAValueRewrite()
    {
        // A `names` leaf has a null name, so only a wildcard in the VALUE drags it through the
        // rebuild. Losing `names` would leave the leaf with no target at all.
        CheckConditionLeaf got = expandedLeafOf(
                CheckConditionLeaf.builder().names(List.of("VISIT", "VISITNUM"))
                        .operator("is_not_contained_by").value(new TextNode("TRTxxP")).build());

        assertNotNull(got.getNames(), "the composite target must not be dropped");
        assertEquals(List.of("VISIT", "VISITNUM"), got.getNames());
    }

}
