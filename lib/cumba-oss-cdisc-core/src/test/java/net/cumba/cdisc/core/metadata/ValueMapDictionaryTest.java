package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T1 — unit coverage for the house value-map dictionary loader and provider over the checked-in
 * dummy dictionaries.
 */
class ValueMapDictionaryTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RuntimeDictionaryProvider dicts() throws Exception
    {
        return RuntimeDictionaryProvider.loadDirectory(Paths.get("dictionaries"));
    }


    @Test
    void loadsShippedDictionaries() throws Exception
    {
        RuntimeDictionaryProvider p = dicts();
        assertTrue(p.isAvailable("meddra"));
        assertTrue(p.isAvailable("MEDDRA"), "type lookup is case-insensitive");
        assertTrue(p.isAvailable("whodrug"));
        assertTrue(p.isAvailable("unii"));
        assertTrue(p.isAvailable("neoplasm"));
        assertFalse(p.isAvailable("nonexistent"));
        assertFalse(p.isAvailable(null));
    }


    @Test
    void membershipIsCaseFolded() throws Exception
    {
        RuntimeDictionaryProvider p = dicts();
        assertTrue(p.isValidTerm("meddra", "PT", "HEADACHE"));
        assertTrue(p.isValidTerm("meddra", "PT", "headache"), "membership is case-folded");
        assertFalse(p.isValidTerm("meddra", "PT", "FOOBAR"));
        assertFalse(p.isValidTerm("nosuch", "PT", "HEADACHE"), "absent type ⇒ not valid");
    }


    @Test
    void caseCheckComparesPreferredCase() throws Exception
    {
        RuntimeDictionaryProvider p = dicts();
        assertTrue(p.caseMatches("meddra", "PT", "Headache"), "preferred case matches");
        assertFalse(p.caseMatches("meddra", "PT", "HEADACHE"), "wrong case fails");
        assertFalse(p.caseMatches("meddra", "PT", "FOOBAR"));
    }


    @Test
    void hierarchyPathAndPairAndAttribute() throws Exception
    {
        RuntimeDictionaryProvider p = dicts();
        // The sensitive probes carry the levels' preferred case: since the C1 data repair the
        // hierarchy and attributes maps are authored in exactly that form.
        assertTrue(p.onHierarchyPath("meddra", "Headache", "Nervous system disorders", true));
        assertFalse(p.onHierarchyPath("meddra", "Headache", "Gastrointestinal disorders", true));
        // Full-ancestor-list convention: every intermediate level (HLT/HLGT) as well as the SOC is
        // reachable from the leaf term, and the intermediate terms carry their own ancestor lists.
        assertTrue(p.onHierarchyPath("meddra", "Headache", "Headaches NEC", true),
                "leaf->HLT ancestor");
        assertTrue(p.onHierarchyPath("meddra", "Headache", "Headaches", true),
                "leaf->HLGT ancestor");
        assertTrue(p.onHierarchyPath("meddra", "Headaches NEC", "Nervous system disorders", true),
                "HLT->SOC ancestor");
        assertTrue(p.onHierarchyPath("meddra", "Nausea", "Gastrointestinal disorders", true),
                "multi-hop leaf->SOC");
        assertFalse(p.onHierarchyPath("meddra", "Nausea", "Nervous system disorders", true),
                "wrong SOC ⇒ off-path");
        assertTrue(p.codeDecodePair("unii", "unii", "R16CO5Y76E", "ASPIRIN", true));
        assertFalse(p.codeDecodePair("unii", "unii", "R16CO5Y76E", "IBUPROFEN", true));
        // NEOPLASM attribute alignment resolves through codeDecodePair over the attributes map.
        assertTrue(p.codeDecodePair("neoplasm", "neoplasm", "Adenoma", "BENIGN", true));
        assertFalse(p.codeDecodePair("neoplasm", "neoplasm", "Carcinoma", "BENIGN", true));
        // termAttribute keeps its folded probe, so an upper-case term still resolves there.
        assertTrue("BENIGN".equals(p.termAttribute("neoplasm", "neoplasm", "ADENOMA")));
        assertNull(p.termAttribute("neoplasm", "neoplasm", "NOSUCH"));
        assertNull(p.termAttribute("nosuch", "neoplasm", "ADENOMA"));
    }


    @Test
    void hierarchyPathAndPairAndDecodeAreFlagAware() throws Exception
    {
        // D-TA-3 / Fix #266: the sensitive path (the default) compares the as-authored dictionary
        // entries verbatim; caseSensitive=false serves the authored `case_sensitive: false` rules
        // by folding BOTH operands — including the decode, which pre-#266 compared verbatim while
        // the code folded (an asymmetry no rule authored).
        RuntimeDictionaryProvider p = dicts();
        // Hierarchy: since the C1 repair dummy meddra stores the levels' preferred case, so an
        // upper-case probe misses sensitively but matches folded.
        assertFalse(p.onHierarchyPath("meddra", "HEADACHE", "NERVOUS SYSTEM DISORDERS", true),
                "sensitive: case-mismatched child is off-path");
        assertTrue(p.onHierarchyPath("meddra", "HEADACHE", "NERVOUS SYSTEM DISORDERS", false),
                "insensitive: both operands folded");
        // Pair: code and decode both fold under false, both compare verbatim under true.
        assertFalse(p.codeDecodePair("unii", "unii", "r16co5y76e", "ASPIRIN", true),
                "sensitive: case-mismatched code is not paired");
        assertFalse(p.codeDecodePair("unii", "unii", "R16CO5Y76E", "Aspirin", true),
                "sensitive: case-mismatched decode is not paired");
        assertTrue(p.codeDecodePair("unii", "unii", "r16co5y76e", "aspirin", false),
                "insensitive: code AND decode fold");
        // Decode-presence: the code lookup follows the same flag.
        assertTrue(p.hasDecode("medrt", "medrt", "N0000000181", true));
        assertFalse(p.hasDecode("medrt", "medrt", "n0000000181", true),
                "sensitive: case-mismatched code has no decode");
        assertTrue(p.hasDecode("medrt", "medrt", "n0000000181", false), "insensitive: code folds");
        assertFalse(p.hasDecode("medrt", "medrt", "NOSUCH", false));
    }


    @Test
    void codeDecodePairFallsThroughToAttributesWhenRegistryMisses() throws Exception
    {
        // M1: a named registry (`reg`) is a preference, not a short-circuit. Here the code/decode
        // pair lives in the `attributes` map while `reg` names a `pairs` registry that does not
        // carry it — codeDecodePair must still find it by scanning all pairs + attributes.
        ValueMapDictionary d = ValueMapDictionary.parse(
                MAPPER.readTree("{\"type\":\"x\",\"pairs\":{\"unii\":{\"R16CO5Y76E\":\"ASPIRIN\"}},"
                        + "\"attributes\":{\"neoplasm\":{\"ADENOMA\":\"BENIGN\"}}}"));
        for (boolean cs : new boolean[]
        {
                true, false
        })
        {
            assertTrue(d.codeDecodePair("unii", "ADENOMA", "BENIGN", cs),
                    "reg='unii' misses but the attributes scan finds ADENOMA->BENIGN (cs=" + cs
                            + ")");
            assertTrue(d.codeDecodePair("unii", "R16CO5Y76E", "ASPIRIN", cs),
                    "preferred registry hit (cs=" + cs + ")");
            assertFalse(d.codeDecodePair("unii", "ADENOMA", "MALIGNANT", cs),
                    "no map carries this pair (cs=" + cs + ")");
            assertFalse(d.codeDecodePair("unii", null, "BENIGN", cs), "null code ⇒ not paired");
        }
        // The attributes fallback is flag-aware too.
        assertFalse(d.codeDecodePair("unii", "adenoma", "BENIGN", true),
                "sensitive: folded code misses the attributes map");
        assertTrue(d.codeDecodePair("unii", "adenoma", "benign", false),
                "insensitive: attributes fallback folds code and decode");
    }


    @Test
    void parsesInlineDocumentAndEmptyProvider() throws Exception
    {
        ValueMapDictionary d = ValueMapDictionary
                .parse(MAPPER.readTree("{\"type\":\"x\",\"levels\":{\"L\":{\"a\":\"A\"}}}"));
        assertTrue(d.isValidTerm("L", "A"));
        assertTrue(d.isValidTerm(null, "A"), "no level ⇒ search any level");
        assertFalse(d.isValidTerm("L", null));
        // A missing directory yields an empty provider — every type unavailable ⇒ rules SKIP.
        RuntimeDictionaryProvider empty = RuntimeDictionaryProvider
                .loadDirectory(Paths.get("no-such-dictionaries-dir"));
        assertFalse(empty.isAvailable("meddra"));
        RuntimeDictionaryProvider explicit = new RuntimeDictionaryProvider(Map.of("t", d));
        assertTrue(explicit.isAvailable("T"));
    }


    @Test
    void noLevelAndNullProbeEdgeBranches() throws Exception
    {
        // Two levels so anyLevel()/no-level scans have something to iterate over.
        ValueMapDictionary d = ValueMapDictionary.parse(
                MAPPER.readTree("{\"type\":\"x\",\"levels\":{\"PT\":{\"headache\":\"Headache\"},"
                        + "\"LLT\":{\"migraine\":\"Migraine\"}}}"));
        // isValidTerm with no level scans every level; a named level that is absent ⇒ false.
        assertTrue(d.isValidTerm(null, "MIGRAINE"), "no level ⇒ any-level membership");
        assertFalse(d.isValidTerm(null, "NOPE"), "no level, absent everywhere ⇒ false");
        assertFalse(d.isValidTerm("NOSUCHLEVEL", "HEADACHE"), "named level absent ⇒ false");
        // caseMatches with no level compares against the first (any) level's preferred case.
        assertTrue(d.caseMatches(null, "Headache"), "no level ⇒ preferred case of first level");
        assertFalse(d.caseMatches(null, "HEADACHE"), "no level, wrong case ⇒ false");
        assertFalse(d.caseMatches(null, "MIGRAINE"),
                "no level ⇒ only the first level is consulted, so an LLT-only term misses");
        assertFalse(d.caseMatches("PT", null), "null term ⇒ false");
    }


    @Test
    void emptyLevelsAndNullArgumentGuards() throws Exception
    {
        ValueMapDictionary empty = ValueMapDictionary.parse(MAPPER.readTree("{\"type\":\"x\"}"));
        assertFalse(empty.caseMatches(null, "X"), "no levels ⇒ anyLevel() null ⇒ false");
        assertFalse(empty.isValidTerm(null, null), "null term ⇒ false");
        // Hierarchy null-argument and unknown-term guards.
        ValueMapDictionary h = ValueMapDictionary
                .parse(MAPPER.readTree("{\"type\":\"x\",\"hierarchy\":{\"child\":[\"parent\"]}}"));
        assertTrue(h.onHierarchyPath("CHILD", "parent", false), "case-folded parent match");
        assertTrue(h.onHierarchyPath("child", "parent", true), "as-authored sensitive match");
        assertFalse(h.onHierarchyPath("CHILD", "parent", true),
                "sensitive: folded probe misses the as-authored key");
        assertFalse(h.onHierarchyPath(null, "parent", true), "null term ⇒ false");
        assertFalse(h.onHierarchyPath("child", null, true), "null parent ⇒ false");
        assertFalse(h.onHierarchyPath("unknown", "parent", true), "term with no parents ⇒ false");
        assertFalse(h.onHierarchyPath("unknown", "parent", false),
                "term with no parents ⇒ false (folded)");
        // termAttribute null-argument and missing-map guards.
        ValueMapDictionary a = ValueMapDictionary.parse(MAPPER
                .readTree("{\"type\":\"x\",\"attributes\":{\"class\":{\"adenoma\":\"BENIGN\"}}}"));
        assertNull(a.termAttribute(null, "adenoma"), "null attr ⇒ null");
        assertNull(a.termAttribute("class", null), "null term ⇒ null");
        assertNull(a.termAttribute("nosuch", "adenoma"), "absent attribute map ⇒ null");
    }


    @Test
    void loadDirectoryDerivesTypeFromFileStemWhenTypeAbsent(@TempDir Path dir) throws Exception
    {
        // A house-format file with no "type" field: the provider key falls back to the file stem.
        Files.writeString(dir.resolve("myloinc.json"),
                "{\"levels\":{\"CODE\":{\"1234-5\":\"1234-5\"}}}", StandardCharsets.UTF_8);
        // A non-JSON file in the same directory is ignored.
        Files.writeString(dir.resolve("README.txt"), "ignore me", StandardCharsets.UTF_8);
        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);
        assertTrue(p.isAvailable("myloinc"), "type defaults to the file stem 'myloinc'");
        assertTrue(p.isValidTerm("myloinc", "CODE", "1234-5"));
        assertFalse(p.isAvailable("readme"), "non-JSON files are not loaded");
    }
}
