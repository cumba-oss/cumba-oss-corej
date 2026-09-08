package net.cumba.corej.core.exec;

import static net.cumba.datatable.testkit.TestMetadataFixtures.codelist;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * The full {@code (level, returntype)} matrix of {@code codelist_terms}, mirroring Python's
 * {@code operations/codelist_terms.py::_get_codelist_values}. Six shapes are implemented and three
 * of them ({@code (term,value)}, {@code (term,code)}, empty&nbsp;&rArr;&nbsp;SKIP) were already
 * covered by {@link OperationExecutorTest} / {@link OperationExecutorLibraryOpsTest}; this class
 * covers the rest, both <em>absent</em>-{@code returntype} defaults, both unrecognised-parameter
 * fallbacks, and the honest-degradation default.
 *
 * <pre>
 * level=term,     returntype=value           -> term submission value  (ICodelistEntry.getCodeValue)
 * level=term,     returntype=code            -> term concept id        (getConceptId)
 * level=term,     returntype=&lt;absent&gt;     -> term concept id        (Python's else arm)
 * level=term,     returntype=pref_term       -> term preferred term    (getDecodeValue)
 * level=codelist, returntype=value           -> codelist submission value
 * level=codelist, returntype=&lt;absent&gt;     -> codelist submission value (Python's else arm)
 * level=codelist, returntype=code            -> codelist concept id    (CODELIST_CONCEPT_ID)
 * level=codelist, returntype=pref_term       -> codelist preferred term (CODELIST_PREFERRED_TERM)
 * </pre>
 */
class OperationExecutorCodelistTermsShapesTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** Every projection of the fixture codelist is a distinct string, so no shape can alias. */
    private static ICodeList sexCodelist()
    {
        return codelist("SEX").extensible(Boolean.FALSE)
                .meta(MetadataKeys.CODELIST_SUBMISSION_VALUE, "SEX")
                .meta(MetadataKeys.CODELIST_CONCEPT_ID, "C66731")
                .meta(MetadataKeys.CODELIST_PREFERRED_TERM, "Sex").entry("M", "Male", "C20197")
                .entry("F", "Female", "C16576").build();
    }

    // -----------------------------------------------------------------------
    // level=term
    // -----------------------------------------------------------------------


    @Test
    void term_prefTerm_returnsPreferredTerms()
    {
        assertShape("term", "pref_term", List.of("Male", "Female"));
    }


    @Test
    void term_absentReturntype_defaultsToConceptIds()
    {
        // ⭐ Python's else arm at level="term": no returntype means concept ids, NOT submission
        // values. Answering ["M","F"] here is the regression this test exists to catch.
        assertShape("term", null, List.of("C20197", "C16576"));
    }


    @Test
    void term_unrecognisedReturntype_fallsToConceptIds()
    {
        // An unknown returntype is not an error — it takes the same else arm as "code".
        assertShape("term", "bogus", List.of("C20197", "C16576"));
    }

    // -----------------------------------------------------------------------
    // level=codelist
    // -----------------------------------------------------------------------


    @Test
    void codelist_value_returnsCodelistSubmissionValue()
    {
        assertShape("codelist", "value", List.of("SEX"));
    }


    @Test
    void codelist_absentReturntype_defaultsToSubmissionValue()
    {
        // ⭐ Python's else arm at level="codelist" is "value" — the mirror image of level="term",
        // where the same absence means "code". Getting these two crossed is the easiest regression
        // in the whole operation, which is why both defaults are pinned separately.
        assertShape("codelist", null, List.of("SEX"));
    }


    @Test
    void codelist_unrecognisedReturntype_fallsToSubmissionValue()
    {
        assertShape("codelist", "bogus", List.of("SEX"));
    }


    @Test
    void codelist_code_returnsCodelistConceptId()
    {
        assertShape("codelist", "code", List.of("C66731"));
    }


    @Test
    void codelist_prefTerm_returnsCodelistPreferredTerm()
    {
        assertShape("codelist", "pref_term", List.of("Sex"));
    }


    @Test
    void codelist_value_fallsBackToCodelistNameWhenSubmissionValueMetaAbsent()
    {
        ICodeList noMeta = codelist("QSCAT").entry("M", "Male", "C20197").build();
        assertEquals(List.of("QSCAT"), run(new RichProvider(noMeta), "codelist", "value"));
    }

    // -----------------------------------------------------------------------
    // Degenerate parameters
    // -----------------------------------------------------------------------


    @Test
    void unrecognisedLevel_contributesNothing_soTheRuleSkips()
    {
        // Python appends nothing for a level that is neither "term" nor "codelist"; the empty
        // union then reaches the CODELIST_TERMS arm's empty check and demotes to a SKIP.
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE,
                run(new RichProvider(sexCodelist()), "bogus", "value"));
    }


    @Test
    void absentLevel_contributesNothing_soTheRuleSkips()
    {
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE,
                run(new RichProvider(sexCodelist()), null, "value"));
    }

    // -----------------------------------------------------------------------
    // The honest default
    // -----------------------------------------------------------------------


    @Test
    void providerWithoutGetCodelist_servesTermValueAndSkipsEveryOtherShape()
    {
        // ⚠⚠ ANTI-REGRESSION — do not "helpfully" default getCodelist to something derived from
        // getCodelistTerms. A MetadataProvider that implements only getCodelistTerms knows term
        // submission values and NOTHING else: no concept ids, no preferred terms, no codelist-level
        // attributes. Deriving the other five shapes from it would answer submission values under
        // every returntype — exactly the defect this plan fixed, and it would be silent, because
        // the rule would validate happily against the wrong representation instead of skipping.
        // The honest answer for those shapes is empty ⇒ LIBRARY_NOT_AVAILABLE ⇒ the rule SKIPs.
        TermsOnlyProvider p = new TermsOnlyProvider();

        assertEquals(List.of("M", "F"), run(p, "term", "value"));

        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "term", "code"));
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "term", null));
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "term", "pref_term"));
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "codelist", "value"));
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "codelist", null));
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "codelist", "code"));
        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE, run(p, "codelist", "pref_term"));
    }

    // -----------------------------------------------------------------------
    // The placeholder decode — review finding F1
    // -----------------------------------------------------------------------


    @Test
    void term_prefTerm_allTermsLackAPreferredTerm_skipsInsteadOfProjectingABlank()
    {
        // ⚠⚠ ANTI-REGRESSION. ICodelistEntry.getDecodeValue() is declared as a plain `String`,
        // NOT @Nullable (unlike its sibling getConceptId()), so a builder with no preferred term
        // to report has no way to say "absent" and substitutes "" instead — every one of them
        // does: CdiscLibraryMetadataLibrary.buildCodelist uses
        // `term.preferredTerm().orElse("")`, MapBackedLibraryMetadataProvider.getCodelist uses
        // `mappings.getOrDefault(term, "")`, and the stubs do the same. Because the substitution
        // is uniform, NO fixture built from those providers can catch it — which is why the
        // codelist here is hand-built with blank decodes.
        //
        // The projection is therefore the only place absence can be recovered, and it must be:
        // "" passes codelistTerms()' .filter(Objects::nonNull), .distinct() collapses the whole
        // codelist to the single element [""], the list is NON-EMPTY, and the CODELIST_TERMS
        // arm's empty-list check never fires. The rule then executes against [""] — silently
        // validating the data against one meaningless value — where Python raises KeyError and
        // where every other unanswerable shape in this class SKIPs. Assert the SKIP, never [""].
        ICodeList noPrefTerms = codelist("SEX").meta(MetadataKeys.CODELIST_SUBMISSION_VALUE, "SEX")
                .entry("M", "", "C20197").entry("F", "", "C16576").build();

        assertSame(OperationExecutor.LIBRARY_NOT_AVAILABLE,
                run(new RichProvider(noPrefTerms), "term", "pref_term"));

        // The other five shapes are unaffected — the blank decode is not a broken codelist.
        assertEquals(List.of("M", "F"), run(new RichProvider(noPrefTerms), "term", "value"));
        assertEquals(List.of("C20197", "C16576"),
                run(new RichProvider(noPrefTerms), "term", "code"));
        assertEquals(List.of("SEX"), run(new RichProvider(noPrefTerms), "codelist", "value"));
    }


    @Test
    void term_prefTerm_someTermsLackAPreferredTerm_dropsOnlyTheBlanks()
    {
        // Partial absence must degrade per term, exactly like a null concept id does: the terms
        // that HAVE a preferred term are still projected, the ones that do not contribute
        // nothing. A whole-list SKIP here would be over-correction, and answering ["Male", ""]
        // would be the original defect in miniature.
        ICodeList mixed = codelist("SEX").meta(MetadataKeys.CODELIST_SUBMISSION_VALUE, "SEX")
                .entry("M", "Male", "C20197").entry("F", "", "C16576").build();

        assertEquals(List.of("Male"), run(new RichProvider(mixed), "term", "pref_term"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static void assertShape(String aLevel, String aReturntype, List<String> aExpected)
    {
        assertEquals(aExpected, run(new RichProvider(sexCodelist()), aLevel, aReturntype));
    }


    /** Runs one {@code codelist_terms} operation over the named codelist and returns its value. */
    private static Object run(MetadataProvider aProvider, String aLevel, String aReturntype)
    {
        IDataTable table = MockTable.of().col("SEX", "M").name("DM").build();
        Operation op = new Operation();
        op.setId("$terms");
        op.setOperator("codelist_terms");
        op.setCodelists(List.of("SEX"));
        op.setLevel(aLevel);
        op.setReturntype(aReturntype);
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER,
                aProvider);
        return vars.get("$terms");
    }

    /** Serves the rich {@code getCodelist} view — every shape resolvable. */
    private static final class RichProvider extends BaseProvider
    {

        private final ICodeList codelist;

        RichProvider(ICodeList aCodelist)
        {
            this.codelist = aCodelist;
        }


        @Override
        public Optional<ICodeList> getCodelist(String aCodelistName)
        {
            return Optional.of(codelist);
        }


        @Override
        public List<String> getCodelistTerms(String aCodelistCode)
        {
            return List.of("M", "F");
        }
    }


    /**
     * The honest-degradation shape: term submission values and nothing else. {@code getCodelist} is
     * deliberately NOT overridden, so it takes {@link MetadataProvider}'s empty default.
     */
    private static final class TermsOnlyProvider extends BaseProvider
    {

        @Override
        public List<String> getCodelistTerms(String aCodelistCode)
        {
            return List.of("M", "F");
        }
    }


    /** Empty implementations of the interface's abstract methods; tests override what they need. */
    private abstract static class BaseProvider implements MetadataProvider
    {

        @Override
        public List<String> getRequiredVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String aDomain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String aDomain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String aCodelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String aDomain)
        {
            return Map.of();
        }


        @Override
        public boolean isCodelistExtensible(String aCodelistName)
        {
            return false;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String aCodelistName)
        {
            return Map.of();
        }


        @Override
        public String getStandard()
        {
            return "sdtmig";
        }


        @Override
        public String getVersion()
        {
            return "3.4";
        }
    }
}
