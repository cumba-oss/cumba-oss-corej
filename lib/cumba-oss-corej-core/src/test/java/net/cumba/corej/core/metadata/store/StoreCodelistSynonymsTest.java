package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.LibraryLists;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.metadata.CdiscLibraryMetadataLibrary;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code codelist_terms(…, returntype="synonym")} over the metadata store
 * ({@code PLAN-engine-gap-dict-unsupported} §4.2, Q4; runbook W1b DI3, coordinator D16): the stored
 * synonyms reach {@code LibraryLists} as meta values — {@link MetadataKeys#TERM_SYNONYMS} on each
 * term entry, {@link MetadataKeys#CODELIST_SYNONYMS} on the codelist — built by
 * {@code CdiscLibraryMetadataLibrary.buildCodelist}; no {@code cumba-datatable} interface change.
 * The fixture's terms carry every stored shape: {@code ["Yes"]} (YESONLY's Y), an empty list (NY's
 * N) and {@code null} — unpublished (NY's U).
 */
class StoreCodelistSynonymsTest
{

    private static final CdiscLibraryMetadataLibrary LIBRARY = CdiscLibraryMetadataLibrary
            .fromStoredSdtm("sdtmig", "3-4", MetadataStoreFixtures.igProduct(), List
                    .of(MetadataStoreFixtures.sdtmPackage1(), MetadataStoreFixtures.qsftPackage()),
                    List.of());

    private static Object run(String aCodelist, String aLevel)
    {
        EvaluationContext ctx = EvaluationContext.builder()
                .table(MockTable.of().col("X", "x").name("DM").build())
                .libraryProvider(new MetadataLibraryProvider(LIBRARY)).build();
        return LibraryLists.codelistTerms(EvalRun.fullRange(ctx),
                Arrays.asList(ConstVector.of(List.of(aCodelist)), ConstVector.of(aLevel),
                        ConstVector.of("synonym")))
                .value(0).resolved();
    }


    @Test
    void theStoredTermSynonymsTravelAsTheEntrysMetaValue()
    {
        ICodeList yes = LIBRARY.getCodelist("YESONLY").orElseThrow();
        assertEquals(List.of("Yes"),
                yes.getEntries().get(0).getMetaValue(MetadataKeys.TERM_SYNONYMS).orElseThrow());
        ICodeList ny = LIBRARY.getCodelist("NY").orElseThrow();
        assertEquals(List.of(),
                ny.getEntries().get(0).getMetaValue(MetadataKeys.TERM_SYNONYMS).orElseThrow(),
                "N: a published empty list stays empty");
        assertEquals(java.util.Optional.empty(),
                ny.getEntries().get(1).getMetaValue(MetadataKeys.TERM_SYNONYMS),
                "U: an unpublished (null) list is no meta value");
        assertEquals(List.of("Yes No"),
                ny.getMetaValue(MetadataKeys.CODELIST_SYNONYMS).orElseThrow());
    }


    @Test
    void termLevelSynonymsProjectThroughTheStoreBackedProvider()
    {
        assertEquals(List.of("Yes"), run("YESONLY", "term"));
        // NY's terms: an empty list and an unpublished one — nothing to project ⇒ the empty-union
        // SKIP, never [] and never [""]
        assertThrows(UnusableProviderAnswerException.class, () -> run("NY", "term"));
    }


    @Test
    void codelistLevelSynonymsAreTheCodelistsOwn()
    {
        assertEquals(List.of("Yes No"), run("NY", "codelist"));
        // QSCAT stores an empty codelist synonym list, YESONLY an unpublished one
        assertThrows(UnusableProviderAnswerException.class, () -> run("QSCAT", "codelist"));
        assertThrows(UnusableProviderAnswerException.class, () -> run("YESONLY", "codelist"));
    }
}
