package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of {@code PLAN-leaf-scope-domain-inference.md} (owner ruling 1): the generic
 * {@code exists} / {@code not_exists} presence operators — the one construct whose meaning the
 * retired {@code Rule_Type} decided (column presence on a data rule, dataset presence on a Domain
 * Presence Check) — are rejected at load with a message naming the replacement, in every spelling
 * and position: leaf form, expression form, nested, and in a Precondition.
 */
class GenericPresenceRetirementTest
{

    private static Rule load(String body) throws IOException
    {
        return RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}").getRules()
                .get("R1");
    }


    private static String rule(String check, String precondition)
    {
        return "{\"Core\":{\"Id\":\"R1\"},\"Check\":" + check
                + (precondition == null ? "" : ",\"Precondition\":" + precondition)
                + ",\"Outcome\":{\"Message\":\"m\"}}";
    }


    @Test
    void aGenericLeafIsRejectedAsTheRetiredOperatorLeafForm()
    {
        // Phase 7d (D121): the LEAF spelling of the generic presence operator is now doubly
        // retired — the operator-leaf model itself rejects at bind time, before the
        // generic-presence validation could even see an operator name.
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> load(rule("{\"all\":[{\"name\":\"AETERM\",\"operator\":\"exists\"},"
                        + "{\"expression\": \"not empty(AETERM)\"}]}", null)));
        assertTrue(ex.getMessage().contains("operator-leaf Check form"), ex.getMessage());
    }


    @Test
    void aGenericCallInExpressionFormIsALoadError() throws IOException
    {
        Rule nested = load(rule(
                "{\"expression\":\"not empty(AETERM) and (AESEV == \\\"MILD\\\" or not_exists(AESER))\"}",
                null));
        assertNotNull(nested.getLoadError());
        assertTrue(nested.getLoadError().contains("'not_exists'"), nested.getLoadError());
    }


    @Test
    void aGenericCallInAPreconditionIsALoadErrorNotAThrow() throws IOException
    {
        Rule r = load(rule("{\"expression\":\"not empty(AETERM)\"}",
                "{\"expression\":\"exists(AESER)\"}"));
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().contains("Precondition"), r.getLoadError());
        assertTrue(r.getLoadError().contains("'exists'"), r.getLoadError());
    }


    /**
     * ⚠ The {@code Precondition} half of this fixture is installed on the <b>engine-internal</b>
     * tier rather than authored: gate R8 ({@code plans/done/PLAN-scope-requirements-split.md}
     * &#167;4.2, owner ruling Q3) closed the authoring surface, and
     * {@link RulePackageLoader#installEngineInternalPrecondition} is the supported constructor. The
     * property under test — that the explicit {@code ds_exists} / {@code var_exists} spellings
     * raise cleanly on both surfaces — is unchanged.
     */
    @Test
    void theExplicitSpellingsLoadCleanly() throws IOException
    {
        Rule r = load(rule("{\"expression\":\"ds_exists(\\\"EX\\\") and var_exists(\\\"AESEQ\\\")"
                + " and not var_exists(\\\"AESER\\\") and EXDOSE > 0\"}", null));
        assertNull(r.getLoadError(), r.getLoadError());
        assertNotNull(r.getCheckExpr(), "the mixed presence shape compiles natively");
        RulePackageLoader.installEngineInternalPrecondition(r,
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        "{\"expression\":\"not ds_exists(\\\"SUPPAE\\\")\"}",
                        net.cumba.corej.core.model.CheckCondition.class));
        assertNull(r.getLoadError(), r.getLoadError());
        assertNotNull(r.getPreconditionExpr(), "the precondition raises to a broadcast verdict");
    }
}
