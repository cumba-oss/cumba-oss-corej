package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D13, at the point the value enters the report (review finding F7): {@code ReportAssembler} puts
 * the run's effective tolerance into {@code Conformance_Details}, so the projections only route it.
 *
 * <p>
 * ⚑ This is the test that pins the VALUE. {@code ReportToleranceKeyTest} pins routing — v2 carries
 * the key, v1 strips it even across a round trip — but compares the emitted value against the same
 * source that emitted it, so it cannot catch a wrong default.
 * </p>
 */
class ReportAssemblerToleranceTest
{

    @Test
    @DisplayName("the assembler records the effective tolerance, and the default is 12")
    void assemblerRecordsTolerance()
    {
        Map<String, Object> cd = new ReportAssembler()
                .conformance(ReportAssembler.Conformance.builder().coreEngineVersion("0.5.0.0")
                        .reportGeneration("2026-01-01T00:00:00").build())
                .sections().conformanceDetails().entrySet().stream()
                .collect(java.util.LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()),
                        Map::putAll);

        assertEquals(12, cd.get("Numeric_Tolerance_Digits"),
                "the shipped default, asserted as a literal rather than against its own source");
        assertEquals(ScalarSemantics.toleranceDigits(), cd.get("Numeric_Tolerance_Digits"));
    }
}
