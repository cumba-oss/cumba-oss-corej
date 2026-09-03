package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import org.junit.jupiter.api.Test;

class CheckConditionTransformerTest
{

    // ---- Null / edge cases ----

    @Test
    void resolvePrefixes_nullPrefix_returnsUnchanged()
    {
        CheckConditionLeaf leaf = leaf("--STDTC", "empty");
        CheckCondition result = CheckConditionTransformer.resolvePrefixes(leaf, null);
        // null prefix => return condition unchanged
        assertSame(leaf, result);
    }


    @Test
    void resolvePrefixes_nonTwoCharPrefix_stillApplies()
    {
        CheckConditionLeaf leaf = leaf("--STDTC", "empty");
        CheckCondition result = CheckConditionTransformer.resolvePrefixes(leaf, "AEX");
        // Prefix is applied even if not 2 chars (just warns)
        CheckConditionLeaf r = assertInstanceOf(CheckConditionLeaf.class, result);
        assertEquals("AEXSTDTC", r.getName());
    }

    // ---- Leaf name resolution ----


    @Test
    void resolvePrefixes_leafName_dashDash()
    {
        CheckConditionLeaf leaf = leaf("--STDTC", "empty");
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("AESTDTC", r.getName());
    }


    @Test
    void resolvePrefixes_leafName_noDashDash_unchanged()
    {
        CheckConditionLeaf leaf = leaf("STUDYID", "var_exists");
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("STUDYID", r.getName());
    }


    @Test
    void resolvePrefixes_leafName_nullName_unchanged()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name(null).operator("var_exists")
                .build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertNull(r.getName());
    }

    // ---- Leaf value resolution ----


    @Test
    void resolvePrefixes_leafValue_doubleStar()
    {
        // Fix #5: `**` in a dot-qualified value is preserved so that RelrecExpandedLookup can
        // resolve
        // it per-row against the paired parent domain (different FA rows may pair to AE or CM).
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("IDVAR").operator("equal_to")
                .value(new TextNode("RELREC.**DECOD")).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("RELREC.**DECOD", r.getValue().asText());
    }


    @Test
    void resolvePrefixes_leafValue_plainDoubleStar_stillResolved()
    {
        // Non-dot-qualified `**` still resolves to the primary prefix.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("COL").operator("equal_to")
                .value(new TextNode("**DECOD")).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("AEDECOD", r.getValue().asText());
    }


    @Test
    void resolvePrefixes_leafValue_dashDashPrefix()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("COL").operator("equal_to")
                .value(new TextNode("--CLASCD")).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "CM");
        assertEquals("CMCLASCD", r.getValue().asText());
    }


    @Test
    void resolvePrefixes_leafValue_dotQualifiedDashDash()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("COL").operator("equal_to")
                .value(new TextNode("SUPP--.QNAM")).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("SUPPAE.QNAM", r.getValue().asText());
    }


    @Test
    void resolvePrefixes_leafValue_nonTextual_unchanged()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("COL").operator("equal_to")
                .value(new IntNode(42)).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals(42, r.getValue().asInt());
    }


    @Test
    void resolvePrefixes_leafValue_null_unchanged()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--DTC").operator("empty")
                .value(null).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("AEDTC", r.getName());
        assertNull(r.getValue());
    }


    @Test
    void resolvePrefixes_leafValue_noWildcard_unchanged()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("COL").operator("equal_to")
                .value(new TextNode("PLAIN")).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("PLAIN", r.getValue().asText());
    }

    // ---- Identity optimization ----


    @Test
    void resolvePrefixes_noChange_returnsSameInstance()
    {
        CheckConditionLeaf leaf = leaf("STUDYID", "var_exists");
        CheckCondition result = CheckConditionTransformer.resolvePrefixes(leaf, "AE");
        // No wildcards → same object returned
        assertSame(leaf, result);
    }

    // ---- Composite conditions ----


    @Test
    void resolvePrefixes_allComposite()
    {
        CheckConditionAll all = new CheckConditionAll(
                List.of(leaf("--STDTC", "empty"), leaf("STUDYID", "var_exists")));
        CheckConditionAll r = (CheckConditionAll) CheckConditionTransformer.resolvePrefixes(all,
                "AE");
        CheckConditionLeaf first = (CheckConditionLeaf) r.getConditions().get(0);
        CheckConditionLeaf second = (CheckConditionLeaf) r.getConditions().get(1);
        assertEquals("AESTDTC", first.getName());
        assertEquals("STUDYID", second.getName());
    }


    @Test
    void resolvePrefixes_anyComposite()
    {
        CheckConditionAny any = new CheckConditionAny(
                List.of(leaf("--TERM", "empty"), leaf("--SEQ", "var_exists")));
        CheckConditionAny r = (CheckConditionAny) CheckConditionTransformer.resolvePrefixes(any,
                "CM");
        CheckConditionLeaf first = (CheckConditionLeaf) r.getConditions().get(0);
        CheckConditionLeaf second = (CheckConditionLeaf) r.getConditions().get(1);
        assertEquals("CMTERM", first.getName());
        assertEquals("CMSEQ", second.getName());
    }


    @Test
    void resolvePrefixes_notComposite()
    {
        CheckConditionNot not = new CheckConditionNot(leaf("--DTC", "empty"));
        CheckConditionNot r = (CheckConditionNot) CheckConditionTransformer.resolvePrefixes(not,
                "AE");
        CheckConditionLeaf inner = (CheckConditionLeaf) r.getCondition();
        assertEquals("AEDTC", inner.getName());
    }


    @Test
    void resolvePrefixes_deeplyNested()
    {
        CheckCondition tree = new CheckConditionAll(List.of(
                new CheckConditionNot(new CheckConditionAny(
                        List.of(leaf("--SEQ", "var_exists"), leaf("--TERM", "empty")))),
                leaf("--STDTC", "non_empty")));
        CheckConditionAll r = (CheckConditionAll) CheckConditionTransformer.resolvePrefixes(tree,
                "MH");

        CheckConditionNot not = (CheckConditionNot) r.getConditions().get(0);
        CheckConditionAny any = (CheckConditionAny) not.getCondition();
        assertEquals("MHSEQ", ((CheckConditionLeaf) any.getConditions().get(0)).getName());
        assertEquals("MHTERM", ((CheckConditionLeaf) any.getConditions().get(1)).getName());
        assertEquals("MHSTDTC", ((CheckConditionLeaf) r.getConditions().get(1)).getName());
    }

    // ---- Leaf modifiers preserved ----


    @Test
    void resolvePrefixes_preservesLeafModifiers()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--DTC")
                .operator("matches_regex").value(new TextNode("\\d{4}-\\d{2}-\\d{2}"))
                .valueIsLiteral(true).prefix(4).suffix(2).within(new TextNode("USUBJID"))
                .ordering("--SEQ").regex("(.+)").typeInsensitive(true).negative(true).build();
        CheckConditionLeaf r = (CheckConditionLeaf) CheckConditionTransformer.resolvePrefixes(leaf,
                "AE");
        assertEquals("AEDTC", r.getName());
        assertEquals(true, r.getValueIsLiteral());
        assertEquals(4, r.getPrefix());
        assertEquals(2, r.getSuffix());
        assertEquals(java.util.List.of("USUBJID"), r.getWithinColumns());
        assertEquals("--SEQ", r.getOrdering()); // ordering is a string, not a variable name — not
                                                // resolved
        assertEquals("(.+)", r.getRegex());
        assertEquals(true, r.getTypeInsensitive());
        assertEquals(true, r.getNegative());
    }

    // ---- Package-private resolveWildcard ----


    @Test
    void resolveWildcard_nullName()
    {
        assertNull(CheckConditionTransformer.resolveWildcard(null, "AE"));
    }


    @Test
    void resolveWildcard_noPrefix()
    {
        assertEquals("STUDYID", CheckConditionTransformer.resolveWildcard("STUDYID", "AE"));
    }


    @Test
    void resolveWildcard_withPrefix()
    {
        assertEquals("AESTDTC", CheckConditionTransformer.resolveWildcard("--STDTC", "AE"));
    }

    // ---- Helper ----


    private static CheckConditionLeaf leaf(String name, String operator)
    {
        return CheckConditionLeaf.builder().name(name).operator(operator).build();
    }

}
