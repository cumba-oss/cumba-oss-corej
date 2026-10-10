package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.dictionary.convert.MedDraConverter;
import net.cumba.datatable.dictionary.house.RuntimeDictionaryProvider;
import net.cumba.datatable.dictionary.house.ValueMapDictionary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runbook W2 DI1 ({@code PLAN-engine-gap-dict-unsupported} §4.1, §5.1 Q1/Q2, §6b):
 * {@code valid_external_dictionary_hierarchy} keyed by the <b>level of the variable read</b>, child
 * and parent alike, end to end through the loader and the runner over a house file the real
 * {@link MedDraConverter} wrote from a synthetic distribution (MedDRA is licensed). Owner facts
 * (2026-10-08): a PT name can also be an HLT and an LLT name, so a bare-name lookup would admit a
 * wrong SOC; codes are code-like identifiers, never words.
 *
 * <p>
 * Runbook W4 DI2 ({@code PLAN-engine-gap-dict-unsupported} Q3, coordinator W4-D11): the bare-name
 * legacy arm is gone. A house file without the level-scoped section (every file converted before
 * DI1), or whose section lacks the level the child column reads (LLT / LLTCD — never written since
 * PB-M1), cannot answer: the hierarchy rule SKIPs with the reason, binding or inline, while the
 * file stays loaded for the other MedDRA functions. A column whose name maps to no MedDRA level is
 * the rule's load error (F2) — there is no bare map left to read it through.
 * </p>
 */
class DictionaryHierarchyLevelTest
{

    @TempDir
    private Path raw;

    private ObjectNode converted;

    @BeforeEach
    void convert() throws IOException
    {
        write("llt.asc", "10000001$Alpha$10000001$", "10000002$Beta$10000002$",
                "50000003$Gamma$10000001$", "10000004$Delta$10000004$");
        write("pt.asc", "10000001$Alpha$$40000001$", "10000002$Beta$$40000001$",
                "10000003$Gamma$$40000002$", "10000004$Delta$$40000002$");
        write("hlt.asc", "20000001$Hlt One$", "20000002$Hlt Two$", "20000003$Beta$");
        write("hlgt.asc", "30000001$Hlgt One$", "30000002$Hlgt Two$");
        write("soc.asc", "40000001$Soc One$S1$", "40000002$Soc Two$S2$");
        write("mdhier.asc",
                "10000001$20000001$30000001$40000001$Alpha$Hlt One$Hlgt One$Soc One$S1$$40000001$Y$",
                "10000001$20000002$30000002$40000002$Alpha$Hlt Two$Hlgt Two$Soc Two$S2$$40000001$N$",
                "10000002$20000001$30000001$40000001$Beta$Hlt One$Hlgt One$Soc One$S1$$40000001$Y$",
                "10000003$20000002$30000002$40000002$Gamma$Hlt Two$Hlgt Two$Soc Two$S2$$40000002$Y$",
                "10000004$20000003$30000002$40000002$Delta$Beta$Hlgt Two$Soc Two$S2$$40000002$Y$");
        write("meddra_release.asc", "27.0$English$$");
        converted = new MedDraConverter().convert(raw);
        converted.put("type", "meddra");
    }

    // ------------------------------------------------------------------ the level-scoped file


    /**
     * {@code AEDECOD} reads the PT entry, {@code AESOC} its SOC: a secondary SOC fires (row 2), the
     * HLT {@code Beta}'s SOC does not satisfy the PT {@code Beta} (row 4), the LLT {@code Gamma}'s
     * path does not satisfy the PT {@code Gamma} (row 6), and an HLT name in {@code AESOC} is no
     * SOC even though it lies on the PT's path (row 7 — the parent level is scoped too).
     */
    @Test
    void thePtEntryAnswersAtItsOwnLevelAndTheParentIsScopedToo() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3", "4", "5", "6", "7")
                .str("AEDECOD", "Alpha", "Alpha", "Beta", "Beta", "Gamma", "Gamma", "Alpha")
                .str("AESOC", "Soc One", "Soc Two", "Soc One", "Soc Two", "Soc Two", "Soc One",
                        "Hlt One")
                .build();
        assertEquals(List.of(2L, 4L, 6L, 7L), fired(ae, "AEDECOD, AESOC", converted));
    }


    /** {@code AEHLT} reads the HLT entry: {@code Beta} as an HLT sits under Soc Two only. */
    @Test
    void theHltEntryAnswersAtItsOwnLevel() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3", "4")
                .str("AEHLT", "Beta", "Beta", "Hlt One", "Hlt Two")
                .str("AESOC", "Soc Two", "Soc One", "Soc One", "Soc One").build();
        assertEquals(List.of(2L, 4L), fired(ae, "AEHLT, AESOC", converted));
        IDataTable hlgt = RealTables.of("AE").str("USUBJID", "1", "2").str("AEHLT", "Beta", "Beta")
                .str("AEHLGT", "Hlgt Two", "Hlgt One").build();
        assertEquals(List.of(2L), fired(hlgt, "AEHLT, AEHLGT", converted));
    }


    /**
     * Runbook W4 DI2: the converter writes no LLT level (PB-M1), so an {@code AELLT} child cannot
     * be answered — the rule SKIPs with the reason. Until DI2 it took the bare-name map, keyed by
     * HLT and HLGT names, and fired on every row (PB-L1).
     */
    @Test
    void anLltReadSkipsWithTheReason() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3")
                .str("AELLT", "Gamma", "Gamma", "Alpha")
                .str("AESOC", "Soc One", "Soc Two", "Soc One").build();
        RuleExecutionResult result = run(ae, "AELLT, AESOC", converted);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(), result.getStatusMessage());
        assertEquals(
                "Rule skipped \u2014 external dictionary meddra cannot answer the LLT \u2192 SOC"
                        + " hierarchy: its house file carries no LLT level in hierarchy_by_level \u2014 the"
                        + " converter writes PT, HLT and HLGT and their codes (the rule needs this external"
                        + " dictionary)",
                result.getStatusMessage());
    }


    /** {@code AEDECOD} against {@code AEHLT}: the PT's own primary HLT, never a same-named PT. */
    @Test
    void aPtAgainstItsHltReadsThePrimaryHlt() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3")
                .str("AEDECOD", "Alpha", "Alpha", "Beta").str("AEHLT", "Hlt One", "Hlt Two", "Beta")
                .build();
        assertEquals(List.of(2L, 3L), fired(ae, "AEDECOD, AEHLT", converted));
    }


    /**
     * Q2: the code variables read the code levels (PT code → primary SOC code); an LLT code child
     * SKIPs since DI2 (no LLTCD level since PB-M1; before DI2 the bare-name map fired both rows).
     */
    @Test
    void theCodeVariablesReadTheCodeLevels() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3")
                .str("AEPTCD", "10000001", "10000001", "10000002")
                .str("AESOCCD", "40000001", "40000002", "40000001").build();
        assertEquals(List.of(2L), fired(ae, "AEPTCD, AESOCCD", converted));
        IDataTable llt = RealTables.of("AE").str("USUBJID", "1", "2")
                .str("AELLTCD", "50000003", "50000003").str("AESOCCD", "40000001", "40000002")
                .build();
        RuleExecutionResult skipped = run(llt, "AELLTCD, AESOCCD", converted);
        assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus(), skipped.getStatusMessage());
        assertTrue(skipped.getStatusMessage().contains("cannot answer the LLTCD \u2192 SOCCD"
                + " hierarchy: its house file carries no LLTCD level in hierarchy_by_level"),
                skipped.getStatusMessage());
    }


    /**
     * Runbook W2 fold (R5 PB-L2): the {@code CDISC-CG0460} shape — {@code --HLT, --SOC}, here as
     * the specialised {@code AEHLT, AESOC} the runner evaluates (the authored {@code --HLT} maps to
     * the same level, {@link #hierarchyLevelOfMapsEachMedDraVariableToItsLevel}) — on the
     * level-scoped arm, over a name that is both an HLT and an HLGT: the HLT {@code Hlgt Two}
     * (primary under Hlgt One / Soc One, through the PT {@code Epsilon}) beside the HLGT
     * {@code Hlgt Two} (Soc Two). The bare-name map merges the two under one key and admits both
     * SOCs; the HLT entry admits its own only (row 2 fires). The same file without the section
     * SKIPs since DI2 (until then the bare-name map admitted both and nothing fired).
     */
    @Test
    void theAuthoredCg0460ShapeReadsTheHltEntryOverAnHltHlgtNameCollision() throws IOException
    {
        write("llt.asc", "10000001$Alpha$10000001$", "10000002$Beta$10000002$",
                "50000003$Gamma$10000001$", "10000004$Delta$10000004$",
                "10000005$Epsilon$10000005$");
        write("pt.asc", "10000001$Alpha$$40000001$", "10000002$Beta$$40000001$",
                "10000003$Gamma$$40000002$", "10000004$Delta$$40000002$",
                "10000005$Epsilon$$40000001$");
        write("hlt.asc", "20000001$Hlt One$", "20000002$Hlt Two$", "20000003$Beta$",
                "20000004$Hlgt Two$");
        write("mdhier.asc",
                "10000001$20000001$30000001$40000001$Alpha$Hlt One$Hlgt One$Soc One$S1$$40000001$Y$",
                "10000002$20000001$30000001$40000001$Beta$Hlt One$Hlgt One$Soc One$S1$$40000001$Y$",
                "10000003$20000002$30000002$40000002$Gamma$Hlt Two$Hlgt Two$Soc Two$S2$$40000002$Y$",
                "10000004$20000003$30000002$40000002$Delta$Beta$Hlgt Two$Soc Two$S2$$40000002$Y$",
                "10000005$20000004$30000001$40000001$Epsilon$Hlgt Two$Hlgt One$Soc One$S1$$40000001$Y$");
        ObjectNode collided = new MedDraConverter().convert(raw);
        collided.put("type", "meddra");
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2")
                .str("AEHLT", "Hlgt Two", "Hlgt Two").str("AESOC", "Soc One", "Soc Two").build();
        assertEquals(List.of(2L), fired(ae, "AEHLT, AESOC", collided));
        ObjectNode legacy = collided.deepCopy();
        legacy.remove("hierarchy_by_level");
        assertEquals(RuleExecutionStatus.SKIPPED, run(ae, "AEHLT, AESOC", legacy).getStatus(),
                "no bare-name map answers any more");
    }


    /** Runbook W2 fold (R5 PB-L2): the level each column name reads. */
    @Test
    void hierarchyLevelOfMapsEachMedDraVariableToItsLevel()
    {
        Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("AEDECOD", "PT");
        table.put("--DECOD", "PT");
        table.put("AEPTCD", "PTCD");
        table.put("AELLT", "LLT");
        table.put("AELLTCD", "LLTCD");
        table.put("--HLT", "HLT");
        table.put("AEHLTCD", "HLTCD");
        table.put("AEHLGT", "HLGT");
        table.put("AEHLGTCD", "HLGTCD");
        table.put("AESOC", "SOC");
        table.put("AESOCCD", "SOCCD");
        table.put("AE.AEHLT", "HLT");
        table.put("aehlgt", "HLGT");
        for (Map.Entry<String, String> e : table.entrySet())
        {
            assertEquals(e.getValue(), DictionaryFunctions.hierarchyLevelOf(e.getKey()),
                    e.getKey());
        }
        for (String unmapped : new String[]
        {
                "AEBODSYS", "AEBDSYCD", "AETERM", "$hlt", "MHDECODX"
        })
        {
            assertNull(DictionaryFunctions.hierarchyLevelOf(unmapped), unmapped);
        }
        assertNull(DictionaryFunctions.hierarchyLevelOf(null));
    }


    /** {@code case_sensitive=false} folds both operands on the level-scoped lookup too. */
    @Test
    void caseInsensitiveFoldsTheScopedLookup() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2")
                .str("AEDECOD", "ALPHA", "alpha").str("AESOC", "SOC ONE", "soc two").build();
        assertEquals(List.of(2L), fired(ae, "AEDECOD, AESOC, case_sensitive=false", converted));
    }


    /**
     * Runbook W4 DI2 F2: a column whose name maps to no MedDRA level ({@code --BODSYS} may carry a
     * secondary SOC, so it is deliberately not mapped; a {@code $} binding; a computed operand) is
     * the rule's load error — in a binding, inline in a Check, in a Check-less rule — naming the
     * operand and the columns that do name a level. Until DI2 it read the bare-name map.
     */
    @Test
    void aColumnThatNamesNoMedDraLevelIsALoadError() throws IOException
    {
        String hint = " names no MedDRA level (write a --DECOD/--PTCD, --HLT/--HLTCD,"
                + " --HLGT/--HLGTCD or --SOC/--SOCCD column)";
        String head = "valid_external_dictionary_hierarchy reads the dictionary at the levels its two"
                + " columns name \u2014 ";
        assertEquals(head + "AEBODSYS" + hint, loadError(binding("AEHLT, AEBODSYS")));
        assertEquals(head + "AEBODSYS" + hint, loadError(binding("AEBODSYS, AESOC")));
        assertEquals(head + "upper(AEHLT)" + hint, loadError(binding("upper(AEHLT), AESOC")));
        String dollar = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI2\"},\"Sensitivity\":\"Record\","
                + "\"Bindings\":[{\"name\":\"$c\",\"expression\":\"upper(AEHLT)\"},"
                + "{\"name\":\"$p\",\"expression\":\"valid_external_dictionary_hierarchy($c,"
                + " AESOC, external_dictionary_type=\\\"meddra\\\")\"}],"
                + "\"Check\":{\"expression\":\"$p == false\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        assertEquals(head + "$c" + hint, loadError(dollar));
        String inline = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI2\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"not valid_external_dictionary_hierarchy(AEHLT,"
                + " AEBODSYS, external_dictionary_type=\\\"meddra\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        assertEquals(head + "AEBODSYS" + hint, loadError(inline));
        String checkless = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI2\"},"
                + "\"Bindings\":[{\"name\":\"$p\",\"expression\":"
                + "\"valid_external_dictionary_hierarchy(AEHLT, AEBODSYS,"
                + " external_dictionary_type=\\\"meddra\\\")\"}],"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        assertEquals(head + "AEBODSYS" + hint, loadError(checkless));
        assertNull(RulePackageLoader.loadFromString(binding("--HLT, --SOC")).getRules().get("x")
                .getLoadError(), "the CG0460 shape names two levels and loads");
    }

    // ------------------------------------------------------------------ the stale house file


    /**
     * Runbook W4 DI2 (Q3): the same distribution WITHOUT {@code hierarchy_by_level} — the shape of
     * every file converted before DI1 — answers no hierarchy: the rule SKIPs with the reason, the
     * {@code CDISC-CG0460} binding shape and the same call inline in the Check alike, while a
     * sibling {@code valid_external_dictionary_value} rule on the same file still EXECUTES (the
     * file stays loaded). Until DI2 the bare-name map answered (and admitted any SOC a same-named
     * HLT carried).
     */
    @Test
    void aFileWithoutTheLevelScopedSectionSkipsTheHierarchyRuleWithTheReason() throws IOException
    {
        ObjectNode legacy = converted.deepCopy();
        legacy.remove("hierarchy_by_level");
        IDataTable hlt = RealTables.of("AE").str("USUBJID", "1", "2", "3", "4")
                .str("AEHLT", "Beta", "Beta", "Hlt One", "Hlt Two")
                .str("AEDECOD", "Alpha", "Beta", "Gamma", "Nope")
                .str("AESOC", "Soc Two", "Soc One", "Soc One", "Soc One").build();
        String reason = "Rule skipped \u2014 external dictionary meddra cannot answer the HLT \u2192 SOC"
                + " hierarchy: its house file has no level-scoped hierarchy (hierarchy_by_level)"
                + " \u2014 it was converted before this release; re-install meddra to re-convert it"
                + " (the rule needs this external dictionary)";
        RuleExecutionResult bound = run(hlt, "AEHLT, AESOC", legacy);
        assertEquals(RuleExecutionStatus.SKIPPED, bound.getStatus(), bound.getStatusMessage());
        assertEquals(reason, bound.getStatusMessage());
        String inline = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI2\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"not valid_external_dictionary_hierarchy(AEHLT,"
                + " AESOC, external_dictionary_type=\\\"meddra\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        RuleExecutionResult inlined = execute(inline, hlt, legacy);
        assertEquals(RuleExecutionStatus.SKIPPED, inlined.getStatus(), inlined.getStatusMessage());
        assertEquals(reason, inlined.getStatusMessage());
        String value = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI2\"},\"Sensitivity\":\"Record\","
                + "\"Bindings\":[{\"name\":\"$v\",\"expression\":"
                + "\"valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\\"meddra\\\","
                + " dictionary_term_type=\\\"PT\\\")\"}],"
                + "\"Check\":{\"expression\":\"$v == false\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        RuleExecutionResult member = execute(value, hlt, legacy);
        assertEquals(RuleExecutionStatus.EXECUTED, member.getStatus(), member.getStatusMessage());
        assertEquals(1, member.getViolations().size(), "Nope is no PT; the file still answers");
        assertEquals(RuleExecutionStatus.EXECUTED, run(hlt, "AEHLT, AESOC", converted).getStatus(),
                "the re-converted file answers (control)");
    }


    /**
     * W4 fold (R5 P3-L3, coordinator: the level scoping was only ever MedDRA's): a hierarchy call
     * on any OTHER dictionary type answers from the bare-name {@code hierarchy} map exactly as
     * before W4 — no F2 load error for a column that names no MedDRA level ({@code CMCLAS}), and no
     * "re-install" SKIP for a file without {@code hierarchy_by_level} (no converter but MedDRA's
     * writes one). Before the fold the first shape failed to load, the second SKIPPED.
     */
    @Test
    void aNonMeddraHierarchyCallAnswersFromTheBareMapAsBefore() throws IOException
    {
        ObjectNode whodrug = (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"type\":\"whodrug\",\"levels\":{\"PT\":{\"ASPIRIN\":\"Aspirin\"},"
                        + "\"ATC\":{\"ANALGESICS\":\"Analgesics\"}},"
                        + "\"hierarchy\":{\"Aspirin\":[\"Analgesics\"]}}");
        IDataTable cm = RealTables.of("CM").str("USUBJID", "1", "2")
                .str("CMDECOD", "Aspirin", "Aspirin").str("CMCLAS", "Analgesics", "Other")
                .str("CMSOC", "Analgesics", "Other").build();
        for (String arguments : List.of("CMDECOD, CMCLAS", "CMDECOD, CMSOC"))
        {
            String pkg = binding(arguments).replace("\\\"meddra\\\"", "\\\"whodrug\\\"");
            assertTrue(pkg.contains("whodrug"), pkg);
            RuleExecutionResult result = execute(pkg, cm, "whodrug", whodrug);
            assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                    arguments + ": " + result.getStatusMessage());
            List<Long> rows = new ArrayList<>();
            result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
            assertEquals(List.of(2L), rows, arguments + ": Other is no ancestor of Aspirin");
        }
    }

    // ------------------------------------------------------------------ helpers


    /** The 1-based rows on which {@code valid_external_dictionary_hierarchy(args) == false}. */
    private static List<Long> fired(IDataTable aTable, String aArguments, ObjectNode aDictionary)
        throws IOException
    {
        RuleExecutionResult result = run(aTable, aArguments, aDictionary);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /** The {@code CDISC-CG0460}-shaped package: the call in a binding, {@code $p == false}. */
    private static String binding(String aArguments)
    {
        return "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI1\"},\"Sensitivity\":\"Record\","
                + "\"Bindings\":[{\"name\":\"$p\",\"expression\":"
                + "\"valid_external_dictionary_hierarchy(" + aArguments
                + ", external_dictionary_type=\\\"meddra\\\")\"}],"
                + "\"Check\":{\"expression\":\"$p == false\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
    }


    private static RuleExecutionResult run(IDataTable aTable, String aArguments,
            ObjectNode aDictionary)
        throws IOException
    {
        return execute(binding(aArguments), aTable, aDictionary);
    }


    private static RuleExecutionResult execute(String aPackage, IDataTable aTable,
            ObjectNode aDictionary)
        throws IOException
    {
        return execute(aPackage, aTable, "meddra", aDictionary);
    }


    private static RuleExecutionResult execute(String aPackage, IDataTable aTable, String aType,
            ObjectNode aDictionary)
        throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(aPackage).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuntimeDictionaryProvider dicts = new RuntimeDictionaryProvider(
                Map.of(aType, ValueMapDictionary.parse(aDictionary)));
        return RuleRunnerCalls.execute(rule, aTable,
                name -> name.equals(aTable.getMetaData().getName()) ? aTable : null, null, null,
                null, null, Integer.MAX_VALUE, null, dicts);
    }


    private static String loadError(String aPackage) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(aPackage).getRules().get("x");
        assertNotNull(rule, "the rule binds");
        return String.valueOf(rule.getLoadError());
    }


    private void write(String aFileName, String... aLines) throws IOException
    {
        Files.writeString(raw.resolve(aFileName), String.join("\n", aLines) + "\n",
                StandardCharsets.UTF_8);
    }

}
