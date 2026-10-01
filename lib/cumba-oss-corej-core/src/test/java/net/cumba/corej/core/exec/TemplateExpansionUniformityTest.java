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
 * ⚠ Known red <b>N28</b> (measured here, recorded, not fixed): a qualified marker template in a
 * Check is collected as a wildcard name whole ({@code WildcardExpander.isWildcard} sees its
 * lower-case markers), parsed into a regex over the WHOLE dotted text and matched against the
 * PRIMARY's column names ({@code WildcardExpander.expand}: {@code meta.getColumn(i).getName()}),
 * which no primary column can match — so the rule never expands ({@code NoMatch}) and is dropped,
 * while the same template bare expands. The {@code Requirements.Variables} entry grammar handles
 * the qualifier ({@code scopeVariableWildcardPattern} parses the variable half), the Check
 * expansion does not. Zero corpus carriers (phase-0 census, T2 r1: no Check carries a qualified
 * marker template).
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


    /** ⚠ Known red N28: the qualified template never expands today. */
    @Test
    void aQualifiedTemplateIsMatchedAgainstThePrimaryAndNeverExpandsToday()
    {
        IDataTable p = withTreatments("P", "a", "a");
        IDataTable j = withTreatments("J", "x", "y");
        WildcardExpander.ExpansionResult result = expand("empty(J.TRTxxP)", p, j);
        assertInstanceOf(WildcardExpander.ExpansionResult.NoMatch.class, result,
                "N28: the dotted wildcard name is matched whole against P's column names — remove"
                        + " this pin and assert the two expansions agree when the Check expansion"
                        + " learns the qualifier");
        // What uniformity would require, stated as the reference the fix must meet: the bare
        // template over a primary carrying J's columns.
        IDataTable pLikeJ = withTreatments("P", "x", "y");
        assertEquals(List.of(List.of(2L), List.of(1L)),
                firedPerExpansion(expand("empty(TRTxxP)", pLikeJ, j), pLikeJ, j));
    }
}
