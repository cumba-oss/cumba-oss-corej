package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Group-B follow-up operations (E1 / E6 / E8 / E10). Each new
 * {@link net.cumba.cdisc.core.model.OperationType} arm is exercised through
 * {@link OperationExecutor#executeOne} and its raw result inspected — the focused counterpart to
 * {@link OperationExecutorLibraryOpsTest} (library ops) and
 * {@link net.cumba.cdisc.core.DictionaryValidationTest} (dictionary ops). GroupedResult verdicts
 * are read from the per-value results map (single-column keys equal the raw cell value).
 */
class OperationExecutorGroupBFuOpsTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }

    // -- E1 referenced_domain_class ---------------------------------------


    @Test
    void referencedDomainClass_mapsRdomainToLibraryClass()
    {
        IDataTable supp = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("RDOMAIN", "AE", "EX", "DM").name("SUPPAE").build();
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getDatasetClass("AE", "AE")).thenReturn("EVENTS");
        lenient().when(p.getDatasetClass("EX", "EX")).thenReturn("INTERVENTIONS");
        lenient().when(p.getDatasetClass("DM", "DM")).thenReturn("SPECIAL PURPOSE");

        Operation op = makeOp("$c", "referenced_domain_class"); // name defaults to RDOMAIN
        Object result = OperationExecutor.executeOne(op, supp, NO_RESOLVER, p, new HashMap<>());
        assertTrue(result instanceof GroupedResult, "GroupedResult keyed by RDOMAIN");
        GroupedResult gr = (GroupedResult) result;
        assertEquals(List.of("RDOMAIN"), gr.groupColumns());
        assertEquals("EVENTS", gr.results().get("AE"));
        assertEquals("INTERVENTIONS", gr.results().get("EX"));
        assertEquals("SPECIAL PURPOSE", gr.results().get("DM"));
    }


    @Test
    void referencedDomainClass_mixedCaseProviderClassIsUpperCased()
    {
        // The Library product reverse-walk (MetadataLibraryProvider.sdtmClassForDomain) returns
        // the CDISC Library's mixed-case class names; rules compare against the canonical
        // upper-case tokens, so the operation must normalise.
        IDataTable supp = MockTable.of().col("USUBJID", "S1", "S2").col("RDOMAIN", "AE", "FA")
                .name("SUPPAE").build();
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getDatasetClass("AE", "AE")).thenReturn("Events");
        lenient().when(p.getDatasetClass("FA", "FA")).thenReturn("Findings About");

        Operation op = makeOp("$c", "referenced_domain_class");
        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, supp, NO_RESOLVER, p,
                new HashMap<>());
        assertEquals("EVENTS", gr.results().get("AE"));
        assertEquals("FINDINGS ABOUT", gr.results().get("FA"));
    }


    @Test
    void referencedDomainClass_unclassifiableDomainYieldsEmptyString()
    {
        IDataTable supp = MockTable.of().col("USUBJID", "S1").col("RDOMAIN", "ZZ").name("SUPPZZ")
                .build();
        MetadataProvider p = mock(MetadataProvider.class); // getDatasetClass → null (default)

        Operation op = makeOp("$c", "referenced_domain_class");
        Object result = OperationExecutor.executeOne(op, supp, NO_RESOLVER, p, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        assertEquals("", gr.results().get("ZZ"), "null class ⇒ \"\" (Library cannot classify)");
    }


    @Test
    void referencedDomainClass_absentColumn_returnsLibraryNotAvailable()
    {
        IDataTable supp = MockTable.of().col("USUBJID", "S1").name("SUPPAE").build();
        MetadataProvider p = mock(MetadataProvider.class);

        Operation op = makeOp("$c", "referenced_domain_class"); // RDOMAIN column absent
        Object result = OperationExecutor.executeOne(op, supp, NO_RESOLVER, p, new HashMap<>());
        assertEquals("<library not available>", String.valueOf(result));
    }


    @Test
    void referencedDomainClass_nullProvider_returnsLibraryNotAvailable()
    {
        IDataTable supp = MockTable.of().col("RDOMAIN", "AE").name("SUPPAE").build();
        Operation op = makeOp("$c", "referenced_domain_class");
        Object result = OperationExecutor.executeOne(op, supp, NO_RESOLVER, null, new HashMap<>());
        assertEquals("<library not available>", String.valueOf(result));
    }

    // -- E6 interval_uncertainty_precision_mismatch -----------------------


    @Test
    void intervalUncertaintyPrecisionMismatch_firesOnlyOnDifferingPrecision()
    {
        IDataTable mh = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4", "S5")
                .col("MHSTDTC", "2020-01-01/2020-01", "2020-01-01/2020-06-15", "2020", "", "2020/")
                .name("MH").build();
        Operation op = makeOp("$m", "interval_uncertainty_precision_mismatch");
        op.setName("MHSTDTC");
        op.setDelimiter("/");

        Object result = OperationExecutor.executeOne(op, mh, NO_RESOLVER, null, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        assertEquals(true, gr.results().get("2020-01-01/2020-01"), "day vs month ⇒ mismatch");
        assertEquals(false, gr.results().get("2020-01-01/2020-06-15"), "day vs day ⇒ match");
        assertEquals(false, gr.results().get("2020"), "no delimiter ⇒ no fire");
        assertEquals(false, gr.results().get(""), "blank ⇒ no fire");
        assertEquals(false, gr.results().get("2020/"), "trailing-blank half ⇒ no fire");
    }


    @Test
    void intervalUncertaintyPrecisionMismatch_defaultDelimiterIsSolidus()
    {
        IDataTable mh = MockTable.of().col("MHSTDTC", "2020-01-01T10/2020-01-01").name("MH")
                .build();
        Operation op = makeOp("$m", "interval_uncertainty_precision_mismatch");
        op.setName("MHSTDTC"); // no delimiter set → default "/"

        Object result = OperationExecutor.executeOne(op, mh, NO_RESOLVER, null, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        // "2020-01-01T10" length 13 (hour) vs "2020-01-01" length 10 (day) ⇒ mismatch.
        assertEquals(true, gr.results().get("2020-01-01T10/2020-01-01"));
    }


    /**
     * {@code Fix #213} — a UTC offset carried on one half only is a difference of
     * <em>representation of the offset</em>, not of precision. Before the fix
     * {@code detectIsoPrecision} bucketed the raw halves by length (22 ⇒ tier 19 against 16 ⇒ tier
     * 16), so SEND70 raised a false positive on the SDTMIG's own worked example the moment a
     * timezone was attached to one side. Symmetric offsets cancel under length bucketing, so only
     * the mixed case bites — both shapes are pinned here.
     */
    @Test
    void intervalUncertaintyPrecisionMismatch_timezoneOnOneHalfIsNotAPrecisionDifference()
    {
        IDataTable mh = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4")
                .col("MHSTDTC", "2003-12-15T10:00+02:00/2003-12-15T10:30",
                        "2003-12-15T10:00/2003-12-15T10:30+02:00",
                        "2003-12-15T10:00+02:00/2003-12-15T10:30+02:00",
                        "2003-12-15T10:00Z/2003-12-15T10:30")
                .name("MH").build();
        Operation op = makeOp("$m", "interval_uncertainty_precision_mismatch");
        op.setName("MHSTDTC");
        op.setDelimiter("/");

        Object result = OperationExecutor.executeOne(op, mh, NO_RESOLVER, null, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        // assertAll so that neutering the fix reports every regressed shape, not just the first.
        assertAll(
                () -> assertEquals(false,
                        gr.results().get("2003-12-15T10:00+02:00/2003-12-15T10:30"),
                        "offset on the begin half only ⇒ both halves are minute precision"),
                () -> assertEquals(false,
                        gr.results().get("2003-12-15T10:00/2003-12-15T10:30+02:00"),
                        "offset on the end half only ⇒ both halves are minute precision"),
                () -> assertEquals(false,
                        gr.results().get("2003-12-15T10:00+02:00/2003-12-15T10:30+02:00"),
                        "symmetric offsets ⇒ no fire (already true before the fix)"),
                () -> assertEquals(false, gr.results().get("2003-12-15T10:00Z/2003-12-15T10:30"),
                        "a bare Z is stripped too — green before the fix as well, because a single "
                                + "extra character can never cross a length-bucket boundary"));
    }


    /**
     * {@code Fix #213} — the unnormalised comparison also produced a <em>false negative</em>: an
     * offset on the coarser half padded it up to the finer half's tier (both length 16), hiding a
     * real day-against-minute mismatch. Stripping first restores the fire.
     */
    @Test
    void intervalUncertaintyPrecisionMismatch_offsetNoLongerMasksARealMismatch()
    {
        IDataTable mh = MockTable.of().col("MHSTDTC", "2020-01-01+02:00/2020-01-01T10:30")
                .name("MH").build();
        Operation op = makeOp("$m", "interval_uncertainty_precision_mismatch");
        op.setName("MHSTDTC");

        Object result = OperationExecutor.executeOne(op, mh, NO_RESOLVER, null, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        assertEquals(true, gr.results().get("2020-01-01+02:00/2020-01-01T10:30"),
                "day vs minute ⇒ mismatch, even though both raw halves are 16 chars long");
    }


    /**
     * {@code Fix #213} — fractional seconds are stripped for the same reason as the offset: a
     * {@code .000} tail on one half only pushed it from tier 19 to tier 23.
     */
    @Test
    void intervalUncertaintyPrecisionMismatch_fractionalSecondsAreNotAPrecisionDifference()
    {
        IDataTable mh = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("MHSTDTC", "2003-12-15T10:00:00.000/2003-12-15T10:00:00",
                        "2003-12-15T10:00:00.000/2003-12-15T10:30:00.000",
                        "2003-12-15T10:00:00.000Z/2003-12-15T10:00:00")
                .name("MH").build();
        Operation op = makeOp("$m", "interval_uncertainty_precision_mismatch");
        op.setName("MHSTDTC");
        op.setDelimiter("/");

        Object result = OperationExecutor.executeOne(op, mh, NO_RESOLVER, null, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        assertAll(
                () -> assertEquals(false,
                        gr.results().get("2003-12-15T10:00:00.000/2003-12-15T10:00:00"),
                        "fractional seconds on the begin half only ⇒ both are second precision"),
                () -> assertEquals(false,
                        gr.results().get("2003-12-15T10:00:00.000/2003-12-15T10:30:00.000"),
                        "symmetric fractional seconds ⇒ no fire (already true before the fix)"),
                () -> assertEquals(false,
                        gr.results().get("2003-12-15T10:00:00.000Z/2003-12-15T10:00:00"),
                        "offset and fractional seconds together, on one half only"));
    }


    /**
     * {@code Fix #213} guard — the normalisation must not disable the rule. A genuine asymmetry of
     * completeness still fires, with and without an offset in play.
     */
    @Test
    void intervalUncertaintyPrecisionMismatch_genuineMismatchStillFires()
    {
        IDataTable mh = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("MHSTDTC", "2003-12-15T10:00/2003-12-15", "2003-12-15T10:00Z/2003-12-15Z",
                        "2003-12/2003-12-15")
                .name("MH").build();
        Operation op = makeOp("$m", "interval_uncertainty_precision_mismatch");
        op.setName("MHSTDTC");
        op.setDelimiter("/");

        Object result = OperationExecutor.executeOne(op, mh, NO_RESOLVER, null, new HashMap<>());
        GroupedResult gr = (GroupedResult) result;
        assertAll(
                () -> assertEquals(true, gr.results().get("2003-12-15T10:00/2003-12-15"),
                        "minute vs day ⇒ mismatch, no offsets involved"),
                () -> assertEquals(true, gr.results().get("2003-12-15T10:00Z/2003-12-15Z"),
                        "minute vs day ⇒ mismatch, symmetric offsets stripped from both"),
                () -> assertEquals(true, gr.results().get("2003-12/2003-12-15"),
                        "month vs day ⇒ mismatch"));
    }

    // -- E8 dictionary_has_decode -----------------------------------------


    @Test
    void dictionaryHasDecode_trueWhenDictionaryHoldsADecode() throws Exception
    {
        RuntimeDictionaryProvider dicts = RuntimeDictionaryProvider
                .loadDirectory(Paths.get("dictionaries"));
        IDataTable cm = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("CMTRT", "R16CO5Y76E", "ZZZNOTACODE", "").name("CM").build();
        Operation op = makeOp("$d", "dictionary_has_decode");
        op.setName("CMTRT");
        op.setExternalDictionaryType("unii");

        Object result = OperationExecutor.executeOne(op, cm, NO_RESOLVER, null, new HashMap<>(),
                null, dicts);
        GroupedResult gr = (GroupedResult) result;
        assertEquals(true, gr.results().get("R16CO5Y76E"), "code present in unii pairs");
        assertEquals(false, gr.results().get("ZZZNOTACODE"), "code absent ⇒ no decode");
        assertEquals(false, gr.results().get(""), "blank ⇒ no fire");
    }


    @Test
    void dictionaryHasDecode_nullDictionaryProvider_returnsNull()
    {
        IDataTable cm = MockTable.of().col("CMTRT", "R16CO5Y76E").name("CM").build();
        Operation op = makeOp("$d", "dictionary_has_decode");
        op.setName("CMTRT");
        op.setExternalDictionaryType("unii");

        Object result = OperationExecutor.executeOne(op, cm, NO_RESOLVER, null, new HashMap<>(),
                null, null);
        assertNull(result, "no dictionary ⇒ unresolvable ⇒ rule SKIPs");
    }

    // -- E10 split_sibling_length_mismatch --------------------------------


    @Test
    void splitSiblingLengthMismatch_returnsDivergentVariables()
    {
        IDataTable lb1 = MockTable.of().col("DOMAIN", "LB").col("LBORRES", "x").col("LBTEST", "t")
                .colMeta("LBORRES", null, 20, null).colMeta("LBTEST", null, 30, null).name("LB1")
                .build();
        IDataTable lb2 = MockTable.of().col("DOMAIN", "LB").col("LBORRES", "y").col("LBTEST", "u")
                .colMeta("LBORRES", null, 40, null).colMeta("LBTEST", null, 30, null).name("LB2")
                .build();
        DatasetResolver.WithInventory resolver = inventory(Map.of("LB1", lb1, "LB2", lb2));

        Operation op = makeOp("$mism", "split_sibling_length_mismatch");
        Object result = OperationExecutor.executeOne(op, lb1, resolver, null, new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> mism = (List<String>) result;
        assertEquals(List.of("LBORRES"), mism, "only LBORRES diverges (20 vs 40)");
    }


    @Test
    void splitSiblingLengthMismatch_notSplit_returnsEmpty()
    {
        IDataTable lb = MockTable.of().col("DOMAIN", "LB").col("LBORRES", "x")
                .colMeta("LBORRES", null, 20, null).name("LB").build();
        DatasetResolver.WithInventory resolver = inventory(Map.of("LB", lb));

        Operation op = makeOp("$mism", "split_sibling_length_mismatch");
        Object result = OperationExecutor.executeOne(op, lb, resolver, null, new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> mism = (List<String>) result;
        assertTrue(mism.isEmpty(), "single-member family cannot diverge");
    }


    @Test
    void splitSiblingLengthMismatch_noInventory_returnsEmpty()
    {
        IDataTable lb = MockTable.of().col("DOMAIN", "LB").col("LBORRES", "x").name("LB").build();
        Operation op = makeOp("$mism", "split_sibling_length_mismatch");
        Object result = OperationExecutor.executeOne(op, lb, NO_RESOLVER, null, new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> mism = (List<String>) result;
        assertTrue(mism.isEmpty(), "plain resolver (no WithInventory) ⇒ empty");
    }

    // -- E7 duplicate_label_variables -------------------------------------


    @Test
    void duplicateLabelVariables_returnsSharedLabelNames()
    {
        IDataTable ae = MockTable.of().col("AESTDTC", "x").col("AEENDTC", "y").col("AETERM", "z")
                .col("AEDECOD", "w").col("AELLT", "v").col("AESEV", "u")
                .colMeta("AESTDTC", "Start Date/Time", 0, null)
                .colMeta("AEENDTC", "End Date/Time", 0, null)
                .colMeta("AETERM", "Reported Term", 0, null)
                .colMeta("AEDECOD", "Reported Term", 0, null)
                .colMeta("AELLT", "Reported Term", 0, null).colMeta("AESEV", "Severity", 0, null)
                .name("AE").build();
        Operation op = makeOp("$dup", "duplicate_label_variables");
        Object result = OperationExecutor.executeOne(op, ae, NO_RESOLVER, null, new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> dup = (List<String>) result;
        assertEquals(List.of("AETERM", "AEDECOD", "AELLT"), dup,
                "all three columns sharing \"Reported Term\" are returned in column order");
    }


    @Test
    void duplicateLabelVariables_noDuplicates_returnsEmpty()
    {
        IDataTable ae = MockTable.of().col("AESTDTC", "x").col("AETERM", "z")
                .colMeta("AESTDTC", "Start Date/Time", 0, null)
                .colMeta("AETERM", "Reported Term", 0, null).name("AE").build();
        Operation op = makeOp("$dup", "duplicate_label_variables");
        Object result = OperationExecutor.executeOne(op, ae, NO_RESOLVER, null, new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> dup = (List<String>) result;
        assertTrue(dup.isEmpty(), "unique labels ⇒ no duplicates");
    }


    @Test
    void duplicateLabelVariables_blankLabelsIgnored()
    {
        // Two columns with no declared label must not count as a duplicate label bucket.
        IDataTable ae = MockTable.of().col("AAA", "x").col("BBB", "y").name("AE").build();
        Operation op = makeOp("$dup", "duplicate_label_variables");
        Object result = OperationExecutor.executeOne(op, ae, NO_RESOLVER, null, new HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> dup = (List<String>) result;
        assertTrue(dup.isEmpty(), "blank/absent labels are not treated as duplicates");
    }

    // -- E7 column_series_metadata ----------------------------------------


    @Test
    void columnSeriesMetadata_completeSeries_doesNotFire()
    {
        // base COVAL + COVAL1 + COVAL2 -> suffixes {0,1,2} contiguous, no min_length -> complete.
        IDataTable co = MockTable.of().col("COVAL", "a").col("COVAL1", "b").col("COVAL2", "c")
                .name("CO").build();
        Operation op = makeOp("$s", "column_series_metadata");
        op.setNamePattern("^COVAL\\d+$");
        op.setName("COVAL");
        Object result = OperationExecutor.executeOne(op, co, NO_RESOLVER, null, new HashMap<>());
        assertEquals(false, result, "contiguous series is complete ⇒ no fire");
    }


    @Test
    void columnSeriesMetadata_gapInSuffixes_fires()
    {
        // COVAL1 + COVAL3 (COVAL2 missing) -> suffixes {1,3} not contiguous -> incomplete.
        IDataTable co = MockTable.of().col("COVAL1", "b").col("COVAL3", "d").name("CO").build();
        Operation op = makeOp("$s", "column_series_metadata");
        op.setNamePattern("^COVAL\\d+$");
        Object result = OperationExecutor.executeOne(op, co, NO_RESOLVER, null, new HashMap<>());
        assertEquals(true, result, "gap in the numeric suffixes ⇒ series incomplete ⇒ fire");
    }


    @Test
    void columnSeriesMetadata_shortNonTerminalMember_fires()
    {
        // base COVAL (declared length 100) + COVAL1 -> contiguous, but with min_length 200 the
        // non-terminal COVAL is too short (continuation not yet warranted) -> fire.
        IDataTable co = MockTable.of().col("COVAL", "a").col("COVAL1", "b")
                .colMeta("COVAL", "Comment", 100, null).colMeta("COVAL1", "Comment 1", 200, null)
                .name("CO").build();
        Operation op = makeOp("$s", "column_series_metadata");
        op.setNamePattern("^COVAL\\d+$");
        op.setName("COVAL");
        op.setMinLength(200);
        Object result = OperationExecutor.executeOne(op, co, NO_RESOLVER, null, new HashMap<>());
        assertEquals(true, result, "a short non-terminal continuation member fires");
    }


    @Test
    void columnSeriesMetadata_singleMember_doesNotFire()
    {
        IDataTable co = MockTable.of().col("COVAL1", "b").name("CO").build();
        Operation op = makeOp("$s", "column_series_metadata");
        op.setNamePattern("^COVAL\\d+$");
        Object result = OperationExecutor.executeOne(op, co, NO_RESOLVER, null, new HashMap<>());
        assertEquals(false, result, "fewer than two members ⇒ no series to flag");
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String domainName)
            {
                return tables.get(domainName);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return tables.keySet();
            }
        };
    }
}
