package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.eval.NativeExprEvaluator;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * T8 — {@code supp_qnam_present} / {@code supp_qnam_value} operations. From a parent PC record,
 * test whether a SUPPPC supplemental qualifier with a given {@code QNAM} exists for that record
 * (presence) and read its {@code QVAL} joined back to the parent row (value). The join is keyed on
 * {@code USUBJID} + the SUPP {@code IDVAR}/{@code IDVARVAL} → the parent {@code PCSEQ}.
 *
 * <p>
 * Fixture: three PC records — (S1, PCSEQ=1), (S1, PCSEQ=2), (S2, PCSEQ=1). SUPPPC carries a single
 * {@code QNAM='PCCALCN'} row for (S1, PCSEQ=1) with {@code QVAL='0.5'}. So only the first PC record
 * has the qualifier present.
 * </p>
 */
class SuppQnamJoinOperationTest
{

    private static IDataTable pc()
    {
        return MockTable.of().name("PC").col("USUBJID", "S1", "S1", "S2")
                .col("PCSEQ", "1", "2", "1").col("PCSTRESC", "BLQ", "BLQ", "BLQ").build();
    }


    /**
     * SUPPPC with one PCCALCN row joining (S1, PCSEQ=1). {@code idvarval} lets a test miss the
     * join.
     */
    private static IDataTable suppPc(String qnam, String idvarval)
    {
        return MockTable.of().name("SUPPPC").col("RDOMAIN", "PC").col("USUBJID", "S1")
                .col("IDVAR", "PCSEQ").col("IDVARVAL", idvarval).col("QNAM", qnam)
                .col("QVAL", "0.5").build();
    }


    private static EvaluationContext ctx(IDataTable supp)
    {
        return EvaluationContext.builder().table(pc()).domainPrefix("PC")
                .datasetResolver(name -> "SUPPPC".equals(name) ? supp : null).build();
    }


    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    @Test
    void presentTrueOnlyForTheJoinedParentRecord()
    {
        BitSet present = eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == true",
                ctx(suppPc("PCCALCN", "1")));
        assertTrue(present.get(0), "(S1, PCSEQ=1) has a matching SUPPPC PCCALCN row");
        assertFalse(present.get(1), "(S1, PCSEQ=2) has no matching row");
        assertFalse(present.get(2), "(S2, PCSEQ=1) has no matching row");
    }


    @Test
    void ruleFiresForRecordsWithoutTheQualifier()
    {
        // FDA-SE2234 shape: fire when the numeric-interpretation SUPP record is absent.
        BitSet fires = eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == false",
                ctx(suppPc("PCCALCN", "1")));
        assertFalse(fires.get(0), "(S1, PCSEQ=1) is covered ⇒ does not fire");
        assertTrue(fires.get(1), "(S1, PCSEQ=2) lacks the qualifier ⇒ fires");
        assertTrue(fires.get(2), "(S2, PCSEQ=1) lacks the qualifier ⇒ fires");
    }


    @Test
    void valueExposesTheJoinedQval()
    {
        BitSet matches = eval(
                "supp_qnam_value(domain=\"SUPPPC\", key_value=\"PCCALCN\") == \"0.5\"",
                ctx(suppPc("PCCALCN", "1")));
        assertTrue(matches.get(0), "(S1, PCSEQ=1) reads QVAL 0.5");
        assertFalse(matches.get(1), "(S1, PCSEQ=2) has no QVAL");
        assertFalse(matches.get(2), "(S2, PCSEQ=1) has no QVAL");
    }


    @Test
    void idvarvalMustResolveToTheParentSeq()
    {
        // SUPP row references PCSEQ=99, which no PC record carries ⇒ nothing joins ⇒ all absent.
        BitSet present = eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == true",
                ctx(suppPc("PCCALCN", "99")));
        assertTrue(present.isEmpty(), "no parent record has PCSEQ=99 ⇒ no join ⇒ none present");
    }


    @Test
    void suppPresentButNoMatchingQnamFiresEveryRecord()
    {
        // SUPPPC exists but carries only a different QNAM ⇒ the required PCCALCN record is absent
        // for every PC record, so none is "present" and the == false consequent fires all three.
        EvaluationContext ctx = ctx(suppPc("PCOTHER", "1"));
        assertTrue(eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == true", ctx)
                .isEmpty(), "no PCCALCN row ⇒ none present");
        BitSet fires = eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == false",
                ctx);
        assertEquals(3, fires.cardinality(), "SUPPPC present but no PCCALCN ⇒ every record fires");
    }


    @Test
    void secondIdvarRowsAreDroppedAgainstTheAnchor()
    {
        // Two PCCALCN rows for the same parent under different IDVARs: the first (PCSEQ) anchors
        // the join; the second (PCGRPID) diverges and is dropped, so only the PCSEQ join survives.
        IDataTable supp = MockTable.of().name("SUPPPC").col("RDOMAIN", "PC", "PC")
                .col("USUBJID", "S1", "S1").col("IDVAR", "PCSEQ", "PCGRPID")
                .col("IDVARVAL", "1", "1").col("QNAM", "PCCALCN", "PCCALCN")
                .col("QVAL", "0.5", "0.9").build();
        BitSet present = eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == true",
                ctx(supp));
        assertTrue(present.get(0), "PCSEQ=1 anchor joins (S1, PCSEQ=1)");
        assertFalse(present.get(1), "PCSEQ=2 has no PCCALCN row");
        assertFalse(present.get(2), "the divergent PCGRPID row was dropped ⇒ S2 not joined");
    }


    @Test
    void suppMissingIdvarColumnsResolvesToEmptyGroup()
    {
        // SUPPPC present with a QNAM column but no IDVAR/IDVARVAL columns ⇒ no join key ⇒ the
        // per-parent default applies (present=false everywhere), so the == false consequent fires
        // every record.
        IDataTable supp = MockTable.of().name("SUPPPC").col("USUBJID", "S1").col("QNAM", "PCCALCN")
                .col("QVAL", "0.5").build();
        assertTrue(eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == true",
                ctx(supp)).isEmpty(), "no IDVAR columns ⇒ nothing present");
        assertEquals(3,
                eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == false",
                        ctx(supp)).cardinality(),
                "no join key ⇒ every record lacks the qualifier ⇒ all fire");
    }


    /**
     * ⚑ Q17-a moved this case, deliberately. The supplemental dataset is not in the study, so no
     * record carries the qualifier — {@code supp_qnam_present} declares {@code EmptyResult
     * .PREDICATE} and now answers {@code false}, where it used to answer an unclassified
     * {@code null} that broadcast "no value" and fired nothing.
     *
     * <p>
     * ⚠⚠ The coherence argument is the neighbouring cases, not the change in isolation:
     * {@link #suppPresentButNoMatchingQnamFiresEveryRecord} (SUPPPC present, no PCCALCN row) and
     * {@link #suppMissingIdvarColumnsResolvesToEmptyGroup} (SUPPPC present, no join key) both fire
     * all three records today. "SUPPPC is not in the study" is the same study fact as "SUPPPC is
     * there and holds no PCCALCN", and before Q17-a the engine reported the two differently — the
     * weaker evidence produced the louder silence.
     * </p>
     *
     * <p>
     * ⚠ Applicability is still scope's job: a rule that genuinely should not run when its
     * supplemental dataset is absent declares that in {@code Scope.Variables}, not by relying on an
     * operation returning nothing. {@code FDA-SE2234} makes the same statement today by hand, with
     * a {@code $study_datasets shares_no_elements_with ["SUPPPC"]} disjunct.
     * </p>
     */
    @Test
    void absentSuppDatasetIsAFalsePresenceNotASilentSkip()
    {
        EvaluationContext noSupp = EvaluationContext.builder().table(pc()).domainPrefix("PC")
                .datasetResolver(_ -> null).build();
        assertTrue(
                eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == true", noSupp)
                        .isEmpty(),
                "no SUPPPC at all ⇒ nothing is present");
        BitSet fires = eval("supp_qnam_present(domain=\"SUPPPC\", key_value=\"PCCALCN\") == false",
                noSupp);
        assertEquals(3, fires.cardinality(),
                "absent SUPPPC ⇒ every record lacks the qualifier, exactly as a present-but-empty "
                        + "SUPPPC already reported");
    }

}
