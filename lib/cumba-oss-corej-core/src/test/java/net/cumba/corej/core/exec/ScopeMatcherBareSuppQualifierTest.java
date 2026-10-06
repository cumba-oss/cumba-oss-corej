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
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * W2a (C1 ruled (a)) across every <b>bare</b> {@code Requirements.Variables} facet: a variable the
 * primary's {@code SUPP<domain>} delivers as a {@code QNAM} row is <em>present</em> for the
 * existence surface — {@code var_exists("AETRTEM")} answers {@code true} through the pivot, so
 * {@code All}, {@code All_Or_None} and {@code None} must read it the same way.
 *
 * <p>
 * Combined review W2 M4: only the bare {@code All} arm asked
 * {@link ScopeVariableSource#localQualifier}; {@code None: [AETRTEM]} did not exclude AE when
 * SUPPAE delivered the qualifier, and {@code All_Or_None} counted it absent. The {@code All} pins
 * below are the already-correct control the other two facets are held to.
 * </p>
 */
class ScopeMatcherBareSuppQualifierTest
{

    private static Rule rule(VariableRequirement vars)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-BARE-SUPP-QUALIFIER");
        rule.setCore(core);
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    private static Rule none(String... entries)
    {
        VariableRequirement vars = new VariableRequirement();
        vars.setNone(List.of(entries));
        return rule(vars);
    }


    private static Rule all(String... entries)
    {
        VariableRequirement vars = new VariableRequirement();
        vars.setAll(List.of(entries));
        return rule(vars);
    }


    private static Rule allOrNone(String... group)
    {
        VariableRequirement vars = new VariableRequirement();
        vars.setAllOrNoneGroups(List.of(List.of(group)));
        return rule(vars);
    }


    /** Primary AE WITHOUT an AETRTEM column. */
    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1").str("AESEQ", "1").build();
    }


    /** SUPPAE delivering AETRTEM as a QNAM row. */
    private static IDataTable suppae()
    {
        return RealTables.of("SUPPAE").str("USUBJID", "S1").str("IDVAR", "AESEQ")
                .str("IDVARVAL", "1").str("QNAM", "AETRTEM").str("QVAL", "Y").build();
    }


    private static DatasetResolver.WithInventory inventory(IDataTable... tables)
    {
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            byName.put(t.getMetaData().getName(), t);
        }
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


    private static @Nullable String check(Rule rule, boolean suppMerge, IDataTable primary,
            IDataTable... others)
    {
        IDataTable[] tables = new IDataTable[others.length + 1];
        tables[0] = primary;
        System.arraycopy(others, 0, tables, 1, others.length);
        ScopeVariableSource src = ScopeVariableSource.of(inventory(tables), primary, suppMerge);
        assertNotNull(src, "an inventory-capable resolver must yield a source");
        return ScopeMatcherCalls.describeVariablesMismatch(rule, primary.getMetaData(), "AE", src);
    }


    @Test
    @DisplayName("control — All: [AETRTEM] is satisfied by the SUPPAE qualifier")
    void allSeesTheQualifier()
    {
        assertNull(check(all("AETRTEM"), true, ae(), suppae()));
        assertNotNull(check(all("AETRTEM"), true, ae()), "no SUPPAE: AETRTEM is absent");
    }


    @Test
    @DisplayName("⭐ None: [AETRTEM] excludes AE when SUPPAE delivers the qualifier")
    void noneExcludesTheQualifier()
    {
        String reason = check(none("AETRTEM"), true, ae(), suppae());
        assertEquals("Requirements.Variables.None variable AETRTEM present as a supplemental"
                + " qualifier of the dataset", reason);
    }


    @Test
    @DisplayName("None: [AETRTEM] excludes nothing without SUPPAE, or with Supp_Merge off")
    void noneWithoutTheQualifierExcludesNothing()
    {
        assertNull(check(none("AETRTEM"), true, ae()), "no SUPPAE: nothing delivers AETRTEM");
        assertNull(check(none("AETRTEM"), false, ae(), suppae()),
                "Supp_Merge off: the pivot answers false for every probe (§2.3)");
    }


    @Test
    @DisplayName("⭐ All_Or_None [AETRTEM, AESEQ] counts the qualifier present: the rule runs")
    void allOrNoneCountsTheQualifierPresent()
    {
        assertNull(check(allOrNone("AETRTEM", "AESEQ"), true, ae(), suppae()),
                "AETRTEM (via SUPPAE) and AESEQ are both present — the whole group is");
    }


    /** The split family: SUPPAE1 + SUPPAE2 (RDOMAIN AE), no SUPPAE — one qualifier each. */
    private static IDataTable suppae(String name, String subject, String qnam)
    {
        return RealTables.of(name).str("RDOMAIN", "AE").str("USUBJID", subject)
                .str("IDVAR", "AESEQ").str("IDVARVAL", "1").str("QNAM", qnam).str("QVAL", "Y")
                .build();
    }


    /**
     * {@code PLAN-split-supp-resolution} PG-H1 / C5: the bare facets read the primary's SUPP
     * sources — the one chooser's members — so a split family delivers its qualifiers to {@code AE}
     * (SUPPAE1 ∪ SUPPAE2), and a split member reads exactly its own SUPP.
     */
    @Test
    @DisplayName("⭐ the bare facets see a split SUPP family's qualifiers")
    void theBareFacetsSeeASplitFamily()
    {
        IDataTable s1 = suppae("SUPPAE1", "S1", "AETRTEM");
        IDataTable s2 = suppae("SUPPAE2", "S2", "AESOSP");
        assertNull(check(all("AETRTEM"), true, ae(), s1, s2), "SUPPAE1 delivers it");
        assertNull(check(all("AESOSP"), true, ae(), s1, s2), "SUPPAE2 delivers it");
        assertNotNull(check(none("AESOSP"), true, ae(), s1, s2), "None excludes it");
        assertNull(check(allOrNone("AETRTEM", "AESOSP", "AESEQ"), true, ae(), s1, s2),
                "every member of the group is present");
        assertNotNull(check(all("AETRTEM"), false, ae(), s1, s2), "Supp_Merge off: absent");
        // a split member reads its own SUPP: LBCH ← SUPPLBCH, never SUPPLBHE (D12)
        IDataTable lbch = RealTables.of("LBCH").str("DOMAIN", "LB").str("USUBJID", "S1")
                .str("LBSEQ", "1").build();
        IDataTable supplbch = RealTables.of("SUPPLBCH").str("RDOMAIN", "LB").str("USUBJID", "S1")
                .str("IDVAR", "LBSEQ").str("IDVARVAL", "1").str("QNAM", "LBCHX").str("QVAL", "a")
                .build();
        IDataTable supplbhe = RealTables.of("SUPPLBHE").str("RDOMAIN", "LB").str("USUBJID", "S1")
                .str("IDVAR", "LBSEQ").str("IDVARVAL", "1").str("QNAM", "LBHEX").str("QVAL", "b")
                .build();
        IDataTable lbhe = RealTables.of("LBHE").str("DOMAIN", "LB").str("USUBJID", "S1")
                .str("LBSEQ", "2").build();
        ScopeVariableSource src = ScopeVariableSource
                .of(RealTables.inventoryOf(lbch, lbhe, supplbch, supplbhe), lbch, true);
        assertNotNull(src);
        assertNull(ScopeMatcherCalls.describeVariablesMismatch(all("LBCHX"), lbch.getMetaData(),
                "LB", src), "its own SUPP's qualifier");
        assertNotNull(ScopeMatcherCalls.describeVariablesMismatch(all("LBHEX"), lbch.getMetaData(),
                "LB", src), "a sibling's qualifier is the sibling's");
    }


    /**
     * {@code PLAN-split-supp-resolution} confirmation look side (a): the two-character leg of a
     * qualified entry walks the inventory fault-tolerantly — an unreadable UNRELATED dataset does
     * not throw through the gate; the facet decides on the members.
     */
    @Test
    @DisplayName("a throwing unrelated dataset beside a split-LB qualified entry: the facet decides")
    void aThrowingUnrelatedDatasetDoesNotThrowThroughTheGate()
    {
        IDataTable lbch = RealTables.of("LBCH").str("DOMAIN", "LB").str("LBORRES", "r").build();
        IDataTable lbhe = RealTables.of("LBHE").str("DOMAIN", "LB").str("LBORRES", "r").build();
        Map<String, IDataTable> tables = new LinkedHashMap<>();
        tables.put("AE", ae());
        tables.put("LBCH", lbch);
        tables.put("LBHE", lbhe);
        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                if ("BAD".equals(name))
                {
                    throw new IllegalStateException("corrupt file");
                }
                return name == null ? null : tables.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                Set<String> all = new java.util.LinkedHashSet<>(tables.keySet());
                all.add("BAD");
                return all;
            }
        };
        ScopeVariableSource src = ScopeVariableSource.of(inv, ae(), true);
        assertNotNull(src);
        assertNull(ScopeMatcherCalls.describeVariablesMismatch(all("LB.LBORRES"),
                ae().getMetaData(), "AE", src),
                "the split members carry it — decided, no exception");
        assertNotNull(ScopeMatcherCalls.describeVariablesMismatch(all("LB.NOSUCH"),
                ae().getMetaData(), "AE", src), "and an absent column is absent");
    }


    @Test
    @DisplayName("All_Or_None without the qualifier is partly present: SKIP naming both halves")
    void allOrNoneWithoutTheQualifierIsPartlyPresent()
    {
        String reason = check(allOrNone("AETRTEM", "AESEQ"), true, ae());
        assertNotNull(reason);
        assertTrue(reason.contains("present: [AESEQ], absent: [AETRTEM]"), reason);
        String off = check(allOrNone("AETRTEM", "AESEQ"), false, ae(), suppae());
        assertNotNull(off, "Supp_Merge off: AETRTEM is absent again");
        assertTrue(off.contains("absent: [AETRTEM]"), off);
    }
}
