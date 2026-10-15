package net.cumba.corej.define.conformance.ct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link CodelistClass} ({@code PLAN-stopped-rule-engine-fixes} SR3 / T8): class and role read from
 * the NCI preferred term's published-name convention — the three {@code "CDISC <class> "} prefixes
 * and the five {@code " <role> Terminology"} suffixes.
 */
class CodelistClassTest
{

    private static CodelistClass of(String aTerm)
    {
        return CodelistClass.of(aTerm).orElseThrow(() -> new AssertionError(aTerm));
    }


    @Test
    void theFiveRoleSuffixesOfAClinicalClassification()
    {
        assertEquals(new CodelistClass("Clinical Classification", Optional.of("Test Code")),
                of("CDISC Clinical Classification HAMD 17 Test Code Terminology"));
        assertEquals(new CodelistClass("Clinical Classification", Optional.of("Test Name")),
                of("CDISC Clinical Classification HAMD 17 Test Name Terminology"));
        assertEquals(new CodelistClass("Clinical Classification", Optional.of("Original Response")),
                of("CDISC Clinical Classification HAMD 17 Original Response Terminology"));
        assertEquals(
                new CodelistClass("Clinical Classification", Optional.of("Character Response")),
                of("CDISC Clinical Classification HAMD 17 Character Response Terminology"));
        assertEquals(new CodelistClass("Clinical Classification", Optional.of("Category")),
                of("CDISC Clinical Classification Category Terminology"));
    }


    @Test
    void theThreeClasses()
    {
        assertEquals("Clinical Classification",
                of("CDISC Clinical Classification HAMD 17 Test Code Terminology").qrsClass());
        assertEquals("Questionnaire",
                of("CDISC Questionnaire HAMD 17 Test Code Terminology").qrsClass());
        assertEquals("Functional Test",
                of("CDISC Functional Test 6MWT Test Code Terminology").qrsClass());
    }


    @Test
    void aTermWithoutARoleSuffixHasAClassButNoRole()
    {
        CodelistClass noRole = of("CDISC Questionnaire Something Else Terminology");
        assertEquals("Questionnaire", noRole.qrsClass());
        assertEquals(Optional.empty(), noRole.role());
        // a role word inside the name is not the suffix
        assertEquals(Optional.empty(),
                of("CDISC Questionnaire Test Code Scale Terminology").role());
    }


    @Test
    void aTermOutsideTheThreePrefixesIsInNoClass()
    {
        assertTrue(CodelistClass.of("CDISC SDTM Tumor Response Assessment Test Code Terminology")
                .isEmpty(), "an SDTM list (C96782) is in no QRS class");
        assertTrue(CodelistClass.of("CDISC SDTM Sex Terminology").isEmpty());
        // the one questionnaire whose name the prefix does not catch (measured 2026-10-12)
        assertTrue(CodelistClass
                .of("CDISC Brief Questionnaire of Smoking Urges Test Code Terminology").isEmpty());
        assertTrue(CodelistClass.of("Clinical Classification X Test Code Terminology").isEmpty(),
                "the prefix starts with CDISC");
        assertTrue(CodelistClass.of("").isEmpty());
        assertTrue(CodelistClass.of(null).isEmpty());
    }


    /**
     * PACK G fold (G8 LOW2): the role suffix is matched in any case — published Questionnaire terms
     * spell it in lower case too ({@code "… Test code Terminology"}); the role answered is the
     * canonical spelling.
     */
    @Test
    void theRoleSuffixMatchesInAnyCase()
    {
        assertEquals(Optional.of("Test Code"),
                of("CDISC Questionnaire GAD-7 Test code Terminology").role());
        assertEquals(Optional.of("Original Response"),
                of("CDISC Questionnaire GAD-7 Original response terminology").role());
        assertEquals(Optional.of("Character Response"),
                of("CDISC Questionnaire GAD-7 CHARACTER RESPONSE TERMINOLOGY").role());
    }


    /** PACK G fold (G8 LOW3): an absent role is {@code Optional.empty()}, never {@code null}. */
    @Test
    void aNullRoleIsRefused()
    {
        assertThrows(NullPointerException.class, () -> new CodelistClass("Questionnaire", null));
    }
}
