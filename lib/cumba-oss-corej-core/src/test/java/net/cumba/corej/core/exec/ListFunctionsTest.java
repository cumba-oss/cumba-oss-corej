package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.ElementTable;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.metadata.LibraryVariableAttributes;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Wave 4 ({@code PLAN-list-functions} §4.2): the 21 dataset-level list functions — their
 * descriptors, every empty / unusable arm of §2.2 carried from the retired operations (D-W4-1,
 * D-W4-9), the per-row parent walk with the D2 fix, the loud non-inventory arm (D-W4-5),
 * {@code minus} with the I6 structural tuple comparison (D-W4-4), the study-level classification
 * carried over (D-W4-6), the compile seam's vocabularies (D-W4-3) and a handful of rule-level runs.
 * Real objects and small fakes throughout — no Mockito (owner, 2026-09-28).
 */
class ListFunctionsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DatasetResolver NO_INVENTORY = _ -> null;

    // ============================================================ the descriptors

    @Test
    void everyListFunctionIsARegisteredListTypedAggregateWithItsCapability()
    {
        for (String name : ListFunctionSupport.FUNCTION_NAMES)
        {
            FunctionDescriptor d = Objects.requireNonNull(FunctionRegistry.descriptor(name), name);
            assertTrue(ElementTable.resultType(name) instanceof ExprType.ListOf,
                    name + " is list-typed (D-W4-11)");
            boolean perRow = ParentModelColumnOrder.NAME.equals(name);
            assertEquals(!perRow, d.aggregate(), name + ": one list for the dataset (D-W4-10)"
                    + " — only the parent walk is per row");
        }
        for (String name : List.of(LibraryLists.REQUIRED_VARIABLES, LibraryLists.EXPECTED_VARIABLES,
                LibraryLists.GET_COLUMN_ORDER_FROM_LIBRARY, LibraryLists.GET_MODEL_COLUMN_ORDER,
                LibraryLists.VARIABLE_NAMES, LibraryLists.STANDARD_DOMAINS,
                LibraryLists.GET_DATASET_FILTERED_VARIABLES, LibraryLists.NATURAL_KEY_VARIABLES,
                LibraryLists.GET_MODEL_FILTERED_VARIABLES, LibraryLists.VALID_CODELIST_DATES,
                LibraryLists.CODELIST_TERMS, ParentModelColumnOrder.NAME))
        {
            assertEquals(ProviderNeed.LIBRARY, descriptor(name).provider(), name);
        }
        for (String name : List.of(DefineLists.DEFINE_VARIABLE_NAMES,
                DefineLists.DEFINE_DATASET_NAMES, DefineLists.DEFINE_KEY_VARIABLES))
        {
            assertEquals(ProviderNeed.DEFINE, descriptor(name).provider(), name);
        }
        for (String name : List.of(InventoryLists.GET_COLUMN_ORDER_FROM_DATASET,
                InventoryLists.DATASET_NAMES, InventoryLists.STUDY_DOMAINS,
                InventoryLists.SPLIT_SIBLING_LENGTH_MISMATCH,
                InventoryLists.DUPLICATE_LABEL_VARIABLES, Minus.NAME))
        {
            assertNull(descriptor(name).provider(), name + " needs no provider");
        }
        assertEquals(21, ListFunctionSupport.FUNCTION_NAMES.size());
    }

    // ============================================================ the LIBRARY walks


    @Test
    void columnOrderFromLibraryAnswersTheProvidersListAndSkipsOnNothing()
    {
        Library lib = new Library();
        lib.columnOrder = List.of("STUDYID", "USUBJID", "DOMAIN");
        assertEquals(List.of("STUDYID", "USUBJID", "DOMAIN"),
                list(LibraryLists.columnOrderFromLibrary(run(dm(), lib), List.of())));
        // J10: a domain absent from the library variable model resolves to an empty order, and
        // that SKIPs — an empty list would defeat `not empty($column_order_from_library)`.
        lib.columnOrder = List.of();
        assertUnusable(LibraryLists.GET_COLUMN_ORDER_FROM_LIBRARY, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.columnOrderFromLibrary(run(dm(), lib), List.of()));
        lib.columnOrder = null;
        assertUnusable(LibraryLists.GET_COLUMN_ORDER_FROM_LIBRARY, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.columnOrderFromLibrary(run(dm(), lib), List.of()));
    }


    @Test
    void modelColumnOrderAnswersTheModelWalkAndSkipsOnNullOrEmpty()
    {
        Library lib = new Library();
        lib.modelColumnOrder.put("AE", List.of("STUDYID", "USUBJID", "AESEQ"));
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"),
                list(LibraryLists.modelColumnOrder(run(table("AE", "X", "1"), lib), List.of())));
        // Fix #55: null (no product / degraded) and empty both SKIP — the alternative is the
        // FDA-SD0058 fan-out, one finding per column against an empty allowed set.
        assertUnusable(LibraryLists.GET_MODEL_COLUMN_ORDER, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.modelColumnOrder(run(table("XYZ", "X", "1"), lib), List.of()));
        lib.modelColumnOrder.put("XYZ", List.of());
        assertUnusable(LibraryLists.GET_MODEL_COLUMN_ORDER, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.modelColumnOrder(run(table("XYZ", "X", "1"), lib), List.of()));
    }


    @Test
    void variableNamesAndStandardDomainsSkipOnAnEmptyOrAbsentEnumeration()
    {
        Library lib = new Library();
        IDataTable supp = table("SUPPAE", "QNAM", "AESEQ");
        lib.standardVariableNames = List.of("STUDYID", "USUBJID", "AESEQ", "AETERM");
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM"),
                list(LibraryLists.variableNames(run(supp, lib), List.of())));
        lib.standardVariableNames = List.of();
        assertUnusable(LibraryLists.VARIABLE_NAMES, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.variableNames(run(supp, lib), List.of()));
        lib.standardVariableNames = null;
        assertUnusable(LibraryLists.VARIABLE_NAMES, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.variableNames(run(supp, lib), List.of()));
        // EC-14: the empty-enumeration guard is critical — SRCDOM is_not_contained_by [] would
        // fire on every populated SRCDOM in a degraded run.
        IDataTable relrec = table("RELREC", "SRCDOM", "DM");
        lib.standardDatasetNames = List.of("DM", "AE", "LB", "RELREC");
        assertEquals(List.of("DM", "AE", "LB", "RELREC"),
                list(LibraryLists.standardDomains(run(relrec, lib), List.of())));
        lib.standardDatasetNames = List.of();
        assertUnusable(LibraryLists.STANDARD_DOMAINS, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.standardDomains(run(relrec, lib), List.of()));
        lib.standardDatasetNames = null;
        assertUnusable(LibraryLists.STANDARD_DOMAINS, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.standardDomains(run(relrec, lib), List.of()));
    }


    @Test
    void requiredAndExpectedVariablesAnswerTheDomainKeyedListsAndAnEmptyOneIsAPass()
    {
        Library lib = new Library();
        lib.requiredVariables = List.of("STUDYID", "USUBJID");
        lib.expectedVariables = List.of("AESTDTC");
        IDataTable ae = table("AE", "X", "1");
        assertEquals(List.of("STUDYID", "USUBJID"),
                list(LibraryLists.requiredVariables(run(ae, lib), List.of())));
        assertEquals(List.of("AESTDTC"),
                list(LibraryLists.expectedVariables(run(ae, lib), List.of())));
        lib.requiredVariables = List.of();
        assertEquals(List.of(), list(LibraryLists.requiredVariables(run(ae, lib), List.of())),
                "a domain that publishes nothing is a legitimate pass (Fix #368 step 4)");
    }


    @Test
    void structureKeyedRequiredVariablesTryEveryTokenAndSkipWhenNoneResolves()
    {
        // ADaM: the provider is structure-keyed; ADSL resolves through its structure token and
        // the published naming template TRTxxP is substituted against the real columns.
        StructureLibrary lib = new StructureLibrary();
        lib.published.put("SUBJECT LEVEL ANALYSIS DATASET",
                List.of("STUDYID", "USUBJID", "TRTxxP"));
        IDataTable adsl = MockTable.of().col("STUDYID", "S").col("USUBJID", "1").col("TRT01P", "A")
                .col("TRT02P", "B").name("ADSL").build();
        assertEquals(List.of("STUDYID", "USUBJID", "TRT01P", "TRT02P"),
                list(LibraryLists.requiredVariables(run(adsl, lib), List.of())));
        // A template no column matches contributes the template verbatim — reported as missing.
        IDataTable bare = MockTable.of().col("STUDYID", "S").col("USUBJID", "1").name("ADSL")
                .build();
        assertEquals(List.of("STUDYID", "USUBJID", "TRTxxP"),
                list(LibraryLists.requiredVariables(run(bare, lib), List.of())));
        // No token resolves ⇒ SKIP, never a green pass (the ADaM invisibility of Fix #368).
        lib.published.clear();
        assertUnusable(LibraryLists.REQUIRED_VARIABLES, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.requiredVariables(run(adsl, lib), List.of()));
    }


    @Test
    void theFilteredWalksAnswerTheLegacyRowsAndAnEmptySourceIsAnEmptyList()
    {
        Library lib = new Library();
        lib.domainVariables = List.of(Map.of("name", "USUBJID", "role", "Identifier"),
                Map.of("name", "AESEQ", "role", "Identifier"),
                Map.of("name", "STUDYID", "role", "Other"));
        IDataTable ae = MockTable.of().col("STUDYID", "S").col("USUBJID", "X").col("AESEQ", "1")
                .name("AE").build();
        List<Object> ids = list(
                LibraryLists.datasetFilteredVariables(run(ae, lib), args("role", "Identifier")));
        assertEquals(List.of("USUBJID", "AESEQ"), ids);
        assertEquals(List.of("USUBJID", "AESEQ", "STUDYID"),
                list(LibraryLists.datasetFilteredVariables(run(ae, lib), args(null, null))),
                "no filter: every served variable present in the dataset, in library order");
        lib.domainVariables = List.of();
        assertEquals(List.of(),
                list(LibraryLists.datasetFilteredVariables(run(ae, lib), args("role", "Timing"))));
        // The Model walk substitutes the `--` names with the dataset's prefix on the legacy path.
        lib.modelVariables = List.of(Map.of("name", "--SEQ", "role", "Identifier"),
                Map.of("name", "--TERM", "role", "Topic"),
                Map.of("name", "USUBJID", "role", "Identifier"));
        assertEquals(List.of("AESEQ", "USUBJID"), list(LibraryLists
                .modelFilteredVariables(run(ae, lib), args("role", "Identifier", null))));
        lib.modelVariables = List.of();
        assertEquals(List.of(),
                list(LibraryLists.modelFilteredVariables(run(ae, lib),
                        args("role", "Identifier", null))),
                "own-class walk: an empty source stays an empty list (the old arm)");
    }


    @Test
    void naturalKeyVariablesKeepTheNaturalKeyRolesPresentInTheDataset()
    {
        IDataTable lb = MockTable.of().col("STUDYID", "S").col("USUBJID", "X")
                .col("LBTESTCD", "GLUC").col("VISITNUM", "1").col("LBSPEC", "BLOOD")
                .col("LBMETHOD", "M").col("LBSCAT", "C").name("LB").build();
        Library lib = new Library();
        lib.domainVariables = List.of(Map.of("name", "USUBJID", "role", "Identifier"),
                Map.of("name", "LBTESTCD", "role", "Topic"),
                Map.of("name", "VISITNUM", "role", "Timing"),
                Map.of("name", "LBSPEC", "role", "Grouping Qualifier"),
                Map.of("name", "LBORRES", "role", "Result Qualifier"),
                Map.of("name", "LBMETHOD", "role", "Record Qualifier"),
                Map.of("name", "--SCAT", "role", "Variable Qualifier"),
                Map.of("name", "--SYN", "role", "Synonym Qualifier"));
        assertEquals(List.of("VISITNUM", "LBSPEC", "LBMETHOD", "LBSCAT"),
                list(LibraryLists.naturalKeyVariables(run(lb, lib), List.of())),
                "Identifier / Topic excluded, absent LBORRES / LBSYN dropped, --SCAT resolved");
        lib.domainVariables = List.of();
        assertEquals(List.of(), list(LibraryLists.naturalKeyVariables(run(lb, lib), List.of())));
        assertUnusable(LibraryLists.NATURAL_KEY_VARIABLES, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.naturalKeyVariables(run(lb, null), List.of()));
    }


    @Test
    void modelFilteredVariablesWithAModelClassWalksThatClassAndSkipsWhenUnserved()
    {
        IDataTable bw = table("BW", "DOMAIN", "BW");
        Library lib = new Library();
        lib.standardModelVariablesDetailed = List.of(Map.of("name", "BWTESTCD", "role", "Topic"));
        lib.standardModelVariablesForClass = List.of(Map.of("name", "BWTERM", "role", "Topic"),
                Map.of("name", "BWDECOD", "role", "Synonym Qualifier"),
                Map.of("name", "BWSEV", "role", "Record Qualifier"));
        assertEquals(List.of("BWTERM", "BWDECOD", "BWSEV"), list(
                LibraryLists.modelFilteredVariables(run(bw, lib), args(null, null, "EVENTS"))));
        assertEquals("EVENTS", lib.lastModelClassAsked, "the own-class walk is not consulted");
        assertEquals(List.of("BWTERM"),
                list(LibraryLists.modelFilteredVariables(run(bw, lib),
                        args("role", "Topic", "events"))),
                "composes with the role filter; normalised");
        // The legacy fallback is the CLASS-keyed map, with prefix substitution.
        lib.standardModelVariablesForClass = null;
        lib.modelVariables = List.of(Map.of("name", "--TESTCD", "role", "Topic"));
        lib.modelVariablesForClass = List.of(Map.of("name", "--TERM", "role", "Topic"),
                Map.of("name", "STUDYID", "role", "Identifier"));
        assertEquals(List.of("BWTERM", "STUDYID"), list(
                LibraryLists.modelFilteredVariables(run(bw, lib), args(null, null, "EVENTS"))));
        // D-6: a class the library cannot serve SKIPs — never an empty set a membership leaf
        // could silently never-fire against; the own-class arm above keeps List.of().
        lib.modelVariablesForClass = List.of();
        assertUnusable(LibraryLists.GET_MODEL_FILTERED_VARIABLES, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.modelFilteredVariables(run(bw, lib),
                        args(null, null, "EVENTS")));
        lib.standardModelVariablesForClass = List.of();
        assertUnusable(LibraryLists.GET_MODEL_FILTERED_VARIABLES, ProviderNeed.Kind.LIBRARY,
                () -> LibraryLists.modelFilteredVariables(run(bw, lib),
                        args(null, null, "EVENTS")));
    }


    @Test
    void validCodelistDatesFiltersByTheStandardOrTheNamedTypesAndSortsTheDates()
    {
        IDataTable dm = dm();
        Library lib = new Library();
        lib.standard = "sdtmig";
        lib.publishedCtPackages = List.of("sdtmct-2024-12-13", "sdtmct-2024-09-26",
                "adamct-2024-09-26", "unknown-pkg", "nodash");
        assertEquals(List.of("2024-09-26", "2024-12-13"),
                list(LibraryLists.validCodelistDates(run(dm, lib), args((String) null))));
        assertEquals(List.of("2024-09-26"), list(LibraryLists.validCodelistDates(run(dm, lib),
                List.of(ConstVector.of(List.of("ADAM"))))), "ct_package_types overrides");
        lib.standard = "unknownstandard";
        assertEquals(List.of(),
                list(LibraryLists.validCodelistDates(run(dm, lib), args((String) null))));
        lib.standard = null;
        assertEquals(List.of(),
                list(LibraryLists.validCodelistDates(run(dm, lib), args((String) null))));
        lib.publishedCtPackages = List.of();
        assertEquals(List.of(),
                list(LibraryLists.validCodelistDates(run(dm, lib), args((String) null))),
                "no published package is an empty answer on a working Library");
    }


    @Test
    void codelistTermsUnionsTheNamedCodelistsAndSkipsOnAnEmptyUnion()
    {
        Library lib = new Library();
        lib.codelistTerms.put("DOMAIN", List.of("C1", "C2"));
        lib.codelistTerms.put("EXTRA", List.of("C2", "C3"));
        IDataTable dm = dm();
        assertEquals(List.of("C1", "C2", "C3"),
                list(LibraryLists.codelistTerms(run(dm, lib),
                        codelists(List.of("DOMAIN", "EXTRA"), "term", "value"))),
                "union, de-duplicated, order preserved");
        assertEquals(List.of("C1", "C2"), list(LibraryLists.codelistTerms(run(dm, lib),
                codelists(List.of("DOMAIN", "DOMAIN"), "term", "value"))));
        // (term, code) resolves through the getCodelist view's concept ids — getCodelistTerms
        // (submission values) must NOT be consulted for that shape.
        lib.codelists.put("DOMAIN",
                new Codelist("DOMAIN", List.of(new Entry("DM", "Demographics", "C12345"),
                        new Entry("AE", "Adverse Events", "C67890"))));
        assertEquals(List.of("C12345", "C67890"), list(LibraryLists.codelistTerms(run(dm, lib),
                codelists(List.of("DOMAIN"), "term", "code"))));
        assertEquals(List.of("C12345", "C67890"),
                list(LibraryLists.codelistTerms(run(dm, lib),
                        codelists(List.of("DOMAIN"), "term", null))),
                "term level defaults to code");
        // An empty union — no CT package, an unknown codelist — SKIPs: `X not in []` would flag
        // every row.
        lib.codelistTerms.clear();
        assertUnusable(LibraryLists.CODELIST_TERMS, ProviderNeed.Kind.LIBRARY, () -> LibraryLists
                .codelistTerms(run(dm, lib), codelists(List.of("NOSUCH"), "term", "value")));
    }


    @Test
    void theThreeLibraryConditionsAreUnusableOnEveryLibraryWalk()
    {
        Library degraded = new Library();
        degraded.unavailable = true;
        degraded.columnOrder = List.of("STUDYID");
        degraded.domainVariables = List.of(Map.of("name", "STUDYID", "role", "Identifier"));
        IDataTable dm = dm();
        // Condition 1: no provider. Condition 2: degraded and not answerable (fn not applied —
        // the provider is never asked, so the seeded answers above cannot leak through).
        for (Library lib : Arrays.asList(null, degraded))
        {
            assertUnusable(LibraryLists.GET_COLUMN_ORDER_FROM_LIBRARY, ProviderNeed.Kind.LIBRARY,
                    () -> LibraryLists.columnOrderFromLibrary(run(dm, lib), List.of()));
            assertUnusable(LibraryLists.GET_DATASET_FILTERED_VARIABLES, ProviderNeed.Kind.LIBRARY,
                    () -> LibraryLists.datasetFilteredVariables(run(dm, lib),
                            args("role", "Identifier")));
            assertUnusable(LibraryLists.CODELIST_TERMS, ProviderNeed.Kind.LIBRARY,
                    () -> LibraryLists.codelistTerms(run(dm, lib),
                            codelists(List.of("DOMAIN"), "term", "value")));
            assertUnusable(ParentModelColumnOrder.NAME, ProviderNeed.Kind.LIBRARY,
                    () -> ParentModelColumnOrder.evaluate(run(dm, lib),
                            List.of(ConstVector.of("DM"))));
        }
        assertEquals(0, degraded.asked, "a degraded Library that cannot answer is never asked");
    }

    // ============================================================ the parent walk (per row, D2)


    @Test
    void parentModelColumnOrderAnswersEachRowsParentAndSharesOneListPerParent()
    {
        // One SUPPQUAL carrying several parents: each row is checked against ITS parent's model
        // order; rows naming the same parent share one list instance (the per-row membership arm
        // folds it once per run of rows); a blank or unresolved parent answers the empty list.
        IDataTable supp = MockTable.of().col("RDOMAIN", "AE", "DM", "", "ZZ", "AE")
                .col("QNAM", "AETERM", "ARM", "X", "AETERM", "AEDECOD").name("SUPPQUAL").build();
        Library lib = new Library();
        lib.modelColumnOrder.put("AE", List.of("STUDYID", "AETERM", "AEDECOD"));
        lib.modelColumnOrder.put("DM", List.of("STUDYID", "ARM"));
        DatasetResolver.WithInventory study = inventory(
                Map.of("AE", table("AE", "AETERM", "x"), "DM", table("DM", "ARM", "x")));
        Vector answer = ParentModelColumnOrder.evaluate(run(supp, lib, null, study),
                List.of(column(supp, "RDOMAIN")));
        assertTrue(answer instanceof ComputedVector, "a per-row list, not a broadcast constant");
        assertEquals(List.of("STUDYID", "AETERM", "AEDECOD"), answer.value(0).resolved());
        assertEquals(List.of("STUDYID", "ARM"), answer.value(1).resolved());
        assertEquals(List.of(), answer.value(2).resolved(), "a blank parent");
        assertEquals(List.of(), answer.value(3).resolved(), "a parent no dataset carries");
        assertSame(answer.value(0).resolved(), answer.value(4).resolved(),
                "the same parent shares the same list instance");
    }


    @Test
    void d2_aSplitParentResolvesThroughEveryMemberOfItsFamily()
    {
        // The shape census defect D2: LB is submitted as LBCH + LBHE, no dataset is named LB.
        // The retired operation resolved the parent by member name and SKIPPED the rule; the
        // function unions the model orders of every member whose data-driven domain is LB.
        IDataTable supplb = MockTable.of().col("RDOMAIN", "LB", "LB").col("QNAM", "LBTESTCD", "LBX")
                .name("SUPPLB").build();
        IDataTable lbch = table("LBCH", "DOMAIN", "LB");
        IDataTable lbhe = table("LBHE", "DOMAIN", "LB");
        Library lib = new Library();
        lib.modelColumnOrder.put("LBCH", List.of("STUDYID", "LBTESTCD"));
        lib.modelColumnOrder.put("LBHE", List.of("STUDYID", "LBTEST"));
        Map<String, IDataTable> family = new LinkedHashMap<>();
        family.put("LBCH", lbch);
        family.put("LBHE", lbhe);
        Vector answer = ParentModelColumnOrder.evaluate(run(supplb, lib, null, inventory(family)),
                List.of(column(supplb, "RDOMAIN")));
        assertEquals(List.of("STUDYID", "LBTESTCD", "LBTEST"), answer.value(0).resolved(),
                "the union over the members, in inventory order");
        // A resolver without an inventory keeps the direct resolve (the pre-D2 reach).
        assertUnusable(ParentModelColumnOrder.NAME, ProviderNeed.Kind.LIBRARY,
                () -> ParentModelColumnOrder.evaluate(run(supplb, lib, null, NO_INVENTORY),
                        List.of(column(supplb, "RDOMAIN"))));
        lib.modelColumnOrder.put("LB", List.of("STUDYID", "LBTESTCD"));
        Vector direct = ParentModelColumnOrder.evaluate(
                run(supplb, lib, null, name -> "LB".equals(name) ? lbch : null),
                List.of(column(supplb, "RDOMAIN")));
        assertEquals(List.of("STUDYID", "LBTESTCD"), direct.value(1).resolved());
    }


    @Test
    void parentModelColumnOrderSkipsWhenNoParentResolvesOrTheLibraryHasNoProduct()
    {
        Library lib = new Library();
        lib.modelColumnOrder.put("AE", List.of("AETERM"));
        DatasetResolver.WithInventory study = inventory(Map.of("AE", table("AE", "AETERM", "x")));
        IDataTable supp = MockTable.of().col("RDOMAIN", "ZZ", "").col("QNAM", "A", "B")
                .name("SUPPZZ").build();
        assertUnusable(
                ParentModelColumnOrder.NAME, ProviderNeed.Kind.LIBRARY, () -> ParentModelColumnOrder
                        .evaluate(run(supp, lib, null, study), List.of(column(supp, "RDOMAIN"))),
                "no row resolves a parent");
        // An absent RDOMAIN column folds to blank on every row (D4) — the same SKIP the retired
        // operation gave for a table without the column (D-W4-2 keeps the verdict).
        assertUnusable(ParentModelColumnOrder.NAME, ProviderNeed.Kind.LIBRARY,
                () -> ParentModelColumnOrder.evaluate(run(supp, lib, null, study),
                        List.of(ConstVector.of(""))));
        // A resolved parent the Library cannot serve (null: no product) SKIPs the whole rule, as
        // get_model_column_order does.
        IDataTable suppae = MockTable.of().col("RDOMAIN", "AE").col("QNAM", "A").name("SUPPAE")
                .build();
        lib.modelColumnOrder.clear();
        assertUnusable(ParentModelColumnOrder.NAME, ProviderNeed.Kind.LIBRARY,
                () -> ParentModelColumnOrder.evaluate(run(suppae, lib, null, study),
                        List.of(column(suppae, "RDOMAIN"))));
    }

    // ============================================================ the DEFINE walks


    @Test
    void theDefineWalksReadTheDefineAndOnlyTheKeySetSkipsWhenEmpty()
    {
        Define define = new Define();
        define.datasetNames = List.of("dm", "Ae");
        define.columnOrder.put("DM", List.of("STUDYID", "USUBJID", "SEX"));
        define.keys.put("DM", List.of("STUDYID", "USUBJID"));
        IDataTable dm = dm();
        assertEquals(
                List.of("DM", "AE"), list(DefineLists
                        .defineDatasetNames(run(dm, null, define, NO_INVENTORY), List.of())),
                "upper-cased (FU-2)");
        assertEquals(List.of("STUDYID", "USUBJID", "SEX"), list(
                DefineLists.defineVariableNames(run(dm, null, define, NO_INVENTORY), List.of())));
        assertEquals(List.of("STUDYID", "USUBJID"), list(
                DefineLists.defineKeyVariables(run(dm, null, define, NO_INVENTORY), List.of())));
        define.columnOrder.clear();
        define.datasetNames = List.of();
        assertEquals(List.of(), list(
                DefineLists.defineVariableNames(run(dm, null, define, NO_INVENTORY), List.of())));
        assertEquals(List.of(), list(
                DefineLists.defineDatasetNames(run(dm, null, define, NO_INVENTORY), List.of())));
        // PMDA-SD1152 M4: an empty key set would collapse the uniqueness key to the constant
        // anchor and flag every record — it SKIPs.
        define.keys.clear();
        assertUnusable(DefineLists.DEFINE_KEY_VARIABLES, ProviderNeed.Kind.DEFINE, () -> DefineLists
                .defineKeyVariables(run(dm, null, define, NO_INVENTORY), List.of()));
        EvalRun noDefine = run(dm, null, null, NO_INVENTORY);
        assertUnusable(DefineLists.DEFINE_VARIABLE_NAMES, ProviderNeed.Kind.DEFINE,
                () -> DefineLists.defineVariableNames(noDefine, List.of()), "no Define-XML");
        assertUnusable(DefineLists.DEFINE_DATASET_NAMES, ProviderNeed.Kind.DEFINE,
                () -> DefineLists.defineDatasetNames(noDefine, List.of()), "no Define-XML");
        assertUnusable(DefineLists.DEFINE_KEY_VARIABLES, ProviderNeed.Kind.DEFINE,
                () -> DefineLists.defineKeyVariables(noDefine, List.of()), "no Define-XML");
    }

    // ============================================================ the inventory / schema walks


    @Test
    void theInventoryWalksEnumerateTheStudyAndAreLoudWithoutAnInventory()
    {
        IDataTable lbch = table("LBCH", "DOMAIN", "LB");
        IDataTable supp = MockTable.of().col("RDOMAIN", "LB").name("SUPPLB").build();
        DatasetResolver.WithInventory study = inventory(new LinkedHashMap<>(
                Map.of("lbch", lbch, "dm", table("DM", "DOMAIN", "DM"), "supplb", supp)));
        List<Object> names = list(
                InventoryLists.datasetNames(run(supp, null, null, study), List.of()));
        assertEquals(Set.of("LBCH", "DM", "SUPPLB"), Set.copyOf(names),
                "member names, upper-cased");
        List<Object> domains = list(
                InventoryLists.studyDomains(run(supp, null, null, study), List.of()));
        assertEquals(Set.of("LB", "DM", ""), Set.copyOf(domains),
                "J7: the DOMAIN cell, so a split LB matches RDOMAIN=LB; SUPP contributes \"\"");
        assertEquals(List.of("RDOMAIN"), list(InventoryLists
                .columnOrderFromDataset(run(supp, null, null, NO_INVENTORY), List.of())));
        // D-W4-5: a resolver that cannot enumerate the study is a wiring defect — loud, never [].
        EvalRun noInventory = run(supp, null, null, NO_INVENTORY);
        assertLoud(InventoryLists.DATASET_NAMES,
                () -> InventoryLists.datasetNames(noInventory, List.of()));
        assertLoud(InventoryLists.STUDY_DOMAINS,
                () -> InventoryLists.studyDomains(noInventory, List.of()));
        assertLoud(InventoryLists.SPLIT_SIBLING_LENGTH_MISMATCH,
                () -> InventoryLists.splitSiblingLengthMismatch(noInventory, List.of()));
    }


    @Test
    void splitSiblingLengthMismatchReportsTheDivergingVariablesOfAFamily()
    {
        IDataTable lb1 = MockTable.of().col("DOMAIN", "LB").col("LBORRES", "x").col("LBTEST", "t")
                .colMeta("LBORRES", null, 20, null).colMeta("LBTEST", null, 30, null).name("LB1")
                .build();
        IDataTable lb2 = MockTable.of().col("DOMAIN", "LB").col("LBORRES", "y").col("LBTEST", "u")
                .colMeta("LBORRES", null, 40, null).colMeta("LBTEST", null, 30, null).name("LB2")
                .build();
        assertEquals(List.of("LBORRES"),
                list(InventoryLists.splitSiblingLengthMismatch(
                        run(lb1, null, null, inventory(Map.of("LB1", lb1, "LB2", lb2))),
                        List.of())),
                "only LBORRES diverges (20 vs 40)");
        assertEquals(List.of(),
                list(InventoryLists.splitSiblingLengthMismatch(
                        run(lb1, null, null, inventory(Map.of("LB1", lb1))), List.of())),
                "a single-member family cannot diverge");
    }


    @Test
    void duplicateLabelVariablesReportsEveryColumnOfASharedLabelInColumnOrder()
    {
        IDataTable ae = MockTable.of().col("AESTDTC", "x").col("AEENDTC", "y").col("AETERM", "z")
                .col("AEDECOD", "w").col("AELLT", "v").col("AESEV", "u")
                .colMeta("AESTDTC", "Start Date/Time", 0, null)
                .colMeta("AEENDTC", "End Date/Time", 0, null)
                .colMeta("AETERM", "Reported Term", 0, null)
                .colMeta("AEDECOD", "Reported Term", 0, null)
                .colMeta("AELLT", "Reported Term", 0, null).colMeta("AESEV", "Severity", 0, null)
                .name("AE").build();
        assertEquals(List.of("AETERM", "AEDECOD", "AELLT"), list(InventoryLists
                .duplicateLabelVariables(run(ae, null, null, NO_INVENTORY), List.of())));
        IDataTable unique = MockTable.of().col("AESTDTC", "x").col("AETERM", "z")
                .colMeta("AESTDTC", "Start Date/Time", 0, null)
                .colMeta("AETERM", "Reported Term", 0, null).name("AE").build();
        assertEquals(List.of(), list(InventoryLists
                .duplicateLabelVariables(run(unique, null, null, NO_INVENTORY), List.of())));
        IDataTable unlabelled = MockTable.of().col("AAA", "x").col("BBB", "y").name("AE").build();
        assertEquals(List.of(),
                list(InventoryLists.duplicateLabelVariables(
                        run(unlabelled, null, null, NO_INVENTORY), List.of())),
                "blank / absent labels are not a duplicate bucket");
    }

    // ============================================================ minus


    @Test
    void minusIsTheOrderPreservingDifferenceOfItsListOperands()
    {
        assertEquals(List.of("A", "C"), minus(List.of("A", "B", "C"), List.of("B")));
        assertEquals(List.of(), minus(List.of(), List.of("B")), "an empty minuend");
        assertEquals(List.of("A", "B"), minus(List.of("A", "B"), List.of()));
        assertEquals(List.of("A", "B", "A"), minus(List.of("A", "B", "A", "C"), List.of("C")),
                "duplicates in the minuend are preserved");
        assertEquals(List.of("SOLO"), minus("SOLO", "OTHER"), "a scalar is its singleton");
        assertEquals(List.of(), minus("X", List.of("X")));
        assertEquals(List.of("A", "B"), minus(List.of("A", "B"), null),
                "a subtrahend binding that evaluates to nothing removes nothing");
        assertEquals(List.of("SDESIGN"),
                minus(List.of("AGEU", "SDESIGN", "ARM"), List.of("AGEU", "ARM")),
                "the SEND-0247 shape: the authored literals absent from the data");
    }


    @Test
    void minusKeepsAMissingElementsIdentityAndComparesATupleByItsComponents()
    {
        // D34 #5-2: subtracting a present "." does not remove the missing element, and the
        // missing survives as itself, not as its "." rendering.
        assertEquals(List.of("X", MissingValue.MIS),
                minus(List.of("X", MissingValue.MIS), List.of(".")));
        assertEquals(List.of("."),
                minus(List.of(".", MissingValue.MIS), List.of(MissingValue.MIS)));
        assertEquals(List.of("X"),
                minus(List.of("X", MissingValue.MIS_A), List.of(MissingValue.MIS_A)),
                "the same missing is removed");
        // I6 (PLAN-no-null-list-elements residual, fixed in W4): a tuple element compares by its
        // components — the rendering "[A, B]" is NOT the tuple ["A", "B"].
        List<Object> ab = List.of("A", "B");
        List<Object> cd = List.of("C", "D");
        assertEquals(List.of(List.of("C", "D")),
                minus(List.of(ab, cd), List.of(List.of("A", "B"))));
        assertEquals(List.of(List.of("A", "B"), List.of("C", "D")),
                minus(List.of(ab, cd), List.of("[A, B]")),
                "a present string equal to the tuple's rendering removes nothing");
        assertEquals(List.of(List.of("A", MissingValue.MIS)),
                minus(List.of(List.of("A", MissingValue.MIS)), List.of(List.of("A", "."))),
                "a missing component keeps its identity inside the tuple");
    }


    @Test
    void minusRefusesAnArrayLoudlyAndAPerRowOperandAtLoad() throws Exception
    {
        EvalRun run = run(dm(), null, null, NO_INVENTORY);
        IllegalStateException array = assertThrows(IllegalStateException.class,
                () -> Minus.evaluate(run, List.of(ConstVector.of(new String[]
                {
                        "A", "B"
                }), ConstVector.of(List.of("B")))));
        assertEquals("an array is not a list value (NNL §1)", array.getMessage());
        // A list binding computed per row over a dataset-level list (upper($list)) holds the same
        // list on every row — its dataset-level value is what minus reads (the SEND-0012 chain).
        Vector sameEveryRow = new ComputedVector(3, DataValueType.STRING, _ -> List.of("A", "B"));
        assertEquals(List.of("A"),
                list(Minus.evaluate(run, List.of(sameEveryRow, ConstVector.of(List.of("B"))))));
        // A genuinely per-row binding is the retired operation's runtime refusal, kept at load.
        Rule perRow = rule("not empty($m)", List.of("$m"), "$u", "upper(USUBJID)", "$s",
                "distinct(DOMAIN)", "$m", "minus($u, subtract=$s)");
        assertNotNull(perRow.getLoadError());
        assertTrue(
                perRow.getLoadError().contains("OPERATION_READS_CURSOR_BINDING")
                        && perRow.getLoadError().contains("the list function minus(…)"),
                perRow.getLoadError());
    }

    // ============================================================ classification (D-W4-6)


    @Test
    void theStudyLevelListFunctionsDoNotReadThePrimaryDatasetAndMinusFollowsItsOperands()
        throws Exception
    {
        // The study-level arm is reached through Output_Variables (a `$` output is walked) and
        // through a binary Check; a Check that is itself a call (contains_all, empty, …) reads the
        // primary dataset by the classifier's standing rule, before and after wave 4.
        Rule outputs = rule("ds_exists(\"DM\")", List.of("$missing_datasets"), "$study_datasets",
                "dataset_names()", "$define_datasets", "define_dataset_names()",
                "$missing_datasets", "minus($define_datasets, subtract=$study_datasets)");
        assertNull(outputs.getLoadError(), outputs.getLoadError());
        assertFalse(StudyRuleClassifier.readsPrimaryDataset(outputs),
                "dataset_names / define_dataset_names / minus over them: the study inventory");
        Rule overAColumn = rule("ds_exists(\"DM\")", List.of("$missing"), "$present",
                "distinct(TSPARMCD)", "$missing",
                "minus([\"AGEU\", \"SDESIGN\"], subtract=$present)");
        assertNull(overAColumn.getLoadError(), overAColumn.getLoadError());
        assertTrue(StudyRuleClassifier.readsPrimaryDataset(overAColumn),
                "minus is study-safe only when every operand is");
        Rule domains = rule("SRCDOM not in $sdtm_domains", List.of("SRCDOM"), "$sdtm_domains",
                "standard_domains()");
        assertTrue(StudyRuleClassifier.readsPrimaryDataset(domains), "SRCDOM is a column read");
        Rule sd0061 = rule("not contains_all($study_datasets, $define_datasets)",
                List.of("$missing_datasets"), "$study_datasets", "dataset_names()",
                "$define_datasets", "define_dataset_names()", "$missing_datasets",
                "minus($define_datasets, subtract=$study_datasets)");
        assertTrue(StudyRuleClassifier.readsPrimaryDataset(sd0061),
                "a call Check reads the primary dataset — unchanged by the port");
    }


    @Test
    void aMinusBindingDerivesAsAnOutputWhileTheOtherListBindingsAreBulk() throws Exception
    {
        // D-W4-13: the deriver's one list-valued exception — minus's result IS the finding.
        Rule rule = rule("record_count() > 0", List.of(), "$terms",
                "codelist_terms(codelists=[\"DOMAIN\"], level=\"term\", returntype=\"value\")",
                "$present", "distinct(DOMAIN)", "$missing",
                "minus([\"AE\", \"DM\"], subtract=$present)");
        assertNull(rule.getLoadError(), rule.getLoadError());
        List<String> derived = OutputVariableDeriver.derive(rule);
        assertTrue(derived.contains("$missing"), derived.toString());
        assertFalse(derived.contains("$terms"), derived.toString());
    }


    @Test
    void aDefineWalkIsACallNotAMetadataOperandDespiteItsNamePrefix()
    {
        // DomainScan / MetadataExprScan key the define_variable_* / define_dataset_* OPERANDS by
        // prefix; a registry CALL takes the call arm — an aggregate with no operands is a
        // dataset-level fact, and its provider need is the descriptor's DEFINE capability.
        Expr call = CheckExpressionParser.parse("define_variable_names()");
        assertEquals(Domain.DATASET, DomainScan.infer(call, BindingDomains.NONE));
        assertTrue(ProviderNeeds.ofExpr(call).define());
        assertFalse(ProviderNeeds.ofExpr(call).library());
        assertEquals(Domain.ROW,
                DomainScan.infer(
                        CheckExpressionParser
                                .parse("QNAM in get_parent_model_column_order(RDOMAIN)"),
                        BindingDomains.NONE),
                "the parent column carries the ROW demand (D-W4-2)");
        assertEquals(Domain.DATASET, DomainScan.infer(CheckExpressionParser.parse(
                "not contains_all($dataset_vars, get_model_filtered_variables(model_class=\"Findings\"))"),
                BindingDomains.NONE));
    }


    /**
     * Combined review of runbook W2–W8, W4 M2 (root cause): {@code upper} / {@code lower} over a
     * dataset-level operand is ONE constant — the fold runs once and answers a {@link ConstVector}
     * — so {@code $dataset_variables_upper = upper($dataset_variables)} stays a dataset-level list
     * on a zero-row dataset too, and {@code minus} over it answers the real set difference. Pre-fix
     * the fold answered a per-row {@link ComputedVector} with no row 0 (the assertInstanceOf is
     * red), and minus read an empty subtrahend ([STUDYID, DOMAIN, USUBJID]).
     */
    @Test
    void upperOverADatasetLevelListIsOneConstantAndMinusOverItHoldsOnAZeroRowTable()
    {
        IDataTable empty = MockTable.of().col("STUDYID").col("DOMAIN").name("DM").build();
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(empty).build());
        assertEquals(0, run.rowCount(), "the fixture is a zero-row dataset");
        ConstVector folded = assertInstanceOf(ConstVector.class,
                caseFold("upper").apply(run, List.of(ConstVector.of(List.of("studyid", "Domain")))),
                "upper over a broadcast list is one broadcast constant");
        assertEquals(List.of("STUDYID", "DOMAIN"), folded.value());
        Vector answer = Minus.evaluate(run,
                List.of(ConstVector.of(List.of("STUDYID", "DOMAIN", "USUBJID")), folded));
        assertEquals(List.of("USUBJID"), assertInstanceOf(ConstVector.class, answer).value(),
                "the set difference, not the whole minuend");
        // The scalar and missing arms of the same fast path: a string constant folds once; a
        // missing constant is handed through as the very same vector (identity kept, D36).
        assertEquals("ab", assertInstanceOf(ConstVector.class,
                caseFold("lower").apply(run, List.of(ConstVector.of("AB")))).value());
        ConstVector missing = ConstVector.of(null);
        assertSame(missing, caseFold("upper").apply(run, List.of(missing)));
    }


    private static net.cumba.corej.core.expr.eval.EvalFunction caseFold(String name)
    {
        return Objects.requireNonNull(
                Objects.requireNonNull(FunctionRegistry.descriptor(name), name).fn(), name);
    }

    // ============================================================ the compile seam (D-W4-3)


    @Test
    void theSeamHoldsTheStaticParametersToLiteralsAndTheirVocabularies() throws Exception
    {
        assertLoadError("codelist_terms(codelists=[DOMAIN], level=\"term\", returntype=\"value\")",
                "takes a list of string literals", "\"DOMAIN\"");
        assertLoadError("codelist_terms(codelists=\"DOMAIN\", level=\"term\")",
                "takes a static list of string literals");
        assertLoadError("codelist_terms(codelists=[\"DOMAIN\"], level=DOMAIN)", "argument 'level'",
                "static string literal");
        assertLoadError("codelist_terms(codelists=[\"DOMAIN\"], level=\"bogus\")",
                "unknown `level` value `bogus`", "codelist", "term");
        assertLoadError(
                "codelist_terms(codelists=[\"DOMAIN\"], level=\"term\", returntype=\"text\")",
                "unknown `returntype` value `text`", "pref_term");
        assertLoadError("valid_codelist_dates(ct_package_types=[SDTM])",
                "takes a list of string literals", "\"SDTM\"");
        assertLoadError("minus(value=[AGEU, SDESIGN], subtract=$p)",
                "takes a list of string literals", "\"AGEU\"");
        assertLoadError("get_model_filtered_variables(key_name=\"valueList\", key_value=\"DM\")",
                "`key_name` `valueList`", "can never match");
        for (String key : LibraryVariableAttributes.KEYS)
        {
            assertLoadError("get_dataset_filtered_variables(key_name=\"Core\")", key);
        }
        assertLoadError("get_model_filtered_variables(model_class=\"EVENT\")",
                "unknown `model_class` value `EVENT`", "EVENTS");
        assertLoadError("get_model_filtered_variables(model_class=\"special-purpose datasets\")",
                "unknown `model_class`");
        assertLoadError("get_column_order_from_library(key_name=\"role\")", "key_name");
        assertLoadError("get_model_column_order(model_class=\"EVENTS\")", "model_class");
        assertLoadError("required_variables(USUBJID)", "required_variables");
        assertLoadError("minus($p)", "subtract");
        assertLoadError("get_parent_model_column_order(\"RDOMAIN\")",
                "takes a column reference, not the literal RDOMAIN");
        assertLoadError("get_parent_model_column_order()", "get_parent_model_column_order");
    }


    @Test
    void theSeamAcceptsEverySpellingTheCorpusAuthors() throws Exception
    {
        assertLoads("codelist_terms(codelists=[\"DOMAIN\"], level=\"term\", returntype=\"value\")");
        assertLoads("codelist_terms(codelists=[\"DOMAIN\"], level=\"term\", returntype=\"code\")");
        assertLoads("valid_codelist_dates(ct_package_types=[\"SDTM\"])");
        assertLoads("valid_codelist_dates()");
        assertLoads("get_model_filtered_variables(model_class=\" Findings About \")");
        assertLoads("get_model_filtered_variables(model_class=\"EVENTS\", key_name=\"role\","
                + " key_value=\"Topic\")");
        assertLoads("get_model_filtered_variables(key_name=\"core\", key_value=\"Perm\")");
        for (String key : LibraryVariableAttributes.KEYS)
        {
            assertLoads("get_model_filtered_variables(key_name=\"" + key + "\")");
            assertLoads("get_dataset_filtered_variables(key_name=\"" + key + "\")");
        }
        assertLoads("get_parent_model_column_order(RDOMAIN)");
        assertLoads("minus([\"AGEU\", \"SDESIGN\"], subtract=$p)", "$p", "distinct(TSPARMCD)");
        assertLoads("minus(subtract=$p, value=[\"AGEU\"])", "$p", "distinct(TSPARMCD)");
        for (String zeroArg : List.of("required_variables", "expected_variables",
                "get_column_order_from_library", "get_model_column_order", "variable_names",
                "standard_domains", "get_dataset_filtered_variables", "natural_key_variables",
                "get_model_filtered_variables", "define_variable_names", "define_dataset_names",
                "define_key_variables", "get_column_order_from_dataset", "dataset_names",
                "study_domains", "split_sibling_length_mismatch", "duplicate_label_variables"))
        {
            assertLoads(zeroArg + "()");
        }
    }

    // ============================================================ rule-level runs


    @Test
    void theSd0061ShapeReportsTheMissingDatasetsThroughTheMinusChain() throws Exception
    {
        Rule rule = rule("not contains_all($study_datasets, $define_datasets)",
                List.of("$missing_datasets"), "$study_datasets", "dataset_names()",
                "$define_datasets", "define_dataset_names()", "$missing_datasets",
                "minus($define_datasets, subtract=$study_datasets)");
        Define define = new Define();
        define.datasetNames = List.of("DM", "AE");
        IDataTable dm = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, dm, inventory(Map.of("DM", dm)),
                null, null, null, define);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size());
        assertEquals("[AE]", result.getViolations().get(0).getValues().get("$missing_datasets"),
                "the report renders the difference exactly as the operation's list rendered");
        RuleExecutionResult complete = RuleRunnerCalls.execute(rule, dm,
                inventory(Map.of("DM", dm, "AE", table("AE", "DOMAIN", "AE"))), null, null, null,
                define);
        assertTrue(complete.getViolations().isEmpty());
        RuleExecutionResult noDefine = RuleRunnerCalls.execute(rule, dm,
                inventory(Map.of("DM", dm)), null, null, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, noDefine.getStatus());
    }


    @Test
    void theSend0012ChainReportsTheMissingRequiredVariables() throws Exception
    {
        Rule rule = rule("not contains_all($dataset_variables_upper, $required_variables_upper)",
                List.of("$missing_required_variables"), "$required_variables",
                "required_variables()", "$required_variables_upper", "upper($required_variables)",
                "$dataset_variables", "get_column_order_from_dataset()", "$dataset_variables_upper",
                "upper($dataset_variables)", "$required_variables_absent_upper",
                "minus($required_variables, subtract=$dataset_variables_upper)",
                "$missing_required_variables",
                "minus($required_variables_absent_upper, subtract=$dataset_variables)");
        Library lib = new Library();
        lib.requiredVariables = List.of("STUDYID", "USUBJID", "SEX");
        IDataTable dm = MockTable.of().col("STUDYID", "S").col("USUBJID", "1").name("DM").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, dm, NO_INVENTORY, null, lib);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size());
        assertEquals("[SEX]",
                result.getViolations().get(0).getValues().get("$missing_required_variables"));
        RuleExecutionResult noLibrary = RuleRunnerCalls.execute(rule, dm, NO_INVENTORY, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, noLibrary.getStatus());
        assertEquals("Rule skipped — no Library access", noLibrary.getStatusMessage());
    }


    @Test
    void theCg0314ShapeFiresOnASplitParentAndSkipsWhenTheLibraryAnswersNothing() throws Exception
    {
        Rule rule = rule("QNAM in $model_variables", List.of("QNAM", "$model_variables"),
                "$model_variables", "get_parent_model_column_order(RDOMAIN)");
        IDataTable supplb = MockTable.of().col("RDOMAIN", "LB", "LB").col("QNAM", "LBTESTCD", "LBX")
                .name("SUPPLB").build();
        IDataTable lbch = table("LBCH", "DOMAIN", "LB");
        IDataTable lbhe = table("LBHE", "DOMAIN", "LB");
        Library lib = new Library();
        lib.modelColumnOrder.put("LBCH", List.of("STUDYID", "LBTESTCD"));
        lib.modelColumnOrder.put("LBHE", List.of("STUDYID", "LBTEST"));
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, supplb,
                inventory(Map.of("LBCH", lbch, "LBHE", lbhe)), null, lib);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "LBTESTCD is a model variable of LB");
        assertEquals("LBTESTCD", result.getViolations().get(0).getValues().get("QNAM"));
        lib.modelColumnOrder.clear();
        RuleExecutionResult skipped = RuleRunnerCalls.execute(rule, supplb,
                inventory(Map.of("LBCH", lbch, "LBHE", lbhe)), null, lib);
        assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus());
        assertEquals("Rule skipped — library returned no data for get_parent_model_column_order",
                skipped.getStatusMessage());
    }


    @Test
    void anEmptyCodelistUnionAndAnEmptyDefineKeySetSkipTheRuleNamingTheFunction() throws Exception
    {
        Rule terms = rule("DOMAIN not in $codes", List.of("DOMAIN"), "$codes",
                "codelist_terms(codelists=[\"DOMAIN\"], level=\"term\", returntype=\"value\")");
        Library lib = new Library();
        RuleExecutionResult noTerms = RuleRunnerCalls.execute(terms, dm(), NO_INVENTORY, null, lib);
        assertEquals(RuleExecutionStatus.SKIPPED, noTerms.getStatus());
        assertEquals("Rule skipped — library returned no data for codelist_terms",
                noTerms.getStatusMessage());
        lib.codelistTerms.put("DOMAIN", List.of("AE", "LB"));
        RuleExecutionResult fires = RuleRunnerCalls.execute(terms, dm(), NO_INVENTORY, null, lib);
        assertEquals(RuleExecutionStatus.EXECUTED, fires.getStatus(), fires.getStatusMessage());
        assertEquals(1, fires.getViolations().size(), "DM is not in [AE, LB]");
        Rule keys = rule("not is_unique_set([STUDYID, $define_key_variables])", List.of("STUDYID"),
                "$define_key_variables", "define_key_variables()");
        Define define = new Define();
        RuleExecutionResult noKeys = RuleRunnerCalls.execute(keys, dm(), NO_INVENTORY, null, null,
                null, define);
        assertEquals(RuleExecutionStatus.SKIPPED, noKeys.getStatus());
        assertEquals("Rule skipped — Define-XML returned no data for define_key_variables",
                noKeys.getStatusMessage());
    }

    // ============================================================ helpers


    private static FunctionDescriptor descriptor(String name)
    {
        return Objects.requireNonNull(FunctionRegistry.descriptor(name), name);
    }


    private static IDataTable dm()
    {
        return MockTable.of().col("STUDYID", "S").col("DOMAIN", "DM").col("USUBJID", "1").name("DM")
                .build();
    }


    private static IDataTable table(String name, String column, String value)
    {
        return MockTable.of().col(column, value).name(name).build();
    }


    private static EvalRun run(IDataTable table, @Nullable MetadataProvider library)
    {
        return run(table, library, null, NO_INVENTORY);
    }


    private static EvalRun run(IDataTable table, @Nullable MetadataProvider library,
            @Nullable MetadataProvider define, DatasetResolver resolver)
    {
        return EvalRun.fullRange(EvaluationContext.builder().table(table).ruleId("W4-FN")
                .libraryProvider(library).defineProvider(define).datasetResolver(resolver).build());
    }


    private static Vector column(IDataTable table, String name)
    {
        int idx = table.getMetaData().getColumnIndex(name);
        return new net.cumba.corej.core.expr.eval.ColumnVector(name, table.getColumn(idx),
                table.getMetaData().getColumn(idx).getType());
    }


    private static List<@Nullable Vector> args(@Nullable String... statics)
    {
        List<@Nullable Vector> out = new ArrayList<>();
        for (String s : statics)
        {
            out.add(s == null ? null : ConstVector.of(s));
        }
        return out;
    }


    private static List<@Nullable Vector> codelists(List<String> names, @Nullable String level,
            @Nullable String returntype)
    {
        return Arrays.asList(ConstVector.of(names), level == null ? null : ConstVector.of(level),
                returntype == null ? null : ConstVector.of(returntype));
    }


    @SuppressWarnings("unchecked")
    private static List<Object> list(Vector v)
    {
        assertTrue(v instanceof ConstVector, "a dataset-level list is one broadcast constant");
        return (List<Object>) Objects.requireNonNull(v.value(0).resolved());
    }


    private static List<Object> minus(@Nullable Object minuend, @Nullable Object subtrahend)
    {
        return list(
                Minus.evaluate(EvalRun.fullRange(EvaluationContext.builder().table(dm()).build()),
                        Arrays.asList(ConstVector.of(minuend), ConstVector.of(subtrahend))));
    }


    private static void assertLoud(String function, Runnable body)
    {
        IllegalStateException loud = assertThrows(IllegalStateException.class, body::run, function);
        assertTrue(loud.getMessage().startsWith(function + " needs a dataset inventory"),
                loud.getMessage());
        assertTrue(loud.getMessage().contains("WithInventory"), loud.getMessage());
    }


    private static void assertUnusable(String function, ProviderNeed.Kind kind, Runnable body)
    {
        assertUnusable(function, kind, body, function);
    }


    private static void assertUnusable(String function, ProviderNeed.Kind kind, Runnable body,
            String message)
    {
        UnusableProviderAnswerException ex = assertThrows(UnusableProviderAnswerException.class,
                body::run, message);
        assertEquals(function, ex.function(), message);
        assertEquals(kind, ex.kind(), message);
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> tables)
    {
        Map<String, IDataTable> upper = new LinkedHashMap<>();
        tables.forEach((k, v) -> upper.put(k.toUpperCase(Locale.ROOT), v));
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : upper.get(name.toUpperCase(Locale.ROOT));
            }


            @Override
            public Set<String> availableDatasets()
            {
                return upper.keySet();
            }
        };
    }


    private static Rule rule(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "W4-LIST"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        if (!declared.isEmpty())
        {
            rule.put("Bindings", declared);
        }
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", outputs));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        return Objects.requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
    }


    private static void assertLoadError(String binding, String... fragments) throws Exception
    {
        Rule rule = binding.contains("$p")
                ? rule("not empty($v)", List.of("$v"), "$p", "distinct(TSPARMCD)", "$v", binding)
                : rule("not empty($v)", List.of("$v"), "$v", binding);
        String error = rule.getLoadError();
        assertNotNull(error, binding + " must be a load error");
        for (String fragment : fragments)
        {
            assertTrue(error.contains(fragment),
                    binding + " → " + error + " // missing " + fragment);
        }
    }


    private static void assertLoads(String binding, String... more) throws Exception
    {
        List<String> bindings = new ArrayList<>(List.of(more));
        bindings.add("$v");
        bindings.add(binding);
        Rule rule = rule("not empty($v)", List.of("$v"), bindings.toArray(new String[0]));
        assertNull(rule.getLoadError(), binding + " → " + rule.getLoadError());
    }

    // ------------------------------------------------------------ the fakes

    /** A Library answering from fields; every unset field is the interface's empty default. */
    private static class Library implements MetadataProvider
    {

        List<String> requiredVariables = List.of();

        List<String> expectedVariables = List.of();

        @Nullable
        List<String> columnOrder = List.of();

        final Map<String, List<String>> modelColumnOrder = new LinkedHashMap<>();

        final Map<String, List<String>> codelistTerms = new LinkedHashMap<>();

        final Map<String, ICodeListFake> codelists = new LinkedHashMap<>();

        List<Map<String, String>> domainVariables = List.of();

        List<Map<String, String>> modelVariables = List.of();

        @Nullable
        List<Map<String, String>> standardModelVariablesDetailed;

        @Nullable
        List<Map<String, String>> standardModelVariablesForClass;

        List<Map<String, String>> modelVariablesForClass = List.of();

        @Nullable
        List<String> standardVariableNames;

        @Nullable
        List<String> standardDatasetNames;

        List<String> publishedCtPackages = List.of();

        @Nullable
        String standard = "sdtmig";

        boolean unavailable;

        int asked;

        @Nullable
        String lastModelClassAsked;

        @Override
        public boolean isLibraryUnavailable()
        {
            return unavailable;
        }


        @Override
        public List<String> getRequiredVariables(String d)
        {
            asked++;
            return requiredVariables;
        }


        @Override
        public List<String> getExpectedVariables(String d)
        {
            asked++;
            return expectedVariables;
        }


        @Override
        public @Nullable List<String> getColumnOrder(String d)
        {
            asked++;
            return columnOrder;
        }


        @Override
        public @Nullable List<String> getStandardModelVariables(IDataTable t, DatasetResolver r)
        {
            asked++;
            return modelColumnOrder.get(t.getMetaData().getName());
        }


        @Override
        public boolean isDomainCustom(String d)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String c)
        {
            asked++;
            return codelistTerms.getOrDefault(c, List.of());
        }


        @Override
        public Optional<net.cumba.datatable.metadata.ICodeList> getCodelist(String name)
        {
            asked++;
            return Optional.ofNullable(codelists.get(name));
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String d)
        {
            asked++;
            return domainVariables;
        }


        @Override
        public List<Map<String, String>> getModelVariables(String d)
        {
            asked++;
            return modelVariables;
        }


        @Override
        public @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable t,
                DatasetResolver r)
        {
            asked++;
            return standardModelVariablesDetailed;
        }


        @Override
        public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable t,
                DatasetResolver r, String aModelClass)
        {
            asked++;
            lastModelClassAsked = aModelClass;
            return standardModelVariablesForClass;
        }


        @Override
        public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
        {
            asked++;
            lastModelClassAsked = aModelClass;
            return modelVariablesForClass;
        }


        @Override
        public @Nullable List<String> getStandardVariableNames()
        {
            asked++;
            return standardVariableNames;
        }


        @Override
        public @Nullable List<String> getStandardDatasetNames()
        {
            asked++;
            return standardDatasetNames;
        }


        @Override
        public List<String> getPublishedCtPackages()
        {
            asked++;
            return publishedCtPackages;
        }


        @Override
        public Map<String, String> getDatasetMetadata(String d)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String cl)
        {
            return Optional.empty();
        }


        @Override
        public @Nullable String getStandard()
        {
            return standard;
        }
    }


    /** A structure-keyed (ADaM) Library: {@code supportsStructureKeyedVariables} is true. */
    private static final class StructureLibrary extends Library
    {

        final Map<String, List<String>> published = new LinkedHashMap<>();

        @Override
        public boolean supportsStructureKeyedVariables()
        {
            return true;
        }


        @Override
        public @Nullable List<String> getRequiredVariablesForStructure(String token,
                List<String> subclasses)
        {
            return published.get(token);
        }


        @Override
        public @Nullable List<String> getExpectedVariablesForStructure(String token,
                List<String> subclasses)
        {
            return published.get(token);
        }


        @Override
        public List<String> declaredStructureKeyedProducts()
        {
            return List.of("adamig-1-3");
        }
    }


    /** A sponsor Define-XML answering from fields. */
    private static final class Define implements MetadataProvider
    {

        List<String> datasetNames = List.of();

        final Map<String, List<String>> columnOrder = new LinkedHashMap<>();

        final Map<String, List<String>> keys = new LinkedHashMap<>();

        @Override
        public List<String> getDatasetNames()
        {
            return datasetNames;
        }


        @Override
        public List<String> getColumnOrder(String d)
        {
            return columnOrder.getOrDefault(d, List.of());
        }


        @Override
        public List<String> getKeyVariables(String d)
        {
            return keys.getOrDefault(d, List.of());
        }


        @Override
        public List<String> getRequiredVariables(String d)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String d)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String d)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String c)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String d)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String d)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String cl)
        {
            return Optional.empty();
        }


        @Override
        public @Nullable String getStandard()
        {
            return "sdtmig";
        }
    }


    private record Entry(String code, String decode,
            String conceptId) implements net.cumba.datatable.metadata.ICodelistEntry
    {

        @Override
        public String getCodeValue()
        {
            return code;
        }


        @Override
        public String getDecodeValue()
        {
            return decode;
        }


        @Override
        public @Nullable String getConceptId()
        {
            return conceptId;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Set.of();
        }


        @Override
        public Optional<Object> getMetaValue(String key)
        {
            return Optional.empty();
        }
    }


    private record Codelist(String name,
            List<net.cumba.datatable.metadata.ICodelistEntry> entries) implements ICodeListFake
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public DataValueType getValueType()
        {
            return DataValueType.STRING;
        }


        @Override
        public List<net.cumba.datatable.metadata.ICodelistEntry> getEntries()
        {
            return entries;
        }


        @Override
        public @Nullable Boolean isExtensible()
        {
            return null;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Set.of();
        }


        @Override
        public Optional<Object> getMetaValue(String key)
        {
            return Optional.empty();
        }
    }


    private interface ICodeListFake extends net.cumba.datatable.metadata.ICodeList
    {
    }

}
