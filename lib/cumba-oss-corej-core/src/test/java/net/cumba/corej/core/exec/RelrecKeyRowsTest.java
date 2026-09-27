package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RelrecRowExpander.KeyRows;
import net.cumba.corej.core.exec.RelrecRowExpander.RelrecKey;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The RELREC value index's {@code int[]} CSR shape ({@code PLAN-shared-key-identity} P-R1), checked
 * against the {@code Map<key, List<rows>>} it replaced: for every key, the same rows in the same
 * ascending order, whatever the table's size (the slot table grows past its first 16 slots at the
 * ninth key). The manager twin ({@code cumba-datatable-manager-local}) carries the same test. The
 * join semantics on top of it are pinned end to end by {@code RelrecRowExpanderTest} and
 * {@code RelrecMissingSubjectKeyTest}.
 */
class RelrecKeyRowsTest
{

    /** A key per row: 3 studies (one a missing marker), 40 subjects, one skipped row in 7. */
    private static @Nullable RelrecKey keyOf(int aRow)
    {
        if (aRow % 7 == 3)
        {
            return null;
        }
        Object study = switch (aRow % 3)
        {
        case 0 -> "S1";
        case 1 -> "S2";
        default -> MissingValue.MIS_A;
        };
        return new RelrecKey(study, "SUBJ-" + (aRow % 40), aRow % 2 == 0 ? "1" : "");
    }


    @Test
    void theIndexHoldsWhatAMapOfListsWould()
    {
        int rowCount = 1000;
        Map<RelrecKey, List<Integer>> oracle = new HashMap<>();
        List<RelrecKey> firstSeen = new ArrayList<>();
        List<Object> studies = new ArrayList<>();
        for (int r = 0; r < rowCount; r++)
        {
            RelrecKey key = keyOf(r);
            if (key == null)
            {
                continue;
            }
            if (!oracle.containsKey(key))
            {
                firstSeen.add(key);
            }
            if (!studies.contains(key.study()))
            {
                studies.add(key.study());
            }
            oracle.computeIfAbsent(key, _ -> new ArrayList<>()).add(r);
        }

        KeyRows index = KeyRows.build(rowCount, RelrecKeyRowsTest::keyOf);

        assertTrue(oracle.size() > 16, "the population must outgrow the first slot table");
        assertEquals(oracle.size(), index.keyCount());
        assertEquals(studies, index.studies(), "distinct studies, first-appearance order");
        for (int id = 0; id < index.keyCount(); id++)
        {
            RelrecKey key = index.key(id);
            assertEquals(firstSeen.get(id), key, "ids run in first-appearance order");
            assertEquals(id, index.find(key));
            // an equal key built afresh finds the same id
            assertEquals(id, index.find(new RelrecKey(key.study(), key.subject(), key.value())));
            List<Integer> rows = new ArrayList<>();
            for (int pos = index.start(id); pos < index.end(id); pos++)
            {
                rows.add(index.row(pos));
            }
            assertEquals(oracle.get(key), rows, () -> "rows of " + key);
        }
    }


    @Test
    void anAbsentKeyIsNotFound()
    {
        KeyRows index = KeyRows.build(100, RelrecKeyRowsTest::keyOf);
        assertEquals(KeyRows.NOT_FOUND, index.find(new RelrecKey("S9", "SUBJ-1", "1")));
        // a missing marker never finds the text that spells it, nor another marker
        assertNotEquals(KeyRows.NOT_FOUND,
                index.find(new RelrecKey(MissingValue.MIS_A, "SUBJ-2", "1")));
        assertEquals(KeyRows.NOT_FOUND, index.find(new RelrecKey("MIS_A", "SUBJ-2", "1")));
        assertEquals(KeyRows.NOT_FOUND,
                index.find(new RelrecKey(MissingValue.MIS_B, "SUBJ-2", "1")));
    }


    @Test
    void anEmptyTableHasNoKeys()
    {
        KeyRows none = KeyRows.build(0, _ -> null);
        assertEquals(0, none.keyCount());
        assertTrue(none.studies().isEmpty());
        assertEquals(KeyRows.NOT_FOUND, none.find(new RelrecKey("S1", "SUBJ-1", "1")));

        KeyRows skipped = KeyRows.build(50, _ -> null);
        assertEquals(0, skipped.keyCount());
        assertEquals(KeyRows.NOT_FOUND, skipped.find(new RelrecKey("S1", "SUBJ-1", "1")));
    }


    @Test
    void aKeyIsEqualByItsThreeComponentsOnly()
    {
        RelrecKey key = new RelrecKey("S1", MissingValue.MIS_A, "1");
        RelrecKey same = new RelrecKey(String.valueOf("S1".toCharArray()), MissingValue.MIS_A, "1");
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
        assertNotEquals(key, new RelrecKey("S2", MissingValue.MIS_A, "1"));
        assertNotEquals(key, new RelrecKey("S1", MissingValue.MIS_B, "1"));
        assertNotEquals(key, new RelrecKey("S1", "MIS_A", "1"));
        assertNotEquals(key, new RelrecKey("S1", MissingValue.MIS_A, "2"));
        assertNotEquals(key, (Object) "S1");
        assertEquals("(S1, " + MissingValue.MIS_A + ", 1)", key.toString());
    }
}
