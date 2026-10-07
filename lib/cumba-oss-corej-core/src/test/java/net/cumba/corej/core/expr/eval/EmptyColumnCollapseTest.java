package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.ColumnContent;
import net.cumba.datatable.ColumnContent.Fill;
import net.cumba.datatable.ColumnContent.Uniformity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link EmptyColumnCollapse}'s static surface: the R2 classifier (a/b/c), the
 * {@code Keep_Row_Findings} read check, the leaf and reference walks, the truth table
 * ({@link EmptyColumnCollapse#rowConstant}) cell by cell, the resolution of a {@code --} template,
 * and {@link EmptyColumnCollapse#foldableColumns} on a table.
 */
class EmptyColumnCollapseTest
{

    private static Rule rule(String check, Sensitivity sensitivity, @Nullable String folded,
            @Nullable List<String> keep)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("C7-CLS");
        rule.setCore(core);
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(check), check));
        rule.setSensitivity(sensitivity);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setMessageFolded(folded);
        outcome.setKeepRowFindings(keep);
        rule.setOutcome(outcome);
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static Expr parse(String text)
    {
        return CheckExpressionParser.parse(text);
    }

    // ---- R2 -------------------------------------------------------------------------------


    @Test
    void aRecordRuleWithAPlainEmptyLeafIsCollapsible()
    {
        assertTrue(EmptyColumnCollapse.collapsible(
                rule("not empty(AEBODSYS) and empty(--BDSYCD)", Sensitivity.RECORD, "f", null)));
    }


    @Test
    void r2aNoEmptyFamilyLeafOverAPlainColumn()
    {
        List<String> r = EmptyColumnCollapse
                .refusals(rule("AEBDSYCD != \"X\"", Sensitivity.RECORD, "f", null));
        assertEquals(1, r.size(), r.toString());
        assertTrue(r.getFirst().contains("no empty-family leaf"), r.getFirst());
        // A dotted argument and a $-binding do not count (R5).
        assertFalse(EmptyColumnCollapse
                .collapsible(rule("empty(DM.ARMCD)", Sensitivity.RECORD, "f", null)));
    }


    @Test
    void r2aEveryLeafExcludedByKeepRowFindings()
    {
        List<String> r = EmptyColumnCollapse
                .refusals(rule("empty(AEBDSYCD)", Sensitivity.RECORD, "f", List.of("aebdsycd")));
        assertEquals(1, r.size(), r.toString());
        assertTrue(r.getFirst().contains("outside Keep_Row_Findings"), r.getFirst());
    }


    @Test
    void r2bNonRecordSensitivity()
    {
        for (Sensitivity s : List.of(Sensitivity.DATASET, Sensitivity.GROUP, Sensitivity.STUDY))
        {
            List<String> r = EmptyColumnCollapse.refusals(rule("empty(AEBDSYCD)", s, "f", null));
            assertEquals(1, r.size(), s + ": " + r);
            assertTrue(r.getFirst().contains("RECORD-level")
                    && r.getFirst().contains(s.getJsonValue()), r.getFirst());
        }
    }


    @Test
    void r2cVariableCursorDomain()
    {
        List<String> r = EmptyColumnCollapse.refusals(rule(
                "empty(AEBDSYCD) and varname() == \"AEBDSYCD\"", Sensitivity.RECORD, "f", null));
        assertEquals(1, r.size(), r.toString());
        assertTrue(r.getFirst().contains("variable cursor"), r.getFirst());
    }

    // ---- Keep_Row_Findings --------------------------------------------------------------------


    @Test
    void keepRowFindingsMustNameAColumnTheCheckReads()
    {
        List<String> r = EmptyColumnCollapse.keepRowFindingsErrors(
                rule("empty(AEBDSYCD)", Sensitivity.RECORD, "f", List.of("AETERM", " ")));
        assertEquals(2, r.size(), r.toString());
        assertTrue(r.get(0).contains("'AETERM'") && r.get(0).contains("does not read"), r.get(0));
        assertTrue(r.get(1).contains("empty entry"), r.get(1));
        assertTrue(
                EmptyColumnCollapse
                        .keepRowFindingsErrors(rule("empty(--BDSYCD) and DM.ARMCD == \"x\"",
                                Sensitivity.RECORD, "f", List.of("--bdsycd", "DM.ARMCD")))
                        .isEmpty(),
                "compared as written, case-insensitively, dotted included");
    }


    @Test
    void keepRowFindingsResolvesTheWildcardPrefix()
    {
        Outcome o = new Outcome();
        o.setKeepRowFindings(List.of("--BDSYCD", "AETERM", ""));
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).variableWildcardPrefix("AE")
                .build();
        assertEquals(Set.of("AEBDSYCD", "AETERM"),
                EmptyColumnCollapse.resolveKeepRowFindings(o, ctx));
        EvaluationContext noPrefix = EvaluationContext.builder().table(t).build();
        assertEquals(Set.of("--BDSYCD", "AETERM"),
                EmptyColumnCollapse.resolveKeepRowFindings(o, noPrefix),
                "no prefix: the entry stays as written (nothing to resolve against)");
        assertTrue(EmptyColumnCollapse.resolveKeepRowFindings(null, ctx).isEmpty());
    }

    // ---- walks --------------------------------------------------------------------------------


    @Test
    void plainColumnLeafNamesTakeOnlyEmptyFamilyLeavesOverPlainReferences()
    {
        Set<String> names = EmptyColumnCollapse.plainColumnLeafNames(parse(
                "not empty(A) and is_missing(--B) and non_empty(DM.C) and present($d) and E != \"\""
                        + " and is_present(F) and any(joined(empty(G)))"));
        assertEquals(Set.of("A", "--B", "F"), names, names.toString());
    }


    @Test
    void referencedColumnNamesTakeEveryColumnReferenceAsWritten()
    {
        Set<String> names = EmptyColumnCollapse.referencedColumnNames(
                parse("empty(A) and --B == DM.C and $d == 1 and len(E) > 2"));
        assertEquals(Set.of("A", "--B", "DM.C", "E"), names, names.toString());
    }

    // ---- the truth table ----------------------------------------------------------------------


    @Test
    void truthTable()
    {
        ColumnContent filled = new ColumnContent(Fill.FILLED, Uniformity.MULTI_VALUES);
        ColumnContent allEmptyString = new ColumnContent(Fill.EMPTY, Uniformity.SINGLE_VALUE);
        ColumnContent mixed = new ColumnContent(Fill.EMPTY, Uniformity.MULTI_VALUES);
        ColumnContent missingSingle = new ColumnContent(Fill.MISSING, Uniformity.SINGLE_VALUE);
        ColumnContent missingMulti = new ColumnContent(Fill.MISSING, Uniformity.MULTI_VALUES);
        for (String f : List.of("empty", "non_empty", "present", "is_present"))
        {
            assertFalse(EmptyColumnCollapse.rowConstant(f, filled), f + " on FILLED");
            assertTrue(EmptyColumnCollapse.rowConstant(f, allEmptyString), f + " on all \"\"");
            assertTrue(EmptyColumnCollapse.rowConstant(f, mixed),
                    f + " on \"\" mixed with missing");
            assertTrue(EmptyColumnCollapse.rowConstant(f, missingSingle), f + " on MISSING");
            assertTrue(EmptyColumnCollapse.rowConstant(f, missingMulti), f + " on MISSING (multi)");
        }
        assertFalse(EmptyColumnCollapse.rowConstant("is_missing", filled));
        assertTrue(EmptyColumnCollapse.rowConstant("is_missing", allEmptyString),
                "D50: constant FALSE — \"\" is not missing");
        assertFalse(EmptyColumnCollapse.rowConstant("is_missing", mixed), "row-dependent");
        assertTrue(EmptyColumnCollapse.rowConstant("is_missing", missingSingle));
        assertTrue(EmptyColumnCollapse.rowConstant("is_missing", missingMulti));
    }


    @Test
    void foldableColumnsReadsThePresentColumnsOfTheEvaluationTable()
    {
        IDataTable t = MockTable.of().name("AE").col("A", "", "").col("B", "x", "")
                .colSasMissing("C", "", null).build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        Expr check = parse("empty(A) and empty(B) and is_missing(C) and empty(ZZ) and empty(DM.A)");
        assertEquals(Set.of("A"), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of()),
                "A is all \"\"; B is FILLED; C is row-dependent for is_missing; ZZ absent; DM.A dotted");
        assertEquals(Set.of("A", "C"),
                EmptyColumnCollapse.foldableColumns(parse("empty(A) and empty(C)"), ctx, Set.of()),
                "empty over \"\" mixed with missing is constant TRUE");
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of("a")),
                "Keep_Row_Findings, case-insensitive");
        IDataTable noRows = MockTable.of().name("AE").col("A").build();
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(parse("empty(A)"),
                EvaluationContext.builder().table(noRows).build(), Set.of()), "C5");
    }
}
