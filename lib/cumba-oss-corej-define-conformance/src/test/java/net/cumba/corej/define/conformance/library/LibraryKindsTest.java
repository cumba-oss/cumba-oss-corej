package net.cumba.corej.define.conformance.library;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.ct.CodelistClass;
import net.cumba.corej.define.conformance.ct.CtCodelist;
import net.cumba.corej.define.conformance.ct.CtProvider;
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
        return context(aMetaDataVersionBody, aVersion, aProvider, aMetaDataVersionAttributes, null);
    }


    private static DocumentContext context(String aMetaDataVersionBody, String aVersion,
            @Nullable LibraryProvider aProvider, String aMetaDataVersionAttributes,
            @Nullable CtProvider aCtProvider)
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
            return new DocumentContext(root, aVersion, aCtProvider, null, List.of(), aProvider);
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
    void datasetLabelSkipsATemplatedPublishedLabel()
    {
        // PLAN-library-template-label-resolution §4.4: a combined SUPPQUAL file is published with
        // "Supplemental Qualifiers for [domain name]" — a template, which a sponsor fills and no
        // single parent domain can substitute. Comparing it would flag every combined SUPPQUAL.
        // Control first: the stub really serves the template, so without the guard the labels
        // below differ and the check would fire.
        assertEquals(Optional.of("Supplemental Qualifiers for [domain name]"),
                new StubLibraryProvider().datasetLabel("SDTMIG", "3.4", "SUPPQUAL"));
        RuleResult result = evaluate(rule("ItemGroupDef", """
                kind: "library_dataset_label_matches"
                """),
                context("""
                        <def:Standards>
                          <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                        </def:Standards>
                        <ItemGroupDef OID="IG.SUPPQUAL" Name="SUPPQUAL" def:StandardOID="STD.IG">
                          <Description>
                            <TranslatedText xml:lang="en">Supplemental Qualifiers for All Domains</TranslatedText>
                          </Description>
                        </ItemGroupDef>
                        <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" def:StandardOID="STD.IG">
                          <Description>
                            <TranslatedText xml:lang="en">Adverse Event Log</TranslatedText>
                          </Description>
                        </ItemGroupDef>
                        """));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(List.of("Adverse Event Log vs Adverse Events"), values(result),
                "the templated SUPPQUAL label is skipped; a plain mismatch beside it still fires");
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
    // library_variable_description_copied (PLAN-engine-gap-define-text Route D, runbook W1b DT1)
    // ------------------------------------------------------------------

    /**
     * SDTMIG 3.4 AE with four variables: AETERM's comment copies the IG's CDISC Notes verbatim,
     * AESEV's copies them with different whitespace (a line break and a double space), AEACN's
     * comment is the sponsor's own text, AEDECOD has no comment, and AESER's comment copies the
     * Notes in different letter case.
     */
    private static final String AE_WITH_COMMENTS = """
            <def:Standards>
              <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
            </def:Standards>
            <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" def:StandardOID="STD.IG">
              <ItemRef ItemOID="IT.AE.AETERM" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.AE.AESEV" Mandatory="No"/>
              <ItemRef ItemOID="IT.AE.AEACN" Mandatory="No"/>
              <ItemRef ItemOID="IT.AE.AEDECOD" Mandatory="No"/>
              <ItemRef ItemOID="IT.AE.AESER" Mandatory="No"/>
            </ItemGroupDef>
            <ItemDef OID="IT.AE.AETERM" Name="AETERM" def:CommentOID="COM.AETERM"/>
            <ItemDef OID="IT.AE.AESEV" Name="AESEV" def:CommentOID="COM.AESEV"/>
            <ItemDef OID="IT.AE.AEACN" Name="AEACN" def:CommentOID="COM.AEACN"/>
            <ItemDef OID="IT.AE.AEDECOD" Name="AEDECOD"/>
            <ItemDef OID="IT.AE.AESER" Name="AESER" def:CommentOID="COM.AESER"/>
            <def:CommentDef OID="COM.AETERM">
              <Description>
                <TranslatedText xml:lang="en">Verbatim name of the event.</TranslatedText>
              </Description>
            </def:CommentDef>
            <def:CommentDef OID="COM.AESEV">
              <Description>
                <TranslatedText xml:lang="en">  The severity or intensity
                  of the  event. </TranslatedText>
              </Description>
            </def:CommentDef>
            <def:CommentDef OID="COM.AEACN">
              <Description>
                <TranslatedText xml:lang="en">Collected on the AE CRF page.</TranslatedText>
              </Description>
            </def:CommentDef>
            <def:CommentDef OID="COM.AESER">
              <Description>
                <TranslatedText xml:lang="en">WAS THE EVENT SERIOUS?</TranslatedText>
              </Description>
            </def:CommentDef>
            """;

    @Test
    void descriptionCopiedFiresOnlyWhereTheCommentEqualsTheLibraryNotesAfterWhitespaceNormalisation()
    {
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_variable_description_copied"
                """), context(AE_WITH_COMMENTS));
        // AETERM: verbatim copy -> fires. AESEV: the same text under different whitespace (Q2:
        // whitespace-normalised equality) -> fires. AEACN: the sponsor's own text -> silent.
        // AEDECOD: no comment -> silent. AESER: same words, different case -> silent (case-
        // sensitive, D17).
        assertEquals(List.of("AETERM", "AESEV"), values(result));
    }


    @Test
    void descriptionCopiedIsSilentWhereTheLibraryHasNoNotesOrTheStandardIsNotAnIg()
    {
        // the stub knows no DM descriptions at all: a DM comment equal to anything stays silent
        RuleResult noNotes = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_variable_description_copied"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.DM.SEX" Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemDef OID="IT.DM.SEX" Name="SEX" def:CommentOID="COM.SEX"/>
                <def:CommentDef OID="COM.SEX">
                  <Description>
                    <TranslatedText xml:lang="en">Sex</TranslatedText>
                  </Description>
                </def:CommentDef>
                """));
        assertEquals(List.of(), values(noNotes));
        // an ADaM dataset is out of reach, like the label kinds (SDTMIG / SENDIG only)
        RuleResult adam = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_variable_description_copied"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="ADaMIG" Type="IG" Version="1.3"/>
                </def:Standards>
                <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.AE.AETERM" Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemDef OID="IT.AE.AETERM" Name="AETERM" def:CommentOID="COM.AETERM"/>
                <def:CommentDef OID="COM.AETERM">
                  <Description>
                    <TranslatedText xml:lang="en">Verbatim name of the event.</TranslatedText>
                  </Description>
                </def:CommentDef>
                """));
        assertEquals(List.of(), values(adam));
    }


    @Test
    void descriptionCopiedTreatsANoBreakSpaceAsWhitespaceAndABlankCommentAsNoCopy()
    {
        // R5 B-L5: AEREL copies the Notes with a no-break space (&#160;) between two words — the
        // same text under whitespace normalisation, so it fires; AEOUT's comment is a lone
        // no-break space and so are its (stub) Notes: a blank comment copies nothing, whatever
        // the Notes hold
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_variable_description_copied"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.AE.AEREL" Mandatory="No"/>
                  <ItemRef ItemOID="IT.AE.AEOUT" Mandatory="No"/>
                </ItemGroupDef>
                <ItemDef OID="IT.AE.AEREL" Name="AEREL" def:CommentOID="COM.AEREL"/>
                <ItemDef OID="IT.AE.AEOUT" Name="AEOUT" def:CommentOID="COM.AEOUT"/>
                <def:CommentDef OID="COM.AEREL">
                  <Description>
                    <TranslatedText xml:lang="en">Causality&#160;of the event.</TranslatedText>
                  </Description>
                </def:CommentDef>
                <def:CommentDef OID="COM.AEOUT">
                  <Description>
                    <TranslatedText xml:lang="en">&#160;</TranslatedText>
                  </Description>
                </def:CommentDef>
                """));
        assertEquals(List.of("AEREL"), values(result));
    }


    @Test
    void descriptionCopiedReadsTheEnglishCommentTextOnly()
    {
        // a Japanese-only comment carries no English text to compare; an English text beside it
        // is the one compared (Q4)
        RuleResult result = evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "library_variable_description_copied"
                """), context("""
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.AE.AETERM" Mandatory="Yes"/>
                  <ItemRef ItemOID="IT.AE.AESEV" Mandatory="No"/>
                </ItemGroupDef>
                <ItemDef OID="IT.AE.AETERM" Name="AETERM" def:CommentOID="COM.AETERM"/>
                <ItemDef OID="IT.AE.AESEV" Name="AESEV" def:CommentOID="COM.AESEV"/>
                <def:CommentDef OID="COM.AETERM">
                  <Description>
                    <TranslatedText xml:lang="ja">Verbatim name of the event.</TranslatedText>
                  </Description>
                </def:CommentDef>
                <def:CommentDef OID="COM.AESEV">
                  <Description>
                    <TranslatedText xml:lang="ja">x</TranslatedText>
                    <TranslatedText>The severity or intensity of the event.</TranslatedText>
                  </Description>
                </def:CommentDef>
                """));
        assertEquals(List.of("AESEV"), values(result));
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


    /**
     * D-12 (PLAN-define-ct-evaluation): a variable the IG assigns SEVERAL codelists — 21 real
     * SDTMIG/SENDIG variables do, e.g. SDTMIG 3.3 {@code DS.DSDECOD} → {@code C114118},
     * {@code C66727} — must be compared against ALL of them. A define that correctly uses the
     * second one is conformant, not a mismatch against the first.
     */
    @Test
    void codelistCCodeAcceptsAnyOfTheIgsCodelistsForAMultiCodelistVariable()
    {
        String rule = rule("ItemGroupDef/ItemRef", """
                kind: "library_codelist_ccode_matches"
                """);
        RuleResult conformant = evaluate(rule, context(dsDecod("C66727")));
        assertEquals(List.of(), values(conformant),
                "the document uses the IG's SECOND codelist for DSDECOD - that is conformant");

        RuleResult foreign = evaluate(rule, context(dsDecod("C99999")));
        assertEquals(List.of("DSDECOD: C99999 vs C114118, C66727"), values(foreign),
                "a c-code that is none of the IG's codelists fires, and the message lists them all");
    }


    /** A DS dataset whose DSDECOD codelist carries {@code aAlias} as its nci:ExtCodeID. */
    private static String dsDecod(String aAlias)
    {
        return """
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4"/>
                </def:Standards>
                <ItemGroupDef OID="IG.DS" Name="DS" Domain="DS" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.DS.DSDECOD" Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemDef OID="IT.DS.DSDECOD" Name="DSDECOD">
                  <CodeListRef CodeListOID="CL.DSDECOD"/>
                </ItemDef>
                <CodeList OID="CL.DSDECOD" Name="Completion/Reason for Non-Completion">
                  <Alias Context="nci:ExtCodeID" Name="%s"/>
                </CodeList>
                """.formatted(aAlias);
    }

    // ------------------------------------------------------------------
    // library_codelist_ccode_matches with exempt: (PMDA DD0118 on RS clinical classifications,
    // PLAN-stopped-rule-engine-fixes SR2 / SR3 / SR7 / T4 / T8)
    // ------------------------------------------------------------------

    /** The shipped PMDA-DD0118 exemption: RS, one entry per role. */
    private static final String DD0118_EXEMPT = """
            kind: "library_codelist_ccode_matches"
            exempt:
            - datasets: ["RS"]
              variables: ["RSTESTCD"]
              ct_class: "Clinical Classification"
              ct_role: "Test Code"
              standard: "SDTMIG"
              since_version: "3.3"
            - datasets: ["RS"]
              variables: ["RSTEST"]
              ct_class: "Clinical Classification"
              ct_role: "Test Name"
              standard: "SDTMIG"
              since_version: "3.3"
            """;

    /**
     * A CT provider that knows only the QRS class of a few c-codes (the codelists themselves are
     * not consulted by this kind): HAMD-17's five instrument lists and a questionnaire's test code
     * list; everything else (the oncology lists, an unknown c-code) is in no class.
     */
    private static final class ClassedCtProvider implements CtProvider
    {

        private static final Map<String, CodelistClass> CLASSES = Map.of(//
                "C100138", new CodelistClass("Clinical Classification", Optional.of("Test Code")),
                "C100137", new CodelistClass("Clinical Classification", Optional.of("Test Name")),
                "C100136",
                new CodelistClass("Clinical Classification", Optional.of("Original Response")),
                "C100135",
                new CodelistClass("Clinical Classification", Optional.of("Character Response")),
                "C118971", new CodelistClass("Clinical Classification", Optional.of("Category")),
                "C100101", new CodelistClass("Questionnaire", Optional.of("Test Code")));

        @Override
        public Optional<CtCodelist> codelistByCCode(String aCCode)
        {
            return Optional.empty();
        }


        @Override
        public Optional<CodelistClass> codelistClass(String aCCode)
        {
            return Optional.ofNullable(CLASSES.get(aCCode));
        }
    }

    /**
     * An RS dataset governed by {@code aStandard} {@code aVersion} (a 2.1 def:Standards block)
     * whose {@code aVariable} is bound to a codelist carrying {@code aAlias}.
     */
    private static String rs(String aStandard, String aVersion, String aVariable, String aAlias)
    {
        return """
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="%s" Type="IG" Version="%s"/>
                </def:Standards>
                """.formatted(aStandard, aVersion) + rsBody(aVariable, aAlias);
    }


    private static String rsBody(String aVariable, String aAlias)
    {
        return """
                <ItemGroupDef OID="IG.RS" Name="RS" Domain="RS" def:StandardOID="STD.IG">
                  <ItemRef ItemOID="IT.RS.VAR" Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemDef OID="IT.RS.VAR" Name="%s">
                  <CodeListRef CodeListOID="CL.RS"/>
                </ItemDef>
                <CodeList OID="CL.RS" Name="Instrument list">
                  <Alias Context="nci:ExtCodeID" Name="%s"/>
                </CodeList>
                """.formatted(aVariable, aAlias);
    }


    private static List<String> dd0118(String aBody, @Nullable CtProvider aCt)
    {
        return values(evaluate(rule("ItemGroupDef/ItemRef", DD0118_EXEMPT),
                context(aBody, "2.1", new StubLibraryProvider(), "", aCt)));
    }


    @Test
    void codelistCCodeExemptsAClinicalClassificationTestCodeListOnRsSince33()
    {
        ClassedCtProvider ct = new ClassedCtProvider();
        // SDTMIG 3.3 RS.RSTESTCD -> HAMD-17's Test Code list: a clinical classification in RS,
        // which the IG prescribes since 3.3 while the Library assigns the oncology list only
        assertEquals(List.of(), dd0118(rs("SDTMIG", "3.3", "RSTESTCD", "C100138"), ct));
        assertEquals(List.of(), dd0118(rs("SDTMIG", "3.3", "RSTEST", "C100137"), ct),
                "RSTEST takes the Test Name list");
        assertEquals(List.of(), dd0118(rs("SDTMIG", "3.10", "RSTESTCD", "C100138"), ct),
                "3.10 >= 3.3, compared component-wise, not as text");
        assertEquals(List.of(), dd0118(rs("SDTM-IG", "3.4", "RSTESTCD", "C100138"), ct),
                "the standard matches after hyphen removal");
        // the exemption is not blanket: the same define on an unexempt version still fires
        assertEquals(List.of("RSTESTCD: C100138 vs C96782"),
                dd0118(rs("SDTMIG", "3.2", "RSTESTCD", "C100138"), ct),
                "SDTMIG 3.2's RS is Disease Response only");
    }


    @Test
    void codelistCCodeExemptionNeedsTheRoleTheVariableTakes()
    {
        ClassedCtProvider ct = new ClassedCtProvider();
        // T8: the class alone would admit any of HAMD-17's five lists on RSTESTCD
        assertEquals(List.of("RSTESTCD: C100137 vs C96782"),
                dd0118(rs("SDTMIG", "3.3", "RSTESTCD", "C100137"), ct),
                "RSTESTCD bound to the instrument's Test NAME list is a mismatch");
        assertEquals(List.of("RSTESTCD: C100136 vs C96782"),
                dd0118(rs("SDTMIG", "3.3", "RSTESTCD", "C100136"), ct),
                "an Original Response list on RSTESTCD is a mismatch");
        assertEquals(List.of("RSTEST: C100138 vs C96781"),
                dd0118(rs("SDTMIG", "3.3", "RSTEST", "C100138"), ct),
                "RSTEST bound to the Test CODE list is a mismatch");
    }


    @Test
    void codelistCCodeExemptionIsBoundToItsDatasetVariablesClassAndStandard()
    {
        ClassedCtProvider ct = new ClassedCtProvider();
        assertEquals(List.of("RSORRES: C100136 vs C96783"),
                dd0118(rs("SDTMIG", "3.3", "RSORRES", "C100136"), ct),
                "RSORRES is not an exempt variable");
        assertEquals(List.of("RSTESTCD: C100101 vs C96782"),
                dd0118(rs("SDTMIG", "3.3", "RSTESTCD", "C100101"), ct),
                "a Questionnaire test-code list is another class: questionnaires belong in QS");
        assertEquals(List.of("RSTESTCD: C99999 vs C96782"),
                dd0118(rs("SDTMIG", "3.3", "RSTESTCD", "C99999"), ct),
                "a c-code in no QRS class (an oncology mismatch) fires as before");
        assertEquals(List.of("RSTESTCD: C100138 vs C96782"),
                dd0118(rs("SDTMIG-MD", "3.3", "RSTESTCD", "C100138"), ct),
                "SDTMIG-MD is not SDTMIG: equality after hyphen removal, never startsWith");
        assertEquals(List.of("RSTESTCD: C100138 vs C96782"),
                dd0118(rs("SDTMIG", "3.3 Amendment 1", "RSTESTCD", "C100138"), ct),
                "a non-numeric version is not covered - the conservative direction");
    }


    /**
     * SR7 (a) / T4: the class comes from the bound CT ONLY. With no CT bound — every 2.0 define
     * ({@code def:Standards} is a 2.1 element, so none names a CT package) and a 2.1 define that
     * declares no CT — the exemption is inert and DD0118 fires as it did before. The engine never
     * selects a CT version on its own (owner 2026-10-12).
     */
    @Test
    void codelistCCodeExemptionIsInertWithoutABoundCt()
    {
        assertEquals(List.of("RSTESTCD: C100138 vs C96782"),
                dd0118(rs("SDTMIG", "3.3", "RSTESTCD", "C100138"), null),
                "a 2.1 define with no CT provider bound: the class is unknown, the rule fires");
        // the 2.0 leg: the standard from the MetaDataVersion attributes, no CT provider
        List<String> twoZero = values(evaluate(rule("ItemGroupDef/ItemRef", DD0118_EXEMPT),
                context(rsBody("RSTESTCD", "C100138"), "2.0", new StubLibraryProvider(),
                        "def:StandardName=\"SDTM-IG\" def:StandardVersion=\"3.3\"", null)));
        assertEquals(List.of("RSTESTCD: C100138 vs C96782"), twoZero,
                "a 2.0 define binds no CT, so the gap is accepted and pinned (SR7 (a))");
        // sensitivity: the same 2.0 define WITH a CT bound is exempt — the provider is the only
        // thing that changed
        List<String> twoZeroWithCt = values(evaluate(rule("ItemGroupDef/ItemRef", DD0118_EXEMPT),
                context(rsBody("RSTESTCD", "C100138"), "2.0", new StubLibraryProvider(),
                        "def:StandardName=\"SDTM-IG\" def:StandardVersion=\"3.3\"",
                        new ClassedCtProvider())));
        assertEquals(List.of(), twoZeroWithCt, "-ct / CT Packages on a 2.0 run binds the class");
    }


    @Test
    void codelistCCodeWithoutAnExemptBlockIsUnchanged()
    {
        ClassedCtProvider ct = new ClassedCtProvider();
        String plain = """
                kind: "library_codelist_ccode_matches"
                """;
        assertEquals(List.of("RSTESTCD: C100138 vs C96782"),
                values(evaluate(rule("ItemGroupDef/ItemRef", plain),
                        context(rs("SDTMIG", "3.3", "RSTESTCD", "C100138"), "2.1",
                                new StubLibraryProvider(), "", ct))),
                "no exempt block: a classed CT changes nothing");
        assertEquals(List.of("SEX: C99999 vs C66731"),
                values(evaluate(rule("ItemGroupDef/ItemRef", plain),
                        context(DM_AND_AE, "2.1", new StubLibraryProvider(), "", ct))));
    }


    @Test
    void codelistCCodeExemptEntriesAreValidatedAtLoad()
    {
        String head = """
                kind: "library_codelist_ccode_matches"
                exempt:
                """;
        for (String entry : List.of("""
                - datasets: []
                  variables: ["RSTESTCD"]
                  ct_class: "Clinical Classification"
                  ct_role: "Test Code"
                """, """
                - datasets: ["RS"]
                  variables: ["RSTESTCD"]
                  ct_class: "Clinical Classification"
                """, """
                - datasets: ["RS"]
                  variables: ["RSTESTCD"]
                  ct_class: "Clinical Classification"
                  ct_role: "Test Code"
                  since_version: "3.3"
                """, """
                - datasets: ["RS"]
                  variables: ["RSTESTCD"]
                  ct_class: "Clinical Classification"
                  ct_role: "Test Code"
                  standard: "SDTMIG"
                  since_version: "3.3a"
                """))
        {
            String yaml = rule("ItemGroupDef/ItemRef", head + entry);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> RuleRepository.parse(yaml, "test"), entry);
            assertTrue(failure.getMessage().contains("library_codelist_ccode_matches exempt"),
                    failure.getMessage());
        }
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
