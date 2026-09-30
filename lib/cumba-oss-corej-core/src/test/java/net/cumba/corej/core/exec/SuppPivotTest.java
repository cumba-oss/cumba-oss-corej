package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The SUPP pivot by reference ({@link SuppPivot}, {@code PLAN-operation-replacements} §2.3, C1
 * ruled (a)): which qualifier a parent's bare name resolves to, which record each row qualifies,
 * and when the pivot answers nothing — while the table's own column set stays untouched.
 */
class SuppPivotTest
{

    /** PC: S1/1, S1/2, S2/1 — a numeric PCSEQ so the IDVARVAL text must coerce (J5). */
    private static IDataTable pc()
    {
        return RealTableFixture.of("PC").str("DOMAIN", "PC", "PC", "PC")
                .str("USUBJID", "S1", "S1", "S2").lng("PCSEQ", 1L, 2L, 1L)
                .str("PCSTRESC", "BLQ", "4.1", "BLQ").build();
    }

    /** One SUPPPC row: {@code usubjid | idvar | idvarval | qnam | qval}. */
    private record SuppRow(String usubjid, String idvar, String idvarval, String qnam, String qval)
    {
    }

    private static IDataTable suppPc(SuppRow... rows)
    {
        List<String> usubjid = new ArrayList<>();
        List<String> idvar = new ArrayList<>();
        List<String> idvarval = new ArrayList<>();
        List<String> qnam = new ArrayList<>();
        List<String> qval = new ArrayList<>();
        List<String> rdomain = new ArrayList<>();
        for (SuppRow r : rows)
        {
            rdomain.add("PC");
            usubjid.add(r.usubjid());
            idvar.add(r.idvar());
            idvarval.add(r.idvarval());
            qnam.add(r.qnam());
            qval.add(r.qval());
        }
        return RealTableFixture.of("SUPPPC").str("RDOMAIN", rdomain.toArray(String[]::new))
                .str("USUBJID", usubjid.toArray(String[]::new))
                .str("IDVAR", idvar.toArray(String[]::new))
                .str("IDVARVAL", idvarval.toArray(String[]::new))
                .str("QNAM", qnam.toArray(String[]::new)).str("QVAL", qval.toArray(String[]::new))
                .build();
    }


    private static DatasetResolver resolver(IDataTable primary, @Nullable IDataTable supp)
    {
        return name -> "SUPPPC".equalsIgnoreCase(name) ? supp
                : primary.getMetaData().getName().equalsIgnoreCase(name) ? primary : null;
    }


    private static EvaluationContext ctx(IDataTable primary, @Nullable IDataTable supp)
    {
        return EvaluationContext.builder().table(primary).ruleId("T-1")
                .datasetResolver(resolver(primary, supp)).build();
    }


    private static EvaluationContext ctx(IDataTable primary, @Nullable IDataTable supp,
            boolean suppMerge)
    {
        return EvaluationContext.builder().table(primary).ruleId("T-1").suppMerge(suppMerge)
                .datasetResolver(resolver(primary, supp)).build();
    }


    private static String cell(IDataTableColumn column, int row)
    {
        IDataValue dv = column.getDataValue(row);
        return dv.isMissingOrInvalid() ? "<missing>" : dv.getValueAsString();
    }


    private static IDataTableColumn column(EvaluationContext ctx, String name)
    {
        IDataTableColumn column = SuppPivot.qualifierColumn(ctx, name);
        assertNotNull(column, name + " is delivered by SUPPPC");
        return column;
    }


    @Test
    void aRecordLevelQualifierResolvesForTheMatchedRecordOnly()
    {
        IDataTable pc = pc();
        EvaluationContext ctx = ctx(pc, suppPc(new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "0.5")));
        IDataTableColumn column = column(ctx, "PCCALCN");
        assertEquals("0.5", cell(column, 0), "S1/PCSEQ=1 carries the qualifier");
        assertEquals("", cell(column, 1),
                "S1/PCSEQ=2 has no qualifier row: the present blank, never a missing");
        assertEquals("", cell(column, 2), "S2/PCSEQ=1 has no qualifier row");
        assertEquals(3, column.getRowCount(), "one cell per parent row");
        assertEquals(4, pc.getMetaData().getColumnCount(),
                "the table's own column set is untouched: the qualifier is not a variable of PC");
        assertTrue(SuppPivot.exists(ctx, "PCCALCN"));
        assertSame(column, SuppPivot.qualifierColumn(ctx, "PCCALCN"), "memoised per context");
    }


    @Test
    void theIdvarvalTextCoercesToTheNumericParentColumn()
    {
        // " 1" (SAS-padded) and "1.0" both name the numeric PCSEQ 1 (ChildMatchIndex J5 / D4-R5).
        assertEquals("a",
                cell(column(ctx(pc(), suppPc(new SuppRow("S2", "PCSEQ", " 1", "PCCALCN", "a"))),
                        "PCCALCN"), 2));
        IDataTableColumn decimal = column(
                ctx(pc(), suppPc(new SuppRow("S1", "PCSEQ", "1.0", "PCCALCN", "b"))), "PCCALCN");
        assertEquals("b", cell(decimal, 0));
        assertEquals("", cell(decimal, 1));
    }


    @Test
    void aBlankIdvarQualifiesEveryRecordOfTheSubject()
    {
        IDataTableColumn column = column(
                ctx(pc(), suppPc(new SuppRow("S1", "", "", "PCCMT", "see protocol"))), "PCCMT");
        assertEquals("see protocol", cell(column, 0));
        assertEquals("see protocol", cell(column, 1));
        assertEquals("", cell(column, 2), "another subject is not qualified");
    }


    @Test
    void aRecordLevelRowWinsOverASubjectLevelOneAndTheFirstDuplicateWins()
    {
        IDataTableColumn column = column(ctx(pc(),
                suppPc(new SuppRow("S1", "", "", "PCCALCN", "subject"),
                        new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "first"),
                        new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "second"))),
                "PCCALCN");
        assertEquals("first", cell(column, 0),
                "record-level beats subject-level; the first duplicate row wins");
        assertEquals("subject", cell(column, 1),
                "a record without its own row falls back to the subject-level qualifier");
        assertEquals("", cell(column, 2));
    }


    @Test
    void theParentsOwnColumnWinsCaseInsensitively()
    {
        EvaluationContext ctx = ctx(pc(),
                suppPc(new SuppRow("S1", "PCSEQ", "1", "PCSTRESC", "overwrite?"),
                        new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "0.5")));
        assertNull(SuppPivot.qualifierColumn(ctx, "PCSTRESC"),
                "a QNAM naming a parent column answers nothing: the parent's value stands");
        assertNotNull(SuppPivot.qualifierColumn(ctx, "PCCALCN"));
    }


    @Test
    void aQnamNoRecordMatchesStillExists()
    {
        // The existence half: any SUPP row carrying the QNAM delivers it (the dotted pivot's
        // reading, OperatorRegistry.existsInSuppQnam), even when every cell is blank.
        EvaluationContext ctx = ctx(pc(), suppPc(new SuppRow("S9", "PCSEQ", "1", "PCCALCN", "x")));
        assertTrue(SuppPivot.exists(ctx, "PCCALCN"));
        IDataTableColumn column = column(ctx, "PCCALCN");
        assertEquals("", cell(column, 0));
        assertEquals("", cell(column, 1));
        assertEquals("", cell(column, 2));
    }


    @Test
    void aRecordLevelRowNamingAColumnTheParentLacksQualifiesNothing()
    {
        IDataTableColumn column = column(
                ctx(pc(), suppPc(new SuppRow("S1", "PCGRPID", "G1", "PCCALCN", "x"))), "PCCALCN");
        assertEquals("", cell(column, 0));
        assertEquals("", cell(column, 1));
    }


    @Test
    void nothingToPivotAnswersNull()
    {
        IDataTable pc = pc();
        assertNull(SuppPivot.qualifierColumn(ctx(pc, null), "PCCALCN"), "no SUPPPC dataset");
        assertFalse(SuppPivot.exists(ctx(pc, null), "PCCALCN"));
        // Combined review of runbook W2–W8, W2 L2: existence and the pivot read ONE parse. A
        // SUPPPC that carries the QNAM but names no record and no subject DELIVERS the qualifier
        // (var_exists says so too, as it always did) — and every record reads the blank, because
        // no row can qualify one. It used to answer null here while the existence scan said
        // "present", so a Requirements entry passed on a qualifier the Check could never read.
        IDataTable noShape = RealTableFixture.of("SUPPPC").str("QNAM", "PCCALCN").str("QVAL", "0.5")
                .build();
        IDataTableColumn blankOnly = SuppPivot.qualifierColumn(ctx(pc, noShape), "PCCALCN");
        assertNotNull(blankOnly, "SUPPPC without IDVAR/IDVARVAL still carries the QNAM");
        assertEquals("", cell(blankOnly, 0), "…but its rows qualify no record");
        assertNull(SuppPivot.qualifierColumn(
                ctx(pc, RealTableFixture.of("SUPPPC").str("QVAL", "0.5").build()), "PCCALCN"),
                "a SUPPPC without a QNAM column carries nothing");
        assertNull(SuppPivot.qualifierColumn(
                ctx(pc, suppPc(new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "x"))), "PCOTHER"),
                "a QNAM no SUPP row carries");
        assertNull(SuppPivot.qualifierColumn(
                ctx(pc, suppPc(new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "x")), false), "PCCALCN"),
                "Supp_Merge: false answers nothing");
        IDataTable suppPrimary = suppPc(new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "x"));
        assertNull(
                SuppPivot.qualifierColumn(EvaluationContext.builder().table(suppPrimary)
                        .datasetResolver(name -> suppPrimary).build(), "PCCALCN"),
                "a SUPP-- primary is never pivoted into itself");
    }


    @Test
    void theIndexIsBuiltOncePerSuppTableThroughTheSharedCache()
    {
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        IDataTable supp = suppPc(new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "0.5"));
        IDataTable pc = pc();
        for (int rule = 0; rule < 3; rule++)
        {
            EvaluationContext ctx = EvaluationContext.builder().table(pc).ruleId("T-" + rule)
                    .datasetResolver(resolver(pc, supp)).sharedIndexCache(cache).build();
            assertEquals("0.5", cell(column(ctx, "PCCALCN"), 0));
        }
        assertEquals(1, cache.suppQnamIndexBuildCount(),
                "the SUPP table is parsed once per run, whatever the number of rules");
    }

    // ------------------------------------------------------------------ pooled records (W2 H2)


    /** PC with SEND pooled records: pools P1 and P2, each PCSEQ 1, plus subject S1's record. */
    private static IDataTable pooledPc()
    {
        return RealTableFixture.of("PC").str("DOMAIN", "PC", "PC", "PC")
                .str("USUBJID", "", "", "S1").str("POOLID", "P1", "P2", "").lng("PCSEQ", 1L, 1L, 1L)
                .str("PCSTRESC", "BLQ", "BLQ", "BLQ").build();
    }

    /** One SUPPPC row with a POOLID: {@code usubjid | poolid | idvar | idvarval | qnam | qval}. */
    private record PooledSuppRow(String usubjid, String poolid, String idvar, String idvarval,
            String qnam, String qval)
    {
    }

    private static IDataTable pooledSuppPc(PooledSuppRow... rows)
    {
        String[] usubjid = new String[rows.length];
        String[] poolid = new String[rows.length];
        String[] idvar = new String[rows.length];
        String[] idvarval = new String[rows.length];
        String[] qnam = new String[rows.length];
        String[] qval = new String[rows.length];
        String[] rdomain = new String[rows.length];
        for (int i = 0; i < rows.length; i++)
        {
            rdomain[i] = "PC";
            usubjid[i] = rows[i].usubjid();
            poolid[i] = rows[i].poolid();
            idvar[i] = rows[i].idvar();
            idvarval[i] = rows[i].idvarval();
            qnam[i] = rows[i].qnam();
            qval[i] = rows[i].qval();
        }
        return RealTableFixture.of("SUPPPC").str("RDOMAIN", rdomain).str("USUBJID", usubjid)
                .str("POOLID", poolid).str("IDVAR", idvar).str("IDVARVAL", idvarval)
                .str("QNAM", qnam).str("QVAL", qval).build();
    }


    /**
     * Combined review of runbook W2–W8, W2 H2: SENDIG pooled records carry a blank {@code USUBJID}
     * and a populated {@code POOLID}, and so do their SUPP rows. Keyed on {@code USUBJID} alone,
     * one pool's record-level qualifier qualified the same PCSEQ of EVERY pool.
     */
    @Test
    void aRecordLevelQualifierOfOnePoolQualifiesThatPoolOnly()
    {
        IDataTable supp = pooledSuppPc(
                new PooledSuppRow("", "P1", "PCSEQ", "1", "PCCALCN", "0.05"));
        IDataTableColumn column = column(ctx(pooledPc(), supp), "PCCALCN");
        assertEquals("0.05", cell(column, 0), "pool P1, PCSEQ 1: qualified");
        assertEquals("", cell(column, 1), "pool P2, PCSEQ 1: NOT P1's qualifier");
        assertEquals("", cell(column, 2), "subject S1: not a pool");
    }


    /**
     * A pool-level row (blank {@code IDVAR}, {@code POOLID} set) qualifies every record of the
     * pool.
     */
    @Test
    void aPoolLevelQualifierQualifiesEveryRecordOfThePool()
    {
        IDataTable pc = RealTableFixture.of("PC").str("DOMAIN", "PC", "PC", "PC")
                .str("USUBJID", "", "", "").str("POOLID", "P2", "P2", "P1").lng("PCSEQ", 1L, 2L, 1L)
                .str("PCSTRESC", "BLQ", "BLQ", "BLQ").build();
        IDataTable supp = pooledSuppPc(new PooledSuppRow("", "P2", "", "", "PCCALCN", "0.07"));
        IDataTableColumn column = column(ctx(pc, supp), "PCCALCN");
        assertEquals("0.07", cell(column, 0));
        assertEquals("0.07", cell(column, 1));
        assertEquals("", cell(column, 2), "pool P1 is not pool P2");
    }


    /**
     * A subject-level SUPP row with BOTH {@code USUBJID} and {@code POOLID} blank names neither a
     * subject nor a pool and qualifies nothing — it used to be the qualifier of subject {@code ""}
     * and so of every pooled record.
     */
    @Test
    void aSubjectLevelRowNamingNeitherSubjectNorPoolQualifiesNothing()
    {
        IDataTable supp = pooledSuppPc(new PooledSuppRow("", "", "", "", "PCCALCN", "0.05"));
        IDataTableColumn column = column(ctx(pooledPc(), supp), "PCCALCN");
        assertEquals("", cell(column, 0));
        assertEquals("", cell(column, 1));
        assertEquals("", cell(column, 2));
    }


    /**
     * A record-level row with a blank subject key still reaches a parent record whose key is blank
     * too — the trial-design shape (a {@code SUPPTE} row: no subject, no pool, {@code ETCD} names
     * the record) is keyed exactly as before.
     */
    @Test
    void aRecordLevelRowWithoutSubjectOrPoolReachesARecordWithoutEither()
    {
        IDataTable te = RealTableFixture.of("TE").str("DOMAIN", "TE", "TE")
                .str("ETCD", "SCRN", "TRT").build();
        IDataTable supp = RealTableFixture.of("SUPPTE").str("RDOMAIN", "TE").str("USUBJID", "")
                .str("IDVAR", "ETCD").str("IDVARVAL", "TRT").str("QNAM", "ELEMNOTE")
                .str("QVAL", "note").build();
        EvaluationContext ctx = EvaluationContext.builder().table(te).ruleId("T-1")
                .datasetResolver(name -> "SUPPTE".equalsIgnoreCase(name) ? supp
                        : "TE".equalsIgnoreCase(name) ? te : null)
                .build();
        IDataTableColumn column = column(ctx, "ELEMNOTE");
        assertEquals("", cell(column, 0));
        assertEquals("note", cell(column, 1));
    }


    /**
     * A parent without a {@code POOLID} column keys as before: the SUPP side's {@code POOLID} half
     * is {@code ""} for a subject row and a pooled SUPP row can reach no record.
     */
    @Test
    void aParentWithoutPoolidKeysOnTheSubjectAlone()
    {
        IDataTable supp = pooledSuppPc(new PooledSuppRow("S1", "", "PCSEQ", "1", "PCCALCN", "0.5"),
                new PooledSuppRow("", "P1", "PCSEQ", "2", "PCCALCN", "9.9"));
        IDataTableColumn column = column(ctx(pc(), supp), "PCCALCN");
        assertEquals("0.5", cell(column, 0), "S1/1: the subject row");
        assertEquals("", cell(column, 1), "S1/2: the pooled row names a pool PC does not have");
        assertEquals("", cell(column, 2));
    }


    /**
     * Combined review of runbook W2–W8, XCUT PERF 1: the materialised column is built once per run
     * per (parent, QNAM) through the shared cache, not once per rule.
     */
    @Test
    void theColumnIsMaterialisedOncePerParentAndQnamThroughTheSharedCache()
    {
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        IDataTable supp = suppPc(new SuppRow("S1", "PCSEQ", "1", "PCCALCN", "0.5"),
                new SuppRow("S2", "", "", "PCOTHER", "x"));
        IDataTable pc = pc();
        for (int rule = 0; rule < 3; rule++)
        {
            EvaluationContext ctx = EvaluationContext.builder().table(pc).ruleId("T-" + rule)
                    .datasetResolver(resolver(pc, supp)).sharedIndexCache(cache).build();
            assertEquals("0.5", cell(column(ctx, "PCCALCN"), 0));
            assertEquals("x", cell(column(ctx, "PCOTHER"), 2));
        }
        assertEquals(2, cache.suppQnamColumnBuildCount(),
                "one resolution per (parent, QNAM) for the whole run, whatever the rule count");
    }
}
