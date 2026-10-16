package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.CdiscLibraryMetadataLibrary;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.define.conformance.ct.CodelistClass;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code ct_class_terms(ct_class=, ct_role=)} — {@code PLAN-ct-class-exemption-data-rules} C8. The
 * provider is the production one ({@link MetadataLibraryProvider} over
 * {@link CdiscLibraryMetadataLibrary#fromStoredSdtm}), built from a <b>two-package merge</b> so the
 * class lookup is proven over the primary-wins codelist set the run really binds, not over a fake:
 * the head package's HAMD-17 lists win over the older package's, and a list only the older package
 * carries still answers.
 *
 * <p>
 * The contract under test: class AND role must match (the role because the class alone would admit
 * the Test Name list for a Test Code variable); the answer is term <b>submission values</b> only
 * (T7); a Questionnaire HAMD list is never a Clinical Classification one; a classless codelist
 * never matches; and an empty answer — no CC codelist, no CT, a provider without the lookup — is an
 * <b>answer</b>, never the SKIP {@code codelist_terms} throws (review L1).
 * </p>
 */
class CtClassTermsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CC = "Clinical Classification";

    /** The newer package — the precedence head. */
    private static final StoredCtPackage HEAD = new StoredCtPackage("sdtmct-2026-03-27", List.of(
            codelist("CCCAT", "C118971", "CDISC Clinical Classification Category Terminology",
                    term("HAMILTON DEPRESSION RATING SCALE 17-ITEM", "C100138")),
            codelist("HAMD1TC", "C100139",
                    "CDISC Clinical Classification HAMD 17 Test Code Terminology",
                    term("HAMD101", "C100140"), term("HAMD102", "C100141")),
            codelist("HAMD1TN", "C100142",
                    "CDISC Clinical Classification HAMD 17 Test Name Terminology",
                    term("HAMD1-Depressed Mood", "C100143"),
                    term("HAMD1-Feelings of Guilt", "C100144")),
            // a Questionnaire list of the same instrument family: never a Clinical Classification
            codelist("HAMD2TC", "C200001", "CDISC Questionnaire HAMD 21 Test Code Terminology",
                    term("HAMD201", "C200002")),
            // classless (no QRS prefix) and role-less (a CC prefix without a role suffix)
            codelist("SEX", "C66731", "CDISC SDTM Sex Terminology", term("M", "C20197"),
                    term("F", "C16576")),
            codelist("CCODD", "C300001", "CDISC Clinical Classification Odd Listing",
                    term("ODD01", "C300002"))));

    /** The older package: its HAMD1TC loses to the head's, its CGI list is unique to it. */
    private static final StoredCtPackage OLDER = new StoredCtPackage("sdtmct-2025-09-26",
            List.of(codelist("HAMD1TC", "C100139",
                    "CDISC Clinical Classification HAMD 17 Test Code Terminology",
                    term("HAMD1OLD", "C999999")),
                    codelist("CGI01TC", "C400001",
                            "CDISC Clinical Classification CGI Test Code Terminology",
                            term("CGI0101", "C400002"), term("HAMD101", "C100140"))));

    // -----------------------------------------------------------------------
    // the lookup
    // -----------------------------------------------------------------------

    @Test
    void classAndRoleMatchAnswersSubmissionValuesInCtOrderAcrossTheMerge()
    {
        MetadataProvider p = provider(HEAD, OLDER);
        // head's HAMD1TC wins (HAMD1OLD never appears); CGI01TC is only in OLDER and still
        // answers; HAMD101 is in both answering lists and appears once
        assertEquals(List.of("HAMD101", "HAMD102", "CGI0101"), terms(p, CC, "Test Code"));
        assertEquals(List.of("HAMD1-Depressed Mood", "HAMD1-Feelings of Guilt"),
                terms(p, CC, "Test Name"));
        assertEquals(List.of("HAMILTON DEPRESSION RATING SCALE 17-ITEM"), terms(p, CC, "Category"));
    }


    @Test
    void theAnswerIsSubmissionValuesNeverCodesOrPreferredTerms()
    {
        List<String> answer = terms(provider(HEAD, OLDER), CC, "Test Code");
        assertTrue(answer.stream().noneMatch(v -> v.startsWith("C1") || v.startsWith("C4")),
                "no NCI C-code in " + answer);
        assertTrue(answer.stream().noneMatch(v -> v.contains("HAMD 17")),
                "no preferred term in " + answer);
    }


    @Test
    void aQuestionnaireListNeverAnswersClinicalClassification()
    {
        MetadataProvider p = provider(HEAD, OLDER);
        assertFalse(terms(p, CC, "Test Code").contains("HAMD201"));
        // control: the Questionnaire list is found under its own class
        assertEquals(List.of("HAMD201"), terms(p, "Questionnaire", "Test Code"));
    }


    @Test
    void aClasslessOrRolelessCodelistNeverMatches()
    {
        MetadataProvider p = provider(HEAD, OLDER);
        for (String cls : LibraryLists.CT_CLASSES)
        {
            for (String role : LibraryLists.CT_ROLES)
            {
                List<String> answer = terms(p, cls, role);
                assertFalse(answer.contains("M") || answer.contains("ODD01"),
                        cls + "/" + role + " → " + answer);
            }
        }
    }


    @Test
    void theAnswerIsBuiltOncePerClassAndRole()
    {
        MetadataProvider p = provider(HEAD, OLDER);
        assertSame(p.getCodelistsOfClass(CC, "Test Code"), p.getCodelistsOfClass(CC, "Test Code"));
    }

    // -----------------------------------------------------------------------
    // the empty answer is an answer, never a SKIP
    // -----------------------------------------------------------------------


    @Test
    void noClinicalClassificationCodelistIsAnEmptyAnswerNotASkip()
    {
        StoredCtPackage sendLike = new StoredCtPackage("sendct-2026-03-27", List
                .of(codelist("SEX", "C66731", "CDISC SDTM Sex Terminology", term("M", "C20197"))));
        assertEquals(List.of(), terms(provider(sendLike), CC, "Test Code"));
    }


    @Test
    void noBoundCtIsAnEmptyAnswerNotASkip()
    {
        // the production no-CT shape: a single anonymous empty package (forSdtm)
        assertEquals(List.of(),
                terms(provider(new StoredCtPackage(null, List.of())), CC, "Category"));
    }


    @Test
    void aProviderWithoutTheLookupAnswersEmpty()
    {
        assertEquals(List.of(), terms(new StubMetadataProvider(), CC, "Test Code"));
    }


    @Test
    void noLibraryProviderSkipsAsEveryLibraryFunction()
    {
        IDataTable table = RealTables.of("RS").str("RSTESTCD", "HAMD101").build();
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(table).build());
        assertThrows(UnusableProviderAnswerException.class, () -> LibraryLists.ctClassTerms(run,
                List.of(ConstVector.of(CC), ConstVector.of("Test Code"))));
    }

    // -----------------------------------------------------------------------
    // the expression surface
    // -----------------------------------------------------------------------


    @Test
    void membershipReadsTheListPerRow() throws Exception
    {
        IDataTable table = RealTables.of("RS").str("RSTESTCD", "HAMD101", "ONCRSP", "HAMD1OLD")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(table)
                .libraryProvider(provider(HEAD, OLDER)).build();
        BitSet in = NativeExprEvaluator.evaluate(CheckExpressionParser
                .parse("RSTESTCD in ct_class_terms(ct_class=\"Clinical Classification\","
                        + " ct_role=\"Test Code\")"),
                ctx);
        BitSet expected = new BitSet();
        expected.set(0);
        assertEquals(expected, in);
    }


    @Test
    void bothParametersAreClosedVocabulariesHeldToALiteral() throws Exception
    {
        assertNull(loadError(
                "ct_class_terms(ct_class=\"Clinical Classification\", ct_role=\"Test Code\")"));
        assertLoadError("ct_class_terms(ct_class=\"Clinical\", ct_role=\"Test Code\")",
                "unknown `ct_class` value `Clinical`", "Clinical Classification");
        assertLoadError("ct_class_terms(ct_class=\"Clinical Classification\", ct_role=\"Code\")",
                "unknown `ct_role` value `Code`", "Test Code");
        assertLoadError("ct_class_terms(ct_class=RSCAT, ct_role=\"Test Code\")",
                "argument 'ct_class'", "static string literal");
        assertLoadError("ct_class_terms(ct_class=\"Clinical Classification\")", "ct_role");
    }


    @Test
    void theVocabulariesAreExactlyWhatCodelistClassReads()
    {
        for (String cls : LibraryLists.CT_CLASSES)
        {
            for (String role : LibraryLists.CT_ROLES)
            {
                assertEquals(Optional.of(new CodelistClass(cls, Optional.of(role))),
                        CodelistClass.of("CDISC " + cls + " XYZ 1 " + role + " Terminology"),
                        cls + " / " + role);
            }
        }
        assertEquals(3, LibraryLists.CT_CLASSES.size());
        assertEquals(5, LibraryLists.CT_ROLES.size());
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------


    private static List<String> terms(MetadataProvider aProvider, String aClass, String aRole)
    {
        IDataTable table = RealTables.of("RS").str("RSTESTCD", "HAMD101").build();
        EvalRun run = EvalRun.fullRange(
                EvaluationContext.builder().table(table).libraryProvider(aProvider).build());
        Vector v = LibraryLists.ctClassTerms(run,
                List.of(ConstVector.of(aClass), ConstVector.of(aRole)));
        Object member = ((ConstVector) v).memberValue();
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) Objects.requireNonNull(member))
        {
            out.add((String) o);
        }
        return out;
    }


    private static MetadataProvider provider(StoredCtPackage... aPackages)
    {
        StoredProduct ig = StoredProduct.builder().key("standards/sdtmig/3-4").name("SDTMIG")
                .version("3-4").build();
        CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromStoredSdtm("sdtmig",
                "3-4", ig, List.of(aPackages), List.of());
        return MetadataLibraryProvider.forStoredSdtm(library, ig, null, "sdtmig", "3-4",
                aPackages[0].id(), aPackages[0], null);
    }


    private static StoredCodelist codelist(String aSubmissionValue, String aConceptId,
            String aPreferredTerm, StoredTerm... aTerms)
    {
        return new StoredCodelist(aSubmissionValue, aConceptId, aSubmissionValue, aPreferredTerm,
                null, null, Boolean.TRUE, List.of(aTerms));
    }


    private static StoredTerm term(String aSubmissionValue, String aConceptId)
    {
        return new StoredTerm(aSubmissionValue, aConceptId, aSubmissionValue + " (preferred)", null,
                null);
    }


    private static @Nullable String loadError(String aBinding) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CT-CLASS"));
        rule.put("Bindings", List.of(Map.of("name", "$v", "expression", aBinding)));
        rule.put("Check", Map.of("expression", "not empty($v)"));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$v")));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = Objects
                .requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
        return loaded.getLoadError();
    }


    private static void assertLoadError(String aBinding, String... aFragments) throws Exception
    {
        String error = loadError(aBinding);
        assertNotNull(error, aBinding + " must be a load error");
        for (String fragment : aFragments)
        {
            assertTrue(error.contains(fragment),
                    aBinding + " → " + error + " // missing " + fragment);
        }
    }
}
