package net.cumba.corej.define.conformance.eval;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.ct.CtCodelist;
import net.cumba.corej.define.conformance.ct.CtProvider;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/** {@link DocumentContext} wiring and the {@link OidResolver} reference index. */
class DocumentContextTest
{

    private static final String XML = """
            <ODM FileOID="F1">
              <ItemGroupDef OID="IG.DM" Name="DM">
                <ItemRef ItemOID="IT.1"/>
              </ItemGroupDef>
              <ItemDef OID="IT.1" Name="STUDYID"/>
            </ODM>
            """;

    private static ElementNode root()
    {
        try
        {
            return ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(XML.getBytes(UTF_8))));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    @Test
    void documentNodeWrapsTheRootWithoutAdoptingIt()
    {
        ElementNode root = root();
        DocumentContext context = new DocumentContext(root, "2.1", null, null);
        assertEquals(root, context.root());
        assertEquals(DocumentContext.DOCUMENT_SCOPE, context.documentNode().localName());
        assertEquals(List.of(root), context.documentNode().children());
        assertTrue(root.parent().isEmpty());
    }


    @Test
    void allNodesIncludesTheSyntheticDocumentNodeDepthFirst()
    {
        DocumentContext context = new DocumentContext(root(), "2.1", null, null);
        List<ElementNode> all = context.allNodes();
        assertEquals(context.documentNode(), all.get(0));
        assertEquals(List.of("Document", "ODM", "ItemGroupDef", "ItemRef", "ItemDef"),
                all.stream().map(ElementNode::localName).toList());
    }


    @Test
    void oidResolverResolvesByElementKeyAndValue()
    {
        DocumentContext context = new DocumentContext(root(), "2.1", null, null);
        OidResolver resolver = context.oidResolver();
        ElementNode hit = resolver.resolve("ItemDef", "OID", "IT.1").orElseThrow();
        assertEquals("ItemDef", hit.localName());
        assertEquals("STUDYID", hit.attribute("Name").orElseThrow());
        // Non-OID keys are indexed too.
        assertTrue(resolver.resolve("ItemGroupDef", "Name", "DM").isPresent());
    }


    @Test
    void oidResolverMissesOnElementKeyOrValue()
    {
        OidResolver resolver = new DocumentContext(root(), "2.1", null, null).oidResolver();
        assertTrue(resolver.resolve("NoSuchElement", "OID", "IT.1").isEmpty());
        assertTrue(resolver.resolve("ItemDef", "NoSuchKey", "IT.1").isEmpty());
        assertTrue(resolver.resolve("ItemDef", "OID", "NO.SUCH.VALUE").isEmpty());
    }


    @Test
    void optionalInputsAreExposedWhenSupplied()
    {
        DocumentContext bare = new DocumentContext(root(), "2.0", null, null);
        assertEquals("2.0", bare.defineVersion());
        assertTrue(bare.ctProvider().isEmpty());
        assertTrue(bare.submissionFolder().isEmpty());

        CtCodelist codelist = new CtCodelist("C66731", true, Map.of("YES", "C12345"));
        CtProvider provider = cCode -> "C66731".equals(cCode) ? Optional.of(codelist)
                : Optional.empty();
        Path folder = Path.of("submission");
        DocumentContext full = new DocumentContext(root(), "2.1", provider, folder);
        assertEquals(provider, full.ctProvider().orElseThrow());
        assertEquals(folder, full.submissionFolder().orElseThrow());

        // The CtCodelist record carries its fields verbatim.
        assertEquals("C66731", codelist.cCode());
        assertTrue(codelist.extensible());
        assertEquals("C12345", codelist.termsBySubmissionValue().get("YES"));
        assertFalse(full.ctProvider().orElseThrow().codelistByCCode("C99999").isPresent());
    }

}
