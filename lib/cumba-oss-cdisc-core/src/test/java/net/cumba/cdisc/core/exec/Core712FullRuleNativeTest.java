package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * End-to-end native/legacy parity guard for CORE-000712
 * ({@code IDVAR is_not_contained_by $rdomain_variables}) on a SUPPLB-shaped dataset.
 *
 * <p>
 * The {@code $rdomain_variables} operation ({@code distinct value_is_reference} with
 * {@code domain: "SUPP--"}) resolves its target via {@code resolver.resolve("SUPPLB")}. When that
 * lookup returns {@code null} (a dataset name-keying mismatch at study-load time) the operation is
 * skipped and the reference resolves to {@code null}. Legacy then evaluates the membership against
 * the empty set; the native backend used to throw {@code ExpressionException} for that case. These
 * tests pin that the native row evaluation matches legacy in both the resolvable and the
 * unresolvable case — and never throws.
 * </p>
 */
class Core712FullRuleNativeTest
{

    // Reads the shared rule from the rules-src YAML source of truth (the per-rule fixture copy was
    // removed in the split-storage migration). The Standards membership block is ignored
    // (FAIL_ON_UNKNOWN_PROPERTIES=false); the check-leaf form is identical to the old fixture.
    private static final ObjectMapper MAPPER = new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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


    private static Rule core712() throws Exception
    {
        Path ruleFile = Path.of("src/test/resources/fixtures/rules/checks/CORE/CORE-000712.yaml");
        Rule rule = MAPPER.readValue(Files.readString(ruleFile), Rule.class);
        // rules-src no longer carries Rule_Type / Sensitivity — the loader
        // derives them, so a hand-bound rule must be completed the same way.
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        RulePackageLoader.deriveOmittedFields(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    @Test
    void operationResolves_flagsOnlyInvalidIdvar() throws Exception
    {
        Rule rule = core712();
        IDataTable supp = suppLb();
        IDataTable lb = lb();
        DatasetResolver resolver = inventory(supp, lb, /*registerSupp=*/true);

        // "SUPPLB" is the value LibraryValidator now derives (cdiscDomain):
        // resolveOperationPrefix's
        // SUPP-aware branch turns "SUPP--" into "SUPPLB", which resolves, so the per-RDOMAIN
        // column-name set is built and only BOGUS (not an LB column) is flagged.
        assertEquals(List.of("BOGUS"), flaggedIdvars(rule, supp, resolver, "SUPPLB"), "legacy");
        assertEquals(List.of("BOGUS"), flaggedIdvars(rule, supp, resolver, "SUPPLB"),
                "native must match legacy");
    }


    @Test
    void operationSelfReferenceFallback_flagsOnlyInvalidIdvar() throws Exception
    {
        Rule rule = core712();
        IDataTable supp = suppLb();
        IDataTable lb = lb();
        // SUPPLB is NOT registered by name, so the operation's "SUPP--" target ("SUPPLB") does not
        // resolve via resolver.resolve. J7 part 2 (resolveTargetTable self-reference fallback): the
        // current table's name ("SUPPLB") starts with the unresolved domain, so the operation —
        // which
        // is self-referential — runs against the current table. $rdomain_variables then reads the
        // SUPP's own RDOMAIN ("LB") and unions LB's columns (tablesForDomain), so LBSEQ is a real
        // LB
        // column (no fire) and only BOGUS is flagged. resolveTargetTable is shared by both
        // backends,
        // so legacy and native change identically and parity holds (and neither throws).
        DatasetResolver resolver = inventory(supp, lb, /*registerSupp=*/false);

        List<String> legacy = flaggedIdvars(rule, supp, resolver, "SUPPLB");
        List<String> nativ = flaggedIdvars(rule, supp, resolver, "SUPPLB");
        assertEquals(List.of("BOGUS"), legacy, "self-reference fallback resolves the operation");
        assertEquals(legacy, nativ, "native must match legacy (no ExpressionException)");
    }


    @Test
    void truncatedSuppPrefixBreaksOperationResolution() throws Exception
    {
        // LibraryValidator.prefixOf("SUPPLB") returns the first 2 chars "SU"; RuleRunner's
        // resolveOperationPrefix then rewrites the operation domain "SUPP--" to "SUPPSU" (Fix #33
        // only special-cases prefixes that start with "SUPP" and are >4 chars). resolve("SUPPSU")
        // misses even though SUPPLB IS registered, so $rdomain_variables is null. This is the real
        // production trigger.
        Rule rule = core712();
        IDataTable supp = suppLb();
        IDataTable lb = lb();
        DatasetResolver resolver = inventory(supp, lb, /*registerSupp=*/true);

        // Diagnostic: the operation result under the truncated "SU" prefix.
        net.cumba.cdisc.core.model.Operation op = rule.getOperations().get(0);
        net.cumba.cdisc.core.model.Operation rewritten = rewriteDomain(op, "SUPP--", "SUPPSU");
        Object res = OperationExecutor.execute(List.of(rewritten), supp, resolver)
                .get("$rdomain_variables");
        // Q17-a: an unresolvable target now publishes the operator's declared EmptyResult rather
        // than an unclassified null. `distinct` declares SET, so the value is the empty list — and
        // the membership fold (ExprCompiler.toSet) already treated null and an empty collection
        // identically, which is why the over-fire assertions below are UNCHANGED. That equality is
        // the control: it proves Q17-a re-classified the absent case without touching what the
        // empty set means.
        assertEquals(List.of(), res, "operation target SUPPSU does not resolve -> empty set");

        // With the truncated prefix the full rule does not throw (post-fix) but over-fires.
        List<String> legacy = flaggedIdvars(rule, supp, resolver, "SU");
        List<String> nativ = flaggedIdvars(rule, supp, resolver, "SU");
        assertEquals(List.of("LBSEQ", "BOGUS"), legacy, "truncated prefix -> over-fire (legacy)");
        assertEquals(legacy, nativ, "native matches legacy under the broken prefix (no throw)");

        // With the correct full prefix the operation resolves and only BOGUS is flagged.
        assertEquals(List.of("BOGUS"), flaggedIdvars(rule, supp, resolver, "SUPPLB"),
                "full SUPP prefix resolves the operation -> correct result");
    }


    private static net.cumba.cdisc.core.model.Operation rewriteDomain(
            net.cumba.cdisc.core.model.Operation src, String from, String to)
    {
        net.cumba.cdisc.core.model.Operation o = new net.cumba.cdisc.core.model.Operation();
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
        RuleExecutionResult result = RuleRunner.execute(rule, supp, resolver, domainPrefix, null,
                null);
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
