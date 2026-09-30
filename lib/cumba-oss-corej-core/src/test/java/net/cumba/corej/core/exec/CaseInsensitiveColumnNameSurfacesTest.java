package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.metadata.ScopeClassLadder;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The column-name surfaces of the {@code exec} package that still compared a name case-SENSITIVELY
 * after the 2026-09-28 ruling (owner: <i>"Case-insensitive everywhere"</i>,
 * {@code PLAN-case-insensitive-templates}, register {@code CIT §1}), found by review round 1 and by
 * the fix lane's sweep. Every test runs on a <b>lowercase-named</b> column — the SAS-export shape —
 * and each one fails on the case-sensitive comparison it guards.
 *
 * <p>
 * Mockito-free: {@link RealTables} builds real column-cached tables, {@link StubMetadataProvider}
 * and {@link ClassOnlyProvider} are small real providers.
 * </p>
 */
class CaseInsensitiveColumnNameSurfacesTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** {@code column_series_metadata("COVAL", name_pattern="^COVAL\\d+$")} over {@code co}. */
    private static @Nullable Object columnSeries(IDataTable co)
    {
        return ScalarMetadataFunctions
                .columnSeriesMetadata(
                        net.cumba.corej.core.expr.eval.EvalRun.fullRange(EvaluationContext.builder()
                                .table(co).datasetResolver(NO_RESOLVER).build()),
                        java.util.Arrays.asList(
                                net.cumba.corej.core.expr.eval.ConstVector.of("COVAL"),
                                net.cumba.corej.core.expr.eval.ConstVector.of("^COVAL\\d+$"), null))
                .value(0).resolved();
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }

    // -- column_series_metadata: the un-numbered base column -------------------------------------


    /**
     * {@code CDISC-SEND-0119} shape: the base {@code COVAL} plus its numbered continuations. A
     * lowercase {@code coval} is the base (suffix 0), so {@code coval} + {@code coval2} is a gap —
     * the check fires. Compared case-sensitively the base was not recognised, only {@code coval2}
     * was a member, and "fewer than two members" answered {@code false}.
     */
    @Test
    void columnSeriesMetadataRecognisesALowercaseBaseColumn()
    {
        IDataTable co = RealTables.of("CO").str("coval", "a").str("coval2", "c").build();

        // A registry function since wave 4b (PLAN-scalar-metadata-functions).
        Object result = columnSeries(co);

        assertEquals(true, result, "coval is the base (0), coval2 is 2 — the gap at 1 fires");
    }


    /** Negative control: the complete lowercase series stays quiet. */
    @Test
    void columnSeriesMetadataCompleteLowercaseSeriesDoesNotFire()
    {
        IDataTable co = RealTables.of("CO").str("coval", "a").str("coval1", "b").str("coval2", "c")
                .build();
        assertEquals(false, columnSeries(co), "0, 1, 2 contiguous ⇒ complete");
    }

    // -- StandardVariableSelector: natural_key_variables / get_dataset_filtered_variables --------


    /**
     * The Library's {@code VISITNUM} / {@code LBSPEC} are the dataset's {@code visitnum} /
     * {@code lbspec}: they are selected, and under the dataset's OWN spelling — the name every
     * later read of them resolves ({@code RecordKeyResolver.present} does the same). Compared
     * case-sensitively the selection was empty.
     */
    @Test
    void naturalKeyVariablesSelectsLowercaseColumnsUnderTheirOwnSpelling()
    {
        IDataTable lb = RealTables.of("LB").str("usubjid", "U1").str("visitnum", "1")
                .str("lbspec", "BLOOD").str("lbtestcd", "ALT").build();
        StubMetadataProvider library = new StubMetadataProvider()
                .variable("LB", Map.of("name", "USUBJID", "role", "Identifier"))
                .variable("LB", Map.of("name", "VISITNUM", "role", "Timing"))
                .variable("LB", Map.of("name", "--SPEC", "role", "Record Qualifier"))
                .variable("LB", Map.of("name", "--TESTCD", "role", "Topic"))
                .variable("LB", Map.of("name", "--ORRES", "role", "Result Qualifier"));

        Object nk = LibraryLists.naturalKeyVariables(
                net.cumba.corej.core.expr.eval.EvalRun.fullRange(EvaluationContext.builder()
                        .table(lb).libraryProvider(library).datasetResolver(NO_RESOLVER).build()),
                List.of()).value(0).resolved();

        assertEquals(List.of("visitnum", "lbspec"), nk,
                "Timing + Record Qualifier present in any case, in the dataset's spelling; "
                        + "Identifier / Topic excluded, the absent --ORRES dropped");
    }

    // -- AP datasets: APID / DOMAIN looked up as column names ------------------------------------


    /** A SAS-exported {@code aplb} carrying {@code apid} has the AP suffix {@code LB}. */
    @Test
    void apSuffixRecognisesALowercaseApidColumn()
    {
        IDataTable aplb = RealTables.of("APLB").str("apid", "P1").str("domain", "APLB").build();

        assertEquals("LB", DatasetIdentity.apSuffixOf(aplb, "APLB"));
    }


    /** Negative control: without an APID column in any case there is no AP suffix. */
    @Test
    void apSuffixIsEmptyWithoutAnApidColumn()
    {
        IDataTable aplb = RealTables.of("APLB").str("usubjid", "U1").str("domain", "APLB").build();

        assertEquals("", DatasetIdentity.apSuffixOf(aplb, "APLB"));
    }


    /**
     * The AP-inherit tier of the class ladder: a lowercase {@code apid} / {@code domain} AP dataset
     * inherits its parent's class ({@code APLB} → {@code LB}'s). Compared case-sensitively the
     * inherit never ran and the class stayed undetermined.
     */
    @Test
    void scopeClassLadderInheritsTheParentClassForALowercaseApDataset()
    {
        IDataTable aplb = RealTables.of("APLB").str("apid", "P1").str("domain", "APLB").build();
        IDataTable lb = RealTables.of("LB").str("usubjid", "U1").str("domain", "LB").build();

        String className = ScopeClassLadder.classOf(new ClassOnlyProvider("LB", "FINDINGS"), "APLB",
                "APLB", aplb, inventory(Map.of("LB", lb)));

        assertEquals("FINDINGS", className);
    }

    // -- A provider that answers a class for one domain -------------------------------------------

    /** A real provider that classifies exactly one domain; every other answer is empty. */
    private static final class ClassOnlyProvider implements MetadataProvider
    {

        private final String domain;

        private final String className;

        ClassOnlyProvider(String aDomain, String aClassName)
        {
            domain = aDomain;
            className = aClassName;
        }


        @Override
        public @Nullable String getDatasetClass(String aDomain)
        {
            return domain.equals(aDomain) ? className : null;
        }


        @Override
        public List<String> getRequiredVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String aDomain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String aDomain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String aCodelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String aDomain)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String aCodelistName)
        {
            return Optional.empty();
        }


        @Override
        public String getStandard()
        {
            return "sdtmig";
        }
    }
}
