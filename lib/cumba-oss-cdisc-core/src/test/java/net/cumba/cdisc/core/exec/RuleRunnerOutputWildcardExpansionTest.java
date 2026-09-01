package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * EC-12 (Option A) — a driver-free {@code ${*}} wildcard in {@code Output_Variables} expands, once
 * per execution, to one concrete {@code <foreign>.<column>} entry per matching foreign-dataset
 * column. Exercises {@link RuleRunner#extractOutputValues} directly with a joined ADSL fixture.
 */
class RuleRunnerOutputWildcardExpansionTest
{

    @BeforeEach
    void resetCaches()
    {
        WildcardForeignColumnCache.clearForTesting();
    }


    /** A JoinLookup that reads the single ADSL row for whichever column is asked. */
    private static JoinLookup adslLookup(IDataTable adsl)
    {
        return new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                int ci = adsl.getMetaData().getColumnIndex(columnName);
                if (ci < 0)
                {
                    return null;
                }
                IDataValue dv = adsl.getColumn(ci).getDataValue(0);
                return dv.isMissingOrInvalid() ? "" : dv.getValueAsString();
            }


            @Override
            public boolean hasColumn(IDataTable primaryTable, long row, String columnName)
            {
                return adsl.getMetaData().getColumnIndex(columnName) >= 0;
            }


            @Override
            public String getDatasetName()
            {
                return "ADSL";
            }
        };
    }


    private static IDataTable adslFixture()
    {
        return MockTable.of().col("USUBJID", "S1").col("PH1STM", "2020-01-01")
                .col("PH2STM", "2020-02-01").col("PH10STM", "2020-10-01").col("PHXSTM", "bad")
                .col("PHSTM", "nodigit").name("ADSL").build();
    }


    private static IDataTable adaeFixture()
    {
        return MockTable.of().col("USUBJID", "S1", "S1").colLong("AESEQ", 1L, 2L).name("ADAE")
                .build();
    }


    private static EvaluationContext ctxWith(IDataTable adae, IDataTable adsl)
    {
        return EvaluationContext.builder().table(adae)
                .datasetResolver(name -> "ADSL".equals(name) ? adsl : null)
                .joinedDatasets(Map.of("ADSL", adslLookup(adsl))).domainPrefix("AE").build();
    }


    @Test
    void wildcardOv_expandsToAllMatchingForeignColumns()
    {
        IDataTable adae = adaeFixture();
        IDataTable adsl = adslFixture();
        EvaluationContext ctx = ctxWith(adae, adsl);

        Map<String, String> values = RuleRunner.extractOutputValues(adae, ctx,
                List.of("ADSL.PH${*}STM"), 0);

        assertEquals("2020-01-01", values.get("ADSL.PH1STM"));
        assertEquals("2020-02-01", values.get("ADSL.PH2STM"));
        assertEquals("2020-10-01", values.get("ADSL.PH10STM"));
        // Non-digit runs must not match ^PH\d+STM$.
        assertFalse(values.containsKey("ADSL.PHXSTM"), "PHXSTM has a non-digit run");
        assertFalse(values.containsKey("ADSL.PHSTM"), "PHSTM has no digit run");
    }


    @Test
    void wildcardOv_coexistsWithDashPrefixOv()
    {
        IDataTable adae = adaeFixture();
        IDataTable adsl = adslFixture();
        EvaluationContext ctx = ctxWith(adae, adsl);

        // "--SEQ" (no ${*}) is resolved by resolveOutputVarWildcards to AESEQ and is unaffected
        // by the new wildcard expansion; the ${*} entry expands alongside it.
        Map<String, String> values = RuleRunner.extractOutputValues(adae, ctx,
                List.of("--SEQ", "ADSL.PH${*}STM"), 0);

        assertEquals("1", values.get("AESEQ"));
        assertTrue(values.containsKey("ADSL.PH1STM"));
        assertTrue(values.containsKey("ADSL.PH2STM"));
    }


    @Test
    void wildcardOv_absentForeignDataset_omitted()
    {
        IDataTable adae = adaeFixture();
        // Resolver returns null for ADSL: the wildcard entry is dropped entirely.
        EvaluationContext ctx = EvaluationContext.builder().table(adae).datasetResolver(_ -> null)
                .domainPrefix("AE").build();

        Map<String, String> values = RuleRunner.extractOutputValues(adae, ctx,
                List.of("ADSL.PH${*}STM"), 0);

        assertTrue(values.isEmpty(), "unresolved foreign dataset -> wildcard OV omitted");
    }


    @Test
    void wildcardOv_noMatchingColumns_omitted()
    {
        IDataTable adae = adaeFixture();
        IDataTable adsl = adslFixture();
        EvaluationContext ctx = ctxWith(adae, adsl);

        Map<String, String> values = RuleRunner.extractOutputValues(adae, ctx,
                List.of("ADSL.ZZ${*}QQ"), 0);

        assertTrue(values.isEmpty(), "no ADSL column matches -> nothing emitted");
    }


    @Test
    void wildcardOv_expansionIsRowInvariant()
    {
        IDataTable adae = adaeFixture();
        IDataTable adsl = adslFixture();
        EvaluationContext ctx = ctxWith(adae, adsl);

        Map<String, String> row0 = RuleRunner.extractOutputValues(adae, ctx,
                List.of("ADSL.PH${*}STM"), 0);
        Map<String, String> row1 = RuleRunner.extractOutputValues(adae, ctx,
                List.of("ADSL.PH${*}STM"), 1);

        // Same expanded key set on both rows: the foreign column set is fixed for the run.
        assertEquals(row0.keySet(), row1.keySet());
        assertEquals(List.of("ADSL.PH1STM", "ADSL.PH2STM", "ADSL.PH10STM"),
                List.copyOf(row0.keySet()));
    }


    @Test
    void wildcardOv_multiStar_perSubperiodTemplate()
    {
        // EC-16 dependency: two ${*} runs (P${*}S${*}SDT) expand against P<n>S<m>SDT columns.
        IDataTable adae = adaeFixture();
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P1S1SDT", "d11")
                .col("P1S2SDT", "d12").col("P2S1SDT", "d21").col("PXSYSDT", "bad").name("ADSL")
                .build();
        EvaluationContext ctx = ctxWith(adae, adsl);

        Map<String, String> values = RuleRunner.extractOutputValues(adae, ctx,
                List.of("ADSL.P${*}S${*}SDT"), 0);

        assertEquals("d11", values.get("ADSL.P1S1SDT"));
        assertEquals("d12", values.get("ADSL.P1S2SDT"));
        assertEquals("d21", values.get("ADSL.P2S1SDT"));
        assertFalse(values.containsKey("ADSL.PXSYSDT"));
    }
}
