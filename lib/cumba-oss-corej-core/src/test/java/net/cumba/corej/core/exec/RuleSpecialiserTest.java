package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins the bind-time specialisation stage (D77): the one prefix policy (D77c), the native
 * expression coverage (the {@code CheckConditionExpression} arm the old expander never touched),
 * the D92a / D92b / D77d exceptions that must survive specialisation untouched, and the engine's
 * D77b assertion that an unresolved {@code --} reaching the evaluator is an error, never a silent
 * substitution.
 */
class RuleSpecialiserTest
{

    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("AESTDTC", "2020-01-01").col("AELNKGRP", "G1").build();
    }


    private static Rule exprRule(String coreId, String expression)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(
                new CheckConditionExpression(CheckExpressionParser.parse(expression), expression));
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    /** Populates the package-private Jackson any-setter target reflectively. */
    private static void addUnknownKey(Rule rule, String key)
    {
        try
        {
            java.lang.reflect.Field f = Rule.class.getDeclaredField("unknownKeys");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Collection<String> keys = (java.util.Collection<String>) f.get(rule);
            keys.add(key);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static String printed(Rule rule)
    {
        return ExpressionPrinter.print(Objects.requireNonNull(rule.getCheckExpr()));
    }

    // ---- the native-expression arm (the 167 instances the old expander never saw) ----


    @Test
    void aNativeExpressionCheckIsSpecialisedAtBindTime()
    {
        Rule rule = exprRule("TEST-NATIVE", "--STDTC == \"X\"");

        Rule specialised = RuleSpecialiser.specialise(rule, ae(), "AE");

        assertNotSame(rule, specialised);
        assertEquals("AESTDTC == \"X\"", printed(Objects.requireNonNull(specialised)));
        assertEquals("--STDTC == \"X\"", printed(rule),
                "the SOURCE rule must stay untouched — it is shared across datasets");
    }


    @Test
    void aQuotedNamePositionLiteralIsSpecialisedToo()
    {
        // The corpus spells var_exists with a quoted name 309 times; the exists family treats a
        // string literal as equivalent-by-definition to a reference, so the specialiser must
        // resolve it the same way.
        Rule rule = exprRule("TEST-QUOTED", "var_exists(\"--STDTC\")");

        Rule specialised = Objects.requireNonNull(RuleSpecialiser.specialise(rule, ae(), "AE"));

        assertEquals("var_exists(\"AESTDTC\")", printed(specialised));
    }


    @Test
    void theDollarSubstitutionAndRelrecFormsStayPerRow()
    {
        // D77d: both derive a column name from a CELL VALUE — no metadata resolves them early.
        Rule substitution = exprRule("TEST-SUBST",
                "--STDTC == \"X\" and non_empty(AP${APERIOD:%02d}SDT)");
        Rule relrec = exprRule("TEST-RELREC", "RELREC.**DECOD == \"Y\"");

        Rule substitutionSpecialised = Objects
                .requireNonNull(RuleSpecialiser.specialise(substitution, ae(), "AE"));
        assertTrue(printed(substitutionSpecialised).contains("AP${APERIOD:%02d}SDT"),
                "a ${...} operand is per-row column selection and must survive verbatim: "
                        + printed(substitutionSpecialised));
        assertTrue(printed(substitutionSpecialised).contains("AESTDTC"),
                "…while the -- beside it still resolves");
        Rule relrecSpecialised = Objects
                .requireNonNull(RuleSpecialiser.specialise(relrec, ae(), "AE"));
        assertTrue(printed(relrecSpecialised).contains("RELREC.**DECOD"),
                "the dotted ** column half is per-row (Fix #5) and must survive: "
                        + printed(relrecSpecialised));
    }


    @Test
    void aConcreteRuleComesBackAsTheVerySameInstance()
    {
        Rule rule = exprRule("TEST-CONCRETE", "AESTDTC == \"X\"");

        assertSame(rule, RuleSpecialiser.specialise(rule, ae(), "AE"));
    }


    @Test
    void withoutADomainCodeNothingIsResolved()
    {
        Rule rule = exprRule("TEST-NODOMAIN", "--STDTC == \"X\"");

        assertSame(rule, RuleSpecialiser.specialise(rule, ae(), null));
    }

    // ---- D92b: name_pattern= is a string-literal selector, never a wildcard ----


    @Test
    void aNamePatternArgumentIsNeverRewritten()
    {
        Rule rule = exprRule("TEST-PATTERN", "variable_count(name_pattern=\"^--[A-Z]+FL$\") == 0");

        Rule specialised = RuleSpecialiser.specialise(rule, ae(), "AE");

        // The whole rule is concrete apart from the selector, so nothing changes at all.
        assertSame(rule, specialised);
        assertTrue(printed(rule).contains("^--[A-Z]+FL$"));
    }

    // ---- D92a: the inventory folds keep their template ----


    private static DatasetResolver.WithInventory inventory(IDataTable ae, IDataTable cm)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                if ("AE".equals(name))
                {
                    return ae;
                }
                if ("CM".equals(name))
                {
                    return cm;
                }
                return null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return java.util.Set.of("AE", "CM");
            }
        };
    }


    /**
     * ⛔⛔ The D92a regression the plan demands: {@code variable_count(--LNKGRP)} folds across the
     * dataset INVENTORY using the pre-resolution template ({@code AE}&rarr;{@code AELNKGRP},
     * {@code CM}&rarr;{@code CMLNKGRP}). If the specialiser ever expands that name operand, the
     * count collapses to 1 (only the current domain's column resolves), the {@code < 2} check
     * fires, and CDISC-CG0022 / CG0024 report false findings on every conformant study — or, in
     * their real corpus shape, go inert. This test goes RED in exactly that case: both domains
     * carry the column, so a violation here means the template was lost.
     */
    @Test
    void variableCountKeepsItsTemplateAcrossTheInventory_operationsForm()
    {
        IDataTable ae = ae();
        IDataTable cm = MockTable.of().name("CM").col("CMLNKGRP", "G1").build();

        Operation op = new Operation();
        op.setId("$variable_count");
        op.setOperator("variable_count");
        op.setName("--LNKGRP");
        Rule rule = exprRule("TEST-D92A", "var_exists(\"--LNKGRP\") and $variable_count < 2");
        rule.setOperations(List.of(op));

        RuleExecutionResult result = RuleRunner.execute(rule, ae, inventory(ae, cm), "AE", null,
                null);

        assertFalse(result.isError(), result.getStatusMessage() + "");
        assertFalse(result.hasViolations(),
                "a violation here means the --LNKGRP template was specialised away and the fold "
                        + "counted only the current domain (D92a)");
        // And the rule still fires when the column genuinely exists nowhere else:
        IDataTable cmWithout = MockTable.of().name("CM").col("CMTRT", "X").build();
        RuleExecutionResult lone = RuleRunner.execute(rule, ae, inventory(ae, cmWithout), "AE",
                null, null);
        assertTrue(lone.hasViolations(), "non-vacuity: a lone --LNKGRP must still be flagged");
    }


    /** The same D92a contract for the INLINE spelling of the fold. */
    @Test
    void variableCountKeepsItsTemplateAcrossTheInventory_inlineForm()
    {
        IDataTable ae = ae();
        IDataTable cm = MockTable.of().name("CM").col("CMLNKGRP", "G1").build();

        Rule rule = exprRule("TEST-D92A-INLINE",
                "var_exists(\"--LNKGRP\") and variable_count(--LNKGRP) < 2");

        RuleExecutionResult result = RuleRunner.execute(rule, ae, inventory(ae, cm), "AE", null,
                null);

        assertFalse(result.isError(), result.getStatusMessage() + "");
        assertFalse(result.hasViolations(),
                "a violation here means the inline --LNKGRP operand was specialised away (D92a)");
    }

    // ---- Operations / Match_Datasets / Grouping coverage ----


    @Test
    void operationsAreSpecialisedWithTheOriginalNameStashed()
    {
        Operation op = new Operation();
        op.setId("$distinct");
        op.setOperator("distinct");
        op.setName("--STDTC");
        Rule rule = exprRule("TEST-OPS", "AESTDTC in $distinct");
        rule.setOperations(List.of(op));

        Rule specialised = Objects.requireNonNull(RuleSpecialiser.specialise(rule, ae(), "AE"));

        assertNotSame(rule, specialised);
        Operation resolved = Objects.requireNonNull(specialised.getOperations()).getFirst();
        assertEquals("AESTDTC", resolved.getName());
        assertEquals("--STDTC", resolved.getOriginalName(),
                "the pre-resolution template must ride along (Fix #1 / D92a)");
        assertEquals("--STDTC", Objects.requireNonNull(rule.getOperations()).getFirst().getName(),
                "the SOURCE operation must stay untouched — the list is shared across datasets");
    }


    @Test
    void aNonChildMatchDatasetNameIsResolvedAndAChildPatternIsNot()
    {
        MatchDataset join = new MatchDataset();
        join.setName("SUPP--");
        join.setKeys(List.of("USUBJID"));
        MatchDataset childPattern = new MatchDataset();
        childPattern.setName("SUPP--");
        childPattern.setChild(true);
        Rule rule = exprRule("TEST-MD", "--STDTC == \"X\"");
        rule.setMatchDatasets(List.of(join, childPattern));

        Rule specialised = Objects.requireNonNull(RuleSpecialiser.specialise(rule, ae(), "AE"));

        List<MatchDataset> resolved = Objects.requireNonNull(specialised.getMatchDatasets());
        assertEquals("SUPPAE", resolved.get(0).getName(),
                "a join reference is decidable from (rule × dataset) and resolves at bind time");
        assertEquals("SUPP--", resolved.get(1).getName(),
                "a Child entry's name is a PATTERN matched against the primary's own name — "
                        + "split-aware (SUPP-- must keep matching SUPPLBCH) — and must survive");
    }


    @Test
    void groupingVariablesAndOutputVariablesAreSpecialised()
    {
        Rule rule = exprRule("TEST-GRP", "--STDTC == \"X\"");
        rule.setGroupingVariables(List.of("USUBJID", "--SEQ"));
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of("--STDTC", "!--SEQ"));
        rule.setOutcome(outcome);

        Rule specialised = Objects.requireNonNull(RuleSpecialiser.specialise(rule, ae(), "AE"));

        assertEquals(List.of("USUBJID", "AESEQ"), specialised.getGroupingVariables());
        assertEquals(List.of("AESTDTC", "!AESEQ"),
                Objects.requireNonNull(specialised.getOutcome()).getOutputVariables());
        assertEquals(List.of("--STDTC", "!--SEQ"),
                Objects.requireNonNull(rule.getOutcome()).getOutputVariables(),
                "the SOURCE outcome must stay untouched");
    }


    @Test
    void aLoadErrorRuleIsNeverRewritten()
    {
        Rule rule = exprRule("TEST-LOADERR", "--STDTC == \"X\"");
        rule.setLoadError("bad enum");

        assertSame(rule, RuleSpecialiser.specialise(rule, ae(), "AE"));
    }

    // ---- D77b: the engine asserts instead of resolving ----


    /**
     * A {@code --} that survives to the evaluator (here: no domain code, so the specialisation
     * stage cannot resolve anything) is an ERROR, never a silent substitution and never a silent
     * empty verdict. This is the assertion that makes a specialisation coverage gap loud.
     */
    @Test
    void anUnresolvedDashReachingTheEvaluatorIsAnError()
    {
        Rule rule = exprRule("TEST-ASSERT", "--STDTC == \"X\"");
        IDataTable nameless = MockTable.of().col("AESTDTC", "2020-01-01").build();

        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> RuleRunner.execute(rule, nameless, name -> null, null, null, null));
        assertTrue(ex.getMessage().contains("--STDTC"), ex.getMessage());
        assertTrue(ex.getMessage().contains("RuleSpecialiser"), ex.getMessage());
    }


    @Test
    void theSpecialisedCopyCarriesUnknownKeysAndSeverity()
    {
        Rule rule = exprRule("TEST-COPY", "--STDTC == \"X\"");
        rule.setSeverity(net.cumba.datatable.report.Severity.WARNING);
        addUnknownKey(rule, "X-Custom");

        Rule specialised = Objects.requireNonNull(RuleSpecialiser.specialise(rule, ae(), "AE"));

        assertNotSame(rule, specialised);
        assertEquals(net.cumba.datatable.report.Severity.WARNING, specialised.getSeverity());
        assertTrue(specialised.getUnknownKeys().contains("X-Custom"),
                "the shallow copy must carry the final unknownKeys content too");
        assertNull(specialised.getLoadError());
    }

}
