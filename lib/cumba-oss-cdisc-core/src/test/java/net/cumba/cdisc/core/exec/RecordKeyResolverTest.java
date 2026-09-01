package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.RecordKeyResolver.KeySource;
import net.cumba.cdisc.core.exec.RecordKeyResolver.RowKeySpec;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RecordKeyResolver} — the EC-40 record-key tiers, the always-append /
 * always-subtract rules, and the per-row read.
 */
class RecordKeyResolverTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** A Library provider whose legacy path serves the given variable attribute maps. */
    private static MetadataProvider legacyLibrary(List<Map<String, String>> aVars)
    {
        MetadataProvider p = mock(MetadataProvider.class);
        // null from the detailed resolver => the legacy getDomainVariables fallback path.
        lenient().when(p.getStandardVariablesDetailed(any(), any())).thenReturn(null);
        lenient().when(p.getDomainVariables(anyString())).thenReturn(aVars);
        return p;
    }


    private static MetadataProvider defineWithKeys(String aDataset, List<String> aKeys)
    {
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getKeyVariables(anyString())).thenReturn(List.of());
        lenient().when(p.getKeyVariables(aDataset)).thenReturn(aKeys);
        return p;
    }


    @Test
    void offMode_resolvesNothingAtAll()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESEQ", "1").col("AESPID", "X")
                .name("AE").build();
        MetadataProvider define = defineWithKeys("AE", List.of("USUBJID", "AESEQ", "AESPID"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.OFF, define, null,
                NO_RESOLVER, "R1");

        assertSame(RowKeySpec.NONE, spec);
        assertTrue(spec.isEmpty());
        assertEquals(KeySource.NONE, spec.source());
    }


    @Test
    void defineKeyTier_usesSponsorKeyIntersectedWithColumns()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("VISITNUM", "1")
                .col("LBTESTCD", "GLUC").col("LBSEQ", "3").name("LB").build();
        // The sponsor declares a key that includes a variable the data does not carry (LBCAT).
        MetadataProvider define = defineWithKeys("LB",
                List.of("USUBJID", "LBTESTCD", "VISITNUM", "LBCAT"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        assertEquals(KeySource.DEFINE_KEY, spec.source());
        // KeySequence order preserved; USUBJID subtracted (carried on its own field); LBCAT
        // dropped because the dataset has no such column.
        assertEquals(List.of("LBTESTCD", "VISITNUM"), spec.names());
    }


    @Test
    void defineKeyTier_fallsBackToTheCdiscDomainCodeForASplitDataset()
    {
        // Split dataset: member name LBHE, DOMAIN=LB. The sponsor declares the key under LB.
        IDataTable table = MockTable.of().col("DOMAIN", "LB").col("USUBJID", "S1")
                .col("LBTESTCD", "GLUC").col("LBSEQ", "1").name("LBHE").build();
        MetadataProvider define = defineWithKeys("LB", List.of("USUBJID", "LBTESTCD"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LBHE", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        assertEquals(KeySource.DEFINE_KEY, spec.source());
        assertEquals(List.of("LBTESTCD"), spec.names());
    }


    @Test
    void structuralTier_suppDatasetKeyedByRdomainIdvarIdvarvalQnam()
    {
        // The case the whole tier exists for: SUPP-- has no sequence variable, so before EC-40
        // such a finding was located by USUBJID alone.
        IDataTable table = MockTable.of().col("STUDYID", "S").col("RDOMAIN", "AE")
                .col("USUBJID", "S1").col("IDVAR", "AESEQ").col("IDVARVAL", "3")
                .col("QNAM", "AESOSP").col("QVAL", "Y").name("SUPPAE").build();

        RowKeySpec spec = RecordKeyResolver.resolve(table, "SUPPAE", FindingKeyMode.DEFINE, null,
                null, NO_RESOLVER, "R1");

        assertEquals(KeySource.STRUCTURAL, spec.source());
        assertEquals(List.of("RDOMAIN", "IDVAR", "IDVARVAL", "QNAM"), spec.names());
    }


    @Test
    void structuralTier_relrecKeyedByRelid()
    {
        IDataTable table = MockTable.of().col("RDOMAIN", "AE").col("USUBJID", "S1")
                .col("IDVAR", "AESEQ").col("IDVARVAL", "3").col("RELID", "R01").name("RELREC")
                .build();

        RowKeySpec spec = RecordKeyResolver.resolve(table, "RELREC", FindingKeyMode.DEFINE, null,
                null, NO_RESOLVER, "R1");

        assertEquals(KeySource.STRUCTURAL, spec.source());
        assertEquals(List.of("RDOMAIN", "IDVAR", "IDVARVAL", "RELID"), spec.names());
    }


    @Test
    void structuralTier_commentsDataset()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "CO").col("RDOMAIN", "AE")
                .col("USUBJID", "S1").col("IDVAR", "AESEQ").col("IDVARVAL", "3").col("COSEQ", "1")
                .name("CO").build();

        RowKeySpec spec = RecordKeyResolver.resolve(table, "CO", FindingKeyMode.DEFINE, null, null,
                NO_RESOLVER, "R1");

        assertEquals(KeySource.STRUCTURAL, spec.source());
        // COSEQ is the dataset's own sequence variable and is carried on the Violation's seq
        // field, so it must not be repeated in the key.
        assertEquals(List.of("RDOMAIN", "IDVAR", "IDVARVAL"), spec.names());
    }


    @Test
    void naturalTier_addsTopicAndNaturalKeyRolesInFullMode()
    {
        IDataTable table = MockTable.of().col("STUDYID", "S").col("USUBJID", "S1")
                .col("LBTESTCD", "GLUC").col("VISITNUM", "1").col("LBSPEC", "BLOOD")
                .col("LBMETHOD", "M").col("LBSCAT", "C").col("LBSEQ", "1").name("LB").build();
        MetadataProvider library = legacyLibrary(
                List.of(Map.of("name", "USUBJID", "role", "Identifier"),
                        Map.of("name", "LBTESTCD", "role", "Topic"),
                        Map.of("name", "VISITNUM", "role", "Timing"),
                        Map.of("name", "LBSPEC", "role", "Grouping Qualifier"),
                        Map.of("name", "LBMETHOD", "role", "Record Qualifier"),
                        Map.of("name", "--SCAT", "role", "Variable Qualifier")));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.FULL, null, library,
                NO_RESOLVER, "R1");

        assertEquals(KeySource.NATURAL, spec.source());
        // Topic is included (unlike natural_key_variables, whose consuming rule adds --TESTCD
        // itself); Identifier USUBJID is not; the `--` wildcard resolves to the LB prefix.
        assertEquals(List.of("LBTESTCD", "VISITNUM", "LBSPEC", "LBMETHOD", "LBSCAT"), spec.names());
    }


    @Test
    void naturalTier_isNotReachedInDefineMode()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("LBTESTCD", "GLUC")
                .col("VISITNUM", "1").col("LBSEQ", "1").name("LB").build();
        MetadataProvider library = legacyLibrary(
                List.of(Map.of("name", "LBTESTCD", "role", "Topic"),
                        Map.of("name", "VISITNUM", "role", "Timing")));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.DEFINE, null,
                library, NO_RESOLVER, "R1");

        assertSame(RowKeySpec.NONE, spec);
    }


    @Test
    void naturalTier_isNotReachedWithoutALibraryProvider()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("LBTESTCD", "GLUC")
                .col("LBSEQ", "1").name("LB").build();

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.FULL, null, null,
                NO_RESOLVER, "R1");

        assertSame(RowKeySpec.NONE, spec);
    }


    @Test
    void alwaysAppend_addsSpidAndRefidOnTopOfTheWinningTier()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("LBTESTCD", "GLUC")
                .col("LBSPID", "SP1").col("LBREFID", "RF1").col("LBSEQ", "1").name("LB").build();
        MetadataProvider define = defineWithKeys("LB", List.of("USUBJID", "LBTESTCD"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        assertEquals(KeySource.DEFINE_KEY, spec.source());
        // The `--`-prefixed sponsor identifiers resolve against the LB prefix and land after the
        // tier's own columns.
        assertEquals(List.of("LBTESTCD", "LBSPID", "LBREFID"), spec.names());
    }


    @Test
    void alwaysAppend_keepsPoolIdForPooledSendDataWhereUsubjidIsBlank()
    {
        // Pooled SEND: USUBJID is blank and POOLID carries subject identity, so POOLID has to
        // survive regardless of which tier won.
        IDataTable table = MockTable.of().col("USUBJID", "").col("POOLID", "P1")
                .col("LBTESTCD", "GLUC").col("LBSEQ", "1").name("LB").build();
        MetadataProvider define = defineWithKeys("LB", List.of("LBTESTCD"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        assertEquals(List.of("LBTESTCD", "POOLID"), spec.names());
    }


    @Test
    void usubjidAndTheDomainSequenceAreNeverPartOfTheKey()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESEQ", "1").col("AETERM", "X")
                .name("AE").build();
        // A sponsor key consisting only of the two columns the engine already carries.
        MetadataProvider define = defineWithKeys("AE", List.of("USUBJID", "AESEQ"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        // Everything subtracted => nothing left to add beyond USUBJID/SEQ.
        assertSame(RowKeySpec.NONE, spec);
    }


    @Test
    void aseqIsSubtractedOnAnAdamDataset()
    {
        // ADaM: the sequence variable is ASEQ, not <DOMAIN>SEQ (EC-37 D5b).
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("ASEQ", "1")
                .col("PARAMCD", "GLUC").col("AVISITN", "1").name("ADLB").build();
        MetadataProvider define = defineWithKeys("ADLB",
                List.of("USUBJID", "PARAMCD", "AVISITN", "ASEQ"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "ADLB", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        assertEquals(List.of("PARAMCD", "AVISITN"), spec.names());
        assertFalse(spec.names().contains("ASEQ"));
    }


    @Test
    void noProvidersAtAll_resolvesNone()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESEQ", "1").col("AETERM", "X")
                .name("AE").build();

        RowKeySpec spec = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.FULL, null, null,
                NO_RESOLVER, "R1");

        assertSame(RowKeySpec.NONE, spec);
    }


    @Test
    void nullTable_resolvesNone()
    {
        assertSame(RowKeySpec.NONE, RecordKeyResolver.resolve(null, "AE", FindingKeyMode.FULL, null,
                null, NO_RESOLVER, "R1"));
    }


    @Test
    void readRowKeys_readsTheResolvedColumnsAndBlanksMissingCells()
    {
        IDataTable table = MockTable.of().col("RDOMAIN", "AE", "AE").col("USUBJID", "S1", "S2")
                .col("IDVAR", "AESEQ", null).col("IDVARVAL", "3", "4").col("QNAM", "Q1", "Q2")
                .name("SUPPAE").build();
        RowKeySpec spec = RecordKeyResolver.resolve(table, "SUPPAE", FindingKeyMode.DEFINE, null,
                null, NO_RESOLVER, "R1");

        assertEquals(Map.of("RDOMAIN", "AE", "IDVAR", "AESEQ", "IDVARVAL", "3", "QNAM", "Q1"),
                RecordKeyResolver.readRowKeys(table, spec, 0));
        // A missing cell reads as "", exactly as readRowIdentity handles USUBJID / SEQ.
        Map<String, String> row1 = RecordKeyResolver.readRowKeys(table, spec, 1);
        assertEquals("", row1.get("IDVAR"));
        assertEquals("4", row1.get("IDVARVAL"));
    }


    @Test
    void readRowKeys_emptySpecOrNegativeRowYieldsAnEmptyMap()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").name("AE").build();

        assertTrue(RecordKeyResolver.readRowKeys(table, RowKeySpec.NONE, 0).isEmpty());

        MetadataProvider define = defineWithKeys("AE", List.of("USUBJID"));
        RowKeySpec none = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");
        assertTrue(RecordKeyResolver.readRowKeys(table, none, -1).isEmpty());
    }


    @Test
    void alwaysAppendOnly_isLabelledSponsorIdNotNone()
    {
        // Review finding #1: no tier fires (no Define, not a structural shape, DEFINE mode blocks
        // NATURAL) but --SPID resolves. The key must not claim the "nothing resolved" source.
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESEQ", "1")
                .col("AESPID", "SP1").col("AETERM", "X").name("AE").build();

        RowKeySpec spec = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.DEFINE, null, null,
                NO_RESOLVER, "R1");

        assertEquals(List.of("AESPID"), spec.names());
        assertEquals(KeySource.SPONSOR_ID, spec.source());
    }


    @Test
    void constantColumnsAreNeverPartOfTheKey()
    {
        // Review finding #6: a Define key conventionally starts with STUDYID. STUDYID and DOMAIN
        // are constant within a data set, so keeping them would emit an "authoritative"
        // DEFINE_KEY whose every value is identical on every row.
        IDataTable table = MockTable.of().col("STUDYID", "S").col("DOMAIN", "AE")
                .col("USUBJID", "S1").col("AESEQ", "1").col("AETERM", "X").name("AE").build();
        MetadataProvider define = defineWithKeys("AE", List.of("STUDYID", "USUBJID", "AESEQ"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        // Everything in the declared key is either constant or already carried => no key at all.
        assertSame(RowKeySpec.NONE, spec);
    }


    @Test
    void columnMatchingFollowsTheTablesCaseSensitivitySetting()
    {
        // Review finding #3: DataTableMeta.getColumnIndex is case-insensitive by default, which is
        // how RuleRunner.readRowIdentity finds the sequence column. If the key used a
        // case-sensitive name set instead, the always-subtract would miss and the sequence value
        // would be emitted twice — once as SEQ, once inside the key.
        IDataTable table = MockTable.of().col("usubjid", "S1").col("aeseq", "1")
                .col("aespid", "SP1").col("aeterm", "X").name("AE").caseInsensitiveColumnNames()
                .build();
        MetadataProvider define = defineWithKeys("AE", List.of("USUBJID", "AESEQ", "AETERM"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "AE", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        // USUBJID and AESEQ subtracted despite the case difference; names come back in the data
        // set's own spelling so the per-row read resolves.
        assertEquals(List.of("aeterm", "aespid"), spec.names());
        assertFalse(spec.names().contains("aeseq"));
        assertEquals(Map.of("aeterm", "X", "aespid", "SP1"),
                RecordKeyResolver.readRowKeys(table, spec, 0));
    }


    @Test
    void keyOrderIsStableAndDeduplicated()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("LBTESTCD", "GLUC")
                .col("LBSPID", "SP").col("LBSEQ", "1").name("LB").build();
        MetadataProvider define = mock(MetadataProvider.class);
        // A sponsor key that repeats a column and already contains the always-append LBSPID.
        when(define.getKeyVariables("LB")).thenReturn(List.of("LBTESTCD", "LBSPID", "LBTESTCD"));

        RowKeySpec spec = RecordKeyResolver.resolve(table, "LB", FindingKeyMode.DEFINE, define,
                null, NO_RESOLVER, "R1");

        assertEquals(List.of("LBTESTCD", "LBSPID"), spec.names());
    }

}
