package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.metadata.ValueMapDictionary;
import net.cumba.cdisc.core.metadata.dictionary.HouseFormatValidator;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * T1 — external-dictionary validation (dummy value-map dictionaries). Exercises the native
 * regenerated form of the authored FDA rules end-to-end through {@link RuleRunner}: a
 * {@code valid_external_dictionary_*} operation inlined behind a
 * {@code dictionary_available(<type>)} precondition gate. Both engines read the SAME checked-in
 * {@code dictionaries/*.json}; these tests pin the coreJ verdicts.
 */
class DictionaryValidationTest
{

    /**
     * The checked-in dummy dictionaries, resolved relative to the module basedir (surefire CWD).
     */
    private static final RuntimeDictionaryProvider DICTS = load();

    private static RuntimeDictionaryProvider load()
    {
        try
        {
            return RuntimeDictionaryProvider.loadDirectory(Paths.get("dictionaries"));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("could not load dummy dictionaries", e);
        }
    }


    private static Rule rule(String check, String precondition) throws Exception
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Precondition\":{\"expression\":\"" + precondition + "\"}}}}";
        Rule r = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(r, "rule loads");
        assertNull(r.getLoadError(), "clean load: " + r.getLoadError());
        assertNotNull(r.getCheckExpr(), "native checkExpr");
        assertNotNull(r.getPreconditionExpr(), "precondition gate raised to a broadcast verdict");
        return r;
    }


    private static RuleExecutionResult run(Rule r, IDataTable t, RuntimeDictionaryProvider dicts)
    {
        return RuleRunner.execute(r, t, name -> name.equals(t.getMetaData().getName()) ? t : null,
                null, null, null, null, Integer.MAX_VALUE, null, dicts);
    }


    /**
     * KDICT-F1 / Fix #268 — the <b>declared</b> ({@code $}-ref) authoring form, which is the form
     * every one of the 98 shipped dictionary rules uses: the operation sits in {@code Operations}
     * and the Check reads its {@code $}-variable. Deliberately builds <b>no</b>
     * {@code Precondition} and asserts none was injected, so a SKIP observed by these tests can
     * only come from {@link RuleRunner}'s eager dictionary arm — not from a gate the test itself
     * authored.
     */
    private static Rule declaredRule(String check, String... operationExpressions) throws Exception
    {
        StringBuilder ops = new StringBuilder();
        for (int i = 0; i < operationExpressions.length; i++)
        {
            if (i > 0)
            {
                ops.append(',');
            }
            ops.append("{\"id\":\"$op").append(i).append("\",\"expression\":\"")
                    .append(operationExpressions[i]).append("\"}");
        }
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T\"}," + "\"Sensitivity\":\"Record\","
                + "\"Operations\":[" + ops + "]," + "\"Check\":{\"expression\":\"" + check
                + "\"}}}}";
        Rule r = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(r, "rule loads");
        assertNull(r.getLoadError(), "clean load: " + r.getLoadError());
        assertNull(r.getPrecondition(), "the declared form carries NO availability gate — exactly"
                + " like the shipped corpus, which has zero Preconditions");
        assertNull(r.getInjectedPreconditionGates(),
                "the loader injects gates for INLINED calls only; a declared $-ref gets none");
        return r;
    }


    /** The SKIP reason, asserted present so a null message cannot silently satisfy a contains(). */
    private static String skipReason(RuleExecutionResult result)
    {
        String m = result.getStatusMessage();
        assertNotNull(m, "a SKIP must carry a reason");
        return m;
    }


    /**
     * A bundle holding <b>only</b> MedDRA — the "provider present but this rule's type absent"
     * case. {@code dictionaryProvider != null} must not be mistaken for "this rule can run".
     */
    private static RuntimeDictionaryProvider meddraOnly()
    {
        try
        {
            return new RuntimeDictionaryProvider(Map.of("meddra",
                    ValueMapDictionary.load(Paths.get("dictionaries/meddra.json"))));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("could not load the dummy MedDRA dictionary", e);
        }
    }

    // -- SD0008 membership -------------------------------------------------


    @Test
    void membershipValidTermDoesNotFireInvalidFires() throws Exception
    {
        // Mirrors the shipped SD0008 shape: the sheet says Case-insensitive, so the rule authors
        // an explicit case_sensitive=false (D-TA-3: insensitive intent must be visible).
        Rule r = rule(
                "valid_external_dictionary_value(AEDECOD, case_sensitive=false, "
                        + "dictionary_term_type=\\\"PT\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        // HEADACHE valid case-folded (no fire), FOOBAR invalid (fire), "" empty (no fire).
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("AEDECOD", "HEADACHE", "FOOBAR", "").name("AE").build();
        RuleExecutionResult result = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid term fires");
    }


    @Test
    void membershipDefaultIsCaseSensitive() throws Exception
    {
        // D-TA-3 / Fix #266: a flag-less membership rule validates against the dictionary's
        // preferred case — the default compare is case-SENSITIVE.
        Rule r = rule(
                "valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\\"PT\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        // "Headache" is the preferred case (no fire); "HEADACHE" is a case mismatch (fire);
        // FOOBAR is not a term at all (fire); "" empty (no fire).
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4")
                .col("AEDECOD", "Headache", "HEADACHE", "FOOBAR", "").name("AE").build();
        RuleExecutionResult result = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(2, result.getViolations().size(),
                "the case-mismatched term and the non-term fire; the preferred case does not");
    }


    @Test
    void membershipSkipsWithNoDictionary() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\\"PT\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "FOOBAR").name("AE")
                .build();
        // No dictionary provider ⇒ dictionary_available folds FALSE ⇒ SKIP, never a false PASS.
        RuleExecutionResult result = run(r, ae, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "no dictionary ⇒ rule SKIPs (invalid term is NOT silently passed)");
        assertEquals(0, result.getViolations().size());
    }

    // -- SD0008C case ------------------------------------------------------


    @Test
    void caseMismatchFires() throws Exception
    {
        Rule r = rule("valid_external_dictionary_value(AEDECOD, case_sensitive=true, "
                + "dictionary_term_type=\\\"PT\\\", external_dictionary_type=\\\"meddra\\\") "
                + "== false", "dictionary_available(\\\"meddra\\\")");
        // "Headache" is the MedDRA preferred case (no fire); "HEADACHE" is the wrong case (fire).
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2")
                .col("AEDECOD", "Headache", "HEADACHE").name("AE").build();
        RuleExecutionResult result = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the wrong-case term fires");
    }

    // -- SD2007 code membership --------------------------------------------


    @Test
    void codeMembershipValidCodeDoesNotFireInvalidFires() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_code(AEPTCD, dictionary_term_type=\\\"PTCD\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        // 10019211 (Headache) is a valid PT code (no fire), 99999999 is not (fire), "" empty (no
        // fire).
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("AEPTCD", "10019211", "99999999", "").name("AE").build();
        RuleExecutionResult result = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid code fires");
    }


    @Test
    void codeMembershipDefaultIsCaseSensitiveAndFalseFolds() throws Exception
    {
        // D-TA-3 / Fix #266: valid_external_dictionary_code shares the _value evaluator and flag
        // (dispatch maps both to the same arm). Default: a case-mismatched code fires.
        String check = "valid_external_dictionary_code(TSVALCD, dictionary_term_type=\\\"UNII\\\", "
                + "external_dictionary_type=\\\"unii\\\") == false";
        IDataTable ts = MockTable.of().col("TSPARMCD", "TRT", "TRT")
                .col("TSVALCD", "R16CO5Y76E", "r16co5y76e").name("TS").build();
        RuleExecutionResult sensitive = run(rule(check, "dictionary_available(\\\"unii\\\")"), ts,
                DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, sensitive.getStatus());
        assertEquals(1, sensitive.getViolations().size(),
                "default sensitive: only the case-mismatched code fires");
        // Authored case_sensitive=false: the same data folds and nothing fires.
        Rule insensitive = rule(
                "valid_external_dictionary_code(TSVALCD, case_sensitive=false, "
                        + "dictionary_term_type=\\\"UNII\\\", "
                        + "external_dictionary_type=\\\"unii\\\") == false",
                "dictionary_available(\\\"unii\\\")");
        RuleExecutionResult folded = run(insensitive, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, folded.getStatus());
        assertEquals(0, folded.getViolations().size(), "case_sensitive=false: membership folds");
    }


    @Test
    void codeMembershipSkipsWithNoDictionary() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_code(AEPTCD, dictionary_term_type=\\\"PTCD\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1").col("AEPTCD", "99999999").name("AE")
                .build();
        // No dictionary provider ⇒ dictionary_available folds FALSE ⇒ SKIP, never a false PASS.
        RuleExecutionResult result = run(r, ae, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "no dictionary ⇒ rule SKIPs (invalid code is NOT silently passed)");
        assertEquals(0, result.getViolations().size());
    }

    // -- SD2262 code<->decode pair ----------------------------------------


    @Test
    void codeDecodePairMismatchFires() throws Exception
    {
        Rule r = rule(
                "TSPARMCD == \\\"TRT\\\" and valid_external_dictionary_code_term_pair(TSVALCD, "
                        + "external_dictionary_term_variable=\\\"TSVAL\\\", "
                        + "external_dictionary_type=\\\"unii\\\") == false",
                "dictionary_available(\\\"unii\\\")");
        // R16CO5Y76E decodes to ASPIRIN (aligned, no fire); the second row's decode is wrong
        // (fire).
        IDataTable ts = MockTable.of().col("TSPARMCD", "TRT", "TRT")
                .col("TSVALCD", "R16CO5Y76E", "R16CO5Y76E").col("TSVAL", "ASPIRIN", "IBUPROFEN")
                .name("TS").build();
        RuleExecutionResult result = run(r, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the mismatched code/decode fires");
    }


    @Test
    void codeDecodePairBlankCellDoesNotFire() throws Exception
    {
        // H1: a blank code OR blank decode is a valid pair — completeness is a different rule's
        // concern. Neither the blank-code row nor the blank-decode row may fire the `== false`
        // consequent; only the genuinely mismatched pair does.
        Rule r = rule(
                "TSPARMCD == \\\"TRT\\\" and valid_external_dictionary_code_term_pair(TSVALCD, "
                        + "external_dictionary_term_variable=\\\"TSVAL\\\", "
                        + "external_dictionary_type=\\\"unii\\\") == false",
                "dictionary_available(\\\"unii\\\")");
        // Row1: blank code (no fire); Row2: blank decode (no fire); Row3: genuine mismatch (fire).
        IDataTable ts = MockTable.of().col("TSPARMCD", "TRT", "TRT", "TRT")
                .col("TSVALCD", "", "R16CO5Y76E", "R16CO5Y76E")
                .col("TSVAL", "ASPIRIN", "", "IBUPROFEN").name("TS").build();
        RuleExecutionResult result = run(r, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(),
                "blank code/decode cells do not fire; only the real mismatch does");
    }


    @Test
    void codeDecodePairDefaultIsCaseSensitiveAndFalseFolds() throws Exception
    {
        // D-TA-3 / Fix #266: the pair operation reads the flag (pre-#266 it was ignored: code
        // folded, decode verbatim). Default: code AND decode compare against the as-authored
        // dictionary entries.
        String check = "TSPARMCD == \\\"TRT\\\" and "
                + "valid_external_dictionary_code_term_pair(TSVALCD, "
                + "external_dictionary_term_variable=\\\"TSVAL\\\", "
                + "external_dictionary_type=\\\"unii\\\") == false";
        // Row1 exact (no fire); row2 decode case-mismatch (fire); row3 code case-mismatch (fire).
        IDataTable ts = MockTable.of().col("TSPARMCD", "TRT", "TRT", "TRT")
                .col("TSVALCD", "R16CO5Y76E", "R16CO5Y76E", "r16co5y76e")
                .col("TSVAL", "ASPIRIN", "Aspirin", "ASPIRIN").name("TS").build();
        RuleExecutionResult sensitive = run(rule(check, "dictionary_available(\\\"unii\\\")"), ts,
                DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, sensitive.getStatus());
        assertEquals(2, sensitive.getViolations().size(),
                "default sensitive: case-mismatched decode and code both fire");
        // Authored case_sensitive=false: both sides fold and nothing fires.
        Rule insensitive = rule("TSPARMCD == \\\"TRT\\\" and "
                + "valid_external_dictionary_code_term_pair(TSVALCD, " + "case_sensitive=false, "
                + "external_dictionary_term_variable=\\\"TSVAL\\\", "
                + "external_dictionary_type=\\\"unii\\\") == false",
                "dictionary_available(\\\"unii\\\")");
        RuleExecutionResult folded = run(insensitive, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, folded.getStatus());
        assertEquals(0, folded.getViolations().size(),
                "case_sensitive=false: code and decode fold consistently");
    }

    // -- SE2229 NEOPLASM attribute alignment ------------------------------


    @Test
    void neoplasmAttributeMisalignmentFires() throws Exception
    {
        Rule r = rule(
                "MIRESCAT in [\\\"BENIGN\\\", \\\"MALIGNANT\\\"] and "
                        + "valid_external_dictionary_code_term_pair(MISTRESC, "
                        + "external_dictionary_term_variable=\\\"MIRESCAT\\\", "
                        + "external_dictionary_type=\\\"neoplasm\\\") == false",
                "dictionary_available(\\\"neoplasm\\\")");
        // Adenoma is BENIGN (aligned, no fire); Carcinoma is MALIGNANT but tagged BENIGN (fire);
        // NORMAL is outside BENIGN/MALIGNANT so the row is not checked (no fire). The terms carry
        // the NEOPLASM preferred case: since C1 the attributes keys are the levels' preferred form
        // and the sensitive default compares them verbatim.
        IDataTable mi = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("MISTRESC", "Adenoma", "Carcinoma", "Carcinoma")
                .col("MIRESCAT", "BENIGN", "BENIGN", "NORMAL").name("MI").build();
        RuleExecutionResult result = run(r, mi, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(),
                "only the benign/malignant-misaligned record fires");
    }

    // -- SD2250 FDA-SRS/UNII preferred-term membership ---------------------


    @Test
    void uniiSrsMembershipInvalidTermFires() throws Exception
    {
        Rule r = rule("TSPARMCD == \\\"TRT\\\" and valid_external_dictionary_value(TSVAL, "
                + "dictionary_term_type=\\\"SRS\\\", external_dictionary_type=\\\"unii\\\") "
                + "== false", "dictionary_available(\\\"unii\\\")");
        // ASPIRIN is a valid FDA-SRS substance name (no fire); UNOBTAINIUM is not (fire).
        IDataTable ts = MockTable.of().col("TSPARMCD", "TRT", "TRT")
                .col("TSVAL", "ASPIRIN", "UNOBTAINIUM").name("TS").build();
        RuleExecutionResult result = run(r, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid SRS term fires");
    }

    // -- SD2257 SNOMED CT term membership ---------------------------------


    @Test
    void snomedTermMembershipInvalidFires() throws Exception
    {
        Rule r = rule(
                "TSPARMCD == \\\"INDIC\\\" and valid_external_dictionary_value(TSVAL, "
                        + "dictionary_term_type=\\\"SNOMED\\\", "
                        + "external_dictionary_type=\\\"snomed\\\") == false",
                "dictionary_available(\\\"snomed\\\")");
        // "Headache" is a valid SNOMED term (no fire); "Flibbertigibbet" is not (fire).
        IDataTable ts = MockTable.of().col("TSPARMCD", "INDIC", "INDIC")
                .col("TSVAL", "Headache", "Flibbertigibbet").name("TS").build();
        RuleExecutionResult result = run(r, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid SNOMED term fires");
    }

    // -- SD2259 SNOMED CT code<->term pair --------------------------------


    @Test
    void snomedCodeTermPairMismatchFires() throws Exception
    {
        Rule r = rule(
                "TSPARMCD == \\\"INDIC\\\" and valid_external_dictionary_code_term_pair(TSVALCD, "
                        + "external_dictionary_term_variable=\\\"TSVAL\\\", "
                        + "external_dictionary_type=\\\"snomed\\\") == false",
                "dictionary_available(\\\"snomed\\\")");
        // 25064002 decodes to Headache (aligned, no fire); paired with Nausea it is wrong (fire).
        IDataTable ts = MockTable.of().col("TSPARMCD", "INDIC", "INDIC")
                .col("TSVALCD", "25064002", "25064002").col("TSVAL", "Headache", "Nausea")
                .name("TS").build();
        RuleExecutionResult result = run(r, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(),
                "only the mismatched SNOMED code/term fires");
    }

    // -- SD1345 WHODrug ATC-text membership -------------------------------


    @Test
    void whodrugAtcMembershipInvalidFires() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_value(CMCLAS, dictionary_term_type=\\\"ATC\\\", "
                        + "external_dictionary_type=\\\"whodrug\\\") == false",
                "dictionary_available(\\\"whodrug\\\")");
        // "Analgesics" is a valid ATC text (no fire); "Wonderdrugs" is not (fire).
        IDataTable cm = MockTable.of().col("USUBJID", "S1", "S2")
                .col("CMCLAS", "Analgesics", "Wonderdrugs").name("CM").build();
        RuleExecutionResult result = run(r, cm, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid ATC text fires");
    }

    // -- SD2263 MED-RT term membership (Java-only dictionary) --------------


    @Test
    void medrtTermMembershipInvalidFires() throws Exception
    {
        Rule r = rule("TSPARMCD == \\\"PCLAS\\\" and valid_external_dictionary_value(TSVAL, "
                + "dictionary_term_type=\\\"MEDRT\\\", external_dictionary_type=\\\"medrt\\\") "
                + "== false", "dictionary_available(\\\"medrt\\\")");
        // "Cyclooxygenase Inhibitors" is a valid MED-RT term (no fire); a bogus class fires.
        IDataTable ts = MockTable.of().col("TSPARMCD", "PCLAS", "PCLAS")
                .col("TSVAL", "Cyclooxygenase Inhibitors", "Nonsense Class").name("TS").build();
        RuleExecutionResult result = run(r, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid MED-RT term fires");
    }

    // -- SD2285 LOINC code membership -------------------------------------


    @Test
    void loincCodeMembershipInvalidFires() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_value(LBLOINC, dictionary_term_type=\\\"LOINC\\\", "
                        + "external_dictionary_type=\\\"loinc\\\") == false",
                "dictionary_available(\\\"loinc\\\")");
        // 1558-6 is a valid LOINC code (no fire); 9999-9 is not (fire).
        IDataTable lb = MockTable.of().col("USUBJID", "S1", "S2").col("LBLOINC", "1558-6", "9999-9")
                .name("LB").build();
        RuleExecutionResult result = run(r, lb, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid LOINC code fires");
    }

    // -- CG0460/CG0461 MedDRA hierarchy-path consistency ------------------


    @Test
    void hierarchyPathOnPathDoesNotFireOffPathFires() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_hierarchy(AEDECOD, dictionary_parent=\\\"AESOC\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        // Headache has Nervous system disorders on its hierarchy path (aligned, no fire);
        // pairing Headache with Gastrointestinal disorders is off-path (fire). Both operands are
        // in the MedDRA preferred case — since C1 the hierarchy is authored that way.
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2")
                .col("AEDECOD", "Headache", "Headache")
                .col("AESOC", "Nervous system disorders", "Gastrointestinal disorders").name("AE")
                .build();
        RuleExecutionResult result = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the off-path record fires");
    }


    @Test
    void hierarchyPathBlankChildOrParentDoesNotFire() throws Exception
    {
        // A blank child OR blank parent is on-path — completeness is a different rule's concern.
        Rule r = rule(
                "valid_external_dictionary_hierarchy(AEDECOD, dictionary_parent=\\\"AESOC\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        // Row1: blank child (no fire); Row2: blank parent (no fire); Row3: genuine off-path (fire).
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("AEDECOD", "", "Headache", "Headache")
                .col("AESOC", "Nervous system disorders", "", "Gastrointestinal disorders")
                .name("AE").build();
        RuleExecutionResult result = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(),
                "blank child/parent cells do not fire; only the real off-path record does");
    }


    @Test
    void hierarchyPathDefaultIsCaseSensitiveAndFalseFolds() throws Exception
    {
        // D-TA-3 / Fix #266: the hierarchy operation reads the flag (pre-#266 it was ignored and
        // both operands folded). Since the C1 data repair the dummy meddra hierarchy is authored
        // in the levels' preferred case, so an upper-case child misses sensitively but folds
        // cleanly under case_sensitive=false.
        String check = "valid_external_dictionary_hierarchy(AEDECOD, "
                + "dictionary_parent=\\\"AESOC\\\", "
                + "external_dictionary_type=\\\"meddra\\\") == false";
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2")
                .col("AEDECOD", "HEADACHE", "Headache")
                .col("AESOC", "Nervous system disorders", "Nervous system disorders").name("AE")
                .build();
        RuleExecutionResult sensitive = run(rule(check, "dictionary_available(\\\"meddra\\\")"), ae,
                DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, sensitive.getStatus());
        assertEquals(1, sensitive.getViolations().size(),
                "default sensitive: only the case-mismatched child fires");
        Rule insensitive = rule(
                "valid_external_dictionary_hierarchy(AEDECOD, case_sensitive=false, "
                        + "dictionary_parent=\\\"AESOC\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        RuleExecutionResult folded = run(insensitive, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, folded.getStatus());
        assertEquals(0, folded.getViolations().size(),
                "case_sensitive=false: child and parent fold");
    }

    // -- CG0096 WHODrug decode-presence -----------------------------------


    @Test
    void hasDecodeDefaultIsCaseSensitiveAndFalseFolds() throws Exception
    {
        // D-TA-3 / Fix #266: dictionary_has_decode reads the flag (pre-#266 it was ignored and the
        // code lookup folded). The dummy MED-RT pairs map decodes N0000000181; the lower-case
        // variant only resolves under case_sensitive=false.
        String check = "dictionary_has_decode(TSVALCD, "
                + "external_dictionary_type=\\\"medrt\\\") == false";
        IDataTable ts = MockTable.of().col("TSPARMCD", "PCLAS", "PCLAS")
                .col("TSVALCD", "N0000000181", "n0000000181").name("TS").build();
        RuleExecutionResult sensitive = run(rule(check, "dictionary_available(\\\"medrt\\\")"), ts,
                DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, sensitive.getStatus());
        assertEquals(1, sensitive.getViolations().size(),
                "default sensitive: only the case-mismatched code lacks a decode");
        Rule insensitive = rule(
                "dictionary_has_decode(TSVALCD, case_sensitive=false, "
                        + "external_dictionary_type=\\\"medrt\\\") == false",
                "dictionary_available(\\\"medrt\\\")");
        RuleExecutionResult folded = run(insensitive, ts, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, folded.getStatus());
        assertEquals(0, folded.getViolations().size(), "case_sensitive=false: the code folds");
    }

    // -- C1 preferred-case cross-consistency of the shipped dictionary data ---

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Delegates to the production validator. The contract used to live here as a private copy; the
     * installer must hold its own output to exactly this standard, so the implementation moved to
     * {@link HouseFormatValidator} and this test now exercises the same code the installer runs.
     * Two copies could have drifted, and the shipped fixtures would have been audited by the one
     * nothing else used.
     */
    private static List<String> caseContractViolations(String dict, JsonNode root)
    {
        return HouseFormatValidator.caseContractViolations(dict, root);
    }


    @Test
    void shippedDictionariesSatisfyThePreferredCaseContract() throws Exception
    {
        List<String> violations = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.list(Paths.get("dictionaries")))
        {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
            {
                scanned.add(f.getFileName().toString());
                violations.addAll(caseContractViolations(f.getFileName().toString(),
                        MAPPER.readTree(f.toFile())));
            }
        }
        // A glob that matched nothing would make the sweep vacuously green.
        assertEquals(
                Set.of("loinc.json", "meddra.json", "medrt.json", "neoplasm.json", "snomed.json",
                        "unii.json", "whodrug.json"),
                Set.copyOf(scanned), "every shipped dictionary is audited");
        assertTrue(violations.isEmpty(), "preferred-case contract: " + violations);
    }


    @Test
    void preferredCaseContractRejectsTheUnrepairedShapes() throws Exception
    {
        // The four pre-C1 shapes, one per repaired file: an upper-case hierarchy key (meddra), an
        // upper-case hierarchy ancestor (meddra), an upper-case pairs decode (medrt/snomed) and an
        // upper-case attributes key (neoplasm) — each against levels that carry the preferred
        // case. Keeps the sweep above honest: it must reject exactly these, and nothing else.
        String unrepaired = """
                {"type":"probe",
                 "levels":{"MEDRT":{"CYCLOOXYGENASE INHIBITORS":"Cyclooxygenase Inhibitors"},
                           "MEDRTCD":{"N0000000181":"N0000000181"},
                           "HLT":{"HEADACHES NEC":"Headaches NEC"},
                           "SOC":{"NERVOUS SYSTEM DISORDERS":"Nervous system disorders"},
                           "STRESC":{"ADENOMA":"Adenoma"}},
                 "hierarchy":{"HEADACHES NEC":["NERVOUS SYSTEM DISORDERS"]},
                 "pairs":{"medrt":{"N0000000181":"CYCLOOXYGENASE INHIBITORS"}},
                 "attributes":{"neoplasm":{"ADENOMA":"BENIGN"}}}
                """;
        List<String> bad = caseContractViolations("probe", MAPPER.readTree(unrepaired));
        assertEquals(4, bad.size(), "one violation per unrepaired shape: " + bad);
        assertTrue(bad.get(0).contains("hierarchy key 'HEADACHES NEC'"), bad.get(0));
        assertTrue(bad.get(1).contains("hierarchy ancestor of 'HEADACHES NEC'"), bad.get(1));
        assertTrue(bad.get(2).contains("pairs[medrt] value 'CYCLOOXYGENASE INHIBITORS'"),
                bad.get(2));
        assertTrue(bad.get(3).contains("attributes[neoplasm] key 'ADENOMA'"), bad.get(3));
        // BENIGN is a class, not a term of this dictionary, so the attribute VALUE is untouched.
        assertTrue(bad.stream().noneMatch(v -> v.contains("'BENIGN'")),
                "a non-term decode is unconstrained: " + bad);
        // The repaired twin of the same document is clean.
        String repaired = unrepaired
                .replace("\"HEADACHES NEC\":[\"NERVOUS SYSTEM DISORDERS\"]",
                        "\"Headaches NEC\":[\"Nervous system disorders\"]")
                .replace("\"N0000000181\":\"CYCLOOXYGENASE INHIBITORS\"",
                        "\"N0000000181\":\"Cyclooxygenase Inhibitors\"")
                .replace("\"neoplasm\":{\"ADENOMA\":\"BENIGN\"}",
                        "\"neoplasm\":{\"Adenoma\":\"BENIGN\"}");
        assertEquals(List.of(), caseContractViolations("probe", MAPPER.readTree(repaired)),
                "the repaired shapes are accepted");
    }


    @Test
    void hierarchyPathSkipsWithNoDictionary() throws Exception
    {
        Rule r = rule(
                "valid_external_dictionary_hierarchy(AEDECOD, dictionary_parent=\\\"AESOC\\\", "
                        + "external_dictionary_type=\\\"meddra\\\") == false",
                "dictionary_available(\\\"meddra\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "HEADACHE")
                .col("AESOC", "GASTROINTESTINAL DISORDERS").name("AE").build();
        // No dictionary provider ⇒ dictionary_available folds FALSE ⇒ SKIP, never a false PASS.
        RuleExecutionResult result = run(r, ae, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "no dictionary ⇒ rule SKIPs (off-path record is NOT silently passed)");
        assertEquals(0, result.getViolations().size());
    }

    // -- KDICT-F1 / Fix #268: the DECLARED ($-ref) form's eager skip ---------


    @Test
    void declaredFormSkipsWithNoDictionaryProvider() throws Exception
    {
        // The shipped PMDA-SD0008 shape, verbatim in structure: Operations + $-ref Check, no gate.
        // Before Fix #268 this EXECUTED and reported noViolation — a silent false PASS.
        Rule r = declaredRule("$op0 == false",
                "valid_external_dictionary_value(AEDECOD, case_sensitive=false, "
                        + "dictionary_term_type=\\\"PT\\\", "
                        + "external_dictionary_type=\\\"meddra\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "FOOBAR").name("AE")
                .build();
        RuleExecutionResult result = run(r, ae, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "no dictionary ⇒ the declared form SKIPs, it does not silently pass");
        assertEquals(0, result.getViolations().size());
        assertTrue(skipReason(result).contains("meddra"),
                "the skip names the missing dictionary type: " + skipReason(result));
    }


    @Test
    void declaredFormSkipsWhenTheBundleLacksItsType() throws Exception
    {
        // A non-null provider is NOT the test: a MedDRA-only bundle cannot answer a UNII rule.
        Rule r = declaredRule("$op0 == false",
                "valid_external_dictionary_value(TSVAL, dictionary_term_type=\\\"SRS\\\", "
                        + "external_dictionary_type=\\\"unii\\\")");
        IDataTable ts = MockTable.of().col("TSPARMCD", "TRT").col("TSVAL", "NOTADRUG").name("TS")
                .build();
        RuleExecutionResult result = run(r, ts, meddraOnly());
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a loaded MedDRA does not make a UNII rule answerable");
        assertTrue(skipReason(result).contains("unii"), skipReason(result));
    }


    @Test
    void declaredFormRunsWhenItsTypeIsLoaded() throws Exception
    {
        // The other half of the contract: the eager arm must not silence a rule whose dictionary
        // IS present — otherwise it would turn every dictionary scenario into a skip.
        Rule r = declaredRule("$op0 == false",
                "valid_external_dictionary_value(AEDECOD, case_sensitive=false, "
                        + "dictionary_term_type=\\\"PT\\\", "
                        + "external_dictionary_type=\\\"meddra\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1", "S2")
                .col("AEDECOD", "HEADACHE", "FOOBAR").name("AE").build();
        RuleExecutionResult result = run(r, ae, meddraOnly());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolations().size(), "only the invalid term fires");
    }


    @Test
    void declaredFormWithSeveralTypesSkipsUntilEveryTypeIsLoaded() throws Exception
    {
        // Design point 3 — partial availability. Mirrors the inlined gate, which ANDs one
        // dictionary_available(<type>) term per distinct type: a conjunction folds false when any
        // term does, so a partly-loaded bundle SKIPs.
        Rule r = declaredRule("$op0 == false or $op1 == false",
                "valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\\"PT\\\", "
                        + "external_dictionary_type=\\\"meddra\\\")",
                "valid_external_dictionary_value(AETRT, dictionary_term_type=\\\"SRS\\\", "
                        + "external_dictionary_type=\\\"unii\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "Headache")
                .col("AETRT", "ASPIRIN").name("AE").build();
        RuleExecutionResult partial = run(r, ae, meddraOnly());
        assertEquals(RuleExecutionStatus.SKIPPED, partial.getStatus(),
                "one loaded type out of two is still unanswerable");
        assertTrue(skipReason(partial).contains("unii"), skipReason(partial));
        assertTrue(!skipReason(partial).contains("meddra"),
                "only the MISSING type is named: " + skipReason(partial));
        RuleExecutionResult full = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, full.getStatus(),
                "with both types loaded the rule runs");
        assertEquals(0, full.getViolations().size(), "both values are valid terms");
    }


    @Test
    void aDeclaredDictionaryAvailableOperationIsNeverEagerSkipped() throws Exception
    {
        // Design point 1 — dictionary_available IS the gate. isDictionaryDependent includes it,
        // but its executor arm is total (Boolean.FALSE with no provider, never null), so
        // eager-skipping on it would destroy the reporting it exists for. Excluded from the arm.
        Rule r = declaredRule("$op0 == false",
                "dictionary_available(external_dictionary_type=\\\"meddra\\\")");
        IDataTable ae = MockTable.of().col("USUBJID", "S1").col("AEDECOD", "FOOBAR").name("AE")
                .build();
        RuleExecutionResult none = run(r, ae, null);
        assertEquals(RuleExecutionStatus.EXECUTED, none.getStatus(),
                "the gate operation reports absence; it must not itself trigger a skip");
        assertEquals(1, none.getViolations().size(),
                "dictionary_available folds false with no provider, so `== false` fires");
        RuleExecutionResult loaded = run(r, ae, DICTS);
        assertEquals(RuleExecutionStatus.EXECUTED, loaded.getStatus());
        assertEquals(0, loaded.getViolations().size(), "with MedDRA loaded the gate is true");
    }
}
