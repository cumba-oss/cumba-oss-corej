package net.cumba.corej.define.conformance.eval;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.corej.define.conformance.library.StubLibraryProvider;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code matches_regex} with {@code exemptLibraryLabel: true}
 * ({@code PLAN-define-rule-library-label} §7.1 E1–E8, E10–E12): a value that fails the pattern
 * passes when it is the CDISC Library label of the scoped text's OWN variable — every
 * {@code ItemGroupDef/ItemRef} binding of the {@code ItemDef} whose {@code Description} holds the
 * text, under the standard and version the define declares for the binding's dataset. Against
 * {@link StubLibraryProvider}'s SENDIG 3.0 / 3.1 labels. Each case names the wrong implementation
 * it reds: a pooled label set (E1), an engine-chosen version (E2), a last-wins index (E10), a
 * nearest-ancestor walk (E11), an eager scan (E12).
 */
class MatchesRegexLibraryLabelTest
{

    private static final String MDV = "MetaDataVersion[@OID='MDV.1']";

    /** The DEFINE-XML-9001 shape: ItemDef and CommentDef descriptions, the label exemption on. */
    private static final String SCOPED_RULE = """
            Rule_Id: "DEFINE-XML-9999"
            Sheet_Rule_Identifier: "9999"
            Rule_Set: "CDISC"
            Element: "Description/TranslatedText"
            Attribute: null
            Applicable_Versions: ["2.0", "2.1"]
            Severity: "Warning"
            Source_Type: "Specification"
            Requires: "library"
            Plain_Text_Rule: "Test rule."
            Message: "Uses animal [${value}]."
            Check:
              kind: "matches_regex"
              attribute: "#text"
              pattern: '(?is)(?!.*\\banimals?\\b).*'
              exemptLibraryLabel: true
              when:
                any:
                - path: "../../@OID->ItemDef@OID/@OID"
                  exists: true
                - path: "../../@OID->CommentDef@OID/@OID"
                  exists: true
            """;

    /** E11: the same check with no {@code when:}, so every Description/TranslatedText is scoped. */
    private static final String UNGUARDED_RULE = SCOPED_RULE.substring(0,
            SCOPED_RULE.indexOf("  when:"));

    private static String standards21(String aIgVersion)
    {
        return """
                <def:Standards>
                  <def:Standard OID="STD.IG" Name="SENDIG" Type="IG" Version="%s" Status="Final"/>
                  <def:Standard OID="STD.MODEL" Name="SEND" Type="IG" Version="1.0" Status="Final"/>
                </def:Standards>
                """.formatted(aIgVersion);
    }


    private static String odm(String aDefNamespace, String aMetaDataVersionAttributes, String aBody)
    {
        return """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/%s">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV" %s>
                """.formatted(aDefNamespace, aMetaDataVersionAttributes) + aBody + """
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
    }


    /** A Define-XML 2.1 body governed by SENDIG {@code aIgVersion} through def:StandardOID. */
    private static String define21(String aIgVersion, String aBody)
    {
        return odm("v2.1", "", standards21(aIgVersion) + aBody);
    }


    /** A Define-XML 2.0 body: the standard named on MetaDataVersion. */
    private static String define20(String aIgVersion, String aBody)
    {
        return odm("v2.0",
                "def:StandardName=\"SEND-IG\" def:StandardVersion=\"" + aIgVersion + "\"", aBody);
    }


    /** An ItemGroupDef named {@code aName} (Domain too) with one ItemRef per item OID. */
    private static String group(String aName, @Nullable String aStandardOid, String... aItemOids)
    {
        StringBuilder out = new StringBuilder(128).append("<ItemGroupDef OID=\"IG.").append(aName)
                .append("\" Name=\"").append(aName).append("\" Domain=\"").append(aName)
                .append('"');
        if (aStandardOid != null)
        {
            out.append(" def:StandardOID=\"").append(aStandardOid).append('"');
        }
        out.append('>');
        for (String oid : aItemOids)
        {
            out.append("<ItemRef ItemOID=\"").append(oid).append("\" Mandatory=\"No\"/>");
        }
        return out.append("</ItemGroupDef>").toString();
    }


    private static String item(String aOid, String aName, String aText)
    {
        return item(aOid, aName, aText, "");
    }


    private static String item(String aOid, String aName, String aText, String aInner)
    {
        return "<ItemDef OID=\"" + aOid + "\" Name=\"" + aName + "\" DataType=\"text\">"
                + "<Description><TranslatedText xml:lang=\"en\">" + aText
                + "</TranslatedText></Description>" + aInner + "</ItemDef>";
    }


    private static String comment(String aOid, String aText)
    {
        return "<def:CommentDef OID=\"" + aOid + "\"><Description><TranslatedText xml:lang=\"en\">"
                + aText + "</TranslatedText></Description></def:CommentDef>";
    }


    private static DocumentContext context(String aXml, String aVersion,
            @Nullable LibraryProvider aLibrary)
    {
        try
        {
            return new DocumentContext(
                    ElementNodeBuilder.build(
                            DefineDomIo.parse(new ByteArrayInputStream(aXml.getBytes(UTF_8)))),
                    aVersion, null, null, List.of(), aLibrary);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static RuleResult run(String aRule, String aXml, String aVersion,
            @Nullable LibraryProvider aLibrary)
    {
        ConformanceRule rule = RuleRepository.parse(aRule, "test");
        return new RuleEvaluator().evaluate(rule, context(aXml, aVersion, aLibrary));
    }


    /** Where each finding sits, below the MetaDataVersion; the run must have EXECUTED. */
    private static List<String> firing(RuleResult aResult)
    {
        assertEquals(ExecutionStatus.EXECUTED, aResult.status());
        return aResult.findings().stream().map(ConformanceFinding::getXpath)
                .map(x -> x.substring(x.indexOf(MDV) + MDV.length())).toList();
    }


    private static List<String> firing(String aXml, String aVersion)
    {
        return firing(run(SCOPED_RULE, aXml, aVersion, new StubLibraryProvider()));
    }


    private static String at(String aOid)
    {
        String element = aOid.startsWith("COM.") ? "CommentDef" : "ItemDef";
        return "/" + element + "[@OID='" + aOid + "']/Description/TranslatedText";
    }


    @Test
    void e1TheExemptionIsTheLabelOfTheTextsOwnVariable()
    {
        // BWDTC's label is the text; BWTESTCD's is "Test Short Name". A pooled set of every label
        // in the document would also let BWTESTCD pass.
        String xml = define21("3.1",
                group("BW", "STD.IG", "IT.BW.BWTESTCD", "IT.BW.BWDTC")
                        + item("IT.BW.BWTESTCD", "BWTESTCD", "Date/Time Animal Weighed")
                        + item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed"));
        assertEquals(List.of(at("IT.BW.BWTESTCD")), firing(xml, "2.1"));
    }


    @Test
    void e2TheLabelIsTheOneOfTheVersionTheDefineDeclares()
    {
        // SENDIG 3.0 labels BGDY "Study Day Animal Weighed", 3.1 "Study Day of Start of Interval"
        String body = group("BG", "STD.IG", "IT.BG.BGDY")
                + item("IT.BG.BGDY", "BGDY", "Study Day Animal Weighed");
        assertEquals(List.of(at("IT.BG.BGDY")), firing(define21("3.1", body), "2.1"));
        assertEquals(List.of(), firing(define21("3.0", body), "2.1"));
        assertEquals(List.of(at("IT.BG.BGDY")), firing(define20("3.1", body), "2.0"));
        assertEquals(List.of(), firing(define20("3.0", body), "2.0"));
    }


    @Test
    void e3ALabelNoListEverNamedIsExemptWithoutACorpusEdit()
    {
        String xml = define21("3.1", group("BW", "STD.IG", "IT.BW.BWXANIM")
                + item("IT.BW.BWXANIM", "BWXANIM", "Animal Housing Weight"));
        assertEquals(List.of(), firing(xml, "2.1"));
    }


    @Test
    void e4ACommentIsNeverALabelEvenWhenWordedAsOne()
    {
        // LL6 (a): the comment of the very variable whose label it repeats still fires
        String xml = define21("3.1",
                group("BW", "STD.IG", "IT.BW.BWDTC")
                        + "<ItemDef OID=\"IT.BW.BWDTC\" Name=\"BWDTC\" DataType=\"text\""
                        + " def:CommentOID=\"COM.1\"><Description><TranslatedText xml:lang=\"en\">"
                        + "Date/Time Animal Weighed</TranslatedText></Description></ItemDef>"
                        + comment("COM.1", "Date/Time Animal Weighed")
                        + comment("COM.2", "Each subject was weighed."));
        assertEquals(List.of(at("COM.1")), firing(xml, "2.1"));
    }


    @Test
    void e5WithoutALibraryTheRuleIsSkippedCommentsIncluded()
    {
        // LL2 (a): the price the ruling accepted (review L2) - the comment needs no library, yet
        // the whole rule skips
        String xml = define21("3.1",
                group("BW", "STD.IG", "IT.BW.BWTESTCD")
                        + item("IT.BW.BWTESTCD", "BWTESTCD", "Animal Weight Code")
                        + comment("COM.1", "All animals were fasted overnight."));
        RuleResult result = run(SCOPED_RULE, xml, "2.1", null);
        assertEquals(ExecutionStatus.SKIPPED_MISSING_LIBRARY, result.status());
        assertEquals(List.of(), result.findings());
        assertEquals(List.of(at("IT.BW.BWTESTCD"), at("COM.1")), firing(xml, "2.1"),
                "with the library bound both fire");
    }


    @Test
    void e6AndLl3WithoutAResolvableIgLabelThereIsNoExemption()
    {
        String items = item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed")
                + item("IT.BW.BWDY", "BWDY", "Study Day Animal Weighed");
        // E6 / LL4 (a): a 2.1 dataset without def:StandardOID has no governing standard, though
        // the define declares exactly one SENDIG
        assertEquals(List.of(at("IT.BW.BWDTC"), at("IT.BW.BWDY")), firing(
                define21("3.1", group("BW", null, "IT.BW.BWDTC", "IT.BW.BWDY") + items), "2.1"));
        // LL3 (a): a version the library does not hold
        assertEquals(List.of(at("IT.BW.BWDTC"), at("IT.BW.BWDY")),
                firing(define21("3.2", group("BW", "STD.IG", "IT.BW.BWDTC", "IT.BW.BWDY") + items),
                        "2.1"));
        // a governing standard that is not an SDTMIG / SENDIG (the SEND model)
        assertEquals(List.of(at("IT.BW.BWDTC"), at("IT.BW.BWDY")), firing(
                define21("3.1", group("BW", "STD.MODEL", "IT.BW.BWDTC", "IT.BW.BWDY") + items),
                "2.1"));
        // the control: the same items governed by SENDIG 3.1 pass
        assertEquals(List.of(),
                firing(define21("3.1", group("BW", "STD.IG", "IT.BW.BWDTC", "IT.BW.BWDY") + items),
                        "2.1"));
    }


    @Test
    void e7WhitespaceIsCollapsedAndLetterCaseIgnored()
    {
        // LL5 (b): a doubled space, a no-break space and another letter case are still the IG's
        // wording; a different word is not
        String xml = define21("3.1",
                group("BW", "STD.IG", "IT.BW.BWDTC", "IT.BW.BWDY", "IT.BW.BWTESTCD")
                        + item("IT.BW.BWDTC", "BWDTC", "date/time  animal weighed")
                        + item("IT.BW.BWDY", "BWDY", "Study Day Animal\tWeighed")
                        + item("IT.BW.BWTESTCD", "BWTESTCD", "Test Short Name of the animal"));
        assertEquals(List.of(at("IT.BW.BWTESTCD")), firing(xml, "2.1"));
    }


    @Test
    void e8AValueLevelItemDefHasNoBindingAndNoExemption()
    {
        // T2: referenced only from def:ValueListDef/ItemRef, never from an ItemGroupDef
        String xml = define21("3.1",
                group("BW", "STD.IG", "IT.BW.BWDTC")
                        + "<def:ValueListDef OID=\"VL.BW\"><ItemRef ItemOID=\"IT.BW.BWDTC.VL\""
                        + " Mandatory=\"No\"/></def:ValueListDef>"
                        + item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed")
                        + item("IT.BW.BWDTC.VL", "BWDTC", "Date/Time Animal Weighed"));
        assertEquals(List.of(at("IT.BW.BWDTC.VL")), firing(xml, "2.1"));
    }


    @Test
    void e10EveryBindingOfASharedItemDefCounts()
    {
        // T1: one ItemDef referenced from BW first and BG second; a last-wins index keeps only
        // the BG binding and fires on the BW label
        String groups = group("BW", "STD.IG", "IT.XXANIM") + group("BG", "STD.IG", "IT.XXANIM");
        assertEquals(List.of(), firing(
                define21("3.1", groups + item("IT.XXANIM", "XXANIM", "Animal Count Weighed")),
                "2.1"));
        assertEquals(List.of(), firing(
                define21("3.1", groups + item("IT.XXANIM", "XXANIM", "Animal Count Grouped")),
                "2.1"));
        assertEquals(List.of(at("IT.XXANIM")),
                firing(define21("3.1", groups + item("IT.XXANIM", "XXANIM", "Animal Count Housed")),
                        "2.1"));
    }


    @Test
    void e11AnOriginTextHasNoOwnItemDef()
    {
        // T8: Description/TranslatedText also matches def:Origin/Description/TranslatedText under
        // the ItemDef; only the ItemDef's own Description reads its label
        String xml = define21("3.1",
                group("BW", "STD.IG", "IT.BW.BWDTC") + item("IT.BW.BWDTC", "BWDTC",
                        "Date/Time Animal Weighed",
                        "<def:Origin Type=\"Collected\"><Description><TranslatedText"
                                + " xml:lang=\"en\">Date/Time Animal Weighed</TranslatedText>"
                                + "</Description></def:Origin>"));
        List<String> fired = firing(run(UNGUARDED_RULE, xml, "2.1", new StubLibraryProvider()));
        assertEquals(List.of("/ItemDef[@OID='IT.BW.BWDTC']/Origin/Description/TranslatedText"),
                fired);
    }


    @Test
    void anItemDefScopedDirectlyIsItsOwnItemDef()
    {
        // T8's other leg: a rule scoped to the ItemDef itself reads that ItemDef's label
        String rule = SCOPED_RULE
                .replace("Element: \"Description/TranslatedText\"", "Element: \"ItemDef\"")
                .replace("attribute: \"#text\"", "attribute: \"SASFieldName\"");
        rule = rule.substring(0, rule.indexOf("  when:"));
        String xml = define21("3.1", group("BW", "STD.IG", "IT.BW.BWDTC", "IT.BW.BWTESTCD")
                + "<ItemDef OID=\"IT.BW.BWDTC\" Name=\"BWDTC\" SASFieldName=\"Date/Time Animal"
                + " Weighed\" DataType=\"text\"/><ItemDef OID=\"IT.BW.BWTESTCD\" Name=\"BWTESTCD\""
                + " SASFieldName=\"Date/Time Animal Weighed\" DataType=\"text\"/>");
        assertEquals(List.of("/ItemDef[@OID='IT.BW.BWTESTCD']"),
                firing(run(rule, xml, "2.1", new StubLibraryProvider())));
    }


    @Test
    void e12TheLibraryIsReadOnlyForAFailingNodesOwnBindings()
    {
        // review M1: nothing fails => no lookup at all; one failing ItemDef => only its own
        // bindings are looked up (an eager scan asks once per bound ItemRef)
        CountingLibrary clean = new CountingLibrary();
        String bound = group("BW", "STD.IG", "IT.BW.BWDY", "IT.BW.BWTESTCD", "IT.XXANIM")
                + group("BG", "STD.IG", "IT.XXANIM");
        assertEquals(List.of(), firing(run(SCOPED_RULE,
                define21("3.1",
                        bound + item("IT.BW.BWDY", "BWDY", "Study Day of Weighing")
                                + item("IT.BW.BWTESTCD", "BWTESTCD", "Test Short Name")
                                + item("IT.XXANIM", "XXANIM", "Subject Count")),
                "2.1", clean)));
        assertEquals(List.of(), clean.calls);

        CountingLibrary failing = new CountingLibrary();
        assertEquals(List.of(at("IT.XXANIM")),
                firing(run(SCOPED_RULE,
                        define21("3.1",
                                bound + item("IT.BW.BWDY", "BWDY", "Study Day of Weighing")
                                        + item("IT.BW.BWTESTCD", "BWTESTCD", "Test Short Name")
                                        + item("IT.XXANIM", "XXANIM", "Animal Count Housed")),
                        "2.1", failing)));
        assertEquals(List.of("SENDIG|3.1|BW|XXANIM", "SENDIG|3.1|BG|XXANIM"), failing.calls);
    }


    @Test
    void theFlagOffKeepsThePlainPatternAndNeedsNoLibrary()
    {
        String rule = SCOPED_RULE.replace("exemptLibraryLabel: true", "exemptLibraryLabel: false")
                .replace("Requires: \"library\"\n", "");
        String xml = define21("3.1", group("BW", "STD.IG", "IT.BW.BWDTC")
                + item("IT.BW.BWDTC", "BWDTC", "Date/Time Animal Weighed"));
        assertEquals(List.of(at("IT.BW.BWDTC")), firing(run(rule, xml, "2.1", null)));
    }

    /**
     * A {@link StubLibraryProvider} that records every {@code variableLabel} question (no Mockito).
     */
    private static final class CountingLibrary implements LibraryProvider
    {

        private final StubLibraryProvider stub = new StubLibraryProvider();

        private final List<String> calls = new ArrayList<>();

        @Override
        public Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
                String aDatasetName)
        {
            return stub.datasetLabel(aStandardName, aStandardVersion, aDatasetName);
        }


        @Override
        public Optional<String> variableLabel(String aStandardName, String aStandardVersion,
                String aDatasetName, String aVariableName)
        {
            calls.add(aStandardName + "|" + aStandardVersion + "|" + aDatasetName + "|"
                    + aVariableName);
            return stub.variableLabel(aStandardName, aStandardVersion, aDatasetName, aVariableName);
        }


        @Override
        public Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
                String aDatasetName, String aVariableName)
        {
            return stub.variableCodelistCCode(aStandardName, aStandardVersion, aDatasetName,
                    aVariableName);
        }


        @Override
        public Optional<String> qualifierVariableLabel(String aStandardName,
                String aStandardVersion, String aFragment)
        {
            return stub.qualifierVariableLabel(aStandardName, aStandardVersion, aFragment);
        }
    }
}
