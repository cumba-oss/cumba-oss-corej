package net.cumba.cdisc.define.conformance.rule;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/** {@link Condition}: structural validation and the all/any/not/leaf-clause semantics. */
class ConditionTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private static final String XML = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1">
              <ItemDef OID="IT.1" DataType="text">
                <def:Origin Type="Collected"/>
              </ItemDef>
            </ODM>
            """;

    private static Condition condition(String aYaml)
    {
        try
        {
            return MAPPER.readValue(aYaml, Condition.class);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse condition YAML", e);
        }
    }


    private static ElementNode itemDef()
    {
        try
        {
            ElementNode root = ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(XML.getBytes(UTF_8))));
            return root.children("ItemDef").get(0);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }

    // ------------------------------------------------------------------
    // validate()
    // ------------------------------------------------------------------


    @Test
    void validateRejectsZeroForms()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> condition("{}").validate());
        assertTrue(e.getMessage().contains("got 0 forms"), e.getMessage());
    }


    @Test
    void validateRejectsMultipleForms()
    {
        Condition c = condition("""
                path: "@Type"
                exists: true
                not:
                  path: "@Type"
                  exists: true
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(e.getMessage().contains("got 2 forms"), e.getMessage());
    }


    @Test
    void validateRejectsPathWithoutPredicate()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> condition("path: \"@Type\"").validate());
        assertTrue(e.getMessage().contains("exactly one of equals/oneOf/exists"), e.getMessage());
    }


    @Test
    void validateRejectsPathWithMultiplePredicates()
    {
        Condition c = condition("""
                path: "@Type"
                equals: "Collected"
                exists: true
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(e.getMessage().contains("got 2"), e.getMessage());
    }


    @Test
    void validateRejectsPredicateWithoutPath()
    {
        Condition c = condition("""
                not:
                  path: "@Type"
                  exists: true
                equals: "Collected"
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(e.getMessage().contains("only valid together with a path"), e.getMessage());
    }


    @Test
    void validateRecursesIntoAllAnyAndNot()
    {
        assertThrows(IllegalStateException.class, () -> condition("""
                all:
                  - path: "@Type"
                """).validate());
        assertThrows(IllegalStateException.class, () -> condition("""
                any:
                  - path: "@Type"
                """).validate());
        assertThrows(IllegalStateException.class, () -> condition("""
                not:
                  path: "@Type"
                """).validate());
    }


    @Test
    void validateAcceptsWellFormedNestedConditions()
    {
        assertDoesNotThrow(() -> condition("""
                all:
                  - path: "@DataType"
                    equals: "text"
                  - any:
                      - path: "Origin/@Type"
                        oneOf: ["Collected", "Derived"]
                      - not:
                          path: "@OID"
                          exists: true
                """).validate());
    }

    // ------------------------------------------------------------------
    // matches()
    // ------------------------------------------------------------------


    @Test
    void equalsMatchesWhenAnyResolvedValueMatches()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                path: "Origin/@Type"
                equals: "Collected"
                """).matches(node));
        assertFalse(condition("""
                path: "Origin/@Type"
                equals: "Derived"
                """).matches(node));
    }


    @Test
    void oneOfMatchesAgainstTheAllowedSet()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                path: "@DataType"
                oneOf: ["text", "integer"]
                """).matches(node));
        assertFalse(condition("""
                path: "@DataType"
                oneOf: ["float", "integer"]
                """).matches(node));
    }


    @Test
    void existsTrueOnAttributePath()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                path: "@OID"
                exists: true
                """).matches(node));
        assertFalse(condition("""
                path: "@Missing"
                exists: true
                """).matches(node));
    }


    @Test
    void existsFalseOnAttributePath()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                path: "@Missing"
                exists: false
                """).matches(node));
        assertFalse(condition("""
                path: "@OID"
                exists: false
                """).matches(node));
    }


    @Test
    void existsOnElementPathChecksNodePresence()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                path: "Origin"
                exists: true
                """).matches(node));
        assertFalse(condition("""
                path: "CodeListRef"
                exists: true
                """).matches(node));
        assertTrue(condition("""
                path: "CodeListRef"
                exists: false
                """).matches(node));
    }


    @Test
    void allRequiresEveryConditionToMatch()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                all:
                  - path: "@DataType"
                    equals: "text"
                  - path: "Origin/@Type"
                    equals: "Collected"
                """).matches(node));
        assertFalse(condition("""
                all:
                  - path: "@DataType"
                    equals: "text"
                  - path: "Origin/@Type"
                    equals: "Derived"
                """).matches(node));
    }


    @Test
    void anyRequiresAtLeastOneConditionToMatch()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                any:
                  - path: "@DataType"
                    equals: "float"
                  - path: "Origin/@Type"
                    equals: "Collected"
                """).matches(node));
        assertFalse(condition("""
                any:
                  - path: "@DataType"
                    equals: "float"
                  - path: "Origin/@Type"
                    equals: "Derived"
                """).matches(node));
    }


    @Test
    void notInvertsItsCondition()
    {
        ElementNode node = itemDef();
        assertTrue(condition("""
                not:
                  path: "@DataType"
                  equals: "float"
                """).matches(node));
        assertFalse(condition("""
                not:
                  path: "@DataType"
                  equals: "text"
                """).matches(node));
    }

}
