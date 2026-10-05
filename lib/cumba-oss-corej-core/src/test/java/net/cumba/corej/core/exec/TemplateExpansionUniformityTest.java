package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.matchJ;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.rows;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} T2 r1 M6 — the {@code WildcardExpander} marker
 * template expansion of a Check: a bare {@code TRTxxP} expands over the primary's matching columns
 * and a qualified {@code J.TRTxxP} must expand over the JOINED dataset's, reading each through the
 * join exactly as the bare expansion reads the primary.
 *
 * <p>
 * N28 — served since {@code PLAN-merged-evaluation-table} W3d (MQ6): the dotted wildcard name is
 * matched against the {@code J.}-prefixed real columns of the dataset the rule's entry {@code J}
 * names ({@code WildcardExpander.columnsFor}), so {@code J.TRTxxP} mints {@code J.TRT01P} and the
 * expansion reads the join. Before W3d it was matched whole against the PRIMARY's names and the
 * rule was dropped ({@code NoMatch}); zero corpus carriers either way (phase-0 census).
 * </p>
 */
class TemplateExpansionUniformityTest
{

    private static IDataTable withTreatments(String name, String first, String second)
    {
        return RealTables.of(name).str("K", "k1", "k2", "k3").str("TRT01P", first, "", "b")
                .str("TRT02P", "", second, "c").build();
    }


    private static WildcardExpander.ExpansionResult expand(String expression, IDataTable p,
            IDataTable j)
    {
        Rule template = loadClean(
                ruleJson("Record", matchJ("") + "," + check(expression) + "," + outcome("K")),
                true);
        ScopeVariableSource source = ScopeVariableSource.of(exactInventory(p, j), p);
        return WildcardExpander.tryExpand(template, p.getMetaData(),
                new TokenExpander.Context(source, null, "P"));
    }


    private static List<List<Long>> firedPerExpansion(WildcardExpander.ExpansionResult result,
            IDataTable p, IDataTable j)
    {
        List<List<Long>> out = new ArrayList<>();
        for (Rule concrete : assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                result).rules())
        {
            out.add(rows(executed(concrete, p, exactInventory(p, j))));
        }
        return out;
    }


    @Test
    void aBareTemplateExpandsOverThePrimaryAndReadsIt()
    {
        IDataTable p = withTreatments("P", "a", "a");
        IDataTable j = withTreatments("J", "x", "y");
        List<List<Long>> fired = firedPerExpansion(expand("empty(TRTxxP)", p, j), p, j);
        assertEquals(List.of(List.of(2L), List.of(1L)), fired,
                "TRT01P is blank on row 2, TRT02P on row 1 — P's own columns");
    }


    /**
     * N28 served ({@code PLAN-merged-evaluation-table} W3d, MQ6): the qualified template expands
     * over the JOINED dataset's real columns, each expansion reading through the join — the same
     * rows the bare template fires over a primary carrying J's columns. RED before (the pin this
     * replaces): {@code NoMatch}, the rule dropped.
     */
    @Test
    void aQualifiedTemplateExpandsOverTheJoinedDatasetAndReadsThroughTheJoin()
    {
        IDataTable p = withTreatments("P", "a", "a");
        IDataTable j = withTreatments("J", "x", "y");
        IDataTable pLikeJ = withTreatments("P", "x", "y");
        List<List<Long>> reference = firedPerExpansion(expand("empty(TRTxxP)", pLikeJ, j), pLikeJ,
                j);
        assertEquals(List.of(List.of(2L), List.of(1L)), reference);
        assertEquals(reference, firedPerExpansion(expand("empty(J.TRTxxP)", p, j), p, j),
                "J.TRT01P and J.TRT02P, read through the join: J's blanks, not P's");
        // P-19: the qualifier's case is immaterial — j.TRTxxP enumerates the entry J
        assertEquals(reference, firedPerExpansion(expand("empty(j.TRTxxP)", p, j), p, j));
    }


    /**
     * A qualifier naming no entry of the rule enumerates nothing (D3 of the absorbed
     * {@code PLAN-qualified-template-expansion}: Stage A's observe-only
     * {@code DOTTED_REF_UNDECLARED} judges the spelling, the expansion contributes nothing) — the
     * template's own no-match, exactly as a bare template over a primary without the columns.
     */
    @Test
    void anUndeclaredQualifierEnumeratesNothing()
    {
        IDataTable p = withTreatments("P", "a", "a");
        IDataTable j = withTreatments("J", "x", "y");
        assertInstanceOf(WildcardExpander.ExpansionResult.NoMatch.class,
                expand("empty(Q.TRTxxP)", p, j));
        // and a bare template never sees the join's columns (MQ6, UVC): P without TRT columns
        IDataTable pBare = RealTables.of("P").str("K", "k1", "k2", "k3").build();
        assertInstanceOf(WildcardExpander.ExpansionResult.NoMatch.class,
                expand("empty(TRTxxP)", pBare, j));
    }
}
