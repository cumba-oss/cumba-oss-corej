package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * D94c (Review 2, phase 5): a group finding's plain-column output values are computed <b>over the
 * block's flagged rows</b> — degenerating to the single value when constant, which D94a measures
 * true of the 20 NO-CHANGE rules — never by a plain read of the anchor row. The rendered output is
 * identical either way while the value is constant, so what reds on a regression to anchor-reading
 * is this class: the flagged-rows-only scan ({@link #blockScanReadsOnlyTheFlaggedRows}) and the
 * GROUP-VALUE observation plus distinct-set rendering
 * ({@link #aNonConstantOutputRendersTheDistinctSetAndIsObserved}, phase 5b / D58), which an anchor
 * read can never produce.
 */
class GroupBlockOutputTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    @AfterEach
    void reset()
    {
        LevelInstrument.setGroupOutputObserver(null);
    }


    /**
     * A Group-sensitivity rule flagging {@code AEOUT == "FATAL"} rows, grouped by {@code USUBJID},
     * projecting {@code AEDECOD}.
     */
    private static Rule fatalRule()
    {
        Rule rule = new Rule();
        rule.setId("TEST-G1");
        rule.setSensitivity(Sensitivity.GROUP);
        rule.setGroupingVariables(List.of("USUBJID"));
        rule.setCheckExpr(CheckExpressionParser.parse("AEOUT == \"FATAL\""));
        rule.setCheck(expr("AEOUT == \"FATAL\""));
        Outcome outcome = new Outcome();
        outcome.setOutputVariables(List.of("AEDECOD"));
        rule.setOutcome(outcome);
        return rule;
    }


    /** Builds an AE table from parallel USUBJID / AEOUT / AEDECOD rows. */
    private static IDataTable ae(String[] usubjid, String[] aeout, String[] aedecod)
    {
        OverlayDataTable t = OverlayDataTable.empty("AE", "AE", usubjid.length);
        t.addColumn("USUBJID", DataValueType.STRING, "USUBJID");
        t.addColumn("AEOUT", DataValueType.STRING, "AEOUT");
        t.addColumn("AEDECOD", DataValueType.STRING, "AEDECOD");
        for (int r = 0; r < usubjid.length; r++)
        {
            t.setValue(r, "USUBJID", usubjid[r]);
            t.setValue(r, "AEOUT", aeout[r]);
            t.setValue(r, "AEDECOD", aedecod[r]);
        }
        return t;
    }


    @Test
    void aConstantOutputOverTheFlaggedRowsRendersTheDegenerateValue()
    {
        AtomicReference<LevelInstrument.GroupOutputObservation> seen = new AtomicReference<>();
        LevelInstrument.setGroupOutputObserver(seen::set);
        // Subject A: rows 1 and 2 flagged, both HEADACHE — the distinct set degenerates (D94b).
        IDataTable table = ae(new String[]
        {
                "A", "A", "A"
        }, new String[]
        {
                "OTHER", "FATAL", "FATAL"
        }, new String[]
        {
                "X", "HEADACHE", "HEADACHE"
        });

        RuleExecutionResult result = RuleRunner.execute(fatalRule(), table);

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size());
        assertEquals("HEADACHE", result.getViolations().get(0).getValues().get("AEDECOD"));
        assertNull(seen.get(), "a degenerate value-set is not a GROUP-VALUE observation");
    }


    @Test
    void blockScanReadsOnlyTheFlaggedRows()
    {
        // Row 0 is NOT flagged and carries a different AEDECOD. A scan over ALL block rows would
        // see {X, HEADACHE} and misreport non-constancy; D94a's kind-1 constancy is defined over
        // the VIOLATING rows. This is the test that reds if the scan widens (or narrows to the
        // anchor, where the observation below is what reds).
        AtomicReference<LevelInstrument.GroupOutputObservation> seen = new AtomicReference<>();
        LevelInstrument.setGroupOutputObserver(seen::set);
        IDataTable table = ae(new String[]
        {
                "A", "A", "A"
        }, new String[]
        {
                "OTHER", "FATAL", "FATAL"
        }, new String[]
        {
                "X", "HEADACHE", "HEADACHE"
        });

        RuleRunner.execute(fatalRule(), table);

        assertNull(seen.get(), "non-flagged rows must not enter the value-set");
    }


    @Test
    void aNonConstantOutputRendersTheDistinctSetAndIsObserved()
    {
        // Subject B's flagged rows disagree (NAUSEA vs RASH) — the D94a GROUP-VALUE shape.
        // Phase 5b's report shape (D58): the value is the distinct set over the flagged rows, in
        // scalarToString's collection form, and the variable is observed. A regression to
        // anchor-reading renders a single arbitrary value and silences the observation: both
        // assertions red.
        AtomicReference<LevelInstrument.GroupOutputObservation> seen = new AtomicReference<>();
        LevelInstrument.setGroupOutputObserver(seen::set);
        IDataTable table = ae(new String[]
        {
                "B", "B"
        }, new String[]
        {
                "FATAL", "FATAL"
        }, new String[]
        {
                "NAUSEA", "RASH"
        });

        RuleExecutionResult result = RuleRunner.execute(fatalRule(), table);

        assertEquals(1, result.getViolations().size());
        assertEquals("[NAUSEA, RASH]", result.getViolations().get(0).getValues().get("AEDECOD"));
        LevelInstrument.GroupOutputObservation obs = seen.get();
        assertNotNull(obs, "a non-degenerate value-set is the GROUP-VALUE population");
        assertEquals(Set.of("AEDECOD"), obs.nonConstant());
        assertEquals("TEST-G1", obs.ruleId());
    }


    @Test
    void twoBlocksProjectTheirOwnBlockValues()
    {
        LevelInstrument.setGroupOutputObserver(
                new AtomicReference<LevelInstrument.GroupOutputObservation>()::set);
        IDataTable table = ae(new String[]
        {
                "A", "A", "B", "B"
        }, new String[]
        {
                "FATAL", "FATAL", "OTHER", "FATAL"
        }, new String[]
        {
                "HEADACHE", "HEADACHE", "X", "RASH"
        });

        RuleExecutionResult result = RuleRunner.execute(fatalRule(), table);

        assertEquals(2, result.getViolations().size());
        Map<String, String> byRow0 = result.getViolations().get(0).getValues();
        Map<String, String> byRow1 = result.getViolations().get(1).getValues();
        assertEquals("HEADACHE", byRow0.get("AEDECOD"));
        assertEquals("RASH", byRow1.get("AEDECOD"));
    }


    @Test
    void theSingleGroupFallbackComputesOverAllFlaggedRows()
    {
        // Grouping column absent from the dataset -> the whole dataset is one group; the flagged
        // rows span it, and a disagreement among them is the same GROUP-VALUE observation.
        AtomicReference<LevelInstrument.GroupOutputObservation> seen = new AtomicReference<>();
        LevelInstrument.setGroupOutputObserver(seen::set);
        Rule rule = fatalRule();
        rule.setGroupingVariables(List.of("ZZGRP"));
        IDataTable table = ae(new String[]
        {
                "A", "B"
        }, new String[]
        {
                "FATAL", "FATAL"
        }, new String[]
        {
                "NAUSEA", "RASH"
        });

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals(1, result.getViolations().size());
        assertEquals("[NAUSEA, RASH]", result.getViolations().get(0).getValues().get("AEDECOD"));
        assertNotNull(seen.get());
        assertEquals(Set.of("AEDECOD"), seen.get().nonConstant());
    }

}
