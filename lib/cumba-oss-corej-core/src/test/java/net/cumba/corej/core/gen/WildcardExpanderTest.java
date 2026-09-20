package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.gen.WildcardExpander.WildcardPattern;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

class WildcardExpanderTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    /** The expansion's Check rendered back to expression text, for concrete-name assertions. */
    private static String rendered(net.cumba.corej.core.model.CheckCondition condition)
    {
        return net.cumba.corej.core.expr.ExpressionPrinter
                .print(net.cumba.corej.core.expr.CheckToExpr.toExpr(condition));
    }

    // ---- isWildcard ----


    @Test
    void isWildcard_starPrefix()
    {
        assertTrue(WildcardExpander.isWildcard("*FL"));
        assertTrue(WildcardExpander.isWildcard("*DT"));
        assertTrue(WildcardExpander.isWildcard("*GRyN"));
    }


    @Test
    void isWildcard_indexedMarkers()
    {
        assertTrue(WildcardExpander.isWildcard("TRTxxP"));
        assertTrue(WildcardExpander.isWildcard("ANLzzFL"));
        assertTrue(WildcardExpander.isWildcard("CRITy"));
        assertTrue(WildcardExpander.isWildcard("STRATwR"));
    }


    @Test
    void isWildcard_concreteNames()
    {
        assertFalse(WildcardExpander.isWildcard("SAFFL"));
        assertFalse(WildcardExpander.isWildcard("TRT01P"));
        assertFalse(WildcardExpander.isWildcard("STUDYID"));
        assertFalse(WildcardExpander.isWildcard("USUBJID"));
    }


    @Test
    void isWildcard_engineMetadataNames()
    {
        assertFalse(WildcardExpander.isWildcard("variable_name"));
        assertFalse(WildcardExpander.isWildcard("variable_label"));
        assertFalse(WildcardExpander.isWildcard("variable_data_type"));
        assertFalse(WildcardExpander.isWildcard("variable_format"));
        assertFalse(WildcardExpander.isWildcard("$ae_aeout"));
    }


    @Test
    void isWildcard_nullAndEmpty()
    {
        assertFalse(WildcardExpander.isWildcard(null));
        assertFalse(WildcardExpander.isWildcard(""));
    }

    // ---- scopeVariableWildcardPattern ----


    @Test
    void scopeVariableWildcardPattern_markerEntriesCompile()
    {
        var xx = WildcardExpander.scopeVariableWildcardPattern("TRTxxP");
        assertNotNull(xx);
        assertTrue(xx.matcher("TRT01P").matches());
        assertTrue(xx.matcher("TRT99P").matches());
        assertFalse(xx.matcher("TRT1P").matches()); // xx = exactly two digits
        assertFalse(xx.matcher("TRTxxP").matches()); // the marker text itself never matches

        var y = WildcardExpander.scopeVariableWildcardPattern("CRITy");
        assertNotNull(y);
        assertTrue(y.matcher("CRIT1").matches());
        assertTrue(y.matcher("CRIT12").matches());

        var w = WildcardExpander.scopeVariableWildcardPattern("STRATwR");
        assertNotNull(w);
        assertTrue(w.matcher("STRAT1R").matches());
        assertFalse(w.matcher("STRAT12R").matches()); // w = single digit

        assertNotNull(WildcardExpander.scopeVariableWildcardPattern("ANLzzFL"));
    }


    @Test
    void scopeVariableWildcardPattern_digitBearingStemKeepsItsDigitsLiteral()
    {
        // Only the lowercase run is a marker — digits in the stem stay literal. R2AyLO parses as
        // "R2A" + y + "LO", so a differently-numbered stem is a different variable entirely.
        var y = WildcardExpander.scopeVariableWildcardPattern("R2AyLO");
        assertNotNull(y);
        assertTrue(y.matcher("R2A1LO").matches());
        assertTrue(y.matcher("R2A12LO").matches());
        assertFalse(y.matcher("R2ALO").matches()); // y = at least one digit
        assertFalse(y.matcher("R1A1LO").matches()); // the stem digit is literal
        assertFalse(y.matcher("R2AyLO").matches()); // the marker text itself never matches

        // The fixed-arity markers keep their arity next to literal stem digits too.
        var xx = WildcardExpander.scopeVariableWildcardPattern("R2AxxLO");
        assertNotNull(xx);
        assertTrue(xx.matcher("R2A01LO").matches());
        assertFalse(xx.matcher("R2A1LO").matches());

        // An unknown lowercase run stays literal even with digits in the stem.
        assertNull(WildcardExpander.scopeVariableWildcardPattern("R2AyyLO"));
    }


    @Test
    void scopeVariableWildcardPattern_literalEntriesReturnNull()
    {
        assertNull(WildcardExpander.scopeVariableWildcardPattern("TRT01P"));
        assertNull(WildcardExpander.scopeVariableWildcardPattern("STUDYID"));
        // Unknown lowercase runs are literals, not markers.
        assertNull(WildcardExpander.scopeVariableWildcardPattern("TRTyyP"));
        assertNull(WildcardExpander.scopeVariableWildcardPattern("Char"));
        // Engine metadata / operation names are never wildcards.
        assertNull(WildcardExpander.scopeVariableWildcardPattern("variable_name"));
        assertNull(WildcardExpander.scopeVariableWildcardPattern("$ae_aeout"));
    }

    // ---- WildcardPattern.parse ----


    @Test
    void pattern_starFL()
    {
        WildcardPattern pat = WildcardPattern.parse("*FL");
        assertTrue(pat.regex().matcher("SAFFL").matches());
        assertTrue(pat.regex().matcher("ITTFL").matches());
        assertTrue(pat.regex().matcher("COMPLFL").matches());
        assertFalse(pat.regex().matcher("FL").matches()); // * requires at least one char
        assertFalse(pat.regex().matcher("SAFF").matches());
        assertEquals(List.of("*"), pat.groupNames());
    }


    @Test
    void pattern_TRTxxP()
    {
        WildcardPattern pat = WildcardPattern.parse("TRTxxP");
        assertTrue(pat.regex().matcher("TRT01P").matches());
        assertTrue(pat.regex().matcher("TRT99P").matches());
        assertFalse(pat.regex().matcher("TRT1P").matches()); // xx requires 2 digits
        assertFalse(pat.regex().matcher("TRT001P").matches());
        assertEquals(List.of("xx"), pat.groupNames());
    }


    @Test
    void pattern_ANLzzFL()
    {
        WildcardPattern pat = WildcardPattern.parse("ANLzzFL");
        assertTrue(pat.regex().matcher("ANL01FL").matches());
        assertTrue(pat.regex().matcher("ANL12FL").matches());
        assertFalse(pat.regex().matcher("ANL1FL").matches());
        assertEquals(List.of("zz"), pat.groupNames());
    }


    @Test
    void pattern_CRITy()
    {
        WildcardPattern pat = WildcardPattern.parse("CRITy");
        assertTrue(pat.regex().matcher("CRIT1").matches());
        assertTrue(pat.regex().matcher("CRIT12").matches());
        assertTrue(pat.regex().matcher("CRIT123").matches());
        assertFalse(pat.regex().matcher("CRIT").matches());
        assertEquals(List.of("y"), pat.groupNames());
    }


    @Test
    void pattern_STRATwR()
    {
        WildcardPattern pat = WildcardPattern.parse("STRATwR");
        assertTrue(pat.regex().matcher("STRAT1R").matches());
        assertTrue(pat.regex().matcher("STRAT9R").matches());
        assertFalse(pat.regex().matcher("STRAT12R").matches()); // w = single digit
        assertEquals(List.of("w"), pat.groupNames());
    }


    @Test
    void pattern_starGRyN_twoGroups()
    {
        WildcardPattern pat = WildcardPattern.parse("*GRyN");
        assertTrue(pat.regex().matcher("SEVGR1N").matches());
        assertTrue(pat.regex().matcher("DEVGR2N").matches());
        assertFalse(pat.regex().matcher("REGION2N").matches()); // no "GR" as separate literal
        assertFalse(pat.regex().matcher("GR1N").matches()); // * needs at least 1 char
        assertEquals(List.of("*", "y"), pat.groupNames());
    }


    @Test
    void pattern_TRxxPGy_twoGroups()
    {
        WildcardPattern pat = WildcardPattern.parse("TRxxPGy");
        assertTrue(pat.regex().matcher("TR01PG1").matches());
        assertTrue(pat.regex().matcher("TR02PG12").matches());
        assertFalse(pat.regex().matcher("TR1PG1").matches());
        assertEquals(List.of("xx", "y"), pat.groupNames());
    }


    @Test
    void pattern_PxxSwSDT_twoGroups()
    {
        WildcardPattern pat = WildcardPattern.parse("PxxSwSDT");
        assertTrue(pat.regex().matcher("P01S1SDT").matches());
        assertTrue(pat.regex().matcher("P99S9SDT").matches());
        assertFalse(pat.regex().matcher("P01S12SDT").matches()); // w = single digit
        assertEquals(List.of("xx", "w"), pat.groupNames());
    }

    // ---- buildConcreteName ----


    @Test
    void buildConcreteName_starFL()
    {
        WildcardPattern pat = WildcardPattern.parse("*FL");
        assertEquals("SAFFL", pat.buildConcreteName("SAF"));
    }


    @Test
    void buildConcreteName_TRTxxP()
    {
        WildcardPattern pat = WildcardPattern.parse("TRTxxP");
        assertEquals("TRT01P", pat.buildConcreteName("01"));
        assertEquals("TRT99P", pat.buildConcreteName("99"));
    }


    @Test
    void buildConcreteName_starGRyN()
    {
        WildcardPattern pat = WildcardPattern.parse("*GRyN");
        assertEquals("SEVGR1N", pat.buildConcreteName("SEV\u00001"));
    }

    // ---- collectWildcardNames ----


    @Test
    void collectWildcardNames_singleLeaf()
    {
        Set<String> names = WildcardExpander.collectWildcardNames(expr("not empty(*FL)"));
        assertEquals(Set.of("*FL"), names);
    }


    @Test
    void collectWildcardNames_multipleInAll()
    {
        CheckConditionAll check = new CheckConditionAll(
                List.of(expr("*FL == \"Y\""), expr("*FN != 1")));
        Set<String> names = WildcardExpander.collectWildcardNames(check);
        assertEquals(Set.of("*FL", "*FN"), names);
    }


    @Test
    void collectWildcardNames_valueReference()
    {
        CheckConditionAll check = new CheckConditionAll(
                List.of(expr("not empty(*SDT)"), expr("not empty(*EDT)"), expr("*SDT > *EDT")));
        Set<String> names = WildcardExpander.collectWildcardNames(check);
        assertEquals(Set.of("*SDT", "*EDT"), names);
    }


    @Test
    void collectWildcardNames_skipsLiteral()
    {
        // A string literal on the value side must not be collected as a wildcard.
        Set<String> names = WildcardExpander.collectWildcardNames(expr("*FL == \"Y\""));
        assertEquals(Set.of("*FL"), names);
    }


    @Test
    void collectWildcardNames_skipsMetadataNames()
    {
        CheckConditionAll check = new CheckConditionAll(
                List.of(expr("var_exists(\"*DT\")"), expr("var_type(\"DATA\") != \"Num\"")));
        Set<String> names = WildcardExpander.collectWildcardNames(check);
        assertEquals(Set.of("*DT"), names);
    }

    // ---- Full expansion ----


    @Test
    void expand_singleWildcard_starFL()
    {
        Rule template = buildTemplateRule("CDISC-AD0005",
                new CheckConditionAll(
                        List.of(expr("not empty(*FL)"), expr("*FL not in [\"Y\", \"N\"]"))),
                List.of("*FL"));

        IDataTable table = MockTable.withColumns("STUDYID", "USUBJID", "SAFFL", "ITTFL", "AGE");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(2, expanded.size());
        assertEquals("CDISC-AD0005-SAFFL", expanded.get(0).getCore().getId());
        assertEquals("CDISC-AD0005-ITTFL", expanded.get(1).getCore().getId());
    }


    @Test
    void expand_singleWildcard_indexedTRTxxP()
    {
        Rule template = buildTemplateRule("CDISC-AD0075",
                new CheckConditionAll(
                        List.of(expr("var_exists(TRTxxPN)"), expr("var_not_exists(TRTxxP)"))),
                List.of("TRTxxPN"));

        IDataTable table = MockTable.withColumns("TRT01P", "TRT01PN", "TRT02P", "TRT02PN",
                "STUDYID");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        // Both xx=01 and xx=02 have TRTxxPN matching
        assertEquals(2, expanded.size());
        // Check that the expanded Check has concrete names
        CheckConditionAll check0 = (CheckConditionAll) expanded.get(0).getCheck();
        assertEquals("var_exists(TRT01PN)", rendered(check0.getConditions().get(0)));
    }


    @Test
    void expand_multiWildcard_starSDT_starEDT()
    {
        Rule template = buildTemplateRule("CDISC-AD0121", new CheckConditionAll(
                List.of(expr("not empty(*SDT)"), expr("not empty(*EDT)"), expr("*SDT > *EDT"))),
                List.of("*SDT", "*EDT"));

        // TRTSDT/TRTEDT share root "TRT", ASTSDT has no matching ASTEDT
        IDataTable table = MockTable.withColumns("TRTSDT", "TRTEDT", "ASTSDT", "STUDYID");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        // Only TRT* pair should match (both TRTSDT and TRTEDT present)
        // ASTSDT has no matching ASTEDT so it should still produce a rule
        // (the generator creates rules for all tuples, not_exists handles missing)
        // Actually: *SDT matches TRTSDT (root=TRT) and ASTSDT (root=AST)
        // *EDT matches TRTEDT (root=TRT) — no ASTEDT
        // Tuple "TRT" has both *SDT=TRTSDT and *EDT=TRTEDT → produces rule
        // Tuple "AST" has only *SDT=ASTSDT, but *EDT is computed from tuple as ASTEDT → produces
        // rule
        assertEquals(2, expanded.size());

        // The TRT tuple should have concrete names TRTSDT/TRTEDT
        Rule trtRule = expanded.stream().filter(r -> r.getCore().getId().contains("TRTSDT"))
                .findFirst().orElseThrow();
        CheckConditionAll trtCheck = (CheckConditionAll) trtRule.getCheck();
        assertEquals("TRTSDT > TRTEDT", rendered(trtCheck.getConditions().get(2)));
    }


    @Test
    void expand_noMatches()
    {
        Rule template = buildTemplateRule("CDISC-AD0046", expr("*DY == 0"), List.of("*DY"));

        IDataTable table = MockTable.withColumns("STUDYID", "USUBJID", "AGE");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        assertTrue(expanded.isEmpty());
    }


    @Test
    void expand_doubleIndex_TRxxPGy()
    {
        Rule template = buildTemplateRule("CDISC-AD0419",
                new CheckConditionAll(List.of(expr("not empty(TRxxPGyN)"), expr("empty(TRxxPGy)"))),
                List.of("TRxxPGyN", "TRxxPGy"));

        IDataTable table = MockTable.withColumns("TR01PG1", "TR01PG1N", "TR01PG2", "TR01PG2N",
                "TR02PG1", "TR02PG1N");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        // 3 tuples: (01,1), (01,2), (02,1)
        assertEquals(3, expanded.size());
    }


    @Test
    void containsWildcards_templateWithWildcards()
    {
        Rule template = buildTemplateRule("CDISC-AD0005", expr("not empty(*FL)"), List.of("*FL"));
        assertTrue(WildcardExpander.containsWildcards(template));
    }


    @Test
    void containsWildcards_noWildcards()
    {
        Rule rule = buildTemplateRule("CDISC-AD0001", expr("ds_not_exists(\"ADSL\")"), null);
        assertFalse(WildcardExpander.containsWildcards(rule));
    }


    @Test
    void containsWildcards_metadataOnly()
    {
        Rule rule = buildTemplateRule("CDISC-AD0013", expr("len(varname()) > 8"), null);
        assertFalse(WildcardExpander.containsWildcards(rule));
    }

    // ---- Variable Metadata Check exists→variable_name transformation ----


    @Test
    void expand_variableMetadataCheck_wildcardSubstitutedInName()
    {
        // Template: *SDT exists AND variable_label does_not_contain "Start Date"
        // WildcardExpander performs pure name substitution — no hidden transforms.
        Rule template = buildTemplateRule("CDISC-AD0509", Sensitivity.DATASET,
                new CheckConditionAll(List.of(expr("var_exists(\"*SDT\")"),
                        expr("not contains(var_label(\"DATA\"), \"Start Date\")"))),
                List.of("*SDT"));

        IDataTable table = MockTable.withColumns("STUDYID", "TRTSDT", "ASTSDT", "AGE");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        assertEquals(2, expanded.size()); // TRT and AST roots

        // After expansion, *SDT is substituted to TRTSDT — operator stays "var_exists"
        Rule trtRule = expanded.stream().filter(r -> r.getCore().getId().contains("TRTSDT"))
                .findFirst().orElseThrow();
        CheckConditionAll trtCheck = (CheckConditionAll) trtRule.getCheck();
        assertEquals("var_exists(\"TRTSDT\")", rendered(trtCheck.getConditions().get(0)));

        // The second conjunct (variable_label) should be unchanged
        assertEquals("not contains(var_label(\"DATA\"), \"Start Date\")",
                rendered(trtCheck.getConditions().get(1)));
    }


    @Test
    void expand_existsPairSubstituted()
    {
        // Both *FN and *FL are pure name substitutions
        Rule template = buildTemplateRule("CDISC-AD0007", Sensitivity.DATASET,
                new CheckConditionAll(
                        List.of(expr("var_exists(\"*FN\")"), expr("var_not_exists(\"*FL\")"))),
                List.of("*FN"));

        IDataTable table = MockTable.withColumns("SAFFN", "SAFFL", "ITTFN");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        assertEquals(2, expanded.size());

        // exists/not_exists operators are preserved — no hidden transformation
        Rule safRule = expanded.stream().filter(r -> r.getCore().getId().contains("SAFFN"))
                .findFirst().orElseThrow();
        CheckConditionAll safCheck = (CheckConditionAll) safRule.getCheck();
        assertEquals("var_exists(\"SAFFN\")", rendered(safCheck.getConditions().get(0)));
        assertEquals("var_not_exists(\"SAFFL\")", rendered(safCheck.getConditions().get(1)));
    }

    // ---- Fix #23 — mixed-group expansion ----


    @Test
    void expand_mixedGroups_TRxxPGy_and_TRTxxP()
    {
        // CDISC-AD0756 shape: TRxxPGy (groups [xx, y]) +
        // TRTxxP (groups [xx]). Pre-fix the expander rejected this rule
        // outright because group lists differed. Now: TRxxPGy seeds the
        // candidate tuples (it covers the union); TRTxxP's column per tuple
        // is matched-or-computed.
        Rule template = buildTemplateRule("CDISC-AD0756",
                new CheckConditionAll(
                        List.of(expr("not empty(TRxxPGy)"), expr("not empty(TRTxxP)"))),
                List.of("TRxxPGy", "TRTxxP"));

        IDataTable table = MockTable.withColumns("TR01PG1", "TR01PG2", "TR02PG1", "TRT01P",
                "TRT02P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        // Three (xx, y) tuples come from TRxxPGy: (01,1), (01,2), (02,1).
        // For each, TRTxxP's matching column is found via its xx instance
        // (TRT01P or TRT02P — both anchored).
        assertEquals(3, expanded.size());
        Set<String> expandedIds = expanded.stream().map(r -> r.getCore().getId())
                .collect(java.util.stream.Collectors.toSet());
        // The Core ID suffix is the first concrete column of the first wildcard
        // (insertion order: TRxxPGy first → TR01PG1, TR01PG2, TR02PG1).
        assertTrue(expandedIds.contains("CDISC-AD0756-TR01PG1"), expandedIds.toString());
        assertTrue(expandedIds.contains("CDISC-AD0756-TR01PG2"), expandedIds.toString());
        assertTrue(expandedIds.contains("CDISC-AD0756-TR02PG1"), expandedIds.toString());
    }


    @Test
    void expand_mixedGroups_partialPattern_noMatchingColumn_isComputed()
    {
        // CDISC-AD0368 shape: TRxxPGy (groups [xx, y]) +
        // TRTxxA (groups [xx]) + TRxxAGy (groups [xx, y]) — the rule fires
        // when TRxxAGy is *missing*. Dataset has TRxxPGy/TRTxxA columns but
        // no TRxxAGy. The expander must still produce one rule per (xx, y)
        // tuple, with TRxxAGy resolved via buildConcreteName so the
        // not_exists Check has a concrete column name to evaluate against.
        Rule template = buildTemplateRule("CDISC-AD0368", Sensitivity.DATASET,
                new CheckConditionAll(List.of(expr("var_exists(\"TRxxPGy\")"),
                        expr("var_exists(\"TRTxxA\")"), expr("var_not_exists(\"TRxxAGy\")"))),
                List.of("TRxxPGy", "TRTxxA", "TRxxAGy"));

        IDataTable table = MockTable.withColumns("TR01PG1", "TR02PG1", "TRT01A", "TRT02A");
        // Note: no TR01AG1 / TR02AG1 columns — those are the ones the rule
        // flags as missing.

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        // Two (xx, y) tuples: (01,1) and (02,1). Each gets TRxxAGy filled in
        // via buildConcreteName (TR01AG1, TR02AG1).
        assertEquals(2, expanded.size());

        // Check that the not_exists leaf in one of the expansions points at
        // the computed column name.
        Rule first = expanded.stream()
                .filter(r -> r.getCore().getId().equals("CDISC-AD0368-TR01PG1")).findFirst()
                .orElseThrow();
        CheckConditionAll firstCheck = (CheckConditionAll) first.getCheck();
        assertEquals("var_not_exists(\"TR01AG1\")", rendered(firstCheck.getConditions().get(2)),
                "TRxxAGy in expansion (01,1) should resolve to TR01AG1");
    }


    @Test
    void expand_mixedGroups_partialPattern_orphanedXxIsDropped()
    {
        // When the wider pattern (TRxxPGy, [xx, y]) doesn't have a tuple for
        // some xx that the narrower pattern (TRTxxP, [xx]) does, that xx is
        // orphaned: we have no y value for it, so no full tuple. The
        // expansion correctly drops the orphan rather than fabricating an
        // arbitrary y or producing an under-specified rule.
        Rule template = buildTemplateRule("CDISC-AD0756",
                new CheckConditionAll(
                        List.of(expr("not empty(TRxxPGy)"), expr("not empty(TRTxxP)"))),
                List.of("TRxxPGy", "TRTxxP"));

        // TRxxPGy only matches xx=01; TRTxxP matches xx=01 AND xx=02. xx=02
        // has no y to enumerate, so no expansion for it.
        IDataTable table = MockTable.withColumns("TR01PG1", "TR01PG2", "TRT01P", "TRT02P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        assertEquals(2, expanded.size());
        Set<String> expandedIds = expanded.stream().map(r -> r.getCore().getId())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(expandedIds.contains("CDISC-AD0756-TR01PG1"));
        assertTrue(expandedIds.contains("CDISC-AD0756-TR01PG2"));
        // No TRT02P-anchored expansion — there's no y for xx=02.
        assertFalse(expandedIds.stream().anyMatch(id -> id.contains("TRT02P")));
    }

    // ---- Helpers ----


    private static Rule buildTemplateRule(String coreId,
            net.cumba.corej.core.model.CheckCondition check, List<String> outputVars)
    {
        return buildTemplateRule(coreId, Sensitivity.RECORD, check, outputVars);
    }


    private static Rule buildTemplateRule(String coreId, Sensitivity sensitivity,
            net.cumba.corej.core.model.CheckCondition check, List<String> outputVars)
    {
        Rule rule = new Rule();
        rule.setId(java.util.UUID.randomUUID().toString());
        RuleCore core = new RuleCore();
        core.setId(coreId);
        core.setStatus("Published");
        core.setVersion("1");
        rule.setCore(core);
        rule.setDescription("Test rule " + coreId);
        rule.setSensitivity(sensitivity);
        rule.setCheck(check);
        Outcome outcome = new Outcome();
        outcome.setMessage("Violation: " + coreId);
        if (outputVars != null)
        {
            outcome.setOutputVariables(outputVars);
        }
        rule.setOutcome(outcome);
        return rule;
    }


    /**
     * {@code Fix #356} — the {@code Output_Variables} rename is a whole-name map lookup, so an
     * {@code !X} exclusion token ({@code OutputVariableToken}) must be renamed by the name INSIDE
     * it. A raw {@code "!TRxxSDT"} is not a key of the map and would survive the expansion with its
     * wildcard unresolved, while the Check resolved — the E-3.1 load error / ENGINE_ERROR shape.
     */
    @Test
    void expand_renamesTheNameInsideAnExclusionToken()
    {
        Rule template = buildTemplateRule("EXCL-OV",
                new CheckConditionAll(List.of(expr("not empty(TRTxxP)"), expr("empty(TRxxSDT)"))),
                List.of("TRTxxP", "!TRxxSDT"));

        IDataTable table = MockTable.withColumns("TRT01P", "TR01SDT");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(1, expanded.size());
        Rule concrete = expanded.get(0);
        assertEquals("EXCL-OV-TRT01P", concrete.getCore().getId());
        // Both arms: the retained include AND the excluded name are resolved, marker preserved.
        assertEquals(List.of("TRT01P", "!TR01SDT"), concrete.getOutcome().getOutputVariables());
    }

    // ---- Fix #24: numeric range filter via wildcards --------------------------


    @Test
    void expand_wildcardFilterMin_dropsXx01()
    {
        // CDISC-AD0078 pattern: TRTxxP exists AND TRxxSDT not_exists, with
        // wildcards: {xx: {min: 2}} so xx=01 is dropped and only xx=02+
        // expansions are emitted.
        Rule template = buildTemplateRule("CDISC-AD0078", new CheckConditionAll(
                List.of(expr("var_exists(\"TRTxxP\")"), expr("var_not_exists(\"TRxxSDT\")"))),
                List.of("TRTxxP", "TRxxSDT"));
        net.cumba.corej.core.model.WildcardFilter filter = new net.cumba.corej.core.model.WildcardFilter();
        filter.setMin(2);
        template.setWildcards(java.util.Map.of("xx", filter));

        IDataTable table = MockTable.withColumns("TRT01P", "TRT02P", "TRT03P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(2, expanded.size(),
                "wildcards.xx.min=2 drops xx=01; xx=02 and xx=03 expansions remain");
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Rule r : expanded)
            ids.add(r.getCore().getId());
        assertTrue(ids.contains("CDISC-AD0078-TRT02P"));
        assertTrue(ids.contains("CDISC-AD0078-TRT03P"));
        assertFalse(ids.contains("CDISC-AD0078-TRT01P"));
    }


    @Test
    void expand_wildcardFilterMax_dropsAboveBound()
    {
        Rule template = buildTemplateRule("FILTER-MAX",
                new CheckConditionAll(List.of(expr("var_exists(\"TRTxxP\")"))), List.of("TRTxxP"));
        net.cumba.corej.core.model.WildcardFilter filter = new net.cumba.corej.core.model.WildcardFilter();
        filter.setMax(5);
        template.setWildcards(java.util.Map.of("xx", filter));

        IDataTable table = MockTable.withColumns("TRT01P", "TRT05P", "TRT06P", "TRT10P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(2, expanded.size(),
                "max=5 inclusive: xx=01 and xx=05 remain; xx=06 and xx=10 dropped");
    }


    @Test
    void expand_wildcardFilterMinAndMax_inclusiveBoth()
    {
        Rule template = buildTemplateRule("FILTER-RANGE",
                new CheckConditionAll(List.of(expr("var_exists(\"TRTxxP\")"))), List.of("TRTxxP"));
        net.cumba.corej.core.model.WildcardFilter filter = new net.cumba.corej.core.model.WildcardFilter();
        filter.setMin(2);
        filter.setMax(4);
        template.setWildcards(java.util.Map.of("xx", filter));

        IDataTable table = MockTable.withColumns("TRT01P", "TRT02P", "TRT03P", "TRT04P", "TRT05P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(3, expanded.size(), "range [2,4] inclusive keeps xx=02, xx=03, xx=04 only");
    }


    @Test
    void expand_wildcardFilterAcrossLeaves_appliesToAllPatterns()
    {
        // Both leaves share the same xx group; the filter applies once for the
        // shared tuple regardless of which leaf carries the wildcard.
        Rule template = buildTemplateRule("MULTI-LEAF", new CheckConditionAll(
                List.of(expr("var_exists(\"TRTxxP\")"), expr("var_not_exists(\"TRxxSDT\")"))),
                List.of("TRTxxP", "TRxxSDT"));
        net.cumba.corej.core.model.WildcardFilter filter = new net.cumba.corej.core.model.WildcardFilter();
        filter.setMin(2);
        template.setWildcards(java.util.Map.of("xx", filter));

        IDataTable table = MockTable.withColumns("TRT01P", "TRT02P", "TR02SDT", "TRT03P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(2, expanded.size(), "xx=01 dropped; xx=02 and xx=03 both produce expansions");
    }


    @Test
    void expand_noWildcardFilter_existingBehaviorUnchanged()
    {
        // Sanity check: rules without wildcards expand exactly as before.
        Rule template = buildTemplateRule("NO-FILTER",
                new CheckConditionAll(List.of(expr("var_exists(\"TRTxxP\")"))), List.of("TRTxxP"));

        IDataTable table = MockTable.withColumns("TRT01P", "TRT02P", "TRT03P");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(3, expanded.size(),
                "no filter \u2192 every captured xx produces an expansion");
    }

    // ---- Fix #84 (Group B / B4): empty-suffix pairing, name-exclude, pair catalogue ------------


    @Test
    void expand_bareStarAnchoredByStarN_seedsOnlyFromAnchoredColumns()
    {
        // PMDA-AD0376 shape: bare "*" primary co-anchored by "*N" secondary. Tuples must be
        // seeded from the ANCHORED "*N" columns only — never one-tuple-per-column (explosion).
        Rule template = buildTemplateRule("PMDA-AD0376",
                new CheckConditionAll(List.of(expr("not empty(*)"), expr("empty(*N)"))),
                List.of("*"));

        // 8 columns, but only TRTPN and PARAMN end in "N" (anchored). A bare-* seed would
        // produce ~8 expansions; anchored seeding produces exactly 2.
        IDataTable table = MockTable.withColumns("STUDYID", "USUBJID", "TRTP", "TRTPN", "PARAM",
                "PARAMN", "AGE", "AVAL");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(2, expanded.size(), "seed from *N columns only (TRTPN, PARAMN)");
        Set<String> ids = expanded.stream().map(r -> r.getCore().getId())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("PMDA-AD0376-TRTP"), ids.toString());
        assertTrue(ids.contains("PMDA-AD0376-PARAM"), ids.toString());

        // The TRTP expansion pairs bare-* -> TRTP with *N -> TRTPN.
        Rule trtRule = expanded.stream().filter(r -> r.getCore().getId().equals("PMDA-AD0376-TRTP"))
                .findFirst().orElseThrow();
        CheckConditionAll trtCheck = (CheckConditionAll) trtRule.getCheck();
        assertEquals("not empty(TRTP)", rendered(trtCheck.getConditions().get(0)));
        assertEquals("empty(TRTPN)", rendered(trtCheck.getConditions().get(1)));
    }


    @Test
    void expand_bareStarWithoutAnchor_isGuarded()
    {
        // A bare "*" leaf with NO "*N"/"*C" sibling must be refused (would seed one tuple per
        // column). expand returns empty rather than exploding.
        Rule template = buildTemplateRule("GUARD-TEST",
                new CheckConditionAll(List.of(expr("not empty(*)"))), List.of("*"));

        IDataTable table = MockTable.withColumns("STUDYID", "TRTP", "PARAM", "AGE");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        assertTrue(expanded.isEmpty(), "bare-* without an anchor must be guarded (no explosion)");
    }


    @Test
    void expand_nameExcludeDirective_dropsExcludedSecondaries()
    {
        // PMDA-AD0376/AD1011 shape: wildcardExclude drops the anchored secondary columns that
        // match a literal (TRTPN) or wildcard (*FN) exclusion, so their pairs never seed.
        Rule template = buildTemplateRule("PMDA-AD1011",
                new CheckConditionAll(List.of(expr("not empty(*N)"), expr("empty(*)"))),
                List.of("*"));
        template.setWildcardExclude(List.of("TRTPN", "*FN"));

        IDataTable table = MockTable.withColumns("TRTP", "TRTPN", "PARAM", "PARAMN", "ANL01F",
                "ANL01FN");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        // TRTPN excluded (literal), ANL01FN excluded (*FN). Only PARAMN survives -> 1 expansion.
        assertEquals(1, expanded.size(), "TRTPN and ANL01FN excluded; only PARAM pair remains");
        // Core-ID suffix is the first collected wildcard's concrete column; here the check lists
        // "*N" before "*", so the surviving expansion is suffixed with the secondary (PARAMN).
        assertEquals("PMDA-AD1011-PARAMN", expanded.get(0).getCore().getId());
        // The pairing resolved bare-* -> PARAM (the primary) and *N -> PARAMN.
        CheckConditionAll surviving = (CheckConditionAll) expanded.get(0).getCheck();
        assertEquals("not empty(PARAMN)", rendered(surviving.getConditions().get(0)));
        assertEquals("empty(PARAM)", rendered(surviving.getConditions().get(1)));
    }


    @Test
    void expand_pairCatalogue_emitsOnlyCataloguedPairs()
    {
        // PMDA-AD1012A shape: wildcardPairCatalogue=true restricts anchored *N/*C seeds to the
        // curated CDISC-standard catalogue. TRTPN (*N) and AVALC (*C) are catalogued; FOON/BARC
        // are not.
        Rule template = buildTemplateRule("PMDA-AD1012A", new CheckConditionAny(List.of(
                new CheckConditionAll(
                        List.of(expr("var_not_exists(\"*\")"), expr("var_exists(\"*N\")"))),
                new CheckConditionAll(
                        List.of(expr("var_not_exists(\"*\")"), expr("var_exists(\"*C\")"))))),
                List.of("*"));
        template.setWildcardPairCatalogue(true);

        IDataTable table = MockTable.withColumns("TRTPN", "AVALC", "FOON", "BARC");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());

        assertEquals(2, expanded.size(), "only catalogued TRTPN and AVALC seed; FOON/BARC dropped");
        Set<String> ids = expanded.stream().map(r -> r.getCore().getId())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("PMDA-AD1012A-TRTP"), ids.toString());
        assertTrue(ids.contains("PMDA-AD1012A-AVAL"), ids.toString());
        assertFalse(ids.stream().anyMatch(id -> id.contains("FOO") || id.contains("BAR")),
                ids.toString());
    }


    @Test
    void expand_valuePositionLiteralStar_doesNotTriggerBareStarGuard()
    {
        // Regression for Fix #84, on the native form the corpus actually ships: a "*" that
        // appears only in a VALUE-position string literal (the
        // `not contains(var_label("LIBRARY"), "*")` shape of CDISC-AD0018 / 0708 / 0709 and
        // PMDA-AD0018) is NOT a bare-* target. It must neither trigger the empty-suffix guard
        // nor mark the rule as a wildcard template at all.
        Rule template = buildTemplateRule("REGRESS-AD0018",
                new CheckConditionAll(List.of(expr("varname() !~ /xx/"),
                        expr("not contains(var_label(\"LIBRARY\"), \"*\")"))),
                List.of("library_variable_label"));

        assertFalse(WildcardExpander.containsWildcards(template),
                "a value-position literal '*' is data, not a template marker");

        IDataTable table = MockTable.withColumns("STUDYID", "AGE", "SEX");
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(template,
                table.getMetaData());
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, result,
                "no template markers → NotApplicable, never the bare-* guard's empty result");
    }


    @Test
    void expand_pairCatalogue_withoutFlag_wouldSeedAll()
    {
        // Control for the catalogue test: with the flag OFF, the same template seeds every *N/*C
        // column (proving the catalogue restriction — not some other filter — drops FOON/BARC).
        Rule template = buildTemplateRule("NOCAT", new CheckConditionAny(List.of(
                new CheckConditionAll(
                        List.of(expr("var_not_exists(\"*\")"), expr("var_exists(\"*N\")"))),
                new CheckConditionAll(
                        List.of(expr("var_not_exists(\"*\")"), expr("var_exists(\"*C\")"))))),
                List.of("*"));

        IDataTable table = MockTable.withColumns("TRTPN", "AVALC", "FOON", "BARC");

        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        assertEquals(4, expanded.size(), "no catalogue -> all four *N/*C columns seed");
    }


    @Test
    void collectAvailableCaptureGroups_starAndIndex()
    {
        CheckConditionAll all = new CheckConditionAll(
                List.of(expr("var_exists(\"TRTxxP\")"), expr("not empty(*GRy)")));
        java.util.Set<String> groups = WildcardExpander.collectAvailableCaptureGroups(all);
        assertTrue(groups.contains("xx"));
        assertTrue(groups.contains("*"));
        assertTrue(groups.contains("y"));
    }


    /**
     * Fix #147 — {@code substituteInText} applies substitutions <b>longest key first</b>.
     *
     * <p>
     * ⚠ The obvious wildcard case ({@code TRTxxP} ⊂ {@code TRTxxPN}) does <b>not</b> pin this: both
     * bindings come from the same tuple, so replacing the shorter key first happens to yield the
     * longer key's intended result anyway ({@code TRTxxPN} → {@code TRT01PN} either way). Measured
     * — a test written that way stays green with the ordering removed. The property is only
     * observable when the two replacements disagree inside the shared prefix, which the declared
     * {@code Expansion:} tokens can do because their values are unrelated. It is a defensive
     * contract of this helper: {@code RulePackageLoader} rejects a rule whose tokens overlap, so
     * the mechanism never relies on it — but the helper is shared, and an order-dependent helper is
     * a trap for the next caller.
     * </p>
     */
    @Test
    void substituteInTextAppliesTheLongestKeyFirst()
    {
        java.util.Map<String, String> substitutions = new java.util.LinkedHashMap<>();
        // Deliberately insertion-ordered shortest-first: the method must not honour this order.
        substitutions.put("&D", "AE");
        substitutions.put("&DS", "SUPPAE");

        assertEquals("SUPPAE", WildcardExpander.substituteInText("&DS", substitutions));
    }

}
