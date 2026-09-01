package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * Load guard against a {@code Scope.Domains} {@code --} token whose prefix is a 2-character SDTM
 * <em>domain code</em> — {@code FA--}, {@code LB--}, … Such a token is broken by construction:
 * {@code --} is strict (prefix plus exactly two characters), so {@code FA--} catches {@code FALB}
 * and <b>misses</b> the split {@code FALBHM}, whose data-derived base is the 2-character
 * {@code FA}. That is a false negative — the invisible direction — which is why it earns a gate
 * rather than a guideline entry.
 *
 * <p>
 * ⚠ {@code AP--} and {@code SQ--} are deliberately exempt: neither prefix is a domain code, and
 * both families' data-derived bases are 4 characters ({@code APMH}; {@code SQ} + {@code RDOMAIN}),
 * which is exactly what a 2-character {@code --} prefix demands. Getting that exemption wrong would
 * warn on 29 shipped {@code AP--} rules.
 * </p>
 *
 * <p>
 * Measured when the guard was written: <b>0</b> of 3722 {@code rules-src} rules trip it. It exists
 * so the next one is caught at load.
 * </p>
 */
class DomainWildcardPrefixLoadWarningTest
{

    private static Rule scoped(List<String> include, List<String> exclude)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        if (include != null)
        {
            domains.setInclude(include);
        }
        if (exclude != null)
        {
            domains.setExclude(exclude);
        }
        scope.setDomains(domains);
        rule.setScope(scope);
        return rule;
    }


    private static @org.jspecify.annotations.Nullable String warn(List<String> include,
            List<String> exclude)
    {
        Rule rule = scoped(include, exclude);
        java.util.List<String> warnings = new java.util.ArrayList<>();
        RulePackageLoader.checkDomainWildcardPrefix(rule, warnings);
        return warnings.isEmpty() ? null : String.join("; ", warnings);
    }


    @Test
    void includeEntryWithDomainCodePrefixWarns()
    {
        String message = warn(List.of("FA--"), null);
        assertNotNull(message, "FA-- must be flagged");
        assertTrue(message.contains("Scope.Domains.Include"), message);
        assertTrue(message.contains("FA--"), message);
        assertTrue(message.contains("'FA'"), "the message must name the correct scope: " + message);
    }


    @Test
    void excludeEntryWithDomainCodePrefixWarns()
    {
        String message = warn(null, List.of("LB--"));
        assertNotNull(message, "LB-- must be flagged");
        assertTrue(message.contains("Scope.Domains.Exclude"), message);
    }


    @Test
    void suppApSqApfaTokensAreExempt()
    {
        // The four family tokens: AP/SQ by the documented exemption, SUPP/APFA because their
        // prefixes are longer than a domain code.
        assertNull(warn(List.of("SUPP--", "AP--", "SQ--", "APFA--"), null));
        assertNull(warn(null, List.of("SUPP--", "AP--", "SQ--", "APFA--")));
    }


    @Test
    void plainDomainCodesAndOtherEntriesAreSilent()
    {
        assertNull(warn(List.of("FA", "LB", "ALL"), List.of("RELREC", "NONE")));
    }


    @Test
    void patternEntriesAreNotDashDashTokens()
    {
        // A glob/regex entry takes precedence over the `--` branch in firstMatchingDomainEntry,
        // so the strict-length reasoning does not apply to it and it must not be flagged.
        assertNull(warn(List.of("FA--*"), null));
        assertNull(warn(List.of("/^FA--$/"), null));
    }


    @Test
    void warningLandsOnTheWarningChannelAndKeepsAnEarlierOne()
    {
        Rule rule = scoped(List.of("FA--"), null);
        rule.setLoadWarning("an earlier warning");
        RulePackageLoader.validateEnumFields(rule);
        assertNull(rule.getLoadError(), "a broken `--` token is not a load error");
        assertNotNull(rule.getLoadWarning());
        assertTrue(rule.getLoadWarning().startsWith("an earlier warning"),
                "the earlier warning must not be clobbered: " + rule.getLoadWarning());
        assertTrue(rule.getLoadWarning().contains("FA--"), rule.getLoadWarning());
    }


    @Test
    void noDomainScopeIsSilent()
    {
        assertNull(warn(null, null));
        Rule bare = new Rule();
        java.util.List<String> warnings = new java.util.ArrayList<>();
        RulePackageLoader.checkDomainWildcardPrefix(bare, warnings);
        assertTrue(warnings.isEmpty());
    }

}
