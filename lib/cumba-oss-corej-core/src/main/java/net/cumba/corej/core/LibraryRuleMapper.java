package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.rules.RuleAuthority;
import net.cumba.cdisc.library.api.model.rules.RuleCitation;
import net.cumba.cdisc.library.api.model.rules.RuleCondition;
import net.cumba.cdisc.library.api.model.rules.RuleMap;
import net.cumba.cdisc.library.api.model.rules.RuleMatchDataset;
import net.cumba.cdisc.library.api.model.rules.RuleOperation;
import net.cumba.cdisc.library.api.model.rules.RuleOutcome;
import net.cumba.cdisc.library.api.model.rules.RuleReference;
import net.cumba.cdisc.library.api.model.rules.RuleScope;
import net.cumba.cdisc.library.api.model.rules.RuleScopeFilter;
import net.cumba.cdisc.library.api.model.rules.RuleStandard;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.AuthorityStandard;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.Citation;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Reference;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.SubclassScope;
import net.cumba.corej.core.model.VariableUniverse;
import net.cumba.web.api.ApiResource;
import org.jspecify.annotations.Nullable;

/**
 * Maps from the proxy-based library model ({@link net.cumba.cdisc.library.api.model.rules.Rule}) to
 * the POJO-based core execution model ({@link net.cumba.corej.core.model.Rule}).
 */
final class LibraryRuleMapper
{

    /**
     * The one retired {@code Rule_Type} value that names a variable universe rather than a shape.
     */
    static final String DEFINE_ITEM_RULE_TYPE = "Define Item Metadata Check against Library Metadata";

    private static final String FIELD_VALUE = "value";

    private LibraryRuleMapper()
    {
    }

    // --- Top-level entry points ---


    public static @Nullable RulePackage mapRulePackage(
            net.cumba.cdisc.library.api.model.rules.RulePackage src)
    {
        if (src == null)
        {
            return null;
        }
        RulePackage target = new RulePackage();
        src.rules().ifPresent(ruleMap -> target.setRules(mapRuleMap(ruleMap)));
        // Fix #159 — the CDISC-Library path must park a rule exactly as the file loader does.
        // This mapper deliberately does NOT call finishLoad (it hand-picks the passes that make
        // sense without normalizeOperations), so the skip has to be invoked explicitly here or a
        // library-sourced `Executability: "Not Executable"` rule would load and run — the one
        // bypass the loader-side guard exists for. ⚠ The upstream Library corpus is not this
        // repo's corpus and is not under this project's control, so "zero rules declare it today"
        // is a fact about rules-src/, not about what this method can be handed.
        RulePackageLoader.removeParkedRules(target);
        return target;
    }


    /**
     * Maps one library rule. ⚠ <b>This primitive does not park.</b> Parking removes a rule from a
     * <em>package</em>, and a single mapped {@link Rule} has no package to be removed from —
     * returning {@code null} here would be a silent surprise for a caller that asked to map a
     * specific rule. {@link #mapRulePackage} is where {@code Executability: "Not Executable"} takes
     * effect on this path; the only production consumer ({@code CoreLibraryAccessImpl.loadRules})
     * goes through it.
     */
    public static @Nullable Rule mapRule(net.cumba.cdisc.library.api.model.rules.Rule src)
    {
        if (src == null)
        {
            return null;
        }
        Rule target = new Rule();
        target.setId(src.id().orElse(null));
        target.setCore(src.core().map(LibraryRuleMapper::mapRuleCore).orElse(null));
        target.setDescription(src.description().orElse(null));
        // Raw-string binding (mirrors the Jackson @JsonSetter path): keeps the verbatim source
        // value so present-but-unrecognized strings fail loud below, exactly like loader-loaded
        // rules. Rule_Type: the CDISC Library payload carries it on EVERY rule (measured
        // 2026-08-22: 1 995 / 1 995 across the six cached standards), so the ruling-6 rejection
        // that applies to an authored package would load nothing from the API. The mapper is a
        // translator, not an authored corpus: it carries the one bit the taxonomy held that the
        // engine cannot infer (`Define Item Metadata Check against Library Metadata` = iterate
        // the Define-XML ItemDefs) into Variable_Universe: Define and drops the rest — the
        // engine infers the evaluation domain from the Check (PLAN-leaf-scope-domain-inference).
        src.ruleType().ifPresent(type -> target.setVariableUniverse(
                DEFINE_ITEM_RULE_TYPE.equals(type) ? VariableUniverse.DEFINE : null));
        // ⚑ Plan C: there is deliberately NO Severity mapping here. The CDISC Library rule DTO
        // (net.cumba.cdisc.library.api.model.rules.Rule) publishes id / description / ruleType /
        // sensitivity / executability and NO severity — the Library API does not grade its rules,
        // and PMDA is the only publisher of the nine that does. A Library-sourced rule therefore
        // carries no authored Severity and takes the absent-field default, ERROR, via
        // Rule.effectiveSeverity(). validateEnumFields (which this mapper calls) already reaches
        // the new gate; with a null raw value it is correctly a no-op.
        target.setSensitivityJson(src.sensitivity().orElse(null));
        target.setExecutabilityJson(src.executability().orElse(null));
        target.setAuthorities(mapList(src.authorities(), LibraryRuleMapper::mapAuthority));
        target.setScope(src.scope().map(LibraryRuleMapper::mapScope).orElse(null));
        target.setCheck(src.check().map(LibraryRuleMapper::mapCondition).orElse(null));
        target.setOutcome(src.outcome().map(LibraryRuleMapper::mapOutcome).orElse(null));
        target.setOperations(mapList(src.operations(), LibraryRuleMapper::mapOperation));
        target.setMatchDatasets(mapList(src.matchDatasets(), LibraryRuleMapper::mapMatchDataset));
        target.setGroupingVariables(src.groupingVariables());
        // Phase 2 (PLAN-extend-expression-engine): a present-but-unrecognized
        // Sensitivity / Executability (and any Rule_Type at all) tags the same loadError the
        // loader path sets, so
        // library-sourced rules surface as ERROR identically.
        RulePackageLoader.deriveOmittedFields(target);
        RulePackageLoader.validateEnumFields(target);
        // Same contract for a Check operand no Operations entry defines
        // (PLAN-dangling-operation-reference-load-check): a library-sourced rule fails that way
        // just as silently as a file-loaded one. Safe without normalizeOperations, which this
        // mapper does not run: the Library operation model is field-form only (mapOperation binds
        // `id` directly — there is no `expression` to normalise), so every defined id is already
        // visible here.
        RulePackageLoader.validateOperationReferences(target);
        // D13 item 3 — same loadError contract for a dictionary operation naming no
        // external_dictionary_type: unanswerable by any install, so an authoring defect. The type
        // is bound by mapOperation (RuleOperation.externalDictionaryType), so a typed
        // library-sourced dictionary rule passes this untouched.
        RulePackageLoader.validateDictionaryOperationTypes(target);
        // EC-37: same completion contract — a library-sourced rule gets the effective
        // Output_Variables a loader-loaded rule would carry (legacy-tree fallback here, since
        // this mapper installs no native checkExpr).
        RulePackageLoader.deriveOutputVariables(target);
        return target;
    }

    // --- Sub-object mappers ---


    private static Map<String, Rule> mapRuleMap(RuleMap ruleMap)
    {
        Map<String, Rule> result = new LinkedHashMap<>();
        for (String key : ruleMap.keys())
        {
            ruleMap.get(key).ifPresent(r -> result.put(key, mapRule(r)));
        }
        return result;
    }


    private static net.cumba.corej.core.model.RuleCore mapRuleCore(
            net.cumba.cdisc.library.api.model.rules.RuleCore src)
    {
        net.cumba.corej.core.model.RuleCore target = new net.cumba.corej.core.model.RuleCore();
        target.setId(src.id().orElse(null));
        target.setStatus(src.status().orElse(null));
        target.setVersion(src.version().orElse(null));
        return target;
    }


    private static Authority mapAuthority(RuleAuthority src)
    {
        Authority target = new Authority();
        target.setOrganization(src.organization().orElse(null));
        target.setStandards(mapList(src.standards(), LibraryRuleMapper::mapAuthorityStandard));
        return target;
    }


    private static AuthorityStandard mapAuthorityStandard(RuleStandard src)
    {
        AuthorityStandard target = new AuthorityStandard();
        target.setName(src.name().orElse(null));
        target.setVersion(src.version().orElse(null));
        target.setSubstandard(src.substandard().orElse(null));
        target.setReferences(mapList(src.references(), LibraryRuleMapper::mapReference));
        return target;
    }


    private static Reference mapReference(RuleReference src)
    {
        Reference target = new Reference();
        target.setOrigin(src.origin().orElse(null));
        target.setVersion(src.version().orElse(null));
        target.setRuleIdentifier(
                src.ruleIdentifier().map(LibraryRuleMapper::mapRuleIdentifier).orElse(null));
        target.setCitations(mapList(src.citations(), LibraryRuleMapper::mapCitation));
        return target;
    }


    private static net.cumba.corej.core.model.RuleIdentifier mapRuleIdentifier(
            net.cumba.cdisc.library.api.model.rules.RuleIdentifier src)
    {
        net.cumba.corej.core.model.RuleIdentifier target = new net.cumba.corej.core.model.RuleIdentifier();
        target.setId(src.id().orElse(null));
        target.setVersion(src.version().orElse(null));
        return target;
    }


    private static Citation mapCitation(RuleCitation src)
    {
        Citation target = new Citation();
        target.setCitedGuidance(src.citedGuidance().orElse(null));
        target.setDocument(src.document().orElse(null));
        target.setItem(src.item().orElse(null));
        target.setSection(src.section().orElse(null));
        return target;
    }


    private static Scope mapScope(RuleScope src)
    {
        Scope target = new Scope();
        target.setClasses(src.classes().map(LibraryRuleMapper::mapClassScope).orElse(null));
        target.setDomains(src.domains().map(LibraryRuleMapper::mapDomainScope).orElse(null));
        target.setUseCase(src.useCase().orElse(null));
        // Fix #117/#118 (review finding 6): CDISC-Library-sourced rules are the one population
        // that actually authors "Data Structures"/"Subclasses" upstream — dropping them here
        // would silently over-run those rules in Java while the Python engine gates them.
        target.setDataStructures(
                src.dataStructures().map(LibraryRuleMapper::mapDataStructureScope).orElse(null));
        target.setSubclasses(
                src.subclasses().map(LibraryRuleMapper::mapSubclassScope).orElse(null));
        return target;
    }


    private static DataStructureScope mapDataStructureScope(RuleScopeFilter src)
    {
        DataStructureScope target = new DataStructureScope();
        target.setInclude(src.include());
        target.setExclude(src.exclude());
        return target;
    }


    private static SubclassScope mapSubclassScope(RuleScopeFilter src)
    {
        SubclassScope target = new SubclassScope();
        target.setInclude(src.include());
        target.setExclude(src.exclude());
        return target;
    }


    private static ClassScope mapClassScope(RuleScopeFilter src)
    {
        ClassScope target = new ClassScope();
        target.setInclude(src.include());
        target.setExclude(src.exclude());
        return target;
    }


    private static DomainScope mapDomainScope(RuleScopeFilter src)
    {
        DomainScope target = new DomainScope();
        target.setInclude(src.include());
        target.setExclude(src.exclude());
        target.setIncludeSplitDatasets(src.includeSplitDatasets().orElse(null));
        return target;
    }


    private static Outcome mapOutcome(RuleOutcome src)
    {
        Outcome target = new Outcome();
        target.setMessage(src.message().orElse(null));
        target.setOutputVariables(src.outputVariables());
        return target;
    }


    private static Operation mapOperation(RuleOperation src)
    {
        Operation target = new Operation();
        target.setId(src.id().orElse(null));
        target.setOperator(src.operator().orElse(null));
        target.setName(src.name().orElse(null));
        target.setDomain(src.domain().orElse(null));
        target.setGroup(src.group());
        target.setFilter(mapFilter(src.filter().orElse(null)));
        target.setCodelists(src.codelists());
        target.setLevel(src.level().orElse(null));
        target.setReturntype(src.returntype().orElse(null));
        target.setKeyName(src.keyName().orElse(null));
        target.setKeyValue(src.keyValue().orElse(null));
        target.setCtAttribute(src.ctAttribute().orElse(null));
        target.setVersion(src.version().orElse(null));
        target.setCtPackageTypes(src.ctPackageTypes());
        target.setRegex(src.regex().orElse(null));
        target.setValueIsReference(src.valueIsReference().orElse(null));
        // D13 item 3 — without this binding every library-sourced dictionary operation looked
        // typeless, which validateDictionaryOperationTypes below now treats as a load error.
        target.setExternalDictionaryType(src.externalDictionaryType().orElse(null));
        return target;
    }


    private static @Nullable Map<String, Object> mapFilter(@Nullable ApiResource filterResource)
    {
        if (filterResource == null)
        {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : filterResource.getFieldNames())
        {
            filterResource.getString(key).ifPresent(v -> result.put(key, v));
        }
        return result;
    }


    private static MatchDataset mapMatchDataset(RuleMatchDataset src)
    {
        MatchDataset target = new MatchDataset();
        target.setName(src.name().orElse(null));
        target.setKeys(src.keys());
        target.setWildcard(src.wildcard().orElse(null));
        target.setChild(src.child().orElse(null));
        target.setJoinType(src.joinType().orElse(null));
        return target;
    }

    // --- CheckCondition mapping ---


    private static CheckCondition mapCondition(RuleCondition src)
    {
        // Composite: all
        List<RuleCondition> allChildren = src.all();
        if (!allChildren.isEmpty())
        {
            return new CheckConditionAll(mapList(allChildren, LibraryRuleMapper::mapCondition));
        }
        // Composite: any
        List<RuleCondition> anyChildren = src.any();
        if (!anyChildren.isEmpty())
        {
            return new CheckConditionAny(mapList(anyChildren, LibraryRuleMapper::mapCondition));
        }
        // Composite: not
        if (src.not().isPresent())
        {
            return new CheckConditionNot(mapCondition(src.not().orElseThrow()));
        }
        // Leaf
        return mapLeaf(src);
    }


    private static CheckConditionLeaf mapLeaf(RuleCondition src)
    {
        return CheckConditionLeaf.builder().name(src.name().orElse(null))
                .operator(src.operator().orElse(null)).value(mapLeafValue(src))
                .valueIsLiteral(src.valueIsLiteral().orElse(null))
                .valueIsReference(src.valueIsReference().orElse(null))
                .typeInsensitive(src.typeInsensitive().orElse(null))
                .negative(src.negative().orElse(null)).regex(src.regex().orElse(null))
                .prefix(src.prefixInt().isPresent() ? src.prefixInt().getAsInt() : null)
                .suffix(src.suffixInt().isPresent() ? src.suffixInt().getAsInt() : null)
                .within(src.within().<JsonNode> map(TextNode::valueOf).orElse(null))
                .ordering(src.ordering().orElse(null)).build();
    }


    /**
     * Reconstructs a {@link JsonNode} for the leaf condition's {@code value} field from the typed
     * proxy accessors.
     */
    private static @Nullable JsonNode mapLeafValue(RuleCondition src)
    {
        // Number (check before string — a number field won't match getString)
        if (src.valueNumber().isPresent())
        {
            Number n = src.valueNumber().orElseThrow();
            if (n instanceof Integer || n instanceof Long
                    || (n instanceof Double d && d == Math.floor(d) && !Double.isInfinite(d)))
            {
                return LongNode.valueOf(n.longValue());
            }
            return DoubleNode.valueOf(n.doubleValue());
        }
        // Boolean
        if (src.isBoolean(FIELD_VALUE))
        {
            return src.getBoolean(FIELD_VALUE).map(BooleanNode::valueOf).orElse(null);
        }
        // Array
        if (src.isArray(FIELD_VALUE))
        {
            List<String> items = src.valueList();
            ArrayNode arr = JsonNodeFactory.instance.arrayNode(items.size());
            items.forEach(arr::add);
            return arr;
        }
        // String (column reference or literal)
        if (src.valueString().isPresent())
        {
            return TextNode.valueOf(src.valueString().orElseThrow());
        }
        return null;
    }

    // --- Utility ---


    private static <S, T> List<T> mapList(List<S> source, java.util.function.Function<S, T> mapper)
    {
        if (source == null || source.isEmpty())
        {
            return List.of();
        }
        List<T> result = new ArrayList<>(source.size());
        for (S item : source)
        {
            result.add(mapper.apply(item));
        }
        return result;
    }

}
