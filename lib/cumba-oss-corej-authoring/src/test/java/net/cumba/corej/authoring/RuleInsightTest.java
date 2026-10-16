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
    void aStringLiteralArgumentOfEachColumnStatusPredicateNamesAColumn()
    {
        // PLAN-present-empty-column-fold row 28a: the three predicates join NAME_LITERAL_CALLS.
        String yaml = CHECK.replace("var_exists(\"AESER\")",
                "var_is_empty(\"AESER\") and var_is_single_value(\"AESEV\")"
                        + " and var_is_all_missing(\"AEREL\")");
        AssembledRule r = AuthoredRuleAssembler.assemble(yaml, null,
                List.of(new StandardRef("sdtmig-3-4", StandardRef.Role.PRIMARY)));
        assertNotNull(r.rule(), r.diagnostics().toString());
        List<VariableRef> refs = RuleInsight.referencedVariables(r.rule());
        assertTrue(refs.contains(new VariableRef("AESER", "Check", true)), refs.toString());
        assertTrue(refs.contains(new VariableRef("AESEV", "Check", true)), refs.toString());
        assertTrue(refs.contains(new VariableRef("AEREL", "Check", true)), refs.toString());
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


    /**
     * Review 8a-A F7: the ADaM structures are judged the way production judges them — the structure
     * set with its supertypes ({@code AdamDataStructureDetector.structureSet}: a medical-device BDS
     * IS a BDS), and the token / subclass a stored structure maps onto read through the engine's
     * own choke point, not a parallel short-label table.
     */
    @Test
    void adamStructuresAreJudgedAsProductionJudgesThem()
    {
        AssembledRule assembled = AuthoredRuleAssembler.assemble("""
                Core:
                  Id: "CDISC-AD9001"
                  Status: "Published"
                  Version: "1"
                Description: "a BDS rule"
                Executability: "Fully Executable"
                Scope:
                  Data_Structures:
                    Include:
                    - "BASIC DATA STRUCTURE"
                Check:
                  expression: 'not empty(PARAMCD)'
                Outcome:
                  Message: "PARAMCD is empty"
                """, null, List.of(new StandardRef("adamig-1-3", StandardRef.Role.PRIMARY)));
        Rule rule = assembled.rule();
        assertNotNull(rule, assembled.diagnostics().toString());
        ScopeUniverse u = RuleInsight.scopeUniverse(rule, new AdamStore(),
                List.of("standards/adam/adamig-1-3", "standards/adam/adam-tte-1-0",
                        "standards/adam/adam-adae-1-0"));
        Map<String, ScopeEntry> byName = new java.util.HashMap<>();
        u.entries().forEach(e -> byName.put(e.dataset(), e));
        assertTrue(byName.get("ADMDBDS").inScope(),
                "a medical-device BDS is a BDS: " + byName.get("ADMDBDS"));
        assertTrue(byName.get("BDS for TTE").inScope(), byName.get("BDS for TTE").toString());
        assertEquals("BASIC DATA STRUCTURE", byName.get("BDS for TTE").datasetClass(),
                "the engine's class override for a structure published without a class");
        assertFalse(byName.get("ADAE").inScope(), byName.get("ADAE").toString());
        assertEquals("OCCURRENCE DATA STRUCTURE", byName.get("ADAE").datasetClass());
    }

    /** Three ADaM products, one data structure each. */
    private static final class AdamStore implements MetadataStore
    {

        private static StoredDataStructure structure(String aName, String aClass)
        {
            return new StoredDataStructure(aName, aName, null, aClass, null, List.of(), null);
        }


        @Override
        public Optional<StoredProduct> product(String aKey)
        {
            StoredDataStructure s = switch (aKey)
            {
            case "standards/adam/adamig-1-3" -> structure("ADMDBDS",
                    "MEDICAL DEVICE BASIC DATA STRUCTURE");
            case "standards/adam/adam-tte-1-0" -> structure("BDS for TTE", null);
            case "standards/adam/adam-adae-1-0" -> structure("ADAE", "ADAE");
            default -> null;
            };
            return s == null ? Optional.empty()
                    : Optional.of(StoredProduct.builder().key(aKey).name("ADaM").version("1")
                            .classes(List.of()).datasets(List.of()).dataStructures(List.of(s))
                            .build());
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
            return List.of();
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

    @Test
    void productKeysFollowTheStoreConvention()
    {
        assertEquals(List.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3"),
                RuleInsight.productKeysOf(
                        List.of(new AuthoredRuleAssembler.StandardVersion("SDTMIG", "3.4"),
                                new AuthoredRuleAssembler.StandardVersion("ADaMIG", "1.3"),
                                new AuthoredRuleAssembler.StandardVersion("SDTMIG", "3.4"))));
        // PLAN-bugfix-engine-define SV (pre-go H2): a variant standard maps through the store's
        // D-11 family grammar — standards/<family>/<variant>-<version>, never
        // standards/<name>/<version>, which names no product and left every AR / DART / MD
        // rule's universe empty.
        assertEquals(
                List.of("standards/sendig/ar-1-0", "standards/sendig/dart-1-2",
                        "standards/sdtmig/md-1-1"),
                RuleInsight.productKeysOf(
                        List.of(new AuthoredRuleAssembler.StandardVersion("SENDIG-AR", "1.0"),
                                new AuthoredRuleAssembler.StandardVersion("SENDIG-DART", "1.2"),
                                new AuthoredRuleAssembler.StandardVersion("SDTMIG-MD", "1.1"))));
    }


    /**
     * {@code PLAN-bugfix-engine-define} SV: an AR rule's universe goes through
     * {@link RuleInsight#productKeysOf} to the real store key and reads the base-IG view — the
     * SENDIG 3.1 datasets AR does not print ({@code LB}) are judged, beside AR's own ({@code AG}).
     */
    @Test
    void anArRulesUniverseReadsTheBaseSendigsDatasets()
    {
        AssembledRule assembled = AuthoredRuleAssembler.assemble("""
                Core:
                  Id: "CDISC-SEND-9001"
                  Status: "Published"
                  Version: "1"
                Description: "an AR rule over LB"
                Executability: "Fully Executable"
                Scope:
                  Domains:
                    Include:
                    - "LB"
                Check:
                  expression: 'not empty(LBTEST)'
                Outcome:
                  Message: "LBTEST is empty"
                """, null, List.of(new StandardRef("sendig-ar-1-0", StandardRef.Role.PRIMARY)));
        Rule rule = assembled.rule();
        assertNotNull(rule, assembled.diagnostics().toString());
        ScopeUniverse u = RuleInsight.scopeUniverse(rule, new SendStore(),
                RuleInsight.productKeysOf(
                        List.of(new AuthoredRuleAssembler.StandardVersion("SENDIG-AR", "1.0"))));
        assertEquals(List.of(), u.missingProducts(), "the AR product is held under its store key");
        Map<String, ScopeEntry> byName = new java.util.HashMap<>();
        u.entries().forEach(e -> byName.put(e.dataset(), e));
        assertNotNull(byName.get("LB"), "LB comes from the base SENDIG 3.1: " + byName.keySet());
        assertTrue(byName.get("LB").inScope(), byName.get("LB").toString());
        assertNotNull(byName.get("AG"), "AR's own dataset is judged too");
        assertFalse(byName.get("AG").inScope(), byName.get("AG").toString());
        assertTrue(u.variableNames().contains("LBTEST"), u.variableNames().toString());
    }

    /** SENDIG-AR 1.0 (prints AG only) beside its base SENDIG 3.1 (prints LB). */
    private static final class SendStore implements MetadataStore
    {

        private static StoredVariable var(String aName)
        {
            return StoredVariable.builder().name(aName).build();
        }


        private static StoredDataset ds(String aName, String... aVars)
        {
            return new StoredDataset(aName, aName, null, null,
                    java.util.Arrays.stream(aVars).map(SendStore::var).toList(), null, null);
        }


        private static StoredProduct product(String aKey, String aName, String aClass,
                StoredDataset aDataset)
        {
            StoredClass klass = new StoredClass(aClass, aClass, "1", List.of(), List.of(aDataset),
                    null, List.of(), List.of(), List.of());
            return StoredProduct.builder().key(aKey).name(aName).version("1.0")
                    .classes(List.of(klass)).datasets(List.of(aDataset))
                    .dataStructures(List.<StoredDataStructure> of()).build();
        }


        @Override
        public Optional<StoredProduct> product(String aKey)
        {
            return switch (aKey)
            {
            case "standards/sendig/ar-1-0" -> Optional.of(product(aKey, "SENDIG-AR",
                    "INTERVENTIONS", ds("AG", "STUDYID", "USUBJID", "AGTRT")));
            case "standards/sendig/3-1" -> Optional.of(
                    product(aKey, "SENDIG", "FINDINGS", ds("LB", "STUDYID", "USUBJID", "LBTEST")));
            default -> Optional.empty();
            };
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
            return List.of("standards/sendig/3-1", "standards/sendig/ar-1-0");
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
