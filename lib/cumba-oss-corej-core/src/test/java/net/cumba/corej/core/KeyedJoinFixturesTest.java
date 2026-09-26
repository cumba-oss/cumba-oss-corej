package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * {@link KeyedJoinFixtures} adds what is missing and repairs nothing (review round 4, H5): the
 * cases where it must fail loudly instead of quietly shaping a fixture into something the test did
 * not write.
 */
class KeyedJoinFixturesTest
{

    private static final String KEYED = "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]";

    @Test
    void addsOnlyWhatIsMissing()
    {
        String declared = KeyedJoinFixtures.declared("{\"Core\":{\"Id\":\"R\"}," + KEYED
                + ",\"Requirements\":{\"Variables\":{\"All\":[\"AGE\"]}}}");
        assertEquals("{\"Core\":{\"Id\":\"R\"}," + KEYED + ",\"Requirements\":{\"Variables\":"
                + "{\"All\":[\"AGE\",\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}}}",
                declared);
        // Idempotent: a declared fixture is returned unchanged.
        assertEquals(declared, KeyedJoinFixtures.declared(declared));
    }


    @Test
    void aDuplicateKeyIsRefusedAsTheLoaderRefusesIt()
    {
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> KeyedJoinFixtures
                .declared("{\"Core\":{\"Id\":\"R\"},\"Core\":{\"Id\":\"S\"}}"));
        assertTrue(e.getMessage().contains("Duplicate field 'Core'"), e.getMessage());
    }


    @Test
    void aNonObjectRequirementsOrVariablesIsNotOverwritten()
    {
        IllegalArgumentException req = assertThrows(IllegalArgumentException.class,
                () -> KeyedJoinFixtures.declared("{" + KEYED + ",\"Requirements\":[]}"));
        assertTrue(req.getMessage().contains("Requirements is ARRAY, not an object"),
                req.getMessage());
        IllegalArgumentException vars = assertThrows(IllegalArgumentException.class,
                () -> KeyedJoinFixtures
                        .declared("{" + KEYED + ",\"Requirements\":{\"Variables\":\"x\"}}"));
        assertTrue(vars.getMessage().contains("Variables is STRING, not an object"),
                vars.getMessage());
    }


    @Test
    void aNonArrayFacetIsNotOverwritten()
    {
        IllegalArgumentException all = assertThrows(IllegalArgumentException.class,
                () -> KeyedJoinFixtures
                        .declared("{" + KEYED + ",\"Requirements\":{\"Variables\":{\"All\":{}}}}"));
        assertTrue(all.getMessage().contains("All is OBJECT, not an array"), all.getMessage());
        IllegalArgumentException aon = assertThrows(IllegalArgumentException.class,
                () -> KeyedJoinFixtures.declared("{" + KEYED
                        + ",\"Requirements\":{\"Variables\":{\"All_Or_None\":\"USUBJID\"}}}"));
        assertTrue(aon.getMessage().contains("All_Or_None is STRING, not an array"),
                aon.getMessage());
    }


    @Test
    void aNearMissSiblingOfAFacetToAddIsRefused()
    {
        // `Al` is one edit from `All`; adding `All` beside it would make the loader's hint for
        // the typo disappear (the hint is suppressed once the object carries the candidate).
        IllegalArgumentException al = assertThrows(IllegalArgumentException.class,
                () -> KeyedJoinFixtures.declared(
                        "{" + KEYED + ",\"Requirements\":{\"Variables\":{\"Al\":[\"USUBJID\"]}}}"));
        assertTrue(al.getMessage().contains("'Al', a near miss of 'All'"), al.getMessage());
        IllegalArgumentException aon = assertThrows(IllegalArgumentException.class,
                () -> KeyedJoinFixtures.declared("{" + KEYED + ",\"Requirements\":{\"Variables\":"
                        + "{\"All\":[\"USUBJID\"],\"All_or_None\":[]}}}"));
        assertTrue(aon.getMessage().contains("'All_or_None', a near miss of 'All_Or_None'"),
                aon.getMessage());
        // A sibling that is no near miss of anything to be added is left alone (the unknown-key
        // gate's generic plants pass through here).
        KeyedJoinFixtures.declared(
                "{" + KEYED + ",\"Requirements\":{\"Variables\":{\"Xyzzy\":[\"USUBJID\"]}}}");
    }
}
