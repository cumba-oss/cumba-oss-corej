package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.JoinLookup;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Epic B2 — multi-match (1-to-many) join parity between the LEGACY engine ({@code CheckEvaluator}
 * over the lowered {@code Check}) and the NATIVE engine ({@code NativeExprEvaluator} over the
 * {@code Expr}).
 *
 * <p>
 * A {@code Match_Datasets} join can be 1-to-many. Legacy resolves a joined reference as the
 * <b>first non-null value across all {@code JoinLookup.lookupAll} matches</b> (the joined-dataset
 * lookup). The native name-position path used to resolve an unqualified foreign reference with the
 * scalar first-match {@code JoinLookup.lookup}, which returns {@code null} when the first matched
 * child row's cell is null even though a <em>later</em> matched row is non-null — a latent
 * wrong-verdict divergence now that native is the default executor.
 * </p>
 *
 * <p>
 * Each test below builds a 1-to-many join whose FIRST matched row's cell is null/missing and whose
 * LATER matched row is non-null, then asserts native == legacy (and == the legacy first-non-null
 * value) in both the unqualified name position and the dotted {@code DS.COL} position. The
 * single-match and all-null-matches contracts are pinned too.
 * </p>
 */
class NativeJoinedMultiMatchParityTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

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


    /** A leaf whose value is a column reference (value position resolves another column). */
    private static CheckConditionLeaf refLeaf(String name, String operator, String reference)
    {
        return CheckConditionLeaf.builder().name(name).operator(operator)
                .value(MAPPER.valueToTree(reference)).build();
    }


    /** Runs the leaf through the native engine (the legacy engine is retired). */
    private static BitSet nativeBits(CheckConditionLeaf leaf, EvaluationContext context)
    {
        CheckConditionAll check = new CheckConditionAll(List.of(leaf));
        return NativeExprEvaluator.evaluate(CheckToExpr.toExpr(check), context);
    }


    private static List<List<String>> matches(List<List<String>> rows)
    {
        return new ArrayList<>(rows);
    }

    // (a) Unqualified foreign ref in a comparison (NAME position) ----------------------------


    @Test
    void unqualifiedForeignName_multiMatch_firstNull_laterNonNull_nativeMatchesLegacy()
    {
        // Primary AVAL == foreign QVAL. For row 0 the join is 1-to-many: the first matched child
        // cell is null (scalar lookup -> null) but a later matched cell is "1" (lookupAll -> [1]).
        // Legacy first-non-null = "1" == AVAL "1" -> row 0 fires. The OLD native scalar path saw
        // null and would NOT fire -> divergence. Row 1 has a single non-null match "9" != "2".
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                null, "9"
        };
        List<List<String>> all = matches(List.of(List.of("1"), List.of("9")));

        EvaluationContext context = ctx(primary, firstMatch, all);
        BitSet r = nativeBits(refLeaf(TARGET, "equal_to", "AVAL"), context);
        assertEquals(bits(0), r, "any-match OR reaches the later non-null candidate");

    }

    // (b) Dotted DS.COL reference (NAME position) --------------------------------------------


    @Test
    void dottedForeignName_multiMatch_nativeMatchesLegacy()
    {
        // Dotted SUPP.QVAL in the NAME position. The dotted path is scalar first-match in BOTH
        // the native dottedVector calls
        // JoinLookup.lookup), so it has no 1-to-many divergence — but native must still mirror
        // legacy exactly. Row 0: first match is null -> joined value missing -> AVAL "1" does not
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

    // (c) Single-match case still works ------------------------------------------------------


    @Test
    void unqualifiedForeignName_singleMatch_nativeMatchesLegacy()
    {
        // 1-to-1 join: one non-null match per row. Behaviour must be unchanged from before the fix.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2", "3").build();
        String[] firstMatch =
        {
                "1", "X", "3"
        };
        List<List<String>> all = matches(List.of(List.of("1"), List.of("X"), List.of("3")));

        EvaluationContext context = ctx(primary, firstMatch, all);
        BitSet r = nativeBits(refLeaf(TARGET, "equal_to", "AVAL"), context);
        assertEquals(bits(0, 2), r, "1-to-1 matches: rows 0 and 2 equal their AVAL");

    }

    // (d) All-null-matches case stays missing ------------------------------------------------


    @Test
    void unqualifiedForeignName_allNullMatches_staysMissing_nativeMatchesLegacy()
    {
        // Every matched child cell is null/missing -> lookupAll yields an empty list per row, so
        // the
        // joined value is missing everywhere. equal_to against a present value fires for nobody.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                null, null
        };
        List<List<String>> all = matches(List.of(List.<String> of(), List.<String> of()));

        EvaluationContext context = ctx(primary, firstMatch, all);
        BitSet r = nativeBits(refLeaf(TARGET, "equal_to", "AVAL"), context);
        assertEquals(new BitSet(), r, "all-null matches stay missing: equal_to fires nowhere");

    }

    // ------------------------------------------------------------------
    // B2 (PLAN-native-engine-residuals P1) — ANY-MATCH OR over multiple distinct
    // non-null candidates.
    // ------------------------------------------------------------------


    /** A leaf comparing the joined column against a literal. */
    private static CheckConditionLeaf litLeaf(String operator, String literal)
    {
        return CheckConditionLeaf.builder().name(TARGET).operator(operator)
                .value(MAPPER.valueToTree(literal)).valueIsLiteral(true).build();
    }


    @Test
    void anyMatchOverDistinctNonNullCandidates()
    {
        // Row 0 matches ["N", "Y"]: equal_to "Y" must fire — legacy ORs over ALL candidates, not
        // just the first non-null ("N"). Row 1 matches ["N"] only: no fire.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                "N", "N"
        };
        List<List<String>> all = matches(List.of(List.of("N", "Y"), List.of("N")));

        BitSet r = nativeBits(litLeaf("equal_to", "Y"), ctx(primary, firstMatch, all));
        assertEquals(bits(0), r, "any-match OR over ALL candidates reaches \"Y\" on row 0");
    }


    @Test
    void anyMatchForNegatedEqualityAndMembershipAndRegex()
    {
        // not_equal_to "Y": row 0 ["Y","Y"] → no candidate differs → no fire; row 1 ["Y","N"] →
        // "N" differs → fires. Same any-match contract for membership and regex.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                "Y", "Y"
        };
        List<List<String>> all = matches(List.of(List.of("Y", "Y"), List.of("Y", "N")));
        EvaluationContext context = ctx(primary, firstMatch, all);

        BitSet neq = nativeBits(litLeaf("not_equal_to", "Y"), context);
        assertEquals(bits(1), neq);

        CheckConditionLeaf in = CheckConditionLeaf.builder().name(TARGET)
                .operator("is_contained_by").value(MAPPER.createArrayNode().add("N")).build();
        BitSet mem = nativeBits(in, context);
        assertEquals(bits(1), mem);

        CheckConditionLeaf rx = CheckConditionLeaf.builder().name(TARGET).operator("matches_regex")
                .value(MAPPER.valueToTree("^N")).valueIsLiteral(true).build();
        BitSet re = nativeBits(rx, context);
        assertEquals(bits(1), re);
    }


    @Test
    void missingProbeForUnmatchedRowsOfTheLiveLookup()
    {
        // Row 0 has matches; row 1 has NONE — the live lookup matched elsewhere, so row 1 votes
        // once with a missing probe: `empty` fires there (and nowhere else), while equal_to
        // against a concrete literal does not fire on the probe.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                "N", null
        };
        List<List<String>> all = matches(List.of(List.of("N"), List.<String> of()));
        EvaluationContext context = ctx(primary, firstMatch, all);

        BitSet empty = nativeBits(
                CheckConditionLeaf.builder().name(TARGET).operator("empty").build(), context);
        assertEquals(bits(1), empty, "the unmatched row votes once with a missing probe");

        BitSet eq = nativeBits(litLeaf("equal_to", "Y"), context);
        assertEquals(new BitSet(), eq);
    }


    @Test
    void lenAndUpperWrappersPropagateCandidates()
    {
        // longer_than raises to len(X) > n and the case-insensitive membership to upper(X) in […]
        // — the pure unary wrapper must keep the candidate list (transformed per value) so the
        // any-match OR still applies. Row 0: ["AB", "ABCD"] → len 4 > 2 fires; row 1: ["AB"] → no.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                "AB", "AB"
        };
        List<List<String>> all = matches(List.of(List.of("AB", "ABCD"), List.of("AB")));
        EvaluationContext context = ctx(primary, firstMatch, all);

        CheckConditionLeaf longer = CheckConditionLeaf.builder().name(TARGET)
                .operator("longer_than").value(MAPPER.valueToTree(2)).build();
        BitSet len = nativeBits(longer, context);
        assertEquals(bits(0), len, "candidate ABCD (len 4) > 2 fires row 0 only");

        // upper-propagation: candidates ["n","Y"] vs case-insensitive set ["y"] → "Y" matches.
        List<List<String>> ci = matches(List.of(List.of("n", "Y"), List.of("n")));
        EvaluationContext ciCtx = ctx(primary, new String[]
        {
                "n", "n"
        }, ci);
        CheckConditionLeaf inCi = CheckConditionLeaf.builder().name(TARGET)
                .operator("is_contained_by_case_insensitive")
                .value(MAPPER.createArrayNode().add("y")).build();
        BitSet r = nativeBits(inCi, ciCtx);
        assertEquals(bits(0), r);
    }


    @Test
    void lenOverEmptyStringCandidateIsLengthZero()
    {
        // operator-examples.md A.5 / function-examples.md: len("")=0, so an empty-string joined
        // candidate has length 0 and the length operators evaluate it literally. shorter_than 5
        // therefore fires (0 < 5) on the empty candidate, in both backends.
        IDataTable primary = MockTable.of().col("AVAL", "1").build();
        String[] firstMatch =
        {
                ""
        };
        List<List<String>> all = matches(List.of(List.of("")));
        EvaluationContext context = ctx(primary, firstMatch, all);

        CheckConditionLeaf shorter = CheckConditionLeaf.builder().name(TARGET)
                .operator("shorter_than").value(MAPPER.valueToTree(5)).build();
        BitSet r = nativeBits(shorter, context);
        assertEquals(bits(0), r, "len(\"\")=0 < 5 fires on the empty candidate");
    }


    @Test
    void stringPartPlanAppliesAnyMatch()
    {
        // R-P7 review M3: the does_not_equal_string_part plan must OR over joined candidates
        // like every Primitives-routed predicate. The part captured from AVAL "Y-9" is "Y"
        // (differsFromStringPart anchors a full matches()); row 0's candidates ["Y","X"]: the
        // FIRST equals the part (a first-non-null plan would not fire) but "X" differs → the
        // any-match OR fires; row 1's single candidate "Y" equals → no fire.
        IDataTable primary = MockTable.of().col("AVAL", "Y-9", "Y-9").build();
        String[] firstMatch =
        {
                "Y", "Y"
        };
        List<List<String>> all = matches(List.of(List.of("Y", "X"), List.of("Y")));
        EvaluationContext context = ctx(primary, firstMatch, all);

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name(TARGET)
                .operator("does_not_equal_string_part").value(MAPPER.valueToTree("AVAL"))
                .regex("^([A-Z])-[0-9]$").build();
        BitSet r = nativeBits(leaf, context);
        assertEquals(bits(0), r, "any-match OR fires on the differing candidate only");
    }


    @Test
    void noLiveLookupCastsNoVoteEvenForEmpty()
    {
        // The join's SCHEMA carries the column but NO row anywhere has a match: the column is not
        // genuinely absent (it exists in the joined dataset), so the "absent column is empty" model
        // does not apply. Legacy forEachJoinedValue never latches a lookup and returns an empty
        // BitSet — even `empty` must not fire (unlike the per-row missing probe of a partly-latched
        // lookup).
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        String[] firstMatch =
        {
                null, null
        };
        List<List<String>> all = matches(List.of(List.<String> of(), List.<String> of()));
        EvaluationContext context = ctx(primary, firstMatch, all);

        BitSet r = nativeBits(CheckConditionLeaf.builder().name(TARGET).operator("empty").build(),
                context);
        assertEquals(new BitSet(), r, "a never-latched lookup casts no vote, even for empty");
    }


    @Test
    void liveLookupLatchPicksFirstLookupWithAnyMatch()
    {
        // Two joins carry the column. The FIRST (in registration order) has no data match
        // anywhere, the second does — legacy latches the second; verdicts must agree.
        IDataTable primary = MockTable.of().col("AVAL", "1", "2").build();
        IDataTable suppA = MockTable.of().name("SUPPA").col(TARGET, "x").build();
        IDataTable suppB = MockTable.of().name("SUPPB").col(TARGET, "x").build();
        DatasetResolver resolver = ds -> "SUPPA".equals(ds) ? suppA
                : ("SUPPB".equals(ds) ? suppB : null);
        JoinLookup dead = new MultiMatchLookup(TARGET, new String[]
        {
                null, null
        }, matches(List.of(List.<String> of(), List.<String> of())));
        JoinLookup live = new MultiMatchLookup(TARGET, new String[]
        {
                "N", "Y"
        }, matches(List.of(List.of("N", "Y"), List.of("Y"))));
        java.util.LinkedHashMap<String, JoinLookup> joins = new java.util.LinkedHashMap<>();
        joins.put("SUPPA", dead);
        joins.put("SUPPB", live);
        EvaluationContext context = EvaluationContext.builder().table(primary)
                .datasetResolver(resolver).joinedDatasets(joins).build();

        BitSet r = nativeBits(litLeaf("equal_to", "Y"), context);
        assertEquals(bits(0, 1), r, "the live lookup's candidates decide both rows");
    }
}
