package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import org.junit.jupiter.api.Test;

/**
 * The per-rule load channel kept one entry at a time ({@link Rule#getLoadErrors()}), beside the
 * joined string every consumer has always read (pack-2 review 8a-A F3).
 */
class RuleLoadChannelTest
{

    @Test
    void entriesJoinExactlyAsTheLoaderUsedToConcatenate()
    {
        Rule rule = new Rule();
        rule.addLoadError(null);
        assertNull(rule.getLoadError(),
                "a message-less exception on an empty channel files nothing");
        assertEquals(List.of(), rule.getLoadErrors());
        rule.addLoadError("first; with its own separator");
        rule.addLoadError(null);
        rule.addLoadError("third");
        assertEquals("first; with its own separator; null; third", rule.getLoadError());
        assertEquals(List.of("first; with its own separator", "null", "third"),
                rule.getLoadErrors());
        rule.setLoadError("stated whole");
        assertEquals(List.of("stated whole"), rule.getLoadErrors());
        rule.setLoadError(null);
        assertNull(rule.getLoadError());
        assertEquals(List.of(), rule.getLoadErrors());

        rule.addLoadWarning("w1");
        rule.addLoadWarning("w2");
        assertEquals("w1; w2", rule.getLoadWarning());
        assertEquals(List.of("w1", "w2"), rule.getLoadWarnings());
    }


    @Test
    void theLoaderFilesEachErrorAsItsOwnEntry() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString("""
                {"rules":{"CDISC-XX0001":{"Core":{"Id":"CDISC-XX0001","Status":"Published",
                "Version":"1"},"Executability":"Fully Executable",
                "Scope":{"Domains":{"Include":["","AE"]},"Variables":{"Include":["AESEV"]}},
                "Check":{"expression":"AESEV == \\"MILD\\""},
                "Outcome":{"Message":"m"}}}}
                """).getRules().get("CDISC-XX0001");
        assertTrue(rule.getLoadErrors().size() >= 2, rule.getLoadErrors().toString());
        assertEquals(String.join("; ", rule.getLoadErrors()), rule.getLoadError());
        assertTrue(rule.getLoadErrors().getFirst().contains("; "),
                "premise: an entry carries the separator itself: " + rule.getLoadErrors());
    }
}
