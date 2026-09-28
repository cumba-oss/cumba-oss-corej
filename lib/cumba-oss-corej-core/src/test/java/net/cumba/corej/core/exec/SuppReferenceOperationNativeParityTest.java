package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * End-to-end guard for a {@code value_is_reference} membership operation whose target domain must
 * be resolved through the SUPP-- prefix rewrite, on a SUPPLB-shaped dataset.
 *
 * <p>
 * The carrier is a hand-written rule ({@link #SUPP_RDOMAIN_RULE}): {@code IDVAR not in
 * $rdomain_variables}, where {@code $rdomain_variables} is
 * {@code distinct(IDVAR, value_is_reference=true, domain="SUPP--")}. Each test asserts the exact
 * set of flagged {@code IDVAR} values against a stated expectation, and that the rule EXECUTED
 * (never ERROR). Three mechanisms are pinned:
 * </p>
 * <ol>
 * <li><b>SUPP-- rewrite</b> — with the full {@code SUPPLB} prefix, {@code "SUPP--"} becomes
 * {@code "SUPPLB"}, which resolves, so only {@code BOGUS} (not an LB column) is flagged.</li>
 * <li><b>Self-reference fallback</b> — when {@code SUPPLB} is not registered by name, the operation
 * runs against the current table and still flags only {@code BOGUS}.</li>
 * <li><b>Truncated prefix</b> — the two-character {@code "SU"} prefix rewrites the target to the
 * unresolvable {@code SUPPSU}; the operation answers the empty set and the rule over-fires on both
 * rows, without throwing.</li>
 * </ol>
 * <p>
 * The class name is historical: it dates from when a legacy and a native row backend were compared
 * here. Only the native backend exists now, so nothing is compared against a second engine.
 * </p>
 *
 * <h2>⚠ The {@code domain="SUPP--"} operand is load-bearing</h2> It is what routes the operation
 * through the engine branch under test — {@code OperationExecutor.resolvePrefixes}'s SUPP-aware
 * rewrite of a {@code SUPP--} operation domain, and {@code resolveTargetTable}'s self-reference
 * fallback. No shipped rule authors {@code domain="SUPP--"} (measured 2026-09-19), which is why the
 * rule is written here rather than read from the corpus. ⛔ Do not drop the operand. <b>Measured
 * 2026-09-19, and again 2026-09-28 on this hand-written rule, by removing it:</b>
 * {@link #truncatedSuppPrefixBreaksOperationResolution()} fails outright — the unresolvable
 * {@code SUPPSU} target comes back as a resolved {@code GroupedResult} instead of the empty set —
 * while the other two arms <b>still pass</b>, because an operation with no declared domain never
 * reaches the prefix rewrite at all.
 */
class SuppReferenceOperationNativeParityTest
{

    private static final String SUPP_RDOMAIN_RULE = """
            {"rules":{"S1":{"Core":{"Id":"T-SUPP-IDVAR"},
             "Scope":{"Domains":{"Include":["SUPP--"]}},
             "Requirements":{"Variables":{"All":["IDVAR"]}},
             "Bindings":[{"name":"$rdomain_variables",
               "expression":"distinct(IDVAR, value_is_reference=true, domain=\\"SUPP--\\")"}],
             "Check":{"expression":"not empty(IDVAR) and IDVAR not in $rdomain_variables"},
             "Outcome":{"Message":"m","Output_Variables":["RDOMAIN","IDVAR"]}}}}""";

    private static final class Fix
    {

        private final String name;

        private final List<String> colNames = new ArrayList<>();

        private final List<Object[]> colData = new ArrayList<>();

        private Fix(String aName)
        {
            name = aName;
        }


        static Fix of(String n)
        {
            return new Fix(n);
        }


        Fix str(String n, String... v)
        {
            colNames.add(n);
            colData.add(v);
            return this;
        }


        IDataTable build()
        {
            int colCount = colNames.size();
            int rowCount = colData.isEmpty() ? 0 : colData.get(0).length;
            CachedDataTableColumn[] cols = new CachedDataTableColumn[colCount];
            DataTableColumnMeta[] metas = new DataTableColumnMeta[colCount];
            for (int c = 0; c < colCount; c++)
            {
                cols[c] = new CachedDataTableColumn(c, DataValueType.STRING);
                metas[c] = DataTableColumnMeta.builder().index(c).name(colNames.get(c))
                        .label(colNames.get(c)).type(DataValueType.STRING).build();
                Object[] data = colData.get(c);
                for (int r = 0; r < rowCount; r++)
                {
                    cols[c].addElement(data[r]);
                }
                cols[c].complete();
            }
            DataTableMeta meta = DataTableMeta.builder().name(name).label(name).columns(metas)
                    .rowCount(rowCount).totalRowCount(rowCount).build();
            return new ColumnCachedDataTable(meta, cols);
        }
    }

    private static IDataTable suppLb()
    {
        // Row 0 IDVAR=LBSEQ is a real LB column (no violation); row 1 IDVAR=BOGUS is not.
        return Fix.of("SUPPLB").str("STUDYID", "S1", "S1").str("RDOMAIN", "LB", "LB")
                .str("USUBJID", "001", "001").str("IDVAR", "LBSEQ", "BOGUS")
                .str("IDVARVAL", "1", "2").str("QNAM", "X", "X").str("QLABEL", "x", "x")
                .str("QVAL", "a", "b").str("QORIG", "o", "o").str("QEVAL", "e", "e").build();
    }


    private static IDataTable lb()
    {
        return Fix.of("LB").str("STUDYID", "S1").str("DOMAIN", "LB").str("USUBJID", "001")
                .str("LBSEQ", "1").str("LBTESTCD", "T").str("LBTEST", "Test").str("LBORRES", "5")
                .build();
    }


    private static Rule suppRdomainRule() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(SUPP_RDOMAIN_RULE).getRules().get("S1");
        assertNull(rule.getLoadError(), "the hand-written rule must load: " + rule.getLoadError());
        assertEquals("SUPP--", rule.getOperations().get(0).getDomain(),
                "the SUPP-- operand is authored, and it is what the class exercises");
        return rule;
    }


    @Test
    void operationResolves_flagsOnlyInvalidIdvar() throws Exception
    {
        Rule rule = suppRdomainRule();
        IDataTable supp = suppLb();
        IDataTable lb = lb();
        DatasetResolver resolver = inventory(supp, lb, /*registerSupp=*/true);

        // "SUPPLB" is the value LibraryValidator now derives (cdiscDomain):
        // OperationExecutor.resolvePrefixes's SUPP-aware branch turns "SUPP--" into "SUPPLB",
        // which resolves, so the per-RDOMAIN column-name set is built and only BOGUS (not an LB
        // column) is flagged; LBSEQ is a real LB column.
        assertEquals(List.of("BOGUS"), flaggedIdvars(rule, supp, resolver, "SUPPLB"),
                "SUPP-- rewritten to the resolvable SUPPLB -> only BOGUS flagged");
    }


    @Test
    void operationSelfReferenceFallback_flagsOnlyInvalidIdvar() throws Exception
    {
        Rule rule = suppRdomainRule();
        IDataTable supp = suppLb();
        IDataTable lb = lb();
        // SUPPLB is NOT registered by name, so the operation's "SUPP--" target ("SUPPLB") does not
        // resolve via resolver.resolve. J7 part 2 (resolveTargetTable self-reference fallback): the
        // current table's name ("SUPPLB") starts with the unresolved domain, so the operation —
        // which is self-referential — runs against the current table. $rdomain_variables then
        // reads the SUPP's own RDOMAIN ("LB") and unions LB's columns (tablesForDomain), so LBSEQ
        // is a real LB column (no fire) and only BOGUS is flagged. Without the fallback the target
        // would be the empty set and LBSEQ would be flagged too.
        DatasetResolver resolver = inventory(supp, lb, /*registerSupp=*/false);

        assertEquals(List.of("BOGUS"), flaggedIdvars(rule, supp, resolver, "SUPPLB"),
                "self-reference fallback resolves the operation -> only BOGUS flagged");
    }


    @Test
    void truncatedSuppPrefixBreaksOperationResolution() throws Exception
    {
        // LibraryValidator.prefixOf("SUPPLB") returns the first 2 chars "SU";
        // OperationExecutor.resolvePrefixes then rewrites the operation domain "SUPP--" to
        // "SUPPSU" (Fix #33 only special-cases prefixes that start with "SUPP" and are >4 chars).
        // resolve("SUPPSU") misses even though SUPPLB IS registered, so $rdomain_variables is
        // null. This is the real production trigger.
        Rule rule = suppRdomainRule();
        IDataTable supp = suppLb();
        IDataTable lb = lb();
        DatasetResolver resolver = inventory(supp, lb, /*registerSupp=*/true);

        // Diagnostic: the operation result under the truncated "SU" prefix.
        net.cumba.corej.core.model.Operation op = rule.getOperations().get(0);
        net.cumba.corej.core.model.Operation rewritten = rewriteDomain(op, "SUPP--", "SUPPSU");
        Object res = OperationExecutorCalls.execute(List.of(rewritten), supp, resolver)
                .get("$rdomain_variables");
        // Q17-a: an unresolvable target now publishes the operator's declared EmptyResult rather
        // than an unclassified null. `distinct` declares SET, so the value is the empty list — and
        // the membership fold (ExprCompiler.toSet) already treated null and an empty collection
        // identically, which is why the over-fire assertions below are UNCHANGED. That equality is
        // the control: it proves Q17-a re-classified the absent case without touching what the
        // empty set means.
        assertEquals(List.of(), res, "operation target SUPPSU does not resolve -> empty set");

        // With the truncated prefix the full rule EXECUTES (no ExpressionException over the empty
        // set) but over-fires: every non-empty IDVAR is "not in" the empty set.
        assertEquals(List.of("LBSEQ", "BOGUS"), flaggedIdvars(rule, supp, resolver, "SU"),
                "truncated prefix -> empty target set -> both rows flagged");

        // With the correct full prefix the operation resolves and only BOGUS is flagged.
        assertEquals(List.of("BOGUS"), flaggedIdvars(rule, supp, resolver, "SUPPLB"),
                "full SUPP prefix resolves the operation -> correct result");
    }


    private static net.cumba.corej.core.model.Operation rewriteDomain(
            net.cumba.corej.core.model.Operation src, String from, String to)
    {
        net.cumba.corej.core.model.Operation o = new net.cumba.corej.core.model.Operation();
        o.setId(src.getId());
        o.setOperator(src.getOperator());
        o.setName(src.getName());
        o.setDomain(from.equals(src.getDomain()) ? to : src.getDomain());
        o.setValueIsReference(src.getValueIsReference());
        return o;
    }


    private static List<String> flaggedIdvars(Rule rule, IDataTable supp, DatasetResolver resolver,
            String domainPrefix)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, supp, resolver, domainPrefix,
                null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> "the rule must execute, never ERROR: " + result.getStatusMessage());
        List<String> idvars = new ArrayList<>();
        result.getViolations().forEach(v -> idvars.add(v.getValues().get("IDVAR")));
        return idvars;
    }


    private static DatasetResolver inventory(IDataTable supp, IDataTable lb, boolean registerSupp)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                if ("LB".equals(name))
                {
                    return lb;
                }
                if (registerSupp && "SUPPLB".equals(name))
                {
                    return supp;
                }
                return null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return registerSupp ? java.util.Set.of("SUPPLB", "LB") : java.util.Set.of("LB");
            }
        };
    }
}
