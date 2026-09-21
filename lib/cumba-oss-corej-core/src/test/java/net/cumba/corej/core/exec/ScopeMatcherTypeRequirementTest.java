package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code :N} / {@code :C} type suffix on a {@code Requirements.Variables} entry
 * ({@code plans/PLAN-variable-type-requirements.md}).
 *
 * <p>
 * The rulings under test, by name, so a reader of a failure knows which decision broke: <b>D2</b>
 * an undecidable type never blocks; <b>D5</b> an untagged entry is byte-for-byte what it was;
 * <b>D6</b> the type is the DATASET's; <b>D8</b> a qualified entry takes the qualifier's dataset, a
 * split domain the agreed member type; <b>D9</b> a SUPP-QNAM delivery takes that SUPP table's
 * {@code QVAL} type; <b>M7</b> a group unmet by type does not report absence.
 * </p>
 */
class ScopeMatcherTypeRequirementTest
{

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Rule ruleAll(String... entries)
    {
        return rule(List.of(entries), null);
    }


    private static Rule rule(@Nullable List<String> all, @Nullable List<List<String>> anyGroups)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-TYPE-REQ");
        rule.setCore(core);
        VariableRequirement vars = new VariableRequirement();
        vars.setAll(all);
        vars.setAnyGroups(anyGroups);
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    /** AE with AESEQ numeric, AETERM character — the two decidable kinds side by side. */
    private static DataTableMeta ae()
    {
        return MockTable.of().name("AE").col("AETERM", "headache").colDouble("AESEQ", 1.0).build()
                .getMetaData();
    }


    /**
     * A meta built by hand, so a type {@code ColumnTypeGate.kindOf} does not classify is reachable.
     */
    private static DataTableMeta metaWithTypes(String name, Map<String, DataValueType> columns)
    {
        List<DataTableColumnMeta> cols = new java.util.ArrayList<>();
        int i = 0;
        for (Map.Entry<String, DataValueType> e : columns.entrySet())
        {
            cols.add(DataTableColumnMeta.builder().index(i++).name(e.getKey()).label(e.getKey())
                    .type(e.getValue()).build());
        }
        return DataTableMeta.builder().name(name).setColumns(cols).rowCount(1L).totalRowCount(1L)
                .build();
    }


    private static Map<String, DataValueType> cols(Object... pairs)
    {
        Map<String, DataValueType> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            m.put((String) pairs[i], (DataValueType) pairs[i + 1]);
        }
        return m;
    }


    private static @Nullable String describe(Rule rule, DataTableMeta meta)
    {
        return ScopeMatcher.describeVariablesMismatch(rule, meta);
    }

    // ------------------------------------------------------------------
    // All — the literal arm
    // ------------------------------------------------------------------


    @Test
    void aNumericColumnSatisfiesANumericRequirement()
    {
        assertNull(describe(ruleAll("AESEQ:N"), ae()));
        assertNull(describe(ruleAll("AETERM:Char"), ae()));
    }


    @Test
    void aCharacterColumnFailsANumericRequirementAndSaysBothTypes()
    {
        String reason = describe(ruleAll("AETERM:N"), ae());
        assertNotNull(reason);
        assertEquals("Requirements.Variables.All variable AETERM:N is required to be Numeric"
                + " but is Character", reason);
    }


    /** Presence is decided FIRST: an absent column reports absence, never a type. */
    @Test
    void anAbsentColumnStillReportsAbsence()
    {
        String reason = describe(ruleAll("NOSUCH:N"), ae());
        assertNotNull(reason);
        assertTrue(reason.contains("not present in dataset"), reason);
        assertTrue(!reason.contains("required to be"), reason);
    }


    /**
     * ⛔ Ruling D2. {@code BOOLEAN} is one of the types {@code ColumnTypeGate.kindOf} deliberately
     * does not classify, so the requirement is not decidable and must not block — in EITHER
     * direction, which is what makes it "undecidable" rather than "character".
     *
     * <p>
     * ⚠ This fixture is hand-built on purpose: §5.3 of the plan measured that {@code OTHER} /
     * {@code BOOLEAN} reach only from the parquet and rds providers, so no dataset in the shipped
     * test study can exercise this arm.
     * </p>
     */
    @Test
    void anUndecidableTypeBlocksNeitherDirection()
    {
        DataTableMeta meta = metaWithTypes("AE", cols("FLAG", DataValueType.BOOLEAN));
        assertNull(describe(ruleAll("FLAG:N"), meta), "BOOLEAN is not decidably numeric");
        assertNull(describe(ruleAll("FLAG:C"), meta), "nor decidably character");
    }


    /**
     * ⭐⭐ Ruling D5, the plan's load-bearing acceptance criterion: an entry with no tag produces the
     * EXACT message it produced before the feature existed. Asserted as a literal, because a
     * containment check would pass over a changed prefix.
     */
    @Test
    void anUntaggedEntryProducesTheExactPreFeatureMessage()
    {
        assertEquals("Requirements.Variables.All variable NOSUCH not present in dataset",
                describe(ruleAll("NOSUCH"), ae()));
        assertNull(describe(ruleAll("AESEQ"), ae()), "a present column still satisfies");
        assertNull(describe(ruleAll("AETERM"), ae()), "whatever its type");
    }


    /**
     * M10: the message shows the entry AS AUTHORED, tag included — never a silently stripped one.
     */
    @Test
    void theMessagePrintsTheEntryAsAuthored()
    {
        assertTrue(describe(ruleAll("NOSUCH:Num"), ae()).contains("NOSUCH:Num"));
    }

    // ------------------------------------------------------------------
    // All — the pattern arm
    // ------------------------------------------------------------------

    @Nested
    class PatternEntries
    {

        /**
         * ⛔ The trap the plan names: a dataset carrying BOTH {@code AEORRES} (Char) and
         * {@code AEORRESN} (Num) satisfies a numeric pattern requirement through the second.
         * Answering about the FIRST name match alone would skip the rule.
         */
        @Test
        void aLaterMatchingColumnOfTheRightTypeSatisfies()
        {
            DataTableMeta meta = MockTable.of().name("AE").col("AEORRES", "5")
                    .colDouble("AEORRESN", 5.0).build().getMetaData();
            assertNull(describe(ruleAll("/^AEORRES.?$/:N"), meta));
        }


        @Test
        void aNameMatchOfTheWrongTypeIsReportedApartFromAbsence()
        {
            DataTableMeta meta = MockTable.of().name("AE").col("AEORRES", "5").build()
                    .getMetaData();
            String reason = describe(ruleAll("/^AEORRES.?$/:N"), meta);
            assertNotNull(reason);
            assertTrue(reason.contains("matches the name but is Character"), reason);

            DataTableMeta none = MockTable.of().name("AE").col("USUBJID", "S1").build()
                    .getMetaData();
            String absent = describe(ruleAll("/^AEORRES.?$/:N"), none);
            assertNotNull(absent);
            assertTrue(absent.endsWith("present in dataset"), absent);
        }
    }

    // ------------------------------------------------------------------
    // Any — the group legs
    // ------------------------------------------------------------------


    @Nested
    class AnyGroups
    {

        /**
         * The short-circuit trap's TYPE axis: the group's FIRST entry is present but the wrong
         * type, so the group must still be satisfied by the second. A fixture that only ever
         * spoiled the second entry could not tell a correct implementation from one that answers on
         * entry 1.
         */
        @Test
        void aWrongTypedFirstEntryDoesNotDecideTheGroup()
        {
            DataTableMeta meta = MockTable.of().name("AE").col("AESEV", "MILD")
                    .colDouble("AESTDY", 3.0).build().getMetaData();
            assertNull(describe(rule(null, List.of(List.of("AESEV:N", "AESTDY:N"))), meta));
        }


        /** ⚠ M7: every entry present but wrongly typed must NOT be reported as absence. */
        @Test
        void aGroupUnmetByTypeDoesNotClaimAbsence()
        {
            DataTableMeta meta = MockTable.of().name("AE").col("AESEV", "MILD")
                    .col("AEOUT", "RECOVERED").build().getMetaData();
            String reason = describe(rule(null, List.of(List.of("AESEV:N", "AEOUT:N"))), meta);
            assertNotNull(reason);
            assertTrue(reason.contains("is of the required type"), reason);
            assertTrue(!reason.endsWith("present in dataset"),
                    "a present column must never be described as absent: " + reason);
        }


        /**
         * ⛔⛔ Review round 1, finding 1. The M7 arm first detected a type mismatch by testing the
         * reason for the substring {@code " is required to be "} — and only TWO of the four
         * mismatch messages carry it. The two PATTERN arms say
         * {@code "<col> matches the name but is Character"} instead, so a group of pattern entries,
         * all present by NAME and all of the wrong type, fell straight through to the absence
         * wording M7 exists to suppress. Both columns below are visible in the dataset.
         */
        @Test
        void aGroupOfPatternEntriesUnmetByTypeAlsoDoesNotClaimAbsence()
        {
            DataTableMeta meta = MockTable.of().name("AE").col("AEORRES", "5")
                    .col("AEDECOD", "HEADACHE").build().getMetaData();
            String reason = describe(
                    rule(null, List.of(List.of("/^AEORRES.?$/:N", "/^AEDECOD$/:N"))), meta);
            assertNotNull(reason);
            assertTrue(reason.contains("is of the required type"), reason);
            assertTrue(!reason.endsWith("present in dataset"),
                    "both columns ARE present — the reader can see them: " + reason);
        }


        /**
         * ⛔ Round 2, finding 4: this test's first version paired a literal {@code AESEV:N} with a
         * pattern entry — and could not fail for finding 1. The literal's message carries
         * {@code " is required to be "}, so the OLD substring detector fired on iteration 1 and the
         * assertion passed against the unfixed code. A mixed group can never discriminate while any
         * literal entry rescues the detector.
         *
         * <p>
         * So the literal here is <b>absent</b> rather than wrongly typed: its message is an absence
         * one, carrying no phrase, and the only type verdict in the group comes from the PATTERN
         * arm. Under the old detector {@code wrongType} stays null and the group falls through to
         * the absence wording — which is what this asserts against.
         * </p>
         */
        @Test
        void aPatternEntryIsTheOnlyTypeVerdictInAMixedGroup()
        {
            DataTableMeta meta = MockTable.of().name("AE").col("AEORRES", "5").build()
                    .getMetaData();
            String reason = describe(rule(null, List.of(List.of("NOSUCH:N", "/^AEORRES.?$/:N"))),
                    meta);
            assertNotNull(reason);
            assertTrue(reason.contains("is of the required type"),
                    "the pattern arm's verdict must reach the group message: " + reason);
        }


        /**
         * The second axis: group 1 satisfied, group 2 unmet by type — the message names group 2.
         */
        @Test
        void theUnmetGroupIsNamedByItsOwnIndex()
        {
            DataTableMeta meta = MockTable.of().name("AE").colDouble("AESTDY", 1.0)
                    .col("AESEV", "MILD").col("AEOUT", "RECOVERED").build().getMetaData();
            String reason = describe(
                    rule(null,
                            List.of(List.of("AESTDY:N", "NOPE:N"), List.of("AESEV:N", "AEOUT:N"))),
                    meta);
            assertNotNull(reason);
            assertTrue(reason.contains("group 2"), reason);
        }
    }

    // ------------------------------------------------------------------
    // D8 / D9 — qualified entries
    // ------------------------------------------------------------------


    @Nested
    class QualifiedEntries
    {

        private DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
        {
            return new DatasetResolver.WithInventory()
            {

                @Override
                public @Nullable IDataTable resolve(String name)
                {
                    return name == null ? null : byName.get(name);
                }


                @Override
                public Set<String> availableDatasets()
                {
                    return byName.keySet();
                }
            };
        }


        private @Nullable String describeWith(Rule rule, Map<String, IDataTable> byName)
        {
            IDataTable primary = MockTable.of().name("AE").col("USUBJID", "S1").build();
            ScopeVariableSource src = ScopeVariableSource.of(inventory(byName), primary);
            assertNotNull(src);
            return ScopeMatcher.describeVariablesMismatch(rule, primary.getMetaData(), null, src,
                    ScopeMatcher.QualifiedEntryPolicy.SKIP);
        }


        private Map<String, IDataTable> map(Object... pairs)
        {
            Map<String, IDataTable> m = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2)
            {
                m.put((String) pairs[i], (IDataTable) pairs[i + 1]);
            }
            return m;
        }


        @Test
        void theTypeComesFromTheQualifiersDataset()
        {
            IDataTable dm = MockTable.of().name("DM").col("ARM", "A").colDouble("AGE", 42.0)
                    .build();
            assertNull(describeWith(ruleAll("DM.ARM:C"), map("DM", dm)));
            assertNull(describeWith(ruleAll("DM.AGE:N"), map("DM", dm)));
            String reason = describeWith(ruleAll("DM.AGE:C"), map("DM", dm));
            assertNotNull(reason);
            assertTrue(reason.contains("required to be Character but is Numeric"), reason);
            assertTrue(reason.contains("in dataset DM"), reason);
        }


        /**
         * ⭐ Ruling D8, the agreeing half: a split domain whose members declare the column
         * identically is decided by that type — the same answer {@code UnionDataTable} would give.
         */
        @Test
        void agreeingSplitMembersDecideTheType()
        {
            IDataTable lb1 = MockTable.of().name("lbch").col("DOMAIN", "LB")
                    .colDouble("LBSTRESN", 1.0).build();
            IDataTable lb2 = MockTable.of().name("lbhe").col("DOMAIN", "LB")
                    .colDouble("LBSTRESN", 2.0).build();
            assertNull(describeWith(ruleAll("LB.LBSTRESN:N"), map("lbch", lb1, "lbhe", lb2)));
            String reason = describeWith(ruleAll("LB.LBSTRESN:C"), map("lbch", lb1, "lbhe", lb2));
            assertNotNull(reason);
            assertTrue(reason.contains("required to be Character but is Numeric"), reason);
        }


        /**
         * ⛔⛔ Ruling D8, the disagreeing half — and the reason it is NOT a skip. The members declare
         * the column differently, so {@code UnionDataTable} would refuse to union them and the rule
         * errors with {@code InvalidJoinedDomainException}. The requirement gate runs BEFORE that,
         * so a skip here would MASK a submission defect the engine deliberately surfaces. ⇒
         * undecidable, blocks neither direction.
         */
        @Test
        void disagreeingSplitMembersBlockNeitherDirection()
        {
            IDataTable lb1 = MockTable.of().name("lbch").col("DOMAIN", "LB")
                    .colDouble("LBSTRESC", 1.0).build();
            IDataTable lb2 = MockTable.of().name("lbhe").col("DOMAIN", "LB").col("LBSTRESC", "POS")
                    .build();
            Map<String, IDataTable> split = map("lbch", lb1, "lbhe", lb2);
            assertNull(describeWith(ruleAll("LB.LBSTRESC:N"), split),
                    "a clash is undecidable, not a skip — the ERROR is the engine's to raise");
            assertNull(describeWith(ruleAll("LB.LBSTRESC:C"), split));
        }


        /**
         * ⭐ Ruling D9: AE has no AETRTEM column, but SUPPAE delivers it as a QNAM row. Its values
         * come through QVAL, so QVAL's type decides — {@code :C} passes, {@code :N} does not.
         */
        @Test
        void aSuppQnamDeliveryTakesTheQvalType()
        {
            IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
            IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM")
                    .col("QVAL", "Y").build();
            Map<String, IDataTable> study = map("AE", ae, "SUPPAE", suppae);
            assertNull(describeWith(ruleAll("AE.AETRTEM"), study), "untagged: presence is enough");
            assertNull(describeWith(ruleAll("AE.AETRTEM:C"), study), "QVAL is character");
            String reason = describeWith(ruleAll("AE.AETRTEM:N"), study);
            assertNotNull(reason);
            assertTrue(reason.contains("delivered as a supplemental qualifier"), reason);
        }


        /** D9's edge: no QVAL column at all means no type, which is D2's undecidable. */
        @Test
        void aSuppTableWithoutQvalIsUndecidable()
        {
            IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
            IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM").build();
            Map<String, IDataTable> study = map("AE", ae, "SUPPAE", suppae);
            assertNull(describeWith(ruleAll("AE.AETRTEM:N"), study));
            assertNull(describeWith(ruleAll("AE.AETRTEM:C"), study));
        }
    }

}
