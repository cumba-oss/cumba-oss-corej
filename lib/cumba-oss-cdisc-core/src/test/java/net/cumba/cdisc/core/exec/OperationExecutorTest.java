package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationExecutorTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    // -----------------------------------------------------------------------
    // variable_count
    // -----------------------------------------------------------------------

    @Test
    void testVariableCount()
    {
        IDataTable table = MockTable.of().col("A", "1", "2").col("B", "3", "4").col("C", "5", "6")
                .build();

        Operation op = makeOp("$VAR_COUNT", "variable_count");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(3L, vars.get("$VAR_COUNT"));
    }

    // -----------------------------------------------------------------------
    // record_count
    // -----------------------------------------------------------------------


    @Test
    void testRecordCount_noFilter()
    {
        IDataTable table = MockTable.of().col("X", "a", "b", "c").build();

        Operation op = makeOp("$ROW_COUNT", "record_count");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(3L, vars.get("$ROW_COUNT"));
    }


    @Test
    void testRecordCount_withFilter()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "AE", "AE", "DM", "AE")
                .col("SEX", "M", "F", "M", "M").build();

        Operation op = makeOp("$AE_COUNT", "record_count");
        op.setFilter(Map.of("DOMAIN", "AE"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(3L, vars.get("$AE_COUNT"));
    }


    @Test
    void testRecordCount_withPrefixWildcardFilter()
    {
        IDataTable table = MockTable.of().col("QNAM", "RACE1", "RACE2", "OTHER", "RACE3").build();

        // "&" and "%" are equivalent trailing prefix wildcards: both match QNAM starting "RACE".
        Operation amp = makeOp("$amp", "record_count");
        amp.setFilter(Map.of("QNAM", "RACE&"));
        Operation pct = makeOp("$pct", "record_count");
        pct.setFilter(Map.of("QNAM", "RACE%"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(amp, pct), table, NO_RESOLVER);

        assertEquals(3L, vars.get("$amp"));
        assertEquals(3L, vars.get("$pct"), "trailing % prefix-wildcard matches like &");
    }


    @Test
    void testRecordCount_withMultiFilter()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "AE", "AE", "DM", "AE")
                .col("SEX", "M", "F", "M", "M").build();

        Operation op = makeOp("$AE_M_COUNT", "record_count");
        op.setFilter(Map.of("DOMAIN", "AE", "SEX", "M"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(2L, vars.get("$AE_M_COUNT"));
    }

    // -----------------------------------------------------------------------
    // distinct
    // -----------------------------------------------------------------------


    @Test
    void testDistinct()
    {
        IDataTable table = MockTable.of().col("SEX", "M", "F", "M", "F", "U").build();

        Operation op = makeOp("$distinct_sex", "distinct");
        op.setName("SEX");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$distinct_sex");
        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.contains("M"));
        assertTrue(result.contains("F"));
        assertTrue(result.contains("U"));
    }


    @Test
    void testDistinct_withMissing()
    {
        IDataTable table = MockTable.of().col("VAL", "A", null, "B", "A", null).build();

        Operation op = makeOp("$vals", "distinct");
        op.setName("VAL");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$vals");
        assertEquals(2, result.size());
        assertTrue(result.contains("A"));
        assertTrue(result.contains("B"));
    }


    @Test
    void testDistinct_missingColumn()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$vals", "distinct");
        op.setName("NONEXISTENT");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$vals");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }


    @Test
    void testDistinct_withFilter()
    {
        // Shared filter enabler: flat distinct honors op.getFilter() — only values from rows that
        // satisfy the filter are collected (parity with Python distinct's _filter_data).
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02", "S03", "S04")
                .col("DSDECOD", "TERMINAL SACRIFICE", "MORIBUND SACRIFICE", "SCHEDULED SACRIFICE",
                        "TERMINAL SACRIFICE")
                .build();

        Operation op = makeOp("$scheduled", "distinct");
        op.setName("USUBJID");
        op.setFilter(Map.of("DSDECOD", "TERMINAL SACRIFICE"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$scheduled");
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("S01"));
        assertTrue(result.contains("S04"));
        assertFalse(result.contains("S02"), "MORIBUND row is filtered out");
        assertFalse(result.contains("S03"), "SCHEDULED row is filtered out");
    }


    @Test
    void testDistinct_withListMembershipFilter()
    {
        // E5: a list-valued filter (DSDECOD in {A, B}) keeps rows whose cell is a member of the
        // list (parity with Python's `.isin(...)`). Single-value filters keep strict equality;
        // a missing filter column matches no row.
        IDataTable table = MockTable
                .of().col("USUBJID", "S01", "S02", "S03", "S04").col("DSDECOD",
                        "INFORMED CONSENT OBTAINED", "RANDOMIZED", "SCREEN FAILURE", "RANDOMIZED")
                .build();

        Operation op = makeOp("$subjects", "distinct");
        op.setName("USUBJID");
        op.setFilter(Map.of("DSDECOD", List.of("INFORMED CONSENT OBTAINED", "RANDOMIZED")));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$subjects");
        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.contains("S01"), "INFORMED CONSENT OBTAINED is a member");
        assertTrue(result.contains("S02"), "RANDOMIZED is a member");
        assertTrue(result.contains("S04"), "RANDOMIZED is a member");
        assertFalse(result.contains("S03"), "SCREEN FAILURE is not in the list");

        // Single-value equality still holds (backward compatible).
        Operation eq = makeOp("$eq", "distinct");
        eq.setName("USUBJID");
        eq.setFilter(Map.of("DSDECOD", "RANDOMIZED"));
        @SuppressWarnings("unchecked")
        List<String> eqResult = (List<String>) OperationExecutor
                .execute(List.of(eq), table, NO_RESOLVER).get("$eq");
        assertNotNull(eqResult);
        assertEquals(List.of("S02", "S04"), eqResult);

        // A missing filter column matches no row.
        Operation missing = makeOp("$none", "distinct");
        missing.setName("USUBJID");
        missing.setFilter(Map.of("NOSUCH", List.of("RANDOMIZED")));
        @SuppressWarnings("unchecked")
        List<String> noneResult = (List<String>) OperationExecutor
                .execute(List.of(missing), table, NO_RESOLVER).get("$none");
        assertNotNull(noneResult);
        assertTrue(noneResult.isEmpty(), "missing filter column => empty subset");
    }


    @Test
    void testRecordCount_withListMembershipFilter()
    {
        // record_count over a list-membership filter counts rows whose cell is a member.
        IDataTable table = MockTable.of().col("DSDECOD", "RANDOMIZED", "SCREEN FAILURE",
                "RANDOMIZED", "INFORMED CONSENT OBTAINED").build();

        Operation op = makeOp("$cnt", "record_count");
        op.setFilter(Map.of("DSDECOD", List.of("RANDOMIZED", "INFORMED CONSENT OBTAINED")));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);
        assertEquals(3L, vars.get("$cnt"));
    }


    @Test
    void testDistinctTuples_withFilter()
    {
        // The composite (names-list) distinct path mirrors the flat one: filtered rows are skipped
        // before the tuple set is built.
        IDataTable table = MockTable.of().col("DOMAIN", "AE", "DM", "AE", "AE")
                .col("USUBJID", "S01", "S02", "S01", "S03")
                .col("AEDECOD", "HEADACHE", "NA", "HEADACHE", "NAUSEA").build();

        Operation op = makeOp("$pairs", "distinct");
        op.setNames(List.of("USUBJID", "AEDECOD"));
        op.setFilter(Map.of("DOMAIN", "AE"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        Set<List<String>> result = (Set<List<String>>) vars.get("$pairs");
        assertNotNull(result);
        assertEquals(2, result.size(),
                "the DM row is filtered out; duplicate S01/HEADACHE collapses");
        assertTrue(result.contains(List.of("S01", "HEADACHE")));
        assertTrue(result.contains(List.of("S03", "NAUSEA")));
        assertFalse(result.contains(List.of("S02", "NA")), "DM row filtered out");
    }

    // -----------------------------------------------------------------------
    // max
    // -----------------------------------------------------------------------


    @Test
    void testMax()
    {
        IDataTable table = MockTable.of().col("SCORE", "10", "25", "5", "20").build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(25.0, vars.get("$max_score"));
    }


    @Test
    void testMax_withMissing()
    {
        IDataTable table = MockTable.of().col("SCORE", "10", null, "30").build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(30.0, vars.get("$max_score"));
    }

    // -----------------------------------------------------------------------
    // EC-28(b) / Fix #131 — `--` in an Operation filter KEY resolves like a variable name
    // -----------------------------------------------------------------------


    @Test
    void resolvePrefixes_wildcardFilterKey_resolvesWithTheVariablePrefix()
    {
        // The FDA-SD1240 re-shape this unlocks: distinct(USUBJID) filtered on --BLFL = "Y".
        // Before Fix #131 the key stayed "--BLFL", matched no column, and the filter silently
        // selected nothing.
        Operation op = makeOp("$baseline_subjects", "distinct");
        op.setName("USUBJID");
        op.setFilter(Map.of("--BLFL", "Y"));

        Operation resolved = OperationExecutor.resolvePrefixes(op, "LB", "LB");

        assertEquals(Map.of("LBBLFL", "Y"), resolved.getFilter());
    }


    @Test
    void resolvePrefixes_wildcardFilterKey_usesTheVariablePrefixNotTheDomainCode()
    {
        // EC-36's side-of-dot rule: a filter key names a COLUMN, so on SUPP the variable prefix
        // is "" (SUPPAE carries QNAM, not AEQNAM) even though the domain-code prefix is SUPPAE.
        Operation op = makeOp("$q", "distinct");
        op.setName("USUBJID");
        op.setFilter(Map.of("--QNAM", "AESOSP"));

        Operation resolved = OperationExecutor.resolvePrefixes(op, "SUPPAE", "");

        assertEquals(Map.of("QNAM", "AESOSP"), resolved.getFilter());
    }


    @Test
    void resolvePrefixes_filterWithoutWildcard_isReturnedUnchanged()
    {
        // 342 shipped Operations carry a filter and none has a `--` key; the no-wildcard path
        // must stay identity (same instance, no copy) so nothing about them moves.
        Operation op = makeOp("$ts", "distinct");
        op.setName("TSVAL");
        Map<String, Object> filter = Map.of("TSPARMCD", "PLANSUB");
        op.setFilter(filter);

        Operation resolved = OperationExecutor.resolvePrefixes(op, "TS", "TS");

        assertSame(filter, resolved.getFilter());
    }


    @Test
    void testMax_allMissing()
    {
        IDataTable table = MockTable.of().col("SCORE", null, null).build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertNull(vars.get("$max_score"));
    }


    @Test
    void testMax_filter_numeric()
    {
        IDataTable table = MockTable.of().col("SCORE", "10", "25", "5").col("ABLFL", "Y", "", "Y")
                .build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        // max over the ABLFL=Y rows only: 10, not the unfiltered 25
        assertEquals(10.0, vars.get("$max_score"));
    }


    @Test
    void testMax_filter_stringFallback()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-01-15", "2024-12-31", "2024-03-20")
                .col("ABLFL", "Y", "", "Y").build();

        Operation op = makeOp("$max_dtc", "max");
        op.setName("DTC");
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        // no numeric values -> string fallback, still filtered: 2024-03-20, not 2024-12-31
        assertEquals("2024-03-20", vars.get("$max_dtc"));
    }


    @Test
    void testMax_filter_noMatchingRows()
    {
        IDataTable table = MockTable.of().col("SCORE", "10", "25").col("ABLFL", "", "").build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertNull(vars.get("$max_score"));
    }


    @Test
    void testMax_filter_prefixWildcard()
    {
        IDataTable table = MockTable.of().col("SCORE", "5", "50", "7")
                .col("VISIT", "BASELINE 1", "WEEK 2", "BASELINE 2").build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        op.setFilter(Map.of("VISIT", "BASELINE&"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(7.0, vars.get("$max_score"));
    }

    // -----------------------------------------------------------------------
    // max_date / min_date
    // -----------------------------------------------------------------------


    @Test
    void testMaxDate()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-01-15", "2024-03-20", "2024-02-10")
                .build();

        Operation op = makeOp("$max_dtc", "max_date");
        op.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals("2024-03-20", vars.get("$max_dtc"));
    }


    @Test
    void testMinDate()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-01-15", "2024-03-20", "2024-02-10")
                .build();

        Operation op = makeOp("$min_dtc", "min_date");
        op.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals("2024-01-15", vars.get("$min_dtc"));
    }


    @Test
    void testMinDate_withMissing()
    {
        IDataTable table = MockTable.of().col("DTC", null, "2024-06-01", null).build();

        Operation op = makeOp("$min_dtc", "min_date");
        op.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals("2024-06-01", vars.get("$min_dtc"));
    }

    // -----------------------------------------------------------------------
    // EC-51 — the shared candidate filter (`extremeCandidate`)
    // -----------------------------------------------------------------------


    /**
     * An EMPTY cell is not a candidate. This was already true, but it was re-inlined at five
     * selector sites rather than stated once; EC-51 routes them all through one predicate, and this
     * pins that the behaviour did not move.
     */
    @Test
    void extremeCandidate_emptyStringIsNotACandidate()
    {
        IDataTable table = MockTable.of().col("DTC", "", "2024-06-01", "").build();

        Operation min = makeOp("$min", "min_date");
        min.setName("DTC");
        Operation max = makeOp("$max", "max_date");
        max.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(min, max), table, NO_RESOLVER);

        assertEquals("2024-06-01", vars.get("$min"), "an empty cell must not win the min");
        assertEquals("2024-06-01", vars.get("$max"));
    }


    /**
     * EC-51's one behaviour change: a WHITESPACE-ONLY cell is no longer a candidate. It used to win
     * every {@code min}, because {@code " "} (0x20) sorts below every digit (0x30+) and the old
     * inlined test used {@code isEmpty()} rather than {@code strip().isEmpty()}. Providers are
     * expected to right-trim; this makes a stray blank harmless rather than decisive.
     */
    @Test
    void extremeCandidate_whitespaceOnlyIsNotACandidate()
    {
        IDataTable table = MockTable.of().col("DTC", " ", "2024-06-01", "\t").build();

        Operation min = makeOp("$min", "min_date");
        min.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(min), table, NO_RESOLVER);

        assertEquals("2024-06-01", vars.get("$min"),
                "a whitespace-only cell used to win the min; EC-51 excludes it");
    }


    /**
     * The candidate filter strips only to DECIDE; the selected extreme is still the verbatim cell
     * text, so Fix #137's {@code Q2 = raw string} contract is preserved.
     */
    @Test
    void extremeCandidate_returnsRawTextNotStripped()
    {
        IDataTable table = MockTable.of().col("DTC", " 2024-06-01 ", "2024-07-01").build();

        Operation min = makeOp("$min", "min_date");
        min.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(min), table, NO_RESOLVER);

        assertEquals(" 2024-06-01 ", vars.get("$min"), "the raw cell text must be returned");
    }


    /**
     * The grouped path is the one all 27 shipped {@code min_date}/{@code max_date} rules take, and
     * the one where the fork diverged: pandas {@code .min()} skips {@code NaN} but not {@code ""},
     * so a blank won every grouped min there while coreJ skipped it. Both lanes now agree.
     */
    @Test
    void extremeCandidate_groupedSkipsBlanksPerGroup()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("EXSTDTC", "", "2024-03-01", " ", "2024-06-01").build();

        Operation op = makeOp("$min_ex", "min_date");
        op.setName("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = assertInstanceOf(GroupedResult.class, vars.get("$min_ex"));
        assertEquals("2024-03-01", grouped.results().get("S01"));
        assertEquals("2024-06-01", grouped.results().get("S02"));
    }


    /**
     * On the max side the change is not "whitespace loses" — it never could win a max, since
     * {@code " "} sorts below every digit — it is that the <b>result disappears</b>. Every other
     * test here keeps a populated value in the candidate set, so this is the only one that pins the
     * vanishing.
     */
    @Test
    void extremeCandidate_allWhitespaceYieldsNoResultAtAll()
    {
        IDataTable table = MockTable.of().col("DTC", " ", "\t").build();

        Operation max = makeOp("$max", "max_date");
        max.setName("DTC");
        Operation min = makeOp("$min", "min_date");
        min.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(max, min), table, NO_RESOLVER);

        assertNull(vars.get("$max"), "previously returned \" \"; now there is no candidate at all");
        assertNull(vars.get("$min"));
    }


    /** The same vanishing, per group, on the max path. */
    @Test
    void extremeCandidate_allWhitespaceGroupEmitsNoKeyOnTheMaxPath()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02")
                .col("EXSTDTC", " ", "\t", "2024-06-01").build();

        Operation op = makeOp("$max_ex", "max_date");
        op.setName("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = assertInstanceOf(GroupedResult.class, vars.get("$max_ex"));
        assertFalse(grouped.results().containsKey("S01"),
                "an all-whitespace group used to carry a \" \" entry");
        assertEquals("2024-06-01", grouped.results().get("S02"));
    }


    /**
     * Blankness is defined explicitly rather than via {@code String.strip()}, whose
     * {@code Character.isWhitespace} basis excludes the non-breaking spaces that Python's
     * {@code str.strip()} removes. Without this the two lanes would disagree on an NBSP-only cell.
     */
    @Test
    void extremeCandidate_nonBreakingSpacesCountAsBlank()
    {
        IDataTable table = MockTable.of().col("DTC", "\u00A0", "\u202F", "2024-06-01").build();

        Operation min = makeOp("$min", "min_date");
        min.setName("DTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(min), table, NO_RESOLVER);

        assertEquals("2024-06-01", vars.get("$min"),
                "NBSP / narrow NBSP are blank on both lanes, so neither may win");
    }


    /** A group with no usable candidate emits no key at all — unchanged by EC-51. */
    @Test
    void extremeCandidate_groupWithNoCandidateEmitsNoKey()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02")
                .col("EXSTDTC", "", " ", "2024-06-01").build();

        Operation op = makeOp("$min_ex", "min_date");
        op.setName("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = assertInstanceOf(GroupedResult.class, vars.get("$min_ex"));
        assertFalse(grouped.results().containsKey("S01"), "no usable candidate ⇒ no key");
        assertEquals("2024-06-01", grouped.results().get("S02"));
    }

    // -----------------------------------------------------------------------
    // extract_metadata
    // -----------------------------------------------------------------------


    @Test
    void testExtractMetadata_datasetName()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        Operation op = makeOp("$ds_name", "extract_metadata");
        op.setName("dataset_name");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals("DM", vars.get("$ds_name"));
    }


    @Test
    void testExtractMetadata_datasetLabel()
    {
        IDataTable table = MockTable.of().col("X", "1").label("Demographics").build();

        Operation op = makeOp("$ds_label", "extract_metadata");
        op.setName("dataset_label");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals("Demographics", vars.get("$ds_label"));
    }

    // -----------------------------------------------------------------------
    // Domain resolution
    // -----------------------------------------------------------------------


    @Test
    void testDistinct_withDomain()
    {
        IDataTable mainTable = MockTable.of().col("AETERM", "Headache", "Nausea").build();

        IDataTable dmTable = MockTable.of().col("USUBJID", "S01", "S02", "S01", "S03").build();

        Operation op = makeOp("$dm_usubjid", "distinct");
        op.setName("USUBJID");
        op.setDomain("DM");

        DatasetResolver resolver = name -> "DM".equals(name) ? dmTable : null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), mainTable, resolver);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$dm_usubjid");
        assertEquals(3, result.size());
        assertTrue(result.contains("S01"));
        assertTrue(result.contains("S02"));
        assertTrue(result.contains("S03"));
    }


    @Test
    void testDomain_notAvailable()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$result", "distinct");
        op.setName("COL");
        op.setDomain("MISSING_DOMAIN");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        // Q17-a: an unresolvable target is no longer an unclassified null — the operator publishes
        // its declared EmptyResult, and `distinct` declares SET. The membership fold treats null
        // and the empty list identically, so no check changes verdict; what changes is that the
        // result is now classified rather than absent.
        assertEquals(List.of(), vars.get("$result"));
    }

    // -----------------------------------------------------------------------
    // Domain wildcard resolution
    // -----------------------------------------------------------------------


    @Test
    void testWildcard_suppDashDash_resolved()
    {
        // Target table is AE, operation domain is "SUPP--" → resolver gets "SUPPAE"
        IDataTable aeTable = MockTable.of().col("USUBJID", "S01", "S02").name("AE").build();

        IDataTable suppaeTable = MockTable.of().col("IDVAR", "AESEQ", "AESEQ", "AEGRPID").build();

        Operation op = makeOp("$supp_idvars", "distinct");
        op.setName("IDVAR");
        op.setDomain("SUPP--");

        DatasetResolver resolver = name -> "SUPPAE".equals(name) ? suppaeTable : null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), aeTable, resolver);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$supp_idvars");
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("AESEQ"));
        assertTrue(result.contains("AEGRPID"));
    }


    @Test
    void testWildcard_apDashDash_resolved()
    {
        IDataTable pooldefTable = MockTable.of().col("X", "1").name("POOLDEF").build();

        Operation op = makeOp("$count", "variable_count");
        op.setDomain("AP--");

        DatasetResolver resolver = name -> "APPOOLDEF".equals(name) ? pooldefTable : null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), pooldefTable, resolver);

        assertEquals(1L, vars.get("$count"));
    }


    @Test
    void testWildcard_noDashDash_passedThrough()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();

        IDataTable dmTable = MockTable.of().col("USUBJID", "S01").build();

        Operation op = makeOp("$dm_subj", "distinct");
        op.setName("USUBJID");
        op.setDomain("DM");

        DatasetResolver resolver = name -> "DM".equals(name) ? dmTable : null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, resolver);

        assertNotNull(vars.get("$dm_subj"));
    }


    @Test
    void testWildcard_noTableName_passedLiterally()
    {
        // Table has no name set — wildcard can't be resolved, passed as-is
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$result", "variable_count");
        op.setDomain("SUPP--");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        // Resolver gets "SUPP--" literally and returns null, so no target dataset resolves.
        // Q17-a: variable_count declares EmptyResult.COUNT, so the unresolvable target answers 0
        // rather than dropping out of the map. The point of the case is unchanged — the wildcard
        // was NOT resolved, which a real count over the (nameless) current table would have shown
        // as 1.
        assertEquals(0L, vars.get("$result"));
    }


    @Test
    void testSplitSuppSelfReference_fallsBackToCurrentTable()
    {
        // J7 part 2 (CORE-000712): on a SPLIT SUPP dataset (supplbch/he/ur) the value_is_reference
        // distinct's domain wildcard collapses to the unsplit family name ("SUPPLB"), which
        // resolves
        // to null because no standalone "SUPPLB" dataset exists. resolveTargetTable must fall back
        // to
        // the current table (the operation is self-referential), so the distinct runs against its
        // own
        // RDOMAIN instead of being skipped to an empty membership set (the bug fired every row).
        IDataTable suppTable = MockTable.of().col("RDOMAIN", "LB", "LB")
                .col("IDVAR", "LBSEQ", "LBSEQ").name("SUPPLBCH").build();
        IDataTable lbchTable = MockTable.of().col("DOMAIN", "LB").col("USUBJID", "U1")
                .col("LBSEQ", "1").name("LBCH").build();

        DatasetResolver.WithInventory resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return "LBCH".equals(name) ? lbchTable : null; // "SUPPLB" / "LB" → null
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("LBCH");
            }
        };

        Operation op = makeOp("$rdomain_variables", "distinct");
        op.setName("IDVAR");
        op.setDomain("SUPPLB"); // the already-collapsed unsplit family name
        op.setValueIsReference(true);

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), suppTable, resolver);

        Object result = vars.get("$rdomain_variables");
        assertInstanceOf(GroupedResult.class, result);
        @SuppressWarnings("unchecked")
        List<String> lbVars = (List<String>) ((GroupedResult) result).results().get("LB");
        assertNotNull(lbVars);
        assertTrue(lbVars.contains("LBSEQ"));
    }


    @Test
    void testMissingDomain_noSelfReference_stillSkips()
    {
        // Guard: the split-self fallback only triggers when the current table name starts with the
        // unresolved domain. A genuinely-absent unrelated domain must NOT be redirected to the
        // current table. Q17-a: it now answers `distinct`'s declared EmptyResult (the empty set)
        // instead of dropping out of the map — and the empty set is what proves no redirection
        // happened, because a redirect would have read column X and yielded ["1"].
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();

        Operation op = makeOp("$result", "distinct");
        op.setName("X");
        op.setDomain("ZZ");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(List.of(), vars.get("$result"));
    }


    @Test
    void testNonSuppSplitMember_literalDomainPrefix_notRedirected()
    {
        // Over-correction guard (Phase-7 review): the self-reference fallback is restricted to
        // SUPP/SQAP current tables. A non-SUPP split member ("LBCH") with a literal cross-domain
        // reference whose name merely prefixes it ("LB") must NOT be redirected to the current
        // table — the operation genuinely targets the (absent) "LB" domain. Q17-a: that now reads
        // as the empty set rather than an absent key; a wrongly-redirected operation would have
        // read column X off LBCH and yielded ["1"], so the assertion still catches the
        // over-correction it was written for.
        IDataTable table = MockTable.of().col("X", "1").name("LBCH").build();

        Operation op = makeOp("$result", "distinct");
        op.setName("X");
        op.setDomain("LB"); // "LBCH".startsWith("LB") is true, but LBCH is not SUPP/SQAP

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals(List.of(), vars.get("$result"));
    }

    // -----------------------------------------------------------------------
    // Unknown operation type
    // -----------------------------------------------------------------------


    @Test
    void testUnknownOperationType_skipped()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$result", "some_unknown_op");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertFalse(vars.containsKey("$result"));
    }

    // -----------------------------------------------------------------------
    // Chaining: later ops see earlier results
    // -----------------------------------------------------------------------


    @Test
    void testOperationChaining()
    {
        IDataTable table = MockTable.of().col("A", "1", "2", "3").col("B", "x", "y", "z").build();

        Operation op1 = makeOp("$count", "variable_count");
        Operation op2 = makeOp("$rows", "record_count");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op1, op2), table, NO_RESOLVER);

        assertEquals(2L, vars.get("$count"));
        assertEquals(3L, vars.get("$rows"));
    }

    // -----------------------------------------------------------------------
    // Grouped operations
    // -----------------------------------------------------------------------


    @Test
    void testMinDate_grouped()
    {
        // CORE-000239 pattern: min_date of EXSTDTC grouped by USUBJID
        IDataTable exTable = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02", "S01")
                .col("EXSTDTC", "2024-03-01", "2024-01-15", "2024-06-01", "2024-02-10",
                        "2024-02-01")
                .build();

        Operation op = makeOp("$min_ex_exstdtc", "min_date");
        op.setName("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), exTable, NO_RESOLVER);

        Object result = vars.get("$min_ex_exstdtc");
        assertInstanceOf(GroupedResult.class, result);
        GroupedResult grouped = (GroupedResult) result;

        // But the check runs on the DM table, so let's verify the raw map
        assertEquals(List.of("USUBJID"), grouped.groupColumns());
        assertEquals("2024-01-15", grouped.results().get("S01"));
        assertEquals("2024-02-10", grouped.results().get("S02"));
    }


    @Test
    void testMaxDate_grouped()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("DTC", "2024-01-01", "2024-06-01", "2024-03-01", "2024-02-01").build();

        Operation op = makeOp("$max_dtc", "max_date");
        op.setName("DTC");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = (GroupedResult) vars.get("$max_dtc");
        assertEquals("2024-06-01", grouped.results().get("S01"));
        assertEquals("2024-03-01", grouped.results().get("S02"));
    }


    @Test
    void testMaxDate_filter()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-01-15", "2024-12-31", "2024-03-20")
                .col("ABLFL", "Y", "", "Y").build();

        Operation op = makeOp("$max_dtc", "max_date");
        op.setName("DTC");
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        // max_date over the ABLFL=Y rows only: 2024-03-20, not 2024-12-31
        assertEquals("2024-03-20", vars.get("$max_dtc"));
    }


    @Test
    void testMinDate_filter()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-05-15", "2024-01-01", "2024-03-20")
                .col("ABLFL", "Y", "", "Y").build();

        Operation op = makeOp("$min_dtc", "min_date");
        op.setName("DTC");
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        // min_date over the ABLFL=Y rows only: 2024-03-20, not 2024-01-01
        assertEquals("2024-03-20", vars.get("$min_dtc"));
    }


    @Test
    void testMaxDate_filter_noMatchingRows()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-01-15", "2024-12-31")
                .col("ABLFL", "", "").build();

        Operation op = makeOp("$max_dtc", "max_date");
        op.setName("DTC");
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertNull(vars.get("$max_dtc"));
    }


    @Test
    void testMaxDate_grouped_filter()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("DTC", "2024-01-01", "2024-06-01", "2024-03-01", "2024-02-01")
                .col("ABLFL", "Y", "", "", "").build();

        Operation op = makeOp("$max_dtc", "max_date");
        op.setName("DTC");
        op.setGroup(List.of("USUBJID"));
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = (GroupedResult) vars.get("$max_dtc");
        // S01: max over its ABLFL=Y row -> 2024-01-01 (not 2024-06-01);
        // S02: no ABLFL=Y row -> no entry (missing value semantics)
        assertEquals("2024-01-01", grouped.results().get("S01"));
        assertNull(grouped.results().get("S02"));
    }


    @Test
    void testMinDate_grouped_filter()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02")
                .col("DTC", "2024-05-01", "2024-01-15", "2024-06-01").col("ABLFL", "Y", "", "Y")
                .build();

        Operation op = makeOp("$min_dtc", "min_date");
        op.setName("DTC");
        op.setGroup(List.of("USUBJID"));
        op.setFilter(Map.of("ABLFL", "Y"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = (GroupedResult) vars.get("$min_dtc");
        // S01: min over its ABLFL=Y row -> 2024-05-01 (not 2024-01-15)
        assertEquals("2024-05-01", grouped.results().get("S01"));
        assertEquals("2024-06-01", grouped.results().get("S02"));
    }


    @Test
    void testRecordCount_grouped()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S01", "S02")
                .col("DSCAT", "DISPOSITION EVENT", "OTHER", "DISPOSITION EVENT",
                        "DISPOSITION EVENT", "DISPOSITION EVENT")
                .build();

        Operation op = makeOp("$count", "record_count");
        op.setGroup(List.of("USUBJID"));
        op.setFilter(Map.of("DSCAT", "DISPOSITION EVENT"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = (GroupedResult) vars.get("$count");
        assertEquals(2L, grouped.results().get("S01"));
        assertEquals(2L, grouped.results().get("S02"));
    }


    @Test
    void testDistinct_grouped()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02", "S01")
                .col("AEOUT", "FATAL", "RECOVERED", "FATAL", "FATAL", "FATAL").build();

        Operation op = makeOp("$ae_aeout", "distinct");
        op.setName("AEOUT");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = (GroupedResult) vars.get("$ae_aeout");
        @SuppressWarnings("unchecked")
        List<String> s01 = (List<String>) grouped.results().get("S01");
        assertEquals(2, s01.size());
        assertTrue(s01.contains("FATAL"));
        assertTrue(s01.contains("RECOVERED"));

        @SuppressWarnings("unchecked")
        List<String> s02 = (List<String>) grouped.results().get("S02");
        assertEquals(1, s02.size());
        assertTrue(s02.contains("FATAL"));
    }


    @Test
    void testMax_grouped()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("SCORE", "10", "30", "20", "5").build();

        Operation op = makeOp("$max_score", "max");
        op.setName("SCORE");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = (GroupedResult) vars.get("$max_score");
        assertEquals(30.0, grouped.results().get("S01"));
        assertEquals(20.0, grouped.results().get("S02"));
    }

    // -----------------------------------------------------------------------
    // get_column_order_from_dataset
    // -----------------------------------------------------------------------


    @Test
    void testGetColumnOrderFromDataset()
    {
        IDataTable table = MockTable.of().col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        Operation op = makeOp("$col_order", "get_column_order_from_dataset");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) vars.get("$col_order");
        assertEquals(List.of("STUDYID", "USUBJID", "SEX"), order);
    }

    // -----------------------------------------------------------------------
    // extract_metadata (null name)
    // -----------------------------------------------------------------------


    @Test
    void testExtractMetadata_nullName()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Operation op = makeOp("$meta", "extract_metadata");
        op.setName(null);
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertNull(vars.get("$meta"));
    }

    // -----------------------------------------------------------------------
    // max (additional edge cases)
    // -----------------------------------------------------------------------


    @Test
    void testMax_missingColumn()
    {
        IDataTable table = MockTable.of().col("OTHER", "1").build();

        Operation op = makeOp("$max", "max");
        op.setName("NONEXISTENT");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertNull(vars.get("$max"));
    }

    // -----------------------------------------------------------------------
    // Library-dependent operations
    // -----------------------------------------------------------------------


    @Test
    void testLibraryOps_noProvider_returnsSkipSentinel()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        Operation op = makeOp("$req", "required_variables");
        // No library provider → execute with null provider
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, null);

        // The sentinel value indicates library not available
        assertNotNull(vars.get("$req"));
    }


    @Test
    void testLibraryOps_withProvider()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();

        MetadataProvider provider = new MetadataProvider()
        {

            @Override
            public List<String> getRequiredVariables(String domain)
            {
                return List.of("STUDYID", "USUBJID");
            }


            @Override
            public List<String> getExpectedVariables(String d)
            {
                return List.of();
            }


            @Override
            public List<String> getColumnOrder(String d)
            {
                return List.of();
            }


            @Override
            public List<String> getModelColumnOrder(String d)
            {
                return List.of();
            }


            @Override
            public boolean isDomainCustom(String d)
            {
                return false;
            }


            @Override
            public List<String> getCodelistTerms(String c)
            {
                return List.of();
            }


            @Override
            public Map<String, String> getVariableMetadata(String d, String v)
            {
                return Map.of();
            }


            @Override
            public List<Map<String, String>> getDomainVariables(String d)
            {
                return List.of();
            }


            @Override
            public Map<String, String> getDatasetMetadata(String d)
            {
                return Map.of();
            }


            @Override
            public boolean isCodelistExtensible(String cl)
            {
                return true;
            }


            @Override
            public Map<String, String> getCodelistTermMappings(String cl)
            {
                return Map.of();
            }


            @Override
            public String getStandard()
            {
                return "SDTMIG";
            }


            @Override
            public String getVersion()
            {
                return "3.4";
            }
        };

        Operation op = makeOp("$req", "required_variables");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER,
                provider);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$req");
        assertEquals(List.of("STUDYID", "USUBJID"), result);
    }

    // -----------------------------------------------------------------------
    // codelist_terms — codelist named via the `codelists` field (CORE-000929)
    // -----------------------------------------------------------------------


    @Test
    void testCodelistTerms_resolvedFromCodelistsField()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "DM").name("DM").build();

        MetadataProvider provider = mock(MetadataProvider.class);
        when(provider.getCodelistTerms("DOMAIN")).thenReturn(List.of("C12345", "C67890"));

        // The codelist is named via `codelists`, not `name` (the shape that used to NPE).
        Operation op = makeOp("$domain_lib_ccode", "codelist_terms");
        op.setCodelists(List.of("DOMAIN"));
        op.setLevel("term");
        op.setReturntype("code");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER,
                provider);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$domain_lib_ccode");
        assertEquals(List.of("C12345", "C67890"), result);
    }


    @Test
    void testCodelistTerms_unionsMultipleCodelists()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "DM").name("DM").build();

        MetadataProvider provider = mock(MetadataProvider.class);
        when(provider.getCodelistTerms("DOMAIN")).thenReturn(List.of("C1", "C2"));
        when(provider.getCodelistTerms("EXTRA")).thenReturn(List.of("C2", "C3"));

        Operation op = makeOp("$codes", "codelist_terms");
        op.setCodelists(List.of("DOMAIN", "EXTRA"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER,
                provider);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$codes");
        // Union, de-duplicated, order preserved.
        assertEquals(List.of("C1", "C2", "C3"), result);
    }


    @Test
    void testCodelistTerms_emptyTermsSkipsRule()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "DM").name("DM").build();

        MetadataProvider provider = mock(MetadataProvider.class);
        when(provider.getCodelistTerms("DOMAIN")).thenReturn(List.of());

        Operation op = makeOp("$domain_lib_ccode", "codelist_terms");
        op.setCodelists(List.of("DOMAIN"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER,
                provider);

        // No define.xml / CT package → empty terms → library-not-available sentinel so the rule is
        // SKIPPED, rather than flagging every row against an empty list.
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, vars.get("$domain_lib_ccode"));
    }


    @Test
    void testGetCodelistAttributes_resolvesPackageFromRowColumns()
    {
        // get_codelist_attributes derives a CT package id from the row's target column
        // (name=TSVCDREF → "CDISC") + version column (version=TSVCDVER → "2024-09-27") + the
        // standard ("sdtmig" → prefix "sdtmct"), then extracts the named ct_attribute. It must NOT
        // route through getCodelistTerms(name) the way codelist_terms does.
        IDataTable table = MockTable.of().col("TSVCDREF", "CDISC").col("TSVCDVER", "2024-09-27")
                .name("TS").build();

        MetadataProvider provider = mock(MetadataProvider.class);
        when(provider.getStandard()).thenReturn("sdtmig");
        when(provider.getCodelistAttribute("sdtmct-2024-09-27", "Term CCODE"))
                .thenReturn(List.of("C1", "C2"));

        Operation op = makeOp("$VALID_TERM_CODES", "get_codelist_attributes");
        op.setName("TSVCDREF");
        op.setVersion("TSVCDVER");
        op.setCtAttribute("Term CCODE");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER,
                provider);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$VALID_TERM_CODES");
        assertEquals(List.of("C1", "C2"), result);
    }

    // -----------------------------------------------------------------------
    // EC-46 — a date extreme has a value only when a DETERMINED candidate wins
    // against every possible completion of every other candidate.
    //
    // MIN: complete c wins iff c <= lower(x) for every candidate x.
    // MAX: complete c wins iff c >= upper(x) for every candidate x.
    // Otherwise the group yields NO VALUE (no map key at all).
    // -----------------------------------------------------------------------


    /**
     * The control that must not move: with no partials — the shape of every group in
     * {@code /data/testdata} — selection is exactly what it was before EC-46.
     */
    @Test
    void ec46_allCompleteIsUnchanged()
    {
        IDataTable table = MockTable.of()
                .col("DTC", "2012-06-15", "2012-06-01", "2012-06-30", "2012-05-31").build();

        Map<String, Object> vars = OperationExecutor.execute(
                List.of(dateOp("$min", "min_date", "DTC"), dateOp("$max", "max_date", "DTC")),
                table, NO_RESOLVER);

        assertEquals("2012-05-31", vars.get("$min"));
        assertEquals("2012-06-30", vars.get("$max"));
    }


    /**
     * <b>Defect A's headline pair.</b> {@code max{2012-06, 2012-06-15}} used to return
     * {@code 2012-06-15}; the partial's latest completion is {@code 2012-06-30}, which is later, so
     * the max cannot be determined and the operation yields nothing.
     */
    @Test
    void ec46_maxIsIndeterminateWhenAPartialCouldBeLater()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06", "2012-06-15").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$max", "max_date", "DTC")), table, NO_RESOLVER);

        assertNull(vars.get("$max"), "2012-06 could be the 30th, so no max is determined");
    }


    /**
     * The benign MAX tie: the partial <i>cannot</i> end after the last day of its own month, so the
     * complete date on that day is the determinate max. This is the case that makes the upper-bound
     * test non-strict.
     */
    @Test
    void ec46_maxResolvesWhenThePartialCannotReachPastTheCompleteDate()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06", "2012-06-30").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$max", "max_date", "DTC")), table, NO_RESOLVER);

        assertEquals("2012-06-30", vars.get("$max"));
    }


    /** A non-prefix pair: the partial's whole hull is earlier, so the max is determinate. */
    @Test
    void ec46_maxResolvesForANonPrefixPair()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-05", "2012-06-15").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$max", "max_date", "DTC")), table, NO_RESOLVER);

        assertEquals("2012-06-15", vars.get("$max"));
    }


    /**
     * <b>OQ1.</b> {@code min{2012-06, 2012-06-01}} — no completion of {@code 2012-06} precedes
     * {@code 2012-06-01}, so the complete date wins. Before EC-46 the engine strictly preferred the
     * unusable partial and destroyed the group (Defect B).
     */
    @Test
    void ec46_oq1TheCompleteDateWinsWhenThePartialCannotBeatIt()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06", "2012-06-01").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$min", "min_date", "DTC")), table, NO_RESOLVER);

        assertEquals("2012-06-01", vars.get("$min"));
    }


    /**
     * <b>OQ1's explicit exclusion.</b> {@code min{2012-06, 2012-06-02}} — the partial <i>can</i>
     * still win (it might be the 1st), so nothing is determined. This is why the rule must NOT be
     * implemented as "prefer the more precise candidate on a tie": that would wrongly answer here.
     */
    @Test
    void ec46_oq1ExclusionThePartialCanStillWin()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06", "2012-06-02").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$min", "min_date", "DTC")), table, NO_RESOLVER);

        assertNull(vars.get("$min"), "2012-06 could be the 1st, which precedes 2012-06-02");
    }


    /** The order of arrival must not change the answer — the rule is not a pairwise fold. */
    @Test
    void ec46_resultIsIndependentOfRowOrder()
    {
        IDataTable a = MockTable.of().col("DTC", "2012-06-02", "2012-06-01", "2012-06").build();
        IDataTable b = MockTable.of().col("DTC", "2012-06", "2012-06-01", "2012-06-02").build();

        Object minA = OperationExecutor
                .execute(List.of(dateOp("$min", "min_date", "DTC")), a, NO_RESOLVER).get("$min");
        Object minB = OperationExecutor
                .execute(List.of(dateOp("$min", "min_date", "DTC")), b, NO_RESOLVER).get("$min");

        assertEquals("2012-06-01", minA);
        assertEquals(minA, minB);
    }


    /**
     * <b>Defect C.</b> Lexically {@code …T00:00:00Z} sorts first, but {@code …T01:00:00+02:00} is
     * the earlier instant. The offset value must win the min.
     */
    @Test
    void ec46_offsetsAreNormalisedBeforeSelection()
    {
        IDataTable table = MockTable.of()
                .col("DTC", "2012-06-15T00:00:00Z", "2012-06-15T01:00:00+02:00").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$min", "min_date", "DTC")), table, NO_RESOLVER);

        assertEquals("2012-06-15T01:00:00+02:00", vars.get("$min"),
                "the +02:00 value is 23:00Z the previous day — the earlier instant");
    }


    /**
     * <b>Defect D.</b> A day-masked value is a month-wide hull, so it cannot be pinned against a
     * complete date inside that month — indeterminate, where lexically {@code 2012-06--} would have
     * lost the min to nothing and won nothing useful.
     */
    @Test
    void ec46_maskedDayIsAMonthWideHull()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06--", "2012-06-15").build();

        Map<String, Object> vars = OperationExecutor.execute(
                List.of(dateOp("$min", "min_date", "DTC"), dateOp("$max", "max_date", "DTC")),
                table, NO_RESOLVER);

        assertNull(vars.get("$min"), "the masked day could be the 1st");
        assertNull(vars.get("$max"), "the masked day could be the 30th");
    }


    /** A masked day that cannot reach past the complete date still resolves. */
    @Test
    void ec46_maskedDayResolvesAgainstTheMonthEnd()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06--", "2012-06-30").build();

        Map<String, Object> vars = OperationExecutor
                .execute(List.of(dateOp("$max", "max_date", "DTC")), table, NO_RESOLVER);

        assertEquals("2012-06-30", vars.get("$max"));
    }


    /**
     * <b>Defect E — the change lands on MAX.</b> A present-but-unpositionable value already won
     * every MIN by sorting first; it used to LOSE every MAX, so a falsely-certain max was computed
     * from the other candidates. Now both directions yield nothing.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "----06-15", "UNKNOWN", "2012-13-45"
    })
    void ec46_aPresentUnpositionableValueMakesBothExtremesIndeterminate(String junk)
    {
        IDataTable table = MockTable.of().col("DTC", junk, "2012-06-15", "2012-07-20").build();

        Map<String, Object> vars = OperationExecutor.execute(
                List.of(dateOp("$min", "min_date", "DTC"), dateOp("$max", "max_date", "DTC")),
                table, NO_RESOLVER);

        assertNull(vars.get("$min"), junk + " must make the min indeterminate");
        assertNull(vars.get("$max"), junk + " must make the max indeterminate too (this is new)");
    }


    /**
     * <b>The control for Defect E, and the line that must never be inverted.</b> An empty cell is
     * "no data", not "an unknown date": it stays SKIPPED. If blanks made a group indeterminate,
     * nearly every group would be — most contain a blank date — and all 35 rules would fire almost
     * everywhere.
     */
    @Test
    void ec46_emptyCellsAreStillSkippedNotIndeterminate()
    {
        IDataTable table = MockTable.of().col("DTC", "", "2012-06-15", "   ", "2012-07-20").build();

        Map<String, Object> vars = OperationExecutor.execute(
                List.of(dateOp("$min", "min_date", "DTC"), dateOp("$max", "max_date", "DTC")),
                table, NO_RESOLVER);

        assertEquals("2012-06-15", vars.get("$min"), "a blank must not make the min indeterminate");
        assertEquals("2012-07-20", vars.get("$max"));
    }


    /** The grouped path — the one all 27 shipped rules take. Groups resolve independently. */
    @Test
    void ec46_groupedIndeterminacyIsPerGroup()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("DTC", "2012-06", "2012-06-15", "2012-03-01", "2012-04-01").build();

        Operation op = dateOp("$max", "max_date", "DTC");
        op.setGroup(List.of("USUBJID"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        GroupedResult grouped = assertInstanceOf(GroupedResult.class, vars.get("$max"));
        assertFalse(grouped.results().containsKey("S01"),
                "S01 is indeterminate and must emit NO KEY, as an all-blank group already did");
        assertEquals("2012-04-01", grouped.results().get("S02"), "S02 is unaffected");
    }


    /**
     * <b>A deliberate consequence on MIXED-PRECISION complete values, pinned so it is not
     * rediscovered as a bug.</b> {@code 2012-06-15} and {@code 2012-06-15T10:30:00} are both
     * "determined" (day precision is the atom for a date extreme — if it were not, a column of
     * plain dates would have no eligible winner at all and every group would yield nothing). But a
     * day-precision value spans its whole day, so on the MAX side it beats a same-day timestamp,
     * where the old raw {@code compareTo} preferred the longer string.
     *
     * <p>
     * Zero exposure today: across all 988 {@code /data/testdata} files the extreme-read columns
     * hold 71 215 values and <b>none</b> carries a time component. Recorded because it is the one
     * place EC-46 moves an all-complete group.
     * </p>
     */
    @Test
    void ec46_dayPrecisionSpansItsDayAgainstASameDayTimestamp()
    {
        IDataTable table = MockTable.of().col("DTC", "2012-06-15", "2012-06-15T10:30:00").build();

        Map<String, Object> vars = OperationExecutor.execute(
                List.of(dateOp("$min", "min_date", "DTC"), dateOp("$max", "max_date", "DTC")),
                table, NO_RESOLVER);

        assertEquals("2012-06-15", vars.get("$min"), "midnight is the earliest instant of the day");
        assertEquals("2012-06-15", vars.get("$max"),
                "the day-precision cell could be as late as 23:59:59, so it takes the max");
    }


    /**
     * <b>OQ4.</b> The generic {@code max} string fallback is NOT date-only — {@code ANRIND} (5
     * rules) and {@code ATOXGR} (4) reach it with Char category codes. Applying date semantics
     * there would yield no value and silence them, so a group that is not unambiguously dates keeps
     * plain lexicographic order.
     */
    @Test
    void ec46_oq4GenericMaxKeepsLexicographicOrderForNonDates()
    {
        IDataTable table = MockTable.of().col("ANRIND", "NORMAL", "HIGH", "LOW").build();

        Operation op = makeOp("$max", "max");
        op.setName("ANRIND");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertEquals("NORMAL", vars.get("$max"), "a category column must keep lexicographic max");
    }


    /** …but an unambiguously-date group on the generic operator does get EC-46's rule. */
    @Test
    void ec46_oq4GenericMaxAppliesTheDateRuleToADateGroup()
    {
        IDataTable table = MockTable.of().col("DSSTDTC", "2012-06", "2012-06-15").build();

        Operation op = makeOp("$max", "max");
        op.setName("DSSTDTC");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER);

        assertNull(vars.get("$max"), "all candidates are dates, so EC-46's rule applies");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static Operation dateOp(String id, String operator, String column)
    {
        Operation op = makeOp(id, operator);
        op.setName(column);
        return op;
    }


    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }

}
