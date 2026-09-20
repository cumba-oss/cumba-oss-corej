package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.SubclassScope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code WildcardExpander.expandRequirements} is a <b>hand-written, field-by-field copy</b>. A
 * field added to {@link Requirements} without a line in the copier is dropped from every expanded
 * rule, silently and with no warning — the exact failure class
 * {@code OperationFieldRegistrationTest} exists to prevent for {@code Operation} ("Fix #99 lost
 * {@code offset} exactly that way"). This test extends that guard's shape to the two classes
 * {@code plans/done/PLAN-scope-requirements-split.md} touches.
 *
 * <h2>⚠⚠ Why both branches, and why that is not visible from the signature</h2>
 *
 * <p>
 * The copier <b>returns the template object itself</b> when there is nothing to substitute (no
 * variable facet). On that branch every field survives <em>by identity</em>, so a forgotten copy
 * line is invisible; the bug only appears on the copying branch. A test driving one branch
 * therefore passes while the bug ships, which is why
 * {@link #everyRequirementsFieldSurvivesTheCopyingBranch()} and
 * {@link #everyRequirementsFieldSurvivesTheIdentityBranch()} exist as a pair, and why
 * {@link #theTwoBranchesAreActuallyDifferent()} pins that they really are two different code paths
 * rather than one the fixtures happen to share.
 * </p>
 *
 * <h2>⛔ Where the hazard used to live — and why the {@link Scope} half is still here</h2>
 *
 * <p>
 * This class was originally written against {@code WildcardExpander.expandScope}, which had exactly
 * the same two-branch shape for {@code Scope.Variables}. Phase 5 retired that field, and with it
 * the copier: {@code Scope} now carries over <b>by reference</b>
 * ({@code rule.setScope(template.getScope())}), because every remaining axis is a closed vocabulary
 * or a dataset-name pattern and none can hold a wildcard token. The two-branch guard therefore
 * <em>moved</em> onto {@code expandRequirements}, and the tests moved with it.
 * </p>
 *
 * <p>
 * The {@code Scope} assertions were <b>re-armed rather than deleted</b>. Carrying by reference is a
 * guarantee, not the absence of one, and it is exactly the guarantee that would break if a future
 * axis needed substitution and someone reintroduced a copier: the reflection sweep below then
 * catches the field the new copier forgets, in the same shape it caught them before. It runs on
 * both fixtures — variable facet present and absent — because the requirement copy is what varies
 * now, and {@code Scope} must be unaffected by either.
 * </p>
 */
class WildcardExpanderScopeCompletenessTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }

    /**
     * Fields of {@link Scope} exempt from the survival sweep. {@code unknownKeys} is parse-time
     * diagnostic state; it is never null, so it would pass the sweep regardless — naming it keeps
     * the exemption explicit should a copier ever return and have to decide what to do with it.
     */
    private static final Set<String> SCOPE_FIELDS_NOT_CARRIED = Set.of("unknownKeys");

    /** {@link Requirements}' counterpart of {@link #SCOPE_FIELDS_NOT_CARRIED}. */
    private static final Set<String> REQUIREMENT_FIELDS_NOT_CARRIED = Set.of("unknownKeys");

    /**
     * {@link VariableRequirement}'s counterpart — ⛔ the net used to stop at {@link Requirements},
     * one level ABOVE the hazard (review F1, 2026-09-17).
     *
     * <p>
     * {@code WildcardExpander.expandRequirements} builds a <b>fresh</b> {@code VariableRequirement}
     * and copies its facets by hand, which is exactly the two-branch shape the class javadoc warns
     * about — but the sweep only walked {@code Requirements}, where {@code variables} is checked as
     * <em>one non-null field</em>. A field added to {@code VariableRequirement} without its sibling
     * copy line would be dropped on every wildcard-expanded rule, the copy-branch test would still
     * pass because {@code variables} is non-null, the identity branch would carry it invisibly, and
     * the gate would stay green. {@code anyGroups} is safe today only because
     * {@code anyGroupingSurvivesExpansion} was hand-written for it.
     * </p>
     *
     * <p>
     * ⚑ {@code anyMixedShape} is exempt <b>deliberately</b>: it is parse-time diagnostic state read
     * by loader gate R4, never a carried facet — recorded here in executable form rather than only
     * in a javadoc, so the exemption is a decision someone made rather than a gap.
     * </p>
     */
    private static final Set<String> VARIABLE_REQUIREMENT_FIELDS_NOT_CARRIED = Set.of("unknownKeys",
            "anyMixedShape");

    private static DataTableMeta adaeMeta()
    {
        // Two treatment periods on the primary => two expansion tuples (xx=01, xx=02).
        return MockTable.of().name("ADAE").col("TRT01P", "a").col("TRT02P", "b").build()
                .getMetaData();
    }


    /** A fully-populated {@link Scope} — every declared axis carries a distinguishable value. */
    private static Scope fullScope()
    {
        Scope scope = new Scope();
        ClassScope classes = new ClassScope();
        classes.setInclude(List.of("EVENTS"));
        scope.setClasses(classes);
        DomainScope domains = new DomainScope();
        domains.setInclude(List.of("ADAE"));
        scope.setDomains(domains);
        scope.setUseCase("INDH");
        DataStructureScope structures = new DataStructureScope();
        structures.setInclude(List.of("BASIC DATA STRUCTURE"));
        scope.setDataStructures(structures);
        SubclassScope subclasses = new SubclassScope();
        subclasses.setInclude(List.of("TIME-TO-EVENT"));
        scope.setSubclasses(subclasses);
        DatasetScope datasets = new DatasetScope();
        datasets.setInclude(List.of("ADAE"));
        datasets.setExclude(List.of("ADSL"));
        scope.setDatasets(datasets);
        return scope;
    }


    /** A fully-populated {@link Requirements} block. */
    private static Requirements fullRequirements(boolean withVariables)
    {
        Requirements req = new Requirements();
        req.setDatasets(List.of("EX"));
        req.setLibrary(Boolean.FALSE);
        req.setDefine(Boolean.FALSE);
        req.setDictionary(Boolean.FALSE);
        if (withVariables)
        {
            VariableRequirement variables = new VariableRequirement();
            variables.setAll(List.of("TRTxxP"));
            variables.setAnyGroups(List.of(List.of("ADSL.TRTxxPN", "AESEV")));
            variables.setNone(List.of("POOLID"));
            req.setVariables(variables);
        }
        return req;
    }


    private static Rule template(boolean withVariables)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-REQ-WC");
        rule.setCore(core);
        rule.setCheck(expr("not empty(TRTxxP)"));
        rule.setScope(fullScope());
        rule.setRequirements(fullRequirements(withVariables));
        return rule;
    }


    /** The declared instance fields of {@code type}, minus synthetic / static ones. */
    private static List<Field> declaredFields(Class<?> type)
    {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields())
        {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
            {
                out.add(f);
            }
        }
        return out;
    }


    private static Object valueOf(Object owner, Field field) throws Exception
    {
        field.setAccessible(true);
        return field.get(owner);
    }


    /**
     * The reflection half of the {@link Scope} sweep, run against one expanded rule: every declared
     * axis must still hold a value. Named field-by-field afterwards by
     * {@link #assertEveryScopeAxisHasItsOwnValue(Scope)}, so a future copier cannot satisfy this by
     * carrying one field across as another field's value.
     */
    private static Scope assertNoScopeFieldWasDropped(List<Rule> expanded, Rule template)
        throws Exception
    {
        assertFalse(expanded.isEmpty(), "no expansion — the sweep would be vacuous");
        TreeSet<String> dropped = new TreeSet<>();
        for (Rule rule : expanded)
        {
            Scope out = rule.getScope();
            assertNotNull(out, "the Scope block must reach the expanded rule at all");
            assertSame(template.getScope(), out,
                    "phase 5: Scope carries over BY REFERENCE — a copy here means a copier was"
                            + " reintroduced, and every axis below must be re-checked by hand");
            for (Field field : declaredFields(Scope.class))
            {
                if (SCOPE_FIELDS_NOT_CARRIED.contains(field.getName()))
                {
                    continue;
                }
                if (valueOf(out, field) == null)
                {
                    dropped.add(field.getName());
                }
            }
        }
        assertTrue(dropped.isEmpty(),
                "Scope axes silently dropped by WildcardExpander — every expanded rule loses"
                        + " them, with no warning anywhere: " + dropped);
        Scope first = expanded.get(0).getScope();
        assertNotNull(first);
        return first;
    }


    /** Every {@link Scope} axis, by name and by value, as {@link #fullScope()} authored it. */
    private static void assertEveryScopeAxisHasItsOwnValue(Scope out)
    {
        assertNotNull(out.getClasses());
        assertEquals(List.of("EVENTS"), out.getClasses().getInclude());
        assertNotNull(out.getDomains());
        assertEquals(List.of("ADAE"), out.getDomains().getInclude());
        assertEquals("INDH", out.getUseCase());
        assertNotNull(out.getDataStructures());
        assertEquals(List.of("BASIC DATA STRUCTURE"), out.getDataStructures().getInclude());
        assertNotNull(out.getSubclasses());
        assertEquals(List.of("TIME-TO-EVENT"), out.getSubclasses().getInclude());
        assertNotNull(out.getDatasets());
        assertEquals(List.of("ADAE"), out.getDatasets().getInclude());
        assertEquals(List.of("ADSL"), out.getDatasets().getExclude());
    }


    /**
     * ⛔ Re-armed, not deleted: this used to drive {@code expandScope}'s copying branch, which phase
     * 5 retired along with {@code Scope.Variables}. What survives is the guarantee the copier used
     * to have to deliver by hand — every {@code Scope} axis reaches the expanded rule — and it is
     * asserted here on the fixture whose {@code Requirements} take the <b>copying</b> branch, i.e.
     * the branch that does real work next door.
     */
    @Test
    @DisplayName("⚠ every Scope axis survives expansion (Requirements on the copying branch)")
    void everyScopeAxisSurvivesWhenRequirementsAreCopied() throws Exception
    {
        Rule template = template(true);
        List<Rule> expanded = WildcardExpander.expand(template, adaeMeta());
        assertEquals(2, expanded.size(), "one rule per treatment period");
        assertNotSame(template.getRequirements(), expanded.get(0).getRequirements(),
                "the fixture must drive the COPYING branch of expandRequirements");
        Scope out = assertNoScopeFieldWasDropped(expanded, template);
        assertEveryScopeAxisHasItsOwnValue(out);
    }


    /**
     * The twin of the above on the {@code Requirements} <b>identity</b> branch. Both exist because
     * {@code Scope} must be unaffected by which branch {@code expandRequirements} takes — the two
     * blocks are copied independently, and a future edit that folded the {@code Scope} carry-over
     * into the requirement copier would show up here first.
     */
    @Test
    @DisplayName("⚠ every Scope axis survives expansion (Requirements on the identity branch)")
    void everyScopeAxisSurvivesWhenRequirementsAreCarriedByIdentity() throws Exception
    {
        Rule template = template(false);
        List<Rule> expanded = WildcardExpander.expand(template, adaeMeta());
        assertSame(template.getRequirements(), expanded.get(0).getRequirements(),
                "the fixture must drive the IDENTITY branch of expandRequirements");
        Scope out = assertNoScopeFieldWasDropped(expanded, template);
        assertEveryScopeAxisHasItsOwnValue(out);
    }


    @Test
    @DisplayName("the copying branch: every declared Requirements field survives expansion")
    void everyRequirementsFieldSurvivesTheCopyingBranch() throws Exception
    {
        Rule template = template(true);
        List<Rule> expanded = WildcardExpander.expand(template, adaeMeta());
        TreeSet<String> dropped = new TreeSet<>();
        for (Rule rule : expanded)
        {
            Requirements out = rule.getRequirements();
            assertNotNull(out, "the Requirements block must reach the expanded rule at all");
            assertNotSame(template.getRequirements(), out,
                    "the fixture must drive the COPYING branch");
            for (Field field : declaredFields(Requirements.class))
            {
                if (REQUIREMENT_FIELDS_NOT_CARRIED.contains(field.getName()))
                {
                    continue;
                }
                if (valueOf(out, field) == null)
                {
                    dropped.add(field.getName());
                }
            }
        }
        assertTrue(dropped.isEmpty(),
                "Requirements fields silently dropped by WildcardExpander: " + dropped);

        // ⭐ F1: sweep one level DOWN, where expandRequirements actually hand-copies.
        List<String> droppedFacets = new ArrayList<>();
        for (Rule rule : expanded)
        {
            Requirements out = rule.getRequirements();
            assertNotNull(out);
            VariableRequirement vars = out.getVariables();
            assertNotNull(vars, "the variable facet must reach the expanded rule at all");
            assertNotSame(template.getRequirements().getVariables(), vars,
                    "the fixture must drive the COPYING branch for the facet too");
            for (Field field : declaredFields(VariableRequirement.class))
            {
                if (VARIABLE_REQUIREMENT_FIELDS_NOT_CARRIED.contains(field.getName()))
                {
                    continue;
                }
                if (valueOf(vars, field) == null)
                {
                    droppedFacets.add(field.getName());
                }
            }
        }
        assertTrue(droppedFacets.isEmpty(),
                "VariableRequirement facets silently dropped by WildcardExpander: "
                        + droppedFacets);
    }


    @Test
    @DisplayName("the identity branch: Requirements with no variable facet keeps every field")
    void everyRequirementsFieldSurvivesTheIdentityBranch() throws Exception
    {
        Rule template = template(false);
        List<Rule> expanded = WildcardExpander.expand(template, adaeMeta());
        assertFalse(expanded.isEmpty());
        for (Rule rule : expanded)
        {
            Requirements out = rule.getRequirements();
            assertNotNull(out);
            for (Field field : declaredFields(Requirements.class))
            {
                if (REQUIREMENT_FIELDS_NOT_CARRIED.contains(field.getName())
                        || "variables".equals(field.getName()))
                {
                    continue;
                }
                assertNotNull(valueOf(out, field), field.getName() + " was dropped");
            }
        }
    }


    /**
     * ⭐ N4's red target ({@code plans/done/PLAN-any-variable-sets.md} §5.1):
     * {@code expandRequirements} both <b>reads and writes</b> the {@code Any} facet, so a
     * substitution over {@code anyUnion()} would collapse {@code [[A,B],[C,D]]} — "one of each" —
     * into one union group — "one of the four" — for every wildcard-expanded rule, with every entry
     * still correctly substituted and everything green. Only an assertion on the GROUP COUNT after
     * expansion can tell the two apart.
     */
    @Test
    @DisplayName("⭐ Any GROUPING survives expansion — substituted per group, never flattened")
    void anyGroupingSurvivesExpansion() throws Exception
    {
        Rule template = template(true);
        Requirements req = template.getRequirements();
        assertNotNull(req);
        VariableRequirement vars = req.getVariables();
        assertNotNull(vars);
        vars.setAnyGroups(List.of(List.of("ADSL.TRTxxPN", "AESEV"), List.of("AESTDTC", "AEENDTC")));

        List<Rule> expanded = WildcardExpander.expand(template, adaeMeta());
        assertEquals(2, expanded.size(), "one rule per treatment period");
        List<String> qualifiedFirstEntries = new ArrayList<>();
        for (Rule rule : expanded)
        {
            Requirements outReq = rule.getRequirements();
            assertNotNull(outReq);
            VariableRequirement out = outReq.getVariables();
            assertNotNull(out);
            List<List<String>> groups = out.getAnyGroups();
            assertNotNull(groups, "the Any facet must survive the copying branch at all");
            assertEquals(2, groups.size(),
                    "a flattened substitution collapses the two groups into one union: " + groups);
            assertEquals("AESEV", groups.get(0).get(1),
                    "the literal entry stays in ITS group: " + groups);
            assertEquals(List.of("AESTDTC", "AEENDTC"), groups.get(1),
                    "an all-literal group is carried verbatim as its own group: " + groups);
            qualifiedFirstEntries.add(groups.get(0).get(0));
        }
        assertTrue(
                qualifiedFirstEntries.contains("ADSL.TRT01PN")
                        && qualifiedFirstEntries.contains("ADSL.TRT02PN"),
                "the qualified template entry must bind the tuple's xx INSIDE its group: "
                        + qualifiedFirstEntries);
    }


    @Test
    @DisplayName("a PRESENT but empty variable facet also takes the identity branch")
    void anEmptyVariableFacetTakesTheIdentityBranch()
    {
        Rule template = template(false);
        Requirements req = fullRequirements(false);
        // Present block, all three facets null — the second half of the identity condition, which
        // the `variables == null` fixture above never reaches.
        req.setVariables(new VariableRequirement());
        template.setRequirements(req);

        List<Rule> expanded = WildcardExpander.expand(template, adaeMeta());
        assertFalse(expanded.isEmpty());
        assertSame(template.getRequirements(), expanded.get(0).getRequirements());
        // Scope is by reference regardless of which requirement branch is taken.
        assertSame(template.getScope(), expanded.get(0).getScope());
    }


    @Test
    @DisplayName("⚠⚠ the two branches really are two code paths — identity vs copy")
    void theTwoBranchesAreActuallyDifferent()
    {
        Rule withVars = template(true);
        Rule withoutVars = template(false);
        List<Rule> copied = WildcardExpander.expand(withVars, adaeMeta());
        List<Rule> identity = WildcardExpander.expand(withoutVars, adaeMeta());
        assertNotSame(withVars.getRequirements(), copied.get(0).getRequirements(),
                "a variable facet forces a real copy");
        assertSame(withoutVars.getRequirements(), identity.get(0).getRequirements(),
                "no variable facet returns the template object itself — the branch on which a"
                        + " forgotten copy line is invisible");
        // ⛔ Scope is NOT a second discriminator any more: it is carried by reference on both
        // branches. Pinned here so the pair above cannot be misread as covering it too.
        assertSame(withVars.getScope(), copied.get(0).getScope());
        assertSame(withoutVars.getScope(), identity.get(0).getScope());
    }


    @Test
    @DisplayName("the variable facets are substituted per tuple, not carried literally")
    void requirementFacetsAreSubstitutedPerTuple()
    {
        List<Rule> expanded = WildcardExpander.expand(template(true), adaeMeta());
        List<String> alls = new ArrayList<>();
        List<String> anys = new ArrayList<>();
        for (Rule rule : expanded)
        {
            Requirements req = rule.getRequirements();
            assertNotNull(req);
            VariableRequirement vars = req.getVariables();
            assertNotNull(vars);
            assertNotNull(vars.getAll());
            assertNotNull(vars.getAnyGroups());
            alls.addAll(vars.getAll());
            anys.addAll(vars.anyUnion());
            assertEquals(List.of("POOLID"), vars.getNone(), "a literal facet is carried verbatim");
        }
        assertTrue(alls.contains("TRT01P") && alls.contains("TRT02P"), alls.toString());
        assertTrue(anys.contains("ADSL.TRT01PN") && anys.contains("ADSL.TRT02PN"),
                "a qualified entry must bind the SAME tuple index as the Check: " + anys);
        for (String entry : anys)
        {
            assertFalse(entry.contains("xx"),
                    "an unsubstituted token in a requirement is matched literally by a gate that"
                            + " runs before expansion, so the rule skips on every dataset: "
                            + entry);
        }
        // ⚠ Pre-existing, unchanged, and worth stating because phase 4 will move 1 140 rules
        // through this code: an UNQUALIFIED template token is substituted only when the Check
        // binds that very token (substituteScopeEntry's map lookup). `Requirements.Variables`
        // inherits that rule exactly as `Scope.Variables` had it — it is not a regression, and it
        // is not a widening either.
    }

}
