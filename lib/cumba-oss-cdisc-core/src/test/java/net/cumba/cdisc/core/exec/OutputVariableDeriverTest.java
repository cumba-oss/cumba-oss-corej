package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * {@link OutputVariableDeriver} — one test per decision of PLAN-auto-output-variables §4.
 * Hand-built {@link Expr} trees and {@link Rule} objects only: derivation is load-time and
 * dataset-independent, so no loader and no data tables appear here.
 */
class OutputVariableDeriverTest
{

    // ------------------------------------------------------------- builders

    private static Rule rule(Expr check)
    {
        Rule r = new Rule();
        r.setCheckExpr(check);
        return r;
    }


    private static Rule rule(List<String> authored, Expr check)
    {
        Rule r = rule(check);
        Outcome outcome = new Outcome();
        outcome.setOutputVariables(authored);
        r.setOutcome(outcome);
        return r;
    }


    private static Expr col(String name)
    {
        return new Expr.Ref(name, OperandKind.COLUMN);
    }


    private static Expr opRef(String name)
    {
        return new Expr.Ref(name, OperandKind.OPERATION_REF);
    }


    private static Expr builtin(String name)
    {
        return new Expr.Ref(name, OperandKind.BUILTIN);
    }


    private static Expr str(String value)
    {
        return new Expr.Lit(Expr.LitKind.STRING, value);
    }


    private static Expr list(Expr... items)
    {
        return new Expr.Lit(Expr.LitKind.LIST, List.of(items));
    }


    private static Expr call(String name, Expr... args)
    {
        return new Expr.Call(name, List.of(args), Map.of());
    }


    private static Expr callKw(String name, List<Expr> args, String kwarg, Expr value)
    {
        return new Expr.Call(name, args, Map.of(kwarg, value));
    }


    private static Expr eq(Expr left, Expr right)
    {
        return new Expr.Binary(Expr.BinOp.EQ, left, right);
    }


    private static Operation operation(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }


    private static Scope domains(String... include)
    {
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of(include));
        scope.setDomains(ds);
        return scope;
    }

    // ------------------------------------------------------------- D1


    @Test
    void authoredEntriesArePreservedInOrder()
    {
        Rule r = rule(List.of("ZZCUSTOM", "AESTDTC"), eq(col("AESTDTC"), col("AEENDTC")));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertEquals(List.of("ZZCUSTOM", "AESTDTC", "AEENDTC"), effective);
        assertEquals(List.of("AEENDTC"), OutputVariableDeriver.derivedOnly(r));
    }


    @Test
    void authoredEntrySurvivesEveryExclusion()
    {
        // authored USUBJID (D5), variable_label on a record path (D6), and a not-exists
        // column (D3): D1 keeps all three verbatim.
        Rule r = rule(List.of("USUBJID", "variable_label", "GONE"),
                new Expr.Not(call("var_exists", col("GONE"))));
        assertEquals(List.of("USUBJID", "variable_label", "GONE"), OutputVariableDeriver.derive(r));
        assertEquals(List.of(), OutputVariableDeriver.derivedOnly(r));
    }

    // ------------------------------------------------------------- D2


    @Test
    void columnWildcardDottedAndOperationRefsAreDerived()
    {
        Rule r = rule(new Expr.And(
                List.of(eq(col("AESTDTC"), new Expr.Ref("--DTC", OperandKind.WILDCARD_COLUMN)),
                        eq(new Expr.Ref("DM.AGE", OperandKind.DOTTED_REF), opRef("$max_age")))));
        assertEquals(List.of("AESTDTC", "--DTC", "DM.AGE", "$max_age"),
                OutputVariableDeriver.derive(r));
    }


    @Test
    void listLiteralElementsAreWalked()
    {
        // A list literal in a column position is walked into its refs (D2); the same list as
        // an IN right-hand side is a value list and contributes nothing (D2c).
        Expr columnsList = callKw("is_unique_set", List.of(col("AEGRPID")), "keys",
                list(col("USUBJID2"), str("N")));
        Expr valuesList = new Expr.Binary(Expr.BinOp.IN, str("Y"), list(col("DTHFL"), str("N")));
        List<String> fromColumns = OutputVariableDeriver.derive(rule(columnsList));
        assertTrue(fromColumns.contains("USUBJID2"));
        assertFalse(fromColumns.contains("N"));
        List<String> fromValues = OutputVariableDeriver.derive(rule(valuesList));
        assertFalse(fromValues.contains("DTHFL"));
        assertFalse(fromValues.contains("Y"));
    }

    // ------------------------------------------------------------- D2b


    @Test
    void accessorCallsReverseToTheirOperandNames()
    {
        Expr check = new Expr.And(
                List.of(eq(call("var_label", builtin("variable_name"), str("LIBRARY")), str("x")),
                        eq(call("varname"), str("AEDECOD")), eq(call("value"), str("y")),
                        eq(call("record_count"), new Expr.Lit(Expr.LitKind.NUMBER, 0)),
                        eq(call("vlm_length", builtin("variable_name")),
                                new Expr.Lit(Expr.LitKind.NUMBER, 8)),
                        call("library_variable_code_pair_matches")));
        List<String> effective = OutputVariableDeriver.derive(rule(check));
        assertTrue(effective.contains("library_variable_label"));
        assertTrue(effective.contains("variable_value"));
        assertTrue(effective.contains("record_count"));
        assertTrue(effective.contains("define_vlm_length"));
        assertTrue(effective.contains("library_variable_code_pair_matches"));
        // D7/R-9.7 — the per-variable path hoists variable_name to index 0
        assertEquals("variable_name", effective.get(0));
    }


    @Test
    void arbitraryLiteralAnchoredAccessorContributesNothing()
    {
        // var_label("AETERM", "LIBRARY") — no per-variable cursor, no projection key.
        Rule r = rule(eq(call("var_label", str("AETERM"), str("LIBRARY")), str("x")));
        assertFalse(OutputVariableDeriver.derive(r).contains("library_variable_label"));
    }

    // ------------------------------------------------------------- D2c


    @Test
    void uniquenessKeysAreColumnsMembershipKeysAreValues()
    {
        Expr uniqueKeys = callKw("is_not_unique_set", List.of(col("AEGRPID")), "keys",
                list(col("USUBJID2"), col("AETERM")));
        Expr valueKeys = callKw("contains_all", List.of(col("TSPARMCD")), "keys",
                list(col("ADDON"), col("AGEMAX")));
        assertEquals(List.of("AEGRPID", "USUBJID2", "AETERM"),
                OutputVariableDeriver.derive(rule(uniqueKeys)));
        assertEquals(List.of("TSPARMCD"), OutputVariableDeriver.derive(rule(valueKeys)));
    }


    @Test
    void uniqueSetListOperandDerivesEveryMember()
    {
        // Owner requirement #1 (2026-08-23): is_unique_set([A, B, …]) — ONE positional list
        // operand. walk's Lit/LIST arm recurses it with inValueList=false (arg 0 is never a value
        // list), so every member is a column — the D2c distinction with contains_all's keys=
        // (values, never derived) is unchanged. Both arms asserted so neither can go vacuous.
        Expr flattened = new Expr.Not(
                call("is_unique_set", list(col("AEGRPID"), col("USUBJID2"), col("AETERM"))));
        assertEquals(List.of("AEGRPID", "USUBJID2", "AETERM"),
                OutputVariableDeriver.derive(rule(flattened)));
        Expr withRef = call("is_not_unique_set",
                list(col("USUBJID2"), col("--TESTCD"), opRef("$natural_key")));
        assertEquals(List.of("USUBJID2", "--TESTCD", "$natural_key"),
                OutputVariableDeriver.derive(rule(withRef)));
        Expr valueKeys = callKw("contains_all", List.of(col("TSPARMCD")), "keys",
                list(col("ADDON"), col("AGEMAX")));
        assertEquals(List.of("TSPARMCD"), OutputVariableDeriver.derive(rule(valueKeys)));
    }


    @Test
    void hasMultipleValuesForComparedColumnIsDerived()
    {
        // has_multiple_values_for(QNAM, QVAL, within=[USUBJID2]) — the 2nd positional is the
        // compared-value COLUMN (ExprCompiler.compileHasMultipleValuesFor reads it as one),
        // and within= entries are grouping columns; none of them are literals.
        Expr check = new Expr.Call("has_multiple_values_for", List.of(col("QNAM"), col("QVAL")),
                Map.of("within", list(col("USUBJID2"))));
        List<String> effective = OutputVariableDeriver.derive(rule(check));
        assertTrue(effective.containsAll(List.of("QNAM", "QVAL", "USUBJID2")), effective::toString);
    }


    @Test
    void operationRefInValueListStillContributes()
    {
        // contains_all(TSPARMCD, keys=[$required_params, ADDON]) — a $-ref names a
        // materialised operation result, not a literal; the bare token stays a literal.
        Expr check = callKw("contains_all", List.of(col("TSPARMCD")), "keys",
                list(opRef("$required_params"), col("ADDON")));
        List<String> effective = OutputVariableDeriver.derive(rule(check));
        assertTrue(effective.contains("$required_params"));
        assertFalse(effective.contains("ADDON"));
    }

    // ------------------------------------------------------------- D3


    @Test
    void notVarExistsSuppressesTheColumn()
    {
        Rule r = rule(new Expr.And(List.of(new Expr.Not(call("var_exists", col("AESLIFE"))),
                eq(col("AESER"), str("Y")))));
        assertEquals(List.of("AESER"), OutputVariableDeriver.derive(r));
    }


    @Test
    void varNotExistsCallSuppressesTheColumn()
    {
        Rule r = rule(new Expr.And(
                List.of(call("var_not_exists", col("AESLIFE")), eq(col("AESER"), str("Y")))));
        assertEquals(List.of("AESER"), OutputVariableDeriver.derive(r));
    }


    @Test
    void emptyCheckedColumnIsStillDerived()
    {
        // R-9.6 correction: an empty-checked variable IS present; its blankness is the finding.
        Rule r = rule(call("empty", col("AEDECOD")));
        assertEquals(List.of("AEDECOD"), OutputVariableDeriver.derive(r));
    }


    @Test
    void columnSuppressedInOneBranchSurvivesAnother()
    {
        // not var_exists(ARM) or ACTARM == ARM — ARM also occurs positively, so it is kept.
        Rule r = rule(new Expr.Or(List.of(new Expr.Not(call("var_exists", col("ARM"))),
                eq(col("ACTARM"), col("ARM")))));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertTrue(effective.contains("ARM"));
        assertTrue(effective.contains("ACTARM"));
    }


    @Test
    void suppressionExtendsToOperationInputs()
    {
        // The check asserts TSGRPID absent; an operation input naming it must not re-add it.
        Rule r = rule(new Expr.Not(call("var_exists", col("TSGRPID"))));
        Operation op = operation("$grp", "distinct");
        op.setName("TSGRPID");
        r.setOperations(List.of(op));
        assertFalse(OutputVariableDeriver.derive(r).contains("TSGRPID"));
    }

    // ------------------------------------------------------------- D4


    @Test
    void everyOperationIdIsDerived()
    {
        Rule r = rule(eq(col("AESTDY"), opRef("$dy")));
        Operation referenced = operation("$dy", "dy");
        Operation unreferenced = operation("$count", "record_count");
        r.setOperations(List.of(referenced, unreferenced));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertTrue(effective.contains("$dy"));
        assertTrue(effective.contains("$count"));
    }


    @Test
    void bulkResultOperationIdIsNotDerived()
    {
        // §4.1 as a GLOBAL post-filter: $terms enters via the Check walk, not D4a.
        Rule r = rule(new Expr.Binary(Expr.BinOp.NOT_IN, col("AEDECOD"), opRef("$terms")));
        r.setOperations(List.of(operation("$terms", "codelist_terms")));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertTrue(effective.contains("AEDECOD"));
        assertFalse(effective.contains("$terms"));
    }


    @Test
    void minusResultIsDerived()
    {
        // §4.1's one list-valued DERIVE: the minus result IS the finding.
        Rule r = rule(new Expr.Binary(Expr.BinOp.GT, call("record_count"),
                new Expr.Lit(Expr.LitKind.NUMBER, 0)));
        r.setOperations(List.of(operation("$missing", "minus")));
        assertTrue(OutputVariableDeriver.derive(r).contains("$missing"));
    }


    @Test
    void authoredBulkResultOperationIdIsKept()
    {
        Rule r = rule(List.of("$terms"),
                new Expr.Binary(Expr.BinOp.NOT_IN, col("AEDECOD"), opRef("$terms")));
        r.setOperations(List.of(operation("$terms", "codelist_terms")));
        assertTrue(OutputVariableDeriver.derive(r).contains("$terms"));
    }


    @Test
    void localOperationInputsAreDerivedForeignOnesAreNot()
    {
        Rule r = rule(eq(opRef("$local_max"), opRef("$ti_codes")));
        r.setScope(domains("AE"));
        Operation local = operation("$local_max", "max");
        local.setName("AESEV");
        Operation foreign = operation("$ti_codes", "distinct");
        foreign.setDomain("TI");
        foreign.setName("IETESTCD");
        r.setOperations(List.of(local, foreign));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertTrue(effective.contains("$local_max"));
        assertTrue(effective.contains("AESEV"));
        assertFalse(effective.contains("IETESTCD"));
        // $ti_codes is distinct → §4.1-ignored on top of being foreign
        assertFalse(effective.contains("$ti_codes"));
    }


    @Test
    void filterKeysDeriveFilterValuesDoNot()
    {
        Rule r = rule(eq(col("QVAL"), opRef("$qlabel")));
        Operation op = operation("$qlabel", "max");
        op.setFilter(Map.of("QNAM", "SDTMVER"));
        r.setOperations(List.of(op));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertTrue(effective.contains("QNAM"));
        assertFalse(effective.contains("SDTMVER"));
    }

    // ------------------------------------------------------------- D5


    @Test
    void locationVariablesAreNeverDerived()
    {
        Rule r = rule(new Expr.And(List.of(eq(col("USUBJID"), str("X")), eq(col("ASEQ"), str("1")),
                eq(col("--SEQ"), str("2")), eq(col("AETERM"), str("t")))));
        assertEquals(List.of("AETERM"), OutputVariableDeriver.derive(r));
    }


    @Test
    void scopePinnedDomainSeqIsExcluded()
    {
        Rule r = rule(new Expr.And(
                List.of(eq(col("LBSEQ"), str("1")), eq(col("LBTESTCD"), str("GLUC")))));
        r.setScope(domains("LB", "AE"));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertFalse(effective.contains("LBSEQ"));
        assertTrue(effective.contains("LBTESTCD"));
    }


    @Test
    void unpinnedDomainSeqSurvives()
    {
        // Scope ALL — the accepted D5 residual: a literal LBSEQ cannot be resolved at load time.
        Rule r = rule(eq(col("LBSEQ"), str("1")));
        r.setScope(domains("ALL"));
        assertTrue(OutputVariableDeriver.derive(r).contains("LBSEQ"));
    }


    @Test
    void srcseqIsNotTreatedAsALocationVariable()
    {
        Rule r = rule(eq(col("SRCSEQ"), str("1")));
        assertTrue(OutputVariableDeriver.derive(r).contains("SRCSEQ"));
    }

    // ------------------------------------------------------------- D6


    @Test
    void variableScopeBuiltinsOnlyOnPerVariablePath()
    {
        // Record-path rule referencing a variable-scope builtin by name: dropped (it would be
        // an unresolvable key). The same name on a per-variable path is kept.
        Rule record = rule(eq(builtin("variable_label"), str("x")));
        assertFalse(OutputVariableDeriver.derive(record).contains("variable_label"));
        Rule perVar = rule(new Expr.And(List.of(eq(builtin("variable_label"), str("x")),
                eq(call("varname"), str("AETERM")))));
        assertTrue(OutputVariableDeriver.derive(perVar).contains("variable_label"));
    }


    @Test
    void datasetScopeBuiltinsAlwaysDerived()
    {
        Rule r = rule(new Expr.And(List.of(eq(builtin("dataset_label"), str("Adverse Events")),
                eq(col("DOMAIN"), str("AE")))));
        assertTrue(OutputVariableDeriver.derive(r).contains("dataset_label"));
    }


    @Test
    void datasetMetadataOperandIsNeverDerived()
    {
        Rule r = rule(eq(builtin("dataset_metadata"), str("x")));
        assertFalse(OutputVariableDeriver.derive(r).contains("dataset_metadata"));
    }

    // ------------------------------------------------------------- D7


    @Test
    void vlmRuleAlwaysEmitsVariableNameFirst()
    {
        // A vlm_* read guarantees the pair regardless of what else the Check mentions (the
        // retired VLM Rule_Type used to carry this; the Check itself does now).
        Rule r = rule(List.of("LBORRES"), eq(col("LBORRES"), call("vlm_codelist")));
        assertEquals(List.of("variable_name", "variable_value", "LBORRES"),
                OutputVariableDeriver.derive(r));
    }


    @Test
    void vlmcRuleAlsoEmitsVariableValue()
    {
        // Per-variable path + value() cursor ⇒ variable_name, variable_value lead the list.
        Rule r = rule(new Expr.And(
                List.of(eq(call("varname"), str("AETERM")), eq(call("value"), str("")))));
        assertEquals(List.of("variable_name", "variable_value"), OutputVariableDeriver.derive(r));
    }


    @Test
    void perVariablePathWithoutValueCursorOmitsVariableValue()
    {
        Rule r = rule(eq(call("varname"), str("AETERM")));
        assertEquals(List.of("variable_name"), OutputVariableDeriver.derive(r));
    }

    // ------------------------------------------------------------- D8


    @Test
    void domainPresenceCheckDoesNotDeriveDatasetNames()
    {
        Rule r = rule(new Expr.Not(call("ds_exists", str("AE"))));
        assertEquals(List.of(), OutputVariableDeriver.derive(r));
    }


    @Test
    void dsExistsOperandNeverContributesRegardlessOfRuleType()
    {
        Rule r = rule(new Expr.And(
                List.of(call("ds_exists", str("SUPPAE")), eq(col("AETERM"), str("t")))));
        assertEquals(List.of("AETERM"), OutputVariableDeriver.derive(r));
    }

    // ------------------------------------------------------------- D9


    @Test
    void preconditionContributesNothing()
    {
        Rule r = rule(eq(col("AESTDTC"), str("2020")));
        r.setPreconditionExpr(eq(col("AEENDTC"), str("2021")));
        assertEquals(List.of("AESTDTC"), OutputVariableDeriver.derive(r));
    }

    // ------------------------------------------------------------- D2 fallback


    @Test
    void legacyTreeFallbackWhenNoCheckExpr()
    {
        CheckConditionLeaf keep = CheckConditionLeaf.builder().name("AETERM").operator("empty")
                .build();
        CheckConditionLeaf gone = CheckConditionLeaf.builder().name("AESLIFE")
                .operator("var_not_exists").build();
        CheckConditionLeaf additional = CheckConditionLeaf.builder().name("TSVAL")
                .operator("additional_columns_empty").build();
        Rule r = new Rule();
        r.setCheck(new CheckConditionAll(List.of(keep, gone, additional)));
        // var_not_exists (D3) and additional_columns_* (runtime-expanded) leaves contribute nothing
        assertEquals(List.of("AETERM"), OutputVariableDeriver.derive(r));
    }

    // ------------------------------------------------------------- D10


    @Test
    void resultIsImmutableAndDeduplicated()
    {
        Rule r = rule(List.of("AETERM"),
                new Expr.And(List.of(eq(col("AETERM"), str("t")), eq(col("AETERM"), str("u")))));
        List<String> effective = OutputVariableDeriver.derive(r);
        assertEquals(List.of("AETERM"), effective);
        assertThrows(UnsupportedOperationException.class, () -> effective.add("X"));
    }

    // ------------------------------------------------------------- E-2 (Fix #354) exclusions


    @Test
    void exclusionSubtractsADerivedEntryAndNeverCreatesOne()
    {
        // AEDECOD is derived from the Check; `!AEDECOD` removes it. `!AEZZZ` names nothing the
        // rule reads and must not surface anywhere — an exclusion never creates an entry.
        Rule r = rule(List.of("AETERM", "!AEDECOD", "!AEZZZ"),
                new Expr.And(List.of(eq(col("AETERM"), str("t")), eq(col("AEDECOD"), str("d")))));
        assertEquals(List.of("AETERM"), OutputVariableDeriver.derive(r));
        assertEquals(List.of("AEDECOD", "AEZZZ"), List.copyOf(OutputVariableDeriver.excludedOf(r)));
        assertTrue(OutputVariableDeriver.derivedSet(r).contains("AEDECOD"));
        assertFalse(OutputVariableDeriver.derivedSet(r).contains("AEZZZ"));
    }


    @Test
    void exclusionIsSubtractedAfterTheVariableNameHoist()
    {
        // Per-variable path: variable_name is hoisted to index 0, variable_value to index 1 —
        // and `!variable_name` removes the hoisted lead, leaving variable_value in front.
        Rule r = rule(List.of("!variable_name"), new Expr.And(
                List.of(eq(call("varname"), str("AETERM")), eq(call("value"), str("")))));
        assertEquals(List.of("variable_value"), OutputVariableDeriver.derive(r));
        // the control: without the token the hoisted lead is there
        Rule control = rule(List.of(), new Expr.And(
                List.of(eq(call("varname"), str("AETERM")), eq(call("value"), str("")))));
        assertEquals(List.of("variable_name", "variable_value"),
                OutputVariableDeriver.derive(control));
    }


    @Test
    void exclusionIsSubtractedAfterTheVariableValueHoist()
    {
        Rule r = rule(List.of("LBORRES", "!variable_value"),
                eq(col("LBORRES"), call("vlm_codelist")));
        assertEquals(List.of("variable_name", "LBORRES"), OutputVariableDeriver.derive(r));
    }


    @Test
    void derivedOnlyExcludesFromBothSides()
    {
        // AEDECOD is derived and excluded: it is on neither side of DERIVED \ AUTHORED, and the
        // token itself is never an authored entry.
        Rule r = rule(List.of("AETERM", "!AEDECOD"),
                new Expr.And(List.of(eq(col("AETERM"), str("t")), eq(col("AEDECOD"), str("d")),
                        eq(col("AESEV"), str("s")))));
        assertEquals(List.of("AESEV"), OutputVariableDeriver.derivedOnly(r));
    }


    @Test
    void wildcardIncludeIsNeverReadAsAnExclusion()
    {
        Rule r = rule(List.of("--DTC"), eq(col("--STDTC"), str("x")));
        assertEquals(List.of("--DTC", "--STDTC"), OutputVariableDeriver.derive(r));
        assertEquals(List.of(), List.copyOf(OutputVariableDeriver.excludedOf(r)));
        // and `!--X` excludes the wildcard variable --X
        Rule ex = rule(List.of("--DTC", "!--STDTC"), eq(col("--STDTC"), str("x")));
        assertEquals(List.of("--DTC"), OutputVariableDeriver.derive(ex));
    }


    @Test
    void excludedOfIsEmptyForNullRuleAndNullOutcome()
    {
        assertEquals(List.of(), List.copyOf(OutputVariableDeriver.excludedOf(null)));
        assertEquals(List.of(), List.copyOf(OutputVariableDeriver.excludedOf(new Rule())));
        assertTrue(OutputVariableDeriver.derivedSet(null).isEmpty());
    }


    /**
     * {@code Fix #356} — the rule-aware D5 test. The verbatim set is matched with or without a
     * rule; the wildcard member {@code --SEQ} additionally matches its per-domain resolution, but
     * only for a domain the rule actually pins.
     */
    @Test
    void isLocationVariableResolvesTheWildcardMemberAgainstThePinnedDomains()
    {
        Rule pinned = new Rule();
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        domains.setInclude(List.of("LB"));
        scope.setDomains(domains);
        pinned.setScope(scope);

        assertTrue(OutputVariableDeriver.isLocationVariable(pinned, "LBSEQ"));
        assertTrue(OutputVariableDeriver.isLocationVariable(pinned, "--SEQ"));
        assertTrue(OutputVariableDeriver.isLocationVariable(pinned, "USUBJID"));
        // near misses: a different domain's SEQ, and a name that merely starts the same way
        assertFalse(OutputVariableDeriver.isLocationVariable(pinned, "AESEQ"));
        assertFalse(OutputVariableDeriver.isLocationVariable(pinned, "LBSEQUENCE"));
        assertFalse(OutputVariableDeriver.isLocationVariable(pinned, "LBORRES"));

        // no pinned domain ⇒ only the verbatim members
        assertFalse(OutputVariableDeriver.isLocationVariable(new Rule(), "LBSEQ"));
        assertTrue(OutputVariableDeriver.isLocationVariable(new Rule(), "ASEQ"));

        // null rule ⇒ the verbatim test alone, never an NPE
        assertFalse(OutputVariableDeriver.isLocationVariable(null, "LBSEQ"));
        assertTrue(OutputVariableDeriver.isLocationVariable(null, "--SEQ"));
    }
}
