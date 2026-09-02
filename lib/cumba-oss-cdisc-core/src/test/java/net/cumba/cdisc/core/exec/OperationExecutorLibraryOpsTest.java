package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OperationExecutor} library-dependent operations with a stub
 * {@link MetadataProvider} attached. Covers the {@link OperationExecutor#executeOne dispatch} arms
 * for {@code GET_COLUMN_ORDER_FROM_LIBRARY}, {@code DOMAIN_IS_CUSTOM}, {@code CODELIST_TERMS},
 * {@code DATASET_CLASS_FROM_LIBRARY}, {@code VALID_CODELIST_DATES},
 * {@code GET_DATASET_FILTERED_VARIABLES}, {@code GET_MODEL_FILTERED_VARIABLES} and
 * {@code GET_MODEL_COLUMN_ORDER} branches that the existing tests don't reach.
 */
// Test fixture / helper exposing LinkedHashMap/LinkedHashSet for ordered iteration.
@SuppressWarnings("NonApiType")
class OperationExecutorLibraryOpsTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    @Test
    void getColumnOrderFromLibrary_returnsListFromProvider()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01").name("DM").build();
        Provider p = new Provider();
        p.columnOrder = List.of("STUDYID", "USUBJID", "DOMAIN");

        Operation op = makeOp("$order", "get_column_order_from_library");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("STUDYID", "USUBJID", "DOMAIN"), vars.get("$order"));
    }


    @Test
    void getColumnOrderFromLibrary_emptyList_returnsLibraryNotAvailable()
    {
        // J10 parity fix: a domain absent from the library variable model (e.g. DI under
        // SDTMIG 3-4) resolves to an empty column order. It must translate to
        // LIBRARY_NOT_AVAILABLE so the rule SKIPS, instead of an empty list that defeats the
        // rule's own `not empty($column_order_from_library)` guard.
        IDataTable table = MockTable.of().col("X", "1").name("DI").build();
        Provider p = new Provider();
        p.columnOrder = List.of();

        Operation op = makeOp("$order", "get_column_order_from_library");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$order");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void getColumnOrderFromLibrary_nullList_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DI").build();
        Provider p = new Provider();
        p.columnOrder = null;

        Operation op = makeOp("$order", "get_column_order_from_library");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$order");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void domainIsCustom_returnsBooleanFromProvider()
    {
        IDataTable table = MockTable.of().col("X", "1").name("CUSTOM").build();
        Provider p = new Provider();
        p.domainCustom = true;

        Operation op = makeOp("$custom", "domain_is_custom");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(true, vars.get("$custom"));
    }


    @Test
    void codelistTerms_returnsListFromProvider()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();
        Provider p = new Provider();
        p.codelistTerms = List.of("M", "F", "U");

        Operation op = makeOp("$terms", "codelist_terms");
        op.setName("C66731");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("M", "F", "U"), vars.get("$terms"));
    }


    @Test
    void getCodelistAttributes_returnsListFromProvider()
    {
        // get_codelist_attributes resolves a CT package from the row's target (name) + version
        // columns and the standard, then extracts the requested ct_attribute. The row carries
        // TSVCDREF=CDISC + TSVCDVER=2024-09-27 → package sdtmct-2024-09-27 (sdtmig standard).
        IDataTable table = MockTable.of().col("TSVCDREF", "CDISC").col("TSVCDVER", "2024-09-27")
                .name("TS").build();
        Provider p = new Provider();
        p.standard = "sdtmig";
        p.codelistAttribute = List.of("A", "B");

        Operation op = makeOp("$attrs", "get_codelist_attributes");
        op.setName("TSVCDREF");
        op.setVersion("TSVCDVER");
        op.setCtAttribute("Term CCODE");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("A", "B"), vars.get("$attrs"));
    }


    @Test
    void datasetClassFromLibrary_returnsClassNameOnDatasetMetadata()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        Provider p = new Provider();
        p.datasetMetadata = Map.of("className", "EVENTS");

        Operation op = makeOp("$class", "dataset_class_from_library");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals("EVENTS", vars.get("$class"));
    }


    @Test
    void datasetClassFromLibrary_returnsNullIfNoMetadata()
    {
        IDataTable table = MockTable.of().col("X", "1").name("XYZ").build();
        Provider p = new Provider();
        p.datasetMetadata = null;

        Operation op = makeOp("$class", "dataset_class_from_library");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        // Provider returned null map → null className → result not stored
        assertFalse(vars.containsKey("$class"));
    }


    @Test
    void validCodelistDates_filtersByPackageType()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();
        Provider p = new Provider();
        p.standard = "sdtmig";
        p.publishedCtPackages = List.of("sdtmct-2024-09-26", "sdtmct-2024-12-13",
                "adamct-2024-09-26", // wrong type, filtered out
                "unknown-pkg" // not parseable (still has dash, but type "unknown")
        );

        Operation op = makeOp("$dates", "valid_codelist_dates");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);

        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) vars.get("$dates");
        assertNotNull(dates);
        // Should contain only sdtmct dates (sorted).
        assertEquals(List.of("2024-09-26", "2024-12-13"), dates);
    }


    @Test
    void validCodelistDates_emptyPackages_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();
        Provider p = new Provider();
        p.publishedCtPackages = List.of();
        p.standard = "sdtmig";

        Operation op = makeOp("$dates", "valid_codelist_dates");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) vars.get("$dates");
        assertNotNull(dates);
        assertTrue(dates.isEmpty());
    }


    @Test
    void validCodelistDates_withCtPackageTypesOnOp()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();
        Provider p = new Provider();
        p.publishedCtPackages = List.of("sdtmct-2024-09-26", "adamct-2024-10-01");
        // standard intentionally null — op overrides it.

        Operation op = makeOp("$dates", "valid_codelist_dates");
        op.setCtPackageTypes(List.of("ADAM"));
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) vars.get("$dates");
        assertEquals(List.of("2024-10-01"), dates);
    }


    @Test
    void validCodelistDates_unknownStandard_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();
        Provider p = new Provider();
        p.publishedCtPackages = List.of("sdtmct-2024-09-26");
        p.standard = "unknownstandard"; // → applicable set is empty

        Operation op = makeOp("$dates", "valid_codelist_dates");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) vars.get("$dates");
        assertNotNull(dates);
        assertTrue(dates.isEmpty());
    }


    @Test
    void validCodelistDates_nullStandard_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").name("DM").build();
        Provider p = new Provider();
        p.publishedCtPackages = List.of("sdtmct-2024-09-26");
        // standard null & no op.ctPackageTypes → applicable = empty

        Operation op = makeOp("$dates", "valid_codelist_dates");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) vars.get("$dates");
        assertNotNull(dates);
        assertTrue(dates.isEmpty());
    }


    @Test
    void getDatasetFilteredVariables_legacyPath_filtersByKey()
    {
        IDataTable table = MockTable.of().col("STUDYID", "S").col("USUBJID", "X").col("AESEQ", "1")
                .name("AE").build();
        Provider p = new Provider();
        // Return null from the new resolver → legacy fallback path.
        p.standardModelVariablesDetailed = null;
        p.domainVariables = List.of(Map.of("name", "USUBJID", "role", "Identifier"),
                Map.of("name", "AESEQ", "role", "Identifier"),
                Map.of("name", "STUDYID", "role", "Other"));

        Operation op = makeOp("$ids", "get_dataset_filtered_variables");
        op.setKeyName("role");
        op.setKeyValue("Identifier");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) vars.get("$ids");
        assertNotNull(ids);
        assertTrue(ids.contains("USUBJID"));
        assertTrue(ids.contains("AESEQ"));
        assertFalse(ids.contains("STUDYID"));
    }


    @Test
    void getDatasetFilteredVariables_emptySource_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        Provider p = new Provider();
        p.standardModelVariablesDetailed = null;
        p.domainVariables = List.of();

        Operation op = makeOp("$x", "get_dataset_filtered_variables");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$x");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }


    @Test
    void naturalKeyVariables_legacyPath_filtersByNaturalKeyRoles()
    {
        // FINDINGS-style dataset. Present columns span every natural-key role plus an Identifier
        // and a Topic (excluded) and one wildcard variable resolved through the `--` prefix.
        IDataTable table = MockTable.of().col("STUDYID", "S").col("USUBJID", "X")
                .col("LBTESTCD", "GLUC").col("VISITNUM", "1").col("LBSPEC", "BLOOD")
                .col("LBMETHOD", "M").col("LBSCAT", "C").name("LB").build();
        Provider p = new Provider();
        // Return null from the resolver → legacy fallback path (getDomainVariables).
        p.standardModelVariablesDetailed = null;
        p.domainVariables = List.of(Map.of("name", "USUBJID", "role", "Identifier"),
                Map.of("name", "LBTESTCD", "role", "Topic"),
                Map.of("name", "VISITNUM", "role", "Timing"),
                Map.of("name", "LBSPEC", "role", "Grouping Qualifier"),
                Map.of("name", "LBORRES", "role", "Result Qualifier"),
                Map.of("name", "LBMETHOD", "role", "Record Qualifier"),
                Map.of("name", "--SCAT", "role", "Variable Qualifier"),
                Map.of("name", "--SYN", "role", "Synonym Qualifier"));

        Operation op = makeOp("$nk", "natural_key_variables");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> nk = (List<String>) vars.get("$nk");
        assertNotNull(nk);
        // Natural-key roles present in the dataset are kept, in library iteration order; the
        // `--`-prefixed wildcard resolves to the LB-prefixed column.
        assertEquals(List.of("VISITNUM", "LBSPEC", "LBMETHOD", "LBSCAT"), nk);
        // Identifier / Topic are excluded even though their columns are present.
        assertFalse(nk.contains("USUBJID"));
        assertFalse(nk.contains("LBTESTCD"));
        // A natural-key role whose column is absent (LBORRES / LBSYN) is dropped.
        assertFalse(nk.contains("LBORRES"));
        assertFalse(nk.contains("LBSYN"));
    }


    @Test
    void naturalKeyVariables_nullProvider_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("USUBJID", "X").col("VISITNUM", "1").name("LB")
                .build();
        Operation op = makeOp("$nk", "natural_key_variables");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, null);
        Object result = vars.get("$nk");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void naturalKeyVariables_emptySource_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").name("LB").build();
        Provider p = new Provider();
        p.standardModelVariablesDetailed = null;
        p.domainVariables = List.of();

        Operation op = makeOp("$nk", "natural_key_variables");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$nk");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }


    @Test
    void getModelFilteredVariables_legacyPath()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "AE").name("AE").build();
        Provider p = new Provider();
        p.standardModelVariablesDetailed = null;
        p.modelVariables = List.of(Map.of("name", "--SEQ", "role", "Identifier"),
                Map.of("name", "--TERM", "role", "Topic"),
                Map.of("name", "USUBJID", "role", "Identifier"));

        Operation op = makeOp("$ids", "get_model_filtered_variables");
        op.setKeyName("role");
        op.setKeyValue("Identifier");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) vars.get("$ids");
        assertNotNull(ids);
        // --SEQ should be substituted to AESEQ; USUBJID has no wildcard
        assertTrue(ids.contains("AESEQ"));
        assertTrue(ids.contains("USUBJID"));
        // --TERM should be filtered out because it's Topic not Identifier
        assertFalse(ids.contains("AETERM"));
    }


    @Test
    void getModelFilteredVariables_emptySource_returnsEmpty()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        Provider p = new Provider();
        p.standardModelVariablesDetailed = null;
        p.modelVariables = List.of();

        Operation op = makeOp("$x", "get_model_filtered_variables");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) vars.get("$x");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // EC-85 — get_model_filtered_variables(model_class=)
    // ------------------------------------------------------------------


    @Test
    void getModelFilteredVariables_modelClass_asksTheProviderForThatClass_notTheOwnClassWalk()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "BW").name("BW").build();
        Provider p = new Provider();
        // The own-class walk would answer FINDINGS; it must NOT be consulted.
        p.standardModelVariablesDetailed = List.of(Map.of("name", "BWTESTCD", "role", "Topic"));
        p.standardModelVariablesForClass = List.of(Map.of("name", "BWTERM", "role", "Topic"),
                Map.of("name", "BWDECOD", "role", "Synonym Qualifier"));

        Operation op = makeOp("$events", "get_model_filtered_variables");
        op.setModelClass("EVENTS");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals("EVENTS", p.lastModelClassAsked);
        assertEquals(List.of("BWTERM", "BWDECOD"), vars.get("$events"));
    }


    @Test
    void getModelFilteredVariables_modelClass_composesWithTheRoleFilter()
    {
        IDataTable table = MockTable.of().col("DOMAIN", "BW").name("BW").build();
        Provider p = new Provider();
        p.standardModelVariablesForClass = List.of(Map.of("name", "BWTERM", "role", "Topic"),
                Map.of("name", "BWDECOD", "role", "Synonym Qualifier"),
                Map.of("name", "BWSEV", "role", "Record Qualifier"));

        Operation op = makeOp("$events_topic", "get_model_filtered_variables");
        op.setModelClass("EVENTS");
        op.setKeyName("role");
        op.setKeyValue("Topic");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("BWTERM"), vars.get("$events_topic"));
    }


    @Test
    void getModelFilteredVariables_modelClass_legacyFallbackIsTheClassKeyedMap_withPrefixSubstitution()
    {
        // Resolver says library-not-available (null) → the CLASS-keyed harness map answers, and
        // its unsubstituted `--` names take the dataset's prefix. The DOMAIN-keyed map must be
        // ignored: it would answer a different question.
        IDataTable table = MockTable.of().col("DOMAIN", "BW").name("BW").build();
        Provider p = new Provider();
        p.standardModelVariablesForClass = null;
        p.modelVariables = List.of(Map.of("name", "--TESTCD", "role", "Topic"));
        p.modelVariablesForClass = List.of(Map.of("name", "--TERM", "role", "Topic"),
                Map.of("name", "STUDYID", "role", "Identifier"));

        Operation op = makeOp("$events", "get_model_filtered_variables");
        op.setModelClass("EVENTS");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("BWTERM", "STUDYID"), vars.get("$events"));
    }


    @Test
    void getModelFilteredVariables_modelClass_unservedClass_isLibraryNotAvailable()
    {
        // D-6: with model_class set, "nothing" is a SKIP, never an empty set a membership leaf
        // could silently never-fire against.
        IDataTable table = MockTable.of().col("DOMAIN", "BW").name("BW").build();
        Provider p = new Provider();
        p.standardModelVariablesForClass = null;
        p.modelVariablesForClass = List.of();

        Operation op = makeOp("$events", "get_model_filtered_variables");
        op.setModelClass("EVENTS");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$events");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());

        // ...and an EMPTY (non-null) resolver answer is the same SKIP.
        p.standardModelVariablesForClass = List.of();
        vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals("<library not available>", String.valueOf(vars.get("$events")));
    }


    @Test
    void getModelFilteredVariables_withoutModelClass_emptySourceStaysAnEmptyList_theOldArm()
    {
        // The D-6 gate must NOT move the four shipped own-class rules: no model_class ⇒ the
        // own-class walk is asked, the class-keyed accessors are never consulted, and an empty
        // source is still List.of() — exactly what getModelFilteredVariables_emptySource pins.
        IDataTable table = MockTable.of().col("DOMAIN", "BW").name("BW").build();
        Provider p = new Provider();
        p.standardModelVariablesDetailed = null;
        p.modelVariables = List.of();
        p.standardModelVariablesForClass = List.of(Map.of("name", "BWTERM", "role", "Topic"));
        p.modelVariablesForClass = List.of(Map.of("name", "--TERM", "role", "Topic"));

        Operation op = makeOp("$x", "get_model_filtered_variables");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of(), vars.get("$x"));
        assertNull(p.lastModelClassAsked, "no model_class ⇒ the class accessors are never asked");
    }


    @Test
    void getModelColumnOrder_emptyList_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("X", "1").name("XYZ").build();
        Provider p = new Provider();
        p.standardModelVariables = List.of(); // empty → translates to LIBRARY_NOT_AVAILABLE

        Operation op = makeOp("$cols", "get_model_column_order");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$cols");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void getModelColumnOrder_nullList_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("X", "1").name("XYZ").build();
        Provider p = new Provider();
        p.standardModelVariables = null; // explicit null

        Operation op = makeOp("$cols", "get_model_column_order");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$cols");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void getModelColumnOrder_returnsListFromProvider()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        Provider p = new Provider();
        p.standardModelVariables = List.of("STUDYID", "USUBJID", "AESEQ");

        Operation op = makeOp("$cols", "get_model_column_order");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"), vars.get("$cols"));
    }

    // -----------------------------------------------------------------------
    // EC-13 variable_names / EC-14 layer (i) standard_domains — library mirrors
    // -----------------------------------------------------------------------


    @Test
    void variableNames_returnsUnionFromProvider()
    {
        IDataTable table = MockTable.of().col("QNAM", "AESEQ").name("SUPPAE").build();
        Provider p = new Provider();
        p.standardVariableNames = List.of("STUDYID", "USUBJID", "AESEQ", "AETERM");

        Operation op = makeOp("$variable_names", "variable_names");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM"), vars.get("$variable_names"));
    }


    @Test
    void variableNames_emptyEnumeration_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("QNAM", "AESEQ").name("SUPPAE").build();
        Provider p = new Provider();
        p.standardVariableNames = List.of(); // empty ⇒ SKIP (never a `$`-ref against empty set)

        Operation op = makeOp("$variable_names", "variable_names");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$variable_names");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void variableNames_nullEnumeration_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("QNAM", "AESEQ").name("SUPPAE").build();
        Provider p = new Provider();
        p.standardVariableNames = null; // no product / degraded ⇒ SKIP

        Operation op = makeOp("$variable_names", "variable_names");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals("<library not available>", vars.get("$variable_names").toString());
    }


    @Test
    void variableNames_nullProvider_returnsLibraryNotAvailable()
    {
        IDataTable table = MockTable.of().col("QNAM", "AESEQ").name("SUPPAE").build();
        Operation op = makeOp("$variable_names", "variable_names");

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, null);
        assertEquals("<library not available>", vars.get("$variable_names").toString());
    }


    @Test
    void standardDomains_returnsUnionFromProvider()
    {
        IDataTable table = MockTable.of().col("SRCDOM", "DM").name("RELREC").build();
        Provider p = new Provider();
        p.standardDatasetNames = List.of("DM", "AE", "LB", "RELREC");

        Operation op = makeOp("$sdtm_domains", "standard_domains");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("DM", "AE", "LB", "RELREC"), vars.get("$sdtm_domains"));
    }


    @Test
    void standardDomains_emptyEnumeration_returnsLibraryNotAvailable()
    {
        // CRITICAL guard — an empty domain set must SKIP, else `SRCDOM is_not_contained_by
        // $sdtm_domains` fires on every populated SRCDOM in a degraded run.
        IDataTable table = MockTable.of().col("SRCDOM", "DM").name("RELREC").build();
        Provider p = new Provider();
        p.standardDatasetNames = List.of();

        Operation op = makeOp("$sdtm_domains", "standard_domains");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        Object result = vars.get("$sdtm_domains");
        assertNotNull(result);
        assertEquals("<library not available>", result.toString());
    }


    @Test
    void standardDomains_nullEnumeration_returnsLibraryNotAvailable()
    {
        // e.g. an ADaM run (no SDTM product) returns null until P5b.
        IDataTable table = MockTable.of().col("SRCDOM", "DM").name("RELREC").build();
        Provider p = new Provider();
        p.standardDatasetNames = null;

        Operation op = makeOp("$sdtm_domains", "standard_domains");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals("<library not available>", vars.get("$sdtm_domains").toString());
    }


    @Test
    void expectedVariables_returnsListFromProvider()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        Provider p = new Provider();
        p.expectedVariables = List.of("AESTDTC");

        Operation op = makeOp("$ev", "expected_variables");
        Map<String, Object> vars = OperationExecutor.execute(List.of(op), table, NO_RESOLVER, p);
        assertEquals(List.of("AESTDTC"), vars.get("$ev"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }

    /**
     * Configurable {@link MetadataProvider} stub used by the library-op tests above. Fields are set
     * per-test; defaults are empty/null so unused operations produce empty results.
     */
    private static final class Provider implements MetadataProvider
    {

        List<String> requiredVariables = List.of();

        List<String> expectedVariables = List.of();

        List<String> columnOrder = List.of();

        List<String> modelColumnOrder = List.of();

        boolean domainCustom = false;

        List<String> codelistTerms = List.of();

        List<String> codelistAttribute = List.of();

        Map<String, String> variableMetadata = Map.of();

        List<Map<String, String>> domainVariables = List.of();

        List<Map<String, String>> modelVariables = List.of();

        List<String> publishedCtPackages = List.of();

        Map<String, String> datasetMetadata = Map.of();

        boolean codelistExtensible = false;

        Map<String, String> codelistTermMappings = Map.of();

        String standard;

        String version;

        List<String> standardModelVariables = null;

        List<Map<String, String>> standardModelVariablesDetailed = null;

        List<String> standardVariableNames = null;

        List<String> standardDatasetNames = null;

        @Override
        public List<String> getRequiredVariables(String d)
        {
            return requiredVariables;
        }


        @Override
        public List<String> getExpectedVariables(String d)
        {
            return expectedVariables;
        }


        @Override
        public List<String> getColumnOrder(String d)
        {
            return columnOrder;
        }


        @Override
        public List<String> getModelColumnOrder(String d)
        {
            return modelColumnOrder;
        }


        @Override
        public boolean isDomainCustom(String d)
        {
            return domainCustom;
        }


        @Override
        public List<String> getCodelistTerms(String c)
        {
            return codelistTerms;
        }


        @Override
        public List<String> getCodelistAttribute(String ctPackageId, String ctAttribute)
        {
            return codelistAttribute;
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            return variableMetadata;
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String d)
        {
            return domainVariables;
        }


        @Override
        public List<Map<String, String>> getModelVariables(String d)
        {
            return modelVariables;
        }

        /** EC-85 — recorded so a test can assert WHICH class the executor asked for. */
        String lastModelClassAsked;

        @Nullable
        List<Map<String, String>> standardModelVariablesForClass;

        List<Map<String, String>> modelVariablesForClass = List.of();

        @Override
        public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable t,
                DatasetResolver r, String aModelClass)
        {
            lastModelClassAsked = aModelClass;
            return standardModelVariablesForClass;
        }


        @Override
        public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
        {
            lastModelClassAsked = aModelClass;
            return modelVariablesForClass;
        }


        @Override
        public List<String> getPublishedCtPackages()
        {
            return publishedCtPackages;
        }


        @Override
        public Map<String, String> getDatasetMetadata(String d)
        {
            return datasetMetadata;
        }


        @Override
        public boolean isCodelistExtensible(String cl)
        {
            return codelistExtensible;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String cl)
        {
            return codelistTermMappings;
        }


        @Override
        public String getStandard()
        {
            return standard;
        }


        @Override
        public String getVersion()
        {
            return version;
        }


        @Override
        public List<String> getStandardModelVariables(IDataTable t, DatasetResolver r)
        {
            return standardModelVariables;
        }


        @Override
        public List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable t,
                DatasetResolver r)
        {
            return standardModelVariablesDetailed;
        }


        @Override
        public List<String> getStandardVariableNames()
        {
            return standardVariableNames;
        }


        @Override
        public List<String> getStandardDatasetNames()
        {
            return standardDatasetNames;
        }
    }

    // Silence unused-import warnings when these are not directly referenced.
    @SuppressWarnings("unused")
    private static void unused(Optional<?> o, LinkedHashMap<?, ?> m, Collections c)
    {
        // no-op
    }
}
