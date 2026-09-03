package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.GroupKeyPolicy.Blankness;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Phase 2 of {@code PLAN-grouping-missing-key-semantics}: the grouping primitive is unified behind
 * one {@link GroupKeyPolicy} and one predicate.
 *
 * <p>
 * ⚠⚠ <b>The original heading said "and <em>nothing changes behaviour</em>". That was true of the
 * unification and is no longer true of this class</b>: {@code W32-E3} (owner, 2026-08-12) retired
 * {@code Blankness.MISSING_ONLY}, so {@code ""} is now blank wherever a {@code MissingValue} is.
 * Two assertions here were <b>inverted or re-pointed</b> rather than deleted — each says so at its
 * own site, and each is the only thing pinning the new contract.
 * </p>
 *
 * <p>
 * The value of this class is entirely in what it <em>pins</em>. Two asymmetries in the shipped
 * engine look like untidiness and are not:
 * </p>
 * <ul>
 * <li>{@code Operations[].group:} <b>folds</b> a blank key for five operators and <b>discards</b>
 * it for {@code is_last_in_group} — one authoring surface, two behaviours;</li>
 * <li>{@code GroupSemantics.componentKeyValue}'s two branches — ⭐ <b>they no longer disagree about
 * {@code ""} ({@code W32-E3} made both call it blank); the surviving disagreement is
 * WHITESPACE-ONLY</b>, and it is now the entire difference between {@code DROP_MISSING_KEYS} and
 * {@code COALESCE_COMPONENT}, which makes it MORE fragile than before, not less.</li>
 * </ul>
 *
 * <p>
 * Both are pinned here so a later "cleanup" cannot erase them silently. ⚠ Each assertion below was
 * neutered and watched to fail before being kept — see the class comment on
 * {@link #coalesceBranchAndSingletonBranchNowAgreeOnEmptyButNotOnWhitespace()} for the one that
 * needs a deliberately-shaped fixture.
 * </p>
 */
class GroupKeyPolicyUnificationTest
{

    private static Set<Set<Integer>> groupSet(List<int[]> groups)
    {
        Set<Set<Integer>> out = new HashSet<>();
        for (int[] g : groups)
        {
            Set<Integer> s = new HashSet<>();
            for (int r : g)
            {
                s.add(r);
            }
            out.add(s);
        }
        return out;
    }


    private static List<List<String>> comps(Object... entries)
    {
        List<List<String>> out = new ArrayList<>();
        for (Object e : entries)
        {
            if (e instanceof String s)
            {
                out.add(List.of(s));
            }
            else
            {
                @SuppressWarnings("unchecked")
                List<String> group = (List<String>) e;
                out.add(group);
            }
        }
        return out;
    }


    /**
     * A table with a NUMERIC key column carrying a genuine missing marker on row 1. Numeric is
     * deliberate: after {@code Fix #161} a character column yields only {@code ""} and a numeric
     * column only a {@code MissingValue}, so a genuine missing key is reachable <b>only</b> through
     * a numeric column. A character fixture would make every discard assertion below vacuous.
     */
    private static IDataTable numericKeyWithMissing()
    {
        return MockTable.of().colLong("K", 1L, null, 1L, 2L).col("V", "a", "b", "c", "d").build();
    }

    // ------------------------------------------------------------------
    // The predicate itself — one method, TWO notions since W32-E3 retired MISSING_ONLY
    // ------------------------------------------------------------------


    @Test
    void blanknessNotionsAreOrderedByStrictness()
    {
        IDataValue missing = MockTable.of().colLong("K", (Long) null).build().getColumn(0)
                .getDataValue(0);
        IDataValue empty = MockTable.of().col("K", "").build().getColumn(0).getDataValue(0);
        IDataValue spaces = MockTable.of().col("K", "   ").build().getColumn(0).getDataValue(0);
        IDataValue real = MockTable.of().col("K", "x").build().getColumn(0).getDataValue(0);

        // ⭐⭐ INVERTED by W32-E3 (owner, 2026-08-12), NOT deleted. This assertion previously read
        // assertFalse(...isBlankKeyComponent(empty)) with the message
        // "\"\" must stay a real key under MISSING_ONLY — this is the pre-EC-24 contract".
        // That was true of the shipped engine and is now false BY RULING: "" is handled exactly as
        // a MissingValue, so it drops the group here just as a genuine marker does.
        // ⚑ It is inverted rather than removed because deleting it would leave the NEW contract
        // unpinned — the same assertion, opposite polarity, is what proves the ruling landed.
        assertTrue(GroupKeyPolicy.DROP_MISSING_KEYS.isBlankKeyComponent(missing));
        assertTrue(GroupKeyPolicy.DROP_MISSING_KEYS.isBlankKeyComponent(empty),
                "W32-E3: \"\" is blank under MISSING_OR_EMPTY — it drops the group, exactly as a "
                        + "MissingValue does. If this fails, part 1 of the grouping change is not "
                        + "in effect.");
        assertFalse(GroupKeyPolicy.DROP_MISSING_KEYS.isBlankKeyComponent(spaces),
                "whitespace-only is NOT blank here — that notion belongs to COALESCE_COMPONENT "
                        + "alone (EC-24 / FDA-SE2279), and this is the boundary that must not move");

        // MISSING_OR_EMPTY: the marker and "".
        assertTrue(GroupKeyPolicy.FOLD_BLANK_KEYS.isBlankKeyComponent(missing));
        assertTrue(GroupKeyPolicy.FOLD_BLANK_KEYS.isBlankKeyComponent(empty));
        assertFalse(GroupKeyPolicy.FOLD_BLANK_KEYS.isBlankKeyComponent(spaces),
                "whitespace is NOT blank under MISSING_OR_EMPTY");

        // MISSING_OR_WHITESPACE: also whitespace-only.
        assertTrue(GroupKeyPolicy.COALESCE_COMPONENT.isBlankKeyComponent(missing));
        assertTrue(GroupKeyPolicy.COALESCE_COMPONENT.isBlankKeyComponent(empty));
        assertTrue(GroupKeyPolicy.COALESCE_COMPONENT.isBlankKeyComponent(spaces));

        // A populated value is blank under none of them.
        assertFalse(GroupKeyPolicy.DROP_MISSING_KEYS.isBlankKeyComponent(real));
        assertFalse(GroupKeyPolicy.FOLD_BLANK_KEYS.isBlankKeyComponent(real));
        assertFalse(GroupKeyPolicy.COALESCE_COMPONENT.isBlankKeyComponent(real));
    }


    @ParameterizedTest
    @EnumSource(Blankness.class)
    void predicateIgnoresTheDispositionAxis(Blankness notion)
    {
        // The predicate answers "is this blank", never "what do we do about it". Keeping the two
        // axes independent is what lets ONE predicate serve both the fold and the discard.
        IDataValue missing = MockTable.of().colLong("K", (Long) null).build().getColumn(0)
                .getDataValue(0);
        assertEquals(new GroupKeyPolicy(true, notion).isBlankKeyComponent(missing),
                new GroupKeyPolicy(false, notion).isBlankKeyComponent(missing),
                "keepMissings must not change what counts as blank");
    }


    @Test
    void nullCellIsBlankUnderEveryNotion()
    {
        for (Blankness notion : Blankness.values())
        {
            assertTrue(new GroupKeyPolicy(false, notion).isBlankKeyComponent(null),
                    "a null cell must be blank under " + notion);
        }
    }

    // ------------------------------------------------------------------
    // ⚠⚠ Asymmetry 1 — ONE authoring surface, TWO behaviours
    // ------------------------------------------------------------------


    /**
     * The {@code Operations[].group:} split, pinned at the primitive level: the five key-building
     * evaluators route through {@link IndexHelper#groupByPresent} under
     * {@link GroupKeyPolicy#KEEP_MISSING_KEYS} and keep the blank-keyed row, while
     * {@code evalIsLastInGroup} routes through {@link GroupSemantics#group} under
     * {@link GroupKeyPolicy#DROP_MISSING_KEYS} and loses it.
     *
     * <p>
     * ⚠ This is a <b>defect pin, not an endorsement.</b> The asymmetry is deliberately preserved by
     * Phase 2/3 so that correcting it is a separate, attributable change; the point of the pin is
     * that it cannot be corrected <em>accidentally</em>.
     * </p>
     */
    @Test
    void groupSurfaceFoldsForFiveOperatorsAndDiscardsForIsLastInGroup()
    {
        IDataTable t = numericKeyWithMissing();

        // Site 1 (max/distinct/record_count/date-extreme/mixed-emptiness): the blank-keyed row 1
        // survives as its own "" -keyed block.
        IndexHelper.Grouping folded = IndexHelper.groupByPresent(t, List.of("K"), "test");
        assertEquals(3, folded.blocks().size(), "the fold keeps three blocks: K=1, K=missing, K=2");
        int foldedRows = folded.blocks().stream().mapToInt(b -> b.rows().length).sum();
        assertEquals(4, foldedRows, "the fold loses no row");

        // Site 7 (is_last_in_group): the same key list, the blank-keyed block dropped.
        List<int[]> discarded = GroupSemantics.partition(t, List.of("K"));
        assertEquals(2, discarded.size(), "the discard drops the missing-keyed block");
        int discardedRows = discarded.stream().mapToInt(g -> g.length).sum();
        assertEquals(3, discardedRows, "the discard loses exactly the one blank-keyed row");

        // The asymmetry itself, stated as one assertion.
        assertNotEquals(discardedRows, foldedRows,
                "site 1 and site 7 read the SAME group: on the SAME data and must still disagree —"
                        + " if this passes by equality the asymmetry was silently resolved");
    }


    /**
     * The declared parameter is what lets an author settle the asymmetry — the whole point of Phase
     * 3. Under an explicit policy both sites agree, in both directions.
     */
    @Test
    void anExplicitPolicyMakesTheTwoGroupSitesAgree()
    {
        IDataTable t = numericKeyWithMissing();

        IndexHelper.Grouping foldedByDefault = IndexHelper.groupByPresent(t, List.of("K"), "test",
                GroupKeyPolicy.KEEP_MISSING_KEYS);
        List<int[]> keptViaGroup = GroupSemantics.group(t, List.of("K"),
                GroupKeyPolicy.KEEP_MISSING_KEYS);
        assertEquals(foldedByDefault.blocks().size(), keptViaGroup.size(),
                "keep_missings: true makes is_last_in_group agree with its five siblings");

        IndexHelper.Grouping droppedOnDemand = IndexHelper.groupByPresent(t, List.of("K"), "test",
                GroupKeyPolicy.DROP_MISSING_KEYS);
        List<int[]> droppedViaGroup = GroupSemantics.group(t, List.of("K"),
                GroupKeyPolicy.DROP_MISSING_KEYS);
        assertEquals(droppedViaGroup.size(), droppedOnDemand.blocks().size(),
                "keep_missings: false makes the five siblings agree with is_last_in_group");
        assertEquals(2, droppedOnDemand.blocks().size(),
                "and the discarding direction really does drop the block");
    }

    // ------------------------------------------------------------------
    // ⚠⚠ Asymmetry 2 — TWO predicates inside ONE method
    // ------------------------------------------------------------------


    /**
     * ⭐⭐ <b>RE-POINTED by {@code W32-E3} (owner, 2026-08-12) — the asymmetry MOVED, it did not
     * vanish.</b>
     *
     * <p>
     * This test was {@code coalesceBranchAndSingletonBranchDisagreeOnEmptyString} and asserted that
     * the singleton branch keeps {@code ""} as a real key (all 3 rows survive) while the coalesce
     * branch treats it as unpopulated (row 0 drops). ⚑ <b>After the ruling the two branches AGREE
     * on {@code ""}</b> — both now call it blank — so that particular disagreement is gone <em>by
     * decision</em>.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>What survives is the whitespace boundary, and it is now the ONLY thing separating
     * {@code DROP_MISSING_KEYS} from {@code COALESCE_COMPONENT}.</b> That makes it <em>more</em>
     * fragile than before, not less: the two policies used to differ on two axes and now differ on
     * one. Collapsing them would still silently change {@code FDA-SE2279}
     * ({@code within: [[USUBJID, POOLID]]}), the one shipped rule that reaches this branch.
     * </p>
     *
     * <p>
     * ⚠ <b>The fixture is shaped so only the predicate under test can decide the outcome.</b> Every
     * column is a character column holding a literal {@code ""} or spaces, so no genuine
     * {@code MissingValue} is anywhere in the table: whatever difference the assertions see comes
     * from the blankness notion and nothing else. A fixture using a numeric missing would be
     * rejected by <em>both</em> branches and would pass whatever the code did.
     * </p>
     */
    @Test
    void coalesceBranchAndSingletonBranchNowAgreeOnEmptyButNotOnWhitespace()
    {
        // Row 0: both blank. Row 1: pool only. Row 2: subject only.
        IDataTable t = MockTable.of().col("USUBJID", "", "", "S3").col("POOLID", "", "P2", "")
                .build();

        // SINGLETON, INVERTED: "" is now blank, so the two blank-keyed rows DROP and only S3
        // survives. Pre-ruling this asserted 3 — "a singleton component keeps \"\" as a real key
        // and drops no row".
        List<int[]> singleton = GroupSemantics.partitionCoalesced(t, comps("USUBJID"));
        assertEquals(1, singleton.stream().mapToInt(g -> g.length).sum(),
                "W32-E3: a singleton component now treats \"\" as blank and drops those rows");

        // COALESCE, UNCHANGED: row 0 (blank subject AND blank pool) is still dropped; rows 1 and 2
        // still fall through to their populated column. This half did not move.
        List<int[]> coalesced = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID")));
        assertEquals(2, coalesced.stream().mapToInt(g -> g.length).sum(),
                "the coalesce branch still drops only the all-blank row");
        assertEquals(Set.of(Set.of(1), Set.of(2)), groupSet(coalesced),
                "the surviving rows key on their first populated column (P2, S3)");

        // ⭐ THE SURVIVING ASYMMETRY, and it is now the whole of the difference: a whitespace-only
        // component is blank to the coalesce branch and REAL to the singleton branch.
        IDataTable ws = MockTable.of().col("USUBJID", "   ", "S2").col("POOLID", "", "").build();
        assertEquals(2,
                GroupSemantics.partitionCoalesced(ws, comps("USUBJID")).stream()
                        .mapToInt(g -> g.length).sum(),
                "the singleton branch keeps a whitespace-only key as a REAL key — "
                        + "MISSING_OR_EMPTY stops at \"\"");
        assertEquals(1,
                GroupSemantics.partitionCoalesced(ws, comps(List.of("USUBJID", "POOLID"))).stream()
                        .mapToInt(g -> g.length).sum(),
                "the coalesce branch calls whitespace unpopulated and drops the row — "
                        + "collapsing the two predicates would silently change FDA-SE2279");
    }


    /**
     * The coalesce branch's blankness notion is fixed by the operator's meaning, not by the
     * caller's policy: "populated" must stay whitespace-aware so a space-filled {@code USUBJID}
     * still falls through to {@code POOLID}. Only the disposition for an all-unpopulated component
     * follows the caller.
     */
    @Test
    void coalesceFallThroughStaysWhitespaceAwareUnderEveryPolicy()
    {
        IDataTable t = MockTable.of().col("USUBJID", "   ").col("POOLID", "P1").build();
        for (GroupKeyPolicy policy : List.of(GroupKeyPolicy.DROP_MISSING_KEYS,
                GroupKeyPolicy.KEEP_MISSING_KEYS, GroupKeyPolicy.FOLD_BLANK_KEYS))
        {
            List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                    comps(List.of("USUBJID", "POOLID")), policy);
            assertEquals(1, groups.size(),
                    "a whitespace-only USUBJID must fall through to POOLID under " + policy);
            assertEquals(1, groups.get(0).length);
        }
    }


    @Test
    void keepMissingsFoldsTheAllUnpopulatedCoalesceComponentInsteadOfDropping()
    {
        IDataTable t = MockTable.of().col("USUBJID", "", "", "S3").col("POOLID", "", "P2", "")
                .build();
        List<int[]> kept = GroupSemantics.partitionCoalesced(t, comps(List.of("USUBJID", "POOLID")),
                GroupKeyPolicy.COALESCE_COMPONENT.withKeepMissings(true));
        assertEquals(3, kept.stream().mapToInt(g -> g.length).sum(),
                "keep_missings: true folds the all-blank component to \"\" and keeps the row —"
                        + " this is what FDA-SE2279 would opt into");
    }

    // ------------------------------------------------------------------
    // ⚑ The seventh implementation — a LOCKSTEP pair, not a duplicate
    // ------------------------------------------------------------------


    /**
     * {@code GroupedResult.buildKey} is the per-row <b>lookup</b> twin of
     * {@code IndexHelper.buildGroupKey}'s block-representative key. The plan lists six missing-key
     * implementations; this is a seventh, and it is the one whose divergence would be hardest to
     * see: if the two disagree about which cells are blank, every lookup on a blank-keyed row
     * silently misses and the operation reads "no value" rather than erroring.
     *
     * <p>
     * They are pinned to agree cell-for-cell, which is why both now ask the same predicate.
     * </p>
     */
    @Test
    void groupedResultLookupKeyAgreesWithTheBlockKeyOnABlankKey()
    {
        // ⚑ HISTORICAL, corrected 2026-09-01. This used to read "colSasMissing, NOT colLong",
        // because a colLong missing cell rendered "" — which is also what a FOLD produces — so a
        // folding and a non-folding implementation were indistinguishable through it and this
        // assertion was vacuous. That hazard is GONE: MockTable.colLong / colDouble now answer
        // MissingValue.MIS and render "." like a real numeric buffer, so either column kind is
        // safe for fold detection. colSasMissing is kept here because it is what the assertion
        // was written against, not because colLong would still be unsafe. Measured: with colLong
        // this test stayed
        // GREEN against a GroupedResult.buildKey deliberately broken to skip the fold entirely.
        // A real DataValueMissing renders "." (MissingValue.MIS), so the fold is observable.
        IDataTable t = MockTable.of().colSasMissing("K", "1", null, "1", "2")
                .col("V", "a", "b", "c", "d").build();
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(t, List.of("K"), "test");

        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            for (int row : block.rows())
            {
                assertEquals(block.key(),
                        GroupedResult.buildKey(t.getMetaData(), t, List.of("K"), row),
                        "the lookup key must reproduce the block key for EVERY row of the block,"
                                + " including the block whose key is a genuine missing");
            }
        }
    }

    // ------------------------------------------------------------------
    // Behaviour preservation: the overloads ARE the shipped defaults
    // ------------------------------------------------------------------


    /**
     * The refactor's core claim: each legacy entry point equals the new primitive under that site's
     * documented default. If any of these drifts, some call site silently changed disposition.
     */
    @Test
    void legacyEntryPointsEqualTheirDocumentedDefaults()
    {
        IDataTable t = numericKeyWithMissing();

        assertEquals(
                groupSet(GroupSemantics.group(t, List.of("K"), GroupKeyPolicy.DROP_MISSING_KEYS)),
                groupSet(GroupSemantics.partition(t, List.of("K"))),
                "partition() must be group() under DROP_MISSING_KEYS");

        assertEquals(
                groupSet(GroupSemantics.partitionCoalesced(t, comps("K"),
                        GroupKeyPolicy.DROP_MISSING_KEYS)),
                groupSet(GroupSemantics.partitionCoalesced(t, comps("K"))),
                "partitionCoalesced()'s legacy overload must be DROP_MISSING_KEYS");

        IndexHelper.Grouping legacy = IndexHelper.groupByPresent(t, List.of("K"), "test");
        IndexHelper.Grouping explicit = IndexHelper.groupByPresent(t, List.of("K"), "test",
                GroupKeyPolicy.KEEP_MISSING_KEYS);
        assertEquals(legacy.blocks().size(), explicit.blocks().size(),
                "groupByPresent()'s legacy overload must be KEEP_MISSING_KEYS");
    }


    @Test
    void withKeepMissingsPreservesTheBlanknessNotion()
    {
        // An authored keep_missings chooses the DISPOSITION of a blank, never what counts as blank.
        for (Blankness notion : Blankness.values())
        {
            GroupKeyPolicy base = new GroupKeyPolicy(false, notion);
            assertEquals(notion, base.withKeepMissings(true).blankness());
            assertTrue(base.withKeepMissings(true).keepMissings());
            assertFalse(base.withKeepMissings(false).keepMissings());
        }
    }


    @Test
    void withDeclaredIsIdentityWhenTheRuleIsSilent()
    {
        GroupKeyPolicy base = GroupKeyPolicy.DROP_MISSING_KEYS;
        assertEquals(base, base.withDeclared(null),
                "a rule that declares nothing must keep the engine default exactly");
        assertTrue(base.withDeclared(Boolean.TRUE).keepMissings());
        assertFalse(base.withDeclared(Boolean.FALSE).keepMissings());
    }

    // ------------------------------------------------------------------
    // The two FOLD sites under a declared discarding policy
    // ------------------------------------------------------------------


    /**
     * {@code is_not_unique_set} folds a blank key component by default (D.1). Under a declared
     * discarding policy the blank-keyed row leaves the uniqueness question entirely — it is neither
     * a duplicate nor a unique candidate.
     *
     * <p>
     * ⚠ The fixture uses {@code colSasMissing} so the two dispositions are distinguishable: rows 0
     * and 1 both have a blank key, so under the fold they are duplicates of each other, and under
     * the discard neither is flagged at all.
     * </p>
     */
    @Test
    void uniqueSetFoldsBlankKeysByDefaultAndDiscardsWhenDeclared()
    {
        IDataTable t = MockTable.of().colSasMissing("K", null, null, "x").build();

        BitSet folded = GroupSemantics.uniqueSetViolations(t, 3, List.of("K"), null, true,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
        assertTrue(folded.get(0) && folded.get(1),
                "the shipped fold makes the two blank-keyed rows duplicates of each other");
        assertFalse(folded.get(2), "the populated key is unique");

        BitSet discarded = GroupSemantics.uniqueSetViolations(t, 3, List.of("K"), null, true,
                GroupKeyPolicy.FOLD_BLANK_KEYS.withKeepMissings(false));
        assertTrue(discarded.isEmpty(),
                "a declared discard takes the blank-keyed rows out of the uniqueness question");
    }


    /**
     * {@code target_is_not_sorted_by} folds a blank {@code within} key by default — which is the
     * questionable behaviour for an ordering operator, because it chains rows that belong to no
     * common subject. Under a declared discard those rows form no chain at all.
     */
    @Test
    void sortedByFoldsBlankWithinKeysByDefaultAndDiscardsWhenDeclared()
    {
        // Rows 0 and 1 have a blank WITHIN key. Ordered by ORD (1, 2) the target SEQ reads 2 then 1
        // — descending — so the fold chains them and reports a sort violation that the data never
        // asserted. ⚠ The ordering column must differ from the target, or sorting by the target
        // makes it trivially sorted and the test is vacuous (measured: it was).
        IDataTable t = MockTable.of().colSasMissing("G", null, null).col("ORD", "1", "2")
                .col("SEQ", "2", "1").build();

        BitSet folded = GroupSemantics.targetIsNotSortedByViolations(t, 2, "SEQ", List.of("ORD"),
                "G");
        assertFalse(folded.isEmpty(),
                "the shipped fold chains the two blank-keyed rows and fires — the fabricated"
                        + " record chain this operator's family is exposed to");

        BitSet discarded = GroupSemantics.targetIsNotSortedByViolations(t, 2, "SEQ", List.of("ORD"),
                "G", GroupKeyPolicy.FOLD_BLANK_KEYS.withKeepMissings(false));
        assertTrue(discarded.isEmpty(),
                "a declared discard forms no chain across rows with no common key");
    }


    /**
     * {@code is_inconsistent_across_dataset} discards a blank grouping key by default; under a
     * declared keep the blank-keyed rows form their own consistency class.
     */
    @Test
    void inconsistentAcrossDatasetDiscardsByDefaultAndKeepsWhenDeclared()
    {
        // Two blank-keyed rows disagreeing on the target: invisible today, a finding when kept.
        IDataTable t = MockTable.of().colSasMissing("G", null, null).col("V", "a", "b").build();

        BitSet dropped = GroupSemantics.inconsistentAcrossDatasetViolations(t, "V", List.of("G"),
                2);
        assertTrue(dropped.isEmpty(), "the shipped default discards the missing-keyed group");

        BitSet kept = GroupSemantics.inconsistentAcrossDatasetViolations(t, "V", List.of("G"), 2,
                false, GroupKeyPolicy.KEEP_MISSING_KEYS);
        assertFalse(kept.isEmpty(),
                "keep_missings: true evaluates the stratum the default silently drops");
    }

    // ------------------------------------------------------------------
    // Distinct missing markers stay distinct keys
    // ------------------------------------------------------------------


    /**
     * A blank key component renders its <b>identity</b> in the reporting key, and a populated one
     * renders its own value — so two different populated values never collide, and the blank key is
     * its own bucket rather than being merged into a neighbouring one.
     *
     * <p>
     * ⚠ Re-pointed by {@code W38-A1} (Fix #249): the original assertion was <i>"the missing-keyed
     * block's reporting key is {@code ""}"</i>. That rendering was the last place a genuinely
     * missing key and a literal {@code ""} key still shared one name; a missing key now renders its
     * SOH-prefixed marker token ({@code KeyPart.reportingForm}), which no populated value can
     * spell.
     * </p>
     */
    @Test
    void blankKeyRendersItsIdentityAndDoesNotCollideWithAPopulatedKey()
    {
        IDataTable t = numericKeyWithMissing();
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(t, List.of("K"), "test");
        Set<String> keys = new HashSet<>();
        for (IndexHelper.GroupBlock b : grouping.blocks())
        {
            assertTrue(keys.add(b.key()), "block keys must be distinct: " + b.key());
        }
        assertTrue(keys.contains(GroupKeyPolicy.KeyPart.MISSING_MIS.reportingForm()),
                "the missing-keyed block's reporting key is the MIS marker token");
        assertFalse(keys.contains(""),
                "no block is filed under \"\" — the fold to the empty rendering is retired");
        assertTrue(keys.contains("1"), "a populated key renders its own value");
        assertTrue(keys.contains("2"));
    }

}
