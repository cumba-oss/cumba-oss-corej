package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ ⚑ TARGET-INVARIANT(null-free-value-channel), §1b: <b>a {@code MissingValue} IS a joinable key,
 * and this class pins what the engine does BEFORE that is implemented.</b>
 *
 * <p>
 * ⛔⛔ <b>RETIRED CLAIM, corrected 2026-09-21.</b> This javadoc used to assert <i>"a
 * {@code MissingValue} is not a joinable key. Two rows whose {@code Match_Datasets} key cell is
 * MISSING do not join each other — they are two unknowns, not one value."</i> <b>The owner ruled
 * the opposite</b> — twice. 2026-09-19: <i>"MissingValue can be a join key."</i> 2026-09-21, ruling
 * the default: <i>"From my point of view the DROP is the bug we need to fix. … Therefore I rule
 * KEEP is the default and DROP must explicitly be authored if needed."</i> ⇒ the claim was
 * installed by a commit whose whole purpose was to PIN it, which is why it outlived its own
 * retirement in the production javadoc by two days. See {@code PLAN-join-key-missing-semantics} §4
 * and the register ({@code JKM R4}/{@code R5}/{@code R7}).
 * </p>
 *
 * <p>
 * ⭐ <b>What the cases below therefore are: a record of the DROP behaviour, not of the ruled
 * semantics.</b> They stay because the behaviour they pin is still what ships until
 * {@code PLAN-join-key-missing-semantics} phase 4 lands, and a silent change of it is exactly what
 * this class exists to catch. ⛔ When phase 4 lands they must be INVERTED, not deleted — the join
 * then keeps missing-keyed rows, with {@code MIS} joining {@code MIS} and joining neither
 * {@code ""} nor {@code MIS_A} ({@code JKM R5}).
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why this class exists: a real product join behaviour MOVED on 2026-09-18 and nothing
 * recorded it or tested it.</b> {@code KeyMatchRowExpander.tuple} has guarded
 * {@code dv.isMissingOrInvalid()} since the initial commit, but what a raw {@code null} CHARACTER
 * cell wraps to changed underneath it in the datatable repository's {@code d4edd59} ("a null
 * character cell is {@code MissingValue.MIS}, on both wrap paths"):
 * </p>
 * <table border="1">
 * <caption>the composition that moved</caption>
 * <tr>
 * <th></th>
 * <th>{@code getDataValue} for a raw-null char cell</th>
 * <th>{@code isMissingOrInvalid()}</th>
 * <th>the key {@code tuple} builds</th>
 * <th>effect</th>
 * </tr>
 * <tr>
 * <td>before</td>
 * <td>{@code DataValueString("")}</td>
 * <td>{@code false}</td>
 * <td>an EMPTY segment</td>
 * <td>two null-key rows JOINED each other</td>
 * </tr>
 * <tr>
 * <td>at HEAD</td>
 * <td>{@code DataValueMissing(MIS)}</td>
 * <td>{@code true}</td>
 * <td>{@code null} ⇒ no key</td>
 * <td>they do not join at all</td>
 * </tr>
 * </table>
 *
 * <p>
 * ⭐ <b>Reachable on real data, not a thought experiment:</b> an {@code .rds} dataset whose join-key
 * character column carries an R {@code NA} — {@code factor(..., exclude = NULL)}, which
 * {@code RdataTableProvider.copyFactorData} stores as an explicit null LEVEL. That is the one
 * shipped read path where {@code d4edd59} is visible, and the shape
 * {@code RdataTableProviderRealFixturesTest} was re-based for.
 * </p>
 *
 * <p>
 * ⭐ <b>The new behaviour is the CORRECT one and is deliberately not changed here</b> — §1b: a
 * missing cell is that cell's own {@code MissingValue}, never the empty string, and an unknown
 * identifier cannot be equal to another unknown identifier. It also restores agreement with the
 * behaviour this class' enclosing file cites as its model: <i>"pandas drops NaN keys"</i>
 * ({@code buildChildIndex}). What was missing was a test, so the next change to the wrap path
 * cannot move a product join silently again.
 * </p>
 *
 * <p>
 * ⚠ <b>The fixture uses {@link MockTable#colSasMissing} and NOT a {@code null} element of
 * {@link MockTable#col}, deliberately.</b> {@code MockTable.mockDataValue}'s null arm was itself
 * corrected on the same day (the datatable repository's {@code d1585c9}), so a fixture built on it
 * asserts one thing against a freshly installed testkit and the opposite against a stale one.
 * {@code colSasMissing}'s missing cell has answered {@code MissingValue.MIS} / {@code "."} /
 * {@code isMissingOrInvalid() == true} across that boundary, so this test measures the ENGINE and
 * not which testkit jar happens to be in the local repository.
 * </p>
 */
class KeyMatchMissingJoinKeyTest
{

    private static final String AE = "AE";

    private static final String USUBJID = "USUBJID";

    /** {@code USUBJID} is present on row 0 and MISSING on row 1, on both sides of the join. */
    private static IDataTable withMissingKeyOnRowOne(String name, String payloadCol,
            String... payload)
    {
        return MockTable.of().colSasMissing(USUBJID, "P1", null).col(payloadCol, payload).name(name)
                .build();
    }


    private static MatchDataset md(String joinType)
    {
        MatchDataset m = new MatchDataset();
        m.setName(AE);
        m.setKeys(List.of(USUBJID));
        m.setJoinType(joinType);
        return m;
    }


    private static KeyMatchRowExpander.KeyMatchExpansion expand(String joinType)
    {
        IDataTable dm = withMissingKeyOnRowOne("DM", "AGE", "34", "51");
        IDataTable ae = withMissingKeyOnRowOne(AE, "AETERM", "HEADACHE", "NAUSEA");
        var exp = KeyMatchRowExpander.expand(dm, List.of(md(joinType)),
                Map.of("DM", dm, AE, ae)::get, "R-TEST");
        assertNotNull(exp, "the expansion must be built — one key entry is expandable");
        return exp;
    }


    /** "primaryRow:childValue" per expanded row, the same reading as KeyMatchRowExpanderTest. */
    private static List<String> rows(KeyMatchRowExpander.KeyMatchExpansion exp, String col)
    {
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookups().get(AE);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, col));
        }
        return out;
    }


    /**
     * ⭐⭐ The discriminating assertion, <b>INVERTED 2026-09-21 for {@code JKM R4}</b>. Both rows now
     * survive an {@code inner} join, because both key cells carry <b>the same</b>
     * {@code MissingValue} and a missing is a normal value: <i>"There is no reason to remove a row
     * from the merge because there is a missing value in one of the keys. Especially if both tables
     * have rows that would match up."</i>
     *
     * <p>
     * ⛔ This test previously asserted {@code List.of("0:HEADACHE")} — the DROP behaviour — with the
     * message <i>"a MissingValue key is not joinable"</i>. That claim is retired; the assertion is
     * INVERTED rather than deleted, so the change of behaviour is visible in the diff of the very
     * test that pinned the old one.
     * </p>
     */
    @Test
    void anInnerJoinKEEPSTheMissingKeyRowsAndPairsThemWithEachOther()
    {
        assertEquals(List.of("0:HEADACHE", "1:NAUSEA"), rows(expand("inner"), "AETERM"),
                "JKM R4: both sides carry the SAME MissingValue, so the rows pair. Seeing only"
                        + " '0:HEADACHE' means the drop is back");
    }


    /**
     * ⭐ The same fact from the other side, also inverted: under a {@code left} join the missing-key
     * primary row survives <b>bound</b>. ⚠ Both join types now give the same row count here, so
     * this arm no longer distinguishes "survived because it matched" from "survived because left
     * keeps unmatched rows" — the binding, not the count, is what it asserts.
     */
    @Test
    void aLeftJoinBindsTheMissingKeyRowToTheEquallyMissingChildRow()
    {
        var exp = expand("left");
        assertEquals(2, exp.table().getRowCount(), "both primary rows survive");
        assertEquals(List.of("0:HEADACHE", "1:NAUSEA"), rows(exp, "AETERM"),
                "JKM R4: the missing-key primary row is BOUND now. '1:null' would mean the drop is"
                        + " back");
    }


    /**
     * ⛔⛔ <b>THIS TEST DOES NOT TEST WHAT ITS NAME SAYS, and the correction is recorded rather than
     * the name quietly changed.</b> Measured 2026-09-21 while writing {@code MissingAsMemberTest}:
     * {@code MockTable.colSasMissing} turns <b>only a {@code null}</b> entry into a
     * {@code MissingValue}, and always into {@code MissingValue.MIS} — a string like {@code ".A"}
     * is a <b>PRESENT string</b> ({@code MockTable.mockSasMissingDataValue}). So the fixture below
     * pins <i>"the text `.A` does not join the text `.B`"</i>, not <i>"`MIS_A` does not join
     * `MIS_B`"</i>.
     *
     * <p>
     * ⚠ It is still a live assertion — its sabotage (folding every key part into one bucket) reds
     * it — but it reds for the PRESENT-value path, not the marker path. ⇒ <b>{@code JKM R5}'s
     * marker-distinctness rows remain UNPINNED on the join path</b>, and the reason is a testkit
     * limitation, not an oversight: nothing in {@code MockTable} can put a specific marker in a
     * cell. Closing it needs a testkit capability (a {@code colMissingMarker(name, MissingValue…)}
     * kind) in {@code cumba-datatable}, which is another repo and another plan.
     * </p>
     *
     * <p>
     * ⭐ Where the property IS pinned: {@code MissingAsMemberTest} asserts it from the <b>member</b>
     * side, where a {@link net.cumba.datatable.values.MissingValue} can be named directly — a
     * {@code MIS} cell is not a member of {@code {MIS_A}}.
     * </p>
     */
    @Test
    void differentPresentSasSpellingsAreDifferentKeys()
    {
        // ⚠ ".A" / ".B" / "." are PRESENT strings here — see the javadoc. The distinctness this
        // pins
        // is textual.
        IDataTable dm = MockTable.of().colSasMissing(USUBJID, ".A", ".B").col("AGE", "34", "51")
                .name("DM").build();
        IDataTable ae = MockTable.of().colSasMissing(USUBJID, ".A", ".")
                .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
        var exp = KeyMatchRowExpander.expand(dm, List.of(md("left")), Map.of("DM", dm, AE, ae)::get,
                "R-TEST");
        assertNotNull(exp);
        assertEquals(List.of("0:HEADACHE", "1:null"), rows(exp, "AETERM"),
                "row 0 pairs \".A\" with \".A\"; row 1's \".B\" matches neither \".A\" nor"
                        + " \".\". A '1:NAUSEA' means two different values were folded together");
    }


    /**
     * ⭐⭐ {@code JKM R5} — the assertion the KEEP default makes load-bearing: <i>"a MIS will not
     * join a record with an empty string and a MIS_A will not join a record with a MIS or
     * MIS_B."</i>
     *
     * <p>
     * ⛔ <b>This is the case a stringified key gets WRONG</b>, and it is why the key is built from
     * {@code GroupKeyPolicy.KeyPart}: {@code MissingValue.toString()} renders its display string,
     * so a {@code \0}-joined {@code getValueAsString()} key would make a SAS missing on one side
     * equal a genuine text cell holding that same rendering on the other — measured elsewhere at +9
     * 528 findings over 8 rules.
     * </p>
     */
    @Test
    void aMissingKeyJoinsNeitherAPresentDotNorAnEmptyString()
    {
        IDataTable dm = MockTable.of().colSasMissing(USUBJID, "P1", null, null)
                .col("AGE", "34", "51", "62").name("DM").build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1", ".", "")
                .col("AETERM", "HEADACHE", "DOTTED", "BLANK").name(AE).build();
        var exp = KeyMatchRowExpander.expand(dm, List.of(md("left")), Map.of("DM", dm, AE, ae)::get,
                "R-TEST");
        assertNotNull(exp);
        assertEquals(List.of("0:HEADACHE", "1:null", "2:null"), rows(exp, "AETERM"),
                "JKM R5: a MissingValue joins neither a present dot nor a present empty string."
                        + " '1:DOTTED' is the stringified-key collision; '2:BLANK' is the"
                        + " empty-string conflation");
    }


    /**
     * ⭐ Non-vacuity control: the fixture must really carry a MISSING key on row 1 and a real one on
     * row 0. Without this the two tests above would pass just as well over a fixture whose key
     * column was absent, or whose row 1 held an ordinary value that simply did not match — neither
     * of which measures the missing-key guard.
     */
    @Test
    void theFixtureReallyCarriesAMissingKeyCellAndAPresentOne()
    {
        IDataTable dm = withMissingKeyOnRowOne("DM", "AGE", "34", "51");
        int col = dm.getMetaData().getColumnIndex(USUBJID);
        assertTrue(col >= 0, "CONTROL FAILED: the key column must be PRESENT — an absent column"
                + " takes tuple()'s other arm (colIds[i] < 0) and would pass for the wrong reason");
        assertTrue(!dm.getColumn(col).getDataValue(0).isMissingOrInvalid(),
                "CONTROL FAILED: row 0's key must be a real value");
        assertTrue(dm.getColumn(col).getDataValue(1).isMissingOrInvalid(),
                "CONTROL FAILED: row 1's key must read as MISSING, or the guard under test is"
                        + " never entered and both tests above are vacuous");
        assertTrue(!(dm.getColumn(col).getDataValue(1).getValue() instanceof String),
                "CONTROL FAILED: a missing key cell must not carry a String value — that is the"
                        + " pre-d4edd59 encoding this test exists to keep out");
    }
}
