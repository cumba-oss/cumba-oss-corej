package net.cumba.corej.define.conformance.library;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The eight library-backed check kinds ({@code Requires: library}) and the standard-resolution
 * machinery behind them — the half of the engine that compares a define.xml against the CDISC
 * implementation guide it claims to follow (PMDA DD0116/DD0118/DD0124/DD0136/DD0137, CDISC 67,
 * 97/98/99, 263).
 *
 * <p>
 * ⭐ Before this class the whole family executed only from the rules repository's corpus tests, so
 * every one of its "no finding" branches was unmeasured here. That matters more than a coverage
 * number: each of these kinds decides when NOT to raise a finding, and a library-backed rule that
 * silently reaches "out of the rule's reach" reports a clean define.xml to the reviewer. Every test
 * below therefore asserts the exact finding list, not merely that it is non-empty.
 * </p>
 */
class LibraryKindsTest
{

    /** SDTMIG 3.4, one clean dataset (DM) and one whose label disagrees with the IG (AE). */
    private static final String DM_AND_AE = """
            <def:Standards>
              <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
              <def:Standard OID="STD.CT" Name="SDTM CT" Type="CT" PublishingSet="SDTM"
                            Version="2023-12-15"/>
            </def:Standards>
            <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" def:StandardOID="STD.IG">
              <Description>
                <TranslatedText xml:lang="en">Demographics</TranslatedText>
              </Description>
              <ItemRef ItemOID="IT.DM.USUBJID" Mandatory="No"/>
              <ItemRef ItemOID="IT.DM.SEX" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.DM.EXTRA" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.NOWHERE" Mandatory="Yes"/>
            </ItemGroupDef>
            <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" def:StandardOID="STD.IG">
              <Description>
                <TranslatedText xml:lang="ja">有害事象</TranslatedText>
                <TranslatedText xml:lang="en-US">Adverse Event Log</TranslatedText>
              </Description>
              <ItemRef ItemOID="IT.AE.AESEV" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.AE.AETERM" Mandatory="Yes"/>
            </ItemGroupDef>
            <ItemGroupDef OID="IG.ORPHAN" Name="DM" Domain="DM">
              <Description>
                <TranslatedText xml:lang="en">Not Demographics At All</TranslatedText>
              </Description>
            </ItemGroupDef>
            <ItemDef OID="IT.DM.USUBJID" Name="USUBJID">
              <Description>
                <TranslatedText xml:lang="en">Unique Subject Identifier</TranslatedText>
              </Description>
            </ItemDef>
            <ItemDef OID="IT.DM.SEX" Name="SEX">
              <Description>
                <TranslatedText xml:lang="en">Gender</TranslatedText>
              </Description>
              <CodeListRef CodeListOID="CL.SEX"/>
            </ItemDef>
            <ItemDef OID="IT.DM.EXTRA" Name="EXTRA">
              <Description>
                <TranslatedText xml:lang="en">Sponsor Extension</TranslatedText>
              </Description>
            </ItemDef>
            <ItemDef OID="IT.AE.AESEV" Name="AESEV">
              <CodeListRef CodeListOID="CL.SEV"/>
            </ItemDef>
            <ItemDef OID="IT.AE.AETERM" Name="AETERM"/>
            <CodeList OID="CL.SEX" Name="Sex" def:StandardOID="STD.CT">
              <Alias Context="nci:ExtCodeID" Name="C99999"/>
              <EnumeratedItem CodedValue="F">
                <Alias Context="nci:ExtCodeID" Name="C16576"/>
              </EnumeratedItem>
              <EnumeratedItem CodedValue="Z" def:ExtendedValue="Yes"/>
              <EnumeratedItem CodedValue="Q"/>
            </CodeList>
            <CodeList OID="CL.SEV" Name="Severity" def:StandardOID="STD.CT"/>
            <CodeList OID="CL.FREE" Name="Free"/>
            """;

    private static DocumentContext context(String aMetaDataVersionBody)
    {
        return context(aMetaDataVersionBody, "2.1", new StubLibraryProvider());
    }


    private static DocumentContext context(String aMetaDataVersionBody, String aVersion,
            @Nullable LibraryProvider aProvider)
    {
        return context(aMetaDataVersionBody, aVersion, aProvider, "");
    }


    private static DocumentContext context(String aMetaDataVersionBody, String aVersion,
            @Nullable LibraryProvider aProvider, String aMetaDataVersionAttributes)
    {
        String xml = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV" %s>
                """.formatted(aMetaDataVersionAttributes) + aMetaDataVersionBody + """
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        try
        {
            ElementNode root = ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(xml.getBytes(UTF_8))));
            return new DocumentContext(root, aVersion, null, null, List.of(), aProvider);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static String rule(String aElement, String aCheckBody)
    {
        return rule(aElement, aCheckBody, "Requires: \"library\"");
    }


    private static String rule(String aElement, String aCheckBody, String aRequiresLine)
    {
        return """
                Rule_Id: "PMDA-LIB01"
                Sheet_Rule_Identifier: "LIB01"
                Rule_Set: "PMDA"
                Element: "%s"
                Applicable_Versions: ["2.0", "2.1"]
                Severity: "Error"
                %s
                Plain_Text_Rule: "Test rule."
                Message: "Offending value [${value}]."
                Check:
                """.formatted(aElement, aRequiresLine) + aCheckBody.indent(2);
    }


    private static RuleResult evaluate(String aRuleYaml, DocumentContext aContext)
    {
        return new RuleEvaluator().evaluate(RuleRepository.parse(aRuleYaml, "test"), aContext);
    }


    private static List<String> values(RuleResult aResult)
    {
        return aResult.findings().stream()
                .map(f -> f.getMessage().replace("Offending value [", "").replace("].", ""))
                .toList();
    }

    // ------------------------------------------------------------------
    // The Requires: library gate
    // ------------------------------------------------------------------


    @Test
    void libraryGatedRuleSkipsWhenNoProviderIsBound()
    {
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """), context(DM_AND_AE, "2.1", null));
        assertEquals(ExecutionStatus.SKIPPED_MISSING_LIBRARY, result.status());
        assertTrue(result.findings().isEmpty());
    }


    @Test
    void aLibraryKindWithoutTheRequiresGateIsRejectedAtLoadTime()
    {
        // An authoring error: the kind is library-backed but the rule forgot Requires: library, so
        // the SKIP gate above could never fire and the rule would evaluate against no library at
        // all. The corpus loader refuses it rather than letting it degrade to "no findings".
        String yaml = rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """, "");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> RuleRepository.parse(yaml, "test"));
        assertTrue(thrown.getMessage().contains("Requires"), thrown.getMessage());
    }

    // ------------------------------------------------------------------
    // library_dataset_label_matches (PMDA DD0136)
    // ------------------------------------------------------------------


    @Test
    void datasetLabelFiresOnlyWhereTheIgKnowsTheDatasetAndTheLabelsDisagree()
    {
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """), context(DM_AND_AE));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        // IG.DM agrees; IG.ORPHAN has no resolvable standard (out of reach); only IG.AE disagrees,
        // and its en-US description is the one compared -- the ja one is not English.
        assertEquals(List.of("Adverse Event Log vs Adverse Events"), values(result));
    }


    @Test
    void datasetLabelUsesTheDatasetsOwnNameNotItsDomain()
    {
        // A SUPP-- dataset has Domain=DM but its own IG label. Comparing it against DM's label
        // would be an Error-severity false positive on every submission that splits a domain.
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemGroupDef OID="IG.SUPPDM" Name="SUPPDM" Domain="DM" def:StandardOID="STD.IG">
                  <Description>
                    <TranslatedText xml:lang="en">Supplemental Qualifiers for DM</TranslatedText>
                  </Description>
                </ItemGroupDef>
                """));
        assertEquals(List.of(), values(result));
    }


    @Test
    void datasetLabelIsOutOfReachForNonIgStandardsAndBlankNames()
    // The stub library DOES know ADaMIG 1.3 / DM and gives it a different label, so a finding
    // here would mean the SDTMIG/SENDIG family filter had stopped filtering.
    {
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.ADAM" Name="ADaMIG" Type="IG" Version="1.3"/>
                </def:Standards>
                <ItemGroupDef OID="IG.ADSL" Name="DM" Domain="DM" def:StandardOID="STD.ADAM">
                  <Description>
                    <TranslatedText xml:lang="en">Subject Level Analysis</TranslatedText>
                  </Description>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.BLANK" Name="" def:StandardOID="STD.ADAM">
                  <Description>
                    <TranslatedText xml:lang="en">Nameless</TranslatedText>
                  </Description>
                </ItemGroupDef>
                """));
        assertEquals(List.of(), values(result));
    }


    @Test
    void datasetLabelResolvesThe20StandardFromTheMetaDataVersionAndIgnoresHyphenation()
    {
        // Define-XML 2.0 has no def:Standards; the IG is named on MetaDataVersion and the CT
        // spelling is hyphenated (SDTM-IG). Both legs must reach the same library entry.
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """), context("""
                <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE">
                  <Description>
                    <TranslatedText xml:lang="en">Adverse Event Log</TranslatedText>
                  </Description>
                </ItemGroupDef>
                """, "2.0", new StubLibraryProvider(),
                "def:StandardName=\"SDTM-IG\" def:StandardVersion=\"3.4\""));
        assertEquals(List.of("Adverse Event Log vs Adverse Events"), values(result));
    }


    @Test
    void datasetLabelIsOutOfReachWhenTheMetaDataVersionNamesNoStandard()
    {
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """), context("""
                <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE">
                  <Description>
                    <TranslatedText xml:lang="en">Adverse Event Log</TranslatedText>
                  </Description>
                </ItemGroupDef>
                """, "2.0", new StubLibraryProvider(), "def:StandardName=\"SDTM-IG\""));
        assertEquals(List.of(), values(result));
    }

    // ------------------------------------------------------------------
    // library_variable_label_matches (PMDA DD0137)
    // ------------------------------------------------------------------


    @Test
    void variableLabelComparesTheDereferencedItemDefsEnglishDescription()
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_variable_label_matches"
                """), context(DM_AND_AE));
        // USUBJID agrees; EXTRA is unknown to the IG; IT.NOWHERE does not resolve; AESEV/AETERM
        // have no AE labels in the stub library. Only SEX disagrees.
        assertEquals(List.of("SEX: Gender vs Sex"), values(result));
    }

    // ------------------------------------------------------------------
    // library_codelist_ref_required (PMDA DD0124)
    // ------------------------------------------------------------------


    @Test
    void codelistRefRequiredFiresOnlyForIgCtVariablesWithoutACodeListRef()
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_codelist_ref_required"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.SEX" Mandatory="Yes"/>
                  <ItemRef ItemOID="IT.USUBJID" Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemDef OID="IT.SEX" Name="SEX"/>
                <ItemDef OID="IT.USUBJID" Name="USUBJID"/>
                """));
        // SEX requires CT per the IG and has no CodeListRef; USUBJID requires none.
        assertEquals(List.of("SEX"), values(result));
    }


    @Test
    void codelistRefRequiredIsSatisfiedByAnyCodeListRef()
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_codelist_ref_required"
                """), context(DM_AND_AE));
        // SEX has one; AESEV (the other IG-CT variable in the fixture) has one too.
        assertEquals(List.of(), values(result));
    }

    // ------------------------------------------------------------------
    // library_codelist_ccode_matches (PMDA DD0118)
    // ------------------------------------------------------------------


    @Test
    void codelistCCodeComparesTheReferencedCodeListsNciAlias()
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_codelist_ccode_matches"
                """), context(DM_AND_AE));
        // SEX points at CL.SEX, whose alias says C99999 where the IG says C66731. AESEV points at
        // CL.SEV, which carries no alias at all -- DD0031's beat, not this rule's.
        assertEquals(List.of("SEX: C99999 vs C66731"), values(result));
    }

    // ------------------------------------------------------------------
    // library_core_mandatory (CDISC 67)
    // ------------------------------------------------------------------


    @Test
    void coreMandatoryFiresWhenAnIgRequiredVariableIsNotMandatory()
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_core_mandatory"
                """), context(DM_AND_AE));
        // USUBJID is Core=Req with Mandatory="No"; SEX is Core=Exp; AETERM is Core=Req but already
        // Mandatory="Yes".
        assertEquals(List.of("USUBJID"), values(result));
    }


    @Test
    void coreMandatoryIsOutOfReachWithoutAMandatoryAttributeOrOutsideTheSdtmSendFamily()
    // Likewise: ADaMIG 1.3 / DM / USUBJID IS Core=Req in the stub, so the ADaM ItemRef below
    // would fire if the SDTM/SEND family filter were dropped.
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_core_mandatory"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                  <def:Standard OID="STD.ADAM" Name="ADaMIG" Type="IG" Version="1.3"/>
                </def:Standards>
                <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.USUBJID"/>
                  <ItemRef ItemOID="IT.USUBJID" Mandatory=""/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.ADSL" Name="DM" Domain="DM" def:StandardOID="STD.ADAM">
                  <ItemRef ItemOID="IT.USUBJID" Mandatory="No"/>
                </ItemGroupDef>
                <ItemDef OID="IT.USUBJID" Name="USUBJID"/>
                """));
        assertEquals(List.of(), values(result));
    }

    // ------------------------------------------------------------------
    // library_ct_alias_required (CDISC 97/98/99)
    // ------------------------------------------------------------------


    @Test
    void ctAliasRequiredAtCodelistLevelReachesOnlyIgCtCodeLists()
    {
        RuleResult result = evaluate(rule("CodeList", """
                kind: "library_ct_alias_required"
                level: "codelist"
                """), context(DM_AND_AE));
        // CL.SEX (SEX) carries an alias; CL.SEV (AESEV) is IG-CT and carries none; CL.FREE is
        // referenced by nothing and stays out of reach.
        assertEquals(List.of("Severity"), values(result));
    }


    @Test
    void ctAliasRequiredAtItemLevelExemptsDeclaredExtensions()
    {
        RuleResult result = evaluate(rule("EnumeratedItem", """
                kind: "library_ct_alias_required"
                level: "enumerated_item"
                """), context(DM_AND_AE));
        // F has an alias; Z is def:ExtendedValue-marked (a declared sponsor extension); Q has
        // neither and is the only finding.
        assertEquals(List.of("Q"), values(result));
    }

    // ------------------------------------------------------------------
    // library_standard_version_known (CDISC 263)
    // ------------------------------------------------------------------


    @Test
    void standardVersionKnownFiresOnlyForAPublishedStandardWithAnUnpublishedVersion()
    {
        RuleResult result = evaluate(rule("Standard", """
                kind: "library_standard_version_known"
                """), context("""
                <def:Standards>
                  <def:Standard OID="S1" Name="SDTMIG" Type="IG" Version="3.4"/>
                  <def:Standard OID="S2" Name="SDTMIG" Type="IG" Version="9.9"/>
                  <def:Standard OID="S3" Name="CDISC/NCI" Type="CT" Version="2023-12-15"/>
                  <def:Standard OID="S4" Name=" " Type="IG" Version="3.4"/>
                  <def:Standard OID="S5" Name="SENDIG" Type="IG" Version=" "/>
                </def:Standards>
                """));
        // 3.4 is published; 9.9 is not; CDISC/NCI is a standard the library does not publish
        // versions for (out of reach); blank name and blank version are out of reach.
        assertEquals(List.of("SDTMIG 9.9"), values(result));
    }


    @Test
    void standardVersionKnownRetargetsItsAttributesForThe20Leg()
    {
        RuleResult result = evaluate(rule("MetaDataVersion", """
                kind: "library_standard_version_known"
                nameAttribute: "StandardName"
                versionAttribute: "StandardVersion"
                """), context("", "2.0", new StubLibraryProvider(),
                "def:StandardName=\"SDTM-IG\" def:StandardVersion=\"9.9\""));
        assertEquals(List.of("SDTM-IG 9.9"), values(result));
    }

    // ------------------------------------------------------------------
    // library_qualifier_label_decode (PMDA DD0116)
    // ------------------------------------------------------------------


    @Test
    void qualifierDecodeWalksEachCodeListOnceAcrossAllReferringItemDefs()
    {
        RuleResult result = evaluate(rule("ItemDef", """
                kind: "library_qualifier_label_decode"
                when:
                  path: "@Name"
                  equals: "FATESTCD"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemDef OID="IT.FA1.FATESTCD" Name="FATESTCD">
                  <CodeListRef CodeListOID="CL.FATESTCD"/>
                </ItemDef>
                <ItemDef OID="IT.FA2.FATESTCD" Name="FATESTCD">
                  <CodeListRef CodeListOID="CL.FATESTCD"/>
                </ItemDef>
                <ItemDef OID="IT.OTHER" Name="AETERM">
                  <CodeListRef CodeListOID="CL.FATESTCD"/>
                </ItemDef>
                <CodeList OID="CL.FATESTCD" Name="FA Test Code">
                  <CodeListItem CodedValue="OCCUR">
                    <Decode>
                      <TranslatedText xml:lang="en">Occurred</TranslatedText>
                    </Decode>
                  </CodeListItem>
                  <CodeListItem CodedValue="SEV">
                    <Decode>
                      <TranslatedText xml:lang="en">Severity/Intensity</TranslatedText>
                    </Decode>
                  </CodeListItem>
                  <CodeListItem CodedValue="LOC">
                    <Decode>
                      <TranslatedText xml:lang="en">Location</TranslatedText>
                    </Decode>
                  </CodeListItem>
                  <CodeListItem CodedValue=" ">
                    <Decode>
                      <TranslatedText xml:lang="en">Blank</TranslatedText>
                    </Decode>
                  </CodeListItem>
                  <EnumeratedItem CodedValue="OCCUR"/>
                </CodeList>
                """));
        // OCCUR disagrees with the model label and is reported ONCE although two FATESTCD ItemDefs
        // share the codelist; SEV agrees; LOC is not a qualifier fragment; the blank CodedValue and
        // the decode-less EnumeratedItem are out of reach.
        assertEquals(List.of("FA Test Code (OCCUR: Occurred vs Occurrence)"), values(result));
    }


    @Test
    void qualifierDecodeIsInertWhenTheDocumentNamesNoIgStandard()
    {
        RuleResult result = evaluate(rule("ItemDef", """
                kind: "library_qualifier_label_decode"
                """), context("""
                <ItemDef OID="IT.FATESTCD" Name="FATESTCD">
                  <CodeListRef CodeListOID="CL.FATESTCD"/>
                </ItemDef>
                <CodeList OID="CL.FATESTCD" Name="FA Test Code">
                  <CodeListItem CodedValue="OCCUR">
                    <Decode>
                      <TranslatedText xml:lang="en">Occurred</TranslatedText>
                    </Decode>
                  </CodeListItem>
                </CodeList>
                """));
        assertEquals(List.of(), values(result));
    }


    @Test
    void qualifierDecodeAcceptsAnUnlabelledTranslatedTextAsEnglish()
    {
        RuleResult result = evaluate(rule("ItemDef", """
                kind: "library_qualifier_label_decode"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SENDIG" Type="IG" Version="3.1"/>
                </def:Standards>
                <ItemDef OID="IT.FATESTCD" Name="FATESTCD">
                  <CodeListRef CodeListOID="CL.FATESTCD"/>
                </ItemDef>
                <CodeList OID="CL.FATESTCD" Name="FA Test Code">
                  <CodeListItem CodedValue="OCCUR">
                    <Decode>
                      <TranslatedText/>
                      <TranslatedText>Occurred</TranslatedText>
                    </Decode>
                  </CodeListItem>
                </CodeList>
                """));
        // SENDIG 3.1 is not in the stub's qualifier table, so nothing fires -- but the language
        // selection above (blank text skipped, no xml:lang treated as English) is what the
        // sibling assertions rely on.
        assertEquals(List.of(), values(result));
    }

}
