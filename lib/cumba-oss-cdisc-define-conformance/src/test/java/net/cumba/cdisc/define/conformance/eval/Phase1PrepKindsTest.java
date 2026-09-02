package net.cumba.cdisc.define.conformance.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.RuleRepository;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/** The Phase-1 prep DSL extensions: {@code is_referenced} and the {@code matchesRegex} guard. */
class Phase1PrepKindsTest
{

    private static DocumentContext context(String aXml)
    {
        try (var in = new ByteArrayInputStream(aXml.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1", null,
                    null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static final String DOC = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
              <MetaDataVersion OID="MDV.1">
                <ItemGroupDef OID="IG.DM">
                  <ItemRef ItemOID="IT.USED" MethodOID="MT.USED"/>
                </ItemGroupDef>
                <ItemDef OID="IT.USED" Name="AEDY"/>
                <ItemDef OID="IT.ORPHAN" Name="AETERM"/>
                <MethodDef OID="MT.USED"/>
                <MethodDef OID="MT.ORPHAN"/>
              </MetaDataVersion>
            </ODM>
            """;

    @Test
    void isReferencedFlagsOrphansOnly()
    {
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "PMDA-DD0080"
                Sheet_Rule_Identifier: "DD0080"
                Rule_Set: "PMDA"
                Element: "MethodDef"
                Applicable_Versions: ["2.0", "2.1"]
                Severity: "Error"
                Plain_Text_Rule: "Methods must be referenced."
                Message: "Method ${value} is not referenced."
                Check:
                  kind: "is_referenced"
                  by:
                  - element: "ItemRef"
                    attribute: "MethodOID"
                """, "test");
        RuleResult result = new RuleEvaluator().evaluate(rule, context(DOC));
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).getMessage().contains("MT.ORPHAN"));
    }


    @Test
    void isReferencedAnyElementDescriptor()
    {
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "PMDA-DD0067"
                Sheet_Rule_Identifier: "DD0067"
                Rule_Set: "PMDA"
                Element: "ItemDef"
                Applicable_Versions: ["2.1"]
                Severity: "Warning"
                Plain_Text_Rule: "Variables must be referenced."
                Message: "Variable ${value} is not referenced."
                Check:
                  kind: "is_referenced"
                  by:
                  - attribute: "ItemOID"
                """, "test");
        RuleResult result = new RuleEvaluator().evaluate(rule, context(DOC));
        assertEquals(List.of("Variable IT.ORPHAN is not referenced."),
                result.findings().stream().map(f -> f.getMessage()).toList());
    }


    @Test
    void isReferencedRequiresByList()
    {
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse("""
                Rule_Id: "X"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "PMDA"
                Element: "MethodDef"
                Applicable_Versions: ["2.1"]
                Plain_Text_Rule: "x"
                Message: "x"
                Check:
                  kind: "is_referenced"
                  by: []
                """, "test"));
    }


    @Test
    void matchesRegexGuardSelectsByNamePattern()
    {
        // DD0105 shape: ItemDefs whose Name ends in DY must satisfy some check — here we assert
        // the guard alone by requiring a never-present attribute, so exactly the DY-named
        // ItemDef fires.
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "PMDA-DD0105"
                Sheet_Rule_Identifier: "DD0105"
                Rule_Set: "PMDA"
                Element: "ItemDef"
                Applicable_Versions: ["2.1"]
                Severity: "Error"
                Plain_Text_Rule: "Study-day variables must declare a derived origin."
                Message: "Origin missing on a study-day variable."
                Check:
                  kind: "exists"
                  target: "Origin"
                  when:
                    path: "@Name"
                    matchesRegex: ".*DY"
                """, "test");
        RuleResult result = new RuleEvaluator().evaluate(rule, context(DOC));
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).getXpath().contains("IT.USED"));
    }


    @Test
    void existsGuardTreatsBlankAttributeAsMissing()
    {
        // review-batch-b N3: a present-but-blank attribute must count as missing in
        // exists-guards, mirroring the check kinds' presence semantics.
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "X-BLANK-GUARD"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "CDISC"
                Element: "ItemRef"
                Applicable_Versions: ["2.1"]
                Source_Type: "Specification"
                Plain_Text_Rule: "x"
                Message: "fires when MethodOID is absent-or-blank"
                Check:
                  kind: "exists"
                  target: "@ItemOID"
                  when:
                    path: "@MethodOID"
                    exists: false
                """, "test");
        String doc = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
                  <ItemGroupDef OID="IG.X">
                    <ItemRef MethodOID=""/>
                    <ItemRef MethodOID="MT.1" ItemOID="IT.1"/>
                  </ItemGroupDef>
                </ODM>
                """;
        RuleResult result = new RuleEvaluator().evaluate(rule, context(doc));
        // Only the blank-MethodOID ItemRef passes the guard, and its ItemOID is missing.
        assertEquals(1, result.findings().size());
    }


    @Test
    void comparePlainAndDerefWithBasename()
    {
        // DD0052 shape: SASDatasetName must equal the basename of the leaf href the
        // ArchiveLocationID points at.
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "PMDA-DD0052"
                Sheet_Rule_Identifier: "DD0052"
                Rule_Set: "PMDA"
                Element: "ItemGroupDef"
                Applicable_Versions: ["2.1"]
                Severity: "Error"
                Plain_Text_Rule: "SASDatasetName and the archive file name must match."
                Message: "SASDatasetName/ArchiveLocation mismatch: ${value}."
                Check:
                  kind: "compare"
                  left: "@SASDatasetName"
                  right: "@ArchiveLocationID->leaf@ID/@href"
                  caseInsensitive: true
                  rightTransform: "file-basename"
                """, "test");
        String doc = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
                  <MetaDataVersion OID="MDV.1">
                    <ItemGroupDef OID="IG.DM" SASDatasetName="DM" ArchiveLocationID="LF.DM">
                      <leaf ID="LF.DM" href="dm.xpt"/>
                    </ItemGroupDef>
                    <ItemGroupDef OID="IG.AE" SASDatasetName="AE" ArchiveLocationID="LF.AE">
                      <leaf ID="LF.AE" href="tabulations/wrong.xpt"/>
                    </ItemGroupDef>
                    <ItemGroupDef OID="IG.VS" SASDatasetName="VS"/>
                  </MetaDataVersion>
                </ODM>
                """;
        RuleResult result = new RuleEvaluator().evaluate(rule, context(doc));
        // IG.DM matches (dm ~ DM case-insensitive), IG.AE mismatches, IG.VS skips (no right).
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).getMessage().contains("AE vs wrong"));
    }


    @Test
    void compareLessOrEqualNumeric()
    {
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "X-LEN"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "CDISC"
                Element: "ItemRef"
                Applicable_Versions: ["2.1"]
                Source_Type: "Specification"
                Plain_Text_Rule: "value-level length must not exceed the variable length"
                Message: "length exceeds parent: ${value}"
                Check:
                  kind: "compare"
                  left: "@ItemOID->ItemDef@OID/@Length"
                  right: "@ParentLength"
                  op: "less_or_equal"
                """, "test");
        String doc = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
                  <MetaDataVersion OID="MDV.1">
                    <ItemRef ItemOID="IT.OK" ParentLength="20"/>
                    <ItemRef ItemOID="IT.LONG" ParentLength="8"/>
                    <ItemRef ItemOID="IT.NONNUM" ParentLength="abc"/>
                    <ItemDef OID="IT.OK" Length="10"/>
                    <ItemDef OID="IT.LONG" Length="12"/>
                    <ItemDef OID="IT.NONNUM" Length="5"/>
                  </MetaDataVersion>
                </ODM>
                """;
        RuleResult result = new RuleEvaluator().evaluate(rule, context(doc));
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).getMessage().contains("12 vs 8"));
    }


    @Test
    void compareRejectsUnknownOpAndTransformAtLoad()
    {
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse("""
                Rule_Id: "X"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "CDISC"
                Element: "ItemGroupDef"
                Applicable_Versions: ["2.1"]
                Source_Type: "Schema"
                Plain_Text_Rule: "x"
                Message: "x"
                Check:
                  kind: "compare"
                  left: "@A"
                  right: "@B"
                  op: "greater_than"
                """, "test"));
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse("""
                Rule_Id: "X"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "CDISC"
                Element: "ItemGroupDef"
                Applicable_Versions: ["2.1"]
                Source_Type: "Schema"
                Plain_Text_Rule: "x"
                Message: "x"
                Check:
                  kind: "compare"
                  left: "@A"
                  right: "@B"
                  leftTransform: "uppercase"
                """, "test"));
    }


    @Test
    void whenGuardDereferencesOidValuedAttributes()
    {
        // Batch-C gap (105/106/251/252 shape): guard on the referenced def:Standard's Name.
        ConformanceRule rule = RuleRepository.parse("""
                Rule_Id: "X-DEREF-GUARD"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "CDISC"
                Element: "ItemGroupDef"
                Applicable_Versions: ["2.1"]
                Source_Type: "Specification"
                Plain_Text_Rule: "SDTM datasets must carry Domain."
                Message: "Domain missing on an SDTM dataset."
                Check:
                  kind: "exists"
                  target: "@Domain"
                  when:
                    path: "@StandardOID->Standard@OID/@Name"
                    matchesRegex: "SDTMIG|SENDIG"
                """, "test");
        String doc = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
                  <MetaDataVersion OID="MDV.1">
                    <Standard OID="STD.SDTM" Name="SDTMIG"/>
                    <Standard OID="STD.ADAM" Name="ADaMIG"/>
                    <ItemGroupDef OID="IG.BAD" StandardOID="STD.SDTM"/>
                    <ItemGroupDef OID="IG.OK" StandardOID="STD.SDTM" Domain="DM"/>
                    <ItemGroupDef OID="IG.ADAM" StandardOID="STD.ADAM"/>
                    <ItemGroupDef OID="IG.NOSTD"/>
                  </MetaDataVersion>
                </ODM>
                """;
        RuleResult result = new RuleEvaluator().evaluate(rule, context(doc));
        // Only IG.BAD: SDTM-bound and Domain-less. ADaM and standard-less IGDs pass the guard
        // negatively (deref yields no matching value).
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).getXpath().contains("IG.BAD"));
    }


    @Test
    void matchesRegexGuardRejectsBadPatternAtLoad()
    {
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse("""
                Rule_Id: "X"
                Sheet_Rule_Identifier: "X"
                Rule_Set: "PMDA"
                Element: "ItemDef"
                Applicable_Versions: ["2.1"]
                Plain_Text_Rule: "x"
                Message: "x"
                Check:
                  kind: "exists"
                  target: "Origin"
                  when:
                    path: "@Name"
                    matchesRegex: "["
                """, "test"));
    }

}
