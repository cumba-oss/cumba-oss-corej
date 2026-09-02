package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.JoinLookup;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.ast.Expr.BinOp;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the R9-2 resolution: an <b>unqualified</b> name that is absent from the primary table
 * but present in a {@code Match_Datasets} join resolves via the same scalar
 * {@link JoinLookup#lookup} the <b>qualified</b> {@code DS.COL} form uses — so qualified and
 * unqualified foreign references are treated identically. A genuinely-missing name (in neither the
 * primary table nor any join) still yields an empty {@link BitSet} (the missing-column contract).
 */
@ExtendWith(MockitoExtension.class)
class NativeJoinedLhsTest
{

    private static Expr ref(String n, OperandKind kind)
    {
        return new Expr.Ref(n, kind);
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    @Test
    void unqualifiedForeignLhsMatchesQualified()
    {
        IDataTable primary = MockTable.of().col("AVAL", "1", "2", "3").build();
        IDataTable dm = MockTable.of().name("DM").col("DMVAL", "1", "X", "3").build();
        DatasetResolver resolver = ds -> "DM".equals(ds) ? dm : null;

        // Row-aligned join lookup (test simplification): primary row i ↔ DM row i.
        JoinLookup lookup = new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                int idx = dm.getMetaData().getColumnIndex(columnName);
                return idx < 0 ? null : dm.getColumn(idx).getDataValue(row).getValueAsString();
            }


            @Override
            public String getDatasetName()
            {
                return "DM";
            }
        };

        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("DM", lookup)).build();

        // Unqualified foreign LHS: DMVAL == AVAL → [1,X,3] vs [1,2,3] → rows 0,2 equal.
        Expr unqualified = new Expr.Binary(BinOp.EQ, ref("DMVAL", OperandKind.COLUMN),
                ref("AVAL", OperandKind.COLUMN));
        // Qualified foreign LHS: DM.DMVAL == AVAL → must give the identical result.
        Expr qualified = new Expr.Binary(BinOp.EQ, ref("DM.DMVAL", OperandKind.DOTTED_REF),
                ref("AVAL", OperandKind.COLUMN));

        BitSet unq = NativeExprEvaluator.evaluate(unqualified, ctx);
        BitSet qua = NativeExprEvaluator.evaluate(qualified, ctx);

        assertEquals(bits(0, 2), unq, "unqualified foreign LHS resolves via the join");
        assertEquals(qua, unq, "qualified and unqualified foreign references behave identically");
    }


    @Test
    void genuinelyMissingNameStillEmpty()
    {
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        IDataTable dm = MockTable.of().name("DM").col("DMVAL", "1", "2").build();
        DatasetResolver resolver = ds -> "DM".equals(ds) ? dm : null;
        JoinLookup lookup = new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                int idx = dm.getMetaData().getColumnIndex(columnName);
                return idx < 0 ? null : dm.getColumn(idx).getDataValue(row).getValueAsString();
            }


            @Override
            public String getDatasetName()
            {
                return "DM";
            }
        };
        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("DM", lookup)).build();

        // GHOST is in neither the primary table nor DM → empty BitSet.
        Expr e = new Expr.Binary(BinOp.EQ, ref("GHOST", OperandKind.COLUMN),
                new Expr.Lit(Expr.LitKind.STRING, "1"));
        assertEquals(new BitSet(), NativeExprEvaluator.evaluate(e, ctx));
    }

}
