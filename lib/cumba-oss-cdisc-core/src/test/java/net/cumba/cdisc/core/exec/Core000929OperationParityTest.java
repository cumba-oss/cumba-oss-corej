package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.DefineMetadataListCodec;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 of {@code plans/PLAN-define-item-metadata-parity-929-1081.md}: end-to-end cross-check of
 * CORE-000929's two operations against the Python reference semantics.
 *
 * <ul>
 * <li>{@code domain_is_custom} &rarr; {@code MetadataProvider.isDomainCustom(domain)} (Python's
 * {@code is_custom_domain}): custom iff absent from the standard/model dataset lists.</li>
 * <li>{@code codelist_terms(codelists=["DOMAIN"], level=term, returntype=code)} &rarr;
 * {@code getCodelistTerms("DOMAIN")} (Python's {@code codelist_terms} returning each term's
 * code).</li>
 * </ul>
 *
 * The rule fires on a standard domain whose define {@code DOMAIN} codelist (ccode {@code C66734})
 * carries a code not published in the CDISC {@code DOMAIN} codelist — verifying both operations
 * resolve and feed the list-aware {@code is_not_contained_by} exactly as Python does.
 */
class Core000929OperationParityTest
{

    private static Rule core929;

    private static final List<String> PUBLISHED_DOMAINS = List.of("AE", "CM", "DM", "EX", "LB");

    @BeforeAll
    static void load() throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-4.json"));
        core929 = pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CORE-000929".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CORE-000929 not in package"));
    }


    /** Library provider: the published DOMAIN codelist + (optionally) custom-domain flags. */
    private static StubMetadataProvider library()
    {
        return new StubMetadataProvider().codelist("DOMAIN", PUBLISHED_DOMAINS);
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
        // define DOMAIN codelist code "XX" is not in the published list -> is_not_contained_by
        // fires.
        RuleExecutionResult r = RuleRunner.execute(core929, aeTable(), _ -> null, "AE", library(),
                null, define(List.of("XX")));
        assertTrue(r.hasViolations(), "unpublished domain code on a standard domain must fire");
    }


    @Test
    void standardDomain_allCodesPublished_doesNotFire()
    {
        // every define DOMAIN code is published -> any(in published) true -> is_not does not fire.
        RuleExecutionResult r = RuleRunner.execute(core929, aeTable(), _ -> null, "AE", library(),
                null, define(List.of("AE", "CM")));
        assertFalse(r.hasViolations());
    }


    @Test
    void customDomain_doesNotFire()
    {
        // $domain_is_custom == true (AE marked custom) -> the first check fails, so no finding even
        // with an unpublished code. Mirrors Python's is_custom_domain gating.
        StubMetadataProvider lib = library().customDomain("AE");
        RuleExecutionResult r = RuleRunner.execute(core929, aeTable(), _ -> null, "AE", lib, null,
                define(List.of("XX")));
        assertFalse(r.hasViolations(), "custom domain is out of scope for CORE-000929");
    }
}
