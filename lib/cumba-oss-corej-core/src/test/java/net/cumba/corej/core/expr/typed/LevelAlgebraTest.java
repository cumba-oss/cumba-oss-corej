package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.SetOf;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import org.junit.jupiter.api.Test;

/**
 * The §1.3 level product and the §1.1 type table: the join is granularity × cursor independently
 * (D5), two groups over different keys are incomparable and join to record (D39e), group keys carry
 * set semantics (D28d), and the {@code group × cursor} cell is unrepresentable (D67).
 */
class LevelAlgebraTest
{

    private static Level group(String... keys)
    {
        return new Level(new Granularity.Group(new LinkedHashSet<>(Set.of(keys))), Cursor.ABSENT);
    }


    @Test
    void joinFollowsTheGranularityChain()
    {
        assertEquals(Level.DATASET, Level.STUDY.join(Level.DATASET));
        assertEquals(Level.RECORD, Level.DATASET.join(Level.RECORD));
        assertEquals(group("USUBJID"), Level.DATASET.join(group("USUBJID")));
        assertEquals(Level.RECORD, group("USUBJID").join(Level.RECORD));
    }


    @Test
    void joinIsIndependentPerAxis()
    {
        // dataset×present ⊔ record×absent = record×present — the product, not a chain (D30a).
        assertEquals(Level.VARIABLE_VALUE, Level.VARIABLE_METADATA.join(Level.RECORD));
        assertEquals(Level.VARIABLE_METADATA, Level.VARIABLE_METADATA.join(Level.STUDY));
    }


    @Test
    void groupKeysHaveSetSemantics()
    {
        // D28d: a different key ORDER does not split a grouping.
        Level a = new Level(
                new Granularity.Group(new LinkedHashSet<>(java.util.List.of("USUBJID", "AESEQ"))),
                Cursor.ABSENT);
        Level b = new Level(
                new Granularity.Group(new LinkedHashSet<>(java.util.List.of("AESEQ", "USUBJID"))),
                Cursor.ABSENT);
        assertEquals(a.granularity(), b.granularity());
        assertEquals(a, a.join(b));
    }


    @Test
    void groupsOverDifferentKeysJoinToRecord()
    {
        // D39e: incomparable groups make the finest level record.
        assertEquals(Level.RECORD, group("USUBJID").join(group("STUDYID")));
    }


    @Test
    void theExcludedCellIsUnrepresentable()
    {
        assertThrows(ExcludedLevelCellException.class,
                () -> new Level(new Granularity.Group(Set.of("USUBJID")), Cursor.PRESENT));
        // ... and the join cannot sneak into it either.
        assertThrows(ExcludedLevelCellException.class,
                () -> group("USUBJID").join(Level.VARIABLE_METADATA));
    }


    @Test
    void raisingKeepsTheCursor()
    {
        assertEquals(Level.VARIABLE_METADATA,
                Level.VARIABLE_VALUE.raise(Granularity.Simple.DATASET));
        assertEquals(Level.DATASET, Level.RECORD.raise(Granularity.Simple.DATASET));
        assertThrows(ExcludedLevelCellException.class,
                () -> Level.VARIABLE_VALUE.raise(new Granularity.Group(Set.of("USUBJID"))));
    }


    @Test
    void emptyGroupKeysAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> new Granularity.Group(Set.of()));
    }


    @Test
    void describeNamesTheCell()
    {
        assertEquals("dataset × cursor", Level.VARIABLE_METADATA.describe());
        assertEquals("record", Level.RECORD.describe());
        assertEquals("group(USUBJID)", group("USUBJID").granularity().describe());
    }


    @Test
    void typeCompatibilityIsKnownVersusKnown()
    {
        assertTrue(ExprType.compatible(Unknown.UNKNOWN, Primitive.NUMBER));
        assertTrue(ExprType.compatible(Primitive.STRING, Primitive.STRING));
        assertFalse(ExprType.compatible(Primitive.STRING, Primitive.NUMBER));
        assertFalse(ExprType.compatible(Primitive.STRING, new ListOf(Primitive.STRING)));
        assertTrue(ExprType.compatible(new ListOf(Primitive.STRING), new SetOf(Primitive.STRING)));
        assertFalse(ExprType.compatible(new ListOf(Primitive.STRING), new SetOf(Primitive.NUMBER)));
        assertTrue(ExprType.compatible(new SetOf(Unknown.UNKNOWN), new ListOf(Primitive.NUMBER)));
    }


    @Test
    void columnReferencesDereferenceToUnknown()
    {
        assertEquals(Unknown.UNKNOWN, Primitive.COLUMN_REFERENCE.dereference());
        assertEquals(Primitive.NUMBER, Primitive.NUMBER.dereference());
        assertEquals("column-reference", Primitive.COLUMN_REFERENCE.describe());
        assertEquals("list<string>", new ListOf(Primitive.STRING).describe());
        assertEquals("set<number>", new SetOf(Primitive.NUMBER).describe());
        assertEquals("unknown", Unknown.UNKNOWN.describe());
        assertEquals(Primitive.NUMBER, ExprType.elementOf(new ListOf(Primitive.NUMBER)));
        assertEquals(Primitive.NUMBER, ExprType.elementOf(new SetOf(Primitive.NUMBER)));
        assertEquals(Unknown.UNKNOWN, ExprType.elementOf(Primitive.STRING));
    }

}
