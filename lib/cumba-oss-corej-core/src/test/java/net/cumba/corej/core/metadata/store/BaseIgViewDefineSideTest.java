package net.cumba.corej.core.metadata.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-bugfix-engine-define} SV — the §6 sensitivity arm of the base-IG view on the
 * <b>define side</b>: a Define-XML 2.1 binding {@code LB} to SENDIG-AR 1.0 runs the Library label
 * check ({@code library_variable_label_matches}, PMDA DD0137) through {@link StoreLibraryProvider}
 * over a store holding {@code sendig/ar-1-0} and its base {@code sendig/3-1}. Before the fix the
 * provider read the AR product raw, so every Library answer for {@code LB} was empty and the check
 * could not fire; after it, {@code LB} is answered from the base ({@code PLAN-send-base-ig-view}
 * {@code LVC-F1} (b)) and a wrong label is reported.
 */
class BaseIgViewDefineSideTest
{

    @TempDir
    Path tempDir;

    private static final String RULE = """
            Rule_Id: "PMDA-DD0137"
            Sheet_Rule_Identifier: "DD0137"
            Rule_Set: "PMDA"
            Element: "ItemGroupDef/ItemRef"
            Applicable_Versions: ["2.1"]
            Severity: "Error"
            Source_Type: "Specification"
            Requires: "library"
            Plain_Text_Rule: "The English variable description must match the standard's label."
            Message: "Invalid variable label value [${value}]"
            Check:
              kind: "library_variable_label_matches"
            """;

    private static String define(String aLbtestLabel)
    {
        return """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <def:Standards>
                        <def:Standard OID="STD.IG" Name="SENDIG-AR" Type="IG" Version="1.0" Status="Final"/>
                      </def:Standards>
                      <ItemGroupDef OID="IG.LB" Name="LB" Domain="LB" def:StandardOID="STD.IG">
                        <ItemRef ItemOID="IT.LB.LBTEST" Mandatory="No"/>
                      </ItemGroupDef>
                      <ItemDef OID="IT.LB.LBTEST" Name="LBTEST" DataType="text">
                        <Description><TranslatedText xml:lang="en">%s</TranslatedText></Description>
                      </ItemDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """
                .formatted(aLbtestLabel);
    }


    /** SENDIG-AR 1.0 prints {@code AG} only; the base SENDIG 3.1 prints {@code LB}. */
    private MetadataStore store(boolean aWithBase) throws IOException
    {
        StoredDataset ag = new StoredDataset("AG", "Procedure Agents", "1", "One record per agent",
                List.of(StoredVariable.builder().name("AGTRT").label("Name of Agent").build()),
                null, null);
        StoredProduct ar = StoredProduct.builder().key("standards/sendig/ar-1-0").name("SENDIG-AR")
                .version("1-0").classes(List.of(new StoredClass("Interventions", null, "1",
                        List.of(), List.of(ag), null, List.of(), List.of(), List.of())))
                .build();
        StoredDataset lb = new StoredDataset("LB", "Laboratory Test Results", "1",
                "One record per lab test", List.of(StoredVariable.builder().name("LBTEST")
                        .label("Lab Test or Examination Name").build()),
                null, null);
        StoredProduct base = StoredProduct.builder().key("standards/sendig/3-1").name("SENDIG")
                .version("3-1").classes(List.of(new StoredClass("Findings", null, "1", List.of(),
                        List.of(lb), null, List.of(), List.of(), List.of())))
                .build();
        MetadataStoreWriter writer = new MetadataStoreWriter().addProduct(ar);
        if (aWithBase)
        {
            writer.addProduct(base);
        }
        Path file = tempDir.resolve(aWithBase ? "ar-with-base.zip" : "ar-alone.zip");
        writer.publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sendig/3-1", "standards/sendig/ar-1-0"))
                .write(file);
        return MetadataStore.open(file);
    }


    private static RuleResult run(String aXml, LibraryProvider aLibrary)
    {
        ConformanceRule rule = RuleRepository.parse(RULE, "test");
        try
        {
            DocumentContext context = new DocumentContext(
                    ElementNodeBuilder.build(
                            DefineDomIo.parse(new ByteArrayInputStream(aXml.getBytes(UTF_8)))),
                    "2.1", null, null, List.of(), aLibrary);
            return new RuleEvaluator().evaluate(rule, context);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static int findings(RuleResult aResult)
    {
        assertEquals(ExecutionStatus.EXECUTED, aResult.status(), aResult.toString());
        return aResult.findings().size();
    }


    @Test
    void anArDefineBindingLbIsJudgedAgainstTheBaseSendigsLabel() throws IOException
    {
        try (MetadataStore store = store(true))
        {
            StoreLibraryProvider library = StoreLibraryProvider.over(store);
            assertEquals(1, findings(run(define("Laboratory Test Name"), library)),
                    "a wrong LBTEST label is reported: LB is read from the base SENDIG 3.1");
            assertEquals(0, findings(run(define("Lab Test or Examination Name"), library)),
                    "the control: the base's own label passes");
            assertEquals(List.of(), library.missedProducts());
        }
    }


    @Test
    void anArDefineWithoutTheBaseInTheStoreKeepsTodaysEmptyAnswer() throws IOException
    {
        try (MetadataStore store = store(false))
        {
            StoreLibraryProvider library = StoreLibraryProvider.over(store);
            assertEquals(0, findings(run(define("Laboratory Test Name"), library)),
                    "no base: the Library has no LB and the check cannot fire");
            assertEquals(List.of("standards/sendig/3-1"), library.missedProducts(),
                    "the basis line names the missing base (D-14)");
        }
    }
}
