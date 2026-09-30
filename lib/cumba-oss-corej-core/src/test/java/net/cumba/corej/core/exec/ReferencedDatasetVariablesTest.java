package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code referenced_dataset_variables(name)} (runbook W7, {@code PLAN-distinct-function} D-W7-6):
 * per row the variable names of the dataset the row's value names — the retired
 * {@code distinct(IDVAR, value_is_reference=true)}, with its column explicit. A split family's
 * members union; a missing, blank or unknown value answers the empty list; {@code upper} folds the
 * per-row list; the classification is per row; a quoted name is a load error.
 */
class ReferencedDatasetVariablesTest
{

    private static Rule load(String binding, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":{"
                    + "\"Core\":{\"Id\":\"X-1\"},"
                    + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"" + binding + "\"},"
                    + "{\"name\":\"$u\",\"expression\":\"upper($v)\"}],"
                    + "\"Check\":{\"expression\":\"" + check + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\",\"IDVAR\"]}}}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + binding, e);
        }
    }

    /** A WithInventory resolver keyed by member name, answering a domain's members by DOMAIN. */
    private static final class Inventory implements DatasetResolver.WithInventory
    {

        private final Map<String, IDataTable> byName = new LinkedHashMap<>();

        Inventory(IDataTable... tables)
        {
            for (IDataTable t : tables)
            {
                byName.put(t.getMetaData().getName().toUpperCase(Locale.ROOT), t);
            }
        }


        @Override
        public @Nullable IDataTable resolve(String name)
        {
            return name == null ? null : byName.get(name.toUpperCase(Locale.ROOT));
        }


        @Override
        public Set<String> availableDatasets()
        {
            return byName.keySet();
        }
    }

    private static IDataTable co()
    {
        return RealTableFixture.of("CO").str("DOMAIN", "CO", "CO", "CO", "CO", "CO", "CO")
                .str("USUBJID", "A", "B", "C", "D", "E", "F")
                .str("RDOMAIN", "DM", "DM", "LB", "", null, "ZZ")
                .str("IDVAR", "USUBJID", "NOPE", "LBORRES", "USUBJID", "USUBJID", "USUBJID")
                .build();
    }


    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM").str("USUBJID", "A").build();
    }


    private static IDataTable lbch()
    {
        return RealTableFixture.of("lbch").str("DOMAIN", "LB").str("USUBJID", "A").str("LBSEQ", "1")
                .str("LBCAT", "CHEM").build();
    }


    private static IDataTable lbhe()
    {
        return RealTableFixture.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "A").str("LBSEQ", "2")
                .str("LBORRES", "x").build();
    }


    private static List<List<?>> rows(String call, EvaluationContext ctx)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        List<List<?>> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add((List<?>) v.value(row).resolved());
        }
        return out;
    }


    @Test
    void eachRowReadsTheColumnNamesOfTheDatasetItNames()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(co())
                .datasetResolver(new Inventory(dm(), lbch(), lbhe())).build();
        List<List<?>> lists = rows("referenced_dataset_variables(RDOMAIN)", ctx);
        assertEquals(List.of("DOMAIN", "USUBJID"), lists.get(0));
        assertEquals(List.of("DOMAIN", "USUBJID"), lists.get(1));
        assertEquals(List.of("DOMAIN", "USUBJID", "LBSEQ", "LBCAT", "LBORRES"), lists.get(2),
                "a split family's members union, in declaration order across the members");
        assertEquals(List.of(), lists.get(3), "a blank value names no dataset");
        assertEquals(List.of(), lists.get(4), "a missing value names no dataset");
        assertEquals(List.of(), lists.get(5), "an unknown dataset has no variables");
    }


    @Test
    void theCorpusCheckFiresPerRowOnAnIdvarTheReferencedDatasetLacks()
    {
        // CG0370's Check, per row: B (NOPE is no DM column), D / E (no dataset ⇒ the empty
        // list ⇒ `not in` fires), F (unknown dataset); A and C are silent.
        Rule rule = load("referenced_dataset_variables(RDOMAIN)",
                "var_exists(\\\"RDOMAIN\\\") and not empty(IDVAR) and IDVAR not in $v and IDVAR not in $u");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, co(),
                new Inventory(co(), dm(), lbch(), lbhe()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<String> fired = result.getViolations().stream().map(f -> f.getValues().get("USUBJID"))
                .toList();
        assertEquals(List.of("B", "D", "E", "F"), fired);
    }


    @Test
    void upperFoldsThePerRowListElementWise()
    {
        // The $rdomain_variables_upper sibling: a lower-case IDVAR meets the upper-cased names.
        IDataTable co = RealTableFixture.of("CO").str("DOMAIN", "CO", "CO").str("USUBJID", "A", "B")
                .str("RDOMAIN", "DM", "DM").str("IDVAR", "USUBJID", "usubjid").build();
        IDataTable dmLower = RealTableFixture.of("DM").str("DOMAIN", "DM").str("UsubjId", "A")
                .build();
        Rule rule = load("referenced_dataset_variables(RDOMAIN)",
                "not empty(IDVAR) and IDVAR not in $v and IDVAR not in $u");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, co, new Inventory(co, dmLower));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "USUBJID meets the upper-cased UsubjId; usubjid meets neither list");
        assertEquals("B", result.getViolations().get(0).getValues().get("USUBJID"));
    }


    @Test
    void aResolverWithoutAnInventoryAnswersTheOneDatasetOfThatName()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(co())
                .datasetResolver(name -> "DM".equalsIgnoreCase(name) ? dm() : null).build();
        List<List<?>> lists = rows("referenced_dataset_variables(RDOMAIN)", ctx);
        assertEquals(List.of("DOMAIN", "USUBJID"), lists.get(0));
        assertEquals(List.of(), lists.get(2),
                "no inventory: a split member is not found by domain");
    }


    @Test
    void aQuotedNameIsALoadErrorAndTheColumnIsRequired()
    {
        Rule quoted = load("referenced_dataset_variables(\\\"RDOMAIN\\\")", "IDVAR not in $v");
        assertNotNull(quoted.getLoadError());
        assertTrue(quoted.getLoadError().contains("column reference"), quoted.getLoadError());
        Rule bare = load("referenced_dataset_variables()", "IDVAR not in $v");
        assertNotNull(bare.getLoadError(), "the RDOMAIN default of the operation is not carried");
    }
}
