package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ValueResolver#resolveWildcardValues} — the one piece of value-position resolution
 * the compiled native plan still delegates, because it must enumerate matching columns against the
 * row's driver values.
 *
 * <p>
 * Previously reached only through the legacy {@code is_(not_)contained_by} leaves; with those gone
 * the local-table and foreign-dataset branches (and both failure modes) need direct coverage.
 * </p>
 */
class WildcardValueCollectionTest
{

    private static OperandSubstitutor.Wildcard wildcard(String operand)
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse(operand);
        assertTrue(parsed instanceof OperandSubstitutor.Wildcard,
                operand + " must parse as a wildcard operand");
        return (OperandSubstitutor.Wildcard) parsed;
    }


    private static EvaluationContext ctx(IDataTable table, Map<String, IDataTable> datasets,
            Map<String, JoinLookup> joins)
    {
        return EvaluationContext.builder().table(table)
                .datasetResolver(name -> name == null ? null : datasets.get(name))
                .joinedDatasets(joins).variables(new LinkedHashMap<>()).build();
    }


    private static Map<String, IDataTable> map(Object... pairs)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            m.put((String) pairs[i], (IDataTable) pairs[i + 1]);
        }
        return m;
    }


    /** A {@link JoinLookup} that answers from a fixed column-name -> value map. */
    private static JoinLookup joinReturning(Map<String, String> byColumn)
    {
        return new JoinLookup()
        {

            @Override
            public @org.jspecify.annotations.Nullable String lookup(IDataTable primaryTable,
                    long row, String columnName)
            {
                return byColumn.get(columnName);
            }


            @Override
            public String getDatasetName()
            {
                return "ADSL";
            }
        };
    }


    @Test
    void localTableWildcardCollectsMatchingColumnValues()
    {
        IDataTable t = MockTable.of().col("TRT01PN", "1", "2").col("TRT02PN", "3", "4")
                .col("OTHER", "x", "y").build();
        EvaluationContext c = ctx(t, map(), Map.of());
        List<Object> row0 = ValueResolver.resolveWildcardValues(wildcard("TRT${*}PN"), null, c, 0);
        assertEquals(List.of("1", "3"), row0, "both TRT..PN columns match, in schema order");
        List<Object> row1 = ValueResolver.resolveWildcardValues(wildcard("TRT${*}PN"), null, c, 1);
        assertEquals(List.of("2", "4"), row1);
    }


    @Test
    void localTableWildcardSkipsBlankCells()
    {
        // A MISSING cell contributes no value, whatever the column type — and since the owner
        // ruling of 2026-09-18 that is the SETTLED contract for a character column too, not an
        // interim state. ⛔ This comment used to call the "a blank char cell contributes ''"
        // variant a pending blindness step blocked on a date_* defect; that step is retired.
        // ⚑ A stored "" is a different cell: a PRESENT value, and it does contribute "".
        IDataTable chars = MockTable.of().col("TRT01PN", "1").col("TRT02PN", (String) null).build();
        assertEquals(List.of("1"),
                ValueResolver.resolveWildcardValues(wildcard("TRT${*}PN"), null,
                        ctx(chars, map(), Map.of()), 0),
                "a blank character cell contributes no value");

        IDataTable nums = MockTable.of().col("TRT01PN", "1").colLong("TRT02PN", (Long) null)
                .build();
        assertEquals(List.of("1"),
                ValueResolver.resolveWildcardValues(wildcard("TRT${*}PN"), null,
                        ctx(nums, map(), Map.of()), 0),
                "a blank numeric cell contributes no value");
    }


    @Test
    void localTableWildcardWithNoMatchingColumnIsEmpty()
    {
        IDataTable t = MockTable.of().col("OTHER", "x").build();
        EvaluationContext c = ctx(t, map(), Map.of());
        assertEquals(List.of(),
                ValueResolver.resolveWildcardValues(wildcard("TRT${*}PN"), null, c, 0));
    }


    @Test
    void foreignDatasetWildcardPullsValuesThroughTheJoinLookup()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "S1").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("TRT01PN", "7").col("TRT02PN", "9")
                .build();
        // A join that returns the foreign column's value regardless of key.
        JoinLookup lookup = joinReturning(Map.of("TRT01PN", "7", "TRT02PN", "9"));
        EvaluationContext c = ctx(primary, map("ADSL", adsl), Map.of("ADSL", lookup));
        assertEquals(List.of("7", "9"),
                ValueResolver.resolveWildcardValues(wildcard("ADSL.TRT${*}PN"), null, c, 0));
    }


    /**
     * ⭐⭐ <b>INVERTED 2026-09-21 for {@code PLAN-joined-value-accessor} §2a.</b> An unmatched join
     * contributes the joined column's <b>type default</b>, not nothing — owner: <i>"I rule it's the
     * same kind, so it's empty string or MIS as well. So on this readong the set is {""}."</i>
     *
     * <p>
     * ⛔ This test previously asserted {@code List.of("7")} with the message <i>"an unmatched join
     * contributes nothing"</i>. That behaviour was never a semantic — it was the raw
     * {@code @Nullable} accessor's {@code null} being dropped.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Why the expected element here is {@code MIS} and not {@code ""}:</b> this fixture's
     * {@link JoinLookup} is a stub that overrides only {@code lookup}, so it inherits the
     * interface's <b>type-blind</b> default — {@code JoinLookup.lookupValue} maps a {@code null}
     * text to {@code ScalarSemantics.computedMissing()} regardless of the column's type. The
     * PRODUCTION lookup is typed and answers the column's own default, {@code ""} for a character
     * column ({@code DatasetLookup.lookupValue}, {@code D72}/{@code D72a-1}) — pinned by
     * {@link #foreignDatasetWildcardContributesTheCharacterDefaultThroughARealLookup()} below,
     * which is the assertion §2a actually rests on.
     * </p>
     */
    @Test
    void foreignDatasetWildcardContributesTheDefaultForAnUnmatchedJoin()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "S1").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("TRT01PN", "7").col("TRT02PN", "9")
                .build();
        JoinLookup lookup = joinReturning(Map.of("TRT01PN", "7"));
        EvaluationContext c = ctx(primary, map("ADSL", adsl), Map.of("ADSL", lookup));
        assertEquals(List.of("7", MissingValue.MIS),
                ValueResolver.resolveWildcardValues(wildcard("ADSL.TRT${*}PN"), null, c, 0),
                "§2a: the unmatched TRT02PN contributes a default, and it arrives as the MissingValue"
                        + " IDENTITY rather than the text '.' — a rendering would let a present '.'"
                        + " cell match it");
    }


    /**
     * ⭐ The production path, and the case §2a is really about: a <b>real</b> {@link DatasetLookup}
     * over a character column answers {@code ""} for an unmatched row, so the wildcard contributes
     * {@code ""} — which is what makes {@code TRTP not in ADSL.TRT${*}P} stop firing for a subject
     * whose own value is a stored blank.
     */
    @Test
    void foreignDatasetWildcardContributesTheCharacterDefaultThroughARealLookup()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "S9").build();
        // ADSL has no S9 row, so every primary row is unmatched.
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1").col("TRT01P", "DRUG A")
                .col("TRT02P", "DRUG B").build();
        DatasetLookup real = DatasetLookup.build("ADSL", adsl, List.of("USUBJID"));
        assertNotNull(real, "a keyed lookup over a non-null table must be built");
        EvaluationContext c = ctx(primary, map("ADSL", adsl), Map.of("ADSL", real));
        assertEquals(List.of("", ""),
                ValueResolver.resolveWildcardValues(wildcard("ADSL.TRT${*}P"), null, c, 0),
                "D72/D72a-1 + §2a: an unmatched row yields each CHARACTER column's type default, so"
                        + " the set is {\"\"} — the owner's own words. An empty list here is the"
                        + " retired drop; a MissingValue here would mean the typed lookup was"
                        + " bypassed");
    }


    @Test
    void foreignDatasetWildcardWithoutAJoinLookupRaises()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "S1").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("TRT01PN", "7").build();
        EvaluationContext c = ctx(primary, map("ADSL", adsl), Map.of());
        OperandSubstitutor.SubstitutionException e = assertThrows(
                OperandSubstitutor.SubstitutionException.class,
                () -> ValueResolver.resolveWildcardValues(wildcard("ADSL.TRT${*}PN"), null, c, 0));
        assertTrue(e.getMessage().contains("Match_Datasets"),
                "the message must point at the missing Match_Datasets declaration: "
                        + e.getMessage());
    }


    @Test
    void foreignDatasetWildcardWithAnUnresolvableDatasetRaises()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "S1").build();
        JoinLookup lookup = joinReturning(Map.of());
        // A JoinLookup is declared, but the dataset itself does not resolve.
        EvaluationContext c = ctx(primary, map(), Map.of("ADSL", lookup));
        OperandSubstitutor.SubstitutionException e = assertThrows(
                OperandSubstitutor.SubstitutionException.class,
                () -> ValueResolver.resolveWildcardValues(wildcard("ADSL.TRT${*}PN"), null, c, 0));
        assertTrue(e.getMessage().contains("could not resolve foreign dataset"), e.getMessage());
    }


    /**
     * Fix #358, site 10: a foreign wildcard over a domain that ships <b>split</b> resolves the
     * member union and returns values instead of throwing {@code SubstitutionException}. Before the
     * fix this failed at the resolve (with a JoinLookup present, the split domain still resolved to
     * {@code null}) — fixing the join alone would only have moved the failure here. Real member
     * tables + a {@code WithInventory} resolver ({@link RealTables}) — the lambda {@code ctx}
     * helper above cannot reach the union.
     */
    @Test
    void foreignWildcardOverASplitDomainResolvesTheUnion()
    {
        // ${*} matches DIGITS (\d+), so the members carry TRT01PN-shaped columns — one per
        // member, proving the enumeration runs over the UNION's column set (TRT02PN exists only
        // in lbhe).
        IDataTable primary = MockTable.of().name("ADLB").col("USUBJID", "S1").build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "S1")
                .str("TRT01PN", "7").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "S2")
                .str("TRT02PN", "9").build();
        JoinLookup lookup = joinReturning(Map.of("TRT01PN", "7", "TRT02PN", "9"));
        EvaluationContext c = EvaluationContext.builder().table(primary)
                .datasetResolver(RealTables.inventoryOf(lbch, lbhe))
                .joinedDatasets(Map.of("LB", lookup)).variables(new LinkedHashMap<>()).build();
        assertEquals(List.of("7", "9"),
                ValueResolver.resolveWildcardValues(wildcard("LB.TRT${*}PN"), null, c, 0),
                "the union's column set must drive the wildcard enumeration");
    }


    @Test
    void aCachedPatternIsUsedInsteadOfRecompiling()
    {
        IDataTable t = MockTable.of().col("TRT01PN", "1").col("ZZZ", "2").build();
        EvaluationContext c = ctx(t, map(), Map.of());
        // Deliberately pass a pattern that matches a DIFFERENT column than the operand would:
        // if the cached pattern is honoured, the ZZZ value comes back.
        java.util.regex.Pattern cached = java.util.regex.Pattern.compile("ZZZ");
        assertEquals(List.of("2"),
                ValueResolver.resolveWildcardValues(wildcard("TRT${*}PN"), cached, c, 0),
                "the pre-compiled pattern must win over re-deriving it from the operand");
    }
}
