package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.run.CompanionSdtmDefaults.Companion;
import org.junit.jupiter.api.Test;

/**
 * The companion-SDTM resolution contract after Plan 2 (R9/R10): <b>one tier</b> — the first
 * declared SDTM-family product — and <b>no house fallback</b>.
 *
 * <p>
 * ⛔ <b>What this file used to assert, and why those tests are gone.</b> It previously pinned three
 * further tiers: the {@code ADAMIG_TO_SDTMIG} table ({@code 1-0 → 3-1-2 … 1-3 → 3-4}), a
 * {@code DEFAULT_COMPANION_SDTMIG = "3-4"} guess for anything unmapped, and a TIG-ADaM derivation
 * that inferred the {@code sdtm} leg from the {@code adam} leg. R9 moved the table to packaging
 * data ({@code rules-src/package-standards.json}), R10 deleted the guess outright, and the
 * derivation became unnecessary once a TIG package declares all four legs. Those tests were
 * <b>deleted rather than adapted</b>: they pinned behaviour the plan deliberately removed, so
 * keeping them in any form would have re-asserted it.
 * </p>
 */
class CompanionSdtmDefaultsTest
{

    private static final List<String> ADAM_ONLY = List.of("standards/adam/adamig-1-3");

    // ------------------------------------------------------------------
    // The single surviving tier: a declared SDTM-family product
    // ------------------------------------------------------------------

    @Test
    void aDeclaredSdtmigProductResolves()
    {
        Companion c = CompanionSdtmDefaults
                .resolve(List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-2"));
        assertNotNull(c);
        assertEquals("sdtmig", c.loaderStandard());
        assertEquals("3-2", c.loaderVersion());
        assertTrue(c.declared(), "a resolved companion always comes from a declaration now");
        assertFalse(c.defaulted(), "R10: nothing can set the defaulted flag any more");
    }


    @Test
    void aDeclaredSendigProductIsSdtmFamilyToo()
    {
        Companion c = CompanionSdtmDefaults
                .resolve(List.of("standards/adam/adamig-1-3", "standards/sendig/3-1-1"));
        assertNotNull(c);
        assertEquals("sendig", c.loaderStandard());
        assertEquals("3-1-1", c.loaderVersion());
    }


    @Test
    void aDeclaredTigSdtmLegIsSdtmFamily()
    {
        Companion c = CompanionSdtmDefaults
                .resolve(List.of("standards/tig/1-0/adam", "standards/tig/1-0/sdtm"));
        assertNotNull(c);
        assertEquals("tig", c.loaderStandard());
        assertEquals("1-0/sdtm", c.loaderVersion());
    }


    @Test
    void theFirstSdtmFamilyKeyInDeclarationOrderWins()
    {
        Companion c = CompanionSdtmDefaults.resolve(List.of("standards/adam/adamig-1-3",
                "standards/sdtmig/3-3", "standards/sdtmig/3-4"));
        assertNotNull(c);
        assertEquals("3-3", c.loaderVersion(), "first match wins, not last and not newest");
    }


    /**
     * ⛔ <b>R10, the load-bearing assertion.</b> An ADaM-only declaration used to fall through to
     * the house table and then to a {@code 3-4} guess. It must now resolve to NOTHING, so the
     * caller SKIPs {@code standard_domains} loudly instead of silently validating against a version
     * nobody chose.
     */
    @Test
    void anAdamOnlyDeclarationResolvesToNoCompanion()
    {
        assertNull(CompanionSdtmDefaults.resolve(ADAM_ONLY),
                "R10: no declared companion means NO companion — never a guessed SDTMIG");
    }


    /** ⛔ R9: the retired ADaMIG→SDTMIG version table must not answer from anywhere. */
    @Test
    void theRetiredAdamigVersionTableNoLongerAnswers()
    {
        for (String v : List.of("1-0", "1-1", "1-2", "1-3"))
        {
            assertNull(CompanionSdtmDefaults.resolve(List.of("standards/adam/adamig-" + v)),
                    "adamig " + v + " must no longer map to a companion in engine code");
        }
    }


    /**
     * ⛔ The TIG-ADaM derivation is retired: an {@code adam} leg alone no longer conjures the
     * {@code sdtm} leg. A TIG package declares all four legs, so the sdtm leg arrives declared.
     */
    @Test
    void aTigAdamLegAloneNoLongerDerivesTheSdtmLeg()
    {
        assertNull(CompanionSdtmDefaults.resolve(List.of("standards/tig/1-0/adam")),
                "the adam leg alone must not derive the sdtm leg any more");
    }


    @Test
    void anEmptyDeclarationResolvesToNoCompanion()
    {
        assertNull(CompanionSdtmDefaults.resolve(List.of()));
    }

    // ------------------------------------------------------------------
    // Surviving helpers — still used by the run header and the ADaM gate
    // ------------------------------------------------------------------


    @Test
    void declaresTigAdamMatchesOnlyTheAdamLeg()
    {
        assertTrue(CompanionSdtmDefaults.declaresTigAdam(List.of("standards/tig/1-0/adam")));
        assertFalse(CompanionSdtmDefaults.declaresTigAdam(List.of("standards/tig/1-0/sdtm")));
        assertFalse(CompanionSdtmDefaults.declaresTigAdam(List.of("standards/sdtmig/3-4")));
    }


    @Test
    void tigLegReturnsTheFirstDeclaredLeg()
    {
        assertEquals("adam", CompanionSdtmDefaults
                .tigLeg(List.of("standards/tig/1-0/adam", "standards/tig/1-0/sdtm")));
        // ⛔⛔ RESTORED (review finding R-18). This reversed-order case was deleted when the class
        // was rewritten, and it is the ONLY assertion that discriminates "the FIRST declared leg
        // wins" from "prefer the adam leg". Without it, a mutation making tigLeg prefer `adam`
        // survives the whole suite — and that exact distinction is what finding R-2 turned on: the
        // shipped TIG packages declare `adam` first, so first-wins silently routed every TIG run
        // onto the ADaM leg.
        assertEquals("sdtm",
                CompanionSdtmDefaults
                        .tigLeg(List.of("standards/tig/1-0/sdtm", "standards/tig/1-0/adam")),
                "the FIRST declared leg wins — this is not a preference for adam");
        assertNull(CompanionSdtmDefaults.tigLeg(List.of("standards/sdtmig/3-4")));
        assertNull(CompanionSdtmDefaults.tigLeg(List.of()), "no products, no leg");
    }


    @Test
    void declaredCompanionIsNullWithoutAnSdtmFamilyKey()
    {
        assertNull(CompanionSdtmDefaults.declaredCompanion(ADAM_ONLY));
    }
}
