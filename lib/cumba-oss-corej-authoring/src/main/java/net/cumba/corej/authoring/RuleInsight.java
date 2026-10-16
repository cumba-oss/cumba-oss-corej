package net.cumba.corej.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.MetadataProductKeys;
import net.cumba.corej.core.metadata.store.BaseIgView;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.OutputVariableListing;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The static analysis an editor needs about one loaded rule — rule plus metadata store only, no
 * data, no execution ({@code PLAN-rule-authoring-api} phase 2).
 *
 * <ul>
 * <li>{@link #derived(Rule)} — the fields the engine derives: Sensitivity, the Output_Variables
 * listing (effective / authored / excluded), Authorities, the evaluation domain, wildcard
 * names;</li>
 * <li>{@link #referencedVariables(Rule)} — every variable name the rule names, with where it names
 * it: Check (per level), Precondition, Bindings, Requirements.Variables, Match_Datasets keys and
 * Output_Variables — the quoted names of {@code var_exists}-family calls included, {@code --} forms
 * kept as written;</li>
 * <li>{@link #scopeUniverse(Rule, MetadataStore, Collection)} — the datasets of the rule's
 * standards the rule's {@code Scope} admits, judged with the production predicates
 * ({@link ScopeMatcher}), and the variable names an author may complete from;</li>
 * <li>{@link #related(String, Collection, CorpusIndex)} — the rules sharing an authority reference,
 * an equivalence class or a minting parentage.</li>
 * </ul>
 *
 * <p>
 * Nothing here re-implements an engine decision: Sensitivity comes from {@link RuleClassifier},
 * Output_Variables from {@link OutputVariableListing} (the deriver), the scope verdicts from
 * {@link ScopeMatcher}, the wildcard names from {@link WildcardExpander}.
 * </p>
 */
public final class RuleInsight
{

    /** The calls whose first positional argument, when a string literal, names a column. */
    private static final Set<String> NAME_LITERAL_CALLS = Set.of("var_exists", "var_not_exists",
            "var_is_empty", "var_is_single_value", "var_is_all_missing");

    private RuleInsight()
    {
    }

    // ---- derived fields -------------------------------------------------------------------

    /**
     * The engine-derived view of a rule.
     *
     * @param sensitivity
     *            the classifier's derivation (value, confidence, rationale)
     * @param outputVariables
     *            effective / authored / excluded Output_Variables
     * @param authorities
     *            the loaded {@code Authorities} (released shape: Organization + Rule_Ids), or
     *            {@code null} when the rule carries none
     * @param evaluationDomain
     *            the inferred evaluation domain's name, or {@code null} when the rule has none
     * @param wildcardNames
     *            the {@code --}-prefixed wildcard names the Check expands over
     * @param loadError
     *            the rule's load error, or {@code null}
     */
    public record DerivedFields(RuleClassifier.Derived<Sensitivity> sensitivity,
            OutputVariableListing outputVariables, @Nullable List<Authority> authorities,
            @Nullable String evaluationDomain, Set<String> wildcardNames,
            @Nullable String loadError)
    {

        public DerivedFields
        {
            wildcardNames = Set.copyOf(wildcardNames);
            authorities = authorities == null ? null : List.copyOf(authorities);
        }
    }

    /**
     * The derived fields of a loaded rule.
     *
     * @param aRule
     *            a rule loaded through {@code RulePackageLoader} (a hand-built rule derives on
     *            first read where the engine supports it)
     * @return the derived fields
     */
    public static DerivedFields derived(Rule aRule)
    {
        Objects.requireNonNull(aRule, "rule");
        return new DerivedFields(RuleClassifier.deriveSensitivity(aRule),
                OutputVariableListing.of(aRule), aRule.getAuthorities(),
                domainName(aRule.getEvaluationDomain()),
                WildcardExpander.collectWildcardNames(aRule), aRule.getLoadError());
    }


    /**
     * The evaluation domain's name: {@code DATASET}, {@code VARIABLE}, {@code ROW} or {@code CELL}.
     */
    static @Nullable String domainName(@Nullable Domain aDomain)
    {
        if (aDomain == null)
        {
            return null;
        }
        if (aDomain.equals(Domain.CELL))
        {
            return "CELL";
        }
        if (aDomain.equals(Domain.ROW))
        {
            return "ROW";
        }
        if (aDomain.equals(Domain.VARIABLE))
        {
            return "VARIABLE";
        }
        return "DATASET";
    }

    // ---- referenced variables -------------------------------------------------------------

    /**
     * One variable name the rule names.
     *
     * @param name
     *            the name as written — {@code --DTC}, {@code DM.RFSTDTC}, {@code AESEQ}
     * @param where
     *            where it is named: {@code Check}, {@code Check[Error]}, {@code Precondition},
     *            {@code Bindings[$x]}, {@code Requirements.Variables.All},
     *            {@code Requirements.Variables.Any[0]}, {@code Match_Datasets[DM].Keys},
     *            {@code Outcome.Output_Variables}
     * @param quoted
     *            whether it was written as a string literal (a {@code var_exists("X")} argument)
     *            rather than as a column reference
     */
    public record VariableRef(String name, String where, boolean quoted)
    {
    }

    /**
     * Every variable name a rule names, in walk order, distinct by (name, where, quoted).
     *
     * @param aRule
     *            a loaded rule
     * @return the references
     */
    public static List<VariableRef> referencedVariables(Rule aRule)
    {
        SequencedSet<VariableRef> out = new LinkedHashSet<>();
        if (aRule.getCheckExpr() != null)
        {
            walk(aRule.getCheckExpr(), "Check", out);
        }
        Map<Severity, Expr> levels = aRule.getCheckLevelExprs();
        if (levels != null)
        {
            for (Map.Entry<Severity, Expr> level : levels.entrySet())
            {
                walk(level.getValue(), "Check[" + level.getKey().name() + "]", out);
            }
        }
        if (aRule.getPreconditionExpr() != null)
        {
            walk(aRule.getPreconditionExpr(), "Precondition", out);
        }
        List<CompiledBinding> bindings = aRule.getCompiledBindings();
        if (bindings != null)
        {
            for (CompiledBinding b : bindings)
            {
                walk(b.expression(), "Bindings[" + b.name() + "]", out);
            }
        }
        if (aRule.getRequirements() != null && aRule.getRequirements().getVariables() != null)
        {
            VariableRequirement v = aRule.getRequirements().getVariables();
            addAll(v.getAll(), "Requirements.Variables.All", out);
            addGroups(v.getAnyGroups(), "Requirements.Variables.Any", out);
            addAll(v.getNone(), "Requirements.Variables.None", out);
            addGroups(v.getAllOrNoneGroups(), "Requirements.Variables.All_Or_None", out);
        }
        if (aRule.getMatchDatasets() != null)
        {
            for (MatchDataset md : aRule.getMatchDatasets())
            {
                addAll(md.getKeys(), "Match_Datasets[" + md.getName() + "].Keys", out);
            }
        }
        if (aRule.getOutcome() != null)
        {
            for (String name : OutputVariableToken
                    .includes(aRule.getOutcome().getOutputVariables()))
            {
                if (!name.startsWith("$"))
                {
                    out.add(new VariableRef(name, "Outcome.Output_Variables", false));
                }
            }
        }
        return List.copyOf(out);
    }


    private static void addAll(@Nullable List<String> aNames, String aWhere, Set<VariableRef> aOut)
    {
        if (aNames == null)
        {
            return;
        }
        for (String name : aNames)
        {
            if (name != null && !name.isBlank())
            {
                aOut.add(new VariableRef(name.strip(), aWhere, false));
            }
        }
    }


    private static void addGroups(@Nullable List<List<String>> aGroups, String aWhere,
            Set<VariableRef> aOut)
    {
        if (aGroups == null)
        {
            return;
        }
        for (int i = 0; i < aGroups.size(); i++)
        {
            addAll(aGroups.get(i), aWhere + "[" + i + "]", aOut);
        }
    }


    private static void walk(Expr aExpr, String aWhere, Set<VariableRef> aOut)
    {
        switch (aExpr)
        {
        case Expr.Ref ref ->
        {
            if (ref.kind() == OperandKind.COLUMN || ref.kind() == OperandKind.WILDCARD_COLUMN
                    || ref.kind() == OperandKind.DOTTED_REF)
            {
                aOut.add(new VariableRef(ref.name(), aWhere, false));
            }
        }
        case Expr.Call call ->
        {
            FunctionDescriptor descriptor = FunctionRegistry.descriptor(call.name());
            for (int i = 0; i < call.args().size(); i++)
            {
                Expr arg = call.args().get(i);
                if (arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                        && namesAColumn(call.name(), descriptor, i))
                {
                    aOut.add(new VariableRef(String.valueOf(lit.value()), aWhere, true));
                }
                else
                {
                    walk(arg, aWhere, aOut);
                }
            }
            for (Expr kw : call.kwargs().values())
            {
                walk(kw, aWhere, aOut);
            }
        }
        case Expr.Binary b ->
        {
            walk(b.left(), aWhere, aOut);
            walk(b.right(), aWhere, aOut);
        }
        case Expr.And and -> and.parts().forEach(p -> walk(p, aWhere, aOut));
        case Expr.Or or -> or.parts().forEach(p -> walk(p, aWhere, aOut));
        case Expr.Not not -> walk(not.inner(), aWhere, aOut);
        case Expr.Lit _ ->
        {
            // a literal names nothing
        }
        }
    }


    /**
     * Whether the {@code i}-th positional argument of a call is a column name: the
     * {@code var_exists} family's target, or any parameter the registry types as a column
     * reference.
     */
    private static boolean namesAColumn(String aFunction, @Nullable FunctionDescriptor aDescriptor,
            int aIndex)
    {
        if (aIndex == 0 && NAME_LITERAL_CALLS.contains(aFunction))
        {
            return true;
        }
        if (aDescriptor == null || aIndex >= aDescriptor.parameters().size())
        {
            return false;
        }
        Parameter p = aDescriptor.parameters().get(aIndex);
        return p.type() == ExprType.Primitive.COLUMN_REFERENCE;
    }

    // ---- scope universe -------------------------------------------------------------------

    /**
     * One dataset of a standard, judged against the rule's {@code Scope}.
     *
     * @param productKey
     *            the store product key ({@code standards/sdtmig/3-4})
     * @param dataset
     *            the dataset name
     * @param datasetClass
     *            the observation class the standard files it under (SDTM / SEND), or the ADaM data
     *            structure's class name, or {@code null}
     * @param dataStructure
     *            the ADaM data structure (ADaM products only), or {@code null}
     * @param inScope
     *            whether every judged axis admits the dataset
     * @param mismatch
     *            the first failing axis's description, or {@code null}
     */
    public record ScopeEntry(String productKey, String dataset, @Nullable String datasetClass,
            @Nullable String dataStructure, boolean inScope, @Nullable String mismatch)
    {
    }


    /**
     * The union over the rule's standards of the datasets its {@code Scope} admits, and the
     * variable names those datasets and their classes declare.
     *
     * @param entries
     *            every dataset of every resolved product, judged
     * @param variableNames
     *            the distinct variable names (first occurrence first) of the in-scope datasets,
     *            their classes' model variables ({@code --} forms kept) and, for ADaM, their
     *            structures' variable sets
     * @param missingProducts
     *            the product keys the store does not hold
     */
    public record ScopeUniverse(List<ScopeEntry> entries, List<String> variableNames,
            List<String> missingProducts)
    {

        public ScopeUniverse
        {
            entries = List.copyOf(entries);
            // distinct, first occurrence wins, immutable
            variableNames = List.copyOf(new LinkedHashSet<>(variableNames));
            missingProducts = List.copyOf(missingProducts);
        }


        /** The in-scope dataset names, distinct, in product then standard order. */
        public SequencedSet<String> inScopeDatasets()
        {
            SequencedSet<String> out = new LinkedHashSet<>();
            for (ScopeEntry e : entries)
            {
                if (e.inScope())
                {
                    out.add(e.dataset());
                }
            }
            return out;
        }
    }

    /**
     * The store product keys of a rule's authored standards ({@code (Name, Version)} pairs).
     *
     * @param aStandards
     *            the pairs ({@link AuthoredRuleAssembler#standardsOf})
     * @return the keys, distinct, in order
     */
    public static List<String> productKeysOf(
            Collection<AuthoredRuleAssembler.StandardVersion> aStandards)
    {
        SequencedSet<String> out = new LinkedHashSet<>();
        for (AuthoredRuleAssembler.StandardVersion sv : aStandards)
        {
            // An SDTM/SEND-family standard maps through the store's D-11 family grammar
            // (SENDIG-AR 1.0 -> standards/sendig/ar-1-0, never standards/sendig-ar/1-0, which
            // names no product: PLAN-bugfix-engine-define SV, pre-go H2 — every AR / DART / MD
            // rule's universe was empty). Store keys are lower-case (standards/sdtmig/3-4); the
            // authored Name is display-cased, so the ADaM fallback folds it.
            out.add(MetadataProductKeys.igStoreKey(sv.name(), sv.version())
                    .orElseGet(() -> MetadataProductKeys
                            .standardsKey(sv.name().toLowerCase(Locale.ROOT), sv.version())));
        }
        return List.copyOf(out);
    }


    /**
     * Judge every dataset of the given products against the rule's {@code Scope}, axis by axis in
     * the production order: Domains, Datasets, Classes, Data_Structures, Subclasses
     * ({@code DatasetRuleResolver.describeScopeSkip}). A dataset the store knows no class or
     * structure for is judged strictly, as production judges an undetermined class.
     *
     * @param aRule
     *            a loaded rule
     * @param aStore
     *            the metadata store
     * @param aProductKeys
     *            the products to iterate ({@link #productKeysOf})
     * @return the universe
     */
    public static ScopeUniverse scopeUniverse(Rule aRule, MetadataStore aStore,
            Collection<String> aProductKeys)
    {
        List<ScopeEntry> entries = new ArrayList<>();
        SequencedSet<String> variables = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String key : aProductKeys)
        {
            // the base-IG view: an AR / DART product plus the base SENDIG datasets it does not
            // print, as the data and define sides read it (BaseIgView; the model read in modelOf
            // stays raw — models are never composed)
            Optional<StoredProduct> product = BaseIgView.productOf(aStore, key);
            if (product.isEmpty())
            {
                missing.add(key);
                continue;
            }
            judgeProduct(aRule, product.get(), modelOf(aStore, product.get()), entries, variables);
        }
        return new ScopeUniverse(entries, new ArrayList<>(variables), missing);
    }


    /**
     * The model an IG is built on ({@code modelHref} {@code /mdr/sdtm/2-0} is store key
     * {@code models/sdtm/2-0}): the class-level variables with their {@code --} forms live there,
     * not in the IG.
     */
    private static @Nullable StoredProduct modelOf(MetadataStore aStore, StoredProduct aProduct)
    {
        String href = aProduct.modelHref();
        if (href == null || href.isBlank())
        {
            return null;
        }
        // /mdr/sdtm/2-0 -> models/sdtm/2-0 (the store's product key of a model)
        String key = href.startsWith("/mdr/") ? "models/" + href.substring("/mdr/".length()) : href;
        return aStore.product(key).orElse(null);
    }

    /** The SDTM general-observation classes, which share the General Observations variables. */
    private static final Set<String> GENERAL_OBSERVATION_CLASSES = Set.of("INTERVENTIONS", "EVENTS",
            "FINDINGS", "FINDINGS ABOUT");

    private static void judgeProduct(Rule aRule, StoredProduct aProduct,
            @Nullable StoredProduct aModel, List<ScopeEntry> aEntries,
            SequencedSet<String> aVariables)
    {
        // An IG files its datasets under its classes (the product-level list is empty for
        // SDTMIG / SENDIG); a product that lists datasets at the top carries no class. Both are
        // walked, first occurrence of a name wins.
        Map<String, StoredDataset> datasets = new java.util.LinkedHashMap<>();
        Map<String, StoredClass> owners = new java.util.HashMap<>();
        for (StoredDataset ds : aProduct.datasets())
        {
            if (ds.name() != null && !ds.name().isBlank())
            {
                datasets.putIfAbsent(ds.name(), ds);
            }
        }
        for (StoredClass c : aProduct.classes())
        {
            for (StoredDataset ds : c.datasets())
            {
                if (ds.name() != null && !ds.name().isBlank())
                {
                    datasets.putIfAbsent(ds.name(), ds);
                    owners.putIfAbsent(ds.name(), c);
                }
            }
        }
        for (Map.Entry<String, StoredDataset> entry : datasets.entrySet())
        {
            String name = entry.getKey();
            StoredDataset ds = entry.getValue();
            StoredClass owner = owners.get(name);
            StoredDataStructure structure = structureOf(aProduct, ds.datasetStructure());
            String className = owner != null ? owner.name()
                    : structure != null ? structure.className() : null;
            String structureName = ds.datasetStructure() != null ? ds.datasetStructure()
                    : structure != null ? structure.name() : null;
            String mismatch = judge(aRule, name, className, structureName,
                    structure == null || structure.subClass() == null ? List.of()
                            : List.of(structure.subClass()));
            aEntries.add(new ScopeEntry(aProduct.key(), name, className, structureName,
                    mismatch == null, mismatch));
            if (mismatch == null)
            {
                collectVariables(ds, owner, structure, aVariables);
                if (owner != null && aModel != null && owner.name() != null)
                {
                    collectModelVariables(aModel, owner.name(), aVariables);
                }
            }
        }
        // An ADaM IG names no datasets, only data structures (ADSL, BDS, OCCDS, ...): each
        // structure is the unit a rule's Scope can admit, judged on the ADaM axes.
        if (datasets.isEmpty())
        {
            for (StoredDataStructure s : aProduct.dataStructures())
            {
                if (s.name() == null || s.name().isBlank())
                {
                    continue;
                }
                // The structure token the scope axes judge (BASIC DATA STRUCTURE, ...): ADaMIG 1.3
                // spells it in className, the older products spell a short label there (BDS,
                // OCCDS, ADAE) and adam-tte-1-0 publishes none at all — read through the engine's
                // own mapping (aliases + per-product overrides), the one MetadataLibraryProvider
                // resolves a declared product with (review 8a-A F7: this used to be a parallel
                // table here); the name stays the label an author knows the structure by.
                String token = MetadataLibraryProvider.structureTokenOf(aProduct.key(), s);
                if (token == null)
                {
                    token = s.className();
                }
                String sub = MetadataLibraryProvider.subClassOf(aProduct.key(), s);
                String mismatch = judge(aRule, s.name(), token, token,
                        sub == null ? List.of() : List.of(sub));
                aEntries.add(new ScopeEntry(aProduct.key(), s.name(), token, token,
                        mismatch == null, mismatch));
                if (mismatch == null)
                {
                    for (StoredVariableSet set : s.variableSets())
                    {
                        for (StoredVariable v : set.variables())
                        {
                            addName(v, aVariables);
                        }
                    }
                }
            }
        }
    }


    /**
     * The scope axes production judges a dataset on, in its order: domain, dataset, class, data
     * structure (with its supertypes — a medical-device BDS is a BDS, exactly as
     * {@code AdamDataStructureDetector.structureSet} feeds {@code RuleRunner}), subclass.
     *
     * <p>
     * ⚠ The {@code Use_Case} axis is deliberately not judged: it filters by the <em>run's</em> use
     * case, which a universe over a standard's datasets has none of — every dataset would be in or
     * out together, whatever the rule says about it.
     * </p>
     */
    private static @Nullable String judge(Rule aRule, String aName, @Nullable String aClassName,
            @Nullable String aStructure, List<String> aSubclasses)
    {
        String mismatch = ScopeMatcher.describeDomainMismatch(aRule, aName, aName);
        if (mismatch != null)
        {
            return mismatch;
        }
        mismatch = ScopeMatcher.describeDatasetMismatch(aRule, aName);
        if (mismatch != null)
        {
            return mismatch;
        }
        // An ADaM Classes scope names structure tokens (ADaM has no observation class): judge it
        // from the structure when the standard files the dataset under none.
        mismatch = ScopeMatcher.describeClassMismatch(aRule,
                aClassName != null ? aClassName : aStructure);
        if (mismatch != null)
        {
            return mismatch;
        }
        mismatch = ScopeMatcher.describeDataStructureMismatch(aRule, aStructure == null ? List.of()
                : AdamDataStructureDetector.structureSet(aStructure));
        if (mismatch != null)
        {
            return mismatch;
        }
        return ScopeMatcher.describeSubclassMismatch(aRule, aSubclasses);
    }


    private static @Nullable StoredDataStructure structureOf(StoredProduct aProduct,
            @Nullable String aStructureName)
    {
        if (aStructureName == null)
        {
            return null;
        }
        for (StoredDataStructure s : aProduct.dataStructures())
        {
            if (aStructureName.equalsIgnoreCase(s.name()))
            {
                return s;
            }
        }
        return null;
    }


    /**
     * The model's class-level variables for a class ({@code --TERM}, {@code --STDTC}, …) and, for a
     * general-observation class, the shared General Observations variables (identifiers and
     * timing).
     */
    private static void collectModelVariables(StoredProduct aModel, String aClassName,
            SequencedSet<String> aOut)
    {
        String wanted = aClassName.toUpperCase(Locale.ROOT);
        boolean general = GENERAL_OBSERVATION_CLASSES.contains(wanted);
        for (StoredClass c : aModel.classes())
        {
            String name = c.name() == null ? "" : c.name().toUpperCase(Locale.ROOT);
            if (name.equals(wanted) || (general && "GENERAL OBSERVATIONS".equals(name)))
            {
                for (StoredVariable v : c.classVariables())
                {
                    addName(v, aOut);
                }
            }
        }
    }


    private static void collectVariables(StoredDataset aDataset, @Nullable StoredClass aClass,
            @Nullable StoredDataStructure aStructure, SequencedSet<String> aOut)
    {
        for (StoredVariable v : aDataset.variables())
        {
            addName(v, aOut);
        }
        if (aClass != null)
        {
            for (StoredVariable v : aClass.classVariables())
            {
                addName(v, aOut);
            }
        }
        if (aStructure != null)
        {
            for (StoredVariableSet set : aStructure.variableSets())
            {
                for (StoredVariable v : set.variables())
                {
                    addName(v, aOut);
                }
            }
        }
    }


    private static void addName(StoredVariable aVariable, SequencedSet<String> aOut)
    {
        if (aVariable.name() != null && !aVariable.name().isBlank())
        {
            aOut.add(aVariable.name());
        }
    }

    // ---- related rules --------------------------------------------------------------------

    /**
     * A rule related to the one asked about.
     *
     * @param coreId
     *            the related rule's {@code Core.Id}
     * @param relation
     *            {@code shared-reference}, {@code equivalence}, {@code related},
     *            {@code minted-from} or {@code minted-sister}
     * @param via
     *            what established it: the reference ({@code Origin Id}), the register class id, the
     *            parentage reason
     */
    public record RelatedRule(String coreId, String relation, String via)
    {
    }

    /**
     * The rules related to {@code aCoreId}: those sharing one of its authority references
     * {@code (Origin, Rule_Identifier.Id)} across families, the members of its equivalence and
     * relation classes where the index carries the register, and its minted parent / sisters.
     *
     * @param aCoreId
     *            the rule
     * @param aReferences
     *            the rule's references ({@link CorpusIndex#referencesOf})
     * @param aIndex
     *            the corpus index
     * @return the related rules, self excluded — distinct as {@link RelatedRule} records, i.e. by
     *         (id, relation, via): a rule sharing two references with this one is listed once per
     *         reference, each naming what established it
     */
    public static List<RelatedRule> related(String aCoreId,
            Collection<CorpusIndex.ReferenceKey> aReferences, CorpusIndex aIndex)
    {
        SequencedSet<RelatedRule> out = new LinkedHashSet<>();
        for (CorpusIndex.ReferenceKey ref : aReferences)
        {
            for (String other : aIndex.rulesReferencing(ref))
            {
                if (!other.equals(aCoreId))
                {
                    out.add(new RelatedRule(other, "shared-reference", ref.toString()));
                }
            }
        }
        for (CorpusIndex.RegisterClass cls : aIndex.classesOf(aCoreId))
        {
            for (String member : cls.members())
            {
                if (!member.equals(aCoreId))
                {
                    out.add(new RelatedRule(member, cls.equivalence() ? "equivalence" : "related",
                            cls.id() + (cls.kind() == null ? "" : " " + cls.kind())));
                }
            }
        }
        for (CorpusIndex.Parentage p : aIndex.parentageOf(aCoreId))
        {
            if (p.child().equals(aCoreId))
            {
                out.add(new RelatedRule(p.parent(), "minted-from", p.reason()));
            }
            else
            {
                out.add(new RelatedRule(p.child(), "minted-sister", p.reason()));
            }
        }
        return List.copyOf(out);
    }
}
