package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D13 of {@code PLAN-joined-column-typing}: the run's effective numeric tolerance is recorded in
 * the report, so a finding can be reproduced from its own output — <b>v2 only</b>, because v1 is a
 * frozen published consumer schema (owner rulings 2026-08-11 and 2026-09-15).
 */
class ReportToleranceKeyTest
{

    /**
     * Sections carrying the tolerance key, as {@code ReportAssembler} now builds them (F7): the
     * projections route it, they do not read a live setting.
     */
    private static ReportSections sections()
    {
        java.util.Map<String, Object> cd = new java.util.LinkedHashMap<>();
        cd.put("CORE_Engine_Version", "x");
        cd.put("Numeric_Tolerance_Digits", ScalarSemantics.toleranceDigits());
        return new ReportSections(new java.util.LinkedHashMap<>(cd), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }


    @Test
    @DisplayName("the v2 document carries the tolerance")
    void v2CarriesTolerance()
    {
        Map<String, Object> doc = sections().toCombinedExportDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> cd = (Map<String, Object>) doc.get("Conformance_Details");
        assertTrue(cd.containsKey("Numeric_Tolerance_Digits"));
        assertEquals(ScalarSemantics.toleranceDigits(), cd.get("Numeric_Tolerance_Digits"));
        // ⚑ This pins ROUTING, not the value: both sides come from the same source. The value
        // itself is pinned by ReportAssemblerToleranceTest, which asserts the literal default.
        // The engine's own metadata is still there -- the key is added, nothing is replaced.
        assertEquals("x", cd.get("CORE_Engine_Version"));
    }


    @Test
    @DisplayName("⛔ the FROZEN v1 document does not, and cannot acquire it by round trip")
    void v1StaysFrozen()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> cd1 = (Map<String, Object>) sections().toExportDocument()
                .get("Conformance_Details");
        assertFalse(cd1.containsKey("Numeric_Tolerance_Digits"));

        // The trap this guards: read a v2 document back and re-project it as v1. fromExportDocument
        // carries whatever its source had, so without the strip in toExportDocument the key would
        // be smuggled into the frozen schema.
        Map<String, Object> v2 = sections().toCombinedExportDocument();
        ReportSections roundTripped = ReportSections.fromExportDocument(v2);
        @SuppressWarnings("unchecked")
        Map<String, Object> reV1 = (Map<String, Object>) roundTripped.toExportDocument()
                .get("Conformance_Details");
        assertFalse(reV1.containsKey("Numeric_Tolerance_Digits"),
                "a v2 report re-projected as v1 must not carry the v2-only key");
        // ...while re-projecting it as v2 keeps it.
        @SuppressWarnings("unchecked")
        Map<String, Object> reV2 = (Map<String, Object>) roundTripped.toCombinedExportDocument()
                .get("Conformance_Details");
        assertTrue(reV2.containsKey("Numeric_Tolerance_Digits"));
    }
}
