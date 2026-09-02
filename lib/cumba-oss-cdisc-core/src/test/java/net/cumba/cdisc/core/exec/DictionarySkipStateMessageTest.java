package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.metadata.dictionary.DictionaryStore;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-dictionary-seeder} Phase 6a, D13 item 2 — the eager dictionary SKIP names the
 * <b>state</b>, not just the type. The old catch-all (<em>"no external dictionary loaded for
 * meddra"</em>) was actively misleading for an operator who <em>did</em> install MedDRA; the three
 * operator-fixable states now each demand their own action, quoted from the diagnosis recorded by
 * whoever declined to load the type. (The fourth state of the taxonomy — an operation naming no
 * type at all — is a load ERROR, {@link TypelessDictionaryOperationLoadTest}.)
 */
class DictionarySkipStateMessageTest
{

    private static final String SUFFIX = "(rule requires valid_external_dictionary_* operations)";

    private static Rule meddraRule() throws IOException
    {
        return rule("valid_external_dictionary_value(AEDECOD, "
                + "external_dictionary_type=\\\"meddra\\\", dictionary_term_type=\\\"PT\\\")");
    }


    private static Rule rule(String opExpression) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString("""
                {"rules": {"TEST-SKIP-1": {
                  "Core": {"Id": "TEST-SKIP-1"},
                  "Executability": "Fully Executable",
                  "Operations": [{"id": "$terms", "expression": "%s"}],
                  "Check": {"all": [{"name": "$terms", "operator": "non_empty"}]}
                }}}
                """.formatted(opExpression)).getRules().values().iterator().next();
        assertNull(rule.getLoadError(), "precondition: the probe rule loads clean");
        return rule;
    }


    private static String skipReason(@Nullable RuntimeDictionaryProvider dicts) throws IOException
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "Cephalgia").name("AE")
                .build();
        RuleExecutionResult result = RuleRunner.execute(meddraRule(), t,
                name -> name.equals("AE") ? t : null, "AE", null, null, null, Integer.MAX_VALUE,
                null, dicts);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        String reason = result.getStatusMessage();
        assertTrue(reason.startsWith("Rule skipped — "), reason);
        assertTrue(reason.endsWith(SUFFIX), reason);
        return reason;
    }


    /** State 1 — never installed (here: no provider at all): say so, and say to install. */
    @Test
    void notInstalledSaysInstall() throws IOException
    {
        String reason = skipReason(null);
        assertTrue(reason.contains("external dictionary meddra is not installed — install it "
                + "into the dictionaries directory"), reason);
    }


    /** State 1 still, with a provider whose bundle simply lacks the type. */
    @Test
    void aBundleLackingTheTypeAlsoReadsNotInstalled(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("unii.json"),
                "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"X\":\"X\"}}}");
        String reason = skipReason(RuntimeDictionaryProvider.loadDirectory(dir));
        assertTrue(reason.contains("meddra is not installed"), reason);
    }


    /** State 2 — installed but unusable: the action is a REINSTALL, never "install". */
    @Test
    void installedButEmptySaysReinstall(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), "{\"type\":\"meddra\",\"levels\":{}}");
        String reason = skipReason(RuntimeDictionaryProvider.loadDirectory(dir));
        assertTrue(reason.contains("external dictionary meddra is installed but carries no "
                + "usable terms (empty or malformed) — reinstall it"), reason);
        assertFalse(reason.contains("is not installed"),
                "an operator who installed the file must not be told to install it: " + reason);
    }


    /** State 3a — installed, no version selected: name the versions and the three selectors. */
    @Test
    void noVersionSelectedNamesInstalledVersionsAndHowToChoose(@TempDir Path dir) throws IOException
    {
        installVersion(dir, "26.1");
        installVersion(dir, "27.0");
        String reason = skipReason(DictionaryStore.load(dir, Map.of()));
        assertTrue(reason.contains("no version is selected (installed: 26.1, 27.0)"), reason);
        assertTrue(reason.contains("--meddra-version"), reason);
        assertTrue(reason.contains("selected-versions.json"), reason);
        assertTrue(reason.contains("define.xml"), reason);
    }


    /** State 3b — a version was selected but is absent: name it, and name what IS installed. */
    @Test
    void aSelectedButAbsentVersionNamesBoth(@TempDir Path dir) throws IOException
    {
        installVersion(dir, "27.0");
        String reason = skipReason(DictionaryStore.load(dir, Map.of("meddra", "25.0")));
        assertTrue(
                reason.contains(
                        "external dictionary meddra version 25.0 (from requested) is not installed "
                                + "(installed: 27.0) — install it or select an installed version"),
                reason);
    }


    /** A rule needing two unavailable types gets one clause per type, each with its own state. */
    @Test
    void aTwoTypeRuleGetsAClausePerType(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), "{\"type\":\"meddra\",\"levels\":{}}");
        RuntimeDictionaryProvider dicts = RuntimeDictionaryProvider.loadDirectory(dir);

        Rule rule = RulePackageLoader
                .loadFromString(
                        """
                                {"rules": {"TEST-SKIP-2": {
                                  "Core": {"Id": "TEST-SKIP-2"},
                                  "Executability": "Fully Executable",
                                  "Operations": [
                                    {"id": "$a", "expression": "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\"meddra\\", dictionary_term_type=\\"PT\\")"},
                                    {"id": "$b", "expression": "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\"unii\\", dictionary_term_type=\\"SRS\\")"}],
                                  "Check": {"all": [{"name": "$a", "operator": "non_empty"},
                                                    {"name": "$b", "operator": "non_empty"}]}
                                }}}
                                """)
                .getRules().values().iterator().next();
        assertNull(rule.getLoadError());

        IDataTable t = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "X").name("AE").build();
        RuleExecutionResult result = RuleRunner.execute(rule, t,
                name -> name.equals("AE") ? t : null, "AE", null, null, null, Integer.MAX_VALUE,
                null, dicts);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        String reason = result.getStatusMessage();
        assertTrue(reason.contains("meddra is installed but carries no usable terms"), reason);
        assertTrue(reason.contains("; external dictionary unii is not installed"), reason);
    }


    private static void installVersion(Path dir, String version) throws IOException
    {
        Path d = Files.createDirectories(dir.resolve("meddra").resolve(version));
        Files.writeString(d.resolve("meddra.json"), "{\"type\":\"meddra\",\"version\":\"" + version
                + "\",\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"}}}");
    }
}
