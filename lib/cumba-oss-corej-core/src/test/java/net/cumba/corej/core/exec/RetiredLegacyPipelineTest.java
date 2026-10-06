package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ⛔ The dead-code tripwire of {@code PLAN-merged-evaluation-table} W6: the legacy join pipeline the
 * merged evaluation view replaced stays retired. Every class and member below was deleted once its
 * last reader moved to the view (W4: the bare {@code Child} pre-merge; W5: the RELREC row expander;
 * W6: the key-match expander, the first-wins key-join lookups, the SUPP pivot, their indexes and
 * caches, and the legacy arm's switch); none may come back — a second pipeline beside the view is
 * how two answers to one join return.
 *
 * <p>
 * The instrument is the class loader and reflection, not a source grep: a comment that names a
 * retired class (the provenance notes the code keeps) is no reach, a resurrected class under the
 * old name is. Each detector is shown to FIRE on a live class and member before its verdict counts
 * ({@link #theDetectorsSeeALiveClassAndMember}); the planted control of W6 re-added
 * {@code RelrecExpandedTable} to {@code src/main} and saw this class red.
 * </p>
 */
class RetiredLegacyPipelineTest
{

    private static final String EXEC = "net.cumba.corej.core.exec.";

    /** The retired classes, in the order of their retirement. */
    private static final List<String> RETIRED_CLASSES = List.of(
            // W4 — the bare Child pre-merge
            "ChildMatchPreMerger", "ChildMatchIndex", "PolymorphicMergedColumn",
            "StringArrayColumn",
            // W5 — the RELREC row expander
            "RelrecRowExpander", "RelrecExpandedLookup",
            // W6 — the key-match expander, the key-join lookups, the SUPP pivot, their structures
            "KeyMatchRowExpander", "KeyMatchExpandedLookup", "DatasetLookup", "KeyHashing",
            "KeyCellReader", "KeyMatchIndex", "SuppPivot", "SuppQnamIndex", "RelrecExpandedTable");

    /** The retired members of classes that live on, as {@code Class#method}. */
    private static final List<String> RETIRED_MEMBERS = List.of(
            "MergedEvaluation#setLegacyForTests", "MergedEvaluation#legacyArmed",
            "RuleRunner#buildJoinedDatasets", "EvaluationContext#getJoinedDatasets",
            "EvaluationContext#getSuppPivot", "JoinCache#getOrBuildLookup",
            "JoinCache$SharedIndexCache#getOrBuildKeyMatchIndex",
            "JoinCache$SharedIndexCache#getOrBuildSuppQnamColumn");

    /** Whether {@code fqcn} loads in this module's class path. */
    static boolean present(String fqcn)
    {
        try
        {
            Class.forName(fqcn, false, RetiredLegacyPipelineTest.class.getClassLoader());
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }


    /** Whether {@code owner#name} is declared (any parameter list) by a loadable class. */
    static boolean declared(String member)
    {
        int hash = member.indexOf('#');
        String owner = EXEC + member.substring(0, hash);
        String name = member.substring(hash + 1);
        if (!present(owner))
        {
            return false;
        }
        try
        {
            for (Method m : Class.forName(owner).getDeclaredMethods())
            {
                if (m.getName().equals(name))
                {
                    return true;
                }
            }
            return false;
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalStateException(owner, e);
        }
    }


    @Test
    void noRetiredClassComesBack()
    {
        List<String> back = new ArrayList<>();
        for (String name : RETIRED_CLASSES)
        {
            if (present(EXEC + name))
            {
                back.add(name);
            }
        }
        assertEquals(List.of(), back, "⛔ a retired class of the legacy join pipeline is back. The"
                + " merged evaluation view is the one reader of every join, SUPP qualifier and"
                + " row map (PLAN-merged-evaluation-table W4–W6): serve the shape through the view"
                + " (MergedEvaluation, the datatable's shared matching) instead of reviving it");
    }


    @Test
    void noRetiredMemberComesBack()
    {
        List<String> back = new ArrayList<>();
        for (String member : RETIRED_MEMBERS)
        {
            if (declared(member))
            {
                back.add(member);
            }
        }
        assertEquals(List.of(), back, "⛔ a retired member of the legacy arm is back — the joins"
                + " are EvaluationContext.joins() (the view's parts), the qualifiers"
                + " EvaluationContext.suppQualifier, and there is no second arm to switch to");
    }


    /**
     * Non-vacuity: each detector answers {@code true} for a live class and member — so the two
     * {@code false} verdicts above are the class loader's and reflection's, not a broken probe.
     */
    @Test
    void theDetectorsSeeALiveClassAndMember()
    {
        assertTrue(present(EXEC + "MergedViewLookup"), "CONTROL FAILED: a live class unseen");
        assertTrue(present(EXEC + "RowMappedTable"), "CONTROL FAILED: a live class unseen");
        assertTrue(declared("MergedEvaluation#plantForTests"),
                "CONTROL FAILED: a live member unseen");
        assertTrue(declared("JoinCache$SharedIndexCache#getOrBuildSuppQnams"),
                "CONTROL FAILED: a live nested-class member unseen");
        assertTrue(declared("EvaluationContext#joins"), "CONTROL FAILED: a live member unseen");
    }
}
