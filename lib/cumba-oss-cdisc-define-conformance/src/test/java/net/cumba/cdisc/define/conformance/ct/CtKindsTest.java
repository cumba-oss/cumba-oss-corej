package net.cumba.cdisc.define.conformance.ct;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.eval.RuleEvaluator;
import net.cumba.cdisc.define.conformance.eval.RuleResult;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The five CT-backed check kinds (plan §3.3/§3.6): {@code term_in_ct_codelist} (enclosing-codelist
 * and explicit-cCode resolution, {@code nonExtensibleOnly}, {@code exemptExtendedValues}),
 * {@code nci_code_known} (codelist / term levels), {@code term_matches_nci_code},
 * {@code extended_value_marking} (required / forbidden), {@code nci_alias_required} (codelist /
 * term levels) — plus the {@code Requires: ct} SKIP gate, the loud failure on a CT kind evaluated
 * without a provider, and the kinds' load-time mode/level validation.
 */
class CtKindsTest
{

    private static final String SEX_AND_UNIT = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1">
              <CodeList OID="CL.SEX" Name="Sex Codes" def:StandardOID="STD.CT">
                <EnumeratedItem CodedValue="F">
                  <Alias Context="nci:ExtCodeID" Name="C16576"/>
                </EnumeratedItem>
                <EnumeratedItem CodedValue="X"/>
                <Alias Context="nci:ExtCodeID" Name="C66731"/>
              </CodeList>
              <CodeList OID="CL.UNIT" Name="Unit Codes" def:StandardOID="STD.CT">
                <EnumeratedItem CodedValue="furlong"/>
                <Alias Context="nci:ExtCodeID" Name="C71620"/>
              </CodeList>
            </ODM>
            """;

    private static DocumentContext context(String aXml, @Nullable CtProvider aProvider)
    {
        try
        {
            ElementNode root = ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(aXml.getBytes(UTF_8))));
            return new DocumentContext(root, "2.1", aProvider, null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static String ctRule(String aElement, String aCheckBody)
    {
        return ctRule(aElement, aCheckBody, "Requires: \"ct\"");
    }


    private static String ctRule(String aElement, String aCheckBody, String aRequiresLine)
    {
        return """
                Rule_Id: "PMDA-TEST01"
                Sheet_Rule_Identifier: "TEST01"
                Rule_Set: "PMDA"
                Element: "%s"
                Applicable_Versions: ["2.1"]
                Severity: "Warning"
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


    private static List<String> messages(RuleResult aResult)
    {
        return aResult.findings().stream().map(f -> f.getMessage()).toList();
    }

    // ------------------------------------------------------------------
    // term_in_ct_codelist
    // ------------------------------------------------------------------


    @Test
    void termInCtCodelistFiresPerNonMemberItem()
    {
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                """), context(SEX_AND_UNIT, new StubCtProvider()));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        // F is a C66731 member; X is not; furlong is not a C71620 member (no
        // nonExtensibleOnly restriction here, so the extensible Unit codelist is checked too).
        assertEquals(List.of("Offending value [X].", "Offending value [furlong]."),
                messages(result));
    }


    @Test
    void termInCtCodelistNonExtensibleOnlySkipsExtensibleCodelists()
    {
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                nonExtensibleOnly: true
                """), context(SEX_AND_UNIT, new StubCtProvider()));
        assertEquals(List.of("Offending value [X]."), messages(result));
    }


    @Test
    void termInCtCodelistExemptsMarkedExtendedValues()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.UNIT" Name="Unit Codes">
                    <EnumeratedItem CodedValue="furlong" def:ExtendedValue="Yes"/>
                    <EnumeratedItem CodedValue="stone"/>
                    <Alias Context="nci:ExtCodeID" Name="C71620"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                exemptExtendedValues: true
                """), context);
        assertEquals(List.of("Offending value [stone]."), messages(result));
    }


    @Test
    void termInCtCodelistResolvesExplicitCCodeForCodelistLessTargets()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <ItemGroupDef OID="IG.AE" Name="AE">
                    <def:Class Name="EVENTS"/>
                  </ItemGroupDef>
                  <ItemGroupDef OID="IG.XX" Name="XX">
                    <def:Class Name="WEIRD CLASS"/>
                  </ItemGroupDef>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("def:Class", """
                kind: "term_in_ct_codelist"
                attribute: "Name"
                cCode: "C103329"
                """), context);
        assertEquals(List.of("Offending value [WEIRD CLASS]."), messages(result));
    }


    @Test
    void termInCtCodelistSkipsUnresolvableCodelists()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.A" Name="No Alias At All">
                    <EnumeratedItem CodedValue="whatever"/>
                  </CodeList>
                  <CodeList OID="CL.B" Name="Unknown Code">
                    <EnumeratedItem CodedValue="whatever"/>
                    <Alias Context="nci:ExtCodeID" Name="C99999"/>
                  </CodeList>
                  <CodeList OID="CL.C" Name="Non-NCI Alias">
                    <EnumeratedItem CodedValue="whatever"/>
                    <Alias Context="SAS" Name="WHATEVER"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                """), context);
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(List.of(), result.findings());
    }


    @Test
    void ctGateSkipsRequiresCtRulesWithoutProvider()
    {
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                """), context(SEX_AND_UNIT, null));
        assertEquals(ExecutionStatus.SKIPPED_MISSING_CT, result.status());
        assertEquals(List.of(), result.findings());
    }


    @Test
    void ctKindWithoutRequiresDeclarationFailsLoudlyWithoutProvider()
    {
        // An authoring error (CT kind, no 'Requires: ct'): the gate does not skip, and the
        // kind must fail loudly instead of silently passing.
        DocumentContext context = context(SEX_AND_UNIT, null);
        String rule = ctRule("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                """, "Attribute: \"CodedValue\"");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> evaluate(rule, context));
        assertTrue(failure.getMessage().contains("Requires: ct"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // nci_code_known
    // ------------------------------------------------------------------


    @Test
    void nciCodeKnownCodelistLevelFiresOnUnknownCCodeAndIgnoresOtherContexts()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.A" Name="Known">
                    <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  </CodeList>
                  <CodeList OID="CL.B" Name="Unknown">
                    <Alias Context="nci:ExtCodeID" Name="C99999"/>
                  </CodeList>
                  <CodeList OID="CL.C" Name="Other Context">
                    <Alias Context="SAS" Name="NOTACODE"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("CodeList/Alias", """
                kind: "nci_code_known"
                level: "codelist"
                """), context);
        assertEquals(List.of("Offending value [C99999]."), messages(result));
    }


    @Test
    void nciCodeKnownTermLevelChecksAgainstTheEnclosingCodelistTerms()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.SEX" Name="Sex Codes">
                    <EnumeratedItem CodedValue="F">
                      <Alias Context="nci:ExtCodeID" Name="C16576"/>
                    </EnumeratedItem>
                    <EnumeratedItem CodedValue="M">
                      <Alias Context="nci:ExtCodeID" Name="C99998"/>
                    </EnumeratedItem>
                    <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  </CodeList>
                  <CodeList OID="CL.X" Name="Unresolvable">
                    <EnumeratedItem CodedValue="A">
                      <Alias Context="nci:ExtCodeID" Name="C99997"/>
                    </EnumeratedItem>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem/Alias", """
                kind: "nci_code_known"
                level: "term"
                """), context);
        // C16576 is a C66731 term c-code; C99998 is not; C99997 sits in a codelist without
        // a resolvable alias and is skipped.
        assertEquals(List.of("Offending value [C99998]."), messages(result));
    }


    @Test
    void nciCodeKnownRejectsAnUnknownLevelAtLoadTime()
    {
        String rule = ctRule("CodeList/Alias", """
                kind: "nci_code_known"
                level: "banana"
                """);
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse(rule, "test"));
    }

    // ------------------------------------------------------------------
    // term_matches_nci_code
    // ------------------------------------------------------------------


    @Test
    void termMatchesNciCodeFiresOnSubmissionValueMismatch()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.UNIT" Name="Unit Codes">
                    <EnumeratedItem CodedValue="mg">
                      <Alias Context="nci:ExtCodeID" Name="C28253"/>
                    </EnumeratedItem>
                    <EnumeratedItem CodedValue="milligram">
                      <Alias Context="nci:ExtCodeID" Name="C28253"/>
                    </EnumeratedItem>
                    <EnumeratedItem CodedValue="noalias"/>
                    <EnumeratedItem CodedValue="notinlist">
                      <Alias Context="nci:ExtCodeID" Name="C99999"/>
                    </EnumeratedItem>
                    <Alias Context="nci:ExtCodeID" Name="C71620"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "term_matches_nci_code"
                """), context);
        // "mg" matches C28253; "milligram" does not; the alias-less item and the c-code
        // that is not in the codelist (DD0034's finding) are skipped.
        assertEquals(List.of("Offending value [milligram]."), messages(result));
    }

    // ------------------------------------------------------------------
    // extended_value_marking
    // ------------------------------------------------------------------


    @Test
    void extendedValueMarkingRequiredFiresOnUnmarkedExtension()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.UNIT" Name="Unit Codes">
                    <EnumeratedItem CodedValue="mg"/>
                    <EnumeratedItem CodedValue="furlong"/>
                    <EnumeratedItem CodedValue="bucket" def:ExtendedValue="Yes"/>
                    <Alias Context="nci:ExtCodeID" Name="C71620"/>
                  </CodeList>
                  <CodeList OID="CL.SEX" Name="Sex Codes">
                    <EnumeratedItem CodedValue="Q"/>
                    <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "extended_value_marking"
                mode: "required"
                """), context);
        // mg is a member, bucket is a marked extension; furlong is an unmarked extension of
        // the extensible Unit codelist. Q sits in a NON-extensible codelist - out of this
        // mode's reach (its non-membership is the term_in_ct_codelist rules' finding).
        assertEquals(List.of("Offending value [furlong]."), messages(result));
    }


    @Test
    void extendedValueMarkingForbiddenFiresOnMarkedItemOfNonExtensibleCodelist()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.SEX" Name="Sex Codes">
                    <EnumeratedItem CodedValue="F" def:ExtendedValue="Yes"/>
                    <EnumeratedItem CodedValue="M"/>
                    <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  </CodeList>
                  <CodeList OID="CL.UNIT" Name="Unit Codes">
                    <EnumeratedItem CodedValue="bucket" def:ExtendedValue="Yes"/>
                    <Alias Context="nci:ExtCodeID" Name="C71620"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "extended_value_marking"
                mode: "forbidden"
                """), context);
        // Marking is legal on the extensible Unit codelist, illegal on non-extensible Sex.
        assertEquals(List.of("Offending value [F]."), messages(result));
    }


    @Test
    void extendedValueMarkingRejectsAnUnknownModeAtLoadTime()
    {
        String rule = ctRule("EnumeratedItem", """
                kind: "extended_value_marking"
                mode: "sometimes"
                """);
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse(rule, "test"));
    }

    // ------------------------------------------------------------------
    // nci_alias_required
    // ------------------------------------------------------------------


    @Test
    void nciAliasRequiredCodelistLevelUsesNameLookup()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.A" Name="Sex">
                    <EnumeratedItem CodedValue="F"/>
                  </CodeList>
                  <CodeList OID="CL.B" Name="Sponsor Thing">
                    <EnumeratedItem CodedValue="A"/>
                  </CodeList>
                  <CodeList OID="CL.C" Name="Unit">
                    <EnumeratedItem CodedValue="mg"/>
                    <Alias Context="nci:ExtCodeID" Name="C71620"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("CodeList", """
                kind: "nci_alias_required"
                level: "codelist"
                """), context);
        // "Sex" is a CT codelist name without an alias; "Sponsor Thing" is unknown to CT
        // (no finding, conservatively); "Unit" carries its alias.
        assertEquals(List.of("Offending value [Sex]."), messages(result));
    }


    @Test
    void nciAliasRequiredTermLevelFiresOnCtMembersWithoutAlias()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <CodeList OID="CL.SEX" Name="Sex Codes">
                    <EnumeratedItem CodedValue="F">
                      <Alias Context="nci:ExtCodeID" Name="C16576"/>
                    </EnumeratedItem>
                    <EnumeratedItem CodedValue="M"/>
                    <EnumeratedItem CodedValue="X"/>
                    <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  </CodeList>
                </ODM>
                """, new StubCtProvider());
        RuleResult result = evaluate(ctRule("EnumeratedItem", """
                kind: "nci_alias_required"
                level: "term"
                """), context);
        // F carries its alias; M is a CT member without one; X is not a CT term at all
        // (not "defined in CDISC Controlled Terminology" - out of reach).
        assertEquals(List.of("Offending value [M]."), messages(result));
    }


    @Test
    void nciAliasRequiredRejectsAnUnknownLevelAtLoadTime()
    {
        String rule = ctRule("CodeList", """
                kind: "nci_alias_required"
                level: "package"
                """);
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse(rule, "test"));
    }


    @Test
    void codelistByNameDefaultsToEmptyForMinimalProviders()
    {
        // Lambda/minimal CtProvider implementations do not override codelistByName; the
        // conservative empty default means name-keyed rules find nothing rather than mis-fire.
        CtProvider minimal = _ -> java.util.Optional.empty();
        assertTrue(minimal.codelistByName("Sex").isEmpty());
    }

}
