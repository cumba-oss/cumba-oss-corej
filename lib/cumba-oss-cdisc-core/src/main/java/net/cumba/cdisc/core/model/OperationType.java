package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
@Getter
public enum OperationType
{

    CODELIST_TERMS("codelist_terms", EmptyResult.SET),
    /**
     * The evaluated dataset's CDISC domain as {@code Scope.Domains} resolves it — the row-0
     * {@code DOMAIN} cell verbatim, else {@code SUPP}/{@code SQ} + row-0 {@code RDOMAIN}, else the
     * raw dataset name ({@code OperationExecutor.unsplitNameFromData}). The Operations carriage of
     * the {@code dataset_domain} bareword; both are registered, exactly as {@code record_count} is
     * registered in this enum <em>and</em> in {@code BuiltinRegistry}. A scalar broadcast, so a
     * Check consuming it decides once per dataset.
     *
     * <p>
     * {@link EmptyResult#MISSING} because the derivation has no "nothing matched" arm to give a
     * zero/empty answer for: it always yields the domain, and the only null it can produce is the
     * degenerate no-table case.
     * </p>
     */
    DATASET_DOMAIN("dataset_domain", EmptyResult.MISSING),
    DATASET_NAMES("dataset_names", EmptyResult.SET),
    DISTINCT("distinct", EmptyResult.SET),
    DOMAIN_IS_CUSTOM("domain_is_custom", EmptyResult.PREDICATE),
    DY("dy", EmptyResult.MISSING),
    EXPECTED_VARIABLES("expected_variables", EmptyResult.SET),
    EXTRACT_METADATA("extract_metadata", EmptyResult.MISSING),
    GET_CODELIST_ATTRIBUTES("get_codelist_attributes", EmptyResult.SET),
    GET_COLUMN_ORDER_FROM_DATASET("get_column_order_from_dataset", EmptyResult.SET),
    GET_COLUMN_ORDER_FROM_LIBRARY("get_column_order_from_library", EmptyResult.SET),
    GET_DATASET_FILTERED_VARIABLES("get_dataset_filtered_variables", EmptyResult.SET),
    GET_MODEL_COLUMN_ORDER("get_model_column_order", EmptyResult.SET),
    GET_MODEL_FILTERED_VARIABLES("get_model_filtered_variables", EmptyResult.SET),
    GET_PARENT_MODEL_COLUMN_ORDER("get_parent_model_column_order", EmptyResult.SET),
    MAX("max", EmptyResult.MISSING),
    /**
     * The latest / earliest ISO date of the {@code name} column — study-wide, or per {@code group}
     * key when {@code group} is set — over the {@code domain} dataset, honouring {@code filter}.
     *
     * <p>
     * Two parameters shape which cells take part, and they answer different questions:
     * </p>
     * <ul>
     * <li>a <b>present but unpositionable</b> value (a year-masked {@code 2012}, a junk
     * {@code UNK}, a structurally-invalid date) makes the extreme undeterminable and the operation
     * emits no value for that group. That is EC-46 and it is <em>not</em> declarable — a value the
     * engine cannot place on a calendar can never be shown to win;</li>
     * <li>a <b>missing</b> (empty) cell is skipped by default, and
     * {@link Operation#getMissingValues missing_values} {@code : "indeterminate"} routes it into
     * the same no-value outcome instead (EC-51 Half B). ⚠ Declaring it only reports through a
     * <em>negative</em> consuming leaf: a {@code date_greater_than} / {@code date_less_than} /
     * {@code equal_to} consumer reads "no value" as "no violation", so the declaration would
     * silence the very check it is meant to sharpen. That combination is rejected at load.</li>
     * </ul>
     */
    MAX_DATE("max_date", EmptyResult.MISSING),
    /** The earliest ISO date of the {@code name} column. See {@link #MAX_DATE}. */
    MIN_DATE("min_date", EmptyResult.MISSING),
    MINUS("minus", EmptyResult.SET),
    RECORD_COUNT("record_count", EmptyResult.COUNT),
    REQUIRED_VARIABLES("required_variables", EmptyResult.SET),
    STUDY_DOMAINS("study_domains", EmptyResult.SET),
    VALID_CODELIST_DATES("valid_codelist_dates", EmptyResult.SET),
    VARIABLE_COUNT("variable_count", EmptyResult.COUNT),
    /**
     * Column presence as a <em>reportable</em> {@code $}-result: {@code true} when the {@code name}
     * column is present on the evaluated dataset — or, when {@code domain} is set, on that foreign
     * dataset (falling back to a {@code SUPP<domain>.QNAM} pivot, exactly as the dotted
     * {@code var_exists("D.X")} form does). A dataset-level boolean, broadcast to every row.
     *
     * <p>
     * ⚠⚠ <b>This operation is the reporting carriage of the {@code var_exists(X)} check function,
     * not a second verdict surface.</b> An earlier {@code VARIABLE_EXISTS} operation was retired in
     * favour of that function (see {@code plans/done/PLAN-variable-exists-cross-dataset.md})
     * because its evaluator did a literal {@code getOptionalColumn} with a skip-on-missing negation
     * bug and had neither the dotted cross-dataset surface nor {@code ${…}} per-row resolution.
     * Nothing about that judgement is reversed here: the Check keeps saying {@code var_exists(X)},
     * and this operation exists so a rule that wants to <em>report</em> the answer can declare
     * {@code $X} in {@code Outcome.Output_Variables} and have a value materialise there. Its
     * evaluator is written to agree with {@code OperatorRegistry.existsAsVariable} /
     * {@code existsAsDottedDatasetColumn} — the same facts the function reads — so the reported
     * value can never contradict the verdict that was reported alongside it.
     * </p>
     *
     * <p>
     * {@link EmptyResult#PREDICATE} because the question always has an answer: a column that is not
     * there is {@code false}, never "unknown". That totality is the property the retired operation
     * lacked.
     * </p>
     */
    VARIABLE_EXISTS("variable_exists", EmptyResult.PREDICATE),
    VARIABLE_VALUE_COUNT("variable_value_count", EmptyResult.COUNT),

    /** Returns the {@code name} field of the operation as a literal string value. */
    CONSTANT("constant", EmptyResult.MISSING),

    /**
     * Resolves a metadata field (label, data_type, length, format) for each variable from another
     * dataset. Returns a {@link net.cumba.cdisc.core.exec.VariableMetadataResult} that resolves
     * per-variable during variable-level evaluation.
     * <p>
     * Requires {@code domain} (target dataset name) and {@code name} (metadata field).
     */
    CROSS_DATASET_VARIABLE_METADATA("cross_dataset_variable_metadata", EmptyResult.MISSING),

    /** Returns the dataset class name from the CDISC Library (e.g., "BASIC DATA STRUCTURE"). */
    DATASET_CLASS_FROM_LIBRARY("dataset_class_from_library", EmptyResult.MISSING),

    /**
     * Fix #26: per-group "mixed emptiness" boolean. For each block defined by {@code group},
     * returns {@code true} when at least one row has the {@code name} column populated AND at least
     * one row has it unpopulated. Used by CDISC-AD0735 / CDISC-AD0131 to detect inconsistent
     * BASETYPE populations within PARAMCD groups. Result shape:
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the group columns, value
     * {@code Boolean}.
     */
    HAS_MIXED_EMPTINESS_WITHIN_GROUP("has_mixed_emptiness_within_group", EmptyResult.PREDICATE),

    /**
     * T5a — per-variable all-null. Returns {@code true} when the {@code name} variable is absent
     * from the target dataset, or is present but empty (null / "") for every record. Mirrors the
     * Python reference engine's {@code variable_is_null} operation
     * ({@code (series.isnull() | (series == "")).all()}), which treats an absent column as null.
     * Dataset-level boolean, broadcast to every row. Backs FDA SD1078 (Permissible variable empty
     * for all records) / SD1149 (Expected variable empty for all records).
     */
    VARIABLE_IS_NULL("variable_is_null", EmptyResult.PREDICATE),

    /**
     * T7 — TS/TX-parameter scalar lookup. Resolves a single parameter value (the {@code name}
     * column, e.g. {@code TSVAL}) from a parameter dataset ({@code domain}, default {@code "TS"})
     * for the row whose {@code key_name} column (e.g. {@code TSPARMCD}) equals {@code key_value}
     * (e.g. {@code EXPSTDTC}). Returns that first matching row's value as a broadcast scalar,
     * usable directly as the {@code value} operand of a date / comparison operator. Returns
     * {@code null} when the parameter dataset is absent, the {@code key_name}/{@code name} column
     * is absent, or no row matches — so the dependent comparison operand is null and the rule
     * effectively SKIPs (no row fires), mirroring the Python reference engine's
     * {@code ts_parameter_value} operation ({@code df[target].iloc[0]} or {@code None}). Backs FDA
     * SE1148–SE1151 (EX dates vs the TS experimental window {@code EXPSTDTC}/{@code EXPENDTC}) and
     * the TS/TX-parameter presence half of SEND105/106.
     */
    TS_PARAMETER_VALUE("ts_parameter_value", EmptyResult.MISSING),

    /**
     * T8 — SUPP-- QNAM-scoped presence join to the parent record. For each record of the primary
     * (parent) findings/interventions/events dataset, returns {@code true} when the supplemental
     * qualifier dataset ({@code domain}, e.g. {@code "SUPPPC"}) carries at least one row whose
     * {@code QNAM} equals {@code key_value} (e.g. {@code "PCCALCN"}) and whose {@code IDVAR}/{@code
     * IDVARVAL} resolve to that parent record — i.e. {@code parent[IDVAR] == IDVARVAL} within the
     * same {@code USUBJID}. The result is a per-parent-row
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by {@code USUBJID} + the resolved
     * {@code IDVAR} column (default {@code false} for a parent record with no matching supplemental
     * row), so a comparison operand reads a per-row boolean. When the supplemental dataset is
     * present but carries no {@code QNAM} column or no row matching {@code key_value}, every parent
     * record resolves to {@code false} (the required qualifier is genuinely absent). When the
     * supplemental dataset is entirely absent from the study the operation is unresolvable and the
     * rule SKIPs (no row fires), the same "absent domain ⇒ skip" contract as {@code
     * ts_parameter_value}. Backs FDA SE2234 (a BLQ/BQL PC record must carry a SUPPPC {@code
     * PCCALCN} numeric-interpretation record) and CDISC-SEND-0330 (SUPPPC {@code CALCN}).
     */
    SUPP_QNAM_PRESENT("supp_qnam_present", EmptyResult.PREDICATE),

    /**
     * T8 — SUPP-- QNAM-scoped value join to the parent record. Like {@link #SUPP_QNAM_PRESENT} but
     * exposes the matching {@code QVAL} joined back to each parent record (a per-parent-row
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by {@code USUBJID} + the resolved
     * {@code IDVAR} column, default {@code null}). Resolves to {@code null} for a parent record
     * with no matching supplemental row, and broadcasts {@code null} when the supplemental dataset
     * / {@code QNAM} is absent — so a dependent comparison operand is null and no row fires.
     */
    SUPP_QNAM_VALUE("supp_qnam_value", EmptyResult.MISSING),

    /**
     * T1 — dictionary-availability skip-gate. Returns {@code true} when a dictionary of the {@code
     * external_dictionary_type} is loaded into the runtime
     * {@link net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider}, {@code false} otherwise.
     * Mirrors the CDISC-Library skip-gate, and is <b>one of the two halves</b> that realise it: the
     * native converter injects a {@code dictionary_available(<type>)} Precondition for every
     * <b>inlined</b> dictionary operation, while a <b>declared</b> ({@code $}-ref) one — the form
     * the entire shipped corpus uses — is caught by {@code RuleRunner}'s eager dictionary arm
     * (KDICT-F1 / {@code Fix #268}). Either way the rule SKIPs (never false-PASSes) when no
     * dictionary of that type is supplied. &#9888; That eager arm deliberately <b>excludes</b> this
     * operation: it <em>is</em> the gate, and unlike the validating operations its own result is
     * well defined with no provider ({@code false}), so skipping on it would destroy the very
     * reporting it exists for. Also exposed as the {@code dictionary_available} builtin gate
     * function consumed by the native fold.
     */
    DICTIONARY_AVAILABLE("dictionary_available", EmptyResult.PREDICATE),

    /**
     * T1 — per-record dictionary term membership. For each record, returns {@code true} when the
     * {@code name} column value is a valid term of the {@code external_dictionary_type} dictionary
     * at the {@code dictionary_term_type} level (e.g. MedDRA {@code PT}), {@code false} otherwise.
     * Mirrors the Python reference engine's {@code valid_external_dictionary_value} operation.
     * Backs FDA SD0008 ({@code --DECOD} in MedDRA) / SD1344 ({@code --DECOD} in WHODrug) — the
     * Check tests {@code == false} to flag records carrying a value absent from the dictionary.
     * Dictionary- dependent: the rule SKIPs when the type is not loaded.
     */
    VALID_EXTERNAL_DICTIONARY_VALUE("valid_external_dictionary_value", EmptyResult.PREDICATE),

    /**
     * T1 — per-record dictionary code membership. Like {@link #VALID_EXTERNAL_DICTIONARY_VALUE} but
     * validates a coded value (e.g. {@code --DECOD}'s companion code column) against the
     * dictionary's codes. Mirrors the Python {@code valid_external_dictionary_code} operation.
     */
    VALID_EXTERNAL_DICTIONARY_CODE("valid_external_dictionary_code", EmptyResult.PREDICATE),

    /**
     * T1 — per-record dictionary code&harr;decode pairing. For each record, returns {@code true}
     * when the code in the {@code name} column decodes (in the {@code external_dictionary_type}
     * dictionary) to the term in the {@code external_dictionary_term_variable} column. Mirrors the
     * Python {@code valid_external_dictionary_code_term_pair} operation. Backs FDA SD2262
     * (TSVAL/TSVALCD from the same FDA-SRS/UNII record) and the NEOPLASM benign/malignant attribute
     * alignment SE2229 ({@code --STRESC} neoplasm term vs {@code --RESCAT} malignancy class).
     */
    VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR("valid_external_dictionary_code_term_pair", EmptyResult.PREDICATE),

    /**
     * T1 — per-record dictionary hierarchy-path membership. For each record, returns {@code true}
     * when the value of the {@code name} (child) column lies on the dictionary hierarchy path of —
     * i.e. has as an ancestor — the value of the {@code dictionary_parent} column (in the
     * {@code external_dictionary_type} dictionary). A blank child OR blank parent value is treated
     * as {@code true} (no fire), mirroring the blank-handling of
     * {@link #VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR}. Backs the MedDRA hierarchy-consistency
     * rules (e.g. {@code --HLT} vs {@code --SOC}). Mirrors the Python reference engine's
     * {@code valid_external_dictionary_hierarchy} operation. Dictionary-dependent: the rule SKIPs
     * when the type is not loaded.
     */
    VALID_EXTERNAL_DICTIONARY_HIERARCHY("valid_external_dictionary_hierarchy", EmptyResult.PREDICATE),

    /**
     * The current dataset's library variables whose SDTM {@code role} is one of the
     * natural-key-forming roles ({@code Timing}, {@code Record Qualifier}, {@code Variable
     * Qualifier}, {@code Result Qualifier}, {@code Synonym Qualifier}, {@code Grouping Qualifier}),
     * intersected with the columns actually present in the dataset. Deliberately excludes the
     * {@code Identifier} and {@code Topic} roles ({@code USUBJID} / {@code --TESTCD} are added
     * explicitly by the consuming rule). Resolved through the same class-aware standard-variable
     * source as {@link #GET_DATASET_FILTERED_VARIABLES} (IG-base + Model-merge, with the legacy
     * {@code getDomainVariables} fallback and {@code --}-prefix resolution). Returns a
     * {@code List<String>}. Library-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isLibraryDependent}): the rule SKIPs when
     * no CDISC Library provider is configured. Used to build a per-domain uniqueness key for
     * Findings duplicate detection —
     * {@code is_not_unique_set(USUBJID, value=["--TESTCD", "$natural_key"])} — to back FDA SD1117 /
     * PMDA SD1117. Stays a {@code $}-ref Operation (never inlined into a function), like
     * {@link #GET_DATASET_FILTERED_VARIABLES} / {@link #DEFINE_VARIABLE_NAMES}.
     */
    NATURAL_KEY_VARIABLES("natural_key_variables", EmptyResult.SET),

    /**
     * T2-residual — the set of variable NAMES the sponsor Define-XML declares for the current
     * domain (the domain's {@code ItemGroupDef} {@code ItemRef}s resolved to their {@code ItemDef}
     * names). Read from the Define provider ({@code MetadataProvider.getColumnOrder(domain)} over
     * the {@code DefineXmlMetadataProvider} / {@code OdmDefineXMLProvider}). Returns a
     * {@code List<String>}. Define-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isDefineDependent}): the rule SKIPs (never
     * PASS/FAIL) when no Define-XML is supplied. Diffed against the data variable set
     * ({@code get_column_order_from_dataset}) via {@code not_contains_all} to back FDA SD0054 (a
     * variable declared in the Define but absent from the data).
     */
    DEFINE_VARIABLE_NAMES("define_variable_names", EmptyResult.SET),

    /**
     * T2-residual — the set of dataset (domain) NAMES the sponsor Define-XML declares for the study
     * (the {@code MetaDataVersion}'s {@code ItemGroupDef} names). Read from the Define provider
     * ({@code MetadataProvider.getDatasetNames()} over the {@code DefineXmlMetadataProvider} /
     * {@code OdmDefineXMLProvider}). Returns a {@code List<String>}. Unlike
     * {@link #DEFINE_VARIABLE_NAMES}, it is <em>not</em> domain-scoped — it returns every dataset
     * the Define declares. Define-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isDefineDependent}): the rule SKIPs (never
     * PASS/FAIL) when no Define-XML is supplied. Stays a {@code $}-ref Operation (never inlined
     * into a function), like {@link #DEFINE_VARIABLE_NAMES}. Mirrors the Python reference engine's
     * {@code define_dataset_names} operation.
     */
    DEFINE_DATASET_NAMES("define_dataset_names", EmptyResult.SET),

    /**
     * T2-residual — the sponsor Define-XML KEY variable names for the current domain, ordered by
     * the {@code ItemRef} {@code KeySequence} attribute. Read from the Define provider
     * ({@code MetadataProvider.getKeyVariables(domain)} over the {@code DefineXmlMetadataProvider}
     * / {@code OdmDefineXMLProvider}). Returns a {@code List<String>}. Define-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isDefineDependent}): the rule SKIPs when
     * no Define-XML is supplied. Consumed as a {@code $}-ref key member of
     * {@code is_not_unique_set} (the proven CDISC-CG0562 ref-key pattern) to back PMDA SD1152
     * (records not unique on the Define key set).
     */
    DEFINE_KEY_VARIABLES("define_key_variables", EmptyResult.SET),

    /**
     * EC-13 — the union of variable NAMES across every dataset the run's IG standard defines. Java
     * mirror of the Python reference engine's {@code variable_names} operation (which reads
     * {@code variables_metadata.pkl}). Resolved by walking the loaded SDTM IG product
     * ({@code SdtmClass.datasets() → SdtmDataset.datasetVariables() → name()}) into an
     * order-preserving union. Returns a {@code List<String>}. Library-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isLibraryDependent}): the rule SKIPs when
     * no CDISC Library provider is configured, and an empty/absent enumeration maps to the
     * {@code LIBRARY_NOT_AVAILABLE} sentinel (never an empty list — a {@code $}-ref membership
     * against an empty set would misfire). Consumed as a membership right-hand side
     * ({@code QNAM is_contained_by $variable_names}) and stays a {@code $}-ref Operation — never
     * inlined into a function (not in {@code OperationInliner.isListReturningOperation}), matching
     * {@link #GET_PARENT_MODEL_COLUMN_ORDER}. Backs SEND-0274-1 (a SUPP-- QNAM that collides with a
     * variable defined in another domain or the SDTM).
     */
    VARIABLE_NAMES("variable_names", EmptyResult.SET),

    /**
     * EC-14 layer (i) — the canonical union of standard dataset (domain) NAMES: the IG standard's
     * datasets unioned with the SDTM Model product's datasets. Java mirror of the Python reference
     * engine's {@code standard_domains} operation ({@code standard ∪ model dataset_names}). Returns
     * a {@code List<String>}. Library-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isLibraryDependent}): the rule SKIPs when
     * no CDISC Library provider is configured, and an empty/absent enumeration maps to the
     * {@code LIBRARY_NOT_AVAILABLE} sentinel — the empty-enumeration guard is critical, else
     * {@code SRCDOM is_not_contained_by $sdtm_domains} would fire on every populated SRCDOM in a
     * degraded run. Consumed as a membership right-hand side and stays a {@code $}-ref Operation
     * (never inlined). On an ADaM run it returns {@code []} (⇒ SKIP) until the P5b
     * companion-SDTM-version run parameter (EC-14 layer (ii)) lands. Backs AD0180 (a SRCDOM that is
     * not a standard SDTM domain).
     */
    STANDARD_DOMAINS("standard_domains", EmptyResult.SET),

    /**
     * E1 — the CDISC-Library observation class of the domain <em>named in a column value</em>. For
     * each record, reads the domain code in the {@code name} column (default {@code RDOMAIN}) and
     * resolves that arbitrary domain's class through the Library
     * ({@code MetadataProvider.getDatasetClass(dom, dom)} — e.g. {@code EVENTS} /
     * {@code INTERVENTIONS} / {@code FINDINGS} / {@code FINDINGS ABOUT}). Result shape:
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the {@code name} column, value the
     * resolved class string ({@code ""} when the Library cannot classify the referenced domain).
     * Unlike {@link #DATASET_CLASS_FROM_LIBRARY} (which resolves only the <em>current</em> table's
     * class), this classifies a domain named in the data. Library-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isLibraryDependent}): the rule SKIPs when
     * no CDISC Library provider is configured. Backs FDA-SD0095 / PMDA-SD0095 (a SUPPQUAL
     * {@code RDOMAIN} must reference a general-observation-class domain).
     */
    REFERENCED_DOMAIN_CLASS("referenced_domain_class", EmptyResult.EMPTY_TEXT),

    /**
     * E6 — ISO-8601 interval-of-uncertainty precision comparator. For each record, when the
     * {@code name} column value contains the {@code delimiter} (default {@code "/"}), splits it
     * into the begin/end halves and returns {@code true} (fires) when the two halves carry a
     * <em>different</em> ISO-8601 precision tier
     * ({@link net.cumba.cdisc.core.exec.ScalarSemantics#detectIsoPrecision}, measured after each
     * half has had its timezone offset and fractional seconds stripped — the comparison is of the
     * <em>representation</em>, so a UTC offset on one side only is not a precision difference).
     * Returns {@code false} (no fire) when the value carries no delimiter or either half is blank.
     * Result shape: {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the {@code name}
     * column. Backs CDISC-SEND-0070 (a {@code --DTC} interval of uncertainty whose begin/end
     * precisions differ).
     */
    INTERVAL_UNCERTAINTY_PRECISION_MISMATCH("interval_uncertainty_precision_mismatch", EmptyResult.PREDICATE),

    /**
     * E8 — WHODrug decode-presence precondition. For each record, returns {@code true} when the
     * {@code external_dictionary_type} dictionary holds <em>any</em> decode for the code in the
     * {@code name} column (a {@code containsKey} over the dictionary's {@code pairs} / {@code
     * attributes} registries — the code&harr;decode-<em>equality</em> of
     * {@link #VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR} minus the decode match). A blank value ⇒
     * {@code false} (no fire). Result shape: {@link net.cumba.cdisc.core.exec.GroupedResult} keyed
     * by the {@code name} column. Dictionary-dependent (see
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#isDictionaryDependent}): an <b>inlined</b>
     * use gets a {@code dictionary_available(<type>)} precondition from the native converter, a
     * <b>declared</b> ({@code $}-ref) use is caught by {@code RuleRunner}'s eager dictionary arm
     * ({@code Fix #268}) — either way the rule SKIPs when no dictionary of that type is loaded.
     * Backs CDISC-CG0096 (the precondition "CMTRT has a decode value in WHODrug").
     */
    DICTIONARY_HAS_DECODE("dictionary_has_decode", EmptyResult.PREDICATE),

    /**
     * E10 — split-family declared-length divergence. Groups the current table's split family (every
     * available dataset whose data-driven unsplit name —
     * {@link net.cumba.cdisc.core.exec.OperationExecutor#unsplitNameFromData} — equals the current
     * table's) and returns the list of variable NAMES whose declared column length
     * ({@code DataTableColumnMeta.getLength()}) differs across the split-family members (a variable
     * present in ≥2 members with ≥2 distinct lengths). Returns an empty list when the dataset is
     * not split or every shared variable has a uniform length. Library-INDEPENDENT: needs only a
     * {@link net.cumba.cdisc.core.exec.DatasetResolver.WithInventory} to enumerate the family.
     * Returns a {@code List<String>}, consumed as a membership right-hand side
     * ({@code variable_name is_contained_by $result}).
     */
    SPLIT_SIBLING_LENGTH_MISMATCH("split_sibling_length_mismatch", EmptyResult.SET),

    /**
     * E7 — duplicate variable-label detection. Groups the current table's columns by their declared
     * label ({@link net.cumba.datatable.DataTableColumnMeta#getLabel()}) and returns the list of
     * variable NAMES whose label bucket holds more than one variable (the labels shared by ≥2
     * columns). Returns an empty list when every label is unique. Library-INDEPENDENT — reads only
     * the current table's column metadata. Returns a {@code List<String>}, consumed as a membership
     * right-hand side ({@code variable_name is_contained_by $result}) in a Variable Metadata Check.
     * Backs CDISC-SEND-0273 (a variable label is not unique within the dataset).
     */
    DUPLICATE_LABEL_VARIABLES("duplicate_label_variables", EmptyResult.SET),

    /**
     * E7 — numbered column-series completeness / continuation check (e.g. the {@code COVAL1..n}
     * comment-value series). Selects the series members from the current table's columns — every
     * column whose name matches the {@code name_pattern} regex (e.g. {@code ^COVAL\d+$}), plus the
     * optional un-numbered {@code name} base column (e.g. {@code COVAL}, taken as suffix 0) when it
     * exists. The trailing integer suffix of each member is parsed, and the operation returns a
     * dataset-level {@code Boolean} broadcast to every row that is {@code true} ("series
     * incomplete", i.e. the check fires) when either:
     * <ul>
     * <li>the present numeric suffixes are <em>not contiguous</em> from the lowest present to the
     * highest present (a gap — e.g. {@code COVAL1}, {@code COVAL3} with {@code COVAL2} missing);
     * or</li>
     * <li>the optional {@code min_length} is set and a <em>non-terminal</em> member (any member
     * other than the highest-suffix one) declares a length
     * ({@link net.cumba.datatable.DataTableColumnMeta#getLength()}) below {@code min_length} — the
     * "a continuation column must be full before the next is used" rule (e.g. {@code COVAL} must be
     * 200 chars before {@code COVAL1} is populated).</li>
     * </ul>
     * Returns {@code false} (no fire) when fewer than two members are present (no series to check).
     * The completeness / continuation logic is folded inside the operation so the Check tests it
     * with the existing {@code equal_to true} operator — no bespoke {@code is_incomplete_series} /
     * {@code longer_than_or_equal_to} comparator is introduced. Library-INDEPENDENT. Backs
     * CDISC-SEND-0119 (COVAL series completeness) / CDISC-SEND-0313 (COVAL length continuation).
     */
    COLUMN_SERIES_METADATA("column_series_metadata", EmptyResult.PREDICATE),

    /**
     * E3 — cross-domain / two-column per-record date arithmetic (days-between). For each record,
     * computes the integer number of calendar days between two ISO-8601 dates via
     * {@link java.time.temporal.ChronoUnit#DAYS}{@code .between(subtrahend, minuend)} —
     * <em>without</em> the {@code +1} SDTM study-day convention of {@link #DY} — plus an
     * {@code offset}. Two modes, selected by whether {@code domain} + {@code group} are set:
     * <ul>
     * <li><b>Mode 1 (record-linked diff):</b> {@code name} is the minuend date column and
     * {@code reference} is the subtrahend date column, both read from the <em>same</em> record. The
     * value is {@code DAYS.between(record[reference], record[name]) + offset}.</li>
     * <li><b>Mode 2 (grouped cross-domain reference):</b> when {@code domain} and {@code group} are
     * set, the subtrahend is a grouped extreme of the {@code reference} column within the
     * {@code group} key, sourced from the foreign {@code domain} dataset, joined back to each
     * record by the {@code group} key. The {@code reference_extreme} param selects which extreme:
     * the <em>earliest</em> ({@code "min"}, the default) or the <em>latest</em> ({@code "max"})
     * value, and {@link Operation#getMissingValues missing_values} governs whether a missing
     * candidate in that foreign column is skipped (the default) or makes the group's subtrahend
     * undeterminable (EC-51 Half B) — Mode 2 only, since Mode 1's subtrahend is a same-record read
     * and a missing one already yields no value for that row. The value is
     * {@code DAYS.between(extreme(domain[reference]) over group, record[name]) +
     * offset}.</li>
     * </ul>
     * {@code offset} is either an integer literal (default {@code 0}) or the name of a per-record
     * integer column. Result shape: {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the
     * columns that determine the per-row value ({@code name} + the {@code reference} column in mode
     * 1, {@code name} + the {@code group} columns in mode 2, plus the {@code offset} column when it
     * is a column), value the {@code Long} day count. A record whose minuend/subtrahend date is
     * missing or shorter than 10 chars (no parseable {@code yyyy-MM-dd}) is omitted, so the key is
     * absent and the row reads the declared {@link EmptyResult#MISSING} — which the comparison
     * folds to {@code ""}, and a {@code not_equal_to} against a populated target <b>fires</b>. ⚠
     * EC-45 corrected the pre-2026-08 claim that this "skips that row": nothing skips. Mode 2
     * likewise returns {@code null} — again <em>not</em> a SKIP — when the {@code domain} dataset
     * or the {@code reference} column is absent, or when no {@code group} column is present on
     * either side. Whole-operation applicability (the foreign dataset or its reference column not
     * being submitted at all) belongs to {@code Scope.Variables}, including its qualified
     * {@code DATASET.VARIABLE} form, where it is a visible and countable SKIP. Consumed as a
     * {@code $}-ref compared to the target var (e.g. {@code --RPDY not_equal_to $rpref_day}). Backs
     * CDISC-SEND-0202..-0205 and -0401..-0403. Mirrors the Python reference engine's
     * {@code operations/date_diff_days.py}.
     */
    DATE_DIFF_DAYS("date_diff_days", EmptyResult.MISSING),

    /**
     * E4 — per-record last-record-in-group flag. Partitions the current table's rows by the
     * {@code group} key, orders each partition by the {@code ordering} column (the shared
     * {@link net.cumba.cdisc.core.exec.GroupSemantics#sortByOrderColumn} string ordering used by
     * the other record-ordering operators), and returns {@code true} for the last
     * (maximum-ordering) row of each group, {@code false} for every other row. Result shape:
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the {@code group} columns plus the
     * {@code ordering} column (so each {@code (group, ordering)} tuple resolves to its own
     * boolean), default {@code false} for a row whose group key has a missing component.
     * Library-INDEPENDENT — reads only the current table. Consumed as {@code $is_last == true}
     * combined (via {@code Match_Datasets}, which joins on the match keys such as {@code USUBJID})
     * with a cross-domain disposition-record scalar, e.g.
     * {@code SEENDTC date_less_than_or_equal_to DS.DSSTDTC}. {@code Match_Datasets} has no
     * {@code filter} field today, so narrowing the join to a specific disposition record (e.g. a
     * particular {@code DSDECOD}) is a documented residual, not something the join can express.
     * Backs CDISC-SEND-0127 / -0283. Mirrors the Python reference engine's
     * {@code operations/is_last_in_group.py}.
     */
    IS_LAST_IN_GROUP("is_last_in_group", EmptyResult.PREDICATE),

    /**
     * EC-8 — per-record horizontal maximum over a wildcard column set. For each record, collects
     * the populated cell values of every column whose name fully matches the {@code name_pattern}
     * regex (e.g. {@code ^TR(0[1-9]|[1-9][0-9])EDT$} for {@code TR01EDT..TR99EDT}), skipping
     * missing / blank cells, and returns the extreme value. Two-mode ordering: when every populated
     * value parses as a finite number the numeric maximum is returned; otherwise the plain
     * lexicographic maximum of the raw strings (correct for same-precision ISO-8601 dates). The
     * winning <em>original cell string</em> is returned so a downstream {@code not_equal_to} /
     * numeric-aware comparison behaves naturally. Result shape:
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the matched columns themselves (the
     * DY per-row-resolution precedent); a row with no populated matching cell is omitted (absent
     * key ⇒ {@code null} ⇒ the dependent comparison skips it), and an empty pattern or no matching
     * column yields {@code null} (the rule SKIPs). Library-INDEPENDENT. Mirrors the Python
     * reference engine's {@code operations/row_extreme.py} {@code RowMax}. Backs CDISC-AD0084 (the
     * latest {@code TRxxEDT} vs {@code TRTEDT}).
     */
    ROW_MAX("row_max", EmptyResult.MISSING),

    /**
     * EC-8 — per-record horizontal minimum over a wildcard column set. The {@code min} counterpart
     * of {@link #ROW_MAX}: identical column matching ({@code name_pattern}), blank-skipping and
     * two-mode ordering (numeric when all populated values parse as finite numbers, else plain
     * lexicographic), returning the extreme <em>original cell string</em>. Result shape:
     * {@link net.cumba.cdisc.core.exec.GroupedResult} keyed by the matched columns. Mirrors the
     * Python reference engine's {@code operations/row_extreme.py} {@code RowMin}.
     */
    ROW_MIN("row_min", EmptyResult.MISSING);

    @JsonValue
    private final String jsonValue;

    /**
     * EC-45 — what this operator publishes when it has no answer for a row (no group matched, no
     * record satisfied the filter, the library holds nothing for the looked-up name). Mandatory:
     * the generated constructor takes it, so operator #55 cannot be added without choosing one
     * rather than copying whichever {@code GroupedResult} constructor sat next door. See
     * {@link EmptyResult}.
     */
    private final EmptyResult emptyResult;

    private static final Map<String, OperationType> LOOKUP = Stream.of(values())
            .collect(Collectors.toMap(OperationType::getJsonValue, Function.identity()));

    @JsonCreator
    public static @Nullable OperationType fromJson(@Nullable String value)
    {
        return LOOKUP.get(value);
    }


    /**
     * EC-45 — the declared empty-result <em>value</em> of {@code type}, i.e. the absent-group-key
     * default a {@link net.cumba.cdisc.core.exec.GroupedResult} built for this operation carries. A
     * {@code null} {@code type} (an operation whose {@code operator} did not parse) has no
     * declaration and falls back to {@link EmptyResult#MISSING} — "no answer", the safest reading
     * for something the engine could not identify.
     */
    public static @Nullable Object emptyValueOf(@Nullable OperationType type)
    {
        return (type != null ? type.emptyResult : EmptyResult.MISSING).value();
    }

}
