package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.cumba.cdisc.define.Alias;
import net.cumba.cdisc.define.CheckValue;
import net.cumba.cdisc.define.CodeList;
import net.cumba.cdisc.define.CodeListItem;
import net.cumba.cdisc.define.CodeListRef;
import net.cumba.cdisc.define.ItemDef;
import net.cumba.cdisc.define.ItemGroupDef;
import net.cumba.cdisc.define.ItemRef;
import net.cumba.cdisc.define.MetaDataVersion;
import net.cumba.cdisc.define.RangeCheck;
import net.cumba.cdisc.define.ValueListDef;
import net.cumba.cdisc.define.ValueListRef;
import net.cumba.cdisc.define.WhereClauseDef;
import net.cumba.cdisc.define.WhereClauseRef;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VlmResolver}: single- and compound-WhereClause matching (AND semantics),
 * comparator coverage, first-ItemRef-by-OrderNumber selection, no-match/null propagation, and
 * structural-integrity warnings. The Define-XML model is built programmatically so the test is
 * independent of any fixture file.
 */
class VlmResolverTest
{

    private static CheckValue cv(String value)
    {
        CheckValue c = new CheckValue();
        c.setValue(value);
        return c;
    }


    /** Reads a value map as the resolver's per-row cell accessor. */
    private static Function<String, String> row(Map<String, String> cells)
    {
        return cells::get;
    }


    /**
     * A small LB define: LBSTRESC has a ValueListDef with two conditions — a simple one (LBTESTCD
     * EQ GLUC ⇒ float/len3/codelist) and a compound one (LBTESTCD IN (SODIUM,POTASSIUM) AND LBSPEC
     * EQ URINE ⇒ text).
     */
    private static MetaDataVersion sampleMdv()
    {
        // Condition variables.
        ItemDef lbtestcd = ItemDef.builder().oid("IT.LBTESTCD").name("LBTESTCD").dataType("text")
                .build();
        ItemDef lbspec = ItemDef.builder().oid("IT.LBSPEC").name("LBSPEC").dataType("text").build();
        // The parent variable, pointing at its ValueListDef.
        ItemDef lbstresc = ItemDef.builder().oid("IT.LBSTRESC").name("LBSTRESC").dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.LBSTRESC").build()).build();
        // Value-level ItemDefs (the per-condition metadata).
        CodeList glucCl = CodeList.builder().oid("CL.GLUCU")
                .codeListItems(List.of(
                        CodeListItem.builder().codedValue("mg/dL")
                                .aliases(List.of(Alias.builder().context("nci:ExtCodeID").name("C1")
                                        .build()))
                                .build(),
                        CodeListItem.builder().codedValue("mmol/L").build()))
                .aliases(List.of(Alias.builder().context("nci:ExtCodeID").name("C67").build()))
                .build();
        ItemDef vlGluc = ItemDef.builder().oid("IT.LBSTRESC.GLUC").name("SET_GLUC")
                .dataType("float").length(3)
                .codeListRef(CodeListRef.builder().codeListOID("CL.GLUCU").build()).build();
        ItemDef vlNak = ItemDef.builder().oid("IT.LBSTRESC.NAK").name("SET_NAK").dataType("text")
                .length(8).build();

        WhereClauseDef wcGluc = WhereClauseDef.builder().oid("WC.GLUC")
                .rangeChecks(List.of(RangeCheck.builder().comparator("EQ").itemOID("IT.LBTESTCD")
                        .checkValues(List.of(cv("GLUC"))).build()))
                .build();
        // Compound: LBTESTCD IN (SODIUM, POTASSIUM) AND LBSPEC EQ URINE.
        WhereClauseDef wcNak = WhereClauseDef.builder().oid("WC.NAK")
                .rangeChecks(List.of(
                        RangeCheck.builder().comparator("IN").itemOID("IT.LBTESTCD")
                                .checkValues(List.of(cv("SODIUM"), cv("POTASSIUM"))).build(),
                        RangeCheck.builder().comparator("EQ").itemOID("IT.LBSPEC")
                                .checkValues(List.of(cv("URINE"))).build()))
                .build();

        ValueListDef vl = ValueListDef.builder().oid("VL.LBSTRESC")
                .itemRefs(List.of(
                        ItemRef.builder().itemOID("IT.LBSTRESC.GLUC").orderNumber(1)
                                .mandatory("Yes")
                                .whereClauseRefs(List.of(
                                        WhereClauseRef.builder().whereClauseOID("WC.GLUC").build()))
                                .build(),
                        ItemRef.builder().itemOID("IT.LBSTRESC.NAK").orderNumber(2).mandatory("No")
                                .whereClauseRefs(List.of(
                                        WhereClauseRef.builder().whereClauseOID("WC.NAK").build()))
                                .build()))
                .build();

        ItemGroupDef lb = ItemGroupDef.builder().oid("IG.LB").name("LB").domain("LB")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.LBSTRESC").build(),
                        ItemRef.builder().itemOID("IT.LBTESTCD").build(),
                        ItemRef.builder().itemOID("IT.LBSPEC").build()))
                .build();

        return MetaDataVersion.builder().itemGroupDefs(List.of(lb))
                .itemDefs(List.of(lbstresc, lbtestcd, lbspec, vlGluc, vlNak))
                .valueListDefs(List.of(vl)).whereClauseDefs(List.of(wcGluc, wcNak))
                .codeLists(List.of(glucCl)).build();
    }


    @Test
    void resolvesSimpleWhereClauseAndSurfacesItemDefMetadata()
    {
        VlmResolver r = VlmResolver.from(sampleMdv());
        assertNotNull(r);
        VlmResolver.VlmMatch m = r.resolve("LB", "LBSTRESC", row(Map.of("LBTESTCD", "GLUC")));
        assertNotNull(m, "LBTESTCD=GLUC must match the GLUC value-level ItemDef");
        assertEquals("float", m.dataType());
        assertEquals(Integer.valueOf(3), m.length());
        assertEquals("Yes", m.mandatory());
        assertEquals(List.of("mg/dL", "mmol/L"), m.codedValues());
        assertEquals("C67", m.codelistCCode());
    }


    @Test
    void compoundWhereClauseRequiresAllRangeChecks()
    {
        VlmResolver r = VlmResolver.from(sampleMdv());
        assertNotNull(r);
        // Both predicates hold -> match the NAK condition.
        VlmResolver.VlmMatch hit = r.resolve("LB", "LBSTRESC",
                row(Map.of("LBTESTCD", "SODIUM", "LBSPEC", "URINE")));
        assertNotNull(hit);
        assertEquals("text", hit.dataType());
        assertEquals(Integer.valueOf(8), hit.length());
        // First predicate holds but the second (LBSPEC=URINE) does not -> NO match (parity guard:
        // Python previously ignored the second RangeCheck; both engines now AND all).
        assertNull(
                r.resolve("LB", "LBSTRESC", row(Map.of("LBTESTCD", "SODIUM", "LBSPEC", "BLOOD"))),
                "compound WhereClause must require every RangeCheck");
    }


    @Test
    void unmatchedRowYieldsNull()
    {
        VlmResolver r = VlmResolver.from(sampleMdv());
        assertNotNull(r);
        assertNull(r.resolve("LB", "LBSTRESC", row(Map.of("LBTESTCD", "HGB"))));
        assertNull(r.resolve("LB", "LBSTRESC", row(Map.of())),
                "missing condition column -> no match");
        assertNull(r.resolve("LB", "AGE", row(Map.of("LBTESTCD", "GLUC"))), "variable with no VLM");
        assertNull(r.resolve("XX", "LBSTRESC", row(Map.of("LBTESTCD", "GLUC"))), "unknown domain");
    }


    @Test
    void nullResolverWhenNoValueLevelMetadata()
    {
        MetaDataVersion empty = MetaDataVersion.builder().itemGroupDefs(List.of())
                .itemDefs(List.of()).build();
        assertNull(VlmResolver.from(empty), "no ValueListDef -> null resolver -> VLM rules SKIP");
        assertNull(VlmResolver.from(null));
    }


    @Test
    void firstItemRefByOrderNumberWinsOnOverlap()
    {
        // Two ItemRefs whose where-clauses both match LBTESTCD=X; OrderNumber picks the first.
        WhereClauseDef wc = WhereClauseDef.builder().oid("WC.X").rangeChecks(List.of(RangeCheck
                .builder().comparator("EQ").itemOID("IT.T").checkValues(List.of(cv("X"))).build()))
                .build();
        ItemDef t = ItemDef.builder().oid("IT.T").name("T").dataType("text").build();
        ItemDef parent = ItemDef.builder().oid("IT.P").name("P").dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.P").build()).build();
        ItemDef a = ItemDef.builder().oid("IT.A").name("A").dataType("integer").build();
        ItemDef b = ItemDef.builder().oid("IT.B").name("B").dataType("float").build();
        ValueListDef vl = ValueListDef.builder().oid("VL.P")
                .itemRefs(List.of(
                        ItemRef.builder().itemOID("IT.B").orderNumber(2)
                                .whereClauseRefs(List.of(
                                        WhereClauseRef.builder().whereClauseOID("WC.X").build()))
                                .build(),
                        ItemRef.builder().itemOID("IT.A").orderNumber(1)
                                .whereClauseRefs(List.of(
                                        WhereClauseRef.builder().whereClauseOID("WC.X").build()))
                                .build()))
                .build();
        ItemGroupDef ig = ItemGroupDef.builder().oid("IG.D").name("D").domain("D")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.P").build(),
                        ItemRef.builder().itemOID("IT.T").build()))
                .build();
        MetaDataVersion mdv = MetaDataVersion.builder().itemGroupDefs(List.of(ig))
                .itemDefs(List.of(parent, t, a, b)).valueListDefs(List.of(vl))
                .whereClauseDefs(List.of(wc)).build();
        VlmResolver r = VlmResolver.from(mdv);
        assertNotNull(r);
        VlmResolver.VlmMatch m = r.resolve("D", "P", row(Map.of("T", "X")));
        assertNotNull(m);
        assertEquals("integer", m.dataType(), "OrderNumber 1 (IT.A) must win over OrderNumber 2");
    }


    @Test
    void comparatorCoverage()
    {
        assertMatch("EQ", "5", List.of("5"), true);
        assertMatch("EQ", "5", List.of("6"), false);
        assertMatch("NE", "5", List.of("6"), true);
        assertMatch("NE", "5", List.of("5"), false);
        assertMatch("IN", "B", List.of("A", "B"), true);
        assertMatch("IN", "C", List.of("A", "B"), false);
        assertMatch("NOTIN", "C", List.of("A", "B"), true);
        assertMatch("NOTIN", "A", List.of("A", "B"), false);
        // numeric-aware
        assertMatch("LT", "3", List.of("5"), true);
        assertMatch("LT", "5", List.of("5"), false);
        assertMatch("LE", "5", List.of("5"), true);
        assertMatch("GT", "7", List.of("5"), true);
        assertMatch("GE", "5", List.of("5"), true);
        assertMatch("GT", "5", List.of("7"), false);
        // lexical fallback for non-numeric operands
        assertMatch("GT", "B", List.of("A"), true);
    }


    /** Builds a one-condition ValueListDef using {@code comparator} and asserts match/no-match. */
    private static void assertMatch(String comparator, String actual, List<String> bounds,
            boolean expectMatch)
    {
        List<CheckValue> cvs = bounds.stream().map(VlmResolverTest::cv).toList();
        WhereClauseDef wc = WhereClauseDef.builder().oid("WC").rangeChecks(List.of(RangeCheck
                .builder().comparator(comparator).itemOID("IT.T").checkValues(cvs).build()))
                .build();
        ItemDef t = ItemDef.builder().oid("IT.T").name("T").dataType("text").build();
        ItemDef parent = ItemDef.builder().oid("IT.P").name("P").dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.P").build()).build();
        ItemDef v = ItemDef.builder().oid("IT.V").name("V").dataType("float").build();
        ValueListDef vl = ValueListDef.builder().oid("VL.P")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.V").orderNumber(1)
                        .whereClauseRefs(
                                List.of(WhereClauseRef.builder().whereClauseOID("WC").build()))
                        .build()))
                .build();
        ItemGroupDef ig = ItemGroupDef.builder().oid("IG.D").name("D").domain("D")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.P").build(),
                        ItemRef.builder().itemOID("IT.T").build()))
                .build();
        MetaDataVersion mdv = MetaDataVersion.builder().itemGroupDefs(List.of(ig))
                .itemDefs(List.of(parent, t, v)).valueListDefs(List.of(vl))
                .whereClauseDefs(List.of(wc)).build();
        VlmResolver r = VlmResolver.from(mdv);
        assertNotNull(r);
        VlmResolver.VlmMatch m = r.resolve("D", "P", row(Map.of("T", actual)));
        assertEquals(expectMatch, m != null, comparator + "(" + actual + " vs " + bounds + ")");
    }


    @Test
    void structuralWarningsForDanglingRefs()
    {
        // ItemRef with a dangling WhereClauseRef and a range check with a dangling ItemOID.
        WhereClauseDef wc = WhereClauseDef.builder().oid("WC.OK")
                .rangeChecks(List.of(RangeCheck.builder().comparator("EQ").itemOID("IT.MISSING")
                        .checkValues(List.of(cv("X"))).build()))
                .build();
        ItemDef parent = ItemDef.builder().oid("IT.P").name("P").dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.P").build()).build();
        ItemDef v = ItemDef.builder().oid("IT.V").name("V").dataType("text").build();
        ValueListDef vl = ValueListDef.builder().oid("VL.P")
                .itemRefs(List.of(
                        ItemRef.builder().itemOID("IT.V").orderNumber(1)
                                .whereClauseRefs(List.of(WhereClauseRef.builder()
                                        .whereClauseOID("WC.DANGLING").build()))
                                .build(),
                        ItemRef.builder().itemOID("IT.V").orderNumber(2)
                                .whereClauseRefs(List.of(
                                        WhereClauseRef.builder().whereClauseOID("WC.OK").build()))
                                .build()))
                .build();
        ItemGroupDef ig = ItemGroupDef.builder().oid("IG.D").name("D").domain("D")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.P").build())).build();
        MetaDataVersion mdv = MetaDataVersion.builder().itemGroupDefs(List.of(ig))
                .itemDefs(List.of(parent, v)).valueListDefs(List.of(vl))
                .whereClauseDefs(List.of(wc)).build();
        VlmResolver r = VlmResolver.from(mdv);
        assertNotNull(r);
        List<String> warnings = r.structuralWarnings();
        assertFalse(warnings.isEmpty(), "dangling refs must be reported");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("WC.DANGLING")),
                "dangling WhereClauseRef warning");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("IT.MISSING")),
                "dangling RangeCheck ItemOID warning");
    }

}
