package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAErrorKind;
import net.cumba.corej.core.expr.typed.StageAFinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b VA ({@code PLAN-engine-gap-value-addressed} V2, Q8 as amended by O15): a
 * {@code var_*} accessor admits a <b>computed</b> name and a computed {@code dataset=} — each the
 * per-row value its expression answers — beside the literal forms, which keep their broadcast plan.
 * A string literal is a name, a bare column stays refused in both slots (with the hint naming the
 * {@code str(...)} spelling for {@code dataset=}), a bound computed name answers as the inline one
 * (G1), and the level is per row.
 */
class MetadataAccessorComputedNameTest
{

    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("DOMAIN", "AE", "AE").col("USUBJID", "A", "B")
                .col("AETERM", "x", "y").col("AEACNOTH", "n", "n")
                .col("NAMEOF", "AETERM", "AEACNOTH").colMeta("AETERM", "Reported Term", 200, null)
                .colMeta("AEACNOTH", "Other Action", 8, null).build();
    }


    /** The continuation rows of row H: PARENT names the parent column, RDOMAIN its dataset. */
    private static IDataTable supp()
    {
        return MockTable.of().name("SUPPAE")
                .col("QNAM", "AETERM1", "AEACNOT1", "AETERM1", "AETERM1")
                .col("RDOMAIN", "AE", "AE", "ZZ", "")
                .col("PARENT", "AETERM", "AEACNOT", "AETERM", "AETERM")
                .col("QLABEL", "Wrong", "Other Action", "x", "x").build();
    }


    private static EvaluationContext ctx(IDataTable primary)
    {
        return EvaluationContext.builder().table(primary)
                .domainName(primary.getMetaData().getName())
                .datasetResolver(new ReferencedRecordCountTest.Inventory(ae(), supp())).build();
    }


    private static Vector vector(String text, IDataTable primary)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(text),
                ctx(primary));
        assertNotNull(v);
        return v;
    }


    private static List<@Nullable Object> cells(String text, IDataTable primary)
    {
        Vector v = vector(text, primary);
        List<@Nullable Object> out = new ArrayList<>();
        for (int row = 0; row < primary.getRowCount(); row++)
        {
            out.add(v.value(row).cell().isMissingOrInvalid() ? null : v.value(row).resolved());
        }
        return out;
    }


    private static List<StageAErrorKind> findings(String text)
    {
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, CheckExpressionParser.parse(text));
        return StageAChecker.check(new Rule(), levels).findings().stream().map(StageAFinding::kind)
                .toList();
    }


    private static Rule load(String bindings, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString(
                    "{\"rules\":{\"X-1\":{" + "\"Core\":{\"Id\":\"X-1\"}," + "\"Bindings\":["
                            + bindings + "]," + "\"Check\":{\"expression\":\"" + check + "\"},"
                            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"QNAM\"]}}}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + check, e);
        }
    }


    @Test
    void aComputedNameAndDatasetReadThatVariableOfThatDatasetPerRow()
    {
        List<@Nullable Object> labels = cells(
                "var_label(str(PARENT), \"DATA\", dataset=str(RDOMAIN))", supp());
        assertEquals("Reported Term", labels.get(0));
        assertNull(labels.get(1), "AEACNOT is no AE column: missing (D4)");
        assertNull(labels.get(2), "an unknown dataset: missing");
        assertNull(labels.get(3), "a blank dataset value: missing");
        assertEquals(java.util.Arrays.asList("Char", null, null, null),
                cells("var_type(str(PARENT), \"DATA\", dataset=str(RDOMAIN))", supp()));
    }


    @Test
    void aComputedNameAloneReadsTheCurrentDataset()
    {
        assertEquals(List.of("Reported Term", "Other Action"),
                cells("var_label(str(NAMEOF))", ae()), "the one-argument form at DATA");
        assertEquals(List.of("Reported Term", "Other Action"),
                cells("var_label(str(NAMEOF), \"DATA\")", ae()));
    }


    @Test
    void theLiteralFormsKeepTheirBroadcastPlan()
    {
        Vector literal = vector("var_label(\"AETERM\", \"DATA\", dataset=\"AE\")", supp());
        assertInstanceOf(ConstVector.class, literal, "the literal form is unchanged");
        assertEquals("Reported Term", literal.value(0).resolved());
        assertEquals("Reported Term", literal.value(3).resolved());
    }


    @Test
    void aBareColumnStaysRefusedInBothSlotsWithTheStringSpelling()
    {
        RuleDefinitionException name = assertThrows(RuleDefinitionException.class,
                () -> vector("var_label(AETERM, \"DATA\", dataset=\"AE\")", supp()));
        assertTrue(name.getMessage().contains("name must be a string"), name.getMessage());
        RuleDefinitionException dataset = assertThrows(RuleDefinitionException.class,
                () -> vector("var_label(\"AETERM\", \"DATA\", dataset=RDOMAIN)", supp()));
        assertTrue(dataset.getMessage().contains("dataset=str(RDOMAIN)"), dataset.getMessage());
        assertEquals(List.of(StageAErrorKind.NON_STATIC_NAME),
                findings("var_label(AETERM, \"DATA\", dataset=\"AE\") == \"x\""));
        assertEquals(List.of(StageAErrorKind.NON_STATIC_NAME),
                findings("var_label(\"AETERM\", \"DATA\", dataset=RDOMAIN) == \"x\""));
        Rule rule = load("", "var_label(\\\"AETERM\\\", \\\"DATA\\\", dataset=RDOMAIN) != QLABEL");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("dataset=str(RDOMAIN)"), rule.getLoadError());
    }


    @Test
    void stageAAdmitsTheComputedFormsAndTypesTheNameAString()
    {
        assertEquals(List.of(),
                findings("var_label(str(PARENT), \"DATA\", dataset=str(RDOMAIN)) != QLABEL"));
        assertEquals(List.of(), findings("var_label(str(NAMEOF)) != \"x\""));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                findings("var_label(len(PARENT), \"DATA\") != \"x\""), "a number is not a name");
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                findings("var_label(\"AETERM\", \"DATA\", dataset=len(RDOMAIN)) != \"x\""));
    }


    @Test
    void theComputedFormIsPerRowForTheLevelDerivation()
    {
        assertTrue(DomainScan.infer(
                CheckExpressionParser
                        .parse("var_label(str(PARENT), \"DATA\", dataset=str(RDOMAIN))"),
                BindingDomains.NONE).rowCursor());
        assertTrue(DomainScan
                .infer(CheckExpressionParser.parse("var_label(str(NAMEOF))"), BindingDomains.NONE)
                .rowCursor());
        assertFalse(DomainScan
                .infer(CheckExpressionParser.parse(
                        "var_label(\"AETERM\", \"DATA\", dataset=\"AE\")"), BindingDomains.NONE)
                .rowCursor());
    }


    @Test
    void aBoundComputedNameAnswersAsTheInlineOne()
    {
        String check = "not empty(RDOMAIN) and var_label(%s, \\\"DATA\\\", dataset=%s) != QLABEL";
        Rule inline = load("", check.formatted("str(PARENT)", "str(RDOMAIN)"));
        Rule bound = load(
                "{\"name\":\"$p\",\"expression\":\"str(PARENT)\"},"
                        + "{\"name\":\"$d\",\"expression\":\"str(RDOMAIN)\"}",
                check.formatted("$p", "$d"));
        assertNull(inline.getLoadError(), inline.getLoadError());
        assertNull(bound.getLoadError(), bound.getLoadError());
        ReferencedRecordCountTest.Inventory inventory = new ReferencedRecordCountTest.Inventory(
                ae(), supp());
        List<String> a = RuleRunnerCalls.execute(inline, supp(), inventory).getViolations().stream()
                .map(f -> f.getValues().get("QNAM")).toList();
        List<String> b = RuleRunnerCalls.execute(bound, supp(), inventory).getViolations().stream()
                .map(f -> f.getValues().get("QNAM")).toList();
        // register: `X != "Y"` on a missing value fires — the wrong label and the two missing
        // labels (an unknown column, an unknown dataset) fire; the blank dataset is guarded
        assertEquals(List.of("AETERM1", "AEACNOT1", "AETERM1"), a);
        assertEquals(a, b, "G1: bound and inline answer alike");
    }
}
