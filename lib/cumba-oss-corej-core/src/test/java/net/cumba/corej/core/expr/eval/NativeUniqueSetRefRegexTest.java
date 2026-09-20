package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plan J6 / J6b — the native {@code is_(not_)unique_set} path on CDISC-CG0562-shaped checks:
 *
 * <ul>
 * <li><b>J6</b> — a {@code $}-reference key member (e.g. {@code $TIMING_VARIABLES} from
 * {@code get_dataset_filtered_variables}) is expanded to its column list at run time, so the key
 * tuple no longer silently collapses (which previously over-flagged every otherwise-identical
 * row).</li>
 * <li><b>J6b</b> — the {@code regex=} kwarg normalizes a matching key column before grouping.</li>
 * </ul>
 *
 * <p>
 * Both the native ({@link net.cumba.corej.core.expr.eval.NativeExprEvaluator}) and legacy
 * ({@code CheckEvaluator}) folds are asserted equal, since the {@code $}-splice was applied to both
 * paths.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class NativeUniqueSetRefRegexTest
{

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    private static EvaluationContext ctx(IDataTable t, Map<String, Object> vars)
    {
        return EvaluationContext.builder().table(t).variables(vars).build();
    }


    private static String notUniqueSet(String name, String regex, String... keys)
    {
        StringBuilder source = new StringBuilder("not is_unique_set([").append(name);
        for (String k : keys)
        {
            source.append(", ").append(k);
        }
        source.append("]");
        if (regex != null)
        {
            source.append(", regex=\"").append(regex.replace("\\", "\\\\")).append("\"");
        }
        return source.append(")").toString();
    }


    /** The native verdict must equal {@code expected}. */
    private static void assertParity(String source, EvaluationContext c, BitSet expected)
    {
        BitSet nativ = NativeExprEvaluator
                .evaluate(net.cumba.corej.core.expr.CheckExpressionParser.parse(source), c);
        assertEquals(expected, nativ, "native verdict");
    }

    // ---- J6: $-ref key expansion ---------------------------------------------------------------


    @Test
    void timingRefDistinguishesOtherwiseIdenticalRows()
    {
        // keys=[USUBJID, VSTESTCD, $TIMING_VARIABLES] with $TIMING_VARIABLES -> [VSTPTNUM].
        // The two rows share (REPNUM,USUBJID,VSTESTCD) but differ on VSTPTNUM, so the expanded key
        // makes them distinct -> NOT duplicates. (Before the fix the $-ref collapsed and both rows
        // were wrongly flagged.)
        IDataTable t = MockTable.of().col("VSREPNUM", "1", "1").col("USUBJID", "S1", "S1")
                .col("VSTESTCD", "SYSBP", "SYSBP").col("VSTPTNUM", "1", "2").build();
        EvaluationContext c = ctx(t, Map.of("$TIMING_VARIABLES", List.of("VSTPTNUM")));
        assertParity(notUniqueSet("VSREPNUM", null, "USUBJID", "VSTESTCD", "$TIMING_VARIABLES"), c,
                new BitSet());
    }


    @Test
    void expandedTimingColumnStillParticipatesInTheKey()
    {
        // Same shape, but now VSTPTNUM is equal on both rows -> the full expanded key collides ->
        // duplicates {0,1}. Proves the spliced column is actually part of the key (not dropped).
        IDataTable t = MockTable.of().col("VSREPNUM", "1", "1").col("USUBJID", "S1", "S1")
                .col("VSTESTCD", "SYSBP", "SYSBP").col("VSTPTNUM", "1", "1").build();
        EvaluationContext c = ctx(t, Map.of("$TIMING_VARIABLES", List.of("VSTPTNUM")));
        assertParity(notUniqueSet("VSREPNUM", null, "USUBJID", "VSTESTCD", "$TIMING_VARIABLES"), c,
                bits(0, 1));
    }

    // ---- J6b: regex normalization end-to-end ---------------------------------------------------


    @Test
    void regexGroupsDatetimeKeyAtDateGranularity()
    {
        // keys=[USUBJID, VSDTC] + regex date prefix: two rows on the same date (different times)
        // collapse to one key -> duplicates {0,1}.
        IDataTable t = MockTable.of().col("VSREPNUM", "1", "1").col("USUBJID", "S1", "S1")
                .col("VSDTC", "2020-01-15T08:00", "2020-01-15T09:00").build();
        EvaluationContext c = ctx(t, Map.of());
        assertParity(notUniqueSet("VSREPNUM", "^\\d{4}-\\d{2}-\\d{2}", "USUBJID", "VSDTC"), c,
                bits(0, 1));
    }
}
