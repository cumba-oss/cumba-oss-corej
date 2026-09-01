package net.cumba.cdisc.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.OperationExecutor;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.convert.OperationExpressionParser;
import org.junit.jupiter.api.Test;

/**
 * <b>The guard that did not exist.</b> {@link Operation} is read by a chain of hand-written,
 * field-by-field routines — two copy routines in {@code OperationExecutor}, a parser, a printer —
 * and every one of them silently drops a field it does not name. {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * is {@code false} on every mapper in the repo and there is no global Jackson naming strategy, so
 * an unregistered field is never an error; it simply stops existing at runtime. Fix #99 lost
 * {@code offset} exactly that way, in {@code resolvePrefixes}.
 *
 * <p>
 * The guards written afterwards did not close the hole: they are literal {@code assertEquals}
 * lists, so they pin the fields someone remembered to add and say nothing about the next one. These
 * tests instead <b>enumerate {@link Operation}'s declared fields reflectively</b> and derive both
 * the sentinel values and the expectations from that enumeration — so a field added tomorrow and
 * wired nowhere fails here, before it can misbehave silently.
 * </p>
 *
 * <p>
 * Added by EC-51 Half B (Fix #145), whose own {@code missing_values} field is the fifth to have to
 * walk this chain by hand.
 * </p>
 */
class OperationFieldRegistrationTest
{

    /**
     * Fields excluded from the JSON-surface tests, each for a stated structural reason — never
     * because wiring them up was inconvenient.
     */
    private static final Map<String, String> NOT_ON_THE_EXPRESSION_SURFACE = Map.of(
            // The function name itself, not a kwarg.
            "operator", "rendered as the call's function name",
            // The authoring form being parsed; a normalised operation never carries it.
            "expression", "the Form-B source text, consumed by normalize()",
            // The `$`-variable, carried alongside the call rather than inside it.
            "id", "assigned by the caller, not parsed from the call",
            // Runtime-only, @JsonIgnore, stashed by resolvePrefixes.
            "originalName", "@JsonIgnore runtime state");

    private static List<Field> instanceFields()
    {
        List<Field> fields = new ArrayList<>();
        for (Field f : Operation.class.getDeclaredFields())
        {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
            {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        assertTrue(fields.size() >= 35,
                "expected Operation to carry its full field set, saw " + fields.size());
        return fields;
    }


    /** The JSON key a field binds to: its {@code @JsonProperty} value, else the field name. */
    private static String jsonKey(Field f)
    {
        JsonProperty annotation = f.getAnnotation(JsonProperty.class);
        return annotation != null && !annotation.value().isEmpty() ? annotation.value()
                : f.getName();
    }


    /**
     * A distinctive, type-appropriate sentinel for {@code f}. Deriving it from the field's type
     * rather than a hand-written table is what makes a new field automatically covered.
     */
    private static Object sentinelFor(Field f)
    {
        Class<?> type = f.getType();
        if (type == String.class)
        {
            return sentinelString(f);
        }
        if (type == Integer.class)
        {
            return 200;
        }
        if (type == Boolean.class)
        {
            return true;
        }
        if (type == List.class)
        {
            return List.of(f.getName().toUpperCase(java.util.Locale.ROOT) + "1",
                    f.getName().toUpperCase(java.util.Locale.ROOT) + "2");
        }
        if (type == Map.class)
        {
            return Map.of("FILTERCOL", "FILTERVAL");
        }
        return fail("no sentinel defined for field `" + f.getName() + "` of type " + type.getName()
                + " — extend sentinelFor() when adding a differently-typed Operation field");
    }


    /**
     * String sentinels have to satisfy the field's own validation where it has any, so the two
     * validated string fields get a legal value and everything else gets a unique marker.
     */
    private static String sentinelString(Field f)
    {
        return switch (f.getName())
        {
        // Validated at load: only these two values exist (EC-51 Half B).
        case "missingValues" -> Operation.MISSING_VALUES_INDETERMINATE;
        // Only "max" is meaningful, but any string round-trips; keep it realistic.
        case "referenceExtreme" -> "max";
        // EC-85: validated at load against SdtmObservationClasses.MODEL_CLASS_NAMES.
        case "modelClass" -> "EVENTS";
        default -> "SENTINEL_" + f.getName();
        };
    }


    private static Operation fullyPopulated() throws Exception
    {
        Operation op = new Operation();
        op.setOperator("date_diff_days");
        for (Field f : instanceFields())
        {
            if (!"operator".equals(f.getName()))
            {
                f.set(op, sentinelFor(f));
            }
        }
        return op;
    }

    // -----------------------------------------------------------------------
    // Sites 4 and 5 — the two hand-written copy routines
    // -----------------------------------------------------------------------


    /**
     * {@code expandGroupRefs} rebuilds an operation field by field whenever its {@code group}
     * carries a {@code $}-ref. Every field except {@code group} itself must survive.
     */
    @Test
    void everyFieldSurvivesExpandGroupRefs() throws Exception
    {
        Operation op = fullyPopulated();
        op.setGroup(List.of("$grp"));

        Map<String, Object> variables = new HashMap<>();
        variables.put("$grp", List.of("USUBJID"));

        Method m = OperationExecutor.class.getDeclaredMethod("expandGroupRefs", Operation.class,
                Map.class);
        m.setAccessible(true);
        Operation copy = (Operation) m.invoke(null, op, variables);

        assertEquals(List.of("USUBJID"), copy.getGroup(), "sanity: the $-ref really was expanded");
        assertEveryFieldPreserved(op, copy, "expandGroupRefs", "group");
    }


    /**
     * {@code resolvePrefixes} is the routine that actually lost {@code offset} (Fix #99). It
     * rebuilds the operation whenever any field carries a {@code --} token; here only {@code name}
     * does, so every other field must come through untouched.
     */
    @Test
    void everyFieldSurvivesResolvePrefixes() throws Exception
    {
        Operation op = fullyPopulated();
        op.setName("--STDTC");

        Operation resolved = OperationExecutor.resolvePrefixes(op, "EX");

        assertEquals("EXSTDTC", resolved.getName(), "sanity: the wildcard really did resolve");
        // `name` was rewritten by design, and `originalName` is where resolvePrefixes stashes the
        // pre-resolution value — both are outputs of this routine, not fields it copies.
        assertEveryFieldPreserved(op, resolved, "resolvePrefixes", "name", "originalName");
    }


    /**
     * Fix #152 — the third hand-written copy routine. {@code WildcardExpander.renameOperationNames}
     * rebuilds an operation field by field whenever a template expansion rewrites one of its
     * column-position names; a field it forgets is silently dropped from every expanded rule, which
     * is the same class of defect as Fix #99's lost {@code offset}.
     */
    @Test
    void everyFieldSurvivesWildcardOperationRename() throws Exception
    {
        Operation op = fullyPopulated();
        op.setName("AyIND");

        // A whole-name lookup, exactly the shape WildcardExpander.expandRule passes: every other
        // sentinel string is not a key and must therefore come through untouched.
        Method m = net.cumba.cdisc.core.gen.WildcardExpander.class.getDeclaredMethod(
                "renameOperationNames", List.class, java.util.function.UnaryOperator.class);
        m.setAccessible(true);
        java.util.function.UnaryOperator<String> rename = n -> "AyIND".equals(n) ? "A1IND" : n;
        @SuppressWarnings("unchecked")
        List<Operation> renamed = (List<Operation>) m.invoke(null, List.of(op), rename);
        assertNotNull(renamed);
        Operation copy = renamed.get(0);

        assertEquals("A1IND", copy.getName(), "sanity: the wildcard token really was substituted");
        assertEveryFieldPreserved(op, copy, "WildcardExpander.renameOperationNames", "name");
    }


    private static void assertEveryFieldPreserved(Operation source, Operation copy, String routine,
            String... transformed)
        throws Exception
    {
        List<String> exempt = List.of(transformed);
        List<String> lost = new ArrayList<>();
        for (Field f : instanceFields())
        {
            if (exempt.contains(f.getName()))
            {
                continue;
            }
            Object before = f.get(source);
            Object after = f.get(copy);
            assertNotNull(before, "the fixture must populate every field, `" + f.getName()
                    + "` was null — extend sentinelFor()");
            if (!before.equals(after))
            {
                lost.add(f.getName() + " (expected " + before + ", got " + after + ")");
            }
        }
        assertTrue(lost.isEmpty(), routine + " dropped " + lost.size() + " Operation field(s): "
                + lost + " — add them to its field-by-field copy, as Fix #99 had to for `offset`");
    }

    // -----------------------------------------------------------------------
    // Site 2 — the parser
    // -----------------------------------------------------------------------


    /**
     * Every JSON-surface field must be a case in {@code applyKwarg}. Its default branch throws, so
     * an unregistered field is a hard load error rather than a silent drop — which means the
     * failure this test catches is "a field exists on the model but no rule can author it".
     */
    @Test
    void everyJsonFieldIsAcceptedByTheParser()
    {
        List<String> unparseable = new ArrayList<>();
        for (Field f : instanceFields())
        {
            if (NOT_ON_THE_EXPRESSION_SURFACE.containsKey(f.getName())
                    || f.getAnnotation(JsonIgnore.class) != null)
            {
                continue;
            }
            Expr.Call call = new Expr.Call("date_diff_days", List.of(),
                    Map.of(jsonKey(f), kwargExprFor(f)));
            try
            {
                Operation parsed = OperationExpressionParser.fromCall(call, "$x");
                assertNotNull(parsed);
            }
            catch (net.cumba.cdisc.core.expr.RuleDefinitionException ex)
            {
                if (ex.getMessage() != null && ex.getMessage().contains("unknown argument"))
                {
                    unparseable.add(jsonKey(f));
                }
                // Any other rejection is the field's own validation doing its job, not a
                // registration gap — `missing_values` on a non-consuming operator, say.
            }
        }
        assertTrue(unparseable.isEmpty(), "OperationExpressionParser.applyKwarg has no case for "
                + unparseable + " — a rule declaring it would fail to load");
    }


    /** The literal a kwarg of this field's type must be written as. */
    private static Expr kwargExprFor(Field f)
    {
        Class<?> type = f.getType();
        if (type == Integer.class)
        {
            return new Expr.Lit(Expr.LitKind.NUMBER, 200.0d);
        }
        if (type == Boolean.class)
        {
            return new Expr.Lit(Expr.LitKind.BOOL, true);
        }
        if (type == List.class)
        {
            return new Expr.Lit(Expr.LitKind.LIST, List.of(new Expr.Lit(Expr.LitKind.STRING, "A")));
        }
        if (type == Map.class)
        {
            return new Expr.Call("filter", List.of(),
                    Map.of("K", new Expr.Lit(Expr.LitKind.STRING, "v")));
        }
        return new Expr.Lit(Expr.LitKind.STRING, sentinelString(f));
    }

    // -----------------------------------------------------------------------
    // The Jackson surface
    // -----------------------------------------------------------------------


    /**
     * Every JSON-surface field must bind from the field form too. With
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} a key Jackson does not recognise is dropped in
     * silence, so this asserts the round trip rather than the absence of an exception.
     */
    @Test
    void everyJsonFieldBindsFromTheFieldForm() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        List<String> unbound = new ArrayList<>();
        for (Field f : instanceFields())
        {
            if (f.getAnnotation(JsonIgnore.class) != null || "expression".equals(f.getName()))
            {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operator", "date_diff_days");
            body.put(jsonKey(f), sentinelFor(f));
            Operation bound = mapper.readValue(mapper.writeValueAsString(body), Operation.class);
            if (!sentinelFor(f).equals(f.get(bound)))
            {
                unbound.add(jsonKey(f) + " (got " + f.get(bound) + ")");
            }
        }
        assertTrue(unbound.isEmpty(), "Jackson silently dropped " + unbound
                + " — check the @JsonProperty key against the authoring spelling");
        assertFalse(instanceFields().isEmpty());
    }

}
