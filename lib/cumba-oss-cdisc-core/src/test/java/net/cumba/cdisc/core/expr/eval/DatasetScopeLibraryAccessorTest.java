package net.cumba.cdisc.core.expr.eval;

import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.lib;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #370</b> — {@code ds_*("LIBRARY")} asked the CDISC Library by the <b>member name</b>,
 * which the Library cannot know.
 *
 * <h2>The defect</h2>
 *
 * <p>
 * {@code ExprCompiler.readProviderLevel} resolved every DATASET-scope accessor ({@code ds_name},
 * {@code ds_label}, {@code ds_class}, {@code ds_structure}) through
 * {@code provider.getDatasetMetadata(ctx.getDomainName())}, and {@code ctx.getDomainName()} is the
 * dataset <em>member</em> name ({@code RuleRunner}: {@code evalTable.getMetaData().getName()}). The
 * Library publishes <b>domains</b>, never a sponsor's split-member file names, so every
 * {@code ds_*("LIBRARY")} read on a split member returned {@code null} — silently, <b>on a
 * perfectly healthy Library with a valid subscription key</b>. Unrelated to {@code Fix #369}: a
 * degraded library was never the cause and the degraded-skip does not help.
 * </p>
 *
 * <h2>The fix — three tiers, at the LIBRARY level only</h2>
 *
 * <ol>
 * <li>the dataset / member name (unchanged);</li>
 * <li>{@code CdiscDomainResolver.cdiscDomainOf} — <em>exactly</em> {@code domain_label()}'s
 * resolution;</li>
 * <li>{@code SUPP--}/{@code SQ--} mapped to {@code SUPPQUAL} with the label template substituted
 * from {@code RDOMAIN}.</li>
 * </ol>
 *
 * <h2>⚠⚠ Why every fixture here is a real {@link MetadataLibraryProvider}</h2>
 *
 * <p>
 * {@link MetadataProvider#getDatasetMetadata} is contracted to return an <b>empty map</b> for an
 * unknown dataset — <em>not</em> {@code null} — and both shipped implementations honour it. A
 * Mockito mock returns {@code null} for an un-stubbed call, so a mock-based test would pass against
 * a {@code meta == null} fallback guard that is <b>inert in production</b>. That is precisely the
 * {@code Fix #369} no-op shape, and {@link #emptyMapIsUnknown_notNull} pins it deliberately.
 * </p>
 */
class DatasetScopeLibraryAccessorTest
{

    /**
     * ⚠ {@code WARNED_TEMPLATE_LABELS} is JVM-global and de-duplicates by label, so without this a
     * second execution of {@link #rewordedPlaceholder_warnsOnce} in the same JVM (a surefire rerun,
     * a {@code @RepeatedTest} refactor, the class listed twice in a suite) sees zero warnings and
     * fails with a baffling {@code expected 1 but was 0}.
     */
    @BeforeEach
    void clearWarningLedger()
    {
        ExprCompiler.resetPlaceholderWarnings();
    }

    private static final String SUPP_TEMPLATE_LOWER = "Supplemental Qualifiers for [domain name]";

    /** ⚑ The second spelling, measured in 3 of 7 products including {@code sdtmig 3-1-2}. */
    private static final String SUPP_TEMPLATE_UPPER = "Supplemental Qualifiers [DOMAIN NAME]";

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A CDISC Library publishing domains only — never a member name. {@code SUPPQUAL} is the single
     * supplemental dataset the Library actually publishes (SDTMIG 3-4: 63 datasets, one of them
     * supplemental); there is deliberately no {@code SUPPAE}, {@code SUPPLB}, {@code SQAPAE}, and
     * no {@code AP*} at all.
     */
    private static IMetadataLibrary standardLibrary(String suppTemplate)
    {
        return lib("sdtmig")
                .table(table("AE").label("Adverse Events").className("Events")
                        .structure("One record per adverse event per subject").build())
                .table(table("CM").label("Concomitant/Prior Medications").className("Interventions")
                        .build())
                .table(table("QS").label("Questionnaires").className("Findings").build())
                .table(table("LB").label("Laboratory Test Results").className("Findings").build())
                .table(table("FA").label("Findings About Events or Interventions")
                        .className("Findings").build())
                .table(table("SUPPQUAL").label(suppTemplate).className("Relationship").build())
                .build();
    }


    private static MetadataProvider library()
    {
        return new MetadataLibraryProvider(standardLibrary(SUPP_TEMPLATE_LOWER));
    }


    /**
     * Evaluates a check expression against {@code table} exactly as a shipped rule does, with the
     * context wired the way {@code RuleRunner} wires it — {@code domainName} is the <b>member
     * name</b>, which is the whole defect.
     */
    private static BitSet eval(String expr, IDataTable table, MetadataProvider libraryProvider)
    {
        EvaluationContext ctx = ctx(table, null, libraryProvider);
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static EvaluationContext ctx(IDataTable table, MetadataProvider defineProvider,
            MetadataProvider libraryProvider)
    {
        return EvaluationContext.builder().table(table).domainName(table.getMetaData().getName())
                .variables(Map.of()).defineProvider(defineProvider).libraryProvider(libraryProvider)
                .build();
    }


    /** True when the expression held on row 0 — the accessor broadcasts, so one row is enough. */
    private static boolean holds(String expr, IDataTable table, MetadataProvider libraryProvider)
    {
        return eval(expr, table, libraryProvider).get(0);
    }

    // ------------------------------------------------------------------
    // §6b — the eight rows, each asserted on the value a rule actually reads
    // ------------------------------------------------------------------


    @Test
    @DisplayName("row 1 — a non-split standard dataset resolves by NAME (tier 1), as before")
    void nonSplitStandardDataset_tier1()
    {
        IDataTable ae = MockTable.of().name("AE").col("DOMAIN", "AE").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Adverse Events\"", ae, library()));
        // ⚑ ds_class / ds_structure ride the same getDatasetMetadata call and are fixed for free.
        assertTrue(holds("ds_class(\"LIBRARY\") == \"Events\"", ae, library()));
        assertTrue(holds("ds_name(\"LIBRARY\") == \"AE\"", ae, library()));
        assertTrue(
                holds("ds_structure(\"LIBRARY\") == \"One record per adverse event per subject\"",
                        ae, library()));
    }


    @Test
    @DisplayName("row 2 ⭐ THE FIX — a split member (qsco, DOMAIN=QS) resolves via tier 2")
    void splitMember_resolvesViaCdiscDomain()
    {
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Questionnaires\"", qsco, library()),
                "the Library has no dataset called 'qsco'; it publishes the DOMAIN, QS");
        assertTrue(holds("ds_class(\"LIBRARY\") == \"Findings\"", qsco, library()));
    }


    @Test
    @DisplayName("row 3 — a digit split (LB1, DOMAIN=LB) resolves via tier 2")
    void digitSplitMember_resolvesViaCdiscDomain()
    {
        IDataTable lb1 = MockTable.of().name("LB1").col("DOMAIN", "LB").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Laboratory Test Results\"", lb1, library()));
    }


    @Test
    @DisplayName("row 4 — no DOMAIN cell: tier 2 falls through cdiscDomainOf to unsplitName")
    void noDomainCell_resolvesViaUnsplitName()
    {
        // cdiscDomainOf's own second leg. A rows-less LB1 takes the identical branch (the DOMAIN
        // read is gated on rowCount > 0), and is asserted below through the operand twin, which
        // does not need a row to produce a value.
        IDataTable lb1 = MockTable.of().name("LB1").col("LBTESTCD", "ALB").build();
        assertTrue(holds("ds_label(\"LIBRARY\") == \"Laboratory Test Results\"", lb1, library()));

        IDataTable empty = MockTable.of().name("LB1").col("LBTESTCD").build();
        assertEquals("Laboratory Test Results",
                ExprCompiler.datasetScopeOperandValue(ctx(empty, null, library()),
                        "library_dataset_label"),
                "a 0-row split member must resolve too — cdiscDomainOf falls to unsplitName");
    }


    @Test
    @DisplayName("row 5 — a genuinely custom domain still resolves to null, unchanged")
    void customDomain_staysNull()
    {
        IDataTable zz = MockTable.of().name("ZZ").col("DOMAIN", "ZZ").build();

        assertFalse(holds("non_empty(ds_label(\"LIBRARY\"))", zz, library()),
                "ZZ is in no tier: not a Library dataset, not a SUPP/SQ prefix");
        assertNull(ExprCompiler.datasetScopeOperandValue(ctx(zz, null, library()),
                "library_dataset_label"));
    }


    @Test
    @DisplayName("row 6 ⭐ SUPPAE (RDOMAIN=AE) → 'Supplemental Qualifiers for AE' via tier 3")
    void suppDataset_substitutesTemplateFromRdomain()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").col("QNAM", "AESOC")
                .build();

        assertTrue(
                holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for AE\"", suppae,
                        library()),
                "tiers 1 and 2 both miss every supplemental — the Library publishes "
                        + "only the SUPPQUAL template");
        assertTrue(holds("ds_class(\"LIBRARY\") == \"Relationship\"", suppae, library()),
                "the non-templated keys come through untouched");
    }


    @Test
    @DisplayName("row 7 — SUPPLBHM (RDOMAIN=LB), a SUPP letter split, also reaches tier 3")
    void suppLetterSplit_reachesTierThree()
    {
        // ⚑ unsplitName("SUPPLBHM") is "SUPPLBH" (7-11 chars), which the Library does not publish
        // either — tier 3 keys off the SUPP prefix, so both SUPPAE and SUPPLBHM reach it.
        IDataTable supplbhm = MockTable.of().name("SUPPLBHM").col("RDOMAIN", "LB").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for LB\"", supplbhm,
                library()));
    }


    @Test
    @DisplayName("row 8 ⭐ Q1 — a file named AE whose DOMAIN says CM keeps AE's label (name first)")
    void nameAndDomainDisagree_nameWins()
    {
        // ✅ RULED 2026-08-27: name-first. The name leg succeeds only when the name IS a standard
        // dataset; a name/DOMAIN disagreement is its own conformance error with its own rule, and
        // this accessor must not silently re-declare on the sponsor's behalf.
        IDataTable ae = MockTable.of().name("AE").col("DOMAIN", "CM").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Adverse Events\"", ae, library()));
        assertFalse(
                holds("ds_label(\"LIBRARY\") == \"Concomitant/Prior Medications\"", ae, library()),
                "domain-first would have answered CM's label — that was rejected");
    }

    // ------------------------------------------------------------------
    // §6b′ — the two scope boundaries
    // ------------------------------------------------------------------


    @Test
    @DisplayName("boundary — FA-- needs no special case; FAAE is an ordinary split of FA")
    void faSplit_needsNoSpecialCase()
    {
        IDataTable faae = MockTable.of().name("FAAE").col("DOMAIN", "FA").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Findings About Events or Interventions\"",
                faae, library()), "FAAE carrying DOMAIN=FA is a split of FA — tier 2 covers it");
    }


    @Test
    @DisplayName("boundary ⛔ APAE stays null — a PERMANENT guard, owner ruling 4 of 2026-08-27")
    void apDataset_staysNullPermanently()
    {
        // ⛔ SDTMIG 3-4 publishes 63 datasets and no AP*. Associated Persons datasets live in a
        // SEPARATE product (standards/sdtmig/ap-1-0, publishing only APDM and APRELSUB) with no
        // label template to substitute — unlike SUPPQUAL.
        //
        // ⭐⭐ This was filed as a deferral and has since been RULED. `Fix #370` shipped it as
        // "deferred to R2, a later ruling has a failing test to flip"; the owner's
        // `plans/PLAN-metadata-product-selection.md` ruling 4 — **"No synthesis. The engine never
        // invents metadata CDISC did not publish. ds_label(\"APAE\") stays null PERMANENTLY"** —
        // settles it the other way. ⇒ this is a guard to KEEP, not a gap to close: synthesising
        // "Associated Persons " + the parent label would be the engine inventing a convention
        // CDISC never published, which is categorically unlike the SUPPQUAL template it DOES
        // publish and tier 3 substitutes.
        // ⚠ Three shapes, because they take three different tier-2 keys: APAE declares
        // DOMAIN=APAE (the convention LibraryValidator.classNameFor reads with substring(2)), so
        // the key is APAE; APFACM has no DOMAIN cell and unsplitName treats it as an AP letter
        // split, so the key is APFAC; APMH1 is a digit split, so the key is APMH.
        List<IDataTable> apShapes = List.of(
                MockTable.of().name("APAE").col("DOMAIN", "APAE").col("APID", "A-1").build(),
                MockTable.of().name("APFACM").col("APID", "A-1").build(),
                MockTable.of().name("APMH1").col("APID", "A-1").build());

        for (IDataTable ap : apShapes)
        {
            assertFalse(holds("non_empty(ds_label(\"LIBRARY\"))", ap, library()),
                    ap.getMetaData().getName());
            assertNull(ExprCompiler.datasetScopeOperandValue(ctx(ap, null, library()),
                    "library_dataset_label"), ap.getMetaData().getName());
        }
        // ⚠⚠ What this does and does NOT prove, stated precisely because the ruling now depends
        // on it: none of the three keys is in the fixture library, and none is in a real SDTMIG
        // product either (63 datasets, no AP*), so "AP stays null" holds because the AP product is
        // UNREACHABLE — NOT because any tier excludes AP. ⇒ if `--metadata-products` is ever
        // pointed at `sdtmig/ap-1-0`, tier 2 WILL start answering `APDM` / `APRELSUB` by name
        // (tier 1, even) and this test stays green while ruling 4's intent quietly changes.
        // Ruling 4 forbids SYNTHESIS, not publication — so a declared AP product answering APDM
        // from what CDISC actually published is consistent with it, and `APAE`, which CDISC does
        // not publish, must still be null. Whoever implements the product list should re-read this
        // and add the case for a DECLARED ap-1-0.
    }

    // ------------------------------------------------------------------
    // ⛔ The asymmetry that is the substance of the design
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⚠⚠ DEFINE must NOT get the fallback — the member name is the correct key there")
    void defineLevel_neverFallsBack()
    {
        // A Define-XML declares one ItemGroupDef per dataset FILE, split members included. A domain
        // fallback here would answer 'qsco' from QS's declaration, so FDA-SD1325 / PMDA-SD1325
        // (ds_label("DATA") != ds_label("DEFINE")) would compare qsco's real label against QS's
        // declaration and FIRE ON CONFORMING DATA. Without this test the next reader "unifies" the
        // two levels and that regression ships silently.
        MetadataProvider define = new MetadataLibraryProvider(
                lib("define").table(table("QS").label("Questionnaires").build()).build());
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").build();

        assertNull(
                ExprCompiler.datasetScopeOperandValue(ctx(qsco, define, null),
                        "define_dataset_label"),
                "the define knows member names; only the Library is domain-keyed");
        // ⚠ The control that makes the assertion above mean something: the SAME provider answers
        // when asked by the key it actually holds. Without it, a provider that answers nothing at
        // all would pass — and an earlier draft of this test wired defineProvider = null, so it
        // returned at the `provider == null` guard before the level was ever examined and passed
        // with the whole DATASET branch deleted.
        assertEquals("Questionnaires",
                ExprCompiler.datasetScopeOperandValue(
                        ctx(MockTable.of().name("QS").col("DOMAIN", "QS").build(), define, null),
                        "define_dataset_label"));
    }


    @Test
    @DisplayName("an EXPLICIT dataset argument is never second-guessed")
    void explicitDatasetName_getsNoFallback()
    {
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").build();

        // ds_label("LIBRARY", "qsco") names the dataset the author wants. Falling back would be
        // answering a different question from the one asked.
        assertFalse(holds("non_empty(ds_label(\"qsco\", \"LIBRARY\"))", qsco, library()));
        // The control: an explicit name the Library DOES publish still resolves.
        assertTrue(holds("ds_label(\"AE\", \"LIBRARY\") == \"Adverse Events\"", qsco, library()));
    }

    // ------------------------------------------------------------------
    // ⛔⛔ The contract that would have made this fix a silent no-op
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⛔⛔ 'unknown' is an EMPTY MAP, not null — a meta == null guard is inert")
    void emptyMapIsUnknown_notNull()
    {
        // MetadataProvider.getDatasetMetadata: "or empty map if unknown". MetadataLibraryProvider
        // returns Map.of() for a domain it has no data table for, and so does the rule-test
        // MapBackedLibraryMetadataProvider — so a `meta == null` fallback guard would be inert in
        // production and in the .cdt suites while a null-returning mock kept its unit tests green.
        MetadataProvider provider = library();
        assertEquals(Map.of(), provider.getDatasetMetadata("qsco"),
                "the shipped provider answers an unknown dataset with an EMPTY MAP");

        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").build();
        assertTrue(holds("ds_label(\"LIBRARY\") == \"Questionnaires\"", qsco, provider),
                "the fallback must trigger on the empty map, not only on null");
    }

    // ------------------------------------------------------------------
    // Tier 3's substitution — both measured spellings, and the drift detector
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⚑ BOTH placeholder spellings substitute — a literal replace no-ops on 3 of 7")
    void bothPlaceholderSpellings_substitute()
    {
        // Measured across the whole cache (34 keys, 507 labels): exactly two bracketed labels
        // exist — "…for [domain name]" (4 products) and "…[DOMAIN NAME]" (3, incl. sdtmig 3-1-2).
        // A case-sensitive replace("[domain name]", …) passes the first and silently no-ops the
        // second, which is why the match is a CASE_INSENSITIVE pattern.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();

        assertTrue(
                holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for AE\"", suppae,
                        new MetadataLibraryProvider(standardLibrary(SUPP_TEMPLATE_LOWER))),
                "lower-case spelling, 4 products");
        assertTrue(
                holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers AE\"", suppae,
                        new MetadataLibraryProvider(standardLibrary(SUPP_TEMPLATE_UPPER))),
                "UPPER-CASE spelling, 3 products — this is the assertion a literal replace fails");
    }


    @Test
    @DisplayName("⛔ no RDOMAIN ⇒ the label is DROPPED, never returned as a raw template")
    void suppWithoutRdomain_declinesTheLabel()
    {
        // ⛔⛔ Returning "Supplemental Qualifiers for [domain name]" as a LABEL is a false-positive
        // generator: the first rule comparing it against ds_label("DATA") fires on conforming data.
        // A 0-row SUPP file reaches this too — firstRowValue returns null when rowCount <= 0.
        IDataTable noRdomain = MockTable.of().name("SUPPAE").col("QNAM", "AESOC").build();
        IDataTable zeroRow = MockTable.of().name("SUPPAE").col("RDOMAIN").build();

        for (IDataTable t : List.of(noRdomain, zeroRow))
        {
            assertNull(
                    ExprCompiler.datasetScopeOperandValue(ctx(t, null, library()),
                            "library_dataset_label"),
                    "no parent domain ⇒ no label, rather than a template masquerading as one");
            assertEquals("Relationship",
                    ExprCompiler.datasetScopeOperandValue(ctx(t, null, library()),
                            "library_dataset_class"),
                    "the keys that carry no template still answer");
        }
    }


    @Test
    @DisplayName("⛔ a SUPP-- file carrying a DOMAIN cell must NOT answer as its parent domain")
    void suppWithDomainCell_neverResolvesToTheParentDomain()
    {
        // SDTMIG defines no DOMAIN on SUPPQUAL, but this engine's whole input population is
        // non-conforming data and a SAS export from a shared variable set is the ordinary way it
        // appears. With tier 2 ahead of tier 3, cdiscDomainOf reads DOMAIN=AE and ds_label
        // answers "Adverse Events" for SUPPAE — a WRONG-DATASET answer, strictly worse than the
        // null it replaces, caused by exactly the data error the engine exists to detect.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("DOMAIN", "AE").col("RDOMAIN", "AE")
                .build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for AE\"", suppae,
                library()));
        assertFalse(holds("ds_label(\"LIBRARY\") == \"Adverse Events\"", suppae, library()),
                "the SUPP/SQ prefix is knowable from the name alone, so it decides before tier 2");
    }


    @Test
    @DisplayName("⛔ a supplemental dataset is answered by SUPPQUAL or NOT AT ALL")
    void supplementalNeverFallsBackToTierTwo()
    {
        // Tier 2 must not be tried AFTER tier 3 declines either, or the same wrong-dataset answer
        // returns by the back door on any Library that publishes no SUPPQUAL.
        MetadataProvider noSupp = new MetadataLibraryProvider(
                lib("sdtmig").table(table("AE").label("Adverse Events").build()).build());
        IDataTable suppae = MockTable.of().name("SUPPAE").col("DOMAIN", "AE").build();

        assertNull(ExprCompiler.datasetScopeOperandValue(ctx(suppae, null, noSupp),
                "library_dataset_label"));
    }


    @Test
    @DisplayName("⚑ a two-character SQ name is not swept into SUPPQUAL by the short prefix")
    void bareSqName_isNotTreatedAsSupplemental()
    {
        IDataTable sq = MockTable.of().name("SQ").col("DOMAIN", "SQ").build();

        // ⚠ Assert on ds_CLASS, not ds_label. The label is templated, so it is dropped either way
        // and cannot tell the two paths apart — an earlier draft asserted it and was VACUOUS
        // (measured: removing the length bound left it green). className carries no template, so
        // it is "Relationship" iff SQ was wrongly swept into SUPPQUAL.
        assertNull(
                ExprCompiler.datasetScopeOperandValue(ctx(sq, null, library()),
                        "library_dataset_class"),
                "a bare two-character SQ is a custom domain, not a supplemental dataset");
        assertNull(ExprCompiler.datasetScopeOperandValue(ctx(sq, null, library()),
                "library_dataset_label"));
    }


    @Test
    @DisplayName("⚑ an RDOMAIN carrying regex metacharacters is substituted literally")
    void rdomainWithRegexMetacharacters_isQuoted()
    {
        // `parent` is a data-driven cell, and the engine's job is garbage data. Without
        // Matcher.quoteReplacement a value containing $ or \ throws
        // IllegalArgumentException: Illegal group reference out of the middle of a rule evaluation.
        IDataTable odd = MockTable.of().name("SUPPAE").col("RDOMAIN", "$1\\x").build();

        assertEquals("Supplemental Qualifiers for $1\\x", ExprCompiler
                .datasetScopeOperandValue(ctx(odd, null, library()), "library_dataset_label"));
    }


    @Test
    @DisplayName("⚠ a CDISC REWORD of the placeholder is WARNED, not silently inert")
    void rewordedPlaceholder_warnsOnce()
    {
        // A precise pattern buys precision and costs drift-tolerance: reworded, the substitution
        // silently stops applying and ds_label("LIBRARY") starts returning a raw template as if it
        // were a label. This warning is the ONLY signal that tier 3 has gone inert — the Fix #369
        // lesson (a lookup that returns something plausible when it has stopped working is worse
        // than one that fails) applied prophylactically.
        String reworded = "Supplemental Qualifiers for [parent domain of the record]";
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();

        List<String> warnings = capturingExprCompilerLog(() ->
        {
            // ⚑ TWICE. These are per-dataset reads, so an unguarded warning floods the log with a
            // line per dataset per rule; the de-duplication is the design, so it is pinned.
            for (int i = 0; i < 2; i++)
            {
                assertFalse(
                        holds("non_empty(ds_label(\"LIBRARY\"))", suppae,
                                new MetadataLibraryProvider(standardLibrary(reworded))),
                        "an unmatched template is DROPPED, never returned as if it were a label");
            }
        });

        assertEquals(1, warnings.size(),
                "warned once per distinct label, not per read: " + warnings);
        assertTrue(warnings.get(0).contains(reworded), warnings.get(0));
        assertTrue(warnings.get(0).contains("Fix #370"), warnings.get(0));
    }


    @Test
    @DisplayName("⚑ a MATCHED placeholder warns about nothing — the detector is not noise")
    void matchedPlaceholder_doesNotWarn()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();

        List<String> warnings = capturingExprCompilerLog(() -> holds(
                "ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for AE\"", suppae, library()));

        assertTrue(warnings.isEmpty(), "a matched template must be silent: " + warnings);
    }

    // ------------------------------------------------------------------
    // The shipped verdict — not the resolver
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⭐ end to end: a RULE reading ds_label(\"LIBRARY\") on a split member now fires")
    void shippedVerdict_onASplitMember() throws Exception
    {
        // ⚠ Assert on the shipped verdict, not on readProviderLevel's return: a test that only
        // checks the resolver proves the mapping, never that a rule reads the right label.
        Rule rule = ruleOn("ds_label(\"LIBRARY\") == \"Questionnaires\"");
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSTESTCD", "Q1")
                .build();

        RuleExecutionResult result = RuleRunner.execute(rule, qsco,
                name -> "QS".equals(name) ? qsco : null, "QS", library());

        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a healthy Library must not skip: " + result.getStatusMessage());
        assertFalse(result.getViolations().isEmpty(),
                "before Fix #370 the accessor returned null here and the rule could never fire");
    }


    @Test
    @DisplayName("the control — a working Library on a NON-split dataset is completely unaffected")
    void shippedVerdict_nonSplitUnchanged() throws Exception
    {
        Rule rule = ruleOn("ds_label(\"LIBRARY\") == \"Adverse Events\"");
        IDataTable ae = MockTable.of().name("AE").col("DOMAIN", "AE").col("AETERM", "HEADACHE")
                .build();

        RuleExecutionResult result = RuleRunner.execute(rule, ae,
                name -> "AE".equals(name) ? ae : null, "AE", library());

        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertFalse(result.getViolations().isEmpty());
    }

    // ------------------------------------------------------------------
    // ⭐ Cases found by chasing the JaCoCo gap on the new code, not by a failing test
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⭐ SQ-- reaches tier 3 too — the other half of the specified prefix")
    void sqPrefixedDataset_reachesTierThree()
    {
        // The design says SUPP--/SQ--, and until this test only the SUPP-- half was proven. SQ-- is
        // the SEND / split spelling ({@code unsplitNameFromData} builds it as "SQ" + RDOMAIN).
        IDataTable sqae = MockTable.of().name("SQAE").col("RDOMAIN", "AE").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for AE\"", sqae,
                library()));
    }


    @Test
    @DisplayName("⭐ a Library that publishes no SUPPQUAL — every ADaM run — resolves to null")
    void libraryWithoutSuppqual_tierThreeDeclines()
    {
        // Not hypothetical: an ADaM product publishes no SUPPQUAL at all, so tier 3 must decline
        // cleanly rather than substitute into nothing.
        MetadataProvider noSupp = new MetadataLibraryProvider(lib("adamig")
                .table(table("ADSL").label("Subject-Level Analysis Dataset").build()).build());
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();

        assertFalse(holds("non_empty(ds_label(\"LIBRARY\"))", suppae, noSupp));
        assertNull(ExprCompiler.datasetScopeOperandValue(ctx(suppae, null, noSupp),
                "library_dataset_label"));
    }


    @Test
    @DisplayName("a nameless dataset resolves to no domain and falls through every tier")
    void namelessDataset_fallsThroughCleanly()
    {
        // cdiscDomainOf returns "" when it can resolve nothing. ⚠ This asserts the OUTCOME only:
        // the `!domain.isEmpty()` guard is an optimisation, and deleting it leaves this green
        // (getDatasetMetadata("") also answers with an empty map). Nothing here pins that guard,
        // and nothing needs to.
        IDataTable nameless = MockTable.of().name("").col("QSTESTCD", "Q1").build();

        assertNull(ExprCompiler.datasetScopeOperandValue(ctx(nameless, null, library()),
                "library_dataset_label"));
    }


    @Test
    @DisplayName("⚠ a provider returning NULL instead of an empty map is handled the same way")
    void nullReturningProvider_isTreatedAsUnknown()
    {
        // The interface contracts an empty map, and both in-repo implementations honour it — but it
        // is implementable outside this module, so `unknownDataset` accepts null as well. A mock is
        // used here deliberately, and only here: this is the out-of-contract case.
        MetadataProvider nullish = mock(MetadataProvider.class);
        lenient().when(nullish.getDatasetMetadata(any())).thenReturn(null);
        lenient().when(nullish.getDatasetMetadata("QS"))
                .thenReturn(Map.of("label", "Questionnaires"));
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Questionnaires\"", qsco, nullish),
                "a null tier-1 answer must fall back exactly as an empty one does");
    }


    @Test
    @DisplayName("a SUPPQUAL map carrying a null value is substituted without blowing up")
    void suppqualWithNullValue_survives()
    {
        // getDatasetMetadata builds its map with putIfPresent, so an in-repo provider never yields
        // a null value; an out-of-repo one could. The map is walked wholesale, so this is the one
        // place that would NPE.
        Map<String, String> withNull = new java.util.HashMap<>();
        withNull.put("label", SUPP_TEMPLATE_LOWER);
        withNull.put("className", null);
        MetadataProvider provider = mock(MetadataProvider.class);
        lenient().when(provider.getDatasetMetadata(any())).thenReturn(Map.of());
        lenient().when(provider.getDatasetMetadata("SUPPQUAL")).thenReturn(withNull);
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();

        assertTrue(holds("ds_label(\"LIBRARY\") == \"Supplemental Qualifiers for AE\"", suppae,
                provider));
        assertFalse(holds("non_empty(ds_class(\"LIBRARY\"))", suppae, provider));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private static Rule ruleOn(String check) throws Exception
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T-370\"},\"Check\":{\"expression\":\""
                + check.replace("\"", "\\\"") + "\"},\"Outcome\":{\"Message\":\"m\"}}}}";
        Rule r = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(r);
        assertNull(r.getLoadError(), "rule failed to load: " + r.getLoadError());
        return r;
    }


    /**
     * Captures the {@code Fix #370} WARNINGs {@link ExprCompiler} emits while {@code body} runs.
     */
    private static List<String> capturingExprCompilerLog(Runnable body)
    {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger juli = Logger.getLogger(ExprCompiler.class.getName());
        Level previous = juli.getLevel();
        juli.addHandler(handler);
        juli.setLevel(Level.ALL);
        try
        {
            body.run();
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
        return handler.formatted();
    }

    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        List<String> formatted()
        {
            return records.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(r -> java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                    .toList();
        }


        @Override
        public void publish(LogRecord aRecord)
        {
            records.add(aRecord);
        }


        @Override
        public void flush()
        {
            // Nothing buffered.
        }


        @Override
        public void close()
        {
            // Nothing to release.
        }
    }
}
