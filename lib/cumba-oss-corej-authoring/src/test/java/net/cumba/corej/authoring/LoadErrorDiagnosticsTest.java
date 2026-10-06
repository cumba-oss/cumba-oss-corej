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
    void entriesBecomeOneDiagnosticEach()
    {
        Rule rule = new Rule();
        rule.addLoadError("[X] Check: first");
        rule.addLoadError("[X] Scope: second");
        rule.addLoadWarning("[X] Outcome: soft");
        assertEquals("[X] Check: first; [X] Scope: second", rule.getLoadError(),
                "the joined channel is what it always was");
        List<RuleDiagnostic> out = LoadErrorDiagnostics.of(rule, null);
        assertEquals(3, out.size());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals("/Check", String.valueOf(out.get(0).path()));
        assertEquals("[X] Scope: second", out.get(1).message());
        assertEquals(Severity.WARNING, out.get(2).severity());
        assertEquals(LoadErrorDiagnostics.LOAD_WARNING, out.get(2).code());
        assertNull(out.get(2).range());
    }


    /**
     * Pack-2 review 8a-A F3: an entry is never split, whatever it contains — a stated whole channel
     * ({@code setLoadError}) is one entry too.
     */
    @Test
    void anEntryContainingTheSeparatorStaysOneDiagnostic()
    {
        Rule rule = new Rule();
        rule.addLoadError("[X] Scope.Domains.Include: names nothing; remove it");
        assertEquals(1, LoadErrorDiagnostics.of(rule, null).size());
        assertEquals("/Scope/Domains/Include",
                String.valueOf(LoadErrorDiagnostics.of(rule, null).getFirst().path()));
        rule.setLoadError("[X] Check: one; [X] Scope: stated whole");
        assertEquals(List.of("[X] Check: one; [X] Scope: stated whole"), rule.getLoadErrors());
        assertEquals(1, LoadErrorDiagnostics.of(rule, null).size());
        rule.setLoadError(null);
        assertEquals(List.of(), rule.getLoadErrors());
    }


    @Test
    void aCleanRuleHasNoDiagnostics()
    {
        assertEquals(List.of(), LoadErrorDiagnostics.of(new Rule(), null));
    }
}
