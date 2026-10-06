package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.exec.DatasetIdentity;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The scenario harness's scope pre-gate: the production scope verdict for a scenario's primary
 * dataset, mirrored here because production enforces the Domains / Classes axes upstream of
 * {@code RuleRunner} ({@code DatasetRuleResolver} → {@link ScopeMatcher}), so an out-of-scope
 * primary means the rule never runs. Lifted verbatim from the corpus's
 * {@code AbstractRuleTestSuitesFactory} ({@code PLAN-rule-authoring-api} phase 3); the corpus
 * factory and the rule editor both call it.
 *
 * <p>
 * Five axes, in the production order — Domains, <b>Datasets</b>, Classes, Data_Structures,
 * Subclasses — matching {@code DatasetRuleResolver.describeScopeSkip} call for call. ⚠ An axis
 * missing from this list is an axis the pre-gate does not judge, and a scenario would then pass
 * because the gate was off for it rather than because the rule was in scope; {@code Scope.Datasets}
 * was exactly that hole until phase 5 of {@code plans/done/PLAN-scope-requirements-split.md}.
 * </p>
 *
 * <p>
 * The two axes differ in how an undeclared value is treated, deliberately:
 * </p>
 * <ul>
 * <li>the <b>class</b> axis reads the scenario's {@code class=} when it declares one. ⚠ Since
 * <b>Fix #206</b> an absent {@code class=} no longer switches the axis off for an <em>ADaM</em>
 * class scope: ADaM has no observation class, so those tokens are structure tokens and are judged
 * from the detected structure set below — see {@link #describeAdamClassMismatch}, which also
 * documents why an SDTM class scope stays undetermined;</li>
 * <li>the <b>ADaM structure / subclass</b> axes <b>detect</b> from the fixture's columns when the
 * scenario declares no {@code structure=} / {@code subclass=}. A {@code .cdt} fixture always
 * carries real columns, so detection is meaningful and a structure- or subclass-scoped rule cannot
 * be satisfied by omission. An explicit declaration is the <em>declared</em> (Define-XML
 * {@code def:Class} / {@code def:SubClass}) tier, which since <b>Fix #154</b> <em>overrides</em>
 * the columns by default; {@code -Dcorej.defineFirst=false} restores the old columns-first order,
 * where a declaration is only a fallback.</li>
 * </ul>
 *
 * <p>
 * ⚠⚠ <b>Phase 3a.3 — the two are no longer independent.</b> Define-XML has a single
 * {@code def:Class} element, which is the SDTM observation class for an SDTM dataset and the ADaM
 * <em>data structure</em> for an ADaM one (ADaM has no class of its own), and one value therefore
 * feeds both gates in a real run. A scenario's {@code class=} is read as a structure declaration
 * too when it names one — {@link ScenarioDeclaredScopeProvider#effectiveDeclaredStructure} — so a
 * fixture saying {@code class="BASIC DATA STRUCTURE"} means in the harness what it would mean in a
 * study. An SDTM class value is not an ADaM structure and is unaffected.
 * </p>
 *
 * <p>
 * {@code RuleRunner} applies the same two ADaM axes at execution time, from the same detectors with
 * the same declared tier — the declaration reaches it via {@link ScenarioDeclaredScopeProvider},
 * which the runner sees as an ordinary {@code MetadataProvider}. This pre-gate therefore does not
 * change <em>whether</em> a scenario is in scope; it attributes the verdict to the scope layer that
 * owns it, which is what makes {@code expect=skipped} the honest contract rather than a
 * mid-execution SKIPPED.
 * </p>
 */
public final class ScenarioScopeGate
{

    private ScenarioScopeGate()
    {
    }


    /**
     * The production scope verdict for the scenario's primary dataset.
     *
     * @param aRule
     *            the rule
     * @param aPrimary
     *            the scenario's primary dataset
     * @return {@code null} when in scope, else the first failing axis's description
     */
    public static @Nullable String describeScopeMismatch(Rule aRule, OverlayDataTable aPrimary)
    {
        // Same inputs as production (DatasetRuleResolver.describeScopeSkip): the dataset name plus
        // the DOMAIN/RDOMAIN-derived unsplit name, so split datasets (LBHEMAT with DOMAIN=LB) match
        // their parent domain's scope.
        String datasetName = java.util.Objects.requireNonNullElse(aPrimary.getMetaData().getName(),
                "");
        String mismatch = ScopeMatcher.describeDomainMismatch(aRule, datasetName,
                DatasetIdentity.unsplitNameFromData(aPrimary));
        if (mismatch != null)
        {
            return mismatch;
        }
        // Scope.Datasets (owner requirement #5, PLAN-scope-requirements-split phase 5) —
        // immediately after Domains, exactly where DatasetRuleResolver.describeScopeSkip puts it,
        // and on the MEMBER file name with no split-base re-test. Without this leg every .cdt
        // scenario on a Datasets-scoped rule is pre-gated wrong, and silently: the axis simply
        // would not be judged, so a fixture would pass because the gate was off for it. Inert
        // while the corpus ships zero carriers — which is precisely why it has to be wired before
        // one appears.
        mismatch = ScopeMatcher.describeDatasetMismatch(aRule, datasetName);
        if (mismatch != null)
        {
            return mismatch;
        }
        // Exactly the inputs and precedence RuleRunner uses (Fix #117/#118/#119): the column
        // heuristic plus the DECLARED tier, which here is the scenario's structure= / subclass=
        // rather than a Define-XML def:Class / def:SubClass. Same call, same
        // corej.defineFirst preference — so this pre-gate cannot disagree with the engine.
        // Computed BEFORE the class gate since Fix #206: an ADaM Classes scope is judged from this
        // same structure set (see describeAdamClassMismatch).
        List<String> columns = scopeColumnNames(aPrimary);
        boolean defineFirst = AdamDataStructureDetector.defineFirstPreference();
        String name = datasetName;
        // Phase 3a.3: the declared tier is `structure=` OR an ADaM-structure `class=`. Production
        // has one def:Class element feeding both the class gate and the structure gate; the
        // harness's independent-axes treatment was the divergence.
        List<String> structures = AdamDataStructureDetector.detectAll(name, columns,
                ScenarioDeclaredScopeProvider.effectiveDeclaredStructure(aPrimary), defineFirst);
        Object datasetClass = aPrimary.getMetaData()
                .getMetaData(ScenarioDeclaredScopeProvider.CLASS_KEY);
        mismatch = datasetClass != null
                ? ScopeMatcher.describeClassMismatch(aRule, datasetClass.toString())
                : describeAdamClassMismatch(aRule, structures);
        if (mismatch != null)
        {
            return mismatch;
        }
        mismatch = ScopeMatcher.describeDataStructureMismatch(aRule, structures);
        if (mismatch != null)
        {
            return mismatch;
        }
        return ScopeMatcher.describeSubclassMismatch(aRule,
                AdamSubclassDetector.resolve(name, structures, columns,
                        ScenarioDeclaredScopeProvider.declaredSubclasses(aPrimary), defineFirst));
    }


    /**
     * <b>Fix #206 — the ADaM arm of the {@code Scope.Classes} gate.</b> Returns {@code null} when
     * the rule's {@code Scope.Classes} block does not apply here or is satisfied by
     * {@code aStructures}, else the mismatch description.
     *
     * <p>
     * Reached only when the scenario declares no {@code class=}. Until Fix #206 that meant the
     * class axis was <em>not judged at all</em>, which is the harness/production divergence wave 27
     * recorded as {@code R-d}: a fixture passed because the gate was off for it, not because the
     * rule was in scope. Production has no such hole — {@code DatasetRuleResolver} judges
     * {@code Scope.Classes} for every dataset, strict-on-null (Fix #41).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Production's chain cannot be reproduced literally here, and reproducing it partially
     * would be worse than the hole.</b> {@code LibraryValidator.classNameFor} resolves the class
     * through {@code MetadataProvider.getDatasetClass(member, domain, columns)}, whose decisive
     * ADaM tier is the CDISC Library <em>product reverse-walk</em> ({@code AdamProduct}). No
     * {@code .cdt} scenario has a Library — {@code MapBackedLibraryMetadataProvider} does not
     * override {@code getDatasetClass} at all, so it returns {@code null} — and the curated offline
     * fallback ({@code DomainClassMap}) has <b>zero</b> {@code adam} entries. A literal
     * transcription would therefore resolve {@code null} (or the FU-4 {@code ADAM OTHER} sentinel)
     * for essentially every ADaM fixture and skip them all, which is the opposite of what a
     * Library-backed production run does.
     * </p>
     *
     * <p>
     * ⚑ What <em>is</em> reproducible is the semantics: <b>ADaM has no observation class</b>. The
     * single Define-XML {@code def:Class} element carries the ADaM <em>data structure</em>
     * ({@code PLAN-adam-structure-set-valued.md} §7.1 — identity, no mapping), so an ADaM
     * {@code Scope.Classes} token is a structure token and is judged from exactly the set the
     * {@code Scope.Data_Structures} gate uses, lifted over the is-a set the same way
     * ({@code Include} is satisfied when <b>any</b> detected token matches). The per-token verdict
     * comes from {@link ScopeMatcher#describeClassMismatch} itself, so normalisation and the
     * {@code ALL} wildcard cannot drift from the class matcher.
     * </p>
     *
     * <p>
     * <b>Three deliberate refusals</b>, each returning {@code null} (i.e. leaving the pre-Fix-#206
     * behaviour) rather than guessing:
     * </p>
     * <ul>
     * <li><b>SDTM class scopes.</b> Judged only when the block names at least one ADaM structure
     * token ({@link AdamDataStructureDetector#structureTokenFromDeclaredClass}). An SDTM class
     * genuinely is undetermined without a Library, and the wave-27 census found <b>0</b> files
     * mixing an SDTM class with an ADaM structure, so the two populations do not overlap.</li>
     * <li><b>{@code Exclude}.</b> Lifting an Exclude list over a set is not expressible through
     * {@link ScopeMatcher#describeClassMismatch} per token (that method folds Include and Exclude
     * into one verdict), and the corpus has <b>0</b> ADaM {@code Classes.Exclude} entries — its
     * only four are {@code RELATIONSHIP}, which is SDTM. Reimplementing the lift here would put
     * matcher semantics in the harness for a population of zero.</li>
     * <li><b>An empty/absent {@code Include}.</b> Nothing to judge.</li>
     * </ul>
     *
     * @param aRule
     *            the rule
     * @param aStructures
     *            the detected structure set, most specific first
     * @return {@code null} when not judged or satisfied, else the mismatch
     */
    public static @Nullable String describeAdamClassMismatch(Rule aRule, List<String> aStructures)
    {
        if (aRule.getScope() == null || aRule.getScope().getClasses() == null)
        {
            return null;
        }
        ClassScope classes = aRule.getScope().getClasses();
        List<String> include = classes.getInclude();
        if (include == null || include.isEmpty()
                || (classes.getExclude() != null && !classes.getExclude().isEmpty()))
        {
            return null;
        }
        if (include.stream().noneMatch(
                t -> AdamDataStructureDetector.structureTokenFromDeclaredClass(t) != null))
        {
            return null;
        }
        for (String structure : aStructures)
        {
            if (ScopeMatcher.describeClassMismatch(aRule, structure) == null)
            {
                return null;
            }
        }
        // Names the MOST SPECIFIC detected token first, like describeDataStructureMismatch, and
        // appends the full set when there is an is-a chain so the reader can see why a
        // supertype-scoped rule would have matched.
        return "data structure " + aStructures.getFirst() + " not in Scope.Classes.Include "
                + include + (aStructures.size() > 1 ? " (detected " + aStructures + ")" : "");
    }


    /**
     * Whether {@code aRule} carries one of the two gates that read the declared ADaM tier. Mirrors
     * {@code RuleRunner}'s own guard exactly — the runner computes the structure set (and therefore
     * consults {@code getDeclaredDatasetClass}) only inside
     * {@code rule.getScope() != null && (getDataStructures() != null || getSubclasses() != null)}.
     * For every other rule a declared-tier decoration is unobservable, which is what lets
     * {@link ScenarioDeclaredScopeProvider#install} leave the {@code class=}-only fixtures that
     * carry no provider block alone.
     *
     * @param aRule
     *            the rule
     * @return whether the declared tier is read
     */
    public static boolean readsDeclaredAdamTier(Rule aRule)
    {
        return aRule.getScope() != null && (aRule.getScope().getDataStructures() != null
                || aRule.getScope().getSubclasses() != null);
    }


    /** Column names in declared order — mirrors {@code DatasetRuleResolver.doGenerate}. */
    public static List<String> scopeColumnNames(OverlayDataTable aPrimary)
    {
        DataTableMeta meta = aPrimary.getMetaData();
        List<String> names = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }
}
