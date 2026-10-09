package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.ValueMapDictionary;
import net.cumba.corej.core.metadata.dictionary.MedDraConverter;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runbook W2 DI1 ({@code PLAN-engine-gap-dict-unsupported} §4.1, §5.1 Q1/Q2, §6b):
 * {@code valid_external_dictionary_hierarchy} keyed by the <b>level of the variable read</b>, child
 * and parent alike, end to end through the loader and the runner over a house file the real
 * {@link MedDraConverter} wrote from a synthetic distribution (MedDRA is licensed). Owner facts
 * (2026-10-08): a PT name can also be an HLT and an LLT name, so a bare-name lookup would admit a
 * wrong SOC; codes are code-like identifiers, never words. A file without the level-scoped section
 * (every file installed before DI1, and the corpus dummy) answers exactly as before — the legacy
 * arm, pinned here on the same distribution with the section removed.
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
     * Runbook W2 fold (R5 PB-M1 / PB-L1): the converter writes no LLT level, so {@code AELLT} takes
     * the bare-name map, exactly as before DI1 — it is keyed by HLT and HLGT names, so no LLT
     * resolves and every row fires, as on a file installed before DI1 (no shipped rule reads
     * {@code --LLT}).
     */
    @Test
    void anLltReadTakesTheBareNameMapAsBefore() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3")
                .str("AELLT", "Gamma", "Gamma", "Alpha")
                .str("AESOC", "Soc One", "Soc Two", "Soc One").build();
        ObjectNode legacy = converted.deepCopy();
        legacy.remove("hierarchy_by_level");
        assertEquals(fired(ae, "AELLT, AESOC", legacy), fired(ae, "AELLT, AESOC", converted));
        assertEquals(List.of(1L, 2L, 3L), fired(ae, "AELLT, AESOC", converted));
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
     * Q2: the code variables read the code levels (PT code → primary SOC code); an LLT code takes
     * the bare-name map since PB-M1 (no code is a key there, so both rows fire, as before DI1).
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
        assertEquals(List.of(1L, 2L), fired(llt, "AELLTCD, AESOCCD", converted));
    }


    /**
     * Runbook W2 fold (R5 PB-L2): the {@code CDISC-CG0460} shape — {@code --HLT, --SOC}, here as
     * the specialised {@code AEHLT, AESOC} the runner evaluates (the authored {@code --HLT} maps to
     * the same level, {@link #hierarchyLevelOfMapsEachMedDraVariableToItsLevel}) — on the
     * level-scoped arm, over a name that is both an HLT and an HLGT: the HLT {@code Hlgt Two}
     * (primary under Hlgt One / Soc One, through the PT {@code Epsilon}) beside the HLGT
     * {@code Hlgt Two} (Soc Two). The bare-name map merges the two under one key and admits both
     * SOCs; the HLT entry admits its own only (row 2 fires), and the legacy arm on the same file
     * without the section still admits both (nothing fires).
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
        assertEquals(List.of(), fired(ae, "AEHLT, AESOC", legacy),
                "the bare-name map merges the HLT and the HLGT Hlgt Two");
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
                "AEBODSYS", "AETERM", "$hlt", "MHDECODX"
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
     * A column whose name maps to no MedDRA level ({@code --BODSYS} may carry a secondary SOC, so
     * it is deliberately not mapped) keeps today's bare-name lookup over the legacy section.
     */
    @Test
    void anUnmappedColumnKeepsTheBareNameLookup() throws IOException
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2")
                .str("AEHLT", "Hlt One", "Hlt One").str("AEBODSYS", "Soc One", "Soc Two").build();
        assertEquals(List.of(2L), fired(ae, "AEHLT, AEBODSYS", converted));
    }

    // ------------------------------------------------------------------ the legacy arm


    /**
     * The same distribution WITHOUT {@code hierarchy_by_level} — the shape of every file installed
     * before DI1: the bare-name map answers exactly as before, the HLT {@code Beta}'s SOC included
     * (row 4 does not fire) and no PT key at all (rows 1, 3, 5 fire). This is the arm that keeps
     * {@code CDISC-CG0460} / {@code CG0461} unmoved on an installed file and on the corpus dummy.
     */
    @Test
    void aFileWithoutTheLevelScopedSectionAnswersAsBefore() throws IOException
    {
        ObjectNode legacy = converted.deepCopy();
        legacy.remove("hierarchy_by_level");
        IDataTable ae = RealTables.of("AE").str("USUBJID", "1", "2", "3", "4", "5", "6", "7")
                .str("AEDECOD", "Alpha", "Alpha", "Beta", "Beta", "Gamma", "Gamma", "Alpha")
                .str("AESOC", "Soc One", "Soc Two", "Soc One", "Soc Two", "Soc Two", "Soc One",
                        "Hlt One")
                .build();
        assertEquals(List.of(1L, 2L, 3L, 5L, 6L, 7L), fired(ae, "AEDECOD, AESOC", legacy));
        IDataTable hlt = RealTables.of("AE").str("USUBJID", "1", "2", "3", "4")
                .str("AEHLT", "Beta", "Beta", "Hlt One", "Hlt Two")
                .str("AESOC", "Soc Two", "Soc One", "Soc One", "Soc One").build();
        assertEquals(List.of(2L, 4L), fired(hlt, "AEHLT, AESOC", legacy));
    }

    // ------------------------------------------------------------------ helpers


    /** The 1-based rows on which {@code valid_external_dictionary_hierarchy(args) == false}. */
    private static List<Long> fired(IDataTable aTable, String aArguments, ObjectNode aDictionary)
        throws IOException
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"DI1\"},\"Sensitivity\":\"Record\","
                + "\"Bindings\":[{\"name\":\"$p\",\"expression\":"
                + "\"valid_external_dictionary_hierarchy(" + aArguments
                + ", external_dictionary_type=\\\"meddra\\\")\"}],"
                + "\"Check\":{\"expression\":\"$p == false\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuntimeDictionaryProvider dicts = new RuntimeDictionaryProvider(
                Map.of("meddra", ValueMapDictionary.parse(aDictionary)));
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, aTable,
                name -> name.equals(aTable.getMetaData().getName()) ? aTable : null, null, null,
                null, null, Integer.MAX_VALUE, null, dicts);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private void write(String aFileName, String... aLines) throws IOException
    {
        Files.writeString(raw.resolve(aFileName), String.join("\n", aLines) + "\n",
                StandardCharsets.UTF_8);
    }

}
