package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.StandardRef;
import org.junit.jupiter.api.Test;

/**
 * Every bound key of every rule-model class names its production reader, and the pinned rosters
 * ({@link BoundRuleKeys}) equal what Jackson's <b>built</b> deserializers bind
 * ({@code PLAN-rule-unknown-keys-gate} &#167;7.2).
 *
 * <p>
 * ⭐ Why: the unknown-key gate accepts exactly what the model binds, so a key bound with no reader
 * is the {@code Match_Datasets.Wildcard} defect again — authored, accepted, doing nothing. Equality
 * (not a subset) means a key bound or unbound on any class reds until the roster is edited and its
 * reader named here; and because the loader's hints read the same {@link BoundRuleKeys} sets, the
 * hint cannot drift from the model either.
 * </p>
 *
 * <p>
 * ⚠ Asked of the built deserializer — setter, field, creator parameter and setterless
 * getter-as-setter properties, plus {@code @JsonAlias} spellings — not of the annotations and not
 * of an introspection filter: the former misses an unannotated Lombok field and every alias, the
 * latter a getter-bound {@code Collection} under {@code USE_GETTERS_AS_SETTERS}
 * ({@code MatchDatasetBoundKeysRosterTest}, review rounds 1 and 2 of the wildcard plan).
 * </p>
 */
class BoundKeysRosterTest
{

    /** Marker for a key with no production reader, followed by why that is legitimate. */
    private static final String NO_READER = "no engine reader: ";

    private static final String LOADER = "net.cumba.corej.core.RulePackageLoader";

    private static final String SCOPE_MATCHER = "net.cumba.corej.core.exec.ScopeMatcher";

    private static final String REPORT_ASSEMBLER = "net.cumba.corej.core.report.ReportAssembler";

    private static final String TOKEN_EXPANDER = "net.cumba.corej.core.gen.TokenExpander";

    private static final String WILDCARD_EXPANDER = "net.cumba.corej.core.gen.WildcardExpander";

    private static final String CLASSIFIER = "net.cumba.corej.core.exec.StudyRuleClassifier";

    /**
     * Bound JSON key → the production class that reads it, per model class. A {@link #NO_READER}
     * entry is a key the engine binds but never reads: serialised back out by the corpus generator
     * (the model is also the package writer's shape) or read by the report / tooling only. Each
     * says why, in a place a reviewer sees.
     */
    private static final Map<Class<?>, Map<String, String>> READERS = Map.ofEntries(
            Map.entry(net.cumba.corej.core.model.RulePackage.class,
                    Map.of("rules", LOADER, "standards",
                            "net.cumba.corej.core.run.StudyValidationService")),
            Map.entry(StandardRef.class,
                    Map.of("id", "net.cumba.corej.core.run.RunStandard", "role",
                            "net.cumba.corej.core.run.RunStandard")),
            Map.entry(Rule.class, Map.ofEntries(
                    Map.entry("id", "provenance: the loader's / the rulespec harness's"
                            + " synthetic identity (Rule.effectiveId); file-loaded rules carry none"),
                    Map.entry("Core", LOADER),
                    Map.entry("Description", "net.cumba.corej.core.exec.OperationExecutor"),
                    Map.entry("ExecutabilityHint", LOADER),
                    Map.entry("Authorities", REPORT_ASSEMBLER), Map.entry("Scope", SCOPE_MATCHER),
                    Map.entry("Requirements", LOADER),
                    Map.entry("Outcome", "net.cumba.corej.core.exec.RuleRunner"),
                    Map.entry("Bindings", LOADER),
                    Map.entry("Match_Datasets", "net.cumba.corej.core.exec.KeyMatchRowExpander"),
                    Map.entry("Grouping_Variables", "net.cumba.corej.core.exec.RuleSpecialiser"),
                    Map.entry("Grouping", "net.cumba.corej.core.exec.RuleSpecialiser"),
                    Map.entry("Precondition", "net.cumba.corej.core.exec.RuleRunner"),
                    Map.entry("Expansion", TOKEN_EXPANDER),
                    Map.entry("skipIfLibraryDefined",
                            "net.cumba.corej.core.exec.DatasetRuleResolver"),
                    Map.entry("wildcards", WILDCARD_EXPANDER),
                    Map.entry("wildcardExclude", WILDCARD_EXPANDER),
                    Map.entry("wildcardPairCatalogue", WILDCARD_EXPANDER),
                    Map.entry("Check", "net.cumba.corej.core.exec.RuleRunner"),
                    Map.entry("Rule_Type",
                            "retired: bound only to be rejected by value"
                                    + " (RulePackageLoader.ruleTypeRejection)"),
                    Map.entry("Sensitivity", "net.cumba.corej.core.exec.RuleRunner"),
                    Map.entry("Severity", "net.cumba.corej.core.exec.RuleRunner"),
                    Map.entry("Executability", LOADER), Map.entry("Variable_Universe", LOADER),
                    Map.entry("Operations",
                            "retired: bound only to throw"
                                    + " (Rule.rejectRetiredOperationsKey)"))),
            Map.entry(net.cumba.corej.core.model.RuleCore.class,
                    Map.of("Id", LOADER, "Status", "net.cumba.corej.core.report.LibraryValidator",
                            "Version",
                            NO_READER + "serialised by the corpus generator; the report reads"
                                    + " Authorities")),
            Map.entry(net.cumba.corej.core.model.Outcome.class,
                    Map.of("Message", "net.cumba.corej.core.exec.RuleRunner", "Output_Variables",
                            "net.cumba.corej.core.exec.OutputVariableDeriver")),
            Map.entry(net.cumba.corej.core.model.ExecutabilityHint.class,
                    Map.of("Category", LOADER, "Detail", LOADER)),
            Map.entry(net.cumba.corej.core.model.Authority.class,
                    Map.of("Organization", REPORT_ASSEMBLER, "Standards", REPORT_ASSEMBLER,
                            "Rule_Ids", REPORT_ASSEMBLER)),
            Map.entry(net.cumba.corej.core.model.AuthorityStandard.class,
                    Map.of("Name",
                            NO_READER + "release shape collapses Authorities to"
                                    + " {Organization, Rule_Ids}; authored corpus only",
                            "Version", NO_READER + "same", "Substandard", NO_READER + "same",
                            "References", REPORT_ASSEMBLER)),
            Map.entry(net.cumba.corej.core.model.Reference.class,
                    Map.of("Origin", NO_READER + "authored corpus only (release shape)", "Version",
                            NO_READER + "same", "Rule_Identifier", REPORT_ASSEMBLER, "Citations",
                            NO_READER + "same — the corpus docs tooling reads citations")),
            Map.entry(net.cumba.corej.core.model.RuleIdentifier.class,
                    Map.of("Id", REPORT_ASSEMBLER, "Version",
                            NO_READER + "authored corpus only (release shape)")),
            Map.entry(net.cumba.corej.core.model.Citation.class,
                    Map.of("Cited_Guidance", NO_READER + "rules-src/docs tooling", "Document",
                            NO_READER + "same", "Item", NO_READER + "same", "Section",
                            NO_READER + "same")),
            Map.entry(net.cumba.corej.core.model.Scope.class,
                    Map.of("Classes", SCOPE_MATCHER, "Domains", SCOPE_MATCHER, "Datasets",
                            SCOPE_MATCHER, "Use_Case", SCOPE_MATCHER, "Data_Structures", CLASSIFIER,
                            "Data Structures",
                            "alias of Data_Structures (the upstream CORE spelling)", "Subclasses",
                            CLASSIFIER)),
            Map.entry(net.cumba.corej.core.model.ClassScope.class,
                    Map.of("Include", SCOPE_MATCHER, "Exclude", SCOPE_MATCHER)),
            Map.entry(net.cumba.corej.core.model.DomainScope.class,
                    Map.of("Include", SCOPE_MATCHER, "Exclude", SCOPE_MATCHER,
                            "include_split_datasets", SCOPE_MATCHER)),
            Map.entry(net.cumba.corej.core.model.DatasetScope.class,
                    Map.of("Include", SCOPE_MATCHER, "Exclude", SCOPE_MATCHER)),
            Map.entry(net.cumba.corej.core.model.DataStructureScope.class,
                    Map.of("Include", CLASSIFIER, "Exclude", CLASSIFIER)),
            Map.entry(net.cumba.corej.core.model.SubclassScope.class,
                    Map.of("Include", CLASSIFIER, "Exclude", CLASSIFIER)),
            Map.entry(net.cumba.corej.core.model.Requirements.class,
                    Map.of("Variables", "net.cumba.corej.core.exec.RuleRunner", "Datasets",
                            "net.cumba.corej.core.exec.RuleRunner", "Library", LOADER, "Define",
                            LOADER, "Dictionary", LOADER)),
            Map.entry(net.cumba.corej.core.model.VariableRequirement.class,
                    Map.of("All", "net.cumba.corej.core.exec.RuleRunner", "Any",
                            "net.cumba.corej.core.exec.RuleRunner", "None",
                            "net.cumba.corej.core.exec.RuleRunner", "All_Or_None",
                            "net.cumba.corej.core.exec.RuleRunner")),
            Map.entry(net.cumba.corej.core.model.Binding.class,
                    Map.of("name", LOADER, "expression", LOADER)),
            Map.entry(MatchDataset.class,
                    Map.of("Name", "net.cumba.corej.core.exec.RelrecRowExpander", "Keys",
                            "net.cumba.corej.core.exec.KeyMatchRowExpander", "Child",
                            "net.cumba.corej.core.exec.ChildMatchPreMerger", "Join_Type",
                            "net.cumba.corej.core.exec.KeyMatchRowExpander", "Join_As_String",
                            "net.cumba.corej.core.exec.KeyMatchRowExpander", "keep_missings",
                            "net.cumba.corej.core.exec.KeyMatchRowExpander", "Filter",
                            "net.cumba.corej.core.exec.MatchFilter")),
            Map.entry(net.cumba.corej.core.model.GroupingSpec.class,
                    Map.of("Variables", "net.cumba.corej.core.exec.RuleRunner", "keep_missings",
                            "net.cumba.corej.core.exec.OperationExecutor")),
            Map.entry(net.cumba.corej.core.model.ExpansionDirective.class,
                    Map.of("token", TOKEN_EXPANDER, "over", TOKEN_EXPANDER, "with", TOKEN_EXPANDER,
                            "pattern", TOKEN_EXPANDER, "known_domain_only", TOKEN_EXPANDER)),
            Map.entry(net.cumba.corej.core.model.WildcardFilter.class,
                    Map.of("min", WILDCARD_EXPANDER, "max", WILDCARD_EXPANDER)));

    /**
     * The JSON names Jackson's built bean deserializer binds on {@code type}, plus the
     * {@code @JsonAlias} spellings of those properties, under the loader's mapper configuration.
     */
    static Set<String> boundKeys(Class<?> type) throws JsonMappingException
    {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DeserializationConfig cfg = mapper.getDeserializationConfig();
        JavaType javaType = mapper.constructType(type);
        DefaultDeserializationContext ctxt = ((DefaultDeserializationContext) mapper
                .getDeserializationContext()).createInstance(cfg, null,
                        mapper.getInjectableValues());
        BeanDeserializerBase deser = (BeanDeserializerBase) ctxt
                .findRootValueDeserializer(javaType);
        Set<String> bound = new TreeSet<>();
        for (Iterator<SettableBeanProperty> it = deser.properties(); it.hasNext();)
        {
            bound.add(it.next().getName());
        }
        BeanDescription bd = cfg.introspect(javaType);
        for (BeanPropertyDefinition p : bd.findProperties())
        {
            if (!bound.contains(p.getName()))
            {
                continue;
            }
            for (PropertyName alias : p.findAliases())
            {
                bound.add(alias.getSimpleName());
            }
        }
        return bound;
    }


    @Test
    void everyRosterEqualsWhatJacksonBinds() throws JsonMappingException
    {
        assertEquals(BoundRuleKeys.BY_CLASS.keySet(), READERS.keySet(),
                "the reader table and the roster table cover the same classes");
        for (Map.Entry<Class<?>, Set<String>> e : BoundRuleKeys.BY_CLASS.entrySet())
        {
            assertEquals(new TreeSet<>(e.getValue()), boundKeys(e.getKey()), e.getKey()
                    .getSimpleName() + ": a key was bound or unbound without editing"
                    + " BoundRuleKeys — name its production reader in this test's READERS"
                    + " table too (a bound key nothing reads is the Wildcard defect again)");
            assertEquals(new TreeSet<>(e.getValue()),
                    new TreeSet<>(READERS.get(e.getKey()).keySet()),
                    e.getKey().getSimpleName() + ": the reader table lists exactly the roster");
        }
    }


    @Test
    void thePopulationIsNotVacuous()
    {
        // 24 bean shapes: the 27 classes reachable from RulePackage minus the Role enum and the
        // two custom-deserialized grammar classes (measured 2026-09-26, BoundKeys.java).
        assertTrue(BoundRuleKeys.BY_CLASS.size() >= 24,
                "classes: " + BoundRuleKeys.BY_CLASS.size());
        assertTrue(BoundRuleKeys.BY_CLASS.containsKey(net.cumba.corej.core.model.Outcome.class));
        assertTrue(BoundRuleKeys.BY_CLASS
                .containsKey(net.cumba.corej.core.model.ExpansionDirective.class));
    }


    @Test
    void everyNamedReaderExists() throws ClassNotFoundException
    {
        int named = 0;
        for (Map<String, String> readers : READERS.values())
        {
            for (String reader : readers.values())
            {
                if (reader.startsWith("net.cumba."))
                {
                    assertEquals(reader, Class.forName(reader).getName());
                    named++;
                }
            }
        }
        assertTrue(named >= 60, "named readers: " + named);
    }


    @Test
    void theHintOffersOneNearMissAndNeverARetiredKey()
    {
        assertEquals("Outcome", BoundRuleKeys.hint("outcome", BoundRuleKeys.RULE));
        assertEquals("Outcome", BoundRuleKeys.hint("Outcom", BoundRuleKeys.RULE));
        assertEquals("Version", BoundRuleKeys.hint("Versoin", BoundRuleKeys.CORE));
        assertEquals("Message", BoundRuleKeys.hint("Mesage", BoundRuleKeys.OUTCOME));
        assertNull(BoundRuleKeys.hint("Foo", BoundRuleKeys.RULE));
        assertNull(BoundRuleKeys.hint("operations", BoundRuleKeys.RULE), "retired, never hinted");
        assertNull(BoundRuleKeys.hint("rule_type", BoundRuleKeys.RULE), "retired, never hinted");
        assertNull(BoundRuleKeys.hint("ID", BoundRuleKeys.RULE),
                "id is the loader's, not the author's");
        // Ambiguity gives no hint rather than a wrong one.
        assertNull(BoundRuleKeys.hint("Includ", Set.of("Include", "Includx")));
        assertEquals(2, BoundRuleKeys.editDistance("abc", "xyz"), "capped at 2");
        assertEquals(1, BoundRuleKeys.editDistance("ab", "ba"), "a transposition is one edit");
    }
}
