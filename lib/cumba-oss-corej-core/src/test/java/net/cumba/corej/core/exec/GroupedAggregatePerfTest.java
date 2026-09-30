package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Random;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * ⚑ A timing harness, not a gate (combined review of runbook W2–W8, W5/W6 L2 / L3 and XCUT PERF 2 /
 * 3): the grouped readers over a 10⁶-row synthetic table (fixed seed), each evaluated and read on
 * every row, the elapsed milliseconds printed. It runs only with {@code -Dcorej.perf=true}, so CI
 * never times anything; run it on the pre-fix and the post-fix tree and compare the lines it
 * prints. The assertions only prove the work was done (a vector that is never read measures
 * nothing).
 */
@EnabledIfSystemProperty(named = "corej.perf", matches = "true")
class GroupedAggregatePerfTest
{

    private static final int ROWS = 1_000_000;

    private static final int SUBJECTS = 100_000;

    /** ADLB-like: 10⁶ rows, 10⁵ subjects × two parameters, numeric AVAL, a character date. */
    private static IDataTable lab()
    {
        Random random = new Random(20260929L);
        String[] usubjid = new String[ROWS];
        String[] paramcd = new String[ROWS];
        Double[] aval = new Double[ROWS];
        String[] dtc = new String[ROWS];
        String[] dtc2 = new String[ROWS];
        for (int r = 0; r < ROWS; r++)
        {
            usubjid[r] = "S" + (r % SUBJECTS);
            paramcd[r] = r / SUBJECTS % 2 == 0 ? "ALT" : "AST";
            aval[r] = Math.floor(random.nextDouble() * 1000.0) / 10.0;
            dtc[r] = "2024-" + String.format(java.util.Locale.ROOT, "%02d-%02d",
                    1 + random.nextInt(12), 1 + random.nextInt(28));
            dtc2[r] = "2024-" + String.format(java.util.Locale.ROOT, "%02d-%02d",
                    1 + random.nextInt(12), 1 + random.nextInt(28));
        }
        return RealTableFixture.of("LB").str("USUBJID", usubjid).str("PARAMCD", paramcd)
                .dbl("AVAL", aval).str("LBDTC", dtc).str("LBDTC2", dtc2).build();
    }


    private static IDataTable subjects()
    {
        String[] usubjid = new String[SUBJECTS];
        for (int s = 0; s < SUBJECTS; s++)
        {
            usubjid[s] = "S" + s;
        }
        return RealTableFixture.of("DM").str("USUBJID", usubjid).build();
    }


    /** Evaluates {@code call} on a fresh execution (its own memo) and reads every row. */
    private static long timed(String label, String call, IDataTable primary, DatasetResolver study)
    {
        long best = Long.MAX_VALUE;
        long checksum = 0;
        for (int rep = 0; rep < 3; rep++)
        {
            EvaluationContext ctx = EvaluationContext.builder().table(primary)
                    .datasetResolver(study).build();
            long start = System.nanoTime();
            Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
            assertNotNull(v);
            long sum = 0;
            for (int r = 0; r < ctx.rowCount(); r++)
            {
                sum += v.value(r).cell().isMissingOrInvalid() ? 0 : 1;
            }
            best = Math.min(best, System.nanoTime() - start);
            checksum = sum;
        }
        System.out.println("[corej.perf] " + label + ": " + best / 1_000_000 + " ms (best of 3), "
                + checksum + " present answers");
        return checksum;
    }


    @Test
    void theGroupedReadersOverAMillionRows()
    {
        IDataTable lb = lab();
        IDataTable dm = subjects();
        DatasetResolver study = name -> "LB".equalsIgnoreCase(name) ? lb
                : "DM".equalsIgnoreCase(name) ? dm : null;
        // max over the evaluated dataset itself (L3's numeric path, PERF 3's by-row answers)
        assertEquals(ROWS, timed("max primary", "max(AVAL, group=[USUBJID, PARAMCD])", lb, study));
        // a foreign date extreme broadcast by key (PERF 3's key columns resolved once)
        assertEquals(SUBJECTS, timed("max_date foreign",
                "max_date(LBDTC, domain=LB, group=[USUBJID])", dm, study));
        // record_count grouped over the primary (L2's per-block cell) and foreign
        assertEquals(ROWS,
                timed("record_count primary", "record_count(group=[USUBJID, PARAMCD])", lb, study));
        assertEquals(SUBJECTS, timed("record_count foreign",
                "record_count(domain=LB, group=[USUBJID])", dm, study));
        // read_value grouped (the Aggregator seam)
        assertEquals(SUBJECTS, timed("read_value grouped",
                "read_value(AVAL, domain=LB, mode=\"MAX\", group=[USUBJID])", dm, study));
        // PLAN-scalar-date-extremes P1 (i): the per-row pair extreme over two bound columns,
        // beside a bare column read and the grouped min_date over the same rows.
        assertEquals(ROWS, timed("column read", "LBDTC", lb, study));
        assertEquals(ROWS,
                timed("earliest_date primary", "earliest_date(LBDTC, LBDTC2)", lb, study));
        assertEquals(ROWS, timed("latest_date primary", "latest_date(LBDTC, LBDTC2)", lb, study));
        assertEquals(ROWS,
                timed("min_date primary", "min_date(LBDTC, group=[USUBJID, PARAMCD])", lb, study));
    }
}
