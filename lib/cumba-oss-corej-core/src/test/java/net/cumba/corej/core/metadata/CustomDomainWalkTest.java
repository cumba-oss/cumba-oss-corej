package net.cumba.corej.core.metadata;

import static net.cumba.corej.core.metadata.CustomDomainWalkFixture.dataset;
import static net.cumba.corej.core.metadata.CustomDomainWalkFixture.igViewProvider;
import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.dev.MapResource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-custom-domain-model-walk} — both SDTM variable walks place a domain the run's IG does
 * not define in exactly the class the <b>scope matcher</b> gives it
 * ({@link ScopeClassLadder#classOf}, the one ladder {@code LibraryValidator} and the walks share),
 * read from the dataset's own columns and, for an {@code AP--} dataset, from its parent.
 *
 * <p>
 * Every provider here is built over the IG product's own table view
 * ({@link CustomDomainWalkFixture#igViewProvider()}), the shape every production caller builds.
 * That view carries no table for an IG-absent domain, so before the fix both walks answered
 * {@code []} for it: the model-order rules SKIPPED, and the rules reading the total operations ran
 * against an empty list and false-fired. The oracle for the walk after the fix is the forced-class
 * walk ({@link MetadataLibraryProvider#getStandardModelVariablesForClass}) with the scope class —
 * the same walk with step 2 given the class instead of deriving it (EC-85 D-1).
 * </p>
 *
 * <p>
 * ⚑ These are direct provider calls, not calls through {@link CompanionDomainsProvider}: production
 * wraps only ADaM runs in it ({@code StudyValidationService.maybeWrapCompanion}), so an SDTM case
 * through the wrapper would test a shape no run builds. The one wrapper case here is the ADaM one,
 * and it pins that the ADaM walk is untouched.
 * </p>
 */
class CustomDomainWalkTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** The sponsor-domain class shapes: topic columns → the class the sniffer gives them. */
    private static final Map<String, List<String>> SHAPES = shapes();

    private static Map<String, List<String>> shapes()
    {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("EVENTS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTERM", "XXDTC"));
        s.put("INTERVENTIONS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTRT", "XXDTC"));
        s.put("FINDINGS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXORRES", "XXDTC"));
        s.put("FINDINGS ABOUT",
                List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXOBJ", "XXORRES", "XXDTC"));
        return s;
    }


    private static List<String> names(@Nullable List<Map<String, String>> aRows)
    {
        assertNotNull(aRows);
        return aRows.stream().map(r -> r.get("name")).toList();
    }


    /**
     * The scope class — what the scope matcher gives the dataset ({@link ScopeClassLadder#classOf},
     * which {@code LibraryValidator} calls) — is {@code aExpectedScopeClass}; the three walk entry
     * points that take the table all answer the forced-class walk for it, and that walk is not
     * empty.
     */
    private static void assertWalksTheScopeClass(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver, String aExpectedScopeClass)
    {
        String member = aTable.getMetaData().getName();
        String scope = ScopeClassLadder.classOf(aProvider, member,
                CdiscDomainResolver.cdiscDomainOf(aTable), aTable, aResolver);
        assertEquals(aExpectedScopeClass, scope, "scope class of " + member);
        List<Map<String, String>> forced = aProvider.getStandardModelVariablesForClass(aTable,
                aResolver, scope);
        assertNotNull(forced, "the forced walk serves " + scope);
        assertFalse(forced.isEmpty(), "the forced walk serves " + scope);
        assertEquals(names(forced), aProvider.getStandardModelVariables(aTable, aResolver),
                "algorithm A (names) walks the scope class for " + member);
        assertEquals(forced, aProvider.getStandardModelVariablesDetailed(aTable, aResolver),
                "algorithm A (detailed) walks the scope class for " + member);
        // An IG-absent domain has no IG dataset to merge, so algorithm B is algorithm A.
        assertEquals(forced, aProvider.getStandardVariablesDetailed(aTable, aResolver),
                "algorithm B walks the scope class for " + member);
    }


    /** Neither the scope matcher nor any walk places the dataset. */
    private static void assertPlacedNowhere(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver)
    {
        String member = aTable.getMetaData().getName();
        assertNull(
                ScopeClassLadder.classOf(aProvider, member,
                        CdiscDomainResolver.cdiscDomainOf(aTable), aTable, aResolver),
                "scope class of " + member);
        assertEquals(List.of(), aProvider.getStandardModelVariables(aTable, aResolver));
        assertEquals(List.of(), aProvider.getStandardModelVariablesDetailed(aTable, aResolver));
        assertEquals(List.of(), aProvider.getStandardVariablesDetailed(aTable, aResolver));
    }

    // ------------------------------------------------------------------
    // The walk class is the scope class
    // ------------------------------------------------------------------


    @Test
    void aSponsorDomainOfEveryDetectableShapeWalksItsScopeClass()
    {
        MetadataLibraryProvider provider = igViewProvider();
        int checked = 0;
        for (Map.Entry<String, List<String>> shape : SHAPES.entrySet())
        {
            IDataTable xx = dataset("XX", "XX", shape.getValue().toArray(String[]::new));
            assertWalksTheScopeClass(provider, xx, NO_RESOLVER, shape.getKey());
            checked++;
        }
        // The population is the map's, so a shape dropped from it must not pass by vanishing.
        assertEquals(4, checked);
    }


    @Test
    void aSponsorFindingsDomainWalksTheExactFindingsModelList()
    {
        // The oracle above is the forced walk; this pins what that walk IS on the fixture, so the
        // two cannot drift together: identifiers, then the FINDINGS class variables, then timing —
        // every `--` substituted with the sponsor prefix.
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        assertEquals(
                List.of("STUDYID", "DOMAIN", "USUBJID", "XXSEQ", "XXTESTCD", "XXTEST", "XXCAT",
                        "XXORRES", "XXREPNUM", "VISITNUM", "XXDTC", "XXDY"),
                igViewProvider().getStandardModelVariables(xx, NO_RESOLVER));
    }


    @Test
    void anIgAbsentStandardDomainTakesTheCuratedClassNotTheSniff()
    {
        // VS is not in the fixture IG, but DomainClassMap (tier 2.5) maps it to FINDINGS. It
        // carries
        // no topic column, so a sniff alone would place it nowhere — the scope ladder does, and the
        // walk must follow the ladder, not the sniff (SENDIG-AR 1.0's LB/CL are this shape).
        IDataTable vs = dataset("VS", "VS", "STUDYID", "USUBJID", "VSSEQ", "VSORRES", "VSDTC");
        assertWalksTheScopeClass(igViewProvider(), vs, NO_RESOLVER, "FINDINGS");
    }


    @Test
    void aDeclaredStudyClassWinsOverTheSniffAsItDoesForScope()
    {
        // Tier 1 of the ladder: a study library declaring XX an EVENTS dataset, although its
        // columns sniff FINDINGS. The scope matcher says EVENTS, so the walk must too.
        IMetadataLibrary study = lib("study")
                .table(table("XX").className("EVENTS")
                        .column(column("DOMAIN", 0, DataValueType.STRING).build())
                        .column(column("XXTESTCD", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = CustomDomainWalkFixture.studyLibraryProvider(study);
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        assertWalksTheScopeClass(provider, xx, NO_RESOLVER, "EVENTS");
        assertTrue(provider.getStandardModelVariables(xx, NO_RESOLVER).contains("XXTERM"));
    }


    @Test
    void aStudyLibraryTableStillClassifiesADatasetWithNoColumns()
    {
        // The study-library shape the older tests build: the caller's table exposes no columns
        // (a metadata-only mock), so the ladder falls back to the library's own table view, exactly
        // as the scope matcher does. The answer is the one those tests have always pinned.
        IMetadataLibrary study = lib("study")
                .table(table("MYAE").column(column("DOMAIN", 0, DataValueType.STRING).build())
                        .column(column("MYAETERM", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = CustomDomainWalkFixture.studyLibraryProvider(study);
        IDataTable noColumns = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn("MYAE");
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(noColumns.getMetaData()).thenReturn(meta);
        assertEquals(
                List.of("STUDYID", "DOMAIN", "USUBJID", "MYAESEQ", "MYAETERM", "MYAEDECOD",
                        "MYAECAT", "VISITNUM", "MYAEDTC", "MYAEDY"),
                provider.getStandardModelVariables(noColumns, NO_RESOLVER));
    }


    @Test
    void anAssociatedPersonsDatasetOfASponsorParentInheritsThePresentParentsClass()
    {
        // APXX: no APXXTERM, so the ladder sniffs nothing on the AP dataset's own domain code and
        // inherits from the parent XX, which the resolver hands back — exactly as scope does.
        IDataTable apxx = dataset("APXX", "APXX", "STUDYID", "APID", "XXSEQ", "XXTERM");
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTERM");
        DatasetResolver parent = name -> "XX".equals(name) ? xx : null;
        MetadataLibraryProvider provider = igViewProvider();
        assertWalksTheScopeClass(provider, apxx, parent, "EVENTS");
        List<String> a = provider.getStandardModelVariables(apxx, parent);
        assertTrue(a.contains("APID"), "the AP identifiers are merged: " + a);
        assertTrue(a.contains("XXTERM"), "the parent prefix substitutes `--`: " + a);
    }


    @Test
    void anAssociatedPersonsDatasetTakesItsParentsClassNotItsOwnSniff()
    {
        // Review round 1, LOW-2: the class is the parent's, even where the AP dataset's own columns
        // would sniff another class under the parent prefix (XXTERM -> EVENTS here, while the
        // parent XX is a FINDINGS dataset). The walk used to take the sniff; scope takes the
        // parent — the walk must agree with scope.
        IDataTable apxx = dataset("APXX", "APXX", "STUDYID", "APID", "XXSEQ", "XXTERM");
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        assertWalksTheScopeClass(igViewProvider(), apxx, name -> "XX".equals(name) ? xx : null,
                "FINDINGS");
    }


    @Test
    void anAssociatedPersonsDatasetWhoseParentIsAbsentIsPlacedNowhere()
    {
        // Parent absent: scope places APXX in no class (its class-scoped rules are rejected), so
        // no walk may place it either — before the review fix the walk sniffed XXTERM -> EVENTS.
        IDataTable apxx = dataset("APXX", "APXX", "STUDYID", "APID", "XXSEQ", "XXTERM");
        assertPlacedNowhere(igViewProvider(), apxx, NO_RESOLVER);
    }


    @Test
    void aSplitMemberOfASponsorDomainWalksTheDomain()
    {
        // XX1 carries DOMAIN=XX: the CDISC code keys the walk, the member name only tier 1.
        IDataTable xx1 = dataset("XX1", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        MetadataLibraryProvider provider = igViewProvider();
        assertWalksTheScopeClass(provider, xx1, NO_RESOLVER, "FINDINGS");
        assertTrue(provider.getStandardModelVariables(xx1, NO_RESOLVER).contains("XXTESTCD"));
    }


    @Test
    void aMemberTheIgDefinesCarryingAnotherDomainCodeTakesTheMembersIgClass()
    {
        // Review round 1, LOW-1: member LB (an IG table, so tier 1 of the ladder reads its IG class
        // FINDINGS by member name) whose row-0 DOMAIN is LX, a code the IG does not define. The
        // walk keys the CDISC code (LX), so it is custom, and now walks FINDINGS under the LX
        // prefix — scope's class — where it answered [] before. On such inconsistent data the
        // LB-prefixed columns are then not model variables, so the allowed-variable rules report
        // them: more findings on bad data, the same verdict scope already implies.
        IDataTable lx = dataset("LB", "LX", "STUDYID", "USUBJID", "LBSEQ", "LBTESTCD");
        MetadataLibraryProvider provider = igViewProvider();
        // Tier 1 answers the IG table's own class spelling; the walk normalises it once.
        assertWalksTheScopeClass(provider, lx, NO_RESOLVER, "Findings");
        List<String> a = provider.getStandardModelVariables(lx, NO_RESOLVER);
        assertTrue(a.contains("LXTESTCD") && !a.contains("LBTESTCD"), "the LX prefix: " + a);
    }

    // ------------------------------------------------------------------
    // What does NOT move
    // ------------------------------------------------------------------


    @Test
    void theDomainStringEntryPointsStayDomainKeyed()
    {
        // CDW-D2 (b): get_column_order_from_library, required/expected variables and the
        // var_*("LIBRARY") lookup take no table; an IG-absent domain stays unserved there.
        MetadataLibraryProvider provider = igViewProvider();
        assertEquals(List.of(), provider.getColumnOrder("XX"));
        assertEquals(List.of(), provider.getRequiredVariables("XX"));
        assertEquals(List.of(), provider.getExpectedVariables("XX"));
        assertTrue(provider.getVariableMetadata("XX", "XXTERM").isEmpty());
    }


    @Test
    void theDomainStringEntryPointsKeepTheLibraryViewSniff()
    {
        // Without a dataset the custom branch keeps today's sniff of the provider's own library:
        // a study library that carries the custom table still classifies it by its columns there.
        IMetadataLibrary study = lib("study")
                .table(table("MYAE").column(column("DOMAIN", 0, DataValueType.STRING).build())
                        .column(column("MYAETERM", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = CustomDomainWalkFixture.studyLibraryProvider(study);
        assertEquals(
                List.of("STUDYID", "DOMAIN", "USUBJID", "MYAESEQ", "MYAETERM", "MYAEDECOD",
                        "MYAECAT", "VISITNUM", "MYAEDTC", "MYAEDY"),
                provider.getColumnOrder("MYAE"));
    }


    @Test
    void aSnifferMissKeepsEveryWalkEmpty()
    {
        // CDW-D3 (b): no topic column (or no DOMAIN column at all) — no tier places the dataset,
        // and every walk keeps today's empty answer.
        MetadataLibraryProvider provider = igViewProvider();
        IDataTable noTopic = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXVAL");
        IDataTable noDomain = dataset("XX", null, "STUDYID", "USUBJID", "XXSEQ", "XXTERM");
        int checked = 0;
        for (IDataTable t : List.of(noTopic, noDomain))
        {
            assertEquals(List.of(), provider.getStandardModelVariables(t, NO_RESOLVER));
            assertEquals(List.of(), provider.getStandardModelVariablesDetailed(t, NO_RESOLVER));
            assertEquals(List.of(), provider.getStandardVariablesDetailed(t, NO_RESOLVER));
            checked++;
        }
        assertEquals(2, checked);
    }


    @Test
    void anIgDefinedDomainIsNotReclassifiedByItsColumns()
    {
        // LB is in the IG. Its columns sniff EVENTS (LBTERM) — the IG class must win, because an
        // IG-defined domain never reaches the custom branch at all.
        IDataTable lb = dataset("LB", "LB", "STUDYID", "USUBJID", "LBSEQ", "LBTERM");
        MetadataLibraryProvider provider = igViewProvider();
        List<String> a = provider.getStandardModelVariables(lb, NO_RESOLVER);
        assertEquals(names(provider.getStandardModelVariablesForClass(lb, NO_RESOLVER, "FINDINGS")),
                a);
        assertFalse(a.contains("LBTERM"));
    }

    // ------------------------------------------------------------------
    // The operations reading the walks
    // ------------------------------------------------------------------


    /** The walk-backed list functions (wave 4) evaluated over {@code aTable} on the IG view. */
    private static Map<String, Object> walks(IDataTable aTable, DatasetResolver aResolver)
    {
        net.cumba.corej.core.expr.eval.EvalRun run = net.cumba.corej.core.expr.eval.EvalRun
                .fullRange(net.cumba.corej.core.exec.EvaluationContext.builder().table(aTable)
                        .libraryProvider(igViewProvider()).datasetResolver(aResolver).build());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("$model", answer(
                () -> net.cumba.corej.core.exec.LibraryLists.modelColumnOrder(run, List.of())));
        vars.put("$timing_model", answer(() -> net.cumba.corej.core.exec.LibraryLists
                .modelFilteredVariables(run, timing(3))));
        vars.put("$timing_dataset", answer(() -> net.cumba.corej.core.exec.LibraryLists
                .datasetFilteredVariables(run, timing(2))));
        vars.put("$natural", answer(
                () -> net.cumba.corej.core.exec.LibraryLists.naturalKeyVariables(run, List.of())));
        return vars;
    }


    private static List<net.cumba.corej.core.expr.eval.@Nullable Vector> timing(int arity)
    {
        List<net.cumba.corej.core.expr.eval.@Nullable Vector> args = new ArrayList<>();
        args.add(net.cumba.corej.core.expr.eval.ConstVector.of("role"));
        args.add(net.cumba.corej.core.expr.eval.ConstVector.of("Timing"));
        while (args.size() < arity)
        {
            args.add(null);
        }
        return args;
    }


    /** The function's list, or the SKIP signal rendered as the retired sentinel did. */
    private static Object answer(
            java.util.function.Supplier<net.cumba.corej.core.expr.eval.Vector> body)
    {
        try
        {
            return Objects.requireNonNull(body.get().value(0).resolved());
        }
        catch (net.cumba.corej.core.expr.eval.UnusableProviderAnswerException unusable)
        {
            return "<library not available>";
        }
    }


    @Test
    void theWalkBackedOperationsAnswerOnASponsorFindingsDomain()
    {
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXCAT",
                "XXORRES", "VISITNUM", "XXDTC");
        Map<String, Object> vars = walks(xx, NO_RESOLVER);
        assertEquals(igViewProvider().getStandardModelVariables(xx, NO_RESOLVER),
                vars.get("$model"));
        // The model-filtered selection does not intersect the dataset; the dataset-filtered one
        // does. Before the fix both were [] — the CG0219 / SD1299 / CG0562 false positives.
        assertEquals(List.of("VISITNUM", "XXDTC", "XXDY"), vars.get("$timing_model"));
        assertEquals(List.of("VISITNUM", "XXDTC"), vars.get("$timing_dataset"));
        // Natural-key roles present in the dataset, in walk order (the SD1117 false positive keyed
        // on USUBJID + XXTESTCD alone before the fix).
        assertEquals(List.of("XXCAT", "XXORRES", "VISITNUM", "XXDTC"), vars.get("$natural"));
    }


    @Test
    void theParentModelOrderOfASponsorSuppDatasetIsTheParentsWalk()
    {
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        // RDOMAIN names the sponsor parent, which the resolver hands back.
        IDataTable suppxx = MockTable.of().name("SUPPXX").col("STUDYID", "S").col("RDOMAIN", "XX")
                .col("USUBJID", "U1").col("IDVAR", "XXSEQ").col("IDVARVAL", "1")
                .col("QNAM", "XXSTRESC").col("QVAL", "v").build();
        // get_parent_model_column_order(RDOMAIN) is a per-row list function since wave 4: the
        // row's parent XX resolves through the resolver, and its walk is the answer.
        DatasetResolver toXx = name -> "XX".equals(name) ? xx : null;
        int rdomain = suppxx.getMetaData().getColumnIndex("RDOMAIN");
        net.cumba.corej.core.expr.eval.Vector parent = net.cumba.corej.core.exec.ParentModelColumnOrder
                .evaluate(net.cumba.corej.core.expr.eval.EvalRun.fullRange(
                        net.cumba.corej.core.exec.EvaluationContext.builder().table(suppxx)
                                .libraryProvider(igViewProvider()).datasetResolver(toXx).build()),
                        List.of(new net.cumba.corej.core.expr.eval.ColumnVector("RDOMAIN",
                                suppxx.getColumn(rdomain),
                                suppxx.getMetaData().getColumn(rdomain).getType())));
        assertEquals(igViewProvider().getStandardModelVariables(xx, NO_RESOLVER),
                parent.value(0).resolved(), "the parent's walk resolves");
    }


    @Test
    void aSnifferMissSkipsTheOrderLookupsAndLeavesTheTotalOperationsEmpty()
    {
        // CDW-D3 (b), unchanged: the order lookup maps [] to library-not-available (SKIP); the
        // total operations answer [].
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXVAL", "VISITNUM");
        Map<String, Object> vars = walks(xx, NO_RESOLVER);
        assertEquals("<library not available>", String.valueOf(vars.get("$model")));
        assertEquals(List.of(), vars.get("$timing_model"));
        assertEquals(List.of(), vars.get("$timing_dataset"));
        assertEquals(List.of(), vars.get("$natural"));
    }

    // ------------------------------------------------------------------
    // ADaM through the companion wrapper — the one shape production wraps
    // ------------------------------------------------------------------


    private static AdamProduct adamProduct()
    {
        Map<String, Object> studyId = new LinkedHashMap<>();
        studyId.put("name", "STUDYID");
        studyId.put("ordinal", "1");
        studyId.put("simpleDatatype", "Char");
        Map<String, Object> paramcd = new LinkedHashMap<>(studyId);
        paramcd.put("name", "PARAMCD");
        paramcd.put("ordinal", "2");
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Identifiers and Analysis");
        set.put("ordinal", "1");
        set.put("analysisVariables", List.of(studyId, paramcd));
        Map<String, Object> bds = new LinkedHashMap<>();
        bds.put("name", "BDS");
        bds.put("label", "Basic Data Structure");
        bds.put("class", "BASIC DATA STRUCTURE");
        bds.put("analysisVariableSets", List.of(set));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "ADaMIG");
        product.put("version", "1-3");
        product.put("dataStructures", List.of(bds));
        return MapResource.of(product, AdamProduct.class);
    }


    @Test
    void anAdamRunThroughTheCompanionWrapperKeepsItsAdamWalk()
    {
        // StudyValidationService wraps an ADaM run's provider with an SDTM companion. The SDTM
        // custom branch must not leak into it: the ADaM walk answers, exactly as unwrapped.
        IMetadataLibrary study = lib("study").table(table("ADXX").build()).build();
        MetadataLibraryProvider base = ApiModelLibraries.adamProvider(study, adamProduct(),
                "adamig", "1-3");
        CompanionDomainsProvider wrapped = new CompanionDomainsProvider(base, igViewProvider());
        IDataTable adxx = dataset("ADXX", null, "STUDYID", "USUBJID", "PARAMCD", "AVAL");
        List<String> unwrapped = base.getStandardModelVariables(adxx, NO_RESOLVER);
        assertEquals(List.of("STUDYID", "PARAMCD"), unwrapped);
        assertEquals(unwrapped, wrapped.getStandardModelVariables(adxx, NO_RESOLVER));
        assertEquals(base.getStandardModelVariablesDetailed(adxx, NO_RESOLVER),
                wrapped.getStandardModelVariablesDetailed(adxx, NO_RESOLVER));
        assertEquals(base.getStandardVariablesDetailed(adxx, NO_RESOLVER),
                wrapped.getStandardVariablesDetailed(adxx, NO_RESOLVER));
    }
}
