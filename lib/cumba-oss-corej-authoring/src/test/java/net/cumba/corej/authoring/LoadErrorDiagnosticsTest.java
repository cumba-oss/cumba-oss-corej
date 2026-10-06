package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.cumba.corej.authoring.RuleDiagnostic.Severity;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

class LoadErrorDiagnosticsTest
{

    @Test
    void theLoaderShapeYieldsAPath()
    {
        assertEquals("/Outcome/Output_Variables", String.valueOf(LoadErrorDiagnostics
                .pathOf("[CDISC-CG0001] Outcome.Output_Variables: the static list names X")));
        assertEquals("/Check", String.valueOf(LoadErrorDiagnostics.pathOf("[X] Check: bad")));
        assertEquals("/Scope", String.valueOf(LoadErrorDiagnostics.pathOf("Scope: no Domains")));
        assertEquals("/Requirements/Variables", String.valueOf(LoadErrorDiagnostics
                .pathOf("[X] Requirements.Variables entry 'ae seq' is not a variable name")));
        assertEquals("/Scope/Use_Case", String.valueOf(
                LoadErrorDiagnostics.pathOf("[X] Scope.Use_Case 'PROD' is not a known use case")));
    }


    @Test
    void aMessageNamingNoBoundKeyHasNoPath()
    {
        assertNull(LoadErrorDiagnostics.pathOf("[X] the rule cannot be evaluated"));
        assertNull(LoadErrorDiagnostics.pathOf("[X] Nonsense.Key: text"));
        assertNull(LoadErrorDiagnostics.pathOf(""));
    }


    @Test
    void segmentsBecomeOneDiagnosticEach()
    {
        Rule rule = new Rule();
        rule.setLoadError("[X] Check: first; [X] Scope: second");
        rule.setLoadWarning("[X] Outcome: soft");
        List<RuleDiagnostic> out = LoadErrorDiagnostics.of(rule, null);
        assertEquals(3, out.size());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals("/Check", String.valueOf(out.get(0).path()));
        assertEquals("[X] Scope: second", out.get(1).message());
        assertEquals(Severity.WARNING, out.get(2).severity());
        assertEquals(LoadErrorDiagnostics.LOAD_WARNING, out.get(2).code());
        assertNull(out.get(2).range());
    }


    @Test
    void aCleanRuleHasNoDiagnostics()
    {
        assertEquals(List.of(), LoadErrorDiagnostics.of(new Rule(), null));
    }
}
