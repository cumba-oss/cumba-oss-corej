package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.BitSet;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * P9 review finding 1 (BLOCKER) regression — CORE-000168's shape: a membership RHS {@code $}-ref
 * whose Operation is GROUPED ({@code distinct(SV.VISITNUM, group=[USUBJID])}) resolves to a per-row
 * {@link GroupedResult}. Pre-fix the native membership plan threw an {@code ExpressionException} at
 * RUN time (the set was assumed broadcast-constant), which under the P7 no-fallback contract
 * surfaced every such rule as ERROR. The plan now resolves the membership set PER ROW via
 * {@code GroupedResult.getForRow}, mirroring the legacy row-aware the grouped-membership contract.
 */
class NativeGroupedMembershipParityTest
{

    @Test
    void groupedDollarMembershipMatchesLegacy() throws Exception
    {
        // CORE-000168 verbatim shape.
        String json = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Operations\":[{\"id\":\"$sv_visitnum\",\"operator\":\"distinct\","
                + "\"domain\":\"SV\",\"name\":\"VISITNUM\",\"group\":[\"USUBJID\"]}],"
                + "\"Check\":{\"all\":[{\"name\":\"VISITNUM\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"VISITNUM\",\"operator\":\"is_not_contained_by\","
                + "\"value\":\"$sv_visitnum\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"VISITNUM\"]}}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the grouped-membership rule must be native");

        // SV: S1 visited 1,2; S2 visited 1. Primary AE rows: S1@2 ok, S1@3 fires (not among S1's
        // SV visits), S2@2 fires (2 is S1's visit, not S2's — the PER-ROW group matters), S2@1 ok.
        IDataTable sv = MockTable.of().name("SV").col("USUBJID", "S1", "S1", "S2")
                .col("VISITNUM", "1", "2", "1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2", "S2")
                .col("VISITNUM", "2", "3", "2", "1").build();
        DatasetResolver resolver = n -> "SV".equals(n) ? sv : null;

        RuleExecutionResult nativ = RuleRunner.execute(rule, ae, resolver, "AE", null, null, null);
        RuleExecutionResult legacy = RuleRunner.execute(rule, ae, resolver, "AE", null, null, null);

        BitSet nativeRows = rows(nativ);
        assertEquals(rows(legacy), nativeRows, "grouped $-membership must match legacy per row");
        BitSet expected = new BitSet();
        expected.set(1);
        expected.set(2);
        assertEquals(expected, nativeRows,
                "rows outside the SUBJECT's own visit set fire (per-row group resolution)");
        assertEquals(RuleExecutionStatus.EXECUTED, nativ.getStatus(),
                "the rule must EXECUTE natively — never ERROR (the pre-fix no-fallback regression)");
    }


    private static BitSet rows(RuleExecutionResult r)
    {
        BitSet bs = new BitSet();
        for (Violation v : r.getViolations())
        {
            bs.set((int) v.getRow());
        }
        return bs;
    }

}
