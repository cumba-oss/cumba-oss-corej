package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * D4 of {@code plans/PLAN-define-item-metadata-parity-929-1081.md}: list-aware
 * {@code is_(not_)contained_by} for the {@code define_variable_codelist_coded_codes} operand. The
 * value is a list of every coded code in a variable's define codelist; membership must compare it
 * element-wise, mirroring the Python engine's {@code is_column_of_iterables(target)} branch
 * ({@code any(is_in(item, comparator) for item in target_val)}). The pre-existing single-code
 * Tier-B fixture cannot exercise the multi-element / empty-list cases this test covers.
 */
class DefineCodedCodesListMembershipTest
{

    /** Rule with a single list-LHS membership leaf against a literal published-code list. */
    private static Rule rule(String operator) throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"}," + "\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"name\":\"define_variable_codelist_coded_codes\",\"operator\":\"" + operator
                + "\",\"value\":[\"AE\",\"CM\",\"EX\"]}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        assertNotNull(r.getCheckExpr(), "list-LHS membership must compile to a native checkExpr");
        return r;
    }


    /**
     * A define provider whose AE.AEACN variable carries the given JSON-encoded coded-codes list.
     */
    private static MetadataProvider define(String codedCodesJson)
    {
        return new StubMetadataProvider().variable("AE",
                Map.of("name", "AEACN", "codelist_coded_codes", codedCodesJson));
    }


    private static boolean fires(String operator, String codedCodesJson) throws Exception
    {
        IDataTable ae = MockTable.of().name("AE").col("AEACN", "x").build();
        RuleExecutionResult r = RuleRunner.execute(rule(operator), ae, _ -> null, "AE", null, null,
                define(codedCodesJson));
        return r.hasViolations();
    }


    @Test
    void isNotContainedBy_noneOfTheCodesPublished_fires() throws Exception
    {
        // any(["ZZ","QQ"] in {AE,CM,EX}) == false -> is_not_contained_by fires.
        assertTrue(fires("is_not_contained_by", "[\"ZZ\",\"QQ\"]"));
    }


    @Test
    void isNotContainedBy_someCodePublished_doesNotFire() throws Exception
    {
        // any(["AE","ZZ"] in {AE,CM,EX}) == true -> is_not_contained_by does not fire (Python
        // ~any).
        assertFalse(fires("is_not_contained_by", "[\"AE\",\"ZZ\"]"));
    }


    @Test
    void isNotContainedBy_emptyList_fires() throws Exception
    {
        // any([]) == false -> is_not_contained_by fires (mirrors Python any([]) == False).
        assertTrue(fires("is_not_contained_by", "[]"));
    }


    @Test
    void isContainedBy_someCodePublished_fires() throws Exception
    {
        // any(["AE","ZZ"] in {AE,CM,EX}) == true -> is_contained_by fires.
        assertTrue(fires("is_contained_by", "[\"AE\",\"ZZ\"]"));
    }


    @Test
    void isContainedBy_noneOfTheCodesPublished_doesNotFire() throws Exception
    {
        assertFalse(fires("is_contained_by", "[\"ZZ\",\"QQ\"]"));
    }
}
