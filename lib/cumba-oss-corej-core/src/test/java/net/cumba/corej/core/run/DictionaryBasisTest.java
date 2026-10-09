package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.model.Rule;
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
                          "Bindings": [{"name": "$terms", "expression":
                              "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\"%s\\", dictionary_term_type=\\"PT\\")"}],
                          "Check": {"all": [{"expression": "not empty($terms)"}]}
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
                  "Check": {"all": [{"expression": "empty(AEDECOD)"}]}
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


    private static Rule hierarchyRule(String id, String columns) throws IOException
    {
        return RulePackageLoader.loadFromString(
                """
                        {"rules": {"%s": {
                          "Core": {"Id": "%s"},
                          "Executability": "Fully Executable",
                          "Bindings": [{"name": "$p", "expression":
                              "valid_external_dictionary_hierarchy(%s, external_dictionary_type=\\"meddra\\")"}],
                          "Check": {"all": [{"expression": "$p == false"}]}
                        }}}
                        """
                        .formatted(id, id, columns))
                .getRules().values().iterator().next();
    }


    /**
     * Runbook W4 DI2 (Q3): a meddra house file converted before DI1 (no {@code hierarchy_by_level})
     * stays loaded — its membership rule is answerable — but its hierarchy rule is not: the basis
     * line counts it and names the file in {@code Loaded:} with the remedy. A file carrying the
     * section answers both (no line). A section without the child level (LLT) is named by level.
     */
    @Test
    void aStaleMeddraHouseFileIsNamedAndItsHierarchyRuleIsNotAnswerable(@TempDir Path dir)
        throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), "{\"type\":\"meddra\",\"version\":\"27.0\","
                + "\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"},\"HLT\":{\"HEADACHES NEC\":"
                + "\"Headaches NEC\"},\"SOC\":{\"NERVOUS SYSTEM DISORDERS\":\"Nervous system"
                + " disorders\"}},\"hierarchy\":{\"Headaches NEC\":[\"Nervous system disorders\"]}}");
        RuntimeDictionaryProvider stale = RuntimeDictionaryProvider.loadDirectory(dir);
        assertTrue(stale.isAvailable("meddra"), "a stale file is NOT dropped");
        List<Rule> rules = List.of(dictRule("R1", "meddra"), hierarchyRule("R2", "AEHLT, AESOC"));

        String basis = StudyValidationService.dictionaryBasis(stale, rules);

        assertNotNull(basis);
        assertTrue(basis.contains("1 of 2 dictionary rules in this run were answerable"), basis);
        assertTrue(basis.contains("Loaded: meddra 27.0 (no hierarchy_by_level \u2014 re-convert it"
                + " to answer the hierarchy rules)."), basis);
        assertFalse(basis.contains("Not loaded"), basis);

        Path fresh = Files.createDirectory(dir.resolve("fresh"));
        Files.writeString(fresh.resolve("meddra.json"), "{\"type\":\"meddra\",\"version\":\"27.0\","
                + "\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"},\"HLT\":{\"HEADACHES NEC\":"
                + "\"Headaches NEC\"},\"SOC\":{\"NERVOUS SYSTEM DISORDERS\":\"Nervous system"
                + " disorders\"}},\"hierarchy_by_level\":{\"HLT\":{\"Headaches NEC\":{\"SOC\":"
                + "[\"Nervous system disorders\"]}}}}");
        RuntimeDictionaryProvider converted = RuntimeDictionaryProvider.loadDirectory(fresh);
        assertNull(StudyValidationService.dictionaryBasis(converted, rules),
                "a re-converted file answers both rules");
        String llt = StudyValidationService.dictionaryBasis(converted,
                List.of(hierarchyRule("R3", "AELLT, AESOC")));
        assertNotNull(llt);
        assertTrue(llt.contains("0 of 1 dictionary rules"), llt);
        assertTrue(llt.contains("Loaded: meddra 27.0 (no LLT level in hierarchy_by_level)."), llt);
    }
}
