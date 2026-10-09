package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.loadError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 U2 ({@code PLAN-union-function} §3.2, owner Q3 #5 2026-10-01: "agreed to
 * Positional-only"): a keyword argument on a collector call ({@code printf}, {@code tuple},
 * {@code union}) is the rule's load error on every path — an inline Check, a binding, a Check-less
 * rule's binding — never a silently dropped argument. It is U1's R8 partner:
 * {@code union($a, $b, first=$c)} dropped {@code $c} before U2.
 */
class CollectorKeywordArgumentLoadTest
{

    private static final String[] LISTS =
    {
            "$a", "[\"A\", \"B\"]", "$b", "[\"C\"]", "$c", "[\"D\"]"
    };

    private static String[] with(String... more)
    {
        String[] out = java.util.Arrays.copyOf(LISTS, LISTS.length + more.length);
        System.arraycopy(more, 0, out, LISTS.length, more.length);
        return out;
    }


    private static void assertPositionalOnly(String function, String error)
    {
        assertTrue(error.contains("'" + function + "' takes positional arguments only"),
                function + ": " + error);
    }


    @Test
    void aKeywordArgumentOnACollectorCallIsALoadErrorInACheck()
    {
        assertPositionalOnly("union", loadError("count(union($a, $b, first=$c)) > 0", LISTS));
        assertPositionalOnly("printf", loadError("printf(\"%s\", format=\"x\") == \"x\""));
        assertPositionalOnly("tuple", loadError("tuple(USUBJID, ETCD, c1=SESEQ) not in $a", LISTS));
    }


    @Test
    void aKeywordArgumentOnACollectorCallIsALoadErrorInABinding()
    {
        assertPositionalOnly("union",
                loadError("count($u) > 0", with("$u", "union($a, $b, first=$c)")));
        Rule checkless = SequenceFunctionTest.loadCheckless(with("$u", "union($a, $b, first=$c)"));
        assertPositionalOnly("union", String.valueOf(checkless.getLoadError()));
    }


    @Test
    void thePositionalFormsLoadAsBefore()
    {
        assertEquals("null", loadError("count(union($a, $b, $c)) > 2", LISTS));
        assertEquals("null", loadError("printf(\"%s-%s\", USUBJID, ETCD) == \"x\""));
        assertEquals("null", loadError("tuple(USUBJID, ETCD, SESEQ) not in $a", LISTS));
    }
}
