package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code date_diff_days(name, reference)} through the loader and {@link RuleRunner} (runbook W2b,
 * {@code PLAN-operation-replacements} §8): the day count without {@code +1}, an offset as
 * arithmetic, the retired operation's Mode 2 written as a named {@code min_date} / {@code max_date}
 * argument (EC-46 / EC-51 carried by the aggregate), the EC-45 carry (a reference with no answer is
 * a missing and a populated target compared with it fires), a computed minuend read per row (the
 * retired {@code keyCols} defect), its Mode 3 written as a declared sided join, and the retired
 * keywords' load errors.
 */
class DateDiffDaysTest
{

    /**
     * Loads one rule; {@code joinAndRequirements} is {@code null} or the JSON members
     * ({@code "Match_Datasets":…, "Requirements":…}) a keyed join needs — the join-key authoring
     * gate: every keyed join declares its key.
     */
    private static Rule load(String joinAndRequirements, String binding, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":{"
                    + "\"Core\":{\"Id\":\"X-1\"},"
                    + (joinAndRequirements == null ? "" : joinAndRequirements + ",")
                    + "\"Bindings\":[{\"name\":\"$d\",\"expression\":\"" + binding + "\"}],"
                    + "\"Check\":{\"expression\":\"" + check + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\",\"$d\"]}}}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + binding, e);
        }
    }


    private static Rule loaded(String binding, String check)
    {
        Rule rule = load(null, binding, check);
        assertNull(rule.getLoadError(), "expected the binding to load: " + rule.getLoadError());
        return rule;
    }


    private static String loadError(String binding, String check)
    {
        Rule rule = load(null, binding, check);
        assertNotNull(rule.getLoadError(), "expected a load error for " + binding);
        return rule.getLoadError();
    }


    /** The study: resolves each table by name and enumerates them (Requirements need both). */
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


    private static RuleExecutionResult run(Rule rule, IDataTable primary, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = primary;
        System.arraycopy(others, 0, all, 1, others.length);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, primary, resolver(all),
                primary.getMetaData().getName());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result;
    }


    /** The rows (1-based USUBJID values) a row-based Check fires on. */
    private static List<String> fired(String binding, String check, IDataTable primary,
            IDataTable... others)
    {
        return run(loaded(binding, "not empty(USUBJID) and (" + check + ")"), primary, others)
                .getViolations().stream().map(v -> String.valueOf(v.getValues().get("USUBJID")))
                .toList();
    }


    /** TF: three records of subject S1 plus one of S2, with a same-record reference column. */
    private static IDataTable tf()
    {
        return RealTableFixture.of("TF").str("USUBJID", "S1", "S1", "S1", "S2")
                .str("TFDTC", "2020-01-11", "2019-12-30", "2020-01", "2020-03-06")
                .str("TFREF", "2020-01-01", "2020-01-01", "2020-01-01", null)
                .lng("TFOFF", 1L, 0L, 1L, 1L).build();
    }


    /** EX: S1's earliest EXSTDTC is on its SECOND row; S2's only one is 2020-03-01. */
    private static IDataTable ex()
    {
        return RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2")
                .str("EXSTDTC", "2020-01-05", "2020-01-01", "2020-03-01").build();
    }

    // -----------------------------------------------------------------------
    // the day count
    // -----------------------------------------------------------------------


    @Test
    void theDayCountIsFromTheReferenceToTheDateWithoutPlusOne()
    {
        // 2020-01-11 - 2020-01-01 = 10 (no study-day +1); 2019-12-30 is before: -2, no day-0 skip.
        assertEquals(List.of("S1"), fired("date_diff_days(TFDTC, TFREF)", "$d == 10", tf()));
        assertEquals(List.of("S1"), fired("date_diff_days(TFDTC, TFREF)", "$d == -2", tf()));
    }


    @Test
    void anOffsetIsArithmetic()
    {
        // The retired offset="1" / offset="RPRFDY" is `+ 1` / `+ COLUMN` on the function surface.
        assertEquals(List.of("S1"), fired("date_diff_days(TFDTC, TFREF) + 1", "$d == 11", tf()));
        assertEquals(List.of("S1", "S1"),
                fired("date_diff_days(TFDTC, TFREF) + TFOFF", "$d == 11 or $d == -2", tf()));
    }


    @Test
    void aMissingOrShortInputAnswersAMissing()
    {
        // S1 row 3: a partial TFDTC (present, short) => the computed missing; S2: a missing
        // TFREF => the carried missing. Both are `empty`, neither is a number.
        assertEquals(List.of("S1", "S2"), fired("date_diff_days(TFDTC, TFREF)", "empty($d)", tf()));
        assertEquals(List.of("S1", "S1"),
                fired("date_diff_days(TFDTC, TFREF)", "not empty($d)", tf()));
    }

    // -----------------------------------------------------------------------
    // Mode 2, composed: the named aggregation
    // -----------------------------------------------------------------------


    @Test
    void aMinDateReferenceIsTheGroupsEarliestNotItsFirstRow()
    {
        // S1's earliest EXSTDTC is 2020-01-01 (second EX row): 2020-01-11 => 10, 2019-12-30 => -2.
        // A first-row reference (2020-01-05) would give 6 and -6. S2: 2020-03-06 - 2020-03-01 = 5.
        String binding = "date_diff_days(TFDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID]))";
        assertEquals(List.of("S1", "S1", "S2"),
                fired(binding, "$d == 10 or $d == -2 or $d == 5", tf(), ex()));
        assertEquals(List.of(), fired(binding, "$d == 6 or $d == -6", tf(), ex()));
    }


    @Test
    void aMaxDateReferenceIsTheGroupsLatest()
    {
        // The retired reference_extreme="max": S1's latest EXSTDTC is 2020-01-05 => 6 and -6.
        assertEquals(List.of("S1", "S1"),
                fired("date_diff_days(TFDTC, max_date(EXSTDTC, domain=EX, group=[USUBJID]))",
                        "$d == 6 or $d == -6", tf(), ex()));
    }


    @Test
    void anIndeterminateReferenceIsAMissingAndAPopulatedTargetFires()
    {
        // EC-46 carried by the aggregate: S1's partial 2020-01 could be earlier than 2020-01-05,
        // so its earliest EXSTDTC is undeterminable and the day count has no value. EC-45: a
        // populated derived day compared with it is REPORTED (`!=`), never skipped.
        IDataTable exPartial = RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2")
                .str("EXSTDTC", "2020-01-05", "2020-01", "2020-03-01").build();
        String binding = "date_diff_days(TFDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID]))";
        assertEquals(List.of("S1", "S1", "S1"), fired(binding, "empty($d)", tf(), exPartial));
        IDataTable target = RealTableFixture.of("TF").str("USUBJID", "S1", "S2")
                .str("TFDTC", "2020-01-11", "2020-03-06").lng("TFDETECT", 6L, 5L).build();
        assertEquals(List.of("S1"), fired(binding, "TFDETECT != $d", target, exPartial),
                "S1's populated TFDETECT is reported against the missing day; S2 matches");
    }


    @Test
    void theMissingValuesDispositionTravelsWithTheAggregate()
    {
        // EC-51 Half B on the named aggregation: S1's blank EXSTDTC makes its earliest date
        // undeterminable only under `indeterminate`.
        IDataTable exBlank = RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2")
                .str("EXSTDTC", "2020-01-01", "", "2020-03-01").build();
        assertEquals(List.of("S1"),
                fired("date_diff_days(TFDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID]))",
                        "$d == 10", tf(), exBlank));
        String indeterminate = "date_diff_days(TFDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID],"
                + " missing_values=\\\"indeterminate\\\"))";
        assertEquals(List.of("S1", "S1", "S1"), fired(indeterminate, "empty($d)", tf(), exBlank));
        // and the EC-51 OQ3 polarity gate sees the declaring call through the enclosing function
        String error = loadError(indeterminate, "$d == 10");
        assertTrue(error.contains("positive-polarity"), error);
    }


    @Test
    void aPrimaryKeyWithNoGroupAnswersAMissing()
    {
        // EC-45's no-group arm (unreachable through the shipped rules' inner Match_Datasets join,
        // which drops such a row first): S3 has no EX record, so its reference and its day count
        // are missing, and a populated target compared with it fires.
        IDataTable target = RealTableFixture.of("TF").str("USUBJID", "S1", "S3")
                .str("TFDTC", "2020-01-11", "2020-01-11").lng("TFDETECT", 10L, 10L).build();
        assertEquals(List.of("S3"),
                fired("date_diff_days(TFDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID]))",
                        "TFDETECT != $d", target, ex()));
    }


    @Test
    void anAbsentReferenceDatasetOrColumnAnswersAMissingOnEveryRow()
    {
        // EC-45 B4 / B5 on the composed form: nothing skips inside the algebra; applicability is
        // Requirements' job.
        IDataTable target = RealTableFixture.of("TF").str("USUBJID", "S1", "S2")
                .str("TFDTC", "2020-01-11", "2020-03-06").lng("TFDETECT", 10L, 5L).build();
        String binding = "date_diff_days(TFDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID]))";
        assertEquals(List.of("S1", "S2"), fired(binding, "TFDETECT != $d", target));
        IDataTable exNoDate = RealTableFixture.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSEQ", "1", "1").build();
        assertEquals(List.of("S1", "S2"), fired(binding, "TFDETECT != $d", target, exNoDate));
    }

    // -----------------------------------------------------------------------
    // the computed minuend, Mode 3, the retired keywords
    // -----------------------------------------------------------------------


    @Test
    void aComputedMinuendIsReadPerRow()
    {
        // P-Q7's computed-target defect: the operation put its target NAME into the GroupedResult
        // key, so a computed target (materialised on a private copy) missed on every row. The
        // function reads its argument vector per row, with no key at all.
        assertEquals(List.of("S1"), fired("date_diff_days(upper(TFDTC), TFREF)", "$d == 10", tf()));
        assertEquals(List.of("S1", "S1", "S2"),
                fired("date_diff_days(upper(TFDTC), min_date(EXSTDTC, domain=EX, group=[USUBJID]))",
                        "$d == 10 or $d == -2 or $d == 5", tf(), ex()));
    }


    @Test
    void theForeignMinuendIsADeclaredSidedJoin()
    {
        // The retired Mode 3 (minuend_domain="PM", minuend_match=[USUBJID, "--SPID"]): the PM
        // record whose PMSPID matches the TF row's TFSPID, declared as a sided Match_Datasets key
        // and read as PM.PMDTC.
        IDataTable target = RealTableFixture.of("TF").str("USUBJID", "S1", "S1")
                .str("TFSPID", "M1", "M2").lng("TFDETECT", 16L, 99L).build();
        IDataTable pm = RealTableFixture.of("PM").str("USUBJID", "S1", "S1")
                .str("PMSPID", "M2", "M1").str("PMDTC", "2020-02-11", "2020-01-20").build();
        Rule rule = load(
                "\"Match_Datasets\":[{\"Name\":\"PM\",\"Keys\":[\"USUBJID\","
                        + "{\"left\":\"TFSPID\",\"right\":\"PMSPID\"}]}],"
                        + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],\"All_Or_None\":"
                        + "[[\"USUBJID\",\"PM.USUBJID\"],[\"TFSPID\",\"PM.PMSPID\"]]}}",
                "date_diff_days(PM.PMDTC, min_date(EXSTDTC, domain=EX, group=[USUBJID])) + 1",
                "TFDETECT != $d");
        assertNull(rule.getLoadError(), rule.getLoadError());
        // M1: 2020-01-20 - 2020-01-01 + 1 = 20 (TFDETECT 16 fires); M2: 2020-02-11 => 42 (99 fires)
        // — both fire; the discriminating pin is the reported value of each row.
        List<String> days = run(rule, target, pm, ex()).getViolations().stream()
                .map(v -> String.valueOf(v.getValues().get("$d"))).toList();
        assertEquals(List.of("20", "42"), days);
    }


    @Test
    void theRetiredKeywordsAndAQuotedNameAreLoadErrors()
    {
        String check = "empty($d)";
        for (String retired : List.of(
                "date_diff_days(TFDTC, domain=\\\"EX\\\", reference=\\\"EXSTDTC\\\", group=[USUBJID])",
                "date_diff_days(TFDTC, TFREF, offset=\\\"1\\\")",
                "date_diff_days(TFDTC, TFREF, reference_extreme=\\\"max\\\")",
                "date_diff_days(TFDTC, TFREF, minuend_domain=\\\"PM\\\")",
                // missing_values= belongs to the aggregate (min_date / max_date), never to the
                // day count: on a same-record reference it would have changed nothing
                "date_diff_days(TFDTC, TFREF, missing_values=\\\"indeterminate\\\")"))
        {
            assertNotNull(load(null, retired, check).getLoadError(), retired);
        }
        String quoted = loadError("date_diff_days(\\\"TFDTC\\\", TFREF)", check);
        assertTrue(quoted.contains("a quoted name is a string, never a column"), quoted);
    }

}
