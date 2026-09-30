package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Timing harness for {@link SuppPivot} over a 10⁶-row parent (combined review of runbook W2–W8,
 * XCUT PERF 1 / W2 L5) — run with {@code -Dcorej.perf=true}; it prints the elapsed time of
 * materialising one qualifier column for five rules sharing one run cache.
 */
class SuppPivotPerfTest
{

    private static final int ROWS = 1_000_000;

    private static final int SUBJECTS = 2_000;

    @Test
    @EnabledIfSystemProperty(named = "corej.perf", matches = "true")
    void fiveRulesReadOneQualifierOfAMillionRowParent()
    {
        String[] usubjid = new String[ROWS];
        Long[] pcseq = new Long[ROWS];
        String[] stresc = new String[ROWS];
        for (int i = 0; i < ROWS; i++)
        {
            usubjid[i] = "S" + (i % SUBJECTS);
            pcseq[i] = (long) (i / SUBJECTS + 1);
            stresc[i] = i % 7 == 0 ? "BLQ" : "4.1";
        }
        IDataTable pc = RealTableFixture.of("PC").str("USUBJID", usubjid).lng("PCSEQ", pcseq)
                .str("PCSTRESC", stresc).build();
        int suppRows = ROWS / 10;
        String[] su = new String[suppRows];
        String[] rd = new String[suppRows];
        String[] idvar = new String[suppRows];
        String[] idvarval = new String[suppRows];
        String[] qnam = new String[suppRows];
        String[] qval = new String[suppRows];
        for (int i = 0; i < suppRows; i++)
        {
            int parentRow = i * 10;
            rd[i] = "PC";
            su[i] = usubjid[parentRow];
            idvar[i] = "PCSEQ";
            idvarval[i] = String.valueOf(pcseq[parentRow]);
            qnam[i] = "PCCALCN";
            qval[i] = "0.0" + (i % 9);
        }
        IDataTable supp = RealTableFixture.of("SUPPPC").str("RDOMAIN", rd).str("USUBJID", su)
                .str("IDVAR", idvar).str("IDVARVAL", idvarval).str("QNAM", qnam).str("QVAL", qval)
                .build();
        DatasetResolver resolver = name -> "SUPPPC".equalsIgnoreCase(name) ? supp
                : "PC".equalsIgnoreCase(name) ? pc : null;
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        long start = System.nanoTime();
        for (int rule = 0; rule < 5; rule++)
        {
            EvaluationContext ctx = EvaluationContext.builder().table(pc).ruleId("T-" + rule)
                    .datasetResolver(resolver).sharedIndexCache(cache).build();
            IDataTableColumn column = SuppPivot.qualifierColumn(ctx, "PCCALCN");
            assertNotNull(column);
            assertEquals("0.00", column.getDataValue(0).getValueAsString());
            assertEquals("", column.getDataValue(1).getValueAsString());
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("SuppPivotPerfTest: 5 rules x 1e6 rows: " + ms + " ms; index builds "
                + cache.suppQnamIndexBuildCount());
    }
}
