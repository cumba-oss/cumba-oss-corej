package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@code JKM R5} on the <b>hashed</b> arm: {@code KeyHashing.KeyMatcher} now compares
 * {@link GroupKeyPolicy.KeyPart}s, not raw column values.
 *
 * <p>
 * ⚠⚠ <b>Why this class exists at all: the change passed 5 475 existing tests without moving one of
 * them.</b> The arm serves the {@code Child:true} entries, and {@code .cdt} — which every corpus
 * scenario is written in — cannot express the inputs that distinguish the raw channel from the
 * typed one. A green suite was therefore evidence of nothing, and these are the cases that actually
 * discriminate.
 * </p>
 *
 * <p>
 * The sharpest is {@link #aLongKeyAndADoubleKeyOfTheSameNumberMatch()}: it is a <b>defect fix</b>,
 * not a refactor. {@code Objects.equals(Long 2, Double 2.0)} is {@code false}, so the same numeric
 * subject/visit key stored as an integer in one dataset and a float in the other did not join —
 * while {@code KeyPart.PresentNumber}'s own javadoc rules that <i>"a LONG 2 and a DOUBLE 2.0 keyed
 * identically before and must keep doing so"</i>.
 * </p>
 */
class KeyMatcherTypedIdentityTest
{

    private static final String KEY = "VISITNUM";

    private static DatasetLookup lookupOver(IDataTable joined)
    {
        DatasetLookup lk = DatasetLookup.build("AE", joined, List.of(KEY));
        assertNotNull(lk, "a keyed lookup over a non-null table must be built");
        return lk;
    }


    /**
     * ⭐⭐ The discriminating case, and a real defect fix: the same number, typed differently on the
     * two sides, is one key.
     */
    @Test
    void aLongKeyAndADoubleKeyOfTheSameNumberMatch()
    {
        IDataTable primary = MockTable.of().colLong(KEY, 2L).col("AETERM", "HEADACHE").name("DM")
                .build();
        IDataTable joined = MockTable.of().colDouble(KEY, 2.0).col("AGE", "34").name("AE").build();
        assertTrue(lookupOver(joined).matchedRow(primary, 0L),
                "JKM R5 / KeyPart.PresentNumber: LONG 2 and DOUBLE 2.0 are ONE numeric key."
                        + " Objects.equals(Long, Double) was false, so this pair did not join before"
                        + " — a defect, not a behaviour this test may accept");
        assertEquals("34", lookupOver(joined).lookup(primary, 0L, "AGE"),
                "and the matched row's value must be readable through the same lookup");
    }


    /**
     * ⭐ {@code JKM R5}, the identity half: a blank numeric key is a {@code MissingValue} and pairs
     * only the same marker. ⚠ This is the case a rendered key gets wrong — both sides render as
     * {@code "."} — and it is why the identity is a sealed type rather than a string.
     */
    @Test
    void aMissingNumericKeyPairsTheSameMarkerAndNotAPresentValue()
    {
        IDataTable primary = MockTable.of().colLong(KEY, (Long) null).col("AETERM", "X").name("DM")
                .build();
        IDataTable joinedMissing = MockTable.of().colLong(KEY, (Long) null).col("AGE", "34")
                .name("AE").build();
        IDataTable joinedPresent = MockTable.of().colLong(KEY, 2L).col("AGE", "34").name("AE")
                .build();
        assertTrue(lookupOver(joinedMissing).matchedRow(primary, 0L),
                "JKM R4/R5: two cells carrying the SAME missing marker are one key");
        assertFalse(lookupOver(joinedPresent).matchedRow(primary, 0L),
                "a missing key must not pair a PRESENT 2. A match here means the missing was"
                        + " rendered into a participating value");
    }


    /**
     * ⭐ Non-vacuity control. Without it every assertion above would pass just as well over a lookup
     * that matched everything — which is exactly what {@code computeKeyHashSafe} does when every
     * key column is absent, so this is not a theoretical concern in this class.
     */
    @Test
    void theLookupDoesNotSimplyMatchEverything()
    {
        IDataTable primary = MockTable.of().colLong(KEY, 2L).col("AETERM", "X").name("DM").build();
        IDataTable joined = MockTable.of().colLong(KEY, 7L).col("AGE", "34").name("AE").build();
        assertFalse(lookupOver(joined).matchedRow(primary, 0L),
                "control: 2 and 7 are different keys. A match means the matcher is all-true and"
                        + " every other assertion in this class is vacuous");
    }
}
