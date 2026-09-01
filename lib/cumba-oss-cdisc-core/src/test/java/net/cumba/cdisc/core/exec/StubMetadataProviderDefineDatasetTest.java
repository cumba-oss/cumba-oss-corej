package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link StubMetadataProvider}'s Define dataset channel (PLAN-parity-define-dataset-channel D3/D6).
 *
 * <p>
 * The stub is shared with the parity harness through the core test-jar, so the contract these tests
 * pin is a cross-module one: {@link StubMetadataProvider#getDatasetNames()} must read the dedicated
 * name list and nothing else. If it were derived from the dataset-attribute map, a Define that
 * declares datasets without exposing dataset-level attributes would report an EMPTY list — and
 * {@code define_dataset_names} has no empty ⇒ not-available demotion, so the rule would execute
 * with {@code $define_datasets = []} and fire on every dataset instead of skipping.
 */
class StubMetadataProviderDefineDatasetTest
{

    @Test
    void datasetNamesReadTheNameListNotTheAttributeMap()
    {
        StubMetadataProvider p = new StubMetadataProvider().defineDataset("LB").defineDataset("XY")
                .datasetMeta("XY", Map.of("className", "TRIAL DESIGN"));

        assertEquals(List.of("LB", "XY"), p.getDatasetNames(),
                "a name registered without attributes must still be on the list");
        assertTrue(p.getDatasetMetadata("LB").isEmpty());
        assertEquals("TRIAL DESIGN", p.getDatasetMetadata("XY").get("className"));
    }


    @Test
    void registeringAttributesDoesNotRegisterTheName()
    {
        // Deliberately one-way: the parity runner registers every define_xml key on the name list
        // before it branches on shape, so the two calls stay independent and the name list can be
        // mutation-checked.
        StubMetadataProvider p = new StubMetadataProvider().datasetMeta("XY", Map.of("name", "XY"));

        assertEquals(List.of(), p.getDatasetNames());
    }


    @Test
    void nameRegistrationIsIdempotentAndOrderPreserving()
    {
        StubMetadataProvider p = new StubMetadataProvider().defineDataset("ZZ").defineDataset("AE")
                .defineDataset("ZZ");

        assertEquals(List.of("ZZ", "AE"), p.getDatasetNames());
    }


    @Test
    void datasetAttributesAreDefensivelyCopiedAndUnknownDatasetsYieldEmpty()
    {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("label", "Custom Dataset");
        StubMetadataProvider p = new StubMetadataProvider().datasetMeta("XY", attrs);
        attrs.put("label", "mutated after registration");

        assertEquals("Custom Dataset", p.getDatasetMetadata("XY").get("label"));
        assertTrue(p.getDatasetMetadata("NOPE").isEmpty());
    }


    @Test
    void mutatingTheReturnedAttributeMapDoesNotReachTheProvider()
    {
        // The other half of the defensive copy: getDatasetMetadata must not hand out the live
        // registration map. It used to, so a caller's put()/remove() rewrote the Define overlay for
        // every later read of the same provider — and the parity harness reuses one provider for
        // the whole spec run.
        StubMetadataProvider p = new StubMetadataProvider().datasetMeta("XY",
                Map.of("className", "TRIAL DESIGN"));

        Map<String, String> first = p.getDatasetMetadata("XY");
        first.put("className", "EVENTS");
        first.put("label", "injected");

        assertEquals("TRIAL DESIGN", p.getDatasetMetadata("XY").get("className"),
                "a caller's mutation must not survive into the provider");
        assertEquals(Map.of("className", "TRIAL DESIGN"), p.getDatasetMetadata("XY"));
    }


    @Test
    void anEmptyStubStillReportsNoDefineDatasets()
    {
        // The pre-P1 shape: getDatasetNames() was not overridden at all and inherited
        // MetadataProvider's List.of(). The default must stay empty for a stub nobody configured —
        // what changed is that a configured stub can now say otherwise.
        assertEquals(List.of(), new StubMetadataProvider().getDatasetNames());
        assertEquals(Map.of(), new StubMetadataProvider().getDatasetMetadata("AE"));
    }
}
