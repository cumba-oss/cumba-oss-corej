package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.model.MatchDataset;
import org.junit.jupiter.api.Test;

/**
 * A {@code Match_Datasets} key cannot be bound without naming the production class that reads it
 * ({@code PLAN-match-datasets-wildcard} §4.3).
 *
 * <p>
 * ⭐ Why: {@code Wildcard} was bound on {@link MatchDataset} since the monorepo and read by nothing,
 * so an author could write it, the loader accepted it, and it did nothing — for years, with every
 * gate green. Binding a key is what makes the unknown-key gate accept it, so a bound key with no
 * reader is the same silence the gate exists to stop. ⇒ The bound set must <b>equal</b> this roster
 * (equality, not a subset: adding a key reds until the roster names its reader), and the loader's
 * own list — the one its error message quotes — must equal it too.
 * </p>
 *
 * <p>
 * ⚑ The reader column is checked only for existence. Checking each reader's bytecode for the
 * accessor was considered and rejected (plan §4.3): {@code getName} is in every class, so it would
 * be vacuous for exactly the key most likely to be copied. The column's job is to make whoever adds
 * a key <b>say</b> where it is read, in a place a reviewer sees.
 * </p>
 */
class MatchDatasetBoundKeysRosterTest
{

    /** Bound JSON key → the production class that reads it. */
    private static final Map<String, String> READERS = Map.of("Name",
            "net.cumba.corej.core.exec.RelrecRowExpander", "Keys",
            "net.cumba.corej.core.exec.KeyMatchRowExpander", "Child",
            "net.cumba.corej.core.exec.ChildMatchPreMerger", "Join_Type",
            "net.cumba.corej.core.exec.KeyMatchRowExpander", "Join_As_String",
            "net.cumba.corej.core.exec.KeyMatchRowExpander", "keep_missings",
            "net.cumba.corej.core.exec.KeyMatchRowExpander", "Filter",
            "net.cumba.corej.core.exec.MatchFilter");

    /**
     * The JSON names Jackson itself binds on {@link MatchDataset} — every introspected property
     * with a mutator (setter, field or creator parameter), plus each property's {@code @JsonAlias}
     * spellings. ⚠ Asked of Jackson, not of the annotations: a reflection walk over
     * {@code @JsonProperty} misses a key bound by a plain Lombok setter (a field added with no
     * annotation binds under its Java name) and every {@code @JsonAlias}, so either would drift in
     * with this roster still green (review round 1, M1). Same technique as the census's
     * {@code BoundKeys} ({@code PLAN-rule-unknown-keys-gate} Appendix A.1).
     */
    private static Set<String> boundKeys()
    {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DeserializationConfig cfg = mapper.getDeserializationConfig();
        BeanDescription bd = cfg.introspect(mapper.constructType(MatchDataset.class));
        Set<String> bound = new TreeSet<>();
        for (BeanPropertyDefinition p : bd.findProperties())
        {
            if (!(p.hasSetter() || p.hasField() || p.hasConstructorParameter()))
            {
                continue;
            }
            bound.add(p.getName());
            for (PropertyName alias : p.findAliases())
            {
                bound.add(alias.getSimpleName());
            }
        }
        return bound;
    }


    @Test
    void theBoundKeysEqualTheRoster()
    {
        assertEquals(new TreeSet<>(READERS.keySet()), boundKeys(),
                "a Match_Datasets key was bound or unbound without editing this roster — name its"
                        + " production reader here (a bound key nothing reads is the Wildcard"
                        + " defect again)");
    }


    @Test
    void theLoaderListsExactlyTheBoundKeys()
    {
        assertEquals(new TreeSet<>(READERS.keySet()),
                new TreeSet<>(RulePackageLoader.MATCH_DATASET_KEYS),
                "the unknown-key message lists the bound keys; it must not drift from the model");
    }


    @Test
    void everyNamedReaderExists() throws ClassNotFoundException
    {
        for (String reader : READERS.values())
        {
            assertEquals(reader, Class.forName(reader).getName());
        }
    }


    @Test
    void theUnknownKeyChannelExists()
    {
        // Without the collector the gate has nothing to read and passes every rule — the channel
        // must not be removable with nothing red.
        assertTrue(
                Arrays.stream(MatchDataset.class.getDeclaredMethods())
                        .anyMatch(m -> m.isAnnotationPresent(JsonAnySetter.class)),
                "MatchDataset must declare a @JsonAnySetter collecting unbound keys");
    }
}
