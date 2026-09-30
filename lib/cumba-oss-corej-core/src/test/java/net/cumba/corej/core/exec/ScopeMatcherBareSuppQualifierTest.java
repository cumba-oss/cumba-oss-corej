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
import net.cumba.datatable.testkit.MockTable;
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
        return MockTable.of().name("AE").col("USUBJID", "S1").col("AESEQ", "1").build();
    }


    /** SUPPAE delivering AETRTEM as a QNAM row. */
    private static IDataTable suppae()
    {
        return MockTable.of().name("SUPPAE").col("USUBJID", "S1").col("IDVAR", "AESEQ")
                .col("IDVARVAL", "1").col("QNAM", "AETRTEM").col("QVAL", "Y").build();
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
