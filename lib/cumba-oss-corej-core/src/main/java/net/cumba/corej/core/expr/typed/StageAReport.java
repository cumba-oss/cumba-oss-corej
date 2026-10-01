package net.cumba.corej.core.expr.typed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The result of one rule's stage-A check: the typed AST per declared level, the typed
 * {@code Precondition} when the rule carries one ({@code PLAN-stage-a-parameter-type-arming} Q2 —
 * since R8 every live Precondition is engine-written, and an engine-written root is checked like an
 * authored one), and the findings. Phase 2 builds and reports; nothing evaluates the typed trees
 * yet.
 */
public record StageAReport(SequencedMap<Severity, TypedExpr> typedLevels,
        @Nullable TypedExpr typedPrecondition, List<StageAFinding> findings)
{

    public StageAReport
    {
        typedLevels = new LinkedHashMap<>(typedLevels);
        findings = List.copyOf(findings);
    }


    /** A report over the levels alone (no Precondition). */
    public StageAReport(SequencedMap<Severity, TypedExpr> typedLevels, List<StageAFinding> findings)
    {
        this(typedLevels, null, findings);
    }


    /**
     * The typed AST per declared level, in declaration order. Returned as a defensive copy
     * (SpotBugs {@code EI_EXPOSE_REP} — {@code Map.copyOf} would lose the level order, the T2
     * review M7 lesson).
     */
    @Override
    public SequencedMap<Severity, TypedExpr> typedLevels()
    {
        return new LinkedHashMap<>(typedLevels);
    }


    /** The findings whose kind is armed — the ones that file a load error. */
    public List<StageAFinding> armedFindings()
    {
        return findings.stream().filter(f -> f.kind().armed()).toList();
    }


    /** The findings whose kind is observe-only. */
    public List<StageAFinding> observedFindings()
    {
        return findings.stream().filter(f -> !f.kind().armed()).toList();
    }

}
