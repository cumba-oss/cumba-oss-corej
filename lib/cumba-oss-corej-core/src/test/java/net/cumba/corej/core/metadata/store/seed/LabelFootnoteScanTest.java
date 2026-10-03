package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredDomain;
import net.cumba.corej.core.metadata.store.StoredField;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.junit.jupiter.api.Test;

/**
 * Review F6: {@link LabelFootnoteScan}'s marker rule — what counts as a printed footnote marker at
 * the end of a label, what is a template placeholder or a unit exponent instead — and its reach:
 * every label level (variable, field, dataset, class, CDASH domain, data structure, variable set)
 * and the CDASH prompts. The real corpus's zero hits are asserted by
 * {@link StoreErrataRealCorpusTest}.
 */
class LabelFootnoteScanTest
{

    @Test
    void theMarkersAFootnoteIsPrintedWith()
    {
        for (String marked : List.of("Study Day of Start of Observation*", "Dose Form†",
                "Dose Form ‡", "Category of Event[1]", "Category of Event [12]", "Test Name [i]",
                "Test Name [iv]", "Test Name [ix]", "Test Name [II]", "Test Name [VIII]",
                "Test Name [a]", "Test Name [b]", "Test Name [d]", "Test Name [e]", "Weight¹",
                "Weight ²", "Result in Standard Format¹²", "Height (cm)³"))
        {
            assertTrue(LabelFootnoteScan.endsInFootnote(marked), marked);
        }
    }


    @Test
    void placeholdersUnitsAndInnerMarkersAreNotFootnotes()
    {
        for (String clean : List.of("Planned Treatment for Period [x]",
                "Planned Treatment for Period [xx]", "Analysis Value [c]", "Body Surface Area m²",
                "Dose per Body Surface Area mg/m²", "Volume cm³", "Concentration in µg/mL",
                "[Visit] Date", "Start Date of [Event]", "Test Name [f]", "Test Name [x]",
                "Test Name [L]", "Ratio*Factor Unit", "Body Surface Area (m²)", "Sex"))
        {
            assertFalse(LabelFootnoteScan.endsInFootnote(clean), clean);
        }
    }


    @Test
    void everyLabelLevelAndThePromptsAreScanned()
    {
        StoredVariable v = StoredVariable.builder().name("AESTDY").label("Study Day*").build();
        StoredDataset ae = new StoredDataset("AE", "Adverse Events[1]", "1", null, List.of(v), null,
                null);
        StoredField prompted = StoredField.builder().name("AEYN").label("Any AE")
                .prompt("Any adverse events?[i]").build();
        StoredDomain domain = new StoredDomain("AE", "Adverse Events†", "1", null,
                List.of(prompted));
        StoredClass events = new StoredClass("Events", "Events ‡", "1", List.of(), List.of(ae),
                null, List.of(domain), List.of(), List.of());
        StoredVariableSet set = new StoredVariableSet("Timing", "Timing Variables[a]", "1",
                List.of(), null);
        StoredDataStructure bds = new StoredDataStructure("BDS", "Basic Data Structure²", "1", null,
                null, List.of(set), null);
        StoredProduct product = StoredProduct.builder().key("standards/x/1-0")
                .classes(List.of(events)).dataStructures(List.of(bds)).build();

        List<String> hits = LabelFootnoteScan.scan(Map.of(product.key(), product));

        assertEquals(List.of("standards/x/1-0/classes[Events]: 'Events ‡'",
                "standards/x/1-0/classes[Events]/datasets[AE]: 'Adverse Events[1]'",
                "standards/x/1-0/classes[Events]/datasets[AE]/variables[AESTDY]: 'Study Day*'",
                "standards/x/1-0/classes[Events]/domains[AE]: 'Adverse Events†'",
                "standards/x/1-0/classes[Events]/domains[AE]/fields[AEYN] prompt:"
                        + " 'Any adverse events?[i]'",
                "standards/x/1-0/dataStructures[BDS]: 'Basic Data Structure²'",
                "standards/x/1-0/dataStructures[BDS]/variableSets[Timing]: 'Timing Variables[a]'"),
                hits);
    }
}
