package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code Rule.setCheck} &harr; {@code Rule.setCheckLevels} invariant (Plan C &#167;3.3), from
 * the setters' own side.
 *
 * <p>
 * ⛔ Both tests pin review findings, not hypotheticals: {@code setCheck} used to {@code put(...)}
 * into the unmodifiable map {@code setCheckLevels} stores — so calling {@code setCheck}
 * <em>after</em> {@code setCheckLevels} always threw {@code UnsupportedOperationException}, and the
 * documented sync mechanism was reachable only by the (undocumented) call ordering the three
 * {@code gen/} clone sites happen to use. And {@code setCheckLevels} enforced no ladder order at
 * all — order lived only in {@code RuleCheckDeserializer.bindLevels}, so a map handed in
 * programmatically weakest-first would have let the weakest rung claim first with nothing noticing.
 * </p>
 */
class RuleCheckLevelSetterTest
{

    private static CheckConditionExpression cond(String aName)
    {
        String source = "not empty(" + aName + ")";
        return new CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    @Test
    @DisplayName("setCheck AFTER setCheckLevels succeeds and keeps the invariant")
    void setCheckAfterSetCheckLevelsRebuildsTheMap()
    {
        Rule r = new Rule();
        SequencedMap<Severity, LevelCheck> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, new LevelCheck(cond("A"), "strict message"));
        levels.put(Severity.INFO, new LevelCheck(cond("B"), null));
        r.setCheckLevels(levels);

        CheckCondition rewritten = cond("A2");
        assertDoesNotThrow(() -> r.setCheck(rewritten),
                "⛔ setCheck must REBUILD the (unmodifiable) level map, never mutate it in place");

        SequencedMap<Severity, LevelCheck> got = r.getCheckLevels();
        assertNotNull(got);
        assertEquals(List.of(Severity.ERROR, Severity.INFO), List.copyOf(got.keySet()),
                "the rebuild preserves the ladder order");
        assertSame(rewritten, got.get(Severity.ERROR).condition(),
                "the strictest entry now holds the new condition — the setter's whole point");
        assertEquals("strict message", got.get(Severity.ERROR).message(),
                "the strictest level's own Message survives the rebuild");
        assertSame(rewritten, r.getCheck(), "check IS the strictest entry");
        assertEquals(cond("B"), got.get(Severity.INFO).condition(),
                "the weaker level is untouched");

        // And clearing still clears both sides.
        r.setCheck(null);
        assertNull(r.getCheck());
        assertNull(r.getCheckLevels());
    }


    @Test
    @DisplayName("setCheckLevels normalises an out-of-order map to the ladder")
    void setCheckLevelsNormalisesToLadderOrder()
    {
        Rule r = new Rule();
        // Weakest-first on purpose — the shape only a programmatic caller can produce, since the
        // deserialiser already sorts.
        SequencedMap<Severity, LevelCheck> unsorted = new LinkedHashMap<>();
        unsorted.put(Severity.INFO, new LevelCheck(cond("B"), null));
        unsorted.put(Severity.WARNING, new LevelCheck(cond("C"), "warn message"));
        unsorted.put(Severity.ERROR, new LevelCheck(cond("A"), null));
        r.setCheckLevels(unsorted);

        SequencedMap<Severity, LevelCheck> got = r.getCheckLevels();
        assertNotNull(got);
        assertEquals(List.of(Severity.ERROR, Severity.WARNING, Severity.INFO),
                List.copyOf(got.keySet()),
                "⛔ evaluation iterates this map in order — unsorted, the WEAKEST rung would claim "
                        + "every unit first and nothing would notice");
        assertEquals(cond("A"), r.getCheck(),
                "check is the STRICTEST entry of the normalised map, not the caller's first entry");
        assertEquals("warn message", got.get(Severity.WARNING).message(),
                "each level's Message rides through the normalisation");
    }

}
