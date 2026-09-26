package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.define.conformance.ct.CtCodelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** N2a (PLAN-define-ct-evaluation): {@link StoreCtProvider} over a store written per test. */
class StoreCtProviderTest
{

    private static final String OLD = "sdtmct-2023-12-15";

    private static final String NEW = "sdtmct-2024-03-29";

    @TempDir
    Path tempDir;

    private static StoredTerm term(String aValue, String aCode)
    {
        return new StoredTerm(aValue, aCode, aValue, null, null);
    }


    private MetadataStore store() throws IOException
    {
        // OLD: SEX (non-extensible, F/M) named "Sex"; NAMELESS (extensible, no name)
        // NEW: SEX revised (F/M/U); a codelist with a null extensible (D-6: left out); UNIT
        StoredCodelist sexOld = new StoredCodelist("SEX", "C66731", "Sex", "Sex", null, null,
                Boolean.FALSE, List.of(term("F", "C16576"), term("M", "C20197")));
        StoredCodelist nameless = new StoredCodelist("NAMELESS", "C90000", null, "Nameless", null,
                null, Boolean.TRUE, List.of(term("A", "C90001")));
        StoredCodelist sexNew = new StoredCodelist("SEX", "C66731", "Sex", "Sex", null, null,
                Boolean.FALSE,
                List.of(term("F", "C16576"), term("M", "C20197"), term("U", "C17998")));
        StoredCodelist unpublished = new StoredCodelist("NOEXT", "C91000", "No Extensible", "x",
                null, null, null, List.of(term("Q", "C91001")));
        StoredCodelist unit = new StoredCodelist("UNIT", "C71620", "Unit", "Unit", null, null,
                Boolean.TRUE, List.of(term("mg", "C28253"),
                        new StoredTerm("nocode", null, "no code", null, null)));
        Path file = tempDir.resolve("store.zip");
        new MetadataStoreWriter().addCtPackage(new StoredCtPackage(OLD, List.of(sexOld, nameless)))
                .addCtPackage(new StoredCtPackage(NEW, List.of(sexNew, unpublished, unit)))
                .publishedCtPackages(List.of(OLD, NEW)).productCatalogue(List.of()).write(file);
        return MetadataStore.open(file);
    }


    @Test
    void cCodeLookupCarriesExtensibilityAndTheSubmissionValueMap() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider provider = StoreCtProvider.of(store, List.of(NEW));
            CtCodelist unit = provider.codelistByCCode("C71620").orElseThrow();
            assertTrue(unit.extensible());
            assertEquals(Map.of("mg", "C28253"), unit.termsBySubmissionValue(),
                    "a term without a conceptId is dropped (D-6)");
            CtCodelist sex = provider.codelistByCCode("C66731").orElseThrow();
            assertFalse(sex.extensible());
            assertEquals(Map.of("F", "C16576", "M", "C20197", "U", "C17998"),
                    sex.termsBySubmissionValue());
            assertEquals(List.of(NEW), provider.packageIds());
        }
    }


    @Test
    void aCodelistWithAnUnpublishedExtensibleFlagIsUnknownToCt() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider provider = StoreCtProvider.of(store, List.of(NEW));
            assertTrue(provider.codelistByCCode("C91000").isEmpty(), "D-6: left out entirely");
            assertTrue(provider.codelistByName("No Extensible").isEmpty());
            assertEquals(2, provider.codelistCount(), "SEX and UNIT; NOEXT is out");
        }
    }


    @Test
    void theFirstPackageInOrderWinsPerCCode() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider oldFirst = StoreCtProvider.of(store, List.of(OLD, NEW));
            assertEquals(Map.of("F", "C16576", "M", "C20197"),
                    oldFirst.codelistByCCode("C66731").orElseThrow().termsBySubmissionValue(),
                    "OLD first: its SEX (without U) wins");
            StoreCtProvider newFirst = StoreCtProvider.of(store, List.of(NEW, OLD));
            assertEquals(3, newFirst.codelistByCCode("C66731").orElseThrow()
                    .termsBySubmissionValue().size(), "NEW first: its SEX (with U) wins");
            assertTrue(newFirst.codelistByCCode("C90000").isPresent(),
                    "a codelist only the second package holds is still reachable");
        }
    }


    /** D-15: exact codelist name, first wins in order, a null name stays reachable by c-code. */
    @Test
    void nameLookupIsExactAndFirstWinsInOrder() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider provider = StoreCtProvider.of(store, List.of(OLD, NEW));
            assertTrue(provider.hasNameLookup());
            CtCodelist sex = provider.codelistByName("Sex").orElseThrow();
            assertEquals("C66731", sex.cCode());
            assertEquals(2, sex.termsBySubmissionValue().size(), "OLD first wins by name too");
            assertTrue(provider.codelistByName("sex").isEmpty(), "exact match, no case folding");
            assertTrue(provider.codelistByName("Nameless").isEmpty(),
                    "a codelist without a name is absent from the name index...");
            assertTrue(provider.codelistByCCode("C90000").isPresent(), "...but present by c-code");
        }
    }


    @Test
    void aPackageThatIsNotHeldFailsLoudly() throws IOException
    {
        try (MetadataStore store = store())
        {
            assertThrows(IllegalStateException.class,
                    () -> StoreCtProvider.of(store, List.of("sdtmct-1999-01-01")),
                    "the caller checks presence (D3); a vanished package is a bug, never empty");
        }
    }
}
