package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.corej.core.RulePackageLoader;
import org.junit.jupiter.api.Test;

/**
 * {@link Rule#effectiveId()} — the stable cross-dataset identity that replaced the raw
 * {@code Rule#getId()} in every grouping / audit / report key.
 *
 * <p>
 * The precedence contract matters because the two id sources are populated by disjoint populations:
 * file-loaded corpus rules carry only {@code Core.Id} (the package map key <em>is</em> that id and
 * no {@code id} member is emitted), while generated and CDISC-Library-sourced rules carry only a
 * synthetic {@code id}. Keying on either field alone silently mis-identifies one whole population —
 * which is precisely how the study-sensitivity collapse and the generation-time scope-skip audit
 * came to be inert.
 * </p>
 */
class RuleEffectiveIdTest
{

    @Test
    void coreIdWinsOverSyntheticId()
    {
        Rule r = new Rule();
        r.setId("uuid-1234");
        RuleCore core = new RuleCore();
        core.setId("CORE-000581");
        r.setCore(core);

        assertEquals("CORE-000581", r.effectiveId(),
                "Core.Id is the stable identity when both are present");
    }


    @Test
    void fallsBackToSyntheticIdWhenNoCore()
    {
        Rule r = new Rule();
        r.setId("uuid-1234");

        assertNull(r.getCore(), "precondition: no Core block");
        assertEquals("uuid-1234", r.effectiveId(),
                "a generated rule with no Core falls back to its synthetic id");
    }


    @Test
    void fallsBackToSyntheticIdWhenCoreCarriesNoId()
    {
        Rule r = new Rule();
        r.setId("uuid-1234");
        r.setCore(new RuleCore());

        assertEquals("uuid-1234", r.effectiveId(),
                "a Core block without an Id does not shadow the synthetic id");
    }


    @Test
    void nullWhenNeitherIdIsPresent()
    {
        assertNull(new Rule().effectiveId(), "a rule with no identity at all yields null");
    }


    /**
     * The regression this whole repair exists for: a rule loaded the way production loads it
     * carries <em>no</em> {@code id}, so every {@code getId()}-keyed feature saw {@code null}.
     */
    @Test
    void ruleLoadedFromAPackageCarriesNoRawIdButHasAnEffectiveId() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "FDA-SD1020": {
                      "Core": { "Id": "FDA-SD1020", "Status": "Published" },
                      "Sensitivity": "Dataset",
                      "Description": "DM must be present",
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM dataset is missing" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule loaded = pkg.getRules().get("FDA-SD1020");

        assertNull(loaded.getId(),
                "a package-loaded rule carries no `id` — the map key IS the Core.Id");
        assertEquals("FDA-SD1020", loaded.effectiveId(),
                "effectiveId() still identifies it, which getId() could not");
    }
}
