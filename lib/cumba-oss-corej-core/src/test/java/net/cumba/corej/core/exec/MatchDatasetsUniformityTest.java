package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.joined;
import static net.cumba.corej.core.exec.QualifiedNameFixture.load;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.primary;
import static net.cumba.corej.core.exec.QualifiedNameFixture.rows;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 1 — the {@code Match_Datasets} surface:
 * {@code Keys} (plain and sided), {@code Filter} and {@code Child}. A {@code Filter} is evaluated
 * on the joined dataset's OWN rows, so its bare names are that dataset's columns and a dotted name
 * there is a LEFT reference — refused at load as {@code FILTER_LEFT_REFERENCE} (D89, armed). A
 * dotted read of a {@code Child} entry's column is refused as {@code DOTTED_REF_CHILD_ENTRY}
 * (armed): the child's columns are merged BARE into the primary (D62b). Both refusals are loud and
 * ruled, so they are recorded as "written bare" exemptions (N5's shape), not findings.
 */
class MatchDatasetsUniformityTest
{

    private static String keyed(String keys, String extra)
    {
        return "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[" + keys + "],\"Join_Type\":\"left\""
                + extra + "}]";
    }


    @Test
    void aSidedKeySpellingJoinsExactlyLikeThePlainOne()
    {
        // J carries DIFFERENT S values (T2 r1 M5): a join that read the primary instead would
        // answer nothing here, and the two spellings must agree on the rows that differ.
        IDataTable p = primary();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("S", "a", "x", "", "A", "y", "c").build();
        Rule plain = loadClean(
                ruleJson("Record",
                        keyed("\"K\"", "") + "," + check("S != J.S") + "," + outcome("S", "J.S")),
                true);
        Rule sided = loadClean(ruleJson("Record", keyed("{\"left\":\"K\",\"right\":\"K\"}", "")
                + "," + check("S != J.S") + "," + outcome("S", "J.S")), true);
        RuleExecutionResult a = executed(plain, p, exactInventory(p, j));
        RuleExecutionResult b = executed(sided, p, exactInventory(p, j));
        assertEquals(List.of(2L, 5L), rows(a), "rows whose J.S differs from S");
        assertEquals("x", QualifiedNameFixture.values(a).get(0).get("J.S"));
        assertEquals(rows(a), rows(b));
        assertEquals(QualifiedNameFixture.values(a), QualifiedNameFixture.values(b));
    }


    @Test
    void aBareFilterNameReadsTheJoinedDatasetsOwnColumn()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        Rule filtered = loadClean(
                ruleJson("Record", keyed("\"K\"", ",\"Filter\":\"S == \\\"b\\\"\"") + ","
                        + check("J.S != \"\"") + "," + outcome("S", "J.S")),
                true);
        RuleExecutionResult r = executed(filtered, p, exactInventory(p, j));
        assertEquals(List.of(2L, 5L), rows(r),
                "only J rows with S == \"b\" join; the others read the unmatched default \"\"");
        assertEquals("b", QualifiedNameFixture.values(r).get(0).get("J.S"));
    }


    /**
     * A dotted name in a Filter is a LEFT reference: refused at load (D89 / FILTER_LEFT_REFERENCE).
     */
    @Test
    void aDottedFilterNameIsRefusedAtLoad()
    {
        Rule r = load(ruleJson("Record", keyed("\"K\"", ",\"Filter\":\"J.S == \\\"b\\\"\"") + ","
                + check("J.S != \"\"") + "," + outcome("S")), true);
        assertNotNull(r.getLoadError(), "FILTER_LEFT_REFERENCE is armed");
        assertTrue(r.getLoadError().contains("J.S"), r.getLoadError());
    }


    @Test
    void aChildEntryIsReadBareAndItsDottedReadIsRefusedAtLoad()
    {
        // The Child shape of CDISC-CG0371: the primary is the CHILD side (IDVAR / IDVARVAL point
        // at the parent record named by RDOMAIN), and the parent's columns are pre-merged BARE into
        // it (D62b).
        IDataTable p = RealTables.of("P").str("USUBJID", "k1", "k2").str("RDOMAIN", "C", "C")
                .str("IDVAR", "TSEQ", "TSEQ").str("IDVARVAL", "1", "2").build();
        IDataTable c = RealTables.of("C").str("USUBJID", "k1", "k2").str("TSEQ", "1", "2")
                .str("T", "x", "y").build();
        String childEntry = "\"Match_Datasets\":[{\"Name\":\"C\",\"Keys\":[\"USUBJID\","
                + "\"IDVAR\",\"IDVARVAL\"],\"Child\":true}]";
        Rule bare = loadClean(
                ruleJson("Record",
                        childEntry + "," + check("T == \"x\"") + "," + outcome("USUBJID", "T")),
                true);
        RuleExecutionResult r = executed(bare, p, exactInventory(p, c));
        assertEquals(List.of(1L), rows(r), "the parent's T is merged bare into the primary (D62b)");
        assertEquals("x", QualifiedNameFixture.values(r).get(0).get("T"));
        Rule dotted = load(ruleJson("Record",
                childEntry + "," + check("C.T == \"x\"") + "," + outcome("USUBJID")), true);
        assertNotNull(dotted.getLoadError(), "DOTTED_REF_CHILD_ENTRY is armed");
        assertTrue(dotted.getLoadError().contains("C.T"), dotted.getLoadError());
    }

}
