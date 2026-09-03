package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 (PLAN-extend-expression-engine) — glob ({@code *}/{@code ?}) and {@code /…/} regex
 * entries in {@code Scope.Domains} / {@code Scope.Variables}, plus {@code --} domain-prefix
 * resolution in {@code Scope.Variables}. Pattern entries are anchored, case-insensitive full
 * matches against the raw name; literal entries keep their pre-existing semantics (normalized
 * prefix match for domains, exact lookup for variables, {@code ALL}/{@code NONE} sentinels,
 * {@code --} two-char wildcard, SUPP/AP family broadening).
 */
class ScopeMatcherPatternTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Rule domainInclude(String... entries)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(Arrays.asList(entries));
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule domainExclude(String... entries)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setExclude(Arrays.asList(entries));
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule domainIncludeExclude(List<String> include, List<String> exclude)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(include);
        ds.setExclude(exclude);
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule variableInclude(String... entries)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(Arrays.asList(entries));
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static Rule variableExclude(String... entries)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setNone(Arrays.asList(entries));
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta meta(String... columns)
    {
        return MockTable.withColumns(columns).getMetaData();
    }

    // ------------------------------------------------------------------
    // scopePattern detection
    // ------------------------------------------------------------------


    @Test
    void scopePattern_literalsReturnNull()
    {
        assertNull(ScopeMatcher.scopePattern("AE"));
        assertNull(ScopeMatcher.scopePattern("SUPP--"));
        assertNull(ScopeMatcher.scopePattern("ALL"));
        // Too short for the /…/ form: stays literal.
        assertNull(ScopeMatcher.scopePattern("/"));
        assertNull(ScopeMatcher.scopePattern("//"));
    }


    @Test
    void scopePattern_globAndRegexCompile()
    {
        assertNotNull(ScopeMatcher.scopePattern("*DY"));
        assertNotNull(ScopeMatcher.scopePattern("AESTD?"));
        assertNotNull(ScopeMatcher.scopePattern("/^LB(HE|CH)?$/"));
    }


    @Test
    void scopePattern_invalidRegexThrows()
    {
        org.junit.jupiter.api.Assertions.assertThrows(PatternSyntaxException.class,
                () -> ScopeMatcher.scopePattern("/[/"));
    }


    @Test
    void scopePattern_globQuotesLiteralRuns()
    {
        // The dot in the literal run must not act as a regex metacharacter.
        java.util.regex.Pattern glob = java.util.Objects
                .requireNonNull(ScopeMatcher.scopePattern("A.E*"));
        assertFalse(glob.matcher("AXE").matches());
        assertTrue(glob.matcher("A.EX").matches());
    }

    // ------------------------------------------------------------------
    // Domains — glob entries
    // ------------------------------------------------------------------


    @Test
    void domainGlobInclude_anchoredFullMatch()
    {
        Rule rule = domainInclude("LB*");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LB"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LBHE"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LB1"));
        // Anchored: must match the whole name, not a substring.
        assertEquals("domain SUPPLB not in Scope.Domains.Include [LB*]",
                ScopeMatcher.describeDomainMismatch(rule, "SUPPLB"));
    }


    @Test
    void domainGlobQuestionMark_exactlyOneChar()
    {
        Rule rule = domainInclude("L?");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LB"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "LBHE"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "L"));
    }


    @Test
    void domainStarAlone_matchesEverything_butAllStaysSentinel()
    {
        // '*' is a pattern that happens to match every name …
        Rule star = domainInclude("*");
        assertNull(ScopeMatcher.describeDomainMismatch(star, "DM"));
        assertNull(ScopeMatcher.describeDomainMismatch(star, "SUPPAE"));
        // … while ALL remains the literal sentinel (and a dataset literally named "ALL"
        // distinguishes the two paths: '*' matches it as a pattern, ALL as the sentinel).
        Rule all = domainInclude("ALL");
        assertNull(ScopeMatcher.describeDomainMismatch(all, "DM"));
        Rule starExclude = domainExclude("*");
        assertEquals("domain DM matches Scope.Domains.Exclude entry *",
                ScopeMatcher.describeDomainMismatch(starExclude, "DM"));
        Rule allExclude = domainExclude("ALL");
        assertEquals("domain DM matches Scope.Domains.Exclude entry ALL",
                ScopeMatcher.describeDomainMismatch(allExclude, "DM"));
    }


    @Test
    void domainGlob_caseInsensitive()
    {
        assertNull(ScopeMatcher.describeDomainMismatch(domainInclude("lb*"), "LBHE"));
        assertNull(ScopeMatcher.describeDomainMismatch(domainInclude("LB*"), "lbhe"));
    }

    // ------------------------------------------------------------------
    // Domains — regex entries
    // ------------------------------------------------------------------


    @Test
    void domainRegexInclude_matchesAlternation()
    {
        Rule rule = domainInclude("/^LB(HE|CH)?$/");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LB"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LBHE"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LBCH"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "lbch"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "LBXX"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPLB"));
    }


    @Test
    void domainRegex_splitDatasetMatchesViaBaseName()
    {
        // LB1 is a recognised split; /^LB$/ does not match "LB1" directly but matches the
        // unsplit base "LB".
        Rule include = domainInclude("/^LB$/");
        assertNull(ScopeMatcher.describeDomainMismatch(include, "LB1"));
        Rule exclude = domainExclude("/^LB$/");
        assertEquals("domain LB1 matches Scope.Domains.Exclude entry /^LB$/",
                ScopeMatcher.describeDomainMismatch(exclude, "LB1"));
    }


    @Test
    void domainExcludePatternHit_namesThePatternEntry()
    {
        Rule rule = domainExclude("/^LB(HE|CH)?$/");
        assertEquals("domain LBHE matches Scope.Domains.Exclude entry /^LB(HE|CH)?$/",
                ScopeMatcher.describeDomainMismatch(rule, "LBHE"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "AE"));
    }


    @Test
    void domainExcludeWins_overIncludePattern()
    {
        Rule rule = domainIncludeExclude(List.of("*"), List.of("AE*"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "DM"));
        assertEquals("domain AEPRE matches Scope.Domains.Exclude entry AE*",
                ScopeMatcher.describeDomainMismatch(rule, "AEPRE"));
    }


    @Test
    void domainLiteralSemantics_exactNextToPatterns()
    {
        // Mixed list: the literal is an exact (normalized) match while the pattern entry stays
        // anchored. ADAEDV is NOT an ADAE dataset — only a glob/regex entry can reach it.
        Rule rule = domainInclude("ADAE", "/^DM$/");
        assertEquals("domain ADAEDV not in Scope.Domains.Include [ADAE, /^DM$/]",
                ScopeMatcher.describeDomainMismatch(rule, "ADAEDV")); // literal is exact
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "ADAE")); // literal hit
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "DM")); // regex
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "DM1X"));
        // Opt back in to family breadth explicitly:
        assertNull(ScopeMatcher.describeDomainMismatch(domainInclude("ADAE*"), "ADAEDV"));
    }


    @Test
    void domainBooleanApi_agreesWithDescriberOnPatterns()
    {
        List<Rule> rules = List.of(domainInclude("LB*"), domainInclude("/^LB(HE|CH)?$/"),
                domainExclude("*"), domainExclude("/^LB$/"), domainInclude("*"));
        List<String> names = Arrays.asList("LB", "LBHE", "LB1", "DM", "SUPPLB", null);
        for (Rule rule : rules)
        {
            for (String name : names)
            {
                assertEquals(ScopeMatcher.describeDomainMismatch(rule, name) == null,
                        ScopeMatcher.matchesDomain(rule, name), "parity for " + name);
            }
        }
    }

    // ------------------------------------------------------------------
    // Variables — pattern entries
    // ------------------------------------------------------------------


    @Test
    void variablesGlobInclude_atLeastOneColumnMatches()
    {
        Rule rule = variableInclude("*DY");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESTDY", "USUBJID")));
        assertEquals("no variable matching Requirements.Variables.All entry *DY present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "AESTDTC")));
    }


    @Test
    void variablesLiteralStaysExact_whileGlobMatches()
    {
        // Literal 'DY' is an exact lookup and does not match AESTDY; '*DY' does.
        assertNotNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("DY"), meta("AESTDY")));
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("*DY"), meta("AESTDY")));
    }


    @Test
    void variablesGlobQuestionMark_exactlyOneChar()
    {
        Rule rule = variableInclude("AESTD?");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESTDY")));
        assertNotNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESTD")));
        assertNotNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESTDTC")));
    }


    @Test
    void variablesGlob_caseInsensitive()
    {
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("*dy"), meta("AESTDY")));
    }


    @Test
    void variablesRegexInclude()
    {
        Rule rule = variableInclude("/^AE(ST|EN)DY$/");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AEENDY")));
        assertNotNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESTDTC")));
    }


    @Test
    void variablesExcludePattern_rejectsWhenAnyColumnMatches()
    {
        Rule rule = variableExclude("*ORRES");
        assertEquals("variable QSORRES matches Requirements.Variables.None entry *ORRES",
                ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "QSORRES")));
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "QSSTRESC")));
    }


    @Test
    void variablesMixedLiteralAndPattern_include()
    {
        Rule rule = variableInclude("USUBJID", "*DY");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "AESTDY")));
        // literal miss reported with the literal message
        assertEquals("Requirements.Variables.All variable USUBJID not present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("AESTDY")));
        // pattern miss reported with the pattern message
        assertEquals("no variable matching Requirements.Variables.All entry *DY present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID")));
    }

    // ------------------------------------------------------------------
    // Variables — wildcard-marker entries (xx / zz / y / w)
    // ------------------------------------------------------------------


    @Test
    void variablesWildcardMarkerInclude_atLeastOneColumnMatches()
    {
        // TRTxxP applies as soon as ANY concrete TRTnnP exists — not only TRT01P.
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("TRTxxP"),
                meta("STUDYID", "TRT02P")));
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("TRTPGy", "TRTPGyN"),
                meta("TRTPG1", "TRTPG1N")));
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("ANLzzFL"),
                meta("ANL01FL")));
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("STRATwR"),
                meta("STRAT1R")));
    }


    @Test
    void variablesWildcardMarkerInclude_noMatchNamesEntry()
    {
        assertEquals(
                "no variable matching Requirements.Variables.All entry TRTxxP present in dataset",
                ScopeMatcher.describeVariablesMismatch(variableInclude("TRTxxP"),
                        meta("STUDYID", "USUBJID")));
    }


    @Test
    void variablesWildcardMarkerInclude_markerShapeEnforced()
    {
        // xx = exactly two digits (zero-padded): TRT1P / TRT001P don't satisfy TRTxxP.
        assertNotNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("TRTxxP"), meta("TRT1P")));
        assertNotNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("TRTxxP"), meta("TRT001P")));
        // w = exactly one digit.
        assertNotNull(ScopeMatcher.describeVariablesMismatch(variableInclude("STRATwR"),
                meta("STRAT12R")));
        // y = any number of digits.
        assertNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("CRITy"), meta("CRIT12")));
    }


    @Test
    void variablesWildcardMarkerInclude_digitBearingStem()
    {
        // Stem digits are literal and only the lowercase run is the marker: R2AyLO is
        // "R2A" + y + "LO", so R2A1LO satisfies the entry.
        assertNull(ScopeMatcher.describeVariablesMismatch(variableInclude("R2AyLO"),
                meta("USUBJID", "R2A1LO")));
        assertNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("R2AyLO"), meta("R2A12LO")));
        // A different stem digit is a different variable — the marker covers only the 'y'.
        assertEquals(
                "no variable matching Requirements.Variables.All entry R2AyLO present in dataset",
                ScopeMatcher.describeVariablesMismatch(variableInclude("R2AyLO"), meta("R1A1LO")));
        // y still needs at least one digit.
        assertNotNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("R2AyLO"), meta("R2ALO")));
        // 'yy' is not a marker: the entry stays a literal name despite the stem digits.
        assertEquals("Requirements.Variables.All variable R2AyyLO not present in dataset",
                ScopeMatcher.describeVariablesMismatch(variableInclude("R2AyyLO"), meta("R2A1LO")));
    }


    @Test
    void variablesWildcardMarkerExclude_digitBearingStem()
    {
        Rule rule = variableExclude("R2AyLO");
        assertEquals("variable R2A1LO matches Requirements.Variables.None entry R2AyLO",
                ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "R2A1LO")));
        // The literal stem digit is not part of the marker, so R1A1LO does not trip the Exclude.
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "R1A1LO")));
    }


    @Test
    void variablesWildcardMarkerExclude_rejectsWhenAnyColumnMatches()
    {
        Rule rule = variableExclude("TRTxxP");
        assertEquals("variable TRT02P matches Requirements.Variables.None entry TRTxxP",
                ScopeMatcher.describeVariablesMismatch(rule, meta("STUDYID", "TRT02P")));
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("STUDYID", "USUBJID")));
    }


    @Test
    void variablesUnknownLowercaseRunStaysLiteral()
    {
        // 'yy' is not a marker: the entry is a literal name — it does not match TRT01P but
        // still matches a column literally named TRTyyP (pre-existing exact-lookup semantics).
        assertEquals("Requirements.Variables.All variable TRTyyP not present in dataset",
                ScopeMatcher.describeVariablesMismatch(variableInclude("TRTyyP"), meta("TRT01P")));
        assertNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("TRTyyP"), meta("TRTyyP")));
        // Mixed-case value-like literals ('Char') never turn into patterns either.
        assertNotNull(
                ScopeMatcher.describeVariablesMismatch(variableInclude("Char"), meta("CHAR1")));
    }


    @Test
    void variablesDashDash_composesWithWildcardMarker()
    {
        // Prefix substitution first (--GRy -> AEGRy), then the marker branch matches AEGR1.
        Rule rule = variableInclude("--GRy");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AEGR1"), "AE"));
        assertEquals(
                "no variable matching Requirements.Variables.All entry --GRy (resolved AEGRy)"
                        + " present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("VSGR1"), "AE"));
    }

    // ------------------------------------------------------------------
    // Variables — `--` domain-prefix resolution
    // ------------------------------------------------------------------


    @Test
    void variablesDashDash_resolvedWithTwoCharPrefix()
    {
        Rule rule = variableInclude("--SEQ");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESEQ"), "AE"));
        assertEquals(
                "Requirements.Variables.All variable --SEQ (resolved AESEQ) not present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("VSSEQ"), "AE"));
    }


    @Test
    void variablesDashDash_withoutPrefix_keepsRawAndMisses()
    {
        Rule rule = variableInclude("--SEQ");
        // Only a NULL prefix leaves the entry raw. EC-36 made null mean "unresolvable", and
        // RuleRunner skips such a rule up front with its own reason — so reaching this gate with
        // a raw `--` entry is now the degraded/synthetic-context case only.
        assertEquals("Requirements.Variables.All variable --SEQ not present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("AESEQ"), null));
    }


    @Test
    void variablesDashDash_emptyPrefixResolvesForSuppDatasets()
    {
        // EC-36: the caller passes the VARIABLE wildcard prefix, and "" is a legitimate value —
        // a SUPP/SQ dataset resolves --QNAM to QNAM. The pre-EC-36 `length() == 2` gate treated ""
        // as "no prefix" and left the literal "--QNAM", which no dataset carries, so every
        // SUPP-scoped rule with a `--` variable guard was skipped.
        Rule rule = variableInclude("--QNAM");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("QNAM"), ""));
        assertEquals(
                "Requirements.Variables.All variable --QNAM (resolved QNAM) not present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("QVAL"), ""));
    }


    @Test
    void variablesDashDash_longerPrefixNowResolves()
    {
        // EC-36: substitution is unconditional once a prefix exists, mirroring Python's
        // `var.replace("--", wildcard_replacement)`. A 4-character prefix reaches here only when
        // the dataset's DOMAIN value genuinely is 4 characters (an AP dataset with no APID), where
        // Python substitutes too. Previously this silently left the entry raw.
        Rule rule = variableInclude("--SEQ");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("ADAESEQ"), "ADAE"));
        assertEquals(
                "Requirements.Variables.All variable --SEQ (resolved ADAESEQ) not present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("AESEQ"), "ADAE"));
    }


    @Test
    void variablesDashDashGlob_composes()
    {
        // '--*DT': prefix substitution first ('AE*DT'), then glob translation.
        Rule rule = variableInclude("--*DT");
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("AESTDT"), "AE"));
        assertEquals(
                "no variable matching Requirements.Variables.All entry --*DT (resolved AE*DT)"
                        + " present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("VSSTDT"), "AE"));
    }


    @Test
    void variablesDashDashExcludePattern_namesEntryAndResolvedForm()
    {
        Rule rule = variableExclude("--*DT");
        assertEquals(
                "variable AESTDT matches Requirements.Variables.None entry --*DT (resolved AE*DT)",
                ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "AESTDT"), "AE"));
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("USUBJID", "VSSTDT"), "AE"));
    }


    @Test
    void variablesDashDashExcludeLiteral_resolvedHitNamesBothForms()
    {
        Rule rule = variableExclude("--SEQ");
        assertEquals(
                "Requirements.Variables.None variable --SEQ (resolved AESEQ) present in dataset",
                ScopeMatcher.describeVariablesMismatch(rule, meta("AESEQ"), "AE"));
        assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("VSSEQ"), "AE"));
    }

    // ------------------------------------------------------------------
    // Overload delegation and boolean parity
    // ------------------------------------------------------------------


    @Test
    void twoArgOverloads_delegateWithNullPrefix()
    {
        Rule rule = variableInclude("--SEQ");
        DataTableMeta m = meta("AESEQ");
        assertEquals(ScopeMatcher.describeVariablesMismatch(rule, m, null),
                ScopeMatcher.describeVariablesMismatch(rule, m));
        assertEquals(ScopeMatcher.matchesVariables(rule, m, null),
                ScopeMatcher.matchesVariables(rule, m));
    }

    // ------------------------------------------------------------------
    // Review F6 — pattern detection takes precedence over the `--` branch
    // ------------------------------------------------------------------


    @Test
    void domainEntryMixingDashDashWithGlob_matchesAsPattern_notAsFamilyWildcard()
    {
        // "SUPP--*" is loader-validated as a GLOB (the `--` run is regex-quoted literally), so
        // the matcher must consume it as that pattern — which never matches a real dataset name
        // (none contain a literal "--") — and NOT as a SUPP family `--` wildcard that would
        // silently match every SUPP/SQ/AP dataset.
        Rule rule = domainInclude("SUPP--*");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPAE12"),
                "glob semantics: 'SUPP--*' must not family-match SUPPAE12");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPDM"),
                "glob semantics: 'SUPP--*' must not family-match SUPPDM");
        // ...while the glob itself still works on a name carrying the literal run.
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "SUPP--X"),
                "the quoted literal '--' run matches literally");

        // Same precedence on the Exclude side.
        Rule exclude = domainExclude("SUPP--*");
        assertNull(ScopeMatcher.describeDomainMismatch(exclude, "SUPPAE12"),
                "'SUPP--*' as Exclude no longer eats SUPP datasets via the family branch");
    }


    @Test
    void regexEntryContainingDashDash_goesPatternPath()
    {
        Rule rule = domainInclude("/^SUPP--$/");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "SUPP--"),
                "the regex matches the literal name");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPAE"),
                "regex containing '--' must not be consumed as a family wildcard");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPDM12"),
                "no family broadening for pattern entries");
    }


    @Test
    void regexEntryWithoutDashDash_unaffected()
    {
        Rule rule = domainInclude("/^(LB|SUPP)$/");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LB"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "SUPP"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "AE"));
    }


    @Test
    void pureDashDashEntries_keepStrictWildcardSemantics()
    {
        // No pattern metacharacters → the `--` branch is untouched by the F6 precedence fix.
        Rule rule = domainInclude("SUPP--");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPAE"),
                "two-char wildcard still matches SUPPAE");
        // The SUPP/AP family broadening is DELETED. A letter-split form is covered by the
        // caller's split-base re-test, and only when that base is the 6-character SUPP<RDOMAIN>
        // read from the DATA (unsplitNameFromData: SUPPLBHM + RDOMAIN=LB -> SUPPLB).
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPLBHM", "SUPPLB"),
                "the data-derived base SUPPLB is what strict SUPP-- matches");
        // ⚠ The table-less, NAME-only heuristic strips one trailing letter and yields the
        // 7-character SUPPLBH, which strict SUPP-- cannot match. Production never takes this
        // path (RuleGenerator passes OperationExecutor.unsplitNameFromData), but the two-arg
        // overload's weaker answer is pinned here so the difference is not rediscovered.
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "SUPPLBHM"),
                "name-only base SUPPLBH is 7 characters — strict SUPP-- misses it");
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "LB"));
    }


    @Test
    void variablesBooleanApi_agreesWithDescriberOnPatterns()
    {
        List<Rule> rules = List.of(variableInclude("*DY"), variableInclude("--SEQ"),
                variableInclude("--*DT"), variableExclude("*ORRES"), variableExclude("--SEQ"));
        List<DataTableMeta> metas = Arrays.asList(meta("AESTDY", "AESEQ", "AESTDT", "QSORRES"),
                meta("USUBJID"), null);
        List<String> prefixes = Arrays.asList("AE", "VS", null, "ADAE");
        for (Rule rule : rules)
        {
            for (DataTableMeta m : metas)
            {
                for (String prefix : prefixes)
                {
                    assertEquals(ScopeMatcher.describeVariablesMismatch(rule, m, prefix) == null,
                            ScopeMatcher.matchesVariables(rule, m, prefix),
                            "variables parity for prefix " + prefix);
                }
            }
        }
    }
}
