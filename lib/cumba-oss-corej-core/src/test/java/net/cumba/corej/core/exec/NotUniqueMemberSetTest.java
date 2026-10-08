package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.GroupKeyPolicy;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b SS1 ({@code PLAN-engine-gap-set-of-sets} §5, §6.1 Q1–Q5): {@code
 * is_not_unique_member_set(members=[…], group=[…])} is true on every row whose group (by {@code
 * group}) has a <b>member set</b> — the set of distinct {@code members} tuples over its rows —
 * equal to the member set of at least one other group. A set, not a multiset (a tuple repeated
 * inside one group is {@code CDISC-SEND-0262}'s defect); order-free (two pools listing the same
 * subjects in a different row order are the same set); the {@code is_unique_set} missing policy
 * ({@code ""} and each missing are real, distinct components); an absent member is dropped, an
 * absent group column partitions nothing; every row of each sharing group fires (Q4). Both lists
 * are splice slots ({@code GroupSplice.SPLICE_SLOTS}): a {@code $} name list and the expression it
 * holds splice as the written names, a per-row member is refused at load.
 */
class NotUniqueMemberSetTest
{

    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"}");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        return b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}")
                .toString();
    }


    private static Rule load(String check, String... bindings)
    {
        try
        {
            Rule r = RulePackageLoader.loadFromString(json(check, bindings)).getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(check, bindings).getLoadError());
    }


    private static List<Long> fired(IDataTable table, String check, String... bindings)
    {
        Rule r = load(check, bindings);
        assertNull(r.getLoadError(), () -> check + " -> " + r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static BitSet primitive(IDataTable table, List<String> members, List<String> group)
    {
        return GroupSemantics.notUniqueMemberSetViolations(table, (int) table.getRowCount(),
                members, group, GroupKeyPolicy.FOLD_BLANK_KEYS);
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /**
     * POOLDEF (row A): P1 and P2 pool the same two subjects (P2 in the other row order); P3 shares
     * one subject with them; P4 lists S1 twice beside S2 — a multiset twin of P1; P5 is a
     * singleton.
     */
    private static IDataTable pooldef()
    {
        return RealTables.of("POOLDEF")
                .str("DOMAIN", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF",
                        "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF")
                .str("POOLID", "P1", "P1", "P2", "P2", "P3", "P3", "P4", "P4", "P4", "P5")
                .str("USUBJID", "S1", "S2", "S2", "S1", "S1", "S3", "S1", "S2", "S1", "S4").build();
    }


    /** OI (row B): HIV1MC and HIV1MB differ in one pair; HIV1MD repeats HIV1MC's taxonomy. */
    private static IDataTable oi()
    {
        return RealTables.of("OI").str("DOMAIN", "OI", "OI", "OI", "OI", "OI", "OI")
                .str("NHOID", "HIV1MC", "HIV1MC", "HIV1MB", "HIV1MB", "HIV1MD", "HIV1MD")
                .str("OIPARMCD", "TYPE", "SUBTYPE", "TYPE", "SUBTYPE", "TYPE", "SUBTYPE")
                .str("OIVAL", "HIV-1", "C", "HIV-1", "B", "HIV-1", "C").build();
    }


    @Test
    void twoPoolsWithOneMembershipFireEveryRowOfBoth()
    {
        // P1 ≡ P2 (same set, other row order); P4 ≡ P1 as a SET (its repeated S1 is not a second
        // member); P3 shares a subject but not the set; P5 stands alone
        assertEquals(List.of(1L, 2L, 3L, 4L, 7L, 8L, 9L),
                fired(pooldef(), "is_not_unique_member_set(members=[USUBJID], group=[POOLID])"));
        assertEquals(bits(0, 1, 2, 3, 6, 7, 8),
                primitive(pooldef(), List.of("USUBJID"), List.of("POOLID")));
    }


    @Test
    void aSubjectInTwoDifferentPoolsIsNoFinding()
    {
        IDataTable t = RealTables.of("POOLDEF").str("DOMAIN", "POOLDEF", "POOLDEF", "POOLDEF")
                .str("POOLID", "P1", "P1", "P3").str("USUBJID", "S1", "S2", "S1").build();
        assertEquals(List.of(),
                fired(t, "is_not_unique_member_set(members=[USUBJID], group=[POOLID])"));
    }


    @Test
    void theTaxonomyComparisonIsDepthSensitive()
    {
        // HIV1MC and HIV1MD share the whole (OIPARMCD, OIVAL) set; HIV1MB differs in one pair
        assertEquals(List.of(1L, 2L, 5L, 6L),
                fired(oi(), "is_not_unique_member_set(members=[OIPARMCD, OIVAL], group=[NHOID])"));
        // one member alone (OIPARMCD) makes every taxonomy the same set — the whole table fires
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                fired(oi(), "is_not_unique_member_set(members=[OIPARMCD], group=[NHOID])"));
    }


    @Test
    void equalSetsInADifferentRowOrderFireAndDuplicatesInsideAGroupAreIgnored()
    {
        // the pooldef fixture carries both: P2 lists P1's subjects reversed, P4 lists S1 twice
        BitSet rows = primitive(pooldef(), List.of("USUBJID"), List.of("POOLID"));
        assertTrue(rows.get(2) && rows.get(3), "P2 (reversed order) fires: " + rows);
        assertTrue(rows.get(6) && rows.get(7) && rows.get(8), "P4 (multiset twin) fires: " + rows);
        assertTrue(!rows.get(4) && !rows.get(5) && !rows.get(9), "P3 and P5 do not: " + rows);
    }


    @Test
    void emptyAndMissingMembersAreRealDistinctComponents()
    {
        // P1 {S1, ""} and P2 {S1, ""} share a set; P3 {S1, missing} does not match them
        IDataTable t = RealTables.of("POOLDEF")
                .str("DOMAIN", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF")
                .str("POOLID", "P1", "P1", "P2", "P2", "P3", "P3")
                .str("USUBJID", "S1", "", "S1", "", "S1", null).build();
        assertEquals(List.of(1L, 2L, 3L, 4L),
                fired(t, "is_not_unique_member_set(members=[USUBJID], group=[POOLID])"));
    }


    @Test
    void anAbsentMemberColumnIsDroppedAndAnAbsentGroupColumnPartitionsNothing()
    {
        IDataTable t = pooldef();
        // an absent member is dropped wherever it sits (D-4) — the same partition as its removal
        assertEquals(primitive(t, List.of("USUBJID"), List.of("POOLID")),
                primitive(t, List.of("NOPE", "USUBJID"), List.of("POOLID")));
        assertEquals(primitive(t, List.of("USUBJID"), List.of("POOLID")),
                primitive(t, List.of("USUBJID", "NOPE"), List.of("POOLID")));
        // an absent group column partitions nothing: one group, nothing to compare, no fire
        assertEquals(new BitSet(), primitive(t, List.of("USUBJID"), List.of("NOPE")));
        assertEquals(List.of(),
                fired(t, "is_not_unique_member_set(members=[USUBJID], group=[NOPE])"));
        // when every member drops the member set is the one empty tuple for every group — the
        // documented degenerate flood, as is_unique_set's (every group shares the set)
        assertEquals(bits(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
                primitive(t, List.of("NOPE"), List.of("POOLID")));
    }


    @Test
    void oneGroupOnlyIsNoFinding()
    {
        IDataTable t = RealTables.of("POOLDEF").str("DOMAIN", "POOLDEF", "POOLDEF", "POOLDEF")
                .str("POOLID", "P1", "P1", "P1").str("USUBJID", "S1", "S2", "S1").build();
        assertEquals(List.of(),
                fired(t, "is_not_unique_member_set(members=[USUBJID], group=[POOLID])"));
        assertEquals(new BitSet(), primitive(t, List.of("USUBJID"), List.of("POOLID")));
    }


    @Test
    void aMissingGroupKeyIsItsOwnGroupUnlessKeepMissingsDropsIt()
    {
        // rows 4-5 have no POOLID: under the FOLD default the missing key is a real group that
        // shares P1's set; keep_missings=false takes those rows out of the question
        IDataTable t = RealTables.of("POOLDEF")
                .str("DOMAIN", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF", "POOLDEF")
                .str("POOLID", "P1", "P1", "P2", "P2", null, null)
                .str("USUBJID", "S1", "S2", "S1", "S3", "S1", "S2").build();
        assertEquals(List.of(1L, 2L, 5L, 6L),
                fired(t, "is_not_unique_member_set(members=[USUBJID], group=[POOLID])"));
        assertEquals(List.of(), fired(t,
                "is_not_unique_member_set(members=[USUBJID], group=[POOLID], keep_missings=false)"));
    }


    @Test
    void theMemberAndGroupListsSpliceABoundNameListAndTheExpressionItHolds()
    {
        List<Long> written = fired(pooldef(),
                "is_not_unique_member_set(members=[USUBJID], group=[POOLID])");
        assertTrue(!written.isEmpty(), "premise: the written form fires");
        assertEquals(written, fired(pooldef(), "is_not_unique_member_set(members=$m, group=$g)",
                "$m", "[USUBJID]", "$g", "[POOLID]"));
        assertEquals(written, fired(pooldef(), "is_not_unique_member_set(members=[$m], group=[$g])",
                "$m", "[USUBJID]", "$g", "[POOLID]"));
        assertEquals(written, fired(pooldef(),
                "is_not_unique_member_set(members=minus([\"USUBJID\", \"X\"], subtract=[\"X\"]),"
                        + " group=minus([\"POOLID\", \"X\"], subtract=[\"X\"]))"));
    }


    @Test
    void aPerRowMemberIsRefusedAtLoadBoundAndInlineAlike()
    {
        for (String[] pair : List.of(new String[]
        {
                "is_not_unique_member_set(members=[$p], group=[POOLID])",
                "is_not_unique_member_set(members=[upper(USUBJID)], group=[POOLID])",
                "upper(USUBJID)"
        }, new String[]
        {
                "is_not_unique_member_set(members=[USUBJID], group=[$p])",
                "is_not_unique_member_set(members=[USUBJID], group=[upper(POOLID)])",
                "upper(POOLID)"
        }))
        {
            String bound = loadError(pair[0], "$p", pair[2]);
            String inline = loadError(pair[1]);
            assertTrue(bound.contains("cannot splice the per-row member"), pair[0] + ": " + bound);
            assertTrue(inline.contains("cannot splice the per-row member"),
                    pair[1] + ": " + inline);
        }
        // a quoted name is a string, never a column (R1)
        String quoted = loadError(
                "is_not_unique_member_set(members=[\"USUBJID\"], group=[POOLID])");
        assertTrue(quoted.contains("PARAMETER_TYPE"), quoted);
        // both lists are required
        assertTrue(loadError("is_not_unique_member_set(members=[USUBJID])").contains("group"),
                loadError("is_not_unique_member_set(members=[USUBJID])"));
    }


    @Test
    void theRostersNameTheFunction()
    {
        assertTrue(GroupSplice.splices("is_not_unique_member_set", "members"));
        assertTrue(GroupSplice.splices("is_not_unique_member_set", "group"));
        assertTrue(GroupReadTable.GROUP_OPERATORS.contains("is_not_unique_member_set"));
        assertTrue(net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls.booleanCallNames()
                .contains("is_not_unique_member_set"));
    }
}
