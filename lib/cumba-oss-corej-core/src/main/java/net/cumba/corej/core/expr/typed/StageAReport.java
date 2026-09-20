package net.cumba.corej.core.expr.typed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.cumba.datatable.report.Severity;

/**
 * The result of one rule's stage-A check: the typed AST per declared level and the findings. Phase
 * 2 builds and reports; nothing evaluates the typed trees yet. (The {@code Precondition} tier is
 * deliberately not checked in phase 2 — R8 retired it as an authoring surface, so every live
 * precondition is engine-installed.)
 */
public record StageAReport(SequencedMap<Severity, TypedExpr> typedLevels,
        List<StageAFinding> findings)
{

    public StageAReport
    {
        typedLevels = new LinkedHashMap<>(typedLevels);
        findings = List.copyOf(findings);
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
