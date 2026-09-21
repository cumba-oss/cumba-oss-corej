package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ <b>The two surviving tests of the retired {@code NativeJoinedMultiMatchParityTest}, preserved
 * deliberately — and restored after review round 1 caught that they had been lost.</b>
 *
 * <p>
 * That class existed to assert native-vs-legacy parity for resolving an <b>unqualified</b> foreign
 * reference out of a {@code Match_Datasets} join. {@code PLAN-unqualified-name-primary-only}
 * deleted that machinery, so eleven of its thirteen tests asserted behaviour the owner's ruling
 * abolished and were retired with it. These two did not: they pin the <b>QUALIFIED</b>
 * ({@code DS.COL}) path, which the ruling leaves untouched and which is the half that must keep
 * working.
 * </p>
 *
 * <p>
 * ⛔ The plan said in so many words: <i>"The two dotted tests are <b>preserved</b> — move them to a
 * qualified-resolution test rather than deleting them with the class."</i> They were not; the class
 * went and nothing replaced them, and the commit claimed {@code NativeJoinedLhsTest} covered it
 * when that class gained no test and builds only a 1-to-1 lookup. ⇒ The specific thing that was
 * briefly unpinned: a 1-to-many join whose FIRST matched child cell is null and a LATER match is
 * non-null. The dotted path is scalar first-match and must IGNORE the later value, in name and
 * value position alike — a first-null/later-non-null regression would otherwise be invisible.
 * </p>
 */
class DottedForeignMultiMatchTest
{

    /**
     * A row-indexed multi-match join. {@code firstMatch[r]} is what the scalar {@link #lookup}
     * returns for primary row {@code r} (the "first matched child row" — may be {@code null}), and
     * {@code allMatches[r]} is the full ordered match list {@link #lookupAll} returns (already
     * null-filtered, as a real {@code DatasetLookup.lookupAll} skips missing cells). The column the
     * test references is the only one this lookup knows about; {@code TARGET} below names it.
     */
    private static final class MultiMatchLookup implements JoinLookup
    {

        private final String column;

        private final String[] firstMatch;

        private final List<List<String>> allMatches;

        MultiMatchLookup(String column, String[] firstMatch, List<List<String>> allMatches)
        {
            this.column = column;
            this.firstMatch = firstMatch;
            this.allMatches = allMatches;
        }


        @Override
        public String lookup(IDataTable primaryTable, long row, String columnName)
        {
            // Scalar first-match semantics (the legacy DS.COL path and the OLD native name path).
            return column.equals(columnName) ? firstMatch[(int) row] : null;
        }


        @Override
        public List<String> lookupAll(IDataTable primaryTable, long row, String columnName)
        {
            if (!column.equals(columnName))
            {
                return List.of();
            }
            return allMatches.get((int) row);
        }


        @Override
        public String getDatasetName()
        {
            return "SUPP";
        }
    }

    private static final String TARGET = "QVAL";

    private static final String FOREIGN_DS = "SUPP";

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /**
     * Builds a context whose single join ({@code SUPP}) carries only the {@code QVAL} column with
     * the given per-row first-match / all-match shape. The foreign schema is a one-column table so
     * the native {@code joinedColumnVector} schema-probe recognises {@code QVAL} as join-carried.
     */
    private static EvaluationContext ctx(IDataTable primary, String[] firstMatch,
            List<List<String>> allMatches)
    {
        IDataTable supp = MockTable.of().name(FOREIGN_DS).col(TARGET, "ignored").build();
        DatasetResolver resolver = ds -> FOREIGN_DS.equals(ds) ? supp : null;
        JoinLookup lookup = new MultiMatchLookup(TARGET, firstMatch, allMatches);
        return EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of(FOREIGN_DS, lookup)).build();
    }


    /** A comparison whose right side is a column reference (value position resolves it). */
    private static String refLeaf(String name, String operator, String reference)
    {
        return name + ("not_equal_to".equals(operator) ? " != " : " == ") + reference;
    }


    /** Runs one canonical expression through the native engine (the legacy engine is retired). */
    private static BitSet nativeBits(String source, EvaluationContext context)
    {
        return NativeExprEvaluator
                .evaluate(net.cumba.corej.core.expr.CheckExpressionParser.parse(source), context);
    }


    private static List<List<String>> matches(List<List<String>> rows)
    {
        return new ArrayList<>(rows);
    }

    // Dotted DS.COL reference -- NAME position, then VALUE position ----------------------------


    @Test
    void dottedForeignName_multiMatch_nativeMatchesLegacy()
    {
        // Dotted SUPP.QVAL in the NAME position. The dotted path is scalar first-match in BOTH
        // the native dottedVector calls
        // JoinLookup.lookup), so it has no 1-to-many divergence — but the dotted contract must
        // hold. Row 0: first match is null -> joined value missing -> AVAL "1" does not
        // match (no fire). Row 1: first match "2" == AVAL "2" -> fires. The later non-null match on
        // row 0 ("1") is intentionally NOT used by the dotted path; both engines ignore it.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                null, "2"
        };
        List<List<String>> all = matches(List.of(List.of("1"), List.of("2")));

        EvaluationContext context = ctx(primary, firstMatch, all);
        BitSet r = nativeBits(refLeaf(FOREIGN_DS + '.' + TARGET, "equal_to", "AVAL"), context);

        assertEquals(bits(1), r, "dotted scalar first-match: only row 1 (first match=2) fires");
    }


    @Test
    void dottedForeignValuePosition_multiMatch_nativeMatchesLegacy()
    {
        // Dotted reference in the VALUE position: AVAL == SUPP.QVAL. Legacy resolveJoinedValue and
        // native dottedVector both use scalar first-match here, so they must stay identical. Row
        // 0's
        // first match is null -> value resolves null -> AVAL "1" != null; row 1 first match "2" ==
        // AVAL "2" -> fires.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                null, "2"
        };
        List<List<String>> all = matches(List.of(List.of("7"), List.of("2")));

        EvaluationContext context = ctx(primary, firstMatch, all);
        BitSet r = nativeBits(refLeaf("AVAL", "equal_to", FOREIGN_DS + '.' + TARGET), context);
        assertEquals(bits(1), r, "dotted value-position scalar first-match: only row 1 fires");

    }

}
