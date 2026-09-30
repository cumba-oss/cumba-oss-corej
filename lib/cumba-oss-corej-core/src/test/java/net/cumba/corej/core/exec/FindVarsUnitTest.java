package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} §2.4 at the unit: {@link FindVars} against the
 * {@code Requirements.Variables} matcher on the same table (the cross-surface assertion — one
 * parser, one matcher), the dataset-level evaluation count of a literal entry, and the load-time
 * entry checks.
 */
class FindVarsUnitTest
{

    private static IDataTable primary()
    {
        return RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2").str("TRT01P", "X", "X", "X")
                .str("TRT02P", "Y", "Y", "Y").str("TRT1P", "Z", "Z", "Z").str("TRTPG1", "", "", "")
                .str("TRTSEQP", "", "", "").build();
    }


    /**
     * ⭐ The cross-surface assertion: for every entry form, {@code find_vars(e)} returns exactly the
     * columns entry {@code e} matches in {@code Requirements.Variables} on the same table (the
     * Requirements side upper-cases; the table's names are upper-case already).
     */
    @Test
    void findVarsSelectsExactlyWhatTheRequirementsEntrySelects()
    {
        IDataTable t = primary();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        for (String entry : List.of("TRTxxP", "TRTPGy", "TRT*P", "TRT?P", "/TRT[0-9]+P/", "TRT01P",
                "TRT09P", "trtxxp", "TRTwP", "TRTSEQP"))
        {
            List<String> viaFindVars = FindVars.names(ctx, entry, true).stream()
                    .map(n -> n.toUpperCase(Locale.ROOT)).toList();
            List<String> viaRequirements = List.copyOf(
                    ScopeMatcher.resolveEntryNames(entry, t.getMetaData(), "AE", null).names());
            assertEquals(viaRequirements, viaFindVars.stream().sorted().toList(), entry);
        }
        assertEquals(List.of("TRT01P", "TRT02P"), FindVars.names(ctx, "TRTxxP", true),
                "and find_vars keeps the dataset's column order");
    }


    /** Level: a literal entry is one dataset-level evaluation — measured, not assumed. */
    @Test
    void aLiteralEntryIsMatchedOncePerExecution()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(primary()).build();
        assertEquals(3, NativeExprEvaluator
                .evaluate(CheckExpressionParser
                        .parse("not empty(USUBJID) and count(find_vars(\"TRTxxP\")) == 2"), ctx)
                .cardinality());
        assertEquals(1, ctx.getWildcardColumns().computeCount(),
                "one column match for three rows: the literal entry is broadcast, not per row");
    }


    @Test
    void theLoadTimeEntryChecksNameTheRightSpelling()
    {
        assertNull(FindVars.literalEntryError("ADSL.TRTxxA"));
        assertNull(FindVars.literalEntryError("ADSL./TRT[0-9]+A/"));
        assertNull(FindVars.literalEntryError("/^TRT.+A$/"), "an anchored regex is fine");
        String dotted = FindVars.literalEntryError("/ADSL.TRT01A/");
        assertNotNull(dotted);
        assertTrue(dotted.contains("\"ADSL./TRT01A/\""), dotted);
        String escaped = FindVars.literalEntryError("/ADSL\\.TRT01A/");
        assertNotNull(escaped);
        assertTrue(escaped.contains("\"ADSL./TRT01A/\""), escaped);
        assertNotNull(FindVars.literalEntryError("TRTxxP:N"));
        assertNotNull(FindVars.literalEntryError("ADSL.--SEQ"));
        assertNotNull(FindVars.literalEntryError("/TRT[/"));
        assertTrue(FindVars.isRegexEntry("/^--.*SEQ$/"));
        assertTrue(FindVars.isRegexEntry("ADSL./TRT0[12]A/"));
        assertFalse(FindVars.isRegexEntry("TRTxxP"));
    }


    @Test
    void anOutputVariablePatternIsRecognisedByShapeOnly()
    {
        assertTrue(FindVars.isOutputVariablePattern("ADSL.TRTxxA"));
        assertTrue(FindVars.isOutputVariablePattern("TRT*P"));
        assertTrue(FindVars.isOutputVariablePattern("ADSL./TRT0[12]N/"));
        for (String kept : List.of("USUBJID", "ADSL.TRT01A", "$b", "!AESEV", "variable_name",
                "ADSL.AP${*}SDT", "library_variable_label", "*DTM", "*GRyN", "RELREC.**TERM"))
        {
            assertFalse(FindVars.isOutputVariablePattern(kept), kept);
        }
    }


    /**
     * A pattern-shaped entry that is itself the name of a column IS that column — kept verbatim.
     */
    @Test
    void anOutputVariableThatNamesAColumnIsKept()
    {
        IDataTable t = RealTables.of("ADSL").str("USUBJID", "S1").str("TRTxxP", "Placebo")
                .str("TRT01P", "A").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        assertEquals(List.of("USUBJID", "TRTxxP"),
                FindVars.expandOutputVariables(List.of("USUBJID", "TRTxxP"), ctx));
        assertEquals(List.of("TRT01P"), FindVars.expandOutputVariables(List.of("TRT0?P"), ctx),
                "a glob that names no column expands to the columns it matches");
    }
}
