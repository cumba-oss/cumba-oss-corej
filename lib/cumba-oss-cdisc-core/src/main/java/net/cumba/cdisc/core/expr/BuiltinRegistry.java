package net.cumba.cdisc.core.expr;

import java.util.Set;

/**
 * The closed set of engine-provided built-in reference names recognised in expression operand
 * position. Seeded from a read-only audit of all 12 shipped rule packages (see
 * {@code plans/PLAN-expression-rule-format.md}, Appendix A): every {@code name}/{@code value} token
 * that is lowercase-leading or underscore-containing and is <em>not</em> a column/wildcard resolves
 * to one of these.
 *
 * <p>
 * The registry is intentionally <em>closed</em>: an operand that looks built-in (lowercase or
 * underscore) but is not listed here is a {@link ExpressionException}, not a silent unknown. This
 * is what turns the legacy "looks like a literal" trap (e.g. {@code library_variable_role}) into a
 * checked reference.
 * </p>
 *
 * <p>
 * 34 distinct names across three metadata levels (data / define / library):
 * </p>
 * <ul>
 * <li><b>data</b> — {@code dataset_*} (name, label, class, metadata) and {@code variable_*} (name,
 * value, label, data_type, format, length)</li>
 * <li><b>library</b> — {@code library_dataset_*} (name, label, class) and
 * {@code library_variable_*} (label, data_type, role, name, core, codelist, ordinal, length)</li>
 * <li><b>define</b> — {@code define_dataset_*} (name, label, class), {@code define_variable_*} (the
 * same 8 scalars as library) plus the Tier-B codelist operands {@code ccode} /
 * {@code codelist_coded_codes} (registered but not yet populated; see
 * PLAN-coreJ-codelist-conformance.md)</li>
 * </ul>
 * <p>
 * {@code data_type} is the canonical alias of the provider's {@code simpleDatatype}.
 * </p>
 */
public final class BuiltinRegistry
{

    private static final Set<String> BUILTINS = Set.of(
            // dataset-level metadata: data level (dataset_*) + the define / library levels.
            // dataset_domain is the dataset's CDISC domain as Scope.Domains resolves it
            // (MetadataAttribute.DS_DOMAIN -> OperationExecutor.unsplitNameFromData); it lowers to
            // ds_domain("DATA") through MetadataOperandMapping's `dataset_` prefix and is
            // dataset-constant, so a Check written against it folds to ONE finding per dataset
            // instead of one per record.
            "dataset_name", "dataset_label", "dataset_class", "dataset_domain", "dataset_metadata",
            "record_count", "library_dataset_name", "library_dataset_label",
            "library_dataset_class", "define_dataset_name", "define_dataset_label",
            "define_dataset_class",
            // variable-level metadata
            "variable_name", "variable_value", "variable_label", "variable_data_type",
            "variable_format", "variable_length",
            // T5b length facts (Variable Metadata Check): the Python variables-metadata frame's
            // declared length (variable_size) and max-actual stored length (variable_max_size).
            // Mapped to var_length(…,"DATA") / max_value_length(…) by MetadataOperandMapping.
            "variable_size", "variable_max_size",
            // CDISC Library comparison values (the "library" level). All are populated from the
            // provider's getVariableMetadata; data_type is the canonical alias of simpleDatatype.
            "library_variable_label", "library_variable_data_type", "library_variable_role",
            "library_variable_name", "library_variable_core", "library_variable_codelist",
            "library_variable_ordinal", "library_variable_length", "library_variable_ccode",
            // NRI-008 / CT-004 (PLAN-value-check-against-library-codelist): the library
            // codelist's published submission values / concept ids and extensibility flag.
            // Auto-mapped to var_codelist_coded_values / var_codelist_coded_codes /
            // var_codelist_extensible ("LIBRARY") by the library_variable_ prefix; populated by
            // the library provider. Mirrors the Python library-metadata builder columns.
            "library_variable_codelist_coded_values", "library_variable_codelist_coded_codes",
            "library_variable_codelist_extensible",
            // Plan 2 R11 — the SDTM carry-over lane. NAME-keyed (not domain-keyed) distinct
            // published values from the COMPANION product, each a distinct
            // (label, simpleDatatype) pair. Absent when the name is published nowhere, which is
            // the rule's not-applicable row. ⛔ There is deliberately no *_format_values: the
            // CDISC Library publishes no format and no length.
            "library_variable_label_values", "library_variable_data_type_values",
            // Define-XML metadata (the "define" level) — scalar surface symmetric with library_*.
            // The codelist operands (ccode / codelist_coded_codes) are Tier B (see
            // PLAN-coreJ-codelist-conformance.md): registered but not yet populated.
            "define_variable_label", "define_variable_data_type", "define_variable_role",
            "define_variable_name", "define_variable_core", "define_variable_codelist",
            "define_variable_ordinal", "define_variable_length", "define_variable_ccode",
            "define_variable_codelist_coded_codes",
            // DEFINE-only variable metadata (E2, plans/PLAN-group-b-followups.md): Origin Type,
            // Comment/Method presence flags, and the bound codelist's external-dictionary
            // name/version. Auto-mapped to var_origin_type / var_has_comment / var_has_method /
            // var_external_dictionary(_version) by the define_variable_ prefix.
            "define_variable_origin_type", "define_variable_has_comment",
            "define_variable_has_method", "define_variable_external_dictionary",
            "define_variable_external_dictionary_version",
            // EC-19 (Value Check against Define XML Variable, SD0037): the variable-level ItemDef
            // codelist guard + enumerated coded values. Auto-mapped to var_has_codelist /
            // var_codelist_coded_values by the define_variable_ prefix. Mirrors the Python
            // define_variable_has_codelist / define_variable_codelist_coded_values columns.
            "define_variable_has_codelist", "define_variable_codelist_coded_values",
            // GLOB-CT-005 variant (PLAN-coreJ-codelist-conformance Phase 3): the define
            // codelist's sponsor-extended (def:ExtendedValue="Yes") coded values. Auto-mapped to
            // var_codelist_extended_values by the define_variable_ prefix.
            "define_variable_codelist_extended_values",
            // Define-XML value-level metadata (VLM, Value Check against Define XML VLM). The row
            // value's stored length + each per-record value-level fact; mapped to
            // len(variable_value)
            // / vlm_*(varname()) by MetadataOperandMapping. Mirrors the Python VLM frame columns.
            "variable_value_length", "define_vlm_data_type", "define_vlm_length",
            "define_vlm_mandatory", "define_vlm_codelist_coded_values",
            "define_vlm_codelist_coded_codes", "define_vlm_type_conforms",
            "define_vlm_codelist_extensible", "define_vlm_has_codelist",
            "define_vlm_decode_matches",
            // E9 (plans/PLAN-group-b-followups): library-level paired code/decode concept-id match
            // (FDA-CT2003 / PMDA-CT2003). Mapped to library_variable_code_pair_matches(varname())
            // by MetadataOperandMapping; mirrors the Python library-metadata builder column.
            "library_variable_code_pair_matches",
            // Fix #123: the variable-level Define-XML paired code/decode match (DRAFT-900025).
            // Mapped to a varname()-anchored call of the same name by MetadataOperandMapping;
            // Engine extension; see
            // corej-cdisc-rules/documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md §9.
            "define_variable_decode_matches");

    private BuiltinRegistry()
    {
    }


    public static boolean isBuiltin(String name)
    {
        return BUILTINS.contains(name);
    }


    public static Set<String> names()
    {
        return BUILTINS;
    }

}
