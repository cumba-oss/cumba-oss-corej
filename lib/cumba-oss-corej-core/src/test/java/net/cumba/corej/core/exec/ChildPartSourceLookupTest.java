package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * B5 review ① L2 and B3 review LOW-1 (PACK H fold): a {@code Child} part's source table is not a
 * keyed partner, so the view builder never held it — {@code MergedViewLookup.sourceColumnIsBlank}
 * answered "not blank" for every parent column and {@code declaredEncoding} answered none. The
 * lookup now holds the table the merge itself read for the part, captured when the view is built
 * (ENGINE FOLD MED-1: a final field, never a lazy memo — the cached view is shared by the rules
 * running in parallel), so a parent's all-blank column is exempt where a keyed partner's is
 * (D72a-1), and a byte count over the parent's columns counts in the PARENT's declared
 * {@code Encoding} — never the primary's (the coordinator's ruling by precedent D72 / BL1). A
 * type-clashing split parent never reaches a lookup: the view build refuses it (ENGINE FOLD LOW-2).
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
    void aTypeClashingSplitParentIsRefusedByTheViewBuild()
    {
        // the parent domain is split and its members clash on LBSTRESN: the merge's own resolution
        // of the parent throws (RuleRunnerSplitJoinTest.typeClashAcrossMembers_reportsRuleError_
        // childPath), so no view — and no lookup — exists to swallow it (ENGINE FOLD LOW-2)
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .lng("LBSTRESN", 1L).str("LBSEQ", "1").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSTRESN", "high").str("LBSEQ", "9").build();
        DatasetResolver.WithInventory inventory = RealTables.inventoryOf(supplbch(), lbch, lbhe);
        InvalidJoinedDomainException refused = assertThrows(InvalidJoinedDomainException.class,
                () -> MergedEvaluation.build(childRule(), supplbch(), supplbch(), inventory, null,
                        "TEST-CHILD-SOURCE", null, null, null));
        assertTrue(refused.getMessage().contains("LBSTRESN"), refused.getMessage());
    }


    /**
     * ENGINE FOLD MED-1, behaviour: the part's source is captured while the view is built — a
     * resolver that answers nothing once the build returned leaves every answer intact. The lazy
     * memo this replaces resolved on first use, so it read the closed resolver here (and, across
     * the rules sharing a cached view, raced on two unsynchronised fields).
     */
    @Test
    void theSourceIsCapturedWhenTheViewIsBuiltNotOnFirstUse()
    {
        IDataTable lbch = MockTable.of().name("lbch").col("DOMAIN", "LB").col("USUBJID", "U1")
                .col("LBSEQ", "1").col("LBORRES", "").col("LBSPEC", "SERUM")
                .metaValue("Encoding", "windows-1252").build();
        IDataTable primary = supplbch();
        DatasetResolver.WithInventory inventory = RealTables.inventoryOf(primary, lbch);
        AtomicBoolean open = new AtomicBoolean(true);
        DatasetResolver.WithInventory closable = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String aName)
            {
                return open.get() ? inventory.resolve(aName) : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return open.get() ? inventory.availableDatasets() : Set.of();
            }
        };
        MergedEvaluation.Built built = MergedEvaluation.build(childRule(), primary, primary,
                closable, null, "TEST-CHILD-SOURCE", null, null, null);
        assertNotNull(built, "the Child entry merges");
        open.set(false);
        JoinLookup lookup = built.lookups().values().iterator().next();
        assertTrue(lookup.sourceColumnIsBlank("LBORRES"),
                "the parent's all-blank column, from the table the merge read");
        assertEquals("windows-1252", lookup.declaredEncoding(), "the PARENT's charset");
    }


    /**
     * ENGINE FOLD MED-1, structure: a lookup is shared by every rule the cached view serves, run in
     * parallel, so it carries no unsynchronised mutable state — every instance field is final (the
     * column memo is a {@code ConcurrentHashMap}).
     */
    @Test
    void theLookupCarriesNoMutableField()
    {
        List<String> mutable = new ArrayList<>();
        for (Field field : MergedViewLookup.class.getDeclaredFields())
        {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                    && !Modifier.isFinal(field.getModifiers()))
            {
                mutable.add(field.getName());
            }
        }
        assertEquals(List.of(), mutable, "a non-final field races across the parallel rules");
        assertTrue(MergedViewLookup.class.getDeclaredFields().length >= 8,
                "the roster reads the lookup's own fields");
    }
}
