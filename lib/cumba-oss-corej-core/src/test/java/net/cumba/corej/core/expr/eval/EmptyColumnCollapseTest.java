package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Outcome;
import net.cumba.datatable.ColumnContent;
import net.cumba.datatable.ColumnContent.Fill;
import net.cumba.datatable.ColumnContent.Uniformity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@link EmptyColumnCollapse}'s static surface: the truth table
 * ({@link EmptyColumnCollapse#rowConstant}) cell by cell, the resolution of a {@code --} template,
 * {@link EmptyColumnCollapse#foldableColumns} on a table, and the P3 absent-column triggers
 * ({@code PLAN-message-folded-corpus-alignment} row 2). The load-time classifier moved to
 * {@link FoldCapabilityTest}.
 */
class EmptyColumnCollapseTest
{

    private static Expr parse(String text)
    {
        return CheckExpressionParser.parse(text);
    }

    // ---- Keep_Row_Findings --------------------------------------------------------------------


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
        assertEquals(Set.of("A", "ZZ"), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of()),
                "A is all \"\"; B is FILLED; C is row-dependent for is_missing; ZZ is ABSENT (P3:"
                        + " empty over an absent column folds); DM.A is dotted (R5)");
        assertEquals(Set.of("A", "C"),
                EmptyColumnCollapse.foldableColumns(parse("empty(A) and empty(C)"), ctx, Set.of()),
                "empty over \"\" mixed with missing is constant TRUE");
        assertEquals(Set.of("ZZ"), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of("a")),
                "Keep_Row_Findings, case-insensitive");
        IDataTable noRows = MockTable.of().name("AE").col("A").build();
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(parse("empty(A)"),
                EvaluationContext.builder().table(noRows).build(), Set.of()), "C5");
    }

    // ---- P3: an absent column is a trigger for any operator ----------------------------------


    @Test
    void anAbsentColumnOfAnEmptyFamilyLeafIsATriggerAndNothingElseIs()
    {
        IDataTable t = MockTable.of().name("AE").col("AESEV", "SEVERE", "MILD").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        assertEquals(Set.of("AEBDSYCD"),
                EmptyColumnCollapse.foldableColumns(
                        parse("empty(AEBDSYCD) and AESEV == \"SEVERE\""), ctx, Set.of()),
                "P3: empty over an absent column folds as over a column of fill MISSING");
        assertEquals(Set.of("AEBDSYCD"),
                EmptyColumnCollapse.foldableColumns(
                        parse("non_empty(AEBDSYCD) and AESEV == \"SEVERE\""), ctx, Set.of()),
                "the whole empty family (the row evaluation decides the polarity)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("AEBDSYCD != \"X\" and AESEV == \"SEVERE\""), ctx, Set.of()),
                "any other operator over an absent column stays per row (D111; the wide P3 was not"
                        + " taken — an absent guard column would collapse per-record findings)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("empty(AEBDSYCD) and AESEV == \"SEVERE\""), ctx, Set.of("aebdsycd")),
                "Keep_Row_Findings: never a trigger, absent or present (R1)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("any(joined(empty(AEBDSYCD))) and AESEV == \"SEVERE\""), ctx,
                        Set.of()),
                "a quantifier leaf reads the joined rows: never a trigger (R5)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("var_exists(AEBDSYCD) and AESEV == \"SEVERE\""), ctx, Set.of()),
                "a presence fact is not an empty-family leaf: not a trigger");
    }
}
