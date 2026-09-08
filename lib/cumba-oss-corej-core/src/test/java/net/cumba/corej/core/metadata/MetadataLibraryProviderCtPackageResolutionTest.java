package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

/**
 * CT package resolution behind {@code getCodelistAttribute} (F-corej-L2-02, work-order rows C-01 …
 * C-07).
 *
 * <p>
 * The house rule (stated in {@code getCodelistAttribute} itself) is that an unanswerable CT
 * question degrades to <b>empty</b>, so the rule SKIPs — never to a plausible-looking answer from
 * the wrong place. Before the fix, {@code resolveCtPackage}'s last resort answered a request for
 * package X with the <em>configured</em> package Y, so a controlled-terminology check ran against
 * the wrong CT version and reported a clean verdict.
 * </p>
 */
class MetadataLibraryProviderCtPackageResolutionTest
{

    private static final String CONFIGURED_ID = "sdtmct-2021-12-17";

    private static final String OTHER_ID = "sdtmct-2024-03-29";

    /** One codelist ({@code NY}), one term — every value distinct per package. */
    private static CtPackage ct(String aCodelistCcode, String aTermValue)
    {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("conceptId", aCodelistCcode + "-T");
        term.put("submissionValue", aTermValue);
        term.put("preferredTerm", aTermValue + " preferred");
        Map<String, Object> codelist = new LinkedHashMap<>();
        codelist.put("conceptId", aCodelistCcode);
        codelist.put("submissionValue", "NY");
        codelist.put("terms", List.of(term));
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("codelists", List.of(codelist));
        return MapResource.of(pkg, CtPackage.class);
    }


    private static MetadataLibraryProvider provider(String aConfiguredId,
            CtPackage aConfiguredPackage, Function<String, Optional<CtPackage>> aLoader)
    {
        IMetadataLibrary study = TestMetadataFixtures.lib("study").build();
        return new MetadataLibraryProvider(study, (SdtmProduct) null, null, "sdtmig", "3-4",
                aConfiguredId, aConfiguredPackage, aLoader);
    }


    /** C-01/C-02: an exact configured-id match answers from the configured package. */
    @Test
    void configuredIdMatch_answersFromConfiguredPackage()
    {
        MetadataLibraryProvider provider = provider(CONFIGURED_ID, ct("C2021", "TERM2021"),
                id -> Optional.of(ct("CLOADED", "TERMLOADED")));

        assertEquals(List.of("TERM2021"),
                provider.getCodelistAttribute(CONFIGURED_ID, "Term Submission Value"));
        assertEquals(List.of("C2021"),
                provider.getCodelistAttribute(CONFIGURED_ID, "Codelist CCODE"));
    }


    /** C-03/C-04/C-05: a non-configured id is served by the loader, not the configured package. */
    @Test
    void otherId_loaderHit_answersFromLoadedPackage()
    {
        MetadataLibraryProvider provider = provider(CONFIGURED_ID, ct("C2021", "TERM2021"),
                id -> OTHER_ID.equals(id) ? Optional.of(ct("C2024", "TERM2024"))
                        : Optional.empty());

        assertEquals(List.of("TERM2024"),
                provider.getCodelistAttribute(OTHER_ID, "Term Submission Value"));
        assertEquals(List.of("C2024-T"), provider.getCodelistAttribute(OTHER_ID, "Term CCODE"));
    }


    /**
     * C-06/C-07, the F-corej-L2-02 fix: an id that is neither configured nor loadable must answer
     * EMPTY (⇒ the rule SKIPs) — never the configured package's terms, which belong to a different
     * CT version and would make the check report a clean verdict against the wrong terminology.
     */
    @Test
    void otherId_loaderMiss_answersEmpty_neverTheConfiguredPackage()
    {
        MetadataLibraryProvider provider = provider(CONFIGURED_ID, ct("C2021", "TERM2021"),
                id -> Optional.empty());

        assertEquals(List.of(), provider.getCodelistAttribute(OTHER_ID, "Term Submission Value"));
        assertEquals(List.of(), provider.getCodelistAttribute(OTHER_ID, "Codelist CCODE"));
    }


    /** Same refusal with no loader at all (C-03 false side + C-06). */
    @Test
    void otherId_noLoader_answersEmpty()
    {
        MetadataLibraryProvider provider = provider(CONFIGURED_ID, ct("C2021", "TERM2021"), null);

        assertEquals(List.of(), provider.getCodelistAttribute(OTHER_ID, "Term Submission Value"));
    }


    /**
     * The pickle path configures the EMPTY package under a {@code null} id ("no CT"). A request for
     * any concrete id then reads as "no terms" — exactly what the old last-resort fallback produced
     * there too, so this documented case is unchanged by the fix.
     */
    @Test
    void nullConfiguredId_loaderMiss_answersEmpty()
    {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("codelists", List.of());
        MetadataLibraryProvider provider = provider(null, MapResource.of(empty, CtPackage.class),
                id -> Optional.empty());

        assertEquals(List.of(), provider.getCodelistAttribute(OTHER_ID, "Term Submission Value"));
    }


    /**
     * C-10 companion: an unknown attribute on a RESOLVABLE package degrades to empty, not throw.
     */
    @Test
    void unknownAttribute_degradesToEmpty()
    {
        MetadataLibraryProvider provider = provider(CONFIGURED_ID, ct("C2021", "TERM2021"), null);

        assertTrue(provider.getCodelistAttribute(CONFIGURED_ID, "No Such Attribute").isEmpty());
    }

}
