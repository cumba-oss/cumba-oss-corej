package net.cumba.corej.define.conformance.ct;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * What the CT-backed kinds do with a <b>present but blank</b> attribute.
 *
 * <p>
 * This is not a curiosity: {@code CodedValue=""} and {@code Alias/@Name=" "} both occur in real
 * hand-edited define.xml files, and the engine's convention is that presence is a separate rule's
 * obligation — a CT kind must not turn an empty value into a "term not in the codelist" finding,
 * because the reviewer would then chase a controlled-terminology problem that is really a missing
 * value. Dropping any of these blank filters produces exactly that fabricated finding, and nothing
 * in the suite noticed until now.
 * </p>
 */
class CtBlankValueEdgesTest
{

    private static final String BLANKS = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1">
              <CodeList OID="CL.SEX" Name="Sex" def:StandardOID="STD.CT">
                <Alias Context="nci:ExtCodeID" Name="C66731"/>
                <EnumeratedItem CodedValue="F">
                  <Alias Context="nci:ExtCodeID" Name="C16576"/>
                </EnumeratedItem>
                <EnumeratedItem CodedValue=" "/>
              </CodeList>
              <CodeList OID="CL.BLANKALIAS" Name="Unit" def:StandardOID="STD.CT">
                <Alias Context="nci:ExtCodeID" Name=" "/>
              </CodeList>
            </ODM>
            """;

    private static DocumentContext context()
    {
        try
        {
            ElementNode root = ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(BLANKS.getBytes(UTF_8))));
            return new DocumentContext(root, "2.1", new StubCtProvider(), null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static List<String> values(String aElement, String aCheckBody)
    {
        String yaml = """
                Rule_Id: "PMDA-BLANK01"
                Sheet_Rule_Identifier: "BLANK01"
                Rule_Set: "PMDA"
                Element: "%s"
                Applicable_Versions: ["2.1"]
                Severity: "Error"
                Requires: "ct"
                Plain_Text_Rule: "Test rule."
                Message: "Offending value [${value}]."
                Check:
                """.formatted(aElement) + aCheckBody.indent(2);
        RuleResult result = new RuleEvaluator().evaluate(RuleRepository.parse(yaml, "test"),
                context());
        return result.findings().stream()
                .map(f -> f.getMessage().replace("Offending value [", "").replace("].", ""))
                .toList();
    }


    @Test
    void aBlankCodedValueIsNotATermInTheCodelistFinding()
    {
        assertEquals(List.of(), values("EnumeratedItem", """
                kind: "term_in_ct_codelist"
                """));
    }


    @Test
    void aBlankCodedValueIsNotAnUnmarkedExtendedValue()
    {
        // Unit (C71620) is the extensible codelist, so a non-member item there WOULD fire.
        assertEquals(List.of(), values("EnumeratedItem", """
                kind: "extended_value_marking"
                mode: "required"
                """));
    }


    @Test
    void aBlankAliasNameIsNotAnUnknownNciCode()
    {
        // C16576 is a TERM c-code, so it correctly fails a codelist-level lookup; the blank
        // alias name contributes nothing. Without the blank filter a bare " " joins that list.
        assertEquals(List.of("C16576"), values("Alias", """
                kind: "nci_code_known"
                level: "codelist"
                """));
    }


    @Test
    void aBlankAliasNameDoesNotSatisfyTheAliasRequirement()
    {
        // CL.BLANKALIAS's only nci:ExtCodeID alias carries no c-code, so the codelist still has
        // none -- the rule must fire. Treating " " as a c-code would silence it.
        assertEquals(List.of("Unit"), values("CodeList", """
                kind: "nci_alias_required"
                level: "codelist"
                """));
    }


    @Test
    void aBlankCodedValueIsNotACodeToTermMismatch()
    {
        assertEquals(List.of(), values("EnumeratedItem", """
                kind: "term_matches_nci_code"
                """));
    }

}
