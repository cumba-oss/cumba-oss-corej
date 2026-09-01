package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuiltinRegistryTest
{

    @Test
    void knownBuiltinsRecognised()
    {
        assertTrue(BuiltinRegistry.isBuiltin("variable_name"));
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_role"));
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_name"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_codelist_coded_codes"));
        // EC-19 (Value Check against Define XML Variable, SD0037): variable-level ItemDef codelist
        // guard + enumerated coded values.
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_has_codelist"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_codelist_coded_values"));
        assertTrue(BuiltinRegistry.isBuiltin("dataset_class"));
        // define-level scalar surface (symmetric with library_*)
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_label"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_data_type"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_name"));
        // extra scalars registered for both levels (core / codelist / ordinal)
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_core"));
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_codelist"));
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_ordinal"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_core"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_codelist"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_ordinal"));
        // dataset-level define / library operands
        assertTrue(BuiltinRegistry.isBuiltin("library_dataset_label"));
        assertTrue(BuiltinRegistry.isBuiltin("library_dataset_name"));
        assertTrue(BuiltinRegistry.isBuiltin("library_dataset_class"));
        assertTrue(BuiltinRegistry.isBuiltin("define_dataset_label"));
        assertTrue(BuiltinRegistry.isBuiltin("define_dataset_name"));
        assertTrue(BuiltinRegistry.isBuiltin("define_dataset_class"));
        // declared length at each level
        assertTrue(BuiltinRegistry.isBuiltin("variable_length"));
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_length"));
        assertTrue(BuiltinRegistry.isBuiltin("define_variable_length"));
    }


    @Test
    void unknownNotRecognised()
    {
        assertFalse(BuiltinRegistry.isBuiltin("DTHFL"));
        assertFalse(BuiltinRegistry.isBuiltin("foo_bar"));
        assertFalse(BuiltinRegistry.isBuiltin(""));
    }


    @Test
    void closedSetSize()
    {
        // data (10 variable/dataset) + library (3 dataset + 8 variable) + define (3 dataset + 10
        // variable) = 34 (PLAN-coreJ-cdisc-provider; three-level data/define/library surface,
        // variable_length registered at each level) + record_count (R-P2,
        // PLAN-native-engine-residuals: the dataset-fold fact, canonicalized to the
        // record_count() builtin at install time) = 35, + variable_size / variable_max_size (Fix
        // #75, T5b: the Python variables-metadata length facts mapped to var_length("DATA") /
        // max_value_length()) = 37, + the 6 Define-XML value-level (VLM) operands
        // (variable_value_length + define_vlm_data_type / _length / _mandatory /
        // _codelist_coded_values / _codelist_coded_codes; PLAN-engine-vlm) = 43, +
        // define_vlm_type_conforms (SD1230 datatype conformance) = 44, +
        // define_vlm_codelist_extensible (CT2004/CT2005) = 45, + define_vlm_has_codelist
        // (SD0037/SD1228/CT2004/CT2005 empty-codelist guard) = 46, + define_vlm_decode_matches
        // (CT2006 code/decode pairing) = 47, + library_variable_ccode (Fix #82, B6: var_ccode at
        // LIBRARY for CDISC-SEND-0055) = 48, + the 5 E2 DEFINE-only Define-XML metadata operands
        // (define_variable_origin_type / _has_comment / _has_method / _external_dictionary /
        // _external_dictionary_version; PLAN-group-b-followups) = 53, +
        // library_variable_code_pair_matches (E9, FDA-CT2003/PMDA-CT2003: library-level paired
        // code/decode concept-id match) = 54, + the 2 EC-19 variable-level ItemDef codelist
        // operands
        // (define_variable_has_codelist / define_variable_codelist_coded_values; SD0037,
        // PLAN-rule-review-engine-changes §20) = 56, + the 3 library codelist operands
        // (library_variable_codelist_coded_values / _coded_codes / _extensible; NRI-008/CT-004,
        // PLAN-value-check-against-library-codelist Phase 5b) = 59, +
        // define_variable_codelist_extended_values (GLOB-CT-005 variant,
        // PLAN-coreJ-codelist-conformance Phase 3) = 60, + define_variable_decode_matches
        // (Fix #123, DRAFT-900025: variable-level Define-XML paired code/decode match) = 61, +
        // dataset_domain (the Scope.Domains base-leg fact, lowered to ds_domain("DATA")) = 62, +
        // library_variable_label_values / _data_type_values (Plan 2 R11, the NAME-keyed SDTM
        // carry-over lane: distinct (label, simpleDatatype) pairs from the companion product) = 64.
        // ⛔ There is deliberately NO library_variable_format_values — the CDISC Library publishes
        // no format and no length, measured across variables_metadata.pkl and
        // standards_details.pkl.
        assertEquals(64, BuiltinRegistry.names().size());
    }

}
