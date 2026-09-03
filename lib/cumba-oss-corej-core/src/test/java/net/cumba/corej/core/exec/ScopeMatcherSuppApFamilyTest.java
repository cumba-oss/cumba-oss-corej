package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * <b>The SUPP/AP family wildcard is deleted</b> (supersedes Fix #34, Symptom A). {@code SUPP--},
 * {@code SQ--}, {@code AP--} and {@code APFA--} are four <em>independent</em> strict {@code --}
 * tokens: each matches its own prefix plus exactly two characters, and none of them reaches another
 * family.
 *
 * <p>
 * Fix #34 added a length-agnostic, cross-family leg mirroring Python's
 * {@code _domain_matched_ap_or_supp}, on the warrant *"a known Python design quirk that Java
 * mirrors for parity"*. java-first removed parity as a constraint. What the quirk uniquely added
 * was the cross-family reach — {@code Exclude: ["SUPP--"]} silently excluding {@code APMH} — and
 * that is the defect. The split forms it appeared to serve are served instead by the callers'
 * <em>data-derived</em> split-base re-test ({@link OperationExecutor#unsplitNameFromData}), which
 * this class pins explicitly in {@link #dataDerivedBaseIsWhatCoversSplitForms()}.
 * </p>
 *
 * <p>
 * ⚠ {@code /data/testdata} still carries zero {@code AP*} and zero {@code SQ*} datasets (control:
 * 24 {@code SUPP*}), so no whole-study run exercises those two families. What these tests
 * <em>cannot</em> prove on their own is the step before the matcher: every call below hands
 * {@code unsplitName} in as a <b>string literal</b>, so nothing here shows that a real
 * {@code SUPPLBHM} dataset actually yields {@code SUPPLB} from its own columns. That link is made
 * by {@code ApSqDomainScopeFromDataTest} in {@code cumba-oss-corej-ruletest}, which derives the
 * base with {@link OperationExecutor#unsplitNameFromData} from ten committed {@code AP*} /
 * {@code SQ*} / {@code SUPP*} dataset fixtures and also drives the selection end-to-end through
 * {@code RuleGenerator}. Keep the two in step.
 * </p>
 */
class ScopeMatcherSuppApFamilyTest
{

    private static Rule withScope(List<String> include, List<String> exclude)
    {
        Rule r = new Rule();
        Scope s = new Scope();
        DomainScope d = new DomainScope();
        if (include != null)
        {
            d.setInclude(include);
        }
        if (exclude != null)
        {
            d.setExclude(exclude);
        }
        s.setDomains(d);
        r.setScope(s);
        return r;
    }


    private static Rule withSplitFilterAndExclude(boolean splitFilter, List<String> exclude)
    {
        Rule r = new Rule();
        Scope s = new Scope();
        DomainScope d = new DomainScope();
        d.setIncludeSplitDatasets(splitFilter);
        if (exclude != null)
        {
            d.setExclude(exclude);
        }
        s.setDomains(d);
        r.setScope(s);
        return r;
    }

    // -----------------------------------------------------------------------
    // The strict `--` contract — prefix plus exactly two characters
    // -----------------------------------------------------------------------


    @Test
    void exclude_supp_dashdash_excludes_canonical_6char_supp()
    {
        Rule r = withScope(null, List.of("SUPP--"));
        assertFalse(ScopeMatcher.matchesDomain(r, "SUPPAE"));
    }


    @Test
    void exclude_ap_dashdash_excludes_canonical_4char_ap()
    {
        Rule r = withScope(null, List.of("AP--"));
        assertFalse(ScopeMatcher.matchesDomain(r, "APMH"));
    }


    @Test
    void exclude_sq_dashdash_excludes_canonical_4char_sq()
    {
        Rule r = withScope(null, List.of("SQ--"));
        assertFalse(ScopeMatcher.matchesDomain(r, "SQLB"));
    }


    @Test
    void exclude_apfa_dashdash_is_an_ordinary_wildcard()
    {
        // Open question 3: once the family set is gone, APFA-- is just APFA + exactly 2.
        Rule r = withScope(null, List.of("APFA--"));
        assertFalse(ScopeMatcher.matchesDomain(r, "APFAMH"));
        assertTrue(ScopeMatcher.matchesDomain(r, "APFA"),
                "APFA-- needs the two trailing characters");
    }

    // -----------------------------------------------------------------------
    // ⚑ THE DEFECT THIS CHANGE REMOVES — cross-family reach, both directions
    // -----------------------------------------------------------------------


    @Test
    void exclude_supp_dashdash_no_longer_excludes_ap()
    {
        // Fix #34 quirk: ANY family wildcard in the list matched ANY family dataset, so
        // Exclude:["SUPP--"] silently excluded AP too. 16 shipped SUPP---scoped rules were
        // wrongly skipping AP datasets because of it.
        Rule r = withScope(null, List.of("SUPP--"));
        assertTrue(ScopeMatcher.matchesDomain(r, "APMH"),
                "SUPP-- must not reach AP datasets — the whole point of the deletion");
    }


    @Test
    void exclude_ap_dashdash_no_longer_excludes_supp_or_sq()
    {
        Rule r = withScope(null, List.of("AP--"));
        assertTrue(ScopeMatcher.matchesDomain(r, "SUPPAE"), "AP-- must not reach SUPP datasets");
        assertTrue(ScopeMatcher.matchesDomain(r, "SQLB"), "AP-- must not reach SQ datasets");
    }


    @Test
    void include_supp_dashdash_no_longer_includes_ap()
    {
        Rule r = withScope(List.of("SUPP--"), null);
        assertFalse(ScopeMatcher.matchesDomain(r, "APMH"),
                "Include:[SUPP--] must not select AP datasets");
        assertTrue(ScopeMatcher.matchesDomain(r, "SUPPAE"));
    }


    @Test
    void include_ap_dashdash_no_longer_includes_supp()
    {
        // CDISC-CG0309 / CG0650 / CORE-000181 / CORE-000778 shape: AP---scoped rules were
        // firing on SUPPLB. CDISC-CG0309-absent-DOMAIN-SUPPLB.cdt pinned that bug.
        Rule r = withScope(List.of("AP--"), null);
        assertFalse(ScopeMatcher.matchesDomain(r, "SUPPLB"),
                "Include:[AP--] must not select SUPP datasets");
        assertTrue(ScopeMatcher.matchesDomain(r, "APMH"));
    }

    // -----------------------------------------------------------------------
    // The length-agnostic reach is gone too — a `--` token is prefix + exactly 2
    // -----------------------------------------------------------------------


    @Test
    void supp_dashdash_does_not_match_a_longer_name_by_prefix_alone()
    {
        Rule r = withScope(null, List.of("SUPP--"));
        // Name-only path: SplitDatasetUtil.unsplitName("SUPPMYAEXTRA") is not a recognised split,
        // so neither the name nor the base is 6 characters.
        assertTrue(ScopeMatcher.matchesDomain(r, "SUPPMYAEXTRA"),
                "SUPP-- is prefix + exactly 2; it is not a startsWith test");
    }

    // -----------------------------------------------------------------------
    // ⚑ What actually covers the split forms: the DATA-DERIVED base re-test
    // -----------------------------------------------------------------------


    @Test
    void dataDerivedBaseIsWhatCoversSplitForms()
    {
        // This is the mechanism the deleted helper appeared to provide. It is supplied by the
        // caller, from the DOMAIN / RDOMAIN columns, not guessed from the name.
        Rule supp = withScope(null, List.of("SUPP--"));
        // SUPPLBHM carrying RDOMAIN=LB -> base SUPPLB (6 chars) -> strict SUPP-- matches.
        assertNotNull(ScopeMatcher.describeDomainMismatch(supp, "SUPPLBHM", "SUPPLB"),
                "the data-derived base SUPPLB is what SUPP-- matches");
        // ...and it stays inside its own family: an AP dataset is untouched.
        assertNull(ScopeMatcher.describeDomainMismatch(supp, "APMH", "APMH"));

        Rule sq = withScope(null, List.of("SQ--"));
        // An SQ dataset resolves to "SQ" + RDOMAIN, e.g. SQLB (4 chars) -> strict SQ-- matches.
        assertNotNull(ScopeMatcher.describeDomainMismatch(sq, "SQLBHM", "SQLB"),
                "the data-derived base SQLB is what SQ-- matches");

        Rule ap = withScope(null, List.of("AP--"));
        // APMH1 carries DOMAIN=APMH -> base APMH (4 chars) -> strict AP-- matches.
        assertNotNull(ScopeMatcher.describeDomainMismatch(ap, "APMH1", "APMH"),
                "the data-derived base APMH is what AP-- matches");
    }


    @Test
    void suppApfaIsTheShapeSdtmig34RenamesAway()
    {
        // SUPPAPFAMH resolves to base SUPPAPFA (8 chars), which strict SUPP-- cannot match.
        // SDTMIG v3.4 §8.4.2 requires exactly this dataset to be renamed SQAPFAMH, whose base
        // SQAPFA... is reached by SQ-- only when RDOMAIN yields a 2-character remainder. The
        // point of the assertion is that the ONE inexpressible shape is the one the standard
        // removes — recorded so nobody reintroduces the family wildcard to "fix" it.
        Rule supp = withScope(null, List.of("SUPP--"));
        assertNull(ScopeMatcher.describeDomainMismatch(supp, "SUPPAPFAMH", "SUPPAPFA"),
                "strict SUPP-- cannot reach an 8-character base — by design");
    }

    // -----------------------------------------------------------------------
    // Non-family datasets, and the untouched split paths
    // -----------------------------------------------------------------------


    @Test
    void exclude_supp_dashdash_does_not_exclude_non_family_dataset()
    {
        Rule r = withScope(null, List.of("SUPP--"));
        assertTrue(ScopeMatcher.matchesDomain(r, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(r, "LB"));
        assertTrue(ScopeMatcher.matchesDomain(r, "ADAE"));
    }


    @Test
    void include_ap_dashdash_covers_digit_split_via_name_heuristic()
    {
        // APMH1 -> SplitDatasetUtil.unsplitName -> APMH, matched by strict AP--. The
        // table-less caller still works for digit splits.
        Rule r = withScope(List.of("AP--"), null);
        assertTrue(ScopeMatcher.matchesDomain(r, "APMH"));
        assertTrue(ScopeMatcher.matchesDomain(r, "APMH1"));
    }


    @Test
    void core000510_style_scope_keeps_working_on_its_own_family()
    {
        // CORE-000510 shape: include_split_datasets=true, Exclude=[SUPP--, AP--]. Both tokens
        // are now present, so both families are excluded — explicitly, not by inference.
        Rule r = withSplitFilterAndExclude(true, List.of("SUPP--", "AP--"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(r, "SUPPLBHM", "SUPPLB"));
        assertNotNull(ScopeMatcher.describeDomainMismatch(r, "APMH1", "APMH"));
        assertTrue(ScopeMatcher.matchesDomain(r, "LB1"), "Non-SUPP/AP split still in scope");
    }


    @Test
    void empty_include_and_exclude_match_everything()
    {
        Rule r = withScope(null, null);
        assertTrue(ScopeMatcher.matchesDomain(r, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(r, "SUPPAE"));
        assertTrue(ScopeMatcher.matchesDomain(r, "APMH"));
    }


    @Test
    void exclude_with_no_family_pattern_unchanged()
    {
        Rule r = withScope(null, List.of("AE"));
        assertFalse(ScopeMatcher.matchesDomain(r, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(r, "SUPPAE"));
    }

}
