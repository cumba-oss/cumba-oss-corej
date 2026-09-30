package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.corej.core.exec.RealTables;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code over: shared_variables} drops exactly the bindings whose check the rule's own merge
 * decides — the positions where a {@code Match_Datasets} key merged the primary's {@code X} on the
 * joined dataset's {@code X} — and nothing else (review round 1 of
 * {@code PLAN-rprfdy-offset-tp-join}, lane 1 L2).
 *
 * <p>
 * Until then {@code TokenExpander.mergeKeysFor} collected every left AND every right key name, so a
 * sided key {@code {left: BWPHASE, right: RPHASE}} dropped both {@code BWPHASE} and {@code RPHASE}
 * (neither comparison is decided by merging {@code BWPHASE} on {@code RPHASE}), and a qualified key
 * {@code DM.RPATHCD} dropped the primary's own {@code RPATHCD != TP.RPATHCD} (the merge read the
 * bound DM record's {@code RPATHCD}, not the primary's).
 * </p>
 */
class SharedVariablesMergeKeyTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private static IDataTable table(String name, String... columns)
    {
        return new SyntheticDataTable(name, List.of(columns), new String[]
        {
                "X"
        }, 1);
    }


    private static MatchDataset md(String json)
    {
        try
        {
            return JSON.readValue(json, MatchDataset.class);
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }


    private static Rule template(MatchDataset... entries)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("T-SV");
        rule.setCore(core);
        String source = "`&VAR` != `TP.&VAR`";
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(source), source));
        rule.setMatchDatasets(List.of(entries));
        ExpansionDirective d = new ExpansionDirective();
        d.setToken("&VAR");
        d.setOverJson(ExpansionSource.SHARED_VARIABLES.getJsonValue());
        d.setWith("TP");
        rule.setExpansion(List.of(d));
        return rule;
    }


    /** The expanded rule ids over a BW primary, TP (and DM) in the study. */
    private static List<String> expandedIds(Rule template, IDataTable bw, IDataTable tp)
    {
        TokenExpander.Context ctx = new TokenExpander.Context(
                ScopeVariableSource.of(RealTables.inventoryOf(bw, tp, table("DM", "USUBJID")), bw),
                null, "BW");
        return assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                TokenExpander.tryExpand(template, bw.getMetaData(), ctx)).rules().stream()
                        .map(Rule::effectiveId).toList();
    }


    @Test
    void aSameNamedKeyIsExcludedAndEveryOtherSharedColumnExpands()
    {
        // CONTROL (green before the fix too): merged on RPHASE, so RPHASE != TP.RPHASE is decided.
        assertEquals(List.of("T-SV-RPATHCD"),
                expandedIds(template(md("{\"Name\":\"TP\",\"Keys\":[\"RPHASE\"]}")),
                        table("BW", "USUBJID", "RPHASE", "RPATHCD"),
                        table("TP", "RPATHCD", "RPHASE")));
    }


    @Test
    void aQualifiedKeyDoesNotExcludeThePrimarysOwnColumnOfThatName()
    {
        // TP merged on [DM.RPATHCD, RPHASE]: RPHASE is a tautology, the primary's RPATHCD is not —
        // the merge read the bound DM record's RPATHCD.
        assertEquals(List.of("T-SV-RPATHCD"), expandedIds(
                template(md("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}"),
                        md("{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"]}")),
                table("BW", "USUBJID", "RPHASE", "RPATHCD"), table("TP", "RPATHCD", "RPHASE")));
    }


    @Test
    void aSidedKeyExcludesNeitherOfItsTwoNames()
    {
        // BW.BWPHASE merged on TP.RPHASE: neither BWPHASE != TP.BWPHASE nor RPHASE != TP.RPHASE is
        // decided by that merge.
        assertEquals(List.of("T-SV-BWPHASE", "T-SV-RPHASE"), expandedIds(
                template(md("{\"Name\":\"TP\",\"Keys\":[{\"left\":\"BWPHASE\","
                        + "\"right\":\"RPHASE\"}]}")),
                table("BW", "USUBJID", "BWPHASE", "RPHASE"), table("TP", "RPHASE", "BWPHASE")));
        // CONTROL: a sided element naming the same column on both sides IS the tautology.
        assertEquals(List.of("T-SV-BWPHASE"), expandedIds(template(
                md("{\"Name\":\"TP\",\"Keys\":[{\"left\":\"RPHASE\"," + "\"right\":\"RPHASE\"}]}")),
                table("BW", "USUBJID", "BWPHASE", "RPHASE"), table("TP", "RPHASE", "BWPHASE")));
    }

}
