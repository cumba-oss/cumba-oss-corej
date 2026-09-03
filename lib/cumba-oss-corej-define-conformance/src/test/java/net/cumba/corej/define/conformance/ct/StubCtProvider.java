package net.cumba.corej.define.conformance.ct;

/*
 * ⚑ DUPLICATED, deliberately — see the copy in
 * lib/cumba-oss-cdisc-rules/src/test/.../ct/StubCtProvider.java, which serves the corpus-driven
 * tests that moved there with the corpus (PLAN-rules-module-consolidation D12). This copy stays
 * because ct/CtKindsTest, an engine unit test, still needs it here.
 */

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic in-memory {@link CtProvider} for the CT-gated rule tests: one non-extensible
 * codelist (Sex, {@code C66731}), one extensible codelist (Unit, {@code C71620}), plus the two
 * fixed-c-code Define-CT codelists the P5 rules name explicitly — STDNAM ({@code C170452},
 * extensible) and GNRLOBSC ({@code C103329}, non-extensible). Name lookup knows the same four
 * codelists by their CT names.
 */
public final class StubCtProvider implements CtProvider
{

    private static final CtCodelist SEX = new CtCodelist("C66731", false,
            Map.of("F", "C16576", "M", "C20197", "U", "C17998"));

    private static final CtCodelist UNIT = new CtCodelist("C71620", true,
            Map.of("mg", "C28253", "mL", "C28254"));

    private static final CtCodelist STDNAM = new CtCodelist("C170452", true, Map.of("SDTMIG",
            "C170455", "SENDIG", "C170456", "ADaMIG", "C170552", "CDISC/NCI", "C163415"));

    private static final CtCodelist GNRLOBSC = new CtCodelist("C103329", false,
            Map.of("EVENTS", "C103372", "FINDINGS", "C103373", "INTERVENTIONS", "C103374",
                    "SPECIAL PURPOSE", "C103377"));

    private static final Map<String, CtCodelist> BY_C_CODE = Map.of(SEX.cCode(), SEX, UNIT.cCode(),
            UNIT, STDNAM.cCode(), STDNAM, GNRLOBSC.cCode(), GNRLOBSC);

    private static final Map<String, CtCodelist> BY_NAME = Map.of("Sex", SEX, "Unit", UNIT,
            "Standard Name", STDNAM, "General Observation Class", GNRLOBSC);

    @Override
    public Optional<CtCodelist> codelistByCCode(String aCCode)
    {
        return Optional.ofNullable(BY_C_CODE.get(aCCode));
    }


    @Override
    public Optional<CtCodelist> codelistByName(String aName)
    {
        return Optional.ofNullable(BY_NAME.get(aName));
    }

}
