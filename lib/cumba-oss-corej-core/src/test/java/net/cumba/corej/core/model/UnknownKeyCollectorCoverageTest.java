package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every model class a rule package deserializes into collects its unknown keys — so <i>"unknown
 * keys in a rule should always result in a load error"</i> (owner, 2026-09-25) holds for a class
 * <b>added later</b>, not only for the ones {@code PLAN-rule-unknown-keys-gate} listed
 * ({@code UnknownKeysGateTest} covers those by planting).
 *
 * <p>
 * The population is discovered, not written: the classes reachable from {@link RulePackage} through
 * the property types of Jackson's <b>built</b> deserializers (list / map element types unwrapped).
 * Each must declare a {@code @JsonAnySetter} method, or be custom-deserialized (its grammar is its
 * own gate: {@link CheckCondition} / {@link RuleCheck} through
 * {@code CheckConditionDeserializer.strayKeys}), or an enum / JDK type, or {@link StandardRef} —
 * whose extras {@link RulePackage#setStandardsJson} collects, because the record is serialised into
 * every package (T1-6, review H1); that exemption is asserted, not assumed.
 * </p>
 *
 * <p>
 * ⚠ What it does NOT cover (review L4): the custom-deserialized exemption means a key added later
 * <em>inside</em> {@code Check} / a level / a condition is invisible here. That surface is the
 * {@code DISPATCH_ORDER} constant's: a new condition keyword not added there is rejected as a stray
 * key at load — loud, not silent — pinned by {@code UnknownKeysGateTest}'s condition rows.
 * </p>
 *
 * <p>
 * ⚑ Non-vacuity: the walk must reach at least 25 classes and, by name, {@link Outcome} and
 * {@link ExpansionDirective}; a sabotage run (one collector deleted) reds this class — recorded in
 * the plan's status file.
 * </p>
 */
class UnknownKeyCollectorCoverageTest
{

    private static final String MODEL_PACKAGE = RulePackage.class.getPackageName();

    /** Every model class reachable from {@link RulePackage} through bound property types. */
    static Set<Class<?>> reachableModelClasses() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DeserializationConfig cfg = mapper.getDeserializationConfig();
        DefaultDeserializationContext ctxt = ((DefaultDeserializationContext) mapper
                .getDeserializationContext()).createInstance(cfg, null,
                        mapper.getInjectableValues());
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(RulePackage.class));
        Set<Class<?>> seen = new LinkedHashSet<>();
        while (!todo.isEmpty())
        {
            Class<?> c = todo.pop();
            if (!seen.add(c) || c.isEnum() || isCustomDeserialized(c))
            {
                continue;
            }
            JsonDeserializer<?> deser = ctxt.findRootValueDeserializer(mapper.constructType(c));
            if (!(deser instanceof BeanDeserializerBase bean))
            {
                continue;
            }
            for (Iterator<SettableBeanProperty> it = bean.properties(); it.hasNext();)
            {
                JavaType t = it.next().getType();
                while (t != null && t.isContainerType())
                {
                    t = t.getContentType();
                }
                if (t != null && t.getRawClass().getPackageName().equals(MODEL_PACKAGE))
                {
                    todo.add(t.getRawClass());
                }
            }
        }
        return seen;
    }


    private static boolean isCustomDeserialized(Class<?> c)
    {
        JsonDeserialize ann = c.getAnnotation(JsonDeserialize.class);
        return ann != null && ann.using() != JsonDeserializer.None.class;
    }


    private static boolean hasAnySetter(Class<?> c)
    {
        return Arrays.stream(c.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(JsonAnySetter.class));
    }


    @Test
    void everyReachableModelClassCollectsUnknownKeys() throws Exception
    {
        Set<Class<?>> reachable = reachableModelClasses();
        // 24 = 22 collector beans + RuleCheck (custom) + the Role enum; StandardRef is bound per
        // entry by RulePackage.setStandardsJson and CheckCondition only through RuleCheck now.
        assertTrue(reachable.size() >= 24, "reachable model classes: " + reachable);
        assertTrue(reachable.contains(Outcome.class), reachable.toString());
        assertTrue(reachable.contains(ExpansionDirective.class), reachable.toString());
        List<String> uncovered = new ArrayList<>();
        for (Class<?> c : reachable)
        {
            if (c.isEnum() || isCustomDeserialized(c) || c == StandardRef.class)
            {
                continue;
            }
            if (!hasAnySetter(c))
            {
                uncovered.add(c.getSimpleName());
            }
        }
        assertTrue(uncovered.isEmpty(), "model classes a rule deserializes into without a"
                + " @JsonAnySetter unknown-key collector (an unknown key there would be dropped"
                + " silently — PLAN-rule-unknown-keys-gate): " + uncovered);
    }


    @Test
    void theStandardRefExemptionIsBackedByThePackageCollector() throws Exception
    {
        // StandardRef carries no collector on purpose; RulePackage's explicit JSON setter for
        // `standards` reads the raw entries and records their extras. Without that method the
        // exemption above would be a hole.
        Method setter = Arrays.stream(RulePackage.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(JsonSetter.class)
                        && "standards".equals(m.getAnnotation(JsonSetter.class).value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "RulePackage must bind `standards` through an explicit @JsonSetter that"
                                + " collects the entries' unknown keys (T1-6)"));
        assertEquals(1, setter.getParameterCount());
        // The walk no longer reaches StandardRef at all: `standards` binds as raw ObjectNodes
        // (that is the collector), so the record is bound per entry by RulePackage. The roster
        // that setter judges the entries by must be the record's own component names.
        assertEquals(RulePackage.STANDARD_REF_KEYS,
                Arrays.stream(StandardRef.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()),
                "STANDARD_REF_KEYS must be exactly the record's components");
    }


    /**
     * ⭐ Review E1 — "always" for a class added later is ENFORCED here: the roster table the
     * loader's gate and hint read ({@code BoundRuleKeys.BY_CLASS}) must equal the classes the walk
     * reaches, plus {@link StandardRef} (bound per entry by {@code RulePackage.setStandardsJson},
     * so the walk never sees it) and minus the two custom-deserialized grammar classes, whose keys
     * are {@code CheckConditionDeserializer.DISPATCH_ORDER} plus a level's {@code Message}. A new
     * model class therefore reds twice: here (no roster) and in
     * {@code UnknownKeysGateTest.everyRosterClassIsWalkedByTheGate} (no walker arm).
     */
    @Test
    void theRosterTableIsExactlyTheReachableBeanClasses() throws Exception
    {
        Set<Class<?>> expected = new java.util.HashSet<>(reachableModelClasses());
        expected.removeIf(c -> c.isEnum() || isCustomDeserialized(c));
        expected.add(StandardRef.class);
        assertEquals(expected, net.cumba.corej.core.BoundRuleKeys.BY_CLASS.keySet(),
                "BoundRuleKeys.BY_CLASS must list exactly the bean classes a rule package"
                        + " deserializes into (a class the walk reaches but the roster lacks has no"
                        + " gate; a roster entry nothing reaches is dead)");
    }


    /**
     * Review E1, second half: a property typed {@code JsonNode} / {@code Object} /
     * {@code Map<String, Object>} is a hole the collector walk cannot see into. Every such property
     * is listed here with the code that judges its contents, and the list is exact.
     */
    @Test
    void everyRawTypedPropertyHasANamedJudge() throws Exception
    {
        Map<String, String> judged = Map.of("MatchDataset.Keys",
                "MatchDataset.strayElementKeys / malformedKeyElement (the {left, right} elements)",
                "MatchDataset.Join_As_String",
                "MatchDataset.hasMalformedJoinAsString (a boolean or a rejected shape)",
                "RulePackage.standards",
                "RulePackage.setStandardsJson (entry extras collected, record bound per entry)",
                "Rule.Operations", "Rule.rejectRetiredOperationsKey (throws)",
                "VariableRequirement.Any", "VariableRequirement.readAny (flat or grouped lists)",
                "VariableRequirement.All_Or_None",
                "VariableRequirement.readAllOrNone (grouped lists)");
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DeserializationConfig cfg = mapper.getDeserializationConfig();
        DefaultDeserializationContext ctxt = ((DefaultDeserializationContext) mapper
                .getDeserializationContext()).createInstance(cfg, null,
                        mapper.getInjectableValues());
        Set<String> raw = new java.util.TreeSet<>();
        for (Class<?> c : reachableModelClasses())
        {
            if (c.isEnum() || isCustomDeserialized(c))
            {
                continue;
            }
            JsonDeserializer<?> deser = ctxt.findRootValueDeserializer(mapper.constructType(c));
            if (!(deser instanceof BeanDeserializerBase bean))
            {
                continue;
            }
            for (Iterator<SettableBeanProperty> it = bean.properties(); it.hasNext();)
            {
                SettableBeanProperty p = it.next();
                JavaType t = p.getType();
                while (t != null && t.isContainerType() && !t.isMapLikeType())
                {
                    t = t.getContentType();
                }
                if (t == null)
                {
                    continue;
                }
                Class<?> rc = t.getRawClass();
                boolean rawShape = com.fasterxml.jackson.databind.JsonNode.class
                        .isAssignableFrom(rc) || rc == Object.class
                        || (t.isMapLikeType() && t.getContentType() != null
                                && t.getContentType().getRawClass() == Object.class);
                if (rawShape)
                {
                    raw.add(c.getSimpleName() + "." + p.getName());
                }
            }
        }
        assertEquals(new java.util.TreeSet<>(judged.keySet()), raw,
                "raw-typed properties the collector walk cannot see into — each needs a named"
                        + " judge in this table: " + raw);
    }


    @Test
    void theCustomDeserializedClassesAreExactlyTheCheckGrammar() throws Exception
    {
        Set<String> custom = new java.util.TreeSet<>();
        for (Class<?> c : reachableModelClasses())
        {
            if (isCustomDeserialized(c))
            {
                custom.add(c.getSimpleName());
            }
        }
        // CheckCondition is reached only through RuleCheck since Precondition binds like Check
        // (PreconditionDeserializer): one custom class, whose grammar strayKeys judges.
        assertEquals(Set.of("RuleCheck"), custom,
                "a new custom-deserialized class widens the exemption — cover its keys explicitly");
    }
}
