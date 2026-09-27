package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.values.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeyMatchIndex}, the hand-rolled open-addressing key table and CSR postings
 * behind the key-match join ({@code PLAN-keymatch-shared-join-index}, proposals P1–P3).
 */
class KeyMatchIndexTest
{

    private static KeyPart p(String aValue)
    {
        return new KeyPart.Present(aValue);
    }


    /** The rows of {@code aKey}, in slice order; empty for a key the index does not carry. */
    private static List<Integer> rowsOf(KeyMatchIndex aIndex, int aId)
    {
        List<Integer> out = new ArrayList<>();
        if (aId == KeyMatchIndex.NOT_FOUND)
        {
            return out;
        }
        for (int pos = aIndex.start(aId); pos < aIndex.end(aId); pos++)
        {
            out.add(aIndex.row(pos));
        }
        return out;
    }


    @Test
    void groupsRowsByKeyInAscendingRowOrder()
    {
        @Nullable
        KeyPart[] keys =
        {
                p("B"), p("A"), p("B"), null, p("A"), p("B")
        };
        KeyMatchIndex index = KeyMatchIndex.build(keys.length, r -> keys[r]);

        assertEquals(List.of(1, 4), rowsOf(index, index.find(p("A"))));
        assertEquals(List.of(0, 2, 5), rowsOf(index, index.find(p("B"))),
                "rows within a key must stay ascending — the order the old List<Long> yielded");
        assertEquals(2, index.keyCount());
        assertEquals(5, index.rowCount(), "the null-keyed row 3 takes no part in the join");
    }


    @Test
    void anAbsentKeyIsNotFound()
    {
        KeyMatchIndex index = KeyMatchIndex.build(2, r -> p("K" + r));
        assertEquals(KeyMatchIndex.NOT_FOUND, index.find(p("nope")));
        assertEquals(KeyMatchIndex.NOT_FOUND, index.find(new KeyPart[]
        {
                p("K0"), p("K1")
        }), "a composite probe never matches a single-component key");
    }


    @Test
    void anEmptyChildBuildsAnEmptyIndex()
    {
        KeyMatchIndex index = KeyMatchIndex.build(0, r -> p("x"));
        assertEquals(0, index.keyCount());
        assertEquals(0, index.rowCount());
        assertEquals(KeyMatchIndex.NOT_FOUND, index.find(p("x")));
    }


    /**
     * Enough distinct keys to grow the slot table many times over, so every probe sequence —
     * including the collisions linear probing resolves — is exercised on both the build and the
     * lookup side.
     */
    @Test
    void survivesManyGrowthsAndFindsEveryKey()
    {
        int n = 50_000;
        KeyMatchIndex index = KeyMatchIndex.build(2 * n, r -> p("S" + (r % n)));
        assertEquals(n, index.keyCount());
        for (int k = 0; k < n; k++)
        {
            assertEquals(List.of(k, k + n), rowsOf(index, index.find(p("S" + k))), "key S" + k);
        }
        assertEquals(KeyMatchIndex.NOT_FOUND, index.find(p("S" + n)));
    }


    /**
     * Key identity is the {@link KeyPart}'s own: a present {@code "."} is not a missing value, a
     * {@code MIS} is not a {@code MIS_A}, and a character {@code "5"} is not a numeric 5.
     */
    @Test
    void keyIdentityIsTheSealedKeyPartsNotARendering()
    {
        KeyPart[] keys =
        {
                p("."), KeyPart.missing(MissingValue.MIS), KeyPart.missing(MissingValue.MIS_A),
                p("5"), new KeyPart.PresentNumber(5.0), KeyPart.EMPTY
        };
        KeyMatchIndex index = KeyMatchIndex.build(keys.length, r -> keys[r]);
        assertEquals(keys.length, index.keyCount(), "every one of these is its own key");
        for (int r = 0; r < keys.length; r++)
        {
            assertEquals(List.of(r), rowsOf(index, index.find(keys[r])), "key " + keys[r]);
        }
    }


    @Test
    void aCompositeKeyIsFoundFromAReusedScratchArray()
    {
        String[][] rows =
        {
                {
                        "P1", "1"
                },
                {
                        "P1", "2"
                },
                {
                        "P2", "1"
                },
                {
                        "P1", "1"
                }
        };
        KeyMatchIndex index = KeyMatchIndex.build(rows.length,
                r -> new KeyMatchIndex.CompositeKey(new KeyPart[]
                {
                        p(rows[r][0]), p(rows[r][1])
                }));
        KeyPart[] scratch = new KeyPart[2];
        scratch[0] = p("P1");
        scratch[1] = p("1");
        assertEquals(List.of(0, 3), rowsOf(index, index.find(scratch)));
        scratch[1] = p("2");
        assertEquals(List.of(1), rowsOf(index, index.find(scratch)), "the scratch array is reused");
        scratch[0] = p("2");
        scratch[1] = p("P1");
        assertEquals(KeyMatchIndex.NOT_FOUND, index.find(scratch), "component order is identity");
        assertEquals(KeyMatchIndex.NOT_FOUND, index.find(p("P1")),
                "a single-component probe never matches a composite key");
    }


    @Test
    void compositeKeyEqualityIsComponentWise()
    {
        KeyMatchIndex.CompositeKey a = new KeyMatchIndex.CompositeKey(new KeyPart[]
        {
                p("P1"), KeyPart.EMPTY
        });
        KeyMatchIndex.CompositeKey b = new KeyMatchIndex.CompositeKey(new KeyPart[]
        {
                p("P1"), KeyPart.EMPTY
        });
        KeyMatchIndex.CompositeKey c = new KeyMatchIndex.CompositeKey(new KeyPart[]
        {
                p("P1"), KeyPart.missing(MissingValue.MIS)
        });
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c, "EMPTY and MIS are different keys (JKM R5)");
        assertNotEquals(a, p("P1"));
        assertEquals("[" + p("P1") + ", " + KeyPart.EMPTY + "]", a.toString());
    }


    @Test
    void specKeyCopiesItsListsAndComparesByContent()
    {
        List<Integer> cols = new ArrayList<>(List.of(0, -1));
        KeyMatchIndex.SpecKey a = new KeyMatchIndex.SpecKey(cols, List.of(true, true),
                List.of(KeyPart.EMPTY), true, false);
        cols.set(1, 7);
        KeyMatchIndex.SpecKey b = new KeyMatchIndex.SpecKey(List.of(0, -1), List.of(true, true),
                List.of(KeyPart.EMPTY), true, false);
        assertEquals(b, a, "a later change to the caller's list must not reach the key");
        assertThrows(UnsupportedOperationException.class, () -> a.childColIds().add(1));
        assertNotEquals(a, new KeyMatchIndex.SpecKey(List.of(0, -1), List.of(true, true),
                List.of(KeyPart.EMPTY), true, true), "asString is part of the key");
    }
}
