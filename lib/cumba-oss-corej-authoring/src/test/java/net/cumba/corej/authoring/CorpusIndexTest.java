package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import net.cumba.corej.authoring.CorpusIndex.ReferenceKey;
import net.cumba.corej.authoring.RuleInsight.RelatedRule;
import org.junit.jupiter.api.Test;

class CorpusIndexTest
{

    private static JsonNode check(String aId, String aOrigin, String aRefId) throws Exception
    {
        return AuthoredRuleAssembler.readYamlTree("""
                Core:
                  Id: "%s"
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "%s"
                      Rule_Identifier:
                        Id: "%s"
                        Version: "1"
                """.formatted(aId, aOrigin, aRefId));
    }


    @Test
    void rulesSharingAReferenceAreRelatedAcrossFamilies() throws Exception
    {
        CorpusIndex index = CorpusIndex.of(Map.of(//
                "FDA-CT2001", check("FDA-CT2001", "FDA Business Rules", "CT2001"), //
                "PMDA-CT2001", check("PMDA-CT2001", "FDA Business Rules", "CT2001"), //
                "CDISC-CG0001", check("CDISC-CG0001", "SDTM Conformance Rules", "CG0001")));
        assertEquals(3, index.size());
        ReferenceKey key = new ReferenceKey("FDA Business Rules", "CT2001");
        assertEquals(List.of(key), index.referencesOf("FDA-CT2001"));
        assertEquals(List.of("FDA Business Rules CT2001"),
                index.referencesOf("FDA-CT2001").stream().map(ReferenceKey::toString).toList());
        List<RelatedRule> related = RuleInsight.related("FDA-CT2001",
                index.referencesOf("FDA-CT2001"), index);
        assertEquals(List.of(
                new RelatedRule("PMDA-CT2001", "shared-reference", "FDA Business Rules CT2001")),
                related);
        assertEquals(List.of(),
                RuleInsight.related("CDISC-CG0001", index.referencesOf("CDISC-CG0001"), index));
    }


    @Test
    void theRegistersAddEquivalenceRelationAndParentage() throws Exception
    {
        CorpusIndex index = CorpusIndex.of(Map.of("FDA-SD0047", check("FDA-SD0047", "o", "a"),
                "PMDA-SD0047", check("PMDA-SD0047", "o", "b"), "CDISC-CG0425",
                check("CDISC-CG0425", "o", "c"), "CDISC-CG9672", check("CDISC-CG9672", "o", "d")))
                .withEquivalenceRegister("""
                        classes:
                        - id: EQ-0002
                          kind: cross-family
                          members:
                          - FDA-SD0047
                          - PMDA-SD0047
                        relations:
                        - id: REL-0001
                          members:
                          - FDA-SD0047
                          - CDISC-CG0425
                          relation: one-to-many
                        """).withParentage(
                        """
                                # child\tparent\tkind\tref\tdate\tissue\treason
                                CDISC-CG9672\tCDISC-CG0425\tabsent-column-sister\tR-4.11c\t2026-09-07\t#172\tthe parent gates on the absence
                                """);
        List<RelatedRule> fda = RuleInsight.related("FDA-SD0047", index.referencesOf("FDA-SD0047"),
                index);
        assertTrue(
                fda.contains(new RelatedRule("PMDA-SD0047", "equivalence", "EQ-0002 cross-family")),
                fda.toString());
        assertTrue(fda.contains(new RelatedRule("CDISC-CG0425", "related", "REL-0001 one-to-many")),
                fda.toString());
        List<RelatedRule> child = RuleInsight.related("CDISC-CG9672",
                index.referencesOf("CDISC-CG9672"), index);
        assertEquals(List.of(
                new RelatedRule("CDISC-CG0425", "minted-from", "the parent gates on the absence")),
                child);
        List<RelatedRule> parent = RuleInsight.related("CDISC-CG0425",
                index.referencesOf("CDISC-CG0425"), index);
        assertTrue(parent.contains(new RelatedRule("CDISC-CG9672", "minted-sister",
                "the parent gates on the absence")), parent.toString());
        assertTrue(
                parent.contains(new RelatedRule("FDA-SD0047", "related", "REL-0001 one-to-many")),
                parent.toString());
    }


    @Test
    void anUnknownRuleHasNothing() throws Exception
    {
        CorpusIndex index = CorpusIndex.of(Map.of("X", check("X", "o", "a")));
        assertEquals(List.of(), index.referencesOf("Y"));
        assertEquals(List.of(), RuleInsight.related("Y", List.of(), index));
    }
}
