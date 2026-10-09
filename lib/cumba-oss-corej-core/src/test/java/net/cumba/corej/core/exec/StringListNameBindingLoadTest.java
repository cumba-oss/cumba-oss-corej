package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.loadError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 E3 ({@code PLAN-elementwise-list-functions} owner ruling D1-B; coordinator W4-D6): a
 * binding whose expression is a written list of <b>string literals</b> ({@code $k: ["USUBJID",
 * "AESEQ"]}), read at a splice slot ({@code GroupSplice.SPLICE_SLOTS}) - as the bare operand, as a
 * list member, as a {@code within=} component or wrapped by {@code asc(…)} - is the rule's load
 * error with D1's hint {@code "write the columns as names: [USUBJID, AESEQ]"}. Before E3 the
 * bind-time specialiser turned its strings into column names, so the binding answered as the
 * written names - while the same strings written inline ({@code group=["USUBJID"]}) were already
 * refused (runbook W8 D-W8-6). Judged by the binding's PROVENANCE, not its type: a computed list of
 * names ({@code minus([…], subtract=[…])}) still splices (D1 (b)); {@code colref([…])} (a computed
 * name read, S4) and {@code key_variable} (a value slot) are no splice slots; a single string
 * binding ({@code $k: "--SEQ"}, PACK 5 S8) is not retired (E3-D1: D1 names a list); a string list
 * used as values is untouched (D1 (a)).
 */
class StringListNameBindingLoadTest
{

    private static final String STRINGS = "[\"USUBJID\", \"AESEQ\"]";

    private static void assertE3(String error, String binding)
    {
        assertTrue(error.contains("reads the binding " + binding + " as column names"), error);
        assertTrue(error.contains("write the columns as names: [USUBJID, AESEQ]"), error);
    }


    @Test
    void aStringListBindingAtASpliceSlotIsALoadErrorInEveryShape()
    {
        for (String check : List.of("record_count(group=$k) > 1",
                "record_count(group=[USUBJID, $k]) > 1", "not is_unique_set($k)",
                "not is_unique_set([AETERM, $k])", "\"y\" in values($k)",
                "has_multiple_values_for(AETERM, AEDECOD, within=$k)",
                "has_multiple_values_for(AETERM, AEDECOD, within=[[$k]])",
                "is_sorted_by(AESTDTC, by=[asc($k)])",
                "not is_unique_relationship(AETERM, keys=$k)",
                "not empty(distinct(AETERM, group=[$k]))"))
        {
            String error = loadError(check, "$k", STRINGS);
            assertE3(error, "$k");
        }
        // the parameter and the function are named
        String named = loadError("record_count(group=$k) > 1", "$k", STRINGS);
        assertTrue(named.contains("argument 'group' of 'record_count'"), named);
    }


    @Test
    void anAliasOfAStringListBindingIsJudgedByItsRoot()
    {
        assertE3(loadError("record_count(group=$k2) > 1", "$k", STRINGS, "$k2", "$k"), "$k2");
    }


    @Test
    void aStringListBindingIsALoadErrorInABindingAndInACheckLessRule()
    {
        assertE3(loadError("$n > 1", "$k", STRINGS, "$n", "record_count(group=$k)"), "$k");
        Rule checkless = SequenceFunctionTest.loadCheckless("$k", STRINGS, "$n",
                "record_count(group=[$k])");
        assertE3(String.valueOf(checkless.getLoadError()), "$k");
    }


    @Test
    void theFormsE3DoesNotRetireStillLoad()
    {
        // a computed list of names splices (D1 (b), BS2)
        assertEquals("null", loadError("record_count(group=$k) > 1", "$k",
                "minus([\"USUBJID\", \"X\"], subtract=[\"X\"])"));
        // the name-list binding of bare names (D1-A, E2)
        assertEquals("null", loadError("record_count(group=$k) > 1", "$k", "[USUBJID, AESEQ]"));
        // a single string binding (E3-D1, PACK 5 S8)
        assertEquals("null", loadError("record_count(group=[USUBJID, $k]) > 1", "$k", "\"--SEQ\""));
        // colref's name slot is a computed-name read (S4), not a splice slot
        assertEquals("null", loadError("not empty(colref($k))", "$k", STRINGS));
        // a string list used as values (D1 (a))
        assertEquals("null", loadError("AETERM in $k", "$k", STRINGS));
        assertEquals("null", loadError("not empty(minus($k, subtract=[\"X\"]))", "$k", STRINGS));
    }


    @Test
    void theNameListSpellingAnswersWhatTheStringListAnsweredBefore()
    {
        // the hint's remedy: the bare names answer as the written list
        IDataTable ae = WithinOrderingSpliceTest.ae();
        List<Long> written = SequenceFunctionTest.fired(ae,
                "record_count(group=[USUBJID, AETERM]) > 1");
        assertTrue(!written.isEmpty(), "premise: S1/A occurs twice");
        assertEquals(written, SequenceFunctionTest.fired(ae, "record_count(group=$k) > 1", "$k",
                "[USUBJID, AETERM]"));
    }
}
