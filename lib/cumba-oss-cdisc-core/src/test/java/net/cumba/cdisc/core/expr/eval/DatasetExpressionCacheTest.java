package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.JoinLookup;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DatasetExpressionCache} (Phase 1 of
 * {@code plans/done/PLAN-dataset-expression-cache.md}): the §3.4 purity classifier and the §3.2
 * cache key. The classifier is the correctness-critical gate — every excluded kind / cursor /
 * metadata function is asserted impure, every allow-list name is asserted pure, and the key's
 * instance-identity / canonical-text / domain-prefix components are exercised.
 */
class DatasetExpressionCacheTest
{

    private static final Expr COL = new Expr.Ref("VAR1", OperandKind.COLUMN);

    private static final Expr COL2 = new Expr.Ref("VAR2", OperandKind.COLUMN);

    private static final Expr WILDCARD = new Expr.Ref("--SEQ", OperandKind.WILDCARD_COLUMN);

    private static final Expr OP_REF = new Expr.Ref("$op", OperandKind.OPERATION_REF);

    private static final Expr DOTTED = new Expr.Ref("DM.AGE", OperandKind.DOTTED_REF);

    private static final Expr BUILTIN = new Expr.Ref("variable_name", OperandKind.BUILTIN);

    private static final Expr STR = new Expr.Lit(Expr.LitKind.STRING, "A");

    private static final Expr NUM = new Expr.Lit(Expr.LitKind.NUMBER, 5.0);

    private static Expr call(String name, Expr... args)
    {
        return new Expr.Call(name, List.of(args), Map.of());
    }

    // ------------------------------------------------------------------
    // Pure (candidate) leaves
    // ------------------------------------------------------------------


    @Test
    void columnAndWildcardRefsArePure()
    {
        assertTrue(DatasetExpressionCache.isPure(COL));
        assertTrue(DatasetExpressionCache.isPure(WILDCARD));
    }


    @Test
    void scalarLiteralsArePure()
    {
        assertTrue(DatasetExpressionCache.isPure(STR));
        assertTrue(DatasetExpressionCache.isPure(NUM));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Lit(Expr.LitKind.BOOL, true)));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Lit(Expr.LitKind.REGEX, "ab")));
    }


    @Test
    void listLiteralOfPureElementsIsPure()
    {
        Expr list = new Expr.Lit(Expr.LitKind.LIST,
                List.of(STR, new Expr.Lit(Expr.LitKind.STRING, "B")));
        assertTrue(DatasetExpressionCache.isPure(list));
    }


    @Test
    void listLiteralWithImpureElementIsImpure()
    {
        Expr list = new Expr.Lit(Expr.LitKind.LIST, List.of(STR, OP_REF));
        assertFalse(DatasetExpressionCache.isPure(list));
    }


    @Test
    void everyAllowListedFunctionIsPureWithPureArgs()
    {
        for (String name : DatasetExpressionCache.PURE_FUNCTIONS)
        {
            assertTrue(DatasetExpressionCache.isPure(call(name, COL)),
                    "expected pure: " + name + "(VAR1)");
        }
    }


    /**
     * Fix #157 — named explicitly, not left to {@link #everyAllowListedFunctionIsPureWithPureArgs}
     * (which iterates the allow-list and so cannot fail for a name that was never added). Both read
     * only their argument vector, so both are cacheable.
     */
    @Test
    void completeDatePartPairIsCacheable()
    {
        assertTrue(DatasetExpressionCache.isPure(call("is_complete_date_part", COL)));
        assertTrue(DatasetExpressionCache.isPure(call("is_not_complete_date_part", COL)));
    }


    @Test
    void nestedPureCallsArePure()
    {
        assertTrue(DatasetExpressionCache.isPure(call("lower", call("trim", COL))));
    }


    @Test
    void comparisonArithmeticRegexOverPureOperandsArePure()
    {
        assertTrue(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.GT, COL, NUM)));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.ADD, COL, COL2)));
        assertTrue(DatasetExpressionCache.isPure(
                new Expr.Binary(Expr.BinOp.MATCH, COL, new Expr.Lit(Expr.LitKind.REGEX, "x"))));
    }


    @Test
    void booleanCombinatorsOverPurePartsArePure()
    {
        Expr a = call("empty", COL);
        Expr b = new Expr.Binary(Expr.BinOp.GT, COL2, NUM);
        assertTrue(DatasetExpressionCache.isPure(new Expr.And(List.of(a, b))));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Or(List.of(a, b))));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Not(a)));
    }


    @Test
    void membershipAgainstLiteralListIsPure()
    {
        Expr list = new Expr.Lit(Expr.LitKind.LIST,
                List.of(STR, new Expr.Lit(Expr.LitKind.STRING, "B")));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.IN, COL, list)));
        assertTrue(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.NOT_IN, COL, list)));
    }

    // ------------------------------------------------------------------
    // Impure leaves
    // ------------------------------------------------------------------


    @Test
    void nonColumnRefKindsAreImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(OP_REF));
        assertFalse(DatasetExpressionCache.isPure(DOTTED));
        assertFalse(DatasetExpressionCache.isPure(BUILTIN));
    }


    @Test
    void currentVariableCursorCallsAreImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(call("value")));
        assertFalse(DatasetExpressionCache.isPure(call("varname")));
        assertFalse(DatasetExpressionCache.isPure(call("colref", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("record_count")));
    }


    @Test
    void metadataAccessorCallsAreImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(call("var_label", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("variable_label", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("ds_name")));
        assertFalse(DatasetExpressionCache.isPure(call("library_variable_role", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("define_variable_core", COL)));
    }


    @Test
    void existsAndTypeTagFormsAreImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(call("var_exists", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("var_not_exists", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("ds_exists", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("str", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("num", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("date", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("date_part", COL)));
        assertFalse(DatasetExpressionCache.isPure(call("time_part", COL)));
    }


    @Test
    void callWithKwargsIsImpure()
    {
        Expr withKwargs = new Expr.Call("empty", List.of(COL), Map.of("ordering", COL2));
        assertFalse(DatasetExpressionCache.isPure(withKwargs));
    }


    @Test
    void pureFunctionWithImpureArgIsImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(call("empty", OP_REF)));
        assertFalse(DatasetExpressionCache.isPure(call("lower", call("varname"))));
    }


    @Test
    void membershipAgainstNonLiteralListIsImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.IN, COL, COL2)));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.IN, COL, OP_REF)));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.NOT_IN, COL, OP_REF)));
        // IN with a left-impure operand is impure even against a literal list.
        Expr list = new Expr.Lit(Expr.LitKind.LIST, List.of(STR));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.IN, OP_REF, list)));
        // IN against a scalar (non-list) literal RHS is impure (only a LIST literal qualifies).
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.IN, COL, STR)));
        // IN against a literal list that itself contains an impure element is impure.
        Expr impureList = new Expr.Lit(Expr.LitKind.LIST, List.of(STR, OP_REF));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.IN, COL, impureList)));
    }


    @Test
    void comparisonWithImpureOperandIsImpure()
    {
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.EQ, COL, OP_REF)));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Binary(Expr.BinOp.EQ, DOTTED, NUM)));
    }


    @Test
    void booleanCombinatorsWithAnyImpurePartAreImpure()
    {
        Expr pure = call("empty", COL);
        Expr impure = call("value");
        assertFalse(DatasetExpressionCache.isPure(new Expr.And(List.of(pure, impure))));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Or(List.of(pure, impure))));
        assertFalse(DatasetExpressionCache.isPure(new Expr.Not(impure)));
    }

    // ------------------------------------------------------------------
    // Key derivation (§3.2)
    // ------------------------------------------------------------------


    @Test
    void sameCanonicalAndTableAndPrefixCollideOnOneKey()
    {
        IDataTable table = mock(IDataTable.class);
        // Two independently-built `empty(VAR1)` leaves.
        Expr e1 = call("empty", new Expr.Ref("VAR1", OperandKind.COLUMN));
        Expr e2 = call("empty", new Expr.Ref("VAR1", OperandKind.COLUMN));
        var k1 = DatasetExpressionCache.keyOf(table, e1, "AE");
        var k2 = DatasetExpressionCache.keyOf(table, e2, "AE");
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
        assertEquals("empty(VAR1)", k1.exprCanonical());
    }


    @Test
    void differentTableInstanceDoesNotCollide()
    {
        Expr e = call("empty", COL);
        var k1 = DatasetExpressionCache.keyOf(mock(IDataTable.class), e, "AE");
        var k2 = DatasetExpressionCache.keyOf(mock(IDataTable.class), e, "AE");
        assertNotEquals(k1, k2);
    }


    @Test
    void differentDomainPrefixDoesNotCollide()
    {
        IDataTable table = mock(IDataTable.class);
        Expr e = call("empty", COL);
        var ae = DatasetExpressionCache.keyOf(table, e, "AE");
        var dm = DatasetExpressionCache.keyOf(table, e, "DM");
        assertNotEquals(ae, dm);
    }


    @Test
    void nullDomainPrefixIsSupportedAndDistinctFromNonNull()
    {
        IDataTable table = mock(IDataTable.class);
        Expr e = call("empty", COL);
        var nullPrefix = DatasetExpressionCache.keyOf(table, e, null);
        var nullPrefix2 = DatasetExpressionCache.keyOf(table, e, null);
        assertEquals(nullPrefix, nullPrefix2);
        assertNotEquals(nullPrefix, DatasetExpressionCache.keyOf(table, e, "AE"));
    }

    // ------------------------------------------------------------------
    // IdentityKey
    // ------------------------------------------------------------------


    @Test
    void identityKeyComparesByReferenceIdentity()
    {
        IDataTable t1 = mock(IDataTable.class);
        IDataTable t2 = mock(IDataTable.class);
        var a = new DatasetExpressionCache.IdentityKey(t1);
        assertEquals(a, new DatasetExpressionCache.IdentityKey(t1));
        assertEquals(a.hashCode(), System.identityHashCode(t1));
        assertNotEquals(a, new DatasetExpressionCache.IdentityKey(t2));
        assertSame(t1, a.table());
    }


    @Test
    void identityKeyHandlesNullAndForeignTypes()
    {
        var a = new DatasetExpressionCache.IdentityKey(mock(IDataTable.class));
        assertNotEquals(a, null);
        assertNotEquals(a, "not an IdentityKey");
        assertTrue(a.toString().startsWith("IdentityKey@"));
    }

    // ------------------------------------------------------------------
    // Eval-time decline gate (§3.6) — cacheableAt
    // ------------------------------------------------------------------


    private static EvaluationContext gateCtx(Domain domain, Map<String, JoinLookup> joins,
            Map<String, Object> vars, String domainPrefix, String... localColumns)
    {
        DataTableMeta meta = mock(DataTableMeta.class);
        when(meta.getColumnIndex(anyString())).thenReturn(-1);
        for (int i = 0; i < localColumns.length; i++)
        {
            when(meta.getColumnIndex(localColumns[i])).thenReturn(i);
        }
        IDataTable table = mock(IDataTable.class);
        when(table.getMetaData()).thenReturn(meta);
        return EvaluationContext.builder().table(table).joinedDatasets(joins).variables(vars)
                .domainPrefix(domainPrefix).evaluationDomain(domain).build();
    }


    /** As {@link #gateCtx} but also setting EC-36's variable wildcard prefix. */
    private static EvaluationContext gateCtx(String domainPrefix, String variableWildcardPrefix,
            String... localColumns)
    {
        return gateCtx(Domain.ROW, Map.of(), Map.of(), domainPrefix, localColumns).toBuilder()
                .variableWildcardPrefix(variableWildcardPrefix).build();
    }


    @Test
    void cacheableAt_suppEmptyPrefixResolvesWildcard()
    {
        // EC-36: the purity probe must ask about the same column the evaluator reads. The old
        // `length() == 2` mirror left --QNAM raw on a SUPP dataset, so it missed the local-column
        // test and the leaf was declined for caching.
        EvaluationContext ctx = gateCtx("SUPPAE", "", "QNAM");
        assertTrue(DatasetExpressionCache.cacheableAt(
                call("empty", new Expr.Ref("--QNAM", OperandKind.WILDCARD_COLUMN)), ctx));
    }


    @Test
    void cacheableAt_apParentSuffixResolvesWildcard()
    {
        // Same for an AP dataset: the 4-character domain code failed the old gate.
        EvaluationContext ctx = gateCtx("APMH", "MH", "MHTERM");
        assertTrue(DatasetExpressionCache.cacheableAt(
                call("empty", new Expr.Ref("--TERM", OperandKind.WILDCARD_COLUMN)), ctx));
    }


    @Test
    void cacheableAt_declinesWhenResolvedColumnIsAbsent()
    {
        // Guard against over-caching: resolution must still be checked against the real columns.
        EvaluationContext ctx = gateCtx("APMH", "MH", "SOMETHINGELSE");
        assertFalse(DatasetExpressionCache.cacheableAt(
                call("empty", new Expr.Ref("--TERM", OperandKind.WILDCARD_COLUMN)), ctx));
    }


    @Test
    void cacheableWhenRecordDataLocalAndUnjoined()
    {
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "VAR1");
        assertTrue(DatasetExpressionCache.cacheableAt(call("empty", COL), ctx));
    }


    @Test
    void declinesWhenJoinsPresent()
    {
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of("DM", mock(JoinLookup.class)), Map.of(),
                "AE", "VAR1");
        assertFalse(DatasetExpressionCache.cacheableAt(call("empty", COL), ctx));
    }


    @Test
    void declinesEveryDomainWithAVariableCursorAndTheBroadcastDomain()
    {
        for (Domain d : List.of(Domain.DATASET, Domain.VARIABLE, Domain.CELL))
        {
            EvaluationContext ctx = gateCtx(d, Map.of(), Map.of(), "AE", "VAR1");
            assertFalse(DatasetExpressionCache.cacheableAt(call("empty", COL), ctx),
                    "expected decline for domain " + d);
        }
    }


    @Test
    void declinesNullDomain()
    {
        EvaluationContext ctx = gateCtx(null, Map.of(), Map.of(), "AE", "VAR1");
        assertFalse(DatasetExpressionCache.cacheableAt(call("empty", COL), ctx));
    }


    @Test
    void declinesRefAbsentFromLocalTable()
    {
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE");
        assertFalse(DatasetExpressionCache.cacheableAt(call("empty", COL), ctx));
    }


    @Test
    void declinesRefShadowedByContextVariable()
    {
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of("VAR1", "x"), "AE", "VAR1");
        assertFalse(DatasetExpressionCache.cacheableAt(call("empty", COL), ctx));
    }


    @Test
    void dashDashWildcardResolvesViaDomainPrefix()
    {
        Expr leaf = call("empty", new Expr.Ref("--SEQ", OperandKind.WILDCARD_COLUMN));
        EvaluationContext present = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "AESEQ");
        assertTrue(DatasetExpressionCache.cacheableAt(leaf, present));
        EvaluationContext absent = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "VAR1");
        assertFalse(DatasetExpressionCache.cacheableAt(leaf, absent));
    }


    @Test
    void declinesNonDashWildcard()
    {
        Expr leaf = call("empty", new Expr.Ref("*DT", OperandKind.WILDCARD_COLUMN));
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "*DT");
        assertFalse(DatasetExpressionCache.cacheableAt(leaf, ctx));
    }


    @Test
    void declinesWhenAnyRefInCompoundLeafIsNonLocal()
    {
        // VAR1 present, VAR2 absent -> the And is not fully local.
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "VAR1");
        Expr leaf = new Expr.And(List.of(call("empty", COL), call("non_empty", COL2)));
        assertFalse(DatasetExpressionCache.cacheableAt(leaf, ctx));
    }


    @Test
    void cacheableBinaryOrNotAndListLeavesWhenAllRefsLocal()
    {
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "VAR1", "VAR2");
        // Binary over a local column and a scalar literal.
        assertTrue(
                DatasetExpressionCache.cacheableAt(new Expr.Binary(Expr.BinOp.GT, COL, NUM), ctx));
        // Or / Not over local pure parts.
        assertTrue(DatasetExpressionCache.cacheableAt(
                new Expr.Or(List.of(call("empty", COL), call("non_empty", COL2))), ctx));
        assertTrue(DatasetExpressionCache.cacheableAt(new Expr.Not(call("empty", COL)), ctx));
        // Membership against a literal list (list elements are scalar literals).
        Expr list = new Expr.Lit(Expr.LitKind.LIST,
                List.of(STR, new Expr.Lit(Expr.LitKind.STRING, "B")));
        assertTrue(
                DatasetExpressionCache.cacheableAt(new Expr.Binary(Expr.BinOp.IN, COL, list), ctx));
    }


    @Test
    void declinesWhenALeafRefIsNotAColumn()
    {
        // cacheableAt is only meant for pure leaves, but defensively a non-column ref declines.
        EvaluationContext ctx = gateCtx(Domain.ROW, Map.of(), Map.of(), "AE", "VAR1");
        assertFalse(DatasetExpressionCache.cacheableAt(new Expr.Binary(Expr.BinOp.EQ, COL, OP_REF),
                ctx));
    }


    @Test
    void declinesDashWildcardWhenDomainPrefixUnusable()
    {
        Expr leaf = call("empty", new Expr.Ref("--SEQ", OperandKind.WILDCARD_COLUMN));
        // No domain prefix -> "--SEQ" stays unresolved -> not a local column.
        EvaluationContext noPrefix = gateCtx(Domain.ROW, Map.of(), Map.of(), null, "AESEQ");
        assertFalse(DatasetExpressionCache.cacheableAt(leaf, noPrefix));
    }
}
