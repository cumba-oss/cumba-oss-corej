package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.matchJ;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.List;
import java.util.function.Supplier;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.testkit.IDataTableReadConcurrencyContract;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-bugfix-engine-define} RC (T11): the engine's two table implementations run through
 * the testkit's read-concurrency contract — the <b>cold arm</b> ({@code checkCold}: fresh,
 * untouched instances read under contention, so a lazily filled cursor or cache published without
 * synchronisation is caught at its first touch) and the warm arm. {@link RowMappedTable} holds the
 * lazily computed real-row projection; {@link GroupReadTable} appends merged columns over a base.
 * The census verdict was "safe"; a red here is a finding, not a flake. The vacuity control of the
 * contract itself proves the threads really read at the same time.
 */
class CorejTableReadConcurrencyTest
{

    /** P: k1, k2, k3, k4 — the base of a row map with repeats (0, 0; 2, 2) and a gap (1). */
    private static IDataTable base()
    {
        return RealTables.of("P").str("K", "k1", "k2", "k3", "k4").str("S", "a", "b", "c", "d")
                .lng("N", 1L, 2L, 3L, 4L).build();
    }


    private static IDataTable rowMapped()
    {
        IDataBufferNumeric rowMap = DataBufferFactory.get().createForRange(0, 3);
        for (long position : new long[]
        {
                0, 0, 2, 2, 3
        })
        {
            rowMap.addValue(position);
        }
        return new RowMappedTable(base(), rowMap);
    }


    @Test
    void rowMappedTableReadsAgreeUnderContentionColdAndWarm()
    {
        Supplier<IDataTable> fresh = CorejTableReadConcurrencyTest::rowMapped;
        assertNotSame(fresh.get(), fresh.get(), "the supplier hands out fresh instances");
        assertEquals(5, fresh.get().getRowCount());
        IDataTableReadConcurrencyContract.checkCold(fresh);
        IDataTableReadConcurrencyContract.check(rowMapped());
    }


    /**
     * The evaluated view of a left join P ⟕ J on K, with J.S appended by {@link GroupReadTable}.
     */
    private static IDataTable groupRead()
    {
        IDataTable p = RealTables.of("P").str("K", "k1", "k2", "k3").str("G", "g", "g", "h")
                .build();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2").str("S", "a", "b").build();
        Rule rule = loadClean(
                ruleJson("Record", matchJ("") + "," + check("K == J.K") + "," + outcome("K")),
                true);
        EvaluationContext ctx = ExecCalls.viewContext(rule, p, exactInventory(p, j), null).build();
        IDataTable table = GroupReadTable.of(ctx, List.of("J.S", "G"));
        assertInstanceOf(GroupReadTable.class, table, "J.S is a merged column: a column is added");
        return table;
    }


    @Test
    void groupReadTableReadsAgreeUnderContentionColdAndWarm()
    {
        Supplier<IDataTable> fresh = CorejTableReadConcurrencyTest::groupRead;
        assertNotSame(fresh.get(), fresh.get(), "the supplier hands out fresh instances");
        IDataTableReadConcurrencyContract.checkCold(fresh);
        IDataTableReadConcurrencyContract.check(groupRead());
    }
}
