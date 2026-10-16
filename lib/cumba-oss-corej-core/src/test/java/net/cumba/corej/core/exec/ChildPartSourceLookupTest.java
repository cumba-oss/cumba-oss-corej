package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * B5 review ① L2 and B3 review LOW-1 (PACK H fold): a {@code Child} part's source table is not a
 * keyed partner, so the view builder never held it — {@code MergedViewLookup.sourceColumnIsBlank}
 * answered "not blank" for every parent column and {@code declaredEncoding} answered none. The
 * lookup now resolves the part's source lazily through the run's dataset resolver (a type-clashing
 * split parent is caught: the lookup stays sourceless, the ERROR is the merge's own), so a parent's
 * all-blank column is exempt where a keyed partner's is (D72a-1), and a byte count over the
 * parent's columns counts in the PARENT's declared {@code Encoding} — never the primary's (the
 * coordinator's ruling by precedent D72 / BL1).
 */
class ChildPartSourceLookupTest
{

    private static Rule childRule()
    {
        MatchDataset child = new MatchDataset();
        child.setName("SUPP--");
        child.setChild(true);
        child.setKeys(List.of("USUBJID", "IDVAR", "IDVARVAL"));
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-CHILD-SOURCE");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(child));
        String text = "not empty(IDVARVAL)";
        rule.setCheck(new CheckConditionAll(
                List.of(new CheckConditionExpression(CheckExpressionParser.parse(text), text))));
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static IDataTable supplbch()
    {
        return RealTables.of("SUPPLBCH").str("RDOMAIN", "LB").str("USUBJID", "U1")
                .str("IDVAR", "LBSEQ").str("IDVARVAL", "1").str("QNAM", "LBX").str("QVAL", "a")
                .build();
    }


    private static JoinLookup theChildLookup(IDataTable primary, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = primary;
        System.arraycopy(others, 0, all, 1, others.length);
        MergedEvaluation.Built built = MergedEvaluation.build(childRule(), primary, primary,
                RealTables.inventoryOf(all), null, "TEST-CHILD-SOURCE", null, null, null);
        assertNotNull(built, "the Child entry merges");
        assertTrue(built.childDeclared());
        assertEquals(1, built.lookups().size(), "one part per parent domain: " + built.lookups());
        return built.lookups().values().iterator().next();
    }


    @Test
    void aChildPartReadsItsParentsBlankColumnsAndEncoding()
    {
        IDataTable lbch = MockTable.of().name("lbch").col("DOMAIN", "LB").col("USUBJID", "U1")
                .col("LBSEQ", "1").col("LBORRES", "").col("LBSPEC", "SERUM")
                .metaValue("Encoding", "windows-1252").build();
        JoinLookup lookup = theChildLookup(supplbch(), lbch);
        assertTrue(lookup.sourceColumnIsBlank("LBORRES"), "the parent's all-blank column");
        assertFalse(lookup.sourceColumnIsBlank("LBSPEC"));
        assertFalse(lookup.sourceColumnIsBlank("NOSUCH"), "an absent column is not blank");
        assertEquals("windows-1252", lookup.declaredEncoding(), "the PARENT's charset");
    }


    @Test
    void aParentWithoutAnEncodingDeclaresNone()
    {
        IDataTable lbch = MockTable.of().name("lbch").col("DOMAIN", "LB").col("USUBJID", "U1")
                .col("LBSEQ", "1").col("LBORRES", "x").build();
        assertNull(theChildLookup(supplbch(), lbch).declaredEncoding(),
                "none declared: the byte count is missing, the primary's charset is never borrowed");
    }


    @Test
    void aTypeClashingSplitParentLeavesTheLookupSourceless()
    {
        // the parent domain is split and its members clash on LBSTRESN: resolving it throws the
        // merge's own refusal (RuleRunnerSplitJoinTest.typeClashAcrossMembers_reportsRuleError_
        // childPath); the lookup must not re-raise it — it simply has no source
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .lng("LBSTRESN", 1L).str("LBSEQ", "1").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSTRESN", "high").str("LBSEQ", "9").build();
        MergedEvaluation.Built built;
        try
        {
            built = MergedEvaluation.build(childRule(), supplbch(), supplbch(),
                    RealTables.inventoryOf(supplbch(), lbch, lbhe), null, "TEST-CHILD-SOURCE", null,
                    null, null);
        }
        catch (RuntimeException refused)
        {
            return; // the view build itself refuses the clash: nothing to look up
        }
        assertNotNull(built);
        for (JoinLookup lookup : built.lookups().values())
        {
            assertFalse(lookup.sourceColumnIsBlank("LBSTRESN"));
            assertNull(lookup.declaredEncoding());
        }
    }
}
