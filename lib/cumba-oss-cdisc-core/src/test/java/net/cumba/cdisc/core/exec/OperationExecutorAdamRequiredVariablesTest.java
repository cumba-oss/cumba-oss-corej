package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.metadata.AdamDataStructureDetector;
import net.cumba.cdisc.core.metadata.AdamSubclassDetector;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #368 — {@code required_variables()} on an ADaM run, at the {@link OperationExecutor} level.
 *
 * <p>
 * The three behaviours below are the ones a run reproduced on 2026-08-27 against
 * {@code PMDA-AD0047}, and each was wrong before this fix: a conformant ADSL was reported as
 * missing the naming <em>template</em> {@code TRTxxP}; a BDS dataset genuinely missing the Required
 * {@code PARAM} passed green; and an occurrence dataset, whose structure no {@code adamig} product
 * defines, also passed green instead of skipping.
 * </p>
 */
class OperationExecutorAdamRequiredVariablesTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /**
     * The abstract surface of {@link MetadataProvider}, answered with harmless defaults so each
     * test overrides only what it is about. {@code StubMetadataProvider} is {@code final}, hence a
     * local base rather than a subclass of it.
     */
    private abstract static class BaseProvider implements MetadataProvider
    {

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
        public List<Map<String, String>> getDomainVariables(String domain)
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
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return false;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return Map.of();
        }


        @Override
        public String getStandard()
        {
            return "adamig";
        }


        @Override
        public String getVersion()
        {
            return "1-3";
        }
    }


    /**
     * A structure-keyed provider, i.e. the ADaM shape. {@code required} maps a structure token to
     * its published list; a token that is absent from the map resolves to {@code null}, which is
     * the "product defines no such structure" signal.
     */
    private static final class StructureProvider extends BaseProvider
    {

        private final Map<String, List<String>> required;

        /** Records what the domain-keyed accessor was asked, to prove it was NOT used. */
        private final List<String> domainKeyedCalls = new ArrayList<>();

        /**
         * Records the subclass tokens each structure-keyed call arrived with (Phase 3 of
         * {@code PLAN-metadata-product-selection}). ⚠ This double overrides the <b>two-arg
         * primary</b>: overriding only the one-arg convenience would leave
         * {@link OperationExecutor}'s call landing on the interface default, i.e. {@code null} for
         * every token — a whole class of ADaM rules SKIPping while the double looked fine.
         */
        private final List<List<String>> subclassCalls = new ArrayList<>();

        StructureProvider(Map<String, List<String>> aRequired)
        {
            required = aRequired;
        }


        @Override
        public boolean supportsStructureKeyedVariables()
        {
            return true;
        }


        @Override
        public @Nullable List<String> getRequiredVariablesForStructure(String structureToken,
                List<String> subclassTokens)
        {
            subclassCalls.add(List.copyOf(subclassTokens));
            return required.get(structureToken);
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            domainKeyedCalls.add(domain);
            return List.of();
        }
    }

    private static Operation requiredVariables()
    {
        Operation op = new Operation();
        op.setId("$required_variables");
        op.setOperator("required_variables");
        return op;
    }


    private static @Nullable Object run(IDataTable table, MetadataProvider provider)
    {
        return OperationExecutor.execute(List.of(requiredVariables()), table, NO_RESOLVER, provider)
                .get("$required_variables");
    }


    @Test
    void aNamingTemplateIsSatisfiedByTheConcreteColumnItMatches()
    {
        // ⭐ The false positive. adsl.xpt carries TRT01P; the published list says TRTxxP. Before
        // Fix #368 the template flowed through verbatim and contains_all failed on every
        // conformant ADSL in existence.
        IDataTable adsl = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("TRT01P", "Placebo").name("ADSL").build();
        StructureProvider p = new StructureProvider(
                Map.of(AdamDataStructureDetector.ADSL, List.of("STUDYID", "USUBJID", "TRTxxP")));

        assertEquals(List.of("STUDYID", "USUBJID", "TRT01P"), run(adsl, p));
        assertEquals(List.of(), p.domainKeyedCalls, "the domain-keyed accessor must not be used");
    }


    @Test
    void aNamingTemplateWithNoMatchingColumnIsReportedVerbatim()
    {
        // ⚠ The other direction, and the one that is easy to lose: a dataset carrying NO planned
        // treatment variable at all must still be reported. Expanding to "whatever matched" would
        // make the obligation vanish exactly when it is violated.
        IDataTable adsl = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1").name("ADSL")
                .build();
        StructureProvider p = new StructureProvider(
                Map.of(AdamDataStructureDetector.ADSL, List.of("STUDYID", "USUBJID", "TRTxxP")));

        assertEquals(List.of("STUDYID", "USUBJID", "TRTxxP"), run(adsl, p));
    }


    @Test
    void aNonAdslDatasetResolvesByItsDetectedStructureNotByItsDomain()
    {
        // ⭐ The silent miss. PARAMCD makes this a BASIC DATA STRUCTURE; its domain is ADLB, which
        // matches no structure name, so the domain-keyed lookup returned an empty list and the
        // rule passed green with PARAM genuinely absent.
        IDataTable adlb = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("PARAMCD", "ALT").name("ADLB").build();
        StructureProvider p = new StructureProvider(Map.of(AdamDataStructureDetector.BDS,
                List.of("PARAM", "PARAMCD", "STUDYID", "USUBJID")));

        assertEquals(List.of("PARAM", "PARAMCD", "STUDYID", "USUBJID"), run(adlb, p));
        assertEquals(List.of(), p.domainKeyedCalls);
    }


    @Test
    void anUnresolvableStructureSkipsTheRuleInsteadOfPassingIt()
    {
        // An occurrence dataset under a product that publishes no occurrence structure. The honest
        // answer is "cannot evaluate", not "nothing wrong". LIBRARY_NOT_AVAILABLE is what
        // RuleRunner turns into a SKIPPED verdict with a reason.
        IDataTable adae = MockTable.of().col("STUDYID", "P1").col("AEDECOD", "HEADACHE")
                .name("ADAE").build();
        StructureProvider p = new StructureProvider(
                Map.of(AdamDataStructureDetector.BDS, List.of("PARAM")));

        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(adae, p));
    }


    @Test
    void anEmptyPublishedListIsAPassNotASkip()
    {
        // adamig-1-3's TTE resolves and requires nothing. That must stay a green pass — collapsing
        // it into the skip signal would make a legitimate answer indistinguishable from an
        // unanswerable one.
        IDataTable table = MockTable.of().col("PARAMCD", "X").name("ADTTE").build();
        StructureProvider p = new StructureProvider(
                Map.of(AdamDataStructureDetector.BDS, List.of()));

        Object result = run(table, p);
        assertEquals(List.of(), result);
        assertNotSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, result);
    }


    @Test
    void theDetectedSubclassTravelsWithTheStructureToken()
    {
        // ⭐ Phase 3 of PLAN-metadata-product-selection. Without this the provider cannot tell an
        // AE occurrence dataset from a plain one, so it can only answer with the base structure or
        // with the union — which is exactly the state the phase removes. The subclass is derived
        // from the SAME AdamStructureContext the Scope.Subclasses gate uses.
        IDataTable adae = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AEDECOD", "HEADACHE").name("ADAE").build();
        StructureProvider p = new StructureProvider(
                Map.of(AdamDataStructureDetector.OCCDS, List.of("STUDYID", "AEDECOD")));

        assertEquals(List.of("STUDYID", "AEDECOD"), run(adae, p));
        assertEquals(List.of(List.of(AdamSubclassDetector.ADVERSE_EVENT)), p.subclassCalls,
                "the resolved subclass must reach the structure-keyed accessor");
    }


    @Test
    void aDatasetWithNoDetectableSubclassAsksWithAnEmptyList()
    {
        // The majority case, and the one that must mean "base structures only" rather than
        // "no subclass context, fall back to the union".
        IDataTable adlb = MockTable.of().col("STUDYID", "P1").col("PARAMCD", "ALT").name("ADLB")
                .build();
        StructureProvider p = new StructureProvider(
                Map.of(AdamDataStructureDetector.BDS, List.of("PARAM", "PARAMCD")));

        assertEquals(List.of("PARAM", "PARAMCD"), run(adlb, p));
        assertEquals(List.of(List.of()), p.subclassCalls);
    }


    @Test
    void aProviderThatIsNotStructureKeyedKeepsTheDomainKeyedLookup()
    {
        // SDTM, and every stub. Fix #368 must be inert for them.
        IDataTable dm = MockTable.of().col("USUBJID", "S1").name("DM").build();
        BaseProvider sdtm = new BaseProvider()
        {

            @Override
            public List<String> getRequiredVariables(String domain)
            {
                assertEquals("DM", domain);
                return List.of("STUDYID", "USUBJID");
            }
        };

        assertEquals(List.of("STUDYID", "USUBJID"), run(dm, sdtm));
    }
}
