package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.run.RunStandard;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeStandard;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * T2 fold, H1 of {@code PLAN-merged-evaluation-table}: the run standard reaches the merge. Before,
 * {@code MergedEvaluation.construct} never set {@code MergeRequest.standard}, so every
 * {@code SUPP--} and {@code RELREC} reading fell back to the POOLID inference — a SEND study
 * without a {@code POOLID} column read as SDTM (a subject-less supplemental row, OD-2, qualified
 * nothing), an SDTM study carrying a blank one read as SEND (the row qualified every record).
 *
 * <p>
 * The standard travels on the dataset's {@link JoinCache} ({@code LibraryValidator.Builder}
 * {@code mergeStandard} → the per-dataset cache → {@code construct}); an execution without a cache
 * keeps the inference. The fixture is the case-9 shape of §3.5.3: CL rows of two subjects with
 * {@code CLSEQ} 1 and one subject-less SUPPCL row {@code (CLSEQ, 1, CLX = Y)}; the rule fires on a
 * record whose {@code CLX} is not {@code Y}.
 * </p>
 */
class MergeStandardPlumbingTest
{

    private static Rule rule()
    {
        String json = "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Requirements\":{\"Variables\":{\"All\":[\"CLSEQ\",\"CLX\"]}},"
                + "\"Check\":{\"expression\":\"CLX != \\\"Y\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(json, e);
        }
    }


    private static IDataTable cl()
    {
        return RealTableFixture.of("CL").str("STUDYID", "S1", "S1").str("DOMAIN", "CL", "CL")
                .str("USUBJID", "A1", "A2").lng("CLSEQ", 1L, 1L).build();
    }


    /** The subject-less row; {@code aPoolid} adds a (blank) POOLID column to the SUPP. */
    private static IDataTable suppcl(boolean aPoolid)
    {
        RealTableFixture f = RealTableFixture.of("SUPPCL").str("STUDYID", "S1").str("RDOMAIN", "CL")
                .str("USUBJID", "");
        if (aPoolid)
        {
            f = f.str("POOLID", "");
        }
        return f.str("IDVAR", "CLSEQ").str("IDVARVAL", "1").str("QNAM", "CLX").str("QVAL", "Y")
                .build();
    }


    private static int violations(IDataTable aCl, IDataTable aSupp, @Nullable JoinCache aCache)
    {
        RuleExecutionResult r = RuleRunnerCalls.execute(rule(), aCl,
                RealTables.inventoryOf(aCl, aSupp), "CL", null, aCache);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), String.valueOf(r));
        return r.getViolations().size();
    }


    @Test
    void aSendRunWithoutPoolidReadsTheSubjectLessRowStudyWide()
    {
        // SEND: every record with CLSEQ 1 is qualified (OD-2) — no row fires
        assertEquals(0, violations(cl(), suppcl(false), new JoinCache(null, MergeStandard.SEND)));
        // the inference reads no POOLID column as SDTM — the row qualifies nothing, both fire
        assertEquals(2,
                violations(cl(), suppcl(false), new JoinCache(null, MergeStandard.UNKNOWN)));
        assertEquals(2, violations(cl(), suppcl(false), null), "no cache: the inference");
        assertEquals(2, violations(cl(), suppcl(false), new JoinCache(null, MergeStandard.SDTM)));
    }


    @Test
    void anSdtmRunWithABlankPoolidColumnIsNotReadAsSend()
    {
        // the inference reads the POOLID column as SEND: the subject-less row qualifies both
        assertEquals(0, violations(cl(), suppcl(true), new JoinCache(null, MergeStandard.UNKNOWN)));
        // SDTM has no study-wide supplemental row (JKM R6): both fire
        assertEquals(2, violations(cl(), suppcl(true), new JoinCache(null, MergeStandard.SDTM)));
        assertEquals(0, violations(cl(), suppcl(true), new JoinCache(null, MergeStandard.SEND)));
    }


    @Test
    void theValidatorBuilderCarriesTheStandardToTheDatasetsCache()
    {
        assertEquals(0, findings(MergeStandard.SEND), "SEND: the row qualifies both records");
        assertEquals(2, findings(MergeStandard.SDTM));
        assertEquals(2, findings(null), "the default is UNKNOWN: no POOLID column, read as SDTM");
    }


    private static int findings(@Nullable MergeStandard aStandard)
    {
        LibraryValidator.Builder b = LibraryValidator.builder().provider(new StubMetadataProvider())
                .rules(List.of(rule())).targetDataset("CL", "cl.xpt", cl())
                .referenceDataset("SUPPCL", suppcl(false));
        if (aStandard != null)
        {
            b.mergeStandard(aStandard);
        }
        ValidationReport report = b.validate();
        // a dataset no rule fired on has no report member: 0 findings
        if (report.getMembers().isEmpty())
        {
            return 0;
        }
        assertEquals(1, report.getMembers().size());
        ValidationReportMember cl = report.getMembers().get(0);
        assertEquals("CL", cl.getDomain());
        // one finding per rule, its rows the records that fired
        return cl.getFindings().stream().mapToInt(f -> f.getRowCount()).sum();
    }


    @Test
    void theRunStandardMapsOntoTheMergeStandard()
    {
        assertSame(MergeStandard.SEND,
                StudyValidationService.mergeStandardOf(RunStandard.of("standards/sendig/3-1-1")));
        assertSame(MergeStandard.SEND, StudyValidationService
                .mergeStandardOf(RunStandard.of("standards/sendig/dart-1-1")));
        assertSame(MergeStandard.SDTM,
                StudyValidationService.mergeStandardOf(RunStandard.of("standards/sdtmig/3-4")));
        assertSame(MergeStandard.UNKNOWN,
                StudyValidationService.mergeStandardOf(RunStandard.of("adam/adamig-1-3")),
                "an ADaM run keeps the inference");
        assertSame(MergeStandard.UNKNOWN,
                StudyValidationService.mergeStandardOf(RunStandard.of("standards/tig/1-0/sdtm")),
                "a TIG run names no SDTM / SEND standard: the inference (recorded)");
    }
}
