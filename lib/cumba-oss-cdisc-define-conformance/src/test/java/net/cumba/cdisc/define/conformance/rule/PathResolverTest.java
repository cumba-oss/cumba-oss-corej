package net.cumba.cdisc.define.conformance.rule;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/** {@link PathResolver}: the {@code ..} / child / {@code @Attr} navigation grammar. */
class PathResolverTest
{

    private static final String XML = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1">
              <ItemDef OID="IT.1">
                <def:Origin Type="Collected"/>
                <def:Origin Type="Derived"/>
                <Description>
                  <TranslatedText>Study Identifier</TranslatedText>
                  <TranslatedText/>
                </Description>
              </ItemDef>
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
    void childStepsCollectAllMatchesInOrder()
    {
        ElementNode root = root();
        List<ElementNode> origins = PathResolver.nodes(root, "ItemDef/Origin");
        assertEquals(2, origins.size());
        assertEquals("Collected", origins.get(0).attribute("Type").orElseThrow());
        assertEquals("Derived", origins.get(1).attribute("Type").orElseThrow());
    }


    @Test
    void defPrefixIsStrippedFromSteps()
    {
        ElementNode root = root();
        assertEquals(2, PathResolver.nodes(root, "ItemDef/def:Origin").size());
        assertEquals(List.of("Collected", "Derived"),
                PathResolver.values(root, "ItemDef/def:Origin/@Type"));
    }


    @Test
    void parentStepNavigatesUpwards()
    {
        ElementNode itemDef = root().children("ItemDef").get(0);
        ElementNode origin = itemDef.children("Origin").get(0);
        assertEquals(List.of(itemDef), PathResolver.nodes(origin, ".."));
        // Sibling access: up, then down to all Origin children.
        assertEquals(List.of("Collected", "Derived"),
                PathResolver.values(origin, "../Origin/@Type"));
    }


    @Test
    void parentStepOnRootResolvesToNothing()
    {
        assertTrue(PathResolver.nodes(root(), "..").isEmpty());
    }


    @Test
    void attributeValuesSkipAbsentAttributes()
    {
        ElementNode root = root();
        assertEquals(List.of("IT.1"), PathResolver.values(root, "ItemDef/@OID"));
        // Origin elements have no OID attribute — they contribute nothing.
        assertEquals(List.of(), PathResolver.values(root, "ItemDef/Origin/@OID"));
        // A def:-prefixed attribute step is stripped too.
        assertEquals(List.of("Collected", "Derived"),
                PathResolver.values(root, "ItemDef/Origin/@def:Type"));
    }


    @Test
    void nonAttributeTerminalStepFallsBackToText()
    {
        ElementNode root = root();
        // Two TranslatedText elements, one empty — only the text-bearing one contributes.
        assertEquals(List.of("Study Identifier"),
                PathResolver.values(root, "ItemDef/Description/TranslatedText"));
        // Elements without text contribute nothing.
        assertEquals(List.of(), PathResolver.values(root, "ItemDef/Description"));
    }


    @Test
    void unknownStepsResolveToEmpty()
    {
        ElementNode root = root();
        assertTrue(PathResolver.nodes(root, "NoSuch/Child").isEmpty());
        assertTrue(PathResolver.values(root, "NoSuch/@OID").isEmpty());
    }


    @Test
    void stripPrefixLeavesBareNamesAlone()
    {
        assertEquals("Standard", PathResolver.stripPrefix("def:Standard"));
        assertEquals("Standard", PathResolver.stripPrefix("Standard"));
    }

}
