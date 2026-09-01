package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.cumba.cdisc.core.metadata.AdamDataStructureDetector;
import net.cumba.cdisc.core.metadata.AdamSubclassDetector;
import net.cumba.cdisc.core.model.DataStructureScope;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.SubclassScope;
import org.junit.jupiter.api.Test;

/**
 * Fix #117/#118: {@code Scope.Data_Structures} / {@code Scope.Subclasses} matchers. Documents the
 * two deliberate deviations from Python's {@code rule_applies_to_data_structure}: an Exclude-only
 * scope works (upstream's missing {@code if included:} guard rejects everything), and {@code ALL}
 * in Include still honours Exclude (house Domains/Classes convention).
 */
class ScopeMatcherStructureSubclassTest
{

    private static Rule structureRule(List<String> include, List<String> exclude)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DataStructureScope ds = new DataStructureScope();
        ds.setInclude(include);
        ds.setExclude(exclude);
        scope.setDataStructures(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule subclassRule(List<String> include, List<String> exclude)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        SubclassScope sc = new SubclassScope();
        sc.setInclude(include);
        sc.setExclude(exclude);
        scope.setSubclasses(sc);
        rule.setScope(scope);
        return rule;
    }

    // ------------------------------------------------------------------
    // Data_Structures
    // ------------------------------------------------------------------


    @Test
    void structure_noScope_matches()
    {
        assertNull(ScopeMatcher.describeDataStructureMismatch(new Rule(),
                AdamDataStructureDetector.BDS));
        assertNull(ScopeMatcher.describeDataStructureMismatch(structureRule(null, null),
                AdamDataStructureDetector.BDS));
        assertNull(ScopeMatcher.describeDataStructureMismatch(structureRule(List.of(), List.of()),
                AdamDataStructureDetector.BDS));
    }


    @Test
    void structure_includeMatchAndMiss()
    {
        Rule rule = structureRule(List.of("BASIC DATA STRUCTURE"), null);
        assertNull(ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.BDS));
        assertEquals(
                "data structure OCCURRENCE DATA STRUCTURE not in Scope.Data_Structures.Include"
                        + " [BASIC DATA STRUCTURE]",
                ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.OCCDS));
        assertTrue(ScopeMatcher.matchesDataStructure(rule, AdamDataStructureDetector.BDS));
        assertFalse(ScopeMatcher.matchesDataStructure(rule, AdamDataStructureDetector.OCCDS));
    }


    @Test
    void structure_excludeOnly_worksUnlikeUpstream()
    {
        // Deviation from Python (documented): Exclude-only excludes exactly the listed
        // structures; upstream's missing include-guard would reject every dataset.
        Rule rule = structureRule(null, List.of("ADAM OTHER"));
        assertNull(ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.BDS));
        assertEquals(
                "data structure ADAM OTHER matches Scope.Data_Structures.Exclude entry"
                        + " ADAM OTHER",
                ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.OTHER));
    }


    @Test
    void structure_allSentinel_stillHonoursExclude()
    {
        Rule rule = structureRule(List.of("ALL"), List.of("ADAM OTHER"));
        assertNull(
                ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.ADSL));
        // House deviation from Python's early return: Exclude still applies under ALL.
        assertEquals(
                "data structure ADAM OTHER matches Scope.Data_Structures.Exclude entry"
                        + " ADAM OTHER",
                ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.OTHER));
    }


    @Test
    void structure_nullDetected_rejectedByInclude()
    {
        Rule rule = structureRule(List.of("BASIC DATA STRUCTURE"), null);
        assertEquals(
                "dataset data structure undetermined but rule has a Scope.Data_Structures.Include"
                        + " [BASIC DATA STRUCTURE]",
                ScopeMatcher.describeDataStructureMismatch(rule, (String) null));
        // Fix #179: the set-valued form spells "undetermined" as an EMPTY set, and must agree.
        assertEquals(
                "dataset data structure undetermined but rule has a Scope.Data_Structures.Include"
                        + " [BASIC DATA STRUCTURE]",
                ScopeMatcher.describeDataStructureMismatch(rule, List.of()));
        // Exclude-only passes a null detection (nothing positively matches).
        assertNull(ScopeMatcher.describeDataStructureMismatch(
                structureRule(null, List.of("BASIC DATA STRUCTURE")), (String) null));
    }


    @Test
    void structure_caseAndSeparatorInsensitive()
    {
        Rule rule = structureRule(List.of("Basic Data Structure"), null);
        assertNull(ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.BDS));
    }

    // ------------------------------------------------------------------
    // Fix #179 — the structure is a SET (an is-a relation), not one token
    // ------------------------------------------------------------------

    /** A declared medical-device BDS dataset: the specialisation, then its base. */
    private static final List<String> DEVICE_BDS = AdamDataStructureDetector
            .structureSet(AdamDataStructureDetector.MEDICAL_DEVICE_BDS);

    /** A plain BDS dataset. */
    private static final List<String> PLAIN_BDS = List.of(AdamDataStructureDetector.BDS);

    /**
     * ⚑ <b>The safety property — and an N2 neuter detector.</b> A rule scoped to the <em>base</em>
     * keeps covering the specialisation, which is what makes the 78 shipped
     * {@code BASIC DATA STRUCTURE} / {@code OCCURRENCE DATA STRUCTURE} entries safe by
     * construction.
     *
     * <p>
     * ⚠ Neuter {@code AdamDataStructureDetector.structureSet} to {@code List.of(structureToken)} —
     * the variant alone, base dropped — and this reddens. Under the <em>old</em> fold (the N1
     * neuter) it would still pass, so on its own it pins nothing about the set; see
     * {@link #structure_includeOfTheVariantMatchesOnlyTheVariant_fix179()} for the test that does.
     * </p>
     */
    @Test
    void structure_includeOfTheBaseCoversTheSpecialisation_fix179()
    {
        Rule rule = structureRule(List.of("BASIC DATA STRUCTURE"), null);
        assertNull(ScopeMatcher.describeDataStructureMismatch(rule, DEVICE_BDS),
                "a BDS-scoped rule must still cover a medical-device BDS dataset");
        assertTrue(ScopeMatcher.matchesDataStructure(rule, DEVICE_BDS));
        // The occurrence side of the same relation.
        Rule occdsRule = structureRule(List.of("OCCURRENCE DATA STRUCTURE"), null);
        assertNull(ScopeMatcher.describeDataStructureMismatch(occdsRule, AdamDataStructureDetector
                .structureSet(AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS)));
        // ⚠⚠ …and NOT on the ADAM OTHER side. Phase 3a (owner decision 2026-08-09) dropped
        // DEVICE LEVEL ANALYSIS DATASET's ADAM OTHER supertype, so an ADAM OTHER-scoped rule no
        // longer covers a device-level dataset — which is the intended change, not a regression.
        // ADAM OTHER means "structure-less"; it was never an is-a claim, only a compatibility
        // mapping for a heuristic that could not see device datasets (Phase 3a.1 removed that
        // blindness). Leaving it would have made the rules scoped Classes.Include:[ADAM OTHER]
        // fire on device datasets once the corpus migrates in Phase 3b. Nothing is lost today:
        // ZERO shipped Scope.Data_Structures entries name ADAM OTHER.
        //
        // ⚠ That population was 8 when this was written and is 3 as of 2026-08-10 (PMDA-AD0376,
        // PMDA-AD1011, PMDA-AD1012A); the five PMDA-AD0252* rules dropped the token as not
        // sheet-faithful. The assertion below pins the MATCHER, not the corpus, so the count is
        // context only — the ZERO claim on the last line is the part that is a real invariant.
        Rule otherRule = structureRule(List.of("ADAM OTHER"), null);
        assertNotNull(
                ScopeMatcher.describeDataStructureMismatch(otherRule,
                        AdamDataStructureDetector.structureSet(
                                AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET)),
                "Phase 3a: DEVICE LEVEL ANALYSIS DATASET no longer carries ADAM OTHER");
        // The plain structure-less dataset is of course still covered.
        assertNull(ScopeMatcher.describeDataStructureMismatch(otherRule,
                AdamDataStructureDetector.structureSet(AdamDataStructureDetector.OTHER)));
    }


    /**
     * ⚑⚑ <b>THE NEUTER TARGET (N1).</b> The asymmetry an is-a relation implies: a rule scoped to
     * the <em>specialisation</em> matches a device dataset and does <b>not</b> match a plain one.
     *
     * <p>
     * ⚠ Collapse the set back to a single folded value — restore the pre-Fix-#175 fold so that a
     * declared {@code MEDICAL DEVICE BASIC DATA STRUCTURE} yields {@code [BASIC DATA STRUCTURE]} —
     * and the first assertion here reddens, because the variant token is no longer in the set. That
     * is the one behaviour the fold made unexpressible, and it is what this test pins. A test that
     * still passes with the fold in place would be pinning nothing.
     * </p>
     */
    @Test
    void structure_includeOfTheVariantMatchesOnlyTheVariant_fix179()
    {
        Rule rule = structureRule(List.of("MEDICAL DEVICE BASIC DATA STRUCTURE"), null);
        assertNull(ScopeMatcher.describeDataStructureMismatch(rule, DEVICE_BDS),
                "a device-BDS-scoped rule must match a device BDS dataset — this is exactly what the"
                        + " pre-Fix-#175 fold made impossible");
        assertEquals(
                "data structure BASIC DATA STRUCTURE not in Scope.Data_Structures.Include"
                        + " [MEDICAL DEVICE BASIC DATA STRUCTURE]",
                ScopeMatcher.describeDataStructureMismatch(rule, PLAIN_BDS),
                "a device-BDS-scoped rule must NOT match a plain BDS dataset");
        assertFalse(ScopeMatcher.matchesDataStructure(rule, PLAIN_BDS));
    }


    /**
     * Owner decision 2026-08-08: <b>{@code Exclude} by a supertype excludes its subtypes.</b>
     * {@code Exclude: [BASIC DATA STRUCTURE]} also excludes a medical-device BDS dataset, because
     * the base is in that dataset's set. Symmetric with {@code Include} — the asymmetric reading
     * ("only the plain ones") is what an author would otherwise assume, which is why it is stated
     * in {@code CORE-RULES-AUTHORING-GUIDELINES.md} §4.8 as well as pinned here.
     *
     * <p>
     * ⚠ N2 neuter detector: drop the base from the set and the first assertion reddens.
     * </p>
     */
    @Test
    void structure_excludeOfTheBaseAlsoExcludesTheSpecialisation_fix179()
    {
        Rule rule = structureRule(null, List.of("BASIC DATA STRUCTURE"));
        assertEquals(
                "data structure MEDICAL DEVICE BASIC DATA STRUCTURE (also BASIC DATA STRUCTURE)"
                        + " matches Scope.Data_Structures.Exclude entry BASIC DATA STRUCTURE",
                ScopeMatcher.describeDataStructureMismatch(rule, DEVICE_BDS));
        // The converse is NOT true: excluding the specialisation leaves the plain structure alone.
        Rule variantExclude = structureRule(null, List.of("MEDICAL DEVICE BASIC DATA STRUCTURE"));
        assertNull(ScopeMatcher.describeDataStructureMismatch(variantExclude, PLAIN_BDS),
                "Exclude of a subtype must not exclude its supertype");
        assertEquals(
                "data structure MEDICAL DEVICE BASIC DATA STRUCTURE (also BASIC DATA STRUCTURE)"
                        + " matches Scope.Data_Structures.Exclude entry"
                        + " MEDICAL DEVICE BASIC DATA STRUCTURE",
                ScopeMatcher.describeDataStructureMismatch(variantExclude, DEVICE_BDS));
    }


    /**
     * The mismatch message is user-visible {@code SKIPPED} text, so its shape is pinned. A
     * single-token set renders <b>byte-identically to the pre-Fix-#175 message</b> — that is the
     * property that keeps every existing skip reason unchanged — and a multi-token set names the
     * most specific token first, with the rest in an {@code (also …)} tail so the reader can see
     * why a supertype-scoped rule would have matched.
     */
    @Test
    void structure_mismatchMessageNamesTheMostSpecificTokenAndTheSet_fix179()
    {
        Rule rule = structureRule(List.of("SUBJECT LEVEL ANALYSIS DATASET"), null);
        // Single token: unchanged wording.
        assertEquals(
                "data structure BASIC DATA STRUCTURE not in Scope.Data_Structures.Include"
                        + " [SUBJECT LEVEL ANALYSIS DATASET]",
                ScopeMatcher.describeDataStructureMismatch(rule, PLAIN_BDS));
        // Set: most specific first, then the tail.
        assertEquals(
                "data structure MEDICAL DEVICE BASIC DATA STRUCTURE (also BASIC DATA STRUCTURE) not"
                        + " in Scope.Data_Structures.Include [SUBJECT LEVEL ANALYSIS DATASET]",
                ScopeMatcher.describeDataStructureMismatch(rule, DEVICE_BDS));
        // The single-token overload and a one-element set are the same call.
        assertEquals(
                ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.BDS),
                ScopeMatcher.describeDataStructureMismatch(rule, PLAIN_BDS));
    }


    /** {@code ALL} and the no-scope cases behave on a set exactly as on a single token. */
    @Test
    void structure_allSentinelAndNoScope_onASet_fix179()
    {
        assertNull(ScopeMatcher.describeDataStructureMismatch(new Rule(), DEVICE_BDS));
        assertNull(ScopeMatcher.describeDataStructureMismatch(structureRule(List.of("ALL"), null),
                DEVICE_BDS));
        // ALL still honours Exclude, and the exclusion sees the whole set.
        assertEquals(
                "data structure MEDICAL DEVICE BASIC DATA STRUCTURE (also BASIC DATA STRUCTURE)"
                        + " matches Scope.Data_Structures.Exclude entry BASIC DATA STRUCTURE",
                ScopeMatcher.describeDataStructureMismatch(
                        structureRule(List.of("ALL"), List.of("BASIC DATA STRUCTURE")),
                        DEVICE_BDS));
    }

    // ------------------------------------------------------------------
    // Subclasses
    // ------------------------------------------------------------------


    @Test
    void subclass_noScope_matches()
    {
        assertNull(ScopeMatcher.describeSubclassMismatch(new Rule(),
                AdamSubclassDetector.TIME_TO_EVENT));
        assertNull(ScopeMatcher.describeSubclassMismatch(subclassRule(null, null), (String) null));
    }


    @Test
    void subclass_includeRequiresPositiveDetection()
    {
        Rule rule = subclassRule(List.of("TIME-TO-EVENT"), null);
        assertNull(ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.TIME_TO_EVENT));
        // Q1 decision: a null-detected dataset (plain BDS) is skipped by an Include list.
        assertEquals("no subclass detected but rule has Scope.Subclasses.Include [TIME-TO-EVENT]",
                ScopeMatcher.describeSubclassMismatch(rule, (String) null));
        assertEquals("subclass ADVERSE EVENT not in Scope.Subclasses.Include [TIME-TO-EVENT]",
                ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.ADVERSE_EVENT));
    }


    @Test
    void subclass_excludeRejectsOnlyPositiveMatch()
    {
        Rule rule = subclassRule(null, List.of("TIME-TO-EVENT"));
        // Q1 decision: null-detected passes an Exclude-only scope.
        assertNull(ScopeMatcher.describeSubclassMismatch(rule, (String) null));
        assertNull(ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.ADVERSE_EVENT));
        assertEquals("subclass TIME-TO-EVENT matches Scope.Subclasses.Exclude entry TIME-TO-EVENT",
                ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.TIME_TO_EVENT));
    }


    @Test
    void subclass_multiTokenVariant()
    {
        // Fix #119: a dataset may declare several subclasses — Include matches on ANY of them.
        Rule include = subclassRule(List.of("TIME-TO-EVENT"), null);
        assertNull(ScopeMatcher.describeSubclassMismatch(include,
                List.of("ADVERSE EVENT", "TIME-TO-EVENT")));
        assertEquals("subclass ADVERSE EVENT not in Scope.Subclasses.Include [TIME-TO-EVENT]",
                ScopeMatcher.describeSubclassMismatch(include, List.of("ADVERSE EVENT")));
        assertEquals("no subclass detected but rule has Scope.Subclasses.Include [TIME-TO-EVENT]",
                ScopeMatcher.describeSubclassMismatch(include, List.of()));
        // Exclude rejects when ANY detected token matches, naming the offender.
        Rule exclude = subclassRule(null, List.of("ADVERSE EVENT"));
        assertEquals("subclass ADVERSE EVENT matches Scope.Subclasses.Exclude entry ADVERSE EVENT",
                ScopeMatcher.describeSubclassMismatch(exclude,
                        List.of("TIME-TO-EVENT", "ADVERSE EVENT")));
    }


    @Test
    void subclass_allSentinel()
    {
        Rule rule = subclassRule(List.of("ALL"), null);
        assertNull(ScopeMatcher.describeSubclassMismatch(rule, (String) null));
        assertNull(ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.TIME_TO_EVENT));
    }


    /**
     * ⚑⚑ <b>What the branch ambiguity would actually cost, end to end</b> (wave 19 /
     * {@code PLAN-adam-subclass-ambiguity.md}). A BDS dataset carrying {@code CNSR} <em>and</em>
     * the relative-time signature satisfies two branches of {@link AdamSubclassDetector#detect};
     * the first-match-wins chain answers {@code TIME-TO-EVENT}, and this test shows the consequence
     * at the gate: the 17 shipped {@code NON-COMPARTMENTAL ANALYSIS} rules are <b>skipped</b> on
     * that dataset, while <b>no rule at all</b> scopes {@code TIME-TO-EVENT} (measured 2026-08-08
     * by parsing {@code Scope.Subclasses} out of all 3&nbsp;727 {@code rules-src/checks} YAMLs:
     * {@code ADVERSE EVENT} 55, {@code NON-COMPARTMENTAL ANALYSIS} 17, {@code POPULATION
     * PHARMACOKINETIC ANALYSIS} 6, {@code TIME-TO-EVENT} 0). ⇒ the dataset is silenced, not
     * mislabelled.
     *
     * <p>
     * ⚠ Left as-is deliberately, not overlooked: the shape occurs in <b>0</b> of the 8&nbsp;197
     * dataset definitions available to this project, a declared subclass shields it outright under
     * the default {@code corej.defineFirst=true}, and the branch order is correct on specificity
     * grounds. The measurement and the adjudication are on {@link AdamSubclassDetector#detect}.
     * </p>
     *
     * <p>
     * ⚠ Neuter-and-watch: reorder the BDS branches in {@code detect} and the first assertion
     * reddens. The second assertion is the control — it shows the NCA rule <em>would</em> have
     * matched, so the skip is a precedence result rather than an unrelated miss.
     * </p>
     */
    @Test
    void subclass_ambiguousColumnsResolveToAnUnscopedToken()
    {
        List<String> ambiguous = List.of("PARAMCD", "AVAL", "CNSR", "NFRLT", "AFRLT");
        List<String> plainBds = List.of(AdamDataStructureDetector.BDS);
        Rule ncaRule = subclassRule(List.of("NON-COMPARTMENTAL ANALYSIS"), null);

        assertEquals(
                "subclass TIME-TO-EVENT not in Scope.Subclasses.Include"
                        + " [NON-COMPARTMENTAL ANALYSIS]",
                ScopeMatcher.describeSubclassMismatch(ncaRule,
                        AdamSubclassDetector.resolve(plainBds, ambiguous, List.of(), true)),
                "an NCA-scoped rule is SKIPPED on the ambiguous dataset");
        // Control: drop CNSR and the same rule matches — branch 3 was live all along.
        assertNull(ScopeMatcher.describeSubclassMismatch(ncaRule, AdamSubclassDetector
                .resolve(plainBds, List.of("PARAMCD", "AVAL", "NFRLT", "AFRLT"), List.of(), true)));
        // The same shape under the popPK branch: the 6 popPK rules are skipped just as the 17 NCA
        // ones are.
        Rule popPkRule = subclassRule(List.of("POPULATION PHARMACOKINETIC ANALYSIS"), null);
        List<String> ambiguousPopPk = List.of("PARAMCD", "AVAL", "CNSR", "DV", "MDV", "AMT");
        assertEquals(
                "subclass TIME-TO-EVENT not in Scope.Subclasses.Include"
                        + " [POPULATION PHARMACOKINETIC ANALYSIS]",
                ScopeMatcher.describeSubclassMismatch(popPkRule,
                        AdamSubclassDetector.resolve(plainBds, ambiguousPopPk, List.of(), true)));
        assertNull(ScopeMatcher.describeSubclassMismatch(popPkRule, AdamSubclassDetector.resolve(
                plainBds, List.of("PARAMCD", "AVAL", "DV", "MDV", "AMT"), List.of(), true)));
    }


    @Test
    void subclass_caseAndSeparatorInsensitive()
    {
        // normalize() collapses case AND separators — "Time-To-Event" and "TIME TO EVENT" match.
        Rule rule = subclassRule(Arrays.asList("Time-To-Event"), null);
        assertNull(ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.TIME_TO_EVENT));
    }

}
