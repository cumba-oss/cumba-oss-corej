package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.datatable.DataTableMeta;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RuleRunner#collectCheckLeafColumns} — the Output_Variables inference used
 * when a rule declares no {@code Outcome.Output_Variables}, mirroring Python's
 * {@code RuleProcessor._extract_targets_from_conditions}. Covers the three parity rules:
 * {@code not_exists} leaves contribute nothing; ordinary leaves contribute their {@code name};
 * {@code additional_columns_*} expand to dataset columns matching {@code ^<name>\d+$}.
 */
class CollectCheckLeafColumnsTest
{

    private static CheckConditionLeaf leaf(String name, String op)
    {
        return CheckConditionLeaf.builder().name(name).operator(op).build();
    }


    private static DataTableMeta meta(String... cols)
    {
        MockTable t = MockTable.of();
        for (String c : cols)
        {
            t.col(c, "x");
        }
        return t.name("LB").build().getMetaData();
    }


    @Test
    void skipsNotExistsLeaves()
    {
        // MS exists AND MB not_exists -> only MS (Python skips not_exists targets).
        var check = new CheckConditionAll(
                List.of(leaf("MS", "var_exists"), leaf("MB", "var_not_exists")));
        assertEquals(List.of("MS"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(check, meta("MS"))));
    }


    @Test
    void ordinaryLeavesContributeNameDeduped()
    {
        var check = new CheckConditionAll(List.of(leaf("TRTP", "non_empty"),
                leaf("TRTP", "not_equal_to"), leaf("USUBJID", "var_exists")));
        assertEquals(List.of("TRTP", "USUBJID"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(check, meta("TRTP", "USUBJID"))));
    }


    @Test
    void additionalColumnsExpandToNumberedSiblings()
    {
        // additional_columns_empty(TSVAL) -> TSVAL1, TSVAL2 (matching ^TSVAL\d+$), NOT bare TSVAL.
        var check = new CheckConditionAll(List.of(leaf("TSVAL", "additional_columns_empty")));
        assertEquals(List.of("TSVAL1", "TSVAL2"), List.copyOf(RuleRunner
                .collectCheckLeafColumns(check, meta("TSVAL", "TSVAL1", "TSVAL2", "TSVALCD"))));
    }


    @Test
    void nullNameLeafContributesNothing()
    {
        // A leaf with no column target (e.g. a get_dataset operator) adds nothing.
        var check = new CheckConditionAll(
                List.of(leaf(null, "var_exists"), leaf("AGE", "var_exists")));
        assertEquals(List.of("AGE"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(check, meta("AGE"))));
    }
}
