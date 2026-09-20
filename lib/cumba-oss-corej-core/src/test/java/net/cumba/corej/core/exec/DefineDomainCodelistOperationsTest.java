package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineMetadataListCodec;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 of {@code plans/done/PLAN-define-item-metadata-parity-929-1081.md}: end-to-end coverage
 * of the two define/library operations {@code CDISC-CG0001} binds.
 *
 * <ul>
 * <li>{@code domain_is_custom} &rarr; {@code MetadataProvider.isDomainCustom(domain)}: custom iff
 * absent from the standard/model dataset lists.</li>
 * <li>{@code codelist_terms(codelists=["DOMAIN"], level=term, returntype=code)} &rarr; the
 * {@code getCodelist("DOMAIN")} view's term <em>concept ids</em> (each term's NCI C-code — NOT
 * {@code getCodelistTerms}' submission values).</li>
 * </ul>
 *
 * The rule fires on a standard domain whose define {@code DOMAIN} codelist (ccode {@code C66734})
 * carries a code not published in the CDISC {@code DOMAIN} codelist — verifying both operations
 * resolve and feed the list-aware {@code not in} membership.
 */
class DefineDomainCodelistOperationsTest
{

    private static Rule cg0001;

    private static final List<String> PUBLISHED_DOMAINS = List.of("AE", "CM", "DM", "EX", "LB");

    /**
     * The published DOMAIN terms' NCI concept ids, keyed by submission value — what the define's
     * {@code codelist_coded_codes} (CodeListItem Alias names, i.e. C-codes) is compared against
     * under {@code returntype="code"}. Synthetic values; only membership matters here.
     */
    private static final Map<String, String> PUBLISHED_DOMAIN_CCODES = domainCcodes();

    private static Map<String, String> domainCcodes()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("AE", "C11111");
        m.put("CM", "C22222");
        m.put("DM", "C33333");
        m.put("EX", "C44444");
        m.put("LB", "C55555");
        return m;
    }


    @BeforeAll
    static void load() throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-4.json"));
        cg0001 = pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CDISC-CG0001".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CDISC-CG0001 not in package"));
    }


    /** Library provider: the published DOMAIN codelist + (optionally) custom-domain flags. */
    private static StubMetadataProvider library()
    {
        return new StubMetadataProvider().codelist("DOMAIN", PUBLISHED_DOMAINS)
                .codelistTermCcodes("DOMAIN", PUBLISHED_DOMAIN_CCODES);
    }


    /** Define provider: the AE dataset's DOMAIN ItemDef bound to the DOMAIN codelist (C66734). */
    private static MetadataProvider define(List<String> codedCodes)
    {
        return new StubMetadataProvider().variable("AE", Map.of("name", "DOMAIN", "ccode", "C66734",
                "codelist_coded_codes", DefineMetadataListCodec.encode(codedCodes)));
    }


    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").col("DOMAIN", "AE").col("AETERM", "x").build();
    }


    @Test
    void standardDomain_withUnpublishedDomainCode_fires()
    {
        // $domain_is_custom == false (AE is standard), define_variable_ccode == C66734, and the
        // define DOMAIN CodeListItem alias "C99999" is not among the published term C-codes ->
        // the `not in` membership fires.
        RuleExecutionResult r = RuleRunner.execute(cg0001, aeTable(), _ -> null, "AE", library(),
                null, define(List.of("C99999")));
        assertTrue(r.hasViolations(), "unpublished domain code on a standard domain must fire");
    }


    @Test
    void standardDomain_allCodesPublished_doesNotFire()
    {
        // every define DOMAIN alias C-code is published -> the `not in` membership does not fire.
        RuleExecutionResult r = RuleRunner.execute(cg0001, aeTable(), _ -> null, "AE", library(),
                null, define(List.of("C11111", "C22222")));
        assertFalse(r.hasViolations());
    }


    @Test
    void customDomain_doesNotFire()
    {
        // $domain_is_custom == true (AE marked custom) -> the first check fails, so no finding even
        // with an unpublished code.
        StubMetadataProvider lib = library().customDomain("AE");
        RuleExecutionResult r = RuleRunner.execute(cg0001, aeTable(), _ -> null, "AE", lib, null,
                define(List.of("C99999")));
        assertFalse(r.hasViolations(), "custom domain is out of scope for CDISC-CG0001");
    }
}
