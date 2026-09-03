package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Locale;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Fix #38 — load-time rejection of empty/null entries in {@code Scope.Domains.Include} and
 * {@code Scope.Domains.Exclude}. Under Fix #38's prefix-matching semantics a zero-length entry
 * would match every dataset, which is never the rule author's intent.
 *
 * <p>
 * Each test loads a minimal rule package via {@link RulePackageLoader#loadFromString} and inspects
 * {@link Rule#getLoadError()}. Mirrors the fixture style of {@link RuleLoadValidationTest} (Fix
 * #37).
 * </p>
 */
class RuleLoadValidationEmptyIncludeTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule onlyRule(RulePackage pkg)
    {
        return pkg.getRules().values().iterator().next();
    }


    @Test
    void emptyStringIncludeEntry_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-038-A"},
                  "Scope": {
                    "Domains": {
                      "Include": [""]
                    }
                  },
                  "Check": {
                    "name": "AESTDY",
                    "operator": "non_empty"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "empty include entry should tag loadError");
        assertTrue(rule.getLoadError().contains("TEST-038-A"),
                "loadError mentions the Core ID: " + rule.getLoadError());
        assertTrue(rule.getLoadError().toLowerCase(Locale.ROOT).contains("empty"),
                "loadError mentions 'empty' wording: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Scope.Domains.Include"),
                "loadError mentions Scope.Domains.Include: " + rule.getLoadError());
    }


    @Test
    void mixedEmptyAndValidIncludeEntries_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-038-B"},
                  "Scope": {
                    "Domains": {
                      "Include": ["", "ADAE"]
                    }
                  },
                  "Check": {
                    "name": "AESTDY",
                    "operator": "non_empty"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(),
                "mixed include with one empty entry should tag loadError");
        assertTrue(rule.getLoadError().contains("TEST-038-B"),
                "loadError mentions the Core ID: " + rule.getLoadError());
        assertTrue(rule.getLoadError().toLowerCase(Locale.ROOT).contains("empty"),
                "loadError mentions 'empty' wording: " + rule.getLoadError());
    }


    @Test
    void emptyStringExcludeEntry_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-038-C"},
                  "Scope": {
                    "Domains": {
                      "Exclude": [""]
                    }
                  },
                  "Check": {
                    "name": "AESTDY",
                    "operator": "non_empty"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "empty exclude entry should tag loadError");
        assertTrue(rule.getLoadError().contains("TEST-038-C"),
                "loadError mentions the Core ID: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Scope.Domains.Exclude"),
                "loadError mentions Scope.Domains.Exclude: " + rule.getLoadError());
        assertTrue(rule.getLoadError().toLowerCase(Locale.ROOT).contains("empty"),
                "loadError mentions 'empty' wording: " + rule.getLoadError());
    }


    @Test
    void wellFormedInclude_noLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-038-D"},
                  "Scope": {
                    "Domains": {
                      "Include": ["ADAE"]
                    }
                  },
                  "Check": {
                    "name": "AESTDY",
                    "operator": "non_empty"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(),
                "well-formed Include should leave loadError null but was: " + rule.getLoadError());
    }
}
