package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan C phase 4 — the level-keyed {@code Check} grammar (§3.3) and its three load gates.
 *
 * <p>
 * ⚑ The gates are asserted through {@code RulePackageLoader.validateEnumFields}, not through a
 * Jackson exception, on purpose: a grammar violation has to name the <b>rule</b>, and only the
 * loader knows which rule it was. That is the {@code rawSeverity} arrangement, reused.
 * </p>
 */
class RuleCheckLevelsLoadTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static Rule parse(String aBody) throws IOException
    {
        return YAML.readValue("""
                Core:
                  Id: "T-0001"
                Description: "Raise an error when something is wrong."
                """ + aBody, Rule.class);
    }


    /** A Check rendered to its expression form — the null-insensitive shape comparison. */
    private static String rendered(net.cumba.corej.core.model.@Nullable CheckCondition aCondition)
    {
        assertNotNull(aCondition);
        return net.cumba.corej.core.expr.ExpressionPrinter
                .print(net.cumba.corej.core.expr.CheckToExpr.toExpr(aCondition));
    }


    private static Rule loaded(String aBody) throws IOException
    {
        Rule rule = parse(aBody);
        RulePackageLoader.validateEnumFields(rule);
        return rule;
    }

    // ------------------------------------------------------------------ the plain shape


    @Test
    @DisplayName("a plain Check: is still a plain Check — one level, at the rule's Severity")
    void plainCheckIsOneLevelAtTheRuleSeverity() throws IOException
    {
        Rule r = loaded("""
                Check:
                  expression: >-
                    1 == 1
                """);
        assertNull(r.getLoadError());
        assertNull(r.getCheckLevels(), "no level map was authored, so none is stored");
        assertNotNull(r.getCheck());
        assertEquals(List.of(Severity.ERROR), List.copyOf(r.effectiveCheckLevels().keySet()),
                "an absent Severity means ERROR, so the synthesised single level is ERROR");
        assertEquals(r.getCheck(), r.effectiveCheckLevels().get(Severity.ERROR).condition(),
                "the synthesised level's condition IS getCheck() — that is why widening a walker "
                        + "to effectiveCheckLevels() cannot change it on the shipped corpus");
        assertNull(r.getCheckLevelExprs(), "a single-level rule carries no per-level Expr map");
    }


    @Test
    @DisplayName("a plain Check on a Warning rule declares its one level at WARNING")
    void plainCheckFollowsTheAuthoredSeverity() throws IOException
    {
        Rule r = loaded("""
                Severity: "Warning"
                Check:
                  expression: >-
                    1 == 1
                """);
        assertNull(r.getLoadError());
        assertEquals(List.of(Severity.WARNING), List.copyOf(r.effectiveCheckLevels().keySet()));
    }

    // ------------------------------------------------------------------ the level map


    @Test
    @DisplayName("a level map binds strictest-first, whatever order it was authored in")
    void levelMapIsOrderedByTheLadderNotByFileOrder() throws IOException
    {
        Rule r = loaded("""
                Check:
                  INFO:
                    expression: >-
                      2 == 2
                  ERROR:
                    expression: >-
                      1 == 1
                """);
        assertNull(r.getLoadError());
        assertNotNull(r.getCheckLevels());
        assertEquals(List.of(Severity.ERROR, Severity.INFO),
                List.copyOf(r.getCheckLevels().keySet()),
                "the ladder decides evaluation order, never the file");
        assertEquals(r.getCheckLevels().get(Severity.ERROR).condition(), r.getCheck(),
                "getCheck() keeps meaning the STRICTEST level");
    }


    @Test
    @DisplayName("a level's optional Message binds, and is never defaulted from Outcome.Message")
    void perLevelMessageBinds() throws IOException
    {
        Rule r = loaded("""
                Outcome:
                  Message: "the rule message"
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                    Message: "definitely wrong"
                  INFO:
                    expression: >-
                      2 == 2
                """);
        assertNull(r.getLoadError());
        assertNotNull(r.getCheckLevels());
        assertEquals("definitely wrong", r.getCheckLevels().get(Severity.ERROR).message());
        assertNull(r.getCheckLevels().get(Severity.INFO).message(),
                "a level without a Message keeps null — the fallback is resolved at REPORT time, "
                        + "never copied in at load");
    }


    @Test
    @DisplayName("Message is stripped before the condition binds — it never becomes a leaf property")
    void messageIsStrippedFromTheConditionNode() throws IOException
    {
        Rule r = loaded("""
                Check:
                  ERROR:
                    operator: "equal_to"
                    name: "A"
                    value: "x"
                    Message: "m"
                """);
        assertNull(r.getLoadError());
        assertTrue(r.getUnknownKeys().isEmpty(), "no stray key reached the rule");
        assertNotNull(r.getCheckLevels());
        assertEquals("m", r.getCheckLevels().get(Severity.ERROR).message());
    }


    @Test
    @DisplayName("a level map round-trips: read → write → read yields the same levels")
    void levelMapRoundTrips() throws IOException
    {
        String authored = """
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                    Message: "definitely wrong"
                  INFO:
                    expression: >-
                      2 == 2
                """;
        Rule first = loaded(authored);
        String written = YAML.writeValueAsString(first);
        Rule second = YAML.readValue(written, Rule.class);
        assertNotNull(second.getCheckLevels());
        assertEquals(List.of(Severity.ERROR, Severity.INFO),
                List.copyOf(second.getCheckLevels().keySet()), written);
        assertEquals("definitely wrong", second.getCheckLevels().get(Severity.ERROR).message(),
                written);
        assertEquals(first.getCheck(), second.getCheck(), written);
    }


    @Test
    @DisplayName("a plain Check still serialises exactly as it did before levels existed")
    void plainCheckSerialisationIsUnchanged() throws IOException
    {
        Rule r = loaded("""
                Check:
                  expression: >-
                    1 == 1
                """);
        String written = YAML.writeValueAsString(r);
        assertTrue(written.contains("expression:"), written);
        assertFalse(written.contains("ERROR:"), () -> "no level key is invented: " + written);
    }

    // ------------------------------------------------------------------ the gates


    @Test
    @DisplayName("gate — a MIXED map is a load error naming the rule")
    void mixedMapFailsToLoad() throws IOException
    {
        Rule r = loaded("""
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                  expression: >-
                    2 == 2
                """);
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().startsWith("[T-0001] "), r.getLoadError());
        assertTrue(r.getLoadError().contains("mixes check levels"), r.getLoadError());
        assertNull(r.getCheck(), "nothing binds, so nothing evaluates");
    }


    @Test
    @DisplayName("gate — an UNKNOWN level name is a load error, and so is NOTICE")
    void unknownLevelNameFailsToLoad() throws IOException
    {
        Rule fatal = loaded("""
                Check:
                  FATAL:
                    expression: >-
                      1 == 1
                """);
        assertNotNull(fatal.getLoadError());
        assertTrue(fatal.getLoadError().contains("unknown check level(s) [FATAL]"),
                fatal.getLoadError());
        assertTrue(fatal.getLoadError().contains("REJECT, ERROR, WARNING, INFO"),
                () -> "the message names the ladder: " + fatal.getLoadError());

        Rule notice = loaded("""
                Check:
                  NOTICE:
                    expression: >-
                      1 == 1
                """);
        assertNotNull(notice.getLoadError());
        assertTrue(notice.getLoadError().contains("unknown check level(s) [NOTICE]"),
                () -> "NOTICE is a report-only kind no rule may author: " + notice.getLoadError());
    }


    @Test
    @DisplayName("gate — a level with no condition (Message only, or empty) is a load error")
    void levelWithNoConditionFailsToLoad() throws IOException
    {
        // `ERROR: {Message: "m"}` used to bind CLEAN as an all-null no-op level, because
        // fromNode never returns null for an object node and the "has no condition" branch
        // tested exactly that — i.e. it was dead. The emptiness of the condition node (after the
        // Message strip) is what has to be tested.
        Rule messageOnly = loaded("""
                Check:
                  ERROR:
                    Message: "m"
                """);
        assertNotNull(messageOnly.getLoadError(),
                "a level carrying only a Message checks nothing and must not load");
        assertTrue(messageOnly.getLoadError().contains("has no condition"),
                messageOnly.getLoadError());

        Rule empty = loaded("""
                Check:
                  ERROR: {}
                """);
        assertNotNull(empty.getLoadError(), "an empty level object checks nothing either");
        assertTrue(empty.getLoadError().contains("has no condition"), empty.getLoadError());
    }


    @Test
    @DisplayName("gate — a DUPLICATE level key is rejected at parse, never collapsed last-wins")
    void duplicateLevelKeyIsRejected()
    {
        // The tree parser collapses duplicate keys BEFORE RuleCheckDeserializer can see them, so
        // a duplicate level would silently drop a declared level's condition. The loader's parser
        // runs with STRICT_DUPLICATE_DETECTION, which is the only place the defect is still
        // visible; the parse exception names the line/column rather than the rule — the best
        // available signal at that stage.
        String pkg = """
                {
                  "rules": {
                    "T-0001": { "Core": { "Id": "T-0001" },
                                "Check": { "ERROR": { "expression": "1 == 1" },
                                           "ERROR": { "expression": "2 == 2" } } }
                  }
                }
                """;
        IOException e = assertThrows(IOException.class,
                () -> RulePackageLoader.loadFromString(pkg));
        assertTrue(e.getMessage().contains("Duplicate field"), e.getMessage());
    }


    @Test
    @DisplayName("gate — the STRICTEST declared level must equal the rule's Severity")
    void strictestLevelMustAgreeWithSeverity() throws IOException
    {
        Rule r = loaded("""
                Severity: "Warning"
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                  INFO:
                    expression: >-
                      2 == 2
                """);
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().contains("declares ERROR as its strictest level"),
                r.getLoadError());
        assertTrue(r.getLoadError().contains("Severity is Warning"), r.getLoadError());
    }


    @Test
    @DisplayName("gate — an absent Severity means ERROR, so {ERROR, INFO} agrees with it")
    void absentSeverityAgreesWithAnErrorStrictestLevel() throws IOException
    {
        Rule r = loaded("""
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                  INFO:
                    expression: >-
                      2 == 2
                """);
        assertNull(r.getLoadError(),
                "absent is not 'unknown' — it is ERROR, and ERROR is the strictest declared level");
    }


    @Test
    @DisplayName("gate — a per-RULE severity threshold is a load error")
    void perRuleThresholdFailsToLoad() throws IOException
    {
        Rule r = loaded("""
                Severity_Threshold: "Error"
                Check:
                  expression: >-
                    1 == 1
                """);
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().contains("'Severity_Threshold' is not a rule field"),
                r.getLoadError());
        assertTrue(r.getLoadError().contains("RUN option"), r.getLoadError());
    }


    @Test
    @DisplayName("gate — a rules/*.json PACKAGE that declares a threshold fails the whole load")
    void packageThresholdFailsToLoad()
    {
        String pkg = """
                {
                  "severityThreshold": "Error",
                  "rules": {
                    "T-0001": { "Core": { "Id": "T-0001" },
                                "Check": { "expression": "1 == 1" } }
                  }
                }
                """;
        IOException e = assertThrows(IOException.class,
                () -> RulePackageLoader.loadFromString(pkg));
        assertTrue(e.getMessage().contains("severityThreshold"), e.getMessage());
        assertTrue(e.getMessage().contains("RUN option"),
                () -> "the message says where the threshold DOES belong: " + e.getMessage());
    }


    @Test
    @DisplayName("a package with no threshold key still loads, and records no unknown key")
    void ordinaryPackageIsUnaffected() throws IOException
    {
        var pkg = RulePackageLoader.loadFromString("""
                {
                  "rules": {
                    "T-0001": { "Core": { "Id": "T-0001" },
                                "Check": { "expression": "1 == 1" } }
                  }
                }
                """);
        assertTrue(pkg.getUnknownKeys().isEmpty(), pkg.getUnknownKeys().toString());
        assertNotNull(pkg.getRules());
        assertNull(pkg.getRules().get("T-0001").getLoadError());
    }


    @Test
    @DisplayName("a Message splices into EVERY condition shape — all / any / not / leaf")
    void perLevelMessageRoundTripsForEveryConditionShape() throws IOException
    {
        // One rule per shape, because the Message has to be written as a SIBLING of the
        // condition's own keys (§3.3's flat spelling) and each shape writes those keys
        // differently. A shape the splice cannot handle would round-trip to a different Check.
        List<String> checks = List.of("""
                Check:
                  ERROR:
                    all:
                      - operator: "non_empty"
                        name: "A"
                      - operator: "empty"
                        name: "B"
                    Message: "m"
                """, """
                Check:
                  ERROR:
                    any:
                      - operator: "non_empty"
                        name: "A"
                    Message: "m"
                """, """
                Check:
                  ERROR:
                    not:
                      operator: "non_empty"
                      name: "A"
                    Message: "m"
                """, """
                Check:
                  ERROR:
                    operator: "equal_to"
                    name: "A"
                    value: "x"
                    value_is_literal: true
                    Message: "m"
                """, """
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                    Message: "m"
                """);
        for (String check : checks)
        {
            Rule first = loaded(check);
            assertNull(first.getLoadError(), check);
            assertNotNull(first.getCheckLevels(), check);

            String written = YAML.writeValueAsString(first);
            Rule second = YAML.readValue(written, Rule.class);

            assertNotNull(second.getCheckLevels(), written);
            assertEquals("m", second.getCheckLevels().get(Severity.ERROR).message(), written);
            // Compared as EXPRESSIONS, not by CheckCondition.equals: a leaf's absent `value`
            // round-trips as an explicit JSON null under this mapper's default inclusion, which
            // makes two structurally identical leaves compare unequal. That is a pre-existing
            // property of the condition serialiser and orthogonal to the Message splice; the
            // expression form is the shape this test is actually about.
            assertEquals(rendered(first.getCheck()), rendered(second.getCheck()),
                    () -> "the condition must survive the Message splice unchanged:\n" + written);
        }
    }

    // ------------------------------------------------------------------ compilation


    @Test
    @DisplayName("installNativeExpr compiles ONE Expr per level and joins the domains")
    void everyLevelIsCompiled() throws IOException
    {
        Rule r = loaded("""
                Check:
                  ERROR:
                    expression: >-
                      A == "x"
                  INFO:
                    expression: >-
                      A == "y"
                """);
        RulePackageLoader.installNativeExpr(r);
        assertNotNull(r.getCheckLevelExprs());
        assertEquals(List.of(Severity.ERROR, Severity.INFO),
                List.copyOf(r.getCheckLevelExprs().keySet()));
        assertEquals(r.getCheckExpr(), r.getCheckLevelExprs().get(Severity.ERROR),
                "checkExpr keeps meaning the strictest level");
        assertNotNull(r.getEvaluationDomain());
        assertTrue(r.getEvaluationDomain().rowCursor());
    }


    @Test
    @DisplayName("LevelCheck.mapConditions rewrites every level and keeps each Message")
    void mapConditionsRewritesEveryLevel() throws IOException
    {
        Rule r = loaded("""
                Check:
                  ERROR:
                    expression: >-
                      1 == 1
                    Message: "m"
                  INFO:
                    expression: >-
                      2 == 2
                """);
        var mapped = LevelCheck.mapConditions(r.getCheckLevels(),
                _ -> new net.cumba.corej.core.model.CheckConditionConstant(true));
        assertNotNull(mapped);
        assertEquals(List.of(Severity.ERROR, Severity.INFO), List.copyOf(mapped.keySet()));
        assertEquals("m", mapped.get(Severity.ERROR).message(), "the Message survives the rewrite");
        assertNull(mapped.get(Severity.INFO).message());
        assertNull(LevelCheck.mapConditions(null, java.util.function.UnaryOperator.identity()),
                "a single-level rule maps to null, i.e. stays single-level");
    }

}
