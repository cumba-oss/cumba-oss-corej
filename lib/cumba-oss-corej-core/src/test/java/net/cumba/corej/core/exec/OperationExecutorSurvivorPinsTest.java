package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Value-level pins for {@link OperationExecutor} evaluators whose result is the operand a rule
 * compares against.
 *
 * <p>
 * <b>Why this matters.</b> A {@code $}-operation result is not a diagnostic — it is one half of
 * every comparison the rule makes. If {@code supp_qnam_value} keys its join on the wrong parent
 * column, or {@code max} ignores its {@code filter}, or {@code extract_metadata("filename")}
 * returns the directory instead of the file, then a <em>correct</em> rule silently fires on the
 * wrong records. No rule reviewer can see that. Every assertion below therefore pins the exact
 * value the operation publishes, with a negative case for each branch.
 * </p>
 */
// Test fixture exposing LinkedHashMap for ordered iteration in the assertions.
@SuppressWarnings("NonApiType")
class OperationExecutorSurvivorPinsTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }

    // ==================================================================
    // extract_metadata("filename" / "dataset_location") — the source-URI basename.
    // A rule that checks the submitted file name against the dataset name reads
    // exactly this string; an off-by-one in the path split renames every dataset.
    // ==================================================================


    private static @Nullable Object filenameOf(@Nullable String uri)
    {
        MockTable mt = MockTable.of().name("AE").col("AETERM", "x");
        if (uri != null)
        {
            mt = mt.uri(uri);
        }
        Operation op = makeOp("$f", "extract_metadata");
        op.setName("filename");
        return OperationExecutor.execute(List.of(op), mt.build(), NO_RESOLVER).get("$f");
    }


    @Test
    void filenameFromUri_isTheLastPathSegment_andNullWhenThereIsNone()
    {
        // Ordinary absolute file URI: everything before the final '/' is dropped, and exactly
        // one character after it is kept — a +1/-1 slip here yields "a/ae.xpt".
        assertEquals("ae.xpt", filenameOf("file:///data/ae.xpt"), "last path segment, decoded");
        // The separator at index 0 — the case a ">= 0" narrowed to "> 0" silently loses, which
        // would publish "/ae.xpt" (leading slash) as the file name.
        assertEquals("ae.xpt", filenameOf("file:/ae.xpt"), "a path whose only '/' is at index 0");
        // No separator at all: the whole path is the name.
        assertEquals("ae.xpt", filenameOf("ae.xpt"), "a bare relative name");
        // Opaque URI — getPath() is null, so the scheme-specific part is the fallback.
        assertEquals("ae.xpt", filenameOf("s3:bucket/ae.xpt"), "opaque URI falls back to the SSP");
        // Empty path with a non-empty SSP — the second half of the same fallback.
        assertEquals("host", filenameOf("http://host"), "an authority-only URI falls back too");
        // Negative — no URI at all: the accessor has no answer and must publish none.
        assertNull(filenameOf(null), "no source URI ⇒ no file name (never an empty string)");
        // Negative — a URI with neither a path nor a scheme-specific part.
        assertNull(filenameOf("#frag"), "nothing usable in the URI ⇒ no file name");
        // Negative — a path ending in the separator has an empty last segment.
        assertNull(filenameOf("file:///data/"), "a trailing separator leaves no file name");
    }

    // ==================================================================
    // get_codelist_attributes → ctPackageId: which CT package the row's
    // (target, version, standard) triple names. Ask the wrong package and the rule
    // validates terms against a codelist the sponsor never used.
    // ==================================================================


    /** Runs get_codelist_attributes and reports the CT package id the provider was asked for. */
    private static Object packageIdsAskedFor(String target, String version, @Nullable String std)
    {
        IDataTable table = MockTable.of().name("TS").col("TSVCDREF", target)
                .col("TSVCDVER", version).build();
        RecordingProvider p = new RecordingProvider();
        p.standard = std;
        Operation op = makeOp("$attrs", "get_codelist_attributes");
        op.setName("TSVCDREF");
        op.setVersion("TSVCDVER");
        op.setCtAttribute("Term CCODE");
        return OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p).get("$attrs");
    }


    @Test
    void ctPackageId_derivesThePrefixFromTheStandard_andPassesNonCdiscTargetsThrough()
    {
        // The provider echoes the package id it was asked for, so the operation's value IS the
        // derived package id.
        assertEquals(List.of("sdtmct-2024-09-27"),
                packageIdsAskedFor("CDISC", "2024-09-27", "sdtmig"), "SDTM standard ⇒ sdtmct");
        assertEquals(List.of("adamct-2024-09-27"),
                packageIdsAskedFor("CDISC", "2024-09-27", "adamig"), "ADaM standard ⇒ adamct");
        assertEquals(List.of("sendct-2024-09-27"),
                packageIdsAskedFor("CDISC", "2024-09-27", "sendig"), "SEND standard ⇒ sendct");
        assertEquals(List.of("sdtmct-2024-09-27"), packageIdsAskedFor("CDISC", "2024-09-27", null),
                "an unknown/absent standard falls back to sdtmct, it must not crash");
        // "CDISC CT" is the second accepted spelling of the same target.
        assertEquals(List.of("adamct-2024-09-27"),
                packageIdsAskedFor("CDISC CT", "2024-09-27", "adamig"), "the 'CDISC CT' spelling");
        // The target is stripped before the comparison.
        assertEquals(List.of("sdtmct-2024-09-27"),
                packageIdsAskedFor("  CDISC  ", "  2024-09-27  ", "sdtmig"),
                "operands are stripped");
        // Negative — a sponsor/external CT target is used verbatim as the package prefix, NOT
        // silently mapped onto a CDISC package.
        assertEquals(List.of("MEDDRA-2024-09-27"),
                packageIdsAskedFor("MEDDRA", "2024-09-27", "sdtmig"),
                "a non-CDISC target names its own package");
        // Negative — a blank version resolves to no package at all: the provider is never asked,
        // the attribute union stays empty, and an empty library answer is published as the SKIP
        // sentinel. What matters here is that no package was invented: a fabricated id such as
        // "sdtmct-" would come back as a one-element list instead.
        assertEquals("<library not available>",
                String.valueOf(packageIdsAskedFor("CDISC", "   ", "sdtmig")),
                "a blank version ⇒ no CT package ⇒ nothing asked for");
    }

    // ==================================================================
    // supp_qnam_present / supp_qnam_value — the SUPP→parent join. The GroupedResult's
    // KEY is what decides which parent record the qualifier lands on.
    // ==================================================================


    /** PC: (S1,1), (S1,2), (S2,1). */
    private static IDataTable pc()
    {
        return MockTable.of().name("PC").col("USUBJID", "S1", "S1", "S2")
                .col("PCSEQ", "1", "2", "1").build();
    }


    /**
     * SUPPPC carrying one PCCALCN row for (S1, PCSEQ=1), with the columns emitted in {@code order}
     * so a test can put any one of them at index 0.
     */
    private static IDataTable supp(List<String> order)
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("USUBJID", "S1");
        values.put("IDVAR", "PCSEQ");
        values.put("IDVARVAL", "1");
        values.put("QNAM", "PCCALCN");
        values.put("QVAL", "0.5");
        MockTable mt = MockTable.of().name("SUPPPC");
        for (String c : order)
        {
            mt = mt.col(c, values.get(c));
        }
        return mt.build();
    }


    private static Object suppJoin(String operator, IDataTable suppTable)
    {
        Operation op = makeOp("$q", operator);
        op.setDomain("SUPPPC");
        op.setKeyValue("PCCALCN");
        return OperationExecutor
                .execute(List.of(op), pc(), n -> "SUPPPC".equals(n) ? suppTable : null).get("$q");
    }

    private static final List<String> ALL_SUPP_COLS = List.of("USUBJID", "IDVAR", "IDVARVAL",
            "QNAM", "QVAL");

    /** Rotates {@code first} to column index 0, keeping the rest in their declared order. */
    private static List<String> firstAt0(String first)
    {
        List<String> out = new ArrayList<>();
        out.add(first);
        for (String c : ALL_SUPP_COLS)
        {
            if (!c.equals(first))
            {
                out.add(c);
            }
        }
        return out;
    }


    @Test
    void suppQnamPresent_joinsOnUsubjidPlusIdvarval_whicheverColumnSitsAtIndexZero()
    {
        // The expected join: the qualifier lands on (USUBJID=S1, PCSEQ=1) and nowhere else, and
        // the result declares [USUBJID, PCSEQ] as its group columns so the parent lookup uses
        // the anchor IDVAR. Every rotation below must produce EXACTLY this.
        Map<String, Object> expected = Map.of(GroupedResult.buildKey(List.of("S1", "1")), true);
        for (String atZero : ALL_SUPP_COLS)
        {
            GroupedResult g = assertInstanceOf(GroupedResult.class,
                    suppJoin("supp_qnam_present", supp(firstAt0(atZero))),
                    atZero + " at column index 0");
            assertEquals(List.of("USUBJID", "PCSEQ"), g.groupColumns(),
                    "join keys, with " + atZero + " at index 0");
            assertEquals(expected, g.results(), "join result, with " + atZero + " at index 0");
            assertEquals(false, g.defaultForMissingKey(),
                    "an unmatched parent record reads false, not null");
        }
    }


    @Test
    void suppQnamValue_readsQvalEvenWhenQvalIsTheFirstColumn()
    {
        for (String atZero : ALL_SUPP_COLS)
        {
            GroupedResult g = assertInstanceOf(GroupedResult.class,
                    suppJoin("supp_qnam_value", supp(firstAt0(atZero))),
                    atZero + " at column index 0");
            assertEquals(Map.of(GroupedResult.buildKey(List.of("S1", "1")), "0.5"), g.results(),
                    "QVAL is read from its own column, with " + atZero + " at index 0");
        }
    }


    @Test
    void suppQnamJoin_declinesWhenAnyOfTheThreeJoinColumnsIsAbsent()
    {
        // Negative side of the same guard: drop one required column at a time and the join must
        // collapse to the empty per-parent result (keyed on USUBJID alone), never to a partial
        // join keyed on whatever remains.
        for (String dropped : List.of("QNAM", "IDVAR", "IDVARVAL"))
        {
            List<String> cols = new ArrayList<>(ALL_SUPP_COLS);
            cols.remove(dropped);
            GroupedResult g = assertInstanceOf(GroupedResult.class,
                    suppJoin("supp_qnam_present", supp(cols)), "SUPPPC without " + dropped);
            assertEquals(List.of("USUBJID"), g.groupColumns(),
                    "the empty result is keyed on USUBJID alone, without " + dropped);
            assertEquals(Map.of(), g.results(), "no rows join without " + dropped);
        }
    }


    @Test
    void suppQnamJoin_keepsEveryRowOnTheAnchorIdvar_andDropsOnlyDivergentOnes()
    {
        // Two PCCALCN rows on the SAME IDVAR for different parent records: BOTH must join.
        IDataTable sameIdvar = MockTable.of().name("SUPPPC").col("USUBJID", "S1", "S1")
                .col("IDVAR", "PCSEQ", "PCSEQ").col("IDVARVAL", "1", "2")
                .col("QNAM", "PCCALCN", "PCCALCN").col("QVAL", "0.5", "0.9").build();
        GroupedResult kept = assertInstanceOf(GroupedResult.class,
                suppJoin("supp_qnam_value", sameIdvar));
        assertEquals(
                Map.of(GroupedResult.buildKey(List.of("S1", "1")), "0.5",
                        GroupedResult.buildKey(List.of("S1", "2")), "0.9"),
                kept.results(), "both rows share the anchor IDVAR ⇒ both join");

        // Same shape but the second row references a DIFFERENT IDVAR: the first anchors, the
        // second is dropped — a GroupedResult keys on one parent column only.
        IDataTable divergent = MockTable.of().name("SUPPPC").col("USUBJID", "S1", "S1")
                .col("IDVAR", "PCSEQ", "PCGRPID").col("IDVARVAL", "1", "2")
                .col("QNAM", "PCCALCN", "PCCALCN").col("QVAL", "0.5", "0.9").build();
        GroupedResult anchored = assertInstanceOf(GroupedResult.class,
                suppJoin("supp_qnam_value", divergent));
        assertEquals(List.of("USUBJID", "PCSEQ"), anchored.groupColumns(),
                "the FIRST-seen IDVAR anchors the join");
        assertEquals(Map.of(GroupedResult.buildKey(List.of("S1", "1")), "0.5"), anchored.results(),
                "the divergent PCGRPID row is dropped, not silently re-keyed");
    }

    // ==================================================================
    // max with group + filter. The filter is the rule author's row selection; ignoring
    // it publishes the maximum of the WRONG subset to every row of the group.
    // ==================================================================


    private static Object groupedMax(IDataTable table, @Nullable Map<String, Object> filter)
    {
        Operation op = makeOp("$max", "max");
        op.setName("AVAL");
        op.setGroup(List.of("USUBJID"));
        op.setFilter(filter);
        return OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$max");
    }


    /** S1 has AVAL 1 (PARAMCD=X) and 5 (PARAMCD=Y); S2 has AVAL 3 (PARAMCD=X). */
    private static IDataTable numericTable()
    {
        return MockTable.of().name("ADLB").col("USUBJID", "S1", "S1", "S2")
                .col("PARAMCD", "X", "Y", "X").colLong("AVAL", 1L, 5L, 3L).build();
    }


    @Test
    void groupedNumericMax_honoursTheFilter_andDiffersWithoutIt()
    {
        GroupedResult filtered = assertInstanceOf(GroupedResult.class,
                groupedMax(numericTable(), Map.of("PARAMCD", "X")));
        assertEquals(
                Map.of(GroupedResult.buildKey(List.of("S1")), 1.0d,
                        GroupedResult.buildKey(List.of("S2")), 3.0d),
                filtered.results(), "only PARAMCD=X rows are candidates, so S1's max is 1 (not 5)");
        // Negative control — the very same data with no filter yields a DIFFERENT answer, so the
        // assertion above cannot pass by accident.
        GroupedResult unfiltered = assertInstanceOf(GroupedResult.class,
                groupedMax(numericTable(), null));
        assertEquals(
                Map.of(GroupedResult.buildKey(List.of("S1")), 5.0d,
                        GroupedResult.buildKey(List.of("S2")), 3.0d),
                unfiltered.results(), "without a filter S1's max is 5");
        // ... and an EMPTY filter map must behave as no filter, not as "match nothing".
        GroupedResult emptyFilter = assertInstanceOf(GroupedResult.class,
                groupedMax(numericTable(), Map.of()));
        assertEquals(unfiltered.results(), emptyFilter.results(),
                "an empty filter selects every row");
    }


    /** The same shape over non-numeric (ISO date) values, which take the string fallback. */
    private static IDataTable stringTable()
    {
        return MockTable.of().name("ADLB").col("USUBJID", "S1", "S1", "S2")
                .col("PARAMCD", "X", "Y", "X").col("AVAL", "2020-01-01", "2021-01-01", "2019-05-05")
                .build();
    }


    @Test
    void groupedStringMax_honoursTheFilterToo_andDiffersWithoutIt()
    {
        GroupedResult filtered = assertInstanceOf(GroupedResult.class,
                groupedMax(stringTable(), Map.of("PARAMCD", "X")));
        assertEquals(
                Map.of(GroupedResult.buildKey(List.of("S1")), "2020-01-01",
                        GroupedResult.buildKey(List.of("S2")), "2019-05-05"),
                filtered.results(),
                "the string fallback must apply the same filter as the numeric pass");
        GroupedResult unfiltered = assertInstanceOf(GroupedResult.class,
                groupedMax(stringTable(), null));
        assertEquals(
                Map.of(GroupedResult.buildKey(List.of("S1")), "2021-01-01",
                        GroupedResult.buildKey(List.of("S2")), "2019-05-05"),
                unfiltered.results(), "without a filter S1's latest date is 2021-01-01");
    }

    // ==================================================================
    // get_parent_model_column_order — every "cannot answer" path must publish the
    // library-skipped sentinel, so the rule is reported SKIPPED. Publishing null
    // instead leaves the operand absent and the rule quietly evaluates as if the
    // question had been answered.
    // ==================================================================


    private static @Nullable Object parentModelColumnOrder(IDataTable table,
            @Nullable RecordingProvider provider, DatasetResolver resolver)
    {
        Operation op = makeOp("$model", "get_parent_model_column_order");
        return OperationExecutor.execute(List.of(op), table, resolver, provider).get("$model");
    }


    private static IDataTable suppAe()
    {
        return MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").col("QNAM", "AETRTEM").build();
    }


    @Test
    void parentModelColumnOrder_publishesTheLibrarySkipSentinelOnEveryUnanswerablePath()
    {
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "x").build();
        DatasetResolver toAe = n -> "AE".equals(n) ? ae : null;

        // 1 — no library provider at all.
        Object noProvider = parentModelColumnOrder(suppAe(), null, toAe);
        assertEquals("<library not available>", String.valueOf(noProvider),
                "no provider ⇒ SKIP sentinel, never an absent operand");

        // 2 — provider present, but the dataset carries no RDOMAIN so there is no parent to ask
        // about.
        RecordingProvider p = new RecordingProvider();
        p.standardModelVariables = List.of("STUDYID", "DOMAIN", "USUBJID");
        Object noRdomain = parentModelColumnOrder(
                MockTable.of().name("AE").col("AETERM", "x").build(), p, toAe);
        assertEquals("<library not available>", String.valueOf(noRdomain),
                "no RDOMAIN column ⇒ SKIP sentinel");

        // 3 — the library has no model for the parent (null answer).
        RecordingProvider degraded = new RecordingProvider();
        degraded.standardModelVariables = null;
        assertEquals("<library not available>",
                String.valueOf(parentModelColumnOrder(suppAe(), degraded, toAe)),
                "a null model answer ⇒ SKIP sentinel");

        // 4 — the parent domain named in RDOMAIN is not in the study, so no group resolves.
        assertEquals("<library not available>",
                String.valueOf(parentModelColumnOrder(suppAe(), p, NO_RESOLVER)),
                "an unresolvable parent ⇒ SKIP sentinel");

        // Positive control — with a resolvable parent AND a model, the operation publishes the
        // parent's model column order keyed by RDOMAIN. Without this the four assertions above
        // would pass against an implementation that always skipped.
        GroupedResult ok = assertInstanceOf(GroupedResult.class,
                parentModelColumnOrder(suppAe(), p, toAe));
        assertEquals(List.of("RDOMAIN"), ok.groupColumns());
        assertEquals(
                Map.of(GroupedResult.buildKey(List.of("AE")),
                        List.of("STUDYID", "DOMAIN", "USUBJID")),
                ok.results(), "the PARENT domain's model variables, keyed by RDOMAIN");
        assertFalse(ok.results().isEmpty(), "the positive control must actually carry a group");
    }

    // ==================================================================
    // Provider stub
    // ==================================================================

    /**
     * A {@link MetadataProvider} that echoes back the CT package id it was asked for, so a test can
     * assert WHICH package {@code ctPackageId} derived rather than merely that some list came back.
     */
    private static final class RecordingProvider implements MetadataProvider
    {

        @Nullable
        String standard;

        @Nullable
        List<String> standardModelVariables = List.of();

        @Override
        public List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
        {
            return List.of(aCtPackageId);
        }


        @Override
        public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
                DatasetResolver aResolver)
        {
            return standardModelVariables;
        }


        @Override
        public @Nullable String getStandard()
        {
            return standard;
        }


        @Override
        public @Nullable String getVersion()
        {
            return null;
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return true;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return Map.of();
        }
    }

}
