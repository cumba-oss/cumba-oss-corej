package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@link DomainScan} — the §3.1 leaf table and the §3.2 join, on the raised {@link Expr} IR.
 */
class DomainScanTest
{

    private static Domain infer(String source)
    {
        return DomainScan.infer(CheckExpressionParser.parse(source), BindingDomains.NONE);
    }


    private static Domain infer(String source, BindingDomains kinds)
    {
        return DomainScan.infer(CheckExpressionParser.parse(source), kinds);
    }


    private static BindingDomains kinds(String ref, Domain domain)
    {
        return r -> r.equals(ref) ? domain : Domain.DATASET;
    }


    @Test
    void datasetScopedLeavesDemandNoCursor()
    {
        assertEquals(Domain.DATASET, infer("ds_exists(\"EX\")"));
        assertEquals(Domain.DATASET, infer("not ds_exists(\"EX\")"));
        assertEquals(Domain.DATASET, infer("var_exists(\"AESEQ\")"));
        assertEquals(Domain.DATASET, infer("var_exists(\"AE.AESEQ\")"));
        assertEquals(Domain.DATASET, infer("ds_label(\"DATA\") == \"\""));
        assertEquals(Domain.DATASET, infer("ds_label(\"EX\", \"DEFINE\") != ds_label(\"DATA\")"));
        assertEquals(Domain.DATASET, infer("record_count() > 0"));
        assertEquals(Domain.DATASET,
                infer("library_available() and dictionary_available(\"MEDDRA\")"));
        assertEquals(Domain.DATASET, infer("$x == 0"));
        assertEquals(Domain.DATASET, infer("\"A\" in [\"A\", \"B\"]"));
        // An explicit literal variable name is a dataset-level fact about a named column.
        assertEquals(Domain.DATASET, infer("var_label(\"AESEV\", \"DATA\") == \"Severity\""));
    }


    @Test
    void wholeColumnVerdictOperatorsAbsorbTheirOperands()
    {
        assertEquals(Domain.DATASET, infer("has_same_values(MHCAT)"));
        assertEquals(Domain.DATASET, infer("not contains_all(TSPARMCD, keys=[TITLE, SSTDTC])"));
        assertEquals(Domain.DATASET,
                infer("var_exists(\"TSPARMCD\") and not contains_all(TSPARMCD, keys=[TITLE])"));
        assertEquals(Domain.DATASET, infer("not is_ordered_subset_of($a, $b)"));
        // Absorption is per call: a sibling row read still demands the row cursor.
        assertEquals(Domain.ROW, infer("not empty(MHCAT) and has_same_values(MHCAT)"));
    }


    @Test
    void variableCursorLeaves()
    {
        assertEquals(Domain.VARIABLE, infer("varname() == \"AESEV\""));
        assertEquals(Domain.VARIABLE, infer("var_label(\"DATA\") != var_label(\"LIBRARY\")"));
        assertEquals(Domain.VARIABLE, infer("var_label(varname(), \"DATA\") == \"\""));
        assertEquals(Domain.VARIABLE, infer("var_exists(varname())"));
        assertEquals(Domain.VARIABLE, infer("max_value_length() > var_length(\"DEFINE\")"));
        assertEquals(Domain.VARIABLE, infer("not library_variable_code_pair_matches(varname())"));
        assertEquals(Domain.VARIABLE, infer("variable_name in $model_variables"));
        assertEquals(Domain.VARIABLE, infer("$vmr == \"x\"", kinds("$vmr", Domain.VARIABLE)));
        assertEquals(Domain.VARIABLE, infer("ds_exists(\"EX\") and varname() == \"AESEV\""));
    }


    @Test
    void rowCursorLeaves()
    {
        assertEquals(Domain.ROW, infer("EXDOSE > 0"));
        assertEquals(Domain.ROW, infer("empty(AESEV)"));
        assertEquals(Domain.ROW, infer("ds_exists(\"EX\") and EXDOSE > 0"));
        assertEquals(Domain.ROW, infer("DM.RFSTDTC < EXSTDTC"));
        // W5 (PLAN-grouped-aggregate-functions D-W5-7): a grouped aggregate function answers one
        // value per primary row, even under domain= (before the domain= arm reads it DATASET).
        assertEquals(Domain.ROW,
                infer("max_date(DSSTDTC, domain=DS, group=[USUBJID]) != \"2020-01-01\""));
        assertEquals(Domain.ROW, infer("max(AVAL, group=[USUBJID], filter=(ABLFL == \"Y\")) > 1"));
        assertEquals(Domain.ROW,
                infer("read_value(DSSTDTC, domain=DS, group=[USUBJID], mode=\"FIRST\") != \"x\""));
        assertEquals(Domain.DATASET,
                infer("read_value(DSSTDTC, domain=DS, mode=\"FIRST\") != \"x\""),
                "ungrouped, read_value stays the W2a dataset-level read");
        assertEquals(Domain.ROW, infer("--DTC != \"\""));
        assertEquals(Domain.ROW, infer("$grp > 1", kinds("$grp", Domain.ROW)));
        assertEquals(Domain.ROW, infer("var_exists(\"AP${APERIOD}SDT\")"));
        assertEquals(Domain.ROW, infer("date(SEENDTC) > DS.DSSTDTC"));
        assertEquals(Domain.ROW,
                infer("tuple(VISIT, VISITNUM) not in distinct([VISIT, VISITNUM], domain=\"TV\")"));
    }


    @Test
    void cellLeaves()
    {
        assertEquals(Domain.CELL, infer("value() != \"\""));
        assertEquals(Domain.CELL, infer(
                "record_count() > 0 and var_label(varname(), \"DATA\") != \"\" and value() != \"\""));
        assertEquals(Domain.CELL, infer("len(value()) > vlm_length(varname())"));
        assertEquals(Domain.CELL,
                infer("variable_value not in var_codelist_coded_values(\"LIBRARY\")"));
        assertEquals(Domain.CELL, infer("var_label(\"DATA\") != \"\" and EXDOSE > 0"));
        assertEquals(Domain.CELL,
                infer("varname() == \"A\" and $grp > 1", kinds("$grp", Domain.ROW)));
    }


    @Test
    void theTwoParameterisedOperationsFollowTheirResultKind()
    {
        // model_class= is a parameter on get_model_filtered_variables — a registry aggregate
        // since wave 4 — still one list for the dataset ⇒ {}.
        assertEquals(Domain.DATASET, infer("not contains_all($dataset_vars,"
                + " get_model_filtered_variables(model_class=\"Findings\"))"));
        // relation= is a parameter on the row-level has_next_corresponding_record ⇒ {ROW}.
        assertEquals(Domain.ROW, infer(
                "not has_next_corresponding_record(SEENDTC, SESTDTC, ordering=SESTDTC, within=USUBJID, relation=\"<=\")"));
    }


    @Test
    void inlineOperationsFollowTheirResultKindNotTheirArguments()
    {
        assertEquals(Domain.DATASET, infer("record_count(domain=\"DM\") > 0"));
        assertEquals(Domain.ROW, infer("record_count(group=[USUBJID]) > 1"));
        assertEquals(Domain.ROW, infer("dy(AESTDTC, DM.RFSTDTC) < 0"));
        assertEquals(Domain.ROW, infer(
                "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\"MEDDRA\") == false"));
    }


    /**
     * Wave 4b ({@code PLAN-scalar-metadata-functions} D-W4b-1): the per-variable function reads the
     * variable cursor — its one value is projected per variable — although it names another dataset
     * through {@code domain=} (which alone would read DATASET, W2a's arm) and is an aggregate; the
     * other five scalar functions are dataset facts.
     */
    @Test
    void theScalarPerVariableFunctionReadsTheVariableCursor()
    {
        assertEquals(Domain.VARIABLE,
                infer("cross_dataset_variable_metadata(\"label\", domain=\"ADSL\") == \"\""));
        assertEquals(Domain.VARIABLE,
                infer("cross_dataset_variable_metadata(\"data_type\", domain=\"*\") == \"\""));
        for (String call : List.of("domain_is_custom()", "dataset_class_from_library()",
                "extract_metadata(\"file_format\")", "variable_count(\"--LNKGRP\")",
                "variable_count(name_pattern=\"^.+FL$\")",
                "column_series_metadata(\"COVAL\", name_pattern=\"^COVAL\\\\d+$\")"))
        {
            assertEquals(Domain.DATASET, infer(call + " == 1"), call);
        }
    }


    /**
     * {@link BindingDomains#forRule} over a loaded rule's compiled bindings. Until runbook W8 this
     * pinned {@code forOperations} / {@code kindOf} over declared operation records (all SCALAR
     * since W7); the carrier is gone, so the pin is the compiled bindings' domains, and the two
     * defaults it kept — a reference no binding defines, and a rule with no bindings, read
     * dataset-constant.
     */
    @Test
    void bindingDomainsReadTheCompiledBindings() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString("""
                {"rules":{"x":{"Core":{"Id":"T-DS1"},
                 "Check":{"expression":"AESEQ not in $vm and contains($all, \\"1\\")"},
                 "Bindings":[{"name":"$all","expression":"distinct(AESEQ)"},
                   {"name":"$vm","expression":"distinct(AESTDTC, group=[USUBJID])"}]}}}""")
                .getRules().values().iterator().next();
        assertNull(rule.getLoadError(), rule.getLoadError());
        BindingDomains k = BindingDomains.forRule(rule);
        assertEquals(Domain.DATASET, k.domainOf("$all"), "the ungrouped list is dataset-level");
        assertEquals(Domain.ROW, k.domainOf("$vm"), "the grouped call answers per primary row");
        assertEquals(Domain.DATASET, k.domainOf("$dangling"));
        assertEquals(Domain.DATASET, BindingDomains.forRule(new Rule()).domainOf("$x"));
    }


    @Test
    void distinctReadsRowWhenGroupedAndDatasetOtherwise()
    {
        // Runbook W7 (PLAN-distinct-function D-W7-12): the retired DISTINCT operation's
        // ResultKind, carried over — the grouped call answers per primary row, the ungrouped one
        // (local or under domain=) one dataset-level list; its target and filter are parameters
        // of the target table, never ROW reads of the primary.
        assertEquals(Domain.ROW, infer("AESEQ not in distinct(AESEQ, group=[USUBJID])"));
        assertEquals(Domain.DATASET, infer("contains(distinct(AESEQ), \"1\")"));
        assertEquals(Domain.DATASET,
                infer("contains(distinct(AESEQ, domain=\"DM\", filter=(SEX == \"F\")), \"1\")"));
        assertEquals(Domain.DATASET,
                infer("contains(distinct([AESEQ, AETERM], domain=DM), \"1\")"));
        // referenced_dataset_variables reads its column per row (the retired value_is_reference
        // form's PER_ROW), through the operand join.
        assertEquals(Domain.ROW, infer("IDVAR not in referenced_dataset_variables(RDOMAIN)"));
    }


    /**
     * {@code var_is_null} is a {@linkplain BroadcastFold#BROADCAST_COLUMN_PREDICATES broadcast
     * column predicate}: {@code ExprCompiler.compileVarIsNull} computes one boolean and paints it
     * over every row, so its named-column argument must not demand the row cursor.
     *
     * <p>
     * Registered 2026-09-11. Until then the call matched none of the {@link DomainScan#call}
     * branches, fell through to the generic operand scan, and {@code FDA-SD9714}
     * ({@code not var_is_null(--ORRES) and not var_exists("--ORRESU")}) inferred {@code {ROW}} —
     * one finding per record from a rule minted to report a dataset-wide absence once.
     * </p>
     */
    @Test
    void varIsNullIsABroadcastColumnPredicate()
    {
        // The evidence case: a bare column reference inferred {ROW} before registration.
        assertEquals(Domain.DATASET, infer("var_is_null(--ORRES)"));
        assertEquals(Domain.DATASET, infer("var_is_null(AEORRES)"));
        // The defect in full: SD9714's own Check.
        assertEquals(Domain.DATASET,
                infer("not var_is_null(--ORRES) and not var_exists(\"--ORRESU\")"));
        // A pin, not evidence: the string-literal form already reached DATASET through
        // DomainScan.literal() before registration, so its green says nothing about the fix.
        assertEquals(Domain.DATASET, infer("var_is_null(\"AEORRES\")"));
        // The cursor form must keep its per-variable answer — one finding per variable is exactly
        // right for FDA-SD1078 / PMDA-SD1078 / FDA-SD1149 ("every Permissible variable that is
        // entirely null"), which is why the branch reuses existsCall rather than answering DATASET.
        assertEquals(Domain.VARIABLE, infer("var_is_null(varname())"));
        assertEquals(Domain.VARIABLE, infer("var_is_null(variable_name)"));
        assertEquals(Domain.VARIABLE,
                infer("varname() in $permissible_variables and var_is_null(varname())"));
        // A ${...} driver template is a per-row substitution, exactly as for the exists family.
        // ⚠ This is a CHANGE: the unregistered call used to answer DATASET here. No corpus rule
        // uses the form, and compileVarIsNull never expands the template, so ROW is both the
        // consistent and the conservative answer.
        assertEquals(Domain.ROW, infer("var_is_null(\"AP${APERIOD}SDT\")"));
    }


    /**
     * The argument shapes {@code ExprCompiler.compileVarIsNull} <em>rejects</em> must keep falling
     * through to the generic operand scan.
     *
     * <p>
     * &#9888; This is the crash a name-and-arity-only guard would have introduced, not a
     * hypothetical: {@link DomainScan}'s shared {@code existsCall} helper ends in
     * {@code (String) ((Expr.Lit) arg).value()} and casts blind, and {@code DomainScan.infer} runs
     * at <b>load</b> for every rule — so one unguarded shape would have failed the whole corpus
     * with a {@link ClassCastException}. Both shapes answered without throwing before the change
     * and must still.
     * </p>
     */
    @Test
    void unsupportedVarIsNullArgumentShapesStillInferWithoutThrowing()
    {
        assertEquals(Domain.ROW, infer("var_is_null(upper(AEORRES))"));
        assertEquals(Domain.DATASET, infer("var_is_null(3)"));
        assertEquals(Domain.DATASET, infer("var_is_null(\"A\", \"B\")"));
        assertFalse(
                BroadcastFold.isBroadcastColumnPredicate(
                        (Expr.Call) CheckExpressionParser.parse("var_is_null(3)")),
                "a numeric literal is not one of compileVarIsNull's three shapes");
        assertTrue(
                BroadcastFold.isBroadcastColumnPredicate(
                        (Expr.Call) CheckExpressionParser.parse("var_is_null(varname())")),
                "isExistsCall's guard would reject the varname() Call form FDA-SD1078 uses");
    }


    @Test
    void domainAlgebra()
    {
        assertEquals(Domain.CELL, Domain.VARIABLE.join(Domain.ROW));
        assertEquals(Domain.ROW, Domain.DATASET.join(Domain.ROW));
        assertTrue(Domain.DATASET.isBroadcast());
        assertFalse(Domain.VARIABLE.isBroadcast());
        for (Domain d : List.of(Domain.DATASET, Domain.VARIABLE, Domain.ROW, Domain.CELL))
        {
            assertEquals(d, Domain.of(d.varCursor(), d.rowCursor()));
        }
        assertEquals("{VAR,ROW}", Domain.CELL.label());
        Expr list = CheckExpressionParser.parse("AESEV in [\"A\", \"B\"]");
        assertEquals(Domain.ROW, DomainScan.infer(list, BindingDomains.NONE));
    }


    /**
     * Combined review of runbook W2–W8, XCUT H1: {@code row_max} selects its columns by a static
     * regex and has no column operand, so the leaf table saw only a literal and classified a
     * {@code $trxx_max} binding {@code {}} — CDISC-/PMDA-AD0084 then decided every row from row 0's
     * maximum. A declared row reader ({@code FunctionDescriptor.readingRows}) demands the ROW
     * cursor whatever its operands say.
     */
    @Test
    void aDeclaredRowReaderDemandsTheRowCursorWithoutAColumnOperand()
    {
        assertEquals(Domain.ROW, infer("row_max(name_pattern=\"^TR(0[1-9]|[1-9][0-9])EDT$\")"));
        assertEquals(Domain.ROW, infer("not empty($trxx_max) and TRTEDT != $trxx_max",
                kinds("$trxx_max", infer("row_max(name_pattern=\"^TR..EDT$\")"))));
        // The flag is the mechanism, not the name: the registry says so.
        assertTrue(
                java.util.Objects.requireNonNull(FunctionRegistry.descriptor("row_max")).perRow(),
                "row_max is registered as a row reader");
    }


    /**
     * Combined review of runbook W2–W8, W3+W4b L5: the per-variable MAP shape
     * ({@code cross_dataset_variable_metadata}) is declared once on the descriptor and read there
     * by every seam that used to key it on the name — the VARIABLE arm here included.
     */
    @Test
    void thePerVariableMapShapeIsDeclaredOnTheDescriptorNotOnTheName()
    {
        FunctionDescriptor cdvm = java.util.Objects
                .requireNonNull(FunctionRegistry.descriptor("cross_dataset_variable_metadata"));
        assertTrue(cdvm.perVariableMap() && cdvm.aggregate(),
                "declared a per-variable map (and so an aggregate)");
        assertEquals(Domain.VARIABLE, infer("empty($lbl)", kinds("$lbl",
                infer("cross_dataset_variable_metadata(\"label\", domain=\"ADSL\")"))));
        assertEquals(Domain.DATASET, infer("variable_count() > 3"),
                "a sibling scalar function without the flag stays a dataset fact");
    }
}
