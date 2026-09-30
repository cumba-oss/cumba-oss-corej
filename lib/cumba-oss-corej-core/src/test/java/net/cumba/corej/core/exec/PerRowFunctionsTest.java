package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ColumnTypeMismatchException;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.UngatedProviderReachException;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.ValueMapDictionary;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The per-row registry functions of wave 3 ({@code PLAN-per-row-functions}): the semantics each
 * carries over from its retired operation (the claims {@code OperationExecutorGroupBFuOpsTest},
 * {@code OperationExecutorRowExtremeTest} and {@code DictionaryValidationTest} pinned on the
 * operation surface), the missing hand-through, {@code row_max}'s one named movement (D-W3-5: a
 * value ranks by its own type, so CHAR {@code "9"} beats CHAR {@code "12"}), the loud arms (the
 * dictionary tripwire, D-W1-3 (vii); the LIBRARY {@code UnusableProviderAnswerException}, Fix #369)
 * and the load-error negative controls (R1, R6, D-W3-4). Mockito-free: real tables, the checked-in
 * dummy dictionaries, a small hand-written Library, the real loader.
 */
class PerRowFunctionsTest
{

    private static final String MEDDRA = "meddra";

    // ============================================================ the dictionary functions

    @Test
    void membershipValidatesThePreferredCaseByDefaultAndAnyCaseWhenInsensitive() throws Exception
    {
        IDataTable ae = RealTables.of("AE")
                .str("AEDECOD", "Headache", "HEADACHE", "Migraine", "", null).build();
        EvalRun run = run(ae, dictionaries());
        Vector decod = column(ae, "AEDECOD");
        for (String name : List.of(DictionaryFunctions.VALUE, DictionaryFunctions.CODE))
        {
            BitSet sensitive = DictionaryFunctions.termMembership(run,
                    Arrays.asList(decod, ConstVector.of(MEDDRA), ConstVector.of("PT"), null), name);
            BitSet insensitive = DictionaryFunctions.termMembership(run, Arrays.asList(decod,
                    ConstVector.of(MEDDRA), ConstVector.of("PT"), ConstVector.of(false)), name);
            assertAll(name,
                    () -> assertEquals(bits(0, 3, 4), sensitive,
                            "D-TA-3: only the preferred case is valid; a blank or missing value is"
                                    + " valid (no fire)"),
                    () -> assertEquals(bits(0, 1, 3, 4), insensitive,
                            "case_sensitive=false: membership in any case"));
        }
    }


    @Test
    void membershipReadsTheCodeLevelTheRuleNames() throws Exception
    {
        // _code is the same implementation: the level decides what a member is. AELLTCD is Num in
        // SDTMIG 3.4, so the cell's text is what the dictionary's code level is keyed by.
        IDataTable ae = RealTables.of("AE").lng("AELLTCD", 10019211L, 1234567L, null).build();
        BitSet valid = DictionaryFunctions.termMembership(
                run(ae, dictionaries()), Arrays.asList(column(ae, "AELLTCD"),
                        ConstVector.of(MEDDRA), ConstVector.of("LLTCD"), null),
                DictionaryFunctions.CODE);
        assertEquals(bits(0, 2), valid, "a code of the level, and the missing cell, are valid");
    }


    @Test
    void hasDecodeIsTrueOnlyForACodeWithADecode() throws Exception
    {
        IDataTable cm = RealTables.of("CM")
                .str("CMTRT", "ASPIRIN TABLETS", "HERBAL REMEDY XYZ", "", null).build();
        BitSet decoded = DictionaryFunctions.hasDecode(run(cm, dictionaries()),
                Arrays.asList(column(cm, "CMTRT"), ConstVector.of("whodrug"), null));
        assertEquals(bits(0), decoded, "a blank or missing code holds no decode (no fire)");
        IDataTable ts = RealTables.of("TS").str("TSVALCD", "R16CO5Y76E", "ZZZNOTACODE").build();
        assertEquals(bits(0), DictionaryFunctions.hasDecode(run(ts, dictionaries()),
                Arrays.asList(column(ts, "TSVALCD"), ConstVector.of("unii"), null)));
    }


    @Test
    void reachedPastTheirGateTheNewDictionaryFunctionsThrow() throws Exception
    {
        // D-W1-3 (vii), for wave 3's three: no provider, and a provider without the rule's type.
        IDataTable ae = RealTables.of("AE").str("AEDECOD", "Headache").build();
        RuntimeDictionaryProvider uniiOnly = new RuntimeDictionaryProvider(
                Map.of("unii", ValueMapDictionary.load(Paths.get("dictionaries/unii.json"))));
        for (RuntimeDictionaryProvider provider : Arrays.asList(null, uniiOnly))
        {
            EvalRun run = run(ae, provider);
            List<Vector> membership = Arrays.asList(column(ae, "AEDECOD"), ConstVector.of(MEDDRA),
                    ConstVector.of("PT"), null);
            for (String name : List.of(DictionaryFunctions.VALUE, DictionaryFunctions.CODE))
            {
                UngatedProviderReachException ex = assertThrows(UngatedProviderReachException.class,
                        () -> DictionaryFunctions.termMembership(run, membership, name));
                assertTrue(ex.getMessage().startsWith(name + " reached ")
                        && ex.getMessage().contains(MEDDRA), ex.getMessage());
            }
            UngatedProviderReachException decode = assertThrows(UngatedProviderReachException.class,
                    () -> DictionaryFunctions.hasDecode(run,
                            Arrays.asList(column(ae, "AEDECOD"), ConstVector.of(MEDDRA), null)));
            assertTrue(decode.getMessage().startsWith(DictionaryFunctions.HAS_DECODE + " reached "),
                    decode.getMessage());
        }
    }

    // ============================================================ interval precision


    @Test
    void intervalPrecisionFiresOnlyOnDifferingPrecision()
    {
        IDataTable mh = RealTables.of("MH").str("MHSTDTC", "2020-01-01/2020-01",
                "2020-01-01/2020-06-15", "2020", "", "2020/", null).build();
        assertEquals(bits(0), interval(mh, null),
                "day vs month fires; day vs day, no delimiter, blank, a blank half and a missing"
                        + " value do not");
    }


    @Test
    void intervalPrecisionIgnoresOffsetsAndFractionsButNotRealMismatches()
    {
        // Fix #213: a timezone or a fractional-second tail on one half only is representation,
        // not precision — and stripping it must not mask a real mismatch either.
        IDataTable mh = RealTables.of("MH").str("MHSTDTC",
                "2003-12-15T10:00+02:00/2003-12-15T10:30",
                "2003-12-15T10:00/2003-12-15T10:30+02:00",
                "2003-12-15T10:00+02:00/2003-12-15T10:30+02:00",
                "2003-12-15T10:00Z/2003-12-15T10:30", "2003-12-15T10:00:00.000/2003-12-15T10:00:00",
                "2003-12-15T10:00:00.000Z/2003-12-15T10:00:00", "2020-01-01+02:00/2020-01-01T10:30",
                "2003-12-15T10:00/2003-12-15", "2003-12-15T10:00Z/2003-12-15Z",
                "2003-12/2003-12-15", "2020-01-01T10/2020-01-01").build();
        assertEquals(bits(6, 7, 8, 9, 10), interval(mh, ConstVector.of("/")));
    }


    @Test
    void intervalPrecisionReadsTheDelimiterPerRowWithTheSolidusAsDefault()
    {
        IDataTable mh = RealTables.of("MH")
                .str("MHSTDTC", "2020-01-01|2020-01", "2020-01-01/2020-01").str("DELIM", "|", null)
                .build();
        assertEquals(bits(0, 1), interval(mh, column(mh, "DELIM")),
                "row 0 splits on its own '|', row 1's missing delimiter is the default '/'");
        assertEquals(bits(1), interval(mh, ConstVector.of("")), "an empty delimiter is '/'");
    }

    // ============================================================ referenced_domain_class


    @Test
    void referencedDomainClassIsTheUpperCasedLibraryClassAndHandsAMissingThrough()
    {
        IDataTable supp = RealTables.of("SUPPAE").str("RDOMAIN", "AE", "FA", "ZZ", "AE", null)
                .build();
        Library library = new Library(Map.of("AE", "Events", "FA", "Findings About"));
        Vector classes = ReferencedDomainClass.evaluate(runWithLibrary(supp, library),
                List.of(column(supp, "RDOMAIN")));
        assertAll(() -> assertEquals("EVENTS", text(classes, 0)),
                () -> assertEquals("FINDINGS ABOUT", text(classes, 1)),
                () -> assertEquals("", text(classes, 2), "an unclassifiable domain is \"\""),
                () -> assertEquals("EVENTS", text(classes, 3)),
                () -> assertEquals(MissingValue.MIS, classes.value(4).missing(),
                        "a missing cell is handed through, never a class"),
                () -> assertEquals(List.of("AE", "FA", "ZZ"), library.asked,
                        "one Library question per distinct domain, not per row"));
    }


    @Test
    void referencedDomainClassRefusesToAnswerWithoutAUsableLibrary()
    {
        IDataTable supp = RealTables.of("SUPPAE").str("RDOMAIN", "AE").build();
        List<Vector> args = List.of(column(supp, "RDOMAIN"));
        UnusableProviderAnswerException none = assertThrows(UnusableProviderAnswerException.class,
                () -> ReferencedDomainClass.evaluate(runWithLibrary(supp, null), args));
        assertEquals(ProviderNeed.Kind.LIBRARY, none.kind());
        Library degraded = new Library(Map.of("AE", "Events"));
        degraded.unavailable = true;
        UnusableProviderAnswerException notConsulted = assertThrows(
                UnusableProviderAnswerException.class,
                () -> ReferencedDomainClass.evaluate(runWithLibrary(supp, degraded), args));
        assertTrue(notConsulted.getMessage().contains("could not be consulted"),
                notConsulted.getMessage());
        assertTrue(degraded.asked.isEmpty(), "Fix #369 condition 1: the Library is not asked");
    }


    /**
     * Fix #369 condition 3 (combined review W3/W4b M3), both arms, on a real degraded Library whose
     * study metadata is Define-XML backed (it publishes {@code DefineVersion}) under the opt-in
     * fallback: condition 1 lets it answer, and the define's classes are the answer — unless it
     * classifies NO referenced domain, which is an unusable answer (the rule SKIPs), never a column
     * of blanks every row would compare against.
     */
    @Test
    void referencedDomainClassUnderTheDefineFallbackAnswersOrRefusesWhenItClassifiesNothing()
    {
        IMetadataLibrary classed = net.cumba.datatable.testkit.TestMetadataFixtures.lib("study")
                .meta(IMetadataLibrary.META_KEY_DEFINE_VERSION, "2.1.0")
                .table(net.cumba.datatable.testkit.TestMetadataFixtures.table("LB")
                        .className("Findings")
                        .column(net.cumba.datatable.testkit.TestMetadataFixtures
                                .column("STUDYID", 0, DataValueType.STRING).build())
                        .build())
                .build();
        MetadataProvider degraded = MetadataLibraryProvider.degraded(classed,
                new IOException("HTTP 401"));
        IDataTable classifiable = RealTables.of("SUPPLB").str("RDOMAIN", "LB", "ZZ").build();
        IDataTable unclassifiable = RealTables.of("SUPPZZ").str("RDOMAIN", "ZZ", "ZZ").build();
        String property = LibraryAnswerability.DEGRADED_DEFINE_FALLBACK_PROPERTY;
        try
        {
            System.setProperty(property, "true");
            Vector classes = ReferencedDomainClass.evaluate(runWithLibrary(classifiable, degraded),
                    List.of(column(classifiable, "RDOMAIN")));
            assertAll(
                    () -> assertEquals("FINDINGS", text(classes, 0),
                            "condition 3 is met: the define classified a referenced domain"),
                    () -> assertEquals("", text(classes, 1),
                            "a domain the define does not carry is \"\" beside a classified one"));
            UnusableProviderAnswerException nothing = assertThrows(
                    UnusableProviderAnswerException.class,
                    () -> ReferencedDomainClass.evaluate(runWithLibrary(unclassifiable, degraded),
                            List.of(column(unclassifiable, "RDOMAIN"))));
            assertEquals(ProviderNeed.Kind.LIBRARY, nothing.kind());
            assertTrue(nothing.getMessage().contains("could not classify any referenced domain"),
                    nothing.getMessage());
        }
        finally
        {
            System.clearProperty(property);
        }
        // Control: the same provider without the opt-in never reaches condition 3 — condition 1
        // refuses it first, so the arm above is reached only through the fallback.
        UnusableProviderAnswerException optOut = assertThrows(UnusableProviderAnswerException.class,
                () -> ReferencedDomainClass.evaluate(runWithLibrary(classifiable, degraded),
                        List.of(column(classifiable, "RDOMAIN"))));
        assertTrue(optOut.getMessage().contains("could not be consulted"), optOut.getMessage());
    }

    // ============================================================ row_max


    @Test
    void rowMaxIsNumericOverNumericCellsAndKeepsTheWinningCell()
    {
        IDataTable adsl = RealTables.of("ADSL").dbl("TR01EDT", 22000.0, 21000.0, 5.0)
                .dbl("TR02EDT", 22010.0, 20000.0, 4.9999999999994).dbl("TRTEDT", 1.0, 1.0, 1.0)
                .build();
        Vector max = rowMax(adsl, "^TR(0[1-9]|[1-9][0-9])EDT$");
        assertAll(() -> assertEquals(22010.0, max.value(0).cell().getValue()),
                () -> assertEquals(21000.0, max.value(1).cell().getValue()),
                () -> assertEquals(5.0, max.value(2).cell().getValue(), "exact, no noise fold"),
                () -> assertTrue(max.value(0).cell().getValue() instanceof Double,
                        "the winning cell's own numeric value, not a rendering of it"),
                () -> assertEquals(DataValueType.DOUBLE, max.declaredType(),
                        "the matched columns' one type"));
    }


    @Test
    void rowMaxRanksACharValueAsTextNeverAsANumber()
    {
        // ⚑ D-W3-5, the one named movement (D-W1-2a: "a string is a string"): the retired reducer
        // parsed CHAR "9" / "12" as numbers and answered "12"
        // (OperationExecutorRowExtremeTest.numericMode_max_picksNumericExtremeReturnsOriginalString,
        // green on the pre-port engine); as text "9" > "12". Population 0 in the corpus: both
        // row_max rules read ADaM TRxxEDT, which is numeric.
        IDataTable tr = RealTables.of("TR").str("TR01N", "9", "3").str("TR02N", "12", "20").build();
        Vector max = rowMax(tr, "^TR\\d+N$");
        assertEquals("9", text(max, 0));
        assertEquals("3", text(max, 1));
    }


    /**
     * Owner K3, 2026-09-30: <i>"no, special handling for special texts. Text is text."</i> — a
     * date-looking CHAR value ranks as text like any other; the EC-46 date rule (whose
     * {@code max{2012-06, 2012-06-15}} was <em>indeterminate</em>, a computed missing) belongs to
     * {@code max_date} / {@code min_date} only. ⚑ MOVED ANSWER (the former
     * {@code rowMaxOverIsoDateTextKeepsTheEc46DateRule}): row 2 answers {@code 2012-06-15}.
     */
    @Test
    void rowMaxOverIsoDateTextRanksAsText()
    {
        IDataTable tr = RealTables.of("TR")
                .str("TR01EDT", "2020-01-10", "2019-12-31", "2012-06", "2020-01-10", "2024-01")
                .str("TR02EDT", "2020-03-01", "2020-01-01", "2012-06-15", "", "2024-01-15").build();
        Vector max = rowMax(tr, "^TR\\d+EDT$");
        assertAll(() -> assertEquals("2020-03-01", text(max, 0)),
                () -> assertEquals("2020-01-01", text(max, 1)),
                () -> assertEquals("2012-06-15", text(max, 2),
                        "K3: text is text — no date rule, so the longer text wins"),
                () -> assertEquals("2020-01-10", text(max, 3), "a blank cell is no candidate"),
                () -> assertEquals("2024-01-15", text(max, 4), "K3: the owner's pinned pair"));
    }


    /**
     * Combined review W3/W4b L2: the branch is decided once from the matched set's DECLARED types,
     * and a set matching a Num AND a Char column is a column-type mismatch — the rule ERRORs
     * (column-type doctrine). Pre-fix the branch was chosen per row: this row ranked in the string
     * branch and answered the DOUBLE cell 9.0 under a vector declared STRING.
     */
    @Test
    void rowMaxOverAMixedNumAndCharColumnSetIsAColumnTypeMismatch()
    {
        IDataTable tr = RealTables.of("TR").dbl("TR01X", 9.0).str("TR02X", "10").build();
        ColumnTypeMismatchException mismatch = assertThrows(ColumnTypeMismatchException.class,
                () -> rowMax(tr, "^TR\\d+X$"));
        assertTrue(mismatch.getMessage().contains("[TR01X]")
                && mismatch.getMessage().contains("[TR02X]"), mismatch.getMessage());
        // A Char column carrying ONE real candidate beside a populated Num column still mixes —
        // the two have no common order on that row (combined review round 2, H2's control).
        IDataTable oneDate = RealTables.of("ADSL").dbl("TR01EDT", 22000.0, 21000.0)
                .str("TR02EDT", "", "2020-01-01").build();
        assertThrows(ColumnTypeMismatchException.class,
                () -> rowMax(oneDate, "^TR(0[1-9]|[1-9][0-9])EDT$"));
    }


    /**
     * Combined review of runbook W2–W8, round 2 H2: a CSV / XLSX provider types a column STRING
     * unless a sampled cell is numeric evidence, so an ADSL whose TR02EDT is blank on every row
     * arrives as a Char column beside a numeric TR01EDT. A matched column with no candidate in any
     * row takes no part in the type decision — CDISC-/PMDA-AD0084 answer TR01EDT, as the retired
     * operation did, instead of ERRORing. The mirror: an all-missing Num column beside a Char set.
     */
    @Test
    void aMatchedColumnWithoutAnyCandidateTakesNoPartInTheTypeDecision()
    {
        IDataTable csv = RealTables.of("ADSL").dbl("TR01EDT", 22000.0, 21000.0)
                .str("TR02EDT", "", "").build();
        Vector max = rowMax(csv, "^TR(0[1-9]|[1-9][0-9])EDT$");
        assertAll(() -> assertEquals(DataValueType.DOUBLE, max.declaredType()),
                () -> assertEquals(22000.0, max.value(0).cell().getValue()),
                () -> assertEquals(21000.0, max.value(1).cell().getValue()));
        // A DOUBLE buffer need not accept a null cell, so the all-missing Num column carries its
        // missing identity explicitly: MIS_UNKNOWN, what a null numeric cell reads as.
        double unknown = MissingValue.MIS_UNKNOWN.asDouble();
        IDataTable numBlank = RealTables.of("TR").dbl("TR01X", unknown, unknown)
                .str("TR02X", "2020-01-01", "2020-03-01").build();
        Vector charMax = rowMax(numBlank, "^TR\\d+X$");
        assertAll(() -> assertEquals(DataValueType.STRING, charMax.declaredType()),
                () -> assertEquals("2020-01-01", text(charMax, 0)),
                () -> assertEquals("2020-03-01", text(charMax, 1)));
    }


    /**
     * The decided-once branch over one declared type: a Num set is the numeric maximum on every
     * row, a Char set the string branch on every row — whatever a row's cells look like.
     */
    @Test
    void rowMaxDecidesItsBranchOnceFromTheDeclaredColumnTypes()
    {
        IDataTable num = RealTables.of("TR").dbl("TR01X", 9.0, 30.0).dbl("TR02X", 10.0, 4.0)
                .build();
        Vector numMax = rowMax(num, "^TR\\d+X$");
        assertAll(() -> assertEquals(DataValueType.DOUBLE, numMax.declaredType()),
                () -> assertEquals(10.0, numMax.value(0).cell().getValue(), "10 > 9 numerically"),
                () -> assertEquals(30.0, numMax.value(1).cell().getValue()));
        IDataTable chr = RealTables.of("TR").str("TR01X", "9", "30").str("TR02X", "10", "4")
                .build();
        Vector charMax = rowMax(chr, "^TR\\d+X$");
        assertAll(() -> assertEquals(DataValueType.STRING, charMax.declaredType()),
                () -> assertEquals("9", text(charMax, 0), "\"9\" > \"10\" as text"),
                () -> assertEquals("4", text(charMax, 1), "\"4\" > \"30\" as text"));
    }


    @Test
    void rowMaxTreatsPlusAndMinusZeroAsOneValueAndTheFirstWins()
    {
        IDataTable tr = RealTables.of("TR").dbl("TR01X", -0.0).dbl("TR02X", 0.0).buildRaw();
        Vector max = rowMax(tr, "^TR\\d+X$");
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits((Double) max.value(0).cell().getValue()),
                "D84: ±0 tie, so the first candidate in column order wins");
    }


    @Test
    void rowMaxWithoutACandidateAnswersAMissingNeverNull()
    {
        double a = MissingValue.MIS_A.asDouble();
        double b = MissingValue.MIS_B.asDouble();
        // ⚑ The Char column TR03X this fixture used to carry is gone: a Num + Char matched set is
        // a column-type mismatch since combined review W3/W4b L2 (see the mixed-set test). The
        // missing-text arm is pinned on a Char-only set below.
        double unknown = MissingValue.MIS_UNKNOWN.asDouble();
        IDataTable tr = RealTables.of("TR").dbl("TR01X", a, a, 1.0).dbl("TR02X", b, b, unknown)
                .build();
        Vector max = rowMax(tr, "^TR\\d+X$");
        IDataTable missingText = RealTables.of("TR").str("TR01X", (String) null)
                .str("TR02X", (String) null).build();
        assertEquals(MissingValue.MIS, rowMax(missingText, "^TR\\d+X$").value(0).missing(),
                "missing texts only ⇒ their MIS identity");
        assertAll(
                () -> assertEquals(MissingValue.MIS, max.value(0).missing(),
                        ".A and .B combine to MIS (distinct identities)"),
                () -> assertEquals(MissingValue.MIS, max.value(1).missing()),
                () -> assertEquals(1.0, max.value(2).cell().getValue(),
                        "the only populated cell wins"));
        IDataTable same = RealTables.of("TR").dbl("TR01X", a).dbl("TR02X", a).build();
        TypedValue carried = rowMax(same, "^TR\\d+X$").value(0);
        assertEquals(MissingValue.MIS_A, carried.missing(),
                "one identity across every matched cell is carried, not recomputed");
        IDataTable blanks = RealTables.of("TR").str("TR01X", "", " ").str("TR02X", "  ", "")
                .build();
        Vector blank = rowMax(blanks, "^TR\\d+X$");
        assertEquals(MissingValue.MIS, blank.value(0).missing(),
                "present blanks only ⇒ the computed missing");
        IDataTable none = RealTables.of("DM").str("AGE", "40").build();
        assertEquals(MissingValue.MIS, rowMax(none, "^TR\\d+X$").value(0).missing(),
                "no matching column ⇒ the computed missing on every row");
    }


    @Test
    void rowMaxMatchesColumnNamesInAnyLetterCase()
    {
        // Owner ruling 2026-09-28 (CIT §1): name_pattern selects columns case-insensitively.
        IDataTable tr = RealTables.of("TR").dbl("tr01n", 9.0).dbl("Tr02N", 12.0).dbl("OTHER", 99.0)
                .build();
        assertEquals(12.0, rowMax(tr, "^TR\\d+N$").value(0).cell().getValue());
    }

    // ============================================================ load-time negative controls


    @Test
    void theWellFormedCorpusSpellingsLoadAndDeclareTheirProviders() throws Exception
    {
        Map<String, String> spellings = new HashMap<>();
        spellings.put(
                "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\\"meddra\\\","
                        + " dictionary_term_type=\\\"PT\\\", case_sensitive=false)",
                "dictionary:meddra");
        spellings.put(
                "valid_external_dictionary_code(AELLTCD, external_dictionary_type=\\\"meddra\\\","
                        + " dictionary_term_type=\\\"LLTCD\\\")",
                "dictionary:meddra");
        spellings.put("dictionary_has_decode(CMTRT, external_dictionary_type=\\\"whodrug\\\")",
                "dictionary:whodrug");
        spellings.put("interval_uncertainty_precision_mismatch(LBDTC, delimiter=\\\"/\\\")",
                "none");
        spellings.put("referenced_domain_class(RDOMAIN)", "library");
        spellings.put("row_max(name_pattern=\\\"^TR(0[1-9]|[1-9][0-9])EDT$\\\")", "none");
        for (Map.Entry<String, String> e : spellings.entrySet())
        {
            Rule rule = load(e.getKey());
            assertNull(rule.getLoadError(), e.getKey() + ": " + rule.getLoadError());
            ProviderNeeds needs = ProviderNeeds.ofBindings(rule);
            String actual = needs.dictionary() ? "dictionary:" + needs.dictionaryTypes().getFirst()
                    : needs.library() ? "library" : "none";
            assertEquals(e.getValue(), actual, e.getKey());
            assertEquals(List.of("$v"),
                    rule.bindingOrder().stream().map(CompiledBinding::name).toList(),
                    "the binding is a compiled function call: " + e.getKey());
        }
    }


    @Test
    void theRetiredAndIllFormedSpellingsFailToLoad() throws Exception
    {
        Map<String, String> expected = new HashMap<>();
        // R1: a quoted name is a string literal, never a column.
        expected.put("valid_external_dictionary_value(\\\"AEDECOD\\\","
                + " external_dictionary_type=\\\"meddra\\\", dictionary_term_type=\\\"PT\\\")",
                "column reference");
        expected.put("referenced_domain_class(\\\"RDOMAIN\\\")", "column reference");
        expected.put("interval_uncertainty_precision_mismatch(\\\"LBDTC\\\")", "column reference");
        // D-W3-4: the per-call parameters take a static string literal; the regex must compile.
        expected.put(
                "valid_external_dictionary_code(AEPTCD, external_dictionary_type=\\\"meddra\\\","
                        + " dictionary_term_type=AEDECOD)",
                "static string literal");
        expected.put("row_max(name_pattern=TRTEDT)", "static string literal");
        expected.put("row_max(name_pattern=\\\"[unclosed\\\")", "not a valid regular expression");
        // W1's boolean-literal seam reaches the new dictionary functions too.
        expected.put("dictionary_has_decode(CMTRT, external_dictionary_type=\\\"whodrug\\\","
                + " case_sensitive=\\\"no\\\")", "boolean literal");
        // Arity: the level and the column are required; row_max takes no target (R6).
        expected.put(
                "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\\\"meddra\\\")",
                "dictionary_term_type");
        expected.put("referenced_domain_class()", "name");
        expected.put("row_max(TRTEDT, name_pattern=\\\"^TR\\\")", "row_max");
        // D-W1-3 (v): the dictionary type is a static string literal.
        expected.put("valid_external_dictionary_value(AEDECOD, external_dictionary_type=AETERM,"
                + " dictionary_term_type=\\\"PT\\\")", "not a static string literal");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet())
        {
            String error = load(e.getKey()).getLoadError();
            if (error == null || !error.contains(e.getValue()))
            {
                wrong.add(e.getKey() + " ⇒ " + error);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    // ============================================================ helpers


    private static RuntimeDictionaryProvider dictionaries() throws IOException
    {
        return RuntimeDictionaryProvider.loadDirectory(Paths.get("dictionaries"));
    }


    private static EvalRun run(IDataTable table, @Nullable RuntimeDictionaryProvider provider)
    {
        return EvalRun.fullRange(
                EvaluationContext.builder().table(table).dictionaryProvider(provider).build());
    }


    private static EvalRun runWithLibrary(IDataTable table, @Nullable MetadataProvider library)
    {
        return EvalRun.fullRange(
                EvaluationContext.builder().table(table).libraryProvider(library).build());
    }


    private static Vector column(IDataTable table, String name)
    {
        int idx = table.getMetaData().getColumnIndex(name);
        return new ColumnVector(name, table.getColumn(idx),
                table.getMetaData().getColumn(idx).getType());
    }


    private static BitSet interval(IDataTable table, @Nullable Vector delimiter)
    {
        return IntervalPrecision.evaluate(
                EvalRun.fullRange(EvaluationContext.builder().table(table).build()), Arrays.asList(
                        column(table, table.getMetaData().getColumn(0).getName()), delimiter));
    }


    private static Vector rowMax(IDataTable table, String pattern)
    {
        return RowMax.evaluate(EvalRun.fullRange(EvaluationContext.builder().table(table).build()),
                List.of(ConstVector.of(pattern)));
    }


    private static String text(Vector v, int row)
    {
        IDataValue cell = v.value(row).cell();
        assertNull(v.value(row).missing(), "row " + row + " holds a value");
        return cell.getValueAsString();
    }


    private static BitSet bits(int... rows)
    {
        BitSet b = new BitSet();
        for (int r : rows)
        {
            b.set(r);
        }
        return b;
    }


    private static Rule load(String binding) throws IOException
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W3-FN\"},"
                + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"" + binding + "\"}],"
                + "\"Check\":{\"expression\":\"$v == false\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        return rule;
    }

    /** A Library that classifies a fixed set of domains and records what it was asked. */
    private static final class Library implements MetadataProvider
    {

        private final Map<String, String> classes;

        private final List<String> asked = new ArrayList<>();

        private boolean unavailable;

        Library(Map<String, String> aClasses)
        {
            classes = aClasses;
        }


        @Override
        public @Nullable String getDatasetClass(String aDomain)
        {
            asked.add(aDomain);
            return classes.get(aDomain);
        }


        @Override
        public boolean isLibraryUnavailable()
        {
            return unavailable;
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public java.util.Optional<Boolean> isCodelistExtensible(String codelistName)
        {
            return java.util.Optional.empty();
        }


        @Override
        public String getStandard()
        {
            return "sdtmig";
        }

    }

}
