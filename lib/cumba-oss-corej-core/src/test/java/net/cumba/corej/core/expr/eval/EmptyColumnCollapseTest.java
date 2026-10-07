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
    void aLeafInsideATargetTableReaderNamesTheOtherTablesColumnAndNeverTriggers()
    {
        // PACK 4 review MED-2: record_count(domain="EX", filter=(empty(EXDOSE))) reads EX, so
        // its empty(EXDOSE) is no leaf over the evaluation table even if AE happens to carry a
        // blank EXDOSE; the same for every domain= reader and the grouped aggregates.
        IDataTable ae = MockTable.of().name("AE").col("AEBDSYCD", "", "").col("EXDOSE", "", "")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae).build();
        // (the reader is a dataset fact — 0 here, no EX is bound — so it sits in an Or with a
        // per-row part: alone it would kill the And and, MED-1, leave no live leaf at all)
        Expr check = parse("empty(AEBDSYCD) and (record_count(domain=\"EX\","
                + " filter=(empty(EXDOSE))) > 0 or AESEV == \"X\")");
        assertEquals(Set.of("AEBDSYCD"), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of()),
                "EXDOSE is EX's column inside the reader, never a trigger on AE");
        assertEquals(Set.of("AEBDSYCD"), FoldCapability.plainColumnLeafNames(check),
                "and no R2 leaf either");
        assertEquals(Set.of(),
                FoldCapability.plainColumnLeafNames(
                        parse("read_value(EXDOSE, domain=\"EX\", filter=(empty(EXSTDTC))) == \"1\""
                                + " and max(EXDOSE, domain=\"EX\", group=[USUBJID]) > 1")));
    }


    @Test
    void foldableColumnsReadsThePresentColumnsOfTheEvaluationTable()
    {
        IDataTable t = MockTable.of().name("AE").col("A", "", "").col("B", "x", "")
                .colSasMissing("C", "", null).build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        Expr check = parse("empty(A) and empty(B) and is_missing(C) and empty(ZZ) and empty(DM.A)");
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of()),
                "B is FILLED and C row-dependent for is_missing: two live leaves still vary, so"
                        + " nothing collapses (owner refinement 2026-10-07)");
        Expr decided = parse("empty(A) and empty(ZZ) and empty(DM.A)");
        assertEquals(Set.of("A", "ZZ"), EmptyColumnCollapse.foldableColumns(decided, ctx, Set.of()),
                "A is all \"\"; ZZ is ABSENT and empty(ZZ) is live (owner 2026-10-07); DM.A is dotted"
                        + " (R5): every live leaf decided");
        assertEquals(Set.of("A", "C"),
                EmptyColumnCollapse.foldableColumns(parse("empty(A) and empty(C)"), ctx, Set.of()),
                "empty over \"\" mixed with missing is constant TRUE");
        assertEquals(Set.of("ZZ"), EmptyColumnCollapse.foldableColumns(decided, ctx, Set.of("a")),
                "Keep_Row_Findings, case-insensitive");
        IDataTable noRows = MockTable.of().name("AE").col("A").build();
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(parse("empty(A)"),
                EvaluationContext.builder().table(noRows).build(), Set.of()), "C5");
    }

    // ---- Owner 2026-10-07: only a polarity-live leaf triggers, absent or blank alike, and only
    // ---- when EVERY live leaf is decided ----------------------------------------------------


    @Test
    void onlyAPolarityLiveLeafTriggersAndOnlyWhenEveryLiveLeafIsDecided()
    {
        IDataTable t = MockTable.of().name("AE").col("AESEV", "SEVERE", "MILD")
                .col("AEBLANK", "", "").col("AEFULL", "x", "y").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        assertEquals(Set.of("AEBDSYCD"),
                EmptyColumnCollapse.foldableColumns(
                        parse("empty(AEBDSYCD) and AESEV == \"SEVERE\""), ctx, Set.of()),
                "owner 2026-10-07: an ABSENT column triggers iff its leaf is polarity-live —"
                        + " empty(absent) is TRUE and decides");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(parse("empty(AEFULL) and not empty(AEBDSYCD)"),
                        ctx, Set.of()),
                "not empty(absent) is FALSE and decides nothing: no trigger (CG0114's CMDOSTXT)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("AEBDSYCD != \"X\" and AESEV == \"SEVERE\""), ctx, Set.of()),
                "any other operator over an absent column stays per row");
        assertEquals(Set.of("AEBLANK"), EmptyColumnCollapse
                .foldableColumns(parse("empty(AEBLANK) and AESEV == \"SEVERE\""), ctx, Set.of()),
                "a PRESENT column blank on every record triggers (C7 D5)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(parse("empty(AEFULL) or not empty(AEBLANK)"),
                        ctx, Set.of()),
                "polarity: not empty(blank) is FALSE on every row and decides nothing in the Or");
        assertEquals(Set.of("AEBLANK"),
                EmptyColumnCollapse.foldableColumns(parse("not non_empty(AEBLANK)"), ctx, Set.of()),
                "polarity through the Not of the leaf: TRUE on every row, a trigger");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(parse("empty(AEBLANK) and AESEV == \"SEVERE\""),
                        ctx, Set.of("aeblank")),
                "Keep_Row_Findings: never a trigger (R1)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("any(joined(empty(AEBDSYCD))) and AESEV == \"SEVERE\""), ctx,
                        Set.of()),
                "a quantifier leaf reads the joined rows: never a trigger (R5)");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("var_exists(AEBDSYCD) and AESEV == \"SEVERE\""), ctx, Set.of()),
                "a presence fact is not an empty-family leaf: not a trigger");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(parse("empty(AEBLANK) and empty(AEFULL)"), ctx,
                        Set.of()),
                "refinement: AEFULL's live leaf varies per record — no collapse while any live leaf"
                        + " is undecided");
        assertEquals(Set.of("AEBLANK", "AEBDSYCD"),
                EmptyColumnCollapse.foldableColumns(parse("empty(AEBLANK) and empty(AEBDSYCD)"),
                        ctx, Set.of()),
                "every live leaf decided (one blank, one absent): both are named");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(parse("empty(AEBLANK) == true"), ctx, Set.of()),
                "L6: a boolean leaf under a comparison is not in boolean position — not live");
    }

    // ---- PACK 4 confirmation look: a leaf is live only where blanking it can change the verdict


    @Test
    void aLeafInABranchAPresenceFactKillsIsNotLive()
    {
        // MED-1 (owner 2026-10-07: never because another column is absent): an And with a
        // dataset-constant FALSE sibling is FALSE whatever its leaves read; an Or with a constant
        // TRUE sibling is TRUE whatever they read — their empty-family leaves decide nothing.
        IDataTable presp = MockTable.of().name("SV").col("SVPRESP", "Y", "N").col("VISIT", "A", "B")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(presp).build();
        Expr sv = parse(
                "var_exists(\"SVPRESP\") and SVPRESP == \"Y\" or not var_exists(\"SVPRESP\")"
                        + " and empty(SVUPDES)");
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(sv, ctx, Set.of()),
                "SVPRESP present: the SVUPDES branch is dead, no live leaf, no collapse");
        assertTrue(EmptyColumnCollapse.everyLiveLeafDecided(sv, ctx, Set.of()),
                "and nothing left to vary for the absent fold either");
        assertEquals(Set.of(),
                EmptyColumnCollapse.foldableColumns(
                        parse("SVPRESP == \"Y\" and (var_exists(\"SVPRESP\") or empty(SVUPDES))"),
                        ctx, Set.of()),
                "an Or with a constant-TRUE sibling: its leaf decides nothing either");
        IDataTable noPresp = MockTable.of().name("SV").col("VISIT", "A", "B").build();
        EvaluationContext ctx2 = EvaluationContext.builder().table(noPresp).build();
        assertEquals(Set.of("SVUPDES"), EmptyColumnCollapse.foldableColumns(sv, ctx2, Set.of()),
                "control — SVPRESP absent: the branch is live and its absent SVUPDES decides");
    }


    @Test
    void aKeepRowFindingsLeafNeitherTriggersNorBlocks()
    {
        // LOW-3 (the FDA-SD1320 shape): a Keep_Row_Findings column's leaf is not counted as live —
        // blank, it does not trigger; filled on some records, it does not hold the other live
        // leaves' collapse back.
        IDataTable t = MockTable.of().name("AE").col("AEBLANK", "", "").col("AEFULL", "x", "")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        Expr check = parse("empty(AEBLANK) and empty(AEFULL)");
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of()),
                "control: AEFULL varies, so nothing collapses");
        assertEquals(Set.of("AEBLANK"),
                EmptyColumnCollapse.foldableColumns(check, ctx, Set.of("AEFULL")),
                "AEFULL kept per row: it neither triggers nor blocks AEBLANK's collapse");
        assertEquals(Set.of(), EmptyColumnCollapse.foldableColumns(check, ctx, Set.of("AEBLANK")),
                "AEBLANK kept: not a trigger, and AEFULL still varies");
    }
}
