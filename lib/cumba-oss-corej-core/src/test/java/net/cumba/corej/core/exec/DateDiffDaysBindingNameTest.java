package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A {@code $}-binding as {@code date_diff_days}' {@code name} operand
 * ({@code PLAN-ruletest-scenario-channels} item 3) — first corpus use in the un-parked
 * {@code CDISC-SEND-0202} / {@code -0203}, whose draft reads the detection date of the TF row's own
 * mass ({@code $own_date}: the earliest {@code CLDTC} of the CL group keyed by {@code USUBJID} and
 * the sided {@code {left: TFSPID, right: CLSPID}} member) and subtracts the subject's earliest dose
 * ({@code $first_dose}). {@link DateDiffDaysTest} pins only a computed <em>expression</em> there
 * ({@code upper(TFDTC)}); this pins the binding form end to end through the loader and
 * {@link RuleRunner}: it loads (stage A takes a date-typed binding as the minuend), and every row's
 * day count is keyed by <b>that row's</b> group on both operands — a subject-only key, or a
 * first-row read, gives different numbers on the fixture.
 */
class DateDiffDaysBindingNameTest
{

    /**
     * The draft's R4 bindings ({@code send-0202-0203.md}): the masses of each domain, the other
     * domain's earliest date and its day difference to this one's. PM is not supplied in the
     * fixture, so every mass is CL-only and the R4 split never removes one.
     */
    private static final String R4_BINDINGS = ",{\"name\":\"$own_masses\",\"expression\":"
            + "\"distinct([USUBJID, CLSPID], domain=CL, filter=(not empty(CLSPID)))\"},"
            + "{\"name\":\"$other_masses\",\"expression\":"
            + "\"distinct([USUBJID, PMSPID], domain=PM, filter=(not empty(PMSPID)))\"},"
            + "{\"name\":\"$other_date\",\"expression\":\"min_date(PMDTC, domain=PM,"
            + " group=[USUBJID, {left: TFSPID, right: PMSPID}], filter=(not empty(PMSPID)))\"},"
            + "{\"name\":\"$other_minus_own\",\"expression\":"
            + "\"date_diff_days($other_date, $own_date)\"}";

    /**
     * The draft CDISC-SEND-0202 Check as written in {@code send-0202-0203.md} (the {@code -A}
     * sibling's first dose: the subject's own, no pool).
     */
    private static final String DRAFT_CHECK = "not empty(TFSPID) and not empty(TFDETECT)"
            + " and tuple(USUBJID, TFSPID) in $own_masses and is_complete_date(trim($own_date))"
            + " and (tuple(USUBJID, TFSPID) not in $other_masses"
            + " or (not empty($other_minus_own) and $other_minus_own >= 0))"
            + " and TFDETECT != $days_to_detection";

    /** The draft CDISC-SEND-0202 day-count bindings and the given Check. */
    private static Rule send0202(String aCheck)
    {
        return send0202(aCheck, "");
    }


    /** The draft CDISC-SEND-0202 day-count bindings, {@code aMoreBindings} and the given Check. */
    private static Rule send0202(String aCheck, String aMoreBindings)
    {
        String json = "{\"rules\":{\"X-0202\":{\"Core\":{\"Id\":\"X-0202\"}," + "\"Bindings\":["
                + "{\"name\":\"$own_date\",\"expression\":\"min_date(CLDTC, domain=CL,"
                + " group=[USUBJID, {left: TFSPID, right: CLSPID}], filter=(not empty(CLSPID)))\"},"
                + "{\"name\":\"$first_dose\",\"expression\":"
                + "\"min_date(EXSTDTC, domain=EX, group=[USUBJID])\"},"
                + "{\"name\":\"$days_to_detection\",\"expression\":"
                + "\"date_diff_days($own_date, $first_dose) + 1\"}" + aMoreBindings + "],"
                + "\"Check\":{\"expression\":\"" + aCheck + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"USUBJID\",\"TFSPID\",\"$days_to_detection\",\"TFDETECT\"]}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("X-0202");
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException("bad test fixture", e);
        }
    }


    /**
     * S1 has two masses, S2 one, S3's mass has no CL record. TFDETECT is right for S1's two masses
     * and wrong for S2's.
     */
    private static IDataTable tf()
    {
        return RealTableFixture.of("TF").str("USUBJID", "S1", "S1", "S2", "S3")
                .str("TFSPID", "MASS A", "MASS B", "MASS A", "MASS A")
                .lng("TFDETECT", 10L, 12L, 99L, 5L).build();
    }


    /**
     * S1/MASS A's earliest date is its SECOND record (01-10, not 01-15); S1/MASS B is 01-12 — so a
     * subject-only key would read 01-10 for both of S1's masses. S1's blank-CLSPID record (2019-12)
     * is filtered out; S2's date carries a time (the day count is at day level). S2's MASS A is not
     * S1's: the subject is part of the key.
     */
    private static IDataTable cl()
    {
        return RealTableFixture.of("CL").str("USUBJID", "S1", "S1", "S1", "S1", "S2")
                .str("CLSPID", "MASS A", "MASS A", "MASS B", "", "MASS A").str("CLDTC",
                        "2020-01-15", "2020-01-10", "2020-01-12", "2019-12-01", "2020-02-01T08:30")
                .build();
    }


    /** S1's earliest EXSTDTC is its SECOND row (01-01); S2's and S3's only one. */
    private static IDataTable ex()
    {
        return RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2", "S3")
                .str("EXSTDTC", "2020-01-05", "2020-01-01", "2020-01-20", "2020-01-01").build();
    }


    private static DatasetResolver resolver(IDataTable... tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                for (IDataTable t : tables)
                {
                    if (t.getMetaData().getName().equalsIgnoreCase(name))
                    {
                        return t;
                    }
                }
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                Set<String> names = new LinkedHashSet<>();
                for (IDataTable t : tables)
                {
                    names.add(t.getMetaData().getName());
                }
                return names;
            }
        };
    }


    /** {@code USUBJID/TFSPID=$days_to_detection} of every row the Check fires on. */
    private static List<String> fired(String aCheck)
    {
        return fired(send0202(aCheck));
    }


    /** {@code USUBJID/TFSPID=$days_to_detection} of every row the rule fires on. */
    private static List<String> fired(Rule aRule)
    {
        IDataTable target = tf();
        RuleExecutionResult result = RuleRunnerCalls.execute(aRule, target,
                resolver(target, cl(), ex()), "TF");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().stream().map(v -> v.getValues().get("USUBJID") + "/"
                + v.getValues().get("TFSPID") + "=" + v.getValues().get("$days_to_detection"))
                .toList();
    }


    @Test
    void everyRowsDayCountIsKeyedByItsOwnMassAndSubject()
    {
        // S1/A: 01-10 - 01-01 + 1 = 10; S1/B: 01-12 - 01-01 + 1 = 12 (a subject-only key: 10);
        // S2/A: 02-01 - 01-20 + 1 = 13 (the time dropped); S3/A: no CL date => a missing.
        assertEquals(List.of("S1/MASS A=10", "S1/MASS B=12", "S2/MASS A=13", "S3/MASS A=."),
                fired("not empty(TFSPID)"));
    }


    @Test
    void theDraftCheckJudgesOnlyAMassWithACompleteClDate()
    {
        // The draft Check gates on tuple(USUBJID, TFSPID) in $own_masses and a complete
        // $own_date: S1's two masses match their day counts, S2's 99 is reported against 13, and
        // S3's mass — no CL record — is not judged at all (EC-45 is about an unusable FIRST DOSE,
        // which is reported; a mass with no detection date is not).
        assertEquals(List.of("S2/MASS A=13"), fired(send0202(DRAFT_CHECK, R4_BINDINGS)));
        // The sensitivity arm: the comparison without the own-mass gate also reports S3 against
        // its missing day count, so the expectation above is the gate's, not the fixture's.
        assertEquals(List.of("S2/MASS A=13", "S3/MASS A=."),
                fired("not empty(TFDETECT) and TFDETECT != $days_to_detection"));
    }
}
