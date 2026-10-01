package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.joined;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.primary;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static net.cumba.corej.core.exec.QualifiedNameFixture.run;
import static net.cumba.corej.core.exec.QualifiedNameFixture.table;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 1 — the {@code Requirements.Variables}
 * surface: a qualified entry {@code J.S} gates the rule exactly as the bare {@code S} does when
 * {@code J} is in the study inventory — present ⇒ run, absent ⇒ SKIP, in {@code All} and in
 * {@code None} alike. The entry resolves through the {@code DatasetResolver}
 * ({@code ScopeVariableSource.resolveMetas}), never the join, so no {@code Match_Datasets} is
 * declared here.
 *
 * <p>
 * Ruled difference pinned (plan §2.3, Q12 / N7): a qualified entry is ALSO met by a SUPP-QNAM pivot
 * ({@code ScopeVariableSource.existsViaSuppQnam}) that the value read of {@code J.S} does not
 * consult. Known red recorded (N2, owner-pending): the qualifier of an entry is matched exactly by
 * an exact-case resolver — a lower-case {@code j.S} is "not available" and SKIPs.
 * </p>
 */
class RequirementsUniformityTest
{

    private static Rule gated(String facet, String entry)
    {
        return loadClean(ruleJson("Record", "\"Requirements\":{\"Variables\":{\"" + facet + "\":[\""
                + entry + "\"]}}," + check("K != \"zz\"") + "," + outcome("K")), false);
    }


    private static RuleExecutionStatus status(Rule r, IDataTable p, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = p;
        System.arraycopy(others, 0, all, 1, others.length);
        return run(r, p, exactInventory(all)).getStatus();
    }


    @Test
    void aQualifiedEntryGatesLikeTheBareOne()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        assertEquals(RuleExecutionStatus.EXECUTED, status(gated("All", "S"), p, j));
        assertEquals(RuleExecutionStatus.EXECUTED, status(gated("All", "J.S"), p, j));
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("None", "S"), p, j));
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("None", "J.S"), p, j));
        IDataTable pAbsent = table("P", "S");
        IDataTable jAbsent = table("J", "S");
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("All", "S"), pAbsent, jAbsent));
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("All", "J.S"), pAbsent, jAbsent));
        assertEquals(RuleExecutionStatus.EXECUTED, status(gated("None", "S"), pAbsent, jAbsent));
        assertEquals(RuleExecutionStatus.EXECUTED, status(gated("None", "J.S"), pAbsent, jAbsent));
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("All", "J.S"), p),
                "J not in the inventory: a qualified All entry cannot be met");
    }


    /**
     * Ruled (Q12 / N7): the existence gate consults the SUPP-QNAM pivot; the value read does not.
     */
    @Test
    void aQualifiedEntryIsMetByTheSuppQnamPivotWhichTheValueReadDoesNotConsult()
    {
        IDataTable p = primary();
        IDataTable jAbsent = table("J", "S");
        IDataTable suppJ = RealTables.of("SUPPJ").str("RDOMAIN", "J").str("K", "k1")
                .str("IDVAR", "L").str("IDVARVAL", "1").str("QNAM", "S").str("QVAL", "q").build();
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("All", "J.S"), p, jAbsent),
                "without SUPPJ the entry is absent");
        assertEquals(RuleExecutionStatus.EXECUTED, status(gated("All", "J.S"), p, jAbsent, suppJ),
                "SUPPJ.QNAM == S meets the qualified entry (ScopeMatcher.describeIncludeEntry)");
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("None", "J.S"), p, jAbsent, suppJ),
                "… and excludes it from None (describeExcludeEntry)");
    }


    /** ⚠ Known red N2 (owner-pending, plan §2.4): the qualifier is matched exactly. */
    @Test
    void aLowerCaseQualifierIsNotAvailableToAnExactResolverToday()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        assertEquals(RuleExecutionStatus.SKIPPED, status(gated("All", "j.S"), p, j),
                "ScopeVariableSource.resolveMetas → resolver.resolve(\"j\") — exact in this"
                        + " inventory; the product's resolvers are not traced (findings S9)");
    }
}
