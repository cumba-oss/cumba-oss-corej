package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@code JKM R5} on the <b>RELREC</b> path — a <b>live violation</b> until 2026-09-21, and not
 * the DROP/KEEP question at all.
 *
 * <p>
 * {@code RelrecRowExpander} built every join key through {@code cell()} + an {@code nz()} collapse,
 * and {@code cell()} answers {@code null} for a MISSING cell while {@code nz()} mapped {@code null}
 * to {@code ""}. ⇒ a subject whose {@code USUBJID} was <b>missing</b> joined a row whose
 * {@code USUBJID} was a genuine <b>empty string</b>, and two <em>different</em> missing markers
 * joined each other. Owner, 2026-09-21: <i>"a MIS will not join a record with an empty string and a
 * MIS_A will not join a record with a MIS or MIS_B."</i>
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why a dedicated class rather than a case in {@code RelrecRowExpanderTest}:</b> every
 * fixture there is built through a helper that takes {@code String[][]}, so it can express
 * {@code ""} but <b>not</b> a missing cell — which is exactly the input under test. A case added
 * there would have looked like coverage while testing the empty string against itself.
 * </p>
 */
class RelrecMissingSubjectKeyTest
{

    private static MatchDataset forwardRelrec()
    {
        MatchDataset md = new MatchDataset();
        md.setName("RELREC");
        return md;
    }


    /**
     * Dataset-level RELREC: blank {@code IDVARVAL} on both sides, linking AE.AELNKID ↔ FA.FALNKGRP.
     */
    private static IDataTable datasetLevelRelrec()
    {
        return MockTable.of().col("STUDYID", "S1", "S1").col("RDOMAIN", "AE", "FA")
                .col("USUBJID", "", "").col("IDVAR", "AELNKID", "FALNKGRP").col("IDVARVAL", "", "")
                .col("RELID", "G1", "G1").name("RELREC").build();
    }


    private static List<String> collect(RelrecRowExpander.RelrecExpansion exp, String col)
    {
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookup();
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, col));
        }
        return out;
    }


    private static List<String> join(IDataTable ae, IDataTable fa, String readColumn)
    {
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("RELREC", datasetLevelRelrec());
        tables.put("FA", fa);
        var exp = RelrecRowExpander.expand(ae, List.of(forwardRelrec()), tables::get, "R-TEST");
        return exp == null ? List.of() : collect(exp, readColumn);
    }


    /** ⛔ The violation: a MISSING USUBJID must not join a present EMPTY one. */
    @Test
    void aMissingSubjectDoesNotJoinAnEmptyOne()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "S1").colSasMissing("USUBJID", (String) null)
                .col("AELNKID", "1").col("AETERM", "REACTION").name("AE").build();
        IDataTable fa = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "FA").col("USUBJID", "")
                .col("FALNKGRP", "1").col("FAOBJ", "ERYTHEMA").name("FA").build();
        assertEquals(List.of(), join(ae, fa, "FAOBJ"),
                "JKM R5: a MissingValue USUBJID is not the empty string. '0:ERYTHEMA' here is the"
                        + " pre-2026-09-21 nz() collapse, in which both sides keyed as \"\"");
    }


    /**
     * ⭐ Non-vacuity control, and the half that makes the test above mean something: the SAME marker
     * on both sides <b>does</b> join. Without this, the assertion above would pass just as well
     * over a fixture whose RELREC link was simply broken.
     */
    @Test
    void theSameMissingMarkerOnBothSidesDoesJoin()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "S1").colSasMissing("USUBJID", (String) null)
                .col("AELNKID", "1").col("AETERM", "REACTION").name("AE").build();
        IDataTable fa = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "FA")
                .colSasMissing("USUBJID", (String) null).col("FALNKGRP", "1")
                .col("FAOBJ", "ERYTHEMA").name("FA").build();
        assertEquals(List.of("0:ERYTHEMA"), join(ae, fa, "FAOBJ"),
                "JKM R4/R5: the same missing marker is one key, so the link still joins. An empty"
                        + " result means the fix over-corrected into a drop");
    }


    /** ⭐ And the ordinary present case still joins — the control for the control. */
    @Test
    void twoPresentSubjectsStillJoin()
    {
        IDataTable ae = MockTable.of().col("STUDYID", "S1").col("USUBJID", "P1").col("AELNKID", "1")
                .col("AETERM", "REACTION").name("AE").build();
        IDataTable fa = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "FA").col("USUBJID", "P1")
                .col("FALNKGRP", "1").col("FAOBJ", "ERYTHEMA").name("FA").build();
        List<String> joined = join(ae, fa, "FAOBJ");
        assertNotNull(joined);
        assertEquals(List.of("0:ERYTHEMA"), joined,
                "control: the plain present-key RELREC join must be untouched by the key encoding");
    }
}
