package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Ensures forward-RELREC row expansion (dataset-level + one-to-many) works under the NATIVE
 * expression engine, not only the legacy interpreter. The expansion and the expanded-row-aware
 * {@code RELREC} {@link JoinLookup} are installed in the shared {@code RuleRunner.execute} prologue
 * (before the backend split), and the native engine resolves dot-qualified {@code RELREC.<var>}
 * references via {@code ctx.getJoinedDatasets()} (ExprCompiler.dottedVector) — the same lookup the
 * legacy path uses. This test drives the same RELREC rule through both backends and asserts they
 * produce identical violations, and that the native backend is genuinely exercised
 * ({@code checkExpr != null}).
 */
class RelrecNativeEvalTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static IDataTable tbl(String name, String[] cols, String[][] rows)
    {
        MockTable mt = MockTable.of();
        for (int c = 0; c < cols.length; c++)
        {
            String[] colVals = new String[rows.length];
            for (int r = 0; r < rows.length; r++)
            {
                colVals[r] = rows[r][c];
            }
            mt.col(cols[c], colVals);
        }
        return mt.name(name).build();
    }


    /** AE primary, one AE record (AELNKID=1) -> 3 FA findings (FALNKGRP=1); AELNKID=2 -> none. */
    private static IDataTable aePrimary()
    {
        return tbl("AE", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "AESEQ", "AELNKID", "AETERM"
        }, new String[][]
        {
                {
                        "S1", "AE", "P1", "1", "L1", "REACTION"
                },
                {
                        "S1", "AE", "P1", "2", "L2", "FATIGUE"
                }
        });
    }


    private static DatasetResolver resolver()
    {
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "L1", "ERYTHEMA"
                },
                {
                        "S1", "FA", "P1", "2", "L1", "ERYTHEMA"
                },
                {
                        "S1", "FA", "P1", "3", "L1", "PAIN"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELTYPE", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "ONE", "AEFA"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "MANY", "AEFA"
                }
        });
        Map<String, IDataTable> m = new HashMap<>();
        m.put("FA", fa);
        m.put("RELREC", relrec);
        return m::get;
    }


    private static Rule relrecRule(CheckConditionAll check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-RELREC-NATIVE");
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("relrec native test");
        outcome.setOutputVariables(List.of("AETERM", "RELREC.FAOBJ"));
        rule.setOutcome(outcome);
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(check);
        MatchDataset md = new MatchDataset();
        md.setName("RELREC");
        md.setWildcard("FA");
        rule.setMatchDatasets(List.of(md));
        return rule;
    }


    /** A leaf whose value is a column reference (not a literal). */
    private static CheckConditionLeaf ref(String name, String operator, String reference)
    {
        return CheckConditionLeaf.builder().name(name).operator(operator)
                .value(MAPPER.valueToTree(reference)).build();
    }


    /** Normalises a result to a comparable list of (realRow, output-values). */
    private static List<String> norm(RuleExecutionResult r)
    {
        List<String> out = new ArrayList<>();
        for (Violation v : r.getViolations())
        {
            out.add(v.getRow() + " " + v.getValues());
        }
        return out;
    }


    @Test
    void forwardRelrecOneToManyMatchesAcrossBackends()
    {
        CheckConditionAll check = new CheckConditionAll(
                List.of(ref("AETERM", "not_equal_to", "RELREC.FAOBJ")));
        Rule rule = relrecRule(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        assertNotNull(rule.getCheckExpr(),
                "RELREC dotted-ref Check must lower to a native Expr so the native backend runs");

        IDataTable ae = aePrimary();
        RuleExecutionResult legacy = RuleRunner.execute(rule, ae, resolver(), "AE", null, null);
        RuleExecutionResult nativ = RuleRunner.execute(rule, ae, resolver(), "AE", null, null);

        // One-to-many: AE record 0 expands to 3 FA pairs (AETERM != FAOBJ for all three); AE
        // record 1 (AELNKID=2) has no FA -> excluded by the inner join.
        assertEquals(3, legacy.getViolations().size(), "legacy: one violation per related FA");
        assertEquals(3, nativ.getViolations().size(), "native: one violation per related FA");
        for (Violation v : nativ.getViolations())
        {
            assertEquals(0L, v.getRow(), "every expanded violation maps back to AE record 0");
        }
        assertEquals(norm(legacy), norm(nativ),
                "native backend must produce identical RELREC violations to legacy");
    }


    @Test
    void nativeBackendReadsExpandedRelrecValues()
    {
        // A value-dependent native rule: flag the related FA whose FAOBJ == 'PAIN'. If the native
        // backend did NOT resolve the expanded RELREC.FAOBJ (or silently fell back), it could not
        // single out the PAIN pair. Proves the native engine reads the expanded related values.
        // Leaf with a literal RHS: compare the expanded RELREC.FAOBJ (name side, dot-qualified)
        // to the literal "PAIN".
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("RELREC.FAOBJ")
                .operator("equal_to").value(MAPPER.valueToTree("PAIN")).valueIsLiteral(true)
                .build();
        CheckConditionAll check = new CheckConditionAll(List.of(leaf));
        Rule rule = relrecRule(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        assertNotNull(rule.getCheckExpr());

        IDataTable ae = aePrimary();
        RuleExecutionResult nativ = RuleRunner.execute(rule, ae, resolver(), "AE", null, null);

        // Exactly the PAIN pair fires (1 expanded row), on AE record 0, with RELREC.FAOBJ=PAIN.
        assertEquals(1, nativ.getViolations().size());
        Violation v = nativ.getViolations().get(0);
        assertEquals(0L, v.getRow());
        assertTrue(v.getValues().getOrDefault("RELREC.FAOBJ", "").equals("PAIN"),
                "native resolved the expanded RELREC.FAOBJ value: " + v.getValues());
    }


    @Test
    void dottedStarStarResolvesNatively()
    {
        // P5b (CORE-000744 mechanics): a dot-qualified ** reference (RELREC.**OBJ) resolves the
        // ** prefix per expanded row against the BOUND TARGET's domain (FA → FAOBJ) inside
        // RelrecExpandedLookup — previously a native decline (legacy-only), now routed through the
        // same dotted joined-lookup plan. Native must match legacy bit-for-bit.
        CheckConditionAll check = new CheckConditionAll(
                List.of(ref("AETERM", "not_equal_to", "RELREC.**OBJ")));
        Rule rule = relrecRule(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        assertNotNull(rule.getCheckExpr(),
                "RELREC.** Check must compile natively after the P5b carve-out");

        IDataTable ae = aePrimary();
        RuleExecutionResult legacy = RuleRunner.execute(rule, ae, resolver(), "AE", null, null);
        RuleExecutionResult nativ = RuleRunner.execute(rule, ae, resolver(), "AE", null, null);
        assertEquals(3, legacy.getViolations().size(),
                "legacy: AETERM differs from every related FAOBJ");
        assertEquals(norm(legacy), norm(nativ), "native RELREC.** verdicts must equal legacy");

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, ae, resolver(), "AE", null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("CORE-RELREC-NATIVE"),
                "the RELREC.** rule must run on the NATIVE backend");
    }
}
