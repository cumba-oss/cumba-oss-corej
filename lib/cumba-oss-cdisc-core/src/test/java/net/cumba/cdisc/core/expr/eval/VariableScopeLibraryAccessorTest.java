package net.cumba.cdisc.core.expr.eval;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #373</b>, phase 1a — {@code var_*("LIBRARY")} asked the CDISC Library by the <b>member
 * name</b>, the VARIABLE-scope sibling of {@code Fix #370}'s dataset leg.
 *
 * <h2>The defect</h2>
 *
 * <p>
 * {@code ExprCompiler.readProviderLevel}'s VARIABLE branch resolved through
 * {@code provider.getVariableMetadata(ctx.getDomainName(), name)}, and {@code ctx.getDomainName()}
 * is the dataset <em>member</em> name. The Library publishes <b>domains</b>, so every
 * {@code var_*("LIBRARY")} read on a split member resolved to nothing. Unlike the dataset leg —
 * which no rule used — this one has <b>44 authored carrier files</b>.
 * </p>
 *
 * <h2>⚠⚠ Why the provider here is deliberately PRODUCT-LESS</h2>
 *
 * <p>
 * {@code new MetadataLibraryProvider(lib)} leaves {@code hasSdtmProduct()} false, so algorithm-B
 * (leg 2) never runs and tier 1 misses <em>deterministically</em> for a split member. That isolates
 * exactly what phase 1a fixes. The AP/SUPP behaviour that leg 2 drives — and phase 1b repairs — is
 * pinned separately in {@code MetadataLibraryProviderCanonicalCodelistTest}; asserting it here too
 * would couple two independent fixes into one test and hide which of them broke.
 * </p>
 */
class VariableScopeLibraryAccessorTest
{

    // ------------------------------------------------------------------
    // Fixtures — a Library that publishes DOMAINS and never a member name
    // ------------------------------------------------------------------

    private static IMetadataLibrary standardLibrary()
    {
        return lib("sdtmig")
                .table(table("QS").label("Questionnaires").className("Findings")
                        .column(column("QSORRES", 5, DataValueType.STRING)
                                .label("Finding in Original Units").role("Result Qualifier")
                                .core("Exp").build())
                        .build())
                .table(table("LB").label("Laboratory Test Results").className("Findings")
                        .column(column("LBORRES", 6, DataValueType.STRING)
                                .label("Result or Finding in Original Units")
                                .role("Result Qualifier").core("Exp").build())
                        .build())
                .table(table("MH").label("Medical History").className("Events")
                        .column(column("MHTERM", 4, DataValueType.STRING)
                                .label("Reported Term for the Medical History").role("Topic")
                                .core("Req").build())
                        .build())
                .table(table("AE").label("Adverse Events").className("Events")
                        .column(column("AESEV", 8, DataValueType.STRING).label("Severity/Intensity")
                                .role("Record Qualifier").core("Perm").build())
                        .build())
                .build();
    }


    private static MetadataProvider library()
    {
        return new MetadataLibraryProvider(standardLibrary());
    }


    private static EvaluationContext ctx(IDataTable table, String variable,
            MetadataProvider defineProvider, MetadataProvider libraryProvider)
    {
        return EvaluationContext.builder().table(table).domainName(table.getMetaData().getName())
                .variables(Map.of("variable_name", variable)).defineProvider(defineProvider)
                .libraryProvider(libraryProvider).build();
    }


    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    /** True when the expression held on row 0 — the accessor broadcasts, so one row is enough. */
    private static boolean holds(String expr, IDataTable table, String variable)
    {
        return eval(expr, ctx(table, variable, null, library())).get(0);
    }

    // ------------------------------------------------------------------
    // §4.2 D5 — the test matrix
    // ------------------------------------------------------------------


    @Test
    @DisplayName("row 1 — a non-split standard dataset resolves by NAME (tier 1), as before")
    void nonSplitDataset_tier1_unchanged()
    {
        IDataTable qs = MockTable.of().name("QS").col("DOMAIN", "QS").col("QSORRES", "5").build();

        assertTrue(holds("var_label(\"LIBRARY\") == \"Finding in Original Units\"", qs, "QSORRES"));
        assertTrue(holds("var_role(\"LIBRARY\") == \"Result Qualifier\"", qs, "QSORRES"));
        assertTrue(holds("var_core(\"LIBRARY\") == \"Exp\"", qs, "QSORRES"));
    }


    @Test
    @DisplayName("row 2 ⭐ THE FIX — a letter split (qsco, DOMAIN=QS) resolves via tier 2")
    void letterSplitMember_resolvesViaCdiscDomain()
    {
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSORRES", "5")
                .build();

        assertTrue(
                holds("var_label(\"LIBRARY\") == \"Finding in Original Units\"", qsco, "QSORRES"),
                "the Library has no dataset called 'qsco'; it publishes the DOMAIN, QS");
        assertTrue(holds("var_role(\"LIBRARY\") == \"Result Qualifier\"", qsco, "QSORRES"));
        assertTrue(holds("var_core(\"LIBRARY\") == \"Exp\"", qsco, "QSORRES"));
    }


    @Test
    @DisplayName("row 3 — a digit split (LB1, DOMAIN=LB) resolves via tier 2")
    void digitSplitMember_resolvesViaCdiscDomain()
    {
        IDataTable lb1 = MockTable.of().name("LB1").col("DOMAIN", "LB").col("LBORRES", "7").build();

        assertTrue(holds("var_label(\"LIBRARY\") == \"Result or Finding in Original Units\"", lb1,
                "LBORRES"));
    }


    @Test
    @DisplayName("row 4 — no DOMAIN cell: tier 2 falls through cdiscDomainOf to unsplitName")
    void noDomainCell_resolvesViaUnsplitName()
    {
        IDataTable qs1 = MockTable.of().name("QS1").col("QSORRES", "5").build();

        assertTrue(holds("var_label(\"LIBRARY\") == \"Finding in Original Units\"", qs1, "QSORRES"),
                "cdiscDomainOf's second leg: unsplitName(\"QS1\") -> QS");
    }


    @Test
    @DisplayName("row 5 ⭐ APMH1 — the AP-compound split a naive fix silently misses (§7.1)")
    void apCompoundSplitMember_resolves()
    {
        IDataTable apmh1 = MockTable.of().name("APMH1").col("DOMAIN", "MH").col("MHTERM", "COLD")
                .build();

        assertTrue(
                holds("var_label(\"LIBRARY\") == \"Reported Term for the Medical History\"", apmh1,
                        "MHTERM"),
                "a provider-internal fix applied BEFORE the AP strip would leave APMH1 broken; "
                        + "cdiscDomainOf reads the DOMAIN cell and never needs the strip");
        assertTrue(holds("var_role(\"LIBRARY\") == \"Topic\"", apmh1, "MHTERM"));
    }


    @Test
    @DisplayName("row 6 — a genuinely custom domain still resolves to null, unchanged")
    void customDomain_staysNull()
    {
        IDataTable zz = MockTable.of().name("ZZ").col("DOMAIN", "ZZ").col("ZZTERM", "x").build();

        assertFalse(holds("non_empty(var_label(\"LIBRARY\"))", zz, "ZZTERM"),
                "ZZ is not a Library dataset and unsplitName leaves it unchanged");
    }


    @Test
    @DisplayName("row 7 — a variable the DOMAIN does not publish stays null (no invention)")
    void unknownVariableOnAResolvedDomain_staysNull()
    {
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSXX", "1").build();

        assertFalse(holds("non_empty(var_label(\"LIBRARY\"))", qsco, "QSXX"),
                "tier 2 resolves the DOMAIN, but QS publishes no QSXX — the read must stay empty");
    }


    @Test
    @DisplayName("row 8 ⭐ §2 ASYMMETRY — DEFINE is member-keyed and must NOT get the fallback")
    void defineLevel_staysMemberKeyed()
    {
        // ⚠⚠ THE SHAPE MATTERS. An earlier version of this test stubbed DEFINE to HIT on
        // the member; tier 1 then short-circuited and the test still passed with the level guard
        // REMOVED — it proved nothing (caught by mutation 3). The fallback only runs when tier 1
        // MISSES, so the define must miss on the member and hit on the parent. That is also the
        // realistic risk case: a Define-XML that does not declare the split member, where a
        // fallback would silently answer from the PARENT's ItemGroupDef.
        MetadataProvider define = mock(MetadataProvider.class);
        // ⚑ Map.of(), not null: getVariableMetadata is contracted to return an EMPTY map when
        // unknown, so stubbing null would exercise a branch production never takes.
        lenient().when(define.getVariableMetadata("qsco", "QSORRES")).thenReturn(Map.of());
        lenient().when(define.getVariableMetadata("QS", "QSORRES"))
                .thenReturn(Map.of("label", "Sponsor label for QS"));
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSORRES", "5")
                .build();

        assertFalse(
                eval("non_empty(var_label(\"DEFINE\"))", ctx(qsco, "QSORRES", define, library()))
                        .get(0),
                "DEFINE is member-keyed BY DESIGN: an undeclared split member must read EMPTY, "
                        + "never borrow the parent's declaration. A fallback here would make "
                        + "FDA-SD1325 / PMDA-SD1325 fire on conforming data");
    }


    @Test
    @DisplayName("row 9 ⭐ §4.1e — an UNGUARDED != fires BEFORE the fix and goes silent AFTER")
    void unguardedNotEquals_goesSilent()
    {
        // The CDISC-SEND-0005 shape. A null LIBRARY read is not silence: it compares as "", so
        // `X != var_name("LIBRARY")` was TRUE on every split member. Once the read resolves, the
        // names match and the finding DISAPPEARS — the one mover in the shrinking direction.
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSORRES", "5")
                .build();

        assertFalse(holds("\"QSORRES\" != var_name(\"LIBRARY\")", qsco, "QSORRES"),
                "after the fix var_name(\"LIBRARY\") is QSORRES, so the inequality is false");
        assertTrue(holds("\"QSORRES\" == var_name(\"LIBRARY\")", qsco, "QSORRES"));
    }


    @Test
    @DisplayName("row 10 — the operand twin (library_variable_*) rides the same resolution")
    void operandTwin_resolvesToo()
    {
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSORRES", "5")
                .build();

        assertEquals(
                Map.of("label", "Finding in Original Units", "role", "Result Qualifier", "core",
                        "Exp", "name", "QSORRES", "simpleDatatype", "Char", "ordinal", "5"),
                ExprCompiler.libraryVariableMetadata(library(), qsco, "qsco", "QSORRES"),
                "the shared helper is what RuleRunner's three LIBRARY sites call");
    }


    @Test
    @DisplayName("gap — a provider returning NULL (not an empty map) still falls through to tier 2")
    void nullFromProvider_isTreatedAsAMiss()
    {
        // getVariableMetadata is contracted to return an EMPTY map when unknown, but the interface
        // is implementable outside this module and Mockito returns null for an un-stubbed call —
        // so the helper must tolerate null rather than NPE or short-circuit.
        MetadataProvider partial = mock(MetadataProvider.class);
        lenient().when(partial.getVariableMetadata("QS", "QSORRES"))
                .thenReturn(Map.of("label", "Finding in Original Units"));
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").col("QSORRES", "5")
                .build();

        assertEquals(Map.of("label", "Finding in Original Units"),
                ExprCompiler.libraryVariableMetadata(partial, qsco, "qsco", "QSORRES"),
                "tier 1 answered null; tier 2 must still run");
    }


    @Test
    @DisplayName("⭐ TIER 1 WINS — a member the Library knows is NOT re-resolved via its DOMAIN cell")
    void tierOneWins_evenWhenATableCouldResolveADomain()
    {
        // ⭐⭐ Pins the ORDER, which is load-bearing and which mutation showed was untested: with the
        // tier-1 short-circuit deleted the whole class still passed. Two real shapes depend on it —
        // a study-backed MetadataLibraryProvider (buildOrDegraded's no-access branch) is
        // member-keyed, and a SUPP dataset that carries a DOMAIN cell would resolve to the RELATED
        // domain and lose RDOMAIN/QEVAL. Here: the member IS known and its answer differs from the
        // domain's, so only tier-1-wins can produce it.
        MetadataProvider both = mock(MetadataProvider.class);
        lenient().when(both.getVariableMetadata("QS1", "QSORRES"))
                .thenReturn(Map.of("label", "the MEMBER's own answer"));
        lenient().when(both.getVariableMetadata("QS", "QSORRES"))
                .thenReturn(Map.of("label", "the DOMAIN's answer"));
        IDataTable qs1 = MockTable.of().name("QS1").col("DOMAIN", "QS").col("QSORRES", "5").build();

        assertEquals(Map.of("label", "the MEMBER's own answer"),
                ExprCompiler.libraryVariableMetadata(both, qs1, "QS1", "QSORRES"),
                "tier 2 must not run when tier 1 answered");
    }


    @Test
    @DisplayName("⭐ AP SHIM — an AP split member keeps its AP prefix, so APID/RSUBJID/SREL survive")
    void apSplitMember_keepsTheApPrefixSoTheShimVariablesResolve()
    {
        // ⛔ Found by the Fix #373 review. cdiscDomainOf prefers the row-0 DOMAIN cell, which on a
        // conforming AP dataset holds the RELATED domain (MH). But the ASSOCIATED PERSONS
        // identifiers live on the AP-PREFIXED key — buildResolvedSdtm strips AP itself and sets
        // addAP to merge them. Resolving APMH1 to a bare "MH" silently lost exactly those
        // variables, while an unsplit APMH resolved them fine.
        MetadataProvider ap = mock(MetadataProvider.class);
        lenient().when(ap.getVariableMetadata("APMH", "RSUBJID"))
                .thenReturn(Map.of("label", "Related Subject or Pool Identifier", "core", "Req"));
        lenient().when(ap.getVariableMetadata("MH", "RSUBJID")).thenReturn(Map.of());
        IDataTable apmh1 = MockTable.of().name("APMH1").col("DOMAIN", "MH").col("RSUBJID", "S1")
                .build();

        assertEquals("APMH", ExprCompiler.libraryVariableDomain(apmh1, "APMH1"),
                "the DOMAIN cell says MH; the Library key must be re-prefixed to APMH");
        assertEquals(Map.of("label", "Related Subject or Pool Identifier", "core", "Req"),
                ExprCompiler.libraryVariableMetadata(ap, apmh1, "APMH1", "RSUBJID"));
    }


    @Test
    @DisplayName("a non-AP member is NOT re-prefixed, and an already-AP domain is not doubled")
    void apPrefixing_isNarrow()
    {
        IDataTable qsco = MockTable.of().name("qsco").col("DOMAIN", "QS").build();
        assertEquals("QS", ExprCompiler.libraryVariableDomain(qsco, "qsco"));

        IDataTable apmh1 = MockTable.of().name("APMH1").col("DOMAIN", "APMH").build();
        assertEquals("APMH", ExprCompiler.libraryVariableDomain(apmh1, "APMH1"),
                "the DOMAIN cell already carries the AP prefix — do not double it");

        IDataTable ap = MockTable.of().name("AP").col("DOMAIN", "MH").build();
        assertEquals("MH", ExprCompiler.libraryVariableDomain(ap, "AP"),
                "\"AP\" is two characters: not an AP-prefixed member");
    }


    @Test
    @DisplayName("no table and no resolvable domain — the member key is kept, and never null")
    void unresolvableTable_keepsTheMemberKeyAndReturnsAnEmptyMap()
    {
        // ⚑ Rewritten after the review: the previous pair asserted only that the RESULT was empty,
        // which stayed true with both the null-guard and the isEmpty() guard deleted. Assert the
        // resolved KEY instead — that is what the guards actually decide.
        assertEquals("qsco", ExprCompiler.libraryVariableDomain(null, "qsco"),
                "a null table cannot resolve anything; the member key must survive");
        IDataTable nameless = MockTable.of().name("").col("QSORRES", "5").build();
        assertEquals("qsco", ExprCompiler.libraryVariableDomain(nameless, "qsco"),
                "cdiscDomainOf returns \"\" here; the member key must survive, never \"\"");

        // …and the public contract: never null, even from a null-returning provider.
        MetadataProvider nulls = mock(MetadataProvider.class);
        assertEquals(Map.of(), ExprCompiler.libraryVariableMetadata(nulls, null, "qsco", "QSORRES"),
                "declared non-null: a provider's null must not leak through");
    }
}
