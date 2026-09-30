package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.model.KeyHint;
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

    private static final String P = "net.cumba.corej.core.";

    private static final String LOADER = P + "RulePackageLoader";

    private static final String SCOPE_MATCHER = P + "exec.ScopeMatcher";

    private static final String REPORT_ASSEMBLER = P + "report.ReportAssembler";

    private static final String TOKEN_EXPANDER = P + "gen.TokenExpander";

    private static final String WILDCARD_EXPANDER = P + "gen.WildcardExpander";

    private static final String CLASSIFIER = P + "exec.StudyRuleClassifier";

    private static final String RUNNER = P + "exec.RuleRunner";

    private static final String KEY_MATCH = P + "exec.KeyMatchRowExpander";

    /** {@code fqcn#accessor}: the production class that reads the key and the accessor it calls. */
    private static String r(String fqcn, String accessor)
    {
        return fqcn + "#" + accessor;
    }

    /**
     * Bound JSON key → the production reader as {@code fqcn#accessor} (review E11: the reader's
     * bytecode must name that accessor), per model class. A {@link #NO_READER} entry is a key the
     * engine binds but never reads: serialised back out by the corpus generator (the model is also
     * the package writer's shape) or read by the report / tooling only. Each says why, in a place a
     * reviewer sees. {@code provenance:} / {@code retired:} / {@code alias} entries are not
     * readers.
     */
    private static final Map<Class<?>, Map<String, String>> READERS = Map.ofEntries(
            Map.entry(net.cumba.corej.core.model.RulePackage.class,
                    Map.of("rules", r(P + "run.StudyValidationService", "getRules"), "standards",
                            r(P + "run.StudyValidationService", "getStandards"))),
            Map.entry(StandardRef.class,
                    Map.of("id", r(P + "run.RunStandard", "id"), "role",
                            r(P + "run.RunStandard", "role"))),
            Map.entry(Rule.class, Map.ofEntries(
                    Map.entry("id", "provenance: the loader's / the rulespec harness's"
                            + " synthetic identity (Rule.effectiveId); file-loaded rules carry none"),
                    Map.entry("Core", r(P + "exec.DatasetRuleResolver", "getCore")),
                    Map.entry("Description", r(TOKEN_EXPANDER, "getDescription")),
                    Map.entry("ExecutabilityHint", r(LOADER, "getExecutabilityHint")),
                    Map.entry("Authorities", r(REPORT_ASSEMBLER, "getAuthorities")),
                    Map.entry("Scope", r(SCOPE_MATCHER, "getScope")),
                    Map.entry("Requirements", r(RUNNER, "getRequirements")),
                    Map.entry("Outcome", r(RUNNER, "getOutcome")),
                    Map.entry("Bindings", r(LOADER, "getBindings")),
                    Map.entry("Match_Datasets", r(RUNNER, "getMatchDatasets")),
                    Map.entry("Grouping_Variables",
                            r(P + "exec.RuleSpecialiser", "getGroupingVariables")),
                    Map.entry("Grouping", r(P + "exec.RuleSpecialiser", "getGrouping")),
                    Map.entry("Precondition", r(RUNNER, "getPrecondition")),
                    Map.entry("Expansion", r(TOKEN_EXPANDER, "getExpansion")),
                    Map.entry("skipIfLibraryDefined",
                            r(P + "exec.DatasetRuleResolver", "getSkipIfLibraryDefined")),
                    Map.entry("Supp_Merge", r(RUNNER, "isSuppMergeEnabled")),
                    Map.entry("wildcards", r(WILDCARD_EXPANDER, "getWildcards")),
                    Map.entry("wildcardExclude", r(WILDCARD_EXPANDER, "getWildcardExclude")),
                    Map.entry("wildcardPairCatalogue",
                            r(WILDCARD_EXPANDER, "getWildcardPairCatalogue")),
                    Map.entry("Check", r(RUNNER, "getCheck")),
                    Map.entry("Rule_Type",
                            "retired: bound only to be rejected by value"
                                    + " (RulePackageLoader.ruleTypeRejection)"),
                    Map.entry("Sensitivity", r(RUNNER, "getSensitivity")),
                    Map.entry("Severity", r(RUNNER, "effectiveSeverity")),
                    Map.entry("Executability",
                            r(P + "report.ValidationReportBuilder", "getExecutability")),
                    Map.entry("Variable_Universe", r(RUNNER, "getVariableUniverse")),
                    Map.entry("Operations",
                            "retired: bound only to throw"
                                    + " (Rule.rejectRetiredOperationsKey)"))),
            Map.entry(net.cumba.corej.core.model.RuleCore.class,
                    Map.of("Id", r(P + "exec.DatasetRuleResolver", "getId"), "Status",
                            r(P + "report.LibraryValidator", "getStatus"), "Version",
                            NO_READER + "serialised by the corpus generator; the report reads"
                                    + " Authorities")),
            Map.entry(net.cumba.corej.core.model.Outcome.class,
                    Map.of("Message", r(RUNNER, "getMessage"), "Output_Variables",
                            r(P + "exec.OutputVariableDeriver", "getOutputVariables"))),
            Map.entry(net.cumba.corej.core.model.ExecutabilityHint.class,
                    Map.of("Category", r(LOADER, "getCategory"), "Detail", r(LOADER, "getDetail"))),
            Map.entry(net.cumba.corej.core.model.Authority.class,
                    Map.of("Organization", r(REPORT_ASSEMBLER, "getOrganization"), "Standards",
                            r(REPORT_ASSEMBLER, "getStandards"), "Rule_Ids",
                            r(REPORT_ASSEMBLER, "getRuleIds"))),
            Map.entry(net.cumba.corej.core.model.AuthorityStandard.class,
                    Map.of("Name",
                            NO_READER + "release shape collapses Authorities to"
                                    + " {Organization, Rule_Ids}; authored corpus only",
                            "Version", NO_READER + "same", "Substandard", NO_READER + "same",
                            "References", r(REPORT_ASSEMBLER, "getReferences"))),
            Map.entry(net.cumba.corej.core.model.Reference.class,
                    Map.of("Origin", NO_READER + "authored corpus only (release shape)", "Version",
                            NO_READER + "same", "Rule_Identifier",
                            r(REPORT_ASSEMBLER, "getRuleIdentifier"), "Citations",
                            NO_READER + "same — the corpus docs tooling reads citations")),
            Map.entry(net.cumba.corej.core.model.RuleIdentifier.class,
                    Map.of("Id", r(REPORT_ASSEMBLER, "getId"), "Version",
                            NO_READER + "authored corpus only (release shape)")),
            Map.entry(net.cumba.corej.core.model.Citation.class,
                    Map.of("Cited_Guidance", NO_READER + "rules-src/docs tooling", "Document",
                            NO_READER + "same", "Item", NO_READER + "same", "Section",
                            NO_READER + "same")),
            Map.entry(net.cumba.corej.core.model.Scope.class,
                    Map.of("Classes", r(SCOPE_MATCHER, "getClasses"), "Domains",
                            r(SCOPE_MATCHER, "getDomains"), "Datasets",
                            r(SCOPE_MATCHER, "getDatasets"), "Use_Case",
                            r(SCOPE_MATCHER, "getUseCase"), "Data_Structures",
                            r(CLASSIFIER, "getDataStructures"), "Data Structures",
                            "alias of Data_Structures (the upstream CORE spelling)", "Subclasses",
                            r(CLASSIFIER, "getSubclasses"))),
            Map.entry(net.cumba.corej.core.model.ClassScope.class,
                    Map.of("Include", r(SCOPE_MATCHER, "getInclude"), "Exclude",
                            r(SCOPE_MATCHER, "getExclude"))),
            Map.entry(net.cumba.corej.core.model.DomainScope.class,
                    Map.of("Include", r(SCOPE_MATCHER, "getInclude"), "Exclude",
                            r(SCOPE_MATCHER, "getExclude"), "include_split_datasets",
                            r(SCOPE_MATCHER, "getIncludeSplitDatasets"))),
            Map.entry(net.cumba.corej.core.model.DatasetScope.class,
                    Map.of("Include", r(SCOPE_MATCHER, "getInclude"), "Exclude",
                            r(SCOPE_MATCHER, "getExclude"))),
            Map.entry(net.cumba.corej.core.model.DataStructureScope.class,
                    Map.of("Include", r(CLASSIFIER, "getInclude"), "Exclude",
                            r(CLASSIFIER, "getExclude"))),
            Map.entry(net.cumba.corej.core.model.SubclassScope.class,
                    Map.of("Include", r(CLASSIFIER, "getInclude"), "Exclude",
                            r(CLASSIFIER, "getExclude"))),
            Map.entry(net.cumba.corej.core.model.Requirements.class,
                    Map.of("Variables", r(WILDCARD_EXPANDER, "getVariables"), "Datasets",
                            r(RUNNER, "getDatasets"), "Library", r(WILDCARD_EXPANDER, "getLibrary"),
                            "Define", r(WILDCARD_EXPANDER, "getDefine"), "Dictionary",
                            r(WILDCARD_EXPANDER, "getDictionary"))),
            Map.entry(net.cumba.corej.core.model.VariableRequirement.class,
                    Map.of("All", r(SCOPE_MATCHER, "getAll"), "Any", r(SCOPE_MATCHER, "anyUnion"),
                            "None", r(SCOPE_MATCHER, "getNone"), "All_Or_None",
                            r(SCOPE_MATCHER, "allOrNoneUnion"))),
            Map.entry(net.cumba.corej.core.model.Binding.class,
                    Map.of("name", r(LOADER, "getName"), "expression", r(LOADER, "getExpression"))),
            Map.entry(MatchDataset.class, Map.of("Name", r(P + "exec.RelrecRowExpander", "getName"),
                    "Keys", r(KEY_MATCH, "getKeys"), "Child",
                    r(P + "exec.ChildMatchPreMerger", "getChild"), "Join_Type",
                    r(KEY_MATCH, "getJoinType"), "Join_As_String", r(KEY_MATCH, "joinKeysAsString"),
                    "keep_missings", r(KEY_MATCH, "keepMissingKeys"), "Filter",
                    r(P + "exec.MatchFilter", "filterExpr"))),
            Map.entry(net.cumba.corej.core.model.GroupingSpec.class,
                    Map.of("Variables", r(P + "model.Rule", "getVariables"), "keep_missings",
                            r(P + "model.Rule", "getKeepMissings"))),
            Map.entry(net.cumba.corej.core.model.ExpansionDirective.class,
                    Map.of("token", r(TOKEN_EXPANDER, "getToken"), "over",
                            r(TOKEN_EXPANDER, "getOver"), "with", r(TOKEN_EXPANDER, "getWith"),
                            "pattern", r(TOKEN_EXPANDER, "getPattern"), "known_domain_only",
                            r(TOKEN_EXPANDER, "getKnownDomainOnly"))),
            Map.entry(net.cumba.corej.core.model.WildcardFilter.class, Map.of("min",
                    r(WILDCARD_EXPANDER, "accepts"), "max", r(WILDCARD_EXPANDER, "accepts"))));

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


    /**
     * Every named reader exists AND its bytecode names the accessor the table says it calls (review
     * E11). The scan reads the reader class file and its nested classes for the accessor name as a
     * UTF-8 constant — a proxy for "calls it" that a reader which never mentions the accessor
     * cannot pass. ⚠ A generic accessor name ({@code getName}, {@code getId}, {@code getVariables})
     * can be satisfied by an unrelated call in the same class; the column's value is still that
     * whoever adds a key has to SAY where it is read, in a place a reviewer sees — and name an
     * accessor that class really contains.
     */
    @Test
    void everyNamedReaderReferencesTheAccessor() throws Exception
    {
        int named = 0;
        List<String> unreferenced = new java.util.ArrayList<>();
        for (Map.Entry<Class<?>, Map<String, String>> e : READERS.entrySet())
        {
            for (Map.Entry<String, String> r : e.getValue().entrySet())
            {
                String reader = r.getValue();
                if (!reader.startsWith("net.cumba."))
                {
                    continue;
                }
                named++;
                int hash = reader.indexOf('#');
                assertTrue(hash > 0, "reader entries are fqcn#accessor: " + reader);
                Class<?> readerClass = Class.forName(reader.substring(0, hash));
                String accessor = reader.substring(hash + 1);
                if (!bytecodeMentions(readerClass, e.getKey(), accessor))
                {
                    unreferenced
                            .add(e.getKey().getSimpleName() + "." + r.getKey() + " -> " + reader);
                }
            }
        }
        assertTrue(named >= 60, "named readers: " + named);
        assertTrue(unreferenced.isEmpty(),
                "readers whose constant pool holds no reference to the accessor on the roster"
                        + " class: " + unreferenced);
    }


    /**
     * Whether {@code reader} (or one of its nested classes — lambdas compile into the class that
     * declares them) holds a {@code Methodref} / {@code InterfaceMethodref} / {@code Fieldref}
     * constant whose owner is {@code owner} and whose name is exactly {@code member}. Parsed from
     * the class file's constant pool (review T1 / T2): an exact name-and-owner match, never a
     * substring of the file ({@code getInclude} is not {@code getIncludeSplitDatasets}, and
     * {@code id} is not {@code idByLine}).
     */
    private static boolean bytecodeMentions(Class<?> reader, Class<?> owner, String member)
        throws Exception
    {
        String ownerInternal = owner.getName().replace('.', '/');
        java.util.Deque<Class<?>> todo = new java.util.ArrayDeque<>(List.of(reader));
        while (!todo.isEmpty())
        {
            Class<?> c = todo.pop();
            String binary = c.getName().substring(c.getName().lastIndexOf('.') + 1);
            try (java.io.InputStream in = c.getResourceAsStream(binary + ".class"))
            {
                assertNotNull(in, "class file of " + c.getName());
                if (memberRefs(in.readAllBytes()).contains(ownerInternal + "#" + member))
                {
                    return true;
                }
            }
            java.util.Collections.addAll(todo, c.getDeclaredClasses());
        }
        return false;
    }


    /**
     * Every {@code owner#name} of the Fieldref / Methodref / InterfaceMethodref constants of a
     * class file (JVMS §4.4: tags 9, 10, 11 → class index + name-and-type index).
     */
    private static Set<String> memberRefs(byte[] classFile)
    {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(classFile);
        assertEquals(0xCAFEBABE, in.getInt(), "class file magic");
        in.getShort();
        in.getShort();
        int count = in.getShort() & 0xFFFF;
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        int[] natNameIndex = new int[count];
        int[][] refs = new int[count][];
        for (int i = 1; i < count; i++)
        {
            int tag = in.get() & 0xFF;
            switch (tag)
            {
            case 1 ->
            {
                int len = in.getShort() & 0xFFFF;
                byte[] bytes = new byte[len];
                in.get(bytes);
                utf8[i] = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            case 3, 4 -> in.getInt();
            case 5, 6 ->
            {
                in.getLong();
                i++;
            }
            case 7 -> classNameIndex[i] = in.getShort() & 0xFFFF;
            case 8, 16, 19, 20 -> in.getShort();
            case 9, 10, 11 -> refs[i] = new int[]
                {
                        in.getShort() & 0xFFFF, in.getShort() & 0xFFFF
                };
            case 12 ->
            {
                natNameIndex[i] = in.getShort() & 0xFFFF;
                in.getShort();
            }
            case 15 ->
            {
                in.get();
                in.getShort();
            }
            case 17, 18 ->
            {
                in.getShort();
                in.getShort();
            }
            default -> throw new IllegalStateException("constant pool tag " + tag);
            }
        }
        Set<String> out = new java.util.HashSet<>();
        for (int[] ref : refs)
        {
            if (ref != null)
            {
                out.add(utf8[classNameIndex[ref[0]]] + "#" + utf8[natNameIndex[ref[1]]]);
            }
        }
        return out;
    }


    @Test
    void theHintOffersOneNearMissAndNeverARetiredOrPresentKey()
    {
        Set<String> none = Set.of();
        Set<String> retired = BoundRuleKeys.NEVER_HINTED;
        assertEquals("Outcome", KeyHint.nearest("outcome", BoundRuleKeys.RULE, none, retired));
        assertEquals("Outcome", KeyHint.nearest("Outcom", BoundRuleKeys.RULE, none, retired));
        assertEquals("Version", KeyHint.nearest("Versoin", BoundRuleKeys.CORE, none, retired));
        assertEquals("Message", KeyHint.nearest("Mesage", BoundRuleKeys.OUTCOME, none, retired));
        assertEquals("Domains", KeyHint.nearest("domains", BoundRuleKeys.SCOPE, none, retired));
        // P6: the case-only match wins over the alias one edit away.
        assertEquals("Data_Structures",
                KeyHint.nearest("data_structures", BoundRuleKeys.SCOPE, none, retired));
        assertNull(KeyHint.nearest("data-structures", Set.of("Data_Structures", "Data Structures"),
                none, none), "equally near (one edit from both spellings): no hint");
        assertNull(KeyHint.nearest("Foo", BoundRuleKeys.RULE, none, retired));
        assertNull(KeyHint.nearest("operations", BoundRuleKeys.RULE, none, retired),
                "retired, never hinted");
        assertNull(KeyHint.nearest("rule_type", BoundRuleKeys.RULE, none, retired),
                "retired, never hinted");
        assertNull(KeyHint.nearest("ID", BoundRuleKeys.RULE, none, retired),
                "id is the loader's, not the author's");
        // Ambiguity gives no hint rather than a wrong one.
        assertNull(KeyHint.nearest("Includ", Set.of("Include", "Includx"), none, none));
        // A key the object already carries is never proposed (review E3).
        assertNull(KeyHint.nearest("lfet", Set.of("left", "right"), Set.of("left", "lfet"), none));
        assertEquals("left",
                KeyHint.nearest("lfet", Set.of("left", "right"), Set.of("lfet"), none));
        assertEquals("; did you mean 'Outcome'?",
                KeyHint.clause("Outcom", BoundRuleKeys.RULE, none, retired));
        assertEquals("", KeyHint.clause("Foo", BoundRuleKeys.RULE, none, retired));
        assertEquals(2, KeyHint.editDistance("abc", "xyz"), "capped at 2");
        assertEquals(1, KeyHint.editDistance("ab", "ba"), "a transposition is one edit");
    }
}
