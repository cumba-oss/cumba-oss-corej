package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.authoring.RuleInsight.DerivedFields;
import net.cumba.corej.authoring.RuleInsight.ScopeEntry;
import net.cumba.corej.authoring.RuleInsight.ScopeUniverse;
import net.cumba.corej.authoring.RuleInsight.VariableRef;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.corej.core.metadata.store.StoreManifest;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.StandardRef;
import org.junit.jupiter.api.Test;

/** {@link RuleInsight} over a synthetic rule and a small in-memory metadata store. */
class RuleInsightTest
{

    static final String CHECK = """
            Core:
              Id: "CDISC-XX0002"
              Status: "Published"
              Version: "1"
            Description: "AE dates agree with DM"
            Executability: "Fully Executable"
            Scope:
              Classes:
                Include:
                - "EVENTS"
            Requirements:
              Variables:
                All:
                - "--STDTC"
                - "USUBJID"
                Any:
                - - "--ENDTC"
                  - "--DUR"
                All_Or_None:
                - - "USUBJID"
                  - "DM.USUBJID"
            Match_Datasets:
            - Name: "DM"
              Keys:
              - "USUBJID"
            Bindings:
            - name: "$ref"
              expression: "DM.RFSTDTC"
            Check:
              expression: '--STDTC < $ref and var_exists("AESER") and not empty(AETERM)'
            Outcome:
              Message: "an event before the reference start"
              Output_Variables:
              - "--STDTC"
              - "!AETERM"
            Standards:
            - Organization: "CDISC"
              Standards:
              - Name: "SDTMIG"
                Version: "3.4"
                References:
                - Origin: "SDTM and SDTMIG Conformance Rules"
                  Rule_Identifier:
                    Id: "XX0002"
                    Version: "1"
                  Version: "2.0"
            """;

    private static Rule load()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble(CHECK, null,
                List.of(new StandardRef("sdtmig-3-4", StandardRef.Role.PRIMARY)));
        assertNotNull(r.rule(), r.diagnostics().toString());
        return r.rule();
    }


    @Test
    void derivedFieldsComeFromTheEngine()
    {
        Rule rule = load();
        DerivedFields d = RuleInsight.derived(rule);
        assertTrue(d.loadError() == null, "loadError: " + d.loadError());
        assertEquals(Sensitivity.RECORD, d.sensitivity().value(), d.sensitivity().rationale());
        assertEquals(List.of("--STDTC"), d.outputVariables().authored(),
                "outcome entries after load: " + rule.getOutcome().getOutputVariables()
                        + " effective: " + d.outputVariables().effective());
        assertEquals(List.of("AETERM"), d.outputVariables().excluded());
        assertFalse(d.outputVariables().effective().contains("AETERM"));
        assertTrue(d.outputVariables().derived());
        assertNotNull(d.authorities());
        assertEquals("CDISC", d.authorities().getFirst().getOrganization());
        // `--STDTC` is the domain-prefix form the specialiser resolves, not a Wildcards: template
        // the expander enumerates — so the engine reports no wildcard name here.
        assertEquals(Set.of(), d.wildcardNames());
        assertTrue(d.loadError() == null, String.valueOf(d.loadError()));
    }


    @Test
    void referencedVariablesCoverEverySurface()
    {
        List<VariableRef> refs = RuleInsight.referencedVariables(load());
        assertTrue(refs.contains(new VariableRef("--STDTC", "Check", false)), refs.toString());
        assertTrue(refs.contains(new VariableRef("AESER", "Check", true)),
                "the quoted var_exists target: " + refs);
        assertTrue(refs.contains(new VariableRef("AETERM", "Check", false)), refs.toString());
        assertTrue(refs.contains(new VariableRef("DM.RFSTDTC", "Bindings[$ref]", false)),
                refs.toString());
        assertTrue(refs.contains(new VariableRef("USUBJID", "Requirements.Variables.All", false)),
                refs.toString());
        assertTrue(refs.contains(new VariableRef("--DUR", "Requirements.Variables.Any[0]", false)),
                refs.toString());
        assertTrue(refs.contains(new VariableRef("USUBJID", "Match_Datasets[DM].Keys", false)),
                refs.toString());
        assertTrue(refs.contains(
                new VariableRef("DM.USUBJID", "Requirements.Variables.All_Or_None[0]", false)),
                refs.toString());
        assertTrue(refs.contains(new VariableRef("--STDTC", "Outcome.Output_Variables", false)),
                refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.name().startsWith("$")),
                "a binding reference is not a variable: " + refs);
    }


    @Test
    void theScopeUniverseJudgesEveryDatasetOfTheStandard()
    {
        Rule rule = load();
        ScopeUniverse u = RuleInsight.scopeUniverse(rule, new FakeStore(),
                List.of("standards/sdtmig/3-4", "standards/sdtmig/9-9"));
        assertEquals(List.of("standards/sdtmig/9-9"), u.missingProducts());
        Map<String, ScopeEntry> byName = new java.util.HashMap<>();
        u.entries().forEach(e -> byName.put(e.dataset(), e));
        assertTrue(byName.get("AE").inScope(), byName.get("AE").toString());
        assertTrue(byName.get("CE").inScope());
        assertFalse(byName.get("DM").inScope());
        assertTrue(byName.get("DM").mismatch().contains("Scope.Classes"),
                byName.get("DM").mismatch());
        assertEquals("EVENTS", byName.get("AE").datasetClass());
        assertEquals(Set.of("AE", "CE"), u.inScopeDatasets());
        // in-scope datasets' variables + their class's model variables (the -- forms kept)
        assertTrue(u.variableNames().containsAll(Set.of("AETERM", "AESTDTC", "CETERM", "--STDTC")),
                u.variableNames().toString());
        assertFalse(u.variableNames().contains("RFSTDTC"),
                "an out-of-scope dataset contributes nothing");
    }


    @Test
    void productKeysFollowTheStoreConvention()
    {
        assertEquals(List.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3"),
                RuleInsight.productKeysOf(
                        List.of(new AuthoredRuleAssembler.StandardVersion("SDTMIG", "3.4"),
                                new AuthoredRuleAssembler.StandardVersion("ADaMIG", "1.3"),
                                new AuthoredRuleAssembler.StandardVersion("SDTMIG", "3.4"))));
    }

    /** Two classes, three datasets, one standard: enough to tell the axes apart. */
    private static final class FakeStore implements MetadataStore
    {

        private static StoredVariable var(String aName)
        {
            return StoredVariable.builder().name(aName).build();
        }


        private static StoredDataset ds(String aName, String... aVars)
        {
            return new StoredDataset(aName, aName, null, null,
                    java.util.Arrays.stream(aVars).map(FakeStore::var).toList(), null, null);
        }


        @Override
        public Optional<StoredProduct> product(String aKey)
        {
            if (!"standards/sdtmig/3-4".equals(aKey))
            {
                return Optional.empty();
            }
            StoredDataset ae = ds("AE", "STUDYID", "USUBJID", "AETERM", "AESTDTC");
            StoredDataset ce = ds("CE", "STUDYID", "USUBJID", "CETERM", "CESTDTC");
            StoredDataset dm = ds("DM", "STUDYID", "USUBJID", "RFSTDTC");
            StoredClass events = new StoredClass("EVENTS", "Events", null,
                    List.of(var("--TERM"), var("--STDTC")), List.of(ae, ce), null, List.of(),
                    List.of(), List.of());
            StoredClass special = new StoredClass("SPECIAL PURPOSE", "Special Purpose", null,
                    List.of(), List.of(dm), null, List.of(), List.of(), List.of());
            return Optional.of(StoredProduct.builder().key(aKey).name("SDTMIG").version("3.4")
                    .classes(List.of(events, special)).datasets(List.of(ae, ce, dm))
                    .dataStructures(List.<StoredDataStructure> of()).build());
        }


        @Override
        public Optional<StoredCtPackage> ctPackage(String aId)
        {
            return Optional.empty();
        }


        @Override
        public List<String> publishedCtPackages()
        {
            return List.of();
        }


        @Override
        public Presence presence(String aCtPackageId)
        {
            return Presence.ABSENT;
        }


        @Override
        public List<String> productCatalogue()
        {
            return List.of("standards/sdtmig/3-4");
        }


        @Override
        public StoreManifest manifest()
        {
            throw new UnsupportedOperationException("no manifest in the fake");
        }


        @Override
        public void close()
        {
            // nothing to release
        }
    }
}
