package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.model.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-dictionary-seeder} Phase 6a, D13 item 1 / D6 — the run-level {@code Dictionary_Basis}
 * line. Null on a run whose every dictionary rule is answerable (the healthy default, so no
 * existing report gains a key); otherwise one line naming what loaded (with versions), what did not
 * and <em>why</em> (the same diagnosis the per-rule SKIPs carry), and the answerable count.
 */
class DictionaryBasisTest
{

    private static Rule dictRule(String id, String type) throws IOException
    {
        return RulePackageLoader.loadFromString(
                """
                        {"rules": {"%s": {
                          "Core": {"Id": "%s"},
                          "Executability": "Fully Executable",
                          "Operations": [{"id": "$terms", "expression":
                              "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\"%s\\", dictionary_term_type=\\"PT\\")"}],
                          "Check": {"all": [{"name": "$terms", "operator": "non_empty"}]}
                        }}}
                        """
                        .formatted(id, id, type))
                .getRules().values().iterator().next();
    }


    private static Rule plainRule(String id) throws IOException
    {
        return RulePackageLoader.loadFromString("""
                {"rules": {"%s": {
                  "Core": {"Id": "%s"},
                  "Executability": "Fully Executable",
                  "Check": {"all": [{"name": "AEDECOD", "operator": "empty"}]}
                }}}
                """.formatted(id, id)).getRules().values().iterator().next();
    }


    private static RuntimeDictionaryProvider meddraOnly(Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), "{\"type\":\"meddra\",\"version\":\"27.0\","
                + "\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"}}}");
        return RuntimeDictionaryProvider.loadDirectory(dir);
    }


    /** The healthy default: no dictionary rules in the run ⇒ nothing to report. */
    @Test
    void aRunWithNoDictionaryRulesHasNoBasisLine() throws IOException
    {
        assertNull(StudyValidationService.dictionaryBasis(null, List.of(plainRule("R1"))));
    }


    /** Fully equipped for this run ⇒ absent, exactly like Library_Metadata_Basis (Fix #369). */
    @Test
    void aFullyAnswerableRunHasNoBasisLine(@TempDir Path dir) throws IOException
    {
        RuntimeDictionaryProvider dicts = meddraOnly(dir);
        assertNull(StudyValidationService.dictionaryBasis(dicts,
                List.of(plainRule("R1"), dictRule("R2", "meddra"))));
    }


    @Test
    void noProviderAtAllNamesEveryNeededTypeAsNotInstalled() throws IOException
    {
        List<Rule> rules = new ArrayList<>();
        rules.add(dictRule("R1", "meddra"));
        rules.add(dictRule("R2", "unii"));
        rules.add(plainRule("R3"));

        String basis = StudyValidationService.dictionaryBasis(null, rules);

        assertNotNull(basis);
        assertTrue(basis.contains("0 of 2 dictionary rules in this run were answerable"), basis);
        assertTrue(basis.contains("Loaded: none"), basis);
        assertTrue(basis.contains("external dictionary meddra is not installed"), basis);
        assertTrue(basis.contains("external dictionary unii is not installed"), basis);
    }


    /**
     * Partial degradation: the loaded half is named WITH its version, the missing half with why.
     */
    @Test
    void partialDegradationNamesLoadedVersionsAndMissingReasons(@TempDir Path dir)
        throws IOException
    {
        RuntimeDictionaryProvider dicts = meddraOnly(dir);
        List<Rule> rules = List.of(dictRule("R1", "meddra"), dictRule("R2", "unii"));

        String basis = StudyValidationService.dictionaryBasis(dicts, rules);

        assertNotNull(basis);
        assertTrue(basis.contains("1 of 2 dictionary rules in this run were answerable"), basis);
        assertTrue(basis.contains("Loaded: meddra 27.0"), basis);
        assertTrue(basis.contains("Not loaded: external dictionary unii is not installed"), basis);
        assertEquals(-1, basis.indexOf('\n'), "one line — it must survive a log and a stderr line");
    }


    /** The recorded diagnosis (here: content-guard drop) reaches the basis line verbatim. */
    @Test
    void theRecordedUnavailabilityReasonReachesTheBasisLine(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("unii.json"), "{\"type\":\"unii\",\"levels\":{}}");
        RuntimeDictionaryProvider dicts = RuntimeDictionaryProvider.loadDirectory(dir);

        String basis = StudyValidationService.dictionaryBasis(dicts,
                List.of(dictRule("R1", "unii")));

        assertNotNull(basis);
        assertTrue(
                basis.contains("external dictionary unii is installed but carries no usable terms"),
                basis);
    }
}
