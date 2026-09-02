package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * E9 — native-compiler tests for the per-record {@code library_variable_code_pair_matches} accessor
 * (rule type {@code Value Check against Library Metadata}, FDA-CT2003 / PMDA-CT2003). The current
 * variable is the code variable ({@code LBTESTCD}); its paired decode variable ({@code LBTEST}) is
 * derived by dropping the trailing {@code "CD"}. Each value is mapped to its CDISC-CT term concept
 * id; the accessor is {@code true} when the code and decode concept ids match. Paired
 * {@code --TESTCD} / {@code --TEST} terms share their concept id, so a mismatch flags a broken
 * pairing.
 */
class LibraryCodePairMatchesNativeTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    /** LBTESTCD codes and LBTEST decodes, paired through their shared concept id. */
    private static StubMetadataProvider library()
    {
        return new StubMetadataProvider()
                .codeMap("LB", "LBTESTCD", Map.of("ALB", "C64431", "GLUC", "C105585"))
                .codeMap("LB", "LBTEST", Map.of("Albumin", "C64431", "Glucose", "C105585"));
    }


    private static EvaluationContext ctx(IDataTable t, StubMetadataProvider library)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("LB")
                .libraryProvider(library).variables(Map.of("variable_name", "LBTESTCD")).build();
    }


    @Test
    void firesOnlyOnGenuineDecodeMismatch()
    {
        // r0 ALB/Albumin (C64431==C64431) match -> no fire; r1 ALB/Glucose (C64431!=C105585)
        // mismatch -> fire; r2 empty code -> null; r3 code not a term -> null; r4 decode not a
        // term -> null.
        IDataTable t = MockTable.of().col("LBTESTCD", "ALB", "ALB", "", "ZZZ", "ALB")
                .col("LBTEST", "Albumin", "Glucose", "Albumin", "Albumin", "NotATerm").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and library_variable_code_pair_matches(varname()) "
                        + "== false"),
                ctx(t, library()));
        assertFalse(bits.get(0), "ALB<->Albumin share concept id -> match -> no fire");
        assertTrue(bits.get(1), "ALB<->Glucose concept ids differ -> mismatch -> fire (CT2003)");
        assertFalse(bits.get(2), "empty code value -> accessor null -> no fire");
        assertFalse(bits.get(3), "code not a codelist term -> accessor null -> no fire");
        assertFalse(bits.get(4), "decode not a codelist term -> accessor null -> no fire");
    }


    @Test
    void decodeVariableAbsent_noFire()
    {
        // No LBTEST column: the paired decode variable is absent -> accessor null for every row.
        IDataTable t = MockTable.of().col("LBTESTCD", "ALB", "GLUC").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and library_variable_code_pair_matches(varname()) "
                        + "== false"),
                ctx(t, library()));
        assertTrue(bits.isEmpty(), "paired decode variable absent -> nothing fires");
    }


    @Test
    void nullLibraryProviderProducesNoFire()
    {
        // With no Library provider the accessor is null for every row -> nothing fires (the
        // rule-level SKIPPED status is driven by the LIBRARY provider gate; here we confirm the
        // accessor null-propagates).
        IDataTable t = MockTable.of().col("LBTESTCD", "ALB").col("LBTEST", "Glucose").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and library_variable_code_pair_matches(varname()) "
                        + "== false"),
                EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("LB")
                        .variables(Map.of("variable_name", "LBTESTCD")).build());
        assertTrue(bits.isEmpty(), "no Library provider -> accessor null -> no fire");
    }


    @Test
    void requiresLibraryLevel()
    {
        // The provider-level scan must report LIBRARY so RuleRunner SKIPs the rule when no Library
        // metadata is supplied.
        assertTrue(
                MetadataExprScan
                        .providerLevelsUsed(
                                parse("library_variable_code_pair_matches(varname()) == false"))
                        .contains(MetadataLevel.LIBRARY),
                "library_variable_code_pair_matches requires the LIBRARY provider");
    }
}
