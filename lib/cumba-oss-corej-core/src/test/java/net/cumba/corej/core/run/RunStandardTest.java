package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.StandardRef;
import org.junit.jupiter.api.Test;

/**
 * {@link RunStandard} — what a run IS, derived from its packages' declared primaries now that
 * {@code -s} / {@code -v} / {@code -f} are gone (Plan 2, R5/R7/R8).
 */
class RunStandardTest
{

    private static StandardRef primary(String id)
    {
        return new StandardRef(id, StandardRef.Role.PRIMARY);
    }


    private static StandardRef companion(String id)
    {
        return new StandardRef(id, StandardRef.Role.COMPANION);
    }


    @Test
    void sdtmigKeySplitsIntoGroupStandardAndVersion()
    {
        RunStandard r = RunStandard.of("sdtmig/3-4");
        assertEquals("sdtmig", r.group());
        assertEquals("sdtmig", r.standard());
        assertEquals("3-4", r.version());
        assertEquals("standards/sdtmig/3-4", r.key());
    }


    /**
     * ⚑ A SENDIG sub-standard's version IS the dashed remainder — {@code dart-1-1}, not
     * {@code 1-1}. That is how the library names it, and reassembling it any other way is the
     * guesswork R1/R6 exist to remove.
     */
    @Test
    void sendigSubStandardKeepsItsCompoundVersion()
    {
        RunStandard r = RunStandard.of("sendig/dart-1-1");
        assertEquals("sendig", r.group());
        assertEquals("sendig", r.standard());
        assertEquals("dart-1-1", r.version());
    }


    /** An ADaM product id splits on its trailing version segment: {@code adamig-1-3}. */
    @Test
    void adamProductIdSplitsOnTheTrailingVersion()
    {
        RunStandard r = RunStandard.of("adam/adamig-1-3");
        assertEquals("adam", r.group());
        assertEquals("adamig", r.standard());
        assertEquals("1-3", r.version());
    }


    /** ... including a multi-dash product id, where a naive first-dash split would be wrong. */
    @Test
    void adamMultiDashProductIdSplitsOnTheVersionNotTheFirstDash()
    {
        RunStandard r = RunStandard.of("adam/adam-occds-1-1");
        assertEquals("adam-occds", r.standard(),
                "the version tail is 1-1, so the id is adam-occds");
        assertEquals("1-1", r.version());
    }


    /** A TIG key is {@code tig/<version>/<leg>}; the version is the FIRST segment, not the leg. */
    @Test
    void tigKeyTakesTheVersionBeforeTheLeg()
    {
        RunStandard r = RunStandard.of("tig/1-0/adam");
        assertEquals("tig", r.group());
        assertEquals("tig", r.standard());
        assertEquals("1-0", r.version());
    }


    @Test
    void theStandardsPrefixIsOptionalAndCaseIsNormalised()
    {
        assertEquals(RunStandard.of("sdtmig/3-4"), RunStandard.of("standards/SDTMIG/3-4"));
    }


    @Test
    void derivesFromTheFirstPrimaryIgnoringCompanions()
    {
        RunStandard r = RunStandard
                .from(List.of(primary("adam/adamig-1-3"), companion("sdtmig/3-4")));
        assertEquals("adam", r.group());
        assertEquals("adamig", r.standard(), "a companion must not become the run's standard");
    }


    @Test
    void noPrimaryYieldsNull()
    {
        assertNull(RunStandard.from(List.of(companion("sdtmig/3-4"))));
        assertNull(RunStandard.from(List.of()));
    }


    /**
     * ⭐ <b>R8, "refined B".</b> Several primaries are fine while they share ONE library group —
     * this is the case the ruling explicitly allows.
     */
    @Test
    void severalPrimariesInOneGroupAreAllowed()
    {
        RunStandard r = RunStandard
                .from(List.of(primary("sendig/3-1-1"), primary("sendig/dart-1-1")));
        assertEquals("sendig", r.group());
    }


    /** ... and all four TIG legs are one group, which is what lets a TIG package declare them. */
    @Test
    void allFourTigLegsAreOneGroup()
    {
        RunStandard r = RunStandard.from(List.of(primary("tig/1-0/adam"), primary("tig/1-0/cdash"),
                primary("tig/1-0/sdtm"), primary("tig/1-0/send")));
        assertEquals("tig", r.group());
    }


    /**
     * ⛔ <b>R8 — primaries from two groups are REFUSED.</b> Routing, {@code hasSdtmProduct()} and
     * the companion decorator are all per-run, so a mixed run would answer from the wrong provider
     * on a shared dataset. The message must name both groups so the user can split the run.
     */
    @Test
    void primariesFromTwoGroupsAreRefusedNamingBoth()
    {
        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> RunStandard.from(List.of(primary("adam/adamig-1-3"), primary("sdtmig/3-4"))));
        assertTrue(ex.getMessage().contains("adam") && ex.getMessage().contains("sdtmig"),
                "both offending groups must be named: " + ex.getMessage());
    }


    /**
     * ⛔⛔ <b>Review finding R-3 / ruling V3.</b> Two versions of ONE standard share a group, so the
     * group check passed them — and {@code from()} then returned {@code of(primaries.get(0))},
     * letting the order the packages were TYPED decide the library product in silence. The report
     * header named one version while the other's rules executed, and reversing the two tokens
     * flipped it with nothing logged.
     */
    @Test
    void sameStandardAtTwoVersionsIsRefused()
    {
        StudyValidationException e = assertThrows(StudyValidationException.class, () -> RunStandard
                .from(List.of(primary("adam/adamig-1-1"), primary("adam/adamig-1-3"))));
        assertTrue(e.getMessage().contains("two different versions"), e.getMessage());
        assertTrue(e.getMessage().contains("adamig-1-1") && e.getMessage().contains("adamig-1-3"),
                "the message must name BOTH conflicting products: " + e.getMessage());

        // Order must not change the verdict — the defect was that it changed the OUTCOME.
        assertThrows(StudyValidationException.class, () -> RunStandard
                .from(List.of(primary("adam/adamig-1-3"), primary("adam/adamig-1-1"))));

        // A bare version line collides with itself too.
        assertThrows(StudyValidationException.class,
                () -> RunStandard.from(List.of(primary("sendig/3-1-1"), primary("sendig/3-2"))));
    }


    /**
     * ⭐⭐ <b>The case ruling V3 explicitly protects, and the reason the key is the PRODUCT LINE.</b>
     * {@code RunStandard.of} gives BOTH of these {@code standard = "sendig"}, so a naive
     * {@code (group, standard)} key would refuse them — the exact combination R8 was refined to
     * allow. The version's alphabetic prefix ({@code dart} vs {@code ""}) is what separates a named
     * product line from a bare version.
     */
    @Test
    void twoDifferentProductLinesInOneGroupStayLegal()
    {
        RunStandard rs = RunStandard
                .from(List.of(primary("sendig/3-1-1"), primary("sendig/dart-1-1")));

        assertNotNull(rs, "sendig/3-1-1 + sendig/dart-1-1 is legal under R8");
        assertEquals("sendig", rs.group());
    }


    /**
     * ⚑ TIG legs must not be caught by the version check: {@code tig/1-0/adam} and
     * {@code tig/1-0/cdash} both derive version {@code 1-0}, so they share a line at the SAME
     * version. The two shipped TIG packages declare four such legs each.
     */
    @Test
    void tigLegsShareOneVersionAndAreNotAConflict()
    {
        RunStandard rs = RunStandard.from(List.of(primary("tig/1-0/adam"), primary("tig/1-0/cdash"),
                primary("tig/1-0/sdtm"), primary("tig/1-0/send")));

        assertNotNull(rs);
        assertEquals("tig", rs.group());
        assertEquals("1-0", rs.version());
    }
}
