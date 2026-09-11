package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.exec.OperationExecutor.ResultKind;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * {@link DomainScan} — the §3.1 leaf table and the §3.2 join, on the raised {@link Expr} IR.
 */
class DomainScanTest
{

    private static Domain infer(String source)
    {
        return DomainScan.infer(CheckExpressionParser.parse(source), OperationKinds.NONE);
    }


    private static Domain infer(String source, OperationKinds kinds)
    {
        return DomainScan.infer(CheckExpressionParser.parse(source), kinds);
    }


    private static OperationKinds kinds(String ref, ResultKind kind)
    {
        return r -> r.equals(ref) ? kind : ResultKind.SCALAR;
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
        assertEquals(Domain.VARIABLE,
                infer("$vmr == \"x\"", kinds("$vmr", ResultKind.PER_VARIABLE)));
        assertEquals(Domain.VARIABLE, infer("ds_exists(\"EX\") and varname() == \"AESEV\""));
    }


    @Test
    void rowCursorLeaves()
    {
        assertEquals(Domain.ROW, infer("EXDOSE > 0"));
        assertEquals(Domain.ROW, infer("empty(AESEV)"));
        assertEquals(Domain.ROW, infer("ds_exists(\"EX\") and EXDOSE > 0"));
        assertEquals(Domain.ROW, infer("DM.RFSTDTC < EXSTDTC"));
        assertEquals(Domain.ROW, infer("--DTC != \"\""));
        assertEquals(Domain.ROW, infer("$grp > 1", kinds("$grp", ResultKind.PER_ROW)));
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
                infer("varname() == \"A\" and $grp > 1", kinds("$grp", ResultKind.PER_ROW)));
    }


    @Test
    void theTwoParameterisedOperationsFollowTheirResultKind()
    {
        // model_class= is a parameter on get_model_filtered_variables, still a scalar set ⇒ {}.
        Operation filtered = new Operation();
        filtered.setId("$vars");
        filtered.setOperator("get_model_filtered_variables");
        filtered.setModelClass("Findings");
        assertEquals(Domain.DATASET, infer("not contains_all($dataset_vars, $vars)",
                OperationKinds.forOperations(List.of(filtered))));
        // relation= is a parameter on the row-level has_next_corresponding_record ⇒ {ROW}.
        assertEquals(Domain.ROW, infer(
                "not has_next_corresponding_record(SEENDTC, SESTDTC, ordering=SESTDTC, within=USUBJID, relation=\"<=\")"));
    }


    @Test
    void inlineOperationsFollowTheirResultKindNotTheirArguments()
    {
        assertEquals(Domain.DATASET, infer("record_count(domain=\"DM\") > 0"));
        assertEquals(Domain.ROW, infer("record_count(group=[USUBJID]) > 1"));
        assertEquals(Domain.ROW, infer("dy(AESTDTC) < 0"));
        assertEquals(Domain.ROW, infer(
                "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\"MEDDRA\") == false"));
    }


    @Test
    void operationKindsReadTheDeclaredOperations()
    {
        Operation grouped = new Operation();
        grouped.setId("$cnt");
        grouped.setOperator("record_count");
        grouped.setGroup(List.of("USUBJID"));
        Operation scalar = new Operation();
        scalar.setId("$all");
        scalar.setOperator("record_count");
        Operation formB = new Operation();
        formB.setId("$vm");
        formB.setExpression("cross_dataset_variable_metadata(AESEV, domain=\"SUPPAE\")");
        Operation malformed = new Operation();
        malformed.setId("$bad");
        malformed.setExpression("this is not a call");
        OperationKinds k = OperationKinds.forOperations(List.of(grouped, scalar, formB, malformed));
        assertEquals(ResultKind.PER_ROW, k.kindOf("$cnt"));
        assertEquals(ResultKind.SCALAR, k.kindOf("$all"));
        assertEquals(ResultKind.PER_VARIABLE, k.kindOf("$vm"));
        assertEquals(ResultKind.SCALAR, k.kindOf("$bad"));
        assertEquals(ResultKind.SCALAR, k.kindOf("$dangling"));
        assertEquals(ResultKind.SCALAR, OperationKinds.forOperations(null).kindOf("$x"));
        assertEquals(ResultKind.SCALAR, OperationKinds.forOperations(List.of()).kindOf("$x"));
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
            assertEquals(d, Domain.parse(d.label()));
            assertEquals(d, Domain.of(d.varCursor(), d.rowCursor()));
        }
        assertEquals("{VAR,ROW}", Domain.CELL.label());
        assertThrows(IllegalArgumentException.class, () -> Domain.parse("{STUDY}"));
        Expr list = CheckExpressionParser.parse("AESEV in [\"A\", \"B\"]");
        assertEquals(Domain.ROW, DomainScan.infer(list, OperationKinds.NONE));
    }
}
